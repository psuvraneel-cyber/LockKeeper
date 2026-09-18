# Verification Agent 03: Android / MIUI 14 Runtime Review

**Date:** 2026-09-18T03:27:45+05:30  
**Phase:** STAGE 1 — READ-ONLY FORENSICS  
**Device Context:** Xiaomi Mi 10i 5G (`gauguininpro`), Android 12 (`SKQ1.211006.001`), MIUI 14.0.3.0 Global (`V14.0.3.0.SJSINXM`)  

---

## 1. Scope & Objective

Agent 03 evaluated the interaction between LockKeeper's native background services and Xiaomi's MIUI 14 operating system layer, focusing on:
- Window state event dispatch from `com.miui.home`.
- Injection of `GLOBAL_ACTION_HOME` via `AccessibilityService`.
- Event storm dynamics and main-thread saturation risks.
- MIUI-specific security permissions (`com.miui.securitycenter`, background pop-up windows).
- Real device verification requirements for the physical Xiaomi Mi 10i.

---

## 2. MIUI 14 Window Event Mechanics & The Original Flaw

### 2.1 Event Generation by `com.miui.home`
On MIUI 14, `com.miui.home` handles:
- Home screen desktop pages and widgets.
- App drawer sliding surface.
- Recent apps screen and gesture navigation transitions.

Whenever a user interacts with the launcher, the Android Accessibility framework dispatches `AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED` and `TYPE_WINDOW_CONTENT_CHANGED` with `packageName = "com.miui.home"`.

### 2.2 The Catastrophic MIUI Feedback Loop
In the pre-fix build, when `LockDecisionEngine` returned `RecoveryRequired` for all non-LockKeeper packages:
1. `LockKeeperAccessibilityService` received `TYPE_WINDOW_STATE_CHANGED` for `com.miui.home`.
2. The service called `performGlobalAction(GLOBAL_ACTION_HOME)`.
3. Under MIUI 14, receiving `GLOBAL_ACTION_HOME` while already on the launcher forces the launcher to reset its view hierarchy, triggering a brand-new `TYPE_WINDOW_STATE_CHANGED` event for `com.miui.home`.
4. The service received the new event within milliseconds and issued another `GLOBAL_ACTION_HOME`.
5. This generated an infinite event loop (~50–100 events/second), completely freezing UI touch dispatch, starving Android Settings, and trapping the user on the home screen.

---

## 3. Current Mitigation & Architectural Resilience

The current codebase deploys a three-stage defense against this failure mode:

### Mitigation 1: Setup Mode Immunity
When in onboarding, `evaluatePackage()` passes `isSetupInProgress = true`. `LockDecisionEngine` evaluates rule 4:
```kotlin
if (isSetupInProgress || securityHealthStatus == "SETUP_IN_PROGRESS") {
    return LockDecision.Allowed(ProtectionDecisionReason.ALLOWED_SETUP_MODE)
}
```
This returns `LockDecision.Allowed`. In `onAccessibilityEvent()`:
```kotlin
is LockDecision.Allowed -> {
    overlayManager.dismissIfShowing(packageName)
}
```
No global actions are called.

### Mitigation 2: Event Throttling and Debounce
```kotlin
// Debounce identical events within 150ms
if (packageName == lastHandledPackage && (now - lastEventTimestamp) < 150L) {
    return
}
lastEventTimestamp = now
```
Any high-frequency barrage of identical package events from MIUI is immediately dropped before invoking coroutines or querying the database.

### Mitigation 3: Launcher Package Blacklist for Global Home Action
Even in fail-closed scenarios (e.g. `RecoveryRequired`, `Blocked`, `DenyUnknown`):
```kotlin
if (!isLauncherOrSystemUiPackage(packageName)) {
    performGlobalAction(GLOBAL_ACTION_HOME)
}
```
Where `isLauncherOrSystemUiPackage(packageName)` resolves `Intent.ACTION_MAIN + CATEGORY_HOME` via `PackageManager` and checks explicit string constants:
- `"com.miui.home"`
- `"com.android.systemui"`
- `"com.miui.powerkeeper"`
- `"*.launcher"` / `"*.home"`

**Result:** `GLOBAL_ACTION_HOME` is physically barred from execution whenever `com.miui.home` is in the foreground.

---

## 4. Test Matrix for Real Xiaomi Mi 10i Verification

To provide unequivocal proof, the physical device must undergo testing across the following scenarios:

| ID | Test Scenario | Expected Outcome on Xiaomi MIUI 14 |
| :--- | :--- | :--- |
| **MIUI-01** | Device Admin active + PIN not set -> Press Home | Launcher renders smoothly, zero global actions, no stutter. |
| **MIUI-02** | Open MIUI App Drawer (swipe up) | App drawer opens, search bar interactive, no overlay. |
| **MIUI-03** | Open MIUI Settings (`com.android.settings`) | Settings opens immediately, no tamper overlay during onboarding. |
| **MIUI-04** | Launch third-party app (e.g., Chrome / Notes) | Third-party app launches without interference. |
| **MIUI-05** | Rapid multi-tasking (Home <-> Settings <-> LockKeeper) | No ANR, no memory leak, zero event loop in logcat. |
| **MIUI-06** | Enable Accessibility in MIUI Settings | Accessibility connects without kicking user out of Settings. |
| **MIUI-07** | LockKeeper self-lock after completed provisioning | Self-lock triggers correctly on MIUI resume. |
