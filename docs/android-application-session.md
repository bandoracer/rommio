# Android application session ownership

Status: partial R10 implementation, now connected to the production Compose player for verified, account-bound FCEUmm NROM/Namco candidates. Runtime qualification remains authoritative. Other cores/layouts and unassigned legacy installs retain the legacy path; the old-player guard prevents it from ignoring existing v2 progress. This route now uses independent [schema-3 game-save and snapshot sync](progress-protocol-v3.md) on qualified RomM 5.2.0 servers; broader core/platform qualification remains open.

## One retained owner

`ApplicationProgressSessions` lazily acquires one application `ProgressStore`, prepares an isolated workspace, constructs an engine, and publishes the retained `ApplicationProgressSession` through `active`. A second preparation is rejected until the current owner releases native ownership. Accepted preparation survives cancellation of its UI waiter; the resulting session remains visible through `active` and can be attached or explicitly closed.

In the app container, the store lives at `noBackupFilesDir/progress-v2` and uses the existing Room database. A separately persisted UUID identifies the app installation. Constructing the container keeps store acquisition lazy. Application startup now schedules progress reconciliation; only qualified player sessions activate native capture. [Bundle format 3](progress-bundle-format.md) now transfers this root, its metadata, and interrupted captures. The old player remains gated from starting around v2 content identities outside the qualified route.

`ApplicationProgressStorage` owns the lazy store instance. Preparation and every store operation participate in the application storage barrier. Import maintenance drains accepted work, closes the idle store through its exclusive owner, and invalidates the instance before replacement. Subsequent access reopens the store; requests already waiting on the old library generation are canceled. Session lifetime leases prevent this closure while native work owns a workspace. See [coordinated import recovery](import-recovery.md#coordinated-progress-directory-recovery).

`RommRepository.buildProgressPlayerSession` prepares verified launch inputs without reading legacy SRAM into the core seed. The ViewModel inventories legacy files and supplies `legacyProgressPresent`. The screen offers explicit import of compatible SRAM or a selected state, preserving every original file. SRAM-only import requires the complete qualified persistence inventory to be SRAM; it refuses cartridges requiring additional memory. Imported checkpoints are pinned. An attempted native import must be closed and reopened before trying another choice if it fails. If the old state loads but reserialization is rejected, its complete in-game save is retained and the player requires a fresh cold launch rather than continuing with a partially migrated state. Unassigned legacy installs still need the Settings ownership-recovery flow.

## View and lifecycle contract

The owner creates the native view using application context and a retained lifecycle. A screen observes state and calls `attach` on Main; it does not own native destruction. Attachments have generations, so callbacks from an old Activity cannot pause or close a replacement screen. Two live screens cannot display the same owner concurrently.

Selection waits for the fresh paused core and the workspace-aware planner. Ready selection starts the coordinator paused. Branch choice, cold-boot offers, legacy recovery, and unsupported formats remain explicit outcomes. Gameplay starts only through the owner's `resume` command while a screen is foreground.

Commands for checkpoint, restore, reset, retry, and exit are serialized in an application-owned queue and delegated to the existing coordinator. Canceling a caller's deferred result does not cancel an accepted command. Local-save failure retains the owner and paused core for retry. Final exit commits before native teardown; only completed teardown clears the application `active` slot and releases the workspace.

On screen pause, the owner requests one background checkpoint and then pauses its native lifecycle. Foreground return resumes gameplay only if it was running before that background operation and saving succeeded. A manually paused or save-failed session stays paused. The qualified screen uses these commands instead of its old lifecycle and periodic writers. Foreground lifecycle changes also trigger initial resume, so navigation entering the player cannot leave a ready core paused.

Controlled navigation that temporarily removes the view must await `prepareViewRemoval`; it saves first, then removes the observer/view while retaining the core and workspace. Failed navigation leaves the visible screen able to retry and resume. Navigation that ends gameplay must instead await `finishExit`, or invoke explicit abandonment after a save failure. OS process death cannot be delayed by this API; it still relies on committed checkpoints and the interrupted-session recovery contract.

## Surface recreation

`GLSurfaceView` replaces its rendering thread after detachment. `RetainedViewCommands` keeps unclaimed native callbacks outside that thread's queue and resubmits them after attachment. An atomic claim prevents an old and replacement thread from executing the same callback twice. Closing the engine invalidates pending callbacks; already executing native work is drained by the existing engine teardown.

The source-pinned frontend now rebuilds display resources on a new EGL surface even when content is already loaded. It does not reload the ROM, reapply initial SRAM, or register another render lifecycle observer. The native rebuild takes the core lock. The tested qualification is software-rendered FCEUmm; hardware-core context teardown/reset and physical GPU behavior still require their own qualification.

## Evidence and activation work

The application-owner native tests cover durable exit and a fresh owned resume, canceled preparation waiters, duplicate launch rejection, legacy recovery gating, controlled view removal, activity recreation while running, commands issued while the surface is detached, background/foreground behavior, capture-failure retry, and cancellation while native teardown is delayed. PixelCopy checks resumed rendering around recreation using the default shader, alongside exact state/memory checks. Unit tests independently exercise old/new thread callback competition and shutdown invalidation.

These tests use the actual native engine, bundled FCEUmm, Room, and isolated original test content. `PlayerScreenProgressTest` additionally navigates into the real `PlayerScreen` through a Compose navigation host and the real repository/container. Its checks cover quick save, history restore, reset, final exit and fresh reopen, original-file-preserving import, and visible save failure/retry. A fresh test emulator must dismiss Android’s fullscreen tutorial before these touch interactions; CI sets `immersive_mode_confirmations` to `confirmed` on its disposable emulator. See [current validation results](remediation-status.md) for completed runs.

The qualified screen exposes branch selection, compatible save-only cold boot, interrupted-capture replay and confirmed discard, save failure/retry, and confirmed abandonment. Save slots, restore, reset, background saves, periodic saves, and final exit go through the owner. The screen never detaches the owned engine on composition disposal. New checkpoints are local-only; the UI explicitly states that cloud sync is unavailable.

Remaining work includes unassigned legacy ownership migration, other core/layout qualification, retained-backup and damaged-metadata repair, actual OS termination/storage exhaustion, physical devices, and cloud reconciliation. These remain open; passing the local player flow does not close M1/M2 or the full remediation list.
