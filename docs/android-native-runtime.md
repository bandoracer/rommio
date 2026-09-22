# Android native runtime isolation

Status: partial R07/R10 implementation. Native session isolation and checked capture are implemented in the locally built frontend. Coherent checkpoints, compatibility enforcement, and the full session coordinator remain open.

## Ownership and source

The app builds `:libretrodroid` from [vendored source](../third_party/libretrodroid/UPSTREAM.md), replacing the JitPack binary dependency. LibretroDroid, Oboe, and libretro-common each have an exact source revision. The source digest inventory and complete local patch accompany the module. Builds do not fetch native submodules. NDK `27.2.12479018` and CMake `3.22.1` are pinned.

This pins the frontend, not the downloaded emulator cores. [Approved core artifacts and persistence qualification](android-core-artifacts.md) remain separate gates. The application still owns one process-wide runtime through the [guarded GL command lane and private lifecycle](android-player-startup.md).

## Active behavior

- Initialization and teardown reset environment options, dirty-option state, controller descriptions, disk/hardware callbacks, directories, rotation, and VFS/microphone flags. Pixel format resets to libretro's default 0RGB1555. A later session starts from its own declarations.
- Core initialization, successful game load, and hardware-context initialization are tracked separately. Failed load still deinitializes the core; unloading occurs only after a successful game load. Repeated teardown is idempotent. Failed core symbol loading closes its library handle.
- GLRetroView invokes native cleanup even after an aborted startup. Its legacy synchronous helper completes its latch on exceptions; application calls continue using the bounded coroutine command lane.
- State capture checks readiness, size, and the actual serialization result. JNI owns and releases capture buffers after copying to Java. See [the save-failure contract](android-checked-capture.md).
- SRAM restore requires the exact runtime SRAM size and returns the actual result through JNI. A rejected initial restore fails startup before gameplay. Zero-byte SRAM is accepted only when the runtime also exposes no SRAM.
- The engine can read effective options under the same guarded command ownership. Connecting qualified descriptors to production playback remains required.
- The subsequent [checkpoint capture component](android-checkpoint-capture.md) reads SRAM/RTC/state/options in one GL callback and records effective options in its v2 descriptors. Production qualification and activation remain open.

## Verification

`NativeEnvironmentIsolationTest` uses the built-in synthetic core and actual Android player lifecycle. Four mandatory cases establish:

1. Options, controllers, and disk callbacks from one session are absent after teardown; a subsequent session uses its own defaults without fixture overrides masking leaked values.
2. A rejected game load records deinitialization without an invalid unload, clears its environment, and permits a later session.
3. Incompatible initial SRAM fails readiness and cleans up; an exact-size restore reaches readiness with matching bytes.
4. Repeated engine/native teardown invokes unload/deinitialize once and leaves the environment empty.

Together with three capture-failure cases, these tests need no downloaded core or server:

```bash
./gradlew connectedStandardDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.bandoracer.rommio.domain.player.CoreCaptureFailureTest,io.github.bandoracer.rommio.domain.player.NativeEnvironmentIsolationTest
```

The four optional FCEUmm startup/restore tests also passed with the documented supplied arm64 fixture. Full local validation passed 100 JVM cases per flavor, both lint tasks/debug builds, and 101 instrumented cases with two existing live-server skips and no failures/errors. All 11 native cases executed. Logs: `/tmp/rommio-runtime-native.log` and `/tmp/rommio-runtime-final.log`.

Release native compilation passed for all four ABIs, with ELF load alignment of at least 16 KiB. Both debug APKs contain the local frontend for all four ABIs and no obsolete `rommio_capture` helper. The 383-file source inventory and complete patch were verified against the pinned checkout. This is build and local emulator evidence; hosted CI, signed release installation, and four-ABI/device gameplay remain unqualified.

## Remaining gates and rollback

Software rendering is the tested native path. Hardware-context teardown still needs GL-context/lifecycle qualification. Coherent SRAM/state/file-backed snapshots, save-only fallback after state failure, restore safety checkpoints, final-save ownership, effective-option compatibility, and v2 activation remain open. This patch does not claim to resolve every upstream allocation/lifetime issue. Native hangs/crashes cannot be interrupted safely by coroutine cancellation.

No storage schema or serialized-save format changed in this batch. No personal saves, remote records, or iOS code changed. Rollback must preserve checked serialization and cleanup after aborted load: reverting to the unpatched binary dependency would restore the demonstrated failures. Retain this module or disable affected native operations until a qualified replacement exists; do not revert the existing local/cloud containment.
