import XCTest
@testable import RommioUI

/// The noisy rim textures (RimNoise.swift): grit that isn't a pulse train on the touch clock,
/// beds that are noise, and their controls.
final class RimNoiseTests: XCTestCase {
    /// When each grain plays (seconds) as a thumb circles the rim once a second at half push for
    /// `seconds`, touches arriving at 120 Hz; scattered grains land between updates.
    private func grainTimes(_ feel: StickFeel, seconds: Double = 3) -> [Double] {
        let profile = feel.profile
        let tuning = StickTuning()
        let rim = profile.travel * (profile.deadZone + profile.saturation * (1 - profile.deadZone))
        let r = rim + tuning.fullPush * pow(0.5, 1 / 1.3) * profile.travel
        let omega = 2 * Double.pi
        let interval = 1.0 / 120
        var texture = StickTexture()
        var times: [Double] = []
        for frame in 0..<Int(seconds * 120) {
            let t = Double(frame) * interval
            let angle = omega * t
            let cues = texture.update(dx: r * cos(angle), dy: r * sin(angle), vx: -r * omega * sin(angle), vy: r * omega * cos(angle),
                                      feel: feel, tuning: tuning)
            for cue in cues {
                switch cue {
                case .grain: times.append(t)
                case let .scatter(grains): times += grains.map { t + $0.offset * interval }
                default: break
                }
            }
        }
        return times.filter { $0 > 0.2 }
    }

    /// How strongly the grains repeat at a fixed interval (6–60 ms): the autocorrelation's
    /// highest peak, 1 for a perfect pulse train, near 0 for random arrivals.
    private func periodicity(_ times: [Double]) -> Double {
        let bin = 0.002
        let count = Int(((times.max() ?? 0) + 0.1) / bin)
        var series = [Double](repeating: 0, count: count)
        for time in times { series[min(Int(time / bin), count - 1)] += 1 }
        let mean = series.reduce(0, +) / Double(count)
        let centred = series.map { $0 - mean }
        let energy = centred.reduce(0) { $0 + $1 * $1 }
        var best = 0.0
        for lag in 3...30 {
            var sum = 0.0
            for i in 0..<(count - lag) { sum += centred[i] * centred[i + lag] }
            best = max(best, sum / energy)
        }
        return best
    }

    func testScatteredGritIsNotAPulseTrain() {
        let regular = periodicity(grainTimes(.n64Worn))
        let regularSand = periodicity(grainTimes(StickFeel.n64Worn.textured(.fineSand)))
        for texture in [RimTexture.scatterSand, .scatterGrit, .scatterGravel, .wornPlastic] {
            let scattered = periodicity(grainTimes(StickFeel.n64Worn.textured(texture)))
            XCTAssertLessThan(scattered, 0.2, "\(texture.id) repeats (\(scattered))")
            XCTAssertLessThan(scattered, regular * 0.5, "\(texture.id) vs the regular grit \(regular)")
        }
        XCTAssertGreaterThan(regular, 0.3, "the regular grit was a pulse train")
        XCTAssertGreaterThan(regularSand, 0.3, "the regular fine sand was too")
    }

    func testScatterDensityFollowsItsSpacing() {
        for texture in [RimTexture.scatterSand, .scatterGrit, .scatterGravel] {
            guard let scatter = texture.scatter else { return XCTFail(texture.id) }
            let times = grainTimes(StickFeel.n64Worn.textured(texture), seconds: 6)
            // Half push: 1.25 − 0.5 × 0.5 = 1 × the spacing; clumps add one or two now and then.
            let expected = 360 * (times.count > 0 ? (6 - 0.2) : 0) / scatter.degrees * (1 + scatter.clumping * 1.5)
            XCTAssertEqual(Double(times.count), expected, accuracy: expected * 0.3, texture.id)
        }
    }

    func testBedsAreDeterministicSeamlessAndBounded() {
        for texture in RimTexture.all {
            guard let bed = texture.bed else { continue }
            let recipe = bed.recipe()
            XCTAssertEqual(recipe, bed.recipe(), "\(texture.id): same seed, same texture")
            XCTAssertFalse(recipe.events.isEmpty, texture.id)
            XCTAssertLessThan(recipe.events.count, 700, "\(texture.id): a pattern Core Haptics takes in one go")
            XCTAssertLessThanOrEqual(recipe.events.map(\.at).max() ?? 0, bed.seconds, texture.id)
            if let hiss = bed.hiss {
                let buzzes = recipe.events.filter { $0.duration != nil }
                XCTAssertEqual(Double(buzzes.count) * hiss.step, bed.seconds, accuracy: hiss.step, "\(texture.id): the hiss fills the loop")
                XCTAssertTrue(buzzes.allSatisfy { $0.intensity >= hiss.floor - 1e-9 && $0.intensity <= 1 }, texture.id)
            }
            if let grains = bed.grains {
                let taps = recipe.events.filter { $0.duration == nil }.count
                let expected = grains.rate * bed.seconds * (1 + grains.clumping * 2)
                XCTAssertEqual(Double(taps), expected, accuracy: expected * 0.35, texture.id)
                let denser = bed.recipe(density: 2).events.filter { $0.duration == nil }.count
                XCTAssertGreaterThan(Double(denser), Double(taps) * 1.5, "\(texture.id): density adds grains")
            }
        }
    }

    func testNoiseColoursWanderDifferently() {
        func lagOne(_ color: NoiseBed.Color) -> Double {
            var random = NoiseSource(seed: 3)
            var colorer = NoiseColorer(color: color)
            let values = (0..<4000).map { _ in colorer.next(&random) }
            let mean = values.reduce(0, +) / Double(values.count)
            let centred = values.map { $0 - mean }
            let energy = centred.reduce(0) { $0 + $1 * $1 }
            return zip(centred, centred.dropFirst()).reduce(0) { $0 + $1.0 * $1.1 } / energy
        }
        let white = lagOne(.white), pink = lagOne(.pink), brown = lagOne(.brown)
        XCTAssertLessThan(abs(white), 0.05, "white: each step anywhere")
        XCTAssertGreaterThan(pink, white + 0.3, "pink: mostly small steps")
        XCTAssertGreaterThan(brown, pink, "brown: drifts")
    }

    func testABedFollowsTheThumb() {
        guard let bed = RimTexture.hiss.bed else { return XCTFail() }
        XCTAssertEqual(bed.level(rimSpeed: 10, force: 1, floor: 0.12), 0, "still: silent")
        let slow = bed.level(rimSpeed: 90, force: 0.2, floor: 0.12)
        let fast = bed.level(rimSpeed: 540, force: 0.2, floor: 0.12)
        let pushed = bed.level(rimSpeed: 540, force: 1, floor: 0.12)
        XCTAssertGreaterThanOrEqual(slow, 0.12, "any contact is felt")
        XCTAssertLessThan(slow, fast)
        XCTAssertLessThan(fast, pushed)
        XCTAssertEqual(bed.level(rimSpeed: 540, force: 1, floor: 0.12, gain: 0.5), pushed * 0.5, accuracy: 0.001)
        XCTAssertEqual(bed.rate(rimSpeed: 720, response: 0), 1, "fixed speed")
        XCTAssertEqual(bed.rate(rimSpeed: 720, response: 1), 2, accuracy: 0.001, "two turns a second: twice as fast")
        XCTAssertEqual(bed.rate(rimSpeed: 10, response: 1), 0.3, accuracy: 0.001, "slowest")
    }

    func testTheBenchStartsFromTheTexture() {
        let hiss = NoiseBenchSettings(texture: .hiss)
        XCTAssertEqual(hiss.level, 0.32, accuracy: 0.001)
        XCTAssertEqual(hiss.step, 0.005, accuracy: 0.0001)
        XCTAssertEqual(hiss.depth, 0.85, accuracy: 0.001)
        XCTAssertEqual(hiss.color, .white)
        XCTAssertEqual(hiss.bed(for: .hiss), RimTexture.hiss.bed, "untouched: the texture as designed")

        // Scattered grit plays as a loop of grains at a turn a second's rate.
        let sand = NoiseBenchSettings(texture: .scatterSand).bed(for: .scatterSand)
        XCTAssertEqual(sand?.grains?.rate ?? 0, 360 / 2.5, accuracy: 0.001)
        XCTAssertNil(sand?.hiss)
        let worn = NoiseBenchSettings(texture: .wornPlastic).bed(for: .wornPlastic)
        XCTAssertNotNil(worn?.hiss)
        XCTAssertNotNil(worn?.grains, "its scattered grit joins its hiss")
        XCTAssertNil(NoiseBenchSettings(texture: .silk).bed(for: .silk), "no noise, nothing to play")
    }

    func testTheBenchsSettingsReachTheLoop() {
        var settings = NoiseBenchSettings(texture: .rasp)
        settings.step = 0.012
        settings.depth = 0.4
        settings.color = .brown
        let hiss = settings.bed(for: .rasp)?.hiss
        XCTAssertEqual(hiss?.step ?? 0, 0.012, accuracy: 0.0001)
        XCTAssertEqual(hiss?.floor ?? 0, 0.6, accuracy: 0.0001)
        XCTAssertEqual(hiss?.color, .brown)
    }

    /// At the bench's extremes (finest hiss, densest grains) a loop stays a pattern Core
    /// Haptics takes in one go: dense loops get shorter.
    func testDenseLoopsStayWithinTheEventBudget() {
        for texture in RimTexture.all where texture.isNoisy {
            var settings = NoiseBenchSettings(texture: texture)
            settings.step = 0.004
            settings.density = 3
            guard let bed = settings.bed(for: texture) else { return XCTFail(texture.id) }
            let fitted = bed.fitted(density: 3)
            let events = fitted.recipe(density: 3).events.count
            XCTAssertLessThan(events, 820, "\(texture.id): \(events) events in \(fitted.seconds) s")
            XCTAssertGreaterThanOrEqual(fitted.seconds, 0.5, texture.id)
        }
    }

    func testRimTexturesAreDistinct() {
        XCTAssertEqual(Set(RimTexture.all.map(\.id)).count, RimTexture.all.count)
        XCTAssertTrue(StickFeel.n64Worn.textured(.rasp).isNoisy)
        XCTAssertFalse(StickFeel.n64Worn.isNoisy)
        XCTAssertNil(StickFeel.n64Worn.textured(.rasp).grain, "a noisy texture replaces the regular grit")
    }
}
