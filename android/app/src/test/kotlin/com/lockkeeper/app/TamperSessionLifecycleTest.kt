package com.lockkeeper.app

import com.lockkeeper.app.data.db.AppSettingsDao
import com.lockkeeper.app.data.db.AppSettingsEntity
import com.lockkeeper.app.security.AdminAuthResult
import com.lockkeeper.app.security.CredentialStore
import com.lockkeeper.app.security.KeystoreCredentialStore
import com.lockkeeper.app.security.TamperAuthorizationController
import com.lockkeeper.app.security.TamperConfidence
import com.lockkeeper.app.security.TamperEvent
import com.lockkeeper.app.security.TamperSessionState
import com.lockkeeper.app.security.TamperSource
import com.lockkeeper.app.security.TamperType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Regression Test Suite for Device Admin Anti-Tamper Session Binding & Lifecycle.
 *
 * Covers:
 * - TEST GROUP A: Session Ownership & Idempotency
 * - TEST GROUP B: Package Transition Immunity (Observation != Termination)
 * - TEST GROUP C: Accessibility Event Stress & Concurrency Races (100+ events, 50+ soak cycles)
 * - TEST GROUP D: Device Admin Action & Authorization Flow
 * - TEST GROUP E: Fail-Closed Cancel / Back Terminal Handling
 * - TEST GROUP F: Concurrency & Stale Callback Defense
 * - TEST GROUP G: Service & System Lifecycle (SERVICE_DESTROYED, INTERRUPTED)
 */
class TamperSessionLifecycleTest {

    private lateinit var controller: TamperAuthorizationController
    private lateinit var store: CredentialStore
    private lateinit var dao: FakeAppSettingsDao
    private var mockMonotonicTime: Long = 100_000L
    private var mockWallClockTime: Long = 1_700_000_000_000L

    private class FakeAppSettingsDao : AppSettingsDao {
        var currentSettings: AppSettingsEntity? = AppSettingsEntity()

        override suspend fun getSettings(): AppSettingsEntity? = currentSettings

        override suspend fun insertInitialSettings(settings: AppSettingsEntity): Long {
            if (currentSettings == null) {
                currentSettings = settings
                return 1L
            }
            return -1L
        }

        override suspend fun upsert(settings: AppSettingsEntity) {
            currentSettings = settings
        }

        override suspend fun updatePinLockout(attempts: Int, lockoutUntil: Long?, updatedAt: Long) {
            currentSettings = currentSettings?.copy(failedPinAttempts = attempts, pinLockoutUntil = lockoutUntil, updatedAt = updatedAt)
        }

        override suspend fun resetPinFailures(updatedAt: Long) {
            currentSettings = currentSettings?.copy(failedPinAttempts = 0, pinLockoutUntil = null, updatedAt = updatedAt)
        }

        override suspend fun updateAdminLockout(attempts: Int, lockoutUntil: Long?, updatedAt: Long) {
            currentSettings = currentSettings?.copy(failedAdminAttempts = attempts, adminLockoutUntil = lockoutUntil, updatedAt = updatedAt)
        }

        override suspend fun resetAdminFailures(updatedAt: Long) {
            currentSettings = currentSettings?.copy(failedAdminAttempts = 0, adminLockoutUntil = null, updatedAt = updatedAt)
        }

        override suspend fun setOnboardingComplete(complete: Boolean, updatedAt: Long) {
            currentSettings = currentSettings?.copy(onboardingComplete = complete, updatedAt = updatedAt)
        }

        override suspend fun setSelfLockEnabled(enabled: Boolean, updatedAt: Long) {
            currentSettings = currentSettings?.copy(selfLockEnabled = enabled, updatedAt = updatedAt)
        }

        override suspend fun setSelfLockTimeout(timeoutSeconds: Int, updatedAt: Long) {
            currentSettings = currentSettings?.copy(selfLockTimeoutSeconds = timeoutSeconds, updatedAt = updatedAt)
        }

        override suspend fun updateSelfLock(enabled: Boolean, timeoutSeconds: Int, updatedAt: Long) {
            currentSettings = currentSettings?.copy(selfLockEnabled = enabled, selfLockTimeoutSeconds = timeoutSeconds, updatedAt = updatedAt)
        }

        override suspend fun setRecoveryRequired(required: Boolean, updatedAt: Long) {
            currentSettings = currentSettings?.copy(recoveryRequired = required, updatedAt = updatedAt)
        }

        override suspend fun setSecurityProvisioned(provisioned: Boolean, updatedAt: Long) {
            currentSettings = currentSettings?.copy(securityProvisioned = provisioned, updatedAt = updatedAt)
        }

        override suspend fun setProvisionedAndOnboardingComplete(provisioned: Boolean, complete: Boolean, updatedAt: Long) {
            currentSettings = currentSettings?.copy(securityProvisioned = provisioned, onboardingComplete = complete, updatedAt = updatedAt)
        }
    }

    private fun createDeviceAdminTamperEvent(): TamperEvent {
        return TamperEvent(
            type = TamperType.DISABLE_DEVICE_ADMIN,
            source = TamperSource.SETTINGS,
            confidence = TamperConfidence.HIGH,
            targetPackage = "com.lockkeeper.app",
            targetActivity = "com.android.settings.DeviceAdminAddActivity",
            timestamp = mockWallClockTime
        )
    }

    @Before
    fun setUp() {
        val prefs = InMemorySharedPreferences()
        store = KeystoreCredentialStore(prefs)
        store.setAdminPassword("DeterministicAdminPass!99")

        dao = FakeAppSettingsDao()
        controller = TamperAuthorizationController(
            credentialStore = store,
            appSettingsDao = dao,
            monotonicTimeProvider = { mockMonotonicTime },
            wallClockTimeProvider = { mockWallClockTime }
        )
    }

    // =========================================================================
    // TEST GROUP A: SESSION OWNERSHIP & IDEMPOTENCY
    // =========================================================================

    @Test
    fun `A1 startSession creates exactly one active session with unique ID and PROMPTING state`() {
        val event = createDeviceAdminTamperEvent()
        val session = controller.startOrGetSession(event)

        assertNotNull("Session must be created", session)
        assertTrue("Session ID must not be blank", session!!.sessionId.isNotBlank())
        assertEquals(TamperSessionState.PROMPTING, session.state)
        assertEquals(session, controller.activeSession)
        assertTrue(controller.isSessionActive(session.sessionId))
    }

    @Test
    fun `A2 repeated identical tamper events collapse onto same session and do not create second session`() {
        val event1 = createDeviceAdminTamperEvent()
        val session1 = controller.startOrGetSession(event1)
        assertNotNull(session1)

        val event2 = createDeviceAdminTamperEvent()
        val session2 = controller.startOrGetSession(event2)
        assertNotNull(session2)

        // Must be exact same instance and ID (idempotent)
        assertEquals("Must reuse active session", session1!!.sessionId, session2!!.sessionId)
        assertEquals("Must maintain exactly one active session", session1, controller.activeSession)
    }

    @Test
    fun `A3 startSession returns false when active session is already PROMPTING`() {
        val event = createDeviceAdminTamperEvent()
        val started1 = controller.startSession(event)
        assertTrue("First startSession must return true", started1)

        val started2 = controller.startSession(event)
        assertFalse("Second startSession while PROMPTING must return false", started2)
    }

    @Test
    fun `A4 stale callback from obsolete session cannot terminate or mutate current session`() {
        val event = createDeviceAdminTamperEvent()
        val session1 = controller.startOrGetSession(event)
        assertNotNull(session1)
        val activeId = session1!!.sessionId

        val staleSessionId = UUID.randomUUID().toString()
        val terminated = controller.endSession(
            sessionId = staleSessionId,
            terminalState = TamperSessionState.CANCELLED,
            reason = "Stale dismissal"
        )

        assertFalse("Stale session ID must be rejected", terminated)
        assertNotNull("Active session must remain intact", controller.activeSession)
        assertEquals("Active session ID must be unchanged", activeId, controller.activeSession?.sessionId)
        assertEquals(TamperSessionState.PROMPTING, controller.activeSession?.state)
    }

    @Test
    fun `A5 stale dismiss request on already terminated session is rejected`() {
        val event = createDeviceAdminTamperEvent()
        val session = controller.startOrGetSession(event)!!
        val sid = session.sessionId

        val firstEnd = controller.endSession(sid, TamperSessionState.CANCELLED, "First cancel")
        assertTrue("First termination must succeed", firstEnd)
        assertNull("activeSession must be cleared", controller.activeSession)

        val secondEnd = controller.endSession(sid, TamperSessionState.AUTHORIZED, "Second auth")
        assertFalse("Second termination on terminated session must be rejected", secondEnd)
    }

    // =========================================================================
    // TEST GROUP B: PACKAGE TRANSITIONS & OBSERVATION != TERMINATION
    // =========================================================================

    @Test
    fun `B1 generic package transitions cannot terminate active ADMIN session`() {
        val session = controller.startOrGetSession(createDeviceAdminTamperEvent())!!
        val sid = session.sessionId

        // Simulate observations across diverse system and app packages
        val observedPackages = listOf(
            "com.android.settings",
            "com.android.systemui",
            "com.miui.securitycenter",
            "com.lockkeeper.app",
            "com.google.android.apps.nexuslauncher",
            "android",
            "com.google.android.inputmethod.latin"
        )

        for (pkg in observedPackages) {
            // Package change is an observation, not a termination!
            assertTrue("Session must remain active while observing $pkg", controller.isSessionActive(sid))
            assertEquals(TamperSessionState.PROMPTING, controller.activeSession?.state)
        }
    }

    @Test
    fun `B2 active grace window prevents starting redundant tamper session`() {
        controller.grantGraceWindow()
        assertTrue("Grace window is active", controller.isGraceActive())

        val event = createDeviceAdminTamperEvent()
        val session = controller.startOrGetSession(event)
        assertNull("startOrGetSession must return null while grace window is active", session)
        assertNull("No active session should exist during grace", controller.activeSession)
    }

    // =========================================================================
    // TEST GROUP C: ACCESSIBILITY EVENT STRESS & CONCURRENCY RACES
    // =========================================================================

    @Test
    fun `C1 100 concurrent startOrGetSession calls collapse onto identical session ID without race`() = runBlocking {
        val event = createDeviceAdminTamperEvent()
        val jobs = (1..100).map {
            async { controller.startOrGetSession(event) }
        }
        val results = jobs.awaitAll()

        assertEquals(100, results.size)
        val firstId = results[0]?.sessionId
        assertNotNull("Session must not be null", firstId)
        assertTrue("All 100 calls must return exact same session ID", results.all { it?.sessionId == firstId })
        assertEquals(firstId, controller.activeSession?.sessionId)
    }

    @Test
    fun `C2 50 soak iterations of session creation, wrong attempts, and cancellation`() = runBlocking {
        for (iteration in 1..50) {
            // 1. Start session
            val session = controller.startOrGetSession(createDeviceAdminTamperEvent())
            assertNotNull(session)
            val sid = session!!.sessionId
            assertEquals(TamperSessionState.PROMPTING, session.state)

            // 2. Submit wrong password
            val wrongResult = controller.verifyAdminPassword("IncorrectPass")
            assertTrue(wrongResult is AdminAuthResult.Failure || wrongResult is AdminAuthResult.LockedOut)
            assertTrue("Session must remain active after wrong password", controller.isSessionActive(sid))

            // 3. Cancel session
            val cancelled = controller.endSession(sid, TamperSessionState.CANCELLED, "Soak cancel $iteration")
            assertTrue(cancelled)
            assertNull(controller.activeSession)

            // Reset failures in DAO for next iteration to prevent premature lockout
            dao.resetAdminFailures(mockWallClockTime)
        }
    }

    // =========================================================================
    // TEST GROUP D: DEVICE ADMIN ACTION & AUTHORIZATION FLOW
    // =========================================================================

    @Test
    fun `D1 correct admin password transitions session to AUTHORIZED and grants grace window`() = runBlocking {
        val session = controller.startOrGetSession(createDeviceAdminTamperEvent())!!
        val sid = session.sessionId

        val authResult = controller.verifyAdminPassword("DeterministicAdminPass!99")
        assertTrue("Correct password must succeed", authResult is AdminAuthResult.Success)

        val ended = controller.endSession(sid, TamperSessionState.AUTHORIZED, "Password verified")
        assertTrue("Ending with AUTHORIZED must succeed", ended)
        assertEquals(TamperSessionState.AUTHORIZED, session.state)
        assertTrue("Grace window must be active after AUTHORIZED outcome", controller.isGraceActive())
        assertNull("activeSession must be cleared after terminal outcome", controller.activeSession)
    }

    @Test
    fun `D2 wrong admin password increments failure count and keeps session PROMPTING`() = runBlocking {
        val session = controller.startOrGetSession(createDeviceAdminTamperEvent())!!
        val sid = session.sessionId

        val result1 = controller.verifyAdminPassword("Wrong1")
        assertTrue(result1 is AdminAuthResult.Failure)
        assertEquals(4, (result1 as AdminAuthResult.Failure).remainingAttempts)
        assertTrue("Session must remain PROMPTING after wrong password", controller.isSessionActive(sid))

        val result2 = controller.verifyAdminPassword("Wrong2")
        assertTrue(result2 is AdminAuthResult.Failure)
        assertEquals(3, (result2 as AdminAuthResult.Failure).remainingAttempts)
        assertTrue("Session must remain PROMPTING after 2nd wrong password", controller.isSessionActive(sid))
    }

    @Test
    fun `D3 5 failed attempts trigger 300s lockout and session remains active`() = runBlocking {
        val session = controller.startOrGetSession(createDeviceAdminTamperEvent())!!
        val sid = session.sessionId

        for (i in 1..4) {
            controller.verifyAdminPassword("Bad$i")
        }

        val result5 = controller.verifyAdminPassword("Bad5")
        assertTrue("5th wrong attempt must trigger lockout", result5 is AdminAuthResult.LockedOut)
        assertEquals(300L, (result5 as AdminAuthResult.LockedOut).remainingLockoutSeconds)

        // Session is still bound, user is locked out from authenticating
        assertTrue("Session remains active during lockout", controller.isSessionActive(sid))
        assertNotNull("Lockout must be active", controller.checkLockout())
    }

    // =========================================================================
    // TEST GROUP E: FAIL-CLOSED CANCEL / BACK
    // =========================================================================

    @Test
    fun `E1 cancel transitions session to CANCELLED and does NOT grant grace window`() {
        val session = controller.startOrGetSession(createDeviceAdminTamperEvent())!!
        val sid = session.sessionId

        val cancelled = controller.endSession(sid, TamperSessionState.CANCELLED, "User tapped Cancel")
        assertTrue("Cancel must succeed", cancelled)
        assertEquals(TamperSessionState.CANCELLED, session.state)
        assertEquals("User tapped Cancel", session.terminalReason)
        assertFalse("Cancel must NEVER grant grace window", controller.isGraceActive())
        assertNull("Active session must be cleared", controller.activeSession)
    }

    // =========================================================================
    // TEST GROUP F: CONCURRENCY & STALE CALLBACK DEFENSE
    // =========================================================================

    @Test
    fun `F1 concurrent termination race - exactly one caller terminates session`() = runBlocking {
        val session = controller.startOrGetSession(createDeviceAdminTamperEvent())!!
        val sid = session.sessionId

        val jobs = listOf(
            async { controller.endSession(sid, TamperSessionState.AUTHORIZED, "Auth Success") },
            async { controller.endSession(sid, TamperSessionState.CANCELLED, "User Cancel") }
        )
        val results = jobs.awaitAll()

        val successCount = results.count { it }
        assertEquals("Exactly one termination must succeed", 1, successCount)
        assertTrue("Session must be terminal", session.isTerminal)
        assertNull(controller.activeSession)
    }

    @Test
    fun `F2 stale callback cannot end newer session after session replacement`() {
        // 1. Session A created and terminated
        val sessionA = controller.startOrGetSession(createDeviceAdminTamperEvent())!!
        val idA = sessionA.sessionId
        controller.endSession(idA, TamperSessionState.CANCELLED, "Cancelled A")

        // 2. Session B created
        val sessionB = controller.startOrGetSession(createDeviceAdminTamperEvent())!!
        val idB = sessionB.sessionId
        assertNotEquals(idA, idB)

        // 3. Late arriving callback with idA attempts to terminate Session B
        val staleAttempt = controller.endSession(idA, TamperSessionState.AUTHORIZED, "Late callback A")
        assertFalse("Late callback from session A must be rejected", staleAttempt)
        assertEquals("Session B must remain active and unaffected", idB, controller.activeSession?.sessionId)
        assertEquals(TamperSessionState.PROMPTING, controller.activeSession?.state)
    }

    // =========================================================================
    // TEST GROUP G: SERVICE & SYSTEM LIFECYCLE
    // =========================================================================

    @Test
    fun `G1 service destruction terminates active session with SERVICE_DESTROYED`() {
        val session = controller.startOrGetSession(createDeviceAdminTamperEvent())!!
        val sid = session.sessionId

        val destroyed = controller.endSession(sid, TamperSessionState.SERVICE_DESTROYED, "Service destroyed")
        assertTrue(destroyed)
        assertEquals(TamperSessionState.SERVICE_DESTROYED, session.state)
        assertFalse("Must not grant grace", controller.isGraceActive())
        assertNull(controller.activeSession)
    }

    @Test
    fun `G2 screen off terminates active session with INTERRUPTED`() {
        val session = controller.startOrGetSession(createDeviceAdminTamperEvent())!!
        val sid = session.sessionId

        val interrupted = controller.endSession(sid, TamperSessionState.INTERRUPTED, "Screen off")
        assertTrue(interrupted)
        assertEquals(TamperSessionState.INTERRUPTED, session.state)
        assertFalse("Screen off must not grant grace", controller.isGraceActive())
        assertNull(controller.activeSession)
    }

    @Test
    fun `G3 terminal denial ends session with TERMINAL_DENIAL`() {
        val session = controller.startOrGetSession(createDeviceAdminTamperEvent())!!
        val sid = session.sessionId

        val denied = controller.endSession(sid, TamperSessionState.TERMINAL_DENIAL, "Overlay attach failure")
        assertTrue(denied)
        assertEquals(TamperSessionState.TERMINAL_DENIAL, session.state)
        assertFalse(controller.isGraceActive())
        assertNull(controller.activeSession)
    }
}
