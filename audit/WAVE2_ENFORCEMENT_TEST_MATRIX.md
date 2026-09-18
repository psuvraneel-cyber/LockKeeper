# WAVE 2: ENFORCEMENT TEST MATRIX

## 1. Security Invariants (INV-201 through INV-215)

| Invariant ID | Invariant Definition | Primary Test Method | File Location | Verdict |
| :--- | :--- | :--- | :--- | :--- |
| **INV-201** | UNKNOWN security state must never become ALLOW through default values | `INV-201 UNKNOWN security state must never become ALLOW through default values` | `ProtectionEnforcementTest.kt` | **PASS** |
| **INV-202** | RECOVERY_REQUIRED must never become normal protection mode | `INV-202 RECOVERY_REQUIRED must never become normal protection mode` | `ProtectionEnforcementTest.kt` | **PASS** |
| **INV-203** | A disconnected AccessibilityService must not be treated as operational | `INV-203 Disconnected AccessibilityService must not be treated as operational` | `ProtectionEnforcementTest.kt` | **PASS** |
| **INV-204** | A Device Admin state change must invalidate any stale enforcement decision | `INV-204 Device Admin state change must invalidate any stale enforcement decision` | `ProtectionEnforcementTest.kt` | **PASS** |
| **INV-205** | A service restart must reconstruct enforcement correctly | `INV-205 Service restart must reconstruct enforcement correctly without session leakage` | `ProtectionEnforcementTest.kt` | **PASS** |
| **INV-206** | Two concurrent launch events must not produce conflicting enforcement decisions | `INV-206 Two concurrent launch events must not produce conflicting enforcement decisions` | `ProtectionEnforcementTest.kt` | **PASS** |
| **INV-207** | A stale Flutter state must never permit an otherwise denied native operation | `INV-207 Stale Flutter state must never permit an otherwise denied native operation` | `ProtectionEnforcementTest.kt` / `enforcement_integrity_test.dart` | **PASS** |
| **INV-208** | An exception in a security decision path must fail closed | `INV-208 Exception in a security decision path must fail closed` | `ProtectionEnforcementTest.kt` / `enforcement_integrity_test.dart` | **PASS** |
| **INV-209** | Destroying MainActivity must not disable protection | `INV-209 Destroying MainActivity must not disable protection` | `ProtectionEnforcementTest.kt` | **PASS** |
| **INV-210** | Destroying/reconnecting AccessibilityService must not create a permanent bypass | `INV-210 Destroying and reconnecting AccessibilityService must not create a permanent bypass` | `ProtectionEnforcementTest.kt` | **PASS** |
| **INV-211** | Tamper lockout must override ordinary authorization paths | `INV-211 Tamper lockout must override ordinary authorization paths` | `ProtectionEnforcementTest.kt` | **PASS** |
| **INV-212** | Grace sessions must never outlive their defined monotonic expiry | `INV-212 Grace sessions must never outlive their defined monotonic expiry` | `ProtectionEnforcementTest.kt` | **PASS** |
| **INV-213** | Overlay dismissal must not equal authorization | `INV-213 Overlay dismissal must not equal authorization` | `ProtectionEnforcementTest.kt` | **PASS** |
| **INV-214** | Repeated foreground events must not create inconsistent overlay state | `INV-214 Repeated foreground events must not create inconsistent overlay state` | `ProtectionEnforcementTest.kt` | **PASS** |
| **INV-215** | Changing system security permissions while LockKeeper is running must eventually converge to the correct enforcement state | `INV-215 Changing system security permissions while running converges cleanly` | `ProtectionEnforcementTest.kt` | **PASS** |

---

## 2. Enforcement Path Matrix (Paths A through T)

| Path | Description | Expected Decision / Enforcement | Automated Test | Verdict |
| :--- | :--- | :--- | :--- | :--- |
| **Path A** | Normal protected app launch | `RequirePin` (`AUTH_REQUIRED_PIN`) | `Path A Normal protected app launch requires PIN` | **PASS** |
| **Path B** | Unprotected app launch | `Allowed` (`ALLOWED_UNPROTECTED_APP`) | `Path B Unprotected app launch is allowed` | **PASS** |
| **Path C** | PIN unlock | `grantAppSession` $\rightarrow$ `Allowed` (`ALLOWED_ACTIVE_SESSION`) | `Path C PIN unlock grants session allowing access` | **PASS** |
| **Path D** | Admin password authorization | `TamperAuthorizationController` verify $\rightarrow$ `Allowed` (`ALLOWED_ADMIN_GRACE`) | `Path D Admin password authorization activates grace allowing admin access` | **PASS** |
| **Path E** | Self-lock | `SelfLockSessionManager` enforces gate screen on startup/timeout | `Path E Self-lock enforces authentication on cold start and background timeout` | **PASS** |
| **Path F** | Self-lock exit | PIN verified $\rightarrow$ session granted | `Path F Self-lock exit clears lock requirement` | **PASS** |
| **Path G** | Device Admin removal | `onDisabled` calls `clearAllSessions()` | `Path G Device Admin removal invalidates sessions` | **PASS** |
| **Path H** | Accessibility disable | Operational becomes false $\rightarrow$ Health `DEGRADED` | `Path H Accessibility disable sets operational to false` | **PASS** |
| **Path I** | Accessibility disconnect | `isA11yConnected = false` $\rightarrow$ Health `DEGRADED` | `Path I Accessibility disconnect degrades health status` | **PASS** |
| **Path J** | Tamper detection | Multi-signal detection triggers structured `TamperEvent` | `Path J Tamper detection triggers structured TamperEvent` | **PASS** |
| **Path K** | Tamper lockout | 5 failed admin attempts $\rightarrow$ `Blocked` (`DENIED_TAMPER_LOCKOUT`) | `Path K Tamper lockout enforces blocking decision` | **PASS** |
| **Path L** | Grace period | 30s monotonic window permits admin modifications | `Path L Grace period grants temporary admin access` | **PASS** |
| **Path L-Exp** | Grace period expiry | At 30,001 ms, grace terminates and revokes access | `INV-212 Grace sessions must never outlive their defined monotonic expiry` | **PASS** |
| **Path M** | App process restart | Process memory cleared $\rightarrow$ zero active sessions | `Path M App process restart clears all in-memory sessions` | **PASS** |
| **Path N** | Accessibility service restart | `onDestroy` clears sessions; clean slate on reconnect | `Path N Accessibility service restart clears sessions on onDestroy` | **PASS** |
| **Path O** | Device reboot | Persisted Room DB lockout survives reboot | `Path O Device reboot preserves persisted lockout in Room` | **PASS** |
| **Path P** | MainActivity destruction | Background engine continues locking without UI | `Path P MainActivity destruction does not alter engine decision` | **PASS** |
| **Path Q** | Flutter process restart | Native service intercepts launches independently | `Path Q Flutter process restart leaves native protection active` | **PASS** |
| **Path R** | Platform-channel failure | Flutter fails closed to `unknown` and `_isLocked = true` | `Path R Platform-channel failure fails closed to unknown and locked` | **PASS** |
| **Path S** | Room DB failure | Catches SQLite error and returns `DenyUnknown` | `Path S Room database failure fails closed returning DenyUnknown` | **PASS** |
| **Path T** | Unexpected native exception | Catches throwable and returns `DenyUnknown` | `Path T Unexpected native exception fails closed returning DenyUnknown` | **PASS** |

---

## 3. Concurrency & Race Condition Validation

| Test Scenario | Concurrency Mechanism | Assertion | Verdict |
| :--- | :--- | :--- | :--- |
| **Concurrent Evaluation & Grant** | 50 asynchronous coroutines simultaneously evaluating and granting sessions | Zero race conditions, 100% valid immutable `LockDecision` instances with non-null reason/outcome | **PASS** |
| **Device Admin Deactivation during Active Session** | Session active $\rightarrow$ admin receiver clears sessions $\rightarrow$ subsequent eval requires PIN | Zero session leakage across admin state transitions | **PASS** |
| **Monotonic Clock Expiry Race** | Sampling grace status across 29,999 ms and 30,001 ms boundaries | Exact cut-off, immunity to wall-clock manipulation | **PASS** |
| **Multiple Foreground Window Triggers** | 3 successive evaluations of same package | Idempotent decisions and outcome codes | **PASS** |
