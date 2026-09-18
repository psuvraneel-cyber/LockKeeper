# WAVE 3: PRE-IMPLEMENTATION BASELINE

## 1. Baseline Verification & Metrics

- **Date / Timestamp:** 2026-09-17T22:18:00+05:30
- **Platform:** Windows (Host), Android / Kotlin + Flutter Dart
- **Flutter Analyze:** 0 issues found (clean)
- **Flutter Test Suite:** 17/17 passed (100%)
- **JVM Debug Unit Test Suite:** 148/148 passed (100%)
- **Total Certified Baseline Tests:** 165/165 passing
- **ADB Status:** No active devices/emulators connected (`adb devices` returned empty)

---

## 2. Certified Wave 1 & Wave 2 State

1. **Security State Truth (Wave 1):**
   - Single Source of Truth architecture separating system status: `PROTECTED`, `CONFIGURED`, `DEGRADED`, `RECOVERY_REQUIRED`, `INITIALIZING`, `UNKNOWN`.
   - Distinct accessibility states: `isAccessibilityGranted` (permission in Settings), `isAccessibilityConnected` (active service binder alive), `isAccessibilityOperational` ($granted \land connected$).
   - Authoritative Device Admin truth polled from Android `DevicePolicyManager.isAdminActive()`.
   - Fail-closed platform-channel error boundaries.
   - Preserved all 6 critical anti-tamper remediations.

2. **Protection Decision & Enforcement Integrity (Wave 2):**
   - Authoritative Decision Pipeline in `LockDecisionEngine` with immutable `ProtectionDecisionOutcome` and `ProtectionDecisionReason`.
   - Strict Precedence:
     $$\text{Self Package} \rightarrow \text{Recovery} \rightarrow \text{Unknown Health} \rightarrow \text{Admin Grace} \rightarrow \text{Tamper Lockout} \rightarrow \text{Unprotected App} \rightarrow \text{Active Session} \rightarrow \text{PIN Lockout} \rightarrow \text{Cooldown} \rightarrow \text{Initializing} \rightarrow \text{PIN Required}$$
   - Monotonic clock timing for all grace and session expirations.
   - Guaranteed session invalidation on Device Admin deactivation (`onDisabled`) and Accessibility Service destruction (`onDestroy`).
   - Fail-closed catch-all blocks in Flutter UI (`_isLocked = true`) and native repository (`DenyUnknown`).
   - 15 Security Invariants (INV-201..INV-215) and 20 Enforcement Paths (Paths A..T) fully passing.

---

## 3. Wave 3 Problem Formulation

Wave 3 focuses on **Runtime Resilience, Anti-Tamper & Bypass Resistance**:
> *"Can LockKeeper be bypassed even though its internal security decision is correct?"*

Even if `LockDecisionEngine.evaluate()` correctly decides `RequirePin` or `Blocked`, can an attacker or adversarial runtime condition circumvent the enforcement action at runtime?

Potential attack vectors to investigate:
- Lifecycle interrupts, task kills, process termination, service restart race windows.
- Overlay manipulation, back/home/recents races, keyboard/IME overlay leaks, window focus changes.
- Alternate intent routes (deep links, notifications, share intents, file managers, widgets).
- Settings sub-routes, MIUI Security Center apps, app detail screens, split-screen, picture-in-picture.
- Tamper authorization replay, expired grace race, simultaneous authorization attempts.
