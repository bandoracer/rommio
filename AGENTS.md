# AGENTS.md

## Repo scope

- This repository is the home for the Android and iOS native RomM clients.
- The Android project now lives at the repository root.
- The iOS app and Swift package live under `ios/`.
- Platform helper tooling lives under `scripts/android/` and `scripts/ios/`.

## Working rules

- Maintain Android and iOS feature parity against `docs/save-sync-spec.md`; synced game saves and synced resume snapshots are equally important.
- Platform qualification must be reported separately; do not claim parity from a shared model or passing unit tests alone.
- Do not reintroduce paths that assume the app lives under `apps/romm-android-native/`.
- Prefer updating repo-level docs when setup, build, or test flows change.

## Build and test

- Android build: `./gradlew assembleDebug`
- iOS native test prerequisite: `python3 scripts/ios/build_pinned_core.py --sdk macosx --arch "$(uname -m)"`
- iOS package tests: `swift test --package-path ios`
- iOS app/simulator validation: follow `ios/README.md` and `scripts/ios/`.
- Android unit and instrumentation tasks should be run from the repo root with `./gradlew ...`
- Android emulator helper scripts should be run from the repo root as `scripts/android/...`

## Structure

- `app/`: Android application module
- `ios/`: iOS application, Swift package, and tests
- `scripts/ios/`: Apple build and simulator helpers
- `docs/`: architecture, validation, and platform notes
- `design/studio/`: browser-based UI mockup studio (design directions for every screen; see its README)
- `scripts/android/`: local emulator and device workflow helpers

