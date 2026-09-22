#if DEBUG && canImport(UIKit)
import Foundation
import RommioFoundation
import RommioPlayerKit
import RommioPlayerBridge

/// Opt-in native qualification using the exact signed cores in the installed app.
@MainActor
extension RommioAppModel {
    func runExpansionNativeProbe(root: URL) throws {
        struct Fixture: Decodable { let runtime: String; let system: String; let rom: String; let variables: [String: String] }
        let fixtures = try JSONDecoder().decode([Fixture].self, from: Data(contentsOf: root.appending(path: "cases.json")))
        var results: [[String: String]] = []
        for fixture in fixtures {
            guard PinnedAppleCore.cartridgeRuntimes.contains(fixture.runtime), fixture.rom == URL(fileURLWithPath: fixture.rom).lastPathComponent else { throw ProgressStoreError.invalid("Invalid native fixture") }
            let core = Bundle.main.bundleURL.appending(path: "Cores/\(fixture.runtime).dylib")
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
            for _ in 0..<(fixture.runtime == "ppsspp" ? 180 : 30) { try bridge.runFrame() }
            let slot = saves.appending(path: "PSP/SAVEDATA/ROMMIO001")
            if fixture.runtime == "ppsspp" {
                try FileManager.default.createDirectory(at: slot.appending(path: "EMPTY"), withIntermediateDirectories: true)
                try Data("game data".utf8).write(to: slot.appending(path: "DATA.BIN"))
                try Data("metadata".utf8).write(to: slot.appending(path: "PARAM.SFO"))
                guard let frame = bridge.copyLatestVideoFrame(), Set(frame.pixelData).count > 8 else { throw ProgressStoreError.invalid("PSP did not render its test scene") }
            }
            var capture = try NativeProgressCapture.read(bridge, includeState: true)
            let contract = try CartridgePersistence(capture: capture, coreSHA256: digest)
            guard contract.save.system == fixture.system, capture.state != nil else { throw ProgressStoreError.invalid(capture.stateError ?? "Missing snapshot") }
            var memory = capture.memory
            if fixture.runtime != "ppsspp", var bytes = memory[0], !bytes.isEmpty {
                bytes[bytes.count/4] ^= 0x5a; bytes[bytes.count*3/4] ^= 0xa5; memory[0] = bytes
            }
            func restore(_ state: Data?) throws {
                try bridge.restoreProgress(Dictionary(uniqueKeysWithValues: memory.map { (NSNumber(value: $0.key), $0.value) }), state: state)
            }
            if fixture.runtime == "ppsspp" { try FileManager.default.removeItem(at: slot) }
            try restore(capture.state)
            capture = try NativeProgressCapture.read(bridge, includeState: true)
            guard capture.memory == memory else { throw ProgressStoreError.invalid("Incomplete native game-save restore") }
            for _ in 0..<8 { try bridge.runFrame() }
            let frame = bridge.copyLatestVideoFrame()!.pixelData
            let expected = try NativeProgressCapture.read(bridge, includeState: false).memory
            try restore(capture.state)
            for _ in 0..<8 { try bridge.runFrame() }
            let actualFrame = bridge.copyLatestVideoFrame()!.pixelData
            let pixelsMatch = fixture.system == "n64" ? actualFrame.count == frame.count && (zip(frame, actualFrame).map { abs(Int($0)-Int($1)) }.max() ?? 0) <= 1 : actualFrame == frame
            guard pixelsMatch, try NativeProgressCapture.read(bridge, includeState: false).memory == expected else { throw ProgressStoreError.invalid("Native snapshot replay differs") }
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
            guard try NativeProgressCapture.read(bridge, includeState: false).memory == expected else { throw ProgressStoreError.invalid("Fresh snapshot replay differs") }
            bridge.stop()
            results.append(["runtime": fixture.runtime, "system": fixture.system, "coreSHA256": digest, "gameSave": "passed", "snapshotReplay": "passed", "coldBoot": "passed", "freshSnapshot": "passed", "snapshotBytes": String(capture.state?.count ?? 0)])
        }
        try ProgressDurability.write(JSONSerialization.data(withJSONObject: ["status": "passed", "platform": "ios-simulator", "fixtures": results], options: [.prettyPrinted, .sortedKeys]), to: root.appending(path: "probe-result.json"))
    }
}
#endif
