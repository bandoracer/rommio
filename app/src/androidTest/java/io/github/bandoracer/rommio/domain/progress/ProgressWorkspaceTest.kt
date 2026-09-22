package io.github.bandoracer.rommio.domain.progress

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.bandoracer.rommio.data.local.AppDatabase
import io.github.bandoracer.rommio.domain.player.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ProgressWorkspaceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "workspace-${UUID.randomUUID()}"
    private val root = File(context.filesDir, name)
    private lateinit var db: AppDatabase
    private lateinit var store: ProgressStore
    private lateinit var source: PlayerSession
    private lateinit var identity: ProgressIdentity
    private val leases = mutableListOf<ProgressSessionLease>()
    private val device = UUID.randomUUID().toString()
    private val contract get() = MemoryOnlyPersistence("test", source.coreIdentity!!.sha256, "arm64-v8a",
        ProgressSaveCompatibility("test", "memory", "1", "test", false), setOf(PersistentMemory.SRAM), "test-v1", MemoryRestoreOrder.STATE_THEN_MEMORY)
    private fun snapshot(value: Byte = 1) = CoreMemorySnapshot(mapOf(PersistentMemory.SRAM to byteArrayOf(value), PersistentMemory.RTC to byteArrayOf()),
        null, false, emptyMap(), source.coreIdentity, source.firmwareSha256, identity.binding)

    @Before fun setup() = runBlocking {
        root.mkdirs()
        val inputs = File(root, "inputs").apply { mkdirs() }
        val rom = File(inputs, "game.nes").apply { writeText("original game") }
        val core = File(inputs, "core.so").apply { writeText("core fixture; no native execution in these file tests") }
        val system = File(inputs, "system").apply { mkdirs() }
        val bios = File(system, "bios.bin").apply { writeText("firmware") }
        val saves = File(inputs, "saves").apply { mkdirs() }
        val save = File(saves, "game.srm").apply { writeText("legacy progress") }
        source = PlayerSession(1, "Workspace fixture", rom, core, RuntimeProfile("test", "Test", setOf("test"), "core.so"),
            system, saves, save, File(inputs, "states").apply { mkdirs() },
            coreIdentity = CoreArtifactIdentity("test", "arm64-v8a", CoreBinary.sha256(core), core.length(), "fixture"),
            firmwareSha256 = mapOf("bios.bin" to CoreBinary.sha256(bios)),
            progressBinding = ProgressBinding("https://fixture.example", "account", 1,
                listOf(ProgressContentFile(1, 0, CoreBinary.sha256(rom), null, null))))
        open(); identity = store.resolveIdentity(source.progressBinding!!)
    }

    private fun open() { db = Room.databaseBuilder(context, AppDatabase::class.java, name).build(); store = ProgressStore(db, File(root, "store")) }
    private fun reopen() { store.close(); db.close(); open() }
    @After fun cleanup() = runBlocking {
        leases.forEach { runCatching { store.releaseSession(it) } }
        store.close(); db.close(); context.deleteDatabase(name); root.deleteRecursively()
        Unit
    }
    private suspend fun workspace() = store.prepareWorkspace(identity, source).also { leases += it.lease }
    private suspend fun reject(block: suspend () -> Unit) { assertNotNull(runCatching { block() }.exceptionOrNull()) }
    private fun writer(workspace: ProgressWorkspace, publish: Boolean = true) = SessionCheckpointWriter(store, identity, contract, device,
        workspace.sessionId, emptyMap(), workspace.stagingDirectory, { snapshot() }, publish)

    @Test fun isolatesRuntimeInputsAndNeverSeedsLegacyProgress() = runBlocking {
        val workspace = workspace()
        try {
            assertNotEquals(source.romPath, workspace.session.romPath)
            assertArrayEquals(source.romPath.readBytes(), workspace.session.romPath.readBytes())
            assertArrayEquals(File(source.systemDirectory, "bios.bin").readBytes(), File(workspace.session.systemDirectory, "bios.bin").readBytes())
            assertTrue(workspace.session.savesDirectory.listFiles()!!.isEmpty())
            assertNull(workspace.session.initialSaveRam)
            assertFalse(workspace.session.saveRamFile.exists())
            assertTrue(store.recoverWorkingSessions(identity).isEmpty()) // A live workspace is never orphaned.
            reject { store.prepareWorkspace(identity, source) }
            workspace.session.romPath.writeText("modified by core")
            File(workspace.session.systemDirectory, "bios.bin").writeText("changed firmware copy")
            assertEquals("original game", source.romPath.readText())
            assertEquals("firmware", File(source.systemDirectory, "bios.bin").readText())
            assertEquals("legacy progress", source.saveRamFile.readText())
        } finally { store.releaseSession(workspace.lease) }
        val recovery = store.recoverWorkingSessions(identity).single()
        assertNull(recovery.problem)
        assertEquals("modified by core", File(recovery.directory, "content/game.nes").readText())
        assertTrue(store.heads(identity, "save/current").isEmpty())
    }

    @Test fun cleanCloseRemovesOnlyWorkspaceAndAllowsFreshPreparation() = runBlocking {
        val first = workspace(); store.releaseSession(first.lease)
        assertFalse(first.directory.exists()); assertTrue(store.recoverWorkingSessions(identity).isEmpty())
        val second = workspace()
        assertNotEquals(first.sessionId, second.sessionId)
        store.releaseSession(first.lease)
        reject { store.prepareWorkspace(identity, source) }
        store.releaseSession(second.lease)
        assertTrue(source.romPath.isFile); assertTrue(source.saveRamFile.isFile)
        reject { store.prepareWorkspace(identity, source.copy(initialSaveRam = byteArrayOf(1))) }
    }

    @Test fun interruptedPreparationIsInventoriedAfterReopenWithoutAdoptingBytes() = runBlocking {
        source.romPath.writeText("changed installation")
        reject { workspace() }
        reopen()
        val recovery = store.recoverWorkingSessions(identity).single()
        assertNull(recovery.problem); assertNull(recovery.baselineCheckpointId)
        assertTrue(File(recovery.directory, "content/game.nes").exists())
        assertTrue(store.heads(identity, "save/current").isEmpty())
        assertEquals(recovery, store.recoverWorkingSessions(identity).single())
        store.discardAbandonedWorkspace(identity, recovery.sessionId)
        assertTrue(store.recoverWorkingSessions(identity).isEmpty())
        source.romPath.writeText("original game")
        val next = workspace(); store.releaseSession(next.lease)
    }

    @Test fun interruptedCaptureReplaysExactOperationAndPublicationIntentAfterReopen() = runBlocking {
        for (publish in listOf(false, true)) {
            val workspace = workspace()
            reject { writer(workspace, publish).captureAndCommit("manual", includeState = false) {
                if (it == ProgressCommitBoundary.ARTIFACTS_INSTALLED) error("process boundary fixture")
            } }
            val bytes = workspace.stagingDirectory.listFiles()!!.single().resolve("operation.json").readBytes()
            val original = ProgressProtocol.decode(bytes)
            store.releaseSession(workspace.lease); leases.clear(); reopen()
            val pending = store.pendingWorkspaceCheckpoints(identity, workspace.sessionId).single()
            assertNull(pending.problem); assertEquals(original.operationId, pending.operationId)
            // Ambiguous success is retried with the same envelope and cannot create a sibling revision.
            reject { store.recoverWorkspaceCheckpoint(identity, workspace.sessionId, pending.captureId) {
                if (it == ProgressCommitBoundary.TRANSACTION_COMMITTED) error("lost acknowledgment")
            } }
            assertEquals(original.operationId, store.recoverWorkspaceCheckpoint(identity, workspace.sessionId, pending.captureId))
            val row = db.progressDao().operation(identity.namespaceId, identity.contentId, original.operationId)!!
            assertArrayEquals(bytes, row.payload)
            assertEquals(if (publish) "PENDING" else "LOCAL_ONLY", store.outbox(identity).single { it.operationId == original.operationId }.status)
            assertTrue(store.pendingWorkspaceCheckpoints(identity, workspace.sessionId).isEmpty())
            store.discardAbandonedWorkspace(identity, workspace.sessionId)
        }
    }

    @Test fun damagedPendingJournalAndUnknownWorkspaceMetadataNeverPublishOrGetPruned() = runBlocking {
        val workspace = workspace()
        reject { writer(workspace).captureAndCommit("manual", includeState = false) { error("before commit") } }
        store.releaseSession(workspace.lease)
        val recovery = store.recoverWorkingSessions(identity).single()
        val capture = File(recovery.directory, "captures").listFiles()!!.single()
        File(capture, "operation.json").appendText("damaged")
        assertNotNull(store.pendingWorkspaceCheckpoints(identity, workspace.sessionId).single().problem)
        reject { store.recoverWorkspaceCheckpoint(identity, workspace.sessionId, capture.name) }
        assertTrue(store.outbox(identity).isEmpty())
        File(recovery.directory, "session.json").writeText("unknown metadata")
        assertNotNull(store.recoverWorkingSessions(identity).single().problem)
        reject { store.retainHistory(identity) }
        assertTrue(capture.isDirectory)
        store.discardAbandonedWorkspace(identity, workspace.sessionId)
        assertFalse(capture.exists())
    }

    @Test fun orphanBaselineIsProtectedUntilExplicitDiscard() = runBlocking {
        val workspace = workspace()
        val writer = writer(workspace)
        val first = writer.captureAndCommit("periodic", includeState = false)
        fun checkpointId() = first.checkpoint.checkpointId
        suspend fun publish() { val lease = store.leaseNext(identity, System.currentTimeMillis(), 1000)!!; assertTrue(store.acknowledge(lease, lease.payloadHash)) }
        publish()
        val ready = ProgressLaunchPlanner(store).plan(identity, contract, snapshot()) as ProgressLaunchDecision.Ready
        store.selectWorkspace(workspace, ready)
        File(workspace.session.savesDirectory, "unexpected.dat").writeText("native bytes for recovery")
        store.releaseSession(workspace.lease)
        var parents = mapOf("save/current" to listOf(first.operationId))
        repeat(14) { index ->
            val next = SessionCheckpointWriter(store, identity, contract, device, UUID.randomUUID().toString(), parents,
                File(root, "more-captures"), { snapshot((index + 2).toByte()) }, true).captureAndCommit("periodic", includeState = false)
            parents = mapOf("save/current" to listOf(next.operationId)); publish()
        }
        store.retainHistory(identity, System.currentTimeMillis() + 172_800_000)
        assertTrue(store.inspectCheckpoint(identity, checkpointId()).isLocallyAvailable)
        assertEquals(checkpointId(), store.recoverWorkingSessions(identity).single().baselineCheckpointId)
        store.discardAbandonedWorkspace(identity, workspace.sessionId)
        store.retainHistory(identity, System.currentTimeMillis() + 172_800_000)
        assertEquals(ProgressLocalRetention.EVICTED, store.inspectCheckpoint(identity, checkpointId()).retention)
    }

    @Test fun foreignRecoveryMetadataCannotPublishIntoAnotherAccount() = runBlocking {
        val workspace = workspace()
        reject { writer(workspace).captureAndCommit("manual", includeState = false) { error("interrupted") } }
        store.releaseSession(workspace.lease)
        val recovery = store.recoverWorkingSessions(identity).single()
        val other = store.resolveIdentity(identity.binding.copy(accountId = "other-account"))
        val foreign = File(root, "store/${other.namespaceId}/${other.contentId}/sessions/${workspace.sessionId}")
        recovery.directory.copyRecursively(foreign)
        assertNotNull(store.recoverWorkingSessions(other).single().problem)
        reject { store.pendingWorkspaceCheckpoints(other, workspace.sessionId) }
        assertTrue(store.outbox(other).isEmpty()); assertTrue(store.heads(other, "save/current").isEmpty())
        assertNull(store.pendingWorkspaceCheckpoints(identity, workspace.sessionId).single().problem)
    }

    @Test fun linkedOutputsAndUnprovenCleanupMarkerRetainFilesWithoutFollowingTargets() = runBlocking {
        val workspace = workspace()
        val outside = File(root, "outside").apply { mkdirs() }
        val retained = File(outside, "keep").apply { writeText("retain") }
        java.nio.file.Files.createSymbolicLink(File(workspace.session.savesDirectory, "linked").toPath(), outside.toPath())
        store.releaseSession(workspace.lease)
        assertEquals("retain", retained.readText())
        val recovery = store.recoverWorkingSessions(identity).single()
        val unproven = File(root, "store/${identity.namespaceId}/${identity.contentId}/sessions/.cleanup-${workspace.sessionId}")
        java.nio.file.Files.move(recovery.directory.toPath(), unproven.toPath())
        reject { store.recoverWorkingSessions(identity) }
        assertTrue(unproven.isDirectory); assertEquals("retain", retained.readText())
        java.nio.file.Files.move(unproven.toPath(), recovery.directory.toPath())
        store.discardAbandonedWorkspace(identity, workspace.sessionId)
        assertEquals("retain", retained.readText())
    }

    @Test fun interruptedCleanDeletionCanFinishAfterItsInternalRecordWasRemoved() = runBlocking {
        val workspace = workspace()
        File(workspace.session.savesDirectory, "retain-for-fixture").writeText("orphan")
        store.releaseSession(workspace.lease)
        val abandoned = store.recoverWorkingSessions(identity).single().directory
        File(abandoned, "saves/retain-for-fixture").delete()
        val closedRecord = File(abandoned, "session.json").readText().replace("\"phase\":\"prepared\"", "\"phase\":\"closed-clean\"")
        assertTrue(closedRecord.contains("closed-clean"))
        val content = abandoned.parentFile!!.parentFile!!
        val marker = File(content, "cleanup-markers/${workspace.sessionId}.json").apply { parentFile!!.mkdirs(); writeText(closedRecord) }
        val interruptedMarker = File(marker.parentFile, ".rommio-fixture.partial").apply { writeText("partial marker") }
        val cleanup = File(content, "sessions/.cleanup-${workspace.sessionId}")
        java.nio.file.Files.move(abandoned.toPath(), cleanup.toPath())
        File(cleanup, "session.json").delete() // Crash after one unlink, before the remaining tree and outer journal.
        leases.clear(); reopen()
        assertTrue(store.recoverWorkingSessions(identity).isEmpty())
        assertFalse(cleanup.exists()); assertFalse(marker.exists())
        assertTrue(interruptedMarker.exists()) // Incomplete control records cannot authorize deletion.
        assertTrue(source.romPath.exists()); assertEquals("legacy progress", source.saveRamFile.readText())
    }
}
