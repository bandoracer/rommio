package io.github.bandoracer.rommio.data.migration

import androidx.room.withTransaction
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.github.bandoracer.rommio.data.auth.VerifiedAccount
import io.github.bandoracer.rommio.data.local.AppDatabase
import io.github.bandoracer.rommio.data.local.GameSyncJournalEntity
import io.github.bandoracer.rommio.data.local.RecoveryStateEntity
import io.github.bandoracer.rommio.domain.storage.AtomicFiles
import io.github.bandoracer.rommio.domain.storage.LibraryStore
import io.github.bandoracer.rommio.domain.storage.StorageAccess
import io.github.bandoracer.rommio.model.DownloadedRomEntity
import io.github.bandoracer.rommio.model.InstallIdentity
import io.github.bandoracer.rommio.model.RecoveryStateOrigin
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/** Discovery never assigns ownership. A confirmed copy freezes source bytes and the verified target. */
class LegacyProgressRecovery(
    private val database: AppDatabase,
    private val library: LibraryStore,
    private val journalDirectory: File,
    private val currentOwner: suspend () -> Pair<String, VerifiedAccount>?,
    private val checkpoint: (String) -> Unit = {},
) {
    data class Artifact(
        val path: String,
        val label: String,
        val kind: String,
        val size: Long,
        val sha256: String?,
        val provenance: List<String>,
        val issue: String? = null,
    )
    data class Inventory(val artifacts: List<Artifact>, val targets: List<DownloadedRomEntity>)
    data class CopyFile(val source: Artifact, val destination: String, val entryId: String?)
    data class CopyPlan(
        val id: String,
        val target: DownloadedRomEntity,
        val scope: String,
        val createdAt: Long,
        val files: List<CopyFile>,
    )
    val recoveryError = MutableStateFlow<String?>(null)
    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(CopyPlan::class.java)
    private val marker get() = File(journalDirectory, "pending.json")

    suspend fun inventory(): Inventory = StorageAccess.normal { withContext(Dispatchers.IO) {
        val root = library.rootDirectory().canonicalFile
        val references = linkedMapOf<String, MutableList<String>>()
        val labels = mutableMapOf<String, String>()
        fun reference(path: String, note: String) {
            val file = File(path).absoluteFile
            val relative = file.relativeToOrNull(root)?.invariantSeparatorsPath ?: return
            if (relative.startsWith("../") || relative.startsWith("progress-local/")) return
            references.getOrPut(relative) { mutableListOf() }.add(note)
        }
        database.saveStateDao().listAll().filter { it.progressScope == InstallIdentity.LEGACY }.forEach {
            reference(it.localPath, "Manual slot ${it.slot}: ${it.label} (ROM ${it.romId}; account unknown)")
            labels[it.localPath] = it.label
        }
        database.recoveryStateDao().listAll().filter { it.progressScope == InstallIdentity.LEGACY }.forEach {
            reference(it.localPath, "Recovery: ${it.label} (ROM ${it.romId}, file ${it.fileId}; account unknown)")
            labels.putIfAbsent(it.localPath, it.label)
        }
        database.saveStateSyncJournalDao().listAll().filter { it.progressScope == InstallIdentity.LEGACY }.forEach {
            it.localPath?.let { path -> reference(path, "Journal profile ${it.profileId}, ROM ${it.romId}, file ${it.fileId}, slot ${it.slot}" +
                if (it.deleted) " — previously deleted; retained bytes" else " — ownership not verified") }
        }
        for (directory in listOf("saves", "states")) {
            val folder = File(root, directory)
            if (folder.exists()) Files.walk(folder.toPath()).use { paths ->
                paths.filter { !Files.isDirectory(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }.forEach {
                    references.getOrPut(root.toPath().relativize(it).toString()) { mutableListOf("Unassigned legacy file") }
                }
            }
        }
        val artifacts = references.map { (path, notes) ->
            val file = File(root, path)
            val kind = when (file.name.removeSuffix(".before-resume-fix").substringAfterLast('.').lowercase()) {
                "srm" -> "SRAM"; "state" -> "STATE"; else -> "OTHER"
            }
            val issue = when {
                !contained(file) -> "Linked or external files cannot be imported."
                !file.isFile -> "The recorded file is missing."
                kind == "OTHER" -> "Preserved for recovery; this file format needs a persistence descriptor."
                file.length() == 0L -> "Empty legacy data needs manual inspection."
                else -> null
            }
            Artifact(path, labels[file.absolutePath] ?: file.name, kind, if (file.isFile) file.length() else 0,
                if (issue == null) digest(file) else null, notes.distinct(), issue)
        }.sortedBy { it.path }
        val owner = currentOwner()
        val targets = database.downloadedRomDao().listAll().filter {
            owner != null && it.ownerProfileId == owner.first && it.ownerBaseUrl == owner.second.baseUrl &&
                it.ownerAccountId == owner.second.userId && it.contentSha256 != null &&
                it.namespaceKey == InstallIdentity.namespace(owner.second.baseUrl, owner.second.userId)
        }
        Inventory(artifacts, targets)
    } }

    /** selected is the inspected snapshot, so changed bytes cannot be silently confirmed. */
    suspend fun copy(selected: List<Artifact>, target: DownloadedRomEntity): Int = StorageAccess.exclusive { withContext(Dispatchers.IO) {
        StorageAccess.checkRecovered()
        check(!marker.exists()) { "Finish the previous recovery first." }
        require(selected.isNotEmpty() && selected.distinctBy { it.path }.size == selected.size) { "Select the files to recover." }
        require(selected.count { it.kind == "SRAM" } <= 1) { "Choose one in-game save; other copies remain preserved." }
        val owner = requireNotNull(currentOwner()) { "Select a verified account before recovering progress." }
        check(target.ownerProfileId == owner.first && target.ownerBaseUrl == owner.second.baseUrl && target.ownerAccountId == owner.second.userId &&
            target.namespaceKey == InstallIdentity.namespace(owner.second.baseUrl, owner.second.userId)) { "The selected game belongs to another account." }
        check(database.downloadedRomDao().getByIds(target.romId, target.fileId, target.namespaceKey) == target) { "The selected install changed. Review the selection again." }
        check(targetIssue(target) == null) { requireNotNull(targetIssue(target)) }
        verifyContent(target)
        val scope = InstallIdentity.progress(target.ownerBaseUrl, target.ownerAccountId, target.romId, target.fileId, target.contentSha256)
        check(scope != InstallIdentity.LEGACY && target.progressScope in listOf(InstallIdentity.LEGACY, scope)) { "The destination content identity is invalid." }
        val scoped = target.copy(progressScope = scope)
        if (selected.any { it.kind == "SRAM" }) {
            check(!library.saveRamFile(scoped).exists() && !library.continuityResumeStateFile(scoped).exists()) {
                "This game already has current progress. Recover states separately; its in-game save has not been replaced."
            }
        }
        val fresh = inventory().artifacts.associateBy { it.path }
        selected.forEach { check(it.issue == null && it.sha256 != null && fresh[it.path] == it) { "A selected source changed or cannot be recovered. Scan again." } }
        val id = UUID.randomUUID().toString()
        val staged = File(journalDirectory, id)
        val plan = CopyPlan(id, target, scope, System.currentTimeMillis(), selected.mapIndexed { index, source ->
            val entry = if (source.kind == "STATE") "legacy_${id}_$index" else null
            val destination = if (entry == null) library.saveRamFile(scoped) else File(library.saveStatesDirectory(scoped), "$entry.state")
            CopyFile(source, destination.relativeTo(library.rootDirectory()).invariantSeparatorsPath, entry)
        })
        try {
            plan.files.forEachIndexed { index, file ->
                AtomicFiles.write(File(staged, "$index.blob")) { output -> safe(file.source.path).inputStream().use { it.copyTo(output) } }
                check(digest(File(staged, "$index.blob")) == file.source.sha256) { "Source changed while staging recovery." }
            }
            AtomicFiles.writeBytes(marker, adapter.toJson(plan).toByteArray())
            StorageAccess.requireRecovery(true, RECOVERY_KEY)
            checkpoint("PREPARED")
            recoverLocked()
            plan.files.size
        } catch (error: Throwable) {
            if (marker.exists()) {
                StorageAccess.requireRecovery(true, RECOVERY_KEY)
                recoveryError.value = "Progress recovery was interrupted. Retry to finish the selected copy. " + error.message.orEmpty()
            } else staged.deleteRecursively()
            throw error
        }
    } }

    suspend fun recover() = StorageAccess.exclusive { withContext(Dispatchers.IO) { recoverLocked() } }

    private suspend fun recoverLocked() {
        try {
            if (!marker.exists()) { StorageAccess.requireRecovery(false, RECOVERY_KEY); recoveryError.value = null; return }
            StorageAccess.requireRecovery(true, RECOVERY_KEY)
            val plan = requireNotNull(adapter.fromJson(marker.readText()))
            check(UUID.fromString(plan.id).toString() == plan.id && plan.files.isNotEmpty()) { "Invalid recovery journal; files have been retained." }
            check(plan.scope == InstallIdentity.progress(plan.target.ownerBaseUrl, plan.target.ownerAccountId, plan.target.romId, plan.target.fileId, plan.target.contentSha256) && plan.scope != InstallIdentity.LEGACY)
            val updated = plan.target.copy(progressScope = plan.scope)
            val current = database.downloadedRomDao().getByIds(updated.romId, updated.fileId, updated.namespaceKey)
            check(current == plan.target || current == updated) { "The destination install changed during recovery; all copies remain retained." }
            verifyContent(updated)
            val receipt = safe("recovery-imports/${plan.id}.json")
            // A receipt is durable before SQLite publication. It also preserves source provenance in bundle exports.
            plan.files.forEachIndexed { index, file ->
                check(file.destination.startsWith("progress-local/${plan.scope}/"))
                val staged = File(journalDirectory, "${plan.id}/$index.blob")
                check(staged.isFile && digest(staged) == file.source.sha256 && staged.length() == file.source.size) { "Staged recovery bytes are missing or changed." }
                val target = safe(file.destination)
                if (target.exists()) check(target.isFile && digest(target) == file.source.sha256) { "Existing progress differs; no file has been overwritten." }
                else AtomicFiles.write(target) { output -> staged.inputStream().use { it.copyTo(output) } }
                checkpoint("FILE_$index")
            }
            AtomicFiles.writeBytes(receipt, adapter.toJson(plan).toByteArray())
            checkpoint("FILES_INSTALLED")
            database.withTransaction {
                val row = database.downloadedRomDao().getByIds(updated.romId, updated.fileId, updated.namespaceKey)
                check(row == plan.target || row == updated) { "Destination changed during recovery." }
                plan.files.filter { it.entryId != null }.forEach { file ->
                    database.recoveryStateDao().upsert(RecoveryStateEntity(
                        romId = updated.romId, fileId = updated.fileId, entryId = requireNotNull(file.entryId),
                        label = "Recovered: ${file.source.label}", origin = RecoveryStateOrigin.LEGACY_IMPORT.name,
                        localPath = safe(file.destination).absolutePath, remoteFileName = "", localHash = file.source.sha256,
                        preserved = true, capturedAtEpochMs = plan.createdAt, progressScope = plan.scope,
                    ))
                }
                if (plan.files.any { it.source.kind == "SRAM" }) {
                    val profile = requireNotNull(updated.ownerProfileId)
                    val old = database.gameSyncJournalDao().getByKey(profile, updated.romId, updated.fileId, plan.scope)
                    database.gameSyncJournalDao().upsert((old ?: GameSyncJournalEntity(profile, updated.romId, updated.fileId, progressScope = plan.scope))
                        .copy(pendingContinuityUpload = true, lastSyncNote = "Recovered locally. Cloud synchronization is unavailable."))
                }
                database.downloadedRomDao().upsert(updated)
            }
            checkpoint("DATABASE_APPLIED")
            check(marker.delete() || !marker.exists()) { "Unable to finalize progress recovery." }
            StorageAccess.didReplaceLibrary()
            StorageAccess.requireRecovery(false, RECOVERY_KEY)
            recoveryError.value = null
            File(journalDirectory, plan.id).deleteRecursively()
        } catch (error: Throwable) {
            StorageAccess.requireRecovery(marker.exists(), RECOVERY_KEY)
            recoveryError.value = error.message ?: "Progress recovery failed. Originals and staged copies are retained."
            throw error
        }
    }

    private fun verifyContent(target: DownloadedRomEntity) {
        val content = File(target.localPath)
        check(contained(content) && content.isFile && content.length() == target.fileSizeBytes && digest(content) == target.contentSha256) {
            "The installed game bytes no longer match the verified download. Download the game again before mapping progress."
        }
    }
    fun targetIssue(target: DownloadedRomEntity): String? =
        if (File(target.fileName).extension.lowercase() in setOf("zip", "7z", "rar", "gz", "tar", "m3u", "cue", "ccd", "mds", "gdi"))
            "Archive and disc-set recovery needs verified playable-content identity. The original saves remain available."
        else null
    private fun contained(file: File): Boolean {
        val root = library.rootDirectory().canonicalFile
        val absolute = file.absoluteFile
        return absolute.toPath().normalize() == absolute.toPath() && absolute.canonicalFile == absolute && absolute.path.startsWith(root.path + File.separator)
    }
    private fun safe(relative: String): File = File(library.rootDirectory(), relative).also { check(contained(it)) { "Invalid recovery path." } }
    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream -> val buffer = ByteArray(65536); while (true) { val count = stream.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) } }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    companion object { private const val RECOVERY_KEY = "legacy-progress" }
}
