# LockKeeper Security Findings Report

**Audit Date**: September 17, 2026  
**Auditor**: Lead Security & Software Quality Auditor  
**Classification**: Highly Confidential / Read-Only Audit  
**Total Security Findings**: 18 (5 Critical, 7 High, 4 Medium, 2 Low)

---

## Executive Overview of Security Posture

The static forensic audit of LockKeeper revealed foundational vulnerabilities in its access control, authentication, anti-tamper heuristics, credential lifecycle, and process isolation model. While the cryptographic primitives (hardware Keystore backed by StrongBox/TEE and PBKDF2-HMAC-SHA256 with 65,536 iterations) are implemented correctly in isolation, the architectural assumptions surrounding Android security guarantees are severely compromised.

Specifically, the application relies on an ordinary Device Administrator receiver and accessibility heuristics to simulate enterprise Device Owner enforcement. These simulations fail against standard user interactions such as changing the OS language, uninstalling via third-party launchers, invoking app storage clearance, or background platform channel exploitation.

---

## Detailed Vulnerability Dossiers

---

### SEC-01 — Hardcoded English String Matching in Accessibility Allows 100% Anti-Tamper Bypass

- **Finding ID**: SEC-01
- **Severity**: CRITICAL
- **Confidence**: CONFIRMED
- **Category**: Anti-Tamper / Accessibility Bypass
- **Affected File(s)**: `android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperAccessibilityService.kt`
- **Exact Line(s)**: Lines 166–204
- **Relevant Function/Class**: `LockKeeperAccessibilityService.evaluateSettingsProtection()`

#### Technical Description
LockKeeper's core self-defense mechanism inspects accessibility node hierarchies inside `com.android.settings` to detect destructive actions such as uninstalling the application, clearing data, or deactivating Device Administrator privileges. In `evaluateSettingsProtection()`, the inspection explicitly searches for hardcoded, lowercase English text substrings:
```kotlin
val destructiveKeywords = listOf("uninstall", "force stop", "disable", "clear storage", "clear data", "deactivate")
```
When the user navigates Settings, the service traverses the active `AccessibilityNodeInfo` tree and checks:
```kotlin
val nodeText = node.text?.toString()?.lowercase() ?: ""
if (destructiveKeywords.any { nodeText.contains(it) }) {
    triggerAdminProtectionOverlay()
    return true
}
```

#### Root Cause
The anti-tamper logic assumes the host Android operating system is running in an English locale (`en-US` or `en-GB`). Android OS localizes system UI strings dynamically across hundreds of language and region locales (e.g., Spanish: "Desinstalar", French: "Désinstaller", German: "Deinstallieren", Hindi: "अनइंस्टॉल करें").

#### Preconditions
1. The device has LockKeeper installed and configured.
2. The user has physical or UI control of the device.

#### Attack/User Scenario & Exploit Path
1. Attacker or restricted user opens Android System Settings (`com.android.settings`).
2. Attacker navigates to `System > Languages & input` and adds Spanish (or any non-English language) and moves it to the top.
3. Attacker opens `Settings > Apps > LockKeeper`.
4. The button displays "Desinstalar" instead of "uninstall" and "Forzar detención" instead of "force stop".
5. The `evaluateSettingsProtection()` method scans the view hierarchy, but `destructiveKeywords.any { nodeText.contains(it) }` evaluates to `false` on all nodes.
6. The attacker taps "Desinstalar" and confirms the uninstallation without ever triggering the admin password overlay.

#### Impact
Complete circumvention of the app's self-protection mechanism. Any non-admin user can deactivate Device Admin, clear data, or uninstall LockKeeper in seconds without entering the Admin Password.

#### Existing Mitigation
None. The code does not inspect node resource IDs, action IDs, or standard Android Intent structures.

#### Why Mitigation Is Insufficient
Hardcoded string checks are inherently fragile and incapable of internationalized UI enforcement.

#### Recommended Remediation Direction
1. Replace text-based matching with canonical Android system view resource identifiers (e.g., `com.android.settings:id/button1_negative`, `com.android.settings:id/uninstall_button`).
2. Intercept navigation at the Activity level rather than relying on button text heuristics.
3. Bind localized system resource strings dynamically at runtime using Android's `Resources` context for the system package (`Resources.getSystem()`).

---

### SEC-02 — Device Admin Architectural Fallacy: Ordinary Admin Does Not Provide Device Owner Protection

- **Finding ID**: SEC-02
- **Severity**: CRITICAL
- **Confidence**: CONFIRMED
- **Category**: Architecture / Privileges
- **Affected File(s)**: 
  - `android/app/src/main/kotlin/com/lockkeeper/app/receiver/LockKeeperDeviceAdminReceiver.kt` (Lines 18–35)
  - `android/app/src/main/res/xml/device_admin_receiver.xml` (Lines 1–10)
- **Relevant Function/Class**: `LockKeeperDeviceAdminReceiver.onDisableRequested()`

#### Technical Description
The project architecture assumes that registering LockKeeper as a Device Administrator prevents uninstallation. However, in `LockKeeperDeviceAdminReceiver.kt`, the implementation only overrides `onDisableRequested()`:
```kotlin
override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
    return "Deactivating LockKeeper Device Administrator will disable tamper and uninstall protection."
}
```
In Android, standard Device Administration (`android.app.admin.DeviceAdminReceiver`) provides policies for screen lock, camera restrictions, and wipe policies. It does **not** grant the privilege to block its own deactivation. The return value of `onDisableRequested()` is merely a warning message displayed in a system dialog before the user taps "Deactivate anyway".

#### Root Cause
Fundamental architectural confusion between **Device Administrator** (legacy enterprise management) and **Device Owner / Profile Owner** (`DevicePolicyManager.setUninstallBlocked()` or `DevicePolicyManager.addUserRestriction(UserManager.DISALLOW_UNINSTALL_APPS)`).

#### Preconditions
Physical access to the device and access to Device Admin settings.

#### Attack/User Scenario & Exploit Path
1. Attacker opens `Settings > Security > Device admin apps`.
2. Attacker selects `LockKeeper` and taps `Deactivate this device admin app`.
3. Android displays the warning string returned by `onDisableRequested()`.
4. Attacker clicks `OK / Deactivate`.
5. Device Administrator status is permanently revoked.
6. Attacker uninstalls LockKeeper cleanly.

#### Impact
LockKeeper cannot enforce uninstall prevention natively. The entire uninstall prevention relies exclusively on AccessibilityService intercepting the screen. If Accessibility is killed, crashed, or bypassed (via SEC-01 or SEC-03), Device Admin is stripped instantly.

#### Existing Mitigation
The app relies on AccessibilityService to cover the Device Admin deactivation screen with `AdminOverlayView`.

#### Why Mitigation Is Insufficient
If the AccessibilityService is disrupted, disabled, or bypassed, the Device Admin API offers zero OS-level resistance.

#### Recommended Remediation Direction
1. Document the boundary clearly: LockKeeper is an accessibility-based enforcement layer, not a Managed Device (Device Owner).
2. For enterprise deployments, provision LockKeeper via ADB or QR code provisioning as a genuine **Device Owner** using `dpm set-device-owner`.
3. For consumer mode, maintain a robust watchdog that immediately triggers full-screen lockdown if `DevicePolicyManager.isAdminActive()` returns false.

---

### SEC-03 — Anti-Tamper Scope Limited to `com.android.settings`, Ignoring Launchers & OEM Centers

- **Finding ID**: SEC-03
- **Severity**: CRITICAL
- **Confidence**: CONFIRMED
- **Category**: Anti-Tamper / Scope Limitation
- **Affected File(s)**: `android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperAccessibilityService.kt`
- **Exact Line(s)**: Lines 134–145
- **Relevant Function/Class**: `LockKeeperAccessibilityService.onAccessibilityEvent()`

#### Technical Description
In `LockKeeperAccessibilityService.kt`, the anti-tamper and uninstall detection is gated strictly behind a package name equality check:
```kotlin
val currentPackage = event.packageName?.toString() ?: return
if (currentPackage == "com.android.settings") {
    if (evaluateSettingsProtection(rootNode)) {
        return
    }
}
```
No other package name triggers `evaluateSettingsProtection()`.

#### Root Cause
The developer assumed uninstallation and app management only occur inside the standard AOSP/Google Settings application (`com.android.settings`).

#### Preconditions
1. Modern Android device running third-party launchers (Nova, Lawnchair, Pixel Launcher, Samsung OneUI Home, Xiaomi POCO Launcher).
2. Or an OEM device with proprietary maintenance apps (Samsung Device Care: `com.samsung.android.lool`, Xiaomi Security: `com.miui.securitycenter`).

#### Attack/User Scenario & Exploit Path
1. Attacker long-presses the LockKeeper app icon on the home screen or app drawer.
2. Attacker drags the icon to the top "Uninstall" drop target or selects "Uninstall" from the pop-up shortcut menu.
3. The launcher invokes `com.google.android.packageinstaller` or `com.android.packageinstaller`.
4. The system uninstallation confirmation dialog appears ("Do you want to uninstall this app?").
5. Because `event.packageName` is `com.android.packageinstaller` and not `com.android.settings`, `evaluateSettingsProtection()` is never called.
6. If Device Admin is not active, the app is uninstalled immediately. If Device Admin is active, the system displays "Manage device admin apps" button, taking the user directly into Device Admin settings without hitting the `com.android.settings` keyword heuristics.

#### Impact
Direct uninstallation from home launchers and third-party package managers completely bypasses the LockKeeper anti-tamper subsystem.

#### Existing Mitigation
None. `evaluateSettingsProtection()` is strictly gated by `currentPackage == "com.android.settings"`.

#### Why Mitigation Is Insufficient
Package uninstallation in Android is handled by package installers (`com.google.android.packageinstaller`, `com.android.packageinstaller`), not `com.android.settings`.

#### Recommended Remediation Direction
1. Expand the monitored package list to include package installer packages: `com.android.packageinstaller`, `com.google.android.packageinstaller`, and OEM equivalents (`com.samsung.android.packageinstaller`).
2. Intercept window changes where package removal confirmation intents (`android.intent.action.UNINSTALL_PACKAGE` / `android.intent.action.DELETE`) are detected.

---

### SEC-04 — SelfLockGateScreen Hardcoded Auto-Submit at 4 Digits Permanently Locks Out 5–8 Digit PIN Users

- **Finding ID**: SEC-04
- **Severity**: CRITICAL
- **Confidence**: CONFIRMED
- **Category**: Authentication / Denial of Service
- **Affected File(s)**: 
  - `lib/screens/self_lock_gate_screen.dart` (Lines 92–95, 147–152)
  - `lib/screens/onboarding_screen.dart` (Lines 210–225)
  - `lib/screens/settings_screen.dart` (Lines 312–330)
- **Relevant Function/Class**: `_SelfLockGateScreenState._handleKeypadInput()`

#### Technical Description
In `onboarding_screen.dart` and `settings_screen.dart`, user PIN creation allows PINs between 4 and 8 digits (`pin.length >= 4 && pin.length <= 8`). However, in `self_lock_gate_screen.dart`, the keypad handler contains an automatic submission trigger when the buffer length reaches 4 digits:
```dart
void _handleKeypadInput(String key) {
  if (_pinBuffer.length < 8) {
    setState(() {
      _pinBuffer += key;
    });
    if (_pinBuffer.length >= 4) {
      _verifyPinAndProceed();
    }
  }
}
```
Once the user types the 4th digit, `_verifyPinAndProceed()` is immediately executed.

#### Root Cause
Incomplete implementation of dynamic-length PIN entry. The developer added auto-submit for 4-digit PINs without providing an "Enter / Submit" button or checking whether the stored PIN was longer than 4 digits.

#### Preconditions
A user sets up LockKeeper with a 5, 6, 7, or 8-digit PIN during onboarding or settings.

#### Attack/User Scenario & Exploit Path
1. User configures a secure 6-digit PIN during onboarding: `123456`.
2. LockKeeper stores the PBKDF2 hash of `123456` in the secure repository.
3. Later, the user leaves the app; self-lock activates.
4. When returning to the app, `SelfLockGateScreen` is displayed.
5. User enters `1`, `2`, `3`, `4`.
6. As soon as `4` is pressed, `_pinBuffer.length >= 4` evaluates to true.
7. `_verifyPinAndProceed()` immediately passes `1234` to the backend platform bridge.
8. The hash of `1234` does not match `123456`. The attempt fails.
9. After 5 attempts, the PIN lockout mechanism locks the user out for 300 seconds.
10. The user can never type the 5th or 6th digit because the screen automatically submits at 4 digits every time.

#### Impact
Catastrophic permanent lockout of legitimate users who choose PINs between 5 and 8 digits. The user is permanently barred from accessing LockKeeper settings or dashboard.

#### Existing Mitigation
None.

#### Why Mitigation Is Insufficient
The code unconditionally triggers verification at length 4, preventing any 5th digit from being appended before submission.

#### Recommended Remediation Direction
1. Remove automatic submission at 4 digits in `SelfLockGateScreen`.
2. Introduce an explicit "Submit" (Checkmark) button on the custom numeric keypad, or query the stored PIN length from native storage to know when to auto-submit without revealing the PIN itself.

---

### SEC-05 — Unrestricted Clear-Data in Settings Wipes Keystore-Backed Storage & Protection

- **Finding ID**: SEC-05
- **Severity**: CRITICAL
- **Confidence**: CONFIRMED
- **Category**: Anti-Tamper / Data Wiping
- **Affected File(s)**: 
  - `android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperAccessibilityService.kt` (Lines 170–204)
  - `android/AndroidManifest.xml`
- **Relevant Function/Class**: `evaluateSettingsProtection()`

#### Technical Description
In `com.android.settings`, opening `App info > Storage & cache > Clear storage / Clear data` deletes the app's SQLite database (`app_database.db`), SharedPreferences (`lockkeeper_prefs.xml`), and resets the Android Keystore alias associations. 
While `evaluateSettingsProtection()` attempts to search for `"clear storage"` and `"clear data"`, it fails to intercept the preceding screens or alternative navigation paths (e.g., clicking "Storage & cache" directly). Furthermore, on Android 12+, tapping "Clear storage" launches a secondary sub-activity or system dialog (`com.android.settings.applications.StorageUseActivity`) which often reports different package and class names, bypassing the heuristics.

#### Root Cause
Reliance on surface-level view text matching inside a deeply nested Settings hierarchy.

#### Preconditions
User has device access and opens Settings.

#### Attack/User Scenario & Exploit Path
1. Attacker opens `Settings > Storage > Other apps > LockKeeper`.
2. Attacker selects "Clear storage".
3. The system confirms data wipe.
4. All local Room databases, PIN hashes, admin credentials, and lock configurations are deleted.
5. Upon next launch or service check, LockKeeper boots in unconfigured/factory state (`isOnboardingComplete == false`), removing all app protection.

#### Impact
Complete removal of all application protection, configured rules, and security credentials without needing the Admin Password.

#### Existing Mitigation
Keyword search in `evaluateSettingsProtection()`.

#### Why Mitigation Is Insufficient
Fails when the settings screen is navigated via alternate sub-screens (like the system-level "Storage" menu instead of "Apps"), or when localized into another language (SEC-01).

#### Recommended Remediation Direction
1. Intercept any accessibility event where `event.packageName == "com.android.settings"` and the current screen or focused element relates to `StorageUseActivity`.
2. Cover the screen immediately upon detecting access to LockKeeper's app info page unless an Admin grace period is currently active.

---

### SEC-06 — Flutter Root State Desynchronization Disables Self-Lock Gate on Initial Launch

- **Finding ID**: SEC-06
- **Severity**: HIGH
- **Confidence**: CONFIRMED
- **Category**: Lifecycle / Authentication
- **Affected File(s)**: 
  - `lib/main.dart` (Lines 48–65, 80–115)
  - `lib/screens/onboarding_screen.dart` (Lines 340–355)
- **Relevant Function/Class**: `LockKeeperApp.build()`, `_LockKeeperAppState.didChangeAppLifecycleState()`

#### Technical Description
In `main.dart`, `LockKeeperApp` is a `StatefulWidget` whose constructor receives `isOnboardingComplete`:
```dart
final bool isOnboardingComplete;
const LockKeeperApp({Key? key, required this.isOnboardingComplete}) : super(key: key);
```
During a fresh installation, `isOnboardingComplete` is passed as `false`.
When onboarding finishes, `onboarding_screen.dart` performs a navigation push:
```dart
Navigator.pushReplacementNamed(context, '/home');
```
Crucially, `LockKeeperApp` is never rebuilt, and its immutable field `widget.isOnboardingComplete` remains `false` in `_LockKeeperAppState`.
In `didChangeAppLifecycleState()`, the app lifecycle listener checks:
```dart
if (state == AppLifecycleState.resumed) {
  if (!widget.isOnboardingComplete) {
    return; // Self-lock gate bypassed!
  }
  _checkAndEnforceSelfLock();
}
```

#### Root Cause
Mutable lifecycle state depending on an immutable initial widget parameter that is never updated when onboarding completes.

#### Preconditions
Fresh installation where the user completes onboarding and continues using the app without killing the OS process.

#### Attack/User Scenario & Exploit Path
1. Victim sets up LockKeeper for the first time, sets PIN and Admin Password, completes onboarding, and lands on `HomeScreen`.
2. Victim locks the phone or switches to another app.
3. Attacker grabs the unlocked phone and opens LockKeeper.
4. The Flutter app transitions to `AppLifecycleState.resumed`.
5. Because `widget.isOnboardingComplete` is still `false`, `_checkAndEnforceSelfLock()` aborts immediately.
6. The attacker is inside `HomeScreen` and can view protected apps and modify configurations.

#### Impact
Bypass of the Self-Lock security gate on the initial app session, leaving all dashboard features accessible.

#### Existing Mitigation
Self-lock gate works only after the app process is terminated and restarted (when `main()` re-queries SharedPreferences and passes `true`).

#### Why Mitigation Is Insufficient
Mobile apps frequently run for days without OS-level process termination.

#### Recommended Remediation Direction
Store and read onboarding completion from a reactive state container (e.g., `StateNotifier` / `ValueNotifier` or directly querying `PlatformBridge.isOnboardingComplete()`) rather than binding to a static widget constructor field.

---

### SEC-07 — Unthrottled `verifyPin` and `verifyAdminPassword` Platform IPC Enables Unlimited Brute-Force

- **Finding ID**: SEC-07
- **Severity**: HIGH
- **Confidence**: CONFIRMED
- **Category**: Authentication / Brute-Force
- **Affected File(s)**: 
  - `android/app/src/main/kotlin/com/lockkeeper/app/bridge/PlatformChannelHandler.kt` (Lines 80–105)
  - `android/app/src/main/kotlin/com/lockkeeper/app/security/CredentialStore.kt` (Lines 110–135)
- **Relevant Function/Class**: `PlatformChannelHandler.onMethodCall("verifyPin")` & `"verifyAdminPassword"`

#### Technical Description
In `PlatformChannelHandler.kt`, the `"verifyPin"` and `"verifyAdminPassword"` method channel endpoints delegate directly to `CredentialStore.verifyPin(pin)` and `CredentialStore.verifyAdminPassword(password)`:
```kotlin
"verifyPin" -> {
    val pin = call.argument<String>("pin") ?: ""
    val isValid = credentialStore.verifyPin(pin)
    result.success(isValid)
}
"verifyAdminPassword" -> {
    val password = call.argument<String>("password") ?: ""
    val isValid = credentialStore.verifyAdminPassword(password)
    result.success(isValid)
}
```
Neither of these methods updates the failed attempt counter in `AppSettingsDao` nor checks if the lockout threshold has been reached.

#### Root Cause
Lockout counter updates (`failedPinAttempts`, `pinLockoutUntil`) are implemented inside `OverlayManager.kt` specifically for native overlays, but omitted entirely from the `PlatformChannelHandler` IPC methods.

#### Preconditions
Attacker interacts with LockKeeper's platform channel interface or settings screen.

#### Attack/User Scenario & Exploit Path
1. Attacker interacts with LockKeeper's platform channel interface or script.
2. Attacker invokes `MethodChannel("com.lockkeeper.app/bridge").invokeMethod("verifyPin", mapOf("pin" to candidate))`.
3. Attacker loops through all 10,000 four-digit PIN combinations (`0000` to `9999`).
4. Because `verifyPin` in `PlatformChannelHandler` does not check or increment `failedPinAttempts`, zero lockouts are triggered.
5. The 4-digit PIN is cracked in under 2 minutes.

#### Impact
High-speed automated brute-forcing of user PINs and Admin Passwords without triggering any lockouts or security alerts.

#### Existing Mitigation
Lockout logic exists in `OverlayManager.kt` for UI-based overlay attempts only.

#### Why Mitigation Is Insufficient
Core credential verification in the business logic layer must be self-throttling and stateful; decoupling UI lockout from backend verification violates defense-in-depth.

#### Recommended Remediation Direction
Move rate-limiting and attempt-tracking logic directly into `ProtectionRepository.kt` or `CredentialStore.kt` so that all verification pathways (UI, PlatformChannel, Overlays) share an authoritative lockout state.

---

### SEC-08 — Absence of Attempt Throttling or Lockout on Admin Password

- **Finding ID**: SEC-08
- **Severity**: HIGH
- **Confidence**: CONFIRMED
- **Category**: Authentication / Brute-Force
- **Affected File(s)**: 
  - `android/app/src/main/kotlin/com/lockkeeper/app/ui/AdminOverlayView.kt` (Lines 80–115)
  - `android/app/src/main/kotlin/com/lockkeeper/app/manager/OverlayManager.kt` (Lines 160–185)
- **Relevant Function/Class**: `AdminOverlayView.verifyPassword()`

#### Technical Description
While `PinOverlayView` has a 5-attempt limit followed by a 300-second lockout (`pinLockoutUntil`), `AdminOverlayView` and the admin password verification infrastructure feature **zero lockout mechanics**:
```kotlin
// AdminOverlayView.kt
submitButton.setOnClickListener {
    val enteredPassword = passwordInput.text.toString()
    if (credentialStore.verifyAdminPassword(enteredPassword)) {
        onSuccess()
    } else {
        errorTextView.text = "Incorrect Admin Password"
        errorTextView.visibility = View.VISIBLE
        passwordInput.text?.clear()
    }
}
```
The user or attacker can enter passwords indefinitely.

#### Root Cause
Omission of failed attempt counters and lockout timestamps for the Admin credential in `AppSettingsEntity` and `AdminOverlayView`.

#### Preconditions
Admin overlay is displayed over Settings or during an admin-guarded action.

#### Attack/User Scenario & Exploit Path
1. Attacker triggers the Admin Password overlay by attempting to deactivate Device Admin or access Settings.
2. Attacker enters dictionary passwords or automated keypad inputs repeatedly.
3. No lockout occurs; no progressive delays are enforced.
4. Attacker successfully cracks weak or common passwords through repetitive submission.

#### Impact
Admin password vulnerability to offline and online iterative guessing attacks.

#### Existing Mitigation
PBKDF2 with 65,536 iterations introduces a computational delay (~100ms per attempt).

#### Why Mitigation Is Insufficient
A 100ms delay does not prevent manual or automated guessing of simple passwords (e.g., "admin123", "password", "12345678").

#### Recommended Remediation Direction
Implement exponential backoff and lockouts for the Admin Password (e.g., 5 attempts -> 5-minute lockout, 10 attempts -> 30-minute lockout).

---

### SEC-09 — Missing `FLAG_SECURE` on Native Overlay Windows Allows Credential Sniffing

- **Finding ID**: SEC-09
- **Severity**: HIGH
- **Confidence**: CONFIRMED
- **Category**: Window Security / Data Sniffing
- **Affected File(s)**: 
  - `android/app/src/main/kotlin/com/lockkeeper/app/manager/OverlayManager.kt` (Lines 75–98)
  - `android/app/src/main/kotlin/com/lockkeeper/app/ui/PinOverlayView.kt`
  - `android/app/src/main/kotlin/com/lockkeeper/app/ui/AdminOverlayView.kt`
- **Relevant Function/Class**: `OverlayManager.createLayoutParams()`

#### Technical Description
In `OverlayManager.kt`, overlay windows are created using Android's `WindowManager.LayoutParams`:
```kotlin
private fun createLayoutParams(): WindowManager.LayoutParams {
    val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        },
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT
    )
    layoutParams.gravity = Gravity.CENTER
    return layoutParams
}
```
The window parameters omit `WindowManager.LayoutParams.FLAG_SECURE`.

#### Root Cause
Failure to treat native overlay input surfaces as security-sensitive credential boundaries.

#### Preconditions
1. Screen recording application, remote support tool (TeamViewer, AnyDesk), or malicious accessibility/media projection service is running on the device.
2. User enters PIN or Admin Password on LockKeeper's native overlay.

#### Attack/User Scenario & Exploit Path
1. Malware or commercial spyware with `MediaProjection` permission records the screen.
2. User launches a protected banking application.
3. LockKeeper displays `PinOverlayView`.
4. The user taps digits on the custom numeric keypad. The keypad visually highlights pressed digits.
5. Because `FLAG_SECURE` is absent, the screen recorder captures all keypresses and renders the PIN in high definition.

#### Impact
Plaintext capture and theft of user PINs and Admin Passwords via screen recording, screenshots, casting, or assistive tools.

#### Existing Mitigation
None.

#### Why Mitigation Is Insufficient
Without `FLAG_SECURE`, the Android WindowManager renders the overlay content into the public frame buffer.

#### Recommended Remediation Direction
Add `WindowManager.LayoutParams.FLAG_SECURE` to `createLayoutParams()` in `OverlayManager.kt`:
```kotlin
layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_SECURE
```

---

### SEC-12 — Foreground Service Fallback Explicitly Ignores Settings, Leaving Zero Tamper Protection

- **Finding ID**: SEC-12
- **Severity**: HIGH
- **Confidence**: CONFIRMED
- **Category**: Service Fallback / Anti-Tamper
- **Affected File(s)**: `android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperForegroundService.kt`
- **Exact Line(s)**: Lines 112–125
- **Relevant Function/Class**: `LockKeeperForegroundService.checkForegroundApp()`

#### Technical Description
When the `AccessibilityService` is killed or disabled, LockKeeper falls back to `LockKeeperForegroundService`, which queries `UsageStatsManager.queryUsageStats()` every 400ms. In `checkForegroundApp()`, the service detects the top package. However, lines 118–122 state:
```kotlin
if (topPackage == "com.android.settings") {
    // Ignore settings in polling fallback mode to prevent overlay loops
    return
}
```

#### Root Cause
Developer attempted to avoid overlay flicker loops caused by coarse `UsageStatsManager` granularity (which cannot inspect specific Settings sub-screens).

#### Preconditions
`AccessibilityService` is inactive or disconnected; LockKeeper is operating in `ForegroundService` fallback mode.

#### Attack/User Scenario & Exploit Path
1. Accessibility service crashes or is killed by OEM battery optimization.
2. LockKeeper switches to foreground service fallback.
3. Attacker opens Android Settings (`com.android.settings`).
4. Because `topPackage == "com.android.settings"` is explicitly ignored, no overlay is ever shown.
5. Attacker navigates to Device Admin or Apps and uninstalls LockKeeper unhindered.

#### Impact
Zero anti-tamper or self-protection capability when running in fallback mode.

#### Existing Mitigation
None.

#### Why Mitigation Is Insufficient
The fallback mode completely surrenders anti-tamper security.

#### Recommended Remediation Direction
Instead of completely ignoring `com.android.settings`, the foreground service should trigger a full-screen notification or persistent security alert instructing the user that protection is degraded, or enforce an admin overlay whenever Settings is accessed in fallback mode.

---

### SEC-17 — Android Cloud Auto-Backup of Hardware Keystore Blobs Causes Permanent Lockout

- **Finding ID**: SEC-17
- **Severity**: HIGH
- **Confidence**: CONFIRMED
- **Category**: Data Protection / Cloud Backup
- **Affected File(s)**: 
  - `android/app/src/main/AndroidManifest.xml` (Line 18)
  - `android/app/src/main/kotlin/com/lockkeeper/app/security/CredentialStore.kt`
- **Relevant Function/Class**: `<application android:allowBackup="true">`

#### Technical Description
In `AndroidManifest.xml`, the `<application>` tag specifies:
```xml
<application
    android:label="LockKeeper"
    android:name="${applicationName}"
    android:icon="@mipmap/ic_launcher"
    android:allowBackup="true">
```
No `android:fullBackupContent` or `android:dataExtractionRules` XML resource is defined.
LockKeeper stores its encrypted master key, IV, and ciphertexts in SharedPreferences (`lockkeeper_prefs.xml`). These ciphertexts are encrypted using an AES-256 key generated in the device's hardware-backed Android Keystore (`AndroidKeyStore`).

#### Root Cause
Default `allowBackup="true"` without filtering out Keystore-dependent ciphertexts.

#### Preconditions
User backs up device data to Google Drive / Android Cloud Backup and restores it onto a new device or following a factory reset.

#### Attack/User Scenario & Exploit Path
1. User backs up phone data. Android copies `lockkeeper_prefs.xml` and Room databases to Google Drive.
2. User transfers to a new device or factory-resets their device.
3. Android restores `lockkeeper_prefs.xml` and Room database.
4. The hardware Android Keystore on the new device/factory-reset device **does not contain the private key** (as Keystore keys are non-exportable hardware secrets).
5. When LockKeeper initializes, `CredentialStore` attempts to decrypt the master key using the Keystore alias.
6. The Keystore throws `KeyPermanentlyInvalidatedException` or `UnrecoverableKeyException`.
7. The app crashes on startup or becomes permanently unusable, locking the user out of all locked applications.

#### Impact
Permanent data corruption, app crash loops, and total denial of service upon cloud restore.

#### Existing Mitigation
None.

#### Why Mitigation Is Insufficient
Android Keystore keys can never be backed up or restored across device boundaries. Restoring ciphertexts without keys guarantees decryption failure.

#### Recommended Remediation Direction
Set `android:allowBackup="false"` in `AndroidManifest.xml`, or configure `res/xml/data_extraction_rules.xml` to strictly exclude all shared preferences, database files, and credential stores from cloud and device-to-device transfers.

---

### SEC-19 — Fail-Open Exception Handling on Self-Lock Gate Check Automatically Unlocks UI

- **Finding ID**: SEC-19
- **Severity**: HIGH
- **Confidence**: CONFIRMED
- **Category**: Error Handling / Fail-Open
- **Affected File(s)**: 
  - `lib/main.dart` (Lines 110–125)
  - `lib/services/platform_bridge.dart` (Lines 150–165)
- **Relevant Function/Class**: `_LockKeeperAppState._checkAndEnforceSelfLock()`

#### Technical Description
In `main.dart`, when the application resumes from the background, `_checkAndEnforceSelfLock()` queries the native platform to determine if self-lock is required:
```dart
Future<void> _checkAndEnforceSelfLock() async {
  try {
    final shouldLock = await PlatformBridge.isSelfLockRequired();
    if (shouldLock && mounted) {
      Navigator.pushNamed(context, '/self-lock-gate');
    }
  } catch (e) {
    debugPrint('Error checking self-lock: $e');
    // Fail-open: Does nothing, allowing user to remain on current screen!
  }
}
```
If `PlatformBridge.isSelfLockRequired()` throws any exception (e.g., PlatformException, timeout, channel uninitialized, Room database lock), the catch block prints a debug message and execution completes without pushing `/self-lock-gate`.

#### Root Cause
Fail-open security design in the top-level route guard.

#### Preconditions
A runtime error occurs in the platform channel, SQLite repository, or coroutine during the method call.

#### Attack/User Scenario & Exploit Path
1. Attacker induces stress or causes channel failure (or exploits native exception states).
2. `PlatformBridge.isSelfLockRequired()` throws an unhandled error.
3. Catch block swallows the exception.
4. `Navigator.pushNamed(context, '/self-lock-gate')` is bypassed.
5. The application remains open on `HomeScreen` or `SettingsScreen`.

#### Impact
Bypass of the Self-Lock access control barrier under exceptional conditions.

#### Existing Mitigation
None.

#### Why Mitigation Is Insufficient
Security controls must fail-closed (i.e., lock the screen if the security state cannot be proven safe).

#### Recommended Remediation Direction
Refactor `_checkAndEnforceSelfLock()` to fail-closed:
```dart
} catch (e) {
  debugPrint('Error checking self-lock: $e');
  if (mounted) {
    Navigator.pushNamed(context, '/self-lock-gate');
  }
}
```

---

### SEC-20 — Production Debug Logging of Navigation Classes and Settings Events via `Log.d`

- **Finding ID**: SEC-20
- **Severity**: MEDIUM
- **Confidence**: CONFIRMED
- **Category**: Privacy / Information Leak
- **Affected File(s)**: `android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperAccessibilityService.kt`
- **Exact Line(s)**: Lines 140, 168, 192
- **Relevant Function/Class**: `LockKeeperAccessibilityService.onAccessibilityEvent()`

#### Technical Description
In `LockKeeperAccessibilityService.kt`, several production `Log.d` calls log active foreground classes, package transitions, and matched keywords:
```kotlin
Log.d("LockKeeperAccess", "Processing package: $currentPackage, class: ${event.className}")
Log.d("LockKeeperAccess", "Settings node text matched: $nodeText")
```

#### Root Cause
Failure to strip debug log statements in release builds or wrap them in `if (BuildConfig.DEBUG)`.

#### Preconditions
A third-party application with `READ_LOGS` on older Android versions or any desktop computer connected via USB debugging running `adb logcat`.

#### Attack/User Scenario & Exploit Path
1. User connects phone to a shared charging station, computer, or runs diagnostic logging.
2. LockKeeper logs every package navigated to and specific settings viewed.
3. An observer reconstructs user application usage patterns and settings modifications.

#### Impact
Metadata leakage regarding user app launches, UI navigation paths, and security events.

#### Existing Mitigation
ProGuard/R8 can strip logging, but R8 minification is currently disabled in release mode (SEC-27).

#### Why Mitigation Is Insufficient
Since R8 shrinking is disabled, all `Log.d` strings are retained verbatim in the production DEX.

#### Recommended Remediation Direction
1. Strip all `Log.d` calls in release builds via ProGuard rules:
```proguard
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
```
2. Enable R8 minification in `build.gradle.kts`.

---

### SEC-21 — Target App Visual Leak During Overlay Back/Cancel Navigation

- **Finding ID**: SEC-21
- **Severity**: MEDIUM
- **Confidence**: CONFIRMED
- **Category**: Information Disclosure / Visual Leak
- **Affected File(s)**: `android/app/src/main/kotlin/com/lockkeeper/app/manager/OverlayManager.kt`
- **Exact Line(s)**: Lines 142–156
- **Relevant Function/Class**: `OverlayManager.dismissOverlay()`

#### Technical Description
When a protected app is opened, LockKeeper displays `PinOverlayView`. If the user taps the Android Back button or the Cancel button on the overlay, `OverlayManager` dismisses the overlay and immediately executes `performGlobalAction(GLOBAL_ACTION_HOME)`. 
Between the moment the overlay view is removed from `WindowManager` via `removeView(activeOverlayView)` and the moment Android's window manager completes the transition to the home screen (which takes 150–300ms), the underlying target application's active window is visible on the display.

#### Root Cause
Removing the blocking overlay window before ensuring the foreground activity has been displaced by the Home screen.

#### Preconditions
A protected application contains sensitive information (e.g., bank balance, private chat) visible in its last activity state.

#### Attack/User Scenario & Exploit Path
1. Attacker opens victim's protected Telegram app.
2. LockKeeper overlay appears.
3. Attacker taps "Cancel" or "Back".
4. The overlay vanishes immediately.
5. The confidential Telegram chat is clearly visible for ~200ms before the device switches to Home.
6. Attacker takes a photo or screen capture of the exposed data.

#### Impact
Transient visual data exposure of confidential application screens.

#### Existing Mitigation
None.

#### Why Mitigation Is Insufficient
Asynchronous window manager transitions cannot guarantee zero visual exposure if the blocking window is detached immediately.

#### Recommended Remediation Direction
Execute `GLOBAL_ACTION_HOME` **first**, delay removing the overlay view for 200–300ms until the home screen intent is active, or render a solid black/theme background until the transition finishes.

---

### SEC-22 — Recents Overview Task Snapshot Leaks Unprotected App Frames

- **Finding ID**: SEC-22
- **Severity**: MEDIUM
- **Confidence**: CONFIRMED
- **Category**: Information Disclosure / Recents Leak
- **Affected File(s)**: 
  - `android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperAccessibilityService.kt`
  - `android/app/src/main/kotlin/com/lockkeeper/app/manager/OverlayManager.kt`
- **Relevant Function/Class**: `LockKeeperAccessibilityService`

#### Technical Description
When an app is protected by LockKeeper, the protection relies on displaying a `TYPE_APPLICATION_OVERLAY` window over the target app's window. However, when the user opens Android's "Recent Apps" / Overview screen, the Android system renders a cached task snapshot (bitmap thumbnail) of the target application taken at the moment it lost focus. LockKeeper's overlay is an external window and is **not** included in the target app's task snapshot.

#### Root Cause
Architectural limitation of accessibility/overlay lockers. Third-party applications cannot apply `FLAG_SECURE` to external applications.

#### Preconditions
Protected app is launched, used, and backgrounded; user opens the Recents/Overview screen.

#### Attack/User Scenario & Exploit Path
1. Attacker swipes up to open the Android Recents screen.
2. The thumbnails for all backgrounded applications are rendered in full fidelity.
3. Attacker reads sensitive data directly from the thumbnail cards without unlocking the app.

#### Impact
Visual information disclosure via the Recents switcher.

#### Existing Mitigation
None.

#### Why Mitigation Is Insufficient
Native overlays do not mask OS-level task snapshot thumbnails.

#### Recommended Remediation Direction
Document this boundary transparently to users. In AccessibilityService, detect `TYPE_WINDOW_STATE_CHANGED` for `com.android.systemui.recents` and blur or dismiss the app if possible, or advise users to enable built-in app locking for apps that support native `FLAG_SECURE`.

---

### SEC-24 — Substring Matching & Stale Connected Status in Accessibility State Check

- **Finding ID**: SEC-24
- **Severity**: MEDIUM
- **Confidence**: CONFIRMED
- **Category**: Accessibility Verification / State Inconsistency
- **Affected File(s)**: `android/app/src/main/kotlin/com/lockkeeper/app/bridge/PlatformChannelHandler.kt`
- **Exact Line(s)**: Lines 145–160
- **Relevant Function/Class**: `PlatformChannelHandler.isAccessibilityPermissionGranted()`

#### Technical Description
In `PlatformChannelHandler.kt`, checking whether Accessibility is enabled queries `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` and checks if the string contains the app package name:
```kotlin
val enabledServices = Settings.Secure.getString(
    context.contentResolver,
    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
) ?: ""
val isEnabled = enabledServices.contains(context.packageName)
result.success(isEnabled)
```
This check only verifies whether the toggle is switched ON in Android Settings. It does **not** verify whether `LockKeeperAccessibilityService.instance` is currently connected or if the service was killed/crashed.

#### Root Cause
Confusing OS permission grant state with runtime service connection state.

#### Preconditions
Accessibility service is enabled in Settings, but the process or service crashed or was killed by an OEM task killer.

#### Attack/User Scenario & Exploit Path
1. Android OS kills `LockKeeperAccessibilityService` to reclaim memory.
2. User opens LockKeeper Flutter app.
3. The dashboard queries `PlatformBridge.isAccessibilityPermissionGranted()`.
4. The method returns `true` because the package name is present in `ENABLED_ACCESSIBILITY_SERVICES`.
5. The UI displays a green "Protected / Active" status badge.
6. In reality, the service is dead and zero applications are being locked or protected.

#### Impact
False sense of security. Users believe their phone is protected when no protection is active.

#### Existing Mitigation
None.

#### Why Mitigation Is Insufficient
`Settings.Secure` persists the user's configuration, not the runtime liveness of the service.

#### Recommended Remediation Direction
Implement a dual check:
```kotlin
val isServiceRunning = LockKeeperAccessibilityService.instance != null
val isSettingEnabled = enabledServices.contains("${context.packageName}/${LockKeeperAccessibilityService::class.java.canonicalName}")
result.success(isServiceRunning && isSettingEnabled)
```

---

### SEC-29 — System Wall-Clock Manipulation Can Indefinitely Extend Admin Grace Window

- **Finding ID**: SEC-29
- **Severity**: LOW
- **Confidence**: CONFIRMED
- **Category**: Time Manipulation / Grace Period
- **Affected File(s)**: `android/app/src/main/kotlin/com/lockkeeper/app/service/LockDecisionEngine.kt`
- **Exact Line(s)**: Lines 65–75
- **Relevant Function/Class**: `LockDecisionEngine.recordAdminUnlock()` & `isAdminUnlocked()`

#### Technical Description
In `LockDecisionEngine.kt`, the admin grace window timestamp is calculated using `System.currentTimeMillis()`:
```kotlin
fun recordAdminUnlock() {
    adminUnlockedUntil = System.currentTimeMillis() + ADMIN_GRACE_PERIOD_MS
}
fun isAdminUnlocked(): Boolean {
    return System.currentTimeMillis() < adminUnlockedUntil
}
```
`System.currentTimeMillis()` represents the device wall-clock time, which can be modified by the user in Android Settings.

#### Root Cause
Using wall-clock time (`System.currentTimeMillis()`) instead of monotonic boot time (`SystemClock.elapsedRealtime()`).

#### Preconditions
Admin has authenticated, initiating the 5-minute grace period.

#### Attack/User Scenario & Exploit Path
1. Admin enters password to configure an app. Grace period is set to `Current Time + 300,000ms`.
2. Attacker gets hold of the device while the grace period is still active.
3. Attacker opens Settings and rolls the device clock backward by 24 hours.
4. `System.currentTimeMillis() < adminUnlockedUntil` will evaluate to `true` for the next 24 hours.
5. The admin overlay will never appear during this period.

#### Impact
Unauthorized extension of the privileged administrative window.

#### Existing Mitigation
None.

#### Why Mitigation Is Insufficient
Wall-clock time is entirely user-controllable.

#### Recommended Remediation Direction
Replace all timestamp tracking with `SystemClock.elapsedRealtime()`, which cannot be manipulated by manual clock adjustments or network NTP updates.

---

### SEC-31 — Plaintext Credentials Stored in Immutable Heap String Objects

- **Finding ID**: SEC-31
- **Severity**: LOW
- **Confidence**: CONFIRMED
- **Category**: Memory Hygiene / Secret Retention
- **Affected File(s)**: 
  - `lib/screens/self_lock_gate_screen.dart` (Line 25)
  - `android/app/src/main/kotlin/com/lockkeeper/app/bridge/PlatformChannelHandler.kt` (Lines 82, 95)
- **Relevant Function/Class**: `_pinBuffer`, `verifyPin(pin: String)`

#### Technical Description
Throughout both the Dart UI layer and the Kotlin native bridge, PINs and Admin Passwords are represented as immutable `String` objects (`String pin`, `var _pinBuffer = ""`).
Because strings in Dart and Java/Kotlin are immutable, their underlying character arrays cannot be overwritten (`0`ed out) in memory after verification completes. They remain in heap memory until collected and overwritten by garbage collection cycles.

#### Root Cause
Use of immutable String primitives for credential processing instead of mutable `CharArray` or byte buffers.

#### Preconditions
Device memory dump via root access, core dump, or forensic memory acquisition tool.

#### Attack/User Scenario & Exploit Path
1. User enters PIN and Admin Password.
2. Device is seized or examined via memory imaging.
3. Plaintext PINs and passwords are recovered from heap string intern pools.

#### Impact
Exposure of sensitive credentials in device RAM.

#### Existing Mitigation
None.

#### Why Mitigation Is Insufficient
Garbage collection does not sanitize memory contents upon deallocation.

#### Recommended Remediation Direction
Use `CharArray` or byte arrays in native code where possible and explicitly overwrite them with zeros immediately after PBKDF2 hashing.
