# LockKeeper — Wave 6: Versioning & Release Management Policy

## 1. Overview & Objectives

This document establishes the formal versioning, build provenance, and release management protocols for LockKeeper. Because LockKeeper is a security-critical Android utility operating Device Administration, Accessibility Services, and Hardware Keystore cryptography, every released binary must possess unambiguous provenance, strict monotonic progression, and complete traceability.

---

## 2. Version Identification Scheme

LockKeeper utilizes a dual-component versioning structure adhering to Semantic Versioning (SemVer 2.0.0) aligned with Android OS package requirements:

$$\text{Display Version (\texttt{versionName})} \longleftrightarrow \text{Integer Sequence (\texttt{versionCode})}$$

### 2.1 Display Version (`versionName`)
- **Format:** `MAJOR.MINOR.PATCH` (e.g., `1.0.0`)
- **Definition:**
  - **`MAJOR` (Breaking / Architectural):** Incremented when significant platform architecture changes occur, breaking database schema migrations require manual intervention, or underlying Android security paradigms change.
  - **`MINOR` (Features & Enhancements):** Incremented for backward-compatible feature additions (e.g., new app-locking profiles, UX redesigns, enhanced tamper heuristic rules).
  - **`PATCH` (Bug Fixes & Hardening):** Incremented for backward-compatible bug fixes, minor performance improvements, and non-breaking security patches.
  - **Emergency Hotfix:** If an out-of-band security fix is deployed without feature changes, `PATCH` is incremented immediately (e.g., `1.0.1`).

### 2.2 Internal Android Version Code (`versionCode`)
- **Format:** Positive 32-bit Integer ($1 \le \text{versionCode} \le 2,100,000,000$).
- **Requirement:** **Strictly Monotonically Increasing.** Android Package Manager (`PackageManager`) and Google Play reject any upgrade where `new.versionCode <= installed.versionCode`.
- **Current Baseline:** `versionCode = 1` (`1.0.0+1`).

### 2.3 Version Code Incrementing Rules
```
v1.0.0 Release Candidate 1  ->  versionName: 1.0.0,  versionCode: 1
v1.0.0 Production Release    ->  versionName: 1.0.0,  versionCode: 2
v1.0.1 Security Patch        ->  versionName: 1.0.1,  versionCode: 3
v1.1.0 Minor Feature Release ->  versionName: 1.1.0,  versionCode: 4
v2.0.0 Major Release         ->  versionName: 2.0.0,  versionCode: 5
```

---

## 3. Release Provenance & Traceability Triad

Every distributed LockKeeper artifact (AAB or APK) must be cryptographically verifiable back to its exact source tree and build environment through the **Traceability Triad**:

```
           [ Git Commit SHA & Signed Tag ]
                         ▲
                         │ Build Inputs
                         ▼
             [ Build Environment State ]
        (Flutter, AGP, Gradle, JDK, Toolchains)
                         ▲
                         │ Reproducible Compilation
                         ▼
             [ Cryptographic Fingerprint ]
         (Artifact SHA-256 + Certificate SHA-256)
```

### 3.1 Traceability Record Table (RC-1 Baseline)
| Attribute | Release Record / Verified Baseline |
| :--- | :--- |
| **Product Name** | LockKeeper |
| **Package / Application ID** | `com.lockkeeper.app` |
| **versionName** | `1.0.0` |
| **versionCode** | `1` |
| **Git Commit Reference** | `3fc54b1` (HEAD) |
| **Git Tag** | `v1.0.0-rc1` (Pending final release promotion) |
| **Flutter Version** | `3.41.6` (Channel stable, Framework revision `b7bfb5fd50`) |
| **Dart SDK** | `3.11.4` (build 3.11.4-0.0.dev) |
| **Java / JDK Version** | OpenJDK 17.0.18 (`C:\Program Files\Java\jdk-17`) |
| **Android Gradle Plugin** | `8.11.1` |
| **Gradle Wrapper** | `8.14` |
| **Kotlin Compiler** | `2.0.21` |
| **Android Compile SDK** | `36` (Android 16 Baklava preview / 15 Vanilla Ice Cream) |
| **Android Target SDK** | `36` |
| **Android Min SDK** | `26` (Android 8.0 Oreo) |
| **Release AAB SHA-256** | `14EEA8872D7EC13F5CF450CDAC93F62A2A222E1B3BFEC65B7D006ECB9B61A869` |
| **Release APK SHA-256** | `F73ED32F3C7F5278223C272D3082E1B148D8A9B7C6468FAE6DB6B5752065390E` |
| **Signing Cert SHA-256** | `97B4CC881F6D8F5CE69DAFDB6EF2E6986814897B73D898E6553A29A6B1C9AD69` |

---

## 4. Release Classification & Cadence

| Release Class | Trigger / Description | Verification Gate Required | Approval Authority |
| :--- | :--- | :--- | :--- |
| **Major Release** (`X.0.0`) | Architectural redesigns, major OS updates, new cryptographic engines | Full 15-Wave Test Suite, Room Migration Test, Real-Device Validation (Android 8 to 15), Independent Adversarial Review | Lead Architect + Security Lead |
| **Minor Release** (`1.Y.0`) | New features, secondary app-locker modes, UI refinements | Full automated test suite (195+ tests), Clean install & upgrade test, Soak test | Lead Architect |
| **Patch Release** (`1.0.Z`) | Bug fixes, edge-case UI corrections, localized translations | Full automated test suite, Regression matrix verification | Release Engineer |
| **Emergency Hotfix** (`1.0.Z-sec`) | Critical bypass remediation, zero-day vulnerability fix, critical OEM crash | Accelerated Security Validation, Invariant Test Run, Targeted Regression Proof | Security Lead |

---

## 5. Formal Release Workflow

### Step 1: Pre-Release Freeze
1. Create a release branch: `git checkout -b release/v1.0.0`.
2. Update version strings in `pubspec.yaml`:
   ```yaml
   version: 1.0.0+1
   ```
3. Run verification suite:
   ```bash
   flutter analyze
   flutter test
   ./gradlew testDebugUnitTest
   ```
   *Gate:* Must be 100% green with zero warnings and zero failed tests.

### Step 2: Clean Build & Signing
1. Execute clean release compilation:
   ```bash
   flutter clean
   flutter pub get
   flutter build appbundle --release
   flutter build apk --release
   ```
2. Verify artifact signatures with Android SDK `apksigner`:
   ```bash
   apksigner verify --verbose --print-certs build/app/outputs/flutter-apk/app-release.apk
   ```

### Step 3: Checksum & Provenance Logging
1. Generate SHA-256 hashes of the resulting binaries:
   ```powershell
   Get-FileHash build/app/outputs/bundle/release/app-release.aab -Algorithm SHA256
   Get-FileHash build/app/outputs/flutter-apk/app-release.apk -Algorithm SHA256
   ```
2. Document artifact checksums in the release manifest.

### Step 4: Tagging & Publication
1. Commit the release preparation and create an annotated git tag:
   ```bash
   git commit -am "chore(release): promote candidate to v1.0.0"
   git tag -a v1.0.0 -m "LockKeeper Production Release v1.0.0"
   ```
2. Upload `app-release.aab` to Google Play Console Internal / Closed Testing track.
3. Archive release checksums, mapping files, and native debug symbols (`libapp.so.symbols`) in secure cold storage.

---

## 6. Rollback & Emergency Invalidation Policy

1. **Monotonicity Constraint:** Android OS does not permit downgrading APKs without uninstalling first (which purges user app data and encryption keys). Therefore, a rollback is executed by deploying a new forward `versionCode` containing the reverted codebase.
2. **Emergency Hotfix SLA:**
   - In the event of an active bypass or high-severity vulnerability, an emergency hotfix branch (`hotfix/vX.Y.Z`) is cut immediately.
   - The fix must add a targeted regression test verifying the fix before release.
   - Turnaround target: Under 12 hours from reproduction to certified signed artifact.
