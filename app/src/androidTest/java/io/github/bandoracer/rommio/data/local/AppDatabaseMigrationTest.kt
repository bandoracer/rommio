package io.github.bandoracer.rommio.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    @Test
    fun migration6To7CreatesRecoveryStateTableWithoutTouchingExistingRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration-test-${System.currentTimeMillis()}.db"
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()
        if (dbFile.exists()) {
            dbFile.delete()
        }

        val helper = openDatabase(context, dbName, version = 6)

        try {
            helper.writableDatabase.use { database ->
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS save_states (
                      romId INTEGER NOT NULL,
                      slot INTEGER NOT NULL,
                      label TEXT NOT NULL,
                      localPath TEXT NOT NULL,
                      screenshotPath TEXT,
                      updatedAtEpochMs INTEGER NOT NULL,
                      PRIMARY KEY(romId, slot)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS save_state_sync_journal (
                      profileId TEXT NOT NULL,
                      romId INTEGER NOT NULL,
                      fileId INTEGER NOT NULL,
                      slot INTEGER NOT NULL,
                      label TEXT NOT NULL,
                      localPath TEXT,
                      localHash TEXT,
                      localUpdatedAtEpochMs INTEGER,
                      remoteHash TEXT,
                      remoteUpdatedAtEpochMs INTEGER,
                      deleted INTEGER NOT NULL,
                      pendingUpload INTEGER NOT NULL,
                      pendingDelete INTEGER NOT NULL,
                      lastSyncedAtEpochMs INTEGER,
                      PRIMARY KEY(profileId, romId, fileId, slot)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO save_states (romId, slot, label, localPath, screenshotPath, updatedAtEpochMs)
                    VALUES (25, 3, 'Slot 3', '/tmp/fire-red-slot3.state', NULL, 123456789)
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO save_state_sync_journal (
                      profileId, romId, fileId, slot, label, localPath, localHash, localUpdatedAtEpochMs,
                      remoteHash, remoteUpdatedAtEpochMs, deleted, pendingUpload, pendingDelete, lastSyncedAtEpochMs
                    ) VALUES (
                      'profile-1', 25, 99, 3, 'Slot 3', '/tmp/fire-red-slot3.state', 'abc', 123456789,
                      'remote-abc', 123456789, 0, 0, 0, 123456790
                    )
                    """.trimIndent(),
                )

                AppDatabase.MIGRATION_6_7.migrate(database)

                database.query(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'recovery_states'",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("recovery_states", cursor.getString(0))
                }

                database.query("SELECT label, localPath FROM save_states WHERE romId = 25 AND slot = 3").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("Slot 3", cursor.getString(0))
                    assertEquals("/tmp/fire-red-slot3.state", cursor.getString(1))
                }

                database.query(
                    "SELECT remoteHash FROM save_state_sync_journal WHERE profileId = 'profile-1' AND romId = 25 AND fileId = 99 AND slot = 3",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("remote-abc", cursor.getString(0))
                }
            }
        } finally {
            helper.close()
            dbFile.delete()
            File(dbFile.parentFile, "$dbName-wal").delete()
            File(dbFile.parentFile, "$dbName-shm").delete()
        }
    }

    private fun openDatabase(context: Context, name: String, version: Int): SupportSQLiteOpenHelper {
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(version) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit

                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )
    }
}
