# Haptics (iOS)

Rumble and controller feel on the Taptic Engine, designed in **Settings › Haptics playground**
before any of it reaches the player. iOS only for now (by request, 2026-09-28); Android's
counterparts are noted in the research summary below but not built.

## Where things are

| Piece | File |
|---|---|
| Designs as data (`HapticRecipe`: taps, buzzes, curves → `CHHapticPattern`) | `ios/Sources/RommioUI/Haptics/HapticRecipe.swift` |
| Engine: one-shot recipes and live continuous voices; restarts after the system stops it | `ios/Sources/RommioUI/Haptics/HapticsEngine.swift` |
| Rumble: `TwoVoiceRumble` (default), `BlendedRumble`, today's `OneTapRumble`, `MotorRamp`, 12 recorded `RumbleScene`s | `ios/Sources/RommioUI/Haptics/Rumble.swift` |
| Controller feel: 16 button materials, 7 d-pads (`DpadTracker`), 7 sticks (`StickTexture`), 21 rim textures (`RimTexture`), 11 console pads | `ios/Sources/RommioUI/Haptics/ControllerFeels.swift` |
| Noisy rim textures: scattered grit (`StickFeel.Scatter`), looped noise beds (`NoiseBed`: hiss and grains), `NoiseTuning`, the noise sources | `ios/Sources/RommioUI/Haptics/RimNoise.swift` |
| Sounds: 22 synthesised clicks, tocks and thunks (`SoundRecipe`), rendered to WAV and registered with the engine | `ios/Sources/RommioUI/Haptics/SoundRecipe.swift` |
| Playground state (renderer, scene playback at 60 fps, meters) | `ios/Sources/RommioUI/Haptics/HapticsLab.swift` |
| The page and its touch pads | `ios/Sources/RommioUI/Pages/Settings/HapticsPlaygroundPage.swift`, `HapticsPads.swift` |
| Tests | `ios/Tests/RommioUITests/HapticsPlaygroundTests.swift`, `RimNoiseTests.swift` (and the page in `StackBudgetTests`) |

Its sibling for the screen, Settings › Screen playground, is in [screen-shaders.md](screen-shaders.md).

The playground changes nothing in the player. Tune by feel on a phone (the Simulator has no
Taptic Engine), then move the chosen designs into the player.

## Vocabulary

Intensity and sharpness are 0…1. Sharpness is roughly the actuator's drive frequency (about
80 Hz at 0 to 230 Hz at 1 on measured iPhones, loudest near 0.73), so it changes how strong a
tap feels as well as its texture: low is a deep thud, high a crisp tick. A press is built from
a stroke (a short buzz for a soft dome, nothing for a snap switch), the click (a tap) and the
bottom-out (a second, duller tap 10–25 ms later). Releases are lighter and sharper than presses.

## Rumble

libretro rumble is a **state**: `set_rumble_state(port, effect, strength)` means "this motor
at this strength until told otherwise". Cores report differently: gambatte (GB/GBC carts),
mupen64plus (N64) and melonDS DS only on a change; pcsx_rearmed with a 0–255 strong motor and
an on/off weak one; mGBA both motors every frame, averaged.

**Today** (`deliverDeviceRumble` in `PlayerFeatureModel.swift`): every report above zero becomes
one `UIImpactFeedbackGenerator` tap and zeros are dropped. A held rumble is a single tap; mGBA
becomes up to 120 taps a second that never stop. The playground's "Today" renderer reproduces
this for comparison.

**Two voices** (`TwoVoiceRumble`, the default; revised 2026-09-28): one looping continuous
voice per motor, the strong motor at sharpness 0.12 (a deep rumble) and the weak at 0.72 (a
buzz). Each motor's level is strength^0.55 (low strengths are felt); the weak motor's is held
to 80% (at full the buzz overpowered the rumble). Each voice eases toward its level like a
motor spinning up and winding down (`MotorRamp`, one-pole time constants: strong 35 ms up,
90 ms down; weak 15 ms up, 45 ms down), so hits have a moment of inertia; a motor starting
above half also adds an onset tap. Changes under 0.02 aren't sent; the voices stop once wound
down and silent for 15 frames (stopping on every zero clicks). Rumble runs frame by frame for
the motor pad as well, so lifting the finger winds the motors down.

**Blend** (`BlendedRumble`): both motors as one voice, their felt sum 1 − (1 − strong)(1 −
weak) with the sharpness sliding between the two, and the same ramps. The first version of
two voices also halved both when they ran together, so together they felt weaker than either
alone; the blend was the answer to that, but on the phone two voices without the halving felt
better.

Before rumble can ship in more consoles, the player must also turn it on in the cores:
mupen64plus `mupen64plus-pak1 = rumble` (default is the Controller Pak), pcsx_rearmed needs
the DualShock device subclass (`RETRO_DEVICE_SUBCLASS(ANALOG, 1)`), melonDS DS a Rumble Pak in
Slot-2. The bridge offers only plain `RETRO_DEVICE_ANALOG` today; pcsx_rearmed may treat that
as no pad (from its source, not tested).

## Controls

- **D-pads** (`DpadTracker`, revised 2026-09-28: the first dead zone, a quarter of the
  radius, was too twitchy side to side). A **pivot** pad (the Nintendo cross, N64 plus,
  Genesis disc, tiny tact) reads the thumb's angle: diagonals get a narrower sector than
  cardinals (26–34° against 45°), the held direction keeps the thumb 10° past its sector, and
  the thumb leaves the centre at the dead zone (0.34–0.44 of the radius) but is back only
  inside 72% of it. **Switches** (the arcade stick, the PlayStation's arms) are four
  independent microswitches: each closes when its axis passes the throw (0.42–0.46 of the
  radius) and opens below 60% of it, clicking on and off by itself, so a side-to-side sweep
  is left off, right on, however the thumb wobbles; a diagonal is two switches.
- **Sticks** (`StickTexture`, `StickTuning`, revised 2026-09-28). The texture knows speed
  and force. Reaching the rim is a smack whose strength (×0.2–1) and sharpness (−0.3 to +0.3)
  follow the radial speed: soft below 2 stick radii a second, full at `fullHit` (30 by
  default; at 16 nearly every arrival maxed out). Force is how far past the rim the thumb
  pushes, full at `fullPush` (0.8 of the travel), eased (power 1.3); glide is the speed along
  the rim (two turns a second is full). Force and glide both make the grit stronger and
  sharper, force makes it denser (spacing × 1.25 − 0.5 × force); notch clicks follow both,
  the hum both. The spring back is as strong as the stick was far out when released, or as
  fast as it was drawn back. The playground shows the last hit, push and glide, with sliders
  for `fullHit` and `fullPush`.
- N64 worn's grit: every 8° at 0.12–0.28 (was 5° at 0.18–0.4: too textured). DualShock
  corners: a click with a dull tail (0.62 at sharpness 0.36, then 0.2 at 0.22), not a buzz.

- **Contact floor** (`StickTuning.contactFloor`, 0.15, revised 2026-09-28): any contact with
  the rim plays at least this strong — plastic on plastic always gives something — and push
  and speed add to it; the hum keeps at least 80% of it while the thumb slides. Before it, the
  light textures faded toward nothing at no push and couldn't be felt.
- **Rim textures** (revised 2026-09-28): every stick has some texture at the rim, because
  the rim is where the thumb learns where it is (N64 new gained a fine rib, the 3DS circle
  pad a low glide with faint ticks every 12°, toward the PSP's hum but less buzzy). The
  playground's "Rim texture" puts any of 21 on any stick: first the ten noisy ones (see
  Noise), then the first, regular designs: silk, satin, glide, fine sand, sandpaper, coarse
  grit, gravel, ribbed, rumble strip, velvet drag, fizz. A regular texture is taps (`Grain`:
  spacing even, in pairs, or irregular), a hum, or both.

## Noise

Added 2026-10-06, after the regular rim textures turned out to feel like a pitched buzz rather
than grit. Plotted, they were pulse trains: the same short burst every so many degrees, so at a
steady speed all their energy sat in a few lines around the actuator's frequency, like a tone.
Worse, a grain could only fire on a touch update (120 a second), so the touch clock showed up
as lines at 120 and 240 Hz, and dense textures (fine sand, 115 taps a second at a turn a second)
turned into a 120 Hz drone whatever their "irregular" spacing. Real friction is noise: no
repetition, energy spread across frequencies. The Taptic Engine only moves near its resonance,
so broadband noise is out of reach, but noise around the carrier isn't, and neither is
irregular timing. The first ten rim textures are noisy, three ways:

- **Scattered grit** (`StickFeel.Scatter`: scattered sand, grit, gravel). The number of grains
  for each stretch of rim is random (Poisson, a mean of one every `degrees`), some bring one
  or two more in a clump, and each plays at a random moment between this touch update and the
  next (`ScatterGrain.offset` × the measured touch interval), so nothing lands on the touch
  clock. Strength and sharpness are drawn per grain (mostly small, now and then large) and
  rise with push and glide like the regular grit. Each batch plays with its own player
  (`HapticsEngine.playOnce`), so the cache doesn't fill with one-offs.
- **Hiss** (`NoiseBed.Hiss`: hiss, rasp, grind). A looped bed of back-to-back buzzes a few
  milliseconds long (4–10 ms), each at a random strength (white: anywhere each step; pink:
  mostly small steps, Voss–McCartney; brown: a drift) and a wandering sharpness: noise
  around the carrier. Strength isn't a parameter curve, since the bed's live level uses the
  intensity control.
- **Grain beds** (`NoiseBed.Grains`: sand bed, gravel bed, and sandpaper hiss with a hiss
  under it). A loop of taps at random (Poisson) times, a few in clumps.

A bed (`HapticVoice` looping its recipe, 1.5 s, from a random point each touch) plays while
the thumb slides along the rim: its level follows push and speed like the hum (at least near
the contact floor), and its playback rate follows the thumb's speed (a turn a second plays it
as made, two twice as fast), so sliding faster makes the grain finer, as on a real surface.
Worn plastic layers scattered grit over a soft pink hiss.

The **Noise bench** (near the top of the page) plays one on its own, constantly, as if a
thumb slid a turn a second (`NoiseBenchSettings`; scattered grit becomes a loop of grains at
that rate): amplitude, speed (playback rate) and a sharpness shift move live; grain density
and a hiss's grain (step, 4–15 ms), depth (1 − floor) and colour remake the loop. Dense loops
are shortened (`NoiseBed.fitted`, at least half a second) to stay within about 700 events.

Sticks › Noise (shown for a noisy texture) tunes them live: noise strength, grain density
(scatter and bed grains), how much a bed's speed follows the thumb (0: fixed), and a hiss's
colour. `RimNoiseTests` checks the property that was missing: the autocorrelation of grain
times (how strongly they repeat at a fixed interval, 6–60 ms) is under 0.2 for every scattered
texture and at most half the regular grit's (which is above 0.3, as is the old fine sand's).

## Sounds

Buttons, d-pads and sticks (notches, the rim, the spring back) have a quiet sound in the same
pattern as their haptic, so they land together. The aim is the Magic Trackpad's click:
invisible, only noticed by its absence. So a sound is the pitchless click a physical contact
makes, synthesised from band-limited noise and described only by what the control is made
of (`SoundRecipe`): **hardness** (soft rubber is duller and a little longer, a hard switch
brighter and under a millisecond), **mass** (a dull, low thud of low-passed noise under the
click), a **second contact** (a switch's two leaves) and a faint **slide** before it (a
trigger's travel). No tones: the first set (2026-09-27) had decaying sine pings and read as
tonal "effects"; a test now fails any sound whose autocorrelation shows a period.

Each sound renders in three styles (Invisible, the default; Muted, duller; Crisp, brighter)
and four takes whose band and timing differ slightly; repeated presses rotate through the
takes so they don't sound like one sample. Files are 16-bit WAV, rendered on first use and
registered with the engine (`registerAudioResource`, no volume envelope). Each design carries
its sound at a volume of 0.15–0.6, × the playground's sound level (35% by default); a softer
hit is quieter too. With sounds on the engine shares the app's audio session, set to ambient
(mixes with other audio, follows the Ring/Silent switch; the player sets playback again when a
game starts); with sounds off it plays haptics only, with the lowest latency. A reset of the
engine forgets registered sounds; they are registered again.

## Latency

From touch to haptic, what the app controls (revised 2026-09-28; the playground's "Touch to
haptic" row shows the last press and the average of 20, from the screen registering the touch
to Core Haptics accepting the pattern):

- Touches come straight from UIKit (`padTouch`, a gesture recognizer that begins on
  touch-down): a SwiftUI gesture reaches its handler up to a frame later, and a view's own
  touch handling inside a scroll view is held back. Pads keep the touch from the page's
  scrolling; the material cards share it.
- The material cards wait 12 ms (was 35) before pressing, in case the finger is starting a
  scroll.
- Every press opens with a crisp contact at 0: a press that opened with a deep buzz or clicked
  15–20 ms in read as late (low sharpness is an ~80 Hz drive, a full cycle is ~12 ms).
- One player per design, made when the page opens and restarted on each press (making a
  player per press was main-thread work in between).
- The engine is started again as soon as the app returns (the system stops it in the
  background, and starting it blocks).

The actuator's own response comes after and can't be measured from the app. The player's
touch controls already take touches from UIKit.

## Engine notes

- Two `CHHapticEngine`s (revised 2026-09-28). Haptics play on a haptics-only engine
  (`playsHapticsOnly`, lowest start latency), which never waits on audio; sounds on an
  audio-only one (`playsAudioOnly`). Every sound is rendered and registered in the background
  when the page opens or the style changes, and a press whose sound isn't ready plays without
  it. With one shared engine, and each sound's four takes rendered and registered the first
  time they played, presses stalled at touch-down and landed as the finger lifted.
  Auto-shutdown is off. The system stops the engines on backgrounding and audio
  interruptions; the next play starts them.
  A player whose engine stopped must not be touched (Core Haptics raises an Objective-C
  exception), so voices are rebuilt after every stop or reset.
- Live intensity changes multiply (0…1) and apply to a whole player: accents go through
  separate one-shot players, never the voice.
- A continuous event lasts at most 30 s; voices loop one. Curves honour 16 points.
- In the iOS 27 SDK `UIImpactFeedbackGenerator(style:)` is deprecated (use `init(style:view:)`);
  the player's touch controls still use it.
