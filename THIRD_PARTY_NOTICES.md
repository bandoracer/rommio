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
| LibretroDroid | Embedded libretro frontend bridge used by the in-app player | GPL-3.0 | https://github.com/Swordfish90/LibretroDroid |

## Related Upstream Projects

These projects are important to Rommio's function, but are not bundled as direct runtime libraries in the same way as the dependencies above:

| Project | Relationship | License | Upstream |
| --- | --- | --- | --- |
| RomM | Server-side ROM manager and API Rommio connects to | AGPL-3.0 | https://github.com/rommapp/romm |
| libretro cores | Optional downloadable emulator cores used for embedded playback | Varies by core | https://docs.libretro.com/meta/core-list/ |

## Runtime Note For Cores And BIOS Files

Rommio does not bundle libretro cores or BIOS files by default.

- Downloadable cores are separate upstream components with their own licenses, authors, and distribution terms.
- BIOS files remain the responsibility of the user and may be required for some systems before embedded playback can work.
- Because those assets are not shipped as part of the default repository or release APK, they are not listed as bundled dependencies in the table above.
