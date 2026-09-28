import XCTest
#if canImport(CoreHaptics)
import CoreHaptics
#endif
import RommioPlayerKit
@testable import RommioUI

final class HapticsPlaygroundTests: XCTestCase {
    private var everyRecipe: [(String, HapticRecipe)] {
        ButtonMaterial.all.flatMap { [("\($0.id) press", $0.press), ("\($0.id) release", $0.release)] }
            + DpadFeel.all.flatMap { [("\($0.id) direction", $0.direction), ("\($0.id) diagonal", $0.diagonal), ("\($0.id) center", $0.center)] }
            + StickFeel.all.flatMap { [("\($0.id) notch", $0.notch), ("\($0.id) edge", $0.edge), ("\($0.id) center", $0.center)] }
    }

    func testEveryDesignIsAValidPattern() throws {
        for (name, recipe) in everyRecipe where !recipe.isEmpty {
            for event in recipe.events {
                XCTAssertTrue((0...1).contains(event.intensity), "\(name) intensity")
                XCTAssertTrue((0...1).contains(event.sharpness), "\(name) sharpness")
                XCTAssertLessThanOrEqual(event.duration ?? 0, 30, name)
            }
            for curve in recipe.curves { XCTAssertLessThanOrEqual(curve.points.count, 16, name) }
            // A press or a tick is over quickly: a long one would smear into the next.
            XCTAssertLessThanOrEqual(recipe.duration, 0.12, name)
            #if canImport(CoreHaptics)
            XCTAssertNoThrow(try recipe.hapticPattern(), name)
            XCTAssertNoThrow(try recipe.hapticPattern(strength: 0.2, sharpnessBias: -0.3), name)
            #endif
        }
    }

    func testEverySoundIsAShortPitchlessClick() {
        XCTAssertEqual(Set(SoundRecipe.all.map(\.id)).count, SoundRecipe.all.count)
        for sound in SoundRecipe.all {
            for style in SoundStyle.allCases {
                let name = "\(sound.id) \(style)"
                let wav = sound.wav(style: style)
                XCTAssertEqual(String(decoding: wav.prefix(4), as: UTF8.self), "RIFF", name)
                XCTAssertEqual(String(decoding: wav[8..<12], as: UTF8.self), "WAVE", name)
                let samples = sound.samples(style: style)
                XCTAssertEqual(wav.count, 44 + samples.count * 2, name)
                XCTAssertLessThan(Double(samples.count) / SoundRecipe.sampleRate, 0.1, "\(name) is a click, not a sound effect")
                let peak = samples.map { abs(Int($0)) }.max() ?? 0
                XCTAssertEqual(Double(peak) / 32_767, 0.9, accuracy: 0.01, "\(name) is normalised")
                XCTAssertEqual(samples.last, 0, "\(name) fades out without a click")
                XCTAssertLessThan(periodicity(samples), 0.45, "\(name) has a pitch")
            }
            XCTAssertNotEqual(sound.samples(take: 0), sound.samples(take: 1), "\(sound.id): takes differ")
        }
    }

    /// How periodic a sound is: its autocorrelation's highest point after first crossing
    /// zero (a tone comes back near 1 a period later; noise stays low).
    private func periodicity(_ samples: [Int16]) -> Double {
        let x = samples.map { Double($0) }
        let energy = x.reduce(0) { $0 + $1 * $1 }
        guard energy > 0 else { return 0 }
        func r(_ lag: Int) -> Double {
            var sum = 0.0
            for n in 0..<(x.count - lag) { sum += x[n] * x[n + lag] }
            return sum / energy
        }
        let maxLag = min(x.count / 2, Int(SoundRecipe.sampleRate * 0.01))
        guard let crossing = (1..<maxLag).first(where: { r($0) < 0 }) else { return 0 }
        return (crossing..<maxLag).map(r).max() ?? 0
    }

    func testAToneWouldFailThePitchCheck() {
        // The check itself: a decaying 1 kHz sine is plainly periodic.
        let tone = (0..<2_400).map { n -> Int16 in
            let t = Double(n) / SoundRecipe.sampleRate
            return Int16(sin(2 * .pi * 1_000 * t) * exp(-t / 0.02) * 20_000)
        }
        XCTAssertGreaterThan(periodicity(tone), 0.6)
    }

    func testButtonsCarryTheirSoundsAndScaleThem() {
        for material in ButtonMaterial.all where material != .today {
            XCTAssertFalse(material.press.sounds.isEmpty, material.id)
        }
        XCTAssertTrue(ButtonMaterial.today.press.sounds.isEmpty, "today's tap has no sound")
        let soft = ButtonMaterial.tactSwitch.press.scaled(intensity: 0.5)
        XCTAssertEqual(soft.sounds.first?.volume ?? 0, (ButtonMaterial.tactSwitch.press.sounds.first?.volume ?? 0) * 0.5, accuracy: 1e-9)
        #if canImport(CoreHaptics)
        for material in ButtonMaterial.all {
            XCTAssertNoThrow(try material.press.hapticPattern(), material.id)
            if material != .today {
                XCTAssertNotNil(try material.press.soundPattern(level: 0.5, resource: { _ in 1 }), material.id)
            }
            XCTAssertNil(try material.press.soundPattern(level: 0, resource: { _ in 1 }), "no sound at level 0")
        }
        #endif
    }

    func testEveryStickHasATextureAtItsRim() {
        for feel in StickFeel.all where feel != .today {
            XCTAssertTrue(feel.grain != nil || feel.hum != nil, "\(feel.id) has nothing at the rim")
        }
        for texture in RimTexture.all where texture != .designed {
            XCTAssertTrue(texture.grain != nil || texture.hum != nil, texture.id)
            if let grain = texture.grain {
                XCTAssertTrue(grain.intensity.lowerBound >= 0 && grain.intensity.upperBound <= 1, texture.id)
            }
        }
        XCTAssertEqual(StickFeel.dualShock.textured(.gravel).grain, RimTexture.gravel.grain)
        XCTAssertEqual(StickFeel.dualShock.textured(.designed), StickFeel.dualShock)
    }

    func testARumbleStripTicksInPairs() {
        let feel = StickFeel.circlePad.textured(.rumbleStrip)
        var texture = StickTexture()
        // On the rim (the 3DS pad's full tilt starts at 93% of its travel), no push.
        let radius = feel.profile.travel * 0.94
        var angles: [Double] = []
        for tenth in 0...1_800 {
            let degree = Double(tenth) / 10
            let angle = degree * .pi / 180
            for cue in texture.update(dx: cos(angle) * radius, dy: sin(angle) * radius, feel: feel) {
                if case .grain = cue { angles.append(degree) }
            }
        }
        let gaps = zip(angles.dropFirst(), angles).map { $0 - $1 }
        XCTAssertGreaterThan(gaps.count, 6)
        // Short, long, short, long: the pair's gap and the space between pairs.
        let short = gaps.enumerated().filter { $0.offset.isMultiple(of: 2) == gaps.first.map { $0 < 6 } ?? true }.map(\.element)
        XCTAssertTrue(short.allSatisfy { $0 < 6 }, "\(gaps)")
    }

    func testEveryPressStartsWithACrispContact() {
        // A press that opens with a deep buzz, or a click 15–20 ms in, reads as late.
        for material in ButtonMaterial.all {
            let first = material.press.events.filter { $0.at == 0 && $0.duration == nil }
            XCTAssertFalse(first.isEmpty, "\(material.id) has no tap at 0")
            XCTAssertGreaterThanOrEqual(first.map(\.sharpness).max() ?? 0, 0.45, "\(material.id) opens too dull")
            let late = material.press.events.map(\.at).max() ?? 0
            XCTAssertLessThanOrEqual(late, 0.04, "\(material.id) finishes its press late")
        }
        XCTAssertLessThanOrEqual(PressIntent.hold, 0.015)
    }

    func testEveryConsoleHasAFullPad() {
        for console in ConsoleFeel.all {
            XCTAssertFalse(console.face.isEmpty, console.id)
            XCTAssertLessThanOrEqual(console.face.count, 4, console.id)
        }
        XCTAssertEqual(Set(ConsoleFeel.all.map(\.id)).count, ConsoleFeel.all.count)
        XCTAssertEqual(Set(ButtonMaterial.all.map(\.id)).count, ButtonMaterial.all.count)
        XCTAssertEqual(Set(RumbleScene.all.map(\.id)).count, RumbleScene.all.count)
    }

    // MARK: Rumble

    func testTwoVoicesSpinUpHoldAndWindDownWithTheCore() {
        var rumble = TwoVoiceRumble()
        let start = rumble.frame([RumbleCall(motor: .strong, strength: 1)])
        XCTAssertEqual(start.kicks.count, 1, "a motor starting hard kicks")
        XCTAssertGreaterThan(start.strong ?? 0, 0)
        XCTAssertLessThan(start.strong ?? 1, 0.6, "a motor spins up, it doesn't jump")
        var level = start.strong ?? 0
        for _ in 0..<30 { if let next = rumble.frame([]).strong { level = next } }
        XCTAssertEqual(level, 1, accuracy: RumbleMotors.threshold + 0.005, "held at the core's strength")
        // Off: it winds down over a few frames, then stops once silent long enough.
        let off = rumble.frame([RumbleCall(motor: .strong, strength: 0)])
        XCTAssertGreaterThan(off.strong ?? 1, 0.5)
        XCTAssertFalse(off.stop)
        var stoppedAt: Int?
        for frame in 1...90 where stoppedAt == nil {
            if rumble.frame([]).stop { stoppedAt = frame }
        }
        XCTAssertNotNil(stoppedAt)
        XCTAssertGreaterThanOrEqual(stoppedAt ?? 0, RumbleMotors.idleFrames - 1)
        XCTAssertFalse(rumble.running)
    }

    func testMotorsSpinUpFasterThanTheyWindDown() {
        var up = MotorRamp.strong, down = MotorRamp.strong
        for _ in 0..<60 { _ = down.step(toward: 1) }
        var rising = 0, falling = 0
        while up.step(toward: 1) < 0.95 { rising += 1 }
        while down.step(toward: 0) > 0.05 { falling += 1 }
        XCTAssertGreaterThan(falling, rising)
        XCTAssertLessThan(rising, 12, "up to speed within a fifth of a second")
        var weak = MotorRamp.weak, quick = 0
        while weak.step(toward: 1) < 0.95 { quick += 1 }
        XCTAssertLessThan(quick, rising, "the small motor is quicker")
    }

    func testTheBlendIsNeverWeakerThanEitherMotorAlone() {
        let both = BlendedRumble.mix(strong: 1, weak: 1)
        XCTAssertEqual(both.level, 1, accuracy: 1e-9)
        for strength in [0.2, 0.5, 0.8] {
            let together = BlendedRumble.mix(strong: strength, weak: strength)
            XCTAssertGreaterThanOrEqual(together.level, BlendedRumble.mix(strong: strength, weak: 0).level)
            XCTAssertGreaterThanOrEqual(together.level, BlendedRumble.mix(strong: 0, weak: strength).level)
        }
        // Deep for the strong motor, buzzy for the weak, in between for both.
        XCTAssertEqual(BlendedRumble.mix(strong: 1, weak: 0).sharpness, RumbleMotors.strongSharpness, accuracy: 1e-9)
        XCTAssertEqual(BlendedRumble.mix(strong: 0, weak: 1).sharpness, RumbleMotors.weakSharpness, accuracy: 1e-9)
        let mixed = BlendedRumble.mix(strong: 0.5, weak: 0.5).sharpness
        XCTAssertGreaterThan(mixed, RumbleMotors.strongSharpness)
        XCTAssertLessThan(mixed, RumbleMotors.weakSharpness)
        XCTAssertEqual(RumbleMotors.level(0.25), pow(0.25, RumbleMotors.gamma), accuracy: 1e-9)
        XCTAssertGreaterThan(RumbleMotors.level(0.1), 0.2, "low strengths are still felt")
    }

    func testTwoVoicesKeepEachMotorsOwnStrengthWithTheBuzzAt80Percent() {
        var rumble = TwoVoiceRumble()
        var strong = 0.0, weak = 0.0
        let full = [RumbleCall(motor: .strong, strength: 1), RumbleCall(motor: .weak, strength: 1)]
        for frame in 0..<40 {
            let output = rumble.frame(frame == 0 ? full : [])
            strong = output.strong ?? strong
            weak = output.weak ?? weak
        }
        XCTAssertEqual(strong, 1, accuracy: 0.03)
        XCTAssertEqual(weak, RumbleMotors.weakCap, accuracy: 0.03)
    }

    func testRumbleIgnoresRepeatsFromCoresThatReportEveryFrame() {
        var voices = TwoVoiceRumble()
        let calls = [RumbleCall(motor: .strong, strength: 0.5), RumbleCall(motor: .weak, strength: 0.5)]
        let first = voices.frame(calls)
        XCTAssertNotNil(first.strong)
        XCTAssertNotNil(first.weak)
        // Once the motors are up to speed, the same report every frame sends nothing.
        for _ in 0..<40 { _ = voices.frame(calls) }
        for _ in 0..<30 { XCTAssertEqual(voices.frame(calls), RumbleOutput()) }
    }

    func testTodayPlaysOneTapPerReport() {
        // A held rumble (reported once) is a single tap…
        let held = RumbleScene.arwingHit.timeline.flatMap { OneTapRumble.taps($0) }
        XCTAssertEqual(held.count, 3, "three hits, one tap each")
        // …and a core reporting every frame is a stream of taps: two a frame (both motors),
        // 120 a second.
        let stream = RumbleScene.drillGears.timeline.prefix(50).flatMap { OneTapRumble.taps($0) }
        XCTAssertEqual(stream.count, 100)
    }

    func testScenesReportTheWayTheirCoresDo() {
        // gambatte, mupen64plus, pcsx_rearmed: only on a change.
        let pinball = RumbleScene.pinballBumpers.timeline
        XCTAssertLessThan(pinball.filter { !$0.isEmpty }.count, pinball.count / 3)
        // mGBA: both motors every frame.
        for frame in RumbleScene.drillGears.timeline.dropLast() { XCTAssertEqual(frame.count, 2) }
        for scene in RumbleScene.all {
            XCTAssertGreaterThan(scene.seconds, 0.5, scene.id)
            XCTAssertLessThan(scene.seconds, 6, scene.id)
            XCTAssertEqual(scene.timeline.last?.contains { $0.strength > 0 }, false, "\(scene.id) ends with the motors off")
        }
    }

    // MARK: Sticks

    /// Sweeps the thumb around the rim from `from`° to `to`° in 1° steps, `reach` travels
    /// from the centre.
    private func sweep(_ feel: StickFeel, from: Int, to: Int, reach: Double = 1) -> (cues: [StickTexture.Cue], texture: StickTexture) {
        var texture = StickTexture()
        let radius = feel.profile.travel * reach
        var cues: [StickTexture.Cue] = []
        for degree in from...to {
            let angle = Double(degree) * .pi / 180
            cues += texture.update(dx: cos(angle) * radius, dy: sin(angle) * radius, feel: feel)
        }
        return (cues, texture)
    }

    private func grains(_ cues: [StickTexture.Cue]) -> [Double] {
        cues.compactMap { if case let .grain(intensity, _) = $0 { intensity } else { nil } }
    }

    func testTheOctagonClicksIntoEachNotchAlongTheRim() {
        var (cues, texture) = sweep(.n64Worn, from: 0, to: 90)
        XCTAssertEqual(cues.filter { if case .edge = $0 { true } else { false } }.count, 1)
        XCTAssertEqual(cues.filter { if case .notch = $0 { true } else { false } }.count, 3, "0°, 45° and 90°")
        XCTAssertGreaterThan(grains(cues).count, 5)
        XCTAssertEqual(texture.release(), [.center(strength: 1)], "released from full tilt: a full snap back")
    }

    func testPushingPastTheRimIsMoreForceAndMoreTexture() {
        let light = sweep(.n64Worn, from: 0, to: 90, reach: 0.92)
        let hard = sweep(.n64Worn, from: 0, to: 90, reach: 1.8)
        XCTAssertEqual(light.texture.force, 0, accuracy: 0.05)
        XCTAssertEqual(hard.texture.force, 1)
        let lightGrains = grains(light.cues), hardGrains = grains(hard.cues)
        XCTAssertGreaterThan(hardGrains.count, lightGrains.count, "denser grit")
        XCTAssertGreaterThan(hardGrains.reduce(0, +) / Double(hardGrains.count),
                             lightGrains.reduce(0, +) / Double(lightGrains.count), "stronger grit")
        let hardNotch = hard.cues.compactMap { if case let .notch(force, _) = $0 { force } else { nil } }
        XCTAssertEqual(hardNotch.last, 1, "notches clunk harder under force")
    }

    func testReachingTheRimFastIsASharperSmack() {
        let feel = StickFeel.n64New
        let travel = feel.profile.travel
        var slow = StickTexture(), fast = StickTexture()
        _ = slow.update(dx: travel * 0.5, dy: 0, vx: travel * 1, vy: 0, feel: feel)
        var medium = StickTexture()
        _ = fast.update(dx: travel * 0.5, dy: 0, vx: travel * 40, vy: 0, feel: feel)
        _ = medium.update(dx: travel * 0.5, dy: 0, vx: travel * 12, vy: 0, feel: feel)
        let slowEdge = slow.update(dx: travel, dy: 0, vx: travel * 1, vy: 0, feel: feel)
        let fastEdge = fast.update(dx: travel, dy: 0, vx: travel * 40, vy: 0, feel: feel)
        let mediumEdge = medium.update(dx: travel, dy: 0, vx: travel * 12, vy: 0, feel: feel)
        XCTAssertTrue(slowEdge.contains(.edge(impact: 0)))
        XCTAssertTrue(fastEdge.contains(.edge(impact: 1)))
        // An ordinary push to the rim is in the middle of the range, not at its top.
        guard case let .edge(impact) = mediumEdge.first(where: { if case .edge = $0 { true } else { false } }) else {
            return XCTFail("no edge")
        }
        XCTAssertEqual(impact, 0.35, accuracy: 0.05)
        // A tuning that asks for a slower full hit reaches it sooner.
        XCTAssertEqual(StickTuning(fullHit: 12).impact(12), 1)
    }

    func testTheSpringBackFollowsHowFarOutTheStickWas() {
        var texture = StickTexture()
        XCTAssertEqual(texture.update(dx: 1, dy: 0, feel: .n64New), [], "inside the dead zone")
        XCTAssertEqual(texture.release(), [])
        _ = texture.update(dx: feel(.n64New, reach: 0.4), dy: 0, feel: .n64New)
        XCTAssertEqual(texture.release(), [.center(strength: 0.4)])
        _ = texture.update(dx: 20, dy: 0, feel: .n64New)
        // Drawn back quickly past the dead zone: a firm return.
        let back = texture.update(dx: 1, dy: 0, vx: -StickFeel.n64New.profile.travel * 10, vy: 0, feel: .n64New)
        XCTAssertEqual(back, [.center(strength: 1)])
    }

    private func feel(_ feel: StickFeel, reach: Double) -> Double { feel.profile.travel * reach }

    func testGlidingFasterAlongTheRimMakesStrongerGrit() {
        let feel = StickFeel.n64Worn
        let radius = feel.profile.travel
        func grit(degreesPerSecond: Double) -> Double {
            var texture = StickTexture()
            var total = 0.0, count = 0.0
            for degree in 0...90 {
                let angle = Double(degree) * .pi / 180
                let speed = degreesPerSecond * .pi / 180 * radius
                for cue in texture.update(dx: cos(angle) * radius, dy: sin(angle) * radius,
                                          vx: -sin(angle) * speed, vy: cos(angle) * speed, feel: feel) {
                    if case let .grain(intensity, _) = cue { total += intensity; count += 1 }
                }
            }
            return total / max(count, 1)
        }
        XCTAssertGreaterThan(grit(degreesPerSecond: 700), grit(degreesPerSecond: 60))
    }

    func testAnyContactWithTheRimIsAtLeastTheFloor() {
        let feel = StickFeel.circlePad.textured(.satin)
        let (cues, _) = sweep(feel, from: 0, to: 90, reach: 0.94)
        let intensities = grains(cues)
        XCTAssertFalse(intensities.isEmpty)
        XCTAssertTrue(intensities.allSatisfy { $0 >= StickTuning().contactFloor }, "\(intensities)")
        var none = StickTuning()
        none.contactFloor = 0
        var texture = StickTexture()
        let radius = feel.profile.travel * 0.94
        var quiet: [Double] = []
        for degree in 0...90 {
            let angle = Double(degree) * .pi / 180
            for cue in texture.update(dx: cos(angle) * radius, dy: sin(angle) * radius, feel: feel, tuning: none) {
                if case let .grain(intensity, _) = cue { quiet.append(intensity) }
            }
        }
        XCTAssertLessThan(quiet.min() ?? 1, StickTuning().contactFloor, "without a floor, a light contact is lighter")
    }

    func testTheHumFollowsMovementAboveTheFloor() {
        let feel = StickFeel.pspNub
        let hum = feel.hum!
        let radius = feel.profile.travel
        var texture = StickTexture()
        _ = texture.update(dx: radius, dy: 0, feel: feel)
        XCTAssertEqual(texture.humLevel(hum), 0, "no hum while the thumb rests")
        func level(degreesPerSecond: Double, reach: Double) -> Double {
            var texture = StickTexture()
            let speed = degreesPerSecond * .pi / 180 * radius * reach
            _ = texture.update(dx: radius * reach, dy: 0, vx: 0, vy: speed, feel: feel)
            return texture.humLevel(hum)
        }
        let slow = level(degreesPerSecond: 40, reach: 1), fast = level(degreesPerSecond: 600, reach: 1)
        XCTAssertGreaterThanOrEqual(slow, StickTuning().contactFloor * 0.8 - 1e-9)
        XCTAssertGreaterThan(fast, slow)
        XCTAssertGreaterThan(level(degreesPerSecond: 600, reach: 1.8), fast, "more push, more hum")
    }

    func testARoundGateHasNoNotches() {
        let (cues, _) = sweep(.circlePad, from: 0, to: 180)
        XCTAssertFalse(cues.contains { if case .notch = $0 { true } else { false } })
        XCTAssertGreaterThan(grains(cues).count, 7)
    }

    // MARK: Material cards

    func testAHeldCardPressesAndReleases() {
        var intent = PressIntent()
        intent.begin()
        XCTAssertEqual(intent.holdElapsed(), [.press])
        intent.moved(3)
        XCTAssertEqual(intent.phase, .pressed, "a wobble is still a press")
        XCTAssertEqual(intent.end(moved: 3), [.release])
    }

    func testAScrollThatStartsOnACardNeverPresses() {
        var intent = PressIntent()
        intent.begin()
        intent.moved(20)
        XCTAssertEqual(intent.holdElapsed(), [])
        XCTAssertEqual(intent.end(moved: 120), [])
    }

    func testAScrollThatTakesOverAHeldCardLetsGoSilently() {
        var intent = PressIntent()
        intent.begin()
        XCTAssertEqual(intent.holdElapsed(), [.press])
        intent.moved(30)
        XCTAssertEqual(intent.phase, .scrolling)
        XCTAssertEqual(intent.end(moved: 30), [])
    }

    func testAQuickTapStillPressesAndReleases() {
        var intent = PressIntent()
        intent.begin()
        XCTAssertEqual(intent.end(moved: 0), [.press, .releaseAfterTap])
        XCTAssertEqual(intent.phase, .idle)
    }

    // MARK: D-pads

    func testAPivotPadNeedsADeliberateDiagonal() {
        let feel = DpadFeel.nintendoCross
        var pad = DpadTracker()
        XCTAssertEqual(pad.update(x: 0.3, y: 0, feel: feel), [], "inside the dead zone")
        XCTAssertEqual(pad.update(x: 0.6, y: 0, feel: feel), [feel.direction])
        XCTAssertEqual(pad.direction, .right)
        // 35° is past the right sector (30°) but inside its hold: still right.
        XCTAssertEqual(pad.update(x: 0.6 * cos(.pi * 35 / 180), y: 0.6 * sin(.pi * 35 / 180), feel: feel), [])
        XCTAssertEqual(pad.direction, .right)
        XCTAssertEqual(pad.update(x: 0.5, y: 0.5, feel: feel), [feel.diagonal])
        XCTAssertEqual(pad.direction, .downRight)
        // Back toward the centre: engaged until well inside the dead zone.
        XCTAssertEqual(pad.update(x: 0.3 * 0.7071, y: 0.3 * 0.7071, feel: feel), [], "0.3 out: past the 0.26 return")
        XCTAssertEqual(pad.update(x: 0.1, y: 0, feel: feel), [feel.center])
        XCTAssertEqual(pad.release(feel: feel), [])
    }

    func testTheRockingDiscHasAWideDeadZone() {
        var pad = DpadTracker()
        XCTAssertEqual(pad.update(x: 0.4, y: 0, feel: .rockingDisc), [])
        XCTAssertEqual(pad.update(x: 0.5, y: 0, feel: .rockingDisc), [DpadFeel.rockingDisc.direction])
    }

    func testAnArcadeStickSweepsSideToSideOnItsSwitches() {
        let feel = DpadFeel.arcadeStick
        var pad = DpadTracker()
        var played: [HapticRecipe] = []
        // Left to right across the pad, the thumb wobbling up and down as it goes.
        for step in 0...40 {
            let x = -0.8 + 1.6 * Double(step) / 40
            let wobble = 0.3 * sin(Double(step))
            played += pad.update(x: x, y: wobble, feel: feel)
            XCTAssertFalse([.up, .down, .upLeft, .upRight, .downLeft, .downRight].contains(pad.direction), "no vertical switch at \(step)")
        }
        // The left switch closes, opens, then the right one closes: nothing else.
        XCTAssertEqual(played, [feel.direction, feel.center, feel.direction])
        XCTAssertEqual(pad.direction, .right)
        XCTAssertEqual(pad.release(feel: feel), [feel.center])
    }

    func testADiagonalOnSwitchesIsTwoClicks() {
        let feel = DpadFeel.arcadeStick
        var pad = DpadTracker()
        XCTAssertEqual(pad.update(x: 0.7, y: 0.7, feel: feel), [feel.direction, feel.direction])
        XCTAssertEqual(pad.direction, .downRight)
        XCTAssertEqual(pad.release(feel: feel), [feel.center, feel.center])
    }

    func testDirectionsAndTheirFeel() {
        XCTAssertEqual(DpadDirection.sector(0, diagonal: 30), .right)
        XCTAssertEqual(DpadDirection.sector(45, diagonal: 30), .downRight)
        XCTAssertEqual(DpadDirection.sector(28, diagonal: 30), .right, "the diagonal starts at 30°")
        XCTAssertEqual(DpadDirection.sector(270, diagonal: 30), .up)
        XCTAssertEqual(DpadDirection.sector(225, diagonal: 45), .upLeft)
        let feel = DpadFeel.nintendoCross
        XCTAssertEqual(DpadDirection.right.recipe(from: .none, feel), feel.direction)
        XCTAssertEqual(DpadDirection.downRight.recipe(from: .right, feel), feel.diagonal)
        XCTAssertEqual(DpadDirection.none.recipe(from: .downRight, feel), feel.center)
        XCTAssertNil(DpadDirection.right.recipe(from: .right, feel))
    }
}
