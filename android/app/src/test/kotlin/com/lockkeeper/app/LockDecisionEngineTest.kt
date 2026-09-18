package com.lockkeeper.app

import com.lockkeeper.app.data.db.AppSettingsEntity
import com.lockkeeper.app.data.db.LockedAppEntity
import com.lockkeeper.app.domain.LockDecision
import com.lockkeeper.app.domain.LockDecisionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LockDecisionEngineTest {

    private lateinit var engine: LockDecisionEngine

    @Before
    fun setUp() {
        engine = LockDecisionEngine(appPackageName = "com.lockkeeper.app")
    }

    @Test
    fun `own package is always allowed`() {
        val decision = engine.evaluate(
            targetPackage = "com.lockkeeper.app",
            lockedApp = null,
            appSettings = null
        )
        assertTrue(decision is LockDecision.Allowed)
    }

    @Test
    fun `unlocked app is allowed`() {
        val app = LockedAppEntity(packageName = "com.example.chat", isLocked = false)
        val decision = engine.evaluate(
            targetPackage = "com.example.chat",
            lockedApp = app,
            appSettings = null
        )
        assertTrue(decision is LockDecision.Allowed)
    }

    @Test
    fun `locked app requires PIN when no session exists`() {
        val app = LockedAppEntity(packageName = "com.example.social", isLocked = true)
        val decision = engine.evaluate(
            targetPackage = "com.example.social",
            lockedApp = app,
            appSettings = null
        )
        assertTrue(decision is LockDecision.RequirePin)
        assertEquals("com.example.social", (decision as LockDecision.RequirePin).packageName)
    }

    @Test
    fun `locked app with active session is allowed`() {
        val app = LockedAppEntity(packageName = "com.example.social", isLocked = true)
        engine.grantAppSession("com.example.social")
        val decision = engine.evaluate(
            targetPackage = "com.example.social",
            lockedApp = app,
            appSettings = null
        )
        assertTrue(decision is LockDecision.Allowed)
    }

    @Test
    fun `strict lock in active cooldown blocks PIN and displays cooldown`() {
        val now = 1000000L
        val app = LockedAppEntity(
            packageName = "com.example.game",
            isLocked = true,
            strictLock = true,
            lockedUntilTimestamp = now + 60000L // 60s remaining
        )
        val decision = engine.evaluate(
            targetPackage = "com.example.game",
            lockedApp = app,
            appSettings = null,
            currentTime = now
        )
        assertTrue(decision is LockDecision.StrictCooldown)
        assertEquals(60L, (decision as LockDecision.StrictCooldown).remainingSeconds)
    }

    @Test
    fun `strict lock allows access once cooldown timestamp passes`() {
        val now = 1000000L
        val app = LockedAppEntity(
            packageName = "com.example.game",
            isLocked = true,
            strictLock = true,
            lockedUntilTimestamp = now - 1000L // expired
        )
        val decision = engine.evaluate(
            targetPackage = "com.example.game",
            lockedApp = app,
            appSettings = null,
            currentTime = now
        )
        assertTrue(decision is LockDecision.RequirePin)
    }

    @Test
    fun `lockout state after 5 failed attempts overrides PIN prompt`() {
        val now = 1000000L
        val app = LockedAppEntity(packageName = "com.example.app", isLocked = true)
        val settings = AppSettingsEntity(
            failedPinAttempts = 5,
            pinLockoutUntil = now + 45000L // 45s remaining
        )
        val decision = engine.evaluate(
            targetPackage = "com.example.app",
            lockedApp = app,
            appSettings = settings,
            currentTime = now
        )
        assertTrue(decision is LockDecision.PinLockout)
        assertEquals(45L, (decision as LockDecision.PinLockout).remainingSeconds)
    }

    @Test
    fun `admin grace window is granted and expires after 30 seconds`() {
        val now = 1000000L
        engine.grantAdminGraceWindow(currentTime = now)
        assertTrue(engine.isAdminGraceActive(currentTime = now + 15000L))
        assertTrue(!engine.isAdminGraceActive(currentTime = now + 35000L))
    }
}
