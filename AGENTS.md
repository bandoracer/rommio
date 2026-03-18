# AGENTS.md

## Repo scope

- This repository is the standalone home for the Android-native RomM client.
- The Android project now lives at the repository root.
- Emulator helper tooling lives under `scripts/android/`.

## Working rules

- Keep changes scoped to the Android-native client and its supporting docs/tooling.
- Do not reintroduce paths that assume the app lives under `apps/romm-android-native/`.
- Prefer updating repo-level docs when setup, build, or test flows change.

## Build and test

- Build: `./gradlew assembleDebug`
- Unit or instrumentation tasks should be run from the repo root with `./gradlew ...`
- Emulator helper scripts should be run from the repo root as `scripts/android/...`

## Structure

- `app/`: Android application module
- `docs/`: architecture, validation, and platform notes
- `scripts/android/`: local emulator and device workflow helpers

