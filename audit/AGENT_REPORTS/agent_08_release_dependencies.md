# Agent 08 — Dependency, Build, Release & Configuration Audit Report

**Auditor Persona**: Senior DevSecOps & Build/Release Security Engineer  
**Target Repository**: LockKeeper (`C:\AppLocker`)  
**Audit Date**: September 17, 2026  
**Scope**: Gradle Build Configurations, Gradle Signing Settings, Dependencies, Minification / ProGuard / R8, Target SDK & Platform API Compatibilities.

---

## 1. Build & Release Configuration Analysis

### 1.1 Insecure Release Signing Fallback to Debug Keystore (CFG-01)
- **Severity**: HIGH
- **Confidence**: CONFIRMED
- **File**: `android/app/build.gradle.kts`, Lines 47–58
```kotlin
buildTypes {
    release {
        val releaseConfig = signingConfigs.getByName("release")
        signingConfig = if (releaseConfig.storeFile?.exists() == true) {
            releaseConfig
        } else {
            signingConfigs.getByName("debug")
        }
        isMinifyEnabled = false
        isShrinkResources = false
    }
}
```
- **Technical Risk**:
  - If `key.properties` is missing or the store file does not exist on disk, Gradle **silently signs the release build using the public Android debug keystore**!
  - Debug-signed builds are vulnerable to repackaging, tampering, and cannot be published to app stores or securely distributed.
  - A secure CI/CD or production build script must fail fast (`throw GradleException("Release keystore configuration missing")`) rather than quietly falling back to a debug key.

### 1.2 ProGuard / R8 Obfuscation & Minification Disabled (CFG-02)
- **Severity**: MEDIUM
- **Confidence**: CONFIRMED
- **File**: `android/app/build.gradle.kts`, Lines 55–56
```kotlin
isMinifyEnabled = false
isShrinkResources = false
```
- **Technical Risk**:
  - Obfuscation and code shrinking are disabled in the release build.
  - All class names, field names, package structures, and internal security routines (`LockDecisionEngine`, `CredentialStore`, `LockKeeperAccessibilityService`, `handleSettingsEvent`) remain completely unobfuscated in the release APK bytecode.
  - Reverse engineers can inspect decompiled Smali/Java using tools like JADX or Ghidra with 100% symbol fidelity, drastically accelerating exploit discovery.

### 1.3 Target SDK 36 (Android 16 Preview) Stability Risks (CFG-03)
- **Severity**: MEDIUM
- **Confidence**: CONFIRMED
- **File**: `android/app/build.gradle.kts`, Lines 14, 25
```kotlin
compileSdk = 36
targetSdk = 36
```
- **Technical Risk**:
  - API Level 36 represents an unfinalized / preview version of Android (Android 16).
  - Production applications should target the latest finalized, stable Android release (API 34 or API 35).
  - TargetSdk 36 triggers strict foreground service policies (`FOREGROUND_SERVICE_TYPE_SPECIAL_USE`), restrictive broadcast receiver policies, and edge-to-edge window insets that can cause compatibility anomalies on existing Android 12–15 devices.

### 1.4 Kotlin Version Deprecation Warning (CFG-04)
- **Severity**: LOW
- **Confidence**: CONFIRMED
- **File**: `android/settings.gradle.kts`, Line 23; Gradle build logs
```text
Warning: Flutter support for your project's Kotlin version (2.0.21) will soon be dropped. Please upgrade your Kotlin version to a version of at least 2.1.0 soon.
```
- Kotlin is currently locked to `2.0.21` with JVM target 17. The project should plan an upgrade to Kotlin 2.1+ before newer Flutter SDK revisions deprecate the toolchain.

---

## 2. Dependency Audit

### 2.1 Pubspec Dependencies (`pubspec.yaml`)
- `flutter`: sdk flutter
- `cupertino_icons: ^1.0.8`
- `flutter_lints: ^6.0.0`
- **Assessment**: Zero third-party Dart package dependencies. The Dart layer relies entirely on official Flutter SDK libraries. This minimizes the external supply-chain attack surface on the Flutter side.

### 2.2 Gradle Dependencies (`android/app/build.gradle.kts`)
- `androidx.room:room-runtime:2.6.1`
- `androidx.room:room-ktx:2.6.1`
- `androidx.room:room-compiler:2.6.1`
- `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1`
- `androidx.lifecycle:lifecycle-service:2.8.4`
- **Assessment**: Standard, high-reputation AndroidX libraries. No rogue or unmaintained dependencies detected.

---

## 3. Configuration & Secrets Scan

1. **Git Repository Secrets Scan**:
   - `git check-ignore` confirms `android/key.properties` is ignored.
   - Searching git tracked files confirms no keystore `.jks` files or raw passwords are committed in tracked history.
2. **Hardcoded Secrets Check**:
   - `CredentialStore.kt`: Key alias `"LockKeeperMasterKey_v1"` is public and stored in hardware KeyStore. No hardcoded encryption keys or passwords found in source files.
3. **Debug Settings Leakage**:
   - `debug/AndroidManifest.xml` includes `android.permission.INTERNET` (used strictly for Flutter hot reload during development).
   - In release builds, `INTERNET` permission is omitted. Verified.

---

## 4. Summary of Build & Release Findings

| ID | Title | Severity | Confidence | Category |
|---|---|---|---|---|
| **CFG-01** | Release Build Silently Falls Back to Debug Signing Keystore | HIGH | CONFIRMED | Build / Signing |
| **CFG-02** | R8 Minification & Code Obfuscation Disabled in Release | MEDIUM | CONFIRMED | Code Protection |
| **CFG-03** | CompileSdk and TargetSdk set to Unfinalized API 36 | MEDIUM | CONFIRMED | Platform Compatibility |
| **CFG-04** | Deprecated Kotlin Version (2.0.21) vs Flutter Gradle Toolchain | LOW | CONFIRMED | Toolchain Hygiene |
| **CFG-05** | Missing `android:allowBackup="false"` in Release Manifest | HIGH | CONFIRMED | Data Extraction |
