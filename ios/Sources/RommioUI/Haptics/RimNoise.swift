import Foundation

// Noisy rim textures (2026-10-06). The first textures were too regular: grit was one tap every
// so many degrees, fired on touch updates (120 a second), so it felt like a pitched buzz rather
// than grit (docs/haptics.md, Noise). These are noise instead: grains at random (Poisson)
// spacing and at random moments between touch updates, a buzz whose strength is noise, and
// looped beds of random grains whose speed follows the thumb.

extension StickFeel {
    /// Grains scattered along the rim: on average one every `degrees`, at random rather than
    /// evenly, each at a random moment between touch updates (not on the touch clock).
    struct Scatter: Hashable, Sendable {
        var degrees: Double
        var intensity: ClosedRange<Double>
        var sharpness: ClosedRange<Double>
        /// 0…1: how often a grain brings one or two more right behind it (gravel comes in clumps).
        var clumping: Double = 0
    }
}

/// A grain scattered within a touch update: when (0…1 of the time to the next update) and how.
struct ScatterGrain: Equatable, Sendable {
    var offset: Double
    var intensity: Double
    var sharpness: Double
}

/// A looped bed of noise, played while the thumb slides along the rim: its strength follows
/// the push and the speed, its playback speed the thumb's speed (faster sliding, finer grain).
struct NoiseBed: Hashable, Sendable {
    /// How a hiss's strength wanders: white jumps anywhere each step, pink mostly in small
    /// steps with some large, brown drifts.
    enum Color: String, CaseIterable, Hashable, Sendable, Identifiable {
        case white, pink, brown
        var id: String { rawValue }
        var title: String { rawValue.capitalized }
    }

    /// A buzz whose strength (and sharpness) changes at random every `step` seconds.
    struct Hiss: Hashable, Sendable {
        var sharpness: Double
        /// How far the sharpness wanders either way.
        var sharpnessSpread: Double
        var step: Double
        /// The weakest it gets (0…1 of the bed's level).
        var floor: Double
        var color: Color
    }

    /// Taps at random times, `rate` a second on average at the bed's own speed.
    struct Grains: Hashable, Sendable {
        var rate: Double
        var intensity: ClosedRange<Double>
        var sharpness: ClosedRange<Double>
        var clumping: Double = 0
    }

    var hiss: Hiss?
    var grains: Grains?
    /// The bed's strength at full push and its own speed.
    var level: Double
    /// Degrees a second along the rim that play the bed at its own speed (one turn a second).
    var normalSpeed: Double = 360
    /// The loop's length (long enough not to be heard repeating, short enough to stay small).
    var seconds: Double = 1.5
    var seed: UInt64 = 0x5EED

    /// The bed as one recipe: the hiss as back-to-back buzzes of random strength, the grains
    /// as taps at random times. `density` multiplies the grains; `color` replaces the hiss's.
    func recipe(density: Double = 1, color: Color? = nil) -> HapticRecipe {
        var random = NoiseSource(seed: seed)
        var events: [HapticRecipe.Event] = []
        if let hiss {
            let steps = Int((seconds / hiss.step).rounded())
            var shade = NoiseColorer(color: color ?? hiss.color)
            var sharpnessShade = NoiseColorer(color: .pink)
            for index in 0..<steps {
                let value = shade.next(&random)
                let sharpness = hiss.sharpness + hiss.sharpnessSpread * (sharpnessShade.next(&random) * 2 - 1)
                events.append(HapticRecipe.Event(at: Double(index) * hiss.step, duration: hiss.step,
                                                 intensity: hiss.floor + (1 - hiss.floor) * value,
                                                 sharpness: min(max(sharpness, 0), 1),
                                                 attack: 0, release: 0, sustained: true))
            }
        }
        if let grains {
            var time = random.exponential(mean: 1 / (grains.rate * density))
            while time < seconds {
                let clump = random.unit() < grains.clumping ? 1 + Int(random.unit() * 2.99) : 0
                for member in 0...clump {
                    let at = time + Double(member) * (0.002 + 0.006 * random.unit())
                    guard at < seconds else { break }
                    events.append(HapticRecipe.Event(at: at, intensity: random.skewed(grains.intensity),
                                                     sharpness: random.uniform(grains.sharpness)))
                }
                time += random.exponential(mean: 1 / (grains.rate * density))
            }
        }
        return HapticRecipe(events: events.sorted { $0.at < $1.at })
    }

    /// The bed with its loop shortened (to half a second at most) so a dense one stays within
    /// about `budget` events, a pattern Core Haptics takes in one go.
    func fitted(density: Double = 1, budget: Double = 700) -> NoiseBed {
        let hissRate = hiss.map { 1 / $0.step } ?? 0
        let grainRate = grains.map { $0.rate * density * (1 + $0.clumping * 2) } ?? 0
        let perSecond = hissRate + grainRate
        guard perSecond * seconds > budget else { return self }
        var copy = self
        copy.seconds = max(0.5, budget / perSecond)
        return copy
    }

    /// The bed's level now (0: silent) for the thumb's speed along the rim and its push: at
    /// least `floor` while it slides (any contact is felt), more with push and speed.
    func level(rimSpeed: Double, force: Double, floor: Double, gain: Double = 1) -> Double {
        guard rimSpeed > 15 else { return 0 }
        let speed = min(1, rimSpeed / normalSpeed)
        let value = level * gain * (0.55 + 0.45 * force) * (0.4 + 0.6 * speed)
        return min(max(value, floor), 1)
    }

    /// Playback speed: `response` 0 keeps the bed's own speed, 1 follows the thumb (a turn a
    /// second plays it as made).
    func rate(rimSpeed: Double, response: Double) -> Double {
        let following = min(max(rimSpeed / normalSpeed, 0.3), 2.5)
        return 1 + (following - 1) * min(max(response, 0), 1)
    }
}

/// The noise bench's settings for one texture (Noise bench): played constantly, as if the
/// thumb slid a turn a second.
struct NoiseBenchSettings: Hashable, Sendable {
    /// Strength (the bed's live level).
    var level: Double
    /// Playback speed (1: as made).
    var rate = 1.0
    /// Sharpness shift, live (−0.5…0.5).
    var sharpness = 0.0
    /// Grains ×.
    var density = 1.0
    /// A hiss's step (seconds): shorter is finer.
    var step = 0.006
    /// How deep a hiss's strength swings (1 − its floor).
    var depth = 0.85
    var color: NoiseBed.Color = .white

    /// The texture's own settings.
    init(texture: RimTexture) {
        let bed = Self.baseBed(texture)
        level = bed?.level ?? 0.5
        if let hiss = bed?.hiss {
            step = hiss.step
            depth = 1 - hiss.floor
            color = hiss.color
        }
    }

    /// The texture's bed with these settings, scattered grit becoming a loop of grains at the
    /// rate a turn a second would scatter them; nil for a texture with no noise.
    func bed(for texture: RimTexture) -> NoiseBed? {
        guard var bed = Self.baseBed(texture) else { return nil }
        if var hiss = bed.hiss {
            hiss.step = step
            // To the thousandth, so an untouched depth gives back the design's own floor.
            hiss.floor = ((1 - depth) * 1000).rounded() / 1000
            hiss.color = color
            bed.hiss = hiss
        }
        return bed
    }

    private static func baseBed(_ texture: RimTexture) -> NoiseBed? {
        var bed = texture.bed
        if let scatter = texture.scatter {
            let grains = NoiseBed.Grains(rate: 360 / scatter.degrees, intensity: scatter.intensity,
                                         sharpness: scatter.sharpness, clumping: scatter.clumping)
            if bed == nil { bed = NoiseBed(grains: grains, level: 0.75) } else if bed?.grains == nil { bed?.grains = grains }
        }
        return bed
    }
}

/// How the playground's noisy textures are tuned (Sticks › Noise).
struct NoiseTuning: Hashable, Sendable {
    /// Every noisy texture's strength ×.
    var gain = 1.0
    /// Grains ×: scattered grit and a bed's grains.
    var density = 1.0
    /// How much a bed's speed follows the thumb (0: its own speed).
    var speedResponse = 0.8
    /// A hiss's colour instead of its own (nil: as designed).
    var color: NoiseBed.Color?
}

/// A small deterministic random source (SplitMix64): the same seed, the same texture.
struct NoiseSource: Sendable {
    private var state: UInt64

    init(seed: UInt64) { state = seed }

    /// 0…1.
    mutating func unit() -> Double {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        z ^= z >> 31
        return Double(z >> 11) / Double(UInt64(1) << 53)
    }

    mutating func uniform(_ range: ClosedRange<Double>) -> Double {
        range.lowerBound + (range.upperBound - range.lowerBound) * unit()
    }

    /// Mostly near the bottom of the range, now and then near the top (most specks are small).
    mutating func skewed(_ range: ClosedRange<Double>) -> Double {
        let u = unit()
        return range.lowerBound + (range.upperBound - range.lowerBound) * u * u
    }

    /// The time to the next of events arriving at random, `mean` apart on average.
    mutating func exponential(mean: Double) -> Double {
        -mean * log(max(1 - unit(), 1e-9))
    }

    /// How many of events averaging `mean` arrive (Knuth).
    mutating func poisson(_ mean: Double) -> Int {
        guard mean > 0 else { return 0 }
        let limit = exp(-mean)
        var count = 0
        var product = unit()
        while product > limit, count < 32 {
            count += 1
            product *= unit()
        }
        return count
    }
}

/// White, pink or brown noise, 0…1, one value at a time.
struct NoiseColorer: Sendable {
    let color: NoiseBed.Color
    private var rows = [Double](repeating: 0.5, count: 6)
    private var counter = 0
    private var walk = 0.5

    init(color: NoiseBed.Color) { self.color = color }

    mutating func next(_ random: inout NoiseSource) -> Double {
        switch color {
        case .white:
            return random.unit()
        case .pink:
            // Voss–McCartney: row r changes every 2^r values; their mean falls off at 3 dB an octave.
            for row in rows.indices where counter % (1 << row) == 0 { rows[row] = random.unit() }
            counter += 1
            let mean = rows.reduce(0, +) / Double(rows.count)
            return min(max((mean - 0.5) * 2.4 + 0.5, 0), 1)
        case .brown:
            walk += (random.unit() - 0.5) * 0.35
            if walk < 0 { walk = -walk }
            if walk > 1 { walk = 2 - walk }
            return walk
        }
    }
}
