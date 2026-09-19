package com.lockkeeper.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.lockkeeper.app.domain.LockDecision
import com.lockkeeper.app.domain.ProtectionRepository
import com.lockkeeper.app.overlay.OverlayManager
import com.lockkeeper.app.receiver.LockKeeperDeviceAdminReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

class LockKeeperAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var isConnected: Boolean = false
            private set

        fun isSystemUiPackage(pkg: String): Boolean {
            val lower = pkg.lowercase()
            return lower == "com.android.systemui" ||
                    lower == "com.miui.powerkeeper" ||
                    lower.contains(".systemui")
        }

        fun isLauncherPackage(pkg: String, pm: PackageManager? = null): Boolean {
            val lower = pkg.lowercase()
            if (pm != null) {
                try {
                    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                    }
                    val defaultLauncher = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
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

        fun isSystemManagementPackage(pkg: String): Boolean {
            val lower = pkg.lowercase()
            return lower == "com.android.settings" ||
                    lower.contains("packageinstaller") ||
                    lower == "com.android.vending" ||
                    lower == "com.miui.securitycenter" ||
                    lower == "com.samsung.android.lool" ||
                    lower == "com.coloros.safecenter" ||
                    lower == "com.vivo.abe"
        }

        fun isLauncherOrSystemUiPackage(pkg: String, pm: PackageManager? = null): Boolean {
            return isSystemUiPackage(pkg) || isLauncherPackage(pkg, pm)
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
    private val evaluationSequence = AtomicLong(0)

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
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
        val screenFilter = IntentFilter(Intent.ACTION_SCREEN_OFF)
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
        // Accessibility interrupts (e.g. system UI dialogs, audio focus) must NEVER
        // blindly terminate unrelated active enforcement sessions or dismiss security overlays.
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

        // 1. EARLY SYSTEM UI FILTER: Filter com.android.systemui at the earliest practical point
        if (isSystemUiPackage(packageName)) {
            return
        }

        // 2. Immunity for soft keyboards (IME)
        if (overlayManager.isInputMethodPackage(packageName)) {
            return
        }

        // 3. Event Type Specificity:
        // Ordinary app-lock transitions are driven by TYPE_WINDOW_STATE_CHANGED.
        // Only evaluate TYPE_WINDOW_CONTENT_CHANGED for system management apps when settings protection is active.
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (!isSystemManagementPackage(packageName) || !repository.shouldProtectSettings()) {
                return
            }
        }

        // 4. LockKeeper Intermediate Event Guard:
        // If the event is from LockKeeper and an overlay is showing or prompting, ignore to preserve interaction.
        if (packageName == this.packageName) {
            if (overlayManager.isOverlayShowing() ||
                repository.tamperController.activeSession?.state == com.lockkeeper.app.security.TamperSessionState.PROMPTING
            ) {
                return
            }
        }

        // Check for Tamper / Uninstallation / Settings tampering events
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

        // Reworked App-Exit Detection:
        val prev = lastHandledPackage
        if (prev != null && prev != packageName) {
            if (prev == this.packageName) {
                repository.recordAppBackgrounded(now)
            } else if (!isSystemManagementPackage(prev) && !isLauncherPackage(prev)) {
                // User genuinely navigated away from protected app `prev`
                serviceScope.launch {
                    repository.handleAppExited(prev)
                }
            }
        }
        lastHandledPackage = packageName

        // Evaluate target package with generation/session validation
        val currentSeq = evaluationSequence.incrementAndGet()
        evaluationJob?.cancel()
        evaluationJob = serviceScope.launch {
            try {
                val decision = repository.evaluatePackage(packageName)
                if (currentSeq != evaluationSequence.get() || packageName != lastHandledPackage) {
                    return@launch
                }

                mainHandler.post {
                    if (currentSeq != evaluationSequence.get() || packageName != lastHandledPackage) {
                        return@post
                    }
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
                            if (!isLauncherPackage(packageName) && !isSystemUiPackage(packageName)) {
                                performGlobalAction(GLOBAL_ACTION_HOME)
                            }
                            mainHandler.postDelayed({
                                overlayManager.dismissIfShowing(packageName)
                            }, 250L)
                        }
                        else -> {}
                    }
                }
            } catch (_: CancellationException) {
                // Superseded by a newer evaluation job; do not post DenyUnknown
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
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            }
        }
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
