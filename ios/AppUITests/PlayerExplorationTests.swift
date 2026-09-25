import XCTest

/// Opt-in exploratory actions against the installed QA app; never runs against a
/// user's device automatically. The actions come from the runner bundle
/// (`scripts/ios/generate_player_ui_runner.rb --actions <json>`, simulator or
/// device) or, on a simulator, from the host's `/tmp/rommio-player-ui-actions.json`.
/// See `UIActionRunner` for the actions.
@MainActor
final class PlayerExplorationTests: XCTestCase {
    func testPlayerExploration() throws {
        guard let actions = try UIActionRunner.embeddedActions(for: Self.self) else { throw XCTSkip("No exploratory actions supplied") }
        let runner = UIActionRunner(test: self)
        // A pass that starts with `launch` relaunches the app with its arguments; otherwise it
        // continues the app as it is (or starts it with this run's appearance, if any).
        if actions.first?["launch"] == nil {
            let arguments = UIActionRunner.appearanceArguments(UIActionRunner.config(for: Self.self))
            if arguments.isEmpty { runner.app.activate() } else { runner.launch(arguments: arguments) }
        }
        try runner.run(actions)
    }
}
