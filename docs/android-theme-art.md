# Android: theme components, art treatments and player style

Status: implemented 2026-09-23 (Phase 3 of [theme-experiences-plan.md](theme-experiences-plan.md)).
Android run 4 aligned the art, components and every page with the cross-platform spec
[theme-layouts.md](theme-layouts.md); the sections below say how Android implements each rule
and where it differs. iOS qualification is reported separately.

## Components

Pages call the same components in both themes; each component reads `LocalRommioTheme` and draws
its own variant (`ui/component/`).

| Component | Aurora | Riso |
| --- | --- | --- |
| `GlassSurface`, `GlassBox`, `Modifier.themedSurface` | spec §1.6: on API 31+ the page field blurred under the surface (`GlassBackdrop.kt`: the field records itself into a `GraphicsLayer` as it draws; one blurred copy, `BlurEffect(material.glassBlur)` worked out at quarter resolution, is cached in an offscreen layer and recorded again only when the field or its size changes; each top-level glass surface draws its window onto that copy, clipped to its shape and moved by layer properties as it scrolls; surfaces in another window, e.g. sheets, and nested surfaces skip it), then translucent `surface`; below API 31 `surface` over the field. Edge (0.5 dp white 70 %/8 % keyline, 1 dp top rim 95 %/16 % → clear by 12 %) and shadow (black 7 % r18 y9 / 34 % r20 y12, a Gaussian `setShadowLayer` clipped outside the shape on API 28+, layered fallback below) | white `surface` block with a 1.5 dp ink keyline |
| nested surface (`LocalInCard`) | no second shadow | no keyline — cards never stack borders |
| `ListGroup`, `ListRow`, `ListDivider` | rows on one glass card, inset 0.5 dp hairlines | paper rows under a 1 dp ink rule, full-width hairlines, flush with the text column (`LocalRowInset` = 0) |
| label…value rows (`ListRow(value=…)`, `LeaderLine`, `LeaderRow`) | label and value at either end | dot leaders on the baseline, mono value (`.ldr`) |
| `SectionHeader` | small caps, secondary ink | display caps + 1.5 dp ink rule to the right + mono meta (`.sec`) |
| `Kicker` | small caps + quieter detail (`.a-kicker`) | mono caps in an ink block + detail (`.kick`) |
| `PrimaryButton` | ink capsule, top highlight, glow in the page's `k1` | ink block, display caps, pink plate 2 × misregistration behind (`.rb.ink`) |
| `SecondaryButton` | glass capsule | 2 dp ink outline (`.rb.line`); destructive on the pink plate (`.rb.pink`) |
| `TextAction` | tint text | underlined display caps (`.rb.quiet`) |
| `SyncBadge` | dot (or spinner) on a 13 % wash of the state colour (`.a-sync`) | stamp: green / yellow / blue chip, empty chip for local, pink fill for conflict (`.stamp`) |
| `Tag` | glass pill (`.a-tag`) | stamp, chip when it carries a tone |
| `SupportTierBadge` | glass pill with icon (`.a-tier`) | touch solid ink, controller outline, browse-only dashed (`.tier`) |
| `RommioProgressBar` | 5 dp track, `k2`→`k1` fill with a halo | 14 dp halftone track in an ink outline, blue fill (`.bar`) |
| `RowIcon`, `ToolbarIconButton`, `FilterPill`, `SegmentedChoice` | filled tiles, glass capsules | ink-outlined squares and blocks |
| `GameCover` | 14 dp radius, soft shadow, faint rim | two-ink print inside an ink keyline |

Riso's yellow is never text on paper: waiting states are stamps with a yellow chip. Text set in
capitals by a type role goes through `CasedText`: the capitals are drawn, but semantics (screen
readers, UI tests) keep the original words, so "Save snapshot" stays "Save snapshot".

Stock Material `Button`/`OutlinedButton` calls pass the theme's button shape (Material 3 buttons
are always full-round otherwise).

## Aurora ambient light

- `ui/art/AuroraLight.kt` is a line-for-line port of `aurora.js` (`toHsl`, `toHex`, `chroma`,
  `yellowish`, `keyColour`, `sources`, `glow`, `lights`) using `AuroraArtTokens`; constants the
  studio writes inline (grey below saturation 0.1, accent weight 1.15, yellow tint weight 0.3,
  near-grey 0.12/0.1, yellow-accent chroma 0.12, light marks S 0.55–0.95 L 0.46–0.54) are named
  in the object. `AuroraLightTest` pins every output (l1–l3, k1–k3, tint, bg, light and dark) for
  nine palettes and two multi-game cases to the values the studio script prints under node; the
  first five palettes are the ones iOS pins in `ArtTreatmentTests`, with identical expectations.
- `ui/art/CoverPalette.kt` is spec §1.1 exactly: 96 px decode (Coil, `Scale.FIT`, no hardware
  bitmaps), exact area-weighted average to at most 24 × 24 on premultiplied values, cells with
  alpha ≤ 16 dropped, unpremultiplied and rounded to 8 bits; median cut into 8 boxes (largest
  range × count, widest channel, stable median split); 6 k-means passes seeded with the box
  means, distance `2Δr² + 4Δg² + 3Δb²`, ties to the lower index, means quantised; population
  order (ties by hex); accent = share ≥ 2 % maximising `chroma × √share`; base = next four,
  padded; neutral when all five have chroma < 0.06. `CoverArtRulesTest` mirrors iOS
  `ArtTreatmentTests`. Downscaling differs from iOS Lanczos only in the decoder's resampling of
  the 96 px bitmap.
- `ui/art/CoverArt.kt` loads covers through Coil off the main thread, keeps palettes in memory
  and in `cacheDir/cover-palettes/<sha256(url)>.palette.json` (`{"colors":["#rrggbb", …]}`; Coil's
  disk cache is not addressable, so the file sits in its own folder rather than next to the
  image), and caches blurred backdrops and prints.
- `ui/theme/PageLight.kt`: the page's light (spec §1.2) is separate from the base theme.
  `PageLight` swaps `ground`, `tint`, `accent1..3` and adds `mark1..3` (unlit: marks from `tint`,
  `accent2`, `accent3`), with `tintText`, `solidSurfaceStrong` and the lit Material scheme worked
  out once per light. Riso never lights. Read it as `RommioTheme.light`; `LitMaterialTheme` gives
  sheets and dialogs the lit Material scheme. See "Performance" below.
- `ui/component/ArtBackdrop.kt` `PageField(style)` draws spec §1.3 for `room` / `page` / `quiet`
  / `welcome`: ground, the cover baked by `ArtBake` (96 px, CSS saturate matrix, brightness,
  separable Gaussian σ = blur / 603 × width, clamped edges) in the −25 % / −12 %, 150 % × 78 %
  frame with the 55 % → 0 fade, the three radial lights (screen in dark), and the veil. Pages
  name their subject with `LitPage(coverUrl, style)`, which lights the page from it and tells the
  shell's field what to draw.
- Riso paper (§1.4): a 180 px xorshift grain tile (`ArtBake.grainTile`), multiplied at 35 % by
  day, inverted and screened at 50 % by night.

## Riso two-ink print

`ui/art/RisoPrint.kt` is spec §1.5: plate A (pink, `accent1`) from G and plate B (blue,
`accent2`) from R through the single-curve masks (`gain × c − bias`, clamped: light 1.21944 /
1.21606 bias 0.05, dark 1.51980 / 1.48820 bias 0.125) on encoded sRGB; coverage `1 − mask` on
paper (multiply), `mask` on black stock (screen). Only plate B is halftoned: a 15° dot screen,
pitch `clamp(longest side dp / 60, 3, 5)` dp, sharpness 0.7, mixed at 0.55 over 45 % solid, and
offset right by the misregistration and up by half. Full overprint by day is navy (~`#002183`,
pinned in `CoverArtRulesTest`). Covers print at their displayed pixel size; gameplay frames,
save previews and screenshots never do.

## Pages

Every page follows [theme-layouts.md](theme-layouts.md) §3 with one content model per page and an
Aurora and a Riso view where the spec has two layouts (Play, Library, Platform, Search, Stats,
Game). Regular width (≥ 600 dp) moves the tabs to a `NavigationRail` (same `shell.tab.*` tags),
splits Library into a 340 dp list beside the selected Platform/Collection, and switches Play,
Game, Stats and Search to their one-canvas layouts. Rules added from Android (documented in
theme-layouts.md for iOS to follow): Play shows **Recent saves** (Aurora lit gallery / Riso list,
by day) while Recent sessions stay on Stats; the Aurora Game timeline is fed by the saves reader;
Aurora Library has the era ruler by release year; the **Conflict** sheet
(`ResumeConflictSheet`) opens from Play and Game and hands the chosen resume point to the player
(`player/{romId}/{fileId}?keep=<checkpointId>`), whose existing branch prompt applies it.

Known Android differences from theme-layouts.md: glass blur needs API 31+ (surfaces stay
translucent below it); the glowing cover's blurred copy uses `RenderEffect` (API 31+) and is
omitted below; the palette file lives in its own cache folder; Riso's non-prominent icon button
sits on a paper fill so it stays legible over art; the pause sheet keeps Android's existing action
strings; on a regular canvas narrower than the Duo's (e.g. an 800 dp tablet with the rail) the
Riso Play broadsheet's rail narrows to 36 % and the lead keeps its compact sizes, and the Aurora
Recent saves mosaic drops to two or three columns so tiles stay ≥ 180 dp. `DisplayText` and Riso
prints answer intrinsic measurements themselves (`FixedIntrinsics`) so ruled rows sized to their
tallest column can hold them. Browser sign-in stays an in-app web view (see the plan's "Browser
sign-in differs by platform").

### Final round (2026-09-24)

- **Setup code** (`ui/screen/auth/SetupCodeSheet.kt`, `data/setup/`): the one-touch client for
  `setup-service/README.md` — `OneTouchSetupClient` (its own OkHttp client: no cookies, no
  redirects, secrets only in POST bodies; the approval link must be on the approval origin at
  `/a/<code>`; HTTPS only, except a debug build's local mock) and `OneTouchSetupSession` (the iOS
  state machine: start → code + QR (ZXing) + "Approve on This Device" in a Custom Tab → poll every
  `pollIntervalSeconds` → on approval store the service-token profile and check RomM → only then
  `complete`; dismissal cancels, Try Again allocates a new code, expiry after
  `expiresInSeconds`). Release builds use the production service; **debug builds point at a local
  mock** (`http://10.0.2.2:8787`, overridable in `.env.test.local`), so development never reaches
  the real service. From Welcome ("Scan setup code", `welcome.setupCode`) and Connect ("Scan it").
  Tests: `OneTouchSetupSessionTest` (fake transport, injected clock), `OneTouchSetupClientTest`
  (MockWebServer), `SetupCodeSheetTest` (instrumented, fake service).
- **Connect / Sign in** follow iOS: Server address, the Access method row and **Access options**
  sheet (Detect automatically; Direct / Cloudflare login / Service token / Browser login), one
  Continue (discover → store the profile → check RomM → Sign in, or the browser login a protected
  server asks for), Saved servers as a row group, the setup code link, and "Connection details"
  (the former Diagnostics) in a sheet. Welcome → Connect → Sign in are pushed with back buttons.
  Sign in shows the server as a row group; single sign-on reads "Continue with single sign-on".
- **Launch choice** (`ui/component/LaunchChoiceSheet.kt`, `ui/screen/LaunchFlow.kt`): Game
  "Start options" (`game.startOptions`), Play's long-press menu ("Start options"), and before the
  player whenever the saves reader shows the planner would ask: two heads → the Conflict sheet
  (resume points or, new, versions of the game-save data); a resume point this device can't load
  (made by another platform's player, or not kept here) with game-save data → the Launch choice.
  The choice rides the player route (`?start=gameSave` → `ColdBootCurrent`); the session keeps it
  when cloud progress wasn't checked. The player's own prompt remains the fallback (cloud progress
  not checked, a conflict that arrives after launch), drawn with `ThemedDialog` and the same labels:
  "Resume" (`player.start.resume`), "Start with game save" (`player.start.gameSave`), "Keep …" for
  each head of a conflict, plus "Back to library". `LaunchChoiceSheetTest`.
- **Conflict**: counts in the copy and chips ("three", then digits, as iOS), no sync badge on a
  choice, game-save conflicts, and the player's hand-off keyed on `busy` so a choice that arrives
  during another action still applies. `ConflictToPlayerTest` runs it end to end: a real session's
  resume point, a sibling head arriving as another device's synced operation, the saves reader,
  the sheet choosing the older head, and the real player starting from it.
- **Cores and BIOS**: the runtime inventory (`domain/player/CoreInventory.kt`): a row per core
  with a status stamp (Ready / Sets up on first play / Needs BIOS), "Not included in this build
  (N)" for cores this build can't install and your server's browse-only platforms; the Settings
  row reads "N ready · N need BIOS".
- **Formatting and copy** (§4): `Fmt` in `ui/component/Formatters.kt` (grouped counts, sizes as
  `Formatter.formatShortFileSize` writes them — decimal units, "kB", zero "0 kB" — pinned by
  `FormattersTest` and compared with the platform on a device by `FormattersPlatformTest`;
  relative time, day groups, durations, " · "). Controls sheet, toasts and the game-save rollback
  confirmation use the iOS wording; sliders show percentages.
- **Contrast**: `ReadableTint.readableTint` (§1.2a, pinned by `ReadableTintTest`) gives
  `PageLight.tintText` (`RommioTheme.light.tintText`), used for text actions, section actions,
  tint pills, "Sign in again", pause-tile glyphs and cursors; `badText` for error and destructive
  text (Material `error` maps to it).
- **Colours over art** come from `OverArt` (Aurora's night tokens): Recent saves frames, scrims and
  chips, the player toast and scrims, and the light rims on Welcome, Game, Settings and Conflict.

## Player style renderer

`PlayerVisualTheme.style` (`PlayerStyle`) carries the player shape tokens, legend and label type
(`playerLegend`, `playerLabel`), the glyph set (Aurora chevrons, Riso triangles), per-kind colours
(face, shoulder, tertiary, menu, d-pad, pivot, plate) and Aurora's frosted edge.
`PlayerControlRenderer.kt` draws: `PlayerControlGraphic` (face, shoulder, tertiary, menu, stick)
and `PlayerDpadGraphic`; `PlayerStageBackdrop` lights Aurora's stage from the game's cover palette
(OLED black and Riso stay black). The renderer only draws: each control's box is its drawn frame
from the touch model (below). OLED black forces the dark palette. `PlayerStyleResolutionTest`
covers the resolution. Pause, Save states and Controls sheets use the theme's components and type
with their existing strings.

Look ([player-controls.md](player-controls.md), Look):

- **Aurora** frosted controls: `controlFill` (`controlPressed` while held), a soft glass shadow
  (black 55 % r14 y10 by night, `#28143c` 28 % r13 y9 by day), a 0.5 dp keyline and a 1 dp top rim
  fading by 12 %. Pressed controls dip to 94 % over 60 ms. Legends at `playerLegend` scaled from
  a 64 dp face (`size × (d + 24) / 88`: 22 on two-button faces, 20 on diamonds).
- **Console colours** (Aurora) paint the legend and a 2 dp ring, never a wash; the legend is
  stepped in HSL lightness until it reads at 3.5:1 against the frosted face
  (`ReadableTint.legibleOn`, iOS `legibleLegend`). Riso prints each console colour flat in its
  nearest ink by hue (pink, blue, yellow, green; `nearestInk`, as iOS) with the pink plate (blue
  behind pink faces), at the new sizes; its plate prints outside the frame.
- **Opacity** applies to Aurora only and never takes face buttons below 0.5
  (`PlayerStyle.controlAlpha(opacity, face)`). A face fades only its body (shadow, fill, keyline,
  rim); its legend and ring stay at full ink, so the legend keeps 3:1 at the lowest opacity. Riso
  always prints at full ink.

## Touch controls v2

[player-controls.md](player-controls.md) on Android (iOS names kept where they fit):

- **Pure model** (`ui/screen/player/`): `PlayerControlPlan` (a family's d-pad, faces and their
  arrangement, shoulders, sticks, utilities, classified from `ExpansionControls`),
  `PlayerControlMetrics` (the spec's sizes and floors, scale 0.8–1.3), `PlayerControlPlan.place`
  → `PlayerTouchLayout` (every control's frame, the face zone, the chord pair, Menu),
  `StandardStage` / `resolvePlayerStage` (portrait: game at the top 8 dp under the camera
  cutout, full width when it fits and otherwise inside a 12 dp border; the band from 12 dp under
  it, clusters at 55 % of the band 20 dp from the edges, Select · Menu · Start 12 dp above
  the gesture area; landscape: rails either side of a centred game, 6 dp from the cutout, the
  game at full height when they leave room for it, clusters at 60 %, shoulders at
  the top, Select/Start at the feet, Menu under the game or beside Select), and
  `PlayerTouchRules` / `PlayerTouchTracker` (hit regions = drawn shape + 14 dp, nearest centre
  wins, no dead gaps inside a face cluster, the 22 % chord band, rolling within a kind with
  hysteresis, release 40 dp beyond, the d-pad owned by its touch with an 18 % dead zone and 8-way
  sectors with 40° diagonals, sticks owned by their touch until it lifts, key hold counts, ordered
  output).
  `PlayerTouchModelTest` ports the iOS `PlayerTouchModelTests` with the same phone sizes and
  expectations, plus Android phones, a foldable and tablets, for every family with touch controls.
- **One surface** (`PlayerControlsOverlay.kt`): a single `PointerInputModifierNode` over the
  overlay feeds the tracker synchronously; control composables only draw and keep their
  `player.control.*` / `player.dpad` tags, "Pressed"/"Released" state and click actions (for
  TalkBack and tests). Menu stays an ordinary button (`player.menu`). The node shares input with its siblings, so a touch that misses every control
  still reaches the game view (touch-screen cores); controls never overlap the game.
- **Immediate, ordered input**: `PlayerEngine.sendDigital` / `sendMotion` queue presses and
  releases on the core's command queue in call order, with no coroutine per event (the old
  overlays launched one per event). Everything held is released when a touch ends or is
  cancelled, when Pause (or Saved progress, or Controls) opens, on `ON_PAUSE`, on a new layout
  (rotation, size) and when the overlay leaves.
- **Haptics**: `View.performHapticFeedback(VIRTUAL_KEY)` on each button press edge and
  `CLOCK_TICK` on each new d-pad direction (the system's haptic setting still applies). The
  **Haptics** switch (on by default) is in Settings › Player and controls and the in-game
  Controls sheet, stored as `haptics_enabled` in `PlayerControlsPreferencesStore` (device-local;
  kept by the migration bundle's snapshot and restore; older exports read as on).
- **Debug input log**: `PlayerInputLog` (debug builds only) records every output the surface sent
  to the core and each haptic it played; instrumentation tests read it.
- **Portrait shoulders** (revised 2026-09-24): each side's shoulder row sits 16 dp above the top of
  its stack (`PlayerControlAnchors.Shoulders.AboveCluster`), aligned to the stack's outer edge and
  kept inside the rail; landscape keeps them at the top of the rails (`Shoulders.Top`).
- **Per-console layouts** (player-controls.md, "Analog sticks and per-console layouts"):
  `PlayerControlPlan` has the iOS `dpadSlot` (`PRIMARY`, `ABOVE`, `BELOW`, `BESIDE` via
  `compact()`), `leftStickPrimary`, `analogControls` and `RailContent(cluster, above, below,
  beside, shoulderRow)`; stages stack each rail with `stack(up/down)`, cap secondary stick zones
  (`fitted`, down to `minimumSecondaryZone`) before shrinking the controls, and level the primary
  controls (`level`). Nintendo 64: the stick (160 dp zone) in the thumb spot, the 104 dp d-pad
  above it, Z a 92 × 48 tab beside L at the top of the left rail, A 64 with B 56 down-left, the C
  buttons a 44 dp diamond above-right of A (axis buttons on the right stick), Start beside Menu
  bottom centre when Menu fits under the game. 3DS: a circle pad (`circlePad`, left analog)
  added over a 104 dp d-pad, face diamond level with it. PSP: the nub (120 dp zone) under the
  d-pad. PlayStation: sticks, L3 and R3 only with the game's **Analog controls** (Controls sheet ›
  This game, `player.controls.analogControls`, off by default; stored per ROM id in
  `analog_controls_rom_ids`, device-local); the Analog mode button always stays. Every other
  family has no sticks.
- **Touch sticks** (player-controls.md, "Touch sticks"): `PlayerStickProfile` (`forStick`: N64,
  3DS, PSP, PlayStation left and right; `tuned` at the control size) and `PlayerStickModel.gateScale`,
  `output`, `followCentre` and `fullTilt` — an exact port of
  `scripts/touch_stick_reference.py`, pinned by `PlayerTouchModelTest` to every vector the script
  prints (4 decimals, every profile). The tracker floats the centre where the thumb lands (clamped
  so the travel ring stays in the zone), drags it behind a thumb past full travel (on moves, not on
  touch-down), sends a value only when it changes, drops to zero on lift and plays one
  `CLOCK_TICK` on entering full tilt (again only after leaving it). The ring draws at the centre and
  brightens at full tilt (a 2.5 dp `controlInk` rim); the knob (travel × 0.95) sits at the output.
  Per game, the Controls sheet's **Stick** group (consoles with sticks; PlayStation with Analog
  controls on) sets Sensitivity (travel × 1.2 / 1 / 0.8), Dead zone (× 0.6 / 1 / 1.6) and Centre
  (Floating / Fixed: the zone's centre, no follow), with Reset; stored per ROM id as
  `stick_tuning_<id>` (device-local, removed on Reset).
- **Game screens**: `PlayerTouchLayout.gameScreens` holds the game's rect; a touch that lands on it
  goes to the game even where a control's grown hit region reaches over it, and any touch the
  surface doesn't take is left unconsumed for the game view under it.
- **Game view size** (NES frame offset, 2026-09-24): the game view is composed only once the
  platform's controls are known (or after 1.5 s), so its SurfaceView's first size is its real one.
  Composed early, it took the screen's shape and was resized a frame later; the SurfaceView kept
  its first geometry, so the picture was cropped to the left with a black strip beside it. This
  was already at `d814964`.
- Android-only adaptations for screens iOS doesn't have: on a phone narrower than about 375 dp the
  portrait utility row tightens (6 dp gaps, pills down to 30 dp) so many switches (Atari 2600)
  still fit; a foot holding three or more utilities uses 36 dp pills; and
  since the 3DS goes through the phone stage on Android (iOS uses its DS rail stage), primaries
  that can't share a height at the ideal level at the lowest height both allow, and in landscape a
  primary stick's stack gives way in size until they can.
- The old per-button overlays (`ExpansionControlsOverlay`, and the unreachable
  `TouchControlsOverlay`) are gone; `PlayerControlDeckLayout` only places the game and Menu when
  touch controls are off.
- Tests: `PlayerTouchModelTest` (also the stick reference vectors, per-console placement, game
  screens), `PlayerStyleResolutionTest` (unit); `PlayerTouchControlsTest` (a button's edge and the
  gap between A and B register, a d-pad drag off the pad keeps its direction, quick taps never
  leave a button held, rolling, pause/dispose release, haptics on/off, touches that miss every
  control reach the game), `ExpansionControlsTest` (every family, both orientations, mirrored;
  PlayStation with Analog controls on, then off),
  `PlayerScreenProgressTest.touchControlsReachTheCoreInOrder` (the production player and the
  debug input log), `PlayerHapticsPreferenceTest` (haptics, Analog controls and stick tuning per
  game); `PlayerControlsShotsTest` renders review screenshots (the stick consoles in landscape, at
  rest and held at full tilt). Instrumentation runs under all four theme × scheme combinations
  (`-e rommioTheme aurora|riso -e rommioScheme light|dark`).

## Play v2 (Aurora)

[theme-layouts.md](theme-layouts.md) "Aurora (compact), v2": `PlayPage` carries each game's
`platformLine` ("Super Nintendo · 1994"), `captionLine` (its latest resume point — "Automatic
resume · 1 min ago · This phone", or a save state's name or kind — else how it was played),
`previewPath` and `screenSync`, from `SavedProgressRepository.latest`
(`SavedProgressRules.latestByGame`: the current resume point, else the newest loadable save state;
never game-save data), read in the same pass as Recent saves. The carousel (`carousel`, six games)
pages through each game's screen: the preview as captured (nearest-neighbour, at its own aspect on
black, radius 22, 0.5 dp keyline, the frame blurred behind it in the game's light on API 31+),
else the cover on its blurred backdrop; the next page peeks 16 dp; the sync or conflict chip sits
bottom-left in dark glass. The caption, Resume and a 54 dp Details glass circle (info glyph at 20)
follow; "In progress" carries the pager dots and the games beyond the carousel (`more`) as
152 × 114 tiles. Regular width shows the screen (480 wide) beside the caption. Recent saves use
mosaic v2 (`SaveMosaic`, as iOS): the compact rows are built by the model (`PlaySaveDay.rows`);
regular width uses rows of four with a short last row stretched. `PlayV2Test` pins the rules.

## Performance

Navigation was slow for structural reasons, not one hot spot; each is fixed where it starts.
Layouts, strings, test tags and navigation are unchanged (before/after screenshots of every page
in the four theme × scheme combinations).

- **Base theme and page light are separate.** `LocalRommioTheme` (static) holds the base theme
  only: `RommioThemeValue` is `@Immutable` and equal by theme and scheme, so it changes only when
  the user switches them. The page's light is `LocalPageLight` (`compositionLocalOf<PageLight?>`,
  `ui/theme/PageLight.kt`), with its lit colours, `tintText`, marks and lit Material scheme worked
  out once per light (`PageLight.of` keeps recent ones). When a page lights up, only composables
  that read `RommioTheme.light` recompose: the field, lit marks, tint text, prominent buttons.
  `PageLightRecompositionTest` counts compositions to prove it.
- **Pages open lit.** When an Aurora cover decodes (`GameCover`, `RoomCard`),
  `CoverArt.warmPalette` works out its palette on one background-priority thread and keeps it in
  memory and on disk. `rememberCoverPalette` reads memory synchronously, so a page about a game
  whose cover was on screen is lit on its first frame. When the light does change, only the
  field crossfades: `PageField` animates its ground and lights in the draw phase and crossfades
  the blurred cover. The page itself does not animate.
- **Glass blur is recorded once.** The page field records itself into a layer, and one blurred
  copy (quarter resolution, same Gaussian) is cached offscreen. It is recorded again only when
  the field or its size changes. Each glass surface draws its window onto that copy, moved by
  layer translation when it scrolls. Nothing is re-recorded or re-blurred per frame
  (`GlassBackdrop.kt`).
- **Pages are built by their models.** Each page's ViewModel builds its content (`PlatformPage`,
  `CollectionPage`, `SearchPage`, `StatsPage`, `DownloadsPage`, `PlayPage`, `LibraryPage`,
  `GamePage`) on `Dispatchers.Default` from its data, publishes it with `distinctUntilChanged` and
  `stateIn`, and composables take its immutable items. Nothing is sorted, grouped or formatted in
  composition. `PageBuildersTest` pins the builders. `app/compose-stability.conf` tells the
  Compose compiler which model and collection types are immutable, so unchanged sections are
  skipped.
- **Unbounded lists are lazy.** Platform, Collection, Search, Stats, Downloads, Play's Recent
  saves, Library's parts, saved progress and Settings › Cores and BIOS are `LazyColumn`s with keys
  and content types. They keep the same designs: grids are lazy rows of cells, and Riso's A–Z
  index is letter items. In Aurora a row group stays one glass card: `lazyListGroup` makes each
  row an item, and `Modifier.drawCardGroups` draws the card (shadow, blurred field, fill, edge)
  behind its visible rows (`ui/component/LazyGroups.kt`).
- **No disk work on the main thread or per game.**
  - A game's support tier comes from the catalogue (`CoreResolver.supportTier`: the family's tier,
    or Browse only exactly when the full resolution is UNSUPPORTED, as before).
    `CoreSupportTierTest` checks it against the resolver for every platform and file type,
    including a missing core and a failed core setup.
  - The full resolution (`RommRepository.coreSupport`) runs on the IO dispatcher.
  - Readiness identifies each BIOS once per file identity (inode, size, modification time).
    Runtime preparation still reads every byte.
  - Each bundled core manifest is read once, and installed cores stay verified per file identity
    (`CoreArtifactStore`); a launch re-verifies.
  - The Cores and BIOS subtitle is counted in `SettingsViewModel`.
  - The app warms this inventory in the background at launch
    (`AppContainer.warmCoreVerification`).
  - `CoreReadinessCacheTest` pins the caches.

**Measured (2026-09-24).** Setup: the same scripted pass (Play, Downloads, Library, a
170-game Platform, a Game, a Collection, Search, Stats, Settings and its four sub-pages), debug
builds of the code before and after this work, the API 35 emulator with software rendering, and a
mock server. Before and after runs alternated, three each per theme. Medians from
`dumpsys gfxinfo`:

| Theme (dark) | Janky frames | p90 | p95 | p99 | Frames rendered |
| --- | --- | --- | --- | --- | --- |
| Aurora, before → after | 50.2 % → 45.6 % | 77 → 73 ms | 85 → 85 ms | 150 → 109 ms | 2701 → 3182 |
| Riso, before → after | 27.5 % → 27.2 % | 61 → 61 ms | 77 → 81 ms | 150 → 150 ms | 3220 → 3284 |

On this emulator, janky frames are dominated by software GPU draw time. Within one build, runs
vary by up to 4 ms at p90, up to 12 ms at p95 and more at p99 (Riso before: 117–150 ms), so
Riso's changes are within noise. Numbers from a physical device, and from a release build, are still to
be taken.
