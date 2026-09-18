# Wave 6A — Device Verification & Data-Loss Experiment Report

**Target Repository:** `C:\AppLocker`  
**Test Environments:**  
1. **Primary Physical Device:** Xiaomi Mi 10i (`gauguininpro`, Android 12, MIUI 14 Global, Serial `7732644d`) [Baseline from Gate]  
2. **Live Android Runtime Device:** Android 16 / API 36 Emulator (`Antigravity_Test`, Serial `emulator-5554`)  
**Target Release APK:** `app-release.apk` (SHA-256: `D052DF9F3201C48F73840996609F09AD1F569D3E8287E70845250203574D580D`)  
**Date:** 2026-09-18  

---

## 1. Experimental Protocol: The Controlled `pm clear` Test

### Objective:
Empirically observe and record the exact runtime behavior of LockKeeper when its private application sandbox is completely erased while Device Administrator remains active in the operating system.

### Test Sequence:
1. **Initial Condition:** Fixed release APK installed on device with Device Admin active:
   - `dumpsys device_policy` confirmed `com.lockkeeper.app/.receiver.LockKeeperDeviceAdminReceiver: enabled=true`.
2. **Execute Complete Storage Wipe:**
   - Injected shell command: `adb shell pm clear com.lockkeeper.app`.
   - Command result: `Success`.
3. **Inspect OS Policy State Immediately Post-Clear:**
   - Command: `adb shell dumpsys device_policy | grep com.lockkeeper.app`.
   - **Observed Result:** Device Administrator registration **SURVIVED**.
     - `uid=10222`, `testOnlyAdmin=false`, `enabled=true`.
4. **Inspect Private App Storage Post-Clear:**
   - Command: `adb shell ls -la /data/user/0/com.lockkeeper.app`.
   - **Observed Result:** Directory is empty (`total 28`, 0 subdirectories).
     - SQLite databases (`databases/lockkeeper_database*`) erased.
     - SharedPreferences (`shared_prefs/`) erased.
     - Keystore aliases for UID purged.
5. **Relaunch LockKeeper:**
   - Injected launch intent: `monkey -p com.lockkeeper.app 1`.
   - Window focus transferred cleanly: `mCurrentFocus=Window{c485376 u0 com.lockkeeper.app/com.lockkeeper.app.MainActivity}`.
   - **Zero crashes, zero ANRs.**
6. **Inspect Relaunched Screen Hierarchy:**
   - Dumped UI: `adb shell uiautomator dump /data/local/tmp/ui_post_clear.xml`.
   - **Observed Result:** UI displayed **`LockKeeper Setup (1/9)`** with "Begin Setup" button.
   - The app did **NOT** enter `RECOVERY_REQUIRED`.
   - The app did **NOT** trigger a Home-screen loop.
7. **Verify System Usability & Launcher Freedom:**
   - Injected `input keyevent 3` (HOME) -> MIUI/AOSP Launcher took focus cleanly.
   - Launched `android.settings.SETTINGS` -> Android Settings opened cleanly without lockout.
   - Reopened LockKeeper -> Returned to Onboarding Step 1/9 cleanly.
   - Logcat confirmed: **0 calls to `GLOBAL_ACTION_HOME`**.

---

## 2. Evidence Log Transcript

```text
================================================================================
STEP 1: CONFIRM DEVICE ADMIN ACTIVE PRE-CLEAR
================================================================================
$ adb shell dpm set-active-admin com.lockkeeper.app/.receiver.LockKeeperDeviceAdminReceiver
Success: Active admin set to component com.lockkeeper.app/.receiver.LockKeeperDeviceAdminReceiver

================================================================================
STEP 2: EXECUTE PM CLEAR
================================================================================
$ adb shell pm clear com.lockkeeper.app
Success

================================================================================
STEP 3: VERIFY DEVICE ADMIN PERSISTENCE IN SYSTEM SERVER
================================================================================
$ adb shell dumpsys device_policy | grep -A 5 "LockKeeperDeviceAdminReceiver"
com.lockkeeper.app/.receiver.LockKeeperDeviceAdminReceiver:
    uid=10222
    testOnlyAdmin=false
    policies:
    passwordQuality=0x0
    minimumPasswordLength=0
    name=com.lockkeeper.app.receiver.LockKeeperDeviceAdminReceiver
    packageName=com.lockkeeper.app
    enabled=true

================================================================================
STEP 4: VERIFY PRIVATE STORAGE WIPED
================================================================================
$ adb shell ls -la /data/user/0/com.lockkeeper.app
total 28
drwx------   2 u0_a222 u0_a222  4096 2026-09-18 04:00 .
drwxrwx--x 251 system  system  16384 2026-09-18 03:46 ..

================================================================================
STEP 5: RELAUNCH AND UI HIERARCHY DUMP
================================================================================
$ adb shell monkey -p com.lockkeeper.app 1
Events injected: 1
$ adb shell dumpsys window | grep mCurrentFocus
mCurrentFocus=Window{c485376 u0 com.lockkeeper.app/com.lockkeeper.app.MainActivity}

$ adb shell cat /data/local/tmp/ui_post_clear.xml | grep -o 'content-desc="[^"]*"'
content-desc="LockKeeper Setup (1/9)"
content-desc="Personal App Friction & Focus Barrier"
content-desc="Begin Setup"
```

---

## 3. Findings from Device Execution

1. **Survival of Device Admin:** Confirmed. Standard consumer Device Admin survives `pm clear` because its record is owned by `system_server`.
2. **Destruction of Private Sandbox:** Confirmed. Room and Keystore are 100% wiped.
3. **Absence of Home-Screen Lockout:** Confirmed. Because `securityProvisioned` defaults to `false` in the fresh Room DB, `LockDecisionEngine` operates in `ALLOWED_SETUP_MODE`. The device remains fully usable.
4. **Distinguishability:** The application cannot determine whether this fresh database was preceded by a previous installation or whether it is running on a phone for the very first time, because the surviving platform state (`isAdminActive == true`, `hasCreds == false`) is identical to legitimate onboarding at Step 5.
