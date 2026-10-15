#if DEBUG && canImport(UIKit)
import Foundation
import CoreImage
import RommioFoundation
import RommioPlayerKit
import RommioPlayerBridge

/// Opt-in native qualification using the exact signed cores in the installed app.
@MainActor
extension RommioAppModel {
    func runExpansionNativeProbe(root: URL) throws {
        /// Frame capture for comparing renderers: run `frames` frames, holding each press's button
        /// (a libretro joypad id) for its length, and write every `every`th frame as a PNG.
        struct Capture: Decodable { let frames: Int; let every: Int; let presses: [Press] }
        struct Press: Decodable { let frame: Int; let button: Int; let length: Int }
        struct Fixture: Decodable { let runtime: String; let system: String; let rom: String; let variables: [String: String]; let enduranceSeconds: Int?; let audioOnly: Bool?; let bootOnly: Bool?; let capture: Capture? }
        let fixtures = try JSONDecoder().decode([Fixture].self, from: Data(contentsOf: root.appending(path: "cases.json")))
        var results: [[String: String]] = []
        for fixture in fixtures {
            guard (PinnedAppleCore.cartridgeRuntimes.contains(fixture.runtime) || PinnedAppleCore.candidateRuntimes.contains(fixture.runtime)), fixture.rom == URL(fileURLWithPath: fixture.rom).lastPathComponent else { throw ProgressStoreError.invalid("Invalid native fixture") }
            let core = Bundle.main.bundleURL.appending(path: "Frameworks/\(fixture.runtime).framework/\(fixture.runtime)")
            let digest = try PinnedAppleCore.verify(core: core, manifest: Bundle.main.bundleURL.appending(path: "\(fixture.runtime)Provenance.json"), runtimeID: fixture.runtime)
            let work = root.appending(path: UUID().uuidString)
            let saves = work.appending(path: "saves"), system = work.appending(path: "system")
            try FileManager.default.createDirectory(at: saves, withIntermediateDirectories: true)
            try FileManager.default.createDirectory(at: system, withIntermediateDirectories: true)
            if fixture.runtime == "ppsspp" {
                try FileManager.default.copyItem(at: Bundle.main.bundleURL.appending(path: "Cores/system/ppsspp/system/PPSSPP"), to: system.appending(path: "PPSSPP"))
            }
            let rom = root.appending(path: fixture.rom)
            var bridge = try RMLLibretroBridge(coreURL: core)
            defer { bridge.stop() }
            try bridge.prepareGame(at: rom, systemDirectory: system, savesDirectory: saves, variables: fixture.variables)
            let vulkan3DS = fixture.runtime == "azahar" && fixture.variables["citra_graphics_api"] == "Vulkan"
            func framePixels() throws -> Data {
                if vulkan3DS {
                    guard let texture = bridge.copyLatestHardwareTexture(), texture.width == 400,
                          texture.height == 480,
                          let image = CIImage(mtlTexture: texture, options: nil),
                          let cgImage = CIContext(mtlDevice: texture.device).createCGImage(image, from: image.extent),
                          let pixels = cgImage.dataProvider?.data as Data? else {
                        throw ProgressStoreError.invalid("3DS Vulkan title did not render a readable Metal frame")
                    }
                    return pixels
                }
                guard let frame = bridge.copyLatestVideoFrame() else {
                    throw ProgressStoreError.invalid("The core did not render a video frame")
                }
                return frame.pixelData
            }
            if let capture = fixture.capture {
                guard vulkan3DS, (1...36_000).contains(capture.frames), capture.every > 0 else { throw ProgressStoreError.invalid("Invalid frame capture") }
                let frames = root.appending(path: "frames")
                try FileManager.default.createDirectory(at: frames, withIntermediateDirectories: true)
                let context = CIContext()
                var written = 0
                for index in 0..<capture.frames {
                    for press in capture.presses where press.frame == index || press.frame + press.length == index {
                        bridge.setDigitalInputPressed(press.frame == index, keyCode: press.button, port: 0)
                    }
                    try bridge.runFrame()
                    guard index.isMultiple(of: capture.every) || index == capture.frames - 1,
                          let texture = bridge.copyLatestHardwareTexture(),
                          let space = CGColorSpace(name: CGColorSpace.sRGB),
                          // Opaque sRGB, as the player draws it (PlayerFrameImage.hardware).
                          let image = CIImage(mtlTexture: texture, options: nil)?
                            .settingAlphaOne(in: CGRect(x: 0, y: 0, width: texture.width, height: texture.height))
                            .matchedToWorkingSpace(from: space)?
                            .oriented(.downMirrored) else { continue }
                    try context.writePNGRepresentation(of: image, to: frames.appending(path: String(format: "frame-%06d.png", index)), format: .RGBA8, colorSpace: space)
                    written += 1
                }
                results.append(["runtime": fixture.runtime, "rom": fixture.rom, "coreSHA256": digest, "capturedFrames": String(written)])
                bridge.stop()
                continue
            }
            for _ in 0..<(["ppsspp", "azahar"].contains(fixture.runtime) ? 180 : 30) { try bridge.runFrame() }
            if fixture.runtime == "azahar", fixture.bootOnly == true {
                if vulkan3DS {
                    guard let texture = bridge.copyLatestHardwareTexture(), texture.width == 400,
                          texture.height == 480,
                          let image = CIImage(mtlTexture: texture, options: nil),
                          CIContext(mtlDevice: texture.device).createCGImage(image, from: image.extent) != nil else {
                        throw ProgressStoreError.invalid("3DS Vulkan title did not render a Metal frame")
                    }
                } else {
                    guard let frame = bridge.copyLatestVideoFrame(), frame.width == 400,
                          frame.height == 480, Set(frame.pixelData).count > 8 else {
                        throw ProgressStoreError.invalid("3DS title did not render both screens")
                    }
                }
                results.append(["runtime": fixture.runtime, "system": fixture.system,
                                "coreSHA256": digest, "boot": "rendered", "rom": fixture.rom])
                bridge.stop()
                continue
            }
            if fixture.runtime == "azahar", fixture.audioOnly == true {
                var audioFrames = 0, nonSilentSamples = 0
                let started = Date()
                for frameIndex in 0..<1200 {
                    if frameIndex == 300 { bridge.setDigitalInputPressed(true, keyCode: 8, port: 0) }
                    if frameIndex == 310 { bridge.setDigitalInputPressed(false, keyCode: 8, port: 0) }
                    try bridge.runFrame()
                    for batch in bridge.drainAudioBatches() {
                        audioFrames += Int(batch.frameCount)
                        nonSilentSamples += batch.sampleData.withUnsafeBytes { bytes in
                            bytes.bindMemory(to: Int16.self).reduce(0) { $0 + ($1 == 0 ? 0 : 1) }
                        }
                    }
                }
                guard Set(try framePixels()).count > 8,
                      audioFrames > 0, nonSilentSamples > 0 else {
                    throw ProgressStoreError.invalid("3DS audio probe failed: frames=\(audioFrames), nonSilent=\(nonSilentSamples)")
                }
                let elapsed = max(Date().timeIntervalSince(started), 0.001)
                results.append(["runtime": fixture.runtime, "system": fixture.system,
                                "coreSHA256": digest, "audioFrames": String(audioFrames),
                                "nonSilentSamples": String(nonSilentSamples),
                                "averageFPS": String(format: "%.1f", 1200 / elapsed),
                                "audioFramesPerSecond": String(format: "%.0f", Double(audioFrames) / elapsed)])
                bridge.stop()
                continue
            }
            let slot = saves.appending(path: "PSP/SAVEDATA/ROMMIO001")
            let threeDSSave = saves.appending(path: "Azahar/rommio-progress/sdmc/3ds/3dstris/config.mp")
            var threeDSGameWrittenSave: Data?
            if fixture.runtime == "ppsspp" {
                try FileManager.default.createDirectory(at: slot.appending(path: "EMPTY"), withIntermediateDirectories: true)
                try Data("game data".utf8).write(to: slot.appending(path: "DATA.BIN"))
                try Data("metadata".utf8).write(to: slot.appending(path: "PARAM.SFO"))
                guard let frame = bridge.copyLatestVideoFrame(), Set(frame.pixelData).count > 8 else { throw ProgressStoreError.invalid("PSP did not render its test scene") }
            }
            if fixture.runtime == "azahar" {
                guard Set(try framePixels()).count > 8 else { throw ProgressStoreError.invalid("3DS did not render both screens") }
                if !vulkan3DS {
                    guard let frame = bridge.copyLatestVideoFrame(), frame.width == 400, frame.height == 480 else { throw ProgressStoreError.invalid("3DS touch layout changed") }
                }
                let initial = try Data(contentsOf: threeDSSave)
                func tap(_ screenX: Double, _ screenY: Double) throws {
                    let x = ((screenX + 40) / 400) * 2 - 1
                    let y = ((screenY + 240) / 480) * 2 - 1
                    bridge.setPointerX(x, y: y, pressed: true, port: 0)
                    for _ in 0..<5 { try bridge.runFrame() }
                    bridge.setPointerX(x, y: y, pressed: false, port: 0)
                    for _ in 0..<5 { try bridge.runFrame() }
                }
                try tap(270, 210) // Language menu.
                try tap(50, 22) // First non-English language.
                try tap(45, 210) // Save.
                let written = try Data(contentsOf: threeDSSave)
                guard written != initial else { throw ProgressStoreError.invalid("3DS game did not write its changed config") }
                threeDSGameWrittenSave = written
            }
            var capture = try NativeProgressCapture.read(bridge, includeState: true)
            let contract = try CartridgePersistence(capture: capture, coreSHA256: digest)
            guard contract.save.system == fixture.system, capture.state != nil else { throw ProgressStoreError.invalid(capture.stateError ?? "Missing snapshot") }
            var memory = capture.memory
            if fixture.runtime != "ppsspp" && fixture.runtime != "azahar", var bytes = memory[0], !bytes.isEmpty {
                bytes[bytes.count/4] ^= 0x5a; bytes[bytes.count*3/4] ^= 0xa5; memory[0] = bytes
            }
            func restore(_ state: Data?) throws {
                try bridge.restoreProgress(Dictionary(uniqueKeysWithValues: memory.map { (NSNumber(value: $0.key), $0.value) }), state: state)
            }
            if fixture.runtime == "ppsspp" { try FileManager.default.removeItem(at: slot) }
            if fixture.runtime == "azahar" { try FileManager.default.removeItem(at: threeDSSave) }
            try restore(capture.state)
            capture = try NativeProgressCapture.read(bridge, includeState: true)
            guard capture.memory == memory else { throw ProgressStoreError.invalid("Incomplete native game-save restore") }
            if fixture.runtime == "azahar", try Data(contentsOf: threeDSSave) != threeDSGameWrittenSave {
                throw ProgressStoreError.invalid("3DS SD game-save tree did not restore")
            }
            for _ in 0..<8 { try bridge.runFrame() }
            let frame = try framePixels()
            let expected = try NativeProgressCapture.read(bridge, includeState: false).memory
            try restore(capture.state)
            for _ in 0..<8 { try bridge.runFrame() }
            let actualFrame = try framePixels()
            let pixelsMatch = fixture.system == "n64" || vulkan3DS
                ? actualFrame.count == frame.count && (zip(frame, actualFrame).map { abs(Int($0)-Int($1)) }.max() ?? 0) <= 1
                : actualFrame == frame
            // Azahar's SD tree also contains emulator-managed files that legitimately change
            // as frames advance. The game's config must remain intact across state replay.
            let memoryMatch = fixture.runtime == "azahar"
                ? (try Data(contentsOf: threeDSSave) == threeDSGameWrittenSave)
                : (try NativeProgressCapture.read(bridge, includeState: false).memory == expected)
            guard pixelsMatch, memoryMatch else { throw ProgressStoreError.invalid("Native snapshot replay differs (pixels=\(pixelsMatch), game=\(memoryMatch))") }
            bridge.stop()
            try FileManager.default.removeItem(at: saves)
            try FileManager.default.createDirectory(at: saves, withIntermediateDirectories: true)
            bridge = try RMLLibretroBridge(coreURL: core)
            try bridge.prepareGame(at: rom, systemDirectory: system, savesDirectory: saves, variables: fixture.variables)
            try restore(nil); try bridge.runFrame()
            guard try NativeProgressCapture.read(bridge, includeState: false).memory == memory else { throw ProgressStoreError.invalid("Cold game-save load differs") }
            bridge.stop()
            bridge = try RMLLibretroBridge(coreURL: core)
            try bridge.prepareGame(at: rom, systemDirectory: system, savesDirectory: saves, variables: fixture.variables)
            try restore(capture.state)
            guard try NativeProgressCapture.read(bridge, includeState: false).memory == memory else { throw ProgressStoreError.invalid("Fresh snapshot restore differs") }
            for _ in 0..<8 { try bridge.runFrame() }
            let freshMemoryMatch = fixture.runtime == "azahar"
                ? (try Data(contentsOf: threeDSSave) == threeDSGameWrittenSave)
                : (try NativeProgressCapture.read(bridge, includeState: false).memory == expected)
            guard freshMemoryMatch else { throw ProgressStoreError.invalid("Fresh snapshot replay differs") }
            var endurance: [String: String] = [:]
            if fixture.runtime == "azahar", let seconds = fixture.enduranceSeconds, seconds > 0 {
                guard seconds <= 600 else { throw ProgressStoreError.invalid("3DS endurance duration exceeds the QA limit") }
                func tap(_ screenX: Double, _ screenY: Double) throws {
                    let x = ((screenX + 40) / 400) * 2 - 1
                    let y = ((screenY + 240) / 480) * 2 - 1
                    bridge.setPointerX(x, y: y, pressed: true, port: 0)
                    for _ in 0..<5 { try bridge.runFrame() }
                    bridge.setPointerX(x, y: y, pressed: false, port: 0)
                    for _ in 0..<5 { try bridge.runFrame() }
                }
                try tap(160, 35) // 3DStris Play.
                try tap(80, 120) // Normal mode.
                let started = Date()
                var frames = 0, audioFrames = 0
                while Date().timeIntervalSince(started) < Double(seconds) {
                    try bridge.runFrame()
                    frames += 1
                    if frames.isMultiple(of: 60) {
                        audioFrames += bridge.drainAudioBatches().reduce(0) { $0 + Int($1.frameCount) }
                        try ProgressDurability.write(JSONSerialization.data(withJSONObject: ["frames": frames, "seconds": Int(Date().timeIntervalSince(started))]), to: root.appending(path: "endurance-progress.json"))
                    }
                }
                audioFrames += bridge.drainAudioBatches().reduce(0) { $0 + Int($1.frameCount) }
                let elapsed = Date().timeIntervalSince(started)
                let fps = Double(frames) / max(elapsed, 0.001)
                endurance = ["durationSeconds": String(Int(elapsed)), "frames": String(frames), "averageFPS": String(format: "%.1f", fps), "audioFrames": String(audioFrames)]
                // 3DStris has no gameplay audio, so audio output needs a separate fixture.
                guard fps >= 50, !(try framePixels()).isEmpty else {
                    throw ProgressStoreError.invalid("3DS endurance failed: \(endurance)")
                }
            }
            bridge.stop()
            let saveCapability = fixture.runtime == "azahar" ? "game-authored-config-restored" :
                (memory[0]?.isEmpty == false ? (fixture.runtime == "handy" ? "eeprom-restored" : "restored") : "empty-capability")
            results.append(["runtime": fixture.runtime, "system": fixture.system, "coreSHA256": digest, "gameSave": saveCapability, "snapshotReplay": "passed", "coldBoot": "passed", "freshSnapshot": "passed", "snapshotBytes": String(capture.state?.count ?? 0)].merging(endurance) { _, new in new })
        }
        try ProgressDurability.write(JSONSerialization.data(withJSONObject: ["status": "passed", "platform": "ios-simulator", "fixtures": results], options: [.prettyPrinted, .sortedKeys]), to: root.appending(path: "probe-result.json"))
    }
}
#endif
