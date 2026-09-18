package com.lockkeeper.app

import com.lockkeeper.app.domain.SelfLockSessionManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SelfLockDomainTest {

    private lateinit var sessionManager: SelfLockSessionManager

    @Before
    fun setUp() {
        sessionManager = SelfLockSessionManager()
    }

    @Test
    fun `self-lock disabled requires no authentication`() {
        val required = sessionManager.isAuthRequired(
            selfLockEnabled = false,
            onboardingComplete = true,
            timeoutSeconds = 0,
            lockoutUntil = null
        )
        assertFalse("Disabled self-lock should allow access", required)
    }

    @Test
    fun `incomplete onboarding requires no authentication`() {
        val required = sessionManager.isAuthRequired(
            selfLockEnabled = true,
            onboardingComplete = false,
            timeoutSeconds = 0,
            lockoutUntil = null
        )
        assertFalse("Incomplete onboarding should not block initial setup wizard", required)
    }

    @Test
    fun `fresh start with self-lock enabled requires authentication`() {
        val required = sessionManager.isAuthRequired(
            selfLockEnabled = true,
            onboardingComplete = true,
            timeoutSeconds = 0,
            lockoutUntil = null
        )
        assertTrue("Fresh start must require authentication", required)
    }

    @Test
    fun `granted session allows access while active`() {
        sessionManager.grantSession()
        assertTrue(sessionManager.isSessionActive)

        val required = sessionManager.isAuthRequired(
            selfLockEnabled = true,
            onboardingComplete = true,
            timeoutSeconds = 30,
            lockoutUntil = null
        )
        assertFalse("Active session should allow access", required)
    }

    @Test
    fun `background return within grace period keeps session active`() {
        val now = 1000000L
        sessionManager.grantSession()
        sessionManager.recordBackgrounded(now)

        // Return 15s later when timeout is 30s
        val required = sessionManager.isAuthRequired(
            selfLockEnabled = true,
            onboardingComplete = true,
            timeoutSeconds = 30,
            lockoutUntil = null,
            currentTime = now + 15000L
        )
        assertFalse("Session within grace period should not require PIN", required)
        assertTrue(sessionManager.isSessionActive)
    }

    @Test
    fun `background return after timeout invalidates session and requires PIN`() {
        val now = 1000000L
        sessionManager.grantSession()
        sessionManager.recordBackgrounded(now)

        // Return 35s later when timeout is 30s
        val required = sessionManager.isAuthRequired(
            selfLockEnabled = true,
            onboardingComplete = true,
            timeoutSeconds = 30,
            lockoutUntil = null,
            currentTime = now + 35000L
        )
        assertTrue("Session after timeout must require PIN", required)
        assertFalse("Session should be invalidated", sessionManager.isSessionActive)
    }

    @Test
    fun `immediate timeout policy locks immediately upon leaving app`() {
        val now = 1000000L
        sessionManager.grantSession()
        sessionManager.recordBackgrounded(now)

        // Immediate policy (timeout = 0s)
        val required = sessionManager.isAuthRequired(
            selfLockEnabled = true,
            onboardingComplete = true,
            timeoutSeconds = 0,
            lockoutUntil = null,
            currentTime = now + 100L
        )
        assertTrue("Immediate timeout policy must require PIN immediately upon resume", required)
        assertFalse(sessionManager.isSessionActive)
    }

    @Test
    fun `active PIN lockout forces gate even with active session`() {
        val now = 1000000L
        sessionManager.grantSession()

        val required = sessionManager.isAuthRequired(
            selfLockEnabled = true,
            onboardingComplete = true,
            timeoutSeconds = 30,
            lockoutUntil = now + 30000L,
            currentTime = now
        )
        assertTrue("Lockout must force gate even if session was previously granted", required)
    }

    @Test
    fun `explicit invalidate clears session`() {
        sessionManager.grantSession()
        assertTrue(sessionManager.isSessionActive)
        sessionManager.invalidateSession()
        assertFalse(sessionManager.isSessionActive)
    }

    @Test
    fun `process death simulation does not inherit authenticated session`() {
        // First process session
        sessionManager.grantSession()
        assertTrue(sessionManager.isSessionActive)

        // Process dies and new process launches
        val freshProcessManager = SelfLockSessionManager()
        assertFalse(freshProcessManager.isSessionActive)
        val required = freshProcessManager.isAuthRequired(
            selfLockEnabled = true,
            onboardingComplete = true,
            timeoutSeconds = 30,
            lockoutUntil = null
        )
        assertTrue("New process after termination must require authentication", required)
    }
}
