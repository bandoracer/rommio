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

The app build embeds thirteen independently signed core frameworks, including
Stella, ProSystem, and Handy. Pinned source builds produce each new cartridge
core for simulator and device; the final framework digest is recorded in its
provenance file. Cartridge builds need Git, Make, Python 3, CMake, and network
access for first-time source/dependency fetches. Set `CMAKE` to select an
explicit executable. The blocked Azahar 3DS candidate can be included in a
Debug simulator or device build with `ROMMIO_INCLUDE_AZAHAR_CANDIDATE=1` and
`ROMMIO_AZAHAR_SOURCE_CACHE=<pinned-source-checkout>`. It is omitted from
normal builds and every Release build. See [build inputs and persistence
scope](../docs/cartridge-progress.md), [platform expansion
qualification](../docs/platform-expansion.md), and the [priority core
plan](../docs/ios-priority-core-plan.md).

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

The app only advertises a family as playable once its core is in the verified
packaged-core inventory and can be provisioned from a signed framework under
`Rommio.app/Frameworks/`. Provisioning happens automatically when a title is downloaded or
launched, which mirrors Android's integrated core flow without downloading
executable code at runtime on iOS.

The main repo tracks the shipped-core manifest, `CoreLicenses.json`, and the
corresponding license files, but not the prebuilt binary inputs. Those inputs
are fetched from the GitHub Release declared in
`scripts/ios/bundled-core-release-manifest.json`; the Xcode build converts
them to signed frameworks in the final app product. Verify that product with
`python3 scripts/ios/verify_embedded_cores.py ios/build/Debug-iphonesimulator/Rommio.app`.
For a distribution archive, use `--distribution` to require matching Apple
Distribution signatures on the app and every framework.

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

### Nintendo 3DS candidate qualification

Azahar is a blocked, opt-in Debug candidate. Build its simulator framework
into the app with `ROMMIO_INCLUDE_AZAHAR_CANDIDATE=1 scripts/ios/build_simulator_app.sh`.
The separate Vulkan core build probe and the remaining iOS GPU-host contract
are documented in [3DS GPU renderer integration](../docs/ios-3ds-gpu-renderer.md).
The normal player catalog keeps 3DS blocked; `ROMMIO_ENABLE_AZAHAR_QA=1` opens
it only in a Debug QA launch.
Release builds do not include that override.

The reproducible simulator probe uses the separately obtained MIT-licensed
3DStris 1.6.9 `.3dsx` fixture and the disposable, loopback-only RomM 5.2
stack. From the repository root, run:

```bash
python3 scripts/ios/verify_3ds_simulator.py \
  --simulator SIMULATOR_UDID \
  --rom build/azahar-inspection/3dstris.3dsx \
  --endurance-seconds 300
```

It checks a game-authored config change through the real player, separately
publishes the game-save bundle and resume snapshot, restores both from a
fresh app data root, and cold-boots another root using the game save alone.
It also runs the native 3DS core for the requested endurance interval and
reads back the separate RomM blobs. Keep test ROMs and credentials out of Git.
The hosted RomM library currently serves 3DS titles as ZIP archives. The
candidate accepts an account-verified ZIP containing exactly one playable 3DS
file, extracts it into the isolated session workspace, and binds progress to
both the archive and extracted game hashes. A large user-owned ZIP can be
checked without checking it into Git by setting `ROMMIO_3DS_ARCHIVE` for
`swift test --package-path ios --filter ThreeDSArchiveTests`. The extracted
game and archive both occupy local storage during play.
For a real RomM ZIP boot check, use a QA account credential JSON containing
`username` and `password` and run `scripts/ios/verify_3ds_live_romm.py` with
`--simulator`, `--base-url`, `--credentials`, `--rom-id`, and `--file-id`.
For a server protected by Cloudflare Access, add `--cloudflare-credentials`
with the one-touch service credentials JSON; the probe uses the same iOS
Cloudflare mode as the app and removes its temporary credential copy afterward.
The script downloads into a disposable app root and can save a player screenshot
with `--screenshot`; it does not test a game's own save command.
See `docs/ios-priority-core-plan.md` for the remaining release gates.
