# LockKeeper Build, Dependency & Release Configuration Audit Report

**Audit Date**: September 17, 2026  
**Auditor**: Lead Security & Software Quality Auditor  
**Classification**: Highly Confidential / Read-Only Audit  
**Scope**: `build.gradle.kts`, `pubspec.yaml`, `AndroidManifest.xml`, Signing Configurations, ProGuard/R8, and Third-Party Dependencies

---

## Executive Overview of Release & Build Hygiene

A mobile application's operational security is strictly bound to its build pipeline and release configuration. Even the most robust cryptographic architecture can be neutralized if the application binary is signed with public debug keys, compiled without code obfuscation, packaged with unvetted preview SDKs, or bundled with vulnerable dependencies.

The static audit of LockKeeper's build pipeline (`android/app/build.gradle.kts`, `android/build.gradle.kts`, `pubspec.yaml`, `pubspec.lock`) revealed significant release risks that prevent safe production deployment.

---

## Detailed Build & Release Findings Dossiers

---

### SEC-16 — Release Build Insecurely Falls Back to Android Debug Keystore Signing

- **Finding ID**: SEC-16
- **Severity**: HIGH
- **Confidence**: CONFIRMED
- **Category**: Build / Release Security / Signing Integrity
- **Affected File(s)**: `android/app/build.gradle.kts` (Lines 48–68)
- **Relevant Block**: `android.buildTypes.getByName("release")`

#### Technical Description
In `android/app/build.gradle.kts`, the signing configuration for the production release build contains a fallback block:
```kotlin
val keystorePropertiesFile = rootProject.file("key.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

signingConfigs {
    create("release") {
        if (keystorePropertiesFile.exists()) {
            keyAlias = keystoreProperties["keyAlias"] as String
            keyPassword = keystoreProperties["keyPassword"] as String
            storeFile = file(keystoreProperties["storeFile"] as String)
            storePassword = keystoreProperties["storePassword"] as String
        } else {
            // Insecure fallback to debug signing in release!
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

buildTypes {
    getByName("release") {
        signingConfig = signingConfigs.getByName("release")
        // ...
    }
}
```
If `key.properties` is missing from the build environment (standard behavior in clean CI/CD checkouts, developer machines, or automated release runners), Gradle **silently signs the release APK using the well-known Android debug keystore** (`~/.android/debug.keystore`).

#### Root Cause
Permissive fallback logic designed for developer convenience that undermines release integrity.

#### Attack/User Scenario & Exploit Path
1. CI/CD pipeline builds a release APK without `key.properties` configured.
2. Gradle builds the APK without error and signs it with the public debug keystore (`CN=Android Debug,O=Android,C=US`).
3. The APK is distributed to users or sideloaded.
4. Because the debug keystore private key is publicly available in the Android SDK, any attacker can sign a malicious update package with the exact same key.
5. Android OS package manager validates the update signature, treats the attacker's malware as a legitimate app update, and replaces LockKeeper, preserving all granted permissions (Accessibility, Device Admin).

#### Impact
Total application takeover, malicious update spoofing, and privilege hijacking.

#### Existing Mitigation
None.

#### Why Mitigation Is Insufficient
Build scripts must fail with a fatal error if production signing credentials are missing, never fall back silently to debug keys.

#### Recommended Remediation Direction
Replace the silent fallback with an explicit build failure:
```kotlin
if (!keystorePropertiesFile.exists()) {
    throw GradleException("Production release build aborted: 'key.properties' not found. Silent debug fallback is prohibited.")
}
```

---

### SEC-27 — R8 Minification and Code Obfuscation Disabled in Release Build

- **Finding ID**: SEC-27
- **Severity**: MEDIUM
- **Confidence**: CONFIRMED
- **Category**: Code Obfuscation / Reverse Engineering
- **Affected File(s)**: `android/app/build.gradle.kts` (Lines 62–75)
- **Relevant Block**: `buildTypes.getByName("release")`

#### Technical Description
In `android/app/build.gradle.kts`, the release build configuration explicitly disables R8 code shrinking and resource minification:
```kotlin
buildTypes {
    getByName("release") {
        isMinifyEnabled = false
        isShrinkResources = false
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )
    }
}
```

#### Root Cause
Disabling code shrinking to avoid troubleshooting R8 keep rules for Room or Flutter plugins.

#### Attack/User Scenario & Exploit Path
1. Attacker downloads LockKeeper APK from device or public distribution.
2. Attacker runs standard decompilation tools (`jadx-gui`, `apktool`).
3. Because minification is disabled, all Kotlin classes, methods, variable names, and string literals are decompiled in pristine, human-readable clarity (`LockKeeperAccessibilityService`, `evaluateSettingsProtection`, `destructiveKeywords`, `CredentialStore`, `masterKey`).
4. Attacker instantly locates anti-tamper heuristics (SEC-01, SEC-03) and drafts 100% reliable bypass exploits.

#### Impact
Drastic reduction in reverse engineering cost; instant exposure of heuristic bypasses and internal security logic.

#### Existing Mitigation
None.

#### Why Mitigation Is Insufficient
Standard DEX bytecode without obfuscation is functionally equivalent to open source.

#### Recommended Remediation Direction
Enable R8 shrinking in release mode:
```kotlin
isMinifyEnabled = true
isShrinkResources = true
```
Ensure proper keep rules are maintained in `proguard-rules.pro` for Room entities and Flutter engine platform bindings.

---

### SEC-28 — TargetSdk & CompileSdk Set to Unfinalized Android 16 Preview (API 36)

- **Finding ID**: SEC-28
- **Severity**: MEDIUM
- **Confidence**: CONFIRMED
- **Category**: Platform Compatibility / Stability
- **Affected File(s)**: `android/app/build.gradle.kts` (Lines 24–32)
- **Relevant Block**: `android.compileSdk` & `android.defaultConfig.targetSdk`

#### Technical Description
In `android/app/build.gradle.kts`, the SDK versions are configured as:
```kotlin
compileSdk = 36
defaultConfig {
    applicationId = "com.lockkeeper.app"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "1.0.0"
}
```
API 36 represents an unfinalized, preview development platform of Android (Android 16 Developer Preview). Google Play Store enforces strict guidelines on targeting finalized, publicly released SDKs (currently API 34/35 for new apps).

#### Root Cause
Premature configuration of preview SDK levels before platform stability and Google Play submission windows.

#### Failure Scenario
1. Google Play Console rejects the release APK because targetSdk 36 is not permitted for general production distribution.
2. Native system behaviors, background execution limits, and accessibility permissions in API 36 are subject to breaking changes prior to general availability.
3. Unanticipated runtime exceptions occur on existing Android 14/15 devices due to unresolved framework stubs in the API 36 compilation toolchain.

#### Impact
Inability to publish to Google Play Store and potential runtime instability across production Android devices.

#### Existing Mitigation
None.

#### Why Mitigation Is Insufficient
Production releases must target finalized platform releases with stable API contracts.

#### Recommended Remediation Direction
Downgrade `compileSdk` and `targetSdk` to stable **API 35** (Android 15) or **API 34** (Android 14) until API 36 reaches official platform stability.

---

## Dependency Hygiene & Vulnerability Assessment

### Native Android Dependencies (`android/app/build.gradle.kts`)
- `androidx.core:core-ktx:1.12.0` (Stable)
- `androidx.appcompat:appcompat:1.6.1` (Stable)
- `com.google.android.material:material:1.11.0` (Stable)
- `androidx.room:room-runtime:2.6.1` (Stable)
- `androidx.room:room-ktx:2.6.1` (Stable)
- `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3` (Stable)

### Flutter Dependencies (`pubspec.yaml` & `pubspec.lock`)
- `flutter`: SDK version `>=3.3.0 <4.0.0`
- `cupertino_icons: ^1.0.8` (Clean)
- No vulnerable third-party plugins detected in `pubspec.yaml`. The core logic relies exclusively on custom platform channels rather than unmaintained community locker libraries.

### Security Deficiencies in Dependencies & Config
1. **Missing Network Security Configuration**:
   `AndroidManifest.xml` lacks `android:networkSecurityConfig`. While LockKeeper does not currently initiate network requests, the absence of an explicit cleartext traffic prohibition (`android:usesCleartextTraffic="false"`) leaves future network additions vulnerable to cleartext interception.
2. **Missing `android:dataExtractionRules`**:
   The app omits Android 12+ data extraction configuration, leaving Keystore-dependent ciphertexts vulnerable to cloud backup corruption (SEC-17).
