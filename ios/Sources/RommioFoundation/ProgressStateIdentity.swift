import Foundation
import RommioContract

/// Which snapshots a core can restore (docs/save-sync-spec.md, "Snapshot compatibility").
public extension ProgressStateCompatibility {
    /// iOS builds until 2026-09-28 named a core by its signed file's digest, which changed with
    /// every app build (a signature records its signing time); later builds name the pinned
    /// artifact before signing. No core build input changed between 2026-09-23 18:20:27 UTC (the
    /// last change to `scripts/cores`) and that date, so every device snapshot made in between
    /// came from the core builds these apps carry.
    static let legacySignedDigestWindow: Range<Int64> = 1_790_187_627_000 ..< 1_790_553_600_000

    /// Whether this core (`self`) can restore a snapshot made with `snapshot` at
    /// `createdAtEpochMs`: the same core identity, format, platform, architecture, options and
    /// system files; or a device snapshot from the window above that differs only in the digest.
    /// A restore that fails keeps the snapshot and falls back to the game save, as for any other.
    func restores(_ snapshot: ProgressStateCompatibility, createdAtEpochMs: Int64) -> Bool {
        if snapshot == self { return true }
        return platform == "ios" && snapshot.platform == "ios"
            && snapshot.coreId == coreId && snapshot.stateFormat == stateFormat
            && snapshot.architecture == architecture && snapshot.options == options
            && snapshot.systemFiles == systemFiles
            && Self.legacySignedDigestWindow.contains(createdAtEpochMs)
    }
}
