# WAVE 2: PROTECTION DECISION & ENFORCEMENT INTEGRITY ARCHITECTURE

## 1. Executive Overview

Wave 1 established authoritative **Security State Truth** across LockKeeper's native subsystems (Room Database, Device Policy Manager, Accessibility Manager, and Credential Keystore).

Wave 2 establishes authoritative **Security Enforcement Integrity**. It answers the core question:
> *"When LockKeeper knows its security state, does every security-critical subsystem actually enforce that state consistently, deterministically, and fail-safe-closed?"*

Prior to Wave 2, protection enforcement suffered from fragmented decision points:
- Boolean shortcuts (`appSettings.onboardingComplete` evaluated as a quick bypass)
- Separate and uncoordinated admin grace implementations (one in `TamperAuthorizationController` using monotonic clock, another in `LockDecisionEngine` using wall-clock time)
- Fail-open exception handling in Flutter (`catch (_) { _isLocked = false; }`)
- Missing session revocation on Device Admin deactivation and Accessibility Service death
- Ambiguous decision outputs lacking structured reason codes

Wave 2 eliminates these vulnerabilities by introducing a unified, immutable, and monotonic **Protection Decision Pipeline**.

---

## 2. The Authoritative Decision Flow

```
+-------------------------------------------------------------------------+
|                         NATIVE SECURITY STATE                           |
|  - Room AppSettingsEntity (lockout timestamps, failed counts, flags)    |
|  - DevicePolicyManager (Device Admin active/inactive)                  |
|  - AccessibilityManager & Service (connected & operational)             |
|  - CredentialStore (hasPin, hasAdminPassword)                          |
+-------------------------------------------------------------------------+
                                     |
                                     v
+-------------------------------------------------------------------------+
|                       PROTECTION REPOSITORY                             |
|  - checkRecoveryStatus()                                                |
|  - tamperController.checkLockout()                                      |
|  - tamperController.isGraceActive()                                     |
|  - calculateSecurityHealthStatus()                                      |
|  - evaluatePackage(targetPackage) [FAIL-CLOSED BOUNDARY]                |
+-------------------------------------------------------------------------+
                                     |
                                     v
+-------------------------------------------------------------------------+
|                     LOCK DECISION ENGINE (SSOT)                         |
|  - Monotonic Clock Provider                                             |
|  - Thread-Safe Ephemeral App Sessions (ConcurrentHashMap)              |
|  - Deterministic Precedence Evaluation                                  |
+-------------------------------------------------------------------------+
                                     |
                                     v
+-------------------------------------------------------------------------+
|                  IMMUTABLE PROTECTION DECISION                          |
|  - Outcome: ALLOW | BLOCK | REQUIRE_AUTHENTICATION |                   |
|             REQUIRE_RECOVERY | DENY_UNKNOWN_STATE                       |
|  - Reason: Granular, audited reason code                               |
+-------------------------------------------------------------------------+
                                     |
                                     v
+-------------------------------------------------------------------------+
|                       ENFORCEMENT SUBSYSTEMS                            |
|  - LockKeeperAccessibilityService (window interception, overlays, Home) |
|  - OverlayManager (Overlay display, PIN/Admin prompt, Cooldown/Lockout) |
|  - MainActivity & Flutter Gate Screen (Self-Lock, Recovery alert)       |
|  - DeviceAdminReceiver (Session invalidation on disable)                |
+-------------------------------------------------------------------------+
```

---

## 3. Strict Precedence Hierarchy

Every evaluation in `LockDecisionEngine.evaluate()` follows an uncompromising precedence order:

1. **Target Verification (Self-Package)**
   - If `targetPackage == appPackageName` $\rightarrow$ `ALLOW` (`ALLOWED_OWN_PACKAGE`).
   - Prevents self-deadlocks and ensures LockKeeper's UI remains accessible for unlocking.

2. **Recovery Enforcement**
   - If `isRecoveryRequired == true` $\rightarrow$ `REQUIRE_RECOVERY` (`DENIED_RECOVERY_REQUIRED`).
   - Overrides all active app sessions, PIN codes, and admin grace. The system is structurally compromised or out of sync and cannot perform ordinary protection.

3. **Unknown Health State**
   - If `securityHealthStatus == "UNKNOWN"` $\rightarrow$ `DENY_UNKNOWN_STATE` (`DENIED_UNKNOWN_STATE`).
   - If health state is undefined or unreachable, the system fails closed immediately.

4. **Tamper Admin Grace**
   - If `isTamperGraceActive == true` $\rightarrow$ `ALLOW` (`ALLOWED_ADMIN_GRACE`).
   - Allows authorized administrators a 30-second monotonic window to modify device settings or uninstall.

5. **Tamper Lockout**
   - If `isTamperLocked == true` $\rightarrow$ `BLOCK` (`DENIED_TAMPER_LOCKOUT`).
   - Active tamper brute-force triggers total lockdown, overriding all user authentication.

6. **Unprotected Application**
   - If `lockedApp == null` or `!lockedApp.isLocked` $\rightarrow$ `ALLOW` (`ALLOWED_UNPROTECTED_APP`).

7. **Active Temporary App Session**
   - If `hasActiveAppSession(targetPackage)` is true $\rightarrow$ `ALLOW` (`ALLOWED_ACTIVE_SESSION`).

8. **PIN Rate-Limiting Lockout**
   - If `appSettings.pinLockoutUntil != null && pinLockoutUntil > currentTime` $\rightarrow$ `BLOCK` (`LOCKOUT_ACTIVE`).

9. **Strict Lock Cooldown**
   - If `lockedApp.strictLock && lockedUntilTimestamp > currentTime` $\rightarrow$ `BLOCK` (`COOLDOWN_ACTIVE`).

10. **Incomplete Initialization Gate**
    - If `securityHealthStatus == "INITIALIZING"` $\rightarrow$ `BLOCK` (`DENIED_INITIALIZATION_INCOMPLETE`).

11. **Default Fail-Closed Fallback**
    - In all remaining cases $\rightarrow$ `REQUIRE_AUTHENTICATION` (`AUTH_REQUIRED_PIN`).

---

## 4. Structured Outcomes and Reason Codes

### Outcomes (`ProtectionDecisionOutcome`)
- `ALLOW`: Permitted to execute without interference.
- `BLOCK`: Prohibited from running. Background or overlay displays cooldown/lockout banner; accessibility sends `GLOBAL_ACTION_HOME`.
- `REQUIRE_AUTHENTICATION`: Requires credential verification (PIN or Admin Password) before launch.
- `REQUIRE_RECOVERY`: Critical integrity failure. Persistent recovery required banner shown; onboarding blocked.
- `DENY_UNKNOWN_STATE`: System state indeterminate. Interception blocks app launch and sends `GLOBAL_ACTION_HOME`.

### Reasons (`ProtectionDecisionReason`)
| Reason Code | Outcome | Triggering Condition |
| :--- | :--- | :--- |
| `ALLOWED_OWN_PACKAGE` | `ALLOW` | Launching LockKeeper itself |
| `ALLOWED_UNPROTECTED_APP` | `ALLOW` | Application is not marked locked in Room DB |
| `ALLOWED_ACTIVE_SESSION` | `ALLOW` | Transient memory session active following valid PIN |
| `ALLOWED_ADMIN_GRACE` | `ALLOW` | Admin password verified; within 30s monotonic window |
| `DENIED_RECOVERY_REQUIRED` | `REQUIRE_RECOVERY` | Admin active with missing credentials or db mismatch |
| `DENIED_TAMPER_LOCKOUT` | `BLOCK` | 5 failed admin password attempts (5 min persistent) |
| `DENIED_SECURITY_DEGRADED`| `BLOCK` | Critical security component disabled |
| `DENIED_INITIALIZATION_INCOMPLETE` | `BLOCK` | Application launch before initialization concludes |
| `DENIED_UNKNOWN_STATE` | `DENY_UNKNOWN_STATE` | Health status indeterminate |
| `DENIED_NATIVE_FAILURE` | `DENY_UNKNOWN_STATE` | Exception thrown during security evaluation |
| `AUTH_REQUIRED_PIN` | `REQUIRE_AUTHENTICATION` | Protected app without active session |
| `AUTH_REQUIRED_ADMIN_PASSWORD` | `REQUIRE_AUTHENTICATION` | Settings or tamper-sensitive target |
| `COOLDOWN_ACTIVE` | `BLOCK` | Strict lock timer currently counting down |
| `LOCKOUT_ACTIVE` | `BLOCK` | User PIN lockout timer currently counting down |

---

## 5. Enforcement Boundaries & Lifecycle Guarantees

1. **Accessibility Service Lifecycle (`LockKeeperAccessibilityService`)**:
   - Holds no persistent unlock state.
   - On `onDestroy()`, calls `repository.decisionEngine.clearAllSessions()`.
   - On reconnect, begins with 0 granted sessions.

2. **Device Admin Lifecycle (`LockKeeperDeviceAdminReceiver`)**:
   - On `onDisabled()`, calls `repo.decisionEngine.clearAllSessions()`.
   - Invalidates all active app sessions instantly upon admin removal attempt.

3. **Flutter Platform Bridge (`lib/main.dart` & `OnboardingScreen`)**:
   - `_checkInitialLock()` and `_handleAppResumed()` catch blocks now fail closed (`_isLocked = true`).
   - If `recoveryRequired == true`, `LockKeeperApp` routes directly to `HomeScreen` to display the recovery banner, refusing to load `OnboardingScreen` and preventing new PIN generation while in an unmanaged state.

4. **Concurrency & Thread Safety**:
   - `LockDecisionEngine` session storage utilizes `ConcurrentHashMap<String, Long>`.
   - All evaluations produce immutable `LockDecision` instances.
   - Lockout and rate-limiting persist in Room SQLite via transactions.
