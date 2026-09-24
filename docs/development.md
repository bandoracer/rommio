# Development

This document collects the setup, build, emulator, device, and release details for working on Rommio locally.

## Repository Layout

- [`app/`](../app): Android application module
- [`docs/`](.): architecture, runtime, and validation notes
- [`scripts/android/`](../scripts/android): local emulator and device workflow helpers

Important source areas:

- [`app/src/main/java/io/github/bandoracer/rommio/data/`](../app/src/main/java/io/github/bandoracer/rommio/data)
- [`app/src/main/java/io/github/bandoracer/rommio/domain/`](../app/src/main/java/io/github/bandoracer/rommio/domain)
- [`app/src/main/java/io/github/bandoracer/rommio/ui/`](../app/src/main/java/io/github/bandoracer/rommio/ui)
- [`app/src/test/`](../app/src/test)

## Requirements

- macOS or Linux
- JDK 17
- Android SDK with platform tools
- Android NDK `27.2.12479018` and CMake `3.22.1` for the local native frontend
- An Android device or emulator running API 26+

Install the pinned native tools with `sdkmanager "ndk;27.2.12479018" "cmake;3.22.1"`. Gradle compiles the source-pinned `third_party/libretrodroid` module for all four supported ABIs, without fetching native submodules. Gradle also builds the [pinned FCEUmm core](android-pinned-core.md) as APK assets for all four ABIs; Python 3.9 or later is required for the source verifier/build script. Debug builds include a small synthetic core for mandatory capture-failure and session-isolation tests; release native targets exclude it. See [native runtime provenance, boundaries, and test commands](android-native-runtime.md).

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

### Theme tokens

The Aurora and Riso themes (colours, type roles, radii, spacing, player styles, art parameters) live in [`design/tokens/themes.json`](../design/tokens/themes.json). After editing it, regenerate the platform token files and commit them together:

```bash
python3 scripts/generate_theme_tokens.py
```

This writes `app/src/main/java/io/github/bandoracer/rommio/ui/theme/ThemeTokens.kt` and `ios/Sources/RommioUI/Theme/ThemeTokens.swift`; never edit those by hand. CI runs `python3 scripts/generate_theme_tokens.py --check`. Page layouts per theme are specified in [theme-layouts.md](theme-layouts.md); bundled fonts and their licences are listed in [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).

### APKs

Build the renamed standard debug APK from the repository root:

```bash
./gradlew assembleStandardDebug
```

Standard debug APK output:

- `app/build/outputs/apk/standard/debug/app-standard-debug.apk`

Build the temporary legacy bridge APK that keeps the old package id for export-driven migration:

```bash
./gradlew assembleLegacyBridgeDebug
```

Legacy bridge debug APK output:

- `app/build/outputs/apk/legacyBridge/debug/app-legacyBridge-debug.apk`

Build the standard release APK:

```bash
./gradlew assembleStandardRelease
```

Standard release APK output:

- `app/build/outputs/apk/standard/release/app-standard-release.apk`

## Test

Run unit tests from the repository root:

```bash
./gradlew testStandardDebugUnitTest
```

Before submitting functional changes, run both package flavors and lint:

```bash
./gradlew testStandardDebugUnitTest testLegacyBridgeDebugUnitTest lintStandardDebug lintLegacyBridgeDebug assembleDebug
```

The Android validation workflow runs these checks on pull requests and before release publication. Release publication requires signing credentials and verifies both flavor package IDs and signing certificates. The [remediation status](remediation-status.md) records current functional coverage and remaining device/server gates. The isolated [RomM protocol test environment](../tests/integration/romm/README.md) uses synthetic data, not personal saves.

Install to a connected device or booted emulator:

```bash
./gradlew installStandardDebug
```

Install the legacy bridge build over an existing `io.github.mattsays.rommnative` install:

```bash
./gradlew installLegacyBridgeDebug
```

Example connected instrumentation run:

```bash
./gradlew connectedStandardDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.bandoracer.rommio.auth.AuthLiveSmokeTest
```

The v2 progress-store regression tests use only temporary local data and need no RomM login:

```bash
ANDROID_SERIAL=emulator-5580 ./gradlew connectedStandardDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.bandoracer.rommio.domain.progress.ProgressStoreTest
swift test --package-path ios --filter Progress
```

UI instrumentation tests can run under a specific theme and appearance. Pass the arguments through Gradle, or with `am instrument` when you need to keep the app's data (connected Gradle runs uninstall the app afterwards):

```bash
./gradlew connectedStandardDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.bandoracer.rommio.domain.player.PlayerScreenProgressTest \
  -Pandroid.testInstrumentationRunnerArguments.rommioTheme=riso \
  -Pandroid.testInstrumentationRunnerArguments.rommioScheme=dark
```

`rommioTheme` is `aurora` or `riso`; `rommioScheme` is `system`, `light` or `dark`.

Use the serial of an isolated emulator you created for the run; `emulator-5580` above is an example. Shared wire fixtures are packaged into both Android test source sets and read by the Swift package tests from `tests/fixtures/progress-v2`. See the [format and storage contract](progress-protocol-v2.md) for coverage and limitations. The new instrumentation cases are currently a local gate; hosted emulator execution remains to be added to release qualification.

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

Startup now waits for the requested AVD by name, then its boot-complete property. It fails on connection/boot timeout rather than treating another connected device as ready.

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

Use the legacy bridge variant instead of the renamed standard app:

```bash
APP_VARIANT=legacyBridge scripts/android/install_and_launch.sh
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

The script writes into the app's internal storage via `run-as`. Core filenames are normalized to the catalog's `lib…_libretro_android.so` names. Core pushes are legacy bootstrap copies; an already selected versioned core is retained. See [core artifact verification and selection](android-core-artifacts.md). BIOS files now go to `files/library/system/`, including `system/dc/dc_boot.bin` for Dreamcast. It rejects mutable `dc_flash.bin` as a BIOS import. Normal users should use Settings → Manage BIOS files, which validates content and retains differing older copies. See [the firmware layout and remaining qualification gates](android-firmware.md).

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

Both apps support user-configured HTTP server URLs; the native transport policies now match that behavior. See [session isolation](auth-session-isolation.md) for cookie ownership, logout, and the remaining provider/device checks.

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

Catalog refresh behavior, schema-12 readiness migration, and Android qualification limits are documented in [Android catalog refresh](android-catalog-refresh.md).


### Game-save and snapshot qualification

The qualified Android player uses the [independent progress protocol](progress-protocol-v3.md). Run the normal unit/lint/build/instrumentation tasks from the repository root. For opt-in real RomM synchronization and external process-kill/offline recovery, build `assembleStandardDebug assembleStandardDebugAndroidTest`, start the [isolated RomM stack](../tests/integration/romm/README.md), then run `python3 scripts/android/verify_progress_sync.py --serial emulator-5580 --credentials /tmp/rommio-probe-credentials.json`. The script accepts only a disposable `rommio-progress-test-*` AVD and the isolated localhost server, and removes its synthetic credentials afterward.
