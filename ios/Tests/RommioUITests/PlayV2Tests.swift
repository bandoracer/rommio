import XCTest
@testable import RommioContract
@testable import RommioFoundation
@testable import RommioUI

/// Aurora Play v2 (docs/theme-layouts.md, Play): each game's screen and caption come
/// from its latest resume point (else save state), and the recent saves mosaic never
/// leaves a hole or a lone half tile.
final class PlayV2Tests: XCTestCase {
    private func entry(_ kind: SaveKind, rom: Int64 = 7, minutesAgo: Double, resume: Bool = false, loadable: Bool = true,
                       label: String? = nil, slot: Int? = nil, thumbnail: Bool = true) -> SaveEntry {
        let captured = Int64(Date().addingTimeInterval(-minutesAgo * 60).timeIntervalSince1970 * 1000)
        return SaveEntry(key: SaveKey(namespaceId: "n", contentId: "c\(rom)", checkpointId: "\(kind)-\(minutesAgo)"), romId: rom, fileId: rom * 10,
                         operationId: "op-\(minutesAgo)", kind: kind, capturedAtEpochMs: captured, captureReason: kind == .saveState ? "manual" : "exit",
                         deviceId: "me", fromThisDevice: true, deviceLabel: "This iPhone", sync: .waiting, label: label, slotNumber: slot,
                         isCurrentResume: resume, isCurrentSlot: slot != nil, loadable: loadable,
                         thumbnailURL: thumbnail ? URL(fileURLWithPath: "/tmp/preview-\(minutesAgo).jpg") : nil)
    }

    // MARK: Latest place to continue from

    func testTheCurrentResumePointBeatsANewerSaveState() {
        let resume = entry(.resumePoint, minutesAgo: 30, resume: true)
        let state = entry(.saveState, minutesAgo: 5, label: "Before the boss")
        let older = entry(.resumePoint, minutesAgo: 90)
        let latest = SavedProgressRules.latestByGame([older, state, resume])
        XCTAssertEqual(latest[7]?.key, resume.key)
    }

    func testASaveStateStandsInWithoutAResumePoint() {
        let state = entry(.saveState, minutesAgo: 5, slot: 2)
        let gone = entry(.saveState, minutesAgo: 1, loadable: false)
        let gameSave = entry(.gameSave, minutesAgo: 0)
        let latest = SavedProgressRules.latestByGame([gone, gameSave, state, entry(.resumePoint, rom: 8, minutesAgo: 3, resume: true)])
        XCTAssertEqual(latest[7]?.key, state.key, "Loadable save state; game-save data never")
        XCTAssertEqual(latest[8]?.kind, .resumePoint)
    }

    func testTheCaptionReadsTheResumePointElseHowItWasPlayed() {
        let rom = RomDTO(id: 7, name: "Super Metroid", platformName: "Super Nintendo")
        var game = PlayGameItem(rom: rom, artworkURL: nil, lastPlayedAt: Date().addingTimeInterval(-7200), playtime: 3900, sessionCount: 2)
        XCTAssertEqual(game.captionLine, "Played 2 h ago · 1 h 05 min on this device")
        XCTAssertNil(game.previewURL)
        game.latest = entry(.resumePoint, minutesAgo: 1.2, resume: true)
        XCTAssertEqual(game.captionLine, "Automatic resume · 1 min ago · This iPhone")
        XCTAssertNotNil(game.previewURL)
        game.latest = entry(.saveState, minutesAgo: 3.5, label: "Before the boss")
        XCTAssertEqual(game.captionLine, "“Before the boss” · 3 min ago · This iPhone")
        game.latest = entry(.saveState, minutesAgo: 3.5, slot: 2)
        XCTAssertEqual(game.captionLine, "Save state · Slot 2 · 3 min ago · This iPhone")
    }

    // MARK: Mosaic v2

    func testCompactMosaicNeverLeavesAHoleOrALoneHalfTile() {
        XCTAssertEqual(SaveMosaic.rows(count: 0, columns: 2), [])
        XCTAssertEqual(SaveMosaic.rows(count: 1, columns: 2), [[0]])
        XCTAssertEqual(SaveMosaic.rows(count: 2, columns: 2), [[0, 1]])
        XCTAssertEqual(SaveMosaic.rows(count: 3, columns: 2), [[0], [1, 2]])
        XCTAssertEqual(SaveMosaic.rows(count: 4, columns: 2), [[0], [1, 2], [3]])
        XCTAssertEqual(SaveMosaic.rows(count: 5, columns: 2), [[0], [1, 2], [3, 4]])
        for count in 1 ... 12 {
            let rows = SaveMosaic.rows(count: count, columns: 2)
            XCTAssertEqual(rows.flatMap { $0 }, Array(0 ..< count), "Every save once, in order")
            XCTAssertTrue(rows.allSatisfy { $0.count == 1 || $0.count == 2 }, "Full width or a pair")
        }
    }

    func testRegularMosaicFillsRowsOfFourAndStretchesTheLast() {
        XCTAssertEqual(SaveMosaic.rows(count: 6, columns: 4), [[0, 1, 2, 3], [4, 5]])
        XCTAssertEqual(SaveMosaic.rows(count: 4, columns: 4), [[0, 1, 2, 3]])
        XCTAssertEqual(SaveMosaic.columns(width: 800), 4, "Tiles keep 180 pt: four columns")
        XCTAssertEqual(SaveMosaic.columns(width: 600), 3)
        XCTAssertEqual(SaveMosaic.columns(width: 380), 2, "Narrow canvas: the compact rule")
    }
}
