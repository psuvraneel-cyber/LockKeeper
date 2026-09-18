package com.lockkeeper.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.lockkeeper.app.domain.LockDecision
import com.lockkeeper.app.domain.ProtectionRepository
import com.lockkeeper.app.overlay.OverlayManager
import com.lockkeeper.app.receiver.LockKeeperDeviceAdminReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class LockKeeperAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var isConnected: Boolean = false
            private set

        fun isLauncherOrSystemUiPackage(pkg: String, pm: android.content.pm.PackageManager? = null): Boolean {
            val lower = pkg.lowercase()
            if (lower == "com.android.systemui" || lower == "com.miui.powerkeeper" || lower.contains(".systemui")) {
                return true
            }
            if (pm != null) {
                try {
                    val homeIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                        addCategory(android.content.Intent.CATEGORY_HOME)
                    }
                    val defaultLauncher = pm.resolveActivity(homeIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
                    if (defaultLauncher != null && defaultLauncher.equals(pkg, ignoreCase = true)) {
                        return true
                    }
                    val allLaunchers = pm.queryIntentActivities(homeIntent, 0).map { it.activityInfo.packageName.lowercase() }
                    if (allLaunchers.contains(lower)) {
                        return true
                    }
                } catch (_: Exception) {}
            }

            return lower == "com.miui.home" ||
                    lower == "com.sec.android.app.launcher" ||
                    lower == "com.google.android.apps.nexuslauncher" ||
                    lower == "com.android.launcher3" ||
                    lower.endsWith(".launcher") ||
                    lower.endsWith(".home")
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var repository: ProtectionRepository
    private lateinit var overlayManager: OverlayManager
    private lateinit var tamperEngine: com.lockkeeper.app.security.TamperDetectionEngine

    private var lastHandledPackage: String? = null
    private var lastEventTimestamp: Long = 0L
    private var evaluationJob: Job? = null

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            if (intent?.action == android.content.Intent.ACTION_SCREEN_OFF) {
                // INV-318: Screen off immediately purges all transient app sessions and self-lock
                repository.decisionEngine.clearAllSessions()
                repository.selfLockManager.invalidateSession()
                repository.recordAppBackgrounded()
                val activeSession = repository.tamperController.activeSession
                if (activeSession != null) {
                    repository.tamperController.endSession(
                        activeSession.sessionId,
                        com.lockkeeper.app.security.TamperSessionState.INTERRUPTED,
                        "Screen turned off"
                    )
                }
                overlayManager.dismissAdminOverlay()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = ProtectionRepository.getInstance(this)
        overlayManager = OverlayManager.getInstance(this)
        overlayManager.registerAccessibilityService(this)
        tamperEngine = com.lockkeeper.app.security.TamperDetectionEngine(packageName)
        val screenFilter = android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF)
        registerReceiver(screenReceiver, screenFilter)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isConnected = true
        overlayManager.registerAccessibilityService(this)
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        serviceInfo = info
        try {
            repository.notifySecurityStateChanged()
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        isConnected = false
        evaluationJob?.cancel()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
        val activeSession = repository.tamperController.activeSession
        if (activeSession != null) {
            repository.tamperController.endSession(
                activeSession.sessionId,
                com.lockkeeper.app.security.TamperSessionState.SERVICE_DESTROYED,
                "Service destroyed"
            )
        }
        overlayManager.dismissAll()
        overlayManager.unregisterAccessibilityService()
        repository.decisionEngine.clearAllSessions()
        try {
            repository.notifySecurityStateChanged()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onInterrupt() {
        overlayManager.dismissAll()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""
        val now = System.currentTimeMillis()
        android.util.Log.i("LockKeeperA11y", "EVT: type=$eventType pkg=$packageName class=$className")

        // Immunity for soft keyboards (IME) - typing password or searching must NEVER dismiss overlays or sessions
        if (overlayManager.isInputMethodPackage(packageName)) {
            return
        }

        // If the event is from LockKeeper and an overlay is showing or prompting, ignore to allow overlay interaction
        if (packageName == this.packageName) {
            if (overlayManager.isOverlayShowing() ||
                repository.tamperController.activeSession?.state == com.lockkeeper.app.security.TamperSessionState.PROMPTING
            ) {
                return
            }
        }

        // Check for Tamper / Uninstallation / Settings tampering events across Settings, PackageInstallers, and OEM Centers
        if (repository.shouldProtectSettings()) {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val adminComponent = ComponentName(this, LockKeeperDeviceAdminReceiver::class.java)
            val isAdminActive = dpm?.isAdminActive(adminComponent) == true

            val rootNode = rootInActiveWindow ?: run {
                var node = event.source
                while (node != null && node.parent != null) {
                    node = node.parent
                }
                node
            }
            val nodeFacade = rootNode?.let { AndroidNodeFacade(it) }
            android.util.Log.i("LockKeeperA11y", "evaluating: pkg=$packageName class=$className isAdminActive=$isAdminActive rootNodeNull=${nodeFacade == null}")

            val tamperEvent = tamperEngine.evaluate(
                packageName = packageName,
                className = className,
                rootNode = nodeFacade,
                isDeviceAdminActive = isAdminActive
            )

            if (tamperEvent != null && tamperEvent.confidence != com.lockkeeper.app.security.TamperConfidence.LOW) {
                android.util.Log.i("LockKeeperA11y", "Tamper event detected: type=${tamperEvent.type}, source=${tamperEvent.source}")
                handleTamperEvent(tamperEvent, packageName)
                return
            } else if (repository.tamperController.activeSession?.state == com.lockkeeper.app.security.TamperSessionState.PROMPTING &&
                isSystemManagementPackage(packageName)
            ) {
                // While an ADMIN session is PROMPTING inside a system management package,
                // do NOT allow transient node fluctuations to dismiss the gate or drop to evaluatePackage!
                val session = repository.tamperController.activeSession
                if (session != null) {
                    overlayManager.showAdminOverlay(targetPackage = packageName, session = session) { authenticated ->
                        if (!authenticated) {
                            performGlobalAction(GLOBAL_ACTION_HOME)
                        }
                    }
                }
                return
            }
        }

        // Debounce identical non-tamper package evaluations within 150ms
        if (packageName == lastHandledPackage && (now - lastEventTimestamp) < 150L) {
            return
        }
        lastEventTimestamp = now

        // Handle app switch / exit from previous app
        val prev = lastHandledPackage
        if (prev != null && prev != packageName) {
            if (prev == this.packageName) {
                repository.recordAppBackgrounded(now)
            } else if (!isSystemManagementPackage(prev) && packageName != this.packageName) {
                serviceScope.launch {
                    repository.handleAppExited(prev)
                }
            }
        }
        lastHandledPackage = packageName

        // Evaluate target package
        evaluationJob?.cancel()
        evaluationJob = serviceScope.launch {
            val decision = repository.evaluatePackage(packageName)
            mainHandler.post {
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
                        // Fail-closed: dismiss any lingering overlay and force navigation away from target app
                        overlayManager.dismissIfShowing(packageName)
                        // CRITICAL: Never dispatch GLOBAL_ACTION_HOME if target package is already launcher or system UI
                        if (!isLauncherOrSystemUiPackage(packageName)) {
                            performGlobalAction(GLOBAL_ACTION_HOME)
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun handleTamperEvent(tamperEvent: com.lockkeeper.app.security.TamperEvent, targetPackage: String) {
        if (!repository.shouldProtectSettings()) {
            overlayManager.dismissAdminOverlay()
            return
        }
        if (repository.tamperController.isGraceActive()) {
            android.util.Log.d("LockKeeperA11y", "Skipping tamper protection: admin grace active")
            overlayManager.dismissAdminOverlay()
            return
        }

        val session = repository.tamperController.startOrGetSession(tamperEvent)
        if (session != null) {
            android.util.Log.i("LockKeeperA11y", "Displaying admin overlay for tamper defense over $targetPackage (sessionId=${session.sessionId})")
            overlayManager.showAdminOverlay(targetPackage = targetPackage, session = session) { authenticated ->
                if (!authenticated) {
                    // Force navigation to home out of the tampering UI (fail-closed)
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            }
        }
    }

    private fun isLauncherOrSystemUiPackage(pkg: String): Boolean =
        Companion.isLauncherOrSystemUiPackage(pkg, packageManager)

    private fun isSystemManagementPackage(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return lower == "com.android.settings" ||
                lower.contains("packageinstaller") ||
                lower == "com.android.vending" ||
                lower == "com.miui.securitycenter" ||
                lower == "com.samsung.android.lool" ||
                lower == "com.coloros.safecenter" ||
                lower == "com.vivo.abe"
    }

    private class AndroidNodeFacade(private val node: AccessibilityNodeInfo) : com.lockkeeper.app.security.NodeFacade {
        override val text: CharSequence? get() = node.text
        override val contentDescription: CharSequence? get() = node.contentDescription
        override val viewIdResourceName: String? get() = node.viewIdResourceName
        override val className: CharSequence? get() = node.className
        override val childCount: Int get() = node.childCount
        override fun getChild(index: Int): com.lockkeeper.app.security.NodeFacade? {
            val child = node.getChild(index) ?: return null
            return AndroidNodeFacade(child)
        }
    }
}
