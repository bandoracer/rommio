# Download ownership and recovery

Status: Android and iOS now have persisted download ownership and attempt recovery. Legacy ownership migration, complete account-scoped install/content modeling, and device qualification remain open under R05/R06/R17. This is not completion of the storage cutover or save/sync architecture.

## Owned work

An already authenticated profile without an ownership guard is verified through `/users/me` before accepting new work; this does not assign ownership to its older files or jobs. New jobs on both clients capture the profile ID, verified server/account guard, ROM/file IDs, filename, expected byte count, an attempt UUID, and its relative destination before execution. Execution uses that captured server and profile; changing the active profile does not change the job. The profile and account guard are checked before transfer and before the result becomes an installed record. A changed server/account cannot retarget the job.

The record has optional `owner`, `attemptID`, `destinationRelativePath`, `contentSHA256`, `retryAfterEpochMS`, and `parkedForAuthentication` fields so existing JSON records remain decodable. New work requires ownership. These fields do not infer an owner for legacy downloads or files. Work without an owner is retained with an explicit migration error rather than replayed through the current profile.

Each attempt downloads to `roms/installs/<attempt UUID>/<filename>`. The filename must be a single safe component. Managed-path checks reject escapes from the library root. The destination remains app-owned and does not depend on the active profile, title, or platform name. Distinct accepted jobs and retries have distinct files. iOS queue IDs include the profile, so equal numeric ROM/file IDs across profiles can coexist. Android schema 11 also includes the verified account namespace in those primary keys; colliding IDs can coexist. New single-file saves use content-scoped paths and records. See [account/content storage](account-content-storage.md) for the remaining legacy and multi-file boundaries.

## Commit and cancellation

The production transfer uses the existing authenticated API client and HTTP/status/body/size validation. After transfer, the queue checks actual length and computes SHA-256 before committing the installed path and digest together in the download record. An attempt's file is never used as the destination of a later attempt. A failed replacement preserves the prior installed path and digest.

Every transition compares the expected record with the current record inside the SQLite write transaction. The attempt UUID alone is not an in-memory ownership convention. A superseded task cannot replace a newer queue state, installed path, error, or digest. Active-task cleanup also checks its attempt UUID so an older completion cannot remove a retry's task entry.

Cancel and local removal invalidate the attempt before canceling network work. Local removal detaches the installed pointer before unlinking the file; a late callback cannot recreate it. Cleanup removes only unreferenced bytes from the finishing attempt. Previously committed install versions remain retained until migration/retention can account for active sessions and recovery history; this batch does not introduce an unsafe eager garbage collector.

The player launch path consults the durable download record for tracked files. Owned installs require the same server/account guard and matching on-disk SHA-256 before launch, including offline launch. Download-detail presentation uses the record's installed path. An arbitrary same-name file is not a substitute for a missing tracked install. Existing untracked legacy playback remains a separate R06 migration concern.

## Android integration

Room schema 10 added nullable owner profile/server/account, installed SHA-256, attempt UUID/destination, retry deadline, and authentication-parking fields. The 9-to-10 migration preserves existing rows without assigning owners; migration bundle mappings preserve the new optional metadata. Full migration/import recovery and independent content generations remain R05/R06 work.

`OwnedDownloadQueue` owns transitions, while `DownloadRomWorker` schedules and reports progress. Installation commits the `downloaded_roms` row and completed download record in one Room transaction after size/digest validation. Full-record compare-and-set protects claims and completion. A losing executor never owns or cleans the winner’s file. Cancellation or transient failure creates a fresh attempt; a restarted worker also recovers abandoned running records when WorkManager reuses the same work ID.

Startup queues recovery before online authentication probes; successful direct/browser login and explicit validation wake parked work. WorkManager’s network constraint resumes eligible work on reconnect. Its retry/backoff wakes deferred jobs no earlier than their stored deadline. Recovery runs at the start of each worker, whose unique work chain serializes queue execution. Scheduling appends a successor rather than dropping a wake-up while an older worker finishes; cancel/preemption awaits cancellation registration before scheduling replacement work.

Installed-library, recent-game, download-history, detail, and recovery-install queries filter known owners to the active profile. Launch verifies the installed owner and digest, including the configured server URL. Unowned legacy records remain distinguishable and preserved pending migration. Sign-out retains pending save intent and schedules download recovery so another profile’s work can continue. Global save/state keys and the coordinated session boundary are still outstanding.

## Recovery

On iOS, startup, foreground, reconnect, shell refresh, and active queue observation call recovery; Android uses the worker integration above. An abandoned running record gets a new attempt and destination; it never assumes that bytes left by an interrupted process prove completion. Queued jobs retain their original owner even if another profile is active.

Transient connection failures retain queued work with a persisted 30-second retry deadline. On iOS an in-process wake-up handles that deadline while execution is available; Android uses WorkManager backoff. Android also treats HTTP 408/429/5xx as retryable without marking the transfer installed. Background suspension is not treated as guaranteed execution; a later foreground/restart consults the durable deadline again. Authentication failures park work durably. Recovery can resume it only after the original profile is signed in; signing in to another profile does not release it. Invalid ownership and legacy records remain failed with their data preserved.

## Evidence and activation limits

iOS `DownloadQueueTests` has 11 cases covering:

- Authenticated production HTTP transfer, actual installed bytes, captured profile, and persisted digest.
- Identical IDs/names on different profiles, with distinct destinations and bytes.
- Cancel/retry plus late completion and cleanup, and a stale completion from a separate queue actor using the same database.
- On-disk restart fixtures, active-profile changes, edited server rejection, and safe handling of unowned legacy work.
- Authentication parking/resume, transient retry deadlines, local removal during transfer, preservation after a failed replacement, and detection of content tampering.

The UI retry test now uses an owned fixture and controlled transfer, so it tests command routing without depending on an external server's timing. The full Swift suite and simulator build are tracked in [remediation status](remediation-status.md).

Android `OwnedDownloadQueueTest` originally added 14 actual Room/HTTP cases: production authenticated transfer after profile switching and database reopen; account collision handling; cancel/retry with late completion; duplicate-executor cleanup; recovery with a reused worker ID; preserved unowned work; failed replacement/tamper rejection; original-account authentication parking; persisted transient retry; removal during replacement; worker cancellation; stale removal and changed-server rejection; a stale executor after logout; and 9-to-10 migration preservation. The existing progress-store migration fixture also exercises 8-to-9-to-10 schema validation. Worker scheduling call sites are integrated, but a full OS-driven process-kill/network-loss test remains qualification work.

These fixtures do not qualify an actual process kill during a transfer, disk exhaustion at every commit boundary, physical-device background behavior, or arbitrary provider browser handoffs. The remaining authentication mutation/browser-transition audit still applies; comparing the persistent guard does not prove the absence of every credential race.

R05/R06 still require the remaining legacy/account consumer migration, independent content generations and archive/member/file-set identities, retained-install inventory/cleanup, and progress-path cutover. New attempt files and digests do not activate the v2 progress store or migrate legacy save ownership. Schema 11 scopes new single-file Android progress and its state browser; existing legacy saves still require explicit ownership resolution, and iOS progress paths/session integration remain open before claiming fully isolated gameplay.
