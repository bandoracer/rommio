# Android progress backup format

Status: migration-bundle format **3** is implemented by the Android importer/exporter. It transfers v2 progress without enabling the new player writer or cloud publication. Formats 1 and 2 remain readable. Room schema 13 and the progress wire protocol are unchanged.

## Contents and identity

The ZIP contains the existing manifest, database, credential, control, and library entries, plus `progress-v2/`. `database.json.progress` is a version-1 snapshot containing every row from the eight v2 tables: namespaces, contents, operations, heads, outbox, quarantine, history, and history-index cursors. The manifest's bundle version and the snapshot version are separate compatibility checks.

Operation and quarantine payloads use canonical Base64, preserving their exact bytes rather than re-encoding their JSON. Checkpoint manifests stay inside their original operations. Local namespace/content IDs, operation/checkpoint IDs, local sequences, causal parents, manual-slot references, tombstones, pin/eviction records, informational times, and historical device/session attribution survive transfer.

All regular files and directories under the private progress root are inventoried by relative path. File entries include length and SHA-256; directory entries have zero length and a null hash. This preserves empty native directories, immutable blobs, repair backups, staging, working sessions, abandoned sessions, pending captures, and cleanup journals. Recovery files are opaque backup data: import never interprets a ready marker as permission to publish or adopt progress. The imported workspace must go through normal explicit recovery and artifact validation.

The root's internal owner lock and import-generation marker are excluded. The stable process lock is a sibling of the progress root and is never archived. The receiving installation's v2 device UUID is not part of the bundle and is not replaced. Imported profiles still require account revalidation; an archive does not authenticate its claimed account.

## Validation and recovery semantics

Before activation, validation checks unique keys, namespace/content binding, operation hashes and strict protocol decoding, topological parent order, checkpoint identity reuse, the complete causal head set, publication intent, history references/sequences, pins, index cursors, and quarantine hashes. Unknown active progress schemas fail preflight; unknown schemas already in quarantine remain opaque quarantine bytes.

The extracted/streamed progress inventory must exactly match the snapshot, including directories. Missing, extra, changed, duplicate, unsafe, or reserved entries reject the package before replacing active files or metadata. Symbolic links cannot be exported or followed. Metadata entries are bounded at 64 MiB, row/entry collections at 200,000, and individual progress files at 8 GiB. Unsupported sizes fail explicitly. These are format bounds, not claims of large-library performance qualification.

A previously damaged or missing blob is different from damage to the archive in transit. The snapshot inventories the source's actual available bytes. It can therefore preserve a damaged checkpoint, while inspection and the import result report its missing/damaged artifact count. The UI requests confirmation for such a recovery import even on an empty installation. Normal progress materialization still verifies the checkpoint's expected bytes and refuses an invalid load. Evicted history is not counted as an unexpected missing artifact. Invalid active operation/ownership metadata rejects backup/import; a general raw-database salvage flow remains separate recovery work.

Publication states `PUBLISHED`, `PENDING`, `PARKED_AUTH`, and `LOCAL_ONLY` retain their meanings. A `LEASED` row becomes `PENDING`; every lease ticket/deadline is cleared. Attempts, retry scheduling, payload identity, and diagnostics are preserved. Import does not acknowledge unpublished data, invent operations, change local-only intent, or start cloud publication. A stored historical acknowledgment is not a fresh remote reconciliation result.

## Activation and old bundles

Import/export first takes exclusive application storage ownership, draining ordinary work and refusing active native/browser sessions. It closes the lazy idle progress store and takes the stable sibling process lock. That lock remains held across staging/snapshot, renames, metadata application, and cleanup; another store cannot recreate the progress root between renames. Store consumers acquire a fresh instance afterward. A stale direct store handle fails closed; callers must reacquire through the application owner. Exports do not advance the library generation or create progress revisions.

New imports always stage both the library and progress roots. Format-1/format-2 input is normalized to an empty v2 snapshot. Replacing a populated installation with such an older bundle retains the old progress directory and full prior v2 metadata alongside the previous library, then clears active v2 rows in the same Room transaction as the imported legacy records. It does not leave old progress silently attached to a different imported library. Existing replacement confirmation applies when the only local data is v2 metadata or orphan recovery files.

The [import journal](import-recovery.md) installs both roots before applying metadata and resumes forward after interruption. Originals remain in `previous-library`, `previous-progress-v2`, and `previous-database.json`; credentials retain their existing protected recovery handling. Old three-line pending journals remain recoverable without touching progress. Older binaries that only understand bundle formats 1/2 reject a format-3 ZIP.

## Evidence and remaining scope

Unit coverage exercises exact-byte JSON round trips, unknown quarantined schemas, invalid bindings/hashes/graphs/indices, concurrent branches and deletions, empty directories, changed/missing files, unsafe paths, and damaged-versus-evicted artifact reporting.

Device coverage uses actual Room, ZIPs, encrypted credentials, preferences, and on-disk recovery. It verifies complete v2 round trips with published/leased/parked/local-only intent; concurrent branches and slot tombstones; pinned/evicted history; exact operation bytes; successful artifact materialization; opaque quarantine; a real captured-operation journal imported before its first commit and explicitly replayed; damaged artifact preservation; malformed archive rejection; format-1/format-2 compatibility; and reopening at every activation phase. A separate file-lock handle proves that competing acquisition cannot recreate a temporarily absent root or modify transfer destinations.

These checks use injected interruption and database/owner recreation, not abrupt OS termination, power loss, or a filled physical disk. Independent-process/device qualification, retained-backup recovery UI, legacy-to-v2 checkpoint conversion, and production player/recovery integration remain open under R06/R10/R13. Portable credential encryption is unchanged from earlier formats. See [current evidence and results](remediation-status.md).
