package io.github.bandoracer.rommio.data.migration

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.bandoracer.rommio.data.auth.VerifiedAccount
import io.github.bandoracer.rommio.data.local.*
import io.github.bandoracer.rommio.domain.storage.*
import io.github.bandoracer.rommio.model.*
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyProgressRecoveryTest {
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val id = "progress-recovery-${UUID.randomUUID()}"
    private lateinit var root: File
    private lateinit var library: LibraryStore
    private lateinit var db: AppDatabase
    private lateinit var target: DownloadedRomEntity
    private var owner: Pair<String, VerifiedAccount>? = "profile-a" to VerifiedAccount("https://a.example", 1)
    private var failure: String? = null
    private val journal get() = File(root, "journal")
    private fun openDatabase() = Room.databaseBuilder(base, AppDatabase::class.java, id).build()
    private fun manager() = LegacyProgressRecovery(db, library, journal, { owner }, { if (it == failure) error("Interrupted at $it") })
    private fun file(path: String, bytes: String): File = File(library.rootDirectory(), path).apply { parentFile!!.mkdirs(); writeText(bytes) }
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun scoped() = target.copy(progressScope = InstallIdentity.progress(target.ownerBaseUrl, target.ownerAccountId, target.romId, target.fileId, target.contentSha256))
    private suspend fun expectFailure(block: suspend () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalStateException) { } catch (_: IllegalArgumentException) { }
    }
    @Before fun setup() = runBlocking {
        root = File(base.cacheDir, id).canonicalFile.apply { mkdirs() }
        library = AppManagedLibraryStore(object : ContextWrapper(base) { override fun getFilesDir() = File(root, "files") })
        library.ensureRootLayout(); db = openDatabase()
        val rom = file("roms/installs/game.nes", "verified rom")
        target = DownloadedRomEntity(10, 20, "nes", "Game", "game.nes", rom.path, rom.length(), 100,
            "profile-a", "https://a.example", 1, hash(rom))
        db.downloadedRomDao().upsert(target)
    }
    @After fun cleanup() {
        StorageAccess.requireRecovery(false, "legacy-progress"); StorageAccess.requireRecovery(false)
        db.close(); base.deleteDatabase(id); root.deleteRecursively()
    }

    @Test fun inventoriesOrphansDeletedSlotZeroHiddenResumeAndMissingRecordsWithoutAssigningOwner() = runBlocking {
        file("saves/10_20.srm", "sram")
        val zero = file("states/10/10_slot0.state", "zero")
        file("states/10/__rommio_resume_10_20.state", "hidden")
        file("states/99/orphan.state", "orphan")
        file("saves/unknown.sav", "unclassified")
        db.saveStateDao().upsert(SaveStateEntity(10, 0, "Original slot zero", zero.path, updatedAtEpochMs = 1))
        db.saveStateDao().upsert(SaveStateEntity(10, 1, "Missing", File(zero.parentFile, "missing.state").path, updatedAtEpochMs = 1))
        db.saveStateSyncJournalDao().upsert(SaveStateSyncJournalEntity("old-profile", 10, 20, 0, "Deleted", zero.path, deleted = true, pendingDelete = true))
        val result = manager().inventory()
        assertEquals(6, result.artifacts.size)
        assertTrue(result.artifacts.first { it.path.endsWith("10_slot0.state") }.provenance.any { it.contains("previously deleted") })
        assertNotNull(result.artifacts.first { it.path.endsWith("missing.state") }.issue)
        assertNotNull(result.artifacts.first { it.path.endsWith("unknown.sav") }.issue)
        assertEquals(listOf(target), result.targets)
        assertEquals(InstallIdentity.LEGACY, db.downloadedRomDao().listAll().single().progressScope)
        assertTrue(db.recoveryStateDao().listAll().isEmpty())
    }

    @Test fun copiesExplicitSelectionPreservingOriginalsTombstonesAndIntentWithoutAutomaticResume() = runBlocking {
        val sram = file("saves/10_20.srm", "played save")
        val zero = file("states/10/10_slot0.state", "manual bytes")
        file("states/10/__rommio_resume_10_20.state", "old resume")
        db.saveStateSyncJournalDao().upsert(SaveStateSyncJournalEntity("old", 10, 20, 0, "Deleted", zero.path, deleted = true, pendingDelete = true))
        val action = PendingRemoteActionEntity("old", "MANUAL_SLOT_DELETE", "original", "{}", "PENDING", 1, 1, null)
        db.pendingRemoteActionDao().upsert(action)
        val recovery = manager(); val sources = recovery.inventory().artifacts
        assertEquals(3, recovery.copy(sources, target))
        val installed = db.downloadedRomDao().listAll().single()
        assertEquals(scoped(), installed)
        assertEquals("played save", library.saveRamFile(installed).readText())
        assertFalse(library.continuityResumeStateFile(installed).exists())
        val states = db.recoveryStateDao().listByGame(10, 20, installed.progressScope)
        assertEquals(setOf("manual bytes", "old resume"), states.map { File(it.localPath).readText() }.toSet())
        assertTrue(states.all { it.preserved && it.progressScope == installed.progressScope })
        assertTrue(db.saveStateSyncJournalDao().listAll().single().deleted)
        assertEquals(listOf(action), db.pendingRemoteActionDao().listByProfile("old"))
        assertTrue(db.gameSyncJournalDao().getByKey("profile-a", 10, 20, installed.progressScope)!!.pendingContinuityUpload)
        assertEquals("played save", sram.readText()); assertEquals("manual bytes", zero.readText())
        assertTrue(File(library.rootDirectory(), "recovery-imports").listFiles()!!.single().readText().contains("previously deleted"))
        assertFalse(File(journal, "pending.json").exists())
    }

    @Test fun interruptedCopiesResumeAfterDatabaseReopenEvenWithAnotherActiveAccount() = runBlocking {
        file("saves/10_20.srm", "sram"); file("states/10/10_slot0.state", "state")
        val recovery = manager(); val sources = recovery.inventory().artifacts
        failure = "PREPARED"; expectFailure { recovery.copy(sources, target) }
        assertFalse(library.saveRamFile(scoped()).exists())
        expectFailure { StorageAccess.normal { fail("Pending copy became usable") } }
        owner = "profile-b" to VerifiedAccount("https://b.example", 2)
        for (boundary in listOf("FILE_0", "FILES_INSTALLED", "DATABASE_APPLIED")) {
            db.close(); db = openDatabase(); failure = boundary
            expectFailure { manager().recover() }
            assertTrue(File(journal, "pending.json").exists())
        }
        db.close(); db = openDatabase(); failure = null
        manager().recover(); manager().recover()
        assertEquals(scoped(), db.downloadedRomDao().listAll().single())
        assertEquals(1, db.recoveryStateDao().listAll().size)
        assertEquals("sram", library.saveRamFile(scoped()).readText())
        assertTrue(db.recoveryStateDao().listAll().all { it.progressScope == scoped().progressScope })
        StorageAccess.normal { }
    }

    @Test fun existingDestinationProgressIsNeverOverwritten() = runBlocking {
        file("saves/10_20.srm", "old")
        val current = library.saveRamFile(scoped()).apply { parentFile!!.mkdirs(); writeText("current") }
        val sources = manager().inventory().artifacts
        expectFailure { manager().copy(sources, target) }
        assertEquals("current", current.readText()); assertEquals(target, db.downloadedRomDao().listAll().single())
        assertFalse(File(journal, "pending.json").exists())
        current.delete()
        library.continuityResumeStateFile(scoped()).apply { parentFile!!.mkdirs(); writeText("resume") }
        expectFailure { manager().copy(sources, target) }
    }

    @Test fun rejectsChangedSourceChangedInstallAndWrongAccount() = runBlocking {
        val source = file("saves/10_20.srm", "old")
        val sources = manager().inventory().artifacts
        source.writeText("new"); expectFailure { manager().copy(sources, target) }; source.writeText("old")
        owner = "profile-b" to VerifiedAccount("https://b.example", 2)
        assertTrue(manager().inventory().targets.isEmpty()); expectFailure { manager().copy(sources, target) }
        owner = "profile-a" to VerifiedAccount("https://a.example", 1)
        File(target.localPath).writeText("changed rom")
        expectFailure { manager().copy(sources, target) }
        assertFalse(File(journal, "pending.json").exists())
    }

    @Test fun activeSessionBlocksRecoveryBeforeStaging() = runBlocking {
        file("states/10/10_slot0.state", "state")
        val sources = manager().inventory().artifacts
        val lease = StorageAccess.session()
        try { expectFailure { manager().copy(sources, target) } } finally { lease.close() }
        assertFalse(File(journal, "pending.json").exists())
        assertEquals(1, manager().copy(sources, target))
    }

    @Test fun symlinksAndPathTraversalCannotCopyFilesOutsideLegacyInventory() = runBlocking {
        val external = File(root, "external.state").apply { writeText("external") }
        val linked = File(library.rootDirectory(), "states/linked.state")
        Files.createSymbolicLink(linked.toPath(), external.toPath())
        val result = manager().inventory()
        assertNotNull(result.artifacts.single().issue)
        expectFailure { manager().copy(result.artifacts, target) }
        val fake = LegacyProgressRecovery.Artifact("../external.state", "external", "STATE", external.length(), hash(external), emptyList())
        expectFailure { manager().copy(listOf(fake), target) }
        assertEquals("external", external.readText())
    }

    @Test fun corruptStagedDataKeepsRecoveryBlockedAndOriginalUntouched() = runBlocking {
        val source = file("states/10/10_slot0.state", "original")
        failure = "PREPARED"; expectFailure { manager().copy(manager().inventory().artifacts, target) }
        journal.walkTopDown().first { it.extension == "blob" }.writeText("corrupt")
        failure = null; expectFailure { manager().recover() }
        assertTrue(File(journal, "pending.json").exists())
        assertEquals("original", source.readText()); assertEquals(target, db.downloadedRomDao().listAll().single())
        assertTrue(db.recoveryStateDao().listAll().isEmpty())
        expectFailure { StorageAccess.normal { fail("Corrupt recovery released storage") } }
    }

    @Test fun collidingAccountsGetSeparateCopiesAndNoManualSlotReplacement() = runBlocking {
        val source = file("states/10/10_slot0.state", "legacy")
        val sources = manager().inventory().artifacts
        manager().copy(sources, target)
        val first = db.recoveryStateDao().listAll().single()
        owner = "profile-b" to VerifiedAccount("https://b.example", 2)
        val other = target.copy(ownerProfileId = "profile-b", ownerBaseUrl = "https://b.example", ownerAccountId = 2,
            namespaceKey = InstallIdentity.namespace("https://b.example", 2))
        db.downloadedRomDao().upsert(other)
        manager().copy(sources, other)
        val copies = db.recoveryStateDao().listAll()
        assertEquals(2, copies.size); assertEquals(2, copies.map { it.progressScope }.distinct().size)
        assertEquals(2, copies.map { it.localPath }.distinct().size)
        assertTrue(copies.all { File(it.localPath).readText() == "legacy" })
        assertEquals(first, db.recoveryStateDao().listByGame(10, 20, first.progressScope).single())
        assertEquals("legacy", source.readText()); assertTrue(db.saveStateDao().listAll().isEmpty())
    }

    @Test fun preFixResumeCopiesRemainRecoverableAndManualLabelsSurvive() = runBlocking {
        val original = file("states/10/10_slot0.state", "manual")
        file("states/10/__rommio_resume_10_20.state.before-resume-fix", "retained resume")
        db.saveStateDao().upsert(SaveStateEntity(10, 0, "Before final boss", original.path, updatedAtEpochMs = 1))
        val recovery = manager(); val sources = recovery.inventory().artifacts
        assertTrue(sources.all { it.kind == "STATE" && it.issue == null })
        recovery.copy(sources, target)
        assertTrue(db.recoveryStateDao().listAll().any { it.label == "Recovered: Before final boss" })
        assertEquals(setOf("manual", "retained resume"), db.recoveryStateDao().listAll().map { File(it.localPath).readText() }.toSet())
    }

    @Test fun archiveAndDiscSetTargetsWaitForPlayableContentDescriptors() = runBlocking {
        file("saves/10_20.srm", "original")
        val sources = manager().inventory().artifacts
        for (extension in listOf("zip", "m3u", "cue")) {
            val archive = target.copy(fileName = "game.$extension")
            db.downloadedRomDao().upsert(archive)
            assertNotNull(manager().targetIssue(archive))
            expectFailure { manager().copy(sources, archive) }
        }
        assertFalse(File(journal, "pending.json").exists())
    }
}
