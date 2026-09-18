package com.lockkeeper.app.domain

/**
 * Authoritative Protection Decision Outcome.
 */
enum class ProtectionDecisionOutcome {
    ALLOW,
    BLOCK,
    REQUIRE_AUTHENTICATION,
    REQUIRE_RECOVERY,
    DENY_UNKNOWN_STATE
}

/**
 * Granular Reason Codes for auditability and deterministic testing.
 */
enum class ProtectionDecisionReason {
    ALLOWED_OWN_PACKAGE,
    ALLOWED_UNPROTECTED_APP,
    ALLOWED_ACTIVE_SESSION,
    ALLOWED_ADMIN_GRACE,
    ALLOWED_SETUP_MODE,
    DENIED_RECOVERY_REQUIRED,
    DENIED_TAMPER_LOCKOUT,
    DENIED_SECURITY_DEGRADED,
    DENIED_INITIALIZATION_INCOMPLETE,
    DENIED_UNKNOWN_STATE,
    DENIED_NATIVE_FAILURE,
    AUTH_REQUIRED_PIN,
    AUTH_REQUIRED_ADMIN_PASSWORD,
    COOLDOWN_ACTIVE,
    LOCKOUT_ACTIVE
}

/**
 * Sealed hierarchy of authoritative protection decisions.
 */
sealed class LockDecision(
    open val outcome: ProtectionDecisionOutcome,
    open val reason: ProtectionDecisionReason
) {
    open class Allowed(
        override val reason: ProtectionDecisionReason = ProtectionDecisionReason.ALLOWED_UNPROTECTED_APP
    ) : LockDecision(ProtectionDecisionOutcome.ALLOW, reason) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Allowed) return false
            return reason == other.reason
        }

        override fun hashCode(): Int = reason.hashCode()

        override fun toString(): String = "Allowed(reason=$reason)"

        companion object : Allowed(ProtectionDecisionReason.ALLOWED_UNPROTECTED_APP)
    }

    data class RequirePin(
        val packageName: String,
        override val reason: ProtectionDecisionReason = ProtectionDecisionReason.AUTH_REQUIRED_PIN
    ) : LockDecision(ProtectionDecisionOutcome.REQUIRE_AUTHENTICATION, reason)

    data class StrictCooldown(
        val packageName: String,
        val remainingSeconds: Long,
        override val reason: ProtectionDecisionReason = ProtectionDecisionReason.COOLDOWN_ACTIVE
    ) : LockDecision(ProtectionDecisionOutcome.BLOCK, reason)

    data class PinLockout(
        val packageName: String,
        val remainingSeconds: Long,
        override val reason: ProtectionDecisionReason = ProtectionDecisionReason.LOCKOUT_ACTIVE
    ) : LockDecision(ProtectionDecisionOutcome.REQUIRE_AUTHENTICATION, reason)

    object ProtectedSettingsRequireAdmin : LockDecision(
        ProtectionDecisionOutcome.REQUIRE_AUTHENTICATION,
        ProtectionDecisionReason.AUTH_REQUIRED_ADMIN_PASSWORD
    )

    data class Blocked(
        val packageName: String,
        override val reason: ProtectionDecisionReason
    ) : LockDecision(ProtectionDecisionOutcome.BLOCK, reason)

    data class RecoveryRequired(
        override val reason: ProtectionDecisionReason = ProtectionDecisionReason.DENIED_RECOVERY_REQUIRED
    ) : LockDecision(ProtectionDecisionOutcome.REQUIRE_RECOVERY, reason)

    data class DenyUnknown(
        override val reason: ProtectionDecisionReason = ProtectionDecisionReason.DENIED_UNKNOWN_STATE
    ) : LockDecision(ProtectionDecisionOutcome.DENY_UNKNOWN_STATE, reason)
}
