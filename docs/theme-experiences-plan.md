# Aurora and Riso themes: implementation plan

Status: proposal, 2026-09-23. Source designs: the round-2 Aurora and Riso
directions in [design/studio](../design/studio/README.md).

## Context

Aurora and Riso were chosen from the five studio directions. Both ship in the
Android and iOS apps, with a theme selector in Settings, at feature parity (see
[AGENTS.md](../AGENTS.md)). They replace the current look entirely.

The studio versions each had their own tabs and pages. The app instead has one
navigation structure for both themes — tabs **Play · Library · Search · Stats ·
Settings** and the same page list — and the themes differ in typography,
materials, art treatment and the layout of each page.

What the code gives us today:

- **Android** (`app/.../ui`): `RommNativeTheme` sets eight colour roles and
  nothing else; there is no typography, no bundled font, no appearance
  preference, and 132 lines use `Brand*` colours directly. Tabs are one global
  list (`ui/navigation/AppDestination.kt`) rendered inline by `AppShell.kt`.
  Screens take navigation as lambdas, so they can be re-hosted.
- **iOS** (`ios/Sources/RommioUI`): every app screen and shared component is a
  `private struct` in `RommioRootView.swift` (2,366 lines). There are no tokens,
  fonts or asset catalogs, and `RommioUI` declares no resources. Tabs are one
  `TabView` in `AuthenticatedShell` with `.sidebarAdaptable`. Feature models
  (`ShellFeatureModels.swift`, `DetailFeatureModels.swift`) are separate from
  views and can be reused as-is.
- **Player (both)**: `PlayerVisualTheme` is the only theme type and carries
  colours only. Shapes, glyphs and fonts live inside the same views that handle
  touch input (`ExpansionControlsOverlay` on both platforms). Stage geometry is
  pinned by tests: `PlayerControlDeckLayoutTest`/`ExpansionControlsTest`
  (Android) and `PlayerLayoutParityTests`/`PlayerDuoStageLayoutTests` (iOS).
- **Data**: Stats and the cross-game saves views need data that is not recorded
  on either platform — play sessions, save-state thumbnails, device names, slot
  labels — and RomM metadata beyond name, summary and cover is dropped at decode
  (no year, genre, publisher, rating or screenshots). Save sync is a strict
  format (protocol v3); anything that must sync (thumbnails, labels, device
  names) needs a v4. Local-only additions need no protocol change.

## Decisions

1. **Themes are presentation only.** One data layer, one set of feature models
   and view models. A theme supplies tokens, components and its layout of each
   page. No persistence changes to support theming.
2. **One navigation structure for both themes.** The same tabs, the same pages,
   reached from the same places, presented the same way (pushed page, sheet or
   full screen). Changing theme never changes navigation, deep links, strings
   or test selectors — only how each page looks and is laid out.
3. **Two themes, no Classic.** The current look is replaced. **Aurora is the
   default** (its system-glass feel is closest to both platforms' defaults);
   Riso is the alternative. Each has light and dark.
4. **Plain language** (glossary below). Identity comes from type, colour and
   composition, not from renamed features.
5. **Local data first.** Stats and cross-game saves ship on data recorded on this
   device; syncing thumbnails, labels and device names waits for a protocol v4.
6. **iPhone Duo player rules from the studio become the app's rules**: controls
   never overlap a game screen, clusters sit low, the game is never on one half
   with controls on the other.

## Copy glossary

| Concept | Use | Not |
| --- | --- | --- |
| Tabs | Play · Library · Search · Stats · Settings | Home, Issue, Index, Journal, Moments, Arcade, Console |
| Platform | Platform (e.g. "Super Nintendo") | Chapter |
| Collection | Collection | Series, Reel, Cabinet |
| Transfers | Downloads | Print queue, Arriving, Loading bay |
| Resume point, save state, game-save data | as in [save-sync-spec §11](save-sync-spec.md) | Moment, memory, proof |
| Saves across games | Recent saves | Moments, memories |
| Session list | Recent sessions | Journal, diary |
| Conflict prompt | "EarthBound has two resume points. Choose which to keep playing." | Editor's note, two moments |
| Continue playing | Continue playing | Rooms, lead story |

Decorative labels that don't name a feature ("The lead", "No. 39", "Also in
play") are dropped.

## Page list (identical in both themes)

| Area | Page | Reached from | Presented as |
| --- | --- | --- | --- |
| Setup | Welcome | first launch | full screen, no tabs |
| | Connect (server address, saved and nearby servers) | Welcome | pushed |
| | Access options | Connect | sheet |
| | Setup code (QR) | Welcome, Connect | sheet |
| | Sign in (password or single sign-on) | Connect | pushed |
| | Browser sign-in | Connect, Sign in | system web auth |
| | First sync | Sign in | pushed, then into Play |
| **Play** tab | Play | tab | root |
| | Downloads | Play toolbar (queue badge) | pushed |
| | Conflict | Play, Game | sheet |
| **Library** tab | Library (platforms by tier, collections) | tab | root; split view on regular width |
| | Platform | Library | pushed (detail column when split) |
| | Collection | Library | pushed (detail column when split) |
| | Game | Play, Library, Search, Stats | pushed |
| | Saved progress (all states and history for one game) | Game | sheet |
| **Search** tab | Search | tab | root |
| **Stats** tab | Stats | tab | root |
| **Settings** tab | Settings (account, sync, Appearance, storage) | tab | root |
| | Profiles and servers | Settings | pushed |
| | Player and controls | Settings | pushed |
| | Cores and BIOS | Settings | pushed |
| | Backup and recovery | Settings | pushed |
| Player | In game | Play, Game, Stats | full screen |
| | Pause | In game | sheet (floating inspector on Duo open) |
| | Save states | Pause | sheet |
| | Controls | Pause | sheet |
| | Launch choice (resume, game save, branch) | Play, Game | sheet |

Existing screens keep their jobs and move into this structure: Home becomes
Play; Collections and Downloads leave the tab bar (Collections into Library,
Downloads behind a Play toolbar button); legacy recovery, firmware import and
migration transfer sit under Settings › Backup and recovery and Cores and BIOS.

### How the themes differ per page

| Page | Aurora | Riso |
| --- | --- | --- |
| Play | paged Continue playing lit by the game; recent saves as a lit gallery by day | resume as the lead, other games in progress, a pinned collection (e.g. "Finish in 2026"), recent saves as a list |
| Library | browse by era with a cover row; platforms and tiers as filters | platforms grouped by tier with counts; A–Z list per platform |
| Search | results ranked by recent play, best match first | list-style results with related suggestions |
| Stats | summary cards and time by game, lit by the top game | month summary, then recent sessions by day |
| Game | art-forward hero with the game's saves as a timeline | article layout: title, where you left off, details box |
| Settings, sheets, setup | restyled components on glass | restyled components on paper |
| Player controls | frosted controls in the game's light | flat ink shapes on black |

## Architecture

### Theme model (both platforms)

A theme is a value with:

- **Tokens** for light and dark: colours (ground, raised, ink, muted, tint,
  sync-state colours), type roles (display, text, data), radii, spacing,
  materials. The appearance setting (System / Light / Dark) picks the set.
- **Components**: section header, list row, game tile/cover, resume card, sync
  badge, support-tier badge, primary button, progress bar, stat card. Pages call
  components, never raw colours.
- **Page layouts**: where the two themes compose a page differently (table
  above), the page has one view model and two stateless layouts; everything else
  is one layout with themed components.
- **Player style**: control shapes, legend font, fills and borders (extends
  today's `PlayerVisualTheme`; OLED black and console colours keep working).
- **Art treatments**: Aurora ambient light (palette from the cover, blurred
  backdrop); Riso two-ink cover print (colour matrix plus multiply/screen
  blend). Gameplay frames and save-state previews are never treated.

On iOS 26 the Search tab uses the system search role, which the tab bar draws as
a separate button at the trailing end (Play · Library · Stats · Settings, then
Search); on the iPhone Duo vertical bar it sits below the other tabs. Android's
navigation bar keeps the listed order.

### Appearance setting

Settings › Appearance: **Theme** (Aurora, Riso) and **Appearance** (System,
Light, Dark), with a live preview. Device-local, not part of backup bundles.

- Android: `AppearancePreferencesStore` (DataStore, own file) next to
  `data/input/PlayerControlsPreferencesStore.kt`, exposed through
  `AppContainer`; read in `MainActivity` and passed to the theme.
- iOS: `UserDefaults` keys (`appearance.theme`, `appearance.scheme`) behind an
  `AppearanceStore` in `RommioFoundation`; no GRDB migration.

## Work plan

Sizes are relative (S < M < L < XL) and per platform. Each phase lands on
Android and iOS together.

### Phase 1 — Foundations (M)

- **Android**: rebuild `ui/theme/Theme.kt` around a `RommioTheme` value and a
  `LocalRommioTheme` CompositionLocal; derive a complete Material `ColorScheme`
  and `Typography` from tokens (fixes today's near-identical light scheme);
  bundle fonts under `res/font`; replace the 132 direct `Brand*` uses in
  `AppShell.kt`, `ui/component/AppChrome.kt`, `LibraryCards.kt`,
  `GameDetailScreen.kt`, `OnboardingFrame.kt` with tokens and components.
- **iOS**: split `RommioRootView.swift` into one file per page plus
  `Components/` (components become `internal`); add a `Theme` environment
  value; add `resources:` to the `RommioUI` target with bundled fonts registered
  at launch (`CTFontManagerRegisterFontsForURL`); add the asset catalog the
  project already references (`AccentColor`, `AppIcon`).
- **Both**: appearance setting; fonts (all SIL OFL: Bricolage Grotesque,
  Figtree, Big Shoulders Display, Newsreader, IBM Plex Mono) added to
  `THIRD_PARTY_NOTICES.md`.
- **Image pipeline (iOS)**: replace bare `AsyncImage` with a cached loader that
  uses the same request decoration as `RommAPIClient`; both themes lean on art.

Exit: the app runs on Aurora tokens with today's pages.

### Phase 2 — Navigation structure and page list (M)

- **Android**: `TopLevelDestinations` becomes Play, Library, Search, Stats,
  Settings; `AppShell.kt` starts on Play; Collections and Downloads become
  pushed routes; new Search and Stats destinations; Settings sub-pages split
  out of `SettingsScreen.kt`.
- **iOS**: `ShellTab` (`RommioAppModel.swift`) becomes `.play, .library,
  .search, .stats, .settings`; `AuthenticatedShell` keeps `.sidebarAdaptable`
  with Search as `Tab(role: .search)`; Library stays a `NavigationSplitView`
  (narrow list, shared background on the open Duo); Collections and Downloads
  become pushed pages.
- **Stats v1** from data that exists: sessions derived by grouping existing
  checkpoints by session id (durations within 30 s on Android, 120 s on iOS),
  giving playtime, last played, sessions and streak.

Exit: every page in the list exists on both platforms, styled by Aurora.

### Phase 3 — Both themes, page by page (L)

- Aurora and Riso components, and the two layouts for Play, Library, Search,
  Stats and Game; shared pages restyled through components.
- Art treatments: palette extraction (Android `androidx.palette`; iOS Core
  Image); blurred backdrops (`RenderEffect` on API 31+, gradient fallback on
  26–30; SwiftUI blur and materials, `.glassEffect` behind
  `#available(iOS 26, *)`); Riso two-ink covers (Compose `ColorMatrix` +
  `BlendMode`; Core Image colour matrix + blend).
- Player styling: extend `PlayerVisualTheme` with shape, legend font and glyph
  set; move drawing out of the input views (`ExpansionControlsOverlay.kt`
  `ConsoleTouchSurface`/`ConsoleDPad`; iOS `ConsoleInputView`/`DPadInputView`)
  into a style renderer so touch handling and test tags don't change. Pause,
  save states and controls sheets take theme colours and type.
- iPhone Duo open: one canvas per the studio rules — shared background, heroes
  and headers across the fold, list/detail only where real.
- The theme selector appears in Settings when both themes cover every page.

### Phase 4 — Data for Stats and saves (XL, mostly shared design)

All local, no protocol change:

1. **Play sessions table** written at session start and end (game, device id,
   start, duration, how it ended: resume, state, quit, background). Android
   hooks `ProgressSessionCoordinator`/`ApplicationProgressSessions`; iOS hooks
   `PlayerFeatureModel`/`ProgressSessionOwner`. Replaces the Stats derivation.
2. **Save-state thumbnails**, stored locally keyed by checkpoint id. iOS: encode
   the frame the engine already keeps (`IOSLibretroPlayerEngine`,
   `PlayerFrameImage`). Android: new frame capture (libretrodroid has no
   screenshot call) — the largest single item. Closes backlog U15.
3. **Cross-game saves reader**: read-only access to Android's private
   `ProgressStore` (`identities`, `referenceSnapshot`, `history`,
   `publicationStatus`); iOS's store is already public. Feeds Recent saves.
4. **Device labelling**: "This phone" vs "Another device" from the local device
   id; real device names wait for v4 or RomM's device list.
5. **Slot names**: optional user labels in a local table keyed by checkpoint.
6. **RomM metadata**: decode release date, genres, publisher/companies, rating,
   screenshots (confirm field names against RomM 5.2); Android needs a Room
   migration, iOS new optional fields in the cached JSON. Enables Aurora's era
   browsing and richer Game pages.

Recent saves shows text rows until thumbnails land; Aurora's Library shows
platforms and tiers until release years land.

### Phase 5 — iPhone Duo player alignment (M, iOS only; can go first)

`resolvePlayerStageLayout` (`RommioPlayerKit/PlayerViewportLayout.swift`)
already keeps controls off the game, but its pad mode puts the game on one half
and a full pad on the other (automatic in book pose, or via "Game and controls
side by side"). Replace it with the studio rules: side rails with clusters low
(landscape), controls in a band under the game (portrait), DS screens stacked
with a bottom control band. Remove the `player.splitPad` setting, update
`PlayerDuoStageLayoutTests` and `PlayerLayoutParityTests`, and update
[iphone-duo-plan.md](iphone-duo-plan.md) (Phase 4 item 21) and
[ios-player-ui-validation.md](ios-player-ui-validation.md). Android has no Duo
work (per the Duo plan).

## Risks

- **No fallback look**: the current design is gone after Phase 1, so Phases 1–3
  should land on a branch or behind a build flag until the pages are complete.
- **Android frame capture** for thumbnails may need native work in the pinned
  core path; Recent saves stays text-only without it.
- **Blur on Android 8–11** (API 26–30) falls back to gradients; Aurora looks
  flatter there.
- **Strict progress decoder**: nothing here writes new fields into synced
  checkpoints; a v4 is a separate project.
- **Test selectors**: keep `player.*` tags/identifiers and pause-sheet strings;
  shared navigation and copy keep UI tests theme-independent, but Phase 2
  renames tabs, so `AuthGateRoutingTest` and shell accessibility ids change.
- **iOS build**: the three iOS 27.1 call sites still need the 27.1 SDK.

## Verification

- Android: `./gradlew assembleDebug`, unit tests, `ExpansionControlsTest` and
  `PlayerScreenProgressTest` under each theme; screenshot pass per theme × light
  and dark on phone and tablet emulators (`scripts/android/`).
- iOS: `python3 scripts/ios/build_pinned_core.py --sdk macosx --arch "$(uname
  -m)"`, `swift test --package-path ios` (incl. `PlayerVisualThemeTests`,
  `PlayerLayoutParityTests`, `PlayerDuoStageLayoutTests`), simulator runs per
  theme × appearance on iPhone and iPhone Duo (closed, open landscape, open
  portrait) following `ios/README.md`.
- Walk the page list on each platform and theme; compare each page with its
  studio mockup (`design/studio/tools/shoot.sh shot-<theme>-<screen>-<device>`).
