# Medium & Low Security Findings

**Project:** LockKeeper (C:\AppLocker)  
**Phase:** Post-Self-Protection Adversarial Validation  
**Date:** 2026-09-17  
**Validator:** Agent 15 (Lead Security Validation Engineer)

---

## FINDING-MED-01 — Swallowed Lockout Status over MethodChannel Blinds Flutter Settings UI

**Severity:** MEDIUM  
**Confidence:** CONFIRMED (Statically Proven)  
**Category:** IPC / Error Transparency  

**Affected Files:**  
- `android/app/src/main/kotlin/com/lockkeeper/app/bridge/PlatformChannelHandler.kt`  
- `lib/ui/screens/settings_screen.dart`  

**Exact Evidence:**  
`PlatformChannelHandler.kt:117-122`:
```kotlin
"verifyAdminPassword" -> {
    val password = call.argument<String>("password") ?: ""
    val authResult = repository.tamperController.verifyAdminPassword(password)
    val valid = authResult is com.lockkeeper.app.security.AdminAuthResult.Success
    result.success(valid)
}
```

**Root Cause:**  
`verifyAdminPassword` reduces the rich `AdminAuthResult` sealed class (`Success`, `Failure(remainingAttempts)`, `LockedOut(seconds)`) into a single boolean `valid`.

**Impact:**  
When an admin is locked out, entering a password in Flutter Settings produces only `false`. Flutter displays *"Incorrect admin password"* rather than showing the remaining lockout duration.

**Recommended Remediation Direction:**  
Return a map over MethodChannel: `{"success": bool, "isLockedOut": bool, "remainingSeconds": long, "remainingAttempts": int}` and update `settings_screen.dart` to display appropriate lockout alerts.

---

## FINDING-MED-02 — Broken `lastHandledPackage` Tracking on Tamper Events Disables Debounce Window

**Severity:** MEDIUM  
**Confidence:** CONFIRMED (Statically Proven)  
**Category:** Event Processing / State Corruption  

**Affected Files:**  
- `android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperAccessibilityService.kt`  

**Exact Evidence:**  
`LockKeeperAccessibilityService.kt:105-108, 126`:
```kotlin
if (tamperEvent != null && tamperEvent.confidence != com.lockkeeper.app.security.TamperConfidence.LOW) {
    handleTamperEvent(tamperEvent, packageName)
    return // <--- EXITS HERE
}
...
lastHandledPackage = packageName // Line 126 is never reached!
```

**Root Cause:**  
When a tamper event triggers, the function returns early at line 108. `lastHandledPackage` is never updated.

**Impact:**  
The 150ms event debounce (`if (packageName == lastHandledPackage && dt < 150L) return`) never activates for tamper events, causing every redundant window event to trigger duplicate IPC calls.

**Recommended Remediation Direction:**  
Assign `lastHandledPackage = packageName` before calling `handleTamperEvent()`.

---

## FINDING-MED-03 — Static Lockout Text Lacks Real-Time Ticking Countdown Timer

**Severity:** MEDIUM  
**Confidence:** CONFIRMED (Statically Proven)  
**Category:** UI/UX Integrity  

**Affected Files:**  
- `android/app/src/main/kotlin/com/lockkeeper/app/overlay/AdminOverlayView.kt`  

**Exact Evidence:**  
`AdminOverlayView.kt:179`:
```kotlin
fun showLockout(remainingSeconds: Long) {
    errorTextView.text = "Too many incorrect attempts. Locked out for $remainingSeconds seconds."
    inputEditText.isEnabled = false
    submitButton.isEnabled = false
}
```

**Root Cause:**  
The message sets a static string. There is no `CountDownTimer` or coroutine ticking down the seconds.

**Impact:**  
The lockout message remains permanently frozen at the initial second count (e.g. "300 seconds") until the overlay is destroyed and recreated, giving the impression of an infinite hang.

**Recommended Remediation Direction:**  
Attach an Android `CountDownTimer` that updates `errorTextView` every second and re-enables inputs when the timer reaches zero.

---

## FINDING-MED-04 — `errorTextView` Lacks Accessibility Live Region for Screen Readers (WCAG Non-Compliance)

**Severity:** MEDIUM  
**Confidence:** CONFIRMED (Statically Proven)  
**Category:** Accessibility / WCAG Compliance  

**Affected Files:**  
- `android/app/src/main/kotlin/com/lockkeeper/app/overlay/AdminOverlayView.kt`  

**Exact Evidence:**  
`AdminOverlayView.kt:111-120`:
```kotlin
errorTextView = TextView(context).apply {
    text = ""
    textSize = 13f
    setTextColor(Color.parseColor("#EF4444"))
    ...
}
```

**Root Cause:**  
`errorTextView` does not configure `View.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE)`.

**Impact:**  
TalkBack and other screen readers do not announce error messages or lockout notices when text is updated dynamically, leaving visually impaired users unaware of why submission failed.

**Recommended Remediation Direction:**  
Set `accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE` on `errorTextView`.

---

## FINDING-MED-05 — Database Initialization Race on Service Startup Disables Anti-Tamper Upon Boot

**Severity:** MEDIUM  
**Confidence:** CONFIRMED (Statically Proven)  
**Category:** Concurrency / Lifecycle  

**Affected Files:**  
- `android/app/src/main/kotlin/com/lockkeeper/app/domain/ProtectionRepository.kt`  

**Exact Evidence:**  
`ProtectionRepository.kt:38-46`:
```kotlin
init {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val s = appSettingsDao.getSettings()
            if (s != null) {
                prefs.edit().putBoolean("onboarding_complete", s.onboardingComplete).apply()
            }
        } catch (_: Exception) {}
    }
}
```

**Root Cause:**  
The SharedPreferences cache for `onboarding_complete` is populated asynchronously on `Dispatchers.IO` during repository instantiation.

**Impact:**  
If an accessibility event arrives immediately upon boot before the coroutine finishes, `isOnboardingCompleteSync()` reads the default `false`, causing `shouldProtectSettings()` to return `false` and skipping anti-tamper.

**Recommended Remediation Direction:**  
Warming of the cache must be synchronous or `shouldProtectSettings()` must evaluate the database state directly.

---

## FINDING-MED-06 — Plaintext Debug Logging Leaks Sensitive Application Packages to System Logcat

**Severity:** MEDIUM  
**Confidence:** CONFIRMED (Statically Proven)  
**Category:** Information Disclosure / Privacy  

**Affected Files:**  
- `android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperAccessibilityService.kt`  

**Exact Evidence:**  
`LockKeeperAccessibilityService.kt:106, 165`:
```kotlin
android.util.Log.d("LockKeeperA11y", "Tamper event detected: type=${tamperEvent.type}, source=${tamperEvent.source}")
android.util.Log.d("LockKeeperA11y", "Displaying admin overlay for tamper defense over $targetPackage")
```

**Root Cause:**  
Unconditional `Log.d` statements output package names and security events without checking `BuildConfig.DEBUG`.

**Impact:**  
Any application on the device with `READ_LOGS` or via ADB can track user app management and tamper events in real time.

**Recommended Remediation Direction:**  
Strip `Log.d` statements in release builds using ProGuard or wrap with `if (BuildConfig.DEBUG)`.

---

## FINDING-LOW-01 — 50MB Binary APK and Launcher Mipmap Assets Committed to Version Control

**Severity:** LOW  
**Confidence:** CONFIRMED  
**Category:** Repository Hygiene & Release Management  

**Affected Files:**  
- `LockKeeper-v1.0.0-release.apk` (50MB)  
- `android/app/src/main/res/mipmap-*/ic_launcher.png`  
- `assets/icon/app_icon.png`  
- `scripts/android_build.ps1`  

**Root Cause:**  
The build script copies compiled binary APKs to the root repository folder, which were staged and committed to git.

**Impact:**  
Bloats git history; risks version confusion between source tree and compiled binaries.

**Recommended Remediation Direction:**  
Add `*.apk` to `.gitignore` and distribute binaries via GitHub Releases or CI/CD pipelines.

---

## FINDING-LOW-02 — Dead Enums Declared in `TamperEvent.kt` but Unused in Detection Engine

**Severity:** LOW  
**Confidence:** CONFIRMED  
**Category:** Code Hygiene / Dead Code  

**Affected Files:**  
- `android/app/src/main/kotlin/com/lockkeeper/app/security/TamperEvent.kt`  
- `android/app/src/main/kotlin/com/lockkeeper/app/security/TamperDetectionEngine.kt`  

**Exact Evidence:**  
`TamperEvent.kt:10, 11, 18, 19, 20`:
`TamperType.SECURITY_SETTINGS`, `TamperType.UNKNOWN`, `TamperSource.LAUNCHER`, `TamperSource.PLAY_STORE`, `TamperSource.OTHER`.

**Root Cause:**  
These enums were declared in anticipation of features that were never implemented in `TamperDetectionEngine.kt`.

**Impact:**  
Misleading architectural surface; creates false assumption that Play Store and Launchers are handled.

**Recommended Remediation Direction:**  
Remove dead enum values or implement detection routines that actually generate them.
