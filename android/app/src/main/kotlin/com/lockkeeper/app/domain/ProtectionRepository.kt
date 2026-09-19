package com.lockkeeper.app.domain

import android.content.Context
import com.lockkeeper.app.data.db.AppDatabase
import com.lockkeeper.app.data.db.AppSettingsEntity
import com.lockkeeper.app.data.db.LockedAppEntity
import com.lockkeeper.app.security.CredentialStore
import com.lockkeeper.app.security.KeystoreCredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProtectionRepository(
    private val context: Context,
    val database: AppDatabase = AppDatabase.getInstance(context),
    val credentialStore: CredentialStore = KeystoreCredentialStore(context),
    val decisionEngine: LockDecisionEngine = LockDecisionEngine(),
    val selfLockManager: SelfLockSessionManager = SelfLockSessionManager(),
    val tamperController: com.lockkeeper.app.security.TamperAuthorizationController =
        com.lockkeeper.app.security.TamperAuthorizationController(credentialStore, database.appSettingsDao())
) {
    companion object {
        const val STARTUP_CONNECTION_GRACE_MS = 2500L

        @Volatile
        private var INSTANCE: ProtectionRepository? = null

        fun getInstance(context: Context): ProtectionRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ProtectionRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    @Volatile
    private var startupElapsedRealtime = try {
        android.os.SystemClock.elapsedRealtime()
    } catch (_: RuntimeException) {
        System.currentTimeMillis()
    }

    fun setStartupElapsedRealtimeForTesting(time: Long) {
        startupElapsedRealtime = time
    }

    private val lockedAppDao = database.lockedAppDao()
    private val appSettingsDao = database.appSettingsDao()
    private val prefs = context.getSharedPreferences("lockkeeper_protection_prefs", Context.MODE_PRIVATE)

    init {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val s = appSettingsDao.getOrInitializeSettings()
                prefs.edit().putBoolean("onboarding_complete", s.onboardingComplete).apply()
                checkRecoveryStatus()
            } catch (_: Exception) {}
        }
    }

    suspend fun checkRecoveryStatus(): Boolean = withContext(Dispatchers.IO) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
        val adminComponent = android.content.ComponentName(context, com.lockkeeper.app.receiver.LockKeeperDeviceAdminReceiver::class.java)
        val isAdminActive = dpm?.isAdminActive(adminComponent) == true
        val hasCreds = credentialStore.hasPin() && credentialStore.hasAdminPassword()
        val settings = appSettingsDao.getOrInitializeSettings()

        val wasPreviouslyProvisioned = settings.securityProvisioned || settings.onboardingComplete
        val isRecovery = if (wasPreviouslyProvisioned) {
            // A previously provisioned installation with missing credentials or corrupted state MUST fail closed into recovery
            !hasCreds || !settings.onboardingComplete || (isAdminActive && !hasCreds)
        } else {
            // Fresh install or setup in progress: granting Device Admin or Accessibility is normal setup progression
            // and must NOT be treated as a security compromise.
            false
        }

        if (isRecovery) {
            if (!settings.recoveryRequired) {
                appSettingsDao.setRecoveryRequired(true)
            }
            true
        } else if (settings.recoveryRequired && (!isAdminActive || (hasCreds && settings.onboardingComplete && settings.securityProvisioned))) {
            appSettingsDao.setRecoveryRequired(false)
            false
        } else {
            settings.recoveryRequired
        }
    }

    suspend fun resolveRecovery(pin: String, adminPass: String): Boolean = withContext(Dispatchers.IO) {
        if (pin.length < 4 || pin.length > 8 || !pin.all { it.isDigit() }) return@withContext false
        if (adminPass.length < 4 || adminPass == pin) return@withContext false

        val pinSaved = credentialStore.setPin(pin)
        val adminSaved = credentialStore.setAdminPassword(adminPass)
        if (pinSaved && adminSaved) {
            appSettingsDao.setProvisionedAndOnboardingComplete(provisioned = true, complete = true)
            appSettingsDao.setRecoveryRequired(false)
            prefs.edit().putBoolean("onboarding_complete", true).apply()
            grantSelfLockSession()
            notifySecurityStateChanged()
            true
        } else {
            false
        }
    }

    suspend fun completeInitialProvisioning(): Boolean = withContext(Dispatchers.IO) {
        val hasCreds = credentialStore.hasPin() && credentialStore.hasAdminPassword()
        if (!hasCreds) return@withContext false
        setOnboardingComplete(true)
        true
    }

    suspend fun isSecurityProvisioned(): Boolean = withContext(Dispatchers.IO) {
        getSettings().securityProvisioned
    }

    fun isOnboardingCompleteSync(): Boolean {
        return prefs.getBoolean("onboarding_complete", false)
    }

    fun shouldProtectSettings(): Boolean {
        return isOnboardingCompleteSync() && credentialStore.hasAdminPassword()
    }

    suspend fun getSettings(): AppSettingsEntity = withContext(Dispatchers.IO) {
        appSettingsDao.getOrInitializeSettings().also {
            prefs.edit().putBoolean("onboarding_complete", it.onboardingComplete).apply()
        }
    }

    suspend fun getAllLockedApps(): List<LockedAppEntity> = withContext(Dispatchers.IO) {
        lockedAppDao.getAllLockedApps()
    }

    suspend fun getLockedApp(packageName: String): LockedAppEntity? = withContext(Dispatchers.IO) {
        lockedAppDao.getLockedApp(packageName)
    }

    suspend fun setLockedApp(
        packageName: String,
        isLocked: Boolean,
        cooldownMinutes: Int,
        strictLock: Boolean
    ) = withContext(Dispatchers.IO) {
        val existing = lockedAppDao.getLockedApp(packageName)
        val entity = (existing ?: LockedAppEntity(packageName = packageName)).copy(
            isLocked = isLocked,
            cooldownMinutes = cooldownMinutes,
            strictLock = strictLock,
            updatedAt = System.currentTimeMillis()
        )
        lockedAppDao.upsert(entity)
        if (isLocked) {
            decisionEngine.revokeAppSession(packageName)
        }
    }

    suspend fun removeLockedApp(packageName: String) = withContext(Dispatchers.IO) {
        decisionEngine.revokeAppSession(packageName)
        lockedAppDao.delete(packageName)
    }

    suspend fun evaluatePackage(
        packageName: String,
        currentTime: Long = System.currentTimeMillis()
    ): LockDecision = withContext(Dispatchers.IO) {
        try {
            val app = lockedAppDao.getLockedApp(packageName)
            val settings = getSettings()
            val isRecoveryReq = checkRecoveryStatus()
            val isTamperLocked = tamperController.checkLockout() != null
            val isGrace = tamperController.isGraceActive()
            val isProvisioned = settings.securityProvisioned && settings.onboardingComplete

            val nowElapsed = try {
                android.os.SystemClock.elapsedRealtime()
            } catch (_: RuntimeException) {
                System.currentTimeMillis()
            }
            val isStartupGraceActive = (nowElapsed - startupElapsedRealtime) < STARTUP_CONNECTION_GRACE_MS
            val isA11yConnected = com.lockkeeper.app.service.LockKeeperAccessibilityService.isConnected
            val enabledServices = try {
                android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
            } catch (_: Exception) { "" }
            val isA11yEnabled = enabledServices.contains(context.packageName)
            val isA11yConnectingUnderGrace = isA11yEnabled && !isA11yConnected && isStartupGraceActive

            val healthStatus = when {
                isRecoveryReq -> "RECOVERY_REQUIRED"
                !isProvisioned -> "SETUP_IN_PROGRESS"
                isA11yConnectingUnderGrace -> "INITIALIZING"
                else -> null
            }

            decisionEngine.evaluate(
                targetPackage = packageName,
                lockedApp = app,
                appSettings = settings,
                currentTime = currentTime,
                isRecoveryRequired = isRecoveryReq,
                isTamperLocked = isTamperLocked,
                isTamperGraceActive = isGrace,
                securityHealthStatus = healthStatus,
                isSecurityProvisioned = isProvisioned,
                isSetupInProgress = !isProvisioned && !isRecoveryReq
            )
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (e: Exception) {
            // INV-208: An exception in a security decision path must fail closed!
            LockDecision.DenyUnknown(ProtectionDecisionReason.DENIED_NATIVE_FAILURE)
        }
    }

    suspend fun handleFailedPin(): Pair<Int, Long?> = withContext(Dispatchers.IO) {
        val settings = getSettings()
        val newAttempts = settings.failedPinAttempts + 1
        val lockoutUntil = if (newAttempts >= LockDecisionEngine.MAX_FAILED_PIN_ATTEMPTS) {
            System.currentTimeMillis() + LockDecisionEngine.LOCKOUT_DURATION_MS
        } else {
            null
        }
        appSettingsDao.updatePinLockout(newAttempts, lockoutUntil)
        Pair(newAttempts, lockoutUntil)
    }

    suspend fun handleSuccessfulPin(
        packageName: String,
        currentTime: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        appSettingsDao.resetPinFailures(currentTime)
        decisionEngine.grantAppSession(packageName, currentTime)
    }

    suspend fun handleAppExited(
        packageName: String,
        currentTime: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        val app = lockedAppDao.getLockedApp(packageName)
        decisionEngine.revokeAppSession(packageName)
        if (app != null && app.isLocked && app.cooldownMinutes > 0) {
            // Cooldown starts when user leaves the protected application
            val lockedUntil = currentTime + (app.cooldownMinutes * 60 * 1000L)
            lockedAppDao.updateLockedUntil(packageName, lockedUntil, currentTime)
        }
    }

    suspend fun isOnboardingComplete(): Boolean = withContext(Dispatchers.IO) {
        getSettings().onboardingComplete
    }

    suspend fun setOnboardingComplete(complete: Boolean) = withContext(Dispatchers.IO) {
        prefs.edit().putBoolean("onboarding_complete", complete).apply()
        if (complete) {
            // Atomically commit provisioned and onboarding status
            appSettingsDao.setProvisionedAndOnboardingComplete(provisioned = true, complete = true)
            appSettingsDao.setSelfLockEnabled(true)
            grantSelfLockSession()
        } else {
            appSettingsDao.setOnboardingComplete(false)
        }
        notifySecurityStateChanged()
    }

    suspend fun isSelfLockEnabled(): Boolean = withContext(Dispatchers.IO) {
        getSettings().selfLockEnabled
    }

    suspend fun setSelfLockEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        appSettingsDao.setSelfLockEnabled(enabled)
        if (!enabled) {
            invalidateSelfLockSession()
        }
    }

    suspend fun getSelfLockTimeoutSeconds(): Int = withContext(Dispatchers.IO) {
        getSettings().selfLockTimeoutSeconds
    }

    suspend fun setSelfLockTimeoutSeconds(seconds: Int) = withContext(Dispatchers.IO) {
        appSettingsDao.setSelfLockTimeout(seconds)
    }

    suspend fun isSelfLockRequired(currentTime: Long = System.currentTimeMillis()): Boolean = withContext(Dispatchers.IO) {
        val settings = getSettings()
        selfLockManager.isAuthRequired(
            selfLockEnabled = settings.selfLockEnabled,
            onboardingComplete = settings.onboardingComplete,
            timeoutSeconds = settings.selfLockTimeoutSeconds,
            lockoutUntil = settings.pinLockoutUntil,
            currentTime = currentTime
        )
    }

    fun grantSelfLockSession() = selfLockManager.grantSession()

    fun invalidateSelfLockSession() = selfLockManager.invalidateSession()

    fun recordAppBackgrounded(timestamp: Long = System.currentTimeMillis()) = selfLockManager.recordBackgrounded(timestamp)

    suspend fun checkAppForegrounded(currentTime: Long = System.currentTimeMillis()): Boolean {
        return isSelfLockRequired(currentTime)
    }

    suspend fun verifySelfLockPin(pin: String): Map<String, Any?> = withContext(Dispatchers.IO) {
        val settings = getSettings()
        val now = System.currentTimeMillis()
        val lockoutUntil = settings.pinLockoutUntil
        if (lockoutUntil != null && lockoutUntil > now) {
            val remainingSec = maxOf(1L, (lockoutUntil - now) / 1000L)
            return@withContext mapOf(
                "success" to false,
                "isLockedOut" to true,
                "remainingLockoutSeconds" to remainingSec,
                "failedAttempts" to settings.failedPinAttempts
            )
        }

        val isValid = credentialStore.verifyPin(pin)
        if (isValid) {
            appSettingsDao.resetPinFailures(now)
            grantSelfLockSession()
            mapOf(
                "success" to true,
                "isLockedOut" to false,
                "remainingLockoutSeconds" to 0L,
                "failedAttempts" to 0
            )
        } else {
            val (attempts, newLockout) = handleFailedPin()
            val remainingSec = if (newLockout != null) maxOf(1L, (newLockout - now) / 1000L) else 0L
            mapOf(
                "success" to false,
                "isLockedOut" to (newLockout != null),
                "remainingLockoutSeconds" to remainingSec,
                "failedAttempts" to attempts
            )
        }
    }

    suspend fun getAuthoritativeSecurityStatus(): Map<String, Any?> = withContext(Dispatchers.IO) {
        val isA11yConnected = com.lockkeeper.app.service.LockKeeperAccessibilityService.isConnected
        val enabledServices = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        val isA11yEnabled = enabledServices.contains(context.packageName)
        val isA11yOperational = isA11yConnected && isA11yEnabled

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
        val adminComponent = android.content.ComponentName(context, com.lockkeeper.app.receiver.LockKeeperDeviceAdminReceiver::class.java)
        val isDeviceAdminActive = dpm?.isAdminActive(adminComponent) == true

        val isOverlayGranted = android.provider.Settings.canDrawOverlays(context)

        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val usageMode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        }
        val isUsageGranted = usageMode == android.app.AppOpsManager.MODE_ALLOWED

        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val isBatteryExempted = pm.isIgnoringBatteryOptimizations(context.packageName)

        val hasPin = credentialStore.hasPin()
        val hasAdminPass = credentialStore.hasAdminPassword()

        val settings = getSettings()
        val lockedApps = lockedAppDao.getAllLockedApps()
        val appLockConfigured = lockedApps.any { it.isLocked }
        val appLockOperational = appLockConfigured && isA11yOperational && isOverlayGranted

        val isRecoveryReq = checkRecoveryStatus()
        val isTamperLocked = tamperController.checkLockout() != null

        val nowElapsed = try {
            android.os.SystemClock.elapsedRealtime()
        } catch (_: RuntimeException) {
            System.currentTimeMillis()
        }
        val isStartupGraceActive = (nowElapsed - startupElapsedRealtime) < STARTUP_CONNECTION_GRACE_MS
        val isA11yConnectingUnderGrace = isA11yEnabled && !isA11yConnected && isStartupGraceActive

        val isFgsRunning = com.lockkeeper.app.service.LockKeeperForegroundService.isRunning
        val isProvisioned = settings.securityProvisioned && settings.onboardingComplete

        val degradedReasons = mutableListOf<String>()
        if (isRecoveryReq) {
            degradedReasons.add("Security state desynchronization detected: Device Administrator is active but local credentials are missing. Recovery required.")
        }
        if (!isDeviceAdminActive) {
            degradedReasons.add("Device Administrator is not activated")
        }
        if (!isA11yEnabled) {
            degradedReasons.add("Accessibility Service is not enabled in Android Settings")
        } else if (!isA11yConnected && !isStartupGraceActive) {
            degradedReasons.add("Accessibility Service is enabled in Settings but background service is disconnected")
        }
        if (!isOverlayGranted) {
            degradedReasons.add("Overlay Permission is not granted")
        }
        if (!isUsageGranted) {
            degradedReasons.add("Usage Access Permission is not granted")
        }
        if (!isBatteryExempted) {
            degradedReasons.add("Battery Optimization exemption is not granted")
        }
        if (!hasPin) {
            degradedReasons.add("User PIN is not configured")
        }
        if (!hasAdminPass) {
            degradedReasons.add("Admin Password is not configured")
        }
        if (isProvisioned && !isFgsRunning) {
            degradedReasons.add("Background protection service is not running")
        }

        val hasMissingPermissionsOrConfig = !isDeviceAdminActive || !isA11yEnabled || !isOverlayGranted || !isUsageGranted || !hasPin || !hasAdminPass || !isFgsRunning

        val overallStatus = when {
            isRecoveryReq -> "RECOVERY_REQUIRED"
            !isProvisioned -> "SETUP_IN_PROGRESS"
            hasMissingPermissionsOrConfig -> "DEGRADED"
            isA11yConnectingUnderGrace -> "INITIALIZING"
            !isA11yOperational -> "DEGRADED"
            appLockConfigured || settings.selfLockEnabled -> "PROTECTED"
            else -> "CONFIGURED"
        }

        mapOf(
            "overallStatus" to overallStatus,
            "degradedReasons" to degradedReasons,
            "isForegroundServiceRunning" to com.lockkeeper.app.service.LockKeeperForegroundService.isRunning,
            "isOverlayGranted" to isOverlayGranted,
            "isUsageGranted" to isUsageGranted,
            "isAccessibilityGranted" to isA11yEnabled,
            "isAccessibilityConnected" to isA11yConnected,
            "isAccessibilityOperational" to isA11yOperational,
            "isDeviceAdminGranted" to isDeviceAdminActive,
            "isBatteryExempted" to isBatteryExempted,
            "hasPin" to hasPin,
            "hasAdminPassword" to hasAdminPass,
            "selfLockActive" to settings.selfLockEnabled,
            "appLockConfigured" to appLockConfigured,
            "appLockOperational" to appLockOperational,
            "tamperLockedOut" to isTamperLocked,
            "recoveryRequired" to isRecoveryReq,
            "securityProvisioned" to isProvisioned,
            "isSetupInProgress" to (!isProvisioned && !isRecoveryReq),
            "onboardingComplete" to settings.onboardingComplete
        )
    }

    fun notifySecurityStateChanged() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val status = getAuthoritativeSecurityStatus()
                com.lockkeeper.app.bridge.PlatformChannelHandler.notifyProtectionStateChanged(status)
            } catch (_: Exception) {}
        }
    }
}
