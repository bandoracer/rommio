import XCTest

/// Walks the app's page list against the signed-in account on this device or
/// simulator: every tab, Downloads, a platform, a game, a collection, Search and
/// its best match, Stats, and each Settings sub-page, going back after each push.
/// Every stop is captured as an attachment. The app must stay in the foreground
/// throughout (the pass fails at the first crash). The transition tests measure
/// pushes with `XCTOSSignpostMetric.navigationTransitionMetric`.
///
/// Theme and appearance come from `rommio-ui-config.json` in the runner bundle
/// (`scripts/ios/generate_player_ui_runner.rb --theme riso --scheme dark`) or
/// `TEST_RUNNER_ROMMIO_THEME` / `TEST_RUNNER_ROMMIO_SCHEME`, passed to the app as
/// `-appearance.theme` / `-appearance.scheme` (not saved). Read-only: it plays,
/// downloads and changes nothing.
@MainActor
final class NavigationPassTests: XCTestCase {
    private var runner: UIActionRunner!
    private var config: [String: String] = [:]
    private var app: XCUIApplication { runner.app }

    override func setUp() async throws {
        continueAfterFailure = false
        config = UIActionRunner.config(for: Self.self)
        guard config["theme"] != nil || ProcessInfo.processInfo.environment["ROMMIO_NAVIGATION_PASS"] == "1" else {
            throw XCTSkip("Opt-in: build the runner with --theme/--scheme or set TEST_RUNNER_ROMMIO_THEME")
        }
        runner = UIActionRunner(test: self)
        runner.launch(arguments: UIActionRunner.appearanceArguments(config))
        XCTAssertTrue(app.tabBars.buttons["Play"].waitForExistence(timeout: 60) || app.buttons["Play"].waitForExistence(timeout: 1),
                      "The app did not reach its tabs (signed in?)")
    }

    private var prefix: String {
        config["capturePrefix"] ?? "nav-\(config["theme"] ?? "theme")-\(config["scheme"] ?? "system")"
    }

    private func step(_ actions: [String: String]...) throws { try runner.run(actions) }
    private func capture(_ name: String) throws { try runner.capture("\(prefix)-\(name)") }
    private func settle(_ seconds: String = "1.5") throws { try step(["wait": seconds]) }

    private func tab(_ label: String) throws {
        // The second tap returns a tab to its root.
        try step(["tapTab": label], ["wait": "0.8"], ["tapTab": label])
        try settle()
    }

    private func exists(_ prefix: String, swipes: Int = 8) -> XCUIElement? {
        let target = runner.first(prefix)
        runner.scroll(until: target, max: swipes)
        return target.exists ? target : nil
    }

    private func assertRunning(_ where: String) {
        XCTAssertEqual(app.state, .runningForeground, "The app stopped at \(`where`)")
    }

    // MARK: The pass

    func testNavigationPass() throws {
        // Play and Downloads.
        try tab("Play")
        try capture("play")
        try step(["swipe": "up"], ["wait": "1"])
        try capture("play-scrolled")
        try step(["swipe": "down"], ["wait": "0.5"], ["tap": "play.downloads"], ["wait": "2"])
        try capture("downloads")
        try step(["back": "1"], ["wait": "1"])

        // Library, a platform and one of its games.
        try tab("Library")
        try capture("library")
        if let platform = exists("library.platform.") {
            platform.tap()
            try step(["waitFor": "platform.filter", "timeout": "20"], ["wait": "2"])
            try capture("platform")
            if let game = exists("platform.game.", swipes: 3) {
                game.tap()
                try step(["waitFor": "gameDetail.hero", "timeout": "20"], ["wait": "2"])
                try capture("game")
                try step(["swipe": "up"], ["wait": "1"], ["swipe": "up"], ["wait": "1"])
                try capture("game-scrolled")
                try step(["back": "1"], ["wait": "1"])
            }
            try step(["back": "1"], ["wait": "1"])
        }
        assertRunning("Library › Platform")

        // Library, a collection and one of its games.
        try tab("Library")
        if let collection = exists("library.collection.", swipes: 12) {
            collection.tap()
            try step(["wait": "3"])
            try capture("collection")
            if let game = exists("collection.game.", swipes: 2) {
                game.tap()
                try step(["waitFor": "gameDetail.hero", "timeout": "20"], ["wait": "2"])
                try capture("collection-game")
                try step(["back": "1"], ["wait": "1"])
            }
            try step(["back": "1"], ["wait": "1"])
        }
        assertRunning("Library › Collection")

        // Search and its best match.
        try tab("Search")
        try capture("search")
        try step(["search": config["query"] ?? "a"], ["wait": "2"])
        try capture("search-results")
        if runner.button("search.bestMatch").waitForExistence(timeout: 5) {
            try step(["tap": "search.bestMatch"], ["waitFor": "gameDetail.hero", "timeout": "20"], ["wait": "2"])
            try capture("search-game")
            try step(["back": "1"], ["wait": "1"])
        }

        // Stats.
        try tab("Stats")
        try capture("stats")
        try step(["swipe": "up"], ["wait": "1"])
        try capture("stats-scrolled")

        // Settings and each sub-page.
        try tab("Settings")
        try capture("settings")
        for page in ["settings.profiles", "settings.player", "settings.cores", "settings.backup"] {
            try step(["scrollTo": page, "max": "6"], ["tap": page], ["wait": "2"])
            try capture(page.replacingOccurrences(of: ".", with: "-"))
            try step(["back": "1"], ["wait": "1"])
        }
        try tab("Play")
        assertRunning("the end of the pass")
    }

    // MARK: Transitions

    private var measureOptions: XCTMeasureOptions {
        let options = XCTMeasureOptions()
        options.iterationCount = Int(config["iterations"] ?? "") ?? 5
        return options
    }

    /// Library › Platform and back.
    func testPlatformPushTransition() throws {
        try tab("Library")
        let platform = try XCTUnwrap(exists("library.platform."), "No platform in the Library")
        measure(metrics: [XCTOSSignpostMetric.navigationTransitionMetric], options: measureOptions) {
            platform.tap()
            XCTAssertTrue(app.descendants(matching: .any)["platform.filter"].waitForExistence(timeout: 20))
            app.navigationBars.buttons.element(boundBy: 0).tap()
            XCTAssertTrue(platform.waitForExistence(timeout: 10))
        }
        assertRunning("Platform pushes")
    }

    /// Platform › Game and back.
    func testGamePushTransition() throws {
        try tab("Library")
        try XCTUnwrap(exists("library.platform."), "No platform in the Library").tap()
        XCTAssertTrue(app.descendants(matching: .any)["platform.filter"].waitForExistence(timeout: 20))
        let game = try XCTUnwrap(exists("platform.game.", swipes: 3), "No game on the platform")
        measure(metrics: [XCTOSSignpostMetric.navigationTransitionMetric], options: measureOptions) {
            game.tap()
            XCTAssertTrue(app.descendants(matching: .any)["gameDetail.hero"].waitForExistence(timeout: 20))
            app.navigationBars.buttons.element(boundBy: 0).tap()
            XCTAssertTrue(game.waitForExistence(timeout: 10))
        }
        assertRunning("Game pushes")
    }

    /// Settings › Cores and BIOS and back.
    func testSettingsPushTransition() throws {
        try tab("Settings")
        try step(["scrollTo": "settings.cores", "max": "6"])
        let cores = runner.button("settings.cores")
        measure(metrics: [XCTOSSignpostMetric.navigationTransitionMetric], options: measureOptions) {
            cores.tap()
            XCTAssertTrue(app.navigationBars.buttons.element(boundBy: 0).waitForExistence(timeout: 10))
            app.navigationBars.buttons.element(boundBy: 0).tap()
            XCTAssertTrue(cores.waitForExistence(timeout: 10))
        }
        assertRunning("Settings pushes")
    }
}
