import Foundation

/// A small sound that colours a haptic: the brief, pitchless click a physical control makes,
/// synthesised from band-limited noise (revised 2026-09-28: the first sounds had decaying
/// tones in them and read as tonal "effects"). Aim: invisible, like the Magic Trackpad's
/// click — you'd only notice it by its absence. Described by what the control is made of,
/// rendered in one of a few styles and a few takes (so a repeated press isn't one sample),
/// and played by Core Haptics in the same pattern as the haptic, so they land together.
struct SoundRecipe: Hashable, Sendable {
    /// Names the rendered files (with the style and take).
    let id: String
    /// 0 soft rubber … 1 a hard switch: brighter and shorter as it rises.
    var hardness: Double
    /// 0 tiny … 1 heavy: a dull, low thud under the click (noise, never a tone).
    var mass = 0.0
    /// A second contact this many seconds after the first (a switch's two leaves).
    var second: Double?
    /// The second contact's level.
    var echo = 0.5
    /// A faint scrape this long before the click (a trigger's travel).
    var slide: Double?

    static let sampleRate = 48_000.0
    static let takes = 4

    /// Band edges (Hz) and decay (seconds) of the click for a style.
    private func click(_ style: SoundStyle) -> (low: Double, high: Double, decay: Double) {
        let h = min(max(hardness, 0), 1)
        var low = 350 + 2_650 * h, high = 1_800 + 8_200 * h, decay = 0.0024 - 0.0017 * h
        switch style {
        case .invisible: break
        case .muted: low *= 0.6; high *= 0.55; decay *= 1.3
        case .crisp: low *= 1.3; high = min(high * 1.25, 16_000); decay *= 0.8
        }
        return (low, high, decay)
    }

    /// The sound as 16-bit mono PCM, peaking at 90% of full scale.
    func samples(style: SoundStyle = .invisible, take: Int = 0) -> [Int16] {
        let rate = Self.sampleRate
        let start = slide ?? 0
        let shape = click(style)
        let bodyDecay = 0.003 + 0.009 * mass
        var length = start + max(shape.decay * 8, mass > 0 ? bodyDecay * 6 : 0)
        if let second { length = max(length, start + second + shape.decay * 8) }
        let count = Int((length + 0.002) * rate)
        var mix = [Double](repeating: 0, count: count)
        var seed = UInt64(truncatingIfNeeded: (id + "\(style)\(take)").utf8.reduce(17) { $0 &* 31 &+ Int($1) }) | 1

        func noise() -> Double {
            seed ^= seed << 13; seed ^= seed >> 7; seed ^= seed << 17
            return Double(seed % 20_001) / 10_000 - 1
        }

        /// White noise through a one-pole high-pass at `low` and two one-pole low-passes at
        /// `high`, rising over `rise` and decaying with time constant `constant`.
        func burst(at time: Double, low: Double, high: Double, constant: Double, level: Double, rise: Double = 0.00015) {
            let hp = exp(-2 * .pi * low / rate), lp = exp(-2 * .pi * min(high, rate * 0.45) / rate)
            var hpIn = 0.0, hpOut = 0.0, lp1 = 0.0, lp2 = 0.0
            let first = Int(time * rate)
            guard first < count else { return }
            for n in first..<count {
                let t = Double(n - first) / rate
                let white = noise()
                hpOut = hp * (hpOut + white - hpIn)
                hpIn = white
                lp1 = (1 - lp) * hpOut + lp * lp1
                lp2 = (1 - lp) * lp1 + lp * lp2
                let envelope = min(t / rise, 1) * exp(-t / constant)
                if envelope < 0.0005, t > rise { break }
                mix[n] += lp2 * level * envelope
            }
        }

        if let slide {
            // The travel: a faint scrape that swells into the click.
            let hp = exp(-2 * .pi * 500 / rate), lp = exp(-2 * .pi * 2_500 / rate)
            var hpIn = 0.0, hpOut = 0.0, lp1 = 0.0
            let last = min(Int(slide * rate), count)
            for n in 0..<last {
                let white = noise()
                hpOut = hp * (hpOut + white - hpIn)
                hpIn = white
                lp1 = (1 - lp) * hpOut + lp * lp1
                mix[n] += lp1 * 0.12 * Double(n) / Double(max(last, 1))
            }
        }
        // The contact, its body and its second leaf; each take varies them a little.
        let vary = 1 + (Double(take % Self.takes) - 1.5) * 0.04
        burst(at: start, low: shape.low * vary, high: shape.high * vary, constant: shape.decay, level: 1)
        if mass > 0 {
            let body = style == .crisp ? 0.6 : (style == .muted ? 1.3 : 1)
            burst(at: start, low: 40, high: 160 + 260 * (1 - hardness), constant: bodyDecay,
                  level: (0.6 + 1.4 * mass) * body, rise: 0.0006)
        }
        if let second {
            burst(at: start + second * vary, low: shape.low * 1.1, high: shape.high * 1.1, constant: shape.decay * 0.8, level: echo)
        }
        // Fade the last 2 ms, normalise to 90%.
        let fade = min(Int(0.002 * rate), count)
        for n in max(count - fade, 0)..<count { mix[n] *= Double(count - 1 - n) / Double(max(fade, 1)) }
        let peak = max(mix.map(abs).max() ?? 1, 1e-9)
        return mix.map { Int16(($0 / peak * 0.9 * 32_767).rounded()) }
    }

    /// The sound as a WAV file's bytes.
    func wav(style: SoundStyle = .invisible, take: Int = 0) -> Data {
        let pcm = samples(style: style, take: take)
        var data = Data()
        func append<T: FixedWidthInteger>(_ value: T) { withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) } }
        let bytes = UInt32(pcm.count * 2)
        data.append(contentsOf: Array("RIFF".utf8)); append(36 + bytes)
        data.append(contentsOf: Array("WAVE".utf8))
        data.append(contentsOf: Array("fmt ".utf8)); append(UInt32(16)); append(UInt16(1)); append(UInt16(1))
        append(UInt32(Self.sampleRate)); append(UInt32(Self.sampleRate) * 2); append(UInt16(2)); append(UInt16(16))
        data.append(contentsOf: Array("data".utf8)); append(bytes)
        for sample in pcm { append(sample) }
        return data
    }

    /// The rendered file's name for a style and take.
    func fileID(style: SoundStyle, take: Int) -> String {
        "v2-\(id)-\(style.rawValue)-\(take % Self.takes)"
    }
}

/// How the playground renders the sounds.
enum SoundStyle: String, CaseIterable, Identifiable, Sendable {
    /// Short and quiet, the contact's own click (the default).
    case invisible
    /// Duller and a touch longer: through a case.
    case muted
    /// Brighter and tighter.
    case crisp

    var id: String { rawValue }
    var title: String {
        switch self {
        case .invisible: "Invisible"
        case .muted: "Muted"
        case .crisp: "Crisp"
        }
    }
}

/// A sound in a haptic recipe: when, what, how loud (0…1, before the playground's level).
struct SoundCue: Hashable, Sendable {
    var at: Double
    var sound: SoundRecipe
    var volume: Double
}

// MARK: The sounds

extension SoundRecipe {
    // Buttons: each only hardness, mass and contacts.
    static let domeThup = SoundRecipe(id: "dome", hardness: 0.15, mass: 0.35)
    static let domeRelease = SoundRecipe(id: "dome-release", hardness: 0.25)
    static let plasticTock = SoundRecipe(id: "plastic", hardness: 0.5, mass: 0.2)
    static let plasticTockRelease = SoundRecipe(id: "plastic-release", hardness: 0.6)
    static let snapPing = SoundRecipe(id: "snap", hardness: 0.6, mass: 0.1)
    static let tactTic = SoundRecipe(id: "tact", hardness: 0.9)
    static let tactTicRelease = SoundRecipe(id: "tact-release", hardness: 1)
    static let clack = SoundRecipe(id: "clack", hardness: 0.45, mass: 0.3)
    static let shallowTic = SoundRecipe(id: "shallow", hardness: 0.75)
    static let thock = SoundRecipe(id: "thock", hardness: 0.25, mass: 0.45)
    static let hingeClick = SoundRecipe(id: "hinge", hardness: 0.8, second: 0.009, echo: 0.4)
    static let swish = SoundRecipe(id: "swish", hardness: 0.3, slide: 0.03)
    static let pillPuff = SoundRecipe(id: "pill", hardness: 0.08, mass: 0.2)
    static let microswitch = SoundRecipe(id: "microswitch", hardness: 1, second: 0.006, echo: 0.5)
    static let microswitchRelease = SoundRecipe(id: "microswitch-release", hardness: 0.95)
    static let blueClick = SoundRecipe(id: "clicky", hardness: 0.95, second: 0.004, echo: 0.3, slide: 0.009)
    static let thunk = SoundRecipe(id: "thunk", hardness: 0.2, mass: 1)
    static let tink = SoundRecipe(id: "tink", hardness: 1)
    static let puff = SoundRecipe(id: "puff", hardness: 0)
    // Sticks
    static let gateTick = SoundRecipe(id: "gate", hardness: 0.7)
    static let rimTap = SoundRecipe(id: "rim", hardness: 0.4, mass: 0.15)
    static let springClack = SoundRecipe(id: "spring", hardness: 0.55, mass: 0.15)

    static let all: [SoundRecipe] = [
        domeThup, domeRelease, plasticTock, plasticTockRelease, snapPing, tactTic, tactTicRelease, clack, shallowTic,
        thock, hingeClick, swish, pillPuff, microswitch, microswitchRelease, blueClick, thunk, tink, puff,
        gateTick, rimTap, springClack,
    ]
}
