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
            if let identifier = action["touch"] {
                let target = app.descendants(matching: .any).matching(identifier: identifier).firstMatch
                guard target.waitForExistence(timeout: 10) else { XCTFail("Missing touch surface \(identifier)"); return }
                let x = Double(action["x"] ?? "0.5") ?? 0.5
                let y = Double(action["y"] ?? "0.5") ?? 0.5
                target.coordinate(withNormalizedOffset: CGVector(dx: x, dy: y)).tap()
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
