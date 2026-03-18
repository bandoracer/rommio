# Core Matrix

This document tracks the embedded runtime strategy for the Android-native RomM client.

Goals:

- Keep the architecture multi-core so per-platform or per-game overrides can be added later.
- Keep the current UI simple by exposing only one action: download the recommended core.
- Treat the default core in this document as the only UI-visible runtime for v1.

## Policy

- `Recommended core`: the default runtime the app will install and use.
- `Alternates`: additional runtime options that exist in the internal catalog but are not shown in the current UI.
- `Confidence`:
  - `strong`: good default for Android and expected to be a stable v1 choice
  - `provisional`: reasonable default, but still needs more device validation
- `Artifact`: the libretro Android buildbot artifact base name used by the in-app downloader.

## Matrix

| Family | Platform slugs | Recommended core | Alternates in catalog | BIOS | Artifact | Confidence | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| NES / Famicom | `nes`, `famicom` | `FCEUmm` | `Nestopia UE` | None | `fceumm` | strong | Fast, broadly compatible default for Android. |
| SNES / Super Famicom | `snes`, `sfam` | `Snes9x` | `bsnes` | None | `snes9x` | strong | `bsnes` remains available internally for future accuracy-first overrides. |
| Game Boy / Game Boy Color | `gb`, `gbc` | `Gambatte` | `SameBoy` | None | `gambatte` | strong | Good handheld default with LCD shader profile. |
| Game Boy Advance | `gba` | `mGBA` | `VBA Next` | None | `mgba` | strong | Best practical balance of accuracy, compatibility, and Android performance. |
| Sega 8/16-bit | `genesis`, `sms`, `gamegear`, `segacd` | `Genesis Plus GX` | `PicoDrive` | Sega CD BIOS for `segacd` content | `genesis_plus_gx` | strong | `PicoDrive` stays internal as a future fallback for weaker devices. |
| Nintendo 64 | `n64` | `Mupen64Plus-Next (GLES3)` | `Parallel N64` | None | `mupen64plus_next_gles3` | provisional | Needs renderer and device validation before wide enablement. |
| PlayStation | `psx`, `ps1`, `playstation` | `PCSX ReARMed` | `SwanStation` | `scph5501.bin` | `pcsx_rearmed` | strong | Keeps v1 aligned to a proven Android-first PS1 path. |
| PlayStation Portable | `psp` | `PPSSPP` | None | None | `ppsspp` | provisional | Right default, but still performance-sensitive on some Android devices. |
| Nintendo DS | `nds` | `melonDS` | `DeSmuME` | None | `melonds` | strong | Default is touchscreen-aware and better aligned with modern Android devices. |
| Atari 2600 | `atari2600` | `Stella` | `Stella 2023` | None | `stella` | provisional | Lightweight family; both cores remain viable. |
| Atari 7800 | `atari7800` | `ProSystem` | None | None | `prosystem` | provisional | Narrow platform surface, simple default. |
| Atari Lynx | `lynx` | `Handy` | None | None | `handy` | provisional | Needs more real-device validation. |
| Arcade | `arcade` | `FinalBurn Neo` | None | ROM set dependent | `fbneo` | strong | Best default for the curated arcade path we want in-app. |
| DOS | `dos` | `DOSBox Pure` | `DOSBox Core` | None | `dosbox_pure` | strong | `DOSBox Pure` is the cleaner default for v1 UX. |
| PC Engine / TurboGrafx-16 | `tg16` | `Beetle PCE Fast` | None | None | `mednafen_pce_fast` | provisional | Good default, still worth validation on reference devices. |
| Neo Geo Pocket | `neo-geo-pocket`, `neo-geo-pocket-color` | `Beetle NeoPop` | None | None | `mednafen_ngp` | provisional | Low-risk family but not yet deeply tested. |
| WonderSwan | `wonderswan`, `wonderswan-color` | `Beetle Cygne` | None | None | `mednafen_wswan` | provisional | Low-risk family but not yet deeply tested. |

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
