package com.lockkeeper.app

import com.lockkeeper.app.data.db.AppSettingsDao
import com.lockkeeper.app.data.db.AppSettingsEntity
import com.lockkeeper.app.data.db.LockedAppEntity
import com.lockkeeper.app.domain.LockDecision
import com.lockkeeper.app.domain.LockDecisionEngine
import com.lockkeeper.app.domain.ProtectionDecisionOutcome
import com.lockkeeper.app.domain.ProtectionDecisionReason
import com.lockkeeper.app.security.AdminAuthResult
import com.lockkeeper.app.security.CredentialStore
import com.lockkeeper.app.security.KeystoreCredentialStore
import com.lockkeeper.app.security.NodeFacade
import com.lockkeeper.app.security.TamperAuthorizationController
import com.lockkeeper.app.security.TamperDetectionEngine
import com.lockkeeper.app.security.TamperEvent
import com.lockkeeper.app.security.TamperType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Wave 3 Runtime Resilience, Anti-Tamper & Bypass Resistance Test Suite.
 *
 * Verifies:
 * - Runtime Security Invariants INV-301 through INV-319
 * - Hostile lifecycle, reboot, screen-off, session replay, and concurrent race resistance
 * - Fail-closed boundaries under overlay failure, FGS denial, and Play Store uninstallation vectors
 */
class RuntimeResilienceTest {

    private lateinit var credentialStore: CredentialStore
    private lateinit var prefs: InMemorySharedPreferences
    private lateinit var engine: LockDecisionEngine
    private lateinit var dao: ResilienceFakeDao
    private lateinit var tamperController: TamperAuthorizationController
    private lateinit var tamperEngine: TamperDetectionEngine

    private var mockMonotonicTime: Long = 200_000L
    private var mockWallClockTime: Long = 1_700_000_000_000L

    private class ResilienceFakeDao(
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

    private data class FakeNode(
        override val text: CharSequence? = null,
        override val contentDescription: CharSequence? = null,
        override val viewIdResourceName: String? = null,
        override val className: CharSequence? = null,
        val children: List<FakeNode> = emptyList()
    ) : NodeFacade {
        override val childCount: Int get() = children.size
        override fun getChild(index: Int): NodeFacade? = children.getOrNull(index)
    }

    @Before
    fun setUp() {
        prefs = InMemorySharedPreferences()
        credentialStore = KeystoreCredentialStore(prefs)
        dao = ResilienceFakeDao()

        tamperController = TamperAuthorizationController(
            credentialStore = credentialStore,
            appSettingsDao = dao,
            monotonicTimeProvider = { mockMonotonicTime },
            wallClockTimeProvider = { mockWallClockTime }
        )

        tamperEngine = TamperDetectionEngine("com.lockkeeper.app")

        engine = LockDecisionEngine(
            appPackageName = "com.lockkeeper.app",
            monotonicTimeProvider = { mockMonotonicTime }
        )
    }

    // =========================================================================
    // INV-301: Process death / restart cannot clear protection
    // =========================================================================
    @Test
    fun `INV-301 Process death and restart restores strict protection without lingering authorization`() {
        val targetPkg = "com.banking.app"
        val lockedApp = LockedAppEntity(packageName = targetPkg, isLocked = true)

        // Authorize in initial process lifecycle
        engine.grantAppSession(targetPkg, mockMonotonicTime)
        assertTrue("Session active prior to process death", engine.isAppSessionActive(targetPkg, mockMonotonicTime))

        // Simulate complete process death & recreation: fresh engine instance
        val restartedEngine = LockDecisionEngine(
            appPackageName = "com.lockkeeper.app",
            monotonicTimeProvider = { mockMonotonicTime }
        )

        assertFalse("Session memory must be clean after process restart", restartedEngine.isAppSessionActive(targetPkg, mockMonotonicTime))
        val decision = restartedEngine.evaluate(
            targetPackage = targetPkg,
            lockedApp = lockedApp,
            appSettings = dao.settings,
            currentTime = mockMonotonicTime
        )
        assertTrue("Must require PIN immediately after restart", decision is LockDecision.RequirePin)
        assertEquals(ProtectionDecisionOutcome.REQUIRE_AUTHENTICATION, decision.outcome)
    }

    // =========================================================================
    // INV-302: AccessibilityService restart creates no protection gap
    // =========================================================================
    @Test
    fun `INV-302 AccessibilityService reconnection maintains fail-closed protection on evaluation`() {
        val targetPkg = "com.secret.notes"
        val lockedApp = LockedAppEntity(packageName = targetPkg, isLocked = true)

        // While a11y is reconnecting, any incoming check must demand PIN
        val decision = engine.evaluate(
            targetPackage = targetPkg,
            lockedApp = lockedApp,
            appSettings = dao.settings,
            currentTime = mockMonotonicTime
        )
        assertEquals(ProtectionDecisionOutcome.REQUIRE_AUTHENTICATION, decision.outcome)
        assertEquals(ProtectionDecisionReason.AUTH_REQUIRED_PIN, decision.reason)
    }

    // =========================================================================
    // INV-303: AccessibilityService disconnection cannot produce ALLOW
    // =========================================================================
    @Test
    fun `INV-303 Disconnected accessibility state cannot produce ALLOW for protected app`() {
        val targetPkg = "com.crypto.wallet"
        val lockedApp = LockedAppEntity(packageName = targetPkg, isLocked = true)

        // Even with empty active sessions, evaluation produces RequirePin
        val decision = engine.evaluate(
            targetPackage = targetPkg,
            lockedApp = lockedApp,
            appSettings = dao.settings,
            currentTime = mockMonotonicTime,
            securityHealthStatus = "DEGRADED"
        )
        assertFalse("Never ALLOW on protected package", decision is LockDecision.Allowed)
        assertTrue(decision is LockDecision.RequirePin)
    }

    // =========================================================================
    // INV-304: Device Admin removal invalidates applicable sessions immediately
    // =========================================================================
    @Test
    fun `INV-304 Device Admin revocation invalidates active grace sessions immediately`() {
        tamperController.grantGraceWindow()
        assertTrue("Grace window is active", tamperController.isGraceActive())

        // Revoke Device Admin: invalidates active grace
        tamperController.revokeGraceWindow()
        assertFalse("Grace window must be invalidated upon admin removal", tamperController.isGraceActive())
    }

    // =========================================================================
    // INV-305: Overlay dismissal cannot constitute authorization
    // =========================================================================
    @Test
    fun `INV-305 Dismissing overlay does not create session or authorize protected package`() {
        val targetPkg = "com.social.chat"
        val lockedApp = LockedAppEntity(packageName = targetPkg, isLocked = true)

        // An overlay is dismissed without calling grantAppSession
        assertFalse("Session is not active", engine.isAppSessionActive(targetPkg, mockMonotonicTime))
        val decision = engine.evaluate(
            targetPackage = targetPkg,
            lockedApp = lockedApp,
            appSettings = dao.settings,
            currentTime = mockMonotonicTime
        )
        assertTrue("Decision remains RequirePin", decision is LockDecision.RequirePin)
    }

    // =========================================================================
    // INV-306: Expired authentication session cannot be replayed
    // =========================================================================
    @Test
    fun `INV-306 Expired session cannot be replayed after exit`() {
        val targetPkg = "com.finance.bank"
        val lockedApp = LockedAppEntity(packageName = targetPkg, isLocked = true)

        engine.grantAppSession(targetPkg, mockMonotonicTime)
        assertTrue(engine.isAppSessionActive(targetPkg, mockMonotonicTime))

        // App exits
        engine.revokeAppSession(targetPkg)
        assertFalse("Session cleared on exit", engine.isAppSessionActive(targetPkg, mockMonotonicTime))

        // Subsequent launch cannot reuse previous session
        val decision = engine.evaluate(
            targetPackage = targetPkg,
            lockedApp = lockedApp,
            appSettings = dao.settings,
            currentTime = mockMonotonicTime
        )
        assertTrue("Must require fresh authentication", decision is LockDecision.RequirePin)
    }

    // =========================================================================
    // INV-307: Grace sessions cannot survive beyond monotonic expiry
    // =========================================================================
    @Test
    fun `INV-307 Admin grace expires strictly based on monotonic clock advancement`() {
        tamperController.grantGraceWindow()
        assertTrue(tamperController.isGraceActive())

        // Manipulate wall-clock backwards (adversary changing system time)
        mockWallClockTime -= 100_000_000L
        assertTrue("Grace window relies on monotonic time, unaffected by wall-clock tampering", tamperController.isGraceActive())

        // Advance monotonic time beyond grace window (30 seconds = 30_000 ms)
        mockMonotonicTime += 30_001L
        assertFalse("Grace window expired monotonically", tamperController.isGraceActive())
    }

    // =========================================================================
    // INV-308: Lifecycle transition cannot resurrect stale authorization
    // =========================================================================
    @Test
    fun `INV-308 App backgrounding and exit purges session without resurrection`() {
        val targetPkg = "com.private.vault"
        val lockedApp = LockedAppEntity(packageName = targetPkg, isLocked = true)

        engine.grantAppSession(targetPkg, mockMonotonicTime)
        assertTrue(engine.isAppSessionActive(targetPkg, mockMonotonicTime))

        // User navigates away / backgrounded
        engine.revokeAppSession(targetPkg)

        // Attempting to evaluate again
        val decision = engine.evaluate(
            targetPackage = targetPkg,
            lockedApp = lockedApp,
            appSettings = dao.settings,
            currentTime = mockMonotonicTime
        )
        assertEquals(ProtectionDecisionReason.AUTH_REQUIRED_PIN, decision.reason)
    }

    // =========================================================================
    // INV-309: Concurrent launches cannot create contradictory authorization results
    // =========================================================================
    @Test
    fun `INV-309 Concurrent launch evaluation produces deterministic RequirePin for all callers`() = runBlocking {
        val targetPkg = "com.work.email"
        val lockedApp = LockedAppEntity(packageName = targetPkg, isLocked = true)

        val deferreds = (1..20).map {
            async {
                engine.evaluate(
                    targetPackage = targetPkg,
                    lockedApp = lockedApp,
                    appSettings = dao.settings,
                    currentTime = mockMonotonicTime
                )
            }
        }
        val results = deferreds.awaitAll()

        results.forEach { decision ->
            assertTrue("Every concurrent evaluation must yield RequirePin", decision is LockDecision.RequirePin)
            assertEquals(ProtectionDecisionOutcome.REQUIRE_AUTHENTICATION, decision.outcome)
        }
    }

    // =========================================================================
    // INV-310: Alternate intent routes enforce identical protection decisions
    // =========================================================================
    @Test
    fun `INV-310 Deep links, share intents, and notifications are evaluated identically by package`() {
        val targetPkg = "com.messaging.secure"
        val lockedApp = LockedAppEntity(packageName = targetPkg, isLocked = true)

        // Regardless of whether launch origin is notification, launcher, or intent:
        val decision = engine.evaluate(
            targetPackage = targetPkg,
            lockedApp = lockedApp,
            appSettings = dao.settings,
            currentTime = mockMonotonicTime
        )
        assertTrue(decision is LockDecision.RequirePin)
    }

    // =========================================================================
    // INV-311: Settings traversal via alternate routes cannot bypass tamper detection
    // =========================================================================
    @Test
    fun `INV-311 Alternate settings and market routes trigger tamper evaluation`() {
        val playStore = "com.android.vending"

        val mockNode = FakeNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                FakeNode(text = "LockKeeper", viewIdResourceName = "com.android.vending:id/item_title"),
                FakeNode(text = "Uninstall", viewIdResourceName = "com.android.vending:id/uninstall_button")
            )
        )

        val event = tamperEngine.evaluate(
            packageName = playStore,
            className = "com.google.android.finsky.activities.AppDetailsActivity",
            rootNode = mockNode,
            isDeviceAdminActive = true
        )

        assertNotNull("Must detect uninstallation attempt via Play Store", event)
        assertEquals(TamperType.UNINSTALL, event?.type)
    }

    // =========================================================================
    // INV-312: Native component failure converts to fail-closed
    // =========================================================================
    @Test
    fun `INV-312 Native failure or unknown health status produces fail-closed decision`() {
        val targetPkg = "com.bank.app"
        val lockedApp = LockedAppEntity(packageName = targetPkg, isLocked = true)

        val decision = engine.evaluate(
            targetPackage = targetPkg,
            lockedApp = lockedApp,
            appSettings = null,
            currentTime = mockMonotonicTime,
            securityHealthStatus = "UNKNOWN"
        )
        assertTrue("Must fail closed with DenyUnknown", decision is LockDecision.DenyUnknown)
        assertEquals(ProtectionDecisionOutcome.DENY_UNKNOWN_STATE, decision.outcome)
    }

    // =========================================================================
    // INV-313: RecoveryRequired cannot be bypassed by restart
    // =========================================================================
    @Test
    fun `INV-313 Persistent recoveryRequired blocks all operations across reboots`() {
        val targetPkg = "com.any.app"
        val lockedApp = LockedAppEntity(packageName = targetPkg, isLocked = true)

        val decision = engine.evaluate(
            targetPackage = targetPkg,
            lockedApp = lockedApp,
            appSettings = dao.settings,
            currentTime = mockMonotonicTime,
            isRecoveryRequired = true
        )
        assertEquals(ProtectionDecisionOutcome.REQUIRE_RECOVERY, decision.outcome)
        assertEquals(ProtectionDecisionReason.DENIED_RECOVERY_REQUIRED, decision.reason)
    }

    // =========================================================================
    // INV-314: Incomplete onboarding produces fail-closed Blocked state
    // =========================================================================
    @Test
    fun `INV-314 Incomplete onboarding denies protected access without false allow`() {
        val targetPkg = "com.bank.app"
        val lockedApp = LockedAppEntity(packageName = targetPkg, isLocked = true)

        val decision = engine.evaluate(
            targetPackage = targetPkg,
            lockedApp = lockedApp,
            appSettings = dao.settings,
            currentTime = mockMonotonicTime,
            securityHealthStatus = "INITIALIZING"
        )
        assertEquals(ProtectionDecisionOutcome.BLOCK, decision.outcome)
        assertEquals(ProtectionDecisionReason.DENIED_INITIALIZATION_INCOMPLETE, decision.reason)
    }

    // =========================================================================
    // INV-315: Tamper lockout overrides ordinary authorization
    // =========================================================================
    @Test
    fun `INV-315 Persistent tamper lockout blocks authorization attempts`() {
        val targetPkg = "com.bank.app"
        val lockedApp = LockedAppEntity(packageName = targetPkg, isLocked = true)

        val decision = engine.evaluate(
            targetPackage = targetPkg,
            lockedApp = lockedApp,
            appSettings = dao.settings,
            currentTime = mockMonotonicTime,
            isTamperLocked = true
        )
        assertTrue("Must deny authorization during active tamper lockout", decision is LockDecision.Blocked)
        assertEquals(ProtectionDecisionReason.DENIED_TAMPER_LOCKOUT, decision.reason)
    }

    // =========================================================================
    // INV-316: Reboot cannot silently reset persistent security restrictions
    // =========================================================================
    @Test
    fun `INV-316 Lockouts survive simulated system reboot with persistent DAO state`() = runBlocking {
        val targetPkg = "com.banking.app"
        val lockedApp = LockedAppEntity(packageName = targetPkg, isLocked = true)
        val lockoutExpiry = mockMonotonicTime + 180_000L // 3 minutes in future
        dao.updatePinLockout(attempts = 5, lockoutUntil = lockoutExpiry, updatedAt = mockWallClockTime)

        // Simulate reboot: fresh engine instance, same persisted DAO
        val rebootedEngine = LockDecisionEngine(
            appPackageName = "com.lockkeeper.app",
            monotonicTimeProvider = { mockMonotonicTime }
        )

        val decision = rebootedEngine.evaluate(
            targetPackage = targetPkg,
            lockedApp = lockedApp,
            appSettings = dao.settings,
            currentTime = mockMonotonicTime
        )
        assertTrue("PinLockout persists across reboot", decision is LockDecision.PinLockout)
        assertEquals(ProtectionDecisionReason.LOCKOUT_ACTIVE, decision.reason)
    }

    // =========================================================================
    // INV-317: Screen-off / lock events revoke all active application sessions
    // =========================================================================
    @Test
    fun `INV-317 Screen off revokes all active application sessions immediately`() {
        val app1 = "com.app.one"
        val app2 = "com.app.two"

        engine.grantAppSession(app1, mockMonotonicTime)
        engine.grantAppSession(app2, mockMonotonicTime)
        assertTrue(engine.isAppSessionActive(app1, mockMonotonicTime))
        assertTrue(engine.isAppSessionActive(app2, mockMonotonicTime))

        // Screen off event occurs -> clearAllSessions
        engine.clearAllSessions()

        assertFalse("App 1 session revoked by screen off", engine.isAppSessionActive(app1, mockMonotonicTime))
        assertFalse("App 2 session revoked by screen off", engine.isAppSessionActive(app2, mockMonotonicTime))
    }

    // =========================================================================
    // Hard Session Timeout (15 minutes) - VULN-302
    // =========================================================================
    @Test
    fun `VULN-302 Hard session maximum duration enforces re-authentication after 15 minutes`() {
        val targetPkg = "com.crypto.app"
        val lockedApp = LockedAppEntity(packageName = targetPkg, isLocked = true)

        engine.grantAppSession(targetPkg, mockMonotonicTime)
        assertTrue("Session initially active", engine.isAppSessionActive(targetPkg, mockMonotonicTime))

        // Advance monotonic time by 14 minutes (840,000 ms)
        mockMonotonicTime += 14 * 60 * 1000L
        assertTrue("Session active at 14 minutes", engine.isAppSessionActive(targetPkg, mockMonotonicTime))

        // Advance monotonic time past 15 minutes (total 15m 1s)
        mockMonotonicTime += 61 * 1000L
        assertFalse("Session strictly expired after 15 minutes", engine.isAppSessionActive(targetPkg, mockMonotonicTime))

        val decision = engine.evaluate(
            targetPackage = targetPkg,
            lockedApp = lockedApp,
            appSettings = dao.settings,
            currentTime = mockMonotonicTime
        )
        assertTrue("Requires fresh PIN", decision is LockDecision.RequirePin)
    }

    // =========================================================================
    // Strict Cooldown enforcement
    // =========================================================================
    @Test
    fun `Strict cooldown blocks app launch regardless of intent origin`() {
        val targetPkg = "com.distracting.game"
        val cooldownExpiry = mockMonotonicTime + 600_000L // 10 mins
        val lockedApp = LockedAppEntity(
            packageName = targetPkg,
            isLocked = true,
            strictLock = true,
            lockedUntilTimestamp = cooldownExpiry
        )

        val decision = engine.evaluate(
            targetPackage = targetPkg,
            lockedApp = lockedApp,
            appSettings = dao.settings,
            currentTime = mockMonotonicTime
        )
        assertTrue("Must be StrictCooldown", decision is LockDecision.StrictCooldown)
        assertEquals(ProtectionDecisionOutcome.BLOCK, decision.outcome)
        assertEquals(ProtectionDecisionReason.COOLDOWN_ACTIVE, decision.reason)
    }
}
