import Foundation
import XCTest
import RommioContract
import RommioPlayerKit
import RommioPlayerBridge
@testable import RommioFoundation

/// Opt-in private ROM fixtures are read from the installed QA app, never checked in.
@MainActor
final class CartridgeProgressTests: XCTestCase {
    func testCartridgeNativeRoundTrips() async throws {
        guard let path = ProcessInfo.processInfo.environment["ROMMIO_CARTRIDGE_FIXTURES"] else {
            throw XCTSkip("Set ROMMIO_CARTRIDGE_FIXTURES to the private fixture path map")
        }
        let files = try JSONDecoder().decode([String: String].self, from: Data(contentsOf: URL(fileURLWithPath: path)))
        let repo = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        for (runtime, suffix) in [("gambatte", ".gb"), ("gambatte", ".gbc"), ("mgba", ".gba"), ("snes9x", ".sfc"), ("melonds_ds", ".nds")] {
            let core = repo.appending(path: "build/cartridge-cores/\(runtime)/macosx-\(CartridgePersistence.architecture)/output/\(runtime).dylib")
            let rom = URL(fileURLWithPath: try XCTUnwrap(files[suffix]))
            let root = FileManager.default.temporaryDirectory.appending(path: "cartridge-roundtrip-\(UUID().uuidString)")
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            defer { try? FileManager.default.removeItem(at: root) }
            var bridge = try RMLLibretroBridge(coreURL: core)
            try bridge.prepareGame(at: rom, systemDirectory: root, savesDirectory: root, variables: [:])
            for _ in 0..<16 { try bridge.runFrame() }
            let initial = try NativeProgressCapture.read(bridge, includeState: true)
            var marked = initial.memory
            if var ram = marked[0], !ram.isEmpty {
                let payloadSize = (runtime == "mgba" || runtime == "melonds_ds") ? Int(ram[0]) | Int(ram[1]) << 8 | Int(ram[2]) << 16 | Int(ram[3]) << 24 : ram.count
                let offset = (runtime == "mgba" ? 8 : (runtime == "melonds_ds" ? 4 : 0)) + max(0, payloadSize / 2)
                if payloadSize > 0 { ram[offset] ^= 0x5a; marked[0] = ram }
            }
            try bridge.restoreProgress(Dictionary(uniqueKeysWithValues: marked.map { (NSNumber(value: $0.key), $0.value) }), state: initial.state)
            let before = try NativeProgressCapture.read(bridge, includeState: true)
            XCTAssertEqual(before.memory, marked, "\(runtime) non-default game data installed")
            let qualification = try CartridgePersistence(capture: before, coreSHA256: ProgressFileHash.sha256(core))
            XCTAssertEqual(qualification.state.coreId, runtime)
            XCTAssertNotNil(before.state, "\(runtime): \(before.stateError ?? "missing snapshot")")
            for _ in 0..<12 { try bridge.runFrame() }
            try bridge.restoreProgress(Dictionary(uniqueKeysWithValues: before.memory.map { (NSNumber(value: $0.key), $0.value) }), state: before.state)
            let restored = try NativeProgressCapture.read(bridge, includeState: true)
            try qualification.validate(restored)
            XCTAssertEqual(restored.memory, before.memory, "\(runtime) game-save round trip")
            // Core codecs may normalize cached fields when reading a state. Verify
            // deterministic execution from the retained snapshot, not byte-identical re-encoding.
            for _ in 0..<8 { try bridge.runFrame() }
            let expectedFrame = bridge.copyLatestVideoFrame()?.pixelData
            let expectedMemory = try NativeProgressCapture.read(bridge, includeState: false).memory
            try bridge.restoreProgress(Dictionary(uniqueKeysWithValues: before.memory.map { (NSNumber(value: $0.key), $0.value) }), state: before.state)
            for _ in 0..<8 { try bridge.runFrame() }
            XCTAssertEqual(bridge.copyLatestVideoFrame()?.pixelData, expectedFrame, "\(runtime) snapshot replay pixels")
            XCTAssertEqual(try NativeProgressCapture.read(bridge, includeState: false).memory, expectedMemory, "\(runtime) snapshot replay game data")
            bridge.stop()
            bridge = try RMLLibretroBridge(coreURL: core)
            try bridge.prepareGame(at: rom, systemDirectory: root, savesDirectory: root, variables: [:])
            try bridge.restoreProgress(Dictionary(uniqueKeysWithValues: before.memory.map { (NSNumber(value: $0.key), $0.value) }), state: nil)
            let cold = try NativeProgressCapture.read(bridge, includeState: false)
            XCTAssertEqual(cold.memory, before.memory, "\(runtime) independent cold-boot game save")
            // DS and mGBA defer save installation; advancing a frame must not discard restored bytes.
            try bridge.runFrame()
            let installed = try NativeProgressCapture.read(bridge, includeState: false)
            XCTAssertEqual(installed.memory[0], before.memory[0], "\(runtime) save installed in active cartridge")
            bridge.stop()
            print("PASS cartridge native \(runtime) \(suffix), game and snapshot separately")
        }
    }

    func testPersistenceRejectsIncompleteClockAndUnknownLayouts() throws {
        let hash = String(repeating: "a", count: 64)
        func capture(_ descriptor: String, _ memory: [Int: Data]) -> NativeProgressCapture {
            NativeProgressCapture(memory: memory, state: nil, stateError: nil, options: [:], descriptor: descriptor)
        }
        let valid = capture("rommio-cart-v1:gambatte:gb:32768:24", [0: Data(count: 32768), 1: Data(count: 24)])
        let contract = try CartridgePersistence(capture: valid, coreSHA256: hash)
        XCTAssertThrowsError(try contract.validate(capture(valid.descriptor, [0: Data(count: 32768), 1: Data(count: 8)])))
        XCTAssertThrowsError(try CartridgePersistence(capture: capture("rommio-cart-v1:unknown:gb:0:0", [0: Data(), 1: Data()]), coreSHA256: hash))
        XCTAssertThrowsError(try CartridgePersistence(capture: capture("rommio-cart-v1:snes9x:snes:8192:20", [0: Data(count: 8192), 1: Data(count: 20)]), coreSHA256: hash))
        XCTAssertThrowsError(try CartridgePersistence(capture: capture("rommio-cart-v1:melonds_ds:nds:512:0", [0: Data(count: 512), 1: Data()]), coreSHA256: hash))
        let ds = try CartridgePersistence(capture: capture("rommio-cart-v1:melonds_ds:nds:2820:0", [0: Data(count: 2820), 1: Data()]), coreSHA256: hash)
        XCTAssertEqual(ds.sizes[0], 512 + 4 + 2304)
    }
}
