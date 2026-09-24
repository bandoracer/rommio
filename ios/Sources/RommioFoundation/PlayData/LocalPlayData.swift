import CoreGraphics
import Foundation
import RommioContract

/// What local play data needs to know when a player session starts.
public struct PlaySessionStart: Sendable {
    public let sessionId: String
    public let identity: ProgressIdentity
    public let deviceId: String
    public let platformSlug: String?
    public let coreId: String?

    public init(sessionId: String, identity: ProgressIdentity, deviceId: String, platformSlug: String?, coreId: String?) {
        self.sessionId = sessionId
        self.identity = identity
        self.deviceId = deviceId
        self.platformSlug = platformSlug
        self.coreId = coreId
    }
}

/// Where a player session stands, as the session owner reports it.
public enum PlaySessionPhase: Sendable, Equatable {
    /// Running: unpaused and in the foreground. Only this time counts as play.
    case running
    /// Paused (pause menu, sheets, loading, checkpointing, background).
    case paused
    /// The player left. `exitUpdatedResume`: the exit capture committed a resume point.
    case closed(exitUpdatedResume: Bool)
    /// The session failed to start or the core stopped.
    case stopped
}

/// A gameplay frame taken while the engine is paused for a capture. Making the image
/// may be deferred; it never touches the engine again.
public struct PlayDataFrame: @unchecked Sendable {
    public let make: @Sendable () -> CGImage?
    public init(make: @escaping @Sendable () -> CGImage?) { self.make = make }
}

/// Local play data observing one player session. Every call returns quickly, never
/// throws and never blocks or fails a save. Nothing observed here is written into a checkpoint.
public protocol PlayDataSession: AnyObject, Sendable {
    /// Every phase change, including the terminal `closed` / `stopped`.
    func phase(_ phase: PlaySessionPhase)
    /// The core is about to be captured (engine paused). With a state, the frame being serialised.
    func captureStarting(includeState: Bool, frame: PlayDataFrame?)
    /// A checkpoint committed (on the owner's lane, in order).
    func committed(_ checkpoint: ProgressCheckpoint)
    /// A restore committed `result` from `sourceCheckpointId`; the loaded moment is the source's.
    func restored(sourceCheckpointId: String, result: ProgressCheckpoint)
}

public protocol PlayDataRecording: Sendable {
    func begin(_ start: PlaySessionStart) -> any PlayDataSession
}

/// Records play sessions in the local table and save previews in `thumbnails`
/// (docs/play-data-local.md §1–2). Writes run on one serial lane off the player's
/// threads; a failure is logged and dropped, never surfaced as a save failure.
public final class LocalPlayData: PlayDataRecording, @unchecked Sendable {
    public let store: PlayDataStore
    public let thumbnails: SaveThumbnailStore
    private let wallClock: @Sendable () -> Int64
    private let monotonic: @Sendable () -> Int64
    private let frameTimeout: Duration
    private let lane = DispatchQueue(label: "io.github.mattsays.rommio.playdata", qos: .utility)
    private let lock = NSLock()
    private var live = Set<String>()
    private var previews: [UUID: Task<Void, Never>] = [:]

    public init(store: PlayDataStore, thumbnails: SaveThumbnailStore,
                wallClock: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
                monotonic: @escaping @Sendable () -> Int64 = ActivePlayClock.monotonicMs,
                frameTimeout: Duration = .seconds(3)) {
        self.store = store
        self.thumbnails = thumbnails
        self.wallClock = wallClock
        self.monotonic = monotonic
        self.frameTimeout = frameTimeout
        // Sessions left open by process death end at their last heartbeat, reason "background".
        enqueue { [store] in try store.closeAbandoned() }
    }

    func enqueue(_ write: @escaping @Sendable () throws -> Void) {
        lane.async {
            do { try write() } catch { NSLog("[LocalPlayData] write failed: %@", error.localizedDescription) }
        }
    }

    /// Waits until every write accepted so far has landed (for readers that refresh right after a session ends).
    public func flush() async {
        let pending = lock.withLock { Array(previews.values) }
        for task in pending { await task.value }
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            lane.async { continuation.resume() }
        }
    }

    /// Closes open rows not owned by a live session (at launch every row is abandoned).
    public func closeAbandoned() {
        let live = lock.withLock { self.live }
        enqueue { [store] in try store.closeAbandoned(except: live) }
    }

    public func begin(_ start: PlaySessionStart) -> any PlayDataSession {
        lock.withLock { _ = live.insert(start.sessionId) }
        let now = wallClock()
        let identity = start.identity
        let record = PlaySessionRecord(
            sessionId: start.sessionId, namespaceId: identity.namespaceId, contentId: identity.contentId,
            serverKey: identity.binding.serverKey, accountId: identity.binding.accountId, romId: identity.binding.romId,
            fileId: identity.binding.files.min { $0.discIndex < $1.discIndex }?.fileId, platformSlug: start.platformSlug,
            deviceId: start.deviceId, coreId: start.coreId, startedAtEpochMs: now, lastHeartbeatEpochMs: now)
        enqueue { [store] in try store.insert(record) }
        return Session(owner: self, start: start)
    }

    fileprivate func released(_ sessionId: String) {
        lock.withLock { _ = live.remove(sessionId) }
    }

    /// Makes the image off the owner's lane (at most `frameTimeout`), then writes it on the lane.
    fileprivate func writePreview(_ frame: PlayDataFrame, to target: SaveKey) {
        let id = UUID()
        // Registered under the lock before the task can remove itself.
        lock.lock()
        previews[id] = Task.detached(priority: .utility) { [self] in
            if let image = await makeFrame(frame) {
                enqueue { [thumbnails] in try thumbnails.write(target, image: image) }
            }
            lock.withLock { _ = previews.removeValue(forKey: id) }
        }
        lock.unlock()
    }

    /// Copies a preview once any preview still being made (e.g. the source's) has landed.
    fileprivate func copyPreview(from source: SaveKey, to target: SaveKey) {
        let id = UUID()
        lock.lock()
        let pending = Array(previews.values)
        previews[id] = Task.detached(priority: .utility) { [self] in
            for task in pending { await task.value }
            enqueue { [thumbnails] in try thumbnails.copy(from: source, to: target) }
            lock.withLock { _ = previews.removeValue(forKey: id) }
        }
        lock.unlock()
    }

    fileprivate func makeFrame(_ frame: PlayDataFrame) async -> CGImage? {
        let timeout = frameTimeout
        return await withTaskGroup(of: CGImage?.self) { group in
            group.addTask { frame.make() }
            group.addTask { try? await Task.sleep(for: timeout); return nil }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }
    }

    private final class Session: PlayDataSession, @unchecked Sendable {
        private let owner: LocalPlayData
        private let start: PlaySessionStart
        private let clock: ActivePlayClock
        private let lock = NSLock()
        private var finished = false
        private var ticker: Task<Void, Never>?
        private var pendingFrame: PlayDataFrame?

        init(owner: LocalPlayData, start: PlaySessionStart) {
            self.owner = owner
            self.start = start
            self.clock = ActivePlayClock(now: owner.monotonic)
        }

        private func key(_ checkpointId: String) -> SaveKey {
            SaveKey(namespaceId: start.identity.namespaceId, contentId: start.identity.contentId, checkpointId: checkpointId)
        }

        private func heartbeat() {
            let beat = owner.wallClock()
            let active = lock.withLock { clock.activeMs }
            let sessionId = start.sessionId
            owner.enqueue { [store = owner.store] in try store.heartbeat(sessionId, at: beat, activeMs: active) }
        }

        func phase(_ phase: PlaySessionPhase) {
            switch phase {
            case let .closed(exitUpdatedResume):
                finish { since in PlaySessionRules.reasonForExit(exitUpdatedResume: exitUpdatedResume, activeMsSinceManualState: since) }
            case .stopped:
                finish { _ in .quit }
            case .running, .paused:
                let changed: Bool? = lock.withLock {
                    if finished { return nil }
                    let before = clock.isRunning
                    clock.setRunning(phase == .running)
                    return before != clock.isRunning
                }
                guard changed == true else { return }
                heartbeat()
                if phase == .running { startTicker() } else { ticker?.cancel(); ticker = nil }
            }
        }

        private func startTicker() {
            ticker?.cancel()
            ticker = Task { [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(for: .milliseconds(PlaySessionRules.heartbeatIntervalMs))
                    guard !Task.isCancelled, let self else { return }
                    self.heartbeat()
                }
            }
        }

        private func finish(_ reason: (Int64?) -> PlaySessionEndReason) {
            let result: (Int64, PlaySessionEndReason)? = lock.withLock {
                if finished { return nil }
                finished = true
                clock.setRunning(false)
                return (clock.activeMs, reason(clock.activeSinceManualState))
            }
            ticker?.cancel(); ticker = nil
            guard let (active, why) = result else { return }
            let end = owner.wallClock()
            let sessionId = start.sessionId
            owner.enqueue { [store = owner.store] in try store.finish(sessionId, endedAt: end, activeMs: active, reason: why) }
            owner.released(sessionId)
        }

        func captureStarting(includeState: Bool, frame: PlayDataFrame?) {
            guard includeState else { return }
            lock.withLock { pendingFrame = frame }
        }

        func committed(_ checkpoint: ProgressCheckpoint) {
            let hasState = checkpoint.stateCompatibility != nil
            guard hasState else { return }
            let frame: PlayDataFrame? = lock.withLock {
                if checkpoint.captureReason == "manual" { clock.markManualState() }
                defer { pendingFrame = nil }
                return pendingFrame
            }
            heartbeat()
            // A restore capture happens before the restored frame is drawn; its preview is
            // copied from the source in `restored` instead (none after a reset).
            guard let frame, checkpoint.captureReason != "restore" else { return }
            owner.writePreview(frame, to: key(checkpoint.checkpointId))
        }

        func restored(sourceCheckpointId: String, result: ProgressCheckpoint) {
            guard result.stateCompatibility != nil else { return }
            owner.copyPreview(from: key(sourceCheckpointId), to: key(result.checkpointId))
        }
    }
}
