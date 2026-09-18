# LockKeeper v1.0.0 Release Notes

**Release Date:** September 15, 2026  
**Package Identifier:** `com.lockkeeper.app`  
**Version:** 1.0.0 (Version Code: 1)  
**SDK Support:** Minimum SDK 26 (Android 8.0) | Target SDK 36 (Android 16) | Compile SDK 36  
**Architecture:** Hybrid Kotlin Native Services + Flutter Dark Utilitarian UI  
**Distribution:** Direct Sideload Signed Release APK  

---

## 1. Executive Summary

LockKeeper is a zero-telemetry, offline-only personal friction app locker engineered to enforce digital intentionality and eliminate impulsive usage patterns. Unlike commercial "productivity" lockers that harvest user habits or rely on easily bypassed foreground monitors, LockKeeper operates as an uncompromised local security barrier with hardware-backed cryptographic credentials, system overlay interception, and anti-tamper mechanisms.

---

## 2. Key Architecture & Features

### 🛡️ Dual-Tier Cryptographic Credential Hierarchy
- **Daily User PIN (4–6 digits):** Designed for conscious deliberate access to locked applications.
- **Admin Emergency Password (8+ alphanumeric characters):** Required for high-stakes configuration changes, credential resets, and deactivation attempts.
- **Hardware-Backed Keystore Encryption:** Salted PBKDF2 hash wrappers encrypted with AES-256-GCM keys managed by the Android Keystore system. Credential data never touches external storage in plaintext.

### ⏱️ Strict Lock & Cooldown Timers
- **Standard Mode:** Unlocks immediately upon entering the correct PIN. Exiting the app triggers a configurable cooldown period (15m, 1h, 4h, 24h, or Custom) before the next lock cycle.
- **Strict Mode:** For high-distraction apps, Strict Lock prohibits unlocking entirely while a cooldown is active. The countdown overlay displays remaining time and redirects the user to the home launcher.
- **Live Visual Feedback:** Active cooldowns update in real-time on the main app dashboard with countdown chips.

### 🔒 Layered System Protection & Anti-Tamper
- **Accessibility Service Detection:** High-speed, event-driven window state detection (`TYPE_WINDOW_STATE_CHANGED`) to intercept target app launches before render.
- **Fallback Foreground Service:** Background polling with `UsageStatsManager` ensuring continued protection even if the Accessibility Service is interrupted or degraded.
- **System Overlay (`TYPE_APPLICATION_OVERLAY`):** Native hardware-accelerated full-screen overlay views (`PinOverlayView`, `CooldownOverlayView`, `AdminOverlayView`) intercepting all touch and key events.
- **Device Administration Protection:** Registered `DeviceAdminReceiver` preventing unauthorized app uninstallation or clearing from device management.
- **Boot Persistence:** `BootReceiver` automatically restores foreground monitoring and database policies immediately upon device reboot (`BOOT_COMPLETED`).

### 📱 Utilitarian Dark UI (Flutter Presentation)
- **Guided 9-Step Onboarding:** Clear permission explanations and real-time capability verification for Accessibility, Overlays, Usage Access, Device Admin, and Battery Optimization.
- **App Inventory & Search:** Filter applications by All, Locked, and Unlocked with instant search and toggle switches.
- **App Lock Detail Screen:** Fine-grained configuration per application for cooldown durations and strict mode enforcement.
- **Security Audit Screen:** Centralized dashboard to audit active permissions, test service health, and rotate PIN or Admin passwords.

---

## 3. Privacy & Offline Guarantee

- **Zero Network Permissions:** LockKeeper requests no `android.permission.INTERNET`. Network communication is physically impossible at the OS level.
- **Zero Analytics / Telemetry:** No third-party SDKs, crash reporters, or user behavioral trackers.
- **100% Local Persistence:** SQLite Room database stored in sandboxed internal storage.

---

## 4. Release Artifacts & Verification

| Artifact | File Name | Description |
| :--- | :--- | :--- |
| **Release APK** | `LockKeeper-v1.0.0-release.apk` | Digitally signed release APK for direct sideloading |
| **Checksums** | `checksums.txt` | SHA-256 integrity verification hash |
| **Release Notes** | `release-notes.md` | Feature summary, architecture, and instructions |
| **Test Report** | `test-report.md` | Comprehensive automated and live device verification audit |

### SHA-256 Checksum:
```text
3102e67957193aa4d8a87f248afb372abed7ace60457400085ba7837054fd4f8  LockKeeper-v1.0.0-release.apk
```

---

## 5. Sideload Installation Instructions

1. Transfer `LockKeeper-v1.0.0-release.apk` to your target Android device.
2. Verify the SHA-256 checksum:
   ```bash
   sha256sum LockKeeper-v1.0.0-release.apk
   # Output must match: 3102e67957193aa4d8a87f248afb372abed7ace60457400085ba7837054fd4f8
   ```
3. Tap the APK file to install (or via ADB: `adb install LockKeeper-v1.0.0-release.apk`).
4. Launch LockKeeper and follow the 9-step guided setup wizard to configure your PIN, Admin Password, and system permissions.
