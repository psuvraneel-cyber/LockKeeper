# Agent 04: Authentication & Admin Password Auditor Report

**Date:** 2026-09-17  
**Auditor:** Agent 04 — Authentication & Cryptographic Security Auditor  
**Target:** `TamperAuthorizationController.kt`, `CredentialStore.kt`, `PlatformChannelHandler.kt`, `AdminOverlayView.kt`  
**Status:** COMPLETE (Hostile Static Verification)

---

## 1. Executive Summary

This audit conducted an exhaustive adversarial analysis of the Admin Password authentication subsystem, rate-limiting enforcement, lockout persistence, and grace window mechanics.

**Critical Findings:**
1. **Missing DB Row Causes Infinite Brute Force (Fail-Open):**
   In `TamperAuthorizationController.kt:92`, if `appSettingsDao.getSettings()` returns `null`, the controller falls back to an in-memory `AppSettingsEntity()`. When wrong passwords are submitted, it calls `appSettingsDao.updateAdminLockout(newAttempts, ...)`. In `AppSettingsDao.kt`, this executes `UPDATE app_settings ... WHERE id = 1`. Because row `id = 1` does not exist, SQLite updates 0 rows! On every subsequent attempt, `getSettings()` is still null, `failedAdminAttempts` is still 0, and `newAttempts` is always 1. **Lockout is never triggered, enabling infinite online brute-force attacks.**
2. **Clock Manipulation Destroys Lockout:**
   `adminLockoutUntil` is computed using wall-clock time (`System.currentTimeMillis()`). An attacker who is locked out for 300 seconds can advance the device time by 5 minutes in settings or via automation, instantly destroying the lockout condition (`lockoutUntil > now` becomes false).
3. **Concurrent Password Verification Race:**
   `verifyAdminPassword` is not thread-safe. PBKDF2 hashing takes 50–150ms. Two or more concurrent requests both read `failedAdminAttempts = 0`, compute hash, and both write `newAttempts = 1`. Parallelized password submissions can exhaust a massive password space without triggering the 5-attempt limit.
4. **Dual Grace Timers with Asymmetric Revocation:**
   `TamperAuthorizationController.adminGraceUntilElapsed` uses monotonic time (`SystemClock.elapsedRealtime()`). But `LockDecisionEngine.adminGraceUntil` uses wall-clock time (`System.currentTimeMillis()`). When `TamperAuthorizationController.revokeGraceWindow()` is invoked, `LockDecisionEngine.adminGraceUntil` is NOT revoked! Because `LockKeeperAccessibilityService.kt:157` checks `controller.isGraceActive() || decisionEngine.isAdminGraceActive()`, the wall-clock grace remains valid, bypassing protection.
5. **PlatformChannel Information Leakage & Swallowed Lockout:**
   In `PlatformChannelHandler.kt:119`, `verifyAdminPassword` collapses `AdminAuthResult` into a simple boolean `valid`. If the system is locked out, Flutter receives only `false`. Flutter cannot display remaining lockout time or remaining attempts, creating UI/UX confusion and blinding the user.

---

## 2. Deep Dive: Authentication & Rate-Limiting Mechanics

### 2.1 The Missing Database Row / Fail-Open Vulnerability

In `TamperAuthorizationController.kt`:
```kotlin
suspend fun verifyAdminPassword(enteredPassword: String): AdminAuthResult {
    val now = wallClockTimeProvider()
    val settings = appSettingsDao.getSettings() ?: AppSettingsEntity() // (1) In-memory instance!

    val lockoutUntil = settings.adminLockoutUntil
    if (lockoutUntil != null && lockoutUntil > now) {
        val remainingSec = maxOf(1L, (lockoutUntil - now) / 1000L)
        return AdminAuthResult.LockedOut(remainingSec)
    }

    val isValid = credentialStore.verifyAdminPassword(enteredPassword)
    if (isValid) {
        appSettingsDao.resetAdminFailures(now)
        grantGraceWindow()
        return AdminAuthResult.Success(ADMIN_GRACE_WINDOW_MS)
    } else {
        val newAttempts = settings.failedAdminAttempts + 1
        val isLockedOut = newAttempts >= MAX_FAILED_ADMIN_ATTEMPTS
        val newLockoutUntil = if (isLockedOut) now + ADMIN_LOCKOUT_DURATION_MS else null

        appSettingsDao.updateAdminLockout(newAttempts, newLockoutUntil, now) // (2) UPDATE WHERE id = 1
        ...
    }
}
```

Now look at `AppSettingsDao.kt:22`:
```kotlin
@Query("UPDATE app_settings SET failedAdminAttempts = :attempts, adminLockoutUntil = :lockoutUntil, updatedAt = :updatedAt WHERE id = 1")
suspend fun updateAdminLockout(attempts: Int, lockoutUntil: Long?, updatedAt: Long = System.currentTimeMillis())
```

#### Exploit Trace:
1. When LockKeeper is installed or after data is cleared, table `app_settings` is empty.
2. If `ProtectionRepository.getSettings()` was not previously invoked (or if the database was recreated by Room's destructive fallback), row `id = 1` does NOT exist in the SQLite table.
3. Attacker enters an incorrect password.
4. Line 92 executes: `getSettings()` returns `null`. `settings` is assigned `AppSettingsEntity()` (a transient Kotlin object with `failedAdminAttempts = 0`).
5. Line 110: `newAttempts = 0 + 1 = 1`.
6. Line 114: Room runs:
   `UPDATE app_settings SET failedAdminAttempts = 1, adminLockoutUntil = NULL WHERE id = 1`.
   **Rows affected: 0.**
7. Attacker submits another incorrect password.
8. `getSettings()` STILL returns `null`! `failedAdminAttempts` is STILL 0!
9. `newAttempts` is STILL 1!
10. **Result:** An attacker can guess millions of passwords. `newAttempts` never reaches 5. Lockout is NEVER triggered.

---

### 2.2 Time Discrepancy & Clock-Warp Lockout Bypass

Look at `TamperAuthorizationController.kt:29-30`:
```kotlin
private val monotonicTimeProvider: () -> Long = { SystemClock.elapsedRealtime() },
private val wallClockTimeProvider: () -> Long = { System.currentTimeMillis() }
```
And lines 112-114:
```kotlin
val newLockoutUntil = if (isLockedOut) now + ADMIN_LOCKOUT_DURATION_MS else null
appSettingsDao.updateAdminLockout(newAttempts, newLockoutUntil, now)
```
And line 96:
```kotlin
val lockoutUntil = settings.adminLockoutUntil
if (lockoutUntil != null && lockoutUntil > now) {
    val remainingSec = maxOf(1L, (lockoutUntil - now) / 1000L)
    return AdminAuthResult.LockedOut(remainingSec)
}
```

#### Exploit Trace:
1. The lockout duration is stored as a wall-clock millisecond epoch: `System.currentTimeMillis() + 300_000L`.
2. The comparison `lockoutUntil > now` relies entirely on `System.currentTimeMillis()`.
3. Wall-clock time on Android is user-configurable (`Settings -> System -> Date & Time`).
4. If an attacker triggers a 5-minute lockout, they simply change the device clock forward by 5 minutes.
5. On the next call to `verifyAdminPassword`, `lockoutUntil > now` is `false`.
6. Attacker is immediately allowed to resume guessing passwords.

---

### 2.3 Concurrent Authentication Race Condition

Look at lines 90–114 in `TamperAuthorizationController.kt`:
1. `val settings = appSettingsDao.getSettings() ?: AppSettingsEntity()` (Non-atomic DB read)
2. `val isValid = credentialStore.verifyAdminPassword(enteredPassword)` (Heavy cryptographic work: PBKDF2 with 65,536 iterations, ~100ms duration)
3. `appSettingsDao.updateAdminLockout(...)` (Non-atomic DB write)

#### Exploit Trace:
- Thread A (Request 1): reads `failedAdminAttempts = 0`. Starts PBKDF2.
- Thread B (Request 2): reads `failedAdminAttempts = 0`. Starts PBKDF2.
- Thread C (Request 3): reads `failedAdminAttempts = 0`. Starts PBKDF2.
- Thread A completes PBKDF2, writes `failedAdminAttempts = 1`.
- Thread B completes PBKDF2, writes `failedAdminAttempts = 1`.
- Thread C completes PBKDF2, writes `failedAdminAttempts = 1`.
- **Result:** 3 failed attempts resulted in counter value 1 instead of 3.
- Rate limiting is defeated by concurrent submissions.

---

### 2.4 Cryptographic Storage Analysis (`CredentialStore.kt`)

`KeystoreCredentialStore.kt` was reviewed:
- **Key Generation:** AES-256 GCM (`LockKeeperMasterKey_v1`) backed by `AndroidKeyStore`. Correct.
- **KDF:** `PBKDF2WithHmacSHA256` with 65,536 iterations, 16-byte random salt, 256-bit output. Correct.
- **Storage Payload:** `"v1:65536:<salt_b64>:<hash_b64>"` encrypted with AES/GCM/NoPadding (12-byte IV + ciphertext + 16-byte tag). Stored in `lockkeeper_credentials.xml`.
- **Comparison:** `MessageDigest.isEqual` (constant-time). Correct.
- **PIN Verification Boundary:** `pin.length in 4..8 && pin.all { it.isDigit() }`.
- **Admin Password Verification Boundary:** `password.length >= 4`.
  - **Weakness:** No upper bound on Admin Password length. An attacker submitting a 10MB string can cause severe CPU denial of service or OutOfMemoryError in `PBKDF2WithHmacSHA256`.

---

### 2.5 MethodChannel Verification Path Audit

The Flutter method channel was inspected for bypasses:
In `PlatformChannelHandler.kt:117-122`:
```kotlin
"verifyAdminPassword" -> {
    val password = call.argument<String>("password") ?: ""
    val authResult = repository.tamperController.verifyAdminPassword(password)
    val valid = authResult is com.lockkeeper.app.security.AdminAuthResult.Success
    result.success(valid)
}
```
- There is no secondary or unguarded method (e.g., `credentialStore.verifyAdminPassword` is not exposed directly).
- However, calling `verifyAdminPassword` from Flutter consumes rate-limiting attempts against the SAME shared counter as the anti-tamper overlay.
- If a legitimate user fails their password in Settings 5 times, anti-tamper is locked out.
- If an attacker triggers lockout in anti-tamper, the legitimate user is locked out in Settings.

---

## 3. Invariant Evaluation

| Invariant | Description | Result | Evidence |
|---|---|---|---|
| **INV-3** | Correct Admin Password must not grant permanent authorization | **PASS** | `adminGraceUntilElapsed` expires after 30s. |
| **INV-4** | Lockout must survive process restart | **CONDITIONAL FAIL** | Only survives if row `id = 1` was inserted in DB; bypassed by clock change. |
| **INV-5** | Unauthorized tamper attempts must not transition to authorized state | **PASS** | Session denies on cancel. |
| **INV-AT-1** | Rate-limit counters must be atomically incremented | **FAIL** | Check-then-act race in `verifyAdminPassword`. |
| **INV-AT-2** | Rate limiting must not fail open when database is uninitialized | **FAIL** | `updateAdminLockout` affects 0 rows when row 1 is missing. |

---

## 4. Auditor Conclusion

The authentication layer contains a critical fail-open defect: when database row 1 is missing, `UPDATE ... WHERE id = 1` silently updates 0 rows, allowing infinite brute force. Furthermore, lockouts are trivially bypassed by changing the device clock, and concurrent password checks can defeat attempt tracking.
