package io.github.bandoracer.rommio.domain.progress

import io.github.bandoracer.rommio.domain.player.CoreMemorySnapshot
import io.github.bandoracer.rommio.domain.player.PersistentMemory
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Only the qualified state-then-memory layout. File-backed cores need a different adapter. */
class MemoryCheckpointLoader(
    private val store: ProgressStore,
    private val identity: ProgressIdentity,
    private val qualification: MemoryOnlyPersistence,
) {
    suspend fun load(checkpointId: String, actual: CoreMemorySnapshot, includeState: Boolean): CoreMemorySnapshot {
        require(qualification.restoreOrder in setOf(MemoryRestoreOrder.STATE_THEN_MEMORY, MemoryRestoreOrder.FILES_THEN_STATE)) { "Restore ordering is not qualified." }
        qualification.validateMemory(actual)
        val core = requireNotNull(actual.coreIdentity)
        require(actual.binding == identity.binding && core.runtimeId == qualification.coreId &&
            core.sha256 == qualification.coreSha256 && core.abi == qualification.architecture)
        val materialized = store.materializeCheckpointBundle(identity, checkpointId, UUID.randomUUID().toString(), includeState) { checkpoint ->
            require(checkpoint.artifacts.filter { includeState || it.role != "state" }.all { it.byteLength <= 256L * 1024 * 1024 }) {
                "Persistence artifact exceeds native memory budget."
            }
            require(checkpoint.binding == identity.binding && checkpoint.saveDataComplete &&
                checkpoint.saveCompatibility == qualification.saveCompatibility) { "Incompatible save format." }
            val saves = checkpoint.artifacts.filter { it.role != "state" }
            require(saves.size == qualification.regions.size) { "Unexpected persistence artifacts." }
            require(saves.sumOf { it.byteLength } <= PersistentMemory.MAX_TOTAL_BYTES)
            qualification.inventory.forEach { region ->
                if (region in qualification.regions) {
                    val artifact = saves.singleOrNull { it.role == qualification.artifactRole(region) && it.relativePath == qualification.artifactPath(region) && it.format == qualification.artifactFormat }
                    require(artifact != null && qualification.validSize(region, artifact.byteLength, actual.memory.getValue(region).size)) {
                        "Save memory layout does not match the loaded content."
                    }
                } else require(actual.memory.getValue(region).isEmpty()) { "Unqualified persistent memory." }
            }
            val state = checkpoint.stateCompatibility
            if (includeState) {
                require(state != null && state.coreId == core.runtimeId && state.coreSha256 == core.sha256 &&
                    state.architecture == core.abi && state.platform == "android" && state.stateFormat == qualification.stateFormat &&
                    state.options == actual.effectiveOptions && state.systemFiles == actual.firmwareSha256) {
                    "Incompatible state. Cold boot with compatible save data instead."
                }
                val artifact = checkpoint.artifacts.single { it.role == "state" }
                require(artifact.relativePath == "resume.state" && artifact.format == qualification.stateFormat)
            }
        }
        try {
            val checkpoint = materialized.checkpoint
            val saves = checkpoint.artifacts.filter { it.role != "state" }
            require(saves.size == qualification.regions.size)
            val memory = qualification.inventory.associateWith { region ->
                if (region !in qualification.regions) ByteArray(0) else {
                    val artifact = saves.singleOrNull { it.role == qualification.artifactRole(region) && it.relativePath == qualification.artifactPath(region) && it.format == qualification.artifactFormat }
                    requireNotNull(artifact) { "Save bundle does not match the qualified memory layout." }
                    readArtifact(materialized.directory, artifact)
                }
            }
            val bytes = if (includeState) {
                val artifact = checkpoint.artifacts.single { it.role == "state" }
                require(artifact.relativePath == "resume.state" && artifact.format == qualification.stateFormat)
                readArtifact(materialized.directory, artifact)
            } else null
            return CoreMemorySnapshot(memory, bytes, false, actual.effectiveOptions.toMap(), core,
                actual.firmwareSha256.toMap(), identity.binding, actual.persistenceDescriptor)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { materialized.directory.deleteRecursively() }; Unit }
        }
    }

    private suspend fun readArtifact(directory: File, artifact: ProgressArtifact): ByteArray = withContext(Dispatchers.IO) {
        require(artifact.byteLength <= 256L * 1024 * 1024) { "Persistence artifact exceeds native memory budget." }
        val file = File(directory, artifact.relativePath)
        require(file.length() == artifact.byteLength)
        file.readBytes().also {
            require(it.size.toLong() == artifact.byteLength && ProgressProtocol.sha256(it) == artifact.sha256) { "Restore bytes changed." }
        }
    }
}
