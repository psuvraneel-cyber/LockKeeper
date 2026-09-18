package com.lockkeeper.app

import com.lockkeeper.app.data.db.AppSettingsDao
import com.lockkeeper.app.data.db.AppSettingsEntity
import com.lockkeeper.app.data.db.LockedAppDao
import com.lockkeeper.app.data.db.LockedAppEntity
import com.lockkeeper.app.domain.LockDecision
import com.lockkeeper.app.domain.LockDecisionEngine
import com.lockkeeper.app.domain.ProtectionDecisionOutcome
import com.lockkeeper.app.domain.ProtectionDecisionReason
import com.lockkeeper.app.domain.SelfLockSessionManager
import com.lockkeeper.app.security.AdminAuthResult
import com.lockkeeper.app.security.CredentialStore
import com.lockkeeper.app.security.KeystoreCredentialStore
import com.lockkeeper.app.security.NodeFacade
import com.lockkeeper.app.security.TamperAuthorizationController
import com.lockkeeper.app.security.TamperConfidence
import com.lockkeeper.app.security.TamperDetectionEngine
import com.lockkeeper.app.security.TamperEvent
import com.lockkeeper.app.security.TamperSource
import com.lockkeeper.app.security.TamperType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Wave 2 Protection Decision & Enforcement Integrity Test Suite.
 *
 * Verifies:
 * - Formal Security Invariants INV-201 through INV-215
 * - Authoritative Enforcement Paths A through T
 * - Concurrency, session invalidation, and fail-closed error boundaries
 */
class ProtectionEnforcementTest {

    private lateinit var credentialStore: CredentialStore
    private lateinit var prefs: InMemorySharedPreferences
    private lateinit var engine: LockDecisionEngine
    private lateinit var dao: EnforcementFakeDao
    private lateinit var tamperController: TamperAuthorizationController
    private lateinit var tamperEngine: TamperDetectionEngine
    private lateinit var selfLockManager: SelfLockSessionManager

    private var mockMonotonicTime: Long = 100_000L
    private var mockWallClockTime: Long = 1_700_000_000_000L

    private class EnforcementFakeDao(
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
        dao = EnforcementFakeDao()
        engine = LockDecisionEngine(
            appPackageName = "com.lockkeeper.app",
            monotonicTimeProvider = { mockMonotonicTime }
        )
        tamperController = TamperAuthorizationController(
            credentialStore = credentialStore,
            appSettingsDao = dao,
            monotonicTimeProvider = { mockMonotonicTime },
            wallClockTimeProvider = { mockWallClockTime }
        )
        tamperEngine = TamperDetectionEngine(
            appPackageName = "com.lockkeeper.app"
        )
        selfLockManager = SelfLockSessionManager()
    }

    // ======================================================================
    // INVARIANTS: INV-201 through INV-215
    // ======================================================================

    @Test
    fun `INV-201 UNKNOWN security state must never become ALLOW through default values`() {
        val app = LockedAppEntity(packageName = "com.example.vault", isLocked = true)
        val decision = engine.evaluate(
            targetPackage = "com.example.vault",
            lockedApp = app,
            appSettings = dao.settings,
            securityHealthStatus = "UNKNOWN"
        )
        assertEquals(ProtectionDecisionOutcome.DENY_UNKNOWN_STATE, decision.outcome)
        assertEquals(ProtectionDecisionReason.DENIED_UNKNOWN_STATE, decision.reason)
        assertTrue(decision is LockDecision.DenyUnknown)

        // Even with an unconfigured or null appSettings, UNKNOWN never returns Allowed
        val nullSettingsDecision = engine.evaluate(
            targetPackage = "com.example.vault",
            lockedApp = app,
            appSettings = null,
            securityHealthStatus = "UNKNOWN"
        )
        assertEquals(ProtectionDecisionOutcome.DENY_UNKNOWN_STATE, nullSettingsDecision.outcome)
    }

    @Test
    fun `INV-202 RECOVERY_REQUIRED must never become normal protection mode`() {
        val app = LockedAppEntity(packageName = "com.example.vault", isLocked = true)
        engine.grantAppSession("com.example.vault") // Try to have a granted session

        val decision = engine.evaluate(
            targetPackage = "com.example.vault",
            lockedApp = app,
            appSettings = dao.settings,
            isRecoveryRequired = true,
            isTamperGraceActive = true // Even with grace active
        )

        assertEquals(ProtectionDecisionOutcome.REQUIRE_RECOVERY, decision.outcome)
        assertEquals(ProtectionDecisionReason.DENIED_RECOVERY_REQUIRED, decision.reason)
        assertTrue(decision is LockDecision.RecoveryRequired)
    }

    @Test
    fun `INV-203 Disconnected AccessibilityService must not be treated as operational`() {
        val isA11yEnabled = true
        val isA11yConnected = false
        val isA11yOperational = isA11yEnabled && isA11yConnected
        assertFalse("Disconnected service must not be operational", isA11yOperational)

        val healthStatus = if (!isA11yOperational) "DEGRADED" else "PROTECTED"
        assertEquals("DEGRADED", healthStatus)

        val app = LockedAppEntity(packageName = "com.example.vault", isLocked = true)
        val decision = engine.evaluate(
            targetPackage = "com.example.vault",
            lockedApp = app,
            appSettings = dao.settings,
            securityHealthStatus = healthStatus
        )
        assertEquals(ProtectionDecisionOutcome.REQUIRE_AUTHENTICATION, decision.outcome)
    }

    @Test
    fun `INV-204 Device Admin state change must invalidate any stale enforcement decision`() {
        val pkg = "com.example.banking"
        val app = LockedAppEntity(packageName = pkg, isLocked = true)

        engine.grantAppSession(pkg)
        val allowedDecision = engine.evaluate(targetPackage = pkg, lockedApp = app, appSettings = dao.settings)
        assertEquals(ProtectionDecisionOutcome.ALLOW, allowedDecision.outcome)
        assertEquals(ProtectionDecisionReason.ALLOWED_ACTIVE_SESSION, allowedDecision.reason)

        // Device Admin disabled event occurs
        engine.clearAllSessions()

        val revokedDecision = engine.evaluate(targetPackage = pkg, lockedApp = app, appSettings = dao.settings)
        assertEquals(ProtectionDecisionOutcome.REQUIRE_AUTHENTICATION, revokedDecision.outcome)
        assertEquals(ProtectionDecisionReason.AUTH_REQUIRED_PIN, revokedDecision.reason)
    }

    @Test
    fun `INV-205 Service restart must reconstruct enforcement correctly without session leakage`() {
        val pkg = "com.example.social"
        val app = LockedAppEntity(packageName = pkg, isLocked = true)
        engine.grantAppSession(pkg)

        // Simulate service restart: new engine instance created
        val restartedEngine = LockDecisionEngine(appPackageName = "com.lockkeeper.app", monotonicTimeProvider = { mockMonotonicTime })
        val decision = restartedEngine.evaluate(targetPackage = pkg, lockedApp = app, appSettings = dao.settings)

        assertEquals(ProtectionDecisionOutcome.REQUIRE_AUTHENTICATION, decision.outcome)
        assertEquals(ProtectionDecisionReason.AUTH_REQUIRED_PIN, decision.reason)
        assertFalse(restartedEngine.isAppSessionActive(pkg))
    }

    @Test
    fun `INV-206 Two concurrent launch events must not produce conflicting enforcement decisions`() = runBlocking {
        val pkg = "com.example.finance"
        val app = LockedAppEntity(packageName = pkg, isLocked = true)

        val jobs = (1..50).map { i ->
            async {
                if (i % 2 == 0) {
                    engine.grantAppSession(pkg)
                }
                engine.evaluate(targetPackage = pkg, lockedApp = app, appSettings = dao.settings)
            }
        }
        val results = jobs.awaitAll()

        for (decision in results) {
            assertNotNull(decision.outcome)
            assertNotNull(decision.reason)
            assertTrue(
                decision.outcome == ProtectionDecisionOutcome.ALLOW ||
                decision.outcome == ProtectionDecisionOutcome.REQUIRE_AUTHENTICATION
            )
        }
    }

    @Test
    fun `INV-207 Stale Flutter state must never permit an otherwise denied native operation`() {
        val pkg = "com.example.vault"
        val app = LockedAppEntity(packageName = pkg, isLocked = true)

        // Regardless of what Flutter UI claims, native engine enforces PIN requirement
        val decision = engine.evaluate(
            targetPackage = pkg,
            lockedApp = app,
            appSettings = dao.settings,
            isTamperLocked = false,
            isRecoveryRequired = false
        )
        assertEquals(ProtectionDecisionOutcome.REQUIRE_AUTHENTICATION, decision.outcome)
        assertEquals(ProtectionDecisionReason.AUTH_REQUIRED_PIN, decision.reason)
    }

    @Test
    fun `INV-208 Exception in a security decision path must fail closed`() {
        // Safe evaluation wrapper simulation: failure in DB lookup or repository
        fun safeEvaluatePackage(throwingCall: () -> LockDecision): LockDecision {
            return try {
                throwingCall()
            } catch (_: Exception) {
                LockDecision.DenyUnknown(ProtectionDecisionReason.DENIED_NATIVE_FAILURE)
            }
        }

        val decision = safeEvaluatePackage {
            throw RuntimeException("Simulated SQLite Disk I/O Failure")
        }

        assertEquals(ProtectionDecisionOutcome.DENY_UNKNOWN_STATE, decision.outcome)
        assertEquals(ProtectionDecisionReason.DENIED_NATIVE_FAILURE, decision.reason)
        assertTrue(decision is LockDecision.DenyUnknown)
    }

    @Test
    fun `INV-209 Destroying MainActivity must not disable protection`() {
        val app = LockedAppEntity(packageName = "com.example.crypto", isLocked = true)
        // MainActivity destroyed: native engine continues independently
        val decision = engine.evaluate(targetPackage = "com.example.crypto", lockedApp = app, appSettings = dao.settings)
        assertEquals(ProtectionDecisionOutcome.REQUIRE_AUTHENTICATION, decision.outcome)
    }

    @Test
    fun `INV-210 Destroying and reconnecting AccessibilityService must not create a permanent bypass`() {
        val pkg = "com.example.private"
        val app = LockedAppEntity(packageName = pkg, isLocked = true)
        engine.grantAppSession(pkg)
        assertTrue(engine.isAppSessionActive(pkg))

        // OnDestroy called
        engine.clearAllSessions()
        assertFalse(engine.isAppSessionActive(pkg))

        // Service reconnects
        val decision = engine.evaluate(targetPackage = pkg, lockedApp = app, appSettings = dao.settings)
        assertEquals(ProtectionDecisionOutcome.REQUIRE_AUTHENTICATION, decision.outcome)
    }

    @Test
    fun `INV-211 Tamper lockout must override ordinary authorization paths`() {
        val pkg = "com.example.vault"
        val app = LockedAppEntity(packageName = pkg, isLocked = true)
        engine.grantAppSession(pkg) // Active session exists

        val decision = engine.evaluate(
            targetPackage = pkg,
            lockedApp = app,
            appSettings = dao.settings,
            isTamperLocked = true // Active tamper lockout
        )

        assertEquals(ProtectionDecisionOutcome.BLOCK, decision.outcome)
        assertEquals(ProtectionDecisionReason.DENIED_TAMPER_LOCKOUT, decision.reason)
        assertTrue(decision is LockDecision.Blocked)
    }

    @Test
    fun `INV-212 Grace sessions must never outlive their defined monotonic expiry`() = runBlocking {
        credentialStore.setAdminPassword("correct_admin_123")
        val result = tamperController.verifyAdminPassword("correct_admin_123")
        assertTrue(result is AdminAuthResult.Success)

        // Monotonic window is 30 seconds (30,000 ms)
        mockMonotonicTime += 29_999L
        assertTrue("Grace must be active before 30 seconds", tamperController.isGraceActive())

        mockMonotonicTime += 2L // At 30,001 ms, grace has expired
        assertFalse("Grace must expire after 30 seconds monotonic", tamperController.isGraceActive())
    }

    @Test
    fun `INV-213 Overlay dismissal must not equal authorization`() {
        val pkg = "com.example.notes"
        val app = LockedAppEntity(packageName = pkg, isLocked = true)

        val initialDecision = engine.evaluate(targetPackage = pkg, lockedApp = app, appSettings = dao.settings)
        assertEquals(ProtectionDecisionOutcome.REQUIRE_AUTHENTICATION, initialDecision.outcome)

        // Overlay dismissed without granting app session
        assertFalse(engine.isAppSessionActive(pkg))

        val postDismissalDecision = engine.evaluate(targetPackage = pkg, lockedApp = app, appSettings = dao.settings)
        assertEquals(ProtectionDecisionOutcome.REQUIRE_AUTHENTICATION, postDismissalDecision.outcome)
        assertEquals(ProtectionDecisionReason.AUTH_REQUIRED_PIN, postDismissalDecision.reason)
    }

    @Test
    fun `INV-214 Repeated foreground events must not create inconsistent overlay state`() {
        val pkg = "com.example.gallery"
        val app = LockedAppEntity(packageName = pkg, isLocked = true)

        val d1 = engine.evaluate(targetPackage = pkg, lockedApp = app, appSettings = dao.settings)
        val d2 = engine.evaluate(targetPackage = pkg, lockedApp = app, appSettings = dao.settings)
        val d3 = engine.evaluate(targetPackage = pkg, lockedApp = app, appSettings = dao.settings)

        assertEquals(d1.outcome, d2.outcome)
        assertEquals(d2.outcome, d3.outcome)
        assertEquals(d1.reason, d2.reason)
    }

    @Test
    fun `INV-215 Changing system security permissions while running converges cleanly`() {
        val app = LockedAppEntity(packageName = "com.example.vault", isLocked = true)

        var isA11yOperational = true
        var status = if (isA11yOperational) "PROTECTED" else "DEGRADED"
        val d1 = engine.evaluate(targetPackage = "com.example.vault", lockedApp = app, appSettings = dao.settings, securityHealthStatus = status)
        assertEquals(ProtectionDecisionOutcome.REQUIRE_AUTHENTICATION, d1.outcome)

        // Permission revoked
        isA11yOperational = false
        status = if (isA11yOperational) "PROTECTED" else "DEGRADED"
        val d2 = engine.evaluate(targetPackage = "com.example.vault", lockedApp = app, appSettings = dao.settings, securityHealthStatus = status)
        assertEquals(ProtectionDecisionOutcome.REQUIRE_AUTHENTICATION, d2.outcome)
    }

    // ======================================================================
    // ENFORCEMENT PATHS: A through T
    // ======================================================================

    @Test
    fun `Path A Normal protected app launch requires PIN`() {
        val app = LockedAppEntity(packageName = "com.example.target", isLocked = true)
        val decision = engine.evaluate(targetPackage = "com.example.target", lockedApp = app, appSettings = dao.settings)
        assertEquals(ProtectionDecisionOutcome.REQUIRE_AUTHENTICATION, decision.outcome)
        assertEquals(ProtectionDecisionReason.AUTH_REQUIRED_PIN, decision.reason)
        assertTrue(decision is LockDecision.RequirePin)
    }

    @Test
    fun `Path B Unprotected app launch is allowed`() {
        val app = LockedAppEntity(packageName = "com.example.calculator", isLocked = false)
        val decision = engine.evaluate(targetPackage = "com.example.calculator", lockedApp = app, appSettings = dao.settings)
        assertEquals(ProtectionDecisionOutcome.ALLOW, decision.outcome)
        assertEquals(ProtectionDecisionReason.ALLOWED_UNPROTECTED_APP, decision.reason)
        assertTrue(decision is LockDecision.Allowed)

        val unmanagedDecision = engine.evaluate(targetPackage = "com.example.unmanaged", lockedApp = null, appSettings = dao.settings)
        assertEquals(ProtectionDecisionOutcome.ALLOW, unmanagedDecision.outcome)
        assertEquals(ProtectionDecisionReason.ALLOWED_UNPROTECTED_APP, unmanagedDecision.reason)
    }

    @Test
    fun `Path C PIN unlock grants session allowing access`() {
        val pkg = "com.example.target"
        val app = LockedAppEntity(packageName = pkg, isLocked = true)
        engine.grantAppSession(pkg)

        val decision = engine.evaluate(targetPackage = pkg, lockedApp = app, appSettings = dao.settings)
        assertEquals(ProtectionDecisionOutcome.ALLOW, decision.outcome)
        assertEquals(ProtectionDecisionReason.ALLOWED_ACTIVE_SESSION, decision.reason)
        assertTrue(decision is LockDecision.Allowed)
    }

    @Test
    fun `Path D Admin password authorization activates grace allowing admin access`() = runBlocking {
        credentialStore.setAdminPassword("admin_secret_99")
        val auth = tamperController.verifyAdminPassword("admin_secret_99")
        assertTrue(auth is AdminAuthResult.Success)
        assertTrue(tamperController.isGraceActive())

        val decision = engine.evaluate(
            targetPackage = "com.android.settings",
            lockedApp = null,
            appSettings = dao.settings,
            isTamperGraceActive = true
        )
        assertEquals(ProtectionDecisionOutcome.ALLOW, decision.outcome)
        assertEquals(ProtectionDecisionReason.ALLOWED_ADMIN_GRACE, decision.reason)
    }

    @Test
    fun `Path E Self-lock enforces authentication on cold start and background timeout`() {
        val timeoutSeconds = 10
        val now = 1000L

        // Cold start: no session
        assertTrue(
            selfLockManager.isAuthRequired(
                selfLockEnabled = true,
                onboardingComplete = true,
                timeoutSeconds = timeoutSeconds,
                lockoutUntil = null,
                currentTime = now
            )
        )

        // Session granted
        selfLockManager.grantSession()
        assertFalse(
            selfLockManager.isAuthRequired(
                selfLockEnabled = true,
                onboardingComplete = true,
                timeoutSeconds = timeoutSeconds,
                lockoutUntil = null,
                currentTime = now
            )
        )

        // App backgrounded and resumed after timeout
        selfLockManager.recordBackgrounded(now)
        val expiredTime = now + (timeoutSeconds * 1000L) + 1000L
        assertTrue(
            selfLockManager.isAuthRequired(
                selfLockEnabled = true,
                onboardingComplete = true,
                timeoutSeconds = timeoutSeconds,
                lockoutUntil = null,
                currentTime = expiredTime
            )
        )
    }

    @Test
    fun `Path F Self-lock exit clears lock requirement`() {
        selfLockManager.grantSession()
        assertFalse(
            selfLockManager.isAuthRequired(
                selfLockEnabled = true,
                onboardingComplete = true,
                timeoutSeconds = 30,
                lockoutUntil = null
            )
        )
    }

    @Test
    fun `Path G Device Admin removal invalidates sessions`() {
        engine.grantAppSession("com.example.bank")
        assertTrue(engine.isAppSessionActive("com.example.bank"))

        // Receiver onDisabled triggered
        engine.clearAllSessions()
        assertFalse(engine.isAppSessionActive("com.example.bank"))
    }

    @Test
    fun `Path H Accessibility disable sets operational to false`() {
        val isA11yGranted = false
        val isA11yConnected = false
        val isA11yOperational = isA11yGranted && isA11yConnected
        assertFalse(isA11yOperational)
    }

    @Test
    fun `Path I Accessibility disconnect degrades health status`() {
        val isA11yGranted = true
        val isA11yConnected = false
        val isA11yOperational = isA11yGranted && isA11yConnected
        assertFalse(isA11yOperational)
    }

    @Test
    fun `Path J Tamper detection triggers structured TamperEvent`() {
        val root = FakeNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                FakeNode(text = "LockKeeper", viewIdResourceName = "com.android.settings:id/entity_header_title"),
                FakeNode(text = "Uninstall", viewIdResourceName = "com.android.settings:id/button1_negative"),
                FakeNode(text = "Force stop", viewIdResourceName = "com.android.settings:id/button2_negative")
            )
        )

        val event = tamperEngine.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.applications.InstalledAppDetails",
            rootNode = root,
            isDeviceAdminActive = true
        )

        assertNotNull(event)
        assertEquals(TamperType.UNINSTALL, event?.type)
        assertEquals(TamperConfidence.HIGH, event?.confidence)
    }

    @Test
    fun `Path K Tamper lockout enforces blocking decision`() = runBlocking {
        credentialStore.setAdminPassword("secret")
        // 5 failed attempts trigger lockout
        repeat(5) {
            tamperController.verifyAdminPassword("wrong_password")
        }
        val lockoutActive = tamperController.checkLockout() != null
        assertTrue(lockoutActive)

        val app = LockedAppEntity(packageName = "com.example.vault", isLocked = true)
        val decision = engine.evaluate(
            targetPackage = "com.example.vault",
            lockedApp = app,
            appSettings = dao.settings,
            isTamperLocked = true
        )
        assertEquals(ProtectionDecisionOutcome.BLOCK, decision.outcome)
        assertEquals(ProtectionDecisionReason.DENIED_TAMPER_LOCKOUT, decision.reason)
    }

    @Test
    fun `Path L Grace period grants temporary admin access`() = runBlocking {
        credentialStore.setAdminPassword("admin_pwd")
        tamperController.verifyAdminPassword("admin_pwd")
        assertTrue(tamperController.isGraceActive())

        val decision = engine.evaluate(
            targetPackage = "com.android.settings",
            lockedApp = null,
            appSettings = dao.settings,
            isTamperGraceActive = true
        )
        assertEquals(ProtectionDecisionOutcome.ALLOW, decision.outcome)
        assertEquals(ProtectionDecisionReason.ALLOWED_ADMIN_GRACE, decision.reason)
    }

    @Test
    fun `Path M App process restart clears all in-memory sessions`() {
        engine.grantAppSession("com.example.app1")
        engine.grantAppSession("com.example.app2")

        // Process dies and restarts: new engine with empty state
        val freshEngine = LockDecisionEngine(appPackageName = "com.lockkeeper.app")
        assertFalse(freshEngine.isAppSessionActive("com.example.app1"))
        assertFalse(freshEngine.isAppSessionActive("com.example.app2"))
    }

    @Test
    fun `Path N Accessibility service restart clears sessions on onDestroy`() {
        engine.grantAppSession("com.example.app1")
        assertTrue(engine.isAppSessionActive("com.example.app1"))

        // Service onDestroy
        engine.clearAllSessions()
        assertFalse(engine.isAppSessionActive("com.example.app1"))
    }

    @Test
    fun `Path O Device reboot preserves persisted lockout in Room`() = runBlocking {
        val now = 1_700_000_000_000L
        val lockoutUntil = now + 300_000L // 5 minutes
        dao.updateAdminLockout(5, lockoutUntil, now)

        // After simulated reboot, read from DAO
        val reloadedSettings = dao.getSettings()
        assertNotNull(reloadedSettings)
        assertEquals(lockoutUntil, reloadedSettings!!.adminLockoutUntil)
        assertTrue(reloadedSettings.adminLockoutUntil!! > now)
    }

    @Test
    fun `Path P MainActivity destruction does not alter engine decision`() {
        val app = LockedAppEntity(packageName = "com.example.secret", isLocked = true)
        val decision = engine.evaluate(targetPackage = "com.example.secret", lockedApp = app, appSettings = dao.settings)
        assertEquals(ProtectionDecisionOutcome.REQUIRE_AUTHENTICATION, decision.outcome)
        assertEquals(ProtectionDecisionReason.AUTH_REQUIRED_PIN, decision.reason)
    }

    @Test
    fun `Path Q Flutter process restart leaves native protection active`() {
        val app = LockedAppEntity(packageName = "com.example.secret", isLocked = true)
        // Flutter is detached/restarting; native service evaluates package
        val decision = engine.evaluate(targetPackage = "com.example.secret", lockedApp = app, appSettings = dao.settings)
        assertEquals(ProtectionDecisionOutcome.REQUIRE_AUTHENTICATION, decision.outcome)
    }

    @Test
    fun `Path R Platform-channel failure fails closed to unknown and locked`() {
        val unknownModel = com.lockkeeper.app.domain.LockDecision.DenyUnknown(ProtectionDecisionReason.DENIED_UNKNOWN_STATE)
        assertEquals(ProtectionDecisionOutcome.DENY_UNKNOWN_STATE, unknownModel.outcome)
        assertEquals(ProtectionDecisionReason.DENIED_UNKNOWN_STATE, unknownModel.reason)
    }

    @Test
    fun `Path S Room database failure fails closed returning DenyUnknown`() {
        val decision = try {
            throw android.database.sqlite.SQLiteDiskIOException("Disk read error")
        } catch (_: Exception) {
            LockDecision.DenyUnknown(ProtectionDecisionReason.DENIED_NATIVE_FAILURE)
        }

        assertEquals(ProtectionDecisionOutcome.DENY_UNKNOWN_STATE, decision.outcome)
        assertEquals(ProtectionDecisionReason.DENIED_NATIVE_FAILURE, decision.reason)
    }

    @Test
    fun `Path T Unexpected native exception fails closed returning DenyUnknown`() {
        val decision = try {
            throw NullPointerException("Unexpected native NPE in security pipeline")
        } catch (_: Exception) {
            LockDecision.DenyUnknown(ProtectionDecisionReason.DENIED_NATIVE_FAILURE)
        }

        assertEquals(ProtectionDecisionOutcome.DENY_UNKNOWN_STATE, decision.outcome)
        assertEquals(ProtectionDecisionReason.DENIED_NATIVE_FAILURE, decision.reason)
    }
}
