# App Remediation Plan

Status: **In progress.** See [implementation status and evidence](remediation-status.md); the full scope and acceptance gates below remain in force.

Created: 2026-09-17.

Planning review: 2026-09-18. This revision reconciles the execution order with the current integration gaps; it does not claim new runtime validation or close any finding.

This plan covers all 16 findings from the September 17 structural audit and implements the shared [save, sync, and session specification](save-sync-spec.md). Scope includes the Android and iOS clients, native player bridges, local storage, networking, migration, supporting scripts, tests, and release workflows. The save/sync specification defines the behavior; this document defines the work and its completion gates.

Execution covers both Android and iOS. Both platforms must satisfy the same game-save, resume-snapshot, recovery and sync behavior. Qualify platform-specific native behavior independently and verify shared-protocol interoperability; Android completion does not close iOS work.

The approach is incremental replacement of unsafe paths around the existing Compose/SwiftUI interfaces, Room/GRDB stores, and player integrations. Each change must establish an observable behavior and retire the path it replaces. Splitting large files is useful only where it establishes those ownership boundaries.

## Priority and delivery contract

The first priority is preserving progress and keeping credentials and work attached to the right account. The next is making local play dependable, then restoring cloud synchronization. Playback, firmware, and catalog defects remain required parts of the plan and can advance independently when their dependencies allow.

| Priority | Required outcome | Work |
| --- | --- | --- |
| P0 — Prevent loss and credential exposure | Failed saves/transfers preserve prior data; credentials remain scoped; legacy cloud writers stay disabled; migrations never guess ownership | R01–R06, with containment maintained until replacement paths qualify |
| P1 — Reliable local play | Verified runtime assets, coherent capture/restore, durable checkpoints, restart recovery, and truthful local status | R07–R10 and local R13 |
| P1 — Safe synchronization | Proven transport, immutable publication, durable retries, preserved conflicts/deletions, and accurate cloud status | R11–R13 |
| P1 — Complete advertised functionality | Working controller input, correct video, complete catalog refresh, and normal BIOS import | R08, R14–R16 |
| Release gate | Upgrade/recovery and signed artifacts qualify on supported devices; every finding has evidence | R17 |

Priority does not remove dependencies or make later functionality optional. Non-breaking usability and visual polish belong in the [separate backlog](usability-polish-backlog.md); misleading save status, inaccessible recovery, and unusable input are functional defects and stay here.

Deliver reviewable batches with the affected R/F IDs, behavior changed, migration impact, test evidence, remaining limits, and rollback procedure. Preserve existing uncommitted work. Do not activate a new storage or sync path just because its isolated tests pass, and do not treat planning as authorization to publish a release.

## 1. Starting evidence and limits

The audit reviewed commit `319d0ff426b4183e6e404cc9815fa5afeae2c29a`. At that point:

- Android debug builds passed for both package flavors, but unit tests did not compile and lint failed with 5 errors and 34 warnings.
- Swift package tests reported 64 executed, 2 skipped, and no failures. The documented iOS simulator build passed.
- An Android probe reproduced the queued-payload Moshi exception. Additional iOS probes reproduced repeated downloads of unchanged SRAM and resurrection of a deleted slot.
- Other findings were confirmed in source, with timing, controller, real-core, and device behavior still requiring qualification. Existing successful builds did not establish working save/sync behavior.

These are historical audit results, not new test runs for this plan. Reproduce and check in the relevant regression tests in R01. No live saves need to be modified to establish the baseline. Use synthetic data and an isolated server for destructive and concurrent-write tests.

## 2. Complete finding coverage

The IDs below preserve the original audit order. A finding closes only after all assigned work items and their relevant integration tests pass.

| Finding | Fault and affected scope | Work items |
| --- | --- | --- |
| F01 | Android captures into slot zero while resume reads a stale hidden file | R03, R09, R10 |
| F02 | Launch, native readiness, capture, backgrounding, and exit have competing owners; local files can be overwritten partially | R03, R09, R10 |
| F03 | Account/server/file/content identity is incomplete in paths, records, and workers | R05, R06, R09 |
| F04 | Incompatible hash comparisons, blind cloud overwrite, ignored conflicts, and acknowledgment of bytes never uploaded | R03, R11, R12, R13 |
| F05 | Refresh erases slot upload/delete intent; tombstones resurrect; manual saves skip normal sync; manifests precede blobs | R09, R12, R13 |
| F06 | Android queue serialization/retry recovery fails; iOS save and download work lacks durable replay | R01, R05, R09, R12 |
| F07 | iOS installs error responses; Android transfers overwrite final files before completion | R03, R05 |
| F08 | Asset requests and redirects can send server credentials to other origins | R02 |
| F09 | iOS detects controllers without routing their gameplay or hotkey input | R14 |
| F10 | iOS accepts RGB565 without rendering it and mishandles default 16-bit output | R15 |
| F11 | BIOS readiness and runtime directories disagree; normal import/provisioning is incomplete | R08 |
| F12 | Token refresh and cookie logout semantics differ from actual request behavior | R04, R13 |
| F13 | Platform/collection pagination, catalog deletion reconciliation, and iOS collection identity are incomplete | R16 |
| F14 | Floating/unverified core artifacts and missing state/content compatibility metadata | R07, R09, R10, R17 |
| F15 | Android import rollback restores files without restoring database/secrets/preferences and races workers | R06 |
| F16 | Broken tests/lint, weak release gates, stale artifact paths, and unsupported documentation claims | R01, R17 |

## 3. Delivery sequence

| Stage | Work | Exit gate |
| --- | --- | --- |
| A — Establish checks and contain immediate hazards | R01–R03; start R11 capability investigation early | Reproducible checks; credentials stay scoped; failed transfers preserve valid data; unsafe legacy cloud writes cannot erase progress |
| B — Establish trustworthy identities and runtime assets | R04–R08; finish R11 transport decision | Jobs/files are account-bound; migration is recoverable; core and BIOS identities are verified |
| C — Make local play durable | R09–R10, with the local portion of R13 | Offline capture/restore, process-death recovery, ordered lifecycle, and migration qualify on both clients |
| D — Make synchronization safe | R12 and the remaining R13 | Verified transport, persistent retries, two-device conflicts, manual slots, and deletions qualify |
| E — Complete independent playback and library repairs | R14–R16 can proceed alongside B–D once their dependencies are met | Controllers, video, BIOS-dependent play, and complete catalog refresh work under their own tests |
| F — Qualify and release | R17 | Every finding has evidence, both clients pass applicable scenarios, artifacts and support claims match tested behavior |

R11 starts early because server capabilities may change the implementation scope. Controller/rendering work does not depend on finished cloud sync. Catalog repair must not be placed on the save-delivery critical path.

The save critical path is **R01/R03 → R05/R06/R07 → R09 → R10 → R12/R13 → R17**, with R02/R04/R11 feeding remote transport and R08 qualifying BIOS-dependent systems. The explicit dependency lists below govern implementation; overlapping work means independent changes, not concurrent writers to the same storage format.

### iOS parity gates

The qualified Android and iOS routes now implement native capture/restore, retained player ownership, independent game/state synchronization, and recovery controls. The real app interoperability and offline restart checks pass; [Apple qualification evidence](ios-progress-player.md) records the exact boundary. The subsequent iOS backup/recovery batch implements progress export/import, interrupted activation/rollback, deterministic schema-2 conversion and repair/archive controls; see [its evidence](ios-progress-backup.md). These broader gates remain the completion contract. Physical lifecycle qualification is explicitly deferred by the user. Shared types, transport code, and host tests alone do not satisfy them.

| Deliverable | iOS work and completion evidence |
| --- | --- |
| 1. Durable recovery and migration | Account/content-scoped immutable game saves and snapshots; exact staged-capture retry; retained originals and explicit legacy ownership; export/import and interrupted activation; reachable repair, history, pins, and orphan recovery. Exercise database reopen, process termination, and failed writes. |
| 2. One retained player owner | Route launch, periodic capture, pause, background, manual snapshots, confirmed restore, reset, and exit through one ordered owner. Capture all qualified memory regions coherently; commit game data even when state serialization fails. Verify safety capture and rollback before destructive restore. Leaving the screen cannot cancel an accepted save. |
| 3. Qualified content and runtime identity | Bind installs and jobs to their verified account, server, ROM bytes, and file set. Package and validate pinned Apple core artifacts, effective persistence layouts, firmware, and options. Qualify the same supported game-save layouts as Android. Reject incompatible snapshots explicitly while allowing compatible game saves. |
| 4. Independent verified sync | Activate both schema-3 lanes through account-bound RomM transport. Resume durable work on launch, foreground, reconnect, and available iOS background time. Verify interrupted transfers, lost responses, concurrent branches, explicit resolution, deletions, and no mutation of running sessions. Test real iOS-to-Android and Android-to-iOS game-save exchange; test snapshot exchange only within an explicitly qualified compatibility class. |

Completion requires iOS simulator/app evidence plus physical-device lifecycle and signing qualification where applicable. Cross-platform snapshot portability is not inferred from identical ROMs or core names: the current compatibility key includes exact core bytes, platform, architecture, firmware, and options. Both platforms must sync and preserve snapshots even when a receiving runtime cannot load one.

Keep the wider R01–R17 work open where its acceptance criteria remain unmet. Firmware, controller, rendering, and catalog work may advance independently. The new iOS writer is enabled only for the pinned qualified route where native capture/restore, retained ownership, and recovery work together; unsafe legacy cloud writers remain contained.

### Save/state distinction: required corrections

The September 18 [spec clarification](save-sync-spec.md#4-progress-model) is part of the existing R09/R10/R12/R13 scope:

- **R09/R10:** A failed state capture/storage attempt must preserve any successfully committed game-save data, leave the old manual slot intact, and report the outcomes separately. State-only imports remain recovery items until an isolated qualified recovery establishes their complete matching game-save bundle.
- **R10/R13:** Label game-save data, manual states, and automatic resume distinctly. Before loading a state whose paired game-save data differs from the active revision, explain the replacement, require confirmation, and preserve current game-save data plus a restorable safety checkpoint. Cancellation or preservation failure must prevent mutation.
- **R09/R12:** Schema 3 now separates game-save and state operations with an explicit pairing dependency; schema-2 envelopes remain unchanged. Preserve this independence in future adapters and client ports; see [the current protocol](progress-protocol-v3.md).
- **Validation:** S20 and S33–S39 in the spec cover rollback confirmation, partial success, independent publication, incompatible/missing state bytes, incomplete imports, actual game-save semantics, and per-type conflict comparison. These are acceptance requirements, not passing-test claims.

### Milestone checklist

These are behavior gates, not estimates or declarations that existing partial work is complete. Keep each gate open until evidence is attached in the status document.

**M1 — Reliable local play**

- [ ] R01–R04 containment and authentication checks pass for all request consumers, including browser handoff and late callbacks after profile changes.
- [ ] R05/R06 give installs, jobs, and progress account/content-scoped identities and a recoverable migration. Two accounts with identical ROM/file IDs can coexist; rejecting a collision is interim containment, not the final behavior.
- [ ] R07/R08 qualify core persistence descriptors and firmware paths for the representative systems used in gameplay tests.
- [ ] R09/R10 and local R13 preserve coherent progress across offline launch, repeated resume, manual save/load, backgrounding, process death, failed restore, and disk exhaustion. Recovery is reachable through the app.
- [ ] One coordinator owns capture/restore, one storage implementation owns progress writes, and local-save status reflects the committed checkpoint. Cloud sync remains explicitly pending/unavailable until M2.

**M2 — Safe cross-device progress**

- [ ] R11 freezes the proven transport and Android/iOS wire fixtures, including remote names, inventory traversal, and ambiguous-success readback.
- [ ] R12/R13 publish immutable operations, resume persisted work for its original account, and preserve concurrent branches and edit-versus-delete conflicts.
- [ ] Android-to-Android, iOS-to-iOS, and Android-to-iOS tests pass for qualified persistence formats. Incompatible serialized states remain recoverable without being automatically loaded.
- [ ] Every S01–S32 scenario has an owning test or a documented capability-based exclusion; local durability and remote acknowledgment are independently observable.

**M3 — Complete app qualification**

- [ ] R14–R16 pass controller, rendering, firmware-dependent gameplay, complete catalog, and offline-cache acceptance on the advertised platforms.
- [ ] R17 qualifies clean installs, legacy upgrades, recovery/rollback, physical devices, and signed artifacts. Hosted checks reject invalid releases.
- [ ] Every F01–F16 finding has linked implementation and acceptance evidence; documentation and support claims match those results.

### Immediate storage and migration batch

Android now has durable download ownership, journaled bundle recovery, account-scoped install keys, and content-scoped progress for new single-file downloads. [The storage boundary](account-content-storage.md) describes the verified portion and its limits. [Explicit legacy-progress recovery](legacy-progress-recovery.md) also implements inventory and journaled copying of selected SRAM/states to a verified destination. The checklist below remains the full batch gate; descriptors, retained generations, repair paths, remaining platform work, and failure/device qualification are incomplete.

1. Inventory legacy SRAM, slot zero, hidden resume, manual states, recovery copies, and pending intent with their available provenance. Preserve ambiguous and differing copies; discovery must not assign them to the active account.
2. Add an explicit ownership/content mapping and recovery flow. Record the user's selection, source provenance, destination binding, and retained original before activation. Include account reattachment and access to prior content generations.
3. Extend the import journal to apply that mapping consistently across files, database rows, and queued work. Keep active sessions and background writers quiescent; demonstrate idempotent recovery after abrupt termination at each boundary and after insufficient storage.
4. Complete archive/member and ordered-disc descriptors before enabling scoped progress for those launch paths. Carry the same identity and migration contract into the remaining platform work without claiming current parity.
5. Verify legacy fixtures, colliding accounts, changed content, canceled attempts, restart recovery, and format-1/format-2 transfer. Keep existing unit, Room/HTTP, lint, and build checks green. Record evidence and unresolved device limits in the status document.

This batch ends when legacy progress can be selected and recovered without guessing ownership or losing the original. It does not activate the v2 ProgressStore or cloud publication; those remain gated by R07–R13.

### Runtime qualification before local-store activation

Build on the existing Android core and firmware stores in this order:

1. Establish a durable approved core artifact set with source revision, build recipe/toolchain, ABI, archive digest, and library digest. Verify availability and clean installation independently of the moving nightly location. Recording a downloaded hash is not approval or durable hosting.
2. Define each qualified core's full persistence inventory: SRAM, RTC, memory cards, mutable system files, serialized states, relevant options, and firmware identities. Preserve unclassified legacy files for explicit recovery. A core without reliable serialization must still support qualified in-game saving.
3. Exercise native load, in-game save/reload, coherent state round trips, and incompatible-state fallback using legally available test content. Test the actual provisioned system and save paths. Structural ELF checks alone cannot satisfy these gates.
4. Persist the resulting compatibility descriptors with new checkpoints through R09/R10. Retain older core/checkpoint combinations and provide repair or rollback choices before exposing updates that could strand progress.

Evidence must distinguish structural validation, native loading, gameplay, and save compatibility. Android now bundles [source-pinned FCEUmm](android-pinned-core.md) for all four ABIs and runs mandatory native SRAM/state checks on the current emulator ABI. This completes the offline artifact-delivery portion for that core only. Its extra cartridge battery segments now have an exact-build-gated frontend adapter with mapper-19 durable restore, rollback, and cold-boot tests. Effective NROM/Namco descriptors and local selection now have native coverage, but production integration and broader content qualification remain required before activation; FDS, other cores, firmware, physical devices, and signed qualification remain separate gates. Publication of a release or artifact set is a separate action from preparing and validating it.

The [local launch planner](android-progress-launch.md) now connects frozen reference selection to coordinator startup, preserving conflicts and independently recoverable save data. Effective runtime qualification now feeds the planner for explicit NROM/Namco layouts, and a content-session reservation protects the selected baseline through teardown. Isolated working-session preparation and interrupted-capture recovery now have [store/native coverage](android-working-sessions.md). The [application owner](android-application-session.md) now retains the workspace/engine through view recreation and awaited teardown in native tests. Broader qualification, migration, and replacing production UI/lifecycle writers remain required before activation.

### Save activation gates after native runtime repair

The [native runtime batch](android-native-runtime.md) establishes checked serialization, clean sequential sessions, cleanup after failed load, and rejection of incompatible initial SRAM. It does not qualify the complete save system. Advance the local-save critical path through these explicit gates:

| Gate | Next implementation | Required evidence |
| --- | --- | --- |
| Persistence inventory (R07/R08) | Define each qualified core's SRAM, RTC, file-backed saves, effective options, firmware, and state compatibility; retain approved core versions | Exact runtime bytes/options/paths match descriptors; unsupported serialization still permits qualified in-game saving |
| Coherent capture (R09/R10) | Build on the tested [memory capture-to-store component](android-checkpoint-capture.md); qualify production formats, add file-backed flush/snapshot adapters, and expose local durability only after commit | Failures at each capture/commit boundary retain the prior complete checkpoint; state rejection cannot leave a stale resume masking newer in-game progress |
| Restore and session ownership (R10) | Integrate the tested [qualified memory coordinator](android-session-coordinator.md) as the sole application session owner; complete file-backed adapters, production qualification, and actual lifecycle/UI wiring | Failed restore can recover; background/exit cancellation cannot dispose the runtime before an accepted final commit; replacement sessions receive no late callbacks |
| Local activation and recovery (R06/R09/R13) | Migrate explicitly owned progress and connect the qualified store and recovery UI; retire duplicate legacy writers | Offline restart, interrupted migration, process death, and storage exhaustion preserve recoverable progress; no silent ownership guessing |
| Remote activation (R11/R12/R13) | Freeze wire names/inventory, implement durable publication and reconciliation, and expose conflicts/status | Two-device tests prove immutable publication, ambiguous-success recovery, causal conflicts, and edit/delete preservation before cloud writes resume |

The first four gates define M1's remaining save work; the final gate defines M2. Keep hardware-context teardown, effective-option compatibility, and native crash/hang recovery as functional qualification items. Native source pins and green synthetic tests alone do not close them. Record non-breaking visual refinements separately without delaying these gates. The capture-to-store and memory restore/coordinator components satisfy their native boundary tests. Production qualification and one application-owned lifecycle still must replace the legacy screen writers before activation; the existing components do not close those gates.

### Concrete Android execution packets

These packets refine the R-items into the next reviewable changes. Finish an affected feature's prerequisites before activation; independent authentication, catalog, and firmware repairs can proceed alongside the save work. Existing components should be integrated and completed rather than replaced wholesale.

| Order | Packet and owning items | Deliverable | Completion evidence |
| --- | --- | --- | --- |
| 1 | Finish the current qualification boundary — R01/R07 | Review the effective-runtime descriptor registry and fresh, paused preparation path; define the exact supported mapper/layout/core set and preserve an explicit unsupported outcome. Update source provenance and contract documentation together. | Descriptor correction, changed-content rejection, unexpected-region rejection, complete empty saves, and two-launch restore pass; applicable unit/lint/build/native checks pass. A narrow cartridge qualification does not approve FDS, file-backed saves, other cores, or physical hardware. |
| 2 | Establish application session ownership — R09/R10 | Add one application-owned store/session service, isolated working directories, a lease and retained baseline for the active session, and restart cleanup. Compose observes that owner and submits commands. Preparation must not seed legacy SRAM before selection. | Navigation, configuration changes, canceled UI waiters, backgrounding, and replacement launches cannot create two writers or dispose the core before an accepted commit finishes. Cleanup cannot remove an active session's baseline or working data. |
| 3 | Complete migration and recovery before cutover — R05/R06/R09/R13 | Inventory and explicitly map legacy progress; retain original SRAM, resume, slot zero, manual states, and pending intent. Extend transfer/import/export to preserve v2 checkpoints, history, and ownership. Provide recovery choices for damaged data and incompatible states. | Restart at each migration boundary and insufficient-storage tests preserve recoverable originals and consistent metadata. Export/import round trips preserve v2 progress. Ambiguous ownership requires selection; it never silently starts a new game. |
| 4 | Activate dependable local play — R10/local R13 | Route launch, periodic capture, pause/background, manual save/load, reset, and exit through the owner. Connect branch choice, cold boot, recovery history, and save-failure retry. Retire the corresponding legacy screen/ViewModel writers in the same cutover. | App-level offline save/exit/reopen, manual restore, background/foreground, process death, failed restore, and disk-full scenarios preserve the expected gameplay position and matching save data. “Saved locally” follows commit; failed exit remains recoverable. M1 closes only after its remaining identity/auth/runtime gates pass. |
| 5 | Complete remaining identity and runtime coverage — R04–R08 | Close request/browser/profile mutation races; retain changed-content generations and account reattachment; define archive/disc identities; qualify advertised core save formats and firmware paths. Add file-backed flush/snapshot adapters where required. | Account switching cannot retarget work. Content replacement cannot silently reuse incompatible progress. Each supported format has save/reload and compatibility evidence; unsupported formats remain explicit. Required representative coverage gates packet 4; broader advertised coverage gates R17. |
| 6 | Implement cloud progress — R11/R12/remote R13 | Freeze remote names and complete inventory/readback behavior for the selected transport, then add leased publication, inbound validation, causal reconciliation, conflict selection, manual-slot/deletion replay, and separate cloud status. | Lost responses, concurrent devices, offline divergence, auth expiry, pagination, and edit-versus-delete tests pass against the isolated server. Incoming work cannot alter a running session; acknowledgments identify the exact uploaded revision. Legacy publication stays disabled. |
| 7 | Close independent functionality and qualify delivery — R08/R16/R17 | Finish Android firmware and catalog gaps; run upgrade/recovery and physical-device qualification; verify CI and signed artifacts against supported behavior. Track R14/R15 and other iOS gaps separately through their existing acceptance contracts. | Finding-by-finding evidence, complete catalog/offline behavior, supported-core/device results, recoverable upgrade/rollback, and artifact/signature verification. Preparing a release does not publish it. |

**Current implementation handoff:** The qualified Android route now has independent schema-3 game-save and snapshot histories, RomM 5.2 transport, durable automatic publication/reconciliation, explicit conflict/local-continuation choices, rollback confirmation and pinned safety snapshots. See [the protocol and qualification boundary](progress-protocol-v3.md) and [current evidence](remediation-status.md). Both synced game saves and synced resume snapshots are required and implemented in this slice. Preserve the working launch → play → checkpoint → sync → second-client resume/cold-boot flow. Broader core/platform/device qualification and the remaining R-items remain open; do not use this result to claim the entire structural remediation or a release is complete.

For every packet, attach the affected R/F IDs, changed behavior, checked scenarios, migration/rollback behavior, and unresolved platform limits to [implementation status](remediation-status.md). Keep runtime implementation, app activation, physical qualification, and published artifacts distinct. The 16-finding coverage table remains the definition of “everything”; no packet closes a finding merely by adding an API or passing a component test.

## 4. Work items

Each item is an implementation unit that may require multiple focused PRs. Keep Android and iOS contract changes together through shared fixtures, even when platform implementation PRs land separately. Current item status and evidence live in [implementation status](remediation-status.md); the requirements below remain the completion contract.

### R01 — Restore trustworthy checks and release artifact handling

**Dependencies:** none. **Closes part of:** F06, F16.

Primary surfaces: [Android tests](../app/src/test), [Android build configuration](../app/build.gradle.kts), [Android release workflow](../.github/workflows/android-release.yml), [iOS validation workflow](../.github/workflows/ios-validate.yml), [development guide](development.md).

- Repair recovery tests against the current APIs while retaining their intended assertions. Convert the queue serialization, unchanged-SRAM, and deleted-slot probes into maintained regression tests. Keep known failures explicitly tracked until their owning fixes land; do not weaken assertions or label them passing.
- Use one configured Kotlin JSON codec or generated adapters for persisted payloads. Cover continuity, manual-slot, and core-download payload round trips, old payload decoding, and malformed payload errors. Replace scattered bare Moshi builders that handle Kotlin models.
- Fix the five lint errors: vibration permission/call sites, notification permission handling, API-26-compatible version-code lookup, and version-qualified theme resources. Triage all 34 warnings; resolve correctness warnings and document narrowly justified remaining suppressions with an owner/work item.
- Add Android PR validation and require tests/lint/build before release publication. Preserve iOS package, core-resource, simulator-build, and signature checks; include shared fixtures and contract changes in both workflows' path triggers.
- Correct release artifact paths for the `standard` and `legacyBridge` flavors. Fail if expected artifacts are missing; give artifacts distinct names and verify their package IDs and signatures. Do not publish a debug-signed release when release credentials are absent: the current build configuration permits that fallback, so the publishing workflow must reject it.
- Reconcile documented and CI toolchains using a tested JDK/SDK configuration. The audit's local JDK override alone is not a reason to declare a new minimum version.

**Acceptance:** Android unit tests compile, required lint has zero errors, both flavors build, and deliberately broken tests or missing/wrongly signed artifacts prevent publication. Swift package and simulator checks remain green. New regression tests are required when their associated fix becomes release-eligible; the baseline repair is not evidence that save behavior is fixed.

### R02 — Enforce credential origin and redirect boundaries

**Dependencies:** R01 test harness. **Closes:** F08.

Primary surfaces: [AuthManager.kt](../app/src/main/java/io/github/bandoracer/rommio/data/auth/AuthManager.kt), [DownloadClient.kt](../app/src/main/java/io/github/bandoracer/rommio/data/network/DownloadClient.kt), [RequestDecorators.swift](../ios/Sources/RommioFoundation/RequestDecorators.swift).

- Centralize a credential policy that validates scheme, host, effective port, and intended deployment path before attaching RomM authorization or edge-access credentials.
- Route external artwork through unauthenticated requests. Treat server-provided save/ROM URLs and signed URLs through the same explicit policy.
- Re-evaluate authorization at every redirect. Reject secure-to-insecure credential forwarding and strip credentials on untrusted redirects, including custom Cloudflare headers and cookies.
- Apply the boundary to image loaders and direct URLSession/OkHttp users, not only the main API client. Redact sensitive headers and signed query strings from diagnostics.

**Acceptance:** local HTTP fixtures inspect headers at trusted, untrusted, alternate-port, sibling-path, and redirect destinations. Only the intended scope receives each credential. Save scenario S25 passes on both clients.

### R03 — Contain existing progress-loss and transfer hazards

**Dependencies:** R01; R02 for authenticated transfer tests. **Closes part of:** F01, F02, F04, F07.

Primary surfaces: [Android player engine](../app/src/main/java/io/github/bandoracer/rommio/domain/player/LibretroPlayerEngine.kt), [Android sync bridge](../app/src/main/java/io/github/bandoracer/rommio/domain/sync/RommSyncBridge.kt), [Android download client](../app/src/main/java/io/github/bandoracer/rommio/data/network/DownloadClient.kt), [iOS download queue](../ios/Sources/RommioFoundation/DownloadQueue.swift).

- Add a dedicated automatic-resume write target and route Android continuity captures to it. Preserve existing slot zero and hidden resume data before the first corrected write. Treat the old filename migration solely as an import; verify two successive captures use the second state.
- Introduce a reusable staging/validation/atomic-install primitive for existing save and ROM transfer paths. Preserve the prior valid file until validation completes. This primitive is retained by R05/R09, not replaced by a second temporary storage design.
- Validate iOS response status, actual byte counts, expected checksums where available, and content/container shape. Reject login/error HTML. Persist completion only after successful installation.
- Gate unsafe legacy overwrite/delete publication while the revision protocol is being built. Retain local progress and pending intent and expose local-only/pending status; do not silently claim synchronization. Explicit import/export can remain available without enabling background overwrite. Do not add another timestamp-based conflict heuristic.
- Stop swallowing local capture errors. Until the full coordinator lands, do not acknowledge a failed save or discard the prior good checkpoint.

**Acceptance:** successive resumes advance; slot zero remains recoverable; interrupted or canceled transfer leaves the old valid file intact; 401/404/500/HTML/truncation never becomes a completed install; a known competing remote save cannot be overwritten by the old background writer. This is containment, not closure of lifecycle or synchronization findings.

### R04 — Unify request authentication and scoped logout

**Dependencies:** R01, R02. **Closes part of:** F12.

Primary surfaces: [Android authentication](../app/src/main/java/io/github/bandoracer/rommio/data/auth), [AuthSessionController.swift](../ios/Sources/RommioFoundation/AuthSessionController.swift), [RequestDecorators.swift](../ios/Sources/RommioFoundation/RequestDecorators.swift), [InteractiveSessionAuthenticator.swift](../ios/Sources/RommioFoundation/InteractiveSessionAuthenticator.swift).

- Make API, asset, download, and sync requests use the same account-bound request-time authentication service.
- Implement single-flight refresh per account and a bounded retry after refresh. Keep tokens on transient network/server/edge-login failures; distinguish confirmed invalid origin credentials from missing edge access.
- Retry mutations only when their idempotency/readback contract permits it. A refreshed token must not cause a second untracked upload.
- Isolate origin session cookies by profile/account, including interactive login and WebView/URLSession/OkHttp storage. Ordinary sign-out ends origin authentication; clearing edge access affects only its intended profile, not every configured server.
- Prevent a refresh that finishes after sign-out from repopulating that session's credentials; use a session generation or equivalent invalidation boundary.

**Acceptance:** concurrent expired-token requests trigger one refresh; API/download/sync all recover; temporary 5xx preserves sign-in; invalid credentials park work; sign-out removes usable origin sessions; another profile remains signed in. Test refresh/sign-out races and bounded retry loops. S28 passes.

### R05 — Introduce account/content identity and recoverable installations

**Dependencies:** R01, R03; coordinate namespace binding with R04. **Closes part of:** F03, F06, F07.

Primary surfaces: [Android database](../app/src/main/java/io/github/bandoracer/rommio/data/local/AppDatabase.kt), [DownloadRomWorker.kt](../app/src/main/java/io/github/bandoracer/rommio/data/work/DownloadRomWorker.kt), [LibraryStore.kt](../app/src/main/java/io/github/bandoracer/rommio/domain/storage/LibraryStore.kt), [iOS database](../ios/Sources/RommioFoundation/AppDatabase.swift), [iOS library store](../ios/Sources/RommioFoundation/AppManagedLibraryStore.swift), [iOS download queue](../ios/Sources/RommioFoundation/DownloadQueue.swift).

- Define the namespace, content generation, file-set, install, device, and session identifiers from the save/sync spec. Include account identity, not just a selectable profile label.
- Add identity to database keys, paths, install queries, download inputs, recovery records, and future progress records. Freeze the destination when work is enqueued; never read the active profile to determine where an old job runs.
- Use actual verified installs and content digests to determine readiness, not arbitrary file existence. Record archive/member and multi-disc identities where supported.
- Persist download ownership, status, retry/cancellation generation, and restart recovery. Resume eligible queued/abandoned work at startup and reconnect on iOS and Android. A late callback from a canceled attempt must not mark a new attempt complete.
- Ensure installed ROM removal preserves progress. Server URL/account changes require verified rebinding instead of redirecting pending work.

**Acceptance:** queue on server A, switch to B, restart, and verify the request/file still belongs to A. Exercise colliding IDs/names across accounts, alternate ROM files, replaced ROM bytes, download restart, and cancel/retry races. S16–S17 and S24 pass. Ship the schema/path cutover only with R06's migration gate.

### R06 — Make migrations and bundle imports recoverable across all stores

The Android legacy-bundle path now has [journal/barrier implementation and partial evidence](import-recovery.md). The full requirements and acceptance gate below still apply.

**Dependencies:** R05 schema/identity design; R03 file primitives. **Closes:** F15 and migration portions of F03/F16.

Primary surfaces: [MigrationBundleManager.kt](../app/src/main/java/io/github/bandoracer/rommio/data/migration/MigrationBundleManager.kt), both database/library stores, profile secret/preferences stores.

- Separate legacy-format discovery from activation of the new writer. Inventory global paths and ambiguous ownership without assigning them to the current account.
- Implement a persistent migration/import journal covering files, database, secrets, and preferences. Stage and validate before activation; quiesce sessions, download jobs, and sync workers during the commit window.
- Extend the bundle format explicitly for v2 progress. The application store lives at `noBackupFilesDir/progress-v2`, outside the library tree: exporting the library alone cannot preserve it. Include immutable operations/checkpoints/blobs, reference heads, outbox intent, history/pins, retained recovery data, and working-session/capture/cleanup journals. Preserve exact operation IDs and payload bytes; import must not fabricate a new revision or turn local-only progress into publication work.
- Coordinate the application store's lifetime with the import barrier. Refuse replacement while a native session owns storage; close an idle store before swapping its files/database and reopen only after recovery reaches a consistent state. Persisted outbox leases must recover as interrupted work, never as an imported live worker or session. Installation/device identity remains local to the receiving installation while imported historical attribution remains intact.
- Keep format-1/format-2 readers, validate the new bundle completely before activation, and define unsupported-version behavior. Format 3 replaces the earlier transfer refusal with an exact metadata/file snapshot, including interrupted captures with no committed v2 operations. Keep the old-player ownership guard until qualified v2 launch and explicit legacy conversion replace it.
- Define recovery for every journal phase after process death. Roll back or finish forward to a consistent full state; restoring only files after database commit is prohibited.
- Preserve originals, slot zero, hidden resume files, queued intent, and provenance. Require explicit mapping/recovery for ambiguous assets. Keep backup secrets protected by platform storage; never write plaintext tokens into test logs or general-purpose backup manifests.
- Make restart/retry idempotent. Defer thumbnail rebuilding and other derived cache work until the authoritative import commits.
- Exercise both Android package flavors and the legacy export/import route. Apply equivalent recovery discipline to the new iOS progress schema migration.

**Acceptance:** injected failure at every file, database, secret, preference, and activation boundary yields either the intact old state or the validated new state, with no mixed account credentials or active competing worker. Round trips retain conflict branches, manual slots/tombstones, unpublished and local-only operations, pinned history, orphan workspaces, and pending capture bytes. Cover an interrupted capture with zero committed operations, an idle open store, rejected active-session import, and old bundle versions. Rerunning creates no duplicate operations. S29 passes; backup recovery is demonstrated before enabling new storage for existing users. Reopened fixtures and injected failures are component evidence; actual process-kill and storage-exhaustion runs remain required qualification.

### R07 — Pin cores and describe persistence compatibility

**Dependencies:** R01; R05 content binding. **Closes part of:** F14.

Primary surfaces: [CoreInstaller.kt](../app/src/main/java/io/github/bandoracer/rommio/domain/player/CoreInstaller.kt), [Android core catalog](../app/src/main/assets/core_catalog.json), [iOS core release manifest](../scripts/ios/bundled-core-release-manifest.json), [runtime matrix](runtime-matrix.json), both player engine contracts.

- Replace floating Android core resolution with versioned, checksum-verified artifacts and atomic installation. Record ABI, exact build/digest, and install verification; a nonempty library is not sufficient.
- Reuse iOS bundled-core verification to produce the same logical compatibility descriptor, while preserving platform-specific delivery.
- Describe core persistence capabilities: SRAM, RTC, memory cards/file-backed saves, serialization support, relevant BIOS/options, and required capture/restore ordering.
- Default automatic state compatibility to a verified exact descriptor. Build a narrow compatibility allowlist only from measured core/build/platform pairs. Keep in-game-save compatibility separate from state compatibility.

**Acceptance:** corrupted/wrong-ABI cores fail readiness without replacing valid assets; a core update cannot silently auto-load an unqualified state; compatible in-game progress remains available. Representative core descriptors feed R09/R10 fixtures and S18/S19/S21. Record an actual decision for any upstream artifact that cannot be pinned; do not leave it silently floating.

### R08 — Unify BIOS import, validation, and core-visible layout

**Dependencies:** R01, R07 runtime descriptors. **Closes:** F11.

Primary surfaces: [CoreCatalogResolver.kt](../app/src/main/java/io/github/bandoracer/rommio/domain/player/CoreCatalogResolver.kt), [Android asset helper](../scripts/android/push_runtime_assets.sh), [BundledCoreCatalog.swift](../ios/Sources/RommioPlayerKit/BundledCoreCatalog.swift), both session preparation paths.

- Define one provisioned system-directory contract, including core-required subdirectories. Readiness checks and the exact path given to the core must use the same resolver.
- Import user-supplied BIOS files through a normal UI flow, validate required names/content identities, and report missing/wrong firmware. Do not obtain or bundle copyrighted BIOS assets as part of this work.
- Conservatively migrate existing `bios` and `system` assets into the chosen layout, preserving differing copies for resolution. Update emulator helpers and documentation to use it.

**Acceptance:** BIOS imported through the UI is found by the real core; nested layouts work; removing/replacing required firmware yields an accurate readiness state. A helper-only installation is insufficient to close the normal-user flow.

### R09 — Build the durable ProgressStore and local outbox

**Dependencies:** R05, R06, R07. **Closes part of:** F01–F06, F14.

Primary surfaces: [Android storage](../app/src/main/java/io/github/bandoracer/rommio/domain/storage), [Android sync journal](../app/src/main/java/io/github/bandoracer/rommio/data/local/SyncJournal.kt), [iOS persistence](../ios/Sources/RommioFoundation/GameStatePersistence.swift), [iOS state models](../ios/Sources/RommioFoundation/GameStateModels.swift), both databases.

- Implement immutable artifact storage, checkpoint manifests, per-reference causal history, manual slot UUIDs, automatic resume, pinned recovery, and explicit tombstones.
- Commit checkpoint metadata, references, and publication intent in one database transaction after artifact installation. Preserve exactly the immutable payload that will be uploaded.
- Keep core working files separate from immutable blobs. Capture complete save-data inventories; distinguish empty valid data from failed reads and state failure from SRAM failure.
- Recover interrupted staging/commits and expired leases. Retain causal metadata and protect manual, unsynced, conflicting, and pinned progress during garbage collection.
- Implement the local queue schema/codec and ownership outside player screens. Remote draining arrives in R12; the queue already survives restart and exposes pending work.
- Preserve old resume as history when only newer save data succeeds, and prevent that stale state from silently rewinding current progress.

**Acceptance:** S01/S02/S05/S06/S08/S18/S19/S20/S29/S30 pass at the store boundary with crash/failure injection. No success acknowledgment precedes local commit. Legacy imports are recoverable, and the old mutable progress writer is disabled before activating the new one.

### R10 — Centralize the player session lifecycle

**Dependencies:** R09, R07; R08 for BIOS-dependent systems. **Closes:** F01/F02 and lifecycle portions of F04/F14.

Primary surfaces: [PlayerViewModel.kt](../app/src/main/java/io/github/bandoracer/rommio/ui/screen/player/PlayerViewModel.kt), [PlayerScreen.kt](../app/src/main/java/io/github/bandoracer/rommio/ui/screen/player/PlayerScreen.kt), [PlayerFeatureModel.swift](../ios/Sources/RommioUI/PlayerFeatureModel.swift), [PlayerExperienceView.swift](../ios/Sources/RommioUI/PlayerExperienceView.swift), both native engine adapters.

- Introduce the spec's single session coordinator and ordered core lane. Freeze identity and selected baseline at launch; enforce one writable session for each progress identity.
- Replace cached-data-driven loading completion with explicit preparation, progress selection, native-ready, restore, and running states. Observe native errors and prevent input until ready.
- Materialize SRAM/file-backed data before core consumption and load a compatible state only after readiness. Bound cloud checking; late responses become candidates rather than mutations of the active core.
- Enforce a false `retro_serialize` result as a capture failure; Android's [locally patched native runtime](android-checked-capture.md) now implements and tests this boundary. Qualify zero-sized/unsupported state behavior per core, preserving successful in-game save data.
- Reset the complete native environment between sessions, including options and controller descriptors, and record effective options in compatibility identities. Android's [source-pinned runtime](android-native-runtime.md) now tests session reset and failed-load cleanup without fixture defaults masking leakage. The inactive [checkpoint writer](android-checkpoint-capture.md) now records effective options; production compatibility enforcement and hardware-core qualification remain required.
- Route pause, periodic capture, backgrounding, reset, manual save/load, and exit through the coordinator. Coalesce periodic work without dropping manual or exit requests. Await final local commit before core disposal.
- Capture safety checkpoints before restore/reset. Recover a failed restore to a verified working state or stop cleanly. Present local save failure with retry and explicit leave-without-latest options.
- Remove duplicate screen/ViewModel/feature-model capture and pause authorities after cutover.

**Acceptance:** S01–S04/S07/S18–S21/S27/S32 pass on both clients, including actual process death, background/foreground, delayed core readiness, and slow remote preparation. Tests must detect wrong gameplay position and mixed SRAM/state, not just the existence of files.

### R11 — Prove RomM transport capabilities and freeze the wire contract

**Dependencies:** R01 harness and R02 credential boundary for network probes. Starts during A; does not wait for R09. **Closes part of:** F04/F05/F14.

Primary surfaces: both RomM API/endpoint and sync adapters; proposed shared protocol fixtures and capability report under `docs/` and a shared test-fixture directory.

- Build an executable probe against an isolated, version-recorded RomM instance with at least two accounts and two simulated devices. Use synthetic save/state bytes. Do not probe destructive semantics using personal gameplay saves.
- Verify account isolation, pagination/completeness, unique-name behavior, overwrite semantics, opaque manifest storage, checksum/readback, visibility delays, conditional writes, and ambiguous-response retries.
- Record observed requests/responses without secrets, server version, limits, and failure cases. Select the spec's conditional-head mode or immutable-operation-inventory mode only if evidence supports it.
- Freeze schema-v2 serialization, identity mapping, compatibility encoding, error handling, and golden operation graphs. Require Android and iOS to read each other's fixtures.
- If neither transport is viable, record the exact missing capabilities and a concrete server-change or alternate-transport proposal. Local-only/import-export is a safe interim release, but it does not mark the requested synchronization work complete.

**Acceptance:** two concurrent simulated writers retain both operations; retry after a dropped response is idempotent; cross-account access is prevented; every published operation is discoverable with complete inventory traversal. A checked-in decision report and interoperability fixtures are required before R12 publication is enabled.

### R12 — Replace synchronization with reconciliation and durable replay

**Dependencies:** R02, R04, R09, R10, R11's proven transport mode. **Closes:** F04/F05 and sync portions of F06.

Primary surfaces: [Android RommSyncBridge](../app/src/main/java/io/github/bandoracer/rommio/domain/sync/RommSyncBridge.kt), [OfflineSyncWorker.kt](../app/src/main/java/io/github/bandoracer/rommio/data/work/OfflineSyncWorker.kt), [iOS RommSyncBridge](../ios/Sources/RommioFoundation/RommSyncBridge.swift), [ProfileSyncBridge.swift](../ios/Sources/RommioFoundation/ProfileSyncBridge.swift).

- Reconcile causal heads/common ancestors, compare real content hashes, and persist all divergent branches. Stage incoming updates during active play; never re-parent local gameplay to an unseen remote baseline.
- Publish immutable blobs and ancestors before commit markers. Validate complete inbound artifacts before atomic application. Bind acknowledgments to the precise uploaded revision.
- Implement leased jobs, retry/backoff, authentication parking, ambiguous-success readback, and restart/foreground/reconnect activation. Use platform background APIs opportunistically; keep the database as the source of queue truth.
- Include manual slots and versioned deletions in ordinary sync. Preserve pending intent during refresh; retain edit-versus-delete conflicts and unknown schemas.
- Serialize local per-game reconciliation while handling remote concurrency through the chosen server protocol. Remove synthetic remote hashes, blind overwrite, and mutable latest-manifest publication.
- Drain saves independently of catalog/artwork and keep newly captured progress pending when an older upload completes. Preserve external legacy writers as imports; do not silently mirror winning heads into overwrite-prone legacy filenames.

**Acceptance:** S08–S17/S22/S23/S26/S28/S30/S31 pass against the real chosen transport and both clients. Verify Android↔Android, iOS↔iOS, and Android↔iOS divergence/reconnect with compatible in-game data; qualify state transfer only for verified core pairs. Confirm unchanged remote bytes are not repeatedly downloaded.

### R13 — Expose accurate save status, conflict resolution, and recovery

**Dependencies:** R09/R10 for local UX; R04/R12 for remote/profile UX. **Closes user-visible portions of:** F04/F05/F12/F16.

Primary surfaces: player overlays, state browsers, download/profile settings, app-level status and diagnostics on both clients.

- Implement separate local-save and cloud-sync state, including last successful save, pending work, last checked, auth blocked, unsupported transport, and conflict. No generic “synced” while a manual slot/deletion is pending.
- Make branch selection explicit before loading conflicting current progress. Preserve unselected progress and offer named recovery copies. Record resolution/rollback as a new causal operation.
- Add recovery browsing/import for unknown legacy ownership and incompatible states without exposing destructive raw-path operations.
- Implement profile switching, sign-out, server removal/reattachment, ROM removal, local removal, and synchronized deletion with their distinct spec semantics.
- Expose queue age/depth, revision/session IDs, capture errors, compatibility rejections, and transport mode through redacted diagnostics. Do not include credentials or raw saves in a default support export.

**Acceptance:** UI tests cover offline play, failed local save, failed upload after successful local save, conflict selection, explicit rollback, queued manual slots, sign-out and reattachment. Observed labels must match database/remote readback; toast success alone is never evidence.

### R14 — Finish iOS physical controller input

**Dependencies:** R01; integrate gameplay/menu commands with R10 before final closure. **Closes:** F09.

Primary surfaces: [GameControllerMonitor.swift](../ios/Sources/RommioFoundation/GameControllerMonitor.swift), [PlayerControlsRepository.swift](../ios/Sources/RommioFoundation/PlayerControlsRepository.swift), [IOSLibretroPlayerEngine.swift](../ios/Sources/RommioPlayerKit/IOSLibretroPlayerEngine.swift), player UI.

- Route controller buttons, d-pad, analog axes, and configured hotkeys to the engine through device-to-port assignment.
- Support mapping/dead zones where applicable and release held inputs on disconnect, background, reassignment, or session exit. Avoid duplicate delivery from overlapping input callbacks.
- Route pause/menu/save/load actions through the coordinator. Hide touch controls only after a usable controller input route is active; restore touch fallback on disconnect.

**Acceptance:** synthetic input tests verify mapping and releases, followed by physical-controller gameplay on iPhone and iPad. Exercise hot-plug, held-button disconnect, two controllers/port assignment where advertised, analog movement, and menu return without stuck input. Connection detection alone is not qualification.

### R15 — Make iOS video negotiation match rendering

**Dependencies:** R01. **Closes:** F10.

Primary surfaces: [RMLLibretroBridge.mm](../ios/Sources/RommioPlayerBridge/RMLLibretroBridge.mm), [PlayerSurfaceController.swift](../ios/Sources/RommioPlayerKit/PlayerSurfaceController.swift), [fake core fixture](../ios/Tests/RommioPlayerBridgeTests/Fixtures/FakeLibretroCore.c).

- Implement the enabled pixel formats end to end, including RGB565 and the default 16-bit path, or reject a requested unsupported format during negotiation and report it clearly. Prefer support for formats used by enabled cores.
- Treat bytes-per-pixel, pitch/row padding, channel order, frame size changes, and buffer lifetime explicitly. Remove unknown-format-as-32-bit fallback.
- Extend fake-core fixtures with known color patterns in each accepted format and padded rows; test the presentation path as well as native frame capture.

**Acceptance:** expected pixel colors/geometry render through the iOS surface for every accepted format; real enabled cores show moving gameplay with audio on simulator and physical device. A successful byte callback or non-nil image alone does not prove correct color/rendering.

### R16 — Make catalog refresh complete and transactional

**Dependencies:** R01, R04, R05 account identity; coordinate database migration with R06. **Closes:** F13.

Primary surfaces: [RommRepository.kt](../app/src/main/java/io/github/bandoracer/rommio/data/repository/RommRepository.kt), [RommService.kt](../app/src/main/java/io/github/bandoracer/rommio/data/network/RommService.kt), [LibraryRepository.swift](../ios/Sources/RommioFoundation/LibraryRepository.swift), [OfflineReadinessStore.swift](../ios/Sources/RommioFoundation/OfflineReadinessStore.swift), both caches.

- Paginate full platform, collection, and full-library synchronization, including iOS hydration beyond recent/continue-playing entries. Track what scope was actually fetched.
- Stage a complete collection/catalog generation before replacing membership. Failed pages retain the prior complete snapshot; absence in partial/incremental responses is not deletion.
- Reconcile genuinely removed server records without deleting installed ROMs, saves, or richer cached detail fields. Preserve local-only installed entries deliberately.
- Include collection kind with account and collection ID in all iOS keys, joins, routes, and references. Migrate existing membership without cross-kind collisions.
- Make offline-ready status report actual cached coverage and asset availability. Keep this pipeline independent of save publication.

**Acceptance:** fixtures with more than 50 items, multiple pages, interrupted middle pages, removed ROMs, detail-only fields, and equal collection IDs across regular/smart/virtual kinds produce complete correct views. Restart offline and confirm cached scope; a catalog failure does not block a queued save.

### R17 — Qualify the complete app and reconcile support documentation

**Dependencies:** R01–R16, with explicitly recorded blockers where hardware or server capability is unavailable. **Closes:** remaining F14/F16 and validates every other finding.

Primary surfaces: [validation matrix](validation-matrix.md), [core matrix](core-matrix.md), [iOS core matrix](core-matrix-ios.md), [runtime matrix](runtime-matrix.json), [root README](../README.md), [repo instructions](../AGENTS.md), [development guide](development.md), iOS docs and CI.

- Run all applicable S01–S32 scenarios, each with a named harness/device, exact commit, server version, core digest, OS/build flavor, and result. Record inapplicability with a capability reason; do not count skipped tests as passes.
- Add the non-save acceptance evidence from R01/R02/R04/R05/R06/R08/R14/R15/R16. Test installed signed artifacts as well as source builds.
- Qualify representative SRAM, file-backed, and serialized-state cores. Cover minimum Android API behavior, a current Android device, iPhone/iPad lifecycle/input/rendering, and the supported cross-device combinations.
- Align README, AGENTS, developer docs, runtime catalogs, and UI support claims with the Android+iOS scope and measured support. Keep potential core availability separate from qualified playback, in-game-save sync, and state compatibility.
- Remove superseded mutable-file writers, direct screen-owned sync, obsolete filename migration calls, and duplicate JSON/auth paths. Search all call sites and exercise upgrade paths before deletion; preserve legacy readers/importers required for recovery.
- Require a clean-install test, upgrade from legacy fixtures, rollback/recovery rehearsal, and correct release artifact/signature checks before publishing. Attach evidence to each finding's closure.

**Acceptance:** all 16 findings have linked fix/test evidence; required checks pass; no known destructive old path remains reachable; physical-device and real-server qualification are explicitly recorded. An unavailable test environment or unproven transport stays an open item, not a completed release claim.

## 5. Validation layers and commands

Use the smallest meaningful test at each implementation step, then integration gates at stage boundaries. Do not rerun every device suite for unrelated documentation changes.

| Layer | Purpose | Gate |
| --- | --- | --- |
| Shared golden fixtures | Identity, encoding, ancestry, tombstones, compatibility, migration | Android and iOS agree on expected state; introduced in R01/R11 and expanded with implementation |
| Unit/component fault injection | Commit boundaries, leases, auth races, pagination, rendering/input mapping | Required per owning work item |
| Local HTTP fixtures | Redirect/header scope, HTTP errors, truncation, expired credentials, retry ambiguity | Required before networking changes ship |
| Isolated real RomM integration | Actual concurrency, inventory, account isolation, upload/readback semantics | R11 decision and R12 qualification |
| Emulator/simulator integration | Lifecycle UI, fresh install/upgrade, bridge/rendering smoke, artifact loading | Required before platform release |
| Physical devices and cores | OS suspension/process death, input, firmware, state compatibility, real playback | R10/R14/R15/R17 release evidence |

Baseline commands from the repository root, after R01 repairs their current failures:

```bash
./gradlew testStandardDebugUnitTest testLegacyBridgeDebugUnitTest
./gradlew lintStandardDebug lintLegacyBridgeDebug
./gradlew assembleDebug
swift test --package-path ios
scripts/ios/verify_core_build_manifest.sh
scripts/ios/verify_bundled_core_resources.sh
scripts/ios/build_simulator_app.sh
```

Fetch the documented bundled iOS resources before their verification/build on a fresh checkout. Pin the tested toolchains and add dedicated integration/instrumentation commands as their harnesses are introduced. Existing live-auth tests are not substitutes for two-device save tests. Release validation additionally checks the selected release variants, artifact existence, package IDs, and signatures using R01's workflow.

## 6. Rollout and recovery rules

- Use one active writer per namespace/storage version. Introduce new readers, inventory, and migration before cutover; never dual-write progress through legacy and new sync paths.
- Cut over local storage only after backups and recovery fixtures pass. Mark the minimum compatible schema so an older binary cannot mutate new-format data accidentally.
- Enable protocol-v2 remote writes only for qualified server/client combinations. Keep queued local revisions safe while sync is disabled or unsupported.
- A rollback disables the new remote publisher and preserves progress/outbox for forward recovery. It must not reactivate legacy overwrite against protocol-v2 history or blindly reinstall an old app over an incompatible database.
- Preserve original migration backups until validated recovery is available. Restoring an old backup is an explicit recovery action that preserves any newer progress first.
- Local-durability improvements can ship before cloud synchronization. Keep the remaining sync work open and describe the interim limitation clearly.

## 7. Completion tracking

For each R-item, record status (`not started`, `in progress`, `blocked`, `verified`), implementation PR/commit, test evidence, and remaining platform limits. A merged PR without its acceptance evidence is implemented, not verified. Update the finding coverage table when a finding is actually closed.

Recorded implementation now includes partial checks/release guards, credential boundaries and profile-cookie ownership, legacy save containment, inactive v2 stores/shared fixtures, a RomM transport probe, and iOS pixel-format repairs. None of those summaries closes its R-item. Continue with the remaining batches in section 3 and keep containment checks mandatory throughout. Estimate each subsequent batch after its prerequisite schema/transport decisions and representative core tests; file count alone is not a useful estimate of migration or compatibility risk.

The overall effort is complete only when every finding is closed, the save/sync specification is implemented on both clients for the supported configurations, and runtime/release evidence supports the claims made to users.
