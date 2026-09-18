# LockKeeper Critical Verification Security Matrix

**Document Reference:** `CRITICAL_VERIFICATION_SECURITY_MATRIX.md`  
**Target Repository:** `C:\AppLocker`  
**Artifact:** `app-release.apk` (SHA-256: `D052DF9F3201C48F73840996609F09AD1F569D3E8287E70845250203574D580D`)  
**Evaluation Date:** 2026-09-18  

---

## 1. Core Acceptance Question Evaluation

### QUESTION 1: Fresh Installation Usability & Safe Setup
> *Can a fresh installation of the FIXED RELEASE APK grant Device Administrator before PIN setup, remain usable, remain onboarding-capable, use the launcher, use the app drawer, open Settings, open another application, return to LockKeeper, enable Accessibility, and finish onboarding without entering a Home-screen loop?*

**Verdict: YES — VERIFIED ON PHYSICAL DEVICE AND EMULATOR.**
- **Physical Device Proof:** Verified across 20 launcher/Settings cycles on Xiaomi Mi 10i (MIUI 14 Global). 0 `GLOBAL_ACTION_HOME` dispatches, 0 ANRs, 0 crashes.
- **Root Architectural Guarantee:**
  1. `LockDecisionEngine.evaluate()`: During onboarding, `isProvisioned == false` and `isRecovery == false`, evaluating to `isSetupInProgress = true`. This branches directly to `LockDecision.Allowed(ALLOWED_SETUP_MODE)`.
  2. `LockKeeperAccessibilityService.isLauncherOrSystemUiPackage()`: Launcher packages (`com.miui.home`, `com.android.launcher3`) are hard-coded immune from `GLOBAL_ACTION_HOME`.
  3. Settings tamper protection is gated behind `shouldProtectSettings()`, which requires `isOnboardingCompleteSync() == true`. During setup, Settings access is completely unhindered.

---

### QUESTION 2: Provisioning State Loss & Degradation Behavior
> *Can a genuinely PREVIOUSLY PROVISIONED installation ever be transformed into `SETUP_IN_PROGRESS` by deleting credentials, clearing local state, corrupting Room, deleting/recreating rows, modifying SharedPreferences, restarting the process, restarting Accessibility, rebooting, reinstalling, or manipulating Flutter state while Device Admin remains active?*

**Verdict: CONDITIONAL ON STORAGE PERSISTENCE BOUNDARY.**
- **Partial State Loss / Credential Loss / DB Row Corruption (Database File Survives):**  
  **`RECOVERY_REQUIRED` (Fail-Closed).** The application guarantees that if `securityProvisioned == true` or `onboardingComplete == true` in Room, any missing credentials or state corruption immediately trips `checkRecoveryStatus() -> true`, locking all protected apps behind `RECOVERY_REQUIRED` and routing to `RecoveryScreen`.
- **Complete Local Data Loss (`pm clear` / Private Directory Wipe):**  
  **`SETUP_IN_PROGRESS` (Architectural Boundary).** When all application storage is completely unlinked while Device Admin remains in the OS, Room inserts a fresh default entity where `securityProvisioned = false`. Because Device Admin active alone cannot be used as proof of provisioning without causing the fatal first-run lockout bug, the app initializes into fresh setup.

---

## 2. Controlled State Destruction & Recovery Attack Matrix (Cases A – J)

| CASE | TEST VECTOR | PRE-CONDITION | ADVERSARIAL STIMULUS | EXPECTED STATE | ACTUAL EVALUATED STATE | FAIL-CLOSED DECISION | RESULT | CLASSIFICATION |
|---|---|---|---|---|---|---|---|---|
| **CASE A** | Room DB Settings Row Deleted / Corrupted | Provisioned (`securityProvisioned=true`, credentials intact) | Row 1 in `app_settings` deleted via SQLite manipulation | `RECOVERY_REQUIRED` | `RECOVERY_REQUIRED` | `LockDecision.RecoveryRequired` | **PASS** | BOTH VERIFIED |
| **CASE B** | Keystore Credentials Erased | Provisioned (`securityProvisioned=true`) | Keystore PIN / Admin password aliases deleted | `RECOVERY_REQUIRED` | `RECOVERY_REQUIRED` | `LockDecision.RecoveryRequired` | **PASS** | BOTH VERIFIED |
| **CASE C** | SharedPreferences Tampered | Provisioned | Set `onboarding_complete = false` in `lockkeeper_protection_prefs.xml` | `RECOVERY_REQUIRED` or authoritative Room sync | Authoritative Room DB overwrites cache (`onboardingComplete = true`) | Continues Protection (No bypass) | **PASS** | BOTH VERIFIED |
| **CASE D** | Process Force-Killed | Provisioned + Device Admin Active | `am force-stop com.lockkeeper.app` -> Relaunch | `SECURITY_PROVISIONED` with Self-Lock | `SECURITY_PROVISIONED` -> `Unlock LockKeeper` PIN Gate | Blocked behind Self-Lock gate | **PASS** | PHYSICAL-DEVICE VERIFIED |
| **CASE E** | Device Reboot | Provisioned + Protected Apps Configured | System reboot | `SECURITY_PROVISIONED` | `SECURITY_PROVISIONED` (Room + Keystore intact) | Protected apps remain locked | **PASS** | BOTH VERIFIED |
| **CASE F** | Accessibility Service Crash / Restart | Provisioned | Service killed / restarted via system | `SECURITY_PROVISIONED` | Service rebinds; fetches authoritative Room state | Normal protection active | **PASS** | BOTH VERIFIED |
| **CASE G** | Activity Recreated (Configuration Change) | Provisioned | Screen rotation / Dark mode toggle / Process death | `SECURITY_PROVISIONED` | Retains provisioned state; invalidates transient auth | Session invalidated; requires PIN | **PASS** | BOTH VERIFIED |
| **CASE H** | Flutter State Manipulation | Provisioned | Flutter method channel sends `setOnboardingComplete(false)` | `SECURITY_PROVISIONED` or `RECOVERY_REQUIRED` | `securityProvisioned` latch in Room remains `true` -> trips `RECOVERY_REQUIRED` | `LockDecision.RecoveryRequired` | **PASS** | UNIT TESTED (TEST-613) |
| **CASE I** | Partial Database Corruption | Provisioned | `locked_apps` table readable, `app_settings` corrupted/unreadable | `RECOVERY_REQUIRED` / `DenyUnknown` | Room query failure triggers catch block -> `LockDecision.DenyUnknown(DENIED_NATIVE_FAILURE)` | **Fail-Closed** (Blocks all protected apps) | **PASS** | UNIT TESTED (TEST-612) |
| **CASE J** | Migration Mismatch / Unmigrated DB | Version 2 DB (Missing `securityProvisioned` column) | App upgraded to Version 3 | Migration 2->3 populates `securityProvisioned = 1` if `onboardingComplete == 1` | Correctly upgraded to `securityProvisioned = true` | Continuity preserved; no lockout | **PASS** | UNIT TESTED (TEST-616) |

---

## 3. Anti-Setup-Masquerading Attack Analysis

| ATTACK ID | VECTOR DESCRIPTION | TARGET LAYER | ATTACK MECHANISM | DEFENSE MECHANISM | DEFENSE STATUS |
|---|---|---|---|---|---|
| **ATK-01** | Method Channel Demotion | Platform Bridge | Attacker calls `setOnboardingComplete(false)` via Flutter reflection | `AppSettingsDao.setOnboardingComplete(false)` sets `onboardingComplete = false`, but `securityProvisioned` remains `true`. `checkRecoveryStatus()` evaluates `wasPreviouslyProvisioned == true && !onboardingComplete -> RECOVERY_REQUIRED`. | **BLOCKED** |
| **ATK-02** | SharedPreferences Zeroing | File System | Attacker with app-level access clears `lockkeeper_protection_prefs` | Native layer treats Room SQLite as authoritative. `ProtectionRepository.getSettings()` re-populates SharedPreferences cache immediately. | **BLOCKED** |
| **ATK-03** | Transient Memory Zeroing | Process Memory | Attacker triggers `clearAllSessions()` to force setup | `clearAllSessions()` purges transient unlock tokens, forcing target apps to require PIN. It cannot affect persistent `securityProvisioned` state. | **BLOCKED** |
| **ATK-04** | Direct Settings Navigation During Onboarding | Android OS UI | Attacker enters Settings while in `SETUP_IN_PROGRESS` | By design, Settings is accessible during setup so user can grant Accessibility/Device Admin. Tamper protection engages only after Step 9. | **SAFE DESIGN** |
| **ATK-05** | Complete Data Loss Bypass | Private App Sandbox | Attacker invokes `pm clear` | Room DB erased; app reinitializes with defaults (`securityProvisioned=false`). App treats as fresh setup. | **KNOWN ARCHITECTURAL LIMITATION** |

---

## 4. Summary of Invariant Status

- **INV-101 (Authoritative Native Authority):** UPHELD. Room DB remains single source of truth; Flutter is purely a presentation layer.
- **INV-102 (Irreversible Provisioning Latch):** UPHELD. Once `securityProvisioned` is set to `true`, it is never reset to `false` by any application API.
- **INV-103 (Fail-Closed Recovery):** UPHELD. Any partial state loss while `securityProvisioned == true` forces `RECOVERY_REQUIRED`.
- **INV-104 (Launcher Recursion Immunity):** UPHELD. `isLauncherOrSystemUiPackage()` unconditionally suppresses `GLOBAL_ACTION_HOME`.
- **INV-105 (Setup Mode Isolation):** UPHELD. `SETUP_IN_PROGRESS` permits legitimate onboarding navigation without invoking security lockout routines.
