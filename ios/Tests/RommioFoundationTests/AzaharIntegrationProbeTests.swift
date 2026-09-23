import Foundation
import CoreImage
import XCTest
import RommioPlayerBridge
import RommioPlayerKit

/// Opt-in JIT-free renderer probe with a separately licensed homebrew fixture.
@MainActor
final class AzaharIntegrationProbeTests: XCTestCase {
    func testVulkanRendererProducesMetalFrame() throws {
        let environment = ProcessInfo.processInfo.environment
        guard let corePath = environment["ROMMIO_AZAHAR_GPU_CORE"],
              let romPath = environment["ROMMIO_AZAHAR_ROM"] else {
            throw XCTSkip("Set ROMMIO_AZAHAR_GPU_CORE and ROMMIO_AZAHAR_ROM for the native GPU probe")
        }
        let root = FileManager.default.temporaryDirectory.appending(path: "azahar-gpu-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let bridge = try RMLLibretroBridge(coreURL: URL(fileURLWithPath: corePath))
        try bridge.prepareGame(
            at: URL(fileURLWithPath: romPath), systemDirectory: root,
            savesDirectory: root,
            variables: ["citra_graphics_api": "Vulkan", "citra_use_cpu_jit": "disabled", "citra_use_shader_jit": "disabled"]
        )
        defer { bridge.stop() }
        for _ in 0..<180 { try bridge.runFrame() }
        let texture = try XCTUnwrap(bridge.copyLatestHardwareTexture())
        XCTAssertEqual(texture.width, 400)
        XCTAssertEqual(texture.height, 480)
        let image = try XCTUnwrap(CIImage(mtlTexture: texture, options: nil))
        XCTAssertNotNil(CIContext(mtlDevice: texture.device).createCGImage(image, from: image.extent))

        let save = root.appending(path: "Azahar/rommio-progress/sdmc/3ds/3dstris/config.mp")
        let initial = try Data(contentsOf: save)
        func tap(_ x: Double, _ y: Double) throws {
            bridge.setPointerX(((x + 40) / 400) * 2 - 1,
                               y: ((y + 240) / 480) * 2 - 1, pressed: true, port: 0)
            for _ in 0..<5 { try bridge.runFrame() }
            bridge.setPointerX(((x + 40) / 400) * 2 - 1,
                               y: ((y + 240) / 480) * 2 - 1, pressed: false, port: 0)
            for _ in 0..<5 { try bridge.runFrame() }
        }
        try tap(270, 210)
        try tap(50, 22)
        try tap(45, 210)
        let gameWrittenSave = try Data(contentsOf: save)
        XCTAssertNotEqual(gameWrittenSave, initial)

        let capture = try NativeProgressCapture.read(bridge, includeState: true)
        XCTAssertEqual(capture.descriptor, "rommio-files-v1:azahar:3ds")
        let snapshot = try XCTUnwrap(capture.state, capture.stateError ?? "Missing Vulkan snapshot")
        let memory = Dictionary(uniqueKeysWithValues: capture.memory.map { (NSNumber(value: $0.key), $0.value) })
        try FileManager.default.removeItem(at: save)
        try bridge.restoreProgress(memory, state: nil)
        XCTAssertEqual(try Data(contentsOf: save), gameWrittenSave,
                       "The game-authored save must restore independently of a snapshot")
        for _ in 0..<8 { try bridge.runFrame() }
        XCTAssertNotNil(bridge.copyLatestHardwareTexture())
        try FileManager.default.removeItem(at: save)
        try bridge.restoreProgress(memory, state: snapshot)
        XCTAssertEqual(try Data(contentsOf: save), gameWrittenSave)
        for _ in 0..<8 { try bridge.runFrame() }
        XCTAssertNotNil(bridge.copyLatestHardwareTexture())

        bridge.stop()
        func reopen() throws -> RMLLibretroBridge {
            let next = try RMLLibretroBridge(coreURL: URL(fileURLWithPath: corePath))
            try next.prepareGame(
                at: URL(fileURLWithPath: romPath), systemDirectory: root,
                savesDirectory: root,
                variables: ["citra_graphics_api": "Vulkan", "citra_use_cpu_jit": "disabled", "citra_use_shader_jit": "disabled"]
            )
            return next
        }
        let coldSave = try reopen()
        try FileManager.default.removeItem(at: save)
        try coldSave.restoreProgress(memory, state: nil)
        XCTAssertEqual(try Data(contentsOf: save), gameWrittenSave)
        for _ in 0..<8 { try coldSave.runFrame() }
        XCTAssertNotNil(coldSave.copyLatestHardwareTexture())
        coldSave.stop()

        let coldSnapshot = try reopen()
        try FileManager.default.removeItem(at: save)
        try coldSnapshot.restoreProgress(memory, state: snapshot)
        XCTAssertEqual(try Data(contentsOf: save), gameWrittenSave)
        for _ in 0..<8 { try coldSnapshot.runFrame() }
        XCTAssertNotNil(coldSnapshot.copyLatestHardwareTexture())
        coldSnapshot.stop()
    }

    func testAudibleTitleProducesNonSilentAudio() throws {
        let environment = ProcessInfo.processInfo.environment
        guard let corePath = environment["ROMMIO_AZAHAR_CORE"],
              let romPath = environment["ROMMIO_AZAHAR_AUDIO_ROM"] else {
            throw XCTSkip("Set ROMMIO_AZAHAR_CORE and ROMMIO_AZAHAR_AUDIO_ROM for the 3DS audio probe")
        }
        let root = FileManager.default.temporaryDirectory.appending(path: "azahar-audio-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let bridge = try RMLLibretroBridge(coreURL: URL(fileURLWithPath: corePath))
        var options = ["citra_graphics_api": "Software", "citra_use_cpu_jit": "disabled", "citra_use_shader_jit": "disabled"]
        if let audioMode = environment["ROMMIO_AZAHAR_AUDIO_MODE"] { options["citra_audio_emulation"] = audioMode }
        try bridge.prepareGame(at: URL(fileURLWithPath: romPath), systemDirectory: root,
                               savesDirectory: root, variables: options)
        defer { bridge.stop() }
        var samples = 0
        var nonSilentSamples = 0
        for frame in 0..<1200 {
            if frame == 300 { bridge.setDigitalInputPressed(true, keyCode: 8, port: 0) }
            if frame == 310 { bridge.setDigitalInputPressed(false, keyCode: 8, port: 0) }
            try bridge.runFrame()
            for batch in bridge.drainAudioBatches() {
                samples += Int(batch.frameCount)
                nonSilentSamples += batch.sampleData.withUnsafeBytes { bytes in
                    bytes.bindMemory(to: Int16.self).reduce(0) { $0 + ($1 == 0 ? 0 : 1) }
                }
            }
        }
        let frame = try XCTUnwrap(bridge.copyLatestVideoFrame())
        XCTAssertGreaterThan(Set(frame.pixelData).count, 8, "The audio title must render more than a blank frame")
        XCTAssertGreaterThan(samples, 0, "The audio title must feed the audio callback")
        XCTAssertGreaterThan(nonSilentSamples, 0, "The audio callback must contain actual sound")
    }

    func testSoftwareRendererBootsAndProducesFrames() throws {
        let environment = ProcessInfo.processInfo.environment
        guard let corePath = environment["ROMMIO_AZAHAR_CORE"],
              let romPath = environment["ROMMIO_AZAHAR_ROM"] else {
            throw XCTSkip("Set ROMMIO_AZAHAR_CORE and ROMMIO_AZAHAR_ROM for the 3DS integration probe")
        }
        let root = FileManager.default.temporaryDirectory.appending(path: "azahar-probe-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let core = try RMLLibretroBridge(coreURL: URL(fileURLWithPath: corePath))
        try core.prepareGame(
            at: URL(fileURLWithPath: romPath),
            systemDirectory: root,
            savesDirectory: root,
            variables: ["citra_graphics_api": "Software", "citra_use_cpu_jit": "disabled", "citra_use_shader_jit": "disabled"]
        )
        for _ in 0..<180 { try core.runFrame() }
        let frame = try XCTUnwrap(core.copyLatestVideoFrame())
        XCTAssertGreaterThan(frame.width, 0)
        XCTAssertGreaterThan(frame.height, frame.width)
        XCTAssertGreaterThan(Set(frame.pixelData).count, 8, "The 3DS homebrew should render both screens")
        core.stop()
    }

    func testGameSaveBundleAndResumeSnapshotRestoreIndependently() throws {
        let environment = ProcessInfo.processInfo.environment
        guard let corePath = environment["ROMMIO_AZAHAR_CORE"],
              let romPath = environment["ROMMIO_AZAHAR_ROM"] else {
            throw XCTSkip("Set ROMMIO_AZAHAR_CORE and ROMMIO_AZAHAR_ROM for the 3DS integration probe")
        }
        let root = FileManager.default.temporaryDirectory.appending(path: "azahar-progress-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let options = ["citra_graphics_api": "Software", "citra_use_cpu_jit": "disabled", "citra_use_shader_jit": "disabled"]
        func launch() throws -> RMLLibretroBridge {
            let bridge = try RMLLibretroBridge(coreURL: URL(fileURLWithPath: corePath))
            try bridge.prepareGame(at: URL(fileURLWithPath: romPath), systemDirectory: root,
                                   savesDirectory: root, variables: options)
            for _ in 0..<180 { try bridge.runFrame() }
            return bridge
        }
        let save = root.appending(path: "Azahar/rommio-progress/sdmc/3ds/3dstris/config.mp")
        let bridge = try launch()
        let defaultConfig = try Data(contentsOf: save)
        // 3DStris writes config.mp itself when the lower-screen language selector is saved.
        // Azahar's default 400x480 layout centers the 320x240 touch screen below the top screen.
        func tap(_ screenX: Double, _ screenY: Double) throws {
            let x = ((screenX + 40) / 400) * 2 - 1
            let y = ((screenY + 240) / 480) * 2 - 1
            bridge.setPointerX(x, y: y, pressed: true, port: 0)
            for _ in 0..<5 { try bridge.runFrame() }
            bridge.setPointerX(x, y: y, pressed: false, port: 0)
            for _ in 0..<5 { try bridge.runFrame() }
        }
        try tap(270, 210) // Main menu > Languages.
        try tap(50, 22)   // First translated language, different from en_US.
        try tap(45, 210)  // Save.
        let gameWrittenConfig = try Data(contentsOf: save)
        XCTAssertNotEqual(gameWrittenConfig, defaultConfig, "The running game must write the save under test")
        let capture = try NativeProgressCapture.read(bridge, includeState: true)
        XCTAssertEqual(capture.descriptor, "rommio-files-v1:azahar:3ds")
        XCTAssertNotNil(capture.state, capture.stateError ?? "")
        XCTAssertGreaterThan(capture.memory[0]?.count ?? 0, 12)
        try FileManager.default.removeItem(at: save)
        let regions = Dictionary(uniqueKeysWithValues: capture.memory.map { (NSNumber(value: $0.key), $0.value) })
        try bridge.restoreProgress(regions, state: nil)
        XCTAssertEqual(try Data(contentsOf: save), gameWrittenConfig, "The game-authored save restores without a snapshot")
        try FileManager.default.removeItem(at: save)
        try bridge.restoreProgress(regions, state: capture.state)
        XCTAssertEqual(try Data(contentsOf: save), gameWrittenConfig, "The snapshot path also restores the game-save tree")
        try bridge.runFrame()
        bridge.stop()
        let reopened = try launch()
        try FileManager.default.removeItem(at: save)
        try reopened.restoreProgress(regions, state: nil)
        XCTAssertEqual(try Data(contentsOf: save), gameWrittenConfig, "A fresh core restores the game-authored save")
        reopened.stop()
    }
}
