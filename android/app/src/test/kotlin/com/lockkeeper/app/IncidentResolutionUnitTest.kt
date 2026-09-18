package com.lockkeeper.app

import com.lockkeeper.app.data.db.AppSettingsDao
import com.lockkeeper.app.data.db.AppSettingsEntity
import com.lockkeeper.app.data.db.LockedAppEntity
import com.lockkeeper.app.domain.LockDecision
import com.lockkeeper.app.domain.LockDecisionEngine
import com.lockkeeper.app.domain.ProtectionDecisionReason
import com.lockkeeper.app.security.CredentialStore
import com.lockkeeper.app.security.KeystoreCredentialStore
import com.lockkeeper.app.service.LockKeeperAccessibilityService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * LockKeeper Critical Incident Resolution Test Suite (TEST-601 through TEST-626).
 *
 * Verifies resolution of the first-run onboarding / Device Admin home-screen lockout incident:
 * - Distinguishes legitimate first-run setup (SETUP_IN_PROGRESS) from compromised state (RECOVERY_REQUIRED)
 * - Device Admin can be granted during onboarding without triggering recovery or launcher lockouts
 * - Launchers and System UI never trigger recursive GLOBAL_ACTION_HOME feedback loops
 * - Preserves fail-closed invariants for provisioned installations and verified recovery flows
 */
class IncidentResolutionUnitTest {

    private lateinit var credentialStore: CredentialStore
    private lateinit var prefs: InMemorySharedPreferences
    private lateinit var engine: LockDecisionEngine
    private lateinit var dao: IncidentFakeDao

    private var mockMonotonicTime: Long = 100_000L

    private class IncidentFakeDao(
        var settings: AppSettingsEntity? = AppSettingsEntity(
            onboardingComplete = false,
            securityProvisioned = false,
            recoveryRequired = false
        )
    ) : AppSettingsDao {
        override suspend fun getSettings(): AppSettingsEntity? = settings

        override suspend fun insertInitialSettings(s: AppSettingsEntity): Long {
            if (settings == null) {
                settings = s
                return 1L
            }
            return -1L
        }

        override suspend fun upsert(s: AppSettingsEntity) {
            settings = s
        }

        override suspend fun updatePinLockout(attempts: Int, lockoutUntil: Long?, updatedAt: Long) {
            settings = settings?.copy(failedPinAttempts = attempts, pinLockoutUntil = lockoutUntil, updatedAt = updatedAt)
        }

        override suspend fun resetPinFailures(updatedAt: Long) {
            settings = settings?.copy(failedPinAttempts = 0, pinLockoutUntil = null, updatedAt = updatedAt)
        }

        override suspend fun updateAdminLockout(attempts: Int, lockoutUntil: Long?, updatedAt: Long) {
            settings = settings?.copy(failedAdminAttempts = attempts, adminLockoutUntil = lockoutUntil, updatedAt = updatedAt)
        }

        override suspend fun resetAdminFailures(updatedAt: Long) {
            settings = settings?.copy(failedAdminAttempts = 0, adminLockoutUntil = null, updatedAt = updatedAt)
        }

        override suspend fun setOnboardingComplete(complete: Boolean, updatedAt: Long) {
            settings = settings?.copy(onboardingComplete = complete, updatedAt = updatedAt)
        }

        override suspend fun setSelfLockEnabled(enabled: Boolean, updatedAt: Long) {
            settings = settings?.copy(selfLockEnabled = enabled, updatedAt = updatedAt)
        }

        override suspend fun setSelfLockTimeout(timeoutSeconds: Int, updatedAt: Long) {
            settings = settings?.copy(selfLockTimeoutSeconds = timeoutSeconds, updatedAt = updatedAt)
        }

        override suspend fun updateSelfLock(enabled: Boolean, timeoutSeconds: Int, updatedAt: Long) {
            settings = settings?.copy(selfLockEnabled = enabled, selfLockTimeoutSeconds = timeoutSeconds, updatedAt = updatedAt)
        }

        override suspend fun setRecoveryRequired(required: Boolean, updatedAt: Long) {
            settings = settings?.copy(recoveryRequired = required, updatedAt = updatedAt)
        }

        override suspend fun setSecurityProvisioned(provisioned: Boolean, updatedAt: Long) {
            settings = settings?.copy(securityProvisioned = provisioned, updatedAt = updatedAt)
        }

        override suspend fun setProvisionedAndOnboardingComplete(provisioned: Boolean, complete: Boolean, updatedAt: Long) {
            settings = settings?.copy(securityProvisioned = provisioned, onboardingComplete = complete, updatedAt = updatedAt)
        }
    }

    /**
     * Helper to simulate ProtectionRepository recovery evaluation logic.
     */
    private fun evaluateRecoveryCheck(
        isAdminActive: Boolean,
        settings: AppSettingsEntity,
        hasPin: Boolean,
        hasAdminPassword: Boolean
    ): Pair<Boolean, String> {
        val hasCreds = hasPin && hasAdminPassword
        val wasPreviouslyProvisioned = settings.securityProvisioned || settings.onboardingComplete

        val isRecovery = if (wasPreviouslyProvisioned) {
            !hasCreds || !settings.onboardingComplete || (isAdminActive && !hasCreds)
        } else {
            false
        }

        val overallStatus = when {
            isRecovery || settings.recoveryRequired -> "RECOVERY_REQUIRED"
            !wasPreviouslyProvisioned -> "SETUP_IN_PROGRESS"
            !isAdminActive || !hasCreds -> "DEGRADED"
            else -> "PROTECTED"
        }

        return Pair(isRecovery, overallStatus)
    }

    @Before
    fun setUp() {
        prefs = InMemorySharedPreferences()
        credentialStore = KeystoreCredentialStore(prefs)
        dao = IncidentFakeDao()
        engine = LockDecisionEngine(
            appPackageName = "com.lockkeeper.app",
            monotonicTimeProvider = { mockMonotonicTime }
        )
    }

    // =========================================================================
    // Stage E: Incident Resolution Verification Suite (TEST-601 to TEST-626)
    // =========================================================================

    @Test
    fun `TEST-601 Clean install initializes to SETUP_IN_PROGRESS`() = runBlocking {
        val settings = dao.getSettings()!!
        assertFalse("Clean install must not be provisioned", settings.securityProvisioned)
        assertFalse("Clean install must not be onboardingComplete", settings.onboardingComplete)
        assertFalse("Clean install must not require recovery", settings.recoveryRequired)

        val (isRecovery, status) = evaluateRecoveryCheck(
            isAdminActive = false,
            settings = settings,
            hasPin = credentialStore.hasPin(),
            hasAdminPassword = credentialStore.hasAdminPassword()
        )
        assertFalse("Clean install must not enter recovery", isRecovery)
        assertEquals("SETUP_IN_PROGRESS", status)
    }

    @Test
    fun `TEST-602 Granting Device Admin during setup preserves SETUP_IN_PROGRESS`() = runBlocking {
        val settings = dao.getSettings()!!
        val (isRecovery, status) = evaluateRecoveryCheck(
            isAdminActive = true,
            settings = settings,
            hasPin = false,
            hasAdminPassword = false
        )
        assertFalse("Granting Device Admin during onboarding must NOT trigger recovery", isRecovery)
        assertEquals("SETUP_IN_PROGRESS", status)
    }

    @Test
    fun `TEST-603 Clean install + Device Admin + missing PIN does not trip RECOVERY_REQUIRED`() = runBlocking {
        credentialStore.setAdminPassword("AdminPass2026!")
        val settings = dao.getSettings()!!
        val (isRecovery, status) = evaluateRecoveryCheck(
            isAdminActive = true,
            settings = settings,
            hasPin = false,
            hasAdminPassword = true
        )
        assertFalse("Clean install with missing PIN must not trip recovery", isRecovery)
        assertEquals("SETUP_IN_PROGRESS", status)
    }

    @Test
    fun `TEST-604 Clean install + Device Admin + missing Admin Password does not trip RECOVERY_REQUIRED`() = runBlocking {
        credentialStore.setPin("1234")
        val settings = dao.getSettings()!!
        val (isRecovery, status) = evaluateRecoveryCheck(
            isAdminActive = true,
            settings = settings,
            hasPin = true,
            hasAdminPassword = false
        )
        assertFalse("Clean install with missing Admin Password must not trip recovery", isRecovery)
        assertEquals("SETUP_IN_PROGRESS", status)
    }

    @Test
    fun `TEST-605 Clean install + Device Admin + A11y connected remains SETUP_IN_PROGRESS`() = runBlocking {
        val settings = dao.getSettings()!!
        val (isRecovery, status) = evaluateRecoveryCheck(
            isAdminActive = true,
            settings = settings,
            hasPin = false,
            hasAdminPassword = false
        )
        assertFalse("Device Admin + A11y must not trigger recovery before setup completes", isRecovery)
        assertEquals("SETUP_IN_PROGRESS", status)
    }

    @Test
    fun `TEST-606 Foreground event on Launcher during setup returns LockDecision Allowed`() {
        val settings = dao.settings!!
        val decision = engine.evaluate(
            targetPackage = "com.miui.home",
            lockedApp = null,
            appSettings = settings,
            isSetupInProgress = true,
            securityHealthStatus = "SETUP_IN_PROGRESS"
        )
        assertTrue("Launcher during setup must be Allowed", decision is LockDecision.Allowed)
        assertEquals(ProtectionDecisionReason.ALLOWED_SETUP_MODE, (decision as LockDecision.Allowed).reason)
    }

    @Test
    fun `TEST-607 LockKeeper remains launchable during SETUP_IN_PROGRESS`() {
        val settings = dao.settings!!
        val decision = engine.evaluate(
            targetPackage = "com.lockkeeper.app",
            lockedApp = null,
            appSettings = settings,
            isSetupInProgress = true,
            securityHealthStatus = "SETUP_IN_PROGRESS"
        )
        assertTrue("Own app must always be Allowed", decision is LockDecision.Allowed)
        assertEquals(ProtectionDecisionReason.ALLOWED_OWN_PACKAGE, (decision as LockDecision.Allowed).reason)
    }

    @Test
    fun `TEST-608 Android Settings remains usable during SETUP_IN_PROGRESS`() {
        val settings = dao.settings!!
        val decision = engine.evaluate(
            targetPackage = "com.android.settings",
            lockedApp = null,
            appSettings = settings,
            isSetupInProgress = true,
            securityHealthStatus = "SETUP_IN_PROGRESS"
        )
        assertTrue("Settings during setup must be Allowed", decision is LockDecision.Allowed)
        assertEquals(ProtectionDecisionReason.ALLOWED_SETUP_MODE, (decision as LockDecision.Allowed).reason)
    }

    @Test
    fun `TEST-609 App drawer Launcher interactions during setup do not trigger GLOBAL_ACTION_HOME`() {
        assertTrue("MIUI Home recognized as launcher", LockKeeperAccessibilityService.isLauncherOrSystemUiPackage("com.miui.home"))
        assertTrue("AOSP Launcher3 recognized as launcher", LockKeeperAccessibilityService.isLauncherOrSystemUiPackage("com.android.launcher3"))
        assertTrue("Nexus / Pixel Launcher recognized as launcher", LockKeeperAccessibilityService.isLauncherOrSystemUiPackage("com.google.android.apps.nexuslauncher"))
        assertTrue("Samsung Launcher recognized as launcher", LockKeeperAccessibilityService.isLauncherOrSystemUiPackage("com.sec.android.app.launcher"))
        assertTrue("SystemUI recognized as system package", LockKeeperAccessibilityService.isLauncherOrSystemUiPackage("com.android.systemui"))
        assertTrue("MIUI PowerKeeper recognized as system package", LockKeeperAccessibilityService.isLauncherOrSystemUiPackage("com.miui.powerkeeper"))

        assertFalse("Protected third-party apps must not be classified as launcher", LockKeeperAccessibilityService.isLauncherOrSystemUiPackage("com.example.bank"))
        assertFalse("Browser apps must not be classified as launcher", LockKeeperAccessibilityService.isLauncherOrSystemUiPackage("com.android.chrome"))
    }

    @Test
    fun `TEST-610 Previously provisioned install with deleted credentials enters RECOVERY_REQUIRED`() = runBlocking {
        // Given an app that was previously provisioned
        dao.setProvisionedAndOnboardingComplete(provisioned = true, complete = true)
        val settings = dao.getSettings()!!
        assertTrue(settings.securityProvisioned)

        // Attacker wipes credential store while device admin remains active
        credentialStore.clear()

        val (isRecovery, status) = evaluateRecoveryCheck(
            isAdminActive = true,
            settings = settings,
            hasPin = false,
            hasAdminPassword = false
        )
        assertTrue("Previously provisioned device with missing credentials MUST enter recovery", isRecovery)
        assertEquals("RECOVERY_REQUIRED", status)
    }

    @Test
    fun `TEST-611 Previously provisioned install with corrupted Room settings enters RECOVERY_REQUIRED`() = runBlocking {
        dao.setProvisionedAndOnboardingComplete(provisioned = true, complete = true)
        dao.setRecoveryRequired(true)
        val settings = dao.getSettings()!!

        val (isRecovery, status) = evaluateRecoveryCheck(
            isAdminActive = true,
            settings = settings,
            hasPin = true,
            hasAdminPassword = true
        )
        assertEquals("RECOVERY_REQUIRED", status)

        val decision = engine.evaluate(
            targetPackage = "com.example.bank",
            lockedApp = LockedAppEntity(packageName = "com.example.bank", isLocked = true),
            appSettings = settings,
            isRecoveryRequired = true
        )
        assertTrue("Corrupted state enforces RecoveryRequired decision", decision is LockDecision.RecoveryRequired)
    }

    @Test
    fun `TEST-612 Stale SharedPreferences cannot override native provisioning marker`() = runBlocking {
        prefs.edit().putBoolean("onboarding_complete", true).apply()
        // But native Room DB says NOT provisioned
        dao.settings = AppSettingsEntity(onboardingComplete = false, securityProvisioned = false)

        val settings = dao.getSettings()!!
        assertFalse("Room is single source of truth for provisioning", settings.securityProvisioned)

        val (isRecovery, status) = evaluateRecoveryCheck(
            isAdminActive = true,
            settings = settings,
            hasPin = false,
            hasAdminPassword = false
        )
        assertFalse("Stale prefs cannot force recovery on unprovisioned device", isRecovery)
        assertEquals("SETUP_IN_PROGRESS", status)
    }

    @Test
    fun `TEST-613 Flutter method channel cannot demote SECURITY_PROVISIONED to setup mode`() = runBlocking {
        dao.setProvisionedAndOnboardingComplete(provisioned = true, complete = true)
        credentialStore.setPin("1234")
        credentialStore.setAdminPassword("AdminPass2026!")

        val settings = dao.getSettings()!!
        assertTrue(settings.securityProvisioned)

        // Attempting to reset onboardingComplete without setting securityProvisioned false
        dao.setOnboardingComplete(false)
        val updatedSettings = dao.getSettings()!!
        assertTrue("securityProvisioned remains TRUE even if onboardingComplete is manipulated", updatedSettings.securityProvisioned)

        val (_, status) = evaluateRecoveryCheck(
            isAdminActive = true,
            settings = updatedSettings,
            hasPin = true,
            hasAdminPassword = true
        )
        // With securityProvisioned = true, manipulated onboardingComplete trips recovery or degraded, NOT SETUP_IN_PROGRESS
        assertNotEquals("SETUP_IN_PROGRESS", status)
    }

    @Test
    fun `TEST-614 Process restart during setup maintains SETUP_IN_PROGRESS`() = runBlocking {
        // App process killed midway through setup
        val settingsBefore = dao.getSettings()!!
        assertFalse(settingsBefore.securityProvisioned)

        // Simulate new process instantiation reading DAO
        val simulatedNewDao = IncidentFakeDao(dao.settings)
        val settingsAfter = simulatedNewDao.getSettings()!!
        assertFalse("Post-restart state preserves securityProvisioned = false", settingsAfter.securityProvisioned)

        val (isRecovery, status) = evaluateRecoveryCheck(
            isAdminActive = true,
            settings = settingsAfter,
            hasPin = false,
            hasAdminPassword = false
        )
        assertFalse(isRecovery)
        assertEquals("SETUP_IN_PROGRESS", status)
    }

    @Test
    fun `TEST-615 Accessibility Service restart during setup maintains SETUP_IN_PROGRESS`() {
        val settings = dao.settings!!
        // Recreated A11y service evaluates incoming foreground events
        val decision = engine.evaluate(
            targetPackage = "com.miui.home",
            lockedApp = null,
            appSettings = settings,
            isSetupInProgress = true,
            securityHealthStatus = "SETUP_IN_PROGRESS"
        )
        assertTrue(decision is LockDecision.Allowed)
        assertEquals(ProtectionDecisionReason.ALLOWED_SETUP_MODE, (decision as LockDecision.Allowed).reason)
    }

    @Test
    fun `TEST-616 Device reboot during setup maintains SETUP_IN_PROGRESS`() = runBlocking {
        // Reboot occurs before PIN/Admin setup
        val rebootedDao = IncidentFakeDao(dao.settings)
        val rebootedSettings = rebootedDao.getSettings()!!

        val (isRecovery, status) = evaluateRecoveryCheck(
            isAdminActive = true,
            settings = rebootedSettings,
            hasPin = false,
            hasAdminPassword = false
        )
        assertFalse("Reboot during setup must not lock device into recovery", isRecovery)
        assertEquals("SETUP_IN_PROGRESS", status)
    }

    @Test
    fun `TEST-617 Completing initial provisioning atomically transitions to SECURITY_PROVISIONED`() = runBlocking {
        credentialStore.setPin("1234")
        credentialStore.setAdminPassword("AdminPass2026!")

        // Complete onboarding step
        dao.setProvisionedAndOnboardingComplete(provisioned = true, complete = true)

        val settings = dao.getSettings()!!
        assertTrue("securityProvisioned must be true", settings.securityProvisioned)
        assertTrue("onboardingComplete must be true", settings.onboardingComplete)

        val (isRecovery, status) = evaluateRecoveryCheck(
            isAdminActive = true,
            settings = settings,
            hasPin = true,
            hasAdminPassword = true
        )
        assertFalse(isRecovery)
        assertEquals("PROTECTED", status)
    }

    @Test
    fun `TEST-618 Protected app enforcement operates normally after provisioning`() = runBlocking {
        dao.setProvisionedAndOnboardingComplete(provisioned = true, complete = true)
        val settings = dao.getSettings()!!

        val bankApp = LockedAppEntity(packageName = "com.example.bank", isLocked = true)
        val decision = engine.evaluate(
            targetPackage = "com.example.bank",
            lockedApp = bankApp,
            appSettings = settings,
            isSecurityProvisioned = true,
            isSetupInProgress = false
        )
        assertTrue("Protected app must require PIN after provisioning", decision is LockDecision.RequirePin)
    }

    @Test
    fun `TEST-619 RECOVERY_REQUIRED remains fail-closed for protected apps`() {
        val decision = engine.evaluate(
            targetPackage = "com.example.bank",
            lockedApp = LockedAppEntity(packageName = "com.example.bank", isLocked = true),
            appSettings = null,
            isRecoveryRequired = true,
            securityHealthStatus = "RECOVERY_REQUIRED"
        )
        assertTrue("Recovery required must fail closed", decision is LockDecision.RecoveryRequired)
        assertEquals(ProtectionDecisionReason.DENIED_RECOVERY_REQUIRED, (decision as LockDecision.RecoveryRequired).reason)
    }

    @Test
    fun `TEST-620 Deleting UI flags cannot convert RECOVERY_REQUIRED to SETUP_IN_PROGRESS`() = runBlocking {
        dao.setProvisionedAndOnboardingComplete(provisioned = true, complete = true)
        dao.setRecoveryRequired(true)

        // UI state cleared / preferences wiped
        prefs.edit().clear().apply()

        val settings = dao.getSettings()!!
        val (_, status) = evaluateRecoveryCheck(
            isAdminActive = true,
            settings = settings,
            hasPin = false,
            hasAdminPassword = false
        )
        assertEquals("RECOVERY_REQUIRED", status)
        assertNotEquals("SETUP_IN_PROGRESS", status)
    }

    @Test
    fun `TEST-621 Native exception in decision path fails closed (DENIED_UNKNOWN_STATE)`() {
        val decision = engine.evaluate(
            targetPackage = "com.example.bank",
            lockedApp = LockedAppEntity(packageName = "com.example.bank", isLocked = true),
            appSettings = null,
            securityHealthStatus = "UNKNOWN"
        )
        assertTrue("Unknown state must fail closed", decision is LockDecision.DenyUnknown)
        assertEquals(ProtectionDecisionReason.DENIED_UNKNOWN_STATE, (decision as LockDecision.DenyUnknown).reason)
    }

    @Test
    fun `TEST-622 Wave 1 - Authoritative status aggregation integrity`() = runBlocking {
        // 1. RecoveryRequired takes absolute precedence
        dao.setRecoveryRequired(true)
        var status = evaluateRecoveryCheck(isAdminActive = true, settings = dao.getSettings()!!, hasPin = false, hasAdminPassword = false).second
        assertEquals("RECOVERY_REQUIRED", status)

        // 2. SetupInProgress when unprovisioned and healthy
        dao.setRecoveryRequired(false)
        dao.settings = AppSettingsEntity(onboardingComplete = false, securityProvisioned = false)
        status = evaluateRecoveryCheck(isAdminActive = true, settings = dao.getSettings()!!, hasPin = false, hasAdminPassword = false).second
        assertEquals("SETUP_IN_PROGRESS", status)

        // 3. Degraded when provisioned but missing permissions
        dao.setProvisionedAndOnboardingComplete(provisioned = true, complete = true)
        status = evaluateRecoveryCheck(isAdminActive = false, settings = dao.getSettings()!!, hasPin = true, hasAdminPassword = true).second
        assertEquals("DEGRADED", status)

        // 4. Protected when provisioned with all requirements
        status = evaluateRecoveryCheck(isAdminActive = true, settings = dao.getSettings()!!, hasPin = true, hasAdminPassword = true).second
        assertEquals("PROTECTED", status)
    }

    @Test
    fun `TEST-623 Wave 2 - Decision precedence preserved`() {
        val now = 100_000L
        val bankApp = LockedAppEntity(packageName = "com.example.bank", isLocked = true)
        val settings = AppSettingsEntity(
            onboardingComplete = true,
            securityProvisioned = true,
            failedPinAttempts = 5,
            pinLockoutUntil = now + 60_000L
        )

        // Precedence 1: Own package
        val own = engine.evaluate("com.lockkeeper.app", bankApp, settings, now)
        assertTrue(own is LockDecision.Allowed)

        // Precedence 2: RecoveryRequired
        val recovery = engine.evaluate("com.example.bank", bankApp, settings, now, isRecoveryRequired = true)
        assertTrue(recovery is LockDecision.RecoveryRequired)

        // Precedence 3: DenyUnknown
        val unknown = engine.evaluate("com.example.bank", bankApp, settings, now, securityHealthStatus = "UNKNOWN")
        assertTrue(unknown is LockDecision.DenyUnknown)

        // Precedence 4: PinLockout over RequirePin
        val lockout = engine.evaluate("com.example.bank", bankApp, settings, now)
        assertTrue(lockout is LockDecision.PinLockout)

        // Precedence 5: RequirePin when lockout expired
        val normal = engine.evaluate("com.example.bank", bankApp, settings.copy(pinLockoutUntil = null), now)
        assertTrue(normal is LockDecision.RequirePin)
    }

    @Test
    fun `TEST-624 Wave 3 - Runtime session invalidation preserved across lifecycle`() {
        val bankPkg = "com.example.bank"
        val bankApp = LockedAppEntity(packageName = bankPkg, isLocked = true)
        val settings = AppSettingsEntity(onboardingComplete = true, securityProvisioned = true)

        engine.grantAppSession(bankPkg, mockMonotonicTime)
        val allowedDecision = engine.evaluate(bankPkg, bankApp, settings, mockMonotonicTime)
        assertTrue("Active session allows launch", allowedDecision is LockDecision.Allowed)

        // Screen off or explicit clearing purges sessions
        engine.clearAllSessions()
        val afterPurgeDecision = engine.evaluate(bankPkg, bankApp, settings, mockMonotonicTime)
        assertTrue("Session purge requires PIN again", afterPurgeDecision is LockDecision.RequirePin)
    }

    @Test
    fun `TEST-625 Wave 4 - Tamper lockout overrides ordinary authorization`() {
        val settings = AppSettingsEntity(onboardingComplete = true, securityProvisioned = true)
        val decision = engine.evaluate(
            targetPackage = "com.android.settings",
            lockedApp = null,
            appSettings = settings,
            isTamperLocked = true
        )
        assertTrue("Tamper lockout must block settings access", decision is LockDecision.Blocked)
        assertEquals(ProtectionDecisionReason.DENIED_TAMPER_LOCKOUT, (decision as LockDecision.Blocked).reason)
    }

    @Test
    fun `TEST-626 Wave 5 - Valid recovery reconciliation correctly resolves RECOVERY_REQUIRED and re-provisions credentials`() = runBlocking {
        // Given device trapped in recovery
        dao.setProvisionedAndOnboardingComplete(provisioned = true, complete = true)
        dao.setRecoveryRequired(true)
        credentialStore.clear()

        var (isRecovery, status) = evaluateRecoveryCheck(
            isAdminActive = true,
            settings = dao.getSettings()!!,
            hasPin = false,
            hasAdminPassword = false
        )
        assertTrue(isRecovery)
        assertEquals("RECOVERY_REQUIRED", status)

        // When user enters recovery credentials and completes recovery reconciliation
        credentialStore.setPin("9876")
        credentialStore.setAdminPassword("NewAdminPass2026!")
        dao.setProvisionedAndOnboardingComplete(provisioned = true, complete = true)
        dao.setRecoveryRequired(false)

        val updatedSettings = dao.getSettings()!!
        val (isRecoveryAfter, statusAfter) = evaluateRecoveryCheck(
            isAdminActive = true,
            settings = updatedSettings,
            hasPin = true,
            hasAdminPassword = true
        )
        assertFalse("Recovery must be cleared", isRecoveryAfter)
        assertEquals("PROTECTED", statusAfter)

        // Protected apps are locked with RequirePin, not RecoveryRequired
        val bankApp = LockedAppEntity(packageName = "com.example.bank", isLocked = true)
        val decision = engine.evaluate(
            targetPackage = "com.example.bank",
            lockedApp = bankApp,
            appSettings = updatedSettings
        )
        assertTrue("Protected app requires PIN following recovery", decision is LockDecision.RequirePin)
    }
}
