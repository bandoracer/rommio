import SwiftUI
import RommioFoundation
import RommioPlayerKit

/// Menu over the player (everything but the touch surface): SwiftUI only, so the stack
/// budget measures it on every platform.
struct PlayerControlsChrome: View {
    let layout: PlayerTouchLayout
    let theme: PlayerVisualTheme
    let onMenu: () -> Void

    var body: some View {
        ZStack(alignment: .topLeading) {
            Color.clear
            PlayerMenuControl(appearance: PlayerControlStyleRenderer(theme: theme).menu(), diameter: layout.menuDiameter, action: onMenu)
                .position(layout.menuCenter)
        }
    }
}

/// The Menu (pause) button: an ordinary button with a 44 pt target, drawn by the style renderer.
private struct PlayerMenuControl: View {
    let appearance: PlayerControlAppearance
    let diameter: CGFloat
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                let shape = PlayerShapeView(shape: appearance.shape)
                if let shadow = appearance.shadow {
                    shape.fill(Color.black.opacity(0.001)).shadow(color: shadow.color, radius: shadow.radius / 2, y: shadow.y / 2)
                }
                if appearance.frosted {
                    shape.fill(.ultraThinMaterial)
                }
                shape.fill(appearance.fill)
                shape.stroke(appearance.stroke, lineWidth: max(appearance.strokeWidth, 0.5))
                if let rim = appearance.rim {
                    shape.stroke(LinearGradient(stops: [.init(color: rim, location: 0), .init(color: rim.opacity(0), location: 0.12)],
                                                startPoint: .top, endPoint: .bottom), lineWidth: 1)
                }
                Image(systemName: appearance.symbol ?? "line.3.horizontal")
                    .font(.system(size: 17, weight: appearance.frosted ? .medium : .bold))
                    .foregroundStyle(appearance.ink)
            }
            .frame(width: appearance.frosted ? diameter : 52, height: appearance.frosted ? diameter : 34)
            .frame(width: max(diameter, 52), height: diameter)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Menu").accessibilityIdentifier("player.menu")
    }
}

/// A SwiftUI shape for a control outline.
private struct PlayerShapeView: Shape {
    let shape: PlayerControlShape

    func path(in rect: CGRect) -> Path {
        switch shape {
        case .circle:
            return Path(ellipseIn: rect)
        case .capsule:
            return Path(roundedRect: rect, cornerRadius: rect.height / 2)
        case let .rounded(radius):
            return Path(roundedRect: rect, cornerRadius: min(radius, rect.height / 2))
        case let .tab(top, bottom):
            let t = min(top, rect.height / 2), b = min(bottom, rect.height / 2)
            return Path(roundedRect: rect, cornerRadii: RectangleCornerRadii(topLeading: t, bottomLeading: b, bottomTrailing: b, topTrailing: t))
        case let .bumper(leading, top, bottom):
            let t = min(top, rect.height / 2), b = min(bottom, rect.height / 2)
            return Path(roundedRect: rect, cornerRadii: RectangleCornerRadii(
                topLeading: leading ? 0 : t, bottomLeading: leading ? 0 : b, bottomTrailing: leading ? b : 0, topTrailing: leading ? t : 0))
        case let .gate(reach):
            return Path(PlayerGatePath.path(in: rect, reach: reach))
        }
    }
}

#if canImport(UIKit)
import UIKit

/// The player's touch controls (docs/player-controls.md): one multi-touch surface
/// with every control and the Menu button.
/// Where things go is `PlayerTouchLayout`'s; how they look, `PlayerControlStyleRenderer`'s;
/// what touches do, `PlayerTouchTracker`'s.
struct PlayerControlsOverlay: View {
    let layout: PlayerTouchLayout
    let theme: PlayerVisualTheme
    /// The user's opacity (Aurora only; faces never below 0.5).
    let opacity: Double
    /// Controls show (they hide while paused); Menu always shows.
    let showControls: Bool
    let haptics: Bool
    let onInput: (PlayerTouchOutput) -> Void
    let onMenu: () -> Void

    var body: some View {
        ZStack(alignment: .topLeading) {
            PlayerTouchSurface(layout: layout, renderer: PlayerControlStyleRenderer(theme: theme), opacity: opacity,
                               active: showControls, haptics: haptics, onInput: onInput)
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("player.touchControls")
            PlayerControlsChrome(layout: layout, theme: theme, onMenu: onMenu)
        }
    }
}

// MARK: The surface

private struct PlayerTouchSurface: UIViewRepresentable {
    let layout: PlayerTouchLayout
    let renderer: PlayerControlStyleRenderer
    let opacity: Double
    let active: Bool
    let haptics: Bool
    let onInput: (PlayerTouchOutput) -> Void

    func makeUIView(context: Context) -> PlayerTouchSurfaceView { PlayerTouchSurfaceView() }

    func updateUIView(_ view: PlayerTouchSurfaceView, context: Context) {
        view.onInput = onInput
        view.hapticsEnabled = haptics
        view.configure(layout: layout, renderer: renderer, opacity: opacity, active: active)
    }

    static func dismantleUIView(_ view: PlayerTouchSurfaceView, coordinator: ()) { view.releaseAll() }
}

/// One `UIView` owns every touch control (`isMultipleTouchEnabled`). Control views only
/// draw and carry the `player.control.*` identifiers; touches go to the tracker and its
/// presses and releases go out at once, in order. Touches that miss every control
/// fall through to the views below.
final class PlayerTouchSurfaceView: UIView {
    var onInput: ((PlayerTouchOutput) -> Void)?
    var hapticsEnabled = true
    private var tracker = PlayerTouchTracker(layout: PlayerTouchLayout(controls: [], faceZone: nil, chord: nil, menuCenter: .zero,
                                                                        menuDiameter: 44, metrics: PlayerControlMetrics(scale: 1)))
    private var renderer: PlayerControlStyleRenderer?
    private var opacity: Double = 1
    private var active = true
    private var buttons: [String: PlayerControlButtonView] = [:]
    private var dpad: PlayerDPadControlView?
    private var touchIDs: [ObjectIdentifier: Int] = [:]
    private var nextTouchID = 0
    private let impact = UIImpactFeedbackGenerator(style: .light)
    private let selection = UISelectionFeedbackGenerator()
    /// A stick entering full tilt: one soft tick (the gate a touch stick doesn't have).
    private let stickTick = UIImpactFeedbackGenerator(style: .soft)
    /// A stick settling into a notch of its gate: the N64 octagon's crisp click, the
    /// DualShock square's softer corner bump.
    private let notchClick = UIImpactFeedbackGenerator(style: .rigid)
    private let cornerBump = UIImpactFeedbackGenerator(style: .medium)
    #if DEBUG
    private let inputLog = UILabel()
    #endif

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isMultipleTouchEnabled = true
        isAccessibilityElement = false
        let center = NotificationCenter.default
        center.addObserver(self, selector: #selector(releaseForBackground), name: UIApplication.willResignActiveNotification, object: nil)
        center.addObserver(self, selector: #selector(releaseForBackground), name: UIApplication.didEnterBackgroundNotification, object: nil)
        #if DEBUG
        if PlayerInputLog.isEnabled {
            // UI tests read what the core received (`ROMMIO_INPUT_LOG=1`).
            inputLog.isAccessibilityElement = true
            inputLog.accessibilityIdentifier = "player.debug.inputLog"
            inputLog.alpha = 0.02
            inputLog.frame = CGRect(x: 0, y: 0, width: 2, height: 2)
            addSubview(inputLog)
            center.addObserver(self, selector: #selector(inputLogChanged), name: PlayerInputLog.didChange, object: nil)
            inputLogChanged()
        }
        #endif
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    deinit { NotificationCenter.default.removeObserver(self) }

    func configure(layout: PlayerTouchLayout, renderer: PlayerControlStyleRenderer, opacity: Double, active: Bool) {
        let layoutChanged = layout != tracker.layout
        let styleChanged = renderer != self.renderer || opacity != self.opacity
        if active != self.active {
            self.active = active
            // Pause (or anything else that hides the controls) lets go of everything.
            if !active { releaseAll() }
            for view in subviews where view is PlayerControlButtonView || view is PlayerDPadControlView { view.isHidden = !active }
        }
        if layoutChanged {
            perform(tracker.update(layout: layout))
            rebuild(layout)
        }
        if layoutChanged || styleChanged {
            self.renderer = renderer
            self.opacity = opacity
            repaint()
        }
        if hapticsEnabled { impact.prepare(); selection.prepare(); stickTick.prepare(); notchClick.prepare(); cornerBump.prepare() }
    }

    private func rebuild(_ layout: PlayerTouchLayout) {
        let ids = Set(layout.controls.filter { $0.kind != .dpad }.map(\.id))
        for (id, view) in buttons where !ids.contains(id) { view.removeFromSuperview(); buttons[id] = nil }
        for control in layout.controls {
            if control.kind == .dpad {
                let view = dpad ?? PlayerDPadControlView(surface: self)
                if dpad == nil { dpad = view; addSubview(view) }
                view.frame = control.frame
                view.isHidden = !active
            } else {
                let view = buttons[control.id] ?? PlayerControlButtonView(control: control, surface: self)
                if buttons[control.id] == nil { buttons[control.id] = view; addSubview(view) }
                view.control = control
                view.bounds = CGRect(origin: .zero, size: control.frame.size)
                view.center = control.center
                view.isHidden = !active
            }
        }
        if layout.dpad == nil { dpad?.removeFromSuperview(); dpad = nil }
        #if DEBUG
        bringSubviewToFront(inputLog)
        #endif
    }

    private func repaint() {
        guard let renderer else { return }
        let pressed = tracker.pressedControls
        for (id, view) in buttons {
            let appearance = renderer.appearance(label: view.control.spec.label, role: role(view.control)).placed(view.control)
            // Faces fade only their body; legends and console rings stay at full ink.
            let faded = renderer.opacity(user: opacity, role: appearance.role)
            view.alpha = appearance.role == .face ? 1 : faded
            view.bodyOpacity = appearance.role == .face ? faded : 1
            view.paint(appearance, pressed: pressed.contains(id), stick: tracker.stickPositions[id])
        }
        if let dpad {
            dpad.alpha = renderer.opacity(user: opacity, role: .direction)
            dpad.paint(renderer.dpad(), held: tracker.dpadKeys)
        }
    }

    private func role(_ control: PlacedPlayerControl) -> PlayerControlRole {
        switch control.kind {
        case .face: .face
        case .shoulder: .shoulder
        case .system: .system
        case .stick: .stick
        case .dpad: .direction
        }
    }

    // MARK: Touches

    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
        active && tracker.layout.target(at: point) != nil
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        for touch in touches {
            let id = nextTouchID
            nextTouchID += 1
            touchIDs[ObjectIdentifier(touch)] = id
            perform(tracker.began(id, at: touch.location(in: self)))
        }
        refreshPressed()
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        for touch in touches {
            guard let id = touchIDs[ObjectIdentifier(touch)] else { continue }
            perform(tracker.moved(id, to: touch.location(in: self)))
        }
        refreshPressed()
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) { end(touches) }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { end(touches) }

    private func end(_ touches: Set<UITouch>) {
        for touch in touches {
            guard let id = touchIDs.removeValue(forKey: ObjectIdentifier(touch)) else { continue }
            perform(tracker.ended(id))
        }
        refreshPressed()
    }

    /// Lets go of every held input: pause, background, the overlay going away.
    func releaseAll() {
        perform(tracker.releaseAll())
        touchIDs.removeAll()
        refreshPressed()
    }

    @objc private func releaseForBackground() { releaseAll() }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window == nil { releaseAll() }
    }

    /// Presses a control for VoiceOver (and UI tests' accessibility activation).
    func activate(_ id: String) {
        guard let control = tracker.layout.control(id) else { return }
        let touch = -1 - nextTouchID
        nextTouchID += 1
        perform(tracker.began(touch, at: control.center))
        perform(tracker.ended(touch))
        refreshPressed()
    }

    func activateDirection(_ key: Int) {
        guard let pad = tracker.layout.dpad else { return }
        let offset: CGPoint = switch key {
        case 4: CGPoint(x: 0, y: -pad.frame.height * 0.35)
        case 5: CGPoint(x: 0, y: pad.frame.height * 0.35)
        case 6: CGPoint(x: -pad.frame.width * 0.35, y: 0)
        default: CGPoint(x: pad.frame.width * 0.35, y: 0)
        }
        let touch = -1 - nextTouchID
        nextTouchID += 1
        perform(tracker.began(touch, at: CGPoint(x: pad.center.x + offset.x, y: pad.center.y + offset.y)))
        perform(tracker.ended(touch))
        refreshPressed()
    }

    private func perform(_ output: [PlayerTouchOutput]) {
        for item in output {
            switch item {
            case .digital, .motion:
                onInput?(item)
            case .pressHaptic:
                if hapticsEnabled { impact.impactOccurred(intensity: 0.75); impact.prepare() }
            case .directionHaptic:
                if hapticsEnabled { selection.selectionChanged(); selection.prepare() }
            case .stickHaptic:
                if hapticsEnabled { stickTick.impactOccurred(intensity: 0.4); stickTick.prepare() }
            case let .stickNotch(gate):
                // The octagon's notches click; the square's corners bump more softly.
                guard hapticsEnabled else { break }
                switch gate {
                case .octagon: notchClick.impactOccurred(intensity: 0.8); notchClick.prepare()
                case .square, .circle: cornerBump.impactOccurred(intensity: 0.55); cornerBump.prepare()
                }
            }
        }
    }

    /// Redraws the controls whose pressed state changed.
    private func refreshPressed() {
        guard let renderer else { return }
        let pressed = tracker.pressedControls
        for (id, view) in buttons {
            let isPressed = pressed.contains(id)
            let stick = tracker.stickPositions[id]
            guard isPressed != view.isPressed || stick != view.stick else { continue }
            view.paint(renderer.appearance(label: view.control.spec.label, role: role(view.control)).placed(view.control), pressed: isPressed, stick: stick)
        }
        if let dpad, dpad.held != tracker.dpadKeys {
            dpad.paint(renderer.dpad(), held: tracker.dpadKeys)
        }
    }

    #if DEBUG
    @objc private func inputLogChanged() {
        let text = PlayerInputLog.all.suffix(400).joined(separator: "|")
        inputLog.text = text.isEmpty ? "-" : text
        inputLog.accessibilityLabel = inputLog.text
    }
    #endif
}

/// One control, drawn: a face, shoulder, Select/Start pill or stick. Never takes touches.
/// A stick's view is its whole zone; its ring and knob draw at the floating centre
/// while held and at the zone's centre otherwise.
final class PlayerControlButtonView: UIView {
    var control: PlacedPlayerControl {
        didSet { if control.spec != oldValue.spec { describe() } }
    }
    private weak var surface: PlayerTouchSurfaceView?
    private let ringView = UIView()
    private lazy var painter = PlayerControlPainter(in: control.kind == .stick ? ringView : self)
    private let knobView = UIView()
    /// The ring's bright edge at full tilt.
    private let tiltEdge = CAShapeLayer()
    private(set) var isPressed = false
    private(set) var stick: PlayerStickPosition?
    /// The face's fill and frost opacity (the opacity setting, faces only).
    var bodyOpacity: CGFloat = 1

    init(control: PlacedPlayerControl, surface: PlayerTouchSurfaceView) {
        self.control = control
        self.surface = surface
        super.init(frame: control.frame)
        isUserInteractionEnabled = false
        backgroundColor = .clear
        ringView.isUserInteractionEnabled = false
        knobView.isUserInteractionEnabled = false
        if control.kind == .stick {
            addSubview(ringView)
            addSubview(knobView)
            tiltEdge.fillColor = nil
            tiltEdge.lineWidth = 2.5
            layer.addSublayer(tiltEdge)
        }
        describe()
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    private func describe() {
        isAccessibilityElement = true
        accessibilityTraits = [.button]
        accessibilityLabel = control.spec.label
        accessibilityIdentifier = "player.control.\(control.spec.label)"
    }

    func paint(_ appearance: PlayerControlAppearance, pressed: Bool, stick: PlayerStickPosition?) {
        let wasPressed = isPressed
        isPressed = pressed
        self.stick = stick
        if control.kind == .stick {
            // The travel ring and the knob, in the zone's coordinates.
            let origin = control.frame.origin
            func local(_ point: CGPoint) -> CGPoint { CGPoint(x: point.x - origin.x, y: point.y - origin.y) }
            // The ring at the centre (so the thumb sees where centre is), the knob at the
            // output position, the ring's edge bright at full tilt.
            let center = stick.map { local($0.center) } ?? CGPoint(x: bounds.midX, y: bounds.midY)
            let travel = max(control.travel, 12)
            ringView.bounds = CGRect(x: 0, y: 0, width: 2 * travel, height: 2 * travel)
            ringView.center = center
            painter.paint(appearance, bounds: ringView.bounds, pressed: pressed, in: ringView, bodyOpacity: bodyOpacity)
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            // The full-tilt edge follows the gate (octagon, near-square, or a circle).
            let ring = CGRect(x: center.x - travel, y: center.y - travel, width: 2 * travel, height: 2 * travel)
            tiltEdge.path = PlayerGatePath.path(in: ring, reach: CGFloat(control.stickProfile?.diagonalReach ?? 0.5.squareRoot()))
            tiltEdge.lineJoin = .round
            tiltEdge.strokeColor = UIColor(appearance.ink).withAlphaComponent(0.95).cgColor
            tiltEdge.isHidden = stick?.fullTilt != true
            CATransaction.commit()
            let size = travel * 0.95
            knobView.bounds = CGRect(x: 0, y: 0, width: size, height: size)
            knobView.layer.cornerRadius = size / 2
            knobView.backgroundColor = UIColor(appearance.ink).withAlphaComponent(appearance.frosted ? (stick?.fullTilt == true ? 0.8 : 0.55) : 0.9)
            knobView.center = stick.map { local($0.knob) } ?? center
            bringSubviewToFront(knobView)
        } else {
            painter.paint(appearance, bounds: bounds, pressed: pressed, in: self, bodyOpacity: bodyOpacity)
        }
        // Pressed: a quick 94% dip (Aurora).
        if pressed != wasPressed, appearance.pressedScale < 1 {
            let scale = pressed ? appearance.pressedScale : 1
            UIView.animate(withDuration: 0.06, delay: 0, options: [.beginFromCurrentState, .allowUserInteraction]) {
                self.transform = CGAffineTransform(scaleX: scale, y: scale)
            }
        }
    }

    override func accessibilityActivate() -> Bool {
        guard control.kind != .stick else { return false }
        surface?.activate(control.id)
        return true
    }
}

/// The d-pad, drawn. Four direction elements keep `player.control.Up` … for
/// accessibility and UI tests; they take no touches either.
final class PlayerDPadControlView: UIView {
    private weak var surface: PlayerTouchSurfaceView?
    private lazy var painter = PlayerDPadPainter(in: self)
    private(set) var held: Set<Int> = []
    private var directions: [PlayerDirectionElement] = []

    init(surface: PlayerTouchSurfaceView) {
        self.surface = surface
        super.init(frame: .zero)
        isUserInteractionEnabled = false
        backgroundColor = .clear
        for (key, name) in [(4, "Up"), (5, "Down"), (6, "Left"), (7, "Right")] {
            let element = PlayerDirectionElement(key: key, name: name, surface: surface)
            directions.append(element)
            addSubview(element)
        }
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func paint(_ appearance: PlayerDPadAppearance, held: Set<Int>) {
        self.held = held
        painter.paint(appearance, bounds: bounds, held: held)
        // Each arm's accessibility element: a third of the pad, never under 44 pt (46,
        // so rounding never takes a small pad's arm under).
        let size = max(46, bounds.width / 3)
        for element in directions {
            let offset: CGPoint = switch element.key {
            case 4: CGPoint(x: 0, y: -size)
            case 5: CGPoint(x: 0, y: size)
            case 6: CGPoint(x: -size, y: 0)
            default: CGPoint(x: size, y: 0)
            }
            element.frame = CGRect(x: bounds.midX + offset.x - size / 2, y: bounds.midY + offset.y - size / 2, width: size, height: size)
            bringSubviewToFront(element)
        }
    }
}

private final class PlayerDirectionElement: UIView {
    let key: Int
    private weak var surface: PlayerTouchSurfaceView?

    init(key: Int, name: String, surface: PlayerTouchSurfaceView) {
        self.key = key
        self.surface = surface
        super.init(frame: .zero)
        isUserInteractionEnabled = false
        backgroundColor = .clear
        isAccessibilityElement = true
        accessibilityTraits = [.button]
        accessibilityLabel = name
        accessibilityIdentifier = "player.control.\(name)"
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func accessibilityActivate() -> Bool {
        surface?.activateDirection(key)
        return true
    }
}
#endif
