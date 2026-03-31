package io.github.bandoracer.rommio.model

enum class CloudStateKind {
    RESUME,
    MANUAL_SLOT,
    RECOVERY_HISTORY,
    MANIFEST,
}

data class CloudSaveRevision(
    val fileName: String,
    val hash: String? = null,
    val updatedAtEpochMs: Long,
    val sourceDeviceId: String? = null,
    val sourceDeviceName: String? = null,
)

data class CloudStateRevision(
    val fileName: String,
    val kind: CloudStateKind,
    val slot: Int? = null,
    val ringIndex: Int? = null,
    val hash: String? = null,
    val updatedAtEpochMs: Long,
    val sourceDeviceId: String? = null,
    val sourceDeviceName: String? = null,
    val preserved: Boolean = false,
    val deleted: Boolean = false,
)

data class GameSyncManifest(
    val version: Int = 1,
    val romId: Int,
    val fileId: Int,
    val deviceId: String,
    val deviceName: String,
    val sessionActive: Boolean = false,
    val sessionStartedAtEpochMs: Long? = null,
    val lastHeartbeatEpochMs: Long,
    val sram: CloudSaveRevision? = null,
    val resume: CloudStateRevision? = null,
    val manualSlots: List<CloudStateRevision> = emptyList(),
    val recoveryHistory: List<CloudStateRevision> = emptyList(),
)

data class ResumeConflict(
    val remoteDeviceName: String? = null,
    val remoteUpdatedAtEpochMs: Long,
    val localUpdatedAtEpochMs: Long,
)

enum class GameSyncStatusKind {
    IDLE,
    SYNCED,
    OFFLINE_PENDING,
    CLOUD_PROGRESS_AVAILABLE,
    CONFLICT,
    LOCAL_ONLY,
    ERROR,
}

data class GameSyncPresentation(
    val kind: GameSyncStatusKind = GameSyncStatusKind.IDLE,
    val message: String = "Not synced yet.",
    val lastSuccessfulSyncAtEpochMs: Long? = null,
    val remoteDeviceName: String? = null,
)

enum class ResumeStateStatusKind {
    SYNCED,
    SYNCED_REMOTE_SOURCE,
    PENDING_UPLOAD,
    CLOUD_AVAILABLE,
    CONFLICT,
    ERROR,
    LOCAL_ONLY,
    UNAVAILABLE,
}

enum class ResumeStateSourceOrigin {
    THIS_DEVICE,
    REMOTE_DEVICE,
}

data class ResumeStateSummary(
    val available: Boolean,
    val localPath: String? = null,
    val updatedAtEpochMs: Long? = null,
    val statusKind: ResumeStateStatusKind = ResumeStateStatusKind.UNAVAILABLE,
    val lastSuccessfulSyncAtEpochMs: Long? = null,
    val sourceDeviceId: String? = null,
    val sourceDeviceName: String? = null,
    val sourceOrigin: ResumeStateSourceOrigin = ResumeStateSourceOrigin.THIS_DEVICE,
    val primaryStatusMessage: String = "No resume state yet.",
)

enum class RecoveryStateOrigin {
    AUTO_HISTORY,
    LEGACY_IMPORT,
}

enum class BrowsableGameStateKind {
    MANUAL_SLOT,
    RECOVERY_HISTORY,
    IMPORTED_CLOUD,
}

enum class BrowsableGameStateOrigin {
    MANUAL_SLOT,
    IMPORTED_PLAYABLE,
    AUTO_SNAPSHOT,
}

enum class GameStateDeletePolicy {
    NONE,
    LOCAL_ONLY,
    LOCAL_AND_REMOTE,
}

data class BrowsableGameState(
    val id: String,
    val kind: BrowsableGameStateKind,
    val label: String,
    val localPath: String,
    val updatedAtEpochMs: Long,
    val slot: Int? = null,
    val ringIndex: Int? = null,
    val preserved: Boolean = false,
    val sourceDeviceName: String? = null,
    val originType: BrowsableGameStateOrigin = when (kind) {
        BrowsableGameStateKind.MANUAL_SLOT -> BrowsableGameStateOrigin.MANUAL_SLOT
        BrowsableGameStateKind.RECOVERY_HISTORY -> BrowsableGameStateOrigin.AUTO_SNAPSHOT
        BrowsableGameStateKind.IMPORTED_CLOUD -> BrowsableGameStateOrigin.IMPORTED_PLAYABLE
    },
    val deletePolicy: GameStateDeletePolicy = when (kind) {
        BrowsableGameStateKind.MANUAL_SLOT -> GameStateDeletePolicy.LOCAL_AND_REMOTE
        BrowsableGameStateKind.RECOVERY_HISTORY -> GameStateDeletePolicy.NONE
        BrowsableGameStateKind.IMPORTED_CLOUD -> GameStateDeletePolicy.LOCAL_ONLY
    },
)

data class GameStateRecovery(
    val resume: ResumeStateSummary? = null,
    val saveSlots: List<BrowsableGameState> = emptyList(),
    val snapshots: List<BrowsableGameState> = emptyList(),
)

enum class PlayerLaunchTargetKind {
    CONTINUITY,
    MANUAL_SLOT,
    RECOVERY_HISTORY,
    IMPORTED_CLOUD,
}

data class PlayerLaunchTarget(
    val kind: PlayerLaunchTargetKind,
    val localStatePath: String,
    val stateId: String? = null,
    val label: String? = null,
)

data class PlayerLaunchPreparation(
    val launchTarget: PlayerLaunchTarget? = null,
    val resumeConflict: ResumeConflict? = null,
    val syncPresentation: GameSyncPresentation = GameSyncPresentation(),
)

data class PendingContinuitySyncPayload(
    val romId: Int,
    val fileId: Int,
)

data class PendingManualSlotPayload(
    val romId: Int,
    val fileId: Int,
    val slot: Int,
)
