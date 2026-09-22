package io.github.bandoracer.rommio.domain.sync

import io.github.bandoracer.rommio.data.local.GameSyncJournalDao
import io.github.bandoracer.rommio.data.local.GameSyncJournalEntity
import io.github.bandoracer.rommio.data.local.RecoveryStateDao
import io.github.bandoracer.rommio.data.local.RecoveryStateEntity
import io.github.bandoracer.rommio.data.local.SaveStateDao
import io.github.bandoracer.rommio.data.local.SaveStateSyncJournalDao
import io.github.bandoracer.rommio.data.local.SaveStateSyncJournalEntity
import io.github.bandoracer.rommio.domain.player.RuntimeProfile
import io.github.bandoracer.rommio.domain.storage.AtomicFiles
import io.github.bandoracer.rommio.domain.storage.LibraryStore
import io.github.bandoracer.rommio.model.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val LEGACY_SYNC_PAUSED_MESSAGE = "Cloud sync is paused while save safety is upgraded. Local progress is retained."

class LegacySyncUnavailableException : IOException(LEGACY_SYNC_PAUSED_MESSAGE)

fun legacyLocalPresentation(lastSuccessfulSyncAtEpochMs: Long? = null) = GameSyncPresentation(
    kind = GameSyncStatusKind.LOCAL_ONLY,
    message = LEGACY_SYNC_PAUSED_MESSAGE,
    lastSuccessfulSyncAtEpochMs = lastSuccessfulSyncAtEpochMs,
)

/** Local preservation of the legacy layout. It deliberately has no remote transport. */
class RommSyncBridge(
    private val profileIdProvider: suspend () -> String?,
    private val libraryStore: LibraryStore,
    private val saveStateDao: SaveStateDao,
    private val gameSyncJournalDao: GameSyncJournalDao,
    private val saveStateSyncJournalDao: SaveStateSyncJournalDao,
    private val recoveryStateDao: RecoveryStateDao,
    private val deviceName: String,
) : SyncBridge {
    override suspend fun preparePlayerEntry(installation: DownloadedRomEntity, rom: RomDto, runtimeProfile: RuntimeProfile): PlayerLaunchPreparation =
        withContext(Dispatchers.IO) {
            ownerProfile(installation)?.let { importLegacyManualStates(it, installation) }
            val resume = libraryStore.continuityResumeStateFile(installation)
            PlayerLaunchPreparation(
                launchTarget = resume.takeIf { it.isFile }?.let {
                    PlayerLaunchTarget(kind = PlayerLaunchTargetKind.CONTINUITY, localStatePath = it.absolutePath, stateId = "resume", label = "Resume")
                },
                syncPresentation = legacyLocalPresentation(),
            )
        }

    override suspend fun flushContinuity(installation: DownloadedRomEntity, rom: RomDto, runtimeProfile: RuntimeProfile, sessionActive: Boolean): SyncSummary =
        retainPendingAndStop(installation)

    override suspend fun syncGame(installation: DownloadedRomEntity, rom: RomDto, runtimeProfile: RuntimeProfile): SyncSummary =
        retainPendingAndStop(installation)

    override suspend fun adoptRemoteContinuity(installation: DownloadedRomEntity, rom: RomDto, runtimeProfile: RuntimeProfile): PlayerLaunchPreparation {
        throw LegacySyncUnavailableException()
    }

    override suspend fun refreshStateRecovery(installation: DownloadedRomEntity, rom: RomDto, runtimeProfile: RuntimeProfile) =
        withContext(Dispatchers.IO) {
            ownerProfile(installation)?.let { importLegacyManualStates(it, installation) }
            Unit
        }

    private suspend fun retainPendingAndStop(installation: DownloadedRomEntity): Nothing = withContext(Dispatchers.IO) {
        ownerProfile(installation)?.let { profileId ->
            importLegacyManualStates(profileId, installation)
            val existing = gameSyncJournalDao.getByKey(profileId, installation.romId, installation.fileId, installation.progressScope)
                ?: GameSyncJournalEntity(profileId, installation.romId, installation.fileId, progressScope = installation.progressScope)
            val sramHash = libraryStore.saveRamFile(installation).takeIf { it.isFile }?.sha256()
            val resumeHash = libraryStore.continuityResumeStateFile(installation).takeIf { it.isFile }?.sha256()
            gameSyncJournalDao.upsert(existing.copy(
                pendingContinuityUpload = existing.pendingContinuityUpload ||
                    (sramHash != null && sramHash != existing.lastSyncedSramHash) ||
                    (resumeHash != null && resumeHash != existing.lastSyncedResumeHash),
                lastSyncAttemptAtEpochMs = System.currentTimeMillis(),
                lastSyncNote = LEGACY_SYNC_PAUSED_MESSAGE,
            ))
        }
        captureAutoRecoveryState(installation)
        throw LegacySyncUnavailableException()
    }

    override suspend fun recordManualState(installation: DownloadedRomEntity, slot: Int, file: File) = withContext(Dispatchers.IO) {
        saveStateDao.upsert(SaveStateEntity(progressScope = installation.progressScope, romId = installation.romId, slot = slot, label = "Slot $slot", localPath = file.absolutePath, updatedAtEpochMs = file.lastModified()))
        val profileId = ownerProfile(installation) ?: return@withContext
        val existing = saveStateSyncJournalDao.getByKey(profileId, installation.romId, installation.fileId, slot, installation.progressScope)
            ?: SaveStateSyncJournalEntity(profileId, installation.romId, installation.fileId, slot, "Slot $slot", progressScope = installation.progressScope)
        saveStateSyncJournalDao.upsert(existing.copy(
            localPath = file.absolutePath, localHash = file.sha256(), localUpdatedAtEpochMs = file.lastModified(),
            sourceDeviceName = deviceName, deleted = false, pendingUpload = true, pendingDelete = false,
        ))
    }

    override suspend fun markManualStateDeleted(installation: DownloadedRomEntity, slot: Int) = withContext(Dispatchers.IO) {
        // Persist intent before removing the file; an interrupted delete cannot become an import.
        ownerProfile(installation)?.let { profileId ->
            val existing = saveStateSyncJournalDao.getByKey(profileId, installation.romId, installation.fileId, slot, installation.progressScope)
                ?: SaveStateSyncJournalEntity(profileId, installation.romId, installation.fileId, slot, "Slot $slot", progressScope = installation.progressScope)
            saveStateSyncJournalDao.upsert(existing.copy(deleted = true, pendingUpload = false, pendingDelete = true))
        }
        saveStateDao.delete(installation.romId, slot, installation.progressScope)
        val file = libraryStore.saveStateFile(installation, slot)
        if (file.exists() && !file.delete()) throw IOException("Could not remove the local save slot.")
    }

    private suspend fun importLegacyManualStates(profileId: String, installation: DownloadedRomEntity) {
        val resume = libraryStore.continuityResumeStateFile(installation)
        val slotZero = libraryStore.saveStateFile(installation, 0)
        if (!resume.exists() && slotZero.isFile && saveStateSyncJournalDao.getByKey(profileId, installation.romId, installation.fileId, 0, installation.progressScope) == null) {
            AtomicFiles.write(resume) { output -> slotZero.inputStream().use { it.copyTo(output) } }
        }
        val pattern = Regex("${installation.romId}_slot(\\d+)\\.state")
        for (file in libraryStore.saveStatesDirectory(installation).listFiles().orEmpty().filter { it.isFile }) {
            val slot = pattern.matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val existing = saveStateSyncJournalDao.getByKey(profileId, installation.romId, installation.fileId, slot, installation.progressScope)
            if (existing?.deleted == true || existing?.pendingDelete == true) continue
            val hash = file.sha256()
            saveStateDao.upsert(SaveStateEntity(progressScope = installation.progressScope, romId = installation.romId, slot = slot, label = "Slot $slot", localPath = file.absolutePath, updatedAtEpochMs = file.lastModified()))
            val row = existing ?: SaveStateSyncJournalEntity(profileId, installation.romId, installation.fileId, slot, "Slot $slot", progressScope = installation.progressScope)
            saveStateSyncJournalDao.upsert(row.copy(
                localPath = file.absolutePath, localHash = hash, localUpdatedAtEpochMs = file.lastModified(),
                sourceDeviceName = row.sourceDeviceName ?: deviceName,
                pendingUpload = row.pendingUpload || existing == null || (existing.localHash != hash),
            ))
        }
    }

    private suspend fun captureAutoRecoveryState(installation: DownloadedRomEntity) {
        val resume = libraryStore.continuityResumeStateFile(installation)
        if (!resume.isFile) return
        val now = System.currentTimeMillis()
        val existing = recoveryStateDao.listByGame(installation.romId, installation.fileId, installation.progressScope).filter { it.origin == RecoveryStateOrigin.AUTO_HISTORY.name }
        if (!shouldCaptureAutoRecovery(existing.map { it.capturedAtEpochMs }, now)) return
        val ring = recoveryRingIndex(now)
        val fileName = "${installation.romId}_${installation.fileId}_recovery_auto_$ring.state"
        val target = File(libraryStore.saveStatesDirectory(installation), fileName)
        AtomicFiles.write(target) { output -> resume.inputStream().use { it.copyTo(output) } }
        recoveryStateDao.upsert(RecoveryStateEntity(
            progressScope = installation.progressScope,
            romId = installation.romId, fileId = installation.fileId, entryId = "auto:$ring", label = "Auto snapshot",
            origin = RecoveryStateOrigin.AUTO_HISTORY.name, localPath = target.absolutePath, remoteFileName = fileName,
            localHash = target.sha256(), ringIndex = ring, sourceDeviceName = deviceName, capturedAtEpochMs = now,
        ))
    }

    private suspend fun ownerProfile(installation: DownloadedRomEntity): String? = installation.ownerProfileId ?: profileIdProvider()

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

internal fun shouldCaptureAutoRecovery(
    existingAutoCapturedAt: List<Long>,
    now: Long,
    intervalMs: Long = 30L * 60L * 1000L,
): Boolean {
    val currentBucket = now / intervalMs
    val latestBucket = existingAutoCapturedAt.maxOfOrNull { it / intervalMs }
    return latestBucket != currentBucket
}

internal fun recoveryRingIndex(
    now: Long,
    intervalMs: Long = 30L * 60L * 1000L,
    ringSize: Int = 10,
): Int {
    return ((now / intervalMs) % ringSize).toInt()
}
