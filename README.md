# Rommio

`rommio` is the standalone Android-native RomM client extracted from the larger `bakkery` workspace.

It is built around:

- Kotlin + Jetpack Compose
- Retrofit/OkHttp for the existing RomM HTTP API
- Room for local install and save-state metadata
- WorkManager for app-managed downloads
- LibretroDroid for the embedded player surface

## What exists today

- Two-step auth flow that mirrors the previous mobile client
- Cloudflare Access and RomM auth parity, including service-token and bearer-password flows
- App-managed ROM downloads and save/state sync
- Embedded libretro playback with in-app core download support
- Touch controls, pause/menu controls, and controller-first fallbacks
- Android emulator tooling for local dev and smoke testing

## Build

```bash
./gradlew assembleDebug
```

Debug APK:

- `app/build/outputs/apk/debug/app-debug.apk`

## Emulator workflow

The repo includes CLI-only Android emulator tooling under `scripts/android/`:

- `doctor.sh`
- `install_sdk_packages.sh`
- `create_avd.sh`
- `start_emulator.sh`
- `install_and_launch.sh`
- `logcat_romm_native.sh`
- `push_runtime_assets.sh`

Typical setup:

```bash
scripts/android/doctor.sh
scripts/android/install_sdk_packages.sh
scripts/android/create_avd.sh
scripts/android/start_emulator.sh --background
scripts/android/install_and_launch.sh
```

Live auth smoke test with local `.env.test.local` credentials:

```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.mattsays.rommnative.auth.AuthLiveSmokeTest
```

Optional local debug keys in `.env.test.local`:

- `ROMM_TEST_BASE_URL`
- `ROMM_TEST_CLIENT_ID`
- `ROMM_TEST_CLIENT_SECRET`
- `ROMM_TEST_USERNAME`
- `ROMM_TEST_PASSWORD`

If the RomM server is running on the same host as the Android emulator, use `http://10.0.2.2:<port>` inside the emulator instead of `localhost`.

## Core model

- The app is architected for multiple cores per platform family.
- Each supported family has one recommended default core and hidden alternates for future advanced overrides.
- The current UI intentionally exposes only one simple action:
  - `Download recommended core`

See `docs/core-matrix.md` for the current default and alternate runtime map.

## Known gaps

- Core binaries are not bundled in the repo by default.
- BIOS management is only partially modeled.
- Touch-overlay customization and controller remapping UI are incomplete.
- Interactive cookie/OIDC flows are implemented, but the Cloudflare service-token + bearer-password path is the only automated live smoke path today.

## Project map

- `app/src/main/java/io/github/mattsays/rommnative/data/`
  auth stack, networking, Room, repository, and background work
- `app/src/main/java/io/github/mattsays/rommnative/domain/`
  embedded player, core resolution, app-managed storage, and sync bridge
- `app/src/main/java/io/github/mattsays/rommnative/ui/`
  Compose app shell and screens
- `docs/validation-matrix.md`
  platform validation rubric for the embedded player
