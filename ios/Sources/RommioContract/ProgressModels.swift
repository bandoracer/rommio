import Foundation

public struct ProgressContentFile: Codable, Hashable, Sendable {
    public let fileId: Int64
    public let discIndex: Int
    public let contentSha256: String
    public let containerSha256: String?
    public let memberPath: String?

    public init(fileId: Int64, discIndex: Int, contentSha256: String, containerSha256: String?, memberPath: String?) {
        self.fileId = fileId
        self.discIndex = discIndex
        self.contentSha256 = contentSha256
        self.containerSha256 = containerSha256
        self.memberPath = memberPath
    }
}

public struct ProgressBinding: Codable, Hashable, Sendable {
    public let serverKey: String
    public let accountId: String
    public let romId: Int64
    public let files: [ProgressContentFile]

    public init(serverKey: String, accountId: String, romId: Int64, files: [ProgressContentFile]) {
        self.serverKey = serverKey
        self.accountId = accountId
        self.romId = romId
        self.files = files
    }
}

public struct ProgressSaveCompatibility: Codable, Hashable, Sendable {
    public let system: String
    public let format: String
    public let layoutVersion: String
    public let coreId: String?
    public let allowsEmpty: Bool

    public init(system: String, format: String, layoutVersion: String, coreId: String?, allowsEmpty: Bool) {
        self.system = system
        self.format = format
        self.layoutVersion = layoutVersion
        self.coreId = coreId
        self.allowsEmpty = allowsEmpty
    }
}

public struct ProgressStateCompatibility: Codable, Hashable, Sendable {
    public let coreId: String
    public let coreSha256: String
    public let stateFormat: String
    public let platform: String
    public let architecture: String
    public let options: [String: String]
    public let systemFiles: [String: String]

    public init(coreId: String, coreSha256: String, stateFormat: String, platform: String, architecture: String, options: [String: String], systemFiles: [String: String]) {
        self.coreId = coreId
        self.coreSha256 = coreSha256
        self.stateFormat = stateFormat
        self.platform = platform
        self.architecture = architecture
        self.options = options
        self.systemFiles = systemFiles
    }
}

public struct ProgressArtifact: Codable, Hashable, Sendable {
    public let sha256: String
    public let byteLength: Int64
    public let role: String
    public let relativePath: String
    public let format: String

    public init(sha256: String, byteLength: Int64, role: String, relativePath: String, format: String) {
        self.sha256 = sha256
        self.byteLength = byteLength
        self.role = role
        self.relativePath = relativePath
        self.format = format
    }
}

public struct ProgressCheckpoint: Codable, Hashable, Sendable {
    public let schemaVersion: Int
    public let checkpointId: String
    public let binding: ProgressBinding
    public let saveCompatibility: ProgressSaveCompatibility
    public let stateCompatibility: ProgressStateCompatibility?
    public let saveDataComplete: Bool
    public let artifacts: [ProgressArtifact]
    public let deviceId: String
    public let sessionId: String
    public let captureReason: String
    public let createdAtEpochMs: Int64
    public let gameSaveOperationId: String?

    public init(schemaVersion: Int, checkpointId: String, binding: ProgressBinding, saveCompatibility: ProgressSaveCompatibility, stateCompatibility: ProgressStateCompatibility?, saveDataComplete: Bool, artifacts: [ProgressArtifact], deviceId: String, sessionId: String, captureReason: String, createdAtEpochMs: Int64, gameSaveOperationId: String? = nil) {
        self.schemaVersion = schemaVersion
        self.checkpointId = checkpointId
        self.binding = binding
        self.saveCompatibility = saveCompatibility
        self.stateCompatibility = stateCompatibility
        self.saveDataComplete = saveDataComplete
        self.artifacts = artifacts
        self.deviceId = deviceId
        self.sessionId = sessionId
        self.gameSaveOperationId = gameSaveOperationId
        self.captureReason = captureReason
        self.createdAtEpochMs = createdAtEpochMs
    }
}

public struct ProgressRefChange: Codable, Hashable, Sendable {
    public let reference: String
    public let parents: [String]
    public let checkpointId: String?
    public let tombstone: Bool

    public init(reference: String, parents: [String], checkpointId: String?, tombstone: Bool) {
        self.reference = reference
        self.parents = parents
        self.checkpointId = checkpointId
        self.tombstone = tombstone
    }
}

public struct ProgressOperation: Codable, Hashable, Sendable {
    public let schemaVersion: Int
    public let operationId: String
    public let binding: ProgressBinding
    public let changes: [ProgressRefChange]
    public let checkpoints: [ProgressCheckpoint]
    public let deviceId: String
    public let sessionId: String
    public let createdAtEpochMs: Int64

    public init(schemaVersion: Int, operationId: String, binding: ProgressBinding, changes: [ProgressRefChange], checkpoints: [ProgressCheckpoint], deviceId: String, sessionId: String, createdAtEpochMs: Int64) {
        self.schemaVersion = schemaVersion
        self.operationId = operationId
        self.binding = binding
        self.changes = changes
        self.checkpoints = checkpoints
        self.deviceId = deviceId
        self.sessionId = sessionId
        self.createdAtEpochMs = createdAtEpochMs
    }
}

public struct ProgressIdentity: Codable, Hashable, Sendable {
    public let namespaceId: String
    public let contentId: String
    public let binding: ProgressBinding

    public init(namespaceId: String, contentId: String, binding: ProgressBinding) {
        self.namespaceId = namespaceId
        self.contentId = contentId
        self.binding = binding
    }
}
