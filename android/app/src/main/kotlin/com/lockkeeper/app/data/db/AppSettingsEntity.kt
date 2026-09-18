package com.lockkeeper.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val failedPinAttempts: Int = 0,
    val pinLockoutUntil: Long? = null,
    val failedAdminAttempts: Int = 0,
    val adminLockoutUntil: Long? = null,
    val onboardingComplete: Boolean = false,
    val selfLockEnabled: Boolean = false,
    val selfLockTimeoutSeconds: Int = 0,
    val recoveryRequired: Boolean = false,
    val securityProvisioned: Boolean = onboardingComplete,
    val schemaVersion: Int = 5,
    val updatedAt: Long = System.currentTimeMillis()
)
