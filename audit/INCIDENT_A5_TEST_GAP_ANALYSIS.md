# LockKeeper Critical Incident Response — Agent A5 Test Gap Analysis
**Incident ID:** INCIDENT-2026-09-18-LOCKOUT  
**Component:** Test Suite & Quality Gate Forensic Audit  
**Investigator:** Agent A5 (Test & Regression Specialist)  
**Date:** 2026-09-18  

---

## 1. Executive Summary

Prior to this incident, LockKeeper reported:
- **195 / 195 automated tests passing** (174 JVM Unit Tests, 21 Flutter Widget & Integration Tests).
- Waves 1 through 5 reported 100% compliance with security invariants.
- Wave 4 reported real-device validation on the Xiaomi Mi 10i.
- Wave 5 reported full end-to-end journey validation across 45 product journeys.

**The Fatal Question:**  
*Why did 195 passing tests fail to detect that granting Device Administrator during first-run onboarding bricks the physical device into an infinite Home-screen loop?*

---

## 2. Review of Existing Test Suites (Waves 1–5)

### Test Suite Inventory:
1. `CredentialStoreTest.kt` (JVM): Keystore encryption, PBKDF2 hashing, AES-GCM payloads.
2. `DatabaseMigrationTest.kt` (JVM): Room migration 3 to 4 (`recoveryRequired` column default 0).
3. `LockDecisionEngineTest.kt` (JVM): Core lock evaluation rules, sessions, pin lockout, cooldowns.
4. `TamperDetectionEngineTest.kt` (JVM): Node inspection, keyword matching, accessibility tree traversal.
5. `TamperAuthorizationControllerTest.kt` (JVM): Admin password verification, lockout backoff, grace windows.
6. `SelfLockDomainTest.kt` (JVM): Self-lock session timeout, background invalidation.
7. `SecurityInvariantsTest.kt` (JVM - Wave 1): Unified state checks, fail-closed assertions.
8. `ProtectionEnforcementTest.kt` (JVM - Wave 2): Overlay rendering decisions, cooldown enforcement.
9. `RuntimeResilienceTest.kt` (JVM - Wave 3): Anti-tamper resilience, process kills, screen off.
10. `SecurityStateArchitectureTest.kt` (JVM - Wave 4): Platform boundaries, degraded states.
11. `ProductionSecurityBoundaryTest.kt` (JVM - Wave 5): End-to-end recovery reconciliation.
12. `security_state_test.dart` (Flutter): Protection status model, degraded banners.
13. `runtime_resilience_test.dart` (Flutter): Self-lock gate, backgrounding.
14. `enforcement_integrity_test.dart` (Flutter): UI lock toggles, detail screen.

---

## 3. Why Existing Tests Failed to Catch the Defect

### Blind Spot 1: Synthetic Binary Mocks (Fully Configured vs Post-Tamper Wiped)
Across all 174 JVM tests, test fixtures only ever created **two static states**:
- **State Alpha (Fully Configured):**
  - `onboardingComplete = true`
  - `hasPin = true`
  - `hasAdminPassword = true`
  - `isAdminActive = true`
- **State Beta (Post-Tamper Attacker Wipe):**
  - `onboardingComplete = false`
  - `hasPin = false`
  - `hasAdminPassword = false`
  - `isAdminActive = true`
  - Tested in `ProductionSecurityBoundaryTest.kt:121` (`Scenario E and F`):
    ```kotlin
    val isRecoveryRequired = isAdminActive && (!hasCreds || settings?.onboardingComplete != true)
    assertTrue("Must strictly flag recovery required", isRecoveryRequired)
    ```

**The Test Flaw:**  
The unit test actually **asserted the bug as desired behavior**! It assumed that `isAdminActive && (!hasCreds || !onboardingComplete)` could *only* occur if an attacker wiped data. No unit test ever evaluated what happens when a *legitimate user* is halfway through onboarding.

### Blind Spot 2: Absence of Transitional Setup Flow Testing
No JVM or Flutter test ever simulated the **step-by-step onboarding lifecycle**:
- Fresh install -> Step 1 -> Step 2 -> Step 3 (A11y enabled) -> Step 4 (Admin granted) -> intermediate state check.
- Because `onboarding_screen_test.dart` did not test native permission callbacks, the Flutter test suite never observed `onboarding_screen.dart:62` triggering `Navigator.pushReplacement(HomeScreen)`.

### Blind Spot 3: Isolation of Accessibility Service from Real Window Dispatch
`LockKeeperAccessibilityService` was never tested with a live or simulated Android `WindowManager`.
- Tests never simulated what happens when `LockKeeperAccessibilityService.onAccessibilityEvent()` calls `performGlobalAction(GLOBAL_ACTION_HOME)`.
- Specifically, tests never checked what `evaluatePackage()` returns when `packageName` is the **launcher itself** (`com.miui.home` or `com.android.launcher3`).
- In synthetic unit tests, `evaluatePackage()` was always invoked with `"com.target.app"` or `"com.critical.app"`, never `"com.miui.home"`.

### Blind Spot 4: Wave 4/5 Real-Device Testing Flaw
Why didn't Wave 4 on the Xiaomi Mi 10i catch it?
- In Wave 4 and Wave 5 testing, tests were executed via ADB on an already configured app, OR the QA script ran:
  `adb shell am start -n com.lockkeeper.app/.MainActivity` followed by setting PIN and password via automated commands or Flutter driver before activating Device Admin!
- When Device Admin was granted *after* credentials were set, `hasCreds == true` was already satisfied, so `checkRecoveryStatus()` never tripped!
- The physical device was never tested with the **exact natural human sequence** of a first-time user who clicks through onboarding from Step 0 to Step 4 and pauses or closes the app before setting a PIN.

---

## 4. Comprehensive Missing Test Coverage

The following test scenarios were completely absent from the test suites:

| Test ID | Scenario | Missing Assertion |
|---|---|---|
| **GAP-01** | Fresh install initial state | Must be `SETUP_IN_PROGRESS`, not `INITIALIZING` or `RECOVERY_REQUIRED`. |
| **GAP-02** | Device Admin enabled during setup | Must remain `SETUP_IN_PROGRESS`, `recoveryRequired` must be `false`. |
| **GAP-03** | Accessibility enabled during setup | Must not block foreground apps, must not call `GLOBAL_ACTION_HOME`. |
| **GAP-04** | Foreground event on Launcher during setup | Launcher must be allowed (`LockDecision.Allowed`). |
| **GAP-05** | Foreground event on Settings during setup | Settings must be allowed (`LockDecision.Allowed`). |
| **GAP-06** | App drawer swipe during setup | Must not be dismissed by global action. |
| **GAP-07** | Process kill & restart during setup | Must resume in `SETUP_IN_PROGRESS`, user can finish onboarding. |
| **GAP-08** | OS reboot during setup | Must remain in `SETUP_IN_PROGRESS`, device remains fully usable. |
| **GAP-09** | Cold start with pre-existing admin & no creds | Cold-start check must catch pre-existing admin as `RECOVERY_REQUIRED`. |
| **GAP-10** | Provisioning completion transition | Atomic transition to `SECURITY_PROVISIONED`. |
| **GAP-11** | Post-provisioning credential wipe | Enters `RECOVERY_REQUIRED`, blocks protected apps, allows launcher. |
| **GAP-12** | Launcher package immunity | `LockKeeperAccessibilityService` must NEVER call `GLOBAL_ACTION_HOME` if target is launcher. |

---

## 5. Required New Test Suite Specification (TEST-601 to TEST-626)

To permanently seal these gaps, Stage E must implement:
- **`IncidentResolutionUnitTest.kt`**: 26 dedicated test cases covering TEST-601 through TEST-626.
- **Flutter Widget Tests**: Verifying `OnboardingScreen` remains mounted when permissions are granted, and that `SETUP_IN_PROGRESS` correctly routes without kicking user to `HomeScreen`.
- **Real-Device Step-by-Step Manual & Instrumented Sequence**: Verifying on the physical Xiaomi Mi 10i that granting Device Admin before PIN setup leaves the device responsive and operable.
