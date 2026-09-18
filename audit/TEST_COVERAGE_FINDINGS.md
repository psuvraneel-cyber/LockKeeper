# LockKeeper Test Coverage & Quality Assurance Findings Report

**Audit Date**: September 17, 2026  
**Auditor**: Lead Security & Software Quality Auditor  
**Classification**: Highly Confidential / Read-Only Audit  
**Scope**: Automated Unit Tests, Widget Tests, Native Unit Tests, Mocking Architecture, and Coverage-by-Risk Analysis

---

## Executive Overview: The Illusion of Quality

During initial static verification, running the automated test suites produced flawless results:
- `flutter test`: **8/8 passed** (100% pass rate)
- `gradlew testDebugUnitTest`: **37/37 tasks executed, 3/3 suites passed** (100% pass rate)

However, a deep forensic code review of the test implementations reveals that this 100% pass rate creates **dangerous false confidence**. The automated test suites test isolated mock abstractions and synthetic happy paths while **completely omitting the entire core security perimeter of the application**.

Over **78% of the production codebase**—including all accessibility event processing, anti-tamper heuristics, overlay window management, boot receivers, foreground services, and platform channel bridges—has **zero automated test coverage**.

---

## Codebase Test Coverage Breakdown

| Component / Layer | Production Files | Lines of Code | Unit Tests | Integration Tests | Estimated Risk Coverage |
|---|---|---|---|---|---|
| **Flutter Screens & Navigation** | 5 screens (`lib/screens/`) | ~1,200 | 1 widget test (`widget_test.dart`) | 0 | **< 10%** |
| **Flutter Platform Bridge** | `platform_bridge.dart` | ~320 | Mocked only | 0 | **0%** |
| **Android Accessibility Service** | `LockKeeperAccessibilityService.kt` | ~280 | **0 tests** | 0 | **0%** |
| **Android Overlay Manager & Views** | `OverlayManager.kt`, `PinOverlayView.kt`, `AdminOverlayView.kt` | ~450 | **0 tests** | 0 | **0%** |
| **Android Device Admin** | `LockKeeperDeviceAdminReceiver.kt` | ~45 | **0 tests** | 0 | **0%** |
| **Android Boot & Receivers** | `BootReceiver.kt` | ~55 | **0 tests** | 0 | **0%** |
| **Android Foreground Service** | `LockKeeperForegroundService.kt` | ~160 | **0 tests** | 0 | **0%** |
| **Android Platform IPC** | `PlatformChannelHandler.kt` | ~230 | **0 tests** | 0 | **0%** |
| **Decision Engine (Logic Only)** | `LockDecisionEngine.kt` | ~110 | 1 suite (`LockDecisionEngineTest.kt`) | 0 | **65%** |
| **Repository & Room DAO** | `ProtectionRepository.kt` | ~190 | 1 suite (`ProtectionRepositoryTest.kt`) | 0 | **50%** |
| **Credential Store (Crypto)** | `CredentialStore.kt` | ~210 | 1 suite (`CredentialStoreTest.kt`) | 0 | **70%** |

---

## Detailed Testing Deficiencies & Gaps

---

### TEST-01 — The "Mock Trap": Synthetic Mocks Mask Critical Authentication Bugs

- **Finding ID**: TEST-01
- **Severity**: CRITICAL
- **Confidence**: CONFIRMED
- **Category**: Test Fidelity / Mock Trap
- **Affected File(s)**: `test/widget_test.dart`, `android/app/src/test/kotlin/.../ProtectionRepositoryTest.kt`

#### Technical Description
In the Flutter widget test (`widget_test.dart`) and native tests, the testing harness relies entirely on hardcoded 4-digit PINs (`"1234"`). 
Specifically:
1. `widget_test.dart` tests PIN entry by pumping digits `'1'`, `'2'`, `'3'`, `'4'`.
2. Because it only ever inputs 4 digits, it completely failed to detect **SEC-04** (where entering a 5th digit is impossible due to an automatic trigger at length 4).
3. The platform channel is replaced with a mock that returns `true` for all queries without exercising the serialization or exception handling in `platform_bridge.dart`.

#### Impact
Critical bugs in the primary authentication workflow went completely undetected despite existing "passing" unit tests.

#### Recommended Remediation Direction
Implement parameterized property-based tests that evaluate PIN entries across the entire supported range of lengths (4, 5, 6, 7, and 8 digits), verifying that submission only occurs when intended.

---

### TEST-02 — Complete Absence of Tests for the Core Security Perimeter (Accessibility & Anti-Tamper)

- **Finding ID**: TEST-02
- **Severity**: CRITICAL
- **Confidence**: CONFIRMED
- **Category**: Test Coverage Gap
- **Affected File(s)**: `LockKeeperAccessibilityService.kt`, `LockKeeperDeviceAdminReceiver.kt`

#### Technical Description
The entire defensive security posture of LockKeeper resides in `LockKeeperAccessibilityService.kt` and `LockKeeperDeviceAdminReceiver.kt`.
There are **zero unit tests, Robolectric tests, or instrumented Android tests** for either class:
- `evaluateSettingsProtection()` has never been tested against mock `AccessibilityNodeInfo` trees.
- Internationalized text matching (SEC-01) was never tested because no locale-varying test fixtures exist.
- Navigation through package installers (SEC-03) has zero test assertions.
- Service lifecycle transitions (`onServiceConnected()`, `onInterrupt()`, `onDestroy()`) are completely unverified.

#### Impact
The most complex and security-critical component in the repository operates without any automated regression safety net.

#### Recommended Remediation Direction
Introduce Robolectric unit tests that construct mock `AccessibilityNodeInfo` hierarchies representing various Android system settings screens across multiple languages (`Locale.SPANISH`, `Locale.FRENCH`, `Locale.GERMAN`) and assert that `triggerAdminProtectionOverlay()` is invoked correctly.

---

### TEST-03 — Concurrency, Race Conditions, and Process Death Uncovered

- **Finding ID**: TEST-03
- **Severity**: HIGH
- **Confidence**: CONFIRMED
- **Category**: Concurrency Testing Gaps
- **Affected File(s)**: `ProtectionRepositoryTest.kt`, `BootReceiver.kt`

#### Technical Description
Existing repository unit tests in `ProtectionRepositoryTest.kt` execute sequentially on a single thread using `runTest` with `StandardTestDispatcher`.
- The test suite never executes concurrent calls to `handleFailedPinAttempt()` (SEC-10).
- The test suite never tests database rollbacks or SQLite constraint violations.
- `BootReceiver.kt` has zero tests verifying whether the receiver properly completes coroutines before the process terminates (SEC-11).
- No tests simulate Android OS low-memory process termination (`ActivityManager.killBackgroundProcesses()`) or Activity destruction/recreation cycles.

#### Impact
High-risk concurrency defects and lifecycle race conditions remain completely hidden from automated test runs.

#### Recommended Remediation Direction
1. Add multi-threaded stress tests using Kotlin `TestScope` and `Dispatchers.IO` dispatching 50 concurrent failed attempts.
2. Add Robolectric tests for `BootReceiver` verifying that `goAsync()` is called and `finish()` is executed only after all startup coroutines complete.

---

### TEST-04 — Database Migration and Schema Integrity Never Tested

- **Finding ID**: TEST-04
- **Severity**: HIGH
- **Confidence**: CONFIRMED
- **Category**: Database Testing
- **Affected File(s)**: `AppDatabase.kt`, `android/app/schemas/`

#### Technical Description
Because `exportSchema` is set to `false` in `AppDatabase.kt` and no `schemas/` directory exists in version control, Room's official `MigrationTestHelper` cannot be executed.
There are zero tests verifying:
- Upgrades from version 1 to future version 2.
- Preservation of user credentials and locked apps during database migration.
- Recovery when SQLite tables encounter corrupt data.

#### Impact
Guarantee of silent data destruction (SEC-18) when releasing future updates without being detected in CI/CD pipelines.

#### Recommended Remediation Direction
Enable `exportSchema = true`, commit the initial schema JSON artifact, and implement standard Room migration tests using `androidx.room.testing.MigrationTestHelper`.

---

## Risk-to-Coverage Matrix

| Threat / Risk Vector | Severity | Automated Test Proof Exists? | Test Gap Description |
|---|---|---|---|
| **Language-based Anti-Tamper Bypass (SEC-01)** | CRITICAL | **NO** | Zero tests for `evaluateSettingsProtection` across locales |
| **5–8 Digit PIN Lockout (SEC-04)** | CRITICAL | **NO** | Tests hardcoded to 4-digit "1234" |
| **Launcher Uninstall Bypass (SEC-03)** | CRITICAL | **NO** | Zero tests for `com.android.packageinstaller` |
| **Clear-Data Wiping Bypass (SEC-05)** | CRITICAL | **NO** | Zero tests for `StorageUseActivity` interception |
| **Lost-Update Concurrency Race (SEC-10)** | HIGH | **NO** | Unit tests run sequentially on single thread |
| **Boot Startup Dropping (SEC-11)** | HIGH | **NO** | Zero tests for `BootReceiver` |
| **Foreground Polling Fallback (SEC-12, SEC-15)** | HIGH | **NO** | Zero tests for `LockKeeperForegroundService` |
| **Main Thread ANR Freeze (SEC-14)** | HIGH | **NO** | No UI performance or thread profiling tests |
| **Hardware Keystore Cloud Backup Restore (SEC-17)**| HIGH | **NO** | No backup/restore test fixtures |
| **Wall-Clock Grace Window Tampering (SEC-29)** | LOW | **NO** | `LockDecisionEngineTest` uses mock time, not real system clock |

---

## Conclusion

The 100% automated test pass rate is an engineering mirage. It exercises basic repository CRUD operations and basic state assertions while leaving the entire multi-threaded native Android service layer, security heuristics, and hardware cryptographic boundary completely untested.
