import Foundation
import XCTest
import GRDB
import RommioContract
@testable import RommioFoundation

@MainActor
final class ProgressStoreTests: XCTestCase {
    private func harness() async throws -> ProgressHarness {
        let h = try ProgressHarness()
        addTeardownBlock { await h.close() }
        return h
    }
    private func rejected(_ block: () async throws -> Void) async {
        do { try await block(); XCTFail("Expected rejection") } catch { }
    }
    private func child(_ parent: ProgressOperation, changes: [ProgressRefChange]? = nil, checkpoints: [ProgressCheckpoint]? = nil) -> ProgressOperation {
        ProgressOperation(schemaVersion: 2, operationId: UUID().uuidString.lowercased(), binding: parent.binding,
                          changes: changes ?? parent.changes.map { ProgressRefChange(reference: $0.reference, parents: [parent.operationId], checkpointId: $0.checkpointId, tombstone: $0.tombstone) },
                          checkpoints: checkpoints ?? parent.checkpoints, deviceId: parent.deviceId, sessionId: parent.sessionId, createdAtEpochMs: parent.createdAtEpochMs)
    }
    func testEveryInterruptedCommitRetainsAtomicHeadsAndOutbox() async throws {
        let h = try await harness(); let identity = try await h.identity()
        try await h.commit(h.original, identity: identity)
        for boundary in ProgressCommitBoundary.allCases {
            let next = child(h.original)
            await rejected { try await h.store.commit(identity, payload: ProgressProtocol.encode(next), sources: h.sources) { if $0 == boundary { throw ProgressStoreError.invalid("Injected interruption") } } }
            try await h.reopen()
            let committed = boundary == .transactionCommitted
            let heads = try await h.store.heads(identity, reference: "save/current")
            let queue = try await h.store.outbox(identity)
            XCTAssertEqual(heads, [committed ? next.operationId : h.original.operationId])
            XCTAssertEqual(queue.count, committed ? 2 : 1)
            let checkpoint = try await h.store.checkpoint(identity, reference: "save/current")
            XCTAssertEqual(checkpoint, h.original.checkpoints[0])
            if committed { try await h.commit(next, identity: identity); let retryQueue = try await h.store.outbox(identity); XCTAssertEqual(retryQueue.count, 2) }
        }
    }
    func testConcurrentBranchesAndTombstonesRequireExplicitResolution() async throws {
        let h = try await harness(); let identity = try await h.identity()
        try await h.commit(h.original, identity: identity)
        let a = child(h.original); let b = child(h.original)
        try await h.commit(a, identity: identity); try await h.commit(b, identity: identity)
        await rejected { _ = try await h.store.checkpoint(identity, reference: "save/current") }
        let deletion = child(b, changes: [ProgressRefChange(reference: "save/current", parents: [b.operationId], checkpointId: nil, tombstone: true)], checkpoints: [])
        try await h.commit(deletion, identity: identity)
        let heads = try await h.store.heads(identity, reference: "save/current")
        XCTAssertEqual(Set(heads), Set([a.operationId, deletion.operationId]))
        var changes: [ProgressRefChange] = []
        for change in h.original.changes {
            let parents = try await h.store.heads(identity, reference: change.reference)
            changes.append(ProgressRefChange(reference: change.reference, parents: parents, checkpointId: change.checkpointId, tombstone: false))
        }
        let resolution = child(h.original, changes: changes)
        try await h.commit(resolution, identity: identity)
        let resolved = try await h.store.heads(identity, reference: "save/current")
        XCTAssertEqual(resolved, [resolution.operationId])
    }
    func testWorkingCopyIsIndependentAndNewSaveInvalidatesOldResume() async throws {
        let h = try await harness(); let identity = try await h.identity()
        try await h.commit(h.original, identity: identity)
        let working = try await h.store.materialize(identity, reference: "save/current", sessionId: UUID().uuidString.lowercased())!
        try Data("core changed this".utf8).write(to: working.appending(path: "save.srm"))
        let intact = try await h.store.checkpoint(identity, reference: "save/current")
        XCTAssertNotNil(intact)
        // A new save/current event without updating resume must leave the old state ineligible.
        let next = child(h.original, changes: [ProgressRefChange(reference: "save/current", parents: [h.original.operationId], checkpointId: h.original.checkpoints[0].checkpointId, tombstone: false)])
        try await h.commit(next, identity: identity)
        let resume = try await h.store.checkpoint(identity, reference: h.original.changes[1].reference)
        let save = try await h.store.checkpoint(identity, reference: "save/current")
        XCTAssertNil(resume); XCTAssertNotNil(save)
    }
    func testLeasesRecoverRejectStaleAcknowledgmentsAndParkOnlyTheirAccount() async throws {
        let h = try await harness(); let identity = try await h.identity()
        try await h.commit(h.original, identity: identity)
        let first = try await h.store.leaseNext(identity, now: 100, durationMs: 1000)!
        let next = child(h.original); try await h.commit(next, identity: identity)
        let blocked = try await h.store.leaseNext(identity, now: 101, durationMs: 1000)
        XCTAssertNil(blocked)
        try await h.reopen()
        let retry = try await h.store.leaseNext(identity, now: 102, durationMs: 1000)!
        XCTAssertEqual(first.operationId, retry.operationId)
        let stale = try await h.store.acknowledge(first, verifiedPayloadHash: first.payloadHash)
        XCTAssertFalse(stale)
        await rejected { _ = try await h.store.acknowledge(retry, verifiedPayloadHash: String(repeating: "0", count: 64)) }
        let accepted = try await h.store.acknowledge(retry, verifiedPayloadHash: retry.payloadHash)
        XCTAssertTrue(accepted)
        let queue = try await h.store.outbox(identity)
        XCTAssertEqual(queue.first { $0.operationId == next.operationId }?.status, "PENDING")
        let second = try await h.store.leaseNext(identity, now: 103, durationMs: 1000)!
        let parked = try await h.store.retry(second, nextAttempt: 200, error: "Sign in required", authBlocked: true)
        XCTAssertTrue(parked)
        let binding = identity.binding
        let other = try await h.store.resolveIdentity(ProgressBinding(serverKey: binding.serverKey, accountId: "other", romId: binding.romId, files: binding.files))
        try await h.store.resumeAuthenticatedWork(other)
        let stillParked = try await h.store.leaseNext(identity, now: 300, durationMs: 1000)
        XCTAssertNil(stillParked)
        try await h.store.resumeAuthenticatedWork(identity)
        let resumed = try await h.store.leaseNext(identity, now: 300, durationMs: 1000)
        XCTAssertEqual(resumed?.operationId, next.operationId)
    }
    func testIdentityAndCorruptionNeverAdvanceWrongReferences() async throws {
        let h = try await harness(); let identity = try await h.identity()
        let artifact = h.original.checkpoints[0].artifacts[0]; let source = h.sources[artifact.sha256]!
        let good = try Data(contentsOf: source)
        try Data("bad".utf8).write(to: source)
        await rejected { try await h.commit(h.original, identity: identity) }
        let empty = try await h.store.heads(identity, reference: "save/current")
        XCTAssertTrue(empty.isEmpty)
        try good.write(to: source); try await h.commit(h.original, identity: identity)
        let b = identity.binding
        let f = b.files[0]
        for binding in [ProgressBinding(serverKey: b.serverKey, accountId: "43", romId: b.romId, files: b.files),
                        ProgressBinding(serverKey: "https://other.example/romm", accountId: b.accountId, romId: b.romId, files: b.files),
                        ProgressBinding(serverKey: b.serverKey, accountId: b.accountId, romId: b.romId, files: [ProgressContentFile(fileId: f.fileId, discIndex: 0, contentSha256: String(repeating: "f", count: 64), containerSha256: nil, memberPath: nil)])] {
            let other = try await h.store.resolveIdentity(binding)
            XCTAssertNotEqual(other.contentId, identity.contentId)
            await rejected { try await h.commit(h.original, identity: other) }
        }
        await rejected { try await h.store.commit(identity, payload: ProgressProtocol.encode(h.original) + Data(" ".utf8), sources: h.sources) }
        let blob = h.root.appending(path: "store/\(identity.namespaceId)/\(identity.contentId)/blobs/\(artifact.sha256)")
        try Data("broken".utf8).write(to: blob)
        await rejected { _ = try await h.store.materialize(identity, reference: "save/current", sessionId: UUID().uuidString.lowercased()) }
        await rejected { try await h.commit(self.child(h.original), identity: identity) }
        let unchanged = try await h.store.heads(identity, reference: "save/current")
        XCTAssertEqual(unchanged, [h.original.operationId])
        try await h.reopen(); let reopenedIdentity = try await h.identity(); XCTAssertEqual(reopenedIdentity, identity)
    }
    /// Verified artifacts aren't re-hashed on every read, but any change to the file is still caught.
    func testVerifiedArtifactIsCheckedAgainAfterAnyChange() async throws {
        let h = try await harness(); let identity = try await h.identity()
        try await h.commit(h.original, identity: identity)
        let artifact = h.original.checkpoints[0].artifacts[0]
        let blob = h.root.appending(path: "store/\(identity.namespaceId)/\(identity.contentId)/blobs/\(artifact.sha256)")
        let good = try Data(contentsOf: blob)
        _ = try await h.store.artifact(identity, artifact)
        _ = try await h.store.artifact(identity, artifact)

        var flipped = good; flipped[0] ^= 1
        try flipped.write(to: blob)
        await rejected { _ = try await h.store.artifact(identity, artifact) }
        try good.write(to: blob)
        _ = try await h.store.artifact(identity, artifact)

        // Same length, swapped in by rename: a different file even when sizes and times line up.
        let replacement = blob.deletingLastPathComponent().appending(path: "replacement")
        try flipped.write(to: replacement)
        let attributes = try FileManager.default.attributesOfItem(atPath: blob.path)
        try FileManager.default.setAttributes([.modificationDate: attributes[.modificationDate] as Any], ofItemAtPath: replacement.path)
        XCTAssertEqual(rename(replacement.path, blob.path), 0)
        await rejected { _ = try await h.store.artifact(identity, artifact) }
    }
    func testMigrationPreservesLegacyRows() async throws {
        let h = try await harness()
        await h.store.close()
        try h.database.write { db in
            try db.execute(sql: "CREATE TABLE fixture_legacy_marker (value TEXT NOT NULL)")
            try db.execute(sql: "INSERT INTO fixture_legacy_marker VALUES ('retained')")
            for table in ["namespaces", "contents", "operations", "heads", "outbox", "quarantine"] { try db.execute(sql: "DROP TABLE progress_v2_\(table)") }
            try db.execute(sql: "DELETE FROM grdb_migrations WHERE identifier = 'v4_progress_store'")
        }
        try await h.reopen()
        let identity = try await h.identity()
        let marker = try h.database.read { try String.fetchOne($0, sql: "SELECT value FROM fixture_legacy_marker") }
        XCTAssertEqual(marker, "retained")
        try await h.commit(h.original, identity: identity)
    }
    func testOwnerLockSymlinksAndUnknownRecordsAreContained() async throws {
        let h = try await harness(); let identity = try await h.identity()
        XCTAssertThrowsError(try ProgressStore(database: h.database, rootDirectory: h.root.appending(path: "store")))
        let unknown = try Data(contentsOf: h.fixtures.appending(path: "unknown-version.json"))
        try await h.store.quarantine(identity, payload: unknown, reason: "Newer schema")
        let retained = try h.database.read { try Data.fetchOne($0, sql: "SELECT payload FROM progress_v2_quarantine") }
        XCTAssertEqual(retained, unknown)
        try await h.commit(h.original, identity: identity)
        let blobs = h.root.appending(path: "store/\(identity.namespaceId)/\(identity.contentId)/blobs")
        let moved = h.root.appending(path: "moved-blobs")
        try FileManager.default.moveItem(at: blobs, to: moved)
        try FileManager.default.createSymbolicLink(at: blobs, withDestinationURL: moved)
        await rejected { _ = try await h.store.checkpoint(identity, reference: "save/current") }
        try FileManager.default.removeItem(at: blobs)
        try FileManager.default.moveItem(at: moved, to: blobs)
    }
}

@MainActor
private final class ProgressHarness {
    let root: URL
    let fixtures: URL
    var database: AppDatabase
    var store: ProgressStore
    let original: ProgressOperation
    let sources: [String: URL]
    init() throws {
        root = FileManager.default.temporaryDirectory.appending(path: "rommio-progress-\(UUID().uuidString)")
        fixtures = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().appending(path: "tests/fixtures/progress-v2")
        database = try AppDatabase(rootDirectory: root.appending(path: "database"))
        store = try ProgressStore(database: database, rootDirectory: root.appending(path: "store"))
        original = try ProgressProtocol.decode(Data(contentsOf: fixtures.appending(path: "coherent.json")))
        sources = Dictionary(uniqueKeysWithValues: try original.checkpoints[0].artifacts.map { [root, fixtures] artifact in
            let source = root.appending(path: artifact.relativePath)
            try FileManager.default.copyItem(at: fixtures.appending(path: artifact.relativePath), to: source)
            return (artifact.sha256, source)
        })
    }
    func identity() async throws -> ProgressIdentity { try await store.resolveIdentity(original.binding) }
    func commit(_ op: ProgressOperation, identity: ProgressIdentity) async throws { try await store.commit(identity, payload: ProgressProtocol.encode(op), sources: sources) }
    func reopen() async throws {
        await store.close(); try database.dbQueue.close()
        database = try AppDatabase(rootDirectory: root.appending(path: "database"))
        store = try ProgressStore(database: database, rootDirectory: root.appending(path: "store"))
    }
    func close() async { await store.close(); try? database.dbQueue.close(); try? FileManager.default.removeItem(at: root) }
}
