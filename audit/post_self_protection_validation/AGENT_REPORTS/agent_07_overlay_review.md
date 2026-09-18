# Agent 07: Overlay & Window Security Auditor Report

**Date:** 2026-09-17  
**Auditor:** Agent 07 — Overlay & Window Security Specialist  
**Target:** `OverlayManager.kt`, `AdminOverlayView.kt`, `PinOverlayView.kt`, `CooldownOverlayView.kt`  
**Status:** COMPLETE (Hostile Static & Windowing Verification)

---

## 1. Executive Summary

This audit examined the windowing architecture, layout parameter configurations, token lifecycle, display integrity, and failure states of all overlay views in LockKeeper.

**Critical Findings:**
1. **Window Type Discrepancy & Permission Trap:**
   `SELF_PROTECTION_IMPLEMENTATION_REPORT.md` repeatedly claims overlays utilize the system accessibility window token:
   > *"Uses high-priority TYPE_ACCESSIBILITY_OVERLAY with FLAG_SECURE... Overlays use the system Accessibility overlay token (TYPE_ACCESSIBILITY_OVERLAY), decoupled from MainActivity's window token."*
   **In reality**, `OverlayManager.kt:53` creates:
   `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`.
   - `TYPE_APPLICATION_OVERLAY` relies strictly on `android.permission.SYSTEM_ALERT_WINDOW`.
   - It cannot draw over system-level alert dialogs or privileged system popups.
   - On MIUI / HyperOS, `TYPE_APPLICATION_OVERLAY` requires a secondary hidden permission ("Display pop-up windows while running in the background") which is DENIED by default.
   - It is subject to Android 12+ "Untrusted Touch" filtering (`FLAG_WINDOW_IS_OBSCURED`).
2. **Permanent Black Screen Risk from Hardcoded `FLAG_SECURE`:**
   In `OverlayManager.kt:66`, `WindowManager.LayoutParams.FLAG_SECURE` is permanently hardcoded for ALL overlays (PIN, Cooldown, Lockout, Admin). If an overlay fails to dismiss or is orphaned due to an unhandled exception during window removal, the entire screen remains black in screen capture, task switchers, and accessibility tools.
3. **Accidental Dismissal via Asynchronous IPC Queue:**
   `showAdminOverlay`, `dismissAdminOverlay`, and `dismissIfShowing` all post runnables to `mainHandler`. If rapid transitions occur between Settings and other apps, queued dismissals can execute after a new overlay is displayed, immediately tearing down the new overlay (orphaned session).
4. **Soft-Keyboard IME Layout Disruption:**
   `AdminOverlayView` sets:
   `softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE`.
   When the overlay is added, the keyboard is forcibly launched. In split-screen or multi-window mode, this displaces other active windows and forces window reconfiguration events back into `LockKeeperAccessibilityService`.

---

## 2. Attack Scenario Matrix

### Scenario A: Target App Changes While Overlay is Visible
- **Behavior:** `LockKeeperAccessibilityService.kt:116` detects `prev != packageName`. If the user switched to an allowed app or home, `evaluatePackage()` returns `Allowed`, triggering `overlayManager.dismissIfShowing(packageName)`.
- **Finding:** In `dismissIfShowing(packageName)`:
  ```kotlin
  if (packageName == null || currentPackageName == packageName || (currentGateType == GateType.ADMIN && currentPackageName != packageName))
  ```
  If `currentGateType == GateType.ADMIN` and the new package is NOT the settings package, the Admin overlay is properly dismissed.
- **Risk:** If the user switches to another locked app, `attachOverlay` is called before the old view is synchronously removed from WindowManager. `attachOverlay` relies on `mainHandler.post` which can queue behind pending frames.

### Scenario B: Settings Changes Activity Within Same Task
- **Behavior:** User navigates between fragments or sub-settings activities within `com.android.settings`.
- **Finding:**
  In `LockKeeperAccessibilityService.kt:110`:
  ```kotlin
  else if (isSystemManagementPackage(packageName)) {
      overlayManager.dismissAdminOverlay()
  }
  ```
  If the sub-activity does not trigger `tamperEngine.evaluate()`, the Admin overlay is destroyed. This creates a severe window tearing vulnerability.

### Scenario C: User Presses Back Button
- **Behavior:**
  In `AdminOverlayView.kt:185`:
  ```kotlin
  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
      if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
          onCancelClicked()
          return true
      }
      return super.dispatchKeyEvent(event)
  }
  ```
  And `onCancelClicked()` triggers:
  `performGlobalAction(GLOBAL_ACTION_BACK)` in `LockKeeperAccessibilityService.kt:169`.
- **Result:** Back key is properly consumed and forces the underlying Settings activity backwards. **PASS**.

### Scenario D: User Presses Home Button
- **Behavior:** Hardware/gesture HOME cannot be intercepted by non-system apps. Home takes the user to Launcher.
- **Result:** The overlay is dismissed via `dismissIfShowing(launcherPackage)`.
- **Vulnerability:** The underlying Settings App Info activity remains alive in the background. If resumed from Recents, the overlay must re-attach cleanly.

### Scenario E: Service Disconnects While Overlay is Visible
- **Behavior:** If `LockKeeperAccessibilityService` is killed by the OS while an overlay is visible:
  `onDestroy()` is invoked. But `onDestroy()` only calls:
  ```kotlin
  override fun onDestroy() {
      isConnected = false
      evaluationJob?.cancel()
      super.onDestroy()
  }
  ```
  **IT NEVER CALLS `overlayManager.dismissAdminOverlay()`!**
- **Result:** The overlay view remains attached to the WindowManager! The user is permanently trapped behind the overlay with no backing service to process password verification or dismissals!

### Scenario F: Password Authentication Succeeded
- **Behavior:** `OverlayManager.kt:157-161`:
  ```kotlin
  repository.decisionEngine.grantAdminGraceWindow()
  repository.tamperController.endSession(true)
  removeCurrentOverlayInternal()
  onDismissAction(true)
  ```
- **Result:** Overlay is cleanly removed; 30s grace window activated. **PASS**.

### Scenario G: Password Authentication Failed / Locked Out
- **Behavior:** If incorrect, calls `view.showError()`. If attempts >= 5, calls `view.showLockout()`.
- **Finding:** In `AdminOverlayView.kt:178-182`:
  ```kotlin
  fun showLockout(remainingSeconds: Long) {
      errorTextView.text = "Too many incorrect attempts. Locked out for $remainingSeconds seconds."
      inputEditText.isEnabled = false
      submitButton.isEnabled = false
  }
  ```
  `cancelButton` remains enabled! The user can click "Back / Cancel" to navigate out of the locked-out screen. However, there is no real-time countdown timer tick; the remaining seconds display is static and never counts down unless a new window state event occurs.

### Scenario H: Two Overlays Requested Simultaneously
- **Behavior:** Two rapid calls to `showAdminOverlay` or `showPinOverlay`.
- **Finding:** Methods are `@Synchronized`, but the actual view inflation and `attachOverlay` happen asynchronously via `mainHandler.post`.
  If Thread 1 posts `attachOverlay(viewA)` and Thread 2 posts `attachOverlay(viewB)`, WindowManager will attempt to add both views in rapid succession. `removeCurrentOverlayInternal()` inside `mainHandler.post` attempts to remove `currentOverlayView`, but if `viewA` has not yet been assigned to `currentOverlayView`, WindowManager can throw `IllegalStateException: View already added` or leak `viewA`.

---

## 3. WCAG Accessibility & TalkBack Verification

`AdminOverlayView.kt` was reviewed against accessibility standards:
- **Strengths:**
  - Clear high-contrast color scheme (`#180D0D` background with `#F87171` and `#FFFFFF` text).
  - Explicit `contentDescription` on the root view, input field, submit button, and cancel button.
  - Clear non-impersonating branding (`🛡️ LOCKKEEPER ANTI-TAMPER`).
- **Deficiencies:**
  - `errorTextView` does not set `accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE`. TalkBack users will not be automatically notified when an incorrect password error or lockout message is displayed.
  - When lockout occurs, the focus is not programmatically moved to the error/lockout message.

---

## 4. Auditor Conclusion

The overlay subsystem deviates critically from its documented design: it uses `TYPE_APPLICATION_OVERLAY` rather than `TYPE_ACCESSIBILITY_OVERLAY`, making it dependent on `SYSTEM_ALERT_WINDOW` and vulnerable to MIUI pop-up restrictions. Furthermore, failure to clean up overlays on service `onDestroy()` creates a permanent screen trapping hazard.
