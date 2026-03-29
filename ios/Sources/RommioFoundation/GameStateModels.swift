import Foundation

public enum CloudStateKind: String, Codable, Hashable, Sendable {
    case resume = "RESUME"
    case manualSlot = "MANUAL_SLOT"
    case recoveryHistory = "RECOVERY_HISTORY"
    case manifest = "MANIFEST"
}

public struct CloudSaveRevision: Codable, Hashable, Sendable {
    public var fileName: String
    public var hash: String?
    public var updatedAtEpochMS: Int64
    public var sourceDeviceID: String?
    public var sourceDeviceName: String?

    public init(
        fileName: String,
        hash: String? = nil,
        updatedAtEpochMS: Int64,
        sourceDeviceID: String? = nil,
        sourceDeviceName: String? = nil
    ) {
        self.fileName = fileName
        self.hash = hash
        self.updatedAtEpochMS = updatedAtEpochMS
        self.sourceDeviceID = sourceDeviceID
        self.sourceDeviceName = sourceDeviceName
    }
}

public struct CloudStateRevision: Codable, Hashable, Sendable {
    public var fileName: String
    public var kind: CloudStateKind
    public var slot: Int?
    public var ringIndex: Int?
    public var hash: String?
    public var updatedAtEpochMS: Int64
    public var sourceDeviceID: String?
    public var sourceDeviceName: String?
    public var preserved: Bool
    public var deleted: Bool

    public init(
        fileName: String,
        kind: CloudStateKind,
        slot: Int? = nil,
        ringIndex: Int? = nil,
        hash: String? = nil,
        updatedAtEpochMS: Int64,
        sourceDeviceID: String? = nil,
        sourceDeviceName: String? = nil,
        preserved: Bool = false,
        deleted: Bool = false
    ) {
        self.fileName = fileName
        self.kind = kind
        self.slot = slot
        self.ringIndex = ringIndex
        self.hash = hash
        self.updatedAtEpochMS = updatedAtEpochMS
        self.sourceDeviceID = sourceDeviceID
        self.sourceDeviceName = sourceDeviceName
        self.preserved = preserved
        self.deleted = deleted
    }
}

public struct GameSyncManifest: Codable, Hashable, Sendable {
    public var version: Int
    public var romID: Int
    public var fileID: Int
    public var deviceID: String
    public var deviceName: String
    public var sessionActive: Bool
    public var sessionStartedAtEpochMS: Int64?
    public var lastHeartbeatEpochMS: Int64
    public var sram: CloudSaveRevision?
    public var resume: CloudStateRevision?
    public var manualSlots: [CloudStateRevision]
    public var recoveryHistory: [CloudStateRevision]

    public init(
        version: Int = 1,
        romID: Int,
        fileID: Int,
        deviceID: String,
        deviceName: String,
        sessionActive: Bool = false,
        sessionStartedAtEpochMS: Int64? = nil,
        lastHeartbeatEpochMS: Int64,
        sram: CloudSaveRevision? = nil,
        resume: CloudStateRevision? = nil,
        manualSlots: [CloudStateRevision] = [],
        recoveryHistory: [CloudStateRevision] = []
    ) {
        self.version = version
        self.romID = romID
        self.fileID = fileID
        self.deviceID = deviceID
        self.deviceName = deviceName
        self.sessionActive = sessionActive
        self.sessionStartedAtEpochMS = sessionStartedAtEpochMS
        self.lastHeartbeatEpochMS = lastHeartbeatEpochMS
        self.sram = sram
        self.resume = resume
        self.manualSlots = manualSlots
        self.recoveryHistory = recoveryHistory
    }
}

public struct ResumeConflict: Codable, Hashable, Sendable {
    public var remoteDeviceName: String?
    public var remoteUpdatedAtEpochMS: Int64
    public var localUpdatedAtEpochMS: Int64

    public init(
        remoteDeviceName: String? = nil,
        remoteUpdatedAtEpochMS: Int64,
        localUpdatedAtEpochMS: Int64
    ) {
        self.remoteDeviceName = remoteDeviceName
        self.remoteUpdatedAtEpochMS = remoteUpdatedAtEpochMS
        self.localUpdatedAtEpochMS = localUpdatedAtEpochMS
    }
}

public enum GameSyncStatusKind: String, Codable, Hashable, Sendable {
    case idle = "IDLE"
    case synced = "SYNCED"
    case offlinePending = "OFFLINE_PENDING"
    case cloudProgressAvailable = "CLOUD_PROGRESS_AVAILABLE"
    case conflict = "CONFLICT"
    case localOnly = "LOCAL_ONLY"
    case error = "ERROR"
}

public struct GameSyncPresentation: Codable, Hashable, Sendable {
    public var kind: GameSyncStatusKind
    public var message: String
    public var lastSuccessfulSyncAtEpochMS: Int64?
    public var remoteDeviceName: String?

    public init(
        kind: GameSyncStatusKind = .idle,
        message: String = "Not synced yet.",
        lastSuccessfulSyncAtEpochMS: Int64? = nil,
        remoteDeviceName: String? = nil
    ) {
        self.kind = kind
        self.message = message
        self.lastSuccessfulSyncAtEpochMS = lastSuccessfulSyncAtEpochMS
        self.remoteDeviceName = remoteDeviceName
    }
}

public enum ResumeStateStatusKind: String, Codable, Hashable, Sendable {
    case synced = "SYNCED"
    case syncedRemoteSource = "SYNCED_REMOTE_SOURCE"
    case pendingUpload = "PENDING_UPLOAD"
    case cloudAvailable = "CLOUD_AVAILABLE"
    case conflict = "CONFLICT"
    case error = "ERROR"
    case localOnly = "LOCAL_ONLY"
    case unavailable = "UNAVAILABLE"
}

public enum ResumeStateSourceOrigin: String, Codable, Hashable, Sendable {
    case thisDevice = "THIS_DEVICE"
    case remoteDevice = "REMOTE_DEVICE"
}

public struct ResumeStateSummary: Codable, Hashable, Sendable {
    public var available: Bool
    public var localPath: String?
    public var updatedAtEpochMS: Int64?
    public var statusKind: ResumeStateStatusKind
    public var lastSuccessfulSyncAtEpochMS: Int64?
    public var sourceDeviceID: String?
    public var sourceDeviceName: String?
    public var sourceOrigin: ResumeStateSourceOrigin
    public var primaryStatusMessage: String

    public init(
        available: Bool,
        localPath: String? = nil,
        updatedAtEpochMS: Int64? = nil,
        statusKind: ResumeStateStatusKind = .unavailable,
        lastSuccessfulSyncAtEpochMS: Int64? = nil,
        sourceDeviceID: String? = nil,
        sourceDeviceName: String? = nil,
        sourceOrigin: ResumeStateSourceOrigin = .thisDevice,
        primaryStatusMessage: String = "No resume state yet."
    ) {
        self.available = available
        self.localPath = localPath
        self.updatedAtEpochMS = updatedAtEpochMS
        self.statusKind = statusKind
        self.lastSuccessfulSyncAtEpochMS = lastSuccessfulSyncAtEpochMS
        self.sourceDeviceID = sourceDeviceID
        self.sourceDeviceName = sourceDeviceName
        self.sourceOrigin = sourceOrigin
        self.primaryStatusMessage = primaryStatusMessage
    }
}

public enum RecoveryStateOrigin: String, Codable, Hashable, Sendable {
    case autoHistory = "AUTO_HISTORY"
    case legacyImport = "LEGACY_IMPORT"
}

public enum BrowsableGameStateKind: String, Codable, Hashable, Sendable {
    case manualSlot = "MANUAL_SLOT"
    case recoveryHistory = "RECOVERY_HISTORY"
    case importedCloud = "IMPORTED_CLOUD"
}

public enum BrowsableGameStateOrigin: String, Codable, Hashable, Sendable {
    case manualSlot = "MANUAL_SLOT"
    case importedPlayable = "IMPORTED_PLAYABLE"
    case autoSnapshot = "AUTO_SNAPSHOT"
}

public enum GameStateDeletePolicy: String, Codable, Hashable, Sendable {
    case none = "NONE"
    case localOnly = "LOCAL_ONLY"
    case localAndRemoteWhenSupported = "LOCAL_AND_REMOTE_WHEN_SUPPORTED"
}

public struct BrowsableGameState: Codable, Hashable, Sendable, Identifiable {
    public var id: String
    public var kind: BrowsableGameStateKind
    public var label: String
    public var localPath: String
    public var updatedAtEpochMS: Int64
    public var slot: Int?
    public var ringIndex: Int?
    public var preserved: Bool
    public var sourceDeviceID: String?
    public var sourceDeviceName: String?
    public var originKind: BrowsableGameStateOrigin
    public var deletePolicy: GameStateDeletePolicy

    public init(
        id: String,
        kind: BrowsableGameStateKind,
        label: String,
        localPath: String,
        updatedAtEpochMS: Int64,
        slot: Int? = nil,
        ringIndex: Int? = nil,
        preserved: Bool = false,
        sourceDeviceID: String? = nil,
        sourceDeviceName: String? = nil,
        originKind: BrowsableGameStateOrigin,
        deletePolicy: GameStateDeletePolicy
    ) {
        self.id = id
        self.kind = kind
        self.label = label
        self.localPath = localPath
        self.updatedAtEpochMS = updatedAtEpochMS
        self.slot = slot
        self.ringIndex = ringIndex
        self.preserved = preserved
        self.sourceDeviceID = sourceDeviceID
        self.sourceDeviceName = sourceDeviceName
        self.originKind = originKind
        self.deletePolicy = deletePolicy
    }
}

public struct GameStateBrowser: Codable, Hashable, Sendable {
    public var resume: ResumeStateSummary?
    public var saveSlots: [BrowsableGameState]
    public var snapshots: [BrowsableGameState]

    public init(
        resume: ResumeStateSummary? = nil,
        saveSlots: [BrowsableGameState] = [],
        snapshots: [BrowsableGameState] = []
    ) {
        self.resume = resume
        self.saveSlots = saveSlots
        self.snapshots = snapshots
    }
}

public enum PlayerLaunchTargetKind: String, Codable, Hashable, Sendable {
    case continuity = "CONTINUITY"
    case manualSlot = "MANUAL_SLOT"
    case recoveryHistory = "RECOVERY_HISTORY"
    case importedCloud = "IMPORTED_CLOUD"
}

public struct PlayerLaunchTarget: Codable, Hashable, Sendable {
    public var kind: PlayerLaunchTargetKind
    public var localStatePath: String
    public var stateID: String?
    public var label: String?

    public init(
        kind: PlayerLaunchTargetKind,
        localStatePath: String,
        stateID: String? = nil,
        label: String? = nil
    ) {
        self.kind = kind
        self.localStatePath = localStatePath
        self.stateID = stateID
        self.label = label
    }
}

public struct PlayerLaunchPreparation: Codable, Hashable, Sendable {
    public var launchTarget: PlayerLaunchTarget?
    public var resumeConflict: ResumeConflict?
    public var syncPresentation: GameSyncPresentation

    public init(
        launchTarget: PlayerLaunchTarget? = nil,
        resumeConflict: ResumeConflict? = nil,
        syncPresentation: GameSyncPresentation = GameSyncPresentation()
    ) {
        self.launchTarget = launchTarget
        self.resumeConflict = resumeConflict
        self.syncPresentation = syncPresentation
    }
}

public struct GameSyncJournal: Codable, Hashable, Sendable {
    public var profileID: String
    public var romID: Int
    public var fileID: Int
    public var lastSyncedSRAMHash: String?
    public var lastSyncedResumeHash: String?
    public var remoteSRAMHash: String?
    public var remoteResumeHash: String?
    public var remoteDeviceID: String?
    public var remoteDeviceName: String?
    public var remoteSessionActive: Bool
    public var remoteSessionHeartbeatEpochMS: Int64?
    public var remoteContinuityUpdatedAtEpochMS: Int64?
    public var remoteContinuityAvailable: Bool
    public var pendingContinuityUpload: Bool
    public var lastSuccessfulSyncAtEpochMS: Int64?
    public var lastSyncAttemptAtEpochMS: Int64?
    public var lastSyncNote: String?
    public var lastError: String?

    public init(
        profileID: String,
        romID: Int,
        fileID: Int,
        lastSyncedSRAMHash: String? = nil,
        lastSyncedResumeHash: String? = nil,
        remoteSRAMHash: String? = nil,
        remoteResumeHash: String? = nil,
        remoteDeviceID: String? = nil,
        remoteDeviceName: String? = nil,
        remoteSessionActive: Bool = false,
        remoteSessionHeartbeatEpochMS: Int64? = nil,
        remoteContinuityUpdatedAtEpochMS: Int64? = nil,
        remoteContinuityAvailable: Bool = false,
        pendingContinuityUpload: Bool = false,
        lastSuccessfulSyncAtEpochMS: Int64? = nil,
        lastSyncAttemptAtEpochMS: Int64? = nil,
        lastSyncNote: String? = nil,
        lastError: String? = nil
    ) {
        self.profileID = profileID
        self.romID = romID
        self.fileID = fileID
        self.lastSyncedSRAMHash = lastSyncedSRAMHash
        self.lastSyncedResumeHash = lastSyncedResumeHash
        self.remoteSRAMHash = remoteSRAMHash
        self.remoteResumeHash = remoteResumeHash
        self.remoteDeviceID = remoteDeviceID
        self.remoteDeviceName = remoteDeviceName
        self.remoteSessionActive = remoteSessionActive
        self.remoteSessionHeartbeatEpochMS = remoteSessionHeartbeatEpochMS
        self.remoteContinuityUpdatedAtEpochMS = remoteContinuityUpdatedAtEpochMS
        self.remoteContinuityAvailable = remoteContinuityAvailable
        self.pendingContinuityUpload = pendingContinuityUpload
        self.lastSuccessfulSyncAtEpochMS = lastSuccessfulSyncAtEpochMS
        self.lastSyncAttemptAtEpochMS = lastSyncAttemptAtEpochMS
        self.lastSyncNote = lastSyncNote
        self.lastError = lastError
    }
}

public struct SaveStateSyncJournal: Codable, Hashable, Sendable {
    public var profileID: String
    public var romID: Int
    public var fileID: Int
    public var slot: Int
    public var label: String
    public var localPath: String?
    public var localHash: String?
    public var localUpdatedAtEpochMS: Int64?
    public var remoteHash: String?
    public var remoteUpdatedAtEpochMS: Int64?
    public var sourceDeviceID: String?
    public var sourceDeviceName: String?
    public var deleted: Bool
    public var pendingUpload: Bool
    public var pendingDelete: Bool
    public var lastSyncedAtEpochMS: Int64?

    public init(
        profileID: String,
        romID: Int,
        fileID: Int,
        slot: Int,
        label: String,
        localPath: String? = nil,
        localHash: String? = nil,
        localUpdatedAtEpochMS: Int64? = nil,
        remoteHash: String? = nil,
        remoteUpdatedAtEpochMS: Int64? = nil,
        sourceDeviceID: String? = nil,
        sourceDeviceName: String? = nil,
        deleted: Bool = false,
        pendingUpload: Bool = false,
        pendingDelete: Bool = false,
        lastSyncedAtEpochMS: Int64? = nil
    ) {
        self.profileID = profileID
        self.romID = romID
        self.fileID = fileID
        self.slot = slot
        self.label = label
        self.localPath = localPath
        self.localHash = localHash
        self.localUpdatedAtEpochMS = localUpdatedAtEpochMS
        self.remoteHash = remoteHash
        self.remoteUpdatedAtEpochMS = remoteUpdatedAtEpochMS
        self.sourceDeviceID = sourceDeviceID
        self.sourceDeviceName = sourceDeviceName
        self.deleted = deleted
        self.pendingUpload = pendingUpload
        self.pendingDelete = pendingDelete
        self.lastSyncedAtEpochMS = lastSyncedAtEpochMS
    }
}

public struct RecoveryStateRecord: Codable, Hashable, Sendable {
    public var romID: Int
    public var fileID: Int
    public var entryID: String
    public var label: String
    public var origin: RecoveryStateOrigin
    public var localPath: String
    public var remoteFileName: String
    public var localHash: String?
    public var remoteHash: String?
    public var ringIndex: Int?
    public var preserved: Bool
    public var sourceDeviceID: String?
    public var sourceDeviceName: String?
    public var capturedAtEpochMS: Int64
    public var lastSyncedAtEpochMS: Int64?

    public init(
        romID: Int,
        fileID: Int,
        entryID: String,
        label: String,
        origin: RecoveryStateOrigin,
        localPath: String,
        remoteFileName: String,
        localHash: String? = nil,
        remoteHash: String? = nil,
        ringIndex: Int? = nil,
        preserved: Bool = false,
        sourceDeviceID: String? = nil,
        sourceDeviceName: String? = nil,
        capturedAtEpochMS: Int64,
        lastSyncedAtEpochMS: Int64? = nil
    ) {
        self.romID = romID
        self.fileID = fileID
        self.entryID = entryID
        self.label = label
        self.origin = origin
        self.localPath = localPath
        self.remoteFileName = remoteFileName
        self.localHash = localHash
        self.remoteHash = remoteHash
        self.ringIndex = ringIndex
        self.preserved = preserved
        self.sourceDeviceID = sourceDeviceID
        self.sourceDeviceName = sourceDeviceName
        self.capturedAtEpochMS = capturedAtEpochMS
        self.lastSyncedAtEpochMS = lastSyncedAtEpochMS
    }
}
