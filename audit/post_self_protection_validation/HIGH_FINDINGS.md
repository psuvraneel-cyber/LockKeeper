# High Security Findings

**Project:** LockKeeper (C:\AppLocker)  
**Phase:** Post-Self-Protection Adversarial Validation  
**Date:** 2026-09-17  
**Validator:** Agent 15 (Lead Security Validation Engineer)

---

## FINDING-HIGH-01 — Concurrent Password Verification TOCTOU Defeats 5-Attempt Rate Limiting

**Severity:** HIGH  
**Confidence:** CONFIRMED (Statically Proven via Suspending Function Analysis)  
**Category:** Concurrency / Rate-Limiting Race Condition  

**Affected Files:**  
- `android/app/src/main/kotlin/com/lockkeeper/app/security/TamperAuthorizationController.kt`  

**Affected Functions:**  
- `TamperAuthorizationController.verifyAdminPassword(enteredPassword: String)` (lines 90–123)  

**Exact Evidence:**  
`TamperAuthorizationController.kt:90-115`:
```kotlin
suspend fun verifyAdminPassword(enteredPassword: String): AdminAuthResult {
    val now = wallClockTimeProvider()
    val settings = appSettingsDao.getSettings() ?: AppSettingsEntity()

    val lockoutUntil = settings.adminLockoutUntil
    if (lockoutUntil != null && lockoutUntil > now) { ... }

    val isValid = credentialStore.verifyAdminPassword(enteredPassword) // Heavy PBKDF2: 50-150ms!
    if (isValid) { ... } else {
        val newAttempts = settings.failedAdminAttempts + 1
        val isLockedOut = newAttempts >= MAX_FAILED_ADMIN_ATTEMPTS
        val newLockoutUntil = if (isLockedOut) now + ADMIN_LOCKOUT_DURATION_MS else null

        appSettingsDao.updateAdminLockout(newAttempts, newLockoutUntil, now)
        ...
    }
}
```

**Root Cause:**  
`verifyAdminPassword` is a coroutine suspending function with zero synchronization or mutex locking. It reads the current failure count from SQLite, executes PBKDF2 with 65,536 iterations (~100ms on mobile CPUs), and then performs a non-atomic write of `settings.failedAdminAttempts + 1`. Multiple concurrent calls interleave during the 100ms hashing window.

**Attack Scenario:**  
An attacker scripts 10 concurrent requests to `PlatformBridge.verifyAdminPassword` or double-taps the overlay submit button. All 10 threads read `failedAdminAttempts = 0`. All 10 threads compute PBKDF2. All 10 threads compute `newAttempts = 1`. All 10 threads write `1` to the database. 10 failed guesses result in a recorded attempt count of only 1.

**Preconditions:**  
Multiple concurrent requests submitted within ~100ms of each other.

**Impact:**  
Rate-limiting resistance is significantly degraded, allowing high-throughput brute-force attacks.

**Recommended Remediation Direction:**  
Wrap `verifyAdminPassword` inside a Kotlin `Mutex` and increment counters atomically in SQLite via `UPDATE app_settings SET failedAdminAttempts = failedAdminAttempts + 1`.

---

## FINDING-HIGH-02 — Window Type Discrepancy: Uses `TYPE_APPLICATION_OVERLAY` Instead of Claimed `TYPE_ACCESSIBILITY_OVERLAY`

**Severity:** HIGH  
**Confidence:** CONFIRMED (Statically Proven via Source Inspection)  
**Category:** Window Security / Platform Capability Discrepancy  

**Affected Files:**  
- `android/app/src/main/kotlin/com/lockkeeper/app/overlay/OverlayManager.kt`  
- `audit/SELF_PROTECTION_IMPLEMENTATION_REPORT.md`  

**Affected Functions:**  
- `OverlayManager.createLayoutParams()` (lines 51–72)  

**Exact Evidence:**  
`OverlayManager.kt:52-57`:
```kotlin
val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
} else {
    @Suppress("DEPRECATION")
    WindowManager.LayoutParams.TYPE_PHONE
}
```
`audit/SELF_PROTECTION_IMPLEMENTATION_REPORT.md:129, 183`:
> *"Uses high-priority TYPE_ACCESSIBILITY_OVERLAY with FLAG_SECURE... Overlays use the system Accessibility overlay token (TYPE_ACCESSIBILITY_OVERLAY), decoupled from MainActivity's window token."*

**Root Cause:**  
The implementation claims to use the privileged `TYPE_ACCESSIBILITY_OVERLAY` token tied to the accessibility service, but the code actually instantiates `TYPE_APPLICATION_OVERLAY` via the system WindowManager.

**Attack / Failure Scenario:**  
1. `TYPE_APPLICATION_OVERLAY` requires `android.permission.SYSTEM_ALERT_WINDOW`. If this permission is revoked, window addition throws an exception and no overlay can be shown.
2. `TYPE_APPLICATION_OVERLAY` cannot draw over system-level alert dialogs or privileged system popups.
3. On MIUI / HyperOS, `TYPE_APPLICATION_OVERLAY` requires a secondary permission ("Display pop-up windows while running in the background") which is DENIED by default on physical Xiaomi devices.
4. On Android 12+, `TYPE_APPLICATION_OVERLAY` is blocked by untrusted touch filtering when covering dangerous system buttons.

**Impact:**  
Failure to display overlays over system-layer dialogs; complete breakdown on OEM devices that restrict application overlays.

**Recommended Remediation Direction:**  
Obtain the WindowManager directly from the `AccessibilityService` context or utilize `TYPE_ACCESSIBILITY_OVERLAY` if window token permits, or enforce strict verification of overlay drawing permissions.

---

## FINDING-HIGH-03 — Complete Exclusion of Third-Party Launchers and Alternative Package Managers from Detection

**Severity:** HIGH  
**Confidence:** CONFIRMED (Statically Proven via Package Filter Inspection)  
**Category:** Tamper Route Bypass  

**Affected Files:**  
- `android/app/src/main/kotlin/com/lockkeeper/app/security/TamperDetectionEngine.kt`  

**Affected Functions:**  
- `TamperDetectionEngine.evaluate(...)` (lines 62–90)  

**Exact Evidence:**  
`TamperDetectionEngine.kt:74-89`:
```kotlin
// 1. Settings Evaluation
if (packageName == "com.android.settings") {
    return evaluateSettings(className, lowerClass, rootNode, isDeviceAdminActive)
}

// 2. Package Installer Evaluation
if (isPackageInstaller(packageName)) {
    return evaluatePackageInstaller(packageName, className, lowerClass, rootNode)
}

// 3. OEM Security Center Evaluation
if (isOemSecurityCenter(packageName)) {
    return evaluateOemSecurityCenter(packageName, className, lowerClass, rootNode)
}

return null
```

**Root Cause:**  
`TamperDetectionEngine.evaluate` returns `null` for any package outside `com.android.settings`, `PACKAGE_INSTALLER_PACKAGES`, and 3 OEM security centers.

**Attack Scenario:**  
A user installs a third-party launcher (Nova Launcher, Niagara, Lawnchair, Smart Launcher, Microsoft Launcher). The user long-presses LockKeeper and selects "Uninstall" using the launcher's built-in uninstall manager. Because `packageName` is the launcher package, LockKeeper returns `null`. The uninstallation proceeds without challenge.

**Impact:**  
Trivial bypass of anti-tamper via any popular third-party launcher.

**Recommended Remediation Direction:**  
Detect uninstallation intent broadcasts or inspect active launcher windows when uninstallation confirmation dialogs appear.

---

## FINDING-HIGH-04 — Conflation of OS Accessibility Setting with Live Service Connection Violates Truth-in-Security

**Severity:** HIGH  
**Confidence:** CONFIRMED (Statically Proven via IPC Method Analysis)  
**Category:** State Model / UI Deception Hazard  

**Affected Files:**  
- `android/app/src/main/kotlin/com/lockkeeper/app/bridge/PlatformChannelHandler.kt`  
- `lib/ui/screens/home_screen.dart`  

**Affected Functions:**  
- `PlatformChannelHandler.checkPermission("accessibility")` (lines 244–251)  

**Exact Evidence:**  
`PlatformChannelHandler.kt:244-251`:
```kotlin
"accessibility" -> {
    val isServiceConnected = com.lockkeeper.app.service.LockKeeperAccessibilityService.isConnected
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: ""
    isServiceConnected || enabledServices.contains(context.packageName)
}
```

**Root Cause:**  
The method uses an `OR` operator between `isServiceConnected` and `enabledServices.contains(...)`. If the user enabled LockKeeper in Settings, but the background service has been killed by Android's low-memory killer or battery optimizer, `enabledServices` is still true.

**Attack / Failure Scenario:**  
1. Service dies in background due to battery optimization.
2. User opens LockKeeper.
3. Flutter UI displays green checkmarks: "All Protections Active" and "Protection Operational".
4. In reality, the service is dead. No accessibility events are received. Apps are not locked. Tamper attempts are not intercepted.

**Impact:**  
Users are deceived into believing their applications are secured when no protection is running. Direct violation of Invariant 10.

**Recommended Remediation Direction:**  
Require `isServiceConnected == true` for operational status. If the service is enabled in settings but disconnected, report explicit state: `DEGRADED (Service Disconnected)`.

---

## FINDING-HIGH-05 — Dropping `TYPE_WINDOW_CONTENT_CHANGED` Blindfolds LockKeeper to Jetpack Compose, Scrolling, and Tabbed Settings

**Severity:** HIGH  
**Confidence:** CONFIRMED (Statically Proven via Accessibility Configuration Inspection)  
**Category:** Event Processing / Detection Blindspot  

**Affected Files:**  
- `android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperAccessibilityService.kt`  
- `android/app/src/main/res/xml/accessibility_service_config.xml`  

**Affected Functions:**  
- `LockKeeperAccessibilityService.onServiceConnected()` (lines 46–57)  
- `LockKeeperAccessibilityService.onAccessibilityEvent(event: AccessibilityEvent?)` (lines 69–72)  

**Exact Evidence:**  
`LockKeeperAccessibilityService.kt:50`:
```kotlin
eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
```
`LockKeeperAccessibilityService.kt:70`:
```kotlin
if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
    return
}
```
`audit/SELF_PROTECTION_IMPLEMENTATION_REPORT.md:138`:
> *- Event Filtering: Pre-filters for TYPE_WINDOW_STATE_CHANGED and TYPE_WINDOW_CONTENT_CHANGED.*

**Root Cause:**  
The implementation deliberately filters only `TYPE_WINDOW_STATE_CHANGED` and discards `TYPE_WINDOW_CONTENT_CHANGED`, contradicting its own documentation.

**Attack / Failure Scenario:**  
In single-activity architectures (such as `SpaActivity` in AOSP Settings or dynamic fragments), scrolling, expanding accordions, or navigating between tabs (e.g. from General to Storage) generates `TYPE_WINDOW_CONTENT_CHANGED` rather than `TYPE_WINDOW_STATE_CHANGED`. LockKeeper receives zero events when these content changes occur, failing to detect when the user scrolls down to tap "Clear storage" or "Force stop".

**Impact:**  
Silent bypass of anti-tamper detection on modern single-activity settings layouts.

**Recommended Remediation Direction:**  
Subscribe to `TYPE_WINDOW_CONTENT_CHANGED` with debounced content evaluation (e.g. 250ms rate limit) specifically scoped to system management packages.

---

## FINDING-HIGH-06 — Asymmetric Dual Grace Window Timers (Monotonic vs Wall-Clock) Create Desynchronization Bypass

**Severity:** HIGH  
**Confidence:** CONFIRMED (Statically Proven via State Engine Inspection)  
**Category:** Security Architecture / Dual Timer Desync  

**Affected Files:**  
- `android/app/src/main/kotlin/com/lockkeeper/app/domain/LockDecisionEngine.kt`  
- `android/app/src/main/kotlin/com/lockkeeper/app/security/TamperAuthorizationController.kt`  
- `android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperAccessibilityService.kt`  

**Affected Functions:**  
- `LockDecisionEngine.grantAdminGraceWindow(...)` (lines 27–33)  
- `TamperAuthorizationController.grantGraceWindow()` (lines 49–55)  
- `LockKeeperAccessibilityService.handleTamperEvent(...)` (lines 157–161)  

**Exact Evidence:**  
`LockDecisionEngine.kt:25, 27-29`:
```kotlin
@Volatile private var adminGraceUntil: Long = 0L
fun grantAdminGraceWindow(currentTime: Long = System.currentTimeMillis()) {
    adminGraceUntil = currentTime + ADMIN_GRACE_WINDOW_MS
}
fun isAdminGraceActive(currentTime: Long = System.currentTimeMillis()) = currentTime < adminGraceUntil
```
`TamperAuthorizationController.kt:39, 49-51`:
```kotlin
@Volatile private var adminGraceUntilElapsed: Long = 0L
fun grantGraceWindow() {
    adminGraceUntilElapsed = monotonicTimeProvider() + ADMIN_GRACE_WINDOW_MS
}
fun isGraceActive() = monotonicTimeProvider() < adminGraceUntilElapsed
```
`LockKeeperAccessibilityService.kt:157`:
```kotlin
if (repository.tamperController.isGraceActive() || repository.decisionEngine.isAdminGraceActive())
```

**Root Cause:**  
Two independent classes maintain dual grace window timers using conflicting time sources (wall-clock `currentTimeMillis` vs monotonic `elapsedRealtime`). When `tamperController.revokeGraceWindow()` is called, `decisionEngine.adminGraceUntil` is NOT revoked.

**Attack / Failure Scenario:**  
A user enters the Admin Password, activating both timers. If the user subsequently cancels or an overlay event calls `revokeGraceWindow()`, `decisionEngine.isAdminGraceActive()` remains `true` for up to 30 wall-clock seconds. Because `handleTamperEvent` checks `isGraceActive() || isAdminGraceActive()`, anti-tamper is bypassed during the remaining window.

**Impact:**  
Grace window cannot be deterministically revoked, extending unauthorized access windows.

**Recommended Remediation Direction:**  
Remove `adminGraceUntil` from `LockDecisionEngine` entirely; establish `TamperAuthorizationController` as the sole authoritative source of grace status using monotonic time.

---

## FINDING-HIGH-07 — Missing Overlay Cleanup on Accessibility Service `onDestroy()` Causes Permanent Screen Trapping

**Severity:** HIGH  
**Confidence:** CONFIRMED (Statically Proven via Lifecycle Trace)  
**Category:** Windowing / Screen Trapping Hazard  

**Affected Files:**  
- `android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperAccessibilityService.kt`  

**Affected Functions:**  
- `LockKeeperAccessibilityService.onDestroy()` (lines 59–63)  

**Exact Evidence:**  
`LockKeeperAccessibilityService.kt:59-63`:
```kotlin
override fun onDestroy() {
    isConnected = false
    evaluationJob?.cancel()
    super.onDestroy()
}
```

**Root Cause:**  
`LockKeeperAccessibilityService.onDestroy()` resets `isConnected` and cancels jobs, but **never invokes `overlayManager.dismissAdminOverlay()` or `overlayManager.dismissIfShowing()`**.

**Attack / Failure Scenario:**  
If an overlay is visible and Android's OS terminates `LockKeeperAccessibilityService` (due to low memory, user toggling accessibility in Settings, or process crash), the overlay view remains permanently attached to the WindowManager. Because the service is dead, it can never receive password submissions or handle back actions. The device is frozen behind an unresponsive overlay.

**Impact:**  
Permanent screen lockup requiring a hard device reboot. Direct regression of clean overlay teardown requirements.

**Recommended Remediation Direction:**  
In `onDestroy()`, synchronously invoke `overlayManager.dismissIfShowing()` and `overlayManager.dismissAdminOverlay()`.

---

## FINDING-HIGH-08 — R8 / ProGuard Minification and Obfuscation Completely Disabled in Release Builds

**Severity:** HIGH  
**Confidence:** CONFIRMED (Statically Proven via Gradle Build Configuration)  
**Category:** Release Security / Code Exposure  

**Affected Files:**  
- `android/app/build.gradle.kts`  

**Exact Evidence:**  
`android/app/build.gradle.kts:55-56`:
```kotlin
buildTypes {
    release {
        ...
        isMinifyEnabled = false
        isShrinkResources = false
    }
}
```

**Root Cause:**  
Minification, code shrinking, and identifier obfuscation are explicitly turned off for the release build type.

**Attack Scenario:**  
An adversary downloads the release APK, decompiles it with `jadx-gui`, and inspects the exact package names, regexes, keywords, and node count limits of `TamperDetectionEngine.kt`. The adversary uses these exact numbers to craft reliable bypasses.

**Impact:**  
Complete exposure of security architecture, KeyStore aliases, database schemas, and bypass vectors.

**Recommended Remediation Direction:**  
Enable `isMinifyEnabled = true` with robust ProGuard rules preserving Room entities and KeyStore reflection.
