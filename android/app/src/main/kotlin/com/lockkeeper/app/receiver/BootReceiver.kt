package com.lockkeeper.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lockkeeper.app.domain.ProtectionRepository
import com.lockkeeper.app.service.LockKeeperForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            val repository = ProtectionRepository.getInstance(context)
            CoroutineScope(Dispatchers.IO).launch {
                if (repository.isOnboardingComplete()) {
                    LockKeeperForegroundService.startService(context)
                }
            }
        }
    }
}
