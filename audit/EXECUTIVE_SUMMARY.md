# LockKeeper Security & Quality Audit

**Audit Date**: September 17, 2026  
**Lead Auditor**: Lead Security & Software Quality Auditor  
**Classification**: Forensic Codebase Audit / Production-Readiness Assessment  
**Working Repository**: `C:\AppLocker`

---

## Audit Scope

The scope of this audit encompassed a comprehensive, read-only forensic inspection of the entire LockKeeper codebase. The investigation covered all Dart/Flutter source files (`lib/`), Flutter and native unit/widget test suites (`test/`, `android/app/src/test/`), native Android Kotlin and Java source code (`android/app/src/main/kotlin/`), Android resource configurations and XML manifests (`android/app/src/main/res/`, `AndroidManifest.xml`), Gradle build scripts (`build.gradle.kts`), dependency configurations (`pubspec.yaml`, `pubspec.lock`), and architectural security models.

In strict compliance with audit protocols, zero source code, dependencies, build configurations, or tests were modified during this assessment. No emulators, physical test devices, or live instrumentation tools were used; all conclusions were established through static code analysis, control-flow tracing, data-flow verification, dependency review, and threat modeling.

---

## Codebase Size / Technology

- **Primary UI Framework**: Flutter 3.x (Dart 3.x)
- **Native Platform Layer**: Android Native (Kotlin 1.9.x, Android SDK API 26–36)
- **Local Persistence**: AndroidX Room 2.6.1 (SQLite) with Kotlin Coroutines & Flow
- **Cryptography Engine**: Hardware-backed Android Keystore (`AndroidKeyStore`), AES-256-GCM, PBKDF2-HMAC-SHA256 (65,536 iterations), SecureRandom
- **Inter-Process Communication**: Asynchronous Flutter `MethodChannel` (`com.lockkeeper.app/bridge`)
- **System Integration Surfaces**:
  - Android Accessibility Service (`LockKeeperAccessibilityService`)
  - Device Administrator Receiver (`LockKeeperDeviceAdminReceiver`)
  - Foreground Polling Service (`LockKeeperForegroundService`)
  - System Alert Overlay (`WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`)
  - Usage Statistics Manager (`UsageStatsManager`)
  - Boot Receiver (`android.intent.action.BOOT_COMPLETED`)
- **Codebase Volume**:
  - Dart Files: 9 files (~1,850 LOC)
  - Kotlin Files: 12 files (~2,100 LOC)
  - XML & Config Files: 14 files (~650 LOC)
  - Test Files: 4 files (~450 LOC)

---

## Agents Used

The audit was executed via a 12-specialist parallel agent architecture:
1. **Agent 1 — Security Architecture Auditor**: Threat modeling, trust boundaries, privilege separation, and credential lifecycle.
2. **Agent 2 — Android Native Security Auditor**: Native components, intent filters, receiver lifecycles, WindowManager flags, and Keystore crypto.
3. **Agent 3 — Flutter Application Security & Logic Auditor**: Dart UI logic, client state machines, route guards, and platform channel bindings.
4. **Agent 4 — Data, Database & Concurrency Auditor**: Room DAOs, concurrency races, TOCTOU vulnerabilities, and ACID transaction boundaries.
5. **Agent 5 — LockKeeper Security-Bypass Specialist**: Adversarial bypass analysis across 20 distinct physical and logical attack paths.
6. **Agent 6 — UI/UX & Accessibility Auditor**: Design integrity, deceptive status indicators, terminology consistency, and WCAG 2.2 accessibility compliance.
7. **Agent 7 — Testing, QA & Failure-Mode Auditor**: Coverage-by-risk analysis, mock fidelity traps, and lifecycle failure testing.
8. **Agent 8 — Dependency, Build & Release Auditor**: Gradle build configurations, signing fallback vulnerabilities, R8 minification, and SDK targets.
9. **Agent 9 — Privacy & Data Exposure Auditor**: Logging leakage, clipboard hygiene, task snapshot thumbnails, and cloud backup risks.
10. **Agent 10 — Reliability, Performance & Resource Auditor**: ANR risks, main thread crypto freezes, 400ms polling battery drain, and memory leaks.
11. **Agent 11 — Codebase-Wide Static Reviewer**: Global scan for dead code, hardcoded strings, unhandled exceptions, and magic constants.
12. **Agent 12 — Adversarial Review & Finding Validator**: Cross-examination of all findings, false-positive elimination, and proof validation.

---

## Critical Findings (5)

1. **SEC-01 — Hardcoded English String Matching in Accessibility Allows 100% Anti-Tamper Bypass**:
   Anti-tamper heuristics in `LockKeeperAccessibilityService.kt` search exclusively for lowercase English words (`"uninstall"`, `"force stop"`, `"clear data"`). Switching the device language to Spanish, French, German, or Hindi completely disables all anti-tamper overlays, permitting instant uninstallation.
2. **SEC-02 — Device Admin Architectural Fallacy: Ordinary Admin Does Not Provide Device Owner Protection**:
   The project assumes standard Device Administration prevents app removal. In reality, Android permits any user to deactivate an ordinary Device Administrator at any time via a system dialog. Without Device Owner status, uninstall defense relies entirely on vulnerable accessibility heuristics.
3. **SEC-03 — Anti-Tamper Scope Limited to `com.android.settings`, Ignoring Launchers & OEM Centers**:
   Accessibility protection is strictly gated behind `event.packageName == "com.android.settings"`. Drag-and-drop uninstallation from launchers and package installer dialogs (`com.android.packageinstaller`) bypasses the accessibility monitor entirely.
4. **SEC-04 — SelfLockGateScreen Hardcoded Auto-Submit at 4 Digits Permanently Locks Out 5–8 Digit PIN Users**:
   Onboarding permits 4–8 digit PINs, but `SelfLockGateScreen.dart` auto-submits upon receiving the 4th digit. Any user who sets a 5, 6, 7, or 8-digit PIN is permanently barred from accessing the app, triggering a 300-second lockout after 5 failed attempts.
5. **SEC-05 — Unrestricted Clear-Data in Settings Wipes Keystore-Backed Storage & Protection**:
   Navigating to `Settings > Storage > Apps > LockKeeper > Clear storage` deletes the Room database and SharedPreferences without triggering keyword heuristics, reverting the app to factory-unprotected state.

---

## High Findings (14)

- **SEC-06**: Flutter root state desynchronization disables self-lock gate on initial launch session.
- **SEC-07**: Unthrottled `verifyPin` and `verifyAdminPassword` platform channel methods allow automated dictionary brute-forcing.
- **SEC-08**: Complete absence of attempt limits or lockout timers on Admin Password entry.
- **SEC-09**: Native overlay windows omit `FLAG_SECURE`, allowing screen recording and casting spyware to capture PINs and passwords.
- **SEC-10**: Non-atomic read-modify-write in `handleFailedPinAttempt()` allows race-condition evasion of PIN lockout.
- **SEC-11**: `BootReceiver.kt` launches coroutines on `Dispatchers.IO` without `goAsync()`, risking process termination during startup.
- **SEC-12**: `LockKeeperForegroundService.kt` fallback polling explicitly ignores `com.android.settings`, leaving zero tamper protection when Accessibility is dead.
- **SEC-13**: Unbounded in-memory session cache in `LockDecisionEngine` fails to clear unlock tokens on device screen lock.
- **SEC-14**: Synchronous 65,536-iteration PBKDF2 execution on the main Looper thread causes 80–250ms UI freezes and ANR risks.
- **SEC-15**: Continuous 400ms `UsageStatsManager` polling in fallback mode prevents CPU sleep, causing severe battery drain and thermal throttling.
- **SEC-16**: Release build configuration silently falls back to signing with the public Android debug keystore if `key.properties` is missing.
- **SEC-17**: `allowBackup="true"` allows cloud backup of Keystore-encrypted blobs, causing fatal crashes and permanent lockouts upon cloud restore.
- **SEC-18**: Database configured with `fallbackToDestructiveMigration()` and disabled schema export, guaranteeing silent data loss on updates.
- **SEC-19**: Top-level route guard in `main.dart` catches exceptions and fails open, bypassing self-lock on channel errors.

---

## Medium Findings (9)

- **SEC-20**: Production debug logging of navigation classes and settings events via `Log.d` in release builds.
- **SEC-21**: Target app UI is exposed for 150–300ms during overlay dismissal when tapping Back or Cancel.
- **SEC-22**: Android Recents / Overview switcher renders unprotected task snapshot thumbnails of locked apps.
- **SEC-23**: Systemic silent exception swallowing across 24 platform bridge methods defaults to fail-open behavior.
- **SEC-24**: Accessibility permission check relies on substring matching and does not verify live service connectivity.
- **SEC-25**: Static 4-dot UI indicator in `SelfLockGateScreen` misrepresents dynamic 4–8 digit PIN configurations.
- **SEC-26**: Custom numeric keypads lack WCAG semantic annotations and TalkBack content descriptions.
- **SEC-27**: R8 code shrinking and bytecode obfuscation are explicitly disabled in the release build.
- **SEC-28**: Build scripts target unfinalized Android 16 Preview (API 36), preventing Google Play submission.

---

## Low Findings (4)

- **SEC-29**: Admin grace window uses user-controllable wall-clock time (`System.currentTimeMillis()`), enabling indefinite extension.
- **SEC-30**: Onboarding setup wizard displays hardcoded "Active" green badges without validating actual permission grants.
- **SEC-31**: Plaintext PINs and passwords are held in immutable heap String objects, preventing memory wiping.
- **SEC-32**: Vestigial, unused `schemaVersion` column in `AppSettingsEntity.kt`.

---

## Security Architecture Risks

LockKeeper's core architectural vulnerability is its reliance on non-privileged heuristics (Accessibility node scraping and window overlays) to enforce security guarantees that Android reserves for **Device Owner (Work Managed)** installations. Because standard third-party applications cannot override the Android OS user experience or block system settings at the kernel/framework level, any divergence in UI layout, locale, package name, or process lifecycle instantly dismantles the security perimeter.

---

## Major Functional Bugs

1. **Denial-of-Service PIN Lockout**: Users setting a 5–8 digit PIN are permanently locked out of LockKeeper due to premature 4-digit auto-submission.
2. **First-Launch Guard Failure**: Onboarding completion does not update root widget state, allowing immediate self-lock bypass on initial installation.
3. **Database Erasure on Update**: Room's `fallbackToDestructiveMigration()` will silently wipe all locked apps and user passwords upon the first schema update.

---

## Major UI/UX Problems

1. **Deceptive Security Indicators**: The dashboard displays an active green "System Protected" shield even when the background accessibility service is completely dead.
2. **Inconsistent Nomenclature**: Confusing interchanging of "Master Password", "Admin Password", "User PIN", and "Passcode".
3. **Accessibility Incompatibility**: TalkBack users cannot interact with the custom numeric lock screen due to missing semantic labels.

---

## Reliability Risks

1. **Battery Drain**: 400ms fixed-interval polling in foreground service fallback mode drains battery and triggers OEM process termination.
2. **Boot Failure**: Failure to call `goAsync()` in `BootReceiver` risks silent startup drops on device restart.
3. **Main-Thread ANRs**: Cryptographic key derivation (PBKDF2) running synchronously on the UI thread causes noticeable stutter and risks OS ANR termination.

---

## Testing Gaps

1. **The Mock Trap**: 100% automated test pass rate masks critical bugs because tests only exercise hardcoded "1234" PIN happy paths.
2. **Zero Coverage for Core Defense Layer**: 0% automated test coverage across `LockKeeperAccessibilityService`, `OverlayManager`, `LockKeeperDeviceAdminReceiver`, `BootReceiver`, and `LockKeeperForegroundService`.
3. **Absence of Concurrency & Lifecycle Tests**: No automated verification of race conditions, process death, or cloud backup restoration.

---

## Release Risks

1. **Debug Keystore Distribution**: Missing release signing properties cause Gradle to sign production APKs with the well-known public Android debug key, allowing arbitrary malicious update replacement.
2. **Unobfuscated DEX Bytecode**: Disabled R8 shrinking allows trivial reverse engineering with `jadx`.
3. **Unfinalized API 36 Target**: Using Android 16 Developer Preview prevents publication on the Google Play Store.

---

## Privacy Risks

1. **Screen Recording Sniffing**: Missing `FLAG_SECURE` allows third-party screen recorders and media projection tools to capture PINs and passwords.
2. **Recents Snapshot Leaks**: Cached task snapshots expose sensitive data in the Android Overview switcher.
3. **Debug Logcat Exposure**: Navigation paths and settings interactions are logged via `Log.d` in production release builds.

---

## Most Important Unresolved Questions

1. **Target Operating Environment**: Is LockKeeper intended for consumer voluntary self-control, parental monitoring, or enterprise kiosk security? (Enterprise security requires Device Owner provisioning).
2. **OEM Compatibility Strategy**: How does LockKeeper intend to survive aggressive background process termination on Xiaomi (MIUI), Samsung (OneUI), and Huawei without OEM-specific battery whitelisting?
3. **Grace Window Security Boundary**: Should app sessions persist across device screen lock (`ACTION_SCREEN_OFF`), or should screen lock unconditionally revoke all temporary access tokens?

---

## Overall Readiness Assessment

**Status: NOT PRODUCTION READY**

Under no circumstances should LockKeeper be deployed to production in its current state. 

While individual cryptographic primitives (hardware Keystore and PBKDF2) demonstrate good design intent, the application suffers from multiple critical security bypasses (language change bypass, launcher uninstall bypass, clear data wipe), catastrophic functional bugs (permanent PIN lockout for 5–8 digit PINs, first-launch self-lock failure), high-risk release configuration flaws (silent debug keystore signing, unobfuscated bytecode), and severe test coverage deficits across its entire native Android infrastructure.

A comprehensive remediation and architectural stabilization phase is mandatory before any release candidate is prepared.
