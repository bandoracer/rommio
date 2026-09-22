import Foundation
import XCTest
import GRDB
import RommioContract
@testable import RommioFoundation

@MainActor
final class ProgressBackupTests: XCTestCase {
    func testCompleteRoundTripPreservesExactMetadataFilesIntentAndLocalDevice() async throws {
        let a = try BackupHarness(), b = try BackupHarness(); defer { a.remove(); b.remove() }
        let store = try a.store(), identity = try await store.resolveIdentity(a.original.binding)
        try await store.commit(identity, payload: ProgressProtocol.encode(a.original), sources: a.sources)
        _ = try await ProgressUpgrade.run(store: store, identity: identity)
        try await store.pin(identity, checkpointID: a.original.checkpoints[0].checkpointId)
        try await store.quarantine(identity, payload: Data("unknown future record".utf8), reason: "unknown schema")
        let state = try await store.references(identity).first { $0.key.hasPrefix("state/resume/") }!.value[0]
        let slot = ProgressOperation(schemaVersion: 3, operationId: UUID().uuidString.lowercased(), binding: identity.binding, changes: [.init(reference: "state/slot/" + UUID().uuidString.lowercased(), parents: [], checkpointId: state.checkpoint!.checkpointId, tombstone: false)], checkpoints: [state.checkpoint!], deviceId: a.original.deviceId, sessionId: a.original.sessionId, createdAtEpochMs: 8)
        try await store.commit(identity, payload: ProgressProtocol.encode(slot), sources: [:], publish: false)
        let deletion = ProgressOperation(schemaVersion: 3, operationId: UUID().uuidString.lowercased(), binding: identity.binding, changes: [.init(reference: try await store.references(identity).first { $0.key.hasPrefix("state/resume/") }!.key, parents: [state.operationId], checkpointId: nil, tombstone: true)], checkpoints: [], deviceId: a.original.deviceId, sessionId: a.original.sessionId, createdAtEpochMs: 9)
        try await store.commit(identity, payload: ProgressProtocol.encode(deletion), sources: [:], publish: false)
        let gameHead = try await store.references(identity)["game/current"]![0]
        for _ in 0..<2 {
            let branch = ProgressOperation(schemaVersion: 3, operationId: UUID().uuidString.lowercased(), binding: identity.binding, changes: [.init(reference: "game/current", parents: [gameHead.operationId], checkpointId: gameHead.checkpoint!.checkpointId, tombstone: false)], checkpoints: [gameHead.checkpoint!], deviceId: a.original.deviceId, sessionId: a.original.sessionId, createdAtEpochMs: 10)
            try await store.commit(identity, payload: ProgressProtocol.encode(branch), sources: [:])
        }
        let originalLease = try await store.leaseNext(identity, now: 0, durationMs: 1000)!
        _ = try await store.acknowledge(originalLease, verifiedPayloadHash: originalLease.payloadHash)
        _ = try await store.leaseNext(identity, now: 1, durationMs: 1000, states: false)
        try a.database.write { try $0.execute(sql: "UPDATE progress_v2_outbox SET status = 'PARKED_AUTH' WHERE operationId = ?", arguments: [state.operationId]) }
        try ProgressDurability.write(Data("retained bytes".utf8), to: a.progress.appending(path: "sessions/orphan/unknown.bin"))
        try ProgressArchive.directory(a.progress.appending(path: "sessions/orphan/empty"))
        try ProgressDurability.write(Data("old SRAM".utf8), to: a.library.appending(path: "saves/1_1.srm"))
        try ProgressDurability.write(Data("source-device".utf8), to: a.progress.appending(path: "device.json"))
        try ProgressDurability.write(Data("destination-device".utf8), to: b.progress.appending(path: "device.json"))
        await store.close()
        let before = try a.transfer.snapshot()
        let archive = a.root.appending(path: "backup.rommioprogress"); try a.transfer.export(to: archive)
        let preview = try b.transfer.inspect(archive); XCTAssertEqual(preview.damagedArtifacts, 0); XCTAssertTrue(preview.containsLegacy)
        try b.transfer.activate(preview.id)
        let after = try b.transfer.snapshot()
        for name in ProgressBackupSnapshot.columns.keys where name != "progress_v2_outbox" { XCTAssertEqual(after.tables[name], before.tables[name], name) }
        let opened = try b.store()
        let conflicts = try await opened.heads(identity, reference: "game/current"); XCTAssertEqual(conflicts.count, 2)
        let queue = try await opened.outbox(identity)
        XCTAssertEqual(Set(queue.map(\.status)), Set(["PENDING", "PUBLISHED", "PARKED_AUTH", "LOCAL_ONLY"]))
        XCTAssertTrue(queue.allSatisfy { $0.leaseId == nil && $0.leaseUntil == nil })
        XCTAssertEqual(try String(contentsOf: b.progress.appending(path: "device.json"), encoding: .utf8), "destination-device")
        XCTAssertTrue(FileManager.default.fileExists(atPath: b.progress.appending(path: "sessions/orphan/empty").path))
        XCTAssertEqual(try Data(contentsOf: b.progress.appending(path: "sessions/orphan/unknown.bin")), Data("retained bytes".utf8))
        let old = b.root.appending(path: "previous.rommioprogress"); try b.transfer.exportPrevious(preview.id, to: old)
        XCTAssertTrue(FileManager.default.fileExists(atPath: old.path))
        await opened.close()
    }
    func testEveryActivationBoundaryRecoversWithOriginalsAndNoDuplicateOperations() async throws {
        for boundary in ProgressImportBoundary.allCases {
            let a = try BackupHarness(), b = try BackupHarness(); defer { a.remove(); b.remove() }
            let source = try a.store(), identity = try await source.resolveIdentity(a.original.binding)
            try await source.commit(identity, payload: ProgressProtocol.encode(a.original), sources: a.sources); await source.close()
            try ProgressDurability.write(Data("original".utf8), to: b.progress.appending(path: "prior.bin"))
            let archive = a.root.appending(path: "backup"); try a.transfer.export(to: archive)
            let preview = try b.transfer.inspect(archive)
            XCTAssertThrowsError(try b.transfer.activate(preview.id) { if $0 == boundary { throw ProgressStoreError.invalid("interrupted") } })
            let reopenedDB = try AppDatabase(rootDirectory: b.root.appending(path: "Database"))
            let transfer = ProgressTransfer(database: reopenedDB, root: b.progress, library: b.library)
            try transfer.recover(); try transfer.recover()
            XCTAssertEqual(try transfer.snapshot().tables["progress_v2_operations"]?.count, 1)
            let old = try transfer.workspace(preview.id).appending(path: "previous/prior.bin")
            XCTAssertEqual(try Data(contentsOf: old), Data("original".utf8))
        }
    }
    func testCorruptArchiveAndMutatedPreviewNeverReplaceCurrentProgress() async throws {
        let h = try BackupHarness(); defer { h.remove() }
        try ProgressDurability.write(Data("current".utf8), to: h.progress.appending(path: "current.bin"))
        let archive = h.root.appending(path: "backup"); try h.transfer.export(to: archive)
        var damaged = try Data(contentsOf: archive); damaged[damaged.count - 1] ^= 0xff
        let bad = h.root.appending(path: "bad"); try damaged.write(to: bad)
        XCTAssertThrowsError(try h.transfer.inspect(bad))
        let preview = try h.transfer.inspect(archive)
        try Data("changed".utf8).write(to: h.transfer.workspace(preview.id).appending(path: "incoming/current.bin"))
        XCTAssertThrowsError(try h.transfer.activate(preview.id))
        XCTAssertEqual(try String(contentsOf: h.progress.appending(path: "current.bin"), encoding: .utf8), "current")
        XCTAssertFalse(FileManager.default.fileExists(atPath: h.transfer.journalURL.path))
    }
    func testInvalidAncestryIdentityOutboxAndPathsRejectBeforeActivation() async throws {
        let h = try BackupHarness(); defer { h.remove() }
        let store = try h.store(), identity = try await store.resolveIdentity(h.original.binding)
        try await store.commit(identity, payload: ProgressProtocol.encode(h.original), sources: h.sources); await store.close()
        let snapshot = try h.transfer.snapshot()
        for table in ["progress_v2_namespaces", "progress_v2_heads", "progress_v2_outbox"] {
            var broken = snapshot; broken.tables[table] = []
            XCTAssertThrowsError(try broken.validate(at: h.root.appending(path: UUID().uuidString)))
        }
        for path in ["../escape", "/absolute", "a//b", "a/../b", "a\\b", "device.json", "store/.owner.lock"] { XCTAssertThrowsError(try ProgressArchive.safePath(path)) }
        try FileManager.default.createSymbolicLink(at: h.progress.appending(path: "escape"), withDestinationURL: h.root)
        XCTAssertThrowsError(try h.transfer.export(to: h.root.appending(path: "backup")))
    }
    func testDamagedArtifactsRemainExportableInspectableAndRepairable() async throws {
        let a = try BackupHarness(), b = try BackupHarness(); defer { a.remove(); b.remove() }
        let store = try a.store(), identity = try await store.resolveIdentity(a.original.binding)
        try await store.commit(identity, payload: ProgressProtocol.encode(a.original), sources: a.sources)
        let artifact = a.original.checkpoints[0].artifacts[0]
        let blob = try await store.artifact(identity, artifact); try Data("damaged original".utf8).write(to: blob)
        await store.close()
        let backup = a.root.appending(path: "backup"); try a.transfer.export(to: backup)
        let preview = try b.transfer.inspect(backup); XCTAssertEqual(preview.damagedArtifacts, 1)
        try b.transfer.activate(preview.id)
        let repaired = try b.store()
        do { _ = try await repaired.artifact(identity, artifact); XCTFail("Damaged bytes cannot load") } catch { }
        try await repaired.repairArtifact(identity, artifact: artifact, source: a.sources[artifact.sha256]!)
        let valid = try await repaired.artifact(identity, artifact)
        XCTAssertEqual(try ProgressFileHash.sha256(valid), artifact.sha256)
        let retained = try FileManager.default.contentsOfDirectory(at: b.progress.appending(path: "store/\(identity.namespaceId)/\(identity.contentId)/repair-backups"), includingPropertiesForKeys: nil)
        XCTAssertEqual(try Data(contentsOf: retained[0]), Data("damaged original".utf8))
        await repaired.close()
    }
    func testReadyCaptureWithoutCommittedOperationSurvivesBackupAndExplicitRetry() async throws {
        let a = try BackupHarness(), b = try BackupHarness(); defer { a.remove(); b.remove() }
        let store = try a.store(), identity = try await store.resolveIdentity(a.original.binding)
        let bytes = try Dictionary(uniqueKeysWithValues: a.original.checkpoints[0].artifacts.map { ($0.relativePath, try Data(contentsOf: a.sources[$0.sha256]!)) })
        _ = try ProgressCaptureJournal.stage(identity: identity, operation: a.original, bytes: bytes, root: a.progress.appending(path: "captures/\(identity.contentId)"))
        await store.close()
        let archive = a.root.appending(path: "backup"); try a.transfer.export(to: archive)
        let preview = try b.transfer.inspect(archive); XCTAssertEqual(preview.operations, 0)
        try b.transfer.activate(preview.id)
        XCTAssertEqual(try b.transfer.snapshot().tables["progress_v2_operations"]?.count, 0)
        let opened = try b.store()
        let failures = try await ProgressCaptureJournal.recover(root: b.progress.appending(path: "captures/\(identity.contentId)"), store: opened, identity: identity)
        XCTAssertTrue(failures.isEmpty)
        let payloads = try await opened.payloads(identity); XCTAssertEqual(payloads, [try ProgressProtocol.encode(a.original)])
        await opened.close()
    }
    func testCaptureArchiveIsReversibleAndPreservesPartialBytes() throws {
        let h = try BackupHarness(); defer { h.remove() }
        let path = "captures/\(UUID().uuidString.lowercased())/\(UUID().uuidString.lowercased())"
        try ProgressDurability.write(Data("partial".utf8), to: h.progress.appending(path: path + "/partial.bin"))
        try h.transfer.archiveRecovery(path)
        XCTAssertFalse(FileManager.default.fileExists(atPath: h.progress.appending(path: path).path))
        let item = try h.transfer.recoveryItems().first { $0.kind == .archived }!
        try h.transfer.restoreArchive(item.id)
        XCTAssertEqual(try Data(contentsOf: h.progress.appending(path: path + "/partial.bin")), Data("partial".utf8))
        XCTAssertThrowsError(try h.transfer.archiveRecovery("store"))
    }
    func testDamagedImportJournalRecoversFromRedundantDurableCopy() throws {
        let h = try BackupHarness(); defer { h.remove() }
        try ProgressDurability.write(Data("old".utf8), to: h.progress.appending(path: "old.bin"))
        let archive = h.root.appending(path: "backup"); try h.transfer.export(to: archive)
        let preview = try h.transfer.inspect(archive)
        XCTAssertThrowsError(try h.transfer.activate(preview.id) { if $0 == .originalMoved { throw ProgressStoreError.invalid("stop") } })
        try Data("broken journal".utf8).write(to: h.transfer.journalURL)
        try h.transfer.recover()
        XCTAssertEqual(try Data(contentsOf: h.progress.appending(path: "old.bin")), Data("old".utf8))
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(at: h.transfer.workspace(preview.id), includingPropertiesForKeys: nil).contains { $0.lastPathComponent.hasPrefix("damaged-journal-") })
    }
    func testV2UpgradeIsDeterministicAndKeepsLocalOnlyAndDamagedStateIndependent() async throws {
        let h = try BackupHarness(); defer { h.remove() }
        let store = try h.store(), identity = try await store.resolveIdentity(h.original.binding)
        try await store.commit(identity, payload: ProgressProtocol.encode(h.original), sources: h.sources, publish: false)
        let state = h.original.checkpoints[0].artifacts.first { $0.role == "state" }!
        let blob = try await store.artifact(identity, state); try Data("broken".utf8).write(to: blob)
        let warnings = try await ProgressUpgrade.run(store: store, identity: identity); XCTAssertFalse(warnings.isEmpty)
        let first = try await store.payloads(identity)
        _ = try await ProgressUpgrade.run(store: store, identity: identity)
        let second = try await store.payloads(identity); XCTAssertEqual(first, second)
        XCTAssertEqual(first.count, 2)
        let queue = try await store.outbox(identity); XCTAssertTrue(queue.allSatisfy { $0.status == "LOCAL_ONLY" })
        let game = try await store.checkpoint(identity, reference: "game/current"); XCTAssertNotNil(game)
        XCTAssertTrue(first.contains(try ProgressProtocol.encode(h.original)))
        await store.close()
    }
    func testInterruptedImportCanRestoreOriginalWhenIncomingTreeIsUnavailable() throws {
        for boundary in [ProgressImportBoundary.journalReady, .originalMoved, .replacementMoved, .metadataCommitted] {
            let h = try BackupHarness(); defer { h.remove() }
            try ProgressDurability.write(Data("backup".utf8), to: h.progress.appending(path: "value.bin"))
            let archive = h.root.appending(path: "backup"); try h.transfer.export(to: archive)
            try ProgressDurability.write(Data("original".utf8), to: h.progress.appending(path: "value.bin"))
            let preview = try h.transfer.inspect(archive)
            XCTAssertThrowsError(try h.transfer.activate(preview.id) { if $0 == boundary { throw ProgressStoreError.invalid("stop") } })
            if boundary == .originalMoved {
                try FileManager.default.removeItem(at: h.transfer.workspace(preview.id).appending(path: "incoming"))
                XCTAssertThrowsError(try h.transfer.recover())
            }
            try h.transfer.rollbackPendingImport()
            try h.transfer.recover()
            XCTAssertEqual(try String(contentsOf: h.progress.appending(path: "value.bin"), encoding: .utf8), "original")
            XCTAssertEqual(try String(contentsOf: h.transfer.workspace(preview.id).appending(path: "previous/value.bin"), encoding: .utf8), "original")
        }
    }
    func testStableLockRejectsCompetingWriterWithoutRecreatingMovedRoot() throws {
        let h = try BackupHarness(); defer { h.remove() }
        do {
            let lock = try ProgressStorageLock(h.transfer.lock)
            defer { withExtendedLifetime(lock) {} }
            XCTAssertThrowsError(try h.store())
            XCTAssertFalse(FileManager.default.fileExists(atPath: h.progress.path))
        }
    }
    func testApplicationMaintenanceRefusesPlayerAndRetiresStoreHandles() async throws {
        let h = try BackupHarness(); defer { h.remove() }
        let profiles = GRDBServerProfileStore(database: h.database)
        let secrets = try FileSecretStore(rootDirectory: h.root.appending(path: "Secrets"))
        let service = ApplicationProgress(database: h.database, root: h.progress, profiles: profiles, auth: AuthSessionController(profileStore: profiles, secretStore: secrets))
        let store = try await service.store()
        _ = try await store.resolveIdentity(h.original.binding)
        let lease = try await service.beginPlayer()
        do { _ = try await service.exportProgress(); XCTFail("Active player must exclude export") } catch { }
        await service.endPlayer(lease)
        let archive = try await service.exportProgress()
        XCTAssertTrue(FileManager.default.fileExists(atPath: archive.path))
        do { _ = try await store.resolveIdentity(h.original.binding); XCTFail("Retired handle must fail") } catch { }
        let reopened = try await service.store(); _ = try await reopened.resolveIdentity(h.original.binding); await reopened.close()
    }
}

@MainActor
private final class BackupHarness {
    let root: URL, progress: URL, library: URL
    let database: AppDatabase
    let original: ProgressOperation
    let sources: [String: URL]
    var transfer: ProgressTransfer { ProgressTransfer(database: database, root: progress, library: library) }
    init() throws {
        root = FileManager.default.temporaryDirectory.appending(path: "rommio-backup-\(UUID().uuidString)")
        progress = root.appending(path: "Progress"); library = root.appending(path: "Library")
        database = try AppDatabase(rootDirectory: root.appending(path: "Database"))
        let fixture = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().appending(path: "tests/fixtures/progress-v2")
        original = try ProgressProtocol.decode(Data(contentsOf: fixture.appending(path: "coherent.json")))
        sources = Dictionary(uniqueKeysWithValues: original.checkpoints[0].artifacts.map { ($0.sha256, fixture.appending(path: $0.relativePath)) })
    }
    func store() throws -> ProgressStore { try ProgressStore(database: database, rootDirectory: progress.appending(path: "store"), ownerLock: transfer.lock) }
    func remove() { try? FileManager.default.removeItem(at: root) }
}
