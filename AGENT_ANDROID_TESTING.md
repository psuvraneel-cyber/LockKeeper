# LockKeeper / AppLocker - Autonomous Android & Flutter Testing Guide

This guide documents the verified environment configuration, build pipeline, emulator automation, permissions management, UI testing procedures, and common failure modes for the LockKeeper application on Windows.

---

## 1. Verified Toolchain & Environment

| Component | Verified Version / Details | Path |
| :--- | :--- | :--- |
| **Flutter SDK** | Flutter 3.41.6 (Channel stable) | `C:\flutter\bin\flutter.bat` |
| **Dart SDK** | Dart 3.11.4 | `C:\flutter\bin\cache\dart-sdk\bin\dart.exe` |
| **JDK** | Microsoft OpenJDK 17.0.18.8 (hotspot, 64-bit) | `C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot` |
| **Android SDK** | Build-tools, platforms 34/35/36, emulator, cmdline-tools | `C:\Android\Sdk` |
| **ADB Executable** | Android Debug Bridge version 1.0.41 (37.0.1) | `C:\Android\Sdk\platform-tools\adb.exe` |
| **Emulator Executable**| Android Emulator version 36.4.9.0 | `C:\Android\Sdk\emulator\emulator.exe` |

### Environment Variables & PATH Configuration
Ensure the following directories are present in your session or persistent `PATH`:
```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot"
$env:ANDROID_HOME = "C:\Android\Sdk"
$env:PATH = "C:\flutter\bin;C:\Android\Sdk\platform-tools;C:\Android\Sdk\emulator;$env:JAVA_HOME\bin;$env:PATH"
```

---

## 2. Verified Emulator Configuration

| Attribute | Specification |
| :--- | :--- |
| **AVD Name** | `Antigravity_Test` |
| **Target OS / API** | Android 16.0 ("Baklava") / API Level 36 |
| **Architecture / ABI** | `x86_64` (Google APIs) |
| **Device ID** | `emulator-5554` |
| **Screen Resolution** | 1080 x 2400 (420 dpi) |
| **Boot Property** | `sys.boot_completed = 1` |

### Emulator Startup Command (Visible Window)
To launch the emulator visibly from PowerShell:
```powershell
Start-Process "C:\Android\Sdk\emulator\emulator.exe" -ArgumentList "-avd", "Antigravity_Test"
& adb wait-for-device
while ((& adb -s emulator-5554 shell getprop sys.boot_completed).Trim() -ne "1") { Start-Sleep -Seconds 2 }
```

Or execute the provided automation script:
```powershell
powershell -ExecutionPolicy Bypass -File C:\AppLocker\scripts\android_boot.ps1
```

---

## 3. Build Configuration & Root Cause Analysis

### The Build Directory Trap (`android/build.gradle.kts`)
- **Symptoms:** Running `flutter build apk --debug` completes with Gradle reporting `BUILD SUCCESSFUL`, yet Flutter fails with:
  ```text
  Gradle build failed to produce an .apk file. It's likely that this file was generated under ...
  ```
- **Root Cause:** A custom redirect in `android/build.gradle.kts`:
  ```kotlin
  // PROBLEMATIC:
  val newBuildDir: Directory = rootProject.layout.buildDirectory.dir("C:/temp/lockkeeper_build").get()
  rootProject.layout.buildDirectory.value(newBuildDir)
  ```
  diverts the APK output outside the project tree. Flutter's CLI strictly looks for the APK artifact at:
  `C:\AppLocker\build\app\outputs\flutter-apk\app-debug.apk` (`../../build` relative to `android/`).
- **The Correct Fix:**
  In `android/build.gradle.kts`:
  ```kotlin
  val newBuildDir: Directory = rootProject.layout.buildDirectory.dir("../../build").get()
  rootProject.layout.buildDirectory.value(newBuildDir)

  subprojects {
      val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
      project.layout.buildDirectory.value(newSubprojectBuildDir)
  }
  ```

### Build Command
```powershell
cd C:\AppLocker
# Stop previous Gradle daemons to clear stale directory caches
cd android; .\gradlew.bat --stop; cd ..

# Fetch dependencies and build
flutter pub get
flutter build apk --debug -v
```

### Verified Artifact
- **Path:** `C:\AppLocker\build\app\outputs\flutter-apk\app-debug.apk`
- **Size:** 145,279,729 bytes (~138.55 MB)

Or run:
```powershell
powershell -ExecutionPolicy Bypass -File C:\AppLocker\scripts\android_build.ps1
```

---

## 4. Installation & Permission Orchestration

### Installation Command
```powershell
adb -s emulator-5554 install -r C:\AppLocker\build\app\outputs\flutter-apk\app-debug.apk
```

> [!NOTE]
> If `INSTALL_FAILED_UPDATE_INCOMPATIBLE` occurs due to mismatched keystores from an earlier build, first remove the active Device Administrator (if set), then uninstall and reinstall:
> ```powershell
> adb -s emulator-5554 shell dpm remove-active-admin "com.lockkeeper.app/com.lockkeeper.app.receiver.LockKeeperDeviceAdminReceiver"
> adb -s emulator-5554 uninstall com.lockkeeper.app
> adb -s emulator-5554 install -r C:\AppLocker\build\app\outputs\flutter-apk\app-debug.apk
> ```

### Required Permission Configuration Commands
LockKeeper enforces a 6-layer defense mechanism requiring specialized system privileges:

```powershell
# 1. Overlay (SYSTEM_ALERT_WINDOW)
adb -s emulator-5554 shell appops set com.lockkeeper.app SYSTEM_ALERT_WINDOW allow

# 2. Usage Stats (GET_USAGE_STATS)
adb -s emulator-5554 shell appops set com.lockkeeper.app GET_USAGE_STATS allow

# 3. Post Notifications (Android 13+)
adb -s emulator-5554 shell pm grant com.lockkeeper.app android.permission.POST_NOTIFICATIONS

# 4. Battery Optimization Whitelist (Prevent Doze killing background watchdog)
adb -s emulator-5554 shell dumpsys deviceidle whitelist +com.lockkeeper.app

# 5. Accessibility Service
adb -s emulator-5554 shell settings put secure enabled_accessibility_services com.lockkeeper.app/com.lockkeeper.app.service.LockKeeperAccessibilityService
adb -s emulator-5554 shell settings put secure accessibility_enabled 1

# 6. Device Administrator (Android 16 syntax requires --user current)
adb -s emulator-5554 shell dpm set-active-admin --user current com.lockkeeper.app/com.lockkeeper.app.receiver.LockKeeperDeviceAdminReceiver
```

Or execute:
```powershell
powershell -ExecutionPolicy Bypass -File C:\AppLocker\scripts\android_install.ps1
```

---

## 5. UI Automation & Testing Procedures

### Launching Application
```powershell
adb -s emulator-5554 shell am start -n com.lockkeeper.app/.MainActivity -a android.intent.action.MAIN -c android.intent.category.LAUNCHER
```

### Inspecting Foreground Focus
```powershell
adb -s emulator-5554 shell dumpsys window | Select-String "mFocusedApp|mCurrentFocus"
```

### Dumping UI Hierarchy
```powershell
adb -s emulator-5554 shell uiautomator dump /sdcard/window_dump.xml
adb -s emulator-5554 pull /sdcard/window_dump.xml C:\AppLocker\artifacts\window_dump.xml
```

### Capturing Screenshots Reliably in PowerShell
> [!CRITICAL]
> Never use `adb exec-out screencap -p > file.png` in standard Windows PowerShell. PowerShell treats stdout redirection as text and encodes it with UTF-16LE or system codepage, which corrupts the binary PNG header and makes the image unreadable.
>
> **The Reliable Two-Step Method:**
> ```powershell
> adb -s emulator-5554 shell screencap -p /sdcard/screen.png
> adb -s emulator-5554 pull /sdcard/screen.png C:\AppLocker\artifacts\screen.png
> ```

### Onboarding Steps & Key Input Sequences
1. **Welcome Screen (Step 1/9):** Tap `[540, 2185]` ("Begin Setup").
2. **Permissions (Steps 2–6):** Grant permissions using the commands above. Tap `[540, 2185]` ("Continue") on each screen.
3. **PIN Creation (Step 7/9):**
   - Tap PIN input `[540, 715]`, type PIN via `adb shell input text 1234`.
   - Tap Confirm PIN input `[540, 890]`, type PIN via `adb shell input text 1234`.
   - Hide keyboard via `adb shell input keyevent KEYCODE_BACK`.
   - Tap "Save PIN" at `[540, 2185]`.
   - *Validation Test:* Submitting with length < 4 triggers error `"PIN must be between 4 and 8 digits"`.
4. **Admin Password (Step 8/9):**
   - Tap Password input `[540, 715]`, type distinct password via `adb shell input text adminpass`.
   - Tap Confirm Password input `[540, 890]`, type via `adb shell input text adminpass`.
   - Hide keyboard via `adb shell input keyevent KEYCODE_BACK`.
   - Tap "Set Admin Password" at `[540, 2185]`.
   - *Validation Test:* Entering `1234` matching PIN triggers error `"Admin password must be distinct from user PIN"`.
5. **Setup Complete (Step 9/9):**
   - Tap "Go to App Locker" at `[540, 2185]`.
6. **HomeScreen Operations:**
   - Tap Chrome lock toggle at `[966, 680]` -> status toggles to `15m cooldown`.
   - Tap filter tab "Locked (1)" at `[438, 473]` -> displays only locked applications.
   - Tap Settings icon at `[972, 192]` -> opens Settings & Security screen.
   - Tap Back icon at `[108, 192]` -> returns to HomeScreen.

---

## 6. Deterministic Automation Scripts

The project includes four production-grade PowerShell automation scripts in `C:\AppLocker\scripts\`:

| Script | Purpose |
| :--- | :--- |
| `scripts\android_boot.ps1` | Verifies ADB, starts emulator visibly if stopped, waits for `sys.boot_completed=1`, verifies resolution. |
| `scripts\android_build.ps1` | Stops stale Gradle daemons, resolves dependencies, executes debug build, verifies output APK path and size. |
| `scripts\android_install.ps1` | Validates device connection, installs APK (handling signature conflicts cleanly), and grants all required permissions. |
| `scripts\android_test.ps1` | Launches app, waits for window focus, captures UI hierarchy XML and screenshot, verifies zero runtime fatal exceptions in logcat. |

---

## 7. Common Pitfalls & Recovery Guide

### 1. PowerShell Native Command Error
- **Cause:** When `$ErrorActionPreference = "Stop"` is set, Windows PowerShell converts any native utility output to stderr into a fatal `NativeCommandError`—even for non-fatal warnings from `adb` or `flutter`.
- **Solution:** In scripts, use `$ErrorActionPreference = "Continue"` and explicitly inspect `$LASTEXITCODE` or check output conditions.

### 2. PowerShell Array Negation Trap
- **Cause:** Evaluating `if ($array -notmatch "pattern")` filters the array and returns non-matching elements. If any non-matching element exists, the expression returns a non-empty array which evaluates to `$true` in PowerShell!
- **Solution:** Always write `if (-not ($array -match "pattern"))` or `if (($array -join "`n") -notmatch "pattern")`.

### 3. Signature Mismatch on Reinstall
- **Cause:** If an earlier version of the app is already installed with a different debug keystore signature, `adb install -r` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
- **Solution:** Remove Device Admin first (`adb shell dpm remove-active-admin ...`), then uninstall with `adb uninstall com.lockkeeper.app`, and retry install.

### 4. Device Admin Command Syntax on Modern Android
- **Cause:** `dpm set-device-admin` is deprecated or unavailable in Android 14+.
- **Solution:** Use `dpm set-active-admin --user current <Component>`.
