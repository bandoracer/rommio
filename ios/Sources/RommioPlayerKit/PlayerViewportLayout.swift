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
    /// Pad mode puts the game on the trailing half instead of the leading half.
    public var leftHanded: Bool
    /// The player asked for game and controls on separate halves of a wide
    /// display even while it is flat.
    public var prefersSplitPad: Bool

    public init(fold: CGRect? = nil, foldIsActive: Bool = false, dualScreen: Bool = false, threeDS: Bool = false, isPhoneFormFactor: Bool = false, prefersPortrait: Bool? = nil,
                trailingPaneWidth: CGFloat = 0, leftHanded: Bool = false, prefersSplitPad: Bool = false) {
        self.fold = fold
        self.foldIsActive = foldIsActive
        self.dualScreen = dualScreen
        self.threeDS = threeDS
        self.isPhoneFormFactor = isPhoneFormFactor
        self.prefersPortrait = prefersPortrait
        self.trailingPaneWidth = trailingPaneWidth
        self.leftHanded = leftHanded
        self.prefersSplitPad = prefersSplitPad
    }

    /// The fold splits the window into leading and trailing halves.
    var hasVerticalFold: Bool { fold.map { $0.height >= $0.width } ?? false }
    /// The fold splits the window into top and bottom halves (tent or DS-style hold).
    var hasHorizontalFold: Bool { fold.map { $0.width > $0.height } ?? false }
}

/// Video and input use this same geometry. The rails never sit over the game.
public struct PlayerControlDeckLayout {
    public let scale: CGFloat
    public let railTop: CGFloat
    public let railWidth: CGFloat
    public let leftOrigin: CGFloat
    public let rightOrigin: CGFloat
    public let utilityY: CGFloat
    public let gameArea: CGRect
    public let isPortrait: Bool

    private let midX: CGFloat

    public init(size: CGSize, insets: PlayerViewportSafeAreaInsets, deckHeight: Double, scale requestedScale: Double = 1,
                visible: Bool = true, aspectRatio: Double? = nil, utilityCount: Int = 3) {
        self.init(bounds: CGRect(origin: .zero, size: size), insets: insets, deckHeight: deckHeight, scale: requestedScale,
                  visible: visible, aspectRatio: aspectRatio, utilityCount: utilityCount, portrait: nil)
    }

    /// Lays the deck out inside `bounds`. `portrait` forces a bottom deck (true)
    /// or side rails (false); nil derives it from the bounds' shape.
    public init(bounds: CGRect, insets: PlayerViewportSafeAreaInsets, deckHeight: Double, scale requestedScale: Double = 1,
                visible: Bool = true, aspectRatio: Double? = nil, utilityCount: Int = 3, portrait forcedPortrait: Bool?) {
        let size = bounds.size
        let portrait = forcedPortrait ?? (size.height >= size.width)
        isPortrait = portrait
        midX = bounds.midX
        let height = CGFloat(max(1, deckHeight))
        let safeWidth = max(1, size.width - insets.left - insets.right)
        let safeHeight = max(1, size.height - insets.top - insets.bottom)
        let availableHeight = max(1, safeHeight - 72)
        let requested = min(max(requestedScale, 0.92), 1.3)
        var fittedScale = min(requested, (safeWidth - 16) / 296, availableHeight / height)
        if !portrait, visible, requested <= 1, let aspectRatio {
            // First fit the video at full height, then use its existing side bars.
            // Default controls can compact to 44pt targets before reducing video.
            let naturalWidth = min(safeWidth, safeHeight * max(0.1, aspectRatio))
            let sideRoom = (safeWidth - naturalWidth) / 2 - 4
            fittedScale = min(fittedScale, max(0.92, sideRoom / 148))
        }
        scale = fittedScale
        let utilityWidth = CGFloat(max(0, (utilityCount + 1) / 2 - 1)) * 50 + 48
        railWidth = portrait ? 148 * scale : max(148 * scale, utilityWidth)
        leftOrigin = bounds.minX + insets.left + (portrait ? 8 : 0)
        rightOrigin = bounds.minX + size.width - insets.right - (portrait ? 8 : 0) - railWidth
        utilityY = bounds.minY + size.height - insets.bottom - 28
        railTop = bounds.minY + (portrait ? size.height - insets.bottom - 56 - height * scale : insets.top + 8 + (availableHeight - height * scale) / 2)
        let x = bounds.minX + (portrait ? insets.left + 12 : insets.left + (visible ? railWidth + 4 : 52))
        let right = bounds.minX + (portrait ? size.width - insets.right - 12 : size.width - insets.right - (visible ? railWidth + 4 : 52))
        let top = bounds.minY + insets.top + (portrait ? 8 : 0)
        // Landscape utilities live below the side controls, not below the game.
        let bottom = bounds.minY + (portrait ? (visible ? railTop - bounds.minY - 12 : size.height - insets.bottom - 56) : size.height - insets.bottom)
        gameArea = CGRect(x: x, y: top, width: max(1, right - x), height: max(1, bottom - top))
    }

    public func utilityX(index: Int, count: Int, width: CGFloat) -> CGFloat {
        if isPortrait { return midX + (CGFloat(index) - CGFloat(count - 1) / 2) * 52 }
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
    /// Game and controls occupy opposite halves of a folded display.
    public let isPadMode: Bool

    public var screens: [PlayerScreenPlacement] { viewport.screens }
    public var touchScreen: PlayerScreenPlacement? { screens.first(where: \.isTouchScreen) }
}

public func resolvePlayerStageLayout(
    containerSize: CGSize,
    safeAreaInsets: PlayerViewportSafeAreaInsets,
    aspectRatio: Double?,
    topAlignedInPortrait: Bool,
    controlDeckHeight: Double = 200,
    controlScale: Double = 1,
    reserveTouchSpace: Bool? = nil,
    utilityCount: Int = 3,
    stage: PlayerStageContext = PlayerStageContext()
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

    func deck(_ rect: CGRect, portrait: Bool?, insets: PlayerViewportSafeAreaInsets, aspect: Double? = nil) -> PlayerControlDeckLayout {
        PlayerControlDeckLayout(bounds: rect, insets: insets, deckHeight: controlDeckHeight, scale: controlScale,
                                visible: visible, aspectRatio: aspect ?? aspectRatio, utilityCount: utilityCount, portrait: portrait)
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

    // Single screen on an actively folded display: never straddle the hinge.
    // A wide flat display can split the same way when the player asks for it,
    // using the hinge line (or the centre when the hinge isn't reported).
    let splitLine = stage.prefersSplitPad && visible && bounds.width > bounds.height * 1.2
        ? (fold.flatMap { stage.hasVerticalFold ? $0 : nil } ?? CGRect(x: bounds.midX - 6, y: bounds.minY, width: 12, height: bounds.height))
        : nil
    if let fold = splitLine ?? (stage.foldIsActive ? fold : nil), visible {
        if splitLine != nil || stage.hasVerticalFold {
            // Book pose: game on one half, a full-size pad on the other.
            let leading = CGRect(x: bounds.minX, y: bounds.minY, width: max(1, fold.minX - bounds.minX), height: bounds.height)
            let trailing = CGRect(x: fold.maxX, y: bounds.minY, width: max(1, bounds.maxX - fold.maxX), height: bounds.height)
            let gameHalf = stage.leftHanded ? trailing : leading
            let padHalf = stage.leftHanded ? leading : trailing
            let gameInsets = PlayerViewportSafeAreaInsets(top: insets.top, bottom: insets.bottom,
                                                          left: stage.leftHanded ? 0 : insets.left, right: stage.leftHanded ? insets.right : 0)
            let padInsets = PlayerViewportSafeAreaInsets(top: insets.top, bottom: insets.bottom,
                                                         left: stage.leftHanded ? insets.left : 0, right: stage.leftHanded ? 0 : insets.right)
            let frame = fit(aspectRatio ?? Double(gameHalf.width / max(gameHalf.height, 1)), in: inset(gameHalf, gameInsets, by: 8), topAligned: false)
            return PlayerStageLayout(viewport: PlayerViewportLayout(frame: frame, isPortrait: false),
                                     deck: deck(padHalf, portrait: true, insets: padInsets), isPadMode: true)
        }
        if splitLine == nil, stage.hasHorizontalFold {
            // Tent or laptop pose: watch on the top half, touch the bottom half.
            let top = CGRect(x: bounds.minX, y: bounds.minY, width: bounds.width, height: max(1, fold.minY - bounds.minY))
            let bottom = CGRect(x: bounds.minX, y: fold.maxY, width: bounds.width, height: max(1, bounds.maxY - fold.maxY))
            let topInsets = PlayerViewportSafeAreaInsets(top: insets.top, bottom: 0, left: insets.left, right: insets.right)
            let bottomInsets = PlayerViewportSafeAreaInsets(top: 0, bottom: insets.bottom, left: insets.left, right: insets.right)
            let frame = fit(aspectRatio ?? Double(top.width / max(top.height, 1)), in: inset(top, topInsets, by: 8), topAligned: false)
            return PlayerStageLayout(viewport: PlayerViewportLayout(frame: frame, isPortrait: false),
                                     deck: deck(bottom, portrait: true, insets: bottomInsets), isPadMode: true)
        }
    }

    // Standard layout. Near-square windows (iPhone Duo rotated, Split View) pick
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
        let frame = CGRect(x: area.midX - width / 2,
                           y: controlDeck.isPortrait && topAlignedInPortrait ? area.minY : area.midY - height / 2,
                           width: width, height: height)
        var score = width * height
        if let prefers = stage.prefersPortrait, let portrait, prefers == portrait { score *= 1.15 }
        let layout = PlayerStageLayout(viewport: PlayerViewportLayout(frame: frame, isPortrait: controlDeck.isPortrait),
                                       deck: controlDeck, isPadMode: false)
        if best == nil || score > best!.score { best = (layout, score) }
    }
    return best!.layout
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
    func result(_ upper: CGRect, _ lower: CGRect, _ controls: PlayerControlDeckLayout, pad: Bool = false, compact: Bool = false, expandUpper: Bool = false) -> PlayerStageLayout {
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
        ]), deck: controls, isPadMode: pad)
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
    if let fold, stage.foldIsActive, stage.hasVerticalFold, visible {
        let leading = CGRect(x: bounds.minX, y: bounds.minY, width: max(1, fold.minX - bounds.minX), height: bounds.height)
        let trailing = CGRect(x: fold.maxX, y: bounds.minY, width: max(1, bounds.maxX - fold.maxX), height: bounds.height)
        let gameHalf = stage.leftHanded ? trailing : leading
        let controlsHalf = stage.leftHanded ? leading : trailing
        let game = inset(gameHalf, insets, by: 8)
        let upper = CGRect(x: game.minX, y: game.minY, width: game.width, height: max(1, (game.height - gap) / 2))
        let lower = CGRect(x: game.minX, y: upper.maxY + gap, width: game.width, height: upper.height)
        return result(upper, lower, deck(controlsHalf, true, insets, 2.0 / 3.0), pad: true)
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
/// With iPhone Duo unfolded and held in portrait, the hinge runs across the
/// middle like a DS: each screen gets its own half and the controls sit beside
/// the touch screen, so nothing is drawn across the fold.
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

    func stacked(_ top: CGRect, _ bottom: CGRect, _ controlDeck: PlayerControlDeckLayout, pad: Bool = false) -> PlayerStageLayout {
        PlayerStageLayout(
            viewport: PlayerViewportLayout(frame: top.union(bottom), isPortrait: controlDeck.isPortrait, screens: [
                PlayerScreenPlacement(source: topSource, frame: top),
                PlayerScreenPlacement(source: bottomSource, frame: bottom, isTouchScreen: true),
            ]),
            deck: controlDeck, isPadMode: pad)
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

    // Folded like a book: the stacked pair takes one half, controls the other.
    if let fold, stage.foldIsActive, stage.hasVerticalFold, visible {
        let leading = CGRect(x: bounds.minX, y: bounds.minY, width: max(1, fold.minX - bounds.minX), height: bounds.height)
        let trailing = CGRect(x: fold.maxX, y: bounds.minY, width: max(1, bounds.maxX - fold.maxX), height: bounds.height)
        let screensHalf = stage.leftHanded ? trailing : leading
        let padHalf = stage.leftHanded ? leading : trailing
        let area = inset(screensHalf, PlayerViewportSafeAreaInsets(top: insets.top, bottom: insets.bottom, left: stage.leftHanded ? 0 : insets.left, right: stage.leftHanded ? insets.right : 0), by: 8)
        let width = min(area.width, (area.height - minimumGap) / 2 * screenAspect)
        let height = width / screenAspect
        let top = CGRect(x: area.midX - width / 2, y: area.midY - minimumGap / 2 - height, width: width, height: height)
        let bottom = CGRect(x: area.midX - width / 2, y: area.midY + minimumGap / 2, width: width, height: height)
        let padInsets = PlayerViewportSafeAreaInsets(top: insets.top, bottom: insets.bottom, left: stage.leftHanded ? insets.left : 0, right: stage.leftHanded ? 0 : insets.right)
        return stacked(top, bottom, deck(padHalf, true, padInsets, 2.0 / 3.0), pad: true)
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

    public init(top: CGFloat, bottom: CGFloat, left: CGFloat, right: CGFloat) {
        self.top = top
        self.bottom = bottom
        self.left = left
        self.right = right
    }
}
