import Foundation
import XCTest
@testable import RommioPlayerBridge

final class RMLLibretroBridgeTests: XCTestCase {
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
