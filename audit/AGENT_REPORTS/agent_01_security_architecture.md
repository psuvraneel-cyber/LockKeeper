# Agent 01 — Security Architecture Audit Report

**Auditor Persona**: Senior Security Architect & Mobile Threat Modeler  
**Target Repository**: LockKeeper (`C:\AppLocker`)  
**Audit Date**: September 17, 2026  
**Scope**: Full End-to-End Security Architecture, Trust Boundaries, Authentication & Credential Storage, Inter-Process Boundaries, Anti-Tamper Mechanisms, and Threat Modeling.

---

## 1. Security Architecture Overview

LockKeeper is designed as a personal friction and focus barrier on Android that restricts access to selected target applications via PIN/cooldown enforcement, and attempts to protect itself from deactivation or uninstallation through a combination of Android Device Administrator and AccessibilityService inspection.

### Key Architectural Layers:
1. **Presentation / UI Layer (Flutter / Dart)**:
   - `OnboardingScreen`: Collects initial permission grants, sets User PIN and Admin Password.
   - `HomeScreen`: Displays list of launchable apps, toggles locking, and displays cooldown timers.
   - `AppDetailScreen`: Configures per-app lock parameters (cooldown minutes, strict mode).
   - `SettingsScreen`: Manages PIN/Admin password changes, self-lock toggle, and timeout.
   - `SelfLockGateScreen`: Renders in-app PIN keypad to gate LockKeeper's own UI.
2. **Platform Bridge Layer (Flutter MethodChannel / EventChannel)**:
   - `PlatformBridge.dart` <-> `PlatformChannelHandler.kt`: Asynchronous IPC bridge transferring credential verification, permission checks, app lists, and database mutations between Dart and Kotlin.
3. **Android Native Domain & Enforcement Engine**:
   - `ProtectionRepository`: Singleton coordinator managing database operations, credential store, decision engine, and self-lock session state.
   - `LockDecisionEngine`: In-memory evaluator determining if an app access is `Allowed`, `RequirePin`, `StrictCooldown`, or `PinLockout`.
   - `SelfLockSessionManager`: In-memory state tracking active authenticated sessions and background timeout for LockKeeper's UI.
   - `KeystoreCredentialStore`: Encrypted storage backed by `AndroidKeyStore` (AES-256-GCM) and PBKDF2WithHmacSHA256 hashing.
4. **Android Native Services & Windows (Interception & Protection)**:
   - `LockKeeperAccessibilityService`: System accessibility service listening for `TYPE_WINDOW_STATE_CHANGED` events to trigger overlays on locked apps and intercept sensitive screens in `com.android.settings`.
   - `LockKeeperForegroundService`: Ongoing foreground service maintaining sticky process status and executing a 400ms polling fallback via `UsageStatsManager` when accessibility is inactive.
   - `OverlayManager`: WindowManager coordinator creating `TYPE_APPLICATION_OVERLAY` windows for `PinOverlayView`, `AdminOverlayView`, and `CooldownOverlayView`.
   - `LockKeeperDeviceAdminReceiver`: Registered Device Administrator attempting to deter uninstallation via `onDisableRequested()`.

---

## 2. Trust Boundaries & Security Assumptions

### Boundary 1: User vs Physical Device (Attacker Profile)
- **Assumption**: The user or an unauthorized person with physical access to an unlocked phone wants to bypass LockKeeper to use a locked app, or to disable/uninstall LockKeeper to bypass friction.
- **Architectural Reality**: LockKeeper operates as an unprivileged, non-system, non-Device Owner application. It relies entirely on Android APIs intended for assistive technologies (`AccessibilityService`) and UI overlays (`SYSTEM_ALERT_WINDOW`). It possesses no kernel, SELinux, or Device Owner enforcement capabilities.

### Boundary 2: Flutter Engine <-> Android Native Platform Channel
- **Assumption**: Flutter UI reflects the true state of the native security layer.
- **Architectural Reality**: MethodChannel calls are asynchronous and independent. State transitions in Flutter (e.g., finishing onboarding) do not automatically reconfigure root widget parameters (`LockKeeperApp`), creating state desynchronization.

### Boundary 3: Native Process Memory vs Persistent Storage
- **Assumption**: Authenticated sessions and admin grace windows are transient and secure.
- **Architectural Reality**: Session keys and admin grace timestamps are held in static memory (`ConcurrentHashMap` in `LockDecisionEngine`, volatile fields in `SelfLockSessionManager`). Process termination clears these sessions; however, while the process is alive, active app sessions have no TTL.

---

## 3. Threat Model

| Threat ID | Threat Actor | Precondition | Attack Path | Component Affected | Impact | Mitigation Status |
|---|---|---|---|---|---|---|
| **TM-01** | Physical Device Possessor | Phone unlocked | Drag LockKeeper icon from Launcher to "Uninstall" or tap "App Info" -> "Uninstall" | Launcher / PackageInstaller | Complete bypass and uninstallation | **FAILED**: Accessibility service only monitors `com.android.settings`. Launcher and PackageInstaller are unmonitored. |
| **TM-02** | Physical Device Possessor | Phone unlocked | Change system display language to non-English, then open Settings -> Apps -> LockKeeper -> Force Stop / Uninstall | `LockKeeperAccessibilityService.kt` | Anti-tamper completely defeated | **FAILED**: Hardcoded English string matching fails in foreign locales. |
| **TM-03** | Physical Device Possessor | Phone unlocked, SelfLock enabled | Open Settings in LockKeeper -> Change PIN or Disable Self-Lock, brute-force PIN / Admin Password | `PlatformChannelHandler.kt`, `SettingsScreen.dart` | Unauthorized credential change or protection disabling | **FAILED**: Neither `verifyPin` nor `verifyAdminPassword` enforces rate limiting or lockout. |
| **TM-04** | Physical Device Possessor | Phone unlocked | Open LockKeeper after completing onboarding on first launch | `main.dart`, `LockKeeperApp` | Unrestricted access to LockKeeper dashboard and settings | **FAILED**: `widget.isOnboardingComplete` remains `false` in widget tree; self-lock gate never attached. |
| **TM-05** | Malware / Screen Recorder | `SYSTEM_ALERT_WINDOW` or MediaProjection granted | Capture screen while user enters PIN or Admin Password on overlay | `OverlayManager.kt`, `AdminOverlayView.kt`, `PinOverlayView.kt` | Credential leakage | **FAILED**: `FLAG_SECURE` is not set on `WindowManager.LayoutParams` for overlay windows. |
| **TM-06** | Legitimate User | User configured 5–8 digit PIN | Attempt to unlock LockKeeper via `SelfLockGateScreen` | `SelfLockGateScreen.dart` | Permanent user lockout from app UI | **FAILED**: Gate hardcoded to auto-submit at 4 digits. |
| **TM-07** | Physical Device Possessor | Accessibility service killed or disabled | Open Android Settings -> Apps -> LockKeeper -> Force Stop / Clear Storage | `LockKeeperForegroundService.kt` | Unrestricted deactivation | **FAILED**: UsageStats fallback explicitly ignores `com.android.settings`. |
| **TM-08** | Physical Device Possessor | Phone rebooted | Rapidly open target app immediately after boot before services bind | `BootReceiver.kt`, Android bootloader | Target app accessed without lock | **FAILED**: Coroutine in `BootReceiver` runs without `goAsync()`; accessibility binding is delayed. |

---

## 4. Deep-Dive Architectural Findings

### ARCH-01: Device Administrator Architectural Fallacy (Device Admin vs Device Owner)
- **Severity**: CRITICAL
- **Confidence**: CONFIRMED
- **File**: `android/app/src/main/kotlin/com/lockkeeper/app/receiver/LockKeeperDeviceAdminReceiver.kt`, Lines 13–16
- **Analysis**:
  LockKeeper's onboarding and documentation inform the user that Device Administrator prevents uninstallation:
  > *"Activates Device Administrator to block impulsive uninstallation. Any uninstall attempt requires deactivation via Settings, which is guarded by your Admin Password."*
  
  In Android's security architecture:
  1. Standard Device Administrator (`android.app.admin.DeviceAdminReceiver`) provides **no mechanism** to prevent deactivation.
  2. `onDisableRequested()` merely returns a `CharSequence` displayed in a confirmation dialog. Tapping "Deactivate" immediately disables the admin.
  3. Blocking deactivation or uninstallation at the OS level is only possible if the application is provisioned as **Device Owner** (via NFC provisioning or ADB command `dpm set-device-owner`), utilizing policy `DISALLOW_UNINSTALL_APPS` or `setUninstallBlocked()`.
  4. LockKeeper attempts to compensate for this fundamental OS limitation by having `LockKeeperAccessibilityService` monitor `com.android.settings` and pop an overlay (`AdminOverlayView`).
  5. If `LockKeeperAccessibilityService` is disconnected, stopped, crashed, or bypassed, Device Administrator provides **zero security enforcement**.

### ARCH-02: Settings Tamper Protection Scope Failure
- **Severity**: CRITICAL
- **Confidence**: CONFIRMED
- **File**: `android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperAccessibilityService.kt`, Lines 88–94
- **Analysis**:
  `LockKeeperAccessibilityService.kt` restricts its anti-tamper logic strictly to:
  ```kotlin
  if (packageName == "com.android.settings") {
      handleSettingsEvent(event, className)
      return
  }
  ```
  On Android, package uninstallation and application management are handled by diverse components:
  1. **Package Installers**: `com.google.android.packageinstaller`, `com.android.packageinstaller`. When an app is uninstalled via third-party launcher or intent, the package installer activity is top-level.
  2. **OEM Management Centers**: On Xiaomi/MIUI, `com.miui.securitycenter` manages app permissions, force stop, and app data clearing. On Samsung, `com.samsung.android.lool` (Device Care). On Huawei, `com.huawei.systemmanager`.
  3. None of these packages equal `"com.android.settings"`. Any uninstall or clear-data action executed through these entry points completely circumvents `handleSettingsEvent()`.

### ARCH-03: Asynchronous Root Lifecycle Desynchronization
- **Severity**: HIGH
- **Confidence**: CONFIRMED
- **File**: `lib/main.dart`, Lines 29, 89, 118–147; `lib/ui/screens/onboarding_screen.dart`, Lines 130–138
- **Analysis**:
  When LockKeeper launches for the first time, `isOnboardingComplete` is fetched in `main()`:
  ```dart
  runApp(LockKeeperApp(isOnboardingComplete: onboardingComplete));
  ```
  `LockKeeperApp` stores this in `widget.isOnboardingComplete` (immutable).
  Inside `_LockKeeperAppState`:
  - `didChangeAppLifecycleState`: `if (!widget.isOnboardingComplete) return;`
  - `builder`: `if (!widget.isOnboardingComplete) return child!;`
  
  When the user finishes onboarding in `OnboardingScreen`, it performs:
  ```dart
  await PlatformBridge.setOnboardingComplete(true);
  await PlatformBridge.startProtectionService();
  Navigator.of(context).pushReplacement(MaterialPageRoute(builder: (_) => const HomeScreen()));
  ```
  `LockKeeperApp` is never notified or rebuilt with `isOnboardingComplete = true`.
  Consequently, `widget.isOnboardingComplete` remains `false`.
  - Self-lock lifecycle listening is dead.
  - The `Stack` builder never inserts `SelfLockGateScreen`.
  - The user can background the app, reopen it, navigate all settings, without ever encountering the Self-Lock PIN screen until the app process is terminated and restarted.

---

## 5. Security Architecture Conclusions

LockKeeper simulates security enforcement through reactive user-interface interception rather than operating within an authenticated OS privilege boundary. The architecture possesses severe single points of failure:
1. Complete reliance on a single accessibility service package check (`com.android.settings`).
2. Unprotected native overlay windows lacking `FLAG_SECURE`.
3. In-memory session lifetimes lacking expiration.
4. Desynchronized Flutter root state disabling self-lock on initial setup.

Remediation requires structural redesign of tamper detection, enforcing device-owner provisioning where feasible, hardening overlay window security, and rectifying state propagation between the Flutter root and native runtime.
