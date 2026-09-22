# Android core artifact installation and identity

Status: partial R07 implementation. Download validation, atomic selection, version retention, structural/ABI checks, and exact session identity are implemented. FCEUmm now has a [source-pinned APK bundle](android-pinned-core.md) with offline installation and mandatory native tests. Other production catalog downloads still use moving buildbot URLs without approved release pins. Full gameplay and save-state compatibility remain unqualified.

## Installation and selection

`LibretroCoreInstaller` downloads without account credentials into staging under the managed core directory. The existing verified-transfer primitive rejects HTTP errors, HTML login responses, partial responses, and truncated content. Core downloads also have bounded compressed/extracted sizes and check cancellation before publication.

A ZIP must contain exactly the descriptor's expected library entry. Duplicate names, traversal paths, unexpected additional shared libraries, wrong CRC/length, and unbounded entry sizes are rejected. The former fallback that accepted any `.so` is removed. If `CoreDownloadDescriptor.artifacts` supplies an approved per-ABI archive/library digest pair, both must match. Otherwise the resulting digest is recorded as observed, not approved.

`CoreBinary` checks ELF identity, little-endian class, shared-object type, process ABI/machine, program/section bounds, loadable segments, dynamic symbol/string tables, and the required libretro exports. The parser bounds allocation and symbol-name scanning. ABI choice follows the current app process bitness, rather than assuming the first architecture supported by a 64-bit device is suitable for every process.

Validated bytes are copied to a fresh path:

`cores/artifacts/<SHA-256>/<install UUID>/<library filename>`

After validation of that installed copy, an atomic JSON selection is written to `cores/selected/<runtime>-<ABI>.json`. An interrupted artifact installation leaves the previous selection intact. A repair uses a new destination, preserving old bytes even when an earlier same-hash path was modified. Old artifacts and original flat files remain available; retention and user-facing version selection are later work.

Ordinary repeated downloads reuse the selected valid core. A missing/corrupt selection can be repaired by downloading without overwriting old artifact files. Supplying a different approved catalog pin permits a new installation; compatibility and explicit update/rollback policy still need R07/R09/R10 before release qualification.

## Launch identity

Readiness uses the selected artifact or structurally validates an existing packaged/flat legacy core. It caches successful inspections by metadata for presentation, but launch always freshly verifies the actual bytes and expected identity. Same-size/same-timestamp mutation is rejected at launch.

`CoreResolver.prepareCore` is part of the resolver contract. It freezes a legacy core into versioned managed storage before native handoff and verifies that selection did not change between readiness and preparation. `PlayerSession` receives the immutable selected path and `CoreArtifactIdentity` (runtime, ABI, SHA-256, size, and provenance), alongside the firmware snapshot. Session preparation performs the filesystem work on the IO dispatcher under the storage barrier.

An observed digest only identifies bytes. It does not establish publisher authenticity, a reproducible build, native loadability, or compatibility with a previous serialized state. `approvedPin` records validation at installation; imported local metadata is not a replacement for a trusted release manifest. No application decision may treat that flag alone as proof of trusted provenance.

Existing state files still lack the complete producing descriptors required by the target spec. The new identity is a prerequisite for that migration and for the session coordinator; it does not close F14 or activate v2 saves/sync.

## Development verification

The normal unit suite includes structural parser, artifact transaction, HTTP, checksum, cancellation, and stale-content tests. `CoreLifecycleTest` uses Android-managed files to verify readiness, legacy freezing, original retention, wrong-ABI rejection, and launch-time tamper detection.

To run the production validator against externally staged core files, create a UTF-8 TSV with one record per line:

```text
arm64-v8a\t/absolute/path/to/core.so\toptional_expected_sha256
```

Use actual tab characters and omit the third column when no reviewed digest is available. Then run from the repository root:

```bash
./gradlew verifyCoreArtifacts -PcoreArtifactList=/absolute/path/to/artifacts.tsv
```

This task runs the standard unit gate and validates files without loading or executing native code. It does not fetch artifacts or publish a release. Unit and Android tests share synthetic ELF fixtures under `app/src/testShared/java`.

The Android asset helper now normalizes buildbot filenames such as `snes9x_libretro_android.so` to the catalog's `libsnes9x_libretro_android.so`. It stages a flat legacy bootstrap copy only. A previously selected versioned artifact remains selected; overwriting a flat path cannot silently change an existing session/core selection. Explicit version management is still required follow-up work.

A subsequent [player startup qualification](android-player-startup.md) exercises the recorded arm64 FCEUmm artifact through the actual native engine with a synthetic SRAM-counter ROM. Its four passing startup/lifecycle cases are narrower than general gameplay or cross-version save compatibility.

## Recorded upstream structural probe

FCEUmm artifacts fetched from the official [Android buildbot](https://buildbot.libretro.com/nightly/android/latest/) passed the same production validator in all four ABIs. These are observed nightly bytes, not approved production pins or immutable source URLs:

| ABI | Library SHA-256 |
| --- | --- |
| arm64-v8a | `489d0dfc394c19b0557244039865ed8c58261d4dca3140e5f4d6a7fa6077aef4` |
| armeabi-v7a | `0b71574852b03fca6ba887e5906c25009e79899af67ca607fcd0956af4e6662e` |
| x86_64 | `46416c339eaeb9c8589ac2e8bab50673c771aea48b37d46cb949278a20bb38e5` |
| x86 | `477d43244f520cea9c72a11a1935de99d81bb997c5d147e090967a07f0511122` |

Probe inputs, archive hashes, and source URLs are retained locally in `/tmp/rommio-core-probe/`; initial probe log is `/tmp/rommio-core-probe.log`. The final Android run repeats the probe in `/tmp/rommio-core-final.log`. No upstream binaries were added to the repository or published.

ELF parsing follows the [ELF header specification](https://gabi.xinuos.com/elf/02-eheader.html) and [dynamic symbol semantics](https://gabi.xinuos.com/elf/05-symtab.html); required API exports come from the [libretro API header](https://github.com/libretro/libretro-common/blob/master/include/libretro.h).

## Remaining R07 work

- Extend the source-pinned FCEUmm bundle to other supported cores with exact source/build/toolchain provenance. Download pin maps and moving URLs remain unfinished for those cores. Qualify every bundled core separately for persistence and signed distribution.
- Describe actual SRAM, RTC, memory-card/file-backed, serialization, options, and BIOS requirements for each qualified core.
- Persist producing descriptors with checkpoints and enforce automatic state compatibility; preserve older core/checkpoint combinations and provide explicit repair/update/rollback flows.
- Qualify native loading, representative gameplay, saves, state round trips, physical devices, package signatures, and the iOS contract. Structural ELF success is insufficient.
- Complete failed-publication orphan retention/cleanup and abrupt process-kill/disk-full testing. Current transaction tests inject exceptions and reopen stores.
