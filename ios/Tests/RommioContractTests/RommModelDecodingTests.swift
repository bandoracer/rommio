import Foundation
import XCTest
@testable import RommioContract

final class RommModelDecodingTests: XCTestCase {
    func testRomDecodesWhenPlatformMetadataIsOmitted() throws {
        let payload = """
        {
          "id": 99,
          "name": "Galaga: Demons of Death",
          "platform_id": 12,
          "files": [
            {
              "id": 7,
              "rom_id": 99,
              "file_name": "galaga.nes",
              "file_size_bytes": 40976
            }
          ]
        }
        """.data(using: .utf8)!

        let rom = try JSONDecoder().decode(RomDTO.self, from: payload)

        XCTAssertEqual(rom.id, 99)
        XCTAssertEqual(rom.platformID, 12)
        XCTAssertEqual(rom.platformName, "")
        XCTAssertEqual(rom.platformSlug, "")
        XCTAssertEqual(rom.fsName, "")
        XCTAssertEqual(rom.files.first?.fileExtension, "")
    }

    func testCollectionDecodesWhenOptionalArraysAndFlagsAreOmitted() throws {
        let payload = """
        {
          "id": 5,
          "name": "Arcade Classics"
        }
        """.data(using: .utf8)!

        let collection = try JSONDecoder().decode(CollectionResponseDTO.self, from: payload)

        XCTAssertEqual(collection.id, 5)
        XCTAssertEqual(collection.description, "")
        XCTAssertTrue(collection.romIDs.isEmpty)
        XCTAssertEqual(collection.romCount, 0)
        XCTAssertTrue(collection.pathCoversSmall.isEmpty)
        XCTAssertTrue(collection.pathCoversLarge.isEmpty)
        XCTAssertFalse(collection.isPublic)
        XCTAssertFalse(collection.isFavorite)
    }
}
