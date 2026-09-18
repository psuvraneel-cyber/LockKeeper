# Agent 01: Change-Scope / Diff Auditor Report

**Date:** 2026-09-17  
**Auditor:** Agent 01 — Change-Scope / Diff Auditor  
**Target:** LockKeeper Self-Protection & Anti-Tamper Implementation  
**Status:** COMPLETE (Hostile Static Inspection)

---

## 1. Executive Summary

This audit examined the exact changes introduced in the Self-Protection / Anti-Tamper implementation against the codebase baseline. 

The implementation was intended to add structured anti-tamper detection, rate-limiting, and an Admin Password gate to protect against unauthorized uninstall, force-stop, clear-data, and disable attempts.

**Key Scope Findings:**
1. **Scope Creep & Extraneous Modifications:** In addition to security files, binary files (`LockKeeper-v1.0.0-release.apk`, mipmap launcher icons, `assets/icon/app_icon.png`), build scripts (`scripts/android_build.ps1`), and checksum manifests (`checksums.txt`) were modified or committed into git.
2. **Deceptive Alignment with Documented Architecture:** Multiple components claimed in the implementation report (`SELF_PROTECTION_IMPLEMENTATION_REPORT.md`) either do not exist or differ radically from the code:
   - Claimed: `TYPE_ACCESSIBILITY_OVERLAY` token. Actual: `TYPE_APPLICATION_OVERLAY` (`OverlayManager.kt:53`).
   - Claimed: `TYPE_WINDOW_CONTENT_CHANGED` filtering. Actual: Explicitly dropped (`LockKeeperAccessibilityService.kt:50, 70`).
   - Claimed: `RECOVERY_REQUIRED` security state machine. Actual: Non-existent in code.
3. **Weakened / Altered Pre-existing Mechanisms:**
   - Pre-existing `handleSettingsEvent` in `LockKeeperAccessibilityService.kt` performed exhaustive text search on the node tree. It was replaced with a severely truncated Depth-First Search (DFS) bounded at `depth <= 6` and `count < 45`.
   - `lastHandledPackage` tracking was broken for all tamper events because early returns bypass its assignment (`LockKeeperAccessibilityService.kt:108`), impairing debounce and app exit tracking.
4. **Duplication of Core Concepts:** Two conflicting admin grace window mechanisms now run concurrently: `LockDecisionEngine.adminGraceUntil` (wall-clock) and `TamperAuthorizationController.adminGraceUntilElapsed` (monotonic time).

---

## 2. Git Delta Inventory

### 2.1 Newly Created Files
- `android/app/src/main/kotlin/com/lockkeeper/app/security/TamperEvent.kt` (37 lines)
- `android/app/src/main/kotlin/com/lockkeeper/app/security/TamperDetectionEngine.kt` (310 lines)
- `android/app/src/main/kotlin/com/lockkeeper/app/security/TamperAuthorizationController.kt` (125 lines)
- `android/app/src/test/kotlin/com/lockkeeper/app/TamperDetectionEngineTest.kt` (322 lines)
- `android/app/src/test/kotlin/com/lockkeeper/app/TamperAuthorizationControllerTest.kt` (163 lines)

### 2.2 Modified Implementation Files
- `android/app/src/main/kotlin/com/lockkeeper/app/MainActivity.kt` (+24, -2 lines)
- `android/app/src/main/kotlin/com/lockkeeper/app/bridge/PlatformChannelHandler.kt` (+37, -2 lines)
- `android/app/src/main/kotlin/com/lockkeeper/app/data/db/AppDatabase.kt` (+11, -2 lines)
- `android/app/src/main/kotlin/com/lockkeeper/app/data/db/AppSettingsDao.kt` (+6 lines)
- `android/app/src/main/kotlin/com/lockkeeper/app/data/db/AppSettingsEntity.kt` (+2 lines)
- `android/app/src/main/kotlin/com/lockkeeper/app/domain/ProtectionRepository.kt` (+31, -2 lines)
- `android/app/src/main/kotlin/com/lockkeeper/app/overlay/AdminOverlayView.kt` (+24, -12 lines)
- `android/app/src/main/kotlin/com/lockkeeper/app/overlay/OverlayManager.kt` (+62, -18 lines)
- `android/app/src/main/kotlin/com/lockkeeper/app/service/LockKeeperAccessibilityService.kt` (+123, -125 lines)
- `android/app/src/test/kotlin/com/lockkeeper/app/CredentialStoreTest.kt` (+23, -28 lines)

### 2.3 Unrelated / Non-Security Files Modified
- `.gitignore` (+19, -4 lines)
- `LockKeeper-v1.0.0-release.apk` (Binary diff: 51,167,606 bytes -> 50,907,814 bytes)
- `checksums.txt` (+2, -2 lines)
- `scripts/android_build.ps1` (+31, -7 lines)
- `assets/icon/app_icon.png` (Binary diff)
- `android/app/src/main/res/mipmap-*/ic_launcher.png` (All 5 density mipmaps updated)

---

## 3. Detailed Scope & Behavioral Review

### 3.1 Did implementation remain within scope?
**Result: PARTIAL / DEVIATED.**
While the core security classes (`TamperEvent`, `TamperDetectionEngine`, `TamperAuthorizationController`) directly address anti-tamper, the author committed heavy build outputs (a 50MB APK directly into git tracking), modified unrelated icon assets, and modified build scripts.

### 3.2 Were unrelated files changed?
**Result: CONFIRMED.**
- `LockKeeper-v1.0.0-release.apk` committed into workspace.
- `assets/icon/app_icon.png` and all Android mipmaps replaced with brand new icon assets.
- `scripts/android_build.ps1` was modified to inject automatic SHA-256 generation and APK copying.

### 3.3 Were security-sensitive behaviors changed accidentally?
**Result: CONFIRMED.**
1. **Broken Event Tracking:** In `LockKeeperAccessibilityService.kt`:
   ```kotlin
   if (tamperEvent != null && tamperEvent.confidence != com.lockkeeper.app.security.TamperConfidence.LOW) {
       handleTamperEvent(tamperEvent, packageName)
       return // <--- EXITS HERE
   }
   ...
   lastHandledPackage = packageName // Line 126 is NEVER reached!
   ```
   When a tamper event triggers, the method exits at line 108. Consequently, `lastHandledPackage` is never updated to `packageName`. When subsequent events arrive, `packageName == lastHandledPackage` evaluates to `false`, disabling the 150ms debounce mechanism for tamper events entirely.
2. **Settings Event Replacement:** The previous implementation had a private `handleSettingsEvent` and `isProtectedSettingsScreen` that inspected the entire node hierarchy for the app package or "lockkeeper". The new implementation completely deleted `handleSettingsEvent`, replacing it with `tamperEngine.evaluate`. Because `tamperEngine` has an arbitrary 45-node limit and depth-6 cutoff, wide or deep view trees that previously matched now silently fail.
3. **Overly Broad Settings Dismissal:** In `LockKeeperAccessibilityService.kt`:
   ```kotlin
   } else if (isSystemManagementPackage(packageName)) {
       // Navigated away from sensitive screen within Settings or Package Installer
       overlayManager.dismissAdminOverlay()
   }
   ```
   If an intermediate event (such as soft input window appearance, autocomplete popup, or fragment transaction) arrives from `com.android.settings` where `rootNode` is transiently null or incomplete, `tamperEngine.evaluate` returns null, immediately dismissing the active Admin overlay while the user is attempting to type the Admin Password.

### 3.4 Were existing protections weakened?
**Result: CONFIRMED.**
1. **PlatformChannel Error Degradation:** In `PlatformChannelHandler.kt`:
   ```kotlin
   "verifyAdminPassword" -> {
       val password = call.argument<String>("password") ?: ""
       val authResult = repository.tamperController.verifyAdminPassword(password)
       val valid = authResult is com.lockkeeper.app.security.AdminAuthResult.Success
       result.success(valid)
   }
   ```
   Before, `verifyAdminPassword` simply returned verification status. Now it runs through the rate limiter. When locked out, it returns `valid = false`. The Flutter settings screen (`settings_screen.dart:177, 287`) receives a generic `false` without knowing that the account is locked out or for how long. The UI cannot show the lockout countdown, degrading security transparency.
2. **Missing `TYPE_WINDOW_CONTENT_CHANGED`:** Pre-existing architecture relied on dynamic updates. The implementation explicitly constrained event listening to `TYPE_WINDOW_STATE_CHANGED`, ignoring `TYPE_WINDOW_CONTENT_CHANGED`. Any dynamic UI change (scrolling, single-activity tab switch, Jetpack Compose recomposition) within Settings is invisible to LockKeeper.

### 3.5 Did the implementation introduce duplicated mechanisms?
**Result: CONFIRMED.**
1. **Dual Grace Window Timers:**
   - In `LockDecisionEngine.kt`:
     ```kotlin
     @Volatile private var adminGraceUntil: Long = 0L // Wall clock time
     fun grantAdminGraceWindow(currentTime: Long = System.currentTimeMillis()) {
         adminGraceUntil = currentTime + ADMIN_GRACE_WINDOW_MS
     }
     fun isAdminGraceActive(...) = currentTime < adminGraceUntil
     ```
   - In `TamperAuthorizationController.kt`:
     ```kotlin
     @Volatile private var adminGraceUntilElapsed: Long = 0L // Monotonic time
     fun grantGraceWindow() {
         adminGraceUntilElapsed = monotonicTimeProvider() + ADMIN_GRACE_WINDOW_MS
     }
     fun isGraceActive() = monotonicTimeProvider() < adminGraceUntilElapsed
     ```
   - In `LockKeeperAccessibilityService.kt`:
     ```kotlin
     if (repository.tamperController.isGraceActive() || repository.decisionEngine.isAdminGraceActive())
     ```
   Two separate classes maintain independent 30-second grace window timers with different clocks (monotonic elapsed realtime vs wall-clock UTC). If `tamperController.revokeGraceWindow()` is called, `decisionEngine.isAdminGraceActive()` remains active, creating state desynchronization.

### 3.6 Are changes larger than necessary?
**Result: CONFIRMED.**
- Binary APK and icon assets should never be committed as part of a security hardening wave.
- Dead enums in `TamperEvent.kt` (`TamperSource.LAUNCHER`, `TamperSource.PLAY_STORE`, `TamperSource.OTHER`, `TamperType.SECURITY_SETTINGS`, `TamperType.UNKNOWN`) were declared but never used in `TamperDetectionEngine.kt`.

### 3.7 Are there hidden side effects?
**Result: CONFIRMED.**
1. **Room Migration Destructive Fallback:** Database schema was bumped from version 2 to 3. `MIGRATION_2_3` was added, but `AppDatabase.kt` includes `.fallbackToDestructiveMigration()`. If any migration error occurs, the entire database is wiped, including app settings and all locked apps.
2. **Default AppSettings Row Bug:** If the database was never initialized with row `id = 1`, `updateAdminLockout` in `AppSettingsDao` does `UPDATE ... WHERE id = 1`, which affects 0 rows. Rate limiting fails open (infinite attempts).

---

## 4. Auditor Conclusion

The implementation contains severe scope drift, discrepancies between claims and code, and introduces dual timers and broken state tracking. It weakened existing node scanning by prematurely aborting at 45 nodes and depth 6.
