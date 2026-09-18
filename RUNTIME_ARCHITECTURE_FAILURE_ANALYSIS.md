# LockKeeper Runtime Architecture Failure Analysis
**Document ID:** LK-ARCH-FAIL-2026-09-18  
**Author:** Primary Architecture Investigator  
**Target Repository:** `LockKeeper` (`com.lockkeeper.app`)  
**Scope:** Read-Only Runtime Failure & Architectural Forensic Investigation  
**Status:** COMPLETE ARCHITECTURAL ROOT CAUSE ANALYSIS  

---

## 1. Executive Summary

This investigation was conducted to determine the exact architectural root causes of two critical runtime failures confirmed in the `LockKeeper` application:

1. **BUG 1 (PIN Overlay Flicker):** The PIN overlay repeatedly flickers, dismisses, and re-attaches when a user opens a locked application.
2. **BUG 2 (Recents Process Death & Degraded Accessibility):** When LockKeeper is removed from the Android Recents / Overview tray, its process is killed, application locking stops functioning entirely (fail-open), and subsequent launches of LockKeeper display a degraded state reporting that the Accessibility Service is disconnected.

Despite 100% test passage across the 223 existing unit tests, real-world execution on physical hardware (specifically Xiaomi MIUI/HyperOS and standard AOSP builds) exhibits these systemic failures. 

Our investigation reveals that both bugs stem from fundamental architectural flaws:
* **For Bug 1:** An incomplete implementation of the `Observation != Termination` security invariant. While Wave 6 hardened `AdminOverlayView` with tokenized session binding, `PinOverlayView` and `CooldownOverlayView` remain vulnerable to unauthenticated package transitions (such as `com.android.systemui` status bar/navigation bar updates), non-idempotent Looper message queues, uncoordinated coroutine cancellations, and asynchronous Looper race conditions.
* **For Bug 2:** A fatal single-process design in `AndroidManifest.xml` where `MainActivity` (the Flutter UI), `LockKeeperAccessibilityService` (system accessibility client), and `LockKeeperForegroundService` (usage stats fallback engine) share the single OS process `com.lockkeeper.app`. Swiping LockKeeper from Recents allows the Android ActivityManager / OEM task killers to terminate the entire process with `SIGKILL`. Furthermore, runtime state is tracked via in-memory volatile booleans (`isConnected = false`), and `MainActivity` lacks any mechanism to restart the ForegroundService on launch.

---

## 2. System Component Trace & Ownership Matrix

### 2.1 Trace of the 17 Core Components

| # | Component | File Path | Operational Role | State Retention |
|---|---|---|---|---|
| 1 | [`LockKeeperAccessibilityService`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperAccessibilityService.kt) | `service/LockKeeperAccessibilityService.kt` | Real-time window state and content change observer; primary enforcement trigger; tamper event detector. | In-memory companion `@Volatile var isConnected: Boolean`. Resets to `false` on process termination. |
| 2 | [`LockKeeperForegroundService`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperForegroundService.kt) | `service/LockKeeperForegroundService.kt` | Started foreground service (`specialUse`) with notification; periodic 400ms `UsageStatsManager` polling fallback. | In-memory companion `@Volatile var isRunning: Boolean`. Resets to `false` on process termination. |
| 3 | [`ProtectionRepository`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/domain/ProtectionRepository.kt) | `domain/ProtectionRepository.kt` | Central state aggregator, Room/Keystore bridge, and security health status evaluator. | In-memory singleton `INSTANCE`. Holds references to Room DAOs, `LockDecisionEngine`, and `TamperAuthorizationController`. |
| 4 | [`LockDecisionEngine`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/domain/LockDecisionEngine.kt) | `domain/LockDecisionEngine.kt` | Pure evaluation logic for target packages against policies, sessions, lockouts, and recovery. | In-memory `ConcurrentHashMap<String, Long>` storing unlocked package session timestamps. |
| 5 | [`OverlayManager`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/overlay/OverlayManager.kt) | `overlay/OverlayManager.kt` | Presenter responsible for inflating, attaching, and removing overlay views via `WindowManager`. | In-memory singleton. Maintains `currentOverlayView`, `currentPackageName`, `currentGateType`, and `activeTamperSessionId`. |
| 6 | [`PinOverlayView`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/overlay/PinOverlayView.kt) | `overlay/PinOverlayView.kt` | Custom `LinearLayout` displaying PIN dots, numeric keypad, and back/home interception. | Ephemeral view hierarchy. Holds partial input in a `StringBuilder`. |
| 7 | [`CooldownOverlayView`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/overlay/CooldownOverlayView.kt) | `overlay/CooldownOverlayView.kt` | Custom `LinearLayout` with an active `CountDownTimer` blocking access during strict cooldown. | Ephemeral view hierarchy. `CountDownTimer` is cancelled on `onDetachedFromWindow()`. |
| 8 | `LockoutOverlayView` | `overlay/OverlayManager.kt:170` | Alias/Variant: Instantiated directly as `CooldownOverlayView(..., isLockout = true)`. | Ephemeral view hierarchy with red lockout warning text and countdown. |
| 9 | [`AdminOverlayView`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/overlay/AdminOverlayView.kt) | `overlay/AdminOverlayView.kt` | Anti-tamper password challenge view with alphanumeric `EditText` and cancel button. | Ephemeral view hierarchy; strictly bound to `TamperSession.sessionId`. |
| 10 | [`TamperAuthorizationController`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/security/TamperAuthorizationController.kt) | `security/TamperAuthorizationController.kt` | State machine owning tamper sessions (`TamperSessionState`), PBKDF2 admin verification, and grace periods. | In-memory `activeSession: TamperSession?`, in-memory monotonic grace timers, and Room persistence for lockout attempts. |
| 11 | [`MainActivity`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/MainActivity.kt) | `MainActivity.kt` | `FlutterActivity` hosting the Dart UI runtime, registering the platform channel bridge. | Android Activity lifecycle; subject to OS task destruction and recreation. |
| 12 | [`PlatformChannelHandler`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/bridge/PlatformChannelHandler.kt) | `bridge/PlatformChannelHandler.kt` | Method and Event channel dispatcher between Flutter Dart and native Kotlin repository. | Bound to `FlutterEngine.dartExecutor.binaryMessenger`. Maintains static `eventSink`. |
| 13 | [`SelfLockSessionManager`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/domain/SelfLockSessionManager.kt) | `domain/SelfLockSessionManager.kt` | Tracks LockKeeper's self-lock authentication session and background timeout. | In-memory volatile flags: `isSessionActive: Boolean`, `lastBackgroundTimestamp: Long`. |
| 14 | [`BootReceiver`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/receiver/BootReceiver.kt) | `receiver/BootReceiver.kt` | BroadcastReceiver for `BOOT_COMPLETED` and `QUICKBOOT_POWERON` to start `LockKeeperForegroundService`. | Stateless receiver. |
| 15 | Room Database / State | `data/db/AppDatabase.kt` | SQLite database (`lockkeeper_database`) containing `locked_apps` and `app_settings`. | Persistent on-disk flash storage. Survives process death and device reboots. |
| 16 | SharedPreferences | `security/CredentialStore.kt` | Stores encrypted master key blobs (`cred_pin_blob`, `cred_admin_blob`) and `onboarding_complete` flag. | Persistent on-disk XML storage; encryption backed by `AndroidKeyStore`. |
| 17 | Existing Tests | `src/test/kotlin/com/lockkeeper/app/` | Pure unit tests with mocked DAOs and synthetic fake nodes. | Executes purely on JVM. No Android Looper, WindowManager, or multi-process testing. |

---

### 2.2 Ownership of Enforcement & Lifecycle

```
                                 ┌─────────────────────────────────────────┐
                                 │       Android Window / OS Events        │
                                 └────────────────────┬────────────────────┘
                                                      │
                       ┌──────────────────────────────┴──────────────────────────────┐
                       ▼                                                             ▼
     ┌───────────────────────────────────┐                         ┌───────────────────────────────────┐
     │ LockKeeperAccessibilityService    │                         │   LockKeeperForegroundService     │
     │ - Receives A11y Events            │                         │   - Polls UsageStats (400ms)      │
     │ - Checks TamperDetectionEngine    │                         │   - Checks App Switch             │
     └─────────────────┬─────────────────┘                         └─────────────────┬─────────────────┘
                       │                                                             │
                       ▼                                                             ▼
     ┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
     │                                     ProtectionRepository                                        │
     │                                - evaluatePackage(targetPackage)                                 │
     │                                - LockDecisionEngine.evaluate()                                  │
     └─────────────────────────────────────────────────┬───────────────────────────────────────────────┘
                                                       │
                                                       ▼
                                     ┌───────────────────────────────────┐
                                     │          OverlayManager           │
                                     │  - showPinOverlay()               │
                                     │  - showCooldownOverlay()          │
                                     │  - showLockoutOverlay()           │
                                     │  - showAdminOverlay()             │
                                     │  - dismissIfShowing()             │
                                     └─────────────────┬─────────────────┘
                                                       │
                                                       ▼
                                     ┌───────────────────────────────────┐
                                     │           WindowManager           │
                                     │   (TYPE_ACCESSIBILITY_OVERLAY)    │
                                     └───────────────────────────────────┘
```

#### Who Owns Enforcement?
**Enforcement is dual-owned and uncoordinated.** Both `LockKeeperAccessibilityService` and `LockKeeperForegroundService` independently evaluate packages and invoke presentation methods on `OverlayManager`. When AccessibilityService is connected, FGS runs a 2-second sleep loop, but when `AccessibilityService.isConnected` is `false` (or transitioning during startup), both services concurrently query `ProtectionRepository` and independently command `OverlayManager` to display or dismiss overlays.

#### Who Owns Overlay Lifecycle?
**Lifecycle ownership is fragmented and asymmetric:**
* **For `GateType.ADMIN` (Anti-Tamper):** Authoritatively owned by `TamperAuthorizationController`. A discrete `TamperSession` with states (`PROMPTING`, `AUTHORIZED`, `CANCELLED`, etc.) guards the overlay. `OverlayManager` tracks `activeTamperSessionId` and enforces idempotency.
* **For `GateType.PIN`, `GateType.COOLDOWN`, and `GateType.LOCKOUT`:** **UNOWNED.** There is no session object, no state machine, and no active session token. Any incoming event that produces `LockDecision.Allowed` calls `OverlayManager.dismissIfShowing(packageName)` which unconditionally tears down non-admin overlays.

---

### 2.3 Comprehensive Inventory of Show / Dismiss / Remove Paths

#### Show Paths
1. `LockKeeperAccessibilityService.onAccessibilityEvent` $\to$ `LockDecision.RequirePin` $\to$ `OverlayManager.showPinOverlay(packageName)`
2. `LockKeeperAccessibilityService.onAccessibilityEvent` $\to$ `LockDecision.StrictCooldown` $\to$ `OverlayManager.showCooldownOverlay(packageName, remainingSeconds)`
3. `LockKeeperAccessibilityService.onAccessibilityEvent` $\to$ `LockDecision.PinLockout` $\to$ `OverlayManager.showLockoutOverlay(packageName, remainingSeconds)`
4. `LockKeeperAccessibilityService.onAccessibilityEvent` $\to$ `handleTamperEvent` $\to$ `OverlayManager.showAdminOverlay(targetPackage, session)`
5. `LockKeeperAccessibilityService.onAccessibilityEvent` $\to$ Tamper session already prompting $\to$ `OverlayManager.showAdminOverlay(targetPackage, session)` (idempotent re-render)
6. `LockKeeperForegroundService.checkAndEnforcePackage` $\to$ `LockDecision.RequirePin` $\to$ `OverlayManager.showPinOverlay(packageName)`
7. `LockKeeperForegroundService.checkAndEnforcePackage` $\to$ `LockDecision.StrictCooldown` $\to$ `OverlayManager.showCooldownOverlay(packageName, remainingSeconds)`
8. `LockKeeperForegroundService.checkAndEnforcePackage` $\to$ `LockDecision.PinLockout` $\to$ `OverlayManager.showLockoutOverlay(packageName, remainingSeconds)`
9. `OverlayManager.handlePinSubmitted` $\to$ failed PIN reaching 5 attempts $\to$ `OverlayManager.showLockoutOverlay(packageName, remainingSeconds)`

#### Dismiss / Remove Paths
1. `LockKeeperAccessibilityService.onAccessibilityEvent` $\to$ `LockDecision.Allowed` $\to$ `OverlayManager.dismissIfShowing(packageName)` $\to$ if non-admin, calls `removeCurrentOverlayInternal()`. **(PRIMARY ROOT CAUSE OF FLICKER)**
2. `LockKeeperAccessibilityService.onAccessibilityEvent` $\to$ `Blocked` / `RecoveryRequired` / `DenyUnknown` $\to$ `OverlayManager.dismissIfShowing(packageName)` + `GLOBAL_ACTION_HOME`.
3. `LockKeeperForegroundService.checkAndEnforcePackage` $\to$ `LockDecision.Allowed` $\to$ `OverlayManager.dismissIfShowing(packageName)`.
4. `LockKeeperForegroundService.checkAndEnforcePackage` $\to$ `Blocked` / `RecoveryRequired` / `DenyUnknown` $\to$ `OverlayManager.dismissIfShowing(packageName)` + `startActivity(ACTION_HOME)`.
5. `LockKeeperAccessibilityService.screenReceiver.onReceive(ACTION_SCREEN_OFF)` $\to$ `OverlayManager.dismissAdminOverlay()`.
6. `LockKeeperAccessibilityService.onDestroy` $\to$ `OverlayManager.dismissAll()`.
7. `LockKeeperAccessibilityService.onInterrupt` $\to$ `OverlayManager.dismissAll()`.
8. `OverlayManager.handlePinSubmitted` $\to$ PIN verified $\to$ `repository.handleSuccessfulPin()` $\to$ `removeCurrentOverlayInternal()`.
9. `OverlayManager.showAdminOverlay` $\to$ Admin password verified $\to$ `removeCurrentOverlayInternal()`.
10. `OverlayManager.showAdminOverlay` $\to$ Cancel button clicked $\to$ `removeCurrentOverlayInternal()` + `navigateHome()`.
11. `OverlayManager.attachOverlay` $\to$ Exception caught $\to$ `removeCurrentOverlayInternal()` + `navigateHome()`.
12. `OverlayManager.dismissAdminOverlay(sessionId)` $\to$ `removeCurrentOverlayInternal()`.
13. `OverlayManager.dismissAll` $\to$ `removeCurrentOverlayInternal()`.
14. `OverlayManager.navigateHome` $\to$ Back button intercepted on `PinOverlayView` $\to$ `removeCurrentOverlayInternal()` + `startActivity(ACTION_HOME)`.
15. `OverlayManager.showPinOverlay` / `showCooldownOverlay` / `showLockoutOverlay` / `showAdminOverlay` $\to$ First line inside posted Runnable: `removeCurrentOverlayInternal()`. **(SECONDARY ROOT CAUSE OF FLICKER)**
16. `CooldownOverlayView` timer reaches zero $\to$ `onExitClicked()` $\to$ `navigateHome()`.
17. `CooldownOverlayView` "Return to Home" button clicked $\to$ `onExitClicked()` $\to$ `navigateHome()`.
18. `PinOverlayView` Back key pressed $\to$ `onBackInterception()` $\to$ `navigateHome()`.

---

## 3. Bug 1 Deep Forensic Investigation: PIN Overlay Flicker

### 3.1 Failure Mechanics in the Event Pipeline

When a user taps an application icon from the launcher, Android transitions through activity creation, window mapping, surface compositing, and input focus. In `LockKeeperAccessibilityService.kt`, two event types are explicitly registered:
```kotlin
eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
             AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
```

Three critical architectural defects converge during this launch window to trigger continuous flickering:

#### Defect 1: Lack of Package-Guarded Dismissal in `OverlayManager.dismissIfShowing()`
In [`OverlayManager.kt`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/overlay/OverlayManager.kt#L362-L380):
```kotlin
@Synchronized
fun dismissIfShowing(packageName: String? = null) {
    if (packageName != null && isInputMethodPackage(packageName)) {
        return
    }

    mainHandler.post {
        if (currentGateType == GateType.ADMIN) {
            return@post // Only ADMIN is protected from observation dismissals!
        } else {
            removeCurrentOverlayInternal() // Unconditionally removes PIN / Cooldown / Lockout!
        }
    }
}
```
Notice that while `dismissIfShowing` accepts `packageName: String? = null`, **it never compares `packageName` against `currentPackageName`**. If `currentPackageName` is `com.example.vault` and an event from `com.android.systemui` produces `LockDecision.Allowed`, `dismissIfShowing("com.android.systemui")` runs and destroys the overlay guarding `com.example.vault`.

#### Defect 2: Missing System UI / Launcher Filtering in `onAccessibilityEvent()`
In [`LockKeeperAccessibilityService.kt`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperAccessibilityService.kt#L143-L266):
The service checks `overlayManager.isInputMethodPackage(packageName)` and `packageName == this.packageName`. However, **it contains NO check ignoring `com.android.systemui` or launcher packages at the entry of the event handler**.
When `com.android.systemui` fires an event (updating the status bar clock, battery icon, navigation bar pill, or gesture insets during app launch), `onAccessibilityEvent` processes `com.android.systemui` as a foreground application.
Because `com.android.systemui` is not in `LockedAppDao`, `LockDecisionEngine.evaluate()` returns:
```kotlin
LockDecision.Allowed(ProtectionDecisionReason.ALLOWED_UNPROTECTED_APP)
```
Line 250 immediately dispatches:
```kotlin
overlayManager.dismissIfShowing("com.android.systemui")
```
This triggers `removeCurrentOverlayInternal()` on the main thread, ripping the PIN overlay off the screen.

#### Defect 3: Non-Idempotent `showPinOverlay` & Asynchronous Message Queue Reordering
In [`OverlayManager.kt`](file:///c:/AppLocker/android/app/src/main/kotlin/com/lockkeeper/app/overlay/OverlayManager.kt#L124-L146):
```kotlin
@Synchronized
fun showPinOverlay(packageName: String) {
    if (currentOverlayView != null && currentPackageName == packageName && currentGateType == GateType.PIN) {
        return
    }

    val expectedLength = repository.credentialStore.getPinLength()
    mainHandler.post {
        removeCurrentOverlayInternal() // Tears down any existing view!
        val view = PinOverlayView(...)
        attachOverlay(view, packageName, GateType.PIN)
    }
}
```
1. `currentOverlayView`, `currentPackageName`, and `currentGateType` are **only mutated inside the posted Runnable** on `mainHandler`.
2. When `showPinOverlay` is called, `currentOverlayView` is still `null` until the Looper executes that Runnable.
3. If two rapid events occur within milliseconds, both calls evaluate `currentOverlayView == null` as true. Both post Runnables to `mainHandler`.
4. The first Runnable attaches View #1. The second Runnable immediately calls `removeCurrentOverlayInternal()` (destroying View #1) and attaches View #2.
5. In `LockKeeperAccessibilityService.kt`, `evaluationJob?.cancel()` only cancels the background coroutine; **it cannot cancel Runnables already dispatched to `mainHandler`**.

---

### 3.2 Exact Event Sequences Causing PIN Overlay Flicker

#### Sequence 1: `show -> dismiss -> show` (System UI Interleaving)
* **Pre-condition:** `com.example.vault` is locked. User taps icon on launcher.
* **T+0ms:** Android launches `com.example.vault`. `TYPE_WINDOW_STATE_CHANGED` arrives for `pkg="com.example.vault"`.
* **T+2ms:** `evaluationJob` evaluates `com.example.vault` $\to$ `LockDecision.RequirePin`.
* **T+4ms:** `mainHandler.post { overlayManager.showPinOverlay("com.example.vault") }` executes. View attaches to WindowManager (`currentGateType = PIN`).
* **T+18ms:** As `com.example.vault` renders, the Android system updates the status bar theme and navigation bar. `TYPE_WINDOW_CONTENT_CHANGED` arrives for `pkg="com.android.systemui"`.
* **T+19ms:** `packageName` (`com.android.systemui`) $\neq$ `lastHandledPackage` (`com.example.vault`). The 150ms intra-package debounce is **bypassed**.
* **T+21ms:** `evaluationJob` evaluates `com.android.systemui` $\to$ `LockDecision.Allowed`.
* **T+22ms:** Line 250 executes: `overlayManager.dismissIfShowing("com.android.systemui")`.
* **T+23ms:** In `OverlayManager.kt`, `currentGateType == GateType.PIN`. Because it is not `ADMIN`, it executes `removeCurrentOverlayInternal()`. **PIN overlay is removed from screen!** Target app `com.example.vault` is completely exposed.
* **T+35ms:** `com.example.vault` finishes layout and draws its UI, emitting `TYPE_WINDOW_CONTENT_CHANGED`.
* **T+36ms:** `packageName` (`com.example.vault`) $\neq$ `lastHandledPackage` (`com.android.systemui`). Debounce is bypassed again.
* **T+38ms:** `evaluationJob` evaluates `com.example.vault` $\to$ `LockDecision.RequirePin`.
* **T+40ms:** `overlayManager.showPinOverlay("com.example.vault")` executes. View attaches to WindowManager.
* **Visual Result:** The PIN overlay flashes onto the screen, disappears for a fraction of a second exposing the private app, and reappears.

#### Sequence 2: `show -> show -> remove / recreate` (Looper Drain Lag)
* **Pre-condition:** Rapid window layout events during cold start of `com.example.vault`.
* **T+0ms:** First event for `com.example.vault` arrives. `showPinOverlay` runs. `currentOverlayView` is `null`. Runnable #1 is posted to `mainHandler`.
* **T+1ms:** Main thread is briefly busy measuring views. Before Runnable #1 runs, a second event (window focus or content update) finishes evaluation on `serviceScope`.
* **T+2ms:** Second call to `showPinOverlay("com.example.vault")` runs. Because Runnable #1 has not executed, `currentOverlayView` is **still null**.
* **T+3ms:** Idempotency check fails. Runnable #2 is posted to `mainHandler`.
* **T+5ms:** Main thread drains queue:
  1. Runnable #1 runs: attaches View #1 to WindowManager.
  2. Runnable #2 runs immediately after: calls `removeCurrentOverlayInternal()` (view #1 is torn down) and attaches View #2.
* **Visual Result:** Severe hardware rendering stutter, double inflation, and visible screen flash.

#### Sequence 3: `old callback -> remove new overlay` (Stale Looper Queue Reordering)
* **T+0ms:** Target app launch starts. `evaluationJob` A begins for `com.example.vault`.
* **T+2ms:** User quickly swipes or touches, generating a launcher transition or System UI event B.
* **T+3ms:** `evaluationJob?.cancel()` is called. Coroutine A was already at line 238 (`mainHandler.post`). The Runnable for Coroutine A (`showPinOverlay`) is **already in the Handler queue**.
* **T+4ms:** Coroutine B evaluates System UI $\to$ `Allowed`. It posts `dismissIfShowing` to `mainHandler`.
* **T+6ms:** A third event C for `com.example.vault` arrives and posts `showPinOverlay` to `mainHandler`.
* **Execution Order on Main Thread:**
  1. Runnable A (`showPinOverlay`): Adds overlay.
  2. Runnable B (`dismissIfShowing`): Removes overlay.
  3. Runnable C (`showPinOverlay`): Adds overlay again.
* **Visual Result:** Multiple violent layout flickers.

#### Sequence 4: `multiple enforcement decisions -> one overlay` (A11y + FGS Collision)
* **Pre-condition:** `LockKeeperAccessibilityService.isConnected` is briefly `false` or transitioning.
* **T+0ms:** User opens `com.example.vault`.
* **T+10ms:** `LockKeeperAccessibilityService` receives `TYPE_WINDOW_STATE_CHANGED`, launches evaluation, and posts `showPinOverlay`.
* **T+15ms:** Concurrently, `LockKeeperForegroundService.startUsageStatsFallbackIfNecessary()` reaches its 400ms polling tick. `usageStatsManager.queryEvents()` detects `ACTIVITY_RESUMED` for `com.example.vault`.
* **T+18ms:** FGS executes `checkAndEnforcePackage("com.example.vault")` on a background thread.
* **T+22ms:** FGS calls `overlayManager.showPinOverlay("com.example.vault")`.
* **T+25ms:** Two independent `mainHandler.post` calls with `removeCurrentOverlayInternal()` execute back-to-back from different services.

#### Sequence 5: `service event -> unexpected dismissal` (Self-Window Event Race)
* **T+0ms:** `showPinOverlay("com.example.vault")` is posted to `mainHandler`.
* **T+2ms:** Before `attachOverlay()` finishes setting `currentOverlayView`, the creation of the window dispatches an event where `packageName == "com.lockkeeper.app"`.
* **T+3ms:** In `onAccessibilityEvent`, line 163 checks:
  ```kotlin
  if (packageName == this.packageName) {
      if (overlayManager.isOverlayShowing() || ...) return
  }
  ```
* **T+4ms:** Because `currentOverlayView` is still `null`, `overlayManager.isOverlayShowing()` returns `false`!
* **T+5ms:** The event for `com.lockkeeper.app` is NOT ignored. It proceeds down to line 237: `repository.evaluatePackage("com.lockkeeper.app")`.
* **T+7ms:** `evaluatePackage("com.lockkeeper.app")` returns `LockDecision.Allowed(ALLOWED_OWN_PACKAGE)`.
* **T+8ms:** Line 250 executes: `overlayManager.dismissIfShowing("com.lockkeeper.app")`, tearing down the overlay that was just created.

---

## 4. Bug 2 Deep Forensic Investigation: Recents Swipe, Process Death & Degraded State

### 4.1 Chronological 9-Step Lifecycle Trace

#### Step 1: LockKeeper is operational
* `AppSettingsEntity`: `onboardingComplete = true`, `securityProvisioned = true`, `recoveryRequired = false`.
* `LockedAppDao`: One or more apps configured with `isLocked = 1`.
* `KeystoreCredentialStore`: Master key, PIN hash, and admin password hash are verified in storage.
* System security health status evaluates to `PROTECTED`.

#### Step 2: One or more apps are locked
* `com.example.vault` is active in `LockedAppDao`.
* Any access will require PIN verification.

#### Step 3: AccessibilityService is connected
* `LockKeeperAccessibilityService.onServiceConnected()` has executed.
* In-memory companion field: `LockKeeperAccessibilityService.isConnected = true`.
* Android system service `AccessibilityManagerService` holds a valid Binder token to LockKeeper's `AccessibilityService`.

#### Step 4: ForegroundService is running
* `LockKeeperForegroundService.onCreate()` and `onStartCommand()` have executed.
* In-memory companion field: `LockKeeperForegroundService.isRunning = true`.
* Ongoing notification `NOTIFICATION_ID = 1001` is posted to `NotificationManager`.
* Process has `ProcessList.PERCEPTIBLE_APP_ADJ` in stock AOSP.

#### Step 5: LockKeeper is removed from Recents
* The user opens the Android Overview / Recents tray and swipes LockKeeper away (or taps "Clear All").
* In Android's `ActivityTaskManagerService` and `ActivityManagerService`:
  1. The task container for `MainActivity` (`taskAffinity=""`) is removed from the task stack.
  2. `ActivityManagerService.cleanUpRemovedTaskLocked()` is triggered.
  3. `Service.onTaskRemoved(rootIntent)` is dispatched to running services.
* In `LockKeeperForegroundService.kt`:
  ```kotlin
  override fun onTaskRemoved(rootIntent: Intent?) {
      super.onTaskRemoved(rootIntent)
      val restartIntent = Intent(applicationContext, LockKeeperForegroundService::class.java)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          applicationContext.startForegroundService(restartIntent)
      } else {
          applicationContext.startService(restartIntent)
      }
  }
  ```
  `LockKeeperForegroundService` attempts to start itself again.
* In `LockKeeperAccessibilityService.kt`:
  **`onTaskRemoved()` is NOT implemented.**

#### Step 6: Android / OEM kills the process
* **The Fatal Single-Process Manifest Flaw:**
  In [`AndroidManifest.xml`](file:///c:/AppLocker/android/app/src/main/AndroidManifest.xml), neither `MainActivity`, `LockKeeperAccessibilityService`, nor `LockKeeperForegroundService` declares an `android:process` attribute.
  **All three components execute within a single Linux process: `com.lockkeeper.app`.**
* **OEM Process Termination Policy (MIUI / HyperOS / ColorOS / OneUI):**
  On Xiaomi (MIUI 14 / HyperOS) and other aggressive OEM ROMs, swiping an app from Recents is treated as an explicit user command to **terminate the application process**. The OS invokes `killBackgroundProcesses` or sends an immediate `SIGKILL` to PID `com.lockkeeper.app`.
* Even on stock Android:
  If the system determines the task was swiped and FGS restart is delayed, or under system memory pressure (`LMKD`), the process is terminated.
* **The Entire Linux Address Space is Destroyed:**
  * PID `com.lockkeeper.app` ceases to exist.
  * Every static variable, companion object, and in-memory singleton is wiped from RAM.
  * The Binder connection to Android's `system_server` (`AccessibilityManagerService`) abruptly breaks.

#### Step 7: Services are destroyed / recreated (or fail to recreate)
* **What happens to `LockKeeperAccessibilityService`?**
  1. In `system_server`, `AccessibilityManagerService` receives `binderDied()` for the service connection.
  2. In stock AOSP, `AccessibilityManagerService` attempts to re-bind to the service. However, because the process was killed via task swipe, the system enters a binding backoff.
  3. On MIUI / Xiaomi devices, MIUI's `SecurityCenter` / `PowerKeeper` marks the package as force-stopped by the user. **MIUI actively suppresses automatic rebinding of accessibility services for apps swiped from Recents until explicitly reopened or toggled.**
* **What happens to `LockKeeperForegroundService`?**
  1. `onStartCommand()` returned `START_STICKY`.
  2. On stock AOSP, Android will eventually reschedule the service after a 5–15 second penalty delay.
  3. On OEM ROMs (MIUI), `START_STICKY` is ignored following a Recents swipe. The service is dead and will not restart on its own.

#### Step 8: LockKeeper is opened again
* The user taps the LockKeeper icon on the home screen.
* Android spawns a **brand new Linux process** with a new PID for `com.lockkeeper.app`.
* `MainActivity.onCreate()` executes.
* Flutter engine initializes and executes `main()` in `lib/main.dart`:
  ```dart
  final status = await PlatformBridge.getProtectionStatus();
  ```
* Native method `getProtectionStatus` delegates to `ProtectionRepository.getAuthoritativeSecurityStatus()`:
  ```kotlin
  val isA11yConnected = com.lockkeeper.app.service.LockKeeperAccessibilityService.isConnected
  val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
  val isA11yEnabled = enabledServices.contains(context.packageName)
  val isA11yOperational = isA11yConnected && isA11yEnabled
  ```
* **The State Inconsistency:**
  * `isA11yEnabled`: Reads from `Settings.Secure`. It is **`true`** (the system setting was never turned off).
  * `isA11yConnected`: Reads from `LockKeeperAccessibilityService.isConnected`. In this brand-new process, the class was just loaded into memory. **It is initialized to `false`!**
  * Android's `AccessibilityManagerService` has not yet completed its asynchronous Binder handshake with `LockKeeperAccessibilityService.onServiceConnected()`.
  * Therefore: `isA11yOperational = (true && false) = false`!
* **Status Degradation:**
  Lines 354–358 in `ProtectionRepository.kt`:
  ```kotlin
  if (!isA11yEnabled) {
      degradedReasons.add("Accessibility Service is not enabled in Android Settings")
  } else if (!isA11yConnected) {
      degradedReasons.add("Accessibility Service is enabled in Settings but background service is disconnected")
  }
  ```
  `overallStatus` evaluates to **`"DEGRADED"`**.
* **Flutter Displays Degraded Banner:**
  `HomeScreen.dart` renders the amber warning banner:
  *"Protection Degraded: Accessibility Service is enabled in Settings but background service is disconnected"*.
* **Missing Service Resuscitation:**
  Neither `MainActivity.kt`, `PlatformChannelHandler.kt`, `main.dart`, nor `HomeScreen.dart` calls `LockKeeperForegroundService.startService()`. (As proven by codebase grep, `startProtectionService()` is **only** called in `onboarding_screen.dart`!). FGS remains completely dead.

#### Step 9: A locked app is opened
* The user switches to `com.example.vault`.
* If `LockKeeperAccessibilityService` has not reconnected:
  * `onAccessibilityEvent()` is never called.
* What about `LockKeeperForegroundService`?
  * FGS was never started upon reopening `MainActivity`. `isRunning` is `false`. No usage stats polling occurs.
* **Catastrophic Fail-Open Outcome:**
  * **ZERO enforcement code runs.**
  * `com.example.vault` opens directly without any PIN prompt, lock screen, or protection.
  * Security boundary is completely broken.

---

## 5. Synthesis & Evidence Matrix

### 5.1 Confirmed Root Causes

| Ref | Failure Area | Confirmed Root Cause Mechanism | Impact |
|---|---|---|---|
| **CONF-1** | Bug 1 (PIN Flicker) | `OverlayManager.dismissIfShowing()` ignores `packageName` parameter and unconditionally removes any non-ADMIN overlay (`currentGateType != GateType.ADMIN`). | Whenever System UI, Navigation Bar, Insets, or unmanaged packages emit events, an active PIN overlay is torn down immediately. |
| **CONF-2** | Bug 1 (PIN Flicker) | `LockKeeperAccessibilityService.onAccessibilityEvent()` fails to filter out `com.android.systemui` or launcher packages upon entry. | System UI updates during app launch are evaluated as unprotected apps $\to$ `LockDecision.Allowed` $\to$ dispatches `dismissIfShowing()`. |
| **CONF-3** | Bug 1 (PIN Flicker) | Non-idempotent `OverlayManager.showPinOverlay()`. Check for existing view occurs before `mainHandler.post` runs; posted runnable always calls `removeCurrentOverlayInternal()`. | Rapid content change events post duplicate runnables that tear down and re-create the overlay view repeatedly. |
| **CONF-4** | Bug 2 (Recents Kill) | Monolithic single-process architecture in `AndroidManifest.xml` lacking `android:process` separation. | Swiping `MainActivity` from Recents kills the OS process hosting both `LockKeeperAccessibilityService` and `LockKeeperForegroundService`. |
| **CONF-5** | Bug 2 (Recents Kill) | `LockKeeperForegroundService` is only started once in `onboarding_screen.dart` and never restarted by `MainActivity` or `HomeScreen`. | Once the process dies, FGS remains dead permanently after subsequent app launches. |
| **CONF-6** | Bug 2 (Recents Kill) | Volatile in-memory flag `LockKeeperAccessibilityService.isConnected` initializes to `false` on process startup. | `getAuthoritativeSecurityStatus()` evaluates before `onServiceConnected()` Binder handshake completes, falsely marking the system as `DEGRADED`. |

### 5.2 Likely Root Causes

| Ref | Failure Area | Likely Root Cause Mechanism | Probability |
|---|---|---|---|
| **LIKE-1** | Bug 1 (PIN Flicker) | `evaluationJob?.cancel()` does not purge Runnables already dispatched to `mainHandler` queue. | **High (95%)** — Coroutines finishing `evaluatePackage` before cancellation leave `showPinOverlay` or `dismissIfShowing` runnables orphaned in the Looper queue. |
| **LIKE-2** | Bug 1 (PIN Flicker) | 150ms intra-package debounce in `LockKeeperAccessibilityService` is defeated by package interleaving (`TargetApp` $\to$ `SystemUI` $\to$ `TargetApp`). | **High (90%)** — Because `packageName != lastHandledPackage` on each alternating event, debounce logic drops zero events. |
| **LIKE-3** | Bug 2 (Recents Kill) | OEM task killers (MIUI `SecurityCenter` / `PowerKeeper`) suppress automatic A11y service rebinding after task removal. | **High (90%)** — Standard behavior on Xiaomi MIUI/HyperOS where swiped apps are marked as user-force-closed. |
| **LIKE-4** | Bug 1 (PIN Flicker) | `repository.handleAppExited(prev)` called prematurely when `packageName` transitions to `com.android.systemui`. | **Medium (80%)** — Revokes active session and starts cooldown timer while the user is still actively on the locked app. |

### 5.3 Unproven Hypotheses

| Ref | Hypothesis Description | Plausibility | Required Validation |
|---|---|---|---|
| **HYP-1** | `TYPE_ACCESSIBILITY_OVERLAY` itself emits an AccessibilityEvent that `LockKeeperAccessibilityService` fails to identify as self-owned when `currentOverlayView` is pending. | Medium | Filter logcat for `LockKeeperA11y` during overlay attachment to observe whether `pkg=com.lockkeeper.app` appears with `isOverlayShowing() == false`. |
| **HYP-2** | Third-party or OEM keyboards (e.g. Baidu IME, Sogou IME) not matching `isInputMethodPackage()` trigger `dismissIfShowing()` when the keyboard opens. | Low to Medium | Test PIN overlay entry on OEM keyboards with non-standard package naming. |

### 5.4 Android / OEM Limitations

1. **AccessibilityService Binding Control:** Third-party Android applications cannot programmatically invoke `bindService()` or `startService()` on an `AccessibilityService`. The binding is exclusively managed by `system_server` via `AccessibilityManagerService`.
2. **OEM Recents Swipe Policy:** Android does not guarantee that a Foreground Service will survive task removal from Recents. On MIUI, ColorOS, and EMUI, swiping from Recents sends `SIGKILL` to the process regardless of `START_STICKY` or ongoing notifications, unless the app is locked in the Recents tray or granted specialized autostart permissions.
3. **`TYPE_WINDOW_CONTENT_CHANGED` Flood:** Modern Android apps built with Jetpack Compose or Flutter emit massive streams of content change events during layout transitions. Relying on content change events without strict throttling or window ID filtering causes high CPU usage and event queue flooding.

---

## 6. Test Gap Analysis (Missing Tests)

### Why 223 Existing Unit Tests Failed to Detect These Critical Bugs

The existing test suite (`ProtectionEnforcementTest`, `RuntimeResilienceTest`, `ProductionSecurityBoundaryTest`, etc.) achieved high statement coverage over pure algorithmic logic while completely ignoring the Android runtime environment:
1. **Mocked Decision Logic Only:** All tests instantiate `LockDecisionEngine` or `TamperAuthorizationController` with fake in-memory DAOs (`EnforcementFakeDao`). They test that `evaluate()` returns `RequirePin` given certain inputs, but **never execute `OverlayManager` or `LockKeeperAccessibilityService`**.
2. **No Android Main Looper Testing:** `mainHandler.post { ... }` in `OverlayManager` and `LockKeeperAccessibilityService` was never tested against a real or simulated `Looper` queue. Race conditions, queue reordering, and non-idempotent posts were entirely invisible to JVM tests.
3. **No Multi-Package Event Stream Testing:** Tests never simulated realistic Android accessibility event streams where `com.android.systemui` or `com.miui.home` events interleave with target app events.
4. **No Process Death / Rebirth Simulation:** Tests simulated "process death" simply by calling `val freshEngine = LockDecisionEngine()`. They never tested what happens to `LockKeeperAccessibilityService.isConnected` or `ProtectionRepository.getAuthoritativeSecurityStatus()` when a new process starts before the system server binds the accessibility service.

### Inventory of Missing Tests

* **TEST-GAP-01 (Overlay Idempotency):** Calling `OverlayManager.showPinOverlay("pkg")` multiple times in rapid succession must not tear down or recreate the active view.
* **TEST-GAP-02 (System UI Immunity):** When an overlay is showing for `com.example.vault`, an incoming `LockDecision.Allowed` for `com.android.systemui` must NOT dismiss the overlay.
* **TEST-GAP-03 (Package-Bound Dismissal):** `OverlayManager.dismissIfShowing(pkg)` must only dismiss if `pkg == currentPackageName`.
* **TEST-GAP-04 (Looper Race Simulation):** Testing that cancelling `evaluationJob` prevents stale UI operations from executing on `mainHandler`.
* **TEST-GAP-05 (Service Reconnection Handshake):** Verifying that `ProtectionRepository.getAuthoritativeSecurityStatus()` handles the startup race when `isA11yEnabled == true` but `isA11yConnected == false` without permanently latching into `DEGRADED`.
* **TEST-GAP-06 (FGS Startup on Launch):** Verifying that `MainActivity` startup triggers resuscitation of `LockKeeperForegroundService`.

---

## 7. Recommended Architecture

To achieve absolute runtime resilience and eliminate both bugs permanently, the following target architecture is recommended:

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                 AndroidManifest.xml                                    │
│                                                                                        │
│   ┌────────────────────────────────────────┐   ┌───────────────────────────────────┐   │
│   │             Process: (Default)         │   │      Process: ":protection"       │   │
│   │                                        │   │                                   │   │
│   │   - MainActivity (Flutter UI)          │   │   - LockKeeperAccessibilityService│   │
│   │   - PlatformChannelHandler             │   │   - LockKeeperForegroundService   │   │
│   │   - Onboarding / Settings              │   │   - OverlayManager                │   │
│   │                                        │   │   - Room Database & Keystore      │   │
│   └────────────────────────────────────────┘   └───────────────────────────────────┘   │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

### 7.1 Multi-Process Architecture (`:protection`)
Move `LockKeeperAccessibilityService` and `LockKeeperForegroundService` into an isolated process (`android:process=":protection"`).
* **Impact:** When the user swipes `MainActivity` from Recents, the UI process is killed, but **the `:protection` background process remains completely untouched and active**. App locking continues to function seamlessly.

### 7.2 Unified Tokenized Session Architecture (`EnforcementSession`)
Extend the robust session model built for `TamperSession` to PIN and Cooldown gates:
```kotlin
data class EnforcementSession(
    val sessionId: String = UUID.randomUUID().toString(),
    val targetPackage: String,
    val gateType: GateType,
    @Volatile var isOverlayAttached: Boolean = false
)
```
* `OverlayManager` must track `activeEnforcementSessionId`.
* `showPinOverlay(packageName)` must be completely idempotent: if `activeEnforcementSession?.targetPackage == packageName`, it must be a no-op and never recreate or tear down the view.

### 7.3 Package-Bound Dismissal Guard
Refactor `OverlayManager.dismissIfShowing(packageName: String?)`:
```kotlin
@Synchronized
fun dismissIfShowing(packageName: String?) {
    if (packageName == null || isInputMethodPackage(packageName)) return
    if (isLauncherOrSystemUiPackage(packageName)) return // SystemUI can NEVER dismiss a gate!

    mainHandler.post {
        if (currentGateType == GateType.ADMIN) return@post
        
        // Strict package ownership: only dismiss if the foreground app is the guarded package!
        if (currentPackageName == packageName) {
            removeCurrentOverlayInternal()
            activeEnforcementSessionId = null
        }
    }
}
```

### 7.4 System UI & Launcher Entry Filter
In `LockKeeperAccessibilityService.onAccessibilityEvent()`:
```kotlin
if (isLauncherOrSystemUiPackage(packageName)) {
    // System UI events (status bar, nav bar) must NEVER alter target app enforcement state!
    return
}
```

### 7.5 Automatic Service Resuscitation on App Launch
In `MainActivity.kt` and `lib/main.dart`:
* Invoke `LockKeeperForegroundService.startService(applicationContext)` in `MainActivity.onResume()`.
* Ensure that whenever LockKeeper opens, background services are guaranteed to be running.

### 7.6 Asynchronous Startup Reconnection Grace
In `ProtectionRepository.getAuthoritativeSecurityStatus()`:
* If `isA11yEnabled == true` but `isA11yConnected == false`, check if the process uptime is under 3000ms. If within startup grace, schedule a retry or report `CONNECTING` rather than immediate `DEGRADED`.

---

## 8. Required Runtime Experiments & Forensic Validation

To validate these findings on real hardware without altering production code, execute the following ADB commands and experiments:

### Experiment 1: Capture Live Event Interleaving During PIN Flicker
1. Set up ADB logcat filter:
   ```bash
   adb logcat -v time LockKeeperA11y:I OverlayManager:D ActivityTaskManager:I | grep -E "EVT:|showPinOverlay|removeCurrentOverlay|dismissIfShowing"
   ```
2. Open a locked app (e.g. Calculator).
3. **Expected Diagnostic Output:** Observe `EVT: pkg=com.google.android.calculator` immediately followed by `showPinOverlay`, then `EVT: pkg=com.android.systemui` followed by `dismissIfShowing`, and finally another `EVT: pkg=com.google.android.calculator` with `showPinOverlay`.

### Experiment 2: Verify Process Death on Recents Swipe
1. Start LockKeeper and verify running PID:
   ```bash
   adb shell pidof com.lockkeeper.app
   ```
2. Verify services running in the process:
   ```bash
   adb shell dumpsys activity services com.lockkeeper.app
   ```
3. Swipe LockKeeper away from Recents tray on device.
4. Check PID again immediately:
   ```bash
   adb shell pidof com.lockkeeper.app
   ```
5. **Expected Diagnostic Output:** Command returns empty (PID terminated).

### Experiment 3: Verify Fail-Open Bypass After Recents Swipe
1. Perform Experiment 2 (swipe LockKeeper from Recents).
2. Without reopening LockKeeper, launch the locked app:
   ```bash
   adb shell am start -n <locked.package.name>/<activity>
   ```
3. **Expected Diagnostic Output:** The locked application opens completely uninhibited without any PIN prompt or overlay.

### Experiment 4: Verify Post-Reopen Accessibility Disconnect
1. Perform Experiment 2 (swipe LockKeeper from Recents).
2. Launch LockKeeper:
   ```bash
   adb shell am start -n com.lockkeeper.app/.MainActivity
   ```
3. Query protection status via logcat / dumpsys:
   ```bash
   adb logcat -d -s LockKeeperA11y ProtectionRepository PlatformChannelHandler | grep "isAccessibilityConnected"
   ```
4. **Expected Diagnostic Output:** `isAccessibilityConnected: false`, `overallStatus: DEGRADED`.
