import Foundation

public enum EmbeddedSupportTier: String, Codable, Sendable, CaseIterable {
    case touchSupported = "TOUCH"
    case controllerSupported = "CONTROLLER"
    case unsupported = "UNSUPPORTED"

    public var title: String {
        switch self {
        case .touchSupported:
            "Touch"
        case .controllerSupported:
            "Controller"
        case .unsupported:
            "Unsupported"
        }
    }
}

public enum TouchSupportMode: String, Codable, Sendable, CaseIterable {
    case full = "FULL"
    case controllerFirst = "CONTROLLER_FIRST"
}

public enum ActiveInputMode: String, Codable, Sendable, CaseIterable {
    case touch = "TOUCH"
    case hybrid = "HYBRID"
    case controller = "CONTROLLER"
    case controllerRequired = "CONTROLLER_REQUIRED"

    public var title: String {
        switch self {
        case .touch:
            "Touch"
        case .hybrid:
            "Hybrid"
        case .controller:
            "Controller"
        case .controllerRequired:
            "Controller required"
        }
    }
}

public enum PlayerOrientationPolicy: String, Codable, Sendable, CaseIterable {
    case auto = "AUTO"
    case portraitOnly = "PORTRAIT_ONLY"
    case landscapeOnly = "LANDSCAPE_ONLY"
}

public enum PlayerHotkeyAction: String, Codable, Sendable, CaseIterable, Hashable {
    case pauseMenu = "PAUSE_MENU"
    case quickSave = "QUICK_SAVE"
    case quickLoad = "QUICK_LOAD"
    case reset = "RESET"
}

public enum TouchElementLayoutKind: String, Codable, Sendable, CaseIterable {
    case dpadCross = "DPAD_CROSS"
    case faceDiagonal = "FACE_DIAGONAL"
    case faceDiamond = "FACE_DIAMOND"
    case buttonRow = "BUTTON_ROW"
    case buttonColumn = "BUTTON_COLUMN"
}

public enum ControlAction: Codable, Hashable, Sendable {
    case digital(actionID: String, keyCode: Int, label: String)
    case pointer(actionID: String = "pointer", label: String = "Touch")
}

public struct ConnectedController: Codable, Hashable, Sendable, Identifiable {
    public var deviceID: Int
    public var name: String

    public var id: Int { deviceID }

    public init(deviceID: Int, name: String) {
        self.deviceID = deviceID
        self.name = name
    }
}

public struct TouchButtonSpec: Codable, Hashable, Sendable, Identifiable {
    public var id: String
    public var label: String
    public var action: ControlAction

    public init(id: String, label: String, action: ControlAction) {
        self.id = id
        self.label = label
        self.action = action
    }
}

public struct TouchElementSpec: Codable, Hashable, Sendable, Identifiable {
    public var id: String
    public var label: String
    public var layoutKind: TouchElementLayoutKind
    public var buttons: [TouchButtonSpec]
    public var centerX: Double
    public var centerY: Double
    public var baseScale: Double

    public init(
        id: String,
        label: String,
        layoutKind: TouchElementLayoutKind,
        buttons: [TouchButtonSpec],
        centerX: Double,
        centerY: Double,
        baseScale: Double = 1
    ) {
        self.id = id
        self.label = label
        self.layoutKind = layoutKind
        self.buttons = buttons
        self.centerX = centerX
        self.centerY = centerY
        self.baseScale = baseScale
    }
}

public struct TouchLayoutPreset: Codable, Hashable, Sendable, Identifiable {
    public var presetID: String
    public var displayName: String
    public var elements: [TouchElementSpec]

    public var id: String { presetID }

    public init(presetID: String, displayName: String, elements: [TouchElementSpec]) {
        self.presetID = presetID
        self.displayName = displayName
        self.elements = elements
    }
}

public struct TouchElementState: Codable, Hashable, Sendable, Identifiable {
    public var elementID: String
    public var centerX: Double
    public var centerY: Double
    public var scale: Double

    public var id: String { elementID }

    public init(elementID: String, centerX: Double, centerY: Double, scale: Double = 1) {
        self.elementID = elementID
        self.centerX = centerX
        self.centerY = centerY
        self.scale = scale
    }
}

public struct TouchLayoutProfile: Codable, Hashable, Sendable {
    public var platformFamilyID: String
    public var presetID: String
    public var elementStates: [TouchElementState]
    public var opacity: Double
    public var globalScale: Double
    public var leftHanded: Bool
    public var updatedAtEpochMS: Int64

    public init(
        platformFamilyID: String,
        presetID: String,
        elementStates: [TouchElementState],
        opacity: Double = 0.72,
        globalScale: Double = 1,
        leftHanded: Bool = false,
        updatedAtEpochMS: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    ) {
        self.platformFamilyID = platformFamilyID
        self.presetID = presetID
        self.elementStates = elementStates
        self.opacity = opacity
        self.globalScale = globalScale
        self.leftHanded = leftHanded
        self.updatedAtEpochMS = updatedAtEpochMS
    }
}

public struct HardwareBindingProfile: Codable, Hashable, Sendable {
    public var platformFamilyID: String
    public var controllerTypeID: Int?
    public var deadzone: Double
    public var updatedAtEpochMS: Int64

    public init(
        platformFamilyID: String,
        controllerTypeID: Int? = nil,
        deadzone: Double = 0.2,
        updatedAtEpochMS: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    ) {
        self.platformFamilyID = platformFamilyID
        self.controllerTypeID = controllerTypeID
        self.deadzone = deadzone
        self.updatedAtEpochMS = updatedAtEpochMS
    }
}

public struct PlayerControlsPreferences: Codable, Hashable, Sendable {
    public var touchControlsEnabled: Bool
    public var autoHideTouchOnController: Bool
    public var rumbleToDeviceEnabled: Bool
    public var oledBlackModeEnabled: Bool
    public var consoleColorsEnabled: Bool

    public init(
        touchControlsEnabled: Bool = true,
        autoHideTouchOnController: Bool = true,
        rumbleToDeviceEnabled: Bool = true,
        oledBlackModeEnabled: Bool = false,
        consoleColorsEnabled: Bool = false
    ) {
        self.touchControlsEnabled = touchControlsEnabled
        self.autoHideTouchOnController = autoHideTouchOnController
        self.rumbleToDeviceEnabled = rumbleToDeviceEnabled
        self.oledBlackModeEnabled = oledBlackModeEnabled
        self.consoleColorsEnabled = consoleColorsEnabled
    }
}

public struct PlatformControlProfile: Codable, Hashable, Sendable {
    public var familyID: String
    public var displayName: String
    public var platformSlugs: Set<String>
    public var supportTier: EmbeddedSupportTier
    public var touchSupportMode: TouchSupportMode
    public var playerOrientationPolicy: PlayerOrientationPolicy
    public var preferredViewportAspectRatio: Double?
    public var defaultPresetID: String?
    public var presets: [TouchLayoutPreset]
    public var hotkeys: Set<PlayerHotkeyAction>
    public var controllerFallbackMessage: String?

    public init(
        familyID: String,
        displayName: String,
        platformSlugs: Set<String>,
        supportTier: EmbeddedSupportTier,
        touchSupportMode: TouchSupportMode,
        playerOrientationPolicy: PlayerOrientationPolicy = .auto,
        preferredViewportAspectRatio: Double? = nil,
        defaultPresetID: String? = nil,
        presets: [TouchLayoutPreset] = [],
        hotkeys: Set<PlayerHotkeyAction> = [.pauseMenu, .quickSave, .quickLoad, .reset],
        controllerFallbackMessage: String? = nil
    ) {
        self.familyID = familyID
        self.displayName = displayName
        self.platformSlugs = platformSlugs
        self.supportTier = supportTier
        self.touchSupportMode = touchSupportMode
        self.playerOrientationPolicy = playerOrientationPolicy
        self.preferredViewportAspectRatio = preferredViewportAspectRatio
        self.defaultPresetID = defaultPresetID
        self.presets = presets
        self.hotkeys = hotkeys
        self.controllerFallbackMessage = controllerFallbackMessage
    }
}

public struct PlayerControlsState: Codable, Hashable, Sendable {
    public var platformProfile: PlatformControlProfile
    public var touchLayout: TouchLayoutProfile?
    public var hardwareBinding: HardwareBindingProfile
    public var preferences: PlayerControlsPreferences
    public var connectedControllers: [ConnectedController]
    public var inputMode: ActiveInputMode
    public var showTouchControls: Bool

    public init(
        platformProfile: PlatformControlProfile,
        touchLayout: TouchLayoutProfile?,
        hardwareBinding: HardwareBindingProfile,
        preferences: PlayerControlsPreferences,
        connectedControllers: [ConnectedController],
        inputMode: ActiveInputMode,
        showTouchControls: Bool
    ) {
        self.platformProfile = platformProfile
        self.touchLayout = touchLayout
        self.hardwareBinding = hardwareBinding
        self.preferences = preferences
        self.connectedControllers = connectedControllers
        self.inputMode = inputMode
        self.showTouchControls = showTouchControls
    }
}

public protocol ControlProfileResolving: Sendable {
    func resolve(platformSlug: String) -> PlatformControlProfile
    func supportedProfiles() -> [PlatformControlProfile]
}

public extension PlatformControlProfile {
    func defaultTouchLayout() -> TouchLayoutProfile? {
        guard let defaultPresetID,
              let preset = presets.first(where: { $0.presetID == defaultPresetID }) else {
            return nil
        }
        return TouchLayoutProfile(
            platformFamilyID: familyID,
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

    func defaultHardwareBinding() -> HardwareBindingProfile {
        HardwareBindingProfile(platformFamilyID: familyID)
    }
}

enum LibretroJoypadID {
    static let b = 0
    static let y = 1
    static let select = 2
    static let start = 3
    static let up = 4
    static let down = 5
    static let left = 6
    static let right = 7
    static let a = 8
    static let x = 9
    static let l1 = 10
    static let r1 = 11
    static let l2 = 12
    static let r2 = 13
    static let l3 = 14
    static let r3 = 15
}

func digitalButton(_ id: String, _ label: String, _ keyCode: Int) -> TouchButtonSpec {
    TouchButtonSpec(
        id: id,
        label: label,
        action: .digital(actionID: id, keyCode: keyCode, label: label)
    )
}

func standardDpad(id: String = "dpad") -> TouchElementSpec {
    TouchElementSpec(
        id: id,
        label: "D-Pad",
        layoutKind: .dpadCross,
        buttons: [
            digitalButton("up", "Up", LibretroJoypadID.up),
            digitalButton("left", "Left", LibretroJoypadID.left),
            digitalButton("right", "Right", LibretroJoypadID.right),
            digitalButton("down", "Down", LibretroJoypadID.down),
        ],
        centerX: 0.18,
        centerY: 0.73
    )
}

func standardStartSelect(
    id: String = "system",
    selectLabel: String = "Select",
    startLabel: String = "Start"
) -> TouchElementSpec {
    TouchElementSpec(
        id: id,
        label: "System",
        layoutKind: .buttonRow,
        buttons: [
            digitalButton("select", selectLabel, LibretroJoypadID.select),
            digitalButton("start", startLabel, LibretroJoypadID.start),
        ],
        centerX: 0.50,
        centerY: 0.83,
        baseScale: 0.9
    )
}

func standardShoulders(
    id: String = "shoulders",
    labels: (String, String) = ("L", "R"),
    keyCodes: (Int, Int) = (LibretroJoypadID.l1, LibretroJoypadID.r1)
) -> TouchElementSpec {
    TouchElementSpec(
        id: id,
        label: "Shoulders",
        layoutKind: .buttonRow,
        buttons: [
            digitalButton("left_shoulder", labels.0, keyCodes.0),
            digitalButton("right_shoulder", labels.1, keyCodes.1),
        ],
        centerX: 0.50,
        centerY: 0.15,
        baseScale: 0.92
    )
}

func faceTwo(
    id: String = "face",
    primaryLabel: String,
    secondaryLabel: String,
    primaryKey: Int,
    secondaryKey: Int
) -> TouchElementSpec {
    TouchElementSpec(
        id: id,
        label: "Face",
        layoutKind: .faceDiagonal,
        buttons: [
            digitalButton("secondary", secondaryLabel, secondaryKey),
            digitalButton("primary", primaryLabel, primaryKey),
        ],
        centerX: 0.82,
        centerY: 0.72
    )
}

func faceFour(
    id: String = "face",
    left: (String, Int),
    bottom: (String, Int),
    right: (String, Int),
    top: (String, Int)
) -> TouchElementSpec {
    TouchElementSpec(
        id: id,
        label: "Face",
        layoutKind: .faceDiamond,
        buttons: [
            digitalButton("left", left.0, left.1),
            digitalButton("bottom", bottom.0, bottom.1),
            digitalButton("right", right.0, right.1),
            digitalButton("top", top.0, top.1),
        ],
        centerX: 0.82,
        centerY: 0.72
    )
}
