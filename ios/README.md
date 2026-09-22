# Rommio iOS

Android and iOS must meet the same [save/sync specification](../docs/save-sync-spec.md). Synced game saves and synced resume snapshots have equal priority. Qualified pinned NES, GB/GBC, GBA, SNES, and DS routes use schema-3 persistence, an ordered retained session owner, recovery UI, and application-owned sync. See [cartridge scope and live platform evidence](../docs/cartridge-progress.md). Real iOS/Android exchange and offline iOS process restart pass. See [qualification evidence and limits](../docs/ios-progress-player.md); complete progress backup/import and recovery are now available in Settings. See [backup/recovery behavior](../docs/ios-progress-backup.md). Broader formats and physical lifecycle qualification remain separate.

This directory contains the native iOS foundation and app shell for Rommio.

The code is package-first so the shared contract, networking, storage, sync, player policy, and SwiftUI shell can be validated independently. A generated Xcode app target now sits on top of those modules for Simulator and device builds.

Legacy cloud-save reconciliation and publication are paused in this development version. Local saves, recovery files, and pending intent remain available; the replacement protocol is still being implemented. See the [remediation status](../docs/remediation-status.md).

For product behavior and player semantics, use the narrative iOS specs in:

- [`../docs/ios-product-spec.md`](../docs/ios-product-spec.md)
- [`../docs/ios-player-spec.md`](../docs/ios-player-spec.md)
- [Shared save, sync, and session architecture](../docs/save-sync-spec.md) — target behavior and implementation/release gates
- [App remediation plan](../docs/remediation-plan.md) — audit coverage, implementation order, and acceptance criteria

Keep the core and runtime packaging rules in:

- [`../docs/core-matrix-ios.md`](../docs/core-matrix-ios.md)
- [`../docs/runtime-matrix.json`](../docs/runtime-matrix.json)

## Sign-in

Onboarding follows Marquee's two-screen pattern: server address → Connect →
RomM sign-in → library. Connect runs discovery and native access verification;
optional gateway settings are in the Access profile sheet. Returning from browser
access automatically rechecks the server. Usernames support password autofill,
errors stay beside the form, and successful sign-in has no extra confirmation page.
Both mobile clients request player permissions rather than administrator-only user
management permissions, so normal RomM viewer accounts can sign in.

## Modules

- `RommioContract`: platform-neutral API, auth, offline, and collection/runtime models
- `RommioFoundation`: URLSession client, library storage, sync bridge, and secret-storage abstractions
- `RommioPlayerBridge`: Objective-C++ libretro host bridge for bundled iOS cores
- `RommioPlayerKit`: iOS player engine, bundled-core verification, and player surface
- `RommioUI`: SwiftUI onboarding and authenticated shell scaffold
- `App/`: native SwiftUI app entry point consumed by the generated Xcode project

## Local validation

The app targets iOS 18 and later. iPhone Duo support (vertical bars, fold-aware
layouts, `ArrangementView`) needs Xcode 27.1 or later; see the
[iPhone Duo plan](../docs/iphone-duo-plan.md). Host tools:

```bash
brew install cmake ripgrep
```

CMake builds the pinned cores whenever the SDK changes, so a new Xcode always
needs it even when an older build succeeded from cache. `ripgrep` is used by the
simulator repair script.

Keep `xcode-select` on the release Xcode. For iPhone Duo work, point individual
commands at the beta instead:

```bash
DEVELOPER_DIR=/Applications/Xcode-beta.app/Contents/Developer scripts/ios/build_simulator_app.sh
```

Run package tests:

```bash
python3 scripts/ios/build_pinned_core.py --sdk macosx --arch "$(uname -m)"
swift test --package-path ios
```

Fetch the shipped signed iOS bundled cores declared in the release manifest:

```bash
scripts/ios/fetch_bundled_cores.sh
```

Stage a freshly signed bundled core artifact and its license into a temporary
packaging directory before publishing it to the shared GitHub Release tag:

```bash
scripts/ios/import_bundled_core.sh \
  /path/to/fceumm_libretro_ios.dylib \
  fceumm \
  /path/to/fceumm.LICENSE.txt
```

Publish one or more staged bundled cores to GitHub Releases and refresh the
shipped-core manifest plus `CoreLicenses.json`:

```bash
scripts/ios/publish_bundled_cores.sh \
  --staging-dir build/ios/bundled-core-staging \
  --release-tag ios-bundled-cores-v20260328-1
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

Verify that every shipped bundled core declared in the release manifest,
`CoreLicenses.json`, and its tracked license file are still wired into the
Rommio app target's `Resources` phase before shipping or after refreshing a
signed core:

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

The app build compiles pinned FCEUmm, Gambatte, mGBA, Snes9x, melonDS DS, Genesis Plus GX, PCSX ReARMed, Mupen64Plus-Next, and PPSSPP for its Apple target, signs each bundled dylib, and records its final digest in per-core provenance. The older downloaded FCEUmm resource is replaced by this build phase. Cartridge builds need Git, Make, Python 3, CMake, and network access for first-time source/dependency fetches. Set `CMAKE` to select an explicit executable. See [build inputs and persistence scope](../docs/cartridge-progress.md) and [platform expansion qualification](../docs/platform-expansion.md).

That build reuses the tracked `ios/Rommio.xcodeproj` by default so routine
simulator builds do not churn the repo. Regenerate the project only when the
tracked project is missing or when you intentionally want to refresh it:

```bash
REGENERATE_XCODEPROJ=1 scripts/ios/build_simulator_app.sh
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

The main repo tracks the shipped-core manifest, `CoreLicenses.json`, and the
corresponding license files, but not the signed dylib blobs themselves. The
signed bundled cores live in GitHub Release assets declared in
`scripts/ios/bundled-core-release-manifest.json` and are fetched into
`ios/App/Resources/Cores/` locally and in CI. Keep those fetched dylibs bundled
through the Rommio app target `Resources` phase. Do not move them into SwiftPM
artifacts, generated build directories, or runtime downloads.

Generated SwiftPM and Xcode outputs are always local-only and must never be
tracked. That includes `ios/.build`, `ios/build`, `ios/build-logs`,
`ios/.derived-sim`, `ios/.swiftpm`, simulator logs, `xcuserdata`, and the
generated top-level `ios/Rommio.xcworkspace`.

The supported app editor/build entrypoint is `ios/Rommio.xcodeproj`. The
generated workspace is only a local convenience artifact and is not a canonical
tracked project file.

If a simulator (seen on iOS 26 and the iOS 27.1 iPhone Duo runtime) reports
`Data Migration Failed` during boot even though the device still reaches
`Booted`, repair the simulator's aggregate
migration state and reboot it:

```bash
scripts/ios/repair_simulator_migration_state.sh --reboot <simulator-udid>
```

Or build an individual package scheme from the package directory:

```bash
cd ios
xcodebuild -scheme RommioUI -destination 'generic/platform=iOS Simulator' build
```

### One-touch server setup

The server screen now offers a Marquee-style pairing flow that provisions the
RomM URL and Cloudflare service token through browser approval. RomM account
sign-in remains separate. The backend lives in [`../setup-service`](../setup-service/README.md).
See [`../docs/one-touch-setup-validation.md`](../docs/one-touch-setup-validation.md)
for deployment prerequisites and current qualification. The hosted service is
deployed; browser approval, credential delivery, RomM login, and relaunch were
verified on the iPhone Air simulator on 2026-09-21. Physical-device qualification
remains outstanding.

### Player UI exploration

For the iPhone Air gameplay pass, standalone XCTest action runner, simulator-only
additional cores, and separate game-save/snapshot evidence, see
[`docs/ios-player-ui-validation.md`](../docs/ios-player-ui-validation.md).
The QA bundle does not change the production core manifest.

### Expanded player controls validation

After installing the Debug simulator app, run
`bash scripts/ios/validate_player_controls.sh SIMULATOR_UDID` from the repo root.
The runner requires the `xcodeproj` Ruby gem (the same prerequisite as project
generation); `RUBY`, `GEM_HOME` and `GEM_PATH` may select a local Ruby environment.
It opens an isolated controls harness, checks every console button and analog axis,
mirroring, rotation and Menu, then returns the app to its normal root. It does not
connect to a server or modify game progress. Actual fixture-player validation is
separate; see `docs/player-controls-validation.md`.
