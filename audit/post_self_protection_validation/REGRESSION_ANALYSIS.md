# Regression Analysis Report

**Project:** LockKeeper (C:\AppLocker)  
**Phase:** Post-Self-Protection Adversarial Validation  
**Date:** 2026-09-17  
**Validator:** Agent 15 (Lead Security Validation Engineer)

---

## 1. Executive Summary

This audit evaluated the Self-Protection / Anti-Tamper implementation specifically against the previously established baseline to determine whether any pre-existing functionalities, fixes, or security invariants regressed.

**Primary Regression Targets:**
1. **The Physical-Device Accessibility Black-Screen Fix:** Previously resolved on MIUI 14 / Android 12 by applying dynamic `FLAG_SECURE`, isolating task embedding via `FLAG_ACTIVITY_NEW_TASK`, and preventing interception of general accessibility listing screens (`MiuiAccessibilitySettingsActivity`).
2. **Device Admin Onboarding Usability.**
3. **App Lock & Self-Lock Enforcement.**
4. **Deterministic Overlay Teardown.**

**Key Findings:**
- **Black-Screen Regression Risk:** **PARTIALLY PRESERVED, BUT FRAGILE.**
  - `MiuiAccessibilitySettingsActivity` is explicitly whitelisted in `TamperDetectionEngine.kt:120`.
  - However, in `OverlayManager.kt:66`, `FLAG_SECURE` is permanently hardcoded on ALL overlays. If an overlay fails to dismiss or is orphaned on service kill, the entire screen remains permanently blacked out.
- **Device Admin List Regression:** **CONFIRMED REGRESSION.**
  - In `TamperDetectionEngine.kt:142`, viewing the general list of Device Admin apps now triggers `DISABLE_DEVICE_ADMIN`, blocking general admin inspection.
- **Deterministic Overlay Teardown Regression:** **CONFIRMED REGRESSION.**
  - Service `onDestroy()` fails to remove active overlays, trapping the user behind orphaned windows upon service crash.
- **App Lock & Self-Lock:** **PRESERVED.**
  - Decision engine evaluation, PIN lockout, cooldowns, and self-lock session timeouts function consistently with baseline tests.

---

## 2. Regression Checklist & In-Depth Comparison

### 2.1 General Accessibility Settings Usability
- **Baseline Fix:** LockKeeper was deadlocking MIUI 14 when users navigated to `Settings -> Accessibility -> Downloaded apps` because the view contained the word "LockKeeper", triggering an immediate overlay loop while the system was trying to embed the settings task.
- **Current Code Check:**
  `TamperDetectionEngine.kt:120`:
  ```kotlin
  val isA11yToggleTarget = nodeCollector.containsAnyTextOrDesc(ACCESSIBILITY_TOGGLE_KEYWORDS) ||
          (mentionsLockKeeper && lowerClass.contains("toggleaccessibilityservicepreferencefragment"))
  ```
  And `TamperDetectionEngineTest.kt:147-166` verifies that `com.android.settings.accessibility.MiuiAccessibilitySettingsActivity` is ignored.
- **Regression Verdict:** **PASS (Preserved).** General accessibility listings do not trigger anti-tamper.

---

### 2.2 Device Admin Onboarding Usability
- **Baseline Fix:** During initial onboarding (Step 5), the user must activate Device Admin without being challenged for an Admin Password (which has either not been configured or is in the process of setup).
- **Current Code Check:**
  `TamperDetectionEngine.kt:135`:
  ```kotlin
  if (isDeviceAdminActive) { ... }
  ```
  When Device Admin is not yet active (`isDeviceAdminActive == false`), Sub-route B is skipped entirely.
- **Regression Verdict:** **PASS (Preserved).** Initial Device Admin activation screen can be opened and granted without overlay interference.

---

### 2.3 Device Admin List Navigation (NEW REGRESSION)
- **Baseline Behavior:** Once Device Admin was active, users could still view their list of Device Administrators (`DeviceAdminSettingsActivity`) without interference.
- **Current Code Check:**
  `TamperDetectionEngine.kt:142`:
  ```kotlin
  val isDeviceAdminScreen = lowerClass.contains("deviceadmin") ||
          nodeCollector.containsTextOrDesc("device admin") || ...
  val hasDeactivateAction = nodeCollector.containsAnyTextOrDesc(DEACTIVATE_ADMIN_KEYWORDS)

  if (isDeviceAdminScreen && (mentionsLockKeeper || hasDeactivateAction)) {
      return TamperEvent(type = TamperType.DISABLE_DEVICE_ADMIN, ...)
  }
  ```
- **Regression Verdict:** **FAIL (Severe Regression).**
  Because LockKeeper appears as an active admin item in the list, `mentionsLockKeeper` is `true`. The engine intercepts viewing the list, displaying the Admin Password prompt merely for scrolling or viewing active admin apps.

---

### 2.4 FLAG_SECURE Architecture
- **Baseline Fix:** `MainActivity.kt` applied dynamic `FLAG_SECURE`:
  ```kotlin
  if (repo.isOnboardingCompleteSync()) {
      window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
  } else {
      window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
  }
  ```
- **Current Code Check:**
  `MainActivity.kt` retains this dynamic toggle.
  **However**, `OverlayManager.kt:66` hardcodes `FLAG_SECURE` unconditionally on every overlay:
  ```kotlin
  WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
          WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
          WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or
          WindowManager.LayoutParams.FLAG_SECURE
  ```
- **Regression Verdict:** **CONDITIONAL PASS.**
  `MainActivity` behaves correctly during onboarding. Overlays are secure, but if an overlay leaks, it creates an un-inspectable black screen.

---

### 2.5 Overlay Cleanup Determinism
- **Baseline Fix:** Overlays must clean up cleanly when navigating away or switching tasks.
- **Current Code Check:**
  - `LockKeeperAccessibilityService.onDestroy()` resets `isConnected` but **does not dismiss active overlays**.
  - `OverlayManager.dismissIfShowing()` is posted asynchronously to `mainHandler`, creating a window where rapid switches can drop dismissals.
- **Regression Verdict:** **FAIL (Regression).**
  Orphaned overlay views can survive service death and freeze the device UI.

---

### 2.6 App Lock & Self-Lock Compatibility
- **Baseline Behavior:** Apps registered in `locked_apps` require PIN verification; cooldowns and strict locks apply. Self-lock requires PIN when resuming LockKeeper.
- **Current Code Check:**
  `LockKeeperAccessibilityService.kt:128-150`:
  Package evaluation job runs concurrently with tamper detection:
  ```kotlin
  evaluationJob = serviceScope.launch {
      val decision = repository.evaluatePackage(packageName)
      mainHandler.post { ... }
  }
  ```
  App lock PIN dialog and self-lock gate tests (`self_lock_test.dart` and `LockDecisionEngineTest.kt`) all pass cleanly.
- **Regression Verdict:** **PASS (Preserved).** Core app locking behavior was not broken by the anti-tamper changes.

---

## 3. Regression Summary Matrix

| Baseline Property | Prior Verified State | Current Post-Implementation State | Regression Status |
|---|---|---|---|
| **MIUI A11y Listing** | Non-blocking | Non-blocking (`TamperDetectionEngine:120`) | **PRESERVED** |
| **Device Admin Setup** | Non-blocking | Non-blocking when `isAdminActive == false` | **PRESERVED** |
| **Device Admin List View** | Non-blocking | **BLOCKED** by Admin Password overlay | **REGRESSED** |
| **Dynamic FLAG_SECURE** | Dynamic on Main | Main is dynamic; Overlays hardcoded secure | **PRESERVED** |
| **Overlay Cleanup on Kill**| Clean teardown | **LEAKED** on `onDestroy` | **REGRESSED** |
| **App Lock Execution** | Functional | Functional | **PRESERVED** |
| **Self-Lock Gate** | Functional | Functional | **PRESERVED** |

---

## 4. Auditor Conclusion

The implementation preserved the critical MIUI Accessibility listing fix and onboarding activation, but introduced two new regressions:
1. Blocking the general Device Admin management list.
2. Leaking overlays upon service termination.
