import CoreGraphics

extension PlayerControlPlan {
    /// The rail box for the rail-based stages (iPhone Duo, DS, 3DS), in points at scale 1
    /// (the stage multiplies by its scale): its width, height and the primary controls'
    /// centre (shoulders on top, then what sits above the primary controls, the primary
    /// controls level, then what sits below). `scale` measures it at that control size,
    /// where the size floors (faces, pills, Menu) make it relatively larger.
    public func railBox(scale: CGFloat = 1) -> (base: CGFloat, height: CGFloat, clusterCenter: CGFloat) {
        let metrics = PlayerControlMetrics(scale: scale)
        let unit = max(scale, 0.01)
        let dpadSide = rail(dpadSide: true, metrics), faceSide = rail(dpadSide: false, metrics)
        let shoulderRow = max(dpadSide.shoulderRow.height, faceSide.shoulderRow.height)
        let shoulderBlock = shoulderRow > 0 ? shoulderRow + metrics.shoulderLift : 0
        let above = max(dpadSide.aboveHeight(metrics), faceSide.aboveHeight(metrics))
        let cluster = max(dpadSide.cluster.height, faceSide.cluster.height)
        let below = max(dpadSide.belowHeight(metrics), faceSide.belowHeight(metrics))
        let base = max(dpadSide.width, faceSide.width, footWidth(leading: true, metrics), footWidth(leading: false, metrics))
        return (base / unit, (shoulderBlock + above + cluster + below + 6) / unit, (shoulderBlock + above + cluster / 2) / unit)
    }

    /// Utilities at the rails' feet: the leading rail takes the first half.
    var leadingUtilityCount: Int { (utilities.count + 1) / 2 }
    /// Menu joins the foot with fewer utilities (the leading one on a tie).
    var menuOnLeadingFoot: Bool { leadingUtilityCount <= utilities.count - leadingUtilityCount }

    /// A foot row at its narrowest (44 pt pills, 10 pt apart), with Menu where it goes.
    func footWidth(leading: Bool, _ metrics: PlayerControlMetrics) -> CGFloat {
        let count = leading ? leadingUtilityCount : utilities.count - leadingUtilityCount
        let menu = leading == menuOnLeadingFoot
        let pills = count == 0 ? 0 : CGFloat(count) * 44 + CGFloat(count - 1) * 10
        return pills + (menu ? metrics.menu + (count == 0 ? 0 : 10) : 0)
    }
}

/// Touch controls v2 on a phone or tablet (docs/player-controls.md, Geometry).
///
/// - Portrait: the game at the top, 8 pt under the camera housing: the display's full
///   width when that fits above the controls, otherwise inside a 12 pt border. The rest
///   is the control band: the shoulders in one level row 28 pt under the game, each
///   side's main shoulder a bumper flush against its screen edge; the d-pad and face
///   cluster at 55% of the band's height, 20 pt from the screen edges, below the
///   shoulders; Select · Menu · Start centred at the bottom, 12 pt above the home indicator.
/// - Landscape: the game centred between side rails (6 pt from the camera housing); the
///   display's full height when the rails leave room for it, otherwise inside the
///   border. The clusters centred in their rails at 60% of the height, shoulders at the
///   top of each rail, Select and Start at their foot and Menu bottom centre (under the
///   game when there is room, else beside Select). Nothing overlaps the game.
///
/// The control size comes from the setting; it gives way (down to 0.8) only when the
/// game would otherwise lose too much of its size, and never below the size floors.
/// Both rails are as wide as the wider one, so the game stays centred and mirroring
/// (left-handed) never moves it.
struct StandardStage {
    /// Portrait clusters sit this far from the screen edges.
    static let edge: CGFloat = 20
    /// The game's distance from the camera housing (portrait), and the controls' (landscape).
    static let cameraGap: CGFloat = 8
    static let cameraSideGap: CGFloat = 6
    /// A game smaller than the display's width or height keeps this from its edges.
    static let border: CGFloat = 12
    /// Share of the game's natural height the controls may take before they shrink.
    static let portraitGameFloor: CGFloat = 0.8
    static let landscapeGameFloor: CGFloat = 0.7

    let bounds: CGRect
    let insets: PlayerViewportSafeAreaInsets
    let plan: PlayerControlPlan
    let requestedScale: CGFloat
    let aspectRatio: Double?
    let prefersPortrait: Bool?
    let mirrored: Bool

    private var safe: CGRect {
        CGRect(x: bounds.minX + insets.left, y: bounds.minY + insets.top,
               width: max(1, bounds.width - insets.left - insets.right),
               height: max(1, bounds.height - insets.top - insets.bottom))
    }

    func layout() -> PlayerStageLayout {
        let ratio = bounds.width / max(bounds.height, 1)
        if ratio > 0.85 && ratio < 1.18 {
            // Near square (a side pane beside the game): whichever shows the larger game.
            let portrait = self.portrait(), landscape = self.landscape()
            var portraitScore = portrait.viewport.frame.width * portrait.viewport.frame.height
            var landscapeScore = landscape.viewport.frame.width * landscape.viewport.frame.height
            if prefersPortrait == true { portraitScore *= 1.15 }
            if prefersPortrait == false { landscapeScore *= 1.15 }
            return portraitScore >= landscapeScore ? portrait : landscape
        }
        return bounds.height >= bounds.width ? portrait() : landscape()
    }

    private func aspect(_ fallback: CGSize) -> CGFloat {
        CGFloat(max(aspectRatio ?? Double(fallback.width / max(fallback.height, 1)), 0.1))
    }

    /// The vertical stack on one side: what sits above the primary control's centre
    /// (half of it, a secondary above it, the shoulders) and what sits below it.
    private func stack(dpadSide: Bool, _ metrics: PlayerControlMetrics, portrait: Bool, stackedShoulders: Bool = false) -> (up: CGFloat, down: CGFloat) {
        let content = plan.rail(dpadSide: dpadSide, metrics)
        // Portrait shoulders are the corner row above both stacks (`shoulderBlock`); on a
        // landscape rail they top the stack, one row or (stacked) a column.
        let shoulders = content.shoulderRow.height > 0 && !portrait
            ? plan.shoulderBlockHeight(rowHeight: content.shoulderRow.height, stacked: stackedShoulders) + 10 : 0
        // A pair of sticks lines up under the taller cluster (`PlayerControlPlan.place`).
        let lower = plan.stickPair
            ? max(plan.rail(dpadSide: true, metrics).cluster.height, plan.rail(dpadSide: false, metrics).cluster.height)
            : content.cluster.height
        return (shoulders + content.aboveHeight(metrics) + content.cluster.height / 2, lower / 2 + content.belowHeight(metrics))
    }

    /// Secondary sticks capped to the room a stage has (`room(metrics)`: the height the
    /// stacks may still take), down to their minimum.
    private func fitted(_ metrics: PlayerControlMetrics, room: (PlayerControlMetrics) -> CGFloat) -> PlayerControlMetrics {
        guard plan.hasSticks else { return metrics }
        var nominal = metrics
        nominal.secondaryStickLimit = .infinity
        let spare = room(nominal)
        guard spare < 0 else { return nominal }
        var capped = nominal
        capped.secondaryStickLimit = nominal.stickZone(primary: false) + spare
        return capped
    }

    /// Both primary controls at the same height when each side's clamps allow it.
    private func level(_ dpad: (y: CGFloat, high: CGFloat), _ face: (y: CGFloat, high: CGFloat)) -> (dpad: CGFloat, face: CGFloat) {
        let shared = max(dpad.y, face.y)
        return shared <= min(dpad.high, face.high) ? (shared, shared) : (dpad.y, face.y)
    }

    private func scaled(from requested: CGFloat, while shrink: (PlayerControlMetrics) -> Bool) -> PlayerControlMetrics {
        var metrics = PlayerControlMetrics(scale: requested)
        while metrics.scale > PlayerControlMetrics.scaleRange.lowerBound + 0.001 && shrink(metrics) {
            metrics = PlayerControlMetrics(scale: metrics.scale - 0.02)
        }
        return metrics
    }

    // MARK: Portrait

    func portrait() -> PlayerStageLayout {
        let safe = self.safe
        let top = bounds.minY + insets.cameraTop + Self.cameraGap
        let rowHeight = max(PlayerControlMetrics.menuDiameter, PlayerControlMetrics(scale: requestedScale).pill.height)
        let rowY = safe.maxY - 12 - rowHeight / 2
        let rowTop = rowY - rowHeight / 2
        let aspect = self.aspect(CGSize(width: safe.width, height: safe.width * 0.75))
        // Full width, edge to edge.
        let natural = safe.width / aspect

        /// The corner row of shoulders: 16 pt into the band (28 pt under the game), the row
        /// (44 pt), and 16 pt down to the stacks. On a squeezed band (the controls already at
        /// their smallest and the game still short of its floor) it gives way: at the band's
        /// top (12 pt under the game), at its natural height, 12 pt above the stacks.
        /// PlayStation's numbered shoulders stack (1 above 2) when that costs the game and the
        /// controls nothing; otherwise they share the row.
        var compactShoulders = false
        var stackShoulders = plan.numberedShoulders
        func shoulderBlock(_ metrics: PlayerControlMetrics) -> CGFloat {
            guard plan.hasShoulders else { return 0 }
            if compactShoulders { return plan.cornerRowHeight(metrics, minimum: 0) + 12 }
            return 16 + plan.shoulderBlockHeight(rowHeight: plan.cornerRowHeight(metrics), stacked: stackShoulders) + 16
        }
        func band(_ metrics: PlayerControlMetrics) -> CGFloat {
            shoulderBlock(metrics) + [true, false].map { side -> CGFloat in
                let stack = self.stack(dpadSide: side, metrics, portrait: true)
                return stack.up + stack.down
            }.max()! + 12
        }
        /// The game's frame: the full width when it fits above the controls (12 pt over the
        /// band), otherwise as large as fits inside the border.
        func gameFrame(_ metrics: PlayerControlMetrics) -> CGRect {
            let room = rowTop - band(metrics) - 12 - top
            if natural <= room { return CGRect(x: safe.minX, y: top, width: safe.width, height: natural) }
            let height = max(1, min((safe.width - 2 * Self.border) / aspect, room))
            return CGRect(x: safe.midX - height * aspect / 2, y: top, width: height * aspect, height: height)
        }
        // The two clusters side by side, 20 pt from the edges, never touching.
        func fitsAcross(_ metrics: PlayerControlMetrics) -> Bool {
            let dpad = plan.rail(dpadSide: true, metrics), face = plan.rail(dpadSide: false, metrics)
            return dpad.width + face.width + 16 <= bounds.width - max(Self.edge, insets.left) - max(Self.edge, insets.right)
        }
        func fittedMetrics() -> PlayerControlMetrics {
            scaled(from: requestedScale) { !fitsAcross($0) || gameFrame($0).height < natural * Self.portraitGameFloor }
        }
        var metrics = fittedMetrics()
        if stackShoulders, gameFrame(metrics).height < natural - 0.5 || metrics.scale < requestedScale - 0.001 {
            stackShoulders = false
            metrics = fittedMetrics()
        }
        if plan.hasShoulders && gameFrame(metrics).height < natural * Self.portraitGameFloor { compactShoulders = true }
        let game = gameFrame(metrics)
        let bandTop = game.maxY + 12
        let ideal = bandTop + 0.55 * (safe.maxY - bandTop)
        func clusterY(dpadSide: Bool) -> (y: CGFloat, high: CGFloat) {
            let stack = self.stack(dpadSide: dpadSide, metrics, portrait: true)
            let low = bandTop + shoulderBlock(metrics) + stack.up
            let high = max(low, rowTop - 12 - stack.down)
            return (min(max(ideal, low), high), high)
        }
        let (dpadY, faceY) = level(clusterY(dpadSide: true), clusterY(dpadSide: false))
        let leftEdge = bounds.minX + max(Self.edge, insets.left)
        let rightEdge = bounds.maxX - max(Self.edge, insets.right)
        let anchors = PlayerControlAnchors(
            leftRail: leftEdge ... max(leftEdge, safe.midX - 6),
            rightRail: min(rightEdge, safe.midX + 6) ... rightEdge,
            alignOuter: true,
            leftClusterY: mirrored ? faceY : dpadY,
            rightClusterY: mirrored ? dpadY : faceY,
            shoulders: .corners(top: bandTop + (compactShoulders ? 0 : 16), leftEdge: bounds.minX + insets.left, rightEdge: bounds.maxX - insets.right,
                                minHeight: compactShoulders ? 0 : 44, stacked: stackShoulders),
            utility: .row(midX: safe.midX, y: rowY, maxWidth: rightEdge - leftEdge))
        let controls = plan.place(anchors, metrics: metrics, mirrored: mirrored)
        let deck = PlayerControlDeckLayout(
            scale: metrics.scale, railTop: bandTop, railWidth: max(1, safe.midX - 6 - leftEdge), leftOrigin: leftEdge,
            rightOrigin: safe.midX + 6, utilityY: rowY, gameArea: game, isPortrait: true, utilityRowMidX: safe.midX)
        return PlayerStageLayout(viewport: PlayerViewportLayout(frame: game, isPortrait: true), deck: deck, controls: controls)
    }

    // MARK: Landscape

    func landscape() -> PlayerStageLayout {
        let safe = self.safe
        let side = max(insets.cameraSide + Self.cameraSideGap, 20)
        let leftEdge = bounds.minX + side
        let rightEdge = bounds.maxX - side
        let railGap: CGFloat = 10
        let areaTop = safe.minY + 8
        let areaBottom = safe.maxY
        let available = areaBottom - areaTop
        let aspect = self.aspect(CGSize(width: rightEdge - leftEdge, height: available))
        let naturalHeight = min(available, (rightEdge - leftEdge) / aspect)

        func railWidth(_ metrics: PlayerControlMetrics) -> CGFloat {
            max(plan.rail(dpadSide: true, metrics).width, plan.rail(dpadSide: false, metrics).width,
                plan.footWidth(leading: true, metrics), plan.footWidth(leading: false, metrics))
        }
        /// The game between the rails: the display's full height when the rails leave room
        /// for it, otherwise inside the border.
        func gameSize(_ metrics: PlayerControlMetrics) -> CGSize {
            let width = max(1, rightEdge - leftEdge - 2 * (railWidth(metrics) + railGap))
            if width >= bounds.height * aspect { return CGSize(width: bounds.height * aspect, height: bounds.height) }
            let fitted = min(width, available * aspect)
            return CGSize(width: fitted, height: fitted / aspect)
        }
        // Menu bottom centre when it fits under the game; the feet then hold pills only
        // (none when the utilities join Menu there, as Nintendo 64's Start does).
        let bareFeet = plan.utilities.isEmpty || plan.utilitiesBesideMenu
        func menuFitsUnder(_ metrics: PlayerControlMetrics) -> Bool { available - gameSize(metrics).height >= metrics.menu + 16 }
        func footHeight(_ metrics: PlayerControlMetrics) -> CGFloat {
            menuFitsUnder(metrics) ? (bareFeet ? 0 : metrics.pill.height) : max(metrics.pill.height, metrics.menu)
        }
        /// PlayStation's numbered shoulders stack (1 above 2) at the top of the rails when the
        /// stacks still fit at the requested size with full-size sticks; otherwise one row.
        var stackShoulders = plan.numberedShoulders
        /// Height left over once both stacks and the feet are in (negative: too tall).
        func room(_ metrics: PlayerControlMetrics) -> CGFloat {
            let foot = footHeight(metrics)
            return available - [true, false].map { side -> CGFloat in
                let stack = self.stack(dpadSide: side, metrics, portrait: false, stackedShoulders: stackShoulders)
                return stack.up + stack.down + (foot > 0 ? 10 + foot : 0) + 4
            }.max()!
        }
        // Secondary sticks give way (to their minimum) before the controls shrink.
        func fits(_ metrics: PlayerControlMetrics) -> Bool { room(fitted(metrics, room: room)) >= -0.5 }
        if stackShoulders {
            var nominal = PlayerControlMetrics(scale: requestedScale)
            nominal.secondaryStickLimit = .infinity
            if room(nominal) < -0.5 { stackShoulders = false }
        }
        let metrics = fitted(scaled(from: requestedScale) { !fits($0) || gameSize($0).height < naturalHeight * Self.landscapeGameFloor }, room: room)
        let size = gameSize(metrics)
        let menu = metrics.menu
        let menuUnder = menuFitsUnder(metrics)
        let footSize = footHeight(metrics)
        let footY = areaBottom - 4 - footSize / 2
        let gameY: CGFloat
        if size.height >= bounds.height - 0.5 {
            gameY = bounds.minY
        } else {
            gameY = menuUnder ? areaTop + max(0, (available - menu - 16 - size.height) / 2) : areaTop + (available - size.height) / 2
        }
        let game = CGRect(x: (leftEdge + rightEdge) / 2 - size.width / 2, y: gameY, width: size.width, height: size.height)
        let leftRail = leftEdge ... max(leftEdge + 1, game.minX - railGap)
        let rightRail = min(rightEdge - 1, game.maxX + railGap) ... rightEdge
        let menuSpot: PlayerControlAnchors.MenuSpot
        if menuUnder {
            menuSpot = .under(CGPoint(x: game.midX, y: max(game.maxY + 8 + menu / 2, areaBottom - 4 - menu / 2)))
        } else {
            menuSpot = plan.menuOnLeadingFoot ? .leadingFoot : .trailingFoot
        }
        let ideal = bounds.minY + 0.6 * bounds.height
        let shoulderTop = areaTop
        let footTop = footY - footSize / 2
        func clusterY(dpadSide: Bool) -> (y: CGFloat, high: CGFloat) {
            let stack = self.stack(dpadSide: dpadSide, metrics, portrait: false, stackedShoulders: stackShoulders)
            let low = shoulderTop + stack.up
            let high = max(low, footTop - (footSize > 0 ? 10 : 0) - stack.down)
            return (min(max(ideal, low), high), high)
        }
        let (dpadY, faceY) = level(clusterY(dpadSide: true), clusterY(dpadSide: false))
        let anchors = PlayerControlAnchors(
            leftRail: leftRail, rightRail: rightRail, alignOuter: false,
            leftClusterY: mirrored ? faceY : dpadY, rightClusterY: mirrored ? dpadY : faceY,
            shoulders: .top(shoulderTop, stacked: stackShoulders), utility: .feet(y: footY, menu: menuSpot))
        let controls = plan.place(anchors, metrics: metrics, mirrored: mirrored)
        let deck = PlayerControlDeckLayout(
            scale: metrics.scale, railTop: shoulderTop, railWidth: leftRail.upperBound - leftRail.lowerBound,
            leftOrigin: leftRail.lowerBound, rightOrigin: rightRail.lowerBound,
            utilityY: footY, gameArea: game, isPortrait: false, utilityRowMidX: nil)
        return PlayerStageLayout(viewport: PlayerViewportLayout(frame: game, isPortrait: false), deck: deck, controls: controls)
    }
}
