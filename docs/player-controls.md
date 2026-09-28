# Player touch controls

Status: spec for both platforms (2026-09-24), replacing the per-button touch handling.
Owner feedback it answers: controls were hard to hit, missed presses, were too small and
sat in the wrong place; add haptics. Geometry follows the iPhone Duo stage rules
([iphone-duo-plan.md](iphone-duo-plan.md) Phase 4 item 21): controls never overlap a game
screen.

## Touch model

- **One surface.** A single multi-touch view owns every touch control in the player
  overlay (iOS: one `UIView`, `isMultipleTouchEnabled`; Android: one `pointerInput` over
  the overlay). Control views only draw and carry accessibility/test identifiers. The
  Menu button stays an ordinary button.
- **Generous hit regions.** Each control's hit region is its drawn shape grown by 14 pt.
  A touch goes to the control whose hit region contains it; if several do, to the
  nearest centre (distance divided by the control's radius). Inside a face cluster's
  zone (the box around its buttons plus 14 pt) there are no dead gaps: the nearest
  button takes the touch.
- **Chord.** In two-button clusters (A/B), a touch within 22% of the centre-to-centre
  distance of the midpoint between the two buttons presses both.
- **Rolling.** A touch that began on a face, shoulder or Select/Start control follows the
  thumb: moving onto another control of the same group releases the old one and presses
  the new one. Drifting outside every hit region keeps the last control held until the
  touch is 40 pt beyond it, then releases.
- **D-pad.** The touch that starts in the pad's hit region owns the pad until it lifts,
  even outside the pad. Direction comes from the angle to the pad's centre: dead zone
  18% of the pad's radius; 8-way, with diagonal sectors 30° wide (revised 2026-09-26
  from 40°: a thumb resting on an arm rolled into a diagonal). A small d-pad beside a
  primary stick (Nintendo 64, 3DS) is 4-way: each arm takes a quarter of the circle, so
  it presses one direction at a time.
- **Sticks** (analog), revised 2026-09-24 — see "Analog sticks and per-console layouts"
  below.
- **Immediate, ordered input.** Presses and releases go to the core synchronously, in
  touch order: no per-event `Task`/coroutine. Every held input is released when the
  touch ends or is cancelled, when Pause opens, when the app leaves the foreground and
  when the overlay goes away.
- **Haptics.** On every press edge (not on release): iOS a prepared
  `UIImpactFeedbackGenerator(.light)` at intensity 0.75 for buttons and a
  `UISelectionFeedbackGenerator` tick for each d-pad direction change; Android
  `HapticFeedbackConstants.VIRTUAL_KEY` for buttons and `CLOCK_TICK` for d-pad changes.
  Setting **Haptics** (on by default) in Settings › Player and controls and in the
  in-game Controls sheet; device-local, stored with the controls preferences. The
  system's own haptics setting still applies.

## Geometry (iPhone and Android phones)

Sizes scale with the control-size setting (0.8–1.3). Hit regions follow the touch model.

| Control | Drawn size |
| --- | --- |
| D-pad | 150 pt cross (104 pt beside a primary stick) |
| Two-button face (A/B) | 64 pt circles, centres 76 pt apart on a 25° rise (B lower-left, A upper-right) |
| Four-button diamond | 56 pt circles, centres 62 pt from the cluster centre |
| Six-button (Genesis 6, arcade) | 50 pt circles |
| Shoulders | 88 × 40 |
| Select, Start | 64 × 34 |
| Menu | 44 circle |

No face button is drawn smaller than 50 pt and no hit region is smaller than 58 pt, in
any family (the expansion families keep their layouts but take this floor).

- **The game's size (revised 2026-09-25):** the game takes the display's full width
  (portrait) or full height (landscape), edge to edge, whenever that fits beside the
  controls. When it has to be smaller, it keeps a border on every side (12 pt from the
  sides, 8 pt from the camera, 12 pt above the controls). It sits 8 pt under the camera
  housing (the Dynamic Island, a notch or a punch-hole camera), not under the safe area,
  and landscape rails start 6 pt from it. iOS reports no rectangle for the housing; on
  every iPhone with one (a top inset of 44 pt or more) the safe area runs 11 pt past it,
  so its reach is the inset less 11 pt (`PlayerViewportSafeAreaInsets.cameraTop` /
  `cameraSide`). Android takes it from the cutout's bounding rectangles
  (`PlayerStageInsets.cameraTop` / `cameraSide`). With a controller (no touch controls),
  the same rule applies. Nothing names the game while playing; its name is in the pause
  menu (the title strip under the game was removed on 2026-09-25).
- **Portrait:** the game at the top. The rest of the screen, from 12 pt under the game, is
  the control band. The shoulders are one level row 28 pt under the game (revised
  2026-09-26: per-cluster shoulders sat at two heights and read as more buttons): each
  side's main shoulder (L, R, L1, R1) is a bumper flush against its screen edge, square
  on that side and rounded inward, 44 pt tall and covering the 20 pt margin; any other
  shoulder (L2, R2, ZL, ZR, the N64's Z) sits inward beside it. PlayStation's numbered
  shoulders stack instead, 1 above 2 against each edge (8 pt apart), when that costs the
  game its full width or the controls their size nothing (in practice: without Analog
  controls, on the larger phones); otherwise they share the row. Then the d-pad (left) and
  face cluster (right) centred at 55% of the band's height, 20 pt from the screen edges,
  at least 16 pt below the shoulders; Select · Menu · Start centred at the bottom, 12 pt
  above the home indicator. On a squeezed band (the controls already at their smallest
  and the game still short of its floor) the row gives way: 12 pt under the game, at the
  shoulders' natural height, 12 pt above the stacks.
- **Landscape:** the game centred between side rails (6 pt from the camera housing); the d-pad and the face cluster
  centred in their rails at 60% of the height; shoulders at the top of each rail
  (PlayStation's stacked 1 above 2, centred in the rail, when the stacks still fit at the
  requested size with full-size sticks);
  Select and Start at the foot of the rails; Menu bottom centre. Nothing overlaps the
  game.
- **Left-handed** mirrors the rails, never the labels.

## Analog sticks and per-console layouts

Each console's main movement control takes the prime left-thumb position; the other one
is secondary. Consoles with sticks are landscape (their orientation policy), so these
positions are for the rails; portrait follows the same order top to bottom.

**Touch sticks (not a copy of a physical stick)**

A thumb on glass has no spring, no centre to feel and no gate to push against; it
drifts, jitters a point or two, and overshoots. The stick is designed for that:

- **Floating centre.** A stick owns a zone (160 pt square when it's the primary
  control, 120 pt when secondary). Touch-down anywhere in the zone makes that exact
  point the centre (revised 2026-09-24: clamping it inward made a touch near the zone's
  edge read as instant tilt); the zone only decides which touches start the stick, and
  the ring may draw past it. On lift the output drops to zero at once (the missing
  spring) and the ring returns to the zone's centre.
- **The centre stays put** (revised 2026-09-26). Past full travel the output stays at
  full tilt and the centre stays where the thumb landed; it no longer follows a thumb
  past the rim, which let the stick wander. A drifting thumb still keeps the stick:
  the touch owns it until it lifts.
- **Dead zone with rescale** absorbs contact jitter (a few points) without a jump at its
  edge; **saturation** reads the outer band of travel as full tilt, because a thumb
  can't hold the exact rim; a **response curve** gives fine control near the centre
  (walking vs running).
- **Gate shape.** Each console's physical gate sets how far diagonals reach: a circle
  (3DS, PSP), an octagon (N64: 0.86 per axis at 45°), near-square (DualShock: 0.95).
  Without it, diagonals feel weak in games tuned for the hardware.
- **Haptics** substitute for the gate, shaped like it (revised 2026-09-26). At full
  tilt, the N64's octagon clicks into each of its eight notches (the cardinals and
  diagonals; iOS `.rigid` impact at 0.8, Android `CONTEXT_CLICK`), and the DualShock's
  near-square gate bumps at its four corners (iOS `.medium` at 0.55, Android
  `KEYBOARD_TAP`). A notch catches the stick within 8° of it and holds it out to 12°, so
  a thumb on a notch's edge doesn't chatter. Entering full tilt anywhere else, and on a
  round gate (3DS, PSP) always, is one soft tick (iOS `.soft` at 0.4; Android
  `CLOCK_TICK`), again only after leaving full tilt. Nothing else while moving — a buzz
  per change is noise. L3/R3 presses tick like buttons.
- The touch owns the stick until it lifts; values go to the core immediately.
- **Visuals:** the ring draws at the centre (so the thumb can see where centre is), the
  knob at the output position, and the ring brightens at full tilt. The ring (and its
  full-tilt edge) is the gate's shape — the output's full-tilt boundary (`gateOutline`),
  corners rounded by a tenth of the radius: the N64's octagon, the DualShock's near-square,
  a circle on 3DS and PSP (revised 2026-09-26). The knob stays round.

The maths is one function set, pinned by `scripts/touch_stick_reference.py`
(`stick_output`, `gate_scale`, `full_tilt`, `notch`): both apps implement it and their
unit tests assert the vectors that script prints, to 4 decimals.

| Profile | Travel | Dead zone | Saturation | Curve | Diagonal reach | Gate (haptics) |
| --- | --- | --- | --- | --- | --- | --- |
| N64 stick | 48 pt | 10% | 90% | 1.4 | 0.86 (octagon) | octagon: 8 notches |
| 3DS circle pad | 44 pt | 10% | 92% | 1.2 | circle | circle |
| PSP nub | 36 pt | 12% | 90% | 1.0 | circle | circle |
| PlayStation left | 44 pt | 10% | 90% | 1.3 | 0.95 (near-square) | square: 4 corners |
| PlayStation right | 40 pt | 12% | 90% | 1.6 | 0.95 | square: 4 corners |

**Per-game tuning.** The in-game Controls sheet gets a **Stick** group for consoles
with sticks: Sensitivity (Low / Normal / High = travel × 1.2 / 1 / 0.8), Dead zone
(Small / Normal / Large = × 0.6 / 1 / 1.6), and Centre (Floating / Fixed). Saved per
game, device-local, with the console profile as the default; "Reset" returns to it.

**Per console**
| Console | Left thumb (primary) | Secondary | Right side | Notes |
| --- | --- | --- | --- | --- |
| Nintendo 64 | analog stick | d-pad, small (104 pt), above the stick | A (64) with B (56) down-left; C buttons as a 44 pt yellow diamond above-right of A | Z is a large tab at the top of the left rail (the trigger under the stick); L and R at the top corners; Start bottom centre |
| Nintendo 3DS | circle pad (add it: left analog to the core) | d-pad (104 pt) below the circle pad, as on the hardware | A/B/X/Y diamond | L, R (and ZL, ZR) as shoulders; the bottom screen stays touchable — the control surface never takes touches that land on a game screen |
| PSP | d-pad | analog nub (secondary stick) below the d-pad | face diamond | as the hardware |
| PlayStation | d-pad | sticks only in analog mode | face diamond | Controls sheet › **Analog controls** (per game, off by default): on shows both sticks under the clusters and L3/R3 in the utility row; off hides them. The Analog (mode) button stays in the utility row either way |
| Everything else | d-pad | — | per the face rules above | no sticks |

The control surface never claims touches outside controls: a touch on a game screen
(DS/3DS bottom screen, pointer games) goes to the game.

## Look

- **Aurora:** frosted controls — `controlFill` with the glass edge, legends at the
  `playerLegend` role (22 pt on two-button faces, 20 pt on diamonds) in `controlInk`.
  Console colours colour the legend and a 2 pt ring (never a faint wash that hides the
  button); the legend keeps 3:1 against the fill. Pressed: `controlPressed` fill and a
  94% scale for 60 ms. D-pad: a frosted-glass cross filling its square like Riso's (arms
  34% of the side; revised 2026-09-26 from small grooves inside a disc), chevrons, the
  held arm lit.
- **Riso:** flat ink shapes as today, at the new sizes.
- The opacity setting applies to Aurora only and never takes face buttons below 0.5.

## Verification

- Pure hit-testing and d-pad direction functions with unit tests: gaps inside a cluster,
  the chord band, rolling, hysteresis, dead zone, diagonals, mirrored layouts.
- UI tests: touches at a button's edge and between A and B register; a d-pad drag that
  leaves the pad keeps its direction; a quick tap never leaves a button held.
- `player.*` identifiers and test tags are unchanged.

## iOS implementation (2026-09-24)

Pure, in `RommioPlayerKit` (the renderer and the overlay resolve the same stage):
`PlayerControlPlan.swift` classifies each family's controls (two-button, diamond,
six-button, Nintendo 64, or the family's own arrangement), with each console's layout
(below), and places them; `PlayerStandardStage.swift` is the phone/tablet geometry;
`PlayerTouchTracker.swift` is the touch model (hit regions, chord, rolling, drift
release, d-pad sectors, `PlayerStickModel` for sticks, ordered output with per-key hold
counts). The iPhone Duo and DS/3DS stages keep their rules with rails sized for the
plan (the DS/3DS screens also start 8 pt under the camera; the Duo keeps its studio
geometry). UI: `RommioUI/PlayerControlsOverlay.swift` (one `PlayerTouchSurfaceView`,
drawing-only control views with the `player.control.*` identifiers, haptics, and Menu). Input goes to the core through `ImmediatePlayerInput` (`sendDigital` /
`sendMotion`, synchronous). Tests: `PlayerTouchModelTests`, `PlayerTouchStickTests`, `PlayerLayoutParityTests`,
`PlayerHapticsPreferenceTests`, `PlayerGameControlsTests`, UI `PlayerTouchControlsTests`
(Debug input log, `ROMMIO_INPUT_LOG=1`) and `PlayerStickControlsTests` (the controls QA
harness: floating centre, PlayStation's Analog controls, DS/3DS touch screens).

Choices the spec leaves open, so Android can match:

- Rolling hysteresis: the held control stays until another is 0.12 nearer (distance
  divided by radius); the chord is entered within 22% of the centre distance and left
  4 pt beyond it. The d-pad dead zone shrinks to 15% while a direction is held, and a
  held direction keeps its sector 4° wider.
- Shoulders on one side sit side by side, each at most 88 × 40, narrower when the rail
  is (N64: Z 92 × 48, L a narrow 64 pt tab at the corner; Z keeps its width while L gives way to 56 pt). Portrait
  (`PlayerControlAnchors.Shoulders.corners`): one row at a fixed top (28 pt under the
  game), every shoulder the row's height (the tallest, at least 44 pt); the main shoulder
  from the screen edge (the safe area's edge) inward over the rail's margin, the others
  inward beside it. The DS/3DS rail stages (and the iPhone Duo) level the row 16 pt above
  the higher of the two stacks, at the shoulders' natural height and against the safe
  area's edges, since their bands are sized from below. Landscape rails keep them at the
  rail's top.
- Secondary sticks sit 10 pt under their side's cluster (a pair lines up under the
  taller one); a small d-pad sits 10 pt above (N64) or below (3DS) the primary stick.
- Portrait: the band is from 12 pt under the game to the top of the home indicator. The
  cluster centre is clamped so shoulders, sticks and the Select · Menu · Start row never
  touch it; the control size gives way (to 0.8) before the game loses more than 20% of
  its height, or when the two clusters would not fit side by side. Both sides' primary
  controls sit at one height when each side's clamps allow it (also in landscape).
- Landscape: both rails are as wide as the wider one (the game stays centred and the
  hand setting never moves it). Menu goes bottom centre when the game leaves 60 pt
  under it, otherwise at the foot of the rail with fewer utilities. The control size
  gives way (to 0.8) before the game loses more than 30% of its height.
- Utilities that don't fit at 64 pt shrink to 44 pt wide (PlayStation has five).
- Six-button pitch is 58 pt; face floors raise a cluster's spacing with its buttons.
- Aurora legends: the console colour stepped to 3.5:1 against the face; a face fades only
  its body with the opacity setting (legend and ring stay at full ink), so the legend keeps
  3:1 even at 0.5. Legend size is the token × (diameter + 24) / 88 (22 on 64 pt, 20 on 56 pt).

Sticks and per-console layouts (iOS):

- The maths is `RommioPlayerKit/PlayerTouchStick.swift`: `PlayerStickModel.output`,
  `gateScale`, `fullTilt`, `notch` port the reference script line for line, and
  `PlayerTouchStickTests` holds every vector it prints (generated from its output) at
  ±0.00005 for all five profiles. `PlayerStickProfile.forStick` picks the profile (N64,
  3DS, PSP, PlayStation left and right).
- Stick touch: the first touch in a zone owns the stick (a second touch there does
  nothing). The touch-down sets the centre (the touch point; with Centre › Fixed, the
  zone's centre), and it stays there until the touch lifts. Values go out on every
  change (not repeated), y down; lift sends 0,0 at once. Haptics: `.stickNotch(gate)`
  when the stick settles into a notch (`notch`, which holds the current one out to 12°);
  otherwise one `.soft` impact at 0.4 on entering full tilt (`fullTilt`), again only
  after leaving it.
  The zone is the hit region (no 14 pt growth) and isn't drawn: the ring (diameter
  2 × travel) draws at the centre, the knob (0.95 × travel) at centre + output × travel,
  and at full tilt the ring gets a bright 2.5 pt edge in the control ink; ring and edge
  draw in the gate's shape (`PlayerControlShape.gate`, `PlayerGatePath`).
- Travel = the profile's × Sensitivity (1.2 / 1 / 0.8) × the control size; dead zone =
  the profile's × (0.6 / 1 / 1.6). The Stick group (Sensitivity,
  Dead zone, Centre, Reset) is in the in-game Controls sheet while the game shows touch
  sticks, stored per game in `player_game_controls` with the Analog setting.
- Short landscape screens: a secondary stick's zone gives way (to 75% of 120 pt, never
  under 92 pt × the control size) before the control size shrinks.
- Nintendo 64: A 64 and B 56 on the two-button rise, 70 pt apart; the C buttons 44 pt
  (the one face under the 50 pt floor, never under 44), about 46 pt from their centre, C-Down
  4 pt clear above-right of A. Start sits beside Menu bottom centre, so the rails have no
  feet. The 17 Pro in landscape runs N64 at 0.9 (the face cluster is 197 pt wide and the
  game keeps 70% of its height).
- Nintendo 3DS: the circle pad is added by the iOS plan (`circlePad`, "Circle Pad", left
  analog, stick 0); Azahar reads it as `RETRO_DEVICE_ANALOG` port 0, index left
  (`citra_libretro/input/input_factory.cpp`, y negated). Stacking the d-pad under it
  makes the DS/3DS band taller (the screens are smaller in portrait); when a band can't
  hold the stack at 0.8, the d-pad moves beside the circle pad (`PlayerControlPlan.compact()`).
- PlayStation: Analog controls are per game (GRDB `player_game_controls`, migration
  `v9_player_game_controls`, device-local), in the in-game Controls sheet › This game.
  Off: no sticks, no L3/R3; the utilities are Select · Start · Analog.
- Rail stages (Duo, DS/3DS) measure the rail box again at the scale they pick, since the
  face, pill and Menu floors stop scaling.
- `PlayerTouchLayout.gameScreens`: a touch on a game screen never starts a control, even
  inside a control's grown hit region; it falls through to the game (DS/3DS pointer).

## Android implementation (2026-09-24)

The same model with the iOS names and the choices above (details in
[android-theme-art.md](android-theme-art.md), "Touch controls v2"): `PlayerControlPlan.kt`,
`PlayerStandardStage.kt` and `PlayerTouchTracker.kt` (pure, `ui/screen/player/`, pinned by
`PlayerTouchModelTest` with the iOS phone sizes and expectations); one
`PointerInputModifierNode` in `PlayerControlsOverlay.kt`; input through
`PlayerEngine.sendDigital` / `sendMotion` (synchronous); haptics `VIRTUAL_KEY` / `CLOCK_TICK`
(and the stick notches' `CONTEXT_CLICK` / `KEYBOARD_TAP`);
the debug `PlayerInputLog` for UI tests. Android-only, for screens iOS doesn't have: the portrait
utility row tightens on phones narrower than about 375 dp (6 dp gaps, pills down to 30 dp), a
rail foot with three or more utilities uses 36 dp pills.

Sticks and per-console layouts (Android): the iOS plan and stage names (`dpadSlot`,
`leftStickPrimary`, `analogControls`, `compact()`, `RailContent`, `stack`, `fitted`, `level`,
`gameScreens`) and the iOS stick names (`PlayerStickProfile.forStick` / `tuned`,
`PlayerStickModel.output`, `gateScale`, `fullTilt`, `notch`, `PlayerStickGate`, in
`PlayerTouchTracker.kt`), pinned by `PlayerTouchModelTest` to every vector the reference script
prints. Full tilt plays `CLOCK_TICK`; a notch `CONTEXT_CLICK` (octagon) or `KEYBOARD_TAP` (square). Analog controls are stored per ROM id (`analog_controls_rom_ids`) and the Stick
group per ROM id (`stick_tuning_<id>`) in `PlayerControlsPreferencesStore`, device-local. The 3DS
goes through the phone stage on Android (there is no DS rail stage), so primaries that can't
share a height at the ideal level at the lowest height both allow, and in landscape the controls
give way in size until a primary stick and the face cluster can. The 3DS isn't playable on
Android yet (Citra isn't bundled; controller-first profile), so its circle pad is plan-level and
seen only in the harness.
