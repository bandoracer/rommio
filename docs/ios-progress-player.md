# iOS progress player qualification

Updated 2026-09-18. The [save/sync specification](save-sync-spec.md) remains authoritative. Game saves and emulator snapshots are independent, equally supported progress streams.

## Active route

Owned, digest-verified single-file NES installs use the schema-3 player when the bundled FCEUmm binary matches its pinned build provenance and its effective persistence descriptor qualifies. Current layouts are iNES/NES 2.0 mapper 0 and mapper 19, submapper 0, with the supported battery/PRG-RAM declarations. All five native memory regions are inventoried; mapper 19 includes its additional battery segment. Unsupported layouts stop qualification rather than silently dropping memory. The subsequent [cartridge expansion](cartridge-progress.md) adds pinned GB/GBC, GBA, SNES, and DS layouts to this same owner. Other cores and unassigned legacy installs retain their contained local route.

The Apple build uses the same vendored source manifest and persistence extension as Android. `build_pinned_core.py` validates every source against that manifest and compiles for macOS host tests, iOS Simulator, or iPhone. The Xcode packaging phase signs the dylib and records its final SHA-256. Runtime activation validates provenance, architecture and bytes. Effective core options, firmware, platform, ABI and exact signed core digest participate in snapshot identity.

A matching ROM/core name does not make Android, simulator and iPhone states interchangeable. Both clients sync and retain all valid streams. A snapshot that does not match the receiving runtime and current game-save data leads to an explicit cold-boot choice; compatible game-save data remains usable independently.

## Ownership and durability

`ApplicationProgress` owns the store and coalesced per-content sync jobs. `ProgressSessionOwner` accepts ordered commands that survive cancellation of the waiting UI task. The native recursive lock and pause barrier prevent frames from running across capture or restore.

Launch checks cloud progress with a bounded wait and exposes an explicit unchecked-cloud continuation when necessary. Explicit Retry awaits the application-owned sync job rather than repeatedly applying the initial three-second limit. Each session uses isolated system/save/state directories and freezes its selected causal parents. Incoming sync updates durable metadata without replacing running memory.

Pause/background, periodic capture, manual snapshots, confirmed restore, reset and exit use the same owner. Capture commits complete game data first, then a separately retryable snapshot paired to that exact operation. A serialization or snapshot-write failure cannot invalidate the committed game save; the preceding snapshot remains retained. Hash-verified ready journals replay exact bytes after interrupted commits. Failed game commits keep the session paused for retry. Captured bytes are persisted before background network work; OS expiration ends the background assertion and cancels network work while durable intent remains queued.

Restore validates complete memory and state compatibility, asks for confirmation when game data differs, and requires a pinned safety snapshot before mutation. Native failure rolls back to safety or stops the session if rollback fails. Legacy imports require explicit selection/confirmation, retain original bytes, and create pinned converted checkpoints. A lone SRAM file is rejected for cartridges requiring extra persistent regions. Snapshot deletion uses state-only tombstones. History and explicit branch resolution are reachable in the player panel.

## Sync and recovery

The qualified server is RomM 5.2.0. Both lanes use the existing schema-3 immutable operations, account/server/content binding, dependency ordering, inventory/readback checks, and durable outbox. Foreground startup, captures, reconnect and a foreground timer request work. iOS background time is best effort; no perpetual background execution is promised. Pending work survives process exit.

The new route bypasses the legacy SRAM writer and unsafe legacy cloud writers remain disabled. Old local files are presented for explicit recovery rather than silently assigned to a new account/content identity.

## Evidence

- macOS real pinned core: mapper 0 and mapper 19 exact game-memory/state round trips, full inventory rejection, game success despite state failure, safety/confirmation, failed restore rollback, exact journal retry after a forced SQLite failure, cancellation survival, database reopen, cold boot, logical snapshot deletion, and original-preserving import. Six native session tests pass.
- Full Swift package suite: 132 tests, two existing environment-dependent skips, zero failures. This includes protocol/store/transport fault fixtures; native session tests require the pinned host core and do not silently skip it.
- Simulator app builds and passes strict deep signature validation. Pinned host, simulator and iPhone core artifacts build successfully.
- Real installed app and Android native instrumentation against a dedicated RomM 5.2.0 stack: actual native frames, background capture hook, iOS-to-Android game transfer, explicit Android cold boot, Android-to-iOS successor transfer, separate retained snapshot streams, externally terminated iOS process, exact offline game/state restore under a new PID, and reconnect publication all pass.
- A fresh isolated iOS application root (separate database, device ID and vault) downloads and restores the exact remote game-save/snapshot pair without access to the first installation's files. This verifies cloud snapshot recovery independently of the local restart test.
- Separately signed iPhone test app builds and installs. Physical launch currently fails because iOS reports the paired phone is locked. Physical gameplay/background/termination qualification remains unproven and is now explicitly deferred at the user's request.

The fixtures use original generated 6502 counter programs, dedicated simulator/emulator identities and disposable server records. No personal progress or production server records are used. The test build is not a distributed release.

## Reproduce

From the repository root:

```sh
python3 scripts/ios/build_pinned_core.py --sdk macosx --arch "$(uname -m)"
swift test --package-path ios
scripts/ios/build_simulator_app.sh
```

For live interoperability, prepare the isolated stack under `tests/integration/romm`, its synthetic probe accounts, a simulator named `rommio-progress-*`, and an Android AVD named `rommio-progress-test-*`. Build the Android standard debug app and test APK, then run:

```sh
python3 scripts/ios/verify_progress_parity.py \
  --simulator <dedicated-simulator-udid> \
  --android <dedicated-emulator-serial> \
  --credentials <private-probe-credentials-json>
```

The harness validates the isolated Docker context, named volumes, port and server version before mutating fixture data. It removes injected credentials and restores the isolated server after the intentional offline interval. The debug-only iPhone local fixture requires the separate `.paritytest` bundle ID and `ROMMIO_PROGRESS_LOCAL_PHASE=seed-local` or `verify-local`; the verification phase also uses `ROMMIO_PROGRESS_PROBE_PAUSED=1`. It needs no real server credentials.

## Remaining boundaries

The core cutover is now complemented by [iOS progress backup and recovery](ios-progress-backup.md): deterministic schema-2 conversion, complete progress export/import, interrupted replacement recovery and rollback, retained originals, reversible orphan/capture archiving and damaged-artifact repair. This does not close every remediation item. Bounded retention/GC, Android ZIP migration interchange, physical disk-full/power-loss checks, broader core/file-backed/disc support, controller/firmware/catalog parity and distribution releases remain separate. Physical-device qualification is deferred. Exact signed core digests can change across builds; incompatible snapshots remain retained and compatible game data can still cold boot.
