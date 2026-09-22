# Third-Party Notices

This file summarizes the primary third-party libraries shipped with Rommio and the licenses they are distributed under.

It is not an exhaustive catalog of every transient dependency in the Android build graph. It is the notice inventory for the direct libraries Rommio intentionally depends on at runtime.

## Direct Runtime Libraries

| Project / Library | Used for | License | Upstream |
| --- | --- | --- | --- |
| AndroidX / Jetpack (Compose, Navigation, Lifecycle, Room, WorkManager, DataStore, Security) | App UI, navigation, lifecycle, persistence, background work, encrypted preferences | Apache-2.0 | https://developer.android.com/jetpack |
| Material Components for Android | Material 3 and supporting UI components | Apache-2.0 | https://github.com/material-components/material-components-android |
| Kotlin Coroutines | Async work and Flow-based state handling | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| Retrofit | Typed HTTP API client | Apache-2.0 | https://github.com/square/retrofit |
| OkHttp | HTTP transport and logging interceptor | Apache-2.0 | https://github.com/square/okhttp |
| Moshi | JSON serialization and parsing | Apache-2.0 | https://github.com/square/moshi |
| Coil | Image loading, caching, and SVG decoding | Apache-2.0 | https://github.com/coil-kt/coil |
| FCEUmm | Bundled NES/Famicom core, built from pinned source | GNU GPL v2; see source headers | https://github.com/libretro/libretro-fceumm |
| Gambatte | Bundled GB/GBC cartridge core | GNU GPL v2 | https://github.com/libretro/gambatte-libretro |
| mGBA | Bundled GBA cartridge core | MPL-2.0; see source headers | https://github.com/libretro/mgba |
| Snes9x | Bundled SNES cartridge core | Snes9x license; see bundled full text | https://github.com/libretro/snes9x |
| melonDS DS | Bundled DS cartridge core | GPL-3.0 and embedded dependency notices | https://github.com/JesseTG/melonds-ds |
| Genesis Plus GX | Bundled Genesis, Master System, Game Gear, and Sega CD core | Genesis Plus GX noncommercial license and embedded dependency notices | https://github.com/libretro/Genesis-Plus-GX |
| PCSX ReARMed | Bundled PlayStation core | GPL-2.0 and embedded dependency notices | https://github.com/libretro/pcsx_rearmed |
| Mupen64Plus-Next | Bundled Nintendo 64 core | GPL-2.0 and embedded dependency notices | https://github.com/libretro/mupen64plus-libretro-nx |
| PPSSPP | Bundled PSP core, fonts, compatibility data, and VFPU tables | GPL-2.0-or-later and embedded dependency notices | https://github.com/hrydgard/ppsspp |
| LibretroDroid | Embedded libretro frontend bridge used by the in-app player | GPL-3.0 | https://github.com/Swordfish90/LibretroDroid |

## Related Upstream Projects

These projects are important to Rommio's function, but are not bundled as direct runtime libraries in the same way as the dependencies above:

| Project | Relationship | License | Upstream |
| --- | --- | --- | --- |
| RomM | Server-side ROM manager and API Rommio connects to | AGPL-3.0 | https://github.com/rommapp/romm |
| libretro cores | Optional downloadable emulator cores used for embedded playback | Varies by core | https://docs.libretro.com/meta/core-list/ |

## Runtime Note For Cores And BIOS Files

Rommio bundles FCEUmm, built from the [pinned source and local patch](third_party/fceumm/UPSTREAM.md). Its [license](third_party/fceumm/Copying), [authors](third_party/fceumm/Authors), and individual source notices are retained. Gambatte, mGBA, Snes9x, melonDS DS, Genesis Plus GX, PCSX ReARMed, Mupen64Plus-Next, and PPSSPP are also built from [exact revisions and local patches](scripts/cores/pins.json); their complete upstream license files are included with the generated artifacts. See [cartridge build and qualification details](docs/cartridge-progress.md). Other Android cores remain optional downloads. BIOS files are not bundled.

- Downloadable cores are separate upstream components with their own licenses, authors, and distribution terms.
- BIOS files remain the responsibility of the user and may be required for some systems before embedded playback can work.
- The nine source-built Android cores are listed above. Optional downloadable cores and BIOS payloads are not included in the APK.
