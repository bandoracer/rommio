# Android progress history, repair, and retention

Status: R09 foundation implemented under qualification. These operations belong to the new v2 `ProgressStore`; the player does not use that store yet. This change does not migrate personal saves, activate cloud sync, schedule automatic garbage collection, or provide the final recovery UI.

## Indexed history

Room schema 13 adds recovery-history rows and a durable indexing watermark. It also adds an explicit local sequence to immutable operation rows, without changing their payload bytes, hashes, causal parents, heads, or outbox identity. The upgrade backfills that sequence from the existing database order once; subsequent commits allocate it transactionally.

The sequence is explicit because SQLite maintenance can change implicit row IDs in tables without an integer primary key. See SQLite's [VACUUM documentation](https://www.sqlite.org/lang_vacuum.html). The device test performs VACUUM and then checks subsequent history commits.

History pages use a bounded keyset query (default 20 entries, maximum 100). Each checkpoint has a deterministic position derived from its first operation's local sequence and its position within that envelope. Device timestamps remain display information; they do not determine the selected branch, pagination order, or the retention grace period. Rebuilding an older missing index row restores its original position.

A schema-12 store is indexed in batches of 64 operation envelopes. New checkpoint rows commit with their operation, heads, and outbox. Commits check only their immutable parents and any reused checkpoint IDs instead of decoding every historical operation. Queue leasing likewise no longer decodes every operation just to inspect candidate ancestry.

`history` returns manifests, producing operation IDs, local sequence, manual/pin state, and retention intent. `RETAINED` means the checkpoint is intended to remain local; it is not proof that the files are healthy. `inspectCheckpoint` verifies their actual lengths and SHA-256 digests and returns per-artifact issues. `materializeCheckpoint` explicitly copies a selected historical checkpoint to a fresh working directory; it does not alter current references or bypass the coordinator's future safety-checkpoint and compatibility requirements.

Pins are local retention choices. Imports start pinned. A resolution that incorporates multiple competing parents pins their earlier checkpoint targets so resolving a conflict does not silently discard the other recovery branch. Manual checkpoints are protected independently of pins.

## Verified repair

`repairCheckpoint` accepts sources for known artifact digests. It validates replacement bytes before installation and keeps any damaged original under `repair-backups/<expected digest>/<unique ID>`. Only then does it atomically replace the damaged blob and synchronize its directory. Bad sources and interruption before replacement leave the original path intact. Repair can be retried after restart; the original backup remains.

Repair does not change causal heads, payloads, or outbox acknowledgment. Once every artifact verifies, intentionally evicted history becomes retained and pinned, so rehydration remains available for the explicit recovery action. If shared healthy blobs already exist, rehydration needs no additional source bytes.

`repairOperation` requires the exact previously committed payload digest and a valid matching binding/operation ID. It quarantines the damaged envelope and replaces only its payload bytes in one transaction. It does not re-encode downloaded JSON, invent a new expected digest, or repair a damaged expected hash by guessing.

`rebuildHistoryIndex` replays validated immutable envelopes while preserving existing pins and eviction records. Explicit pin/unpin choices are stored separately from inferred conflict pins, so rebuilding does not resurrect a pin the user cleared. Before retention, an independent bounded scan audits the index against every stored manifest and manual-reference requirement. A missing or inconsistent derived row stops collection until the index is repaired. Damage to an authoritative envelope must be repaired against its recorded digest first.

## Retention and cleanup

`retainHistory` defaults to ten recent unpinned automatic checkpoints and a 24-hour grace period. It always protects:

- Every current reference head, including each competing branch and manual slot.
- Every manual checkpoint and explicit recovery pin.
- Every checkpoint in an unpublished operation, including `PENDING`, `LEASED`, `PARKED_AUTH`, and `LOCAL_ONLY` work.
- Any shared blob still needed by a retained checkpoint.

Local-only and offline unpublished progress can therefore exceed the automatic-history count. Storage pressure must surface as a save/storage failure; it cannot silently discard that work.

Eligible history is marked `EVICTED` in a database transaction before deleting artifact bytes. The immutable envelopes, ancestry, tombstones, and publication records remain. A crash between marking and deletion can leave extra files, but planned eviction is not reported as accidental corruption. Retrying cleanup is idempotent. Restoring evicted history is explicit even when its shared blobs happen to remain on disk.

Collection uses reachability plus grace periods. It removes eligible unreferenced digest-named blobs and abandoned `.staging-<UUID>` entries. It does not delete final working-session directories, repair backups, quarantine records, unknown files, or anything remote. The store's exclusive owner and mutex exclude concurrent commits/materializations; filesystem traversal does not follow staging symlinks and rejects symlink blob targets. The session coordinator must eventually release completed working directories after native ownership ends.

## Validation and remaining gates

`ProgressHistoryTest` exercises real Room/SQLite and Android filesystems with synthetic save bytes. Its twelve cases cover:

- 135-operation history migration and pagination, descending display timestamps, rebuild position, reopen, and VACUUM.
- Artifact repair, bad sources, retained damaged backups, interrupted replacement, and immutable working-copy isolation.
- Exact-digest operation repair with quarantine and unchanged pending publication.
- Automatic-history retention, shared-blob protection, unpublished ancestors beyond the retention window, manual/pinned/conflicting progress, and preserved tombstones.
- Interrupted collection, staging cleanup without following links, retained working sessions, grace periods, identity isolation, and closed owners.
- Coroutine cancellation immediately before and after the database commit boundary, with only complete recoverable progress remaining.

Full Android checks pass: 92 unit cases per flavor, both lint tasks/debug builds, and 90 instrumented cases with two existing live-server skips and no failures/errors. Log: `/tmp/rommio-history-final.log`.

These are fault injection, coroutine cancellation, and database-reopen tests. They are not abrupt process-kill, disk-exhaustion, power-loss, or native gameplay qualification. Cancellation checks were also added to artifact copy/hash loops; mid-copy OS termination still needs its own harness.

Before activation, complete core persistence/compatibility descriptors, the ordered session coordinator, working-session cleanup, import/export of v2 storage, recovery/conflict UI, remote rehydration and publication, and matching Swift behavior. Full-history integrity/retention scans use bounded payload batches but can still be expensive for large libraries; schedule them outside the core's execution lane. Queue paging and maintenance scheduling remain integration work.
