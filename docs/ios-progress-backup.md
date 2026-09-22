# iOS progress backup and recovery

Implemented 2026-09-18. This completes the iOS backup/import and recovery batch against the [save/sync specification](save-sync-spec.md). Physical-device qualification is deferred at the user's request; it is not a gate on this batch.

## User workflow

Settings → Game saves and snapshots → Backup and recovery offers:

- Create a backup, then save/share the resulting `.rommioprogress` file.
- Inspect an imported backup before confirming replacement. The preview reports operation/file counts and any already missing or damaged artifacts. Restore retains the previous progress so it can be exported and restored again.
- Retry exact pending captures, or move incomplete/damaged captures and abandoned player workspaces into a reversible recovery archive.
- Repair a damaged game-save or snapshot artifact from a file with the exact expected size/hash, retaining the damaged original; alternatively retry account-bound cloud repair.
- Finish an interrupted import, explicitly roll back to its retained original if necessary, and export previous versions.
- Explicitly enable sync for local-only history. Merely restoring or checking sync does not turn local-only records into uploads.

Exit the player before backup or recovery operations. A player lease covers both qualified and legacy sessions, including preparation. Active sessions block maintenance. Export/import closes the idle store and retires outstanding handles; a stable sibling file lock prevents competing writers from recreating the root during replacement. Network jobs are canceled/drained and stale completion callbacks cannot modify the new generation. Import does not change profiles, credentials, installed games, or the receiving installation's device identity.

## What is preserved

The archive contains exact operation payloads, account/server/content bindings and local IDs, independent game and snapshot references, manual slots/tombstones, concurrent branches, pins, quarantine bytes, and complete outbox state. Snapshot-to-game dependencies stay intact. `PUBLISHED`, `PENDING`, `PARKED_AUTH`, and `LOCAL_ONLY` retain their meanings. `LEASED` becomes `PENDING`, with stale lease tickets cleared. A restored acknowledgment is historical; normal sync still performs remote checks.

All regular files and empty directories in the progress root are inventoried, including immutable blobs, repair originals, staged capture bytes, interrupted sessions and archived recovery material. Installation identity, generation markers and owner locks are excluded. Legacy SRAM, state/screenshot files and exact legacy journal payloads are included under `legacy-recovery/`; importing them does not bind them to an account or activate an old writer. The qualified player's explicit legacy-import panel lists restored SRAM/state files for deliberate assignment and conversion. Existing legacy files in the library stay intact.

A damaged source blob can be backed up as damaged recovery material. That is distinct from an archive file that fails its recorded checksum, which rejects the import. Missing/damaged artifact counts are shown before activation, and normal materialization still refuses corrupt bytes. Invalid active metadata fails preflight rather than silently dropping rows.

This is a progress backup, not a full application/library image. ROMs, cores, BIOS, credentials, catalog caches and general settings are excluded. The Apple archive is versioned independently of Android's ZIP migration container; it does not yet import Android migration ZIPs. Cross-platform gameplay progress continues to use the shared schema-3 sync protocol.

## Format and validation

Version 1 starts with `ROMMIO-PROGRESS-1\n`, an unsigned big-endian 64-bit metadata length, then a JSON manifest and the ordered bytes of its regular file entries. Each file has a relative path, length and SHA-256; directories have zero length and no digest. Metadata uses typed SQLite cells so operation/quarantine BLOBs retain exact bytes. The manifest has an explicit format/version and a separate metadata snapshot version.

Metadata is bounded at 64 MiB, entries/rows at 200,000, individual files at 8 GiB and aggregate file bytes at 64 GiB. Bodies are streamed in 64 KiB chunks. These are format limits, not large-library performance claims. Duplicate/unsafe/reserved paths, symbolic links, special files, missing parent inventories, truncated bodies, extra bytes and hash mismatches are rejected. Validation in a separate database checks schema, uniqueness, binding ownership, payload identity/hash, causal dependencies, checkpoint reuse, exact head sets, pins, quarantine and publication intent. The staged inventory is rechecked at activation, so a preview is not permission to import subsequently changed bytes.

## Interrupted activation

Preflight extracts to a sibling recovery workspace and validates without changing active progress. Confirmation freezes the prior metadata and stages the replacement with the receiver's device identity. A fsynced journal and redundant transaction copy precede all active-root changes.

Activation retains the original root, renames the complete incoming root into place, and atomically replaces only progress tables in SQLite. The generation marker identifies the replacement. The journal then moves into the retained workspace as a completed record. Startup recovers the same transaction before obtaining a player/store writer. Recovery is idempotent and never invents operations. A torn primary journal can be rebuilt from its unique redundant transaction copy; the damaged journal is retained. Ambiguous/missing recovery evidence fails closed with originals intact.

If the incoming tree is unavailable, Restore pre-import progress records a durable rollback decision, stages the retained original, restores its metadata, and keeps the abandoned replacement. The rollback decision also survives interruption.

Previous roots and metadata remain available through Export previous progress. This export can be inspected and imported through the same validated flow. Recovery archives are reversible and do not delete partial bytes. No automatic cleanup or remote garbage collection is added in this batch.

## Schema-2 upgrade

Complete current v2 game/resume/slot checkpoints are copied into independent v3 operations using the same deterministic UUID scheme as Android. Original bytes/history stay unchanged, historical-slot game data uses archive references, and snapshots name their exact new paired game operation. A damaged state does not prevent its verified game bundle from migrating. Incomplete/unpaired or damaged old state remains recovery history. Repeating an upgrade does not manufacture branches or revisions. Original local-only intent stays local-only; publication requires an explicit choice.

## Validation

- Backup/recovery tests exercise exact metadata/file round trips, published/leased/parked/local-only intent, concurrent branches, manual slots and tombstones, pins/quarantine, receiver identity, empty directories and partial workspaces.
- Fault tests recreate the database/manager at every activation boundary, reject corrupt archives and changed previews before mutation, recover a damaged primary journal, and preserve damaged originals during artifact repair.
- A capture with zero committed operations survives backup/import and is committed only by normal explicit recovery. Archiving partial capture bytes and returning them to the recovery queue is reversible. Active-player exclusion and retired-store rejection are tested; the sibling lock refuses a competing writer without recreating a moved root.
- The installed simulator app runs the pinned native core, creates distinct before/after histories, exports and stages a backup, and is externally SIGKILLed at all five durable activation boundaries. A new process recovers the exact imported operation bytes, verifies every artifact, and retains an exportable previous version. No Swift catch/defer cleanup runs in the killed process.
- Final full package suite: 145 tests, two existing environment-dependent skips, zero failures. The simulator app builds and passes strict deep signature and pinned-core digest checks. Results are also recorded in [remediation status](remediation-status.md). OS background, physical disk exhaustion and physical-device qualification remain separate; interactive desktop-driven UI checking may require an unlocked host.

Reproduce from the repository root:

```sh
python3 scripts/ios/build_pinned_core.py --sdk macosx --arch "$(uname -m)"
swift test --package-path ios
scripts/ios/build_simulator_app.sh
python3 scripts/ios/verify_progress_recovery.py --simulator <rommio-progress-simulator-udid>
```

The process-kill harness refuses personal simulators and uses fresh `rommio-progress-fixture` roots with generated counter ROMs. Its DEBUG-only stop hook additionally requires that fixture root and an explicit boundary environment variable. No personal saves, credentials or production server records are used.
