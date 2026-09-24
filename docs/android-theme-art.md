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
| `GlassSurface`, `GlassBox`, `Modifier.themedSurface` | spec §1.6: on API 31+ the page field redrawn under the surface and blurred (`GlassBackdrop.kt`: the field records itself into a `GraphicsLayer` as it draws; each top-level glass surface draws that recording again, offset to its own position, clipped to its shape, inside a layer with `BlurEffect(material.glassBlur)`; surfaces in another window, e.g. sheets, and nested surfaces skip it), then translucent `surface`; below API 31 `surface` over the field. Edge (0.5 dp white 70 %/8 % keyline, 1 dp top rim 95 %/16 % → clear by 12 %) and shadow (black 7 % r18 y9 / 34 % r20 y12, a Gaussian `setShadowLayer` clipped outside the shape on API 28+, layered fallback below) | white `surface` block with a 1.5 dp ink keyline |
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
- `ui/theme/Theme.kt`: `RommioThemeValue.lit(lights)` (spec §1.2) swaps `ground`, `tint`,
  `accent1..3` and adds `mark1..3`; unlit values derive marks from `tint`, `accent2`, `accent3`.
  Riso never lights. `ProvideLitTheme` applies it (and the Material scheme) below the shell.
- `ui/component/ArtBackdrop.kt` `PageField(style)` draws spec §1.3 for `room` / `page` / `quiet`
  / `welcome`: ground, the cover baked by `ArtBake` (96 px, CSS saturate matrix, brightness,
  separable Gaussian σ = blur / 603 × width, clamped edges) in the −25 % / −12 %, 150 % × 78 %
  frame with the 55 % → 0 fade, the three radial lights (screen in dark), and the veil. Pages
  name their subject with `PageArtSubject(coverUrl, style)`; the shell lights the page from it.
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
  `RommioThemeValue.tintText`, used for text actions, section actions, tint pills, "Sign in again",
  pause-tile glyphs and cursors; `badText` for error and destructive text (Material `error` maps
  to it).
- **Colours over art** come from `OverArt` (Aurora's night tokens): Recent saves frames, scrims and
  chips, the player toast and scrims, and the light rims on Welcome, Game, Settings and Conflict.

## Player style renderer

`PlayerVisualTheme.style` (`PlayerStyle`) carries the player shape tokens, legend and label type
(`playerLegend`, `playerLabel`), the glyph set (Aurora chevrons, Riso triangles), per-kind colours
(face, shoulder, tertiary, menu, d-pad, pivot, plate) and Aurora's frosted edge.
`PlayerControlRenderer.kt` draws: `PlayerControlGraphic` (face, shoulder, tertiary, menu, stick,
d-pad arm) and `PlayerDpadGraphic`; `PlayerStageBackdrop` lights Aurora's stage from the game's
cover palette (OLED black and Riso stay black). `ExpansionControlsOverlay` and
`TouchControlsOverlay` keep all touch handling, hit areas, geometry, `player.*` tags and
semantics and only hand the renderer a box to draw in. Console colours tint face buttons in both
styles (Riso prints them flat with the pink plate); OLED black forces the dark palette.
`PlayerStyleResolutionTest` covers the resolution; `ExpansionControlsTest` and
`PlayerScreenProgressTest` run under all four theme × scheme combinations
(`-e rommioTheme aurora|riso -e rommioScheme light|dark`). Pause, Save states and Controls
sheets use the theme's components and type with their existing strings.
