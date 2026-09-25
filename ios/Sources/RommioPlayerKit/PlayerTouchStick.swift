import CoreGraphics
import Foundation
import RommioFoundation

// Touch sticks (docs/player-controls.md, "Touch sticks (not a copy of a physical stick)").
// The maths is pinned by scripts/touch_stick_reference.py: `output`, `gateScale`,
// `followCentre` and `fullTilt` are that script's `stick_output`, `gate_scale`,
// `follow_centre` and `full_tilt`, line for line, and PlayerTouchStickTests assert the
// vectors it prints. Units are points; y grows downward (screen and libretro agree).

/// One console's stick feel.
public struct PlayerStickProfile: Hashable, Sendable {
    public var name: String
    /// Thumb travel (pt) for full tilt, before saturation.
    public var travel: Double
    /// Share of the travel with no output.
    public var deadZone: Double
    /// Share of the travel after the dead zone that already reads as full tilt.
    public var saturation: Double
    /// Response exponent (above 1: finer near the centre).
    public var curve: Double
    /// Per-axis value at 45° full tilt: 0.7071 is a circle; higher, an octagon or square gate.
    public var diagonalReach: Double
    /// Drag the centre along when the thumb goes past full travel.
    public var follow: Bool

    public init(name: String, travel: Double, deadZone: Double, saturation: Double, curve: Double, diagonalReach: Double, follow: Bool) {
        self.name = name
        self.travel = travel
        self.deadZone = deadZone
        self.saturation = saturation
        self.curve = curve
        self.diagonalReach = diagonalReach
        self.follow = follow
    }

    public static let n64 = PlayerStickProfile(name: "n64", travel: 48, deadZone: 0.10, saturation: 0.90, curve: 1.4, diagonalReach: 0.86, follow: true)
    public static let threeDS = PlayerStickProfile(name: "3ds", travel: 44, deadZone: 0.10, saturation: 0.92, curve: 1.2, diagonalReach: 0.7071, follow: true)
    public static let psp = PlayerStickProfile(name: "psp", travel: 36, deadZone: 0.12, saturation: 0.90, curve: 1.0, diagonalReach: 0.7071, follow: true)
    public static let psx = PlayerStickProfile(name: "psx", travel: 44, deadZone: 0.10, saturation: 0.90, curve: 1.3, diagonalReach: 0.95, follow: true)
    public static let psxRight = PlayerStickProfile(name: "psx-right", travel: 40, deadZone: 0.12, saturation: 0.90, curve: 1.6, diagonalReach: 0.95, follow: true)
    /// In the reference script's order.
    public static let all: [PlayerStickProfile] = [n64, threeDS, psp, psx, psxRight]

    /// A family's stick (0 left, 1 right). Families without a profile of their own use
    /// the DualShock's.
    public static func forStick(family: String, stick: Int) -> PlayerStickProfile {
        switch family {
        case "n64": n64
        case "3ds": threeDS
        case "psp": psp
        default: stick == 1 ? psxRight : psx
        }
    }

    /// The profile with a game's tuning and the control size: travel × Sensitivity ×
    /// control size, dead zone × its factor, and no follow with a fixed centre.
    public func tuned(_ tuning: PlayerStickTuning, scale: Double) -> PlayerStickProfile {
        var tuned = self
        tuned.travel = travel * tuning.sensitivity.travelFactor * scale
        tuned.deadZone = deadZone * tuning.deadZone.factor
        tuned.follow = follow && tuning.centre == .floating
        return tuned
    }
}

public enum PlayerStickModel {
    /// How far full tilt reaches along `angle` for a gate whose 45° corner is at
    /// (diagonalReach, diagonalReach): 1 at the cardinals, straight edges between. A reach
    /// within 1e-4 of √½ (the profiles' 0.7071) is a true circle (1 everywhere).
    public static func gateScale(angle: Double, diagonalReach: Double) -> Double {
        if abs(diagonalReach - 0.5.squareRoot()) < 1e-4 { return 1 }
        var folded = abs(angle).truncatingRemainder(dividingBy: .pi / 2)
        if folded > .pi / 4 { folded = .pi / 2 - folded }
        return 1 / (cos(folded) + sin(folded) * (1 - diagonalReach) / diagonalReach)
    }

    /// The thumb's offset from the stick's centre (pt) → (x, y) in -1…1 for the core.
    public static func output(dx: Double, dy: Double, _ p: PlayerStickProfile) -> (x: Double, y: Double) {
        let distance = hypot(dx, dy)
        if distance == 0 { return (0, 0) }
        var m = min(distance / p.travel, 1)
        if m <= p.deadZone { return (0, 0) }
        m = (m - p.deadZone) / (1 - p.deadZone)
        m = min(m / p.saturation, 1)
        m = pow(m, p.curve)
        let angle = atan2(dy, dx)
        let reach = m * gateScale(angle: angle, diagonalReach: p.diagonalReach)
        return (max(-1, min(1, cos(angle) * reach)), max(-1, min(1, sin(angle) * reach)))
    }

    /// With `follow`, a thumb past full travel drags the centre so it sits at the rim.
    public static func followCentre(cx: Double, cy: Double, tx: Double, ty: Double, _ p: PlayerStickProfile) -> (x: Double, y: Double) {
        let dx = tx - cx, dy = ty - cy
        let distance = hypot(dx, dy)
        if !p.follow || distance <= p.travel { return (cx, cy) }
        let pull = 1 - p.travel / distance
        return (cx + dx * pull, cy + dy * pull)
    }

    /// Full tilt (where the soft haptic tick fires): the thumb has entered saturation.
    public static func fullTilt(dx: Double, dy: Double, _ p: PlayerStickProfile) -> Bool {
        let m = min(hypot(dx, dy) / p.travel, 1)
        if m <= p.deadZone { return false }
        return (m - p.deadZone) / (1 - p.deadZone) >= p.saturation
    }

    /// The centre for a touch-down at `point` (floating): exactly where the thumb landed.
    /// The zone only decides which touches start the stick; clamping the centre inward made a
    /// touch near the zone's edge read as instant tilt. The ring may draw past the zone.
    public static func center(for point: CGPoint, zone: CGRect, travel: CGFloat) -> CGPoint {
        _ = zone; _ = travel
        return point
    }
}

/// Where a held stick is drawn: the ring at its centre, the knob at the output position,
/// and whether it is at full tilt (the ring brightens).
public struct PlayerStickPosition: Hashable, Sendable {
    public var center: CGPoint
    public var knob: CGPoint
    public var fullTilt: Bool
}
