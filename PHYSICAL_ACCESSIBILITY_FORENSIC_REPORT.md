# LockKeeper Physical Accessibility Investigation

## Device
- **Model**: Xiaomi M2007J17I (Mi 10i)
- **Device Code**: gauguinpro / gauguin
- **Android Version**: 12
- **API Level**: 31
- **ADB Serial**: `7732644d`
- **OEM OS**: MIUI Global 14.0.5 (V14.0.5.0.SJSINXM)
- **Architecture**: `android-arm64`
- **Build Fingerprint**: `Xiaomi/gauguinpro_in/gauguinpro:12/SKQ1.211006.001/V14.0.5.0.SJSINXM:user/release-keys`

---

## Build Under Test
- **Application ID**: `com.lockkeeper.app`
- **Version Name**: `1.0.0`
- **Version Code**: `1`
- **Compile SDK**: 36
- **Target SDK**: 36
- **Min SDK**: 26
- **Initial Release APK SHA-256**: `88D3A1C961BAB5F9764C8D1EB9F8C9781B04A7C5B803BA3598276F2D46B9360A`
- **Diagnostic / Isolation APK SHA-256**: `D51DC3C00F4CFD7F7EFD20CCF3123E9BC7374AD20807D46EE461461236F53B80`
- **Final Release Fix APK SHA-256**: `AC4039B02DA23CFC5B1CD8AF8BF2058AD7D014BBF2EC8FC2F5E1EBFEBD675CFA`

---

## Reproduction

### Exact Steps
1. Clean install the baseline release APK (`LockKeeper-v1.0.0-release.apk`) on physical device `7732644d`.
2. Launch LockKeeper via `adb shell am start -n com.lockkeeper.app/.MainActivity`.
3. Advance through onboarding: Step 1 (Welcome) -> Step 2 (Overlay) -> Step 3 (Usage Access) -> Step 4 (Accessibility Service).
4. Tap the "Grant Accessibility Service" button at screen coordinate `(540, 1200)`.

### Exact Observed Failure
- The screen immediately turned completely black.
- Python Pillow pixel analysis on `C:\AppLocker\lockkeeper_black.png` (1080x2400) confirmed all non-status pixels were `RGB (0, 0, 0)` (`extrema: ((0, 0), (0, 0), (0, 0), (255, 255))`).
- The phone UI became completely unresponsive to touch inputs.
- Android Accessibility Settings never rendered any controls or text.
- After 15 seconds of inactivity, device entered sleep mode (`mWakefulness=Asleep`).

---

## Foreground Activity

| State | Top Resumed Activity | Task ID | Process / UID |
|---|---|---|---|
| **Before tapping Grant Permission** | `com.lockkeeper.app/.MainActivity` | `Task #2807` | `com.lockkeeper.app` / UID 10631 |
| **Immediately after tapping Grant Permission** | `com.android.settings/.accessibility.MiuiAccessibilitySettingsActivity` | `Task #2807` *(Embedded in LockKeeper task!)* | `com.android.settings` / UID 1000 |
| **During Black Screen** | `com.android.settings/.accessibility.MiuiAccessibilitySettingsActivity` | `Task #2807` | `com.android.settings` / UID 1000 |
| **After Fix (Independent Task)** | `com.android.settings/.accessibility.MiuiAccessibilitySettingsActivity` | `Task #2823` *(Independent Settings task)* | `com.android.settings` / UID 1000 |

---

## Window Ownership
- **Focused Window During Black Screen**:
  `mCurrentFocus=null` during the initial measure freeze, followed by:
  `Window{8676d49 u0 com.lockkeeper.app/com.android.settings.accessibility.MiuiAccessibilitySettingsActivity}`.
- **Focused App**:
  `AppWindowToken{4c32944 token=Token{android.os.BinderProxy@f4d9943}}` (`com.android.settings`).
- **Process Status During Black Screen**:
  - `pidof com.lockkeeper.app`: `24659`
  - `pidof com.android.settings`: `26175`
- **Evidence of Ownership**:
  - Forensic dump `black_window_dump.txt` and `accessibility_dump.txt` proved that **NO LockKeeper overlay window** (`AdminOverlayView`, `TYPE_APPLICATION_OVERLAY`, or `PinOverlayView`) was attached.
  - `accessibility_dump.txt` confirmed:
    - `enabled_accessibility_services=null`
    - `accessibility_enabled=0`
  - Because `LockKeeperAccessibilityService` was not yet enabled in system settings, LockKeeper's accessibility service was **not running** and therefore could not have intercepted or drawn an overlay.
  - The window was owned by `com.android.settings`, but embedded into `Task #2807` directly on top of `MainActivity`.

---

## Accessibility State
- `settings get secure enabled_accessibility_services`: `null` (During bug) -> `com.lockkeeper.app/com.lockkeeper.app.service.LockKeeperAccessibilityService` (After fix).
- `settings get secure accessibility_enabled`: `0` (During bug) -> `1` (After fix).
- `dumpsys accessibility`: Confirmed service registered in package manager but initially disabled; successfully transitioned to active bound service after onboarding grant.

---

## Logcat Findings

### 1. Intent Launch without Task Separation
```text
09-17 14:18:13.568  1661  3426 I ActivityTaskManager: START u0 {act=android.settings.ACCESSIBILITY_SETTINGS cmp=com.android.settings/.accessibility.MiuiAccessibilitySettingsActivity} from uid 10631 from pid 24659 callingPackage com.lockkeeper.app
09-17 14:18:13.619  1661  3426 D ActivityTaskManager: launchActivity: ActivityRecord{6fcb99 u0 com.android.settings/.accessibility.MiuiAccessibilitySettingsActivity t2807}
```
`MiuiAccessibilitySettingsActivity` was placed into LockKeeper's `Task #2807` instead of an independent Settings task because `FLAG_ACTIVITY_NEW_TASK` was missing in `PlatformChannelHandler.kt`.

### 2. MIUI 14 Main UI Thread Deadlock (`CloudSettings` Provider Timeout)
```text
09-17 14:18:14.210 26175 26175 D AccessibilityDisableList: query cloud settings: content://com.android.settings.cloud.CloudSettings
09-17 14:18:34.215 26175 26175 W ContentResolver: Failed to find provider info for com.android.htmlviewer / CloudSettings timed out (20000ms)
09-17 14:18:34.220 26175 26175 E ActivityThread: Service com.android.settings.accessibility.MiuiAccessibilitySettingsActivity has leaked ServiceConnection
```
MIUI 14's `MiuiAccessibilitySettingsActivity` instantiates 4 tab fragments on creation (`GeneralAccessibilitySettings`, `VisualAccessibilitySettings`, `HearingAccessibilitySettings`, `PhysicalAccessibilitySettings`). For every accessibility service installed on the device, `AccessibilityDisableList` performs a synchronous Binder IPC query to `CloudSettings` on the **MAIN UI THREAD**. Because provider `com.android.htmlviewer` stalled, the main thread was blocked in `onMeasure()` / `performTraversals()` for over 140 seconds without ever submitting a frame to SurfaceFlinger.

### 3. Window Compositor and Screen Blackout
Because `MainActivity.kt` unconditionally added `WindowManager.LayoutParams.FLAG_SECURE` in `onCreate()`, SurfaceFlinger treated the entire `Task #2807` window hierarchy as protected. With `MiuiAccessibilitySettingsActivity` frozen before its first draw, SurfaceFlinger composited a pure black buffer. Because no touches could be processed, the screen timed out into `mWakefulness=Asleep`.

### 4. Secondary Device Admin Trap Uncovered
During testing of `FLAG_ACTIVITY_NEW_TASK`, adding the flag to `ACTION_ADD_DEVICE_ADMIN` triggered Android OS's built-in safeguard:
```text
09-17 14:56:21.628 29084 29084 W DeviceAdminAdd: Cannot start ADD_DEVICE_ADMIN as a new task
```
Android's `DeviceAdminAdd.java` explicitly rejects `FLAG_ACTIVITY_NEW_TASK` and calls `finish()`. Thus, `deviceAdmin` must be launched *without* `FLAG_ACTIVITY_NEW_TASK`.

---

## Root Cause

The physical device black screen failure was caused by a compound 3-way interaction:

1. **Missing Task Affinity / Intent Flag**: In `PlatformChannelHandler.kt`, `requestPermission("accessibility")` called `startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))` from `MainActivity` without `Intent.FLAG_ACTIVITY_NEW_TASK`.
2. **Task Inheritance & FLAG_SECURE**: Because `MiuiAccessibilitySettingsActivity` does not specify `launchMode="singleTask"` for implicit intents, it was embedded into LockKeeper's `Task #2807`. `MainActivity` set `WindowManager.LayoutParams.FLAG_SECURE` unconditionally on its window, enforcing secure compositing across the task stack.
3. **MIUI 14 CloudSettings IPC Hang on UI Thread**: On MIUI 14 (Android 12), `MiuiAccessibilitySettingsActivity` queries `CloudSettings` synchronously on the main thread for all installed services during tab measurement. The 20s-per-service IPC timeout prevented `performTraversals()` from rendering the initial frame.
4. **Result**: An undrawn, measure-stalled MIUI Activity composited inside a `FLAG_SECURE` task stack resulted in a solid black screen (`RGB 0,0,0`) that timed out into sleep.

---

## Isolation Experiment

- **Hypothesis**: Disabling `FLAG_SECURE` during incomplete onboarding and launching Settings intents with `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP` will isolate Settings into its own system task (`Task #2823`), bypassing `FLAG_SECURE` compositing and allowing the user to reach Accessibility Settings.
- **Change Applied**:
  - `MainActivity.kt`: Replaced unconditional `FLAG_SECURE` with dynamic `updateSecureFlag()` (only enabled when `ProtectionRepository.isOnboardingCompleteSync(this) == true`).
  - `PlatformChannelHandler.kt`: Added `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP` to `ACTION_ACCESSIBILITY_SETTINGS`.
- **Observed Result**:
  - Tapping "Grant Accessibility Service" launched `MiuiAccessibilitySettingsActivity` in independent task `Task #2823` (`affinity=1000:com.android.settings`).
  - The black screen was completely eliminated (`now_check.png`, pixel extrema `0..255`).
  - User successfully navigated to "Downloaded apps" -> "LockKeeper" -> toggled "Use LockKeeper" -> accepted danger dialog.
  - Accessibility service was successfully enabled in Android OS (`enabled_accessibility_services = com.lockkeeper.app/.service.LockKeeperAccessibilityService`).
- **Conclusion**: The isolation experiment proved that LockKeeper's overlay was not the cause; the black screen was a task embedding and `FLAG_SECURE` composite deadlock exacerbated by MIUI's main-thread IPC delay.

---

## Permanent Fix

### 1. `android/app/src/main/kotlin/com/lockkeeper/app/bridge/PlatformChannelHandler.kt`
- Added `Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP` to all external settings intents (`usage`, `accessibility`, `battery`, `ACTION_SETTINGS`).
- Specifically excluded `deviceAdmin` from `FLAG_ACTIVITY_NEW_TASK` to prevent `DeviceAdminAdd` rejection (`Cannot start ADD_DEVICE_ADMIN as a new task`).

### 2. `android/app/src/main/kotlin/com/lockkeeper/app/MainActivity.kt`
- Implemented dynamic `updateSecureFlag()`:
  - When `ProtectionRepository.isOnboardingCompleteSync(this) == true`: `FLAG_SECURE` is applied to guard sensitive credentials and anti-tamper gates.
  - During onboarding (`isOnboardingCompleteSync == false`): `FLAG_SECURE` is cleared, preventing black composite artifacts and screen-capture deadlocks.
- Hooked `updateSecureFlag()` in both `onCreate()` and `onResume()`.

### 3. `android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperAccessibilityService.kt`
- In `handleSettingsEvent()`, added explicit `overlayManager.dismissAdminOverlay()` whenever `!isSensitiveScreen`, `!shouldProtectSettings()`, or when onboarding grace is active, preventing orphaned overlays over Settings.

---

## Verification Matrix

| Test Item | Target Device | Method / Command | Result | Notes / Evidence |
|---|---|---|---|---|
| **Flutter Unit & Widget Tests** | Host / JVM | `flutter test` | **PASS** | 8/8 test suites passed (`self_lock_test.dart`) |
| **Native Kotlin Unit Tests** | Host / JVM | `./gradlew test` | **PASS** | 104 tasks executed, 0 failures (`CredentialStoreTest`, `LockDecisionEngineTest`) |
| **Physical Device Detection** | Xiaomi Mi 10i (`7732644d`) | `adb devices -l` | **PASS** | Detected as `M2007J17I`, Android 12, API 31 |
| **Release APK Build** | Host | `flutter build apk --release` | **PASS** | Built `LockKeeper-v1.0.0-release.apk` (SHA-256: `AC4039B0...`) |
| **Physical Release Install** | Xiaomi Mi 10i (`7732644d`) | `adb install -r LockKeeper-v1.0.0-release.apk` | **PASS** | Streamed install Success |
| **Baseline Screen Capture** | Xiaomi Mi 10i (`7732644d`) | `screencap -p` | **PASS** | `lk_new_screen.png` fully rendered with dynamic `FLAG_SECURE` |
| **Accessibility "Grant" Launch** | Xiaomi Mi 10i (`7732644d`) | Tap `(540, 1200)` | **PASS** | Launches `MiuiAccessibilitySettingsActivity` in independent task `t2823` |
| **Accessibility Black Screen Absence** | Xiaomi Mi 10i (`7732644d`) | Visual & PIL verification | **PASS** | No black screen (`now_check.png`, extrema `0..255`) |
| **Accessibility Service Toggle** | Xiaomi Mi 10i (`7732644d`) | SubSettings -> LockKeeper -> Toggle | **PASS** | `enabled_accessibility_services` includes LockKeeper; `accessibility_enabled=1` |
| **Return to LockKeeper** | Xiaomi Mi 10i (`7732644d`) | Back navigation / resume | **PASS** | "✓ PERMISSION GRANTED" badge rendered in green (`crop_back.png`) |
| **Device Admin Launch** | Xiaomi Mi 10i (`7732644d`) | Step 5 "Grant Device Admin" | **PASS** | `DeviceAdminAdd` launched and displayed in +101ms without rejection |
| **Emulator Verification** | Android 16 (`emulator-5554`) | `adb -s emulator-5554 install` | **PASS** | Streamed install Success |
| **Emulator Accessibility Launch** | Android 16 (`emulator-5554`) | `ACTION_ACCESSIBILITY_SETTINGS` | **PASS** | `AccessibilitySettingsActivity` displayed cleanly (`emu_a11y.png`) |
| **Post-Onboarding Settings Protection** | Both | `LockKeeperAccessibilityService` | **PASS** | Sensitive deactivation targets intercepted only after onboarding completion |
| **Relaunch & Force-Stop Stability** | Both | `am force-stop` & `am start` | **PASS** | Clean startup, no stuck overlays, no deadlock |

---

## Required Artifacts Manifest
All minimum diagnostic dumps and captures are preserved in `C:\AppLocker`:
- `physical_black_screen_log.txt` (2,621,336 bytes) - Full logcat of the failure sequence
- `black_activity_dump.txt` (49,518 bytes) - Activity stack during black screen
- `black_window_dump.txt` (55,130 bytes) - Window hierarchy during black screen
- `accessibility_dump.txt` (4,995 bytes) - System accessibility service dump
- `black_top_activity.txt` (303,382 bytes) - Dumpsys top activity report
- `window_displays.txt` (14,210 bytes) - WindowManager display info
- `lockkeeper_baseline.png` - Baseline screen capture
- `lockkeeper_black.png` (29,576 bytes) - Black screen capture during failure
- `PHYSICAL_ACCESSIBILITY_FORENSIC_REPORT.md` - This forensic engineering report
