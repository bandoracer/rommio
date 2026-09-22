#if canImport(UIKit)
import SwiftUI
import UIKit
import RommioFoundation
import RommioPlayerKit

/// A continuous D-pad and independent controls occupy reserved rails.
/// Mirroring swaps the rails without reversing individual button labels.
struct ExpansionControlsOverlay: View {
    let controls: [ExpansionControl]
    let size: CGSize
    let insets: EdgeInsets
    let leftHanded: Bool
    let scale: Double
    let opacity: Double
    let showControls: Bool
    let onDigital: (Int, Bool) -> Void
    let onMotion: (Int, Double, Double) -> Void
    let onMenu: () -> Void
    var theme: PlayerVisualTheme? = nil
    var aspectRatio: Double? = nil
    /// Fold and pane facts; the same stage the renderer uses.
    var stage = PlayerStageContext()
    var topAlignedInPortrait = true
    @State private var cButtons: Set<String> = []

    var body: some View {
        let deck = resolvePlayerStageLayout(containerSize: size,
            safeAreaInsets: PlayerViewportSafeAreaInsets(top: insets.top, bottom: insets.bottom, left: insets.leading, right: insets.trailing),
            aspectRatio: aspectRatio, topAlignedInPortrait: topAlignedInPortrait,
            controlDeckHeight: ExpansionControls.deckHeight(controls), controlScale: scale, reserveTouchSpace: showControls,
            utilityCount: showControls ? controls.filter(\.center).count + 1 : 1, stage: stage).deck
        let factor = deck.scale
        let utility = showControls ? controls.filter(\.center) : []
        let menuIndex = utility.count / 2
        let directions = controls.filter { [4, 5, 6, 7].contains($0.key ?? -1) }
        ZStack(alignment: .topLeading) {
            if showControls {
                if !directions.isEmpty {
                    ConsoleDPad(style: theme?.neutralControlStyle, onDigital: onDigital)
                        .frame(width: 144 * factor, height: 144 * factor)
                        .position(x: (leftHanded ? deck.rightOrigin : deck.leftOrigin) + 74 * factor, y: deck.railTop + 122 * factor)
                        .opacity(opacity)
                }
                ForEach(controls.filter { ![4, 5, 6, 7].contains($0.key ?? -1) }) { control in
                    let left = control.left != leftHanded
                    let origin = left ? deck.leftOrigin : deck.rightOrigin
                    let diameter = control.center ? 48 : control.size * factor
                    let utilityIndex = utility.firstIndex(where: { $0.id == control.id }) ?? 0
                    let centerX = control.center ? deck.utilityX(index: utilityIndex < menuIndex ? utilityIndex : utilityIndex + 1, count: utility.count + 1, width: size.width) : origin + control.x * factor
                    let centerY = control.center ? deck.utilityY : deck.railTop + control.y * factor
                    ConsoleTouchSurface(label: control.label, stick: control.stick != nil, trigger: control.label == "Z" && control.key == 12, style: theme?.faceStyle(label: control.label),
                        onValue: { pressed, x, y in
                            if !control.combo.isEmpty { control.combo.forEach { onDigital($0, pressed) } }
                            else if let key = control.key { onDigital(key, pressed) }
                            else if let stick = control.stick { onMotion(stick, x, y) }
                            else {
                                if pressed { cButtons.insert(control.id) } else { cButtons.remove(control.id) }
                                let held = controls.filter { cButtons.contains($0.id) }
                                onMotion(1, min(1, max(-1, held.reduce(0) { $0 + $1.axisX })), min(1, max(-1, held.reduce(0) { $0 + $1.axisY })))
                            }
                        })
                        .frame(width: diameter, height: diameter)
                        .position(x: centerX, y: centerY)
                        .opacity(opacity)
                }
            }
            Button(action: onMenu) {
                Image(systemName: "line.3.horizontal").font(.system(size: 17, weight: .medium))
                    .foregroundStyle(theme?.textColor ?? .white).frame(width: 48, height: 44)
                    .background((theme?.panelAltColor ?? .gray).opacity(0.65), in: RoundedRectangle(cornerRadius: 12))
            }
                .accessibilityLabel("Menu").accessibilityIdentifier("player.menu")
                .position(x: deck.utilityX(index: menuIndex, count: utility.count + 1, width: size.width), y: deck.utilityY)

        }
        .frame(width: size.width, height: size.height)
    }
}

private struct ConsoleTouchSurface: UIViewRepresentable {
    let label: String
    let stick: Bool
    let trigger: Bool
    let style: PlayerControlStyle?
    let onValue: (Bool, Double, Double) -> Void
    func makeUIView(context: Context) -> ConsoleInputView { ConsoleInputView() }
    func updateUIView(_ view: ConsoleInputView, context: Context) {
        view.label.text = ["Up": "↑", "Down": "↓", "Left": "←", "Right": "→", "Square": "□", "Cross": "×", "Circle": "○", "Triangle": "△", "C-Up": "C↑", "C-Down": "C↓", "C-Left": "C←", "C-Right": "C→"][label] ?? label
        view.label.font = .systemFont(ofSize: ["Up", "Down", "Left", "Right", "Square", "Cross", "Circle", "Triangle"].contains(label) ? 20 : 13, weight: .semibold)
        view.fill = style.map { UIColor($0.fillColor) } ?? UIColor.black.withAlphaComponent(0.52)
        view.pressedFill = style.map { UIColor($0.pressedFillColor) } ?? UIColor.white.withAlphaComponent(0.4)
        view.label.textColor = style.map { UIColor($0.contentColor) } ?? .white
        view.border = style.map { UIColor($0.borderColor) } ?? UIColor.white.withAlphaComponent(0.22)
        view.pressedBorder = style.map { UIColor($0.pressedBorderColor) } ?? .white
        view.isPill = trigger || ["L", "R", "L1", "L2", "R1", "R2", "L3", "R3", "Start", "Select", "Mode", "Pause", "Coin", "Option", "Analog"].contains(label)
        view.isDirection = ["Up", "Down", "Left", "Right"].contains(label)
        view.isStick = stick
        view.onValue = onValue
        view.accessibilityLabel = label
        view.accessibilityIdentifier = "player.control.\(label)"
        view.setNeedsLayout()
    }
    static func dismantleUIView(_ view: ConsoleInputView, coordinator: ()) { view.cancel() }
}

/// UIKit gives every control ownership of one touch, so several controls can be
/// held together and the stick continues tracking outside its initial hit area.
private final class ConsoleInputView: UIView {
    let label = UILabel()
    private let knob = UIView()
    private let face = UIView()
    var border = UIColor.white.withAlphaComponent(0.22)
    var pressedBorder = UIColor.white
    var isPill = false
    var isDirection = false
    private var downSent = false
    var fill = UIColor.black.withAlphaComponent(0.52) { didSet { if activeTouch == nil { face.backgroundColor = fill } } }
    var pressedFill = UIColor.white.withAlphaComponent(0.4)
    var isStick = false
    var onValue: ((Bool, Double, Double) -> Void)?
    private weak var activeTouch: UITouch?
    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        face.isUserInteractionEnabled = false; addSubview(face)
        face.layer.borderWidth = 1
        label.textColor = .white; label.font = .systemFont(ofSize: 12, weight: .semibold)
        label.textAlignment = .center; label.adjustsFontSizeToFitWidth = true
        label.isUserInteractionEnabled = false; addSubview(label)
        knob.backgroundColor = UIColor.white.withAlphaComponent(0.5); knob.isUserInteractionEnabled = false; addSubview(knob)
        isAccessibilityElement = true; accessibilityTraits = [.button]
        isMultipleTouchEnabled = false
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    override func layoutSubviews() {
        super.layoutSubviews()
        let visualSize = isStick ? bounds.width : bounds.width * (isPill ? 0.94 : 0.78)
        face.frame = CGRect(x: (bounds.width - visualSize) / 2, y: (bounds.height - (isPill ? visualSize * 0.72 : visualSize)) / 2,
            width: visualSize, height: isPill ? visualSize * 0.72 : visualSize)
        face.layer.cornerRadius = isPill || isDirection ? 8 : face.bounds.width / 2
        face.backgroundColor = activeTouch == nil ? fill : pressedFill
        face.layer.borderColor = (activeTouch == nil ? border : pressedBorder).cgColor
        label.frame = bounds.insetBy(dx: 2, dy: 2)
        knob.isHidden = !isStick; label.isHidden = isStick
        knob.bounds = CGRect(x: 0, y: 0, width: bounds.width * 0.38, height: bounds.height * 0.38)
        knob.layer.cornerRadius = knob.bounds.width / 2
        if activeTouch == nil { knob.center = CGPoint(x: bounds.midX, y: bounds.midY) }
    }
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard activeTouch == nil, let touch = touches.first else { return }
        activeTouch = touch; update(touch)
    }
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let touch = activeTouch, touches.contains(touch) else { return }; update(touch)
    }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        if let touch = activeTouch, touches.contains(touch) { cancel() }
    }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { cancel() }
    override func didMoveToWindow() { super.didMoveToWindow(); if window == nil { cancel() } }
    private func update(_ touch: UITouch) {
        let point = touch.location(in: self)
        if !isStick {
            if bounds.contains(point) { face.backgroundColor = pressedFill; face.layer.borderColor = pressedBorder.cgColor; if !downSent { downSent = true; onValue?(true, 0, 0) } }
            else { cancel() }
            return
        }
        let radius = max(1, bounds.width * 0.32)
        let dx = (point.x - bounds.midX) / radius, dy = (point.y - bounds.midY) / radius
        let length = max(1, hypot(dx, dy)); let x = dx / length, y = dy / length
        knob.center = CGPoint(x: bounds.midX + x * radius, y: bounds.midY + y * radius)
        onValue?(true, x, y)
    }
    func cancel() {
        guard activeTouch != nil else { return }
        activeTouch = nil; downSent = false; face.backgroundColor = fill; face.layer.borderColor = border.cgColor
        knob.center = CGPoint(x: bounds.midX, y: bounds.midY); onValue?(false, 0, 0)
    }
    override func accessibilityActivate() -> Bool {
        guard !isStick else { return false }
        onValue?(true, 0, 0); onValue?(false, 0, 0); return true
    }
}
private struct ConsoleDPad: UIViewRepresentable {
    let style: PlayerControlStyle?
    let onDigital: (Int, Bool) -> Void
    func makeUIView(context: Context) -> DPadInputView { DPadInputView() }
    func updateUIView(_ view: DPadInputView, context: Context) {
        view.onDigital = onDigital
        view.fill = style.map { UIColor($0.fillColor) } ?? UIColor(white: 0.14, alpha: 1)
        view.accent = style.map { UIColor($0.pressedBorderColor) } ?? .white
        view.setNeedsLayout()
    }
    static func dismantleUIView(_ view: DPadInputView, coordinator: ()) { view.release() }
}

/// One continuous surface permits diagonal presses and sliding with one thumb.
private final class DPadInputView: UIView {
    var onDigital: ((Int, Bool) -> Void)?
    var fill = UIColor(white: 0.14, alpha: 1)
    var accent = UIColor.white
    private var held: Set<Int> = []
    private var active = false
    private let directions = [(4, "Up", "↑", 0.0, -1.0), (5, "Down", "↓", 0.0, 1.0), (6, "Left", "←", -1.0, 0.0), (7, "Right", "→", 1.0, 0.0)]
    private var buttons: [ConsoleInputView] = []
    override init(frame: CGRect) {
        super.init(frame: frame)
        for (key, name, glyph, _, _) in directions {
            let button = ConsoleInputView(); button.isUserInteractionEnabled = false; button.isDirection = true
            button.label.text = glyph; button.label.font = .systemFont(ofSize: 20, weight: .medium)
            button.accessibilityIdentifier = "player.control.\(name)"; button.accessibilityLabel = name
            button.onValue = { [weak self] pressed, _, _ in self?.onDigital?(key, pressed) }
            buttons.append(button); addSubview(button)
        }
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    override func layoutSubviews() {
        super.layoutSubviews()
        for (i, direction) in directions.enumerated() {
            let size = bounds.width / 3
            let button = buttons[i]
            button.frame = CGRect(x: bounds.midX + direction.3 * size - size / 2, y: bounds.midY + direction.4 * size - size / 2, width: size, height: size)
            button.fill = held.contains(direction.0) ? accent.withAlphaComponent(0.35) : fill
            button.border = held.contains(direction.0) ? accent : UIColor.white.withAlphaComponent(0.16)
            button.setNeedsLayout()
        }
    }
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) { active = true; update(touches) }
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) { update(touches) }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) { release() }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { release() }
    override func didMoveToWindow() { super.didMoveToWindow(); if window == nil { release() } }
    private func update(_ touches: Set<UITouch>) {
        guard active, let point = touches.first?.location(in: self) else { return }
        guard bounds.contains(point) else { release(); return }
        let x = (point.x - bounds.midX) / (bounds.width / 3), y = (point.y - bounds.midY) / (bounds.height / 3)
        var next: Set<Int> = []
        if max(abs(x), abs(y)) > 0.28 {
            if abs(x) >= abs(y) * 0.42 { next.insert(x < 0 ? 6 : 7) }
            if abs(y) >= abs(x) * 0.42 { next.insert(y < 0 ? 4 : 5) }
        }
        change(next)
    }
    private func change(_ next: Set<Int>) {
        for key in held.subtracting(next).sorted() { onDigital?(key, false) }
        for key in next.subtracting(held).sorted() { onDigital?(key, true) }
        held = next; setNeedsLayout()
    }
    func release() { active = false; change([]) }
}
#endif
