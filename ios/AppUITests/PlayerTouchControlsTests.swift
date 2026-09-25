import XCTest

/// Touch controls v2 against a running game (docs/player-controls.md, Verification):
/// a touch at a button's edge and one between A and B register, a d-pad drag that
/// leaves the pad keeps its direction, and quick taps never leave a button held. The
/// inputs are read from what the core received (Debug `ROMMIO_INPUT_LOG=1`, shown as
/// `player.debug.inputLog`).
///
/// Opt-in (simulator): needs a signed-in app whose library has the "NES pattern test"
/// game (the scratchpad mock RomM server) and the host file `/tmp/rommio-touch-tests`.
@MainActor
final class PlayerTouchControlsTests: XCTestCase {
    private var app: XCUIApplication!
    private var log: XCUIElement { app.descendants(matching: .any)["player.debug.inputLog"] }

    override func setUp() async throws {
        continueAfterFailure = false
        #if targetEnvironment(simulator)
        guard FileManager.default.fileExists(atPath: "/tmp/rommio-touch-tests") else { throw XCTSkip("Opt-in touch controls pass") }
        #else
        throw XCTSkip("Simulator only")
        #endif
        XCUIDevice.shared.orientation = .portrait
        app = XCUIApplication(bundleIdentifier: "io.github.mattsays.rommio.ios")
        app.terminate()
        app.launchArguments = ["-appearance.theme", "aurora", "-appearance.scheme", "light"]
        app.launchEnvironment = ["ROMMIO_INPUT_LOG": "1"]
        app.launch()
        try openPatternGame()
    }

    override func tearDown() async throws {
        if let app, app.buttons["player.menu"].exists {
            app.buttons["player.menu"].tap()
            let leave = app.buttons["player.pause.leave"]
            if leave.waitForExistence(timeout: 5) { leave.tap() }
        }
        XCUIDevice.shared.orientation = .portrait
    }

    private func openPatternGame() throws {
        let search = app.tabBars.buttons["Search"]
        XCTAssertTrue(search.waitForExistence(timeout: 60), "The app did not reach its tabs (signed in?)")
        search.tap()
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        field.tap()
        field.typeText("pattern")
        let best = app.descendants(matching: .any)["search.bestMatch"]
        XCTAssertTrue(best.waitForExistence(timeout: 15), app.debugDescription)
        best.tap()
        let play = app.buttons["gameDetail.toolbar.play"]
        XCTAssertTrue(play.waitForExistence(timeout: 20), app.debugDescription)
        play.tap()
        // The Launch choice (when the cloud check can't finish), then the player's own prompt.
        let start = app.buttons["launch.start"]
        if start.waitForExistence(timeout: 8) { start.tap() }
        let local = app.buttons["Continue with local progress"]
        if local.waitForExistence(timeout: 8) { local.tap() }
        XCTAssertTrue(app.buttons["player.control.A"].waitForExistence(timeout: 40), app.debugDescription)
        XCTAssertTrue(log.waitForExistence(timeout: 10), "Debug input log missing")
        // Let the core settle into its frame loop.
        Thread.sleep(forTimeInterval: 2)
    }

    // MARK: The log

    private func entries() -> [String] {
        let text = log.label
        return text == "-" ? [] : text.split(separator: "|").map(String.init)
    }

    /// Runs `touch` and returns the inputs the core received meanwhile (waits for them to settle).
    private func received(_ touch: () -> Void) -> [String] {
        let before = entries()
        touch()
        var last = entries()
        let deadline = Date().addingTimeInterval(3)
        while Date() < deadline {
            Thread.sleep(forTimeInterval: 0.25)
            let now = entries()
            if now == last && now.count > before.count { break }
            last = now
        }
        let received = Array(last.dropFirst(min(before.count, last.count)))
        print("TOUCHLOG|\(received.joined(separator: " "))")
        return received
    }

    private func point(_ element: XCUIElement, dx: CGFloat, dy: CGFloat) -> XCUICoordinate {
        app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: element.frame.midX + dx, dy: element.frame.midY + dy))
    }

    // MARK: Tests

    func testTouchModelOnTheRunningGame() throws {
        let a = app.buttons["player.control.A"], b = app.buttons["player.control.B"]
        XCTAssertGreaterThanOrEqual(a.frame.width, 50, "Faces are 50 pt or more")

        // At A's edge: 10 pt outside its drawn circle, straight up (away from B).
        let edge = received { point(a, dx: 0, dy: -(a.frame.height / 2 + 10)).press(forDuration: 0.12) }
        XCTAssertEqual(edge, ["D8:1", "D8:0"], "A's grown edge")

        // Between A and B: both, then both released.
        let mid = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: (a.frame.midX + b.frame.midX) / 2, dy: (a.frame.midY + b.frame.midY) / 2))
        let chord = received { mid.press(forDuration: 0.15) }
        XCTAssertEqual(Set(chord.prefix(2)), ["D0:1", "D8:1"], "Chord presses A and B: \(chord)")
        XCTAssertEqual(Set(chord.suffix(2)), ["D0:0", "D8:0"], "Chord releases both: \(chord)")
        XCTAssertEqual(chord.count, 4, "\(chord)")

        // D-pad: start on Right, drag far off the pad and hold; Right stays held until release.
        let right = app.buttons["player.control.Right"]
        let start = point(right, dx: 0, dy: 0)
        let away = point(right, dx: 170, dy: -12)
        let drag = received { start.press(forDuration: 0.1, thenDragTo: away, withVelocity: .default, thenHoldForDuration: 0.4) }
        XCTAssertEqual(drag.first, "D7:1", "\(drag)")
        XCTAssertEqual(drag.last, "D7:0", "\(drag)")
        XCTAssertEqual(drag.filter { $0 == "D7:0" }.count, 1, "Right never let go early: \(drag)")
        XCTAssertFalse(drag.contains { ["D4:1", "D5:1", "D6:1"].contains($0) }, "Direction kept: \(drag)")

        // Quick taps: every press has its release, in order; nothing is left held.
        let taps = received { for _ in 0 ..< 5 { a.tap() } }
        XCTAssertEqual(taps.filter { $0 == "D8:1" }.count, 5, "\(taps)")
        XCTAssertEqual(taps.filter { $0 == "D8:0" }.count, 5, "\(taps)")
        var held = false
        for entry in taps {
            if entry == "D8:1" { XCTAssertFalse(held, "Press before release: \(taps)"); held = true }
            if entry == "D8:0" { XCTAssertTrue(held, "Release before press: \(taps)"); held = false }
        }
        XCTAssertFalse(held, "A left held")

        // Rolling: B to A in one slide.
        let roll = received { point(b, dx: 0, dy: 0).press(forDuration: 0.1, thenDragTo: point(a, dx: 0, dy: 0), withVelocity: .slow, thenHoldForDuration: 0.1) }
        XCTAssertEqual(roll.first, "D0:1", "\(roll)")
        XCTAssertEqual(roll.last, "D8:0", "\(roll)")
        XCTAssertTrue(roll.contains("D0:0") && roll.contains("D8:1"), "\(roll)")

        // Landscape: the same edge rule.
        XCUIDevice.shared.orientation = .landscapeLeft
        Thread.sleep(forTimeInterval: 2)
        let aLandscape = app.buttons["player.control.A"]
        XCTAssertTrue(aLandscape.waitForExistence(timeout: 5))
        let edgeLandscape = received { point(aLandscape, dx: aLandscape.frame.width / 2 + 10, dy: 0).press(forDuration: 0.12) }
        XCTAssertEqual(edgeLandscape, ["D8:1", "D8:0"], "A's grown edge in landscape")
    }
}
