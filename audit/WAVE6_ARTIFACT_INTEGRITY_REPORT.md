# WAVE 6: ARTIFACT INTEGRITY REPORT
## PRODUCTION BUILD ARTIFACTS, SIGNING VERIFICATION, AND CRYPTOGRAPHIC DIGESTS

---

### 1. Executive Summary

This report establishes the cryptographic provenance, artifact digests, signing configuration, and environment reproducibility for the LockKeeper Release Candidate build artifacts.

Both the standalone distribution APK (`app-release.apk`) and Google Play App Bundle (`app-release.aab`) were built from source under clean compiler conditions.

---

### 2. Build Environment Provenance

- **Host Machine:** Windows 11 Pro 64-bit (Build 26100)
- **Flutter Framework:** Flutter 3.41.6 (Channel stable, revision `db50e20168`, 2026-03-25)
- **Dart SDK:** Dart 3.11.4 (DevTools 2.54.2)
- **Java Runtime / Compiler:** OpenJDK 17.0.18 (`javac 17.0.18`, 64-bit)
- **Android SDK:** Build-Tools `35.0.0`, Platform SDK `36` (Android 16)
- **Android Gradle Plugin (AGP):** `8.11.1`
- **Gradle Wrapper:** `8.14` (`gradle-8.14-all.zip`)
- **Kotlin Compiler:** `2.0.21` (JVM target 17)

---

### 3. Generated Build Artifacts

| Artifact Name | Format | Target Destination | File Size | SHA-256 Checksum |
| :--- | :--- | :--- | :--- | :--- |
| **`app-release.aab`** | Android App Bundle | **Google Play Console** | **43,292,771 bytes** (~41.3 MB) | `14EEA8872D7EC13F5CF450CDAC93F62A2A222E1B3BFEC65B7D006ECB9B61A869` |
| **`app-release.apk`** | Universal APK | **Sideload / Direct Release** | **50,973,874 bytes** (~48.6 MB) | `F73ED32F3C7F5278223C272D3082E1B148D8A9B7C6468FAE6DB6B5752065390E` |
| **`app-debug.apk`** | Debug APK | **Local Testing / Automation**| **159,637,653 bytes** (~152.2 MB)| `FC454BD48F8AA99EB42FF9C09AED1C4A885B044D40DD5399D84414F83AAF73EE` |

---

### 4. Digital Signature Verification (`apksigner`)

Verification performed via Android SDK `build-tools/35.0.0/apksigner.bat`:

```text
Status: Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): false
Number of signers: 1

Signer #1 Details:
  Certificate DN: CN=LockKeeper, OU=Security, O=LockKeeper, L=Local, ST=Local, C=US
  Certificate SHA-256 Digest: 97b4cc881f6d8f5ce69dafdb6ef2e6986814897b73d898e6553a29a6b1c9ad69
  Certificate SHA-1 Digest:   3489cb96f61b18b636f4d7d4da8a9547fd70b138
  Certificate MD5 Digest:     062078311e67a4f2741c5d6a511c7452
  Key Algorithm:              RSA (2048 bits)
  Public Key SHA-256 Digest:  11345c2fff40723012bd6775735605d2be6b84acb5a2f7332e5c81df85900031
```

---

### 5. Application Metadata Verification

- **Package ID / Namespace:** `com.lockkeeper.app`
- **versionCode:** `1`
- **versionName:** `1.0.0`
- **Minimum Supported Android Version:** Android 8.0 Oreo (API 26)
- **Target Android Version:** Android 16 (API 36)
- **Build Timestamp:** 2026-09-18T02:25:38+05:30

---

### 6. Build Reproducibility Audit

- **Dependencies:** All dependencies resolve deterministically via Maven Central, Google Maven, and Flutter SDK cache.
- **Hermetic Build State:** Zero undeclared third-party binary artifacts or unpinned dynamic plugins.
- **Git State Alignment:** HEAD commit `3fc54b1` with zero tracked credential exposure.
- **Artifact Verdict:** The release artifacts (`app-release.aab` and `app-release.apk`) meet all cryptographic and structure integrity requirements for release distribution.
