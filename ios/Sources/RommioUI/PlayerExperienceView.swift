import SwiftUI
import RommioFoundation
import RommioPlayerKit

#if canImport(UIKit)
import UIKit

struct PlayerExperienceView: View {
    let feature: PlayerFeatureModel
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.rommioTheme) private var appTheme
    /// On a wide display (iPhone Duo open), Pause, Controls and Save states open
    /// as a floating inspector beside the game instead of a sheet over it
    /// (`PlayerPanelPresentation`); the game stays uncovered.
    @State private var usesSidePanes = false
    @State private var arrangedGameWidth: CGFloat?

    private var sidePaneVisible: Bool {
        usesSidePanes && (feature.pauseSheetPresented || feature.controlsSheetPresented || feature.stateBrowserPresented)
    }

    var body: some View {
        GeometryReader { geometry in
            let insets = geometry.safeAreaInsets
            let size = CGSize(width: geometry.size.width + insets.leading + insets.trailing,
                              height: geometry.size.height + insets.top + insets.bottom)
            let fold = divisionRegion(geometry)
            // An overlay does not enlarge its parent's layout proposal. Keep the
            // native surface and touch chrome in exactly the same window space.
            feature.theme.canvasColor
                .overlay(alignment: .topLeading) {
                    ZStack(alignment: .topLeading) {
                        // The native renderer is transparent outside video; paint the
                        // entire window, including the cutout and home-indicator areas.
                        feature.theme.canvasColor
                        PlayerBackdrop(url: feature.backdropURL,
                                       enabled: feature.controlsState?.preferences.oledBlackModeEnabled != true)
                        PlayerHostContainer { try feature.makeHostController() }
                            .accessibilityIdentifier("player.host")
                        Color.clear.contentShape(Rectangle())
                            .onTapGesture {
                                feature.bumpPrimaryControls()
                                feature.bumpTertiaryControls(wakePrimary: false)
                            }
                        if feature.progressOwner != nil && !feature.hasStarted {
                            ProgressPlayerPanel(feature: feature)
                            // Start failures (such as a snapshot from a different core
                            // build) must be visible, not just leave the panel in place.
                            if let message = feature.errorMessage {
                                PlayerBanner(message: message, isError: true)
                                    .padding(.horizontal, 16)
                                    .padding(.bottom, insets.bottom + 16)
                                    .frame(width: size.width, height: size.height, alignment: .bottom)
                            }
                        } else {
                            PlayerOverlayChrome(feature: feature, size: size, safeAreaInsets: insets,
                                                livePreview: sidePaneVisible && feature.controlsSheetPresented)
                        }
                        if sidePaneVisible {
                            PlayerSidePaneArrangement(size: size, insets: insets, arrangedGameWidth: $arrangedGameWidth) {
                                sidePaneContent
                            }
                        }
                    }
                    .frame(width: size.width, height: size.height)
                    .offset(x: -insets.leading, y: -insets.top)
                }
                .onChange(of: fold, initial: true) { _, region in
                    feature.updateWindowFold(region?.frame, isActive: region?.isActive ?? false)
                }
                .onChange(of: size.width, initial: true) { _, width in
                    usesSidePanes = PlayerPanelPresentation.resolve(regularWidth: horizontalSizeClass == .regular, windowWidth: width) == .sidePane
                }
                .onChange(of: sidePaneVisible ? (arrangedGameWidth.map { size.width - $0 } ?? PlayerPanelPresentation.paneWidth(windowWidth: size.width)) : 0, initial: true) { _, width in
                    feature.updateSidePaneWidth(width)
                }
        }
        .interactiveDismissDisabled()
        .statusBarHidden()
        .persistentSystemOverlays(.hidden)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("player.screen")
        .sheet(isPresented: pauseSheetBinding, onDismiss: {
            if feature.isPaused && !feature.controlsSheetPresented && !feature.stateBrowserPresented && feature.errorMessage == nil {
                Task { await feature.resumeGame() }
            }
        }) {
            PlayerPauseSheet(feature: feature)
                // Tall enough for every action (including Leave game) on
                // iPhone Duo's 515pt open display without scrolling.
                .presentationDetents([.height(430), .large])
                .presentationDragIndicator(.visible)
                .modifier(PlayerSheetBars())
                .accessibilityIdentifier("player.pause.sheet")
        }
        .sheet(isPresented: controlsSheetBinding, onDismiss: resumeAfterPanelDismissal) {
            PlayerControlsSheet(feature: feature)
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
                .modifier(PlayerSheetBars())
                .accessibilityIdentifier("player.controls.sheet")
        }
        .sheet(isPresented: stateBrowserSheetBinding, onDismiss: resumeAfterPanelDismissal) {
            stateBrowserContent
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
                .modifier(PlayerSheetBars())
        }
        .onChange(of: sidePaneVisible) { wasVisible, visible in
            if wasVisible && !visible { resumeAfterPanelDismissal() }
        }
        .onChange(of: feature.pendingRestoreID) { _, value in
            if value != nil { feature.pauseSheetPresented = false; feature.stateBrowserPresented = true }
        }
        .task(id: feature.id) {
            await feature.startIfNeeded()
        }
        .onChange(of: appTheme.identity, initial: true) { _, _ in
            feature.appTheme = appTheme
        }
        // Touch-free controller play shouldn't let the display sleep mid-game.
        .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
    }

    @ViewBuilder
    private var sidePaneContent: some View {
        if feature.controlsSheetPresented {
            PlayerControlsSheet(feature: feature)
        } else if feature.stateBrowserPresented {
            stateBrowserContent
        } else {
            PlayerPauseSheet(feature: feature)
                .accessibilityIdentifier("player.pause.sheet")
        }
    }

    @ViewBuilder
    private var stateBrowserContent: some View {
        if feature.progressOwner != nil {
            ProgressPlayerPanel(feature: feature)
        } else {
            StateBrowserView(
                browser: feature.stateBrowser,
                onUseResume: { Task { await feature.loadResumeState() } },
                onLoadState: { state in
                    Task { await feature.loadBrowsableState(state) }
                },
                onDeleteState: { state in
                    Task { await feature.deleteState(state) }
                },
                onClose: { feature.stateBrowserPresented = false }
            )
            .accessibilityIdentifier("player.stateBrowser.sheet")
        }
    }

    /// iPhone Duo's hinge in window coordinates, from the division reserved
    /// region (iOS 27.1). Inactive regions still mark a flat display's centre.
    private func divisionRegion(_ geometry: GeometryProxy) -> PlayerFoldRegion? {
        if #available(iOS 27.1, *) {
            guard let region = geometry.reservedRegions(kind: .division, options: .includeInactive).first else { return nil }
            let insets = geometry.safeAreaInsets
            return PlayerFoldRegion(frame: region.frame.offsetBy(dx: insets.leading, dy: insets.top), isActive: region.isActive)
        }
        return nil
    }

    private func resumeAfterPanelDismissal() {
        if feature.isPaused && feature.errorMessage == nil {
            Task { await feature.resumeGame() }
        }
    }

    private var pauseSheetBinding: Binding<Bool> {
        Binding(
            get: { feature.pauseSheetPresented && !usesSidePanes },
            set: { feature.pauseSheetPresented = $0 }
        )
    }

    private var controlsSheetBinding: Binding<Bool> {
        Binding(
            get: { feature.controlsSheetPresented && !usesSidePanes },
            set: { feature.controlsSheetPresented = $0 }
        )
    }

    private var stateBrowserSheetBinding: Binding<Bool> {
        Binding(
            get: { feature.stateBrowserPresented && !usesSidePanes },
            set: { feature.stateBrowserPresented = $0 }
        )
    }
}

struct PlayerFoldRegion: Equatable {
    let frame: CGRect
    let isActive: Bool
}

/// Splits the window between the game and a side pane with ArrangementView
/// (iOS 27.1), which sizes both sides around iPhone Duo's fold.
private struct PlayerSidePaneArrangement<Pane: View>: View {
    let size: CGSize
    let insets: EdgeInsets
    @Binding var arrangedGameWidth: CGFloat?
    @ViewBuilder let pane: () -> Pane

    var body: some View {
        Group {
            if #available(iOS 27.1, *) {
                ArrangementView {
                    gamePlaceholder
                } secondary: {
                    paneBody
                }
                .arrangementViewStyle(.split.axes(.horizontal))
            } else {
                HStack(spacing: 0) {
                    gamePlaceholder
                    paneBody.frame(width: PlayerPanelPresentation.paneWidth(windowWidth: size.width))
                }
            }
        }
        .frame(width: size.width, height: size.height)
        .onDisappear { arrangedGameWidth = nil }
    }

    private var gamePlaceholder: some View {
        Color.clear
            .allowsHitTesting(false)
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { arrangedGameWidth = $0 }
    }

    private var paneBody: some View {
        pane()
            .padding(.top, insets.top)
            .padding(.bottom, insets.bottom)
            .padding(.trailing, insets.trailing)
            .background(.regularMaterial)
            .accessibilityIdentifier("player.sidePane")
    }
}

/// Player sheets keep standard horizontal bars; iPhone Duo's vertical bars
/// would crowd their control-heavy forms.
private struct PlayerSheetBars: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 27.1, *) {
            content.toolbarVerticalBehavior(.disabled)
        } else {
            content
        }
    }
}

/// Aurora bias lighting: the game's art, blurred and brightened, spills into the
/// rails behind the controls. Riso prints on black stock, so there is none.
private struct PlayerBackdrop: View {
    @Environment(\.rommioTheme) private var theme
    let url: URL?
    let enabled: Bool

    var body: some View {
        // The clear base takes the proposed (window) size; an aspect-filled image
        // on its own would grow the player's stage to the artwork's shape.
        Color.clear
            .overlay {
                if enabled, theme.isGlass, let url {
                    BlurredCover(url: url, dark: theme.isDark)
                        .scaledToFill()
                        .opacity(0.85)
                        .overlay {
                            RadialGradient(colors: [.clear, theme.player.ground.color.opacity(0.55)], center: .center,
                                           startRadius: 40, endRadius: 520)
                        }
                }
            }
            .clipped()
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}

private struct PlayerOverlayChrome: View {
    let feature: PlayerFeatureModel
    let size: CGSize
    let safeAreaInsets: EdgeInsets
    /// Keep touch controls visible while the controls editor pane is open.
    var livePreview = false
    @State private var noticeVisible = false

    private var overlaySnapshot: PlayerOverlaySnapshot? {
        feature.overlaySnapshot(
            containerSize: size,
            safeAreaInsets: PlayerViewportSafeAreaInsets(
                top: safeAreaInsets.top,
                bottom: safeAreaInsets.bottom,
                left: safeAreaInsets.leading,
                right: safeAreaInsets.trailing
            )
        )
    }

    private var viewportFrame: CGRect {
        overlaySnapshot?.viewportFrame
            ?? resolvePlayerViewportLayout(
                containerSize: size,
                safeAreaInsets: PlayerViewportSafeAreaInsets(
                    top: safeAreaInsets.top,
                    bottom: safeAreaInsets.bottom,
                    left: safeAreaInsets.leading,
                    right: safeAreaInsets.trailing
                ),
                aspectRatio: feature.controlsState?.platformProfile.preferredViewportAspectRatio,
                topAlignedInPortrait: feature.supportTier == .touchSupported,
                stage: feature.stageContext
            ).frame
    }

    var body: some View {
        ZStack {
            if let message = feature.noticeMessage, noticeVisible {
                PlayerBanner(message: message, isError: false)
                    .padding(.top, 16)
                    .frame(maxHeight: .infinity, alignment: .top)
            }

            if let message = feature.errorMessage {
                PlayerBanner(message: message, isError: true)
                    .padding(.top, feature.noticeMessage == nil ? 16 : 72)
                    .frame(maxHeight: .infinity, alignment: .top)
            }

            if let state = feature.controlsState {
                let controls = ExpansionControls.controls(for: state.platformProfile.familyID) ?? []
                ExpansionControlsOverlay(controls: controls, size: size, insets: safeAreaInsets,
                    leftHanded: state.touchLayout?.leftHanded ?? false, scale: state.touchLayout?.globalScale ?? 1,
                    opacity: state.touchLayout?.opacity ?? 0.72, showControls: state.showTouchControls && (!feature.isPaused || livePreview),
                    onDigital: { key, pressed in feature.enqueueConsoleDigital(key, pressed: pressed) },
                    onMotion: { stick, x, y in feature.enqueueConsoleMotion(stick, x: x, y: y) },
                    onMenu: { Task { await feature.openPauseMenu() } }, theme: feature.theme, aspectRatio: state.platformProfile.preferredViewportAspectRatio,
                    stage: feature.stageContext, topAlignedInPortrait: feature.supportTier == .touchSupported)
            } else {
            PlayerTouchOverlay(
                feature: feature,
                size: size,
                safeAreaInsets: safeAreaInsets,
                viewportFrame: viewportFrame,
                overlaySnapshot: overlaySnapshot
            )

            }
            if overlaySnapshot?.showsPointerOverlay == true {
                DSPointerOverlay(feature: feature, touchScreenFrame: overlaySnapshot?.touchScreenFrame
                    ?? CGRect(x: viewportFrame.minX, y: viewportFrame.midY, width: viewportFrame.width, height: viewportFrame.height / 2))
            }
        }
        .task(id: feature.noticeMessage) {
            // Keep the detail in the progress panel, without leaving a success
            // banner across the game for the rest of the session.
            noticeVisible = feature.noticeMessage != nil
            do { try await Task.sleep(for: .seconds(3)) } catch { return }
            noticeVisible = false
        }
    }
}

private struct PlayerTouchOverlay: View {
    let feature: PlayerFeatureModel
    let size: CGSize
    let safeAreaInsets: EdgeInsets
    let viewportFrame: CGRect
    let overlaySnapshot: PlayerOverlaySnapshot?

    var body: some View {
        if let controlsState = feature.controlsState {
            let bindings = fixedTouchBindings(for: controlsState)
            let metrics = FixedTouchMetrics(
                screenWidth: size.width,
                screenHeight: size.height,
                scale: CGFloat(controlsState.touchLayout?.globalScale ?? 1)
            )
            let snapshot = overlaySnapshot
            let isLandscape = snapshot?.isPortrait == false
            let showsGameplayTouchControls = snapshot?.showsGameplayTouchControls ?? false
            let visibleSystemButtons = snapshot?.systemPlacement == .none ? [] : (bindings?.systemButtons ?? [])

            ZStack {
                if showsGameplayTouchControls, let bindings {
                    Group {
                        if isLandscape {
                            LandscapePrimaryControlsOverlay(
                                feature: feature,
                                bindings: bindings,
                                metrics: metrics,
                                viewportFrame: viewportFrame,
                                safeAreaInsets: safeAreaInsets
                            )
                        } else {
                            PortraitPrimaryControlsOverlay(
                                feature: feature,
                                bindings: bindings,
                                metrics: metrics,
                                viewportFrame: viewportFrame,
                                bottomInset: safeAreaInsets.bottom
                            )
                        }
                    }
                    .opacity(feature.primaryControlsVisible ? 1 : 0)
                    .animation(.easeInOut(duration: 0.18), value: feature.primaryControlsVisible)
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("player.primaryControls")
                }

                Group {
                    if isLandscape {
                        LandscapeTertiaryControlsOverlay(
                            feature: feature,
                            systemButtons: visibleSystemButtons,
                            metrics: metrics,
                            viewportFrame: viewportFrame,
                            safeAreaInsets: safeAreaInsets,
                            leftHanded: controlsState.touchLayout?.leftHanded ?? false
                        )
                    } else {
                        PortraitTertiaryControlsOverlay(
                            feature: feature,
                            systemButtons: visibleSystemButtons,
                            metrics: metrics,
                            viewportFrame: viewportFrame,
                            leftHanded: controlsState.touchLayout?.leftHanded ?? false,
                            safeAreaInsets: safeAreaInsets
                        )
                    }
                }
                .opacity(feature.tertiaryControlsVisible ? 1 : 0)
                .animation(.easeInOut(duration: 0.18), value: feature.tertiaryControlsVisible)
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("player.systemControls")
            }
        }
    }
}

private struct PortraitPrimaryControlsOverlay: View {
    let feature: PlayerFeatureModel
    let bindings: FixedTouchBindings
    let metrics: FixedTouchMetrics
    let viewportFrame: CGRect
    let bottomInset: CGFloat

    var body: some View {
        let isMirrored = bindings.leftHanded
        let dpadX = isMirrored ? metrics.screenWidth - metrics.portraitSideInset - metrics.zoneSize : metrics.portraitSideInset
        let buttonX = isMirrored ? metrics.portraitSideInset : metrics.screenWidth - metrics.portraitSideInset - metrics.zoneSize
        let controlRowTop = viewportFrame.maxY + metrics.controlRowTopOffset
        let systemRowBottom = controlRowTop + metrics.portraitControlButtonHeight
        let minimumZoneTop = systemRowBottom + 18
        let maximumZoneTop = metrics.portraitBandBottom(bottomInset: bottomInset) - metrics.zoneSize - 12
        // Center the thumb controls in the space below the screen. Anchoring
        // directly to the viewport stranded them halfway up tall iPhones.
        let preferredZoneTop = (minimumZoneTop + maximumZoneTop) / 2
        let zoneTop = max(minimumZoneTop, min(maximumZoneTop, preferredZoneTop))
        let triggerCenterY = zoneTop - metrics.portraitTriggerHeight / 2 - 8

        ZStack {
            if let dpad = bindings.dpadElement {
                PositionedClusterOverlay(
                    feature: feature,
                    buttons: dpad.buttons,
                    clusterStyle: .dpad,
                    center: CGPoint(x: dpadX + (metrics.zoneSize / 2), y: zoneTop + (metrics.zoneSize / 2)),
                    buttonSize: metrics.zoneSize * 0.34,
                    scale: dpad.scale,
                    opacity: dpad.opacity
                )
            }

            if let face = bindings.faceElement {
                PositionedClusterOverlay(
                    feature: feature,
                    buttons: face.buttons,
                    clusterStyle: .face(bindings.faceStyle),
                    center: CGPoint(x: buttonX + (metrics.zoneSize / 2), y: zoneTop + (metrics.zoneSize / 2)),
                    buttonSize: metrics.zoneSize * 0.34,
                    scale: face.scale,
                    opacity: face.opacity
                )
            }

            if !bindings.leftTriggers.isEmpty {
                TriggerButtonsOverlay(
                    feature: feature,
                    buttons: bindings.leftTriggers,
                    size: CGSize(width: metrics.portraitTriggerWidth, height: metrics.portraitTriggerHeight),
                    center: CGPoint(
                        x: dpadX + metrics.zoneSize / 2,
                        y: triggerCenterY
                    ),
                    opacity: bindings.primaryOpacity
                )
            }

            if !bindings.rightTriggers.isEmpty {
                TriggerButtonsOverlay(
                    feature: feature,
                    buttons: bindings.rightTriggers,
                    size: CGSize(width: metrics.portraitTriggerWidth, height: metrics.portraitTriggerHeight),
                    center: CGPoint(
                        x: buttonX + metrics.zoneSize / 2,
                        y: triggerCenterY
                    ),
                    opacity: bindings.primaryOpacity
                )
            }
        }
    }
}

private struct PortraitTertiaryControlsOverlay: View {
    let feature: PlayerFeatureModel
    let systemButtons: [TouchButtonSpec]
    let metrics: FixedTouchMetrics
    let viewportFrame: CGRect
    let leftHanded: Bool
    let safeAreaInsets: EdgeInsets

    var body: some View {
        let controlRowTop = viewportFrame.maxY + metrics.controlRowTopOffset
        let menuHalf = metrics.menuButtonSize / 2
        let menuTop = max(menuHalf + 8, safeAreaInsets.top / 2)
        let leadingMenuX = max(
            metrics.screenEdgeInset + menuHalf,
            viewportFrame.minX + menuHalf + 8
        )
        let trailingMenuX = min(
            metrics.screenWidth - metrics.screenEdgeInset - menuHalf,
            viewportFrame.maxX - menuHalf - 8
        )

        ZStack {
            PlayerMenuButton(feature: feature)
                .position(
                    x: leftHanded ? trailingMenuX : leadingMenuX,
                    y: menuTop
                )

            if !systemButtons.isEmpty {
                SystemButtonsRowOverlay(
                    feature: feature,
                    buttons: systemButtons,
                    buttonSize: CGSize(width: metrics.portraitControlButtonWidth, height: metrics.portraitControlButtonHeight),
                    gap: metrics.systemGap
                )
                .position(
                    x: viewportFrame.midX,
                    y: controlRowTop + (metrics.portraitControlButtonHeight / 2)
                )
            }
        }
    }
}

private struct LandscapePrimaryControlsOverlay: View {
    let feature: PlayerFeatureModel
    let bindings: FixedTouchBindings
    let metrics: FixedTouchMetrics
    let viewportFrame: CGRect
    let safeAreaInsets: EdgeInsets

    var body: some View {
        let dpadRailOnLeft = !bindings.leftHanded
        let leadingRailInset = metrics.landscapeRailInset(sideInset: safeAreaInsets.leading)
        let trailingRailInset = metrics.landscapeRailInset(sideInset: safeAreaInsets.trailing)
        let dpadX = dpadRailOnLeft ? leadingRailInset : metrics.screenWidth - trailingRailInset - metrics.zoneSize
        let buttonX = dpadRailOnLeft ? metrics.screenWidth - trailingRailInset - metrics.zoneSize : leadingRailInset
        let triggerY = metrics.landscapeTopInset
        let bottomSafe = metrics.landscapeBottomInset(bottomInset: safeAreaInsets.bottom)
        let preferredZoneTop = viewportFrame.minY + (viewportFrame.height / 2) - (metrics.zoneSize / 2) + metrics.landscapeZoneDrop
        let lowerZoneY = min(
            max(preferredZoneTop, triggerY + metrics.triggerZoneHeight + 18),
            metrics.screenHeight - bottomSafe - metrics.zoneSize
        )

        ZStack {
            if !bindings.leftTriggers.isEmpty {
                TriggerButtonsOverlay(
                    feature: feature,
                    buttons: bindings.leftTriggers,
                    size: CGSize(width: metrics.triggerZoneWidth, height: metrics.triggerZoneHeight),
                    center: CGPoint(
                        x: (dpadRailOnLeft ? leadingRailInset : metrics.screenWidth - trailingRailInset - metrics.triggerZoneWidth) + (metrics.triggerZoneWidth / 2),
                        y: triggerY + (metrics.triggerZoneHeight / 2)
                    ),
                    opacity: bindings.primaryOpacity
                )
            }

            if !bindings.rightTriggers.isEmpty {
                TriggerButtonsOverlay(
                    feature: feature,
                    buttons: bindings.rightTriggers,
                    size: CGSize(width: metrics.triggerZoneWidth, height: metrics.triggerZoneHeight),
                    center: CGPoint(
                        x: (dpadRailOnLeft ? metrics.screenWidth - trailingRailInset - metrics.triggerZoneWidth : leadingRailInset) + (metrics.triggerZoneWidth / 2),
                        y: triggerY + (metrics.triggerZoneHeight / 2)
                    ),
                    opacity: bindings.primaryOpacity
                )
            }

            if let dpad = bindings.dpadElement {
                PositionedClusterOverlay(
                    feature: feature,
                    buttons: dpad.buttons,
                    clusterStyle: .dpad,
                    center: CGPoint(x: dpadX + (metrics.zoneSize / 2), y: lowerZoneY + (metrics.zoneSize / 2)),
                    buttonSize: metrics.zoneSize * 0.34,
                    scale: dpad.scale,
                    opacity: dpad.opacity
                )
            }

            if let face = bindings.faceElement {
                PositionedClusterOverlay(
                    feature: feature,
                    buttons: face.buttons,
                    clusterStyle: .face(bindings.faceStyle),
                    center: CGPoint(x: buttonX + (metrics.zoneSize / 2), y: lowerZoneY + (metrics.zoneSize / 2)),
                    buttonSize: metrics.zoneSize * 0.34,
                    scale: face.scale,
                    opacity: face.opacity
                )
            }
        }
    }
}

private struct LandscapeTertiaryControlsOverlay: View {
    let feature: PlayerFeatureModel
    let systemButtons: [TouchButtonSpec]
    let metrics: FixedTouchMetrics
    let viewportFrame: CGRect
    let safeAreaInsets: EdgeInsets
    let leftHanded: Bool

    var body: some View {
        let bottomSafe = metrics.landscapeBottomInset(bottomInset: safeAreaInsets.bottom)
        let leadingEdgeInset = metrics.landscapeTertiaryInset(sideInset: safeAreaInsets.leading)
        let trailingEdgeInset = metrics.landscapeTertiaryInset(sideInset: safeAreaInsets.trailing)
        let menuY = metrics.screenHeight - bottomSafe - metrics.menuButtonSize - 6
        let systemHeight = systemGroupHeight(count: systemButtons.count, buttonHeight: metrics.controlButtonHeight, gap: metrics.systemGap)
        let controlY = metrics.screenHeight - bottomSafe - systemHeight - 6
        let menuX = leftHanded ? metrics.screenWidth - trailingEdgeInset - metrics.menuButtonSize : leadingEdgeInset
        let controlX = leftHanded ? leadingEdgeInset : metrics.screenWidth - trailingEdgeInset - metrics.controlButtonWidth

        ZStack {
            PlayerMenuButton(feature: feature)
                .position(x: menuX + (metrics.menuButtonSize / 2), y: menuY + (metrics.menuButtonSize / 2))

            if !systemButtons.isEmpty {
                SystemButtonsColumnOverlay(
                    feature: feature,
                    buttons: systemButtons,
                    buttonSize: CGSize(width: metrics.controlButtonWidth, height: metrics.controlButtonHeight),
                    gap: metrics.systemGap
                )
                .position(
                    x: controlX + (metrics.controlButtonWidth / 2),
                    y: controlY + (systemHeight / 2)
                )
            }
        }
    }
}

private struct PositionedClusterOverlay: View {
    let feature: PlayerFeatureModel
    let buttons: [TouchButtonSpec]
    let clusterStyle: ClusterStyle
    let center: CGPoint
    let buttonSize: CGFloat
    let scale: Double
    let opacity: Double

    var body: some View {
        let scaledButtonSize = buttonSize * CGFloat(scale)
        let clusterSize = clusterFrame(buttonSize: scaledButtonSize)

        ZStack {
            ForEach(Array(buttons.enumerated()), id: \.element.id) { index, button in
                TouchButtonControl(
                    button: button,
                    theme: feature.theme,
                    size: scaledButtonSize,
                    familyID: feature.controlsState?.platformProfile.familyID,
                    onPress: {
                        await feature.press(button.action)
                        feature.bumpPrimaryControls()
                    },
                    onRelease: {
                        await feature.release(button.action)
                    }
                )
                .position(position(for: index, in: clusterSize, buttonSize: scaledButtonSize))
            }
        }
        .frame(width: clusterSize.width, height: clusterSize.height)
        .opacity(opacity)
        .position(center)
    }

    private func clusterFrame(buttonSize: CGFloat) -> CGSize {
        switch clusterStyle {
        case .dpad:
            CGSize(width: buttonSize * 3.1, height: buttonSize * 3.1)
        case let .face(style):
            switch style {
            case .twoDiagonal:
                CGSize(width: buttonSize * 2.6, height: buttonSize * 2.0)
            case .diamond, .arcadeGrid, .segaArc:
                CGSize(width: buttonSize * 3.1, height: buttonSize * 3.1)
            }
        }
    }

    private func position(for index: Int, in cluster: CGSize, buttonSize: CGFloat) -> CGPoint {
        let centerPoint = CGPoint(x: cluster.width / 2, y: cluster.height / 2)
        switch clusterStyle {
        case .dpad:
            switch index {
            case 0: return CGPoint(x: centerPoint.x, y: centerPoint.y - buttonSize)
            case 1: return CGPoint(x: centerPoint.x - buttonSize, y: centerPoint.y)
            case 2: return CGPoint(x: centerPoint.x + buttonSize, y: centerPoint.y)
            default: return CGPoint(x: centerPoint.x, y: centerPoint.y + buttonSize)
            }
        case let .face(style):
            switch style {
            case .twoDiagonal:
                switch index {
                case 0: return CGPoint(x: centerPoint.x - buttonSize * 0.55, y: centerPoint.y + buttonSize * 0.25)
                default: return CGPoint(x: centerPoint.x + buttonSize * 0.55, y: centerPoint.y - buttonSize * 0.25)
                }
            case .diamond:
                switch index {
                case 0: return CGPoint(x: centerPoint.x - buttonSize, y: centerPoint.y)
                case 1: return CGPoint(x: centerPoint.x, y: centerPoint.y + buttonSize)
                case 2: return CGPoint(x: centerPoint.x + buttonSize, y: centerPoint.y)
                default: return CGPoint(x: centerPoint.x, y: centerPoint.y - buttonSize)
                }
            case .arcadeGrid:
                switch index {
                case 0: return CGPoint(x: centerPoint.x - buttonSize * 0.85, y: centerPoint.y - buttonSize * 0.65)
                case 1: return CGPoint(x: centerPoint.x + buttonSize * 0.85, y: centerPoint.y - buttonSize * 0.65)
                case 2: return CGPoint(x: centerPoint.x - buttonSize * 0.85, y: centerPoint.y + buttonSize * 0.65)
                default: return CGPoint(x: centerPoint.x + buttonSize * 0.85, y: centerPoint.y + buttonSize * 0.65)
                }
            case .segaArc:
                switch index {
                case 0: return CGPoint(x: centerPoint.x - buttonSize, y: centerPoint.y + buttonSize * 0.15)
                case 1: return CGPoint(x: centerPoint.x, y: centerPoint.y + buttonSize)
                case 2: return CGPoint(x: centerPoint.x + buttonSize, y: centerPoint.y + buttonSize * 0.15)
                default: return CGPoint(x: centerPoint.x, y: centerPoint.y - buttonSize)
                }
            }
        }
    }
}

private struct SystemButtonsRowOverlay: View {
    let feature: PlayerFeatureModel
    let buttons: [TouchButtonSpec]
    let buttonSize: CGSize
    let gap: CGFloat

    var body: some View {
        HStack(spacing: gap) {
            ForEach(buttons, id: \.id) { button in
                SystemTouchButton(
                    button: button,
                    theme: feature.theme,
                    size: buttonSize,
                    onPress: {
                        feature.bumpTertiaryControls(wakePrimary: false)
                        await feature.press(button.action)
                    },
                    onRelease: {
                        await feature.release(button.action)
                    }
                )
            }
        }
    }
}

private struct SystemButtonsColumnOverlay: View {
    let feature: PlayerFeatureModel
    let buttons: [TouchButtonSpec]
    let buttonSize: CGSize
    let gap: CGFloat

    var body: some View {
        VStack(spacing: gap) {
            ForEach(buttons, id: \.id) { button in
                SystemTouchButton(
                    button: button,
                    theme: feature.theme,
                    size: buttonSize,
                    onPress: {
                        feature.bumpTertiaryControls(wakePrimary: false)
                        await feature.press(button.action)
                    },
                    onRelease: {
                        await feature.release(button.action)
                    }
                )
            }
        }
    }
}

private struct TriggerButtonsOverlay: View {
    let feature: PlayerFeatureModel
    let buttons: [TouchButtonSpec]
    let size: CGSize
    let center: CGPoint
    let opacity: Double

    var body: some View {
        Group {
            if buttons.count == 1, let button = buttons.first {
                SystemTouchButton(
                    button: button,
                    theme: feature.theme,
                    size: size,
                    onPress: {
                        await feature.press(button.action)
                        feature.bumpPrimaryControls()
                    },
                    onRelease: {
                        await feature.release(button.action)
                    }
                )
            } else {
                VStack(spacing: 10) {
                    ForEach(buttons, id: \.id) { button in
                        SystemTouchButton(
                            button: button,
                            theme: feature.theme,
                            size: CGSize(width: size.width, height: (size.height - 10) / 2),
                            onPress: {
                                await feature.press(button.action)
                                feature.bumpPrimaryControls()
                            },
                            onRelease: {
                                await feature.release(button.action)
                            }
                        )
                    }
                }
            }
        }
        .opacity(opacity)
        .position(center)
    }
}

private struct SystemTouchButton: View {
    let button: TouchButtonSpec
    let theme: PlayerVisualTheme
    let size: CGSize
    let onPress: @MainActor () async -> Void
    let onRelease: @MainActor () async -> Void

    @State private var pressed = false

    var body: some View {
        Capsule()
            .fill(pressed ? theme.neutralControlStyle.pressedFillColor : theme.neutralControlStyle.fillColor)
            .overlay {
                Capsule()
                    .stroke(pressed ? theme.neutralControlStyle.pressedBorderColor : theme.neutralControlStyle.borderColor, lineWidth: 1.5)
                Text(button.label)
                    .font(.system(size: min(size.width, size.height) * 0.34, weight: .semibold, design: .rounded))
                    .foregroundStyle(theme.neutralControlStyle.contentColor)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                    .padding(.horizontal, 8)
            }
            .frame(width: size.width, height: size.height)
            .contentShape(Capsule())
            .simultaneousGesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { _ in
                        guard !pressed else { return }
                        pressed = true
                        Task { await onPress() }
                    }
                    .onEnded { _ in
                        guard pressed else { return }
                        pressed = false
                        Task { await onRelease() }
                    }
            )
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(button.label)
            .accessibilityAddTraits(.isButton)
            .accessibilityAction {
                Task {
                    await onPress()
                    try? await Task.sleep(for: .milliseconds(100))
                    await onRelease()
                }
            }
            .accessibilityIdentifier("player.touch.\(button.id)")
    }
}

private struct DSPointerOverlay: View {
    let feature: PlayerFeatureModel
    /// Where the touch screen is drawn: below, beside, or before the top screen.
    let touchScreenFrame: CGRect

    var body: some View {
        let width = touchScreenFrame.width
        let height = touchScreenFrame.height
        // libretro pointer coordinates cover the combined video frame.
        Color.clear
            .frame(width: width, height: height)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in send(value.location, width: width, height: height, pressed: true) }
                    .onEnded { value in send(value.location, width: width, height: height, pressed: false) }
            )
            .position(x: touchScreenFrame.midX, y: touchScreenFrame.midY)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(feature.isThreeDS ? "Nintendo 3DS touch screen" : "Nintendo DS touch screen")
            .accessibilityIdentifier(feature.isThreeDS ? "player.3ds.pointer" : "player.nds.pointer")
    }

    private func send(_ point: CGPoint, width: CGFloat, height: CGFloat, pressed: Bool) {
        let localX = min(max(point.x / max(width, 1), 0), 1)
        let x = feature.isThreeDS ? 0.1 + localX * 0.8 : localX
        let y = 0.5 + min(max(point.y / max(height, 1), 0), 1) / 2
        feature.enqueuePointer(normalizedX: x, normalizedY: y, pressed: pressed)
    }
}

private struct PlayerMenuButton: View {
    let feature: PlayerFeatureModel

    var body: some View {
        Button {
            Task { await feature.openPauseMenu() }
        } label: {
            Circle()
                .fill(feature.theme.neutralControlStyle.fillColor)
                .overlay {
                    Circle()
                        .stroke(feature.theme.neutralControlStyle.borderColor, lineWidth: 1.5)
                    Image(systemName: "line.3.horizontal")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(feature.theme.neutralControlStyle.contentColor)
                }
                .frame(width: 40, height: 40)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Pause game")
        .accessibilityIdentifier("player.menu")
    }
}

private struct TouchButtonControl: View {
    let button: TouchButtonSpec
    let theme: PlayerVisualTheme
    let size: CGFloat
    let familyID: String?
    let onPress: @MainActor () async -> Void
    let onRelease: @MainActor () async -> Void

    @State private var pressed = false

    var body: some View {
        let style = buttonStyle
        Circle()
            .fill(pressed ? style.pressedFillColor : style.fillColor)
            .overlay {
                Circle()
                    .stroke(pressed ? style.pressedBorderColor : style.borderColor, lineWidth: 1.5)
                if let iconName {
                    Image(systemName: iconName)
                        .font(.system(size: size * 0.34, weight: .bold))
                        .foregroundStyle(style.contentColor)
                } else {
                    Text(button.label)
                        .font(.system(size: size * 0.28, weight: .bold, design: .rounded))
                        .foregroundStyle(style.contentColor)
                }
            }
            .frame(width: size, height: size)
            .contentShape(Circle())
            .simultaneousGesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { _ in
                        guard !pressed else { return }
                        pressed = true
                        Task { await onPress() }
                    }
                    .onEnded { _ in
                        guard pressed else { return }
                        pressed = false
                        Task { await onRelease() }
                    }
            )
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(button.label)
            .accessibilityAddTraits(.isButton)
            .accessibilityAction {
                Task {
                    await onPress()
                    try? await Task.sleep(for: .milliseconds(100))
                    await onRelease()
                }
            }
            .accessibilityIdentifier("player.touch.\(button.id)")
    }

    private var buttonStyle: PlayerControlStyle {
        guard button.label.count <= 8 else { return theme.neutralControlStyle }
        return theme.faceStyle(label: button.label)
    }

    private var iconName: String? {
        switch button.label.lowercased() {
        case "up":
            "chevron.up"
        case "down":
            "chevron.down"
        case "left":
            "chevron.left"
        case "right":
            "chevron.right"
        default:
            nil
        }
    }
}

private enum ClusterStyle {
    case dpad
    case face(TouchFaceStyle)
}

private enum TouchFaceStyle {
    case twoDiagonal
    case diamond
    case arcadeGrid
    case segaArc
}

private struct FixedTouchBindingElement {
    let buttons: [TouchButtonSpec]
    let scale: Double
    let opacity: Double
}

private struct FixedTouchBindings {
    let dpadElement: FixedTouchBindingElement?
    let faceElement: FixedTouchBindingElement?
    let systemButtons: [TouchButtonSpec]
    let leftTriggers: [TouchButtonSpec]
    let rightTriggers: [TouchButtonSpec]
    let faceStyle: TouchFaceStyle
    let leftHanded: Bool
    let primaryOpacity: Double
}

private struct FixedTouchMetrics {
    let screenWidth: CGFloat
    let screenHeight: CGFloat
    let scale: CGFloat

    private var clampedScale: CGFloat { min(max(scale, 0.80), 1.30) }
    var zoneSize: CGFloat { 141 * clampedScale }
    var triggerZoneWidth: CGFloat { 96 * clampedScale }
    var triggerZoneHeight: CGFloat { 40 * clampedScale }
    var portraitTriggerWidth: CGFloat { 68 * clampedScale }
    var portraitTriggerHeight: CGFloat { 28 * clampedScale }
    var controlButtonWidth: CGFloat { 41.6 * clampedScale }
    var controlButtonHeight: CGFloat { 24 * clampedScale }
    var portraitControlButtonWidth: CGFloat { 52 * clampedScale }
    var portraitControlButtonHeight: CGFloat { 30 * clampedScale }
    var menuButtonSize: CGFloat { 40 * clampedScale }
    let systemGap: CGFloat = 8
    let portraitSideInset: CGFloat = 18
    let screenEdgeInset: CGFloat = 8
    let controlRowTopOffset: CGFloat = 10
    var portraitControlRowHeight: CGFloat { max(portraitControlButtonHeight, menuButtonSize) }
    let controlAnchorGap: CGFloat = 12
    let landscapeSideInset: CGFloat = 14
    let landscapeTopInset: CGFloat = 20
    let landscapeZoneDrop: CGFloat = 34

    func portraitBandBottom(bottomInset: CGFloat) -> CGFloat {
        screenHeight - (24 + bottomInset)
    }

    func landscapeBottomInset(bottomInset: CGFloat) -> CGFloat {
        18 + bottomInset
    }

    func landscapeRailInset(sideInset: CGFloat) -> CGFloat {
        max(6, sideInset * 0.12)
    }

    func landscapeTertiaryInset(sideInset: CGFloat) -> CGFloat {
        max(12, sideInset * 0.2)
    }
}

private func fixedTouchBindings(for controlsState: PlayerControlsState) -> FixedTouchBindings? {
    guard let layout = controlsState.touchLayout,
          let preset = controlsState.platformProfile.presets.first(where: { $0.presetID == layout.presetID }) else {
        return FixedTouchBindings(
            dpadElement: nil,
            faceElement: nil,
            systemButtons: [],
            leftTriggers: [],
            rightTriggers: [],
            faceStyle: .twoDiagonal,
            leftHanded: controlsState.touchLayout?.leftHanded ?? false,
            primaryOpacity: controlsState.touchLayout?.opacity ?? 0.72
        )
    }

    let stateByID = Dictionary(uniqueKeysWithValues: layout.elementStates.map { ($0.elementID, $0) })
    func elementState(for element: TouchElementSpec) -> FixedTouchBindingElement {
        let state = stateByID[element.id]
        return FixedTouchBindingElement(
            buttons: element.buttons,
            scale: state?.scale ?? element.baseScale,
            opacity: layout.opacity
        )
    }

    let dpadElement = preset.elements.first(where: { $0.id.localizedCaseInsensitiveContains("dpad") || $0.label.caseInsensitiveCompare("D-Pad") == .orderedSame }).map(elementState)
    let faceElement = preset.elements.first(where: { $0.label.caseInsensitiveCompare("Face") == .orderedSame }).map(elementState)
    let shoulderButtons = preset.elements
        .filter { $0.label.caseInsensitiveCompare("Shoulders") == .orderedSame || $0.id.localizedCaseInsensitiveContains("shoulder") }
        .flatMap(\.buttons)
    let leftTriggers = shoulderButtons
        .filter { $0.id.localizedCaseInsensitiveContains("left") || $0.label.hasPrefix("L") }
        .sorted { $0.label < $1.label }
    let rightTriggers = shoulderButtons
        .filter { $0.id.localizedCaseInsensitiveContains("right") || $0.label.hasPrefix("R") }
        .sorted { $0.label < $1.label }
    let systemButtons = preset.elements
        .first(where: { $0.id == "system" || $0.label.caseInsensitiveCompare("System") == .orderedSame })?
        .buttons ?? []

    let faceStyle: TouchFaceStyle
    switch controlsState.platformProfile.familyID {
    case "arcade":
        faceStyle = .arcadeGrid
    case "sega16":
        faceStyle = .segaArc
    default:
        faceStyle = (faceElement?.buttons.count ?? 0) <= 2 ? .twoDiagonal : .diamond
    }

    return FixedTouchBindings(
        dpadElement: dpadElement,
        faceElement: faceElement,
        systemButtons: systemButtons,
        leftTriggers: leftTriggers,
        rightTriggers: rightTriggers,
        faceStyle: faceStyle,
        leftHanded: layout.leftHanded,
        primaryOpacity: layout.opacity
    )
}

private func systemGroupHeight(count: Int, buttonHeight: CGFloat, gap: CGFloat) -> CGFloat {
    guard count > 0 else { return 0 }
    return (buttonHeight * CGFloat(count)) + (gap * CGFloat(max(count - 1, 0)))
}

/// Pause for a running player.
private struct PlayerPauseSheet: View {
    let feature: PlayerFeatureModel

    var body: some View {
        PlayerPausePanel(
            title: feature.presentation.romTitle,
            platformSlug: feature.presentation.platformSlug,
            runtimeName: feature.presentation.runtimeName,
            actions: PlayerPauseActions(
                resume: { await feature.resumeGame() },
                quickSave: { await feature.quickSave() },
                savedProgress: { await feature.openStateBrowser() },
                controls: {
                    feature.pauseSheetPresented = false
                    feature.controlsSheetPresented = true
                },
                sync: { await feature.syncSaves() },
                reset: { await feature.resetCore() },
                leave: { await feature.leaveGame() }
            )
        )
    }
}

/// What the Pause panel's buttons do.
struct PlayerPauseActions {
    let resume: () async -> Void
    let quickSave: () async -> Void
    let savedProgress: () async -> Void
    let controls: () -> Void
    let sync: () async -> Void
    let reset: () async -> Void
    let leave: () async -> Void
}

/// Pause: the game, Resume, and the actions as tiles. Aurora: glass tiles.
/// Riso: a numbered manual page, Leave game printed in pink. The action titles
/// and identifiers are the player's contract and never change. A sheet over the
/// game, or the floating inspector beside it on a wide window.
struct PlayerPausePanel: View {
    @Environment(\.rommioTheme) private var theme
    let title: String
    let platformSlug: String
    let runtimeName: String
    let actions: PlayerPauseActions

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            header
            Button {
                Task { await actions.resume() }
            } label: {
                Label("Resume", systemImage: "play.fill")
            }
            .buttonStyle(PrimaryButtonStyle(large: false))
            .accessibilityIdentifier("player.pause.resume")

            LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
                PauseTile(number: 1, title: "Save snapshot", symbol: "camera") {
                    Task { await actions.quickSave() }
                }
                .accessibilityIdentifier("player.pause.quickSave")
                PauseTile(number: 2, title: "Saved progress", symbol: "square.stack.3d.up") {
                    Task { await actions.savedProgress() }
                }
                .accessibilityIdentifier("player.pause.stateBrowser")
                PauseTile(number: 3, title: "Controls", symbol: "gamecontroller") {
                    actions.controls()
                }
                .accessibilityIdentifier("player.pause.controls")
                PauseTile(number: 4, title: "Sync game saves and snapshots", symbol: "arrow.triangle.2.circlepath") {
                    Task { await actions.sync() }
                }
                .accessibilityIdentifier("player.pause.sync")
                PauseTile(number: 5, title: "Reset core", symbol: "arrow.counterclockwise") {
                    Task { await actions.reset() }
                }
                .accessibilityIdentifier("player.pause.reset")
                PauseTile(number: 6, title: "Leave game", symbol: "rectangle.portrait.and.arrow.right", destructive: true) {
                    Task { await actions.leave() }
                }
                .accessibilityIdentifier("player.pause.leave")
            }
        }
        .padding(.horizontal, theme.spacing.gutter)
        .padding(.top, 22)
        .padding(.bottom, 12)
        .frame(maxWidth: 620, maxHeight: .infinity, alignment: .top)
        .frame(maxWidth: .infinity)
        .background { PageBackground(style: .quiet) }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("player.pause.actions")
    }

    @ViewBuilder
    private var header: some View {
        if theme.isRiso {
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text("Paused")
                    Spacer()
                    Text("\(platformSlug.uppercased()) · \(runtimeName)").foregroundStyle(theme.inkSecondary)
                }
                .themeText(.label, size: 10.5)
                .foregroundStyle(theme.ink)
                .padding(.bottom, 6)
                .overlay(alignment: .bottom) { Rectangle().fill(theme.ink).frame(height: 3) }
                Text(title).themeText(.headline, size: 26).foregroundStyle(theme.ink).lineLimit(1)
            }
        } else {
            VStack(alignment: .leading, spacing: 3) {
                Text("Paused").themeText(.section).foregroundStyle(theme.inkSecondary)
                Text(title).themeText(.headline).foregroundStyle(theme.ink).lineLimit(1)
            }
        }
    }
}

/// One pause action.
private struct PauseTile: View {
    @Environment(\.rommioTheme) private var theme
    let number: Int
    let title: String
    let symbol: String
    var destructive = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(alignment: .center, spacing: 10) {
                if theme.isRiso {
                    Text("\(number)").themeText(.statValue, size: 26).foregroundStyle(destructive ? theme.onAccent : theme.accent2)
                        .frame(width: 20)
                }
                Image(systemName: symbol)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(destructive ? (theme.isRiso ? theme.onAccent : theme.badText) : (theme.isRiso ? theme.ink : theme.readableTint))
                    .frame(width: 22)
                Text(title)
                    .themeText(theme.isRiso ? .section : .bodyEmphasis, size: theme.isRiso ? 15 : 15)
                    .foregroundStyle(destructive ? (theme.isRiso ? theme.onAccent : theme.badText) : theme.ink)
                    .multilineTextAlignment(.leading)
                    .lineLimit(3)
                    .fixedSize(horizontal: false, vertical: true)
                    .minimumScaleFactor(0.8)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12)
            .frame(maxWidth: .infinity, minHeight: 60, alignment: .leading)
            .background {
                if theme.isRiso {
                    Rectangle().fill(destructive ? theme.accent1 : theme.surface)
                    if !destructive { Rectangle().strokeBorder(theme.ink, lineWidth: theme.material.strokeWidth) }
                } else {
                    GlassFill(shape: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    GlassEdge(shape: RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
    }
}

private struct PlayerControlsSheet: View {
    @Environment(\.rommioTheme) private var theme
    let feature: PlayerFeatureModel

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Toggle("Show touch controls", isOn: Binding(
                        get: { feature.controlsState?.preferences.touchControlsEnabled ?? true },
                        set: { value in Task { await feature.setTouchControlsEnabled(value) } }
                    ))

                    Toggle("Auto-hide on controller", isOn: Binding(
                        get: { feature.controlsState?.preferences.autoHideTouchOnController ?? true },
                        set: { value in Task { await feature.setAutoHideTouchOnController(value) } }
                    ))

                    Toggle("Left-handed swap", isOn: Binding(
                        get: { feature.controlsState?.touchLayout?.leftHanded ?? false },
                        set: { value in Task { await feature.setLeftHanded(value) } }
                    ))

                    LabeledSlider(
                        title: "Opacity",
                        value: Binding(
                            get: { feature.controlsState?.touchLayout?.opacity ?? 0.72 },
                            set: { value in Task { await feature.updateTouchOpacity(value) } }
                        ),
                        range: 0.25...1.0
                    )

                    LabeledSlider(
                        title: "Global size",
                        value: Binding(
                            get: { feature.controlsState?.touchLayout?.globalScale ?? 1.0 },
                            set: { value in Task { await feature.updateTouchGlobalScale(value) } }
                        ),
                        range: 0.92...1.3
                    )
                } header: {
                    SectionHeader(title: "Touch controls")
                }
                .themedRow()

                Section {
                    LabeledSlider(
                        title: "Deadzone",
                        value: Binding(
                            get: { feature.controlsState?.hardwareBinding.deadzone ?? 0.2 },
                            set: { value in Task { await feature.updateDeadzone(value) } }
                        ),
                        range: 0.05...0.5
                    )

                    if !feature.availableControllerTypes.isEmpty {
                        Picker("Controller type", selection: Binding(
                            get: { feature.controlsState?.hardwareBinding.controllerTypeID ?? -1 },
                            set: { value in
                                Task { await feature.setControllerType(value < 0 ? nil : value) }
                            }
                        )) {
                            Text("Default").tag(-1)
                            ForEach(feature.availableControllerTypes) { descriptor in
                                Text(descriptor.description).tag(descriptor.id)
                            }
                        }
                    }

                    Toggle("Rumble to device", isOn: Binding(
                        get: { feature.controlsState?.preferences.rumbleToDeviceEnabled ?? true },
                        set: { value in Task { await feature.setRumbleToDeviceEnabled(value) } }
                    ))
                } header: {
                    SectionHeader(title: "Controller")
                }
                .themedRow()

                Section {
                    Toggle("OLED black mode", isOn: Binding(
                        get: { feature.controlsState?.preferences.oledBlackModeEnabled ?? false },
                        set: { value in Task { await feature.setOLEDBlackModeEnabled(value) } }
                    ))

                    Toggle("Console colors", isOn: Binding(
                        get: { feature.controlsState?.preferences.consoleColorsEnabled ?? false },
                        set: { value in Task { await feature.setConsoleColorsEnabled(value) } }
                    )).accessibilityIdentifier("player.controls.consoleColors")
                } header: {
                    SectionHeader(title: "Display")
                }
                .themedRow()

                Section {
                    Button("Reset control tuning", role: .destructive) {
                        Task { await feature.resetControlTuning() }
                    }
                    .foregroundStyle(theme.badText)
                }
                .themedRow()
            }
            .themeText(.body, size: 16)
            .tint(theme.readableTint)
            .themedList()
            .navigationTitle("Controls")
            .accessibilityIdentifier("player.controls.form")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        feature.controlsSheetPresented = false
                    }
                }
            }
        }
    }
}

private struct LabeledSlider: View {
    @Environment(\.rommioTheme) private var theme
    let title: String
    @Binding var value: Double
    let range: ClosedRange<Double>

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(title).foregroundStyle(theme.ink)
                Spacer()
                Text(value.formatted(.percent.precision(.fractionLength(0))))
                    .themeText(.numeral, size: 13)
                    .foregroundStyle(theme.inkSecondary)
            }
            Slider(value: $value, in: range)
        }
    }
}

/// Notices and errors over the game: a dark glass pill (Aurora) or a paper
/// ticket with a yellow plate (Riso).
private struct PlayerBanner: View {
    @Environment(\.rommioTheme) private var theme
    let message: String
    let isError: Bool

    var body: some View {
        let label = theme.tokens.type.playerLabel
        HStack(spacing: 8) {
            Image(systemName: isError ? "exclamationmark.triangle.fill" : "checkmark")
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(theme.isRiso ? theme.player.toastInk.color : (isError ? DarkFrame.bad : DarkFrame.ink))
                .padding(theme.isRiso ? 3 : 0)
                .background(theme.isRiso ? (isError ? theme.accent1 : theme.accent4) : .clear)
            Text(message)
                .themeText(.playerLabel, size: theme.isRiso ? label.size : 13)
                .foregroundStyle(theme.isRiso ? theme.player.toastInk.color : DarkFrame.ink)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 9)
        .background {
            if theme.isRiso {
                Rectangle().fill(theme.player.toastFill.color)
                    .background(Rectangle().fill(isError ? theme.accent1 : theme.accent3).offset(x: 3, y: 3))
            } else {
                Capsule().fill(DarkFrame.glass.opacity(0.62))
                    .background(Capsule().fill(.ultraThinMaterial))
            }
        }
        .accessibilityElement(children: .combine)
    }
}

private struct RenderedTouchElement: Identifiable {
    let id: String
    let label: String
    let layoutKind: TouchElementLayoutKind
    let buttons: [TouchButtonSpec]
    let centerX: Double
    let centerY: Double
    let scale: Double
    let opacity: Double
}

private extension PlayerControlsState {
    func touchLayoutResolvedElements() -> [RenderedTouchElement] {
        guard let layout = touchLayout,
              let preset = platformProfile.presets.first(where: { $0.presetID == layout.presetID }) else {
            return []
        }
        let stateByID = Dictionary(uniqueKeysWithValues: layout.elementStates.map { ($0.elementID, $0) })
        return preset.elements.map { element in
            let state = stateByID[element.id]
            return RenderedTouchElement(
                id: element.id,
                label: element.label,
                layoutKind: element.layoutKind,
                buttons: element.buttons,
                centerX: state?.centerX ?? element.centerX,
                centerY: state?.centerY ?? element.centerY,
                scale: state?.scale ?? element.baseScale,
                opacity: layout.opacity
            )
        }
    }

    func touchLayoutElement(id: String) -> RenderedTouchElement? {
        touchLayoutResolvedElements().first(where: { $0.id == id })
    }
}

private struct PlayerHostContainer: UIViewControllerRepresentable {
    let controllerProvider: () throws -> PlayerHostController

    func makeUIViewController(context: Context) -> UIViewController {
        do {
            return try controllerProvider()
        } catch {
            return PlayerErrorViewController(message: error.localizedDescription)
        }
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

private final class PlayerErrorViewController: UIViewController {
    private let message: String

    init(message: String) {
        self.message = message
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        view.accessibilityIdentifier = "player.error"

        let label = UILabel()
        label.translatesAutoresizingMaskIntoConstraints = false
        label.text = message
        label.textColor = .white
        label.numberOfLines = 0
        label.textAlignment = .center
        label.accessibilityIdentifier = "player.errorMessage"
        view.addSubview(label)

        NSLayoutConstraint.activate([
            label.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            label.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
            label.centerYAnchor.constraint(equalTo: view.centerYAnchor),
        ])
    }
}
#else
struct PlayerExperienceView: View {
    let feature: PlayerFeatureModel

    var body: some View {
        VStack(spacing: 12) {
            Text(feature.presentation.romTitle)
                .font(.headline)
            Text("Player hosting requires UIKit.")
                .foregroundStyle(.secondary)
        }
        .padding(24)
    }
}
#endif
