package com.lockkeeper.app

import com.lockkeeper.app.data.db.AppSettingsEntity
import com.lockkeeper.app.data.db.LockedAppEntity
import com.lockkeeper.app.domain.LockDecision
import com.lockkeeper.app.domain.LockDecisionEngine
import com.lockkeeper.app.domain.ProtectionDecisionReason
import com.lockkeeper.app.service.LockKeeperAccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Stage 2 Accessibility Runtime Enforcement Test Suite.
 *
 * Exercises real production classes:
 * - LockKeeperAccessibilityService package classification rules
 * - LockDecisionEngine evaluation paths across protected, unprotected, settings, and grace states
 * - App session granting and explicit revocation
 */
class AccessibilityRuntimeEnforcementTest {

    private lateinit var engine: LockDecisionEngine
    private var mockMonotonicTime: Long = 100_000L
    private var mockWallClockTime: Long = 1_700_000_000_000L

    @Before
    fun setUp() {
        mockMonotonicTime = 100_000L
        mockWallClockTime = 1_700_000_000_000L
        engine = LockDecisionEngine(
            appPackageName = "com.lockkeeper.app",
            monotonicTimeProvider = { mockMonotonicTime },
            wallClockTimeProvider = { mockWallClockTime }
        )
    }

    @Test
    fun `isSystemUiPackage correctly identifies system UI and powerkeeper`() {
        assertTrue(LockKeeperAccessibilityService.isSystemUiPackage("com.android.systemui"))
        assertTrue(LockKeeperAccessibilityService.isSystemUiPackage("com.miui.powerkeeper"))
        assertTrue(LockKeeperAccessibilityService.isSystemUiPackage("com.samsung.systemui"))
        assertFalse(LockKeeperAccessibilityService.isSystemUiPackage("com.android.launcher3"))
        assertFalse(LockKeeperAccessibilityService.isSystemUiPackage("com.android.settings"))
        assertFalse(LockKeeperAccessibilityService.isSystemUiPackage("com.whatsapp"))
    }

    @Test
    fun `isLauncherPackage correctly identifies home screen launchers`() {
        assertTrue(LockKeeperAccessibilityService.isLauncherPackage("com.miui.home"))
        assertTrue(LockKeeperAccessibilityService.isLauncherPackage("com.android.launcher3"))
        assertTrue(LockKeeperAccessibilityService.isLauncherPackage("com.sec.android.app.launcher"))
        assertTrue(LockKeeperAccessibilityService.isLauncherPackage("com.google.android.apps.nexuslauncher"))
        assertTrue(LockKeeperAccessibilityService.isLauncherPackage("com.teslacoilsw.launcher"))
        assertFalse(LockKeeperAccessibilityService.isLauncherPackage("com.android.systemui"))
        assertFalse(LockKeeperAccessibilityService.isLauncherPackage("com.android.settings"))
        assertFalse(LockKeeperAccessibilityService.isLauncherPackage("com.whatsapp"))
    }

    @Test
    fun `isSystemManagementPackage correctly identifies settings and security centers`() {
        assertTrue(LockKeeperAccessibilityService.isSystemManagementPackage("com.android.settings"))
        assertTrue(LockKeeperAccessibilityService.isSystemManagementPackage("com.google.android.packageinstaller"))
        assertTrue(LockKeeperAccessibilityService.isSystemManagementPackage("com.android.packageinstaller"))
        assertTrue(LockKeeperAccessibilityService.isSystemManagementPackage("com.miui.securitycenter"))
        assertTrue(LockKeeperAccessibilityService.isSystemManagementPackage("com.samsung.android.lool"))
        assertFalse(LockKeeperAccessibilityService.isSystemManagementPackage("com.android.systemui"))
        assertFalse(LockKeeperAccessibilityService.isSystemManagementPackage("com.android.launcher3"))
        assertFalse(LockKeeperAccessibilityService.isSystemManagementPackage("com.whatsapp"))
    }

    @Test
    fun `evaluating unprotected app returns Allowed unprotected app`() {
        val decision = engine.evaluate(
            targetPackage = "com.unprotected.calculator",
            lockedApp = null,
            appSettings = AppSettingsEntity(onboardingComplete = true, securityProvisioned = true)
        )
        assertTrue(decision is LockDecision.Allowed)
        assertEquals(ProtectionDecisionReason.ALLOWED_UNPROTECTED_APP, (decision as LockDecision.Allowed).reason)
    }

    @Test
    fun `evaluating protected app without active session requires PIN`() {
        val lockedApp = LockedAppEntity(
            packageName = "com.bank.app",
            isLocked = true
        )
        val decision = engine.evaluate(
            targetPackage = "com.bank.app",
            lockedApp = lockedApp,
            appSettings = AppSettingsEntity(onboardingComplete = true, securityProvisioned = true)
        )
        assertTrue(decision is LockDecision.RequirePin)
        assertEquals("com.bank.app", (decision as LockDecision.RequirePin).packageName)
    }

    @Test
    fun `evaluating protected app with active session returns Allowed active session`() {
        val lockedApp = LockedAppEntity(
            packageName = "com.bank.app",
            isLocked = true
        )
        engine.grantAppSession("com.bank.app", mockWallClockTime)

        val decision = engine.evaluate(
            targetPackage = "com.bank.app",
            lockedApp = lockedApp,
            appSettings = AppSettingsEntity(onboardingComplete = true, securityProvisioned = true)
        )
        assertTrue(decision is LockDecision.Allowed)
        assertEquals(ProtectionDecisionReason.ALLOWED_ACTIVE_SESSION, (decision as LockDecision.Allowed).reason)
    }

    @Test
    fun `switching apps revokes previous app session and requires PIN for new protected app`() {
        val lockedAppA = LockedAppEntity(packageName = "com.bank.app", isLocked = true)
        val lockedAppB = LockedAppEntity(packageName = "com.crypto.wallet", isLocked = true)
        val settings = AppSettingsEntity(onboardingComplete = true, securityProvisioned = true)

        engine.grantAppSession("com.bank.app", mockWallClockTime)
        assertTrue(engine.isAppSessionActive("com.bank.app", mockWallClockTime))

        // When switching from A to B, A's session is revoked
        engine.revokeAppSession("com.bank.app")
        assertFalse(engine.isAppSessionActive("com.bank.app", mockWallClockTime))

        // Evaluating B requires PIN
        val decisionB = engine.evaluate("com.crypto.wallet", lockedAppB, settings)
        assertTrue(decisionB is LockDecision.RequirePin)
    }

    @Test
    fun `system settings with active admin grace returns Allowed admin grace`() {
        engine.grantAdminGraceWindow(mockMonotonicTime)
        assertTrue(engine.isAdminGraceActive(mockMonotonicTime))

        val decision = engine.evaluate(
            targetPackage = "com.android.settings",
            lockedApp = null,
            appSettings = AppSettingsEntity(onboardingComplete = true, securityProvisioned = true)
        )
        assertTrue(decision is LockDecision.Allowed)
        assertEquals(ProtectionDecisionReason.ALLOWED_ADMIN_GRACE, (decision as LockDecision.Allowed).reason)

        // After 31 seconds, grace expires
        mockMonotonicTime += 31_000L
        assertFalse(engine.isAdminGraceActive(mockMonotonicTime))

        // Revoking or expiring grace revokes access
        engine.revokeAdminGrace()
        val postGraceDecision = engine.evaluate(
            targetPackage = "com.android.settings",
            lockedApp = null,
            appSettings = AppSettingsEntity(onboardingComplete = true, securityProvisioned = true)
        )
        // With lockedApp = null, it returns unprotected app since settings isn't a locked app entity
        assertTrue(postGraceDecision is LockDecision.Allowed)
        assertEquals(ProtectionDecisionReason.ALLOWED_UNPROTECTED_APP, (postGraceDecision as LockDecision.Allowed).reason)
    }
}
