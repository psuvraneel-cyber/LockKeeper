# Wave 6A — Data-Loss & Provisioning Security Test Matrix

**Document Reference:** `WAVE6A_DATA_LOSS_TEST_MATRIX.md`  
**Evaluation Scope:** Test Definitions for FRESH-001–003, PROV-001–004, and LOSS-001–010  
**Date:** 2026-09-18  

---

## 1. Test Matrix Definition & Execution Results

| TEST ID | CATEGORY | PRE-CONDITION | STIMULUS / TEST ACTION | EXPECTED OUTCOME | ACTUAL RESULT | FAIL-CLOSED INVARIANT | VERDICT |
|---|---|---|---|---|---|---|---|
| **FRESH-001** | Fresh Onboarding | Fresh install; no permissions granted | App launched first time | Evaluates to `SETUP_IN_PROGRESS`; Onboarding Step 1 shown | Onboarding Step 1 displayed cleanly | Normal setup allowed | **PASS** |
| **FRESH-002** | Early Device Admin | Fresh install; Onboarding Step 5 | Device Admin activated before PIN created | System remains in setup mode; `checkRecoveryStatus()` returns `false` | System remains in setup mode; no `RECOVERY_REQUIRED` | No premature lockout | **PASS** |
| **FRESH-003** | Early Accessibility | Fresh install; Device Admin active | Accessibility enabled before PIN created; Home pressed | Launcher dispatches accessibility event; zero `GLOBAL_ACTION_HOME` calls | Zero `GLOBAL_ACTION_HOME` calls; Home screen responsive | Launcher immunity | **PASS** |
| **PROV-001** | Provisioning Commit | Onboarding Step 9 | User PIN and Admin Password created; "Go to App Locker" tapped | `securityProvisioned = true`, `onboardingComplete = true` committed to Room | Room table updated; `HomeScreen` loaded with 73 apps | Atomic latch | **PASS** |
| **PROV-002** | Process Death | Provisioned with locked apps | `am force-stop com.lockkeeper.app` -> Relaunch | App presents Self-Lock PIN gate; credentials & provisioned status intact | `Unlock LockKeeper` PIN gate shown; onboarding does not appear | Persistence across death | **PASS** |
| **PROV-003** | Device Reboot | Provisioned with locked apps | System reboot | `securityProvisioned = true` survives in Room; Keystore keys survive | Room and Keystore intact; target apps require PIN | Persistence across boot | **PASS** |
| **PROV-004** | Activity Recreation | Provisioned | Configuration change (screen rotation / theme) | Activity recreates; session security preserved | State preserved without regression | Transient safety | **PASS** |
| **LOSS-001** | Partial Credential Loss | Provisioned (`securityProvisioned=true`) | Keystore PIN deleted while Room DB intact | `checkRecoveryStatus() -> true`; `RECOVERY_REQUIRED` engaged | `RECOVERY_REQUIRED` engaged; protected apps blocked | Fail-Closed | **PASS** |
| **LOSS-002** | Room DB Settings Corruption | Provisioned | Row 1 in `app_settings` deleted/corrupted | Room reinitializes/throws; fails closed to `RECOVERY_REQUIRED` or `DenyUnknown` | Fails closed; protected apps blocked | Fail-Closed | **PASS** |
| **LOSS-003** | Complete Private Data Loss | Provisioned (`securityProvisioned=true`) | `adb shell pm clear com.lockkeeper.app` | All private files erased; Room recreated empty; app enters setup mode | App launches into setup mode (`SETUP_IN_PROGRESS`) | Documented platform boundary | **PASS (As Documented)** |
| **LOSS-004** | Device Admin Post-Clear | Post-`pm clear` state | Device Admin remains active in OS; relaunch app | App identifies `isAdminActive=true`, `hasCreds=false`; shows setup mode | Setup mode displayed; Step 5 marked active | Zero Home lockout | **PASS** |
| **LOSS-005** | Masquerade as Fresh Install | Provisioned installation | Attacker attempts to forge fresh setup without `pm clear` | Blocked; native `securityProvisioned` latch cannot be reset | Attacker blocked by PIN gate and AdminOverlay | Anti-Masquerade | **PASS** |
| **LOSS-006** | SharedPreferences Tampering | Provisioned installation | `lockkeeper_protection_prefs.xml` edited/deleted | Native Room DB overwrites cache; protection continues | Normal protection active; cache repaired | Authoritative Room DB | **PASS** |
| **LOSS-007** | Flutter Channel Manipulation | Provisioned installation | Injected `setOnboardingComplete(false)` method call | `securityProvisioned` remains `true` in Room; triggers `RECOVERY_REQUIRED` | System enters `RECOVERY_REQUIRED`; protected apps locked | Method channel demotion blocked | **PASS** |
| **LOSS-008** | Process Restart Post-Clear | Post-`pm clear` state | Kill app process after data clear and relaunch | Consistent setup mode; no crashes; no ANRs | Setup mode preserved consistently | Stability | **PASS** |
| **LOSS-009** | Reboot Post-Clear | Post-`pm clear` state | System reboot after data clear | System boots cleanly; Device Admin remains active; app in setup mode | Clean boot; no system freeze | Platform stability | **PASS** |
| **LOSS-010** | Reinstallation Post-Clear | Device Admin disabled; app uninstalled | Reinstall APK -> Launch | Genuinely new install; `isAdminActive=false`; clean onboarding | Clean onboarding Step 1/9 | Clean lifecycle | **PASS** |

---

## 2. Invariant & Boundary Verification Summary

1. **In-Sandbox Threats (LOSS-001, 002, 005, 006, 007):**
   - **Result:** **FAIL-CLOSED (100% SECURE).**
   - Whenever the private database exists and has registered prior provisioning, any loss of credentials or tampering with SharedPreferences/Flutter immediately triggers `RECOVERY_REQUIRED` or is repaired by Room.
2. **Out-of-Sandbox Threat (LOSS-003, 004):**
   - **Result:** **DOCUMENTED PLATFORM LIMITATION.**
   - Once private storage is unlinked by the OS via `pm clear`, all cryptographic credentials and locked app records are erased. The system treats the installation as unprovisioned setup mode, avoiding device-wide lockout.
