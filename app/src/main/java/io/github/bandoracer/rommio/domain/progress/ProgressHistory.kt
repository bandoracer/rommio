package io.github.bandoracer.rommio.domain.progress

import androidx.room.withTransaction
import io.github.bandoracer.rommio.data.local.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** RETAINED is retention intent; only inspection/materialization verifies artifact health. */
enum class ProgressLocalRetention { RETAINED, EVICTED }
data class ProgressHistoryEntry(
    val sequence: Long, val operationId: String, val checkpoint: ProgressCheckpoint,
    val pinned: Boolean, val manual: Boolean, val retention: ProgressLocalRetention,
)
data class ProgressHistoryPage(val entries: List<ProgressHistoryEntry>, val beforeSequence: Long?)
data class ProgressArtifactIssue(val sha256: String, val reason: String)
data class ProgressCheckpointHealth(val checkpointId: String, val retention: ProgressLocalRetention, val issues: List<ProgressArtifactIssue>) {
    val isLocallyAvailable: Boolean get() = retention == ProgressLocalRetention.RETAINED && issues.isEmpty()
}
data class ProgressRetentionResult(val evictedCheckpoints: Int, val deletedBlobs: Int, val deletedStaging: Int, val retainedBlobCount: Int)

/** Called only under the store's exclusive owner/mutex. This index never changes causal heads. */
internal class ProgressHistoryIndex(private val database: AppDatabase) {
    private val dao = database.progressDao()

    fun decode(identity: ProgressIdentity, row: ProgressOperationRow): ProgressOperation {
        require(ProgressProtocol.sha256(row.payload) == row.payloadHash) { "Corrupt operation ${row.operationId}" }
        return ProgressProtocol.decode(row.payload).also { require(it.binding == identity.binding && it.operationId == row.operationId) }
    }

    suspend fun operation(identity: ProgressIdentity, operationId: String): ProgressOperation =
        decode(identity, requireNotNull(dao.operation(identity.namespaceId, identity.contentId, operationId)) { "Missing operation $operationId" })

    suspend fun checkpoint(identity: ProgressIdentity, row: ProgressHistoryRow): ProgressCheckpoint =
        operation(identity, row.operationId).checkpoints.single { it.checkpointId == row.checkpointId }

    suspend fun ensure(identity: ProgressIdentity, now: Long) {
        var after = dao.historyIndex(identity.namespaceId, identity.contentId)?.throughRowId ?: 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = dao.operationPage(identity.namespaceId, identity.contentId, after, 64)
            if (page.isEmpty()) return
            database.withTransaction {
                page.forEach { entry -> index(identity, decode(identity, entry.operation), now) }
                after = page.last().localRowId
                dao.updateHistoryIndex(ProgressHistoryIndexRow(identity.namespaceId, identity.contentId, after))
            }
        }
    }

    suspend fun index(identity: ProgressIdentity, operation: ProgressOperation, now: Long) {
        val committedSequence = requireNotNull(dao.operation(identity.namespaceId, identity.contentId, operation.operationId)).localSequence
        check(committedSequence in 1..Long.MAX_VALUE / 64) { "Invalid local operation sequence" }
        for ((position, checkpoint) in operation.checkpoints.withIndex()) {
            val existing = dao.historyCheckpoint(identity.namespaceId, identity.contentId, checkpoint.checkpointId)
            val manual = checkpoint.captureReason == "manual" || operation.changes.any { it.checkpointId == checkpoint.checkpointId && (it.reference.startsWith("slot/") || it.reference.startsWith("state/slot/")) }
            if (existing == null) {
                dao.insertHistory(ProgressHistoryRow(sequence = committedSequence * 64 + position, namespaceId = identity.namespaceId, contentId = identity.contentId,
                    checkpointId = checkpoint.checkpointId, operationId = operation.operationId, recordedAt = now,
                    manual = manual, pinned = checkpoint.captureReason == "import"))
            } else {
                require(checkpoint(identity, existing) == checkpoint) { "Checkpoint ID reused" }
                if (manual && !existing.manual) dao.updateHistory(existing.copy(manual = true))
            }
        }
        // Resolving a conflict must not make the discarded branch silently eligible for eviction.
        operation.changes.filter { it.parents.size > 1 }.forEach { change ->
            change.parents.forEach { parentId ->
                val parent = operation(identity, parentId)
                parent.changes.single { it.reference == change.reference }.checkpointId?.let { checkpointId ->
                    dao.historyCheckpoint(identity.namespaceId, identity.contentId, checkpointId)?.let { if (it.pinOverride == null) dao.updateHistory(it.copy(pinned = true)) }
                }
            }
        }
    }

    /** Fail closed before GC if a damaged derived index could hide reachable artifacts. */
    suspend fun audit(identity: ProgressIdentity) {
        var after = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = dao.operationPage(identity.namespaceId, identity.contentId, after, 64)
            if (page.isEmpty()) return
            for (entry in page) {
                val operation = decode(identity, entry.operation)
                for (manifest in operation.checkpoints) {
                    val indexed = row(identity, manifest.checkpointId)
                    check(checkpoint(identity, indexed) == manifest) { "Damaged recovery index; rebuild it before retention" }
                    val manual = manifest.captureReason == "manual" || operation.changes.any { it.checkpointId == manifest.checkpointId && (it.reference.startsWith("slot/") || it.reference.startsWith("state/slot/")) }
                    check(!manual || indexed.manual) { "Damaged manual recovery index; rebuild it before retention" }
                }
            }
            after = page.last().localRowId
        }
    }

    suspend fun row(identity: ProgressIdentity, checkpointId: String): ProgressHistoryRow =
        requireNotNull(dao.historyCheckpoint(identity.namespaceId, identity.contentId, checkpointId)) { "Unknown recovery checkpoint" }

    suspend fun forEachPage(identity: ProgressIdentity, block: suspend (List<ProgressHistoryRow>) -> Unit) {
        var before = Long.MAX_VALUE
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = dao.historyPage(identity.namespaceId, identity.contentId, before, 64)
            if (page.isEmpty()) return
            block(page); before = page.last().sequence
        }
    }
}
