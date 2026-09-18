# Agent 10: Concurrency, TOCTOU & Race Condition Auditor Report

**Date:** 2026-09-17  
**Auditor:** Agent 10 — Concurrency, TOCTOU & Race Specialist  
**Target:** Multi-Threaded Execution across AccessibilityService, OverlayManager, CoroutineScopes, and Room  
**Status:** COMPLETE (Hostile Concurrent Execution Modeling)

---

## 1. Executive Summary

This audit modeled hostile race conditions, concurrent thread interleavings, and Time-of-Check to Time-of-Use (TOCTOU) vulnerabilities across LockKeeper's asynchronous boundaries.

**Critical Findings:**
1. **Parallel Password Verification TOCTOU (Rate-Limit Bypass):**
   `TamperAuthorizationController.verifyAdminPassword` is a suspending function lacking mutual exclusion. It reads `failedAdminAttempts`, executes PBKDF2 (~100ms), and writes the incremented value. Simultaneous calls result in lost increments, allowing attackers to attempt dozens of passwords while the counter advances by only 1.
2. **Overlay Display / Dismissal Asynchronous Desynchronization:**
   Overlay show and dismiss operations are dispatched via `mainHandler.post`. When a user rapidly switches activities, `attachOverlay` and `removeCurrentOverlayInternal` can interleave out of order, causing WindowManager leaks or destroying newly attached security overlays.
3. **Session State Mutation Race:**
   `TamperAuthorizationController.startSession` and `endSession` mutate `activeSession` without synchronization. Two concurrent accessibility events can both observe `activeSession == null` and both start sessions, overwriting callbacks.
4. **Success vs Lockout Collision:**
   A slow successful password entry racing with a rapid incorrect attempt can overwrite the reset state, leaving a legitimately authenticated user locked out.

---

## 2. Race Condition Model Catalog

---

### RACE-01: Parallel Password Verification Rate-Limit Defeat
- **Classification:** **CONFIRMED VULNERABILITY**
- **Trigger:** Attacker scripts concurrent `verifyAdminPassword` calls via MethodChannel or rapid double-tap on `AdminOverlayView` submit button.
- **Interleaving:**
  ```
  Thread A (Attempt 1: "wrongA")           Thread B (Attempt 2: "wrongB")
  ---------------------------------       ---------------------------------
  1. reads failedAdminAttempts = 0
                                          2. reads failedAdminAttempts = 0
  3. begins PBKDF2 (100ms)                4. begins PBKDF2 (100ms)
  5. finishes PBKDF2 (invalid)
  6. computes newAttempts = 1
  7. writes failedAdminAttempts = 1
                                          8. finishes PBKDF2 (invalid)
                                          9. computes newAttempts = 1
                                          10. writes failedAdminAttempts = 1
  ```
- **Incorrect State:** Two incorrect attempts were processed, but the database recorded only 1 failed attempt.
- **Impact:** Attacker can multiply the 5-attempt limit by the number of concurrent threads, brute-forcing passwords with near impunity.
- **Mitigation Direction:** Guard `verifyAdminPassword` with a `Mutex` or synchronize the method, and perform atomic SQL counter increments (`UPDATE app_settings SET failedAdminAttempts = failedAdminAttempts + 1`).

---

### RACE-02: Show Overlay vs Dismiss Overlay Interleaving
- **Classification:** **CONFIRMED VULNERABILITY**
- **Trigger:** Rapid window switching between Settings App Info and Launcher.
- **Interleaving:**
  ```
  Main Thread / Handler Queue             Accessibility Event Thread
  ---------------------------------       ---------------------------------
  1. Queue: [Task A: showAdminOverlay]
                                          2. User leaves Settings -> dismissAdminOverlay() posted
  3. Queue: [Task A: showAdminOverlay, Task B: dismissAdminOverlay]
  4. Task A runs: attaches AdminView
  5. Task B runs: removes AdminView
                                          6. User re-enters Settings within 5ms -> showAdminOverlay() posted
                                          7. Task C: dismissIfShowing(launcher) posted from previous exit!
  8. Task C runs: tears down new AdminView!
  ```
- **Incorrect State:** The security overlay is dismissed over the sensitive screen because a delayed dismissal from a prior window transition executed late.
- **Impact:** The sensitive screen is left completely exposed without an overlay.
- **Mitigation Direction:** Tag overlays with unique monotonically increasing generation IDs or tokens; ignore dismissal tasks whose token does not match the active session.

---

### RACE-03: `activeSession` Start / End Collision
- **Classification:** **HIGH CONFIDENCE**
- **Trigger:** Two accessibility events arrive concurrently from distinct window state changes.
- **Code Reference:** `TamperAuthorizationController.kt:57-77`:
  ```kotlin
  fun startSession(event: TamperEvent): Boolean {
      val current = activeSession
      if (current != null && current.state == TamperSessionState.PROMPTING) return false
      activeSession = TamperSession(event, wallClockTimeProvider(), TamperSessionState.PROMPTING)
      return true
  }
  ```
- **Interleaving:** Neither `startSession` nor `endSession` is synchronized.
  - Thread 1 checks `activeSession == null` -> true.
  - Thread 2 checks `activeSession == null` -> true.
  - Thread 1 sets `activeSession = Session1`.
  - Thread 2 sets `activeSession = Session2`.
  - Thread 1 user enters password -> `endSession(true)` updates `Session2` instead of `Session1`.
- **Impact:** Session state corruption; callbacks dispatched to wrong listeners.
- **Mitigation Direction:** Make `activeSession` updates thread-safe using `AtomicReference` or synchronized blocks.

---

### RACE-04: Concurrent Success and Failure Collision
- **Classification:** **HIGH CONFIDENCE**
- **Trigger:** A legitimate user submits the correct Admin Password on `AdminOverlayView` while an automated background process or malicious script calls `verifyAdminPassword("wrong")` over MethodChannel.
- **Interleaving:**
  - Thread 1 (Valid Password): verifies successfully. Dispatches `appSettingsDao.resetAdminFailures(now)`.
  - Thread 2 (Invalid Password): verifies unsuccessfully. Dispatches `appSettingsDao.updateAdminLockout(newAttempts, now)`.
  - Thread 2 commits to SQLite AFTER Thread 1 commits.
- **Impact:** The user entered the correct password, but the database counter is set to failed attempts or lockout, blocking legitimate access.
- **Mitigation Direction:** Single serialized transaction pipeline for authentication decisions.

---

### RACE-05: Database Initialization vs Service Startup Race
- **Classification:** **CONFIRMED**
- **Trigger:** Device boots; `LockKeeperAccessibilityService` is bound by system framework before `MainActivity` has launched or Room has initialized.
- **Code Reference:** `ProtectionRepository.kt:38-46`:
  ```kotlin
  init {
      CoroutineScope(Dispatchers.IO).launch {
          try {
              val s = appSettingsDao.getSettings()
              if (s != null) {
                  prefs.edit().putBoolean("onboarding_complete", s.onboardingComplete).apply()
              }
          } catch (_: Exception) {}
      }
  }
  ```
- **Interleaving:**
  - Service binds and calls `repository = ProtectionRepository.getInstance(this)`.
  - `init` launches an asynchronous coroutine on `Dispatchers.IO` to sync preferences.
  - Before the coroutine executes, an accessibility event arrives.
  - `repository.shouldProtectSettings()` calls `isOnboardingCompleteSync()`.
  - `isOnboardingCompleteSync()` reads `prefs.getBoolean("onboarding_complete", false)`.
  - SharedPreferences has not yet been populated by the coroutine!
  - `shouldProtectSettings()` returns `false`!
- **Impact:** Anti-tamper protection is disabled during the first several seconds following service launch or boot.
- **Mitigation Direction:** Block synchronously on initial preference cache warming or eliminate the SharedPreferences cache in favor of a memory-cached repository state backed by Room initialization.

---

## 3. Auditor Conclusion

The implementation is riddled with classic concurrency anti-patterns:
- Suspending functions performing multi-step authentication without mutexes.
- Non-atomic database updates.
- Asynchronous Handler posting without sequence tokens.
- An initialization race that disables anti-tamper on boot.
