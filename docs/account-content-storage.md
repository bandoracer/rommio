# Android account and content storage

Status: Room schema 11 and the new-download path implement account-scoped install/download keys and content-scoped local progress. This is partial R05/R06 implementation. Legacy ownership resolution, archive/member and multi-disc identities, iOS parity, and v2 session/store activation remain open.

## Keys and installed content

`downloaded_roms` and `download_records` use `(namespaceKey, romId, fileId)` as their primary key. `namespaceKey` is a stable digest of the verified server URL/deployment path and server-side user ID. It is a database routing key, not the UUID namespace ID or remote identity of the v2 progress protocol. Queue authentication still uses the original captured profile and verified account guard.

Identical numeric IDs and filenames from two servers/accounts can now coexist. Selection, retry, cancellation, completion, and local removal address the namespace explicitly. Known-owned installs are shown for their profile; when a known-owned install and a legacy install share IDs, ordinary installed-file selection prefers the owned install. The preserved legacy copy is not overwritten by downloading a new owned copy.

For a new single-file install, `progressScope` is the existing progress protocol's content-binding digest: server/account, ROM ID, file ID, and verified SHA-256. Its mutable local working paths are:

- `progress-local/<binding digest>/saves/save.srm`
- `progress-local/<binding digest>/states/…`
- `progress-local/<binding digest>/screenshots/…`

The core's save directory follows that same installation scope. A replacement with identical bytes retains the current progress scope. Changed bytes create a new scope and leave prior save files and records intact. No automatic state or SRAM copy is made between content generations.

Manual slots, continuity journals, manual-slot journals/tombstones, and recovery history include `progressScope` in their primary keys. Player recovery observations and pending launch selections use that scope. The local bridge uses an owned install's captured profile even after the active profile changes. Deferred legacy save intent also carries the content scope in its payload and dedupe key. Cloud publication remains disabled.

This path identifies the downloaded single file. It does not yet identify extracted archive members or complete ordered disc sets, qualify cross-core state compatibility, or commit coherent immutable checkpoints. Those remain R05/R07/R09/R10 work.

## Schema migration and legacy data

The 10-to-11 migration rebuilds the affected primary keys transactionally. It derives install namespaces only from the account fields already recorded by verified download ownership. Rows without that information stay in the literal `legacy` namespace.

All pre-existing progress rows and installed progress scopes remain `legacy`. A matching installed ROM is insufficient evidence that an old global save belongs to its account or content generation. The migration leaves old files in place and does not relabel or copy them into a new scope. An identical-byte redownload of a migrated install retains its legacy progress scope for continuity.

Legacy paths, global manual slots, and unowned compatibility playback therefore remain an explicit unresolved migration boundary. They are not proof of fully account-isolated historical gameplay. [Explicit legacy-progress recovery](legacy-progress-recovery.md) now inventories old files and lets users copy selected SRAM/states into a verified single-file destination while preserving originals. Full ownership/reattachment, retained content generations, additional persistence formats, and qualification remain R06/R13 work.

## Bundle transfer

New exports use [migration-bundle format 3](progress-bundle-format.md). It retains format 2's exact manual-state records, labels, relative paths, scopes, installs, and journals, and adds the complete v2 progress snapshot and recovery files. Formats 1 and 2 remain readable with their legacy rules. Older clients reject the newer archive version instead of silently omitting progress. Known installed-ROM hashes and sizes are checked against extracted bytes before activation.

Import preflight validates each owned install's content scope and rejects scoped progress paths pointing into another generation. Equal ROM/file IDs across accounts are valid. Import summary counts include account identity. The existing durable import journal and revalidation requirement still apply; importing a bundle never authenticates its claimed account by itself.

## Evidence and remaining work

`OwnedDownloadQueueTest` now has 17 instrumented cases. Added/reworked cases verify two accounts with colliding IDs, separate install and save paths, manual-slot deletion isolated from another account, profile switching during local capture, identical versus changed replacement bytes, and migration of known install ownership without assigning old progress. Existing cancel/retry, HTTP, restart, auth parking, and install verification tests remain active.

At the original account-content batch, `MigrationRecoveryTest` had six cases, including a format-2 round trip with two accounts and colliding manual slots, preserved labels/bytes, and backward-compatible format-1 import. The Room migration fixtures reconstruct the actual prior primary-key shapes before exercising schema upgrades. Progress-store instrumentation still validates the full additive 8-to-11 chain.

Full Android checks pass: 66 unit tests per flavor, both lint tasks, both debug builds, and 44 instrumented cases with two existing live-server skips and zero failures/errors. Log: `/tmp/rommio-namespace-android-final.log`.

R05/R06 remain open for explicit legacy ownership mapping, account setup/reattachment flows, retained install generations and cleanup, archive/member/file-set descriptors, iOS storage parity, and process-kill/disk-full/device qualification. The new paths remain mutable working storage; they do not activate the separate v2 ProgressStore, session coordinator, or cloud reconciliation.
