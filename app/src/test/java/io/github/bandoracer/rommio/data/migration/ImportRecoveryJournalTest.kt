package io.github.bandoracer.rommio.data.migration

import io.github.bandoracer.rommio.domain.storage.AtomicFiles
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImportRecoveryJournalTest {
    @get:Rule val temp = TemporaryFolder()
    private class Interrupted : RuntimeException()
    private class Stores(private val root: File, var failAfter: String? = null) : ImportRecoveryJournal.Participants {
        override suspend fun applyDatabase(payload: File) = apply("database")
        override suspend fun applySecrets(importId: String) = apply("secrets")
        override suspend fun applyPreferences(payload: File) = apply("preferences")
        override suspend fun releaseSecrets(importId: String) = apply("cleanup")
        private fun apply(name: String) {
            AtomicFiles.writeBytes(File(root, name), "new".toByteArray())
            if (failAfter == name) throw Interrupted()
        }
    }
    private data class Fixture(val root: File, val journalRoot: File, val library: File, val payload: File, val id: String)
    private fun fixture(old: Boolean = true): Fixture {
        val root = temp.newFolder()
        val journalRoot = File(root, "imports").apply { mkdirs() }
        val library = File(root, "library")
        if (old) File(library.apply { mkdirs() }, "save.srm").writeText("old save")
        val id = UUID.randomUUID().toString()
        val payload = File(journalRoot, id).apply { mkdirs() }
        File(File(payload, "library").apply { mkdirs() }, "save.srm").writeText("new save")
        File(payload, "database.json").writeText("{}")
        File(payload, "controls.json").writeText("{}")
        for (name in listOf("database", "secrets", "preferences")) File(root, name).writeText("old")
        return Fixture(root, journalRoot, library, payload, id)
    }
    private fun assertNew(f: Fixture, hadOld: Boolean = true) {
        assertEquals("new save", File(f.library, "save.srm").readText())
        for (name in listOf("database", "secrets", "preferences")) assertEquals("new", File(f.root, name).readText())
        if (hadOld) assertEquals("old save", File(f.payload, "previous-library/save.srm").readText())
        assertFalse(File(f.journalRoot, "pending").exists())
    }

    @Test fun everyDurablePhaseResumesAfterOwnerRecreation() = runBlocking {
        for (phase in ImportRecoveryJournal.Phase.entries) {
            val f = fixture()
            val interrupted = ImportRecoveryJournal(f.journalRoot, f.library, Stores(f.root), syncDirectory = {}) { if (it == phase) throw Interrupted() }
            try { interrupted.prepare(f.id, f.payload); interrupted.recover(); fail("Expected interruption at $phase") } catch (_: Interrupted) { }
            val recreated = ImportRecoveryJournal(f.journalRoot, f.library, Stores(f.root), syncDirectory = {})
            assertEquals(ImportRecoveryJournal.Phase.COMMITTED, recreated.recover()?.phase)
            assertNew(f); assertNull(recreated.recover())
        }
    }

    @Test fun participantCommitBeforeMarkerIsSafelyReplayed() = runBlocking {
        for (name in listOf("database", "secrets", "preferences", "cleanup")) {
            val f = fixture(); val stores = Stores(f.root, name)
            val journal = ImportRecoveryJournal(f.journalRoot, f.library, stores, syncDirectory = {})
            journal.prepare(f.id, f.payload)
            try { journal.recover(); fail("Expected interrupted $name commit") } catch (_: Interrupted) { }
            ImportRecoveryJournal(f.journalRoot, f.library, Stores(f.root), syncDirectory = {}).recover()
            assertNew(f)
        }
    }

    @Test fun interruptedDirectoryRenamesAreRecognizedWithoutOverwritingOldBackup() = runBlocking {
        for (installed in listOf(false, true)) {
            val f = fixture(); val journal = ImportRecoveryJournal(f.journalRoot, f.library, Stores(f.root), syncDirectory = {})
            journal.prepare(f.id, f.payload)
            assertTrue(f.library.renameTo(File(f.payload, "previous-library")))
            if (installed) assertTrue(File(f.payload, "library").renameTo(f.library))
            ImportRecoveryJournal(f.journalRoot, f.library, Stores(f.root), syncDirectory = {}).recover()
            assertNew(f)
        }
    }

    @Test fun emptyDestinationAndRepeatedRecoveryStayConsistent() = runBlocking {
        val f = fixture(old = false); val journal = ImportRecoveryJournal(f.journalRoot, f.library, Stores(f.root), syncDirectory = {})
        journal.prepare(f.id, f.payload); journal.recover(); assertNew(f, hadOld = false)
        File(f.library, "save.srm").writeText("later gameplay")
        assertNull(journal.recover()); assertEquals("later gameplay", File(f.library, "save.srm").readText())
    }

    @Test fun changedDestinationBlocksActivationWithoutDeletingEitherLibrary() = runBlocking {
        val f = fixture(old = false); val journal = ImportRecoveryJournal(f.journalRoot, f.library, Stores(f.root), syncDirectory = {})
        journal.prepare(f.id, f.payload)
        File(f.library.apply { mkdirs() }, "save.srm").writeText("unexpected save")
        try { journal.recover(); fail("Overwrote a different library") } catch (_: IllegalStateException) { }
        assertEquals("unexpected save", File(f.library, "save.srm").readText())
        assertEquals("new save", File(f.payload, "library/save.srm").readText())
        assertNotNull(journal.pending())
    }

    private fun progress(f: Fixture, old: Boolean = true): File {
        val target = File(f.root, "progress-v2")
        if (old) File(target.apply { mkdirs() }, "old-capture").writeText("retained capture")
        val staged = File(f.payload, "progress-v2").apply { mkdirs() }
        File(staged, "blobs").mkdirs()
        File(staged, "blobs/checkpoint").writeText("new progress")
        File(staged, "sessions/capture").apply { parentFile!!.mkdirs(); writeText("uncommitted capture") }
        return target
    }

    private fun assertProgress(f: Fixture, target: File, old: Boolean = true) {
        assertEquals("new progress", File(target, "blobs/checkpoint").readText())
        assertEquals("uncommitted capture", File(target, "sessions/capture").readText())
        if (old) assertEquals("retained capture", File(f.payload, "previous-progress-v2/old-capture").readText())
        else assertFalse(File(f.payload, "previous-progress-v2").exists())
    }

    @Test fun twoRootsAndMetadataRecoverAfterEveryPhaseWithOrWithoutPriorProgress() = runBlocking {
        for (old in listOf(false, true)) for (phase in ImportRecoveryJournal.Phase.entries) {
            val f = fixture(); val target = progress(f, old)
            val journal = ImportRecoveryJournal(f.journalRoot, f.library, Stores(f.root), progress = target, syncDirectory = {}) {
                if (it == phase) throw Interrupted()
            }
            try { journal.prepare(f.id, f.payload); journal.recover(); fail("Missed $phase") } catch (_: Interrupted) { }
            val recovered = ImportRecoveryJournal(f.journalRoot, f.library, Stores(f.root), progress = target, syncDirectory = {})
            assertEquals(ImportRecoveryJournal.Phase.COMMITTED, recovered.recover()?.phase)
            assertNew(f); assertProgress(f, target, old); assertNull(recovered.recover())
        }
    }

    @Test fun directoryCommitsBeforeAcknowledgmentKeepBothBackupsAndResumeWithoutNewIdentity() = runBlocking {
        for (boundary in listOf("library:BACKED_UP", "library:INSTALLED", "progress-v2:BACKED_UP", "progress-v2:INSTALLED")) {
            val f = fixture(); val target = progress(f)
            val journal = ImportRecoveryJournal(f.journalRoot, f.library, Stores(f.root), progress = target,
                syncDirectory = {}, fileCheckpoint = { if (it == boundary) throw Interrupted() })
            journal.prepare(f.id, f.payload)
            try { journal.recover(); fail("Missed $boundary") } catch (_: Interrupted) { }
            assertEquals("old", File(f.root, "database").readText())
            val recovered = ImportRecoveryJournal(f.journalRoot, f.library, Stores(f.root), progress = target, syncDirectory = {})
            assertEquals(f.id, recovered.recover()?.id)
            assertNew(f); assertProgress(f, target)
        }
    }

    @Test fun legacyPendingMarkerNeverReplacesProgressEvenWithNewRecoveryConfiguration() = runBlocking {
        val f = fixture(); val target = progress(f)
        val old = ImportRecoveryJournal(f.journalRoot, f.library, Stores(f.root), syncDirectory = {})
        old.prepare(f.id, f.payload)
        assertNull(old.pending()?.hadProgress)
        ImportRecoveryJournal(f.journalRoot, f.library, Stores(f.root), progress = target, syncDirectory = {}).recover()
        assertNew(f)
        assertEquals("retained capture", File(target, "old-capture").readText())
        assertTrue(File(f.payload, "progress-v2/sessions/capture").isFile)
        assertFalse(File(f.payload, "previous-progress-v2").exists())
    }

    @Test fun missingProgressConfigurationAndUnidentifiedDestinationCannotAdvanceMetadata() = runBlocking {
        val f = fixture(); val target = progress(f, old = false)
        val journal = ImportRecoveryJournal(f.journalRoot, f.library, Stores(f.root), progress = target, syncDirectory = {})
        journal.prepare(f.id, f.payload)
        try {
            ImportRecoveryJournal(f.journalRoot, f.library, Stores(f.root), syncDirectory = {}).recover()
            fail("Missing participant was ignored")
        } catch (_: IllegalStateException) { }
        assertEquals("old save", File(f.library, "save.srm").readText())
        File(target.apply { mkdirs() }, "other-progress").writeText("unexpected")
        try { journal.recover(); fail("Unexpected destination replaced") } catch (_: IllegalStateException) { }
        assertEquals("old", File(f.root, "database").readText())
        assertEquals("unexpected", File(target, "other-progress").readText())
        assertEquals("uncommitted capture", File(f.payload, "progress-v2/sessions/capture").readText())
        assertNotNull(journal.pending())
    }

    @Test fun renamedProgressWithoutRetainedBackupCannotBeAccepted() = runBlocking {
        val f = fixture(); val target = progress(f)
        val journal = ImportRecoveryJournal(f.journalRoot, f.library, Stores(f.root), progress = target,
            syncDirectory = {}, fileCheckpoint = { if (it == "progress-v2:INSTALLED") throw Interrupted() })
        journal.prepare(f.id, f.payload)
        try { journal.recover(); fail("Expected interruption") } catch (_: Interrupted) { }
        // Model a missing recovery volume; do not manufacture another backup from imported files.
        assertTrue(File(f.payload, "previous-progress-v2").renameTo(File(f.root, "detached-backup")))
        try {
            ImportRecoveryJournal(f.journalRoot, f.library, Stores(f.root), progress = target, syncDirectory = {}).recover()
            fail("Missing original backup ignored")
        } catch (_: IllegalStateException) { }
        assertEquals("old", File(f.root, "database").readText())
        assertEquals("new progress", File(target, "blobs/checkpoint").readText())
        assertEquals("retained capture", File(f.root, "detached-backup/old-capture").readText())
    }

    @Test fun unsafeProgressTreesAndOverlappingRootsRejectBeforePreparation() = runBlocking {
        for (kind in listOf("symlink", "overlap", "backup")) {
            val f = fixture(); val target = progress(f)
            when (kind) {
                "symlink" -> java.nio.file.Files.createSymbolicLink(File(f.payload, "progress-v2/link").toPath(), f.library.toPath())
                "backup" -> java.nio.file.Files.createSymbolicLink(File(f.payload, "previous-progress-v2").toPath(), File(f.root, "missing").toPath())
            }
            val journal = ImportRecoveryJournal(f.journalRoot, f.library, Stores(f.root),
                progress = if (kind == "overlap") File(f.library, "progress-v2") else target, syncDirectory = {})
            try { journal.prepare(f.id, f.payload); fail("Accepted $kind") } catch (_: IllegalStateException) { }
            assertNull(journal.pending())
            assertEquals("old save", File(f.library, "save.srm").readText())
            assertEquals("retained capture", File(target, "old-capture").readText())
        }
    }

    @Test fun directoryDurabilityFailureLeavesReplayableMarkerAndDoesNotAdvanceDatabase() = runBlocking {
        val f = fixture(); val target = progress(f)
        var failSync = false
        val journal = ImportRecoveryJournal(f.journalRoot, f.library, Stores(f.root), progress = target,
            syncDirectory = { if (failSync && it == f.payload) throw Interrupted() })
        journal.prepare(f.id, f.payload); failSync = true
        try { journal.recover(); fail("Directory sync failure ignored") } catch (_: Interrupted) { }
        assertEquals(ImportRecoveryJournal.Phase.PREPARED, journal.pending()?.phase)
        assertEquals("old", File(f.root, "database").readText())
        ImportRecoveryJournal(f.journalRoot, f.library, Stores(f.root), progress = target, syncDirectory = {}).recover()
        assertNew(f); assertProgress(f, target)
    }
}
