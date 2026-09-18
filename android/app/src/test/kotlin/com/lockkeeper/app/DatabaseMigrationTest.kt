package com.lockkeeper.app

import androidx.sqlite.db.SupportSQLiteDatabase
import com.lockkeeper.app.data.db.AppDatabase
import com.lockkeeper.app.data.db.AppSettingsEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class DatabaseMigrationTest {

    @Test
    fun `migration 3 to 4 version numbers are correct`() {
        assertEquals(3, AppDatabase.MIGRATION_3_4.startVersion)
        assertEquals(4, AppDatabase.MIGRATION_3_4.endVersion)
    }

    @Test
    fun `migration 3 to 4 adds recoveryRequired column with default 0 without data loss`() {
        val executedStatements = mutableListOf<String>()

        val dbProxy = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                executedStatements.add(args[0] as String)
            }
            null
        } as SupportSQLiteDatabase

        AppDatabase.MIGRATION_3_4.migrate(dbProxy)

        assertEquals(1, executedStatements.size)
        val sql = executedStatements[0]
        assertTrue(
            "SQL must ALTER TABLE app_settings ADD COLUMN recoveryRequired",
            sql.contains("ALTER TABLE app_settings ADD COLUMN recoveryRequired INTEGER NOT NULL DEFAULT 0")
        )
    }

    @Test
    fun `app settings entity v4 defaults recoveryRequired to false preserving existing settings`() {
        val entity = AppSettingsEntity(
            id = 1,
            onboardingComplete = true,
            selfLockEnabled = true,
            selfLockTimeoutSeconds = 30,
            failedPinAttempts = 2,
            pinLockoutUntil = null,
            failedAdminAttempts = 1,
            adminLockoutUntil = null
        )

        assertFalse("recoveryRequired must default to false for existing setups", entity.recoveryRequired)
        assertTrue("securityProvisioned must match onboardingComplete for existing setups", entity.securityProvisioned)
        assertEquals(5, entity.schemaVersion)
        assertEquals(1, entity.id)
        assertTrue(entity.onboardingComplete)
        assertTrue(entity.selfLockEnabled)
    }

    @Test
    fun `app settings entity defaults securityProvisioned to false when onboardingComplete is false`() {
        val entity = AppSettingsEntity(
            onboardingComplete = false
        )
        assertFalse(entity.securityProvisioned)
    }

    @Test
    fun `migration 4 to 5 version numbers are correct`() {
        assertEquals(4, AppDatabase.MIGRATION_4_5.startVersion)
        assertEquals(5, AppDatabase.MIGRATION_4_5.endVersion)
    }

    @Test
    fun `migration 4 to 5 adds securityProvisioned column with default 0 and updates from onboardingComplete`() {
        val executedStatements = mutableListOf<String>()

        val dbProxy = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                executedStatements.add(args[0] as String)
            }
            null
        } as SupportSQLiteDatabase

        AppDatabase.MIGRATION_4_5.migrate(dbProxy)

        assertEquals(2, executedStatements.size)
        assertTrue(
            "SQL must ALTER TABLE app_settings ADD COLUMN securityProvisioned",
            executedStatements[0].contains("ALTER TABLE app_settings ADD COLUMN securityProvisioned INTEGER NOT NULL DEFAULT 0")
        )
        assertTrue(
            "SQL must UPDATE app_settings SET securityProvisioned = onboardingComplete",
            executedStatements[1].contains("UPDATE app_settings SET securityProvisioned = onboardingComplete WHERE id = 1")
        )
    }
}
