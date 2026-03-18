# Embedded Player Validation Matrix

Use this matrix before marking a platform as fully supported in the native player.

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

## Initial runtime targets

- NES / Famicom
- SNES / Super Famicom
- GB / GBC / GBA
- Genesis / SMS / Game Gear / Sega CD
- Atari 2600 / 7800 / Lynx
- N64
- PS1
- PSP
- NDS
- Arcade
- DOS
- TG16
- Neo Geo Pocket
- WonderSwan

## Deferred until a later milestone

- Systems requiring non-libretro runtimes
- Systems that cannot maintain stable performance or state integrity on the reference device set
- Any platform that needs a more complex multi-screen, motion, or per-core UI than the first milestone provides
