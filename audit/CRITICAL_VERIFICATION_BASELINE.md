# Critical Verification Baseline — LockKeeper

**Date:** 2026-09-18T03:26:00+05:30  
**Phase:** STAGE 0 — PRESERVE EVIDENCE  
**Execution Type:** Read-Only Verification Gate Pre-Flight  

---

## 1. Version Control State

### Current Commit
```
commit 3fc54b197be00e5142c8ce73114498543e86f5ac
Author: Sauvraneel Paul <sauvraneel.paul@example.com>
Date:   Thu Sep 17 2026
Message: Harden self-protection and anti-tamper
```

### Git Log (-5)
```
3fc54b1 Harden self-protection and anti-tamper
5c8ba93 test: add UI screenshots and view hierarchy XML artifacts
23c57fb feat: add initial implementation of AppLocker with Flutter UI and Android native components
9aafd06 Initial commit: LockKeeper
```

### Working Tree State (`git status --short`)
```
 M android/app/src/main/AndroidManifest.xml
 M android/app/src/main/kotlin/com/lockkeeper/app/MainActivity.kt
 M android/app/src/main/kotlin/com/lockkeeper/app/bridge/PlatformChannelHandler.kt
 M android/app/src/main/kotlin/com/lockkeeper/app/data/db/AppDatabase.kt
 M android/app/src/main/kotlin/com/lockkeeper/app/data/db/AppSettingsDao.kt
 M android/app/src/main/kotlin/com/lockkeeper/app/data/db/AppSettingsEntity.kt
 M android/app/src/main/kotlin/com/lockkeeper/app/domain/LockDecision.kt
 M android/app/src/main/kotlin/com/lockkeeper/app/domain/LockDecisionEngine.kt
 M android/app/src/main/kotlin/com/lockkeeper/app/domain/ProtectionRepository.kt
 M android/app/src/main/kotlin/com/lockkeeper/app/overlay/OverlayManager.kt
 M android/app/src/main/kotlin/com/lockkeeper/app/receiver/LockKeeperDeviceAdminReceiver.kt
 M android/app/src/main/kotlin/com/lockkeeper/app/security/TamperDetectionEngine.kt
 M android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperAccessibilityService.kt
 M android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperForegroundService.kt
 M android/app/src/test/kotlin/com/lockkeeper/app/DatabaseMigrationTest.kt
 M android/app/src/test/kotlin/com/lockkeeper/app/SecurityInvariantsTest.kt
 M android/app/src/test/kotlin/com/lockkeeper/app/TamperAuthorizationControllerTest.kt
 M lib/core/models/protection_status_model.dart
 M lib/core/services/platform_bridge.dart
 M lib/main.dart
 M lib/ui/screens/home_screen.dart
 M lib/ui/screens/onboarding_screen.dart
 M lib/ui/screens/settings_screen.dart
?? android/app/src/test/kotlin/com/lockkeeper/app/IncidentResolutionUnitTest.kt
?? android/app/src/test/kotlin/com/lockkeeper/app/ProductionSecurityBoundaryTest.kt
?? android/app/src/test/kotlin/com/lockkeeper/app/ProtectionEnforcementTest.kt
?? android/app/src/test/kotlin/com/lockkeeper/app/RuntimeResilienceTest.kt
?? android/app/src/test/kotlin/com/lockkeeper/app/SecurityStateArchitectureTest.kt
?? test/enforcement_integrity_test.dart
?? test/incident_resolution_test.dart
?? test/runtime_resilience_test.dart
?? test/security_state_test.dart
```

### Git Diff Statistics (`git diff --stat`)
```
 android/app/src/main/AndroidManifest.xml           |   3 +-
 .../main/kotlin/com/lockkeeper/app/MainActivity.kt |   1 +
 .../app/bridge/PlatformChannelHandler.kt           |  26 ++
 .../com/lockkeeper/app/data/db/AppDatabase.kt      |  15 +-
 .../com/lockkeeper/app/data/db/AppSettingsDao.kt   |   6 +
 .../lockkeeper/app/data/db/AppSettingsEntity.kt    |   3 +-
 .../com/lockkeeper/app/domain/LockDecision.kt      |  95 ++++++-
 .../lockkeeper/app/domain/LockDecisionEngine.kt    |  96 +++++--
 .../lockkeeper/app/domain/ProtectionRepository.kt  | 137 ++++++++-
 .../com/lockkeeper/app/overlay/OverlayManager.kt   |   7 +-
 .../app/receiver/LockKeeperDeviceAdminReceiver.kt  |   8 +
 .../app/security/TamperDetectionEngine.kt          |   3 +-
 .../app/service/LockKeeperAccessibilityService.kt  |  74 ++++-
 .../app/service/LockKeeperForegroundService.kt     |  16 ++
 .../com/lockkeeper/app/DatabaseMigrationTest.kt    |  43 +++
 .../com/lockkeeper/app/SecurityInvariantsTest.kt   |   8 +
 .../app/TamperAuthorizationControllerTest.kt       |   8 +
 lib/core/models/protection_status_model.dart       | 104 ++++++-
 lib/core/services/platform_bridge.dart             |  55 ++--
 lib/main.dart                                      |  37 ++-
 lib/ui/screens/home_screen.dart                    | 151 +++++++++-
 lib/ui/screens/onboarding_screen.dart              |   6 +
 lib/ui/screens/settings_screen.dart                | 308 ++++++++++++++++++---
 23 files changed, 1085 insertions(+), 125 deletions(-)
```

---

## 2. Toolchain & Environment

| Component | Version / Specification |
| :--- | :--- |
| **Flutter** | 3.41.6 (stable, channel stable, revision `db50e20168`) |
| **Dart** | 3.11.4 |
| **Gradle** | 8.14 (wrapper: `gradle-8.14-all.zip`) |
| **Android Gradle Plugin (AGP)** | 8.11.1 |
| **Kotlin** | 2.0.21 (JVM target: 17) |
| **JDK** | OpenJDK 17.0.18 (Microsoft-13106358, build 17.0.18+8-LTS, 64-Bit Server VM) |
| **Android SDK Path** | `C:\Android\Sdk` |
| **Compile SDK** | API 36 |
| **Target SDK** | API 36 |
| **Min SDK** | API 26 |
| **Application ID** | `com.lockkeeper.app` |
| **Version Code / Name** | `1` / `1.0.0` (version: `1.0.0+1`) |

---

## 3. Connected Test Devices

### Primary Real Device (Physical)
- **Device Serial:** `7732644d`
- **Product:** `gauguininpro`
- **Model:** `M2007J17I` (Xiaomi Mi 10i 5G)
- **Android Version:** 12 (`ro.build.version.release=12`)
- **MIUI Version:** MIUI 14 Global (`ro.miui.ui.version.name=V140`, build `V14.0.3.0.SJSINXM`)
- **Transport ID:** 8
- **Initial State:** Clean (LockKeeper not installed, no Device Admin registered, no Accessibility service enabled).

### Secondary Emulator Environment
- **AVD Identifier:** `Antigravity_Test`
- **Target OS:** Android 16 (API 36, `system-images\android-36\google_apis\x86_64\`)
- **Architecture:** x86_64
