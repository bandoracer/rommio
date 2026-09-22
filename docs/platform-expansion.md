# Sega, PlayStation, Nintendo 64, and PSP expansion

This increment implements the three platform groups requested after the original
cartridge qualification. The shared [save/sync spec](save-sync-spec.md) remains the
contract: game saves and resume snapshots are separate, equally important lanes.
A snapshot always keeps its matching game-save data. A game-save cold boot does
not apply an emulator snapshot. Snapshot compatibility includes the exact native
binary, platform, architecture, effective options, and firmware/assets hashes.

## Implemented persistence

| Platforms | Pinned core | Complete game-save inventory |
| --- | --- | --- |
| Genesis/Mega Drive, Master System, Game Gear | Genesis Plus GX | Stable complete 64 KiB SRAM/EEPROM buffer when present; an explicit empty inventory otherwise |
| Sega CD | Genesis Plus GX | Internal 8 KiB backup RAM plus the configured RAM cartridge, up to 512 KiB |
| PlayStation | PCSX ReARMed | Both 128 KiB memory cards, concatenated in slot order |
| Nintendo 64 | Mupen64Plus-Next | EEPROM, four controller paks, SRAM, FlashRAM, and a separate 24-byte RTC record |
| PSP | PPSSPP | Complete session memory-stick directory as a validated file bundle |

The source revisions and persistence extension versions are in
[`scripts/cores/pins.json`](../scripts/cores/pins.json). Android packages all four
supported ABIs; Apple builds the simulator/device target from the same source
pins and patches. Android strips debug symbols from packaged core assets and
records the digest of each resulting binary.

N64 uses Angrylion/CXD4 with the cached interpreter. PSP uses its software
framebuffer backend and IR interpreter. These paths do not require an iOS JIT
entitlement or an unimplemented hardware-render host. Performance is a separate
qualification, particularly for these two systems.

## Content and firmware boundaries

The supported disc routes are self-contained files. CUE sheets, M3U playlists,
external sidecars, and multi-disc PBP sets are not qualified by the single-file
identity. Both app catalogs reject unsupported extensions; the patched disc
cores also refuse a multi-disc persistence descriptor. Sega flash boards and
cartridge-plus-CD boot modes remain outside this adapter.

PlayStation requires verified `scph5501.bin`; supported regional BIOS files are
also included in snapshot compatibility when supplied. Sega CD currently requires
the three verified regional BIOS files. Android and iOS Settings provide import
for the user's copies. The app recognizes known bytes, preserves an invalid old
copy before replacement, and stages verified firmware into each owned session.
PSP's open-source fonts, compatibility data, and VFPU tables ship with the core;
their per-file hashes are bound to snapshots.

The iOS content file is also copied and rehashed inside the session workspace.
The Sega and PlayStation cores' upstream 255-character content/BIOS path limits are patched to
handle the longer application container and session paths. The Apple PlayStation
interpreter build also omits the upstream process-tracing workaround, which
otherwise prevents normal simulator termination. N64 initializes its native
device graph before an immediate snapshot restore and can close an unstarted
prepared session without leaving the ROM open. Android links that core's C++
runtime statically; otherwise the APK's existing static-runtime setup cannot load it.

The live N64 handoff also exposed a host lifecycle defect: iOS's periodic timer
captured while paused, creating a competing game-save branch after Android took
over. Periodic captures now require active, unpaused gameplay. The installed-app
probe asserts that a timer tick while paused creates no operation.

## PSP file-container contract

`saveFile` / `psp-memory-stick-v1` is the portable game-save artifact. It is not
SRAM and is not a memory-state snapshot. The native bridge's memory-region API
only transports its opaque bytes; checkpoint roles and restore order identify
the file-backed adapter explicitly.

`RMMF0001` contains a little-endian entry count followed by sorted entries:
path length (u16), kind (u8), reserved zero (u8), data length (u64), modification
seconds (u64), modification nanoseconds (u32), path bytes, and file bytes. Kinds
are directory, writable regular file, and read-only regular file. Directories
have zero data and timestamps. Limits are 64 MiB, 1,024 entries, 512 bytes per path,
and 16 path components. Absolute paths, traversal, symlinks, special files,
case-colliding names, missing parents, truncated input, and trailing bytes are
rejected. Empty files and directories are retained.

Capture drains native asynchronous I/O and flushes/truncates open file streams.
Restore parses and stages the whole tree, reads it back, closes native handles,
and swaps directories with rollback on installation failure. Files restore
**before** a PSP snapshot, so its open handles and offsets refer to the restored
files. Cartridge cores retain state-then-memory restore order.

PSP snapshots use an `RMSZ0001` envelope containing a single Zstandard frame,
with a 128 MiB decoded bound. Compression happens in the native core before a
host-language buffer is allocated. This avoids the upstream 72 MiB padded Java
snapshot allocations that exhausted the Android test device's heap. Truncated,
trailing, and invalid frames are rejected before loading native state. Game-save
file bundles remain separate from this snapshot format.

The embedded PSP core also leaves per-thread alternate signal stacks owned by
the host. Its upstream CPU initialization installs on a worker thread, while the
Android frontend can tear down on the UI thread; restoring the worker's stack on
the UI thread caused a process crash after an otherwise successful save/restore.

Android removes a completed workspace only when its files, directories,
timestamps, and read-only flags match the acknowledged bundle. Later or
unrecognized changes preserve the workspace for recovery. A small native stat
reader preserves nanoseconds on Android, including API 26; Android's Java file
attributes truncate these timestamps to whole seconds and cannot prove the
native bundle matches.

## Player controls

All seven systems now have console-specific touch controls on both clients,
including analog sticks, N64 C buttons, Genesis six-button inputs and PlayStation's
Analog toggle. [Player controls validation](player-controls-validation.md) records
the separate iPhone Air and Android checks. The save/sync ownership contract above
is unchanged.

## Validation and remaining qualification

The subsequent [expanded progress pass](expanded-progress-validation.md) adds a
game-created N64 save with visible Android cold-boot readback, offline capture
and external iOS restart, interrupted-upload recovery, and isolated Sega/PSP
handoffs. Its fixture and retail-game boundaries are recorded separately.

Evidence is reported separately for desktop native tests, the iPhone Air
simulator, Android emulator, iPhone device compilation, and physical gameplay.
Do not infer mobile parity from desktop tests or successful builds.

Generated original Sega/PlayStation programs exercise native execution and
persistence. The PSP fixture is PPSSPP's open-source `cubevfpu` test program, with
multi-file save fixtures. The N64 fixture is the user's existing Majora's Mask
entry (RomM ID 62). These do not replace retail-game or in-game save-menu testing.
Sega CD still requires user-provided BIOS/game fixtures for native gameplay
qualification. PlayStation's generated executable uses HLE firmware for the
native probe; BIOS-backed retail disc gameplay remains unqualified. N64 frame
replay allows a maximum difference of one per color channel for Angrylion's
renderer-local VI gamma dither; persistent memory comparisons are exact.
Physical-device qualification remains explicitly deferred.

Current evidence:

| Target | Result | Scope |
| --- | --- | --- |
| macOS arm64 native / Swift package | 167 tests, 3 environmental skips, no failures | Six expansion fixtures; native replay, game-save cold boot, fresh snapshot restore, malformed PSP snapshot rejection |
| iPhone Air, iOS 27 simulator | [Six fixtures passed](validation/expansion-iphone-air-native.json) | Installed signed binaries, frame replay, separate game saves, fresh snapshots, normal process termination |
| Android API 35 arm64 emulator | [Six fixtures passed](validation/expansion-android-native.json) | Distinct saved bytes, snapshot restore, game-save cold boot, fresh snapshots, PSP complete file/metadata audit and clean close/reopen |
| Android packaging | [All four ABIs verified](validation/expansion-android-builds.json) | Exact packaged binary hashes; no missing shared C++ runtime dependency |
| Android unit suite | [122 passed](validation/expansion-regression-summary.json) | Persistence contracts, file-bundle audit, lifecycle and existing application logic |
| Android existing native/player regressions | [31 passed, 1 opt-in force-stop case skipped](validation/expansion-regression-summary.json) | Checked captures, startup, environment isolation, player recovery/import; the force-stop scenario is a separate invocation |
| N64 live RomM 5.2.0 | [Two-way handoff passed](validation/expansion-n64-live-sync.json) | iOS process restart/offline recovery, reconnect, Android game-save cold boot, fresh cloud snapshot restore on each platform, both snapshot streams retained |
| iPhone arm64 device cores | [All four new cores compiled](validation/expansion-iphone-device-builds.json) | Compilation only; no physical-device gameplay claim |

Reproduction:

- `python3 scripts/cores/fixtures/generate_sega_psx.py --help`
- `ROMMIO_EXPANSION_FIXTURES=/path/to/cases.json swift test --package-path ios --filter ExpansionProgressTests`
- `clang++ -std=c++17 scripts/cores/extensions/rommio_file_bundle.cpp scripts/cores/extensions/file_bundle_test.cpp -o /tmp/rommio-file-bundle-test && /tmp/rommio-file-bundle-test`
- Android `ExpansionProgressTest` consumes privately staged `files/expansion-fixtures/fixture.{md,sms,gg,exe,z64,prx}` on a disposable emulator.
- The debug app's `expansion-native` probe consumes `cases.json` in an explicitly disposable `rommio-progress-fixture` root and verifies the installed signed cores.
- `scripts/ios/verify_live_cartridge_sync.py --roms 62 ...` exercises the real RomM game-save and platform snapshot streams without stopping the server.
