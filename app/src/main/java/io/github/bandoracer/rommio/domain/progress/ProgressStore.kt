package io.github.bandoracer.rommio.domain.progress

import android.system.Os
import android.system.OsConstants
import androidx.room.withTransaction
import io.github.bandoracer.rommio.data.local.*
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import io.github.bandoracer.rommio.domain.storage.StorageAccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ProgressConflict(val heads: List<String>) : IllegalStateException("Progress has unresolved branches")
data class ProgressReferenceHead(val operationId: String, val checkpoint: ProgressCheckpoint?, val changedReferences: Set<String>)
data class MaterializedProgressCheckpoint(val checkpoint: ProgressCheckpoint, val directory: File)
data class ProgressLease(val identity: ProgressIdentity, val operationId: String, val payloadHash: String, val leaseId: String, val payload: ByteArray)
enum class ProgressCommitBoundary { ARTIFACTS_INSTALLED, OPERATION_INSERTED, HEADS_UPDATED, OUTBOX_INSERTED, TRANSACTION_COMMITTED }

/** Held until native teardown completes; independent of user-controlled recovery pins. */
class ProgressSessionLease internal constructor(
    internal val owner: ProgressStore,
    val identity: ProgressIdentity,
    checkpointId: String?,
    internal val storageLease: AutoCloseable,
) {
    var checkpointId: String? = checkpointId
        internal set
    internal var workspace: ProgressWorkspace? = null
    internal var started = false
}

/** Owns only v2 storage; legacy data is never imported or activated implicitly. */
class ProgressStore(private val database: AppDatabase, rootDirectory: File) : Closeable {
    // Resolve trusted OS aliases (e.g. /data/user/0) once; all paths beneath this anchor are checked.
    private val root = rootDirectory.also { require(!Files.isSymbolicLink(it.toPath())) }.canonicalFile
    private val dao = database.progressDao()
    private val historyIndex = ProgressHistoryIndex(database)
    private val mutex = Mutex()
    private val lockFile: RandomAccessFile
    private val ownerLock: java.nio.channels.FileLock
    private val storageLock: ProgressStorageLock
    private var closed = false
    private var recovered = false
    private val sessions = mutableMapOf<Pair<String, String>, ProgressSessionLease>()
    private val workspaceFiles = ProgressWorkspaceFiles()

    init {
        safeDirectories(requireNotNull(root.parentFile))
        storageLock = ProgressStorageLock.acquire(root)
        try {
            // Acquire the stable sibling lock before creating a root that may be between import renames.
            safeDirectories(root)
            val path = File(root, ".owner.lock")
            require(!Files.isSymbolicLink(path.toPath()))
            lockFile = RandomAccessFile(path, "rw")
            try { ownerLock = requireNotNull(lockFile.channel.tryLock()) { "Progress store already has a writer" } }
            catch (error: Exception) { lockFile.close(); throw error }
        } catch (error: Exception) { storageLock.close(); throw error }
    }

    private suspend fun <T> access(block: suspend () -> T): T = StorageAccess.normal { withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!closed)
            if (!recovered) {
                database.openHelper.writableDatabase.execSQL("PRAGMA synchronous = FULL")
                dao.recoverLeases(); recovered = true
            }
            block()
        }
    } }

    suspend fun resolveIdentity(binding: ProgressBinding): ProgressIdentity = access {
        ProgressProtocol.validateBinding(binding)
        database.withTransaction {
            val namespace = dao.namespace(binding.serverKey, binding.accountId) ?: ProgressNamespaceRow(UUID.randomUUID().toString(), binding.serverKey, binding.accountId).also { dao.insertNamespace(it) }
            val key = ProgressProtocol.bindingKey(binding)
            val content = dao.content(namespace.namespaceId, key) ?: ProgressContentRow(UUID.randomUUID().toString(), namespace.namespaceId, key).also { dao.insertContent(it) }
            ProgressIdentity(namespace.namespaceId, content.contentId, binding)
        }
    }

    suspend fun identities(): List<ProgressIdentity> = access {
        dao.exportOperations().groupBy { it.namespaceId to it.contentId }.map { (key, rows) ->
            ProgressIdentity(key.first, key.second, ProgressProtocol.decode(rows.first().payload).binding)
        }
    }

    private suspend fun requireIdentity(identity: ProgressIdentity) {
        ProgressProtocol.validateIdentity(identity)
        require(dao.namespace(identity.binding.serverKey, identity.binding.accountId)?.namespaceId == identity.namespaceId)
        require(dao.content(identity.namespaceId, ProgressProtocol.bindingKey(identity.binding))?.contentId == identity.contentId)
    }

    suspend fun commit(identity: ProgressIdentity, payload: ByteArray, sources: Map<String, File>, publish: Boolean = true,
                       fault: (ProgressCommitBoundary) -> Unit = {}) = access {
        commitLocked(identity, payload, sources, publish, fault)
    }

    private suspend fun commitLocked(identity: ProgressIdentity, payload: ByteArray, sources: Map<String, File>, publish: Boolean,
        fault: (ProgressCommitBoundary) -> Unit) {
        requireIdentity(identity)
        val immutablePayload = payload.copyOf()
        val operation = ProgressProtocol.decode(immutablePayload)
        require(operation.binding == identity.binding) { "Wrong progress binding" }
        val hash = ProgressProtocol.sha256(immutablePayload)
        val existing = dao.operation(identity.namespaceId, identity.contentId, operation.operationId)
        if (existing != null) {
            require(existing.payloadHash == hash && existing.payload.contentEquals(immutablePayload)) { "Operation ID reused with different bytes" }
            verifyArtifacts(identity, operation.checkpoints.flatMap { it.artifacts })
            return
        }
        historyIndex.ensure(identity, System.currentTimeMillis())
        // Existing immutable parents cannot reference a not-yet-inserted operation. Their
        // ancestry was validated at commit; loading the whole graph on every capture is unnecessary.
        val known = ProgressProtocol.dependencies(operation).associateWith { historyIndex.operation(identity, it) }
        ProgressProtocol.validateParents(operation, known)
        operation.checkpoints.forEach { checkpoint ->
            dao.historyCheckpoint(identity.namespaceId, identity.contentId, checkpoint.checkpointId)?.let {
                require(historyIndex.checkpoint(identity, it) == checkpoint) { "Checkpoint ID reused" }
            }
        }
        val artifacts = operation.checkpoints.flatMap { it.artifacts }
        artifacts.groupBy { it.sha256 }.forEach { (digest, records) ->
            require(records.map { it.byteLength }.distinct().size == 1)
            installBlob(identity, records.first(), sources[digest])
        }
        fault(ProgressCommitBoundary.ARTIFACTS_INSTALLED)
        database.withTransaction {
            val sequence = dao.lastOperationSequence(identity.namespaceId, identity.contentId)
            check(sequence < Long.MAX_VALUE / 64) { "Progress sequence exhausted" }
            dao.insertOperation(ProgressOperationRow(identity.namespaceId, identity.contentId, operation.operationId, immutablePayload, hash, sequence + 1))
            fault(ProgressCommitBoundary.OPERATION_INSERTED)
            historyIndex.index(identity, operation, System.currentTimeMillis())
            operation.checkpoints.forEach { checkpoint ->
                val row = historyIndex.row(identity, checkpoint.checkpointId)
                if (row.evicted) dao.updateHistory(row.copy(evicted = false))
            }
            operation.changes.forEach { change ->
                dao.removeParents(identity.namespaceId, identity.contentId, change.reference, change.parents)
                dao.insertHead(ProgressHeadRow(identity.namespaceId, identity.contentId, change.reference, operation.operationId))
            }
            fault(ProgressCommitBoundary.HEADS_UPDATED)
            dao.insertOutbox(ProgressOutboxRow(identity.namespaceId, identity.contentId, operation.operationId, hash, if (publish) "PENDING" else "LOCAL_ONLY"))
            fault(ProgressCommitBoundary.OUTBOX_INSERTED)
        }
        fault(ProgressCommitBoundary.TRANSACTION_COMMITTED)
    }

    suspend fun heads(identity: ProgressIdentity, reference: String): List<String> = access {
        requireIdentity(identity); dao.heads(identity.namespaceId, identity.contentId, reference).map { it.operationId }
    }

    /** Check selection and reserve its baseline under the same barrier used by retention/commits.
     * Remote commits remain allowed and become independent causal heads. */
    suspend fun acquireSession(identity: ProgressIdentity, checkpointId: String?,
        observedHeads: Map<String, List<String>>): ProgressSessionLease = withContext(NonCancellable) { access {
        requireIdentity(identity)
        val key = identity.namespaceId to identity.contentId
        check(key !in sessions) { "This progress already has a player session." }
        observedHeads.forEach { (reference, expected) ->
            val actual = dao.heads(identity.namespaceId, identity.contentId, reference).map { it.operationId }
            check(actual.toSet() == expected.toSet()) { "Progress changed while preparing the player. Select progress again." }
        }
        if (checkpointId != null) {
            historyIndex.ensure(identity, System.currentTimeMillis())
            check(!historyIndex.row(identity, checkpointId).evicted) { "The selected checkpoint needs recovery before playback." }
        }
        ProgressSessionLease(this@ProgressStore, identity, checkpointId, StorageAccess.session()).also { it.started = true; sessions[key] = it }
    } }

    /** Owns the content before creating a core. The source must explicitly omit legacy seeds. */
    suspend fun prepareWorkspace(identity: ProgressIdentity, source: io.github.bandoracer.rommio.domain.player.PlayerSession): ProgressWorkspace =
        withContext(NonCancellable) { access {
            requireIdentity(identity)
            val key = identity.namespaceId to identity.contentId
            check(key !in sessions) { "This progress already has a player session." }
            val lease = ProgressSessionLease(this@ProgressStore, identity, null, StorageAccess.session())
            sessions[key] = lease
            try {
                workspaceFiles.recover(contentRoot(identity), identity, null)
                workspaceFiles.prepare(contentRoot(identity), identity, source, lease).also { lease.workspace = it }
            } catch (error: Throwable) {
                sessions.remove(key); lease.storageLease.close()
                throw error // Any incomplete files retain their preparation record for the next recovery pass.
            }
        } }

    /** Converts preparation ownership into gameplay ownership without opening a duplicate-writer gap. */
    internal suspend fun selectWorkspace(workspace: ProgressWorkspace, prepared: ProgressLaunchDecision.Ready): ProgressSessionLease =
        withContext(NonCancellable) { access {
            val lease = workspace.lease
            require(lease.owner === this@ProgressStore && prepared.identity == workspace.identity)
            check(sessions[lease.identity.namespaceId to lease.identity.contentId] === lease && !lease.started)
            prepared.observedHeads.forEach { (reference, expected) ->
                val actual = dao.heads(lease.identity.namespaceId, lease.identity.contentId, reference).map { it.operationId }
                check(actual.toSet() == expected.toSet()) { "Progress changed while preparing the player. Select progress again." }
            }
            prepared.checkpointId?.let {
                historyIndex.ensure(lease.identity, System.currentTimeMillis())
                check(!historyIndex.row(lease.identity, it).evicted) { "The selected checkpoint needs recovery before playback." }
            }
            workspaceFiles.select(workspace, prepared.checkpointId)
            lease.checkpointId = prepared.checkpointId
            lease.started = true
            lease
        } }

    suspend fun recoverWorkingSessions(identity: ProgressIdentity): List<AbandonedProgressWorkspace> = access {
        requireIdentity(identity)
        workspaceFiles.recover(contentRoot(identity), identity, sessions[identity.namespaceId to identity.contentId]?.workspace)
    }

    /** Explicit recovery-UI discard only; never invoked by retention. */
    suspend fun discardAbandonedWorkspace(identity: ProgressIdentity, sessionId: String) = access {
        requireIdentity(identity)
        workspaceFiles.discard(contentRoot(identity), identity, sessionId)
    }

    suspend fun pendingWorkspaceCheckpoints(identity: ProgressIdentity, sessionId: String): List<PendingWorkspaceCheckpoint> = access {
        requireIdentity(identity)
        workspaceFiles.pending(contentRoot(identity), identity, sessionId)
    }

    /** Explicit retry of a complete captured revision; never infer progress from loose core files. */
    suspend fun recoverWorkspaceCheckpoint(identity: ProgressIdentity, sessionId: String, captureId: String,
        fault: (ProgressCommitBoundary) -> Unit = {}): String = access {
        requireIdentity(identity)
        val directory = workspaceFiles.pendingDirectory(contentRoot(identity), identity, sessionId, captureId)
        val replay = PendingCheckpointJournal.read(directory, identity, sessionId)
        commitLocked(identity, replay.payload, replay.sources, replay.publish, fault)
        // As with the live writer, cleanup cannot turn a committed revision into a failed save.
        runCatching { deleteStagingTree(directory); syncDirectory(requireNotNull(directory.parentFile)) }
        replay.operation.operationId
    }

    /** Only the native owner may release this after awaited teardown, including failed startup. */
    suspend fun releaseSession(lease: ProgressSessionLease, committedOutputBytes: ByteArray? = null) = withContext(NonCancellable) { access {
        require(lease.owner === this@ProgressStore) { "Session belongs to another store." }
        val key = lease.identity.namespaceId to lease.identity.contentId
        if (sessions[key] === lease) {
            // Native teardown has already succeeded. Cleanup failure must not retain a phantom core.
            try { lease.workspace?.let { workspaceFiles.finish(contentRoot(lease.identity), it, committedOutputBytes) } }
            finally { lease.storageLease.close(); sessions.remove(key) }
        }
    } }

    /** One frozen metadata view for launch decisions; individual artifacts are validated by the loader.
     * A damaged state must not prevent inspecting independently recoverable in-game saves. */
    suspend fun referenceSnapshot(identity: ProgressIdentity): Map<String, List<ProgressReferenceHead>> = access {
        requireIdentity(identity)
        database.withTransaction {
            val operations = mutableMapOf<String, ProgressOperation>()
            dao.allHeads(identity.namespaceId, identity.contentId).groupBy { it.reference }.toSortedMap().mapValues { (reference, heads) ->
                heads.sortedBy { it.operationId }.map { head ->
                    val operation = operations[head.operationId] ?: historyIndex.decode(identity,
                        requireNotNull(dao.operation(identity.namespaceId, identity.contentId, head.operationId)))
                        .also { operations[head.operationId] = it }
                    val change = operation.changes.single { it.reference == reference }
                    ProgressReferenceHead(head.operationId,
                        change.checkpointId?.let { id -> operation.checkpoints.single { it.checkpointId == id } },
                        operation.changes.map { it.reference }.toSet())
                }
            }
        }
    }

    suspend fun checkpoint(identity: ProgressIdentity, reference: String): ProgressCheckpoint? = access {
        requireIdentity(identity)
        selectedCheckpoint(identity, reference)
    }

    private suspend fun selectedCheckpoint(identity: ProgressIdentity, reference: String): ProgressCheckpoint? {
        val heads = dao.heads(identity.namespaceId, identity.contentId, reference)
        if (heads.isEmpty()) return null
        if (heads.size > 1) throw ProgressConflict(heads.map { it.operationId })
        val op = requireNotNull(dao.operation(identity.namespaceId, identity.contentId, heads.single().operationId))
        val operation = historyIndex.decode(identity, op)
        val change = operation.changes.single { it.reference == reference }
        if (change.tombstone) return null
        val checkpoint = operation.checkpoints.single { it.checkpointId == change.checkpointId }
        if (reference.startsWith("resume/")) {
            val saves = dao.heads(identity.namespaceId, identity.contentId, "save/current")
            if (saves.size != 1 || saves.single().operationId != operation.operationId) return null // Old state must not rewind newer save data.
        }
        verifyArtifacts(identity, checkpoint.artifacts)
        return checkpoint
    }

    suspend fun materialize(identity: ProgressIdentity, reference: String, sessionId: String): File? = access {
        requireIdentity(identity)
        require(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(sessionId))
        val checkpoint = selectedCheckpoint(identity, reference) ?: return@access null
        materializeVerified(identity, checkpoint, sessionId)
    }

    private suspend fun materializeVerified(identity: ProgressIdentity, checkpoint: ProgressCheckpoint, sessionId: String, artifacts: List<ProgressArtifact> = checkpoint.artifacts): File {
        val working = File(contentRoot(identity), "working")
        safeDirectories(working)
        val destination = File(working, sessionId)
        require(!destination.exists() && !Files.isSymbolicLink(destination.toPath())) { "Working session already exists" }
        val staging = File(working, ".staging-${UUID.randomUUID()}")
        safeDirectories(staging)
        try {
            artifacts.forEach { artifact ->
                val target = File(staging, artifact.relativePath)
                safeDirectories(requireNotNull(target.parentFile))
                copyVerified(blob(identity, artifact.sha256), target, artifact)
                syncDirectory(requireNotNull(target.parentFile))
            }
            syncDirectory(staging)
            Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            syncDirectory(working)
            return destination
        } finally { staging.deleteRecursively() }
    }

    suspend fun outbox(identity: ProgressIdentity): List<ProgressOutboxRow> = access { requireIdentity(identity); dao.outbox(identity.namespaceId, identity.contentId) }

    /** Explicit choice consumes exactly the displayed heads and pins every replaced checkpoint. */
    suspend fun resolveReference(identity: ProgressIdentity, reference: String, selectedId: String,
        expectedHeads: List<String>, deviceId: String, sessionId: String, publish: Boolean = true) = access {
        requireIdentity(identity)
        require(expectedHeads.size > 1 && selectedId in expectedHeads)
        check(dao.heads(identity.namespaceId, identity.contentId, reference).map { it.operationId }.toSet() == expectedHeads.toSet()) {
            "Cloud progress changed. Refresh the choices before resolving this conflict."
        }
        val selected = historyIndex.operation(identity, selectedId)
        require(selected.schemaVersion == 3)
        val change = selected.changes.single { it.reference == reference }
        val checkpoints = selected.checkpoints.filter { it.checkpointId == change.checkpointId }
        val operation = ProgressOperation(3, UUID.randomUUID().toString(), identity.binding,
            listOf(change.copy(parents = expectedHeads)), checkpoints, deviceId, sessionId, System.currentTimeMillis())
        commitLocked(identity, ProgressProtocol.encode(operation), emptyMap(), publish) {}
    }

    suspend fun isGameAncestor(identity: ProgressIdentity, ancestor: String, descendant: String): Boolean = access {
        requireIdentity(identity)
        val todo = ArrayDeque<String>(); val seen = mutableSetOf<String>(); todo.add(descendant)
        while (todo.isNotEmpty()) {
            val id = todo.removeFirst()
            if (id == ancestor) return@access true
            if (!seen.add(id)) continue
            todo.addAll(historyIndex.operation(identity, id).changes.filter { it.reference == "game/current" }.flatMap { it.parents })
        }
        false
    }

    suspend fun operationPayloads(identity: ProgressIdentity): List<ByteArray> = access {
        requireIdentity(identity); dao.operations(identity.namespaceId, identity.contentId).map { it.payload.copyOf() }
    }
    suspend fun artifactFile(identity: ProgressIdentity, artifact: ProgressArtifact): File = access {
        requireIdentity(identity); blob(identity, artifact.sha256).also { verifyFile(it, artifact) }
    }
    suspend fun enablePublication(identity: ProgressIdentity) = access {
        requireIdentity(identity)
        dao.outbox(identity.namespaceId, identity.contentId).filter { it.status == "LOCAL_ONLY" }.forEach {
            if (historyIndex.operation(identity, it.operationId).schemaVersion == 3) dao.updateOutbox(it.copy(status = "PENDING"))
        }
    }
    suspend fun received(identity: ProgressIdentity, payload: ByteArray, sources: Map<String, File>) = access {
        commitLocked(identity, payload, sources, false) {}
        val op = ProgressProtocol.decode(payload)
        val row = requireNotNull(dao.outboxOperation(identity.namespaceId, identity.contentId, op.operationId))
        dao.updateOutbox(row.copy(status = "PUBLISHED", leaseId = null, leaseUntil = null, lastError = null))
    }
    suspend fun publicationStatus(identity: ProgressIdentity): List<io.github.bandoracer.rommio.data.local.ProgressOutboxRow> = access {
        requireIdentity(identity); dao.outbox(identity.namespaceId, identity.contentId)
    }

    suspend fun leaseNext(identity: ProgressIdentity, now: Long, durationMs: Long, states: Boolean? = null): ProgressLease? = access {
        requireIdentity(identity); require(durationMs in 1..3_600_000 && now <= Long.MAX_VALUE - durationMs)
        database.withTransaction {
            val queue = dao.outbox(identity.namespaceId, identity.contentId)
            val published = queue.filter { it.status == "PUBLISHED" }.map { it.operationId }.toSet()
            val pending = queue.filter { row ->
                (row.status == "PENDING" && row.nextAttempt <= now) || (row.status == "LEASED" && (row.leaseUntil ?: 0) <= now)
            }.filter { row -> states == null || historyIndex.operation(identity, row.operationId).let { op ->
                op.schemaVersion == 3 && op.changes.any { it.reference.startsWith("state/") } == states
            } }.firstOrNull { row -> ProgressProtocol.dependencies(historyIndex.operation(identity, row.operationId)).all { it in published } }
                ?: return@withTransaction null
            val row = requireNotNull(dao.operation(identity.namespaceId, identity.contentId, pending.operationId))
            require(row.payloadHash == pending.payloadHash && ProgressProtocol.sha256(row.payload) == row.payloadHash)
            val ticket = UUID.randomUUID().toString()
            dao.updateOutbox(pending.copy(status = "LEASED", attempts = pending.attempts + 1, leaseId = ticket, leaseUntil = now + durationMs))
            ProgressLease(identity, row.operationId, row.payloadHash, ticket, row.payload.copyOf())
        }
    }

    suspend fun acknowledge(lease: ProgressLease, verifiedPayloadHash: String): Boolean = finishLease(lease, "PUBLISHED", 0, null, verifiedPayloadHash)
    suspend fun retry(lease: ProgressLease, nextAttempt: Long, error: String, authBlocked: Boolean = false): Boolean =
        finishLease(lease, if (authBlocked) "PARKED_AUTH" else "PENDING", nextAttempt, error, null)

    private suspend fun finishLease(lease: ProgressLease, status: String, nextAttempt: Long, error: String?, verifiedHash: String?): Boolean = access {
        requireIdentity(lease.identity)
        database.withTransaction {
            val row = dao.outboxOperation(lease.identity.namespaceId, lease.identity.contentId, lease.operationId) ?: return@withTransaction false
            if (row.status != "LEASED" || row.leaseId != lease.leaseId || row.payloadHash != lease.payloadHash) return@withTransaction false
            if (status == "PUBLISHED") require(verifiedHash == row.payloadHash) { "Acknowledgment does not match uploaded bytes" }
            dao.updateOutbox(row.copy(status = status, leaseId = null, leaseUntil = null, nextAttempt = nextAttempt, lastError = error))
            true
        }
    }

    suspend fun resumeAuthenticatedWork(identity: ProgressIdentity) = access {
        requireIdentity(identity)
        database.withTransaction { dao.outbox(identity.namespaceId, identity.contentId).filter { it.status == "PARKED_AUTH" }.forEach { dao.updateOutbox(it.copy(status = "PENDING")) } }
    }

    suspend fun quarantine(identity: ProgressIdentity, payload: ByteArray, reason: String) = access {
        requireIdentity(identity); require(payload.size <= ProgressProtocol.MAX_PAYLOAD_BYTES)
        dao.quarantine(ProgressQuarantineRow(identity.namespaceId, identity.contentId, ProgressProtocol.sha256(payload), payload.copyOf(), reason))
    }

    /** Keyset pagination uses local commit order, never device timestamps or conflict priority. */
    suspend fun history(identity: ProgressIdentity, beforeSequence: Long = Long.MAX_VALUE, limit: Int = 20): ProgressHistoryPage = access {
        requireIdentity(identity); require(limit in 1..100 && beforeSequence > 0)
        historyIndex.ensure(identity, System.currentTimeMillis())
        val rows = dao.historyPage(identity.namespaceId, identity.contentId, beforeSequence, limit + 1)
        val entries = rows.take(limit).map { row ->
            ProgressHistoryEntry(row.sequence, row.operationId, historyIndex.checkpoint(identity, row), row.pinned, row.manual,
                if (row.evicted) ProgressLocalRetention.EVICTED else ProgressLocalRetention.RETAINED)
        }
        ProgressHistoryPage(entries, if (rows.size > limit) entries.last().sequence else null)
    }

    /** Rebuild missing derived rows without deleting envelopes or existing pins/eviction records. */
    suspend fun rebuildHistoryIndex(identity: ProgressIdentity) = access {
        requireIdentity(identity)
        dao.updateHistoryIndex(ProgressHistoryIndexRow(identity.namespaceId, identity.contentId, 0))
        historyIndex.ensure(identity, System.currentTimeMillis())
    }

    suspend fun pinCheckpoint(identity: ProgressIdentity, checkpointId: String, pinned: Boolean) = access {
        requireIdentity(identity); historyIndex.ensure(identity, System.currentTimeMillis())
        val row = historyIndex.row(identity, checkpointId)
        dao.updateHistory(row.copy(pinned = pinned, pinOverride = pinned))
    }

    suspend fun inspectCheckpoint(identity: ProgressIdentity, checkpointId: String): ProgressCheckpointHealth = access {
        requireIdentity(identity); historyIndex.ensure(identity, System.currentTimeMillis())
        val row = historyIndex.row(identity, checkpointId)
        val checkpoint = historyIndex.checkpoint(identity, row)
        val issues = checkpoint.artifacts.mapNotNull { artifact ->
            try { verifyFile(blob(identity, artifact.sha256), artifact); null }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                ProgressArtifactIssue(artifact.sha256, error.message ?: "Artifact cannot be verified")
            }
        }.distinctBy { it.sha256 }
        ProgressCheckpointHealth(checkpointId, if (row.evicted) ProgressLocalRetention.EVICTED else ProgressLocalRetention.RETAINED, issues)
    }

    /** Explicit recovery candidate only. The coordinator must capture safety progress before loading it. */
    suspend fun materializeCheckpoint(identity: ProgressIdentity, checkpointId: String, sessionId: String): File =
        materializeCheckpointBundle(identity, checkpointId, sessionId).directory

    suspend fun materializeCheckpointBundle(identity: ProgressIdentity, checkpointId: String, sessionId: String,
        includeState: Boolean = true, validate: (ProgressCheckpoint) -> Unit = {}): MaterializedProgressCheckpoint = access {
        requireIdentity(identity); require(UUID_PATTERN.matches(sessionId))
        historyIndex.ensure(identity, System.currentTimeMillis())
        val row = historyIndex.row(identity, checkpointId)
        check(!row.evicted) { "Checkpoint was intentionally evicted; restore its verified artifacts first" }
        val checkpoint = historyIndex.checkpoint(identity, row)
        validate(checkpoint)
        val artifacts = checkpoint.artifacts.filter { includeState || it.role != "state" }
        verifyArtifacts(identity, artifacts)
        MaterializedProgressCheckpoint(checkpoint, materializeVerified(identity, checkpoint, sessionId, artifacts))
    }

    /** Repairs exact known bytes, retains damaged originals, and never changes heads or publication state. */
    suspend fun repairCheckpoint(identity: ProgressIdentity, checkpointId: String, sources: Map<String, File>,
                                 fault: (String) -> Unit = {}) = access {
        requireIdentity(identity); historyIndex.ensure(identity, System.currentTimeMillis())
        val row = historyIndex.row(identity, checkpointId)
        val checkpoint = historyIndex.checkpoint(identity, row)
        for (artifact in checkpoint.artifacts.distinctBy { it.sha256 }) {
            val target = blob(identity, artifact.sha256)
            val healthy = try { verifyFile(target, artifact); true } catch (error: Exception) {
                if (error is CancellationException) throw error
                false
            }
            if (healthy) continue
            val source = requireNotNull(sources[artifact.sha256]) { "Verified repair source is missing" }
            safeDirectories(requireNotNull(target.parentFile))
            require(!Files.isSymbolicLink(target.toPath()) && (!target.exists() || target.isFile)) { "Unsafe repair target" }
            val staging = File(target.parentFile, ".staging-${UUID.randomUUID()}")
            try {
                copyVerified(source, staging, artifact)
                if (target.exists()) {
                    // Backup the damaged bytes before atomic replacement. Backups are not GC candidates.
                    val backupDirectory = File(contentRoot(identity), "repair-backups/${artifact.sha256}")
                    safeDirectories(backupDirectory)
                    val backup = File(backupDirectory, UUID.randomUUID().toString())
                    copyDamagedBackup(target, backup)
                    syncDirectory(backupDirectory)
                }
                fault("BACKUP_RETAINED")
                currentCoroutineContext().ensureActive()
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                syncDirectory(requireNotNull(target.parentFile))
                fault("ARTIFACT_REPAIRED")
            } finally { staging.delete() }
        }
        verifyArtifacts(identity, checkpoint.artifacts)
        dao.updateHistory(row.copy(evicted = false, pinned = row.pinned || row.evicted, pinOverride = if (row.evicted) true else row.pinOverride))
    }

    /** Repair an operation only against its previously committed digest, not re-encoded JSON. */
    suspend fun repairOperation(identity: ProgressIdentity, operationId: String, verifiedPayload: ByteArray) = access {
        requireIdentity(identity)
        val row = requireNotNull(dao.operation(identity.namespaceId, identity.contentId, operationId))
        val bytes = verifiedPayload.copyOf()
        require(ProgressProtocol.sha256(bytes) == row.payloadHash) { "Repair bytes do not match the committed operation" }
        val operation = ProgressProtocol.decode(bytes)
        require(operation.operationId == operationId && operation.binding == identity.binding)
        if (!row.payload.contentEquals(bytes)) {
            database.withTransaction {
                // Keep the damaged record in quarantine for diagnosis; do not rewrite its expected hash.
                dao.quarantine(ProgressQuarantineRow(identity.namespaceId, identity.contentId,
                    ProgressProtocol.sha256(row.payload), row.payload.copyOf(), "Damaged operation $operationId before verified repair"))
                dao.replaceOperationPayload(identity.namespaceId, identity.contentId, operationId, bytes)
            }
        }
    }

    /** No operation/tombstone metadata or remote data is deleted by local retention. */
    suspend fun retainHistory(identity: ProgressIdentity, now: Long = System.currentTimeMillis(), keepAutomatic: Int = 10,
                              graceMs: Long = 86_400_000L, fault: (String) -> Unit = {}): ProgressRetentionResult = access {
        requireIdentity(identity); require(now >= 0 && graceMs >= 0 && keepAutomatic in 10..10_000)
        historyIndex.ensure(identity, now)
        historyIndex.audit(identity)
        val protected = mutableSetOf<String>()
        sessions[identity.namespaceId to identity.contentId]?.checkpointId?.let(protected::add)
        // Orphan baselines remain recoverable until the user resolves/discards the workspace.
        workspaceFiles.recover(contentRoot(identity), identity, sessions[identity.namespaceId to identity.contentId]?.workspace).forEach {
            check(it.problem == null) { "Repair the abandoned session record before pruning its history." }
            it.baselineCheckpointId?.let(protected::add)
        }
        val requiredBlobs = mutableSetOf<String>()
        // Protect every current reference, including every competing conflict branch.
        dao.allHeads(identity.namespaceId, identity.contentId).forEach { head ->
            val operation = historyIndex.operation(identity, head.operationId)
            operation.changes.single { it.reference == head.reference }.checkpointId?.let { id ->
                protected.add(id)
                requiredBlobs.addAll(operation.checkpoints.single { it.checkpointId == id }.artifacts.map { it.sha256 })
            }
        }
        // LOCAL_ONLY is also unpublished. Explicit discard/promotion is separate from retention.
        dao.outbox(identity.namespaceId, identity.contentId).filter { it.status != "PUBLISHED" }.forEach { pending ->
            val checkpoints = historyIndex.operation(identity, pending.operationId).checkpoints
            protected.addAll(checkpoints.map { it.checkpointId })
            requiredBlobs.addAll(checkpoints.flatMap { it.artifacts }.map { it.sha256 })
        }
        var automatic = 0
        var evicted = 0
        database.withTransaction {
            historyIndex.forEachPage(identity) { page ->
                page.forEach { row ->
                    val manifest = historyIndex.checkpoint(identity, row)
                    val hardProtected = row.checkpointId in protected || row.manual || row.pinned
                    val recentAutomatic = !row.manual && !row.pinned && !row.evicted && automatic++ < keepAutomatic
                    val eligibleAge = now >= row.recordedAt && now - row.recordedAt >= graceMs
                    val keep = hardProtected || recentAutomatic || (!row.evicted && !eligibleAge)
                    if (keep) {
                        requiredBlobs.addAll(manifest.artifacts.map { it.sha256 })
                    } else if (!row.evicted) {
                        dao.updateHistory(row.copy(evicted = true)); evicted++
                    }
                }
            }
        }
        // Metadata commits before unlinking: a crash can leak files, never mislabel intentional removal as corruption.
        fault("RETENTION_COMMITTED")
        var deletedBlobs = 0; var deletedStaging = 0
        val directory = File(contentRoot(identity), "blobs")
        safeDirectories(directory)
        directory.listFiles().orEmpty().forEach { file ->
            currentCoroutineContext().ensureActive()
            require(!Files.isSymbolicLink(file.toPath())) { "Unexpected symlink in progress blobs" }
            if (!file.isFile || !oldEnough(file, now, graceMs)) return@forEach
            if (DIGEST_PATTERN.matches(file.name) && file.name !in requiredBlobs) {
                Files.delete(file.toPath()); deletedBlobs++
            } else if (STAGING_PATTERN.matches(file.name)) {
                Files.delete(file.toPath()); deletedStaging++
            }
        }
        syncDirectory(directory)
        val working = File(contentRoot(identity), "working")
        safeDirectories(working)
        working.listFiles().orEmpty().filter { STAGING_PATTERN.matches(it.name) && oldEnough(it, now, graceMs) }.forEach { staged ->
            currentCoroutineContext().ensureActive()
            deleteStagingTree(staged); deletedStaging++
        }
        syncDirectory(working)
        ProgressRetentionResult(evicted, deletedBlobs, deletedStaging, requiredBlobs.size)
    }

    private fun oldEnough(file: File, now: Long, grace: Long): Boolean = file.lastModified().let { it >= 0 && now >= it && now - it >= grace }

    private suspend fun copyDamagedBackup(source: File, backup: File) {
        require(source.length() <= 8_589_934_592L) { "Damaged artifact is too large to back up safely" }
        val size = source.length()
        FileOutputStream(backup).use { output ->
            source.inputStream().use { input ->
                val buffer = ByteArray(65536); var count = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer); if (read < 0) break
                    count += read; require(count <= size) { "Damaged artifact changed during backup" }
                    output.write(buffer, 0, read)
                }
                require(count == size) { "Damaged artifact changed during backup" }
            }
            output.flush(); output.fd.sync()
        }
    }

    private fun deleteStagingTree(directory: File) {
        require(!Files.isSymbolicLink(directory.toPath())) { "Unexpected staging symlink" }
        Files.walkFileTree(directory.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                // walkFileTree does not follow links. Deleting a link never visits its target.
                Files.delete(file); return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: java.nio.file.Path, error: java.io.IOException?): java.nio.file.FileVisitResult {
                if (error != null) throw error
                Files.delete(dir); return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }

    private companion object {
        val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val DIGEST_PATTERN = Regex("[0-9a-f]{64}")
        val STAGING_PATTERN = Regex("\\.staging-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }

    private fun contentRoot(identity: ProgressIdentity) = File(File(root, identity.namespaceId), identity.contentId)
    private fun blob(identity: ProgressIdentity, digest: String) = File(File(contentRoot(identity), "blobs"), digest)
    private suspend fun verifyArtifacts(identity: ProgressIdentity, artifacts: List<ProgressArtifact>) { artifacts.forEach { verifyFile(blob(identity, it.sha256), it) } }
    private suspend fun verifyFile(file: File, artifact: ProgressArtifact) {
        safeDirectories(requireNotNull(file.parentFile))
        currentCoroutineContext().ensureActive()
        require(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() == artifact.byteLength) { "Missing or truncated checkpoint artifact" }
        require(hashFile(file) == artifact.sha256) { "Corrupt checkpoint artifact" }
    }
    private suspend fun installBlob(identity: ProgressIdentity, artifact: ProgressArtifact, source: File?) {
        val target = blob(identity, artifact.sha256)
        safeDirectories(requireNotNull(target.parentFile))
        if (target.exists()) { verifyFile(target, artifact); return }
        val input = requireNotNull(source) { "Checkpoint artifact source is missing" }
        val staged = File(target.parentFile, ".staging-${UUID.randomUUID()}")
        try {
            copyVerified(input, staged, artifact)
            Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            syncDirectory(requireNotNull(target.parentFile))
        } finally { staged.delete() }
    }
    private suspend fun copyVerified(source: File, target: File, artifact: ProgressArtifact) {
        require(source.isFile && !Files.isSymbolicLink(source.toPath()) && source.length() == artifact.byteLength)
        FileOutputStream(target).use { output -> source.inputStream().use { input ->
            val buffer = ByteArray(65536); var remaining = artifact.byteLength
            while (remaining > 0) { currentCoroutineContext().ensureActive(); val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()); require(count > 0) { "Truncated artifact" }; output.write(buffer, 0, count); remaining -= count }
            require(input.read() == -1) { "Artifact grew during capture" }
        }; output.flush(); output.fd.sync() }
        verifyFile(target, artifact)
    }
    private suspend fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun safeDirectories(directory: File) {
        val path = directory.absoluteFile.toPath().normalize()
        var current = path.root
        for (part in path) {
            current = current.resolve(part)
            require(!Files.isSymbolicLink(current)) { "Progress directory must not be a symlink" }
            if (!Files.exists(current)) { Files.createDirectory(current); syncDirectory(current.parent.toFile()) }
            require(Files.isDirectory(current))
        }
    }
    private fun syncDirectory(directory: File) {
        val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }

    /** Close after callers have quiesced; the owner lock also fences other processes. */
    override fun close() { runBlocking(Dispatchers.IO) { mutex.withLock {
        check(sessions.isEmpty()) { "Close native player sessions before closing progress storage." }
        if (!closed) {
            closed = true
            try { try { ownerLock.release() } finally { lockFile.close() } } finally { storageLock.close() }
        }
    } } }
}
