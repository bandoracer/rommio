# Android isolated working sessions

Status: partial R06/R09/R10 implementation. Working sessions and explicit interrupted-capture recovery are implemented at the store/planner boundary and exercised by native tests. The production player still uses its legacy writer. An [application owner](android-application-session.md) now drives this path in native tests. Production UI wiring, migration, recovery UI, and file-backed core qualification remain activation gates.

## Preparation and ownership

`ProgressStore.prepareWorkspace` reserves the account/content identity before creating runtime files. It rejects a legacy SRAM seed, an identity mismatch, changed core bytes, and unqualified archive/disc descriptors. The current file adapter supports a single raw content file. A prepared workspace contains:

```text
<namespace>/<content>/
  sessions/<session>/
    session.json
    content/<original-name>
    system/<declared-firmware-paths>
    saves/
    states/
    captures/<capture>/...
  abandoned-sessions/<session>/...
  cleanup-markers/<session>.json
```

The ROM and explicitly declared firmware are copied and hashed before the workspace is handed to the native engine. Core-visible save/state directories start empty. Original installs, legacy SRAM/states, shared firmware, and immutable progress blobs are not writable runtime inputs. The core library remains the verified, versioned artifact; workspace creation does not qualify a core's persistence format.

Preparation writes a versioned ownership record before copying inputs and uses a `.preparing-<session>` directory until preparation completes. The record identifies the local namespace/content binding, session, core digest, content path, and firmware hashes. Prepared files are synced before the directory is atomically published. Preparation failures retain their partial directory for inventory and release the session reservation.

The workspace reservation blocks a second session and storage import/replacement. `ProgressLaunchPlanner.prepare(workspace, ...)` checks for retained interrupted sessions before choosing progress. `start(..., workspace)` atomically verifies selected heads, persists the selected checkpoint in the workspace record, and transfers the existing reservation to the coordinator. It uses the workspace session ID and capture directory. There is no release/reacquire gap and no implicit legacy migration.

## Teardown and interrupted sessions

The coordinator releases the reservation after native teardown succeeds. Workspace cleanup audits the actual files:

- Unchanged copied content/firmware plus empty output directories can be removed.
- Changed inputs, extra native files, symlinks, or remaining capture files cause the whole workspace to be retained under `abandoned-sessions`.
- Cleanup failures retain whatever remains for subsequent recovery; the already-closed native instance does not keep a phantom reservation. A cleanup error is still reported to the caller.

A clean deletion gets an independent, synced cleanup journal before rename/unlink begins. That journal survives removal of the internal `session.json`, so interrupted deletion can finish on reopening the store. A directory name alone cannot authorize deletion. Partial atomic marker files have no cleanup authority and remain retained.

Recovery runs under the store's process lock and mutation barrier. It skips the current active workspace, completes journaled clean deletion, and moves other prepared/preparing directories into the recovery inventory. It never loads a loose SRAM/state file, guesses ownership from a filename, or selects progress by modification time. Unknown/corrupt metadata is reported and preserved. Foreign-account records cannot be replayed in the current identity.

Both active and abandoned sessions protect their selected baseline from history eviction. An unreadable abandoned record blocks pruning for that content because its protected baseline cannot be established. Explicit discard can remove a selected abandoned directory, including an unreadable record, without following links or changing committed references/outbox entries. Recovery UI must distinguish that discard from replaying a verified checkpoint; neither action currently runs automatically in production.

## Captures that outlive a failed commit

`SessionCheckpointWriter` now stages the following before attempting a database commit:

1. Flushed artifact files under their SHA-256 names.
2. The exact validated operation bytes in `operation.json`.
3. A versioned `ready` marker containing the operation digest and its original publication policy.
4. Directory syncs for the capture and its parent.

The operation already contains its original session/account/content identity, causal parents, manual-slot changes, and artifact inventory. Recovery does not reconstruct these values from the latest heads. An incomplete capture without a valid ready marker remains an unverified recovery candidate.

`pendingWorkspaceCheckpoints` inventories journal envelopes; artifact validation still occurs during replay. `recoverWorkspaceCheckpoint` explicitly retries those exact bytes through the normal store commit path, which checks identity, ancestry, artifact lengths/hashes, and operation-ID consistency. An operation committed before a lost acknowledgment is recognized on retry and cannot create another revision. `PENDING` versus `LOCAL_ONLY` intent is preserved. A newer head can coexist as a conflict; recovery does not silently merge it or mutate a running core.

Successful replay removes the capture staging directory when possible. It retains the abandoned workspace until explicit discard, preserving any other native files. Failed/corrupt replay leaves its files and the last valid committed progress intact. The journal is a local recovery format; the shared v2 wire envelope and database schema are unchanged.

## Evidence and remaining integration

`ProgressWorkspaceTest` covers input isolation, clean release, duplicate ownership, interrupted preparation/reopen, exact-byte replay after ambiguous commit success, publication intent, corrupt journals/metadata, orphan baseline retention, cross-account rejection, links, and interrupted cleanup after internal metadata deletion. These are Room/filesystem failure fixtures, not actual OS process-kill or power-loss qualification.

The bundled FCEUmm tests now run the two-launch qualification/planner/coordinator round trip using isolated workspaces, confirm successful teardown cleans them, and verify that retained unexpected native output blocks another automatic launch until explicitly resolved. All content is synthetic. CI's native selection includes workspace and history tests; hosted execution still needs evidence.

The application owner and repository cold-input entry point now exist. Next: migrate legacy and v2 transfer formats and connect the production UI to that owner, including orphan inventory/replay/discard alongside ordinary checkpoint recovery. File-backed formats still need their own complete persistence and flush adapters. Physical-device process death, storage exhaustion, firmware-dependent gameplay, and signed-app qualification remain open. See [current validation](remediation-status.md), [launch selection](android-progress-launch.md), and [the remediation plan](remediation-plan.md).
