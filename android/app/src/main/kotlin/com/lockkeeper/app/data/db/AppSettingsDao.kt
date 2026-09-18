package com.lockkeeper.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface AppSettingsDao {
    @Query("SELECT * FROM app_settings WHERE id = 1 LIMIT 1")
    suspend fun getSettings(): AppSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertInitialSettings(settings: AppSettingsEntity = AppSettingsEntity(id = 1)): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: AppSettingsEntity)

    @Transaction
    suspend fun getOrInitializeSettings(): AppSettingsEntity {
        val existing = getSettings()
        if (existing != null) return existing
        insertInitialSettings(AppSettingsEntity(id = 1))
        return getSettings() ?: AppSettingsEntity(id = 1)
    }

    @Query("UPDATE app_settings SET failedPinAttempts = :attempts, pinLockoutUntil = :lockoutUntil, updatedAt = :updatedAt WHERE id = 1")
    suspend fun updatePinLockout(attempts: Int, lockoutUntil: Long?, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE app_settings SET failedPinAttempts = 0, pinLockoutUntil = NULL, updatedAt = :updatedAt WHERE id = 1")
    suspend fun resetPinFailures(updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE app_settings SET failedAdminAttempts = :attempts, adminLockoutUntil = :lockoutUntil, updatedAt = :updatedAt WHERE id = 1")
    suspend fun updateAdminLockout(attempts: Int, lockoutUntil: Long?, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE app_settings SET failedAdminAttempts = 0, adminLockoutUntil = NULL, updatedAt = :updatedAt WHERE id = 1")
    suspend fun resetAdminFailures(updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE app_settings SET onboardingComplete = :complete, updatedAt = :updatedAt WHERE id = 1")
    suspend fun setOnboardingComplete(complete: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE app_settings SET selfLockEnabled = :enabled, updatedAt = :updatedAt WHERE id = 1")
    suspend fun setSelfLockEnabled(enabled: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE app_settings SET selfLockTimeoutSeconds = :timeoutSeconds, updatedAt = :updatedAt WHERE id = 1")
    suspend fun setSelfLockTimeout(timeoutSeconds: Int, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE app_settings SET selfLockEnabled = :enabled, selfLockTimeoutSeconds = :timeoutSeconds, updatedAt = :updatedAt WHERE id = 1")
    suspend fun updateSelfLock(enabled: Boolean, timeoutSeconds: Int, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE app_settings SET recoveryRequired = :required, updatedAt = :updatedAt WHERE id = 1")
    suspend fun setRecoveryRequired(required: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE app_settings SET securityProvisioned = :provisioned, updatedAt = :updatedAt WHERE id = 1")
    suspend fun setSecurityProvisioned(provisioned: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE app_settings SET securityProvisioned = :provisioned, onboardingComplete = :complete, updatedAt = :updatedAt WHERE id = 1")
    suspend fun setProvisionedAndOnboardingComplete(provisioned: Boolean, complete: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Transaction
    suspend fun recordAdminFailure(maxAttempts: Int, lockoutDurationMs: Long, now: Long): Pair<Int, Long?> {
        val settings = getOrInitializeSettings()
        val newAttempts = settings.failedAdminAttempts + 1
        val isLockedOut = newAttempts >= maxAttempts
        val newLockoutUntil = if (isLockedOut) now + lockoutDurationMs else null
        updateAdminLockout(newAttempts, newLockoutUntil, now)
        return Pair(newAttempts, newLockoutUntil)
    }

    @Transaction
    suspend fun recordAdminSuccess(now: Long) {
        getOrInitializeSettings()
        resetAdminFailures(now)
    }
}
