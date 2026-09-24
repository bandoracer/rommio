import Foundation
import XCTest
@testable import RommioFoundation

/// Stats merge rule (docs/play-data-local.md §1): recorded sessions are
/// authoritative; derived sessions only for play before the table epoch.
final class PlayStatsMergeTests: XCTestCase {
    private let device = "d"
    private var calendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar
    }()
    private let day: Int64 = 86_400_000
    private let base: Int64 = 1_790_000_000_000 // Sep 2026

    private func row(_ id: String, rom: Int64 = 1, start: Int64, end: Int64?, heartbeat: Int64? = nil, active: Int64, reason: PlaySessionEndReason? = .resume, device: String = "d") -> PlaySessionRecord {
        PlaySessionRecord(sessionId: id, namespaceId: "n", contentId: "c", serverKey: "s", accountId: "1", romId: rom, fileId: nil, platformSlug: "nes",
                          deviceId: device, coreId: "fceumm", startedAtEpochMs: start, endedAtEpochMs: end, lastHeartbeatEpochMs: heartbeat ?? end ?? start,
                          activeDurationMs: active, endReason: end == nil ? nil : reason?.rawValue)
    }

    private func samples(_ session: String, rom: Int = 1, from start: Int64, count: Int, every: Int64) -> [PlayCheckpointSample] {
        (0..<count).map { PlayCheckpointSample(romID: rom, sessionID: session, deviceID: device,
                                               createdAt: Date(timeIntervalSince1970: TimeInterval(start + Int64($0) * every) / 1000)) }
    }

    func testRecordedSessionsAreAuthoritativeAndOnlyEarlierPlayIsDerived() {
        let now = Date(timeIntervalSince1970: TimeInterval(base + 3 * day) / 1000)
        // Before the table existed: a derived session two days earlier (4 captures, 120 s apart = 6 min).
        let before = samples("old", from: base, count: 4, every: 120_000)
        // A recorded session also has checkpoints; they are not double-counted.
        let recordedSamples = samples("rec", from: base + 2 * day, count: 3, every: 120_000)
        // A checkpoint-only session after the epoch (e.g. recorded row lost) is not derived.
        let after = samples("late", from: base + 2 * day + 3_600_000, count: 3, every: 60_000)
        let rows = [
            row("rec", start: base + 2 * day, end: base + 2 * day + 600_000, active: 420_000, reason: .state),
            row("open", rom: 2, start: base + 3 * day - 60_000, end: nil, heartbeat: base + 3 * day - 30_000, active: 25_000),
            row("idle", start: base + 2 * day + 10, end: base + 2 * day + 20, active: 0, reason: .quit),
            row("peer", start: base + day, end: base + day + 10_000, active: 9_000, device: "other"),
        ]
        let stats = PlayStatsDerivation.merge(recorded: rows, samples: before + recordedSamples + after, deviceID: device, now: now, calendar: calendar)
        XCTAssertEqual(stats.sessions.map(\.id), ["open", "rec", "old"])
        XCTAssertEqual(stats.totalPlaytime, 420 + 25 + 360, accuracy: 0.001)
        XCTAssertTrue(stats.includesEstimates)
        let rec = stats.sessions.first { $0.id == "rec" }!
        XCTAssertTrue(rec.isRecorded); XCTAssertEqual(rec.endReason, .state); XCTAssertEqual(rec.playtime, 420)
        let open = stats.sessions.first { $0.id == "open" }!
        XCTAssertNil(open.endReason)
        XCTAssertEqual(open.endedAt, Date(timeIntervalSince1970: TimeInterval(base + 3 * day - 30_000) / 1000), "an open row ends at its heartbeat")
        XCTAssertFalse(stats.sessions.first { $0.id == "old" }!.isRecorded)
        XCTAssertEqual(stats.games.map(\.romID), [1, 2])
        XCTAssertEqual(stats.games.first?.sessionCount, 2)
        XCTAssertEqual(stats.currentStreakDays, 2, "days with play: base, +2, +3 (today); base+1 missing")
        XCTAssertEqual(stats.lastPlayedAt, open.endedAt)
    }

    func testWithoutRecordedRowsStatsAreExactlyTheV1Derivation() {
        let now = Date(timeIntervalSince1970: TimeInterval(base + day) / 1000)
        let all = samples("a", from: base, count: 5, every: 30_000) + samples("b", rom: 2, from: base + 3_600_000, count: 2, every: 500_000)
        XCTAssertEqual(PlayStatsDerivation.merge(recorded: [], samples: all, deviceID: device, now: now, calendar: calendar),
                       PlayStatsDerivation.derive(from: all, deviceID: device, now: now, calendar: calendar))
        let onlyRecorded = PlayStatsDerivation.merge(recorded: [row("r", start: base, end: base + 60_000, active: 50_000)], samples: [], deviceID: device, now: now, calendar: calendar)
        XCTAssertFalse(onlyRecorded.includesEstimates)
        XCTAssertEqual(onlyRecorded.sessionCount, 1)
    }
}
