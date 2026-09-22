import XCTest

@MainActor
final class ExpansionControlsTests: XCTestCase {
    func testActualPlayerControlsAndMenus() throws {
        let path = "/tmp/rommio-player-fixtures.json"
        guard FileManager.default.fileExists(atPath: path) else { throw XCTSkip("No disposable player fixtures") }
        let roots = try JSONDecoder().decode([String: String].self, from: Data(contentsOf: URL(fileURLWithPath: path)))
        let app = XCUIApplication(bundleIdentifier: "io.github.mattsays.rommio.ios")
        XCUIDevice.shared.orientation = .landscapeLeft
        for slug in ["genesis", "sms", "gamegear", "n64", "psp"] {
            let root = try XCTUnwrap(roots[slug])
            XCTAssertTrue(root.hasSuffix("rommio-progress-fixture"))
            app.terminate()
            app.launchEnvironment = ["ROMMIO_APP_ROOT": root, "ROMMIO_PROGRESS_PROBE_CONFIG": root + "/config.json"]
            app.launch()
            let up = app.buttons["player.control.Up"]
            XCTAssertTrue(up.waitForExistence(timeout: 30), app.debugDescription)
            // Exercise the real settings path as well as the themed fixture.
            app.buttons["player.menu"].tap()
            app.buttons["player.pause.controls"].tap()
            let consoleColors = app.switches["player.controls.consoleColors"]
            let form = app.collectionViews.firstMatch
            XCTAssertTrue(form.waitForExistence(timeout: 10))
            for _ in 0..<8 {
                if consoleColors.exists && consoleColors.isHittable { break }
                // Scroll in the list gutter so a size/opacity slider cannot own the drag.
                form.coordinate(withNormalizedOffset: CGVector(dx: 0.18, dy: 0.6)).press(forDuration: 0.05,
                    thenDragTo: form.coordinate(withNormalizedOffset: CGVector(dx: 0.18, dy: 0.15)))
            }
            XCTAssertTrue(consoleColors.exists && consoleColors.isHittable)
            if (consoleColors.value as? String) != "1" {
                consoleColors.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.5)).tap()
            }
            XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == '1'"), object: consoleColors)], timeout: 5), .completed)
            app.buttons["Done"].tap()
            XCTAssertTrue(up.waitForExistence(timeout: 10))
            up.press(forDuration: 0.15)
            let label = ["sms": "1", "gamegear": "1", "psp": "Cross"][slug] ?? "A"
            app.buttons["player.control." + label].press(forDuration: 0.15)
            let screenshot = XCUIScreen.main.screenshot()
            try screenshot.pngRepresentation.write(to: URL(fileURLWithPath: "/tmp/rommio-player-\(slug)-ios.png"))
            let attachment = XCTAttachment(screenshot: screenshot); attachment.name = "player-\(slug)"; attachment.lifetime = .keepAlways; add(attachment)
            app.buttons["player.menu"].tap()
            let save = app.buttons["player.pause.quickSave"]
            XCTAssertTrue(save.waitForExistence(timeout: 10)); save.tap()
            app.buttons["player.pause.stateBrowser"].tap()
            let gameSaves = app.buttons["Game saves"]
            XCTAssertTrue(gameSaves.waitForExistence(timeout: 10)); gameSaves.tap()
            XCTAssertTrue(app.buttons["Restore game-save data"].firstMatch.waitForExistence(timeout: 10))
            app.buttons["Snapshots"].tap()
            let restore = app.buttons["Restore snapshot"].firstMatch
            for _ in 0..<5 where !restore.exists { app.swipeUp() }
            XCTAssertTrue(restore.waitForExistence(timeout: 15), app.debugDescription)
            restore.tap()
            let confirm = app.buttons["Preserve a safety checkpoint and restore"]
            if confirm.waitForExistence(timeout: 1) { confirm.tap() }
            app.buttons["Save and sync status"].tap()
            XCTAssertTrue(app.staticTexts["Restored progress. The safety checkpoint is retained."].waitForExistence(timeout: 10), app.debugDescription)
            app.buttons["Done"].tap()
            XCTAssertTrue(app.buttons["player.menu"].waitForExistence(timeout: 10))
            app.buttons["player.menu"].tap()
            app.buttons["player.pause.reset"].tap()
            app.buttons["player.pause.leave"].tap()
            XCTAssertTrue(app.buttons["player.menu"].waitForNonExistence(timeout: 15), app.debugDescription)
        }
        app.terminate(); app.launchEnvironment = [:]; app.launch()
    }

    func testConsoleControls() throws {
        guard ProcessInfo.processInfo.environment["ROMMIO_CONTROLS_TEST"] == "1" || FileManager.default.fileExists(atPath: "/tmp/rommio-controls-test") else { throw XCTSkip("Opt-in console controls test") }
        let contract = try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: "/tmp/rommio-controls-contract.json"))) as! [String: [String: Any]]
        let app = XCUIApplication(bundleIdentifier: "io.github.mattsays.rommio.ios")
        XCUIDevice.shared.orientation = .landscapeLeft
        for family in contract.keys.sorted() {
            app.terminate()
            app.launchEnvironment = ["ROMMIO_CONTROLS_QA": family]
            app.launch()
            let events = app.staticTexts["qa.events"]
            XCTAssertTrue(events.waitForExistence(timeout: 15))
            for control in contract[family]!["buttons"] as! [[String: Any]] {
                let label = control["label"] as! String
                let target = app.buttons["player.control.\(label)"]
                XCTAssertTrue(target.exists, "\(family) \(label)")
                XCTAssertTrue(target.isHittable, "\(family) \(label)")
                XCTAssertGreaterThanOrEqual(target.frame.width, 44, "Small target \(family) \(label)")
                XCTAssertFalse(app.otherElements["qa.viewport"].frame.intersects(target.frame), "Control obscures game: \(family) \(label)")
                XCTAssertTrue(app.frame.contains(target.frame), "Clipped \(family) \(label): \(target.frame)")
                if let combo = control["combo"] as? [Int] {
                    target.press(forDuration: 0.1)
                    for key in combo { XCTAssertTrue(events.label.contains("D\(key):1")); XCTAssertTrue(events.label.contains("D\(key):0")) }
                } else if let key = control["key"] as? Int {
                    target.press(forDuration: 0.08)
                    XCTAssertTrue(events.label.contains("D\(key):1"), "Missing press \(family) \(label): \(events.label)")
                    XCTAssertTrue(events.label.contains("D\(key):0"), "Missing release \(family) \(label): \(events.label)")
                } else if let stick = control["stick"] as? Int {
                    let start = target.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
                    let end = target.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5))
                    start.press(forDuration: 0.1, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: 0.1)
                    // Fractional target centers round to simulator pixels. A horizontal
                    // drag can produce a 1% orthogonal offset on compact controls.
                    let reachedRight = events.label.split(separator: "|").contains { event in
                        let parts = event.split(separator: ":")
                        guard parts.count == 2, parts[0] == "A\(stick)" else { return false }
                        let axes = parts[1].split(separator: ",").compactMap { Int($0) }
                        return axes.count == 2 && axes[0] >= 98 && abs(axes[1]) <= 2
                    }
                    XCTAssertTrue(reachedRight, "Missing full right \(family): \(events.label)")
                    XCTAssertTrue(events.label.hasSuffix("A\(stick):0,0"), "Stick did not center")
                } else {
                    target.press(forDuration: 0.08)
                    let axis = control["axis"] as! [Int]
                    XCTAssertTrue(events.label.contains("A1:\(axis[0]*100),\(axis[1]*100)"), "Missing C direction: \(events.label)")
                    XCTAssertTrue(events.label.hasSuffix("A1:0,0"))
                }
            }
            let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); attachment.name = "controls-\(family)-landscape"; attachment.lifetime = .keepAlways; add(attachment)
            try XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: "/tmp/rommio-controls-\(family)-ios.png"))
            let up = app.buttons["player.control.Up"]
            let right = app.buttons["player.control.Right"]
            // One thumb can slide from Up into Up+Right and release both.
            let diagonal = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: right.frame.midX, dy: up.frame.midY))
            up.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).press(forDuration: 0.1, thenDragTo: diagonal)
            XCTAssertTrue(events.label.contains("D4:1") && events.label.contains("D7:1"), events.label)
            XCTAssertTrue(events.label.contains("D4:0") && events.label.contains("D7:0"), events.label)
            up.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).press(forDuration: 0.1, thenDragTo: up.coordinate(withNormalizedOffset: CGVector(dx: 2, dy: 0.5)))
            XCTAssertTrue(events.label.contains("D4:0"), "Drag-out release missing")
            app.buttons["Mirror"].tap()
            XCTAssertTrue(app.buttons["player.control.Start"].exists || app.buttons["player.control.Pause"].exists)
            app.buttons["player.menu"].tap()
            XCTAssertFalse(app.buttons["player.control.Up"].exists)
            XCTAssertTrue(app.buttons["player.menu"].isHittable)
            app.buttons["player.menu"].tap()
            XCTAssertTrue(app.buttons["player.control.Up"].isHittable)
            XCUIDevice.shared.orientation = .portrait
            XCTAssertTrue(app.buttons["player.control.Up"].isHittable)
            XCTAssertTrue(app.buttons["player.menu"].isHittable)
            for control in contract[family]!["buttons"] as! [[String: Any]] {
                let target = app.buttons["player.control.\(control["label"] as! String)"]
                XCTAssertTrue(app.frame.contains(target.frame), "Portrait clip \(family)")
                XCTAssertFalse(app.otherElements["qa.viewport"].frame.intersects(target.frame), "Portrait overlap \(family)")
            }
            try XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: "/tmp/rommio-controls-\(family)-portrait-ios.png"))
            XCUIDevice.shared.orientation = .landscapeLeft
        }
        app.terminate(); app.launchEnvironment = [:]; app.launch()
    }
}
