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
