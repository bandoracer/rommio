import CoreGraphics
import Foundation
import RommioFoundation

// Touch controls v2 (docs/player-controls.md): what each family's controls are, how big
// they are at a control size, and where they go on a stage. Pure: the renderer, the
// touch surface and the tests all place controls with these functions.

/// What a touch control is, for the hit test and for drawing.
public enum PlayerTouchKind: String, Hashable, Sendable {
    case dpad, face, shoulder, system, stick

    /// A touch that began on one of these follows the thumb to others of the same kind.
    public var rolls: Bool { self == .face || self == .shoulder || self == .system }
}

/// How a family's face buttons are arranged (docs/player-controls.md, Geometry).
public enum PlayerFaceArrangement: String, Hashable, Sendable {
    /// Two circles on a 25° rise, B lower-left and A upper-right (NES, Game Boy…).
    case twoButton
    /// Four circles around a centre (SNES, PlayStation…).
    case diamond
    /// Two rows of three (Genesis six-button).
    case sixButton
    /// Nintendo 64: A with B down-left, the C buttons as a small diamond above-right of A.
    case n64
    /// The family's own arrangement, scaled to the size floor.
    case custom
    case none
}

/// The drawn outline of a control.
public enum PlayerTouchShape: String, Hashable, Sendable {
    case circle, capsule, tab
    /// An analog stick's square zone: touches anywhere in it take the stick.
    case zone
}

/// One control of a family, before placement.
public struct PlayerControlSpec: Hashable, Sendable, Identifiable {
    public let id: String
    public let label: String
    public let kind: PlayerTouchKind
    /// Digital keys it holds (one, or a combo); empty for sticks and axis buttons.
    public let keys: [Int]
    public let stick: Int?
    /// Axis buttons (N64 C buttons) push the right stick by this much.
    public let axisX: Double
    public let axisY: Double
    /// On the d-pad's rail (left unless mirrored); face buttons are on the other.
    public let left: Bool
    /// The family's own rail coordinates (docs/player-controls.json), for arrangement.
    let railX: CGFloat
    let railY: CGFloat
    let railSize: CGFloat

    public var isAxisButton: Bool { keys.isEmpty && stick == nil && (axisX != 0 || axisY != 0) }
}

/// A family's touch controls: the d-pad, the face cluster, shoulders, sticks and the
/// utility row (Select, Start…), classified from `ExpansionControls`, with each console's
/// layout (docs/player-controls.md, "Analog sticks and per-console layouts").
public struct PlayerControlPlan: Hashable, Sendable {
    /// Where the d-pad sits on its rail: in the thumb spot, or small above or below a
    /// primary stick (or beside it, toward the screen's centre, on a band too short for
    /// the stack: `compact()`).
    public enum DPadSlot: String, Hashable, Sendable { case primary, above, below, beside }

    public let family: String
    public let dpad: PlayerControlSpec?
    public private(set) var dpadSlot: DPadSlot
    public let face: [PlayerControlSpec]
    public let arrangement: PlayerFaceArrangement
    /// Left-rail shoulders, then right-rail shoulders, each in rail order (left to right).
    public let leftShoulders: [PlayerControlSpec]
    public let rightShoulders: [PlayerControlSpec]
    public let leftStick: PlayerControlSpec?
    public let rightStick: PlayerControlSpec?
    /// The left stick takes the thumb spot (N64, 3DS); otherwise sticks are secondary.
    public let leftStickPrimary: Bool
    /// Select, Start and the like, in order; Menu sits in the middle of them.
    public let utilities: [PlayerControlSpec]
    /// PlayStation's per-game Analog controls (sticks and L3/R3 shown).
    public let analogControls: Bool
    /// The game's touch-stick tuning (travel, dead zone, floating or fixed centre).
    public let stickTuning: PlayerStickTuning

    public init?(family: String, analogControls: Bool = false, stickTuning: PlayerStickTuning = .standard) {
        guard let controls = ExpansionControls.controls(for: family) else { return nil }
        self.init(family: family, controls: controls, analogControls: analogControls, stickTuning: stickTuning)
    }

    public init(family: String, controls: [ExpansionControl], analogControls: Bool = false, stickTuning: PlayerStickTuning = .standard) {
        self.family = family
        self.analogControls = analogControls
        self.stickTuning = stickTuning
        func spec(_ control: ExpansionControl, _ kind: PlayerTouchKind) -> PlayerControlSpec {
            PlayerControlSpec(id: control.id, label: control.label, kind: kind,
                              keys: control.combo.isEmpty ? (control.key.map { [$0] } ?? []) : control.combo,
                              stick: control.stick, axisX: control.axisX, axisY: control.axisY, left: control.left,
                              railX: CGFloat(control.x), railY: CGFloat(control.y), railSize: CGFloat(control.size))
        }
        // PlayStation without Analog controls: no sticks and no L3/R3 (the Analog mode
        // button stays).
        let hidesAnalog = family == "psx" && !analogControls
        let kept = controls.filter { control in
            !(hidesAnalog && (control.stick != nil || control.id == "L3" || control.id == "R3"))
        }
        let directions = kept.filter { !$0.center && [4, 5, 6, 7].contains($0.key ?? -1) }
        dpad = directions.isEmpty ? nil : PlayerControlSpec(
            id: "dpad", label: "D-pad", kind: .dpad, keys: [4, 5, 6, 7], stick: nil, axisX: 0, axisY: 0, left: true,
            railX: 74, railY: 122, railSize: 144)
        let rest = kept.filter { control in !directions.contains { $0.id == control.id } }
        utilities = rest.filter(\.center).map { spec($0, .system) }
        var sticks = rest.filter { !$0.center && $0.stick != nil }.map { spec($0, .stick) }
        if family == "3ds" && !sticks.contains(where: { $0.left }) {
            // The circle pad: left analog to the core (docs/player-controls.md, per console).
            sticks.append(PlayerControlSpec(id: "circlePad", label: "Circle Pad", kind: .stick, keys: [], stick: 0, axisX: 0, axisY: 0,
                                            left: true, railX: 74, railY: 122, railSize: 160))
        }
        leftStick = sticks.first { $0.left }
        rightStick = sticks.first { !$0.left }
        switch family {
        case "n64":
            leftStickPrimary = leftStick != nil
            dpadSlot = leftStick == nil ? .primary : .above
        case "3ds":
            leftStickPrimary = leftStick != nil
            dpadSlot = leftStick == nil ? .primary : .below
        default:
            leftStickPrimary = false
            dpadSlot = .primary
        }
        // Shoulders are the rail's top row.
        let shoulders = rest.filter { !$0.center && $0.stick == nil && $0.y <= 30 }
        leftShoulders = shoulders.filter(\.left).sorted { $0.x < $1.x }.map { spec($0, .shoulder) }
        rightShoulders = shoulders.filter { !$0.left }.sorted { $0.x < $1.x }.map { spec($0, .shoulder) }
        face = rest.filter { control in !control.center && control.stick == nil && !shoulders.contains { $0.id == control.id } }
            .map { spec($0, .face) }
        arrangement = family == "n64" ? .n64 : Self.arrangement(face)
    }

    private static func arrangement(_ face: [PlayerControlSpec]) -> PlayerFaceArrangement {
        switch face.count {
        case 0: return .none
        case 2: return .twoButton
        case 4:
            // Four around one centre, one in each direction.
            let cx = face.map(\.railX).reduce(0, +) / 4, cy = face.map(\.railY).reduce(0, +) / 4
            let radii = face.map { hypot($0.railX - cx, $0.railY - cy) }
            return (radii.max()! - radii.min()!) < 2 ? .diamond : .custom
        case 6:
            let rows = Set(face.map { Int($0.railY.rounded()) })
            return rows.count == 2 ? .sixButton : .custom
        default: return .custom
        }
    }

    /// Every control, in a stable order (d-pad, face, shoulders, sticks, utilities).
    public var all: [PlayerControlSpec] {
        [dpad].compactMap { $0 } + face + leftShoulders + rightShoulders + [leftStick, rightStick].compactMap { $0 } + utilities
    }

    public var hasShoulders: Bool { !leftShoulders.isEmpty || !rightShoulders.isEmpty }
    public var hasSticks: Bool { leftStick != nil || rightStick != nil }
    /// A pair of secondary sticks lines up under the taller cluster.
    var stickPair: Bool { leftStick != nil && rightStick != nil && !leftStickPrimary }
    /// Start goes bottom centre beside Menu (Nintendo 64).
    var utilitiesBesideMenu: Bool { family == "n64" }

    /// The same controls with the small d-pad beside the primary stick rather than above
    /// or below it, for a band too short for the stack; nil when there is no such stack.
    public func compact() -> PlayerControlPlan? {
        guard leftStickPrimary, dpad != nil, dpadSlot == .above || dpadSlot == .below else { return nil }
        var copy = self
        copy.dpadSlot = .beside
        return copy
    }
}

// MARK: Sizes

/// Sizes at one control size (docs/player-controls.md, Geometry). The setting scales
/// everything between 0.8 and 1.3; no face button is drawn under 50 pt and no hit
/// region is smaller than 58 pt.
public struct PlayerControlMetrics: Hashable, Sendable {
    public static let scaleRange: ClosedRange<CGFloat> = 0.8 ... 1.3
    /// Hit regions are the drawn shape grown by this much.
    public static let hitGrowth: CGFloat = 14
    public static let minimumFace: CGFloat = 50
    public static let menuDiameter: CGFloat = 44

    public let scale: CGFloat
    /// A stage short of room may cap secondary stick zones (never below
    /// `minimumSecondaryZone`) before the controls shrink further.
    public var secondaryStickLimit: CGFloat = .infinity

    public init(scale requested: CGFloat) {
        scale = min(max(requested, Self.scaleRange.lowerBound), Self.scaleRange.upperBound)
    }

    public var dpad: CGFloat { 150 * scale }
    /// The d-pad beside a primary stick (N64, 3DS).
    public var smallDPad: CGFloat { 104 * scale }
    /// Two-button faces: 64 pt circles, centres 76 pt apart on a 25° rise.
    public var twoButtonDiameter: CGFloat { max(Self.minimumFace, 64 * scale) }
    public var twoButtonSpacing: CGFloat { 76 * max(scale, twoButtonDiameter / 64) }
    public static let twoButtonRise: CGFloat = 25 * .pi / 180
    /// Diamonds: 56 pt circles, centres 62 pt from the cluster centre.
    public var diamondDiameter: CGFloat { max(Self.minimumFace, 56 * scale) }
    public var diamondRadius: CGFloat { 62 * max(scale, diamondDiameter / 56) }
    /// Six-button grids: 50 pt circles.
    public var sixDiameter: CGFloat { max(Self.minimumFace, 50 * scale) }
    public var sixPitch: CGFloat { max(58 * scale, sixDiameter + 8) }
    /// Nintendo 64: A 64, B 56, the C buttons a 44 pt diamond (the one exception to the
    /// 50 pt face floor, per console; never under 44).
    public var n64A: CGFloat { max(Self.minimumFace, 64 * scale) }
    public var n64B: CGFloat { max(Self.minimumFace, 56 * scale) }
    public var n64C: CGFloat { max(44, 44 * scale) }
    /// A family's own arrangement (rail units of 48 pt buttons), grown to the floor.
    public var customFactor: CGFloat { max(1.1 * scale, Self.minimumFace / 48) }
    public var shoulder: CGSize { CGSize(width: 88 * scale, height: max(32, 40 * scale)) }
    /// Nintendo 64's Z trigger: a large tab.
    public var zTab: CGSize { CGSize(width: 92 * scale, height: max(40, 48 * scale)) }
    public var pill: CGSize { CGSize(width: 64 * scale, height: max(30, 34 * scale)) }
    public var menu: CGFloat { Self.menuDiameter }
    public var shoulderGap: CGFloat { 8 }
    /// Gap between the elements stacked on a rail (a stick under a cluster…).
    public var stackGap: CGFloat { 10 }
    /// Portrait: shoulders sit this far above their side's cluster.
    public var shoulderLift: CGFloat { 16 }

    /// An analog stick's square zone: 160 pt in the thumb spot, 120 pt as a secondary.
    public func stickZone(primary: Bool) -> CGFloat {
        primary ? 160 * scale : max(minimumSecondaryZone, min(120 * scale, secondaryStickLimit))
    }
    /// The smallest a capped secondary zone gets: a PSP nub's travel ring (36 pt) and
    /// 10 pt of play each way, and never under three quarters of its size.
    public var minimumSecondaryZone: CGFloat { max(2 * 36 * scale + 20, 90 * scale) }
}

extension PlayerControlPlan {
    /// Face buttons as (spec, offset from the cluster centre, diameter).
    public func faceGeometry(_ metrics: PlayerControlMetrics) -> [(spec: PlayerControlSpec, offset: CGPoint, diameter: CGFloat)] {
        switch arrangement {
        case .none: return []
        case .twoButton:
            // The lower one (on the console) goes lower-left.
            let sorted = face.sorted { $0.railY > $1.railY }
            let dx = metrics.twoButtonSpacing * cos(PlayerControlMetrics.twoButtonRise) / 2
            let dy = metrics.twoButtonSpacing * sin(PlayerControlMetrics.twoButtonRise) / 2
            let d = metrics.twoButtonDiameter
            return [(sorted[0], CGPoint(x: -dx, y: dy), d), (sorted[1], CGPoint(x: dx, y: -dy), d)]
        case .diamond:
            let cx = face.map(\.railX).reduce(0, +) / 4, cy = face.map(\.railY).reduce(0, +) / 4
            return face.map { spec in
                let vx = spec.railX - cx, vy = spec.railY - cy, length = max(hypot(vx, vy), 0.001)
                return (spec, CGPoint(x: vx / length * metrics.diamondRadius, y: vy / length * metrics.diamondRadius), metrics.diamondDiameter)
            }
        case .sixButton:
            let xs = Array(Set(face.map { Int($0.railX.rounded()) })).sorted()
            let ys = Array(Set(face.map { Int($0.railY.rounded()) })).sorted()
            return face.map { spec in
                let column = CGFloat(xs.firstIndex(of: Int(spec.railX.rounded())) ?? 0) - CGFloat(xs.count - 1) / 2
                let row = CGFloat(ys.firstIndex(of: Int(spec.railY.rounded())) ?? 0) - CGFloat(ys.count - 1) / 2
                return (spec, CGPoint(x: column * metrics.sixPitch, y: row * metrics.sixPitch), metrics.sixDiameter)
            }
        case .n64:
            // A at the origin, B down-left on a 25° line, the C diamond above-right of A;
            // then everything centred on the cluster's box.
            let a = metrics.n64A, b = metrics.n64B, c = metrics.n64C
            let unit = max(metrics.scale, a / 64)
            let bOffset = CGPoint(x: -70 * unit * cos(PlayerControlMetrics.twoButtonRise), y: 70 * unit * sin(PlayerControlMetrics.twoButtonRise))
            // C-Down sits just above-right of A, clear of it; the diamond around it, its
            // buttons' boxes clear of each other.
            let cRadius = c * 1.05
            let cCentre = CGPoint(x: a / 2 + 6 * unit, y: -(a / 2 + c / 2 + 4 * unit) - cRadius)
            var raw: [(PlayerControlSpec, CGPoint, CGFloat)] = []
            for spec in face {
                switch spec.id {
                case "A": raw.append((spec, .zero, a))
                case "B": raw.append((spec, bOffset, b))
                default:
                    let direction: CGPoint = switch spec.id {
                    case "C-Up": CGPoint(x: 0, y: -1)
                    case "C-Down": CGPoint(x: 0, y: 1)
                    case "C-Left": CGPoint(x: -1, y: 0)
                    default: CGPoint(x: 1, y: 0)
                    }
                    raw.append((spec, CGPoint(x: cCentre.x + direction.x * cRadius, y: cCentre.y + direction.y * cRadius), c))
                }
            }
            let minX = raw.map { $0.1.x - $0.2 / 2 }.min() ?? 0, maxX = raw.map { $0.1.x + $0.2 / 2 }.max() ?? 0
            let minY = raw.map { $0.1.y - $0.2 / 2 }.min() ?? 0, maxY = raw.map { $0.1.y + $0.2 / 2 }.max() ?? 0
            let mid = CGPoint(x: (minX + maxX) / 2, y: (minY + maxY) / 2)
            return raw.map { ($0.0, CGPoint(x: $0.1.x - mid.x, y: $0.1.y - mid.y), $0.2) }
        case .custom:
            let minX = face.map(\.railX).min()!, maxX = face.map(\.railX).max()!
            let minY = face.map(\.railY).min()!, maxY = face.map(\.railY).max()!
            let k = metrics.customFactor
            return face.map { spec in
                (spec, CGPoint(x: (spec.railX - (minX + maxX) / 2) * k, y: (spec.railY - (minY + maxY) / 2) * k),
                 max(PlayerControlMetrics.minimumFace, spec.railSize * k))
            }
        }
    }

    /// The face cluster's drawn bounding box, centred on the origin.
    public func faceSize(_ metrics: PlayerControlMetrics) -> CGSize {
        let geometry = faceGeometry(metrics)
        guard !geometry.isEmpty else { return .zero }
        let xs = geometry.flatMap { [$0.offset.x - $0.diameter / 2, $0.offset.x + $0.diameter / 2] }
        let ys = geometry.flatMap { [$0.offset.y - $0.diameter / 2, $0.offset.y + $0.diameter / 2] }
        return CGSize(width: 2 * max(abs(xs.min()!), abs(xs.max()!)), height: 2 * max(abs(ys.min()!), abs(ys.max()!)))
    }

    /// A shoulder's nominal size (Nintendo 64's Z is a large tab; its L, rarely used and
    /// at the rail's outer corner beside Z, a narrow 64 pt one).
    func shoulderSize(_ spec: PlayerControlSpec, _ metrics: PlayerControlMetrics) -> CGSize {
        guard family == "n64" else { return metrics.shoulder }
        switch spec.id {
        case "Z": return metrics.zTab
        case "L": return CGSize(width: 64 * metrics.scale, height: metrics.shoulder.height)
        default: return metrics.shoulder
        }
    }

    /// One side's shoulder row: widths (narrower to fit `available`) and height. A
    /// large tab (Nintendo 64's Z) keeps its width while the others give way to 56 pt.
    func shoulderRow(_ specs: [PlayerControlSpec], available: CGFloat, _ metrics: PlayerControlMetrics) -> (widths: [CGFloat], height: CGFloat) {
        guard !specs.isEmpty else { return ([], 0) }
        let sizes = specs.map { shoulderSize($0, metrics) }
        let room = max(0, available - CGFloat(specs.count - 1) * metrics.shoulderGap)
        var widths = sizes.map(\.width)
        let large = specs.map { sizes.count > 1 && shoulderSize($0, metrics) == metrics.zTab }
        let largeWidth = zip(widths, large).filter(\.1).map(\.0).reduce(0, +)
        let others = CGFloat(large.filter { !$0 }.count)
        if widths.reduce(0, +) > room, largeWidth > 0, others > 0 {
            let each = max(min(56, metrics.shoulder.width), (room - largeWidth) / others)
            widths = zip(widths, large).map { $0.1 ? $0.0 : min($0.0, each) }
        }
        let total = widths.reduce(0, +)
        if total > room { widths = widths.map { $0 * room / max(total, 1) } }
        return (widths, sizes.map(\.height).max() ?? 0)
    }

    /// What one rail holds, as a vertical stack: shoulders, then (optionally) a secondary
    /// control above the primary, the primary in the thumb spot, and a secondary below.
    public struct RailContent: Hashable, Sendable {
        /// The primary control's drawn size (d-pad disc, stick zone or face box).
        public var cluster: CGSize
        public var above: CGSize?
        public var below: CGSize?
        /// A secondary beside the primary, toward the screen's centre (10 pt apart).
        public var beside: CGSize?
        /// The shoulder row at its natural width, and its height.
        public var shoulderRow: CGSize

        /// The primary control with anything beside it.
        public var block: CGFloat { cluster.width + (beside.map { $0.width + 10 } ?? 0) }
        /// The stack's width (a shoulder row narrows to fit it).
        public var width: CGFloat { max(block, above?.width ?? 0, below?.width ?? 0) }
        /// Height above the primary control's top: a secondary above it and its gap.
        public func aboveHeight(_ metrics: PlayerControlMetrics) -> CGFloat { above.map { $0.height + metrics.stackGap } ?? 0 }
        /// Height under the primary control's bottom: a secondary below it and its gap.
        public func belowHeight(_ metrics: PlayerControlMetrics) -> CGFloat { below.map { $0.height + metrics.stackGap } ?? 0 }
    }

    /// The rail holding the d-pad (`dpadSide`) or the face cluster.
    public func rail(dpadSide: Bool, _ metrics: PlayerControlMetrics) -> RailContent {
        func row(_ specs: [PlayerControlSpec]) -> CGSize {
            guard !specs.isEmpty else { return .zero }
            let sizes = specs.map { shoulderSize($0, metrics) }
            return CGSize(width: sizes.map(\.width).reduce(0, +) + CGFloat(specs.count - 1) * metrics.shoulderGap,
                          height: sizes.map(\.height).max() ?? 0)
        }
        if dpadSide {
            if leftStickPrimary {
                let zone = metrics.stickZone(primary: true)
                let pad = dpad == nil ? nil : CGSize(width: metrics.smallDPad, height: metrics.smallDPad)
                return RailContent(cluster: CGSize(width: zone, height: zone), above: dpadSlot == .above ? pad : nil,
                                   below: dpadSlot == .below ? pad : nil, beside: dpadSlot == .beside ? pad : nil,
                                   shoulderRow: row(leftShoulders))
            }
            let secondary = metrics.stickZone(primary: false)
            return RailContent(cluster: dpad == nil ? .zero : CGSize(width: metrics.dpad, height: metrics.dpad), above: nil,
                               below: leftStick == nil ? nil : CGSize(width: secondary, height: secondary), shoulderRow: row(leftShoulders))
        }
        let secondary = metrics.stickZone(primary: false)
        return RailContent(cluster: faceSize(metrics), above: nil,
                           below: rightStick == nil ? nil : CGSize(width: secondary, height: secondary), shoulderRow: row(rightShoulders))
    }
}

// MARK: Placed controls

/// A control where it is drawn, in window points.
public struct PlacedPlayerControl: Hashable, Sendable, Identifiable {
    public let spec: PlayerControlSpec
    public let frame: CGRect
    public let shape: PlayerTouchShape
    /// Sticks: the console profile with the game's tuning and the control size.
    public var stickProfile: PlayerStickProfile? = nil
    /// Sticks: the centre stays at the zone's centre (no floating, no follow).
    public var fixedCentre = false
    /// Sticks: thumb travel for full tilt (points).
    public var travel: CGFloat { CGFloat(stickProfile?.travel ?? 0) }
    public var id: String { spec.id }
    public var kind: PlayerTouchKind { spec.kind }
    public var center: CGPoint { CGPoint(x: frame.midX, y: frame.midY) }
    /// The drawn shape grown by `PlayerControlMetrics.hitGrowth` (a stick's zone is its own).
    public var hitFrame: CGRect {
        shape == .zone ? frame : frame.insetBy(dx: -PlayerControlMetrics.hitGrowth, dy: -PlayerControlMetrics.hitGrowth)
    }
}

/// Every touch control of a stage, the Menu button and the title strip.
public struct PlayerTouchLayout: Hashable, Sendable {
    public var controls: [PlacedPlayerControl]
    /// The face cluster's zone: its buttons' box grown by the hit growth. Inside it the
    /// nearest button takes a touch.
    public var faceZone: CGRect?
    /// The two buttons of a two-button face (a touch between them presses both).
    public var chord: [String]?
    public var menuCenter: CGPoint
    public var menuDiameter: CGFloat
    /// Portrait on a phone: the game's name and play line under the game.
    public var titleStrip: CGRect?
    public var metrics: PlayerControlMetrics
    /// The game's screens: touches that land on one go to the game (a DS or 3DS touch
    /// screen, pointer games), never to a control whose grown hit region reaches it.
    public var gameScreens: [CGRect]

    public init(controls: [PlacedPlayerControl], faceZone: CGRect?, chord: [String]?, menuCenter: CGPoint, menuDiameter: CGFloat,
                titleStrip: CGRect?, metrics: PlayerControlMetrics, gameScreens: [CGRect] = []) {
        self.controls = controls
        self.faceZone = faceZone
        self.chord = chord
        self.menuCenter = menuCenter
        self.menuDiameter = menuDiameter
        self.titleStrip = titleStrip
        self.metrics = metrics
        self.gameScreens = gameScreens
    }

    public var dpad: PlacedPlayerControl? { controls.first { $0.kind == .dpad } }
    public var menuFrame: CGRect {
        CGRect(x: menuCenter.x - menuDiameter / 2, y: menuCenter.y - menuDiameter / 2, width: menuDiameter, height: menuDiameter)
    }
    public func control(_ id: String) -> PlacedPlayerControl? { controls.first { $0.id == id } }
}

/// Where the rails and rows are, for `PlayerControlPlan.place`.
public struct PlayerControlAnchors: Hashable, Sendable {
    /// Horizontal extent of the rail on each side of the screen.
    public var leftRail: ClosedRange<CGFloat>
    public var rightRail: ClosedRange<CGFloat>
    /// Clusters hug the rail's outer edge (portrait) or centre in it (landscape).
    public var alignOuter: Bool
    /// Vertical centre of the primary control on each side.
    public var leftClusterY: CGFloat
    public var rightClusterY: CGFloat
    public var shoulders: Shoulders
    public var utility: Utility
    public var titleStrip: CGRect?

    public enum Shoulders: Hashable, Sendable {
        /// The top of the rails (landscape, rail stages).
        case top(CGFloat)
        /// A lift above the top of their side's stack, aligned to its outer edge (portrait).
        case aboveCluster(CGFloat)
    }

    public enum Utility: Hashable, Sendable {
        /// Select · Menu · Start in one centred row.
        case row(midX: CGFloat, y: CGFloat, maxWidth: CGFloat)
        /// Select and Start at the foot of the rails; Menu under the game or beside them.
        case feet(y: CGFloat, menu: MenuSpot)
    }

    public enum MenuSpot: Hashable, Sendable {
        case under(CGPoint)
        case leadingFoot
        case trailingFoot
    }
}

extension PlayerControlPlan {
    /// Places every control. `mirrored` (left-handed) swaps the rails, never labels.
    public func place(_ anchors: PlayerControlAnchors, metrics: PlayerControlMetrics, mirrored: Bool) -> PlayerTouchLayout {
        var placed: [PlacedPlayerControl] = []
        let dpadRail = mirrored ? anchors.rightRail : anchors.leftRail
        let faceRail = mirrored ? anchors.leftRail : anchors.rightRail
        let dpadY = mirrored ? anchors.rightClusterY : anchors.leftClusterY
        let faceY = mirrored ? anchors.leftClusterY : anchors.rightClusterY
        let dpadOnLeft = !mirrored
        let gap = metrics.stackGap

        func clusterX(_ rail: ClosedRange<CGFloat>, width: CGFloat, onLeft: Bool) -> CGFloat {
            guard anchors.alignOuter else { return (rail.lowerBound + rail.upperBound) / 2 }
            return onLeft ? rail.lowerBound + width / 2 : rail.upperBound - width / 2
        }
        func square(_ centre: CGPoint, _ side: CGFloat) -> CGRect {
            CGRect(x: centre.x - side / 2, y: centre.y - side / 2, width: side, height: side)
        }
        func stickControl(_ spec: PlayerControlSpec, _ frame: CGRect) -> PlacedPlayerControl {
            let profile = PlayerStickProfile.forStick(family: family, stick: spec.stick ?? 0).tuned(stickTuning, scale: Double(metrics.scale))
            return PlacedPlayerControl(spec: spec, frame: frame, shape: .zone, stickProfile: profile, fixedCentre: stickTuning.centre == .fixed)
        }

        let dpadContent = rail(dpadSide: true, metrics)
        let faceContent = rail(dpadSide: false, metrics)
        // A pair of secondary sticks lines up under the taller cluster.
        let tallest = max(dpadContent.cluster.height, faceContent.cluster.height)
        let pairY = max(dpadY, faceY)

        // The d-pad's rail: the primary control (d-pad, or the left stick) and its secondary.
        // With a secondary beside it, the pair is one block: the primary at its outer side.
        let dpadBlockX = clusterX(dpadRail, width: max(dpadContent.block, 1), onLeft: dpadOnLeft)
        let besideShift = (dpadContent.block - dpadContent.cluster.width) / 2
        let dpadPrimaryX = dpadBlockX + (dpadOnLeft ? -besideShift : besideShift)
        let primaryTop = dpadY - dpadContent.cluster.height / 2
        let primaryBottom = dpadY + dpadContent.cluster.height / 2
        if leftStickPrimary, let stick = leftStick {
            placed.append(stickControl(stick, square(CGPoint(x: dpadPrimaryX, y: dpadY), dpadContent.cluster.width)))
            if let dpad {
                let side = metrics.smallDPad
                let centre: CGPoint = switch dpadSlot {
                case .above: CGPoint(x: dpadPrimaryX, y: primaryTop - gap - side / 2)
                case .beside: CGPoint(x: dpadPrimaryX + (dpadContent.cluster.width / 2 + gap + side / 2) * (dpadOnLeft ? 1 : -1), y: dpadY)
                default: CGPoint(x: dpadPrimaryX, y: primaryBottom + gap + side / 2)
                }
                placed.append(PlacedPlayerControl(spec: dpad, frame: square(centre, side), shape: .circle))
            }
        } else {
            if let dpad {
                placed.append(PlacedPlayerControl(spec: dpad, frame: square(CGPoint(x: dpadPrimaryX, y: dpadY), metrics.dpad), shape: .circle))
            }
            if let stick = leftStick {
                let side = metrics.stickZone(primary: false)
                let top = stickPair ? pairY + tallest / 2 : primaryBottom
                placed.append(stickControl(stick, square(CGPoint(x: dpadPrimaryX, y: top + gap + side / 2), side)))
            }
        }

        // The face rail.
        var faceZone: CGRect?
        let facePrimaryX = clusterX(faceRail, width: max(faceContent.cluster.width, 1), onLeft: !dpadOnLeft)
        if !face.isEmpty {
            let centre = CGPoint(x: facePrimaryX, y: faceY)
            for (spec, offset, diameter) in faceGeometry(metrics) {
                let frame = CGRect(x: centre.x + offset.x - diameter / 2, y: centre.y + offset.y - diameter / 2, width: diameter, height: diameter)
                placed.append(PlacedPlayerControl(spec: spec, frame: frame, shape: .circle))
            }
            let union = placed.filter { $0.kind == .face }.reduce(CGRect.null) { $0.union($1.frame) }
            faceZone = union.insetBy(dx: -PlayerControlMetrics.hitGrowth, dy: -PlayerControlMetrics.hitGrowth)
        }
        if let stick = rightStick {
            let side = metrics.stickZone(primary: false)
            let top = stickPair ? pairY + tallest / 2 : faceY + faceContent.cluster.height / 2
            placed.append(stickControl(stick, square(CGPoint(x: facePrimaryX, y: top + gap + side / 2), side)))
        }

        // Shoulders: the top of each rail (landscape), or just above their side's stack,
        // aligned to its outer edge (portrait).
        func shoulders(_ specs: [PlayerControlSpec], rail: ClosedRange<CGFloat>, onLeft: Bool, content: RailContent, clusterY: CGFloat, stackX: CGFloat) {
            // (`stackX`: the centre of the primary control and anything beside it.)
            guard !specs.isEmpty else { return }
            let row = shoulderRow(specs, available: rail.upperBound - rail.lowerBound, metrics)
            let total = row.widths.reduce(0, +) + CGFloat(specs.count - 1) * metrics.shoulderGap
            let top: CGFloat
            let startX: CGFloat
            switch anchors.shoulders {
            case let .top(y):
                top = y
                startX = anchors.alignOuter ? (onLeft ? rail.lowerBound : rail.upperBound - total) : (rail.lowerBound + rail.upperBound) / 2 - total / 2
            case let .aboveCluster(lift):
                let stackTop = clusterY - content.cluster.height / 2 - content.aboveHeight(metrics)
                top = stackTop - lift - row.height
                let stackWidth = max(content.block, content.above?.width ?? 0)
                let aligned = onLeft ? stackX - stackWidth / 2 : stackX + stackWidth / 2 - total
                // A row wider than its stack stays inside the rail.
                startX = min(max(aligned, rail.lowerBound), max(rail.lowerBound, rail.upperBound - total))
            }
            var x = startX
            for (spec, width) in zip(specs, row.widths) {
                let height = shoulderSize(spec, metrics).height
                placed.append(PlacedPlayerControl(spec: spec, frame: CGRect(x: x, y: top + row.height - height, width: width, height: height), shape: .tab))
                x += width + metrics.shoulderGap
            }
        }
        shoulders(leftShoulders, rail: dpadRail, onLeft: dpadOnLeft, content: dpadContent, clusterY: dpadY, stackX: dpadBlockX)
        shoulders(rightShoulders, rail: faceRail, onLeft: !dpadOnLeft, content: faceContent, clusterY: faceY, stackX: facePrimaryX)

        // Select · Menu · Start.
        let pill = metrics.pill
        let pillGap: CGFloat = 10
        var menuCenter: CGPoint
        switch anchors.utility {
        case let .row(midX, y, maxWidth):
            let count = utilities.count
            let width = count == 0 ? pill.width : max(44, min(pill.width, (maxWidth - metrics.menu - CGFloat(count) * pillGap) / CGFloat(count)))
            let menuIndex = count / 2
            let items: [CGFloat] = (0 ... count).map { $0 == menuIndex ? metrics.menu : width }
            let total = items.reduce(0, +) + CGFloat(count) * pillGap
            var x = midX - total / 2
            menuCenter = CGPoint(x: midX, y: y)
            var utilityIndex = 0
            for (slot, itemWidth) in items.enumerated() {
                if slot == menuIndex {
                    menuCenter = CGPoint(x: x + itemWidth / 2, y: y)
                } else {
                    let spec = utilities[utilityIndex]
                    utilityIndex += 1
                    placed.append(PlacedPlayerControl(spec: spec, frame: CGRect(x: x, y: y - pill.height / 2, width: itemWidth, height: pill.height), shape: .capsule))
                }
                x += itemWidth + pillGap
            }
        case let .feet(y, menu):
            if case let .under(point) = menu, utilitiesBesideMenu, !utilities.isEmpty {
                // Nintendo 64: Menu and Start together, bottom centre.
                let total = metrics.menu + CGFloat(utilities.count) * (pill.width + pillGap)
                var x = point.x - total / 2
                menuCenter = CGPoint(x: x + metrics.menu / 2, y: point.y)
                x += metrics.menu + pillGap
                for spec in utilities {
                    placed.append(PlacedPlayerControl(spec: spec, frame: CGRect(x: x, y: point.y - pill.height / 2, width: pill.width, height: pill.height), shape: .capsule))
                    x += pill.width + pillGap
                }
                break
            }
            // Leading rail: the first half; trailing rail: the rest; Menu under the game
            // or beside them (leading first, innermost on the trailing rail).
            let leadingCount = (utilities.count + 1) / 2
            let leading = Array(utilities.prefix(leadingCount)), trailing = Array(utilities.dropFirst(leadingCount))
            menuCenter = .zero
            if case let .under(point) = menu { menuCenter = point }
            func row(_ specs: [PlayerControlSpec], rail: ClosedRange<CGFloat>, withMenu: Bool, menuFirst: Bool) {
                let railWidth = rail.upperBound - rail.lowerBound
                let menuWidth = withMenu ? metrics.menu + pillGap : 0
                guard !specs.isEmpty || withMenu else { return }
                let count = CGFloat(specs.count)
                let width = specs.isEmpty ? 0 : max(44, min(pill.width, (railWidth - menuWidth - (count - 1) * pillGap) / count))
                let total = menuWidth + (specs.isEmpty ? 0 : count * width + (count - 1) * pillGap)
                var x = (rail.lowerBound + rail.upperBound) / 2 - total / 2
                if withMenu && menuFirst {
                    menuCenter = CGPoint(x: x + metrics.menu / 2, y: y)
                    x += metrics.menu + pillGap
                }
                for spec in specs {
                    placed.append(PlacedPlayerControl(spec: spec, frame: CGRect(x: x, y: y - pill.height / 2, width: width, height: pill.height), shape: .capsule))
                    x += width + pillGap
                }
                if withMenu && !menuFirst {
                    menuCenter = CGPoint(x: x + metrics.menu / 2, y: y)
                }
            }
            row(leading, rail: anchors.leftRail, withMenu: menu == .leadingFoot, menuFirst: false)
            row(trailing, rail: anchors.rightRail, withMenu: menu == .trailingFoot, menuFirst: true)
        }

        let chord = arrangement == .twoButton ? face.map(\.id) : nil
        return PlayerTouchLayout(controls: placed, faceZone: faceZone, chord: chord, menuCenter: menuCenter,
                                 menuDiameter: metrics.menu, titleStrip: anchors.titleStrip, metrics: metrics)
    }
}
