# Rommio

Rommio is the standalone Android-native client for [RomM](https://github.com/rommapp/romm). It focuses on native Android ergonomics instead of a wrapped web view: guided setup, an authenticated app shell, app-managed downloads, embedded libretro playback, and touch controls tuned for phone-sized devices.

This repository is the canonical home for the Android app. The project lives at the repository root.

## Current product surface

Rommio currently includes:

- A guided onboarding flow that walks through server discovery, edge access, RomM authentication, and resumable setup
- An authenticated app shell with bottom-tab navigation for `Home`, `Library`, `Collections`, and `Settings`
- A download manager with queue state, progress, retry/cancel flows, and local library tracking
- RomM-backed collections, including collection detail views and preview artwork fallbacks
- An embedded libretro player with:
  - on-demand core downloads
  - local save/state sync plumbing
  - controller-first fallbacks where needed
  - fixed-zone touch controls for supported platforms
  - player-only visual themes such as OLED black mode and console-color control accents
- Local persistence for installed ROM metadata, download history, player/control preferences, and auth/session state

## Tech stack

- Kotlin
- Jetpack Compose + Material 3
- Navigation Compose
- Retrofit + OkHttp + Moshi
- Room
- DataStore
- WorkManager
- Coil
- LibretroDroid

## Project layout

- [`app/`](app): Android application module
- [`docs/`](docs): runtime policy and validation notes
- [`scripts/android/`](scripts/android): local emulator/device workflow helpers

Important app areas:

- [`app/src/main/java/io/github/mattsays/rommnative/data/`](app/src/main/java/io/github/mattsays/rommnative/data): auth, networking, Room, repository, downloads, background work
- [`app/src/main/java/io/github/mattsays/rommnative/domain/`](app/src/main/java/io/github/mattsays/rommnative/domain): input models, core resolution, player/runtime abstractions, storage
- [`app/src/main/java/io/github/mattsays/rommnative/ui/`](app/src/main/java/io/github/mattsays/rommnative/ui): Compose shell, screens, and shared components
- [`app/src/test/`](app/src/test): unit tests for repository logic, auth routing, collection mapping, and queue ordering

## Requirements

- macOS or Linux with the Android SDK installed
- JDK 17
- Android platform tools
- An Android device or emulator running API 26+

The helper scripts assume an SDK that includes:

- `platform-tools`
- `emulator`
- Android API `36`
- `google_apis` arm64 system image

Default emulator values come from [`scripts/android/common.sh`](scripts/android/common.sh):

- API level: `36`
- Image tag: `google_apis`
- Arch: `arm64-v8a`
- Device: `pixel_8`
- AVD name: `romm-native-api36`

These can be overridden with environment variables such as `ANDROID_API_LEVEL`, `ANDROID_IMAGE_TAG`, `ANDROID_ARCH`, `ANDROID_DEVICE_NAME`, `ANDROID_AVD_NAME`, and `ANDROID_SERIAL`.

## Build

Build the debug APK from the repository root:

```bash
./gradlew assembleDebug
```

Debug APK output:

- `app/build/outputs/apk/debug/app-debug.apk`

## Test

Run unit tests from the repository root:

```bash
./gradlew testDebugUnitTest
```

Install to a connected device or booted emulator:

```bash
./gradlew installDebug
```

## Quick start

### 1. Check your Android toolchain

```bash
scripts/android/doctor.sh
```

### 2. Install emulator dependencies

```bash
scripts/android/install_sdk_packages.sh
```

### 3. Create the default AVD

```bash
scripts/android/create_avd.sh
```

### 4. Boot the emulator

```bash
scripts/android/start_emulator.sh --background
```

### 5. Build, install, and launch the app

```bash
scripts/android/install_and_launch.sh
```

## Emulator and device workflows

All helper scripts are intended to be run from the repository root.

### Android environment doctor

```bash
scripts/android/doctor.sh
```

Reports:

- detected SDK root
- `adb`, `sdkmanager`, `avdmanager`, and emulator paths
- installed system image and AVD status
- attached devices

### Install required SDK packages

```bash
scripts/android/install_sdk_packages.sh
```

Optional overrides:

```bash
scripts/android/install_sdk_packages.sh --api 36 --image-tag google_apis --arch arm64-v8a
```

### Create or replace the default AVD

```bash
scripts/android/create_avd.sh
```

Example:

```bash
scripts/android/create_avd.sh --name romm-native-api36 --device pixel_8
```

### Start the emulator

```bash
scripts/android/start_emulator.sh --background
```

Useful variants:

```bash
scripts/android/start_emulator.sh --foreground
scripts/android/start_emulator.sh --headless
scripts/android/start_emulator.sh --wipe-data
```

### Install and launch on a specific target

```bash
scripts/android/install_and_launch.sh --serial emulator-5554
scripts/android/install_and_launch.sh --serial R5CY841NYQR
```

Skip rebuilding when the APK already exists:

```bash
scripts/android/install_and_launch.sh --serial emulator-5554 --skip-build
```

### Tail logs

```bash
scripts/android/logcat_romm_native.sh
```

For full logcat instead of the crash-focused fallback:

```bash
scripts/android/logcat_romm_native.sh --all
```

### Push runtime assets into app storage

This is useful when testing embedded playback locally without building full in-app download flows around every asset.

```bash
scripts/android/push_runtime_assets.sh --core ~/Downloads/snes9x_libretro_android.so
scripts/android/push_runtime_assets.sh --bios ~/Downloads/scph5501.bin
scripts/android/push_runtime_assets.sh --platform snes --rom ~/Downloads/smw.sfc
```

The script writes into the app's internal storage via `run-as`.

## Local debug auth

Debug builds can read credentials from a root-level `.env.test.local` file:

```bash
ROMM_TEST_BASE_URL=
ROMM_TEST_CLIENT_ID=
ROMM_TEST_CLIENT_SECRET=
ROMM_TEST_USERNAME=
ROMM_TEST_PASSWORD=
```

These values are used by debug-only onboarding shortcuts and by smoke/instrumentation flows.

If your RomM server runs on the same host as the Android emulator, use `http://10.0.2.2:<port>` inside the emulator instead of `localhost`.

## Authentication model

Rommio mirrors RomM's existing auth expectations rather than inventing a client-specific auth layer.

Supported flows include:

- direct server access with no extra edge auth
- protected cookie/SSO-style access handoff
- Cloudflare Access and related protected-edge flows
- RomM-origin sign-in flows, including bearer/password and interactive session completion

The onboarding flow makes these steps explicit:

1. discover server capabilities
2. choose or validate edge access mode
3. verify native reachability from the Android app
4. complete RomM authentication
5. enter the authenticated app shell

## Embedded player

The embedded player is built around LibretroDroid plus an app-managed runtime catalog.

Current player capabilities:

- recommended-core download flow
- local core and BIOS storage under app-managed files
- save and save-state sync plumbing
- platform support gating via runtime/core validation
- touch controls for supported platform families
- fixed portrait/landscape control zones
- per-player control tuning for:
  - opacity
  - global size
  - left-handed swap
  - controller auto-hide
  - rumble
  - OLED black mode
  - console-color styling

Touch controls are intentionally limited to supported families. Controller-first platforms stay browsable in the app but require external input to play.

See:

- [`docs/core-matrix.md`](docs/core-matrix.md)
- [`docs/validation-matrix.md`](docs/validation-matrix.md)

## Runtime assets and storage

Rommio uses app-managed storage for:

- libretro core binaries
- BIOS files
- downloaded ROM files
- synced saves and save states

Important notes:

- core binaries are not bundled by default
- some platforms require BIOS files before they can be considered playable
- download/install state is persisted locally and surfaced throughout Home, Library, Downloads, and Settings

## UI structure

The authenticated shell currently uses:

- `Home`: dashboard, continue-playing rows, recent additions, collection highlights, download summary
- `Library`: platform-first browsing and installed-recent rows
- `Collections`: RomM-backed collection browsing
- `Settings`: account/profile, storage, and global control settings

Deeper routes include:

- platform detail
- collection detail
- game detail
- downloads manager
- embedded player

## Testing notes

The repo includes unit coverage for core app logic, including:

- collection model mapping
- download ordering and aggregate repository behavior
- auth gate routing

Live smoke or instrumentation tasks should still be run from the repository root with `./gradlew ...`.

Example connected test:

```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.mattsays.rommnative.auth.AuthLiveSmokeTest
```

## Known limitations

- Core binaries are not bundled by default.
- BIOS management is still only partially modeled in the UI.
- Not every RomM platform is validated for embedded playback yet.
- Some systems remain intentionally controller-first or unsupported in the embedded player.
- The player runtime remains oriented around a recommended-core UX rather than exposing advanced per-game or per-core configuration.

## Additional documentation

- [`docs/core-matrix.md`](docs/core-matrix.md): recommended/alternate runtime policy by platform family
- [`docs/validation-matrix.md`](docs/validation-matrix.md): criteria for promoting a platform to fully supported embedded playback

## Repository scope notes

- This repo is only for the Android-native RomM client and its supporting docs/tooling.
- The Android project lives at the repository root.
- Do not reintroduce old workspace paths such as `apps/romm-android-native/`.
