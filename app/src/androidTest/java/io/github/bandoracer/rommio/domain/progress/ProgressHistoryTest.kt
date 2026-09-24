package io.github.bandoracer.rommio.domain.progress

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.bandoracer.rommio.data.local.AppDatabase
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProgressHistoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "progress-history-${UUID.randomUUID()}"
    private val root = File(context.cacheDir, name)
    private lateinit var db: AppDatabase
    private lateinit var store: ProgressStore
    private lateinit var template: ProgressOperation
    private lateinit var identity: ProgressIdentity
    private val sources = mutableMapOf<String, File>()
    private fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_12_13, AppDatabase.MIGRATION_13_14).build()
    @Before fun setup() = runBlocking {
        root.mkdirs(); db = open(); store = ProgressStore(db, File(root, "store"))
        template = ProgressProtocol.decode(InstrumentationRegistry.getInstrumentation().context.assets.open("sram-only.json").use { it.readBytes() })
        identity = store.resolveIdentity(template.binding)
    }
    @After fun cleanup() { store.close(); db.close(); context.deleteDatabase(name); root.deleteRecursively() }
    private fun reopen() { store.close(); db.close(); db = open(); store = ProgressStore(db, File(root, "store")) }
    private suspend fun reject(block: suspend () -> Unit) { var e: Exception? = null; try { block() } catch (error: Exception) { e = error }; assertNotNull("Expected rejection", e) }
    private fun operation(parent: ProgressOperation? = null, reason: String = "periodic", reference: String = "save/current", bytes: ByteArray = UUID.randomUUID().toString().toByteArray()): ProgressOperation {
        val hash = ProgressProtocol.sha256(bytes)
        sources[hash] = File(root, "source-$hash").apply { writeBytes(bytes) }
        val checkpoint = template.checkpoints.single().copy(checkpointId = UUID.randomUUID().toString(), captureReason = reason,
            artifacts = listOf(ProgressArtifact(hash, bytes.size.toLong(), "sram", "save.srm", "libretro")))
        return template.copy(operationId = UUID.randomUUID().toString(), checkpoints = listOf(checkpoint),
            changes = listOf(ProgressRefChange(reference, listOfNotNull(parent?.operationId), checkpoint.checkpointId, false)))
    }
    private suspend fun commit(op: ProgressOperation, publish: Boolean = true) = store.commit(identity, ProgressProtocol.encode(op), sources, publish)
    private suspend fun publish(op: ProgressOperation) {
        val lease = requireNotNull(store.leaseNext(identity, System.currentTimeMillis(), 1000))
        assertEquals(op.operationId, lease.operationId); assertTrue(store.acknowledge(lease, lease.payloadHash))
    }
    private fun checkpointId(op: ProgressOperation) = op.checkpoints.single().checkpointId
    private fun blob(op: ProgressOperation) = File(root, "store/${identity.namespaceId}/${identity.contentId}/blobs/${op.checkpoints.single().artifacts.single().sha256}")
    private val future get() = System.currentTimeMillis() + 172_800_000L

    @Test fun activeBaselineSurvivesNewHeadsAndRetentionWithoutChangingUserPins() = runBlocking {
        val original = operation(); commit(original); publish(original)
        val lease = store.acquireSession(identity, checkpointId(original), mapOf("save/current" to listOf(original.operationId)))
        try {
            reject { store.acquireSession(identity, checkpointId(original), emptyMap()) }
            reject { store.close() }
            var enteredMaintenance = false
            reject { io.github.bandoracer.rommio.domain.storage.StorageAccess.exclusive { enteredMaintenance = true } }
            assertFalse(enteredMaintenance)
            var previous = original
            repeat(14) { val next = operation(previous); commit(next); publish(next); previous = next }
            store.pinCheckpoint(identity, checkpointId(original), false)
            store.retainHistory(identity, future)
            assertTrue(store.inspectCheckpoint(identity, checkpointId(original)).isLocallyAvailable)
            assertArrayEquals(sources[original.checkpoints.single().artifacts.single().sha256]!!.readBytes(), blob(original).readBytes())
            assertFalse(store.history(identity).entries.single { it.checkpoint.checkpointId == checkpointId(original) }.pinned)
        } finally { store.releaseSession(lease) }
        store.retainHistory(identity, future)
        assertFalse(blob(original).exists())
        val replacement = store.acquireSession(identity, null, emptyMap())
        try {
            store.releaseSession(lease) // A late old release must not clear a replacement reservation.
            reject { store.acquireSession(identity, null, emptyMap()) }
        } finally { store.releaseSession(replacement) }
        io.github.bandoracer.rommio.domain.storage.StorageAccess.exclusive { Unit }
        reopen()
    }

    @Test fun staleSelectionAndEvictedBaselineCannotReserveSession() = runBlocking {
        val original = operation(); commit(original); publish(original)
        val next = operation(original); commit(next); publish(next)
        reject { store.acquireSession(identity, checkpointId(original), mapOf("save/current" to listOf(original.operationId))) }
        val lease = store.acquireSession(identity, checkpointId(next), mapOf("save/current" to listOf(next.operationId)))
        store.releaseSession(lease)
        var previous = next
        repeat(12) { val op = operation(previous); commit(op); publish(op); previous = op }
        store.retainHistory(identity, future)
        reject { store.acquireSession(identity, checkpointId(original), emptyMap()) }
        val fresh = store.acquireSession(identity, checkpointId(previous), mapOf("save/current" to listOf(previous.operationId)))
        store.releaseSession(fresh)
    }

    @Test fun boundedHistoryUsesCommitOrderAndBackfillsSchemaTwelveWithoutChangingHeads() = runBlocking {
        val records = mutableListOf<ProgressOperation>()
        repeat(135) { index ->
            val op = operation(records.lastOrNull()).let { it.copy(checkpoints = it.checkpoints.map { cp -> cp.copy(createdAtEpochMs = 1000L - index) }) }
            commit(op); records.add(op)
        }
        val head = store.heads(identity, "save/current")
        store.close()
        val raw = db.openHelper.writableDatabase
        io.github.bandoracer.rommio.data.local.restoreVersion12ProgressTables(raw)
        raw.execSQL("DELETE FROM room_master_table"); raw.version = 12
        db.close(); db = open(); store = ProgressStore(db, File(root, "store"))
        var cursor = Long.MAX_VALUE; val ids = mutableListOf<String>()
        do {
            val page = store.history(identity, cursor, 17)
            assertTrue(page.entries.size <= 17)
            ids.addAll(page.entries.map { it.checkpoint.checkpointId })
            cursor = page.beforeSequence ?: 0
        } while (cursor > 0)
        assertEquals(records.reversed().map(::checkpointId), ids)
        assertEquals(head, store.heads(identity, "save/current")); assertEquals(135, store.outbox(identity).size)
        reopen(); assertEquals(135, db.progressDao().operations(identity.namespaceId, identity.contentId).size)
        assertEquals(checkpointId(records.last()), store.history(identity, limit = 1).entries.single().checkpoint.checkpointId)
        store.close(); db.openHelper.writableDatabase.execSQL("VACUUM"); store = ProgressStore(db, File(root, "store"))
        val next = operation(records.last()); commit(next)
        assertEquals(checkpointId(next), store.history(identity, limit = 1).entries.single().checkpoint.checkpointId)
    }

    @Test fun damagedArtifactRepairPreservesOriginalAndDoesNotAdvanceProgress() = runBlocking {
        val op = operation(); commit(op)
        val heads = store.heads(identity, "save/current"); val outbox = store.outbox(identity)
        blob(op).writeText("damaged")
        assertFalse(store.inspectCheckpoint(identity, checkpointId(op)).isLocallyAvailable)
        val badSource = File(root, "bad-source").apply { writeText("wrong") }
        reject { store.repairCheckpoint(identity, checkpointId(op), mapOf(op.checkpoints.single().artifacts.single().sha256 to badSource)) }
        assertEquals("damaged", blob(op).readText())
        reject { store.repairCheckpoint(identity, checkpointId(op), sources) { if (it == "BACKUP_RETAINED") error("Interrupted repair") } }
        assertEquals("damaged", blob(op).readText()); reopen()
        store.repairCheckpoint(identity, checkpointId(op), sources)
        assertTrue(store.inspectCheckpoint(identity, checkpointId(op)).isLocallyAvailable)
        val backups = File(root, "store/${identity.namespaceId}/${identity.contentId}/repair-backups").walkTopDown().filter { it.isFile }.toList()
        assertTrue(backups.isNotEmpty()); assertTrue(backups.all { it.readText() == "damaged" })
        assertEquals(heads, store.heads(identity, "save/current")); assertEquals(outbox, store.outbox(identity))
    }

    @Test fun operationRepairRequiresOriginalDigestAndQuarantinesDamagedEnvelope() = runBlocking {
        val op = operation(); val payload = ProgressProtocol.encode(op); commit(op)
        db.progressDao().replaceOperationPayload(identity.namespaceId, identity.contentId, op.operationId, "broken".toByteArray())
        reject { store.checkpoint(identity, "save/current") }
        reject { store.repairOperation(identity, op.operationId, ProgressProtocol.encode(op.copy(createdAtEpochMs = 1))) }
        store.repairOperation(identity, op.operationId, payload); reopen()
        assertEquals(checkpointId(op), store.checkpoint(identity, "save/current")!!.checkpointId)
        assertEquals("PENDING", store.outbox(identity).single().status)
        db.openHelper.readableDatabase.query("SELECT payload, reason FROM progress_v2_quarantine").use {
            assertTrue(it.moveToFirst()); assertEquals("broken", String(it.getBlob(0))); assertTrue(it.getString(1).contains(op.operationId))
        }
    }

    @Test fun retentionKeepsTenAutomaticCheckpointsAndRehydratesOldHistoryWithoutChangingHeads() = runBlocking {
        val records = mutableListOf<ProgressOperation>()
        repeat(14) { val op = operation(records.lastOrNull()); commit(op); publish(op); records.add(op) }
        val result = store.retainHistory(identity, future)
        assertEquals(4, result.evictedCheckpoints); assertEquals(4, result.deletedBlobs)
        records.take(4).forEach { assertFalse(blob(it).exists()) }
        records.takeLast(10).forEach { assertTrue(blob(it).exists()) }
        assertEquals(14, db.progressDao().operations(identity.namespaceId, identity.contentId).size)
        reject { store.materializeCheckpoint(identity, checkpointId(records.first()), UUID.randomUUID().toString()) }
        assertEquals(ProgressLocalRetention.EVICTED, store.inspectCheckpoint(identity, checkpointId(records.first())).retention)
        store.repairCheckpoint(identity, checkpointId(records.first()), sources)
        val working = store.materializeCheckpoint(identity, checkpointId(records.first()), UUID.randomUUID().toString())
        assertArrayEquals(blob(records.first()).readBytes(), File(working, "save.srm").readBytes())
        File(working, "save.srm").writeText("running game")
        assertTrue(store.inspectCheckpoint(identity, checkpointId(records.first())).isLocallyAvailable)
        assertEquals(listOf(records.last().operationId), store.heads(identity, "save/current"))
        assertTrue(store.history(identity).entries.single { it.checkpoint.checkpointId == checkpointId(records.first()) }.pinned)
    }

    @Test fun sharedBlobSurvivesWhenOlderCheckpointIsEvicted() = runBlocking {
        val records = mutableListOf<ProgressOperation>(); val shared = "same save bytes".toByteArray()
        repeat(13) { index ->
            val op = operation(records.lastOrNull(), bytes = if (index == 0 || index == 12) shared else "save-$index".toByteArray())
            commit(op); publish(op); records.add(op)
        }
        val result = store.retainHistory(identity, future)
        assertEquals(3, result.evictedCheckpoints); assertEquals(2, result.deletedBlobs)
        assertArrayEquals(shared, blob(records.first()).readBytes())
        assertTrue(store.inspectCheckpoint(identity, checkpointId(records.last())).isLocallyAvailable)
        assertEquals(ProgressLocalRetention.EVICTED, store.inspectCheckpoint(identity, checkpointId(records.first())).retention)
        store.repairCheckpoint(identity, checkpointId(records.first()), emptyMap())
        assertTrue(store.inspectCheckpoint(identity, checkpointId(records.first())).isLocallyAvailable)
    }

    @Test fun everyUnpublishedAncestorIsProtectedBeyondTheAutomaticWindow() = runBlocking {
        val all = mutableListOf<ProgressOperation>()
        for (publish in listOf(false, true)) {
            var parent: ProgressOperation? = null
            repeat(15) {
                val op = operation(parent); commit(op, publish = publish); all.add(op); parent = op
            }
        }
        val result = store.retainHistory(identity, future)
        assertEquals(0, result.evictedCheckpoints); assertEquals(0, result.deletedBlobs)
        assertTrue(all.all { blob(it).exists() })
        assertEquals(setOf("PENDING", "LOCAL_ONLY"), store.outbox(identity).map { it.status }.toSet())
    }

    @Test fun manualPinnedUnpublishedAndConflictingBranchesSurviveRetention() = runBlocking {
        val original = operation(); commit(original); publish(original)
        val otherBranch = operation(original); commit(otherBranch); publish(otherBranch)
        val branch = operation(original); commit(branch); publish(branch)
        val slot = "slot/${UUID.randomUUID()}"
        val manual = operation(reason = "manual", reference = slot); commit(manual); publish(manual)
        val deletion = manual.copy(operationId = UUID.randomUUID().toString(), checkpoints = emptyList(), changes = listOf(ProgressRefChange(slot, listOf(manual.operationId), null, true)))
        commit(deletion); publish(deletion)
        store.pinCheckpoint(identity, checkpointId(original), true)
        var previous = branch
        repeat(14) { val next = operation(previous); commit(next); publish(next); previous = next }
        val localOnly = operation(previous); commit(localOnly, publish = false)
        val pending = operation(localOnly); commit(pending)
        store.retainHistory(identity, future)
        for (op in listOf(original, otherBranch, manual, localOnly, pending)) assertTrue(blob(op).exists())
        assertEquals(2, store.heads(identity, "save/current").size)
        reject { store.checkpoint(identity, "save/current") }
        assertEquals(listOf(deletion.operationId), store.heads(identity, slot))
    }

    @Test fun resolutionPinsBothPriorBranchesAndDoesNotChooseByTimestamp() = runBlocking {
        val left = operation(); commit(left); publish(left)
        val right = operation(); commit(right); publish(right)
        val resolution = operation().let { it.copy(changes = it.changes.map { c -> c.copy(parents = listOf(left.operationId, right.operationId)) }) }
        commit(resolution); publish(resolution)
        val entries = store.history(identity).entries.associateBy { it.checkpoint.checkpointId }
        assertTrue(entries.getValue(checkpointId(left)).pinned); assertTrue(entries.getValue(checkpointId(right)).pinned)
        assertEquals(listOf(resolution.operationId), store.heads(identity, "save/current"))
        store.pinCheckpoint(identity, checkpointId(left), false); reopen(); store.rebuildHistoryIndex(identity)
        val rebuilt = store.history(identity).entries.associateBy { it.checkpoint.checkpointId }
        assertFalse(rebuilt.getValue(checkpointId(left)).pinned)
        assertTrue(rebuilt.getValue(checkpointId(right)).pinned)
    }

    @Test fun interruptedRetentionAndStagingCleanupAreIdempotentAndDoNotTouchWorkingSessions() = runBlocking {
        val records = mutableListOf<ProgressOperation>()
        repeat(12) { val op = operation(records.lastOrNull()); commit(op); publish(op); records.add(op) }
        val working = store.materialize(identity, "save/current", UUID.randomUUID().toString())!!
        val content = File(root, "store/${identity.namespaceId}/${identity.contentId}")
        val abandoned = File(content, "working/.staging-${UUID.randomUUID()}").apply { mkdirs() }
        File(abandoned, "partial").writeText("incomplete")
        val external = File(root, "external-data").apply { mkdirs() }
        File(external, "retain").writeText("outside the staging tree")
        java.nio.file.Files.createSymbolicLink(File(abandoned, "link").toPath(), external.toPath())
        val orphan = File(content, "blobs/" + "f".repeat(64)).apply { writeText("unreferenced") }
        reject { store.retainHistory(identity, future) { if (it == "RETENTION_COMMITTED") error("Interrupted GC") } }
        assertTrue(blob(records.first()).exists()); reopen()
        assertEquals(ProgressLocalRetention.EVICTED, store.inspectCheckpoint(identity, checkpointId(records.first())).retention)
        store.retainHistory(identity, future)
        assertFalse(orphan.exists()); assertFalse(abandoned.exists()); assertTrue(working.exists())
        assertEquals("outside the staging tree", File(external, "retain").readText())
        assertNotNull(store.checkpoint(identity, "save/current"))
        assertEquals(0, store.retainHistory(identity, future).deletedBlobs)
    }

    @Test fun cancellationAtCommitBoundariesLeavesOnlyCompleteRecoverableProgress() = runBlocking {
        val old = operation(); commit(old)
        val interrupted = operation(old)
        val beforeCommit = launch {
            val owner = currentCoroutineContext()[Job]!!
            store.commit(identity, ProgressProtocol.encode(interrupted), sources) { boundary ->
                if (boundary == ProgressCommitBoundary.ARTIFACTS_INSTALLED) owner.cancel()
            }
        }
        beforeCommit.join()
        assertTrue(beforeCommit.isCancelled)
        assertEquals(listOf(old.operationId), store.heads(identity, "save/current"))
        assertEquals(listOf(old.operationId), store.outbox(identity).map { it.operationId })
        val committed = operation(old)
        val afterCommit = launch {
            val owner = currentCoroutineContext()[Job]!!
            store.commit(identity, ProgressProtocol.encode(committed), sources) { boundary ->
                if (boundary == ProgressCommitBoundary.TRANSACTION_COMMITTED) owner.cancel()
            }
        }
        afterCommit.join(); assertTrue(afterCommit.isCancelled); reopen()
        assertEquals(listOf(committed.operationId), store.heads(identity, "save/current"))
        assertTrue(store.outbox(identity).any { it.operationId == committed.operationId })
        assertTrue(store.inspectCheckpoint(identity, checkpointId(committed)).isLocallyAvailable)
    }

    @Test fun missingDerivedIndexPreventsCollectionAndRebuildRetainsExistingPins() = runBlocking {
        val original = operation(); commit(original); publish(original)
        store.pinCheckpoint(identity, checkpointId(original), true)
        val pending = operation(original); commit(pending)
        store.history(identity) // Advance the durable index watermark before injecting a missing old row.
        db.openHelper.writableDatabase.execSQL("DELETE FROM progress_v2_history WHERE checkpointId = ?", arrayOf(checkpointId(pending)))
        reject { store.retainHistory(identity, future) }
        assertTrue(blob(original).exists()); assertTrue(blob(pending).exists())
        store.rebuildHistoryIndex(identity)
        assertTrue(store.history(identity).entries.single { it.checkpoint.checkpointId == checkpointId(original) }.pinned)
        assertTrue(store.history(identity).entries.any { it.checkpoint.checkpointId == checkpointId(pending) })
        store.retainHistory(identity, future)
        assertTrue(blob(original).exists()); assertTrue(blob(pending).exists())
        // Rebuilding an older missing entry restores its original position, rather than appending it as newest.
        val tail = operation(pending); commit(tail)
        db.openHelper.writableDatabase.execSQL("DELETE FROM progress_v2_history WHERE checkpointId = ?", arrayOf(checkpointId(pending)))
        store.rebuildHistoryIndex(identity)
        assertEquals(listOf(checkpointId(tail), checkpointId(pending), checkpointId(original)), store.history(identity).entries.map { it.checkpoint.checkpointId })
    }

    @Test fun gracePinsAccountBoundariesAndClosedOwnerPreventUnsafeRemoval() = runBlocking {
        val records = mutableListOf<ProgressOperation>()
        repeat(12) { val op = operation(records.lastOrNull()); commit(op); publish(op); records.add(op) }
        assertEquals(0, store.retainHistory(identity).evictedCheckpoints)
        store.pinCheckpoint(identity, checkpointId(records.first()), true); reopen()
        assertTrue(store.history(identity).entries.single { it.checkpoint.checkpointId == checkpointId(records.first()) }.pinned)
        val other = store.resolveIdentity(identity.binding.copy(accountId = "other"))
        reject { store.repairCheckpoint(other, checkpointId(records.first()), sources) }
        assertTrue(store.history(other).entries.isEmpty())
        val outside = File(root, "outside").apply { writeText("must survive") }
        val target = blob(records.first()); target.delete(); java.nio.file.Files.createSymbolicLink(target.toPath(), outside.toPath())
        reject { store.repairCheckpoint(identity, checkpointId(records.first()), sources) }
        reject { store.retainHistory(identity, future) }
        assertEquals("must survive", outside.readText())
        store.close(); reject { store.history(identity) }
    }
}
