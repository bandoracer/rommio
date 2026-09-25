import XCTest

/// Analog sticks and per-console layouts (docs/player-controls.md) in the controls QA
/// harness (`ROMMIO_CONTROLS_QA`): a stick's centre floats to where the touch lands and
/// full deflection follows its travel; PlayStation's sticks, L3 and R3 show only with
/// Analog controls; taps on a DS or 3DS touch screen, even right beside a control,
/// reach the game (the harness logs them as `screenN:x,y`).
///
/// Opt-in (simulator): the host file `/tmp/rommio-stick-tests`.
@MainActor
final class PlayerStickControlsTests: XCTestCase {
    private var app: XCUIApplication!
    private var events: XCUIElement { app.staticTexts["qa.events"] }

    override func setUp() async throws {
        continueAfterFailure = false
        #if targetEnvironment(simulator)
        guard FileManager.default.fileExists(atPath: "/tmp/rommio-stick-tests") else { throw XCTSkip("Opt-in stick controls pass") }
        #else
        throw XCTSkip("Simulator only")
        #endif
        app = XCUIApplication(bundleIdentifier: "io.github.mattsays.rommio.ios")
    }

    override func tearDown() async throws {
        app?.terminate()
        XCUIDevice.shared.orientation = .portrait
    }

    private func launch(_ family: String, _ orientation: UIDeviceOrientation, analog: Bool = true) {
        app.terminate()
        XCUIDevice.shared.orientation = orientation
        app.launchEnvironment = ["ROMMIO_CONTROLS_QA": family, "ROMMIO_CONTROLS_QA_ANALOG": analog ? "1" : "0"]
        app.launch()
        XCTAssertTrue(events.waitForExistence(timeout: 15))
        // Read frames once the rotation has settled.
        Thread.sleep(forTimeInterval: 1.5)
    }

    /// The harness shows its last eight events.
    private func recent() -> [String] { events.label.split(separator: "|").map(String.init) }

    private func point(_ element: XCUIElement, _ dx: CGFloat, _ dy: CGFloat) -> XCUICoordinate {
        element.coordinate(withNormalizedOffset: CGVector(dx: dx, dy: dy))
    }

    private func axes(_ event: String, stick: Int) -> (x: Int, y: Int)? {
        let parts = event.split(separator: ":")
        guard parts.count == 2, parts[0] == "A\(stick)" else { return nil }
        let values = parts[1].split(separator: ",").compactMap { Int($0) }
        return values.count == 2 ? (values[0], values[1]) : nil
    }

    func testTheStickCentreFloatsToTheTouch() throws {
        launch("n64", .landscapeLeft)
        let stick = app.buttons["player.control.Analog stick"]
        XCTAssertTrue(stick.exists)
        XCTAssertGreaterThanOrEqual(stick.frame.width, 120, "The primary zone")
        // Holding still left of the zone's centre: that point is the centre, so no deflection.
        point(stick, 0.36, 0.5).press(forDuration: 0.3)
        XCTAssertEqual(recent().suffix(2), ["A0:0,0", "A0:0,0"], "A touch-down is the centre: \(events.label)")
        // From there, the travel to the right is full right (from the zone's centre it would not be).
        // The N64 profile's travel (48 pt) at the zone's scale (160 pt).
        let travel = stick.frame.width * 48 / 160
        let start = point(stick, 0.36, 0.5)
        start.press(forDuration: 0.2, thenDragTo: start.withOffset(CGVector(dx: travel + 6, dy: 0)), withVelocity: .slow, thenHoldForDuration: 0.2)
        let values = recent().compactMap { axes($0, stick: 0) }
        XCTAssertTrue(values.contains { $0.x >= 98 && abs($0.y) <= 2 }, "Full right from the floating centre: \(events.label)")
        XCTAssertEqual(recent().last, "A0:0,0", "Back to centre on lift")
        // Dragged far outside the zone, the touch keeps the stick.
        let far = point(stick, 0.5, 0.5)
        far.press(forDuration: 0.2, thenDragTo: far.withOffset(CGVector(dx: 0, dy: -stick.frame.height)), withVelocity: .slow, thenHoldForDuration: 0.2)
        XCTAssertTrue(recent().compactMap { axes($0, stick: 0) }.contains { $0.y <= -98 }, "Full up outside the zone: \(events.label)")
        XCTAssertEqual(recent().last, "A0:0,0")
    }

    func testPlayStationShowsSticksAndClicksOnlyWithAnalogControls() throws {
        launch("psx", .landscapeLeft, analog: false)
        for hidden in ["Analog stick", "Right stick", "L3", "R3"] {
            XCTAssertFalse(app.buttons["player.control.\(hidden)"].exists, "\(hidden) without Analog controls")
        }
        XCTAssertTrue(app.buttons["player.control.Analog"].exists, "The Analog mode button stays")
        launch("psx", .landscapeLeft, analog: true)
        for shown in ["Analog stick", "Right stick", "L3", "R3", "Analog"] {
            XCTAssertTrue(app.buttons["player.control.\(shown)"].isHittable, "\(shown) with Analog controls")
        }
        app.buttons["player.control.L3"].press(forDuration: 0.08)
        XCTAssertTrue(recent().suffix(2) == ["D14:1", "D14:0"], events.label)
    }

    func testTouchesOnATouchScreenReachTheGame() throws {
        for family in ["3ds", "nds"] {
            launch(family, .portrait)
            let screen = app.otherElements["qa.viewport.1"]
            XCTAssertTrue(screen.exists, "\(family) touch screen")
            let frame = screen.frame
            // Its centre and its lower corners, the points nearest the controls.
            for (dx, dy) in [(0.5, 0.5), (0.02, 0.97), (0.98, 0.97)] as [(CGFloat, CGFloat)] {
                point(screen, dx, dy).tap()
                let last = recent().last ?? ""
                XCTAssertTrue(last.hasPrefix("screen1:"), "\(family) tap at \(dx),\(dy) of \(frame) went to: \(events.label)")
            }
            XCTAssertFalse(recent().contains { $0.hasPrefix("D") || $0.hasPrefix("A") }, "No control fired: \(events.label)")
            if family == "3ds" {
                // The circle pad drives the left analog stick.
                let pad = app.buttons["player.control.Circle Pad"]
                XCTAssertTrue(pad.isHittable)
                let start = point(pad, 0.5, 0.5)
                start.press(forDuration: 0.2, thenDragTo: start.withOffset(CGVector(dx: pad.frame.width * 0.4, dy: 0)), withVelocity: .slow, thenHoldForDuration: 0.2)
                XCTAssertTrue(recent().compactMap { axes($0, stick: 0) }.contains { $0.x >= 98 }, "Circle pad: \(events.label)")
                XCTAssertGreaterThan(app.buttons["player.control.Down"].frame.minY, pad.frame.maxY, "D-pad below the circle pad")
            }
        }
    }
}
