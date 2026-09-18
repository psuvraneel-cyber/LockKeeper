# LockKeeper Wave 1: Security State Architecture & Verification Report

**Program:** LockKeeper Security Remediation  
**Wave:** Wave 1 — Unified Security State Architecture  
**Document Version:** 1.0.0  
**Lead Architect:** Wave 1 Multi-Agent Architecture Team  
**Date:** September 17, 2026  
**Final Wave Verdict:** **PASS — READY FOR WAVE 2**

---

## 1. Previous Audit Findings Addressed

During previous program phases and pre-Wave 1 validation, several architectural state fragmentation vulnerabilities were discovered:

| Finding ID | Previous Defect Description | Wave 1 Remediation |
|---|---|---|
| **STATE-01** | **Accessibility Conflation:** The system conflated Android Settings permission toggle with background service binder connectivity. When the service crashed or was killed by Android LMK, the UI declared "Permission Revoked" and prompted users to enable it in Settings again. | **Separated into 3 distinct states:** `isAccessibilityGranted` (System Settings SSOT), `isAccessibilityConnected` (Binder lifecycle SSOT), and `isAccessibilityOperational` (`granted && connected`). Disconnection is now cleanly reported as "Disconnected" without claiming permission loss. |
| **STATE-02** | **False "Protected" UI State:** The Flutter UI displayed a green "Protected" shield whenever app locking was configured, even if Accessibility was disconnected or Device Admin had been deactivated. | **Authoritative Native Evaluation:** `ProtectionRepository.getAuthoritativeSecurityStatus()` now calculates `overallStatus` natively (`PROTECTED`, `CONFIGURED`, `DEGRADED`, `RECOVERY_REQUIRED`, `INITIALIZING`, `UNKNOWN`). Flutter UI is purely a passive projection and can never report "Protected" unless native confirms all operational requirements are met. |
| **STATE-03** | **Fail-Open Platform Channel Defaults:** When platform channel calls failed or encountered exceptions, Dart code caught the error and defaulted to `false`, which in some branches resulted in bypasses or inconsistent state. | **Fail-Safe Closed Model:** `ProtectionStatusModel.unknown()` and `PlatformBridge.getProtectionStatus()` now explicitly construct `SecurityOverallStatus.unknown` with clear degradation reasons. It is structurally impossible for an exception to result in an authorization grant or a "Protected" status. |
| **STATE-04** | **Device Admin State Desync:** Device Admin state was cached in SharedPreferences or memory. Disabling Device Admin externally in Android Settings went undetected until app restart. | **DevicePolicyManager SSOT + Live Event Propagation:** Device Admin state is queried directly from `DevicePolicyManager.isAdminActive()` on every security evaluation. Furthermore, `LockKeeperDeviceAdminReceiver.onEnabled()` and `onDisabled()` immediately broadcast `notifySecurityStateChanged()` over the native event channel. |
| **STATE-05** | **Silent Reset of Missing State (Recovery Required):** If app storage was wiped while Device Admin remained active in the OS, the app previously routed directly to normal onboarding, potentially leaving device administration orphaned. | **Authoritative Recovery Enforcement:** Native `checkRecoveryStatus()` detects active Device Admin alongside missing local credentials and flags `RECOVERY_REQUIRED`. The UI intercepts this state and locks the interface into a red Recovery banner, preventing onboarding overwrite. |

---

## 2. Source-of-Truth Architecture

LockKeeper enforces an unambiguous hierarchy of truth:

```
                      +------------------------------------------+
                      |         TIER 1: ANDROID OS TRUTH         |
                      |  - DevicePolicyManager.isAdminActive()   |
                      |  - Settings.Secure (A11y Services)       |
                      |  - Settings.canDrawOverlays()            |
                      |  - AppOpsManager (Usage Access)          |
                      +------------------------------------------+
                                           |
                                           v
                      +------------------------------------------+
                      |       TIER 2: NATIVE RUNTIME TRUTH       |
                      |  - AccessibilityService.isConnected      |
                      |  - KeystoreCredentialStore (PIN/Admin)   |
                      |  - Room SQLite DB (app_settings row 1)   |
                      |  - TamperAuthorizationController (Lockout|
                      +------------------------------------------+
                                           |
                                           v
                      +------------------------------------------+
                      |  TIER 3: AGGREGATED STATE COORDINATOR    |
                      |  - ProtectionRepository                  |
                      |  - getAuthoritativeSecurityStatus()      |
                      |  - Computes overallStatus + reasons      |
                      +------------------------------------------+
                                           |
                                           v
                      +------------------------------------------+
                      |         TIER 4: FLUTTER PROJECTION       |
                      |  - PlatformBridge (EventChannel stream)  |
                      |  - ProtectionStatusModel (Immutable)     |
                      |  - HomeScreen / SettingsScreen (Passive) |
                      +------------------------------------------+
```

### Critical Rules:
1. **Flutter Memory is Never Authoritative:** Dart code cannot create, elevate, or override security states.
2. **Persistent Storage Represents Intended Configuration, Not Transient Runtime Truth:** Room stores user settings (`selfLockEnabled`, `cooldownMinutes`); OS system services provide live capability truth.
3. **No Dual-Sided Decisions:** All evaluation of operational health is performed in Kotlin on the Android native side.

---

## 3. SecurityState Architecture

The unified security model is represented natively as a structured payload:

```json
{
  "overallStatus": "PROTECTED | CONFIGURED | DEGRADED | RECOVERY_REQUIRED | INITIALIZING | UNKNOWN",
  "degradedReasons": [
    "Accessibility Service is enabled in Settings but background service is disconnected",
    "Overlay Permission is not granted"
  ],
  "isForegroundServiceRunning": true,
  "isOverlayGranted": true,
  "isUsageGranted": true,
  "isAccessibilityGranted": true,
  "isAccessibilityConnected": false,
  "isAccessibilityOperational": false,
  "isDeviceAdminGranted": true,
  "isBatteryExempted": true,
  "hasPin": true,
  "hasAdminPassword": true,
  "selfLockActive": true,
  "appLockConfigured": true,
  "appLockOperational": false,
  "tamperLockedOut": false,
  "recoveryRequired": false,
  "onboardingComplete": true
}
```

### Overall Status State Machine:
1. **`RECOVERY_REQUIRED`**: Set if `checkRecoveryStatus() == true` (Device Admin active in OS but local credentials missing or state corrupted). Overrides all other states.
2. **`INITIALIZING`**: Set if `!onboardingComplete`.
3. **`DEGRADED`**: Set if any prerequisite is missing:
   - `!isDeviceAdminActive`
   - `!isAccessibilityOperational` (`!isA11yEnabled || !isA11yConnected`)
   - `!isOverlayGranted`
   - `!isUsageGranted`
   - `!hasPin`
   - `!hasAdminPassword`
4. **`PROTECTED`**: Set when all system permissions and operational requirements are satisfied AND at least one protection module is configured (`appLockConfigured || selfLockActive`).
5. **`CONFIGURED`**: Set when all requirements are satisfied, but no apps are locked and self-lock is disabled.
6. **`UNKNOWN`**: Emitted upon platform channel error, IPC failure, or uninitialized cache. Fail-safe closed.

---

## 4. Accessibility State Model

The accessibility state is cleanly partitioned into three independent dimensions:

```
+-----------------------------------------------------------------------------+
| System Setting: Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES              |
| -> isAccessibilityGranted: true/false                                       |
+-----------------------------------------------------------------------------+
                                       |
                                       v
+-----------------------------------------------------------------------------+
| Runtime Lifecycle: LockKeeperAccessibilityService.isConnected               |
| -> isAccessibilityConnected: true/false                                     |
+-----------------------------------------------------------------------------+
                                       |
                                       v
+-----------------------------------------------------------------------------+
| Operational Status: isAccessibilityGranted && isAccessibilityConnected      |
| -> isAccessibilityOperational: true/false                                   |
+-----------------------------------------------------------------------------+
```

- **Permission Revocation vs. Disconnection:**
  - If `isAccessibilityGranted == true` and `isAccessibilityConnected == false`, the Settings UI displays **"Disconnected" (Amber)** with subtitle *"Permission enabled, but service binder is dormant"*.
  - It does NOT claim "Revoked", and does not clear any credentials or settings.
  - When the service reconnects (`onServiceConnected`), `notifySecurityStateChanged()` triggers an instantaneous transition back to **"Active" (Green)**.

---

## 5. Device Admin State Model

- **Source of Truth:** Exclusively `DevicePolicyManager.isAdminActive(ComponentName(context, LockKeeperDeviceAdminReceiver::class.java))`.
- **Receiver Interception:**
  - `LockKeeperDeviceAdminReceiver.onEnabled()`: Broadcasts state change immediately.
  - `LockKeeperDeviceAdminReceiver.onDisabled()`: Broadcasts state change immediately, downgrading overall status to `DEGRADED`.
  - `LockKeeperDeviceAdminReceiver.onDisableRequested()`: If self-protection is active, returns warning message to the user before deactivation.
- **No Cached Bypass:** Every call to `getAuthoritativeSecurityStatus()` queries the live `DevicePolicyManager` instance.

---

## 6. Recovery State Model

- **Trigger Condition:** `dpm.isAdminActive() == true && (!hasPin || !hasAdminPassword || !onboardingComplete || recoveryRequired)`.
- **Handling:**
  - `appSettingsDao.setRecoveryRequired(true)` persists the condition to SQLite.
  - `overallStatus` transitions unconditionally to `RECOVERY_REQUIRED`.
  - In Flutter, `HomeScreen` displays a high-priority red alert banner: *"Device Administrator is active in the OS, but local security credentials or configuration are missing. Secure recovery is required."*
  - Normal onboarding navigation is blocked to prevent accidental overwrite or bypass of orphan device policies.

---

## 7. Platform-Channel Error Model

- **Channel Contract:**
  - Methods: `getProtectionStatus`, `getSecurityHealth`, `checkPermission`.
  - Streams: `protectionStateChanged` (EventChannel).
- **Error Handling:**
  - If native throws an exception, `MethodChannel` returns `PlatformException`.
  - `PlatformBridge.getProtectionStatus()` catches all exceptions and returns `ProtectionStatusModel.unknown(reasons: [...])`.
  - Null payloads automatically default to `ProtectionStatusModel.unknown()`.
  - **No Permissive Fallback:** An error never produces `isProtected = true` or `overallStatus = protected`.

---

## 8. Flutter / Native Boundary

| Concern | Android Native (Kotlin) | Flutter (Dart) |
|---|---|---|
| **Authoritative Decision** | YES — Determines all security states, lock decisions, lockouts | NO — Strictly forbidden |
| **Credential Verification** | YES — KeyStore salt + SHA-256 in KeystoreCredentialStore | NO — Transmits input PIN/password for remote check |
| **Tamper Detection & Overlay** | YES — TamperDetectionEngine + WindowManager overlay | NO — Receives informational notifications only |
| **State Push Synchronization** | YES — `EventChannel` emits on any lifecycle/security event | NO — Listens and updates UI state via `setState` |
| **Presentation / UX** | Background service / overlay UI | Foreground interactive screens (Home, Settings, Gate) |

---

## 9. Lifecycle Behavior

- **`MainActivity.onPause()` / `onStop()` / `onDestroy()`:**
  - Does NOT alter credentials, Room DB, or service state.
  - Activity recreation simply queries `getAuthoritativeSecurityStatus()`.
  - `MainActivity.onResume()` invokes `ProtectionRepository.getInstance(applicationContext).notifySecurityStateChanged()`, ensuring that any changes made while in background or system settings are reflected immediately.
- **Process Death & Restart:**
  - Room DB persists `app_settings` and `locked_apps`.
  - `KeystoreCredentialStore` persists PIN and Admin Password hashes.
  - On restart, `AppDatabase.getOrInitializeSettings()` reloads persistent configuration and checks live OS permissions.

---

## 10. Persistence Behavior

- **Room Database Schema:** Preserved intact with zero breaking changes.
  - `MIGRATION_3_4` remains fully intact (added `failedAdminAttempts`, `adminLockoutUntil`, `recoveryRequired`).
  - Table `app_settings`: Single row with `id = 1`.
  - Table `locked_apps`: Package-specific lock configuration and cooldowns.
- **Credential Storage:** `lockkeeper_secure_creds` preferences file stores encrypted salts and PBKDF2/SHA-256 hashes.
- **Write-Through Caching:** SharedPreferences `lockkeeper_protection_prefs` acts strictly as an unprivileged read-through cache for synchronous onboarding checks, updated write-through by Room.

---

## 11. Tests Added & Verification Results

### Test Count Summary:
- **Android JVM Unit Tests:** **113 passed, 0 failed, 0 ignored** (up from 78 baseline).
- **Flutter Unit & Widget Tests:** **13 passed, 0 failed, 0 ignored** (up from 8 baseline).
- **Total Automated Tests:** **126 passed (100% success rate)**.

### New Test Suites:
1. `SecurityStateArchitectureTest.kt` (35 tests):
   - Invariants 1 through 15.
   - Cases A through T.
2. `security_state_test.dart` (5 tests):
   - Model parsing, degraded reason propagation, recoveryRequired routing, fail-closed unknown constructor.

---

## 12. Remaining Risks & Runtime Validation

1. **OEM-Specific Accessibility Killers:** Certain aggressive task killers (e.g. Huawei EMUI, Xiaomi MIUI battery saver) may kill the AccessibilityService binder unexpectedly. The state architecture now handles this safely by entering `DEGRADED (Disconnected)` without losing user settings.
2. **Physical Device Runtime Validation Required:**
   - Physical device verification of `LockKeeperDeviceAdminReceiver.onDisabled()` trigger speed.
   - Verification of MIUI Accessibility Settings pass-through on physical MIUI hardware.

---

## 13. Files Changed

| File Path | Nature of Change |
|---|---|
| `android/app/src/main/kotlin/com/lockkeeper/app/domain/ProtectionRepository.kt` | Added `overallStatus` calculation, structured `degradedReasons`, and `notifySecurityStateChanged()`. |
| `android/app/src/main/kotlin/com/lockkeeper/app/bridge/PlatformChannelHandler.kt` | Added `getSecurityHealth` method; refined `checkPermission` for A11y connectivity/operational separation. |
| `android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperAccessibilityService.kt` | Added state change broadcasts on `onServiceConnected()` and `onDestroy()`. |
| `android/app/src/main/kotlin/com/lockkeeper/app/receiver/LockKeeperDeviceAdminReceiver.kt` | Added state change broadcasts on `onEnabled()` and `onDisabled()`. |
| `android/app/src/main/kotlin/com/lockkeeper/app/MainActivity.kt` | Hooked `onResume()` to trigger state synchronization. |
| `lib/core/models/protection_status_model.dart` | Added `SecurityOverallStatus` enum, reasons parsing, and fail-closed `unknown()` factory. |
| `lib/core/services/platform_bridge.dart` | Updated `getProtectionStatus` with fail-safe error catching and added `getSecurityHealth`. |
| `lib/ui/screens/home_screen.dart` | EventChannel live subscription, dynamic shield styling, degradation banner, and recovery alerts. |
| `lib/ui/screens/settings_screen.dart` | EventChannel live subscription, three-state Accessibility status tile, and recovery card. |
| `android/app/src/test/kotlin/com/lockkeeper/app/SecurityStateArchitectureTest.kt` | Comprehensive JVM test suite covering 15 Invariants and Cases A-T (35 tests). |
| `test/security_state_test.dart` | Flutter unit test suite covering state parsing and error safety. |
| `audit/WAVE1_SECURITY_STATE_ARCHITECTURE.md` | Authoritative architecture blueprint and state map. |
| `audit/WAVE1_SECURITY_STATE_TEST_MATRIX.md` | Detailed test mapping and execution results. |

---

## 14. Regression Analysis

All completed Self-Protection capabilities were rigorously regression-tested and preserved:
- **MIUI Accessibility Settings Fix:** Preserved. `MiuiAccessibilitySettingsActivity` and general accessibility settings remain accessible without false tamper triggers (tested in `SecurityInvariantsTest.kt` Invariant 9).
- **Accessibility Black Screen Fix:** Preserved. Window bounds and overlay dismissal on target activity transitions remain intact.
- **Admin Password Persistent Lockout:** Preserved. 5 failed attempts locks out for 5 minutes across reboots (tested in `SecurityInvariantsTest.kt` Invariant 5 and `CASE Q`).
- **Tamper Authorization Controller:** Preserved. Monotonic 30s grace window and anti-race mutex locks intact (tested in `TamperAuthorizationControllerTest.kt`).
- **Database Schema:** Untouched. `MIGRATION_3_4` and all DAO methods preserved without migration breakage.

---

## 15. Final Gate Evaluation

| Gate | Requirement | Evidence / Test Support | Gate Verdict |
|---|---|---|---|
| **GATE A — Single Security State Architecture** | Explicit native-backed model distinguishing Config, System, Runtime, and Recovery states without boolean collapse. | `overallStatus` enum in Kotlin and Dart; structured `degradedReasons`; tested across Cases A-T. | **PASS** |
| **GATE B — Native Source of Truth** | Flutter memory never authoritative for security-critical decisions; native evaluates state. | Flutter models are read-only projections; platform channel returns native SSOT; Invariant 5 tested. | **PASS** |
| **GATE C — Accessibility State Separation** | Clear separation between `isAccessibilityGranted` (System), `isAccessibilityConnected` (Binder), and `isAccessibilityOperational`. | Settings tile separates "Active", "Disconnected", and "Disabled"; Invariants 2, 6, 10 and Cases A, B, C, O, P tested. | **PASS** |
| **GATE D — Device Admin State** | Device Admin truth sourced exclusively from `DevicePolicyManager.isAdminActive()`. | Live DPM call in `getAuthoritativeSecurityStatus()`; receiver hooks broadcast changes; Invariants 1, 14 and Cases D, E, T tested. | **PASS** |
| **GATE E — Platform Channel Safety** | Security methods fail closed on exception; never default to permissive booleans. | Catch blocks construct `ProtectionStatusModel.unknown()`; Invariant 6 and Cases J, K, L tested. | **PASS** |
| **GATE F — Lifecycle Safety** | Activity/service destruction does not erase configuration or state truth. | Invariants 3, 4 and Cases M, N tested; Room DB and Keystore survive activity destroy and service death. | **PASS** |
| **GATE G — Persistence Safety** | Room DB schema and migrations preserved; Room authoritative over SharedPreferences. | `MIGRATION_3_4` intact; `DatabaseMigrationTest` passes; Invariant 11 and Case S tested. | **PASS** |
| **GATE H — UI Security Truthfulness** | UI never displays "Protected" during Degraded, Unknown, or RecoveryRequired states. | `ProtectionStatusModel.isProtected` checks `overallStatus == SecurityOverallStatus.protected`; Invariant 9 tested. | **PASS** |
| **GATE I — Regression Safety** | Zero regressions in Self-Protection, anti-tamper, MIUI fix, or black-screen prevention. | All 78 baseline JVM tests + 8 baseline Flutter tests pass without modification. | **PASS** |
| **GATE J — Test Sufficiency** | Deterministic tests for all 15 Invariants and Cases A-T; 100% pass rate. | 126 automated tests passing (113 JVM + 13 Flutter); 0 failures, 0 errors. | **PASS** |

---

## Final Wave 1 Question:
**"Is Wave 1 complete and safe enough to proceed to Wave 2?"**

### **YES.**
All architectural gates are **PASS**. The security state architecture is unified, authoritative, fail-safe closed, and thoroughly verified by 126 automated tests.
