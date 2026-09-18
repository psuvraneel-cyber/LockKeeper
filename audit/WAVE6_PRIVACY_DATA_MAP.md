# LockKeeper — Wave 6: Privacy & Data Disclosure Map

## 1. Executive Summary

LockKeeper is architected as an **offline-first, zero-network-egress** local security utility. A comprehensive code-level audit of the Android native layer (`android/app/src/main/kotlin`) and Flutter Dart UI layer (`lib/`) demonstrates:
1. **Zero Network Transmission:** Release builds completely omit `android.permission.INTERNET`. Network sockets cannot be created by the OS sandbox.
2. **Zero Third-Party Telemetry / Trackers:** No analytics, advertising, or remote tracking SDKs (e.g., Firebase, Facebook, Sentry, Mixpanel) are present.
3. **Hardware-Backed Encryption:** Credentials (PIN and Admin Password) are hashed with PBKDF2WithHmacSHA256 (65,536 iterations) and encrypted at rest with hardware-backed AES-256-GCM via `AndroidKeyStore`.
4. **Zero Raw Text / Keystroke Capture:** Accessibility service strictly inspects `TYPE_WINDOW_STATE_CHANGED` and `TYPE_WINDOW_CONTENT_CHANGED` for target package names and settings tamper buttons. No user keystrokes, personal messages, or form inputs are parsed, stored, or logged.
5. **No Cloud or ADB Backup:** `android:allowBackup="false"` is enforced in `AndroidManifest.xml`.

---

## 2. End-to-End Data Flow Map

| Data Element | Input Source | Processing Layer | Storage Mechanism | IPC Surface | Logging Output | Backup Status | Deletion Trigger |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **PIN** | Flutter UI (Numeric Keypad) | Transferred in memory to native Kotlin; salted & hashed via `PBKDF2WithHmacSHA256` (65,536 iterations); encrypted with AES-256-GCM | Encrypted payload (`v1:65536:salt:hash`) stored in private SharedPreferences (`lockkeeper_credentials`) | In-process Flutter `MethodChannel` (`com.lockkeeper.app/security`); zero broadcast or Intent exposure | Zero logging. No raw PIN, salt, or hash is ever written to Logcat | `android:allowBackup="false"` (Excluded from Google Drive & ADB backup) | Overwritten on PIN reset; destroyed on App Data Clear or App Uninstall |
| **Admin Password** | Flutter UI (Text Field) | Transferred in memory; salted & hashed via `PBKDF2WithHmacSHA256` (65,536 iterations); encrypted with AES-256-GCM | Encrypted payload stored in private SharedPreferences (`lockkeeper_credentials`) | In-process Flutter `MethodChannel`; zero broadcast or Intent exposure | Zero logging. No raw password, salt, or hash is ever written to Logcat | `android:allowBackup="false"` (Excluded from Google Drive & ADB backup) | Overwritten on Admin reset; destroyed on App Data Clear or App Uninstall |
| **Protected Package Names** | Flutter UI (App Selection List) | Queried against `PackageManager` (`getInstalledApplications`); matched in `LockKeeperAccessibilityService` | SQLite Room DB (`lockkeeper.db`, table `locked_apps`) | Internal memory querying; exposed only to Flutter UI via `MethodChannel` | Zero package logging in production flows | Excluded (`allowBackup="false"`) | Removed when user unprotects app in UI; destroyed on Clear Data or Uninstall |
| **Accessibility Event Data** | Android OS `AccessibilityEvent` | Filtered for `TYPE_WINDOW_STATE_CHANGED` and `TYPE_WINDOW_CONTENT_CHANGED`; package name extracted to trigger overlay | None (Ephemeral in memory; zero persistence) | None (Handled locally inside service process) | Diagnostic event type & source logged on tamper detection (`Tamper event detected: type=..., source=...`); zero text/view data logged | Excluded (Not persisted) | Discarded immediately after policy evaluation (within milliseconds) |
| **Usage Statistics** | Android OS `UsageStatsManager` (fallback) | Used solely to determine top foreground package when Accessibility is delayed | None (Queried on-demand; zero persistence) | None | Zero logging | Excluded (Not persisted) | Discarded immediately after foreground query |
| **Application Settings & Lockout State** | Flutter UI / Security Engines | Tracks failed attempts count, lockout timestamps, self-lock timeout, onboarding flag | SQLite Room DB (`lockkeeper.db`, table `app_settings`) | Internal to `ProtectionRepository` | Failures tracked as numeric counts; zero credential association | Excluded (`allowBackup="false"`) | Reset via Settings or destroyed on App Data Clear or Uninstall |
| **Device Admin Telemetry** | Android OS `DevicePolicyManager` | Evaluates whether Device Admin is currently active to determine self-protection state | None (Queried dynamically via `dpm.isAdminActive()`) | None | Logcat logs admin overlay display events for tamper defense; zero device serials or identifiers | Excluded (Dynamic OS state) | N/A (Dynamic state) |
| **Crash Information** | JVM / Dart Runtime | Uncaught exceptions print standard stack traces to local process `logcat` | None (No local crash file; no remote reporting) | None | Local system logcat only (standard Android crash handler) | Excluded | Purged as OS circular logcat buffer rolls over |

---

## 3. Deep-Dive Security & Privacy Inspections

### 3.1 Credential Secrecy Verification
- **Inspection:** Inspected `CredentialStore.kt` (`KeystoreCredentialStore`).
- **Cryptographic Rigor:**
  - KDF: `PBKDF2WithHmacSHA256` with 65,536 iterations and 16-byte cryptographically secure random salt (`SecureRandom`).
  - Storage Encryption: Master key `LockKeeperMasterKey_v1` generated in hardware-backed `AndroidKeyStore` (`KeyProperties.KEY_ALGORITHM_AES`, 256-bit, `GCMParameterSpec` with 128-bit authentication tag and 12-byte random IV).
  - Memory Lifecycle: Plaintext string exists solely during the execution frame of verification/hashing.
  - Constant-Time Comparison: Stored hash comparison executes via `MessageDigest.isEqual` to eliminate timing side-channel attacks.

### 3.2 Accessibility Text / Keystroke Isolation
- **Inspection:** Inspected `LockKeeperAccessibilityService.kt`.
- **Finding:**
  - Event subscription is restricted to `eventTypes = TYPE_WINDOW_STATE_CHANGED | TYPE_WINDOW_CONTENT_CHANGED`.
  - Keyboard IME packages are specifically detected and granted immediate immunity (`overlayManager.isInputMethodPackage(packageName)` returns early without event handling).
  - Node traversal occurs exclusively inside `tamperEngine.evaluate()` when target package belongs to system management or package installer (`com.android.settings`, `com.google.android.packageinstaller`), solely inspecting view IDs and button text (e.g., "Deactivate", "Uninstall") to detect administrative tampering.
  - No text fields in user applications are ever read, inspected, or buffered.

### 3.3 Zero Leakage Across IPC / Process Boundaries
- **Inspection:** Inspected `AndroidManifest.xml` and `PlatformChannelHandler.kt`.
- **Finding:**
  - `MainActivity` has `exported="true"` solely as the launcher entry point.
  - `LockKeeperForegroundService` is explicitly `android:exported="false"`.
  - `LockKeeperAccessibilityService` is protected by system permission `android.permission.BIND_ACCESSIBILITY_SERVICE`.
  - `LockKeeperDeviceAdminReceiver` is protected by system permission `android.permission.BIND_DEVICE_ADMIN`.
  - No BroadcastReceivers accept custom external actions without permission checks.
  - No ContentProviders exist in the application.
  - All communication between the Flutter UI engine and the Android Kotlin layer occurs via internal binary messenger (`MethodChannel`). No IPC intents or system binders carry credentials.

### 3.4 Backup & Cloud Storage Verification
- **Inspection:** `android:allowBackup="false"` is explicitly configured on the `<application>` tag in `AndroidManifest.xml`.
- **Finding:**
  - Physical ADB backup (`adb backup com.lockkeeper.app`) is rejected by the Android OS package manager.
  - Google Drive Auto Backup for Apps is completely disabled.
  - Device-to-device cloud restores will NOT copy `lockkeeper_credentials.xml` or `lockkeeper.db`, preventing state desynchronization or stolen keystore master keys across different devices.

### 3.5 Logcat Sanitization Audit
- **Inspection:** Grepped all Kotlin and Dart source code for `Log.`, `android.util.Log`, `print`, and `debugPrint`.
- **Finding:**
  - Total Logcat calls in Kotlin: Exactly 3 debug calls in `LockKeeperAccessibilityService.kt`.
    1. Line 140: `Log.d("LockKeeperA11y", "Tamper event detected: type=${tamperEvent.type}, source=${tamperEvent.source}")`
    2. Line 201: `Log.d("LockKeeperA11y", "Skipping tamper protection: admin grace active")`
    3. Line 209: `Log.d("LockKeeperA11y", "Displaying admin overlay for tamper defense over $targetPackage")`
  - Total logging calls in Dart: Exactly 0.
  - Zero sensitive parameters (PIN, password, package names of protected apps, or node texts) are passed to logging methods.

---

## 4. Google Play Data Safety Declaration Guidance

For store listing submission, the Google Play Console "Data safety" section must be completed as follows based on actual code verification:

| Section | Question | Answer | Technical Verification Justification |
| :--- | :--- | :--- | :--- |
| **Data Collection** | Does your app collect or share any of the required user data types? | **No** | LockKeeper does not transmit any data off the device. All data is processed locally within the app sandbox. |
| **Data Sharing** | Is user data shared with any third party? | **No** | Zero third parties, zero network SDKs, zero internet access. |
| **Data Transfer** | Is all user data transferred over a secure connection (HTTPS)? | **N/A** | No data is transferred across any network. `android.permission.INTERNET` is absent. |
| **Data Deletion** | Do you provide a way for users to request data deletion? | **Yes** | Users can delete all credentials and application configurations directly in-app via Settings ("Reset App"), via Android Settings ("Clear Data"), or by uninstalling the application. |
| **Security Practices** | Is data encrypted in transit? | **N/A** | No data transit exists. |
| **Security Practices** | Is data encrypted at rest? | **Yes** | Sensitive authentication credentials are encrypted using hardware-backed AES-256-GCM via `AndroidKeyStore`. |

---

## 5. Verdict & Compliance Status

- **Privacy Data Handling:** `COMPLIANT`
- **Data Minimization:** `COMPLIANT` (Collects strictly the minimum data required to execute app locking and self-protection)
- **Credential Protection:** `COMPLIANT` (PBKDF2 + AES-GCM + AndroidKeyStore)
- **Data Disclosure Consistency:** `COMPLIANT` (Accurate reflection of offline-first zero-telemetry architecture)
