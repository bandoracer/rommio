import Foundation
import GRDB

public struct PlayerControlsRepository: Sendable {
    private let database: AppDatabase
    private let resolver: any ControlProfileResolving
    public let controllerMonitor: any ControllerMonitoring

    public init(
        database: AppDatabase,
        resolver: any ControlProfileResolving = PlatformControlProfileResolver(),
        controllerMonitor: any ControllerMonitoring
    ) {
        self.database = database
        self.resolver = resolver
        self.controllerMonitor = controllerMonitor
    }

    public func resolveProfile(platformSlug: String) -> PlatformControlProfile {
        resolver.resolve(platformSlug: platformSlug)
    }

    public func supportedProfiles() -> [PlatformControlProfile] {
        resolver.supportedProfiles()
    }

    public func preferences() async throws -> PlayerControlsPreferences {
        try database.read { db in
            guard let row = try Row.fetchOne(db, sql: """
                SELECT touch_controls_enabled,
                       auto_hide_touch_on_controller,
                       rumble_to_device_enabled,
                       oled_black_mode_enabled,
                       console_colors_enabled,
                       haptics_enabled,
                       audio_muted
                FROM player_control_preferences
                WHERE id = 1
                """)
            else {
                return PlayerControlsPreferences()
            }
            return PlayerControlsPreferences(
                touchControlsEnabled: row["touch_controls_enabled"],
                autoHideTouchOnController: row["auto_hide_touch_on_controller"],
                rumbleToDeviceEnabled: row["rumble_to_device_enabled"],
                oledBlackModeEnabled: row["oled_black_mode_enabled"],
                consoleColorsEnabled: row["console_colors_enabled"],
                hapticsEnabled: row["haptics_enabled"],
                audioMuted: row["audio_muted"]
            )
        }
    }

    public func controls(platformSlug: String) async throws -> PlayerControlsState {
        let profile = resolver.resolve(platformSlug: platformSlug)
        let controllers = controllerMonitor.currentControllers()
        let preferences = try await preferences()
        let touchLayout = try readTouchLayout(for: profile)
        let hardwareBinding = try readHardwareBinding(for: profile)
        let inputMode = resolveInputMode(
            profile: profile,
            preferences: preferences,
            controllerConnected: !controllers.isEmpty
        )
        return PlayerControlsState(
            platformProfile: profile,
            touchLayout: touchLayout,
            hardwareBinding: hardwareBinding,
            preferences: preferences,
            connectedControllers: controllers,
            inputMode: inputMode,
            showTouchControls: shouldShowTouchControls(
                profile: profile,
                preferences: preferences,
                controllerConnected: !controllers.isEmpty
            )
        )
    }

    public func saveTouchLayout(_ profile: TouchLayoutProfile) async throws {
        let elementStatesData = try database.encoded(profile.elementStates)
        try database.write { db in
            try db.execute(
                sql: """
                INSERT INTO player_touch_layouts (
                    platform_family_id,
                    preset_id,
                    element_states_blob,
                    opacity,
                    global_scale,
                    left_handed,
                    updated_at_epoch_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(platform_family_id) DO UPDATE SET
                    preset_id = excluded.preset_id,
                    element_states_blob = excluded.element_states_blob,
                    opacity = excluded.opacity,
                    global_scale = excluded.global_scale,
                    left_handed = excluded.left_handed,
                    updated_at_epoch_ms = excluded.updated_at_epoch_ms
                """,
                arguments: [
                    profile.platformFamilyID,
                    profile.presetID,
                    elementStatesData,
                    profile.opacity,
                    profile.globalScale,
                    profile.leftHanded,
                    profile.updatedAtEpochMS,
                ]
            )
        }
    }

    public func resetTouchLayout(platformSlug: String, presetID: String? = nil) async throws {
        let profile = resolver.resolve(platformSlug: platformSlug)
        guard var layout = profile.defaultTouchLayout() else {
            try database.write { db in
                try db.execute(
                    sql: "DELETE FROM player_touch_layouts WHERE platform_family_id = ?",
                    arguments: [profile.familyID]
                )
            }
            return
        }
        if let presetID,
           let preset = profile.presets.first(where: { $0.presetID == presetID }) {
            layout = TouchLayoutProfile(
                platformFamilyID: profile.familyID,
                presetID: preset.presetID,
                elementStates: preset.elements.map { element in
                    TouchElementState(
                        elementID: element.id,
                        centerX: element.centerX,
                        centerY: element.centerY,
                        scale: element.baseScale
                    )
                }
            )
        }
        try await saveTouchLayout(layout)
    }

    public func saveHardwareBinding(_ profile: HardwareBindingProfile) async throws {
        try database.write { db in
            try db.execute(
                sql: """
                INSERT INTO player_hardware_bindings (
                    platform_family_id,
                    controller_type_id,
                    deadzone,
                    updated_at_epoch_ms
                ) VALUES (?, ?, ?, ?)
                ON CONFLICT(platform_family_id) DO UPDATE SET
                    controller_type_id = excluded.controller_type_id,
                    deadzone = excluded.deadzone,
                    updated_at_epoch_ms = excluded.updated_at_epoch_ms
                """,
                arguments: [
                    profile.platformFamilyID,
                    profile.controllerTypeID,
                    profile.deadzone,
                    profile.updatedAtEpochMS,
                ]
            )
        }
    }

    public func setTouchControlsEnabled(_ enabled: Bool) async throws {
        try await updatePreferences { $0.touchControlsEnabled = enabled }
    }

    public func setAutoHideTouchOnController(_ enabled: Bool) async throws {
        try await updatePreferences { $0.autoHideTouchOnController = enabled }
    }

    public func setRumbleToDeviceEnabled(_ enabled: Bool) async throws {
        try await updatePreferences { $0.rumbleToDeviceEnabled = enabled }
    }

    public func setOLEDBlackModeEnabled(_ enabled: Bool) async throws {
        try await updatePreferences { $0.oledBlackModeEnabled = enabled }
    }

    public func setConsoleColorsEnabled(_ enabled: Bool) async throws {
        try await updatePreferences { $0.consoleColorsEnabled = enabled }
    }

    public func setHapticsEnabled(_ enabled: Bool) async throws {
        try await updatePreferences { $0.hapticsEnabled = enabled }
    }

    public func setAudioMuted(_ muted: Bool) async throws {
        try await updatePreferences { $0.audioMuted = muted }
    }

    /// One game's touch-control settings: PlayStation's Analog controls (off until turned
    /// on for that game) and the touch-stick tuning (the console profile until changed).
    /// Device-local.
    public func gameControls(romID: Int) async throws -> PlayerGameControls {
        try database.read { db in
            guard let row = try Row.fetchOne(db, sql: """
                SELECT analog_controls, stick_sensitivity, stick_dead_zone, stick_centre
                FROM player_game_controls WHERE rom_id = ?
                """, arguments: [romID])
            else {
                return PlayerGameControls()
            }
            let sensitivity: String = row["stick_sensitivity"]
            let deadZone: String = row["stick_dead_zone"]
            let centre: String = row["stick_centre"]
            return PlayerGameControls(
                analogControls: row["analog_controls"],
                stick: PlayerStickTuning(
                    sensitivity: PlayerStickTuning.Sensitivity(rawValue: sensitivity) ?? .normal,
                    deadZone: PlayerStickTuning.DeadZone(rawValue: deadZone) ?? .normal,
                    centre: PlayerStickTuning.Centre(rawValue: centre) ?? .floating))
        }
    }

    public func saveGameControls(_ value: PlayerGameControls, romID: Int) async throws {
        try database.write { db in
            try db.execute(
                sql: """
                INSERT INTO player_game_controls (rom_id, analog_controls, stick_sensitivity, stick_dead_zone, stick_centre, updated_at_epoch_ms)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(rom_id) DO UPDATE SET
                    analog_controls = excluded.analog_controls,
                    stick_sensitivity = excluded.stick_sensitivity,
                    stick_dead_zone = excluded.stick_dead_zone,
                    stick_centre = excluded.stick_centre,
                    updated_at_epoch_ms = excluded.updated_at_epoch_ms
                """,
                arguments: [romID, value.analogControls, value.stick.sensitivity.rawValue, value.stick.deadZone.rawValue,
                            value.stick.centre.rawValue, Int64(Date().timeIntervalSince1970 * 1000)]
            )
        }
    }

    public func resolveInputMode(
        profile: PlatformControlProfile,
        preferences: PlayerControlsPreferences,
        controllerConnected: Bool
    ) -> ActiveInputMode {
        switch (profile.touchSupportMode, controllerConnected, preferences.autoHideTouchOnController) {
        case (.controllerFirst, true, _):
            .controller
        case (.controllerFirst, false, _):
            .controllerRequired
        case (.full, true, true):
            .controller
        case (.full, true, false):
            .hybrid
        case (.full, false, _):
            .touch
        }
    }

    public func shouldShowTouchControls(
        profile: PlatformControlProfile,
        preferences: PlayerControlsPreferences,
        controllerConnected: Bool
    ) -> Bool {
        if profile.touchSupportMode == .controllerFirst {
            return false
        }
        if !preferences.touchControlsEnabled {
            return false
        }
        if controllerConnected && preferences.autoHideTouchOnController {
            return false
        }
        return true
    }

    private func readTouchLayout(for profile: PlatformControlProfile) throws -> TouchLayoutProfile? {
        try database.read { db in
            guard profile.touchSupportMode != .controllerFirst else {
                return nil
            }
            guard let row = try Row.fetchOne(
                db,
                sql: """
                SELECT preset_id,
                       element_states_blob,
                       opacity,
                       global_scale,
                       left_handed,
                       updated_at_epoch_ms
                FROM player_touch_layouts
                WHERE platform_family_id = ?
                """,
                arguments: [profile.familyID]
            ) else {
                return profile.defaultTouchLayout()
            }

            let defaultLayout = profile.defaultTouchLayout()
            let blob: Data = row["element_states_blob"]
            let elementStates = (try? database.decoded([TouchElementState].self, from: blob)) ?? defaultLayout?.elementStates ?? []
            return TouchLayoutProfile(
                platformFamilyID: profile.familyID,
                presetID: row["preset_id"],
                elementStates: elementStates,
                opacity: row["opacity"],
                globalScale: row["global_scale"],
                leftHanded: row["left_handed"],
                updatedAtEpochMS: row["updated_at_epoch_ms"]
            )
        }
    }

    private func readHardwareBinding(for profile: PlatformControlProfile) throws -> HardwareBindingProfile {
        try database.read { db in
            guard let row = try Row.fetchOne(
                db,
                sql: """
                SELECT controller_type_id,
                       deadzone,
                       updated_at_epoch_ms
                FROM player_hardware_bindings
                WHERE platform_family_id = ?
                """,
                arguments: [profile.familyID]
            ) else {
                return profile.defaultHardwareBinding()
            }
            return HardwareBindingProfile(
                platformFamilyID: profile.familyID,
                controllerTypeID: row["controller_type_id"],
                deadzone: row["deadzone"],
                updatedAtEpochMS: row["updated_at_epoch_ms"]
            )
        }
    }

    private func updatePreferences(_ mutate: (inout PlayerControlsPreferences) -> Void) async throws {
        var value = try await preferences()
        mutate(&value)
        try database.write { db in
            try db.execute(
                sql: """
                INSERT INTO player_control_preferences (
                    id,
                    touch_controls_enabled,
                    auto_hide_touch_on_controller,
                    rumble_to_device_enabled,
                    oled_black_mode_enabled,
                    console_colors_enabled,
                    haptics_enabled,
                    audio_muted,
                    updated_at_epoch_ms
                ) VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    touch_controls_enabled = excluded.touch_controls_enabled,
                    auto_hide_touch_on_controller = excluded.auto_hide_touch_on_controller,
                    rumble_to_device_enabled = excluded.rumble_to_device_enabled,
                    oled_black_mode_enabled = excluded.oled_black_mode_enabled,
                    console_colors_enabled = excluded.console_colors_enabled,
                    haptics_enabled = excluded.haptics_enabled,
                    audio_muted = excluded.audio_muted,
                    updated_at_epoch_ms = excluded.updated_at_epoch_ms
                """,
                arguments: [
                    value.touchControlsEnabled,
                    value.autoHideTouchOnController,
                    value.rumbleToDeviceEnabled,
                    value.oledBlackModeEnabled,
                    value.consoleColorsEnabled,
                    value.hapticsEnabled,
                    value.audioMuted,
                    Int64(Date().timeIntervalSince1970 * 1000),
                ]
            )
        }
    }
}
