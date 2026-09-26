# Progress v2 format and local storage

Status: **Preserved schema-2 foundation and history format.** Qualified Android sessions now write [schema 3](progress-protocol-v3.md), which separates game-save and state publication dependencies. Existing schema-2 bytes and ancestry are not rewritten. The remaining sections describe schema 2 specifically; the schema-3 document describes current Android remote activation, migration and UI behavior.

The [behavioral specification](save-sync-spec.md) remains authoritative. Schema 2's same-operation resume/game-save rule cannot satisfy independent game-save publication during a state-upload failure. Schema 3 resolves that gap with separate references and an explicit state-to-game pairing edge. Schema-2-only clients, including the existing iOS implementation, do not gain schema-3 interoperability through this Android change.

## Envelope and identity

An operation is one UTF-8 JSON object with `schemaVersion: 2`. Field names and types are defined in [Kotlin models](../app/src/main/java/io/github/bandoracer/rommio/domain/progress/ProgressModels.kt) and [Swift models](../ios/Sources/RommioContract/ProgressModels.swift). Each operation contains:

| Field | Meaning |
| --- | --- |
| `operationId` | Immutable lowercase UUID; retries use exactly the original payload bytes |
| `binding` | Normalized server origin/base path, stable account ID, ROM ID, ordered content files |
| `changes` | One or more reference changes, each with its own causal parents and checkpoint target or tombstone |
| `checkpoints` | Embedded complete manifests for the targets named by those changes |
| `deviceId`, `sessionId` | Lowercase UUID provenance; neither determines which branch wins |
| `createdAtEpochMs` | Display/diagnostic timestamp; never a conflict ordering rule |

A binding contains `serverKey`, `accountId`, `romId`, and ordered `files`. A file contains `fileId`, zero-based contiguous `discIndex`, `contentSha256`, optional `containerSha256`, and optional `memberPath`. Archive member identity requires both the container digest and a safe member path. Changing bytes, file order, account, or server creates a different content binding.

Local `namespaceId` and `contentId` are opaque UUIDs stored in SQLite. A unique `(serverKey, accountId)` maps to a namespace. A unique `(namespaceId, bindingKey)` maps to content. Neither display names nor server-provided paths become filesystem directory names. Resolving these records is implemented; assigning verified accounts and installed ROM generations throughout the existing app is still R05/R06 work.

## Checkpoints and compatibility

Each checkpoint includes its ID, binding, capture reason, provenance, save compatibility, optional state compatibility, `saveDataComplete`, and artifacts. Artifact fields are `sha256`, `byteLength`, `role`, `relativePath`, and `format`.

- Save compatibility describes `system`, `format`, `layoutVersion`, optional `coreId`, and whether empty save data is valid (`allowsEmpty`). Its values must eventually come from qualified core capabilities, not guesses based on file extensions.
- State compatibility describes the exact `coreId`, `coreSha256`, `stateFormat`, `platform`, `architecture`, core `options`, and `systemFiles` digests. `coreSha256` is the core artifact's digest before platform signing (save/sync specification §3.2).
- Artifact roles are `sram`, `rtc`, `memoryCard`, `saveFile`, and `state`. At most one state artifact is allowed, and it requires a state descriptor. A state artifact cannot be empty.
- A complete save capture contains save artifacts unless the descriptor explicitly allows an empty save. A checkpoint that declares an incomplete save cannot contain partial save artifacts.
- Artifact paths are NFC Unicode relative paths, without empty, dot, parent, backslash, colon, or control-character components. Paths cannot duplicate another artifact ignoring case or use another artifact as a directory. Safe materialization additionally rejects symlinks beneath the owned root.

Capture reasons are `periodic`, `manual`, `pause`, `background`, `exit`, `safety`, `restore`, and `import`. These describe why a capture happened; they grant no authority to replace another branch.

## References and ancestry

References are `save/current`, `resume/<compatibility-key>`, and `slot/<lowercase-UUID>`. A change includes `reference`, unique `parents`, optional `checkpointId`, and `tombstone`. Tombstones have no checkpoint target. Other changes must name an embedded checkpoint.

A parent must already be installed for the same binding and must itself change the same reference. Missing parents defer installation; cycles and cross-reference parents are invalid. A commit removes only the heads explicitly named by its parents, then adds its own operation as a head. Unnamed competing branches survive. The store refuses automatic checkpoint selection while a reference has multiple heads.

Updating a resume reference requires a complete checkpoint and a `save/current` change to that **same checkpoint in the same operation**. This gives the local store a conservative freshness rule: automatic resume is eligible only while its head operation is also the sole `save/current` head. A newer save-only operation makes the old resume ineligible even if those state bytes remain available in history. Explicit manual-slot restore will be handled by the session coordinator.

A resolution is another operation naming all heads being resolved as parents for each affected reference. A deletion is a branch event, not permission to erase a concurrent edit. A checkpoint ID cannot later identify different manifest content.

## Encoding and shared keys

The decoder rejects malformed UTF-8, duplicate JSON keys, unknown schema-2 fields, missing required fields, coercion between strings/booleans/numbers, fractional or exponent-form numbers, unsafe paths, and inconsistent manifests. Optional fields may be absent or JSON null. Unknown schema versions have a distinct upgrade-required error; the caller can retain their original bytes in quarantine.

Limits include a 1 MiB envelope, 64 changes/checkpoints/content files, 256 parents per change, 1,024 artifacts per checkpoint, 8 GiB per artifact, and 128 entries per compatibility map. Identifiers and hashes have explicit validators. IDs/timestamps carried as JSON integers stay within the exact integer range `[-(2^53-1), 2^53-1]`, with nonnegative ROM/file IDs. Account text and option values use NFC; server keys use normalized ASCII URL text. Paths and option values have separate byte limits in the validators.

**JSON is not globally canonicalized.** Kotlin and Swift can serialize key order and optional nulls differently. The first serialized operation is the immutable payload: local retries, remote uploads, and acknowledgment compare its SHA-256 and original bytes. Re-encoding a downloaded operation is not a valid retry. Reusing an operation ID with different bytes is rejected even when the JSON decodes to equivalent values.

Binding and state keys use a separate deterministic encoding. For each field, append the decimal length of its UTF-8 bytes, `:`, and those bytes. Hash the concatenation with SHA-256.

- Binding fields: `rommio-binding-v2`, server key, account ID, decimal ROM ID, decimal file count; then, in disc order, each file ID, disc index, content digest, container digest or empty string, and member path or empty string.
- State fields: `rommio-state-v2`, binding key; save system, format, layout version, core ID or empty string, lowercase `true`/`false`; state core ID, core digest, state format, platform, architecture; then options and system-files maps in that order. Each map adds its count, then key/value pairs sorted by unsigned UTF-8 key bytes.

The [shared fixtures](../tests/fixtures/progress-v2) include expected keys and raw payload digests, Unicode/archive content, explicit-null fields, a save-only checkpoint, a tombstone, and malformed inputs. Both implementations consume the same fixture bytes. Their expected hashes were independently calculated, not generated by either production decoder.

## Local commit and recovery

The initial Room schema 9 and GRDB migration `v4_progress_store` add namespaces, contents, immutable operations, reference heads, outbox records, and raw quarantine records. Existing application tables remain intact. These additive schema migrations are not the cross-store legacy-save migration required by R06.

Each store owns one fixed app-private root and holds an exclusive process lock. Android serializes access through a coroutine mutex; Swift uses an actor with no suspension inside a commit. The root resolves trusted operating-system aliases once. All descendants are checked for symlinks. Callers must use one stable root/database pair and close the owner before replacing either.

A local commit proceeds as follows:

1. Validate identity, payload, existing parents, and checkpoint-ID reuse.
2. Copy artifacts to unique temporary files, check their exact sizes and SHA-256 digests, synchronize files, atomically install immutable blobs, and synchronize parent directories.
3. In one SQLite transaction, insert the original operation bytes, update affected heads, and insert its immutable outbox entry. Writers request SQLite `synchronous=FULL`.
4. Only after the transaction commits may the caller report the checkpoint saved locally.

The file layout is `<root>/<namespaceId>/<contentId>/blobs/<sha256>` with separately copied writable `working/<sessionId>/` directories. A working copy never shares mutable file contents with an immutable checkpoint. Its files are verified and installed through a staging directory before the session receives it. Android uses these `working` directories for temporary verified loader copies; native runtime files now live in separately owned `sessions` directories with abandoned-session inventory and cleanup journals. See [working sessions](android-working-sessions.md) and [application ownership](android-application-session.md).

Failure before the database commit leaves the old references and outbox intact; installed but unreachable blobs are safe orphans. Failure after commit leaves the new operation and outbox recoverable. Retrying the exact committed payload is idempotent and checks that its artifacts remain valid. Corrupt or missing artifacts cause an explicit failure; the store does not boot a partial checkpoint or silently select another branch.

Outbox states currently include `PENDING`, `LEASED`, `PARKED_AUTH`, `PUBLISHED`, and `LOCAL_ONLY`. A lease identifies one operation ID, exact payload hash, and unique lease ticket. A later save cannot be acknowledged by an earlier upload. Stale tickets are rejected after recovery/re-leasing. Only a verified matching payload hash can mark the leased row published. Publication waits for every parent operation to be marked published; a local-only ancestor currently blocks a child until explicit publication promotion is implemented. No silent promotion occurs.

A new exclusive owner resets abandoned leases to pending. Authentication parking and resumption are scoped to an exact identity. These are durable queue primitives; request scheduling, actual verification against RomM, received-operation acknowledgment, and publication promotion are not yet wired.

## Android history and maintenance extension

Android schema 13 now adds bounded checkpoint history, stable local operation sequences, pins, verified artifact/envelope repair, and protected local retention. [The recovery contract](android-progress-recovery.md) defines the APIs, ordering, grace period, failure boundaries, and qualification limits. Local-only/unpublished operations, manual checkpoints, current/conflicting heads, and pins remain protected; intentionally evicted history retains its causal metadata. This is a local storage extension, not a change to schema-2 envelope bytes or cross-device keys. Swift parity and application activation remain open.

Android also has a tested [native capture-to-checkpoint writer](android-checkpoint-capture.md): one emulation barrier captures memory/state/options, explicit memory-only qualifications gate completeness, and idempotent commits preserve the selected session's causal baseline. It remains unactivated pending production persistence qualification, restore/coordinator integration, and migration/recovery UI.

## Evidence and activation gates

The Android instrumentation suite runs on real SQLite/Room in an isolated emulator. The Swift suite runs against temporary on-disk GRDB databases. They inject failures after file installation and at each database commit boundary, close/reopen stores, verify branch preservation and explicit resolution, invalidate stale resume, test account/content isolation, reject corrupt bytes and changed operation payloads, recover leases, scope auth parking, and validate additive migration. Symlink and exclusive-owner cases are included.

Those are exception/rollback and reopen tests, not proof of sudden power-loss behavior. Before this replaces active gameplay storage, finish:

- Verified installed-content/account binding, resumable legacy migration, pinned core descriptors, and firmware identities (R05–R08).
- Session-owned coherent capture, readiness, restore, and lifecycle ownership (R10).
- Qualify the Android history/repair/retention APIs through the real app, complete Swift parity and working-session cleanup, and integrate maintenance without losing pending/conflicting history (R09).
- Process-kill fault tests, disk-full/cancellation qualification, closed-owner and queue recovery under real app lifecycles (R09/R17).
- Remote object naming, parent/inventory fetch, received-operation handling, explicit local-only publication promotion, exact readback, and two-client server integration (R11/R12).
- Accurate save/sync/conflict UI and physical-device qualification (R13/R17).

Until those gates pass, existing gameplay uses the contained legacy local path and cloud save synchronization remains paused. No personal saves are imported by these new primitives.
