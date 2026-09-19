package com.lockkeeper.app

import com.lockkeeper.app.data.db.AppSettingsEntity
import com.lockkeeper.app.data.db.LockedAppEntity
import com.lockkeeper.app.domain.LockDecision
import com.lockkeeper.app.domain.LockDecisionEngine
import com.lockkeeper.app.domain.ProtectionDecisionReason
import com.lockkeeper.app.domain.SelfLockSessionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 5 Startup, Boot & System Lifecycle Resilience Test Suite.
 *
 * Exercises real production classes:
 * - Process restart state volatility & clean slate security
 * - SelfLockSessionManager memory state upon cold startup
 * - Persistent configuration evaluation on cold engine instantiation
 */
class SystemLifecycleResilienceTest {

    @Test
    fun `process restart clears in-memory volatile sessions and restores fail-closed stance`() {
        var engine = LockDecisionEngine(appPackageName = "com.lockkeeper.app")
        var selfLock = SelfLockSessionManager()

        engine.grantAppSession("com.bank.app")
        engine.grantAdminGraceWindow()
        selfLock.grantSession()

        assertTrue(engine.isAppSessionActive("com.bank.app"))
        assertTrue(engine.isAdminGraceActive())
        assertTrue(selfLock.isSessionActive)

        // SIMULATE PROCESS DEATH AND RESTART:
        // JVM heap is completely discarded, brand new instances created by Android system
        engine = LockDecisionEngine(appPackageName = "com.lockkeeper.app")
        selfLock = SelfLockSessionManager()

        assertFalse("App session must not survive process death", engine.isAppSessionActive("com.bank.app"))
        assertFalse("Admin grace window must not survive process death", engine.isAdminGraceActive())
        assertFalse("Self lock session must not survive process death", selfLock.isSessionActive)
    }

    @Test
    fun `cold engine instantiation respects persistent recoveryRequired flag`() {
        val coldEngine = LockDecisionEngine(appPackageName = "com.lockkeeper.app")
        val recoverySettings = AppSettingsEntity(
            onboardingComplete = true,
            securityProvisioned = true,
            recoveryRequired = true
        )
        val lockedApp = LockedAppEntity(packageName = "com.bank.app", isLocked = true)

        val decision = coldEngine.evaluate(
            targetPackage = "com.bank.app",
            lockedApp = lockedApp,
            appSettings = recoverySettings
        )

        assertTrue(decision is LockDecision.RecoveryRequired)
        assertEquals(ProtectionDecisionReason.DENIED_RECOVERY_REQUIRED, (decision as LockDecision.RecoveryRequired).reason)
    }

    @Test
    fun `cold engine instantiation respects setup in progress before onboarding completion`() {
        val coldEngine = LockDecisionEngine(appPackageName = "com.lockkeeper.app")
        val setupSettings = AppSettingsEntity(
            onboardingComplete = false,
            securityProvisioned = false,
            recoveryRequired = false
        )
        val lockedApp = LockedAppEntity(packageName = "com.bank.app", isLocked = true)

        val decision = coldEngine.evaluate(
            targetPackage = "com.bank.app",
            lockedApp = lockedApp,
            appSettings = setupSettings,
            isSetupInProgress = true
        )

        assertTrue(decision is LockDecision.Allowed)
        assertEquals(ProtectionDecisionReason.ALLOWED_SETUP_MODE, (decision as LockDecision.Allowed).reason)
    }
}
