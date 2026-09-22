# Android coherent checkpoint capture

Status: partial R07/R09/R10 implementation. The native capture and v2 commit component is implemented and tested together. The production player still uses the contained legacy save path. No v2 activation, cloud publication, or personal-save migration is included.

## Capture boundary

`LibretroPlayerEngine.captureMemorySnapshot` reads state, its verified persistent-memory inventory, and effective options in one callback on the guarded GL execution lane, under the engine operation mutex. No frontend emulation step or queued input/options command can run between these reads. Capture does not change the caller's paused/running policy. Buffers are copied out before the native callback finishes; storage work happens afterward.

The locally built frontend exposes checked persistent-memory reads for libretro SRAM and RTC, plus private battery-region IDs only after [exact bundled-core verification](android-pinned-core.md). Unsupported region IDs, an unavailable runtime, excessive allocation sizes, and a nonempty region with a null pointer fail explicitly. A declared zero-sized region returns empty bytes; whether that is a valid complete save is determined by the qualified format. State rejection is represented separately so valid save data can still be committed. A failed memory read rejects the entire capture even if serialization succeeded.

`CoreMemorySnapshot` is a raw inventory. It cannot establish that a core has no memory cards, mutable system files, or asynchronous persistence writers. File-backed and independently threaded cores need a separately verified flush/snapshot barrier. Merely exposing SRAM is insufficient to claim complete persistence.

## Qualified commit and identity

`SessionCheckpointWriter` accepts an explicit `MemoryOnlyPersistence` qualification for exact core bytes, ABI, save format, queried inventory, saved memory regions, empty-save semantics, and state format. No production core qualification registry is enabled in this batch. The synthetic test contract is not approval of an emulator core.

The captured core must match that qualification. The captured inventory must match exactly. Every nonempty persistent-memory region must be declared; unclassified RTC or extra cartridge banks cannot be silently dropped. A 256 MiB total memory budget matches the native restore limit. The captured account/content binding must equal the store identity. Prepared nonlegacy single-file sessions now carry their verified install binding; unassigned legacy sessions carry none and cannot enter this writer. Archive/member and ordered-disc bindings still require their own implementation.

The writer hashes/stages the complete bundle and encodes a validated v2 operation. State compatibility records actual effective options and the frozen firmware/core identities. The existing ProgressStore installs immutable blobs and commits heads, history, and outbox atomically. The writer reports success only after that commit returns. It does not modify legacy SRAM, state files, or metadata.

## Freshness and causality

- A successful state capture updates `save/current` and its matching resume reference in one operation. An explicit manual slot also targets that checkpoint in the same operation.
- If state capture fails, the complete save-data bundle still commits. The result reports `stateRejected`; the older resume becomes ineligible through the store's existing freshness rule. Its checkpoint remains available for explicit recovery.
- Each reference descends from the explicitly selected launch baseline and this writer's own successful commits. Captures do not read and adopt newly arrived heads. Incoming branches remain conflicts; they are not silently overwritten or merged.
- A baseline with multiple parents is rejected. Conflict resolution requires a separate explicit choice and resolution operation.
- Repeated unchanged automatic captures within this writer are coalesced. Explicit manual, safety, and restore captures create revisions even when their bytes match. Initial capture after a new writer starts is not yet deduplicated against its selected launch checkpoint.

## Failure and lifetime contract

Staging or validation failure leaves the previous checkpoint current. Once an operation is prepared for commit, a failed or canceled commit retains that exact payload and its staged sources in the writer. `retryCommit` replays those bytes idempotently; a new capture is rejected until the pending commit succeeds. This also handles cancellation after the database has already committed: retry acknowledges the existing operation without creating a sibling or consulting a changed core.

The writer itself does not own a session-lifetime coroutine scope, final-exit policy, startup selection, or restore. The subsequent [qualified memory coordinator](android-session-coordinator.md) supplies accepted-command ownership, safety restore, periodic capture, and final exit; production lifecycle/selection wiring remains open. A process restart must select durable store state with a new session and clean abandoned staging only after proving its owner is gone. Uncommitted captures are not promised to survive process death. Staging cleanup after a successful commit is best effort and cannot invalidate the durable checkpoint.

## Verification

Eleven `NativeCheckpointCaptureTest` cases exercise the actual engine, GL lane, synthetic SRAM/RTC core, filesystem, Room store, immutable history, and outbox. They cover:

- Coherent state/SRAM/RTC while gameplay is advancing, with exact core and effective-option metadata.
- Save-only fallback, stale-resume exclusion, retained historical state, and later successful state capture.
- Null memory buffers, excessive size, unexpected empty saves, undeclared RTC, and wrong core qualification.
- Every commit fault boundary, exact-byte retry, and actual coroutine cancellation after commit.
- Wrong account/content, staging write failure, unresolved launch branches, and preservation of an incoming unplayed head.
- Automatic deduplication and explicit manual-slot overwrite revisions.

Run the new native-to-store cases without a server or external core:

```bash
./gradlew connectedStandardDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.bandoracer.rommio.domain.player.NativeCheckpointCaptureTest
```

Full local validation passed 100 unit cases per flavor, both lint tasks/debug builds, and 112 device cases with two existing live-server skips and no failures/errors. All 22 capture/isolation/checkpoint/real-core startup cases ran, including the four supplied-fixture FCEUmm cases. Release frontend native compilation also passed for all four ABIs. Logs: `/tmp/rommio-checkpoint-final.log`, `/tmp/rommio-checkpoint-verified.log`. The latter contains the final expanded suite. CI now includes this test class; hosted execution is unverified.

A subsequent focused FCEUmm run also passed all four cases after extending the readiness case: three captures taken during active gameplay restored exactly the SRAM captured in the same callback, and the test ROM exposed no RTC region. Log: `/tmp/rommio-checkpoint-realcore.log`. This qualifies that capture boundary for the supplied artifact and synthetic NES program, not its complete production persistence inventory.

The native-to-store tests establish the component's behavior with a synthetic memory-only core. They do not qualify production persistence formats, file-backed save flushing, real RTC restore, process-kill/storage-exhaustion behavior, hardware-rendered cores, physical devices, or Swift parity. The [remediation plan](remediation-plan.md) keeps those gates open.

## Next integration and rollback

The qualified memory restore/coordinator component now exercises this writer end to end. Complete production core qualifications and file-backed adapters, then connect it through one application session owner that replaces all legacy capture/disposal paths. Recovery UI and explicit legacy migration must be ready before production activation. Keep the current legacy cloud writers disabled.

This batch changes no database schema or wire envelope format. Rolling back the unactivated writer and capture API together leaves existing progress untouched; retain the prior checked serialization and native isolation fixes. No release or remote save mutation occurred.
