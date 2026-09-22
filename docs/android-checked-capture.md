# Android checked native state capture

Status: the Android state-capture failure path is corrected. This is part of R07/R10, not completion of the checkpoint/session architecture or activation of v2 saves/sync.

## Contract

`LibretroPlayerEngine.saveState` and `saveResumeState` call the locally patched LibretroDroid serializer inside the guarded GL execution lane, while the process-wide native owner and operation mutex are held. Only a successful `retro_serialize` result returns bytes for installation. Failed, unsupported zero-sized, or excessive state capture occurs before atomic file replacement. Existing manual/resume files remain unchanged. In-game SRAM capture remains available after an ordinary state rejection, and state capture can be retried.

The serializer checks runtime readiness and size before allocating an initialized buffer, with a current upper bound of 256 MiB. A core requiring more needs an explicitly reviewed capability/budget change. JNI releases the native capture buffer after copying to Java. Per-core persistence qualification must still establish that a core reporting success produces a valid compatible state.

This corrects the unchecked upstream serializer in the [pinned local runtime](../third_party/libretrodroid/UPSTREAM.md). The temporary separate `CoreCaptureBridge`/`rommio_capture` JNI helper has been removed. Native environment cleanup, failed-load recovery, exact-size initial SRAM restore, and source/build provenance are described in [native runtime isolation](android-native-runtime.md).

## Build and regression tests

Native tools are pinned to NDK `27.2.12479018` and CMake `3.22.1`. Both Android flavors build the frontend for arm64-v8a, armeabi-v7a, x86, and x86_64. Debug builds include an original tiny libretro test core from `app/src/debug/cpp/persistence_test_core.c`; release native targets exclude it. It is not a catalog runtime and contains no commercial game data.

`CoreCaptureFailureTest` runs on the actual GL view/native bridge without an external core or server. Its three cases verify:

- False-after-write cannot replace either a manual slot or resume. SRAM capture remains usable and a later successful state capture works.
- Zero/oversized declarations cannot create a manual slot or overwrite existing resume bytes.
- A disposed engine cannot capture or overwrite a prior save.

Run these from the repository root on a booted emulator/device:

```bash
./gradlew connectedStandardDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.bandoracer.rommio.domain.player.CoreCaptureFailureTest
```

The mandatory environment-isolation tests and four supplied-fixture [FCEUmm startup tests](android-player-startup.md) also pass against the local runtime. Fixtures no longer supply default options to mask leakage between sessions. The Android validation workflow includes an API 35 x86_64 emulator job for the capture and isolation tests; hosted execution remains unverified.

The original helper batch passed 100 unit cases per flavor and 97 instrumented cases with two existing live-server skips (`/tmp/rommio-capture-final.log`). The replacement runtime passed the expanded 101-case instrumented suite and the same unit/lint/build gates; see [current evidence](android-native-runtime.md#verification).

## Remaining functional work

Coherent SRAM/state/file-backed snapshots, save-only fallback and resume freshness after state failure, restore safety checkpoints, final-save ownership, v2 activation, physical devices, and native crash/deadlock recovery remain open. A native core that crashes or never returns cannot be made safe merely by checking its Boolean result.

Rollback must keep state capture disabled or retain an implementation that checks the native result. Reverting to the unchecked serializer reintroduces false save success. No storage schema or save format changes are needed for this boundary fix.
