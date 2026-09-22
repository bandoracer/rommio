package io.github.bandoracer.rommio.domain.progress

import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.*

/** The transport is account-bound before construction. Names are immutable protocol identities. */
data class ProgressRemoteRecord(val name: String, val id: Int, val byteLength: Long)
interface ProgressTransport {
    suspend fun inventory(): List<ProgressRemoteRecord>
    suspend fun read(record: ProgressRemoteRecord, destination: File, maxBytes: Long)
    suspend fun upload(name: String, source: File): ProgressRemoteRecord
}
data class ProgressSyncResult(val gamePending: Int, val statePending: Int, val conflicts: Int, val errors: List<String>, val gameConflicts: Int = 0, val stateConflicts: Int = 0) {
    val message: String get() = "Game saves: ${if (gameConflicts > 0) "choice required" else if (gamePending == 0 && errors.isEmpty()) "synced" else if (gamePending == 0) "check incomplete" else "$gamePending pending"}. " +
        "Resume/states: ${if (stateConflicts > 0) "choice required" else if (statePending == 0 && errors.isEmpty()) "synced" else if (statePending == 0) "check incomplete" else "$statePending pending"}." +
        (if (conflicts > 0) " $conflicts conflicting references need a choice." else "") +
        errors.firstOrNull()?.let { " $it" }.orEmpty()
}

/** Complete mode-B inventory, exact retry bytes, game-first transfer, and causal branch preservation. */
class ProgressSync(private val store: ProgressStore, private val staging: File) {
    suspend fun synchronize(identity: ProgressIdentity, remote: ProgressTransport): ProgressSyncResult = withContext(Dispatchers.IO) {
        require(staging.isDirectory || staging.mkdirs())
        val run = File(staging, UUID.randomUUID().toString()).apply { check(mkdir()) }
        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        val prefix = "__rommio3_${ProgressProtocol.bindingKey(identity.binding)}_"
        // Reuse validated immutable envelopes within this reconciliation only.
        // Disk-backed caching bounds heap use when retained history is large.
        val envelopes = mutableMapOf<ProgressRemoteRecord, File>()
        fun opName(op: ProgressOperation, bytes: ByteArray) = "${prefix}op_${op.operationId}_${ProgressProtocol.sha256(bytes)}.json"
        fun blobName(hash: String) = "${prefix}blob_$hash.bin"
        suspend fun inventory() = remote.inventory().also { require(it.size <= 100_000) }.filter { it.name.startsWith(prefix) }
        suspend fun bytes(record: ProgressRemoteRecord, max: Long): Pair<File, ByteArray?> {
            require(record.byteLength in 1..max)
            val file = File(run, UUID.randomUUID().toString())
            remote.read(record, file, max)
            require(file.length() == record.byteLength) { "Remote size mismatch" }
            return file to if (max == ProgressProtocol.MAX_PAYLOAD_BYTES.toLong()) file.readBytes() else null
        }
        suspend fun receive(records: List<ProgressRemoteRecord>) {
            val localPayloads = store.operationPayloads(identity).associateBy { ProgressProtocol.decode(it).operationId }
            val known = localPayloads.mapValues { ProgressProtocol.decode(it.value) }.toMutableMap()
            val pending = linkedMapOf<String, Pair<ByteArray, ProgressOperation>>()
            val blocked = mutableSetOf<String>()
            for (record in records.filter { it.name.startsWith("${prefix}op_") }) {
                try {
                    val cached = envelopes[record]
                    val downloaded = if (cached == null) bytes(record, ProgressProtocol.MAX_PAYLOAD_BYTES.toLong()) else null
                    val payload = downloaded?.second ?: cached!!.readBytes()
                    val op = ProgressProtocol.decode(payload)
                    require(op.schemaVersion == 3 && op.binding == identity.binding && record.name == opName(op, payload)) { "Remote operation identity mismatch" }
                    if (downloaded != null) envelopes[record] = downloaded.first
                    if (op.operationId in blocked) continue
                    val duplicate = pending[op.operationId]
                    if (duplicate != null && !duplicate.first.contentEquals(payload)) {
                        pending.remove(op.operationId); blocked.add(op.operationId)
                    }
                    require(duplicate == null || duplicate.first.contentEquals(payload)) { "Remote operation ID reused" }
                    val local = known[op.operationId]
                    if (local != null) {
                        val exact = localPayloads.getValue(op.operationId)
                        require(exact.contentEquals(payload)) { "Remote operation differs from local immutable bytes" }
                        if (op.checkpoints.any { cp ->
                            val health = store.inspectCheckpoint(identity, cp.checkpointId)
                            health.retention == ProgressLocalRetention.RETAINED && !health.isLocallyAvailable
                        }) pending[op.operationId] = payload to op
                    } else pending[op.operationId] = payload to op
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { errors.add(error.message ?: "Remote manifest unavailable") }
            }
            var progressed: Boolean
            do {
                progressed = false
                val ordered = pending.values.sortedBy { (_, op) -> if (op.changes.any { it.reference.startsWith("game/") }) 0 else 1 }
                for ((payload, op) in ordered) {
                    if (!ProgressProtocol.dependencies(op).all { it in known }) continue
                    try {
                        val sources = linkedMapOf<String, File>()
                        for (artifact in op.checkpoints.flatMap { it.artifacts }.distinctBy { it.sha256 }) {
                            sources[artifact.sha256] = try { store.artifactFile(identity, artifact) } catch (_: Exception) {
                                val candidates = records.filter { it.name == blobName(artifact.sha256) }
                                require(candidates.isNotEmpty()) { "Waiting for ${if (artifact.role == "state") "snapshot" else "game-save"} bytes" }
                                val file = File(run, UUID.randomUUID().toString())
                                remote.read(candidates.first(), file, minOf(artifact.byteLength, 256L * 1024 * 1024))
                                require(file.length() == artifact.byteLength && fileHash(file) == artifact.sha256) { "Remote artifact verification failed" }
                                file
                            }
                        }
                        if (op.operationId in known) op.checkpoints.forEach { cp ->
                            if (!store.inspectCheckpoint(identity, cp.checkpointId).isLocallyAvailable) store.repairCheckpoint(identity, cp.checkpointId, sources)
                        }
                        store.received(identity, payload, sources)
                        known[op.operationId] = op; pending.remove(op.operationId); progressed = true
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { errors.add(error.message ?: "Remote progress unavailable"); pending.remove(op.operationId) }
                }
            } while (progressed)
            if (pending.isNotEmpty()) errors.add("Waiting for ${pending.size} operation dependencies")
        }
        suspend fun publish(states: Boolean) {
            val attempted = mutableSetOf<String>()
            while (true) {
                val lease = store.leaseNext(identity, System.currentTimeMillis(), 120_000, states) ?: break
                if (!attempted.add(lease.operationId)) break
                try {
                    val op = ProgressProtocol.decode(lease.payload)
                    var records = inventory()
                    suspend fun ensure(name: String, source: File, expectedHash: String, max: Long) {
                        val existing = records.filter { it.name == name }
                        if (existing.isEmpty()) {
                            // If the response is lost, retry discovers this exact name before uploading again.
                            val uploaded = remote.upload(name, source)
                            require(uploaded.name == name)
                            records = inventory()
                        }
                        val candidates = records.filter { it.name == name }
                        require(candidates.isNotEmpty()) { "Upload is not yet visible" }
                        for (record in candidates) {
                            val copy = File(run, UUID.randomUUID().toString())
                            remote.read(record, copy, max)
                            require(copy.length() == source.length() && fileHash(copy) == expectedHash) { "Readback differs; immutable data was not overwritten" }
                            copy.delete()
                        }
                    }
                    for (artifact in op.checkpoints.flatMap { it.artifacts }.distinctBy { it.sha256 }) {
                        ensure(blobName(artifact.sha256), store.artifactFile(identity, artifact), artifact.sha256, artifact.byteLength)
                    }
                    val envelope = File(run, "${op.operationId}.json")
                    FileOutputStream(envelope).use { it.write(lease.payload); it.fd.sync() }
                    ensure(opName(op, lease.payload), envelope, lease.payloadHash, ProgressProtocol.MAX_PAYLOAD_BYTES.toLong())
                    check(store.acknowledge(lease, lease.payloadHash))
                } catch (error: CancellationException) {
                    withContext(NonCancellable) { store.retry(lease, 0, "Transfer interrupted") }; throw error
                } catch (error: Exception) {
                    val auth = error is retrofit2.HttpException && error.code() in setOf(401, 403)
                    store.retry(lease, System.currentTimeMillis() + 15_000, error.message ?: "Transfer failed", auth)
                    errors.add(error.message ?: "Transfer failed")
                }
            }
        }
        try {
            store.enablePublication(identity)
            store.resumeAuthenticatedWork(identity)
            receive(inventory())
            publish(false)
            // The lanes have independent leases/ancestry. Keep checking for new game saves while
            // snapshots transfer, so an active player's smaller writes do not wait behind a snapshot.
            coroutineScope {
                val stateLane = async { publish(true) }
                while (!stateLane.isCompleted) { publish(false); delay(200) }
                stateLane.await()
            }
            receive(inventory())
            val operations = store.operationPayloads(identity).map(ProgressProtocol::decode).associateBy { it.operationId }
            val pending = store.publicationStatus(identity).filter { it.status != "PUBLISHED" && operations[it.operationId]?.schemaVersion == 3 }
            val gamePending = pending.count { operations.getValue(it.operationId).changes.any { c -> c.reference.startsWith("game/") } }
            val references = store.referenceSnapshot(identity)
            val gameConflicts = if (references["game/current"].orEmpty().size > 1) 1 else 0
            val stateConflicts = references.filterKeys { it.startsWith("state/") }.count { it.value.size > 1 }
            ProgressSyncResult(gamePending, pending.size - gamePending, gameConflicts + stateConflicts, errors.distinct(), gameConflicts, stateConflicts)
        } finally { withContext(NonCancellable) { run.deleteRecursively() } }
    }
    companion object {
        fun fileHash(file: File): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
