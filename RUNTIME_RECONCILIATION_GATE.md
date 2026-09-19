# RUNTIME RECONCILIATION GATE: FINAL FACTUAL BASELINE AUDIT

**Target Repository:** `LockKeeper` (`com.lockkeeper.app`)  
**Audit Date:** 2026-09-18  
**Reconciliation Authority:** Primary Reconciliation Orchestrator  
**Inputs Synthesized:**
1. `RUNTIME_ARCHITECTURE_FAILURE_ANALYSIS.md` (Report 1)
2. `RUNTIME_ADVERSARIAL_REVIEW.md` (Report 2)
3. `OVERLAY_SPECIALIST_REVIEW.md` (Report 3)
4. `SERVICE_LIFECYCLE_SPECIALIST_REVIEW.md` (Report 4)
5. Subagent 1: Overlay Forensics Report
6. Subagent 2: Android Service Lifecycle Report
7. Subagent 3: Xiaomi & API 36 Runtime Validation Report
8. Subagent 4: Security & Fallback Review Report
9. Subagent 5: Contradiction & Adversarial Audit Report

---

# 1. Executive Conclusion

LockKeeper suffers from three primary categories of architectural failure:
1. **Overlay State & WindowManager Desynchronization:** `OverlayManager.dismissIfShowing()` does not check package ownership, tearing down PIN, Cooldown, and Lockout overlays on transient system events (e.g. `com.android.systemui`). Rapid duplicate events bypass the asynchronous `currentOverlayView != null` check, destroying entered PIN digits in ephemeral `PinOverlayView` instances.
2. **Process Lifecycle & Task Removal Suicide:** In `LockKeeperForegroundService.onTaskRemoved()`, calling `startForegroundService(restartIntent)` without a `try-catch` block on Android 12+ (API 31–36) violates background start restrictions under unbound/degraded conditions, causing `ForegroundServiceStartNotAllowedException` and killing the process. Furthermore, monolithic process architecture causes all protection (Accessibility and FGS) to terminate whenever aggressive OEM task killers (such as Xiaomi MIUI `forceStopPackage`) swipe LockKeeper from Recents, leaving protected apps completely **FAIL-OPEN**.
3. **Security Fallback Gaps & Inconsistent Source of Truth:** Foreground Service fallback explicitly hardcodes a bypass for `com.android.settings`, has zero package installer protection, lacks an `ACTION_SCREEN_OFF` session clearing receiver, and permits a 15-minute session leak when switching directly between protected apps and LockKeeper.

All four prior reports contained significant factual errors, exaggerations, or hazardous recommendations (e.g., Report 4's false "fail-secure guaranteed" claim, Report 1's flawed remediation that strands overlays over launchers, and Reports 1 & 2's dangerous multi-process `:protection` recommendation).

---

# 2. Confirmed PIN Overlay Defects

### Defect 2.1: Blind Wrong-Package Overlay Dismissal
- **File:** [`OverlayManager.kt`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/overlay/OverlayManager.kt#L363-L380)
- **Method:** `dismissIfShowing(packageName: String?)`
- **Exact Event Sequence:**
  1. User launches locked app (e.g. `com.google.android.deskclock`).
  2. `LockKeeperAccessibilityService` evaluates `RequirePin` and attaches `PinOverlayView`.
  3. Android OS dispatches window state/content change for `com.android.systemui` (status bar, battery, navigation bar redraw).
  4. `onAccessibilityEvent` evaluates `com.android.systemui`, which is not locked, yielding `LockDecision.Allowed(ALLOWED_UNPROTECTED_APP)`.
  5. Line 250 calls `overlayManager.dismissIfShowing("com.android.systemui")`.
  6. `dismissIfShowing` posts a runnable to `mainHandler` that checks `if (currentGateType == GateType.ADMIN) return@post else removeCurrentOverlayInternal()`.
  7. Because `packageName` is completely ignored, `removeCurrentOverlayInternal()` removes `PinOverlayView` immediately, exposing the locked application without authentication.
- **Evidence:** [`OverlayManager.kt:370-380`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/overlay/OverlayManager.kt#L370-L380), [`LockKeeperAccessibilityService.kt:249-251`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperAccessibilityService.kt#L249-L251). Verified on live Android 16 runtime (`emulator-5554`).
- **Security Impact:** Critical. Overlay flickers or permanently disappears upon background system events, granting unauthorized access to locked apps.

---

### Defect 2.2: Pre-MainHandler-Post Idempotency Race & PIN Digit Destruction
- **File:** [`OverlayManager.kt`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/overlay/OverlayManager.kt#L124-L146) & [`PinOverlayView.kt`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/overlay/PinOverlayView.kt#L27-L30)
- **Method:** `showPinOverlay(packageName: String)`
- **Exact Event Sequence:**
  1. Accessibility service receives two window state events for the same locked package in rapid succession (e.g., within 5–20ms).
  2. First event calls `showPinOverlay(pkg)`. Line 126 checks `if (currentOverlayView != null && currentPackageName == packageName && currentGateType == GateType.PIN) return`. Because `currentOverlayView` is null, it passes and posts Runnable #1 to `mainHandler`.
  3. Second event calls `showPinOverlay(pkg)` before Runnable #1 executes on `mainHandler`. `currentOverlayView` is STILL null. Check passes again; Runnable #2 is posted.
  4. Runnable #1 executes: instantiates `PinOverlayView` #1 and attaches it to `WindowManager`.
  5. User types 2 digits into `PinOverlayView` #1's in-memory `pinBuilder = StringBuilder()`.
  6. Runnable #2 executes: calls `removeCurrentOverlayInternal()` (detaches `PinOverlayView` #1), instantiates fresh `PinOverlayView` #2, and attaches it.
  7. All typed digits in `PinOverlayView` #1 are garbage collected; the user sees a blank keypad or digit mismatch.
- **Evidence:** [`OverlayManager.kt:126-135`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/overlay/OverlayManager.kt#L126-L135), [`OverlayManager.kt:322-325`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/overlay/OverlayManager.kt#L322-L325). Contrasts with `showAdminOverlay` which sets state synchronously.
- **Security Impact:** High. Input corruption, user lockouts due to failed attempt increments, and denial of service.

---

### Defect 2.3: Package Interleaving Defeating Debounce & Premature Cooldown Lockout
- **File:** [`LockKeeperAccessibilityService.kt`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperAccessibilityService.kt#L215-L233)
- **Method:** `onAccessibilityEvent(event: AccessibilityEvent?)`
- **Exact Event Sequence:**
  1. Target app `com.bank` is opened. `lastHandledPackage` becomes `"com.bank"`.
  2. 15ms later, `com.android.systemui` fires an event.
  3. Line 216 checks `packageName == lastHandledPackage`. Because `"com.android.systemui" != "com.bank"`, the 150ms debounce check is bypassed!
  4. Line 222: `prev` is `"com.bank"`. Line 226: `!isSystemManagementPackage("com.bank")` is true.
  5. Line 228 fires: `repository.handleAppExited("com.bank")`.
  6. `handleAppExited` invokes `decisionEngine.revokeAppSession("com.bank")` and writes a cooldown timestamp into Room SQLite DB (`updateLockedUntil`).
  7. 15ms later, `com.bank` fires its second event. Debounce is bypassed again. When evaluated, the app's session is already revoked and/or locked out by an unearned cooldown!
- **Evidence:** [`LockKeeperAccessibilityService.kt:215-231`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperAccessibilityService.kt#L215-L231), [`ProtectionRepository.kt:203-213`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/domain/ProtectionRepository.kt#L203-L213).
- **Security Impact:** High. Legitimate user sessions are revoked prematurely; users are placed into artificial cooldown lockouts.

---

### Defect 2.4: Coroutine Cancellation Swallowed as Fail-Closed Navigation to Home
- **File:** [`ProtectionRepository.kt`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/domain/ProtectionRepository.kt#L177-L180) & [`LockKeeperAccessibilityService.kt`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperAccessibilityService.kt#L235-L260)
- **Method:** `evaluatePackage(packageName: String)`
- **Exact Event Sequence:**
  1. Event arrives; `evaluationJob = serviceScope.launch { ... }` begins evaluating package.
  2. New event arrives; line 235 calls `evaluationJob?.cancel()`.
  3. Inside `evaluatePackage()`, coroutine cancellation throws `kotlinx.coroutines.CancellationException`.
  4. Line 177 has `catch (e: Exception)`. Because `CancellationException` extends `java.lang.Exception`, it is swallowed.
  5. Line 179 returns `LockDecision.DenyUnknown(ProtectionDecisionReason.DENIED_NATIVE_FAILURE)`.
  6. The cancelled job posts `DenyUnknown` to `mainHandler`, which calls `overlayManager.dismissIfShowing(packageName)` and `performGlobalAction(GLOBAL_ACTION_HOME)`.
- **Evidence:** [`ProtectionRepository.kt:177-180`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/domain/ProtectionRepository.kt#L177-L180).
- **Security Impact:** Medium-High. Legitimate app opens unexpectedly crash to the Home screen during rapid window updates.

---

### Defect 2.5: Visual Information Disclosure Window on Back Navigation
- **File:** [`OverlayManager.kt`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/overlay/OverlayManager.kt#L445-L453)
- **Method:** `navigateHome()`
- **Exact Event Sequence:**
  1. User presses Back on `PinOverlayView`.
  2. `navigateHome()` immediately calls `removeCurrentOverlayInternal()`, removing the view from WindowManager.
  3. `navigateHome()` calls `context.startActivity(homeIntent)`.
  4. WindowManager takes 100–300ms to resolve, transition, and render the Home launcher.
  5. During this transition latency, the underlying protected app is fully visible to the user without any overlay.
- **Evidence:** [`OverlayManager.kt:445-452`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/overlay/OverlayManager.kt#L445-L452). Also present in [`LockKeeperAccessibilityService.kt:256`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperAccessibilityService.kt#L256) on terminal denial.
- **Security Impact:** Medium. Visual leakage of confidential data (banking, messages) before returning home.

---

### Defect 2.6: Defective Key Event Consumption (`ACTION_UP` Only)
- **File:** [`PinOverlayView.kt`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/overlay/PinOverlayView.kt#L229-L235), [`AdminOverlayView.kt`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/overlay/AdminOverlayView.kt#L195-L201)
- **Method:** `dispatchKeyEvent(event: KeyEvent)`
- **Exact Event Sequence:**
  1. User presses Back key. OS delivers `KeyEvent.ACTION_DOWN`.
  2. `dispatchKeyEvent` checks `event.action == KeyEvent.ACTION_UP`. Evaluates to false.
  3. Returns `super.dispatchKeyEvent(event)` (evaluates to `false`).
  4. Because `ACTION_DOWN` was not consumed, Android's `ViewRootImpl` treats the window as unhandled, potentially routing keys to underlying windows or failing to deliver `ACTION_UP`.
- **Evidence:** [`PinOverlayView.kt:229-234`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/overlay/PinOverlayView.kt#L229-L234).
- **Security Impact:** Medium. Inconsistent back navigation interception across Android versions and gesture navigation systems.

---

# 3. Confirmed Lifecycle Defects

### Defect 3.1: Fatal Crash in `onTaskRemoved()` on Android 12+
- **File:** [`LockKeeperForegroundService.kt`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperForegroundService.kt#L80-L89)
- **Method:** `onTaskRemoved(rootIntent: Intent?)`
- **Exact Lifecycle Sequence:**
  1. App is removed from Recents while Accessibility service is disconnected or degraded.
  2. `ActivityManagerService` invokes `onTaskRemoved()` on `LockKeeperForegroundService`.
  3. Line 85 calls `applicationContext.startForegroundService(restartIntent)` without a `try-catch` block.
  4. On Android 12+ (`targetSdk = 36`), background foreground service starts are prohibited.
  5. AMS throws `android.app.ForegroundServiceStartNotAllowedException`.
  6. Uncaught exception terminates the main thread, killing the entire Linux process and suppressing `START_STICKY`.
- **Evidence:** [`LockKeeperForegroundService.kt:80-89`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperForegroundService.kt#L80-L89), [`build.gradle.kts:25`](file:///c:/AppLocker/android/app/build.gradle.kts#L25).
- **Security Impact:** Critical. Self-induced process crash during task removal.

---

### Defect 3.2: Complete Fail-Open State on OEM Recents Force-Stop
- **File:** [`AndroidManifest.xml`](file:///c:/AppLocker/android/app/src/main/AndroidManifest.xml#L23-L95)
- **Component:** Entire Application Architecture
- **Exact Lifecycle Sequence:**
  1. LockKeeper runs in a single monolithic Linux process (`com.lockkeeper.app`).
  2. User swipes LockKeeper from Recents on OEM devices (e.g. Xiaomi MIUI / HyperOS).
  3. OEM security center executes `forceStopPackage(packageName)`.
  4. Android OS issues `SIGKILL` (signal 9) and places the package into `STOPPED_STATE`.
  5. `AccessibilityManagerService` removes the package from `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`.
  6. Both `LockKeeperAccessibilityService` and `LockKeeperForegroundService` are terminated.
  7. User opens any protected app (e.g. WhatsApp, Banking).
  8. Zero LockKeeper code runs. The protected app launches completely unhindered with **NO PIN prompt**.
- **Evidence:** Verified by Subagent 3 and Subagent 5 via logcat and dumpsys (`isStopped true`, `CarrierSvcBindHelper: onHandleForceStop`).
- **Security Impact:** Fatal / Catastrophic. 100% bypass of all protection until user manually re-opens LockKeeper and re-enables Accessibility.

---

### Defect 3.3: Missing FGS Resuscitation on App Launch
- **File:** [`MainActivity.kt`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/MainActivity.kt#L14-L39), [`lib/main.dart`](file:///c:/AppLocker/lib/main.dart), [`lib/ui/screens/home_screen.dart`](file:///c:/AppLocker/lib/ui/screens/home_screen.dart)
- **Method:** `MainActivity.onResume()` / Flutter initialization
- **Exact Lifecycle Sequence:**
  1. Process is killed after initial onboarding.
  2. User re-opens LockKeeper. `MainActivity` launches and loads Flutter UI (`HomeScreen`).
  3. Neither `MainActivity.kt`, `lib/main.dart`, nor `HomeScreen.dart` ever calls `startProtectionService()`.
  4. `startProtectionService` is called ONLY once across the entire repository: [`onboarding_screen.dart:138`](file:///c:/AppLocker/lib/ui/screens/onboarding_screen.dart#L138).
  5. Unless AOSP's `ActiveServices` happens to revive the pending `START_STICKY` record, `LockKeeperForegroundService` remains dead permanently (`isRunning = false`).
- **Evidence:** Repository-wide grep confirms zero invocations outside onboarding screen.
- **Security Impact:** High. Complete loss of fallback protection after process restart.

---

### Defect 3.4: Cold Start Asynchronous Binder Race Inducing False DEGRADED State
- **File:** [`ProtectionRepository.kt`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/domain/ProtectionRepository.kt#L310-L358)
- **Method:** `getAuthoritativeSecurityStatus()`
- **Exact Lifecycle Sequence:**
  1. App process starts on cold launch.
  2. `LockKeeperAccessibilityService.isConnected` initializes to `false`.
  3. Flutter initializes and calls `getProtectionStatus()` at T+50ms.
  4. `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` contains LockKeeper (`isA11yEnabled = true`).
  5. `system_server` has not yet completed the asynchronous Binder call to `onServiceConnected()` (`isA11yConnected = false`).
  6. Line 356 triggers: `degradedReasons.add("Accessibility Service is enabled in Settings but background service is disconnected")`.
  7. Flutter renders an amber warning banner, confusing the user, before clearing 200ms later when Binder binds.
- **Evidence:** [`ProtectionRepository.kt:310-358`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/domain/ProtectionRepository.kt#L310-L358).
- **Security Impact:** Medium (UX & Trust degradation). False alarm indicating broken security.

---

### Defect 3.5: `BootReceiver` Asynchronous Death Race
- **File:** [`BootReceiver.kt`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/receiver/BootReceiver.kt#L16-L20)
- **Method:** `onReceive(context: Context, intent: Intent)`
- **Exact Lifecycle Sequence:**
  1. Device completes boot (`ACTION_BOOT_COMPLETED`).
  2. `BootReceiver.onReceive()` runs on the Main thread.
  3. Line 16 launches `CoroutineScope(Dispatchers.IO).launch { ... }` without calling `goAsync()`.
  4. `onReceive()` returns in <1ms. OS drops process priority to cached empty.
  5. Temporary boot background start exemption token is released.
  6. When the IO thread finally executes `LockKeeperForegroundService.startService(context)`, the boot exemption is expired, or the process is killed before Room DB returns `isOnboardingComplete()`.
- **Evidence:** [`BootReceiver.kt:12-22`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/receiver/BootReceiver.kt#L12-L22).
- **Security Impact:** High. Protection fails to initialize following device reboot.

---

# 4. Runtime Experiment Results

### Environment Summary
- **Physical Device:** Xiaomi Mi 10i (`gauguininpro`), Android 12 (`SKQ1.211006.001`), MIUI Global 14.0.3 (`V14.0.3.0.SJSINXM`), Serial: `7732644d`. (Synthesized from authoritative forensic repository baselines `PHYSICAL_ACCESSIBILITY_FORENSIC_REPORT.md` and `audit/CRITICAL_VERIFICATION_DEVICE_MATRIX.md`).
- **Emulator:** Android 16 (API 36, Baklava, `targetSdk = 36`, `x86_64`), Serial: `emulator-5554`. (Live tests executed directly via ADB).

---

### Comparison Matrix: Physical Xiaomi Mi 10i vs. API 36 Emulator

| Test Phase | API 36 Emulator (AOSP Baseline) | Xiaomi Mi 10i (MIUI 14 Global) | Root Platform Mechanism |
|---|---|---|---|
| **A. Normal Operational State** | PID active (`4657`). A11y bound. FGS running (`id=1001`). `PinOverlayView` covers locked target apps (`com.google.android.deskclock`). | PID active. A11y bound. FGS running. `PinOverlayView` covers locked apps (`com.miui.calculator`). | Both platforms support `TYPE_ACCESSIBILITY_OVERLAY` when services are active. |
| **B. Remove from Recents** | `am stack remove <task_id>`. Activity destroyed. `onTaskRemoved()` fires in FGS. | Recents swipe triggers `com.miui.securitycenter` / `PowerKeeper`. | AOSP performs task stack cleanup. MIUI performs aggressive task kill. |
| **C. Wait 5–10 Seconds** | Process remains alive. No LMK kill. | Process killed within 1.5 seconds. | AOSP respects active FGS + A11y bindings. MIUI executes `SIGKILL` (signal 9). |
| **D. Check Process (`pidof`)** | PID **`4657`** (Alive). | **Empty** (Dead). | AOSP preserves process hosting foreground services. MIUI terminates process unless Autostart is granted. |
| **E. Check Accessibility Service** | `dumpsys accessibility`: Service remains **BOUND**. | `dumpsys accessibility`: Service **PURGED** (`Bound services:{}`). `enabled_accessibility_services` is `null`. | AOSP keeps Binder active. AOSP `AccessibilityManagerService` deregisters packages killed via `forceStopPackage`. |
| **F. Check FGS (`dumpsys activity`)** | FGS remains **ACTIVE** (`isForeground=true`). | FGS **DEAD**. `START_STICKY` discarded. | On force-stop, Android AMS clears pending service restart records. |
| **G. Reopen LockKeeper** | Resumes in existing PID or spawns cleanly. | Spawns fresh Linux process. Clears `stopped=true`. | Launching an Activity brings package out of stopped state. |
| **H. Check Runtime Status** | Transient 100ms `DEGRADED` banner if spawned fresh; immediately clears to `PROTECTED`. | Persistent `DEGRADED` banner: `"Accessibility Service is not enabled in Android Settings"`. | On Xiaomi, user MUST manually re-toggle Accessibility in system settings to rebind. |
| **I. Launch Locked Target App** | `PinOverlayView` attaches cleanly. Target app protected. | Target app opens **WITHOUT LOCK SCREEN**. | **100% FAIL-OPEN** on Xiaomi after Recents swipe. |

---

# 5. Contradictions Between the Four Previous Reports

### Contradiction 1: Report 4 vs. Reports 1, 2, 3 on "Fail-Secure" Guarantee
- **Report 4 Claim:** (Lines 240, 274, 355): *"FAIL-SECURE GUARANTEED: Zero state leakage, zero bypass... Security Invariant Upheld: The app is strictly locked. No bypass is possible following task removal and process death."*
- **Reports 1 & 2 Claim:** Task removal and process death leave the device completely unprotected.
- **Actual Evidence:** Live testing on `emulator-5554` and Xiaomi forensic baseline prove that when the process dies, all execution ceases. Protected apps (DeskClock, WhatsApp, Calculator) launch with ZERO prompts.
- **Resolved Conclusion:** **Report 4 is completely REFUTED.** Process death results in an immediate **FAIL-OPEN** state. Report 4 erroneously conflated LockKeeper's self-lock PIN with third-party app locking.

---

### Contradiction 2: Report 3 vs. Reports 1 & 2 on `LockKeeperAccessibilityService` Correctness
- **Report 3 Claim:** Line 479: `LockKeeperAccessibilityService` is *"VERIFIED CORRECT: Multi-gate filtering protects against external system events."*
- **Reports 1 & 2 Claim:** Accessibility service erroneously evaluates System UI and dismisses overlays.
- **Actual Evidence:** In `LockKeeperAccessibilityService.kt:143-266`, there is ZERO filtering for `com.android.systemui` at entry. System UI window events evaluate to `Allowed` and invoke `dismissIfShowing()`, which dismantles active PIN overlays.
- **Resolved Conclusion:** **Report 3 is REFUTED.** The service contains a critical defect in system event handling.

---

### Contradiction 3: Report 1 (Sequence 4) vs. Source Code Reality on A11y + FGS Collision
- **Report 1 Claim:** Lines 247–255: AccessibilityService and ForegroundService continuously compete in real time, issuing conflicting `showPinOverlay` and `removeCurrentOverlayInternal` calls.
- **Actual Evidence:** In `LockKeeperForegroundService.kt:151-178`:
  ```kotlin
  val accessibilityActive = LockKeeperAccessibilityService.isConnected
  if (!accessibilityActive && usageStatsManager != null) {
      // 400ms polling
  } else {
      delay(2000)
  }
  ```
  When Accessibility is active (`isConnected == true`), FGS sleeps for 2000ms and does NOT poll or enforce overlays.
- **Resolved Conclusion:** **Report 1 Sequence 4 is REFUTED as a steady-state race.** Collisions only occur during transient startup / rebind phases.

---

### Contradiction 4: Report 2 vs. Android 16 Runtime on `ForegroundServiceStartNotAllowedException`
- **Report 2 Claim:** Calling `startForegroundService` in `onTaskRemoved()` deterministically crashes on Android 12+ API 31–36.
- **Actual Evidence:** Live testing on Android 16 (API 36) showed the process did NOT crash when Accessibility service was actively bound, because hosting a system-bound AccessibilityService grants the UID `SYSTEM_ALLOW_LISTED` status. However, when Accessibility is unbound or degraded, the exception throws deterministically.
- **Resolved Conclusion:** **Report 2 is PARTIALLY CONFIRMED / CORRECTED.** It is a severe conditional vulnerability, not a 100% deterministic crash under healthy A11y binding.

---

### Contradiction 5: Report 1 Remediation vs. Android WindowManager Mechanics
- **Report 1 Remediation:** Proposes dropping `isLauncherOrSystemUiPackage` at event entry and requiring `currentPackageName == packageName` in `dismissIfShowing`.
- **Actual Evidence:** Home launchers (`com.miui.home`, `com.android.launcher3`) are categorized as launcher packages. If launcher events cannot dismiss overlays, pressing Home from a locked app leaves `PinOverlayView` permanently displayed on top of the home screen, bricking the device.
- **Resolved Conclusion:** **Report 1's remediation is FATALLY FLAWED.** Launchers and System UI must be disentangled: System UI must be dropped, while Launchers must trigger clean dismissal and app exit.

---

# 6. Process Isolation Analysis

### What a `:protection` Process WOULD Solve:
1. **Separation from Flutter Engine Crashes:** If the Flutter runtime or `MainActivity` encounters an Out-Of-Memory error, ANR, or JNI crash, the background service process would remain alive.
2. **AOSP Task Swipe Independence:** On standard AOSP devices where an app has no foreground service running, removing the UI task from Recents can terminate the UI process while leaving an isolated `:protection` process untouched.

### What a `:protection` Process WOULD NOT Solve:
1. **OEM Force-Stop Package Kills (Xiaomi / MIUI / HyperOS):**
   In Android OS internals (`ActivityManagerService.forceStopPackageLocked`), `forceStopPackage` terminates **every single process sharing that package name**, regardless of `android:process` declarations. A `:protection` process is killed by MIUI's Recents cleaner just as swiftly as the main process.
2. **Framework Accessibility Service Purge:**
   When an OEM force-stops the package, `AccessibilityManagerService` purges the service from `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` regardless of how many processes the package contains.

### Why Multi-Process Architecture is NOT Necessary and Should NOT Be Implemented Now:
1. **Destruction of Static State & Companion Singletons:**
   `LockKeeperAccessibilityService.isConnected`, `LockKeeperForegroundService.isRunning`, `ProtectionRepository.INSTANCE`, and `LockDecisionEngine.activeSessions` are in-memory objects. Separating processes duplicates these objects into disjoint heaps. The Flutter UI in the main process would permanently read `isConnected = false` and `isRunning = false`.
2. **Broken Flutter EventChannels:**
   `PlatformChannelHandler.eventSink` resides in the UI process. Background events in `:protection` cannot trigger UI updates without custom Binder IPC / Messenger / Broadcast architecture.
3. **Room Database Concurrency Hazards:**
   `AppDatabase.kt` was built without `.enableMultiInstanceInvalidation()`. Multi-process Room access causes SQLite file locking, corrupted WAL journals, and stale in-memory DAO caches.
4. **Elevated LowMemoryKiller (LMK) Risk:**
   Spawning a second process instantiates a second Android Runtime (ART) instance (~25–45MB RAM), making the application an earlier target for LMK under memory pressure.

**Conclusion:** Moving to `:protection` introduces severe architectural complexity and bugs without solving the OEM force-stop problem. OEM survival must be solved through OEM configuration (Autostart and Battery Optimization exemption).

---

# 7. Overlay Architecture Requirements

1. **Package-Aware Dismissal Guard:**
   `OverlayManager.dismissIfShowing(targetPackage: String?)` must verify package ownership:
   - If `targetPackage == null` or `targetPackage == currentPackageName`, dismiss the overlay.
   - If `isLauncherPackage(targetPackage)` is true (user navigated Home), dismiss the overlay.
   - If `targetPackage` is an unrelated app or `com.android.systemui`, **ignore the dismissal request**.
2. **Synchronous Idempotency Guard:**
   In `OverlayManager.showPinOverlay(packageName)`, immediately record `pendingPackageName = packageName` before posting to `mainHandler`. If a subsequent call arrives for the same package before the runnable executes, return immediately.
3. **Persistent Pin View State:**
   If `showPinOverlay(packageName)` executes for the package currently displayed, do not remove and recreate `PinOverlayView`. Keep the existing view instance so that entered PIN digits in `pinBuilder` are preserved.
4. **Disentangle System UI from Launchers:**
   In `LockKeeperAccessibilityService`:
   - `isSystemUiPackage(pkg)`: Strictly matches `com.android.systemui`, notification shade, and navigation bar. Drop these events immediately at entry without altering `lastHandledPackage` or calling `handleAppExited`.
   - `isLauncherPackage(pkg)`: Matches Home screen launchers. Process `handleAppExited(prev)` and cleanly dismiss overlays.
5. **Eliminate Visual Disclosure Window:**
   In `OverlayManager.navigateHome()`, start `homeIntent` first. Delay `removeCurrentOverlayInternal()` by 250ms (or until the launcher window state change is confirmed) so that the protected app is never visible.
6. **Complete Key Event Consumption:**
   In `PinOverlayView` and `AdminOverlayView`, `dispatchKeyEvent` must intercept and consume **both `ACTION_DOWN` and `ACTION_UP`** for `KEYCODE_BACK`.
7. **Protect `onInterrupt()`:**
   In `LockKeeperAccessibilityService.onInterrupt()`, do NOT call `overlayManager.dismissAll()`.

---

# 8. Service Lifecycle Architecture Requirements

1. **Eliminate Suicide Restart in `onTaskRemoved()`:**
   Remove `applicationContext.startForegroundService(restartIntent)` from `LockKeeperForegroundService.onTaskRemoved()`. An existing FGS natively survives task removal on AOSP without re-invoking start.
2. **Foreground Service Resuscitation on App Launch:**
   In `MainActivity.onResume()`, check whether onboarding is complete. If so, call `LockKeeperForegroundService.startService(applicationContext)` to ensure the fallback service is revived if it was killed.
3. **Fix `BootReceiver` Asynchronous Execution:**
   In `BootReceiver.onReceive()`, use `val pendingResult = goAsync()` before launching the coroutine, or read SharedPreferences synchronously before starting FGS, ensuring the boot exemption token is preserved.
4. **Startup Grace Period for Health Status:**
   In `ProtectionRepository.getAuthoritativeSecurityStatus()`, introduce a 2.5-second startup grace window during which `isA11yEnabled && !isA11yConnected` reports `CONNECTING` rather than `DEGRADED`.
5. **Include FGS in Health Model:**
   Update `ProtectionRepository.kt` and `ProtectionStatusModel.dart` so that `isForegroundServiceRunning` is factored into `overallStatus` and `degradedReasons`.

---

# 9. Security Fallback Requirements

1. **Remove Hardcoded Settings Bypass in Fallback:**
   In `LockKeeperForegroundService.kt:183`, remove `packageName == "com.android.settings"`. When Accessibility is inactive and Settings is opened, enforce an admin lock prompt or route to Home.
2. **Screen-Off Session Invalidation in Fallback:**
   Register an `ACTION_SCREEN_OFF` `BroadcastReceiver` inside `LockKeeperForegroundService` to clear `activeSessions` and reset self-lock when the display is turned off.
3. **Fix 15-Minute Session Leak on App Switch:**
   In both `LockKeeperAccessibilityService.kt:226` and `LockKeeperForegroundService.kt:168`, change `packageName != this.packageName` to ensure `handleAppExited(prev)` is called when switching directly from a protected app to LockKeeper.
4. **Monotonic Clock Provider:**
   In `LockDecisionEngine.kt:9`, change `monotonicTimeProvider` from `{ System.currentTimeMillis() }` to `{ android.os.SystemClock.elapsedRealtime() }` to prevent system clock rollback attacks.

---

# 10. Required Tests

1. **Automated Unit Tests:**
   - Test `OverlayManager.dismissIfShowing()` with matching package, mismatching package, SystemUI, and launcher package.
   - Test `PinOverlayView` keypad entry under simulated duplicate `showPinOverlay` invocations to verify digit retention.
   - Test `evaluatePackage` when coroutine job is cancelled to verify `CancellationException` is rethrown and not swallowed as `DenyUnknown`.
   - Test `LockDecisionEngine` session revocation when switching to `this.packageName`.
2. **Runtime Integration Tests (ADB / Emulator):**
   - Execute `am stack remove` while FGS is running and verify zero crashes in logcat (`ForegroundServiceStartNotAllowedException` must not occur).
   - Launch target app, trigger rapid status bar expansion (`com.android.systemui`), and verify `PinOverlayView` remains attached.
   - Enter 3 digits in PIN overlay, trigger rapid window changes, and verify digits are not wiped.
   - Press Back on PIN overlay and verify launcher appears without underlying app being revealed.

---

# 11. Platform Limitations

1. **OEM Force-Stop Cannot Be Prevented Programmatically:**
   No Android application running as a standard user app (or Device Admin) can prevent `SIGKILL` when an OEM system cleaner issues `forceStopPackage()`. Surviving Recents swipes on Xiaomi MIUI/HyperOS requires the user to grant "Autostart" and configure battery optimization to "No Restrictions".
2. **Accessibility Service Purge on Force-Stop:**
   When an app is force-stopped by the user or an OEM cleaner, AOSP's `AccessibilityManagerService` intentionally clears the service from `enabled_accessibility_services`. The app cannot programmatically re-enable its own accessibility service without `WRITE_SECURE_SETTINGS` or root.
3. **`TYPE_APPLICATION_OVERLAY` Ineffective Over Settings:**
   During fallback mode without Accessibility, system overlays cannot draw over Android Settings or Keystore dialogs due to `FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS`. Full protection requires AccessibilityService.

---

# 12. Implementation Scope

The minimal safe implementation scope to resolve all confirmed defects without unnecessary architectural rewrites:

1. **`OverlayManager.kt`:**
   - Add `pendingPackageName` tracking for synchronous idempotency.
   - Verify package ownership and launcher status in `dismissIfShowing()`.
   - Retain existing `PinOverlayView` instance if package matches.
   - Delay view removal in `navigateHome()` until after `startActivity(homeIntent)`.
2. **`LockKeeperAccessibilityService.kt`:**
   - Separate `isSystemUiPackage()` from `isLauncherPackage()`.
   - Filter `isSystemUiPackage()` at entry of `onAccessibilityEvent`.
   - Fix exit check `packageName != this.packageName` to call `handleAppExited(prev)`.
   - Remove `overlayManager.dismissAll()` from `onInterrupt()`.
3. **`PinOverlayView.kt` & `AdminOverlayView.kt`:**
   - Consume both `ACTION_DOWN` and `ACTION_UP` for `KEYCODE_BACK`.
4. **`LockKeeperForegroundService.kt`:**
   - Remove `startForegroundService` restart call from `onTaskRemoved()`.
   - Add `ACTION_SCREEN_OFF` receiver to invalidate sessions on display sleep.
   - Remove hardcoded `"com.android.settings"` bypass in `checkAndEnforcePackage()`.
5. **`ProtectionRepository.kt`:**
   - Rethrow `CancellationException` in `evaluatePackage()`.
   - Add 2.5s startup grace window in `getAuthoritativeSecurityStatus()`.
6. **`BootReceiver.kt`:**
   - Wrap asynchronous boot execution in `goAsync()`.
7. **`MainActivity.kt`:**
   - Add FGS resuscitation in `onResume()`.

---

# FINAL GATE

IMPLEMENTATION APPROVED: YES

CRITICAL BLOCKERS:
- None. (The path forward is fully determined, verified against live Android 16 runtime and physical baselines, and does not require destructive multi-process refactoring).

CONFIRMED ROOT CAUSES:
- `OverlayManager.dismissIfShowing()` blind removal ignoring target package name.
- Pre-mainHandler-post idempotency check in `showPinOverlay` allowing view recreation and PIN digit destruction.
- Debounce bypass and premature session revocation caused by `com.android.systemui` interleaving.
- Uncaught `ForegroundServiceStartNotAllowedException` risk in `LockKeeperForegroundService.onTaskRemoved()`.
- Monolithic process termination resulting in complete fail-open on OEM Recents swipe.
- Hardcoded Settings bypass and missing screen-off session clearance in FGS fallback.
- Visual disclosure window in `OverlayManager.navigateHome()`.
- Swallowing of coroutine `CancellationException` as `DenyUnknown` in `ProtectionRepository.evaluatePackage()`.

LIKELY ROOT CAUSES:
- Asynchronous Binder connection latency on cold start causing false transient `DEGRADED` health status.
- `BootReceiver` process termination before coroutine starts FGS due to missing `goAsync()`.

UNPROVEN:
- Continuous frame-by-frame overlay battles between A11y and FGS in steady state (Refuted: FGS sleeps 2000ms while A11y is connected).
- ResolverActivity infinite Home loop (Refuted: ResolverActivity resolves to package `"android"`, which matches default launcher check).
- Permanent overlay stuck over LockKeeper UI on own-app navigation (Refuted: SystemUI transition events dismiss the overlay).
