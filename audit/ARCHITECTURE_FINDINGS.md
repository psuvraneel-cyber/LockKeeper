# LockKeeper System Architecture Findings Report

**Audit Date**: September 17, 2026  
**Auditor**: Lead Security & Software Quality Auditor  
**Classification**: Highly Confidential / Read-Only Audit  
**Scope**: Structural Architecture, State Machines, Process Isolation, IPC, and OS Boundaries

---

## Executive Overview of Architectural Deficiencies

LockKeeper's architecture attempts to bridge two disparate runtime environments: a high-level UI framework (**Flutter/Dart**) and a low-level background system service layer (**Android Kotlin / Room / Accessibility / WindowManager**). 

The architectural audit reveals systemic structural vulnerabilities stemming from:
1. **The Device Admin Fallacy**: Attempting to provide enterprise kiosk/parental lock enforcement without using Device Owner (DPM) APIs.
2. **Split Brain / Dual Source of Truth**: Security states (onboarding, lockout, permissions, active sessions) are duplicated across Flutter memory, SharedPreferences, SQLite Room, and Kotlin singletons without bidirectional synchronization.
3. **Fragile IPC Contract**: MethodChannel communication is stringly typed, fail-open, unauthenticated, and lacks transactional semantics.
4. **Lifecycle Blindspots**: The architecture assumes continuous in-memory execution, breaking down when the Android OS reclaims processes or recreates Activities.

---

## The Security State Machine: Formal Reconstruction & Flaws

### Theoretical State Machine
The LockKeeper system model defines eight intended conceptual operational states:

```
[ UNCONFIGURED ] ──(Onboarding Started)──> [ PARTIAL_SETUP ]
       │                                            │
(Onboarding Complete)                      (Onboarding Complete)
       ▼                                            ▼
   [ READY ] ─────────(Service Active)────────> [ PROTECTED ]
       ▲                                            │
       │                                     (Permission Lost /
       │                                      Service Crash)
       │                                            ▼
(Admin Restore) <─── [ RECOVERING ] <─────────── [ DEGRADED ]
       │
       ▼
   [ LOCKED ] (Lockout / Tamper Detected)
       │
       ▼
  [ DISABLED ] (Admin Revocation)
```

### Architectural State Machine Vulnerabilities Identified

1. **Unreachable Recovery Transition (`DEGRADED -> RECOVERING`)**:
   When AccessibilityService is disabled by the user or terminated by the OS, the app drops into `DEGRADED` state. In theory, the app should enter `RECOVERING` and demand the Admin Password. In reality, `LockKeeperForegroundService.kt` activates silently, ignores Settings (`com.android.settings`), and never prompts the user for recovery until the user manually launches the Flutter UI.
2. **Missing `PARTIAL_SETUP` Protection Boundary**:
   If a user exits the app halfway through onboarding (after setting a PIN but before completing permissions), `isOnboardingComplete` remains `false`. However, the PIN hash is already written to disk. The app enters an ambiguous limbo state where native services ignore lock rules, yet the database contains partial credentials.
3. **Stale In-Memory State across Process Death**:
   `LockDecisionEngine.kt` stores temporary unlock sessions in `unlockedSessions: ConcurrentHashMap<String, Long>`. If the system terminates the process to reclaim RAM, this map is wiped. When restarted, all previously unlocked sessions are lost, forcing immediate re-authentication. While secure, the reverse occurs for `failedPinAttempts`: if Room updates fail or race (SEC-10), the in-memory failure count diverges from the disk.

---

## Detailed Architectural Findings Dossiers

---

### ARCH-01 — Simulation of Privileged OS Controls via UI Interception (Device Admin vs Device Owner)

- **Cross-Reference**: SEC-02, SEC-03, SEC-05
- **Severity**: CRITICAL
- **Architectural Layer**: OS Boundary / Privilege Model

#### Architectural Analysis
Android's security architecture enforces hard privilege boundaries:
- **Standard Application**: Runs in a sandboxed UID. Can display windows, query basic APIs.
- **Device Administrator**: Can enforce screen lock policies, wipe device, disable camera. **Cannot** block uninstallation, cannot prevent user from deactivating administrator status.
- **Device Owner (Work Managed)**: Can call `DevicePolicyManager.setUninstallBlocked()`, `UserManager.DISALLOW_APPS_CONTROL`, `UserManager.DISALLOW_UNINSTALL_APPS`, and silently manage runtime permissions.
- **Accessibility Service**: An assistive API designed for users with disabilities to inspect UI nodes and inject gestures.

LockKeeper attempts to simulate **Device Owner** protections using an **Accessibility Service** coupled with a **Device Administrator**. This architectural decision produces severe systemic vulnerabilities:
1. It relies on the UI node hierarchy matching expected layout patterns.
2. It breaks when the device language changes (SEC-01).
3. It breaks when the user accesses management flows via alternative packages or launchers (SEC-03).
4. It can be disabled instantly if the user navigates Settings faster than the accessibility event dispatch loop (~50–100ms latency).

#### Architectural Recommendation
Architectural documentation must formally state that LockKeeper operates as a *heuristic consumer app locker*, not a cryptographically enforced OS sandbox. For true tamper-proof deployments (schools, enterprise, parental control), a dedicated `DeviceOwnerProvisioningEngine` must be built that registers LockKeeper as the device owner during initial device setup.

---

### ARCH-02 — Dual Source of Truth and State Desynchronization Between Flutter and Kotlin

- **Cross-Reference**: SEC-06, SEC-24
- **Severity**: HIGH
- **Architectural Layer**: Cross-Runtime State Architecture

#### Architectural Analysis
The application maintains two independent state graphs:
1. **Dart State**: Flutter widgets query `PlatformBridge` once on startup, cache booleans in widget fields (`widget.isOnboardingComplete`, `_isLocked`), and rely on `didChangeAppLifecycleState` for refresh.
2. **Native State**: Room database (`AppDatabase.kt`), SharedPreferences (`lockkeeper_prefs.xml`), and in-memory singletons (`LockKeeperAccessibilityService.instance`, `LockDecisionEngine`).

Because there is no active reactive event stream (e.g., `EventChannel` or WebSocket/Flow) streaming native lifecycle changes to Flutter:
- When permissions change in Android Settings, Flutter does not know until the app is backgrounded and resumed.
- When onboarding completes in Flutter, the native process is notified via an asynchronous `invokeMethod`, but Flutter's own root widget tree is never invalidated (SEC-06), leaving the Dart runtime in an unconfigured state.
- When `AccessibilityService` crashes, `PlatformChannelHandler` reports `true` based on `Settings.Secure` string contents (SEC-24), causing Flutter to render an active green status icon while the native service is dead.

#### Architectural Recommendation
Replace pull-based, polling platform channel methods with a unified reactive stream (`EventChannel` or Kotlin `SharedFlow` bridged to Dart `Stream`):
```kotlin
// Native Kotlin Flow
val securityStatusFlow: Flow<SecurityHealthState>
```
Flutter must bind its presentation layer to a reactive state manager (`Riverpod` or `Bloc`) listening to this event channel.

---

### ARCH-03 — Untyped, Fail-Open IPC Architecture (MethodChannel)

- **Cross-Reference**: SEC-07, SEC-19, SEC-23
- **Severity**: HIGH
- **Architectural Layer**: Platform Bridge / IPC

#### Architectural Analysis
The platform channel interface `com.lockkeeper.app/bridge` exposes over 25 distinct methods. The architecture exhibits three major design flaws:
1. **Stringly-Typed Payloads**: Method names and argument maps are bare strings without schema generation (such as Pigeon). Typos or missing arguments fail silently at runtime.
2. **Universal Fail-Open Error Handling**: In `platform_bridge.dart`, every call is wrapped in a catch block that returns default permissive values (`false` for lock status, allowing entry).
3. **No IPC Authentication or Context Verification**: Any component running within the hosting activity can invoke any method. Sensitive methods like `setAdminPassword` and `resetAllSettings` do not require an active session token or cryptographic signature.

#### Architectural Recommendation
1. Migrate the platform bridge from manual `MethodChannel` to **Pigeon**, which generates type-safe, compile-time-verified Dart and Kotlin bindings.
2. Enforce fail-closed design: if an IPC call fails or times out, the system must assume the most secure state (e.g., lock the app, deny navigation).
3. Introduce an ephemeral `SessionToken` required by sensitive bridge calls (such as changing passwords or clearing database tables).

---

### ARCH-04 — Database Concurrency and Transactional Integrity

- **Cross-Reference**: SEC-10, SEC-18
- **Severity**: HIGH
- **Architectural Layer**: Persistence Layer / Room Database

#### Architectural Analysis
The persistence layer uses Room atop SQLite. While SQLite provides ACID guarantees at the database engine level, LockKeeper's repository layer violates transactional boundaries:
1. **Non-Transactional Read-Modify-Write**: As documented in SEC-10, operations such as checking and incrementing `failedPinAttempts` execute across multiple non-transactional DAO calls.
2. **Dangerous Fallbacks in Production**: `AppDatabase.kt` enables `fallbackToDestructiveMigration()`. Any schema discrepancy in an app update results in Room executing `DROP TABLE`, obliterating all user data and security rules.
3. **Disabled Schema Export**: `exportSchema = false` prevents automated migration testing and schema tracking in version control.

#### Architectural Recommendation
1. Enforce Room `@Transaction` blocks across all composite read-modify-write repository methods.
2. Disable `fallbackToDestructiveMigration()` immediately.
3. Enable `exportSchema = true` and establish an explicit migration registry (`RoomDatabase.addMigrations()`).

---

### ARCH-05 — Lifecycle Vulnerabilities and Process Survival

- **Cross-Reference**: SEC-11, SEC-13, SEC-15
- **Severity**: HIGH
- **Architectural Layer**: Android OS Process Lifecycle

#### Architectural Analysis
Android's process model prioritizes foreground user experiences and aggressively terminates background processes under memory pressure. LockKeeper's architecture makes hazardous assumptions about process longevity:
1. **BootReceiver Execution Window**: Launching coroutines on `Dispatchers.IO` without calling `goAsync()` (SEC-11) results in process death before the service can start.
2. **AccessibilityService Connection Guarantees**: The architecture assumes that if Accessibility is turned ON, `LockKeeperAccessibilityService` is running. In practice, Android regularly unbinds accessibility services during system UI reloads or high-memory events without revoking the user's permission toggle.
3. **Foreground Service Fallback Drain**: The fallback mechanism resorts to unthrottled 400ms polling, which triggers Android power-management kills (SEC-15).

#### Architectural Recommendation
1. Use `goAsync()` in all BroadcastReceivers performing I/O.
2. Use Android `WorkManager` with `PeriodicWorkRequest` as a secondary watchdog to verify that either Accessibility or ForegroundService is operational.
3. Replace tight-loop polling with event-based broadcast listeners (`USAGE_STATS` query throttled by `ACTION_USER_PRESENT` and screen state).
