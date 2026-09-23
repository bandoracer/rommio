# iOS priority core delivery plan

Status: **implementation in progress; release gates not qualified**. Active scope: Nintendo 3DS (Azahar iOS
libretro candidate), Atari 2600 (Stella), Atari 7800
(ProSystem), and Atari Lynx (Handy). Dreamcast/NAOMI (Flycast) is deferred.
Keep the thirteen cores in current development builds; Azahar remains an
opt-in blocked debug candidate, and other new cores are deferred. This
plan extends the [iOS core matrix](core-matrix-ios.md) and does not change the behavioral
[save/sync specification](save-sync-spec.md).

## Delivery rule

Move a family through **source/build → embedded runtime → local play → local
progress → synced progress → signed distribution → device qualification**.
Record the result of each gate separately. A catalog entry, manifest row,
compiling binary, simulator launch, or passing unit test is not proof of a
shippable player.

Every supported persistent game-save artifact and every supported resume
snapshot is a separate progress stream. Neither can stand in for the other.
If a core/title has no in-game save facility, record an explicit empty or
unsupported game-save capability; do not invent save bytes or report a
game-save sync pass. Keep snapshot capture, sync, and restore fully functional.
If a target title depends on a game-save facility absent from the chosen core,
evaluate another core or leave that title outside the qualified scope. A
failure to capture a state must not discard valid game-save data.

## 0. Shared prerequisites

1. **Make the runtime inventory honest.** Keep `docs/runtime-matrix.json` and
   `scripts/ios/core-build-manifest.json` as catalog/declared-path inventories.
   Add a verified packaged-core inventory generated from the final app product;
   only verified entries may appear playable. Preserve the existing blocked
   reasons for 3DS until its gates pass. Keep Flycast blocked and out of the
   active build scope. Align Library/detail readiness, the player UI, and
   release docs with this inventory.
2. **Pin and build from source.** For each selected core, choose an upstream
   revision and license, add it to `scripts/cores/pins.json`, implement its
   build recipe and persistence extension in `scripts/cores/`, and record the
   source, patch, toolchain, final digest, architecture, and effective options.
   Verify exported libretro and Rommio persistence symbols. Never treat an
   unbuilt row in `scripts/ios/core-build-manifest.json` as a recipe.
3. **Fix iOS delivery before release.** Convert executable cores from the
   earlier `Cores/*.dylib` resource layout to embedded, independently signed
   iOS frameworks. Update package paths, loader/provenance checks, generated
   Xcode project and verification scripts together. Build and inspect a
   distribution-signed archive; verify every embedded framework and app
   signature. Do not download executable cores at runtime. This can progress
   alongside core integration but blocks release of each active core.
4. **Keep a reproducible evidence set.** Use legally available or generated
   test content, identify the exact core digest and ROM/content digest, and
   keep separate results for host tests, iOS simulator, iPhone/iPad devices,
   Android, real RomM sync, and signed distribution. Use the acceptance
   scenarios in `docs/save-sync-spec.md` and `docs/validation-matrix.md`.

Apple's [bundle rules](https://developer.apple.com/documentation/bundleresources/placing-content-in-a-bundle)
exclude standalone third-party iOS dynamic libraries, and
[App Review guideline 2.5.2](https://developer.apple.com/app-store/review/guidelines/)
bars downloading code that adds app functionality. These are packaging
constraints, not proof that any particular core will pass review.

## 1. Nintendo 3DS — Azahar integration

**First prove integration with Azahar's iOS libretro core.** The catalog still
names legacy Citra, whose [documented OpenGL 3.3 requirement](https://docs.libretro.com/library/citra/)
does not match the current iOS bridge. That limitation does not establish a
platform-wide 3DS barrier: Azahar [publishes an iOS arm64 libretro core](https://github.com/azahar-emu/azahar/releases)
and [disables JIT by default on iOS](https://github.com/azahar-emu/azahar/discussions/1811).
[Folium's App Store listing](https://apps.apple.com/us/app/folium/id6498623389)
also demonstrates App Store distribution of a 3DS emulator. Rommio has not
integrated or qualified either implementation.

Pin an Azahar source revision, dependencies and license; inspect the exact
iOS build's rendering API and implement the corresponding libretro host path.
Package it as a signed development framework. Boot legal test content on an
iPhone without JIT. Record five minutes of interactive audio/video, frame
rate, memory/thermal behavior, and whether shaders require runtime executable
generation. This is an integration/performance gate, not a claim that 3DS on
iOS is inherently impossible. If Azahar does not satisfy it, document the
specific failure and evaluate another maintained candidate.

After that integration gate passes:

- Update the catalog's runtime ID/name and packaged-core provenance to match
  the selected Azahar build; preserve the blocked status until gameplay and
  persistence checks pass.
- Define supported decrypted/encrypted content and user-supplied key paths.
  Bind title identity to the exact installed file set and effective keys.
- Inventory the complete per-title SD/save/extdata tree as the **game-save**
  artifact. Define a bounded, validated file bundle and flush/restore order.
  Keep shaders/caches out of progress unless they affect correctness. Capture
  a separate **resume snapshot** only if serialization/restore is reliable;
  snapshot identity includes core, renderer, options, keys/firmware and device
  platform. An incompatible snapshot must never block game-save cold boot.
- Implement dual-screen composition, lower-screen pointer/touch mapping,
  screen arrangement, gamepad controls, background/foreground recovery, and
  performance checks on both iPhone and iPad.
- Gate: signed device execution; local and RomM sync/readback of a real in-game
  save; separately qualified automatic/manual snapshot restore and sync; and
  dual-screen input. If the selected build fails a gate, keep 3DS blocked and
  record the measured failure and alternative-core decision.

## 2. Atari 2600 — Stella

- Pin and compile Stella for Apple simulator and device; package its license
  and provenance. Verify software-framebuffer video, audio, cold boot, reset,
  pause, background/foreground, and controller input.
- Replace the generic two-button assumption with tested 2600 controls:
  joystick/fire, Select, Reset, difficulty switches, and color/B&W where the
  core exposes them. Keep controls beside the video when space permits.
- Probe the pinned core's real game-save capability and all writable paths.
  Libretro currently documents Stella as supporting states but not saves;
  qualify empty game-save inventory explicitly unless the pinned source and a
  test cartridge establish a real portable save artifact. Qualify manual and
  automatic resume snapshots independently.
- Gate: local state survives process death and restores gameplay; signed
  simulator/device builds and a real RomM snapshot round trip pass. Any claim
  of synced game saves requires a distinct nonempty save fixture and cold-boot
  readback, not a restored state.

## 3. Atari 7800 — ProSystem

- Pin/build/package ProSystem with the same provenance checks. Test NTSC and
  PAL video timing, two-button controls, Select/Pause, and controller play.
- Determine whether the pinned core exposes cartridge/high-score persistence
  for any supported title. Libretro currently documents states but not saves;
  do not infer portable save support from a core-state slot. Declare exact
  supported/unsupported content and empty game-save inventory accordingly.
- Treat the optional 7800 BIOS as an effective runtime input when supplied;
  include its digest in snapshot compatibility. Missing optional BIOS must
  follow the pinned core's tested fallback, not a generic missing-BIOS error.
- Gate: deterministic state replay, process-death recovery, snapshot sync and
  real-device controls/video. A separate game-save cold-boot and sync test is
  required before claiming game-save support for any cartridge class.

## 4. Atari Lynx — Handy

- Pin/build/package Handy. Reconcile iOS firmware readiness with Android:
  the pinned Handy core has an internal HLE boot fallback when `lynxboot.img`
  is absent. Validate optional firmware identity, import path, core-visible
  location, fallback behavior, and snapshot binding.
- Test the Lynx A/B, Pause, Option 1/2 controls, screen rotation, touch and
  gamepad input; ensure rotation changes the viewport without clipping or
  shrinking it unnecessarily.
- Audit writable game data. The pinned Handy build and homebrew fixture expose
  EEPROM; treat those bytes as the game-save artifact and qualify cold readback
  separately from resume snapshots. Other cartridges may have an empty
  game-save inventory. Test snapshot restore after a fresh process and after
  firmware mismatch.
- Gate: firmware import/missing-firmware UX, local and synced snapshots,
  signed builds, and iPhone/iPad gameplay. Qualify EEPROM sync separately
  from snapshot sync.

The current upstream capability descriptions are [Stella](https://docs.libretro.com/library/stella/),
[ProSystem](https://docs.libretro.com/library/prosystem/), and
[Handy](https://docs.libretro.com/library/handy/). The exact pinned versions
and observed behavior take precedence over those descriptions.

## Evidence recorded 2026-09-22

- The simulator app product contains 13 independently signed core frameworks;
  `scripts/ios/verify_embedded_cores.py` verified their signatures, hashes,
  licenses and architecture. An Apple Distribution signing identity was not
  present, so a distribution archive has not been qualified.
- Stella, ProSystem and Handy build from pinned source for macOS, iOS simulator,
  iPhone arm64 and Android arm64. The generated ProSystem fixture, an upstream
  Stella test ROM, and a separately obtained Lynx homebrew release pass host
  cold-boot/save-inventory and snapshot replay tests. Stella and ProSystem have
  empty game-save inventory; the Lynx fixture exposes EEPROM.
- All three installed Atari simulator frameworks passed the native app probe:
  Stella and ProSystem reported empty game-save inventory, while Handy
  restored EEPROM bytes; each replayed a snapshot and restored in a fresh
  core. The Android arm64 packaged-core instrumentation passed separately for
  Stella, ProSystem, and Handy, including a Handy EEPROM cold readback. The
  Android test also found and fixed a clean-exit audit that had retained Handy
  EEPROM as an abandoned session. Real RomM sync, touch/controller play,
  iPhone/iPad devices and distribution signing remain open.
- Azahar 2126.1.2 is pinned as a blocked integration candidate. JIT-disabled
  software-rendering macOS, iOS-simulator and iPhone arm64 builds succeeded.
  The [MIT-licensed 3DStris 1.6.9 homebrew](https://github.com/3DStris/3DStris/releases/tag/v1.6.9)
  (SHA-256 `09ef94c1eb759bd4cca0c38f932389ea0b6f4aa40a2a97b8b73c924b3f697280`)
  ran in the iPhone Duo simulator's
  actual player. Touch input changed its language and caused the game itself
  to write `sdmc:/3ds/3dstris/config.mp`. The host and installed-app probes
  restored that file without a snapshot, then replayed a separate snapshot
  and cold-restored the file in a fresh core. The two-screen player viewport
  and lower-screen pointer mapping were observed in the installed app. An
  iPad Air 11-inch simulator pass exposed an incorrect synthetic hinge on
  every wide portrait display; the stage now limits that fallback to phones.
  Reinstalled iPad screenshots show adjacent 3DS screens with controls at
  the sides, without the unnecessary blank band. The iPad simulator also
  passed the local RomM publish, fresh snapshot peer, save-only cold peer,
  and separate remote-blob readback sequence.
- In the disposable local RomM 5.2 stack, the iPhone Duo player published a
  game-save checkpoint and a separate resume snapshot. RomM returned four
  owner-scoped objects: game blob, game operation, snapshot blob, and snapshot
  operation. A fresh app data root downloaded the pair, resumed the snapshot,
  and read the same game-written config bytes. This qualifies a **local-server
  simulator round trip for this homebrew**; the hosted RomM server, broader
  3DS titles, encrypted content and user keys are not qualified by it.
- The real RomM 5.2 library has eight 3DS titles, delivered as ZIP archives.
  The candidate now recognizes a 3DS ZIP, rejects ambiguous or unsafe members,
  verifies the extracted file, and records both the archive and member digests
  in the progress binding. A user-owned Pokémon X archive passed its ZIP CRC
  and the RomM SHA-1 for the 2 GiB member; the member rendered in the macOS
  host core and in the iPhone Duo simulator's embedded Azahar core. This is a
  real-title boot check, not conventional in-game save qualification. The
  iPhone Duo Debug app also downloaded the ZIP from that server's LAN origin,
  verified it, extracted the member into the progress session, and started
  the production SwiftUI player. A simulator screenshot showed both Pokémon X
  screens at its language-selection scene with on-screen controls. The
  reproducible real-server boot probe is `scripts/ios/verify_3ds_live_romm.py`.
  The Cloudflare route, an in-game Pokémon save, and remote progress sync are
  separate unqualified paths.
- Pokémon X produced non-silent audio in a 1,200-frame macOS host probe and in
  the installed iPad Air simulator app (745,280 audio frames, 23,464 nonzero
  samples after advancing past the title screen).
  ccleste and Ballistica homebrew produced no audio callbacks over that same
  interval, so those fixtures do not establish a general core-audio failure.
  Physical-device sound and longer play sessions remain unqualified.
- Azahar's current game-save bundle includes emulated NAND system data and
  game-generated log files as well as title data. Those bytes can change as
  frames run, so a whole-bundle comparison after snapshot replay is not a
  valid persistence assertion. The probe compares the game's save file and
  resumed video separately. Before promotion, narrow or otherwise classify
  the portable save inventory so system-data churn does not create false
  game-save changes. Test at least one title using conventional 3DS save or
  extdata, not only 3DStris's config.
- The initial five-minute JIT-disabled software-rendering 3DStris normal-mode
  run in the installed iPhone Duo simulator averaged **38.2 emulated fps** over
  11,464 frames. Profiling showed Azahar creating one software-rasterizer
  worker per host CPU (18 here), with heavy triangle-dispatch contention. The
  pinned candidate patch now caps those workers at four. The same automated
  five-minute run with the optimized core digest
  `745b36d266d8fbc590b02bb3a2f4112ce2738c0feb1d6ba4155cf33ebef0048a`
  completed **20,334 frames at 67.8 fps average**; the later sampled windows
  stayed around 55–58 fps. The simulator app process stayed near 676 MiB RSS.
  This clears the provisional 50 fps **simulator homebrew** gate. It does not
  predict physical iPhone thermals or qualify more demanding titles. 3DStris
  produced no audio, so audio remains untested.
- The full Swift package suite and Android standard debug unit suite pass.
  The final candidate-enabled simulator product contains 14 signed frameworks
  and passes `verify_embedded_cores.py`; `--distribution` correctly rejects
  its development/ad-hoc signature. The optimized Azahar iPhone arm64 artifact
  (`81167cc3b339b43930f70475758efc0b6cbd1646c301f74782d8b91fb57065ec`)
  and a full unsigned iPhoneOS app build with the same 14 candidate-enabled
  frameworks also succeed and verify all
  four target architectures as arm64. Device installation remains blocked:
  Xcode has no account or provisioning profile for Rommio and no Apple
  Distribution identity is present on this machine. Physical-device
  qualification is deferred at the user's request; simulator evidence alone
  does not promote 3DS or establish App Store readiness.

## Deferred: Dreamcast / NAOMI — Flycast

Keep the existing blocked catalog entry and its research notes. Do not pin,
build, package, or promote Flycast in this delivery cycle. Its reentry gate is
an explicit decision to resume after the 3DS integration outcome and the Atari
integration are understood. At that point, first prove JIT-free, App
Store-compatible device execution and the required hardware-render context;
Libretro's [iOS guide](https://docs.libretro.com/guides/install-ios/) cites
Flycast as enabled by JIT. A successful renderer alone is insufficient. If
feasible, plan multi-file disc/NAOMI identity, BIOS, VMU/NVRAM game-save files,
a separate resume snapshot, controls, sync, and device qualification using the
[Flycast core documentation](https://docs.libretro.com/library/flycast/).

## Integration and release order

1. Start the **Azahar iOS integration proof now**: pin its source and renderer,
   prove JIT-free boot and interactive device rendering, and record performance,
   licensing, packaging, and snapshot capability. Its result is an explicit
   integration decision, not a playable catalog change.
2. In parallel with that work, finish the shared packaged-core inventory and
   framework-signing path, then integrate Stella, ProSystem, and Handy one at a
   time. Promote each Atari independently after controls, firmware where needed,
   persistence, sync, and installed-app gates pass.
3. If the Azahar integration gate passes, complete its game-save file
   inventory, independent snapshot lane, dual-screen/player UI, RomM sync,
   signed archive, and iPhone/iPad qualification. If it fails, keep 3DS
   blocked with the measured reason
   and a documented alternative-core decision.
4. Record Android and iOS qualification separately for each promoted family.
   Test compatible game-save exchange with Android only after Android has the
   same measured format; preserve both platforms' snapshot streams even when
   cross-platform snapshot loading is incompatible.
5. Leave Dreamcast/NAOMI deferred. Revisit its feasibility only after an
   explicit scope decision; do not make it a dependency of the 3DS renderer or
   Atari delivery.
