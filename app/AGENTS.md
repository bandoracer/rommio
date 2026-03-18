# AGENTS.md

## Module scope

- This module contains the Android application code for Rommio.
- Keep package names, manifest wiring, and Gradle configuration internally consistent.

## Code map

- `src/main/java/io/github/mattsays/rommnative/data/`: auth, networking, Room, repositories, workers
- `src/main/java/io/github/mattsays/rommnative/domain/`: player, storage, sync, and input models
- `src/main/java/io/github/mattsays/rommnative/ui/`: Compose app shell, navigation, and screens
- `src/androidTest/`: live smoke tests and instrumentation coverage

## Expectations

- Preserve the app-managed storage model.
- Preserve the two-layer auth model and the embedded-player architecture.
- Keep touch controls and immersive-mode behavior testable on real devices.

