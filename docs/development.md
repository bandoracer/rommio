# Development

This document collects the setup, build, emulator, device, and release details for working on Rommio locally.

## Repository Layout

- [`app/`](../app): Android application module
- [`docs/`](.): architecture, runtime, and validation notes
- [`scripts/android/`](../scripts/android): local emulator and device workflow helpers

Important source areas:

- [`app/src/main/java/io/github/mattsays/rommnative/data/`](../app/src/main/java/io/github/mattsays/rommnative/data)
- [`app/src/main/java/io/github/mattsays/rommnative/domain/`](../app/src/main/java/io/github/mattsays/rommnative/domain)
- [`app/src/main/java/io/github/mattsays/rommnative/ui/`](../app/src/main/java/io/github/mattsays/rommnative/ui)
- [`app/src/test/`](../app/src/test)

## Requirements

- macOS or Linux
- JDK 17
- Android SDK with platform tools
- An Android device or emulator running API 26+

The helper scripts assume an SDK that includes:

- `platform-tools`
- `emulator`
- Android API `36`
- `google_apis` arm64 system image

Default emulator values come from [`scripts/android/common.sh`](../scripts/android/common.sh):

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

Build a release APK:

```bash
./gradlew assembleRelease
```

Release APK output:

- `app/build/outputs/apk/release/app-release.apk`

## Test

Run unit tests from the repository root:

```bash
./gradlew testDebugUnitTest
```

Install to a connected device or booted emulator:

```bash
./gradlew installDebug
```

Example connected instrumentation run:

```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.mattsays.rommnative.auth.AuthLiveSmokeTest
```

## Android Tooling Quick Start

All helper scripts are intended to be run from the repository root.

### Check the local Android toolchain

```bash
scripts/android/doctor.sh
```

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

### Build, install, and launch the app

```bash
scripts/android/install_and_launch.sh
```

Install and launch on a specific target:

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

This is useful when testing embedded playback locally without building every in-app download flow first.

```bash
scripts/android/push_runtime_assets.sh --core ~/Downloads/snes9x_libretro_android.so
scripts/android/push_runtime_assets.sh --bios ~/Downloads/scph5501.bin
scripts/android/push_runtime_assets.sh --platform snes --rom ~/Downloads/smw.sfc
```

The script writes into the app's internal storage via `run-as`.

## Local Debug Auth

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

## Release Signing

Local release builds fall back to the Android debug key unless dedicated signing values are present.

Create a root-level `.env.release.local` file or export matching environment variables:

```bash
ROMMIO_RELEASE_KEYSTORE_PATH=
ROMMIO_RELEASE_STORE_PASSWORD=
ROMMIO_RELEASE_KEY_ALIAS=
ROMMIO_RELEASE_KEY_PASSWORD=
```

If these are present, `assembleRelease` signs the APK with that keystore instead of the debug key.

GitHub Actions also supports:

- `ROMMIO_RELEASE_KEYSTORE_BASE64`
- `ROMMIO_RELEASE_STORE_PASSWORD`
- `ROMMIO_RELEASE_KEY_ALIAS`
- `ROMMIO_RELEASE_KEY_PASSWORD`

## GitHub Releases

The repository includes [`/.github/workflows/android-release.yml`](../.github/workflows/android-release.yml).

- Pushing a tag like `v0.2.0` builds `app-release.apk`
- The workflow uploads the APK as both a build artifact and a GitHub release asset
- If repository signing secrets are configured, the workflow signs the release with that keystore
- Without those secrets, the workflow still produces an installable APK signed with the debug key for testing or pre-release distribution

## Related Runtime Docs

- [core-matrix.md](core-matrix.md)
- [validation-matrix.md](validation-matrix.md)
