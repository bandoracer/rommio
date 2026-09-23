package io.github.bandoracer.rommio.domain.progress

import io.github.bandoracer.rommio.domain.player.CoreMemorySnapshot
import io.github.bandoracer.rommio.domain.player.PersistentMemory
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** An explicit qualification for exact core bytes. Never infer this from exposed SRAM alone.
 * File-backed cores require a different adapter with a verified flush/snapshot mechanism.
 */
enum class MemoryRestoreOrder { STATE_THEN_MEMORY, FILES_THEN_STATE }

data class MemoryOnlyPersistence(
    val coreId: String,
    val coreSha256: String,
    val architecture: String,
    val saveCompatibility: ProgressSaveCompatibility,
    val regions: Set<PersistentMemory>,
    val stateFormat: String,
    val restoreOrder: MemoryRestoreOrder? = null,
    val inventory: Set<PersistentMemory> = PersistentMemory.STANDARD,
    val requiredRuntimeDescriptor: String? = null,
    val requiredRegionSizes: Map<PersistentMemory, Int>? = null,
) {
    init {
        require(inventory.containsAll(PersistentMemory.STANDARD) && inventory.containsAll(regions))
        requiredRegionSizes?.let { sizes ->
            require(sizes.keys == inventory && sizes.values.all { it >= 0 })
            require(sizes.values.sumOf { it.toLong() } <= PersistentMemory.MAX_TOTAL_BYTES)
        }
    }
    val fileBundle get() = requiredRuntimeDescriptor == "rommio-files-v1:ppsspp:psp"
    val artifactFormat get() = if (fileBundle) "psp-memory-stick-v1" else "libretro-memory"
    fun artifactPath(region: PersistentMemory) = if (fileBundle && region == PersistentMemory.SRAM) "memory-stick.rmmf" else region.path
    fun artifactRole(region: PersistentMemory) = if (fileBundle && region == PersistentMemory.SRAM) "saveFile" else region.role
    fun validSize(region: PersistentMemory, length: Long, actual: Int): Boolean =
        if (fileBundle && region == PersistentMemory.SRAM) length in 12..64L*1024*1024 else length == actual.toLong()
    fun validateMemory(snapshot: CoreMemorySnapshot) {
        if (fileBundle) require(snapshot.memory.getValue(PersistentMemory.SRAM).size in 12..64*1024*1024)
        require(snapshot.memory.keys == inventory) { "Incomplete memory inventory." }
        require(snapshot.memory.values.sumOf { it.size.toLong() } <= PersistentMemory.MAX_TOTAL_BYTES)
        requiredRuntimeDescriptor?.let { require(snapshot.persistenceDescriptor == it) { "Effective persistence layout changed." } }
        requiredRegionSizes?.forEach { (region, size) ->
            require(snapshot.memory.getValue(region).size == size) { "Unexpected ${region.path} length." }
        }
        require(snapshot.memory.filterKeys { it !in regions }.values.all { it.isEmpty() }) { "Unqualified persistent memory." }
        require(regions.all { snapshot.memory.getValue(it).isNotEmpty() || saveCompatibility.allowsEmpty })
    }
}

data class CheckpointWriteResult(
    val operationId: String,
    val checkpoint: ProgressCheckpoint,
    val resumeReference: String?,
    val stateRejected: Boolean,
    val unchanged: Boolean = false,
    val stateError: String? = null,
)

/** Capture/commit portion of the future session coordinator. Not activated by the legacy UI.
 * Parents are the selected launch baseline and our own commits, never the latest remote heads.
 * A failed commit must be retried with identical bytes before another capture is accepted.
 */
class SessionCheckpointWriter(
    private val store: ProgressStore,
    private val identity: ProgressIdentity,
    private val qualification: MemoryOnlyPersistence,
    private val deviceId: String,
    private val sessionId: String,
    baselineParents: Map<String, List<String>>,
    private val stagingRoot: File,
    private val capture: suspend (includeState: Boolean) -> CoreMemorySnapshot,
    private val publish: Boolean,
    private val version: Int = 2,
) {
    suspend fun pin(checkpointId: String) = store.pinCheckpoint(identity, checkpointId, true)

    private val mutex = Mutex()
    private val parents = baselineParents.mapValues { it.value.toList() }.toMutableMap()
    private var pending: Pending? = null
    private var lastResult: CheckpointWriteResult? = null
    init {
        ProgressProtocol.validateIdentity(identity)
        require(UUID.fromString(deviceId).toString() == deviceId && UUID.fromString(sessionId).toString() == sessionId)
        require(parents.values.all { it.size <= 1 }) { "Choose a baseline explicitly; capture cannot resolve conflicts." }
    }
    private data class Pending(val operation: ProgressOperation, val payload: ByteArray,
        val sources: Map<String, File>, val directory: File, val result: CheckpointWriteResult)

    suspend fun captureAndCommit(reason: String, includeState: Boolean = true, slotId: String? = null,
        fault: (ProgressCommitBoundary) -> Unit = {}): CheckpointWriteResult = mutex.withLock {
        check(pending == null) { "Retry the pending checkpoint before capturing again." }
        require(reason in setOf("periodic", "manual", "pause", "background", "exit", "safety", "restore", "import"))
        slotId?.let { require(UUID.fromString(it).toString() == it && reason == "manual") }
        val snapshot = capture(includeState)
        require(snapshot.binding == identity.binding) { "Captured content/account does not match the session binding." }
        val core = requireNotNull(snapshot.coreIdentity) { "A verified core identity is required." }
        require(core.runtimeId == qualification.coreId && core.sha256 == qualification.coreSha256 &&
            core.abi == qualification.architecture) { "Core does not match the persistence qualification." }
        qualification.validateMemory(snapshot)
        require(qualification.regions.isNotEmpty() || qualification.saveCompatibility.allowsEmpty)
        require(snapshot.memory.filterKeys { it !in qualification.regions }.values.all { it.isEmpty() }) {
            "Core exposes persistent memory absent from its qualification."
        }
        require(!(snapshot.stateRejected && snapshot.state != null))
        require(includeState || (snapshot.state == null && !snapshot.stateRejected))
        require(!includeState || snapshot.state != null || snapshot.stateRejected)
        if (version == 3) return@withLock captureModern(snapshot, reason, includeState, slotId, fault)
        val bytes = linkedMapOf<String, ByteArray>()
        val artifacts = qualification.regions.sortedBy { it.id }.map { region ->
            val data = snapshot.memory.getValue(region)
            require(data.isNotEmpty() || qualification.saveCompatibility.allowsEmpty) { "Unexpected empty persistent memory." }
            bytes[qualification.artifactPath(region)] = data
            ProgressArtifact(ProgressProtocol.sha256(data), data.size.toLong(), qualification.artifactRole(region), qualification.artifactPath(region), qualification.artifactFormat)
        }.toMutableList()
        snapshot.state?.let { data ->
            require(data.isNotEmpty()) { "Empty serialized state." }
            bytes["resume.state"] = data
            artifacts += ProgressArtifact(ProgressProtocol.sha256(data), data.size.toLong(), "state", "resume.state", qualification.stateFormat)
        }
        val now = System.currentTimeMillis()
        val checkpoint = ProgressCheckpoint(2, UUID.randomUUID().toString(), identity.binding,
            qualification.saveCompatibility,
            snapshot.state?.let { ProgressStateCompatibility(core.runtimeId, core.sha256, qualification.stateFormat,
                "android", core.abi, snapshot.effectiveOptions.toSortedMap(), snapshot.firmwareSha256.toSortedMap()) },
            true, artifacts, deviceId, sessionId, reason, now)
        val resume = checkpoint.stateCompatibility?.let { "resume/${ProgressProtocol.stateCompatibilityKey(checkpoint)}" }
        val references = listOfNotNull("save/current", resume, slotId?.takeIf { snapshot.state != null }?.let { "slot/$it" })
        val operation = ProgressOperation(2, UUID.randomUUID().toString(), identity.binding,
            references.map { ProgressRefChange(it, parents[it].orEmpty(), checkpoint.checkpointId, false) },
            listOf(checkpoint), deviceId, sessionId, now)
        val payload = ProgressProtocol.encode(operation) // Validate before creating staging files.
        lastResult?.takeIf { previous ->
            reason in setOf("periodic", "pause", "background", "exit") &&
                previous.checkpoint.artifacts == checkpoint.artifacts &&
                previous.checkpoint.saveCompatibility == checkpoint.saveCompatibility &&
                previous.checkpoint.stateCompatibility == checkpoint.stateCompatibility
        }?.let { return@withLock it.copy(unchanged = true, stateRejected = snapshot.stateRejected) }
        var staged: File? = null
        val directory = try { withContext(Dispatchers.IO) {
            require(!Files.isSymbolicLink(stagingRoot.toPath()))
            check(stagingRoot.isDirectory || stagingRoot.mkdirs()) { "Unable to stage checkpoint." }
            val directory = File(stagingRoot, UUID.randomUUID().toString())
            check(directory.mkdir())
            staged = directory
            try {
                artifacts.forEach { artifact ->
                    currentCoroutineContext().ensureActive()
                    FileOutputStream(File(directory, artifact.sha256)).use {
                        it.write(bytes.getValue(artifact.relativePath)); it.fd.sync()
                    }
                }
                PendingCheckpointJournal.write(directory, payload, publish)
                directory
            } catch (error: Throwable) { directory.deleteRecursively(); throw error }
        }
        } catch (error: Throwable) {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { runCatching { staged?.deleteRecursively() }; Unit }
            throw error
        }
        pending = Pending(operation, payload, artifacts.associate { it.sha256 to File(directory, it.sha256) }, directory,
            CheckpointWriteResult(operation.operationId, checkpoint, resume, snapshot.stateRejected))
        commitPending(fault)
    }

    private suspend fun stage(operation: ProgressOperation, bytes: Map<String, ByteArray>, result: CheckpointWriteResult): Pending = withContext(Dispatchers.IO) {
        val payload = ProgressProtocol.encode(operation)
        require(!Files.isSymbolicLink(stagingRoot.toPath()))
        check(stagingRoot.isDirectory || stagingRoot.mkdirs())
        val directory = File(stagingRoot, UUID.randomUUID().toString()).apply { check(mkdir()) }
        try {
            operation.checkpoints.flatMap { it.artifacts }.distinctBy { it.sha256 }.forEach { artifact ->
                FileOutputStream(File(directory, artifact.sha256)).use { it.write(bytes.getValue(artifact.relativePath)); it.fd.sync() }
            }
            PendingCheckpointJournal.write(directory, payload, publish)
            Pending(operation, payload, operation.checkpoints.flatMap { it.artifacts }.associate { it.sha256 to File(directory, it.sha256) }, directory, result)
        } catch (error: Throwable) { directory.deleteRecursively(); throw error }
    }

    private suspend fun captureModern(snapshot: CoreMemorySnapshot, reason: String, includeState: Boolean, slotId: String?, fault: (ProgressCommitBoundary) -> Unit): CheckpointWriteResult {
        val bytes = qualification.regions.associate { qualification.artifactPath(it) to snapshot.memory.getValue(it) }.toMutableMap()
        val artifacts = qualification.regions.sortedBy { it.id }.map { region ->
            val data = snapshot.memory.getValue(region)
            ProgressArtifact(ProgressProtocol.sha256(data), data.size.toLong(), qualification.artifactRole(region), qualification.artifactPath(region), qualification.artifactFormat)
        }
        val now = System.currentTimeMillis()
        val gameId = UUID.randomUUID().toString()
        val game = ProgressCheckpoint(3, UUID.randomUUID().toString(), identity.binding, qualification.saveCompatibility,
            null, true, artifacts, deviceId, sessionId, reason, now)
        val gameOperation = ProgressOperation(3, gameId, identity.binding,
            listOf(ProgressRefChange("game/current", parents["game/current"].orEmpty(), game.checkpointId, false)), listOf(game), deviceId, sessionId, now)
        val gameResult = CheckpointWriteResult(gameId, game, null, includeState,
            stateError = if (includeState) "Game-save data saved; the requested state was not committed." else null)
        pending = stage(gameOperation, bytes, gameResult)
        commitPending(fault) // A game-save commit failure remains an exact-byte retry.
        val stateBytes = snapshot.state ?: return gameResult.copy(stateRejected = snapshot.stateRejected,
            stateError = if (snapshot.stateRejected) "Game-save data saved; state capture failed." else null)
        val core = requireNotNull(snapshot.coreIdentity)
        val state = game.copy(checkpointId = UUID.randomUUID().toString(),
            stateCompatibility = ProgressStateCompatibility(core.runtimeId, core.sha256, qualification.stateFormat, "android", core.abi,
                snapshot.effectiveOptions.toSortedMap(), snapshot.firmwareSha256.toSortedMap()),
            artifacts = artifacts + ProgressArtifact(ProgressProtocol.sha256(stateBytes), stateBytes.size.toLong(), "state", "resume.state", qualification.stateFormat),
            gameSaveOperationId = gameId)
        val resume = "state/resume/${ProgressProtocol.stateCompatibilityKey(state)}"
        val stateId = UUID.randomUUID().toString()
        val references = listOfNotNull(resume, slotId?.let { "state/slot/$it" })
        val stateOperation = ProgressOperation(3, stateId, identity.binding,
            references.map { ProgressRefChange(it, parents[it].orEmpty(), state.checkpointId, false) }, listOf(state), deviceId, sessionId, now)
        val result = CheckpointWriteResult(stateId, state, resume, false)
        bytes["resume.state"] = stateBytes
        return try {
            pending = stage(stateOperation, bytes, result)
            commitPending(fault)
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: Exception) {
            if (store.operationPayloads(identity).any { it.contentEquals(ProgressProtocol.encode(stateOperation)) }) {
                return commitPending {} // The transaction committed; only its acknowledgement was interrupted.
            }
            // Keep any exact ready journal for explicit recovery. A state failure cannot undo or
            // block future game-save commits, and it must not replace the old manual slot.
            pending = null
            gameResult.copy(stateError = "Game-save data saved; state failed: ${error.message}")
        }
    }

    suspend fun hasPendingCommit(): Boolean = mutex.withLock { pending != null }

    suspend fun retryCommit(fault: (ProgressCommitBoundary) -> Unit = {}): CheckpointWriteResult = mutex.withLock {
        check(pending != null) { "No checkpoint is pending." }
        commitPending(fault)
    }

    internal var committedOutputBytes: ByteArray? = null
        private set

    private suspend fun commitPending(fault: (ProgressCommitBoundary) -> Unit): CheckpointWriteResult {
        val item = requireNotNull(pending)
        store.commit(identity, item.payload, item.sources, publish, fault)
        item.operation.changes.forEach { parents[it.reference] = listOf(item.operation.operationId) }
        if (qualification.fileBundle || qualification.coreId == "handy") {
            committedOutputBytes = runCatching {
                val path = if (qualification.fileBundle) "memory-stick.rmmf" else PersistentMemory.SRAM.path
                val artifact = item.result.checkpoint.artifacts.singleOrNull { it.relativePath == path }
                    ?: return@runCatching ByteArray(0)
                requireNotNull(item.sources[artifact.sha256]).readBytes().also { require(ProgressProtocol.sha256(it) == artifact.sha256) }
            }.getOrNull()
        }
        lastResult = item.result
        pending = null
        // A cleanup failure cannot turn an acknowledged durable commit into a failed save.
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { runCatching { item.directory.deleteRecursively() }; Unit }
        return item.result
    }
}
