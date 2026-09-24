package io.github.bandoracer.rommio.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.bandoracer.rommio.data.auth.AuthJsonCodec
import io.github.bandoracer.rommio.data.local.*
import io.github.bandoracer.rommio.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogCacheTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "catalog-${UUID.randomUUID()}.db"
    private lateinit var db: AppDatabase
    private lateinit var profile: ServerProfile
    private val codec = OfflineCacheJsonCodec()
    private val gameFile = File(context.cacheDir, "$name.rom")
    private fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_11_12, AppDatabase.MIGRATION_12_13, AppDatabase.MIGRATION_13_14).build()
    @Before fun setup() = runBlocking {
        db = open()
        val auth = AuthJsonCodec()
        val row = ServerProfileEntity("catalog-test", "Test", "https://catalog.invalid", EdgeAuthMode.NONE.name,
            OriginAuthMode.ROMM_BEARER_PASSWORD.name, auth.encodeCapabilities(AuthCapabilities()),
            auth.encodeServerAccess(ServerAccessState(status = ServerAccessStatus.READY)), auth.encodeSessionState(SessionState()),
            true, AuthStatus.CONNECTED.name, null, "created", "updated")
        db.serverProfileDao().upsert(row); profile = ServerProfile(row.id, row.label, row.baseUrl, EdgeAuthMode.NONE, OriginAuthMode.ROMM_BEARER_PASSWORD, AuthCapabilities(), ServerAccessState(status = ServerAccessStatus.READY), SessionState(), true, AuthStatus.CONNECTED, null, "created", "updated")
        gameFile.writeText("installed-content")
    }
    @After fun cleanup() { db.close(); context.deleteDatabase(name); gameFile.delete() }
    private fun snapshot(ids: IntRange, collections: List<RommCollectionDto> = emptyList()) = CatalogSnapshot(
        listOf(PlatformDto(1, "nes", "NES", "nes")), ids.map { RomDto(it, "Game $it", platformId = 1) }, collections,
        ids.take(2).map { RomDto(it) }, ids.toList().takeLast(2).map { RomDto(it) })
    private fun reopen() { db.close(); db = open() }
    private suspend fun reject(block: suspend () -> Unit) { var error: Exception? = null; try { block() } catch (e: Exception) { error = e }; assertNotNull(error) }

    @Test fun failedTransactionRetainsCompletePreviousGenerationAcrossReopen() = runBlocking {
        val oldCollection = RommCollectionDto(CollectionKind.REGULAR, "1", "Old", romIds = setOf(1, 2), romCount = 2)
        CatalogCache(db).commitFull(profile, snapshot(1..120, listOf(oldCollection)), 100)
        for (boundary in listOf("ROMS_WRITTEN", "MEMBERSHIP_WRITTEN")) {
            reject { CatalogCache(db, checkpoint = { if (it == boundary) error("Interrupted") })
                .commitFull(profile, snapshot(121..250), 200) }
            reopen()
            assertEquals((1..120).toSet(), db.cachedRomDao().listAll(profile.id).map { it.romId }.toSet())
            assertEquals("Old", db.cachedCollectionDao().listAll(profile.id).single().name)
            assertEquals(listOf(1, 2), db.cachedCollectionRomDao().listByCollection(profile.id, "REGULAR", "1").map { it.romId })
            assertEquals(100L, db.profileCacheStateDao().getByProfile(profile.id)!!.lastFullSyncAtEpochMs)
        }
    }
    @Test fun deletionKeepsInstalledBytesMetadataAndUnrelatedAccount() = runBlocking {
        val cache = CatalogCache(db); cache.commitFull(profile, snapshot(1..5), 100)
        db.downloadedRomDao().upsert(DownloadedRomEntity(2, 20, "nes", "Installed", "game.nes", gameFile.path, gameFile.length(), 1,
            ownerProfileId = profile.id, ownerBaseUrl = profile.baseUrl, ownerAccountId = 1))
        db.downloadedRomDao().upsert(DownloadedRomEntity(1, 10, "nes", "Legacy installed", "legacy.nes", gameFile.path, gameFile.length(), 1))
        db.cachedRomDao().upsertAll(listOf(CachedRomEntity("another-profile", 1, name = "Other", updatedAtEpochMs = 1)))
        cache.commitFull(profile, snapshot(3..4), 200); reopen()
        assertEquals(setOf(1, 2, 3, 4), db.cachedRomDao().listAll(profile.id).map { it.romId }.toSet())
        assertEquals("Other", db.cachedRomDao().getById("another-profile", 1)!!.name)
        assertEquals("installed-content", gameFile.readText()); assertEquals(2, db.downloadedRomDao().listAll().size)
    }
    @Test fun summariesPreserveDetailedFilesAndDetailedRefreshCanClearThem() = runBlocking {
        val cache = CatalogCache(db)
        val files = listOf(RomFileDto(10, 1, "one.nes", "nes", 3), RomFileDto(20, 2, "sibling.nes", "nes", 4))
        cache.cacheRoms(profile, listOf(RomDto(1, files = files, siblings = listOf(RomSiblingDto(2)))), detailed = true)
        cache.cacheRoms(profile, listOf(RomDto(1, name = "Renamed")))
        assertEquals(files, codec.decodeFiles(db.cachedRomDao().getById(profile.id, 1)!!.filesJson))
        cache.cacheRoms(profile, listOf(RomDto(1, files = listOf(files[0].copy(fileSizeBytes = 5)), siblings = listOf(RomSiblingDto(2)))))
        assertEquals(listOf(5L, 4L), codec.decodeFiles(db.cachedRomDao().getById(profile.id, 1)!!.filesJson).map { it.fileSizeBytes })
        cache.cacheRoms(profile, listOf(RomDto(1, siblings = listOf(RomSiblingDto(2)))), filesIncluded = true)
        assertEquals(listOf(files[1]), codec.decodeFiles(db.cachedRomDao().getById(profile.id, 1)!!.filesJson))
        cache.cacheRoms(profile, listOf(RomDto(1)), detailed = true)
        assertEquals("[]", db.cachedRomDao().getById(profile.id, 1)!!.filesJson)
    }
    @Test fun emptyCatalogAndEqualCollectionIdsRemainValidAfterRestart() = runBlocking {
        val collections = CollectionKind.entries.mapIndexed { index, kind -> RommCollectionDto(kind, "1", kind.name, romIds = setOf(index + 1), romCount = 1) }
        val cache = CatalogCache(db); cache.commitFull(profile, snapshot(1..3, collections), 100)
        reopen()
        CollectionKind.entries.forEachIndexed { i, kind -> assertEquals(i + 1, db.cachedCollectionRomDao().listByCollection(profile.id, kind.name, "1").single().romId) }
        CatalogCache(db).commitFull(profile, snapshot(IntRange.EMPTY), 200); reopen()
        assertTrue(db.cachedRomDao().listAll(profile.id).isEmpty()); assertTrue(db.cachedCollectionDao().listAll(profile.id).isEmpty())
        assertTrue(db.profileCacheStateDao().getByProfile(profile.id)!!.catalogReady)
    }
    @Test fun schemaElevenUpgradeInvalidatesOnlyUnprovenReadiness() = runBlocking {
        CatalogCache(db).commitFull(profile, snapshot(1..2), 100)
        db.profileCacheStateDao().upsert(ProfileCacheStateEntity(profile.id, 100, 100, true, true, true))
        io.github.bandoracer.rommio.data.local.restoreVersion12ProgressTables(db.openHelper.writableDatabase)
        db.close()
        val raw = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        raw.version = 11; raw.execSQL("DELETE FROM room_master_table"); raw.close()
        db = open()
        val state = db.profileCacheStateDao().getByProfile(profile.id)!!
        assertFalse(state.catalogReady); assertFalse(state.mediaReady); assertFalse(state.isRefreshing)
        assertNull(state.lastFullSyncAtEpochMs); assertEquals(2, db.cachedRomDao().listAll(profile.id).size)
    }
    @Test fun targetedRefreshNeverCertifiesFullCatalogOrMedia() = runBlocking {
        val cache = CatalogCache(db)
        cache.cacheRoms(profile, listOf(RomDto(1)))
        cache.finishTargetedRefresh(profile.id)
        var state = db.profileCacheStateDao().getByProfile(profile.id)!!
        assertFalse(state.catalogReady); assertFalse(state.mediaReady); assertNull(state.lastFullSyncAtEpochMs)
        cache.commitFull(profile, snapshot(1..2), 100)
        cache.finishTargetedRefresh(profile.id)
        state = db.profileCacheStateDao().getByProfile(profile.id)!!
        assertTrue(state.catalogReady); assertFalse(state.mediaReady); assertEquals(100L, state.lastFullSyncAtEpochMs)
    }
    @Test fun missingProfileAndInconsistentCollectionCannotPublish() = runBlocking {
        val cache = CatalogCache(db); cache.commitFull(profile, snapshot(1..2), 100)
        reject { cache.commitFull(profile, snapshot(3..4, listOf(RommCollectionDto(CollectionKind.REGULAR, "1", "Stale", romIds = setOf(99), romCount = 1))), 200) }
        assertEquals(setOf(1, 2), db.cachedRomDao().listAll(profile.id).map { it.romId }.toSet())
        db.serverProfileDao().delete(profile.id)
        reject { cache.commitFull(profile, snapshot(3..4), 200) }
    }
}
