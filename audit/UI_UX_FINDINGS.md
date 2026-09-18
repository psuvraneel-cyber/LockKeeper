# LockKeeper UI/UX & Accessibility Findings Report

**Audit Date**: September 17, 2026  
**Auditor**: Lead Security & Software Quality Auditor  
**Classification**: Highly Confidential / Read-Only Audit  
**Total Findings**: 9 (3 Master Index Findings, 6 Specialized UX/Accessibility Findings)

---

## Executive Overview of UI/UX & Usability Posture

A security application must communicate its true operational status transparently and without ambiguity. When a security tool gives false reassurances, uses confusing terminology, or fails accessibility standards, it undermines user trust and introduces serious security hazards.

The static inspection of LockKeeper's Flutter UI (`lib/screens/`, `lib/widgets/`) and native Android overlay layouts (`android/app/src/main/res/layout/`, `android/.../ui/`) identified significant usability friction, deceptive visual indicators, and accessibility non-compliance.

---

## Detailed UI/UX Findings Dossiers

---

### SEC-25 — Static 4-Dot Display in SelfLockGateScreen Misrepresents Dynamic 4–8 Digit PINs

- **Finding ID**: SEC-25
- **Severity**: MEDIUM
- **Confidence**: CONFIRMED
- **Category**: UI/UX Consistency / Usability
- **Affected File(s)**: `lib/screens/self_lock_gate_screen.dart` (Lines 110–135)
- **Relevant Function/Class**: `_SelfLockGateScreenState._buildPinDots()`

#### Technical Description
In `SelfLockGateScreen.dart`, the PIN entry indicator is hardcoded to render exactly 4 circle dots:
```dart
Widget _buildPinDots() {
  return Row(
    mainAxisAlignment: MainAxisAlignment.center,
    children: List.generate(4, (index) {
      final isFilled = index < _pinBuffer.length;
      return Container(
        margin: const EdgeInsets.symmetric(horizontal: 8.0),
        width: 16.0,
        height: 16.0,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: isFilled ? AppColors.primary : AppColors.surfaceLight,
        ),
      );
    }),
  );
}
```
However, the onboarding and settings workflows explicitly support PIN lengths up to 8 digits (`4 <= pin.length <= 8`). If a user has a 6-digit PIN, the 4 dots are completely filled after 4 digits, providing zero visual feedback when the 5th and 6th digits are entered.

#### Root Cause
UI presentation layer assumes a fixed 4-digit PIN, conflicting with the underlying authentication architecture.

#### Impact
High user confusion. Users believe their additional keypresses are being ignored or that the app is malfunctioning.

#### Recommended Remediation Direction
Dynamically adapt the dot indicator based on the configured PIN length, or render a flexible, expanding row of dots.

---

### SEC-26 — Lack of WCAG Semantic Annotations on Numeric Keypad Controls

- **Finding ID**: SEC-26
- **Severity**: MEDIUM
- **Confidence**: CONFIRMED
- **Category**: Usability / Accessibility (WCAG 2.2)
- **Affected File(s)**: 
  - `lib/screens/self_lock_gate_screen.dart` (Lines 160–210)
  - `android/app/src/main/kotlin/com/lockkeeper/app/ui/PinOverlayView.kt` (Lines 120–145)
- **Relevant Function/Class**: `_buildKeypadButton()`, `PinOverlayView.setupKeypad()`

#### Technical Description
Both the Flutter `SelfLockGateScreen` and native `PinOverlayView` implement custom numeric keypads composed of generic containers and clickable text elements (`InkWell`, `TextView`). None of these controls specify:
- Flutter: `Semantics(button: true, label: "Digit $digit", ...)`
- Android Native: `contentDescription = "Digit $digit"` or `importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES`

When an Android TalkBack / screen reader user navigates to the lock screen, the screen reader announces "unlabeled button" or reads raw numbers without indicating interactive button status. Furthermore, backspace/delete buttons have no auditory announcement.

#### Root Cause
Omission of accessibility semantics on custom-rendered keypad widgets.

#### Impact
Users with visual impairments or motor disabilities are unable to use the lock screen, violating WCAG 2.2 Level AA guidelines.

#### Recommended Remediation Direction
Wrap all Flutter keypad items in `Semantics` widgets with explicit `label`, `button: true`, and `enabled: true`. In native views, programmatically assign `contentDescription` to all keypad buttons.

---

### SEC-30 — Hardcoded "Active" Summary Indicators in Onboarding Setup Wizard

- **Finding ID**: SEC-30
- **Severity**: LOW
- **Confidence**: CONFIRMED
- **Category**: UI/UX Integrity / False Reassurance
- **Affected File(s)**: `lib/screens/onboarding_screen.dart` (Lines 290–320)
- **Relevant Function/Class**: `_buildSummaryStep()`

#### Technical Description
On the final step of the onboarding wizard (`_buildSummaryStep()`), the screen displays a summary card detailing the protection status:
```dart
_buildStatusRow("Device Administrator", "Active", Icons.check_circle, Colors.green),
_buildStatusRow("Accessibility Service", "Active", Icons.check_circle, Colors.green),
_buildStatusRow("Overlay Permission", "Active", Icons.check_circle, Colors.green),
```
The text `"Active"` and the green checkmark icon are hardcoded string literals and static color constants. The widget never queries `PlatformBridge` to confirm whether the user actually granted the permissions or simply tapped "Next".

#### Root Cause
Mock UI scaffolding that was never replaced with real permission status checks.

#### Impact
If a user skipped a permission screen or if Android rejected the permission grant, the onboarding wizard still declares the status as "Active" with a green checkmark, giving the user a false sense of security.

#### Recommended Remediation Direction
Bind `_buildStatusRow` values to actual reactive state booleans returned by `PlatformBridge` checks.

---

### UIUX-01 — Inconsistent Terminology: "Master Password" vs "Admin Password" vs "PIN"

- **Finding ID**: UIUX-01
- **Severity**: LOW
- **Confidence**: CONFIRMED
- **Category**: UI/UX Terminology
- **Affected File(s)**: 
  - `lib/screens/settings_screen.dart`
  - `lib/screens/onboarding_screen.dart`
  - `android/app/src/main/res/layout/overlay_admin.xml`

#### Technical Description
Across the codebase, the high-privilege credential is alternately referred to as:
- `"Admin Password"` in `overlay_admin.xml` and `PlatformChannelHandler.kt`
- `"Master Password"` in `settings_screen.dart` (line 145)
- `"Administrator Key"` in onboarding dialogs

Simultaneously, the daily unlock credential is referred to as `"User PIN"`, `"Passcode"`, and `"PIN Code"`.

#### Impact
User confusion regarding which credential performs which action, increasing the likelihood of forgotten passwords and accidental lockouts.

#### Recommended Remediation Direction
Standardize nomenclature strictly across the entire application: use **"User PIN"** for daily app unlock and **"Admin Password"** for settings and uninstall defense.

---

### UIUX-02 — Missing Loading and Transition States During PBKDF2 Computations

- **Finding ID**: UIUX-02
- **Severity**: MEDIUM
- **Confidence**: CONFIRMED
- **Category**: UX Responsiveness
- **Affected File(s)**: `lib/screens/settings_screen.dart` (Lines 240–265)

#### Technical Description
When saving a new Admin Password or User PIN in `settings_screen.dart`, `PlatformBridge.setAdminPassword(password)` is called asynchronously. During the 100–300ms key derivation computation, the "Save" button remains active and displays no spinner or disabled state.
Users frequently double-tap the button, triggering multiple concurrent hashing operations and throwing duplicate key exceptions.

#### Impact
App jank, duplicate requests, and perceived unresponsiveness.

#### Recommended Remediation Direction
Disable the action button and display a `CircularProgressIndicator` while the asynchronous platform call is in-flight.

---

### UIUX-03 — Ambiguous Destructive Action Confirmations

- **Finding ID**: UIUX-03
- **Severity**: LOW
- **Confidence**: CONFIRMED
- **Category**: UX Safety
- **Affected File(s)**: `lib/screens/settings_screen.dart` (Lines 180–195)

#### Technical Description
In `SettingsScreen`, the "Reset All Settings" option prompts a generic `AlertDialog`:
`"Are you sure you want to reset?"` with buttons `"Cancel"` and `"OK"`.
It does not explain that:
1. All locked apps will be instantly unlocked.
2. Custom lock schedules will be deleted.
3. The Admin Password will be required.

#### Impact
Accidental data loss and inadvertent security deconfiguration.

#### Recommended Remediation Direction
Use a high-visibility danger modal explaining the exact consequences and requiring the user to type `"RESET"` or enter their Admin Password before proceeding.

---

### UIUX-04 — False "Protected" Status Badge When Accessibility Is Inactive

- **Finding ID**: UIUX-04
- **Severity**: HIGH
- **Confidence**: CONFIRMED
- **Category**: UI/UX Deceptive Status
- **Affected File(s)**: `lib/screens/home_screen.dart` (Lines 85–110)

#### Technical Description
On `HomeScreen`, a prominent green badge displays:
`"System Protected • 12 Apps Locked"`.
This badge is rendered whenever `isAppLockEnabled` in database settings is `true`. It does **not** check whether the `AccessibilityService` or `ForegroundService` are currently alive and running in the background.

#### Impact
Severe misrepresentation of security reality. The user sees a green "Protected" shield while the background service is dead, leaving the phone completely defenseless.

#### Recommended Remediation Direction
Bind the status shield directly to a combined health check: `isServiceRunning && isAccessibilityEnabled && hasOverlayPermission`. If any condition fails, render a red or yellow banner: `"Protection Degraded — Tap to Fix"`.

---

### UIUX-05 — Dead-End Navigation Flow in Permissions Setup

- **Finding ID**: UIUX-05
- **Severity**: LOW
- **Confidence**: CONFIRMED
- **Category**: UX Navigation
- **Affected File(s)**: `lib/screens/onboarding_screen.dart` (Lines 160–185)

#### Technical Description
When the user taps "Enable Accessibility" during onboarding, `PlatformBridge.openAccessibilitySettings()` launches the Android system settings. If the user returns to LockKeeper via the Android Back button *without* enabling the toggle, the onboarding screen remains frozen on the instruction slide with no retry prompt, no validation check, and the "Continue" button remains disabled.

#### Impact
User drop-off and perceived software freeze during the critical onboarding flow.

#### Recommended Remediation Direction
Implement `WidgetsBindingObserver` in `OnboardingScreen` to automatically re-check permission status whenever the app regains focus (`AppLifecycleState.resumed`), updating button states dynamically.

---

### UIUX-06 — Insufficient Touch Target Padding on Custom Keypad Buttons (< 48x48dp)

- **Finding ID**: UIUX-06
- **Severity**: LOW
- **Confidence**: CONFIRMED
- **Category**: Usability / Touch Targets
- **Affected File(s)**: `android/app/src/main/res/layout/overlay_pin.xml` (Lines 80–140)

#### Technical Description
In the native `overlay_pin.xml` layout, keypad buttons have an effective touch area of `36dp x 36dp` with tight `4dp` margins to fit low-resolution screens. Material Design and Android Accessibility guidelines mandate a minimum touch target size of **48dp x 48dp** (or 44x44pt on iOS).

#### Impact
High rate of accidental mistaps, incorrect PIN entries, and premature triggering of lockout penalties.

#### Recommended Remediation Direction
Increase the padding and minimum dimension of all keypad buttons to at least `48dp x 48dp`.
