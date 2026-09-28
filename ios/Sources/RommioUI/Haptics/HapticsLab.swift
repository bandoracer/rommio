import Foundation
import Observation

/// How the playground plays rumble.
enum RumbleRenderer: String, CaseIterable, Identifiable, Sendable {
    /// One continuous voice per motor (`TwoVoiceRumble`).
    case twoVoices
    /// Both motors as one voice (`BlendedRumble`).
    case blend
    /// One tap per report, as the player does today (`OneTapRumble`).
    case oneTap

    var id: String { rawValue }
    var title: String {
        switch self {
        case .blend: "Blend"
        case .twoVoices: "Two voices"
        case .oneTap: "Today"
        }
    }
}

/// The haptics playground's state: the engine, rumble playback and the live voices.
@MainActor
@Observable
final class HapticsLab {
    var strength = 1.0 {
        didSet { engine.strength = strength }
    }
    var sharpnessBias = 0.0 {
        didSet { engine.sharpnessBias = sharpnessBias }
    }
    /// Sounds colouring the buttons, d-pads and sticks, and how loud.
    var soundsOn = true {
        didSet { engine.soundsOn = soundsOn }
    }
    var soundLevel = 0.35 {
        didSet { engine.soundLevel = soundLevel }
    }
    var soundStyle: SoundStyle = .invisible {
        didSet { engine.soundStyle = soundStyle }
    }
    var renderer: RumbleRenderer = .twoVoices {
        didSet { if renderer != oldValue { stopRumble() } }
    }
    /// How sticks respond to speed and push.
    var stickTuning = StickTuning()
    /// What the stick last measured, for the playground's readout.
    private(set) var stickImpact: Double?
    private(set) var stickForce = 0.0
    private(set) var stickGlide = 0.0
    /// The scene playing, if any.
    private(set) var playingScene: String?
    /// The motors' strengths as the core last set them (for the meters).
    private(set) var meterStrong = 0.0
    private(set) var meterWeak = 0.0

    @ObservationIgnored let engine = HapticsEngine()
    @ObservationIgnored private lazy var blendVoice = engine.voice(sharpness: RumbleMotors.strongSharpness)
    @ObservationIgnored private lazy var strongVoice = engine.voice(sharpness: RumbleMotors.strongSharpness)
    @ObservationIgnored private lazy var weakVoice = engine.voice(sharpness: RumbleMotors.weakSharpness)
    @ObservationIgnored private var humVoice: HapticVoice?
    @ObservationIgnored private var blend = BlendedRumble()
    @ObservationIgnored private var twoVoices = TwoVoiceRumble()
    /// Scene frames still to play, and what the motor pad reported since the last frame.
    @ObservationIgnored private var queued: [[RumbleCall]] = []
    @ObservationIgnored private var padCalls: [RumbleCall] = []
    @ObservationIgnored private var padHeld = false
    /// Runs the renderer at 60 fps while anything plays or winds down.
    @ObservationIgnored private var ticker: Task<Void, Never>?

    var isSupported: Bool { HapticsEngine.isSupported }

    init() {}

    /// Starts the engine ahead of the first touch (starting it blocks for a moment), makes
    /// the players for every fixed design, and prepares the sounds in the background. Again
    /// when the app comes back: the system stops the engine in the background.
    func prepare() {
        #if canImport(CoreHaptics)
        engine.prewarm(Self.designs)
        engine.prepareSounds()
        #endif
    }

    /// Every button, d-pad and stick design as it plays unscaled.
    static let designs: [HapticRecipe] = ButtonMaterial.all.flatMap { [$0.press, $0.release] }
        + DpadFeel.all.flatMap { [$0.direction, $0.diagonal, $0.center] }
        + StickFeel.all.flatMap { [$0.notch, $0.edge, $0.center] }

    // MARK: Latency

    /// The last press's time from the screen registering the touch to its haptic starting
    /// (milliseconds), and the average of the last 20.
    private(set) var lastLatency: Double?
    private(set) var averageLatency: Double?
    @ObservationIgnored private var latencies: [Double] = []

    /// A haptic just started for a touch registered at `timestamp` (system uptime).
    func noteLatency(since timestamp: TimeInterval) {
        let milliseconds = (ProcessInfo.processInfo.systemUptime - timestamp) * 1000
        guard milliseconds >= 0, milliseconds < 1_000 else { return }
        latencies = Array((latencies + [milliseconds]).suffix(20))
        lastLatency = milliseconds
        averageLatency = latencies.reduce(0, +) / Double(latencies.count)
    }

    func play(_ recipe: HapticRecipe) {
        engine.play(recipe)
    }

    // MARK: Rumble

    /// Plays a scene through the chosen renderer, frame by frame at 60 fps.
    func play(_ scene: RumbleScene) {
        stopRumble()
        playingScene = scene.id
        queued = scene.timeline
        startTicker()
    }

    /// Drives both motors from a finger (the motor pad): reported on change, like most cores.
    func drive(strong: Double, weak: Double) {
        if playingScene != nil { stopRumble() }
        padHeld = true
        if abs(strong - meterStrong) > 0.001 { padCalls.append(RumbleCall(motor: .strong, strength: strong)) }
        if abs(weak - meterWeak) > 0.001 { padCalls.append(RumbleCall(motor: .weak, strength: weak)) }
        startTicker()
    }

    /// The finger lifted from the motor pad: the motors wind down.
    func releaseMotors() {
        padHeld = false
        padCalls += [RumbleCall(motor: .strong, strength: 0), RumbleCall(motor: .weak, strength: 0)]
    }

    private var rendererRunning: Bool { twoVoices.running || blend.running }

    private func startTicker() {
        guard ticker == nil else { return }
        ticker = Task { [weak self] in
            let clock = ContinuousClock()
            var deadline = clock.now
            var windDown = 0
            while !Task.isCancelled {
                guard let self else { return }
                var calls = self.queued.isEmpty ? [] : self.queued.removeFirst()
                calls += self.padCalls
                self.padCalls = []
                self.apply(calls)
                // Done when the scene and the finger are, and the motors have wound down
                // (at most five seconds after).
                if self.queued.isEmpty, !self.padHeld, self.padCalls.isEmpty {
                    windDown += 1
                    if !self.rendererRunning || windDown > 300 { break }
                }
                deadline += .microseconds(16_667)
                try? await clock.sleep(until: deadline)
            }
            // A cancelled ticker leaves the state to whatever replaced it.
            guard !Task.isCancelled else { return }
            self?.tickerEnded()
        }
    }

    private func tickerEnded() {
        ticker = nil
        playingScene = nil
        meterStrong = 0
        meterWeak = 0
    }

    func stopRumble() {
        ticker?.cancel()
        ticker = nil
        queued = []
        padCalls = []
        padHeld = false
        playingScene = nil
        blendVoice.silence()
        strongVoice.silence()
        weakVoice.silence()
        blend = BlendedRumble()
        twoVoices = TwoVoiceRumble()
        meterStrong = 0
        meterWeak = 0
    }

    private func apply(_ calls: [RumbleCall]) {
        for call in calls {
            switch call.motor {
            case .strong: meterStrong = call.strength
            case .weak: meterWeak = call.strength
            }
        }
        switch renderer {
        case .blend:
            let output = blend.frame(calls)
            output.kicks.forEach(engine.play)
            if output.level != nil || output.sharpness != nil {
                blendVoice.set(output.level ?? blendVoice.level, sharpness: output.sharpness)
            }
            if output.stop { blendVoice.silence() }
        case .twoVoices:
            let output = twoVoices.frame(calls)
            output.kicks.forEach(engine.play)
            if let level = output.strong { strongVoice.set(level) }
            if let level = output.weak { weakVoice.set(level) }
            if output.stop {
                strongVoice.silence()
                weakVoice.silence()
            }
        case .oneTap:
            OneTapRumble.taps(calls).forEach(engine.play)
        }
    }

    // MARK: Sticks

    func readStick(_ texture: StickTexture) {
        if stickImpact != texture.lastImpact { stickImpact = texture.lastImpact }
        if stickForce != texture.force { stickForce = texture.force }
        if stickGlide != texture.glide { stickGlide = texture.glide }
    }

    /// A stick's hum at `level` (0 quiets it; no hum stops it: the thumb lifted).
    func hum(_ hum: StickFeel.Hum?, level: Double) {
        guard let hum else {
            humVoice?.silence()
            return
        }
        guard level > 0 else {
            humVoice?.set(0)
            return
        }
        if humVoice?.sharpness != hum.sharpness {
            if let humVoice { engine.discard(humVoice) }
            humVoice = engine.voice(sharpness: hum.sharpness)
        }
        humVoice?.set(level)
    }

    /// The page went away: everything stops.
    func stopAll() {
        stopRumble()
        humVoice?.silence()
        engine.stop()
    }
}
