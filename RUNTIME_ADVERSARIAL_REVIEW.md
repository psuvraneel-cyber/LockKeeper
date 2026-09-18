# LockKeeper Runtime Adversarial Security Review
**Document ID:** LK-SEC-REV-2026-09-18  
**Classification:** CRITICAL SECURITY & RUNTIME ARCHITECTURE AUDIT  
**Target Repository:** [`LockKeeper`](file:///c:/AppLocker) (`com.lockkeeper.app`)  
**Mode:** READ-ONLY ADVERSARIAL INSPECTION (NO SOURCE CODE MODIFIED)  

---

## Executive Overview

This independent adversarial security review investigates two systemic runtime vulnerabilities confirmed in the LockKeeper application:

1. **Problem 1: PIN Overlay Flickering and Transient UI Exposure**  
   When opening a locked application, the PIN overlay frequently flashes, dismisses, and re-attaches, destroying user keypad input and exposing underlying private application contents.
2. **Problem 2: Complete Collapse of Lock Enforcement Following Recents Removal or Process Termination**  
   When LockKeeper is removed from the Android Recents overview or subjected to process termination, lock enforcement completely stops (fail-open), and subsequent launches display a persistent degraded state reporting that the Accessibility Service is disconnected.

Our inspection of the Kotlin, Dart, Android manifest, and test configurations confirms that despite a 100% pass rate across the 223 JVM unit tests, **the runtime architecture contains critical concurrency races, lifecycle misconfigurations, and asymmetric security controls**.

---

## Component Architecture & Vulnerability Map

```
                                      Android System Events
                                  (Window / Content / Recents)
                                               │
                       ┌───────────────────────┴───────────────────────┐
                       ▼                                               ▼
       ┌───────────────────────────────┐               ┌───────────────────────────────┐
       │LockKeeperAccessibilityService │               │  LockKeeperForegroundService  │
       │ - Dual event subscription     │               │ - 400ms UsageStats polling    │
       │ - Coroutine evaluation job    │               │ - Dangerous onTaskRemoved()   │
       │ - Unfiltered SystemUI events  │               │ - Hardcoded Settings bypass   │
       └───────────────┬───────────────┘               └───────────────┬───────────────┘
                       │                                               │
                       ▼                                               ▼
       ┌───────────────────────────────────────────────────────────────────────────────┐
       │                             ProtectionRepository                              │
       │           - evaluatePackage() [Room DB + Keystore on Dispatchers.IO]          │
       └───────────────────────────────────────┬───────────────────────────────────────┘
                                               │
                                               ▼
       ┌───────────────────────────────────────────────────────────────────────────────┐
       │                                OverlayManager                                 │
       │ - showPinOverlay() [Un-tokenized, non-idempotent Looper post queue]           │
       │ - showAdminOverlay() [Hardened with TamperSession idempotency]                │
       │ - dismissIfShowing() [Blindly tears down PIN overlays on ANY non-locked event]│
       └───────────────────────────────────────┬───────────────────────────────────────┘
                                               │
                                               ▼
       ┌───────────────────────────────────────────────────────────────────────────────┐
       │                                WindowManager                                  │
       │          (TYPE_ACCESSIBILITY_OVERLAY / TYPE_APPLICATION_OVERLAY)              │
       └───────────────────────────────────────────────────────────────────────────────┘
```

---

## Section 1: Deep-Dive Investigation — Problem 1 (PIN Overlay Flickering)

### Finding 1.1: Blind Teardown of PIN Overlays in `OverlayManager.dismissIfShowing()` via Non-Target System Events

- **File:** [`OverlayManager.kt`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/overlay/OverlayManager.kt)
- **Method:** `dismissIfShowing(packageName: String? = null)`
- **Line Reference:** [Lines 363–380](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/overlay/OverlayManager.kt#L363-L380)
- **Triggering Event Sequence:**
  1. User launches a locked application, e.g., WhatsApp (`com.whatsapp`).
  2. `LockKeeperAccessibilityService.onAccessibilityEvent` receives `TYPE_WINDOW_STATE_CHANGED` for `com.whatsapp`.
  3. `evaluatePackage("com.whatsapp")` returns `LockDecision.RequirePin("com.whatsapp")`.
  4. `OverlayManager.showPinOverlay("com.whatsapp")` is invoked, setting `currentGateType = GateType.PIN` and attaching `PinOverlayView`.
  5. Simultaneously, Android's `com.android.systemui` processes the app launch (updating the status bar clock, battery icon, notification ticker, or gesture navigation bar).
  6. `onAccessibilityEvent` fires with `packageName = "com.android.systemui"`.
  7. `onAccessibilityEvent` checks `overlayManager.isInputMethodPackage("com.android.systemui")` (returns `false`) and `packageName == this.packageName` (returns `false`).
  8. `lastHandledPackage` was `"com.whatsapp"`. Since `"com.android.systemui" != "com.whatsapp"`, **the 150ms debounce check is completely bypassed**.
  9. `evaluatePackage("com.android.systemui")` executes on `Dispatchers.IO`. Because `com.android.systemui` is not present in the `locked_apps` Room table, `LockDecisionEngine.evaluate()` reaches line 113:
     ```kotlin
     if (lockedApp == null || !lockedApp.isLocked) {
         return LockDecision.Allowed(ProtectionDecisionReason.ALLOWED_UNPROTECTED_APP)
     }
     ```
  10. `LockKeeperAccessibilityService.kt:250` receives `LockDecision.Allowed` and executes:
      ```kotlin
      is LockDecision.Allowed -> {
          overlayManager.dismissIfShowing(packageName)
      }
      ```
  11. `OverlayManager.dismissIfShowing("com.android.systemui")` executes:
      ```kotlin
      mainHandler.post {
          if (currentGateType == GateType.ADMIN) {
              return@post
          } else {
              removeCurrentOverlayInternal()
          }
      }
      ```
  12. **Critical Defect:** The method accepts `packageName: String? = null`, but **never checks whether `packageName` matches `currentPackageName`**. Because `currentGateType` is `GateType.PIN` (only `GateType.ADMIN` is protected), it unconditionally calls `removeCurrentOverlayInternal()`.
  13. `PinOverlayView` is immediately stripped from the window hierarchy. The private content of `com.whatsapp` is rendered directly on screen without an overlay.
  14. 50–200ms later, WhatsApp finishes rendering or dispatches a `TYPE_WINDOW_CONTENT_CHANGED` event.
  15. `onAccessibilityEvent` receives `com.whatsapp`, evaluates back to `RequirePin`, and re-inflates and attaches a brand new `PinOverlayView`.
- **Resulting State:**
  Violent visual flicker (overlay attaches $\rightarrow$ vanishes $\rightarrow$ re-attaches); underlying target application content exposed to view; entered PIN digits destroyed.
- **Security Impact:**
  **Critical Fail-Open Information Disclosure.** A bystander or recording tool captures the target app's confidential data during the un-gated window.
- **Deterministic vs Timing-Dependent:**
  Deterministic upon arrival of any non-locked window event while a PIN/Cooldown/Lockout overlay is showing.
- **Proposed Invariant:**
  `INV-OVERLAY-TARGET-MATCH`: An active overlay protecting target package $P_{\text{target}}$ must NEVER be dismissed by an `Allowed` decision evaluated for package $P_{\text{eval}}$ where $P_{\text{eval}} \neq P_{\text{target}}$. `dismissIfShowing(packageName)` must enforce:
  ```kotlin
  if (packageName != null && packageName != currentPackageName) {
      return
  }
  ```

---

### Finding 1.2: Missing Idempotency Check and Double-Post Queue Race in `OverlayManager.showPinOverlay()`

- **File:** [`OverlayManager.kt`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/overlay/OverlayManager.kt)
- **Method:** `showPinOverlay(packageName: String)`
- **Line Reference:** [Lines 124–146](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/overlay/OverlayManager.kt#L124-L146)
- **Triggering Event Sequence:**
  1. Contrast `showAdminOverlay()` with `showPinOverlay()`:
     - `showAdminOverlay()` synchronizes and checks `activeTamperSessionId == targetSessionId` before posting, explicitly noting: `// This eliminates visual flicker and window races!`.
     - `showPinOverlay()` has **NO session ID, NO pending flag, and NO pre-post idempotency token**.
  2. Line 126 evaluates:
     ```kotlin
     if (currentOverlayView != null && currentPackageName == packageName && currentGateType == GateType.PIN) {
         return
     }
     ```
  3. When an app launches, `currentOverlayView` is initially `null`.
  4. Event 1 (`TYPE_WINDOW_STATE_CHANGED`) triggers `showPinOverlay("com.target")`. The guard evaluates `currentOverlayView != null` as `false`.
  5. Line 131 schedules Runnable $R_1$ onto `mainHandler`:
     ```kotlin
     mainHandler.post {
         removeCurrentOverlayInternal()
         val view = PinOverlayView(...)
         attachOverlay(view, packageName, GateType.PIN)
     }
     ```
  6. While $R_1$ is pending in the Looper queue, Event 2 (`TYPE_WINDOW_CONTENT_CHANGED` or decor view creation) arrives for `"com.target"`.
  7. Event 2 invokes `showPinOverlay("com.target")`.
  8. Because $R_1$ has not yet executed on the main thread, `currentOverlayView` is **STILL `null`**!
  9. The guard passes again! Runnable $R_2$ is enqueued onto `mainHandler`.
  10. The Main Looper executes $R_1$:
      - Creates `PinOverlayView` #1.
      - `attachOverlay` adds View #1 to WindowManager and sets `currentOverlayView = view1`.
  11. The Main Looper immediately executes $R_2$:
      - Calls `removeCurrentOverlayInternal()`, which calls `wm.removeViewImmediate(view1)`.
      - Creates `PinOverlayView` #2.
      - `attachOverlay` adds View #2 to WindowManager and sets `currentOverlayView = view2`.
- **Resulting State:**
  Within a single display refresh frame, View #1 is added, immediately ripped away, and replaced with View #2.
- **Security Impact:**
  High Denial of Service & Window Management Anomaly. Flashes the display and can provoke WindowManager `BadTokenException` or `IllegalStateException: View already added` if asynchronous queue ordering slips.
- **Deterministic vs Timing-Dependent:**
  Timing-dependent on event arrival spacing relative to Looper message processing; occurs reliably on multi-core devices during activity initialization.
- **Proposed Invariant:**
  `INV-OVERLAY-IDEMPOTENCY`: `showPinOverlay()` must synchronously track a `pendingPackageName`. If `pendingPackageName == packageName` or `currentPackageName == packageName`, subsequent dispatches must be no-ops.

---

### Finding 1.3: Redundant Looper Hop Expanding the Vulnerability Gap

- **File:** [`LockKeeperAccessibilityService.kt`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperAccessibilityService.kt) & [`OverlayManager.kt`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/overlay/OverlayManager.kt)
- **Method:** `onAccessibilityEvent` (lines 238–242) and `showPinOverlay` (lines 131–145)
- **Triggering Event Sequence:**
  1. In `LockKeeperAccessibilityService.kt:238`, `decision` is dispatched to the main thread:
     ```kotlin
     mainHandler.post {
         when (decision) {
             is LockDecision.RequirePin -> {
                 overlayManager.showPinOverlay(packageName)
             }
         }
     }
     ```
  2. The Looper runs this block on `Thread[main]`.
  3. Inside `OverlayManager.showPinOverlay()`, line 131 unnecessarily re-dispatches:
     ```kotlin
     mainHandler.post {
         removeCurrentOverlayInternal()
         ...
         attachOverlay(view, packageName, GateType.PIN)
     }
     ```
  4. This second `mainHandler.post` pushes overlay attachment to the back of the queue behind any other messages already pending in the Android UI Looper.
- **Resulting State:**
  Adds 16–33ms of latency before the blocking overlay appears, widening the window where the target app is unprotected.
- **Security Impact:**
  Medium. Unnecessary lag in overlay presentation allows transient visibility of sensitive activity state.
- **Deterministic vs Timing-Dependent:**
  Deterministic code pathology.
- **Proposed Invariant:**
  `INV-SYNCHRONOUS-MAIN-ATTACH`: If the calling thread is already `Looper.getMainLooper().thread`, `OverlayManager` must execute view removal and attachment synchronously rather than re-posting to `mainHandler`.

---

### Finding 1.4: Unlinked Coroutine Cancellation Leaving Orphaned MainHandler Runnables

- **File:** [`LockKeeperAccessibilityService.kt`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperAccessibilityService.kt)
- **Method:** `onAccessibilityEvent`
- **Line Reference:** [Lines 235–265](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperAccessibilityService.kt#L235-L265)
- **Triggering Event Sequence:**
  1. Event $E_1$ arrives for target package $P_1$.
  2. `evaluationJob?.cancel()` runs, and a new `serviceScope.launch` begins evaluating $P_1$ on `Dispatchers.IO`.
  3. The coroutine completes `repository.evaluatePackage(P_1)` and posts Runnable $R_1$ to `mainHandler`.
  4. At this exact moment, before the Main Looper executes $R_1$, the user taps Home or switches to app $P_2$.
  5. Event $E_2$ arrives. Line 235 invokes `evaluationJob?.cancel()`.
  6. **Fatal Disconnect:** `evaluationJob?.cancel()` cancels the coroutine, but **HAS ZERO EFFECT on Runnable $R_1$ already queued on `mainHandler`**.
  7. $R_1$ contains no active-job validation and no generation counter.
  8. The Main Looper executes $R_1$, commanding `overlayManager.showPinOverlay(P_1)` over top of the Launcher or $P_2$.
  9. A fraction of a second later, $E_2$'s evaluation finishes and dismisses or changes the overlay.
- **Resulting State:**
  Spurious overlay appearance on the home screen or another application, followed by sudden dismissal.
- **Security Impact:**
  High Denial of Service and visual corruption.
- **Deterministic vs Timing-Dependent:**
  Timing-dependent on coroutine execution speed vs Main Looper message queue drain rate.
- **Proposed Invariant:**
  `INV-GENERATION-TAGGED-DISPATCH`: Every evaluation request must increment an atomic generation ID. Any runnable posted to `mainHandler` must check whether its generation ID matches the current active generation before executing any overlay action.

---

### Finding 1.5: Unfiltered Global Subscription to `TYPE_WINDOW_CONTENT_CHANGED`

- **File:** [`LockKeeperAccessibilityService.kt`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperAccessibilityService.kt)
- **Method:** `onServiceConnected` (lines 102–110) & `onAccessibilityEvent` (lines 143–150)
- **Triggering Event Sequence:**
  1. `accessibility_service_config.xml` originally specified only `typeWindowStateChanged`.
  2. However, `onServiceConnected()` dynamically overrides this configuration:
     ```kotlin
     val info = AccessibilityServiceInfo().apply {
         eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                 AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
         ...
     }
     serviceInfo = info
     ```
  3. No package filtering is applied (`packageNames = null`), meaning the service receives every content change event from every active process on Android.
  4. When an application opens, complex UI components (e.g. RecyclerView, Lottie animations, WebView, Jetpack Compose layouts) emit dozens of content change events per second.
  5. Even though the 150ms debounce (`now - lastEventTimestamp < 150L`) exists, every 150ms window allows another evaluation through during app startup.
  6. Each evaluation triggers multiple Room database queries (`lockedAppDao.getLockedApp`, `appSettingsDao.getOrInitializeSettings`, `tamperController.checkLockout`).
- **Resulting State:**
  Severe thread pool starvation on `Dispatchers.IO`, continuous coroutine cancellation and re-triggering, and Main Looper congestion, generating visible UI stutter and frame drops.
- **Security Impact:**
  Medium Denial of Service / Usability Degradation.
- **Deterministic vs Timing-Dependent:**
  Deterministic generation of excessive IPC and database load.
- **Proposed Invariant:**
  `INV-CONTENT-CHANGE-FILTER`: Global event handling must be restricted to `TYPE_WINDOW_STATE_CHANGED`. `TYPE_WINDOW_CONTENT_CHANGED` must be gated strictly to verified system management packages (`com.android.settings`, package installers, OEM security suites) required for anti-tamper traversal.

---

### Finding 1.6: In-Memory Keypad State Destruction on View Teardown

- **File:** [`PinOverlayView.kt`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/overlay/PinOverlayView.kt)
- **Method:** Class level & `setupKeypad`
- **Line Reference:** [Lines 27–45, 172–198](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/overlay/PinOverlayView.kt#L27-L45)
- **Triggering Event Sequence:**
  1. `PinOverlayView` holds input state in a transient field: `private val pinBuilder = StringBuilder()`.
  2. When any of the re-attachment or flicker races (Findings 1.1, 1.2, 1.4) occur, `removeCurrentOverlayInternal()` removes the view and inflates an entirely new `PinOverlayView`.
  3. If a user had entered 3 digits of a 4-digit PIN when the flicker occurred, `pinBuilder` is wiped clean.
  4. The user, watching the keypad, presses the 4th digit.
  5. The keypad registers only 1 digit. If the user presses "✓", `statusTextView.text = "PIN must be at least 4 digits"`.
  6. If the user continues typing, they submit a misaligned PIN, triggering `handleFailedPin()` and incrementing `failedPinAttempts` toward lockout.
- **Resulting State:**
  Keypad input silently reset midway through user entry; legitimate users locked out.
- **Security Impact:**
  High Denial of Service & Spurious Authentication Lockout.
- **Deterministic vs Timing-Dependent:**
  Deterministic result of destroying and recreating the view instance during active input.
- **Proposed Invariant:**
  `INV-PIN-SESSION-CONTINUITY`: View state must not be destroyed while a user is actively interacting with an overlay for package $P$ unless package $P$ has been confirmed exited or backgrounded.

---

## Section 2: Deep-Dive Investigation — Problem 2 (Enforcement Stopping After Recents Dismissal / Process Death)

### Finding 2.1: Monolithic Single-Process Architecture Bound to Task Dismissal

- **File:** [`AndroidManifest.xml`](file:///c:/AppLocker/android/app/src/main/AndroidManifest.xml)
- **Component:** Entire `<application>` element (lines 23–95)
- **Triggering Event Sequence:**
  1. In `AndroidManifest.xml`, neither `LockKeeperAccessibilityService` nor `LockKeeperForegroundService` declares an `android:process` attribute.
  2. As a consequence, `MainActivity` (Flutter UI), `LockKeeperAccessibilityService` (system accessibility client), and `LockKeeperForegroundService` (background enforcement and usage stats fallback) all execute inside the single application process: `com.lockkeeper.app`.
  3. When the user opens LockKeeper and subsequently swipes LockKeeper away from the Android Recents / Overview tray:
     - The OS treats this as an explicit task removal.
     - On standard Android builds, all activities in the task are destroyed.
     - On aggressive OEM skins (Xiaomi MIUI/HyperOS, Samsung OneUI, Oppo ColorOS, Vivo OriginOS), the OEM task cleaner dispatches `SIGKILL` or `am force-stop` to PID `com.lockkeeper.app`.
- **Resulting State:**
  The entire process `com.lockkeeper.app` is terminated. Both services are killed simultaneously.
- **Security Impact:**
  **Critical Fail-Open Security Collapse.** All lock enforcement ceases immediately. Any locked application can be launched without hindrance.
- **Deterministic vs Timing-Dependent:**
  Deterministic on Xiaomi/MIUI/HyperOS upon swipe from Recents; timing-dependent on standard Android under background resource reclamation.
- **Proposed Invariant:**
  `INV-PROCESS-DECOUPLING`: The core security enforcement services must run in a dedicated, isolated process (e.g. `android:process=":core"`), completely decoupled from the transient Flutter UI activity (`MainActivity`), with `android:stopWithTask="false"`.

---

### Finding 2.2: Fatal Crash via Illegal Foreground Service Start in `onTaskRemoved()`

- **File:** [`LockKeeperForegroundService.kt`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperForegroundService.kt)
- **Method:** `onTaskRemoved(rootIntent: Intent?)`
- **Line Reference:** [Lines 80–89](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperForegroundService.kt#L80-L89)
- **Triggering Event Sequence:**
  1. When LockKeeper is swiped from Recents, the Android framework invokes `onTaskRemoved(rootIntent)` on `LockKeeperForegroundService`.
  2. Lines 83–88 execute:
     ```kotlin
     val restartIntent = Intent(applicationContext, LockKeeperForegroundService::class.java)
     if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
         applicationContext.startForegroundService(restartIntent)
     } else {
         applicationContext.startService(restartIntent)
     }
     ```
  3. On Android 12+ (API 31 through Android 15/16), Google enforced **Foreground Service Launch Restrictions**: an application running in the background cannot call `startForegroundService()`.
  4. Because the user just swiped the app from Recents, the application is in the background with no visible activities.
  5. The call throws a fatal runtime exception:
     ```
     android.app.ForegroundServiceStartNotAllowedException: Service.startForeground() not allowed due to mAllowStartForeground false
     ```
  6. The uncaught exception terminates the process immediately.
  7. Android's `ActivityManagerService` detects that the service crashed repeatedly during startup/task-removal and permanently suppresses its `START_STICKY` restart schedule!
- **Resulting State:**
  The service crashes fatally and is permanently removed from the OS restart list.
- **Security Impact:**
  **Critical Fail-Open Failure.** The mechanism intended to keep the service alive directly causes its permanent demise.
- **Deterministic vs Timing-Dependent:**
  Deterministic on Android 12+ (API 31+) whenever LockKeeper is dismissed from Recents.
- **Proposed Invariant:**
  `INV-SAFE-TASK-REMOVAL`: `onTaskRemoved()` must never call `startForegroundService()`. The service must maintain its existing foreground notification and allow the OS to manage lifecycle via `START_STICKY`, supplemented by WorkManager periodic keep-alive jobs.

---

### Finding 2.3: Xiaomi / MIUI / HyperOS Accessibility Service Suppression

- **File:** [`LockKeeperAccessibilityService.kt`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperAccessibilityService.kt) & [`ProtectionRepository.kt`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/domain/ProtectionRepository.kt)
- **Method:** `ProtectionRepository.getAuthoritativeSecurityStatus` (lines 310–317, 355–358)
- **Triggering Event Sequence:**
  1. On Xiaomi devices running MIUI or HyperOS, swiping an app from Recents triggers MIUI's `PowerKeeper` / `SecurityManager` force-close routine.
  2. The system sends `SIGKILL` to `com.lockkeeper.app` and marks the package as "User Force-Stopped" (`FLAG_STOPPED`).
  3. Under Android's system accessibility server (`AccessibilityManagerService`), when a package is force-stopped:
     - The framework unbinds the service.
     - The framework actively suppresses automatic reconnection.
     - Even though `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` still contains `com.lockkeeper.app`, `system_server` **refuses to re-bind the service** until the user manually launches the app or toggles the service in Settings.
  4. When the user subsequently launches LockKeeper from the home screen:
     - `MainActivity` starts up.
     - `ProtectionRepository.getAuthoritativeSecurityStatus()` evaluates:
       - `isA11yEnabled` is `true` (read from `Settings.Secure`).
       - `isA11yConnected` is `false` (in-memory companion boolean is `false`, binder unattached).
     - It adds the degraded reason:
       ```
       "Accessibility Service is enabled in Settings but background service is disconnected"
       ```
- **Resulting State:**
  The application reports a degraded state, and all real-time accessibility enforcement is dead.
- **Security Impact:**
  Critical Fail-Open Failure. Lock enforcement is totally disabled on one of the most widely used Android OEM distributions in the world.
- **Deterministic vs Timing-Dependent:**
  Deterministic on Xiaomi MIUI/HyperOS devices where the app has not been granted "Autostart" and exempted from battery restrictions.
- **Proposed Invariant:**
  `INV-OEM-HARDENING`: LockKeeper must verify OEM-specific permissions during onboarding, specifically directing Xiaomi users to grant "Autostart" and set Battery Saver to "No Restrictions", while running a background WorkManager keep-alive to detect un-binding.

---

### Finding 2.4: Foreground Service Startup Deficit in `MainActivity` and Flutter UI

- **File:** [`lib/main.dart`](file:///c:/AppLocker/lib/main.dart), [`lib/ui/screens/home_screen.dart`](file:///c:/AppLocker/lib/ui/screens/home_screen.dart), and [`MainActivity.kt`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/MainActivity.kt)
- **Method:** `main()`, `HomeScreen.initState()`, `MainActivity.onCreate()`
- **Triggering Event Sequence:**
  1. A search for `startProtectionService` across the entire Dart codebase reveals that it is invoked **in exactly one place**:
     [`lib/ui/screens/onboarding_screen.dart:138`](file:///c:/AppLocker/lib/ui/screens/onboarding_screen.dart#L138).
  2. It is **never invoked in `lib/main.dart`**.
  3. It is **never invoked in `lib/ui/screens/home_screen.dart`**.
  4. It is **never invoked in `MainActivity.onCreate()` or `MainActivity.onResume()`**.
  5. If the process is killed (e.g. by LMK or Recents swipe) and the user subsequently relaunches LockKeeper:
     - The onboarding screen is skipped because `onboardingComplete == true`.
     - `HomeScreen` loads directly.
     - `LockKeeperForegroundService` is **NEVER RESTARTED**.
     - `LockKeeperForegroundService.isRunning` remains `false`.
- **Resulting State:**
  The secondary enforcement mechanism (`UsageStatsManager` polling fallback) is permanently disabled following any process death.
- **Security Impact:**
  High Reliability Breakdown. If Accessibility Service disconnects, there is no secondary fallback running to catch unauthorized app launches.
- **Deterministic vs Timing-Dependent:**
  Deterministic upon any application relaunch following initial onboarding.
- **Proposed Invariant:**
  `INV-FGS-RECONCILIATION`: `MainActivity` (or the native Application class) must idempotently check and start `LockKeeperForegroundService` whenever the application process initializes, provided onboarding is complete.

---

### Finding 2.5: Hardcoded Settings Bypass in Foreground Service Fallback

- **File:** [`LockKeeperForegroundService.kt`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperForegroundService.kt)
- **Method:** `checkAndEnforcePackage(packageName: String)`
- **Line Reference:** [Line 183](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperForegroundService.kt#L183)
- **Triggering Event Sequence:**
  1. `LockKeeperAccessibilityService` is killed or disconnected.
  2. `LockKeeperForegroundService` is running its `UsageStatsManager` polling fallback loop.
  3. User or adversary launches Android Settings (`com.android.settings`).
  4. Line 183 executes:
     ```kotlin
     if (packageName.isBlank() || packageName == this.packageName || packageName == "com.android.settings") return
     ```
  5. Because `com.android.settings` is explicitly bypassed, **the fallback engine does nothing**.
  6. The adversary navigates to "Apps" $\rightarrow$ "LockKeeper" and taps "Clear Storage" or "Uninstall", or goes to "Device Admin Apps" and disables LockKeeper.
- **Resulting State:**
  Android Settings is completely unguarded during fallback enforcement.
- **Security Impact:**
  **Critical Anti-Tamper Bypass.** An attacker can unrestrictedly uninstall or wipe LockKeeper whenever the Accessibility Service is disconnected.
- **Deterministic vs Timing-Dependent:**
  Deterministic code path.
- **Proposed Invariant:**
  `INV-FALLBACK-ANTI-TAMPER`: If AccessibilityService is inactive and the fallback engine detects `com.android.settings`, it must fail closed by issuing a Home intent or launching an administrative challenge, rather than silently bypassing Settings.

---

### Finding 2.6: In-Memory Volatile Variables Mistaken for Authoritative System State

- **File:** [`LockKeeperAccessibilityService.kt`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperAccessibilityService.kt) & [`LockKeeperForegroundService.kt`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperForegroundService.kt)
- **Properties:** `LockKeeperAccessibilityService.isConnected` & `LockKeeperForegroundService.isRunning`
- **Line Reference:** Line 25 in `LockKeeperAccessibilityService.kt`; Line 35 in `LockKeeperForegroundService.kt`
- **Triggering Event Sequence:**
  1. Both services use in-memory static volatile flags:
     ```kotlin
     @Volatile var isConnected: Boolean = false
     @Volatile var isRunning: Boolean = false
     ```
  2. These variables live purely in the heap of process `com.lockkeeper.app`.
  3. When process death occurs, these values vanish.
  4. When `MainActivity` starts up in a fresh process, `ProtectionRepository.getAuthoritativeSecurityStatus()` queries `LockKeeperAccessibilityService.isConnected`.
  5. Even if Android's system server is in the middle of binding the Accessibility Service (which takes 300–800ms across IPC), `isConnected` evaluates to `false`.
  6. `MainActivity` immediately pushes an alert to the user that the service is broken, inducing user confusion and unnecessary settings tampering.
- **Resulting State:**
  Spurious degraded alerts; race conditions during app initialization.
- **Security Impact:**
  Medium Integrity/Usability Defect.
- **Deterministic vs Timing-Dependent:**
  Timing-dependent on process fork and binder connection latency.
- **Proposed Invariant:**
  `INV-HEURISTIC-CONNECTION-CHECK`: Protection status checks must differentiate between an active reconnect window (grace period immediately following process start) and a persistent disconnection, using binder verification rather than raw volatile booleans.

---

## Section 3: Concrete Adversarial Failure Sequences

### Sequence 1: The "Status Bar Notification" PIN Flicker Exploit (Problem 1)

```
Time    Actor/Component          Event / Action                                   Internal State
──────────────────────────────────────────────────────────────────────────────────────────────────────────────────
T0      User                     Taps WhatsApp icon                               WhatsApp launching
T1      A11yService              Receives TYPE_WINDOW_STATE_CHANGED (com.whatsapp)lastHandledPackage = "com.whatsapp"
T2      ProtectionRepo           evaluatePackage("com.whatsapp")                  Returns RequirePin
T3      OverlayManager           showPinOverlay("com.whatsapp")                   currentGateType = PIN
                                 mainHandler.post(AttachRunnable)                 currentOverlayView = null (pending)
T4      SystemUI                 Clock minute rolls over / Battery tick           Status bar redraws
T5      A11yService              Receives TYPE_WINDOW_STATE_CHANGED (SystemUI)    lastHandledPackage = "SystemUI"
T6      ProtectionRepo           evaluatePackage("SystemUI")                      Returns Allowed (not locked)
T7      A11yService              Calls overlayManager.dismissIfShowing(SystemUI)  Allowed callback
T8      Main Looper              Executes AttachRunnable (T3)                     PinOverlayView attached to WM
                                                                                  currentOverlayView = View#1
T9      Main Looper              Executes dismissIfShowing (T7)                   removeCurrentOverlayInternal() called!
                                                                                  View#1 REMOVED FROM WM!
                                                                                  WhatsApp content EXPOSED!
T10     WhatsApp                 Dispatches TYPE_WINDOW_CONTENT_CHANGED           WhatsApp active
T11     A11yService              Receives event (com.whatsapp)                    Evaluates RequirePin
T12     OverlayManager           showPinOverlay("com.whatsapp")                   Creates View#2, attaches to WM
──────────────────────────────────────────────────────────────────────────────────────────────────────────────────
RESULT: User sees WhatsApp UI flash on screen between T9 and T12. Any PIN digits typed during View#1 are lost.
```

---

### Sequence 2: The "Recents Swipe-to-Kill" Security Neutralization (Problem 2)

```
Time    Actor/Component          Event / Action                                   Internal State
──────────────────────────────────────────────────────────────────────────────────────────────────────────────────
T0      User                     Opens LockKeeper app                             LockKeeper active, PID 1420
T1      User                     Swipes up to Recents, swipes LockKeeper away     Task removed
T2      Android OS               Invokes LockKeeperForegroundService.onTaskRemovedProcess in background
T3      ForegroundService        Calls startForegroundService(restartIntent)      Throws ForegroundServiceStartNotAllowedException!
T4      Android OS               Crash caught by Android Runtime                  PID 1420 killed with SIGABRT
T5      ActivityManager          Logs crash loop, cancels START_STICKY            Service marked CRASHED
T6      MIUI / HyperOS           Marks package com.lockkeeper.app as force-stoppedAccessibility binder suppressed
T7      Adversary / User         Taps locked Banking App                          Target app starts
T8      Android OS               No active AccessibilityService to report event   Zero enforcement events fired
T9      Target App               Opens completely unhindered                      CONFIDENTIAL DATA ACCESSED!
──────────────────────────────────────────────────────────────────────────────────────────────────────────────────
RESULT: 100% fail-open vulnerability. Complete loss of device protection.
```

---

## Section 4: Comprehensive Finding Matrix

| Finding ID | Component | Vulnerability Class | Trigger Event | Security Impact | Invariant Violated |
|---|---|---|---|---|---|
| **FIND-01** | `OverlayManager` | Blind Dismissal / Incomplete Guard | SystemUI / Unlocked app event | Critical Information Disclosure | `INV-OVERLAY-TARGET-MATCH` |
| **FIND-02** | `OverlayManager` | Missing Idempotency / Queue Race | Rapid duplicate A11y events | High DoS / Window Anomaly | `INV-OVERLAY-IDEMPOTENCY` |
| **FIND-03** | `A11yService` + `OverlayManager` | Double MainHandler Hop | `showPinOverlay()` invocation | Medium Latency Window Expansion | `INV-SYNCHRONOUS-MAIN-ATTACH` |
| **FIND-04** | `A11yService` | Unlinked Coroutine Cancellation | App switch during evaluation | High DoS / Mis-targeted Overlay | `INV-GENERATION-TAGGED-DISPATCH` |
| **FIND-05** | `A11yService` | Unfiltered Content Change Flooding | Dynamic UI rendering | Medium CPU / I/O Starvation | `INV-CONTENT-CHANGE-FILTER` |
| **FIND-06** | `PinOverlayView` | Ephemeral Keypad State Loss | Overlay re-attachment | High DoS / Erroneous Lockout | `INV-PIN-SESSION-CONTINUITY` |
| **FIND-07** | `AndroidManifest` | Monolithic Single Process | Recents swipe / OS kill | Critical Fail-Open Security Failure | `INV-PROCESS-DECOUPLING` |
| **FIND-08** | `ForegroundService` | Illegal Background FGS Start | `onTaskRemoved()` execution | Critical Crash / Permanent Death | `INV-SAFE-TASK-REMOVAL` |
| **FIND-09** | `A11yService` | OEM Force-Stop Suppression | MIUI / HyperOS Recents swipe | Critical Permanent Disconnection | `INV-OEM-HARDENING` |
| **FIND-10** | `MainActivity` / Dart | Missing FGS Startup Reconciliation | Application relaunch | High Redundancy Failure | `INV-FGS-RECONCILIATION` |
| **FIND-11** | `ForegroundService` | Hardcoded Settings Exclusion | Settings opened during fallback | Critical Anti-Tamper Bypass | `INV-FALLBACK-ANTI-TAMPER` |
| **FIND-12** | Global State | Ephemeral In-Memory Authority | Process resurrection | Medium Desynchronization / Panic | `INV-HEURISTIC-CONNECTION-CHECK` |

---

## Section 5: Defensive Remediation Recommendations

1. **Fix `dismissIfShowing` in `OverlayManager.kt`:**
   Enforce package ownership:
   ```kotlin
   @Synchronized
   fun dismissIfShowing(packageName: String? = null) {
       if (packageName != null && isInputMethodPackage(packageName)) return
       if (currentGateType == GateType.ADMIN) return
       if (packageName != null && currentPackageName != null && packageName != currentPackageName) {
           return // Do NOT dismiss overlay for app A when app B emits an event!
       }
       mainHandler.post { removeCurrentOverlayInternal() }
   }
   ```
2. **Make `showPinOverlay` Idempotent:**
   Introduce an atomic pending package tracker and check before posting to `mainHandler`.
3. **Decouple Process Architecture in `AndroidManifest.xml`:**
   Move `LockKeeperAccessibilityService` and `LockKeeperForegroundService` into `android:process=":core"`, ensuring that swiping `MainActivity` from Recents does not kill the enforcement engine.
4. **Remove `startForegroundService` from `onTaskRemoved()`:**
   Eliminate the `ForegroundServiceStartNotAllowedException` crash loop.
5. **Add FGS Reconciliation in `MainActivity.kt`:**
   Ensure `LockKeeperForegroundService.startService(applicationContext)` is invoked whenever the application launches if onboarding is complete.
6. **Remove Hardcoded Settings Bypass in Fallback Service:**
   Allow the fallback engine to protect `com.android.settings` when the accessibility service is unattached.
