import RommioFoundation

/// A control pressed while the player is choosing one (Remap buttons).
enum ControllerCapture: Equatable {
    case element(ControllerElement)
    case key(Int)
}

#if canImport(GameController)
import GameController
#if canImport(UIKit)
import UIKit
#endif

/// Physical controllers and a keyboard, into the running core (revised 2026-10-03). Each
/// controller and the keyboard reports its whole state to a `ControllerRouter`, which turns
/// it into presses on the controller's own port (player 1, 2… in the order they connected)
/// with the console's bindings, releases everything a controller held when it disconnects or
/// the app leaves the foreground, and raises the hotkeys. While Remap buttons listens, the
/// next control pressed goes to it instead of the game.
@MainActor
final class PlayerGamepadInput {
    private weak var feature: PlayerFeatureModel?
    private var observers: [NSObjectProtocol] = []
    private var router = ControllerRouter()
    private var keysDown: Set<Int> = []
    private var lastPressed: [String: Set<ControllerElement>] = [:]
    /// Set while Remap buttons waits for a control.
    var capture: ((ControllerCapture) -> Void)?

    init(feature: PlayerFeatureModel) {
        self.feature = feature
    }

    /// Each connected controller's player (1-based), by `GameControllerMonitor.deviceID`.
    var players: [Int: Int] {
        Dictionary(uniqueKeysWithValues: router.ports.compactMap { id, port in Int(id).map { ($0, port + 1) } })
    }

    var keyboardConnected: Bool { GCKeyboard.coalesced != nil }

    /// The first controller's own symbol for `element` (a PlayStation's shapes, an Xbox's
    /// letters), when it has one.
    func symbol(for element: ControllerElement) -> String? {
        guard let pad = GCController.controllers().compactMap(\.extendedGamepad).first else { return nil }
        let input: GCControllerElement? = switch element {
        case .buttonA: pad.buttonA
        case .buttonB: pad.buttonB
        case .buttonX: pad.buttonX
        case .buttonY: pad.buttonY
        case .leftShoulder: pad.leftShoulder
        case .rightShoulder: pad.rightShoulder
        case .leftTrigger: pad.leftTrigger
        case .rightTrigger: pad.rightTrigger
        case .leftThumbstickButton: pad.leftThumbstickButton
        case .rightThumbstickButton: pad.rightThumbstickButton
        case .buttonMenu: pad.buttonMenu
        case .buttonOptions: pad.buttonOptions
        case .buttonHome: pad.buttonHome
        case .dpadUp: pad.dpad.up
        case .dpadDown: pad.dpad.down
        case .dpadLeft: pad.dpad.left
        case .dpadRight: pad.dpad.right
        }
        return input?.sfSymbolsName
    }

    func start() {
        guard observers.isEmpty else { return }
        #if DEBUG && os(iOS)
        connectVirtualControllerIfAsked()
        #endif
        GCController.controllers().forEach(attach)
        attachKeyboard()
        let center = NotificationCenter.default
        // Re-scan on the main actor rather than sending the notifications' objects across.
        observers.append(center.addObserver(forName: .GCControllerDidConnect, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { GCController.controllers().forEach { self?.attach($0) } }
        })
        observers.append(center.addObserver(forName: .GCControllerDidDisconnect, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.dropDisconnected() }
        })
        observers.append(center.addObserver(forName: .GCKeyboardDidConnect, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.attachKeyboard() }
        })
        observers.append(center.addObserver(forName: .GCKeyboardDidDisconnect, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.keyboardChanged([]) }
        })
        #if canImport(UIKit)
        for name in [UIApplication.willResignActiveNotification, UIApplication.didEnterBackgroundNotification] {
            observers.append(center.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.releaseAll() }
            })
        }
        #endif
    }

    #if DEBUG && os(iOS)
    private var virtualController: GCVirtualController?

    /// `ROMMIO_VIRTUAL_CONTROLLER=1` (Debug): the system's on-screen controller, which the app
    /// sees as a connected gamepad, so a Simulator can play by controller (UI tests tap it).
    /// `ROMMIO_VIRTUAL_CONTROLLER_DROP_AFTER=<seconds>` disconnects it then, for checking that
    /// whatever it held is let go.
    private func connectVirtualControllerIfAsked() {
        let environment = ProcessInfo.processInfo.environment
        guard environment["ROMMIO_VIRTUAL_CONTROLLER"] == "1", virtualController == nil else { return }
        let configuration = GCVirtualController.Configuration()
        configuration.elements = [GCInputButtonA, GCInputButtonB, GCInputDirectionPad]
        let controller = GCVirtualController(configuration: configuration)
        virtualController = controller
        let dropAfter = Double(environment["ROMMIO_VIRTUAL_CONTROLLER_DROP_AFTER"] ?? "")
        Task { @MainActor [weak self] in
            // Once the player is on screen. The controller inserts its views beneath a SwiftUI
            // window's content (unseen and untouchable): find what it added and raise it.
            try? await Task.sleep(for: .seconds(1.5))
            let before = Self.viewIDs()
            controller.connect { _ in }
            try? await Task.sleep(for: .seconds(0.5))
            var raised: [UIView] = []
            for view in Self.allViews() where !before.contains(ObjectIdentifier(view)) {
                // Onto the window itself, above the player's full-screen presentation.
                if let superview = view.superview, before.contains(ObjectIdentifier(superview)), let window = view.window {
                    let frame = superview.convert(view.frame, to: window)
                    window.addSubview(view)
                    view.frame = frame
                    raised.append(view)
                }
            }
            let dropAt = dropAfter.map { ContinuousClock.now + .seconds($0) }
            // Kept above sheets as they open (Remap buttons listens over the Controls sheet).
            while self?.virtualController != nil {
                raised.forEach { $0.superview?.bringSubviewToFront($0) }
                if let dropAt, ContinuousClock.now >= dropAt {
                    self?.virtualController?.disconnect()
                    return
                }
                try? await Task.sleep(for: .milliseconds(300))
            }
        }
    }
    private static func allViews() -> [UIView] {
        var views: [UIView] = []
        func visit(_ view: UIView) {
            views.append(view)
            view.subviews.forEach(visit)
        }
        for case let scene as UIWindowScene in UIApplication.shared.connectedScenes {
            scene.windows.forEach(visit)
        }
        return views
    }

    private static func viewIDs() -> Set<ObjectIdentifier> {
        Set(allViews().map(ObjectIdentifier.init))
    }
    #endif

    func stop() {
        #if DEBUG && os(iOS)
        virtualController?.disconnect()
        virtualController = nil
        #endif
        observers.forEach(NotificationCenter.default.removeObserver)
        observers.removeAll()
        for controller in GCController.controllers() {
            controller.extendedGamepad?.valueChangedHandler = nil
            controller.playerIndex = .indexUnset
        }
        GCKeyboard.coalesced?.keyboardInput?.keyChangedHandler = nil
        releaseAll()
        for id in router.ports.keys { _ = router.disconnect(id) }
        capture = nil
    }

    /// Lets go of everything held (nothing stays pressed in the core).
    func releaseAll() {
        keysDown = []
        lastPressed = [:]
        feature?.applyControllerEvents(router.releaseAll())
    }

    private func attach(_ controller: GCController) {
        guard let pad = controller.extendedGamepad else { return }
        #if DEBUG && targetEnvironment(simulator)
        // The Simulator's own stand-in gamepad (the Mac's input) isn't a player; with the
        // virtual controller, it alone is.
        if virtualController != nil, controller !== virtualController?.controller { return }
        #endif
        let id = String(GameControllerMonitor.deviceID(controller))
        guard let port = router.connect(id) else { return }
        // The controller's lights show its player, where it has them.
        controller.playerIndex = GCControllerPlayerIndex(rawValue: port) ?? .indexUnset
        // Home opens Rommio's pause menu rather than the system's games overlay.
        pad.buttonHome?.preferredSystemGestureState = .disabled
        pad.valueChangedHandler = { [weak self] gamepad, _ in
            MainActor.assumeIsolated { self?.changed(id, gamepad) }
        }
    }

    private func dropDisconnected() {
        let connected = Set(GCController.controllers().map { String(GameControllerMonitor.deviceID($0)) })
        for id in router.ports.keys where !connected.contains(id) {
            lastPressed[id] = nil
            feature?.applyControllerEvents(router.disconnect(id))
        }
    }

    private func changed(_ id: String, _ pad: GCExtendedGamepad) {
        guard let feature else { return }
        let snapshot = Self.snapshot(pad)
        if let capture {
            let pressedNow = snapshot.pressed.subtracting(lastPressed[id] ?? [])
            lastPressed[id] = snapshot.pressed
            if let element = ControllerElement.allCases.first(where: { pressedNow.contains($0) && $0.isRemappable }) {
                capture(.element(element))
            }
            return
        }
        lastPressed[id] = snapshot.pressed
        router.bindings = feature.controllerBindings
        router.deadzone = feature.controlsState?.hardwareBinding.deadzone ?? 0.2
        feature.applyControllerEvents(router.update(id, snapshot))
    }

    private func attachKeyboard() {
        GCKeyboard.coalesced?.keyboardInput?.keyChangedHandler = { [weak self] _, _, keyCode, pressed in
            MainActor.assumeIsolated {
                guard let self else { return }
                var keys = self.keysDown
                if pressed { keys.insert(keyCode.rawValue) } else { keys.remove(keyCode.rawValue) }
                self.keyboardChanged(keys)
            }
        }
    }

    private func keyboardChanged(_ keys: Set<Int>) {
        guard let feature else { return }
        let pressedNow = keys.subtracting(keysDown)
        keysDown = keys
        if let capture {
            if let key = pressedNow.first(where: { !KeyboardKey.isModifier($0) && $0 != KeyboardKey.escape }) { capture(.key(key)) }
            return
        }
        router.bindings = feature.controllerBindings
        feature.applyControllerEvents(router.keyboard(keys))
    }

    /// A controller's state, by position.
    static func snapshot(_ pad: GCExtendedGamepad) -> ControllerSnapshot {
        var pressed: Set<ControllerElement> = []
        func add(_ element: ControllerElement, _ button: GCControllerButtonInput?) {
            if button?.isPressed == true { pressed.insert(element) }
        }
        add(.buttonA, pad.buttonA)
        add(.buttonB, pad.buttonB)
        add(.buttonX, pad.buttonX)
        add(.buttonY, pad.buttonY)
        add(.leftShoulder, pad.leftShoulder)
        add(.rightShoulder, pad.rightShoulder)
        add(.leftTrigger, pad.leftTrigger)
        add(.rightTrigger, pad.rightTrigger)
        add(.leftThumbstickButton, pad.leftThumbstickButton)
        add(.rightThumbstickButton, pad.rightThumbstickButton)
        add(.buttonMenu, pad.buttonMenu)
        add(.buttonOptions, pad.buttonOptions)
        add(.buttonHome, pad.buttonHome)
        add(.dpadUp, pad.dpad.up)
        add(.dpadDown, pad.dpad.down)
        add(.dpadLeft, pad.dpad.left)
        add(.dpadRight, pad.dpad.right)
        return ControllerSnapshot(
            pressed: pressed,
            leftStick: SIMD2(Double(pad.leftThumbstick.xAxis.value), Double(pad.leftThumbstick.yAxis.value)),
            rightStick: SIMD2(Double(pad.rightThumbstick.xAxis.value), Double(pad.rightThumbstick.yAxis.value))
        )
    }
}
#endif
