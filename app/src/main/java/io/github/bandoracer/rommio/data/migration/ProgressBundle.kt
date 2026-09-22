package io.github.bandoracer.rommio.data.migration

import io.github.bandoracer.rommio.data.local.*
import io.github.bandoracer.rommio.domain.progress.*
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Base64 preserves immutable bytes, including JSON whitespace and quarantined unknown schemas. */
data class MigrationProgressOperation(
    val namespaceId: String, val contentId: String, val operationId: String,
    val payloadBase64: String, val payloadHash: String, val localSequence: Long,
)
data class MigrationProgressQuarantine(
    val namespaceId: String, val contentId: String, val payloadHash: String, val payloadBase64: String, val reason: String,
)
/** A null digest describes a directory; directory entries preserve empty native working paths. */
data class MigrationProgressEntry(val path: String, val byteLength: Long, val sha256: String?)
data class MigrationProgressSnapshot(
    val version: Int = 1,
    val namespaces: List<ProgressNamespaceRow> = emptyList(),
    val contents: List<ProgressContentRow> = emptyList(),
    val operations: List<MigrationProgressOperation> = emptyList(),
    val heads: List<ProgressHeadRow> = emptyList(),
    val outbox: List<ProgressOutboxRow> = emptyList(),
    val quarantine: List<MigrationProgressQuarantine> = emptyList(),
    val history: List<ProgressHistoryRow> = emptyList(),
    val historyIndex: List<ProgressHistoryIndexRow> = emptyList(),
    val entries: List<MigrationProgressEntry> = emptyList(),
)

/** Snapshot/restore is called under the application barrier and the caller's Room transaction. */
internal class ProgressBundle(private val dao: ProgressDao) {
    suspend fun snapshot(root: File): MigrationProgressSnapshot = MigrationProgressSnapshot(
        namespaces = dao.exportNamespaces(), contents = dao.exportContents(),
        operations = dao.exportOperations().map { MigrationProgressOperation(it.namespaceId, it.contentId, it.operationId,
            Base64.getEncoder().encodeToString(it.payload), it.payloadHash, it.localSequence) },
        heads = dao.exportHeads(), outbox = dao.exportOutbox(),
        quarantine = dao.exportQuarantine().map { MigrationProgressQuarantine(it.namespaceId, it.contentId, it.payloadHash,
            Base64.getEncoder().encodeToString(it.payload), it.reason) },
        history = dao.exportHistory(), historyIndex = dao.exportHistoryIndex(), entries = inventory(root),
    ).also(::validate)

    suspend fun restore(snapshot: MigrationProgressSnapshot) {
        validate(snapshot)
        dao.clearHistoryIndex(); dao.clearHistory(); dao.clearQuarantine(); dao.clearOutbox()
        dao.clearHeads(); dao.clearOperations(); dao.clearContents(); dao.clearNamespaces()
        snapshot.namespaces.forEach { dao.insertNamespace(it) }; snapshot.contents.forEach { dao.insertContent(it) }
        snapshot.operations.forEach { dao.insertOperation(ProgressOperationRow(it.namespaceId, it.contentId, it.operationId,
            decodePayload(it.payloadBase64), it.payloadHash, it.localSequence)) }
        snapshot.heads.forEach { dao.insertHead(it) }
        snapshot.outbox.forEach {
            // Preserve intent and acknowledgment; a ticket from another process can never be live here.
            dao.insertOutbox(it.copy(status = if (it.status == "LEASED") "PENDING" else it.status, leaseId = null, leaseUntil = null))
        }
        snapshot.quarantine.forEach { dao.quarantine(ProgressQuarantineRow(it.namespaceId, it.contentId, it.payloadHash,
            decodePayload(it.payloadBase64), it.reason)) }
        snapshot.history.forEach { dao.insertHistory(it) }; snapshot.historyIndex.forEach { dao.updateHistoryIndex(it) }
    }

    companion object {
        const val MAX_METADATA_BYTES = 64L * 1024 * 1024
        const val MAX_ENTRIES = 200_000
        private val hashPattern = Regex("[0-9a-f]{64}")
        private val reserved = setOf(".owner.lock", ImportRecoveryJournal.GENERATION)
        private val statuses = setOf("PENDING", "LEASED", "PUBLISHED", "LOCAL_ONLY", "PARKED_AUTH")
        private fun uuid(value: String) { require(UUID.fromString(value).toString() == value) { "Invalid progress identity in bundle." } }
        private fun hash(value: String) { require(hashPattern.matches(value)) { "Invalid progress hash in bundle." } }
        private fun decodePayload(text: String): ByteArray {
            require(text.length <= (ProgressProtocol.MAX_PAYLOAD_BYTES + 2) / 3 * 4)
            val bytes = Base64.getDecoder().decode(text)
            require(bytes.size <= ProgressProtocol.MAX_PAYLOAD_BYTES && Base64.getEncoder().encodeToString(bytes) == text)
            return bytes
        }
        private fun key(namespace: String, content: String, operation: String) = Triple(namespace, content, operation)
        private fun <T, K> unique(rows: List<T>, key: (T) -> K): Map<K, T> {
            require(rows.size <= MAX_ENTRIES) { "Progress metadata exceeds the supported bundle limit." }
            return rows.associateBy(key).also { require(it.size == rows.size) { "Duplicate progress metadata in bundle." } }
        }

        /** Validate ownership, graph, derived references and exact record bytes before any activation. */
        fun validate(snapshot: MigrationProgressSnapshot) {
            require(snapshot.version == 1) { "Unsupported progress bundle version." }
            val namespaces = unique(snapshot.namespaces) { it.namespaceId }
            unique(snapshot.namespaces) { it.serverKey to it.accountId }
            namespaces.values.forEach { row ->
                uuid(row.namespaceId)
                // Reuse protocol normalization without inventing a content binding for an empty namespace.
                ProgressProtocol.validateBinding(ProgressBinding(row.serverKey, row.accountId, 0,
                    listOf(ProgressContentFile(0, 0, "0".repeat(64), null, null))))
            }
            val contents = unique(snapshot.contents) { it.contentId }
            unique(snapshot.contents) { it.namespaceId to it.bindingKey }
            contents.values.forEach { uuid(it.contentId); require(it.namespaceId in namespaces); hash(it.bindingKey) }
            fun owner(namespace: String, content: String) {
                require(contents[content]?.namespaceId == namespace) { "Progress record has an unknown or mismatched owner." }
            }
            val rows = unique(snapshot.operations) { key(it.namespaceId, it.contentId, it.operationId) }
            unique(snapshot.operations) { key(it.namespaceId, it.contentId, it.localSequence.toString()) }
            val decoded = rows.mapValues { (_, row) ->
                owner(row.namespaceId, row.contentId); uuid(row.operationId); hash(row.payloadHash)
                require(row.localSequence in 1..Long.MAX_VALUE / 64)
                val bytes = decodePayload(row.payloadBase64)
                require(ProgressProtocol.sha256(bytes) == row.payloadHash) { "Progress operation digest mismatch." }
                ProgressProtocol.decode(bytes).also { op ->
                    val namespace = namespaces.getValue(row.namespaceId)
                    require(op.operationId == row.operationId && op.binding.serverKey == namespace.serverKey && op.binding.accountId == namespace.accountId)
                    require(ProgressProtocol.bindingKey(op.binding) == contents.getValue(row.contentId).bindingKey) { "Progress content binding mismatch." }
                }
            }
            val expectedHeads = mutableSetOf<ProgressHeadRow>()
            val checkpoints = mutableMapOf<Triple<String, String, String>, ProgressCheckpoint>()
            // Local sequences must be a topological order. Validate edges in one pass, without a recursive full-graph scan per row.
            snapshot.operations.sortedBy { it.localSequence }.forEach { row ->
                val op = decoded.getValue(key(row.namespaceId, row.contentId, row.operationId))
                val dependencies = ProgressProtocol.dependencies(op).associateWith { id ->
                    val parentKey = key(row.namespaceId, row.contentId, id)
                    require(requireNotNull(rows[parentKey]).localSequence < row.localSequence)
                    decoded.getValue(parentKey)
                }
                ProgressProtocol.validateParents(op, dependencies)
                op.changes.forEach { change ->
                    change.parents.forEach { parentId ->
                        val parentKey = key(row.namespaceId, row.contentId, parentId)
                        val parent = requireNotNull(rows[parentKey]) { "Progress ancestry is incomplete." }
                        require(parent.localSequence < row.localSequence && decoded.getValue(parentKey).changes.any { it.reference == change.reference }) {
                            "Invalid progress ancestry in bundle."
                        }
                        expectedHeads.remove(ProgressHeadRow(row.namespaceId, row.contentId, change.reference, parentId))
                    }
                    expectedHeads.add(ProgressHeadRow(row.namespaceId, row.contentId, change.reference, row.operationId))
                }
                op.checkpoints.forEach { checkpoint ->
                    val prior = checkpoints.put(key(row.namespaceId, row.contentId, checkpoint.checkpointId), checkpoint)
                    require(prior == null || prior == checkpoint) { "Checkpoint identity has different manifests." }
                }
            }
            require(snapshot.heads.size <= MAX_ENTRIES && snapshot.heads.toSet().size == snapshot.heads.size && snapshot.heads.toSet() == expectedHeads) {
                "Progress references do not match their operation history."
            }
            val outbox = unique(snapshot.outbox) { key(it.namespaceId, it.contentId, it.operationId) }
            require(outbox.keys == rows.keys) { "Progress publication intent is incomplete." }
            outbox.forEach { (id, row) ->
                require(row.payloadHash == rows.getValue(id).payloadHash && row.status in statuses && row.attempts >= 0)
                if (row.status == "LEASED") { uuid(requireNotNull(row.leaseId)); require(row.leaseUntil != null) }
                else require(row.leaseId == null && row.leaseUntil == null)
            }
            unique(snapshot.quarantine) { key(it.namespaceId, it.contentId, it.payloadHash) }.values.forEach { row ->
                owner(row.namespaceId, row.contentId); hash(row.payloadHash)
                require(ProgressProtocol.sha256(decodePayload(row.payloadBase64)) == row.payloadHash)
                // Unknown/malformed envelopes remain quarantined. Never decode them as active progress.
            }
            unique(snapshot.history) { key(it.namespaceId, it.contentId, it.checkpointId) }.values.forEach { row ->
                owner(row.namespaceId, row.contentId)
                val operationKey = key(row.namespaceId, row.contentId, row.operationId)
                val op = requireNotNull(decoded[operationKey])
                val position = op.checkpoints.indexOfFirst { it.checkpointId == row.checkpointId }
                require(position >= 0 && row.sequence == rows.getValue(operationKey).localSequence * 64 + position)
                require(row.pinOverride == null || row.pinned == row.pinOverride)
            }
            val maxima = snapshot.operations.groupBy { it.namespaceId to it.contentId }.mapValues { (_, ops) -> ops.maxOf { it.localSequence } }
            unique(snapshot.historyIndex) { it.namespaceId to it.contentId }.values.forEach {
                owner(it.namespaceId, it.contentId); require(it.throughRowId in 0..(maxima[it.namespaceId to it.contentId] ?: 0))
            }
            validateEntries(snapshot.entries)
        }

        fun validateEntries(entries: List<MigrationProgressEntry>) {
            val paths = unique(entries) { it.path }
            entries.forEach { entry ->
                ProgressProtocol.validatePath(entry.path)
                require(entry.path.substringBefore('/') !in reserved) { "Reserved progress metadata in bundle." }
                if (entry.sha256 == null) require(entry.byteLength == 0L)
                else { hash(entry.sha256); require(entry.byteLength in 0..8_589_934_592L) }
                var parent = entry.path.substringBeforeLast('/', "")
                while (parent.isNotEmpty()) {
                    require(paths[parent]?.sha256 == null && parent in paths) { "Progress parent directory is missing or is a file." }
                    parent = parent.substringBeforeLast('/', "")
                }
            }
        }

        /** Inventory all recovery bytes as opaque data; only explicit recovery may interpret orphan journals. */
        fun inventory(root: File): List<MigrationProgressEntry> {
            require(!Files.isSymbolicLink(root.toPath()))
            if (!root.exists()) return emptyList()
            require(root.isDirectory)
            val result = mutableListOf<MigrationProgressEntry>()
            root.walkTopDown().forEach { file ->
                require(!Files.isSymbolicLink(file.toPath())) { "Progress backups cannot follow symbolic links." }
                val path = file.relativeTo(root).invariantSeparatorsPath
                if (path.isEmpty() || path in reserved) return@forEach
                require(result.size < MAX_ENTRIES)
                result.add(if (file.isDirectory) MigrationProgressEntry(path, 0, null) else {
                    require(file.isFile) { "Unsupported progress file." }
                    file.inputStream().use { describe(path, it) }
                })
            }
            return result.sortedBy { it.path }.also(::validateEntries)
        }

        fun describe(path: String, input: InputStream, output: OutputStream? = null): MigrationProgressEntry {
            ProgressProtocol.validatePath(path)
            val digest = MessageDigest.getInstance("SHA-256"); var size = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                size = Math.addExact(size, count.toLong())
                require(size <= 8_589_934_592L) { "Progress file exceeds bundle limit." }
                digest.update(buffer, 0, count); output?.write(buffer, 0, count)
            }
            return MigrationProgressEntry(path, size, digest.digest().joinToString("") { "%02x".format(it) })
        }

        fun verifyEntries(expected: List<MigrationProgressEntry>, actual: List<MigrationProgressEntry>) {
            validateEntries(expected); validateEntries(actual)
            require(expected.toSet() == actual.toSet()) { "Progress files do not match the bundle inventory." }
        }

        data class Summary(val checkpoints: Int, val bytes: Long, val artifactIssues: Int)
        fun summary(snapshot: MigrationProgressSnapshot): Summary {
            val entries = snapshot.entries.associateBy { it.path }
            val evicted = snapshot.history.filter { it.evicted }.map { key(it.namespaceId, it.contentId, it.checkpointId) }.toSet()
            val checkpoints = mutableSetOf<Triple<String, String, String>>()
            val issues = mutableSetOf<String>()
            snapshot.operations.forEach { row ->
                ProgressProtocol.decode(decodePayload(row.payloadBase64)).checkpoints.forEach { checkpoint ->
                    val id = key(row.namespaceId, row.contentId, checkpoint.checkpointId)
                    checkpoints.add(id)
                    if (id !in evicted) checkpoint.artifacts.forEach { artifact ->
                        val path = "${row.namespaceId}/${row.contentId}/blobs/${artifact.sha256}"
                        if (entries[path]?.let { it.byteLength == artifact.byteLength && it.sha256 == artifact.sha256 } != true) issues.add(path)
                    }
                }
            }
            return Summary(checkpoints.size, snapshot.entries.fold(0L) { total, entry -> Math.addExact(total, entry.byteLength) }, issues.size)
        }
    }
}
