#if canImport(GameController)
import GameController
import RommioFoundation

/// Routes physical controllers into the running core through the same ordered
/// queue as touch controls. Buttons map by position to libretro's SNES-style
/// layout (Apple's bottom face button is libretro B, and so on).
@MainActor
final class PlayerGamepadInput {
    private weak var feature: PlayerFeatureModel?
    private var observers: [NSObjectProtocol] = []
    private var pressed: Set<Int> = []
    private var sticks: [Int: (Double, Double)] = [:]

    // libretro RETRO_DEVICE_ID_JOYPAD_* values.
    private enum Joypad {
        static let b = 0, y = 1, select = 2, start = 3, up = 4, down = 5, left = 6, right = 7
        static let a = 8, x = 9, l = 10, r = 11, l2 = 12, r2 = 13, l3 = 14, r3 = 15
    }

    init(feature: PlayerFeatureModel) {
        self.feature = feature
    }

    func start() {
        guard observers.isEmpty else { return }
        GCController.controllers().forEach(attach)
        observers.append(NotificationCenter.default.addObserver(forName: .GCControllerDidConnect, object: nil, queue: .main) { [weak self] _ in
            // Re-scan on the main actor rather than sending the notification's object.
            MainActor.assumeIsolated { GCController.controllers().forEach { self?.attach($0) } }
        })
    }

    func stop() {
        observers.forEach(NotificationCenter.default.removeObserver)
        observers.removeAll()
        GCController.controllers().forEach {
            $0.extendedGamepad?.valueChangedHandler = nil
            $0.extendedGamepad?.buttonHome?.pressedChangedHandler = nil
        }
        // Release anything still held so the core never sees a stuck button.
        pressed.forEach { feature?.enqueueConsoleDigital($0, pressed: false) }
        pressed.removeAll()
    }

    private func attach(_ controller: GCController) {
        guard let pad = controller.extendedGamepad else { return }
        pad.valueChangedHandler = { [weak self] gamepad, _ in
            MainActor.assumeIsolated { self?.update(gamepad) }
        }
        // Home opens Rommio's pause menu; Menu and Options act as Start and Select.
        pad.buttonHome?.pressedChangedHandler = { [weak self] _, _, isPressed in
            guard isPressed else { return }
            MainActor.assumeIsolated {
                guard let feature = self?.feature else { return }
                Task { await feature.openPauseMenu() }
            }
        }
    }

    private func update(_ pad: GCExtendedGamepad) {
        guard let feature else { return }
        let deadzone = feature.controlsState?.hardwareBinding.deadzone ?? 0.2
        var down: Set<Int> = []
        func set(_ key: Int, _ isPressed: Bool) { if isPressed { down.insert(key) } }
        set(Joypad.b, pad.buttonA.isPressed)
        set(Joypad.a, pad.buttonB.isPressed)
        set(Joypad.y, pad.buttonX.isPressed)
        set(Joypad.x, pad.buttonY.isPressed)
        set(Joypad.l, pad.leftShoulder.isPressed)
        set(Joypad.r, pad.rightShoulder.isPressed)
        set(Joypad.l2, pad.leftTrigger.isPressed)
        set(Joypad.r2, pad.rightTrigger.isPressed)
        set(Joypad.l3, pad.leftThumbstickButton?.isPressed ?? false)
        set(Joypad.r3, pad.rightThumbstickButton?.isPressed ?? false)
        set(Joypad.select, pad.buttonOptions?.isPressed ?? false)
        set(Joypad.start, pad.buttonMenu.isPressed)
        set(Joypad.up, pad.dpad.up.isPressed)
        set(Joypad.down, pad.dpad.down.isPressed)
        set(Joypad.left, pad.dpad.left.isPressed)
        set(Joypad.right, pad.dpad.right.isPressed)

        for key in down.subtracting(pressed) { feature.enqueueConsoleDigital(key, pressed: true) }
        for key in pressed.subtracting(down) { feature.enqueueConsoleDigital(key, pressed: false) }
        pressed = down

        sendStick(0, pad.leftThumbstick, deadzone: deadzone)
        sendStick(1, pad.rightThumbstick, deadzone: deadzone)
    }

    private func sendStick(_ stick: Int, _ input: GCControllerDirectionPad, deadzone: Double) {
        func filtered(_ value: Float) -> Double { abs(Double(value)) < deadzone ? 0 : Double(value) }
        // libretro's analog Y axis points down.
        let value = (filtered(input.xAxis.value), -filtered(input.yAxis.value))
        guard sticks[stick].map({ $0 != value }) ?? (value != (0, 0)) else { return }
        sticks[stick] = value
        feature?.enqueueConsoleMotion(stick, x: value.0, y: value.1)
    }
}
#endif
