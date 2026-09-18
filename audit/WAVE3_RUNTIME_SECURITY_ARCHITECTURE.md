# WAVE 3: RUNTIME RESILIENCE, ANTI-TAMPER & BYPASS ARCHITECTURE

## 1. Architectural Foundations

Wave 1 established authoritative **Security State Truth** across persistent storage and OS managers.
Wave 2 established authoritative **Protection Decisions & Enforcement Integrity** via `LockDecisionEngine`.
Wave 3 hardens **Runtime Resilience & Bypass Resistance**, guaranteeing that valid security decisions cannot be subverted by adversarial runtime conditions, lifecycle interruptions, window manipulation, alternate intent vectors, or OEM peculiarities.

```
+-------------------------------------------------------------------------+
|                         HOSTILE RUNTIME ENVIRONMENT                     |
|  - Process termination & OOM kills                                      |
|  - Service disconnect & binder reconnect races                          |
|  - Screen off / power key toggles                                       |
|  - WindowManager token / addView exceptions (MIUI background pop-up)    |
|  - Alternate intent routes (deep links, share sheets, notifications)    |
|  - Multi-threaded concurrent launches                                    |
+-------------------------------------------------------------------------+
                                     |
                                     v
+-------------------------------------------------------------------------+
|                  WAVE 3 RUNTIME HARDENING MEASURES                      |
|                                                                         |
|  1. FAIL-CLOSED OVERLAY FALLBACK (INV-317)                              |
|     - addView exception -> immediate navigateHome()                     |
|                                                                         |
|  2. SCREEN-OFF SESSION PURGE (INV-318)                                  |
|     - BroadcastReceiver(ACTION_SCREEN_OFF) -> clearAllSessions()        |
|     - SelfLockSessionManager -> invalidateSession()                     |
|                                                                         |
|  3. BOUNDED EPHEMERAL APP SESSIONS (INV-306, INV-308)                   |
|     - Monotonic duration cap: activeSessions max validity               |
|     - Zero persistence: memory-only, wiped on restart                   |
|                                                                         |
|  4. UNIFIED TERMINAL ENFORCEMENT IN FGS FALLBACK (INV-319)              |
|     - Blocked / RecoveryRequired / DenyUnknown -> navigateHome()        |
|                                                                         |
|  5. RELIABLE EXIT TRACKING (INV-310)                                    |
|     - Package switches reliably trigger handleAppExited()               |
|                                                                         |
|  6. EXPANDED TAMPER INSTALLER COVERAGE (INV-311)                        |
|     - Google Play Store (com.android.vending) + OEM centers monitored   |
+-------------------------------------------------------------------------+
                                     |
                                     v
+-------------------------------------------------------------------------+
|                       ABSOLUTE RUNTIME INVARIANTS                       |
|  - INV-301 through INV-319 fully satisfied and verified                 |
+-------------------------------------------------------------------------+
```

---

## 2. Invariant Specifications (INV-301 through INV-319)

- **INV-301**: A protected application cannot become usable merely because LockKeeper's process restarts.
  - *Mechanism*: Session storage is strictly in-memory ephemeral; process restart defaults to zero sessions.
- **INV-302**: AccessibilityService restart cannot create a permanent protection gap.
  - *Mechanism*: `onDestroy()` clears sessions; re-connection starts from unauthenticated zero-trust.
- **INV-303**: AccessibilityService disconnection cannot produce ALLOW for a protected app.
  - *Mechanism*: Health status becomes `DEGRADED`; `LockDecisionEngine` continues requiring PIN.
- **INV-304**: Device Admin removal invalidates applicable sessions immediately.
  - *Mechanism*: `LockKeeperDeviceAdminReceiver.onDisabled()` triggers `clearAllSessions()`.
- **INV-305**: Overlay dismissal cannot constitute authorization.
  - *Mechanism*: Overlay view removal never grants an app session; only valid PIN verification calls `grantAppSession()`.
- **INV-306**: An expired authentication session cannot be replayed.
  - *Mechanism*: Timestamps in `activeSessions` are validated against monotonic time.
- **INV-307**: Grace sessions cannot survive beyond their monotonic expiry.
  - *Mechanism*: Checked strictly against `SystemClock.elapsedRealtime()`; 30,000 ms hard cutoff.
- **INV-308**: A service/process lifecycle transition cannot resurrect stale authorization.
  - *Mechanism*: State transitions clear transient sessions; persistent storage only retains lockout and configuration.
- **INV-309**: Two concurrent launch events cannot create contradictory authorization results.
  - *Mechanism*: `ConcurrentHashMap` and immutable `LockDecision` instances prevent race corruption.
- **INV-310**: A protected application launched through an alternate intent route receives the same enforcement as a launcher launch.
  - *Mechanism*: Interception occurs at the package-level via `TYPE_WINDOW_STATE_CHANGED`, independent of intent action, data, or activity component.
- **INV-311**: Settings traversal cannot bypass the existing tamper authorization mechanism through an alternate Settings route.
  - *Mechanism*: Traversal inspection across all known Settings, PackageInstaller, Google Play Store, and OEM security apps.
- **INV-312**: A native component failure cannot convert a protected decision into ALLOW.
  - *Mechanism*: Catch blocks fail closed to `DenyUnknown(DENIED_NATIVE_FAILURE)`.
- **INV-313**: RecoveryRequired cannot be bypassed by restarting the application.
  - *Mechanism*: Persisted in Room DB `recoveryRequired` column; checked at cold start before UI loads.
- **INV-314**: Clearing or invalidating user-facing state cannot silently produce fresh onboarding.
  - *Mechanism*: `checkRecoveryStatus()` detects active Device Admin without credentials and transitions to `RECOVERY_REQUIRED`.
- **INV-315**: Tamper lockout overrides ordinary authorization.
  - *Mechanism*: Precedence rules enforce `Blocked(DENIED_TAMPER_LOCKOUT)` before active sessions or PIN entry.
- **INV-316**: Reboot cannot silently reset persistent security restrictions.
  - *Mechanism*: Room SQLite DB persists lockout timestamps across power cycles; `BootReceiver` restarts FGS.
- **INV-317 (New)**: Overlay attachment failure must fail closed and navigate away from the target app.
  - *Mechanism*: `attachOverlay()` catches any `WindowManager` exception and immediately triggers `navigateHome()`.
- **INV-318 (New)**: Turning off the device screen immediately revokes all active in-memory app sessions and self-lock sessions.
  - *Mechanism*: `ACTION_SCREEN_OFF` broadcast receiver calls `clearAllSessions()` and `invalidateSelfLockSession()`.
- **INV-319 (New)**: Foreground Service fallback must strictly enforce `Blocked`, `RecoveryRequired`, and `DenyUnknown` outcomes by sending Home.
  - *Mechanism*: `LockKeeperForegroundService.checkAndEnforcePackage()` routes all denial decisions to Home with `FLAG_ACTIVITY_NEW_TASK`.
