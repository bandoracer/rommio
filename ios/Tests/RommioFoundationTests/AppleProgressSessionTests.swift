import Foundation
import XCTest
import RommioContract
import RommioPlayerKit
import RommioPlayerBridge
@testable import RommioFoundation

@MainActor
final class AppleProgressSessionTests: XCTestCase {
    func testPinnedAppleCoreCapturesAllMemoryAndRestoresSnapshotExactly() async throws {
        for mapper in [0, 19] {
            let h = try AppleProgressHarness(mapper: mapper)
            let engine = try h.engine()
            defer { engine.bridge.stop(); h.remove() }
            for _ in 0..<8 { try engine.bridge.runFrame() }
            let before = try await engine.captureProgress(includeState: true)
            let qualification = try FceummPersistence(capture: before, coreSHA256: h.digest)
            XCTAssertEqual(before.memory[0]?.count, 8192)
            XCTAssertEqual(before.memory[256]?.count, mapper == 19 ? 128 : 0)
            XCTAssertNotNil(before.state)
            for _ in 0..<8 { try engine.bridge.runFrame() }
            let advanced = try await engine.captureProgress(includeState: false)
            XCTAssertNotEqual(advanced.memory[0], before.memory[0])
            try await engine.restoreProgress(memory: before.memory, state: before.state)
            let restored = try await engine.captureProgress(includeState: true)
            try qualification.validate(restored)
            XCTAssertEqual(restored.memory, before.memory)
            XCTAssertEqual(restored.state, before.state)
            var incomplete = before.memory; incomplete.removeValue(forKey: 256)
            do { try await engine.restoreProgress(memory: incomplete, state: before.state); XCTFail("Incomplete memory must reject before mutation") } catch { }
            let afterRejection = try await engine.captureProgress(includeState: true)
            XCTAssertEqual(afterRejection.state, before.state)
            XCTAssertEqual(afterRejection.memory, before.memory)
        }
    }
    func testRealSessionPreservesGameWhenSnapshotFailsAndRestoresWithPinnedSafety() async throws {
        let h = try AppleProgressHarness(mapper: 19); defer { h.remove() }
        let engine = try h.engine(); let store = try h.store()
        let identity = try await store.resolveIdentity(h.binding)
        let owner = try await h.owner(engine: engine, store: store, identity: identity)
        _ = try await owner.launch()
        for _ in 0..<8 { try engine.bridge.runFrame() }
        let saved = try await owner.capture(reason: "manual", slotID: UUID().uuidString.lowercased())
        let snapshot = try XCTUnwrap(saved.snapshot)
        for _ in 0..<8 { try engine.bridge.runFrame() }
        engine.rejectSnapshot = true
        let partial = try await owner.capture(reason: "periodic")
        XCTAssertNil(partial.snapshot); XCTAssertNotNil(partial.snapshotError)
        let current = try await store.checkpoint(identity, reference: "game/current")
        XCTAssertEqual(current, partial.game)
        let state = try await store.checkpoint(identity, reference: "state/resume/\(try ProgressProtocol.stateCompatibilityKey(snapshot))")
        XCTAssertEqual(state, snapshot)
        engine.rejectSnapshot = false
        let beforeChoice = try await engine.captureProgress(includeState: true)
        do { try await owner.restore(checkpointID: snapshot.checkpointId); XCTFail("Different game bytes require confirmation") }
        catch is ProgressRestoreConfirmation { }
        let untouched = try await engine.captureProgress(includeState: true)
        XCTAssertEqual(untouched.memory, beforeChoice.memory); XCTAssertEqual(untouched.state, beforeChoice.state)
        try await owner.restore(checkpointID: snapshot.checkpointId, confirmed: true)
        let history = try await owner.history()
        XCTAssertTrue(history.contains { $0.checkpoint.captureReason == "safety" && $0.pinned })
        let restored = try await engine.captureProgress(includeState: true)
        let stateArtifact = snapshot.artifacts.first { $0.role == "state" }!
        let expectedURL = try await store.artifact(identity, stateArtifact)
        XCTAssertEqual(restored.state, try Data(contentsOf: expectedURL))
        _ = try await owner.exit(); await store.close()
    }
    func testCancelledCallerCannotCancelAcceptedCaptureAndDatabaseFailureReplaysExactJournal() async throws {
        let h = try AppleProgressHarness(mapper: 0); defer { h.remove() }
        let engine = try h.engine(); let store = try h.store()
        let identity = try await store.resolveIdentity(h.binding)
        let owner = try await h.owner(engine: engine, store: store, identity: identity)
        _ = try await owner.launch()
        for _ in 0..<8 { try engine.bridge.runFrame() }
        let task = Task { try await owner.capture(reason: "background", remainPaused: true) }
        task.cancel()
        let saved = try await task.value
        XCTAssertNotNil(saved.snapshot)
        let database = try AppDatabase(rootDirectory: h.root.appending(path: "database"))
        try database.write { try $0.execute(sql: "CREATE TRIGGER reject_capture BEFORE INSERT ON progress_v2_operations BEGIN SELECT RAISE(ABORT, 'injected storage failure'); END") }
        do { _ = try await owner.capture(reason: "manual"); XCTFail("Write failure must be visible") } catch { }
        let journals = try ProgressCaptureJournal.pending(root: h.root.appending(path: "captures"), identity: identity)
        XCTAssertEqual(journals.count, 1)
        let journal = try JSONDecoder().decode(ProgressCaptureJournal.self, from: Data(contentsOf: journals[0].appending(path: "capture.json")))
        try database.write { try $0.execute(sql: "DROP TRIGGER reject_capture") }
        try await owner.retry()
        let payloads = try await store.payloads(identity)
        XCTAssertTrue(payloads.contains(journal.payload))
        _ = try await owner.exit(); await store.close()
    }
    func testFailedNativeRestoreRollsBackAndSnapshotDeletionDoesNotDeleteGameSave() async throws {
        let h = try AppleProgressHarness(mapper: 19); defer { h.remove() }
        let engine = try h.engine(); let store = try h.store()
        let identity = try await store.resolveIdentity(h.binding)
        let owner = try await h.owner(engine: engine, store: store, identity: identity)
        _ = try await owner.launch()
        for _ in 0..<8 { try engine.bridge.runFrame() }
        let saved = try await owner.capture(reason: "manual", slotID: UUID().uuidString.lowercased())
        for _ in 0..<8 { try engine.bridge.runFrame() }
        try await owner.pause()
        let before = try await engine.captureProgress(includeState: true)
        engine.failNextRestore = true
        do { try await owner.restore(checkpointID: saved.snapshot!.checkpointId, confirmed: true); XCTFail("Native failure must be reported") } catch { }
        let after = try await engine.captureProgress(includeState: true)
        XCTAssertEqual(after.state, before.state); XCTAssertEqual(after.memory, before.memory)
        let game = try await store.checkpoint(identity, reference: "game/current")
        try await owner.deleteSnapshot(checkpointID: saved.snapshot!.checkpointId)
        let retained = try await store.checkpoint(identity, reference: "game/current")
        XCTAssertEqual(retained, game)
        let refs = try await store.references(identity)
        XCTAssertTrue(refs.filter { $0.key.hasPrefix("state/slot/") }.values.allSatisfy { $0.count == 1 && $0[0].checkpoint == nil })
        _ = try await owner.exit(); await store.close()
    }
    func testExplicitLegacyImportPreservesOriginalAndPinsConvertedProgress() async throws {
        let h = try AppleProgressHarness(mapper: 0); defer { h.remove() }
        let engine = try h.engine(); let store = try h.store()
        let identity = try await store.resolveIdentity(h.binding)
        let owner = try await h.owner(engine: engine, store: store, identity: identity)
        _ = try await owner.launch()
        for _ in 0..<8 { try engine.bridge.runFrame() }
        let old = try await engine.captureProgress(includeState: true)
        let file = h.root.appending(path: "original.state"); try old.state!.write(to: file)
        for _ in 0..<8 { try engine.bridge.runFrame() }
        let database = try AppDatabase(rootDirectory: h.root.appending(path: "database"))
        try database.write { try $0.execute(sql: "CREATE TRIGGER reject_import BEFORE INSERT ON progress_v2_operations WHEN instr(CAST(NEW.payload AS TEXT), '\"import\"') > 0 BEGIN SELECT RAISE(ABORT, 'injected import failure'); END") }
        do { _ = try await owner.importLegacy(file: file, snapshot: true); XCTFail("Import commit failure must retain exact retry intent") } catch { }
        let waiting = try await engine.captureProgress(includeState: true)
        XCTAssertEqual(waiting.memory, old.memory)
        XCTAssertEqual(waiting.state, old.state)
        XCTAssertEqual(try ProgressCaptureJournal.pending(root: h.root.appending(path: "captures"), identity: identity).count, 1)
        try database.write { try $0.execute(sql: "DROP TRIGGER reject_import") }
        try await owner.retry()
        let imported = try await owner.importLegacy(file: file, snapshot: true)
        XCTAssertEqual(try Data(contentsOf: file), old.state)
        XCTAssertNotNil(imported.snapshot)
        let entries = try await owner.history()
        XCTAssertTrue(entries.contains { $0.checkpoint.checkpointId == imported.game.checkpointId && $0.pinned })
        XCTAssertTrue(entries.contains { $0.checkpoint.captureReason == "safety" && $0.pinned })
        let restored = try await engine.captureProgress(includeState: true)
        XCTAssertEqual(restored.memory, old.memory)
        _ = try await owner.exit(); await store.close()
    }

    func testDiskReopenRestoresExactResumeAndIndependentColdGameData() async throws {
        let h = try AppleProgressHarness(mapper: 19); defer { h.remove() }
        let engine = try h.engine(); var store = try h.store()
        let identity = try await store.resolveIdentity(h.binding)
        let owner = try await h.owner(engine: engine, store: store, identity: identity)
        _ = try await owner.launch()
        for _ in 0..<12 { try engine.bridge.runFrame() }
        _ = try await owner.capture(reason: "background", remainPaused: true)
        let exact = try await engine.captureProgress(includeState: true)
        // Simulate an unclean player exit: no final capture, close the real store, recreate both owners.
        engine.bridge.stop(); await store.close()
        store = try h.store()
        let reopened = try h.engine()
        let resumed = try await h.owner(engine: reopened, store: store, identity: identity)
        _ = try await resumed.launch()
        let after = try await reopened.captureProgress(includeState: true)
        XCTAssertEqual(after.memory, exact.memory); XCTAssertEqual(after.state, exact.state)
        _ = try await resumed.exit()
        let coldEngine = try h.engine()
        let coldOwner = try await h.owner(engine: coldEngine, store: store, identity: identity)
        _ = try await coldOwner.launch(coldBoot: true)
        let cold = try await coldEngine.captureProgress(includeState: false)
        XCTAssertEqual(cold.memory, exact.memory)
        _ = try await coldOwner.exit(); await store.close()
    }
}

@MainActor
final class AppleProgressHarness {
    let root: URL
    let core: URL
    let rom: URL
    let digest: String
    let binding: ProgressBinding
    init(mapper: Int) throws {
        root = FileManager.default.temporaryDirectory.appending(path: "rommio-apple-real-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let repo = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        core = repo.appending(path: "ios/build/pinned-fceumm/macosx-\(FceummPersistence.architecture)/fceumm.dylib")
        digest = try PinnedAppleCore.verify(core: core, manifest: core.deletingLastPathComponent().appending(path: "FceummProvenance.json"))
        rom = root.appending(path: "counter.nes")
        var data = Data(repeating: 0, count: 16 + (mapper == 19 ? 32768 : 16384))
        data.replaceSubrange(0..<8, with: [0x4e,0x45,0x53,0x1a, mapper == 19 ? 2 : 1,0,mapper == 19 ? 0x32 : 2,mapper == 19 ? 0x10 : 0])
        let counter: [UInt8] = [0x78,0xee,0,0x60,0xd0,8,0xee,1,0x60,0xd0,3,0xee,2,0x60]
        let program: [UInt8] = mapper == 19 ? counter + [0xa9,0x80,0x8d,0,0xf8,0xad,0,0x60,0x8d,0,0x48,0xad,1,0x60,0x8d,0,0x48,0xad,2,0x60,0x8d,0,0x48,0x4c,1,0xe0] : counter + [0x4c,1,0x80]
        let offset = 16 + (mapper == 19 ? 24576 : 0)
        data.replaceSubrange(offset..<offset + program.count, with: program)
        for vector in [6,4,2] { data[data.count - vector] = 0; data[data.count - vector + 1] = mapper == 19 ? 0xe0 : 0x80 }
        try data.write(to: rom)
        binding = ProgressBinding(serverKey: "http://127.0.0.1:18765", accountId: "1", romId: 1, files: [ProgressContentFile(fileId: 1, discIndex: 0, contentSha256: ProgressProtocol.sha256(data), containerSha256: nil, memberPath: nil)])
    }
    func engine() throws -> AppleTestProgressEngine {
        let bridge = try RMLLibretroBridge(coreURL: core)
        try bridge.prepareGame(at: rom, systemDirectory: root, savesDirectory: root, variables: [:])
        return AppleTestProgressEngine(bridge: bridge)
    }
    func store() throws -> ProgressStore { try ProgressStore(database: AppDatabase(rootDirectory: root.appending(path: "database")), rootDirectory: root.appending(path: "store")) }
    func owner(engine: AppleTestProgressEngine, store: ProgressStore, identity: ProgressIdentity) async throws -> ProgressSessionOwner {
        let qualification = try FceummPersistence(capture: await engine.captureProgress(includeState: false), coreSHA256: digest)
        let id = UUID().uuidString.lowercased()
        return ProgressSessionOwner(identity: identity, sessionID: id, qualification: qualification, store: store, engine: engine, deviceID: "00000000-0000-4000-8000-000000000001", captureRoot: root.appending(path: "captures"), workspace: root.appending(path: "sessions/\(id)"))
    }
    func remove() { try? FileManager.default.removeItem(at: root) }
}
final class AppleTestProgressEngine: ProgressPlayerEngine, @unchecked Sendable {
    let bridge: RMLLibretroBridge
    var rejectSnapshot = false
    var failNextRestore = false
    init(bridge: RMLLibretroBridge) { self.bridge = bridge }
    func captureProgress(includeState: Bool) async throws -> NativeProgressCapture {
        let result = try NativeProgressCapture.read(bridge, includeState: includeState && !rejectSnapshot)
        return NativeProgressCapture(memory: result.memory, state: result.state, stateError: includeState && rejectSnapshot ? "Injected snapshot failure" : result.stateError, options: result.options, descriptor: result.descriptor)
    }
    func restoreProgress(memory: [Int: Data], state: Data?) async throws {
        try bridge.restoreProgress(Dictionary(uniqueKeysWithValues: memory.map { (NSNumber(value: $0.key), $0.value) }), state: state)
        if failNextRestore { failNextRestore = false; throw ProgressStoreError.invalid("Injected failure after native mutation") }
    }
    /// The core's last software frame, as the app's engine offers it for save previews.
    func previewFrame() -> PlayDataFrame? {
        guard let frame = bridge.copyLatestVideoFrame() else { return nil }
        return PlayerPreviewFrames.software(data: frame.pixelData, width: Int(frame.width), height: Int(frame.height), pitch: Int(frame.pitch), format: frame.pixelFormat)
    }
    func prepare(session: PlayerSession) async throws {}
    func makeHostController() throws -> PlayerHostController { throw PlayerEngineError.hostUnavailable }
    func start() async throws { bridge.setPaused(false) }
    func stop() async { bridge.stop() }
    func persistSaveRAM() async throws -> URL? { nil }
    func saveState(to url: URL) async throws -> URL { try bridge.serializeState().write(to: url); return url }
    func saveState(slot: Int) async throws -> URL { throw PlayerEngineError.hostUnavailable }
    func loadState(from url: URL) async throws -> Bool { try bridge.unserializeState(Data(contentsOf: url)); return true }
    func setPaused(_ paused: Bool) async throws { bridge.setPaused(paused) }
    func reset() async throws { bridge.reset() }
    func updateVariables(_ variables: [String: String]) async throws { try bridge.updateVariables(variables) }
    func dispatchDigital(keyCode: Int, pressed: Bool, port: Int) async throws {}
    func dispatchMotion(source: PlayerMotionSource, x: Double, y: Double, port: Int) async throws {}
    func updateInputConfiguration(_ configuration: PlayerInputConfiguration) async throws {}
    func availableControllerTypes(port: Int) async throws -> [PlayerControllerDescriptor] { [] }
    func setControllerType(port: Int, controllerTypeID: Int) async throws {}
    func hotkeySignals() -> AsyncStream<PlayerHotkeyAction> { AsyncStream { $0.finish() } }
    func rumbleSignals() -> AsyncStream<PlayerRumbleSignal> { AsyncStream { $0.finish() } }
    func detach() { bridge.stop() }
}
