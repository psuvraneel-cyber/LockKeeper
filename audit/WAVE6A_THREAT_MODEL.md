# Wave 6A — Threat Model & Data-Loss Security Boundaries

**Target Repository:** `C:\AppLocker`  
**Evaluation Scope:** Threat Models A through E (Accidental Clear, Malicious Clear, ADB Access, Reboot/Service Restart, Safe Mode)  
**Date:** 2026-09-18  
**Contributors:** Agent 4 (Security Architect), Agent 5 (Adversarial Reviewer)  

---

## 1. Threat Modeling Overview

LockKeeper is designed as an on-device application locking and privacy protection utility for consumer Android devices. Its threat model must define the boundaries between:
1. **User-Space Protections:** Actions an attacker can perform through standard Android UI and app interactions.
2. **Platform-Level Capabilities:** Actions mediated by the operating system kernel, Android framework daemons, and system settings.
3. **Elevated Attack Surfaces:** Actions requiring direct ADB debugging, physical recovery mode, or root execution.

---

## 2. Threat Model Analysis (Scenarios A through E)

### THREAT MODEL A: User Accidentally Clears LockKeeper Data
*Scenario: A device owner or child navigates into Settings and clears application storage for LockKeeper.*

1. **Can LockKeeper Detect the Condition?**
   - **Post-facto:** Upon next launch, LockKeeper detects that its Room database is empty and credentials are absent.
   - **Pre-facto:** No. Android does not notify apps before executing `clearApplicationUserData`.
2. **Can LockKeeper Prevent It?**
   - **YES, via User-Space Protection.**
   - In LockKeeper's operational design, once onboarding is completed, `shouldProtectSettings()` evaluates to `true`.
   - The moment any user opens Android Settings (`com.android.settings`), `LockKeeperAccessibilityService` detects the window state and triggers `OverlayManager.showAdminOverlay()`, covering Settings with a full-screen Admin Password challenge (`AdminOverlayView`).
   - The user **cannot reach** `Settings -> Apps -> LockKeeper -> Clear Storage` without entering the Admin Password.
3. **Can LockKeeper Recover?**
   - If the user somehow clears data (e.g. before settings protection was enabled or if settings was bypassed), the app launches into onboarding.
4. **Can LockKeeper Distinguish Fresh vs Previously Provisioned?**
   - **NO.** All private evidence is gone. Device Admin active alone is ambiguous.

---

### THREAT MODEL B: Malicious User Intentionally Clears LockKeeper Data (Device UI)
*Scenario: A borrower or malicious actor with temporary physical possession attempts to clear LockKeeper data from the device to access locked applications (e.g., Photos, WhatsApp).*

1. **Can LockKeeper Detect the Condition?**
   - LockKeeper continuously monitors foreground packages and window events via `LockKeeperAccessibilityService`.
2. **Can LockKeeper Prevent It?**
   - **YES.**
   - The attacker cannot access the locked target application (blocked by `PinOverlayView`).
   - The attacker cannot access Android Settings to clear storage (blocked by `AdminOverlayView`).
   - The attacker cannot access the Package Installer or uninstall LockKeeper (blocked by active Device Administrator and `AdminOverlayView`).
   - The attacker cannot access LockKeeper itself to alter settings (blocked by Self-Lock PIN gate).
3. **Can LockKeeper Recover?**
   - Not applicable; the attack is blocked at the UI boundary before data clearing can occur.
4. **Can LockKeeper Distinguish Fresh vs Previously Provisioned?**
   - The app remains in `SECURITY_PROVISIONED`.

---

### THREAT MODEL C: Attacker Has ADB Access (USB Debugging Enabled)
*Scenario: An attacker connects the phone to a PC via USB and runs `adb shell pm clear com.lockkeeper.app` or `adb uninstall com.lockkeeper.app`.*

1. **Can LockKeeper Detect the Condition?**
   - The app process is killed instantly by `system_server` when `pm clear` is executed.
   - On next launch, the app sees empty local storage.
2. **Can LockKeeper Prevent It?**
   - **NO.**
   - **Platform Law:** No consumer Android application can prevent an ADB shell process (UID 2000 / `android.permission.CLEAR_APP_USER_DATA`) from clearing its private sandbox.
   - Only a **Device Owner (MDM)** can set `DISALLOW_APPS_CONTROL` or `DISALLOW_DEBUGGING_FEATURES`. Standard consumer apps do not have this privilege.
3. **Can LockKeeper Recover?**
   - Upon relaunch, the app discovers a clean Room database and starts fresh setup.
4. **Can LockKeeper Distinguish Fresh vs Previously Provisioned?**
   - **NO.** Because ADB erased both the database and the Keystore keys, no local cryptographic evidence remains.

---

### THREAT MODEL D: Device is Rebooted / Accessibility Service Restarted
*Scenario: The user or an adversary powers off, restarts the phone, or toggles accessibility off/on in an attempt to bypass locks.*

1. **Can LockKeeper Detect the Condition?**
   - **YES.**
   - Broadcast receiver listens for `BOOT_COMPLETED`.
   - `LockKeeperForegroundService` starts automatically on boot or app launch.
   - `LockKeeperAccessibilityService.onServiceConnected()` notifies `ProtectionRepository`.
2. **Can LockKeeper Prevent It?**
   - **YES.**
   - Persistent Room database retains `securityProvisioned = true` and `onboardingComplete = true`.
   - Android Keystore credentials survive reboot intact.
   - On boot, all protected apps remain locked.
   - Transient unlock sessions are explicitly cleared on reboot (`decisionEngine.clearAllSessions()`).
   - Screen-off broadcast immediately clears active sessions.
3. **Can LockKeeper Recover?**
   - Seamlessly maintains protection.
4. **Can LockKeeper Distinguish Fresh vs Previously Provisioned?**
   - **YES, 100% RELIABLY.** Room SQLite and Keystore survive reboots and process death.

---

### THREAT MODEL E: Device is Booted into Safe Mode
*Scenario: An attacker boots the Android phone into Safe Mode (by holding Power + Volume Down).*

1. **Can LockKeeper Detect the Condition?**
   - In Android Safe Mode, the Android OS disables ALL third-party applications, including all Accessibility Services and all Device Administrators.
   - LockKeeper cannot execute any code while the device remains in Safe Mode.
2. **Can LockKeeper Prevent It?**
   - **NO.**
   - Safe Mode is an OS kernel/bootloader hardware diagnostic feature. No non-system app can prevent a user from entering Safe Mode or run during Safe Mode.
3. **Can LockKeeper Recover?**
   - When the device is rebooted normally out of Safe Mode, LockKeeper's services resume, the Room database and Keystore remain intact, and normal protection is re-established.
4. **Can LockKeeper Distinguish Fresh vs Previously Provisioned?**
   - **YES.** When normal mode resumes, all persistent state is intact.

---

## 3. Summary Comparison Table across Threat Models

| Threat Model | Attack Vector | Can App Detect? | Can App Prevent? | Survives Attack? | Historical State Distinguishable? |
|---|---|---|---|---|---|
| **A: Accidental Clear** | UI Settings navigation | Post-facto | **YES** (Admin overlay blocks Settings) | Blocked | Ambiguous if clear succeeds |
| **B: Malicious UI Clear** | Attacker touches screen | Continuous | **YES** (Admin overlay blocks Settings) | Blocked | Retains `SECURITY_PROVISIONED` |
| **C: ADB Clear** | `adb shell pm clear` | Post-facto | **NO** (Platform privilege boundary) | Data lost | **NO** (Ambiguous with Step 5) |
| **D: Reboot / Restart** | System reboot | Immediate | **YES** (Persistent storage intact) | **YES** | **YES** (`SECURITY_PROVISIONED`) |
| **E: Safe Mode** | Hardware boot key | Post-facto | **NO** (OS-level hardware feature) | **YES** (on normal boot) | **YES** (Retained on exit) |

---

## 4. Key Takeaways for Architecture Design

1. **The In-App / On-Device Perimeter is Solid:**
   LockKeeper's defense-in-depth (Self-Lock + AdminOverlay on Settings + DeviceAdmin prevent-uninstall) successfully blocks normal users and non-root attackers from ever clearing app data through the phone UI.
2. **The ADB / Hardware Boundary is Real:**
   An attacker with physical USB access and authorized ADB debugging can always issue `pm clear`. No consumer app can prevent this.
3. **The Core Dilemma:**
   The architecture must decide how to handle the ambiguous post-`pm clear` state without breaking the primary user experience or causing catastrophic lockouts for legitimate users.
