package com.lockkeeper.app

import com.lockkeeper.app.domain.LockDecisionEngine
import com.lockkeeper.app.domain.SelfLockSessionManager
import com.lockkeeper.app.service.LockKeeperAccessibilityService
import com.lockkeeper.app.service.LockKeeperForegroundService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Stage 3 Foreground Service Lifecycle Test Suite.
 *
 * Exercises real production classes:
 * - LockDecisionEngine session teardown on screen-off
 * - SelfLockSessionManager session lifetime and invalidation
 * - Service running / connected state reporting
 */
class ForegroundServiceLifecycleTest {

    private lateinit var engine: LockDecisionEngine
    private lateinit var selfLock: SelfLockSessionManager

    @Before
    fun setUp() {
        engine = LockDecisionEngine(appPackageName = "com.lockkeeper.app")
        selfLock = SelfLockSessionManager()
    }

    @Test
    fun `ACTION_SCREEN_OFF cleans up active sessions and self-lock`() {
        engine.grantAppSession("com.bank.app")
        selfLock.grantSession()

        assertTrue(engine.isAppSessionActive("com.bank.app"))
        assertTrue(selfLock.isSessionActive)

        // Simulate broadcast received: ACTION_SCREEN_OFF
        engine.clearAllSessions()
        selfLock.invalidateSession()

        assertFalse("Screen off must invalidate all active app sessions", engine.isAppSessionActive("com.bank.app"))
        assertFalse("Screen off must invalidate self-lock session", selfLock.isSessionActive)
    }

    @Test
    fun `clearAllSessions also revokes active admin grace`() {
        engine.grantAdminGraceWindow()
        assertTrue(engine.isAdminGraceActive())

        engine.clearAllSessions()
        assertFalse(engine.isAdminGraceActive())
    }

    @Test
    fun `foreground service and accessibility service flags are queryable`() {
        // Assert static state flags exist and are safe to access
        val fgsRunning = LockKeeperForegroundService.isRunning
        val a11yConnected = LockKeeperAccessibilityService.isConnected
        // In JVM unit tests without real Android service bind, these default to false
        assertFalse(fgsRunning)
        assertFalse(a11yConnected)
    }
}
