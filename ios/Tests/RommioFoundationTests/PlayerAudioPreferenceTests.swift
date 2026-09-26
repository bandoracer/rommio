import GRDB
import XCTest
@testable import RommioFoundation

/// The pause menu's sound toggle: a device-local preference stored with the controls
/// preferences, sound on by default, and added to existing databases by the
/// `v10_player_audio_muted` migration without touching the other preferences.
@MainActor
final class PlayerAudioPreferenceTests: XCTestCase {
    private var root: URL!

    override func setUp() {
        root = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent(UUID().uuidString, isDirectory: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: root)
    }

    func testSoundIsOnByDefaultAndMutePersists() async throws {
        let database = try AppDatabase(rootDirectory: root.appending(path: "Database"))
        let repository = PlayerControlsRepository(database: database, controllerMonitor: AudioControllerMonitor())
        let defaults = try await repository.preferences()
        XCTAssertFalse(defaults.audioMuted)
        try await repository.setAudioMuted(true)
        try await repository.setHapticsEnabled(false)
        let saved = try await repository.preferences()
        XCTAssertTrue(saved.audioMuted)
        XCTAssertFalse(saved.hapticsEnabled, "Other preferences keep their own values")
        let controls = try await repository.controls(platformSlug: "nes")
        XCTAssertTrue(controls.preferences.audioMuted)
        try await repository.setAudioMuted(false)
        let restored = try await repository.preferences()
        XCTAssertFalse(restored.audioMuted)
        XCTAssertFalse(restored.hapticsEnabled)
    }

    func testMigrationFromVersion9AddsSoundOnAndKeepsTheOtherPreferences() async throws {
        let path = root.appending(path: "Database")
        try FileManager.default.createDirectory(at: path, withIntermediateDirectories: true)
        let queue = try DatabaseQueue(path: path.appending(path: "rommio.sqlite").path)
        try AppDatabase.migrator.migrate(queue, upTo: "v9_player_game_controls")
        try await queue.write { db in
            try db.execute(sql: "UPDATE player_control_preferences SET haptics_enabled = 0, console_colors_enabled = 0 WHERE id = 1")
        }
        let before = try await queue.read { try $0.columns(in: "player_control_preferences").map(\.name) }
        XCTAssertFalse(before.contains("audio_muted"))
        try queue.close()

        let database = try AppDatabase(rootDirectory: path)
        let columns = try database.read { try $0.columns(in: "player_control_preferences").map(\.name) }
        XCTAssertTrue(columns.contains("audio_muted"))
        let preferences = try await PlayerControlsRepository(database: database, controllerMonitor: AudioControllerMonitor()).preferences()
        XCTAssertFalse(preferences.audioMuted, "Existing installs keep their sound on")
        XCTAssertFalse(preferences.hapticsEnabled, "Kept")
        XCTAssertFalse(preferences.consoleColorsEnabled, "Kept")
    }

    func testPreferencesEncodedBeforeTheSoundToggleDecodeWithSoundOn() throws {
        let old = #"{"touchControlsEnabled":true,"autoHideTouchOnController":true,"rumbleToDeviceEnabled":true,"oledBlackModeEnabled":false,"consoleColorsEnabled":true,"hapticsEnabled":false}"#
        let decoded = try JSONDecoder().decode(PlayerControlsPreferences.self, from: Data(old.utf8))
        XCTAssertFalse(decoded.audioMuted)
        XCTAssertFalse(decoded.hapticsEnabled)
        let roundTrip = try JSONDecoder().decode(PlayerControlsPreferences.self, from: JSONEncoder().encode(PlayerControlsPreferences(audioMuted: true)))
        XCTAssertTrue(roundTrip.audioMuted)
    }
}

private struct AudioControllerMonitor: ControllerMonitoring, @unchecked Sendable {
    func currentControllers() -> [ConnectedController] { [] }
    func updates() -> AsyncStream<[ConnectedController]> {
        AsyncStream { continuation in
            continuation.yield([])
            continuation.finish()
        }
    }
}
