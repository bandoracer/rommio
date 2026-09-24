import Foundation
import XCTest
@testable import RommioContract
@testable import RommioFoundation
import RommioPlayerKit
@testable import RommioUI

/// The Launch choice content (docs/theme-layouts.md, Launch choice) and where the
/// player's panels open (Pause on the open iPhone Duo).
final class LaunchChoiceTests: XCTestCase {
    private let locale = Locale(identifier: "en_US")
    private var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar
    }
    private let now = Date(timeIntervalSince1970: 1_790_000_000)
    private let rom = RomDTO(id: 19, name: "NES pattern test")

    private func entry(_ kind: SaveKind, minutesAgo: Double, loadable: Bool = true, device: String = "This iPhone") -> SaveEntry {
        SaveEntry(key: SaveKey(namespaceId: "n", contentId: "c", checkpointId: UUID().uuidString.lowercased()), romId: 19, fileId: 190,
                  operationId: "op", kind: kind, capturedAtEpochMs: Int64((now.timeIntervalSince1970 - minutesAgo * 60) * 1000), captureReason: "exit",
                  deviceId: "d", fromThisDevice: true, deviceLabel: device, sync: .waiting, label: nil, slotNumber: nil,
                  isCurrentResume: kind != .gameSave, isCurrentSlot: false, loadable: loadable, thumbnailURL: nil)
    }

    private func make(_ saves: GameSaves, unavailable: String? = nil, note: String? = nil, preferred: LaunchChoice.Option? = nil) -> LaunchChoice? {
        LaunchChoice.make(rom: rom, artworkURL: nil, saves: saves, resumeUnavailable: unavailable, note: note, preferred: preferred,
                          now: now, calendar: calendar, locale: locale)
    }

    func testResumeAndGameSaveRowsUseTheSpecLabels() throws {
        let saves = GameSaves(resume: entry(.resumePoint, minutesAgo: 3), gameSaveData: [entry(.gameSave, minutesAgo: 3)])
        let choice = try XCTUnwrap(make(saves))
        XCTAssertEqual(choice.rows.map(\.title), ["Resume", "Start with game save"])
        XCTAssertEqual(choice.rows.map(\.option.identifier), ["launch.resume", "launch.gameSave"])
        XCTAssertEqual(choice.rows[0].detail, "Automatic resume · 3 min ago · This iPhone")
        XCTAssertEqual(choice.rows[1].detail, "Game-save data · 3 min ago · the game starts from its own save")
        XCTAssertNotNil(choice.rows[0].preview, "Resume shows the resume point's frame")
        XCTAssertNil(choice.rows[1].preview, "Start with game save shows a save glyph")
        XCTAssertEqual(choice.defaultSelection, .resume)
        XCTAssertEqual(LaunchChoice.primaryTitle(.resume), "Resume")
        XCTAssertEqual(LaunchChoice.primaryTitle(.gameSave), "Start with game save")
        XCTAssertTrue(LaunchChoice.offersStartOptions(saves))
    }

    func testASaveStateResumePointNamesItsKind() throws {
        var state = entry(.saveState, minutesAgo: 120)
        state = SaveEntry(key: state.key, romId: 19, fileId: 190, operationId: "op", kind: .saveState, capturedAtEpochMs: state.capturedAtEpochMs,
                          captureReason: "manual", deviceId: "d", fromThisDevice: true, deviceLabel: "This iPhone", sync: .synced, label: nil,
                          slotNumber: 2, isCurrentResume: true, isCurrentSlot: true, loadable: true, thumbnailURL: nil)
        let choice = try XCTUnwrap(make(GameSaves(resume: state, gameSaveData: [entry(.gameSave, minutesAgo: 1)])))
        XCTAssertEqual(choice.rows[0].detail, "Save state · Slot 2 · 2 h ago · This iPhone")
    }

    func testAnUnusableResumePointLeavesStartWithGameSave() throws {
        let saves = GameSaves(resume: entry(.resumePoint, minutesAgo: 10), gameSaveData: [entry(.gameSave, minutesAgo: 5)])
        let choice = try XCTUnwrap(make(saves, unavailable: "No resume point made by this core matches the current game-save data."))
        XCTAssertFalse(choice.rows[0].enabled)
        XCTAssertTrue(choice.rows[1].enabled)
        XCTAssertEqual(choice.defaultSelection, .gameSave)
        XCTAssertEqual(choice.note, "No resume point made by this core matches the current game-save data.")
        let notOnDevice = try XCTUnwrap(make(GameSaves(resume: entry(.resumePoint, minutesAgo: 1, loadable: false), gameSaveData: [entry(.gameSave, minutesAgo: 1)])))
        XCTAssertEqual(notOnDevice.defaultSelection, .gameSave, "a resume point not kept on this device can't be loaded")
    }

    func testTheOptionChosenBeforeTheSheetStaysSelected() throws {
        let saves = GameSaves(resume: entry(.resumePoint, minutesAgo: 3), gameSaveData: [entry(.gameSave, minutesAgo: 3)])
        XCTAssertEqual(try XCTUnwrap(make(saves, note: "Cloud progress not checked", preferred: .gameSave)).defaultSelection, .gameSave)
        XCTAssertEqual(try XCTUnwrap(make(saves, unavailable: "x", preferred: .resume)).defaultSelection, .gameSave,
                       "a preference for an option that can't be used falls back")
    }

    func testNothingToChooseOrAConflictHasNoLaunchChoice() {
        XCTAssertNil(make(.empty))
        XCTAssertNil(make(GameSaves(resume: entry(.resumePoint, minutesAgo: 1), resumeConflict: true, gameSaveData: [entry(.gameSave, minutesAgo: 1)])),
                     "several resume heads are a Conflict")
        XCTAssertNil(make(GameSaves(resume: entry(.resumePoint, minutesAgo: 1)), unavailable: "x"), "no option can be used")
        XCTAssertFalse(LaunchChoice.offersStartOptions(GameSaves(resume: entry(.resumePoint, minutesAgo: 1))),
                       "Start options needs a resume point and game-save data")
        XCTAssertFalse(LaunchChoice.offersStartOptions(GameSaves(gameSaveData: [entry(.gameSave, minutesAgo: 1)])))
    }

    // MARK: Player panels

    func testPanelsFloatBesideTheGameOnWideWindowsOnly() {
        // iPhone Duo open (landscape 1024+ pt, regular) and iPad: the floating inspector.
        XCTAssertEqual(PlayerPanelPresentation.resolve(regularWidth: true, windowWidth: 1_033), .sidePane)
        XCTAssertEqual(PlayerPanelPresentation.resolve(regularWidth: true, windowWidth: 700), .sidePane)
        // iPhone Duo closed, slab iPhones, narrow regular windows: a sheet.
        XCTAssertEqual(PlayerPanelPresentation.resolve(regularWidth: false, windowWidth: 466), .sheet)
        XCTAssertEqual(PlayerPanelPresentation.resolve(regularWidth: false, windowWidth: 956), .sheet, "compact landscape phone")
        XCTAssertEqual(PlayerPanelPresentation.resolve(regularWidth: true, windowWidth: 669), .sheet)
        XCTAssertEqual(PlayerPanelPresentation.paneWidth(windowWidth: 1_033), 380)
        XCTAssertEqual(PlayerPanelPresentation.paneWidth(windowWidth: 700), 315)
    }

    func testPauseBesideTheGameLeavesTheGameUncovered() {
        // iPhone Duo open, landscape: Pause as the floating inspector.
        let size = CGSize(width: 1_033, height: 669)
        XCTAssertEqual(PlayerPanelPresentation.resolve(regularWidth: true, windowWidth: size.width), .sidePane)
        let pane = PlayerPanelPresentation.paneWidth(windowWidth: size.width)
        for family in ["snes", "nds"] {
            let layout = resolvePlayerStageLayout(containerSize: size, safeAreaInsets: PlayerViewportSafeAreaInsets(top: 0, bottom: 20, left: 0, right: 0),
                                                  aspectRatio: family == "nds" ? 256.0 / 384.0 : 8.0 / 7.0, topAlignedInPortrait: true,
                                                  stage: PlayerStageContext(dualScreen: family == "nds", isPhoneFormFactor: true, trailingPaneWidth: pane,
                                                                            foldable: true))
            for frame in layout.screens.isEmpty ? [layout.viewport.frame] : layout.screens.map(\.frame) {
                XCTAssertLessThanOrEqual(frame.maxX, size.width - pane + 0.5, "\(family): the pane covers the game")
                XCTAssertGreaterThan(frame.width, 0, family)
            }
        }
    }
}
