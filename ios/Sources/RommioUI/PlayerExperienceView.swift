import SwiftUI
import RommioFoundation
import RommioPlayerKit

#if canImport(UIKit)
import UIKit

struct PlayerExperienceView: View {
    let feature: PlayerFeatureModel

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                feature.theme.canvasColor
                    .ignoresSafeArea()

                PlayerHostContainer {
                    try feature.makeHostController()
                }
                .accessibilityIdentifier("player.host")
                .ignoresSafeArea()

                Color.clear
                    .contentShape(Rectangle())
                    .ignoresSafeArea()
                    .onTapGesture {
                        feature.bumpPrimaryControls()
                        feature.bumpTertiaryControls(wakePrimary: false)
                    }

                PlayerOverlayChrome(
                    feature: feature,
                    size: geometry.size,
                    safeAreaInsets: geometry.safeAreaInsets
                )
            }
        }
        .interactiveDismissDisabled()
        .statusBarHidden()
        .accessibilityIdentifier("player.screen")
        .sheet(isPresented: pauseSheetBinding) {
            PlayerPauseSheet(feature: feature)
                .presentationDetents([.medium])
                .presentationDragIndicator(.visible)
                .accessibilityIdentifier("player.pause.sheet")
        }
        .sheet(isPresented: controlsSheetBinding) {
            PlayerControlsSheet(feature: feature)
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
                .accessibilityIdentifier("player.controls.sheet")
        }
        .sheet(isPresented: stateBrowserSheetBinding) {
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
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
            .accessibilityIdentifier("player.stateBrowser.sheet")
        }
        .task(id: feature.id) {
            await feature.startIfNeeded()
        }
    }

    private var pauseSheetBinding: Binding<Bool> {
        Binding(
            get: { feature.pauseSheetPresented },
            set: { feature.pauseSheetPresented = $0 }
        )
    }

    private var controlsSheetBinding: Binding<Bool> {
        Binding(
            get: { feature.controlsSheetPresented },
            set: { feature.controlsSheetPresented = $0 }
        )
    }

    private var stateBrowserSheetBinding: Binding<Bool> {
        Binding(
            get: { feature.stateBrowserPresented },
            set: { feature.stateBrowserPresented = $0 }
        )
    }
}

private struct PlayerOverlayChrome: View {
    let feature: PlayerFeatureModel
    let size: CGSize
    let safeAreaInsets: EdgeInsets

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
                topAlignedInPortrait: feature.supportTier == .touchSupported
            ).frame
    }

    var body: some View {
        ZStack {
            if let message = feature.noticeMessage {
                PlayerBanner(message: message, isError: false)
                    .padding(.top, 16)
                    .frame(maxHeight: .infinity, alignment: .top)
            }

            if let message = feature.errorMessage {
                PlayerBanner(message: message, isError: true)
                    .padding(.top, feature.noticeMessage == nil ? 16 : 72)
                    .frame(maxHeight: .infinity, alignment: .top)
            }

            PlayerTouchOverlay(
                feature: feature,
                size: size,
                safeAreaInsets: safeAreaInsets,
                viewportFrame: viewportFrame,
                overlaySnapshot: overlaySnapshot
            )

            if overlaySnapshot?.showsPointerOverlay == true {
                DSPointerOverlay(feature: feature, viewportFrame: viewportFrame)
            }
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
        let preferredZoneTop = systemRowBottom + 20
        let maximumZoneTop = metrics.portraitBandBottom(bottomInset: bottomInset) - metrics.zoneSize - 12
        let zoneTop = max(minimumZoneTop, min(maximumZoneTop, preferredZoneTop))
        let triggerCenterY = controlRowTop + 28 + (metrics.portraitTriggerHeight / 2)

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
                        x: dpadX + metrics.zoneSize - (metrics.portraitTriggerWidth / 2),
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
                        x: buttonX + metrics.zoneSize - (metrics.portraitTriggerWidth / 2),
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
        let menuTop = safeAreaInsets.top + 14 + menuHalf
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
            .accessibilityIdentifier("player.touch.\(button.id)")
    }
}

private struct DSPointerOverlay: View {
    let feature: PlayerFeatureModel
    let viewportFrame: CGRect

    var body: some View {
        let width = viewportFrame.width * 0.92
        let height = viewportFrame.height * 0.44
        let centerX = viewportFrame.midX
        let centerY = viewportFrame.minY + viewportFrame.height * 0.72

        RoundedRectangle(cornerRadius: 18)
            .fill(Color.white.opacity(0.01))
            .overlay {
                RoundedRectangle(cornerRadius: 18)
                    .stroke(Color.white.opacity(0.18), style: StrokeStyle(lineWidth: 1, dash: [8, 6]))
            }
            .frame(width: width, height: height)
            .position(x: centerX, y: centerY)
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        let localX = min(max((value.location.x - (centerX - width / 2)) / width, 0), 1)
                        let localY = min(max((value.location.y - (centerY - height / 2)) / height, 0), 1)
                        Task { await feature.dispatchPointer(normalizedX: localX, normalizedY: localY) }
                    }
            )
            .accessibilityIdentifier("player.nds.pointer")
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

private struct PlayerPauseSheet: View {
    let feature: PlayerFeatureModel

    var body: some View {
        List {
            Button("Resume") {
                Task { await feature.resumeGame() }
            }
            .accessibilityIdentifier("player.pause.resume")

            Button("Controls") {
                feature.pauseSheetPresented = false
                feature.controlsSheetPresented = true
            }
            .accessibilityIdentifier("player.pause.controls")

            Button("Quick save") {
                Task { await feature.quickSave() }
            }
            .accessibilityIdentifier("player.pause.quickSave")

            Button("Browse states") {
                Task { await feature.openStateBrowser() }
            }
            .accessibilityIdentifier("player.pause.stateBrowser")

            Button("Sync saves") {
                Task { await feature.syncSaves() }
            }
            .accessibilityIdentifier("player.pause.sync")

            Button("Reset core") {
                Task { await feature.resetCore() }
            }
            .accessibilityIdentifier("player.pause.reset")

            Button("Leave game", role: .destructive) {
                Task { await feature.leaveGame() }
            }
            .accessibilityIdentifier("player.pause.leave")
        }
        .scrollContentBackground(.hidden)
        .background(feature.theme.panelColor.ignoresSafeArea())
        .accessibilityIdentifier("player.pause.actions")
    }
}

private struct PlayerControlsSheet: View {
    let feature: PlayerFeatureModel

    var body: some View {
        NavigationStack {
            Form {
                Section("Touch controls") {
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
                        range: 0.75...1.35
                    )
                }

                Section("Controller") {
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
                }

                Section("Theme") {
                    Toggle("OLED black mode", isOn: Binding(
                        get: { feature.controlsState?.preferences.oledBlackModeEnabled ?? false },
                        set: { value in Task { await feature.setOLEDBlackModeEnabled(value) } }
                    ))

                    Toggle("Console colors", isOn: Binding(
                        get: { feature.controlsState?.preferences.consoleColorsEnabled ?? false },
                        set: { value in Task { await feature.setConsoleColorsEnabled(value) } }
                    ))
                }

                Section {
                    Button("Reset control tuning", role: .destructive) {
                        Task { await feature.resetControlTuning() }
                    }
                }
            }
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
    let title: String
    @Binding var value: Double
    let range: ClosedRange<Double>

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(title)
                Spacer()
                Text(value.formatted(.number.precision(.fractionLength(2))))
                    .foregroundStyle(.secondary)
            }
            Slider(value: $value, in: range)
        }
    }
}

private struct PlayerBanner: View {
    let message: String
    let isError: Bool

    var body: some View {
        Text(message)
            .font(.footnote.weight(.semibold))
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(isError ? Color.red.opacity(0.92) : Color.blue.opacity(0.92), in: Capsule())
            .foregroundStyle(.white)
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
