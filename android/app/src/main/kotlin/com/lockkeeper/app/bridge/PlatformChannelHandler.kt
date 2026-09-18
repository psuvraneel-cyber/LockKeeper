package com.lockkeeper.app.bridge

import android.app.Activity
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.lockkeeper.app.domain.ProtectionRepository
import com.lockkeeper.app.receiver.LockKeeperDeviceAdminReceiver
import com.lockkeeper.app.service.LockKeeperForegroundService
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Base64

class PlatformChannelHandler(
    private val context: Context,
    private val repository: ProtectionRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) : MethodChannel.MethodCallHandler, EventChannel.StreamHandler {

    @Volatile
    var currentActivity: Activity? = null

    companion object {
        const val METHOD_CHANNEL_NAME = "com.lockkeeper.app/channel"
        const val EVENT_CHANNEL_NAME = "com.lockkeeper.app/events"

        @Volatile
        var eventSink: EventChannel.EventSink? = null

        fun notifyProtectionStateChanged(data: Map<String, Any?>) {
            eventSink?.success(mapOf("type" to "protectionStateChanged", "data" to data))
        }

        fun notifyPermissionRevoked(permission: String) {
            eventSink?.success(mapOf("type" to "permissionRevoked", "permission" to permission))
        }
    }

    private var methodChannel: MethodChannel? = null
    private var eventChannel: EventChannel? = null

    fun register(messenger: BinaryMessenger) {
        methodChannel = MethodChannel(messenger, METHOD_CHANNEL_NAME).also {
            it.setMethodCallHandler(this)
        }
        eventChannel = EventChannel(messenger, EVENT_CHANNEL_NAME).also {
            it.setStreamHandler(this)
        }
    }

    fun unregister() {
        methodChannel?.setMethodCallHandler(null)
        eventChannel?.setStreamHandler(null)
        methodChannel = null
        eventChannel = null
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        eventSink = events
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        scope.launch {
            try {
                when (call.method) {
                    "checkPermissionStatus" -> {
                        val type = call.argument<String>("type") ?: ""
                        val isGranted = checkPermission(type)
                        result.success(isGranted)
                    }
                    "requestPermission" -> {
                        val type = call.argument<String>("type") ?: ""
                        requestPermission(type)
                        result.success(true)
                    }
                    "setPin" -> {
                        val pin = call.argument<String>("pin") ?: ""
                        val success = repository.credentialStore.setPin(pin)
                        result.success(success)
                    }
                    "verifyPin" -> {
                        val pin = call.argument<String>("pin") ?: ""
                        val valid = repository.credentialStore.verifyPin(pin)
                        result.success(valid)
                    }
                    "hasPin" -> {
                        result.success(repository.credentialStore.hasPin())
                    }
                    "setAdminPassword" -> {
                        val password = call.argument<String>("password") ?: ""
                        val success = repository.credentialStore.setAdminPassword(password)
                        result.success(success)
                    }
                    "verifyAdminPassword" -> {
                        val password = call.argument<String>("password") ?: ""
                        val authResult = repository.tamperController.verifyAdminPassword(password)
                        val map = when (authResult) {
                            is com.lockkeeper.app.security.AdminAuthResult.Success -> mapOf(
                                "success" to true,
                                "status" to "SUCCESS",
                                "graceWindowMs" to authResult.graceWindowMs
                            )
                            is com.lockkeeper.app.security.AdminAuthResult.Failure -> mapOf(
                                "success" to false,
                                "status" to "DENIED",
                                "remainingAttempts" to authResult.remainingAttempts,
                                "failedAttempts" to authResult.failedAttempts
                            )
                            is com.lockkeeper.app.security.AdminAuthResult.LockedOut -> mapOf(
                                "success" to false,
                                "status" to "LOCKED_OUT",
                                "remainingLockoutSeconds" to authResult.remainingLockoutSeconds
                            )
                        }
                        result.success(map)
                    }
                    "hasAdminPassword" -> {
                        result.success(repository.credentialStore.hasAdminPassword())
                    }
                    "getLockedApps" -> {
                        val apps = repository.getAllLockedApps().map { app ->
                            mapOf(
                                "packageName" to app.packageName,
                                "isLocked" to app.isLocked,
                                "cooldownMinutes" to app.cooldownMinutes,
                                "strictLock" to app.strictLock,
                                "lockedUntilTimestamp" to app.lockedUntilTimestamp,
                                "updatedAt" to app.updatedAt
                            )
                        }
                        result.success(apps)
                    }
                    "setLockedApp" -> {
                        val packageName = call.argument<String>("packageName") ?: ""
                        val isLocked = call.argument<Boolean>("isLocked") ?: true
                        val cooldownMinutes = call.argument<Int>("cooldownMinutes") ?: 15
                        val strictLock = call.argument<Boolean>("strictLock") ?: false
                        repository.setLockedApp(packageName, isLocked, cooldownMinutes, strictLock)
                        result.success(true)
                    }
                    "removeLockedApp" -> {
                        val packageName = call.argument<String>("packageName") ?: ""
                        repository.removeLockedApp(packageName)
                        result.success(true)
                    }
                    "getInstalledApps" -> {
                        val installedApps = getLaunchableApps()
                        result.success(installedApps)
                    }
                    "startProtectionService" -> {
                        LockKeeperForegroundService.startService(context)
                        result.success(true)
                    }
                    "getProtectionStatus" -> {
                        val status = repository.getAuthoritativeSecurityStatus()
                        result.success(status)
                    }
                    "getSecurityHealth" -> {
                        val status = repository.getAuthoritativeSecurityStatus()
                        result.success(status)
                    }
                    "isOnboardingComplete" -> {
                        result.success(repository.isOnboardingComplete())
                    }
                    "isSecurityProvisioned" -> {
                        result.success(repository.isSecurityProvisioned())
                    }
                    "completeInitialProvisioning" -> {
                        result.success(repository.completeInitialProvisioning())
                    }
                    "setOnboardingComplete" -> {
                        val complete = call.argument<Boolean>("complete") ?: false
                        repository.setOnboardingComplete(complete)
                        result.success(true)
                    }
                    "resolveRecovery" -> {
                        val pin = call.argument<String>("pin") ?: ""
                        val adminPassword = call.argument<String>("adminPassword") ?: ""
                        val success = repository.resolveRecovery(pin, adminPassword)
                        result.success(success)
                    }
                    "isSelfLockEnabled" -> {
                        result.success(repository.isSelfLockEnabled())
                    }
                    "setSelfLockEnabled" -> {
                        val enabled = call.argument<Boolean>("enabled") ?: false
                        repository.setSelfLockEnabled(enabled)
                        result.success(true)
                    }
                    "getSelfLockTimeout" -> {
                        result.success(repository.getSelfLockTimeoutSeconds())
                    }
                    "setSelfLockTimeout" -> {
                        val seconds = call.argument<Int>("timeoutSeconds") ?: 0
                        repository.setSelfLockTimeoutSeconds(seconds)
                        result.success(true)
                    }
                    "checkSelfLockRequired" -> {
                        result.success(repository.isSelfLockRequired())
                    }
                    "verifySelfLockPin" -> {
                        val pin = call.argument<String>("pin") ?: ""
                        val verifyResult = repository.verifySelfLockPin(pin)
                        result.success(verifyResult)
                    }
                    "reportAppBackgrounded" -> {
                        repository.recordAppBackgrounded(System.currentTimeMillis())
                        result.success(true)
                    }
                    "reportAppResumed" -> {
                        val required = repository.checkAppForegrounded(System.currentTimeMillis())
                        result.success(required)
                    }
                    "invalidateSelfLockSession" -> {
                        repository.invalidateSelfLockSession()
                        result.success(true)
                    }
                    else -> result.notImplemented()
                }
            } catch (e: Exception) {
                result.error("ERROR", e.message, null)
            }
        }
    }

    private fun checkPermission(type: String): Boolean {
        return when (type) {
            "overlay" -> Settings.canDrawOverlays(context)
            "usage" -> {
                val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appOps.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(),
                        context.packageName
                    )
                } else {
                    @Suppress("DEPRECATION")
                    appOps.checkOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(),
                        context.packageName
                    )
                }
                mode == AppOpsManager.MODE_ALLOWED
            }
            "accessibility" -> {
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
                enabledServices.contains(context.packageName)
            }
            "accessibilityConnected" -> {
                com.lockkeeper.app.service.LockKeeperAccessibilityService.isConnected
            }
            "accessibilityOperational" -> {
                val isServiceConnected = com.lockkeeper.app.service.LockKeeperAccessibilityService.isConnected
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
                isServiceConnected && enabledServices.contains(context.packageName)
            }
            "deviceAdmin" -> {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val adminComponent = ComponentName(context, LockKeeperDeviceAdminReceiver::class.java)
                dpm.isAdminActive(adminComponent)
            }
            "battery" -> {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.isIgnoringBatteryOptimizations(context.packageName)
            }
            else -> false
        }
    }

    private fun requestPermission(type: String) {
        val intent = when (type) {
            "overlay" -> Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            "usage" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "deviceAdmin" -> {
                val adminComponent = ComponentName(context, LockKeeperDeviceAdminReceiver::class.java)
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "LockKeeper uses Device Admin to prevent accidental uninstall without admin password."
                    )
                }
            }
            "battery" -> Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            )
            else -> null
        }

        intent?.let {
            if (type != "deviceAdmin") {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val act = currentActivity
            try {
                if (act != null && !act.isFinishing && !act.isDestroyed) {
                    act.startActivity(it)
                } else {
                    if (type == "deviceAdmin") {
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(it)
                }
            } catch (e: Exception) {
                try {
                    val fallbackIntent = Intent(Settings.ACTION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    if (act != null && !act.isFinishing && !act.isDestroyed) {
                        act.startActivity(fallbackIntent)
                    } else {
                        context.startActivity(fallbackIntent)
                    }
                } catch (ignored: Exception) {}
            }
        }
    }

    private suspend fun getLaunchableApps(): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(mainIntent, 0)
        }

        resolveInfos
            .filter { it.activityInfo.packageName != context.packageName }
            .map { resolveInfo ->
                val packageName = resolveInfo.activityInfo.packageName
                val label = resolveInfo.loadLabel(pm).toString()
                val isSystem = (resolveInfo.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                val iconBase64 = try {
                    val drawable = resolveInfo.loadIcon(pm)
                    val bitmap = drawableToBitmap(drawable)
                    val stream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.PNG, 85, stream)
                    Base64.getEncoder().encodeToString(stream.toByteArray())
                } catch (e: Exception) {
                    null
                }

                mapOf(
                    "packageName" to packageName,
                    "name" to label,
                    "isSystem" to isSystem,
                    "iconBase64" to iconBase64
                )
            }
            .sortedBy { (it["name"] as? String)?.lowercase() ?: "" }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return Bitmap.createScaledBitmap(drawable.bitmap, 96, 96, true)
        }
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}
