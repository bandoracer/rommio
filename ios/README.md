# Rommio iOS

This directory contains the native iOS foundation and app shell for Rommio.

The code is package-first so the shared contract, networking, storage, sync, player policy, and SwiftUI shell can be validated independently. A generated Xcode app target now sits on top of those modules for Simulator and device builds.

For product behavior and player semantics, use the narrative iOS specs in:

- [`../docs/ios-product-spec.md`](../docs/ios-product-spec.md)
- [`../docs/ios-player-spec.md`](../docs/ios-player-spec.md)

Keep the core and runtime packaging rules in:

- [`../docs/core-matrix-ios.md`](../docs/core-matrix-ios.md)
- [`../docs/runtime-matrix.json`](../docs/runtime-matrix.json)

## Modules

- `RommioContract`: platform-neutral API, auth, offline, and collection/runtime models
- `RommioFoundation`: URLSession client, library storage, sync bridge, and secret-storage abstractions
- `RommioPlayerBridge`: Objective-C++ libretro host bridge for bundled iOS cores
- `RommioPlayerKit`: iOS player engine, bundled-core verification, and player surface
- `RommioUI`: SwiftUI onboarding and authenticated shell scaffold
- `App/`: native SwiftUI app entry point consumed by the generated Xcode project

## Local validation

Run package tests:

```bash
swift test --package-path ios
```

Import a bundled iOS libretro core and its license into the app resources:

```bash
scripts/ios/import_bundled_core.sh \
  /path/to/fceumm_libretro_ios.dylib \
  fceumm \
  /path/to/fceumm.LICENSE.txt
```

Validate that the parity build manifest accounts for every Android-default
runtime family before importing or bundling release cores:

```bash
scripts/ios/verify_core_build_manifest.sh
```

Use `--require-files` only once the device/simulator artifacts and license
files have been staged locally:

```bash
scripts/ios/verify_core_build_manifest.sh --require-files
```

Generate the Xcode project for the app target:

```bash
gem install xcodeproj
ruby scripts/ios/generate_xcodeproj.rb
```

Verify that every tracked bundled core, license file, and `CoreLicenses.json`
entry is still wired into the Rommio app target's `Resources` phase before
shipping or after refreshing a signed core:

```bash
scripts/ios/verify_bundled_core_resources.sh
```

Open the app project in Xcode:

```bash
open ios/Rommio.xcodeproj
```

You can also open the package directly for module-focused work:

```bash
open ios/Package.swift
```

Build the app for the iOS simulator:

```bash
scripts/ios/build_simulator_app.sh
```

Build, install, and launch the app on the booted simulator, or fall back to
`iPhone Air` when no simulator is booted:

```bash
scripts/ios/install_and_launch_simulator.sh
```

Pass a specific simulator when needed:

```bash
scripts/ios/install_and_launch_simulator.sh --device-name "iPhone 16"
scripts/ios/install_and_launch_simulator.sh --udid 699D613E-95F1-4EDD-835C-CD68F32E525D
```

The app only advertises a family as playable once a signed bundled core exists
under `ios/App/Resources/Cores/`, passes `BundledCoreVerifier`, and can be
provisioned from the shipped bundle into the app's runtime inventory. That
provisioning now happens automatically on demand when a title is downloaded or
launched, which mirrors Android's integrated core flow without downloading
executable code at runtime on iOS.

The signed dylibs under `ios/App/Resources/Cores/`, `CoreLicenses.json`, and
the corresponding license files are repo-tracked build inputs. Keep them in Git
and keep them bundled through the Rommio app target `Resources` phase. Do not
move them into SwiftPM artifacts, generated build directories, or runtime
downloads.

Generated SwiftPM and Xcode outputs are always local-only and must never be
tracked. That includes `ios/.build`, `ios/build`, `ios/build-logs`,
`ios/.derived-sim`, `ios/.swiftpm`, simulator logs, `xcuserdata`, and the
generated top-level `ios/Rommio.xcworkspace`.

The supported app editor/build entrypoint is `ios/Rommio.xcodeproj`. The
generated workspace is only a local convenience artifact and is not a canonical
tracked project file.

If Xcode 26's iOS 26 simulator reports `Data Migration Failed` during boot even
though the device still reaches `Booted`, repair the simulator's aggregate
migration state and reboot it:

```bash
scripts/ios/repair_simulator_migration_state.sh --reboot <simulator-udid>
```

Or build an individual package scheme from the package directory:

```bash
cd ios
xcodebuild -scheme RommioUI -destination 'generic/platform=iOS Simulator' build
```
