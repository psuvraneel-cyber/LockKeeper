package com.lockkeeper.app.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class LockKeeperDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        try {
            com.lockkeeper.app.domain.ProtectionRepository.getInstance(context).notifySecurityStateChanged()
        } catch (_: Exception) {}
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        // Shown by Android when someone attempts to deactivate Device Admin
        return "LockKeeper Device Admin protects this device against unauthorized deactivation or uninstallation."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        try {
            val repo = com.lockkeeper.app.domain.ProtectionRepository.getInstance(context)
            repo.decisionEngine.clearAllSessions()
            repo.notifySecurityStateChanged()
        } catch (_: Exception) {}
    }
}
