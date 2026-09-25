import XCTest

/// Runs a list of UI actions against the installed Rommio app. Used by the opt-in
/// exploration pass (actions from JSON) and the navigation pass. Runs on a
/// simulator or a device: nothing here reads the host file system, and captures are
/// test attachments (on a simulator they are also written to `/tmp/rommio-ui-<name>.png`
/// for the screenshot scripts).
///
/// Actions (every value is a string):
/// - `launch`: space-separated launch arguments (e.g. `-appearance.theme riso -appearance.scheme dark`);
///   `env` adds `KEY=VALUE;KEY=VALUE` to the launch environment. Relaunches the app.
/// - `tap` / `tapIfPresent` (+ `timeout`, `hold`): a button by label or identifier.
/// - `tapFirst`: the first element whose identifier starts with the value (scrolls up to `max` swipes to find it).
/// - `tapTab`: a tab bar item by label (ending an active search first); fails when the tab does not
///   open. Tapping twice returns the tab to its root.
/// - `back`: the navigation bar's back button.
/// - `touch` (+ `x`, `y`): a normalised point on an element by identifier.
/// - `drag` (+ `from`, `to`): a horizontal drag across an element by identifier (normalised x).
/// - `pull`: a slow drag down from an element by identifier (pull to refresh).
/// - `type`, `field` (+ `text`), `search`: text entry.
/// - `scrollTo` (+ `max`): swipe up until an identifier is hittable.
/// - `swipe`: up, down, left or right. `orientation`: portrait or landscape.
/// - `wait`: seconds. `waitFor` (+ `timeout`): an identifier to appear.
/// - `capture`: a screenshot and the accessibility tree under that name.
/// After every action the app must still be running in the foreground (a crash fails the pass).
@MainActor
struct UIActionRunner {
    static let bundleIdentifier = "io.github.mattsays.rommio.ios"

    let app: XCUIApplication
    let test: XCTestCase

    init(test: XCTestCase, app: XCUIApplication = XCUIApplication(bundleIdentifier: UIActionRunner.bundleIdentifier)) {
        self.test = test
        self.app = app
    }

    /// The actions embedded in the runner bundle (`rommio-ui-actions.json`, written by
    /// `scripts/ios/generate_player_ui_runner.rb --actions`), else the simulator
    /// host's `/tmp/rommio-player-ui-actions.json`; nil when neither exists.
    static func embeddedActions(for test: AnyClass) throws -> [[String: String]]? {
        let bundled = Bundle(for: test).url(forResource: "rommio-ui-actions", withExtension: "json")
        #if targetEnvironment(simulator)
        let host = URL(fileURLWithPath: "/tmp/rommio-player-ui-actions.json")
        let url = bundled ?? (FileManager.default.fileExists(atPath: host.path) ? host : nil)
        #else
        let url = bundled
        #endif
        guard let url else { return nil }
        return try JSONDecoder().decode([[String: String]].self, from: Data(contentsOf: url))
    }

    /// Settings for this run from the bundle (`rommio-ui-config.json`) or the test
    /// runner's environment (`TEST_RUNNER_ROMMIO_THEME=riso xcodebuild test …`).
    static func config(for test: AnyClass) -> [String: String] {
        var values: [String: String] = [:]
        if let url = Bundle(for: test).url(forResource: "rommio-ui-config", withExtension: "json"),
           let data = try? Data(contentsOf: url),
           let decoded = try? JSONDecoder().decode([String: String].self, from: data) {
            values = decoded
        }
        let environment = ProcessInfo.processInfo.environment
        for key in ["theme", "scheme", "query", "iterations", "capturePrefix"] {
            if let value = environment["ROMMIO_" + key.uppercased()], !value.isEmpty { values[key] = value }
        }
        return values
    }

    /// `-appearance.theme <theme> -appearance.scheme <scheme>`: the UserDefaults argument
    /// domain, so the choice applies to this launch only and is never saved.
    static func appearanceArguments(_ config: [String: String]) -> [String] {
        var arguments: [String] = []
        if let theme = config["theme"] { arguments += ["-appearance.theme", theme] }
        if let scheme = config["scheme"] { arguments += ["-appearance.scheme", scheme] }
        return arguments
    }

    func launch(arguments: [String], environment: [String: String] = [:]) {
        app.terminate()
        app.launchArguments = arguments
        app.launchEnvironment = environment
        app.launch()
    }

    func run(_ actions: [[String: String]]) throws {
        for (index, action) in actions.enumerated() {
            try perform(action)
            // A crash (for example the main thread's stack) ends the pass here.
            if action["launch"] == nil && action["allowBackground"] == nil {
                XCTAssertEqual(app.state, .runningForeground, "The app is not running after step \(index + 1): \(action)")
                if app.state != .runningForeground { return }
            }
        }
    }

    // MARK: Actions

    func perform(_ action: [String: String]) throws {
        if let arguments = action["launch"] {
            var environment: [String: String] = [:]
            for pair in (action["env"] ?? "").split(separator: ";") {
                let parts = pair.split(separator: "=", maxSplits: 1).map(String.init)
                if parts.count == 2 { environment[parts[0]] = parts[1] }
            }
            launch(arguments: arguments.split(separator: " ").map(String.init), environment: environment)
        }
        if let label = action["tap"] {
            let button = self.button(label)
            guard button.waitForExistence(timeout: timeout(action, 10)) else {
                XCTFail("Missing \(label): \(app.debugDescription)")
                return
            }
            if let seconds = action["hold"], let duration = Double(seconds) { button.press(forDuration: duration) } else { button.tap() }
        }
        // Taps only when the button shows up (e.g. an optional start panel).
        if let label = action["tapIfPresent"] {
            let button = self.button(label)
            if button.waitForExistence(timeout: timeout(action, 4)) { button.tap() }
        }
        if let prefix = action["tapFirst"] {
            let target = first(prefix)
            scroll(until: target, max: Int(action["max"] ?? "8") ?? 8)
            guard target.waitForExistence(timeout: timeout(action, 10)) else {
                XCTFail("Missing an element starting with \(prefix): \(app.debugDescription)")
                return
            }
            target.tap()
        }
        if let label = action["tapTab"] {
            // A keyboard (an active search) covers the tab bar and would take the tap.
            endEditing()
            let inBar = app.tabBars.buttons[label].exists
            let tab = inBar ? app.tabBars.buttons[label] : button(label)
            guard tab.waitForExistence(timeout: timeout(action, 10)) else { XCTFail("Missing tab \(label)"); return }
            tab.tap()
            if inBar && !selected(tab, within: 5) { XCTFail("The \(label) tab did not open: \(app.debugDescription)") }
        }
        if action["back"] != nil {
            let back = app.navigationBars.buttons.element(boundBy: 0)
            guard back.waitForExistence(timeout: timeout(action, 10)) else { XCTFail("No back button"); return }
            back.tap()
        }
        if let identifier = action["touch"] {
            let target = app.descendants(matching: .any).matching(identifier: identifier).firstMatch
            guard target.waitForExistence(timeout: 10) else { XCTFail("Missing touch surface \(identifier)"); return }
            let x = Double(action["x"] ?? "0.5") ?? 0.5
            let y = Double(action["y"] ?? "0.5") ?? 0.5
            target.coordinate(withNormalizedOffset: CGVector(dx: x, dy: y)).tap()
        }
        // Drags across an element by identifier from `from` to `to` (normalised x, at mid height).
        if let identifier = action["drag"] {
            let target = app.descendants(matching: .any).matching(identifier: identifier).firstMatch
            guard target.waitForExistence(timeout: 10) else { XCTFail("Missing drag surface \(identifier)"); return }
            let from = Double(action["from"] ?? "0.85") ?? 0.85, to = Double(action["to"] ?? "0.15") ?? 0.15
            target.coordinate(withNormalizedOffset: CGVector(dx: from, dy: 0.5))
                .press(forDuration: 0.05, thenDragTo: target.coordinate(withNormalizedOffset: CGVector(dx: to, dy: 0.5)))
        }
        // Pulls down from an element by identifier (pull to refresh).
        if let identifier = action["pull"] {
            let target = app.descendants(matching: .any).matching(identifier: identifier).firstMatch
            guard target.waitForExistence(timeout: 10) else { XCTFail("Missing pull surface \(identifier)"); return }
            let start = target.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
            start.press(forDuration: 0.05, thenDragTo: start.withOffset(CGVector(dx: 0, dy: 420)), withVelocity: .slow, thenHoldForDuration: 0.3)
        }
        if let text = action["type"] { app.typeText(text) }
        // Types into a text field found by identifier or label (e.g. a rename alert's field).
        if let identifier = action["field"] {
            let field = app.textFields.matching(NSPredicate(format: "identifier == %@ OR label == %@ OR placeholderValue == %@", identifier, identifier, identifier)).firstMatch
            guard field.waitForExistence(timeout: 10) else { XCTFail("Missing field \(identifier): \(app.debugDescription)"); return }
            field.tap()
            if let text = action["text"] { field.typeText(text) }
        }
        // Swipes up until an element with this identifier is on screen (at most `max` swipes).
        if let identifier = action["scrollTo"] {
            let target = app.descendants(matching: .any).matching(identifier: identifier).firstMatch
            scroll(until: target, max: Int(action["max"] ?? "8") ?? 8)
        }
        if let text = action["search"] {
            let field = app.searchFields.firstMatch
            guard field.waitForExistence(timeout: 10) else { XCTFail("Missing search field"); return }
            field.tap()
            field.typeText(text)
        }
        switch action["swipe"] {
        case "up": app.swipeUp()
        case "down": app.swipeDown()
        case "left": app.swipeLeft()
        case "right": app.swipeRight()
        default: break
        }
        if let direction = action["orientation"] {
            XCUIDevice.shared.orientation = direction == "portrait" ? .portrait : .landscapeLeft
        }
        if let identifier = action["waitFor"] {
            let target = app.descendants(matching: .any).matching(identifier: identifier).firstMatch
            if !target.waitForExistence(timeout: timeout(action, 10)) { XCTFail("\(identifier) did not appear") }
        }
        if let seconds = action["wait"], let duration = Double(seconds) {
            let until = Date().addingTimeInterval(duration)
            while Date() < until { RunLoop.current.run(until: Date().addingTimeInterval(0.1)) }
        }
        if let name = action["capture"] { try capture(name) }
    }

    /// A screenshot and the accessibility tree, kept as attachments (and in the
    /// simulator host's /tmp for the screenshot scripts).
    func capture(_ name: String) throws {
        let shot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: shot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        test.add(attachment)
        let tree = XCTAttachment(string: app.debugDescription)
        tree.name = name + ".txt"
        tree.lifetime = .keepAlways
        test.add(tree)
        #if targetEnvironment(simulator)
        try shot.pngRepresentation.write(to: URL(fileURLWithPath: "/tmp/rommio-ui-\(name).png"))
        try app.debugDescription.write(toFile: "/tmp/rommio-ui-\(name).txt", atomically: true, encoding: .utf8)
        #endif
    }

    // MARK: Queries

    func button(_ label: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label == %@ OR identifier == %@", label, label)).firstMatch
    }

    /// The first element (buttons first) whose identifier starts with `prefix`.
    func first(_ prefix: String) -> XCUIElement {
        let predicate = NSPredicate(format: "identifier BEGINSWITH %@", prefix)
        let button = app.buttons.matching(predicate).firstMatch
        return button.exists ? button : app.descendants(matching: .any).matching(predicate).firstMatch
    }

    /// Ends an active search (its Close button) so the keyboard no longer covers the tab bar.
    func endEditing() {
        guard app.keyboards.firstMatch.exists else { return }
        let close = app.buttons.matching(NSPredicate(format: "label == 'Close' OR label == 'Cancel'")).firstMatch
        if close.exists && close.isHittable { close.tap() }
        let gone = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.keyboards.firstMatch)
        _ = XCTWaiter.wait(for: [gone], timeout: 3)
    }

    private func selected(_ element: XCUIElement, within seconds: TimeInterval) -> Bool {
        let isSelected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isSelected == true"), object: element)
        return XCTWaiter.wait(for: [isSelected], timeout: seconds) == .completed
    }

    func scroll(until target: XCUIElement, max: Int) {
        var swipes = max
        while swipes > 0 && !(target.exists && target.isHittable) {
            app.swipeUp()
            swipes -= 1
        }
    }

    private func timeout(_ action: [String: String], _ fallback: TimeInterval) -> TimeInterval {
        Double(action["timeout"] ?? "") ?? fallback
    }
}
