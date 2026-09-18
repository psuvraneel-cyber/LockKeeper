# LockKeeper Overlay Architecture & WindowManager Specialist Review

**Reviewer Role:** Android WindowManager & Accessibility Overlay Specialist  
**Target Repository:** [LockKeeper](https://github.com/psuvraneel-cyber/LockKeeper)  
**Inspection Scope:** `OverlayManager.kt`, `PinOverlayView.kt`, `CooldownOverlayView.kt`, `AdminOverlayView.kt`, `TamperAuthorizationController.kt`, `LockKeeperAccessibilityService.kt`, `LockKeeperForegroundService.kt`  
**Mode:** READ-ONLY Forensic Audit & Behavioral Analysis  

---

## Executive Summary & Specialist Verdict

This review provides a deep forensic inspection of the window layering, lifecycle transitions, touch dispatch pipelines, and concurrency characteristics of the LockKeeper Android overlay subsystem. 

### Core Specialist Findings:
1. **Touch Event Delivery Integrity:** Child `Button` and `EditText` components **reliably receive touch and click events** despite custom `dispatchTouchEvent()` and `onTouchEvent()` implementations in `PinOverlayView`, `CooldownOverlayView`, and `AdminOverlayView`. Because `super.dispatchTouchEvent(ev)` is invoked first, standard Android `ViewGroup` hit-testing and child touch target assignment (`mFirstTouchTarget`) proceed normally. The unconditional `return true` at the ViewGroup root ensures touch events occurring on non-interactive backdrop regions are consumed, preventing event leakage to underlying windows.
2. **Window Type Hierarchy & Security Layering:** The primary window type `TYPE_ACCESSIBILITY_OVERLAY` (obtained via the active `AccessibilityService` context) successfully places the security gates above standard application windows, notification shades, and system dialogs while remaining immune to `SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS`. The fallback path correctly degrades to `TYPE_APPLICATION_OVERLAY`.
3. **Asynchronous Dismissal Collision (Stale Remove Race):** A significant concurrency vulnerability exists in `OverlayManager.dismissIfShowing(packageName)`. Because the package comparison is not verified inside the asynchronous `mainHandler.post` execution block against the currently attached package, an outdated "Allowed" decision from a previous package can dismiss a newly attached overlay for a protected package if the user switches apps rapidly.
4. **MainHandler Post-Queue Idempotency Window (`show -> show`):** Synchronous idempotency checks (`currentOverlayView != null`) evaluated prior to `mainHandler.post` can fail to deduplicate rapid successive calls if invoked before the first posted runnable has attached the view on the main thread, resulting in a transient view teardown and keypad state reset.
5. **Session Token Isolation:** `TamperAuthorizationController` and `OverlayManager` enforce rigorous session ID validation for admin authorization callbacks, neutralizing stale callback attacks across superseded sessions. However, fallback invocations with `sessionId = null` lack this isolation.
6. **Service Teardown Token Invalidation:** When `LockKeeperAccessibilityService.onDestroy()` executes, its accessibility window token is invalidated by the Android `WindowManagerService`. The `try-catch` cascade in `removeCurrentOverlayInternal()` prevents crashes from `IllegalArgumentException` / `BadTokenException`.

---

## 1. WindowManager Pipeline, Tokens & Layering Architecture

```
                                  +--------------------------------------------------+
                                  |         Android WindowManager Hierarchy          |
                                  +--------------------------------------------------+
                                                            |
                 +------------------------------------------+-----------------------------------------+
                 |                                                                                    |
  [Accessibility Service Active]                                                          [Fallback / Service Off]
                 |                                                                                    |
  +------------------------------+                                                      +------------------------------+
  | TYPE_ACCESSIBILITY_OVERLAY   |                                                      |  TYPE_APPLICATION_OVERLAY   |
  | Token: AccessibilityService  |                                                      |  Token: Application Context  |
  | Layer: Above System UI / Top |                                                      |  Layer: Standard Alert Layer |
  +------------------------------+                                                      +------------------------------+
                 |                                                                                    |
                 +------------------------------------+-----------------------------------------------+
                                                      |
                                      +-------------------------------+
                                      | WindowManager.LayoutParams:   |
                                      | - MATCH_PARENT x MATCH_PARENT |
                                      | - FLAG_LAYOUT_IN_SCREEN       |
                                      | - FLAG_HARDWARE_ACCELERATED   |
                                      | - FLAG_DRAWS_SYSTEM_BAR_BG    |
                                      | - FLAG_SECURE                 |
                                      | - SOFT_INPUT_ADJUST_RESIZE    |
                                      | - SOFT_INPUT_STATE_HIDDEN     |
                                      +-------------------------------+
```

### 1.1 Window Type Selection & Context Token Binding
- **Primary Type (`TYPE_ACCESSIBILITY_OVERLAY`):**
  - Configured when `accessibilityServiceContext != null` on API $\ge 22$ (`LOLLIPOP_MR1`).
  - Obtained via `accessibilityServiceContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager`.
  - **Security Advantage:** Positioned in the highest system overlay layer. Crucially immune to `SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS`, which Android Settings, PackageInstaller, and Device Admin screens activate to hide ordinary `TYPE_APPLICATION_OVERLAY` windows.
  - **Token Lifetime:** The window token is directly bound to the lifecycle of the `LockKeeperAccessibilityService` connection.
- **Fallback Type (`TYPE_APPLICATION_OVERLAY` / `TYPE_PHONE`):**
  - Utilized when `accessibilityServiceContext == null` (e.g., fallback enforcement driven by `LockKeeperForegroundService`) or when `wm.addView` throws an exception using the accessibility window token.
  - Requires `Settings.canDrawOverlays(context)` (`SYSTEM_ALERT_WINDOW`).

### 1.2 Layout Parameters & Flags Analysis
In `OverlayManager.createLayoutParams()`:
```kotlin
WindowManager.LayoutParams(
    WindowManager.LayoutParams.MATCH_PARENT,
    WindowManager.LayoutParams.MATCH_PARENT,
    type,
    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or
            WindowManager.LayoutParams.FLAG_SECURE,
    PixelFormat.TRANSLUCENT
).apply {
    softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
}
```
- `FLAG_LAYOUT_IN_SCREEN`: Extends the window over the entire display surface including notch cutouts, status bar, and navigation bar, preventing touch ingress from edges.
- `FLAG_HARDWARE_ACCELERATED`: Enables GPU rendering pipelines for smooth keypad animations and 60fps countdown timers.
- `FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS`: Ensures system bar background protection is drawn by the window.
- `FLAG_SECURE`: Prevents screen recording, screenshots, and recents thumbnail caching of PIN/Admin entries.
- `SOFT_INPUT_ADJUST_RESIZE`: Critical for `AdminOverlayView` so that when the software keyboard (IME) appears, the password `EditText` and action buttons are panned above the keyboard rather than obscured.

---

## 2. Touch Dispatch Forensics: `dispatchTouchEvent()` & `onTouchEvent()`

### 2.1 The Inspection Subject
In all three overlay classes (`PinOverlayView`, `CooldownOverlayView`, `AdminOverlayView`):
```kotlin
override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
    super.dispatchTouchEvent(ev)
    return true
}

override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
    return true
}
```

### 2.2 Execution Flow in Android `ViewGroup`
To verify whether keypad buttons reliably receive touches, let us trace Android's internal `ViewGroup.dispatchTouchEvent(MotionEvent ev)` execution pipeline:

```
                                      MotionEvent (ACTION_DOWN)
                                                  |
                                                  v
                               +-------------------------------------+
                               | PinOverlayView.dispatchTouchEvent() |
                               +-------------------------------------+
                                                  |
                                                  v
                               +-------------------------------------+
                               |     super.dispatchTouchEvent()      |
                               +-------------------------------------+
                                                  |
                                                  v
                               +-------------------------------------+
                               |      onInterceptTouchEvent()?       |
                               | (Not overridden -> returns false)   |
                               +-------------------------------------+
                                                  |
                                                  v
                               +-------------------------------------+
                               |     Child View Hit-Test Loop        |
                               |  Iterates children (Reverse Z-Order)|
                               +-------------------------------------+
                                                  |
                   +------------------------------+------------------------------+
                   | (Touch inside Button bounds)                                | (Touch on blank background)
                   v                                                             v
+-------------------------------------+                       +-------------------------------------+
|      child.dispatchTouchEvent()     |                       |       No child handles event        |
|      (Button.dispatchTouchEvent)    |                       |      (mFirstTouchTarget is null)    |
+-------------------------------------+                       +-------------------------------------+
                   |                                                             |
                   v                                                             v
+-------------------------------------+                       +-------------------------------------+
|        child.onTouchEvent()         |                       |      super.super.dispatchTouchEvent |
|  (Button handles click -> true)     |                       |     calls this.onTouchEvent(ev)     |
+-------------------------------------+                       +-------------------------------------+
                   |                                                             |
                   v                                                             v
+-------------------------------------+                       +-------------------------------------+
|   mFirstTouchTarget = child (Btn)   |                       |    PinOverlayView.onTouchEvent()    |
|   super.dispatchTouchEvent -> true  |                       |         returns true (absorbed)     |
+-------------------------------------+                       +-------------------------------------+
                   |                                                             |
                   +------------------------------+------------------------------+
                                                  |
                                                  v
                               +-------------------------------------+
                               | PinOverlayView ignores return val   |
                               | Unconditionally returns true        |
                               +-------------------------------------+
```

### 2.3 Keypad Button Event Delivery Verdict
- **Reliability:** **100% RELIABLE.**
- **Technical Reasoning:**
  1. `super.dispatchTouchEvent(ev)` executes the complete standard `ViewGroup` dispatch logic.
  2. For `ACTION_DOWN` within a child `Button`'s coordinate bounds, `child.dispatchTouchEvent(ev)` delegates to `Button.onTouchEvent(ev)`.
  3. Because `Button` has an `OnClickListener` registered via `setOnClickListener`, `Button.onTouchEvent` returns `true`.
  4. The parent `LinearLayout` registers the `Button` into its internal `mFirstTouchTarget` linked list.
  5. Subsequent `ACTION_MOVE` and `ACTION_UP` events are routed directly to `mFirstTouchTarget` (the Button). On `ACTION_UP`, the `Button` triggers `performClick()` and fires the `OnClickListener`.
  6. The outer method unconditionally returning `true` has **zero negative impact** on child delivery because the child has already processed the event during `super.dispatchTouchEvent(ev)`.

### 2.4 Security Benefit: Touch Leakage Prevention
- If a touch occurs outside any button (e.g., in the negative space of the layout), `super.dispatchTouchEvent` finds no target child and invokes `this.onTouchEvent(ev)`.
- `PinOverlayView.onTouchEvent(ev)` returns `true` (and `isClickable = true`).
- The window manager receives `true`, ensuring the touch gesture is fully consumed by LockKeeper and **never leaked or passed through to the underlying locked application or system window**.

---

## 3. Comprehensive Trace of All Overlay Paths

```
                                  +----------------------------------------------------+
                                  |             OVERLAY LIFECYCLE PATHWAYS             |
                                  +----------------------------------------------------+
                                                            |
          +-------------------+-----------------------------+--------------------+--------------------+
          |                   |                             |                    |                    |
          v                   v                             v                    v                    v
     [CREATION]         [REPLACEMENT]                  [DISMISSAL]           [REMOVAL]          [TERMINATION]
          |                   |                             |                    |                    |
  showPinOverlay      showLockoutOverlay            dismissIfShowing      removeCurrent-       endSession()
  showCooldownOverlay (replaces PIN on 5 fails)     dismissAdminOverlay   OverlayInternal()    (AUTHORIZED /
  showLockoutOverlay  showAdminOverlay              dismissAll()          wm.removeView-       CANCELLED /
  showAdminOverlay    (replaces PIN on Settings)    Pin Success           Immediate()          INTERRUPTED /
                                                    Admin Auth Success    wm.removeView()      SERVICE_DESTROYED /
                                                    Cancel / Back key     fallback cascade     TERMINAL_DENIAL)
```

### 3.1 Path 1: Create an Overlay
- **P1.1 (App Launch Lock):** `LockKeeperAccessibilityService.onAccessibilityEvent` $\rightarrow$ `serviceScope.launch { repository.evaluatePackage(pkg) }` $\rightarrow$ `mainHandler.post { overlayManager.showPinOverlay(pkg) }`.
- **P1.2 (Strict Cooldown Lock):** Evaluated package has active cooldown timer $\rightarrow$ `overlayManager.showCooldownOverlay(pkg, remainingSec)`.
- **P1.3 (PIN Lockout):** Evaluated package has active failed-PIN lockout $\rightarrow$ `overlayManager.showLockoutOverlay(pkg, remainingSec)`.
- **P1.4 (Tamper Detection - Anti-Uninstall / Settings):** `LockKeeperAccessibilityService.onAccessibilityEvent` $\rightarrow$ `tamperEngine.evaluate()` detects tamper event $\rightarrow$ `tamperController.startOrGetSession(event)` $\rightarrow$ `overlayManager.showAdminOverlay(pkg, session, callback)`.
- **P1.5 (ForegroundService Fallback):** `LockKeeperForegroundService.checkAndEnforcePackage` $\rightarrow$ Polling detect package $\rightarrow$ `overlayManager.showPinOverlay(pkg)`.

### 3.2 Path 2: Replace an Overlay
- **P2.1 (PIN $\rightarrow$ Lockout on 5 Failures):** `PinOverlayView` keypad $\rightarrow$ `OverlayManager.handlePinSubmitted` $\rightarrow$ `repository.handleFailedPin()` reaches limit $\rightarrow$ `mainHandler.post { showLockoutOverlay(pkg, remainingSec) }` $\rightarrow$ `removeCurrentOverlayInternal()` tears down `PinOverlayView` $\rightarrow$ `attachOverlay()` mounts `CooldownOverlayView(isLockout = true)`.
- **P2.2 (PIN $\rightarrow$ Admin Overlay):** While `PinOverlayView` is displayed, user navigates to Settings or attempts uninstallation $\rightarrow$ `handleTamperEvent` fires $\rightarrow$ `showAdminOverlay()` $\rightarrow$ `removeCurrentOverlayInternal()` tears down `PinOverlayView` $\rightarrow$ `attachOverlay()` mounts `AdminOverlayView`.
- **P2.3 (Inter-Package Switch):** Protected App A is showing PIN overlay $\rightarrow$ User switches directly to Protected App B $\rightarrow$ `showPinOverlay(App B)` $\rightarrow$ `removeCurrentOverlayInternal()` tears down App A's view $\rightarrow$ `attachOverlay()` mounts App B's view.

### 3.3 Path 3: Dismiss an Overlay
- **P3.1 (PIN Authentication Success):** User enters valid PIN $\rightarrow$ `handlePinSubmitted` $\rightarrow$ `repository.handleSuccessfulPin(pkg)` grants session $\rightarrow$ `mainHandler.post { removeCurrentOverlayInternal() }`.
- **P3.2 (Admin Password Success):** User submits valid admin password in `AdminOverlayView` $\rightarrow$ `tamperController.verifyAdminPassword` succeeds $\rightarrow$ `decisionEngine.grantAdminGraceWindow()` $\rightarrow$ `tamperController.endSession(sessionId, AUTHORIZED)` $\rightarrow$ `removeCurrentOverlayInternal()` $\rightarrow$ `onDismissAction(true)`.
- **P3.3 (Allowed App Package Transition):** User leaves locked app for home launcher or allowed app $\rightarrow$ `evaluatePackage` returns `LockDecision.Allowed` $\rightarrow$ `mainHandler.post { overlayManager.dismissIfShowing(pkg) }`.
- **P3.4 (Explicit Admin Dismissal / Session End):** Admin grace period active or settings protection disabled $\rightarrow$ `overlayManager.dismissAdminOverlay(sessionId)`.
- **P3.5 (Interception / Back Navigation):**
  - In `PinOverlayView`: Back key intercepted $\rightarrow$ `navigateHome()` $\rightarrow$ `removeCurrentOverlayInternal()` $\rightarrow$ `startActivity(HomeIntent)`.
  - In `AdminOverlayView`: Cancel button / Back key $\rightarrow$ `tamperController.endSession(sessionId, CANCELLED)` $\rightarrow$ `removeCurrentOverlayInternal()` $\rightarrow$ `navigateHome()` $\rightarrow$ `onDismissAction(false)`.
  - In `CooldownOverlayView`: Return to Home button / Back key $\rightarrow$ `onExitClicked()` $\rightarrow$ `navigateHome()`.

### 3.4 Path 4: Remove an Overlay
All removal operations funnel into `OverlayManager.removeCurrentOverlayInternal()`:
```kotlin
private fun removeCurrentOverlayInternal() {
    currentOverlayView?.let { view ->
        val wm = currentWindowManager ?: getActiveWindowManager()
        try {
            wm.removeViewImmediate(view)
        } catch (e: Exception) {
            try {
                wm.removeView(view)
            } catch (ignored: Exception) {
                try {
                    windowManager.removeView(view)
                } catch (_: Exception) {}
            }
        }
    }
    currentOverlayView = null
    currentWindowManager = null
    currentPackageName = null
    currentGateType = null
}
```
- **Execution guarantees:**
  1. Synchronous removal attempt via `removeViewImmediate(view)` ensures view teardown before any new `addView()` in the same main-thread cycle.
  2. Fallback cascade catches `IllegalArgumentException` (view already detached) or window token death.
  3. Clears state variables (`currentOverlayView = null`, `currentWindowManager = null`, `currentPackageName = null`, `currentGateType = null`).

### 3.5 Path 5: Terminate a Session
In `TamperAuthorizationController.endSession(sessionId, terminalState, reason)`:
1. Validates that `activeSession?.sessionId == sessionId` (neutralizes stale callbacks).
2. Validates `!current.isTerminal` (state transitions are immutable once in a terminal state).
3. If `terminalState == TamperSessionState.AUTHORIZED`, grants the 30-second monotonic grace window (`adminGraceUntilElapsed = now + 30_000L`).
4. Atomically clears `activeSession = null`.

### 3.6 Path 6: Recreate an Overlay
Recreation occurs when an overlay is required after a previous removal or when re-entering a locked app.
- **Idempotency Guards:**
  - `showPinOverlay`: `if (currentOverlayView != null && currentPackageName == packageName && currentGateType == GateType.PIN) return`
  - `showCooldownOverlay`: `if (currentOverlayView != null && currentPackageName == packageName && currentGateType == GateType.COOLDOWN) return`
  - `showLockoutOverlay`: `if (currentOverlayView != null && currentPackageName == packageName && currentGateType == GateType.LOCKOUT) return`
  - `showAdminOverlay`: `if (currentOverlayView != null && currentGateType == GateType.ADMIN && activeTamperSessionId != null && activeTamperSessionId == targetSessionId) return`

---

## 4. Race Condition Construction & Forensic Scenarios

### Scenario 1: `show -> remove -> show` (Rapid App Refocus Race)

```
Time   Thread / Source                     Action / Event                                  MainHandler Queue State
------------------------------------------------------------------------------------------------------------------
T0     LockKeeperA11y (App A)             showPinOverlay("com.appA")                      [Task 1: Attach App A]
T1     LockKeeperA11y (Home Navigation)   dismissIfShowing("com.launcher")                [Task 1: Attach App A, Task 2: Remove Overlay]
T2     LockKeeperA11y (App A Refocused)   showPinOverlay("com.appA")                      [Task 1: Attach App A, Task 2: Remove Overlay, Task 3: Attach App A]
T3     MainLooper                         Executes Task 1: removeCurrent() -> addView(A1) currentOverlayView = View A1
T4     MainLooper                         Executes Task 2: removeCurrent() -> remove(A1)  currentOverlayView = null
T5     MainLooper                         Executes Task 3: removeCurrent() -> addView(A2) currentOverlayView = View A2
```

- **Analysis:** Because all three tasks are serialized on `mainHandler`, each `attachOverlay` begins with `removeCurrentOverlayInternal()`. No window leak occurs. View A2 remains attached and visible.

---

### Scenario 2: `show -> show` (Duplicate Show Call Before Main Thread Execution)

```
Time   Thread / Source                     Action / Event                                  State & MainHandler Queue
------------------------------------------------------------------------------------------------------------------
T0     Event 1 (Bg Thread / Coroutine)     showPinOverlay("com.bank")                      currentOverlayView is null
                                          Idempotency check passes                        [Task 1: Attach Bank View 1]
T1     Event 2 (Rapid Repeated Event)      showPinOverlay("com.bank")                      currentOverlayView is STILL null!
                                          Idempotency check passes                        [Task 1: Attach Bank View 1, Task 2: Attach Bank View 2]
T2     MainLooper                         Executes Task 1                                 addView(Bank View 1), currentOverlayView = View 1
T3     MainLooper                         Executes Task 2                                 removeViewImmediate(View 1), addView(Bank View 2)
```

- **Forensic Finding:** When two `showPinOverlay()` calls are invoked in rapid succession before `mainHandler` executes Task 1, the check `currentOverlayView != null` on line 126 evaluates to `false` in both calls.
- **Impact:** View 1 is attached and then immediately torn down and replaced with View 2. While WindowManager does not leak (Task 2 executes `removeCurrentOverlayInternal()` first), any keypad digits entered by the user during the milliseconds before Task 2 runs will be cleared.

---

### Scenario 3: `show -> stale remove` (Asynchronous Dismissal Collision)

```
Time   Thread / Source                     Action / Event                                  Outcome / Vulnerability
------------------------------------------------------------------------------------------------------------------
T0     User in Allowed App X              evaluatePackage(App X) launched in Coroutine    Running in serviceScope...
T1     User rapidly switches to Locked Y  onAccessibilityEvent(App Y) fires               evaluationJob.cancel() called
T2     LockKeeperA11y (App Y)             showPinOverlay("com.lockedY") posted to Main    [Task 1: Attach Locked Y]
T3     Coroutine (App X) [pre-cancel post] evaluatePackage(App X) posted Allowed decision [Task 1: Attach Locked Y, Task 2: dismissIfShowing("com.appX")]
T4     MainLooper                         Executes Task 1: Attaches Locked Y Overlay      currentPackageName = "com.lockedY"
T5     MainLooper                         Executes Task 2: dismissIfShowing("com.appX")   Calls removeCurrentOverlayInternal() UNCONDITIONALLY!
T6     State Result                       Locked Y Overlay is REMOVED while user is on Y! SECURITY VULNERABILITY
```

- **Root Cause Code (`OverlayManager.kt:363-380`):**
```kotlin
@Synchronized
fun dismissIfShowing(packageName: String? = null) {
    if (packageName != null && isInputMethodPackage(packageName)) {
        return
    }

    mainHandler.post {
        if (currentGateType == GateType.ADMIN) {
            return@post
        } else {
            // BUG: Does NOT check if currentPackageName == packageName!
            removeCurrentOverlayInternal()
        }
    }
}
```
- **Forensic Diagnosis:** `dismissIfShowing` ignores the `packageName` argument inside the `mainHandler.post` block. If `dismissIfShowing("com.appX")` runs after `showPinOverlay("com.lockedY")` has already attached, it indiscriminately removes the overlay for `com.lockedY`.

---

### Scenario 4: `PIN overlay -> wrong dismissal` (Package Transition vs Overlay Immunity)

```
Time   Component                           Event / Invocation                              Behavior
------------------------------------------------------------------------------------------------------------------
T0     OverlayManager                      PinOverlayView is showing for "com.target"      currentOverlayView != null
T1     System / LockKeeper                 A11y Event fires for "com.lockkeeper.app"       Handled in onAccessibilityEvent
T2     LockKeeperA11y                      Checks: packageName == this.packageName        if (overlayManager.isOverlayShowing()) return
T3     Result                              Event is correctly ignored                      No wrong dismissal
```
- **Edge Case Race:** If an accessibility event for `com.lockkeeper.app` arrives in the microsecond interval between `showPinOverlay` posting and `mainHandler` setting `currentOverlayView`, `isOverlayShowing()` returns `false`. The event falls through to `tamperEngine.evaluate()`. Because `com.lockkeeper.app` is not a system management package, it executes `evaluatePackage("com.lockkeeper.app")`, which returns `Allowed`, queueing a `dismissIfShowing` that could strip the incoming PIN overlay.

---

### Scenario 5: `PIN overlay -> duplicate overlay` (WindowManager Collision Analysis)

```
Time   Execution Step                      Operation                                       Collision Defense
------------------------------------------------------------------------------------------------------------------
T0     showPinOverlay("com.appA")          mainHandler.post { ... }                        Queued
T1     showCooldownOverlay("com.appB")     mainHandler.post { ... }                        Queued
T2     Task 1 executes                     removeCurrentOverlayInternal()                  Clean removal
                                           wm.addView(viewA, params)                       viewA added
T3     Task 2 executes                     removeCurrentOverlayInternal()                  wm.removeViewImmediate(viewA)
                                           wm.addView(viewB, params)                       viewB added
```
- **Forensic Verdict:** **NO DUPLICATE WINDOW CRASHES.** Because all mutations are serialized on the MainLooper and every attachment method strictly invokes `removeCurrentOverlayInternal()` immediately prior to `addView()`, two overlay views are never concurrently attached to the WindowManager.

---

### Scenario 6: `Admin overlay -> stale callback` (Superseded Tamper Session Callback)

```
Time   Action                              Session State                                   Stale Callback Handling
------------------------------------------------------------------------------------------------------------------
T0     Tamper Event 1 (Settings App Ops)   Session 1 created (sid="S1")                    activeSession = S1, activeTamperSessionId = "S1"
T1     AdminOverlayView 1 attached         capturedSessionId = "S1"                        AdminOverlayView 1 on screen
T2     Screen Turns Off (ACTION_SCREEN_OFF) ScreenReceiver ends Session 1 (INTERRUPTED)     activeSession = null, activeTamperSessionId = null
T3     User wakes device, triggers Event 2 Session 2 created (sid="S2")                    activeSession = S2, activeTamperSessionId = "S2"
T4     Delayed auth callback from View 1   onPasswordSubmitted callback fires for "S1"     Coroutine finishes verification...
T5     MainHandler executes callback       if (capturedSessionId != null &&                "S1" != "S2" -> Callback drops silently!
                                               activeTamperSessionId != capturedSessionId)
                                               return@post
```
- **Forensic Finding:** Stale callbacks for superseded `TamperSession` instances are **strictly rejected** at two independent gates:
  1. `OverlayManager` checks `capturedSessionId == activeTamperSessionId`.
  2. `TamperAuthorizationController.endSession` verifies `current.sessionId == sessionId`.
- **Vulnerability in Legacy / Null Sessions:** If an admin overlay was launched without a session (`session == null`, `capturedSessionId = null`), `activeTamperSessionId` is `null`. In this case, `capturedSessionId != null` evaluates to `false`, bypassing the check and invoking `repository.tamperController.endSession(true)`, which will mutate whatever new session happens to be active.

---

### Scenario 7: `Service destruction -> orphaned overlay` (Token Death & Cleanup)

```
Time   Component                           Lifecycle / Teardown Step                       State & Window Token
------------------------------------------------------------------------------------------------------------------
T0     LockKeeperAccessibilityService      System invokes onDestroy()                      Service unbinding from OS
T1     LockKeeperAccessibilityService      overlayManager.dismissAll()                     Posts removeCurrentOverlayInternal to MainHandler
T2     LockKeeperAccessibilityService      overlayManager.unregisterAccessibilityService() accessibilityServiceContext = null
T3     Android WindowManagerService        Invalidates Accessibility Window Token          Token dead in WindowManagerService
T4     MainLooper                          Executes removeCurrentOverlayInternal()         wm = currentWindowManager (saved token)
T5     MainLooper                          wm.removeViewImmediate(view)                    Throws IllegalArgumentException / BadTokenException
T6     OverlayManager                      Caught by catch(e: Exception) block             currentOverlayView = null, safely cleared
```
- **Forensic Diagnosis:**
  - When `TYPE_ACCESSIBILITY_OVERLAY` is used, the Android OS automatically tears down any windows associated with the dying AccessibilityService window token.
  - The fallback `try-catch` cascade inside `removeCurrentOverlayInternal()` catches and swallows any `IllegalArgumentException: View not attached to window manager` or `BadTokenException`.
  - **No orphaned window or application crash occurs.**

---

## 5. Environmental & System-Level Dynamics

```
+---------------------------------------------------------------------------------------+
|                                ENVIRONMENTAL DYNAMICS                                 |
+---------------------------------------------------------------------------------------+
| 1. IME / Soft Keyboards    | isInputMethodPackage() prevents keyboard events from     |
|                            | dismissing overlays or resetting tamper sessions.        |
| 2. Self-Generated Events   | Events with packageName == com.lockkeeper.app are ignored|
|                            | when isOverlayShowing() is true.                         |
| 3. System UI & Launchers   | isLauncherOrSystemUiPackage() prevents infinite Home     |
|                            | redirection loops when denials are issued.               |
| 4. Debounce & Job Control  | 150ms debounce and evaluationJob.cancel() limit          |
|                            | excessive package evaluation spam.                       |
+---------------------------------------------------------------------------------------+
```

### 5.1 Soft Keyboard (IME) Package Immunity
- **Identified Packages:** `com.google.android.inputmethod.latin` (Gboard), `com.samsung.android.honeyboard`, `com.touchtype.swiftkey`, `*.latin`, `*.keyboard`, `*.inputmethod`.
- **Handling in `LockKeeperAccessibilityService`:**
  ```kotlin
  if (overlayManager.isInputMethodPackage(packageName)) {
      return
  }
  ```
- **Handling in `OverlayManager.dismissIfShowing`:**
  ```kotlin
  if (packageName != null && isInputMethodPackage(packageName)) {
      return
  }
  ```
- **Forensic Verdict:** **ROBUST.** When a user focuses the `EditText` in `AdminOverlayView`, the soft keyboard window triggers `TYPE_WINDOW_STATE_CHANGED`. Because of the IME filter, LockKeeper does not treat the keyboard as an untracked app and does not dismiss the admin overlay.

### 5.2 Self-Generated Accessibility Events
- When LockKeeper adds an overlay window, the Android framework generates accessibility events for `com.lockkeeper.app`.
- Handled via:
  ```kotlin
  if (packageName == this.packageName) {
      if (overlayManager.isOverlayShowing() ||
          repository.tamperController.activeSession?.state == TamperSessionState.PROMPTING
      ) {
          return
      }
  }
  ```
- This prevents recursive self-lock evaluation during active overlay interaction.

### 5.3 System UI and Launcher Safety
- `isLauncherOrSystemUiPackage` detects launcher packages (`com.miui.home`, `com.sec.android.app.launcher`, `com.android.launcher3`, `nexuslauncher`, and dynamic `CATEGORY_HOME` resolution) and system UI (`com.android.systemui`).
- When a terminal denial (`Blocked`, `RecoveryRequired`, `DenyUnknown`) occurs:
  ```kotlin
  if (!isLauncherOrSystemUiPackage(packageName)) {
      performGlobalAction(GLOBAL_ACTION_HOME)
  }
  ```
- This prevents catastrophic infinite navigation loops if the launcher or notification shade triggers an accessibility event.

---

## 6. Comprehensive Component Correctness Matrix

| Component | Responsibility | Invariant / Guarantee | Verification Status | Identified Vulnerabilities / Notes |
| :--- | :--- | :--- | :--- | :--- |
| **`PinOverlayView`** | PIN entry UI & touch absorption | Child buttons receive clicks; background absorbs touches; Back key routes Home | **VERIFIED CORRECT** | `super.dispatchTouchEvent` properly routes to child `Button` instances; background touches return `true`. |
| **`CooldownOverlayView`** | Cooldown / Lockout timer display | Shows live countdown; prevents touch pass-through; cancels timer on detach | **VERIFIED CORRECT** | `onDetachedFromWindow` cancels `CountDownTimer`; touch absorption verified. |
| **`AdminOverlayView`** | Tamper admin authentication | Secure password input; IME compatibility; touch isolation | **VERIFIED CORRECT** | `SOFT_INPUT_ADJUST_RESIZE` pans layout; IME events filtered; touches absorbed. |
| **`OverlayManager`** | WindowManager attachment, removal, and window type management | Single overlay active at a time; clean removal before add; `TYPE_ACCESSIBILITY_OVERLAY` precedence | **VULNERABILITY IDENTIFIED** | **Stale Remove Race:** `dismissIfShowing` does not match `packageName` in `mainHandler.post`. **Idempotency Gap:** Rapid `show` calls before post execution cause keypad reset. |
| **`TamperAuthorizationController`** | Admin tamper session lifecycle | Immutable terminal states; token-based session verification; monotonic lockout | **VERIFIED CORRECT** | Session tokens prevent stale callback mutations for active sessions. Null-session fallback lacks token protection. |
| **`LockKeeperAccessibilityService`** | Event reception, tamper detection, package evaluation | IME immunity; LockKeeper self-immunity; Launcher loop protection | **VERIFIED CORRECT** | Multi-gate filtering protects against external system events. |

---

## 7. Precise Hardening Specifications

To achieve complete formal correctness across all concurrency and lifecycle edges, the following architectural fixes are specified:

### 7.1 Fix Stale Remove Race in `OverlayManager.dismissIfShowing`
**Current Code:**
```kotlin
@Synchronized
fun dismissIfShowing(packageName: String? = null) {
    if (packageName != null && isInputMethodPackage(packageName)) return
    mainHandler.post {
        if (currentGateType == GateType.ADMIN) {
            return@post
        } else {
            removeCurrentOverlayInternal()
        }
    }
}
```
**Corrected Pattern:**
```kotlin
@Synchronized
fun dismissIfShowing(packageName: String? = null) {
    if (packageName != null && isInputMethodPackage(packageName)) return
    mainHandler.post {
        if (currentGateType == GateType.ADMIN) {
            return@post
        }
        // GUARD: Only dismiss if the currently showing overlay belongs to the dismissed package
        if (packageName == null || currentPackageName == packageName) {
            removeCurrentOverlayInternal()
        }
    }
}
```

### 7.2 Fix MainHandler Idempotency Window in `OverlayManager.showPinOverlay`
**Current Code:**
```kotlin
@Synchronized
fun showPinOverlay(packageName: String) {
    if (currentOverlayView != null && currentPackageName == packageName && currentGateType == GateType.PIN) {
        return
    }
    ...
}
```
**Corrected Pattern:**
Maintain a pending target state variable (e.g. `pendingPackageName`, `pendingGateType`) updated synchronously inside the `@Synchronized` block so that rapid successive calls before `mainHandler` execution are immediately deduplicated.

---

## 8. Final Specialist Conclusion

The LockKeeper overlay subsystem exhibits strong foundational architecture with correct window type prioritization (`TYPE_ACCESSIBILITY_OVERLAY`), comprehensive IME protection, and accurate touch event delivery to keypad child buttons. 

The primary area requiring hardening is the asynchronous package-matching guard in `OverlayManager.dismissIfShowing` to eliminate the `show -> stale remove` race during rapid app switching.
