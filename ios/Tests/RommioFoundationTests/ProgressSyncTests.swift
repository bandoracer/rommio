import Foundation
import XCTest
import RommioContract
@testable import RommioFoundation

/// Two independent stores, actual immutable envelopes/files, and injected transport faults.
@MainActor
final class ProgressSyncTests: XCTestCase {
    func testBothLanesRoundTripExactBytesIntoIndependentStore() async throws {
        let h = try SyncHarness(); defer { h.remove() }
        let (a, ai) = try await h.store("a"); let (b, bi) = try await h.store("b")
        defer { Task { await a.close(); await b.close() } }
        let game = try h.game(); let state = try h.state(game: game)
        try await a.commit(ai, payload: ProgressProtocol.encode(game), sources: h.sources)
        try await a.commit(ai, payload: ProgressProtocol.encode(state), sources: h.sources)
        let remote = MemoryProgressTransport()
        let sent = try await h.sync(a, ai, remote)
        XCTAssertTrue(sent.complete)
        await remote.resetReadCounts()
        let fetched = try await h.sync(b, bi, remote)
        XCTAssertTrue(fetched.complete)
        let gameHead = try await b.checkpoint(bi, reference: "game/current")
        let stateHead = try await b.checkpoint(bi, reference: state.changes[0].reference)
        XCTAssertEqual(gameHead, game.checkpoints[0]); XCTAssertEqual(stateHead, state.checkpoints[0])
        let payloads = try await b.payloads(bi)
        XCTAssertEqual(Set(payloads), Set(try [game, state].map(ProgressProtocol.encode)))
        let published = try await b.outbox(bi)
        XCTAssertTrue(published.allSatisfy { $0.status == "PUBLISHED" })
        let reads = await remote.readCounts()
        XCTAssertTrue(reads.values.allSatisfy { $0 == 1 }, "Each immutable envelope/shared blob needs one download into a fresh store: \(reads)")
        // Cache scope is one reconciliation: later server corruption is still
        // detected, while the already verified local bytes remain intact.
        await remote.corruptEnvelope(game.operationId)
        let damaged = try await h.sync(b, bi, remote)
        XCTAssertFalse(damaged.errors.isEmpty)
        let preserved = try await b.checkpoint(bi, reference: "game/current")
        XCTAssertEqual(preserved, game.checkpoints[0])
    }

    func testFailedSnapshotUploadDoesNotBlockLaterGameSaveAndLostResponseRecovers() async throws {
        let h = try SyncHarness(); defer { h.remove() }
        let (store, identity) = try await h.store("a"); defer { Task { await store.close() } }
        let game = try h.game(); let state = try h.state(game: game)
        let remote = MemoryProgressTransport()
        try await store.commit(identity, payload: ProgressProtocol.encode(game), sources: h.sources)
        try await store.commit(identity, payload: ProgressProtocol.encode(state), sources: h.sources)
        await remote.rejectBlob(state.checkpoints[0].artifacts.first { $0.role == "state" }!.sha256)
        let first = try await h.sync(store, identity, remote)
        XCTAssertEqual(first.gamePending, 0); XCTAssertEqual(first.statePending, 1)
        let next = try h.game(parent: game.operationId)
        try await store.commit(identity, payload: ProgressProtocol.encode(next), sources: h.sources)
        let second = try await h.sync(store, identity, remote)
        XCTAssertEqual(second.gamePending, 0); XCTAssertEqual(second.statePending, 1)
        await remote.rejectBlob(nil)
        await remote.loseNextEnvelopeResponse()
        // Advance the durable retry deadline without a wall-clock sleep.
        let lease = try await store.leaseNext(identity, now: Int64(Date().timeIntervalSince1970 * 1000) + 60_000, durationMs: 1000, states: true)!
        _ = try await store.retry(lease, nextAttempt: 0, error: "retry")
        _ = try await h.sync(store, identity, remote)
        let recovered = try await h.sync(store, identity, remote)
        XCTAssertTrue(recovered.complete, "\(recovered)")
        let count = await remote.envelopeCount(state.operationId)
        XCTAssertEqual(count, 1, "Server-accepted bytes must be adopted after a lost response")
    }

    func testConcurrentGameBranchesRemainChoicesAndResolutionPinsBoth() async throws {
        let h = try SyncHarness(); defer { h.remove() }
        let (a, ai) = try await h.store("a"); let (b, bi) = try await h.store("b")
        defer { Task { await a.close(); await b.close() } }
        let remote = MemoryProgressTransport()
        let ga = try h.game(); let gb = try h.game()
        try await a.commit(ai, payload: ProgressProtocol.encode(ga), sources: h.sources)
        try await b.commit(bi, payload: ProgressProtocol.encode(gb), sources: h.sources)
        _ = try await h.sync(a, ai, remote); _ = try await h.sync(b, bi, remote)
        let result = try await h.sync(a, ai, remote)
        XCTAssertEqual(result.gameConflicts, 1)
        let heads = try await a.heads(ai, reference: "game/current")
        XCTAssertEqual(Set(heads), Set([ga.operationId, gb.operationId]))
        try await a.resolve(ai, reference: "game/current", selected: ga.operationId, expected: heads, deviceID: ga.deviceId, sessionID: ga.sessionId)
        let history = try await a.history(ai)
        XCTAssertEqual(history.filter(\.pinned).count, 2)
        _ = try await h.sync(a, ai, remote); _ = try await h.sync(b, bi, remote)
        let winner = try await b.checkpoint(bi, reference: "game/current")
        XCTAssertEqual(winner, ga.checkpoints[0])
    }

    func testLocalOnlyProgressNeverBecomesPublicationWorkDuringSync() async throws {
        let h = try SyncHarness(); defer { h.remove() }
        let (store, identity) = try await h.store("local")
        let game = try h.game(), state = try h.state(game: game)
        try await store.commit(identity, payload: ProgressProtocol.encode(game), sources: h.sources, publish: false)
        try await store.commit(identity, payload: ProgressProtocol.encode(state), sources: h.sources, publish: false)
        let remote = MemoryProgressTransport()
        _ = try await h.sync(store, identity, remote)
        let queue = try await store.outbox(identity)
        XCTAssertTrue(queue.allSatisfy { $0.status == "LOCAL_ONLY" })
        let inventory = await remote.inventory(); XCTAssertTrue(inventory.isEmpty)
        await store.close()
    }

    func testWrongPairedGameCannotCommit() async throws {
        let h = try SyncHarness(); defer { h.remove() }
        let (store, identity) = try await h.store("a"); defer { Task { await store.close() } }
        let game = try h.game(); let state = try h.state(game: game)
        do {
            try await store.commit(identity, payload: ProgressProtocol.encode(state), sources: h.sources)
            XCTFail("State needs its exact game operation")
        } catch { }
        try await store.commit(identity, payload: ProgressProtocol.encode(game), sources: h.sources)
        var raw = try JSONSerialization.jsonObject(with: ProgressProtocol.encode(state)) as! [String: Any]
        var cps = raw["checkpoints"] as! [[String: Any]]
        var artifacts = cps[0]["artifacts"] as! [[String: Any]]
        artifacts[0]["sha256"] = String(repeating: "a", count: 64)
        cps[0]["artifacts"] = artifacts; raw["checkpoints"] = cps
        do {
            try await store.commit(identity, payload: JSONSerialization.data(withJSONObject: raw), sources: h.sources)
            XCTFail("State must pair the exact game bytes")
        } catch { }
        let heads = try await store.heads(identity, reference: "game/current")
        XCTAssertEqual(heads, [game.operationId])
    }

    func testRemoteReadbackRepairsCorruptLocalBlobWithoutDiscardingDamagedOriginal() async throws {
        let h = try SyncHarness(); defer { h.remove() }
        let (store, identity) = try await h.store("a"); defer { Task { await store.close() } }
        let game = try h.game(); let remote = MemoryProgressTransport()
        try await store.commit(identity, payload: ProgressProtocol.encode(game), sources: h.sources)
        _ = try await h.sync(store, identity, remote)
        let artifact = game.checkpoints[0].artifacts[0]
        let file = try await store.artifact(identity, artifact)
        try Data("damaged".utf8).write(to: file)
        let result = try await h.sync(store, identity, remote)
        XCTAssertTrue(result.complete, "\(result)")
        let repaired = try await store.artifact(identity, artifact)
        XCTAssertEqual(try Data(contentsOf: repaired), try Data(contentsOf: h.sources[artifact.sha256]!))
        let backups = file.deletingLastPathComponent().deletingLastPathComponent().appending(path: "repair-backups")
        let entries = try FileManager.default.contentsOfDirectory(at: backups, includingPropertiesForKeys: nil)
        XCTAssertEqual(entries.count, 1)
        XCTAssertEqual(try Data(contentsOf: entries[0]), Data("damaged".utf8))
    }
}

private actor MemoryProgressTransport: ProgressTransport {
    private var records: [(ProgressRemoteRecord, Data)] = []
    private var rejectedHash: String?
    private var loseResponse = false
    private var reads: [String: Int] = [:]
    func resetReadCounts() { reads = [:] }
    func readCounts() -> [String: Int] { reads }
    func corruptEnvelope(_ id: String) {
        let index = records.firstIndex { $0.0.name.contains("op_\(id)_") }!
        records[index].1[0] ^= 1
    }
    func rejectBlob(_ hash: String?) { rejectedHash = hash }
    func loseNextEnvelopeResponse() { loseResponse = true }
    func envelopeCount(_ id: String) -> Int { records.filter { $0.0.name.contains("op_\(id)_") }.count }
    func inventory() -> [ProgressRemoteRecord] { records.map(\.0) }
    func read(_ record: ProgressRemoteRecord, to destination: URL, maxBytes: Int64) throws {
        reads[record.name, default: 0] += 1
        let data = records.first { $0.0.id == record.id }!.1
        guard data.count <= maxBytes else { throw ProgressStoreError.invalid("Oversized read") }
        try data.write(to: destination)
    }
    func upload(name: String, source: URL) throws -> ProgressRemoteRecord {
        if let rejectedHash, name.contains(rejectedHash) { throw ProgressStoreError.invalid("Snapshot upload interrupted") }
        let data = try Data(contentsOf: source)
        let record = ProgressRemoteRecord(name: name, id: records.count + 1, byteLength: Int64(data.count))
        records.append((record, data))
        if loseResponse && name.contains("_op_") { loseResponse = false; throw ProgressStoreError.invalid("Response lost after server accepted envelope") }
        return record
    }
}

@MainActor
private final class SyncHarness {
    let root: URL
    let original: ProgressOperation
    let sources: [String: URL]
    init() throws {
        root = FileManager.default.temporaryDirectory.appending(path: "rommio-sync-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let fixtures = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().appending(path: "tests/fixtures/progress-v2")
        original = try ProgressProtocol.decode(Data(contentsOf: fixtures.appending(path: "coherent.json")))
        sources = Dictionary(uniqueKeysWithValues: original.checkpoints[0].artifacts.map { ($0.sha256, fixtures.appending(path: $0.relativePath)) })
    }
    func remove() { try? FileManager.default.removeItem(at: root) }
    func store(_ name: String) async throws -> (ProgressStore, ProgressIdentity) {
        let database = try AppDatabase(rootDirectory: root.appending(path: name + "/database"))
        let store = try ProgressStore(database: database, rootDirectory: root.appending(path: name + "/progress"))
        return (store, try await store.resolveIdentity(original.binding))
    }
    func game(parent: String? = nil) throws -> ProgressOperation {
        let cp = original.checkpoints[0]
        let game = ProgressCheckpoint(schemaVersion: 3, checkpointId: UUID().uuidString.lowercased(), binding: cp.binding, saveCompatibility: cp.saveCompatibility, stateCompatibility: nil, saveDataComplete: true, artifacts: ProgressProtocol.gameArtifacts(cp), deviceId: cp.deviceId, sessionId: cp.sessionId, captureReason: cp.captureReason, createdAtEpochMs: cp.createdAtEpochMs)
        return ProgressOperation(schemaVersion: 3, operationId: UUID().uuidString.lowercased(), binding: cp.binding, changes: [ProgressRefChange(reference: "game/current", parents: parent.map { [$0] } ?? [], checkpointId: game.checkpointId, tombstone: false)], checkpoints: [game], deviceId: cp.deviceId, sessionId: cp.sessionId, createdAtEpochMs: cp.createdAtEpochMs)
    }
    func state(game: ProgressOperation) throws -> ProgressOperation {
        let cp = original.checkpoints[0]
        let state = ProgressCheckpoint(schemaVersion: 3, checkpointId: UUID().uuidString.lowercased(), binding: cp.binding, saveCompatibility: cp.saveCompatibility, stateCompatibility: cp.stateCompatibility, saveDataComplete: true, artifacts: cp.artifacts, deviceId: cp.deviceId, sessionId: cp.sessionId, captureReason: cp.captureReason, createdAtEpochMs: cp.createdAtEpochMs, gameSaveOperationId: game.operationId)
        return ProgressOperation(schemaVersion: 3, operationId: UUID().uuidString.lowercased(), binding: cp.binding, changes: [ProgressRefChange(reference: "state/resume/\(try ProgressProtocol.stateCompatibilityKey(state))", parents: [], checkpointId: state.checkpointId, tombstone: false)], checkpoints: [state], deviceId: cp.deviceId, sessionId: cp.sessionId, createdAtEpochMs: cp.createdAtEpochMs)
    }
    func sync(_ store: ProgressStore, _ identity: ProgressIdentity, _ remote: MemoryProgressTransport) async throws -> ProgressSyncResult {
        try await ProgressSync(store: store, identity: identity, remote: remote, staging: root.appending(path: "staging")).synchronize()
    }
}
