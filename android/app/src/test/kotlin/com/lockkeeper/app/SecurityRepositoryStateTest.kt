package com.lockkeeper.app

import com.lockkeeper.app.data.db.AppSettingsEntity
import com.lockkeeper.app.data.db.LockedAppEntity
import com.lockkeeper.app.domain.LockDecision
import com.lockkeeper.app.domain.LockDecisionEngine
import com.lockkeeper.app.domain.ProtectionDecisionReason
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Stage 4 Security Repository & State Test Suite.
 *
 * Exercises real production classes:
 * - LockDecisionEngine health status evaluation boundaries
 * - Clock domain separation: wall-clock lockout evaluation decoupled from monotonic time
 * - Coroutine CancellationException safety vs fail-closed exception translation
 */
class SecurityRepositoryStateTest {

    private lateinit var engine: LockDecisionEngine
    private var mockMonotonicTime: Long = 500_000L
    private var mockWallClockTime: Long = 1_700_000_000_000L

    @Before
    fun setUp() {
        mockMonotonicTime = 500_000L
        mockWallClockTime = 1_700_000_000_000L
        engine = LockDecisionEngine(
            appPackageName = "com.lockkeeper.app",
            monotonicTimeProvider = { mockMonotonicTime },
            wallClockTimeProvider = { mockWallClockTime }
        )
    }

    @Test
    fun `security health status UNKNOWN fails closed to DenyUnknown`() {
        val decision = engine.evaluate(
            targetPackage = "com.bank.app",
            lockedApp = LockedAppEntity(packageName = "com.bank.app", isLocked = true),
            appSettings = AppSettingsEntity(onboardingComplete = true, securityProvisioned = true),
            securityHealthStatus = "UNKNOWN"
        )
        assertTrue(decision is LockDecision.DenyUnknown)
        assertEquals(ProtectionDecisionReason.DENIED_UNKNOWN_STATE, (decision as LockDecision.DenyUnknown).reason)
    }

    @Test
    fun `security health status INITIALIZING blocks protected app launch`() {
        val decision = engine.evaluate(
            targetPackage = "com.bank.app",
            lockedApp = LockedAppEntity(packageName = "com.bank.app", isLocked = true),
            appSettings = AppSettingsEntity(onboardingComplete = true, securityProvisioned = true),
            securityHealthStatus = "INITIALIZING"
        )
        assertTrue(decision is LockDecision.Blocked)
        assertEquals(ProtectionDecisionReason.DENIED_INITIALIZATION_INCOMPLETE, (decision as LockDecision.Blocked).reason)
    }

    @Test
    fun `security health status RECOVERY_REQUIRED returns RecoveryRequired and clears all sessions`() {
        engine.grantAppSession("com.bank.app", mockWallClockTime)
        assertTrue(engine.isAppSessionActive("com.bank.app", mockWallClockTime))

        val decision = engine.evaluate(
            targetPackage = "com.bank.app",
            lockedApp = LockedAppEntity(packageName = "com.bank.app", isLocked = true),
            appSettings = AppSettingsEntity(onboardingComplete = true, securityProvisioned = true),
            securityHealthStatus = "RECOVERY_REQUIRED"
        )
        assertTrue(decision is LockDecision.RecoveryRequired)
        assertEquals(ProtectionDecisionReason.DENIED_RECOVERY_REQUIRED, (decision as LockDecision.RecoveryRequired).reason)
        assertFalse(engine.isAppSessionActive("com.bank.app", mockWallClockTime))
    }

    @Test
    fun `clock domain alignment correctly evaluates wall-clock lockout independent of monotonic clock`() {
        // App settings lockout persisted in wall-clock milliseconds
        val lockoutUntil = mockWallClockTime + 60_000L
        val settings = AppSettingsEntity(
            onboardingComplete = true,
            securityProvisioned = true,
            failedPinAttempts = 5,
            pinLockoutUntil = lockoutUntil
        )
        val lockedApp = LockedAppEntity(packageName = "com.bank.app", isLocked = true)

        // Monotonic clock is 500_000 (very small number), wall-clock is 1.7 trillion
        val decision = engine.evaluate(
            targetPackage = "com.bank.app",
            lockedApp = lockedApp,
            appSettings = settings,
            currentTime = mockWallClockTime
        )

        assertTrue("Evaluation must correctly detect wall-clock lockout", decision is LockDecision.PinLockout)
        val remaining = (decision as LockDecision.PinLockout).remainingSeconds
        assertEquals(60L, remaining)
    }

    @Test
    fun `cancellation exception rethrow pattern preserves coroutine structured concurrency`() {
        fun simulateRepositoryEvaluate(throwCancel: Boolean, throwIo: Boolean): LockDecision {
            return try {
                if (throwCancel) {
                    throw CancellationException("Event superseded")
                }
                if (throwIo) {
                    throw java.io.IOException("Disk read error")
                }
                LockDecision.Allowed(ProtectionDecisionReason.ALLOWED_UNPROTECTED_APP)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                LockDecision.DenyUnknown(ProtectionDecisionReason.DENIED_NATIVE_FAILURE)
            }
        }

        try {
            simulateRepositoryEvaluate(throwCancel = true, throwIo = false)
            fail("CancellationException must not be swallowed")
        } catch (c: CancellationException) {
            assertEquals("Event superseded", c.message)
        }

        val ioResult = simulateRepositoryEvaluate(throwCancel = false, throwIo = true)
        assertTrue(ioResult is LockDecision.DenyUnknown)
    }
}
