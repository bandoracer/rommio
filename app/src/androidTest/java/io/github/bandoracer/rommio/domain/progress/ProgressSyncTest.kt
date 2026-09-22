package io.github.bandoracer.rommio.domain.progress

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.bandoracer.rommio.data.local.AppDatabase
import io.github.bandoracer.rommio.domain.player.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID

class ProgressSyncTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun id() = UUID.randomUUID().toString()
    private val binding = ProgressBinding("https://sync.example", "1", 1,
        listOf(ProgressContentFile(1, 0, "a".repeat(64), null, null)))
    private val core = CoreArtifactIdentity("test", "arm64-v8a", "b".repeat(64), 1, "test")
    private val qualification = MemoryOnlyPersistence("test", core.sha256, core.abi,
        ProgressSaveCompatibility("nes", "memory", "1", "test", false), PersistentMemory.STANDARD,
        "test-v1", MemoryRestoreOrder.STATE_THEN_MEMORY)
    private fun snapshot(value: Int = 1, state: Boolean = true) = CoreMemorySnapshot(
        mapOf(PersistentMemory.SRAM to byteArrayOf(value.toByte()), PersistentMemory.RTC to byteArrayOf(2)),
        if (state) byteArrayOf(value.toByte(), 3) else null, !state, emptyMap(), core, emptyMap(), binding)
    private inner class Client(val root: File) : AutoCloseable {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val store = ProgressStore(db, File(root, "store"))
        lateinit var identity: ProgressIdentity
        var current = snapshot()
        suspend fun open() { identity = store.resolveIdentity(binding) }
        suspend fun writer(version: Int = 3) = SessionCheckpointWriter(store, identity, qualification, id(), id(),
            store.referenceSnapshot(identity).mapValues { it.value.map { head -> head.operationId } }, File(root, "stage"),
            { include -> current.copy(state = if (include) current.state else null, stateRejected = include && current.stateRejected) }, true, version)
        suspend fun sync(remote: ProgressTransport) = ProgressSync(store, File(root, "transfer")).synchronize(identity, remote)
        override fun close() { store.close(); db.close() }
    }
    private class Remote : ProgressTransport {
        val records = linkedMapOf<String, Pair<Int, ByteArray>>()
        var failStateHash: String? = null
        var lostResponse = false
        var readCorruption = false
        override suspend fun inventory() = synchronized(records) { records.map { ProgressRemoteRecord(it.key, it.value.first, it.value.second.size.toLong()) }.reversed() }
        override suspend fun read(record: ProgressRemoteRecord, destination: File, maxBytes: Long) {
            val bytes = synchronized(records) { records.getValue(record.name).second.copyOf() }
            require(bytes.size <= maxBytes)
            destination.writeBytes(if (readCorruption) bytes + 1 else bytes)
        }
        override suspend fun upload(name: String, source: File): ProgressRemoteRecord {
            if (failStateHash?.let { name.contains(it) } == true) throw IOException("State transfer interrupted")
            val bytes = source.readBytes()
            val record = synchronized(records) {
                val previous = records[name]
                if (previous != null) assertArrayEquals(previous.second, bytes)
                records[name] = (previous?.first ?: (records.size + 1)) to bytes
                ProgressRemoteRecord(name, records.getValue(name).first, bytes.size.toLong())
            }
            if (lostResponse) { lostResponse = false; throw IOException("Response lost after upload") }
            return record
        }
    }
    private suspend fun clients(block: suspend (Client, Client, Remote) -> Unit) {
        val root = File(context.filesDir, "sync-${id()}").apply { mkdirs() }
        val a = Client(File(root, "a").apply { mkdirs() }); val b = Client(File(root, "b").apply { mkdirs() })
        try { a.open(); b.open(); block(a, b, Remote()) } finally { a.close(); b.close(); root.deleteRecursively() }
    }
    @Test fun bothLanesRoundTripWithExactPairingAndNoLocalNamespaceLeak() = runBlocking { clients { a, b, remote ->
        val saved = a.writer().captureAndCommit("manual", slotId = id())
        assertEquals(0, a.sync(remote).statePending)
        assertTrue(b.sync(remote).errors.isEmpty())
        assertNotEquals(a.identity.namespaceId, b.identity.namespaceId)
        assertEquals(a.store.referenceSnapshot(a.identity), b.store.referenceSnapshot(b.identity))
        val game = b.store.checkpoint(b.identity, "game/current")!!
        assertNull(game.stateCompatibility)
        assertEquals(ProgressProtocol.gameArtifacts(game), ProgressProtocol.gameArtifacts(saved.checkpoint))
        val restored = MemoryCheckpointLoader(b.store, b.identity, qualification).load(saved.checkpoint.checkpointId, snapshot(), true)
        assertArrayEquals(snapshot().state, restored.state)
        assertArrayEquals(snapshot().memory[PersistentMemory.SRAM], restored.memory[PersistentMemory.SRAM])
    } }
    @Test fun failedStateUploadDoesNotBlockLaterGameSavesOrColdBoot() = runBlocking { clients { a, b, remote ->
        val writer = a.writer(); val first = writer.captureAndCommit("pause")
        remote.failStateHash = first.checkpoint.artifacts.single { it.role == "state" }.sha256
        assertEquals(1, a.sync(remote).statePending)
        a.current = snapshot(9, false)
        val later = writer.captureAndCommit("periodic", false)
        val result = a.sync(remote)
        assertEquals(0, result.gamePending); assertEquals(1, result.statePending)
        b.sync(remote)
        val choice = ProgressLaunchPlanner(b.store).plan(b.identity, qualification, snapshot(), ProgressLaunchRequest.ColdBootCurrent)
        assertTrue(choice is ProgressLaunchDecision.Ready && !choice.useState)
        assertEquals(later.checkpoint.checkpointId, (choice as ProgressLaunchDecision.Ready).checkpointId)
        remote.failStateHash = null
        // Backoff is durable; explicitly make the interrupted lease eligible after reconnect.
        a.store.resumeAuthenticatedWork(a.identity)
        for (row in a.db.progressDao().outbox(a.identity.namespaceId, a.identity.contentId)) if (row.status == "PENDING") a.db.progressDao().updateOutbox(row.copy(nextAttempt = 0))
        assertEquals(0, a.sync(remote).statePending); b.sync(remote)
        assertEquals(first.checkpoint, b.store.checkpoint(b.identity, first.resumeReference!!))
        assertFalse((ProgressLaunchPlanner(b.store).plan(b.identity, qualification, snapshot()) as ProgressLaunchDecision.Ready).useState)
    } }
    @Test fun offlineDivergenceAndLateDiscoveryPreserveBothGameAndSnapshotBranches() = runBlocking { clients { a, b, remote ->
        a.writer().captureAndCommit("pause"); a.sync(remote); b.sync(remote)
        val wa = a.writer(); val wb = b.writer()
        a.current = snapshot(5); b.current = snapshot(8)
        val aa = wa.captureAndCommit("pause"); val bb = wb.captureAndCommit("pause")
        a.sync(remote); b.sync(remote); a.sync(remote)
        assertEquals(2, a.store.heads(a.identity, "game/current").size)
        assertEquals(setOf(aa.operationId, bb.operationId), a.store.heads(a.identity, aa.resumeReference!!).toSet())
        assertTrue(ProgressLaunchPlanner(a.store).plan(a.identity, qualification, snapshot()) is ProgressLaunchDecision.ChooseBranch)
        val selected = ProgressLaunchPlanner(a.store).plan(a.identity, qualification, snapshot(), ProgressLaunchRequest.ContinueBranch(aa.checkpoint.gameSaveOperationId!!, true)) as ProgressLaunchDecision.Ready
        assertEquals(aa.checkpoint.checkpointId, selected.checkpointId)
        // Receiving a sibling never silently changes the existing writer's frozen parents.
        a.current = snapshot(7); wa.captureAndCommit("pause"); a.sync(remote); b.sync(remote)
        assertEquals(2, b.store.heads(b.identity, "game/current").size)
    } }
    @Test fun incompatibleCoreRejectsSnapshotButPreservesColdGameSave() = runBlocking { clients { a, b, remote ->
        a.writer().captureAndCommit("pause"); a.sync(remote); b.sync(remote)
        val other = core.copy(sha256 = "c".repeat(64))
        val contract = qualification.copy(coreSha256 = other.sha256)
        val actual = snapshot().copy(coreIdentity = other)
        assertTrue(ProgressLaunchPlanner(b.store).plan(b.identity, contract, actual) is ProgressLaunchDecision.OfferColdBoot)
        assertFalse((ProgressLaunchPlanner(b.store).plan(b.identity, contract, actual, ProgressLaunchRequest.ColdBootCurrent) as ProgressLaunchDecision.Ready).useState)
    } }
    @Test fun failedStateCapturePreservesManualSlotAndGameSaveStillAdvances() = runBlocking { clients { a, _, _ ->
        val writer = a.writer(); val slot = id(); val first = writer.captureAndCommit("manual", slotId = slot)
        a.current = snapshot(8, false)
        val later = writer.captureAndCommit("manual", slotId = slot)
        assertTrue(later.stateRejected)
        assertEquals(listOf(first.operationId), a.store.heads(a.identity, "state/slot/$slot"))
        assertEquals(later.checkpoint, a.store.checkpoint(a.identity, "game/current"))
        assertFalse(writer.hasPendingCommit())
    } }
    @Test fun stateCommitFailurePreservesSlotAndDoesNotBlockNextGameCommit() = runBlocking { clients { a, _, _ ->
        val writer = a.writer(); val slot = id(); val first = writer.captureAndCommit("manual", slotId = slot)
        a.current = snapshot(8)
        var transactions = 0
        val partial = writer.captureAndCommit("manual", slotId = slot) {
            if (it == ProgressCommitBoundary.OPERATION_INSERTED && ++transactions == 2) throw IOException("State disk full")
        }
        assertTrue(partial.stateRejected); assertFalse(writer.hasPendingCommit())
        assertEquals(listOf(first.operationId), a.store.heads(a.identity, "state/slot/$slot"))
        assertEquals(partial.checkpoint, a.store.checkpoint(a.identity, "game/current"))
        assertFalse(writer.captureAndCommit("periodic", false).stateRejected)
    } }
    @Test fun stateCommitWithLostAcknowledgementIsReportedAsDurable() = runBlocking { clients { a, _, _ ->
        var transactions = 0
        val result = a.writer().captureAndCommit("manual", slotId = id()) {
            if (it == ProgressCommitBoundary.TRANSACTION_COMMITTED && ++transactions == 2) throw IOException("Acknowledgement lost")
        }
        assertFalse(result.stateRejected)
        assertEquals(result.checkpoint, a.store.checkpoint(a.identity, result.resumeReference!!))
    } }
    @Test fun interruptedUploadRetriesIdenticalBytesAndVerifiesReadback() = runBlocking { clients { a, b, remote ->
        a.writer().captureAndCommit("pause"); remote.lostResponse = true
        assertTrue(a.sync(remote).errors.isNotEmpty())
        for (row in a.db.progressDao().outbox(a.identity.namespaceId, a.identity.contentId)) if (row.status == "PENDING") a.db.progressDao().updateOutbox(row.copy(nextAttempt = 0))
        assertTrue(a.sync(remote).errors.isEmpty()); assertTrue(b.sync(remote).errors.isEmpty())
        assertEquals(2, b.store.operationPayloads(b.identity).size)
    } }
    @Test fun corruptRemoteManifestNeverAdvancesLocalHeads() = runBlocking { clients { a, b, remote ->
        a.writer().captureAndCommit("pause"); a.sync(remote)
        remote.readCorruption = true
        assertTrue(b.sync(remote).errors.isNotEmpty())
        assertTrue(b.store.referenceSnapshot(b.identity).isEmpty())
    } }
    @Test fun upgradeKeepsOriginalBytesAndIsIdempotent() = runBlocking { clients { a, b, remote ->
        val old = a.writer(2).captureAndCommit("manual", slotId = id())
        val original = a.store.operationPayloads(a.identity).single()
        ProgressUpgrade(a.store).upgrade(a.identity)
        assertEquals(3, a.store.operationPayloads(a.identity).size)
        ProgressUpgrade(a.store).upgrade(a.identity)
        assertEquals(3, a.store.operationPayloads(a.identity).size)
        assertArrayEquals(original, a.store.operationPayloads(a.identity).first { ProgressProtocol.decode(it).operationId == old.operationId })
        a.sync(remote); b.sync(remote)
        assertEquals(2, b.store.operationPayloads(b.identity).size)
        assertTrue((ProgressLaunchPlanner(b.store).plan(b.identity, qualification, snapshot()) as ProgressLaunchDecision.Ready).useState)
    } }
    @Test fun explicitConflictChoicePinsAlternativesAndConverges() = runBlocking { clients { a, b, remote ->
        val first = a.writer().captureAndCommit("pause"); a.sync(remote); b.sync(remote)
        val wa = a.writer(); val wb = b.writer()
        a.current = snapshot(5); b.current = snapshot(8)
        val aa = wa.captureAndCommit("pause"); val bb = wb.captureAndCommit("pause")
        a.sync(remote); b.sync(remote); a.sync(remote)
        val heads = a.store.heads(a.identity, "game/current")
        a.store.resolveReference(a.identity, "game/current", aa.checkpoint.gameSaveOperationId!!, heads, id(), id())
        a.sync(remote); b.sync(remote)
        assertEquals(1, b.store.heads(b.identity, "game/current").size)
        assertEquals(aa.checkpoint.gameSaveOperationId, (ProgressLaunchPlanner(b.store).plan(b.identity, qualification, snapshot()) as ProgressLaunchDecision.Ready).let {
            b.store.history(b.identity).entries.single { row -> row.checkpoint.checkpointId == it.checkpointId }.checkpoint.gameSaveOperationId
        })
        assertTrue(b.store.history(b.identity).entries.filter { it.operationId in setOf(aa.checkpoint.gameSaveOperationId, bb.checkpoint.gameSaveOperationId) }.all { it.pinned })
        assertNotNull(first.resumeReference)
    } }
    @Test fun knownCorruptSnapshotRepairsFromVerifiedRemoteBytesWithoutChangingHeads() = runBlocking { clients { a, b, remote ->
        val saved = a.writer().captureAndCommit("pause"); a.sync(remote); b.sync(remote)
        val heads = b.store.referenceSnapshot(b.identity)
        val state = saved.checkpoint.artifacts.single { it.role == "state" }
        File(b.root, "store/${b.identity.namespaceId}/${b.identity.contentId}/blobs/${state.sha256}").writeText("damaged")
        assertTrue(b.sync(remote).errors.isEmpty())
        assertEquals(heads, b.store.referenceSnapshot(b.identity))
        assertTrue(b.store.inspectCheckpoint(b.identity, saved.checkpoint.checkpointId).isLocallyAvailable)
        assertTrue(File(b.root, "store/${b.identity.namespaceId}/${b.identity.contentId}/repair-backups/${state.sha256}").listFiles()!!.isNotEmpty())
    } }
    @Test fun migrationBundlePreservesIndependentDependenciesAndExactEnvelopes() = runBlocking { clients { a, _, _ ->
        a.writer().captureAndCommit("manual", slotId = id())
        val bundle = io.github.bandoracer.rommio.data.migration.ProgressBundle(a.db.progressDao()).snapshot(File(a.root, "store"))
        io.github.bandoracer.rommio.data.migration.ProgressBundle.validate(bundle)
        assertEquals(2, bundle.operations.size)
        val before = a.store.operationPayloads(a.identity).map { ProgressProtocol.sha256(it) }.toSet()
        io.github.bandoracer.rommio.data.migration.ProgressBundle(a.db.progressDao()).restore(bundle)
        assertEquals(before, a.store.operationPayloads(a.identity).map { ProgressProtocol.sha256(it) }.toSet())
    } }

}
