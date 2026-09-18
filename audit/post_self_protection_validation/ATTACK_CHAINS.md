# Multi-Stage Attack Chain Analysis

**Project:** LockKeeper (C:\AppLocker)  
**Phase:** Post-Self-Protection Adversarial Validation  
**Date:** 2026-09-17  
**Validator:** Agent 15 (Lead Security Validation Engineer)

---

## 1. Executive Summary

Adversarial security validation requires analyzing how individually isolated minor flaws combine across architectural boundaries into fatal, multi-stage exploit chains.

This document formalizes 5 verified attack chains that completely defeat LockKeeper's self-protection mechanisms.

---

## 2. Attack Chain Catalog

---

### ATTACK CHAIN 01: The Clear-Data State Collapse Cascade
- **Classification:** **CONFIRMED CHAIN**
- **Prerequisites:** Physical or interactive device access.
- **Components Exploited:**
  - Route 13 / ADB / Third-Party Cleaner / Settings Storage
  - `ProtectionRepository.shouldProtectSettings()`
  - `LockKeeperDeviceAdminReceiver`
  - Missing `RECOVERY_REQUIRED` state
- **Execution Path:**
  ```
  Step 1: Attacker clears LockKeeper storage (via Settings -> Apps -> Storage -> Clear Data,
          or ADB, or via a third-party cleaner app).
  
  Step 2: SQLite database (lockkeeper_database) and SharedPreferences are wiped.
          The documented RECOVERY_REQUIRED state does not exist.
  
  Step 3: LockKeeper launches in an UNCONFIGURED state:
          - isOnboardingCompleteSync() -> false
          - credentialStore.hasAdminPassword() -> false
          - shouldProtectSettings() -> false
  
  Step 4: LockKeeperAccessibilityService completely disables anti-tamper logic because
          shouldProtectSettings() evaluates to false.
  
  Step 5: Attacker opens Settings -> Security -> Device Admin Apps -> LockKeeper -> Deactivate.
          Because shouldProtectSettings() is false, zero overlay is shown.
  
  Step 6: LockKeeperDeviceAdminReceiver.onDisabled() runs. It is an empty stub and performs
          no action. Device Admin is revoked.
  
  Step 7: Attacker uninstalls LockKeeper normally.
  ```
- **Result:** **Total Permanent Defeat of LockKeeper.** No Admin Password was ever required.

---

### ATTACK CHAIN 02: Soft-Keyboard Overlay Disruption & Force-Stop Kill
- **Classification:** **CONFIRMED CHAIN**
- **Prerequisites:** Device running LockKeeper with anti-tamper active.
- **Components Exploited:**
  - `LockKeeperAccessibilityService.kt:111` (Premature overlay dismissal)
  - `AdminOverlayView.kt:81` (Soft keyboard auto-show)
  - Settings App Info Force Stop button
- **Execution Path:**
  ```
  Step 1: Attacker opens Settings -> Apps -> LockKeeper.
  
  Step 2: TamperDetectionEngine detects InstalledAppDetails and shows AdminOverlayView.
  
  Step 3: AdminOverlayView sets SOFT_INPUT_STATE_ALWAYS_VISIBLE, launching the soft keyboard.
  
  Step 4: The IME window launch generates a TYPE_WINDOW_STATE_CHANGED event for com.android.settings.
  
  Step 5: In LockKeeperAccessibilityService.onAccessibilityEvent, tamperEngine.evaluate() runs
          on the intermediate window state where App Info controls are obscured, returning null.
  
  Step 6: Line 110-112 executes:
          else if (isSystemManagementPackage(packageName)) {
              overlayManager.dismissAdminOverlay()
          }
  
  Step 7: The Admin Overlay is torn down and destroyed.
  
  Step 8: Attacker is left on the live Settings App Info screen with no overlay.
  
  Step 9: Attacker taps "Force stop". LockKeeper's accessibility service process is killed.
  
  Step 10: With the service dead, attacker deactivates Device Admin and uninstalls.
  ```
- **Result:** **Total Bypass of Admin Password via Standard UI Interaction.**

---

### ATTACK CHAIN 03: Third-Party Launcher Direct Uninstallation
- **Classification:** **CONFIRMED CHAIN**
- **Prerequisites:** User installs any custom launcher from Play Store (Nova, Niagara, Smart Launcher).
- **Components Exploited:**
  - `TamperDetectionEngine.evaluate` package whitelist (`com.android.settings`, `packageinstaller`, 3 OEM centers)
  - Launcher internal app management UI
- **Execution Path:**
  ```
  Step 1: Attacker sets Nova Launcher or Niagara Launcher as default home.
  
  Step 2: Attacker long-presses the LockKeeper icon on the home screen.
  
  Step 3: Attacker selects "Uninstall" within the launcher's popup menu.
  
  Step 4: The launcher renders its own confirmation dialog (packageName: com.teslacoilsw.launcher).
  
  Step 5: LockKeeperAccessibilityService receives TYPE_WINDOW_STATE_CHANGED for com.teslacoilsw.launcher.
  
  Step 6: TamperDetectionEngine.evaluate() checks:
          - packageName == "com.android.settings"? -> false
          - isPackageInstaller(packageName)? -> false
          - isOemSecurityCenter(packageName)? -> false
          - returns null immediately!
  
  Step 7: The launcher fires the uninstallation intent directly.
  
  Step 8: If Device Admin is active, Android displays the standard deactivation prompt;
          otherwise, LockKeeper is uninstalled immediately.
  ```
- **Result:** **Zero Interception by LockKeeper.**

---

### ATTACK CHAIN 04: Clock-Warp Lockout Destruction & Online Brute Force
- **Classification:** **CONFIRMED CHAIN**
- **Prerequisites:** Device clock can be adjusted via Settings or Notification shade.
- **Components Exploited:**
  - `TamperAuthorizationController.kt:96` (`adminLockoutUntil > now` wall-clock comparison)
  - `System.currentTimeMillis()` dependency
  - SQLite zero-row update bug
- **Execution Path:**
  ```
  Step 1: Attacker attempts to guess Admin Password on AdminOverlayView.
  
  Step 2: If database row 1 is missing, rate limiting never engages (infinite attempts).
  
  Step 3: Even if row 1 exists and 5 attempts are recorded, a 300-second lockout is engaged:
          adminLockoutUntil = System.currentTimeMillis() + 300_000L.
  
  Step 4: Attacker pulls down the notification shade, taps the clock/settings widget,
          and advances the date/time by 5 minutes.
  
  Step 5: Attacker returns to AdminOverlayView and enters another password.
  
  Step 6: verifyAdminPassword checks:
          if (lockoutUntil != null && lockoutUntil > now)
          Because now is +5 minutes, lockoutUntil > now is FALSE.
  
  Step 7: Lockout is immediately erased; attacker resumes guessing.
  ```
- **Result:** **Complete Annihilation of Lockout Defense.**

---

### ATTACK CHAIN 05: Service Death Zombie State & Silent Deactivation
- **Classification:** **CONFIRMED CHAIN**
- **Prerequisites:** Android OS low-memory or battery optimization killing background service.
- **Components Exploited:**
  - `PlatformChannelHandler.kt:250` (`isServiceConnected || enabledServices.contains(...)`)
  - Flutter Home Dashboard (`isFullyProtected`)
  - Lack of background watchdog service
- **Execution Path:**
  ```
  Step 1: Android OS terminates LockKeeperAccessibilityService due to memory pressure or battery saving.
  
  Step 2: User opens LockKeeper Flutter app.
  
  Step 3: Flutter queries PlatformBridge.getProtectionStatus().
  
  Step 4: PlatformChannelHandler checks checkPermission("accessibility"):
          Because the switch in Android Settings is technically ON, enabledServices contains
          com.lockkeeper.app, returning true!
  
  Step 5: Flutter dashboard displays green status: "Protection Operational: All 5 Protections Active".
  
  Step 6: The user assumes the device is secure and leaves the phone unattended.
  
  Step 7: A malicious actor picks up the phone. All protected apps (WhatsApp, Photos, Banking)
          can be opened with zero PIN overlay because the accessibility service is not running.
  
  Step 8: The actor navigates to Settings -> Device Admin Apps -> LockKeeper -> Deactivate.
          No overlay appears because the service is dead.
  
  Step 9: LockKeeper is uninstalled.
  ```
- **Result:** **Deceptive Security Posture Enabling Unauthorized Access and Removal.**
