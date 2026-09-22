import Foundation
import XCTest
@testable import RommioPlayerBridge

final class RMLLibretroBridgeTests: XCTestCase {
    func testNegotiatedAndDefault16BitFramesRetainFormatAndPitch() throws {
        let workspace = try makeWorkspace()
        defer { try? FileManager.default.removeItem(at: workspace) }
        let core = try buildFakeCore(in: workspace)
        let rom = try makeROM(in: workspace)
        for (selection, expectedFormat, red) in [("rgb565", 2, UInt16(0xf800)), ("0rgb1555", 3, UInt16(0x7c00)), ("default1555", 3, UInt16(0x7c00)), ("invalid", 3, UInt16(0x7c00))] {
            let bridge = try RMLLibretroBridge(coreURL: core)
            try bridge.prepareGame(at: rom, systemDirectory: workspace.appending(path: "system"), savesDirectory: workspace.appending(path: "saves"), variables: ["faux_pixel_format": selection])
            try bridge.runFrame()
            let frame = try XCTUnwrap(bridge.copyLatestVideoFrame())
            XCTAssertEqual(frame.pixelFormat.rawValue, expectedFormat, selection)
            XCTAssertEqual(frame.pitch, 6)
            XCTAssertEqual(frame.pixelData.count, 12)
            XCTAssertEqual(frame.pixelData.withUnsafeBytes { $0.loadUnaligned(as: UInt16.self) }, red)
            bridge.stop()
        }
    }

    func testAllConsoleDigitalAndAnalogInputsReachNativeCoreAndClearOnPause() throws {
        let workspace = try makeWorkspace()
        defer { try? FileManager.default.removeItem(at: workspace) }
        let bridge = try RMLLibretroBridge(coreURL: buildFakeCore(in: workspace))
        defer { bridge.stop() }
        try bridge.prepareGame(at: makeROM(in: workspace), systemDirectory: workspace, savesDirectory: workspace, variables: ["faux_input": "true"])
        func sample() throws -> [UInt32] {
            try bridge.runFrame()
            let data = try XCTUnwrap(bridge.copyLatestVideoFrame()).pixelData
            return (0...2).map { offset in data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: offset * 4, as: UInt32.self) } }
        }
        for key in 0..<16 {
            bridge.setDigitalInputPressed(true, keyCode: key, port: 0)
            XCTAssertEqual(try sample()[0], 1 << key)
            bridge.setDigitalInputPressed(false, keyCode: key, port: 0)
            XCTAssertEqual(try sample()[0], 0)
        }
        bridge.setMotionSource(.dPad, x: -1, y: -1, port: 0)
        XCTAssertEqual(try sample()[0], (1 << 4) | (1 << 6))
        bridge.setMotionSource(.dPad, x: 0, y: 0, port: 0)
        bridge.setMotionSource(.analogLeft, x: 1, y: -1, port: 0)
        bridge.setMotionSource(.analogRight, x: -1, y: 1, port: 0)
        var result = try sample()
        XCTAssertEqual(result[1], UInt32(0x80017fff))
        XCTAssertEqual(result[2], UInt32(0x7fff8001))
        bridge.setPaused(true); bridge.setPaused(false)
        result = try sample()
        XCTAssertEqual(result[1], 0); XCTAssertEqual(result[2], 0)
    }

    func testShortTapSurvivesUntilOneFrameAndPauseClearsHeldInput() throws {
        let workspace = try makeWorkspace()
        defer { try? FileManager.default.removeItem(at: workspace) }
        let bridge = try RMLLibretroBridge(coreURL: buildFakeCore(in: workspace))
        defer { bridge.stop() }
        try bridge.prepareGame(at: makeROM(in: workspace), systemDirectory: workspace, savesDirectory: workspace, variables: [:])
        func pixel() throws -> UInt32 {
            try bridge.runFrame()
            return try XCTUnwrap(bridge.copyLatestVideoFrame()).pixelData.withUnsafeBytes { $0.loadUnaligned(as: UInt32.self) }
        }
        bridge.setDigitalInputPressed(true, keyCode: 0, port: 0)
        bridge.setDigitalInputPressed(false, keyCode: 0, port: 0)
        XCTAssertEqual(try pixel(), 0xFFFF00FF, "A tap between frames must reach the core")
        XCTAssertEqual(try pixel(), 0xFFFF0000, "Released input must not stick")
        bridge.setDigitalInputPressed(true, keyCode: 0, port: 0)
        XCTAssertEqual(try pixel(), 0xFFFF00FF)
        XCTAssertEqual(try pixel(), 0xFFFF00FF, "Held input persists across frames")
        bridge.setPaused(true)
        bridge.setPaused(false)
        XCTAssertEqual(try pixel(), 0xFFFF0000, "Pause clears held and pending input")
    }

    func testPointerCenterEdgesReleaseAndShortTapAreIndependent() throws {
        let workspace = try makeWorkspace()
        defer { try? FileManager.default.removeItem(at: workspace) }
        let bridge = try RMLLibretroBridge(coreURL: buildFakeCore(in: workspace))
        defer { bridge.stop() }
        try bridge.prepareGame(at: makeROM(in: workspace), systemDirectory: workspace, savesDirectory: workspace, variables: ["faux_pointer": "true"])
        func sample() throws -> [Int32] {
            try bridge.runFrame()
            let data = try XCTUnwrap(bridge.copyLatestVideoFrame()).pixelData
            return (1...3).map { index in data.withUnsafeBytes { $0.loadUnaligned(fromByteOffset: index * 4, as: Int32.self) } }
        }
        bridge.setPointerX(0, y: 0, pressed: true, port: 0)
        XCTAssertEqual(try sample(), [0, 0, 1], "The center is a valid pressed coordinate")
        bridge.setPointerX(0, y: 0, pressed: false, port: 0)
        XCTAssertEqual(try sample(), [0, 0, 0])
        bridge.setPointerX(-1, y: 1, pressed: true, port: 0)
        bridge.setPointerX(-1, y: 1, pressed: false, port: 0)
        XCTAssertEqual(try sample(), [-32767, 32767, 1], "A short touch survives one core frame")
        XCTAssertEqual(try sample(), [-32767, 32767, 0])
        bridge.setPointerX(1, y: -1, pressed: true, port: 0)
        bridge.setPaused(true)
        bridge.setPaused(false)
        XCTAssertEqual(try sample(), [0, 0, 0])
    }

    func testMissingCoreReportsReadableError() {
        let missingURL = URL(fileURLWithPath: "/tmp/definitely-missing-libretro-core.dylib")

        XCTAssertThrowsError(try RMLLibretroBridge(coreURL: missingURL)) { error in
            XCTAssertTrue(
                error.localizedDescription.contains("Missing libretro core"),
                "Unexpected missing-core error: \(error.localizedDescription)"
            )
        }
    }

    func testFakeCoreProducesFramesAudioRumbleAndPersistedData() throws {
        let workspace = try makeWorkspace()
        let coreURL = try buildFakeCore(in: workspace)
        let romURL = try makeROM(in: workspace)
        let bridge = try RMLLibretroBridge(coreURL: coreURL)

        try bridge.prepareGame(
            at: romURL,
            systemDirectory: workspace.appending(path: "system"),
            savesDirectory: workspace.appending(path: "saves"),
            variables: ["faux_palette": "red"]
        )
        try bridge.runFrame()

        let frame = try XCTUnwrap(bridge.copyLatestVideoFrame())
        XCTAssertEqual(frame.width, 2)
        XCTAssertEqual(frame.height, 2)
        XCTAssertEqual(frame.pitch, 8)
        XCTAssertEqual(frame.pixelData.count, 16)

        let firstPixel = frame.pixelData.withUnsafeBytes { rawBuffer in
            rawBuffer.load(as: UInt32.self)
        }
        XCTAssertEqual(firstPixel, 0xFFFF0000)

        XCTAssertEqual(bridge.drainAudioBatches().first?.frameCount, 2)
        XCTAssertEqual(
            bridge.drainRumbleMagnitudes().first?.doubleValue ?? 0,
            Double(1234) / Double(UInt16.max),
            accuracy: 0.0001
        )

        XCTAssertEqual(try bridge.serializeSaveRAM(), Data([1, 2, 3, 4]))
        try bridge.restoreSaveRAM(Data([4, 3, 2, 1]))
        XCTAssertEqual(try bridge.serializeSaveRAM(), Data([4, 3, 2, 1]))

        let nextState = Data([1, 1, 2, 2, 3, 3, 4, 4])
        try bridge.unserializeState(nextState)
        XCTAssertEqual(try bridge.serializeState(), nextState)

        let controllers = bridge.availableControllerDescriptors(forPort: 0)
        XCTAssertFalse(controllers.isEmpty)
        XCTAssertEqual(controllers.first?.identifier, 1)

        bridge.stop()
    }

    func testBridgeUpdatesVariablesBetweenFrames() throws {
        let workspace = try makeWorkspace()
        let coreURL = try buildFakeCore(in: workspace)
        let romURL = try makeROM(in: workspace)
        let bridge = try RMLLibretroBridge(coreURL: coreURL)

        try bridge.prepareGame(
            at: romURL,
            systemDirectory: workspace.appending(path: "system"),
            savesDirectory: workspace.appending(path: "saves"),
            variables: ["faux_palette": "red"]
        )
        try bridge.runFrame()
        try bridge.updateVariables(["faux_palette": "green"])
        try bridge.runFrame()

        let frame = try XCTUnwrap(bridge.copyLatestVideoFrame())
        let firstPixel = frame.pixelData.withUnsafeBytes { rawBuffer in
            rawBuffer.load(as: UInt32.self)
        }
        XCTAssertEqual(firstPixel, 0xFF00FF00)

        bridge.stop()
    }

    private func makeWorkspace() throws -> URL {
        let root = FileManager.default.temporaryDirectory.appending(path: "RommioPlayerBridgeTests").appending(path: UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        return root
    }

    private func makeROM(in workspace: URL) throws -> URL {
        let romURL = workspace.appending(path: "fake.nes")
        try Data([0x4E, 0x45, 0x53, 0x1A]).write(to: romURL, options: .atomic)
        return romURL
    }

    private func buildFakeCore(in workspace: URL) throws -> URL {
        let sourceRoot = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appending(path: "Fixtures")
        let sourceURL = sourceRoot.appending(path: "FakeLibretroCore.c")
        let outputURL = workspace.appending(path: "fake_libretro.dylib")

        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/xcrun")
        process.arguments = [
            "clang",
            "-std=c11",
            "-dynamiclib",
            sourceURL.path,
            "-o",
            outputURL.path,
        ]

        let pipe = Pipe()
        process.standardError = pipe
        process.standardOutput = pipe
        try process.run()
        process.waitUntilExit()

        if process.terminationStatus != 0 {
            let output = String(data: pipe.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""
            throw XCTSkip("Unable to compile the fake libretro core: \(output)")
        }

        return outputURL
    }
}
