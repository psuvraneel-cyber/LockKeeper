# Agent 03: Uninstall / Tamper Route Bypass Auditor Report

**Date:** 2026-09-17  
**Auditor:** Agent 03 — Uninstall / Tamper Route Bypass Specialist  
**Perspective:** Hostile Malicious User / Adversary  
**Target:** LockKeeper Self-Protection Implementation  
**Status:** COMPLETE (Adversarial Static Route Attack)

---

## 1. Executive Summary

As an adversary with physical or interactive access to an unlocked Android device running LockKeeper, the primary objective is to disable, clear data, or uninstall LockKeeper without knowing or entering the Admin Password.

This audit methodically evaluated all 20 attack routes and sub-vectors against the actual Kotlin implementation.

**Key Exploits Discovered:**
1. **The Third-Party Launcher Bypass:** Any launcher that presents an internal confirmation or direct uninstallation intent (Nova, Niagara, Smart, Microsoft, etc.) completely evades detection because `TamperDetectionEngine` discards all packages except `com.android.settings`, package installers, and 3 OEM centers.
2. **The Soft-Keyboard Overlay Disruption Exploit:** Tapping the input field on `AdminOverlayView` causes an IME window change event. If this event arrives for `com.android.settings` with a transient or partial node tree, `LockKeeperAccessibilityService.kt:111` immediately executes `overlayManager.dismissAdminOverlay()`, killing the overlay and exposing the underlying Settings UI!
3. **The Language / Locale Bypass:** Changing system language to Russian, Japanese, Korean, Arabic, Turkish, or German (on non-AOSP devices) disables keyword matching and allows disabling Accessibility or clicking Uninstall without interception.
4. **The Clear-Data Destructive Cascade:** If storage is cleared through an unintercepted route (or ADB / secondary user / OEM cleaner), the Room database and SharedPreferences are wiped. LockKeeper boots in an unconfigured state, permanently disabling all protection and allowing Device Admin deactivation with zero authentication.
5. **The Google Play Store Direct Route:** Uninstallation directly from the Google Play Store is 100% unimpeded.

---

## 2. Comprehensive 20-Route Adversarial Assessment

### Route 01: Launcher -> Uninstall (Context Menu / App Drawer)
- **Status:** **PARTIAL / BYPASSABLE**
- **Mechanism:** User long-presses LockKeeper icon on home screen and taps "Uninstall".
- **Execution Path:**
  - If the launcher opens the system `PackageInstaller` dialog: Route 10 is reached.
  - If the launcher is a custom or OEM launcher that manages the prompt internally: `TamperDetectionEngine.evaluate` returns `null` because `packageName` is the launcher package.
- **Exploit Likelihood:** **HIGH** on custom launchers; **MEDIUM** on stock launchers.
- **Runtime Validation Required:** YES.

### Route 02: Launcher Drag -> Top Bar "Uninstall"
- **Status:** **PARTIAL**
- **Mechanism:** User drags icon to "Uninstall" drop zone at top of screen.
- **Execution Path:**
  - Generally invokes `PackageInstaller.UninstallerActivity`.
  - If the uninstaller dialog title is generic ("Uninstall this app?") without mentioning "LockKeeper", `TamperDetectionEngine.kt:221` returns `null`.
- **Exploit Likelihood:** **HIGH** on OEMs that omit app name in confirmation dialog.
- **Runtime Validation Required:** YES.

### Route 03: Launcher Context Menu -> "App Info"
- **Status:** **PARTIALLY COVERED**
- **Mechanism:** User long-presses LockKeeper icon and selects "App Info" (`(i)` icon).
- **Execution Path:**
  - Opens `com.android.settings`.
  - Reaches Route 04 (Settings App Info).

### Route 04: Settings -> Apps -> LockKeeper -> Uninstall
- **Status:** **PARTIALLY COVERED / BYPASSABLE**
- **Mechanism:** User navigates to Settings -> Apps -> LockKeeper -> Uninstall.
- **Vulnerabilities:**
  1. **45-Node Pruning:** If Settings UI contains more than 45 nodes before reaching the Uninstall button, detection fails.
  2. **Depth > 6 Pruning:** If the action buttons are nested deeper than 6 levels (common in modern Compose/Material3), detection fails.
  3. **Debounce 150ms Window:** Navigating rapidly between Settings screens within 150ms drops the window state event.
  4. **Keyboard Overlay Kill:** When overlay appears, tapping the password box causes an IME event that can trigger `overlayManager.dismissAdminOverlay()`.
- **Exploit Likelihood:** **MEDIUM**.
- **Runtime Validation Required:** YES.

### Route 05: Settings -> Apps -> LockKeeper -> Storage & Cache -> Clear Storage
- **Status:** **PARTIALLY COVERED / BYPASSABLE**
- **Mechanism:** User navigates to Storage & Cache and taps "Clear storage" / "Clear data".
- **Vulnerabilities:**
  - In `evaluateSettings` Sub-route C (`TamperDetectionEngine.kt:154`):
    Matches only `storageuseactivity`, `appstoragesettings`, `cleardatadialog`.
  - OEM storage screens named `ManageSpaceActivity` or `StorageDashboardFragment` evade detection.
  - If "LockKeeper" is not mentioned in the text of the dialog, `mentionsLockKeeper` is false -> no tamper event.
- **Exploit Likelihood:** **HIGH**.
- **Runtime Validation Required:** YES.

### Route 06: Settings -> Apps -> LockKeeper -> Force Stop
- **Status:** **PARTIALLY COVERED**
- **Mechanism:** User taps "Force Stop" on App Info screen.
- **Vulnerabilities:**
  - Dependent on Route 04 interception.
  - If App Info was not intercepted due to node/depth limits, Force Stop executes unimpeded. Once force-stopped, `LockKeeperAccessibilityService` is killed!

### Route 07: Settings -> Apps -> LockKeeper -> Disable
- **Status:** **PARTIALLY COVERED**
- **Mechanism:** User taps "Disable" (if available on certain builds or enterprise configurations).
- **Vulnerabilities:** Same as Route 04.

### Route 08: Settings -> Accessibility -> LockKeeper -> Toggle Off
- **Status:** **PARTIALLY COVERED / BYPASSABLE**
- **Mechanism:** User navigates to Settings -> Accessibility -> Downloaded apps -> LockKeeper -> Toggle off.
- **Vulnerabilities:**
  - `ACCESSIBILITY_TOGGLE_KEYWORDS` is limited to English, Spanish, French.
  - German, Italian, Russian, Chinese, Hindi, etc., bypass keyword match.
  - Non-AOSP class names (e.g. `MiuiAccessibilityServiceDetailsActivity`) bypass class match.
  - **Result:** Attacker disables Accessibility service without entering Admin Password.
- **Exploit Likelihood:** **HIGH** on non-English locales and OEM devices.
- **Runtime Validation Required:** YES.

### Route 09: Settings -> Security -> Device Admin Apps -> Deactivate
- **Status:** **BROKEN (FALSE POSITIVE HAZARD + BYPASS)**
- **Mechanism:** User navigates to Device Admin screen to deactivate LockKeeper.
- **Vulnerabilities:**
  - **Critical False Positive:** Merely opening the Device Admin list triggers `DISABLE_DEVICE_ADMIN`, blocking general browsing of device administrators.
  - **Interference with other apps:** Deactivating unrelated device admin apps (e.g. Find My Device) triggers LockKeeper's admin gate.
  - If bypassed, `LockKeeperDeviceAdminReceiver.onDisableRequested` only returns a warning string. The user can still tap "Deactivate" if the overlay fails.

### Route 10: Package Installer System Dialog
- **Status:** **PARTIALLY COVERED / BYPASSABLE**
- **Mechanism:** Any intent invoking `android.intent.action.UNINSTALL_PACKAGE` or `PackageInstaller`.
- **Vulnerabilities:**
  - Strict requirement `mentionsLockKeeper` in `TamperDetectionEngine.kt:221`.
  - If the uninstaller prompt displays an icon and generic text ("Do you want to uninstall this application?"), the engine returns `null`.

### Route 11: OEM Package Installer (MIUI / HyperOS, One UI, ColorOS)
- **Status:** **HIGH BYPASS RISK**
- **Mechanism:** Proprietary vendor installer packages.
- **Vulnerabilities:**
  - Only 7 packages are listed in `PACKAGE_INSTALLER_PACKAGES`.
  - Modern vendor variants (e.g., Vivo V-Appstore installer, Honor, Realme, Transsion) are omitted.

### Route 12: OEM Maintenance / Security Center (Xiaomi Security, Samsung Device Care)
- **Status:** **HIGH BYPASS RISK**
- **Mechanism:** Cleaning tools, app managers, and deep-clean cleaners built into OEM skins.
- **Vulnerabilities:**
  - Only `com.miui.securitycenter`, `com.samsung.android.lool`, `com.coloros.safecenter` are checked.
  - Asus Mobile Manager, Huawei Optimizer, Vivo iManager, Oppo Phone Manager variants are not covered.
  - Within checked centers, `evaluateOemSecurityCenter` requires `mentionsLockKeeper` within 45 nodes.

### Route 13: Google Play Store -> Manage Apps -> LockKeeper -> Uninstall
- **Status:** **UNSUPPORTED / 100% BYPASS BY DESIGN**
- **Mechanism:** User opens Google Play Store -> Manage apps & device -> Installed -> LockKeeper -> Uninstall.
- **Execution Path:**
  - Package `com.android.vending` is explicitly omitted from `TamperDetectionEngine.kt`.
  - Zero tamper events are emitted.
  - Uninstallation succeeds immediately without password prompt.

### Route 14: Alternate Launchers (Nova, Niagara, Smart, Microsoft Launcher)
- **Status:** **100% BYPASS**
- **Mechanism:** User installs third-party launcher. Long-presses LockKeeper -> Uninstall via launcher's internal manager.
- **Execution Path:** `TamperDetectionEngine.kt:68` discards all package names not in the 3 whitelisted categories. Complete bypass.

### Route 15: Alternate Settings Activities / Deep Links
- **Status:** **PARTIALLY COVERED**
- **Mechanism:** Invoking Settings activities via shortcuts, search, or deep link intents (e.g., `android.settings.APPLICATION_DETAILS_SETTINGS`).
- **Vulnerabilities:**
  - If deep-linked into `SpaActivity` or sub-fragments where the layout has > 45 nodes, DFS fails to detect actions.

### Route 16: Alternate Fragments & Nested Tabs
- **Status:** **HIGH BYPASS RISK**
- **Mechanism:** Tabbed settings layouts (e.g., App Info with separate Permissions, Storage, Battery tabs).
- **Vulnerabilities:**
  - Service filters ONLY `TYPE_WINDOW_STATE_CHANGED`.
  - Switching tabs within a single Activity generates `TYPE_WINDOW_CONTENT_CHANGED`, which is ignored!

### Route 17: Alternate Intents / Intent Actions
- **Status:** **HIGH BYPASS RISK**
- **Mechanism:** Direct intent launches from browser, notifications, or automation tools (Tasker, MacroDroid).

### Route 18: Notification-Driven Package Management
- **Status:** **HIGH BYPASS RISK**
- **Mechanism:** Android 13+ Foreground Services (FGS) Task Manager in notification shade.
- **Vulnerabilities:**
  - FGS Task Manager lives inside `com.android.systemui`.
  - `com.android.systemui` is completely uninspected by `TamperDetectionEngine`.
  - User taps "Stop" on LockKeeper Foreground Service without any challenge!

### Route 19: Package Management Dialogs
- **Status:** **PARTIAL**
- **Mechanism:** System alert dialogs. Vulnerable to `TYPE_APPLICATION_OVERLAY` z-order layering.

### Route 20: App-Management Search Routes
- **Status:** **HIGH BYPASS RISK**
- **Mechanism:** User searches for "LockKeeper" in Settings top search bar and taps inline actions (Force Stop / Clear Data).
- **Vulnerabilities:**
  - Search results live inside `SearchActivity` / `com.google.android.settings.intelligence`.
  - Package is `com.google.android.settings.intelligence`, NOT `com.android.settings`!
  - `TamperDetectionEngine` discards it immediately.

---

## 3. Hostile Interactive & Lifecycle Attacks

### 3.1 The Soft-Keyboard Overlay Kill Attack
1. User enters Settings App Info -> LockKeeper.
2. `AdminOverlayView` appears.
3. User taps the EditText to enter password.
4. Android opens the IME (Soft Keyboard).
5. Window state changes in the background; an event arrives for `com.android.settings`.
6. Node traversal is evaluated. Because the IME window is foreground, `rootNode` does not contain the full App Info tree, or `tamperEngine.evaluate` returns `null`.
7. `LockKeeperAccessibilityService.kt:111` triggers:
   ```kotlin
   else if (isSystemManagementPackage(packageName)) {
       overlayManager.dismissAdminOverlay()
   }
   ```
8. The Admin Overlay is dismissed! The user is now on the un-obscured App Info screen and can tap Uninstall!

### 3.2 The 150ms Rapid Transition Attack
1. `LockKeeperAccessibilityService.kt:84`:
   `if (packageName == lastHandledPackage && (now - lastEventTimestamp) < 150L) return`
2. User opens Settings (`packageName = com.android.settings`).
3. User rapidly taps LockKeeper (or automation opens it within 150ms).
4. `packageName` is still `com.android.settings` and `(now - lastEventTimestamp) < 150ms`.
5. Event is dropped! Tamper engine is never called for the App Info transition!

### 3.3 The Clock-Warp Lockout Kill Attack
1. User makes 5 incorrect Admin Password attempts.
2. 300-second lockout is engaged (`adminLockoutUntil = System.currentTimeMillis() + 300_000L`).
3. User changes device clock forward by 5 minutes.
4. `TamperAuthorizationController.kt:96`:
   `if (lockoutUntil != null && lockoutUntil > now)` evaluates to `FALSE`!
5. Lockout is immediately destroyed! Attacker resumes brute forcing!

---

## 4. Auditor Conclusion

The implementation provides partial deterrence only against naive, standard AOSP Settings navigation in English. It is completely bypassed by third-party launchers, Google Play Store, Android 13+ Notification Task Manager, settings search intelligence, non-English locales, and rapid UI/IME transitions.
