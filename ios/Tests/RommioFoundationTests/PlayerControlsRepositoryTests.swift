import XCTest
@testable import RommioFoundation

@MainActor
final class PlayerControlsRepositoryTests: XCTestCase {
    func testResolverMatchesAndroidParityForTouchAndControllerFamilies() throws {
        let resolver = PlatformControlProfileResolver()

        let nes = resolver.resolve(platformSlug: "nes")
        XCTAssertEqual(nes.familyID, "nes")
        XCTAssertEqual(nes.supportTier, .touchSupported)
        XCTAssertEqual(nes.playerOrientationPolicy, .auto)
        XCTAssertEqual(nes.defaultPresetID, "standard")

        let nds = resolver.resolve(platformSlug: "nds")
        XCTAssertEqual(nds.familyID, "nds")
        XCTAssertEqual(nds.supportTier, .touchSupported)
        XCTAssertEqual(nds.playerOrientationPolicy, .portraitOnly)
        XCTAssertEqual(nds.defaultPresetID, "portrait-handheld")

        let psp = resolver.resolve(platformSlug: "psp")
        XCTAssertEqual(psp.familyID, "psp")
        XCTAssertEqual(psp.supportTier, .controllerSupported)
        XCTAssertEqual(psp.touchSupportMode, .controllerFirst)
    }

    func testRepositoryUsesAndroidDefaultPreferencesAndPersistsTuning() async throws {
        let root = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        let database = try AppDatabase(rootDirectory: root.appending(path: "Database"))
        let monitor = TestControllerMonitor()
        let repository = PlayerControlsRepository(database: database, controllerMonitor: monitor)

        let defaults = try await repository.preferences()
        XCTAssertEqual(defaults, PlayerControlsPreferences())

        var controls = try await repository.controls(platformSlug: "snes")
        XCTAssertEqual(controls.inputMode, .touch)
        XCTAssertTrue(controls.showTouchControls)
        XCTAssertEqual(controls.hardwareBinding.deadzone, 0.2, accuracy: 0.001)

        var touchLayout = try XCTUnwrap(controls.touchLayout)
        touchLayout.opacity = 0.61
        touchLayout.globalScale = 1.18
        touchLayout.leftHanded = true
        try await repository.saveTouchLayout(touchLayout)

        try await repository.saveHardwareBinding(
            HardwareBindingProfile(
                platformFamilyID: controls.platformProfile.familyID,
                controllerTypeID: 7,
                deadzone: 0.33
            )
        )
        try await repository.setAutoHideTouchOnController(false)
        monitor.controllers = [ConnectedController(deviceID: 1, name: "Backbone One")]

        controls = try await repository.controls(platformSlug: "snes")
        let persistedLayout = try XCTUnwrap(controls.touchLayout)
        XCTAssertEqual(controls.inputMode, .hybrid)
        XCTAssertTrue(controls.showTouchControls)
        XCTAssertEqual(controls.hardwareBinding.controllerTypeID, 7)
        XCTAssertEqual(controls.hardwareBinding.deadzone, 0.33, accuracy: 0.001)
        XCTAssertEqual(persistedLayout.opacity, 0.61, accuracy: 0.001)
        XCTAssertEqual(persistedLayout.globalScale, 1.18, accuracy: 0.001)
        XCTAssertEqual(persistedLayout.leftHanded, true)
    }

    func testActiveInputModeMatchesAndroidStates() throws {
        let root = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        let database = try AppDatabase(rootDirectory: root.appending(path: "Database"))
        let monitor = TestControllerMonitor()
        let repository = PlayerControlsRepository(database: database, controllerMonitor: monitor)
        let resolver = PlatformControlProfileResolver()
        let preferences = PlayerControlsPreferences()

        XCTAssertEqual(
            repository.resolveInputMode(
                profile: resolver.resolve(platformSlug: "snes"),
                preferences: preferences,
                controllerConnected: false
            ),
            .touch
        )

        XCTAssertEqual(
            repository.resolveInputMode(
                profile: resolver.resolve(platformSlug: "snes"),
                preferences: preferences,
                controllerConnected: true
            ),
            .controller
        )

        XCTAssertEqual(
            repository.resolveInputMode(
                profile: resolver.resolve(platformSlug: "snes"),
                preferences: PlayerControlsPreferences(autoHideTouchOnController: false),
                controllerConnected: true
            ),
            .hybrid
        )

        XCTAssertEqual(
            repository.resolveInputMode(
                profile: resolver.resolve(platformSlug: "psp"),
                preferences: preferences,
                controllerConnected: true
            ),
            .controller
        )

        XCTAssertEqual(
            repository.resolveInputMode(
                profile: resolver.resolve(platformSlug: "psp"),
                preferences: preferences,
                controllerConnected: false
            ),
            .controllerRequired
        )
    }
}

private final class TestControllerMonitor: ControllerMonitoring, @unchecked Sendable {
    var controllers: [ConnectedController] = []

    func currentControllers() -> [ConnectedController] {
        controllers
    }

    func updates() -> AsyncStream<[ConnectedController]> {
        AsyncStream { continuation in
            continuation.yield(controllers)
            continuation.finish()
        }
    }
}
