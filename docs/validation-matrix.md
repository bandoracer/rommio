# Embedded Player Validation Matrix

Use this matrix before promoting a platform from backlog or research into the user-facing native player catalog.

## Required checks

- Boots into gameplay on the reference device without an external emulator installed
- Maintains stable audio/video during a five-minute play session
- Survives background/foreground transitions without losing the render surface
- Persists SaveRAM correctly across process death
- Saves and loads at least one save-state slot correctly
- Accepts gamepad input and native touch input where applicable
- Syncs local SaveRAM and at least one save-state file to RomM successfully
- Recovers cleanly when the required core library is missing
- Recovers cleanly when required BIOS files are missing

## Save and sync qualification

The checks above remain basic runtime smoke tests. Qualification of the new persistence architecture also requires the applicable [save/sync acceptance scenarios](save-sync-spec.md#13-acceptance-scenarios) and [release gates](save-sync-spec.md#14-implementation-order-and-release-gates).

Record local-save durability, same-platform synchronization, cross-platform in-game-save compatibility, and save-state compatibility separately. A successful file upload alone does not qualify conflict handling or recovery. Automatic synchronization must remain disabled for transport/client combinations that have not passed the capability investigation and concurrency tests defined in that specification. These target requirements do not retroactively mark existing catalog entries as validated.

## Reference outcomes

- `READY`
  All required checks pass. Platform can be exposed as playable.
- `MISSING_CORE`
  Runtime profile exists, but the libretro core binary is not provisioned.
- `MISSING_BIOS`
  Runtime profile exists, but BIOS files are missing from app-managed storage.
- `UNSUPPORTED`
  Platform is intentionally out of scope for the first milestone or does not pass validation.

## Promotion rules

- `Controller supported`
  The libretro runtime exists, Android validation passes, and the family is ready to show in-app without a dedicated touch layout.
- `Touch supported`
  Everything required for `Controller supported` passes, and the family also has a validated mobile touch/control layout.
- `Unsupported`
  The runtime is absent, intentionally hidden, or has not passed the required Android validation yet.

## Current touch-ready catalog

- NES / Famicom
- SNES / Super Famicom
- GB / GBC / GBA
- Genesis / SMS / Game Gear / Sega CD
- PS1
- Atari 2600 / 7800 / Lynx
- Arcade
- PC Engine / TurboGrafx-16
- Neo Geo Pocket
- WonderSwan

## Current controller-first catalog

- N64
- PSP
- NDS
- DSi-enhanced content under the DS family
- DOS
- Sega 32X
- Dreamcast / NAOMI
- 3DO
- Virtual Boy
- GameCube / Wii
- 3DS

## Research tier

- PS2 via `Play!`
- Saturn via `Kronos` / `Beetle Saturn`
- Any libretro family that still fails the required Android validation checks

## Explicitly out of scope

- Systems requiring non-libretro runtimes for practical Android support
- PS3
- Wii U
- Systems that cannot maintain stable performance or state integrity on the reference device set
- Any platform that needs a more complex multi-screen, motion, or per-core UI than the current milestone provides

## Qualified Apple progress cutover — 2026-09-18

See [iOS progress qualification](ios-progress-player.md) for the pinned native core, ordered session owner and independent game/snapshot sync evidence. Swift: 132 tests, two existing environment skips, zero failures. Real simulator/emulator/RomM 5.2.0 tests pass bidirectional game saves, separate snapshot streams, a fresh iOS cloud snapshot restore, offline process restart and reconnect. Physical test build/sign/install pass; gameplay/lifecycle launch is blocked by the paired iPhone lock screen. This does not qualify other cores or close full platform/release parity.

## iOS progress backup/recovery — 2026-09-18

Twelve backup/recovery tests cover exact metadata and file round trips, conflicts/slots/tombstones, all outbox intents, device identity, staged captures before first commit, damaged archives/artifacts/journals, retry/rollback, active-player exclusion and stable-lock fencing. The real simulator app passes external SIGKILL/restart at all five activation boundaries. Physical qualification is deferred. See [reproduction and format limits](ios-progress-backup.md).
