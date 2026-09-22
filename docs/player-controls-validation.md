# Expanded player controls

The subsequent [controls prototype redesign](player-controls-prototype.md) covers
all 14 touch families, reserves space outside video, and replaces the old
overlays pictured in this earlier qualification record.

The seven expansion platforms have complete touch layouts on both clients:
Genesis/Mega Drive, Master System, Game Gear, Sega CD, PlayStation, Nintendo 64,
and PSP. Game saves and emulator snapshots remain separate under
[save-sync-spec.md](save-sync-spec.md).

## Control contract

[The shared contract](player-controls.json) generates both clients' control
profiles through `python3 scripts/cores/generate_player_controls.py`. Native
button numbers are libretro IDs; the generator translates them to Android key
codes. Mappings were checked against the pinned cores' input implementations.

| Platform | Controls |
| --- | --- |
| Genesis / Sega CD | D-pad, A/B/C, X/Y/Z, Mode, Start; six-button pad selected by default |
| Master System | D-pad, 1/2, console Pause |
| Game Gear | D-pad, 1/2, Start; 10:9 viewport |
| PlayStation | D-pad, four symbol buttons, L1/R1/L2/R2, two analog sticks, L3/R3, Select/Start, Analog toggle; DualShock selected by default |
| N64 | D-pad, analog stick, A/B, L/R/Z, Start, four independent C directions |
| PSP | D-pad, analog stick, four symbol buttons, L/R, Select/Start; 480:272 viewport |

N64 C buttons combine into right-stick axes without stealing A/B. Releasing one
C direction preserves another held direction. PlayStation's Analog button emits
PCSX-ReARMed's default L1+R1+Select toggle; the existing controller-type selector
can select a digital pad for games that require it. Touch layouts prefer landscape;
the overlay itself also handles portrait geometry, mirroring, scale, opacity,
console colors and hidden gameplay controls. Menu remains reachable.

Fixes made during validation:

- Corrected Genesis A/B and X/Y mappings and added missing Y/Z inputs.
- Split Master System and Game Gear away from the Genesis layout.
- Added N64/PSP gameplay touch support and updated support badges/runtime metadata.
- Corrected Android second-finger routing and release on cancellation/disposal.
- Neutralized held digital and analog inputs on pause in both native hosts;
  corrected the iOS motion D-pad's native IDs from 0–3 to 4–7.
- Removed overlaps between PSP's shoulder/D-pad and PlayStation's sticks/system row.
- Aligned the iOS native viewport and overlay to one full-window coordinate space.
- Kept iOS game saves and snapshots directly accessible through separate history tabs.
- Stopped Android lifecycle cancellation from appearing as a player error snackbar.

## Qualification, 2026-09-21

| Layer | iPhone / Apple | Android |
| --- | --- | --- |
| Build | iPhone Air simulator Debug app | Standard Debug app and instrumentation APK |
| Host/unit suite | 170 tests, 3 skipped, zero failures | 123 tests, zero failures |
| Native input | All 16 joypad IDs, both analog axes/sticks, D-pad motion, pause clearing through an original synthetic libretro core | All 16 Android-to-libretro IDs, both analog axes/sticks and pause clearing through an original synthetic core |
| Touch UI | iPhone Air, iOS 27 arm64: six layouts covering seven platform slugs; every button/axis, Analog combo, release/drag-out, mirroring, portrait/landscape, hidden-controls Menu | API 35 arm64 emulator: seven platform slugs; every button/axis/combo, release/cancel, simultaneous fingers, mirroring, hidden-controls Menu |
| Actual player + menus | Genesis, SMS, Game Gear, N64, PSP: launch, touch input, snapshot save/restore, separate game-save history access, reset and exit | Same five platforms: launch, touch input, snapshot save/restore and exit; existing NES reset/import/save-failure/reopen regressions also pass |

Android runs ten instrumentation test methods across `ExpansionControlsTest`,
`ConsoleNativeInputTest` and `PlayerScreenProgressTest`. iOS runs two opt-in
XCUITest methods, which loop over the layouts and five actual player fixtures.
These checks are independent; generated metadata and desktop tests alone are not
being used as proof of mobile parity.

The original Sega fixtures are simple generated programs; PSP uses the open-source
`cubevfpu` program; N64 uses the available Majora's Mask ROM. This is functional
control/menu qualification, not a broad retail-game compatibility or performance
benchmark. See [platform-expansion.md](platform-expansion.md) for previous native
persistence and live N64 sync qualification.

PlayStation's layout, full input path and default mapping are qualified here;
its actual player/BIOS-backed game remains untested in this pass because a verified
PlayStation BIOS was unavailable. The earlier HLE native fixture qualification is
separate. Sega CD shares the validated Genesis input profile but still lacks a
BIOS/disc gameplay fixture. Physical iPhone/Android performance, physical
controller connection/disconnection and iOS simultaneous-finger behavior still
need physical-device qualification. Live server sync was not rerun for this UI pass.

## Reproduce

- Generate controls: `python3 scripts/cores/generate_player_controls.py`.
- Apple unit/native tests: `swift test --package-path ios` after the documented
  pinned macOS core prerequisite; optional native cartridge fixtures use
  `ROMMIO_EXPANSION_FIXTURES` as described in the expansion notes.
- iPhone UI: install the Debug app, then
  `bash scripts/ios/validate_player_controls.sh SIMULATOR_UDID`. Requires Ruby's
  `xcodeproj` gem. The opt-in harness never accesses user/server progress.
- Actual iPhone player test: supply `/tmp/rommio-player-fixtures.json`, mapping
  the five platform slugs to disposable roots ending in `rommio-progress-fixture`.
  Each root contains `config.json` (`phase: controls-local`, local loopback base URL,
  ROM/file IDs, `fixtureFile`, `romMetadata`), matching RomM-format metadata and the
  local fixture ROM. Run `ExpansionControlsTests/testActualPlayerControlsAndMenus`.
  The DEBUG-only probe seeds an isolated local account/download, then uses the
  production player and progress owner. Remove the opt-in file afterward.
- Android: build `:app:assembleStandardDebug`, `:app:assembleStandardDebugAndroidTest`,
  and `:app:testStandardDebugUnitTest`; install both APKs on an unlocked, fully
  booted emulator. Run the three instrumentation classes named above. The five
  player tests use private `files/expansion-fixtures/fixture.{md,sms,gg,z64,prx}`.

Screenshots: [Air N64](validation/player-controls/iphone-air-n64.png),
[Air PSP](validation/player-controls/iphone-air-psp.png),
[Android N64](validation/player-controls/android-n64.png),
[Android PSP](validation/player-controls/android-psp.png).
