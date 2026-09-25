import Foundation
import XCTest
@testable import RommioContract
@testable import RommioFoundation
@testable import RommioUI

/// docs/theme-layouts.md §4 (copy and formatting) and §1.2a (readable tint).
final class CopyAndFormattingTests: XCTestCase {
    private let locale = Locale(identifier: "en_US")
    private var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "America/Los_Angeles")!
        return calendar
    }

    /// Wednesday 23 September 2026, 14:30 local.
    private var now: Date {
        calendar.date(from: DateComponents(year: 2026, month: 9, day: 23, hour: 14, minute: 30))!
    }

    private func date(_ month: Int, _ day: Int, _ hour: Int, _ minute: Int = 0, year: Int = 2026) -> Date {
        calendar.date(from: DateComponents(year: year, month: month, day: day, hour: hour, minute: minute))!
    }

    private func relative(_ date: Date) -> String {
        PlayFormat.relative(date, now: now, calendar: calendar, locale: locale)
    }

    // MARK: Counts, sizes, durations

    func testCountsUseTheLocaleGrouping() {
        XCTAssertEqual(PlayFormat.count(1234, locale: locale), "1,234")
        XCTAssertEqual(PlayFormat.games(1234, locale: locale), "1,234 games")
        XCTAssertEqual(PlayFormat.games(1, locale: locale), "1 game")
        XCTAssertEqual(PlayFormat.count(2, "entry", "entries", locale: locale), "2 entries")
        XCTAssertEqual(PlayFormat.count(0, "save", locale: locale), "0 saves")
    }

    func testSizesAreDecimalAndZeroIsKilobytes() {
        XCTAssertEqual(PlayFormat.bytes(0), "0 KB")
        XCTAssertEqual(PlayFormat.bytes(1_000), "1 KB")
        XCTAssertEqual(PlayFormat.bytes(1_234_567), "1.2 MB")
        XCTAssertEqual(PlayFormat.bytes(3_400_000_000), "3.4 GB")
        XCTAssertEqual(PlayFormat.bytes(-5), "0 KB")
    }

    func testDurations() {
        XCTAssertEqual(PlayFormat.duration(0), "0 min")
        XCTAssertEqual(PlayFormat.duration(30), "Under a minute")
        XCTAssertEqual(PlayFormat.duration(45 * 60), "45 min")
        XCTAssertEqual(PlayFormat.duration(2 * 3600 + 5 * 60), "2 h 05 min")
        XCTAssertEqual(PlayFormat.duration(2 * 3600), "2 h")
        XCTAssertEqual(PlayFormat.compactDuration(30), "0 min")
    }

    func testSeparatorIsAMiddleDotAndSkipsEmptyParts() {
        XCTAssertEqual(PlayFormat.separator, " · ")
        XCTAssertEqual(PlayFormat.line(["SFC", nil, "", "1.5 MB"]), "SFC · 1.5 MB")
    }

    // MARK: Relative time

    func testRelativeTimeFollowsTheSharedRule() {
        XCTAssertEqual(relative(now.addingTimeInterval(-20)), "Just now")
        XCTAssertEqual(relative(now.addingTimeInterval(20)), "Just now", "a clock ahead of this one")
        XCTAssertEqual(relative(now.addingTimeInterval(-2 * 60)), "2 min ago")
        XCTAssertEqual(relative(now.addingTimeInterval(-59 * 60)), "59 min ago")
        XCTAssertEqual(relative(date(9, 23, 9, 10)), "5 h ago")
        XCTAssertEqual(relative(date(9, 22, 23, 50)), "Yesterday")
        XCTAssertEqual(relative(date(9, 21, 8)), "Monday")
        XCTAssertEqual(relative(date(9, 17, 8)), "Thursday", "six days ago is still a weekday")
        XCTAssertEqual(relative(date(9, 16, 8)), "Sep 16")
        XCTAssertEqual(relative(date(9, 21, 8, year: 2025)), "Sep 21, 2025")
    }

    func testRelativeTimeAcrossMidnightCountsMinutesFirst() {
        let justAfterMidnight = calendar.date(from: DateComponents(year: 2026, month: 9, day: 23, hour: 0, minute: 10))!
        let beforeMidnight = calendar.date(from: DateComponents(year: 2026, month: 9, day: 22, hour: 23, minute: 50))!
        XCTAssertEqual(PlayFormat.relative(beforeMidnight, now: justAfterMidnight, calendar: calendar, locale: locale), "20 min ago")
        let twoHoursLater = justAfterMidnight.addingTimeInterval(2 * 3600)
        XCTAssertEqual(PlayFormat.relative(beforeMidnight, now: twoHoursLater, calendar: calendar, locale: locale), "Yesterday")
    }

    func testInlineRelativeTimeLowercasesWordsNotNames() {
        XCTAssertEqual(PlayFormat.inline(now.addingTimeInterval(-5), now: now, calendar: calendar, locale: locale), "just now")
        XCTAssertEqual(PlayFormat.inline(date(9, 22, 10), now: now, calendar: calendar, locale: locale), "yesterday")
        XCTAssertEqual(PlayFormat.inline(date(9, 21, 10), now: now, calendar: calendar, locale: locale), "Monday")
    }

    func testTimelineMarks() {
        XCTAssertEqual(PlayFormat.short(now.addingTimeInterval(-10), now: now, calendar: calendar, locale: locale), "Now")
        XCTAssertEqual(PlayFormat.short(now.addingTimeInterval(-180), now: now, calendar: calendar, locale: locale), "3 min")
        XCTAssertEqual(PlayFormat.short(date(9, 23, 12), now: now, calendar: calendar, locale: locale), "2 h")
        XCTAssertEqual(PlayFormat.short(date(9, 22, 12), now: now, calendar: calendar, locale: locale), "Yesterday")
    }

    // MARK: Day groups

    func testDayGroupTitlesAndCaptions() {
        func title(_ date: Date) -> String { PlayFormat.dayTitle(date, now: now, calendar: calendar, locale: locale) }
        func caption(_ date: Date) -> String? { PlayFormat.dayCaption(date, now: now, calendar: calendar, locale: locale) }
        XCTAssertEqual(title(date(9, 23, 8)), "Today")
        XCTAssertEqual(caption(date(9, 23, 8)), "Wednesday, September 23")
        XCTAssertEqual(title(date(9, 22, 8)), "Yesterday")
        XCTAssertEqual(caption(date(9, 22, 8)), "Tuesday, September 22")
        XCTAssertEqual(title(date(9, 20, 8)), "Sunday")
        XCTAssertEqual(caption(date(9, 20, 8)), "Sunday, September 20")
        XCTAssertEqual(title(date(9, 10, 8)), "Sep 10")
        XCTAssertNil(caption(date(9, 10, 8)), "the title is already the date")
        XCTAssertEqual(title(date(12, 30, 8, year: 2025)), "Dec 30, 2025")
        XCTAssertEqual(SaveFormat.day(date(9, 22, 8), now: now, calendar: calendar, locale: locale), "Yesterday")
        XCTAssertEqual(PlayFormat.longDate(date(9, 23, 8), calendar: calendar, locale: locale), "Wednesday, September 23, 2026")
    }

    // MARK: Game page copy (Android's words)

    func testGameDownloadStatusUsesAndroidsWords() {
        let record = { (status: DownloadStatus) in
            DownloadRecord(profileID: "p", romID: 1, fileID: 2, romName: "Game", platformSlug: "snes", fileName: "game.sfc",
                           fileSizeBytes: 10, status: status, enqueuedAtEpochMS: 0, updatedAtEpochMS: 0)
        }
        XCTAssertEqual(GameDownloadStatus(localPath: "/tmp/game.sfc", record: nil).title, "On this device")
        XCTAssertEqual(GameDownloadStatus(localPath: nil, record: record(.running)).title, "Downloading")
        XCTAssertEqual(GameDownloadStatus(localPath: nil, record: record(.queued)).title, "Queued")
        XCTAssertEqual(GameDownloadStatus(localPath: nil, record: record(.failed)).title, "Download failed")
        XCTAssertEqual(GameDownloadStatus(localPath: nil, record: record(.canceled)).title, "Not downloaded")
        XCTAssertEqual(GameDownloadStatus(localPath: nil, record: nil).title, "Not downloaded")
        XCTAssertEqual(GameDownloadStatus(localPath: nil, record: record(.failed)).tone, .bad)
        XCTAssertEqual(GameDetailActionKind.queue.title, "Add to queue")
        XCTAssertEqual(GameDetailAction(kind: .downloadNow, enabled: false, label: "Download active").title, "Download active")
    }

    func testPagesWithNothingLoadedShowAndroidsErrorCopy() {
        XCTAssertEqual(PlayContent.errorMessage(isEmpty: true, isLoading: false, offline: true, failure: nil),
                       "You're offline. Your games appear here after this server syncs once.")
        XCTAssertEqual(PlayContent.errorMessage(isEmpty: true, isLoading: false, offline: false, failure: "Timed out"), "Timed out")
        XCTAssertNil(PlayContent.errorMessage(isEmpty: false, isLoading: false, offline: true, failure: "x"), "content wins")
        XCTAssertNil(PlayContent.errorMessage(isEmpty: true, isLoading: true, offline: true, failure: nil))
        XCTAssertNil(PlayContent.errorMessage(isEmpty: true, isLoading: false, offline: false, failure: nil))
        XCTAssertEqual(loadErrorMessage(empty: true, errors: ["x"], offline: true, offlineText: "Offline.", fallback: "f"), "Offline.")
        XCTAssertEqual(loadErrorMessage(empty: true, errors: [""], offline: false, offlineText: "Offline.", fallback: "Couldn't load the library."),
                       "Couldn't load the library.")
        XCTAssertNil(loadErrorMessage(empty: false, errors: ["x"], offline: true, offlineText: "o", fallback: "f"))
    }

    // MARK: Readable tint (§1.2a)

    func testReadableTintMatchesThePinnedTable() {
        let cases: [(String, Bool, String, String, String)] = [
            ("smetroid", false, "#d64300", "#f9f4f3", "#b23800"),
            ("smetroid", true, "#ff621a", "#0e0910", "#ff621a"),
            ("chrono", false, "#c83c0e", "#f9f4f3", "#b5360d"),
            ("linksawak", false, "#2bab56", "#f3f9f5", "#1d743a"),
            ("grey", true, "#d34545", "#100909", "#d75858"),
            ("earthbound", false, "#207fb6", "#f3f6f9", "#1b6a98"),
            ("unlit aurora", false, "#a84010", "#f6f4f1", "#a84010"),
            // The reference script's other cases.
            ("chrono", true, "#f05a28", "#100c09", "#f05a28"),
            ("linksawak", true, "#45d375", "#091009", "#45d375"),
            ("grey", false, "#ab2b2b", "#f9f3f3", "#ab2b2b"),
            ("earthbound", true, "#3ca1dd", "#10090b", "#3ca1dd"),
            ("unlit aurora", true, "#ff6a24", "#07070c", "#ff6a24"),
        ]
        for (name, dark, tint, ground, expected) in cases {
            let resolved = ReadableTint.resolve(tint: ArtRGB(hex: tint)!, ground: ArtRGB(hex: ground)!, dark: dark)
            XCTAssertEqual(resolved.hex, expected, "\(name) \(dark ? "dark" : "light")")
            XCTAssertGreaterThanOrEqual(ReadableTint.contrast(resolved, ArtRGB(hex: ground)!), 4.5, name)
        }
    }

    func testUnlitThemesUseTheirTokenTintAndLitPagesTheReadableOne() {
        for set in ThemeTokens.all {
            for dark in [false, true] {
                let theme = RommioTheme(tokens: set, isDark: dark)
                let token = set.colors(dark: dark).tint
                XCTAssertEqual(theme.unlitLight.readableTintRGB.hex, ArtRGB(red: token.red, green: token.green, blue: token.blue).hex, "\(set.key) dark \(dark)")
                let ground = set.colors(dark: dark).ground
                XCTAssertGreaterThanOrEqual(ReadableTint.contrast(theme.unlitLight.readableTintRGB, ArtRGB(red: ground.red, green: ground.green, blue: ground.blue)), 4.5,
                                            "\(set.key) dark \(dark): the retuned tint token passes")
            }
        }
        let palette = CoverPalette(hex: ["#0b0620", "#3a1450", "#5d2a6e", "#1c0c2b", "#ff7a3d"])!
        let lit = PageLight.lit(by: palette, theme: RommioTheme(.aurora, dark: false))
        XCTAssertTrue(lit.isLit)
        XCTAssertEqual(lit.readableTintRGB.hex, "#b23800", "the smetroid page: the studio tint #d64300 made readable")
        XCTAssertFalse(PageLight.lit(by: palette, theme: RommioTheme(.riso, dark: false)).isLit, "Riso never lights a page")
    }

    func testErrorTextUsesBadText() {
        XCTAssertEqual(RommioTheme(.riso, dark: false).colors.badText, ThemeTokens.riso.light.badText)
        XCTAssertEqual(StatusTone.bad.textColor(RommioTheme(.riso, dark: false), light: RommioTheme(.riso, dark: false).unlitLight), RommioTheme(.riso, dark: false).badText)
    }
}
