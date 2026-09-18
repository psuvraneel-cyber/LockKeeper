# Stage 9 — Complete Local Data Loss Forensics & Architectural Analysis

**Target Application:** LockKeeper (`com.lockkeeper.app`)  
**Artifact Inspected:** `app-release.apk` (Version `1.0.0`, VersionCode `1`, SHA-256 `D052DF9F3201C48F73840996609F09AD1F569D3E8287E70845250203574D580D`)  
**Evaluation Date:** 2026-09-18  
**Author:** Agent 4 / Verification Gate Team  

---

## 1. Executive Summary

This investigation analyzes the specific runtime and architectural consequences when a device running LockKeeper suffers **complete local data loss** while **Device Administrator remains active in the Android OS**.

The critical security question addressed:
> *What happens when Device Admin remains active, but LockKeeper's application data is completely wiped (via `pm clear`, private directory wipe, or OS-level storage clear)? Does LockKeeper identify that it was previously provisioned and fail closed into `RECOVERY_REQUIRED`, or does it revert to `SETUP_IN_PROGRESS` (allowing an adversary to bypass previous locks by walking through a fresh onboarding)?*

---

## 2. Technical Trace of Complete Data Loss

### 2.1 OS-Level State Cleared
When an application's data is cleared on Android (e.g., via `adb shell pm clear com.lockkeeper.app` or Settings -> Storage -> Clear All Data):
1. **Private Sandbox Wiped:**
   - `/data/user/0/com.lockkeeper.app/databases/lockkeeper_database*` is unlinked.
   - `/data/user/0/com.lockkeeper.app/shared_prefs/lockkeeper_protection_prefs.xml` is unlinked.
   - All cache, runtime files, and temp assets are erased.
2. **Android Keystore Destruction:**
   - On Android (API 23+ through API 36), the Keystore daemon (`keystore2` / `android.security.keystore`) ties cryptographic aliases (`lockkeeper_user_pin_v1`, `lockkeeper_admin_pw_v1`) to the application's UID and private directory. Calling `pm clear` signals Keystore to permanently delete all keys stored for that UID.
   - Consequently, `KeyStoreCredentialStore.hasPin()` and `KeyStoreCredentialStore.hasAdminPassword()` evaluate to `false`.
3. **Device Administrator Survives:**
   - Device Administrator registrations are stored in system space: `/data/system/device_policies.xml`.
   - Android explicitly retains Device Admin registration across app data clears as long as the package itself is not uninstalled (and even blocks uninstallation while the admin is active).
   - Therefore, `DevicePolicyManager.isAdminActive(adminComponent)` continues to return `true`.

---

## 3. Native Security State Evaluation Post-Data Loss

When LockKeeper is subsequently launched following complete data loss, the native runtime executes the following sequence:

### Step 1: Database Initialization
`ProtectionRepository` accesses Room via `AppSettingsDao.getOrInitializeSettings()`:
```kotlin
// AppSettingsDao.kt
@Transaction
suspend fun getOrInitializeSettings(): AppSettingsEntity {
    val existing = getSettings()
    if (existing != null) return existing
    val default = AppSettingsEntity(id = 1)
    insert(default)
    return default
}
```
In `AppSettingsEntity.kt`:
```kotlin
@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val securityProvisioned: Boolean = false,
    val onboardingComplete: Boolean = false,
    val recoveryRequired: Boolean = false,
    ...
)
```
A fresh default entity is inserted where:
- `securityProvisioned = false`
- `onboardingComplete = false`
- `recoveryRequired = false`

### Step 2: Recovery Status Check
`ProtectionRepository.checkRecoveryStatus()` is invoked:
```kotlin
// ProtectionRepository.kt
suspend fun checkRecoveryStatus(): Boolean = withContext(Dispatchers.IO) {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
    val adminComponent = ComponentName(context, LockKeeperDeviceAdminReceiver::class.java)
    val isAdminActive = dpm?.isAdminActive(adminComponent) == true
    val hasCreds = credentialStore.hasPin() && credentialStore.hasAdminPassword()
    val settings = appSettingsDao.getOrInitializeSettings()

    val wasPreviouslyProvisioned = settings.securityProvisioned || settings.onboardingComplete
    val isRecovery = if (wasPreviouslyProvisioned) {
        !hasCreds || !settings.onboardingComplete || (isAdminActive && !hasCreds)
    } else {
        false
    }
...
```
**Critical Line Analysis:**
- `wasPreviouslyProvisioned` checks: `settings.securityProvisioned || settings.onboardingComplete`.
- Since both values in the newly created Room database are `false`, `wasPreviouslyProvisioned` evaluates to **`false`**.
- As a consequence, `isRecovery` evaluates to **`false`**.
- `checkRecoveryStatus()` returns **`false`**.

### Step 3: Package Evaluation & Enforcement
When `evaluatePackage(targetPackage)` is invoked:
```kotlin
val isProvisioned = settings.securityProvisioned && settings.onboardingComplete // false
val isRecoveryReq = checkRecoveryStatus() // false

decisionEngine.evaluate(
    targetPackage = packageName,
    lockedApp = app, // null (locked_apps table is empty)
    appSettings = settings,
    currentTime = currentTime,
    isRecoveryRequired = isRecoveryReq, // false
    isTamperLocked = false,
    isTamperGraceActive = false,
    securityHealthStatus = if (isRecoveryReq) "RECOVERY_REQUIRED" else if (!isProvisioned) "SETUP_IN_PROGRESS" else null, // "SETUP_IN_PROGRESS"
    isSecurityProvisioned = isProvisioned, // false
    isSetupInProgress = !isProvisioned && !isRecoveryReq // true
)
```
In `LockDecisionEngine.evaluate()`:
```kotlin
// 4. Setup in progress / not yet provisioned: allow normal navigation so user can complete setup
if (isSetupInProgress || securityHealthStatus == "SETUP_IN_PROGRESS") {
    return LockDecision.Allowed(ProtectionDecisionReason.ALLOWED_SETUP_MODE)
}
```
**Outcome:** LockKeeper classifies the installation as `SETUP_IN_PROGRESS`, allowing unrestricted access under `ALLOWED_SETUP_MODE`.

---

## 4. Why Device Admin Active Alone Cannot Prove Previous Provisioning

An intuitive countermeasure might be:
*"If `isAdminActive == true` and credentials are missing, why not infer that the app was previously provisioned and trigger `RECOVERY_REQUIRED`?"*

**The Fatal Conflict:**
This was the **exact root cause of the original Home-Screen Lockout incident**:
1. During normal onboarding (Step 5 of 9), LockKeeper requests the user to grant Device Administrator.
2. The user grants Device Administrator.
3. At this point, the user has **NOT YET created a PIN** (Step 7) or **Admin Password** (Step 8).
4. If `isAdminActive && !hasCreds` were to unconditionally trigger `RECOVERY_REQUIRED`, then the moment Device Administrator is granted during legitimate first-run onboarding, the application instantly flags itself as compromised, triggers `RECOVERY_REQUIRED`, fires `GLOBAL_ACTION_HOME`, and locks the user out of their own phone in an infinite launcher loop!

Therefore, the architectural fix deliberately introduced the `securityProvisioned` persistent latch in Room:
- `wasPreviouslyProvisioned = settings.securityProvisioned || settings.onboardingComplete`
- Before this latch is committed (at Step 9), `isAdminActive` is recognized as a normal step in onboarding.

---

## 5. Security Boundary & Threat Model Analysis

| Threat Scenario | Attacker Capability | System State After Attack | Resulting State | Security Impact |
|---|---|---|---|---|
| **Adversary via ADB** | `adb shell pm clear com.lockkeeper.app` | Room wiped, Keystore wiped, Device Admin active | `SETUP_IN_PROGRESS` | Previous locked app list and PIN are wiped. Attacker can walk through fresh setup. Device is NOT bricked/locked out. |
| **Adversary on Device (Without Root/ADB)** | Physical access to locked phone | Target app locked by PIN overlay; Android Settings protected by LockKeeper | **Blocked** | Attacker cannot reach "Clear Data" button in Settings because `com.android.settings` is guarded by `AdminOverlayView` requiring the Admin Password. |
| **Partial Local Corruption** | File system fault corrupting `credentialStore` or flipping `onboardingComplete=false` while SQLite database file survives | `securityProvisioned = true`, `hasCreds = false` | **`RECOVERY_REQUIRED`** | **Fail-Closed**. The system detects genuine post-provisioning state loss, blocks access, and presents `RecoveryScreen`. |
| **Complete Unlink of SQLite Database** | Database files deleted but app UID preserved | Room recreated with defaults (`securityProvisioned = false`) | `SETUP_IN_PROGRESS` | Application treats as fresh install. |

---

## 6. Architectural Conclusion & Formal Classification

1. **Question 2 Finding:**
   - For **partial corruption**, **credential deletion**, **SharedPreferences tampering**, **process restarts**, and **reboots** where the Room database survives:
     **PASS** (`PREVIOUSLY PROVISIONED + CREDENTIALS LOST → RECOVERY_REQUIRED`).
   - For **complete local data wipe (`pm clear`)**:
     The application has **no surviving authoritative evidence** outside its private sandbox that it was previously provisioned. Because `isAdminActive` cannot be used as a standalone indicator without reintroducing the fatal first-run lockout bug, complete data wipe causes LockKeeper to initialize into `SETUP_IN_PROGRESS`.

2. **Classification:**
   In accordance with the mandatory gate rules:
   > *"If complete data loss makes previous provisioning impossible to distinguish from a fresh install: DO NOT hide this. Report: SECURITY ARCHITECTURE LIMITATION REQUIRING REVIEW"*

   This finding is formally classified as:
   **SECURITY ARCHITECTURE LIMITATION REQUIRING REVIEW**.
