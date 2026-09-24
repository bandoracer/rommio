import Foundation
import RommioContract

/// One progress checkpoint reduced to what Stats needs. Read from the local
/// progress store; nothing here is written back or synced.
public struct PlayCheckpointSample: Hashable, Sendable {
    public let romID: Int
    public let sessionID: String
    public let deviceID: String
    public let createdAt: Date
    public let captureReason: String

    public init(romID: Int, sessionID: String, deviceID: String, createdAt: Date, captureReason: String = "periodic") {
        self.romID = romID
        self.sessionID = sessionID
        self.deviceID = deviceID
        self.createdAt = createdAt
        self.captureReason = captureReason
    }

    public init(checkpoint: ProgressCheckpoint) {
        self.init(romID: Int(checkpoint.binding.romId), sessionID: checkpoint.sessionId, deviceID: checkpoint.deviceId,
                  createdAt: Date(timeIntervalSince1970: TimeInterval(checkpoint.createdAtEpochMs) / 1000),
                  captureReason: checkpoint.captureReason)
    }
}

/// One play session: recorded in the local play-sessions table, or (for play before
/// the table existed) derived from the checkpoints one player session wrote.
public struct PlaySession: Hashable, Sendable, Identifiable {
    public let id: String
    public let romID: Int
    public let deviceID: String
    public let startedAt: Date
    public let endedAt: Date
    /// Recorded: active (unpaused, foreground) play. Derived: time between
    /// consecutive checkpoints, each gap counted up to the continuity window.
    public let playtime: TimeInterval
    /// How a recorded session ended; nil for derived sessions and open ones.
    public let endReason: PlaySessionEndReason?
    /// From the play-sessions table (false: estimated from checkpoints).
    public let isRecorded: Bool

    public init(id: String, romID: Int, deviceID: String, startedAt: Date, endedAt: Date, playtime: TimeInterval,
                endReason: PlaySessionEndReason? = nil, isRecorded: Bool = false) {
        self.id = id
        self.romID = romID
        self.deviceID = deviceID
        self.startedAt = startedAt
        self.endedAt = endedAt
        self.playtime = playtime
        self.endReason = endReason
        self.isRecorded = isRecorded
    }
}

/// Playtime for one game across its sessions.
public struct GamePlaytime: Hashable, Sendable, Identifiable {
    public var id: Int { romID }
    public let romID: Int
    public let playtime: TimeInterval
    public let sessionCount: Int
    public let lastPlayedAt: Date

    public init(romID: Int, playtime: TimeInterval, sessionCount: Int, lastPlayedAt: Date) {
        self.romID = romID
        self.playtime = playtime
        self.sessionCount = sessionCount
        self.lastPlayedAt = lastPlayedAt
    }
}

public struct PlayStats: Hashable, Sendable {
    public let totalPlaytime: TimeInterval
    /// Newest first.
    public let sessions: [PlaySession]
    /// Most played first.
    public let games: [GamePlaytime]
    public let lastPlayedAt: Date?
    /// Consecutive days with play, ending today (or yesterday when today has none yet).
    public let currentStreakDays: Int

    public var sessionCount: Int { sessions.count }
    public var isEmpty: Bool { sessions.isEmpty }
    /// Some of the play shown was estimated from automatic saves (before sessions were recorded).
    public var includesEstimates: Bool { sessions.contains { !$0.isRecorded } }

    public init(totalPlaytime: TimeInterval, sessions: [PlaySession], games: [GamePlaytime], lastPlayedAt: Date?, currentStreakDays: Int) {
        self.totalPlaytime = totalPlaytime
        self.sessions = sessions
        self.games = games
        self.lastPlayedAt = lastPlayedAt
        self.currentStreakDays = currentStreakDays
    }

    public static let empty = PlayStats(totalPlaytime: 0, sessions: [], games: [], lastPlayedAt: nil, currentStreakDays: 0)
}

/// Stats v1: sessions derived from the progress checkpoints this device already
/// records, until a play-sessions table exists (theme plan Phase 4).
public enum PlayStatsDerivation {
    /// iOS captures an automatic resume every 120 s of foreground play, so a gap
    /// between consecutive checkpoints of one session counts in full up to 120 s.
    /// A longer gap (paused, backgrounded) counts as at most the window and does
    /// not start a new session. (Android uses 30 s, its capture interval.)
    public static let continuityWindow: TimeInterval = 120

    public static func derive(
        from samples: [PlayCheckpointSample],
        deviceID: String? = nil,
        now: Date = Date(),
        calendar: Calendar = .current,
        continuityWindow: TimeInterval = continuityWindow
    ) -> PlayStats {
        // Imports carry restored history, not play on this device.
        let relevant = samples.filter { sample in
            sample.captureReason != "import" && (deviceID == nil || sample.deviceID == deviceID)
        }
        let grouped = Dictionary(grouping: relevant) { SessionKey(sessionID: $0.sessionID, romID: $0.romID) }
        let sessions: [PlaySession] = grouped.map { key, group in
            let times = group.map(\.createdAt).sorted()
            var playtime: TimeInterval = 0
            for (earlier, later) in zip(times, times.dropFirst()) {
                playtime += min(later.timeIntervalSince(earlier), continuityWindow)
            }
            return PlaySession(id: key.sessionID, romID: key.romID, deviceID: group[0].deviceID,
                               startedAt: times.first!, endedAt: times.last!, playtime: playtime)
        }
        .sorted { ($0.endedAt, $0.id) > ($1.endedAt, $1.id) }

        let games: [GamePlaytime] = Dictionary(grouping: sessions, by: \.romID).map { romID, group in
            GamePlaytime(romID: romID, playtime: group.reduce(0) { $0 + $1.playtime }, sessionCount: group.count,
                         lastPlayedAt: group.map(\.endedAt).max()!)
        }
        .sorted { ($0.playtime, $0.lastPlayedAt) > ($1.playtime, $1.lastPlayedAt) }

        let playDays = Set(relevant.map { calendar.startOfDay(for: $0.createdAt) })
        var day = calendar.startOfDay(for: now)
        if !playDays.contains(day), let yesterday = calendar.date(byAdding: .day, value: -1, to: day) { day = yesterday }
        var streak = 0
        while playDays.contains(day) {
            streak += 1
            guard let previous = calendar.date(byAdding: .day, value: -1, to: day) else { break }
            day = previous
        }

        return PlayStats(
            totalPlaytime: sessions.reduce(0) { $0 + $1.playtime },
            sessions: sessions,
            games: games,
            lastPlayedAt: sessions.first?.endedAt,
            currentStreakDays: streak
        )
    }

    /// A recorded row as a Stats session; rows with no active play are not play. An
    /// open row ends at its heartbeat.
    public static func session(from row: PlaySessionRecord) -> PlaySession? {
        guard row.activeDurationMs > 0 else { return nil }
        let end = row.endedAtEpochMs ?? max(row.lastHeartbeatEpochMs, row.startedAtEpochMs)
        return PlaySession(id: row.sessionId, romID: Int(row.romId), deviceID: row.deviceId,
                           startedAt: Date(timeIntervalSince1970: TimeInterval(row.startedAtEpochMs) / 1000),
                           endedAt: Date(timeIntervalSince1970: TimeInterval(end) / 1000),
                           playtime: TimeInterval(row.activeDurationMs) / 1000,
                           endReason: row.reason, isRecorded: true)
    }

    /// Merge rule (docs/play-data-local.md §1): recorded sessions are authoritative.
    /// Checkpoint-derived sessions fill in only play from before the first recorded
    /// session on this device: a derived session counts when its session id has no
    /// recorded row and it started before the earliest recorded start (the "table
    /// epoch"). With no recorded rows, Stats are fully derived, exactly as v1.
    public static func merge(
        recorded: [PlaySessionRecord],
        samples: [PlayCheckpointSample],
        deviceID: String? = nil,
        now: Date = Date(),
        calendar: Calendar = .current,
        continuityWindow: TimeInterval = continuityWindow
    ) -> PlayStats {
        let rows = recorded.filter { deviceID == nil || $0.deviceId == deviceID }
        let derived = derive(from: samples, deviceID: deviceID, now: now, calendar: calendar, continuityWindow: continuityWindow)
        guard let epochMs = rows.map(\.startedAtEpochMs).min() else { return derived }
        let epoch = Date(timeIntervalSince1970: TimeInterval(epochMs) / 1000)
        let recordedIDs = Set(rows.map(\.sessionId))
        let earlier = derived.sessions.filter { !recordedIDs.contains($0.id) && $0.startedAt < epoch }
        return summarize(rows.compactMap(session(from:)) + earlier, now: now, calendar: calendar)
    }

    /// Totals, per-game totals, last played and the streak (consecutive local days
    /// with play, from each session's start and end day, ending today or yesterday).
    public static func summarize(_ sessions: [PlaySession], now: Date = Date(), calendar: Calendar = .current) -> PlayStats {
        let ordered = sessions.sorted { ($0.endedAt, $0.id) > ($1.endedAt, $1.id) }
        let games: [GamePlaytime] = Dictionary(grouping: ordered, by: \.romID).map { romID, group in
            GamePlaytime(romID: romID, playtime: group.reduce(0) { $0 + $1.playtime }, sessionCount: group.count,
                         lastPlayedAt: group.map(\.endedAt).max()!)
        }
        .sorted { ($0.playtime, $0.lastPlayedAt, -$0.romID) > ($1.playtime, $1.lastPlayedAt, -$1.romID) }
        let playDays = Set(ordered.flatMap { [calendar.startOfDay(for: $0.startedAt), calendar.startOfDay(for: $0.endedAt)] })
        var day = calendar.startOfDay(for: now)
        if !playDays.contains(day), let yesterday = calendar.date(byAdding: .day, value: -1, to: day) { day = yesterday }
        var streak = 0
        while playDays.contains(day) {
            streak += 1
            guard let previous = calendar.date(byAdding: .day, value: -1, to: day) else { break }
            day = previous
        }
        return PlayStats(totalPlaytime: ordered.reduce(0) { $0 + $1.playtime }, sessions: ordered, games: games,
                         lastPlayedAt: ordered.map(\.endedAt).max(), currentStreakDays: streak)
    }

    private struct SessionKey: Hashable {
        let sessionID: String
        let romID: Int
    }
}
