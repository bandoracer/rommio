import XCTest

/// Touch controls v2 (docs/player-controls.md) through the controls QA harness
/// (`ROMMIO_CONTROLS_QA`), which draws the real stage and touch surface over a stand-in
/// screen and lists what the core would receive (`qa.events`, the last eight):
/// `D<key>:1/0` for buttons. It plays no game and writes no saves, so this runs on a
/// signed-in phone. Keys are libretro's: B 0, Select 2, Start 3, Up 4, Down 5, Left 6,
/// Right 7, A 8, L 10, R 11.
///
/// Opt-in: a runner built with `--controls` (`scripts/ios/run_controls_pass.sh`), or the
/// simulator host file `/tmp/rommio-stick-tests`.
@MainActor
final class PlayerHarnessButtonTests: XCTestCase {
    private var app: XCUIApplication!
    private var appearance: [String] = []
    private var config: [String: String] = [:]
    private var events: XCUIElement { app.staticTexts["qa.events"] }

    override func setUp() async throws {
        continueAfterFailure = false
        config = UIActionRunner.config(for: Self.self)
        var optedIn = config["controls"] == "1"
        #if targetEnvironment(simulator)
        optedIn = optedIn || FileManager.default.fileExists(atPath: "/tmp/rommio-stick-tests")
        #endif
        guard optedIn else { throw XCTSkip("Opt-in controls pass") }
        appearance = UIActionRunner.appearanceArguments(config)
        app = XCUIApplication(bundleIdentifier: "io.github.mattsays.rommio.ios")
    }

    override func tearDown() async throws {
        app?.terminate()
        XCUIDevice.shared.orientation = .portrait
    }

    private func launch(_ family: String, _ orientation: UIDeviceOrientation, analog: Bool = true) {
        app.terminate()
        XCUIDevice.shared.orientation = orientation
        app.launchArguments = appearance
        app.launchEnvironment = ["ROMMIO_CONTROLS_QA": family, "ROMMIO_CONTROLS_QA_ANALOG": analog ? "1" : "0"]
        app.launch()
        XCTAssertTrue(events.waitForExistence(timeout: 15))
        Thread.sleep(forTimeInterval: 1.5)
    }

    private func recent() -> [String] { events.label.split(separator: "|").map(String.init) }
    private func control(_ label: String) -> XCUIElement { app.buttons["player.control.\(label)"] }
    private func centre(_ element: XCUIElement) -> XCUICoordinate {
        element.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
    }

    /// A tap 10 pt outside a button's drawn edge still presses it (hit regions grow 14 pt).
    func testATapJustOutsideAButtonPressesIt() throws {
        launch("nes", .portrait)
        let a = control("A")
        XCTAssertTrue(a.isHittable)
        XCTAssertGreaterThanOrEqual(a.frame.width, 60, "Two-button faces are 64 pt")
        centre(a).withOffset(CGVector(dx: a.frame.width / 2 + 10, dy: 0)).tap()
        XCTAssertEqual(recent().suffix(2), ["D8:1", "D8:0"], events.label)
    }

    /// Between A and B presses both (the chord), and releases both.
    func testATapBetweenAAndBPressesBoth() throws {
        launch("nes", .portrait)
        let aFrame = control("A").frame, bFrame = control("B").frame
        let mid = centre(control("B")).withOffset(CGVector(dx: (aFrame.midX - bFrame.midX) / 2, dy: (aFrame.midY - bFrame.midY) / 2))
        mid.tap()
        let last = Array(recent().suffix(4))
        XCTAssertTrue(last.contains("D8:1") && last.contains("D0:1") && last.contains("D8:0") && last.contains("D0:0"), events.label)
    }

    /// Quick taps never leave a button held.
    func testQuickTapsNeverLeaveAButtonHeld() throws {
        launch("nes", .portrait)
        let a = centre(control("A"))
        for _ in 0..<6 { a.tap() }
        let presses = recent().filter { $0.hasPrefix("D8:") }
        XCTAssertEqual(presses.last, "D8:0", events.label)
        XCTAssertFalse(zip(presses, presses.dropFirst()).contains { $0 == "D8:1" && $1 == "D8:1" }, "Never pressed twice without a release: \(events.label)")
    }

    /// Sliding the thumb from B onto A releases B and presses A.
    func testSlidingFromBOntoARollsThePress() throws {
        launch("nes", .portrait)
        let b = centre(control("B")), a = centre(control("A"))
        b.press(forDuration: 0.15, thenDragTo: a, withVelocity: .slow, thenHoldForDuration: 0.2)
        let log = recent()
        guard let bDown = log.lastIndex(of: "D0:1"), let bUp = log.lastIndex(of: "D0:0"), let aDown = log.lastIndex(of: "D8:1") else {
            return XCTFail("Expected B then A: \(events.label)")
        }
        XCTAssertLessThan(bDown, bUp, events.label)
        XCTAssertLessThanOrEqual(bUp, aDown + 1, "B lets go as A takes the thumb: \(events.label)")
        XCTAssertEqual(log.last, "D8:0", "A releases on lift: \(events.label)")
    }

    /// A d-pad direction stays held while the thumb drifts off the pad, until it lifts.
    func testTheDPadKeepsItsDirectionOffThePad() throws {
        launch("nes", .portrait)
        let right = control("Right")
        XCTAssertTrue(right.exists)
        // Straight right, well past the pad's edge (toward the middle of the screen).
        let start = centre(right)
        start.press(forDuration: 0.15, thenDragTo: start.withOffset(CGVector(dx: 120, dy: 0)), withVelocity: .slow, thenHoldForDuration: 0.3)
        let log = recent()
        guard let down = log.lastIndex(of: "D7:1") else { return XCTFail("Right pressed: \(events.label)") }
        // Held the whole way: the only release after the press is the lift, and it is last.
        let releasesAfter = log[(down + 1)...].filter { $0 == "D7:0" }
        XCTAssertEqual(releasesAfter.count, 1, "Right released only on lift: \(events.label)")
        XCTAssertEqual(log.last, "D7:0", events.label)
    }

    /// GBA shoulders sit above their clusters and press L/R.
    func testGBAShouldersPressAndSitAboveTheirClusters() throws {
        launch("gba", .portrait)
        let l = control("L"), r = control("R")
        XCTAssertTrue(l.isHittable && r.isHittable)
        XCTAssertLessThan(l.frame.maxY, control("Up").frame.minY, "L above the d-pad")
        XCTAssertLessThan(r.frame.maxY, control("A").frame.minY, "R above the face buttons")
        centre(l).tap()
        XCTAssertEqual(recent().suffix(2), ["D10:1", "D10:0"], events.label)
        centre(r).tap()
        XCTAssertEqual(recent().suffix(2), ["D11:1", "D11:0"], events.label)
    }

    /// Screenshots of every family's layout in this run's theme and appearance.
    func testCaptureEveryFamilyLayout() throws {
        let prefix = "controls-\(config["theme"] ?? "theme")-\(config["scheme"] ?? "system")"
        for family in ["nes", "gba", "snes", "n64", "psx", "psp", "3ds", "nds"] {
            for orientation in [UIDeviceOrientation.portrait, .landscapeLeft] {
                launch(family, orientation)
                let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
                shot.name = "\(prefix)-\(family)-\(orientation == .portrait ? "portrait" : "landscape")"
                shot.lifetime = .keepAlways
                add(shot)
            }
        }
    }
}
