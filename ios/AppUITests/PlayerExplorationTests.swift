import XCTest

/// Opt-in exploratory actions against the installed QA app; never runs against a
/// user's device automatically. The opt-in JSON file supplies each pass.
@MainActor
final class PlayerExplorationTests: XCTestCase {
    func testPlayerExploration() throws {
        let path = "/tmp/rommio-player-ui-actions.json"
        guard FileManager.default.fileExists(atPath: path) else { throw XCTSkip("No exploratory actions supplied") }
        let actions = try JSONDecoder().decode([[String: String]].self, from: Data(contentsOf: URL(fileURLWithPath: path)))
        let app = XCUIApplication(bundleIdentifier: "io.github.mattsays.rommio.ios")
        app.activate()
        for action in actions {
            if let label = action["tap"] {
                let button = app.buttons.matching(NSPredicate(format: "label == %@ OR identifier == %@", label, label)).firstMatch
                guard button.waitForExistence(timeout: 10) else {
                    XCTFail("Missing \(label): \(app.debugDescription)")
                    return
                }
                if let seconds = action["hold"], let duration = Double(seconds) { button.press(forDuration: duration) } else { button.tap() }
            }
            // Taps only when the button shows up (e.g. an optional start panel).
            if let label = action["tapIfPresent"] {
                let button = app.buttons.matching(NSPredicate(format: "label == %@ OR identifier == %@", label, label)).firstMatch
                if button.waitForExistence(timeout: Double(action["timeout"] ?? "4") ?? 4) { button.tap() }
            }
            if let identifier = action["touch"] {
                let target = app.descendants(matching: .any).matching(identifier: identifier).firstMatch
                guard target.waitForExistence(timeout: 10) else { XCTFail("Missing touch surface \(identifier)"); return }
                let x = Double(action["x"] ?? "0.5") ?? 0.5
                let y = Double(action["y"] ?? "0.5") ?? 0.5
                target.coordinate(withNormalizedOffset: CGVector(dx: x, dy: y)).tap()
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
                var swipes = Int(action["max"] ?? "8") ?? 8
                while swipes > 0 && !(target.exists && target.isHittable) { app.swipeUp(); swipes -= 1 }
            }
            if action["swipe"] == "left" { app.swipeLeft() }
            if action["swipe"] == "right" { app.swipeRight() }
            if let text = action["search"] {
                let field = app.searchFields.firstMatch
                guard field.waitForExistence(timeout: 10) else { XCTFail("Missing search field"); return }
                field.tap()
                field.typeText(text)
            }
            if action["swipe"] == "up" { app.swipeUp() }
            if action["swipe"] == "down" { app.swipeDown() }
            if let direction = action["orientation"] {
                XCUIDevice.shared.orientation = direction == "portrait" ? .portrait : .landscapeLeft
            }
            if let seconds = action["wait"], let duration = Double(seconds) {
                let until = Date().addingTimeInterval(duration)
                while Date() < until { RunLoop.current.run(until: Date().addingTimeInterval(0.1)) }
            }
            if let name = action["capture"] {
                let shot = XCUIScreen.main.screenshot()
                try shot.pngRepresentation.write(to: URL(fileURLWithPath: "/tmp/rommio-ui-\(name).png"))
                try app.debugDescription.write(toFile: "/tmp/rommio-ui-\(name).txt", atomically: true, encoding: .utf8)
                let attachment = XCTAttachment(screenshot: shot); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
            }
        }
    }
}
