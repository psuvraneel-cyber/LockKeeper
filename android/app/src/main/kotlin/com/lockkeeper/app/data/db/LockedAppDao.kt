package com.lockkeeper.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LockedAppDao {
    @Query("SELECT * FROM locked_apps ORDER BY packageName ASC")
    suspend fun getAllLockedApps(): List<LockedAppEntity>

    @Query("SELECT * FROM locked_apps ORDER BY packageName ASC")
    fun getAllLockedAppsFlow(): Flow<List<LockedAppEntity>>

    @Query("SELECT * FROM locked_apps WHERE packageName = :packageName LIMIT 1")
    suspend fun getLockedApp(packageName: String): LockedAppEntity?

    @Query("SELECT * FROM locked_apps WHERE isLocked = 1")
    suspend fun getActiveLockedApps(): List<LockedAppEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(app: LockedAppEntity)

    @Query("DELETE FROM locked_apps WHERE packageName = :packageName")
    suspend fun delete(packageName: String)

    @Query("UPDATE locked_apps SET lockedUntilTimestamp = :lockedUntilTimestamp, updatedAt = :updatedAt WHERE packageName = :packageName")
    suspend fun updateLockedUntil(packageName: String, lockedUntilTimestamp: Long?, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE locked_apps SET isLocked = :isLocked, updatedAt = :updatedAt WHERE packageName = :packageName")
    suspend fun setLockStatus(packageName: String, isLocked: Boolean, updatedAt: Long = System.currentTimeMillis())
}
