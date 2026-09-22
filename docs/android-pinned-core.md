# Android bundled FCEUmm

Status: partial R07 implementation. FCEUmm is built from pinned source and shipped as APK assets for offline installation. This supplies reproducible local core bytes and installation provenance; full persistence qualification and v2 save activation remain open.

## Source and build contract

The [vendored source record](../third_party/fceumm/UPSTREAM.md) pins upstream revision `236ccdfc911e84c60fea6b9d0699c2d440a8de14`, records upstream/local hashes for 659 files, and includes the complete patch and original notices. The build refuses changed or unrecorded source and a mismatched NDK. Gradle's `buildPinnedCores` runs before app builds and includes generated assets in both package flavors. It does not download source or core binaries.

The recipe uses NDK `27.2.12479018`, Android API 26, upstream's RGB565/NTSC Android configuration, a frozen version string and source epoch, stripped release libraries, and 16 KiB linker alignment. HD packs are not enabled. Each generated manifest identifies the source revision, source inventory digest, build script digest, toolchain, extension ABI, and per-ABI library hash/length. The APK is the trusted manifest source; imported application data cannot redefine it.

To force all four libraries to rebuild:

```sh
python3 scripts/android/build_pinned_cores.py \
  --ndk "$ANDROID_HOME/ndk/27.2.12479018" \
  --output app/build/generated/pinned-core-assets --rebuild
```

A forced recompilation on the local macOS toolchain produced identical bytes in all four ABIs. These are observed build digests, not a claim that another host/toolchain produces identical bytes:

| ABI | Library SHA-256 |
| --- | --- |
| arm64-v8a | `6282859e88ffc4c7a8680e9c058613a9e0b04f944badd3cefc95b3bde89fb001` |
| armeabi-v7a | `413e760c4b47116be9efef08cc90be854cbe15837d2e2c79ad07b5f36a93a630` |
| x86 | `5ecc72d4d68533128015e9d71f1f3c7fa4a7db4197fff101b1ab25990ae75376` |
| x86_64 | `564a82d72cc190aceb9c49fc9367e8ee26a38bff42c6cb49870af78376fefac0` |

## Installation and update behavior

Readiness prefers the existing selected version, then an existing flat library, then the bundled manifest. Bundled readiness does not copy the library. Launch copies it to unique staging, verifies exact length/hash/ABI/exports, installs an immutable artifact, and atomically selects it. The install action uses the same bundle without HTTP. A valid existing selection is retained across APK updates; upgrading or repairing must not silently strand its states.

Corrupt bundled bytes, invalid metadata, or a changed expected selection fail without replacing a valid selection. Missing bundles for other runtimes retain the existing download path; those moving downloads are still unapproved unless an explicit pin is configured. `approvedPin` means exact trusted artifact provenance, not approval of every game's save layout or state compatibility.

## Persistence inventory and remaining gates

The source audit identified additional battery regions in `src/boards/n106.c` (mapper 19 IRAM) and `src/boards/coolgirl.c` (flash). Upstream's standard SRAM getter exposes only cartridge segment zero. The local core patch exposes segments 1–3 at memory IDs `0x100`, `0x200`, and `0x300`, declared as `rommio-fceumm-segments-v2`. No aggregation changes the standard SRAM file format. Serialization now propagates a failed memory-stream save instead of reporting success unconditionally.

The frontend now verifies the trusted bundle manifest and freshly hashes the selected library before granting the additional region inventory. This check runs off the UI/GL thread and is cached only for the current session. An imported approval flag or matching runtime name cannot grant it. Unknown core bytes retain the standard SRAM/RTC interface.

All declared regions, state, and options are captured in one GL callback. Additional nonempty banks use distinct `save-1.srm`, `save-2.srm`, and `save-3.srm` artifacts with the existing `sram` role. The explicit qualification records both the queried inventory and the saved nonempty regions; omission of a nonempty bank is rejected. The loader checks inventory, path, format, and exact loaded-region lengths before materializing data. State applies first, then one native operation preflights all memory destinations before copying any. The version-2 restore-completion hook then rebuilds Namco audio caches/callbacks, every region is read back, and the coordinator can roll back to the complete safety bundle. Total persistent memory is bounded to 256 MiB across capture/commit/restore.

**Production v2 saving remains inactive.** An effective-runtime registry now qualifies the specific layouts below; it does not approve arbitrary FCEUmm content. Native tests now qualify the adapter using synthetic mapper-zero and mapper-19 content. The mapper-19 program updates both 8 KiB WRAM and 128-byte Namco IRAM; tests verify complete durable restore, safety rollback after a rejected state, and save-only cold boot in a new native instance. Invalid final-bank lengths, duplicate/unknown native IDs, and mismatched array inventories leave existing memory unchanged. The cold oscillator regression also proves derived sound state is restored; see [launch selection and native completion](android-progress-launch.md). Other mapper layouts need explicit qualification before activation.

FDS disk saves, firmware-dependent content, mutable files, cartridge variants, effective options, and save/state compatibility need separate qualification. The tested mapper-zero and mapper-19 cartridges do not establish those additional formats. Core retention/selection UI, physical devices, other-ABI gameplay, signed artifacts, and hosted CI remain open. Cloud publication and the v2 coordinator remain inactive.

## Effective runtime qualification

The optional `retro_rommio_persistence_descriptor` export reports `fceumm-ines-v1:<header-version>:<effective-mapper>:<submapper>:<battery>:<PRG-nonvolatile-bytes>:<CHR-nonvolatile-bytes>` only while the active iNES cartridge loader owns the game. Values come from the core after header/database correction. Other loaders return no descriptor. The frontend bounds and validates the string; capture and restore compare it within the native command lane.

`FceummPersistenceRegistry` permits only exact trusted bundled builds, verified single raw NES content, mapper 0 or 19, and submapper zero. Battery-backed layouts require exactly 8 KiB SRAM, plus 128-byte extra battery RAM for mapper 19; all other declared regions must be empty. Battery-free layouts must expose no persistent bytes. NES 2.0 nonvolatile declarations exceeding covered PRG memory or declaring CHR memory are rejected. A recognized mapper with unexpected region sizes fails rather than committing a partial inventory.

Save compatibility includes effective header version, mapper, submapper, battery flag, and declared nonvolatile sizes. State compatibility additionally retains the exact core build, ABI, firmware, and effective options. Native tests cover corrected DiskDude headers, NES 2.0 declarations, uncovered CHR memory, proven empty saves, changed content, descriptor/region drift, and registry-to-planner-to-coordinator restore across two native launches. `prepare` rejects legacy-seeded or previously running instances. Unknown formats return an explicit unsupported result.

## Validation

`PackagedCoreArtifactsTest` covers every bundled ABI with the production validator, persistent identity, fresh offline launch/install, preservation of legacy selections, invalid metadata, truncated/oversized/corrupted assets, wrong ABI, changed launch expectation, and preservation of the prior selection. `PlayerNativeStartupTest` now runs thirteen mandatory native cases against the bundled current-ABI core unless a developer explicitly supplies another fixture. Tests use an original synthetic SRAM-counter ROM and isolated app storage.

Run the focused device checks with:

```sh
./gradlew connectedStandardDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.bandoracer.rommio.domain.player.PackagedCoreArtifactsTest,io.github.bandoracer.rommio.domain.player.PlayerNativeStartupTest
```

The original ten packaged-core/startup cases passed in `/tmp/rommio-packaged-focused.log`. The expanded qualification adds an artifact-gating case and two native battery-bank cases; all 38 focused installation/capture/coordinator/startup cases pass in `/tmp/rommio-battery-focused.log` on the isolated API 35 arm64 emulator. The native CI job now includes both classes and watches this source subtree. Local execution does not establish hosted results. Full-suite evidence is recorded in [remediation status](remediation-status.md).
