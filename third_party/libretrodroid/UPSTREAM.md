# LibretroDroid source pin and Rommio changes

This Android library replaces the binary JitPack dependency. Builds use the source in this directory and do not fetch native source or submodules. Original copyright headers and license files are retained.

| Source | Revision | Included material |
| --- | --- | --- |
| [LibretroDroid](https://github.com/Swordfish90/LibretroDroid/tree/0ebd299624bfd51a0a1336dd0a2c56fe7ddbc0e3) | `0ebd299624bfd51a0a1336dd0a2c56fe7ddbc0e3` (0.13.2) | Library `src/main`, license, consumer rules; Android build wiring replaced locally |
| [Oboe](https://github.com/google/oboe/tree/b15f5e39c01a7ada306d959e5129620b145fb8b4) | `b15f5e39c01a7ada306d959e5129620b145fb8b4` | `src`, `include`, CMake file, LICENSE, AUTHORS, CONTRIBUTORS, MODULE_LICENSE_APACHE2 |
| [libretro-common](https://github.com/libretro/libretro-common/tree/b0c348ea5543c4d7fb0bc479258aa6988b20c0c9) | `b0c348ea5543c4d7fb0bc479258aa6988b20c0c9` | All headers and the five C implementations named by LibretroDroid's CMake file |

The dependency revisions are the submodule commits in that LibretroDroid checkout, not branch heads. Examples, unrelated platform implementations, tests, generated binaries, and upstream build wrappers are omitted. `sources.json` records each included source file's original and vendored digest; `rommio.patch` is the complete diff of changed `src/main` files. `build.gradle.kts` pins the same Android/Kotlin/NDK/CMake toolchain as the application.

Local changes:

- Reset every Environment field, including options/dirty flag, controller descriptions, rotation, persistence directories, hardware/disk callbacks, and VFS/microphone flags, at initialization and teardown. Use libretro's default 0RGB1555 pixel format.
- Track core initialization, successful game load, and hardware-context initialization. Teardown handles partial load and repeated calls and clears runtime rendering/input/audio state. Failed core construction closes its loader handle.
- Run native cleanup even after GLRetroView has aborted. Its legacy synchronous command helper now releases the latch and propagates callback exceptions. The app continues to use its bounded coroutine command lane.
- Reject failed, zero-sized, or oversized state captures; preserve native return values for SRAM restore; require exact SRAM length and stop startup after a rejected initial restore.
- Release native capture buffers after copying to Java. Validate nonempty SRAM buffers and bound their allocation.
- Expose checked persistent-memory reads for both SRAM and RTC through JNI, for the app's single-lane coherent snapshot. These reads do not qualify file-backed persistence or restore ordering; see [checkpoint capture](../../docs/android-checkpoint-capture.md).
- Add checked SRAM/RTC and qualified extra-battery restore that preflights every region before copying any, rejects duplicate/unknown IDs and oversized total memory, guards state restore readiness/size, and releases JNI buffers on exceptions. Private IDs `0x100`–`0x300` require the app to verify the exact core artifact before invoking them. See [qualified restore and session ownership](../../docs/android-session-coordinator.md).
- Resolve the optional `retro_rommio_memory_restored` hook. Private battery-region restore requires it before any write and calls it after all copies so the qualified core can rebuild derived caches. Standard cores do not receive the call.
- Resolve the optional effective-cartridge descriptor and expose it through bounded, validated JNI reads under the core lock. The app compares it during qualified capture and restore; absence does not qualify arbitrary formats.
- Rebuild video resources under the core lock when a retained game receives a replacement EGL surface, without reloading content, reseeding SRAM, or adding a duplicate render lifecycle observer. Software FCEUmm reattachment is tested; hardware-context qualification remains separate.
- Include `<functional>` explicitly where required by the pinned NDK; remove the legacy manifest package attribute in favor of the Gradle namespace.

The checked serializer is now part of this runtime. The temporary separate `rommio_capture` JNI helper has been removed. Current limitations and tests are recorded in [native runtime isolation](../../docs/android-native-runtime.md). This source pin does not approve downloaded emulator cores or establish all core persistence capabilities.

When refreshing upstream, compare revisions, regenerate the complete source diff/digest inventory, and rerun the synthetic failure/isolation tests plus real-core qualification. Do not silently replace this module with an unpatched binary AAR.
