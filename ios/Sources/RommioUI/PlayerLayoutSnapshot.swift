import CoreGraphics
import RommioFoundation
import RommioPlayerKit

public enum PlayerPrimaryControlsPlacement: String, Hashable, Sendable {
    case bottomBand
    case sideRails
    case hiddenControllerFirst
}

public enum PlayerMenuPlacement: String, Hashable, Sendable {
    case portraitTopLeading
    case portraitTopTrailing
    case landscapeLowerLeading
    case landscapeLowerTrailing
}

public enum PlayerSystemPlacement: String, Hashable, Sendable {
    case portraitCenteredRow
    case landscapeLeadingColumn
    case landscapeTrailingColumn
    case none
}

public struct PlayerOverlaySnapshot: Hashable, Sendable {
    public let viewportFrame: CGRect
    public let isPortrait: Bool
    public let primaryControlsPlacement: PlayerPrimaryControlsPlacement
    public let menuPlacement: PlayerMenuPlacement
    public let systemPlacement: PlayerSystemPlacement
    public let showsGameplayTouchControls: Bool
    public let showsPointerOverlay: Bool
    public let menuCenter: CGPoint?
    public let systemFrame: CGRect?
    public let leftRailInset: CGFloat?
    public let rightRailInset: CGFloat?

    public init(
        viewportFrame: CGRect,
        isPortrait: Bool,
        primaryControlsPlacement: PlayerPrimaryControlsPlacement,
        menuPlacement: PlayerMenuPlacement,
        systemPlacement: PlayerSystemPlacement,
        showsGameplayTouchControls: Bool,
        showsPointerOverlay: Bool,
        menuCenter: CGPoint?,
        systemFrame: CGRect?,
        leftRailInset: CGFloat?,
        rightRailInset: CGFloat?
    ) {
        self.viewportFrame = viewportFrame
        self.isPortrait = isPortrait
        self.primaryControlsPlacement = primaryControlsPlacement
        self.menuPlacement = menuPlacement
        self.systemPlacement = systemPlacement
        self.showsGameplayTouchControls = showsGameplayTouchControls
        self.showsPointerOverlay = showsPointerOverlay
        self.menuCenter = menuCenter
        self.systemFrame = systemFrame
        self.leftRailInset = leftRailInset
        self.rightRailInset = rightRailInset
    }
}

public func resolvePlayerOverlaySnapshot(
    containerSize: CGSize,
    safeAreaInsets: PlayerViewportSafeAreaInsets,
    controlsState: PlayerControlsState
) -> PlayerOverlaySnapshot {
    let viewport = resolvePlayerViewportLayout(
        containerSize: containerSize,
        safeAreaInsets: safeAreaInsets,
        aspectRatio: controlsState.platformProfile.preferredViewportAspectRatio,
        topAlignedInPortrait: controlsState.platformProfile.supportTier == .touchSupported
    )

    let leftHanded = controlsState.touchLayout?.leftHanded ?? false
    let systemButtons = controlsState.platformProfile.presets
        .first(where: { $0.presetID == controlsState.touchLayout?.presetID ?? controlsState.platformProfile.defaultPresetID })?
        .elements
        .first(where: { $0.id == "system" || $0.label.caseInsensitiveCompare("System") == .orderedSame })?
        .buttons ?? []
    let scale = CGFloat(controlsState.touchLayout?.globalScale ?? 1)
    let metrics = SnapshotTouchMetrics(
        screenWidth: containerSize.width,
        screenHeight: containerSize.height,
        scale: scale
    )

    let showsGameplayTouchControls = controlsState.showTouchControls && controlsState.platformProfile.supportTier == .touchSupported

    let primaryPlacement: PlayerPrimaryControlsPlacement
    if !showsGameplayTouchControls {
        primaryPlacement = .hiddenControllerFirst
    } else if viewport.isPortrait {
        primaryPlacement = .bottomBand
    } else {
        primaryPlacement = .sideRails
    }

    let menuPlacement: PlayerMenuPlacement
    if viewport.isPortrait {
        menuPlacement = leftHanded ? .portraitTopTrailing : .portraitTopLeading
    } else {
        menuPlacement = leftHanded ? .landscapeLowerTrailing : .landscapeLowerLeading
    }

    let systemPlacement: PlayerSystemPlacement
    if systemButtons.isEmpty {
        systemPlacement = .none
    } else if viewport.isPortrait {
        systemPlacement = .portraitCenteredRow
    } else {
        systemPlacement = leftHanded ? .landscapeLeadingColumn : .landscapeTrailingColumn
    }

    let geometry = resolveOverlayGeometry(
        viewportFrame: viewport.frame,
        isPortrait: viewport.isPortrait,
        safeAreaInsets: safeAreaInsets,
        leftHanded: leftHanded,
        systemButtonCount: systemButtons.count,
        showsGameplayTouchControls: showsGameplayTouchControls,
        metrics: metrics
    )

    return PlayerOverlaySnapshot(
        viewportFrame: viewport.frame,
        isPortrait: viewport.isPortrait,
        primaryControlsPlacement: primaryPlacement,
        menuPlacement: menuPlacement,
        systemPlacement: systemPlacement,
        showsGameplayTouchControls: showsGameplayTouchControls,
        showsPointerOverlay: controlsState.platformProfile.familyID == "nds" && showsGameplayTouchControls,
        menuCenter: geometry.menuCenter,
        systemFrame: geometry.systemFrame,
        leftRailInset: geometry.leftRailInset,
        rightRailInset: geometry.rightRailInset
    )
}

private struct SnapshotTouchMetrics {
    let screenWidth: CGFloat
    let screenHeight: CGFloat
    let scale: CGFloat

    private var clampedScale: CGFloat { min(max(scale, 0.80), 1.30) }
    var zoneSize: CGFloat { 141 * clampedScale }
    var controlButtonWidth: CGFloat { 41.6 * clampedScale }
    var controlButtonHeight: CGFloat { 24 * clampedScale }
    var portraitControlButtonWidth: CGFloat { 52 * clampedScale }
    var portraitControlButtonHeight: CGFloat { 30 * clampedScale }
    var menuButtonSize: CGFloat { 40 * clampedScale }
    let systemGap: CGFloat = 8
    let screenEdgeInset: CGFloat = 8
    let controlRowTopOffset: CGFloat = 10

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

private struct OverlayGeometry {
    let menuCenter: CGPoint?
    let systemFrame: CGRect?
    let leftRailInset: CGFloat?
    let rightRailInset: CGFloat?
}

private func resolveOverlayGeometry(
    viewportFrame: CGRect,
    isPortrait: Bool,
    safeAreaInsets: PlayerViewportSafeAreaInsets,
    leftHanded: Bool,
    systemButtonCount: Int,
    showsGameplayTouchControls: Bool,
    metrics: SnapshotTouchMetrics
) -> OverlayGeometry {
    if isPortrait {
        let menuHalf = metrics.menuButtonSize / 2
        let menuY = safeAreaInsets.top + 14 + menuHalf
        let leadingMenuX = max(
            metrics.screenEdgeInset + menuHalf,
            viewportFrame.minX + menuHalf + 8
        )
        let trailingMenuX = min(
            metrics.screenWidth - metrics.screenEdgeInset - menuHalf,
            viewportFrame.maxX - menuHalf - 8
        )
        let menuCenter = CGPoint(x: leftHanded ? trailingMenuX : leadingMenuX, y: menuY)

        let systemFrame: CGRect?
        if systemButtonCount > 0 {
            let width = (CGFloat(systemButtonCount) * metrics.portraitControlButtonWidth)
                + (CGFloat(max(systemButtonCount - 1, 0)) * metrics.systemGap)
            let height = metrics.portraitControlButtonHeight
            let top = viewportFrame.maxY + metrics.controlRowTopOffset
            systemFrame = CGRect(
                x: viewportFrame.midX - (width / 2),
                y: top,
                width: width,
                height: height
            )
        } else {
            systemFrame = nil
        }

        return OverlayGeometry(
            menuCenter: menuCenter,
            systemFrame: systemFrame,
            leftRailInset: nil,
            rightRailInset: nil
        )
    }

    let leftRailInset = showsGameplayTouchControls ? metrics.landscapeRailInset(sideInset: safeAreaInsets.left) : nil
    let rightRailInset = showsGameplayTouchControls ? metrics.landscapeRailInset(sideInset: safeAreaInsets.right) : nil
    let bottomSafe = metrics.landscapeBottomInset(bottomInset: safeAreaInsets.bottom)
    let leadingEdgeInset = metrics.landscapeTertiaryInset(sideInset: safeAreaInsets.left)
    let trailingEdgeInset = metrics.landscapeTertiaryInset(sideInset: safeAreaInsets.right)
    let menuTop = metrics.screenHeight - bottomSafe - metrics.menuButtonSize - 6
    let menuLeft = leftHanded ? metrics.screenWidth - trailingEdgeInset - metrics.menuButtonSize : leadingEdgeInset
    let menuCenter = CGPoint(
        x: menuLeft + (metrics.menuButtonSize / 2),
        y: menuTop + (metrics.menuButtonSize / 2)
    )

    let systemFrame: CGRect?
    if systemButtonCount > 0 {
        let height = (CGFloat(systemButtonCount) * metrics.controlButtonHeight)
            + (CGFloat(max(systemButtonCount - 1, 0)) * metrics.systemGap)
        let width = metrics.controlButtonWidth
        let top = metrics.screenHeight - bottomSafe - height - 6
        let left = leftHanded ? leadingEdgeInset : metrics.screenWidth - trailingEdgeInset - width
        systemFrame = CGRect(x: left, y: top, width: width, height: height)
    } else {
        systemFrame = nil
    }

    return OverlayGeometry(
        menuCenter: menuCenter,
        systemFrame: systemFrame,
        leftRailInset: leftRailInset,
        rightRailInset: rightRailInset
    )
}
