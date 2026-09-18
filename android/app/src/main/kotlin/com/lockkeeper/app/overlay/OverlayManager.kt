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
    private var activeSession: com.lockkeeper.app.security.TamperSession? = null

    fun isOverlayShowing(): Boolean = currentOverlayView != null

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

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
        if (currentOverlayView != null && currentGateType == GateType.ADMIN && currentPackageName == targetPackage) {
            return
        }

        activeSession = session
        mainHandler.post {
            removeCurrentOverlayInternal()
            lateinit var view: AdminOverlayView
            view = AdminOverlayView(
                context = context,
                onPasswordSubmitted = { password ->
                    CoroutineScope(Dispatchers.IO).launch {
                        val authResult = repository.tamperController.verifyAdminPassword(password)
                        mainHandler.post {
                            when (authResult) {
                                is com.lockkeeper.app.security.AdminAuthResult.Success -> {
                                    repository.decisionEngine.grantAdminGraceWindow()
                                    repository.tamperController.endSession(true)
                                    activeSession = null
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
                    repository.tamperController.endSession(false)
                    activeSession = null
                    removeCurrentOverlayInternal()
                    onDismissAction(false)
                }
            )

            // Check if already locked out on initial display
            CoroutineScope(Dispatchers.IO).launch {
                val lockoutSec = repository.tamperController.checkLockout()
                if (lockoutSec != null) {
                    mainHandler.post {
                        view.showLockout(lockoutSec)
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
        try {
            val params = createLayoutParams()
            windowManager.addView(view, params)
            currentOverlayView = view
            currentPackageName = packageName
            currentGateType = gateType
        } catch (e: Exception) {
            currentOverlayView = null
            currentPackageName = null
            currentGateType = null
            activeSession = null
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
                // If an admin overlay is showing:
                // Do not dismiss if the event is within the same target package (e.g. transient settings dialogs)
                if (packageName != null && packageName == currentPackageName) {
                    return@post
                }
                repository.tamperController.endSession(false)
                activeSession = null
                removeCurrentOverlayInternal()
            } else {
                // If the foreground package is Allowed, dismiss any non-admin overlay cleanly
                removeCurrentOverlayInternal()
            }
        }
    }

    @Synchronized
    fun dismissAdminOverlay() {
        mainHandler.post {
            if (currentGateType == GateType.ADMIN) {
                repository.tamperController.endSession(false)
                activeSession = null
                removeCurrentOverlayInternal()
            }
        }
    }

    @Synchronized
    fun dismissAll() {
        mainHandler.post {
            if (currentGateType == GateType.ADMIN) {
                repository.tamperController.endSession(false)
                activeSession = null
            }
            removeCurrentOverlayInternal()
        }
    }

    private fun removeCurrentOverlayInternal() {
        currentOverlayView?.let { view ->
            try {
                windowManager.removeViewImmediate(view)
            } catch (e: Exception) {
                try {
                    windowManager.removeView(view)
                } catch (ignored: Exception) {}
            }
        }
        currentOverlayView = null
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
