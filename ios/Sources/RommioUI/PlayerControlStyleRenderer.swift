import SwiftUI

/// What a player control is, for styling. Touch handling never looks at this.
enum PlayerControlRole: Equatable, Sendable {
    case face, shoulder, direction, system, stick

    static let directions: Set<String> = ["Up", "Down", "Left", "Right"]
    static let shoulders: Set<String> = ["L", "R", "L1", "L2", "R1", "R2"]
    static let systems: Set<String> = ["L3", "R3", "Start", "Select", "Mode", "Pause", "Coin", "Option", "Analog"]

    static func resolve(label: String, stick: Bool, trigger: Bool) -> PlayerControlRole {
        if stick { return .stick }
        if directions.contains(label) { return .direction }
        if trigger || shoulders.contains(label) { return .shoulder }
        if systems.contains(label) { return .system }
        return .face
    }

    /// Pills (shoulders and system buttons) are wide and short; the rest are round.
    var isPill: Bool { self == .shoulder || self == .system }
}

/// A control's outline.
enum PlayerControlShape: Equatable, Sendable {
    case circle
    case capsule
    /// Rounded more at the top than the bottom (shoulder tabs).
    case tab(top: CGFloat, bottom: CGFloat)
    case rounded(CGFloat)
}

/// How one control is drawn: resolved from the player theme, then painted by
/// the UIKit input views. Geometry and hit areas are the input views' own.
struct PlayerControlAppearance: Equatable {
    let role: PlayerControlRole
    let shape: PlayerControlShape
    let fill: Color
    let pressedFill: Color
    let stroke: Color
    let pressedStroke: Color
    let strokeWidth: CGFloat
    let ink: Color
    /// The misregistered plate behind the control (Riso), and its offset.
    let plate: Color?
    let plateOffset: CGSize
    /// Frosted glass behind the fill (Aurora).
    let frosted: Bool
    let font: PlayerFontSpec
    /// The legend text, or nil when a symbol is drawn instead.
    let text: String?
    /// An SF Symbol legend (directions, the menu).
    let symbol: String?

    /// The drawn face inside a control's bounds. The proportions are the input
    /// views' long-standing ones, so a restyle never moves a control.
    static func faceRect(in bounds: CGRect, role: PlayerControlRole) -> CGRect {
        let visual = role == .stick ? bounds.width : bounds.width * (role.isPill ? 0.94 : 0.78)
        let height = role.isPill ? visual * 0.72 : visual
        return CGRect(x: bounds.midX - visual / 2, y: bounds.midY - height / 2, width: visual, height: height)
    }

    /// The legend size for a face of `diameter` points: the role's token size
    /// scaled from a 56 pt reference face.
    func legendSize(for diameter: CGFloat) -> CGFloat {
        let scale: CGFloat = role.isPill ? 1 : diameter / PlayerControlStyleRenderer.referenceDiameter
        return max(9, font.size * scale)
    }
}

/// The d-pad's drawing: a frosted disc with grooves and chevrons (Aurora) or two
/// ink plates (pink arms over a blue offset copy) with a yellow pivot (Riso).
struct PlayerDPadAppearance: Equatable {
    let twoPlate: Bool
    let disc: Color?
    let discStroke: Color?
    let frosted: Bool
    let arm: Color
    let pressedArm: Color
    let plate: Color?
    let plateOffset: CGSize
    let pivot: Color?
    let arrowInk: Color
    let glyphs: PlayerGlyphSet
}

/// Resolves every control's look from a `PlayerVisualTheme`. Pure: no UIKit,
/// so the style rules are unit-tested; the input views only paint the result.
struct PlayerControlStyleRenderer {
    /// Legend sizes in the tokens are for a face this wide.
    static let referenceDiameter: CGFloat = 56

    let theme: PlayerVisualTheme

    private var misregistration: CGSize {
        CGSize(width: theme.shape.misregistration, height: theme.shape.misregistration)
    }

    /// Legends as printed on a console: arrows and PlayStation shapes as glyphs.
    static func legend(for label: String) -> String {
        ["Up": "↑", "Down": "↓", "Left": "←", "Right": "→", "Square": "□", "Cross": "×", "Circle": "○", "Triangle": "△",
         "C-Up": "C↑", "C-Down": "C↓", "C-Left": "C←", "C-Right": "C→"][label] ?? label
    }

    func appearance(label: String, stick: Bool = false, trigger: Bool = false) -> PlayerControlAppearance {
        appearance(label: label, role: .resolve(label: label, stick: stick, trigger: trigger))
    }

    func appearance(label: String, role: PlayerControlRole) -> PlayerControlAppearance {
        let neutral = theme.neutralControlStyle
        let palette = theme.palette
        let frosted = theme.shape.frosted
        switch role {
        case .face, .direction:
            let style = role == .direction ? neutral : theme.faceStyle(label: label)
            return PlayerControlAppearance(
                role: role, shape: .circle, fill: style.fillColor, pressedFill: style.pressedFillColor,
                stroke: style.borderColor, pressedStroke: style.pressedBorderColor, strokeWidth: style.borderWidth,
                ink: style.contentColor, plate: style.plateColor, plateOffset: style.plateColor == nil ? .zero : misregistration,
                frosted: style.frosted, font: theme.legendFont, text: role == .direction ? nil : Self.legend(for: label),
                symbol: role == .direction ? theme.glyphs.direction(label) : nil)
        case .shoulder:
            if frosted {
                return PlayerControlAppearance(
                    role: role, shape: .tab(top: 14, bottom: 20), fill: neutral.fillColor, pressedFill: neutral.pressedFillColor,
                    stroke: neutral.borderColor, pressedStroke: neutral.pressedBorderColor, strokeWidth: neutral.borderWidth,
                    ink: neutral.contentColor, plate: nil, plateOffset: .zero, frosted: true,
                    font: theme.legendFont.resized(15), text: label, symbol: nil)
            }
            return PlayerControlAppearance(
                role: role, shape: .tab(top: 18, bottom: 6), fill: palette.shoulderFill.color,
                pressedFill: palette.shoulderFill.color.mix(with: .white, by: 0.35), stroke: .clear, pressedStroke: .clear,
                strokeWidth: 0, ink: palette.faceInk.color, plate: palette.offsetPlate.color, plateOffset: misregistration,
                frosted: false, font: theme.legendFont.resized(18), text: label, symbol: nil)
        case .system:
            return PlayerControlAppearance(
                role: role, shape: .capsule, fill: neutral.fillColor, pressedFill: neutral.pressedFillColor,
                stroke: neutral.borderColor, pressedStroke: neutral.pressedBorderColor, strokeWidth: frosted ? 1 : 1.5,
                ink: neutral.contentColor, plate: nil, plateOffset: .zero, frosted: frosted,
                font: theme.labelFont, text: label, symbol: nil)
        case .stick:
            return PlayerControlAppearance(
                role: role, shape: .circle, fill: neutral.fillColor, pressedFill: neutral.pressedFillColor,
                stroke: neutral.borderColor, pressedStroke: neutral.pressedBorderColor, strokeWidth: frosted ? 1 : 1.5,
                ink: neutral.contentColor, plate: nil, plateOffset: .zero, frosted: frosted,
                font: theme.labelFont, text: nil, symbol: nil)
        }
    }

    /// The menu (pause) button: frosted glass (Aurora) or a pink ink pill (Riso).
    func menu() -> PlayerControlAppearance {
        let palette = theme.palette
        let frosted = theme.shape.frosted
        return PlayerControlAppearance(
            role: .system, shape: frosted ? .circle : .capsule,
            fill: frosted ? theme.neutralControlStyle.fillColor : palette.menuFill.color,
            pressedFill: frosted ? theme.neutralControlStyle.pressedFillColor : palette.menuFill.color.mix(with: .white, by: 0.3),
            stroke: frosted ? theme.neutralControlStyle.borderColor : .clear, pressedStroke: .clear,
            strokeWidth: frosted ? 1 : 0, ink: frosted ? theme.neutralControlStyle.contentColor : palette.menuInk.color,
            plate: nil, plateOffset: .zero, frosted: frosted, font: theme.labelFont, text: nil, symbol: theme.glyphs.menu)
    }

    func dpad() -> PlayerDPadAppearance {
        let palette = theme.palette
        let neutral = theme.neutralControlStyle
        if theme.shape.dpad == .twoPlateCross {
            return PlayerDPadAppearance(
                twoPlate: true, disc: nil, discStroke: nil, frosted: false,
                arm: palette.dpadFill.color, pressedArm: palette.dpadFill.color.mix(with: .white, by: 0.35),
                plate: palette.dpadAltFill.color, plateOffset: misregistration, pivot: palette.pivot.color,
                arrowInk: palette.faceInk.color.opacity(0.8), glyphs: theme.glyphs)
        }
        return PlayerDPadAppearance(
            twoPlate: false, disc: neutral.fillColor, discStroke: neutral.borderColor, frosted: true,
            arm: neutral.contentColor.opacity(0.16), pressedArm: theme.accentColor.opacity(0.45),
            plate: nil, plateOffset: .zero, pivot: nil, arrowInk: neutral.contentColor, glyphs: theme.glyphs)
    }

    /// Controls print at full ink when the theme says so (Riso never lays them
    /// over a game screen); otherwise the user's opacity applies.
    func opacity(user: Double) -> Double {
        theme.shape.controlOpacity >= 1 ? 1 : user
    }
}

extension PlayerFontSpec {
    func resized(_ points: CGFloat) -> PlayerFontSpec {
        PlayerFontSpec(postScriptName: postScriptName, size: points, uppercase: uppercase, tracking: tracking)
    }

    init(postScriptName: String, size: CGFloat, uppercase: Bool, tracking: CGFloat) {
        self.postScriptName = postScriptName
        self.size = size
        self.uppercase = uppercase
        self.tracking = tracking
    }
}

#if canImport(UIKit)
import UIKit

extension PlayerControlShape {
    func path(in rect: CGRect) -> UIBezierPath {
        switch self {
        case .circle:
            return UIBezierPath(ovalIn: rect)
        case .capsule:
            return UIBezierPath(roundedRect: rect, cornerRadius: rect.height / 2)
        case let .rounded(radius):
            return UIBezierPath(roundedRect: rect, cornerRadius: min(radius, rect.height / 2))
        case let .tab(top, bottom):
            let t = min(top, rect.height / 2), b = min(bottom, rect.height / 2)
            let path = UIBezierPath()
            path.move(to: CGPoint(x: rect.minX + t, y: rect.minY))
            path.addLine(to: CGPoint(x: rect.maxX - t, y: rect.minY))
            path.addArc(withCenter: CGPoint(x: rect.maxX - t, y: rect.minY + t), radius: t, startAngle: -.pi / 2, endAngle: 0, clockwise: true)
            path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - b))
            path.addArc(withCenter: CGPoint(x: rect.maxX - b, y: rect.maxY - b), radius: b, startAngle: 0, endAngle: .pi / 2, clockwise: true)
            path.addLine(to: CGPoint(x: rect.minX + b, y: rect.maxY))
            path.addArc(withCenter: CGPoint(x: rect.minX + b, y: rect.maxY - b), radius: b, startAngle: .pi / 2, endAngle: .pi, clockwise: true)
            path.addLine(to: CGPoint(x: rect.minX, y: rect.minY + t))
            path.addArc(withCenter: CGPoint(x: rect.minX + t, y: rect.minY + t), radius: t, startAngle: .pi, endAngle: 1.5 * .pi, clockwise: true)
            path.close()
            return path
        }
    }
}

extension PlayerFontSpec {
    func uiFont(size points: CGFloat) -> UIFont {
        RommioFonts.registerIfNeeded()
        return UIFont(name: postScriptName, size: points) ?? .systemFont(ofSize: points, weight: .bold)
    }

    func attributed(_ text: String, size points: CGFloat, color: UIColor) -> NSAttributedString {
        NSAttributedString(string: uppercase ? text.uppercased() : text, attributes: [
            .font: uiFont(size: points), .foregroundColor: color, .kern: tracking * points,
        ])
    }
}

/// Paints one control's appearance into a view: the offset plate, frosted glass,
/// the face and its legend. Owns only sublayers; the view keeps its touches.
@MainActor
final class PlayerControlPainter {
    private let plate = CAShapeLayer()
    private let face = CAShapeLayer()
    private let blur = UIVisualEffectView(effect: nil)
    private let blurMask = CAShapeLayer()
    let label = UILabel()
    private let symbol = UIImageView()

    init(in view: UIView) {
        view.layer.addSublayer(plate)
        blur.isUserInteractionEnabled = false
        blur.layer.mask = blurMask
        view.addSubview(blur)
        view.layer.addSublayer(face)
        label.textAlignment = .center
        label.adjustsFontSizeToFitWidth = true
        label.minimumScaleFactor = 0.6
        label.isUserInteractionEnabled = false
        view.addSubview(label)
        symbol.contentMode = .scaleAspectFit
        symbol.isUserInteractionEnabled = false
        view.addSubview(symbol)
    }

    /// Hides everything (for controls that another view draws, like d-pad arms).
    func clear() {
        plate.path = nil
        face.path = nil
        blur.isHidden = true
        label.isHidden = true
        symbol.isHidden = true
    }

    func paint(_ appearance: PlayerControlAppearance?, faceRect: CGRect, pressed: Bool, bringToFront view: UIView) {
        guard let appearance else { clear(); return }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        let path = appearance.shape.path(in: faceRect)
        if let plateColor = appearance.plate {
            plate.path = appearance.shape.path(in: faceRect.offsetBy(dx: appearance.plateOffset.width, dy: appearance.plateOffset.height)).cgPath
            plate.fillColor = UIColor(plateColor).cgColor
        } else {
            plate.path = nil
        }
        blur.isHidden = !appearance.frosted
        if appearance.frosted {
            blur.effect = UIBlurEffect(style: .systemUltraThinMaterial)
            blur.frame = view.bounds
            blurMask.path = path.cgPath
        }
        face.path = path.cgPath
        face.fillColor = UIColor(pressed ? appearance.pressedFill : appearance.fill).cgColor
        face.strokeColor = UIColor(pressed ? appearance.pressedStroke : appearance.stroke).cgColor
        face.lineWidth = appearance.strokeWidth
        let legendSize = appearance.legendSize(for: min(faceRect.width, faceRect.height))
        if let text = appearance.text {
            label.isHidden = false
            label.attributedText = appearance.font.attributed(text, size: legendSize, color: UIColor(appearance.ink))
            label.frame = faceRect.insetBy(dx: 2, dy: 1)
        } else {
            label.isHidden = true
        }
        if let name = appearance.symbol {
            symbol.isHidden = false
            let config = UIImage.SymbolConfiguration(pointSize: max(12, min(faceRect.width, faceRect.height) * 0.36), weight: .bold)
            symbol.image = UIImage(systemName: name, withConfiguration: config)
            symbol.tintColor = UIColor(appearance.ink)
            symbol.frame = faceRect.insetBy(dx: faceRect.width * 0.2, dy: faceRect.height * 0.2)
        } else {
            symbol.isHidden = true
        }
        CATransaction.commit()
        view.bringSubviewToFront(label)
        view.bringSubviewToFront(symbol)
    }
}

/// Paints the whole d-pad (disc or plates, arms, pivot, arrows) into its view.
@MainActor
final class PlayerDPadPainter {
    private let disc = CAShapeLayer()
    private let blur = UIVisualEffectView(effect: nil)
    private let blurMask = CAShapeLayer()
    private let plate = CAShapeLayer()
    private let arms = CAShapeLayer()
    private let pressed = CAShapeLayer()
    private let pivot = CAShapeLayer()
    private var arrows: [String: UIImageView] = [:]

    init(in view: UIView) {
        blur.isUserInteractionEnabled = false
        blur.layer.mask = blurMask
        view.addSubview(blur)
        for layer in [disc, plate, arms, pressed, pivot] { view.layer.addSublayer(layer) }
        for name in ["Up", "Down", "Left", "Right"] {
            let image = UIImageView()
            image.contentMode = .scaleAspectFit
            image.isUserInteractionEnabled = false
            view.addSubview(image)
            arrows[name] = image
        }
    }

    /// `held` holds the directions (4 up, 5 down, 6 left, 7 right) being pressed.
    func paint(_ appearance: PlayerDPadAppearance, bounds: CGRect, held: Set<Int>) {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        let side = min(bounds.width, bounds.height)
        let square = CGRect(x: bounds.midX - side / 2, y: bounds.midY - side / 2, width: side, height: side)
        let armWidth = side * (appearance.twoPlate ? 0.34 : 0.32)
        let crossRect = appearance.twoPlate ? square.insetBy(dx: side * 0.02, dy: side * 0.02) : square.insetBy(dx: side * 0.13, dy: side * 0.13)
        let cross = Self.cross(in: crossRect, arm: armWidth, radius: appearance.twoPlate ? side * 0.05 : 8)
        if let discColor = appearance.disc {
            let discPath = UIBezierPath(ovalIn: square)
            disc.path = discPath.cgPath
            disc.fillColor = UIColor(discColor).cgColor
            disc.strokeColor = appearance.discStroke.map { UIColor($0).cgColor }
            disc.lineWidth = 1
            blur.isHidden = !appearance.frosted
            blur.effect = UIBlurEffect(style: .systemUltraThinMaterial)
            blur.frame = bounds
            blurMask.path = discPath.cgPath
        } else {
            disc.path = nil
            blur.isHidden = true
        }
        if let plateColor = appearance.plate {
            var transform = CGAffineTransform(translationX: appearance.plateOffset.width, y: appearance.plateOffset.height)
            plate.path = cross.cgPath.copy(using: &transform)
            plate.fillColor = UIColor(plateColor).cgColor
        } else {
            plate.path = nil
        }
        arms.path = cross.cgPath
        arms.fillColor = UIColor(appearance.arm).cgColor
        // The pressed arms light up.
        let pressedPath = UIBezierPath()
        let centre = CGPoint(x: crossRect.midX, y: crossRect.midY)
        let reach = crossRect.width / 2
        for key in held {
            let rect: CGRect = switch key {
            case 4: CGRect(x: centre.x - armWidth / 2, y: crossRect.minY, width: armWidth, height: reach - armWidth / 2)
            case 5: CGRect(x: centre.x - armWidth / 2, y: centre.y + armWidth / 2, width: armWidth, height: reach - armWidth / 2)
            case 6: CGRect(x: crossRect.minX, y: centre.y - armWidth / 2, width: reach - armWidth / 2, height: armWidth)
            default: CGRect(x: centre.x + armWidth / 2, y: centre.y - armWidth / 2, width: reach - armWidth / 2, height: armWidth)
            }
            pressedPath.append(UIBezierPath(roundedRect: rect, cornerRadius: min(8, armWidth * 0.2)))
        }
        pressed.path = pressedPath.cgPath
        pressed.fillColor = UIColor(appearance.pressedArm).cgColor
        if let pivotColor = appearance.pivot {
            let radius = armWidth * 0.32
            pivot.path = UIBezierPath(ovalIn: CGRect(x: centre.x - radius, y: centre.y - radius, width: radius * 2, height: radius * 2)).cgPath
            pivot.fillColor = UIColor(pivotColor).cgColor
            pivot.compositingFilter = "multiplyBlendMode"
        } else {
            pivot.path = nil
        }
        let arrowSize = armWidth * (appearance.twoPlate ? 0.42 : 0.5)
        let inset = armWidth * 0.55
        let positions: [String: CGPoint] = [
            "Up": CGPoint(x: centre.x, y: crossRect.minY + inset), "Down": CGPoint(x: centre.x, y: crossRect.maxY - inset),
            "Left": CGPoint(x: crossRect.minX + inset, y: centre.y), "Right": CGPoint(x: crossRect.maxX - inset, y: centre.y),
        ]
        for (name, view) in arrows {
            let config = UIImage.SymbolConfiguration(pointSize: arrowSize, weight: appearance.twoPlate ? .black : .semibold)
            view.image = UIImage(systemName: appearance.glyphs.direction(name), withConfiguration: config)
            view.tintColor = UIColor(appearance.arrowInk)
            let point = positions[name] ?? centre
            view.frame = CGRect(x: point.x - arrowSize / 2, y: point.y - arrowSize / 2, width: arrowSize, height: arrowSize)
        }
        CATransaction.commit()
    }

    static func cross(in rect: CGRect, arm: CGFloat, radius: CGFloat) -> UIBezierPath {
        let centre = CGPoint(x: rect.midX, y: rect.midY)
        let vertical = UIBezierPath(roundedRect: CGRect(x: centre.x - arm / 2, y: rect.minY, width: arm, height: rect.height), cornerRadius: radius)
        let horizontal = UIBezierPath(roundedRect: CGRect(x: rect.minX, y: centre.y - arm / 2, width: rect.width, height: arm), cornerRadius: radius)
        vertical.append(horizontal)
        vertical.usesEvenOddFillRule = false
        return vertical
    }
}
#endif
