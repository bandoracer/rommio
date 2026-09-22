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
    /// Game and controls share a folded display half and half.
    public var isPadMode = false

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
    let controls = ExpansionControls.controls(for: controlsState.platformProfile.familyID) ?? []
    let visible = controlsState.showTouchControls && controlsState.platformProfile.supportTier == .touchSupported
    let height = ExpansionControls.deckHeight(controls)
    let scale = controlsState.touchLayout?.globalScale ?? 1
    let utility = visible ? controls.filter(\.center) : []
    let stageLayout = resolvePlayerStageLayout(containerSize: containerSize, safeAreaInsets: safeAreaInsets,
        aspectRatio: controlsState.platformProfile.preferredViewportAspectRatio,
        topAlignedInPortrait: controlsState.platformProfile.supportTier == .touchSupported,
        controlDeckHeight: height, controlScale: scale, reserveTouchSpace: visible, utilityCount: utility.count + 1,
        stage: stage)
    let deck = stageLayout.deck
    let viewport = stageLayout.viewport
    let menuIndex = utility.count / 2
    let menuX = deck.utilityX(index: menuIndex, count: utility.count + 1, width: containerSize.width)
    let systemTargets = utility.indices.map { index in
        CGRect(x: deck.utilityX(index: index < menuIndex ? index : index + 1, count: utility.count + 1, width: containerSize.width) - 24,
               y: deck.utilityY - 24, width: 48, height: 48)
    }
    var snapshot = PlayerOverlaySnapshot(viewportFrame: viewport.frame, isPortrait: viewport.isPortrait,
        primaryControlsPlacement: visible ? (deck.isPortrait ? .bottomBand : .sideRails) : .hiddenControllerFirst,
        menuPlacement: deck.isPortrait ? .utilityRow : (menuX < containerSize.width / 2 ? .landscapeLowerLeading : .landscapeLowerTrailing),
        systemPlacement: utility.isEmpty ? .none : (deck.isPortrait ? .utilityRow : .sideRails),
        showsGameplayTouchControls: visible, showsPointerOverlay: controlsState.platformProfile.familyID == "nds" && visible,
        menuCenter: CGPoint(x: deck.utilityX(index: menuIndex, count: utility.count + 1, width: containerSize.width), y: deck.utilityY),
        systemFrame: systemTargets.isEmpty ? nil : systemTargets.reduce(CGRect.null) { $0.union($1) },
        leftRailInset: visible ? deck.leftOrigin : nil,
        rightRailInset: visible ? containerSize.width - deck.rightOrigin - deck.railWidth : nil)
    snapshot.touchScreenFrame = stageLayout.touchScreen?.frame
    snapshot.isPadMode = stageLayout.isPadMode
    return snapshot
}
