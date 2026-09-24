import Foundation
import XCTest
@testable import RommioContract
@testable import RommioFoundation
@testable import RommioUI

/// Phase 4 page content: details copy and order, timeline stops, save lines and
/// Aurora's eras (docs/play-data-local.md, docs/theme-layouts.md).
final class PlayDataPresentationTests: XCTestCase {
    private func entry(_ kind: SaveKind, reason: String, at: Int64, label: String? = nil, resume: Bool = false, slot: Int? = nil,
                       sync: SaveSyncStatus = .waiting, device: String = "This iPhone") -> SaveEntry {
        SaveEntry(key: SaveKey(namespaceId: "n", contentId: "c", checkpointId: UUID().uuidString.lowercased()), romId: 7, fileId: 70,
                  operationId: "op", kind: kind, capturedAtEpochMs: at, captureReason: reason, deviceId: "d", fromThisDevice: device != "Another device",
                  deviceLabel: device, sync: sync, label: label, slotNumber: slot, isCurrentResume: resume, isCurrentSlot: slot != nil,
                  loadable: kind != .gameSave, thumbnailURL: nil)
    }

    func testGameDetailsArePlainTextInTheSharedOrder() {
        let metadata = RomMetadata(firstReleaseEpochMs: 764_035_200_000, genres: ["Platform", "Adventure"], franchises: ["Metroid"],
                                   companies: ["Nintendo"], developers: ["Intelligent Systems"], playerCount: "1-2", averageRating: 86.4,
                                   regions: ["USA"], languages: ["English", "Japanese"])
        XCTAssertEqual(GameDetailItem.items(metadata).map(\.label), ["Released", "Genres", "Companies", "Developer", "Series", "Players", "Rating", "Region", "Languages"])
        XCTAssertEqual(GameDetailItem.items(metadata).map(\.value), ["1994", "Platform, Adventure", "Nintendo", "Intelligent Systems", "Metroid", "1–2", "86 / 100", "USA", "English, Japanese"])
        XCTAssertEqual(GameDetailItem.items(RomMetadata(genres: ["RPG"], publishers: ["Square"])).map(\.label), ["Genre", "Publisher"])
        XCTAssertTrue(GameDetailItem.items(.empty).isEmpty)
    }

    func testTimelineStopsAndSaveLines() {
        let saves = GameSaves(
            resume: entry(.resumePoint, reason: "exit", at: 9_000, resume: true),
            saveStates: [entry(.saveState, reason: "manual", at: 7_000, label: "Before the boss", slot: 1)],
            history: (0..<8).map { entry(.resumePoint, reason: "pause", at: 6_000 - Int64($0)) },
            gameSaveData: [entry(.gameSave, reason: "exit", at: 9_000)])
        let stops = SaveTimelineItem.stops(saves)
        XCTAssertEqual(stops.count, 8, "resume, one save state and six earlier resume points")
        XCTAssertEqual(stops.first?.kindTitle, "Automatic resume")
        XCTAssertEqual(stops.first?.title, "Resume point")
        XCTAssertEqual(stops[1].kindTitle, "Save state · Slot 1")
        XCTAssertEqual(stops[1].title, "Before the boss")
        XCTAssertEqual(stops[2].title, "Paused")
        XCTAssertEqual(SaveFormat.count(saves), "1 resume point · 1 save state")

        let named = entry(.saveState, reason: "manual", at: 0, label: "Before the boss", slot: 2)
        XCTAssertTrue(SaveFormat.recentDetail(named).hasPrefix("“Before the boss” · Save state · Slot 2 · "))
        XCTAssertTrue(SaveFormat.recentDetail(named).hasSuffix(" · This iPhone · Waiting to sync"))
        XCTAssertFalse(SaveFormat.whereWhen(entry(.resumePoint, reason: "exit", at: 0, sync: .unknown)).contains("·  ·"))
        XCTAssertTrue(SaveFormat.gameDetail(entry(.resumePoint, reason: "exit", at: 0)).hasPrefix("Left game · "))
        XCTAssertFalse(SaveFormat.gameDetail(entry(.gameSave, reason: "exit", at: 0)).hasPrefix("Left game"))
        XCTAssertEqual(relabelled(named, nil).title, "Save state · Slot 2")
        XCTAssertNil(relabelled(entry(.gameSave, reason: "exit", at: 0), "x").label, "game-save data is never named")
    }

    func testErasGroupReleaseYearsAndLeaveOutGamesWithoutOne() {
        func rom(_ id: Int, _ year: Int?) -> RomDTO {
            var rom = RomDTO(id: id, name: "Game \(id)")
            if let year { rom.metadata.firstReleaseEpochMs = Int64((Double(year - 1970) * 365.25 + 40) * 86_400_000) }
            return rom
        }
        let groups = LibraryEra.groups([rom(1, 1994), rom(2, 1986), rom(3, 1991), rom(4, nil), rom(5, 2019), rom(6, 1995), rom(7, 1987)])
        XCTAssertEqual(groups.map(\.era.id), ["e80", "e16", "e12"])
        XCTAssertEqual(groups.map(\.era.name), ["Arcades & cartridges", "The 16-bit years", "Since 2012"])
        XCTAssertEqual(groups[1].games.map(\.id), [3, 1, 6], "by release year")
        XCTAssertEqual(groups[0].span, "1986–87")
        XCTAssertEqual(groups[2].span, "2019")
        XCTAssertEqual(LibraryEra.span([1998, 2001]), "1998–2001")
        XCTAssertEqual(LibraryEra.era(of: 2011)?.id, "e00")
        XCTAssertEqual(LibraryEra.era(of: 1996)?.name, "Polygons & pockets")
        XCTAssertTrue(LibraryEra.groups([rom(4, nil)]).isEmpty, "games without a year are in no era")
        XCTAssertEqual(rom(1, 1994).platformAndYear, "1994")
    }
}
