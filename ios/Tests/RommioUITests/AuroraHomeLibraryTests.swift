import CoreGraphics
import Foundation
import XCTest
@testable import RommioContract
@testable import RommioFoundation
@testable import RommioUI

/// Aurora's Play and Library (docs/theme-layouts.md): what's in play and in what order,
/// the picks and their reasons, the wall's groups, and trimming a screen's letterbox.
final class AuroraHomeLibraryTests: XCTestCase {
    private func rom(_ id: Int, _ name: String, platform: String = "gba", year: Int? = nil, genres: [String] = [],
                     series: [String] = [], rating: Double? = nil) -> RomDTO {
        var rom = RomDTO(id: id, name: name, platformSlug: platform)
        rom.metadata = RomMetadata(firstReleaseEpochMs: year.map { Int64((Double($0 - 1970) * 365.25 + 40) * 86_400_000) },
                                   genres: genres, franchises: series, averageRating: rating)
        return rom
    }

    private func save(_ romID: Int, at date: Date) -> SaveEntry {
        SaveEntry(key: SaveKey(namespaceId: "n", contentId: "c\(romID)", checkpointId: UUID().uuidString.lowercased()), romId: Int64(romID), fileId: nil,
                  operationId: "op", kind: .resumePoint, capturedAtEpochMs: Int64(date.timeIntervalSince1970 * 1000), captureReason: "exit",
                  deviceId: "d", fromThisDevice: true, deviceLabel: "This iPhone", sync: .synced, label: nil, slotNumber: nil,
                  isCurrentResume: true, isCurrentSlot: false, loadable: true, thumbnailURL: nil)
    }

    // MARK: Play

    func testInPlayLeadsWithWhatThisDevicePlayedThenRomM() {
        let now = Date()
        let majora = rom(1, "Majora's Mask"), fireEmblem = rom(2, "Fire Emblem"), kart = rom(3, "Mario Kart DS"), galaga = rom(4, "Galaga")
        let library = [1: majora, 2: fireEmblem, 3: kart, 4: galaga]
        let order = PlayContent.inPlay(
            romm: [kart, galaga, fireEmblem],
            latest: [1: save(1, at: now.addingTimeInterval(-3_600)), 2: save(2, at: now.addingTimeInterval(-7_200))],
            playtimes: [GamePlaytime(romID: 4, playtime: 60, sessionCount: 1, lastPlayedAt: now.addingTimeInterval(-86_400 * 2))],
            library: library)
        XCTAssertEqual(order.map(\.id), [1, 2, 4, 3], "saves and sessions here first (newest first), then RomM's list")
        XCTAssertEqual(PlayContent.inPlay(romm: [kart], latest: [:], playtimes: [], library: [:]).map(\.id), [3], "nothing played here: RomM's order")
    }

    func testPicksComeWithReasonsFromTheData() {
        let now = Date()
        let hero = rom(1, "Majora's Mask", genres: ["Adventure"])
        let fireEmblem = rom(2, "Fire Emblem", genres: ["Role-playing (RPG)", "Strategy"])
        let emerald = rom(3, "Pokémon Emerald", genres: ["Role-playing (RPG)"], rating: 88)
        let sudoku = rom(4, "Platinum Sudoku", platform: "nds", genres: ["Puzzle"])
        let guitar = rom(5, "Guitar Hero", platform: "ps2", genres: ["Music"], rating: 95)
        let playtimes = [GamePlaytime(romID: 2, playtime: 1_800, sessionCount: 3, lastPlayedAt: now.addingTimeInterval(-86_400))]
        let picks = PlayPicks.build(library: [hero, fireEmblem, emerald, sudoku, guitar], playtimes: playtimes, continuing: [hero, fireEmblem],
                                    installed: [3], playable: { $0.platformSlug != "ps2" }, artwork: { _ in nil })
        XCTAssertEqual(picks.map(\.kind), [.keepGoing, .somethingNew, .quickHit])
        XCTAssertEqual(picks.map(\.rom.id), [2, 3, 4], "never the hero; browse-only games are never picked")
        XCTAssertEqual(picks[0].reason, "30 min in · 3 sessions")
        XCTAssertEqual(picks[1].reason, "Rated 88 · Role-playing, like Fire Emblem")
        XCTAssertTrue(picks[1].installed)
        XCTAssertEqual(picks[2].reason, "Puzzle")
        XCTAssertTrue(PlayPicks.build(library: [], playtimes: [], continuing: [], installed: [], playable: { _ in true }, artwork: { _ in nil }).isEmpty)
    }

    // MARK: Library

    private func library() -> LibraryContent {
        var content = LibraryContent()
        let gba = PlatformDTO(id: 1, slug: "gba", name: "Game Boy Advance", fsSlug: "gba", romCount: 3)
        let gb = PlatformDTO(id: 2, slug: "gb", name: "Game Boy", fsSlug: "gb", romCount: 1)
        let ps2 = PlatformDTO(id: 3, slug: "ps2", name: "PlayStation 2", fsSlug: "ps2", romCount: 1)
        content.sections = [
            PlatformSupportSection(supportTier: .touchSupported, title: "Touch", supportingText: "", platforms: [gba, gb]),
            PlatformSupportSection(supportTier: .unsupported, title: "Browse", supportingText: "", platforms: [ps2]),
        ]
        content.games = [
            rom(1, "Pokémon Emerald Version", year: 2004, genres: ["Role-playing (RPG)"], series: ["Pokémon"]),
            rom(2, "Fire Emblem", year: 2003, genres: ["Strategy"]),
            rom(3, "Pokémon Blue Version", platform: "gb", year: 1996, genres: ["Role-playing (RPG)"], series: ["Pokémon"]),
            rom(4, "The Minish Cap", year: 2004),
            rom(5, "Guitar Hero", platform: "ps2", year: 2005, genres: ["Music"]),
        ].map { LibraryGame(rom: $0, artworkURL: nil) }
        content.installedRomIDs = [1, 3]
        return content
    }

    func testTheWallGroupsEveryGameOnceInEachLens() {
        let content = library()
        for lens in LibraryLens.allCases {
            let ids = content.wall(lens).flatMap(\.games).map(\.id)
            XCTAssertEqual(ids.sorted(), [1, 2, 3, 4, 5], "\(lens): every game, once")
        }
        let consoles = content.wall(.console)
        XCTAssertEqual(consoles.map(\.title), ["Game Boy Advance", "Game Boy", "PlayStation 2"], "tier order: playable first")
        XCTAssertEqual(consoles.first?.games.map(\.id), [2, 4, 1], "A–Z, ignoring “The”")
        XCTAssertEqual(consoles.first?.detail, "3 games · 1 on this device")
        XCTAssertEqual(consoles.map(\.index), ["GBA", "GB", "PS2"])
    }

    func testSeriesTrimTheirNameAndTheRestGatherLast() {
        let series = library().wall(.series)
        XCTAssertEqual(series.map(\.title), ["Pokémon", "Everything else"])
        let pokemon = series[0]
        XCTAssertEqual(pokemon.games.map(\.id), [3, 1], "by release year")
        XCTAssertEqual(pokemon.games.map { pokemon.title(of: $0) }, ["Blue Version", "Emerald Version"])
        XCTAssertEqual(series[1].title(of: series[1].games[0]), series[1].games[0].rom.displayName, "no prefix outside a series")
    }

    func testGenresAndErasAndLetters() {
        let content = library()
        let genres = content.wall(.genre)
        XCTAssertEqual(genres.first?.title, "Role-playing", "IGDB's abbreviation dropped; biggest first")
        XCTAssertEqual(genres.last?.title, "No genre from RomM")
        let eras = content.wall(.year)
        XCTAssertEqual(eras.map(\.title), ["Polygons & pockets", "The 2000s"])
        XCTAssertEqual(eras.map(\.subtitle), ["1996", "2003–05"])
        XCTAssertEqual(eras.map(\.index), ["’96", "’03"])
        XCTAssertEqual(content.wall(.alphabetical).map(\.title), ["F", "G", "M", "P"], "“The Minish Cap” files under M")
    }

    // MARK: Screens

    private func image(_ width: Int, _ height: Int, content: CGRect) -> CGImage {
        let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        context.setFillColor(CGColor(red: 0, green: 0, blue: 0, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        context.setFillColor(CGColor(red: 0.2, green: 0.6, blue: 0.9, alpha: 1))
        // Content is given top-down; the context draws bottom-up.
        context.fill(CGRect(x: content.minX, y: CGFloat(height) - content.maxY, width: content.width, height: content.height))
        return context.makeImage()!
    }

    func testTheStatusBarReadsTheArtUnderIt() {
        // A cover dark at the top and light below: the band under the bar decides.
        let darkTop = image(200, 300, content: CGRect(x: 0, y: 60, width: 200, height: 240))
        XCTAssertTrue(ArtTone.isDark(darkTop, band: 0...0.1), "black rows under the bar: light text")
        XCTAssertFalse(ArtTone.isDark(darkTop, band: 0.5...0.6), "light rows: dark text")
        // A 2:3 cover filling a 402 × 400 hero is cropped about its centre (101.5 pt off the
        // top of a 603 pt drawing); the bar covers the first 62 pt of the hero.
        let poster = image(200, 300, content: CGRect(x: 0, y: 0, width: 200, height: 300))
        let band = GameArtHero.statusBarBand(image: poster, hero: CGSize(width: 402, height: 400), topInset: 62)
        XCTAssertEqual(band.lowerBound, 101.5 / 603, accuracy: 0.001)
        XCTAssertEqual(band.upperBound, 163.5 / 603, accuracy: 0.001)
        // A wide cover fills by height: nothing is cropped off the top.
        let wide = image(400, 200, content: CGRect(x: 0, y: 0, width: 400, height: 200))
        XCTAssertEqual(GameArtHero.statusBarBand(image: wide, hero: CGSize(width: 402, height: 400), topInset: 62).lowerBound, 0, accuracy: 0.001)
    }

    func testALetterboxedScreenIsTrimmed() {
        let letterboxed = ScreenTrim.trimmed(image(320, 240, content: CGRect(x: 0, y: 40, width: 320, height: 150)))
        XCTAssertEqual(letterboxed.width, 320)
        XCTAssertEqual(letterboxed.height, 150)
        let untouched = ScreenTrim.trimmed(image(240, 160, content: CGRect(x: 0, y: 0, width: 240, height: 160)))
        XCTAssertEqual([untouched.width, untouched.height], [240, 160])
        let mostlyBlack = ScreenTrim.trimmed(image(320, 240, content: CGRect(x: 150, y: 110, width: 20, height: 20)))
        XCTAssertEqual([mostlyBlack.width, mostlyBlack.height], [320, 240], "a dark scene is not cropped to a sliver")
    }
}
