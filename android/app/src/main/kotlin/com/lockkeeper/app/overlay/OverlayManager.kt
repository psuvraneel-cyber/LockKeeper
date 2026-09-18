package com.lockkeeper.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import com.lockkeeper.app.domain.LockDecision
import com.lockkeeper.app.domain.ProtectionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class OverlayManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: OverlayManager? = null

        fun getInstance(context: Context): OverlayManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: OverlayManager(context.applicationContext).also { INSTANCE = it }
            }
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

    fun getActiveAdminSessionId(): String? = activeTamperSessionId

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
            // TYPE_ACCESSIBILITY_OVERLAY is an official system overlay placed above all other windows.
            // Crucially, it is EXEMPT from HIDE_NON_SYSTEM_OVERLAY_WINDOWS which Android Settings uses to hide normal alert windows!
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
        if (currentOverlayView != null && currentPackageName == packageName && currentGateType == GateType.PIN) {
            return
        }

        val expectedLength = repository.credentialStore.getPinLength()
        mainHandler.post {
            removeCurrentOverlayInternal()
            val view = PinOverlayView(
                context = context,
                targetPackage = packageName,
                expectedPinLength = expectedLength,
                onPinEntered = { enteredPin ->
                    handlePinSubmitted(packageName, enteredPin)
                },
                onBackInterception = {
                    navigateHome()
                }
            )
            attachOverlay(view, packageName, GateType.PIN)
        }
    }

    @Synchronized
    fun showCooldownOverlay(packageName: String, remainingSeconds: Long) {
        if (currentOverlayView != null && currentPackageName == packageName && currentGateType == GateType.COOLDOWN) {
            return
        }

        mainHandler.post {
            removeCurrentOverlayInternal()
            val view = CooldownOverlayView(
                context = context,
                targetPackage = packageName,
                initialSeconds = remainingSeconds,
                isLockout = false,
                onExitClicked = {
                    navigateHome()
                }
            )
            attachOverlay(view, packageName, GateType.COOLDOWN)
        }
    }

    @Synchronized
    fun showLockoutOverlay(packageName: String, remainingSeconds: Long) {
        if (currentOverlayView != null && currentPackageName == packageName && currentGateType == GateType.LOCKOUT) {
            return
        }

        mainHandler.post {
            removeCurrentOverlayInternal()
            val view = CooldownOverlayView(
                context = context,
                targetPackage = packageName,
                initialSeconds = remainingSeconds,
                isLockout = true,
                onExitClicked = {
                    navigateHome()
                }
            )
            attachOverlay(view, packageName, GateType.LOCKOUT)
        }
    }

    @Synchronized
    fun showAdminOverlay(
        targetPackage: String = "com.android.settings",
        session: com.lockkeeper.app.security.TamperSession? = null,
        onDismissAction: (Boolean) -> Unit
    ) {
        val targetSessionId = session?.sessionId
        // IDEMPOTENCY CHECK:
        // If an Admin overlay is already showing for this exact active session, DO NOTHING.
        // Do NOT tear down and recreate the view. This eliminates visual flicker and window races!
        if (currentOverlayView != null &&
            currentGateType == GateType.ADMIN &&
            activeTamperSessionId != null &&
            activeTamperSessionId == targetSessionId
        ) {
            return
        }

        activeTamperSessionId = targetSessionId
        activeTamperSession = session
        val capturedSessionId = targetSessionId

        mainHandler.post {
            // Check if session was superseded or invalidated while post was queued
            if (capturedSessionId != null && activeTamperSessionId != capturedSessionId) {
                return@post
            }

            removeCurrentOverlayInternal()
            lateinit var view: AdminOverlayView
            view = AdminOverlayView(
                context = context,
                onPasswordSubmitted = { password ->
                    CoroutineScope(Dispatchers.IO).launch {
                        val authResult = repository.tamperController.verifyAdminPassword(password)
                        mainHandler.post {
                            // STALE CALLBACK GUARD: Confirm captured session is still active
                            if (capturedSessionId != null && activeTamperSessionId != capturedSessionId) {
                                return@post
                            }
                            when (authResult) {
                                is com.lockkeeper.app.security.AdminAuthResult.Success -> {
                                    repository.decisionEngine.grantAdminGraceWindow()
                                    if (capturedSessionId != null) {
                                        repository.tamperController.endSession(
                                            sessionId = capturedSessionId,
                                            terminalState = com.lockkeeper.app.security.TamperSessionState.AUTHORIZED,
                                            reason = "Admin password verified successfully"
                                        )
                                    } else {
                                        repository.tamperController.endSession(true)
                                    }
                                    activeTamperSessionId = null
                                    activeTamperSession = null
                                    removeCurrentOverlayInternal()
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
                },
                onCancelClicked = {
                    // STALE CALLBACK GUARD:
                    if (capturedSessionId == null || activeTamperSessionId == capturedSessionId) {
                        if (capturedSessionId != null) {
                            repository.tamperController.endSession(
                                sessionId = capturedSessionId,
                                terminalState = com.lockkeeper.app.security.TamperSessionState.CANCELLED,
                                reason = "User cancelled admin prompt"
                            )
                        } else {
                            repository.tamperController.endSession(false)
                        }
                        activeTamperSessionId = null
                        activeTamperSession = null
                        removeCurrentOverlayInternal()
                        navigateHome()
                        onDismissAction(false)
                    }
                }
            )

            // Check if already locked out on initial display
            CoroutineScope(Dispatchers.IO).launch {
                val lockoutSec = repository.tamperController.checkLockout()
                if (lockoutSec != null) {
                    mainHandler.post {
                        if (capturedSessionId == null || activeTamperSessionId == capturedSessionId) {
                            view.showLockout(lockoutSec)
                        }
                    }
                }
            }

            attachOverlay(view, targetPackage, GateType.ADMIN)
            session?.isOverlayAttached = true
        }
    }

    private fun handlePinSubmitted(packageName: String, enteredPin: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val isValid = repository.credentialStore.verifyPin(enteredPin)
            if (isValid) {
                repository.handleSuccessfulPin(packageName)
                mainHandler.post {
                    removeCurrentOverlayInternal()
                }
            } else {
                val (failedAttempts, lockoutUntil) = repository.handleFailedPin()
                mainHandler.post {
                    if (lockoutUntil != null) {
                        val remainingSec = (lockoutUntil - System.currentTimeMillis()) / 1000L
                        showLockoutOverlay(packageName, maxOf(1L, remainingSec))
                    } else {
                        (currentOverlayView as? PinOverlayView)?.onWrongPin(failedAttempts)
                    }
                }
            }
        }
    }

    private fun attachOverlay(view: View, packageName: String, gateType: GateType) {
        val wm = getActiveWindowManager()
        try {
            val params = createLayoutParams(forceApplicationOverlay = (wm == windowManager))
            wm.addView(view, params)
            currentWindowManager = wm
            currentOverlayView = view
            currentPackageName = packageName
            currentGateType = gateType
        } catch (e: Exception) {
            // If adding via accessibility WindowManager fails, attempt fallback via application WindowManager
            if (wm != windowManager) {
                try {
                    val fallbackParams = createLayoutParams(forceApplicationOverlay = true)
                    windowManager.addView(view, fallbackParams)
                    currentWindowManager = windowManager
                    currentOverlayView = view
                    currentPackageName = packageName
                    currentGateType = gateType
                    return
                } catch (fallbackEx: Exception) {
                    android.util.Log.e("OverlayManager", "Fallback attach also failed", fallbackEx)
                }
            }
            currentOverlayView = null
            currentWindowManager = null
            currentPackageName = null
            currentGateType = null
            val sid = activeTamperSessionId
            if (sid != null) {
                repository.tamperController.endSession(
                    sessionId = sid,
                    terminalState = com.lockkeeper.app.security.TamperSessionState.TERMINAL_DENIAL,
                    reason = "Overlay attachment failed: ${e.message}"
                )
            } else if (gateType == GateType.ADMIN) {
                repository.tamperController.endSession(false)
            }
            activeTamperSessionId = null
            activeTamperSession = null
            // INV-317: Fail-closed fallback: If overlay fails to attach, target app must not remain usable!
            navigateHome()
        }
    }

    @Synchronized
    fun dismissIfShowing(packageName: String? = null) {
        // Never dismiss any overlay due to an Input Method (keyboard) window event!
        if (packageName != null && isInputMethodPackage(packageName)) {
            return
        }

        mainHandler.post {
            if (currentGateType == GateType.ADMIN) {
                // CRITICAL SECURITY GUARANTEE:
                // An active ADMIN tamper session MUST NOT be dismissed by generic package transitions or observations!
                // OBSERVATION != TERMINATION.
                return@post
            } else {
                // If the foreground package is Allowed, dismiss any non-admin overlay cleanly
                removeCurrentOverlayInternal()
            }
        }
    }

    @Synchronized
    fun dismissAdminOverlay(sessionId: String? = null) {
        mainHandler.post {
            if (currentGateType == GateType.ADMIN) {
                val sid = sessionId ?: activeTamperSessionId
                if (sid != null) {
                    repository.tamperController.endSession(
                        sessionId = sid,
                        terminalState = com.lockkeeper.app.security.TamperSessionState.CANCELLED,
                        reason = "Explicit dismissAdminOverlay invoked"
                    )
                } else {
                    repository.tamperController.endSession(false)
                }
                activeTamperSessionId = null
                activeTamperSession = null
                removeCurrentOverlayInternal()
            }
        }
    }

    @Synchronized
    fun dismissAll() {
        mainHandler.post {
            if (currentGateType == GateType.ADMIN) {
                val sid = activeTamperSessionId
                if (sid != null) {
                    repository.tamperController.endSession(
                        sessionId = sid,
                        terminalState = com.lockkeeper.app.security.TamperSessionState.INTERRUPTED,
                        reason = "dismissAll invoked"
                    )
                } else {
                    repository.tamperController.endSession(false)
                }
                activeTamperSessionId = null
                activeTamperSession = null
            }
            removeCurrentOverlayInternal()
        }
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

    private fun navigateHome() {
        removeCurrentOverlayInternal()
        val homeIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_HOME)
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(homeIntent)
    }
}
