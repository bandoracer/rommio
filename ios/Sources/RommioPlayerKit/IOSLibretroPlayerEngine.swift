import Foundation
import OSLog
import RommioContract
import RommioFoundation
import RommioPlayerBridge

public enum PlayerEngineError: LocalizedError, Sendable {
    case sessionNotPrepared
    case hostUnavailable
    case frameLoopAlreadyRunning
    case coreBundleIncomplete(String)

    public var errorDescription: String? {
        switch self {
        case .sessionNotPrepared:
            "Prepare a player session before invoking engine controls."
        case .hostUnavailable:
            "The player surface could not be created for this runtime."
        case .frameLoopAlreadyRunning:
            "The player is already running."
        case let .coreBundleIncomplete(message):
            message
        }
    }
}

#if canImport(UIKit) && canImport(AVFAudio)
import AVFAudio
import UIKit

private final class PlayerAudioEngine {
    private let engine = AVAudioEngine()
    private let playerNode = AVAudioPlayerNode()
    private let format: AVAudioFormat
    private var started = false

    init(sampleRate: Double) {
        self.format = AVAudioFormat(
            commonFormat: .pcmFormatInt16,
            sampleRate: sampleRate,
            channels: 2,
            interleaved: true
        )!
        engine.attach(playerNode)
        engine.connect(playerNode, to: engine.mainMixerNode, format: format)
    }

    func startIfNeeded() {
        guard !started else { return }
        do {
            try engine.start()
            playerNode.play()
            started = true
        } catch {
            started = false
        }
    }

    func stop() {
        playerNode.stop()
        engine.stop()
        started = false
    }

    func enqueue(_ batches: [RMLAudioBatch]) {
        guard started else { return }

        for batch in batches where batch.frameCount > 0 {
            let frameCapacity = AVAudioFrameCount(batch.frameCount)
            guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frameCapacity) else {
                continue
            }
            buffer.frameLength = frameCapacity
            let audioBuffers = UnsafeMutableAudioBufferListPointer(buffer.mutableAudioBufferList)
            guard let target = audioBuffers.first?.mData else {
                continue
            }
            batch.sampleData.withUnsafeBytes { source in
                memcpy(target, source.baseAddress, min(Int(audioBuffers[0].mDataByteSize), batch.sampleData.count))
            }
            playerNode.scheduleBuffer(buffer, completionHandler: nil)
        }
    }
}

public final class IOSLibretroPlayerEngine: PlayerEngine, @unchecked Sendable {
    private let logger = Logger(subsystem: "io.github.mattsays.rommio.ios", category: "PlayerEngine")
    private let bundle: Bundle
    private let lock = NSLock()
    private let workerQueue = DispatchQueue(label: "io.github.mattsays.rommio.player.prepare", qos: .userInitiated)

    private var session: PlayerSession?
    private var bridge: RMLLibretroBridge?
    private var surfaceController: PlayerSurfaceController?
    private var audioEngine: PlayerAudioEngine?
    private var latestFrameSnapshot: PlayerVideoFrameSnapshot?
    private var paused = false
    private var running = false
    private var statusMessage = "Preparing player"
    private var didLogFirstStepFrame = false
    private var hotkeyContinuation: AsyncStream<PlayerHotkeyAction>.Continuation?
    private var rumbleContinuation: AsyncStream<PlayerRumbleSignal>.Continuation?

    public init(bundle: Bundle = .main) {
        self.bundle = bundle
    }

    public func prepare(session: PlayerSession) async throws {
        logger.info("prepare session romID=\(session.romID, privacy: .public) runtimeID=\(session.runtimeProfile.runtimeID, privacy: .public)")
        NSLog("[IOSLibretroPlayerEngine] prepare session romID=%d runtimeID=%@", session.romID, session.runtimeProfile.runtimeID)
        try await stop()

        self.session = session
        self.statusMessage = "Loading \(session.romTitle)"

        let preparedState = try await prepareBridgeState(for: session)
        logger.info("bridge prepared sampleRate=\(preparedState.sampleRate, privacy: .public)")
        NSLog("[IOSLibretroPlayerEngine] bridge prepared sampleRate=%f", preparedState.sampleRate)
        self.bridge = preparedState.bridge
        self.audioEngine = PlayerAudioEngine(sampleRate: preparedState.sampleRate)
        self.latestFrameSnapshot = nil
        self.paused = false
        self.running = false
        self.didLogFirstStepFrame = false
        self.surfaceController = nil
        self.statusMessage = "Ready"
    }

    public func makeHostController() throws -> PlayerHostController {
        logger.info("makeHostController requested")
        NSLog("[IOSLibretroPlayerEngine] makeHostController requested")
        guard session != nil, bridge != nil else {
            throw PlayerEngineError.sessionNotPrepared
        }

        if let surfaceController {
            return surfaceController
        }

        let controller = PlayerSurfaceController(
            frameAdvanceProvider: { [weak self] in self?.advanceFrameForVisibleSurfaceIfNeeded() },
            frameProvider: { [weak self] in self?.frameSnapshot() },
            statusProvider: { [weak self] in self?.currentStatusMessage() ?? "Player unavailable" },
            preferredFPSProvider: { [weak self] in self?.preferredFramesPerSecond() ?? 60 },
            preferredAspectRatioProvider: { [weak self] in self?.session?.preferredViewportAspectRatio },
            topAlignedInPortraitProvider: { [weak self] in
                guard let self, let session = self.session else { return true }
                switch session.runtimeProfile.interactionProfile {
                case .touch, .dualScreenTouch:
                    return true
                case .controller, .keyboardMouse:
                    return false
                }
            }
        )
        self.surfaceController = controller
        logger.info("makeHostController created new surface controller")
        NSLog("[IOSLibretroPlayerEngine] makeHostController created new surface controller")
        return controller
    }

    public func start() async throws {
        logger.info("start requested")
        NSLog("[IOSLibretroPlayerEngine] start requested")
        guard bridge != nil, session != nil else {
            throw PlayerEngineError.sessionNotPrepared
        }
        guard !running else {
            throw PlayerEngineError.frameLoopAlreadyRunning
        }

        await startAudioEngineIfNeeded()
        logger.info("audio engine started")
        NSLog("[IOSLibretroPlayerEngine] audio engine started")
        running = true
        paused = false
        statusMessage = "Running"
        NSLog("[IOSLibretroPlayerEngine] frame loop delegated to player surface")
        logger.info("start returning")
        NSLog("[IOSLibretroPlayerEngine] start returning")
    }

    public func stop() async {
        _ = try? await persistSaveRAM()
        audioEngine?.stop()
        bridge?.stop()
        running = false
        paused = false
        statusMessage = "Stopped"
    }

    public func persistSaveRAM() async throws -> URL? {
        guard let session, let bridge else {
            throw PlayerEngineError.sessionNotPrepared
        }

        let data = try serializeSaveRAM(from: bridge)
        try FileManager.default.createDirectory(at: session.saveRAMURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: session.saveRAMURL, options: .atomic)
        return session.saveRAMURL
    }

    public func saveState(slot: Int) async throws -> URL {
        guard let session, let bridge else {
            throw PlayerEngineError.sessionNotPrepared
        }

        let target = session.saveStatesDirectory.appending(path: "\(session.romID)_slot\(slot).state")
        return try await saveState(to: target)
    }

    public func saveState(to url: URL) async throws -> URL {
        guard let bridge else {
            throw PlayerEngineError.sessionNotPrepared
        }

        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let state = try serializeState(from: bridge)
        try state.write(to: url, options: .atomic)
        return url
    }

    public func loadState(from url: URL) async throws -> Bool {
        guard bridge != nil else {
            throw PlayerEngineError.sessionNotPrepared
        }
        let state = try Data(contentsOf: url)
        return try withBridge { bridge in
            try bridge.unserializeState(state)
            return true
        }
    }

    public func setPaused(_ paused: Bool) async throws {
        guard bridge != nil else {
            throw PlayerEngineError.sessionNotPrepared
        }
        locked { self.paused = paused }
        locked { self.statusMessage = paused ? "Paused" : "Running" }
    }

    public func reset() async throws {
        try withBridge { bridge in
            bridge.reset()
        }
    }

    public func updateVariables(_ variables: [String : String]) async throws {
        try withBridge { bridge in
            try bridge.updateVariables(variables)
        }
    }

    public func dispatchDigital(keyCode: Int, pressed: Bool, port: Int) async throws {
        try withBridge { bridge in
            bridge.setDigitalInputPressed(pressed, keyCode: keyCode, port: UInt(max(port, 0)))
        }
    }

    public func dispatchMotion(source: PlayerMotionSource, x: Double, y: Double, port: Int) async throws {
        try withBridge { bridge in
            bridge.setMotionSource(source.bridgeSource, x: x, y: y, port: UInt(max(port, 0)))
        }
    }

    public func updateInputConfiguration(_ configuration: PlayerInputConfiguration) async throws {
        _ = configuration
    }

    public func availableControllerTypes(port: Int) async throws -> [PlayerControllerDescriptor] {
        try withBridge { bridge in
            bridge
                .availableControllerDescriptors(forPort: UInt(max(port, 0)))
                .map { PlayerControllerDescriptor(id: Int($0.identifier), description: $0.controllerDescription) }
        }
    }

    public func setControllerType(port: Int, controllerTypeID: Int) async throws {
        try withBridge { bridge in
            bridge.setControllerTypeIdentifier(controllerTypeID, forPort: UInt(max(port, 0)))
        }
    }

    public func hotkeySignals() -> AsyncStream<PlayerHotkeyAction> {
        AsyncStream { continuation in
            self.hotkeyContinuation = continuation
        }
    }

    public func rumbleSignals() -> AsyncStream<PlayerRumbleSignal> {
        AsyncStream { continuation in
            self.rumbleContinuation = continuation
        }
    }

    public func detach() {
        audioEngine?.stop()
        bridge?.stop()
        bridge = nil
        session = nil
        surfaceController = nil
        latestFrameSnapshot = nil
        paused = false
        running = false
        didLogFirstStepFrame = false
        statusMessage = "Detached"
        hotkeyContinuation?.finish()
        hotkeyContinuation = nil
        rumbleContinuation?.finish()
        rumbleContinuation = nil
    }

    private func makeBridge(coreURL: URL) throws -> RMLLibretroBridge {
        try RMLLibretroBridge(coreURL: coreURL)
    }

    private func prepareBridgeState(for session: PlayerSession) async throws -> PreparedBridgeState {
        try await withCheckedThrowingContinuation { continuation in
            workerQueue.async { [weak self] in
                guard let self else {
                    continuation.resume(throwing: PlayerEngineError.hostUnavailable)
                    return
                }

                do {
                    self.logger.info("worker makeBridge begin")
                    NSLog("[IOSLibretroPlayerEngine] worker makeBridge begin")
                    let bridge = try self.makeBridge(coreURL: session.coreURL)
                    self.logger.info("worker makeBridge complete")
                    NSLog("[IOSLibretroPlayerEngine] worker makeBridge complete")
                    try self.prepareBridge(bridge, session: session)
                    self.logger.info("worker prepareBridge complete")
                    NSLog("[IOSLibretroPlayerEngine] worker prepareBridge complete")
                    continuation.resume(
                        returning: PreparedBridgeState(
                            bridge: bridge,
                            sampleRate: max(bridge.sampleRate, 44_100)
                        )
                    )
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    private func startAudioEngineIfNeeded() async {
        await withCheckedContinuation { continuation in
            workerQueue.async { [weak self] in
                self?.audioEngine?.startIfNeeded()
                continuation.resume()
            }
        }
    }

    private func prepareBridge(_ bridge: RMLLibretroBridge, session: PlayerSession) throws {
        try FileManager.default.createDirectory(at: session.systemDirectory, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: session.savesDirectory, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: session.saveStatesDirectory, withIntermediateDirectories: true)

        try bridge.prepareGame(
            at: session.romURL,
            systemDirectory: session.systemDirectory,
            savesDirectory: session.savesDirectory,
            variables: session.variables
        )

        if let initialSaveRAM = session.initialSaveRAM, !initialSaveRAM.isEmpty {
            try bridge.restoreSaveRAM(initialSaveRAM)
        }
    }

    private func serializeSaveRAM(from bridge: RMLLibretroBridge) throws -> Data {
        let data = try bridge.serializeSaveRAM()
        return data
    }

    private func serializeState(from bridge: RMLLibretroBridge) throws -> Data {
        let data = try bridge.serializeState()
        return data
    }

    private func stepFrame() {
        if !didLogFirstStepFrame {
            didLogFirstStepFrame = true
            logger.info("stepFrame begin")
            NSLog("[IOSLibretroPlayerEngine] stepFrame begin")
        }
        do {
            try withBridge { bridge in
                try bridge.runFrame()
                if let frame = bridge.copyLatestVideoFrame() {
                    self.locked {
                        self.latestFrameSnapshot = PlayerVideoFrameSnapshot(
                            pixelData: frame.pixelData,
                            width: Int(frame.width),
                            height: Int(frame.height),
                            pitch: Int(frame.pitch),
                            pixelFormat: frame.pixelFormat
                        )
                    }
                }
                audioEngine?.enqueue(bridge.drainAudioBatches())
                for magnitude in bridge.drainRumbleMagnitudes() {
                    let normalized = magnitude.doubleValue
                    self.rumbleContinuation?.yield(
                        PlayerRumbleSignal(
                            port: 0,
                            weakStrength: normalized,
                            strongStrength: normalized
                        )
                    )
                }
            }
        } catch {
            locked {
                self.statusMessage = error.localizedDescription
                self.paused = true
            }
        }
    }

    private func advanceFrameForVisibleSurfaceIfNeeded() {
        let shouldRun = locked { running && !paused }
        guard shouldRun else { return }
        stepFrame()
    }

    private func withBridge<T>(_ operation: (RMLLibretroBridge) throws -> T) throws -> T {
        guard let bridge = locked({ self.bridge }) else {
            throw PlayerEngineError.sessionNotPrepared
        }
        return try operation(bridge)
    }

    private func frameSnapshot() -> PlayerVideoFrameSnapshot? {
        locked { latestFrameSnapshot }
    }

    private func currentStatusMessage() -> String {
        locked { statusMessage }
    }

    private func preferredFramesPerSecond() -> Int {
        let fps = locked {
            bridge?.nominalFramesPerSecond ?? 60
        }
        return max(Int(round(fps)), 1)
    }

    @discardableResult
    private func locked<T>(_ operation: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return operation()
    }
}

private struct PreparedBridgeState: @unchecked Sendable {
    let bridge: RMLLibretroBridge
    let sampleRate: Double
}

private extension PlayerMotionSource {
    var bridgeSource: RMLMotionSource {
        switch self {
        case .dpad:
            .dPad
        case .analogLeft:
            .analogLeft
        case .analogRight:
            .analogRight
        case .pointer:
            .pointer
        }
    }
}
#else
public final class IOSLibretroPlayerEngine: PlayerEngine, @unchecked Sendable {
    public init(bundle: Bundle = .main) {
        _ = bundle
    }

    public func prepare(session: PlayerSession) async throws { _ = session; throw PlayerEngineError.hostUnavailable }
    public func makeHostController() throws -> PlayerHostController { throw PlayerEngineError.hostUnavailable }
    public func start() async throws { throw PlayerEngineError.hostUnavailable }
    public func stop() async {}
    public func persistSaveRAM() async throws -> URL? { throw PlayerEngineError.hostUnavailable }
    public func saveState(to url: URL) async throws -> URL { _ = url; throw PlayerEngineError.hostUnavailable }
    public func saveState(slot: Int) async throws -> URL { _ = slot; throw PlayerEngineError.hostUnavailable }
    public func loadState(from url: URL) async throws -> Bool { _ = url; throw PlayerEngineError.hostUnavailable }
    public func setPaused(_ paused: Bool) async throws { _ = paused; throw PlayerEngineError.hostUnavailable }
    public func reset() async throws { throw PlayerEngineError.hostUnavailable }
    public func updateVariables(_ variables: [String : String]) async throws { _ = variables; throw PlayerEngineError.hostUnavailable }
    public func dispatchDigital(keyCode: Int, pressed: Bool, port: Int) async throws { _ = keyCode; _ = pressed; _ = port; throw PlayerEngineError.hostUnavailable }
    public func dispatchMotion(source: PlayerMotionSource, x: Double, y: Double, port: Int) async throws { _ = source; _ = x; _ = y; _ = port; throw PlayerEngineError.hostUnavailable }
    public func updateInputConfiguration(_ configuration: PlayerInputConfiguration) async throws { _ = configuration; throw PlayerEngineError.hostUnavailable }
    public func availableControllerTypes(port: Int) async throws -> [PlayerControllerDescriptor] { _ = port; throw PlayerEngineError.hostUnavailable }
    public func setControllerType(port: Int, controllerTypeID: Int) async throws { _ = port; _ = controllerTypeID; throw PlayerEngineError.hostUnavailable }
    public func hotkeySignals() -> AsyncStream<PlayerHotkeyAction> { AsyncStream { _ in } }
    public func rumbleSignals() -> AsyncStream<PlayerRumbleSignal> { AsyncStream { _ in } }
    public func detach() {}
}
#endif
