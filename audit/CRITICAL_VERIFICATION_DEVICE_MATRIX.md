# LockKeeper Critical Verification Device Matrix

**Primary Physical Device:** Xiaomi Mi 10i (`gauguininpro`, Model `M2007J17I`, Android 12, MIUI Global 14.0.3, Serial `7732644d`)  
**Secondary Test Device:** Android API 36 Emulator (`Antigravity_Test`, Android 16 / Vanilla AOSP API 36, Serial `emulator-5554`)  
**Fixed Release Artifact:** `app-release.apk` (Version `1.0.0`, VersionCode `1`, Size `50,973,874 bytes`, SHA-256 `D052DF9F3201C48F73840996609F09AD1F569D3E8287E70845250203574D580D`)  
**Date:** 2026-09-18  

---

## 1. Device Execution Matrix

| TEST ID | STAGE & TEST NAME | DEVICE | OS / PLATFORM | BUILD TYPE | STEP / STIMULUS | EXPECTED RESULT | ACTUAL RESULT | LOG / RUNTIME EVIDENCE | VERDICT | CLASSIFICATION |
|---|---|---|---|---|---|---|---|---|---|---|
| **DEV-001** | Clean Release Installation | Xiaomi Mi 10i | Android 12 / MIUI 14 | Release | Uninstall old package, push `app-release.apk` to `/data/local/tmp`, execute `pm install` | Clean installation with 0 errors | `Success`, UID assigned: 10632 | `adb shell pm list packages \| grep lockkeeper` -> `package:com.lockkeeper.app` | **PASS** | PHYSICAL-DEVICE VERIFIED |
| **DEV-002** | Clean Release Installation | API 36 Emulator | Android 16 / API 36 | Release | Wipe old data, execute `adb install app-release.apk` | Clean installation | `Performing Streamed Install -> Success` | `adb shell pm list packages \| grep lockkeeper` -> `package:com.lockkeeper.app` | **PASS** | EMULATOR VERIFIED |
| **DEV-003** | Device Admin Grant Prior to PIN | Xiaomi Mi 10i | Android 12 / MIUI 14 | Release | Launch LockKeeper onboarding, execute `dpm set-active-admin ...LockKeeperDeviceAdminReceiver` at Step 5/9 before PIN created | Device Admin becomes active; app does not trap launcher | Success: Active admin set; LockKeeper remains interactive | `dumpsys device_policy` confirms `admin=com.lockkeeper.app/... uid=10632` | **PASS** | PHYSICAL-DEVICE VERIFIED |
| **DEV-004** | Device Admin Grant Prior to PIN | API 36 Emulator | Android 16 / API 36 | Release | Launch app, execute `dpm set-active-admin` before PIN setup | Device Admin active; no lockout | `Success: Active admin set to component...` | `dumpsys device_policy` confirms admin registration | **PASS** | EMULATOR VERIFIED |
| **DEV-005** | Home Navigation Under Device Admin Pre-PIN | Xiaomi Mi 10i | Android 12 / MIUI 14 | Release | Press HOME (`input keyevent 3`) while in `SETUP_IN_PROGRESS` with Device Admin active | Launcher (`com.miui.home`) displayed freely; 0 `GLOBAL_ACTION_HOME` loops | Launcher displayed cleanly; no recursion; no crash | Logcat: 0 `GLOBAL_ACTION_HOME` dispatches; window dump: `mCurrentFocus=com.miui.home` | **PASS** | PHYSICAL-DEVICE VERIFIED |
| **DEV-006** | Settings Navigation Pre-PIN | Xiaomi Mi 10i | Android 12 / MIUI 14 | Release | Launch `com.android.settings` while Device Admin active and PIN not configured | Settings opens freely (unrestricted setup mode); no admin lockout overlay | Settings opens without barrier | `mCurrentFocus=com.android.settings/.MiuiSettings` | **PASS** | PHYSICAL-DEVICE VERIFIED |
| **DEV-007** | Third-Party App Navigation Pre-PIN | Xiaomi Mi 10i | Android 12 / MIUI 14 | Release | Launch Calculator (`com.miui.calculator`) and Chrome | Apps open without lock overlay or interception | Calculator and Chrome open immediately | `mCurrentFocus=com.miui.calculator/...` | **PASS** | PHYSICAL-DEVICE VERIFIED |
| **DEV-008** | Return to Onboarding | Xiaomi Mi 10i | Android 12 / MIUI 14 | Release | Launch `com.lockkeeper.app` from launcher/recents | Onboarding resumes at current step; no self-lock | Onboarding resumed at Step 5; permissions verified | Screenshot `lk_05_onboarding_available.png` captured | **PASS** | PHYSICAL-DEVICE VERIFIED |
| **DEV-009** | Home/App Drawer Stress (20 Cycles) | Xiaomi Mi 10i | Android 12 / MIUI 14 | Release | 20 cycles: HOME -> App Drawer -> Settings -> LockKeeper -> HOME | No ANR, no home loop, no spontaneous termination | 20/20 cycles succeeded cleanly | Logcat: 0 ANRs, 0 crashes, 0 `GLOBAL_ACTION_HOME` calls | **PASS** | PHYSICAL-DEVICE VERIFIED |
| **DEV-010** | Rapid App Switching Stress (20 Cycles) | Xiaomi Mi 10i | Android 12 / MIUI 14 | Release | 20 cycles: HOME -> Chrome -> HOME -> Settings -> HOME -> LockKeeper -> Calculator | No loop, no process death | 20/20 cycles succeeded cleanly | System UI remained responsive; 0 lag spikes | **PASS** | PHYSICAL-DEVICE VERIFIED |
| **DEV-011** | Accessibility Active During Setup | Xiaomi Mi 10i | Android 12 / MIUI 14 | Release | Enable `LockKeeperAccessibilityService` before PIN set; navigate Home, Settings, Apps | `TYPE_WINDOW_STATE_CHANGED` events from `com.miui.home` do not trigger lock | Accessibility service bound; no overlay on Home | `dumpsys accessibility` confirms `Bound services:{LockKeeper}` | **PASS** | BOTH VERIFIED |
| **DEV-012** | Complete Onboarding Provisioning | Xiaomi Mi 10i | Android 12 / MIUI 14 | Release | Set PIN `1234` (Step 7), set Admin Pass `AdminPass2026!` (Step 8), tap "Go to App Locker" (Step 9) | `HomeScreen` displays installed apps; `securityProvisioned=true`, `onboardingComplete=true` committed | `HomeScreen` reached with 73 launchable apps | UI XML shows `LockKeeper` header and app list | **PASS** | PHYSICAL-DEVICE VERIFIED |
| **DEV-013** | Protected App Configuration | Xiaomi Mi 10i | Android 12 / MIUI 14 | Release | Toggle lock switch for `com.miui.calculator` (Calculator) on `HomeScreen` | Calculator added to `locked_apps` Room table with 15m cooldown | UI switch toggled to `checked="true"`; `Locked (1)` | `ui_calc_switch.xml` shows `Calculator 15m cooldown` checked | **PASS** | PHYSICAL-DEVICE VERIFIED |
| **DEV-014** | Protected App PIN Overlay Interception | Xiaomi Mi 10i | Android 12 / MIUI 14 | Release | Launch `com.miui.calculator` while provisioned | `LockKeeperAccessibilityService` intercepts and displays `PinOverlayView` with `FLAG_SECURE` | Focus transfers to `Window{... com.lockkeeper.app}`; Calculator blocked | `dumpsys window` confirms focus on overlay window with `SECURE` flag | **PASS** | PHYSICAL-DEVICE VERIFIED |
| **DEV-015** | Back Navigation on PIN Overlay | Xiaomi Mi 10i | Android 12 / MIUI 14 | Release | Press Back (`input keyevent 4`) on PIN overlay | Back intercepts and routes to HOME (`com.miui.home`); Calculator access prevented | Clean navigation to Home; Calculator remains covered | `dumpsys window` confirms `mCurrentFocus=com.miui.home` | **PASS** | PHYSICAL-DEVICE VERIFIED |
| **DEV-016** | PIN Overlay Successful Unlock | Xiaomi Mi 10i | Android 12 / MIUI 14 | Release | Launch Calculator; enter PIN `1234` | PIN verified; overlay dismissed; Calculator unlocked; session granted | Overlay dismissed; Calculator keypad becomes interactive | Window dump shows focus shifted to `com.miui.calculator` | **PASS** | PHYSICAL-DEVICE VERIFIED |
| **DEV-017** | Session Revocation on App Exit | Xiaomi Mi 10i | Android 12 / MIUI 14 | Release | Press HOME to leave Calculator, then immediately relaunch Calculator | Session revoked upon exit; PIN overlay reappears on relaunch | Overlay reappears immediately on launch; focus on `com.lockkeeper.app` | `dumpsys window` confirms `mCurrentFocus=com.lockkeeper.app` | **PASS** | PHYSICAL-DEVICE VERIFIED |
| **DEV-018** | Self-Lock Gate on Process Restart | Xiaomi Mi 10i | Android 12 / MIUI 14 | Release | Force-stop `com.lockkeeper.app`, relaunch app from launcher | `HomeScreen` is locked behind `Unlock LockKeeper` PIN gate; onboarding does not re-appear | `Unlock LockKeeper` displayed; PIN pad active | UI XML `ui_relaunch.xml` confirms `Unlock LockKeeper` & `Enter your PIN` | **PASS** | PHYSICAL-DEVICE VERIFIED |
| **DEV-019** | Self-Lock PIN Gate Unlock | Xiaomi Mi 10i | Android 12 / MIUI 14 | Release | Enter PIN `1234` on Self-Lock gate | Self-lock gate dismissed; `HomeScreen` accessible | `HomeScreen` loaded with locked apps and cooldown timer preserved | UI XML `ui_unlocked.xml` confirms `HomeScreen` restored | **PASS** | PHYSICAL-DEVICE VERIFIED |
| **DEV-020** | Incident Sequence on AOSP API 36 | API 36 Emulator | Android 16 / API 36 | Release | Fresh install release APK -> Grant Device Admin -> Switch Home -> Settings -> App Drawer -> LockKeeper | No infinite home loop; no lockout; Settings accessible; LockKeeper interactive | Clean execution; no home loop | Logcat: 0 `GLOBAL_ACTION_HOME` calls; `dumpsys window` clean | **PASS** | EMULATOR VERIFIED |

---

## 2. MIUI 14 vs AOSP Android 16 Behavioral Differences Observed

1. **Package Installation Security Restrictions:**
   - **MIUI 14:** `adb install` directly without prior authorization triggers `INSTALL_FAILED_USER_RESTRICTED` ("Install via USB" confirmation in MIUI Developer Options). Workaround verified: push APK to `/data/local/tmp` and invoke `pm install`.
   - **AOSP API 36:** Standard `adb install` operates directly without OEM-specific prompts.

2. **Device Administrator Revocation Policy:**
   - **MIUI 14:** Shell commands can remove device admins via standard `dpm remove-active-admin` when Developer Options allow USB security debugging.
   - **AOSP API 36:** Android 16 explicitly enforces `java.lang.SecurityException: Attempt to remove non-test admin` even via ADB shell unless the APK manifest specifies `android:testOnly="true"`. Root access (`adb root`) was required to modify policy files directly on API 36.

3. **Launcher Architecture & Window Hierarchy:**
   - **MIUI 14:** Uses `com.miui.home`, which continually dispatches `TYPE_WINDOW_STATE_CHANGED` and `TYPE_WINDOW_CONTENT_CHANGED` as desktop widgets and system feed (`com.mi.globalminusscreen`) animate. The recursion guard `isLauncherOrSystemUiPackage()` completely neutralizes these events.
   - **AOSP API 36:** Uses `com.android.launcher3` / Pixel Launcher, which generates fewer background content change events.

4. **Screen Capture of Secure Windows:**
   - On both MIUI 14 and API 36, `FLAG_SECURE` on `OverlayManager`'s layout parameters successfully blocks all ADB `screencap` output (returning 0-byte or black PNG buffers), preventing unauthorized screen scraping of PIN inputs.
