# LockKeeper Wave 1: Security State Architecture Specification

**Document Version:** 1.0.0  
**Status:** Approved Architectural Blueprint  
**Lead Architect:** Wave 1 Architecture Specialist Group  
**Date:** September 17, 2026  

---

## 1. Executive Problem Statement

Prior to Wave 1, LockKeeper suffered from state bifurcation across 9 distinct layers:
1. Android OS System Services (`DevicePolicyManager`, `Settings.Secure`, `AppOpsManager`, `PowerManager`, `Settings.canDrawOverlays`)
2. Android Service Lifecycles (`LockKeeperAccessibilityService.isConnected`, `LockKeeperForegroundService.isRunning`)
3. Hardware Keystore / Encrypted Preferences (`CredentialStore`: PIN salt/hash, Admin Password salt/hash)
4. Persistent SQLite / Room (`app_settings` entity: row id=1)
5. Repository Cache (`lockkeeper_protection_prefs` SharedPreferences)
6. Native Singletons (`ProtectionRepository.INSTANCE`, `OverlayManager.INSTANCE`)
7. Platform Channel (`MethodChannel` & `EventChannel`)
8. Flutter Memory (`_protectionStatus`, `_isLocked`, `_isInitialized`)
9. Flutter Presentation Layer (`HomeScreen`, `SettingsScreen`, `OnboardingScreen`, `SelfLockGateScreen`)

This fragmentation allowed divergent states where:
- The UI could report "Protected" when the Accessibility Service was disconnected.
- Service disconnection was conflated with permission revocation.
- Platform Channel exceptions defaulted to permissive booleans (`false`).
- An active Device Admin in the OS could coexist with missing credentials without triggering recovery.

Wave 1 establishes a **Single Source of Truth (SSOT)** architecture where native system truth unconditionally dictates presentation state.

---

## 2. Complete State Map & Source-of-Truth Matrix

| State Variable | Category | Authoritative Source of Truth (SSOT) | Cached / Derivative Source | Write Path | Read Path | Process-Death Behavior | Reboot Behavior | Clear-Data Behavior | UI Representation |
|---|---|---|---|---|---|---|---|---|---|
| `deviceAdminActive` | System | `DevicePolicyManager.isAdminActive(adminComponent)` | None (live OS query) | Android Settings UI / `DeviceAdminAdd` | `dpm.isAdminActive()` | Unaffected (persisted by OS) | Unaffected (persisted by OS) | Remains `true` in OS while app data erased | "Device Administrator" Active/Revoked |
| `accessibilityEnabled` | System | `Settings.Secure.getString(ENABLED_ACCESSIBILITY_SERVICES)` | None (live OS query) | Android Settings UI | Secure Settings query | Unaffected (persisted by OS) | Unaffected (persisted by OS) | Cleared by OS if package wiped | "Accessibility Service" Enabled/Disabled |
| `accessibilityConnected` | Runtime | `LockKeeperAccessibilityService.isConnected` | Static volatile boolean | Service `onServiceConnected()` / `onDestroy()` | Static volatile boolean | Resets to `false` | Resets to `false` | Resets to `false` | Service connection status |
| `accessibilityOperational` | Runtime | Composite: `accessibilityEnabled && accessibilityConnected` | None | Calculated | Calculated | `false` until service connects | `false` until service connects | `false` | Operational indicator |
| `overlayPermissionGranted` | System | `Settings.canDrawOverlays(context)` | None (live OS query) | Android Settings UI | `Settings.canDrawOverlays()` | Unaffected (persisted by OS) | Unaffected (persisted by OS) | Reset by OS on reinstall | "Overlay Permission" Active/Revoked |
| `usageStatsGranted` | System | `AppOpsManager.unsafeCheckOpNoThrow(OPSTR_GET_USAGE_STATS)` | None (live OS query) | Android Settings UI | `AppOpsManager` query | Unaffected (persisted by OS) | Unaffected (persisted by OS) | Reset by OS on reinstall | "Usage Access" Active/Revoked |
| `batteryExempted` | System | `PowerManager.isIgnoringBatteryOptimizations(packageName)` | None (live OS query) | Android Settings UI | `PowerManager` query | Unaffected (persisted by OS) | Unaffected (persisted by OS) | Reset by OS on reinstall | "Battery Optimization" Active/Revoked |
| `userPinConfigured` | Config | `KeystoreCredentialStore.hasPin()` | Preferences key `key_pin_hash` | `CredentialStore.setPin()` | `CredentialStore.hasPin()` | Preserved in app storage | Preserved in app storage | Erased (triggers Recovery) | "Change User PIN" available |
| `adminPasswordConfigured`| Config | `KeystoreCredentialStore.hasAdminPassword()` | Preferences key `key_admin_password_hash`| `CredentialStore.setAdminPassword()`| `CredentialStore.hasAdminPassword()` | Preserved in app storage | Preserved in app storage | Erased (triggers Recovery) | "Change Admin Password" available |
| `onboardingComplete` | Config | Room DB `app_settings.onboardingComplete` (row 1) | `lockkeeper_protection_prefs` (write-through cache) | `ProtectionRepository.setOnboardingComplete()` | `appSettingsDao.getOrInitializeSettings()` | Preserved in SQLite | Preserved in SQLite | Erased (triggers Recovery) | Roots to `HomeScreen` vs `Onboarding` |
| `selfLockConfigured` | Config | Room DB `app_settings.selfLockEnabled` (row 1) | None | `ProtectionRepository.setSelfLockEnabled()` | `appSettingsDao.getOrInitializeSettings()` | Preserved in SQLite | Preserved in SQLite | Erased | "Protect LockKeeper" Switch |
| `selfLockTimeoutSeconds` | Config | Room DB `app_settings.selfLockTimeoutSeconds` (row 1) | None | `ProtectionRepository.setSelfLockTimeoutSeconds()` | `appSettingsDao.getOrInitializeSettings()` | Preserved in SQLite | Preserved in SQLite | Erased | "Lock Timeout" selection |
| `appLockConfigured` | Config | Room DB `locked_apps` count > 0 | None | `lockedAppDao.upsert() / delete()` | `lockedAppDao.getAllLockedApps()` | Preserved in SQLite | Preserved in SQLite | Erased | Locked count badge in UI |
| `appLockOperational` | Runtime | Composite: `appLockConfigured && accessibilityOperational && overlayGranted` | None | Calculated | Calculated | `false` until runtime operational | `false` until runtime operational | `false` | Overall protection status |
| `tamperLockoutActive` | Security | Monotonic deadline + Room `adminLockoutUntil` | In-memory `adminLockoutUntilElapsed` | `TamperAuthorizationController.verifyAdminPassword()` | `TamperAuthorizationController.checkLockout()` | Preserved in Room DB | Preserved in Room DB | Erased | Admin overlay locked countdown |
| `recoveryRequired` | Recovery | Room DB `app_settings.recoveryRequired` (row 1) | None | `ProtectionRepository.checkRecoveryStatus()` | `appSettingsDao.getOrInitializeSettings()` | Preserved in SQLite | Preserved in SQLite | Re-evaluated on startup | `RECOVERY_REQUIRED` UI barrier |

---

## 3. Authoritative Domain Architecture

```
                               +--------------------------------------------+
                               |              ANDROID OS LEVEL              |
                               |  - DevicePolicyManager                     |
                               |  - Settings.Secure (Enabled A11y Services) |
                               |  - AppOpsManager (Usage Access)            |
                               |  - PowerManager (Battery Optimization)     |
                               |  - WindowManager (Overlay Permission)      |
                               +--------------------------------------------+
                                                     |
                                                     v
                               +--------------------------------------------+
                               |            NATIVE RUNTIME LEVEL            |
                               |  - LockKeeperAccessibilityService          |
                               |    * isConnected                           |
                               |    * onServiceConnected() / onDestroy()    |
                               |  - LockKeeperDeviceAdminReceiver           |
                               |    * onEnabled() / onDisabled()            |
                               |  - KeystoreCredentialStore                 |
                               |  - Room DB (app_settings row 1)            |
                               +--------------------------------------------+
                                                     |
                                                     v
                               +--------------------------------------------+
                               |     ProtectionRepository (Aggregator)      |
                               |  - getAuthoritativeSecurityHealth()        |
                               |  - checkRecoveryStatus()                   |
                               |  - computeOverallSecurityHealth()          |
                               +--------------------------------------------+
                                                     |
                                                     v
                               +--------------------------------------------+
                               |     PlatformChannelHandler & Bridge        |
                               |  - Structured Map: SecurityHealth          |
                               |  - EventChannel: protectionStateChanged    |
                               +--------------------------------------------+
                                                     |
                                                     v
                               +--------------------------------------------+
                               |             FLUTTER UI LEVEL               |
                               |  - SecurityHealthModel (Dart)              |
                               |  - Pure Projection (Zero Business Logic)   |
                               |  - OverallStatus:                          |
                               |    * PROTECTED                             |
                               |    * CONFIGURED                            |
                               |    * DEGRADED                              |
                               |    * RECOVERY_REQUIRED                     |
                               |    * UNKNOWN                               |
                               +--------------------------------------------+
```

---

## 4. Security Health Evaluation Model

The authoritative health is calculated by `ProtectionRepository.computeOverallSecurityHealth()`:

```kotlin
enum class OverallSecurityStatus {
    PROTECTED,            // All configured protections are fully operational
    CONFIGURED,           // Onboarded and credentials set, but app lock or self-lock not enabled
    DEGRADED,             // Permissions missing or service disconnected
    RECOVERY_REQUIRED,    // DPM active in OS but local credentials wiped
    INITIALIZING,         // First boot or startup in progress
    UNKNOWN               // Native API failure or unexpected exception
}
```

### Evaluation Hierarchy:
1. **RECOVERY_REQUIRED:** If `checkRecoveryStatus() == true` (DPM is active, but credentials or DB state are missing). Overrides all other states.
2. **DEGRADED:** If `onboardingComplete == true`, but ANY required permission is revoked OR `isAccessibilityConnected == false`.
3. **PROTECTED:** If `onboardingComplete == true`, all permissions are granted, `isAccessibilityOperational == true`, credentials exist, and protection is active.
4. **CONFIGURED:** If `onboardingComplete == true` and permissions are granted, but no apps are locked and self-lock is disabled.
5. **INITIALIZING / UNKNOWN:** If startup is incomplete or native queries fail.

---

## 5. Platform Channel Error Contract

Security-sensitive platform channel operations must never fail open:
- `getProtectionStatus` returns a structured map containing `overallStatus`, all permissions, connection states, and recovery flags.
- In Dart, if `getProtectionStatus` throws an exception, `SecurityHealthModel` defaults to `OverallSecurityStatus.unknown` (displaying an alert), NEVER `OverallSecurityStatus.protected`.
- `checkPermissionStatus` distinguishes between `GRANTED`, `DENIED`, and `UNAVAILABLE`.
