# Agent 04 — Data, Database & Concurrency Audit Report

**Auditor Persona**: Database Architect & Concurrency Specialist  
**Target Repository**: LockKeeper (`C:\AppLocker`)  
**Audit Date**: September 17, 2026  
**Scope**: Room Database Architecture, DAOs, SQLite Concurrency, Room Migrations, SharedPreferences Caching, Time-of-Check to Time-of-Use (TOCTOU), and In-Memory Session Synchronization.

---

## 1. Database & Persistence Architecture Overview

LockKeeper persists data across three separate stores:
1. **Room SQLite Database (`AppDatabase`)**:
   - `lockkeeper_database` (version 2)
   - Entities: `LockedAppEntity` (table `locked_apps`), `AppSettingsEntity` (table `app_settings`).
   - Managed via `ProtectionRepository`.
2. **Encrypted SharedPreferences (`lockkeeper_credentials`)**:
   - Key-value store holding AES-256-GCM encrypted PBKDF2 hashes: `cred_pin_blob`, `cred_pin_len`, `cred_admin_blob`.
3. **Plain SharedPreferences (`lockkeeper_protection_prefs`)**:
   - Holds cached flags for fast synchronous access: `"onboarding_complete"`.
4. **In-Memory Volatile Caches**:
   - `LockDecisionEngine.activeSessions` (`ConcurrentHashMap<String, Long>`)
   - `SelfLockSessionManager.isSessionActive`, `lastBackgroundTimestamp` (`@Volatile` fields).

---

## 2. In-Depth Concurrency & Persistence Vulnerabilities

### 2.1 Destructive Migration Fallback Enabled (VULN-DATA-01)
- **Severity**: HIGH
- **Confidence**: CONFIRMED
- **File**: `android/app/src/main/kotlin/com/lockkeeper/app/data/db/AppDatabase.kt`, Lines 30–43
```kotlin
fun getInstance(context: Context): AppDatabase {
    return INSTANCE ?: synchronized(this) {
        val instance = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "lockkeeper_database"
        )
            .addMigrations(MIGRATION_1_2)
            .fallbackToDestructiveMigration()
            .build()
        INSTANCE = instance
        instance
    }
}
```
- **Technical Risk**:
  - `fallbackToDestructiveMigration()` is enabled.
  - Furthermore, in `@Database`: `exportSchema = false`.
  - If any subsequent database schema change is introduced without an explicit migration or with a mismatched version, Room will **silently drop all tables** without warning or error!
  - If tables are dropped, all configured locked apps, cooldown timers, failed PIN attempt records, and onboarding completion status are completely wiped.

### 2.2 Time-of-Check to Time-of-Use (TOCTOU) in Pin Lockout Updates (VULN-DATA-02)
- **Severity**: HIGH
- **Confidence**: CONFIRMED
- **File**: `android/app/src/main/kotlin/com/lockkeeper/app/domain/ProtectionRepository.kt`, Lines 100–110
```kotlin
suspend fun handleFailedPin(): Pair<Int, Long?> = withContext(Dispatchers.IO) {
    val settings = getSettings()
    val newAttempts = settings.failedPinAttempts + 1
    val lockoutUntil = if (newAttempts >= LockDecisionEngine.MAX_FAILED_PIN_ATTEMPTS) {
        System.currentTimeMillis() + LockDecisionEngine.LOCKOUT_DURATION_MS
    } else {
        null
    }
    appSettingsDao.updatePinLockout(newAttempts, lockoutUntil)
    Pair(newAttempts, lockoutUntil)
}
```
- **Technical Concurrency Flaw**:
  - `handleFailedPin()` executes in two distinct, non-atomic database operations:
    1. Read `getSettings()`
    2. Increment in memory: `val newAttempts = settings.failedPinAttempts + 1`
    3. Update `appSettingsDao.updatePinLockout(newAttempts, lockoutUntil)`
  - If two concurrent threads invoke `handleFailedPin()` (e.g., rapid submissions via platform bridge or concurrent overlay interactions), both read the same `failedPinAttempts` value.
  - This classic read-modify-write race condition causes **lost updates**: 2 failed attempts increment the count by only 1!
  - In an automated attack, the threshold to trigger lockout (5 attempts) can be delayed or bypassed.
  - **Remediation Direction**: Implement atomic SQL update: `UPDATE app_settings SET failedPinAttempts = failedPinAttempts + 1 ...` within a Room `@Transaction`.

### 2.3 Stale Dual-Layer Caching of `onboarding_complete` (VULN-DATA-03)
- **Severity**: MEDIUM
- **Confidence**: CONFIRMED
- **File**: `android/app/src/main/kotlin/com/lockkeeper/app/domain/ProtectionRepository.kt`, Lines 34–49, 137–145
```kotlin
private val prefs = context.getSharedPreferences("lockkeeper_protection_prefs", Context.MODE_PRIVATE)

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

fun isOnboardingCompleteSync(): Boolean {
    return prefs.getBoolean("onboarding_complete", false)
}
```
- **Technical Inconsistency**:
  - `onboarding_complete` is stored in both Room SQLite (`app_settings.onboardingComplete`) and SharedPreferences (`lockkeeper_protection_prefs.onboarding_complete`).
  - In `init`, SharedPreferences is populated asynchronously from Room via `Dispatchers.IO.launch`.
  - When `MainActivity.onCreate()` calls `updateSecureFlag()`, it immediately invokes `repo.isOnboardingCompleteSync()`.
  - If the asynchronous coroutine in `init` has not yet completed its database read, `isOnboardingCompleteSync()` reads stale data from SharedPreferences (or the default `false`), causing `FLAG_SECURE` to be omitted.

### 2.4 Unbounded Session Lifetime in `LockDecisionEngine` (VULN-DATA-04)
- **Severity**: HIGH
- **Confidence**: CONFIRMED
- **File**: `android/app/src/main/kotlin/com/lockkeeper/app/domain/LockDecisionEngine.kt`, Lines 17, 39–54
```kotlin
// Active unlocked sessions for apps: packageName -> unlockedTimestamp
private val activeSessions = ConcurrentHashMap<String, Long>()

fun grantAppSession(packageName: String, currentTime: Long = System.currentTimeMillis()) {
    activeSessions[packageName] = currentTime
}

fun isAppSessionActive(packageName: String): Boolean {
    return activeSessions.containsKey(packageName)
}
```
- **Technical Flaw**:
  - `activeSessions` stores the timestamp when an app was unlocked (`activeSessions[packageName] = currentTime`), but `isAppSessionActive` and `evaluate` **never check the elapsed time**!
  - There is no session TTL or inactivity timeout.
  - The entry in `activeSessions` is only removed when `handleAppExited(packageName)` is called upon switching to another app.
  - If the user unlocks YouTube, leaves YouTube on screen, and locks the phone screen, YouTube remains in `activeSessions`. When the phone is unlocked hours later, YouTube is immediately accessible without a PIN.
  - If an accessibility event is missed (e.g., during rapid switching or launcher crash), the package remains in `activeSessions` indefinitely for the entire life of the LockKeeper process.

### 2.5 In-Memory Admin Grace Window Clock-Skew Assumptions (VULN-DATA-05)
- **Severity**: LOW
- **Confidence**: CONFIRMED
- **File**: `android/app/src/main/kotlin/com/lockkeeper/app/domain/LockDecisionEngine.kt`, Lines 25–33
```kotlin
fun grantAdminGraceWindow(currentTime: Long = System.currentTimeMillis()) {
    adminGraceUntil = currentTime + ADMIN_GRACE_WINDOW_MS
}

fun isAdminGraceActive(currentTime: Long = System.currentTimeMillis()): Boolean {
    return currentTime < adminGraceUntil
}
```
- Uses `System.currentTimeMillis()` instead of `SystemClock.elapsedRealtime()`.
- `System.currentTimeMillis()` is wall-clock time and can be modified by the user in Android Settings (Date & Time) or via network time updates.
- If a user rolls back the system clock while `adminGraceUntil` is active, the 30-second admin grace window can be extended arbitrarily!
- **Remediation Direction**: Use monotonic clock `SystemClock.elapsedRealtime()`.

---

## 3. Data & Concurrency Summary

| ID | Title | Severity | Confidence | Impact |
|---|---|---|---|---|
| **VULN-DATA-01** | Destructive Migration Fallback Enabled | HIGH | CONFIRMED | Silent database drops and total data loss on unmigrated schema updates |
| **VULN-DATA-02** | Non-Atomic Read-Modify-Write in Failed PIN Handling | HIGH | CONFIRMED | Lost updates allow brute-force attempts without triggering lockout |
| **VULN-DATA-03** | Stale Dual-Layer Onboarding Cache | MEDIUM | CONFIRMED | `FLAG_SECURE` desynchronization on startup race condition |
| **VULN-DATA-04** | Unbounded In-Memory App Session Lifetimes | HIGH | CONFIRMED | Unlocked apps remain permanently unlocked while foregrounded/idle |
| **VULN-DATA-05** | Wall-Clock Manipulation of Admin Grace Period | LOW | CONFIRMED | Time rollbacks can extend admin grace window indefinitely |
