import Foundation
import os
#if canImport(CoreHaptics)
import CoreHaptics
#endif
#if os(iOS)
import AVFAudio
#endif

/// The haptics playground's Core Haptics: one-shot recipes and their sounds, and continuous
/// voices whose intensity moves live (rumble motors, a stick's hum).
///
/// Two engines (revised 2026-09-28): haptics play on a haptics-only engine, which starts
/// playback with the least latency and never waits on audio; sounds play on their own
/// audio-only engine. On one shared engine, and with each sound rendered and registered the
/// first time it played, presses stalled at touch-down and landed as the finger lifted. Sounds
/// are now rendered and registered in the background when the page opens (and when the style
/// changes); a press whose sound isn't ready yet plays without it rather than wait.
///
/// Each recipe gets one player, made ahead (`prewarm`) or on first use, and restarted on
/// every press: making a player per press was main-thread work between the touch and the
/// haptic.
///
/// The system stops an engine when the app goes to the background or audio is interrupted;
/// the next play starts it again, and players from before a stop or reset are never touched
/// (Core Haptics raises an exception for a player whose engine stopped): the stop is flagged
/// the moment Core Haptics reports it, before the main thread hears of it.
@MainActor
final class HapticsEngine {
    /// Global strength (× every intensity) and sharpness bias (+ every sharpness).
    var strength: Double = 1 {
        didSet { if strength != oldValue { players.removeAll(); voices.forEach { $0.engineSettingsChanged() } } }
    }
    var sharpnessBias: Double = 0 {
        didSet { if sharpnessBias != oldValue { players.removeAll(); voices.forEach { $0.engineSettingsChanged() } } }
    }
    /// Whether recipes play their sounds.
    var soundsOn = true {
        didSet {
            guard soundsOn != oldValue else { return }
            if soundsOn { prepareSounds() } else { stopSounds() }
        }
    }
    /// × every sound's volume.
    var soundLevel: Double = 0.35 {
        didSet { if soundLevel != oldValue { soundPatterns.removeAll() } }
    }
    var soundStyle: SoundStyle = .invisible {
        didSet {
            guard soundStyle != oldValue else { return }
            soundPatterns.removeAll()
            prepareSounds()
        }
    }

    static var isSupported: Bool {
        #if canImport(CoreHaptics)
        CHHapticEngine.capabilitiesForHardware().supportsHaptics
        #else
        false
        #endif
    }

    #if canImport(CoreHaptics)
    private var engine: CHHapticEngine?
    private var running = false
    /// Set by Core Haptics' stop and reset handlers on their own queue, at once.
    private let stopped = OSAllocatedUnfairLock(initialState: false)
    /// Bumped on every stop or reset: a voice's player from an older generation is dropped.
    private(set) var generation = 0
    /// A player per recipe, restarted on every play.
    private var players: [HapticRecipe: CHHapticPatternPlayer] = [:]

    private var soundEngine: CHHapticEngine?
    private var soundRunning = false
    /// Bumped when the sound engine resets or goes: registrations from before are void.
    private var soundGeneration = 0
    /// A recipe's sound pattern, per take.
    private struct SoundKey: Hashable {
        let recipe: HapticRecipe
        let take: Int
    }
    private var soundPatterns: [SoundKey: CHHapticPattern] = [:]
    /// Sounds registered with the sound engine, by file.
    private var resources: [String: CHHapticAudioResourceID] = [:]
    private var preparing: Task<Void, Never>?
    /// The next take of a sound: repeated presses rotate through them.
    private var nextTake = 0
    #endif
    private var voices: [HapticVoice] = []

    init() {}

    /// Plays a recipe now: its haptic at once, its sound if it's ready.
    func play(_ recipe: HapticRecipe) {
        #if canImport(CoreHaptics)
        if !recipe.events.isEmpty, let engine = ready() {
            do {
                try player(for: recipe, engine: engine).start(atTime: CHHapticTimeImmediate)
            } catch {
                // A player that won't start again: a fresh one (a dropped tap is better than a
                // stalled control).
                players[recipe] = nil
                try? player(for: recipe, engine: engine).start(atTime: CHHapticTimeImmediate)
            }
        }
        if soundsOn, !recipe.sounds.isEmpty { playSound(of: recipe) }
        #endif
    }

    /// A continuous voice at `sharpness`, silent until its level is set.
    func voice(sharpness: Double) -> HapticVoice {
        let voice = HapticVoice(owner: self, sharpness: sharpness)
        voices.append(voice)
        return voice
    }

    /// Silences a voice and forgets it (a texture's hum replaced by another's).
    func discard(_ voice: HapticVoice) {
        voice.silence()
        voices.removeAll { $0 === voice }
    }

    /// Silences every voice and stops the engines (the page went away).
    func stop() {
        voices.forEach { $0.silence() }
        #if canImport(CoreHaptics)
        engine?.stop(completionHandler: nil)
        running = false
        generation += 1
        players.removeAll()
        stopSounds()
        #endif
    }

    #if canImport(CoreHaptics)
    /// Makes the players for `recipes` now, so their first play has nothing to build.
    func prewarm(_ recipes: [HapticRecipe]) {
        guard let engine = ready() else { return }
        for recipe in recipes where !recipe.events.isEmpty {
            _ = try? player(for: recipe, engine: engine)
        }
    }

    private func player(for recipe: HapticRecipe, engine: CHHapticEngine) throws -> CHHapticPatternPlayer {
        if let player = players[recipe] { return player }
        // Textures make many slightly different taps: keep the cache bounded.
        if players.count > 400 { players.removeAll() }
        let player = try engine.makePlayer(with: recipe.hapticPattern(strength: strength, sharpnessBias: sharpnessBias))
        players[recipe] = player
        return player
    }

    /// The haptics engine, started (created on first use).
    func ready() -> CHHapticEngine? {
        guard Self.isSupported else { return nil }
        if stopped.withLock({ $0 }) { engineStopped() }
        if engine == nil {
            guard let created = try? CHHapticEngine() else { return nil }
            created.playsHapticsOnly = true
            created.isAutoShutdownEnabled = false
            let stopped = stopped
            created.stoppedHandler = { [weak self] _ in
                stopped.withLock { $0 = true }
                Task { @MainActor in if stopped.withLock({ $0 }) { self?.engineStopped() } }
            }
            created.resetHandler = { [weak self] in
                stopped.withLock { $0 = true }
                Task { @MainActor in if stopped.withLock({ $0 }) { self?.engineStopped() } }
            }
            engine = created
        }
        if !running {
            do {
                try engine?.start()
                running = true
            } catch {
                return nil
            }
        }
        return engine
    }

    /// Catches up with a stop Core Haptics has reported but the main thread hasn't handled.
    func noteStopIfAny() {
        if stopped.withLock({ $0 }) { engineStopped() }
    }

    private func engineStopped() {
        stopped.withLock { $0 = false }
        running = false
        generation += 1
        players.removeAll()
        voices.forEach { $0.engineStopped() }
    }

    // MARK: Sounds

    /// Renders and registers every sound in the current style, off the main thread, so a
    /// press never waits for one.
    func prepareSounds() {
        guard soundsOn, let sounds = readySound() else { return }
        preparing?.cancel()
        let style = soundStyle, generation = soundGeneration
        let done = Set(resources.keys)
        let box = EngineBox(engine: sounds)
        preparing = Task.detached(priority: .userInitiated) { [weak self] in
            var registered: [(String, CHHapticAudioResourceID)] = []
            for sound in SoundRecipe.all {
                for take in 0..<SoundRecipe.takes {
                    guard !Task.isCancelled else { return }
                    let name = sound.fileID(style: style, take: take)
                    guard !done.contains(name), let file = Self.render(sound, style: style, take: take, name: name),
                          let id = try? box.engine.registerAudioResource(file, options: [CHHapticAudioResourceKeyUseVolumeEnvelope: false])
                    else { continue }
                    registered.append((name, id))
                }
            }
            await self?.registered(registered, generation: generation)
        }
    }

    private func registered(_ ids: [(String, CHHapticAudioResourceID)], generation: Int) {
        guard generation == soundGeneration else { return }
        for (name, id) in ids { resources[name] = id }
    }

    /// Writes a sound's WAV file (once) and returns it.
    private nonisolated static func render(_ sound: SoundRecipe, style: SoundStyle, take: Int, name: String) -> URL? {
        let folder = FileManager.default.temporaryDirectory.appending(path: "rommio-haptic-sounds", directoryHint: .isDirectory)
        let file = folder.appending(path: "\(name).wav")
        if FileManager.default.fileExists(atPath: file.path) { return file }
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try sound.wav(style: style, take: take).write(to: file, options: .atomic)
            return file
        } catch {
            return nil
        }
    }

    private func playSound(of recipe: HapticRecipe) {
        guard let sounds = readySound() else { return }
        let take = nextTake
        nextTake = (nextTake + 1) % SoundRecipe.takes
        let key = SoundKey(recipe: recipe, take: take)
        do {
            let pattern: CHHapticPattern
            if let cached = soundPatterns[key] {
                pattern = cached
            } else {
                // Only sounds already registered: a press never waits for one.
                let names = recipe.sounds.map { $0.sound.fileID(style: soundStyle, take: take) }
                guard names.allSatisfy({ resources[$0] != nil }) else { return }
                guard let made = try recipe.soundPattern(level: soundLevel, resource: { [self] in
                    resources[$0.fileID(style: soundStyle, take: take)]
                }) else { return }
                if soundPatterns.count > 400 { soundPatterns.removeAll() }
                soundPatterns[key] = made
                pattern = made
            }
            try sounds.makePlayer(with: pattern).start(atTime: CHHapticTimeImmediate)
        } catch {
            // A missing sound is better than a late haptic.
        }
    }

    /// The sound engine, started (created, and its sounds prepared, on first use).
    private func readySound() -> CHHapticEngine? {
        guard soundsOn, Self.isSupported else { return nil }
        if soundEngine == nil {
            do {
                #if os(iOS)
                // Interface sounds: they mix with other audio and follow the Ring/Silent
                // switch (the player sets its own category when a game starts).
                let session = AVAudioSession.sharedInstance()
                try? session.setCategory(.ambient, mode: .default, options: [.mixWithOthers])
                let created = try CHHapticEngine(audioSession: session)
                #else
                let created = try CHHapticEngine()
                #endif
                created.playsAudioOnly = true
                created.isAutoShutdownEnabled = false
                created.stoppedHandler = { [weak self] _ in
                    Task { @MainActor in self?.soundRunning = false }
                }
                created.resetHandler = { [weak self] in
                    Task { @MainActor in self?.soundEngineReset() }
                }
                soundEngine = created
            } catch {
                return nil
            }
        }
        if !soundRunning {
            do {
                try soundEngine?.start()
                soundRunning = true
            } catch {
                return nil
            }
        }
        return soundEngine
    }

    /// A reset forgets registered sounds: register them again.
    private func soundEngineReset() {
        soundRunning = false
        soundGeneration += 1
        resources.removeAll()
        soundPatterns.removeAll()
        prepareSounds()
    }

    private func stopSounds() {
        preparing?.cancel()
        preparing = nil
        soundEngine?.stop(completionHandler: nil)
        soundEngine = nil
        soundRunning = false
        soundGeneration += 1
        resources.removeAll()
        soundPatterns.removeAll()
    }
    #endif
}

#if canImport(CoreHaptics)
/// The sound engine, handed to the background task that registers sounds (Core Haptics
/// engines are thread-safe).
private struct EngineBox: @unchecked Sendable {
    let engine: CHHapticEngine
}
#endif

/// A continuous haptic that runs while its level is above zero: one looping buzz whose
/// intensity follows `level` (× the engine's strength) and whose sharpness can move from
/// the one it was made with. Used for rumble and textures that follow a finger.
@MainActor
final class HapticVoice {
    let sharpness: Double
    private(set) var level: Double = 0
    /// The sharpness it plays at now (live changes add to `sharpness`).
    private(set) var currentSharpness: Double
    private unowned let owner: HapticsEngine
    #if canImport(CoreHaptics)
    private var player: CHHapticAdvancedPatternPlayer?
    private var playerGeneration = -1
    #endif

    fileprivate init(owner: HapticsEngine, sharpness: Double) {
        self.owner = owner
        self.sharpness = sharpness
        self.currentSharpness = sharpness
    }

    /// Sets the voice's level (0 silences it) and, optionally, its sharpness.
    func set(_ newLevel: Double, sharpness newSharpness: Double? = nil) {
        let clamped = min(max(newLevel, 0), 1)
        level = clamped
        if let newSharpness { currentSharpness = min(max(newSharpness, 0), 1) }
        #if canImport(CoreHaptics)
        owner.noteStopIfAny()
        if clamped <= 0 {
            // Quiet, still running: a motor that pauses for a moment shouldn't click back
            // in (`silence()` stops it).
            if let player, playerGeneration == owner.generation {
                try? player.sendParameters([CHHapticDynamicParameter(parameterID: .hapticIntensityControl, value: 0, relativeTime: 0)],
                                           atTime: CHHapticTimeImmediate)
            }
            return
        }
        guard let player = startedPlayer() else { return }
        var parameters = [CHHapticDynamicParameter(parameterID: .hapticIntensityControl,
                                                   value: Float(clamped * owner.strength), relativeTime: 0)]
        if currentSharpness != sharpness {
            parameters.append(CHHapticDynamicParameter(parameterID: .hapticSharpnessControl,
                                                       value: Float(currentSharpness - sharpness), relativeTime: 0))
        }
        try? player.sendParameters(parameters, atTime: CHHapticTimeImmediate)
        #endif
    }

    /// Stops the voice.
    func silence() {
        level = 0
        #if canImport(CoreHaptics)
        owner.noteStopIfAny()
        if let player, playerGeneration == owner.generation {
            try? player.stop(atTime: CHHapticTimeImmediate)
        }
        player = nil
        #endif
    }

    fileprivate func engineStopped() {
        #if canImport(CoreHaptics)
        player = nil
        #endif
    }

    fileprivate func engineSettingsChanged() {
        // The sharpness bias is baked into the player's pattern: rebuild on the next level.
        let current = level
        silence()
        if current > 0 { set(current) }
    }

    #if canImport(CoreHaptics)
    private func startedPlayer() -> CHHapticAdvancedPatternPlayer? {
        if let player, playerGeneration == owner.generation { return player }
        player = nil
        guard let engine = owner.ready() else { return nil }
        // A 30 s buzz (Core Haptics' longest), looped: it holds until silenced.
        let recipe = HapticRecipe.buzz(1, sharpness, for: 30, sustained: true)
        guard let pattern = try? recipe.hapticPattern(strength: 1, sharpnessBias: owner.sharpnessBias),
              let made = try? engine.makeAdvancedPlayer(with: pattern) else { return nil }
        made.loopEnabled = true
        made.loopEnd = 30
        do {
            try made.start(atTime: CHHapticTimeImmediate)
        } catch {
            return nil
        }
        player = made
        playerGeneration = owner.generation
        return made
    }
    #endif
}
