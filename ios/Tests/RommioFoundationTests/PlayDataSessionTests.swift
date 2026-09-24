import Foundation
import XCTest
import RommioContract
import RommioPlayerKit
@testable import RommioFoundation

/// Local play data from a real player session (pinned fceumm core, real store): the
/// session row and its end reason, previews for state captures and restores, save
/// names, and the saves reader's output for Play, Game and conflicts.
@MainActor
final class PlayDataSessionTests: XCTestCase {
    private static let device = "00000000-0000-4000-8000-000000000001"
    private static let peer = "00000000-0000-4000-8000-0000000000ee"
    /// Android's quick-save slot 1 (`UUID.nameUUIDFromBytes("rommio-slot-1")`).
    private static let slot1 = "f363ea84-8c1c-31c5-a30a-cd5771de7078"

    func testRealSessionRecordsPlayPreviewsNamesAndTheSavesReader() async throws {
        let h = try AppleProgressHarness(mapper: 0); defer { h.remove() }
        let database = try AppDatabase(rootDirectory: h.root.appending(path: "database"))
        let store = try ProgressStore(database: database, rootDirectory: h.root.appending(path: "store"))
        let playData = LocalPlayData(store: PlayDataStore(database: database), thumbnails: SaveThumbnailStore(root: h.root.appending(path: "previews")))
        let identity = try await store.resolveIdentity(h.binding)
        let engine = try h.engine()
        defer { engine.bridge.stop() }
        let qualification = try FceummPersistence(capture: await engine.captureProgress(includeState: false), coreSHA256: h.digest)
        let sessionID = UUID().uuidString.lowercased()
        let owner = ProgressSessionOwner(identity: identity, sessionID: sessionID, qualification: qualification, store: store, engine: engine,
                                         deviceID: Self.device, captureRoot: h.root.appending(path: "captures"),
                                         workspace: h.root.appending(path: "sessions/\(sessionID)"), playData: playData, platformSlug: "nes")
        _ = try await owner.launch()
        await playData.flush()
        let open = try XCTUnwrap(try playData.store.session(sessionID))
        XCTAssertNil(open.endedAtEpochMs, "the session starts when progress selection yields a playable session")
        XCTAssertEqual(open.platformSlug, "nes"); XCTAssertEqual(open.coreId, "fceumm"); XCTAssertEqual(open.deviceId, Self.device)

        for _ in 0..<8 { try engine.bridge.runFrame() }
        let manual = try await owner.capture(reason: "manual", slotID: Self.slot1)
        let manualState = try XCTUnwrap(manual.snapshot)
        for _ in 0..<8 { try engine.bridge.runFrame() }
        // Loading the save state: a safety copy first, then a "restore" capture that shows the source's frame.
        try await owner.restore(checkpointID: manualState.checkpointId, confirmed: true)
        try await owner.resume()
        let exited = try await owner.exit()
        let exit = try XCTUnwrap(exited)
        XCTAssertNotNil(exit.snapshot)
        await playData.flush()

        // Left within 5 s of active play after a manual save state: "state".
        let row = try XCTUnwrap(try playData.store.session(sessionID))
        XCTAssertEqual(row.reason, .state)
        XCTAssertNotNil(row.endedAtEpochMs)
        XCTAssertGreaterThanOrEqual(row.activeDurationMs, 0)

        let key = { (cp: ProgressCheckpoint) in SaveKey(namespaceId: identity.namespaceId, contentId: identity.contentId, checkpointId: cp.checkpointId) }
        let history = try await store.history(identity)
        XCTAssertNotNil(playData.thumbnails.urlIfPresent(key(manualState)), "a manual save state has a preview")
        XCTAssertNotNil(playData.thumbnails.urlIfPresent(key(exit.snapshot!)), "the exit resume point has a preview")
        XCTAssertNil(playData.thumbnails.urlIfPresent(key(exit.game)), "game-save data has none")
        let restore = try XCTUnwrap(history.first { $0.checkpoint.captureReason == "restore" && $0.checkpoint.stateCompatibility != nil })
        let copied = try XCTUnwrap(playData.thumbnails.urlIfPresent(key(restore.checkpoint)))
        XCTAssertEqual(try Data(contentsOf: copied), try Data(contentsOf: playData.thumbnails.urlIfPresent(key(manualState))!), "restore shows the source's frame")
        XCTAssertTrue(history.contains { $0.manual && $0.checkpoint.checkpointId == manualState.checkpointId })
        XCTAssertFalse(history.contains { $0.operationId.isEmpty })

        // The reader: one game, Recent saves, names.
        let device = Self.device
        let reader = SavedProgressReader(storeProvider: { store }, deviceProvider: { device }, playData: playData.store,
                                         thumbnails: playData.thumbnails, formFactor: { .phone })
        let account = ProgressAccount(serverKey: h.binding.serverKey, accountId: h.binding.accountId)
        var saves = try await reader.gameSaves(account, romId: 1, fileId: 1, contentSha256: h.binding.files[0].contentSha256)
        let resume = try XCTUnwrap(saves.resume)
        XCTAssertEqual(resume.key, key(exit.snapshot!))
        XCTAssertEqual(resume.captureReason, "exit")
        XCTAssertEqual(resume.deviceLabel, "This iPhone")
        XCTAssertEqual(resume.sync, .waiting, "committed locally, not yet published")
        XCTAssertNotNil(resume.thumbnailURL)
        XCTAssertFalse(saves.resumeConflict)
        let slot = try XCTUnwrap(saves.saveStates.first { $0.key == key(manualState) })
        XCTAssertEqual(slot.slotNumber, 1)
        XCTAssertEqual(slot.title, "Save state · Slot 1")
        XCTAssertTrue(slot.loadable)
        XCTAssertTrue(saves.history.contains { $0.captureReason == "safety" }, "earlier resume points include the safety copy")
        XCTAssertEqual(saves.gameSaveData.count, 4, "manual, safety, restore and exit each saved game-save data")
        XCTAssertNil(saves.gameSaveData.first?.thumbnailURL)
        let other = try await reader.gameSaves(account, romId: 2)
        XCTAssertTrue(other.isEmpty)

        let renamed = try await reader.rename(slot.key, to: "  Before   the boss ")
        XCTAssertEqual(renamed, "Before the boss")
        let recent = try await reader.recent(account)
        XCTAssertEqual(recent.first?.key, resume.key, "newest first")
        XCTAssertEqual(recent.first { $0.key == slot.key }?.label, "Before the boss")
        XCTAssertFalse(recent.contains { $0.kind == .gameSave })
        XCTAssertFalse(recent.contains { $0.kind == .resumePoint && !$0.isCurrentResume }, "earlier resume points are left out")
        let described = try await reader.describe(identity, history: history)
        XCTAssertEqual(described.first { $0.key == slot.key }?.label, "Before the boss")
        let otherAccount = try await reader.recent(ProgressAccount(serverKey: h.binding.serverKey, accountId: "2"))
        XCTAssertEqual(otherAccount.count, 0, "other accounts are left out")

        // A second device continued from an earlier point: two resume heads.
        let references = try await store.references(identity)
        let resumeRef = try XCTUnwrap(references.keys.first { $0.hasPrefix("state/resume/") })
        let head = try XCTUnwrap(references[resumeRef]?.first)
        let headOperation = try (try await store.payloads(identity)).map(ProgressProtocol.decode).first { $0.operationId == head.operationId }
        let parents = try XCTUnwrap(headOperation?.changes.first { $0.reference == resumeRef }?.parents)
        let branch = ProgressOperation(schemaVersion: 3, operationId: UUID().uuidString.lowercased(), binding: identity.binding,
                                       changes: [ProgressRefChange(reference: resumeRef, parents: parents, checkpointId: manualState.checkpointId, tombstone: false)],
                                       checkpoints: [manualState], deviceId: Self.peer, sessionId: UUID().uuidString.lowercased(),
                                       createdAtEpochMs: manualState.createdAtEpochMs + 1)
        try await store.commit(identity, payload: ProgressProtocol.encode(branch), sources: [:], publish: false)
        let conflicts = try await reader.conflicts(account)
        let conflict = try XCTUnwrap(conflicts.first)
        XCTAssertEqual(conflicts.count, 1)
        XCTAssertEqual(conflict.kind, .resume)
        XCTAssertEqual(conflict.reference, resumeRef)
        XCTAssertEqual(Set(conflict.choices.map(\.operationId)), Set([head.operationId, branch.operationId]))
        XCTAssertEqual(conflict.prompt(gameName: "Counter"), "Counter has two resume points. Choose which to keep playing.")
        saves = try await reader.gameSaves(account, romId: 1)
        XCTAssertTrue(saves.resumeConflict)
        XCTAssertEqual(saves.resume?.sync, .conflict, "a conflicted current reference wins over the outbox state")

        try await store.resolve(identity, reference: resumeRef, selected: head.operationId, expected: conflict.choices.map(\.operationId),
                                deviceID: Self.device, sessionID: UUID().uuidString.lowercased())
        let after = try await reader.conflicts(account)
        XCTAssertTrue(after.isEmpty)
        let resolved = try await reader.gameSaves(account, romId: 1)
        XCTAssertFalse(resolved.resumeConflict)
        await store.close()
    }
}
