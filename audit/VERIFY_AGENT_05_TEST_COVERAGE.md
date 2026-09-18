# Verification Agent 05: Test Coverage & Validity Review

**Date:** 2026-09-18T03:28:12+05:30  
**Phase:** STAGE 1 — READ-ONLY FORENSICS  
**Subject:** Rigorous Audit of JVM and Dart Automated Test Suites (TEST-601 through TEST-626)  

---

## 1. Scope & Objective

Agent 05 audited the 26 incident resolution unit tests (`IncidentResolutionUnitTest.kt` and `test/incident_resolution_test.dart`) and existing regression suites (`DatabaseMigrationTest.kt`, `SecurityInvariantsTest.kt`, `ProductionSecurityBoundaryTest.kt`, `SecurityStateArchitectureTest.kt`, `RuntimeResilienceTest.kt`, `ProtectionEnforcementTest.kt`, `LockDecisionEngineTest.kt`).

The goal is to determine:
1. What TEST-601 through TEST-626 mathematically prove.
2. What they **cannot** prove due to JVM mocking and sandbox abstractions.
3. Where test assumptions diverge from the physical Android 12 / MIUI 14 runtime boundary.

---

## 2. Test Coverage Mapping: TEST-601 to TEST-626

| Test ID | Method Name | Logical Assertion |
| :--- | :--- | :--- |
| **TEST-601** | Clean install initializes to `SETUP_IN_PROGRESS` | Default settings yield `isRecovery = false`, `status = SETUP_IN_PROGRESS`. |
| **TEST-602** | Device Admin during setup preserves `SETUP_IN_PROGRESS` | `isAdminActive = true` + `wasPreviouslyProvisioned = false` -> `isRecovery = false`. |
| **TEST-603** | Missing PIN does not trip `RECOVERY_REQUIRED` on fresh install | Admin pass set, PIN absent -> remains `SETUP_IN_PROGRESS`. |
| **TEST-604** | Missing Admin Password does not trip `RECOVERY_REQUIRED` | PIN set, admin pass absent -> remains `SETUP_IN_PROGRESS`. |
| **TEST-605** | Device Admin + A11y connected remains `SETUP_IN_PROGRESS` | Neither permission trips recovery on clean install. |
| **TEST-606** | Launcher foreground event returns `LockDecision.Allowed` | `com.miui.home` returns `Allowed(ALLOWED_SETUP_MODE)`. |
| **TEST-607** | LockKeeper remains launchable during setup | `com.lockkeeper.app` returns `Allowed(ALLOWED_OWN_PACKAGE)`. |
| **TEST-608** | Android Settings remains usable during setup | `com.android.settings` returns `Allowed(ALLOWED_SETUP_MODE)`. |
| **TEST-609** | Launcher packages recognized; third-party apps excluded | String pattern matching for MIUI, Samsung, Nexus, AOSP launchers. |
| **TEST-610** | Deleted credentials on provisioned install enter recovery | `securityProvisioned = true` + `hasCreds = false` -> `RECOVERY_REQUIRED`. |
| **TEST-611** | Corrupted Room settings enforce `RecoveryRequired` decision | `recoveryRequired = true` -> `LockDecision.RecoveryRequired`. |
| **TEST-612** | Stale SharedPreferences cannot override native Room DB | Room DB `securityProvisioned = false` overrides Prefs `true`. |
| **TEST-613** | Flutter cannot demote `SECURITY_PROVISIONED` to setup mode | `setOnboardingComplete(false)` retains `securityProvisioned = true`. |
| **TEST-614** | Process restart during setup preserves setup state | Re-instantiated DAO preserves `securityProvisioned = false`. |
| **TEST-615** | A11y restart during setup maintains setup mode | New evaluation returns `Allowed(ALLOWED_SETUP_MODE)`. |
| **TEST-616** | Device reboot during setup maintains setup mode | Disk state preserves setup mode. |
| **TEST-617** | Completing provisioning transitions to `PROTECTED` | Atomically commits `provisioned = true, onboardingComplete = true`. |
| **TEST-618** | Protected app requires PIN after provisioning | Evaluates target app to `RequirePin`. |
| **TEST-619** | `RECOVERY_REQUIRED` remains fail-closed for protected apps | Protected app blocked with `RecoveryRequired`. |
| **TEST-620** | Deleting UI flags cannot convert recovery to setup | Cleared prefs + DB recovery -> stays `RECOVERY_REQUIRED`. |
| **TEST-621** | Native exception in decision path fails closed | Error trips `LockDecision.DenyUnknown`. |
| **TEST-622** | Wave 1 - Status aggregation precedence | Recovery > SetupInProgress > Degraded > Protected. |
| **TEST-623** | Wave 2 - Decision engine rule precedence | Own > Recovery > Unknown > Lockout > Cooldown > Auth. |
| **TEST-624** | Wave 3 - Runtime session invalidation | Screen off/purge forces `RequirePin`. |
| **TEST-625** | Wave 4 - Tamper lockout overrides authorization | Tamper lockout blocks Settings. |
| **TEST-626** | Wave 5 - Valid recovery flow reconciliation | Supplying recovery credentials clears `RECOVERY_REQUIRED`. |

---

## 3. Critical Limitations of the Unit Test Suite

Despite 100% pass rates across JVM and Flutter tests, Agent 05 identified the following **blind spots**:

### 1. Mocked DAO & In-Memory SharedPreferences
`IncidentResolutionUnitTest.kt` uses `IncidentFakeDao` and `InMemorySharedPreferences`. It **does not**:
- Execute actual SQLite B-tree queries via Android SQLite driver.
- Verify multi-process concurrency between `LockKeeperAccessibilityService` and Flutter engine.
- Verify hardware AndroidKeyStore MasterKey encryption or Keystore corruption.

### 2. Lack of Android IPC / Framework Runtime
The JVM unit tests do not execute within Android's `AccessibilityService` lifecycle. They cannot verify:
- Whether `performGlobalAction(GLOBAL_ACTION_HOME)` triggers a secondary `AccessibilityEvent` on MIUI 14.
- Whether Xiaomi's `com.miui.home` fires `TYPE_WINDOW_CONTENT_CHANGED` alongside `TYPE_WINDOW_STATE_CHANGED`.
- Whether `OverlayManager` window attachment succeeds under `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`.

### 3. Release Artifact & R8 ProGuard Obfuscation
Unit tests run on un-minified JVM bytecode. They cannot verify whether:
- R8 strips reflection or Room DAO generated code in release builds.
- Native JNI bridges or Flutter platform channels survive release packaging.

---

## 4. Conclusion

**Automated unit tests validate the mathematical correctness of the state machine, but they CANNOT prove device stability.** Physical device execution on the Xiaomi Mi 10i and AOSP API 36 emulator is strictly required to sign off on this gate.
