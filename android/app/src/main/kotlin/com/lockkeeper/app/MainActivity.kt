package com.lockkeeper.app
 
import android.os.Bundle
import android.view.WindowManager
import com.lockkeeper.app.bridge.PlatformChannelHandler
import com.lockkeeper.app.domain.ProtectionRepository
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {

    private var channelHandler: PlatformChannelHandler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateSecureFlag()
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val repository = ProtectionRepository.getInstance(applicationContext)
        channelHandler = PlatformChannelHandler(applicationContext, repository).apply {
            currentActivity = this@MainActivity
        }
        channelHandler?.register(flutterEngine.dartExecutor.binaryMessenger)
    }

    override fun onResume() {
        super.onResume()
        channelHandler?.currentActivity = this
        updateSecureFlag()
        reconcileProtectionService()
        ProtectionRepository.getInstance(applicationContext).notifySecurityStateChanged()
    }

    private fun reconcileProtectionService() {
        val repo = ProtectionRepository.getInstance(applicationContext)
        if (repo.isOnboardingCompleteSync() && !com.lockkeeper.app.service.LockKeeperForegroundService.isRunning) {
            try {
                com.lockkeeper.app.service.LockKeeperForegroundService.startService(applicationContext)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to reconcile foreground protection service", e)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        channelHandler?.currentActivity = null
        ProtectionRepository.getInstance(applicationContext).recordAppBackgrounded()
    }

    private fun updateSecureFlag() {
        val repo = ProtectionRepository.getInstance(applicationContext)
        if (repo.isOnboardingCompleteSync()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        channelHandler?.currentActivity = null
        channelHandler?.unregister()
        channelHandler = null
        super.cleanUpFlutterEngine(flutterEngine)
    }
}
