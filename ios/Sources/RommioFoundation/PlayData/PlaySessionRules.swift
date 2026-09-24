import Foundation

/// How a play session ended (docs/play-data-local.md §1). The raw value is what the
/// local table stores; Android and iOS use the same keys.
public enum PlaySessionEndReason: String, CaseIterable, Sendable {
    /// The player left and the exit capture updated the automatic resume point.
    case resume
    /// The player made a save state and left with under `PlaySessionRules.stateGraceMs` of play after it.
    case state
    /// The player left without a new resume point (state capture failed or
    /// unsupported, or the session never started), or the core stopped.
    case quit
    /// Never closed by the player: the process ended (usually in the background)
    /// and the session was closed at the next launch from its last heartbeat.
    case background

    /// How Stats describe the ending.
    public var title: String {
        switch self {
        case .resume: "Left with a resume point"
        case .state: "Left after a save state"
        case .quit: "Left without a resume point"
        case .background: "Ended in the background"
        }
    }
}

public enum PlaySessionRules {
    /// Leaving within this much active play after a manual save state counts as ending on it.
    public static let stateGraceMs: Int64 = 5_000
    /// How often an open session's heartbeat and active time are written while it runs.
    public static let heartbeatIntervalMs: Int64 = 30_000

    /// End reason for a session the player closed.
    /// - Parameters:
    ///   - exitUpdatedResume: the final capture committed a resume point.
    ///   - activeMsSinceManualState: active play since this session's last manual save state, or nil when it made none.
    public static func reasonForExit(exitUpdatedResume: Bool, activeMsSinceManualState: Int64?) -> PlaySessionEndReason {
        if let since = activeMsSinceManualState, since < stateGraceMs { return .state }
        return exitUpdatedResume ? .resume : .quit
    }

    public struct AbandonedClose: Equatable, Sendable {
        public let endedAtEpochMs: Int64
        public let activeDurationMs: Int64
        public let reason: PlaySessionEndReason
    }

    /// Closes a session left open by process death: it ends at its last heartbeat
    /// (never later, and never before it started), keeping the active time recorded
    /// at that heartbeat (clamped to the span).
    public static func closeAbandoned(startedAtEpochMs: Int64, lastHeartbeatEpochMs: Int64, activeDurationMs: Int64) -> AbandonedClose {
        let end = max(startedAtEpochMs, lastHeartbeatEpochMs)
        let active = min(max(activeDurationMs, 0), end - startedAtEpochMs)
        return AbandonedClose(endedAtEpochMs: end, activeDurationMs: active, reason: .background)
    }
}

/// Active play time for one session: only time spent running (unpaused, in the
/// foreground) counts. Driven by phase changes; `now` is a monotonic clock in
/// milliseconds. Not thread-safe; the owner serialises access.
public final class ActivePlayClock {
    private let now: () -> Int64
    private var runningSince: Int64?
    private var accumulated: Int64 = 0
    private var manualStateMark: Int64?

    public init(now: @escaping () -> Int64 = ActivePlayClock.monotonicMs) {
        self.now = now
    }

    public var isRunning: Bool { runningSince != nil }

    public func setRunning(_ running: Bool) {
        let t = now()
        if running, runningSince == nil { runningSince = t }
        if !running, let since = runningSince {
            accumulated += max(0, t - since)
            runningSince = nil
        }
    }

    public var activeMs: Int64 { accumulated + (runningSince.map { max(0, now() - $0) } ?? 0) }

    /// A manual save state committed now.
    public func markManualState() { manualStateMark = activeMs }

    /// Active play since the last manual save state, or nil when there was none.
    public var activeSinceManualState: Int64? { manualStateMark.map { activeMs - $0 } }

    /// Milliseconds on a clock that keeps counting while the device sleeps and never jumps with the wall clock.
    public static func monotonicMs() -> Int64 {
        Int64(clock_gettime_nsec_np(CLOCK_MONOTONIC) / 1_000_000)
    }
}
