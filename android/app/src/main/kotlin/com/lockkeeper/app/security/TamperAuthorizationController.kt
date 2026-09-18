package com.lockkeeper.app.security

import android.os.SystemClock
import com.lockkeeper.app.data.db.AppSettingsDao
import com.lockkeeper.app.data.db.AppSettingsEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

sealed class AdminAuthResult {
    data class Success(val graceWindowMs: Long) : AdminAuthResult()
    data class Failure(val remainingAttempts: Int, val failedAttempts: Int) : AdminAuthResult()
    data class LockedOut(val remainingLockoutSeconds: Long) : AdminAuthResult()
}

enum class TamperSessionState {
    IDLE,
    PROMPTING,
    AUTHORIZED,
    DENIED
}

data class TamperSession(
    val sessionId: String = UUID.randomUUID().toString(),
    val event: TamperEvent,
    val targetPackage: String = event.targetPackage,
    val targetActivity: String = event.targetActivity,
    val tamperType: TamperType = event.type,
    val creationTime: Long = System.currentTimeMillis(),
    val creationElapsed: Long = 0L,
    @Volatile var state: TamperSessionState = TamperSessionState.PROMPTING,
    @Volatile var isOverlayAttached: Boolean = false
)

class TamperAuthorizationController(
    private val credentialStore: CredentialStore,
    private val appSettingsDao: AppSettingsDao,
    private val monotonicTimeProvider: () -> Long = { SystemClock.elapsedRealtime() },
    private val wallClockTimeProvider: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        const val MAX_FAILED_ADMIN_ATTEMPTS = 5
        const val ADMIN_LOCKOUT_DURATION_MS = 300_000L // 5 minutes
        const val ADMIN_GRACE_WINDOW_MS = 30_000L      // 30 seconds
    }

    private val authMutex = Mutex()

    @Volatile
    private var adminGraceUntilElapsed: Long = 0L

    @Volatile
    private var adminLockoutUntilElapsed: Long = 0L

    @Volatile
    var activeSession: TamperSession? = null
        private set

    fun isGraceActive(): Boolean {
        return monotonicTimeProvider() < adminGraceUntilElapsed
    }

    fun grantGraceWindow() {
        adminGraceUntilElapsed = monotonicTimeProvider() + ADMIN_GRACE_WINDOW_MS
    }

    fun revokeGraceWindow() {
        adminGraceUntilElapsed = 0L
    }

    fun startSession(event: TamperEvent): Boolean {
        if (isGraceActive()) {
            return false // Already authorized, no need to prompt
        }
        val current = activeSession
        if (current != null && current.state == TamperSessionState.PROMPTING) {
            // Already prompting for an event
            return false
        }
        activeSession = TamperSession(
            event = event,
            creationTime = wallClockTimeProvider(),
            creationElapsed = monotonicTimeProvider(),
            state = TamperSessionState.PROMPTING
        )
        return true
    }

    fun endSession(authorized: Boolean) {
        val session = activeSession ?: return
        session.state = if (authorized) TamperSessionState.AUTHORIZED else TamperSessionState.DENIED
        if (authorized) {
            grantGraceWindow()
        }
        activeSession = null
    }

    suspend fun checkLockout(): Long? {
        val monoNow = monotonicTimeProvider()
        // 1. In-memory monotonic check for the current boot session
        if (adminLockoutUntilElapsed > monoNow) {
            return maxOf(1L, (adminLockoutUntilElapsed - monoNow) / 1000L)
        }

        // 2. Persistent database check for surviving restarts/reboots
        val settings = appSettingsDao.getOrInitializeSettings()
        val lockoutUntil = settings.adminLockoutUntil ?: return null
        val wallNow = wallClockTimeProvider()

        // Detect backward clock manipulation
        if (wallNow < settings.updatedAt) {
            val conservativeLockoutSec = ADMIN_LOCKOUT_DURATION_MS / 1000L
            if (adminLockoutUntilElapsed <= monoNow) {
                adminLockoutUntilElapsed = monoNow + ADMIN_LOCKOUT_DURATION_MS
            }
            return conservativeLockoutSec
        }

        if (lockoutUntil > wallNow) {
            val remainingSec = maxOf(1L, (lockoutUntil - wallNow) / 1000L)
            if (adminLockoutUntilElapsed <= monoNow) {
                adminLockoutUntilElapsed = monoNow + (remainingSec * 1000L)
            }
            return remainingSec
        } else {
            return null
        }
    }

    suspend fun verifyAdminPassword(enteredPassword: String): AdminAuthResult = authMutex.withLock {
        val monoNow = monotonicTimeProvider()
        val wallNow = wallClockTimeProvider()

        // 1. Check lockout status
        val remainingSec = checkLockout()
        if (remainingSec != null && remainingSec > 0L) {
            return@withLock AdminAuthResult.LockedOut(remainingSec)
        }

        // 2. Load settings to ensure attempt bounds
        val settings = appSettingsDao.getOrInitializeSettings()
        if (settings.failedAdminAttempts >= MAX_FAILED_ADMIN_ATTEMPTS) {
            adminLockoutUntilElapsed = monoNow + ADMIN_LOCKOUT_DURATION_MS
            return@withLock AdminAuthResult.LockedOut(ADMIN_LOCKOUT_DURATION_MS / 1000L)
        }

        // 3. Verify password against CredentialStore (PBKDF2)
        val isValid = credentialStore.verifyAdminPassword(enteredPassword)
        if (isValid) {
            appSettingsDao.recordAdminSuccess(wallNow)
            adminLockoutUntilElapsed = 0L
            grantGraceWindow()
            return@withLock AdminAuthResult.Success(ADMIN_GRACE_WINDOW_MS)
        } else {
            val (newAttempts, newLockoutUntil) = appSettingsDao.recordAdminFailure(
                maxAttempts = MAX_FAILED_ADMIN_ATTEMPTS,
                lockoutDurationMs = ADMIN_LOCKOUT_DURATION_MS,
                now = wallNow
            )
            val isLockedOut = newAttempts >= MAX_FAILED_ADMIN_ATTEMPTS
            if (isLockedOut) {
                adminLockoutUntilElapsed = monoNow + ADMIN_LOCKOUT_DURATION_MS
                return@withLock AdminAuthResult.LockedOut(ADMIN_LOCKOUT_DURATION_MS / 1000L)
            } else {
                val remaining = MAX_FAILED_ADMIN_ATTEMPTS - newAttempts
                return@withLock AdminAuthResult.Failure(remainingAttempts = remaining, failedAttempts = newAttempts)
            }
        }
    }
}
