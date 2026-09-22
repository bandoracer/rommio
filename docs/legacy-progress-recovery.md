# Android legacy-progress recovery

Status: partial R05/R06/R13 implementation. Settings now exposes explicit selection and copying of legacy progress to a verified installed game. This does not activate the v2 store or cloud synchronization, prove old state compatibility, or complete legacy migration across platforms.

## Discovery and selection

Settings → **Review older saves** scans legacy `saves/` and `states/` files and the legacy manual-state, recovery, and slot-journal records. Discovery includes orphan files, slot zero, hidden resume, pre-fix resume backups, missing-file references, and retained bytes whose journal says they were deleted. It leaves every record and file unchanged.

The list shows the original path, size, manual/recovery label when available, and recorded provenance. A profile ID or filename is explicitly not verified ownership. Missing, linked/external, empty, or unclassified files remain visible with an explanation and cannot be silently treated as compatible progress. Unknown file-backed formats need R07 persistence descriptors.

The user selects individual files and a destination among the current verified account's owned installs. Nothing is preselected. Copying checks the same inspected source bytes, the current installed row, verified account/server binding, and the actual installed ROM digest and length. Changed sources, changed ROMs, a switched account, and stale install selections fail before activation. Archives and disc-set descriptors remain gated rather than assigning container identity as playable-content identity.

An unowned old install is not relabeled from its title or numeric ID. The user can download a verified destination game and explicitly select the older progress belonging to it.

## Copy semantics

- Originals, old manual slots, old tombstones, and existing queued intent are retained. Copying is a new local recovery action, not acknowledgment or replay of an old remote operation.
- At most one selected SRAM file becomes the destination's current in-game save. Existing current SRAM or automatic resume prevents this copy; it never overwrites current progress or combines new SRAM with a stale automatic state.
- Selected state files become separate, pinned recovery entries in the destination content scope. Labels survive. They do not replace manual slots or become automatic resume, including when the source was slot zero or a hidden resume file. State/core compatibility is unverified and is stated during selection; R07/R10 still own compatibility enforcement at restore.
- A migrated owned install that still uses legacy paths switches to its verified content scope after the copy commits. Unselected legacy files remain available for another explicit recovery selection. Other installs/accounts retain their previous scope and files.
- Local SRAM recovery marks continuity pending without changing remote acknowledgment. The UI reports local recovery and the continuing absence of cloud sync.

## Durable recovery

`LegacyProgressRecovery` runs under exclusive `StorageAccess`. It drains ordinary participating work and refuses mutation while a native player or browser-sign-in session is active. The user does not need to be online, but the installed account binding must already have been verified.

The operation stages hash-verified source copies under `noBackupFilesDir/legacy-progress-recovery/<UUID>` and atomically records a frozen copy plan in `pending.json`. Only then does it install destination files. Each destination is new or must already contain the exact planned bytes on retry. Differing files are never replaced.

A provenance receipt is written under the library's `recovery-imports/<UUID>.json`, so bundle exports retain the mapping history. Room then commits the selected install scope, recovery rows, and local continuity intent together. Stable entry IDs make replay idempotent. After commit, the pending marker is removed and storage is released. The receipt and original source files remain.

Startup and **Retry recovery** resume a pending copy from its staged bytes and frozen destination, even if a different account is now active. Recovery never retargets the selection. A missing or changed destination install, corrupt staged bytes, or an invalid plan fails closed and retains the available files. A separate retained-backup/repair flow is still needed for cases where retry cannot validate the journal; no automatic destructive rollback is attempted.

Storage tracks bundle-import and progress-copy recovery separately. Finishing one journal cannot release another pending recovery. New bundle imports/exports also check this barrier before starting. The existing import journal continues to own whole-library transfers; the copy journal neither restores credentials nor changes preferences.

## Evidence

`LegacyProgressRecoveryTest` has 11 instrumented cases against actual Room databases and app-managed files. Coverage includes discovery, explicit copying, original/tombstone/queued-intent preservation, named slots and pre-fix backups, colliding accounts, wrong-account and changed-content rejection, existing progress protection, active-session exclusion, linked/traversal rejection, corrupt staging, and interrupted prepare/file/database boundaries with database reopen and repeated replay.

`StorageAccessTest` additionally verifies that completing one recovery cannot release another. The full Android run passes 67 unit tests per flavor, both lint tasks, both debug builds, and 55 instrumented cases with two pre-existing live-server skips and zero failures/errors. Local log: `/tmp/rommio-legacy-recovery-final.log`.

These tests inject exceptions and reopen actual databases; they are not abrupt OS process-kill, disk-exhaustion, physical-device, or visual interaction qualification. No personal saves or remote save records were copied or changed during this work.

Remaining functional work includes archive/disc and file-backed descriptors, retained content-generation browsing, unrecoverable-journal repair/abandonment, full legacy ownership/reattachment flows, v2 checkpoint migration, restore compatibility and session coordination, iOS parity, and device/failure qualification. The complete gates remain in the [remediation plan](remediation-plan.md).
