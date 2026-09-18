# Technical Design Document (TDD) — LockKeeper

## 1. Architecture Overview
Hybrid architecture: **Flutter/Dart** for UI (leveraging existing Flutter background), with a **native Android module (Kotlin)** handling everything Flutter can't reach directly — AccessibilityService, DeviceAdminReceiver, system overlay windows, and foreground services. Communication between the two happens over **Flutter Platform Channels** (MethodChannel + EventChannel).

```
┌─────────────────────────────┐
│        Flutter Layer        │
│  (UI, onboarding, settings, │
│   app list, PIN screens)    │
└──────────────┬──────────────┘
               │ Platform Channels
┌──────────────▼──────────────┐
│      Native Android Layer   │
│  - AccessibilityService     │
│  - Foreground Service       │
│  - Overlay WindowManager    │
│  - DeviceAdminReceiver      │
│  - Boot Receiver            │
│  - Room DB + EncryptedPrefs │
└──────────────────────────────┘
```

Rationale: overlay windows, accessibility events, and device admin callbacks are lifecycle-sensitive and must run reliably even when the Flutter engine/activity isn't in the foreground — so the native layer owns all of that state and persistence, and Flutter is treated as a "remote control + display" for configuration screens only.

## 2. Native Components

### 2.1 AccessibilityService
- Listens for `TYPE_WINDOW_STATE_CHANGED` events.
- On each event, reads `event.packageName` and `event.className`.
- Checks packageName against the Room `LockedApp` table.
  - If locked and cooldown expired → trigger overlay display (PIN or strict countdown).
  - If cooldown still active and strict-locked → trigger countdown overlay directly, skip PIN.
- Additionally checks for `com.android.settings` package + specific class names matching:
  - App info / uninstall screen for this app's package
  - Device Admin list screen
  - Accessibility settings screen (when this app's toggle row is visible)
  - On match → trigger Admin Password overlay + arm a "back if not confirmed within timeout" action via `performGlobalAction(GLOBAL_ACTION_BACK)`.

### 2.2 Foreground Service
- Started at boot and kept alive with a persistent low-priority notification (required Android 8+).
- Hosts the `UsageStatsManager` polling fallback (300–500ms interval) in case the Accessibility Service is ever disabled — note: if Accessibility is disabled, only the Settings-interception feature is lost; Usage Stats fallback still allows basic app-locking detection to continue, but with more polling latency and no direct visibility into Settings navigation.
- Restarts itself from `onTaskRemoved()` where the OEM allows it.

### 2.3 Overlay Window Manager
- Uses `WindowManager.addView()` with `TYPE_APPLICATION_OVERLAY`, `FLAG_NOT_FOCUSABLE` off (needs focus for PIN input), full-screen `MATCH_PARENT` layout.
- Two overlay layouts: PIN/Countdown (locked-app screen) and Admin Password (settings-interception screen), visually distinct per the UI/UX spec.
- Consumes back-button events (`KEYCODE_BACK`) at the view level so the underlying app/screen cannot be dismissed to.

### 2.4 DeviceAdminReceiver
- Standard `DeviceAdminReceiver` subclass; requests activation via `ACTION_ADD_DEVICE_ADMIN` intent during onboarding.
- No custom policies enforced beyond activation itself — its only purpose here is to force the OS to route uninstall attempts through the deactivation screen first, which the Accessibility layer then intercepts.

### 2.5 Boot Receiver
- `BroadcastReceiver` on `BOOT_COMPLETED`, starts the Foreground Service.

### 2.6 Persistence Layer
- **Room database** (native side) with two tables:
```
LockedApp
- packageName: String (PK)
- isLocked: Boolean
- cooldownMinutes: Int
- strictLock: Boolean
- lockedUntilTimestamp: Long (nullable)

AppSettings (single row)
- pinHash: String
- pinSalt: String
- adminPasswordHash: String
- adminPasswordSalt: String
- failedPinAttempts: Int
- pinLockoutUntil: Long (nullable)
```
- **EncryptedSharedPreferences** (via Jetpack Security) as an additional wrapper for the raw hash/salt values, so even the Room DB file itself doesn't hold them in a directly readable form outside the app sandbox.
- Hashing: PBKDF2WithHmacSHA256, per-credential random salt, ≥120,000 iterations (adjust for device performance).

## 3. Platform Channel API (Flutter ⇄ Native)

| Method (Flutter → Native) | Purpose |
|---|---|
| `requestPermission(type)` | Deep-links to the relevant system settings screen for a given permission type |
| `checkPermissionStatus(type)` | Returns whether overlay/usage-access/accessibility/device-admin/battery-exemption is currently granted |
| `setPin(pin)` / `verifyPin(pin)` | Set or check the user PIN (native hashes/compares, Flutter never sees the hash) |
| `setAdminPassword(pw)` / `verifyAdminPassword(pw)` | Same, for the admin credential |
| `getLockedApps()` / `setLockedApp(packageName, config)` | Read/write the `LockedApp` table |
| `startProtectionService()` | Ensures the foreground service + accessibility are running |

| Event (Native → Flutter, via EventChannel) | Purpose |
|---|---|
| `onPermissionRevoked(type)` | Notifies UI to show the "protections disabled" banner |

Note: the actual PIN/password verification for **overlay screens** happens natively (the overlay is a native Android view, not a Flutter route) — Flutter is only used for the in-app configuration UI (onboarding, home, settings), not for the lock/admin overlays themselves, since those must render reliably outside of the Flutter engine's own activity lifecycle.

## 4. Permissions Required
- `SYSTEM_ALERT_WINDOW`
- `PACKAGE_USAGE_STATS` (special app-ops permission, granted via Settings)
- `BIND_ACCESSIBILITY_SERVICE`
- `BIND_DEVICE_ADMIN`
- `RECEIVE_BOOT_COMPLETED`
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
- `FOREGROUND_SERVICE`

## 5. Security Design
- PIN and admin password are **never** the same credential and are validated by two entirely separate code paths, so a leak/guess of one doesn't expose the other.
- All comparisons done on hashes, constant-time comparison to avoid timing side-channels (low real-world risk here, but cheap to do correctly).
- No network component at all — everything is local-only, reducing attack surface and eliminating any need for remote credential storage.

## 6. Testing Strategy
- **Use a secondary device or a fresh emulator/work-profile** for all Accessibility/Device-Admin interception development. A bug in the "detect Settings navigation → back out" logic can lock you out of your own Settings app; recovering without ADB is painful.
- Keep ADB access available on the test device throughout development (even though the final "production" install on the daily-driver device may have USB debugging off) so you can always force-uninstall during iteration.
- Manually test each bypass vector (Safe Mode boot, ADB uninstall/disable, ADB `settings put` for accessibility) to confirm they behave as expected (i.e., that they *do* still work — this is the accepted limitation, not a bug).

## 7. Known Limitations (carried from PRD)
- Safe Mode disables all third-party apps including this one — no fix without Device Owner.
- ADB commands bypass all protections if USB debugging is enabled on the device.
- Factory reset wipes everything.
- True "cannot be uninstalled under any circumstance" behavior requires Device Owner provisioning via `adb shell dpm set-device-owner`, which requires a device with no accounts configured — explicitly unavailable given this project's constraints.

## 8. Tech Stack Summary
- Flutter/Dart — UI layer
- Kotlin — native Android module (Accessibility, Device Admin, overlay, foreground service, Room, EncryptedSharedPreferences)
- Room — local persistence
- Jetpack Security (EncryptedSharedPreferences) — credential storage
- Flutter Platform Channels — cross-layer communication
- Development environment: Gemini 3.8 Flash (High) in Antigravity IDE
