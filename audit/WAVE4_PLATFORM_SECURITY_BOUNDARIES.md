# WAVE 4: PLATFORM SECURITY BOUNDARIES & ARCHITECTURAL LIMITS

## 1. Executive Statement

LockKeeper is a non-system, consumer Android security application running with standard user-space privileges (`uid >= 10000`). It operates without root permissions and without enterprise Device Owner (`DO`) provisioning.

Consequently, LockKeeper's security guarantees are strictly defined by what the underlying Android Security Model permits. This document formally classifies every core security capability to eliminate ambiguity regarding what is **OS-enforced**, what is **app-enforced**, and what is **platform-limited**.

---

## 2. Capability Classification Matrix

| Capability | Classification | Platform Mechanism | Behavior Under Hostile Conditions |
| :--- | :--- | :--- | :--- |
| **Uninstallation Prevention** | **OS-ENFORCED & APP-ENFORCED** | Device Administration (`DevicePolicyManager`) + Accessibility Node Interception | When Device Admin is active, Android OS blocks `pm uninstall` and Settings uninstallation (`DELETE_FAILED_DEVICE_POLICY_MANAGER`). In Play Store or third-party launchers, Tamper Engine detects uninstall buttons and overlays an Admin Password challenge. Rooted users or Safe Mode boots can bypass this (PLATFORM-LIMITED). |
| **Device Admin Removal Interception** | **APP-ENFORCED (BEST-EFFORT)** | Accessibility Service (`TYPE_WINDOW_CONTENT_CHANGED`) | When a user navigates to Device Admin settings to deactivate LockKeeper, Tamper Engine detects the switch/button and draws `AdminOverlayView`. If the service is killed or disabled, removal succeeds, but immediately revokes active sessions via `onDisabled()` and triggers `RECOVERY_REQUIRED` on next launch. |
| **App Clear-Data / Storage Wipe** | **PLATFORM-LIMITED (DEFENSIVE FALLBACK)** | AppOps + Settings Navigation Interception | Android OS allows device users to "Clear Storage" from Settings > Apps. LockKeeper intercepts the Storage screen with an Admin Overlay. If an attacker bypasses the overlay (e.g. via ADB or Safe Mode), local credentials are wiped while Device Admin remains active. On next boot, `checkRecoveryStatus()` detects the desync and enters `RECOVERY_REQUIRED`, permanently locking down protected apps until authenticated recovery. |
| **Force Stop Defense** | **APP-ENFORCED** | Settings App Node Traversal | The "Force Stop" button in App Info is intercepted by `TamperDetectionEngine` matching button text and resource IDs (`button2_negative`, etc.). If force stopped via ADB (`am force-stop`), the process halts until the next user launch or `BOOT_COMPLETED` broadcast. |
| **Accessibility Service Persistence** | **OS-ENFORCED & USER-CONFIGURED** | Android Accessibility Framework | Once granted by the user, Android persists the setting across reboots in `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`. However, Android does not allow third-party apps to programmatically re-enable an Accessibility Service if toggled off by the user. |
| **Overlay Persistence (`TYPE_APPLICATION_OVERLAY`)** | **APP-ENFORCED (FAIL-CLOSED)** | WindowManager Service | Overlays require `SYSTEM_ALERT_WINDOW`. On stock Android, overlays render on layer 111000 above normal applications. On aggressive OEM forks (MIUI/HyperOS), "Display pop-up windows in background" permission can be revoked; LockKeeper hardens this by executing `navigateHome()` on attachment exception, preventing unprotected app usage. |
| **Service Survival & Background Execution** | **BEST-EFFORT** | `LockKeeperForegroundService` (SpecialUse FGS + Notification + STICKY) | The foreground service runs with `START_STICKY` and a persistent notification to resist low-memory kills. However, aggressive OEM battery managers (MIUI MIUI Optimization, Samsung Deep Sleep, Huawei PowerGenie) can kill background processes. Mitigated by requesting battery optimization exemption (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`). |
| **Reboot Behavior & Persistence** | **OS-ENFORCED & APP-ENFORCED** | `RECEIVE_BOOT_COMPLETED` + Room Database | Upon boot, `BootReceiver` restores the foreground service. Lockout timestamps (`pinLockoutUntil`, `adminLockoutUntil`) and locked packages are stored in Room SQLite on disk, surviving device power-off and battery depletion. |
| **Keyguard / Lock Screen Boundary** | **OS-ENFORCED** | Android Keyguard & WindowManager | While the device is locked, Android OS prevents background overlays from displaying above the lock screen unless `FLAG_SHOW_WHEN_LOCKED` is used. LockKeeper deliberately avoids showing above keyguard, ensuring device credentials must be satisfied before application gates appear. |

---

## 3. Detailed Boundary Analysis

### A. Uninstall Prevention
- **Android OS Reality:** Third-party consumer applications cannot declare themselves non-uninstallable.
- **LockKeeper Enforcement:** 
  1. Activating Device Admin prevents uninstallation through standard system package managers. The OS displays: *"This app is a device administrator and must be deactivated before uninstalling."*
  2. Attempting to deactivate Device Admin brings up Settings, which is monitored by `LockKeeperAccessibilityService`. The moment the deactivation dialog appears, an Admin Password overlay is attached.
- **Platform Boundary:** If a user boots the device into **Android Safe Mode**, all third-party accessibility services are disabled by the OS. In Safe Mode, the user can deactivate Device Admin and uninstall the app. This is an intentional Android OS recovery architecture that no unrooted app can override.

### B. Force Stop & Background Termination
- **Android OS Reality:** When an application is in the "stopped" state (`FLAG_EXCLUDE_STOPPED_PACKAGES`), Android OS suppresses all broadcast intents (including `BOOT_COMPLETED`) until the user explicitly taps the app icon.
- **LockKeeper Enforcement:** `TamperDetectionEngine` detects the "Force stop" button in `com.android.settings` across English, Spanish, French, German, and OEM resource IDs, and blocks clicks via `AdminOverlayView`.
- **Platform Boundary:** If executed via ADB (`adb shell am force-stop com.lockkeeper.app`), the app cannot prevent OS termination.

### C. OEM Process Killers (MIUI / HyperOS / ColorOS / OneUI)
- **OEM Reality:** Xiaomi's MIUI and HyperOS kill background services to extend battery life unless:
  1. Autostart permission is enabled.
  2. Battery saver is set to "No restrictions".
  3. "Display pop-up windows while running in the background" is granted.
- **LockKeeper Enforcement:**
  1. The onboarding walkthrough directs users to grant these specific permissions.
  2. If MIUI restricts background pop-up windows, `OverlayManager.attachOverlay()` catches the exception and immediately invokes `navigateHome()`, ensuring fail-closed behavior (the protected app is closed rather than exposed).

### D. Google Play Distribution Policy Constraints (`DISTRIBUTION-LIMITED`)
- **Policy Reality:** Google Play Developer Program Policies restrict the usage of:
  - `BIND_ACCESSIBILITY_SERVICE`: Permitted only for apps with prominent disclosure that assist users with disabilities or provide legitimate security/management functionality.
  - `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: Permitted only where battery optimization directly impairs the core functionality of the app (such as real-time security lockers).
  - `FOREGROUND_SERVICE_SPECIAL_USE`: Android 14+ requires justification submitted to Google Play Console explaining why existing standard FGS types (dataSync, mediaPlayback, etc.) are insufficient.
- **LockKeeper Compliance:** Declarations in `AndroidManifest.xml` include `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` with description *"Observing foreground apps and enforcing lock security policies"*, satisfying API 34+ guidelines.
