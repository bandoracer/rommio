# Independent game-save and snapshot synchronization

Implemented for the qualified Android player route, September 18, 2026. The behavioral authority remains [save-sync-spec.md](save-sync-spec.md). This document specifies the schema-3 implementation and its qualification boundary; [schema 2](progress-protocol-v2.md) remains readable history.

## Two independent histories

| Reference | Payload and dependencies |
| --- | --- |
| `game/current` | Complete game-save bundle, no emulator state. Parents change this same reference. |
| `game/archive/<UUID>` | Complete historical game-save bundle used when upgrading an old slot; never becomes current automatically. |
| `state/resume/<compatibility-key>` | Snapshot plus its complete matching game-save bundle. Parents change this resume reference. |
| `state/slot/<UUID>` | Explicit manual snapshot, including its matching game-save bundle. Parents change this slot. |

An operation is exclusively one game reference or one/more state references. A snapshot checkpoint adds `gameSaveOperationId`, referencing an already installed schema-3 game operation for the same binding. Its save-compatibility descriptor and sorted non-state artifacts must exactly equal that game's checkpoint. Game operations cannot depend on state operations. Dependency validation and backup validation include both reference parents and this pairing edge.

Capture first commits the game operation with its durable outbox entry. If snapshot capture/commit succeeds, a separate state operation follows. A failed state capture or transaction preserves the previous resume and manual slot, reports the partial outcome, and permits subsequent game captures and publication. Exact staged state bytes remain recoverable after an interrupted transaction. A lost post-transaction acknowledgement is checked against the committed immutable envelope before reporting failure. Failed game commits retain exact retry bytes and keep the player paused.

An automatic resume requires compatible core bytes, ABI, platform, state format, effective options, firmware, and complete verified artifacts. Its paired game operation must be the selected current game operation or an ancestor with identical game-save artifacts. Thus unchanged periodic game captures do not unnecessarily invalidate resume; a changed game save does. Missing/corrupt/incompatible states leave the independent game-save cold-boot path available.

Manual restore compares the paused core's current persistent memory with the target bundle before any safety capture or mutation. Different data requires explicit confirmation. Cancel leaves both streams unchanged. Confirmed restore first captures, verifies, and pins a safety snapshot, then restores the requested state and its paired game data. Rejection restores the safety checkpoint or stops the session. Choosing a conflicting current game/resume creates a causal resolution with exactly the displayed heads as parents; all alternatives are pinned in history. A concurrently changed inventory rejects the stale choice.

## RomM transport

Only the capability-probed RomM **5.2.0** inventory API is enabled. The authenticated service checks the exact account and server binding. Both logical data types use opaque records under `/api/states`; these are transport containers, not a claim that game saves are emulator states.

Names are private to this protocol and content binding:

- `__rommio3_<binding-key>_blob_<sha256>.bin`
- `__rommio3_<binding-key>_op_<operation-UUID>_<payload-sha256>.json`

Upload artifacts before the immutable envelope. Discover an existing exact name before retrying; never request overwrite/delete. Verify every matching record by size and SHA-256 readback before acknowledging the exact leased envelope. A lost response is recovered through inventory, including uploads that reached the server. RomM has mutable filenames and no qualified CAS: uniqueness and readback are client requirements, not server immutability guarantees.

Receive the complete account/ROM inventory, then filter by binding prefix and validate payload names, immutable IDs, manifests, dependencies, hashes and sizes. Install parents before descendants; preserve concurrent heads rather than comparing clocks. A reused ID with different bytes cannot silently select a winner. Missing state artifacts do not prevent complete game operations from arriving. Exact remote bytes can repair damaged retained artifacts; damaged originals are backed up and reference heads remain unchanged. Intentionally evicted history is not silently repopulated.

Local-only operations retain that intent during synchronization; the iOS recovery UI requires an explicit choice to enable their publication. Game and state publication use separate leases; state publication waits for its paired game operation, while new game publication continues during state transfer. Both lanes have durable retries and account-bound authentication parking. WorkManager requests connected retry after captures, at startup, and periodically; an application-owned immediate drain keeps foreground saves moving. Periodic retry also receives other-device progress when no local capture is pending. Removed ROM installations do not erase publication intent.

Before launch, a three-second reconciliation budget allows local play to proceed through an explicit **Cloud progress not checked** choice when the server/check is unavailable. Incoming revisions update the catalog only. Running sessions retain the chosen causal parents and native memory until an explicit restore or a new session. UI status names game saves and snapshots separately, reports pending work and incomplete checks, and does not label a conflicting stream synced.

## Existing data and platform boundary

On Android, schema-2 bytes, IDs, heads, original legacy files and history are preserved. Deterministic schema-3 operations copy complete current game/resume/slot references; historical slot game data uses archive references. Incomplete/unpaired or damaged old states stay recovery history. Retrying an upgrade does not add roots or rewrite ancestry. The old writer is not resumed for qualified sessions. Format-3 backup containers preserve exact envelopes for both progress schemas and validate their dependency order.

Production integration is active on Android and iOS for owned, verified single-file content and the bundled, qualified FCEUmm NROM/Namco layouts. The Apple native bridge, ordered owner, recovery UI and application sync service are exercised by real app gameplay, bidirectional Android/iOS game-save exchange, separate snapshot streams, and offline process restart. See [Apple evidence and remaining gates](ios-progress-player.md).

Existing schema-2-only clients do not interoperate with schema 3 and must not reinterpret its transport records. Other RomM versions remain local with explicit unsupported-sync status. Other cores, file-backed save formats, disc sets, cross-container backup interchange, physical lifecycle qualification and distribution releases remain separate work. There is no remote garbage collection; immutable remote history grows. Apple snapshots use the exact signed core digest and platform/ABI; receiving another platform's snapshot preserves it but requires explicit cold boot from compatible game data.

## Reproducible verification

`ProgressSyncTest` covers independent lanes, snapshot capture/write failures, preserved slots, exact retries, offline divergence, cold fallback, explicit resolution, corrupt download rejection/repair, deterministic upgrade and backup preservation. Protocol unit tests enforce exact pairing and lane separation. Existing native coordinator, application owner and production Compose player tests cover save/retry/exit/import/restore flows.

After building `assembleStandardDebug assembleStandardDebugAndroidTest`, run:

```sh
python3 scripts/android/verify_progress_sync.py --serial emulator-5580 --credentials /tmp/rommio-probe-credentials.json
```

The script refuses non-disposable emulator names or a server outside the isolated Compose stack. `LiveProgressSyncTest` uses two independent Room/file stores, the production transport and real bundled FCEUmm sessions against RomM 5.2. It verifies exact game memory and state, cold boot, rollback confirmation and pinned safety, plus a lost response after a real state upload. `ProcessDeathProgressTest` then backgrounds a running native session, waits for its fsynced completion marker, force-stops the process externally, disables network access and verifies exact game data and snapshot recovery in a new process. Credentials are synthetic, private and removed afterward. These tests skip in an ordinary unprovisioned instrumentation run.
