# Agent 06 — UI/UX & Usability/Accessibility Audit Report

**Auditor Persona**: Senior Mobile Product Designer & Accessibility Specialist (WCAG / Android a11y)  
**Target Repository**: LockKeeper (`C:\AppLocker\lib\ui`)  
**Audit Date**: September 17, 2026  
**Scope**: Flutter UI/UX, Design Consistency, Semantic Annotations, Color Contrast, Navigation Flows, User Error Handling, and Security Indicators.

---

## 1. Executive Summary of UI/UX Inspection

LockKeeper presents a polished, modern visual aesthetic adhering to a dark-mode slate color palette (`#0B0F19`, `#151D2E`, `#38BDF8`). The typography and component hierarchy are clean.

However, a thorough forensic audit reveals severe UX and accessibility flaws:
1. Critical discrepancy between configured PIN length (up to 8 digits) and the fixed 4-dot UI in `SelfLockGateScreen`.
2. Misleading security claims in the onboarding flow regarding uninstall protection and Safe Mode.
3. False security indicators claiming credentials are valid without querying the backend store.
4. Total lack of semantic labeling (`Semantics`) on custom numeric keypad buttons, degrading screen reader usability.
5. High touch-target crowding on smaller phone displays.

---

## 2. Detailed Findings & Usability Defects

### 2.1 Fixed 4-Dot Display on Dynamic Length PIN (UIUX-01)
- **Severity**: HIGH (Direct contributor to VULN-FLUT-01)
- **Confidence**: CONFIRMED
- **File**: `lib/ui/screens/self_lock_gate_screen.dart`, Lines 285–289
```dart
Row(
  mainAxisAlignment: MainAxisAlignment.center,
  children: List.generate(4, (index) => _buildDot(index)),
)
```
- **UX Defect**:
  - The setup screens in `OnboardingScreen` and `SettingsScreen` explicitly indicate the user may select a PIN of 4 to 8 digits (`4-8 digits`).
  - When the user is confronted with `SelfLockGateScreen`, the UI renders **exactly 4 dots**.
  - A user who chose a 6-digit PIN has no visual indication that digits 5 and 6 can or should be entered (and as proven in Agent 03, the code auto-submits at 4 digits anyway).
  - This visual design violates Nielsen's Heuristics #1 (Visibility of system status) and #2 (Match between system and real world).

### 2.2 Misleading Security Claims in Onboarding Wizard (UIUX-02)
- **Severity**: MEDIUM
- **Confidence**: CONFIRMED
- **File**: `lib/ui/screens/onboarding_screen.dart`, Lines 470–474
```dart
const Text(
  '⚠️ IMPORTANT: There is no recovery backdoor by design. If you forget your Admin Password, uninstallation requires ADB or Safe Mode recovery.',
  style: TextStyle(fontSize: 13, color: Color(0xFFFCA5A5), height: 1.4),
)
```
- **UX Defect**:
  - The text informs the user that uninstallation "requires ADB or Safe Mode recovery".
  - This is factually inaccurate under Android's security model. An attacker or user can uninstall via launcher drag-and-drop or simply change system language to Spanish.
  - Making false security claims in the onboarding flow creates unwarranted trust and misleads users regarding the actual boundaries of Android Device Administrator.

### 2.3 Hardcoded Success Indicators in Setup Wizard (UIUX-03)
- **Severity**: LOW
- **Confidence**: CONFIRMED
- **File**: `lib/ui/screens/onboarding_screen.dart`, Lines 535–536
```dart
_buildSummaryRow('User PIN Configured', true),
_buildSummaryRow('Admin Password Configured', true),
```
- **UX Defect**:
  - In `_buildDoneStep()`, the summary rows for PIN and Admin Password pass static boolean literals (`true`).
  - Even if credential storage had failed on the native keystore during steps 6 or 7, the completion screen misleadingly reports a green checkmark with "Active".

### 2.4 Lack of Semantics on Numeric Keypad (WCAG / TalkBack) (UIUX-04)
- **Severity**: MEDIUM
- **Confidence**: CONFIRMED
- **File**: `lib/ui/screens/self_lock_gate_screen.dart`, Lines 194–218; `android/app/src/main/kotlin/com/lockkeeper/app/overlay/PinOverlayView.kt`, Lines 148–198
- **Accessibility Defect**:
  - In `SelfLockGateScreen.dart`, the keypad buttons are constructed using `InkWell` inside `Material(shape: CircleBorder())`. There are no `Semantics(button: true, label: ...)` annotations.
  - When Android TalkBack is enabled, the action button `✓` is read as "Tick" or unpronounceable symbol rather than "Submit PIN". The delete button `⌫` is read as a unicode symbol rather than "Delete digit".
  - In the native overlay `PinOverlayView.kt`, raw `Button` widgets are added to a `GridLayout` without `setContentDescription()`.

### 2.5 Degradation Banner Button Ambiguity (UIUX-05)
- **Severity**: LOW
- **Confidence**: CONFIRMED
- **File**: `lib/ui/screens/home_screen.dart`, Lines 183–195
```dart
TextButton(
  onPressed: () {
    Navigator.of(context).push(
      MaterialPageRoute(builder: (_) => const SettingsScreen()),
    ).then((_) => _loadData());
  },
  child: const Text('Fix', style: TextStyle(fontWeight: FontWeight.bold)),
)
```
- **UX Defect**:
  - The banner identifies multiple missing permissions (e.g., `Missing: Accessibility Service, Usage Access`).
  - Tapping "Fix" simply opens `SettingsScreen`. It does not jump to or highlight the specific missing permission.
  - The user must manually navigate the settings screen and deduce which item needs attention.

### 2.6 Touch Target Density in App Detail Cooldown Selector (UIUX-06)
- **Severity**: LOW
- **Confidence**: CONFIRMED
- **File**: `lib/ui/screens/app_detail_screen.dart`, Lines 199–238
- **UX Defect**:
  - Preset duration chips (`15m`, `1h`, `4h`, `24h`, `Custom...`) use compact choice chips wrapped in `Wrap(spacing: 10, runSpacing: 10)`.
  - On devices with compact display density or large font scaling (accessibility display zoom), the chips wrap onto 3 lines and touch targets approach sub-44dp boundaries, violating Material Accessibility Guidelines.

---

## 3. UI/UX & Usability Findings Table

| ID | Title | Severity | Confidence | Category |
|---|---|---|---|---|
| **UIUX-01** | Static 4-Dot Indicator for Dynamic 4–8 Digit PIN | HIGH | CONFIRMED | Visual / Functional Inconsistency |
| **UIUX-02** | Misleading Anti-Uninstall & Safe-Mode Claims | MEDIUM | CONFIRMED | Security Deception / User Trust |
| **UIUX-03** | Hardcoded Credential Status in Setup Summary | LOW | CONFIRMED | Misleading Status Feedback |
| **UIUX-04** | Missing Semantic Labels on Numeric Keypad Controls | MEDIUM | CONFIRMED | Accessibility (WCAG 2.2) |
| **UIUX-05** | Ambiguous Degradation Banner "Fix" Action | LOW | CONFIRMED | Navigation / User Guidance |
| **UIUX-06** | Compact Choice Chip Touch Targets in App Details | LOW | HIGH CONFIDENCE | Touch Target Sizing |
