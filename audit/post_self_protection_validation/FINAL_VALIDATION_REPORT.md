# LockKeeper Self-Protection Post-Implementation Adversarial Validation

**Project:** LockKeeper (`C:\AppLocker`)  
**Phase:** Post-Self-Protection Implementation Adversarial Audit  
**Date:** 2026-09-17  
**Validator:** Agent 15 — Lead Security Validation Engineer  
**Status:** COMPLETED — FINAL VERDICT: **NO (DO NOT PROCEED TO WAVE 1 UNTIL BLOCKERS RESOLVED)**

---

## 1. Executive Summary

An exhaustive, multi-agent adversarial security validation was executed against the newly implemented Self-Protection / Anti-Tamper subsystem of the LockKeeper Android application.

The implementation claimed comprehensive multi-signal tamper detection, robust rate-limiting and persistent lockout, deterministic overlay teardown, multilingual fallback protection, and strict preservation of the previously verified physical-device Accessibility black-screen fix.

**The adversarial audit soundly refutes these claims.**

Static analysis, code inspection, and architectural decomposition revealed **6 CRITICAL VULNERABILITIES**, **8 HIGH-SEVERITY FLAWS**, **6 MEDIUM DEFECTS**, and **2 LOW-SEVERITY ISSUES**. The subsystem contains multiple fatal fail-open security flaws, severe false positives that break general Android settings, arbitrary node-traversal limits that prune uninstallation buttons, and a complete failure to persist lockout when the database row is uninitialized.

**Core Critical Findings:**
1. **Infinite Online Password Brute Force (Fail-Open):** In `TamperAuthorizationController.kt:92` and `AppSettingsDao.kt:22`, if row `id = 1` does not exist in SQLite, `updateAdminLockout` executes `UPDATE ... WHERE id = 1`, affecting 0 rows. Counter increments fail silently, allowing an attacker to guess passwords indefinitely with zero lockout.
2. **Catastrophic False Positive on General Device Admin Settings:** `TamperDetectionEngine.kt:142` uses `isDeviceAdminScreen && (mentionsLockKeeper || hasDeactivateAction)`. Merely viewing the list of installed Device Admin apps triggers the Admin Password overlay. Furthermore, attempting to deactivate *unrelated* apps (e.g. Google Find My Device) triggers LockKeeper's gate.
3. **Severe Traversal Pruning (Total Bypass):** Node traversal cuts off at `depth > 6` or `count >= 45`. Modern Android Settings layouts (AOSP `SpaActivity`, Samsung One UI, Xiaomi HyperOS) frequently exceed depth 7 and 100+ nodes. Action buttons (Uninstall, Force Stop, Clear Storage) are pruned before evaluation, allowing uninstallation without challenge.
4. **Soft-Keyboard Overlay Destruction:** Interacting with `AdminOverlayView` launches the soft keyboard, triggering intermediate window events within `com.android.settings`. `LockKeeperAccessibilityService.kt:111` misinterprets this as leaving the screen and immediately tears down the overlay.
5. **Clock-Warp Lockout Bypass:** `adminLockoutUntil` is evaluated against wall-clock `System.currentTimeMillis()`. Setting the device clock forward by 5 minutes instantly clears the 300-second lockout.
6. **Total Demolition of Defenses on Clear Data:** The documented `RECOVERY_REQUIRED` state is completely missing from the codebase. Clearing app storage wipes local credentials, dropping `shouldProtectSettings()` to `false` and allowing zero-password Device Admin deactivation and uninstallation.

---

## 2. Implementation Reviewed

The audit evaluated all files modified or added as part of the Self-Protection initiative:
- `TamperEvent.kt` (NEW)
- `TamperDetectionEngine.kt` (NEW)
- `TamperAuthorizationController.kt` (NEW)
- `AppSettingsEntity.kt` (MODIFIED)
- `AppSettingsDao.kt` (MODIFIED)
- `AppDatabase.kt` (MODIFIED — Room v3, `MIGRATION_2_3`)
- `AdminOverlayView.kt` (MODIFIED)
- `OverlayManager.kt` (MODIFIED)
- `LockKeeperAccessibilityService.kt` (MODIFIED)
- `PlatformChannelHandler.kt` (MODIFIED)
- `ProtectionRepository.kt` (MODIFIED)
- `MainActivity.kt` (MODIFIED)
- `TamperDetectionEngineTest.kt` (NEW)
- `TamperAuthorizationControllerTest.kt` (NEW)
- `CredentialStoreTest.kt` (MODIFIED)
- `android/app/build.gradle.kts`
- `AndroidManifest.xml`
- `scripts/android_build.ps1`
- `lib/core/services/platform_bridge.dart`
- `lib/ui/screens/settings_screen.dart`
- `lib/ui/screens/home_screen.dart`

---

## 3. Specialist Agents Used

The validation was executed by 14 independent specialist agents, culminating in synthesis by Agent 15:
- **Agent 01:** Change-Scope / Diff Auditor (`agent_01_scope_review.md`)
- **Agent 02:** TamperDetectionEngine Adversarial Auditor (`agent_02_detection_adversarial.md`)
- **Agent 03:** Uninstall / Tamper Route Bypass Agent (`agent_03_bypass_review.md`)
- **Agent 04:** Authentication / Admin Password Auditor (`agent_04_authentication_review.md`)
- **Agent 05:** Database / Migration / Persistence Auditor (`agent_05_persistence_review.md`)
- **Agent 06:** Accessibility Service / Lifecycle Auditor (`agent_06_accessibility_lifecycle.md`)
- **Agent 07:** Overlay / Window Security Auditor (`agent_07_overlay_review.md`)
- **Agent 08:** Device Admin / Android Security Model Auditor (`agent_08_device_admin_review.md`)
- **Agent 09:** Security State / Source-of-Truth Auditor (`agent_09_source_of_truth_review.md`)
- **Agent 10:** Concurrency / TOCTOU / Race Auditor (`agent_10_concurrency_review.md`)
- **Agent 11:** UI/UX / Security-Truth Auditor (`agent_11_ux_review.md`)
- **Agent 12:** Test Quality / Test Evidence Auditor (`agent_12_test_quality_review.md`)
- **Agent 13:** Release / Build / Static Security Auditor (`agent_13_release_review.md`)
- **Agent 14:** Google Play Policy / Distribution Auditor (`agent_14_policy_review.md`)
- **Agent 15:** Lead Security Validation Engineer (Master Synthesis & Gate Verification)

All specialist reports are preserved under `C:\AppLocker\audit\post_self_protection_validation\AGENT_REPORTS\`.

---

## 4. Total Findings Breakdown

| Severity | Count | Confirmed by Code | Runtime Validation Required |
|---|---|---|---|
| **CRITICAL** | 6 | 6 | 0 |
| **HIGH** | 8 | 8 | 2 (Layout & Touch bounds) |
| **MEDIUM** | 6 | 6 | 1 (Timing verification) |
| **LOW** | 2 | 2 | 0 |
| **TOTAL** | **22** | **22 (100%)** | **3** |

---

## 5. Critical Findings Summary

- **`FINDING-SEC-01`**: Missing SQLite Row Results in Silent `UPDATE` Failure and Infinite Password Brute-Force (Fail-Open).
- **`FINDING-SEC-02`**: Catastrophic False Positive on Device Admin List and Cross-App Deactivation Blocking.
- **`FINDING-SEC-03`**: Arbitrary DFS Depth (<=6) and Node (<=45) Limits Prune Uninstallation Action Buttons (False Negative / Total Bypass).
- **`FINDING-SEC-04`**: Premature Overlay Tearing and Destruction on Soft-Keyboard (IME) and Intermediate Window Events.
- **`FINDING-SEC-05`**: Clock-Warp Epoch Comparison Bypasses 300-Second Persistent Lockout.
- **`FINDING-SEC-06`**: Missing `RECOVERY_REQUIRED` State Machine Causes Total Defense Demolition Upon Storage Clearing.

*(Full specifications in `CRITICAL_FINDINGS.md`)*

---

## 6. High Findings Summary

- **`FINDING-HIGH-01`**: Concurrent Password Verification TOCTOU Defeats 5-Attempt Rate Limiting.
- **`FINDING-HIGH-02`**: Window Type Discrepancy: Uses `TYPE_APPLICATION_OVERLAY` Instead of Claimed `TYPE_ACCESSIBILITY_OVERLAY`.
- **`FINDING-HIGH-03`**: Complete Exclusion of Third-Party Launchers and Alternative Package Managers from Detection.
- **`FINDING-HIGH-04`**: Conflation of OS Accessibility Setting with Live Service Connection Violates Truth-in-Security.
- **`FINDING-HIGH-05`**: Dropping `TYPE_WINDOW_CONTENT_CHANGED` Blindfolds LockKeeper to Jetpack Compose, Scrolling, and Tabbed Settings.
- **`FINDING-HIGH-06`**: Asymmetric Dual Grace Window Timers (Monotonic vs Wall-Clock) Create Desynchronization Bypass.
- **`FINDING-HIGH-07`**: Missing Overlay Cleanup on Accessibility Service `onDestroy()` Causes Permanent Screen Trapping.
- **`FINDING-HIGH-08`**: R8 / ProGuard Minification and Obfuscation Completely Disabled in Release Builds.

*(Full specifications in `HIGH_FINDINGS.md`)*

---

## 7. Medium & Low Findings Summary

- **`FINDING-MED-01`**: Swallowed Lockout Status over MethodChannel Blinds Flutter Settings UI.
- **`FINDING-MED-02`**: Broken `lastHandledPackage` Tracking on Tamper Events Disables Debounce Window.
- **`FINDING-MED-03`**: Static Lockout Text Lacks Real-Time Ticking Countdown Timer.
- **`FINDING-MED-04`**: `errorTextView` Lacks Accessibility Live Region for Screen Readers (WCAG Non-Compliance).
- **`FINDING-MED-05`**: Database Initialization Race on Service Startup Disables Anti-Tamper Upon Boot.
- **`FINDING-MED-06`**: Plaintext Debug Logging Leaks Sensitive Application Packages to System Logcat.
- **`FINDING-LOW-01`**: 50MB Binary APK and Launcher Mipmap Assets Committed to Version Control.
- **`FINDING-LOW-02`**: Dead Enums Declared in `TamperEvent.kt` but Unused in Detection Engine.

*(Full specifications in `MEDIUM_LOW_FINDINGS.md`)*

---

## 8. Answers to Special Security Questions

### Detection
1. **Can localization defeat the detector?**  
   **YES.** Languages outside English, Spanish, and French for Accessibility toggles (e.g. German, Italian, Russian, Chinese) fail keyword matching. On non-AOSP class names, detection is bypassed.
2. **Can resource-ID variation defeat it?**  
   **YES.** Non-AOSP resource IDs on Samsung, Xiaomi, or Vivo that do not contain `button1_negative` or `uninstall_button` fail detection unless class names or keywords match.
3. **Can Activity variation defeat it?**  
   **YES.** Custom OEM activity names (e.g. `SecAppInfoActivity`, `SubSettings`) evade substring checks.
4. **Can package variation defeat it?**  
   **YES.** Any package outside `com.android.settings`, 7 package installers, and 3 OEM centers is ignored 100% of the time.
5. **Can UI structure variation defeat it?**  
   **YES.** Any view structure placing action controls deeper than depth 6 or after 45 preceding nodes is completely pruned.
6. **Can event ordering defeat it?**  
   **YES.** Navigating within 150ms drops the window state event; intermediate IME events destroy active overlays.
7. **Can rapid events overwhelm it?**  
   **YES.** Handler posting without generation tokens causes out-of-order execution.
8. **Can false positives intentionally trigger it?**  
   **YES.** Opening the Device Admin list or deactivating Google Find My Device intentionally triggers LockKeeper's gate.

### Authentication
9. **Can Admin Password brute force be performed?**  
   **YES.** If SQLite row 1 is uninitialized, rate-limiting is fail-open, permitting infinite attempts.
10. **Can lockout be reset?**  
    **YES.** Advancing device clock by 5 minutes destroys the lockout.
11. **Can lockout be bypassed through another authentication path?**  
    **NO.** MethodChannel uses the same controller; however, Flutter settings swallows the lockout state.
12. **Can grace authorization be replayed?**  
    **NO.** Grace is bound to in-memory timestamps.
13. **Can grace authorization be extended?**  
    **YES.** Submitting a valid password repeatedly resets the 30s window.
14. **Can process death reset security state?**  
    **YES.** In-memory grace is wiped; if DB row 1 is missing, lockout is also wiped.
15. **Can database reset reset security state?**  
    **YES.** Clearing data permanently destroys credentials and disables all protection.

### State
16. **Is Accessibility enabled distinct from service connected?**  
    **NO in code.** `PlatformChannelHandler` uses `||`, treating an OS setting as connected.
17. **Is Device Admin active derived from DPM?**  
    **YES.** Correctly queries `dpm.isAdminActive()`.
18. **Can Flutter display stale protection state?**  
    **YES.** Reports "Protected" when service is dead.
19. **Can native state and Flutter state disagree?**  
    **YES.** Native service can be dead while Flutter displays green badges.
20. **Can process death produce an insecure state?**  
    **YES.** Initialization race on startup leaves a window where settings are unprotected.
21. **Can service restart produce an insecure state?**  
    **YES.** Async cache warming causes `shouldProtectSettings()` to return `false` initially.

### Persistence
22. **Is Room migration safe?**  
    **NO.** Uses `.fallbackToDestructiveMigration()`; zero migration tests exist.
23. **Is lockout persistence correct?**  
    **NO.** Relies on `UPDATE ... WHERE id = 1` which affects 0 rows when row 1 is missing.
24. **Are increments atomic?**  
    **NO.** Check-then-act pattern in Kotlin coroutines without SQL atomicity.
25. **Can clear-data destroy security state?**  
    **YES.** Total wipe of credentials and settings; no recovery mode.
26. **Can missing DB cause fresh-start behavior?**  
    **YES.** Re-routes to unconfigured setup.

### Overlay
27. **Can an overlay survive navigation?**  
    **NO.** Dismissed when package leaves settings.
28. **Can it appear over unrelated screens?**  
    **YES.** Covers deactivation of unrelated Device Admin apps.
29. **Can it recreate the previous black-screen bug?**  
    **YES.** Hardcoded `FLAG_SECURE` creates permanent black screens if leaked on service death.
30. **Can multiple overlays appear?**  
    **YES.** Concurrent async Handler posts can leak views.
31. **Can an attacker dismiss/bypass it?**  
    **YES.** Tapping the password field launches IME, causing intermediate events that dismiss the overlay.

### Uninstall
32. **Which normal UI uninstall routes are covered?**  
    AOSP Settings App Info and AOSP PackageInstaller (partial).
33. **Which are partial?**  
    13 routes (see `ROUTE_VALIDATION_MATRIX.md`).
34. **Which cannot be controlled?**  
    Google Play Store, Third-Party Launchers, Notification Task Manager.
35. **Does Package Installer differ from Settings?**  
    YES. Evaluated in a separate branch with different class checks.
36. **Does OEM UI differ?**  
    YES. Different class names and view depths.
37. **Does Play Store differ?**  
    YES. Play Store is 100% ignored.
38. **Can a launcher bypass detection?**  
    YES. Any third-party launcher completely bypasses detection.
39. **Can alternate Activities bypass detection?**  
    YES. Unlisted activities bypass evaluation.
40. **Can clear-data bypass protection?**  
    YES. Once cleared, all protection is permanently disabled.

### Device Admin
41. **Does implementation assume Device Owner behavior?**  
    No in theory, but assumes UI interception can fully substitute for DO policies.
42. **What does ordinary Device Admin actually guarantee?**  
    Uninstallation friction and system deactivation prompt only.
43. **Can deactivation be detected reliably?**  
    NO. Detection produces false positives on list viewing and blocks unrelated apps.
44. **What happens when DPM state changes externally?**  
    LockKeeper's `onDisabled()` is an empty stub that takes zero action.

### Performance
45. **Can Accessibility processing cause ANRs?**  
    YES. Synchronous DPM binder calls and DFS traversal on the Main Thread during rapid transitions.
46. **Is node traversal actually bounded?**  
    YES, bounded to depth 6 and 45 nodes — but this causes severe false-negative bypasses.
47. **Are database operations inside accessibility callbacks?**  
    No, database operations are delegated to background coroutines.
48. **Can event bursts cause battery drain?**  
    YES. Repeated DFS traversals on un-debounced tamper events.
49. **Can overlays cause window leaks?**  
    YES. Service `onDestroy()` leaks active overlay views.

### Regression
50. **Can this implementation reintroduce the previous Accessibility black screen?**  
    Partially mitigated by whitelisting `MiuiAccessibilitySettingsActivity`, but leaks on service kill still cause black screens.
51. **Can Device Admin onboarding regress?**  
    NO. Activation when `isDeviceAdminActive == false` is permitted.
52. **Can general Accessibility Settings become blocked?**  
    NO. Preserved.
53. **Can ordinary Settings navigation trigger anti-tamper?**  
    YES. Viewing the Device Admin list triggers anti-tamper.
54. **Can unrelated app management trigger the LockKeeper password?**  
    YES. Deactivating Google Find My Device triggers LockKeeper.

---

## 9. Final Gate Results

| Gate | Category | Verdict | Evidence / Justification |
|---|---|---|---|
| **GATE A** | Architectural Safety | **FAIL** | Dual timers, missing `RECOVERY_REQUIRED`, split-brain on onboarding. |
| **GATE B** | Authentication Safety | **FAIL** | Fail-open zero-row DB update allows infinite password brute-forcing. |
| **GATE C** | Persistence Safety | **FAIL** | Lockouts wiped by clock warp; Room destructive fallback active. |
| **GATE D** | Detection Safety | **FAIL** | Prunes action buttons at depth > 6; false positive on Device Admin list. |
| **GATE E** | Overlay Safety | **FAIL** | Uses `TYPE_APPLICATION_OVERLAY`; leaks view on service `onDestroy()`. |
| **GATE F** | Lifecycle Safety | **FAIL** | Service death leaves zombie state; IME events destroy overlay. |
| **GATE G** | Regression Safety | **CONDITIONAL PASS** | MIUI list preserved, but Device Admin list view regressed. |
| **GATE H** | Testing Sufficiency | **FAIL** | 42 tests evaluate toy mocks; zero concurrency, migration, or DB tests. |
| **GATE I** | Platform Reality | **CONDITIONAL PASS** | Correctly acknowledges lack of Device Owner uninstallation blocking. |
| **GATE J** | Distribution Risk | **FAIL** | Google Play Accessibility Policy violation unless declared Parental Control. |

---

## 10. Recommendation for Next Development Phase

### Question:
**Is the Self-Protection implementation safe enough to become the foundation for Wave 1 remediation?**

### Final Verdict:
# **NO**

### Empirical Justification:
The implementation cannot serve as a stable foundation for further development. Proceeding to Wave 1 while these flaws persist will build on a broken security architecture:
1. An attacker can brute-force the Admin Password without limitation due to the SQLite zero-row update bug (`FINDING-SEC-01`).
2. Users navigating general Android settings are blocked by severe false positives (`FINDING-SEC-02`).
3. Modern Android devices bypass uninstallation protection completely due to the 45-node DFS cutoff (`FINDING-SEC-03`).
4. Legitimate users attempting to enter passwords have their overlay torn down by soft-keyboard launch (`FINDING-SEC-04`).
5. Clearing app data results in total, permanent collapse of all protections without recovery (`FINDING-SEC-06`).

**All 6 Critical Blockers and 5 High-Priority Deficiencies documented in `REMEDIATION_BLOCKERS.md` must be remediated and re-audited before Wave 1 begins.**
