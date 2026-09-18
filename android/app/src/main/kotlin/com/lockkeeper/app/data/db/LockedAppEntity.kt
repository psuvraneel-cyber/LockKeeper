package com.lockkeeper.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "locked_apps")
data class LockedAppEntity(
    @PrimaryKey val packageName: String,
    val isLocked: Boolean = true,
    val cooldownMinutes: Int = 15,
    val strictLock: Boolean = false,
    val lockedUntilTimestamp: Long? = null,
    val updatedAt: Long = System.currentTimeMillis()
)
