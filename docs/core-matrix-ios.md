# iOS Core Matrix

This document tracks the iOS-specific embedded runtime policy for Rommio.

The matrix is a **catalog of intended runtimes**, not a list of cores present in
the current app or qualified for distribution. The near-term delivery scope is
defined below.

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
| Nintendo 3DS | `3ds`, `new-nintendo-3ds` | Touch | `Azahar` | None | User keys/system files for content that needs them | Bundled Vulkan framework | provisional | 3DStris game saves and resume snapshots restored on simulator; Pokémon X produced non-silent audio in a native Vulkan probe. Physical devices and distribution remain unqualified. |
| GameCube / Wii | `gamecube`, `ngc`, `wii` | Controller | `Dolphin` | None | None | Bundled signed libretro core | provisional | Higher-end expansion target once the iOS bridge is validated. |
| Dreamcast / NAOMI | `dreamcast`, `naomi` | Controller | `Flycast` | None | `dc_boot.bin`, `dc_flash.bin` | Bundled signed libretro core | provisional | Same BIOS policy, iOS-specific packaging. |
| 3DO | `3do` | Controller | `Opera` | None | Varies by ROM set / firmware path | Bundled signed libretro core | provisional | Keep controller-first until heavier per-core validation clears. |
| Virtual Boy | `virtualboy`, `virtual-boy` | Controller | `Beetle VB` | None | None | Bundled signed libretro core | provisional | Moderate-risk expansion target. |
| Atari 2600 | `atari2600` | Touch | `Stella 2023` | None | None | Embedded signed framework in simulator | provisional | Explicit empty game-save inventory; snapshot cold restore passed on simulator. |
| Atari 7800 | `atari7800` | Touch | `ProSystem` | None | Optional `7800 BIOS (U).rom` | Embedded signed framework in simulator | provisional | Generated NTSC fixture passes simulator snapshot replay; PAL and device tests remain. |
| Atari Lynx | `lynx` | Touch | `Handy` | None | Optional `lynxboot.img` | Embedded signed framework in simulator | provisional | Internal HLE fallback and homebrew EEPROM/snapshot simulator replay pass. |
| Arcade | `arcade` | Touch | `FinalBurn Neo` | None | ROM set dependent | Bundled signed libretro core | strong | Keep the curated-arcade policy. |
| DOS | `dos` | Controller | `DOSBox Pure` | `DOSBox Core` | None | Bundled signed libretro core | strong | Native iOS frontend must still mediate keyboard and touch affordances. |
| PC Engine / TurboGrafx-16 | `tg16` | Touch | `Beetle PCE Fast` | None | None | Bundled signed libretro core | provisional | Lower-risk parity family. |
| Neo Geo Pocket | `neo-geo-pocket`, `neo-geo-pocket-color` | Touch | `Beetle NeoPop` | None | None | Bundled signed libretro core | provisional | Lower-risk parity family. |
| WonderSwan | `wonderswan`, `wonderswan-color` | Touch | `Beetle Cygne` | None | None | Bundled signed libretro core | provisional | Lower-risk parity family. |

## Packaging strategy

- Executable cores are embedded as individually signed iOS frameworks. The
  simulator app's final product verifies 13 core frameworks with no loose
  libraries. Apple distribution signing and review remain unqualified.
- Resolve shipped runtimes from the signed app bundle without downloading
  executable code.
- Keep bundle-relative core locations stable so the native iOS player bridge can resolve them deterministically.
- Treat BIOS files as user-managed app content, not bundled executable code.
- Preserve the product behavior of one recommended runtime per family, but express installation state as “bundled and auto-provisioned in this build” instead of “download required”.
- Keep the canonical family/runtime map in `docs/runtime-matrix.json` and
  declared iOS artifact paths in `scripts/ios/core-build-manifest.json`. A
  manifest entry does not establish a working build recipe or bundled core.
- Keep externally fetched cores in
  `scripts/ios/bundled-core-release-manifest.json`. FCEUmm and FinalBurn Neo
  currently come from release assets; the other eleven shipped cores have
  source builds in `scripts/cores/`. None of these binaries is tracked as a
  normal Git blob in the main repository.

## Near-term delivery scope

Keep the fourteen cores now used by iOS builds: FCEUmm,
Snes9x, Gambatte, mGBA, Genesis Plus GX, Mupen64Plus-Next, PCSX ReARMed,
PPSSPP, melonDS DS, FinalBurn Neo, Stella, ProSystem, Handy, and Azahar. Their presence in a build
does not establish App Store signing or platform qualification.

Azahar is enabled with Vulkan; its remaining qualification is tracked below.
The Atari cores are present in the simulator build:

| Priority | Families | Core | Current gap | Required outcome |
| --- | --- | --- | --- | --- |
| Now — qualification | Nintendo 3DS | Azahar 2126.1.2 Vulkan | Pinned simulator and iPhone arm64 builds; 3DStris game save and independent snapshot restore pass on iPhone Duo simulator; Pokémon X native Vulkan probe runs at 93.3 emulated fps with non-silent audio | Visible-player Pokémon X, broader titles and keys, physical iPhone/iPad performance, and distribution signing |
| Atari 1 | Atari 2600 | Stella 2023 | Pinned framework and simulator snapshot replay pass | Real RomM snapshot sync, touch/controller play, and device qualification |
| Atari 2 | Atari 7800 | ProSystem | Pinned framework; generated NTSC fixture passes simulator replay | PAL timing, controls, real RomM sync, and device qualification |
| Atari 3 | Atari Lynx | Handy | Pinned framework; homebrew EEPROM and HLE boot pass simulator replay | EEPROM/snapshot sync, rotation, and device qualification |

The [priority core delivery plan](ios-priority-core-plan.md) defines the work
and qualification checks. The Atari entries are delivery work; 3DS remains
experimental. Dreamcast/NAOMI (Flycast) is on the backburner and is
not in the active build or validation scope.
The experimental playable setting is not proof of qualification. For each
family, a **game save**, where supported, is its own persistent progress
artifact and a **resume snapshot** is a separate captured emulator state; both
must satisfy the ownership, recovery, and synchronization rules in
[save-sync-spec.md](save-sync-spec.md). Simulator checks, iPhone/iPad device
checks, and App Store distribution signing are separate gates.

Defer new iOS integration of Flycast, PicoDrive, DOSBox Pure, Beetle PCE Fast,
Beetle NeoPop, Beetle Cygne, Opera, Beetle VB, and Dolphin while this scope is
underway. They remain in the catalog and artifact manifest as planned
runtimes, not as shipped cores. Revisit them after the active priorities above
or when an explicit scope decision changes the order.
