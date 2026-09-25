import CoreGraphics
import RommioFoundation
import RommioPlayerKit

public enum PlayerPrimaryControlsPlacement: String, Hashable, Sendable {
    case bottomBand
    case sideRails
    case hiddenControllerFirst
}

public enum PlayerMenuPlacement: String, Hashable, Sendable {
    case utilityRow
    /// Landscape on a phone: Menu bottom centre, under the game.
    case bottomCenter
    case portraitTopLeading
    case portraitTopTrailing
    case landscapeLowerLeading
    case landscapeLowerTrailing
}

public enum PlayerSystemPlacement: String, Hashable, Sendable {
    case sideRails
    case utilityRow
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
    /// Where the DS touch screen is drawn; stylus input maps from here.
    public var touchScreenFrame: CGRect? = nil

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
    controlsState: PlayerControlsState,
    stage: PlayerStageContext = PlayerStageContext()
) -> PlayerOverlaySnapshot {
    let family = controlsState.platformProfile.familyID
    let plan = PlayerControlPlan(family: family)
    let visible = controlsState.showTouchControls && controlsState.platformProfile.supportTier == .touchSupported && plan != nil
    let controls = ExpansionControls.controls(for: family) ?? []
    let stageLayout = resolvePlayerStageLayout(containerSize: containerSize, safeAreaInsets: safeAreaInsets,
        aspectRatio: controlsState.platformProfile.preferredViewportAspectRatio,
        topAlignedInPortrait: controlsState.platformProfile.supportTier == .touchSupported,
        controlDeckHeight: ExpansionControls.deckHeight(controls), controlScale: controlsState.touchLayout?.globalScale ?? 1,
        reserveTouchSpace: visible, utilityCount: 1, stage: stage,
        controls: plan, mirrored: controlsState.touchLayout?.leftHanded ?? false)
    let deck = stageLayout.deck
    let viewport = stageLayout.viewport
    let layout = stageLayout.controls
    let menuCenter = layout?.menuCenter ?? CGPoint(x: deck.utilityX(index: 0, count: 1, width: containerSize.width), y: deck.utilityY)
    let systems = visible ? (layout?.controls.filter { $0.kind == .system } ?? []) : []
    let menuUnderGame = menuCenter.y - PlayerControlMetrics.menuDiameter / 2 > viewport.frame.maxY && abs(menuCenter.x - viewport.frame.midX) < 1
    let side: PlayerMenuPlacement = menuCenter.x < containerSize.width / 2 ? .landscapeLowerLeading : .landscapeLowerTrailing
    let menuPlacement: PlayerMenuPlacement = deck.usesUtilityRow ? .utilityRow : (visible && menuUnderGame ? .bottomCenter : side)
    var snapshot = PlayerOverlaySnapshot(viewportFrame: viewport.frame, isPortrait: viewport.isPortrait,
        primaryControlsPlacement: visible ? (deck.isPortrait ? .bottomBand : .sideRails) : .hiddenControllerFirst,
        menuPlacement: menuPlacement,
        systemPlacement: systems.isEmpty ? .none : (deck.usesUtilityRow ? .utilityRow : .sideRails),
        showsGameplayTouchControls: visible, showsPointerOverlay: ["nds", "3ds"].contains(family) && visible,
        menuCenter: menuCenter,
        systemFrame: systems.isEmpty ? nil : systems.reduce(CGRect.null) { $0.union($1.frame) },
        leftRailInset: visible ? deck.leftOrigin : nil,
        rightRailInset: visible ? containerSize.width - deck.rightOrigin - deck.railWidth : nil)
    snapshot.touchScreenFrame = stageLayout.touchScreen?.frame
    return snapshot
}
