# LockKeeper Incident Response — Stage B: Lead Architect Synthesis & Root Cause Analysis
**Document:** `audit/CRITICAL_FIRST_RUN_LOCKOUT_ROOT_CAUSE.md`  
**Incident:** First-Run Onboarding / Device Admin / Home-Screen Lockout  
**Author:** Lead Security Architect  
**Date:** 2026-09-18  
**Status:** COMPLETE SYNTHESIS (Pre-Implementation Gate)  

---

## 1. Exact Root Cause

The incident is caused by an architectural conflation of two mutually exclusive system conditions:
1. **Legitimate First-Run Setup (`SETUP_IN_PROGRESS`):** A clean installation where the user has granted Device Administrator privileges (Step 4 of onboarding) but has not yet completed credential configuration (PIN in Step 6, Admin Password in Step 7).
2. **Adversarial Post-Tamper State Corruption (`RECOVERY_REQUIRED`):** A previously provisioned installation where application credentials or database records were deleted (e.g., via `pm clear` or SQLite manipulation) while OS-level Device Administrator privileges remained active.

In `ProtectionRepository.kt:55`, the recovery check was defined as:
```kotlin
if (isAdminActive && (!hasCreds || !settings.onboardingComplete)) {
    if (!settings.recoveryRequired) {
        appSettingsDao.setRecoveryRequired(true)
    }
    true
}
```
Because this condition evaluates **only** whether Device Admin is active and credentials/onboarding are incomplete, it falsely and unconditionally classifies a legitimate user granting Device Admin during initial setup as an attacker who wiped app data.

---

## 2. Exact State Transition Sequence

```
1. Fresh Installation
   └── AppSettingsEntity(onboardingComplete = false, recoveryRequired = false, securityProvisioned = false)
   └── CredentialStore: hasPin() == false, hasAdminPassword() == false
   └── DevicePolicyManager: isAdminActive() == false

2. User Launches LockKeeper -> Enters OnboardingScreen (Step 0: Welcome)

3. User Grants Permissions (Steps 1-3)
   └── Step 1: Overlay granted
   └── Step 2: Usage Stats granted
   └── Step 3: Accessibility Service connected (LockKeeperAccessibilityService.isConnected = true)

4. User Grants Device Administrator (Step 4)
   └── OS registers LockKeeper in /data/system/device_policies.xml (isAdminActive = true)
   └── LockKeeperDeviceAdminReceiver.onEnabled() fires
   └── calls ProtectionRepository.notifySecurityStateChanged()
   └── ProtectionRepository.checkRecoveryStatus() executes:
         • isAdminActive == true
         • hasCreds == false (PIN not set yet)
         • settings.onboardingComplete == false
         • CONDITION TRIPS: appSettingsDao.setRecoveryRequired(true) commits to Room DB!
         • overallStatus becomes "RECOVERY_REQUIRED"

5. Flutter Layer Premature Abort
   └── MainActivity resumes -> OnboardingScreen._refreshPermissions() queries status
   └── status.recoveryRequired is true
   └── OnboardingScreen executes Navigator.pushReplacement(HomeScreen)
   └── User is forcibly evicted from onboarding wizard before PIN/Password can be configured

6. LockKeeper Closes / User Focuses Home Screen
   └── Foreground window becomes MIUI launcher (com.miui.home)
```

---

## 3. Exact Enforcement Path & Infinite Loop

```
1. Window event fires for com.miui.home
   └── AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED (packageName = "com.miui.home")
   └── LockKeeperAccessibilityService.onAccessibilityEvent() receives event

2. Service queries repository.evaluatePackage("com.miui.home")
   └── ProtectionRepository calls LockDecisionEngine.evaluate("com.miui.home")
   └── LockDecisionEngine Rule 1 (own package): "com.miui.home" != "com.lockkeeper.app" -> No match
   └── LockDecisionEngine Rule 2 (RecoveryRequired):
         • isRecoveryRequired == true OR appSettings.recoveryRequired == true
         • MATCH! Returns LockDecision.RecoveryRequired(DENIED_RECOVERY_REQUIRED)

3. Accessibility Service enforces terminal failure:
   └── LockKeeperAccessibilityService.kt lines 182-188:
         is LockDecision.RecoveryRequired -> {
             overlayManager.dismissIfShowing(packageName)
             performGlobalAction(GLOBAL_ACTION_HOME)
         }
   └── performGlobalAction(GLOBAL_ACTION_HOME) is dispatched to Android WindowManager

4. Android / MIUI WindowManager processes GLOBAL_ACTION_HOME:
   └── Re-dispatches focus to the Home launcher (com.miui.home)
   └── Emits a new AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED for com.miui.home

5. Self-Sustaining Recursive Loop:
   └── Event received -> evaluatePackage("com.miui.home") -> RecoveryRequired -> performGlobalAction(GLOBAL_ACTION_HOME)
   └── Dispatched repeatedly (~10-15 times/sec)
   └── Any user attempt to open App Drawer, System Settings, or Power Menu triggers window events
   └── Those packages also evaluate to RecoveryRequired -> instantly dismissed by GLOBAL_ACTION_HOME
   └── Device is completely locked down on the Home screen.
```

---

## 4. Why Previous Tests (Waves 1–4) Missed It

1. **Synthetic Isolation:** Waves 1–4 tests evaluated `LockDecisionEngine` using isolated unit tests with mock packages (`com.target.app`), never passing the home launcher package (`com.miui.home`, `com.android.launcher3`).
2. **Binary State Assumptions:** Tests only tested either a fully configured system (`onboardingComplete = true, hasCreds = true`) or a simulated post-wipe system (`isAdminActive = true, hasCreds = false -> assert RecoveryRequired`).
3. **No Service Integration:** `LockKeeperAccessibilityService` lifecycle was not connected to a live event loop in unit tests; `performGlobalAction(GLOBAL_ACTION_HOME)` was never executed in a loop.

---

## 5. Why Wave 5 Did Not Catch It

1. **Test-Asserted Defect:** In Wave 5, `ProductionSecurityBoundaryTest.kt` line 132 explicitly asserted that `isAdminActive && (!hasCreds || !onboardingComplete)` MUST flag `isRecoveryRequired = true`! The automated test codified the false equivalence as an expected invariant.
2. **Execution Order in Real-Device Tests:** During real-device test runs on the Xiaomi Mi 10i, credentials were provisioned via automated script commands *prior* to activating Device Admin, or the test started with a pre-configured database where `hasCreds == true`. The specific interactive sequence of a first-run user pausing or switching apps after Step 4 was never executed.

---

## 6. The Minimal Architectural Fix

We introduce an explicit, authoritative native distinction between **`SETUP_IN_PROGRESS`** and **`RECOVERY_REQUIRED`** without weakening fail-closed protection.

### Principle 1: Formal Provisioning State Machine
Add `securityProvisioned: Boolean = false` to `AppSettingsEntity` (and Room schema / DAO):
- When LockKeeper is installed cleanly:
  - `securityProvisioned = false`
  - `onboardingComplete = false`
  - `recoveryRequired = false`
  - Overall status is **`SETUP_IN_PROGRESS`** (maps to Flutter setup).
- While in `SETUP_IN_PROGRESS`:
  - Device Admin may be granted (`isDeviceAdminGranted = true`).
  - Accessibility Service may be connected (`isA11yOperational = true`).
  - Overlay and Usage may be granted.
  - **`checkRecoveryStatus()` MUST NOT trip.** An unprovisioned installation undergoing setup cannot be in "recovery."
- Transition to **`SECURITY_PROVISIONED`**:
  - Occurs **atomically** in `ProtectionRepository.completeInitialProvisioning()` ONLY after:
    1. Valid PIN is committed to `CredentialStore`.
    2. Valid Admin Password is committed to `CredentialStore`.
    3. `appSettingsDao.setProvisionedAndOnboardingComplete(true, true)` is committed in Room SQLite.
- Entry into **`RECOVERY_REQUIRED`**:
  - An installation enters `RECOVERY_REQUIRED` if and only if:
    1. `securityProvisioned == true` (it was previously provisioned) AND (`!hasPin || !hasAdminPassword` OR Room inconsistency).
    2. OR: On cold start (initial app launch), `isAdminActive == true` while neither valid credentials nor a valid setup token exists in private storage (detecting a cold `pm clear` by an adversary).

### Principle 2: Launcher & System UI Immunity from `GLOBAL_ACTION_HOME`
In `LockKeeperAccessibilityService`:
- Resolve and track the system launcher package(s).
- **Rule:** If `packageName` is a home launcher or System UI power menu:
  - **NEVER** call `performGlobalAction(GLOBAL_ACTION_HOME)`.
  - If access must be gated (e.g. Settings or protected apps), dismiss overlays or navigate BACK, never recursively summon HOME when already on HOME.
- During `SETUP_IN_PROGRESS`:
  - `evaluatePackage(packageName)` returns `LockDecision.Allowed(ALLOWED_SETUP_MODE)`.
  - Normal apps, settings, and launcher operate without friction so the user can complete setup.

### Principle 3: Flutter Onboarding Flow Preservation
- In `onboarding_screen.dart`:
  - Do not evict user to `HomeScreen` if status is `SETUP_IN_PROGRESS` (or `recoveryRequired == false`).
  - Even if `recoveryRequired` is signaled, if credentials have never been set (`securityProvisioned == false`), route to onboarding continuation, not to an unauthenticated recovery dead-end.
- In `lib/main.dart`:
  - `home:` routes to `OnboardingScreen()` whenever `!isOnboardingComplete && !isSecurityProvisioned`.

---

## 7. Security Implications of the Fix

| Invariant | Status Under Fix | Verification Method |
|---|---|---|
| **Wave 1 (Unified State)** | **PRESERVED** | Authoritative status remains single source of truth in native repository. |
| **Wave 2 (Decision Integrity)** | **PRESERVED** | `LockDecisionEngine` maintains fail-closed precedence once provisioned. |
| **Wave 3 (Runtime Resilience)** | **PRESERVED** | Screen-off clearing, session timeouts, and anti-tamper remain intact. |
| **Wave 4 (Platform Boundaries)** | **PRESERVED** | Device Admin and A11y remain non-bypassable barriers for protected apps. |
| **Wave 5 (Recovery Flows)** | **PRESERVED** | Legitimate recovery flows for corrupted credentials operate normally. |
| **Cold Data Wipe Protection** | **PRESERVED** | Cold start with pre-existing admin without credentials trips `RECOVERY_REQUIRED`. |
| **No Privilege Escalation** | **PRESERVED** | Unprovisioned app has no locked apps in Room; cannot unlock protected data. |

---

## 8. New Tests Required

1. **TEST-601:** Clean install initializes to `SETUP_IN_PROGRESS`.
2. **TEST-602:** Granting Device Admin during setup preserves `SETUP_IN_PROGRESS`.
3. **TEST-603:** Clean install + Device Admin + missing PIN does not trip `RECOVERY_REQUIRED`.
4. **TEST-604:** Clean install + Device Admin + missing Admin Password does not trip `RECOVERY_REQUIRED`.
5. **TEST-605:** Clean install + Device Admin + A11y connected remains `SETUP_IN_PROGRESS`.
6. **TEST-606:** Foreground event on Launcher during setup returns `LockDecision.Allowed`.
7. **TEST-607:** LockKeeper remains launchable during `SETUP_IN_PROGRESS`.
8. **TEST-608:** Android Settings remains usable during `SETUP_IN_PROGRESS`.
9. **TEST-609:** App drawer / Launcher interactions during setup do not trigger `GLOBAL_ACTION_HOME`.
10. **TEST-610:** Previously provisioned install with deleted credentials enters `RECOVERY_REQUIRED`.
11. **TEST-611:** Previously provisioned install with corrupted Room settings enters `RECOVERY_REQUIRED`.
12. **TEST-612:** Stale SharedPreferences cannot override native provisioning marker.
13. **TEST-613:** Flutter method channel cannot demote `SECURITY_PROVISIONED` to setup mode.
14. **TEST-614:** Process restart during setup maintains `SETUP_IN_PROGRESS`.
15. **TEST-615:** Accessibility Service restart during setup maintains `SETUP_IN_PROGRESS`.
16. **TEST-616:** Device reboot during setup maintains `SETUP_IN_PROGRESS`.
17. **TEST-617:** Completing initial provisioning atomically transitions to `SECURITY_PROVISIONED`.
18. **TEST-618:** Protected app enforcement operates normally after provisioning.
19. **TEST-619:** `RECOVERY_REQUIRED` remains fail-closed for protected apps.
20. **TEST-620:** Deleting UI flags cannot convert `RECOVERY_REQUIRED` to `SETUP_IN_PROGRESS`.
21. **TEST-621:** Native exception in decision path fails closed (`DENIED_NATIVE_FAILURE`).
22. **TEST-622 – TEST-626:** Full regression verification against Waves 1–5 invariants.

---

## 9. Synthesis Gate Approval

The synthesis is complete and aligned across all Stage A forensic findings.  
**Action:** Proceed to planning the minimal implementation changes and requesting user approval before modifying code.
