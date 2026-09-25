import GRDB
import XCTest
@testable import RommioFoundation

/// Per-game touch-control settings (docs/player-controls.md, "Analog sticks and
/// per-console layouts"): PlayStation's Analog controls are off until turned on for a
/// game, the Stick tuning is the console profile until changed, both are kept per game,
/// and they arrive on existing installs through the `v9_player_game_controls` migration
/// without touching the other preferences.
@MainActor
final class PlayerGameControlsTests: XCTestCase {
    private var root: URL!

    override func setUp() {
        root = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent(UUID().uuidString, isDirectory: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: root)
    }

    func testGameControlsDefaultToTheConsoleAndAreKeptPerGame() async throws {
        let database = try AppDatabase(rootDirectory: root.appending(path: "Database"))
        let repository = PlayerControlsRepository(database: database, controllerMonitor: GameControlsControllerMonitor())
        let initial = try await repository.gameControls(romID: 42)
        XCTAssertEqual(initial, PlayerGameControls(analogControls: false, stick: .standard))
        let tuned = PlayerGameControls(analogControls: true, stick: PlayerStickTuning(sensitivity: .high, deadZone: .small, centre: .fixed))
        try await repository.saveGameControls(tuned, romID: 42)
        let saved = try await repository.gameControls(romID: 42)
        let other = try await repository.gameControls(romID: 43)
        XCTAssertEqual(saved, tuned)
        XCTAssertEqual(other, PlayerGameControls(), "Per game")
        // Reset: back to the console's profile, Analog controls kept.
        try await repository.saveGameControls(PlayerGameControls(analogControls: true, stick: .standard), romID: 42)
        let reset = try await repository.gameControls(romID: 42)
        XCTAssertEqual(reset.stick, .standard)
        XCTAssertTrue(reset.analogControls)
        // The device-wide preferences are untouched.
        let preferences = try await repository.preferences()
        XCTAssertTrue(preferences.hapticsEnabled)
    }

    func testMigrationFromVersion8AddsTheTableAndKeepsThePreferences() async throws {
        let path = root.appending(path: "Database")
        try FileManager.default.createDirectory(at: path, withIntermediateDirectories: true)
        let queue = try DatabaseQueue(path: path.appending(path: "rommio.sqlite").path)
        try AppDatabase.migrator.migrate(queue, upTo: "v8_player_haptics")
        try await queue.write { db in
            try db.execute(sql: "UPDATE player_control_preferences SET haptics_enabled = 0 WHERE id = 1")
        }
        let before = try await queue.read { try $0.tableExists("player_game_controls") }
        XCTAssertFalse(before)
        try queue.close()

        let database = try AppDatabase(rootDirectory: path)
        let after = try database.read { try $0.tableExists("player_game_controls") }
        XCTAssertTrue(after)
        let repository = PlayerControlsRepository(database: database, controllerMonitor: GameControlsControllerMonitor())
        let game = try await repository.gameControls(romID: 7)
        XCTAssertEqual(game, PlayerGameControls())
        let columns = try database.read { try $0.columns(in: "player_game_controls").map(\.name) }
        XCTAssertEqual(Set(columns), ["rom_id", "analog_controls", "stick_sensitivity", "stick_dead_zone", "stick_centre", "updated_at_epoch_ms"])
        let preferences = try await repository.preferences()
        XCTAssertFalse(preferences.hapticsEnabled, "Kept")
    }
}

private struct GameControlsControllerMonitor: ControllerMonitoring, @unchecked Sendable {
    func currentControllers() -> [ConnectedController] { [] }
    func updates() -> AsyncStream<[ConnectedController]> {
        AsyncStream { continuation in
            continuation.yield([])
            continuation.finish()
        }
    }
}
