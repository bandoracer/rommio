# Core Matrix

This document tracks the embedded runtime strategy for the Android-native RomM client.

Goals:

- Keep the architecture multi-core so per-platform or per-game overrides can be added later.
- Keep the current UI simple by exposing only one action: download the recommended core.
- Treat the default core in this document as the only UI-visible runtime for v1.

## Policy

- `Recommended core`: the default runtime the app will install and use.
- `Alternates`: additional runtime options that exist in the internal catalog but are not shown in the current UI.
- `Support tier`:
  - `Touch`: embedded runtime plus first-class mobile controls
  - `Controller`: embedded runtime exists, but the family is still controller-first in Rommio
  - `Research`: documented libretro path exists, but the family stays out of the user-facing support catalog until validation clears
- `Confidence`:
  - `strong`: good default for Android and expected to be a stable v1 choice
  - `provisional`: reasonable default, but still needs more device validation
- `Artifact`: the libretro Android buildbot artifact base name used by the in-app downloader.

## Matrix

| Family | Platform slugs | Support tier | Recommended core | Alternates in catalog | BIOS | Artifact | Confidence | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| NES / Famicom | `nes`, `famicom` | Touch | `FCEUmm` | `Nestopia UE` | None | `fceumm` | strong | Fast, broadly compatible default for Android. |
| SNES / Super Famicom | `snes`, `sfam` | Touch | `Snes9x` | `bsnes` | None | `snes9x` | strong | `bsnes` remains available internally for future accuracy-first overrides. |
| Game Boy / Game Boy Color | `gb`, `gbc` | Touch | `Gambatte` | `SameBoy` | None | `gambatte` | strong | Good handheld default with LCD shader profile. |
| Game Boy Advance | `gba` | Touch | `mGBA` | `VBA Next` | None | `mgba` | strong | Best practical balance of accuracy, compatibility, and Android performance. |
| Sega 8/16-bit | `genesis`, `sms`, `gamegear`, `segacd` | Touch | `Genesis Plus GX` | `PicoDrive` | Sega CD BIOS for `segacd` content | `genesis_plus_gx` | strong | `PicoDrive` stays internal as a future fallback for weaker devices. |
| Sega 32X | `32x`, `sega32x`, `sega-32x` | Controller | `PicoDrive` | None | None | `picodrive` | provisional | First controller-first Sega expansion target. |
| Nintendo 64 | `n64` | Controller | `Mupen64Plus-Next (GLES3)` | `Parallel N64` | None | `mupen64plus_next_gles3` | provisional | Needs renderer and device validation before wide enablement. |
| PlayStation | `psx`, `ps1`, `playstation` | Touch | `PCSX ReARMed` | `SwanStation` | `scph5501.bin` | `pcsx_rearmed` | strong | Keeps v1 aligned to a proven Android-first PS1 path. |
| PlayStation Portable | `psp` | Controller | `PPSSPP` | None | None | `ppsspp` | provisional | Right default, but still performance-sensitive on some Android devices. |
| Nintendo DS / DSi-enhanced | `nds`, `nintendo-dsi` | Controller | `melonDS DS` | `DeSmuME` | None | `melondsds` | strong | DSi content stays in the DS family unless RomM ever needs a truly separate runtime path. |
| Nintendo 3DS | `3ds`, `new-nintendo-3ds` | Controller | `Citra Canary/Experimental` | None | None | `citra` | provisional | `New Nintendo 3DS` stays under the same Citra family instead of becoming a separate public runtime family. |
| GameCube / Wii | `gamecube`, `ngc`, `wii` | Controller | `Dolphin` | None | None | `dolphin` | provisional | Flagship expansion target once Android validation clears. |
| Dreamcast / NAOMI | `dreamcast`, `naomi` | Controller | `Flycast` | None | `dc_boot.bin`, `dc_flash.bin` | `flycast` | provisional | First controller-first console/arcade expansion tier. |
| 3DO | `3do` | Controller | `Opera` | None | Varies by ROM set / firmware path | `opera` | provisional | Keep controller-first until heavier per-core validation clears. |
| Virtual Boy | `virtualboy`, `virtual-boy` | Controller | `Beetle VB` | None | None | `mednafen_vb` | provisional | Good breadth win with modest UI risk. |
| Atari 2600 | `atari2600` | Touch | `Stella` | `Stella 2023` | None | `stella` | provisional | Lightweight family; both cores remain viable. |
| Atari 7800 | `atari7800` | Touch | `ProSystem` | None | None | `prosystem` | provisional | Narrow platform surface, simple default. |
| Atari Lynx | `lynx` | Touch | `Handy` | None | None | `handy` | provisional | Needs more real-device validation. |
| Arcade | `arcade` | Touch | `FinalBurn Neo` | None | ROM set dependent | `fbneo` | strong | Best default for the curated arcade path we want in-app. |
| DOS | `dos` | Controller | `DOSBox Pure` | `DOSBox Core` | None | `dosbox_pure` | strong | `DOSBox Pure` is the cleaner default for v1 UX, but it remains controller-first. |
| PC Engine / TurboGrafx-16 | `tg16` | Touch | `Beetle PCE Fast` | None | None | `mednafen_pce_fast` | provisional | Good default, still worth validation on reference devices. |
| Neo Geo Pocket | `neo-geo-pocket`, `neo-geo-pocket-color` | Touch | `Beetle NeoPop` | None | None | `mednafen_ngp` | provisional | Low-risk family but not yet deeply tested. |
| WonderSwan | `wonderswan`, `wonderswan-color` | Touch | `Beetle Cygne` | None | None | `mednafen_wswan` | provisional | Low-risk family but not yet deeply tested. |

## Research tier

These families stay out of the user-facing support catalog until Android validation clears:

| Family | Planned core | Reason to keep in research tier |
| --- | --- | --- |
| PlayStation 2 | `Play!` | Viable libretro path exists, but it still needs performance, save-state, and BIOS validation on the reference device set. |
| Sega Saturn | `Kronos` (`Beetle Saturn` alternate) | High-value family, but Android stability and performance still need dedicated validation before exposure. |
| Optional backlog | `Jaguar`, `Neo Geo CD`, `PC-FX`, other niche libretro families | Keep behind the mainline rollout until the controller-first expansion phases are complete. |

## Runtime packaging strategy

- The app can resolve cores from either:
  - packaged native libraries in the APK
  - app-managed storage under `files/library/cores/`
- The current UX uses app-managed storage and downloads the recommended core on demand.
- Alternate cores are intentionally hidden from the UI until we have a validation-backed reason to expose advanced runtime overrides.

## Validation expectations

Before a family should be considered fully enabled in the embedded player, validate:

- launch stability
- suspend/resume behavior
- save RAM persistence
- save-state create/load
- controller support
- audio stability
- acceptable performance on the reference Android device set

The current platform validation rubric is tracked separately in `docs/validation-matrix.md`.
