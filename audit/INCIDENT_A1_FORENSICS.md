# LockKeeper Critical Incident Response — Agent A1 Forensics Report
**Incident ID:** INCIDENT-2026-09-18-LOCKOUT  
**Severity:** CRITICAL (Release-Blocking Real Device Lockout)  
**Target:** First-Run Onboarding / Device Admin / Home-Screen Loop  
**Investigator:** Agent A1 (Incident Forensics Specialist)  
**Date:** 2026-09-18  

---

## 1. Executive Forensic Summary

A clean installation of LockKeeper on a physical Android device (Xiaomi Mi 10i, Android 12, MIUI 14 Global) resulted in a complete, unrecoverable device-wide lockout upon granting Device Administrator permissions during initial onboarding. The user was trapped on the Android Home screen (`com.miui.home`), normal applications and settings could not be launched, and normal device restart was disrupted.

Forensic examination of the codebase reveals a deterministic, unmitigated infinite loop caused by:
1. Premature triggering of `RECOVERY_REQUIRED` in `ProtectionRepository.checkRecoveryStatus()` when `isAdminActive == true` while credentials or onboarding are incomplete.
2. Incomplete onboarding state being immediately categorized as a security compromise rather than a first-run setup state.
3. Flutter UI routing instantly aborting `OnboardingScreen` and transitioning to `HomeScreen` in response to `recoveryRequired == true`.
4. `LockDecisionEngine.evaluate()` categorizing all non-LockKeeper packages (including the Android launcher `com.miui.home`, System UI, and Android Settings) as `LockDecision.RecoveryRequired`.
5. `LockKeeperAccessibilityService` reacting to `LockDecision.RecoveryRequired` by calling `performGlobalAction(GLOBAL_ACTION_HOME)`.
6. Dispatching `GLOBAL_ACTION_HOME` generating immediate new `TYPE_WINDOW_STATE_CHANGED` accessibility events for `com.miui.home`, which in turn evaluated to `RecoveryRequired`, calling `GLOBAL_ACTION_HOME` again in an infinite loop.

---

## 2. Exact Runtime Execution Trace

### Phase 1: Clean Installation & Initialization
- **Action:** Fresh release APK installed via ADB or package installer.
- **State:**
  - `AppSettingsEntity` created with defaults: `onboardingComplete = false`, `recoveryRequired = false`, `selfLockEnabled = false`.
  - Keystore / `CredentialStore`: `hasPin() == false`, `hasAdminPassword() == false`.
  - Room `locked_apps`: Empty list.
  - OS Device Policy Manager: `isAdminActive(adminComponent) == false`.
  - Flutter app launches: `PlatformBridge.getProtectionStatus()` reports `onboardingComplete = false`, `recoveryRequired = false`.
  - `lib/main.dart` routes to `OnboardingScreen()` (Step 0: Welcome).

### Phase 2: Onboarding Steps 1 to 3
- **Step 1 (Overlay):** User grants `ACTION_MANAGE_OVERLAY_PERMISSION`.
- **Step 2 (Usage Stats):** User grants `ACTION_USAGE_ACCESS_SETTINGS`.
- **Step 3 (Accessibility):** User enables `LockKeeperAccessibilityService` in Accessibility Settings.
  - `LockKeeperAccessibilityService.onServiceConnected()` runs.
  - `isConnected = true`.
  - Calls `repository.notifySecurityStateChanged()`.
  - `checkRecoveryStatus()` is evaluated:
    - `isAdminActive == false`
    - Condition line 55 `isAdminActive && (!hasCreds || !settings.onboardingComplete)` evaluates to `false`.
    - `recoveryRequired` remains `false`.
    - `overallStatus` evaluates to `"INITIALIZING"` (line 349).
  - App state remains healthy.

### Phase 3: The Fatal Trigger — Step 4 (Device Admin Grant)
- **Step 4 (Device Admin):** `OnboardingScreen` invokes `PlatformChannelHandler.requestPermission("deviceAdmin")`.
  - Intent `DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN` is launched.
  - MIUI 14 displays the 10-second security warning dialog.
  - User confirms and taps "Activate this device admin app".
- **OS Callback:**
  - The Android OS registers LockKeeper as active device admin in `/data/system/device_policies.xml`.
  - Android invokes `LockKeeperDeviceAdminReceiver.onEnabled(context, intent)`:
    ```kotlin
    // LockKeeperDeviceAdminReceiver.kt:12
    com.lockkeeper.app.domain.ProtectionRepository.getInstance(context).notifySecurityStateChanged()
    ```
- **State Transition in `ProtectionRepository.kt`:**
  - `notifySecurityStateChanged()` invokes `getAuthoritativeSecurityStatus()`, which invokes `checkRecoveryStatus()`:
    ```kotlin
    // ProtectionRepository.kt:48-66
    suspend fun checkRecoveryStatus(): Boolean = withContext(Dispatchers.IO) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
        val adminComponent = android.content.ComponentName(context, com.lockkeeper.app.receiver.LockKeeperDeviceAdminReceiver::class.java)
        val isAdminActive = dpm?.isAdminActive(adminComponent) == true
        val hasCreds = credentialStore.hasPin() && credentialStore.hasAdminPassword()
        val settings = appSettingsDao.getOrInitializeSettings()

        if (isAdminActive && (!hasCreds || !settings.onboardingComplete)) {
            if (!settings.recoveryRequired) {
                appSettingsDao.setRecoveryRequired(true)
            }
            true
        } ...
    ```
  - **FATAL EVALUATION:**
    - `isAdminActive` is now `true`.
    - `hasCreds` is `false` (PIN and Admin Password have not been reached; they are Steps 6 & 7!).
    - `settings.onboardingComplete` is `false`.
    - Line 55 evaluates to **`TRUE`**!
    - `appSettingsDao.setRecoveryRequired(true)` is written to Room SQLite database!
    - `checkRecoveryStatus()` returns `true`!
    - `overallStatus` becomes `"RECOVERY_REQUIRED"`!

### Phase 4: Flutter Onboarding Abort & Eviction
- User returns from Settings to LockKeeper Activity.
- `MainActivity.onResume()` fires -> `_refreshPermissions()` in `onboarding_screen.dart`:
  ```dart
  // onboarding_screen.dart:60-67
  final status = await PlatformBridge.getProtectionStatus();
  if (mounted) {
    if (status.recoveryRequired) {
      Navigator.of(context).pushReplacement(
        MaterialPageRoute(builder: (_) => const HomeScreen()),
      );
      return;
    }
  ```
- `OnboardingScreen` is abruptly destroyed and replaced with `HomeScreen`.
- The user is completely severed from the remaining setup steps (PIN setup, Admin password setup).

### Phase 5: The Infinite Home-Screen Enforcement Loop
- LockKeeper is closed, or user presses Home, or switches apps, or background transition occurs.
- Foreground window becomes `com.miui.home` (the MIUI launcher).
- Android Accessibility subsystem emits `AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED` for package `com.miui.home`.
- `LockKeeperAccessibilityService.onAccessibilityEvent()` is invoked:
  ```kotlin
  // LockKeeperAccessibilityService.kt:166-188
  evaluationJob = serviceScope.launch {
      val decision = repository.evaluatePackage(packageName)
      mainHandler.post {
          when (decision) {
              ...
              is LockDecision.Blocked,
              is LockDecision.RecoveryRequired,
              is LockDecision.DenyUnknown -> {
                  overlayManager.dismissIfShowing(packageName)
                  performGlobalAction(GLOBAL_ACTION_HOME)
              }
  ```
- Inside `evaluatePackage("com.miui.home")`:
  - `checkRecoveryStatus()` returns `true`.
  - `LockDecisionEngine.evaluate()` is called:
    ```kotlin
    // LockDecisionEngine.kt:77-86
    // 1. Own package or blank is always allowed
    if (targetPackage.isBlank() || targetPackage == appPackageName) {
        return LockDecision.Allowed(ProtectionDecisionReason.ALLOWED_OWN_PACKAGE)
    }

    // 2. Recovery required overrides normal app launch: must block / require recovery
    if (isRecoveryRequired || appSettings?.recoveryRequired == true || securityHealthStatus == "RECOVERY_REQUIRED") {
        clearAllSessions()
        return LockDecision.RecoveryRequired(ProtectionDecisionReason.DENIED_RECOVERY_REQUIRED)
    }
    ```
  - Package `"com.miui.home"` is NOT `"com.lockkeeper.app"`.
  - `isRecoveryRequired` is `true`.
  - **Rule 2 matches before Rule 6 (unprotected app check)!**
  - Result: `LockDecision.RecoveryRequired(ProtectionDecisionReason.DENIED_RECOVERY_REQUIRED)`.
- Inside `LockKeeperAccessibilityService`:
  - Result is `LockDecision.RecoveryRequired`.
  - Executes: `performGlobalAction(GLOBAL_ACTION_HOME)`.
- **The Self-Sustaining Feedback Loop:**
  1. `performGlobalAction(GLOBAL_ACTION_HOME)` signals the Window Manager to focus `com.miui.home`.
  2. The Window Manager dispatches focus to `com.miui.home`.
  3. This generates a new `AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED` with `packageName = "com.miui.home"`.
  4. `LockKeeperAccessibilityService.onAccessibilityEvent()` receives it.
  5. Evaluates `"com.miui.home"` -> `RecoveryRequired`.
  6. Executes `performGlobalAction(GLOBAL_ACTION_HOME)`.
  7. GOTO 1.

---

## 3. Why User Interaction Was Fully Blocked

1. **Attempting to Launch Apps / App Drawer:**
   Swiping the app drawer or tapping an app icon generates a window transition event for the launcher or target app. The target package evaluates to `RecoveryRequired` -> `performGlobalAction(GLOBAL_ACTION_HOME)` instantly aborts the launch and redirects to Home.
2. **Attempting to Open Android Settings:**
   Opening Settings generates an event for `com.android.settings`. Settings is not `com.lockkeeper.app`. It evaluates to `RecoveryRequired` -> `performGlobalAction(GLOBAL_ACTION_HOME)` instantly kills Settings and redirects to Home.
3. **Attempting to Power Off / Restart:**
   Long-pressing the physical power button triggers the system power menu (`com.android.systemui` or `com.miui.powerkeeper`). This window state change evaluates to `RecoveryRequired` -> `performGlobalAction(GLOBAL_ACTION_HOME)` dismisses the power dialog immediately.
4. **Attempting to Re-enter LockKeeper:**
   Tapping the LockKeeper icon on Home is continually interrupted by the rapid-fire `GLOBAL_ACTION_HOME` calls. Even if launched, LockKeeper opens into `HomeScreen` displaying the "Security Recovery Required" banner, demanding credentials the user never configured.

---

## 4. Code Locations of the Flaw

| Component | File & Lines | Defect Description |
|---|---|---|
| **Recovery Logic** | `ProtectionRepository.kt:55` | Conflates `isAdminActive && (!hasCreds || !onboardingComplete)` with data loss / tampering, without checking if the app was EVER provisioned. |
| **Decision Priority** | `LockDecisionEngine.kt:82-86` | Rule 2 (`RecoveryRequired`) unconditionally blocks all packages except `appPackageName`, without exempting system launchers or distinguishing setup state. |
| **A11y Enforcement** | `LockKeeperAccessibilityService.kt:187` | Executes `GLOBAL_ACTION_HOME` on `RecoveryRequired` even when the target package IS already the home launcher, creating a feedback cycle. |
| **FGS Enforcement** | `LockKeeperForegroundService.kt:207` | Fallback polling loop also dispatches `CATEGORY_HOME` intent for `RecoveryRequired`, creating dual loop vectors. |
| **UI Routing** | `lib/main.dart:162`, `onboarding_screen.dart:62` | Prematurely routes to `HomeScreen` when `recoveryRequired == true`, abandoning incomplete onboarding. |

---

## 5. Forensic Verdict

The defect is 100% reproducible, fully deterministic, and caused by an architectural gap: the security model lacks an authoritative, persistent distinction between **`SETUP_IN_PROGRESS`** (a fresh install that has not completed initial security provisioning) and **`RECOVERY_REQUIRED`** (a previously provisioned install that suffered credential or state loss).
