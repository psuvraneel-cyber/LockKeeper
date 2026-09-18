# LOCKKEEPER — CRITICAL INCIDENT TEST MATRIX
## First-Run Onboarding & Device Admin Setup Safeguards (TEST-601 to TEST-626)

**Test Suite:** `com.lockkeeper.app.IncidentResolutionUnitTest` & `test/incident_resolution_test.dart`  
**Execution Environment:** JVM (Kotlin 2.0.21 / Room 2.6.1 / JUnit 4.13.2) & Flutter 3.29.0 / Dart 3.7.0  
**Verification Date:** September 18, 2026  
**Total Tests in Matrix:** 26  
**Status:** 26 PASSED (100% Pass Rate)  

---

| Test ID | Invariant / Requirement | Layer | Preconditions | Inputs & Actions | Expected Result | Status |
|---|---|---|---|---|---|---|
| **TEST-601** | Clean install initializes to `SETUP_IN_PROGRESS` | Room / Domain | Fresh DB (`securityProvisioned=false`, `onboardingComplete=false`, `recoveryRequired=false`) | `evaluateRecoveryCheck(isAdminActive=false, hasCreds=false)` | `isRecovery == false`, `overallStatus == "SETUP_IN_PROGRESS"` | **PASS** |
| **TEST-602** | Granting Device Admin preserves `SETUP_IN_PROGRESS` | Room / Domain | `securityProvisioned=false`, Device Admin enabled | `evaluateRecoveryCheck(isAdminActive=true, hasCreds=false)` | `isRecovery == false`, `overallStatus == "SETUP_IN_PROGRESS"` | **PASS** |
| **TEST-603** | Clean install + Admin + missing PIN does not trip recovery | Room / Domain | `securityProvisioned=false`, Admin active, Admin Pass configured | `evaluateRecoveryCheck(isAdminActive=true, hasPin=false, hasAdminPass=true)` | `isRecovery == false`, `overallStatus == "SETUP_IN_PROGRESS"` | **PASS** |
| **TEST-604** | Clean install + Admin + missing Admin Pass does not trip recovery | Room / Domain | `securityProvisioned=false`, Admin active, PIN configured | `evaluateRecoveryCheck(isAdminActive=true, hasPin=true, hasAdminPass=false)` | `isRecovery == false`, `overallStatus == "SETUP_IN_PROGRESS"` | **PASS** |
| **TEST-605** | Clean install + Admin + A11y operational remains setup mode | Room / Domain | `securityProvisioned=false`, Admin active, A11y active | `evaluateRecoveryCheck(isAdminActive=true, hasCreds=false)` | `isRecovery == false`, `overallStatus == "SETUP_IN_PROGRESS"` | **PASS** |
| **TEST-606** | Launcher foreground during setup returns `Allowed` | Domain Engine | Unprovisioned DB, `isSetupInProgress=true` | `engine.evaluate("com.miui.home", lockedApp=null)` | `LockDecision.Allowed(ALLOWED_SETUP_MODE)` | **PASS** |
| **TEST-607** | LockKeeper own app remains launchable during setup | Domain Engine | Unprovisioned DB, `isSetupInProgress=true` | `engine.evaluate("com.lockkeeper.app", lockedApp=null)` | `LockDecision.Allowed(ALLOWED_OWN_PACKAGE)` | **PASS** |
| **TEST-608** | Android Settings remains usable during setup | Domain Engine | Unprovisioned DB, `isSetupInProgress=true` | `engine.evaluate("com.android.settings", lockedApp=null)` | `LockDecision.Allowed(ALLOWED_SETUP_MODE)` | **PASS** |
| **TEST-609** | Launcher and System UI packages never sent Home | Accessibility Service | Service initialized | `isLauncherOrSystemUiPackage(pkg)` across MIUI Home, Launcher3, Nexus, SystemUI, 3rd party apps | Launchers and System UI return `true`; normal apps return `false` | **PASS** |
| **TEST-610** | Previously provisioned install with wiped credentials enters recovery | Domain / Keystore | `securityProvisioned=true`, `onboardingComplete=true`, credentials deleted | `evaluateRecoveryCheck(isAdminActive=true, hasCreds=false)` | `isRecovery == true`, `overallStatus == "RECOVERY_REQUIRED"` | **PASS** |
| **TEST-611** | Previously provisioned install with corrupted Room enters recovery | Room / Domain Engine | `securityProvisioned=true`, `recoveryRequired=true` | `engine.evaluate("com.example.bank", lockedApp)` | `LockDecision.RecoveryRequired(DENIED_RECOVERY_REQUIRED)` | **PASS** |
| **TEST-612** | Stale SharedPreferences cannot override native provisioning marker | Bridge / Room | SharedPreferences has `onboarding_complete=true`, Room has `securityProvisioned=false` | `evaluateRecoveryCheck(...)` reading Room DB | `isRecovery == false`, `overallStatus == "SETUP_IN_PROGRESS"` | **PASS** |
| **TEST-613** | Flutter method channel cannot demote `SECURITY_PROVISIONED` to setup | Platform Bridge | Device provisioned (`securityProvisioned=true`) | Client calls `setOnboardingComplete(false)` | `securityProvisioned` remains `true`; never demoted to `SETUP_IN_PROGRESS` | **PASS** |
| **TEST-614** | Process restart during setup maintains `SETUP_IN_PROGRESS` | Room / Lifecycle | Setup interrupted; process killed | New instance created reading Room DB | `securityProvisioned` remains `false`; status is `SETUP_IN_PROGRESS` | **PASS** |
| **TEST-615** | Accessibility Service restart during setup maintains `SETUP_IN_PROGRESS` | A11y / Lifecycle | Setup in progress; service reconnected | Foreground event evaluated | Target package evaluated as `LockDecision.Allowed(ALLOWED_SETUP_MODE)` | **PASS** |
| **TEST-616** | Device reboot during setup maintains `SETUP_IN_PROGRESS` | Room / System Boot | Device rebooted with incomplete setup | Repository initialized on boot | `isRecovery == false`, `overallStatus == "SETUP_IN_PROGRESS"` | **PASS** |
| **TEST-617** | Completing initial provisioning atomically transitions state | Room / DB | Initial setup completed | `dao.setProvisionedAndOnboardingComplete(true, true)` | `securityProvisioned` and `onboardingComplete` commit atomically; status is `PROTECTED` | **PASS** |
| **TEST-618** | Protected app enforcement operates normally post-provisioning | Domain Engine | `securityProvisioned=true`, `onboardingComplete=true` | `engine.evaluate("com.example.bank", lockedApp)` | `LockDecision.RequirePin("com.example.bank")` | **PASS** |
| **TEST-619** | `RECOVERY_REQUIRED` remains fail-closed for protected apps | Domain Engine | `securityHealthStatus="RECOVERY_REQUIRED"` | `engine.evaluate("com.example.bank", lockedApp)` | `LockDecision.RecoveryRequired(DENIED_RECOVERY_REQUIRED)` | **PASS** |
| **TEST-620** | Deleting UI flags cannot convert `RECOVERY_REQUIRED` to setup mode | Storage / Security | `securityProvisioned=true`, `recoveryRequired=true` | UI storage cleared (`prefs.edit().clear().apply()`) | Native status remains `RECOVERY_REQUIRED`; does not fall back to setup | **PASS** |
| **TEST-621** | Native exception in decision path fails closed | Domain Engine | Exception / Corrupted status | `engine.evaluate(..., securityHealthStatus="UNKNOWN")` | `LockDecision.DenyUnknown(DENIED_UNKNOWN_STATE)` | **PASS** |
| **TEST-622** | Wave 1 Authoritative status aggregation integrity preserved | Domain / Model | Multiple permission and setup states | `evaluateRecoveryCheck(...)` across state matrix | Precedence verified: `RECOVERY_REQUIRED` > `SETUP_IN_PROGRESS` > `DEGRADED` > `PROTECTED` | **PASS** |
| **TEST-623** | Wave 2 Decision precedence preserved | Domain Engine | Lockouts, sessions, and recovery configurations | `engine.evaluate(...)` across priority tiers | Own > Recovery > DenyUnknown > Lockout > RequirePin | **PASS** |
| **TEST-624** | Wave 3 Runtime session invalidation preserved across lifecycle | Runtime Session | Session granted for protected package | `engine.clearAllSessions()` invoked on screen off | Active session revoked; subsequent evaluation requires PIN | **PASS** |
| **TEST-625** | Wave 4 Tamper lockout overrides ordinary authorization | Anti-Tamper | Tamper lockout triggered by admin deactivation attempt | `engine.evaluate("com.android.settings", isTamperLocked=true)` | `LockDecision.Blocked("com.android.settings", DENIED_TAMPER_LOCKOUT)` | **PASS** |
| **TEST-626** | Wave 5 Valid recovery reconciliation restores credentials | Recovery Flow | System in recovery mode | User submits recovery password and creates new PIN | `isRecovery == false`, `overallStatus == "PROTECTED"`, protected apps require PIN | **PASS** |

---

## Conclusion
All 26 tests in the incident resolution test matrix passed with zero regressions against all established invariants across Waves 1 through 5.
