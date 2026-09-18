# Verification Agent 02: Provisioning State Security Review

**Date:** 2026-09-18T03:27:30+05:30  
**Phase:** STAGE 1 — READ-ONLY FORENSICS  
**Subject:** State Lifecycle, Durability, and Provisioning Integrity Analysis  

---

## 1. Scope & Objective

Agent 02 analyzed the authoritative state machine governing provisioning, setup, degraded states, and recovery in LockKeeper. The analysis covers the interaction of:
- `securityProvisioned` (Boolean, Room schema v5)
- `onboardingComplete` (Boolean, Room schema v1)
- `recoveryRequired` (Boolean, Room schema v4)
- `hasPin` & `hasAdminPassword` (KeystoreCredentialStore)
- `isDeviceAdminActive` (Android `DevicePolicyManager`)
- `isOnboardingCompleteSync` (`SharedPreferences`)
- Flutter `ProtectionStatusModel` & `main.dart` routing

---

## 2. In-Depth Answers to Critical Durability Questions

### Q1: What permanently proves an installation has been provisioned?
**Answer:** The permanent proof consists of two synchronized markers:
1. `app_settings.securityProvisioned = 1` and `app_settings.onboardingComplete = 1` committed to the SQLite Room database table `app_settings`.
2. The existence of verified crypto-backed credentials (`hasPin() == true` and `hasAdminPassword() == true`) in `KeystoreCredentialStore` (hardware Keystore + EncryptedSharedPreferences).

### Q2: Where is that proof stored?
**Answer:** 
- Primary relational state: `/data/user/0/com.lockkeeper.app/databases/lockkeeper_database` (`app_settings` entity, row `id = 1`).
- Credential ciphertext: `/data/user/0/com.lockkeeper.app/shared_prefs/lockkeeper_credentials.xml` (AES-256-GCM encrypted via MasterKey in AndroidKeyStore).
- OS-level permission authority: Android OS `/data/system/device_policies.xml` (managed by `DevicePolicyManager`).

### Q3: What survives process death?
**Answer:** 
- All SQLite Room database tables (`app_settings`, `locked_apps`).
- Android Keystore keys and EncryptedSharedPreferences.
- `SharedPreferences` cache (`lockkeeper_protection_prefs`).
- OS-level Device Admin registration in `DevicePolicyManager`.
- Note: Transient in-memory sessions (`LockDecisionEngine.activeSessions`, `SelfLockSessionManager.sessionGranted`) are erased on process death, enforcing immediate re-authentication.

### Q4: What survives reboot?
**Answer:** 
- Same disk and OS structures as process death.
- Dynamic broadcast receiver `screenReceiver` and background coroutines terminate, but `LockKeeperAccessibilityService` is automatically re-instantiated by Android's Accessibility Framework upon user unlock.

### Q5: What survives Activity destruction?
**Answer:** 
- All application and security state survives. The Flutter `MainActivity` is purely an interface renderer. `ProtectionRepository`, `AppDatabase`, `LockKeeperAccessibilityService`, and `LockKeeperForegroundService` operate independently of the Activity lifecycle.

### Q6: What survives Accessibility restart?
**Answer:** 
- All database, credential, and Device Admin states survive.
- Upon service restart, `onServiceConnected()` runs, sets `isConnected = true`, and invokes `repository.notifySecurityStateChanged()`, refreshing the authoritative state.

### Q7: What survives partial corruption (e.g., credentials deleted, or `onboardingComplete` set to false, while `securityProvisioned` remains true)?
**Answer:** 
- **Fail-Closed to Recovery:** In `ProtectionRepository.checkRecoveryStatus()`:
  ```kotlin
  val wasPreviouslyProvisioned = settings.securityProvisioned || settings.onboardingComplete
  val isRecovery = if (wasPreviouslyProvisioned) {
      !hasCreds || !settings.onboardingComplete || (isAdminActive && !hasCreds)
  } else { false }
  ```
  If `securityProvisioned` is true, even if `onboardingComplete` is wiped or credentials are deleted, `wasPreviouslyProvisioned` remains `true`. `isRecovery` evaluates to `true`, writing `recoveryRequired = true`. The system enters `RECOVERY_REQUIRED` and denies app access.

### Q8: What survives complete database deletion?
**Answer:** 
- If the SQLite database file `/data/user/0/com.lockkeeper.app/databases/lockkeeper_database` is completely destroyed (e.g. via direct filesystem unlink or severe database corruption leading to file recreation):
  - Room's `DB_CALLBACK.onCreate()` executes on first access, creating a clean row `id=1` with `onboardingComplete=0` and `securityProvisioned=0`.
  - **CRITICAL ARCHITECTURAL BOUNDARY:** At this exact point, if Device Admin was active in the OS, `settings.securityProvisioned` and `settings.onboardingComplete` in the newly created database are both `0`.
  - In `checkRecoveryStatus()`, `wasPreviouslyProvisioned` evaluates to `false` because the database record that recorded historical provisioning was wiped.
  - As a result, the code evaluates this condition as a fresh installation (`isRecovery = false`).
  - *Detailed analysis of this boundary is documented in Section 3 below and `audit/VERIFY_COMPLETE_DATA_LOSS_ANALYSIS.md`.*

### Q9: What happens if SharedPreferences is stale or cleared?
**Answer:** 
- `SharedPreferences` (`lockkeeper_protection_prefs`) is strictly a non-authoritative read cache for fast synchronous checks.
- When `ProtectionRepository` evaluates security status or handles method calls, it directly queries Room `appSettingsDao.getOrInitializeSettings()`, which is the ground truth. Any mismatch causes SharedPreferences to be immediately overwritten with the database value.

### Q10: What happens if Flutter state is manipulated?
**Answer:** 
- Flutter state is completely non-authoritative.
- `LockDecisionEngine` and `LockKeeperAccessibilityService` execute exclusively in the native Android JVM process and query Room/Keystore directly.
- Tampering with Dart variables, Hot Reloading, or sending rogue platform channel calls cannot alter native decision evaluation.

---

## 3. Analysis: Can a Previously Provisioned Installation Become `SETUP_IN_PROGRESS`?

### Scenario A: Local Database Intact, Credentials Compromised
- `securityProvisioned = true`, `hasCreds = false`.
- `wasPreviouslyProvisioned = true`.
- `isRecovery = true`.
- Result: **RECOVERY_REQUIRED**. (Cannot become `SETUP_IN_PROGRESS`).

### Scenario B: Local Database Row Tampering (`onboardingComplete = 0`, but `securityProvisioned = 1`)
- `wasPreviouslyProvisioned = true`.
- `isRecovery = true` (because `!settings.onboardingComplete`).
- Result: **RECOVERY_REQUIRED**. (Cannot become `SETUP_IN_PROGRESS`).

### Scenario C: Complete App Data Cleared via Android Settings (`pm clear`)
- When a user or OS executes `pm clear com.lockkeeper.app`:
  - Android OS automatically revokes all Device Admin privileges and disables all Accessibility Services for that package.
  - The installation becomes a literal new installation from the OS perspective.
  - Result: **Fresh Installation** (Device Admin is inactive; safe setup mode).

### Scenario D: Selective Deletion of SQLite Database while Device Admin remains registered in OS
- If an adversary with local app-level code execution deletes only the Room DB file:
  - The newly initialized DB has `securityProvisioned = 0`.
  - Device Admin remains active in `DevicePolicyManager`.
  - Current logic evaluates `wasPreviouslyProvisioned = false`, classifying it as `SETUP_IN_PROGRESS`.
  - This specific condition represents an architectural boundary question: Should `isAdminActive && !hasCreds` unconditionally trigger `RECOVERY_REQUIRED` if any persistent marker of prior installation exists?
  - This is comprehensively addressed in `audit/VERIFY_COMPLETE_DATA_LOSS_ANALYSIS.md`.
