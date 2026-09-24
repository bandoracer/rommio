import Foundation
import XCTest
@testable import RommioContract

/// RomM metadata decode (docs/play-data-local.md §6): optional, lenient, units by magnitude.
final class RomMetadataTests: XCTestCase {
    private func rom(_ json: String) throws -> RomDTO {
        try JSONDecoder().decode(RomDTO.self, from: Data(json.utf8))
    }

    func testDecodesRomM52MetadatumScreenshotsRegionsAndLanguages() throws {
        let rom = try rom("""
        {"id": 7, "name": "Super Metroid", "platform_id": 3, "platform_name": "Super Nintendo", "platform_slug": "snes",
         "metadatum": {"genres": ["Platform", " Adventure ", "", "Platform"], "franchises": ["Metroid"],
                       "companies": [{"name": "Nintendo"}, "Intelligent Systems"], "player_count": "1-2",
                       "first_release_date": 764035200, "average_rating": 94.6, "game_modes": ["Single player"]},
         "merged_screenshots": ["/assets/romm/resources/roms/3/7/screenshots/0.png"],
         "regions": ["USA"], "languages": ["English", "Japanese"]}
        """)
        let metadata = rom.metadata
        XCTAssertEqual(rom.metadataFields, .all)
        XCTAssertEqual(metadata.firstReleaseEpochMs, 764_035_200_000)
        XCTAssertEqual(metadata.releaseYear, 1994)
        XCTAssertEqual(rom.releaseYear, 1994)
        XCTAssertEqual(rom.platformAndYear, "Super Nintendo · 1994")
        XCTAssertEqual(metadata.genres, ["Platform", "Adventure"])
        XCTAssertEqual(metadata.franchises, ["Metroid"])
        XCTAssertEqual(metadata.companies, ["Nintendo", "Intelligent Systems"])
        XCTAssertEqual(metadata.publisherLine, ["Nintendo", "Intelligent Systems"])
        XCTAssertEqual(metadata.playersText, "1–2")
        XCTAssertEqual(metadata.ratingOutOf100, 95)
        XCTAssertEqual(metadata.screenshots, ["/assets/romm/resources/roms/3/7/screenshots/0.png"])
        XCTAssertEqual(metadata.regions, ["USA"])
        XCTAssertEqual(metadata.languages, ["English", "Japanese"])
    }

    func testReleaseDateUnitsByMagnitudeAndFormats() {
        XCTAssertEqual(RomMetadata.releaseEpochMs(.number(764_035_200)), 764_035_200_000, "seconds")
        XCTAssertEqual(RomMetadata.releaseEpochMs(.number(764_035_200_000)), 764_035_200_000, "milliseconds")
        XCTAssertEqual(RomMetadata.releaseEpochMs(.string(" 764035200 ")), 764_035_200_000, "numeric string")
        XCTAssertEqual(RomMetadata.releaseEpochMs(.string("764035200.0")), 764_035_200_000, "decimal string")
        XCTAssertEqual(RomMetadata.releaseEpochMs(.string("1994-03-19")), 764_035_200_000, "ISO date at UTC midnight")
        XCTAssertEqual(RomMetadata.releaseEpochMs(.string("1994-03-19T08:00:00Z")), 764_035_200_000, "ISO date-time uses its date")
        XCTAssertNil(RomMetadata.releaseEpochMs(.number(0)), "0 means none")
        XCTAssertNil(RomMetadata.releaseEpochMs(.string("1994-02-30")), "invalid date")
        XCTAssertNil(RomMetadata.releaseEpochMs(.string("soon")))
        XCTAssertNil(RomMetadata.releaseEpochMs(.bool(true)))
        XCTAssertNil(RomMetadata.releaseEpochMs(nil))
        // Just under the threshold is seconds; just over is milliseconds.
        XCTAssertEqual(RomMetadata.releaseEpochMs(.number(100_000_000_000)), 100_000_000_000_000)
        XCTAssertEqual(RomMetadata.releaseEpochMs(.number(100_000_000_001)), 100_000_000_001)
        // The release year is the UTC calendar year (31 Dec 23:30 UTC stays in that year).
        XCTAssertEqual(RomMetadata(firstReleaseEpochMs: 946_683_000_000).releaseYear, 1999)
    }

    func testWrongTypesAreIgnoredFieldByFieldNeverFailingThePayload() throws {
        let rom = try rom("""
        {"id": 8, "name": "Odd", "metadatum": {"genres": "Action", "companies": [1, {"id": 2}, {"name": "Sega"}],
          "player_count": 0, "first_release_date": {"seconds": 5}, "average_rating": "not a number", "franchises": null},
         "merged_screenshots": {"0": "a.png"}, "regions": [null, "EUR"], "languages": 5}
        """)
        XCTAssertEqual(rom.name, "Odd")
        XCTAssertEqual(rom.metadata.genres, [])
        XCTAssertEqual(rom.metadata.companies, ["Sega"])
        XCTAssertNil(rom.metadata.playerCount)
        XCTAssertNil(rom.metadata.firstReleaseEpochMs)
        XCTAssertNil(rom.metadata.averageRating)
        XCTAssertEqual(rom.metadata.screenshots, [])
        XCTAssertEqual(rom.metadata.regions, ["EUR"])
        XCTAssertEqual(rom.metadata.languages, [])
        XCTAssertEqual(rom.metadataFields, .all, "present (non-null) fields count as carried, even when unusable")
    }

    func testPlayersAndRatingRules() {
        XCTAssertEqual(RomMetadata.fromRemote(metadatum: .object(["player_count": .number(4)]), mergedScreenshots: nil, regions: nil, languages: nil).playerCount, "4")
        XCTAssertNil(RomMetadata.fromRemote(metadatum: .object(["player_count": .string(" ")]), mergedScreenshots: nil, regions: nil, languages: nil).playerCount)
        XCTAssertNil(RomMetadata.fromRemote(metadatum: .object(["average_rating": .number(0)]), mergedScreenshots: nil, regions: nil, languages: nil).averageRating)
        XCTAssertEqual(RomMetadata(averageRating: 130).ratingOutOf100, 100, "clamped to 100")
        XCTAssertEqual(RomMetadata(averageRating: 85.5).ratingOutOf100, 86, "rounded")
        XCTAssertEqual(RomMetadata(companies: ["Nintendo"], publishers: ["Nintendo of America"]).publisherLine, ["Nintendo of America"])
    }

    func testPartialPayloadsKeepWhatTheyDoNotCarry() throws {
        let stored = RomMetadata(firstReleaseEpochMs: 764_035_200_000, genres: ["Platform"], screenshots: ["a.png"], regions: ["USA"], languages: ["English"])
        let list = try rom(#"{"id": 7, "name": "Super Metroid", "regions": ["Japan"]}"#)
        XCTAssertEqual(list.metadataFields, [.regions])
        let merged = RomMetadata.merge(previous: stored, incoming: list.metadata, carried: list.metadataFields)
        XCTAssertEqual(merged.firstReleaseEpochMs, 764_035_200_000)
        XCTAssertEqual(merged.genres, ["Platform"])
        XCTAssertEqual(merged.screenshots, ["a.png"])
        XCTAssertEqual(merged.regions, ["Japan"], "a payload with a field replaces it")
        XCTAssertEqual(merged.languages, ["English"])
        let nullMetadatum = try rom(#"{"id": 7, "metadatum": null}"#)
        XCTAssertEqual(nullMetadatum.metadataFields, [], "null counts as absent")
        let older = try rom(#"{"id": 9, "name": "Old server"}"#)
        XCTAssertTrue(older.metadata.isEmpty)
        XCTAssertNil(older.releaseYear)
    }

    func testCachedPayloadRoundTripsMetadata() throws {
        let remote = try rom("""
        {"id": 7, "name": "Super Metroid", "platform_name": "Super Nintendo",
         "metadatum": {"first_release_date": 764035200, "genres": ["Platform"], "player_count": "1"}, "merged_screenshots": ["s.png"]}
        """)
        let cached = try JSONDecoder().decode(RomDTO.self, from: JSONEncoder().encode(remote))
        XCTAssertEqual(cached.metadata, remote.metadata)
        XCTAssertEqual(cached.metadataFields, .all, "the cache is authoritative for what it stored")
        XCTAssertEqual(cached.name, "Super Metroid")
        let bare = try JSONDecoder().decode(RomDTO.self, from: JSONEncoder().encode(RomDTO(id: 1, name: "Bare")))
        XCTAssertTrue(bare.metadata.isEmpty)
    }
}
