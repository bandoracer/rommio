package io.github.bandoracer.rommio.domain.progress

import com.squareup.moshi.JsonReader
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.Buffer
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

class UnsupportedProgressSchema(val version: Long) : IllegalArgumentException("Progress schema $version requires a newer client")

object ProgressProtocol {
    const val MAX_PAYLOAD_BYTES = 1_048_576
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val digest = Regex("[0-9a-f]{64}")
    private val identifier = Regex("[A-Za-z0-9._-]{1,128}")
    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(ProgressOperation::class.java)

    fun encode(operation: ProgressOperation): ByteArray {
        validate(operation)
        return adapter.toJson(operation).toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_PAYLOAD_BYTES) }
    }

    fun decode(bytes: ByteArray): ProgressOperation {
        require(bytes.size <= MAX_PAYLOAD_BYTES) { "Progress envelope exceeds size limit" }
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
        val reader = JsonReader.of(Buffer().write(bytes))
        val tree = readStrict(reader, 0)
        require(reader.peek() == JsonReader.Token.END_DOCUMENT) { "Trailing JSON content" }
        val version = (tree as? Map<*, *>)?.get("schemaVersion") as? Long ?: error("Missing schema version")
        if (version !in setOf(2L, 3L)) throw UnsupportedProgressSchema(version)
        if (version == 2L) {
            val checkpoints = (tree as Map<*, *>)["checkpoints"] as? List<*>
            require(checkpoints.orEmpty().none { it is Map<*, *> && "gameSaveOperationId" in it }) { "Pairing edges require schema 3" }
        }
        val operation = requireNotNull(adapter.fromJsonValue(tree))
        validateShape(tree, adapter.serializeNulls().toJsonValue(operation))
        validate(operation)
        return operation
    }

    private fun readStrict(reader: JsonReader, depth: Int): Any? {
        require(depth <= 32) { "Progress JSON is nested too deeply" }
        return when (reader.peek()) {
            JsonReader.Token.BEGIN_OBJECT -> {
                val result = linkedMapOf<String, Any?>()
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName().also { require(Charsets.UTF_8.newEncoder().canEncode(it)) }
                    require(!result.containsKey(key)) { "Duplicate JSON key" }
                    result[key] = readStrict(reader, depth + 1)
                }
                reader.endObject(); result
            }
            JsonReader.Token.BEGIN_ARRAY -> {
                val result = mutableListOf<Any?>(); reader.beginArray()
                while (reader.hasNext()) result.add(readStrict(reader, depth + 1))
                reader.endArray(); result
            }
            JsonReader.Token.STRING -> reader.nextString().also { require(Charsets.UTF_8.newEncoder().canEncode(it)) }
            JsonReader.Token.NUMBER -> reader.nextString().toLongOrNull() ?: error("Progress numbers must be decimal integers")
            JsonReader.Token.BOOLEAN -> reader.nextBoolean()
            JsonReader.Token.NULL -> reader.nextNull<Any>()
            else -> error("Invalid progress JSON")
        }
    }

    private fun validateShape(raw: Any?, decoded: Any?) {
        when (decoded) {
            is Map<*, *> -> {
                require(raw is Map<*, *> && decoded.keys.containsAll(raw.keys)) { "Unknown progress field" }
                raw.forEach { (key, value) -> validateShape(value, decoded[key]) }
            }
            is List<*> -> { require(raw is List<*> && raw.size == decoded.size); raw.indices.forEach { validateShape(raw[it], decoded[it]) } }
            is String -> require(raw is String)
            is Boolean -> require(raw is Boolean)
            is Number -> require(raw is Long)
            null -> require(raw == null)
            else -> error("Unsupported progress value")
        }
    }

    fun validateIdentity(identity: ProgressIdentity) {
        require(uuid.matches(identity.namespaceId) && uuid.matches(identity.contentId)) { "Invalid local progress identity" }
        validateBinding(identity.binding)
    }

    fun validateBinding(binding: ProgressBinding) {
        val url = binding.serverKey.toHttpUrlOrNull() ?: error("Invalid server binding")
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
        require(url.toString().removeSuffix("/") == binding.serverKey) { "Server binding must use a normalized origin and base path" }
        require(binding.accountId == Normalizer.normalize(binding.accountId, Normalizer.Form.NFC))
        require(Charsets.UTF_8.newEncoder().canEncode(binding.accountId))
        require(binding.serverKey.all { it.code in 33..126 })
        require(binding.accountId.isNotBlank() && binding.accountId.length <= 128 && binding.accountId.none { it.isISOControl() })
        require(binding.romId in 0..9_007_199_254_740_991L && binding.files.size in 1..64)
        require(binding.files.map { it.fileId }.distinct().size == binding.files.size)
        binding.files.forEachIndexed { index, file ->
            require(file.fileId in 0..9_007_199_254_740_991L && file.discIndex == index)
            require(digest.matches(file.contentSha256))
            file.containerSha256?.let { require(digest.matches(it)) }
            file.memberPath?.let { validatePath(it); require(file.containerSha256 != null) }
        }
    }

    fun validate(operation: ProgressOperation) {
        if (operation.schemaVersion !in setOf(2, 3)) throw UnsupportedProgressSchema(operation.schemaVersion.toLong())
        require(uuid.matches(operation.operationId) && uuid.matches(operation.deviceId) && uuid.matches(operation.sessionId))
        validateBinding(operation.binding)
        require(operation.createdAtEpochMs in -9_007_199_254_740_991L..9_007_199_254_740_991L)
        require(operation.changes.size in 1..64 && operation.checkpoints.size <= 64)
        require(operation.changes.map { it.reference }.distinct().size == operation.changes.size)
        require(operation.checkpoints.map { it.checkpointId }.distinct().size == operation.checkpoints.size)
        val modern = operation.schemaVersion == 3
        if (modern) {
            val gameChanges = operation.changes.filter { it.reference.startsWith("game/") }
            require(gameChanges.isEmpty() || (gameChanges.size == 1 && operation.changes.size == 1)) { "Game saves and states require independent operations" }
        }
        val checkpoints = operation.checkpoints.associateBy { it.checkpointId }
        operation.checkpoints.forEach { checkpoint ->
            require(checkpoint.schemaVersion == operation.schemaVersion && uuid.matches(checkpoint.checkpointId) && checkpoint.binding == operation.binding)
            require(uuid.matches(checkpoint.deviceId) && uuid.matches(checkpoint.sessionId))
            require(checkpoint.captureReason in setOf("periodic", "manual", "pause", "background", "exit", "safety", "restore", "import"))
            require(checkpoint.createdAtEpochMs in -9_007_199_254_740_991L..9_007_199_254_740_991L)
            if (modern && checkpoint.stateCompatibility != null) require(checkpoint.gameSaveOperationId?.let(uuid::matches) == true && checkpoint.gameSaveOperationId != operation.operationId)
            else require(checkpoint.gameSaveOperationId == null)
            val save = checkpoint.saveCompatibility
            require(listOf(save.system, save.format, save.layoutVersion).all { identifier.matches(it) })
            save.coreId?.let { require(identifier.matches(it)) }
            checkpoint.stateCompatibility?.let { state ->
                require(listOf(state.coreId, state.stateFormat, state.platform, state.architecture).all { identifier.matches(it) })
                require(digest.matches(state.coreSha256) && state.options.size <= 128 && state.systemFiles.size <= 128)
                state.options.forEach { (key, value) -> require(identifier.matches(key) && value.toByteArray().size <= 4096 && value == Normalizer.normalize(value, Normalizer.Form.NFC) && Charsets.UTF_8.newEncoder().canEncode(value)) }
                state.systemFiles.forEach { (path, hash) -> validatePath(path); require(digest.matches(hash)) }
            }
            require(checkpoint.artifacts.size <= 1024)
            val paths = checkpoint.artifacts.map { it.relativePath.lowercase(Locale.ROOT) }
            require(paths.distinct().size == paths.size)
            require(paths.none { path -> paths.any { other -> other.startsWith("$path/") } }) { "Artifact path is also a directory" }
            checkpoint.artifacts.forEach { artifact ->
                validatePath(artifact.relativePath)
                require(digest.matches(artifact.sha256) && artifact.byteLength in 0..8_589_934_592L && identifier.matches(artifact.format))
                require(artifact.role in setOf("sram", "rtc", "memoryCard", "saveFile", "state"))
                require(artifact.byteLength > 0 || (artifact.role != "state" && save.allowsEmpty))
            }
            val states = checkpoint.artifacts.count { it.role == "state" }
            require(states <= 1 && (states == 1) == (checkpoint.stateCompatibility != null))
            require(checkpoint.saveDataComplete || checkpoint.artifacts.none { it.role != "state" })
            require(!checkpoint.saveDataComplete || save.allowsEmpty || checkpoint.artifacts.any { it.role != "state" })
        }
        operation.changes.forEach { change ->
            val gameReference = if (modern) "game/current" else "save/current"
            val resumePrefix = if (modern) "state/resume/" else "resume/"
            val slotPrefix = if (modern) "state/slot/" else "slot/"
            require(change.reference == gameReference || (modern && change.reference.startsWith("game/archive/") && uuid.matches(change.reference.removePrefix("game/archive/"))) ||
                (change.reference.startsWith(resumePrefix) && digest.matches(change.reference.removePrefix(resumePrefix))) ||
                (change.reference.startsWith(slotPrefix) && uuid.matches(change.reference.removePrefix(slotPrefix))))
            require(change.parents.size <= 256 && change.parents.distinct().size == change.parents.size && change.parents.all { uuid.matches(it) && it != operation.operationId })
            require(change.tombstone == (change.checkpointId == null))
            if (!change.tombstone) {
                val checkpoint = checkpoints[change.checkpointId] ?: error("Reference target manifest is missing")
                if (change.reference == gameReference || change.reference.startsWith("game/archive/")) require(checkpoint.saveDataComplete && (!modern || checkpoint.stateCompatibility == null))
                if (modern && change.reference.startsWith(slotPrefix)) require(checkpoint.saveDataComplete && checkpoint.stateCompatibility != null)
                if (change.reference.startsWith(resumePrefix)) {
                    require(change.reference == "$resumePrefix${stateCompatibilityKey(checkpoint)}")
                    require(checkpoint.saveDataComplete && (modern || operation.changes.any { it.reference == "save/current" && !it.tombstone && it.checkpointId == checkpoint.checkpointId })) { "Resume requires a coherent save/current change" }
                }
            }
        }
        require(operation.checkpoints.all { checkpoint -> operation.changes.any { it.checkpointId == checkpoint.checkpointId } })
    }

    fun dependencies(operation: ProgressOperation): List<String> =
        (operation.changes.flatMap { it.parents } + operation.checkpoints.mapNotNull { it.gameSaveOperationId }).distinct()

    fun gameArtifacts(checkpoint: ProgressCheckpoint) = checkpoint.artifacts.filter { it.role != "state" }.sortedBy { it.relativePath }

    fun validatePath(path: String) {
        require(path.isNotEmpty() && path.toByteArray().size <= 1024 && path == Normalizer.normalize(path, Normalizer.Form.NFC))
        require(Charsets.UTF_8.newEncoder().canEncode(path) && path.none { it == '\\' || it == ':' || it.isISOControl() })
        require(path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "Unsafe artifact path" }
    }

    fun bindingKey(binding: ProgressBinding): String = fieldHash(buildList {
        add("rommio-binding-v2"); add(binding.serverKey); add(binding.accountId); add(binding.romId.toString()); add(binding.files.size.toString())
        binding.files.forEach { add(it.fileId.toString()); add(it.discIndex.toString()); add(it.contentSha256); add(it.containerSha256 ?: ""); add(it.memberPath ?: "") }
    })

    fun stateCompatibilityKey(checkpoint: ProgressCheckpoint): String {
        val state = requireNotNull(checkpoint.stateCompatibility) { "No state compatibility descriptor" }
        val save = checkpoint.saveCompatibility
        return fieldHash(buildList {
            add("rommio-state-v2"); add(bindingKey(checkpoint.binding))
            add(save.system); add(save.format); add(save.layoutVersion); add(save.coreId ?: ""); add(save.allowsEmpty.toString())
            add(state.coreId); add(state.coreSha256); add(state.stateFormat); add(state.platform); add(state.architecture)
            for (map in listOf(state.options, state.systemFiles)) {
                add(map.size.toString())
                map.keys.sortedWith { a, b -> compareUTF8(a, b) }.forEach { add(it); add(map.getValue(it)) }
            }
        })
    }

    private fun compareUTF8(a: String, b: String): Int {
        val aa = a.toByteArray(); val bb = b.toByteArray()
        for (i in 0 until minOf(aa.size, bb.size)) { val diff = (aa[i].toInt() and 255) - (bb[i].toInt() and 255); if (diff != 0) return diff }
        return aa.size - bb.size
    }
    private fun fieldHash(fields: List<String>): String = sha256(fields.joinToString("") { "${it.toByteArray().size}:$it" }.toByteArray())
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Parents must already be installed. Each edge belongs to one reference, never just to a game. */
    fun validateParents(operation: ProgressOperation, known: Map<String, ProgressOperation>) {
        operation.checkpoints.mapNotNull { it.gameSaveOperationId }.forEach { gameId ->
            val game = known[gameId] ?: error("Missing paired game-save operation $gameId")
            require(game.binding == operation.binding && game.schemaVersion == 3)
            val change = game.changes.single { it.reference.startsWith("game/") && !it.tombstone }
            val saved = game.checkpoints.single { it.checkpointId == change.checkpointId }
            operation.checkpoints.filter { it.gameSaveOperationId == gameId }.forEach { state ->
                require(state.saveCompatibility == saved.saveCompatibility && gameArtifacts(state) == gameArtifacts(saved)) { "State/game-save pairing differs" }
            }
        }
        operation.changes.forEach { change -> change.parents.forEach { parentId ->
            val parent = known[parentId] ?: error("Missing parent $parentId")
            require(parent.schemaVersion == operation.schemaVersion && parent.binding == operation.binding && parent.changes.any { it.reference == change.reference }) { "Parent belongs to another reference or content" }
        } }
        val visiting = mutableSetOf<String>(); val visited = mutableSetOf<String>()
        val all = known + (operation.operationId to operation)
        val stack = ArrayDeque<Pair<String, Boolean>>()
        stack.addLast(operation.operationId to false)
        while (stack.isNotEmpty()) {
            val (id, exit) = stack.removeLast()
            if (exit) { visiting.remove(id); visited.add(id); continue }
            if (id in visited) continue
            require(visiting.add(id)) { "Cyclic progress ancestry" }
            stack.addLast(id to true)
            all[id]?.let(::dependencies)?.forEach { stack.addLast(it to false) }
        }
    }
}
