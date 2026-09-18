# Agent 13: Release, Build & Static Security Auditor Report

**Date:** 2026-09-17  
**Auditor:** Agent 13 — Release, Build & Static Security Auditor  
**Target:** Gradle Build Configurations, Manifest, Obfuscation, Signing & Release Artifacts  
**Status:** COMPLETE (Static Release Build Inspection)

---

## 1. Executive Summary

This audit examined LockKeeper's release engineering, build configurations, binary packaging, R8/ProGuard obfuscation rules, and compile-time security flags.

**Critical Findings:**
1. **R8 / ProGuard Completely Disabled in Release Builds:**
   In `android/app/build.gradle.kts:55-56`:
   ```kotlin
   buildTypes {
       release {
           ...
           isMinifyEnabled = false
           isShrinkResources = false
       }
   }
   ```
   **Minification and code obfuscation are completely disabled in release builds.**
   All native Kotlin security classes (`TamperDetectionEngine`, `TamperAuthorizationController`, `KeystoreCredentialStore`, `ProtectionRepository`), method names, PBKDF2 parameters, KeyStore aliases (`"LockKeeperMasterKey_v1"`), Room database tables, and string constants are packaged in plain, decompilable text inside the release APK.
2. **50MB Release Binary Committed Directly to Git:**
   The release artifact `LockKeeper-v1.0.0-release.apk` (50,907,814 bytes) was committed directly into git version control. Build scripts (`scripts/android_build.ps1`) copy the build output to the repo root and recalculate `checksums.txt`.
3. **Debug Fallback Signing in Release Configurations:**
   In `android/app/build.gradle.kts:50-54`:
   If `key.properties` does not exist on the building machine, the release build automatically signs using the default insecure Android debug key.
4. **Target SDK & Compilation Warnings:**
   - `compileSdk = 36`, `targetSdk = 36` (Android 16 preview/early platform target).
   - `minSdk = 26` (Android 8.0 Oreo).
   - Kapt warning during build: *"Kapt currently doesn't support language version 2.0+. Falling back to 1.9."*
   - Deprecation warnings: `unsafeCheckOpNoThrow`, `SOFT_INPUT_ADJUST_RESIZE`.
5. **Plaintext Diagnostic Logging in Production:**
   `LockKeeperAccessibilityService.kt` calls `android.util.Log.d("LockKeeperA11y", ...)` on every tamper event. These log tags output sensitive target packages and detection decisions to logcat without release stripping.

---

## 2. Component & Configuration Review

### 2.1 Android Manifest Security (`AndroidManifest.xml`)
- **Exported Components:**
  - `MainActivity`: `exported = true`, `launchMode = singleTop`, `taskAffinity = ""`. Required for launcher.
  - `LockKeeperAccessibilityService`: `exported = true`, protected by `android.permission.BIND_ACCESSIBILITY_SERVICE`. Compliant.
  - `LockKeeperDeviceAdminReceiver`: `exported = true`, protected by `android.permission.BIND_DEVICE_ADMIN`. Compliant.
  - `BootReceiver`: `exported = true`, filters `BOOT_COMPLETED` and `QUICKBOOT_POWERON`. Compliant.
  - `LockKeeperForegroundService`: `exported = false`. Compliant.
- **Dangerous Permissions Declared:**
  - `SYSTEM_ALERT_WINDOW`: Required for `TYPE_APPLICATION_OVERLAY`.
  - `PACKAGE_USAGE_STATS`: Protected permission for app usage monitoring.
  - `RECEIVE_BOOT_COMPLETED`: Needed to resume protection on reboot.
  - `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: High-risk Google Play policy permission.
  - `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE`: Android 14+ requirements.

---

### 2.2 R8 / ProGuard / Obfuscation Audit

```kotlin
buildTypes {
    release {
        isMinifyEnabled = false
        isShrinkResources = false
    }
}
```

#### Implications for Security:
1. **Reverse Engineering Ease:** Any attacker with `apktool` or `jadx` can decompile the release APK and read the exact implementation of `TamperDetectionEngine.kt`:
   - They can view the exact list of `PACKAGE_INSTALLER_PACKAGES`.
   - They can view the exact keyword lists (`UNINSTALL_KEYWORDS`, `ACCESSIBILITY_TOGGLE_KEYWORDS`).
   - They can discover the 45-node DFS cutoff and depth-6 limit.
   - They can craft exploits specifically designed to evade these exact conditions.
2. **Master Key Alias Exposure:** The string `"LockKeeperMasterKey_v1"` is stored as a plaintext string constant in bytecode.
3. **Database Schema Exposure:** Entity names, table names, and column names are fully preserved.

---

### 2.3 Build Script Review (`scripts/android_build.ps1`)
- The script automates:
  1. Stopping gradle daemons.
  2. Running `flutter pub get`.
  3. Running `flutter build apk --$Variant -v`.
  4. Verifying artifact existence and minimum size (>1MB).
  5. If release, copies APK to root `LockKeeper-v1.0.0-release.apk` and updates `checksums.txt`.
- **Defect:** Storing generated binary APKs in git tracking bloats the repository and blurs the boundary between source code and compiled binaries.

---

### 2.4 Debug vs Release Behavioral Parity
- **JVM Fallback Key in Unit Tests:** In `CredentialStore.kt:82-92`:
  When running on JVM (unit tests), `getOrCreateMasterKey()` catches the missing `AndroidKeyStore` provider and falls back to a software AES key (`getJvmFallbackKey()`).
  On real Android devices, it enforces `AndroidKeyStore`.
  This means unit tests never test real KeyStore hardware backing.
- **FLAG_SECURE Dynamic Behavior:**
  `MainActivity.kt:40-46` clears `FLAG_SECURE` if `onboardingComplete == false`.
  In release builds, screenshots are allowed during onboarding and blocked once onboarding is marked complete.

---

## 3. Invariant Evaluation

| Invariant | Description | Result | Evidence |
|---|---|---|---|
| **INV-REL-1** | Release builds must obfuscate security decision logic | **FAIL** | `isMinifyEnabled = false` in `build.gradle.kts`. |
| **INV-REL-2** | Diagnostic debug logs must be stripped in release | **FAIL** | `Log.d` active in `LockKeeperAccessibilityService.kt`. |
| **INV-REL-3** | Release signing must not fall back to debug keys silently | **FAIL** | `build.gradle.kts:53` defaults to debug signing if key file missing. |

---

## 4. Auditor Conclusion

The release engineering configuration exposes the entire anti-tamper logic to trivial reverse-engineering by disabling R8/ProGuard. Furthermore, committing large binary APKs to git violates repository hygiene, and debug logs leak sensitive package interactions to logcat.
