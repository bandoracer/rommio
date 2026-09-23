import Foundation
import XCTest
import RommioPlayerKit
import ZIPFoundation

final class ThreeDSArchiveTests: XCTestCase {
    func testUserOwnedRomMArchiveMaterializesWhenProvided() throws {
        guard let archivePath = ProcessInfo.processInfo.environment["ROMMIO_3DS_ARCHIVE"] else {
            throw XCTSkip("Set ROMMIO_3DS_ARCHIVE to validate a user-owned RomM ZIP")
        }
        let root = FileManager.default.temporaryDirectory.appending(path: "3ds-archive-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let member = try ThreeDSArchive.materialize(URL(fileURLWithPath: archivePath), in: root)
        XCTAssertEqual(member.url.pathExtension.lowercased(), "3ds")
        XCTAssertGreaterThan((try FileManager.default.attributesOfItem(atPath: member.url.path)[.size] as? NSNumber)?.uint64Value ?? 0, 512)
        XCTAssertEqual(member.sha256.count, 64)
        let handle = try FileHandle(forReadingFrom: member.url)
        defer { try? handle.close() }
        try handle.seek(toOffset: 0x100)
        XCTAssertEqual(try handle.read(upToCount: 4), Data("NCSD".utf8))
    }
    func testSingleCompressedMemberKeepsItsContainerIdentity() throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "3ds-archive-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let bytes = Data(repeating: 0x5a, count: 4096)
        let source = root.appending(path: "source.3ds")
        try bytes.write(to: source)
        let zip = root.appending(path: "game.zip")
        let archive = try Archive(url: zip, accessMode: .create)
        try archive.addEntry(with: "roms/game.3ds", fileURL: source, compressionMethod: .deflate)
        let member = try ThreeDSArchive.materialize(zip, in: root.appending(path: "session/content"))
        XCTAssertEqual(member.path, "roms/game.3ds")
        XCTAssertEqual(member.url.lastPathComponent, "game.3ds")
        XCTAssertEqual(try Data(contentsOf: member.url), bytes)
        XCTAssertEqual(member.sha256, "f302957da5220938a7e3e51a8718c79b9e00dc13ab2119e8cfc978f041720382")
        XCTAssertTrue(FileManager.default.fileExists(atPath: zip.path))
    }

    func testAmbiguousArchiveCannotSelectTheWrongGame() throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "3ds-archive-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let source = root.appending(path: "source.3ds")
        try Data(repeating: 1, count: 512).write(to: source)
        let zip = root.appending(path: "game.zip")
        let archive = try Archive(url: zip, accessMode: .create)
        try archive.addEntry(with: "first.3ds", fileURL: source)
        try archive.addEntry(with: "second.3ds", fileURL: source)
        XCTAssertThrowsError(try ThreeDSArchive.materialize(zip, in: root.appending(path: "content")))
    }
}
