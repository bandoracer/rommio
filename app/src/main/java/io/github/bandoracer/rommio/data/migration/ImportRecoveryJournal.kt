package io.github.bandoracer.rommio.data.migration

import io.github.bandoracer.rommio.domain.storage.AtomicFiles
import java.io.File
import java.util.UUID
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Durable forward recovery for an import across files, SQLite, protected secrets, and preferences.
 * The caller must hold exclusive application storage ownership for prepare/activate/recover.
 * Every participant must be idempotent: a process can stop after its commit but before our marker.
 */
internal class ImportRecoveryJournal(
    private val directory: File,
    private val library: File,
    private val participants: Participants,
    private val progress: File? = null,
    private val syncDirectory: (File) -> Unit = { directory ->
        val fd = android.system.Os.open(directory.absolutePath, android.system.OsConstants.O_RDONLY, 0)
        try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
    },
    private val fileCheckpoint: (String) -> Unit = {},
    private val checkpoint: (Phase) -> Unit = {},
) {
    enum class Phase { PREPARED, FILES_INSTALLED, PROGRESS_INSTALLED, DATABASE_APPLIED, SECRETS_APPLIED, PREFERENCES_APPLIED, COMMITTED }
    interface Participants {
        suspend fun applyDatabase(payload: File)
        suspend fun applySecrets(importId: String)
        suspend fun applyPreferences(payload: File)
        suspend fun releaseSecrets(importId: String)
    }
    // null means a pre-v2 journal: recovery must leave the progress directory untouched.
    data class Pending(val id: String, val phase: Phase, val hadLibrary: Boolean, val hadProgress: Boolean? = null)
    private val mutex = Mutex()
    private val marker get() = File(directory, "pending")

    /** Staging is already validated, complete, durable, and on the same filesystem. */
    suspend fun prepare(id: String, payload: File) = mutex.withLock {
        require(UUID.fromString(id).toString() == id) { "Invalid import ID." }
        check(!marker.exists()) { "Finish the interrupted import before starting another." }
        check(payload.canonicalFile == File(directory, id).canonicalFile) { "Invalid import staging path." }
        requireDirectory(directory)
        requireDirectory(payload)
        requireDirectory(File(payload, "library"))
        check(File(payload, "database.json").isFile && File(payload, "controls.json").isFile) { "Import metadata is missing." }
        check(!exists(File(payload, "previous-library"))) { "Import backup already exists." }
        if (exists(library)) requireDirectory(library)
        progress?.let {
            // Both roots must be separate from each other and from the journal payload.
            val roots = listOf(directory.canonicalFile.toPath(), library.canonicalFile.toPath(), it.canonicalFile.toPath())
            check(roots.indices.all { a -> roots.indices.all { b -> a == b || !roots[a].startsWith(roots[b]) } }) {
                "Import roots must not overlap."
            }
            requireDirectory(File(payload, "progress-v2"))
            check(!exists(File(payload, "previous-progress-v2"))) { "Progress backup already exists." }
            if (exists(it)) requireDirectory(it)
            writeGeneration(File(payload, "progress-v2"), id)
        }
        writeGeneration(File(payload, "library"), id)
        syncDirectory(payload)
        syncDirectory(directory)
        // Persist directory creation before the marker becomes authority to finish forward.
        syncDirectory(requireNotNull(directory.parentFile))
        write(Pending(id, Phase.PREPARED, exists(library), progress?.let(::exists)))
    }

    suspend fun recover(): Pending? = mutex.withLock {
        var pending = read() ?: return@withLock null
        val payload = File(directory, pending.id)
        requireDirectory(directory)
        requireDirectory(payload)
        if (pending.hadProgress != null) check(progress != null) { "This import requires progress storage recovery." }
        if (pending.phase == Phase.PREPARED) {
            installTree(pending.id, payload, "library", library, pending.hadLibrary)
            pending = advance(pending, Phase.FILES_INSTALLED)
        }
        if (pending.phase == Phase.FILES_INSTALLED) {
            pending.hadProgress?.let { hadProgress ->
                installTree(pending.id, payload, "progress-v2", requireNotNull(progress), hadProgress)
            }
            pending = advance(pending, Phase.PROGRESS_INSTALLED)
        }
        if (pending.phase == Phase.PROGRESS_INSTALLED) {
            participants.applyDatabase(payload)
            pending = advance(pending, Phase.DATABASE_APPLIED)
        }
        if (pending.phase == Phase.DATABASE_APPLIED) {
            participants.applySecrets(pending.id)
            pending = advance(pending, Phase.SECRETS_APPLIED)
        }
        if (pending.phase == Phase.SECRETS_APPLIED) {
            participants.applyPreferences(payload)
            pending = advance(pending, Phase.PREFERENCES_APPLIED)
        }
        if (pending.phase == Phase.PREFERENCES_APPLIED) pending = advance(pending, Phase.COMMITTED)
        // All authoritative stores now agree. Cleanup is repeatable and must never roll files back.
        participants.releaseSecrets(pending.id)
        check(marker.delete() || !marker.exists()) { "Unable to finalize the completed import." }
        syncDirectory(directory)
        // Retain metadata and the prior library for explicit recovery; retention is a separate policy.
        pending
    }

    fun pending(): Pending? = read()

    private fun installTree(id: String, payload: File, name: String, destination: File, hadPrevious: Boolean) {
        val staged = File(payload, name)
        val backup = File(payload, "previous-$name")
        requireDirectory(requireNotNull(destination.parentFile))
        if (exists(backup)) requireDirectory(backup)
        if (!exists(staged)) {
            requireDirectory(destination)
            check(readGeneration(destination) == id) {
                "Cannot identify the installed import; preserving all available files."
            }
            if (hadPrevious) check(exists(backup)) { "Import backup is missing; preserving the installed files." }
            syncDirectory(payload)
            syncDirectory(requireNotNull(destination.parentFile))
            return // The rename succeeded before the phase marker was committed.
        }
        requireDirectory(staged)
        check(readGeneration(staged) == id) { "Import payload identity changed." }
        if (hadPrevious && !exists(backup)) {
            requireDirectory(destination)
            check(destination.renameTo(backup)) { "Unable to retain previous $name." }
            syncDirectory(requireNotNull(destination.parentFile)); syncDirectory(payload)
            fileCheckpoint("$name:BACKED_UP")
        }
        check(!exists(destination)) { "The $name directory changed during import; existing data has been preserved." }
        check(staged.renameTo(destination)) { "Unable to activate staged $name. The prior files are retained." }
        syncDirectory(payload); syncDirectory(requireNotNull(destination.parentFile))
        fileCheckpoint("$name:INSTALLED")
    }

    private fun exists(file: File) = Files.exists(file.toPath(), NOFOLLOW_LINKS)
    private fun requireDirectory(file: File) {
        check(!Files.isSymbolicLink(file.toPath()) && Files.isDirectory(file.toPath(), NOFOLLOW_LINKS)) {
            "Import directory is missing or unsafe; existing data has been preserved."
        }
    }
    private fun readGeneration(root: File): String? {
        val file = File(root, GENERATION)
        check(!Files.isSymbolicLink(file.toPath())) { "Import generation must not be a symbolic link." }
        return file.takeIf { it.isFile && it.length() <= 36 }?.readText()
    }
    private fun writeGeneration(root: File, id: String) {
        check(!exists(File(root, GENERATION))) { "Import payload contains reserved generation metadata." }
        val directories = mutableListOf<File>()
        root.walkTopDown().forEach { file ->
            check(!Files.isSymbolicLink(file.toPath())) { "Import payload must not contain symbolic links." }
            check(file.isFile || file.isDirectory) { "Import payload contains an unsupported file." }
            if (file.isDirectory) directories.add(file)
        }
        AtomicFiles.writeBytes(File(root, GENERATION), id.toByteArray())
        directories.asReversed().forEach(syncDirectory)
    }

    private fun advance(pending: Pending, phase: Phase): Pending = pending.copy(phase = phase).also(::write)
    private fun write(pending: Pending) {
        val progressLine = pending.hadProgress?.let { "$it\n" }.orEmpty()
        AtomicFiles.writeBytes(marker, "${pending.id}\n${pending.phase.name}\n${pending.hadLibrary}\n$progressLine".toByteArray())
        syncDirectory(directory)
        checkpoint(pending.phase)
    }
    private fun read(): Pending? {
        if (!exists(marker)) return null
        check(!Files.isSymbolicLink(marker.toPath()) && marker.isFile && marker.length() <= 128) { "Invalid import recovery marker." }
        val lines = marker.readLines()
        check(lines.size in 3..4 && lines.drop(2).all { it in listOf("true", "false") }) { "Import recovery journal is invalid; no data has been discarded." }
        val id = UUID.fromString(lines[0]).toString()
        check(id == lines[0]) { "Invalid import recovery identity." }
        return Pending(id, Phase.valueOf(lines[1]), lines[2].toBoolean(), lines.getOrNull(3)?.toBoolean())
    }
    companion object { const val GENERATION = ".rommio-import-generation" }
}
