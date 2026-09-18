# LockKeeper Self-Protection Critical Remediation Test Matrix

**Document Status:** Complete & Verified  
**Date:** September 17, 2026  
**Execution Environment:** Android JVM Unit Tests + Flutter Test Framework  
**JVM Test Suite Count:** 78 passed (0 failures)  
**Flutter Test Suite Count:** 8 passed (0 failures)  
**Total Automated Tests:** 86 passed  

---

## 1. Automated Unit Test Matrix

| Test ID | Test Suite | Test Case Name | Objective / Invariant Proved | Finding Remediated | Status |
|---|---|---|---|---|---|
| **TM-01** | `SecurityInvariantsTest` | `invariant 1 - missing AppSettings row cannot disable rate limiting` | Confirms that when row 1 is absent from Room DB, `getOrInitializeSettings()` auto-seeds row 1 and enforces rate-limiting without failing open | **SEC-01**, **MED-06** | **PASS** |
| **TM-02** | `SecurityInvariantsTest` | `invariant 2 - initialization races cannot disable rate limiting` | Confirms concurrent initialization calls on uninitialized DB safely lock out on attempt 5 | **SEC-01**, **HIGH-01** | **PASS** |
| **TM-03** | `SecurityInvariantsTest` | `invariant 3 - two simultaneous password attempts cannot bypass attempt limits` | Proves Mutex serialization prevents 2 simultaneous attempts from bypassing attempt limit | **HIGH-01** | **PASS** |
| **TM-04** | `SecurityInvariantsTest` | `invariant 4 - successful authentication cannot extend grace indefinitely` | Proves monotonic 30s grace window strictly expires at 30,000ms | **SEC-05** | **PASS** |
| **TM-05** | `SecurityInvariantsTest` | `invariant 5 - reboot cannot silently erase an active lockout` | Proves persistent `adminLockoutUntil` survives process death and simulated reboot | **SEC-05**, **MED-01** | **PASS** |
| **TM-06** | `SecurityInvariantsTest` | `invariant 6 - service disconnection is distinct from permission revocation` | Proves separation of `isAccessibilityEnabled` (system setting) from `isAccessibilityConnected` (lifecycle) | **HIGH-03**, **HIGH-05** | **PASS** |
| **TM-07** | `SecurityInvariantsTest` | `invariant 7 - device admin listing does not trigger LockKeeper's password overlay` | Proves general Device Admin list containing LockKeeper is ignored | **SEC-02**, **HIGH-04** | **PASS** |
| **TM-08** | `SecurityInvariantsTest` | `invariant 8 - unrelated administrator management does not trigger LockKeeper protection` | Proves deactivating 3rd party admin (e.g. Find My Device) does not trigger LockKeeper overlay | **SEC-02** | **PASS** |
| **TM-09** | `SecurityInvariantsTest` | `invariant 9 - general accessibility settings remains accessible` | Proves general Accessibility settings list does not trigger false positive freeze | **SEC-03**, **Baseline MIUI Fix** | **PASS** |
| **TM-10** | `SecurityInvariantsTest` | `invariant 10 - accessibility content changes do not accidentally dismiss an active valid tamper overlay` | Proves session bounds overlay to target package/activity; content changes do not terminate session | **SEC-04**, **HIGH-02** | **PASS** |
| **TM-11** | `SecurityInvariantsTest` | `invariant 11 - IME appearance does not destroy the tamper overlay` | Proves input method packages (Gboard, Honeyboard, SwiftKey) are immune to window-switch dismissal | **SEC-04** | **PASS** |
| **TM-12** | `SecurityInvariantsTest` | `invariant 12 - service death cannot leave an orphan overlay` | Proves `onDestroy()` and `onInterrupt()` contract invokes `dismissAll()` to clean WindowManager | **HIGH-07** | **PASS** |
| **TM-13** | `SecurityInvariantsTest` | `invariant 13 - unrelated apps cannot trigger LockKeeper password gate` | Proves non-settings/non-installer app packages (e.g. Calculator) are never intercepted | **SEC-03** | **PASS** |
| **TM-14** | `SecurityInvariantsTest` | `invariant 14 - missing local security state cannot silently create fresh setup` | Proves when DPM is active but credentials/DB are missing, `recoveryRequired = true` is triggered | **SEC-06** | **PASS** |
| **TM-15** | `SecurityInvariantsTest` | `invariant 15 - UI never claims stronger security than native state provides` | Proves `isAccessibilityOperational` is false when service is disconnected even if enabled | **HIGH-05** | **PASS** |
| **TM-16** | `DatabaseMigrationTest` | `migration 3 to 4 version numbers are correct` | Validates Room migration startVersion=3, endVersion=4 | **SEC-06** | **PASS** |
| **TM-17** | `DatabaseMigrationTest` | `migration 3 to 4 adds recoveryRequired column with default 0 without data loss` | Validates SQL statement `ALTER TABLE app_settings ADD COLUMN recoveryRequired INTEGER NOT NULL DEFAULT 0` | **SEC-06** | **PASS** |
| **TM-18** | `DatabaseMigrationTest` | `app settings entity v4 defaults recoveryRequired to false preserving existing settings` | Validates entity default value compatibility with previous schemas | **SEC-06** | **PASS** |
| **TM-19** | `TamperAuthorizationControllerTest` | `valid password grants grace window and resets failed attempts` | Proves correct password resets failure counter to 0 and grants 30s grace | **SEC-01** | **PASS** |
| **TM-20** | `TamperAuthorizationControllerTest` | `invalid password increments failure count and calculates remaining attempts` | Proves step-by-step failure counter increment and remaining attempts computation | **SEC-01** | **PASS** |
| **TM-21** | `TamperAuthorizationControllerTest` | `5 failed attempts triggers 300 second lockout` | Proves 5th failure triggers 300s persistent and monotonic lockout | **SEC-05** | **PASS** |
| **TM-22** | `TamperAuthorizationControllerTest` | `locked out state rejects attempts even with correct password` | Proves correct password cannot authenticate while locked out | **SEC-05** | **PASS** |
| **TM-23** | `TamperAuthorizationControllerTest` | `forward wall clock manipulation does NOT bypass lockout during active boot` | Proves monotonic deadline `adminLockoutUntilElapsed` survives +600s wall-clock jump | **SEC-05** | **PASS** |
| **TM-24** | `TamperAuthorizationControllerTest` | `missing row 1 initializes safely and rate limiting remains active` | Proves empty DB row 1 auto-seeds on first failure and locks out on attempt 5 | **SEC-01** | **PASS** |
| **TM-25** | `TamperAuthorizationControllerTest` | `grace window expires after 30 seconds of monotonic time` | Proves monotonic time elapsed expires grace period | **SEC-05** | **PASS** |
| **TM-26** | `TamperAuthorizationControllerTest` | `two simultaneous wrong passwords decrement attempts sequentially without loss` | Proves coroutine mutex serializes 2 simultaneous wrong password attempts | **HIGH-01** | **PASS** |
| **TM-27** | `TamperAuthorizationControllerTest` | `five simultaneous wrong passwords lock out without race condition` | Proves 5 simultaneous wrong passwords produce exactly 4 failures and 1 lockout | **HIGH-01** | **PASS** |
| **TM-28** | `TamperAuthorizationControllerTest` | `success and failure race - successful password grants session and resets failure count cleanly` | Proves simultaneous success and failure race condition is safely ordered | **HIGH-01** | **PASS** |
| **TM-29** | `TamperAuthorizationControllerTest` | `lockout plus success race - once locked out, simultaneous correct password is still rejected` | Proves simultaneous correct attempt at lockout threshold cannot bypass lockout | **HIGH-01** | **PASS** |
| **TM-30** | `TamperAuthorizationControllerTest` | `process restart preserves persistent lockout state and prevents bypass` | Proves new controller instance enforces persistent lockout timestamp | **SEC-05**, **MED-01** | **PASS** |
| **TM-31** | `TamperAuthorizationControllerTest` | `reboot simulation clears in-memory monotonic deadline but enforces persistent lockout timestamp` | Proves simulated reboot with clock reset preserves persistent lockout duration | **SEC-05**, **MED-01** | **PASS** |
| **TM-32** | `TamperAuthorizationControllerTest` | `repeated authorization requests during lockout consistently return LockedOut` | Proves 10 rapid repeated attempts during lockout consistently return LockedOut | **HIGH-01** | **PASS** |
| **TM-33** | `TamperDetectionEngineTest` | `settings app info in English triggers uninstall tamper event` | Validates AOSP App Info uninstall detection | **SEC-03** | **PASS** |
| **TM-34** | `TamperDetectionEngineTest` | `settings app info in Spanish triggers uninstall tamper event without English keywords` | Validates multilingual fallback on SpaActivity in Spanish | **SEC-03**, **MED-02** | **PASS** |
| **TM-35** | `TamperDetectionEngineTest` | `settings storage clear data triggers clear data tamper event` | Validates StorageUseActivity clear data detection | **SEC-03** | **PASS** |
| **TM-36** | `TamperDetectionEngineTest` | `package installer uninstalling LockKeeper triggers package installer tamper event` | Validates UninstallerActivity targeted at LockKeeper | **SEC-03** | **PASS** |
| **TM-37** | `TamperDetectionEngineTest` | `package installer uninstalling unrelated app is ignored` | Validates UninstallerActivity targeted at other apps is ignored | **SEC-03** | **PASS** |
| **TM-38** | `TamperDetectionEngineTest` | `general accessibility settings listing is ignored to prevent black screen freeze` | Validates MIUI/AOSP downloaded apps list immunity | **Baseline MIUI Fix** | **PASS** |
| **TM-39** | `TamperDetectionEngineTest` | `device admin activation during onboarding is allowed when admin is not yet active` | Validates DeviceAdminAdd activation allowed during onboarding | **SEC-02** | **PASS** |
| **TM-40** | `TamperDetectionEngineTest` | `device admin deactivation attempt is blocked when admin is active` | Validates DeviceAdminAdd deactivation blocked when DPM active | **SEC-02** | **PASS** |
| **TM-41** | `TamperDetectionEngineTest` | `settings app info with only force stop triggers force stop tamper event` | Validates Force Stop button detection | **SEC-03** | **PASS** |
| **TM-42** | `TamperDetectionEngineTest` | `settings app info with disable button triggers disable app tamper event` | Validates Disable button detection | **SEC-03** | **PASS** |
| **TM-43** | `TamperDetectionEngineTest` | `individual accessibility service details screen for LockKeeper triggers accessibility disable event` | Validates ToggleAccessibilityServicePreferenceFragment detection | **SEC-03** | **PASS** |
| **TM-44** | `TamperDetectionEngineTest` | `unrelated settings screen like wifi or display settings is ignored` | Validates NetworkDashboardActivity immunity | **SEC-03** | **PASS** |
| **TM-45** | `TamperDetectionEngineTest` | `rapid burst of identical events produces consistent tamper events without state corruption` | Validates engine statelessness across 20 rapid callbacks | **SEC-03** | **PASS** |
| **TM-46** | `TamperDetectionEngineTest` | `general device admin settings list showing LockKeeper among admins does NOT trigger tamper` | Validates DeviceAdminSettings general list immunity | **SEC-02**, **HIGH-04** | **PASS** |
| **TM-47** | `TamperDetectionEngineTest` | `another device admin deactivation screen does NOT trigger LockKeeper tamper` | Validates other admin deactivation screens are ignored | **SEC-02**, **HIGH-04** | **PASS** |
| **TM-48** | `TamperDetectionEngineTest` | `LockKeeper deactivation screen specifically triggers DISABLE_DEVICE_ADMIN` | Validates LockKeeper-targeted deactivation screen trigger | **SEC-02**, **HIGH-04** | **PASS** |
| **TM-49** | `TamperDetectionEngineTest` | `destructive action outside relevant subtree does NOT trigger tamper` | Validates actions on WhatsApp are ignored even with destructive buttons | **SEC-03** | **PASS** |
| **TM-50** | `TamperDetectionEngineTest` | `deep node tree at depth 2 is detected` | Validates BFS traversal at depth 2 | **SEC-03**, **HIGH-08** | **PASS** |
| **TM-51** | `TamperDetectionEngineTest` | `deep node tree at depth 6 is detected` | Validates BFS traversal at depth 6 | **SEC-03**, **HIGH-08** | **PASS** |
| **TM-52** | `TamperDetectionEngineTest` | `deep node tree at depth 10 is detected` | Validates BFS traversal at depth 10 | **SEC-03**, **HIGH-08** | **PASS** |
| **TM-53** | `TamperDetectionEngineTest` | `deep node tree at depth 12 is detected` | Validates BFS traversal at depth 12 (max depth bound) | **SEC-03**, **HIGH-08** | **PASS** |
| **TM-54** | `TamperDetectionEngineTest` | `missing resource IDs fallback to text matching in German` | Validates multilingual keyword fallback (German) | **SEC-03**, **MED-02** | **PASS** |
| **TM-55** | `TamperDetectionEngineTest` | `dynamic content change with newly added destructive button is caught` | Validates TYPE_WINDOW_CONTENT_CHANGED async button detection | **SEC-03** | **PASS** |
| **TM-56** | `CredentialStoreTest` | `PIN setup and verification succeeds for valid 4, 5, 6, 7, and 8 digit PINs` | Proves PBKDF2/Keystore handles 4-8 digit PINs | **Step 12 Req** | **PASS** |
| **TM-57** | `CredentialStoreTest` | `invalid PIN rejected during setup` | Proves format validation for PIN | **Step 12 Req** | **PASS** |
| **TM-58** | `CredentialStoreTest` | `admin password setup and verification works` | Proves admin password PBKDF2 verification | **Step 12 Req** | **PASS** |
| **TM-59** | `CredentialStoreTest` | `PIN and Admin credentials are completely separate` | Proves cryptographic separation of PIN and Admin credentials | **Step 12 Req** | **PASS** |
| **TM-60** | `CredentialStoreTest` | `no plaintext credentials stored in preferences` | Proves salt + PBKDF2 hash stored, never plaintext | **Step 12 Req** | **PASS** |
| **TM-61..68** | `LockDecisionEngineTest` | 8 domain decision tests | Validates app locking, cooldown, strict lock decisions | Domain Baseline | **PASS** |
| **TM-69..78** | `SelfLockDomainTest` | 10 self-lock domain tests | Validates self-lock timeouts, grace periods, session invalidation | Domain Baseline | **PASS** |

---

## 2. Test Execution Summary

```
========================================================================
GRADLE JVM UNIT TESTS (cd android; .\gradlew.bat testDebugUnitTest)
========================================================================
TEST-com.lockkeeper.app.CredentialStoreTest.xml               :  5 tests, 0 failures
TEST-com.lockkeeper.app.DatabaseMigrationTest.xml             :  3 tests, 0 failures
TEST-com.lockkeeper.app.LockDecisionEngineTest.xml            :  8 tests, 0 failures
TEST-com.lockkeeper.app.SecurityInvariantsTest.xml            : 15 tests, 0 failures
TEST-com.lockkeeper.app.SelfLockDomainTest.xml                : 10 tests, 0 failures
TEST-com.lockkeeper.app.TamperAuthorizationControllerTest.xml : 14 tests, 0 failures
TEST-com.lockkeeper.app.TamperDetectionEngineTest.xml         : 23 tests, 0 failures
------------------------------------------------------------------------
TOTAL JVM UNIT TESTS: 78 tests, 0 failures (100% pass)

========================================================================
FLUTTER TEST SUITE (flutter test)
========================================================================
C:/AppLocker/test/self_lock_test.dart                         :  7 tests, 0 failures
C:/AppLocker/test/widget_test.dart                            :  1 test,  0 failures
------------------------------------------------------------------------
TOTAL FLUTTER TESTS: 8 tests, 0 failures (100% pass)

========================================================================
STATIC CODE ANALYSIS (flutter analyze)
========================================================================
No issues found! (ran in 2.3s)
```
