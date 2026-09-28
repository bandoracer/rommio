import Foundation
#if canImport(CoreHaptics)
import CoreHaptics
#endif

/// One haptic moment as data: taps (transients) and buzzes (continuous events) on a
/// timeline, with curves over them. Designs are written as recipes so they can be read,
/// tested and tuned; `pattern(strength:sharpnessBias:)` turns one into Core Haptics.
///
/// Intensity and sharpness are 0…1. Sharpness is roughly the actuator's drive frequency
/// (low: a deep thud, high: a crisp tick), so it changes how strong a tap feels too.
struct HapticRecipe: Hashable, Sendable {
    struct Event: Hashable, Sendable {
        /// Seconds from the recipe's start.
        var at: Double
        /// nil: a tap (transient). Otherwise a buzz this long (continuous, at most 30 s).
        var duration: Double?
        var intensity: Double
        var sharpness: Double
        /// Buzz envelope (seconds): how long it takes to rise and to fall away.
        var attack: Double?
        var decay: Double?
        var release: Double?
        /// A buzz that holds its intensity rather than decaying.
        var sustained: Bool?
    }

    /// A curve over the recipe's buzzes (intensity multiplies, sharpness adds).
    struct Curve: Hashable, Sendable {
        enum Parameter: Hashable, Sendable { case intensity, sharpness }
        struct Point: Hashable, Sendable {
            var time: Double
            var value: Double
        }
        var parameter: Parameter
        var at: Double
        /// Core Haptics honours the first 16 points of a curve.
        var points: [Point]
    }

    var events: [Event]
    var curves: [Curve] = []
    /// Sounds that colour the haptic, played in the same pattern.
    var sounds: [SoundCue] = []

    static let empty = HapticRecipe(events: [])

    /// A tap: one transient.
    static func tap(_ intensity: Double, _ sharpness: Double, at: Double = 0) -> HapticRecipe {
        HapticRecipe(events: [Event(at: at, intensity: intensity, sharpness: sharpness)])
    }

    /// A buzz: one continuous event.
    static func buzz(_ intensity: Double, _ sharpness: Double, for duration: Double, at: Double = 0,
                     attack: Double? = nil, decay: Double? = nil, release: Double? = nil, sustained: Bool? = nil) -> HapticRecipe {
        HapticRecipe(events: [Event(at: at, duration: duration, intensity: intensity, sharpness: sharpness,
                                    attack: attack, decay: decay, release: release, sustained: sustained)])
    }

    /// A curve over the recipe's buzzes, from (time, value) pairs.
    func curve(_ parameter: Curve.Parameter, at: Double = 0, _ points: [(Double, Double)]) -> HapticRecipe {
        var copy = self
        copy.curves.append(Curve(parameter: parameter, at: at, points: points.map { Curve.Point(time: $0.0, value: $0.1) }))
        return copy
    }

    /// The recipe with a sound on it.
    func sound(_ sound: SoundRecipe, volume: Double, at: Double = 0) -> HapticRecipe {
        var copy = self
        copy.sounds.append(SoundCue(at: at, sound: sound, volume: volume))
        return copy
    }

    /// Both recipes, on one timeline.
    static func + (lhs: HapticRecipe, rhs: HapticRecipe) -> HapticRecipe {
        HapticRecipe(events: lhs.events + rhs.events, curves: lhs.curves + rhs.curves, sounds: lhs.sounds + rhs.sounds)
    }

    /// The recipe moved later by `seconds`.
    func delayed(by seconds: Double) -> HapticRecipe {
        HapticRecipe(events: events.map { var e = $0; e.at += seconds; return e },
                     curves: curves.map { var c = $0; c.at += seconds; return c },
                     sounds: sounds.map { var s = $0; s.at += seconds; return s })
    }

    /// The recipe with every intensity × `intensity` and every sharpness + `sharpness`
    /// (clamped to 0…1): the same design, softer or harder. Sounds follow the intensity.
    func scaled(intensity factor: Double, sharpness shift: Double = 0) -> HapticRecipe {
        HapticRecipe(events: events.map { event in
            var e = event
            e.intensity = min(max(event.intensity * factor, 0), 1)
            e.sharpness = min(max(event.sharpness + shift, 0), 1)
            return e
        }, curves: curves, sounds: sounds.map { var s = $0; s.volume = min(max(s.volume * factor, 0), 1); return s })
    }

    /// When the last event ends (a tap counts as 20 ms).
    var duration: Double {
        events.map { $0.at + ($0.duration ?? 0.02) }.max() ?? 0
    }

    var isEmpty: Bool { events.isEmpty && sounds.isEmpty }
}

#if canImport(CoreHaptics)
extension HapticRecipe {
    /// The recipe's haptic as a Core Haptics pattern: intensities × `strength`, sharpness +
    /// `sharpnessBias`, both clamped to 0…1.
    func hapticPattern(strength: Double = 1, sharpnessBias: Double = 0) throws -> CHHapticPattern {
        func unit(_ value: Double) -> Float { Float(min(max(value, 0), 1)) }
        let hapticEvents = events.map { event -> CHHapticEvent in
            var parameters = [
                CHHapticEventParameter(parameterID: .hapticIntensity, value: unit(event.intensity * strength)),
                CHHapticEventParameter(parameterID: .hapticSharpness, value: unit(event.sharpness + sharpnessBias)),
            ]
            if let attack = event.attack { parameters.append(CHHapticEventParameter(parameterID: .attackTime, value: Float(attack))) }
            if let decay = event.decay { parameters.append(CHHapticEventParameter(parameterID: .decayTime, value: Float(decay))) }
            if let release = event.release { parameters.append(CHHapticEventParameter(parameterID: .releaseTime, value: Float(release))) }
            if let sustained = event.sustained { parameters.append(CHHapticEventParameter(parameterID: .sustained, value: sustained ? 1 : 0)) }
            if let duration = event.duration {
                return CHHapticEvent(eventType: .hapticContinuous, parameters: parameters, relativeTime: event.at, duration: min(duration, 30))
            }
            return CHHapticEvent(eventType: .hapticTransient, parameters: parameters, relativeTime: event.at)
        }
        let hapticCurves = curves.map { curve in
            CHHapticParameterCurve(
                parameterID: curve.parameter == .intensity ? .hapticIntensityControl : .hapticSharpnessControl,
                controlPoints: curve.points.prefix(16).map {
                    CHHapticParameterCurve.ControlPoint(relativeTime: $0.time, value: Float($0.value))
                },
                relativeTime: curve.at)
        }
        return try CHHapticPattern(events: hapticEvents, parameterCurves: hapticCurves)
    }

    /// The recipe's sounds as a pattern, at their volume × `level`, through `resource`; nil
    /// when there's nothing to play.
    func soundPattern(level: Double, resource: (SoundRecipe) -> CHHapticAudioResourceID?) throws -> CHHapticPattern? {
        guard level > 0 else { return nil }
        let audioEvents: [CHHapticEvent] = sounds.compactMap { cue in
            guard let id = resource(cue.sound) else { return nil }
            let volume = Float(min(max(cue.volume * level, 0), 1))
            return CHHapticEvent(audioResourceID: id, parameters: [CHHapticEventParameter(parameterID: .audioVolume, value: volume)],
                                 relativeTime: cue.at)
        }
        guard !audioEvents.isEmpty else { return nil }
        return try CHHapticPattern(events: audioEvents, parameters: [])
    }
}
#endif
