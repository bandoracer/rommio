package io.github.bandoracer.rommio.domain.sync

import android.content.Context
import io.github.bandoracer.rommio.data.local.*
import io.github.bandoracer.rommio.domain.player.RuntimeProfile
import io.github.bandoracer.rommio.domain.storage.LibraryStore
import io.github.bandoracer.rommio.model.*
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LegacyProgressContainmentTest {
    private val install = DownloadedRomEntity(42, 7, "snes", "Fixture", "fixture.sfc", "/unused", 16, 0)
    private val rom = RomDto(id = 42)
    private val runtime = RuntimeProfile("snes9x", "Fixture", setOf("snes"), "fixture.so")

    @Test fun launchUsesLatestLocalResumeWithoutOriginAuthentication() = runBlocking {
        val h = Harness()
        try {
            val resume = h.library.continuityResumeStateFile(install)
            resume.writeText("latest")
            h.profile = null
            val prepared = h.bridge.preparePlayerEntry(install, rom, runtime)
            assertEquals(resume.absolutePath, prepared.launchTarget?.localStatePath)
            assertEquals(GameSyncStatusKind.LOCAL_ONLY, prepared.syncPresentation.kind)
            assertEquals("latest", resume.readText())
        } finally { h.root.deleteRecursively() }
    }

    @Test fun publicationAttemptsKeepLocalBytesAndNeverAdvanceAcknowledgment() = runBlocking {
        val h = Harness()
        try {
            h.library.saveRamFile(install).writeText("save")
            val slot = h.library.saveStateFile(install, 1).apply { writeText("manual") }
            h.bridge.recordManualState(install, 1, slot)
            h.game = GameSyncJournalEntity("profile", 42, 7, lastSyncedSramHash = "old", lastSuccessfulSyncAtEpochMs = 123)
            assertTrue(runCatching { h.bridge.syncGame(install, rom, runtime) }.exceptionOrNull() is LegacySyncUnavailableException)
            assertTrue(runCatching { h.bridge.flushContinuity(install, rom, runtime, false) }.exceptionOrNull() is LegacySyncUnavailableException)
            assertTrue(runCatching { h.bridge.adoptRemoteContinuity(install, rom, runtime) }.exceptionOrNull() is LegacySyncUnavailableException)
            assertEquals("save", h.library.saveRamFile(install).readText())
            assertEquals("manual", slot.readText())
            assertEquals(123L, h.game?.lastSuccessfulSyncAtEpochMs)
            assertEquals("old", h.game?.lastSyncedSramHash)
            assertEquals(true, h.game?.pendingContinuityUpload)
            assertEquals(true, h.slots[1]?.pendingUpload)
        } finally { h.root.deleteRecursively() }
    }

    @Test fun refreshCannotResurrectInterruptedDeletionOrClearUnpublishedSlot() = runBlocking {
        val h = Harness()
        try {
            val deleted = h.library.saveStateFile(install, 1).apply { writeText("old") }
            h.bridge.recordManualState(install, 1, deleted)
            h.bridge.markManualStateDeleted(install, 1)
            deleted.writeText("leftover after interruption")
            val pending = h.library.saveStateFile(install, 2).apply { writeText("new") }
            h.bridge.recordManualState(install, 2, pending)
            h.bridge.preparePlayerEntry(install, rom, runtime)
            h.bridge.refreshStateRecovery(install, rom, runtime)
            assertEquals(true, h.slots[1]?.deleted)
            assertEquals(true, h.slots[1]?.pendingDelete)
            assertFalse(h.visible.containsKey(1))
            assertEquals(true, h.slots[2]?.pendingUpload)
            assertEquals("new", pending.readText())
        } finally { h.root.deleteRecursively() }
    }

    @Test fun legacySlotZeroImportRetainsManualOriginalAndNeverReplacesNewResume() = runBlocking {
        val h = Harness()
        try {
            val zero = h.library.saveStateFile(install, 0).apply { writeText("original") }
            val resume = h.library.continuityResumeStateFile(install)
            h.bridge.preparePlayerEntry(install, rom, runtime)
            assertEquals("original", resume.readText())
            resume.writeText("newer")
            h.bridge.preparePlayerEntry(install, rom, runtime)
            assertEquals("newer", resume.readText())
            assertEquals("original", zero.readText())
        } finally { h.root.deleteRecursively() }
    }
}

private class Harness {
    val root: File = Files.createTempDirectory("legacy-progress").toFile()
    var profile: String? = "profile"
    var game: GameSyncJournalEntity? = null
    val slots = mutableMapOf<Int, SaveStateSyncJournalEntity>()
    val visible = mutableMapOf<Int, SaveStateEntity>()
    val library = TestLibraryStore(root)
    private val games: GameSyncJournalDao = stub { name, args -> when (name) {
        "getByKey" -> game
        "upsert" -> { game = args[0] as GameSyncJournalEntity; Unit }
        else -> error("Unexpected game DAO call: $name")
    } }
    private val journals: SaveStateSyncJournalDao = stub { name, args -> when (name) {
        "getByKey" -> slots[args[3] as Int]
        "upsert" -> { val row = args[0] as SaveStateSyncJournalEntity; slots[row.slot] = row; Unit }
        else -> error("Unexpected slot DAO call: $name")
    } }
    private val states: SaveStateDao = stub { name, args -> when (name) {
        "upsert" -> { val row = args[0] as SaveStateEntity; visible[row.slot] = row; Unit }
        "delete" -> { visible.remove(args[1] as Int); Unit }
        else -> error("Unexpected state DAO call: $name")
    } }
    private val recovery: RecoveryStateDao = stub { name, _ -> when (name) {
        "listByGame" -> emptyList<RecoveryStateEntity>()
        "upsert" -> Unit
        else -> error("Unexpected recovery DAO call: $name")
    } }
    val bridge = RommSyncBridge({ profile }, library, states, games, journals, recovery, "Fixture")
}

private inline fun <reified T> stub(crossinline handler: (String, Array<out Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> handler(method.name, args ?: emptyArray()) } as T

private class TestLibraryStore(private val root: File) : LibraryStore {
    override fun ensureRootLayout() {}
    override fun rootDirectory() = root
    override fun romDirectory(platformSlug: String) = File(root, "roms/$platformSlug").apply { mkdirs() }
    override fun romTarget(platformSlug: String, fileName: String) = File(romDirectory(platformSlug), fileName)
    override fun saveRamFile(installation: DownloadedRomEntity) = File(root, "save.srm")
    override fun saveStatesDirectory(installation: DownloadedRomEntity) = File(root, "states").apply { mkdirs() }
    override fun continuityResumeStateFile(installation: DownloadedRomEntity) = File(saveStatesDirectory(installation), "resume.state")
    override fun saveStateFile(installation: DownloadedRomEntity, slot: Int) = File(saveStatesDirectory(installation), "${installation.romId}_slot$slot.state")
    override fun screenshotsDirectory(installation: DownloadedRomEntity) = File(root, "screenshots")
    override fun systemDirectory() = File(root, "system")
    override fun biosDirectory() = File(root, "bios")
    override fun coresDirectory() = File(root, "cores")
    override fun resolveCoreLibrary(context: Context, libraryFileName: String): File? = null
}
