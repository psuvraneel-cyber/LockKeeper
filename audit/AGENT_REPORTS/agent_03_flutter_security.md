# Agent 03 — Flutter Application Security & Logic Audit Report

**Auditor Persona**: Senior Flutter Security & Architecture Auditor  
**Target Repository**: LockKeeper (`C:\AppLocker\lib`)  
**Audit Date**: September 17, 2026  
**Scope**: Flutter Architecture, State Management, Navigation, Platform Bridge, Self-Lock Gate Logic, Authentication Flows, and Client-Side Error Handling.

---

## 1. Executive Assessment of Flutter Codebase

The Flutter presentation layer consists of 5 primary screens (`OnboardingScreen`, `HomeScreen`, `AppDetailScreen`, `SettingsScreen`, `SelfLockGateScreen`), a theme file (`AppTheme`), a single communication bridge (`PlatformBridge`), and 3 models.

While the UI is well-structured and uses modern dark themes with custom styling, the Flutter layer contains multiple severe logic bugs, including a catastrophic denial-of-service/lockout flaw for multi-digit PINs, fail-open exception handlers, and root state desynchronization that completely disables self-lock on the application's first launch.

---

## 2. In-Depth Vulnerability & Logic Flaw Analysis

### 2.1 Critical PIN Auto-Submit Bug in `SelfLockGateScreen` (VULN-FLUT-01)
- **Severity**: CRITICAL
- **Confidence**: CONFIRMED
- **File**: `lib/ui/screens/self_lock_gate_screen.dart`, Lines 91–95, 285–289
```dart
// Number key pressed
if (_pinBuffer.length < 8) {
  setState(() {
    _pinBuffer.write(key);
  });

  // Auto-submit if PIN length is reached
  if (_pinBuffer.length >= 4) {
    _submitPin();
  }
}
```
and:
```dart
Row(
  mainAxisAlignment: MainAxisAlignment.center,
  children: List.generate(4, (index) => _buildDot(index)),
)
```
- **Technical Vulnerability**:
  - In `OnboardingScreen.dart` (lines 85–88) and `SettingsScreen.dart` (line 99), LockKeeper explicitly permits users to configure PINs between **4 and 8 digits** (`pin.length < 4 || pin.length > 8`).
  - However, in `SelfLockGateScreen.dart`, the keypad handler hardcodes an auto-submit condition at `>= 4` digits!
  - As soon as a user who configured a 5, 6, 7, or 8-digit PIN enters their 4th digit, `_submitPin()` is immediately triggered with only the first 4 digits!
  - Digits 5 through 8 can **never be entered**.
  - Every attempt fails verification (`verifySelfLockPin`). After 5 attempts, the user is locked out for 60 seconds.
  - When lockout expires, any attempt to enter the PIN fails again on the 4th keystroke.
  - **Result**: Any user who configures a 5–8 digit PIN is **permanently locked out of LockKeeper's UI**.

### 2.2 Root Gate Desynchronization Disabling Self-Lock (VULN-FLUT-02)
- **Severity**: HIGH
- **Confidence**: CONFIRMED
- **File**: `lib/main.dart`, Lines 29, 89, 118–147; `lib/ui/screens/onboarding_screen.dart`, Lines 130–138
- **Technical Mechanism**:
  - `main()` reads `isOnboardingComplete` once during bootstrap and instantiates `LockKeeperApp(isOnboardingComplete: onboardingComplete)`.
  - In `LockKeeperApp`:
    - `widget.isOnboardingComplete` is a `final bool`.
    - Lifecycle listener: `didChangeAppLifecycleState(AppLifecycleState state)` exits immediately with `if (!widget.isOnboardingComplete) return;`.
    - MaterialApp builder:
      ```dart
      builder: (context, child) {
        if (!widget.isOnboardingComplete) {
          return child!;
        }
        ...
        return Stack(children: [child!, if (_isLocked) Positioned.fill(child: SelfLockGateScreen(...))]);
      }
      ```
  - When the user completes onboarding, `_finishOnboarding()` calls `Navigator.pushReplacement(MaterialPageRoute(builder: (_) => const HomeScreen()))`.
  - `LockKeeperApp` is never updated. `widget.isOnboardingComplete` remains `false`.
  - Because `widget.isOnboardingComplete` is `false`, `SelfLockGateScreen` is **never rendered**, and background/foreground lifecycle changes are **never reported**.
  - **Result**: The entire first launch session post-onboarding is completely unguarded. An attacker picking up the phone can access all settings and app configurations without entering a PIN.

### 2.3 Fail-Open Exception Handling on Self-Lock Gate Check (VULN-FLUT-03)
- **Severity**: HIGH
- **Confidence**: CONFIRMED
- **File**: `lib/main.dart`, Lines 69–84
```dart
try {
  final required = await PlatformBridge.checkSelfLockRequired();
  if (mounted) {
    setState(() {
      _isLocked = required;
      _isInitialized = true;
    });
  }
} catch (_) {
  if (mounted) {
    setState(() {
      _isLocked = false;
      _isInitialized = true;
    });
  }
}
```
- **Technical Vulnerability**:
  - If `PlatformBridge.checkSelfLockRequired()` encounters an exception (e.g., channel failure, SQLite lock contention, or native timeout), the catch block explicitly sets `_isLocked = false`!
  - This is a textbook **fail-open** security flaw. If the security subsystem is degraded or crashing, the gate unlocks itself rather than denying access.

### 2.4 Unrestricted Brute-Force in Settings Dialogs (VULN-FLUT-04)
- **Severity**: HIGH
- **Confidence**: CONFIRMED
- **File**: `lib/ui/screens/settings_screen.dart`, Lines 89–120, 211–235, 285–297
- **Technical Mechanism**:
  - In `_showChangePinDialog()`, `PlatformBridge.verifyPin(current)` is called.
  - In `_showRequireAdminPasswordDialog()`, `PlatformBridge.verifyAdminPassword(pass)` is called.
  - In `_showChangeAdminPasswordDialog()`, `PlatformBridge.verifyAdminPassword(current)` is called.
  - None of these dialogs implement backoff, failed attempt limits, or lockout states.
  - Because the native side does not throttle these methods (unlike `verifySelfLockPin`), an attacker can sit in the settings dialog and attempt thousands of combinations without lockout.

### 2.5 Stack Overlay Accessibility & Focus Leaks (VULN-FLUT-05)
- **Severity**: MEDIUM
- **Confidence**: CONFIRMED
- **File**: `lib/main.dart`, Lines 127–141
```dart
return Stack(
  children: [
    child ?? const SizedBox.shrink(),
    if (_isLocked)
      Positioned.fill(
        child: SelfLockGateScreen(
          onUnlocked: () {
            setState(() {
              _isLocked = false;
            });
          },
        ),
      ),
  ],
);
```
- In Flutter, placing an overlay widget in a `Stack` does not automatically remove the underlying `child` from accessibility or semantic trees unless wrapped in an `ExcludeSemantics` or `FocusScope` boundary.
- Screen readers (TalkBack) and keyboard navigators can traverse to focusable nodes inside the underlying `HomeScreen` or `SettingsScreen` beneath `SelfLockGateScreen`.

### 2.6 Hardcoded "Active" Summary in Onboarding (VULN-FLUT-06)
- **Severity**: LOW
- **Confidence**: CONFIRMED
- **File**: `lib/ui/screens/onboarding_screen.dart`, Lines 535–536
```dart
_buildSummaryRow('User PIN Configured', true),
_buildSummaryRow('Admin Password Configured', true),
```
- The setup completion step hardcodes `true` for PIN and Admin Password configuration.
- It does not query `PlatformBridge.hasPin()` or `PlatformBridge.hasAdminPassword()`, giving the user false feedback that credentials are saved even if an error occurred during storage.

---

## 3. Flutter Summary Table

| ID | Title | Severity | Confidence | Impact |
|---|---|---|---|---|
| **VULN-FLUT-01** | SelfLockGate Auto-Submits at 4 Digits | CRITICAL | CONFIRMED | Users with 5–8 digit PINs are permanently locked out of app |
| **VULN-FLUT-02** | Flutter Root State Desynchronization | HIGH | CONFIRMED | Self-Lock gate completely disabled on initial run after onboarding |
| **VULN-FLUT-03** | Fail-Open Exception Catch in Self-Lock Gate | HIGH | CONFIRMED | UI automatically unlocks if channel throws exception |
| **VULN-FLUT-04** | Unlimited Brute-Force in Settings Dialogs | HIGH | CONFIRMED | PIN and Admin Password can be brute-forced without lockout |
| **VULN-FLUT-05** | Semantic/Accessibility Tree Leak Under Stack | MEDIUM | CONFIRMED | TalkBack can focus underlying widgets beneath gate screen |
| **VULN-FLUT-06** | Hardcoded Summary Status in Onboarding Wizard | LOW | CONFIRMED | Misleading setup completion indicators |
