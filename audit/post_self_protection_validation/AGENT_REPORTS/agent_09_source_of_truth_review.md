# Agent 09: Security State & Source-of-Truth Auditor Report

**Date:** 2026-09-17  
**Auditor:** Agent 09 — Security State & Source-of-Truth Specialist  
**Target:** Multi-Layer Security State Architecture across Flutter, Room, Prefs, Keystore, and Native Memory  
**Status:** COMPLETE (Hostile Cross-Layer State Analysis)

---

## 1. Executive Summary

This audit evaluated LockKeeper's security state model across all storage, runtime, IPC, and platform layers to identify state desynchronization, stale caches, split-brain scenarios, and unauthorized state transitions.

**Critical Findings:**
1. **Split-Brain on `onboardingComplete`:**
   Stored simultaneously in SQLite (`app_settings.onboardingComplete`) and SharedPreferences (`lockkeeper_protection_prefs["onboarding_complete"]`).
   - Fast path `isOnboardingCompleteSync()` reads SharedPreferences.
   - Flutter and Database read SQLite.
   - If SharedPreferences is wiped or fails to sync during setup, SQLite says `true` while the fast path says `false`. This causes `shouldProtectSettings()` to return `false`, disabling anti-tamper while App Lock remains active!
2. **False Operational State (Layer Disconnect):**
   `checkPermission("accessibility")` in `PlatformChannelHandler.kt:250` checks:
   `isServiceConnected || enabledServices.contains(context.packageName)`.
   If the service crashed or was killed by Android's low-memory killer, `isServiceConnected` is `false`, but `enabledServices` remains `true`. Flutter displays "Protection Operational" with all green checks, but native enforcement is completely dead.
3. **Ghost State on Clear-Data (Total Protection Demolition):**
   The documented `RECOVERY_REQUIRED` state is completely missing from code. When storage is cleared, `app_settings` and SharedPreferences vanish. LockKeeper boots as `UNCONFIGURED`, abandoning all active Device Admin and Accessibility hooks without prompting for recovery.
4. **Dual Grace Window Asynchrony:**
   Two independent grace timers exist: `TamperAuthorizationController.adminGraceUntilElapsed` and `LockDecisionEngine.adminGraceUntil`. Revoking one does not revoke the other.

---

## 2. State Dependency & Authority Graph

```
[Android OS / Framework]
  ├── DevicePolicyManager.isAdminActive() -------> (Authoritative for Device Admin)
  ├── Settings.canDrawOverlays() -----------------> (Authoritative for Overlay Perm)
  ├── AppOpsManager (Usage Stats) ---------------> (Authoritative for Usage Perm)
  ├── Settings.Secure.ENABLED_ACCESSIBILITY -----> (Authoritative for OS A11y Setting)
  └── AccessibilityService Binder --------------> (Authoritative for Live Service Connection)

[Android KeyStore / CredentialStore]
  ├── LockKeeperMasterKey_v1 (Hardware Key)
  ├── lockkeeper_credentials.xml:
        ├── cred_pin_blob -----------------------> (Authoritative for PIN)
        ├── cred_pin_len ------------------------> (Authoritative for PIN Length)
        └── cred_admin_blob ---------------------> (Authoritative for Admin Password)

[Room SQLite Database: lockkeeper_database]
  ├── Table: locked_apps ------------------------> (Authoritative for App Protection Rules)
  └── Table: app_settings (Row 1):
        ├── failedPinAttempts
        ├── pinLockoutUntil ---------------------> (Authoritative for PIN Lockout)
        ├── failedAdminAttempts
        ├── adminLockoutUntil -------------------> (Authoritative for Admin Lockout)
        ├── onboardingComplete ------------------> (Intended Authoritative for Onboarding)
        ├── selfLockEnabled
        └── selfLockTimeoutSeconds

[SharedPreferences: lockkeeper_protection_prefs]
  └── onboarding_complete -----------------------> (Cached Fast-Path; CAUSES SPLIT-BRAIN)

[In-Memory Native Runtime State]
  ├── LockKeeperAccessibilityService.isConnected
  ├── TamperAuthorizationController.adminGraceUntilElapsed (Monotonic timer)
  ├── TamperAuthorizationController.activeSession
  ├── LockDecisionEngine.adminGraceUntil (Wall-clock timer; DUPLICATE!)
  ├── LockDecisionEngine.activeSessions (ConcurrentHashMap)
  └── SelfLockSessionManager.isSessionActive

[Flutter UI Memory Engine]
  └── ProtectionStatusModel (Periodically polled via MethodChannel; CACHED & STALE)
```

---

## 3. Layer-by-Layer State Synchronization Matrix

| Security Variable | Authoritative Source | Cached / Derived Sources | Stale State Window | Behavior on Process Death | Behavior on Clear Data |
|---|---|---|---|---|---|
| **Onboarding Complete** | SQLite `app_settings` | `lockkeeper_protection_prefs` | Indefinite if async write fails. | Preserved in DB & Prefs. | **WIPED** (Becomes `false`). |
| **Admin Password** | `lockkeeper_credentials.xml` (Encrypted) | None | None | Preserved in XML. | **WIPED** (Becomes missing). |
| **User PIN** | `lockkeeper_credentials.xml` (Encrypted) | None | None | Preserved in XML. | **WIPED** (Becomes missing). |
| **Admin Lockout** | SQLite `app_settings` | `TamperAuthorizationController` | Fail-open if row 1 missing. | Preserved in DB (if persisted). | **WIPED** (Lockout cleared). |
| **Admin Grace** | In-Memory (`SystemClock`) | `LockDecisionEngine` (Wall-clock) | 30s window desync. | **WIPED** (Grace revoked). | **WIPED**. |
| **A11y Connected** | `LockKeeperAccessibilityService.isConnected` | `PlatformChannelHandler` | While UI is open. | **WIPED** (Becomes `false`). | Irrelevant. |
| **A11y Protection Active** | Live calculation (`shouldProtectSettings`) | Flutter UI | Until next manual poll. | Restarts on service bound. | **DISABLED**. |

---

## 4. Conflict Scenarios: Layer A vs Layer B

### Conflict 1: Flutter Claims "Protected", Native Engine is DEAD
- **Trigger:** Android OS stops `LockKeeperAccessibilityService` in background due to battery optimization.
- **Layer A (Flutter UI):** Calls `checkPermission("accessibility")`. Method checks:
  `isServiceConnected || enabledServices.contains(context.packageName)`.
  Because the switch in Android Settings is ON, this returns `true`.
  Flutter displays: **"Protected" (Green)**.
- **Layer B (Native Execution):** Service is destroyed (`isConnected == false`). No accessibility events are received. Apps are not locked. Tamper attempts are not intercepted.
- **Result:** Dangerous illusion of security. The user assumes their apps and settings are protected, but any party can open or uninstall them freely.

### Conflict 2: Storage Cleared while Device Admin Remains Active
- **Trigger:** Malicious user clears storage via ADB, alternate route, or OEM utility.
- **Layer A (OS Framework):** Device Admin is still ACTIVE (`dpm.isAdminActive == true`). Accessibility Service is still ENABLED in Settings.
- **Layer B (LockKeeper Native Engine):**
  - Database is wiped (`app_settings` does not exist).
  - SharedPreferences is wiped (`onboarding_complete == false`, `hasAdminPassword == false`).
  - `shouldProtectSettings()` returns `false`!
  - LockKeeper treats the device as a fresh install.
  - User can now open `Settings -> Device Admin Apps` and deactivate LockKeeper with ZERO password required!
- **Result:** Complete bypass of the uninstallation deterrence architecture.

### Conflict 3: Dual Grace Window Split
- **Trigger:** Admin Password entered correctly in `AdminOverlayView`.
- **Layer A (`TamperAuthorizationController`):** Sets `adminGraceUntilElapsed = SystemClock.elapsedRealtime() + 30_000L`.
- **Layer B (`LockDecisionEngine`):** Sets `adminGraceUntil = System.currentTimeMillis() + 30_000L`.
- **Execution:**
  - If user cancels an action, `tamperController.revokeGraceWindow()` is called.
  - `adminGraceUntilElapsed` is set to 0.
  - BUT `decisionEngine.adminGraceUntil` is NOT reset!
  - `LockKeeperAccessibilityService.kt:157`:
    `if (repository.tamperController.isGraceActive() || repository.decisionEngine.isAdminGraceActive())`
  - Because of the `||` operator, `decisionEngine.isAdminGraceActive()` returns `true` for the remaining wall-clock seconds, allowing unauthorized bypass!

---

## 5. Invariant Evaluation

| Invariant | Description | Result | Evidence |
|---|---|---|---|
| **INV-9** | Missing DB must never silently be interpreted as unconfigured when external state indicates otherwise | **FAIL** | Clearing data drops `shouldProtectSettings()` to `false` despite active Device Admin. |
| **INV-10** | UI must never display stronger security status than native state supports | **FAIL** | `checkPermission("accessibility")` reports `true` when service is disconnected. |

---

## 6. Auditor Conclusion

LockKeeper's state model lacks an authoritative single source of truth:
- It splits onboarding state across SharedPreferences and Room.
- It conflates OS accessibility enablement with live service connectivity.
- It completely lacks the claimed `RECOVERY_REQUIRED` state machine, causing catastrophic collapse of all defenses upon storage clearing.
