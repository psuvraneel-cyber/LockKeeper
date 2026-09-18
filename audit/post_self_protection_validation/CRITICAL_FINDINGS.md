# Critical Security Findings

**Project:** LockKeeper (C:\AppLocker)  
**Phase:** Post-Self-Protection Adversarial Validation  
**Date:** 2026-09-17  
**Validator:** Agent 15 (Lead Security Validation Engineer)

---

## FINDING-SEC-01 — Missing Room Database Row Results in Silent UPDATE Failure and Infinite Password Brute-Force (Fail-Open)

**Severity:** CRITICAL  
**Confidence:** CONFIRMED (Statically Proven via SQLite / Room DAO Query Semantics)  
**Category:** Authentication / Persistence Failure  

**Affected Files:**  
- `android/app/src/main/kotlin/com/lockkeeper/app/security/TamperAuthorizationController.kt`  
- `android/app/src/main/kotlin/com/lockkeeper/app/data/db/AppSettingsDao.kt`  
- `android/app/src/main/kotlin/com/lockkeeper/app/data/db/AppSettingsEntity.kt`  

**Affected Functions:**  
- `TamperAuthorizationController.verifyAdminPassword(enteredPassword: String)` (lines 90–123)  
- `AppSettingsDao.updateAdminLockout(attempts: Int, lockoutUntil: Long?, updatedAt: Long)` (lines 22–23)  
- `AppSettingsDao.getSettings()` (lines 10–11)  

**Exact Evidence:**  
`TamperAuthorizationController.kt:92`:
```kotlin
val settings = appSettingsDao.getSettings() ?: AppSettingsEntity()
```
`TamperAuthorizationController.kt:110-114`:
```kotlin
val newAttempts = settings.failedAdminAttempts + 1
val isLockedOut = newAttempts >= MAX_FAILED_ADMIN_ATTEMPTS
val newLockoutUntil = if (isLockedOut) now + ADMIN_LOCKOUT_DURATION_MS else null

appSettingsDao.updateAdminLockout(newAttempts, newLockoutUntil, now)
```
`AppSettingsDao.kt:22-23`:
```kotlin
@Query("UPDATE app_settings SET failedAdminAttempts = :attempts, adminLockoutUntil = :lockoutUntil, updatedAt = :updatedAt WHERE id = 1")
suspend fun updateAdminLockout(attempts: Int, lockoutUntil: Long?, updatedAt: Long = System.currentTimeMillis())
```

**Root Cause:**  
`TamperAuthorizationController` injects `appSettingsDao` directly instead of `ProtectionRepository`. If row `id = 1` does not exist in SQLite (e.g. fresh installation before opening Flutter UI, after destructive migration, or after database deletion), `getSettings()` returns `null`. The code instantiates a transient in-memory `AppSettingsEntity()`, but **never inserts it into the database**. It then executes `UPDATE app_settings SET failedAdminAttempts = :attempts ... WHERE id = 1`. In SQLite, an `UPDATE` matching zero rows executes successfully but modifies zero rows.

**Attack / Failure Scenario:**  
1. An attacker gains access to a device where LockKeeper was freshly installed or where row 1 was never created.
2. Attacker triggers the Admin Password overlay by attempting to uninstall or force-stop.
3. Attacker submits a wrong password.
4. `settings.failedAdminAttempts` is 0; `newAttempts` is calculated as 1.
5. Room executes `UPDATE ... WHERE id = 1`. Rows affected: 0.
6. Attacker submits another wrong password.
7. `appSettingsDao.getSettings()` still returns `null`. `settings.failedAdminAttempts` is still 0. `newAttempts` is still 1.
8. Rows affected: 0.
9. Attacker repeats this process millions of times. The 5-attempt limit is NEVER reached. Lockout is NEVER triggered.

**Preconditions:**  
Table `app_settings` does not contain row `id = 1`.

**Impact:**  
Complete collapse of rate limiting; infinite online brute-force vulnerability for Admin Password.

**Existing Mitigation:**  
Unit tests in `TamperAuthorizationControllerTest` used `FakeAppSettingsDao` which kept an in-memory variable `currentSettings = AppSettingsEntity()`.

**Why Mitigation Is Insufficient:**  
The fake DAO in unit tests mutated an in-memory Kotlin object via `.copy()`, concealing the SQLite `WHERE id = 1` zero-row update behavior.

**Regression Risk:**  
None. Fixing this requires inserting or upserting row `id = 1` on initialization.

**Recommended Remediation Direction:**  
Ensure row `id = 1` is inserted via `INSERT OR IGNORE` or `upsert()` before any `UPDATE`, or rewrite `updateAdminLockout` as an UPSERT / `INSERT OR REPLACE INTO app_settings`.

**Runtime Validation Required:**  
NO. Statically proven from SQLite and Room DAO query specifications.

---

## FINDING-SEC-02 — Catastrophic False Positive on Device Admin List and Cross-App Deactivation Blocking

**Severity:** CRITICAL  
**Confidence:** CONFIRMED (Statically Proven via Logic Analysis)  
**Category:** Detection / False Positive Hazard  

**Affected Files:**  
- `android/app/src/main/kotlin/com/lockkeeper/app/security/TamperDetectionEngine.kt`  

**Affected Functions:**  
- `TamperDetectionEngine.evaluateSettings(...)` (lines 133–152)  

**Exact Evidence:**  
`TamperDetectionEngine.kt:135-151`:
```kotlin
if (isDeviceAdminActive) {
    val isDeviceAdminScreen = lowerClass.contains("deviceadmin") ||
            nodeCollector.containsTextOrDesc("device admin") ||
            nodeCollector.containsTextOrDesc("administrador de dispositivos")

    val hasDeactivateAction = nodeCollector.containsAnyTextOrDesc(DEACTIVATE_ADMIN_KEYWORDS)

    if (isDeviceAdminScreen && (mentionsLockKeeper || hasDeactivateAction)) {
        return TamperEvent(
            type = TamperType.DISABLE_DEVICE_ADMIN,
            source = TamperSource.SETTINGS,
            confidence = TamperConfidence.HIGH,
            targetPackage = appPackageName,
            targetActivity = className
        )
    }
}
```

**Root Cause:**  
The boolean condition `isDeviceAdminScreen && (mentionsLockKeeper || hasDeactivateAction)` uses an `OR` operator between `mentionsLockKeeper` and `hasDeactivateAction`.

**Attack / Failure Scenario:**  
- **Scenario A (General List Blocking):** User opens `Settings -> Security -> Device Admin Apps` to inspect system security. The list shows all installed admin apps, including LockKeeper. Because LockKeeper is in the list, `mentionsLockKeeper` is TRUE. Because the screen title is "Device admin apps", `isDeviceAdminScreen` is TRUE. The engine returns `DISABLE_DEVICE_ADMIN` on LockKeeper! `LockKeeperAccessibilityService` immediately shows the Admin Overlay! If the user taps Cancel, `performGlobalAction(GLOBAL_ACTION_BACK)` forces the user out. **The user cannot even view their list of Device Admin apps.**
- **Scenario B (Unrelated Admin Blocking):** User opens another Device Admin app (e.g. Google Find My Device, Microsoft Intune) to deactivate it. The screen displays "Deactivate this device admin app", making `hasDeactivateAction` TRUE. Because of the `||` operator, `mentionsLockKeeper` is NOT required. The engine returns `TamperEvent` targeting `com.lockkeeper.app`! LockKeeper blocks the deactivation of Google Find My Device or Microsoft Intune!

**Preconditions:**  
Device Admin is active in LockKeeper (`isDeviceAdminActive == true`).

**Impact:**  
Severe denial of service for general Android settings; unauthorized interference with unrelated third-party applications; violation of Invariant 7.

**Existing Mitigation:**  
`TamperDetectionEngineTest.kt:189-208` tests only a synthetic node containing both "LockKeeper" and "Deactivate this device admin app". It never tested browsing the admin list or deactivating an unrelated app.

**Why Mitigation Is Insufficient:**  
The test suite tested only the happy path, masking the logical defect.

**Regression Risk:**  
High risk of breaking Device Admin deactivation interception if not carefully scoped to LockKeeper's specific deactivation component and view target.

**Recommended Remediation Direction:**  
Require BOTH `isDeviceAdminScreen && mentionsLockKeeper && hasDeactivateAction`, and specifically verify that the deactivation target is LockKeeper rather than an arbitrary admin component.

**Runtime Validation Required:**  
NO. Statically proven from Boolean logic.

---

## FINDING-SEC-03 — Arbitrary DFS Depth (<=6) and Node (<=45) Limits Prune Uninstallation Action Buttons (False Negative / Total Bypass)

**Severity:** CRITICAL  
**Confidence:** CONFIRMED (Statically Proven via NodeCollector Algorithm Analysis)  
**Category:** Detection / Bypass Vulnerability  

**Affected Files:**  
- `android/app/src/main/kotlin/com/lockkeeper/app/security/TamperDetectionEngine.kt`  

**Affected Functions:**  
- `TamperDetectionEngine.NodeCollector.traverse(node: NodeFacade, depth: Int)` (lines 280–298)  

**Exact Evidence:**  
`TamperDetectionEngine.kt:16-17`:
```kotlin
private const val MAX_TRAVERSAL_DEPTH = 6
private const val MAX_TRAVERSED_NODES = 45
```
`TamperDetectionEngine.kt:285-298`:
```kotlin
fun traverse(node: NodeFacade, depth: Int) {
    if (depth > MAX_TRAVERSAL_DEPTH || count >= MAX_TRAVERSED_NODES) return
    count++

    node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { texts.add(it.lowercase()) }
    node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { texts.add(it.lowercase()) }
    node.viewIdResourceName?.let { viewIds.add(it.lowercase()) }

    for (i in 0 until node.childCount) {
        if (count >= MAX_TRAVERSED_NODES) break
        val child = node.getChild(i) ?: continue
        traverse(child, depth + 1)
    }
}
```

**Root Cause:**  
The traversal algorithm uses Depth-First Search (DFS) with a hard cutoff at `depth > 6` and `count >= 45`. Modern Android Settings architectures (such as `SpaActivity` in Android 13/14, Samsung One UI, and Xiaomi HyperOS) feature deep component trees where root -> decor -> actionbar -> appbar -> coordinator -> scrollview -> recyclerview alone occupies 6 levels. Children inside card items or sub-fragments reside at depth 7 or 8. Furthermore, top action bars, search chips, and banner widgets routinely consume more than 45 nodes before the main content list is traversed.

**Attack / Failure Scenario:**  
1. User navigates to Settings -> Apps -> LockKeeper on a device with a modern deep view hierarchy (e.g. Google Pixel Android 14/15 running `SpaActivity` or Samsung Galaxy One UI 6).
2. DFS begins at root. The top navigation bar, app bar, search button, overflow menu, and profile container consume the first 45 nodes.
3. At node 45, `count >= MAX_TRAVERSED_NODES` triggers. Traversal aborts immediately.
4. The remaining nodes in the tree (which contain the app title "LockKeeper", the Uninstall button `button1_negative`, and the Force Stop button) are never visited.
5. Neither `mentionsLockKeeper` nor `hasAppActionIds` is populated.
6. `evaluateSettings` returns `null`.
7. **LockKeeper never displays the Admin Password overlay.** The user taps "Uninstall" and uninstalls LockKeeper unimpeded.

**Preconditions:**  
The target Settings or PackageInstaller view hierarchy has depth >= 7 or has more than 45 nodes before reaching the action controls.

**Impact:**  
Complete false-negative bypass of anti-tamper on modern Android OEM devices.

**Existing Mitigation:**  
Unit tests in `TamperDetectionEngineTest` only created 3-to-4 node trees with depth 1.

**Why Mitigation Is Insufficient:**  
Synthetic tests did not reflect real-world Android View hierarchies.

**Regression Risk:**  
Increasing node traversal limits must be balanced against Main-Thread latency to avoid ANRs.

**Recommended Remediation Direction:**  
Switch from pre-order DFS to a targeted Breadth-First Search (BFS) or prioritize nodes matching known resource IDs and package names; increase depth limit to 12 and node budget to 120, or execute tree evaluation off the binder thread.

**Runtime Validation Required:**  
STATICALLY PROVEN on algorithm logic; RUNTIME VALIDATION REQUIRED to measure exact node count on specific OEM builds.

---

## FINDING-SEC-04 — Premature Overlay Tearing and Destruction on Soft-Keyboard (IME) and Intermediate Window Events

**Severity:** CRITICAL  
**Confidence:** CONFIRMED (Statically Proven via Event Dispatch Trace)  
**Category:** Windowing / Overlay Lifecycle Vulnerability  

**Affected Files:**  
- `android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperAccessibilityService.kt`  
- `android/app/src/main/kotlin/com/lockkeeper/app/overlay/OverlayManager.kt`  

**Affected Functions:**  
- `LockKeeperAccessibilityService.onAccessibilityEvent(event: AccessibilityEvent?)` (lines 89–113)  
- `OverlayManager.dismissAdminOverlay()` (lines 243–250)  

**Exact Evidence:**  
`LockKeeperAccessibilityService.kt:104-113`:
```kotlin
val tamperEvent = tamperEngine.evaluate(
    packageName = packageName,
    className = className,
    rootNode = nodeFacade,
    isDeviceAdminActive = isAdminActive
)

if (tamperEvent != null && tamperEvent.confidence != com.lockkeeper.app.security.TamperConfidence.LOW) {
    android.util.Log.d("LockKeeperA11y", "Tamper event detected: type=${tamperEvent.type}, source=${tamperEvent.source}")
    handleTamperEvent(tamperEvent, packageName)
    return
} else if (isSystemManagementPackage(packageName)) {
    // Navigated away from sensitive screen within Settings or Package Installer
    overlayManager.dismissAdminOverlay()
}
```

**Root Cause:**  
When an event arrives from a package recognized by `isSystemManagementPackage(packageName)` (such as `com.android.settings`), if `tamperEngine.evaluate()` returns `null`, the service assumes the user has navigated away from the sensitive screen and immediately invokes `overlayManager.dismissAdminOverlay()`. However, whenever the user interacts with `AdminOverlayView` (such as tapping the EditText), Android launches the soft keyboard (IME). This window change triggers `TYPE_WINDOW_STATE_CHANGED` for `com.android.settings`. Because the active window is transiently focused on the IME, `rootNode` does not contain the full App Info tree, causing `evaluate()` to return `null`.

**Attack / Failure Scenario:**  
1. User navigates to Settings App Info for LockKeeper.
2. `AdminOverlayView` appears, covering the screen.
3. User taps the password input field to enter their password.
4. Soft keyboard opens. A window state change event is dispatched with `packageName = com.android.settings`.
5. `tamperEngine.evaluate()` runs on the intermediate window state and returns `null`.
6. Line 112 executes: `overlayManager.dismissAdminOverlay()`.
7. **The Admin Overlay vanishes.**
8. The user is now on the unobstructed Settings App Info screen with the soft keyboard open, free to tap "Uninstall" or "Force Stop".

**Preconditions:**  
An Admin overlay is visible and the user taps the EditText or an intermediate window event fires within `com.android.settings`.

**Impact:**  
Total bypass of the Admin Password gate during normal user interaction.

**Existing Mitigation:**  
Line 79 ignores events if `packageName == this.packageName && overlayManager.isOverlayShowing()`.

**Why Mitigation Is Insufficient:**  
The window event for the underlying activity or IME carries `packageName = "com.android.settings"`, NOT `this.packageName` (`com.lockkeeper.app`).

**Regression Risk:**  
High. Overlay dismissal must only occur when the foreground package actually leaves `com.android.settings`.

**Recommended Remediation Direction:**  
Never dismiss the Admin overlay based solely on a null evaluation from the same management package. Only dismiss when `lastHandledPackage` explicitly transitions to an allowed non-management package or when user cancels/authenticates.

**Runtime Validation Required:**  
STATICALLY PROVEN from event handling branch logic; RUNTIME VALIDATION RECOMMENDED to observe keyboard focus animation.

---

## FINDING-SEC-05 — Clock-Warp Epoch Comparison Bypasses 300-Second Persistent Lockout

**Severity:** CRITICAL  
**Confidence:** CONFIRMED (Statically Proven via Time Source Inspection)  
**Category:** Authentication / Cryptographic Security  

**Affected Files:**  
- `android/app/src/main/kotlin/com/lockkeeper/app/security/TamperAuthorizationController.kt`  

**Affected Functions:**  
- `TamperAuthorizationController.verifyAdminPassword(enteredPassword: String)` (lines 90–100, 112–115)  
- `TamperAuthorizationController.checkLockout()` (lines 79–88)  

**Exact Evidence:**  
`TamperAuthorizationController.kt:30`:
```kotlin
private val wallClockTimeProvider: () -> Long = { System.currentTimeMillis() }
```
`TamperAuthorizationController.kt:91, 95-99`:
```kotlin
val now = wallClockTimeProvider()
...
val lockoutUntil = settings.adminLockoutUntil
if (lockoutUntil != null && lockoutUntil > now) {
    val remainingSec = maxOf(1L, (lockoutUntil - now) / 1000L)
    return AdminAuthResult.LockedOut(remainingSec)
}
```
`TamperAuthorizationController.kt:112`:
```kotlin
val newLockoutUntil = if (isLockedOut) now + ADMIN_LOCKOUT_DURATION_MS else null
```

**Root Cause:**  
Lockout expiration is calculated and checked using wall-clock time (`System.currentTimeMillis()`). Android users can manually alter wall-clock time in Settings, or malicious scripts can advance device time via root or automated settings intents.

**Attack / Failure Scenario:**  
1. An attacker fails 5 password attempts on the Admin overlay.
2. The controller sets `adminLockoutUntil = System.currentTimeMillis() + 300_000L` (5 minutes in future).
3. The overlay enters lockout mode.
4. Attacker pulls down the notification shade, clicks the clock/settings icon, or advances device time by 5 minutes.
5. Attacker returns to the Admin overlay and submits another password.
6. `now` is now greater than `lockoutUntil`.
7. `lockoutUntil > now` evaluates to `false`.
8. The controller resets lockout state and allows password verification immediately.

**Preconditions:**  
Device wall-clock time is adjustable by user or automation.

**Impact:**  
Complete nullification of brute-force deterrence.

**Existing Mitigation:**  
Grace windows use monotonic time (`SystemClock.elapsedRealtime()`), but lockout persistence explicitly uses wall-clock time.

**Why Mitigation Is Insufficient:**  
Monotonic time resets on device reboot, prompting the author to use wall-clock time for persistence without implementing monotonic skew detection or secure uptime delta tracking.

**Regression Risk:**  
Medium. Requires persisting both monotonic uptime and boot count markers.

**Recommended Remediation Direction:**  
Store `elapsedRealtime` alongside `currentTimeMillis` and check boot count; if current wall-clock time is earlier than the timestamp at which lockout was applied or jumps forward unexpectedly, enforce the full lockout duration.

**Runtime Validation Required:**  
NO. Statically proven from `System.currentTimeMillis()` dependency.

---

## FINDING-SEC-06 — Missing `RECOVERY_REQUIRED` State Machine Causes Total Defense Demolition Upon Storage Clearing

**Severity:** CRITICAL  
**Confidence:** CONFIRMED (Statically Proven via Codebase Grep & Architectural Inspection)  
**Category:** Security Architecture / Source-of-Truth Failure  

**Affected Files:**  
- `android/app/src/main/kotlin/com/lockkeeper/app/domain/ProtectionRepository.kt`  
- `android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperAccessibilityService.kt`  
- `audit/SELF_PROTECTION_IMPLEMENTATION_REPORT.md`  
- `audit/SELF_PROTECTION_THREAT_MODEL.md`  

**Affected Functions:**  
- `ProtectionRepository.shouldProtectSettings()` (lines 53–55)  

**Exact Evidence:**  
`ProtectionRepository.kt:53-55`:
```kotlin
fun shouldProtectSettings(): Boolean {
    return isOnboardingCompleteSync() && credentialStore.hasAdminPassword()
}
```
`audit/SELF_PROTECTION_IMPLEMENTATION_REPORT.md:173`:
> *- `RECOVERY_REQUIRED`: Storage cleared or state loss detected while Keystore / Device Admin indicates previous configuration. Requires Admin Password to restore.*

**Root Cause:**  
The `RECOVERY_REQUIRED` state documented extensively in `SELF_PROTECTION_IMPLEMENTATION_REPORT.md` and `SELF_PROTECTION_THREAT_MODEL.md` **does not exist anywhere in the implementation codebase**.

**Attack / Failure Scenario:**  
1. An attacker clears storage for LockKeeper (`Settings -> Apps -> LockKeeper -> Clear storage` or `adb shell pm clear com.lockkeeper.app`).
2. SQLite database (`lockkeeper_database`) and SharedPreferences (`lockkeeper_protection_prefs`, `lockkeeper_credentials`) are deleted.
3. Android system state remains:
   - Device Admin is STILL ACTIVE in `DevicePolicyManager`.
   - Accessibility Service is STILL ENABLED in `Settings.Secure`.
4. LockKeeper process launches.
5. `isOnboardingCompleteSync()` returns `false`.
6. `credentialStore.hasAdminPassword()` returns `false`.
7. `shouldProtectSettings()` returns `false`.
8. `LockKeeperAccessibilityService` skips all tamper detection because `shouldProtectSettings() == false`.
9. Attacker navigates to `Settings -> Security -> Device Admin Apps -> LockKeeper -> Deactivate`.
10. LockKeeper provides zero overlay interception because `shouldProtectSettings()` is false.
11. Attacker deactivates Device Admin and uninstalls LockKeeper with zero resistance.

**Preconditions:**  
LockKeeper storage is cleared while Device Admin or Accessibility remains active.

**Impact:**  
Total permanent demolition of all LockKeeper protections. Direct violation of Invariant 9.

**Existing Mitigation:**  
None. The recovery mechanism was documented in reports but never coded.

**Why Mitigation Is Insufficient:**  
Documentation claims do not protect runtime execution.

**Regression Risk:**  
High architectural change required to detect orphaned Device Admin state.

**Recommended Remediation Direction:**  
Check `dpm.isAdminActive()` on startup: if Device Admin is active but local database credentials are missing, enter a hard recovery state that blocks all device settings until recovery credentials or setup is validated.

**Runtime Validation Required:**  
NO. Statically proven by complete absence of `RECOVERY_REQUIRED` in Kotlin and Dart code.
