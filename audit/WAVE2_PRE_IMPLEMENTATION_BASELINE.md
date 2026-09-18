# LockKeeper Wave 2: Pre-Implementation Baseline & Enforcement Audit

**Document Version:** 1.0.0  
**Audit Date:** September 17, 2026  
**Auditor:** Lead Security Architect (Wave 2 Multi-Agent Team)  
**Baseline Status:** Certified Baseline Recorded  

---

## 1. Verified Baseline Test Count

Before any code modification in Wave 2, all test suites and static analysis tools were executed:

| Test Suite | Environment | Tests Executed | Passed | Failed | Skipped | Status |
|---|---|---|---|---|---|---|
| **Android JVM Unit Tests** | Gradle / JUnit 4 (`testDebugUnitTest`) | **113** | **113** | 0 | 0 | **PASS (100%)** |
| **Flutter Unit & Widget Tests** | `flutter test` | **13** | **13** | 0 | 0 | **PASS (100%)** |
| **Flutter Static Analysis** | `flutter analyze` | - | - | - | - | **0 Issues Found** |
| **Total Automated Baseline** | | **126** | **126** | **0** | **0** | **PASS (100%)** |

Git State: Working tree clean with respect to certified Wave 1 commits.

---

## 2. Current Architecture Overview

LockKeeper operates as a hybrid Android / Flutter application providing application locking, device self-protection, and anti-tamper security.
- **Android Native Subsystem (Kotlin):**
  - `ProtectionRepository`: Aggregates security status, coordinates Room DB, KeyStore credentials, and runtime sessions.
  - `LockDecisionEngine`: Evaluates target packages against locked apps, active sessions, and cooldowns.
  - `TamperDetectionEngine`: Traverses Accessibility node hierarchies and monitors foreground packages to detect uninstallation and device administrator deactivation attempts.
  - `TamperAuthorizationController`: Enforces persistent admin password rate-limiting, 5-minute lockouts, and 30-second monotonic grace windows.
  - `LockKeeperAccessibilityService`: Listens for `TYPE_WINDOW_STATE_CHANGED` / `TYPE_WINDOW_CONTENT_CHANGED` events, triggers tamper evaluation and app-lock evaluation, and commands `OverlayManager`.
  - `OverlayManager`: WindowManager overlay presenter managing Pin, Cooldown, Lockout, and Admin overlays.
  - `SelfLockSessionManager`: Tracks background timeout and PIN authentication for LockKeeper's own UI.
- **Flutter Presentation Subsystem (Dart):**
  - `PlatformBridge`: MethodChannel and EventChannel communication facade.
  - `ProtectionStatusModel`: Immutable representation of native `overallStatus`, `degradedReasons`, and capability booleans.
  - `HomeScreen`, `SettingsScreen`, `OnboardingScreen`, `SelfLockGateScreen`: Passive presentation layer.

---

## 3. Current Security Decision Paths

### A. Is LockKeeper Protecting?
- **Native Evaluation:**
  - `ProtectionRepository.getAuthoritativeSecurityStatus()` calculates `overallStatus`:
    - `RECOVERY_REQUIRED`: If `dpm.isAdminActive() && (!hasCreds || !onboardingComplete || recoveryRequired)`
    - `INITIALIZING`: If `!onboardingComplete`
    - `DEGRADED`: If any of `!isDeviceAdminActive`, `!isA11yOperational`, `!isOverlayGranted`, `!isUsageGranted`, `!hasPin`, `!hasAdminPassword`
    - `PROTECTED`: If fully operational and (`appLockConfigured || selfLockActive`)
    - `CONFIGURED`: If fully operational but no active lock rules
- **Enforcement Gap:**
  - While `ProtectionRepository` computes `overallStatus`, `LockDecisionEngine.evaluate()` **does not check** `overallStatus`!
  - `LockDecisionEngine` independently checks only whether the app is in `locked_apps` and whether an in-memory session exists. It does not verify if the system is in `RECOVERY_REQUIRED`, `DEGRADED`, or `UNKNOWN`.

### B. Should an App Launch Be Blocked?
- **Current Path:**
  1. `LockKeeperAccessibilityService.onAccessibilityEvent(event)` detects target package.
  2. Calls `repository.evaluatePackage(packageName)`.
  3. `ProtectionRepository.evaluatePackage` queries `lockedAppDao.getLockedApp(packageName)` and delegates to `decisionEngine.evaluate(packageName, app, settings)`.
  4. Returns `LockDecision`: `Allowed`, `RequirePin`, `StrictCooldown`, `PinLockout`, or `ProtectedSettingsRequireAdmin`.
  5. Accessibility service displays corresponding overlay via `OverlayManager`.
- **Enforcement Inconsistency:**
  - If `appSettings.recoveryRequired == true`, `evaluatePackage()` currently evaluates the package normally instead of asserting a recovery state or blocking access.
  - If `LockDecisionEngine` has an active in-memory session (`activeSessions[packageName]`), it allows access even if Device Admin has been revoked or local credentials were wiped.

### C. Should an Overlay Appear?
- **Current Path:**
  - `LockKeeperAccessibilityService` evaluates `tamperEngine.evaluate()` and `repository.evaluatePackage()`.
  - Dispatches to `OverlayManager.showPinOverlay()`, `showCooldownOverlay()`, `showLockoutOverlay()`, or `showAdminOverlay()`.
- **Enforcement Inconsistency:**
  - `OverlayManager` relies on asynchronous `mainHandler.post { ... }`. Rapid app switching or window state changes can enqueue conflicting overlay attach/detach operations.
  - While IME packages are guarded in `onAccessibilityEvent` and `dismissIfShowing`, background activity death or service disconnect leaves overlay state uncoordinated with session managers.

### D. Should a Bypass / Grace Session Be Allowed?
- **Current Path:**
  - `TamperAuthorizationController` tracks `adminGraceUntilElapsed` using monotonic time.
  - BUT `LockDecisionEngine` has its own **duplicate** `adminGraceUntil` using wall-clock time (`System.currentTimeMillis() + 30_000L`).
  - In `LockKeeperAccessibilityService.handleTamperEvent()`:
    `if (repository.tamperController.isGraceActive() || repository.decisionEngine.isAdminGraceActive())`
  - This dual-grace mechanism allows wall-clock time manipulation to bypass admin grace checks if `decisionEngine.isAdminGraceActive()` evaluates true.

### E. Self-Lock Gate Decision
- **Current Path:**
  - `SelfLockSessionManager.isAuthRequired()` determines whether PIN gate is shown on app resume.
  - In Flutter `main.dart`, `_checkInitialLock()` and `_handleAppResumed()` call `PlatformBridge.checkSelfLockRequired()` and `reportAppResumed()`.
- **Enforcement Inconsistency:**
  - In `main.dart`:
    ```dart
    try {
      final required = await PlatformBridge.checkSelfLockRequired();
      setState(() { _isLocked = required; });
    } catch (_) {
      setState(() { _isLocked = false; }); // FAIL-OPEN BUG!
    }
    ```
    If the platform channel fails or throws an exception, `_isLocked` defaults to `false`, allowing immediate access to LockKeeper!

---

## 4. Comprehensive Inventory of State Caches & Boundaries

| Component | State Cache / Variable | Source of Truth | Risk / Inconsistency |
|---|---|---|---|
| `LockDecisionEngine` | `activeSessions: ConcurrentHashMap<String, Long>` | In-memory timestamp | Survives until process death; does not invalidate if PIN changed or permissions revoked. |
| `LockDecisionEngine` | `adminGraceUntil: Long` | Wall-clock time | Redundant with `TamperAuthorizationController`; vulnerable to wall-clock shifting. |
| `SelfLockSessionManager` | `isSessionActive: Boolean`, `lastBackgroundTimestamp: Long` | In-memory volatile | Reset on process restart (safe); but Flutter fails open on bridge exception. |
| `ProtectionRepository` | `prefs ("lockkeeper_protection_prefs")` | Room DB `app_settings` | Write-through cache; could get out of sync if Room DB updated directly. |
| `PlatformChannelHandler` | `currentActivity: Activity?` | Volatile Activity ref | Null when backgrounded; does not affect security state. |
| `OverlayManager` | `currentOverlayView`, `currentGateType`, `activeSession` | Volatile UI references | Replaced on main thread; potential race if background events fire concurrently. |
| Flutter `main.dart` | `_isLocked: Boolean` | Native `isSelfLockRequired` | Defaults to `false` on exception (FAIL-OPEN). |
| Flutter `OnboardingScreen`| Local state `_currentStep` | Native credentials | Does not verify if `recoveryRequired` is active before permitting onboarding steps. |

---

## 5. Suspected Enforcement Inconsistencies for Wave 2 Resolution

1. **Duplicate Grace Implementations:** `LockDecisionEngine.adminGraceUntil` (wall-clock) vs. `TamperAuthorizationController.adminGraceUntilElapsed` (monotonic). Must be unified into a single authoritative monotonic grace window.
2. **Fail-Open Catch Block in `main.dart`:** Exception on self-lock query unlocks the application. Must fail closed (`_isLocked = true`).
3. **Decoupled Decision Engine:** `LockDecisionEngine.evaluate()` makes decisions without checking system policy state (`RECOVERY_REQUIRED`, `DEGRADED`, `TAMPER_LOCKED_OUT`).
4. **Session Invalidation on Policy Changes:** When Device Admin is removed, Accessibility disconnected, or PIN/Admin Password altered, `LockDecisionEngine.activeSessions` is not systematically invalidated.
5. **Recovery Required Bypass in Onboarding:** `main.dart` routes to `OnboardingScreen` if `!onboardingComplete` without checking `recoveryRequired`. An attacker could exploit an wiped DB with active Device Admin to enter fresh onboarding.
6. **Immutable Decision Objects with Reason Codes:** Current `LockDecision` lacks structured audit metadata (policy state, reason code, evaluation timestamp).
7. **Concurrency / TOCTOU Races:** Concurrent window events can trigger overlapping `repository.evaluatePackage` coroutines, resulting in out-of-order overlay dispatches.
