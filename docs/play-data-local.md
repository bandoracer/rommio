# Local play data: sessions, save previews, saves reader, names and RomM metadata

Status: Android implemented 2026-09-23 (Phase 4 of [theme-experiences-plan.md](theme-experiences-plan.md)).
iOS implemented 2026-09-24 (see **iOS implementation** below, with its deviations). Platform
qualification is reported separately per [AGENTS.md](../AGENTS.md).

This is the shared, platform-neutral definition of the data behind Stats, Recent saves, the Game
page's Saved progress and the player's Save states sheet. Terms follow
[save-sync-spec.md](save-sync-spec.md) §3 (identities) and §11 (copy).

## Principles

- **Local only.** Nothing here is written into a checkpoint, an operation, the outbox or any
  synced payload. Protocol v3 is unchanged. Syncing previews, names or device names needs a v4
  (out of scope).
- **Read-only against progress.** The saves reader only calls the progress store's read APIs
  (`identities`, `referenceSnapshot`, `history`, `publicationStatus`). It never creates an
  identity, commits, pins, resolves or changes a reference.
- **Never blocks a save.** Recording a session, grabbing a frame or writing a preview happens off
  the core lane after (or beside) the durable commit. Any failure is dropped; the save result the
  player sees is exactly the commit's.
- **Device-local, not backed up.** Like Appearance, this data is not part of migration or backup
  bundles. After an import, previews and names of checkpoints that no longer exist are pruned
  (see Previews); play sessions stay as a record of play on this device.
- **Themes never touch it.** Save previews and RomM screenshots are shown as captured: no art
  treatment, no tint, letterboxed on the player ground colour.

## Identities used

| Name | Meaning |
| --- | --- |
| `namespaceId`, `contentId` | The progress store's local namespace (server + account) and content generation |
| `checkpointId` | A committed checkpoint (UUID). Previews and names key on `(namespaceId, contentId, checkpointId)` |
| `sessionId` | The progress session id (UUID) every checkpoint of one launch carries; the play-session key |
| `deviceId` | The installation's progress device id (the same value checkpoints carry) |
| `serverKey`, `accountId` | The namespace's binding, stored on sessions so Stats can filter by the signed-in account |

## 1. Play sessions

### Record

| Field | Type | Notes |
| --- | --- | --- |
| `sessionId` | text, key | Progress session id |
| `namespaceId`, `contentId` | text | As the progress store identifies the game |
| `serverKey`, `accountId` | text | Namespace binding |
| `romId` | int | RomM ROM id |
| `fileId` | int? | First file (disc 0) of the content binding |
| `platformSlug` | text? | RomM platform slug of the install |
| `deviceId` | text | This installation |
| `coreId` | text? | Runtime id (e.g. `fceumm`) |
| `startedAt` | epoch ms | Wall clock when the session became playable (progress selection done) |
| `endedAt` | epoch ms? | Null while open |
| `lastHeartbeatAt` | epoch ms | Last time the session was seen alive |
| `activeDurationMs` | int ms | Running (unpaused, foreground) time only; monotonic clock |
| `endReason` | text? | `resume` \| `state` \| `quit` \| `background`; null while open |

### Lifecycle rules

1. **Start**: insert the row (open, 0 ms active) when progress selection yields a playable
   session and the session coordinator is created. A failed start closes it as `quit` with 0 ms.
2. **Active time** accrues only while the coordinator phase is `RUNNING`. Paused (pause menu,
   sheets, loading), backgrounded, checkpointing-then-paused and closed time is excluded.
3. **Heartbeat**: write `lastHeartbeatAt` and `activeDurationMs` on every running/paused
   transition, every 30 s while running, and after every committed state capture.
4. **End** on the coordinator's terminal phase:
   - `state` — the player made a manual save state and left with under **5 s** of active play
     after it.
   - `resume` — otherwise, the player left and the exit capture committed a resume point
     (capture reason `exit`, has a state, not rejected).
   - `quit` — the player left without a new resume point (state capture failed or unsupported,
     "Exit without latest progress"), or the core stopped.
   - `background` — never closed by the player: the process died (usually in the background).
5. **Process death**: at the next launch, before any new session, every open row not owned by a
   live session is closed at `max(startedAt, lastHeartbeatAt)` with the active time recorded at
   that heartbeat (clamped to the span) and reason `background`.
6. A finished row is immutable (finish only applies to open rows).

### Stats merge rule

Stats show play on this device for the signed-in account:

- Recorded sessions are authoritative. A recorded session counts `activeDurationMs` as its play
  time; rows with 0 ms active are not play and are left out. An open row ends at its heartbeat.
- **Before recording existed**, sessions are derived from committed checkpoints (Stats v1:
  grouping by `sessionId`, gaps capped at the capture interval — 30 s Android, 120 s iOS). A
  derived session counts only if its `sessionId` has no recorded row **and** it started before
  the earliest recorded start on this device (the "table epoch"). With no recorded rows, Stats
  are fully derived, exactly as v1.
- Totals, per-game totals, last played and the streak (consecutive local days with play, ending
  today or yesterday) are computed over the merged list.
- When any derived session is included, Stats add: "Play from before sessions were recorded is
  estimated from automatic saves."

## 2. Save previews (thumbnails)

- **When**: every committed capture that has an emulator state (resume points of every reason,
  manual save states, safety copies). The frame is grabbed when the core is about to be captured
  — the engine is paused, so the surface shows exactly the serialised moment — and written only
  after the checkpoint commits, keyed by its `checkpointId`.
- **Not captured**: game-save-only checkpoints; `restore` captures (the surface still shows the
  pre-restore frame). After a restore, the new checkpoint gets a copy of the **source**
  checkpoint's preview. After a reset there is no preview.
- **Image**: at most 320 px wide, aspect kept, never upscaled, height ≥ 1. Encoded lossy
  (Android WebP q80). Format is platform-local; nothing reads another platform's previews.
- **Storage**: app-private, non-backed-up files at a deterministic path
  `<root>/<namespaceId>/<contentId>/<checkpointId>.<ext>` (ids must be UUIDs). Written atomically
  (stage then rename). No index table.
- **Deletion**: a preview is removed when its checkpoint is no longer kept locally — not in the
  game's history or evicted by retention — checked whenever the saves reader has read a game's
  complete history. Files younger than 10 minutes are never pruned (a reader that looked a moment
  before a commit must not delete the new frame). There is no user-facing state deletion in v3
  yet; when one exists it deletes the preview with the state.
- **Failure** (no surface, surface torn down in the background, copy error, timeout of 3 s,
  disk error): no preview. The save is unaffected. Rows then show the game's cover.

## 3. Saves reader

Per signed-in account, for each progress identity, read `referenceSnapshot`, up to 200 entries of
`history` (newest first), and `publicationStatus`, then classify every checkpoint:

| Kind (copy) | Rule |
| --- | --- |
| **Save state** | Has a state and history marks it manual (reason `manual` or a slot reference) |
| **Resume point** | Has a state, not manual |
| **Game-save data** | No state |

- **Current resume point**: head(s) of `state/resume/*` (v3) or `resume/*` (v2). More than one
  head = conflict ("{Game} has two resume points. Choose which to keep playing.").
- **Slots**: heads of `state/slot/<id>` / `slot/<id>`. The four quick-save slots have stable ids
  `UUID.nameUUIDFromBytes("rommio-slot-N")` (N = 1–4) and show as "Slot N".
- **Loadable**: has a state and is retained locally.
- **Sync status** (from the committing operation's outbox row; a conflicted current reference
  wins):

| Outbox | Copy |
| --- | --- |
| `PUBLISHED` | Synced |
| `LEASED` | Syncing |
| `PENDING` | Waiting to sync |
| `LOCAL_ONLY` | Local only |
| `PARKED_AUTH` | Sign-in needed |
| conflicted reference | Conflict |
| none | (nothing shown) |

- **Recent saves** (Play): every loadable save state (or current slot) plus each game's current
  resume point; earlier resume points and game-save data are left out. Newest first by capture
  time, then current heads first; at most 24 read, 12 shown. Grouped by local calendar day
  ("Today", "Yesterday", then a medium date). Row: preview (or cover), game name, then
  `“Name” · Save state · Slot N · 7:07 PM · This phone · Waiting to sync`.
- **One game** (Game page, Saved progress sheet): current resume point, save states, earlier
  resume points, and game-save data (latest shown, the rest counted). The Game page picks the
  install's content generation (its binding key) and falls back to every generation of the ROM.
- **Save states sheet** (player): the running session's history pages, decorated the same way,
  in three sections: Save states, Resume points, Game-save data (latest 3 shown). Rows with a
  loadable state restore through the coordinator (safety copy first; paired game-save rollback
  confirmation unchanged).

Capture-reason words: manual "Manual", periodic "Automatic", pause "Paused", background "App in
background", exit "Left game", safety "Safety copy", restore "Loaded state", import "Imported".

## 4. Device labelling

- A checkpoint whose `deviceId` equals this installation's id is **"This phone"**, **"This
  tablet"** or **"This device"**; anything else is **"Another device"** (also when this
  installation has no id yet).
- Form factor is used only when unambiguous. Android: foldables (hinge sensor), desktops, TVs and
  cars → "This device"; otherwise smallest width < 600 dp → phone, ≥ 600 dp → tablet. iOS:
  `UIUserInterfaceIdiom` phone/pad; iPhone Duo and Mac → "This device".
- Real device names wait for protocol v4 or RomM's device list.

## 5. Save-state names

| Field | Type |
| --- | --- |
| `namespaceId`, `contentId`, `checkpointId` | key |
| `label` | text |
| `updatedAt` | epoch ms |

- Only checkpoints with a state that the player made (Save states) can be named. Game-save data is
  never named.
- The name belongs to the exact checkpoint. Overwriting a slot creates a new, unnamed checkpoint;
  the named one stays in history with its name.
- Input is trimmed, runs of whitespace collapse to one space, at most 60 characters. An empty
  name removes the row.
- Copy: dialog "Name this save state" / "Rename save state", field "Name", hint "Only on this
  device. Leave empty to remove the name.", actions "Save" / "Cancel". Recent saves quote it:
  `“Before the boss”`.

## 6. RomM metadata

Decoded from RomM 5.2 (`RomSchema`/`SimpleRomSchema`/`DetailedRomSchema`); every field optional:

| Source | Stored as | Rule |
| --- | --- | --- |
| `metadatum.first_release_date` | epoch ms | Number, numeric string or ISO date. \|value\| > 100 000 000 000 → ms, else seconds. 0 → none |
| `metadatum.genres`, `franchises`, `companies` | string lists | Strings or `{name}` objects; trimmed, blanks dropped, de-duplicated |
| `metadatum.publishers`, `developers` | string lists | Post-5.2 servers only |
| `metadatum.player_count` | string | As sent (`"1-2"` shows as "1–2"); `0`/blank → none |
| `metadatum.average_rating` | number 0–100 | ≤ 0 → none; shown rounded, clamped to 100 |
| `merged_screenshots` | path list | Server-relative; resolved against the server like covers |
| `regions`, `languages` | string lists | |

- **Leniency**: a field of the wrong type is ignored on its own; it never fails the payload.
- **Partial payloads**: a payload without `metadatum` keeps the stored metadatum fields; without
  `merged_screenshots` / `regions` / `languages`, keeps those. A payload with a field replaces it.
- **Release year** is the UTC calendar year of the release date.
- **Game page details** (plain text, in this order, empty ones left out): Released (year),
  Genre/Genres, Publisher (explicit publishers) or Companies, Developer, Series (franchises),
  Players, Rating ("86 / 100"), Region(s), Language(s). Then a Screenshots row.
- **Library rows**: release year after the platform (e.g. "Super Nintendo · 1994"), on game tiles
  and search results. Aurora's era browsing (Phase 3) groups by this year.

## Android implementation

| Area | Files |
| --- | --- |
| Tables, DAO, migration 13 → 14 | `data/local/PlayDataTables.kt` (`play_sessions`, `save_state_labels`), `data/local/AppDatabase.kt` (`MIGRATION_13_14`, also `cached_roms.metadataJson TEXT NOT NULL DEFAULT '{}'`) |
| Session rules | `domain/playdata/PlaySessionRules.kt` (`PlaySessionEndReason`, `ActivePlayClock`, abandoned close) |
| Hooks | `domain/playdata/PlayDataHooks.kt`; `ApplicationProgressSessions(playData = …)` begins a `PlayDataSession` when a `Ready` session starts, passes coordinator statuses to it, wraps the engine only to observe `captureMemorySnapshot(includeState)` timing, and reports restores. `ProgressSessionCoordinator(onCommitted)` / `ProgressLaunchPlanner.start(onCommitted)` report each commit in order on the core lane (observer exceptions are swallowed). |
| Recorder and previews | `data/playdata/LocalPlayData.kt` (one serial IO lane; closes abandoned sessions at construction), `data/playdata/SurfaceFrameGrabber.kt` (`PixelCopy` from the player's `GLRetroView` straight into a ≤ 320 px bitmap), `data/playdata/SaveThumbnailStore.kt` (`save-previews/` under `noBackupFilesDir`) |
| Saves reader | `domain/playdata/SavedProgress.kt` (pure rules), `data/playdata/SavedProgressRepository.kt` (store reads, labels, previews, prune, `DeviceFormFactors`) |
| Stats | `domain/stats/PlayStats.kt` (`PlayStatsCalculator.merge`), `data/stats/PlayActivityRepository.kt` |
| Metadata | `model/RomMetadata.kt`, `model/RommModels.kt` (`RomDto` lenient fields), `data/repository/CatalogCache.kt` (merge on write), `OfflineCacheJsonCodec` |
| Pages | Play (`ui/screen/play`: Recent saves replaces Recent sessions), Stats (end reasons, estimate note), Game (`ui/screen/game/GameDetailSections.kt`: Saved progress from the store, Details, Screenshots, Saved progress sheet with rename), player Save states sheet (`ui/screen/player/ProgressPlayerPanels.kt`), shared `ui/component/SaveViews.kt` |

Test tags added: `play.recentSaves`, `play.recentSave`, `game.savedProgress`,
`game.viewSavedProgress`, `game.details`, `game.screenshots`, `game.saves.sheet`,
`game.saves.row`, `game.saves.rename`, `game.saves.resume`, `game.saves.renameField`,
`game.saves.renameSave`, `player.states.list`, `player.states.row`, `player.states.rename`,
`player.states.renameField`, `player.states.renameSave`. Existing `player.*` tags and pause-sheet
strings are unchanged (the Save states sheet keeps "Manual · …" in manual rows).

Tests: JVM `PlaySessionRulesTest`, `SavedProgressRulesTest`, `RomMetadataTest`,
`ThumbnailSizingTest`, `PlayStatsCalculatorTest` (merge); instrumented `PlayDataMigrationTest`
(real v13 → v14 with Room schema validation), `LocalPlayDataTest` (recording, process death,
previews), `PlayerScreenProgressTest` (real player: preview written, rename, session row, saves
reader). `ThemeUnderTest` lets UI instrumentation run under any theme:
`-e rommioTheme riso -e rommioScheme dark` (or `-Pandroid.testInstrumentationRunnerArguments.…`).

Final round (Android, 2026-09-24), still read-only: `GameSaves.conflict` reports two or more
current heads of the resume reference, else of the game-save reference (`game/current`, v2
`save/current`), for the Conflict sheet (iOS's `resume` / `gameSave` kinds; slot conflicts don't
block a launch on Android and aren't offered). Entries carry `isCurrentGameSave` and `foreignState`
(a state made by another platform's player, from the checkpoint's `stateCompatibility.platform`);
`GameSaves.launchOptions` is the Launch choice (a resume point and game-save data; the resume
unavailable when foreign or not kept here). `ConflictToPlayerTest` covers the reader-to-player path.

## iOS implementation

| Area | Files (`ios/Sources/`) |
| --- | --- |
| Tables, records, migration | `RommioFoundation/AppDatabase.swift` (`v7_play_data`: `play_sessions`, `save_state_labels`, same columns and indexes as Android's Room entities), `RommioFoundation/PlayData/PlayDataStore.swift` (`PlaySessionRecord`, `SaveStateLabelRecord`, `PlayDataStore`) |
| Session rules | `RommioFoundation/PlayData/PlaySessionRules.swift` (`PlaySessionEndReason`, `PlaySessionRules`, `ActivePlayClock`) |
| Recorder and previews | `RommioFoundation/PlayData/LocalPlayData.swift` (`PlayDataRecording`/`PlayDataSession`, one serial lane, closes abandoned rows at construction), `RommioFoundation/PlayData/SaveThumbnailStore.swift` (`ThumbnailSizing`, JPEG q0.8 via ImageIO, `SavePreviews/` beside the library, excluded from backups) |
| Hooks | `RommioPlayerKit/ProgressSessionOwner.swift` begins the play session when `launch` runs progress selection, reports running/paused on every engine pause change, the frame at each capture (engine paused), every commit in order, restores and the terminal phase (`exit` → closed with the exit capture's result; a failed start that closes the session, a failed rollback, or the player dismissed without leaving → stopped). `ProgressPlayerEngine.previewFrame()`; `IOSLibretroPlayerEngine` copies its latest software frame (made into an image later) or reads back a hardware texture at once (`PlayerPreviewFrames`) |
| Saves reader | `RommioFoundation/PlayData/SavedProgress.swift` (pure rules, `DeviceFormFactor`), `RommioFoundation/PlayData/SavedProgressReader.swift` (store reads, labels, previews, prune, conflicts) |
| Stats | `RommioFoundation/PlayStats.swift` (`PlayStatsDerivation.merge`, `summarize`), `RommioUI/PageFeatureModels.swift` (`StatsFeatureModel`) |
| Metadata | `RommioContract/RomMetadata.swift` (`LenientJSON`, `RomMetadata`, `RomMetadataFields`), `RommioContract/RommModels.swift` (`RomDTO.metadata`, cached as `rommio_metadata`), `RommioFoundation/LibraryRepository.swift` (merge on write) |
| Pages | Play (Recent saves: `RommioUI/Pages/Play/RecentSaves.swift`; Conflict: `ConflictSheet.swift`), Game (`Pages/Game/*`: saves timeline / Where you left off from the reader, Details, Screenshots, `SavedProgressSheet.swift`), Stats (end reasons, footnote), Library (Aurora eras), player Save states sheet (`RommioUI/ProgressPlayerPanel.swift`), shared `RommioUI/Components/SaveViews.swift` |

Identifiers added (Android's names where this document lists them): `play.recentSaves`,
`play.recentSave`, `game.savedProgress`, `game.details`, `game.screenshots`, `game.saves.sheet`,
`game.saves.row`, `game.saves.rename`, `game.saves.resume`, `game.saves.renameSave`,
`player.states.list`, `player.states.row`, `player.states.rename`, `player.states.renameSave`,
plus the Conflict sheet's (`conflict.*`, `game.chooseResume`, `play.conflict.<romId>`) and
`library.eras` / `library.era.<id>` from [theme-layouts.md](theme-layouts.md). The existing
Game page button keeps `gameDetail.browseStates`, and `player.*` identifiers and pause-sheet
strings are unchanged (the pause tile "Saved progress" opens the Save states sheet).

Tests: `RommioFoundationTests/PlayDataTests` (v6 → v7 migration of a real older schema, end-reason
rules, abandoned close, `ActivePlayClock`, recorder heartbeats and end reasons with injected clocks,
process death, preview keying/sizing/restore copy/failure, pruning, names, device labels, reader
rules), `PlayDataSessionTests` (a real fceumm session through `ProgressSessionOwner`: session row
and end reason, previews for state captures and the restore copy, names, `gameSaves`/`recent`/
`describe`, a two-head conflict and its resolution), `PlayStatsMergeTests`,
`RommioContractTests/RomMetadataTests`, `RommioUITests/PlayDataPresentationTests`.

iOS specifics and deviations:

- **Device labels**: "This iPhone" / "This iPad" where the rules say phone / tablet; iPhone Duo
  (a phone whose screen's short side is at least 460 pt, as the player stage recognises it) and
  Mac say "This device".
- **Captures**: an iOS capture commits two checkpoints — game-save data (`game/current`) and,
  when a state is taken, the state (`state/resume/<compatibility>` and, for a save state, its
  slot). Only the state checkpoint gets a preview. A manual save state is also the current
  resume point until the next capture, so the Game page may show a save state as its resume point.
- **Slots**: iOS makes a new slot id for every save state; "Slot N" appears only for Android's
  four quick-save ids (`nameUUIDFromBytes("rommio-slot-N")`, reproduced exactly).
- **Retention**: iOS keeps every checkpoint of a game's history locally (no eviction), so every
  checkpoint with a state is loadable by this rule (the Save states sheet still disables states
  made by another core build). The reader reads the newest 200 history entries and prunes previews
  only after reading a game's complete history; previews of identities no longer in the store are
  pruned too (10-minute grace for both).
- **Resume conflict**: iOS keys resume references by core compatibility, so two current resume
  points of different cores are not a conflict; `resumeConflict` means a conflicted resume
  reference (Android counts current resume heads).
- **Format**: previews are JPEG q0.8 (Android WebP q80); hardware-rendered cores (Azahar/Vulkan)
  read the texture back at capture time.
- **Store API names**: iOS's `references`, `history` and `outbox` are the reads Android calls
  `referenceSnapshot`, `history` and `publicationStatus`; history entries now carry the committing
  operation id and the manual flag (read-only additions).
- **Rename field**: the name is typed into a native alert; its text field has no identifier on iOS
  (SwiftUI drops it inside alerts). The Save button keeps `….renameSave`.
- **Stats**: the table is read after the recorder's lane is flushed; with no recorded rows the
  result equals the v1 derivation exactly (including its checkpoint-day streak).
