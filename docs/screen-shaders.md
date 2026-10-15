# Screen shaders (iOS)

Game frames drawn the way a console's own screen showed them, designed in **Settings › Screen
playground** before any of it reaches the player. iOS only for now (2026-10-02). The player
still scales with Core Image's linear filter, which is the playground's **Today** screen.

## Where things are

| Piece | File |
|---|---|
| The Metal shaders (sharp, smooth, CRT, LCD, and the signal, glow and response passes), `ScreenStyle` and the 13 screens | `ios/Sources/RommioPlayerKit/ScreenShaders.swift` |
| `ScreenRenderer`: compiles the shaders, chains a screen's passes into a viewport, renders offscreen; `ScreenMotionCard` | `ios/Sources/RommioPlayerKit/ScreenRenderer.swift` |
| `ScreenTestCard`: colour bars, a grey ramp, checkers, fine lines, a circle and a sprite at a screen's native size | `ios/Sources/RommioPlayerKit/ScreenTestCard.swift` |
| The page, its state (`ScreenLab`) and its frames (`ScreenSample`) | `ios/Sources/RommioUI/Pages/Settings/ScreenPlaygroundPage.swift` |
| The live view (an `MTKView` drawing at the display's full pixel size) | `ios/Sources/RommioUI/Pages/Settings/ScreenCanvas.swift` |
| Simulator fixture | `ios/Sources/RommioUI/ScreenPlaygroundQAHarness.swift` |
| Tests | `ios/Tests/RommioPlayerKitTests/ScreenShaderTests.swift` (and the page in `StackBudgetTests`) |

## The screens

| Screen | Shader | Models |
|---|---|---|
| Sharp | sharp | Every game pixel a crisp square, even at fractional scales (sharp-bilinear) |
| Today | smooth | The player now: bilinear smoothing |
| Living-room TV | CRT (composite) | A TV over composite: rainbows on fine detail, blended dithering, slot mask, curvature, glow |
| PVM | CRT | A studio monitor over RGB: thin lines, aperture grille, nearly flat |
| Arcade | CRT | A low-resolution arcade monitor: bold lines, dot mask, lots of glow |
| Game Boy | LCD (mono) | Pea-green segments over a reflective layer, each casting a shadow; very slow (response 60%) |
| Pocket | LCD (mono) | Grey on silver, sharper, a little quicker (45%) |
| GBC | LCD | An unlit colour LCD, gentle grid (30%) |
| GBA | LCD | Unlit: darker and washed out (30%) |
| GBA SP | LCD | The backlit AGS-101: brighter, finer grid (15%) |
| DS | LCD | A TFT with visible RGB stripes (15%) |
| 3DS | LCD | Finer pixels, a faint grid, no colour change (5%) |
| PSP | LCD | A wide, bright LCD with fine stripes; the first model's ghosting (25%) |

`ScreenStyle.suggested(platformSlug:)` picks a platform's own screen: its handheld's LCD, or
the living-room TV for everything else.

## How they work

Every shader measures its fine detail in the display's own pixels (the mask's phosphors, the
LCD's gaps), so the view draws at the drawable's full size rather than scaling up a smaller
render.

- **Signal (CRT).** RGB passes the frame through. Composite and S-Video encode it the way a
  console did: brightness (Y) plus colour (I and Q) on a 3.58 MHz carrier, four samples per
  game pixel (about 171 carrier cycles across the picture, 1.5 pixels a cycle on a SNES).
  A second pass decodes it the way a TV does. A luma lowpass removes the carrier, and with
  it the finest detail, so one-pixel dithering blends. Colour comes from multiplying by the
  carrier and lowpassing. On composite, luma and colour share one wire, so brightness detail
  near the carrier's frequency decodes as colour: the rainbows on checkerboards and fine
  lines. The carrier's phase shifts each line by 0° (Genesis: still vertical bands), 120°
  (NES, SNES: diagonals) or 180° (broadcast), and alternates each frame on the latter two,
  which makes it crawl in motion. A narrower luma filter is sharper but lets the carrier
  through as dot crawl; a wider colour filter gives smoother colour and fewer rainbows.
  S-Video keeps luma and colour apart: colour softens, nothing turns into rainbows.
- **CRT.** Each game line is a beam with a gaussian profile whose width runs from the
  thinnest beam (dark) to the widest (bright), so bright parts swell and dark parts show the
  gaps between lines. Softness filters each line horizontally. On RGB, colour bleed filters
  the chroma (in YIQ) wider than the brightness, a cheap stand-in for a signal. Glow is the
  frame blurred in two separable gaussian passes (its size in game pixels) and added. The
  mask (aperture grille, slot or dots) tints each phosphor column. The brightness makes up
  for the light lost to the mask and the lines, and highlights roll off above 0.8 rather
  than clip. Curvature bends the picture; corners and vignette finish the tube.
- **LCD.** Mono screens map the frame's brightness between the panel's darkest and lightest
  shade. Colour screens take the frame through the panel's response: a gamma of 2.2 plus
  *darken*, a colour matrix, then back. *Colour* blends between the game's own colours and
  the panel's. Subpixel stripes split each pixel into red, green and blue. The grid's gaps
  either darken the pixel's colour (backlit and front-lit screens) or show the layer behind
  (the Game Boy), which the pixel up and to the left shadows.
- **Response (LCD).** Each new frame is mixed, in linear light, with the picture the panel
  still showed: the response is how much of it stays. Moving things leave trails, and a
  sprite drawn on every other frame (a trick games used for transparency) shows half there.
  Redrawing the same frame (a still, a changed setting) shows the same picture; a different
  still starts clean.

The colour matrices are approximations of the community's colour-correction shaders. Tune
them by eye with the Colour slider.

## The playground

- **Picture.** The test card is drawn at the chosen screen's native resolution. Motion test
  plays it at 60 frames a second, with a sweeping bar, a gliding sprite and a sprite on every
  other frame: what shows response, and the carrier's crawl. The other frames are recent
  save previews, one per game: the frame each save was made on, stored as a
  JPEG at most 320 pixels wide. A DS preview holds both screens side by side (512×192 scaled
  to 320×120), so its grid is coarser than on the console. Choosing a save picks its
  console's screen.
- **Screens and sliders.** Choosing a screen loads its settings. The controls edit them live
  (CRT: signal, carrier, colour and luma filters, lines, beam, softness, mask, glow and its
  size, glass; LCD: grid, gaps, shadow or subpixels, colour, darken, brightness, response),
  and *Back to …* restores them. Edits aren't saved.
- **Looking closely.** Tap the screen to magnify a spot 4×; tap again to step back. The full
  screen button shows the screen alone on black.

## Checking

- `swift test --package-path ios --filter ScreenShaderTests` renders every screen over its
  test card (whole and magnified) into `$TMPDIR/rommio-screens` and checks that none comes
  out nearly black or white. It also measures the signal (one-pixel stripes: composite
  blends them and colours them, RGB keeps them apart and grey, S-Video adds no colour) and
  the response (a still is unchanged; motion trails).
- In a Debug build, `SIMCTL_CHILD_ROMMIO_SCREENS_QA=1 xcrun simctl launch <UDID>
  io.github.mattsays.rommio.ios` opens the page on its own (no server or profile), using
  the save previews already in that Simulator. Their console is guessed from their size.
- Judge the looks on a phone. The Simulator draws them correctly, but a mask or a grid
  depends on the display's pixel density.

## Not done yet

- The player: `PlayerSurfaceController` draws each screen (`layout.screens`) through Core
  Image. Drawing those rects through `ScreenRenderer` with the chosen screen (passing each
  new frame's number, so response and the carrier advance) is the next step, along with
  remembering a screen per platform.
- Beyond the community's best shaders: RF noise and interference, interlacing, the
  phosphors' own decay, convergence errors, and per-console signal quirks (the NES's
  palette generation, the Genesis's video encoder).
- Android: an AGSL or OpenGL ES counterpart.
