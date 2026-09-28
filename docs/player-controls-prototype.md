# Player controls prototype — 2026-09-22

The player fits video at its native aspect ratio and uses the surrounding space
for touch controls. Video is reduced only when the controls cannot fit beside it. All 14 existing touch families use the same deck implementation on each
client: NES, SNES, Game Boy/Color, GBA, Atari, TG16 and related handhelds, arcade,
DS, Genesis/Sega CD, Master System, Game Gear, PlayStation, N64, and PSP.
Controller-first platforms retain Menu without a gameplay deck.

## Layout and input

- Landscape first fits video to the full safe-area height. Side controls and
  utility buttons share the existing pillarboxes; there is no bottom toolbar
  reservation for video. Rails use only their required width plus a 4-point/dp
  gap. Default controls can compact from 48 to 44-point/dp targets to preserve
  video size; explicitly enlarged controls retain their requested size subject
  to screen bounds. If side space is still insufficient, video shrinks only as
  far as required, preserving its aspect ratio and centering.
- Landscape Menu stays at the lower leading edge, including when controls hide.
  Controller-first/hidden layouts reserve only enough side space for Menu.
  Portrait keeps the controls and utility row below video.
- The shared contract in `player-controls.json` now includes the older touch
  families. It generates native input mappings, not duplicated legacy platform
  profiles. Utility controls are laid out as a row, rather than video coordinates.
- Standard targets are 48 points/dp, with smaller visual faces. Control scaling
  is bounded by available space. Geometry tests cover 375×667 and 420×912,
  their landscape rotations, both handedness settings, and three scales; all
  targets remain at least 44 points/dp, mutually disjoint and outside video.
- Each D-pad is one continuous eight-way input surface, permitting one-thumb
  diagonals and sliding. Buttons and sticks retain independent touch ownership;
  release, cancellation and removal neutralize inputs.
- Sticks use 76-point/dp targets, with their centers at rail y=256. The gap from
  the Down/Cross target is 24 points before scaling, including on the dense PSX
  layout. N64 C buttons remain independent of A/B and combine on the right axis.
- Left-handed mode swaps the two groups without reversing their internal labels.
  Size, opacity, console colors, OLED black and controller auto-hide remain available.
- DS pointer input uses the same viewport geometry as the renderer. Game saves
  and resume snapshots retain the separate flows in [save-sync-spec.md](save-sync-spec.md).

## Visual treatment

Dark faces, thin borders and restrained console accents replace large filled
circles. Shoulder and utility buttons use rounded pills; sticks have a distinct
thumb cap. N64 uses blue A, green B and yellow C buttons; PSX/PSP use symbol colors;
SNES/DS use four face colors; Sega uses subdued faces and a red Start accent.
New preferences enable console colors; existing saved choices remain respected.
The iOS Metal surface lets the themed canvas show around the video, including
the safe-area edges. Informational success banners clear after 2.5 seconds and
sit below the top safe area (the Dynamic Island); their detail remains in the
progress panel. Automatic saves (launch, periodic, pause, background) show no
banner, only their status in the panel (revised 2026-09-27). Errors remain visible.

## Fable design review

Reviewed in the Claude desktop app, **Fable 5.1**, conversation **Rommio controls
design review**. The review inspected the previous screenshots and implementation,
then four revised iOS/Android captures. It judged the revision a workable prototype.

Adopted feedback: reserve video space, continuous D-pad diagonals, quieter console
colors, shoulder pills, larger text, N64 spacing, and a larger stick-to-D-pad gap.
Fable acknowledged the final stick-gap correction and reported no outstanding
review findings. An apparently pressed Android PSP Cross in one screenshot was checked explicitly:
actual-player tests now assert its released state before taking screenshots.

The subsequent video-size correction moves landscape utilities into the side
rails; Fable's review above predates that correction. PSX L3/R3/Analog remain
visible for discoverability. Wide games such as PSP still need some video
reduction where the minimum usable controls exceed the natural side space.

## Qualification

The checks below passed independently on iPhone Air (iOS 27 arm64) and the
API 35 arm64 Android emulator. This is simulator/emulator prototype
qualification, not physical-device ergonomics or a retail-game compatibility
claim. PSX/Sega CD BIOS-backed gameplay is still outside this pass; their input
layouts are covered. Live save sync was not rerun for this visual change.

### Initial prototype test coverage — passed

| Check | iOS | Android |
| --- | --- | --- |
| Build | iPhone Air simulator Debug app | Standard Debug app and test APK |
| Focused unit tests | 10: input profiles, layout geometry, preference persistence | 2: input profiles and layout geometry |
| Production control component | All 14 families / 149 controls; press/release, stick travel, C directions, Analog combo, slide-out, eight-way D-pad, mirroring, portrait/landscape, hiding controls, reachable Menu, no viewport overlap | Same 14 families / 149 controls; also cancellation and independent simultaneous D-pad/face input |
| Actual player | Genesis, SMS, Game Gear, N64, PSP: launch, theme setting, touch, snapshot save/restore, game-save history, reset, exit | Same five: launch, touch, released state, snapshot save/restore, exit; three existing NES progress regressions |

The actual-player fixtures are unchanged from the [earlier qualification](player-controls-validation.md):
original Sega test programs, PSP `cubevfpu`, and the available Majora's Mask ROM.
Controls for the other families are qualified with the production input component;
this does not claim that a retail game for every family was played in this pass.

Reproduce the control matrix with `scripts/ios/validate_player_controls.sh` and
Android `ExpansionControlsTest`. Geometry checks are `PlayerLayoutParityTests`
on Apple and `PlayerControlDeckLayoutTest` on Android. For real-player tests,
use the disposable fixture setup described in the earlier qualification and
`ExpansionControlsTests/testActualPlayerControlsAndMenus` / `PlayerScreenProgressTest`.
The iOS settings gesture scrolls inside the safe-area margin, avoiding sliders,
and taps the trailing switch rather than the row label before waiting for persistence.

Screenshots: [iPhone PSX](validation/player-controls-prototype/iphone-air-psx.png),
[iPhone DS portrait, mirrored](validation/player-controls-prototype/iphone-air-ds-portrait-mirrored.png),
[iPhone N64](validation/player-controls-prototype/iphone-air-n64.png),
[iPhone PSP](validation/player-controls-prototype/iphone-air-psp.png),
[Android N64](validation/player-controls-prototype/android-n64.png),
[Android PSP](validation/player-controls-prototype/android-psp.png).

## Video-size correction — 2026-09-22

The first prototype unnecessarily reserved 56 points/dp below landscape video
and added margins on both sides of each control rail. The revised geometry
removes that bottom reservation and puts utilities below the side controls.
Regression checks explicitly require full natural video size when pillarboxing
is sufficient (4:3 on a wide landscape display and Game Boy on iPhone Air),
and check the exact minimum width reservation for tighter widescreen layouts.
The all-family geometry matrix still covers clipping, overlap, target sizes,
mirroring, orientation and control scale on both platforms.

Fresh layout captures: [Game Boy at full available height](validation/player-video-layout/iphone-air-gb.png),
[N64 controls](validation/player-video-layout/iphone-air-n64-controls.png), and
[PSP controls](validation/player-video-layout/iphone-air-psp-controls.png).
These are the production controls around a geometry test viewport.

Validation of the correction: iOS simulator build and seven layout unit tests
passed; Android Standard Debug app/test builds and three layout unit tests
passed. Android's full 14-family control matrix passed. The iPhone Air run
exercised all 14 families; its only failures were exact-zero stick assertions
caused by a 1% orthogonal offset from pixel rounding. After changing that
assertion to a 2% tolerance while retaining full-travel and release checks,
N64, PSP and PSX passed a focused rerun. No product input change was needed.
The corrected layout was also visually confirmed in the actual PSP player
([native capture](validation/player-video-layout/iphone-air-psp-player.png)).
The earlier five-system gameplay/save qualification above was not repeated
for this layout correction. Physical-device ergonomics remain unqualified.
