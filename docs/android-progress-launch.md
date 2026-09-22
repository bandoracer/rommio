# Android local progress launch selection

Status: partial R07/R09/R10 implementation. `ProgressLaunchPlanner` connects effective runtime qualification and frozen local reference selection to the tested session coordinator. Production Compose navigation, migration, broader core qualification, and cloud reconciliation remain unconnected; this component does not activate a second save writer.

## Selection contract

The caller supplies a verified account/content identity, an explicit persistence qualification for the exact loaded core, and a coherent snapshot from a fresh paused native instance. No CPU execution or legacy state/SRAM import may happen implicitly before selection. The [working-session API](android-working-sessions.md) now supplies isolated runtime inputs and retains interrupted files for explicit recovery. The [application owner](android-application-session.md) uses this path in native tests; the production player still needs its UI/migration cutover.

`ProgressStore.referenceSnapshot` reads all reference heads and their checksum-validated immutable manifests in one transaction. It intentionally does not require serialized-state blobs to be readable. The loader then proves in-game-save compatibility and complete artifact integrity independently from state health.

`prepare` requires an unseeded session and a fresh, paused core that has never run gameplay. It derives qualification through `FceummPersistenceRegistry` before resolving identity and selecting progress. The registry checks exact bundled core bytes, current ROM bytes, the effective native cartridge descriptor, and every persistent region's size. See [the qualified layouts and exclusions](android-pinned-core.md#effective-runtime-qualification).

The planner returns one of five results:

| Result | Required behavior |
| --- | --- |
| Ready | Start fresh, restore the selected matching bundle, or cold boot with current compatible save data; retain the frozen causal parents |
| ChooseBranch | Current-save or selected-resume branches conflict; automatic launch cannot pick a winner |
| OfferColdBoot | The state is incompatible or damaged, but the exact checkpoint's complete in-game-save data verified successfully |
| RecoveryRequired | Save data/format is unusable, legacy data needs explicit mapping, the requested branch is no longer available, or detached history exists without current progress |
| UnsupportedFormat | This exact core/content layout has no qualified persistence adapter; do not start the v2 save path or silently discard legacy progress |

No local progress plus no legacy data permits a fresh launch. Existing legacy data does not implicitly become a v2 checkpoint. A current-save tombstone is a deliberate fresh-start baseline; its operation remains the parent of subsequent progress and older resumes remain retained, never reselected automatically.

A save-only checkpoint cannot resume an older state. State selection checks the exact core/ABI/platform/format/options/firmware descriptor and the matching resume reference. A conflict in that resume reference blocks automatic loading even if `save/current` has one head. Explicit cold boot can proceed from the verified current save without resolving unrelated state branches. Damaged in-game saves never become an empty successful launch.

All ready messages say **Cloud progress not checked**. This component performs no remote read or publication and cannot claim synchronization. The bounded reconciliation and user choice required by the save/sync spec remain production integration work.

## Causal handoff and lifetime

Automatic selection freezes single reference heads as writer parents; conflicting references remain unacknowledged. Explicit branch continuation uses only the chosen operation's reference changes as its base, preserving competing branches. It does not create a merge operation or silently resolve a conflict.

Before creating the coordinator, `start` atomically rechecks the heads relevant to the selected save/resume and acquires an exclusive content-session reservation under the store barrier. Changed current progress requires a new selection. An unrelated manual-slot change does not block play or retarget the frozen parents. Remote changes arriving after handoff remain separate branches when the session commits.

The reservation protects the selected checkpoint from retention even after all heads move forward. It is independent of user recovery pins and holds a storage session lease that blocks import/replacement. Duplicate starts are refused. Normal exit, explicit abandonment, and failed startup release it only after awaited native teardown; canceled startup waiters also drain that teardown. A teardown failure retains the reservation until process recovery. A late release from an old session cannot release a replacement. The store cannot close while a reservation is active. Reservations are process lifetime ownership, not persistent user pins or a claim that uncommitted working memory survives process death.

The coordinator restores while paused. Only its caller's explicit `resume` enables gameplay. If the startup waiter is canceled, the factory retains ownership until the accepted start is followed by orderly close; it cannot orphan a live native instance. A rejected native state still stops that instance. Retrying cold boot requires preparing a fresh instance and choosing the offered save-only path.

## Native cold-restore completion

The FCEUmm extension is now `rommio-fceumm-segments-v2`. In addition to region access it exports `retro_rommio_memory_restored`. Private-region restore requires this hook before any memory write; after the checked copies, the hook refreshes mapper-19 register caches and installs its expansion-audio callbacks without resetting an already restored oscillator phase. Standard SRAM/RTC restores do not call this private API.

A new native test cold-restores Namco frequency/envelope registers without having the ROM rewrite them and checks that the serialized oscillator phase advances during gameplay. It failed when only raw bytes/caches were restored and passed after installing the required sound callbacks. This is stronger than byte readback alone. The core version string, manifest capability, digests, and full source patch reflect the new ABI. Older selected builds remain retained but do not acquire the new capability from their runtime name or approval flag.

## Verification and activation work

Eleven `ProgressLaunchPlannerTest` cases use Room, the filesystem, and the native synthetic engine. They cover fresh/legacy/history selection, matching resume, save-only freshness, damaged state versus damaged save data, option/firmware mismatch, explicit branch continuation, arrivals after handoff, stale current heads, unrelated slots, current deletion, selected-resume conflict, canceled startup ownership, and reservation lifetime through delayed native teardown. Two additional history cases verify active-baseline retention, independent user pins, stale/evicted selection rejection, storage replacement exclusion, and protection from late old-session release. The bundled FCEUmm suite includes the cold sound-cache regression. See [current check results](remediation-status.md).

Before production activation:

1. Expand the implemented effective-runtime descriptors beyond the explicitly qualified NROM/Namco layouts, including persistence files and restore callbacks. A ROM extension or header alone is insufficient: FCEUmm can correct a mapper through its internal content database.
2. Connect the application-owned workspace path to production migration/recovery UI; qualify legacy import, actual process death, and core-version recovery. Do not infer ownership or compatibility from a filename.
3. Connect the implemented application owner to UI for branch choice, cold recovery, save failure/retry, and exit. Replace all legacy capture/restore/reset/disposal paths together; a screen disposal must not race final commit.
4. Integrate bounded remote reconciliation and durable publication only after the shared transport and failure scenarios qualify. Preserve the existing disabled legacy cloud writer.

No database schema or wire envelope changed. No personal or remote progress was modified.
