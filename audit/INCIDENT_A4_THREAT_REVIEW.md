# LockKeeper Critical Incident Response — Agent A4 Adversarial Security Review
**Incident ID:** INCIDENT-2026-09-18-LOCKOUT  
**Threat Vector:** Setup State Abuse, Authentication Bypass, and Fail-Closed Invariants  
**Investigator:** Agent A4 (Adversarial Security Reviewer)  
**Date:** 2026-09-18  

---

## 1. Adversarial Objective

The core objective of this adversarial audit is to answer the critical security question:
> *"Can introducing an explicit `SETUP_IN_PROGRESS` state accidentally allow a previously provisioned, protected installation to become unprotected, or allow an attacker to bypass authentication by masquerading as a fresh install?"*

We systematically evaluate 15 attack vectors and state tampering scenarios against the proposed security model.

---

## 2. Attack Vector Analysis Matrix

| # | Attack Vector / Scenario | Attacker Action | System Defense Requirement | Vulnerability Risk |
|---|---|---|---|---|
| **V-01** | **Fake Fresh Install** | Attacker wipes SharedPreferences while keeping DB and Keystore intact. | State is read from Room & Keystore. If Keystore has credentials or Room has locked apps, state is NOT setup. | **Mitigated** if dual-anchored. |
| **V-02** | **Onboarding Flag Deletion** | Attacker deletes `onboarding_complete` in SharedPreferences. | SharedPreferences is only a fast cache. Authoritative state resides in Room and Keystore. | **Mitigated**: native state wins. |
| **V-03** | **Room DB State Manipulation** | Attacker modifies `app_settings` to set `onboardingComplete = false`. | If Device Admin is active and credentials exist in Keystore, mismatch flags `RECOVERY_REQUIRED`, NOT `SETUP_IN_PROGRESS`. | **Mitigated**: state inconsistency triggers recovery. |
| **V-04** | **Credential Deletion (`pm clear`)** | Attacker wipes app private data while Device Admin remains active in OS. | If Device Admin is active, and app was previously provisioned (or has active admin with no setup session), must fail-closed into `RECOVERY_REQUIRED`. | **CRITICAL BOUNDARY**: must strictly differentiate fresh install from wiped install. |
| **V-05** | **Flutter State Manipulation** | Attacker sends manipulated platform channel call `setOnboardingComplete(false)`. | Native layer must reject demoting a provisioned system to setup without prior admin authentication. | **Mitigated**: irreversible transition. |
| **V-06** | **Process Kill / Restart during Setup** | Process killed after granting Device Admin but before PIN setup. | App relaunches into `SETUP_IN_PROGRESS`. User can reopen LockKeeper and resume onboarding. | **Desired behavior**. |
| **V-07** | **Reboot during Setup** | Phone reboots with Device Admin granted and onboarding incomplete. | Native layer boots into `SETUP_IN_PROGRESS`. No Home loop. Device remains usable. | **Desired behavior**. |
| **V-08** | **Device Admin Disable / Re-enable** | Attacker disables Device Admin and re-enables it. | Disabling admin requires admin authentication in Settings. If disabled, `onDisabled()` revokes sessions. Re-enabling refreshes authoritative state. | **Mitigated**. |
| **V-09** | **Accessibility Service Restart** | Attacker kills or toggles accessibility service in Settings. | Service reconnection fetches authoritative state. If provisioned, protection resumes. If setup, setup continues. | **Mitigated**. |
| **V-10** | **Protected App Access in Setup** | User/attacker tries to open a locked app while in `SETUP_IN_PROGRESS`. | In a fresh install, NO apps are locked (`locked_apps` table is empty). If `locked_apps` has locked entries, app CANNOT be in fresh setup! | **CRITICAL INVARIANT**: Any existing locked app proves prior provisioning! |
| **V-11** | **Admin Grace Window Abuse in Setup** | Attacker requests admin grace during setup. | Admin grace requires valid admin password verification, which is impossible before password is set. | **Mitigated**. |
| **V-12** | **Bypass Recovery via Setup Mode** | Previously provisioned device with lost creds tries to claim it is in setup. | A device that was previously provisioned has a persistent marker (e.g., Keystore provisioning marker or Room history). If lost creds, it MUST enter `RECOVERY_REQUIRED`. | **CRITICAL INVARIANT**: One-way state machine. |
| **V-13** | **Self-Lock Bypass during Setup** | Attacker tries to launch LockKeeper without PIN during setup. | Self-lock is explicitly disabled until onboarding is completed (`onboardingComplete == true`). | **Legitimate behavior**. |
| **V-14** | **Fail-Open on Native Failure** | SQLite corruption or unexpected exception during setup evaluation. | Must return `LockDecision.DenyUnknown(DENIED_NATIVE_FAILURE)` or fail closed for protected apps. | **Mitigated** by `try/catch` fail-closed pattern. |
| **V-15** | **Home Screen Loop Re-emergence** | Can the infinite `GLOBAL_ACTION_HOME` loop re-occur? | Exclude Home / Launcher packages from `GLOBAL_ACTION_HOME` calls. | **Eliminated**. |

---

## 3. Detailed Adversarial Analysis of Key Scenarios

### Scenario 1: The "Data Wipe" Attack vs Fresh Installation
**The Threat:** An adversary steals a locked device. Device Administrator prevents normal uninstallation. The adversary uses ADB (`adb shell pm clear com.lockkeeper.app`) or OEM recovery tools to wipe the app's internal sandbox. The adversary restarts LockKeeper hoping it enters `SETUP_IN_PROGRESS` and unlocks protected apps.

**Architectural Countermeasure:**
1. On an authentic clean installation:
   - Device Admin is **INACTIVE** when the app is installed.
   - Device Admin only becomes active when the user explicitly interacts with the setup wizard.
2. In a "Data Wipe" attack:
   - Device Admin is **ALREADY ACTIVE** at the moment the app process starts for the first time after the wipe!
   - Room DB has no records. Keystore has no keys.
   - BUT `DevicePolicyManager.isAdminActive(adminComponent)` is ALREADY `true` before any onboarding screen was ever presented to the user!
3. **The Invariant:**
   - If `isAdminActive == true` upon cold start, AND the app does NOT possess valid credentials, AND there was NO active setup progression in the current boot/session that requested admin:
   - It is NOT a fresh install. It is a **wiped/corrupted installation**!
   - Therefore, it MUST enter **`RECOVERY_REQUIRED`**!

### Scenario 2: Can a Provisioned Device Revert to `SETUP_IN_PROGRESS`?
**The Threat:** A user has configured LockKeeper with 5 locked apps and a PIN. The user wants to bypass the lock without knowing the PIN, so they attempt to invoke `PlatformBridge.setOnboardingComplete(false)` or manipulate SharedPreferences.

**Architectural Countermeasure:**
1. In Room `app_settings`, once `securityProvisioned = true` (or `onboardingComplete = true`), this flag is a **one-way ratchet**.
2. There is no API exposed via PlatformChannel that permits setting `securityProvisioned = false` or `onboardingComplete = false`.
3. In `ProtectionRepository.setLockedApp()`: if any locked apps exist in the database, the installation is unequivocally recognized as provisioned.
4. If `onboardingComplete` in SharedPreferences is manipulated, `ProtectionRepository.getSettings()` queries Room SQLite, which is authoritative and overwrites SharedPreferences cache immediately.

### Scenario 3: Protected Apps During `SETUP_IN_PROGRESS`
**The Threat:** Can an attacker exploit `SETUP_IN_PROGRESS` to access apps that were supposedly locked?

**Architectural Countermeasure:**
1. During `SETUP_IN_PROGRESS`, the `locked_apps` table in Room is empty. There are NO locked apps.
2. If `locked_apps` is NOT empty (meaning apps were previously marked as locked), the state CANNOT be `SETUP_IN_PROGRESS`! Any non-empty locked app list with missing credentials automatically trips `RECOVERY_REQUIRED`.
3. Therefore, no locked app can ever be accessed under the umbrella of `SETUP_IN_PROGRESS`.

---

## 4. Required Security Invariants for Stage C/D

1. **INV-SETUP-1 (One-Way Ratchet):**  
   Transition from `SETUP_IN_PROGRESS` to `SECURITY_PROVISIONED` is strictly monotonic. Once provisioned, an installation can NEVER return to `SETUP_IN_PROGRESS` without a full application uninstallation (which requires Device Admin deactivation guarded by the Admin Password).

2. **INV-SETUP-2 (Fail-Closed Recovery):**  
   `RECOVERY_REQUIRED` must remain fail-closed. If an installation was previously provisioned and loses its credentials, or if Device Admin is found active on cold start with no credentials, all protected applications remain blocked.

3. **INV-SETUP-3 (Launcher Immunity):**  
   Neither `SETUP_IN_PROGRESS` nor `RECOVERY_REQUIRED` may trigger a `GLOBAL_ACTION_HOME` loop when the current package is the home launcher.

4. **INV-SETUP-4 (Native Authority):**  
   The decision between `SETUP_IN_PROGRESS`, `SECURITY_PROVISIONED`, and `RECOVERY_REQUIRED` must be computed exclusively in native code (`ProtectionRepository` and Room/Keystore), completely independent of Flutter UI state.

---

## 5. Adversarial Audit Verdict

The proposed architectural state separation (`SETUP_IN_PROGRESS` vs `RECOVERY_REQUIRED`) does **NOT** weaken LockKeeper's fail-closed security guarantees, provided the one-way transition ratchet and the cold-start pre-existing admin check are strictly enforced.
