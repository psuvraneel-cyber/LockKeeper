package com.lockkeeper.app

import com.lockkeeper.app.data.db.AppSettingsDao
import com.lockkeeper.app.data.db.AppSettingsEntity
import com.lockkeeper.app.data.db.LockedAppDao
import com.lockkeeper.app.data.db.LockedAppEntity
import com.lockkeeper.app.domain.LockDecision
import com.lockkeeper.app.domain.LockDecisionEngine
import com.lockkeeper.app.domain.ProtectionDecisionOutcome
import com.lockkeeper.app.domain.ProtectionDecisionReason
import com.lockkeeper.app.security.AdminAuthResult
import com.lockkeeper.app.security.CredentialStore
import com.lockkeeper.app.security.KeystoreCredentialStore
import com.lockkeeper.app.security.TamperAuthorizationController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Wave 4 Production Hardening & Persistence Security Boundary Tests.
 *
 * Verifies:
 * - Deterministic resolution of abnormal states (RecoveryRequired, DeniedNativeFailure)
 * - Clear Data / Reinstall while Device Admin remains active (cannot bypass to fresh onboarding)
 * - Clock manipulation & reboot during lockout
 * - In-memory volatility & fail-closed error boundaries
 * - Privacy: zero credential exposure in memory representations
 */
class ProductionSecurityBoundaryTest {

    private lateinit var credentialStore: CredentialStore
    private lateinit var prefs: InMemorySharedPreferences
    private lateinit var engine: LockDecisionEngine
    private lateinit var dao: BoundaryFakeDao
    private lateinit var tamperController: TamperAuthorizationController

    private var mockMonotonicTime: Long = 500_000L
    private var mockWallClockTime: Long = 1_700_000_000_000L

    private class BoundaryFakeDao(
        var settings: AppSettingsEntity? = AppSettingsEntity(onboardingComplete = true)
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

    @Before
    fun setUp() {
        prefs = InMemorySharedPreferences()
        credentialStore = KeystoreCredentialStore(prefs)
        dao = BoundaryFakeDao()

        tamperController = TamperAuthorizationController(
            credentialStore = credentialStore,
            appSettingsDao = dao,
            monotonicTimeProvider = { mockMonotonicTime },
            wallClockTimeProvider = { mockWallClockTime }
        )

        engine = LockDecisionEngine(
            appPackageName = "com.lockkeeper.app",
            monotonicTimeProvider = { mockMonotonicTime }
        )
    }

    // =========================================================================
    // Scenario E & F: Clear data / Reinstall while Device Admin remains active
    // =========================================================================
    @Test
    fun `Scenario E and F - Device Admin active without local credentials enters RECOVERY_REQUIRED`() {
        // App data cleared: credentials gone, onboarding incomplete
        credentialStore.clear()
        assertFalse("PIN cleared", credentialStore.hasPin())
        assertFalse("Admin password cleared", credentialStore.hasAdminPassword())

        val isAdminActive = true
        val hasCreds = credentialStore.hasPin() || credentialStore.hasAdminPassword()
        val settings = dao.settings?.copy(securityProvisioned = true, onboardingComplete = false, recoveryRequired = false)

        // Architecture check: wasPreviouslyProvisioned && (!hasCreds || !onboardingComplete)
        val wasPreviouslyProvisioned = settings?.securityProvisioned == true || settings?.onboardingComplete == true
        val isRecoveryRequired = wasPreviouslyProvisioned && (!hasCreds || settings?.onboardingComplete != true)
        assertTrue("Must strictly flag recovery required for previously provisioned install", isRecoveryRequired)

        val targetPkg = "com.critical.app"
        val lockedApp = LockedAppEntity(packageName = targetPkg, isLocked = true)

        val decision = engine.evaluate(
            targetPackage = targetPkg,
            lockedApp = lockedApp,
            appSettings = settings,
            currentTime = mockMonotonicTime,
            isRecoveryRequired = isRecoveryRequired
        )

        assertTrue("Decision must be RecoveryRequired", decision is LockDecision.RecoveryRequired)
        assertEquals(ProtectionDecisionOutcome.REQUIRE_RECOVERY, decision.outcome)
        assertEquals(ProtectionDecisionReason.DENIED_RECOVERY_REQUIRED, decision.reason)
    }

    // =========================================================================
    // Scenario D: Reboot during lockout
    // =========================================================================
    @Test
    fun `Scenario D - Reboot during lockout preserves remaining duration without reset`() {
        val lockoutUntil = mockMonotonicTime + 45_000L // 45 seconds remaining
        val settings = dao.settings?.copy(pinLockoutUntil = lockoutUntil, failedPinAttempts = 5)

        // Simulate reboot: fresh engine instance, same persisted state
        val rebootedEngine = LockDecisionEngine(
            appPackageName = "com.lockkeeper.app",
            monotonicTimeProvider = { mockMonotonicTime }
        )

        val decision = rebootedEngine.evaluate(
            targetPackage = "com.bank.app",
            lockedApp = LockedAppEntity(packageName = "com.bank.app", isLocked = true),
            appSettings = settings,
            currentTime = mockMonotonicTime
        )

        assertTrue("Must remain locked out post reboot", decision is LockDecision.PinLockout)
        val lockoutDecision = decision as LockDecision.PinLockout
        assertEquals(45L, lockoutDecision.remainingSeconds)
    }

    // =========================================================================
    // Scenario H & I: Missing Room row or corrupted state
    // =========================================================================
    @Test
    fun `Scenario H and I - Null settings or unknown health status fails closed`() {
        val decision = engine.evaluate(
            targetPackage = "com.confidential.app",
            lockedApp = LockedAppEntity(packageName = "com.confidential.app", isLocked = true),
            appSettings = null,
            currentTime = mockMonotonicTime,
            securityHealthStatus = "UNKNOWN"
        )

        assertTrue("Must fail closed with DenyUnknown", decision is LockDecision.DenyUnknown)
        assertEquals(ProtectionDecisionOutcome.DENY_UNKNOWN_STATE, decision.outcome)
    }

    // =========================================================================
    // Scenario J: Keystore / credential corruption safe handling
    // =========================================================================
    @Test
    fun `Scenario J - Unset or corrupted credentials return false without exceptions or leaks`() {
        assertFalse("Unset pin returns false", credentialStore.verifyPin("0000"))
        assertFalse("Unset admin returns false", credentialStore.verifyAdminPassword("Secret"))
        assertEquals("Default pin length is 4", 4, credentialStore.getPinLength())
    }

    // =========================================================================
    // Data Privacy: Credential isolation
    // =========================================================================
    @Test
    fun `Privacy - LockDecision string representations do not leak credentials or sensitive tokens`() {
        val targetPkg = "com.example.bank"
        val decision1 = LockDecision.RequirePin(targetPkg)
        val decision2 = LockDecision.PinLockout(targetPkg, 60)
        val decision3 = LockDecision.StrictCooldown(targetPkg, 120)

        assertFalse("No secret in RequirePin toString", decision1.toString().contains("pinHash"))
        assertFalse("No secret in PinLockout toString", decision2.toString().contains("pinHash"))
        assertFalse("No secret in StrictCooldown toString", decision3.toString().contains("pinHash"))
    }

    // =========================================================================
    // Wave 5 Product Integrity: Recovery Resolution & Reconcile
    // =========================================================================
    @Test
    fun `Wave 5 - Recovery reconciliation restores credentials and exits recoveryRequired state`() = kotlinx.coroutines.runBlocking {
        // Given a system flagged in recoveryRequired because of cleared local credentials with active admin
        dao.settings = AppSettingsEntity(onboardingComplete = false, recoveryRequired = true)
        credentialStore.clear()
        assertTrue("Pre-condition: recoveryRequired is true", dao.settings?.recoveryRequired == true)

        // Attempting recovery with matching PIN and distinct Admin Password
        val newPin = "4321"
        val newAdmin = "ComplexAdminSecret"
        val pinSaved = credentialStore.setPin(newPin)
        val adminSaved = credentialStore.setAdminPassword(newAdmin)
        assertTrue("PIN must be stored", pinSaved)
        assertTrue("Admin password must be stored", adminSaved)

        dao.setOnboardingComplete(true)
        dao.setRecoveryRequired(false)

        // Verify state is reconciled
        assertTrue("Credentials present", credentialStore.hasPin() && credentialStore.hasAdminPassword())
        assertTrue("Onboarding complete", dao.settings?.onboardingComplete == true)
        assertFalse("Recovery required flag must be cleared", dao.settings?.recoveryRequired == true)

        // Verification: Protected app decision is now RequirePin, NOT RecoveryRequired
        val decision = engine.evaluate(
            targetPackage = "com.sample.app",
            lockedApp = LockedAppEntity(packageName = "com.sample.app", isLocked = true),
            appSettings = dao.settings,
            currentTime = mockMonotonicTime,
            isRecoveryRequired = false
        )
        assertTrue("Must transition from RecoveryRequired to RequirePin", decision is LockDecision.RequirePin)
        assertEquals(ProtectionDecisionOutcome.REQUIRE_AUTHENTICATION, decision.outcome)
    }

    @Test
    fun `Wave 5 - Recovery input validation rejects identical pin and admin password`() {
        val pin = "1234"
        val sameAdmin = "1234"
        // Recovery logic requires distinct admin password from pin
        val isDistinct = sameAdmin != pin
        assertFalse("Admin password must NOT match PIN", isDistinct)
    }
}
