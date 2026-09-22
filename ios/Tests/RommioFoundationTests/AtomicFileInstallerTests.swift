import Foundation
import XCTest
@testable import RommioFoundation

final class AtomicFileInstallerTests: XCTestCase {
    func testErrorsHTMLAndTruncatedDownloadsPreservePreviousFile() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let previous = root.appendingPathComponent("game.rom")
        let incoming = root.appendingPathComponent("download")
        try Data("previous".utf8).write(to: previous)
        for (status, body, length) in [(401,"unauthorized",12), (500,"error",5), (200,"<html>login</html>",18), (200,"short",100), (206,"partial",7)] {
            try Data(body.utf8).write(to: incoming)
            let response = try XCTUnwrap(HTTPURLResponse(url: URL(string: "https://romm.example/game")!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: ["Content-Length": "\(length)"]))
            XCTAssertThrowsError(try AtomicFileInstaller.installDownload(at: incoming, response: response, to: previous))
            XCTAssertEqual(try Data(contentsOf: previous), Data("previous".utf8))
        }
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: root.path).sorted(), ["download", "game.rom"])
    }

    func testSuccessfulReplacementUsesActualBytesAndValidatesCatalogLength() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let previous = root.appendingPathComponent("game.rom")
        let incoming = root.appendingPathComponent("download")
        try Data("old".utf8).write(to: previous)
        try Data([1, 2, 3, 4]).write(to: incoming)
        let response = try XCTUnwrap(HTTPURLResponse(url: URL(string: "https://romm.example/game")!, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["Content-Length": "4"]))
        XCTAssertThrowsError(try AtomicFileInstaller.installDownload(at: incoming, response: response, to: previous, expectedBytes: 9))
        XCTAssertEqual(try Data(contentsOf: previous), Data("old".utf8))
        XCTAssertEqual(try AtomicFileInstaller.installDownload(at: incoming, response: response, to: previous, expectedBytes: 4), 4)
        XCTAssertEqual(try Data(contentsOf: previous), Data([1, 2, 3, 4]))
    }
}
