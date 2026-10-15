import Foundation

/// libretro's joypad buttons (`RETRO_DEVICE_ID_JOYPAD_*`): what the core reads, laid out like
/// a SNES pad (B bottom, A right, Y left, X top).
public enum RetroJoypad {
    public static let b = 0, y = 1, select = 2, start = 3, up = 4, down = 5, left = 6, right = 7
    public static let a = 8, x = 9, l = 10, r = 11, l2 = 12, r2 = 13, l3 = 14, r3 = 15

    /// A generic name for a joypad button (a console's own names come from its controls).
    public static func name(_ id: Int) -> String {
        switch id {
        case b: "B"
        case y: "Y"
        case select: "Select"
        case start: "Start"
        case up: "Up"
        case down: "Down"
        case left: "Left"
        case right: "Right"
        case a: "A"
        case x: "X"
        case l: "L"
        case r: "R"
        case l2: "L2"
        case r2: "R2"
        case l3: "L3"
        case r3: "R3"
        default: "Button \(id)"
        }
    }
}

/// A control on a game controller, by position (Apple's names: A is the bottom face button
/// on every controller, whatever it's printed with).
public enum ControllerElement: String, Codable, CaseIterable, Sendable {
    case buttonA, buttonB, buttonX, buttonY
    case leftShoulder, rightShoulder, leftTrigger, rightTrigger
    case leftThumbstickButton, rightThumbstickButton
    case buttonMenu, buttonOptions
    case dpadUp, dpadDown, dpadLeft, dpadRight
    /// The system's button (a logo): opens Rommio's pause menu, never the game's.
    case buttonHome

    /// Can be given to a console button (Home always opens the pause menu).
    public var isRemappable: Bool { self != .buttonHome }

    /// "Bottom button", "Left shoulder"… (by position, since labels differ by brand).
    public var name: String {
        switch self {
        case .buttonA: "Bottom button"
        case .buttonB: "Right button"
        case .buttonX: "Left button"
        case .buttonY: "Top button"
        case .leftShoulder: "Left shoulder"
        case .rightShoulder: "Right shoulder"
        case .leftTrigger: "Left trigger"
        case .rightTrigger: "Right trigger"
        case .leftThumbstickButton: "Left stick click"
        case .rightThumbstickButton: "Right stick click"
        case .buttonMenu: "Menu"
        case .buttonOptions: "Options"
        case .dpadUp: "D-pad up"
        case .dpadDown: "D-pad down"
        case .dpadLeft: "D-pad left"
        case .dpadRight: "D-pad right"
        case .buttonHome: "Home"
        }
    }
}

/// Keyboard keys by HID usage (the values of GameController's `GCKeyCode`).
public enum KeyboardKey {
    public static let a = 0x04, s = 0x16, q = 0x14, w = 0x1A, x = 0x1B, z = 0x1D, l = 0x0F
    public static let returnOrEnter = 0x28, escape = 0x29, backspace = 0x2A, tab = 0x2B, space = 0x2C
    public static let rightArrow = 0x4F, leftArrow = 0x50, downArrow = 0x51, upArrow = 0x52
    public static let leftControl = 0xE0, leftShift = 0xE1, leftAlt = 0xE2, leftCommand = 0xE3
    public static let rightControl = 0xE4, rightShift = 0xE5, rightAlt = 0xE6, rightCommand = 0xE7

    /// Modifiers never reach a game: they make chords.
    public static func isModifier(_ code: Int) -> Bool { (leftControl...rightCommand).contains(code) }

    /// "Z", "Return", "↑"…
    public static func name(_ code: Int) -> String {
        switch code {
        case 0x04...0x1D: String(UnicodeScalar(UInt8(code - 0x04) + 65))
        case 0x1E...0x26: String(code - 0x1E + 1)
        case 0x27: "0"
        case returnOrEnter: "Return"
        case escape: "Escape"
        case backspace: "Delete"
        case tab: "Tab"
        case space: "Space"
        case 0x2D: "-"
        case 0x2E: "="
        case 0x2F: "["
        case 0x30: "]"
        case 0x31: "\\"
        case 0x33: ";"
        case 0x34: "'"
        case 0x35: "`"
        case 0x36: ","
        case 0x37: "."
        case 0x38: "/"
        case rightArrow: "→"
        case leftArrow: "←"
        case downArrow: "↓"
        case upArrow: "↑"
        case leftShift, rightShift: "Shift"
        default: "Key \(code)"
        }
    }
}

/// Which physical control presses which console button, for one console: a controller's
/// elements and a keyboard's keys. Every console starts from the same layout by position.
public struct ControllerBindings: Codable, Hashable, Sendable {
    /// Controller element → libretro joypad button.
    public var buttons: [ControllerElement: Int]
    /// Keyboard key (HID usage) → libretro joypad button.
    public var keys: [Int: Int]

    public init(buttons: [ControllerElement: Int] = Self.standardButtons, keys: [Int: Int] = Self.standardKeys) {
        self.buttons = buttons
        self.keys = keys
    }

    public static let standard = ControllerBindings()

    /// By position: the bottom face button is libretro's B, the right one A, and so on.
    public static let standardButtons: [ControllerElement: Int] = [
        .buttonA: RetroJoypad.b, .buttonB: RetroJoypad.a, .buttonX: RetroJoypad.y, .buttonY: RetroJoypad.x,
        .leftShoulder: RetroJoypad.l, .rightShoulder: RetroJoypad.r,
        .leftTrigger: RetroJoypad.l2, .rightTrigger: RetroJoypad.r2,
        .leftThumbstickButton: RetroJoypad.l3, .rightThumbstickButton: RetroJoypad.r3,
        .buttonOptions: RetroJoypad.select, .buttonMenu: RetroJoypad.start,
        .dpadUp: RetroJoypad.up, .dpadDown: RetroJoypad.down, .dpadLeft: RetroJoypad.left, .dpadRight: RetroJoypad.right,
    ]

    /// Arrows for the d-pad, Z X A S for the face buttons, Q W for the shoulders, Return
    /// and Shift for Start and Select (the layout emulators have long used).
    public static let standardKeys: [Int: Int] = [
        KeyboardKey.upArrow: RetroJoypad.up, KeyboardKey.downArrow: RetroJoypad.down,
        KeyboardKey.leftArrow: RetroJoypad.left, KeyboardKey.rightArrow: RetroJoypad.right,
        KeyboardKey.z: RetroJoypad.b, KeyboardKey.x: RetroJoypad.a, KeyboardKey.a: RetroJoypad.y, KeyboardKey.s: RetroJoypad.x,
        KeyboardKey.q: RetroJoypad.l, KeyboardKey.w: RetroJoypad.r,
        KeyboardKey.returnOrEnter: RetroJoypad.start, KeyboardKey.rightShift: RetroJoypad.select,
    ]

    public var isStandard: Bool { self == .standard }

    /// The controller elements that press `button`.
    public func elements(for button: Int) -> [ControllerElement] {
        ControllerElement.allCases.filter { buttons[$0] == button }
    }

    /// The keys that press `button`, in key order.
    public func keys(for button: Int) -> [Int] {
        keys.filter { $0.value == button }.map(\.key).sorted()
    }

    /// `element` now presses `button`. Whatever pressed `button` before takes over what
    /// `element` used to press (a swap), so no console button is left unreachable.
    public mutating func assign(_ element: ControllerElement, to button: Int) {
        guard element.isRemappable else { return }
        let previous = buttons[element]
        for (other, value) in buttons where value == button && other != element {
            buttons[other] = previous
        }
        buttons[element] = button
    }

    /// `key` now presses `button`, swapping like `assign(_:to:)`.
    public mutating func assign(key: Int, to button: Int) {
        guard !KeyboardKey.isModifier(key), key != KeyboardKey.escape else { return }
        let previous = keys[key]
        for (other, value) in keys where value == button && other != key {
            keys[other] = previous
        }
        keys[key] = button
    }

    // Stored as JSON with string keys (element names and key codes).
    private enum CodingKeys: String, CodingKey { case buttons, keys }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let rawButtons = try container.decodeIfPresent([String: Int].self, forKey: .buttons) ?? [:]
        let rawKeys = try container.decodeIfPresent([String: Int].self, forKey: .keys) ?? [:]
        buttons = Dictionary(uniqueKeysWithValues: rawButtons.compactMap { name, value in
            ControllerElement(rawValue: name).map { ($0, value) }
        })
        keys = Dictionary(uniqueKeysWithValues: rawKeys.compactMap { code, value in Int(code).map { ($0, value) } })
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(Dictionary(uniqueKeysWithValues: buttons.map { ($0.key.rawValue, $0.value) }), forKey: .buttons)
        try container.encode(Dictionary(uniqueKeysWithValues: keys.map { (String($0.key), $0.value) }), forKey: .keys)
    }
}

/// What a controller reports: the elements held and where its sticks are (x right, y up,
/// −1…1).
public struct ControllerSnapshot: Equatable, Sendable {
    public var pressed: Set<ControllerElement>
    public var leftStick: SIMD2<Double>
    public var rightStick: SIMD2<Double>

    public init(pressed: Set<ControllerElement> = [], leftStick: SIMD2<Double> = .zero, rightStick: SIMD2<Double> = .zero) {
        self.pressed = pressed
        self.leftStick = leftStick
        self.rightStick = rightStick
    }
}

/// What the router asks of the player.
public enum ControllerEvent: Equatable, Sendable {
    case digital(port: Int, button: Int, pressed: Bool)
    /// Stick 0 (left) or 1 (right); libretro's y points down.
    case motion(port: Int, stick: Int, x: Double, y: Double)
    case pauseMenu
    case quickSave
    case quickLoad
}

/// Routes controllers and a keyboard to the core's ports (revised 2026-10-03): each
/// controller takes the lowest free player when it connects and keeps it until it
/// disconnects; the keyboard plays as player 1. Every device keeps its own state, a port
/// presses a button while any of its devices holds it, and a device that leaves (or the app
/// going to the background) releases everything it held, so nothing sticks.
///
/// Hotkeys never reach the game: Home, or both stick clicks together, opens the pause menu;
/// on a keyboard Escape does, and ⌘S and ⌘L quick-save and quick-load.
public struct ControllerRouter: Sendable {
    public var bindings: ControllerBindings
    public var deadzone: Double
    public let maxPlayers: Int

    /// Each connected controller's player (0-based port).
    public private(set) var ports: [String: Int] = [:]
    private var held: [String: Set<Int>] = [:]
    private var sticks: [String: [Int: SIMD2<Double>]] = [:]
    private var lastSnapshot: [String: ControllerSnapshot] = [:]
    private var keysDown: Set<Int> = []
    /// What each port's held buttons were last reported as.
    private var portHeld: [Int: Set<Int>] = [:]
    private var portSticks: [Int: [Int: SIMD2<Double>]] = [:]

    public static let keyboardID = "keyboard"

    public init(bindings: ControllerBindings = .standard, deadzone: Double = 0.2, maxPlayers: Int = 4) {
        self.bindings = bindings
        self.deadzone = deadzone
        self.maxPlayers = maxPlayers
    }

    /// Connects a controller; returns its port (nil when every player is taken).
    @discardableResult
    public mutating func connect(_ id: String) -> Int? {
        if let port = ports[id] { return port }
        guard let port = (0..<maxPlayers).first(where: { !ports.values.contains($0) }) else { return nil }
        ports[id] = port
        return port
    }

    /// Disconnects a controller, releasing what it held.
    public mutating func disconnect(_ id: String) -> [ControllerEvent] {
        guard ports[id] != nil else { return [] }
        held[id] = []
        sticks[id] = [:]
        let events = resolve()
        ports[id] = nil
        held[id] = nil
        sticks[id] = nil
        lastSnapshot[id] = nil
        return events
    }

    /// A controller's new state.
    public mutating func update(_ id: String, _ snapshot: ControllerSnapshot) -> [ControllerEvent] {
        guard ports[id] != nil else { return [] }
        let before = lastSnapshot[id] ?? ControllerSnapshot()
        lastSnapshot[id] = snapshot
        var events: [ControllerEvent] = []
        let pressedNow = snapshot.pressed.subtracting(before.pressed)
        let bothSticks: Set<ControllerElement> = [.leftThumbstickButton, .rightThumbstickButton]
        if pressedNow.contains(.buttonHome) || (bothSticks.isSubset(of: snapshot.pressed) && !bothSticks.isSubset(of: before.pressed)) {
            events.append(.pauseMenu)
        }
        // A chord's buttons stay away from the game while the chord is held.
        let chord = bothSticks.isSubset(of: snapshot.pressed)
        held[id] = Set(snapshot.pressed.compactMap { element -> Int? in
            guard element.isRemappable, !(chord && bothSticks.contains(element)) else { return nil }
            return bindings.buttons[element]
        })
        sticks[id] = [0: filtered(snapshot.leftStick), 1: filtered(snapshot.rightStick)]
        return events + resolve()
    }

    /// The keyboard's held keys (HID usages).
    public mutating func keyboard(_ keys: Set<Int>) -> [ControllerEvent] {
        let pressedNow = keys.subtracting(keysDown)
        keysDown = keys
        var events: [ControllerEvent] = []
        let command = keys.contains(KeyboardKey.leftCommand) || keys.contains(KeyboardKey.rightCommand)
        if pressedNow.contains(KeyboardKey.escape) { events.append(.pauseMenu) }
        if command, pressedNow.contains(KeyboardKey.s) { events.append(.quickSave) }
        if command, pressedNow.contains(KeyboardKey.l) { events.append(.quickLoad) }
        // With ⌘ held, keys make shortcuts rather than presses.
        held[Self.keyboardID] = command ? [] : Set(keys.compactMap { bindings.keys[$0] })
        return events + resolve()
    }

    /// Releases everything every device holds (the app leaving the foreground, the session
    /// ending); devices stay connected.
    public mutating func releaseAll() -> [ControllerEvent] {
        for id in held.keys { held[id] = [] }
        for id in sticks.keys { sticks[id] = [:] }
        keysDown = []
        lastSnapshot = [:]
        return resolve()
    }

    private func port(of id: String) -> Int? {
        id == Self.keyboardID ? 0 : ports[id]
    }

    private func filtered(_ stick: SIMD2<Double>) -> SIMD2<Double> {
        // libretro's analog y points down.
        let x = abs(stick.x) < deadzone ? 0 : stick.x
        let y = abs(stick.y) < deadzone ? 0 : stick.y
        return SIMD2(x, -y)
    }

    /// Reports each port's change since the last report: buttons are held while any of the
    /// port's devices holds them; a stick is the strongest of its devices' sticks.
    private mutating func resolve() -> [ControllerEvent] {
        var buttonsByPort: [Int: Set<Int>] = [:]
        var sticksByPort: [Int: [Int: SIMD2<Double>]] = [:]
        for (id, buttons) in held {
            guard let port = port(of: id) else { continue }
            buttonsByPort[port, default: []].formUnion(buttons)
        }
        for (id, deviceSticks) in sticks {
            guard let port = port(of: id) else { continue }
            for (stick, value) in deviceSticks {
                let current = sticksByPort[port]?[stick] ?? .zero
                if abs(value.x) + abs(value.y) >= abs(current.x) + abs(current.y) { sticksByPort[port, default: [:]][stick] = value }
            }
        }
        var events: [ControllerEvent] = []
        for port in Set(buttonsByPort.keys).union(portHeld.keys).sorted() {
            let now = buttonsByPort[port] ?? []
            let before = portHeld[port] ?? []
            for button in now.subtracting(before).sorted() { events.append(.digital(port: port, button: button, pressed: true)) }
            for button in before.subtracting(now).sorted() { events.append(.digital(port: port, button: button, pressed: false)) }
            portHeld[port] = now.isEmpty ? nil : now
        }
        for port in Set(sticksByPort.keys).union(portSticks.keys).sorted() {
            for stick in [0, 1] {
                let now = sticksByPort[port]?[stick] ?? .zero
                let before = portSticks[port]?[stick] ?? .zero
                guard now != before else { continue }
                events.append(.motion(port: port, stick: stick, x: now.x, y: now.y))
            }
            let now = sticksByPort[port] ?? [:]
            portSticks[port] = now.values.allSatisfy({ $0 == .zero }) ? nil : now
        }
        return events
    }
}
