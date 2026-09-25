package io.github.bandoracer.rommio.ui.screen.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.max
import kotlin.math.min

/** Safe-area insets of the player (dp): status bar or cutout, the gesture area, side cutouts. */
data class PlayerStageInsets(val top: Float = 0f, val bottom: Float = 0f, val left: Float = 0f, val right: Float = 0f)

/** Where the game goes and where every control is. */
data class PlayerStage(
    val viewport: Rect,
    val isPortrait: Boolean,
    val controls: PlayerTouchLayout,
)

/**
 * The player's stage (docs/player-controls.md, Geometry). With touch controls: [StandardStage].
 * Without (a controller took over, or the family has none): the game fills the safe area as
 * before, with Menu in its old place.
 */
fun resolvePlayerStage(
    width: Float,
    height: Float,
    insets: PlayerStageInsets,
    aspectRatio: Float?,
    plan: PlayerControlPlan?,
    controlScale: Float = 1f,
    mirrored: Boolean = false,
    reserveTouchSpace: Boolean = true,
    prefersPortrait: Boolean? = null,
): PlayerStage {
    if (plan != null && reserveTouchSpace) {
        return StandardStage(Rect(0f, 0f, width, height), insets, plan, controlScale, aspectRatio, prefersPortrait, mirrored).layout()
    }
    val deck = PlayerControlDeckLayout(width, height, insets.top, insets.bottom, max(insets.left, insets.right), visible = false, aspectRatio = aspectRatio, utilityCount = 1)
    val aspect = (aspectRatio ?: (width / max(height, 1f))).coerceAtLeast(0.1f)
    val areaWidth = (deck.gameRight - deck.gameLeft).coerceAtLeast(1f)
    val areaHeight = (deck.gameBottom - deck.gameTop).coerceAtLeast(1f)
    val gameWidth = min(areaWidth, areaHeight * aspect)
    val gameHeight = gameWidth / aspect
    val left = deck.gameLeft + (areaWidth - gameWidth) / 2
    val top = deck.gameTop + if (deck.portrait) 0f else (areaHeight - gameHeight) / 2
    val menu = PlayerTouchLayout.Empty.copy(menuCenter = Offset(deck.utilityX(0, 1), deck.utilityY))
    return PlayerStage(Rect(left, top, left + gameWidth, top + gameHeight), deck.portrait, menu)
}

/**
 * Touch controls v2 on a phone or tablet (docs/player-controls.md, Geometry). A port of iOS
 * `StandardStage`.
 *
 * - Portrait: the game at the top; 12 dp under it the title strip; the rest is the control band:
 *   the d-pad and face cluster at 55 % of its height, 20 dp from the screen edges, each side's
 *   shoulders 16 dp above its stack at the stack's outer edge (revised 2026-09-24), and
 *   Select · Menu · Start centred at the bottom, 12 dp above the gesture area.
 * - Landscape: the game centred between side rails; the clusters centred in their rails at 60 %
 *   of the height, shoulders at the top of each rail, Select and Start at their foot and Menu
 *   bottom centre (under the game when there is room, else beside Select). Nothing overlaps the
 *   game.
 *
 * The control size comes from the setting; it gives way (down to 0.8) only when the game would
 * otherwise lose too much of its size, and never below the size floors. Both rails are as wide as
 * the wider one, so the game stays centred and mirroring (left-handed) never moves it. Secondary
 * sticks give way (down to [PlayerControlMetrics.minimumSecondaryZone]) before the controls
 * shrink. Touches on the game go to the game ([PlayerTouchLayout.gameScreens]).
 */
internal class StandardStage(
    private val bounds: Rect,
    private val insets: PlayerStageInsets,
    private val plan: PlayerControlPlan,
    private val requestedScale: Float,
    private val aspectRatio: Float?,
    private val prefersPortrait: Boolean?,
    private val mirrored: Boolean,
) {
    private val safe: Rect
        get() = Rect(
            bounds.left + insets.left,
            bounds.top + insets.top,
            bounds.left + insets.left + max(1f, bounds.width - insets.left - insets.right),
            bounds.top + insets.top + max(1f, bounds.height - insets.top - insets.bottom),
        )

    fun layout(): PlayerStage {
        val ratio = bounds.width / max(bounds.height, 1f)
        if (ratio > 0.85f && ratio < 1.18f) {
            // Near square (a side pane, an unfolded book-style foldable): whichever shows the larger game.
            val portrait = portrait()
            val landscape = landscape()
            var portraitScore = portrait.viewport.width * portrait.viewport.height
            var landscapeScore = landscape.viewport.width * landscape.viewport.height
            if (prefersPortrait == true) portraitScore *= 1.15f
            if (prefersPortrait == false) landscapeScore *= 1.15f
            return if (portraitScore >= landscapeScore) portrait else landscape
        }
        return if (bounds.height >= bounds.width) portrait() else landscape()
    }

    private fun aspect(fallback: Size): Float = (aspectRatio ?: (fallback.width / max(fallback.height, 1f))).coerceAtLeast(0.1f)

    /**
     * The vertical stack on one side: what sits above the primary control's centre (half of it, a
     * secondary above it, the shoulders) and what sits below it.
     */
    private data class Stack(val up: Float, val down: Float)

    private fun stack(dpadSide: Boolean, metrics: PlayerControlMetrics, portrait: Boolean): Stack {
        val content = plan.rail(dpadSide, metrics)
        val shoulders = if (content.shoulderRow.height > 0f) content.shoulderRow.height + (if (portrait) metrics.shoulderLift else 10f) else 0f
        // A pair of sticks lines up under the taller cluster (`PlayerControlPlan.place`).
        val lower = if (plan.stickPair) {
            max(plan.rail(true, metrics).cluster.height, plan.rail(false, metrics).cluster.height)
        } else {
            content.cluster.height
        }
        return Stack(shoulders + content.aboveHeight(metrics) + content.cluster.height / 2, lower / 2 + content.belowHeight(metrics))
    }

    /**
     * Secondary sticks capped to the room a stage has ([room]: the height the stacks may still
     * take), down to their minimum.
     */
    private fun fitted(metrics: PlayerControlMetrics, room: (PlayerControlMetrics) -> Float): PlayerControlMetrics {
        if (!plan.hasSticks) return metrics
        val nominal = metrics.withSecondaryStickLimit(Float.POSITIVE_INFINITY)
        val spare = room(nominal)
        if (spare >= 0f) return nominal
        return nominal.withSecondaryStickLimit(nominal.stickZone(primary = false) + spare)
    }

    /** A primary control's centre, the highest it may go ([low]) and the lowest ([high]). */
    private data class Clamp(val y: Float, val low: Float, val high: Float)

    /**
     * Both primary controls at the same height when each side's clamps allow it: the lower of the
     * two, or (Android, for a tall stack such as the 3DS circle pad over its d-pad) the lowest
     * height both can take.
     */
    private fun level(dpad: Clamp, face: Clamp): Pair<Float, Float> {
        val shared = max(dpad.y, face.y)
        if (shared <= min(dpad.high, face.high)) return shared to shared
        val common = min(dpad.high, face.high)
        return if (common >= max(dpad.low, face.low)) common to common else dpad.y to face.y
    }

    private fun scaled(from: Float, shrink: (PlayerControlMetrics) -> Boolean): PlayerControlMetrics {
        var metrics = PlayerControlMetrics(from)
        while (metrics.scale > PlayerControlMetrics.MIN_SCALE + 0.001f && shrink(metrics)) {
            metrics = PlayerControlMetrics(metrics.scale - 0.02f)
        }
        return metrics
    }

    // ---- Portrait ----

    fun portrait(): PlayerStage {
        val safe = safe
        val top = safe.top + 8f
        val rowHeight = max(PlayerControlMetrics.MENU_DIAMETER, PlayerControlMetrics(requestedScale).pill.height)
        val rowY = safe.bottom - 12f - rowHeight / 2
        val rowTop = rowY - rowHeight / 2
        val gameWidth = safe.width - 24f
        val aspect = aspect(Size(gameWidth, gameWidth * 0.75f))
        val natural = gameWidth / aspect

        fun band(metrics: PlayerControlMetrics): Float = listOf(true, false).maxOf { side ->
            val stack = stack(side, metrics, portrait = true)
            stack.up + stack.down
        } + 12f

        var stripSpace = TITLE_STRIP_HEIGHT + TITLE_STRIP_GAP
        fun gameHeight(metrics: PlayerControlMetrics): Float =
            min(natural, rowTop - band(metrics) - 8f - stripSpace - top)

        // The two clusters side by side, 20 dp from the edges, never touching.
        fun fitsAcross(metrics: PlayerControlMetrics): Boolean =
            plan.rail(true, metrics).width + plan.rail(false, metrics).width + 16f <=
                bounds.width - max(EDGE, insets.left) - max(EDGE, insets.right)
        val metrics = scaled(requestedScale) { !fitsAcross(it) || gameHeight(it) < natural * PORTRAIT_GAME_FLOOR }
        // On a small screen with a tall game (a DS on a compact phone) the title strip gives way
        // to the game when the controls are already at their smallest.
        if (gameHeight(metrics) < natural * PORTRAIT_GAME_FLOOR) stripSpace = 0f
        val height = max(1f, gameHeight(metrics))
        val width = height * aspect
        val game = Rect(safe.center.x - width / 2, top, safe.center.x + width / 2, top + height)
        val strip = if (stripSpace > 0f) {
            Rect(safe.left + 16f, game.bottom + TITLE_STRIP_GAP, safe.right - 16f, game.bottom + TITLE_STRIP_GAP + TITLE_STRIP_HEIGHT)
        } else {
            null
        }
        val bandTop = (strip?.bottom ?: game.bottom) + 8f
        val ideal = bandTop + 0.55f * (safe.bottom - bandTop)
        fun clusterY(dpadSide: Boolean): Clamp {
            // The shoulders ride above the stack: it never rises so far that they leave the band.
            val stack = stack(dpadSide, metrics, portrait = true)
            val low = bandTop + stack.up
            val high = max(low, rowTop - 12f - stack.down)
            return Clamp(min(max(ideal, low), high), low, high)
        }
        val (dpadY, faceY) = level(clusterY(true), clusterY(false))
        val leftEdge = bounds.left + max(EDGE, insets.left)
        val rightEdge = bounds.right - max(EDGE, insets.right)
        val anchors = PlayerControlAnchors(
            leftRail = leftEdge..max(leftEdge, safe.center.x - 6f),
            rightRail = min(rightEdge, safe.center.x + 6f)..rightEdge,
            alignOuter = true,
            leftClusterY = if (mirrored) faceY else dpadY,
            rightClusterY = if (mirrored) dpadY else faceY,
            shoulders = PlayerControlAnchors.Shoulders.AboveCluster(PlayerControlMetrics(1f).shoulderLift),
            utility = PlayerControlAnchors.Utility.Row(midX = safe.center.x, y = rowY, maxWidth = rightEdge - leftEdge),
            titleStrip = strip,
        )
        return PlayerStage(game, isPortrait = true, controls = plan.place(anchors, metrics, mirrored).copy(gameScreens = listOf(game)))
    }

    // ---- Landscape ----

    fun landscape(): PlayerStage {
        val safe = safe
        val leftEdge = bounds.left + max(insets.left, 16f) + 4f
        val rightEdge = bounds.right - max(insets.right, 16f) - 4f
        val railGap = 10f
        val areaTop = safe.top + 8f
        val areaBottom = safe.bottom
        val available = areaBottom - areaTop
        val aspect = aspect(Size(rightEdge - leftEdge, available))
        val naturalHeight = min(available, (rightEdge - leftEdge) / aspect)

        fun railWidth(metrics: PlayerControlMetrics): Float = maxOf(
            plan.rail(true, metrics).width, plan.rail(false, metrics).width,
            plan.footWidth(leading = true, metrics), plan.footWidth(leading = false, metrics),
        )
        fun gameSize(metrics: PlayerControlMetrics): Size {
            val width = max(1f, rightEdge - leftEdge - 2 * (railWidth(metrics) + railGap))
            val fitted = min(width, available * aspect)
            return Size(fitted, fitted / aspect)
        }
        // Menu bottom centre when it fits under the game; the feet then hold pills only (none when
        // the utilities join Menu there, as Nintendo 64's Start does).
        val bareFeet = plan.utilities.isEmpty() || plan.utilitiesBesideMenu
        fun menuFitsUnder(metrics: PlayerControlMetrics): Boolean = available - gameSize(metrics).height >= metrics.menu + 16f
        fun footHeight(metrics: PlayerControlMetrics): Float =
            if (menuFitsUnder(metrics)) (if (bareFeet) 0f else metrics.pill.height) else max(metrics.pill.height, metrics.menu)
        // Height left over once both stacks and the feet are in (negative: too tall).
        fun room(metrics: PlayerControlMetrics): Float {
            val foot = footHeight(metrics)
            return available - listOf(true, false).maxOf { side ->
                val stack = stack(side, metrics, portrait = false)
                stack.up + stack.down + (if (foot > 0f) 10f + foot else 0f) + 4f
            }
        }
        // Secondary sticks give way (to their minimum) before the controls shrink.
        fun fits(metrics: PlayerControlMetrics): Boolean = room(fitted(metrics, ::room)) >= -0.5f
        val ideal = bounds.top + 0.6f * bounds.height
        val shoulderTop = areaTop
        // Each side's primary control: at 60 % of the height, between the shoulders and the feet.
        fun clamps(metrics: PlayerControlMetrics): Pair<Clamp, Clamp> {
            val footSize = footHeight(metrics)
            val footTop = areaBottom - 4f - footSize
            fun clusterY(dpadSide: Boolean): Clamp {
                val stack = stack(dpadSide, metrics, portrait = false)
                val low = shoulderTop + stack.up
                val high = max(low, footTop - (if (footSize > 0f) 10f else 0f) - stack.down)
                return Clamp(min(max(ideal, low), high), low, high)
            }
            return clusterY(true) to clusterY(false)
        }
        // A primary stick (N64, 3DS) stays level with the face cluster: its stack is tall, so the
        // controls give way a little (as on iOS's rail stages) until both can share a height.
        fun levels(metrics: PlayerControlMetrics): Boolean {
            if (!plan.leftStickPrimary) return true
            val (dpad, face) = clamps(fitted(metrics, ::room))
            return level(dpad, face).let { it.first == it.second }
        }
        val metrics = fitted(
            scaled(requestedScale) { !fits(it) || gameSize(it).height < naturalHeight * LANDSCAPE_GAME_FLOOR || !levels(it) },
            ::room,
        )
        val size = gameSize(metrics)
        val menu = metrics.menu
        val menuUnder = menuFitsUnder(metrics)
        val footSize = footHeight(metrics)
        val footY = areaBottom - 4f - footSize / 2
        val gameY = if (menuUnder) areaTop + max(0f, (available - menu - 16f - size.height) / 2) else areaTop + (available - size.height) / 2
        val midX = (leftEdge + rightEdge) / 2
        val game = Rect(midX - size.width / 2, gameY, midX + size.width / 2, gameY + size.height)
        val leftRail = leftEdge..max(leftEdge + 1f, game.left - railGap)
        val rightRail = min(rightEdge - 1f, game.right + railGap)..rightEdge
        val menuSpot: PlayerControlAnchors.MenuSpot = when {
            menuUnder -> PlayerControlAnchors.MenuSpot.Under(Offset(game.center.x, max(game.bottom + 8f + menu / 2, areaBottom - 4f - menu / 2)))
            plan.menuOnLeadingFoot -> PlayerControlAnchors.MenuSpot.LeadingFoot
            else -> PlayerControlAnchors.MenuSpot.TrailingFoot
        }
        val (dpadClamp, faceClamp) = clamps(metrics)
        val (dpadY, faceY) = level(dpadClamp, faceClamp)
        val anchors = PlayerControlAnchors(
            leftRail = leftRail,
            rightRail = rightRail,
            alignOuter = false,
            leftClusterY = if (mirrored) faceY else dpadY,
            rightClusterY = if (mirrored) dpadY else faceY,
            shoulders = PlayerControlAnchors.Shoulders.Top(shoulderTop),
            utility = PlayerControlAnchors.Utility.Feet(y = footY, menu = menuSpot),
            titleStrip = null,
        )
        return PlayerStage(game, isPortrait = false, controls = plan.place(anchors, metrics, mirrored).copy(gameScreens = listOf(game)))
    }

    companion object {
        const val TITLE_STRIP_HEIGHT = 44f
        const val TITLE_STRIP_GAP = 12f
        /** Portrait clusters sit this far from the screen edges. */
        const val EDGE = 20f
        /** Share of the game's natural height the controls may take before they shrink. */
        const val PORTRAIT_GAME_FLOOR = 0.8f
        const val LANDSCAPE_GAME_FLOOR = 0.7f
    }
}
