import Foundation
import Observation
import XCTest
@testable import RommioContract
@testable import RommioFoundation
@testable import RommioUI

/// Counts observation callbacks (which are `@Sendable`).
private final class Tally: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    func add() { lock.lock(); count += 1; lock.unlock() }
    var value: Int { lock.lock(); defer { lock.unlock() }; return count }
}

@MainActor
@Observable
private final class Counter {
    var value = 0
    var other = 0
}

/// Page content off the render path (`DerivedContent`), the theme split (`RommioTheme`
/// vs `PageLight`) and lighting from memory.
@MainActor
final class PageContentTests: XCTestCase {
    private func nextTurns() async {
        for _ in 0 ..< 3 { await Task.yield() }
        try? await Task.sleep(for: .milliseconds(20))
    }

    func testDerivedContentRebuildsOncePerTurnAndPublishesOnlyChanges() async {
        let counter = Counter()
        var builds = 0
        let derived = DerivedContent { () -> Int in
            builds += 1
            return counter.value / 2
        }
        XCTAssertEqual(derived.value, 0)
        XCTAssertEqual(builds, 1, "built on first use")

        let published = Tally()
        withObservationTracking { _ = derived.value } onChange: { published.add() }
        // Three changes in one turn: one rebuild. 0 → 1 → … → 1: the content (0) is unchanged.
        counter.value = 1
        counter.value = 1
        counter.value = 1
        await nextTurns()
        XCTAssertEqual(builds, 2, "one rebuild for the turn's changes")
        XCTAssertEqual(published.value, 0, "equal content is not published")
        XCTAssertEqual(derived.value, 0)

        counter.value = 4
        await nextTurns()
        XCTAssertEqual(published.value, 1)
        XCTAssertEqual(derived.value, 2)

        // A property the build doesn't read never rebuilds it.
        let before = builds
        counter.other = 9
        await nextTurns()
        XCTAssertEqual(builds, before)
    }

    func testFeatureModelsPublishOnlyChangedValues() async {
        let counter = Counter()
        let changes = Tally()
        withObservationTracking { _ = counter.value } onChange: { changes.add() }
        counter.update(\.value, 0)
        XCTAssertEqual(changes.value, 0, "assigning an equal value notifies nobody")
        counter.update(\.value, 1)
        XCTAssertEqual(changes.value, 1)
    }

    func testThemeIsSmallEquatableAndItsLightIsSeparate() {
        XCTAssertLessThanOrEqual(MemoryLayout<RommioTheme>.size, 24, "a theme is two references and a flag")
        XCTAssertLessThanOrEqual(MemoryLayout<PageLight>.size, 8, "a page light is one reference")
        XCTAssertEqual(RommioTheme(.aurora, dark: true), RommioTheme(.aurora, dark: true))
        XCTAssertNotEqual(RommioTheme(.aurora, dark: true), RommioTheme(.aurora, dark: false))
        XCTAssertNotEqual(RommioTheme(.aurora, dark: false), RommioTheme(.riso, dark: false))
        let theme = RommioTheme(.aurora, dark: false)
        XCTAssertFalse(theme.unlitLight.isLit)
        XCTAssertEqual(theme.unlitLight, RommioTheme(.aurora, dark: false).unlitLight, "the unlit light is shared")
    }

    func testAPaletteInMemoryLightsThePageOnItsFirstFrame() {
        let pipeline = ArtworkPipeline(directory: nil, loader: { _ in throw URLError(.notConnectedToInternet) })
        let url = URL(string: "https://rommio.invalid/cover.png")!
        XCTAssertNil(pipeline.cachedPalettes(for: [url]))
        let palette = CoverPalette(hex: ["#0b0620", "#3a1450", "#5d2a6e", "#1c0c2b", "#ff7a3d"])!
        pipeline.rememberPalette(palette, for: url)
        let known = pipeline.cachedPalettes(for: [url])
        XCTAssertEqual(known, [palette], "read synchronously, without the actor")
        let aurora = RommioTheme(.aurora, dark: false)
        let lit = PageLight.lit(by: known ?? [], artURL: url, theme: aurora)
        XCTAssertTrue(lit.isLit)
        XCTAssertEqual(lit.artURL, url)
        XCTAssertEqual(lit, PageLight.lit(by: [palette], artURL: url, theme: aurora), "derived once, then cached")
        XCTAssertNotEqual(lit, aurora.unlitLight)
        XCTAssertFalse(PageLight.lit(by: [palette], artURL: url, theme: RommioTheme(.riso, dark: false)).isLit)
        let grey = CoverPalette(hex: ["#101010", "#202020", "#303030", "#404040", "#505050"])!
        XCTAssertFalse(PageLight.lit(by: [grey], artURL: url, theme: aurora).isLit, "a greyscale cover keeps the theme's light")
    }
}
