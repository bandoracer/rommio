package io.github.bandoracer.rommio.domain.progress

import android.system.Os
import android.system.OsConstants
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.github.bandoracer.rommio.domain.player.CoreBinary
import io.github.bandoracer.rommio.domain.player.PlayerSession
import io.github.bandoracer.rommio.domain.storage.AtomicFiles
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class ProgressWorkspace internal constructor(
    val sessionId: String,
    val session: PlayerSession,
    val directory: File,
    internal val lease: ProgressSessionLease,
) {
    val identity: ProgressIdentity get() = lease.identity
    val stagingDirectory: File get() = File(directory, "captures")
}

data class AbandonedProgressWorkspace(
    val sessionId: String,
    val directory: File,
    val baselineCheckpointId: String?,
    val problem: String?,
)

internal data class ProgressWorkspaceRecord(
    val version: Int = 1,
    val sessionId: String,
    val identity: ProgressIdentity,
    val coreSha256: String,
    val runtimeId: String,
    val contentPath: String,
    val firmware: Map<String, String>,
    val phase: String = "preparing",
    val checkpointId: String? = null,
)

/** All calls run under ProgressStore's IO/mutation barrier and process ownership lock.
 * Orphan bytes are inventoried for explicit recovery; they never become progress by file age. */
internal class ProgressWorkspaceFiles {
    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        .adapter(ProgressWorkspaceRecord::class.java).failOnUnknown()

    suspend fun prepare(root: File, identity: ProgressIdentity, source: PlayerSession, lease: ProgressSessionLease): ProgressWorkspace {
        require(source.initialSaveRam == null) { "Working sessions must not be seeded with legacy saves." }
        require(source.progressBinding == identity.binding && source.romId.toLong() == identity.binding.romId)
        val file = identity.binding.files.singleOrNull()
        require(file != null && file.discIndex == 0 && file.containerSha256 == null && file.memberPath == null) {
            "Archive members and disc sets require a qualified working-file adapter."
        }
        val core = requireNotNull(source.coreIdentity)
        require(core.runtimeId == source.runtimeProfile.runtimeId)
        require(source.coreLibrary.isFile && !Files.isSymbolicLink(source.coreLibrary.toPath()) && source.coreLibrary.length() == core.sizeBytes)
        require(CoreBinary.sha256(source.coreLibrary) == core.sha256) { "Core changed before workspace preparation." }
        require(source.firmwareSha256.size <= 128)
        source.firmwareSha256.forEach { (path, hash) ->
            ProgressProtocol.validatePath(path)
            require(hash.matches(Regex("[a-f0-9]{64}")))
        }
        val firmwarePaths = source.firmwareSha256.keys.map { it.lowercase(java.util.Locale.ROOT) }
        require(firmwarePaths.distinct().size == firmwarePaths.size && firmwarePaths.none { a -> firmwarePaths.any { b -> b.startsWith("$a/") } })
        val contentPath = "content/${source.romPath.name}"
        ProgressProtocol.validatePath(contentPath)
        val sessionId = UUID.randomUUID().toString()
        val active = File(root, "sessions").also(::directory)
        val staged = File(active, ".preparing-$sessionId").also(::directory)
        val record = ProgressWorkspaceRecord(sessionId = sessionId, identity = identity, coreSha256 = core.sha256,
            runtimeId = core.runtimeId, contentPath = contentPath, firmware = source.firmwareSha256.toMap())
        // Persist ownership before installing anything that could be mistaken for game progress.
        write(staged, record)
        copy(source.romPath, File(staged, contentPath), file.contentSha256, 8_589_934_592L)
        for ((path, hash) in record.firmware) {
            ProgressProtocol.validatePath(path)
            copy(File(source.systemDirectory, path), File(staged, "system/$path"), hash, 16L * 1024 * 1024)
        }
        listOf("saves", "states", "system", "captures").forEach { directory(File(staged, it)) }
        write(staged, record.copy(phase = "prepared"))
        val final = File(active, sessionId)
        Files.move(staged.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE)
        sync(active)
        return ProgressWorkspace(sessionId, source.copy(
            romPath = File(final, contentPath), systemDirectory = File(final, "system"),
            savesDirectory = File(final, "saves"), saveRamFile = File(final, "saves/save.srm"),
            saveStatesDirectory = File(final, "states"), initialSaveRam = null,
            firmwareSha256 = record.firmware, variables = source.variables.toMap(),
        ), final, lease)
    }

    fun select(workspace: ProgressWorkspace, checkpointId: String?) {
        val record = read(workspace.directory, workspace.identity)
        check(record.phase == "prepared") { "This working session has already selected progress." }
        write(workspace.directory, record.copy(phase = "selected", checkpointId = checkpointId))
    }

    /** A clean close removes only unchanged runtime inputs and empty output directories.
     * Unexpected core files or pending captures retain the entire workspace for recovery. */
    suspend fun finish(root: File, workspace: ProgressWorkspace, committedOutputBytes: ByteArray? = null) {
        val record = read(workspace.directory, workspace.identity)
        val handyEEPROM = "saves/${File(record.contentPath).nameWithoutExtension}.eeprom"
        val completeOutputs = when (record.runtimeId) {
            "ppsspp" -> committedOutputBytes != null && PSPMemoryStickBundle.matches(File(workspace.directory, "saves"), committedOutputBytes)
            "handy" -> committedOutputBytes != null && if (committedOutputBytes.isEmpty()) !File(workspace.directory, handyEEPROM).exists()
                else File(workspace.directory, handyEEPROM).isFile && File(workspace.directory, handyEEPROM).readBytes().contentEquals(committedOutputBytes)
            else -> true
        }
        val outputFiles = if (record.runtimeId == "ppsspp" && committedOutputBytes != null && completeOutputs) {
            PSPMemoryStickBundle.files(committedOutputBytes).filterValues { !it.directory }.map { (path, entry) ->
                "saves/$path" to ProgressProtocol.sha256(entry.bytes)
            }.toMap()
        } else if (record.runtimeId == "handy" && committedOutputBytes != null && committedOutputBytes.isNotEmpty() && completeOutputs) {
            mapOf(handyEEPROM to ProgressProtocol.sha256(committedOutputBytes))
        } else emptyMap()
        val expected = outputFiles + mapOf(record.contentPath to record.identity.binding.files.single().contentSha256) +
            record.firmware.mapKeys { "system/${it.key}" }
        var pristine = completeOutputs && expected.keys.all { File(workspace.directory, it).isFile }
        Files.walkFileTree(workspace.directory.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(path: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                val relative = workspace.directory.toPath().relativize(path).toString()
                if (Files.isSymbolicLink(path) || !attrs.isRegularFile) pristine = false
                else if (relative != "session.json" && (expected[relative] == null || CoreBinary.sha256(path.toFile()) != expected[relative])) pristine = false
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
        if (!pristine) {
            archive(root, workspace.directory, workspace.sessionId)
            return
        }
        // A cleanup marker is only installed after native teardown and the complete file audit.
        val closed = record.copy(phase = "closed-clean")
        write(workspace.directory, closed)
        val markers = File(root, "cleanup-markers").also(::directory)
        val marker = File(markers, "${workspace.sessionId}.json")
        AtomicFiles.writeBytes(marker, adapter.toJson(closed).toByteArray())
        sync(markers)
        val cleanup = File(workspace.directory.parentFile, ".cleanup-${workspace.sessionId}")
        Files.move(workspace.directory.toPath(), cleanup.toPath(), StandardCopyOption.ATOMIC_MOVE)
        sync(requireNotNull(cleanup.parentFile))
        remove(cleanup)
        sync(requireNotNull(cleanup.parentFile))
        Files.delete(marker.toPath()); sync(markers)
    }

    fun recover(root: File, identity: ProgressIdentity, active: ProgressWorkspace?): List<AbandonedProgressWorkspace> {
        val sessions = File(root, "sessions").also(::directory)
        // The independent journal survives deletion of session.json inside a partially removed tree.
        val markers = File(root, "cleanup-markers")
        if (markers.exists()) {
            directory(markers)
            markers.listFiles().orEmpty().forEach { marker ->
                // An interrupted atomic marker write has no authority to delete a workspace.
                if (marker.name.startsWith(".rommio-") && marker.name.endsWith(".partial")) return@forEach
                val id = marker.name.removeSuffix(".json")
                require(validId(id) && marker.name == "$id.json")
                val record = readFile(marker, identity)
                require(record.sessionId == id && record.phase == "closed-clean")
                if (id == active?.sessionId) return@forEach
                val original = File(sessions, id)
                val cleanup = File(sessions, ".cleanup-$id")
                if (original.exists()) {
                    require(read(original, identity).phase == "closed-clean" && !cleanup.exists())
                    Files.move(original.toPath(), cleanup.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    sync(sessions)
                }
                if (cleanup.exists() || Files.isSymbolicLink(cleanup.toPath())) remove(cleanup)
                sync(sessions)
                Files.delete(marker.toPath()); sync(markers)
            }
        }
        sessions.listFiles().orEmpty().forEach { entry ->
            if (entry == active?.directory) return@forEach
            require(!Files.isSymbolicLink(entry.toPath()) && entry.isDirectory) { "Unsafe working-session entry; retained for recovery." }
            val id = entry.name.removePrefix(".preparing-").removePrefix(".cleanup-")
            require(validId(id)) { "Unknown working-session entry; retained for recovery." }
            if (entry.name.startsWith(".cleanup-")) {
                error("Unproven cleanup marker; files retained.")
            } else archive(root, entry, id)
        }
        sync(sessions)
        return inventory(root, identity)
    }

    fun inventory(root: File, identity: ProgressIdentity): List<AbandonedProgressWorkspace> {
        val abandoned = File(root, "abandoned-sessions")
        if (!abandoned.exists()) return emptyList()
        directory(abandoned)
        return abandoned.listFiles().orEmpty().sortedBy { it.name }.map { entry ->
            try {
                require(validId(entry.name) && !Files.isSymbolicLink(entry.toPath()) && entry.isDirectory)
                val record = read(entry, identity)
                require(record.sessionId == entry.name)
                AbandonedProgressWorkspace(entry.name, entry, record.checkpointId, null)
            } catch (error: Exception) {
                AbandonedProgressWorkspace(entry.name, entry, null, error.message ?: "Unreadable session record; files retained.")
            }
        }
    }

    fun discard(root: File, identity: ProgressIdentity, sessionId: String) {
        require(validId(sessionId))
        val candidate = inventory(root, identity).single { it.sessionId == sessionId }
        // Explicit discard can resolve unreadable records; recovery/publication still requires valid metadata.
        require(candidate.directory.isDirectory && !Files.isSymbolicLink(candidate.directory.toPath()))
        remove(candidate.directory)
        sync(requireNotNull(candidate.directory.parentFile))
    }

    fun pending(root: File, identity: ProgressIdentity, sessionId: String): List<PendingWorkspaceCheckpoint> {
        val directory = abandoned(root, identity, sessionId)
        val captures = File(directory, "captures")
        if (!captures.exists()) return emptyList()
        require(captures.isDirectory && !Files.isSymbolicLink(captures.toPath()))
        return captures.listFiles().orEmpty().sortedBy { it.name }.map { entry ->
            try {
                require(validId(entry.name))
                val replay = PendingCheckpointJournal.read(entry, identity, sessionId)
                PendingWorkspaceCheckpoint(entry.name, replay.operation.operationId, null)
            } catch (error: Exception) { PendingWorkspaceCheckpoint(entry.name, null, error.message ?: "Incomplete capture; bytes retained.") }
        }
    }

    fun pendingDirectory(root: File, identity: ProgressIdentity, sessionId: String, captureId: String): File {
        require(validId(captureId))
        val captures = File(abandoned(root, identity, sessionId), "captures")
        require(captures.isDirectory && !Files.isSymbolicLink(captures.toPath()))
        return File(captures, captureId)
    }

    private fun abandoned(root: File, identity: ProgressIdentity, sessionId: String): File {
        require(validId(sessionId))
        val candidate = inventory(root, identity).single { it.sessionId == sessionId }
        require(candidate.problem == null) { "Repair the workspace record before recovering its files." }
        return candidate.directory
    }

    private fun archive(root: File, source: File, sessionId: String) {
        val abandoned = File(root, "abandoned-sessions").also(::directory)
        val destination = File(abandoned, sessionId)
        check(!destination.exists()) { "A recovery session with this ID already exists; both copies are retained." }
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        sync(abandoned); sync(requireNotNull(source.parentFile))
    }

    private fun read(directory: File, identity: ProgressIdentity): ProgressWorkspaceRecord {
        require(!Files.isSymbolicLink(directory.toPath()))
        return readFile(File(directory, "session.json"), identity)
    }

    private fun readFile(file: File, identity: ProgressIdentity): ProgressWorkspaceRecord {
        check(!Files.isSymbolicLink(file.toPath()) && file.isFile && file.length() <= 1_048_576)
        val record = requireNotNull(adapter.fromJson(file.readText()))
        require(record.version == 1 && validId(record.sessionId) && record.identity == identity)
        require(record.phase in setOf("preparing", "prepared", "selected", "closed-clean"))
        record.checkpointId?.let { require(validId(it)) }
        ProgressProtocol.validateIdentity(record.identity)
        ProgressProtocol.validatePath(record.contentPath)
        require(record.contentPath.startsWith("content/") && record.firmware.size <= 128)
        require(record.coreSha256.matches(Regex("[a-f0-9]{64}")))
        record.firmware.forEach { (path, hash) -> ProgressProtocol.validatePath(path); require(hash.matches(Regex("[a-f0-9]{64}"))) }
        return record
    }

    private fun write(directory: File, record: ProgressWorkspaceRecord) {
        AtomicFiles.writeBytes(File(directory, "session.json"), adapter.toJson(record).toByteArray())
        sync(directory)
    }

    private suspend fun copy(source: File, target: File, hash: String, maxBytes: Long) {
        require(source.isFile && !Files.isSymbolicLink(source.toPath()) && source.length() in 1..maxBytes)
        directory(requireNotNull(target.parentFile))
        val size = source.length()
        FileOutputStream(target).use { output -> source.inputStream().use { input ->
            val buffer = ByteArray(65536); var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer); if (count < 0) break
                total += count; check(total <= size)
                output.write(buffer, 0, count)
            }
            check(total == size)
            output.fd.sync()
        } }
        check(CoreBinary.sha256(target) == hash) { "Runtime input changed during preparation." }
        sync(requireNotNull(target.parentFile))
    }

    private fun directory(directory: File) {
        var path = directory.absoluteFile.toPath().normalize().root
        for (part in directory.absoluteFile.toPath().normalize()) {
            path = path.resolve(part)
            require(!Files.isSymbolicLink(path)) { "Working-session paths must not be links." }
            if (!Files.exists(path)) { Files.createDirectory(path); sync(path.parent.toFile()) }
            require(Files.isDirectory(path))
        }
    }

    private fun remove(directory: File) {
        require(!Files.isSymbolicLink(directory.toPath()))
        Files.walkFileTree(directory.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                Files.delete(file); return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: java.nio.file.Path, error: java.io.IOException?): java.nio.file.FileVisitResult {
                if (error != null) throw error
                Files.delete(dir); return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }

    private fun validId(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    private fun sync(directory: File) {
        val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }
}
