import Foundation
import GRDB

/// One launch of one game on this device (docs/play-data-local.md §1). Local only:
/// never synced, never part of a checkpoint or a progress backup.
public struct PlaySessionRecord: Codable, FetchableRecord, PersistableRecord, Hashable, Sendable {
    public static let databaseTableName = "play_sessions"

    /// The progress session id: the same id every checkpoint of this launch carries.
    public var sessionId: String
    /// Progress namespace and content generation, as the progress store identifies the game.
    public var namespaceId: String
    public var contentId: String
    /// Server/account binding of that namespace, for filtering by the signed-in account.
    public var serverKey: String
    public var accountId: String
    public var romId: Int64
    /// First file (disc 0) of the content binding.
    public var fileId: Int64?
    public var platformSlug: String?
    public var deviceId: String
    public var coreId: String?
    public var startedAtEpochMs: Int64
    /// Nil while the session is open.
    public var endedAtEpochMs: Int64?
    /// Last time the recorder saw this session alive; closes abandoned sessions after process death.
    public var lastHeartbeatEpochMs: Int64
    /// Unpaused, foreground play only.
    public var activeDurationMs: Int64
    /// resume | state | quit | background; nil while open.
    public var endReason: String?

    public init(sessionId: String, namespaceId: String, contentId: String, serverKey: String, accountId: String, romId: Int64, fileId: Int64?,
                platformSlug: String?, deviceId: String, coreId: String?, startedAtEpochMs: Int64, endedAtEpochMs: Int64? = nil,
                lastHeartbeatEpochMs: Int64, activeDurationMs: Int64 = 0, endReason: String? = nil) {
        self.sessionId = sessionId
        self.namespaceId = namespaceId
        self.contentId = contentId
        self.serverKey = serverKey
        self.accountId = accountId
        self.romId = romId
        self.fileId = fileId
        self.platformSlug = platformSlug
        self.deviceId = deviceId
        self.coreId = coreId
        self.startedAtEpochMs = startedAtEpochMs
        self.endedAtEpochMs = endedAtEpochMs
        self.lastHeartbeatEpochMs = lastHeartbeatEpochMs
        self.activeDurationMs = activeDurationMs
        self.endReason = endReason
    }

    public var reason: PlaySessionEndReason? { endReason.flatMap(PlaySessionEndReason.init(rawValue:)) }
}

/// Optional player name for one save state, keyed by the checkpoint that holds it. Local only.
public struct SaveStateLabelRecord: Codable, FetchableRecord, PersistableRecord, Hashable, Sendable {
    public static let databaseTableName = "save_state_labels"
    public var namespaceId: String
    public var contentId: String
    public var checkpointId: String
    public var label: String
    public var updatedAtEpochMs: Int64

    public init(namespaceId: String, contentId: String, checkpointId: String, label: String, updatedAtEpochMs: Int64) {
        self.namespaceId = namespaceId
        self.contentId = contentId
        self.checkpointId = checkpointId
        self.label = label
        self.updatedAtEpochMs = updatedAtEpochMs
    }
}

/// Reads and writes the local play-data tables. Synchronous; callers pick the thread.
public struct PlayDataStore: Sendable {
    public let database: AppDatabase

    public init(database: AppDatabase) {
        self.database = database
    }

    public func insert(_ session: PlaySessionRecord) throws {
        try database.write { try session.insert($0, onConflict: .replace) }
    }

    public func session(_ sessionId: String) throws -> PlaySessionRecord? {
        try database.read { try PlaySessionRecord.fetchOne($0, key: sessionId) }
    }

    /// Newest first.
    public func sessions(serverKey: String, accountId: String) throws -> [PlaySessionRecord] {
        try database.read {
            try PlaySessionRecord.fetchAll($0, sql: "SELECT * FROM play_sessions WHERE serverKey = ? AND accountId = ? ORDER BY startedAtEpochMs DESC", arguments: [serverKey, accountId])
        }
    }

    public func openSessions() throws -> [PlaySessionRecord] {
        try database.read { try PlaySessionRecord.fetchAll($0, sql: "SELECT * FROM play_sessions WHERE endedAtEpochMs IS NULL") }
    }

    /// Updates an open session's heartbeat and active time; a finished row is immutable.
    public func heartbeat(_ sessionId: String, at heartbeat: Int64, activeMs: Int64) throws {
        try database.write {
            try $0.execute(sql: "UPDATE play_sessions SET lastHeartbeatEpochMs = ?, activeDurationMs = ? WHERE sessionId = ? AND endedAtEpochMs IS NULL",
                           arguments: [heartbeat, activeMs, sessionId])
        }
    }

    /// Finishes an open session. Returns false when it was already finished (or unknown).
    @discardableResult
    public func finish(_ sessionId: String, endedAt: Int64, activeMs: Int64, reason: PlaySessionEndReason) throws -> Bool {
        try database.write {
            try $0.execute(sql: "UPDATE play_sessions SET endedAtEpochMs = ?, lastHeartbeatEpochMs = ?, activeDurationMs = ?, endReason = ? WHERE sessionId = ? AND endedAtEpochMs IS NULL",
                           arguments: [endedAt, endedAt, activeMs, reason.rawValue, sessionId])
            return $0.changesCount > 0
        }
    }

    /// Closes every open session not in `live` at its last heartbeat, reason "background".
    @discardableResult
    public func closeAbandoned(except live: Set<String> = []) throws -> Int {
        var closed = 0
        for row in try openSessions() where !live.contains(row.sessionId) {
            let close = PlaySessionRules.closeAbandoned(startedAtEpochMs: row.startedAtEpochMs, lastHeartbeatEpochMs: row.lastHeartbeatEpochMs,
                                                        activeDurationMs: row.activeDurationMs)
            if try finish(row.sessionId, endedAt: close.endedAtEpochMs, activeMs: close.activeDurationMs, reason: close.reason) { closed += 1 }
        }
        return closed
    }

    public func labels(namespaceId: String, contentId: String) throws -> [SaveKey: String] {
        try database.read {
            try SaveStateLabelRecord.fetchAll($0, sql: "SELECT * FROM save_state_labels WHERE namespaceId = ? AND contentId = ?", arguments: [namespaceId, contentId])
        }
        .reduce(into: [:]) { $0[SaveKey(namespaceId: $1.namespaceId, contentId: $1.contentId, checkpointId: $1.checkpointId)] = $1.label }
    }

    /// Names a save state (normalised); an empty name removes the row. Returns the stored name.
    @discardableResult
    public func setLabel(_ key: SaveKey, raw: String, now: Date = Date()) throws -> String? {
        let label = SavedProgressRules.normalizeLabel(raw)
        try database.write { db in
            if let label {
                try SaveStateLabelRecord(namespaceId: key.namespaceId, contentId: key.contentId, checkpointId: key.checkpointId, label: label,
                                         updatedAtEpochMs: Int64(now.timeIntervalSince1970 * 1000)).insert(db, onConflict: .replace)
            } else {
                try db.execute(sql: "DELETE FROM save_state_labels WHERE namespaceId = ? AND contentId = ? AND checkpointId = ?",
                               arguments: [key.namespaceId, key.contentId, key.checkpointId])
            }
        }
        return label
    }
}
