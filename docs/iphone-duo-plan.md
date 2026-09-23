# iPhone Duo Plan

This plan adapts the iOS client to iPhone Duo (iOS 27.1). It follows Apple's [Designing for iPhone Duo](https://developer.apple.com/design/human-interface-guidelines/designing-for-iphone-duo) guidance and the iPhone Duo tech talks (111461, 111462, 111463, 111466). Fold support is iOS-only; Android foldables are out of scope for this plan.

## Decisions

- Raise the iOS deployment target from 17.0 to 18.0. Duo-only APIs (`ArrangementView`, `ReservedRegion`) sit behind `#available(iOS 27.1, *)`.
- No fold or dual-screen DS work on Android.
- Out of scope: tent-mode two-player.
- Nintendo DS screens are always stacked, top screen above the touch screen (decided 2026-09-22, replacing items 17 and 19 below). With the device unfolded and held in portrait, the hinge runs between the two screens.

## Findings that motivate this plan

Audited on the Xcode 27.1 beta iPhone Duo simulator, iOS 27.1 (24A94401), against romm.borchetta.xyz. The build under test was made with Xcode 27.0, so it ran in Apple's compatibility mode.

### Player

- The DS video is one 2:3 column centred horizontally. When open, the hinge runs through both DS screens and the touch screen. Each DS screen gets about 343×257 pt.
- Quick stylus taps are lost; only a press of about 0.4 s registers. `DSPointerOverlay` sends press and release as unordered `Task`s, and the bridge latches a press for a single frame.
- In the Open pose, "Leave game" sits below the `.medium` pause-sheet detent.
- The Controls sheet covers the game completely. It also offers controller settings, but physical controller input never reaches the core.
- `PlayerOrientationPolicy` is never applied, and layout is chosen by comparing height and width.

### App shell

- The tab bar is a centred bottom bar on the fold, and Library is a single full-width list.
- Home buries "Continue playing" below the summary card, and Downloads buries its list below the summary card.
- The game detail hero is a fixed 280 pt, which makes the title unreadable at a 515 pt height.
- The one-touch QR sheet is clipped in the Open pose, and the notice banner covers the back button.

### Content

- The platform list shows "0 files" while game detail shows "1 file available".
- "Missing core" is shown for bundled cores that only need setup.
- Settings shows "1 Games" and "1 Files".
- Settings shows "Syncing" beside "Online and ready".
- Settings shows the raw container path.
- "Remove active profile" is placed beside "Current profile".
- Collection section subtitles are truncated.
- The runtime catalog lists unshipped systems among playable ones.

### Build and tooling

- A 27.1 SDK build failed because the pinned cores rebuild with `cmake` whenever the SDK changes, and `cmake` wasn't installed. (`ios/README.md` already named CMake; it was not installed by CI or called out as needed after an Xcode update.)
- `scripts/ios/repair_simulator_migration_state.sh` needs `rg`.

## Phase 0: SDK and foundations

This phase blocks everything else. Vertical bars, the sidebar and edge-to-edge layout only apply to apps built with the iOS 27.1 SDK.

1. Document `cmake` and `rg` as prerequisites in `README.md` and `ios/README.md`, and install them in `.github/workflows/ios-validate.yml`. Verify a clean build with Xcode 27.1.
2. Raise `IPHONEOS_DEPLOYMENT_TARGET` to 18.0 in `scripts/ios/generate_xcodeproj.rb` and the tracked project, and raise `Package.swift` to `.iOS(.v18)`. Remove any iOS 17 fallbacks this makes dead.
3. Add Duo geometries to `PlayerLayoutParityTests`: 466×678, 803×515 and 669×703, each with asymmetric insets and with and without a division region. Add a Device Hub pose checklist to `docs/ios-player-ui-validation.md`.

## Phase 1: shell, vertical bars, sidebar and fold

4. **Sidebar.** Move `AuthenticatedShell` from `.tabItem` to the `Tab` API with `.tabViewStyle(.sidebarAdaptable)`. This gives a vertical tab bar when closed and a sidebar when open.
5. **Toolbars.** Use system placements: `.cancellationAction` for back and close, pinned trailing placements for primary actions. Every item gets both a title and a symbol. Prefer symbols over text buttons. Set `visibilityPriority` on Play and Download. Use `ToolbarOverflowMenu` for secondary actions.
6. **Library split view.** Make Library a `NavigationSplitView`: platforms, then games, then game detail. It collapses to the current flow on the outer display.
7. **Home by size class.** On compact width, lead with Continue playing. On regular width, place the summary beside the shelves. Grids and metric tiles use even column counts.
8. **Game detail.** On regular width, use two columns: artwork and description on one side, actions and file details on the other. Size the hero to the container instead of a fixed 280 pt, with a legible title treatment.
9. **Custom overlays.** The notice banner, the refresh indicator and the one-touch sheet respect safe areas and reserved regions. The banner no longer covers navigation, and the QR sheet fits its content.
10. **Content fixes.** Fix every item listed under Content in the findings above.

## Phase 2: player in every pose

11. **Layout inputs.** Replace the height-versus-width orientation decisions in `PlayerViewportLayout.swift` with size classes plus the division region, queried with `.includeInactive`. The inner display ignores supported orientations, so `PlayerOrientationPolicy` becomes a layout preference rather than a lock.
12. **Fill the screen.** Use the full display width, the way Calculator does, and keep only the status bar and camera clear with `ReservedRegion`. Extend background art into leftover padding instead of black bars. Keep control sizes stable as the device changes shape.
13. **Sheets.** Size the pause sheet so "Leave game" is always visible, and opt player sheets out of vertical bars where those bars cramp them.
14. **Stylus.** Serialize pointer events through the same ordered queue as the console inputs, and hold each press for at least one frame after release.
15. **Controllers.** Route `GCController` extended gamepad input to the core so the existing controller settings take effect.

## Phase 3: DS screen layouts

16. **Split the frame.** Render the stacked 256×384 frame as two views, top and bottom halves, and attach the stylus overlay to the bottom view only.
17. **Arrange the screens.** ~~Side by side when wide.~~ Superseded: always stacked. Unfolded in portrait, the top screen sits above the hinge and the touch screen below it, with the gap on the hinge.
18. **Controls.** Anchor the control rails to the two screen areas instead of the combined viewport.
19. **Layout override.** Dropped with the side-by-side arrangement.
20. **Nickname.** Set the DS firmware nickname from the RomM username through a melonDS core option.

## Phase 4: two-pane Duo experiences

21. **Game plus pad.** For single-screen consoles, add a split arrangement: game on one half, full-size controls on the other.
22. **Live controls editor.** When open, the Controls sheet becomes a side pane beside the running game, so changes preview live.
23. **Browse beside play.** Show save states and the library beside the paused game, with state thumbnails next to the live frame.
24. **Outer-display quick resume.** On the outer display, Continue playing resumes straight into the snapshot, and unfolding continues without a restart. This depends on the resume-snapshot sync in [save-sync-spec.md](save-sync-spec.md).

## Validation and risks

- Check each phase in Device Hub in Closed, Open and folded poses, and in Split View. Report iOS qualification on its own, per `AGENTS.md`.
- In Xcode 27.1 beta 1, `reservedRegions` returns nothing, the vertical bar edge reads empty, and `UIScreen.main` reports the outer display. Fold-dependent code needs a fallback that treats the midline of the open display as the fold until later betas.
- The Book pose is untested: the simulator's fold slider snaps to its ends. Items 17 and 18 need later-beta or hardware confirmation.
- Order of work: Phase 0, then items 4–6, then items 11–14, then Phase 3, then the remainder of Phases 1 and 2, then Phase 4.

## Status (2026-09-22)

All four phases are implemented and built with the iOS 27.1 SDK (minimum iOS 18). Checked on the iPhone Duo simulator (Xcode 27.1 beta 1) against romm.borchetta.xyz in the closed, open landscape and open portrait poses; see the iPhone Duo pass in [ios-player-ui-validation.md](ios-player-ui-validation.md). Package tests pass except `PlayerVisualThemeTests.testConsoleColorsUseFamilySpecificFaceStyles`, which fails on unrelated in-progress control-style changes. Physical-device qualification is outstanding.

How the plan was carried out:

- **Phase 0.** CI installs `cmake`; `ios/README.md` lists `brew install cmake ripgrep`. Deployment targets are iOS 18 and macOS 15 (the package's test host). `PlayerDuoStageLayoutTests` covers the closed, open and portrait sizes, reported and missing hinges, and the pane and pad modes.
- **Phase 1.** Sidebar-adaptable `Tab`s give the vertical tab bar closed and in open landscape, and standard bars in open portrait. Library is a balanced two-column split (platforms, then games with pushed game pages), not three columns. The games column was too cramped at 669 pt. Game detail puts Play (or Download) in the toolbar. `visibilityPriority` wasn't needed with a single primary item.
- **Phase 2.** `resolvePlayerStageLayout` (RommioPlayerKit) is the single layout for the renderer, controls and stylus. It takes the hinge from the division reserved region, and near-square windows pick whichever control arrangement shows the larger game. The renderer draws each screen from its own crop and clears the rest of the drawable. Artwork fills letterbox padding. The home indicator auto-hides and the screen stays awake. Player sheets keep horizontal bars. Pointer input is ordered, with a minimum hold. Game controllers are routed by position, with Home opening the pause menu.
- **Phase 3.** DS frames render as two screens; see the decision above. In the open portrait pose, the upper screen now uses spare space above the crease, capped at 1.3× the touch-screen width for DS and 1.5× for 3DS; controls remain beside the lower screen. The DS nickname comes from the RomM username through `RETRO_ENVIRONMENT_GET_USERNAME`, cached for offline launches and refreshed in the background so launch never waits on the network.
- **Phase 4.** When the window is regular width, Controls and Saved progress open in a pane beside the paused game, split by `ArrangementView`, and touch controls stay visible for live tuning. Single-screen games use a pad half automatically in the book pose, or on request ("Game and controls side by side"). Home's Continue playing tiles resume directly.

Found and fixed along the way:

- **Audio.** Audio played every core at 44.1 kHz or more. melonDS DS (32.7 kHz) and Snes9x (32 kHz) were starved, which caused crackling and raised pitch. It now uses the core's rate, a small jitter buffer and an `.ambient` session.
- **Start errors.** Start failures were hidden behind the pre-start progress panel. For example, snapshots from a differently built core are correctly refused, but nothing said so.
- **Deadzone.** Reloading control settings reset the deadzone to 0.2.

Corrections to the findings:

- The real open inner display is 951×669 pt. 803×515 was a compatibility-mode measurement.
- In beta 1, `reservedRegions` reports no hinge. DS stacking falls back to the display midline in open portrait, and the automatic book-pose pad needs a later beta or hardware.
