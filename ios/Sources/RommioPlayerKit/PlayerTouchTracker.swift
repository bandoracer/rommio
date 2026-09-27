import CoreGraphics
import Foundation

// The touch model of docs/player-controls.md, pure: hit regions, the chord band,
// rolling with hysteresis, the d-pad's dead zone and 8-way sectors, and a tracker that
// turns touches into ordered presses and releases. The UIKit surface only forwards
// touches and performs the output.

/// A d-pad direction as the keys it holds (4 up, 5 down, 6 left, 7 right).
public enum PlayerDPadDirection: Int, CaseIterable, Hashable, Sendable {
    case none, right, downRight, down, downLeft, left, upLeft, up, upRight

    public var keys: Set<Int> {
        switch self {
        case .none: []
        case .right: [7]
        case .downRight: [5, 7]
        case .down: [5]
        case .downLeft: [5, 6]
        case .left: [6]
        case .upLeft: [4, 6]
        case .up: [4]
        case .upRight: [4, 7]
        }
    }

    /// The sector's centre in degrees (0 = right, 90 = down: screen coordinates).
    var angle: Double { Double(rawValue - 1) * 45 }
    var isDiagonal: Bool { rawValue % 2 == 0 && self != .none }
}

public enum PlayerTouchRules {
    /// A two-button chord: within this share of the centre distance of their midpoint.
    public static let chordBand: CGFloat = 0.22
    /// Leaving the chord needs a little more than entering it.
    public static let chordHysteresis: CGFloat = 4
    /// A touch that drifts off every control keeps its control until this far beyond it.
    public static let releaseDistance: CGFloat = 40
    /// Rolling keeps the current control until another is clearly nearer (normalised distance).
    public static let rollHysteresis: CGFloat = 0.12
    /// D-pad dead zone as a share of the pad's radius (smaller once a direction is held).
    public static let deadZone: CGFloat = 0.18
    public static let heldDeadZone: CGFloat = 0.15
    /// Diagonal sectors are this wide (degrees); cardinal ones take the rest (60°). Narrowed
    /// from 40° (revised 2026-09-26): a thumb resting on an arm rolled into a diagonal.
    public static let diagonalSector: Double = 30
    /// A held direction keeps its sector this much wider (degrees).
    public static let sectorHysteresis: Double = 4

    /// The d-pad direction for a touch at (dx, dy) from the pad's centre. A four-way pad
    /// (`diagonals` false: a small secondary pad) splits the circle into its four arms.
    public static func dpadDirection(dx: CGFloat, dy: CGFloat, radius: CGFloat, previous: PlayerDPadDirection = .none,
                                     diagonals: Bool = true) -> PlayerDPadDirection {
        let distance = hypot(dx, dy)
        let dead = (previous == .none ? deadZone : heldDeadZone) * max(radius, 1)
        guard distance >= dead else { return .none }
        var angle = atan2(Double(dy), Double(dx)) * 180 / .pi
        if angle < 0 { angle += 360 }
        func delta(_ a: Double, _ b: Double) -> Double {
            let d = abs(a - b).truncatingRemainder(dividingBy: 360)
            return min(d, 360 - d)
        }
        func halfWidth(_ direction: PlayerDPadDirection) -> Double {
            guard diagonals else { return 45 }
            return direction.isDiagonal ? diagonalSector / 2 : (90 - diagonalSector) / 2
        }
        let candidates = PlayerDPadDirection.allCases.dropFirst().filter { diagonals || !$0.isDiagonal }
        if candidates.contains(previous), delta(angle, previous.angle) <= halfWidth(previous) + sectorHysteresis { return previous }
        return candidates.first { delta(angle, $0.angle) <= halfWidth($0) } ?? .none
    }

    /// How far a point is into a control: 0 at its centre, 1 at its drawn edge.
    public static func normalizedDistance(_ point: CGPoint, to control: PlacedPlayerControl) -> CGFloat {
        let dx = point.x - control.center.x, dy = point.y - control.center.y
        let rx = max(control.frame.width / 2, 1), ry = max(control.frame.height / 2, 1)
        return control.shape == .circle ? hypot(dx, dy) / rx : hypot(dx / rx, dy / ry)
    }

    /// The point is inside the control's hit region (its shape grown by the hit growth).
    public static func hits(_ point: CGPoint, _ control: PlacedPlayerControl) -> Bool {
        let grow = PlayerControlMetrics.hitGrowth
        if control.shape == .circle {
            return hypot(point.x - control.center.x, point.y - control.center.y) <= control.frame.width / 2 + grow
        }
        return control.hitFrame.contains(point)
    }

    /// Distance from the point to the control's hit region (0 inside).
    public static func distanceBeyond(_ point: CGPoint, _ control: PlacedPlayerControl) -> CGFloat {
        let grow = PlayerControlMetrics.hitGrowth
        if control.shape == .circle {
            return max(0, hypot(point.x - control.center.x, point.y - control.center.y) - control.frame.width / 2 - grow)
        }
        let box = control.hitFrame
        let dx = max(box.minX - point.x, 0, point.x - box.maxX), dy = max(box.minY - point.y, 0, point.y - box.maxY)
        return hypot(dx, dy)
    }
}

/// What a touch lands on.
public enum PlayerTouchTarget: Hashable, Sendable {
    case dpad
    /// One control, or both buttons of a two-button chord.
    case buttons([String])
    case stick(String)
}

extension PlayerTouchLayout {
    /// The control a new touch at `point` goes to, or nil (the touch falls through).
    public func target(at point: CGPoint) -> PlayerTouchTarget? {
        // A touch on a game screen goes to the game.
        guard !gameScreens.contains(where: { $0.contains(point) }) else { return nil }
        var candidates = controls.filter { PlayerTouchRules.hits(point, $0) }
        // Inside the face cluster's zone there are no dead gaps: the nearest button takes it.
        if let faceZone, faceZone.contains(point),
           let nearest = controls.filter({ $0.kind == .face }).min(by: { PlayerTouchRules.normalizedDistance(point, to: $0) < PlayerTouchRules.normalizedDistance(point, to: $1) }),
           !candidates.contains(nearest) {
            candidates.append(nearest)
        }
        guard let best = candidates.min(by: { PlayerTouchRules.normalizedDistance(point, to: $0) < PlayerTouchRules.normalizedDistance(point, to: $1) }) else { return nil }
        switch best.kind {
        case .dpad: return .dpad
        case .stick: return .stick(best.id)
        case .face:
            if let chord = chordPair, inChord(point, chord, holding: false) { return .buttons(chord.map(\.id)) }
            return .buttons([best.id])
        case .shoulder, .system: return .buttons([best.id])
        }
    }

    /// Where a rolling touch of `kind` goes as it moves: the same, another control of
    /// its kind, the chord, or nil when it is off every control of that kind.
    public func rollTarget(at point: CGPoint, kind: PlayerTouchKind, current: [String]) -> [String]? {
        if kind == .face, let chord = chordPair, inChord(point, chord, holding: current.count == 2) {
            return chord.map(\.id)
        }
        let ofKind = controls.filter { $0.kind == kind }
        var candidates = ofKind.filter { PlayerTouchRules.hits(point, $0) }
        // Inside the face cluster's zone every button is in reach: the nearest wins,
        // with hysteresis for the one held.
        if kind == .face, let faceZone, faceZone.contains(point) {
            candidates = ofKind
        }
        func distance(_ control: PlacedPlayerControl) -> CGFloat { PlayerTouchRules.normalizedDistance(point, to: control) }
        guard let best = candidates.min(by: { distance($0) < distance($1) }) else { return nil }
        // Hysteresis: keep the current control until another is clearly nearer.
        if current.count == 1, let held = candidates.first(where: { $0.id == current[0] }),
           distance(held) <= distance(best) + PlayerTouchRules.rollHysteresis {
            return current
        }
        return [best.id]
    }

    /// The touch is further than the release distance beyond every control it holds.
    public func driftedAway(_ point: CGPoint, from ids: [String]) -> Bool {
        let held = controls.filter { ids.contains($0.id) }
        guard !held.isEmpty else { return true }
        return held.allSatisfy { PlayerTouchRules.distanceBeyond(point, $0) > PlayerTouchRules.releaseDistance }
    }

    var chordPair: [PlacedPlayerControl]? {
        guard let chord, chord.count == 2 else { return nil }
        let pair = chord.compactMap { control($0) }
        return pair.count == 2 ? pair : nil
    }

    func inChord(_ point: CGPoint, _ pair: [PlacedPlayerControl], holding: Bool) -> Bool {
        let a = pair[0].center, b = pair[1].center
        let mid = CGPoint(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2)
        let band = PlayerTouchRules.chordBand * hypot(a.x - b.x, a.y - b.y) + (holding ? PlayerTouchRules.chordHysteresis : 0)
        return hypot(point.x - mid.x, point.y - mid.y) <= band
    }
}

/// What the tracker asks the player to do, in order.
public enum PlayerTouchOutput: Hashable, Sendable {
    case digital(key: Int, pressed: Bool)
    case motion(stick: Int, x: Double, y: Double)
    /// A button was pressed (a light impact).
    case pressHaptic
    /// The d-pad changed to a new direction (a selection tick).
    case directionHaptic
    /// A stick entered full tilt between notches, or on a round gate (one soft tick).
    case stickHaptic
    /// A stick at full tilt settled into a notch of its gate (the octagon's crisp click, the
    /// square's softer corner bump).
    case stickNotch(PlayerStickGate)
}

/// Turns touches into ordered input. Several touches may hold controls at once; a key
/// held by two touches is released when the last lets go.
public struct PlayerTouchTracker: Sendable {
    public private(set) var layout: PlayerTouchLayout

    private enum State: Hashable, Sendable {
        case dpad(PlayerDPadDirection)
        /// Holding these controls of a rolling kind (empty after drifting off).
        case buttons(PlayerTouchKind, [String])
        /// A stick, its centre (set by the touch-down), whether it is at full tilt, and the
        /// gate notch it rests in.
        case stick(String, center: CGPoint, fullTilt: Bool, notch: Int?)
    }

    private var touches: [Int: State] = [:]
    /// Order of touches, so releasing everything is deterministic.
    private var order: [Int] = []
    private var keyHolds: [Int: Int] = [:]
    private var axisHolds: [String: Int] = [:]
    private var controlHolds: [String: Int] = [:]
    private var directions: [Int: PlayerDPadDirection] = [:]
    /// Held sticks, for drawing (ring and knob at the floating centre).
    public private(set) var stickPositions: [String: PlayerStickPosition] = [:]
    private var stickValues: [Int: CGVector] = [:]

    public init(layout: PlayerTouchLayout) {
        self.layout = layout
    }

    /// Controls drawn pressed, and the d-pad's held keys (for drawing).
    public var pressedControls: Set<String> { Set(controlHolds.filter { $0.value > 0 }.keys) }
    public var dpadKeys: Set<Int> { directions.values.reduce(into: Set<Int>()) { $0.formUnion($1.keys) } }
    public var isTracking: Bool { !touches.isEmpty }

    /// A new layout (rotation, size): everything held is released first.
    public mutating func update(layout: PlayerTouchLayout) -> [PlayerTouchOutput] {
        guard layout != self.layout else { return [] }
        let output = releaseAll()
        self.layout = layout
        return output
    }

    public mutating func began(_ touch: Int, at point: CGPoint) -> [PlayerTouchOutput] {
        guard touches[touch] == nil, let target = layout.target(at: point) else { return [] }
        order.append(touch)
        switch target {
        case .dpad:
            touches[touch] = .dpad(.none)
            return moveDPad(touch, to: point)
        case let .buttons(ids):
            let kind = layout.control(ids[0])?.kind ?? .face
            touches[touch] = .buttons(kind, ids)
            return press(ids, haptic: true)
        case let .stick(id):
            // A stick already held by another touch stays with it.
            guard stickPositions[id] == nil, let control = layout.control(id) else {
                order.removeAll { $0 == touch }
                return []
            }
            // Floating: the touch-down is the centre; fixed: the zone's centre.
            let center = control.fixedCentre ? control.center : PlayerStickModel.center(for: point, zone: control.frame, travel: control.travel)
            return stickMotion(touch, id, center: center, fullTilt: false, notch: nil, at: point)
        }
    }

    public mutating func moved(_ touch: Int, to point: CGPoint) -> [PlayerTouchOutput] {
        guard let state = touches[touch] else { return [] }
        switch state {
        case .dpad:
            return moveDPad(touch, to: point)
        case let .stick(id, center, fullTilt, notch):
            return stickMotion(touch, id, center: center, fullTilt: fullTilt, notch: notch, at: point)
        case let .buttons(kind, held):
            if let next = layout.rollTarget(at: point, kind: kind, current: held) {
                guard next != held else { return [] }
                touches[touch] = .buttons(kind, next)
                let released = held.filter { !next.contains($0) }, pressed = next.filter { !held.contains($0) }
                return release(released) + press(pressed, haptic: true)
            }
            // Off every control of its kind: hold until well beyond, then let go.
            guard !held.isEmpty, layout.driftedAway(point, from: held) else { return [] }
            touches[touch] = .buttons(kind, [])
            return release(held)
        }
    }

    public mutating func ended(_ touch: Int) -> [PlayerTouchOutput] {
        guard let state = touches.removeValue(forKey: touch) else { return [] }
        order.removeAll { $0 == touch }
        switch state {
        case .dpad:
            let previous = directions.removeValue(forKey: touch) ?? .none
            return keysChanged(from: previous.keys, to: [], touch: touch)
        case let .buttons(_, held):
            return release(held)
        case let .stick(id, _, _, _):
            // The missing spring: zero at once.
            controlHolds[id] = nil
            stickPositions[id] = nil
            guard let stick = layout.control(id)?.spec.stick else { return [] }
            stickValues[stick] = nil
            return [.motion(stick: stick, x: 0, y: 0)]
        }
    }

    /// Releases every held input (touches cancelled, pause, background, overlay gone).
    public mutating func releaseAll() -> [PlayerTouchOutput] {
        var output: [PlayerTouchOutput] = []
        for touch in order { output += ended(touch) }
        touches.removeAll()
        order.removeAll()
        return output
    }

    // MARK: Input

    private mutating func moveDPad(_ touch: Int, to point: CGPoint) -> [PlayerTouchOutput] {
        guard let pad = layout.dpad else { return [] }
        let previous = directions[touch] ?? .none
        let next = PlayerTouchRules.dpadDirection(dx: point.x - pad.center.x, dy: point.y - pad.center.y,
                                                  radius: pad.frame.width / 2, previous: previous, diagonals: !pad.fourWay)
        guard next != previous else { return [] }
        directions[touch] = next
        touches[touch] = .dpad(next)
        var output = keysChanged(from: previous.keys, to: next.keys, touch: touch)
        if next != .none { output.append(.directionHaptic) }
        return output
    }

    private mutating func keysChanged(from old: Set<Int>, to new: Set<Int>, touch: Int) -> [PlayerTouchOutput] {
        var output: [PlayerTouchOutput] = []
        for key in old.subtracting(new).sorted() { output += releaseKey(key) }
        for key in new.subtracting(old).sorted() { output += pressKey(key) }
        return output
    }

    private mutating func press(_ ids: [String], haptic: Bool) -> [PlayerTouchOutput] {
        var output: [PlayerTouchOutput] = []
        var axisChanged = false
        for id in ids {
            guard let control = layout.control(id) else { continue }
            controlHolds[id, default: 0] += 1
            if control.spec.isAxisButton {
                axisHolds[id, default: 0] += 1
                axisChanged = true
            }
            for key in control.spec.keys { output += pressKey(key) }
        }
        if axisChanged { output.append(axisMotion()) }
        if haptic && !ids.isEmpty { output.append(.pressHaptic) }
        return output
    }

    private mutating func release(_ ids: [String]) -> [PlayerTouchOutput] {
        var output: [PlayerTouchOutput] = []
        var axisChanged = false
        for id in ids {
            guard let control = layout.control(id) else { continue }
            controlHolds[id, default: 1] -= 1
            if controlHolds[id] ?? 0 <= 0 { controlHolds[id] = nil }
            if control.spec.isAxisButton {
                axisHolds[id, default: 1] -= 1
                if axisHolds[id] ?? 0 <= 0 { axisHolds[id] = nil }
                axisChanged = true
            }
            for key in control.spec.keys { output += releaseKey(key) }
        }
        if axisChanged { output.append(axisMotion()) }
        return output
    }

    private mutating func pressKey(_ key: Int) -> [PlayerTouchOutput] {
        keyHolds[key, default: 0] += 1
        return keyHolds[key] == 1 ? [.digital(key: key, pressed: true)] : []
    }

    private mutating func releaseKey(_ key: Int) -> [PlayerTouchOutput] {
        guard let count = keyHolds[key] else { return [] }
        if count <= 1 {
            keyHolds[key] = nil
            return [.digital(key: key, pressed: false)]
        }
        keyHolds[key] = count - 1
        return []
    }

    /// Held axis buttons (N64 C buttons) push the right stick.
    private func axisMotion() -> PlayerTouchOutput {
        let held = layout.controls.filter { axisHolds[$0.id] != nil }
        let x = min(1, max(-1, held.reduce(0) { $0 + $1.spec.axisX }))
        let y = min(1, max(-1, held.reduce(0) { $0 + $1.spec.axisY }))
        return .motion(stick: 1, x: x, y: y)
    }

    /// A stick: the output for the thumb's offset from the centre, which stays where the
    /// touch landed; the touch keeps the stick outside its zone until it lifts. Haptics: a
    /// notch click when the stick settles into a notch of its gate at full tilt; otherwise
    /// one soft tick on entering full tilt.
    private mutating func stickMotion(_ touch: Int, _ id: String, center: CGPoint, fullTilt wasFullTilt: Bool, notch held: Int?,
                                      at point: CGPoint) -> [PlayerTouchOutput] {
        guard let control = layout.control(id), let stick = control.spec.stick, let profile = control.stickProfile else { return [] }
        controlHolds[id] = 1
        let dx = Double(point.x - center.x), dy = Double(point.y - center.y)
        let value = PlayerStickModel.output(dx: dx, dy: dy, profile)
        let fullTilt = PlayerStickModel.fullTilt(dx: dx, dy: dy, profile)
        let notch = PlayerStickModel.notch(dx: dx, dy: dy, profile, current: held)
        touches[touch] = .stick(id, center: center, fullTilt: fullTilt, notch: notch)
        let travel = CGFloat(profile.travel)
        stickPositions[id] = PlayerStickPosition(center: center, knob: CGPoint(x: center.x + CGFloat(value.x) * travel, y: center.y + CGFloat(value.y) * travel),
                                                 fullTilt: fullTilt)
        var output: [PlayerTouchOutput] = []
        let vector = CGVector(dx: value.x, dy: value.y)
        if stickValues[stick] != vector {
            stickValues[stick] = vector
            output.append(.motion(stick: stick, x: value.x, y: value.y))
        }
        if let notch, notch != held {
            output.append(.stickNotch(profile.gate))
        } else if fullTilt && !wasFullTilt && notch == nil {
            output.append(.stickHaptic)
        }
        return output
    }
}
