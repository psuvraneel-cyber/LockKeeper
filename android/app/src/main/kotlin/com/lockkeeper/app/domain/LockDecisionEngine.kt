package com.lockkeeper.app.domain

import com.lockkeeper.app.data.db.AppSettingsEntity
import com.lockkeeper.app.data.db.LockedAppEntity
import java.util.concurrent.ConcurrentHashMap

class LockDecisionEngine(
    private val appPackageName: String = "com.lockkeeper.app",
    private val monotonicTimeProvider: () -> Long = {
        try {
            android.os.SystemClock.elapsedRealtime()
        } catch (_: RuntimeException) {
            System.currentTimeMillis()
        }
    },
    private val wallClockTimeProvider: () -> Long = {
        System.currentTimeMillis()
    }
) {
    companion object {
        const val MAX_FAILED_PIN_ATTEMPTS = 5
        const val LOCKOUT_DURATION_MS = 60_000L
        const val ADMIN_GRACE_WINDOW_MS = 30_000L
        const val APP_SESSION_MAX_DURATION_MS = 15 * 60 * 1000L // 15 minutes maximum session duration
    }

    // Active unlocked sessions for apps: packageName -> unlockedTimestamp
    private val activeSessions = ConcurrentHashMap<String, Long>()

    // Current foreground package tracking
    @Volatile
    var currentForegroundPackage: String? = null

    // Monotonic admin grace window expiry
    @Volatile
    private var adminGraceUntilMonotonic: Long = 0L

    fun grantAdminGraceWindow(currentTime: Long = monotonicTimeProvider()) {
        adminGraceUntilMonotonic = currentTime + ADMIN_GRACE_WINDOW_MS
    }

    fun isAdminGraceActive(currentTime: Long = monotonicTimeProvider()): Boolean {
        return currentTime < adminGraceUntilMonotonic
    }

    fun revokeAdminGrace() {
        adminGraceUntilMonotonic = 0L
    }

    fun grantAppSession(packageName: String, currentTime: Long = wallClockTimeProvider()) {
        activeSessions[packageName] = currentTime
    }

    fun revokeAppSession(packageName: String) {
        activeSessions.remove(packageName)
    }

    fun clearAllSessions() {
        activeSessions.clear()
        adminGraceUntilMonotonic = 0L
    }

    fun isAppSessionActive(packageName: String, currentTime: Long = wallClockTimeProvider()): Boolean {
        val grantedAt = activeSessions[packageName] ?: return false
        val elapsed = currentTime - grantedAt
        if (elapsed in 0L..APP_SESSION_MAX_DURATION_MS) {
            return true
        }
        activeSessions.remove(packageName)
        return false
    }

    /**
     * Authoritative evaluation of a target package against policy, state, and lock rules.
     */
    fun evaluate(
        targetPackage: String,
        lockedApp: LockedAppEntity?,
        appSettings: AppSettingsEntity?,
        currentTime: Long = wallClockTimeProvider(),
        isRecoveryRequired: Boolean = false,
        isTamperLocked: Boolean = false,
        isTamperGraceActive: Boolean = false,
        securityHealthStatus: String? = null,
        isSecurityProvisioned: Boolean = true,
        isSetupInProgress: Boolean = false
    ): LockDecision {
        // 1. Own package or blank is always allowed
        if (targetPackage.isBlank() || targetPackage == appPackageName) {
            return LockDecision.Allowed(ProtectionDecisionReason.ALLOWED_OWN_PACKAGE)
        }

        // 2. Recovery required overrides normal app launch: must block / require recovery
        if (isRecoveryRequired || appSettings?.recoveryRequired == true || securityHealthStatus == "RECOVERY_REQUIRED") {
            clearAllSessions()
            return LockDecision.RecoveryRequired(ProtectionDecisionReason.DENIED_RECOVERY_REQUIRED)
        }

        // 3. Unknown security health fails closed
        if (securityHealthStatus == "UNKNOWN") {
            return LockDecision.DenyUnknown(ProtectionDecisionReason.DENIED_UNKNOWN_STATE)
        }

        // 4. Setup in progress / not yet provisioned: allow normal navigation so user can complete setup
        if (isSetupInProgress || securityHealthStatus == "SETUP_IN_PROGRESS") {
            return LockDecision.Allowed(ProtectionDecisionReason.ALLOWED_SETUP_MODE)
        }

        // 5. System settings / admin package with active admin grace
        if (isTamperGraceActive || isAdminGraceActive()) {
            if (targetPackage == "com.android.settings" || targetPackage.contains("packageinstaller") || targetPackage == appPackageName) {
                return LockDecision.Allowed(ProtectionDecisionReason.ALLOWED_ADMIN_GRACE)
            }
        }

        // 5. Active tamper lockout overrides ordinary authorization
        if (isTamperLocked) {
            return LockDecision.Blocked(targetPackage, ProtectionDecisionReason.DENIED_TAMPER_LOCKOUT)
        }

        // 6. Unprotected app is allowed
        if (lockedApp == null || !lockedApp.isLocked) {
            return LockDecision.Allowed(ProtectionDecisionReason.ALLOWED_UNPROTECTED_APP)
        }

        // 7. Active unlocked session allows access
        if (isAppSessionActive(targetPackage, currentTime)) {
            return LockDecision.Allowed(ProtectionDecisionReason.ALLOWED_ACTIVE_SESSION)
        }

        // 8. PIN lockout state (5 wrong attempts)
        val lockoutUntil = appSettings?.pinLockoutUntil
        if (lockoutUntil != null && lockoutUntil > currentTime) {
            val remainingSec = (lockoutUntil - currentTime) / 1000L
            return LockDecision.PinLockout(targetPackage, maxOf(1L, remainingSec))
        }

        // 9. Strict cooldown active
        if (lockedApp.strictLock && lockedApp.lockedUntilTimestamp != null && lockedApp.lockedUntilTimestamp > currentTime) {
            val remainingSec = (lockedApp.lockedUntilTimestamp - currentTime) / 1000L
            return LockDecision.StrictCooldown(targetPackage, maxOf(1L, remainingSec))
        }

        // 10. Explicit initializing state
        if (securityHealthStatus == "INITIALIZING") {
            return LockDecision.Blocked(targetPackage, ProtectionDecisionReason.DENIED_INITIALIZATION_INCOMPLETE)
        }

        // 11. Otherwise, PIN authentication required
        return LockDecision.RequirePin(targetPackage)
    }
}
