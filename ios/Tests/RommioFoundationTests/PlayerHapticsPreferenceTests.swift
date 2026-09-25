import GRDB
import XCTest
@testable import RommioFoundation

/// Touch-control haptics (docs/player-controls.md): a device-local preference stored
/// with the controls preferences, on by default, and added to existing databases by
/// the `v8_player_haptics` migration without touching the other preferences.
@MainActor
final class PlayerHapticsPreferenceTests: XCTestCase {
    private var root: URL!

    override func setUp() {
        root = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent(UUID().uuidString, isDirectory: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: root)
    }

    func testHapticsAreOnByDefaultAndPersist() async throws {
        let database = try AppDatabase(rootDirectory: root.appending(path: "Database"))
        let repository = PlayerControlsRepository(database: database, controllerMonitor: HapticsControllerMonitor())
        let defaults = try await repository.preferences()
        XCTAssertTrue(defaults.hapticsEnabled)
        try await repository.setHapticsEnabled(false)
        try await repository.setConsoleColorsEnabled(false)
        let saved = try await repository.preferences()
        XCTAssertFalse(saved.hapticsEnabled)
        XCTAssertFalse(saved.consoleColorsEnabled)
        let controls = try await repository.controls(platformSlug: "nes")
        XCTAssertFalse(controls.preferences.hapticsEnabled)
        try await repository.setHapticsEnabled(true)
        let restored = try await repository.preferences()
        XCTAssertTrue(restored.hapticsEnabled)
    }

    func testMigrationFromVersion7AddsHapticsOnAndKeepsTheOtherPreferences() async throws {
        let path = root.appending(path: "Database")
        try FileManager.default.createDirectory(at: path, withIntermediateDirectories: true)
        let queue = try DatabaseQueue(path: path.appending(path: "rommio.sqlite").path)
        try AppDatabase.migrator.migrate(queue, upTo: "v7_play_data")
        try await queue.write { db in
            try db.execute(sql: "UPDATE player_control_preferences SET oled_black_mode_enabled = 1, console_colors_enabled = 0 WHERE id = 1")
        }
        let before = try await queue.read { try $0.columns(in: "player_control_preferences").map(\.name) }
        XCTAssertFalse(before.contains("haptics_enabled"))
        try queue.close()

        let database = try AppDatabase(rootDirectory: path)
        let columns = try database.read { try $0.columns(in: "player_control_preferences").map(\.name) }
        XCTAssertTrue(columns.contains("haptics_enabled"))
        let preferences = try await PlayerControlsRepository(database: database, controllerMonitor: HapticsControllerMonitor()).preferences()
        XCTAssertTrue(preferences.hapticsEnabled, "Existing installs get haptics on")
        XCTAssertTrue(preferences.oledBlackModeEnabled, "Kept")
        XCTAssertFalse(preferences.consoleColorsEnabled, "Kept")
    }

    func testPreferencesEncodedBeforeHapticsDecodeWithHapticsOn() throws {
        let old = #"{"touchControlsEnabled":true,"autoHideTouchOnController":false,"rumbleToDeviceEnabled":true,"oledBlackModeEnabled":false,"consoleColorsEnabled":true}"#
        let decoded = try JSONDecoder().decode(PlayerControlsPreferences.self, from: Data(old.utf8))
        XCTAssertTrue(decoded.hapticsEnabled)
        XCTAssertFalse(decoded.autoHideTouchOnController)
        let roundTrip = try JSONDecoder().decode(PlayerControlsPreferences.self, from: JSONEncoder().encode(PlayerControlsPreferences(hapticsEnabled: false)))
        XCTAssertFalse(roundTrip.hapticsEnabled)
    }
}

private struct HapticsControllerMonitor: ControllerMonitoring, @unchecked Sendable {
    func currentControllers() -> [ConnectedController] { [] }
    func updates() -> AsyncStream<[ConnectedController]> {
        AsyncStream { continuation in
            continuation.yield([])
            continuation.finish()
        }
    }
}
