package io.github.bandoracer.rommio.model

import io.github.bandoracer.rommio.data.local.GameSyncJournalEntity
import io.github.bandoracer.rommio.data.local.HardwareBindingProfileEntity
import io.github.bandoracer.rommio.data.local.RecoveryStateEntity
import io.github.bandoracer.rommio.data.local.SaveStateSyncJournalEntity
import io.github.bandoracer.rommio.data.local.ServerProfileEntity
import io.github.bandoracer.rommio.data.local.TouchLayoutProfileEntity
import io.github.bandoracer.rommio.domain.input.PlayerControlsPreferences

data class MigrationBundleManifest(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val sourcePackageId: String,
    val appVersionName: String,
    val appVersionCode: Long,
    val exportedAtEpochMs: Long,
) {
    companion object {
        const val CURRENT_FORMAT_VERSION = 1
    }
}

data class MigrationDownloadedRomRecord(
    val romId: Int,
    val fileId: Int,
    val platformSlug: String,
    val romName: String,
    val fileName: String,
    val localPathRelative: String,
    val fileSizeBytes: Long,
    val downloadedAtEpochMs: Long,
)

data class MigrationDownloadRecord(
    val romId: Int,
    val fileId: Int,
    val romName: String,
    val platformSlug: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val workId: String? = null,
    val status: String,
    val progressPercent: Int = 0,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = 0,
    val localPathRelative: String? = null,
    val lastError: String? = null,
    val enqueuedAtEpochMs: Long,
    val startedAtEpochMs: Long? = null,
    val completedAtEpochMs: Long? = null,
    val updatedAtEpochMs: Long,
)

data class MigrationSaveStateSyncJournalRecord(
    val profileId: String,
    val romId: Int,
    val fileId: Int,
    val slot: Int,
    val label: String,
    val sourceDeviceName: String? = null,
    val localPathRelative: String? = null,
    val localHash: String? = null,
    val localUpdatedAtEpochMs: Long? = null,
    val remoteHash: String? = null,
    val remoteUpdatedAtEpochMs: Long? = null,
    val deleted: Boolean = false,
    val pendingUpload: Boolean = false,
    val pendingDelete: Boolean = false,
    val lastSyncedAtEpochMs: Long? = null,
)

data class MigrationRecoveryStateRecord(
    val romId: Int,
    val fileId: Int,
    val entryId: String,
    val label: String,
    val origin: String,
    val localPathRelative: String,
    val remoteFileName: String,
    val localHash: String? = null,
    val remoteHash: String? = null,
    val ringIndex: Int? = null,
    val preserved: Boolean = false,
    val sourceDeviceName: String? = null,
    val capturedAtEpochMs: Long,
    val lastSyncedAtEpochMs: Long? = null,
)

data class MigrationDatabaseExport(
    val serverProfiles: List<ServerProfileEntity> = emptyList(),
    val downloadedRoms: List<MigrationDownloadedRomRecord> = emptyList(),
    val downloadRecords: List<MigrationDownloadRecord> = emptyList(),
    val touchLayoutProfiles: List<TouchLayoutProfileEntity> = emptyList(),
    val hardwareBindingProfiles: List<HardwareBindingProfileEntity> = emptyList(),
    val gameSyncJournal: List<GameSyncJournalEntity> = emptyList(),
    val saveStateSyncJournal: List<MigrationSaveStateSyncJournalRecord> = emptyList(),
    val recoveryStates: List<MigrationRecoveryStateRecord> = emptyList(),
)

data class MigrationProfileSecrets(
    val profileId: String,
    val deviceId: String? = null,
    val tokenBundle: TokenBundle? = null,
    val basicCredentials: DirectLoginCredentials? = null,
    val cloudflareCredentials: CloudflareServiceCredentials? = null,
)

data class MigrationAuthSecretsExport(
    val profiles: List<MigrationProfileSecrets> = emptyList(),
)

data class MigrationControlsPreferencesExport(
    val preferences: PlayerControlsPreferences = PlayerControlsPreferences(),
)

data class MigrationBundleInspection(
    val manifest: MigrationBundleManifest,
    val profileCount: Int,
    val installedGameCount: Int,
    val installedFileCount: Int,
    val downloadRecordCount: Int,
    val recoveryStateCount: Int,
    val manualSlotCount: Int,
    val libraryBytes: Long,
    val requiresReplace: Boolean,
)

data class MigrationImportSummary(
    val sourcePackageId: String,
    val profileCount: Int,
    val installedGameCount: Int,
    val installedFileCount: Int,
    val downloadRecordCount: Int,
    val recoveryStateCount: Int,
    val manualSlotCount: Int,
    val libraryBytes: Long,
)

internal fun SaveStateSyncJournalEntity.toMigrationRecord(localPathRelative: String?): MigrationSaveStateSyncJournalRecord {
    return MigrationSaveStateSyncJournalRecord(
        profileId = profileId,
        romId = romId,
        fileId = fileId,
        slot = slot,
        label = label,
        sourceDeviceName = sourceDeviceName,
        localPathRelative = localPathRelative,
        localHash = localHash,
        localUpdatedAtEpochMs = localUpdatedAtEpochMs,
        remoteHash = remoteHash,
        remoteUpdatedAtEpochMs = remoteUpdatedAtEpochMs,
        deleted = deleted,
        pendingUpload = pendingUpload,
        pendingDelete = pendingDelete,
        lastSyncedAtEpochMs = lastSyncedAtEpochMs,
    )
}

internal fun RecoveryStateEntity.toMigrationRecord(localPathRelative: String): MigrationRecoveryStateRecord {
    return MigrationRecoveryStateRecord(
        romId = romId,
        fileId = fileId,
        entryId = entryId,
        label = label,
        origin = origin,
        localPathRelative = localPathRelative,
        remoteFileName = remoteFileName,
        localHash = localHash,
        remoteHash = remoteHash,
        ringIndex = ringIndex,
        preserved = preserved,
        sourceDeviceName = sourceDeviceName,
        capturedAtEpochMs = capturedAtEpochMs,
        lastSyncedAtEpochMs = lastSyncedAtEpochMs,
    )
}
