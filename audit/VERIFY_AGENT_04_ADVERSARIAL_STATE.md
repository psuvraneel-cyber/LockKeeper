# Verification Agent 04: Adversarial State Bypass Review

**Date:** 2026-09-18T03:27:56+05:30  
**Phase:** STAGE 1 — READ-ONLY FORENSICS  
**Subject:** Adversarial Penetration Analysis of Setup vs. Recovery State Machine  

---

## 1. Threat Model & Adversary Goal

**Adversary Goal:** Force a genuinely provisioned LockKeeper installation to transition into `SETUP_IN_PROGRESS`, thereby achieving `LockDecision.Allowed(ALLOWED_SETUP_MODE)` on protected applications without supplying the user PIN or Admin Password.

**Security Invariant Evaluated:**
> *An installation that has completed security provisioning must never permit unprotected execution of protected targets via state confusion or partial state loss.*

---

## 2. Attack Vector Analysis

### Attack 1: Deletion of `onboardingComplete` Flag
- **Vector:** Adversary or corrupting event sets `onboardingComplete = 0` in Room DB.
- **Trace:**
  - `AppSettingsDao` returns `securityProvisioned = 1`, `onboardingComplete = 0`.
  - In `ProtectionRepository.checkRecoveryStatus()`:
    `wasPreviouslyProvisioned = settings.securityProvisioned || settings.onboardingComplete` -> `1 || 0 = true`.
  - `isRecovery = !hasCreds || !settings.onboardingComplete || (isAdminActive && !hasCreds)` -> `!0 = true`.
  - Result: **BLOCKED.** State transitions to `RECOVERY_REQUIRED`. `evaluatePackage()` issues `LockDecision.RecoveryRequired`. Setup mode cannot be reached.

### Attack 2: Credential KeyStore Excision
- **Vector:** Android KeyStore entries for PIN and Admin Password are lost or corrupted.
- **Trace:**
  - `hasCreds = false`.
  - `wasPreviouslyProvisioned = true` (via `securityProvisioned = 1`).
  - `isRecovery = true`.
  - Result: **BLOCKED.** System enters `RECOVERY_REQUIRED`.

### Attack 3: SharedPreferences Tampering (`onboarding_complete = false`)
- **Vector:** Adversary edits `/data/user/0/com.lockkeeper.app/shared_prefs/lockkeeper_protection_prefs.xml`.
- **Trace:**
  - `ProtectionRepository.evaluatePackage()` and `getSettings()` query Room SQLite table `app_settings` directly.
  - Room returns `onboardingComplete = 1, securityProvisioned = 1`.
  - `getSettings()` immediately overwrites SharedPreferences with the true DB state.
  - Result: **NEUTRALIZED.** SharedPreferences is strictly a read cache; Room is authoritative.

### Attack 4: Rogue Platform Channel Invocations from Flutter
- **Vector:** Adversary injects `PlatformChannelHandler.onMethodCall("setOnboardingComplete", mapOf("complete" to false))`.
- **Trace:**
  - Native handler executes `repository.setOnboardingComplete(false)`.
  - Native method runs `appSettingsDao.setOnboardingComplete(false)`.
  - Crucially, `securityProvisioned` is NOT set to false (only `setProvisionedAndOnboardingComplete` modifies both).
  - On the next decision cycle, `wasPreviouslyProvisioned` evaluates to `true` (because `securityProvisioned == 1`), but `onboardingComplete == 0`.
  - `isRecovery` evaluates to `true`.
  - Result: **BLOCKED.** The rogue call immediately forces the app into `RECOVERY_REQUIRED`, locking down all protected apps.

### Attack 5: Selective Deletion of SQLite Row (`DELETE FROM app_settings WHERE id = 1`)
- **Vector:** SQL injection or direct DB file tampering removes the row.
- **Trace:**
  - `AppSettingsDao.getOrInitializeSettings()` runs `insertInitialSettings(AppSettingsEntity(id = 1))`.
  - A new default entity is created: `securityProvisioned = 0, onboardingComplete = 0`.
  - `wasPreviouslyProvisioned` evaluates to `false`.
  - `isRecovery` evaluates to `false`.
  - Status becomes `SETUP_IN_PROGRESS`.
  - Result: **EXPLOITABLE VIA DIRECT LOCAL DB FILE REMOVAL.**

---

## 3. The Fundamental Dual-Constraint Dilemma

Why does `checkRecoveryStatus()` not check `isAdminActive && !hasCreds` unconditionally?

Because:
1. In First-Run Onboarding, Android requires Device Administrator activation **before** the app can reliably defend credential creation.
2. At the moment Device Admin is granted:
   - `isAdminActive == true`
   - `hasCreds == false`
3. If `isAdminActive && !hasCreds` unconditionally triggered `RECOVERY_REQUIRED`, **every fresh installation would experience the exact Home-screen lockout loop that caused the original incident**.
4. Therefore, distinguishing fresh setup from recovery **requires** a persistent marker of prior provisioning (`securityProvisioned`).
5. If an adversary has filesystem privilege sufficient to wipe `/data/user/0/com.lockkeeper.app/databases/` (such as root access or physical forensics), all local application state is reset. Under Android's permission model, an app cannot preserve SQLite state across its own database deletion.

---

## 4. Adversarial Summary Matrix

| Vector | Attacker Capability | Target State | Intercepted By | Result |
| :--- | :--- | :--- | :--- | :--- |
| `onboardingComplete = false` | Room row edit | `SETUP_IN_PROGRESS` | `checkRecoveryStatus()` | **FAIL** (Becomes `RECOVERY_REQUIRED`) |
| Keystore credentials erased | KeyStore corruption | `SETUP_IN_PROGRESS` | `checkRecoveryStatus()` | **FAIL** (Becomes `RECOVERY_REQUIRED`) |
| SharedPrefs manipulated | SharedPrefs edit | `SETUP_IN_PROGRESS` | `getSettings()` DB reload | **FAIL** (Overwritten by DB truth) |
| Flutter channel hack | MethodChannel call | `SETUP_IN_PROGRESS` | Persistent `securityProvisioned` | **FAIL** (Becomes `RECOVERY_REQUIRED`) |
| Full Room DB file deletion | Root / OS file write | `SETUP_IN_PROGRESS` | None (DB is destroyed) | **ARCHITECTURAL LIMITATION** |
