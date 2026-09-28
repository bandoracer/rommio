import Foundation

// Rumble as libretro cores send it: `set_rumble_state(port, effect, strength)` means "this
// motor at this strength until told otherwise" — a state, not an event. How often cores
// call it differs a lot: gambatte (GB/GBC carts) and mupen64plus (N64 Rumble Pak) only on a
// change, pcsx_rearmed (DualShock) with a 0–255 strong motor and an on/off weak one, mGBA
// (GBA carts) both motors every frame with an averaged on/off level.

/// A motor on a libretro pad.
enum RumbleMotor: Hashable, Sendable {
    /// The big, slow, low motor (a deep rumble).
    case strong
    /// The small, fast motor (a buzz).
    case weak
}

/// One `set_rumble_state` call.
struct RumbleCall: Hashable, Sendable {
    var motor: RumbleMotor
    /// 0…1 (the core's 0…65535).
    var strength: Double
}

/// What a renderer asks of the actuator for one frame; nil fields are unchanged. Two voices
/// set `strong` and `weak`; the blend sets one voice (`level`, `sharpness`).
struct RumbleOutput: Equatable, Sendable {
    var level: Double?
    var sharpness: Double?
    var strong: Double?
    var weak: Double?
    /// Taps on top: a motor starting hard.
    var kicks: [HapticRecipe] = []
    /// Silent and wound down: stop the voices.
    var stop = false
}

/// The pad's motors as the core last set them, and what the renderers share: a gamma so low
/// strengths are still felt, the weak motor's buzz held to 80% (at full it overpowered the
/// rumble; revised 2026-09-28), and an onset tap when a motor starts hard (a motor spinning up
/// has a kick).
struct RumbleMotors: Sendable {
    static let strongSharpness = 0.12
    static let weakSharpness = 0.72
    static let weakCap = 0.8
    static let gamma = 0.55
    /// Level changes smaller than this aren't sent (cores that report every frame).
    static let threshold = 0.02
    /// Silent frames (60 fps) before the voices stop, once wound down (stopping and
    /// restarting on every zero clicks).
    static let idleFrames = 15

    private(set) var strong = 0.0
    private(set) var weak = 0.0
    /// Frames since both motors went off.
    private(set) var silentFrames = 0

    /// Applies a frame's calls; returns the onset taps.
    mutating func apply(_ calls: [RumbleCall]) -> [HapticRecipe] {
        let wasStrong = strong, wasWeak = weak
        for call in calls {
            switch call.motor {
            case .strong: strong = min(max(call.strength, 0), 1)
            case .weak: weak = min(max(call.strength, 0), 1)
            }
        }
        var kicks: [HapticRecipe] = []
        if wasStrong < 0.05, strong > 0.5 { kicks.append(.tap(Self.level(strong), 0.3)) }
        if wasWeak < 0.05, weak > 0.5 { kicks.append(.tap(Self.level(weak) * Self.weakCap * 0.8, 0.85)) }
        silentFrames = strong == 0 && weak == 0 ? silentFrames + 1 : 0
        return kicks
    }

    /// The levels the motors ask for (before their ramps).
    var targets: (strong: Double, weak: Double) {
        (Self.level(strong), Self.level(weak) * Self.weakCap)
    }

    /// Strength → voice level.
    static func level(_ strength: Double) -> Double {
        strength <= 0 ? 0 : pow(strength, gamma)
    }
}

/// A motor's inertia: it takes a moment to spin up and longer to wind down (revised
/// 2026-09-28: levels jumped instantly). A one-pole ease toward the target, with a time
/// constant for rising and one for falling; the heavy strong motor is slower both ways.
struct MotorRamp: Sendable {
    static let strong = MotorRamp(rise: 0.035, fall: 0.09)
    static let weak = MotorRamp(rise: 0.015, fall: 0.045)

    /// Time constants (seconds): 95% of the way in three of them.
    let rise: Double
    let fall: Double
    private(set) var value = 0.0

    init(rise: Double, fall: Double) {
        self.rise = rise
        self.fall = fall
    }

    /// One frame toward `target`.
    mutating func step(toward target: Double, seconds: Double = 1.0 / 60) -> Double {
        let constant = target > value ? rise : fall
        value += (target - value) * (1 - exp(-seconds / constant))
        if abs(target - value) < 0.004 { value = target }
        return value
    }
}

/// Rumble as two continuous voices, one per motor: the strong motor deep (low sharpness),
/// the weak one a buzz (high sharpness), each easing toward its motor's level (the default:
/// on the phone it felt better than the blend, 2026-09-28).
struct TwoVoiceRumble: Sendable {
    private var motors = RumbleMotors()
    private var strongRamp = MotorRamp.strong
    private var weakRamp = MotorRamp.weak
    private var sentStrong = 0.0
    private var sentWeak = 0.0
    private(set) var running = false

    mutating func frame(_ calls: [RumbleCall]) -> RumbleOutput {
        var output = RumbleOutput(kicks: motors.apply(calls))
        let targets = motors.targets
        let strong = strongRamp.step(toward: targets.strong), weak = weakRamp.step(toward: targets.weak)
        if strong > 0 || weak > 0 { running = true }
        if changed(strong, sentStrong) { output.strong = strong; sentStrong = strong }
        if changed(weak, sentWeak) { output.weak = weak; sentWeak = weak }
        if running, strong == 0, weak == 0, motors.silentFrames >= RumbleMotors.idleFrames {
            output.stop = true
            running = false
        }
        return output
    }
}

/// Rumble as one voice: the motors added as felt strengths — 1 − (1 − strong)(1 − weak),
/// never below either alone — with the sharpness sliding between the strong motor's deep
/// rumble and the weak one's buzz by how much each contributes. Kept to compare.
struct BlendedRumble: Sendable {
    private var motors = RumbleMotors()
    private var strongRamp = MotorRamp.strong
    private var weakRamp = MotorRamp.weak
    private var sentLevel = 0.0
    private var sentSharpness = RumbleMotors.strongSharpness
    private(set) var running = false

    mutating func frame(_ calls: [RumbleCall]) -> RumbleOutput {
        var output = RumbleOutput(kicks: motors.apply(calls))
        let targets = motors.targets
        let mix = Self.mix(levels: strongRamp.step(toward: targets.strong), weakRamp.step(toward: targets.weak))
        if mix.level > 0 { running = true }
        if changed(mix.level, sentLevel) { output.level = mix.level; sentLevel = mix.level }
        if abs(mix.sharpness - sentSharpness) > RumbleMotors.threshold {
            output.sharpness = mix.sharpness
            sentSharpness = mix.sharpness
        }
        if running, mix.level == 0, motors.silentFrames >= RumbleMotors.idleFrames {
            output.stop = true
            running = false
        }
        return output
    }

    /// Motor strengths as one voice's level and sharpness.
    static func mix(strong: Double, weak: Double) -> (level: Double, sharpness: Double) {
        mix(levels: RumbleMotors.level(strong), RumbleMotors.level(weak) * RumbleMotors.weakCap)
    }

    private static func mix(levels s: Double, _ w: Double) -> (level: Double, sharpness: Double) {
        let level = 1 - (1 - s) * (1 - w)
        let sharpness = s + w > 0
            ? (s * RumbleMotors.strongSharpness + w * RumbleMotors.weakSharpness) / (s + w)
            : RumbleMotors.strongSharpness
        return (level, sharpness)
    }
}

/// A level worth sending: moved past the threshold, or to or from zero.
private func changed(_ value: Double, _ sent: Double) -> Bool {
    abs(value - sent) > RumbleMotors.threshold || (value == 0) != (sent == 0)
}

/// How rumble plays in the player today, for comparison: every call above zero becomes one
/// tap (heavier for a stronger call); zeros are dropped. A held rumble is one tap; a core
/// that reports every frame becomes a tap per call, 60–120 a second, that never lets go.
enum OneTapRumble {
    static func taps(_ calls: [RumbleCall]) -> [HapticRecipe] {
        calls.filter { $0.strength > 0 }.map { call in
            // Close to UIImpactFeedbackGenerator's heavy / medium / light at the call's intensity.
            let sharpness = call.strength > 0.66 ? 0.35 : (call.strength > 0.33 ? 0.5 : 0.65)
            return .tap(min(max(call.strength, 0.15), 1), sharpness)
        }
    }
}

/// A rumble recording: what a core calls, frame by frame (60 fps), for a moment of a game.
struct RumbleScene: Identifiable, Hashable, Sendable {
    /// How the core reports, which shapes what the renderer receives.
    enum Reporting: Hashable, Sendable {
        /// Only when a motor's strength changes.
        case onChange
        /// Both motors, every frame (mGBA).
        case everyFrame
    }

    let id: String
    let title: String
    /// The game or kind of moment, and the hardware.
    let source: String
    let detail: String
    /// Each frame's calls.
    let timeline: [[RumbleCall]]

    var seconds: Double { Double(timeline.count) / 60 }

    /// A scene from per-frame motor levels, reported the core's way.
    init(id: String, title: String, source: String, detail: String, reporting: Reporting, levels: [(strong: Double, weak: Double)]) {
        self.id = id
        self.title = title
        self.source = source
        self.detail = detail
        var timeline: [[RumbleCall]] = []
        var last: (strong: Double, weak: Double) = (0, 0)
        for frame in levels + [(0, 0)] {
            var calls: [RumbleCall] = []
            switch reporting {
            case .everyFrame:
                calls = [RumbleCall(motor: .strong, strength: frame.strong), RumbleCall(motor: .weak, strength: frame.weak)]
            case .onChange:
                if frame.strong != last.strong { calls.append(RumbleCall(motor: .strong, strength: frame.strong)) }
                if frame.weak != last.weak { calls.append(RumbleCall(motor: .weak, strength: frame.weak)) }
            }
            timeline.append(calls)
            last = frame
        }
        self.timeline = timeline
    }

    static func == (lhs: RumbleScene, rhs: RumbleScene) -> Bool { lhs.id == rhs.id }
    func hash(into hasher: inout Hasher) { hasher.combine(id) }
}

// MARK: The scenes

extension RumbleScene {
    static let all: [RumbleScene] = [
        arwingHit, stoneOfAgony, kartOffRoad, engineRev, heartbeat, gunfire,
        pinballBumpers, drillGears, twistedSpin, dsRumblePak, explosion, earthquake,
    ]

    /// Frames (60 fps) as levels: `count` frames of (strong, weak).
    private static func hold(_ count: Int, _ strong: Double, _ weak: Double = 0) -> [(strong: Double, weak: Double)] {
        Array(repeating: (strong, weak), count: count)
    }

    /// A deterministic 0…1 noise, so scenes play the same every time.
    private static func noise(_ n: Int, _ seed: Int) -> Double {
        var x = UInt64(truncatingIfNeeded: n &* 2654435761 &+ seed &* 40503)
        x ^= x >> 13; x = x &* 0x5bd1e995; x ^= x >> 15
        return Double(x % 1000) / 999
    }

    static let arwingHit = RumbleScene(
        id: "arwing-hit", title: "Laser hits", source: "Star Fox 64 · N64 Rumble Pak",
        detail: "Three hits on the wing. The Rumble Pak has one motor, full on then off.",
        reporting: .onChange,
        levels: hold(14, 1) + hold(10, 0) + hold(10, 1) + hold(6, 0) + hold(22, 1) + hold(20, 0))

    static let stoneOfAgony = RumbleScene(
        id: "stone-of-agony", title: "Stone of Agony", source: "Ocarina of Time · N64 Rumble Pak",
        detail: "A secret nearby: slow pulses, closer and closer together.",
        reporting: .onChange,
        levels: [40, 34, 28, 22, 16, 12].flatMap { gap in hold(7, 0.6) + hold(gap, 0) })

    static let kartOffRoad = RumbleScene(
        id: "kart-off-road", title: "Off the road", source: "Mario Kart 64 · N64 Rumble Pak",
        detail: "Wheels in the grass: a low grumble with bumps in it.",
        reporting: .onChange,
        levels: (0..<150).map { n in
            let bump = noise(n / 5, 3) > 0.72 ? 0.9 : 0.0
            return (max(0.32 + noise(n / 3, 7) * 0.12, bump), 0)
        })

    static let engineRev = RumbleScene(
        id: "engine-rev", title: "Launch and shift", source: "Gran Turismo · DualShock",
        detail: "Revs climb on the big motor; each gear change is a blip on the small one.",
        reporting: .onChange,
        levels: (0..<90).map { n in (0.15 + 0.85 * Double(n) / 90, 0.0) }
            + hold(5, 0.3, 1)
            + (0..<80).map { n in (0.3 + 0.7 * Double(n) / 80, 0.0) }
            + hold(5, 0.35, 1)
            + (0..<40).map { n in (0.35 + 0.3 * Double(n) / 40, 0.0) }
            + (0..<30).map { n in (0.65 * (1 - Double(n) / 30), 0.0) })

    static let heartbeat = RumbleScene(
        id: "heartbeat", title: "Heartbeat", source: "Metal Gear Solid · DualShock",
        detail: "Lub-dub on the big motor, the way the DualShock's classics did it.",
        reporting: .onChange,
        levels: (0..<4).flatMap { _ in hold(5, 0.85) + hold(7, 0) + hold(5, 0.5) + hold(38, 0) })

    static let gunfire = RumbleScene(
        id: "gunfire", title: "Automatic fire", source: "DualShock",
        detail: "A kick on the big motor, then the small motor chatters with each shot.",
        reporting: .onChange,
        levels: hold(4, 1, 1) + (0..<12).flatMap { _ in hold(3, 0, 1) + hold(3, 0, 0) } + hold(6, 0.4))

    static let pinballBumpers = RumbleScene(
        id: "pinball-bumpers", title: "Bumpers", source: "Pokémon Pinball · Game Boy rumble cart",
        detail: "The cart's motor is on or off: a burst for every bumper, a flick for each flipper.",
        reporting: .onChange,
        levels: hold(2, 1) + hold(12, 0) + hold(5, 1) + hold(4, 0) + hold(5, 1) + hold(20, 0)
            + hold(2, 1) + hold(8, 0) + hold(6, 1) + hold(3, 0) + hold(6, 1) + hold(3, 0) + hold(6, 1) + hold(24, 0) + hold(2, 1) + hold(10, 0))

    static let drillGears = RumbleScene(
        id: "drill-gears", title: "Drill gears 1 to 3", source: "Drill Dozer · GBA rumble cart",
        detail: "The cart pulses its motor faster than a frame; mGBA reports every frame with the pulses averaged, so each gear is a steadier, stronger level.",
        reporting: .everyFrame,
        levels: [0.33, 0.66, 1.0].enumerated().flatMap { gear, duty in
            (0..<50).map { n -> (strong: Double, weak: Double) in
                let averaged = min(1, max(0, duty + (noise(n, 17 + gear) - 0.5) * 0.12))
                return (averaged, averaged)
            } + hold(12, 0, 0)
        })

    static let twistedSpin = RumbleScene(
        id: "twisted-spin", title: "Spinning up", source: "WarioWare: Twisted! · GBA rumble cart",
        detail: "Turning the console: ticks that speed up with the spin, one per notch.",
        reporting: .everyFrame,
        levels: {
            var frames: [(strong: Double, weak: Double)] = []
            var gap = 14
            while frames.count < 170 {
                frames += hold(1, 1, 1) + hold(gap, 0, 0)
                gap = max(2, gap - 1)
            }
            return frames
        }())

    static let dsRumblePak = RumbleScene(
        id: "ds-rumble-pak", title: "Ball strikes", source: "Metroid Prime Pinball · DS Rumble Pak",
        detail: "The Slot-2 pak's motor, on for each strike, longer for the big ones.",
        reporting: .onChange,
        levels: hold(4, 1) + hold(16, 0) + hold(4, 1) + hold(9, 0) + hold(12, 1) + hold(30, 0) + hold(4, 1) + hold(20, 0))

    static let explosion = RumbleScene(
        id: "explosion", title: "Explosion", source: "Both motors",
        detail: "Everything at once, the buzz dying first and the rumble rolling away.",
        reporting: .onChange,
        levels: (0..<75).map { n in
            let t = Double(n) / 75
            return (exp(-2.2 * t), max(0, exp(-6 * t) - 0.02))
        })

    static let earthquake = RumbleScene(
        id: "earthquake", title: "Earthquake", source: "Both motors",
        detail: "A long, uneven shake that builds and settles.",
        reporting: .onChange,
        levels: (0..<150).map { n in
            let swell = sin(Double(n) / 150 * .pi)
            return (swell * (0.55 + noise(n / 2, 11) * 0.45), swell * noise(n / 4, 5) * 0.4)
        })
}
