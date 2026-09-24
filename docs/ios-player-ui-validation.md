# iPhone Air player UI qualification

Run: 2026-09-21, iPhone Air simulator (iOS 27), arm64. This is simulator qualification; Android, physical iPhone performance/audio/controller behavior, and cross-device cloud transfer are separate gates.

The player was exercised with a dedicated `codex-player-qa` account. The installed app is a local QA bundle. Public-server legacy sessions explicitly retain local progress while cloud sync is paused; these runs do not establish cloud sync success. Game-save data (SRAM) and serialized emulator snapshots must be reported independently.

## Observed coverage

| Platform | Game | Verified in the simulator |
| --- | --- | --- |
| NES | Galaga: Demons of Death | Qualified pinned core launch with explicit local-only choice; Start, firing, pause, controls panel, snapshot capture |
| Game Boy | Pokémon Blue | ROM launch after duplicate-frame capability repair; intro, manual snapshot creation/loading, exit |
| Game Boy Color | Pokémon Crystal | Download-to-Play transition; new-game dialogue, button input, snapshot save/load returning to running play |
| GBA | Fire Emblem (ROM displays The Sacred Stones) | Startup, difficulty selection and story introduction, short/held input, portrait/landscape display, snapshot save/load |
| SNES | Super Mario World | New game, map, first playable level, movement/jump, snapshot save/load, exit |
| DS | Mario Kart DS | Both screens, touch setup prompts, gamepad menus, Figure-8 Circuit, acceleration, snapshot restore, independent game-save cold boot |

These are targeted UI sessions, not full-game playthroughs. The Game Boy RPG passes stop in introductory dialogue. No physical-controller, audio-listening, multiplayer, or physical-device performance qualification is inferred.

### Game save and resume snapshot are separate proofs

Mario Kart's nickname/emblem setup was completed in game. After a clean player exit, its 262,144-byte game-save file was retained while the canonical resume snapshot was temporarily moved aside. A fresh game launch reached the main menu without asking for setup again. The same check passed after terminating and restarting the app process, again with its canonical resume snapshot withheld. This verifies retained in-game profile data, not a completed race or campaign milestone. The file hashes and procedure are in [the proof record](validation/ios-player/mario-kart-save-proof.json).

Separately, loading manual snapshot slot 0 from that cold-boot menu returned to Figure-8 Circuit, and acceleration advanced the race. This demonstrates emulator state restoration independently of the game-save check.

Local screenshots (generated artifacts, not tracked ROMs or saves):

- [DS game-save cold boot](../ios/build/player-ui-evidence/ds-game-save-coldboot.png)
- [DS restored race snapshot](../ios/build/player-ui-evidence/ds-snapshot-restored.png)
- [NES firing and score](../ios/build/player-ui-evidence/nes-gameplay.png)
- [GBC restored snapshot](../ios/build/player-ui-evidence/gbc-snapshot-restored.png)
- [SNES playable level](../ios/build/player-ui-evidence/snes-gameplay.png)
- [GBA landscape display](../ios/build/player-ui-evidence/gba-landscape.png)

### Automated validation

The final simulator app builds successfully. Focused Swift suites passed: 12 download queue tests, 6 native bridge tests, 2 frame conversion tests, 8 shell model tests, 1 core provisioning flow test, and 6 pinned Apple progress session tests (35 distinct tests). The native session tests separately cover cold game data, exact resume restoration, failed snapshot capture, rollback, and snapshot deletion preserving the game save. Exploratory XCTest runs were reviewed visually; earlier failing probes led to the fixes below and are not represented as passing qualification.

## Repairs found through the UI pass

- UIKit safe-area points were being mixed with Metal drawable pixels. Video and overlay now use the same full-window point layout, converted to pixels only for rendering.
- Very short touch presses could be pressed and released between frames. The native bridge now retains the edge for one frame, while held inputs persist and pause clears inputs. A native fake-core regression checks all three behaviors.
- Touch controls disappeared after ten seconds even without a physical controller. They now stay visible for touch-only play. Portrait thumb controls are centered in the space below the display.
- Nintendo DS cached empty system/save paths because environment registration ran before session setup. Registration now occurs after owned directories are set. Core error logs now provide useful startup failures.
- The DS stylus overlay covered only part of the bottom screen, used the wrong coordinate range, and never released the stylus. It now maps the entire bottom screen into combined-frame coordinates and sends explicit press/release events. Native regression coverage includes center, edges, short taps, and pause.
- Game Boy cores rejected valid ROMs because the frontend did not advertise duplicate-frame support, although the frame callback already retained duplicate frames. The bridge now advertises that capability.
- Completed downloads stayed at 0% in an open detail page. Detail pages now observe local download records and update the Play action without refetching catalog metadata.
- App updates can change the iOS container's absolute path. Completed owned downloads now recover only their expected relative install path, within the storage root and with a matching SHA-256. Corrupt candidates are rejected by regression tests.
- Library readiness now includes actual core availability rather than only the platform's theoretical control support.
- Snapshot loading left a paused game with the pause menu closed. Loading now resumes, and dismissing player panels resumes when there is no unresolved error.
- Player buttons now expose distinct accessible names and identifiers. The pause menu distinguishes “Save snapshot” from “Sync game saves and snapshots.”

## Repeating the UI pass

The opt-in `ios/AppUITests/PlayerExplorationTests.swift` runner operates the app already installed on the specified simulator. It does not log in, install an app, supply ROMs, or alter a release manifest. Use a QA account and games available on that server.

```sh
# Requires the same xcodeproj Ruby gem as the normal project generator.
scripts/ios/run_player_ui_pass.sh SIMULATOR_UDID /path/to/actions.json
```

Action example (labels must match the current screen):

```json
[
  {"tap": "Start"},
  {"tap": "Right", "hold": "0.4"},
  {"orientation": "landscape"},
  {"wait": "3"},
  {"tap": "Pause game"},
  {"capture": "pause"}
]
```

Captures and accessibility dumps are written to `/tmp/rommio-ui-NAME.{png,txt}` and screenshots attach to the XCTest result under `ios/build/player-ui-runner/Logs/Test`. For visual review after rotation, use `xcrun simctl io UDID screenshot PATH`: Early XCTest app screenshots on this simulator had stale orientation/cropped content; the runner now uses the full-screen screenshot API. The native simulator screenshot is an independent orientation check. A successful runner exit establishes that requested controls were found and operated, not that game content was semantically correct; review screenshots and the game itself.

## Additional simulator cores

The normal bundle contains FCEUmm and FBNeo. This pass additionally built Gambatte, mGBA, Snes9x and melonDS DS locally. Those binaries are **not** added to the release manifest or qualified for physical devices. The QA bundle records source pins and signed binary hashes in `PlayerQAProvenance.json` and carries licenses.

Build sources under a separate cache (this run used `/tmp/rommio-player-cores`), at the exact commits in `scripts/ios/package_player_qa_bundle.py`:

| Directory | Source |
| --- | --- |
| gambatte | https://github.com/libretro/gambatte-libretro.git |
| snes9x | https://github.com/libretro/snes9x.git |
| mgba | https://github.com/libretro/mgba.git |
| melonds_ds | https://github.com/JesseTG/melonds-ds.git |

Gambatte:

```sh
make -j8 -C "$CACHE/gambatte" platform=ios-arm64 \
  CC="clang -target arm64-apple-ios17.0-simulator -isysroot $(xcrun --sdk iphonesimulator --show-sdk-path)" \
  CXX="clang++ -target arm64-apple-ios17.0-simulator -isysroot $(xcrun --sdk iphonesimulator --show-sdk-path)" PLATFORM_DEFINES=
```

Snes9x:

```sh
make -j8 -C "$CACHE/snes9x/libretro" platform=osx CROSS_COMPILE=1 \
  LIBRETRO_APPLE_PLATFORM=arm64-apple-ios17.0-simulator \
  LIBRETRO_APPLE_ISYSROOT="$(xcrun --sdk iphonesimulator --show-sdk-path)" CC=clang CXX=clang++
```

mGBA (CMake installed separately if needed):

```sh
cmake -S "$CACHE/mgba" -B "$CACHE/mgba-build" -DCMAKE_SYSTEM_NAME=iOS \
  -DCMAKE_OSX_SYSROOT=iphonesimulator -DCMAKE_OSX_ARCHITECTURES=arm64 \
  -DCMAKE_OSX_DEPLOYMENT_TARGET=17.0 -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_LIBRETRO=ON -DBUILD_QT=OFF -DBUILD_SDL=OFF -DBUILD_GL=OFF -DBUILD_GLES2=OFF \
  -DUSE_FFMPEG=OFF -DUSE_PNG=OFF -DUSE_MINIZIP=OFF -DUSE_LIBZIP=OFF \
  -DUSE_SQLITE3=OFF -DUSE_ELF=OFF -DUSE_LUA=OFF -DUSE_JSON_C=OFF \
  -DUSE_FREETYPE=OFF -DUSE_LZMA=OFF -DUSE_DISCORD_RPC=OFF -DUSE_EPOXY=OFF \
  -DBUILD_TEST=OFF -DBUILD_SUITE=OFF
cmake --build "$CACHE/mgba-build" --target mgba_libretro -j8
```

melonDS DS (CMake fetches the upstream pinned dependencies):

```sh
cmake -S "$CACHE/melonds_ds" -B "$CACHE/melonds-build" -DCMAKE_SYSTEM_NAME=iOS \
  -DCMAKE_OSX_SYSROOT=iphonesimulator -DCMAKE_OSX_ARCHITECTURES=arm64 \
  -DCMAKE_OSX_DEPLOYMENT_TARGET=17.0 -DCMAKE_BUILD_TYPE=Release \
  -DENABLE_JIT=OFF -DENABLE_OPENGL=OFF -DBUILD_AS_SHARED_LIBRARY=ON -DBUILD_TESTING=OFF
cmake --build "$CACHE/melonds-build" -j8
```

Package after building the normal app:

```sh
scripts/ios/build_simulator_app.sh
python3 scripts/ios/package_player_qa_bundle.py --core-builds "$CACHE"
xcrun simctl install UDID ios/build/PlayerQA/Rommio.app
xcrun simctl launch UDID io.github.mattsays.rommio.ios
```

## Expansion controls pass

The later seven-platform control pass, independent Android comparison, screenshots,
regressions and qualification boundaries are recorded in
[player-controls-validation.md](player-controls-validation.md).

## iPhone Duo pass

Run with Xcode 27.1 or later (the iPhone Duo simulator ships with it) against a
build made with the iOS 27.1 SDK. Apps built with older SDKs run in Apple's
compatibility mode, which hides the vertical bars and edge-to-edge layout.

Device Hub drives poses: the slider beside the rotate button folds (left) and
opens (right) the device, the rotate button turns the open device between
landscape and portrait, and Device → Orientation sets the closed display upright.
Sizes seen on 2026-09-22 (Xcode 27.1 beta 1, iOS 27.1 24A94401): closed 466×678,
open landscape 951×669, open portrait 669×951, in points.

Check each pose:

| Pose | Shell | DS player | Single-screen player |
| --- | --- | --- | --- |
| Closed (outer display) | Vertical tab bar and Back/Play on the trailing bar; Continue playing first with ▶ quick resume | Stacked screens, controls below | Controls below |
| Open, landscape | Vertical tab bar; Home summary beside shelves; two-column game page | Stacked centre column, side rails | Side rails; "Game and controls side by side" moves controls to the other half |
| Open, portrait | Horizontal bars; Library sidebar overlays games | Upper screen enlarged within its half, touch screen below, controls beside the touch screen | Controls below |
| Pause → Controls or Saved progress (open) | — | Pane beside the paused game with live touch controls | Same |

Also confirm: a quick stylus tap registers on the touch screen; pausing shows
every action including Leave game without scrolling; unfolding or folding mid-game
keeps the session; audio is clean (the core's own sample rate, not 44.1 kHz).
The former software renderer ran Pokémon X at roughly 23–24 fps on the
simulator. Azahar Vulkan is now the default; a native 1,200-frame probe ran at
93.3 emulated fps with non-silent audio. Clean audio in the visible player
remains a separate check from layout and native-core throughput.

Beta caveats: `reservedRegions` reports no hinge in beta 1, so the DS layout falls
back to the display midline and book-pose pad mode can't trigger automatically.
With the open device rotated to portrait, Device Hub delivers clicks as pointer
events that some buttons ignore; start a game from the closed display and unfold
mid-game instead. Layout rules are covered at these sizes by
`PlayerDuoStageLayoutTests`.
