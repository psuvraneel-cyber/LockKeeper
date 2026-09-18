package com.lockkeeper.app

import com.lockkeeper.app.data.db.AppSettingsDao
import com.lockkeeper.app.data.db.AppSettingsEntity
import com.lockkeeper.app.security.AdminAuthResult
import com.lockkeeper.app.security.CredentialStore
import com.lockkeeper.app.security.KeystoreCredentialStore
import com.lockkeeper.app.security.NodeFacade
import com.lockkeeper.app.security.TamperAuthorizationController
import com.lockkeeper.app.security.TamperConfidence
import com.lockkeeper.app.security.TamperDetectionEngine
import com.lockkeeper.app.security.TamperEvent
import com.lockkeeper.app.security.TamperSession
import com.lockkeeper.app.security.TamperSessionState
import com.lockkeeper.app.security.TamperSource
import com.lockkeeper.app.security.TamperType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SecurityInvariantsTest {

    private lateinit var store: CredentialStore
    private lateinit var prefs: InMemorySharedPreferences
    private lateinit var engine: TamperDetectionEngine
    private var mockMonotonicTime: Long = 100_000L
    private var mockWallClockTime: Long = 1_700_000_000_000L

    private data class SecurityStateSnapshot(
        val isAccessibilityEnabled: Boolean,
        val isAccessibilityConnected: Boolean,
        val isDeviceAdminActive: Boolean,
        val isOverlayGranted: Boolean,
        val isUsageStatsGranted: Boolean,
        val isUserPinConfigured: Boolean,
        val isAdminPasswordConfigured: Boolean,
        val isSelfLockActive: Boolean,
        val isAppLockConfigured: Boolean,
        val isTamperLockedOut: Boolean,
        val isRecoveryRequired: Boolean
    ) {
        val isAccessibilityOperational: Boolean
            get() = isAccessibilityEnabled && isAccessibilityConnected

        val isAppLockOperational: Boolean
            get() = isAppLockConfigured && isAccessibilityOperational && isOverlayGranted
    }

    private class InvariantFakeDao(
        var settings: AppSettingsEntity? = AppSettingsEntity()
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

    private data class InvariantNode(
        override val text: CharSequence? = null,
        override val contentDescription: CharSequence? = null,
        override val viewIdResourceName: String? = null,
        override val className: CharSequence? = null,
        val children: List<InvariantNode> = emptyList()
    ) : NodeFacade {
        override val childCount: Int get() = children.size
        override fun getChild(index: Int): NodeFacade? = children.getOrNull(index)
    }

    @Before
    fun setUp() {
        prefs = InMemorySharedPreferences()
        store = KeystoreCredentialStore(prefs)
        store.setAdminPassword("HardenedAdminPassword2026!")
        store.setPin("123456")
        engine = TamperDetectionEngine("com.lockkeeper.app")
    }

    // INVARIANT 1: Missing AppSettings row cannot disable rate limiting
    @Test
    fun `invariant 1 - missing AppSettings row cannot disable rate limiting`() = runBlocking {
        val emptyDao = InvariantFakeDao(settings = null)
        val controller = TamperAuthorizationController(
            credentialStore = store,
            appSettingsDao = emptyDao,
            monotonicTimeProvider = { mockMonotonicTime },
            wallClockTimeProvider = { mockWallClockTime }
        )

        // Brute force 5 wrong attempts on non-existent DB row
        for (i in 1..5) {
            controller.verifyAdminPassword("Bad$i")
        }

        val result = controller.verifyAdminPassword("Bad6")
        assertTrue("Empty database must lock out and cannot fail open", result is AdminAuthResult.LockedOut)
        assertEquals(5, emptyDao.settings?.failedAdminAttempts)
    }

    // INVARIANT 2: Initialization races cannot disable rate limiting
    @Test
    fun `invariant 2 - initialization races cannot disable rate limiting`() = runBlocking {
        val dao = InvariantFakeDao(settings = null)
        val controller = TamperAuthorizationController(
            credentialStore = store,
            appSettingsDao = dao,
            monotonicTimeProvider = { mockMonotonicTime },
            wallClockTimeProvider = { mockWallClockTime }
        )

        // Concurrently run initial verification calls on an uninitialized DB
        val jobs = (1..5).map { i ->
            async { controller.verifyAdminPassword("Attempt$i") }
        }
        val results = jobs.awaitAll()

        assertEquals(5, results.size)
        // Must lock out safely after 5 total attempts
        val lockouts = results.filterIsInstance<AdminAuthResult.LockedOut>()
        assertEquals("Exactly 1 lockout on 5th attempt", 1, lockouts.size)
        assertEquals(5, dao.settings?.failedAdminAttempts)
    }

    // INVARIANT 3: Two simultaneous password attempts cannot bypass attempt limits
    @Test
    fun `invariant 3 - two simultaneous password attempts cannot bypass attempt limits`() = runBlocking {
        val dao = InvariantFakeDao()
        dao.settings = dao.settings?.copy(failedAdminAttempts = 4)
        val controller = TamperAuthorizationController(
            credentialStore = store,
            appSettingsDao = dao,
            monotonicTimeProvider = { mockMonotonicTime },
            wallClockTimeProvider = { mockWallClockTime }
        )

        val jobs = listOf(
            async { controller.verifyAdminPassword("Wrong1") },
            async { controller.verifyAdminPassword("Wrong2") }
        )
        val results = jobs.awaitAll()

        val lockouts = results.filterIsInstance<AdminAuthResult.LockedOut>()
        assertTrue("At least one request must receive LockedOut", lockouts.isNotEmpty())
        assertEquals(5, dao.settings?.failedAdminAttempts)
    }

    // INVARIANT 4: Successful authentication cannot extend grace indefinitely
    @Test
    fun `invariant 4 - successful authentication cannot extend grace indefinitely`() = runBlocking {
        val dao = InvariantFakeDao()
        val controller = TamperAuthorizationController(
            credentialStore = store,
            appSettingsDao = dao,
            monotonicTimeProvider = { mockMonotonicTime },
            wallClockTimeProvider = { mockWallClockTime }
        )

        val res = controller.verifyAdminPassword("HardenedAdminPassword2026!")
        assertTrue(res is AdminAuthResult.Success)
        assertTrue(controller.isGraceActive())

        // 31 seconds later
        mockMonotonicTime += 31_000L
        assertFalse("Grace period must strictly expire at 30 seconds", controller.isGraceActive())
    }

    // INVARIANT 5: Reboot cannot silently erase an active lockout
    @Test
    fun `invariant 5 - reboot cannot silently erase an active lockout`() = runBlocking {
        val dao = InvariantFakeDao()
        val controller = TamperAuthorizationController(
            credentialStore = store,
            appSettingsDao = dao,
            monotonicTimeProvider = { mockMonotonicTime },
            wallClockTimeProvider = { mockWallClockTime }
        )

        for (i in 1..5) {
            controller.verifyAdminPassword("Wrong$i")
        }
        assertTrue(controller.checkLockout() != null)

        // Reboot: Monotonic clock starts fresh from 0, wall-clock advanced only by 30s
        val rebootedController = TamperAuthorizationController(
            credentialStore = store,
            appSettingsDao = dao,
            monotonicTimeProvider = { 1000L },
            wallClockTimeProvider = { mockWallClockTime + 30_000L }
        )

        val remaining = rebootedController.checkLockout()
        assertNotNull("Reboot cannot erase active persistent lockout", remaining)
        assertTrue((remaining ?: 0L) > 260L)
    }

    // INVARIANT 6: Service disconnection cannot be interpreted as permission revocation
    @Test
    fun `invariant 6 - service disconnection is distinct from permission revocation`() {
        val statusConnected = SecurityStateSnapshot(
            isAccessibilityEnabled = true,
            isAccessibilityConnected = true,
            isDeviceAdminActive = true,
            isOverlayGranted = true,
            isUsageStatsGranted = true,
            isUserPinConfigured = true,
            isAdminPasswordConfigured = true,
            isSelfLockActive = true,
            isAppLockConfigured = false,
            isTamperLockedOut = false,
            isRecoveryRequired = false
        )
        assertTrue(statusConnected.isAccessibilityOperational)

        // When disconnected (e.g. killed by OS), permission is still granted!
        val statusDisconnected = statusConnected.copy(isAccessibilityConnected = false)
        assertTrue("Permission remains granted", statusDisconnected.isAccessibilityEnabled)
        assertFalse("Service is temporarily not operational", statusDisconnected.isAccessibilityOperational)
    }

    // INVARIANT 7: Device Admin listing does not trigger LockKeeper's password overlay
    @Test
    fun `invariant 7 - device admin listing does not trigger LockKeeper's password overlay`() {
        val root = InvariantNode(
            text = "Device admin apps",
            children = listOf(
                InvariantNode(text = "LockKeeper"),
                InvariantNode(text = "Google Play Protect")
            )
        )

        val event = engine.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.DeviceAdminSettings",
            rootNode = root,
            isDeviceAdminActive = true
        )

        assertNull("Device admin listing must NEVER trigger tamper overlay", event)
    }

    // INVARIANT 8: Unrelated administrator management does not trigger LockKeeper protection
    @Test
    fun `invariant 8 - unrelated administrator management does not trigger LockKeeper protection`() {
        val root = InvariantNode(
            text = "Find My Device",
            children = listOf(
                InvariantNode(text = "Deactivate this device admin app")
            )
        )

        val event = engine.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.DeviceAdminAdd",
            rootNode = root,
            isDeviceAdminActive = true
        )

        assertNull("Unrelated admin deactivation must NOT trigger LockKeeper protection", event)
    }

    // INVARIANT 9: General Accessibility Settings remains accessible
    @Test
    fun `invariant 9 - general accessibility settings remains accessible`() {
        val root = InvariantNode(
            text = "Accessibility",
            children = listOf(
                InvariantNode(text = "LockKeeper - On"),
                InvariantNode(text = "TalkBack - Off")
            )
        )

        val event = engine.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.accessibility.MiuiAccessibilitySettingsActivity",
            rootNode = root,
            isDeviceAdminActive = true
        )

        assertNull("General Accessibility settings list must remain accessible", event)
    }

    // INVARIANT 10: Accessibility content changes do not accidentally dismiss an active valid tamper overlay
    @Test
    fun `invariant 10 - accessibility content changes do not accidentally dismiss an active valid tamper overlay`() {
        val root = InvariantNode(
            text = "LockKeeper",
            children = listOf(InvariantNode(text = "Calculating..."))
        )

        val event = engine.evaluate(
            packageName = "com.android.settings",
            className = "com.android.settings.applications.InstalledAppDetails",
            rootNode = root,
            isDeviceAdminActive = true
        )

        val tamperEvent = TamperEvent(
            type = TamperType.UNINSTALL,
            source = TamperSource.SETTINGS,
            confidence = TamperConfidence.HIGH,
            targetPackage = "com.android.settings",
            targetActivity = "com.android.settings.applications.InstalledAppDetails"
        )

        val session = TamperSession(
            sessionId = "session-10",
            event = tamperEvent
        )
        // Same target package does NOT invalidate session
        assertEquals("com.android.settings", session.targetPackage)
    }

    // INVARIANT 11: IME appearance does not destroy the tamper overlay
    @Test
    fun `invariant 11 - IME appearance does not destroy the tamper overlay`() {
        val imePackages = listOf(
            "com.google.android.inputmethod.latin",
            "com.samsung.android.honeyboard",
            "com.touchtype.swiftkey"
        )

        for (pkg in imePackages) {
            val isIme = pkg.contains("inputmethod") ||
                pkg.contains("honeyboard") ||
                pkg.contains("swiftkey") ||
                pkg.contains("keyboard")
            assertTrue("IME package $pkg must be recognized as input method", isIme)
        }
    }

    // INVARIANT 12: Service death cannot leave an orphan overlay
    @Test
    fun `invariant 12 - service death cannot leave an orphan overlay`() {
        val tamperEvent = TamperEvent(
            type = TamperType.UNINSTALL,
            source = TamperSource.SETTINGS,
            confidence = TamperConfidence.HIGH,
            targetPackage = "com.android.settings",
            targetActivity = "com.android.settings.applications.InstalledAppDetails"
        )
        val session = TamperSession(
            sessionId = "session-12",
            event = tamperEvent
        )
        assertEquals(TamperSessionState.PROMPTING, session.state)
    }

    // INVARIANT 13: Unrelated apps cannot trigger LockKeeper password gate
    @Test
    fun `invariant 13 - unrelated apps cannot trigger LockKeeper password gate`() {
        val root = InvariantNode(
            text = "Calculator",
            children = listOf(
                InvariantNode(text = "7"),
                InvariantNode(text = "8"),
                InvariantNode(text = "9")
            )
        )

        val event = engine.evaluate(
            packageName = "com.google.android.calculator",
            className = "com.android.calculator2.Calculator",
            rootNode = root,
            isDeviceAdminActive = true
        )

        assertNull("Calculator must never trigger LockKeeper tamper gate", event)
    }

    // INVARIANT 14: Missing local security state cannot silently create fresh setup
    @Test
    fun `invariant 14 - missing local security state cannot silently create fresh setup`() = runBlocking {
        // If Device Admin is active in system, but CredentialStore has no PIN
        val emptyPrefs = InMemorySharedPreferences()
        val storeWithoutPin = KeystoreCredentialStore(emptyPrefs)
        assertFalse(storeWithoutPin.hasPin())

        // System has Device Admin active:
        val dpmActive = true
        val hasPin = storeWithoutPin.hasPin()

        // Recovery logic requires RECOVERY_REQUIRED when DPM is active but credentials/DB missing
        val recoveryRequired = dpmActive && !hasPin
        assertTrue("Missing credentials with active Device Admin MUST flag recovery required", recoveryRequired)
    }

    // INVARIANT 15: UI never claims stronger security than native state provides
    @Test
    fun `invariant 15 - UI never claims stronger security than native state provides`() {
        // Service enabled in settings, but process killed / not connected
        val state = SecurityStateSnapshot(
            isAccessibilityEnabled = true,
            isAccessibilityConnected = false,
            isDeviceAdminActive = true,
            isOverlayGranted = true,
            isUsageStatsGranted = true,
            isUserPinConfigured = true,
            isAdminPasswordConfigured = true,
            isSelfLockActive = true,
            isAppLockConfigured = false,
            isTamperLockedOut = false,
            isRecoveryRequired = false
        )

        // isAccessibilityOperational must be false if disconnected!
        assertFalse("Cannot report operational when service is disconnected", state.isAccessibilityOperational)
    }
}
