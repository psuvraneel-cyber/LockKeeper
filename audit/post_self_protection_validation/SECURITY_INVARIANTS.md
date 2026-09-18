# Security Invariants Analysis

**Project:** LockKeeper (C:\AppLocker)  
**Phase:** Post-Self-Protection Adversarial Validation  
**Date:** 2026-09-17  
**Validator:** Agent 15 (Lead Security Validation Engineer)

---

## 1. Evaluation Methodology

Security invariants represent non-negotiable axioms that must hold true across all system states, thread interleavings, lifecycle events, and user interactions.

Each invariant is evaluated and classified as:
- **PASS**: Formally verified and guaranteed by code implementation.
- **FAIL**: Statically disproven or violated by an identified attack path.
- **UNKNOWN**: Cannot be proven without physical runtime device testing.

---

## 2. Invariant Scorecard

| Invariant ID | Definition | Verdict | Evidence / Counterexample |
|---|---|---|---|
| **INVARIANT 1** | A service disconnect must not be treated as permission revocation. | **PASS** | `PlatformChannelHandler.kt:250` checks `enabledServices` in Settings; disconnect does not clear OS permission. |
| **INVARIANT 2** | A Device Admin Activity being closed must not make Device Admin inactive. | **PASS** | `isAdminActive` queries live `DevicePolicyManager.isAdminActive()`. |
| **INVARIANT 3** | A correct Admin Password must not create permanent authorization. | **PASS** | Monotonic grace window strictly expires after 30 seconds (`TamperAuthorizationController.kt:35, 50`). |
| **INVARIANT 4** | A lockout must survive process restart. | **FAIL** | If SQLite row `id = 1` is missing, `UPDATE` modifies 0 rows, so lockout never persists. Also bypassed by setting clock forward 5 minutes. |
| **INVARIANT 5** | Unauthorized tamper attempts must not silently transition into authorized state. | **PASS** | Session terminates with `authorized = false` when dismissed or cancelled. |
| **INVARIANT 6** | General Accessibility Settings must never be blocked merely because LockKeeper appears in the service list. | **PASS** | `TamperDetectionEngine.kt:120` checks `ToggleAccessibilityServicePreferenceFragment` and toggle keywords; `MiuiAccessibilitySettingsActivity` is whitelisted. |
| **INVARIANT 7** | Unrelated apps must never trigger LockKeeper's anti-tamper password gate. | **FAIL** | `TamperDetectionEngine.kt:142`: `isDeviceAdminScreen && (mentionsLockKeeper \|\| hasDeactivateAction)` triggers on deactivating Google Find My Device. |
| **INVARIANT 8** | Overlay cleanup must occur whenever its target context disappears. | **FAIL** | If service is killed while overlay is displayed, `onDestroy()` does not dismiss the overlay (`LockKeeperAccessibilityService.kt:59-63`), permanently trapping the screen. |
| **INVARIANT 9** | A missing application database must never silently be interpreted as "security never configured" when external security state indicates otherwise. | **FAIL** | Clearing app storage wipes DB and Prefs; `shouldProtectSettings()` drops to `false` despite active Device Admin. |
| **INVARIANT 10** | The UI must never display stronger security status than native state supports. | **FAIL** | `PlatformChannelHandler.kt:250`: reports Accessibility as granted when service is disconnected or dead; home screen claims "Protection Operational". |
| **INVARIANT 11** | Rate-limiting attempt counters must be atomic and immune to zero-row updates. | **FAIL** | `appSettingsDao.updateAdminLockout` fails silently when row 1 is missing; non-atomic read-modify-write in coroutines. |
| **INVARIANT 12** | Admin lockout must not be revocable by device wall-clock adjustments. | **FAIL** | `lockoutUntil > now` uses `System.currentTimeMillis()`. Setting clock forward 5 minutes wipes lockout immediately. |
| **INVARIANT 13** | An active overlay must never be dismissed by intermediate window events within the protected package. | **FAIL** | `LockKeeperAccessibilityService.kt:111`: null evaluation during soft keyboard popup calls `dismissAdminOverlay()`. |
| **INVARIANT 14** | Release builds must protect security logic against static decompilation. | **FAIL** | `build.gradle.kts:55`: `isMinifyEnabled = false`. ProGuard/R8 completely disabled. |

---

## 3. Invariant Failure Deep-Dive

### Invariant 4: Lockout Persistence Survival
- **Axiom:** *"A lockout must survive process restart."*
- **Failure Mechanism:**
  1. If `app_settings` row 1 does not exist, `updateAdminLockout` updates 0 rows in SQLite. Lockout is only held in the transient memory of `verifyAdminPassword`. Killing the process wipes it completely.
  2. Even if row 1 exists and `adminLockoutUntil` is written to SQLite, it is stored as a wall-clock timestamp (`System.currentTimeMillis() + 300_000L`). Changing the device clock forward by 5 minutes invalidates the lockout immediately upon process restart.
- **Classification:** **FAIL**.

### Invariant 7: Cross-App Isolation
- **Axiom:** *"Unrelated apps must never trigger LockKeeper's anti-tamper password gate."*
- **Failure Mechanism:**
  `TamperDetectionEngine.kt:142`:
  ```kotlin
  if (isDeviceAdminScreen && (mentionsLockKeeper || hasDeactivateAction))
  ```
  The expression `(mentionsLockKeeper || hasDeactivateAction)` allows `hasDeactivateAction` alone to satisfy the condition when `isDeviceAdminScreen` is true. When a user deactivates an unrelated admin app (e.g., Samsung Knox, Find My Device, Microsoft Authenticator), `hasDeactivateAction` is true, causing LockKeeper to display its own Admin Password overlay over the other app.
- **Classification:** **FAIL**.

### Invariant 8: Deterministic Overlay Teardown
- **Axiom:** *"Overlay cleanup must occur whenever its target context disappears."*
- **Failure Mechanism:**
  When `LockKeeperAccessibilityService` is unbound or killed by the OS (`onDestroy`), it sets `isConnected = false` and cancels jobs. It never invokes `windowManager.removeView()`. The overlay window remains visible on the display with no active service backing it.
- **Classification:** **FAIL**.

### Invariant 9: Missing Database Defense
- **Axiom:** *"A missing application database must never silently be interpreted as 'security never configured' when external security state indicates otherwise."*
- **Failure Mechanism:**
  When storage is cleared, `AppSettingsEntity` is lost. `shouldProtectSettings()` calls:
  `isOnboardingCompleteSync() && credentialStore.hasAdminPassword()`.
  Both are `false`. Anti-tamper is disabled. The application fails to detect that `DevicePolicyManager.isAdminActive()` is still `true`, allowing the user to deactivate Device Admin without a password.
- **Classification:** **FAIL**.

### Invariant 10: Truth in Security State
- **Axiom:** *"The UI must never display stronger security status than native state supports."*
- **Failure Mechanism:**
  `PlatformChannelHandler.kt:250` considers Accessibility granted if `enabledServices.contains(context.packageName)` is true, even when `isServiceConnected` is `false`. The UI renders a green badge: *"Protection Operational"*, when zero protection is actually executing.
- **Classification:** **FAIL**.
