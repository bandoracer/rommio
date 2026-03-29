# iOS Core Matrix

This document tracks the iOS-specific embedded runtime policy for Rommio.

It preserves the same product-facing runtime families as Android, but changes delivery and packaging:

- iOS cores must be bundled and code-signed with the app
- iOS must not download executable core binaries at runtime
- BIOS, ROMs, saves, and save states remain app-managed content

## Policy

- `Recommended core`: the default runtime the iOS app will expose for the family.
- `Alternates`: additional runtime options that may exist later, but are not part of the first iOS milestone.
- `Support tier`:
  - `Touch`: embedded runtime plus first-class mobile controls
  - `Controller`: embedded runtime exists, but the family stays controller-first
  - `Research`: documented libretro path exists, but the family remains outside the shipping support catalog until iOS validation clears
- `Confidence`:
  - `strong`: expected to be a stable default once the iOS frontend is validated
  - `provisional`: reasonable default, but still requires iPhone/iPad validation
- `Packaging`: the required iOS delivery policy for the runtime.

## Matrix

| Family | Platform slugs | Support tier | Recommended core | Alternates in catalog | BIOS | Packaging | Confidence | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| NES / Famicom | `nes`, `famicom` | Touch | `FCEUmm` | `Nestopia UE` | None | Bundled signed libretro core | strong | Preserve Android parity without runtime downloads. |
| SNES / Super Famicom | `snes`, `sfam` | Touch | `Snes9x` | `bsnes` | None | Bundled signed libretro core | strong | Keep the same default while validating iPhone thermal behavior. |
| Game Boy / Game Boy Color | `gb`, `gbc` | Touch | `Gambatte` | `SameBoy` | None | Bundled signed libretro core | strong | Reuse LCD-style presentation on iPhone-class displays. |
| Game Boy Advance | `gba` | Touch | `mGBA` | `VBA Next` | None | Bundled signed libretro core | strong | Good baseline for parity with Android. |
| Sega 8/16-bit | `genesis`, `sms`, `gamegear`, `segacd` | Touch | `Genesis Plus GX` | `PicoDrive` | Sega CD BIOS for `segacd` content | Bundled signed libretro core | strong | Keep Sega CD BIOS rules identical to Android. |
| Sega 32X | `32x`, `sega32x`, `sega-32x` | Controller | `PicoDrive` | None | None | Bundled signed libretro core | provisional | Preserve controller-first policy for early iOS rollout. |
| Nintendo 64 | `n64` | Controller | `Mupen64Plus-Next` | `Parallel N64` | None | Bundled signed libretro core | provisional | Metal or equivalent renderer validation remains required. |
| PlayStation | `psx`, `ps1`, `playstation` | Touch | `PCSX ReARMed` | `SwanStation` | `scph5501.bin` | Bundled signed libretro core | strong | Same BIOS policy, but iOS packaging differs. |
| PlayStation Portable | `psp` | Controller | `PPSSPP` | None | None | Bundled signed libretro core | provisional | Needs battery and thermal validation on reference devices. |
| Nintendo DS / DSi-enhanced | `nds`, `nintendo-dsi` | Touch | `melonDS DS` | `DeSmuME` | None | Bundled signed libretro core | strong | Uses the portrait handheld touch layout and lower-screen pointer routing already defined by the iOS player shell. |
| Nintendo 3DS | `3ds`, `new-nintendo-3ds` | Controller | `Citra Canary/Experimental` | None | None | Bundled signed libretro core | provisional | Controller-first until iOS stability is proven. |
| GameCube / Wii | `gamecube`, `ngc`, `wii` | Controller | `Dolphin` | None | None | Bundled signed libretro core | provisional | Higher-end expansion target once the iOS bridge is validated. |
| Dreamcast / NAOMI | `dreamcast`, `naomi` | Controller | `Flycast` | None | `dc_boot.bin`, `dc_flash.bin` | Bundled signed libretro core | provisional | Same BIOS policy, iOS-specific packaging. |
| 3DO | `3do` | Controller | `Opera` | None | Varies by ROM set / firmware path | Bundled signed libretro core | provisional | Keep controller-first until heavier per-core validation clears. |
| Virtual Boy | `virtualboy`, `virtual-boy` | Controller | `Beetle VB` | None | None | Bundled signed libretro core | provisional | Moderate-risk expansion target. |
| Atari 2600 | `atari2600` | Touch | `Stella` | `Stella 2023` | None | Bundled signed libretro core | provisional | Low-risk parity target. |
| Atari 7800 | `atari7800` | Touch | `ProSystem` | None | None | Bundled signed libretro core | provisional | Low-risk parity target. |
| Atari Lynx | `lynx` | Touch | `Handy` | None | None | Bundled signed libretro core | provisional | Needs device validation. |
| Arcade | `arcade` | Touch | `FinalBurn Neo` | None | ROM set dependent | Bundled signed libretro core | strong | Keep the curated-arcade policy. |
| DOS | `dos` | Controller | `DOSBox Pure` | `DOSBox Core` | None | Bundled signed libretro core | strong | Native iOS frontend must still mediate keyboard and touch affordances. |
| PC Engine / TurboGrafx-16 | `tg16` | Touch | `Beetle PCE Fast` | None | None | Bundled signed libretro core | provisional | Lower-risk parity family. |
| Neo Geo Pocket | `neo-geo-pocket`, `neo-geo-pocket-color` | Touch | `Beetle NeoPop` | None | None | Bundled signed libretro core | provisional | Lower-risk parity family. |
| WonderSwan | `wonderswan`, `wonderswan-color` | Touch | `Beetle Cygne` | None | None | Bundled signed libretro core | provisional | Lower-risk parity family. |

## Packaging strategy

- Ship core binaries inside the application bundle under a dedicated cores directory.
- Provision runtimes automatically on demand from the signed app bundle into the app's runtime inventory without downloading executable code.
- Keep bundle-relative core locations stable so the native iOS player bridge can resolve them deterministically.
- Treat BIOS files as user-managed app content, not bundled executable code.
- Preserve the product behavior of one recommended runtime per family, but express installation state as “bundled and auto-provisioned in this build” instead of “download required”.
- Keep the canonical family/runtime map in `docs/runtime-matrix.json` and the
  iOS build recipe inventory in `scripts/ios/core-build-manifest.json` so
  Android parity and iOS packaging verification can be tested independently.
- Keep the shipped bundled-core release set in
  `scripts/ios/bundled-core-release-manifest.json`. The signed dylib binaries
  themselves are hosted as GitHub Release assets and fetched locally or in CI
  before iOS app builds; they are no longer tracked as normal Git blobs in the
  main repository.
