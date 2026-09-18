# WAVE 5: UI STATE MACHINE AUDIT
## COMPLETE SCREEN-BY-SCREEN STATE MACHINE & INVARIANT VERIFICATION

---

### 1. Executive Summary

This audit rigorously evaluates the state models of all core screens in LockKeeper:
1. `LockKeeperApp` (Root Gate & Initialization)
2. `HomeScreen` (Dashboard & Protection Monitoring)
3. `OnboardingScreen` (Guided Initial Provisioning)
4. `SelfLockGateScreen` (Internal Application Shield)
5. `SettingsScreen` (Security Management & Recovery Hub)
6. `AppDetailScreen` (Per-App Lock & Cooldown Configuration)

Each screen is analyzed for:
- **Entry States**
- **Valid States**
- **Invalid / Impossible States**
- **Transitions**
- **Exit Conditions**
- **Recovery Conditions**
- **Dead-End & Inconsistency Remediations**

---

### 2. Screen State Machine Analysis

#### Screen 1: `LockKeeperApp` (Root Router & Initialization)
- **Entry States:**
  - `E1`: Cold start from launcher.
  - `E2`: Warm start from task switcher.
  - `E3`: Intent launch / deep-link.
- **Valid States:**
  - `UNINITIALIZED`: Splash placeholder displayed while querying native security health (prevents visual flash).
  - `ONBOARDING_REQUIRED`: `onboardingComplete == false` and `recoveryRequired == false`.
  - `SELF_LOCKED`: `isLocked == true`, `SelfLockGateScreen` displayed over child content.
  - `UNLOCKED`: Child screen (`HomeScreen` or `OnboardingScreen`) visible and interactive.
  - `RECOVERY_FORCED`: `recoveryRequired == true`.
- **Invalid / Forbidden States:**
  - ❌ `UNINITIALIZED` presenting content before security verification completes.
  - ❌ `ONBOARDING_REQUIRED` active while `recoveryRequired == true` (remediated in Wave 2/Wave 5).
  - ❌ `UNLOCKED` when `checkSelfLockRequired()` returns true or throws a channel exception (fail-closed rule).
- **Transitions:**
  - `Cold Start` -> Query `PlatformBridge.getProtectionStatus()`.
  - If `recoveryRequired == true`: Route directly to `HomeScreen` (with Recovery banner).
  - Else if `!onboardingComplete`: Route to `OnboardingScreen`.
  - Else: Query `checkSelfLockRequired()`. If true -> Mount `SelfLockGateScreen`; If false -> Show `HomeScreen`.
- **Exit Conditions:**
  - Process killed by OS or user back button on launcher.
- **Recovery Conditions:**
  - Platform channel failure defaults safely to `_isLocked = true` (Fail-Closed).

---

#### Screen 2: `HomeScreen` (Dashboard & Monitoring)
- **Entry States:**
  - Navigation from `LockKeeperApp` (either post-unlock or direct recovery routing).
- **Valid States:**
  - `LOADING`: Circular progress indicator while fetching apps and status.
  - `PROTECTED`: Shield green (`#10B981`), all permissions operational, protected apps active.
  - `CONFIGURED`: Shield slate (`#64748B`), permissions active, 0 apps locked.
  - `DEGRADED`: Shield amber (`#F59E0B`), yellow banner displaying exact missing permission/service reason.
  - `RECOVERY_REQUIRED`: Shield red (`#EF4444`), prominent red recovery card with actionable "Recover" button.
  - `FILTERED_VIEW`: App list filtered by "All", "Locked", "Unlocked", or search text.
- **Invalid / Forbidden States:**
  - ❌ Displaying `PROTECTED` when any critical permission (Accessibility, Overlay, Device Admin) is inactive.
  - ❌ Displaying `CONFIGURED` when `recoveryRequired == true`.
  - ❌ App search returning hidden system components or broken package names.
- **Transitions:**
  - Tap "Recover" -> Pushes `SettingsScreen` -> Returns and calls `_loadData()`.
  - Tap "Retry" on degraded banner -> Re-polls native state.
  - Tap Lock toggle on app row -> Calls `PlatformBridge.setLockedApp()` -> Immediate optimistic + native DB update.
  - Tap Settings icon -> Pushes `SettingsScreen`.
- **Exit Conditions:**
  - Back button exits to Android launcher.
- **Recovery Conditions:**
  - Real-time `PlatformBridge.eventsStream` listener re-syncs state immediately on any native broadcast.

---

#### Screen 3: `OnboardingScreen` (Provisioning Wizard)
- **Entry States:**
  - App launched when `onboardingComplete == false` and `recoveryRequired == false`.
- **Valid States:**
  - `STEP_0` (Introduction)
  - `STEP_1` (PIN Setup: 4-8 digits, numeric verification)
  - `STEP_2` (Admin Password Setup: >= 4 chars, distinct from PIN)
  - `STEP_3` (Device Admin Activation)
  - `STEP_4` (Accessibility Service Activation)
  - `STEP_5` (Overlay Permission Grant)
  - `STEP_6` (Usage Stats Access Grant)
  - `STEP_7` (Battery Optimization Exemption)
  - `STEP_8` (Completion & Start Services)
- **Invalid / Forbidden States:**
  - ❌ Admin password identical to user PIN (blocked by validation logic).
  - ❌ Advancing past Step 1 or 2 with empty or non-matching credentials.
  - ❌ Onboarding executing while Device Admin is active without local credentials (`RECOVERY_REQUIRED`).
- **Transitions:**
  - User completes current step -> `_nextStep()`.
  - Lifecycle `resumed` -> Calls `_refreshPermissions()` to automatically advance granted permissions.
  - Step 8 finish -> `setOnboardingComplete(true)` -> `startProtectionService()` -> Replaces route with `HomeScreen`.
- **Exit Conditions:**
  - Completion transitions to `HomeScreen`. Back button exits to launcher.
- **Recovery Conditions:**
  - If `status.recoveryRequired` is detected during onboarding resume, immediately routes to `HomeScreen`.

---

#### Screen 4: `SelfLockGateScreen` (Internal Application Shield)
- **Entry States:**
  - Triggered by `LockKeeperApp` when timeout elapses or background transition occurs.
- **Valid States:**
  - `AWAITING_INPUT`: Virtual keypad enabled; PIN dots reflect buffer length.
  - `VERIFYING`: Input locked while checking hash via native Keystore.
  - `SHAKE_ERROR`: Wrong PIN entered; keypad shakes; failed attempts displayed.
  - `LOCKED_OUT`: 5 consecutive wrong PINs; countdown timer active; keypad disabled.
- **Invalid / Forbidden States:**
  - ❌ Pop navigation bypassing lock (prevented via `PopScope(canPop: false)`).
  - ❌ Auto-unlock on timeout without valid PIN verification.
  - ❌ Keypad active during lockout timer.
- **Transitions:**
  - Enter valid PIN -> Haptic feedback -> `widget.onUnlocked()` called -> Gate removed.
  - Enter wrong PIN -> Failed attempts incremented -> If attempts >= 5, triggers `_startLockout()`.
- **Exit Conditions:**
  - Only valid PIN entry removes the gate overlay.
- **Recovery Conditions:**
  - Lockout survives app restart by querying persisted SQLite lockout timestamp.

---

#### Screen 5: `SettingsScreen` (Security & Recovery Hub)
- **Entry States:**
  - Navigated from `HomeScreen` via toolbar button or "Recover" banner action.
- **Valid States:**
  - `NORMAL_MANAGEMENT`: Manage self-lock toggle, timeouts, and view permission health.
  - `CREDENTIAL_CHANGE`: Enter current PIN/Password to change credentials.
  - `CREDENTIAL_SET`: Set new credentials directly when previously missing.
  - `RECOVERY_MODE`: `recoveryRequired == true`; dedicated Red Recovery banner visible with `_showRecoveryCredentialsDialog()`.
- **Invalid / Forbidden States:**
  - ❌ Prompting for "Current PIN" when local PIN is missing (`hasPin == false`).
  - ❌ Disabling self-lock without Admin Password authorization.
  - ❌ Remaining in `RECOVERY_REQUIRED` after successfully setting new credentials.
- **Transitions:**
  - Toggle Self-Lock off -> Prompts for Admin Password -> Disables only if authorized.
  - Tap "Re-configure PIN & Credentials" -> Opens `_showRecoveryCredentialsDialog()`.
  - Enter new PIN + Admin Pass -> Calls `PlatformBridge.resolveRecovery()` -> Reconciles state -> Clears banner.
- **Exit Conditions:**
  - Back navigation returns to `HomeScreen` and invokes `_loadData()`.
- **Recovery Conditions:**
  - Direct atomic recovery resolution clears native `recoveryRequired` and updates `app_settings` row 1.

---

#### Screen 6: `AppDetailScreen` (Per-App Lock Configuration)
- **Entry States:**
  - Tapped specific app row in `HomeScreen`.
- **Valid States:**
  - `VIEWING_CONFIG`: View app name, icon, lock status, and cooldown duration.
  - `EDITING_COOLDOWN`: Select preset (15m, 1h, 4h, 24h) or custom minute duration.
  - `SAVING`: Asynchronously saving configuration to Room database.
- **Invalid / Forbidden States:**
  - ❌ Setting negative or extreme (> 7 days) cooldown durations.
  - ❌ Crashing when app icon or label is null.
- **Transitions:**
  - Toggle lock or change cooldown -> Tap Save -> Calls `PlatformBridge.setLockedApp()` -> Pops with `true`.
- **Exit Conditions:**
  - Save or Cancel pops back to `HomeScreen`.
- **Recovery Conditions:**
  - Database upsert guarantees configuration persistence even if task is killed immediately after save.

---

### 3. Dead-End & Contradiction Resolution Summary

| Screen | Discovered Gap / Edge Case | Remediation Applied | Invariant Preserved |
| :--- | :--- | :--- | :--- |
| **SettingsScreen** | `_showChangePinDialog` requested "Current PIN" during `RECOVERY_REQUIRED` when PIN was null | Implemented `_showRecoveryCredentialsDialog()` and adaptive "Set" vs "Change" logic | **INV-202 / INV-501** |
| **SettingsScreen** | Recovery required banner remained active after credentials were restored | Implemented `resolveRecovery()` clearing `recoveryRequired` in Room DB and sync | **INV-502** |
| **HomeScreen** | Stale status badge displayed after returning from Android Settings | Bound `didChangeAppLifecycleState` and `eventsStream` to auto-refresh data | **INV-108** |
| **SelfLockGateScreen** | Android Back gesture could close Flutter activity and drop lock | Added strict `PopScope(canPop: false)` | **INV-302** |
| **LockKeeperApp** | Screen flashed white/unlocked while asynchronous lock check was resolving | Added dark splash background container during initial resolution | **INV-301** |

---

### 4. Audit Verdict: PASS

The UI state machine is fully synchronized with native truth. All screen states, transitions, error conditions, and recovery workflows resolve deterministically without dead-ends or contradictory presentations.
