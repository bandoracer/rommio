package io.github.bandoracer.rommio.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.bandoracer.rommio.data.repository.OfflineCacheJsonCodec
import io.github.bandoracer.rommio.model.RomMetadata
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 13 → 14 (local play data). Builds a real version-13 database from the current schema minus the
 * Phase 4 additions, then lets Room run MIGRATION_13_14 and validate every table against the
 * entities — Room refuses to open if the migrated schema differs from a fresh one.
 */
@RunWith(AndroidJUnit4::class)
class PlayDataMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "play-data-migration-${System.nanoTime()}.db"

    @After fun cleanup() {
        val file = context.getDatabasePath(name)
        listOf(file, File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal")).forEach { it.delete() }
    }

    private fun raw(version: Int, block: (SupportSQLiteDatabase) -> Unit) {
        val helper: SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build(),
        )
        try { block(helper.writableDatabase) } finally { helper.close() }
    }

    private fun columns(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA table_info(`$table`)").use { c -> buildList { while (c.moveToNext()) add(c.getString(1)) } }

    @Test
    fun migrationAddsPlayDataAndKeepsTheCatalogue() = runBlocking {
        // 1. A complete current database, then rewound to version 13 exactly as v13 shipped.
        Room.databaseBuilder(context, AppDatabase::class.java, name).build().apply { openHelper.writableDatabase; close() }
        raw(14) { db ->
            restoreVersion13PlayData(db)
            db.execSQL("INSERT INTO cached_roms (profileId, romId, name, summary, platformId, platformName, platformSlug, fsName, filesJson, siblingsJson, urlCover, coverCachedUri, updatedAtEpochMs) VALUES ('p1', 12, 'EarthBound', 'Onett', 1, 'Super Nintendo', 'snes', 'EarthBound (USA).sfc', '[]', '[]', '/c.png', NULL, 5)")
            db.version = 13
            assertFalse(columns(db, "cached_roms").contains("metadataJson"))
            assertTrue(columns(db, "play_sessions").isEmpty())
        }

        // 2. Room migrates and validates the whole schema on open.
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_13_14).build()
        try {
            val rom = migrated.cachedRomDao().getById("p1", 12)!!
            assertEquals("EarthBound", rom.name)
            assertEquals("{}", rom.metadataJson)
            assertEquals(RomMetadata.EMPTY, OfflineCacheJsonCodec().decodeMetadata(rom.metadataJson))

            val dao = migrated.playDataDao()
            dao.upsertSession(PlaySessionEntity("s1", "ns", "c", "https://romm.example", "1", 12, 120, "snes", "dev", "snes9x",
                1_000, null, 1_000, 0, null))
            dao.heartbeat("s1", 31_000, 30_000)
            assertEquals(listOf("s1"), dao.openSessions().map { it.sessionId })
            assertEquals(1, dao.finish("s1", 40_000, 35_000, "resume"))
            assertEquals(0, dao.finish("s1", 90_000, 99_000, "quit")) // a finished session is immutable
            val row = dao.sessionsFor("https://romm.example", "1").single()
            assertEquals(40_000L, row.endedAtEpochMs)
            assertEquals(35_000L, row.activeDurationMs)
            assertEquals("resume", row.endReason)
            assertTrue(dao.openSessions().isEmpty())

            dao.upsertLabel(SaveStateLabelEntity("ns", "c", "cp", "Before Kraid", 1))
            assertEquals("Before Kraid", dao.labelsFor("ns", "c").single().label)
            dao.deleteLabel("ns", "c", "cp")
            assertNull(dao.labelsFor("ns", "c").firstOrNull())
        } finally {
            migrated.close()
        }
    }
}
