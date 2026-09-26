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

The opt-in `ios/AppUITests/PlayerExplorationTests.swift` runner operates the app already installed on the specified simulator or device. It does not log in, install an app, supply ROMs, or alter a release manifest. Use a QA account and games available on that server. `scripts/ios/generate_player_ui_runner.rb --actions FILE` embeds the actions in the test bundle (`rommio-ui-actions.json`), so the same pass runs on a device; on a simulator the runner also reads the host's `/tmp/rommio-player-ui-actions.json` when nothing is embedded. The actions are interpreted by `ios/AppUITests/UIActionRunner.swift`.

```sh
# Requires the same xcodeproj Ruby gem as the normal project generator.
scripts/ios/run_player_ui_pass.sh SIMULATOR_UDID /path/to/actions.json
# A device (signs the runner with your team):
ROMMIO_DEVELOPMENT_TEAM=TEAMID scripts/ios/run_player_ui_pass.sh --device DEVICE_UDID /path/to/actions.json
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

A pass may start with `{"launch": "-appearance.theme riso -appearance.scheme dark"}` (optionally
`"env": "KEY=VALUE;KEY=VALUE"`) to relaunch the app with a theme and appearance for this run only (the
UserDefaults argument domain; nothing is saved). `{"tapFirst": "library.platform."}` taps the first
element whose identifier starts with the prefix (scrolling to it), `{"tapTab": "Stats"}` taps a tab,
`{"back": "1"}` taps the navigation bar's back button and `{"waitFor": "gameDetail.hero"}` waits for an
element. After every action the app must still be in the foreground, so a crash fails the pass at the
step that caused it.

Other actions: `{"touch": "identifier", "x": "0.5", "y": "0.5"}` taps any element by identifier,
`{"search": "text"}` types into the first search field, `{"type": "text"}` types into the focused
field, `{"swipe": "up"}`, and `{"tapIfPresent": "label", "timeout": "4"}` taps a button only when it
appears (for example `launch.start` on the Launch choice sheet, which opens before the player when
the cloud check did not finish; the player's own fallback buttons are `player.start.resume`,
`player.start.gameSave` and `player.start.back`).

The controls QA harness (`ROMMIO_CONTROLS_QA=<family>` in a Debug build, e.g.
`SIMCTL_CHILD_ROMMIO_CONTROLS_QA=snes xcrun simctl launch UDID io.github.mattsays.rommio.ios`) draws
the player style of the stored Settings › Appearance (theme and light/dark), so both themes'
controls can be checked without a ROM. Add `ROMMIO_CONTROLS_QA_PAUSE=1` to open Pause the way the
player presents it (`PlayerPanelPresentation`): a sheet on narrow windows, and on a wide window
(iPhone Duo open, iPad; regular width and at least 700 pt) the floating inspector beside the game
(`player.sidePane` containing `player.pause.sheet`), with the stage keeping the game viewport
(`qa.viewport`) clear of it. The menu button then opens and closes Pause instead of hiding the
controls; the pause buttons only log to `qa.events`.

Touch controls v2 (`docs/player-controls.md`) on a running game: `PlayerTouchControlsTests`
(simulator, opt-in with the host file `/tmp/rommio-touch-tests`; the app signed in to the scratch
mock server so Search finds "NES pattern test"). It launches with `ROMMIO_INPUT_LOG=1` (Debug only),
which makes the engine record every input the core receives (`D<key>:<1|0>`, `A<stick>:<x>,<y>`)
and shows them in the hidden `player.debug.inputLog` element, then checks a touch 10 pt outside A's
circle, the chord between A and B, a d-pad drag that leaves the pad, five quick taps, rolling from B
to A, and A's edge in landscape. Build the runner with `scripts/ios/generate_player_ui_runner.rb`
and run `-only-testing:PlayerUIRunner/PlayerTouchControlsTests`. The runner's `drag` and `pull`
actions scroll Play's carousel and pull to refresh (`app.swipeLeft()` does not page the carousel).

Analog sticks and per-console layouts: `PlayerStickControlsTests` (simulator, opt-in with the host
file `/tmp/rommio-stick-tests`, no server needed) drives the controls QA harness. It checks that an
N64 stick's centre floats to the touch (a press off-centre reads 0,0; the travel from there is full
deflection; a drag far outside the zone keeps the stick), that PlayStation shows its sticks, L3 and
R3 only with Analog controls (the harness shows them unless `ROMMIO_CONTROLS_QA_ANALOG=0`), and that
taps on the DS and 3DS touch screens, including their corners next to the controls, reach the game
(the harness logs them as `screen1:x,y` in `qa.events`) while the 3DS circle pad drives stick 0.

The same harness runs the touch model without a game: `PlayerHarnessButtonTests` checks a tap
10 pt outside A, the A/B chord, quick taps, rolling from B to A, a d-pad direction held off the pad
until lift, and the GBA shoulders (above their clusters, pressing L and R), then captures every
family in portrait and landscape. The harness plays nothing and writes no saves, so both classes
also run on a signed-in phone. `scripts/ios/run_controls_pass.sh` builds the runner with
`--controls` (which opts both classes in through the bundled config, no host file) and runs them
against the installed Debug app, writing the result bundle, captures and a summary to
`ios/build/controls-pass/<theme>-<scheme>/`:

```sh
scripts/ios/run_controls_pass.sh --simulator UDID --theme aurora --scheme light
scripts/ios/run_controls_pass.sh --device UDID --theme riso --scheme dark --team TEAMID
```

On a phone, turn on Do Not Disturb first: a notification banner over the top of the screen takes
the runner's next tap there and sends the app to the background.

The resume-conflict seed (`ROMMIO_SEED_RESUME_CONFLICT=1`, Debug builds only; `ConflictSeedProbe.swift`)
exists to exercise the Conflict sheet against the mock server, which has no progress endpoints.
**Use it with test accounts only.** At launch it writes into the real local progress store of the
signed-in account: for every game whose automatic-resume reference has a single head with a parent,
it commits a sibling head on that reference, a copy of an earlier state checkpoint attributed to a
fixed other device id (`00000000-0000-4000-8000-00000000d0e0`) and dated up to an hour before the
current head. The commit is local only (never published), nothing is deleted, and the Conflict sheet
or the player's branch choice resolves it like a real conflict; the copied checkpoint then stays in
the game's history.

For screenshots of the Setup code sheet, `ROMMIO_SETUP_STUB=1` (Debug builds only) replaces the
one-touch pairing service with a fixed code that stays pending and makes no request, so captures
never contact the production setup service.

Captures and accessibility dumps attach to the XCTest result under `ios/build/player-ui-runner/Logs/Test`; on a simulator they are also written to `/tmp/rommio-ui-NAME.{png,txt}`. For visual review after rotation, use `xcrun simctl io UDID screenshot PATH`: Early XCTest app screenshots on this simulator had stale orientation/cropped content; the runner now uses the full-screen screenshot API. The native simulator screenshot is an independent orientation check. A successful runner exit establishes that requested controls were found and operated, not that game content was semantically correct; review screenshots and the game itself.

## Navigation pass and transition metrics

`ios/AppUITests/NavigationPassTests.swift` walks the page list against the signed-in account on a
simulator or device: every tab, Downloads, a platform and one of its games, a collection and one of
its games, Search and its best match, Stats, and each Settings sub-page, going back after each push.
Every stop is captured as an attachment. It is read-only (it plays, downloads and changes nothing)
and fails at the first step after which the app is no longer running. Three more tests measure
pushes and pops (Library › Platform, Platform › Game, Settings › Cores and BIOS) with
`XCTOSSignpostMetric.navigationTransitionMetric`.

```sh
# Simulator:
scripts/ios/run_navigation_pass.sh --simulator UDID --theme riso --scheme dark
# Device (the installed app, signed in; the runner is signed with your team):
scripts/ios/run_navigation_pass.sh --device UDID --theme aurora --scheme light --team TEAMID
# Only the pass, or only the metrics; the Search query and measure iterations:
scripts/ios/run_navigation_pass.sh --device UDID --theme riso --scheme light --team TEAMID --tests transitions --iterations 5
```

Theme and appearance reach the app as `-appearance.theme` / `-appearance.scheme` launch arguments
(from `rommio-ui-config.json`, embedded by `generate_player_ui_runner.rb --theme --scheme`, or
`TEST_RUNNER_ROMMIO_THEME` / `TEST_RUNNER_ROMMIO_SCHEME`). The script writes the result bundle, the
exported captures and a summary of the measured transitions to
`ios/build/navigation-pass/<theme>-<scheme>/` (`attachments/manifest.json` names each capture). A tab
tap ends an active search first (the keyboard covers the tab bar) and fails if the tab does not open,
so a stop is never captured on the wrong page.

On the Simulator every measured push took 0.50–0.53 s, before and after the 2026-09-24 stack work
alike: that is the push animation's own length, and a Mac-hosted Simulator does not show the phone's
cost. The numbers that matter come from the device run.

## Simulator enforces the device stack limit

Simulator builds of the app are linked with `-Wl,-stack_size,0x100000`
(`OTHER_LDFLAGS[sdk=iphonesimulator*]` in `scripts/ios/generate_xcodeproj.rb`), the 1 MB
main-thread stack an iPhone gives the app, instead of the Mac's 8 MB. A page whose view tree is too
deep for the phone now crashes in the Simulator (`EXC_BAD_ACCESS` in the view's body, like the
device's "Thread stack size exceeded") instead of only on the device. Device builds are unchanged.
Confirm with `otool -l ios/build/Debug-iphonesimulator/Rommio.app/Rommio | grep -A3 LC_MAIN`
(`stacksize 1048576`). `StackBudgetTests` (`swift test --package-path ios`) additionally measures
each page and sheet on the Mac and holds it to 256 KB.

Checked on 2026-09-24 (iPhone 17 Pro simulator, iOS 27.0): the app as it was before the stack work,
linked with this setting, crashed opening a Riso game page (`EXC_BAD_ACCESS`, `SIGSEGV` in
`RisoGameLayout`'s body), as the phone did; the current app passes the navigation pass with it in
both themes, light and dark.

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
| Closed (outer display) | Vertical tab bar and Back/Play on the trailing bar; Continue playing first with ▶ quick resume | Stacked screens, controls in a band below | Portrait: game on top, controls in a band below. Landscape: game centred between side rails, clusters low, Select · Menu · Start under the game |
| Open, landscape | Vertical tab bar; Play summary beside shelves; two-column game page | Stacked centre column, clusters in the bottom corners, utility row between them | Game centred between side rails (across the fold), clusters at about 0.7 of the height, Select · Menu · Start under the game |
| Open, portrait | Horizontal bars; Library sidebar overlays games | Upper screen enlarged above the fold, touch screen below it, clusters in the bottom corners beside it | Game full width at the top (may cross the flat fold), controls in a band beneath |
| Book or laptop pose | — | Same as open | Book: same as open landscape (no pad half). Laptop: the game stays above the bend |
| Pause → Controls or Saved progress (open) | — | Pane beside the paused game with live touch controls | Same |

Also confirm: a quick stylus tap registers on the touch screen; pausing shows
every action including Leave game without scrolling; unfolding or folding mid-game
keeps the session; audio is clean (the core's own sample rate, not 44.1 kHz).
The former software renderer ran Pokémon X at roughly 23–24 fps on the
simulator. Azahar Vulkan is now the default; a native 1,200-frame probe ran at
93.3 emulated fps with non-silent audio. Clean audio in the visible player
remains a separate check from layout and native-core throughput.

On every pose, no control may sit over a game screen, and the D-pad and face
clusters sit low where thumbs rest (the studio stage rules; `design/studio/tools/shoot.sh
shot-sysdemo-player-duo-open` and the `-player-portrait`, `-dual` and `-duo-closed`
variants show the reference wireframes). The book-pose pad half and the "Game and
controls side by side" setting were removed on 2026-09-23.

Beta caveats: `reservedRegions` reports no hinge in beta 1, so the DS layout falls
back to the display midline in open portrait, and iPhone Duo is recognised from a
phone window wider than any slab iPhone (466 pt closed, 669 pt open).
With the open device rotated to portrait, Device Hub delivers clicks as pointer
events that some buttons ignore; start a game from the closed display and unfold
mid-game instead. Layout rules are covered at these sizes by
`PlayerDuoStageLayoutTests`, and for every touch family's real control targets by
`PlayerLayoutParityTests.testDuoControlsNeverOverlapAGameScreenAndClustersSitLow`.
