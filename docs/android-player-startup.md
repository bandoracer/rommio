# Android player startup and native command ownership

Status: partial R10 implementation. The active Android player now prepares and selects local progress before attachment, awaits native readiness, and restores before enabling gameplay. The v2 ProgressStore remains inactive; coherent checkpoint capture, compatibility-aware restore, and the complete session coordinator remain required.

## Active behavior

`PlayerViewModel` freezes the installation, prepared core/firmware session, and initial launch target before exposing a playable viewport. Cached ROM observations can update display metadata but cannot end preparation or replace the launch baseline. Preparation runs asynchronously; the Compose view factory no longer uses `runBlocking` for hashing, storage, or core preparation.

Local resume selection runs both online and offline. An explicit selected state takes precedence. Catalog refresh starts after preparation and cannot unmount a prepared player on failure. This is the contained local-only path; bounded cloud selection and late cloud candidates still belong to R11/R12.

`LibretroPlayerEngine` initializes with emulation speed zero and audio disabled before attaching its surface. It waits for a rendered-frame callback, which can occur with zero gameplay steps. `PlayerViewport` applies the frozen selected state after this readiness signal. It reports readiness to the parent only after a successful restore; the parent's pause policy then enables gameplay. Failed native load or initial restore exposes an error and a return-to-library action, without capturing over the selected save. Gameplay input is gated while paused, starting, closing, or backgrounded.

The engine uses a private lifecycle owner rather than leaving the native view registered with the screen after removal. A process-wide lease reflects LibretroDroid's singleton JNI runtime: another game cannot replace a still-owned core. Detach invalidates queued commands, drains accepted file work, pauses the GL view, destroys the native lifecycle, and releases native/storage ownership. Cleanup has an engine-owned coroutine scope that survives Compose removal. It does not synthesize a final save on arbitrary disposal; that still requires the session coordinator.

`CoreCommandQueue` posts native work directly to the GL thread and returns results or exceptions through deferred values. State/SRAM calls use LibretroDroid's direct-on-current-thread mode inside that callback. Readiness and command waits are bounded; canceled queued commands cannot later mutate the core. Timeout or cancellation of an in-flight command makes the queue unusable. A stuck native function cannot be safely interrupted by Kotlin; a native deadlock/crash can still require process recovery.

Save/load/reset, variable updates, controller queries, and pause changes now share an engine mutex. Digital/motion/pointer callbacks use guarded GL dispatch instead of leaving input callbacks able to target a later runtime. Quick-save UI awaits metadata recording before displaying success. Explicit continuity capture awaits local journal/recovery bookkeeping before navigation; the expected cloud-disabled exception is distinguished from a local storage error. Native/file failures surface through player errors rather than uncaught button-action exceptions.

## Native runtime boundary

The original GLRetroView 0.13.2 emits surface creation separately from successful readiness. Its default synchronous command helper could leave its latch incomplete when the callback threw. The app adapter uses its own bounded command lane and observes both frame and error events. The subsequent [local runtime patch](android-native-runtime.md) also corrects the legacy helper and cleans up aborted startup.

The original native serializer ignored the core's `retro_serialize` return value. [Checked capture](android-checked-capture.md) now lives in the locally built runtime and tests explicit native rejection. Full persistence capability and checkpoint qualification remain required before v2 activation.

## Verification

Eight JVM cases cover delayed readiness, cold boot, rejected restore, canceled startup, native callback exceptions, queued cancellation, closure/late readiness, bounded waits, and exclusive native ownership.

Five opt-in Android tests execute the actual engine, GL surface, lifecycle, and FCEUmm core with an original synthetic NES program. The program increments a 24-bit SRAM counter, so assertions check gameplay position rather than only file existence:

- Readiness leaves the original SRAM unchanged; running advances it; restoring the initial state restores the exact SRAM bytes.
- Background capture preserves the paused counter; a malformed state rejects promptly and subsequent native commands remain available.
- Malformed ROM load produces a native failure rather than a ready player.
- A second active core is rejected; after disposal a replacement survives background/foreground without an old observer reviving or destroying it.

The native fixture is supplied separately, never downloaded by tests or bundled in release builds. Stage a reviewed ABI-compatible FCEUmm library at `<fixture directory>/native-player/fceumm_libretro_android.so`, then run from the repository root:

```bash
./gradlew -PnativePlayerFixtures=/absolute/path/to/fixtures \
  connectedStandardDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.bandoracer.rommio.domain.player.PlayerNativeStartupTest
```

Without the fixture the five native cases explicitly skip. The observed arm64 artifact used in this batch has SHA-256 `489d0dfc394c19b0557244039865ed8c58261d4dca3140e5f4d6a7fa6077aef4`; see [artifact provenance](android-core-artifacts.md). This is test evidence for those bytes, not an approved production pin. The empty activity host exists only in the debug source set. Test cleanup verifies native ownership is released before finishing the activity; it foregrounds the empty host to avoid ActivityScenario's slow background-helper cleanup.

The original startup batch passed: 100 unit cases per flavor, both lint tasks and debug builds, and 94 instrumented cases with two existing live-server skips. All four native cases executed with the supplied fixture. Log: `/tmp/rommio-startup-final.log`.

The later [qualified session coordinator](android-session-coordinator.md) adds a fifth real-core case for state-then-memory restore and supplies the tested memory restore, safety rollback, and final-commit component. Its production lifecycle/UI integration remains open.

## Remaining activation gates

R10 is still open for a full session coordinator that owns capture, manual restore/reset, periodic work, backgrounding, and final commit across screen cancellation. Separate legacy SRAM/state writes are not an atomic coherent checkpoint. Manual restore/reset still need safety capture and verified rollback after failure. Full persistence descriptors, file-backed formats, compatibility enforcement, v2 migration and recovery UI, process-death/disk-full/native-crash qualification, physical devices, and Swift parity remain required. Backgrounded initial launch also needs a lifecycle-aware readiness deadline instead of the current bounded failure.

No storage schema changes, personal save migration, remote publication, or release occurred in this batch. Roll back only the startup/engine adapter and its UI call sites together; keep legacy cloud publication disabled and retain all existing progress files.
