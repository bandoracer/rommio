package io.github.bandoracer.rommio.domain.progress

import java.util.UUID

/** Copy current v2 references into the independent v3 histories; original bytes/history remain intact.
 * IDs are deterministic so two devices upgrading the same imported bundle cannot manufacture forks.
 */
internal class ProgressUpgrade(private val store: ProgressStore) {
    suspend fun upgrade(identity: ProgressIdentity) {
        val references = store.referenceSnapshot(identity)
        val old = references.filterKeys { it == "save/current" || it.startsWith("resume/") || it.startsWith("slot/") }
        if (old.isEmpty()) return
        val payloads = store.operationPayloads(identity).map(ProgressProtocol::decode).associateBy { it.operationId }
        val byOperation = old.values.flatten().distinctBy { it.operationId }
        for (head in byOperation) {
            val original = payloads.getValue(head.operationId)
            val currentChanges = old.flatMap { (ref, heads) -> heads.filter { it.operationId == head.operationId }.map { ref } }
            for (checkpoint in original.checkpoints.filter { cp -> original.changes.any { it.reference in currentChanges && it.checkpointId == cp.checkpointId } }) {
                if (!checkpoint.saveDataComplete) continue // Unpaired states stay in explicit recovery inventory.
                val gameId = id("game:${original.operationId}:${checkpoint.checkpointId}")
                val game = checkpoint.copy(schemaVersion = 3, checkpointId = id("game-cp:${checkpoint.checkpointId}"),
                    stateCompatibility = null, artifacts = ProgressProtocol.gameArtifacts(checkpoint), gameSaveOperationId = null)
                val current = original.changes.any { it.reference == "save/current" && it.reference in currentChanges && it.checkpointId == checkpoint.checkpointId }
                val gameOp = ProgressOperation(3, gameId, identity.binding,
                    listOf(ProgressRefChange(if (current) "game/current" else "game/archive/$gameId", emptyList(), game.checkpointId, false)), listOf(game), original.deviceId, original.sessionId, original.createdAtEpochMs)
                if (gameId !in payloads) store.commit(identity, ProgressProtocol.encode(gameOp), game.artifacts.associate { it.sha256 to store.artifactFile(identity, it) }, false)
                val stateRefs = original.changes.filter { it.reference in currentChanges && it.checkpointId == checkpoint.checkpointId && it.reference != "save/current" }
                if (checkpoint.stateCompatibility != null && stateRefs.isNotEmpty()) {
                    val state = checkpoint.copy(schemaVersion = 3, checkpointId = id("state-cp:${original.operationId}:${checkpoint.checkpointId}"), gameSaveOperationId = gameId)
                    val op = ProgressOperation(3, id("state:${original.operationId}:${checkpoint.checkpointId}"), identity.binding,
                        stateRefs.map { ProgressRefChange("state/${it.reference}", emptyList(), state.checkpointId, false) }, listOf(state), original.deviceId, original.sessionId, original.createdAtEpochMs)
                    // A damaged state must not prevent migration of its verified game-save bundle.
                    try { if (op.operationId !in payloads) store.commit(identity, ProgressProtocol.encode(op), state.artifacts.associate { it.sha256 to store.artifactFile(identity, it) }, false) }
                    catch (error: kotlinx.coroutines.CancellationException) { throw error }
                    catch (_: Exception) { /* The original, including damaged bytes, stays in history. */ }
                }
            }
        }
    }

    private fun id(value: String) = UUID.nameUUIDFromBytes("rommio-v3-upgrade:$value".toByteArray()).toString()
}
