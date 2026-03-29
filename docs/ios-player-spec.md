# iOS Player Spec

This document is the iOS player appendix for Rommio. It describes how the embedded player behaves on iPhone and iPad, how overlays work, and how each supported platform family is expected to present its window and controls.

The runtime catalog and support-status source of truth remain:

- [core-matrix.md](core-matrix.md)
- [core-matrix-ios.md](core-matrix-ios.md)
- [validation-matrix.md](validation-matrix.md)

## Player Role

The embedded player exists to let a user launch compatible titles from Rommio without leaving the app. It is not intended to expose the full configuration surface of a standalone emulator frontend.

The iOS player product priorities are:

- enter play quickly from game detail
- keep the viewport as clear as possible
- make touch controls practical on phone-sized devices where a family is validated for touch
- preserve controller-first access for heavier families without pretending they are touch-ready
- keep iOS delivery App Store-safe through bundled, signed core provisioning rather than runtime executable downloads

## Shared Player Behavior

### Entry and exit

The player is entered from game detail after the app has enough local and cached metadata to resolve the title, runtime family, bundled core, and local file.

The player should be able to launch from:

- installed local content with cached metadata
- local-only installs whose RomM entry has disappeared

It should not require a fresh network fetch to enter when the install, metadata, and runtime path are already resolvable locally.

### Menu and pause behavior

The player uses a `Menu` control rather than a permanently visible header-level pause button.

Shipping behavior:

- touch families get a `Menu` button inside the overlay model
- controller-first families still expose the menu in the appropriate overlay position
- the old warning text inside the viewport for controller-only platforms is not part of the current product behavior

The pause/menu sheet is the place for:

- resume
- controls/preferences
- quick save and latest-state load
- sync saves
- reset core
- leave game

### Offline and local-only behavior

The player should continue to work when:

- the device is offline but the install and metadata are already cached
- the RomM title has been deleted, but the local install still exists

The player should degrade into local-only behavior rather than becoming unusable when server metadata is gone.

### Core and BIOS dependency handling

The iOS player remains libretro-oriented, but runtime delivery differs from Android.

Shared rules:

- missing core should surface as recoverable runtime readiness, not as a crash
- missing BIOS should surface as an actionable dependency problem for families that require BIOS files
- recommended-core handling remains the primary UX rather than a full advanced-core chooser
- executable cores must come from bundled, code-signed dylibs inside the app bundle
- the app may auto-provision a shipped core into runtime inventory, but it must not download executable cores at runtime

### Controller vs touch

Support tiers are product-visible:

- `Touch`: full embedded play with a dedicated iOS touch layout
- `Controller`: embedded play exists, but the family is controller-first
- `Unsupported`: no player entry path should be presented as playable

Controller-first does not mean hidden. It means:

- show the title as playable
- allow player entry
- do not render gameplay touch controls
- keep menu access available

## Overlay Model

### Fade groups

The overlay is split into two fade groups:

- `Primary controls`: d-pad, face buttons, and triggers
- `Tertiary controls`: menu button and system/control buttons such as start/select

Shipping behavior:

- the groups fade separately
- tertiary controls do not wake back to full visibility unless one of those tertiary controls is used
- pause sheet, control sheet, and similar modal states suspend fading and keep overlays visible

### Visual theming

The player supports:

- OLED black mode for the actual player window
- console-color styling for controls

OLED black mode applies to the player surface, not to every sheet in the app. Pause and controls sheets remain on the standard branded dark panel styling.

### Control tuning

The player exposes tuning rather than freeform editing.

Current tuning concepts:

- show touch controls
- auto-hide on controller
- left-handed swap
- opacity
- global size
- deadzone
- controller type
- rumble to device
- OLED black mode
- console colors

Freeform drag-and-resize layout editing is not the current direction.

## Window and Layout Model

The iOS player uses fixed-zone control layouts rather than floating, per-element freeform placement.

### Shared layout rules

- non-trigger touch families can generally work in portrait or landscape when their family allows it
- trigger-based touch families are landscape-only unless a family-specific exception exists
- `DS / DSi-enhanced` is portrait-only
- menu placement is orientation-aware and separate from primary gameplay controls
- necessary overlap with the viewport is acceptable when device size leaves no better option
- controller-first families keep the viewport clear and render only the menu / tertiary path

### Portrait model

Portrait uses a lower control band:

- the viewport stays top-aligned below the top safe-area band
- `Menu` sits in the upper band rather than inside the gameplay cluster
- a dedicated control region occupies the lower part of the device
- primary gameplay zones live left and right in fixed positions relative to screen edges
- system buttons sit in a centered row under the viewport

### Landscape model

Landscape uses side-rail behavior:

- the viewport remains centered
- primary gameplay zones live in fixed left and right rails near the device edges
- trigger zones sit above the main gameplay zones for families that need them
- menu sits on the lower edge near the viewport
- system buttons sit opposite the menu rather than split across both sides

## Touch-First Families With 2-Button Layouts

### Family scope

- `NES / Famicom`
- `Game Boy / Game Boy Color`
- `Atari 2600 / 7800 / Lynx`
- `PC Engine / TurboGrafx-16`
- `Neo Geo Pocket`
- `WonderSwan`

### Orientation policy

These families use the general touch-first orientation behavior and do not require landscape-only play.

### Viewport behavior

The viewport follows the family’s expected aspect ratio, with controls staying secondary to the play area.

### Control-zone concept

The primary layout is:

- conventional cross d-pad on the left
- compact 2-button cluster on the right
- centered system buttons below the viewport

The visible d-pad shape stays compact, while the touch hit area is intentionally larger than the visible art.

### Menu and system buttons

- `Menu` remains separate from gameplay buttons
- `Start`, `Select`, `Option`, or equivalent live in the tertiary/system row

### Special notes

These families are the template for low-friction mobile play on iOS and should remain the cleanest touch experience in the app.

## Touch-First Families With 4-Button Layouts

### Family scope

- `Arcade`
- `Sega 8/16-bit`

### Orientation policy

These families use touch-first behavior without the trigger requirement of the heavier console families.

### Viewport behavior

The viewport stays central, while the right-side cluster uses a family-specific arrangement rather than a generic, one-size-fits-all face layout.

### Control-zone concept

- left d-pad zone
- right 4-button zone
- centered system buttons beneath the viewport

The exact button labels change by family, but the family-specific arrangement should remain recognizable rather than collapsing into generic `A/B/X/Y` semantics.

### Menu and system buttons

- `Menu` is separate from gameplay inputs
- `Start`, `Mode`, `Coin`, and related system actions stay in the tertiary row

## Trigger-Based Landscape-Only Families

### Family scope

- `SNES / Super Famicom`
- `Game Boy Advance`
- `PlayStation`

### Orientation policy

These families are landscape-only in the current iOS player.

### Viewport behavior

The viewport is centered with fixed side rails. It is acceptable for the controls to intrude more into the side or lower regions than lighter touch families.

### Control-zone concept

- d-pad rail on one side
- face-button rail on the opposite side
- shoulder zones above the rails
- tertiary system/menu controls near the lower edge

### Menu and system buttons

- menu is the pause entry and remains separate from the face/trigger cluster
- `Start` and `Select`-style buttons are tertiary controls rather than being mixed into the gameplay cluster

### Special notes

These families are where iPhone and iPad ergonomics matter most. The current design favors a practical fixed-zone layout over emulator-style freeform editing.

## DS / DSi-Enhanced Portrait-Only Family

### Family scope

- `nds`
- `nintendo-dsi`
- `dsi` alias handling where present in platform mapping

### Orientation policy

`DS / DSi-enhanced` is portrait-only.

### Viewport behavior

The family uses a tall handheld viewport rather than the wider TV/console framing used by most other systems.

### Control-zone concept

Shipping behavior:

- portrait-only lower control band
- left d-pad
- right Nintendo-style 4-button face cluster
- system buttons placed beneath the viewport
- shoulder buttons placed as a dedicated upper row in portrait
- lower-screen pointer routing for stylus-driven input

### Menu and system buttons

- menu remains separate from the main gameplay cluster
- `Start` and `Select` remain tertiary controls

### Special notes

This family is the main exception to the controller-first handheld rule on iOS. It is touch-supported because the iOS player already treats it as a portrait handheld rather than a controller-dependent console.

Near-term direction should remain conservative: keep `DS / DSi-enhanced` within one family unless a future runtime or UI need truly requires splitting them apart.

## Controller-First Families

### Family scope

- `N64`
- `PSP`
- `Dreamcast / NAOMI`
- `3DO`
- `Virtual Boy`
- `GameCube / Wii`
- `3DS`
- `DOS`
- `Sega 32X`

### Orientation policy

These families do not currently ship with gameplay touch layouts. They remain controller-first even when they are visible in the embedded runtime catalog.

### Viewport behavior

The viewport should stay as clear as possible because the app is not trying to approximate a full touch scheme for these systems.

### Menu and system behavior

Shipping behavior:

- menu remains available in the player
- controller-only warning text should not occupy the viewport
- no primary touch controls are rendered for gameplay

### Special notes by family

- `DOS` remains controller-first and blocked until a keyboard/pointer helper model is validated
- `GameCube / Wii`, `3DS`, `N64`, `PSP`, and `Dreamcast / NAOMI` remain blocked until the iOS hardware-render host exists and validation clears
- `Dreamcast / NAOMI` and `3DO` also remain subject to BIOS or firmware requirements where applicable
- `Dreamcast / NAOMI`, `3DO`, `Virtual Boy`, and `Sega 32X` are breadth-expansion families rather than touch-first mobile showcases

### Near-term direction

The current roadmap on iOS favors correctness and App Store-safe delivery first:

- promote more validated families into `Controller`
- only add touch layouts when a family-specific iOS control model is clearly worthwhile
- keep blocked families visible with explicit reasons rather than pretending they do not exist

## Unsupported Families

Unsupported families are still browseable in Rommio if RomM exposes them, but the iOS player should not pretend they are natively playable.

Expected product behavior:

- show the support tier clearly in Library, platform detail, collection detail, and game detail
- do not offer a misleading play path
- preserve metadata browsing even when embedded playback is unavailable

Unsupported does not mean hidden. It means outside the validated iOS player scope.

## Promotion Rules

The iOS player spec follows the validation rules in [validation-matrix.md](validation-matrix.md):

- a family becomes `Controller` only after runtime behavior is validated on iPhone and iPad
- a family becomes `Touch` only after controller-level validation passes and the mobile control/window model is also validated
- a blocked family may still remain visible in browse surfaces with an explicit reason, but it should not be documented as fully shipping

The player spec should stay descriptive rather than aspirational. If the support tier changes, the runtime and validation docs should be updated first, and this document should then be aligned to match.
