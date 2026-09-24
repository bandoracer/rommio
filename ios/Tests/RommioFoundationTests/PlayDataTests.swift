import CoreGraphics
import Foundation
import GRDB
import ImageIO
import XCTest
import RommioContract
@testable import RommioFoundation

/// Local play data (docs/play-data-local.md): tables and migration, session rules,
/// the recorder, previews, names, device labels and the saves reader's pure rules.
final class PlayDataTests: XCTestCase {
    private var root: URL!

    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory.appending(path: "rommio-playdata-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: root)
        super.tearDown()
    }

    private static let ns = "11111111-1111-4111-8111-111111111111"
    private static let content = "22222222-2222-4222-8222-222222222222"
    private static let device = "33333333-3333-4333-8333-333333333333"

    private func uuid() -> String { UUID().uuidString.lowercased() }

    private func identity(romId: Int64 = 7) -> ProgressIdentity {
        ProgressIdentity(namespaceId: Self.ns, contentId: Self.content, binding: ProgressBinding(serverKey: "http://romm.local", accountId: "1", romId: romId,
            files: [ProgressContentFile(fileId: 70, discIndex: 0, contentSha256: String(repeating: "a", count: 64), containerSha256: nil, memberPath: nil)]))
    }

    private func checkpoint(_ reason: String, state: Bool, id: String? = nil) -> ProgressCheckpoint {
        ProgressCheckpoint(schemaVersion: 3, checkpointId: id ?? uuid(), binding: identity().binding,
                           saveCompatibility: ProgressSaveCompatibility(system: "nes", format: "libretro-memory", layoutVersion: "1", coreId: "fceumm", allowsEmpty: false),
                           stateCompatibility: state ? ProgressStateCompatibility(coreId: "fceumm", coreSha256: String(repeating: "b", count: 64), stateFormat: "fceumm", platform: "ios", architecture: "arm64", options: [:], systemFiles: [:]) : nil,
                           saveDataComplete: true, artifacts: [], deviceId: Self.device, sessionId: uuid(), captureReason: reason, createdAtEpochMs: 1_000)
    }

    private func image(width: Int, height: Int) -> CGImage {
        let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                                bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
        context.setFillColor(red: 0.2, green: 0.6, blue: 0.9, alpha: 1)
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        return context.makeImage()!
    }

    /// A clock the test moves by hand.
    private final class ManualClock: @unchecked Sendable {
        private let lock = NSLock()
        private var value: Int64
        init(_ value: Int64) { self.value = value }
        var now: Int64 { lock.withLock { value } }
        func advance(_ ms: Int64) { lock.withLock { value += ms } }
    }

    // MARK: Migration

    func testMigrationFromVersion6AddsPlayDataTablesAndKeepsExistingData() throws {
        let path = root.appending(path: "Database")
        try FileManager.default.createDirectory(at: path, withIntermediateDirectories: true)
        let queue = try DatabaseQueue(path: path.appending(path: "rommio.sqlite").path)
        try AppDatabase.migrator.migrate(queue, upTo: "v6_progress_import")
        try queue.write { db in
            try db.execute(sql: "INSERT INTO cached_roms VALUES ('p', 7, 3, ?, 1)", arguments: [Data(#"{"id":7,"name":"Kept"}"#.utf8)])
            try db.execute(sql: "INSERT INTO progress_v2_namespaces VALUES (?, 'http://romm.local', '1')", arguments: [Self.ns])
        }
        let tablesBefore = try queue.read { try String.fetchAll($0, sql: "SELECT name FROM sqlite_master WHERE type = 'table'") }
        XCTAssertFalse(tablesBefore.contains("play_sessions"))
        try queue.close()

        let database = try AppDatabase(rootDirectory: path)
        let columns = try database.read { db in try db.columns(in: "play_sessions").map(\.name) }
        XCTAssertEqual(columns, ["sessionId", "namespaceId", "contentId", "serverKey", "accountId", "romId", "fileId", "platformSlug", "deviceId",
                                 "coreId", "startedAtEpochMs", "endedAtEpochMs", "lastHeartbeatEpochMs", "activeDurationMs", "endReason"])
        let labelColumns = try database.read { db in try db.columns(in: "save_state_labels").map(\.name) }
        XCTAssertEqual(labelColumns, ["namespaceId", "contentId", "checkpointId", "label", "updatedAtEpochMs"])
        let indexes = try database.read { db in try db.indexes(on: "play_sessions").map(\.name) }
        XCTAssertTrue(indexes.contains("index_play_sessions_serverKey_accountId_startedAtEpochMs"))
        let kept = try database.read { try Int.fetchOne($0, sql: "SELECT COUNT(*) FROM cached_roms WHERE rom_id = 7") }
        XCTAssertEqual(kept, 1)
        let namespace = try database.read { try String.fetchOne($0, sql: "SELECT namespaceId FROM progress_v2_namespaces") }
        XCTAssertEqual(namespace, Self.ns)

        let store = PlayDataStore(database: database)
        try store.insert(PlaySessionRecord(sessionId: uuid(), namespaceId: Self.ns, contentId: Self.content, serverKey: "http://romm.local", accountId: "1",
                                           romId: 7, fileId: 70, platformSlug: "nes", deviceId: Self.device, coreId: "fceumm", startedAtEpochMs: 10, lastHeartbeatEpochMs: 10))
        XCTAssertEqual(try store.openSessions().count, 1)
    }

    // MARK: Session rules

    func testEndReasonRules() {
        XCTAssertEqual(PlaySessionRules.reasonForExit(exitUpdatedResume: true, activeMsSinceManualState: nil), .resume)
        XCTAssertEqual(PlaySessionRules.reasonForExit(exitUpdatedResume: false, activeMsSinceManualState: nil), .quit)
        XCTAssertEqual(PlaySessionRules.reasonForExit(exitUpdatedResume: true, activeMsSinceManualState: 4_999), .state)
        XCTAssertEqual(PlaySessionRules.reasonForExit(exitUpdatedResume: false, activeMsSinceManualState: 0), .state)
        XCTAssertEqual(PlaySessionRules.reasonForExit(exitUpdatedResume: true, activeMsSinceManualState: 5_000), .resume)
        XCTAssertEqual(PlaySessionRules.reasonForExit(exitUpdatedResume: false, activeMsSinceManualState: 60_000), .quit)
        XCTAssertEqual(PlaySessionEndReason.allCases.map(\.rawValue), ["resume", "state", "quit", "background"])
        XCTAssertEqual(PlaySessionEndReason.background.title, "Ended in the background")
    }

    func testAbandonedSessionsCloseAtTheirLastHeartbeatWithClampedActiveTime() {
        XCTAssertEqual(PlaySessionRules.closeAbandoned(startedAtEpochMs: 1_000, lastHeartbeatEpochMs: 61_000, activeDurationMs: 45_000),
                       .init(endedAtEpochMs: 61_000, activeDurationMs: 45_000, reason: .background))
        XCTAssertEqual(PlaySessionRules.closeAbandoned(startedAtEpochMs: 1_000, lastHeartbeatEpochMs: 11_000, activeDurationMs: 45_000).activeDurationMs, 10_000,
                       "never more active time than the span")
        XCTAssertEqual(PlaySessionRules.closeAbandoned(startedAtEpochMs: 5_000, lastHeartbeatEpochMs: 1_000, activeDurationMs: 3).endedAtEpochMs, 5_000,
                       "never before it started")
    }

    func testActivePlayClockCountsRunningTimeOnly() {
        let clock = ManualClock(0)
        let active = ActivePlayClock(now: { clock.now })
        clock.advance(4_000)                     // loading, not running
        active.setRunning(true); clock.advance(10_000)
        active.setRunning(false); clock.advance(30_000) // paused
        XCTAssertEqual(active.activeMs, 10_000)
        XCTAssertNil(active.activeSinceManualState)
        active.setRunning(true); clock.advance(2_000)
        active.markManualState(); clock.advance(3_000)
        XCTAssertEqual(active.activeMs, 15_000)
        XCTAssertEqual(active.activeSinceManualState, 3_000)
        active.setRunning(true); clock.advance(1_000)    // repeated running changes nothing
        XCTAssertEqual(active.activeMs, 16_000)
    }

    // MARK: Recorder

    private func recorder(wall: ManualClock, mono: ManualClock) -> LocalPlayData {
        LocalPlayData(store: PlayDataStore(database: try! AppDatabase(rootDirectory: root.appending(path: "Database"))),
                      thumbnails: SaveThumbnailStore(root: root.appending(path: "previews")),
                      wallClock: { wall.now }, monotonic: { mono.now })
    }

    private func start(_ sessionId: String) -> PlaySessionStart {
        PlaySessionStart(sessionId: sessionId, identity: identity(), deviceId: Self.device, platformSlug: "nes", coreId: "fceumm")
    }

    func testRecorderWritesActiveTimeHeartbeatsAndEndReasons() async throws {
        let wall = ManualClock(1_700_000_000_000), mono = ManualClock(0)
        let playData = recorder(wall: wall, mono: mono)
        // Left with a resume point after 15 s of active play (paused time excluded).
        let resumeId = uuid()
        let session = playData.begin(start(resumeId))
        await playData.flush()
        var row = try XCTUnwrap(try playData.store.session(resumeId))
        XCTAssertNil(row.endedAtEpochMs); XCTAssertEqual(row.activeDurationMs, 0)
        XCTAssertEqual(row.romId, 7); XCTAssertEqual(row.fileId, 70); XCTAssertEqual(row.platformSlug, "nes"); XCTAssertEqual(row.coreId, "fceumm")
        XCTAssertEqual(row.serverKey, "http://romm.local"); XCTAssertEqual(row.accountId, "1")
        session.phase(.running); mono.advance(10_000); wall.advance(10_000)
        session.phase(.paused); mono.advance(60_000); wall.advance(60_000)
        await playData.flush()
        row = try XCTUnwrap(try playData.store.session(resumeId))
        XCTAssertEqual(row.activeDurationMs, 10_000, "heartbeat on every running/paused transition")
        XCTAssertEqual(row.lastHeartbeatEpochMs, 1_700_000_010_000)
        session.phase(.running); mono.advance(5_000); wall.advance(5_000)
        session.committed(checkpoint("periodic", state: true))
        await playData.flush()
        XCTAssertEqual(try playData.store.session(resumeId)?.activeDurationMs, 15_000, "heartbeat after a committed state capture")
        session.phase(.closed(exitUpdatedResume: true))
        session.phase(.closed(exitUpdatedResume: false))  // a finished row is immutable
        await playData.flush()
        row = try XCTUnwrap(try playData.store.session(resumeId))
        XCTAssertEqual(row.reason, .resume); XCTAssertEqual(row.activeDurationMs, 15_000); XCTAssertEqual(row.endedAtEpochMs, 1_700_000_075_000)

        // A manual save state and under 5 s of play after it: "state".
        let stateId = uuid()
        let second = playData.begin(start(stateId))
        second.phase(.running); mono.advance(20_000)
        second.committed(checkpoint("manual", state: true)); mono.advance(4_000)
        second.phase(.closed(exitUpdatedResume: true))
        // Played on after the save state: "resume" again.
        let onId = uuid()
        let third = playData.begin(start(onId))
        third.phase(.running)
        third.committed(checkpoint("manual", state: true)); mono.advance(6_000)
        third.phase(.closed(exitUpdatedResume: true))
        // No new resume point, or the core stopped: "quit".
        let quitId = uuid(), stoppedId = uuid()
        let fourth = playData.begin(start(quitId)); fourth.phase(.running); mono.advance(1_000); fourth.phase(.closed(exitUpdatedResume: false))
        let fifth = playData.begin(start(stoppedId)); fifth.phase(.stopped)
        await playData.flush()
        XCTAssertEqual(try playData.store.session(stateId)?.reason, .state)
        XCTAssertEqual(try playData.store.session(stateId)?.activeDurationMs, 24_000)
        XCTAssertEqual(try playData.store.session(onId)?.reason, .resume)
        XCTAssertEqual(try playData.store.session(quitId)?.reason, .quit)
        XCTAssertEqual(try playData.store.session(stoppedId)?.reason, .quit)
        XCTAssertEqual(try playData.store.session(stoppedId)?.activeDurationMs, 0, "a failed start closes as quit with 0 ms")
        XCTAssertEqual(try playData.store.sessions(serverKey: "http://romm.local", accountId: "1").count, 5)
        XCTAssertEqual(try playData.store.sessions(serverKey: "http://romm.local", accountId: "2").count, 0)
    }

    func testProcessDeathClosesOpenSessionsAtNextLaunchAsBackground() async throws {
        let wall = ManualClock(1_700_000_000_000), mono = ManualClock(0)
        let first = recorder(wall: wall, mono: mono)
        let id = uuid()
        let session = first.begin(start(id))
        session.phase(.running); mono.advance(30_000); wall.advance(30_000)
        session.phase(.paused)        // heartbeat at +30 s with 30 s active
        session.phase(.running); mono.advance(20_000); wall.advance(20_000)
        await first.flush()           // the process dies here: no end, no further heartbeat
        XCTAssertNil(try first.store.session(id)?.endedAtEpochMs)

        let next = recorder(wall: wall, mono: mono)
        await next.flush()
        let row = try XCTUnwrap(try next.store.session(id))
        XCTAssertEqual(row.reason, .background)
        XCTAssertEqual(row.endedAtEpochMs, 1_700_000_030_000, "ends at the last heartbeat")
        XCTAssertEqual(row.activeDurationMs, 30_000, "active time recorded at that heartbeat")
        // A live session of this process is never closed as abandoned.
        let liveId = uuid()
        _ = next.begin(start(liveId))
        next.closeAbandoned()
        await next.flush()
        XCTAssertNil(try next.store.session(liveId)?.endedAtEpochMs)
    }

    // MARK: Previews

    func testThumbnailSizingKeepsAspectNeverUpscalesAndKeepsHeight() {
        XCTAssertEqual(ThumbnailSizing.target(width: 256, height: 240)?.width, 256)
        XCTAssertEqual(ThumbnailSizing.target(width: 256, height: 240)?.height, 240)
        XCTAssertEqual(ThumbnailSizing.target(width: 640, height: 480).map { [$0.width, $0.height] }, [320, 240])
        XCTAssertEqual(ThumbnailSizing.target(width: 256, height: 384).map { [$0.width, $0.height] }, [256, 384], "DS screens stay as they are")
        XCTAssertEqual(ThumbnailSizing.target(width: 4_000, height: 3).map { [$0.width, $0.height] }, [320, 1], "height at least 1")
        XCTAssertNil(ThumbnailSizing.target(width: 0, height: 10))
        let scaled = ThumbnailSizing.scaled(image(width: 1_280, height: 720))
        XCTAssertEqual(scaled?.width, 320); XCTAssertEqual(scaled?.height, 180)
    }

    func testPreviewsAreKeyedByCheckpointWrittenOnlyForStatesAndCopiedOnRestore() async throws {
        let wall = ManualClock(1_700_000_000_000), mono = ManualClock(0)
        let playData = recorder(wall: wall, mono: mono)
        let session = playData.begin(start(uuid()))
        let frame = image(width: 640, height: 480)
        // Game-save data only: no preview.
        let game = checkpoint("periodic", state: false)
        session.captureStarting(includeState: true, frame: PlayDataFrame { frame })
        session.committed(game)
        let state = checkpoint("periodic", state: true)
        session.committed(state)
        // A failed frame never affects anything.
        session.captureStarting(includeState: true, frame: PlayDataFrame { nil })
        let failed = checkpoint("pause", state: true)
        session.committed(failed)
        // A restore capture takes the source's preview.
        session.captureStarting(includeState: true, frame: nil)
        let restored = checkpoint("restore", state: true)
        session.committed(restored)
        session.restored(sourceCheckpointId: state.checkpointId, result: restored)
        await playData.flush()

        let key = { (cp: ProgressCheckpoint) in SaveKey(namespaceId: Self.ns, contentId: Self.content, checkpointId: cp.checkpointId) }
        let thumbnails = playData.thumbnails
        XCTAssertEqual(thumbnails.file(key(state))?.path, root.appending(path: "previews/\(Self.ns)/\(Self.content)/\(state.checkpointId).jpg").path)
        let written = try XCTUnwrap(thumbnails.urlIfPresent(key(state)))
        let source = try XCTUnwrap(CGImageSourceCreateWithURL(written as CFURL, nil))
        XCTAssertEqual(CGImageSourceGetType(source) as String?, "public.jpeg")
        let decoded = try XCTUnwrap(CGImageSourceCreateImageAtIndex(source, 0, nil))
        XCTAssertEqual([decoded.width, decoded.height], [320, 240])
        XCTAssertNil(thumbnails.urlIfPresent(key(game)), "game-save data has no preview")
        XCTAssertNil(thumbnails.urlIfPresent(key(failed)), "a failed frame leaves no preview")
        let copy = try XCTUnwrap(thumbnails.urlIfPresent(key(restored)))
        XCTAssertEqual(try Data(contentsOf: copy), try Data(contentsOf: written))
        XCTAssertNil(thumbnails.file(SaveKey(namespaceId: "../x", contentId: Self.content, checkpointId: state.checkpointId)), "ids must be UUIDs")
    }

    func testPruningKeepsYoungFilesAndRetainedCheckpoints() throws {
        let thumbnails = SaveThumbnailStore(root: root.appending(path: "previews"))
        let kept = SaveKey(namespaceId: Self.ns, contentId: Self.content, checkpointId: uuid())
        let gone = SaveKey(namespaceId: Self.ns, contentId: Self.content, checkpointId: uuid())
        let young = SaveKey(namespaceId: Self.ns, contentId: Self.content, checkpointId: uuid())
        for key in [kept, gone, young] { try thumbnails.write(key, image: image(width: 64, height: 48)) }
        let old = Date().addingTimeInterval(-3_600)
        for key in [kept, gone] { try FileManager.default.setAttributes([.modificationDate: old], ofItemAtPath: thumbnails.file(key)!.path) }
        let staging = thumbnails.file(kept)!.deletingLastPathComponent().appending(path: ".staging-leftover")
        try Data([1]).write(to: staging)
        try FileManager.default.setAttributes([.modificationDate: old], ofItemAtPath: staging.path)

        XCTAssertEqual(thumbnails.prune(namespaceId: Self.ns, contentId: Self.content, keep: [kept.checkpointId]), 2)
        XCTAssertNotNil(thumbnails.urlIfPresent(kept))
        XCTAssertNil(thumbnails.urlIfPresent(gone))
        XCTAssertNotNil(thumbnails.urlIfPresent(young), "files younger than 10 minutes are never pruned")
        XCTAssertFalse(FileManager.default.fileExists(atPath: staging.path))

        // After an import, identities that no longer exist lose their old previews.
        XCTAssertEqual(thumbnails.pruneIdentities(keeping: ["\(Self.ns)/\(Self.content)"]), 0)
        XCTAssertEqual(thumbnails.pruneIdentities(keeping: []), 1, "only the old file; the young one stays")
        XCTAssertNotNil(thumbnails.urlIfPresent(young))
    }

    // MARK: Names

    func testSaveStateNamesAreNormalisedAndEmptyRemovesThem() throws {
        XCTAssertEqual(SavedProgressRules.normalizeLabel("  Before   the\tboss  "), "Before the boss")
        XCTAssertEqual(SavedProgressRules.normalizeLabel(String(repeating: "x", count: 70))?.count, 60)
        XCTAssertNil(SavedProgressRules.normalizeLabel("   "))
        let store = PlayDataStore(database: try AppDatabase(rootDirectory: root.appending(path: "Database")))
        let key = SaveKey(namespaceId: Self.ns, contentId: Self.content, checkpointId: uuid())
        XCTAssertEqual(try store.setLabel(key, raw: " Before  the boss "), "Before the boss")
        XCTAssertEqual(try store.labels(namespaceId: Self.ns, contentId: Self.content)[key], "Before the boss")
        XCTAssertEqual(try store.setLabel(key, raw: "Boss door"), "Boss door")
        XCTAssertEqual(try store.labels(namespaceId: Self.ns, contentId: Self.content).count, 1)
        XCTAssertNil(try store.setLabel(key, raw: ""))
        XCTAssertTrue(try store.labels(namespaceId: Self.ns, contentId: Self.content).isEmpty)
    }

    // MARK: Device labels

    func testDeviceLabels() {
        XCTAssertEqual(DeviceFormFactor.resolve(isPhone: true, isPad: false, screenShortSide: 402, runsOnMac: false), .phone)
        XCTAssertEqual(DeviceFormFactor.resolve(isPhone: true, isPad: false, screenShortSide: 466, runsOnMac: false), .unknown, "iPhone Duo")
        XCTAssertEqual(DeviceFormFactor.resolve(isPhone: false, isPad: true, screenShortSide: 820, runsOnMac: false), .tablet)
        XCTAssertEqual(DeviceFormFactor.resolve(isPhone: false, isPad: true, screenShortSide: 820, runsOnMac: true), .unknown, "Mac")
        XCTAssertEqual(DeviceFormFactor.phone.thisDeviceLabel, "This iPhone")
        XCTAssertEqual(DeviceFormFactor.tablet.thisDeviceLabel, "This iPad")
        XCTAssertEqual(DeviceFormFactor.unknown.thisDeviceLabel, "This device")
        XCTAssertEqual(SavedProgressRules.deviceLabel(checkpointDeviceId: "a", localDeviceId: "a", formFactor: .phone), "This iPhone")
        XCTAssertEqual(SavedProgressRules.deviceLabel(checkpointDeviceId: "a", localDeviceId: "b", formFactor: .phone), "Another device")
        XCTAssertEqual(SavedProgressRules.deviceLabel(checkpointDeviceId: "a", localDeviceId: nil, formFactor: .tablet), "Another device")
    }

    // MARK: Saves reader rules

    private func stored(_ reason: String, state: Bool, manual: Bool = false, at: Int64, refs: Set<String> = [], conflicted: Set<String> = [],
                        outbox: String? = "PENDING", device: String = PlayDataTests.device, contentId: String = PlayDataTests.content) -> StoredCheckpoint {
        StoredCheckpoint(key: SaveKey(namespaceId: Self.ns, contentId: contentId, checkpointId: uuid()), romId: 7, fileId: 70, operationId: uuid(),
                         capturedAtEpochMs: at, captureReason: reason, deviceId: device, hasState: state, manual: manual, retained: true,
                         currentReferences: refs, conflictedReferences: conflicted, outboxStatus: outbox)
    }

    func testReaderRulesClassifyNameSlotAndSyncEachSave() {
        XCTAssertEqual(SavedProgressRules.nameUUID(Data("rommio-slot-1".utf8)), "f363ea84-8c1c-31c5-a30a-cd5771de7078", "Java UUID.nameUUIDFromBytes")
        XCTAssertEqual(SavedProgressRules.quickSlots["be6ef519-5e6f-353d-a830-bea99f76c6ea"], 3)
        let slot = stored("manual", state: true, manual: true, at: 3_000, refs: ["state/slot/a466461c-f75f-32fb-9ebd-959e2d4f5df7"])
        let labels = [slot.key: "Before the boss"]
        let entry = SavedProgressRules.entry(slot, labels: labels, localDeviceId: Self.device, formFactor: .phone) { _ in nil }
        XCTAssertEqual(entry.kind, .saveState)
        XCTAssertEqual(entry.slotNumber, 2)
        XCTAssertEqual(entry.kindTitle, "Save state · Slot 2")
        XCTAssertEqual(entry.title, "Before the boss")
        XCTAssertEqual(entry.deviceLabel, "This iPhone")
        XCTAssertEqual(entry.sync, .waiting)
        XCTAssertTrue(entry.isCurrentSlot); XCTAssertTrue(entry.loadable)
        let game = SavedProgressRules.entry(stored("periodic", state: false, at: 1), labels: [:], localDeviceId: Self.device, formFactor: .phone) { _ in URL(fileURLWithPath: "/x.jpg") }
        XCTAssertEqual(game.kind, .gameSave); XCTAssertNil(game.thumbnailURL, "game-save data never shows a preview"); XCTAssertFalse(game.loadable)
        XCTAssertEqual(SavedProgressRules.kind(hasState: true, manual: false), .resumePoint)
        let mapping: [(String?, Bool, SaveSyncStatus, String)] = [("PUBLISHED", false, .synced, "Synced"), ("LEASED", false, .syncing, "Syncing"),
            ("PENDING", false, .waiting, "Waiting to sync"), ("LOCAL_ONLY", false, .localOnly, "Local only"), ("PARKED_AUTH", false, .signInNeeded, "Sign-in needed"),
            ("PUBLISHED", true, .conflict, "Conflict"), (nil, false, .unknown, "")]
        for (outbox, conflicted, status, copy) in mapping {
            XCTAssertEqual(SavedProgressRules.syncStatus(outboxStatus: outbox, conflicted: conflicted), status)
            XCTAssertEqual(status.label, copy)
        }
        XCTAssertEqual(["manual", "periodic", "pause", "background", "exit", "safety", "restore", "import", "odd"].map(SavedProgressRules.reasonLabel),
                       ["Manual", "Automatic", "Paused", "App in background", "Left game", "Safety copy", "Loaded state", "Imported", "Odd"])
    }

    func testRecentSavesAndOneGameGrouping() {
        let resumeRef = "state/resume/core-a"
        let resume = stored("exit", state: true, at: 9_000, refs: [resumeRef])
        let earlier = stored("pause", state: true, at: 8_000)
        let manual = stored("manual", state: true, manual: true, at: 7_000, refs: ["state/slot/x"])
        let otherDevice = stored("manual", state: true, manual: true, at: 6_000, device: "44444444-4444-4444-8444-444444444444")
        let gameSave = stored("exit", state: false, at: 9_000, refs: ["game/current"])
        let entries = [earlier, gameSave, manual, resume, otherDevice].map {
            SavedProgressRules.entry($0, labels: [:], localDeviceId: Self.device, formFactor: .phone) { _ in nil }
        }
        let recent = SavedProgressRules.recent(entries, limit: 24)
        XCTAssertEqual(recent.map(\.key), [resume.key, manual.key, otherDevice.key], "states and current resume points, newest first")
        XCTAssertEqual(recent.last?.deviceLabel, "Another device")
        XCTAssertEqual(SavedProgressRules.recent(entries, limit: 2).count, 2)
        let game = SavedProgressRules.forGame(entries)
        XCTAssertEqual(game.resume?.key, resume.key)
        XCTAssertEqual(game.resume?.title, "Resume point")
        XCTAssertFalse(game.resumeConflict)
        XCTAssertEqual(game.saveStates.map(\.key), [manual.key, otherDevice.key])
        XCTAssertEqual(game.history.map(\.key), [earlier.key])
        XCTAssertEqual(game.history.first?.title, "Earlier resume point")
        XCTAssertEqual(game.gameSaveData.map(\.key), [gameSave.key])
        // A conflicted resume reference is a conflict; two resume references of different cores are not.
        let a = stored("exit", state: true, at: 9_000, refs: [resumeRef], conflicted: [resumeRef])
        let b = stored("exit", state: true, at: 8_500, refs: [resumeRef], conflicted: [resumeRef])
        XCTAssertTrue(SavedProgressRules.forGame([a, b].map { SavedProgressRules.entry($0, labels: [:], localDeviceId: nil, formFactor: .phone) { _ in nil } }).resumeConflict)
        let otherCore = stored("exit", state: true, at: 1_000, refs: ["state/resume/core-b"])
        XCTAssertFalse(SavedProgressRules.forGame([resume, otherCore].map { SavedProgressRules.entry($0, labels: [:], localDeviceId: nil, formFactor: .phone) { _ in nil } }).resumeConflict)
        // Days: local calendar days, newest first.
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(identifier: "UTC")!
        let day1 = stored("exit", state: true, at: 86_400_000 + 10, refs: [resumeRef])
        let day0 = stored("manual", state: true, manual: true, at: 50, refs: ["state/slot/y"])
        let grouped = SavedProgressRules.groupByDay([day1, day0].map { SavedProgressRules.entry($0, labels: [:], localDeviceId: nil, formFactor: .phone) { _ in nil } }, calendar: calendar)
        XCTAssertEqual(grouped.count, 2)
        XCTAssertEqual(grouped.first?.entries.first?.key, day1.key)
    }
}
