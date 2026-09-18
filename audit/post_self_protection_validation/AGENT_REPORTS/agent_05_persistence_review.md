# Agent 05: Database, Migration & Persistence Auditor Report

**Date:** 2026-09-17  
**Auditor:** Agent 05 — Database, Migration & Persistence Auditor  
**Target:** `AppSettingsEntity.kt`, `AppSettingsDao.kt`, `AppDatabase.kt`, Room Migrations  
**Status:** COMPLETE (Hostile Static Verification)

---

## 1. Executive Summary

This audit conducted an in-depth adversarial evaluation of LockKeeper's SQLite schema, Room database migrations, DAO contract semantics, transaction isolation, and persistence lifecycle.

**Critical Findings:**
1. **Critical DAO Design Flaw (`UPDATE` without `UPSERT`):**
   Every single state mutation query in `AppSettingsDao.kt` (`updateAdminLockout`, `resetAdminFailures`, `updatePinLockout`, `resetPinFailures`, `setOnboardingComplete`, `setSelfLockEnabled`, `setSelfLockTimeout`, `updateSelfLock`) is written as:
   `UPDATE app_settings SET ... WHERE id = 1`.
   None of these queries use SQLite `INSERT OR REPLACE` or `UPSERT`. If table `app_settings` does not currently contain a row with `id = 1` (which occurs on fresh installs before `ProtectionRepository.getSettings()` is called, after destructive migration, or after database deletion), **all UPDATE statements silently affect 0 rows**. No error is thrown. State is not saved. Failed attempt counts and lockouts are lost immediately.
2. **Destructive Migration Data Loss Hazard:**
   `AppDatabase.kt:45` specifies `.fallbackToDestructiveMigration()`. While `MIGRATION_1_2` and `MIGRATION_2_3` exist, if any device has an unexpected schema state or if Room encounters a migration validation mismatch, Room silently drops all database tables, destroying all locked app configurations and settings.
3. **Zero Automated Migration Tests:**
   There are ZERO Room migration tests in the entire codebase. Neither `MigrationTestHelper` nor unit tests verify that `MIGRATION_2_3` cleanly preserves existing `app_settings` rows on upgrade from version 2 to version 3.
4. **Non-Atomic Read-Modify-Write Concurrency:**
   Incrementing `failedAdminAttempts` is not implemented as an atomic SQL update (`failedAdminAttempts = failedAdminAttempts + 1`). Instead, the application reads the value in Kotlin, increments it in memory, and writes it back with `UPDATE`. Concurrent authentication attempts overwrite each other, causing counter corruption.
5. **SharedPreferences / Database State Split:**
   `onboarding_complete` is stored in BOTH Room SQLite (`app_settings.onboardingComplete`) and SharedPreferences (`lockkeeper_protection_prefs`). The synchronous getter `isOnboardingCompleteSync()` reads only SharedPreferences. If SharedPreferences and the SQLite database become desynchronized (e.g. via process death during setup or partial restore), LockKeeper can enter an invalid zombie state where App Lock is active but Settings protection is completely disabled.

---

## 2. Schema Evolution & Migration Review

### 2.1 Schema History
- **Version 1:**
  - Table `locked_apps` (`packageName`, `isLocked`, `cooldownMinutes`, `strictLock`, `lockedUntilTimestamp`, `updatedAt`)
  - Table `app_settings` (`id` PK, `failedPinAttempts`, `pinLockoutUntil`, `onboardingComplete`, `schemaVersion`, `updatedAt`)
- **Version 2:**
  - Added columns to `app_settings`:
    - `selfLockEnabled` INTEGER NOT NULL DEFAULT 0
    - `selfLockTimeoutSeconds` INTEGER NOT NULL DEFAULT 0
- **Version 3 (Current):**
  - Added columns to `app_settings`:
    - `failedAdminAttempts` INTEGER NOT NULL DEFAULT 0
    - `adminLockoutUntil` INTEGER (Nullable)

### 2.2 Migration Code Analysis (`AppDatabase.kt:30-35`)
```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE app_settings ADD COLUMN failedAdminAttempts INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE app_settings ADD COLUMN adminLockoutUntil INTEGER")
    }
}
```
- The SQL syntax in `MIGRATION_2_3` is valid SQLite syntax.
- Version is incremented to 3 in `@Database(version = 3)`.
- Room can chain `MIGRATION_1_2` and `MIGRATION_2_3` when upgrading from version 1.
- **Vulnerability:** If an existing row with `id = 1` exists, `failedAdminAttempts` defaults to 0 and `adminLockoutUntil` to `NULL`. However, because `AppDatabase.getInstance()` includes `.fallbackToDestructiveMigration()`, any hash mismatch in Room's internal master table metadata causes Room to drop the tables and recreate them empty.

---

## 3. The Empty Table / Fail-Open Update Vulnerability

Let us examine the exact lifecycle of `app_settings` row insertion:

In `ProtectionRepository.kt:57-63`:
```kotlin
suspend fun getSettings(): AppSettingsEntity = withContext(Dispatchers.IO) {
    (appSettingsDao.getSettings() ?: AppSettingsEntity().also {
        appSettingsDao.upsert(it)
    }).also {
        prefs.edit().putBoolean("onboarding_complete", it.onboardingComplete).apply()
    }
}
```
`ProtectionRepository.getSettings()` performs an `upsert()` if `getSettings()` returns `null`.

However, look at `TamperAuthorizationController.kt:90-115`:
```kotlin
suspend fun verifyAdminPassword(enteredPassword: String): AdminAuthResult {
    val now = wallClockTimeProvider()
    val settings = appSettingsDao.getSettings() ?: AppSettingsEntity() // (1) DOES NOT UPSERT!
    ...
    val newAttempts = settings.failedAdminAttempts + 1
    val isLockedOut = newAttempts >= MAX_FAILED_ADMIN_ATTEMPTS
    val newLockoutUntil = if (isLockedOut) now + ADMIN_LOCKOUT_DURATION_MS else null

    appSettingsDao.updateAdminLockout(newAttempts, newLockoutUntil, now) // (2)
    ...
}
```

And look at `AppSettingsDao.kt:22`:
```kotlin
@Query("UPDATE app_settings SET failedAdminAttempts = :attempts, adminLockoutUntil = :lockoutUntil, updatedAt = :updatedAt WHERE id = 1")
suspend fun updateAdminLockout(attempts: Int, lockoutUntil: Long?, updatedAt: Long = System.currentTimeMillis())
```

### Adversarial Proof:
1. `TamperAuthorizationController` injects `appSettingsDao` directly, NOT `ProtectionRepository`.
2. When `TamperAuthorizationController.verifyAdminPassword` is called:
   - It calls `appSettingsDao.getSettings()`.
   - If row 1 is missing, it creates a transient `AppSettingsEntity()`.
   - It NEVER calls `appSettingsDao.upsert()`.
   - It calls `appSettingsDao.updateAdminLockout()`.
3. In SQLite:
   `UPDATE app_settings SET failedAdminAttempts = 1 WHERE id = 1`
   When `SELECT COUNT(*) FROM app_settings` is 0, this statement modifies 0 rows and returns success with 0 changes.
4. The database remains completely empty.
5. On attempt #2: `getSettings()` is STILL null. `failedAdminAttempts` is STILL 0. `newAttempts` is STILL 1.
6. **Result:** An attacker can submit invalid passwords indefinitely without ever being locked out.

---

## 4. Concurrency & Atomicity Audit

### 4.1 Check-Then-Act Counter Update
In `TamperAuthorizationController.kt`:
```kotlin
val newAttempts = settings.failedAdminAttempts + 1
...
appSettingsDao.updateAdminLockout(newAttempts, newLockoutUntil, now)
```
- This is a non-atomic read-modify-write pattern.
- If two coroutines or threads execute `verifyAdminPassword` concurrently:
  - Both read `failedAdminAttempts = 2`.
  - Both compute `newAttempts = 3`.
  - Both write `3`.
  - Total recorded failed attempts: 3, instead of 4.
- Proper secure implementation requires atomic SQL:
  `UPDATE app_settings SET failedAdminAttempts = failedAdminAttempts + 1 WHERE id = 1` executed inside a database transaction.

### 4.2 Reset vs Failure Collision
If Thread 1 (a delayed success) and Thread 2 (a concurrent failure) interleave:
- Thread 1 computes valid password -> prepares `resetAdminFailures()`.
- Thread 2 computes invalid password -> prepares `updateAdminLockout(newAttempts)`.
- If Thread 2 commits after Thread 1, the counter is incremented despite legitimate authentication, or if Thread 1 commits after Thread 2, failed attempts from an attacker are erased.

---

## 5. Storage Clearing & Database Recovery Audit

| Failure Scenario | Resulting State | Anti-Tamper Impact |
|---|---|---|
| **`pm clear` / Clear Storage** | Database file deleted; SharedPreferences deleted. | **TOTAL BYPASS:** `shouldProtectSettings()` returns `false`. All tamper detection and App Lock shut down completely. No recovery prompt exists. |
| **Missing DB File** | Room creates empty DB. | **FAIL-OPEN:** Row 1 missing -> UPDATE queries affect 0 rows -> Infinite password brute-force possible. |
| **Partial DB Corruption** | SQLite disk I/O error. | Unhandled exception crashes service. Service dies; protection ceases. |
| **Process Death during Update** | Uncommitted transaction. | In-memory grace window wiped; lockout persisted only if DB write completed before kill. |

---

## 6. Auditor Conclusion

The persistence architecture suffers from a fundamental SQL bug: relying on `UPDATE ... WHERE id = 1` without ensuring row `id = 1` exists causes silent failure and infinite password brute-forcing. Furthermore, there are no database migration tests, updates are not atomic, and storage clearing leads to an insecure unconfigured state rather than the documented `RECOVERY_REQUIRED` mode.
