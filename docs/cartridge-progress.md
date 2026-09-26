# Cartridge game saves and resume snapshots

The subsequent Sega/PlayStation/N64/PSP increment is tracked separately in [platform expansion](platform-expansion.md), including its distinct qualification limits.

Updated 2026-09-21. This extends the existing NES implementation under the
[save/sync specification](save-sync-spec.md). Game saves and resume snapshots
remain independent, equally important streams. A game save can cross platforms;
a resume snapshot requires the exact core build (its artifact digest before any
platform signing; see the save/sync specification §3.2), platform, architecture,
options, and firmware identity. An incompatible snapshot stays in history while
an explicit cold boot uses compatible game-save data.

## Normal builds and provenance

Normal Android and iOS app builds now include Gambatte, mGBA, Snes9x, and melonDS
DS alongside pinned FCEUmm. These are no longer simulator-only QA additions.
[`scripts/cores/pins.json`](../scripts/cores/pins.json) records exact upstream
commits. The adjacent patches expose a complete cartridge persistence descriptor
and a post-copy restoration hook. The build records source, patch, recipe,
platform/architecture and final binary digests. Both clients verify packaged
provenance and the loaded cartridge layout before activating the session owner.
Unknown layouts fail qualification, preserving existing progress.

Build prerequisites: Git, Python 3, Make, CMake (validated here with 4.4.3), Xcode for Apple targets, and Android NDK 27.2.12479018. `CMAKE` can select
an explicit executable. The first build clones the pinned sources and fetches
upstream CMake dependencies; it needs network access. `ROMMIO_CORE_SOURCE_CACHE`
can point at existing clones named `gambatte`, `mgba`, `snes9x`, and `melonds_ds`.
Each target gets a disposable checkout under ignored `build/cartridge-cores/`.
Cached results are accepted only when recipe inputs and final bytes match.
Cross-host bit-for-bit reproducibility has not been qualified.

- Android: `./gradlew assembleDebug`; all four Android ABIs are packaged as
  immutable assets with a manifest and full upstream license.
- iOS: `scripts/ios/build_simulator_app.sh`; Xcode's existing pinned-core phase
  also compiles, signs, packages and inventories the four cartridge cores.
- Host native fixtures: build each core with
  `python3 scripts/cores/build.py --core CORE --sdk macosx --arch "$(uname -m)"`,
  then set `ROMMIO_CARTRIDGE_FIXTURES` to a private JSON mapping `.gb`, `.gbc`,
  `.gba`, `.sfc`, `.nds` to local ROM paths when running `swift test --package-path ios`.
  ROMs are not stored in this repository.

The older iOS downloaded-resource manifest still describes its original two
resources. The final app's `CoreLicenses.json` and per-core provenance also
include the four source-built additions.

## Qualified persistence layouts

The extension returns `rommio-cart-v1:<core>:<system>:<SRAM bytes>:<RTC bytes>`.
Native capture holds one execution barrier across both memory regions and state.
Restore preflights every destination, applies state and then complete game-save
memory, invokes the core restoration hook, and checks the result. Hook failure
uses the existing session recovery/rollback path.

| System | Save layout | Scope |
| --- | --- | --- |
| GB/GBC, Gambatte | Cartridge RAM plus 24-byte little-endian MBC3 clock record when present | Standard ROM/MBC1/MBC2/MBC3/MBC5 cartridge types. HuC3, MBC7 EEPROM, camera, and other special boards excluded. |
| GBA, mGBA | Fixed 131080-byte record: 4-byte little-endian length, 4-byte save-type tag, up to 128 KiB save payload with padding; separate 24-byte RTC record | GBA SRAM/Flash/EEPROM; stable inventory even when save type is detected after startup. mGBA's GB mode is excluded. |
| SNES, Snes9x | Standard cartridge SRAM, zero through 128 KiB | Multi-cart, BS-X, S-RTC, SPC7110 RTC and MSU-1 excluded. |
| DS, melonDS DS | 4-byte little-endian cartridge length, 512 bytes through 8 MiB cartridge memory, and 2304 bytes of generated firmware WFC identity/settings | DS console mode with one retail cartridge and generated firmware. Imported firmware, DSi mode, homebrew, GBA slot accessories, error screens and missing save managers excluded. |

Gambatte's old base-time-only RTC export was incomplete; the patch persists the
halt time and clock registers too. mGBA installs the recorded save type before
restoring data. melonDS re-installs copied save bytes into the active cartridge
even after its first frame. Its generated firmware WFC bytes travel in the same save container, so the core no longer leaves an untracked WFC settings file on exit for this qualified layout. Unsupported firmware retains its original file-writing behavior. These are core-specific requirements, not generic
assumptions about libretro SRAM.

Existing valid core selections are retained; this qualification applies to the exact pinned bundled bytes. It is not an automatic migration of old downloaded cores or their states.

Old raw save files do not automatically become this new format. Existing
ownership and explicit recovery rules still apply, and originals remain retained.
This work does not qualify disc/card files, special peripherals, imported DS
firmware, every cartridge board, or unrestricted legacy-format interchange.

## Qualification and reproduction

Current component checks: 165 Swift package tests (two environment-dependent
skips), 118 Android unit tests, and a real Android arm64 native test covering all
five new cartridge cases pass. Another 22 existing Android packaged-core, native-startup, and production player-screen regression tests pass. Host native tests cover the same five cases with
marked memory and deterministic snapshot replay. Normal simulator packaging
passes strict deep code signing and final core digest checks; all 16 packaged
Android artifacts match their manifests. All eight 64-bit Android core binaries
have 16 KiB ELF load alignment. Other Android ABIs compile but have no runtime
qualification in this batch. Building a device core is not physical-device qualification. Physical
execution remains deferred at the user's request.

The opt-in `scripts/ios/verify_live_cartridge_sync.py` harness uses a dedicated
`codex-*` account, isolated iOS roots, and a `rommio-progress-test-*` Android
emulator. Install the normal debug app and test APK first, then provide server,
credentials JSON path, simulator UDID, Android serial, comma-separated RomM IDs,
and an output report path. It never stops the server or deletes cloud progress.
The iOS initial cloud check remains bounded; explicit Retry now waits for the application-owned sync job to complete so growing retained history cannot cause an endless three-second retry loop. Its offline phase injects a transport failure in the isolated debug app; it is
not a test of airplane mode or physical network loss. The fresh-root phase
requires the app to load its compatible snapshot, not merely download it.

The native fixture tests separately verify marked, non-default game data,
state replay, and a new-core game-save-only cold boot. Host snapshot tests compare
deterministic replay pixels and memory because some codecs normalize internal
cached fields when re-serializing a restored snapshot.


The live games are Galaga (NES), Pokémon Blue (GB), Pokémon Crystal (GBC), Fire
Emblem (GBA), Super Mario World (SNES), and Mario Kart DS. These checks use brief
native sessions and exact artifact/memory verification, not complete in-game
save-menu walkthroughs. Galaga has no battery SRAM; separate generated NES
battery fixtures provide the nonempty NES memory coverage. New cartridge host
tests deliberately mark non-default save bytes before round-trip and cold boot.
Android live checks run the real application session owner in an instrumentation
activity; they do not constitute a second complete Android UI tour.


## Live server results

All six cases passed against the actual upgraded RomM 5.2.0 server using the
separate QA account and the LAN route.

| Platform / test | NES | GB | GBC | GBA | SNES | DS |
| --- | --- | --- | --- | --- | --- | --- |
| iPhone Air simulator: publish both streams | Pass | Pass | Pass | Pass | Pass | Pass |
| iPhone Air simulator: external termination, injected-offline resume, reconnect | Pass | Pass | Pass | Pass | Pass | Pass |
| iPhone Air simulator: fresh-root cloud snapshot recovery | Pass | Pass | Pass | Pass | Pass | Pass |
| Android arm64 native owner: game-save receive, cold boot, successor publication | Pass | Pass | Pass | Pass | Pass | Pass |
| Android arm64 native owner: fresh-store cloud snapshot recovery | Pass | Pass | Pass | Pass | Pass | Pass |
| iOS receives Android game-save successor | Pass | Pass | Pass | Pass | Pass | Pass |
| Server retains both platform snapshot streams, artifact hashes verified | Pass | Pass | Pass | Pass | Pass | Pass |

Evidence: [GB/GBC/GBA/SNES live run](validation/live-cartridge-sync.json),
[DS/NES live run](validation/live-ds-nes-sync.json),
[fresh Android recovery](validation/android-cloud-resume.json),
[server artifact readback](validation/live-cloud-artifacts.json), and
[exact packaged core hashes and component checks](validation/cartridge-artifacts.json).

The first DS Android fresh-store test exceeded its 90-second fixture budget.
With a 240-second live-test budget, the complete receive/publish/fresh-download
sequence passed in about 102 seconds. Retained-history sync still needs speed
and retention work; this pass establishes correctness, not instant recovery.
The application launch check and the test budget are separate.
