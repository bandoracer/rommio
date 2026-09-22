# Expanded progress qualification — 2026-09-22

The save/sync contract remains [save-sync-spec.md](save-sync-spec.md): game saves
and emulator snapshots are separate, equally important streams. Android receives
portable game-save data from iOS and cold-boots it; each client restores its own
compatible snapshot. This pass does not load an iOS snapshot into an Android core.

## Gameplay evidence

On iPhone Air, Majora's Mask's own file menu created a new file named **A**.
The game-save hash changed from
`75fc05c335851f0083f2f51a5852175329c32e4c6f833802aa440c5c3a6f2795` to
`53e167414e45ec0da185c5c1778dd238c28ed36cfdfd4c79efd63717eb74667d`.
Android then cold-booted the same ROM from those cloud game-save bytes, and its
file menu displayed **A**. This verifies a game-created file, rather than a host
injecting a save or merely observing pristine RAM. It does not qualify an entire
playthrough or the later owl/statue save paths.

- [iPhone game-created save](validation/expanded-progress/iphone-n64-created-save.png)
- [Android cold-boot readback](validation/expanded-progress/android-n64-cold-boot.png)
- [Live RomM N64 report](validation/expansion-n64-gameplay-sync.json)

The N64 run used the existing dedicated QA account on the live RomM 5.2.0 server.
It preserved prior QA history and did not access another account's saves.

## Recovery scenarios

Each handoff case checks these boundaries independently:

1. iOS captures and publishes game-save data plus its paired resume snapshot.
2. The app is externally terminated and restores locally with progress transport offline.
3. Further offline play creates durable progress and pending publication work.
4. Another external termination recovers that new local snapshot; reconnect publishes it.
5. Android downloads the game-save data and cold-boots without applying an iOS snapshot.
6. Android commits its own snapshot. The server accepts its blob, but the test loses
   the upload response. Game-save publication still completes while the snapshot
   remains pending. Retry verifies the accepted blob without creating a duplicate.
7. A new Android database and vault download and restore the Android snapshot and
   exact paired persistent-memory regions.
8. iOS receives Android's game-save successor. A separate iOS app data root then
   downloads and restores the compatible iOS snapshot with its matching game save.

Offline behavior is injected at the application progress-transport boundary; the
server stays online. Android fresh-store recovery is not an Android OS process-kill
test. iOS process termination is driven externally with `simctl`.

## Isolated expansion fixtures

The isolated Compose server uses only named volumes and localhost port 18765.
Original Genesis, Master System and Game Gear programs write their own SRAM on
the emulated CPU. PSP runs the open-source `cubevfpu` demo with an explicitly
synthetic multi-file memory-stick fixture, including an empty directory; its
data file changes during the offline phase. No retail-game save UI is claimed
for these four fixtures. Results are recorded in
[the isolated handoff report](validation/expansion-isolated-save-sync.json).

| Platform | Source of game-save bytes | Two-way game-save handoff | Fresh cloud snapshots on each client |
| --- | --- | --- | --- |
| N64 | Majora's Mask file menu | Passed, live server | Passed |
| Genesis | Original emulated CPU counter | Passed, isolated server | Passed |
| Master System | Original emulated CPU counter | Passed, isolated server | Passed |
| Game Gear | Original emulated CPU counter | Passed, isolated server | Passed |
| PSP | Explicit synthetic multi-file fixture | Passed, isolated server | Passed |

All five also passed iOS offline capture/restart and Android accepted-upload
response-loss recovery. Test targets were iPhone Air/iOS 27 arm64 and the dedicated
Android API 35 arm64 emulator. N64 ran before the Android envelope-cache optimization;
the four isolated cases qualified that optimization on the final Android build.

Reproduction and fixture seeding are documented in
[the isolated-server README](../tests/integration/romm/README.md).

## Fixes and checks

- Both clients now reuse validated immutable history envelopes within one sync
  reconciliation. The cache is disk-backed and discarded afterward; future syncs
  still revalidate remote history.
- iOS now reuses an already verified local artifact when another incoming
  checkpoint refers to the same bytes. Previously it could download the same
  game-save/snapshot blob repeatedly while receiving history.
- Swift's six sync tests pass, including exact two-store restoration, one download
  per shared blob/envelope, later remote corruption detection, retained damaged
  local originals, conflicts, independent streams and lost-response recovery.
- Android's 123 unit tests pass. The installed-app expansion handoff test asserts
  one envelope download per reconciliation and duplicate-free upload recovery.
- Both mobile Debug builds compile. Separate emulator runs supply the mobile
  behavior evidence; unit results alone are not used to claim platform parity.

Large retained histories still take time to download into a fresh installation.
This change removes repeated work; it does not introduce lazy history hydration
or delete snapshots to make the test faster.

## Remaining boundaries

The current live library has no Genesis, SMS, Game Gear, Sega CD, PlayStation or
PSP games. Those systems still need representative user-provided game files for
retail gameplay/save-menu qualification. PlayStation and Sega CD also need verified
BIOS files. Their previously tested controls/native fixture paths are separate
from BIOS-backed disc-game qualification. Physical devices, controller pairing,
long-session performance and background OS scheduling remain outside this pass.
