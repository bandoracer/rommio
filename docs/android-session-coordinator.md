# Android memory restore and session coordinator

Status: partial R07/R09/R10 implementation. The qualified memory restore and session-ownership components are implemented and tested against the actual native engine and v2 store. They are not connected to the production player. Production core qualifications, file-backed formats, launch/migration/recovery UI, and replacement of legacy screen-owned writers remain activation gates.

## Matching-bundle restore

`MemoryCheckpointLoader` accepts the explicit `STATE_THEN_MEMORY` ordering in a `MemoryOnlyPersistence` qualification. A capture-only qualification defaults to no restore permission. Before materializing files, the loader verifies account/content identity, save-format compatibility, exact memory-region inventory and lengths, native allocation budgets, and, for a state restore, exact core bytes, ABI, platform, state format, effective options, and firmware identities.

The store returns the immutable manifest and copies verified artifacts under the same store operation. An explicit save-only cold boot omits state artifacts from verification and copying, so a corrupt or incompatible serialized state cannot prevent recovery of otherwise valid in-game progress. The original state remains retained. The loader rechecks the selected artifact bytes, returns owned memory buffers, and removes its temporary working copy.

The engine requires a paused, identity-matched session. It preflights memory lengths, applies the state, writes both SRAM and RTC, and reads them back in one GL callback. The native memory writer validates both destinations before changing either one. A bad RTC length cannot partially replace SRAM. The state implementation's Boolean rejection is honored; its JNI array is released on success and failure.

Save-only restore is allowed only before gameplay has ever started in that engine. It cannot silently replace live battery memory underneath unrelated CPU state. A rejected startup restore stops the session and preserves its source checkpoint; choosing compatible save data requires a fresh cold-boot session. These rules do not qualify file-backed or asynchronous core persistence.

## Session ownership

`ProgressSessionCoordinator` accepts an already prepared native engine, a writer whose causal parents were frozen at launch, and the qualified loader. Its FIFO command queue and coroutine scope belong to the session, independently of a caller waiting for a result. Canceling a screen's wait does not cancel an accepted manual save or exit commit.

The component provides:

- Startup readiness and optional selected-checkpoint restore before entering a paused, resumable state.
- Manual slots, pause/background capture, and periodic capture at the default 30-second save-data and 120-second state targets. Only one periodic command can wait at a time; paused/failed/closed sessions do not keep generating periodic work.
- Explicit resume only from a usable paused state with no uncommitted checkpoint.
- Durable safety capture before manual restore or reset. If a restorable safety state is unavailable, the destructive operation is not attempted.
- A new explicit `restore` revision after a successful restore/reset. If this commit fails, the chosen working state remains paused until its exact operation commits.
- Final capture/commit before awaited native disposal. Failed exit retains the paused core for retry or the explicit `exitWithoutLatestProgress` action. There is no automatic abandon path.
- Native error observation and a terminal stopped state when continued execution is unsafe.

Automatic captures preserve whether the session was running; pause/background operations leave it paused. Capture rejection still permits a complete save-only commit through the [checkpoint writer](android-checkpoint-capture.md). The result distinguishes missing resume from failure to commit local save data.

## Rejected restore and recovery

The coordinator validates the target, commits a safety checkpoint, and materializes that safety bundle before calling the core. A core may modify memory and CPU state even when it returns false. On rejection or an exception, the coordinator restores the committed safety bundle while remaining paused. If that recovery is also rejected, it closes the native engine and retains the safety checkpoint for a new session. It never resumes the unknown working state.

An interrupted commit retries the exact captured operation. A failure before staging preserves the original capture request, including its manual slot and whether state was requested. A late successful commit cannot make an unrelated operation or newer capture appear acknowledged.

`PlayerEngine.close` now waits for teardown and ownership release. If native disposal itself throws, the process-wide native/storage lease remains held and a replacement core is refused until process recovery. A native hang still cannot be safely interrupted by coroutine cancellation.

## Verification

Fourteen new `ProgressSessionCoordinatorTest` cases use the actual GL/native engine, synthetic SRAM/RTC core, filesystem, Room, and v2 store. They verify matching state/memory restore after newer progress, a core that mutates before rejecting a state, successful safety rollback, terminal rollback failure, compatibility rejection, recovery with damaged state bytes, fresh save-only startup, safety-gated reset, atomic memory preflight, early layout rejection, canceled UI waiters, ordered final exit, save retries, and periodic/background behavior.

The real FCEUmm suite now has thirteen mandatory bundled-core cases. In addition to SRAM/state startup checks, a synthetic mapper-19 cartridge verifies both SRAM and extra battery RAM through durable restore, rejected-state safety rollback, and cold boot in a fresh native instance. Native preflight rejects bad late regions and duplicate/unknown IDs without changing earlier memory. These are exact-artifact/content checks, not approval of every mapper, real RTC, or file-backed format. See [the region adapter contract](android-pinned-core.md).

Run the new mandatory component cases without an external core or server:

```bash
./gradlew connectedStandardDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.bandoracer.rommio.domain.player.ProgressSessionCoordinatorTest
```

Current full validation and logs are recorded in [remediation status](remediation-status.md). The reusable native CI job includes the coordinator tests; hosted execution remains unverified. All test content is synthetic; no personal saves or remote progress were changed.

## Remaining activation work

The [local launch planner](android-progress-launch.md) now freezes local reference selection and hands it to this coordinator, including effective-runtime qualification, compatibility/cold-boot decisions, and startup cancellation cleanup. Its atomic session reservation protects the selected baseline from retention and blocks storage replacement until native teardown succeeds; it does not mutate user recovery pins. Neither component reconciles cloud heads, imports legacy progress, or wires UI navigation. Content qualification is limited to the registry's explicit cartridge layouts. The caller must freeze matching launch identity/parents, construct the correct qualified working session, and await exit before removing its native view. The current production Compose lifecycle still owns legacy capture/disposal and is unchanged by this component; view destruction and process death are not made safe simply by canceling fewer coroutines.

The [application owner](android-application-session.md) now retains the engine/workspace and handles view attachment in native tests. Activation requires connecting it to the production player and replacing those screen writers, periodic/background hooks bound to actual app lifecycle, restore/cold-boot/retry/abandon UI, exposure of the retained baseline in status, UI for the implemented [abandoned-working-session recovery](android-working-sessions.md), and complete migration/import coordination. Full core persistence inventories, hardware-context teardown, real RTC/file-backed qualification, abrupt process-kill/storage-exhaustion tests, physical devices, and Swift parity remain required.

No database schema or wire format changed. Rollback can remove the unactivated coordinator/loader and their APIs together while retaining the earlier checked capture and native isolation fixes. Keep the existing remote-writer containment in place.
