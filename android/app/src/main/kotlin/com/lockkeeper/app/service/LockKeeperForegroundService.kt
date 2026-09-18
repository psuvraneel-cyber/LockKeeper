package com.lockkeeper.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lockkeeper.app.MainActivity
import com.lockkeeper.app.R
import com.lockkeeper.app.domain.LockDecision
import com.lockkeeper.app.domain.ProtectionRepository
import com.lockkeeper.app.overlay.OverlayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LockKeeperForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "lockkeeper_protection_channel"
        const val NOTIFICATION_ID = 1001

        @Volatile
        var isRunning: Boolean = false
            private set

        fun startService(context: Context) {
            val intent = Intent(context, LockKeeperForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, LockKeeperForegroundService::class.java)
            context.stopService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private var fallbackPollingJob: Job? = null
    private lateinit var repository: ProtectionRepository
    private lateinit var overlayManager: OverlayManager

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        repository = ProtectionRepository.getInstance(this)
        overlayManager = OverlayManager.getInstance(this)
        createNotificationChannel()
        startForegroundWithNotification()
        startUsageStatsFallbackIfNecessary()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        startForegroundWithNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        fallbackPollingJob?.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Attempt to keep service running or restart
        val restartIntent = Intent(applicationContext, LockKeeperForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(restartIntent)
        } else {
            applicationContext.startService(restartIntent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LockKeeper Protection Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows ongoing protection status of LockKeeper."
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification() {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LockKeeper Protection Active")
            .setContentText("App lock enforcement and tamper protection running.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startUsageStatsFallbackIfNecessary() {
        fallbackPollingJob?.cancel()
        fallbackPollingJob = serviceScope.launch {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            var lastForegroundPackage: String? = null

            while (isActive) {
                // If AccessibilityService is active and connected, fallback can sleep longer
                val accessibilityActive = LockKeeperAccessibilityService.isConnected
                if (!accessibilityActive && usageStatsManager != null) {
                    val endTime = System.currentTimeMillis()
                    val startTime = endTime - 1000L
                    val events = usageStatsManager.queryEvents(startTime, endTime)
                    val event = UsageEvents.Event()
                    var currentPackage: String? = null

                    while (events.hasNextEvent()) {
                        events.getNextEvent(event)
                        if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                            currentPackage = event.packageName
                        }
                    }

                    if (currentPackage != null && currentPackage != lastForegroundPackage) {
                        val prev = lastForegroundPackage
                        if (prev != null && prev != currentPackage && prev != packageName && prev != "com.android.settings" && currentPackage != packageName) {
                            repository.handleAppExited(prev)
                        }
                        lastForegroundPackage = currentPackage
                        checkAndEnforcePackage(currentPackage)
                    }
                    delay(400)
                } else {
                    delay(2000)
                }
            }
        }
    }

    private suspend fun checkAndEnforcePackage(packageName: String) {
        if (packageName.isBlank() || packageName == this.packageName || packageName == "com.android.settings") return
        val decision = repository.evaluatePackage(packageName)
        when (decision) {
            is LockDecision.RequirePin -> {
                overlayManager.showPinOverlay(packageName)
            }
            is LockDecision.StrictCooldown -> {
                overlayManager.showCooldownOverlay(packageName, decision.remainingSeconds)
            }
            is LockDecision.PinLockout -> {
                overlayManager.showLockoutOverlay(packageName, decision.remainingSeconds)
            }
            is LockDecision.Allowed -> {
                overlayManager.dismissIfShowing(packageName)
            }
            is LockDecision.Blocked,
            is LockDecision.RecoveryRequired,
            is LockDecision.DenyUnknown -> {
                // INV-319: FGS fallback must enforce terminal denials by dismissing overlays and routing Home
                overlayManager.dismissIfShowing(packageName)
                if (!isLauncherPackage(packageName)) {
                    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(homeIntent)
                }
            }
            else -> {}
        }
    }

    private fun isLauncherPackage(pkg: String): Boolean =
        LockKeeperAccessibilityService.isLauncherOrSystemUiPackage(pkg, packageManager)
}
