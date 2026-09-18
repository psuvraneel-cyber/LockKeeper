# Remediation Blockers & Triage Matrix

**Project:** LockKeeper (C:\AppLocker)  
**Phase:** Post-Self-Protection Adversarial Validation  
**Date:** 2026-09-17  
**Validator:** Agent 15 (Lead Security Validation Engineer)

---

## 1. Executive Summary

This document categorizes all validated findings to provide the project lead with an actionable gate decision:
**Is the Self-Protection subsystem sufficiently safe, robust, and stable to serve as the foundation for Wave 1 remediation?**

**Verdict: NO (BLOCKING FLAWS PRESENT)**

The subsystem contains **6 CRITICAL BLOCKERS** and **5 HIGH-PRIORITY DEFECTS** that must be resolved before any subsequent architectural waves can proceed.

---

## 2. Triage & Blocker Classification

### 2.1 BLOCKS NEXT WAVE (Must Be Fixed Before Any Further Development)

These issues represent fatal security vulnerabilities, severe functional regressions, or complete breakdowns of claimed protections:

1. **`FINDING-SEC-01` — Missing SQLite Row Results in Silent UPDATE Failure & Infinite Brute Force:**
   - *Impact:* Rate-limiting is completely broken on fresh installs. Attackers have infinite password guesses.
   - *Action Required:* Ensure row `id = 1` is inserted/upserted on startup, or rewrite DAO queries to use `INSERT OR REPLACE INTO app_settings`.
2. **`FINDING-SEC-02` — Catastrophic False Positive on Device Admin List:**
   - *Impact:* Merely viewing the list of Device Admin apps triggers the Admin Password overlay; deactivating other apps (e.g. Find My Device) is blocked.
   - *Action Required:* Change condition to require BOTH `isDeviceAdminScreen && mentionsLockKeeper && hasDeactivateAction`, specifically targeted to LockKeeper's receiver.
3. **`FINDING-SEC-03` — Traversal Depth (<=6) and Node (<=45) Limits Prune Action Buttons:**
   - *Impact:* Modern AOSP, Samsung, and Xiaomi Settings screens bypass tamper detection completely.
   - *Action Required:* Increase depth to 12, node budget to 120, and prioritize action-matching nodes before depth traversal.
4. **`FINDING-SEC-04` — Premature Overlay Destruction on Soft Keyboard & Intermediate Events:**
   - *Impact:* Tapping the password field launches the soft keyboard, causing intermediate window state events that immediately dismiss the Admin overlay, exposing the App Info screen.
   - *Action Required:* Prevent `dismissAdminOverlay()` from executing on intermediate events within the same management package.
5. **`FINDING-SEC-05` — Clock-Warp Lockout Bypass via System Time Adjustment:**
   - *Impact:* Advancing device time by 5 minutes completely wipes the 300-second persistent lockout.
   - *Action Required:* Enforce monotonic uptime and boot-count validation for persistent lockouts.
6. **`FINDING-SEC-06` — Missing `RECOVERY_REQUIRED` State Machine Causes Total Protection Collapse on Clear Data:**
   - *Impact:* Clearing storage wipes local credentials, dropping `shouldProtectSettings()` to `false` and allowing zero-password Device Admin deactivation.
   - *Action Required:* Implement recovery detection: if `dpm.isAdminActive()` is true but database credentials are missing, force a recovery lock state.

---

### 2.2 SHOULD FIX BEFORE NEXT WAVE (High-Priority Defects)

These defects severely impair security robustness, concurrency safety, or release posture:

1. **`FINDING-HIGH-01` — Concurrent Password Verification TOCTOU:**
   - Wrap `verifyAdminPassword` in a `Mutex` to serialize checks and prevent parallel brute-force attacks.
2. **`FINDING-HIGH-02` — Window Type Discrepancy (`TYPE_APPLICATION_OVERLAY`):**
   - Correct window type or document dependence on `SYSTEM_ALERT_WINDOW` and address MIUI pop-up restrictions.
3. **`FINDING-HIGH-04` — Conflation of OS Accessibility Setting with Live Service Connection:**
   - Update `PlatformChannelHandler` to verify `isServiceConnected == true` before reporting "Accessibility Granted" to Flutter.
4. **`FINDING-HIGH-06` — Asymmetric Dual Grace Window Timers:**
   - Remove duplicate wall-clock grace timer from `LockDecisionEngine`; make `TamperAuthorizationController` the sole source of truth.
5. **`FINDING-HIGH-07` — Missing Overlay Cleanup on Service `onDestroy()`:**
   - Call `overlayManager.dismissAdminOverlay()` in `onDestroy()` to prevent screen trapping upon service death.
6. **`FINDING-HIGH-08` — R8 / ProGuard Obfuscation Disabled in Release Builds:**
   - Enable `isMinifyEnabled = true` in `build.gradle.kts` with proper ProGuard keep rules.

---

### 2.3 CAN DEFER (Fix During Polish / Later Sprint)

1. **`FINDING-MED-01` — Swallowed Lockout Status over MethodChannel:**
   - Enhance channel response to return rich lockout status map to Flutter Settings screen.
2. **`FINDING-MED-02` — Broken `lastHandledPackage` Assignment on Tamper Events:**
   - Ensure `lastHandledPackage` is assigned before returning from tamper event handling.
3. **`FINDING-MED-03` — Static Lockout Text Lacks Real-Time Ticking Countdown Timer:**
   - Add `CountDownTimer` to `AdminOverlayView`.
4. **`FINDING-MED-04` — `errorTextView` Lacks Accessibility Live Region:**
   - Add `accessibilityLiveRegion = polite` for TalkBack screen reader compliance.
5. **`FINDING-MED-05` — Database Initialization Race on Service Startup:**
   - Ensure cache warming completes synchronously during startup.
6. **`FINDING-MED-06` — Plaintext Debug Logging in Production Logcat:**
   - Strip `Log.d` calls in release builds.

---

### 2.4 DOCUMENT ONLY (Platform Reality & Distribution Constraints)

1. **`FINDING-HIGH-03` — Exclusion of Third-Party Launchers:**
   - Non-system apps cannot reliably hook into private proprietary launcher UI without Device Owner privileges. Document this boundary clearly.
2. **Google Play Accessibility API Policy Restriction:**
   - Document that the self-protection implementation requires either an explicit **Parental Controls** declaration on Google Play or distribution via direct/sideload channels.

---

### 2.5 RUNTIME VALIDATION REQUIRED (To Be Proven on Physical Devices)

The following items cannot be statically proven and must be validated across physical hardware:
1. Exact view node counts and layout depths on Xiaomi MIUI 14 / HyperOS and Samsung One UI 6.
2. Behavior of `TYPE_APPLICATION_OVERLAY` over vendor-specific uninstallation confirmation dialogs.
3. IME soft keyboard animation interaction with `AdminOverlayView`.
4. Background service death behavior under OEM battery optimizers.
