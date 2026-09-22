import Foundation
import XCTest
import RommioContract
@testable import RommioFoundation

final class ProgressProtocolTests: XCTestCase {
    struct Entry: Decodable { var file: String; var bindingKey: String?; var payloadSha256: String?; var stateKey: String? }
    struct Index: Decodable { var valid: [Entry]; var invalid: [Entry] }
    private var fixtures: URL {
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().appending(path: "tests/fixtures/progress-v2")
    }
    private func bytes(_ name: String) throws -> Data { try Data(contentsOf: fixtures.appending(path: name)) }

    func testSharedValidFixturesPreserveMeaningAndKeys() throws {
        let index = try JSONDecoder().decode(Index.self, from: bytes("index.json"))
        for entry in index.valid {
            let raw = try bytes(entry.file)
            let op = try ProgressProtocol.decode(raw)
            XCTAssertEqual(entry.payloadSha256, ProgressProtocol.sha256(raw), entry.file)
            XCTAssertEqual(entry.bindingKey, ProgressProtocol.bindingKey(op.binding), entry.file)
            if let key = entry.stateKey { XCTAssertEqual(key, try ProgressProtocol.stateCompatibilityKey(op.checkpoints[0]), entry.file) }
            XCTAssertEqual(op, try ProgressProtocol.decode(ProgressProtocol.encode(op)), entry.file)
        }
    }

    func testSharedInvalidFixturesAreRejected() throws {
        let index = try JSONDecoder().decode(Index.self, from: bytes("index.json"))
        for entry in index.invalid { XCTAssertThrowsError(try ProgressProtocol.decode(bytes(entry.file)), entry.file) }
    }

    func testUnsupportedSchemaIsDistinctFromCorruption() throws {
        XCTAssertThrowsError(try ProgressProtocol.decode(bytes("unknown-version.json"))) { error in
            XCTAssertEqual(error as? ProgressProtocolError, .unsupportedSchema(4))
        }
    }
}
