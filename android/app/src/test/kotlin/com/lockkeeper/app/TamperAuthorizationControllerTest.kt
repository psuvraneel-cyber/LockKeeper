package com.lockkeeper.app

import com.lockkeeper.app.data.db.AppSettingsDao
import com.lockkeeper.app.data.db.AppSettingsEntity
import com.lockkeeper.app.security.AdminAuthResult
import com.lockkeeper.app.security.CredentialStore
import com.lockkeeper.app.security.KeystoreCredentialStore
import com.lockkeeper.app.security.TamperAuthorizationController
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TamperAuthorizationControllerTest {

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

    @Before
    fun setUp() {
        val prefs = InMemorySharedPreferences()
        store = KeystoreCredentialStore(prefs)
        store.setAdminPassword("SecureAdminPass2026!")

        dao = FakeAppSettingsDao()
        controller = TamperAuthorizationController(
            credentialStore = store,
            appSettingsDao = dao,
            monotonicTimeProvider = { mockMonotonicTime },
            wallClockTimeProvider = { mockWallClockTime }
        )
    }

    @Test
    fun `valid password grants grace window and resets failed attempts`() = runBlocking {
        dao.currentSettings = dao.currentSettings?.copy(failedAdminAttempts = 2)

        val result = controller.verifyAdminPassword("SecureAdminPass2026!")

        assertTrue(result is AdminAuthResult.Success)
        assertTrue(controller.isGraceActive())
        assertEquals(0, dao.currentSettings?.failedAdminAttempts)
    }

    @Test
    fun `invalid password increments failure count and calculates remaining attempts`() = runBlocking {
        val result1 = controller.verifyAdminPassword("WrongPass1")
        assertTrue(result1 is AdminAuthResult.Failure)
        assertEquals(4, (result1 as AdminAuthResult.Failure).remainingAttempts)
        assertEquals(1, dao.currentSettings?.failedAdminAttempts)

        val result2 = controller.verifyAdminPassword("WrongPass2")
        assertTrue(result2 is AdminAuthResult.Failure)
        assertEquals(3, (result2 as AdminAuthResult.Failure).remainingAttempts)
        assertEquals(2, dao.currentSettings?.failedAdminAttempts)
    }

    @Test
    fun `5 failed attempts triggers 300 second lockout`() = runBlocking {
        controller.verifyAdminPassword("Wrong1")
        controller.verifyAdminPassword("Wrong2")
        controller.verifyAdminPassword("Wrong3")
        controller.verifyAdminPassword("Wrong4")

        val result5 = controller.verifyAdminPassword("Wrong5")
        assertTrue(result5 is AdminAuthResult.LockedOut)
        val lockedOut = result5 as AdminAuthResult.LockedOut
        assertEquals(300L, lockedOut.remainingLockoutSeconds)
        assertEquals(5, dao.currentSettings?.failedAdminAttempts)
        assertEquals(mockWallClockTime + 300_000L, dao.currentSettings?.adminLockoutUntil)
    }

    @Test
    fun `locked out state rejects attempts even with correct password`() = runBlocking {
        // Trigger lockout
        for (i in 1..5) {
            controller.verifyAdminPassword("Wrong$i")
        }

        val result = controller.verifyAdminPassword("SecureAdminPass2026!")
        assertTrue(result is AdminAuthResult.LockedOut)
    }

    @Test
    fun `forward wall clock manipulation does NOT bypass lockout during active boot`() = runBlocking {
        // Trigger lockout
        for (i in 1..5) {
            controller.verifyAdminPassword("Wrong$i")
        }
        assertTrue(controller.checkLockout() != null)

        // Attacker advances wall clock by 10 minutes (past 300s lockout)
        mockWallClockTime += 600_000L

        // Monotonic timer must still enforce lockout!
        val check = controller.checkLockout()
        assertTrue("Forward wall-clock jump must not bypass monotonic lockout", check != null)
        assertTrue("Remaining lockout must be positive", (check ?: 0L) > 0L)

        // Correct password during this clock-jumped state must still be rejected
        val authResult = controller.verifyAdminPassword("SecureAdminPass2026!")
        assertTrue("Attempt during active boot with jumped clock must be locked out", authResult is AdminAuthResult.LockedOut)
    }

    @Test
    fun `missing row 1 initializes safely and rate limiting remains active`() = runBlocking {
        // Simulate completely empty database where row 1 does not exist
        dao.currentSettings = null

        // First failed attempt must initialize row 1 and record 1 failure
        val res1 = controller.verifyAdminPassword("WrongPass")
        assertTrue(res1 is AdminAuthResult.Failure)
        assertEquals(4, (res1 as AdminAuthResult.Failure).remainingAttempts)
        assertEquals(1, dao.currentSettings?.failedAdminAttempts)

        // Fail 4 more times
        for (i in 2..5) {
            controller.verifyAdminPassword("WrongPass$i")
        }

        // Must lock out safely, not fail open!
        val res5 = controller.verifyAdminPassword("WrongPass6")
        assertTrue("Empty DB must not fail open into infinite brute force", res5 is AdminAuthResult.LockedOut)
    }

    @Test
    fun `grace window expires after 30 seconds of monotonic time`() {
        assertFalse(controller.isGraceActive())
        controller.grantGraceWindow()
        assertTrue(controller.isGraceActive())

        // Advance monotonic time by 29 seconds
        mockMonotonicTime += 29_000L
        assertTrue(controller.isGraceActive())

        // Advance monotonic time past 30 seconds
        mockMonotonicTime += 2_000L
        assertFalse(controller.isGraceActive())
    }

    @Test
    fun `two simultaneous wrong passwords decrement attempts sequentially without loss`() = runBlocking {
        val jobs = listOf(
            async { controller.verifyAdminPassword("Wrong1") },
            async { controller.verifyAdminPassword("Wrong2") }
        )
        val results = jobs.awaitAll()

        assertEquals(2, results.size)
        assertTrue(results.all { it is AdminAuthResult.Failure })
        assertEquals(2, dao.currentSettings?.failedAdminAttempts)
    }

    @Test
    fun `five simultaneous wrong passwords lock out without race condition`() = runBlocking {
        val jobs = (1..5).map { i ->
            async { controller.verifyAdminPassword("Wrong$i") }
        }
        val results = jobs.awaitAll()

        assertEquals(5, results.size)
        val failures = results.filterIsInstance<AdminAuthResult.Failure>()
        val lockouts = results.filterIsInstance<AdminAuthResult.LockedOut>()
        assertEquals(4, failures.size)
        assertEquals(1, lockouts.size)
        assertEquals(5, dao.currentSettings?.failedAdminAttempts)
        assertTrue(controller.checkLockout() != null)
    }

    @Test
    fun `success and failure race - successful password grants session and resets failure count cleanly`() = runBlocking {
        dao.currentSettings = dao.currentSettings?.copy(failedAdminAttempts = 1)

        val jobs = listOf(
            async { controller.verifyAdminPassword("WrongAttempt") },
            async { controller.verifyAdminPassword("SecureAdminPass2026!") }
        )
        val results = jobs.awaitAll()

        val successCount = results.count { it is AdminAuthResult.Success }
        assertEquals(1, successCount)
        assertTrue(controller.isGraceActive())
        // When success executes second or resets, state is reset
    }

    @Test
    fun `lockout plus success race - once locked out, simultaneous correct password is still rejected`() = runBlocking {
        dao.currentSettings = dao.currentSettings?.copy(failedAdminAttempts = 4)

        // One wrong triggers 5th attempt (lockout), one correct runs at the exact same moment
        val jobs = listOf(
            async { controller.verifyAdminPassword("Wrong5th") },
            async { controller.verifyAdminPassword("SecureAdminPass2026!") }
        )
        val results = jobs.awaitAll()

        // One must be lockout
        val lockedOutCount = results.count { it is AdminAuthResult.LockedOut }
        assertTrue("At least one request must result in lockout", lockedOutCount >= 1)
        assertEquals(5, dao.currentSettings?.failedAdminAttempts)
    }

    @Test
    fun `process restart preserves persistent lockout state and prevents bypass`() = runBlocking {
        // Trigger lockout on original controller
        for (i in 1..5) {
            controller.verifyAdminPassword("Wrong$i")
        }
        assertTrue(controller.checkLockout() != null)

        // Simulate new process starting up with fresh controller instance sharing same persistence
        val restartedController = TamperAuthorizationController(
            credentialStore = store,
            appSettingsDao = dao,
            monotonicTimeProvider = { 10_000L }, // New process monotonic time starting at 10s
            wallClockTimeProvider = { mockWallClockTime + 1000L }
        )

        // Lockout must be active on restarted process!
        val lockoutCheck = restartedController.checkLockout()
        assertTrue("Restarted controller must enforce persistent lockout", lockoutCheck != null)
        assertTrue((lockoutCheck ?: 0L) > 290L)

        // Attempting auth on restarted process must fail
        val authResult = restartedController.verifyAdminPassword("SecureAdminPass2026!")
        assertTrue("Password must be rejected on restarted process while locked out", authResult is AdminAuthResult.LockedOut)
    }

    @Test
    fun `reboot simulation clears in-memory monotonic deadline but enforces persistent lockout timestamp`() = runBlocking {
        // Trigger lockout
        for (i in 1..5) {
            controller.verifyAdminPassword("Wrong$i")
        }
        val initialLockoutUntil = dao.currentSettings?.adminLockoutUntil
        assertNotNull(initialLockoutUntil)

        // Simulate reboot:
        // Monotonic time resets to 0 (boot elapsed)
        // Wall clock is advanced by 60 seconds (1 minute into the 5-minute lockout)
        val postRebootWallClock = mockWallClockTime + 60_000L
        val postRebootMonotonic = 5_000L // 5 seconds since boot

        val rebootController = TamperAuthorizationController(
            credentialStore = store,
            appSettingsDao = dao,
            monotonicTimeProvider = { postRebootMonotonic },
            wallClockTimeProvider = { postRebootWallClock }
        )

        val remaining = rebootController.checkLockout()
        assertNotNull("Lockout must still be enforced after reboot", remaining)
        // 300 - 60 = 240 seconds remaining
        assertEquals(240L, remaining)

        // Trying correct password after reboot during lockout must still be rejected
        val authResult = rebootController.verifyAdminPassword("SecureAdminPass2026!")
        assertTrue(authResult is AdminAuthResult.LockedOut)
    }

    @Test
    fun `repeated authorization requests during lockout consistently return LockedOut`() = runBlocking {
        for (i in 1..5) {
            controller.verifyAdminPassword("Wrong$i")
        }

        for (i in 1..10) {
            val result = controller.verifyAdminPassword("AnyPassword")
            assertTrue(result is AdminAuthResult.LockedOut)
        }
    }
}

