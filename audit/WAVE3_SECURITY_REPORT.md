# WAVE 3: RUNTIME RESILIENCE, ANTI-TAMPER & BYPASS RESISTANCE REPORT

## 1. Executive Summary

Wave 1 established authoritative native security state.
Wave 2 established authoritative protection decisions and enforcement integrity.
Wave 3 investigated **Runtime Resilience, Anti-Tamper & Bypass Resistance**, resolving the foundational question:

> *"Can LockKeeper be bypassed even though its internal security decision is correct?"*

An adversarial 8-agent specialist review examined the real-world execution boundaries of LockKeeper under hostile, interrupted, asynchronous, and OEM-modified conditions. The audit uncovered 5 concrete runtime bypass vulnerabilities:

1. **VULN-301 (CRITICAL) — Fail-Open Overlay Attachment on WindowManager Exception:**
   `OverlayManager.attachOverlay()` swallowed window manager exceptions (such as MIUI/HyperOS "Display pop-up windows while running in the background" permission denials or invalid window tokens) without taking corrective action, allowing the user to interact directly with the protected app in the foreground.
2. **VULN-302 (HIGH) — Unbounded Session Survival Across Screen-Off:**
   In-memory app unlock sessions survived indefinitely while the device screen was locked or turned off, and lacked a maximum lifespan bound, enabling unauthorized access if the device was picked up later.
3. **VULN-303 (HIGH) — ForegroundService Fallback Permissive Linger on Terminal Denials:**
   `LockKeeperForegroundService.checkAndEnforcePackage()` handled PIN and cooldown overlays, but ignored terminal denials (`Blocked`, `RecoveryRequired`, `DenyUnknown`), failing to force navigation away from the forbidden app.
4. **VULN-304 (HIGH) — Google Play Store & OEM Security Center Uninstallation Bypass:**
   `TamperDetectionEngine` and `LockKeeperAccessibilityService` recognized stock AOSP Settings and package installer packages, but omitted Google Play Store (`com.android.vending`) and OEM management apps (`com.vivo.abe`, `com.coloros.safecenter`), allowing users to trigger uninstallation flows without tamper challenge.
5. **VULN-305 (MEDIUM) — Invalidation Failure on App Exit During Lingering Overlays:**
   `LockKeeperAccessibilityService` conditioned `repository.handleAppExited(prev)` on `!overlayManager.isOverlayShowing()`. If an overlay was still technically attached when the user navigated away, session exit logic was skipped, leaving the session active.

All 5 vulnerabilities have been fully remediated using the authoritative Wave 1 and Wave 2 pipeline, verified with 19 new automated JVM unit tests and 4 new Flutter tests (188 total tests passing across the repository, zero regressions).

---

## 2. Multi-Agent Attack Findings

### Agent 1 — Android Lifecycle Attacker
- **Surface Evaluated:** `MainActivity` destruction/recreation, process kill, background process termination (`ActivityManager`), cold start, warm start, configuration changes, and device reboot.
- **Attack Scenario:** Grant an authorized session to a protected app, trigger process termination via OS low-memory killer, restart LockKeeper, and attempt immediate access.
- **Finding:** In-memory sessions are volatile (`ConcurrentHashMap`). When the process dies, the session map is completely cleared. Upon restart, fresh initialization strictly evaluates `LockedAppEntity` and returns `RequirePin`. Lockout states are stored in the Room database (`AppSettingsEntity`), surviving process kill and reboot (INV-301, INV-316).

### Agent 2 — Accessibility Service Attacker
- **Surface Evaluated:** Service disabled in Settings, service crash/restart, delayed `onServiceConnected()`, rapid foreground package transitions, stale window events.
- **Attack Scenario:** Crash or restart the accessibility service while launching a protected app.
- **Finding:** While the service is disconnected or restarting, `LockKeeperForegroundService` acts as a polling fallback (evaluating foreground task every 500ms). When accessibility reconnects, `onDestroy()` explicitly invokes `clearAllSessions()`. No transition produces `Allowed` for a locked app (INV-302, INV-303).

### Agent 3 — Overlay Attacker
- **Surface Evaluated:** Back button, Home button, Recents button, notification shade pull-down, IME/keyboard focus changes, overlay dismissal, WindowManager token errors.
- **Attack Scenario 1:** Press Back or Home on a PIN overlay to dismiss it and access the app behind it.
- **Finding 1:** Overlay dismissal invokes `navigateHome()` (pressing Home), sending the user to the launcher. Crucially, dismissal does **not** call `grantAppSession()`. The app remains locked. Subsequent access demands PIN (INV-305).
- **Attack Scenario 2 (VULN-301):** On devices where background pop-up permission is disabled (MIUI), `windowManager.addView()` throws `BadTokenException` or `SecurityException`.
- **Finding 2:** `attachOverlay()` caught `Exception` and did nothing, leaving the user on the target app.
- **Remediation:** In the `catch` block of `OverlayManager.attachOverlay()`, LockKeeper now immediately triggers `navigateHome()`, failing closed and preventing any interaction with the unprotected window.

### Agent 4 — Settings / System UI Attacker
- **Surface Evaluated:** Force Stop, Clear Data, Clear Cache, Uninstall, Device Admin removal, Accessibility revocation, battery optimization whitelist, Google Play Store app details, OEM Security Centers (MIUI, Vivo, Oppo).
- **Attack Scenario (VULN-304):** Navigate to Google Play Store (`com.android.vending`), search for LockKeeper, and tap "Uninstall".
- **Finding:** Play Store was not registered as a system management package, bypassing `TamperDetectionEngine`.
- **Remediation:** Added `com.android.vending`, `com.vivo.abe`, and `com.coloros.safecenter` to `PACKAGE_INSTALLER_PACKAGES` and `isSystemManagementPackage()`. Any uninstallation attempt targeting LockKeeper triggers a `TamperEvent` requiring Admin Password verification (INV-311).

### Agent 5 — Tamper Attacker
- **Surface Evaluated:** Admin password brute force, lockout persistence, grace period extension via clock manipulation, session replay.
- **Attack Scenario:** Exhaust 5 failed Admin password attempts, then alter the system wall-clock backwards to bypass lockout.
- **Finding:** `TamperAuthorizationController` records both monotonic (`SystemClock.elapsedRealtime()`) and wall-clock timestamps. When clock rollback is detected (`wallNow < settings.updatedAt`), the controller conservatively enforces the full lockout duration. Admin grace strictly uses monotonic elapsed time, completely immune to wall-clock manipulation (INV-307, INV-315).

### Agent 6 — Package / Application Launch Attacker
- **Surface Evaluated:** Launch via Launcher, Recents screen, notification click, deep link (`android.intent.action.VIEW`), share sheet (`android.intent.action.SEND`), file manager, widget.
- **Attack Scenario:** Trigger a locked banking app via an explicit deep link URL or Share intent from another application.
- **Finding:** The Accessibility Service intercepts window state changes based solely on the destination `packageName`, not the launching intent or component alias. Whether launched from the home screen or via a deep link, the engine evaluates the target package against `LockedAppDao` and enforces `RequirePin` (INV-310).

### Agent 7 — Concurrency Attacker
- **Surface Evaluated:** Simultaneous app launches, rapid switching between protected and unprotected apps, authentication during package change, Device Admin deactivation race during evaluation.
- **Attack Scenario:** 20 concurrent threads invoking `engine.evaluate()` on the same locked package while sessions are being granted and revoked.
- **Finding:** All evaluations consistently return `RequirePin`. `ConcurrentHashMap` prevents corruption, and immutable `LockDecision` instances prevent stale decision leakage (INV-309).

### Agent 8 — MIUI / Real-Device Specialist
- **Environment Analyzed:** Xiaomi Mi 10i, Android 12 (API 31), MIUI 14 Global (`M2007J17I`).
- **Surface Evaluated:** MIUI background pop-up window restrictions, aggressive task termination, auto-start management, security center deep routes.
- **Findings & Hardening:**
  1. *Background Pop-up Windows:* On MIUI, apps without "Display pop-up windows while running in the background" cannot render `TYPE_APPLICATION_OVERLAY` when triggered from background services. Remediation VULN-301 guarantees that if `addView()` fails, `navigateHome()` immediately kicks the user back to the launcher, preventing exposure.
  2. *Aggressive Task Kill:* MIUI task killer kills background processes. LockKeeper's `LockKeeperForegroundService` runs with `START_STICKY` and a persistent foreground notification, ensuring OS recreation.
  3. *Settings Traversal:* MIUI's Security Center (`com.miui.securitycenter`) is covered by `TamperDetectionEngine` node inspection.

---

## 3. Vulnerability Summary & Fix Mapping

| ID | Severity | Title | Root Cause | Fix Implemented | Status |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **VULN-301** | **CRITICAL** | Fail-open overlay attachment failure | Exception swallowed in `attachOverlay()` | Added `navigateHome()` in catch block of `OverlayManager` | **FIXED** |
| **VULN-302** | **HIGH** | Unbounded session survival across screen off | No screen off listener; no maximum session duration | Dynamic `ACTION_SCREEN_OFF` receiver in A11y service + 15m `APP_SESSION_MAX_DURATION_MS` | **FIXED** |
| **VULN-303** | **HIGH** | FGS fallback permissive on terminal denials | Missing handling for Blocked/RecoveryRequired/DenyUnknown | Dismiss overlay and execute `homeIntent` in FGS fallback | **FIXED** |
| **VULN-304** | **HIGH** | Play Store & OEM uninstallation bypass | Play Store omitted from package installer list | Added `com.android.vending`, `com.vivo.abe`, `com.coloros.safecenter` | **FIXED** |
| **VULN-305** | **MEDIUM** | Invalidation failure on exit with active overlay | Exit tracking blocked if overlay showing | Decoupled exit tracking from overlay state in `LockKeeperAccessibilityService` | **FIXED** |

---

## 4. Verification Evidence

### Automated Test Count:
- **Wave 1 Baseline:** 126 tests (113 JVM + 13 Flutter)
- **Wave 2 Certification:** 165 tests (148 JVM + 17 Flutter)
- **Wave 3 Certified Final:** **188 tests** (167 JVM + 21 Flutter)
- **Failure Count:** **0**
- **Error Count:** **0**
- **Flutter Analyze Issues:** **0**

### Invariant Verification:
- **INV-301 through INV-319:** Verified in `RuntimeResilienceTest.kt` (19 automated tests) and `runtime_resilience_test.dart` (4 automated tests).
- **Previous Critical Remediations:** Monotonic admin grace, persistent 5-minute lockout, keystore credential storage, and bounded accessibility traversal remain 100% intact with zero regressions.
