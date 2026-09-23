import Foundation
import XCTest
import RommioPlayerKit
import RommioPlayerBridge
@testable import RommioFoundation

@MainActor
final class ExpansionProgressTests: XCTestCase {
    struct Fixture: Decodable { let runtime: String; let system: String; let rom: String; let variables: [String: String] }
    func testNativeGameSavesAndSnapshots() throws {
        guard let path = ProcessInfo.processInfo.environment["ROMMIO_EXPANSION_FIXTURES"] else {
            throw XCTSkip("Set ROMMIO_EXPANSION_FIXTURES to cases.json")
        }
        let fixtures = try JSONDecoder().decode([Fixture].self, from: Data(contentsOf: URL(fileURLWithPath: path)))
        let repo = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        for fixture in fixtures {
            let root = FileManager.default.temporaryDirectory.appending(path: "expansion-\(UUID().uuidString)")
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            defer { try? FileManager.default.removeItem(at: root) }
            let core = repo.appending(path: "build/cartridge-cores/\(fixture.runtime)/macosx-\(CartridgePersistence.architecture)/output/\(fixture.runtime).dylib")
            let saves = root.appending(path: "saves"), system = root.appending(path: "system")
            try FileManager.default.createDirectory(at: saves, withIntermediateDirectories: true)
            try FileManager.default.createDirectory(at: system, withIntermediateDirectories: true)
            if fixture.runtime == "ppsspp" {
                try FileManager.default.copyItem(at: core.deletingLastPathComponent().appending(path: "system/PPSSPP"), to: system.appending(path: "PPSSPP"))
            }
            let longDirectory = root.appending(path: Array(repeating: String(repeating: "p", count: 64), count: 3).joined(separator: "/"))
            try FileManager.default.createDirectory(at: longDirectory, withIntermediateDirectories: true)
            let rom = longDirectory.appending(path: URL(fileURLWithPath: fixture.rom).lastPathComponent)
            try FileManager.default.copyItem(at: URL(fileURLWithPath: fixture.rom), to: rom)
            XCTAssertGreaterThan(rom.path.utf8.count, 255)
            var bridge = try RMLLibretroBridge(coreURL: core)
            try bridge.prepareGame(at: rom, systemDirectory: system, savesDirectory: saves, variables: fixture.variables)
            for _ in 0..<(fixture.runtime == "ppsspp" ? 180 : 30) { try bridge.runFrame() }
            let saveFolder = saves.appending(path: "PSP/SAVEDATA/ROMMIO001")
            if fixture.runtime == "ppsspp" {
                try FileManager.default.createDirectory(at: saveFolder.appending(path: "EMPTY"), withIntermediateDirectories: true)
                try Data("game progress".utf8).write(to: saveFolder.appending(path: "DATA.BIN"))
                try Data("slot metadata".utf8).write(to: saveFolder.appending(path: "PARAM.SFO"))
            }
            var capture = try NativeProgressCapture.read(bridge, includeState: true)
            let contract = try CartridgePersistence(capture: capture, coreSHA256: ProgressFileHash.sha256(core))
            XCTAssertEqual(contract.save.system, fixture.system)
            XCTAssertNotNil(capture.state, capture.stateError ?? fixture.system)
            XCTAssertNotNil(bridge.copyLatestVideoFrame(), fixture.system)
            if fixture.runtime == "ppsspp" {
                XCTAssertGreaterThan(Set(bridge.copyLatestVideoFrame()!.pixelData).count, 8, "The PSP fixture must render a scene, not a blank frame")
                let snapshot = try XCTUnwrap(capture.state)
                XCTAssertLessThan(snapshot.count, 8 * 1024 * 1024, "Compress before allocating a mobile host snapshot")
                XCTAssertEqual(snapshot.prefix(8), Data("RMSZ0001".utf8))
                XCTAssertThrowsError(try bridge.unserializeState(Data(snapshot.dropLast())))
                XCTAssertThrowsError(try bridge.unserializeState(snapshot + Data([0])))
                var corrupt = snapshot; corrupt[0] ^= 1
                XCTAssertThrowsError(try bridge.unserializeState(corrupt))
            }
            var memory = capture.memory
            if fixture.runtime != "ppsspp", var ram = memory[0], !ram.isEmpty {
                ram[ram.count / 4] ^= 0x5a
                ram[ram.count * 3 / 4] ^= 0xa5 // Both PlayStation cards / CD RAM regions.
                memory[0] = ram
            }
            if fixture.runtime == "ppsspp" {
                try? FileManager.default.removeItem(at: saveFolder)
                try Data("unexpected later file".utf8).write(to: saves.appending(path: "LATER.BIN"))
            }
            try bridge.restoreProgress(Dictionary(uniqueKeysWithValues: memory.map { (NSNumber(value: $0.key), $0.value) }), state: capture.state)
            capture = try NativeProgressCapture.read(bridge, includeState: true)
            XCTAssertEqual(capture.memory, memory)
            for _ in 0..<8 { try bridge.runFrame() }
            let expectedFrame = bridge.copyLatestVideoFrame()?.pixelData
            let expectedMemory = try NativeProgressCapture.read(bridge, includeState: false).memory
            try bridge.restoreProgress(Dictionary(uniqueKeysWithValues: memory.map { (NSNumber(value: $0.key), $0.value) }), state: capture.state)
            for _ in 0..<8 { try bridge.runFrame() }
            if fixture.system == "n64", let expectedFrame, let actualFrame = bridge.copyLatestVideoFrame()?.pixelData {
                // Angrylion's VI gamma dither uses renderer-local noise outside the
                // Mupen state codec. Measured replay differs by at most one channel
                // level; still reject shifted frames or different game content.
                XCTAssertEqual(actualFrame.count, expectedFrame.count)
                XCTAssertLessThanOrEqual(zip(actualFrame, expectedFrame).map { abs(Int($0)-Int($1)) }.max() ?? 0, 1)
            } else { XCTAssertEqual(bridge.copyLatestVideoFrame()?.pixelData, expectedFrame, fixture.system) }
            XCTAssertEqual(try NativeProgressCapture.read(bridge, includeState: false).memory, expectedMemory, fixture.system)
            bridge.stop()
            if fixture.runtime == "ppsspp" {
                try FileManager.default.removeItem(at: saves)
                try FileManager.default.createDirectory(at: saves, withIntermediateDirectories: true)
            }
            bridge = try RMLLibretroBridge(coreURL: core)
            try bridge.prepareGame(at: rom, systemDirectory: system, savesDirectory: saves, variables: fixture.variables)
            try bridge.restoreProgress(Dictionary(uniqueKeysWithValues: memory.map { (NSNumber(value: $0.key), $0.value) }), state: nil)
            try bridge.runFrame()
            XCTAssertEqual(try NativeProgressCapture.read(bridge, includeState: false).memory, memory, "\(fixture.system) independent game-save load")
            bridge.stop()
            bridge = try RMLLibretroBridge(coreURL: core)
            try bridge.prepareGame(at: rom, systemDirectory: system, savesDirectory: saves, variables: fixture.variables)
            try bridge.restoreProgress(Dictionary(uniqueKeysWithValues: memory.map { (NSNumber(value: $0.key), $0.value) }), state: capture.state)
            XCTAssertEqual(try NativeProgressCapture.read(bridge, includeState: false).memory, memory, "Immediate snapshot restore in a fresh core")
            for _ in 0..<8 { try bridge.runFrame() }
            XCTAssertEqual(try NativeProgressCapture.read(bridge, includeState: false).memory, expectedMemory)
            bridge.stop()
            if fixture.runtime == "ppsspp" {
                XCTAssertEqual(try Data(contentsOf: saveFolder.appending(path: "DATA.BIN")), Data("game progress".utf8))
                XCTAssertFalse(FileManager.default.fileExists(atPath: saves.appending(path: "LATER.BIN").path))
            } else if fixture.runtime == "handy", let eeprom = memory[0], !eeprom.isEmpty {
                let file = saves.appending(path: rom.deletingPathExtension().lastPathComponent + ".eeprom")
                XCTAssertEqual(try Data(contentsOf: file), eeprom, "Lynx EEPROM must persist independently of snapshots")
                XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: saves.path), [file.lastPathComponent])
            } else {
                XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: saves.path).isEmpty, "Unexpected file-backed save inventory for \(fixture.system)")
            }
            print("PASS expansion \(fixture.runtime) \(fixture.system): game-only cold boot and snapshot replay")
        }
    }
}
