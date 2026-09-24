import Foundation
import XCTest
import RommioContract
@testable import RommioFoundation

final class PlayStatsTests: XCTestCase {
    private var calendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar
    }()
    private let now = Date(timeIntervalSince1970: 1_790_000_000) // 2026-09-21 14:13:20 UTC

    private func sample(_ rom: Int, _ session: String, _ offset: TimeInterval, device: String = "this", reason: String = "periodic") -> PlayCheckpointSample {
        PlayCheckpointSample(romID: rom, sessionID: session, deviceID: device, createdAt: now.addingTimeInterval(offset), captureReason: reason)
    }

    func testEmptyInputHasNoStats() {
        let stats = PlayStatsDerivation.derive(from: [], now: now, calendar: calendar)
        XCTAssertTrue(stats.isEmpty)
        XCTAssertEqual(stats.totalPlaytime, 0)
        XCTAssertNil(stats.lastPlayedAt)
        XCTAssertEqual(stats.currentStreakDays, 0)
    }

    func testGapsCountInFullUpToTheWindow() {
        // 120 + 120, a 10-minute pause counts as the 120 s window, then 60 s more.
        let stats = PlayStatsDerivation.derive(from: [
            sample(1, "a", -900), sample(1, "a", -780), sample(1, "a", -660),
            sample(1, "a", -60), sample(1, "a", 0),
        ], now: now, calendar: calendar)
        XCTAssertEqual(stats.sessions.count, 1)
        XCTAssertEqual(stats.sessions[0].playtime, 420, accuracy: 0.001)
        XCTAssertEqual(stats.sessions[0].startedAt, now.addingTimeInterval(-900))
        XCTAssertEqual(stats.sessions[0].endedAt, now)
        XCTAssertEqual(stats.totalPlaytime, 420, accuracy: 0.001)
    }

    func testLongGapCountsAsTheWindowAndKeepsOneSession() {
        let stats = PlayStatsDerivation.derive(from: [sample(1, "a", -3600), sample(1, "a", 0)], now: now, calendar: calendar)
        XCTAssertEqual(stats.totalPlaytime, 120, accuracy: 0.001)
        XCTAssertEqual(stats.sessionCount, 1)
        let justOver = PlayStatsDerivation.derive(from: [sample(1, "a", -121), sample(1, "a", 0)], now: now, calendar: calendar)
        XCTAssertEqual(justOver.totalPlaytime, 120, accuracy: 0.001)
        let custom = PlayStatsDerivation.derive(from: [sample(1, "a", -45), sample(1, "a", 0)], now: now, calendar: calendar,
                                                continuityWindow: 30)
        XCTAssertEqual(custom.totalPlaytime, 30, accuracy: 0.001)
    }

    func testSingleCheckpointSessionHasNoTime() {
        let stats = PlayStatsDerivation.derive(from: [sample(1, "a", 0)], now: now, calendar: calendar)
        XCTAssertEqual(stats.sessionCount, 1)
        XCTAssertEqual(stats.totalPlaytime, 0)
    }

    func testDuplicateTimestampsFromOneCaptureAddNothing() {
        // A capture writes a game-save and a resume checkpoint with the same time.
        let stats = PlayStatsDerivation.derive(from: [sample(1, "a", -120), sample(1, "a", -120), sample(1, "a", 0), sample(1, "a", 0)],
                                               now: now, calendar: calendar)
        XCTAssertEqual(stats.totalPlaytime, 120, accuracy: 0.001)
    }

    func testTimeByGameSortsMostPlayedFirst() {
        let stats = PlayStatsDerivation.derive(from: [
            sample(1, "a", -3600), sample(1, "a", -3540),                   // 60 s
            sample(2, "b", -1800), sample(2, "b", -1700), sample(2, "b", -1600), // 200 s
            sample(1, "c", -300), sample(1, "c", -200),                     // 100 s
        ], now: now, calendar: calendar)
        // Every gap here is within the window.
        XCTAssertEqual(stats.games.map(\.romID), [2, 1])
        XCTAssertEqual(stats.games[0].playtime, 200, accuracy: 0.001)
        XCTAssertEqual(stats.games[1].playtime, 160, accuracy: 0.001)
        XCTAssertEqual(stats.games[1].sessionCount, 2)
        XCTAssertEqual(stats.games[1].lastPlayedAt, now.addingTimeInterval(-200))
        // Recent sessions: newest first.
        XCTAssertEqual(stats.sessions.map(\.id), ["c", "b", "a"])
        XCTAssertEqual(stats.lastPlayedAt, now.addingTimeInterval(-200))
    }

    func testGamesTieBreakOnMostPlayedThenRecent() {
        let stats = PlayStatsDerivation.derive(from: [
            sample(1, "a", -600), sample(1, "a", -500),
            sample(2, "b", -300), sample(2, "b", -200),
            sample(3, "c", -100), sample(3, "c", 0), sample(3, "c", 100),
        ], now: now, calendar: calendar)
        XCTAssertEqual(stats.games.map(\.romID), [3, 2, 1])
    }

    func testOnlyThisDeviceAndNoImports() {
        let stats = PlayStatsDerivation.derive(from: [
            sample(1, "a", -120), sample(1, "a", 0),
            sample(2, "b", -120, device: "other"), sample(2, "b", 0, device: "other"),
            sample(3, "c", -120, reason: "import"), sample(3, "c", 0, reason: "import"),
        ], deviceID: "this", now: now, calendar: calendar)
        XCTAssertEqual(stats.games.map(\.romID), [1])
        XCTAssertEqual(stats.totalPlaytime, 120, accuracy: 0.001)
    }

    func testStreakCountsConsecutiveDaysEndingToday() {
        let day: TimeInterval = 86_400
        let stats = PlayStatsDerivation.derive(from: [
            sample(1, "t", 0), sample(1, "y", -day), sample(1, "d2", -2 * day), sample(1, "d4", -4 * day),
        ], now: now, calendar: calendar)
        XCTAssertEqual(stats.currentStreakDays, 3)
    }

    func testStreakSurvivesUntilTodayEnds() {
        let day: TimeInterval = 86_400
        let stats = PlayStatsDerivation.derive(from: [sample(1, "y", -day), sample(1, "d2", -2 * day)], now: now, calendar: calendar)
        XCTAssertEqual(stats.currentStreakDays, 2)
        let broken = PlayStatsDerivation.derive(from: [sample(1, "d2", -2 * day)], now: now, calendar: calendar)
        XCTAssertEqual(broken.currentStreakDays, 0)
    }

    func testSampleFromCheckpoint() {
        let binding = ProgressBinding(serverKey: "s", accountId: "1", romId: 42, files: [])
        let checkpoint = ProgressCheckpoint(
            schemaVersion: 3, checkpointId: "c", binding: binding,
            saveCompatibility: ProgressSaveCompatibility(system: "nes", format: "raw", layoutVersion: "1", coreId: nil, allowsEmpty: true),
            stateCompatibility: nil, saveDataComplete: true, artifacts: [], deviceId: "d", sessionId: "s1",
            captureReason: "exit", createdAtEpochMs: 1_790_000_000_500)
        let sample = PlayCheckpointSample(checkpoint: checkpoint)
        XCTAssertEqual(sample.romID, 42)
        XCTAssertEqual(sample.sessionID, "s1")
        XCTAssertEqual(sample.deviceID, "d")
        XCTAssertEqual(sample.captureReason, "exit")
        XCTAssertEqual(sample.createdAt.timeIntervalSince1970, 1_790_000_000.5, accuracy: 0.0001)
    }
}

final class LibrarySearchTests: XCTestCase {
    private let roms = [
        RomDTO(id: 1, name: "Super Metroid", platformName: "Super Nintendo", platformSlug: "snes"),
        RomDTO(id: 2, name: "Metroid Fusion", platformName: "Game Boy Advance", platformSlug: "gba"),
        RomDTO(id: 3, name: "Metroid", platformName: "NES", platformSlug: "nes"),
        RomDTO(id: 4, name: "Pokémon Emerald", platformName: "Game Boy Advance", platformSlug: "gba"),
        RomDTO(id: 5, name: "EarthBound", platformName: "Super Nintendo", platformSlug: "snes"),
        RomDTO(id: 6, name: "Metroid Prime Hunters", platformName: "Nintendo DS", platformSlug: "nds"),
    ]

    func testBestMatchFirst() {
        let results = LibrarySearch.rank(roms, query: "metroid")
        XCTAssertEqual(results.map(\.rom.id), [3, 2, 6, 1])
        XCTAssertEqual(results.first?.quality, .exactTitle)
    }

    func testRecentlyPlayedFirstWithinAMatchQuality() {
        let now = Date()
        let results = LibrarySearch.rank(roms, query: "metroid", lastPlayed: [6: now, 2: now.addingTimeInterval(-60), 1: now])
        // The exact title still leads; the prefix matches order by recent play.
        XCTAssertEqual(results.map(\.rom.id), [3, 6, 2, 1])
    }

    func testAccentAndCaseInsensitive() {
        XCTAssertEqual(LibrarySearch.rank(roms, query: "POKEMON").map(\.rom.id), [4])
        XCTAssertEqual(LibrarySearch.rank(roms, query: "earth").map(\.rom.id), [5])
    }

    func testTokensMatchWordPrefixesAndPlatforms() {
        XCTAssertEqual(LibrarySearch.rank(roms, query: "met hun").map(\.rom.id), [6])
        let snes = LibrarySearch.rank(roms, query: "snes")
        XCTAssertEqual(Set(snes.map(\.rom.id)), [1, 5])
        XCTAssertTrue(snes.allSatisfy { $0.quality == .platform })
    }

    func testEmptyQueryAndNoMatch() {
        XCTAssertTrue(LibrarySearch.rank(roms, query: "   ").isEmpty)
        XCTAssertTrue(LibrarySearch.rank(roms, query: "zelda").isEmpty)
    }

    func testDuplicatesAreDropped() {
        XCTAssertEqual(LibrarySearch.rank(roms + roms, query: "earthbound").count, 1)
    }
}

@MainActor
final class AppearanceStoreTests: XCTestCase {
    private func defaults() -> UserDefaults {
        let name = "appearance-tests-" + UUID().uuidString
        let defaults = UserDefaults(suiteName: name)!
        defaults.removePersistentDomain(forName: name)
        return defaults
    }

    func testDefaultsToAuroraAndSystem() {
        let store = AppearanceStore(defaults: defaults())
        XCTAssertEqual(store.theme, .aurora)
        XCTAssertEqual(store.scheme, .system)
    }

    func testPersistsUnderTheDocumentedKeys() {
        let defaults = defaults()
        let store = AppearanceStore(defaults: defaults)
        store.theme = .riso
        store.scheme = .dark
        XCTAssertEqual(defaults.string(forKey: "appearance.theme"), "riso")
        XCTAssertEqual(defaults.string(forKey: "appearance.scheme"), "dark")
        let reloaded = AppearanceStore(defaults: defaults)
        XCTAssertEqual(reloaded.theme, .riso)
        XCTAssertEqual(reloaded.scheme, .dark)
    }

    func testUnknownValuesFallBackToDefaults() {
        let defaults = defaults()
        defaults.set("classic", forKey: "appearance.theme")
        defaults.set("sepia", forKey: "appearance.scheme")
        let store = AppearanceStore(defaults: defaults)
        XCTAssertEqual(store.theme, .aurora)
        XCTAssertEqual(store.scheme, .system)
    }
}
