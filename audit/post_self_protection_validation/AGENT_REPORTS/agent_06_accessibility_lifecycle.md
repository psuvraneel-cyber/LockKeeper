# Agent 06: Accessibility Service & Lifecycle Auditor Report

**Date:** 2026-09-17  
**Auditor:** Agent 06 — Accessibility Service & Lifecycle Auditor  
**Target:** `LockKeeperAccessibilityService.kt`, Accessibility Configuration  
**Status:** COMPLETE (Hostile Static Lifecycle Audit)

---

## 1. Executive Summary

This audit conducted a deep examination of `LockKeeperAccessibilityService`, its IPC lifecycle, event pipeline, threading model, and operational state boundaries.

**Critical Findings:**
1. **Misleading Documentation Regarding Event Filtering:**
   The implementation report claims:
   > *"Event Filtering: Pre-filters for TYPE_WINDOW_STATE_CHANGED and TYPE_WINDOW_CONTENT_CHANGED."*
   **In reality**, line 50 of `LockKeeperAccessibilityService.kt` sets:
   `eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED`.
   And line 70 checks:
   `if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return`.
   `TYPE_WINDOW_CONTENT_CHANGED` is 100% ignored. Consequently, dynamic UI modifications within a single activity (such as Jetpack Compose recomposition, tab changes, dynamic fragment swaps, and scrolling) are completely invisible to LockKeeper.
2. **Conflation of Accessibility States (Security Distortion):**
   The architecture fails to distinguish between:
   - *Accessibility ENABLED in OS* (`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`)
   - *Accessibility SERVICE CONNECTED* (`LockKeeperAccessibilityService.isConnected`)
   - *Accessibility PROTECTION OPERATIONAL* (`repository.shouldProtectSettings()`)
   In `PlatformChannelHandler.kt:250`, `checkPermission("accessibility")` reports `isServiceConnected || enabledServices.contains(context.packageName)`. If the user enabled the service in Settings, but the process died or the service was disconnected by the OS, Flutter reports "Accessibility Granted" and "Protection Operational" to the user while **zero protection is active**!
3. **Premature Overlay Tearing on Main-Thread Events:**
   In `LockKeeperAccessibilityService.kt:111`:
   ```kotlin
   else if (isSystemManagementPackage(packageName)) {
       overlayManager.dismissAdminOverlay()
   }
   ```
   When a user interacts with the Admin Password overlay over Settings, the soft keyboard (IME) appears or updates. These intermediate window state changes cause `onAccessibilityEvent` to fire for `com.android.settings`. Because `rootNode` is transiently null or incomplete while the keyboard is animating, `tamperEngine.evaluate()` returns null, instantly triggering `dismissAdminOverlay()`. The overlay vanishes while the user is typing!
4. **Broken `lastHandledPackage` Assignment on Tamper Events:**
   At line 108 of `LockKeeperAccessibilityService.kt`, when a tamper event is detected, `return` is executed. Line 126 (`lastHandledPackage = packageName`) is never reached. This leaves `lastHandledPackage` pointing to a stale prior package, breaking the 150ms debounce for all subsequent events from that package.

---

## 2. Event Processing Pipeline Analysis

```
[System Accessibility Event]
            |
            v
[onAccessibilityEvent] (Main Thread)
  - Checks eventType == TYPE_WINDOW_STATE_CHANGED
  - Checks debounce: packageName == lastHandledPackage && dt < 150ms
            |
            v
[shouldProtectSettings() == true?]
      /            \
    YES             NO
    /                 \
[tamperEngine.evaluate()]   [Skip Tamper Detection]
  - DFS traversal               |
  - DPM check                   v
      |                   [App Exit / App Switch Logic]
      v                         |
[TamperEvent detected?]         v
  /              \        [Evaluate Package for App Lock]
YES               NO
/                   \
[handleTamperEvent]   [isSystemManagementPackage(pkg)?]
  - Return early!       /             \
  - (Bypasses line 126!) YES           NO
                        /               \
            [dismissAdminOverlay()]   [Normal App Evaluation]
```

### 2.1 Threading & Latency Risks
- `onAccessibilityEvent` executes on the application's Main Thread (UI thread).
- Inside `onAccessibilityEvent`:
  1. Calls `getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager`.
  2. Calls `dpm.isAdminActive(adminComponent)` (Synchronous IPC binder call to `DevicePolicyManagerService`!).
  3. Calls `rootInActiveWindow ?: event.source` (Synchronous IPC binder call to `AccessibilityManagerService`!).
  4. Calls `tamperEngine.evaluate()` which performs Depth-First Search traversal and string matching across up to 45 nodes.
- **Risk:** Performing synchronous binder IPCs (`dpm.isAdminActive` and `rootInActiveWindow`) on every single `TYPE_WINDOW_STATE_CHANGED` event on the Main Thread creates potential frame drops and ANRs during rapid window transitions or heavy system load.

---

## 3. Detailed Audit of State Conflation

| State Layer | Definition in Architecture | Actual Code Check | Flaw & Security Impact |
|---|---|---|---|
| **OS Enabled** | User flipped switch in Android Settings. | `Settings.Secure.getString(...).contains(packageName)` | True even if process is dead or crashing. |
| **Service Connected** | `onServiceConnected()` has run; binder active. | `LockKeeperAccessibilityService.isConnected` | Stored as `@Volatile var isConnected`. Becomes `false` on `onDestroy()`. |
| **Protection Operational** | Service connected + Onboarding complete + Admin password configured. | `repository.shouldProtectSettings()` | If SharedPreferences is wiped, this is `false` even if service is connected and admin is active. |

### The False Sense of Security Exploit:
1. User enables Accessibility in onboarding.
2. Android OS battery saver kills `LockKeeperAccessibilityService` (common on MIUI, EMUI, ColorOS).
3. `isConnected` becomes `false`.
4. User opens LockKeeper Flutter app.
5. Flutter calls `getProtectionStatus`:
   `PlatformChannelHandler.kt:250`:
   ```kotlin
   isServiceConnected || enabledServices.contains(context.packageName)
   ```
   Because `enabledServices` contains `com.lockkeeper.app`, this returns `true`.
6. Dashboard displays: "All Protections Active" (Green Check).
7. In reality, `LockKeeperAccessibilityService` is dead. Protected apps can be opened, uninstalled, and force-stopped with zero interception!
8. **Invariant 10 is directly violated.**

---

## 4. Overlay Dismissal Race Conditions

In `LockKeeperAccessibilityService.kt`:
```kotlin
// Line 104-113:
val tamperEvent = tamperEngine.evaluate(...)
if (tamperEvent != null && tamperEvent.confidence != com.lockkeeper.app.security.TamperConfidence.LOW) {
    handleTamperEvent(tamperEvent, packageName)
    return
} else if (isSystemManagementPackage(packageName)) {
    // Navigated away from sensitive screen within Settings or Package Installer
    overlayManager.dismissAdminOverlay()
}
```

### Exploit Scenario:
1. User opens Settings -> Apps -> LockKeeper -> Uninstall.
2. `tamperEvent` is detected (`TamperType.UNINSTALL`).
3. `overlayManager.showAdminOverlay()` attaches `AdminOverlayView`.
4. User focuses the password EditText.
5. Android system posts an accessibility event for the IME or focused window.
6. The new event arrives: `packageName = com.android.settings`, `className = android.inputmethodservice.InputMethodService` or `android.widget.FrameLayout`.
7. `tamperEngine.evaluate()` examines the active window. Because the IME window is currently interacting with the view, the App Info root node is not active, returning `null`.
8. Line 109 evaluates `tamperEvent == null`.
9. Line 110 evaluates `isSystemManagementPackage("com.android.settings") == true`.
10. Line 112 executes: `overlayManager.dismissAdminOverlay()`.
11. **The overlay is destroyed.** The user now has unobstructed access to the uninstallation confirmation buttons!

---

## 5. Auditor Conclusion

The Accessibility Service implementation suffers from fatal lifecycle flaws:
- It drops `TYPE_WINDOW_CONTENT_CHANGED` despite documentation claims.
- It falsely reports that protection is operational when the service is disconnected.
- It prematurely tears down the Admin Overlay upon intermediate window and soft-keyboard events.
- It breaks its own debouncing logic for tamper events.
