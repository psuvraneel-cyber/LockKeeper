# Agent 09 — Privacy & Data Exposure Audit Report

**Auditor Persona**: Privacy Engineer & Data Protection Auditor (GDPR / Android Privacy)  
**Target Repository**: LockKeeper (`C:\AppLocker`)  
**Audit Date**: September 17, 2026  
**Scope**: Data Storage, Logging, Sensitive-Data Exposure, Auto-Backup Configuration, Memory Redaction, Accessibility Content Scraping, and Third-Party Leakage.

---

## 1. Privacy Posture & Data Architecture

LockKeeper adopts a privacy-centric design goal:
- **No Cloud Synchronization**: There are no remote telemetry SDKs (e.g. Firebase, Crashlytics, Sentry, Mixpanel) integrated into the codebase.
- **Zero Internet Permission in Production**: Main manifest declares no network permissions.
- **Local Keystore Encryption**: Stored credential hashes are encrypted with an AES-256-GCM master key generated in `AndroidKeyStore`.

Despite this strong local foundation, static analysis reveals multiple privacy risks and data leakage vectors:
1. Production release debug logging of activity navigation and package names via `android.util.Log.d`.
2. Missing Android backup exclusion, creating cloud-backup leakage of encrypted blobs.
3. Unrestricted accessibility event inspection (`canRetrieveWindowContent="true"`).

---

## 2. In-Depth Privacy & Data Exposure Findings

### 2.1 Production Debug Logging of User Navigation (PRIV-01)
- **Severity**: MEDIUM
- **Confidence**: CONFIRMED
- **File**: `android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperAccessibilityService.kt`, Lines 89, 135, 140, 147, 150
```kotlin
android.util.Log.d("LockKeeperA11y", "Settings event: class=$className, shouldProtect=${repository.shouldProtectSettings()}")
...
android.util.Log.d("LockKeeperA11y", "handleSettingsEvent: sensitive=$isSensitiveScreen, class=$className, rootNode=${rootNode != null}")
...
android.util.Log.d("LockKeeperA11y", "Showing admin overlay for settings protection")
```
- **Technical Vulnerability**:
  - `android.util.Log.d` is called unconditionally across the accessibility service.
  - Because ProGuard / R8 minification is disabled in `build.gradle.kts` (`isMinifyEnabled = false`), these log statements are retained in the compiled release APK.
  - Any application with `READ_LOGS` on rooted/enterprise devices, or any USB ADB connection, can passively observe user navigation history, settings activities opened, and app inspection timestamps.
  - **Remediation Direction**: Strip `Log.d` statements in release builds using Timber or R8 `assumenosideeffects`.

### 2.2 Unrestricted Accessibility Window Scraping (PRIV-02)
- **Severity**: MEDIUM
- **Confidence**: CONFIRMED
- **File**: `android/app/src/main/res/xml/accessibility_service_config.xml`, Line 7; `LockKeeperAccessibilityService.kt`, Lines 222–235
```xml
android:canRetrieveWindowContent="true"
```
and:
```kotlin
private fun searchNodeForText(node: AccessibilityNodeInfo, query: String): Boolean {
    val text = node.text?.toString()?.lowercase() ?: ""
    val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
    ...
    for (i in 0 until node.childCount) {
        val child = node.getChild(i) ?: continue
        val found = searchNodeForText(child, query)
        if (found) return true
    }
    return false
}
```
- **Technical Vulnerability**:
  - LockKeeper requests `canRetrieveWindowContent="true"`.
  - When `com.android.settings` is open, `searchNodeForText` traverses the entire view hierarchy, reading `node.text` and `node.contentDescription` of all visible elements into memory.
  - While this is currently discarded after substring evaluation, retaining full window content retrieval permission exposes the app to heightened scrutiny under Google Play's accessibility policies.

### 2.3 Cloud Auto-Backup of Keystore-Dependent Storage (PRIV-03)
- **Severity**: HIGH
- **Confidence**: CONFIRMED
- **File**: `android/app/src/main/AndroidManifest.xml`, Line 23
- **Technical Vulnerability**:
  - In Android 12+, Google Cloud Backup automatically backs up app private storage (`shared_prefs/` and `databases/`) unless `android:allowBackup="false"` or custom `android:dataExtractionRules` are configured.
  - Backing up `lockkeeper_credentials.xml` (which contains encrypted credential blobs) to Google Drive exposes credential ciphertext to cloud storage.
  - More critically, hardware keystore keys are device-bound and cannot be backed up. Restoring this backup onto a new device renders the stored credentials indecipherable, breaking login permanently.

### 2.4 In-Memory Plaintext PIN Exposure in String Objects (PRIV-04)
- **Severity**: LOW
- **Confidence**: CONFIRMED
- **File**: `lib/ui/screens/self_lock_gate_screen.dart`, Lines 17, 99; `android/app/src/main/kotlin/com/lockkeeper/app/bridge/PlatformChannelHandler.kt`, Lines 100, 105
```dart
final StringBuffer _pinBuffer = StringBuffer();
...
final enteredPin = _pinBuffer.toString();
```
and:
```kotlin
val pin = call.argument<String>("pin") ?: ""
val success = repository.credentialStore.setPin(pin)
```
- **Technical Vulnerability**:
  - Passwords and PINs are passed as immutable `java.lang.String` and Dart `String` objects rather than mutable `CharArray` or `List<int>`.
  - Immutable strings remain in heap memory until garbage collected, making credentials discoverable via memory dump analysis (e.g. heap dumps via Android Studio profiler or root RAM acquisition).

---

## 3. Summary of Privacy Findings

| ID | Title | Severity | Confidence | Category |
|---|---|---|---|---|
| **PRIV-01** | Production Release Debug Logging of Settings & Navigation | MEDIUM | CONFIRMED | Information Disclosure |
| **PRIV-02** | Full Window Content Scraping in Settings | MEDIUM | CONFIRMED | Excessive Privilege / Data Ingestion |
| **PRIV-03** | Cloud Auto-Backup of Hardware Keystore Blobs | HIGH | CONFIRMED | Backup & Storage Security |
| **PRIV-04** | Plaintext Credentials in Immutable Heap Strings | LOW | CONFIRMED | Memory Hygiene |
