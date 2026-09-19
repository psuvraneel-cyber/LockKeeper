package com.lockkeeper.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import com.lockkeeper.app.domain.ProtectionRepository
import com.lockkeeper.app.service.LockKeeperAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

class OverlayManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: OverlayManager? = null

        fun getInstance(context: Context): OverlayManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: OverlayManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun isInputMethodPackage(pkg: String?): Boolean {
            if (pkg == null) return false
            val lower = pkg.lowercase()
            return lower.contains("inputmethod") ||
                    lower.contains(".latin") ||
                    lower.contains(".keyboard") ||
                    lower.contains("honeyboard") ||
                    lower.contains("swiftkey") ||
                    lower == "com.google.android.inputmethod.latin" ||
                    lower == "com.samsung.android.honeyboard"
        }
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val repository = ProtectionRepository.getInstance(context)

    @Volatile
    private var currentOverlayView: View? = null
    @Volatile
    private var currentPackageName: String? = null

    @Volatile
    private var activeTamperSessionId: String? = null
    @Volatile
    private var activeTamperSession: com.lockkeeper.app.security.TamperSession? = null

    @Volatile
    private var accessibilityServiceContext: Context? = null
    @Volatile
    private var currentWindowManager: WindowManager? = null

    private val generationCounter = AtomicLong(0)

    @Volatile
    private var currentOperation: EnforcementOperation? = null

    @Volatile
    private var pendingOperation: EnforcementOperation? = null

    @Volatile
    private var pendingNavigateHomeRunnable: Runnable? = null

    private fun cancelPendingNavigateHome() {
        pendingNavigateHomeRunnable?.let {
            mainHandler.removeCallbacks(it)
            pendingNavigateHomeRunnable = null
        }
    }

    @Volatile
    private var mainRunnerForTesting: ((Runnable) -> Unit)? = null

    fun registerAccessibilityService(service: Context) {
        accessibilityServiceContext = service
    }

    fun unregisterAccessibilityService() {
        accessibilityServiceContext = null
    }

    private fun getActiveWindowManager(): WindowManager {
        val a11y = accessibilityServiceContext
        return if (a11y != null) {
            try {
                a11y.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            } catch (_: Exception) {
                windowManager
            }
        } else {
            windowManager
        }
    }

    fun isOverlayShowing(): Boolean = currentOverlayView != null

    fun getActiveAdminSessionId(): String? = currentOperation?.tamperSession?.sessionId ?: activeTamperSessionId

    fun getCurrentOperation(): EnforcementOperation? = currentOperation

    fun getPendingOperation(): EnforcementOperation? = pendingOperation

    fun getCurrentGeneration(): Long = generationCounter.get()

    fun resetForTesting() {
        cancelPendingNavigateHome()
        currentOperation = null
        pendingOperation = null
        currentOverlayView = null
        currentPackageName = null
        currentGateType = null
        activeTamperSessionId = null
        activeTamperSession = null
        generationCounter.set(0)
    }

    fun setMainRunnerForTesting(runner: ((Runnable) -> Unit)?) {
        mainRunnerForTesting = runner
    }

    private fun runOnMainThread(action: Runnable) {
        val customRunner = mainRunnerForTesting
        if (customRunner != null) {
            customRunner(action)
        } else if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            mainHandler.post(action)
        }
    }

    fun isInputMethodPackage(pkg: String?): Boolean = Companion.isInputMethodPackage(pkg)

    fun isSystemUiPackage(pkg: String?): Boolean {
        if (pkg == null) return false
        return LockKeeperAccessibilityService.isSystemUiPackage(pkg)
    }

    fun isLauncherPackage(pkg: String?): Boolean {
        if (pkg == null) return false
        return LockKeeperAccessibilityService.isLauncherPackage(pkg, context.packageManager)
    }

    enum class GateType {
        PIN,
        COOLDOWN,
        LOCKOUT,
        ADMIN
    }

    @Volatile
    private var currentGateType: GateType? = null

    private fun createLayoutParams(forceApplicationOverlay: Boolean = false): WindowManager.LayoutParams {
        val a11yContext = accessibilityServiceContext
        val type = if (!forceApplicationOverlay && a11yContext != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or
                    WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        }
    }

    @Synchronized
    fun showPinOverlay(packageName: String) {
        cancelPendingNavigateHome()
        val activeOp = currentOperation
        if (activeOp != null && !activeOp.isTerminal &&
            activeOp.targetPackage == packageName &&
            activeOp.gateType == GateType.PIN
        ) {
            if (activeOp.lifecycleState == EnforcementLifecycleState.ATTACHED && currentOverlayView != null) {
                return
            }
            if (activeOp.lifecycleState == EnforcementLifecycleState.PENDING) {
                return
            }
        }

        val pending = pendingOperation
        if (pending != null && !pending.isTerminal &&
            pending.targetPackage == packageName &&
            pending.gateType == GateType.PIN
        ) {
            return
        }

        if (activeOp != null && !activeOp.isTerminal) {
            activeOp.lifecycleState = EnforcementLifecycleState.TERMINATED
            activeOp.terminalReason = "Superseded by PIN request for $packageName"
        }

        val gen = generationCounter.incrementAndGet()
        val operation = EnforcementOperation(
            gateType = GateType.PIN,
            targetPackage = packageName,
            generation = gen,
            lifecycleState = EnforcementLifecycleState.PENDING
        )
        currentOperation = operation
        pendingOperation = operation

        val expectedLength = repository.credentialStore.getPinLength()

        val attachRunnable = Runnable {
            synchronized(this@OverlayManager) {
                if (operation.isTerminal ||
                    currentOperation?.operationId != operation.operationId ||
                    currentOperation?.generation != operation.generation ||
                    currentOperation?.targetPackage != operation.targetPackage ||
                    currentOperation?.gateType != operation.gateType
                ) {
                    return@synchronized
                }

                if (currentOverlayView != null &&
                    currentPackageName == packageName &&
                    currentGateType == GateType.PIN
                ) {
                    operation.lifecycleState = EnforcementLifecycleState.ATTACHED
                    pendingOperation = null
                    return@synchronized
                }

                removeCurrentOverlayInternal()

                val view = PinOverlayView(
                    context = context,
                    targetPackage = packageName,
                    expectedPinLength = expectedLength,
                    onPinEntered = { enteredPin ->
                        handlePinSubmitted(operation, enteredPin)
                    },
                    onBackInterception = {
                        navigateHome()
                    }
                )
                attachOverlay(view, operation)
                pendingOperation = null
            }
        }

        runOnMainThread(attachRunnable)
    }

    @Synchronized
    fun showCooldownOverlay(packageName: String, remainingSeconds: Long) {
        showCountdownGate(packageName, remainingSeconds, isLockout = false)
    }

    @Synchronized
    fun showLockoutOverlay(packageName: String, remainingSeconds: Long) {
        showCountdownGate(packageName, remainingSeconds, isLockout = true)
    }

    private fun showCountdownGate(packageName: String, remainingSeconds: Long, isLockout: Boolean) {
        cancelPendingNavigateHome()
        val gateType = if (isLockout) GateType.LOCKOUT else GateType.COOLDOWN
        val activeOp = currentOperation
        if (activeOp != null && !activeOp.isTerminal &&
            activeOp.targetPackage == packageName &&
            activeOp.gateType == gateType
        ) {
            if (activeOp.lifecycleState == EnforcementLifecycleState.ATTACHED && currentOverlayView != null) {
                return
            }
            if (activeOp.lifecycleState == EnforcementLifecycleState.PENDING) {
                return
            }
        }

        val pending = pendingOperation
        if (pending != null && !pending.isTerminal &&
            pending.targetPackage == packageName &&
            pending.gateType == gateType
        ) {
            return
        }

        if (activeOp != null && !activeOp.isTerminal) {
            activeOp.lifecycleState = EnforcementLifecycleState.TERMINATED
            activeOp.terminalReason = "Superseded by $gateType for $packageName"
        }

        val gen = generationCounter.incrementAndGet()
        val operation = EnforcementOperation(
            gateType = gateType,
            targetPackage = packageName,
            generation = gen,
            lifecycleState = EnforcementLifecycleState.PENDING,
            remainingSeconds = remainingSeconds
        )
        currentOperation = operation
        pendingOperation = operation

        val attachRunnable = Runnable {
            synchronized(this@OverlayManager) {
                if (operation.isTerminal ||
                    currentOperation?.operationId != operation.operationId ||
                    currentOperation?.generation != operation.generation ||
                    currentOperation?.targetPackage != operation.targetPackage ||
                    currentOperation?.gateType != operation.gateType
                ) {
                    return@synchronized
                }

                if (currentOverlayView != null &&
                    currentPackageName == packageName &&
                    currentGateType == gateType
                ) {
                    operation.lifecycleState = EnforcementLifecycleState.ATTACHED
                    pendingOperation = null
                    return@synchronized
                }

                removeCurrentOverlayInternal()

                val view = CooldownOverlayView(
                    context = context,
                    targetPackage = packageName,
                    initialSeconds = remainingSeconds,
                    isLockout = isLockout,
                    onExitClicked = {
                        navigateHome()
                    }
                )
                attachOverlay(view, operation)
                pendingOperation = null
            }
        }

        runOnMainThread(attachRunnable)
    }

    @Synchronized
    fun showAdminOverlay(
        targetPackage: String = "com.android.settings",
        session: com.lockkeeper.app.security.TamperSession? = null,
        onDismissAction: (Boolean) -> Unit
    ) {
        cancelPendingNavigateHome()
        val targetSessionId = session?.sessionId
        val activeOp = currentOperation
        if (activeOp != null && !activeOp.isTerminal &&
            activeOp.gateType == GateType.ADMIN &&
            ((targetSessionId != null && activeOp.tamperSession?.sessionId == targetSessionId) ||
             (targetSessionId == null && activeOp.targetPackage == targetPackage))
        ) {
            if (activeOp.lifecycleState == EnforcementLifecycleState.ATTACHED && currentOverlayView != null) {
                return
            }
            if (activeOp.lifecycleState == EnforcementLifecycleState.PENDING) {
                return
            }
        }

        if (activeOp != null && !activeOp.isTerminal) {
            activeOp.lifecycleState = EnforcementLifecycleState.TERMINATED
            activeOp.terminalReason = "Superseded by ADMIN session $targetSessionId"
        }

        activeTamperSessionId = targetSessionId
        activeTamperSession = session

        val gen = generationCounter.incrementAndGet()
        val operation = EnforcementOperation(
            gateType = GateType.ADMIN,
            targetPackage = targetPackage,
            generation = gen,
            lifecycleState = EnforcementLifecycleState.PENDING,
            tamperSession = session
        )
        currentOperation = operation
        pendingOperation = operation

        val attachRunnable = Runnable {
            synchronized(this@OverlayManager) {
                if (operation.isTerminal ||
                    currentOperation?.operationId != operation.operationId ||
                    currentOperation?.generation != operation.generation ||
                    currentOperation?.targetPackage != operation.targetPackage ||
                    currentOperation?.gateType != operation.gateType
                ) {
                    return@synchronized
                }

                removeCurrentOverlayInternal()

                lateinit var view: AdminOverlayView
                view = AdminOverlayView(
                    context = context,
                    onPasswordSubmitted = { password ->
                        CoroutineScope(Dispatchers.IO).launch {
                            val authResult = repository.tamperController.verifyAdminPassword(password)
                            runOnMainThread {
                                synchronized(this@OverlayManager) {
                                    if (operation.isTerminal || currentOperation?.operationId != operation.operationId) {
                                        return@synchronized
                                    }
                                    when (authResult) {
                                        is com.lockkeeper.app.security.AdminAuthResult.Success -> {
                                            repository.decisionEngine.grantAdminGraceWindow()
                                            if (targetSessionId != null) {
                                                repository.tamperController.endSession(
                                                    sessionId = targetSessionId,
                                                    terminalState = com.lockkeeper.app.security.TamperSessionState.AUTHORIZED,
                                                    reason = "Admin password verified successfully"
                                                )
                                            } else {
                                                repository.tamperController.endSession(true)
                                            }
                                            activeTamperSessionId = null
                                            activeTamperSession = null
                                            operation.lifecycleState = EnforcementLifecycleState.TERMINATED
                                            operation.terminalReason = "Admin password verified"
                                            removeCurrentOverlayInternal()
                                            currentOperation = null
                                            pendingOperation = null
                                            onDismissAction(true)
                                        }
                                        is com.lockkeeper.app.security.AdminAuthResult.Failure -> {
                                            view.showError("Incorrect password. ${authResult.remainingAttempts} attempts remaining.")
                                        }
                                        is com.lockkeeper.app.security.AdminAuthResult.LockedOut -> {
                                            view.showLockout(authResult.remainingLockoutSeconds)
                                        }
                                    }
                                }
                            }
                        }
                    },
                    onCancelClicked = {
                        synchronized(this@OverlayManager) {
                            if (operation.isTerminal || currentOperation?.operationId != operation.operationId) {
                                return@synchronized
                            }
                            if (targetSessionId != null) {
                                repository.tamperController.endSession(
                                    sessionId = targetSessionId,
                                    terminalState = com.lockkeeper.app.security.TamperSessionState.CANCELLED,
                                    reason = "User cancelled admin prompt"
                                )
                            } else {
                                repository.tamperController.endSession(false)
                            }
                            activeTamperSessionId = null
                            activeTamperSession = null
                            operation.lifecycleState = EnforcementLifecycleState.TERMINATED
                            operation.terminalReason = "User cancelled admin prompt"
                            navigateHome()
                            onDismissAction(false)
                        }
                    }
                )

                CoroutineScope(Dispatchers.IO).launch {
                    val lockoutSec = repository.tamperController.checkLockout()
                    if (lockoutSec != null) {
                        runOnMainThread {
                            synchronized(this@OverlayManager) {
                                if (!operation.isTerminal && currentOperation?.operationId == operation.operationId) {
                                    view.showLockout(lockoutSec)
                                }
                            }
                        }
                    }
                }

                attachOverlay(view, operation)
                session?.isOverlayAttached = true
                pendingOperation = null
            }
        }

        runOnMainThread(attachRunnable)
    }

    private fun handlePinSubmitted(operation: EnforcementOperation, enteredPin: String) {
        CoroutineScope(Dispatchers.IO).launch {
            if (operation.isTerminal || currentOperation?.operationId != operation.operationId) {
                return@launch
            }
            val isValid = repository.credentialStore.verifyPin(enteredPin)
            if (isValid) {
                repository.handleSuccessfulPin(operation.targetPackage)
                runOnMainThread {
                    synchronized(this@OverlayManager) {
                        if (currentOperation?.operationId == operation.operationId) {
                            operation.lifecycleState = EnforcementLifecycleState.TERMINATED
                            operation.terminalReason = "PIN verified successfully"
                            removeCurrentOverlayInternal()
                            currentOperation = null
                            pendingOperation = null
                        }
                    }
                }
            } else {
                val (failedAttempts, lockoutUntil) = repository.handleFailedPin()
                runOnMainThread {
                    synchronized(this@OverlayManager) {
                        if (currentOperation?.operationId == operation.operationId) {
                            if (lockoutUntil != null) {
                                val remainingSec = (lockoutUntil - System.currentTimeMillis()) / 1000L
                                showLockoutOverlay(operation.targetPackage, maxOf(1L, remainingSec))
                            } else {
                                (currentOverlayView as? PinOverlayView)?.onWrongPin(failedAttempts)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun attachOverlay(view: View, operation: EnforcementOperation) {
        val wm = getActiveWindowManager()
        try {
            val params = createLayoutParams(forceApplicationOverlay = (wm == windowManager))
            wm.addView(view, params)
            currentWindowManager = wm
            currentOverlayView = view
            currentPackageName = operation.targetPackage
            currentGateType = operation.gateType
            operation.lifecycleState = EnforcementLifecycleState.ATTACHED
        } catch (e: Exception) {
            if (wm != windowManager) {
                try {
                    val fallbackParams = createLayoutParams(forceApplicationOverlay = true)
                    windowManager.addView(view, fallbackParams)
                    currentWindowManager = windowManager
                    currentOverlayView = view
                    currentPackageName = operation.targetPackage
                    currentGateType = operation.gateType
                    operation.lifecycleState = EnforcementLifecycleState.ATTACHED
                    return
                } catch (fallbackEx: Exception) {
                    android.util.Log.e("OverlayManager", "Fallback attach also failed", fallbackEx)
                }
            }
            currentOverlayView = null
            currentWindowManager = null
            currentPackageName = null
            currentGateType = null
            operation.lifecycleState = EnforcementLifecycleState.TERMINATED
            operation.terminalReason = "Attachment failed: ${e.message}"
            currentOperation = null
            pendingOperation = null
            val sid = operation.tamperSession?.sessionId ?: activeTamperSessionId
            if (sid != null) {
                repository.tamperController.endSession(
                    sessionId = sid,
                    terminalState = com.lockkeeper.app.security.TamperSessionState.TERMINAL_DENIAL,
                    reason = "Overlay attachment failed: ${e.message}"
                )
            } else if (operation.gateType == GateType.ADMIN) {
                repository.tamperController.endSession(false)
            }
            activeTamperSessionId = null
            activeTamperSession = null
            navigateHome()
        }
    }

    @Synchronized
    fun dismissIfShowing(packageName: String? = null) {
        if (packageName != null && isInputMethodPackage(packageName)) {
            return
        }
        if (packageName != null && isSystemUiPackage(packageName)) {
            return
        }

        val expectedOpId = currentOperation?.operationId
        val expectedGen = currentOperation?.generation

        cancelPendingNavigateHome()

        val dismissRunnable = Runnable {
            synchronized(this@OverlayManager) {
                val activeOp = currentOperation ?: return@synchronized
                if (activeOp.isTerminal) return@synchronized

                if (expectedOpId != null && activeOp.operationId != expectedOpId) {
                    return@synchronized
                }
                if (expectedGen != null && activeOp.generation != expectedGen) {
                    return@synchronized
                }

                if (activeOp.gateType == GateType.ADMIN) {
                    return@synchronized
                }

                val isLauncher = packageName != null && isLauncherPackage(packageName)
                val isMatchingTarget = packageName != null && packageName == activeOp.targetPackage

                if (packageName != null && !isMatchingTarget && !isLauncher) {
                    return@synchronized
                }

                activeOp.lifecycleState = EnforcementLifecycleState.DISMISSED
                activeOp.terminalReason = "Dismissed for package: $packageName (isLauncher=$isLauncher)"
                currentOperation = null
                pendingOperation = null
                removeCurrentOverlayInternal()
            }
        }

        runOnMainThread(dismissRunnable)
    }

    @Synchronized
    fun dismissAdminOverlay(sessionId: String? = null) {
        cancelPendingNavigateHome()
        val dismissRunnable = Runnable {
            synchronized(this@OverlayManager) {
                val activeOp = currentOperation
                if (activeOp != null && activeOp.gateType == GateType.ADMIN) {
                    val sid = sessionId ?: activeOp.tamperSession?.sessionId ?: activeTamperSessionId
                    if (sid != null) {
                        repository.tamperController.endSession(
                            sessionId = sid,
                            terminalState = com.lockkeeper.app.security.TamperSessionState.CANCELLED,
                            reason = "Explicit dismissAdminOverlay invoked"
                        )
                    } else {
                        repository.tamperController.endSession(false)
                    }
                    activeOp.lifecycleState = EnforcementLifecycleState.DISMISSED
                    activeOp.terminalReason = "Admin overlay dismissed: sid=$sid"
                    currentOperation = null
                    pendingOperation = null
                    activeTamperSessionId = null
                    activeTamperSession = null
                    removeCurrentOverlayInternal()
                }
            }
        }
        runOnMainThread(dismissRunnable)
    }

    @Synchronized
    fun dismissAll() {
        cancelPendingNavigateHome()
        val dismissRunnable = Runnable {
            synchronized(this@OverlayManager) {
                val activeOp = currentOperation
                if (activeOp != null && !activeOp.isTerminal) {
                    if (activeOp.gateType == GateType.ADMIN) {
                        val sid = activeOp.tamperSession?.sessionId ?: activeTamperSessionId
                        if (sid != null) {
                            repository.tamperController.endSession(
                                sessionId = sid,
                                terminalState = com.lockkeeper.app.security.TamperSessionState.INTERRUPTED,
                                reason = "dismissAll invoked"
                            )
                        } else {
                            repository.tamperController.endSession(false)
                        }
                    }
                    activeOp.lifecycleState = EnforcementLifecycleState.TERMINATED
                    activeOp.terminalReason = "dismissAll invoked"
                }
                currentOperation = null
                pendingOperation = null
                activeTamperSessionId = null
                activeTamperSession = null
                removeCurrentOverlayInternal()
            }
        }
        runOnMainThread(dismissRunnable)
    }

    private fun removeCurrentOverlayInternal() {
        currentOverlayView?.let { view ->
            val wm = currentWindowManager ?: getActiveWindowManager()
            try {
                wm.removeViewImmediate(view)
            } catch (e: Exception) {
                try {
                    wm.removeView(view)
                } catch (ignored: Exception) {
                    try {
                        windowManager.removeView(view)
                    } catch (_: Exception) {}
                }
            }
        }
        currentOverlayView = null
        currentWindowManager = null
        currentPackageName = null
        currentGateType = null
    }

    @Synchronized
    fun navigateHome() {
        cancelPendingNavigateHome()

        val homeIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_HOME)
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(homeIntent)
        } catch (e: Exception) {
            android.util.Log.e("OverlayManager", "Failed to start home activity", e)
        }

        val opToDismiss = currentOperation
        if (opToDismiss != null && !opToDismiss.isTerminal) {
            opToDismiss.lifecycleState = EnforcementLifecycleState.DISMISSING
            opToDismiss.terminalReason = "Navigating home"
        }

        val delayedDismiss = Runnable {
            synchronized(this@OverlayManager) {
                pendingNavigateHomeRunnable = null
                if (currentOperation?.operationId == opToDismiss?.operationId) {
                    opToDismiss?.lifecycleState = EnforcementLifecycleState.DISMISSED
                    opToDismiss?.terminalReason = "navigateHome transition completed"
                    currentOperation = null
                    pendingOperation = null
                    removeCurrentOverlayInternal()
                }
            }
        }
        pendingNavigateHomeRunnable = delayedDismiss
        val customRunner = mainRunnerForTesting
        if (customRunner != null) {
            customRunner(delayedDismiss)
        } else {
            mainHandler.postDelayed(delayedDismiss, 250L)
        }
    }
}
