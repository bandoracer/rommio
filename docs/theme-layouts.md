# Theme layouts: Aurora and Riso, page by page

Status: Phase 3 of [theme-experiences-plan.md](theme-experiences-plan.md),
implemented on iOS first (2026-09-23). This is the platform-neutral spec the
Android client reproduces. Values are points (dp on Android). Token names are
the roles in [design/tokens/themes.json](../design/tokens/themes.json); type
roles are written `role` (e.g. `display`), colour roles `colour` (e.g.
`inkSecondary`), spacing tokens `spacing.gutter` etc. The studio mockups
(`design/studio/tools/shoot.sh shot-<aurora|riso>-<screen>[-dark|-duo-open|-duo-closed]`)
are the visual reference; copy follows the plan's glossary, not the mockups.

Rules that hold everywhere:

- One navigation structure and one set of strings/identifiers in both themes.
  Pages with **two layouts** (Play, Library, Platform, Search, Stats, Game) build
  one content model and pass it to two stateless views. Every other page is one
  layout whose components pick their variant from the theme.
- Pages never use raw colours; components read tokens.
- Regular width (iPhone Duo open, iPad) is "one canvas": the page background is
  shared across the fold; heroes and headers span it; list/detail only in
  Library.

## 1. Art treatments

### 1.1 Cover palette (Aurora)

Deterministic per cover, cached with the image (memory + a `<sha256(url)>.palette.json`
file next to the disk-cached image).

1. Decode the cover at 96 px (longest side).
2. Area-average to at most 24 × 24 (Lanczos downscale; Android: bitmap scale
   with filtering). Drop cells with alpha ≤ 16/255; unpremultiply.
3. Median cut into 8 boxes: repeatedly split the box with the largest
   (channel range × pixel count) at the median of its widest RGB channel.
4. K-means, 6 passes, seeded with the 8 box means; distance
   `2Δr² + 4Δg² + 3Δb²`; ties go to the lower cluster index. Quantise means to
   8 bits.
5. Sort clusters by population (ties by hex). **Accent** = the cluster with
   share ≥ 2% maximising `chroma × √share` (ties: lower index). **Base** = the
   next four clusters by population excluding the accent (pad with the last).
6. Palette = `[base0, base1, base2, base3, accent]` (the studio's order).

`chroma(h,s,l) = s × (1 − |2l − 1|)`. A palette whose five colours all have
chroma < 0.06 is **neutral**: pages keep the theme's own light (the studio maths
would otherwise tint a grey cover red from hue 0).

### 1.2 Aurora lights (`AuroraLights`)

Exactly the studio's `lights()` (aurora.js), with `AuroraArtTokens`:

| Step | Rule |
| --- | --- |
| HSL | studio `toHsl`/`toHex` (hex rounded to 8 bits, `Math.round`) |
| yellowish | `40 < h < 76` |
| keyColour(p, forTint) | max of `chroma × (index 4 ? 1.15 : 1) × (forTint && yellowish ? 0.3 : 1) × (s < 0.12 ? 0.1 : 1)`, stable |
| sources (one game) | accent = p[4]; rest = p[0..3] by chroma desc (stable); if yellowish(accent) and chroma(rest0) > 0.12 → `[rest0, rest1, accent]`, else `[accent, rest0, rest1]` |
| sources (2–3 games) | keyColour(game, forTint: true) of the first three games, padded with the last |
| l1–l3 (light) | `hsl(h, s < 0.1 ? s × 0.5 : clamp(s, 0.52, 0.9), clamp(0.82 + (l − 0.5) × 0.08, 0.78, 0.87))` |
| l1–l3 (dark) | `hsl(h, s < 0.1 ? s : max(s, 0.55), clamp(l, 0.5, 0.64))` |
| k1–k3 marks (light) | `hsl(h, s < 0.1 ? s : clamp(s, 0.55, 0.95), clamp(l, 0.46, 0.54))`; (dark) = the dark light |
| tint | key = keyColour(first, true); light `hsl(kh, max(ks, 0.6), 0.42)`; dark `hsl(kh, max(ks, 0.62), 0.55)` |
| ground | light `hsl(src0.h, 0.32, 0.963)`; dark `hsl(src1.h, 0.3, 0.048)` |

Pinned by `ArtTreatmentTests` against node output of aurora.js, e.g. palette
`#0b0620 #3a1450 #5d2a6e #1c0c2b #ff7a3d` → light l1 `#fbc5ac`, l2 `#dab3e7`,
l3 `#d3acea`, k1 `#f9601a`, tint `#d64300`, ground `#f9f4f3`; dark l1 `#ff7a3d`,
tint `#ff621a`, ground `#0e0910`. Android must reproduce the same hex values for
the palettes in that test.

A **lit** page replaces, below it: `ground` → lights.ground, `tint` → lights.tint
(also the system tint), `accent1..3` → l1..l3, and adds `mark1..3` = k1..k3
(unlit pages derive marks from `tint`, `accent2`, `accent3` with the mark rule).
Riso never lights a page.

### 1.2a Readable tint (text in the tint colour)

The studio maths above stays pinned for fills, marks, glows and bars. Wherever
the tint colours **text or a meaningful glyph** (text actions, section-header
actions, "Sign in again", tint pills, selected-segment labels, links), use
`readableTint(tint, ground, dark)`:

1. Convert `tint` to HSL (studio `toHsl`).
2. Loop: build the colour from (h, l, s), quantised to 8 bits per channel; stop
   when its WCAG 2 contrast is ≥ 4.5 against `ground` **and** against a 13% wash
   of itself over `ground` (the pill background). Otherwise step l by −0.005 on
   a light ground, +0.005 on a dark ground (at most 400 steps; clamp l to 0…1).
3. Unlit pages use the static token `tint`, which already passes (the tokens were
   retuned for this in `design/tokens/themes.json`, as were `ok`, `wait`,
   `live`, `bad` in Aurora light and every theme's `inkTertiary`).

Pinned values (reference: `readable_tint.py` logic; both platforms test these):

| Case | Scheme | tint | ground | readable tint |
| --- | --- | --- | --- | --- |
| smetroid | light | #d64300 | #f9f4f3 | #b23800 |
| smetroid | dark | #ff621a | #0e0910 | #ff621a |
| chrono | light | #c83c0e | #f9f4f3 | #b5360d |
| linksawak | light | #2bab56 | #f3f9f5 | #1d743a |
| grey | dark | #d34545 | #100909 | #d75858 |
| earthbound | light | #207fb6 | #f3f6f9 | #1b6a98 |
| unlit Aurora | light | #a84010 | #f6f4f1 | #a84010 |

Error and destructive **text** uses the `badText` role (Riso light `#dc007d`),
never `bad`; `bad` stays for fills, chips and glyph washes. Riso never sets text
in `ok` or `wait` (sync states are stamps with ink text).

### 1.3 Aurora page field (`PageBackground`)

Layers, bottom to top:

1. `ground` (lit ground when lit).
2. Blurred cover (lit pages only): the cover at 96 px with
   `saturate(backdropSaturation)`, RGB × `backdropBrightness`, clamp, Gaussian
   σ = `backdropBlur / 603 × imageWidth` (603 = 150% of a 402 pt phone). Frame:
   x −25% of width, y −12% of height, 150% × 78%; alpha fades from 100% at 55% of
   its height to 0 at its bottom; opacity `backdropOpacity` (light 0.26, dark 0.5).
3. Three radial lights l1, l2, l3: ellipse radii (rx, ry) as fractions of the
   page, colour at the centre fading to transparent at `fade` of the radius.
   Opacity `lightOpacity` (0.95 / 0.58); **screen** blend in dark.
4. Veil: vertical gradient of `ground` at the listed opacities.

| Style | Used by | Lights (centre x,y · radii · fade) | Veil (location: ground opacity) |
| --- | --- | --- | --- |
| room | Play (Aurora) | (.26,.24 · .9,.46 · .7) (.96,.06 · .7,.34 · .7) (.4,.86 · 1.1,.4 · .72) | 0:0, .48:0, .76:.55, 1:.88 |
| page | Game, Stats, Library, Platform, Collection, Search results, Downloads | (.08,0 · .8,.3) (.96,.04 · .7,.3) (.5,.26 · 1.1,.26 · .72) | 0:0, .3:.4, .58:.82, .86:1 |
| quiet | Settings and sub-pages, unlit pages | (.1,0 · .8,.26) (.96,.02 · .6,.24) (.5,.2 · 1,.18 · .72); whole field 62% | 0:0, .36:.7, .66:1 |
| welcome | Welcome, Connect, Sign in, First sync, Appearance preview | (.14,.18 · .8,.4) (.94,.3 · .7,.4) (.5,.5 · 1,.44 · .72) | 0:0, .42:.3, .76:.86, 1:1 |

An unlit page always uses `quiet` (except welcome), with the theme's own
`accent1..3` as lights.

### 1.4 Riso paper

`ground` plus a 180 px seeded grain tile (xorshift noise, mostly white with
sparse dark specks) at 35% multiply (light); by night the tile is inverted
(black with light specks) and screened at 50%.

### 1.5 Riso two-ink cover print (`RisoPrinter`)

Every cover in Riso (tiles, rows, heroes) is printed; gameplay frames and
save-state previews never are. Maths on encoded sRGB values (no linearisation).

| Plate | Ink | Source | Light curve (mask) | Dark curve (mask) |
| --- | --- | --- | --- | --- |
| A | `accent1` (pink) | G | `clamp(1.21944 × G − 0.05)` (= 0.7152·1.55·1.1, bias 0.5−0.55) | `clamp(1.51980 × G − 0.125)` (0.7152·1.7·1.25) |
| B | `accent2` (blue) | R | `clamp(1.21606 × R − 0.05)` (0.2126·5.2·1.1) | `clamp(1.48820 × R − 0.125)` (0.2126·5.6·1.25) |

- Coverage: light `1 − mask` (ink where the cover is dark); dark `mask` (inks
  glow where it is bright).
- Halftone plate B: dot screen at 15°, dot pitch `clamp(maxPointSize / 60, 3, 5)`
  points × scale, sharpness 0.7, mixed `RisoArtTokens.halftone` (0.55) dots over
  45% solid.
- Offset plate B by (+misregistration, −misregistration/2) points
  (`RisoArtTokens.misregistration` = 2), i.e. right and up; the uncovered strip
  is blank stock.
- Composite light: `paper × plateA × plateB`, each plate `1 − c × (1 − ink)`;
  paper = `ground`. Dark: black stock `screen` `ink × c` of each plate.
- Full overprint by day is navy (~`#002183`, pinned in tests).

### 1.6 Glass (Aurora surfaces)

Card, row group, chip, button and field backgrounds: on iOS 26 `glassEffect(.regular.tint(surface @ 35%/18%))`
over `surface` at 72% (light) / 90% (dark); before iOS 26 an ultra-thin material
plus `surface`. Edge: 0.5 pt white 70%/8% keyline plus a 1 pt white top rim
(95%/16% → clear by 12% of the height). Shadow black 7% r18 y9 (light), 34% r20 y12 (dark).
Android: Compose has no backdrop filter, but the only thing behind a glass
surface is the app-drawn page field (§1.3), so on API 31+ the surface redraws
that field clipped to its own bounds with a `RenderEffect` blur (radius
`material.glassBlur`) under `surface`; below API 31 it is `surface` over the
field without blur. Edge and shadow as above.

## 2. Components (variant per theme)

| Component | Aurora | Riso |
| --- | --- | --- |
| Section header | `section` in `inkSecondary`, trailing count `numeral` 12.5 `inkTertiary` | `section` in `ink`, then a `strokeWidth` (1.5) ink rule filling the line, then count `label` 10 `inkSecondary`; 8 below |
| Status chip / sync badge | capsule ≥24 high, padding 8/10, 6 pt dot in the tone, `label`, tone text on tone at 13% (16% dark) | stamp ≥22 high, padding 7, 1.5 inset ink outline, `label` 10, 9 pt square chip (ok = `accent4`, wait = `accent3`, live = spinning arrows in `accent2`, neutral = empty); conflict/failure = `bad` fill, `onAccent` text, warning glyph, no outline. Never truncates |
| Support tier | glass capsule 26 high, symbol + `label` 12.5 | touch = ink fill/`ground` text; controller = 1.5 ink outline; browse only = 1.5 dashed outline, `inkSecondary` |
| Primary button | capsule, 54 (large) / 44, `button` 17/15, `buttonFill`/`buttonInk`, glow `mark1` 55% r17 y14 + black 16% r3 y2 | block 52 / 44, `button` 20/17 (caps), `buttonFill`/`buttonInk`, `accent1` plate offset (2 × misregistration, same) behind |
| Secondary button | glass capsule 44, `button` 15, `ink` (destructive `bad`) | 2 pt ink outline 44, `button` 17 |
| Icon button | glass circle (prominent: `buttonFill` + `mark1` glow) | prominent: ink disc + `accent1` plate at 1.25 × misregistration; else 2 pt ink square |
| Progress | 5 pt capsule, track ink 12%, fill `mark2 → mark1` gradient with `mark1` glow | 14 pt: halftone dots (pitch 5, 55%) + 1.5 ink outline; fill `accent2` with 1.5 ink outline |
| Row group (`ThemedGroup`) | glass card radius `radius.card` (22); rows padded 16/9, min `rowHeight`; 0.5 hairline separators inset 16 (58 past icons, 68 past covers) | no card: rows padded 0/11 on paper, full-width 1 pt `hairline` rules, closing hairline |
| Row | title `bodyEmphasis` 16, subtitle `secondary` 13 | same roles (Newsreader) |
| Value row | title … value `numeral` 15 `inkSecondary` | title, dot leader, value `numeral` 12.5 `ink` |
| Icon tile | 30 pt rounded-9 square in the tone, white glyph | 32 pt 1.5 ink square, ink glyph |
| Cover | radius `radius.cover` (14), 0.5 ink 10% keyline, shadow `#28143c` 28% r16 y12 (dark black 55%) | printed (1.5), 1 pt ink keyline, no radius, no shadow |
| Glowing cover (heroes) | cover + its own art blurred r26, saturate 1.8, 55%/80% opacity, 86% × 92% at +14% down; top-left sheen; reflection below at 14% | printed cover only |
| Display text | `display`/`title` in `ink` | `display` in `ink` with an `accent1` copy offset (plateOffset × misregistration, default 1.5) behind, multiply (light) / screen (dark) |
| Kicker | `section` ink label + `section` `inkSecondary` detail | `label` in an ink (or pink/yellow) block padded 7/5 + `label` detail `inkSecondary` |
| Dot leader | — | 1.8 pt dots every 6 pt in `inkSecondary` 80%, baseline-aligned |
| Ink box (`InkBox`) | glass card with a `section` header | 2 pt ink box with an ink header bar (`section` 15, tracking 1.5, `ground` text) |
| Dateline | — | 3 pt ink rule above, 1 pt below, `label` 10 |
| Empty state | symbol 34 + `headline` + `secondary` | `headline`, 48 × 3 ink rule, italic `body` 15 |
| Filter chip | glass capsule (selected `buttonFill`) | 1.5 ink box (selected ink fill), `label` |

## 3. Pages

Section order is top to bottom. "Lit" = the page takes the named cover's light
(Aurora only).

### Play — two layouts

Content: games in progress (RomM "continue playing", with this device's
playtime, sessions, last played), sessions (Stats derivation, newest 24), the
pinned collection (first user-made collection with known games; its games
played-first), the running download and queue counts, connection, installed
count, last library sync, server name.

**Aurora (compact), v2** (2026-09-24; the owner found v1 plain and its layout
off) — lit by the game in focus, field `room`, gutter 20. Inline nav title
hidden; Downloads in the toolbar. The studio's "room": the game's own screen is the
hero, not its box art.
1. Sync status line: 7 pt dot (`ok`/`wait`) + `caption` 12.5 "Online · synced 2 min ago · N games on this device".
2. Continue playing — a paged carousel of screens, one per game in progress (up to
   6), full width minus the gutters, the next page peeking 16 pt at the trailing
   edge; the focused page lights the page and the tab tint.
   - **Screen**: the game's latest resume point preview (or latest save-state
     preview) at its own aspect, radius 22, 0.5 white keyline, on a black screen,
     with the frame blurred and glowing in the game's `k1` behind it. No preview:
     the cover centred on its blurred backdrop inside the same 4:3 screen. Never
     art-treated. A sync status chip (dark glass, §11 wording) sits bottom-left
     inside the screen; a conflicted game shows "Two resume points" in `bad`.
   - **Caption** under the screen, 14 below: `section` platform · year in
     `inkSecondary`; `title` 32 game name (2 lines max); `secondary` for the resume
     point: "Automatic resume · 1 min ago · This iPhone" (or the save state's
     name/kind), else "Played 2 h ago · 1 h 05 min on this device".
   - **Actions**: Resume (primary, lit glow, fills the row) + Details (54 glass
     circle, `info.circle` symbol at 20 pt, not a text "i").
3. "In progress" header (`section`) with the pager dots at its trailing end (6 pt
   dots in each game's `k1`, current 18 wide), then a horizontal row of the other
   games in progress beyond the carousel (152 × 114 tiles: preview or cover, 78%
   scrim, game name `bodyEmphasis` 15 white, relative time); tapping a tile opens
   its Game page. The row is left out when there are no more games.
4. Recent saves — a lit gallery by day (see **Play rules**; mosaic v2 below).
5. Stale notice (glass card), empty state "Nothing to continue yet" (when there
   is nothing in progress and no recent saves).

**Recent saves mosaic v2 (Aurora, all widths)** — never leaves a hole or a lone
half tile: 1 save → one full-width 16:9 tile; 2 → two half tiles; 3+ → the first
full-width, then pairs, and an odd last save becomes full width. Regular width uses
four columns with the same rule applied per row (a short last row stretches its
tiles to fill the row).

**Aurora (regular / Duo open)** — one canvas, 32 margins: status + dots row;
hero row (glowing cover 360 | caption with `title` 48 + actions, max 380 wide,
spacing 44); "Continue playing" row of room cards (176 wide, 0.76 aspect, cover
+ 78% black scrim, platform `section` 10 white 72%, title, relative time;
selected = 2 pt `mark1` outline; tap focuses, context menu Resume/details);
recent saves as day rows (see **Play rules**).

**Riso (compact)** — paper, gutter 16.
1. Masthead: `display` 52 "Play" with plate at 1.25.
2. Dateline: long date (e.g. "Wednesday 23 September 2026") · server name.
3. Ears, two columns split by a 1 pt ink rule, 1.5 rule below: Downloads
   (label, "Name · 62%" `bodyEmphasis` 14, "3 waiting · 1 failed" `label`, 5 pt
   progress; or "Nothing downloading" / "N games on this device") → Downloads;
   connection ("Online"/"Offline", "N games ready", "Synced 2 min ago").
4. Lead: pink kicker "Continue playing" + "SNES · 2 hours ago"; `display` 60
   title (2 lines); columns 60/40: printed cover 3:4 + italic caption
   ("Super Nintendo. Played 2 hours ago."); dek `body` 16 ("You last played 2
   hours ago, 1 h 05 min in so far on this device."), "On this device" +
   sessions stamp (only when played), Resume (primary).
5. Columns (1 pt ink rule between): "In progress" briefs (≤3; 48 × 64 print,
   `label` "SNES · 3 h ago", `headline` 18 title, "1 h 05 min so far.") | the
   pinned collection (name `section` 16 + "N of M played", italic description,
   numbered list: `statValue` 19 numeral, title `bodyEmphasis` 14, playtime
   `label`). Either column spans when the other is empty.
6. Recent saves — a list by day (see **Play rules** below; Recent sessions
   live on Stats only).
7. Stale notice (yellow kicker), empty state.

**Riso (regular)** — broadsheet: Downloads ear (boxed) | masthead 88 | sync ear
(boxed); dateline; left column (lead at `display` 76, 63/37 split; collection
in three columns; notices) | 1 pt rule | right rail 330: "In progress" briefs,
recent saves.

**Play rules** (both platforms; the plan is the source of truth):

- Play shows **Recent saves** (docs/play-data-local.md §3: every loadable save
  state or current slot plus each game's current resume point; newest first;
  12 shown; grouped by local day "Today" / "Yesterday" / date), not Recent
  sessions. Recent sessions are on Stats only. The content list above loses
  "sessions" and gains the saves reader's recent list.
- Save previews are shown as captured, never art-treated (no light, no print);
  when a save has no preview its game's cover stands in (printed in Riso like
  every cover).
- **Aurora** — header "Recent saves" + count; per day: `headline` 27 day,
  `caption` 13 long date … "N saves" `numeral` 12.5, a 3 pt light strip (the
  day's games' `k1`); then a two-column mosaic, gap 10: when the day has more
  than two saves the first tile spans both columns at 16:9.4 (radius 22),
  the rest are 4:3.3 (radius 16). Tile: frame (aspect-fill, nearest-neighbour)
  on `#0b0a12`, 0.5 white 10% keyline, glow in the game's `k1` (55% r12 y16);
  scrim `rgb(8 6 14)` 42% → clear at 26–34% → 78% at 60% → 94%; kind chip top
  left (22 high dark glass pill `rgb(12 10 20 / 0.5)`, glyph 12 + 11 semibold
  white 92%: "Automatic resume" / "Save state · Slot N" / "Save state");
  caption bottom left (11/10 inset; 16/14 on the large tile): game name
  `headline` 18 (28) white, one line on small tiles (two on the large one),
  "“Name” · 7:07 PM · This phone" 11.5 (13) white 76%, then the sync state as
  a 6 pt dot + label in the frame tones (ok `#7cf2c4`, wait `#ffc66b`, live
  `#6fe3ff`, conflict `#ff7a6b` with a warning glyph, local white 70%); on the
  large tile the sync state is a dark glass pill top right instead. Regular
  width (the studio's open canvas): each day is one row — a 150 margin (4 pt
  vertical light strip at its left, then padded 16: `headline` 26 day,
  `caption` 13 long date, "N saves · N games" `numeral` 13), gap 22, then a
  mosaic with four columns when tiles keep ≥ 180 wide (the first day's first
  tile spans two columns and two rows); on a narrower canvas two or three
  columns with the compact rule for the first tile.
- **Riso** — `SectionHeader` "Recent saves"; per day: `headline` 22 day + italic
  `body` 14 "Wednesday 23 September · N saves", 1 pt rule; paper rows padded
  0/11, hairlines between: the frame 64 × 48 in a 1 pt ink keyline (never
  printed), `label` 10 kind ("SAVE STATE · SLOT 2"), `headline` 18 game name,
  italic `body` 14 "“Name” · 7:07 PM · This phone", and the sync state as a
  stamp (status chip) at the trailing edge.
- Tapping a save opens its Game page; a save whose current resume point is in
  conflict opens the **Conflict** sheet instead. A game in progress whose
  resume point is in conflict shows a "Two resume points" chip (`bad`) under its
  caption (Aurora) / in the lead (Riso), and Resume opens the Conflict sheet.

### Conflict (sheet from Play and Game) — one layout

Content: one game, its current resume heads (two or more) from the saves reader,
newest first, each with preview, device label, kind/name, time and day (no
sync badge: every head of a conflicted reference reads "Conflict").
Copy: "{Game} has two resume points. Choose which to keep playing." ("three", …
when more).

- Aurora: `StatusChip` "Conflict" (`bad`), `title` 34 game title, the copy in
  `secondary`; choices two per row, each a glass card (radius `radius.card`,
  padding 8): the frame 4:3 radius 12 on the player ground (as captured;
  cover when none), device `headline` 19 + a radio, "Resume point · 7:07 PM ·
  Yesterday" `caption`; selected = 2 pt `mark1` outline + `mark1`
  glow (55% r14 y12), radio filled `mark1` with a white centre.
- Riso: pink kicker "Conflict" + "2 resume points"; `display` 44 game title,
  1.5 rule, the copy in italic `body` 17; choices on paper with a 1.5 ink
  keyline (selected 2.5 on the `accent1` plate), frame in a 1 pt ink keyline
  (never printed), device `headline` 20, square radio (ink fill when selected).
- Then "The other resume point stays in this game's saved progress." (`caption`
  / italic `body` 14.5), primary "Keep playing" (disabled with "Download {Game}
  to this device to keep playing." when it isn't installed), text action "Not
  now". The first (newest) choice is selected.
- Keep playing opens the player with the chosen checkpoint; the player resolves
  the branch with the existing progress rules (the same call as choosing that
  branch in the player's own prompt; the other head stays in history). The
  sheet itself writes nothing. Reached from Play (Resume on a conflicted game,
  a conflicted save) and Game (Play / "Choose resume point" under the problem
  text). Identifiers: `conflict.sheet`, `conflict.choice.<n>`, `conflict.keep`,
  `conflict.dismiss`, `game.chooseResume`, `play.conflict.<romId>`.

### Downloads (pushed from Play) — one layout

Field `page`, lit by the running download's cover.
1. Current download — Aurora: 180 high cover "developing" (sharp to the left of
   the progress, frosted (blur 18, saturation 0.6, ground 30%) to the right, a
   2 pt `mark1` edge with glow); platform + "Downloading" `section`; `headline`
   name; sizes `secondary`; `statValue` 34 percentage; 7 pt progress; Cancel.
   Riso: yellow kicker "Downloading" + "62% · PS1"; 76 × 101 print + `display`
   34 name + mono sizes/file; 24 pt halftone bar; Cancel (outline, destructive).
2. "N downloads waiting for a connection." (when offline-queued).
3. Groups "Up next" (Riso rows numbered in 32 pt ink squares), "Didn't finish",
   "Downloaded": cover (or number), name, "SNES · 4.3 MB", status stamp,
   progress for running/queued, error in `bad`, actions (secondary buttons).
4. Empty state "No downloads". Stale group.

### Library — two layouts (+ split on regular width)

Content: platforms grouped by support tier (Touch-ready in app / Controller play
in app / Not supported in app yet), collections by kind, downloaded games per
platform, the cached library (for covers), games in progress.

**Compact** is a navigation stack whose root is the theme's layout; Platform,
Collection and Game push. **Regular** (Duo open) is a split view: a themed
selectable list (platforms by tier, then collections; Riso rows are chapter
rows) beside the selected Platform/Collection page; first platform selected.

**Aurora** — field `page`, lit by the cover in focus. Era browsing uses
release years (RomM metadata, Phase 4) — see **Library era rule** below; with
fewer than two eras there is no ruler and the row is as described here.
1. Summary `secondary`: "18 games · 9 platforms · 1 on this device".
2. Browse header: `headline` = selected platform / tier / "All platforms";
   platform: "N games · N here" and "See all" (secondary button → Platform).
3. Cover row: 150 × 200 covers, spacing −18, view-aligned with the focused cover
   centred; scroll transition scale 1 − 0.22|p|, rotate Y −34°·p, opacity
   1 − 0.28|p|; the centred cover lights the page. Pool: filter (platform or
   tier), games in progress first, then A–Z, max 18. Caption: `headline` 21
   title, "SNES · In progress".
4. Platform facets (horizontal): glass 16-radius tiles ≥48 high: dot (selected
   `mark1`), `SLUG` `bodyEmphasis` 14, count, "N here"; selected 1.5 `mark1`
   outline. Tap toggles the platform filter.
5. Tier chips: All · Touch · Controller · Browse only with counts.
6. Row groups per tier (filtered): platform, "N games · N on this device", chevron → Platform.
7. Collections row (only unfiltered): 240 wide glass collection tiles → Collection.

**Library era rule** (Aurora; added in Android run 4 so iOS follows it next):

- Eras by release year (`first_release_date`, UTC year): "Arcades &
  cartridges" ≤ 1988, "The 16-bit years" 1989–1995, "Polygons & pockets"
  1996–2000, "The 2000s" 2001–2011, "Since 2012" ≥ 2012. Games without a year
  are in no era. Within an era: by release year, then A–Z.
- The ruler shows when games fall into at least two eras; it sits between the
  summary and the browse header. One segment per era with games, gap 6, width
  proportional to its game count (at least 15% of the total), ≥ 48 high: a 6
  pt bar (radius 4) in a gradient of up to three of the era's games' `k1`
  (games in progress first) at 40%; selected 8 pt, full strength, glowing in
  the first `k1` (60% r8 y4); the years the era's games span `bodyEmphasis` 12
  ("1986–88", "1994", "1998–2001"; `ink` selected, else `inkSecondary`); the
  count `caption` 11 `inkTertiary`. Identifiers `library.eras`,
  `library.era.<id>` (`e80`, `e16`, `e3d`, `e00`, `e12`).
- It opens on the era of the most recent game in progress that has a year
  (else all years). Tapping the selected era again shows all years.
- With an era selected the browse header is the era's name (`headline` 24)
  over "[SLUG or tier ·] N games · N here" (`caption` 12.5, counts of the
  filtered pool), and the cover row's pool is that era (∩ platform/tier
  filter): games in progress first, then by release year. Caption adds the
  year: "SNES · 1994 · In progress". An empty era/filter pool says "No games
  here from 1989–95."

**Riso** — paper.
1. Italic summary `body` 15.
2. Column key `label` 9.5: "Platform … Games · On this device".
3. Per tier: 3 pt rule, `headline` 28 tier title + tier stamp, italic dek
   ("Touch controls on screen; a controller works too." / "Playable here once
   a controller is connected." / "On your server, but no player on this device
   yet."), 1 pt rule; chapter rows: `headline` 19 two-digit number in `accent2`,
   name `body` 17 (+ `label` slug), dot leader, count `numeral` 12.5, on-device
   count in an ink block (or "–"); hairline between; unsupported names in
   `inkSecondary`.
4. Collections: header + ruled entries (name `bodyEmphasis`, italic description
   or kind, dot leader, `statValue` 26 count).

### Platform — two layouts

**Aurora** — lit by the most recently played game (else the first), field `page`,
large title = platform. Summary glass card (`statValue` 32 games | on this
device, tier badge, "N played" chip); segmented All · On this device · Played;
order toggle A–Z / Recently played; "Games" grid (adaptive min 104; 128 regular):
3:4 cover with a check badge when downloaded, title, last played / "On this
device" / "On your server".

**Riso** — inline title; opener: `label` "Platform" … tier, 3 pt rule; `display`
56 name; "N games on your server, N on this device." `body` 16.5; tier stamp +
played stamp; plates: up to four most-played (else downloaded) printed covers
with title and playtime, caption above a 1 pt rule; segmented filter (same
labels) + order; key line; A–Z index: `statValue` 30 letter in `accent2` in a 30
pt gutter, entries: title (`bodyEmphasis` if downloaded, else `body`
`inkSecondary`), dot leader, playtime / "Ready", a 26 pt ink "not downloaded"
glyph box; hairlines.

### Collection — one layout

Field `page`, lit by its first game. Count `section`, description (`body`; Riso
italic 18). Games: Aurora cover grid; Riso numbered volumes (`statValue` 34 in
`accent2`, 56 × 75 print, `headline` 22 title, `label` platform) under a 3 pt
rule.

### Game — two layouts

Content: game, tier, hero status, action deck (primary, secondary, progress,
notes, errors), selected file and picker, saved-progress summary, the saves
timeline (automatic resume, save slots, snapshots; newest first; each with
kind, label, time, device "This device"/name, resume sync badge), this device's
playtime/sessions/last played, preserved-content notice. The inline title is
hidden (the page draws it); the bar is transparent only at the scroll edge.

**Aurora** — lit by the cover, field `page`, hero `GameArtHero`: cover
aspect-fill, height `clamp(page × 0.46, 220, 400)`, dissolving into the lit
field with an alpha mask (opaque to 35%, 45% at 70%, clear at the bottom — a
fade to `ground` would band against the field); content overlaps the hero by 64.
1. Heading: platform `section` + tier `section inkSecondary`; `display` 44;
   chips (hero status, resume sync badge "… · resume", saves count); play line
   `secondary`; controller note.
2. File picker (segmented, when several files).
3. Action deck: primary (Play / Download now / Retry…), progress, secondary
   buttons, notes.
4. Saved progress: header with count; a horizontal timeline: per stop 164 wide
   — short time ("Now", "3 min", "2 h", "Yesterday"), a 1.5 `mark1` line with an
   11 pt node (first filled + glow), a 164 × 102 lit panel (`accent1 → accent2`
   gradient with the kind's symbol; thumbnails replace it in Phase 4), kind
   `section` 10.5, label `bodyEmphasis`, "time · device", sync badge; problem
   text; "Browse saved progress". With no stops: a glass card with the status.

   **Game saves rule** (Aurora; added in Android run 4 so iOS follows it next):
   the timeline is fed by the saves reader (docs/play-data-local.md §3), not
   a placeholder model. Stops: the current resume point ("Automatic resume",
   label or "Resume point"), every save state ("Save state · Slot N" / "Save
   state", label or kind), and up to six earlier resume points ("Earlier resume
   point", capture reason); newest first; at most 10. The panel shows the
   save's preview as captured (aspect-fit on the player ground, never
   art-treated); the lit gradient + kind symbol only when a save has no
   preview. With two resume heads the problem text is the conflict copy and a
   secondary "Choose resume point" (`game.chooseResume`) opens the
   **Conflict** sheet; Play does the same instead of launching.
5. File group; About (`body`, ≤10 lines), file count, preserved notice.

Regular: hero spans; left column heading + actions; right 340: "Progress"
group (top three stops as rows) and File; then the timeline full width; About.

**Riso** — hero printed, height `clamp(page × 0.32, 220, 400)`, 1.5 ink rule
under it.
1. Pink kicker `SLUG` + platform name; `display` 62 title (3 lines); standfirst
   = first sentence of the summary, italic `body` 19.
2. By-line: 1 pt rule, `label` 10: platform · tier · "N played" · status.
3. "Where you left off" ink box (detail = relative time): entries (term `label`
   10, value `body` 14.5, badge) — Automatic resume; Save states (up to three
   labels); On this device (play line); problem; "Browse saved progress"
   (outline, full width).
4. File picker; action deck (notes go to Details).
5. About (`body` 17).
6. "Details" ink box: spec rows (term `label` 10 in a 96 column, value `body`
   14.5): Platform, How it plays, File (name + meta), Status, Ready, Problem,
   Runs on, notes.

Regular: kicker + `display` 84 span; three columns (230 standfirst + actions |
where you left off | 280 details), 1 pt rules between; About below.

### Search — two layouts

Content: query, ranked results (LibrarySearch: best match, recently played
first), whether each is downloaded / in progress / last played, matching
platforms and collections ("related"), games in progress, library size.

**Aurora** — lit by the best match (field `page`; `quiet` before typing).
Results: "Ranked by recent play, then title" caption; best match card (104 ×
138 cover, `section` "Best match", played time, highlighted title `headline`,
platform, Resume (primary, small) when in progress; 1 pt `mark1` outline);
"Games" group (cover, highlighted title, "SNES · on this device · played 2
hours ago", chevron); "Platforms and collections" group; empty state.
Before typing: Continue playing shelf (116 tiles) + "Search your library".
Highlight: `mark1` at 26% behind the match.

**Riso** — "3 entries for “metroid”. Ranked by what you've played, then by
title." (`body` 16, bold count, italic tail); "See also" (`label`) + outlined
italic boxes for related platforms/collections; "Games" entries: 44 × 58 print,
highlighted `headline` 24 title (match on `accent3`, `onAccent` text), `label`
detail, prominent ink play disc when in progress (else chevron); "Platforms and
collections" ruled entries (kind `label` 84 wide, name, leader, count).
Before typing: italic line, Continue playing entries, Platforms entries.

Identifiers: `search.bestMatch` (first result), `search.result.<id>`,
`search.resume.<id>`, `search.related.<p|c><id>`.

### Stats — two layouts

Content: total, sessions, streak, last played, games by playtime, sessions
(newest 40), daily totals for the last 28 days, the 28-day window (playtime,
sessions, games). Durations use one format everywhere: "2 h 05 min", "45 min",
"0 min", "Under a minute" (never "2 m").

**Aurora** — lit by the most played game, field `page`.
1. Hero: glowing cover 150 (no reflection) + "Most played", `title` 30 name,
   "3 min · 2 sessions".
2. Summary cards (2 columns; 4 on regular): Total playtime, Sessions, Streak,
   Last played ("Just now", "3 min ago", "2 h ago", "Yesterday") — glass cards,
   `section` label with a `mark1` symbol, `statValue` 34.
3. Time by game (≤8): rows with playtime, and a 4 pt bar in the game's `k1`
   scaled to the top game, with glow.
4. Recent sessions by day (≤5 days): `headline` day, count · total, light strip,
   group of sessions (clock, duration).
5. Footnote `caption` (`stats.footnote`): see "Phase 4 details › Stats" below.

**Riso** — italic "N sessions recorded · kept on this device".
1. This month: 3 pt rule; `section` 18 "This month" + `label` "Last 28 days";
   sentence `body` 17 with bold figures: "**2 min** across **1 session** and
   **1 game**. Streak: 1 day."; 28 bars (56 high, 3 apart; played days `accent2`,
   the last 7 days `accent1`; empty days a 1.5 hairline); `label` "4 weeks ago …
   This week"; a 2 pt ink box of figures (Total | Sessions | Streak, 1 pt rules,
   `label` 9.5 over `statValue` 27).
2. Time by game (≤6): `statValue` 22 number in `accent2`, title, leader, duration.
3. Recent sessions by day (≤6 days): `headline` 26 day, italic long date, "N
   entries", 1.5 rule; diary entries (clock, `headline` 19 title, "42 min on
   this device." `body` 15, `label` slug, 52 × 68 print on a mount, rotated).
4. Footnote.

Regular: month + time by game | 1 pt rule | diary.

### Settings and sub-pages — one layout

Field `quiet`, max width 760, large titles.
- Account: Aurora glass card (52 pt avatar in a `mark1 → mark2` gradient with a
  white ring, `headline` label, host, chips Online/Offline and Ready
  offline/Library syncing, "Library synced …"). Riso imprint between 3 pt rules,
  centred: `label` "Server", `display` 44 label, italic host, stamps, sync line.
- Appearance group (footer "Saved on this device only. Backups don't include
  it."): live preview (138 high, the theme's `welcome` field or paper, a stand-in
  cover, "Continue playing", "Super Metroid", Synced badge, Resume), Theme
  segmented (Aurora | Riso), Appearance segmented (System | Light | Dark).
- "Rommio" group of navigation rows with icon tiles: Profiles and servers,
  Player and controls, Cores and BIOS, Backup and recovery.
- Storage (value rows), stale notice, Account (Sign in again in `tint`, Sign out in `bad`).
- Sub-pages use the same groups: Player and controls (toggle rows), Cores and
  BIOS (import row, core rows with status stamps, "Not included in this build"
  disclosure), Profiles and servers (server rows with Active stamp, Switch /
  Remove…, This server). Backup and recovery stays a system list in the theme
  (Aurora inset glass rows; Riso plain paper rows with hairlines).

### Setup — one layout

Field `welcome`.
- Welcome — Aurora: fan of three glowing stand-in covers (138 × 184, ±9°/8°,
  centre 112%), app mark (30 pt tile in `mark1 → mark2`), `display` 40 "Every
  game on your server, right where you left it.", `body` detail, Connect to
  your server (primary), Scan setup code (secondary), fine print. Riso: `label`
  rule "RomM … Your library"; masthead "Rommio" (`display` 115, plate 2×);
  italic `body` 26 promise over a 3 pt rule; halftone prints on white mounts
  over a yellow sun (multiply/screen); detail over a 1 pt rule; primary with a
  trailing arrow; outline secondary.
- Connect / Sign in: `StepHeader` — Aurora `title` + "Step 1 of 2 · Your RomM
  server"; Riso `label` step rule + `display` 54 title. Field labels (Riso
  `label` caps; Aurora `caption`). Fields: glass (Aurora) or white paper in a 1.5
  ink box with `numeral` text (Riso). Saved servers as a row group. Sign in shows
  the server as a row group.
- First sync: step header; progress line; a group of Platforms / Games /
  Collections (Riso: `section` 20 names, leaders, figures, check marks).

### Player — style renderer

Geometry, hit regions and the touch model are [player-controls.md](player-controls.md)'s
(touch controls v2); `player.*` identifiers are unchanged. A pure style resolver
(`PlayerControlStyleRenderer`) turns the player theme into per-control appearances,
painted by the touch surface's control views.

Roles: face (default), direction (Up/Down/Left/Right), shoulder (L, R, L1, L2,
R1, R2 and the N64 Z trigger), system (Start, Select, Mode, Pause, Coin, Option,
Analog, L3, R3), stick. Each control is drawn at its placed frame (v2 sizes);
its hit region is that shape grown by 14 pt.

| Control | Aurora (frosted) | Riso (flat ink on black) |
| --- | --- | --- |
| Canvas | `player.ground`; bias light = the blurred cover (§1.3 bake) at 85% with a radial `player.ground` 55% vignette | `player.ground` (black), no art |
| Face | circle, blur + `controlFill`, glass edge (`controlStroke` 0.5 pt keyline, white top rim, soft shadow), legend `playerLegend` in `controlInk`; pressed `controlPressed` and 94% for 60 ms | circle `faceFill` (yellow), plate `offsetPlate` (pink) offset (misregistration, misregistration) = 2.5, legend `playerLegend` caps in `faceInk` |
| Console colours | the frosted face unchanged; the colour paints the legend (stepped in lightness to 3.5:1 against the face) and a 2 pt ring — never a wash | the console colour printed in its nearest ink by hue (pink `dpadFill`, blue `dpadAltFill`, yellow `faceFill`, green); plate pink (blue behind pink faces) |
| Shoulder | tab radii 14 top / 20 bottom, frosted | tab 18 top / 6 bottom, `shoulderFill` (blue), pink plate, legend `playerLegend` 18 |
| Select/Start etc. | frosted capsule, `playerLabel` | capsule, no fill, 1.5 `controlStroke` outline, `playerLabel` (mono caps) in `controlInk` |
| Menu | 44 pt frosted circle, three lines | 46 × 32 `menuFill` (pink) pill, `menuInk` three lines |
| D-pad | frosted disc; cross grooves (arm = 32% of the side, inset 13%, radius 8) in `controlInk` 16%; chevrons; pressed arms `accent` 45% | cross (arm 34%, inset 2%, radius 5%) in `dpadFill` (pink) over a `dpadAltFill` (blue) copy offset 2.5; `pivot` (yellow) circle r = 0.32 × arm, multiply; solid triangles in `faceInk` 80%; pressed arms lightened 35% |
| Stick | the zone (160 pt primary, 120 pt secondary) isn't drawn; a frosted ring (2 × travel) at the stick's centre and a knob (0.95 × travel, `controlInk` 55%, 80% at full tilt) at the output position; at full tilt the ring gets a 2.5 pt `controlInk` edge; both back at the zone's centre on lift | outlined ring, knob `controlInk` 90%, the same placement and full-tilt edge |
| Legend size | token size × (diameter + 24) / 88: 22 on a 64 pt two-button face, 20 on a 56 pt diamond face (pills use the token size); PlayStation shapes are symbols | same |
| Opacity | user opacity (default 0.72); a face fades only its body (never below 0.5) while its legend and ring stay at full ink, so the legend keeps 3:1 | always full ink (`playerShape.controlOpacity` = 1: Riso never prints over a game) |
| Toast | dark glass pill (`#0c0a14` 62%), white `playerLabel` 13, check | `toastFill` paper ticket, `toastInk` `playerLabel` caps, green check chip, `accent3` plate offset 3 |

OLED black: canvas and panels black, dark player palette, in both themes.

Sheets (theme colours and type; strings and identifiers unchanged):
- Pause — header (Aurora `section` "Paused" + `headline` title; Riso `label`
  "Paused" … "SLUG · Core" over a 3 pt rule + `headline` 26); Resume (primary);
  a 2-column grid of tiles: Save snapshot, Saved progress, Controls, Sync game
  saves and snapshots, Reset core, Leave game. Aurora: glass 16-radius tiles
  (symbol in `tint`, `bodyEmphasis` 15; Leave in `bad`). Riso: numbered boxes
  (`statValue` 26 number in `accent2`, 1.5 ink outline, `section` 15 title);
  Leave game is a pink block. Height fits 430 without scrolling.
- Save states (`StateBrowserView`) and Game progress (`ProgressPlayerPanel`):
  themed lists with section headers.
- Controls: themed list; sliders show percentages.

### Phase 4 details (iOS run 3; Android can follow)

What the pages add from [play-data-local.md](play-data-local.md) beyond the Play,
Conflict, Library era and Game saves rules above:

- **Game › Details and Screenshots** (play-data-local.md §6 order, plain text,
  empty fields left out). Aurora: after the saves timeline a "Details" row group
  (`game.details`; label `secondary` in a 96 column, value `body` trailing),
  then a "Screenshots" row (`game.screenshots`; 176 × 132 frames, aspect-fit on
  the player ground, radius min(`radius.cover`, 12), 0.5 keyline; never
  treated), then File and About; regular width puts Details and Screenshots side
  by side under the timeline. Riso: the RomM rows lead the "Details" ink box
  (before Platform, How it plays…); Screenshots (1 pt ink keyline, never
  printed) follow About. The platform line reads "Super Nintendo · 1994"
  (Aurora heading, Riso kicker detail); the Riso by-line gains the first
  publisher/company and up to two genres.
- **Riso › Where you left off** (from the reader): the resume point's frame
  (150 × 112, 1 pt ink keyline, italic caption "“Name” · This iPhone") beside its
  entry ("Automatic resume" or the save state's kind, "3 minutes ago · today",
  sync stamp); then Save states (up to three titles), Game-save data ("Latest 3
  minutes ago · N kept on this device", stamp), On this device (play line); a
  conflict shows the copy and "Choose resume point"; "Browse saved progress".
  Section identifier `game.savedProgress` (both themes).
- **Saved progress sheet** (Game › Browse saved progress, `game.saves.sheet`):
  the explanation "To load a save state, start the game and open Save states from
  the pause menu. Your current progress is kept in a safety copy first.", then
  row groups Resume point (+ Resume, `game.saves.resume`, when playable), Save
  states ("No save states yet."), Earlier resume points (count; 20 shown),
  Game-save data (latest; "Saved with every capture; a cold boot starts from the
  latest. N earlier captures kept on this device."). Rows: 64 × 48 frame (cover
  when none), title, "Left game · Today · 7:07 PM · This iPhone · Waiting to
  sync", a rename button on save states (`game.saves.rename`).
- **Player › Save states** (`player.states.list`; pause tile "Saved progress"):
  "Loading a save state also loads the game-save data saved with it. Your
  current progress is kept in a safety copy first."; sections Save states,
  Resume points, Game-save data (latest 3, the rest counted); tapping a loadable
  row restores through the session (safety copy first, the game-save rollback
  confirmation unchanged); rename on save states; branch choices, sync status,
  imports and Leave game stay in the same sheet.
- **Rename**: a native alert "Name this save state" / "Rename save state", field
  placeholder "Before the boss", message "Only on this device. Leave empty to
  remove the name.", Save (`….renameSave`) / Cancel.
- **Stats**: session rows add how the session ended — Aurora subtitle "8:59 PM ·
  Left with a resume point"; Riso label "NES · LEFT WITH A RESUME POINT"
  ("Left after a save state", "Left without a resume point", "Ended in the
  background"). Footnote: "Play time counts active play on this device; paused
  and background time is left out." plus "Play from before sessions were
  recorded is estimated from automatic saves." when estimates are included.
- **Release year** after the platform on game tiles and Search rows ("SNES ·
  1994 · on this device").
- **Tab bar glyphs** (iOS): line symbols `play.circle`, `books.vertical` (outline
  variant forced), `chart.line.uptrend.xyaxis`, `gearshape` — the filled
  variants the tab bar picks by default read heavy next to the mockups. Tab
  identifiers stay as before (`chart.bar` for Stats).

Android (run 4) follows the items above except: the pause tiles keep Android's
existing strings ("Snapshots & game saves" and "Sync progress"; instrumentation
tests click them, and the plan keeps pause-sheet strings) and the tab glyphs
are iOS-only; its rename prompt is a Material dialog with the same copy.

### Launch choice (sheet from Play and Game) — one layout

The plan's "Launch choice (resume, game save, branch)". One content model, one
layout restyled through components.

- **When**: (a) Game page action "Start options" (`game.startOptions`), shown
  when the game has a resume point *and* game-save data; (b) the context menu /
  long press on a Continue playing game on Play ("Start options"); (c) instead
  of launching, whenever the launch planner would otherwise stop inside the
  player to ask (the choice is made before the player opens). Multiple resume
  heads are a **Conflict**, which opens the Conflict sheet instead.
- **Sheet** (`launch.sheet`, medium detent / bottom sheet): kicker "Start",
  `title` game name, then one choice row per option, a primary button, and
  "Not now":
  - **Resume** (`launch.resume`) — "Automatic resume · 3 min ago · This iPhone"
    (or the save state kind when the resume point came from one), preview frame.
  - **Start with game save** (`launch.gameSave`) — "Game-save data · 3 min ago ·
    the game starts from its own save", no frame (a save glyph).
  - Default selection: Resume. Primary button label follows the selection
    ("Resume" / "Start with game save"), then the player opens with that plan.
- The player's own start prompt stays as a fallback for launches that bypass
  the sheet, restyled with theme components and the same two labels plus "Back
  to library" (Android) / "Back" (iOS).

### iPhone Duo

- Closed (466 × 678, vertical bar on the trailing edge): the compact layouts.
- Open (regular width): the regular layouts above — one shared background,
  heroes/headers across the fold, Library as the only list/detail split. Player
  stage rules are Phase 5 (unchanged here). On the open Duo the Pause sheet is a
  floating inspector beside the game (as the plan's page list says), like the
  Save states and Controls panes; the game stays visible and is not covered.

## 4. Copy and formatting (both platforms)

One set of strings in both themes and on both platforms (UI copy is US English:
"colors", not "colours"). Platform-natural exceptions: device labels ("This
iPhone"/"This iPad" vs "This phone"/"This tablet"), system dialogs, and the
pause-tile strings noted above.

- **Formatting**:
  - Counts use the locale's grouping ("1,234 games").
  - Sizes use decimal units from the platform formatter (Android
    `Formatter.formatShortFileSize`, iOS `ByteCountFormatter` `.file` with
    non-numeric formatting off); zero is "0 KB".
  - Relative time: under 1 min "Just now"; under 60 min "N min ago"; same
    calendar day "N h ago"; previous calendar day "Yesterday"; within 6 days the
    weekday ("Monday"); otherwise the locale's month-day ("Sep 21"), with the
    year when not the current year ("Sep 21, 2025").
  - Day groups: title "Today" / "Yesterday" / weekday within 6 days / month-day;
    caption the full date ("Wednesday, September 23") only when the title is
    not already that date.
  - Durations: "Under a minute", "45 min", "2 h 05 min".
  - Separator: " · " (never "•").
- **Ownership of wording where the platforms drifted** (the other platform
  aligns): Game page status, file and action labels, Downloads and Backup and
  recovery section titles — Android. Player (Controls sheet labels, toasts,
  rollback confirmation, slider percentages), Conflict, Launch choice, Setup
  (Welcome, Connect, Access options, Setup code, Sign in), Cores and BIOS —
  iOS/this spec. Titles: "Access options" (sheet), "Setup code" (sheet).
- **Themes never change strings** (e.g. the Platform sort is "Recently played"
  and the Game empty state "No saved progress yet" in both themes).
- **Test identifiers** match across platforms: `appearance.theme`,
  `appearance.scheme`, `appearance.preview`, `welcome.setupCode`,
  `stats.footnote`, `play.continue`, `search.field`, `settings.signOut`,
  `launch.*`, `conflict.*`, `game.*`, `play.recentSaves`.

## iOS implementation

| Area | Files (`ios/Sources/RommioUI/`) |
| --- | --- |
| Palette, lights (pure) | `Art/AuroraLight.swift` (`ArtColorMath`, `CoverPalette`, `AuroraLights`), `Art/CoverPaletteExtractor.swift` |
| Riso print, backdrop bake | `Art/RisoPrint.swift` (`RisoPlateCurve`, `RisoPrintStyle`, `RisoPrinter`), `Art/AuroraBackdrop.swift` |
| Caching | `Images/ArtworkPipeline.swift` (`palette(for:)`, `risoPrint(for:…)`, `backdrop(for:dark:)`) |
| Lighting a page, fields, covers | `Art/ArtViews.swift` (`litBy`, `PageBackground`, `PageLightStyle`, `BlurredCover`, `PaperGrain`, `TreatedCover`, `RisoPrintImage`) |
| Theme value | `Theme/RommioTheme.swift` (`lit(by:)`, `lights`, `mark1…3`) |
| Components | `Components/Controls.swift`, `Components/Surfaces.swift` (glass, `ThemedGroup`, `ThemedScrollPage`, `themedList`, rows), `Components/Editorial.swift` (display text, kicker, leaders, rules, ink box, `RatioColumns`), `Components/GameArt.swift`, `Components/Hero.swift`, `Components/Formatting.swift` (§4 `PlayFormat`), `Components/DarkFrame.swift` (tones on dark frames and over the game) |
| Pages | `Pages/Play/{PlayPage,AuroraPlayLayout,RisoPlayLayout,RecentSaves,ConflictSheet,LaunchChoiceSheet,DownloadsPage}.swift`, `Pages/Library/{LibraryPage,AuroraLibraryLayout,RisoLibraryLayout,PlatformPage,CollectionPage}.swift`, `Pages/Game/{GamePage,AuroraGameLayout,RisoGameLayout,SavedProgressSheet}.swift`, `Pages/Search/SearchPage.swift`, `Pages/Stats/StatsPage.swift`, `Pages/Settings/SettingsPage.swift`, `Onboarding/*` |
| Player | `PlayerVisualTheme.swift` (shape, fonts, glyphs, ink mapping), `PlayerControlStyleRenderer.swift` (roles, appearances, painters), `PlayerControlsOverlay.swift` (the one multi-touch surface, control views, haptics, title strip, Menu), `PlayerPausePanel.swift`, `PlayerExperienceView.swift` (bias light, toast, sheets); geometry and the touch model in `RommioPlayerKit/PlayerControlPlan.swift`, `PlayerStandardStage.swift`, `PlayerTouchTracker.swift` (docs/player-controls.md), `PlayerPanelPresentation.swift` (sheet or floating inspector) |
| Saves (Phase 4) | `Components/SaveViews.swift` (`SavePreviewImage`, `SaveRow`, `SaveFormat`, rename alert), `ProgressPlayerPanel.swift` (Save states sheet); data in [play-data-local.md](play-data-local.md) |
| Tests | `Tests/RommioUITests/ArtTreatmentTests.swift`, `PlayerControlStyleRendererTests.swift`, `ThemeTests.swift`, `PlayerVisualThemeTests.swift`, `PlayDataPresentationTests.swift`, `CopyAndFormattingTests.swift` (§4, readable tint), `LaunchChoiceTests.swift` (Launch choice, panel presentation), `PlayV2Tests.swift` (Play screens' data, mosaic v2), `PlayerLayoutParityTests.swift`, `StackBudgetTests.swift`; `Tests/RommioPlayerKitTests/PlayerTouchModelTests.swift` (controls v2 geometry and touch model), `PlayerTouchStickTests.swift` (the touch-stick maths, pinned to `scripts/touch_stick_reference.py`); `Tests/RommioFoundationTests/PlayerHapticsPreferenceTests.swift`, `PlayerGameControlsTests.swift` (per-game Analog controls); UI: `AppUITests/PlayerTouchControlsTests.swift` (touch model on a running game), `PlayerStickControlsTests.swift` (sticks, Analog controls, DS/3DS touch screens in the QA harness) |
