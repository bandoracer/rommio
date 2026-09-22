import Foundation
import XCTest
import RommioPlayerKit

final class ProgressFirmwareTests: XCTestCase {
    func testRejectsUnrecognizedBIOSWithoutReplacingExistingCopy() throws {
        let root = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let original = root.appending(path: "scph5501.bin")
        let selected = root.appending(path: "selected.bin")
        try Data("preserved older file".utf8).write(to: original)
        try Data("not a BIOS".utf8).write(to: selected)
        XCTAssertThrowsError(try ProgressFirmware.importBIOS(selected, destination: root))
        XCTAssertEqual(try Data(contentsOf: original), Data("preserved older file".utf8))
        XCTAssertFalse(ProgressFirmware.valid(original, name: "scph5501.bin"))
    }
}
