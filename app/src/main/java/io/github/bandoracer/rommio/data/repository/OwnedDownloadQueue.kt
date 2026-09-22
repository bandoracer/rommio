package io.github.bandoracer.rommio.data.repository

import io.github.bandoracer.rommio.domain.storage.StorageAccess

import androidx.room.withTransaction
import io.github.bandoracer.rommio.data.auth.AccountBindingException
import io.github.bandoracer.rommio.data.auth.AuthManager
import io.github.bandoracer.rommio.data.auth.TokenRefreshException
import io.github.bandoracer.rommio.data.auth.VerifiedAccount
import io.github.bandoracer.rommio.data.local.AppDatabase
import io.github.bandoracer.rommio.data.network.DownloadClient
import io.github.bandoracer.rommio.data.network.DownloadHttpException
import io.github.bandoracer.rommio.domain.storage.LibraryStore
import io.github.bandoracer.rommio.model.*
import io.github.bandoracer.rommio.util.buildRomContentPath
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class DownloadOwnershipException(message: String) : IOException(message)
class DownloadAuthenticationRequired : IOException("Sign in to the original server account to continue this download.")

typealias DownloadTransfer = suspend (DownloadRecordEntity, String, File, suspend (Long, Long) -> Unit) -> Unit

/** Owns durable transitions. WorkManager and screens never reconstruct a record from stale UI fields. */
class OwnedDownloadQueue(
    private val database: AppDatabase,
    private val auth: AuthManager,
    private val library: LibraryStore,
    client: DownloadClient,
    private val transfer: DownloadTransfer = { record, url, target, progress ->
        client.downloadToFile(requireNotNull(record.ownerProfileId), url, target,
            expectedSize = record.fileSizeBytes.takeIf { it > 0 }, onProgress = progress)
    },
) {
    private val records = database.downloadRecordDao()
    private val installs = database.downloadedRomDao()

    suspend fun enqueue(romId: Int, fileId: Int, romName: String, platformSlug: String, fileName: String,
                        fileSizeBytes: Long, replace: Boolean, prioritize: Boolean) = StorageAccess.normal {
        require(fileSizeBytes >= 0) { "Invalid download size." }
        val profile = auth.getActiveProfile() ?: throw DownloadAuthenticationRequired()
        if (auth.verifiedAccount(profile.id) == null) auth.validateProfile(profile.id)
        val owner = auth.verifiedAccount(profile.id) ?: throw DownloadAuthenticationRequired()
        if (owner.baseUrl != profile.baseUrl) throw AccountBindingException()
        val namespace = InstallIdentity.namespace(owner.baseUrl, owner.userId)
        val previous = records.getByIds(romId, fileId, namespace)
        val installed = installs.getByIds(romId, fileId, namespace)
        if (previous != null && previous.ownerProfileId == null || installed != null && installed.ownerProfileId == null) {
            throw DownloadOwnershipException("This older install or download has no verified account owner. Its data is preserved for migration.")
        }
        if (previous != null && !previous.ownedBy(profile.id, owner) || installed != null && !installed.ownedBy(profile.id, owner)) {
            throw DownloadOwnershipException("The saved account binding does not match this download.")
        }
        if (!replace && previous?.status == DownloadStatus.RUNNING.name) return@normal
        if (!replace && !prioritize && previous?.status == DownloadStatus.QUEUED.name) return@normal
        val now = System.currentTimeMillis()
        val enqueued = if (prioritize) nextPriorityEnqueuedAt(records.listAll().filterNot { it.romId == romId && it.fileId == fileId && it.namespaceKey == namespace }, now) else now
        var next = DownloadRecordEntity(romId, fileId, romName, platformSlug, fileName, fileSizeBytes,
            status = DownloadStatus.QUEUED.name, localPath = installed?.localPath ?: previous?.localPath,
            enqueuedAtEpochMs = enqueued, updatedAtEpochMs = now,
            ownerProfileId = profile.id, ownerBaseUrl = owner.baseUrl, ownerAccountId = owner.userId,
            contentSha256 = installed?.contentSha256 ?: previous?.contentSha256)
        requireOwner(next)
        next = newAttempt(next)
        commit(next, previous)
    }

    suspend fun retry(model: DownloadRecord, prioritize: Boolean = false) = StorageAccess.normal {
        val previous = records.getByIds(model.romId, model.fileId, model.namespaceKey) ?: return@normal
        if (previous.attemptId != model.attemptId || previous.ownerProfileId != model.ownerProfileId) return@normal
        requireOwner(previous)
        var next = newAttempt(previous)
        if (prioritize) next = next.copy(enqueuedAtEpochMs = nextPriorityEnqueuedAt(records.listAll().filterNot { it == previous }, System.currentTimeMillis()))
        commit(next, previous)
    }

    suspend fun cancel(model: DownloadRecord) = StorageAccess.normal {
        val previous = records.getByIds(model.romId, model.fileId, model.namespaceKey) ?: return@normal
        if (previous.attemptId != model.attemptId || previous.ownerProfileId != model.ownerProfileId) return@normal
        commit(previous.copy(attemptId = UUID.randomUUID().toString(), workId = null, status = DownloadStatus.CANCELED.name,
            retryAfterEpochMs = null, parkedForAuthentication = null, lastError = null, updatedAtEpochMs = System.currentTimeMillis()), previous)
    }

    suspend fun removeInstall(romId: Int, fileId: Int, expectedPath: String?, namespaceKey: String = InstallIdentity.LEGACY) = StorageAccess.normal {
        expectedPath?.let { managed(File(it)) }
        val detached = database.withTransaction {
            val installed = installs.getByIds(romId, fileId, namespaceKey)
            val previous = records.getByIds(romId, fileId, namespaceKey)
            if (installed != null && installed.localPath != expectedPath) return@withTransaction null
            if (installed == null && (previous == null || previous.localPath != expectedPath)) return@withTransaction null
            installed?.let { installs.delete(romId, fileId, namespaceKey) }
            previous?.let { records.upsert(it.copy(localPath = null, contentSha256 = null, attemptId = UUID.randomUUID().toString(),
                workId = null, status = DownloadStatus.CANCELED.name, retryAfterEpochMs = null, parkedForAuthentication = null,
                updatedAtEpochMs = System.currentTimeMillis())) }
            expectedPath
        }
        detached?.let { path ->
            val file = managed(File(path))
            if (installs.listAll().none { it.localPath == path } && records.listAll().none { it.localPath == path }) file.delete()
        }
    }

    suspend fun recover() = StorageAccess.normal {
        for (previous in records.listAll()) {
            val abandoned = previous.status == DownloadStatus.RUNNING.name
            val parked = previous.status == DownloadStatus.FAILED.name && previous.parkedForAuthentication == true
            val unownedQueued = previous.status == DownloadStatus.QUEUED.name && previous.ownerProfileId == null
            if (!abandoned && !parked && !unownedQueued) continue
            try {
                requireOwner(previous)
                commit(newAttempt(previous), previous)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                commit(previous.copy(status = DownloadStatus.FAILED.name, workId = null, lastError = error.message,
                    parkedForAuthentication = authFailure(error), updatedAtEpochMs = System.currentTimeMillis()), previous)
            }
        }
    }

    suspend fun next(): DownloadRecordEntity? = StorageAccess.normal { nextQueuedDownload(records.listAll().filter {
        it.retryAfterEpochMs == null || it.retryAfterEpochMs <= System.currentTimeMillis()
    }) }
    suspend fun hasPending(): Boolean = StorageAccess.normal { records.listAll().any { it.status == DownloadStatus.QUEUED.name } }

    suspend fun execute(record: DownloadRecordEntity, workerId: String, stopped: () -> Boolean = { false },
                        onProgress: suspend (DownloadRecordEntity) -> Unit = {}) = StorageAccess.normal {
        var target: File? = null
        var claimed: DownloadRecordEntity? = null
        try {
            requireOwner(record)
            coroutineContext.ensureActive()
            if (stopped()) throw CancellationException("Download stopped")
            val attempt = record.attemptId ?: throw DownloadOwnershipException("Download has no attempt identity.")
            UUID.fromString(attempt)
            validateFileName(record.fileName)
            require(record.destinationRelativePath == "roms/installs/$attempt/${record.fileName}") { "Invalid download destination." }
            val file = managed(File(library.rootDirectory(), requireNotNull(record.destinationRelativePath)))
            val running = record.copy(status = DownloadStatus.RUNNING.name, workId = workerId, startedAtEpochMs = System.currentTimeMillis(), updatedAtEpochMs = System.currentTimeMillis())
            if (!commit(running, record)) return@normal
            claimed = running
            target = file
            val url = requireNotNull(record.ownerBaseUrl).removeSuffix("/") + buildRomContentPath(record.romId, record.fileName)
            transfer(running, url, file) { bytes, total ->
                coroutineContext.ensureActive()
                if (stopped()) throw CancellationException("Download stopped")
                val current = current(running) ?: throw CancellationException("Download attempt superseded")
                val progress = current.copy(bytesDownloaded = bytes, totalBytes = total.coerceAtLeast(record.fileSizeBytes),
                    progressPercent = if (total > 0) (bytes.toDouble() / total * 100).toInt().coerceIn(0, 99) else 0,
                    updatedAtEpochMs = System.currentTimeMillis())
                if (!commit(progress, current)) throw CancellationException("Download attempt superseded")
                onProgress(progress)
            }
            coroutineContext.ensureActive()
            if (stopped()) throw CancellationException("Download stopped")
            requireOwner(running)
            val current = current(running) ?: return@normal
            managed(file)
            check(file.isFile && (record.fileSizeBytes <= 0 || file.length() == record.fileSizeBytes)) { "Downloaded size does not match the library file." }
            val digest = sha256(file)
            coroutineContext.ensureActive()
            val now = System.currentTimeMillis()
            val completed = current.copy(status = DownloadStatus.COMPLETED.name, workId = null, localPath = file.absolutePath,
                contentSha256 = digest, progressPercent = 100, bytesDownloaded = file.length(), totalBytes = file.length(),
                completedAtEpochMs = now, updatedAtEpochMs = now, lastError = null, parkedForAuthentication = null)
            database.withTransaction {
                if (records.getByIds(record.romId, record.fileId, record.namespaceKey) != current) return@withTransaction
                val priorInstall = installs.getByIds(record.romId, record.fileId, record.namespaceKey)
                val progressScope = priorInstall?.takeIf { it.contentSha256 == digest }?.progressScope
                    ?: InstallIdentity.progress(record.ownerBaseUrl, record.ownerAccountId, record.romId, record.fileId, digest)
                installs.upsert(DownloadedRomEntity(record.romId, record.fileId, record.platformSlug, record.romName, record.fileName,
                    file.absolutePath, file.length(), now, record.ownerProfileId, record.ownerBaseUrl, record.ownerAccountId, digest, record.namespaceKey, progressScope))
                records.upsert(completed)
            }
        } catch (error: Exception) {
            withContext(NonCancellable) {
                val current = records.getByIds(record.romId, record.fileId, record.namespaceKey)
                if (current != null && (claimed?.let { current.attemptId == it.attemptId && current.workId == it.workId && current.status == DownloadStatus.RUNNING.name } ?: (current == record && current.status == DownloadStatus.QUEUED.name))) {
                    val canceled = error is CancellationException || stopped()
                    val transient = !canceled && !authFailure(error) && error is IOException && error !is DownloadOwnershipException && error !is AccountBindingException && (error !is DownloadHttpException || error.status == 408 || error.status == 429 || error.status in 500..599)
                    val retryBase = if (canceled || transient) newAttempt(current) else current
                    commit(retryBase.copy(status = when {
                        canceled -> DownloadStatus.QUEUED.name // Explicit user cancellation already invalidated the attempt.
                        transient -> DownloadStatus.QUEUED.name
                        else -> DownloadStatus.FAILED.name
                    }, workId = null, lastError = if (canceled) null else error.message,
                        retryAfterEpochMs = if (transient || canceled) System.currentTimeMillis() + 30_000 else null,
                        parkedForAuthentication = authFailure(error), updatedAtEpochMs = System.currentTimeMillis()), current)
                }
            }
            if (error is CancellationException) throw error
        } finally {
            withContext(NonCancellable) {
                target?.let { file ->
                    if (installs.listAll().none { it.localPath == file.absolutePath } && records.listAll().none { it.localPath == file.absolutePath }) {
                        runCatching { managed(file).delete() }
                    }
                }
            }
        }
    }

    suspend fun verifyInstall(install: DownloadedRomEntity) = StorageAccess.normal { withContext(Dispatchers.IO) {
        managed(File(install.localPath))
        if (install.ownerProfileId != null) {
            val active = auth.getActiveProfile() ?: throw DownloadAuthenticationRequired()
            val owner = auth.verifiedAccount(active.id) ?: throw DownloadAuthenticationRequired()
            if (install.namespaceKey != InstallIdentity.namespace(owner.baseUrl, owner.userId) || active.baseUrl != owner.baseUrl || !install.ownedBy(active.id, owner)) throw AccountBindingException()
            if (install.progressScope != InstallIdentity.LEGACY) check(install.progressScope == InstallIdentity.progress(install.ownerBaseUrl, install.ownerAccountId, install.romId, install.fileId, install.contentSha256)) { "Installed progress identity changed." }
            check(install.contentSha256 != null && sha256(File(install.localPath)) == install.contentSha256) { "The installed file changed. Download it again before playing." }
        }
    } }

    private suspend fun current(running: DownloadRecordEntity): DownloadRecordEntity? = records.getByIds(running.romId, running.fileId, running.namespaceKey)
        ?.takeIf { it.attemptId == running.attemptId && it.status == DownloadStatus.RUNNING.name && it.workId == running.workId }
    private suspend fun commit(next: DownloadRecordEntity, expected: DownloadRecordEntity?): Boolean = database.withTransaction {
        if (records.getByIds(next.romId, next.fileId, next.namespaceKey) != expected) return@withTransaction false
        records.upsert(next); true
    }
    private suspend fun requireOwner(record: DownloadRecordEntity) {
        val profileId = record.ownerProfileId ?: throw DownloadOwnershipException("This older download has no verified account owner. Its record is preserved for migration.")
        val profile = auth.getProfile(profileId) ?: throw DownloadAuthenticationRequired()
        val owner = auth.verifiedAccount(profileId) ?: throw DownloadAuthenticationRequired()
        if (record.namespaceKey != InstallIdentity.namespace(owner.baseUrl, owner.userId) || profile.baseUrl != owner.baseUrl || !record.ownedBy(profileId, owner)) throw AccountBindingException()
        if (profile.status != AuthStatus.CONNECTED || !profile.sessionState.hasOriginSession) throw DownloadAuthenticationRequired()
    }
    private fun newAttempt(record: DownloadRecordEntity): DownloadRecordEntity {
        validateFileName(record.fileName)
        val id = UUID.randomUUID().toString()
        return record.copy(attemptId = id, destinationRelativePath = "roms/installs/$id/${record.fileName}", workId = null,
            status = DownloadStatus.QUEUED.name, progressPercent = 0, bytesDownloaded = 0, totalBytes = record.fileSizeBytes,
            retryAfterEpochMs = null, parkedForAuthentication = null, lastError = null, startedAtEpochMs = null,
            completedAtEpochMs = null, updatedAtEpochMs = System.currentTimeMillis())
    }
    private fun validateFileName(name: String) {
        require(name.isNotBlank() && name !in listOf(".", "..") && name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }) { "Invalid download filename." }
    }
    private fun managed(file: File): File {
        require(file.canonicalPath.startsWith(library.rootDirectory().canonicalPath + File.separator)) { "Download path escapes the library." }
        return file
    }
    private fun DownloadRecordEntity.ownedBy(profile: String, account: VerifiedAccount) = ownerProfileId == profile && ownerBaseUrl == account.baseUrl && ownerAccountId == account.userId
    private fun DownloadedRomEntity.ownedBy(profile: String, account: VerifiedAccount) = ownerProfileId == profile && ownerBaseUrl == account.baseUrl && ownerAccountId == account.userId
    private fun authFailure(error: Exception) = error is DownloadAuthenticationRequired ||
        error is DownloadHttpException && error.status in listOf(401, 403) ||
        error is TokenRefreshException && error.reason in listOf(TokenRefreshException.Reason.INVALID_CREDENTIALS, TokenRefreshException.Reason.ACCESS_REQUIRED)
    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(1024 * 1024); while (true) {
                val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size)
            } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
