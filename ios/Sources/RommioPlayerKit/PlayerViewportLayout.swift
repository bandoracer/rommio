import CoreGraphics

/// One emulated screen: which part of the core's frame to draw, and where.
public struct PlayerScreenPlacement: Hashable, Sendable {
    /// Normalized source rect in the core frame, origin top-left.
    public let source: CGRect
    /// Destination rect in container points.
    public let frame: CGRect
    /// The stylus screen of a dual-screen console.
    public let isTouchScreen: Bool

    public init(source: CGRect, frame: CGRect, isTouchScreen: Bool = false) {
        self.source = source
        self.frame = frame
        self.isTouchScreen = isTouchScreen
    }
}

public struct PlayerViewportLayout {
    public let frame: CGRect
    public let isPortrait: Bool
    public let screens: [PlayerScreenPlacement]

    public init(frame: CGRect, isPortrait: Bool, screens: [PlayerScreenPlacement]? = nil) {
        self.frame = frame
        self.isPortrait = isPortrait
        self.screens = screens ?? [PlayerScreenPlacement(source: CGRect(x: 0, y: 0, width: 1, height: 1), frame: frame)]
    }
}

/// Window facts that shape the player beyond size and safe area: iPhone Duo's
/// fold, dual-screen consoles, and panes that share the window with the game.
public struct PlayerStageContext: Codable, Hashable, Sendable {
    /// Division reserved region (the hinge) in container coordinates.
    public var fold: CGRect?
    /// True while the device is partially folded; flat folds are inactive.
    public var foldIsActive: Bool
    /// The core frame holds two equal screens stacked vertically (Nintendo DS).
    public var dualScreen: Bool
    /// 3DS uses a 400x240 upper display and a centered 320x240 touch display.
    public var threeDS: Bool
    /// A wide unfolded phone may need a synthetic hinge; an iPad never does.
    public var isPhoneFormFactor: Bool
    /// Orientation policy expressed as a preference; iPhone Duo's inner display
    /// ignores supported orientations, so the player adapts instead of locking.
    public var prefersPortrait: Bool?
    /// Width taken by a side pane (live controls editor, state browser).
    public var trailingPaneWidth: CGFloat
    /// The device folds (iPhone Duo, open or closed). A reported fold or a
    /// phone window wider than any slab iPhone also counts; see `isFoldable(in:)`.
    public var foldable: Bool

    public init(fold: CGRect? = nil, foldIsActive: Bool = false, dualScreen: Bool = false, threeDS: Bool = false, isPhoneFormFactor: Bool = false, prefersPortrait: Bool? = nil,
                trailingPaneWidth: CGFloat = 0, foldable: Bool = false) {
        self.fold = fold
        self.foldIsActive = foldIsActive
        self.dualScreen = dualScreen
        self.threeDS = threeDS
        self.isPhoneFormFactor = isPhoneFormFactor
        self.prefersPortrait = prefersPortrait
        self.trailingPaneWidth = trailingPaneWidth
        self.foldable = foldable
    }

    /// The fold splits the window into leading and trailing halves.
    var hasVerticalFold: Bool { fold.map { $0.height >= $0.width } ?? false }
    /// The fold splits the window into top and bottom halves (tent or DS-style hold).
    var hasHorizontalFold: Bool { fold.map { $0.width > $0.height } ?? false }

    /// iPhone Duo rules apply: the device says it folds, a fold is reported, or
    /// a full-screen phone window is wider than any slab iPhone (the widest,
    /// Pro Max, is 440 pt in portrait; iPhone Duo is 466 pt closed and 669 pt open).
    /// Xcode 27.1 beta 1 reports no division region, hence the size check.
    public func isFoldable(in containerSize: CGSize) -> Bool {
        foldable || fold != nil || (isPhoneFormFactor && min(containerSize.width, containerSize.height) >= 460)
    }
}

/// Video and input use this same geometry. The rails never sit over the game.
///
/// Each rail is a `railWidth` × `deckHeight · scale` box at (`leftOrigin` or
/// `rightOrigin`, `railTop`); controls are placed inside it from their generated
/// rail coordinates, with the D-pad and face cluster centred on `clusterCenter`.
public struct PlayerControlDeckLayout {
    /// Vertical centre of the D-pad and face clusters in rail coordinates.
    public static let clusterCenter: CGFloat = 122

    public let scale: CGFloat
    public let railTop: CGFloat
    public let railWidth: CGFloat
    public let leftOrigin: CGFloat
    public let rightOrigin: CGFloat
    public let utilityY: CGFloat
    public let gameArea: CGRect
    public let isPortrait: Bool
    /// Centre of the Select · Menu · Start row when the utilities form one
    /// centred row (portrait, or under the game on iPhone Duo); nil when they sit
    /// at the foot of the side rails.
    public let utilityRowMidX: CGFloat?

    /// Select · Menu · Start form a single centred row.
    public var usesUtilityRow: Bool { utilityRowMidX != nil }

    public init(size: CGSize, insets: PlayerViewportSafeAreaInsets, deckHeight: Double, scale requestedScale: Double = 1,
                visible: Bool = true, aspectRatio: Double? = nil, utilityCount: Int = 3, railBase: CGFloat = 148) {
        self.init(bounds: CGRect(origin: .zero, size: size), insets: insets, deckHeight: deckHeight, scale: requestedScale,
                  visible: visible, aspectRatio: aspectRatio, utilityCount: utilityCount, portrait: nil, railBase: railBase)
    }

    /// Lays the deck out inside `bounds`. `portrait` forces a bottom deck (true)
    /// or side rails (false); nil derives it from the bounds' shape.
    public init(bounds: CGRect, insets: PlayerViewportSafeAreaInsets, deckHeight: Double, scale requestedScale: Double = 1,
                visible: Bool = true, aspectRatio: Double? = nil, utilityCount: Int = 3, portrait forcedPortrait: Bool?,
                railBase: CGFloat = 148) {
        let size = bounds.size
        let portrait = forcedPortrait ?? (size.height >= size.width)
        isPortrait = portrait
        utilityRowMidX = portrait ? bounds.midX : nil
        let height = CGFloat(max(1, deckHeight))
        let safeWidth = max(1, size.width - insets.left - insets.right)
        let safeHeight = max(1, size.height - insets.top - insets.bottom)
        let availableHeight = max(1, safeHeight - 72)
        let requested = min(max(requestedScale, 0.92), 1.3)
        var fittedScale = min(requested, (safeWidth - 16) / (2 * railBase), availableHeight / height)
        if !portrait, visible, requested <= 1, let aspectRatio {
            // First fit the video at full height, then use its existing side bars.
            // Default controls can compact to 44pt targets before reducing video.
            let naturalWidth = min(safeWidth, safeHeight * max(0.1, aspectRatio))
            let sideRoom = (safeWidth - naturalWidth) / 2 - 4
            fittedScale = min(fittedScale, max(0.92, sideRoom / railBase))
        }
        scale = fittedScale
        let utilityWidth = CGFloat(max(0, (utilityCount + 1) / 2 - 1)) * 50 + 48
        railWidth = portrait ? railBase * scale : max(railBase * scale, utilityWidth)
        leftOrigin = bounds.minX + insets.left + (portrait ? 8 : 0)
        rightOrigin = bounds.minX + size.width - insets.right - (portrait ? 8 : 0) - railWidth
        utilityY = bounds.minY + size.height - insets.bottom - 28
        railTop = bounds.minY + (portrait ? size.height - insets.bottom - 56 - height * scale : insets.top + 8 + (availableHeight - height * scale) / 2)
        let x = bounds.minX + (portrait ? insets.left + 12 : insets.left + (visible ? railWidth + 4 : 52))
        let right = bounds.minX + (portrait ? size.width - insets.right - 12 : size.width - insets.right - (visible ? railWidth + 4 : 52))
        let top = bounds.minY + (portrait ? insets.cameraTop + 8 : insets.top)
        // Landscape utilities live below the side controls, not below the game.
        let bottom = bounds.minY + (portrait ? (visible ? railTop - bounds.minY - 12 : size.height - insets.bottom - 56) : size.height - insets.bottom)
        gameArea = CGRect(x: x, y: top, width: max(1, right - x), height: max(1, bottom - top))
    }

    /// A deck with explicit geometry (iPhone Duo stage rules).
    init(scale: CGFloat, railTop: CGFloat, railWidth: CGFloat, leftOrigin: CGFloat, rightOrigin: CGFloat,
         utilityY: CGFloat, gameArea: CGRect, isPortrait: Bool, utilityRowMidX: CGFloat?) {
        self.scale = scale
        self.railTop = railTop
        self.railWidth = railWidth
        self.leftOrigin = leftOrigin
        self.rightOrigin = rightOrigin
        self.utilityY = utilityY
        self.gameArea = gameArea
        self.isPortrait = isPortrait
        self.utilityRowMidX = utilityRowMidX
    }

    public func utilityX(index: Int, count: Int, width: CGFloat) -> CGFloat {
        if let utilityRowMidX { return utilityRowMidX + (CGFloat(index) - CGFloat(count - 1) / 2) * 52 }
        // Keep Menu anchored when controls are hidden or a pause sheet opens.
        let menuIndex = (count - 1) / 2
        if index == menuIndex { return leftOrigin + 24 }
        let slot = index < menuIndex ? index + 1 : index
        let leadingCount = (count + 1) / 2
        if slot < leadingCount { return leftOrigin + 24 + CGFloat(slot) * 50 }
        let trailingCount = count - leadingCount
        return rightOrigin + railWidth / 2 + (CGFloat(slot - leadingCount) - CGFloat(trailingCount - 1) / 2) * 50
    }
}

/// Everything the renderer and touch overlays need for one window state.
public struct PlayerStageLayout {
    public let viewport: PlayerViewportLayout
    public let deck: PlayerControlDeckLayout
    /// Every touch control and the Menu button, placed for this stage (when the stage
    /// was resolved with a control plan).
    public var controls: PlayerTouchLayout? = nil

    public var screens: [PlayerScreenPlacement] { viewport.screens }
    public var touchScreen: PlayerScreenPlacement? { screens.first(where: \.isTouchScreen) }
}

/// Resolves the stage: where the game (or DS screens) and the controls go.
///
/// With a control plan (`controls`), touch controls follow docs/player-controls.md:
/// on a phone or tablet the portrait control band and the landscape rails
/// (`StandardStage`); on iPhone Duo and for DS the stage rules below, with rails
/// sized for the plan. Without one, the legacy rail geometry (148 pt rails).
public func resolvePlayerStageLayout(
    containerSize: CGSize,
    safeAreaInsets: PlayerViewportSafeAreaInsets,
    aspectRatio: Double?,
    topAlignedInPortrait: Bool,
    controlDeckHeight: Double = 200,
    controlScale: Double = 1,
    reserveTouchSpace: Bool? = nil,
    utilityCount: Int = 3,
    stage: PlayerStageContext = PlayerStageContext(),
    controls plan: PlayerControlPlan? = nil,
    mirrored: Bool = false
) -> PlayerStageLayout {
    guard let plan else {
        return resolveRailStage(containerSize: containerSize, safeAreaInsets: safeAreaInsets, aspectRatio: aspectRatio,
                                topAlignedInPortrait: topAlignedInPortrait, controlDeckHeight: controlDeckHeight, controlScale: controlScale,
                                reserveTouchSpace: reserveTouchSpace, utilityCount: utilityCount, stage: stage)
    }
    let visible = reserveTouchSpace ?? topAlignedInPortrait
    if visible, !stage.dualScreen, !stage.isFoldable(in: containerSize) {
        let pane = max(0, min(stage.trailingPaneWidth, containerSize.width * 0.6))
        let insets = PlayerViewportSafeAreaInsets(top: safeAreaInsets.top, bottom: safeAreaInsets.bottom,
                                                  left: safeAreaInsets.left, right: pane > 0 ? 0 : safeAreaInsets.right)
        var layout = StandardStage(bounds: CGRect(x: 0, y: 0, width: containerSize.width - pane, height: containerSize.height),
                                   insets: insets, plan: plan, requestedScale: CGFloat(controlScale), aspectRatio: aspectRatio,
                                   prefersPortrait: stage.prefersPortrait, mirrored: mirrored).layout()
        let screens = layout.screens.map(\.frame)
        layout.controls?.gameScreens = screens
        return layout
    }
    func railStage(_ plan: PlayerControlPlan, _ box: (base: CGFloat, height: CGFloat, clusterCenter: CGFloat)) -> PlayerStageLayout {
        resolveRailStage(containerSize: containerSize, safeAreaInsets: safeAreaInsets, aspectRatio: aspectRatio,
                         topAlignedInPortrait: topAlignedInPortrait,
                         controlDeckHeight: visible ? Double(box.height) : controlDeckHeight, controlScale: controlScale,
                         reserveTouchSpace: reserveTouchSpace, utilityCount: visible ? plan.utilities.count + 1 : 1, stage: stage,
                         railBase: box.base, clusterCenter: box.clusterCenter)
    }
    /// The stage for a plan, with its rail box measured again at the scale the stage
    /// picks (below full size the size floors make the controls relatively larger).
    func fittedRailStage(_ plan: PlayerControlPlan) -> (PlayerStageLayout, (base: CGFloat, height: CGFloat, clusterCenter: CGFloat)) {
        var box = plan.railBox()
        var layout = railStage(plan, box)
        for _ in 0 ..< 3 where visible && layout.deck.scale < 0.999 {
            let refined = plan.railBox(scale: layout.deck.scale)
            guard refined != box else { break }
            box = refined
            layout = railStage(plan, box)
        }
        return (layout, box)
    }
    var railPlan: PlayerControlPlan = plan
    var (layout, box) = fittedRailStage(plan)
    // A band too short for a stacked secondary (the 3DS d-pad under the circle pad) puts
    // the d-pad beside the stick, rather than shrinking the controls under their floor.
    if visible, layout.deck.scale < PlayerControlMetrics.scaleRange.lowerBound - 0.001, let compact = plan.compact() {
        let (alternative, compactBox) = fittedRailStage(compact)
        if alternative.deck.scale > layout.deck.scale {
            railPlan = compact
            layout = alternative
            box = compactBox
        }
    }
    let deck = layout.deck
    if visible {
        let railRange = deck.railTop ... deck.railTop + box.height * deck.scale
        let rowMaxWidth: CGFloat
        if let midX = deck.utilityRowMidX, railRange.contains(deck.utilityY) {
            // The row sits between the rails.
            rowMaxWidth = max(0, 2 * min(midX - (deck.leftOrigin + deck.railWidth), deck.rightOrigin - midX) - 12)
        } else {
            rowMaxWidth = max(0, containerSize.width - safeAreaInsets.left - safeAreaInsets.right - 24)
        }
        let utility: PlayerControlAnchors.Utility
        if let midX = deck.utilityRowMidX {
            utility = .row(midX: midX, y: deck.utilityY, maxWidth: rowMaxWidth)
        } else {
            utility = .feet(y: deck.utilityY, menu: railPlan.menuOnLeadingFoot ? .leadingFoot : .trailingFoot)
        }
        // Portrait (a band under the game, DS and 3DS): shoulders 16 pt above their
        // side's stack; landscape rails: at the top of the rail.
        let shoulders: PlayerControlAnchors.Shoulders = deck.isPortrait
            ? .aboveCluster(PlayerControlMetrics(scale: 1).shoulderLift) : .top(deck.railTop)
        let clusterY = deck.railTop + box.clusterCenter * deck.scale
        let anchors = PlayerControlAnchors(
            leftRail: deck.leftOrigin ... deck.leftOrigin + deck.railWidth,
            rightRail: deck.rightOrigin ... deck.rightOrigin + deck.railWidth,
            alignOuter: false, leftClusterY: clusterY, rightClusterY: clusterY,
            shoulders: shoulders, utility: utility)
        var controls = railPlan.place(anchors, metrics: PlayerControlMetrics(scale: deck.scale), mirrored: mirrored)
        controls.gameScreens = layout.screens.map(\.frame)
        layout.controls = controls
    } else {
        layout.controls = PlayerTouchLayout(controls: [], faceZone: nil, chord: nil,
                                            menuCenter: CGPoint(x: deck.utilityX(index: 0, count: 1, width: containerSize.width), y: deck.utilityY),
                                            menuDiameter: PlayerControlMetrics.menuDiameter,
                                            metrics: PlayerControlMetrics(scale: CGFloat(controlScale)))
    }
    return layout
}

/// The rail-based stage (iPhone Duo, DS, controls hidden, and the legacy geometry).
private func resolveRailStage(
    containerSize: CGSize,
    safeAreaInsets: PlayerViewportSafeAreaInsets,
    aspectRatio: Double?,
    topAlignedInPortrait: Bool,
    controlDeckHeight: Double,
    controlScale: Double,
    reserveTouchSpace: Bool?,
    utilityCount: Int,
    stage: PlayerStageContext,
    railBase: CGFloat = 148,
    clusterCenter: CGFloat = PlayerControlDeckLayout.clusterCenter
) -> PlayerStageLayout {
    let visible = reserveTouchSpace ?? topAlignedInPortrait
    let pane = max(0, min(stage.trailingPaneWidth, containerSize.width * 0.6))
    let bounds = CGRect(x: 0, y: 0, width: containerSize.width - pane, height: containerSize.height)
    // A side pane owns the trailing edge and its safe area.
    let insets = PlayerViewportSafeAreaInsets(top: safeAreaInsets.top, bottom: safeAreaInsets.bottom,
                                              left: safeAreaInsets.left, right: pane > 0 ? 0 : safeAreaInsets.right)
    // Keep zero-width folds: an inactive hinge on a flat display still marks the centre line.
    let fold = stage.fold.flatMap { region -> CGRect? in
        let clipped = region.intersection(bounds)
        return clipped.isNull ? nil : clipped
    }

    // iPhone Duo with touch controls showing: the studio stage rules.
    if visible, stage.isFoldable(in: containerSize) {
        let duo = FoldableStage(bounds: bounds, insets: insets, fold: fold, stage: stage,
                                isOpen: min(containerSize.width, containerSize.height) >= 600,
                                deckHeight: CGFloat(max(1, controlDeckHeight)),
                                requestedScale: CGFloat(min(max(controlScale, 0.92), 1.3)),
                                utilityCount: max(1, utilityCount), railBase: railBase, clusterCenter: clusterCenter)
        return stage.dualScreen ? duo.dualScreen() : duo.singleScreen(aspectRatio: aspectRatio)
    }

    func deck(_ rect: CGRect, portrait: Bool?, insets: PlayerViewportSafeAreaInsets, aspect: Double? = nil) -> PlayerControlDeckLayout {
        PlayerControlDeckLayout(bounds: rect, insets: insets, deckHeight: controlDeckHeight, scale: controlScale,
                                visible: visible, aspectRatio: aspect ?? aspectRatio, utilityCount: utilityCount, portrait: portrait,
                                railBase: railBase)
    }

    if stage.dualScreen {
        if stage.threeDS {
            return resolveThreeDSStage(bounds: bounds, insets: insets, fold: fold, stage: stage, visible: visible) {
                deck($0, portrait: $1, insets: $2, aspect: $3)
            }
        }
        return resolveDualScreenStage(bounds: bounds, insets: insets, fold: fold, stage: stage, visible: visible) {
            deck($0, portrait: $1, insets: $2, aspect: $3)
        }
    }

    // Standard layout. Near-square windows (Split View, a side pane) pick
    // whichever deck arrangement shows the larger game; others keep their shape.
    let ratio = bounds.width / max(bounds.height, 1)
    let candidates: [Bool?] = ratio > 0.85 && ratio < 1.18 ? [true, false] : [nil]
    var best: (layout: PlayerStageLayout, score: CGFloat)?
    for portrait in candidates {
        let controlDeck = deck(bounds, portrait: portrait, insets: insets)
        let area = controlDeck.gameArea
        let aspect = CGFloat(max(aspectRatio ?? (bounds.width / max(bounds.height, 1)), 0.1))
        let width = min(area.width, area.height * aspect)
        let height = width / aspect
        var frame = CGRect(x: area.midX - width / 2,
                           y: controlDeck.isPortrait && topAlignedInPortrait ? area.minY : area.midY - height / 2,
                           width: width, height: height)
        // Without touch controls, the same rule as with them (docs/player-controls.md,
        // Geometry): the display's full width in portrait, or its full height in landscape,
        // when that fits; otherwise the frame above, inside the border.
        if !visible {
            let fullWidth = bounds.width - insets.left - insets.right
            if controlDeck.isPortrait, area.minY + fullWidth / aspect <= area.maxY {
                let height = fullWidth / aspect
                frame = CGRect(x: bounds.minX + insets.left, y: topAlignedInPortrait ? area.minY : area.midY - height / 2,
                               width: fullWidth, height: height)
            } else if !controlDeck.isPortrait, bounds.height * aspect <= area.width {
                frame = CGRect(x: area.midX - bounds.height * aspect / 2, y: bounds.minY, width: bounds.height * aspect, height: bounds.height)
            }
        }
        var score = width * height
        if let prefers = stage.prefersPortrait, let portrait, prefers == portrait { score *= 1.15 }
        let layout = PlayerStageLayout(viewport: PlayerViewportLayout(frame: frame, isPortrait: controlDeck.isPortrait),
                                       deck: controlDeck)
        if best == nil || score > best!.score { best = (layout, score) }
    }
    return best!.layout
}

/// iPhone Duo stage rules, matching the design studio's `stage()` in
/// `design/studio/core/kit.js`. Controls never overlap a game screen, the
/// D-pad and face clusters sit low where thumbs rest, and the game is never
/// put on one half of the display with the controls on the other.
///
/// - Landscape: the game is centred between side rails; the clusters sit at
///   about 0.68 (closed) or 0.7 (open) of the height, shoulders just above
///   them; Select · Menu · Start go under the game when there is room,
///   otherwise at the foot of the rails.
/// - Portrait: the game spans the width at the top (it may cross a flat fold)
///   and the controls form a band beneath it, as low as the utility row allows.
/// - DS and 3DS: the two screens stack (the top screen above the fold when the
///   device is open in portrait) and the controls form a band at the bottom,
///   with the clusters in the corners and the utility row between them.
private struct FoldableStage {
    let bounds: CGRect
    let insets: PlayerViewportSafeAreaInsets
    let fold: CGRect?
    let stage: PlayerStageContext
    let isOpen: Bool
    let deckHeight: CGFloat
    let requestedScale: CGFloat
    let utilityCount: Int
    /// Rail width and cluster centre at scale 1 (a control plan's, or the legacy 148 / 122).
    var railBase: CGFloat = 148
    var clusterCenter: CGFloat = PlayerControlDeckLayout.clusterCenter

    /// Utility targets are 48 pt, 52 pt apart.
    private let rowHeight: CGFloat = 48
    private let rowPitch: CGFloat = 52
    private let screenGap: CGFloat = 12

    private var gap: CGFloat { isOpen ? 14 : 10 }
    private var safe: CGRect {
        CGRect(x: bounds.minX + insets.left, y: bounds.minY + insets.top,
               width: max(1, bounds.width - insets.left - insets.right),
               height: max(1, bounds.height - insets.top - insets.bottom))
    }
    /// The closed display's camera sits top-centre in portrait.
    private var cameraPad: CGFloat { !isOpen && bounds.height > bounds.width ? 10 : 0 }
    private var rowWidth: CGFloat { CGFloat(utilityCount - 1) * rowPitch + rowHeight }
    /// Width the utilities need when they sit at the foot of the rails.
    private var footUtilityWidth: CGFloat { CGFloat(max(0, (utilityCount + 1) / 2 - 1)) * 50 + 48 }
    /// Centre of the utility row, just above the bottom safe area.
    private var rowY: CGFloat { safe.maxY - gap - rowHeight / 2 }

    private func fittedScale(availableHeight: CGFloat) -> CGFloat {
        max(0.5, min(requestedScale, (safe.width - 3 * gap) / (2 * railBase), availableHeight / deckHeight))
    }

    // MARK: Single screen

    func singleScreen(aspectRatio: Double?) -> PlayerStageLayout {
        let ratio = bounds.width / max(bounds.height, 1)
        // Near-square windows (a side pane beside the game) take whichever
        // arrangement shows the larger game.
        let candidates: [Bool] = ratio > 0.85 && ratio < 1.18 ? [true, false] : [bounds.height >= bounds.width]
        var best: (layout: PlayerStageLayout, score: CGFloat)?
        for portrait in candidates {
            let layout = portrait ? portraitLayout(aspectRatio: aspectRatio) : landscapeLayout(aspectRatio: aspectRatio)
            var score = layout.viewport.frame.width * layout.viewport.frame.height
            if candidates.count > 1, let prefers = stage.prefersPortrait, prefers == portrait { score *= 1.15 }
            if best == nil || score > best!.score { best = (layout, score) }
        }
        return best!.layout
    }

    private func landscapeLayout(aspectRatio: Double?) -> PlayerStageLayout {
        let scale = fittedScale(availableHeight: safe.height - 2 * gap - rowHeight)
        // Rails always leave room for utilities at their foot, so the game's
        // size never depends on where the utility row lands.
        let railWidth = max(railBase * scale, footUtilityWidth)
        let leftOrigin = safe.minX + gap
        let rightOrigin = safe.maxX - gap - railWidth
        let columnMinX = leftOrigin + railWidth + gap
        let columnMaxX = max(columnMinX + 1, rightOrigin - gap)
        let available = CGSize(width: columnMaxX - columnMinX, height: max(1, safe.height - 2 * gap))
        let aspect = CGFloat(max(aspectRatio ?? Double(available.width / available.height), 0.1))
        var width = available.width
        var height = width / aspect
        if height > available.height {
            height = available.height
            width = height * aspect
        }
        // A little above centre, leaving the room below for the utility row.
        let slack = safe.height - height
        let game = CGRect(x: columnMinX + (available.width - width) / 2, y: safe.minY + max(gap, slack * 0.3),
                          width: width, height: height)
        // Select · Menu · Start under the game when there is room. Without a known
        // aspect the renderer may draw a taller frame, so they stay on the rails.
        let rowUnderGame = aspectRatio != nil
            && rowY - rowHeight / 2 >= game.maxY + gap / 2
            && rowWidth <= available.width
        let railFloor = rowUnderGame ? safe.maxY - gap : rowY - rowHeight / 2 - gap / 2
        let railHeight = deckHeight * scale
        let target = bounds.minY + bounds.height * (isOpen ? 0.7 : 0.68) - clusterCenter * scale
        let railTop = max(safe.minY + gap / 2, min(target, railFloor - railHeight))
        let deck = PlayerControlDeckLayout(
            scale: scale, railTop: railTop, railWidth: railWidth, leftOrigin: leftOrigin, rightOrigin: rightOrigin,
            utilityY: rowY, gameArea: CGRect(x: columnMinX, y: safe.minY + gap, width: available.width, height: available.height),
            isPortrait: false, utilityRowMidX: rowUnderGame ? game.midX : nil)
        return PlayerStageLayout(viewport: PlayerViewportLayout(frame: game, isPortrait: false), deck: deck)
    }

    private func portraitLayout(aspectRatio: Double?) -> PlayerStageLayout {
        let top = safe.minY + gap + cameraPad
        let scale = fittedScale(availableHeight: safe.height - 72)
        let railWidth = railBase * scale
        let railHeight = deckHeight * scale
        let railTop = rowY - rowHeight / 2 - gap / 2 - railHeight
        // Clusters under the thumbs (about a quarter in from each side of the
        // safe area), never past it and never touching each other.
        var leftOrigin = max(safe.minX + gap, safe.minX + safe.width * 0.26 - railWidth / 2)
        var rightOrigin = min(safe.maxX - gap - railWidth, safe.minX + safe.width * 0.74 - railWidth / 2)
        if rightOrigin < leftOrigin + railWidth + gap {
            leftOrigin = safe.minX + gap
            rightOrigin = safe.maxX - gap - railWidth
        }
        var floor = railTop - gap
        // Partly folded like a laptop: keep the game above the bend.
        if let fold, stage.foldIsActive, stage.hasHorizontalFold, fold.minY - gap > top + 1 {
            floor = min(floor, fold.minY - gap)
        }
        let available = CGSize(width: safe.width - 2 * gap, height: max(1, floor - top))
        let aspect = CGFloat(max(aspectRatio ?? Double(available.width / available.height), 0.1))
        var width = available.width
        var height = width / aspect
        if height > available.height {
            height = available.height
            width = height * aspect
        }
        let game = CGRect(x: safe.midX - width / 2, y: top, width: width, height: height)
        let deck = PlayerControlDeckLayout(
            scale: scale, railTop: railTop, railWidth: railWidth, leftOrigin: leftOrigin, rightOrigin: rightOrigin,
            utilityY: rowY, gameArea: CGRect(x: safe.minX + gap, y: top, width: available.width, height: available.height),
            isPortrait: true, utilityRowMidX: safe.midX)
        return PlayerStageLayout(viewport: PlayerViewportLayout(frame: game, isPortrait: true), deck: deck)
    }

    // MARK: DS and 3DS

    func dualScreen() -> PlayerStageLayout {
        let threeDS = stage.threeDS
        let topAspect: CGFloat = threeDS ? 5.0 / 3.0 : 4.0 / 3.0
        let touchAspect: CGFloat = 4.0 / 3.0
        let topSource = CGRect(x: 0, y: 0, width: 1, height: 0.5)
        let touchSource = threeDS ? CGRect(x: 0.1, y: 0.5, width: 0.8, height: 0.5) : CGRect(x: 0, y: 0.5, width: 1, height: 0.5)
        let top = safe.minY + gap + cameraPad

        // Clusters in the bottom corners; the utility row between them when it
        // fits, otherwise under them. The band never takes more than about half
        // the height, so both screens stay playable.
        let scale = fittedScale(availableHeight: min(safe.height - 72, safe.height * 0.45))
        let railWidth = railBase * scale
        let railHeight = deckHeight * scale
        let leftOrigin = safe.minX + gap
        let rightOrigin = safe.maxX - gap - railWidth
        let columnMinX = leftOrigin + railWidth + gap
        let columnMaxX = max(columnMinX, rightOrigin - gap)
        let columnWidth = columnMaxX - columnMinX
        let columnMidX = (columnMinX + columnMaxX) / 2
        let rowBetweenRails = rowWidth <= columnWidth
        let railTop = (rowBetweenRails ? safe.maxY - gap : rowY - rowHeight / 2 - gap / 2) - railHeight
        // A screen narrower than the centre column may reach down beside the
        // clusters to the utility row; a wider one must end above the clusters.
        let columnFloor = rowY - rowHeight / 2 - gap
        let openFloor = railTop - gap
        let fullWidth = safe.width - 2 * gap

        func widthLimit(bottom: CGFloat) -> CGFloat { bottom <= openFloor + 0.5 ? fullWidth : columnWidth }
        func place(_ width: CGFloat, _ aspect: CGFloat, y: CGFloat) -> CGRect {
            let midX = width > columnWidth + 0.5 ? safe.midX : columnMidX
            return CGRect(x: midX - width / 2, y: y, width: width, height: width / aspect)
        }

        let upper: CGRect
        let lower: CGRect
        // Open in portrait, the hinge runs across the middle like a DS: the top
        // screen sits above it and the touch screen below. Beta 1 reports no
        // hinge, so the midline of the open display stands in for it.
        let hinge = (stage.hasHorizontalFold ? fold : nil)
            ?? (isOpen && bounds.height > bounds.width ? CGRect(x: bounds.minX, y: bounds.midY - screenGap / 2, width: bounds.width, height: screenGap) : nil)
        if let hinge, hinge.minY - gap > top + 40 {
            let touchTop = hinge.maxY + gap
            let aboveRails = min(openFloor - touchTop, fullWidth / touchAspect)
            let inColumn = min(columnFloor - touchTop, columnWidth / touchAspect)
            let touchHeight = max(1, max(aboveRails, inColumn))
            lower = place(touchHeight * touchAspect, touchAspect, y: touchTop)
            let upperBottom = hinge.minY - gap
            // Use the spare room above the hinge without letting the top screen
            // dominate the pair: 1.3× the touch screen's width for DS, 1.5× for 3DS.
            let cap = lower.width * (threeDS ? 1.5 : 1.3)
            let upperWidth = max(1, min(widthLimit(bottom: upperBottom), (upperBottom - top) * topAspect, cap))
            upper = place(upperWidth, topAspect, y: upperBottom - upperWidth / topAspect)
        } else {
            // Both screens share one height, stacked from the top. Try both above
            // the rails, the top one above and the touch screen between the rails,
            // and both between the rails; keep the largest.
            let bothAbove = min((openFloor - top - screenGap) / 2, fullWidth / topAspect, fullWidth / touchAspect)
            let split = min(openFloor - top, (columnFloor - top - screenGap) / 2, fullWidth / topAspect, columnWidth / touchAspect)
            let bothInColumn = min((columnFloor - top - screenGap) / 2, columnWidth / topAspect, columnWidth / touchAspect)
            let height = max(1, bothAbove, split, bothInColumn)
            upper = place(height * topAspect, topAspect, y: top)
            lower = place(height * touchAspect, touchAspect, y: upper.maxY + screenGap)
        }
        let deck = PlayerControlDeckLayout(
            scale: scale, railTop: railTop, railWidth: railWidth, leftOrigin: leftOrigin, rightOrigin: rightOrigin,
            utilityY: rowY, gameArea: upper.union(lower), isPortrait: true, utilityRowMidX: columnMidX)
        return PlayerStageLayout(viewport: PlayerViewportLayout(frame: upper.union(lower), isPortrait: true, screens: [
            PlayerScreenPlacement(source: topSource, frame: upper),
            PlayerScreenPlacement(source: touchSource, frame: lower, isTouchScreen: true),
        ]), deck: deck)
    }
}

/// Fixed Azahar default layout: 400x240 top, centered 320x240 bottom in a 400x480 frame.
private func resolveThreeDSStage(
    bounds: CGRect,
    insets: PlayerViewportSafeAreaInsets,
    fold: CGRect?,
    stage: PlayerStageContext,
    visible: Bool,
    deck: (CGRect, Bool?, PlayerViewportSafeAreaInsets, Double) -> PlayerControlDeckLayout
) -> PlayerStageLayout {
    let topAspect: CGFloat = 5.0 / 3.0
    let bottomAspect: CGFloat = 4.0 / 3.0
    let gap: CGFloat = 12
    let topSource = CGRect(x: 0, y: 0, width: 1, height: 0.5)
    let bottomSource = CGRect(x: 0.1, y: 0.5, width: 0.8, height: 0.5)
    func result(_ upper: CGRect, _ lower: CGRect, _ controls: PlayerControlDeckLayout, compact: Bool = false, expandUpper: Bool = false) -> PlayerStageLayout {
        let sharedHeight = max(1, min(upper.width / topAspect, lower.width / bottomAspect, upper.height, lower.height))
        let bottomHeight = expandUpper ? max(1, min(lower.width / bottomAspect, lower.height)) : sharedHeight
        // In the open Duo pose the top pane has more room than the touch pane.
        // Use some of it without making the top screen dominate the pair.
        let topHeight = expandUpper
            ? max(1, min(upper.width / topAspect, upper.height, bottomHeight * bottomAspect * 1.5 / topAspect))
            : sharedHeight
        let pairTop = controls.isPortrait && visible ? upper.minY : (upper.minY + lower.maxY - (topHeight + bottomHeight + gap)) / 2
        let top = CGRect(x: upper.midX - topHeight * topAspect / 2, y: compact ? pairTop : upper.midY - topHeight / 2, width: topHeight * topAspect, height: topHeight)
        let bottom = CGRect(x: lower.midX - bottomHeight * bottomAspect / 2, y: compact ? pairTop + topHeight + gap : lower.midY - bottomHeight / 2, width: bottomHeight * bottomAspect, height: bottomHeight)
        return PlayerStageLayout(viewport: PlayerViewportLayout(frame: top.union(bottom), isPortrait: controls.isPortrait, screens: [
            PlayerScreenPlacement(source: topSource, frame: top),
            PlayerScreenPlacement(source: bottomSource, frame: bottom, isTouchScreen: true),
        ]), deck: controls)
    }
    let horizontalHinge = stage.hasHorizontalFold ? fold : nil
    let unfoldedHinge = stage.isPhoneFormFactor && bounds.height > bounds.width && bounds.width >= 600
        ? CGRect(x: bounds.minX, y: bounds.midY - gap / 2, width: bounds.width, height: gap) : nil
    if let hinge = horizontalHinge ?? unfoldedHinge {
        let upper = CGRect(x: bounds.minX, y: bounds.minY, width: bounds.width, height: max(1, hinge.minY - bounds.minY))
        let lower = CGRect(x: bounds.minX, y: hinge.maxY, width: bounds.width, height: max(1, bounds.maxY - hinge.maxY))
        let controls = deck(lower, false, PlayerViewportSafeAreaInsets(top: 0, bottom: insets.bottom, left: insets.left, right: insets.right), Double(bottomAspect))
        return result(inset(upper, PlayerViewportSafeAreaInsets(top: insets.top, bottom: 0, left: insets.left, right: insets.right), by: 4), controls.gameArea.insetBy(dx: 0, dy: 4), controls, expandUpper: true)
    }
    var best: (PlayerStageLayout, CGFloat)?
    for portrait in visible ? [true, false] : [bounds.height >= bounds.width] {
        let controls = deck(bounds, portrait, insets, 2.0 / 3.0)
        let area = controls.gameArea
        let h = max(1, (area.height - gap) / 2)
        let upper = CGRect(x: area.minX, y: area.minY, width: area.width, height: h)
        let lower = CGRect(x: area.minX, y: upper.maxY + gap, width: area.width, height: h)
        let layout = result(upper, lower, controls, compact: true)
        let score = layout.viewport.screens[0].frame.width * layout.viewport.screens[0].frame.height
        if best == nil || score > best!.1 { best = (layout, score) }
    }
    return best!.0
}

/// Nintendo DS screens are always stacked, top screen above the touch screen.
/// With a hinge across the middle (a tall unfolded display with the controls
/// hidden), each screen gets its own half so nothing is drawn across the fold.
private func resolveDualScreenStage(
    bounds: CGRect,
    insets: PlayerViewportSafeAreaInsets,
    fold: CGRect?,
    stage: PlayerStageContext,
    visible: Bool,
    deck: (CGRect, Bool?, PlayerViewportSafeAreaInsets, Double) -> PlayerControlDeckLayout
) -> PlayerStageLayout {
    let screenAspect: CGFloat = 4.0 / 3.0
    let minimumGap: CGFloat = 12
    let topSource = CGRect(x: 0, y: 0, width: 1, height: 0.5)
    let bottomSource = CGRect(x: 0, y: 0.5, width: 1, height: 0.5)

    func stacked(_ top: CGRect, _ bottom: CGRect, _ controlDeck: PlayerControlDeckLayout) -> PlayerStageLayout {
        PlayerStageLayout(
            viewport: PlayerViewportLayout(frame: top.union(bottom), isPortrait: controlDeck.isPortrait, screens: [
                PlayerScreenPlacement(source: topSource, frame: top),
                PlayerScreenPlacement(source: bottomSource, frame: bottom, isTouchScreen: true),
            ]),
            deck: controlDeck)
    }

    // The hinge across the middle: reported, or the midline of a tall unfolded
    // display when the system doesn't report it (Xcode 27.1 beta 1 reports none).
    let unfoldedPortrait = stage.isPhoneFormFactor && bounds.height > bounds.width && bounds.width >= 600
    let hinge = (stage.hasHorizontalFold ? fold : nil)
        ?? (unfoldedPortrait ? CGRect(x: bounds.minX, y: bounds.midY - minimumGap / 2, width: bounds.width, height: minimumGap) : nil)
    if let hinge {
        let upper = CGRect(x: bounds.minX, y: bounds.minY, width: bounds.width, height: max(1, hinge.minY - bounds.minY))
        let lower = CGRect(x: bounds.minX, y: hinge.maxY, width: bounds.width, height: max(1, bounds.maxY - hinge.maxY))
        // Rails beside the touch screen, in the lower half only.
        let controlDeck = deck(lower, false, PlayerViewportSafeAreaInsets(top: 0, bottom: insets.bottom, left: insets.left, right: insets.right), Double(screenAspect))
        let touchArea = controlDeck.gameArea.insetBy(dx: 0, dy: 4)
        let upperArea = inset(upper, PlayerViewportSafeAreaInsets(top: insets.top, bottom: 0, left: insets.left, right: insets.right), by: 4)
        let touchWidth = min(touchArea.width, touchArea.height * screenAspect)
        let topWidth = min(upperArea.width, upperArea.height * screenAspect, touchWidth * 1.3)
        let top = CGRect(x: upper.midX - topWidth / 2, y: upperArea.maxY - topWidth / screenAspect, width: topWidth, height: topWidth / screenAspect)
        let bottom = CGRect(x: touchArea.midX - touchWidth / 2, y: touchArea.minY, width: touchWidth, height: touchWidth / screenAspect)
        return stacked(top, bottom, controlDeck)
    }

    // Phone, outer display, or unfolded landscape: stacked in the game area,
    // with whichever control arrangement leaves the larger screens.
    var best: (layout: PlayerStageLayout, score: CGFloat)?
    for portraitDeck in visible ? [true, false] : [bounds.height >= bounds.width] {
        let controlDeck = deck(bounds, portraitDeck, insets, 2.0 / 3.0)
        let area = controlDeck.gameArea
        let width = min(area.width, (area.height - minimumGap) / 2 * screenAspect)
        guard width > 1 else { continue }
        let height = width / screenAspect
        let y = portraitDeck && visible ? area.minY : area.midY - minimumGap / 2 - height
        let top = CGRect(x: area.midX - width / 2, y: y, width: width, height: height)
        let bottom = CGRect(x: top.minX, y: top.maxY + minimumGap, width: width, height: height)
        var score = width * height
        if let prefers = stage.prefersPortrait, prefers == portraitDeck { score *= 1.05 }
        if best == nil || score > best!.score { best = (stacked(top, bottom, controlDeck), score) }
    }
    if let best { return best.layout }
    let fallback = deck(bounds, nil, insets, 2.0 / 3.0)
    return stacked(fallback.gameArea, fallback.gameArea, fallback)
}

private func inset(_ rect: CGRect, _ insets: PlayerViewportSafeAreaInsets, by margin: CGFloat) -> CGRect {
    CGRect(x: rect.minX + insets.left + margin, y: rect.minY + insets.top + margin,
           width: max(1, rect.width - insets.left - insets.right - margin * 2),
           height: max(1, rect.height - insets.top - insets.bottom - margin * 2))
}

private func fit(_ aspectRatio: Double, in area: CGRect, topAligned: Bool) -> CGRect {
    let aspect = CGFloat(max(aspectRatio, 0.1))
    let width = min(area.width, area.height * aspect)
    let height = width / aspect
    return CGRect(x: area.midX - width / 2, y: topAligned ? area.minY : area.midY - height / 2, width: width, height: height)
}

public func resolvePlayerViewportLayout(
    containerSize: CGSize,
    safeAreaInsets: PlayerViewportSafeAreaInsets,
    aspectRatio: Double?,
    topAlignedInPortrait: Bool,
    controlDeckHeight: Double = 200,
    controlScale: Double = 1,
    reserveTouchSpace: Bool? = nil,
    utilityCount: Int = 3,
    stage: PlayerStageContext = PlayerStageContext()
) -> PlayerViewportLayout {
    resolvePlayerStageLayout(containerSize: containerSize, safeAreaInsets: safeAreaInsets, aspectRatio: aspectRatio,
        topAlignedInPortrait: topAlignedInPortrait, controlDeckHeight: controlDeckHeight, controlScale: controlScale,
        reserveTouchSpace: reserveTouchSpace, utilityCount: utilityCount, stage: stage).viewport
}

public struct PlayerViewportSafeAreaInsets {
    public let top: CGFloat
    public let bottom: CGFloat
    public let left: CGFloat
    public let right: CGFloat

    /// How far the camera housing (Dynamic Island or notch) reaches in from the top edge:
    /// iOS reports no rectangle for it, and on every iPhone with one (a top inset of 44 pt
    /// or more, the status bar hidden) the safe area runs 11 pt past it. Without one, the
    /// inset itself (docs/player-controls.md, Geometry).
    public var cameraTop: CGFloat { Self.cameraReach(top) }
    /// The same from a side in landscape. iOS reports equal side insets, so both sides take
    /// the housing's reach and the game stays centred.
    public var cameraSide: CGFloat { Self.cameraReach(max(left, right)) }

    static func cameraReach(_ inset: CGFloat) -> CGFloat { inset >= 40 ? inset - 11 : inset }

    public init(top: CGFloat, bottom: CGFloat, left: CGFloat, right: CGFloat) {
        self.top = top
        self.bottom = bottom
        self.left = left
        self.right = right
    }
}
