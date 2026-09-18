# Agent 11: UI/UX & Security-Truth Auditor Report

**Date:** 2026-09-17  
**Auditor:** Agent 11 — UI/UX & Security-Truth Auditor  
**Target:** Flutter Screens, Native Overlay Views, Security Messaging & System Branding  
**Status:** COMPLETE (Hostile UX & Security Integrity Review)

---

## 1. Executive Summary

This audit evaluated the user experience, deceptive design risks, accessibility compliance, and truth-in-security messaging across LockKeeper's Flutter UI and native Android overlays.

**Core Findings:**
1. **Deceptive "Protected" Messaging on Dead Services:**
   In `lib/ui/screens/home_screen.dart` and `PlatformChannelHandler.kt:250`, when Accessibility is enabled in Android settings but the background service has crashed, the UI displays green badges: *"Protection Operational: All Protections Active"*. This gives users a false sense of security while their apps are completely unprotected.
2. **Missing Lockout Feedback in Flutter Settings:**
   When an admin is locked out due to 5 failed attempts, entering a password in `lib/ui/screens/settings_screen.dart` simply displays *"Incorrect password"*. It does NOT inform the user that the account is locked out, nor does it display the remaining lockout countdown. The user is left confused as to why even the correct password is now rejected.
3. **Double-Submit on Password Verification:**
   In `AdminOverlayView.kt:161-167`, clicking the "Unlock (30s)" button does not disable the button or show a progress spinner while PBKDF2 executes. Users can repeatedly tap the button, triggering multiple concurrent hashing operations and race conditions.
4. **Static Lockout Display (No Countdown Timer):**
   `AdminOverlayView.kt:179` displays: *"Too many incorrect attempts. Locked out for 300 seconds."* The text is completely static; there is no timer ticking down. The user has no indication of when the lockout expires.
5. **System Non-Impersonation (Compliant):**
   `AdminOverlayView` clearly identifies itself with a prominent badge: *"🛡️ LOCKKEEPER ANTI-TAMPER"* and explanatory text. It does not mimic Android OS system dialogs.

---

## 2. Security Truth & Deceptive Claims Audit

### 2.1 The Green Badge Illusion
In `lib/ui/screens/home_screen.dart`:
```dart
bool get isFullyProtected =>
    status.isForegroundServiceRunning &&
    status.isOverlayGranted &&
    status.isUsageGranted &&
    status.isAccessibilityGranted &&
    status.isDeviceAdminGranted;
```
When `isFullyProtected` is true, the home screen renders:
```dart
Text('Protection Operational', style: ... TextStyle(color: AppTheme.successColor)),
Text('All 5 core protections are active and safeguarding your applications.', ...)
```

#### Why This is False:
1. `status.isAccessibilityGranted` is determined in Kotlin by:
   `isServiceConnected || enabledServices.contains(context.packageName)`.
2. If Android's battery optimizer killed the Accessibility Service, `isServiceConnected` is false, but `enabledServices` contains the package.
3. `isFullyProtected` evaluates to `true`!
4. The dashboard proudly claims "All 5 core protections are active", when in reality the Accessibility sensor is DEAD, and not a single app is being locked or protected.
5. **Violation:** Invariant 10 states: *"The UI must never display stronger security status than native state supports."*

---

### 2.2 Flutter Settings Screen Lockout Blindness
In `lib/ui/screens/settings_screen.dart:177`:
```dart
final isCurrentValid = await PlatformBridge.verifyAdminPassword(current);
if (!isCurrentValid) {
    ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Incorrect admin password'), backgroundColor: AppTheme.errorColor),
    );
    return;
}
```

#### Why This is Problematic:
- If 5 wrong attempts were entered (either in the overlay or in settings), `verifyAdminPassword` enters a 300-second lockout.
- When the user enters their real password during lockout, `verifyAdminPassword` returns `false`.
- Flutter displays: *"Incorrect admin password"*.
- The user believes they forgot their password and tries alternate passwords or panics, unaware that the system is simply under temporary lockout.

---

## 3. Native Overlay Interaction & Accessibility Audit

### 3.1 `AdminOverlayView.kt` Review

#### Branding & Anti-Spoofing:
- Root container features dark background (`#180D0D`).
- Prominent badge: `"🛡️ LOCKKEEPER ANTI-TAMPER"` (`#F87171` text on `#3F1212` background).
- Subtitle: `"LockKeeper self-protection detected an attempt to modify or remove protections. Enter your Admin Password to continue."`
- **Assessment:** **PASS**. Highly transparent. Clear attribution to LockKeeper. Does not impersonate AOSP or vendor system dialogs.

#### Focus & Keypad UX:
- Input field: `inputEditText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD`.
- Password characters are masked.
- `imeOptions = EditorInfo.IME_ACTION_DONE`. Pressing enter on keyboard triggers submission.
- **Flaw:** Clicking the submit button does NOT hide the soft keyboard or disable input while PBKDF2 runs.

#### Back & Cancel Mechanics:
- Includes dedicated "Back / Cancel" button (`cancelButton`).
- Pressing Cancel triggers:
  `onCancelClicked() -> onDismissAction(false) -> performGlobalAction(GLOBAL_ACTION_BACK)`.
- This correctly redirects the user away from the protected screen.
- Hardware back button is trapped in `dispatchKeyEvent` and mapped to `onCancelClicked()`.

#### TalkBack & WCAG Deficiencies:
1. `contentDescription` is provided on all interactive views.
2. However, error messages are set via `errorTextView.text = message`.
3. `errorTextView` lacks `android:accessibilityLiveRegion="polite"`. When an error or lockout occurs, TalkBack will NOT announce the failure unless the user manually explores the screen to find the error view.

---

## 4. Invariant Evaluation

| Invariant | Description | Result | Evidence |
|---|---|---|---|
| **INV-10** | UI must never claim stronger security status than native state supports | **FAIL** | Home screen claims "Protection Operational" when Accessibility service is disconnected. |
| **INV-UX-1** | Overlays must not impersonate Android OS system UI | **PASS** | Prominent LockKeeper branding and red shield badge. |
| **INV-UX-2** | Lockout status must be communicated to user across all entry points | **FAIL** | Flutter Settings swallows lockout status and reports generic "Incorrect password". |

---

## 5. Auditor Conclusion

While the native `AdminOverlayView` is cleanly branded and avoids deceptive system spoofing, the broader application suffers from significant truth-in-security defects. Flutter falsely reports operational protection when the service is dead, and the Settings UI completely blinds users to active lockouts.
