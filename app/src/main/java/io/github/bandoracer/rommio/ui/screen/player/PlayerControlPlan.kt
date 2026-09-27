package io.github.bandoracer.rommio.ui.screen.player

import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import io.github.bandoracer.rommio.domain.input.ExpansionControl
import io.github.bandoracer.rommio.domain.input.ExpansionControls
import io.github.bandoracer.rommio.domain.input.PlayerStickTuning
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Touch controls v2 (docs/player-controls.md): what each family's controls are, how big they are
 * at a control size, and where they go on a stage, with each console's layout ("Analog sticks and
 * per-console layouts"). Pure (points are dp): the renderer, the touch surface and the unit tests
 * all place controls with these functions. Names follow the iOS `PlayerControlPlan`.
 */

/** What a touch control is, for the hit test and for drawing. */
enum class PlayerTouchKind {
    DPAD, FACE, SHOULDER, SYSTEM, STICK;

    /** A touch that began on one of these follows the thumb to others of the same kind. */
    val rolls: Boolean get() = this == FACE || this == SHOULDER || this == SYSTEM
}

/** How a family's face buttons are arranged (docs/player-controls.md, Geometry). */
enum class PlayerFaceArrangement {
    /** Two circles on a 25° rise, B lower-left and A upper-right (NES, Game Boy…). */
    TWO_BUTTON,
    /** Four circles around a centre (SNES, PlayStation…). */
    DIAMOND,
    /** Two rows of three (Genesis six-button). */
    SIX_BUTTON,
    /** Nintendo 64: A with B down-left, the C buttons as a small diamond above-right of A. */
    N64,
    /** The family's own arrangement, scaled to the size floor. */
    CUSTOM,
    NONE,
}

/** The drawn outline of a control. */
enum class PlayerTouchShape {
    CIRCLE, CAPSULE, TAB,
    /** A shoulder flush against the screen's left (leading) or right edge: flat on that side (portrait corners). */
    BUMPER_LEADING, BUMPER_TRAILING,
    /** An analog stick's square zone: touches anywhere in it take the stick. */
    ZONE,
}

/** The d-pad's four keys, in the order the core's key codes sort (up, down, left, right). */
val PlayerDirectionKeys: List<Int> = listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)

/** One control of a family, before placement. */
data class PlayerControlSpec(
    val id: String,
    val label: String,
    val kind: PlayerTouchKind,
    /** Digital keys it holds (one, or a combo); empty for sticks and axis buttons. */
    val keys: List<Int>,
    val stick: Int?,
    /** Axis buttons (N64 C buttons) push the right stick by this much. */
    val axisX: Float,
    val axisY: Float,
    /** On the d-pad's rail (left unless mirrored); face buttons are on the other. */
    val left: Boolean,
    /** The family's own rail coordinates (docs/player-controls.json), for arrangement. */
    val railX: Float,
    val railY: Float,
    val railSize: Float,
) {
    val isAxisButton: Boolean get() = keys.isEmpty() && stick == null && (axisX != 0f || axisY != 0f)
}

/** A face button of a cluster: its offset from the cluster centre and its diameter. */
data class PlayerFaceButton(val spec: PlayerControlSpec, val offset: Offset, val diameter: Float)

/**
 * Sizes at one control size (docs/player-controls.md, Geometry). The setting scales everything
 * between 0.8 and 1.3; no face button is drawn under 50 dp and no hit region is smaller than 58.
 */
class PlayerControlMetrics(
    requested: Float,
    /** A stage short of room may cap secondary stick zones (never below [minimumSecondaryZone]) before the controls shrink further. */
    val secondaryStickLimit: Float = Float.POSITIVE_INFINITY,
) {
    val scale: Float = requested.coerceIn(MIN_SCALE, MAX_SCALE)

    val dpad: Float get() = 150f * scale
    /** The d-pad beside a primary stick (N64, 3DS). */
    val smallDpad: Float get() = 104f * scale
    /** Two-button faces: 64 dp circles, centres 76 dp apart on a 25° rise. */
    val twoButtonDiameter: Float get() = max(MINIMUM_FACE, 64f * scale)
    val twoButtonSpacing: Float get() = 76f * max(scale, twoButtonDiameter / 64f)
    /** Diamonds: 56 dp circles, centres 62 dp from the cluster centre. */
    val diamondDiameter: Float get() = max(MINIMUM_FACE, 56f * scale)
    val diamondRadius: Float get() = 62f * max(scale, diamondDiameter / 56f)
    /** Six-button grids: 50 dp circles. */
    val sixDiameter: Float get() = max(MINIMUM_FACE, 50f * scale)
    val sixPitch: Float get() = max(58f * scale, sixDiameter + 8f)
    /**
     * Nintendo 64: A 64, B 56, the C buttons a 44 dp diamond (the one exception to the 50 dp
     * face floor, per console; never under 44).
     */
    val n64A: Float get() = max(MINIMUM_FACE, 64f * scale)
    val n64B: Float get() = max(MINIMUM_FACE, 56f * scale)
    val n64C: Float get() = max(44f, 44f * scale)
    /** A family's own arrangement (rail units of 48 dp buttons), grown to the floor. */
    val customFactor: Float get() = max(1.1f * scale, MINIMUM_FACE / 48f)
    val shoulder: Size get() = Size(88f * scale, max(32f, 40f * scale))
    /** Nintendo 64's Z trigger: a large tab. */
    val zTab: Size get() = Size(92f * scale, max(40f, 48f * scale))
    val pill: Size get() = Size(64f * scale, max(30f, 34f * scale))
    val menu: Float get() = MENU_DIAMETER
    val shoulderGap: Float get() = 8f
    /** Gap between the elements stacked on a rail (a stick under a cluster…). */
    val stackGap: Float get() = 10f
    /** Portrait: shoulders sit this far above their side's cluster. */
    val shoulderLift: Float get() = 16f

    /** An analog stick's square zone: 160 dp in the thumb spot, 120 dp as a secondary. */
    fun stickZone(primary: Boolean): Float =
        if (primary) 160f * scale else max(minimumSecondaryZone, min(120f * scale, secondaryStickLimit))

    /** The smallest a capped secondary zone gets: the travel ring and 10 dp of play each way, and never under three quarters of its size. */
    val minimumSecondaryZone: Float get() = max(2 * stickTravel(primary = false) + 20f, 90f * scale)

    /** Travel for full deflection: 44 dp (primary), 36 dp (secondary). */
    fun stickTravel(primary: Boolean): Float = (if (primary) 44f else 36f) * scale

    fun withSecondaryStickLimit(limit: Float) = PlayerControlMetrics(scale, limit)

    override fun equals(other: Any?): Boolean = other is PlayerControlMetrics && other.scale == scale && other.secondaryStickLimit == secondaryStickLimit
    override fun hashCode(): Int = 31 * scale.hashCode() + secondaryStickLimit.hashCode()
    override fun toString(): String = "PlayerControlMetrics(scale=$scale, secondaryStickLimit=$secondaryStickLimit)"

    companion object {
        const val MIN_SCALE = 0.8f
        const val MAX_SCALE = 1.3f
        /** Hit regions are the drawn shape grown by this much. */
        const val HIT_GROWTH = 14f
        const val MINIMUM_FACE = 50f
        const val MENU_DIAMETER = 44f
        /** The two-button rise, in radians. */
        val TWO_BUTTON_RISE: Float = (25.0 * Math.PI / 180.0).toFloat()
    }
}

/** A control where it is drawn, in dp from the overlay's top left. */
data class PlacedPlayerControl(
    val spec: PlayerControlSpec,
    val frame: Rect,
    val shape: PlayerTouchShape,
    /** Sticks: the console's profile at the control size and the game's tuning. */
    val stickProfile: PlayerStickProfile? = null,
    /** Sticks: the centre stays at the zone's centre (the game's Centre: Fixed). */
    val fixedCentre: Boolean = false,
    /** A d-pad beside a primary stick (Nintendo 64, 3DS): four directions, no diagonals. */
    val fourWay: Boolean = false,
) {
    /** Sticks: thumb travel for full tilt (dp), before saturation. */
    val travel: Float get() = stickProfile?.travel?.toFloat() ?: 0f
    val id: String get() = spec.id
    val kind: PlayerTouchKind get() = spec.kind
    val center: Offset get() = frame.center
    /** The drawn shape grown by [PlayerControlMetrics.HIT_GROWTH] (a stick's zone is its own). */
    val hitFrame: Rect get() = if (shape == PlayerTouchShape.ZONE) frame else frame.inflate(PlayerControlMetrics.HIT_GROWTH)
}

/** Every touch control of a stage and the Menu button. */
data class PlayerTouchLayout(
    val controls: List<PlacedPlayerControl>,
    /** The face cluster's zone: its buttons' box grown by the hit growth. Inside it the nearest button takes a touch. */
    val faceZone: Rect?,
    /** The two buttons of a two-button face (a touch between them presses both). */
    val chord: List<String>?,
    val menuCenter: Offset,
    val menuDiameter: Float,
    val metrics: PlayerControlMetrics,
    /**
     * The game's screens: touches that land on one go to the game (a DS or 3DS touch screen,
     * pointer games), never to a control whose grown hit region reaches it.
     */
    val gameScreens: List<Rect> = emptyList(),
) {
    val dpad: PlacedPlayerControl? get() = controls.firstOrNull { it.kind == PlayerTouchKind.DPAD }
    val menuFrame: Rect get() = Rect(menuCenter.x - menuDiameter / 2, menuCenter.y - menuDiameter / 2, menuCenter.x + menuDiameter / 2, menuCenter.y + menuDiameter / 2)
    fun control(id: String): PlacedPlayerControl? = controls.firstOrNull { it.id == id }

    companion object {
        val Empty = PlayerTouchLayout(emptyList(), null, null, Offset.Zero, PlayerControlMetrics.MENU_DIAMETER, PlayerControlMetrics(1f))
    }
}

/** Where the rails and rows are, for [PlayerControlPlan.place]. */
data class PlayerControlAnchors(
    /** Horizontal extent of the rail on each side of the screen. */
    val leftRail: ClosedFloatingPointRange<Float>,
    val rightRail: ClosedFloatingPointRange<Float>,
    /** Clusters hug the rail's outer edge (portrait) or centre in it (landscape). */
    val alignOuter: Boolean,
    /** Vertical centre of the primary control on each side. */
    val leftClusterY: Float,
    val rightClusterY: Float,
    val shoulders: Shoulders,
    val utility: Utility,
) {
    sealed interface Shoulders {
        /** The top of the rails (landscape); [stacked]: numbered shoulders in a column, 1 above 2, centred in the rail. */
        data class Top(val y: Float, val stacked: Boolean = false) : Shoulders
        /**
         * Portrait (revised 2026-09-26): one level row at [top] (null: 16 dp above the higher of the two
         * stacks), at least [minHeight] tall. Each side's main shoulder (L, R, L1, R1) is a bumper flush
         * against its screen edge ([leftEdge], [rightEdge]), covering the rail's margin; any other shoulder
         * sits inward beside it.
         */
        data class Corners(val top: Float?, val leftEdge: Float, val rightEdge: Float, val minHeight: Float, val stacked: Boolean = false) : Shoulders
    }

    sealed interface Utility {
        /** Select · Menu · Start in one centred row. */
        data class Row(val midX: Float, val y: Float, val maxWidth: Float) : Utility
        /** Select and Start at the foot of the rails; Menu under the game or beside them. */
        data class Feet(val y: Float, val menu: MenuSpot) : Utility
    }

    sealed interface MenuSpot {
        data class Under(val point: Offset) : MenuSpot
        data object LeadingFoot : MenuSpot
        data object TrailingFoot : MenuSpot
    }
}

/**
 * A family's touch controls: the d-pad, the face cluster, shoulders, sticks and the utility row
 * (Select, Start…), classified from [ExpansionControls], with each console's layout.
 */
data class PlayerControlPlan(
    val family: String,
    val dpad: PlayerControlSpec?,
    val dpadSlot: DPadSlot,
    val face: List<PlayerControlSpec>,
    val arrangement: PlayerFaceArrangement,
    /** Left-rail shoulders, then right-rail shoulders, each in rail order (left to right). */
    val leftShoulders: List<PlayerControlSpec>,
    val rightShoulders: List<PlayerControlSpec>,
    val leftStick: PlayerControlSpec?,
    val rightStick: PlayerControlSpec?,
    /** The left stick takes the thumb spot (N64, 3DS); otherwise sticks are secondary. */
    val leftStickPrimary: Boolean,
    /** Select, Start and the like, in order; Menu sits in the middle of them. */
    val utilities: List<PlayerControlSpec>,
    /** PlayStation's per-game Analog controls (sticks and L3/R3 shown). */
    val analogControls: Boolean = false,
    /** The game's stick tuning (Sensitivity, Dead zone, Centre). */
    val stickTuning: PlayerStickTuning = PlayerStickTuning(),
) {
    /**
     * Where the d-pad sits on its rail: in the thumb spot, or small above or below a primary
     * stick (or beside it, toward the screen's centre, on a band too short for the stack:
     * [compact]).
     */
    enum class DPadSlot { PRIMARY, ABOVE, BELOW, BESIDE }

    /** Every control, in a stable order (d-pad, face, shoulders, sticks, utilities). */
    val all: List<PlayerControlSpec>
        get() = listOfNotNull(dpad) + face + leftShoulders + rightShoulders + listOfNotNull(leftStick, rightStick) + utilities

    val hasShoulders: Boolean get() = leftShoulders.isNotEmpty() || rightShoulders.isNotEmpty()
    /** Numbered shoulders (PlayStation's L1/L2, R1/R2) stack, 1 above 2, when a stage has room. */
    val numberedShoulders: Boolean
        get() {
            val labels = (leftShoulders + rightShoulders).map { it.label }.toSet()
            return labels.containsAll(listOf("L1", "L2")) || labels.containsAll(listOf("R1", "R2"))
        }
    val shoulderStackRows: Int get() = if (numberedShoulders) max(leftShoulders.size, rightShoulders.size) else 1

    /** The height the shoulders take: one row, or the stack of [shoulderStackRows]. */
    fun shoulderBlockHeight(rowHeight: Float, stacked: Boolean): Float {
        val rows = if (stacked) shoulderStackRows else 1
        return rows * rowHeight + (rows - 1) * SHOULDER_STACK_GAP
    }
    val hasSticks: Boolean get() = leftStick != null || rightStick != null
    /** A pair of secondary sticks lines up under the taller cluster. */
    internal val stickPair: Boolean get() = leftStick != null && rightStick != null && !leftStickPrimary
    /** Start goes bottom centre beside Menu (Nintendo 64). */
    internal val utilitiesBesideMenu: Boolean get() = family == "n64"

    /**
     * The same controls with the small d-pad beside the primary stick rather than above or below
     * it, for a band too short for the stack; null when there is no such stack.
     */
    fun compact(): PlayerControlPlan? {
        if (!leftStickPrimary || dpad == null || (dpadSlot != DPadSlot.ABOVE && dpadSlot != DPadSlot.BELOW)) return null
        return copy(dpadSlot = DPadSlot.BESIDE)
    }

    /** Face buttons with their offsets from the cluster centre and diameters. */
    fun faceGeometry(metrics: PlayerControlMetrics): List<PlayerFaceButton> = when (arrangement) {
        PlayerFaceArrangement.NONE -> emptyList()
        PlayerFaceArrangement.TWO_BUTTON -> {
            // The lower one (on the console) goes lower-left.
            val sorted = face.sortedByDescending { it.railY }
            val dx = metrics.twoButtonSpacing * cos(PlayerControlMetrics.TWO_BUTTON_RISE) / 2
            val dy = metrics.twoButtonSpacing * sin(PlayerControlMetrics.TWO_BUTTON_RISE) / 2
            val d = metrics.twoButtonDiameter
            listOf(PlayerFaceButton(sorted[0], Offset(-dx, dy), d), PlayerFaceButton(sorted[1], Offset(dx, -dy), d))
        }
        PlayerFaceArrangement.DIAMOND -> {
            val cx = face.map { it.railX }.average().toFloat()
            val cy = face.map { it.railY }.average().toFloat()
            face.map { spec ->
                val vx = spec.railX - cx
                val vy = spec.railY - cy
                val length = max(hypot(vx, vy), 0.001f)
                PlayerFaceButton(spec, Offset(vx / length * metrics.diamondRadius, vy / length * metrics.diamondRadius), metrics.diamondDiameter)
            }
        }
        PlayerFaceArrangement.SIX_BUTTON -> {
            val xs = face.map { it.railX.roundToInt() }.distinct().sorted()
            val ys = face.map { it.railY.roundToInt() }.distinct().sorted()
            face.map { spec ->
                val column = xs.indexOf(spec.railX.roundToInt()).coerceAtLeast(0) - (xs.size - 1) / 2f
                val row = ys.indexOf(spec.railY.roundToInt()).coerceAtLeast(0) - (ys.size - 1) / 2f
                PlayerFaceButton(spec, Offset(column * metrics.sixPitch, row * metrics.sixPitch), metrics.sixDiameter)
            }
        }
        PlayerFaceArrangement.N64 -> {
            // A at the origin, B down-left on a 25° line, the C diamond above-right of A; then
            // everything centred on the cluster's box.
            val a = metrics.n64A
            val b = metrics.n64B
            val c = metrics.n64C
            val unit = max(metrics.scale, a / 64f)
            val bOffset = Offset(-70f * unit * cos(PlayerControlMetrics.TWO_BUTTON_RISE), 70f * unit * sin(PlayerControlMetrics.TWO_BUTTON_RISE))
            // C-Down sits just above-right of A, clear of it; the diamond around it, its
            // buttons' boxes clear of each other.
            val cRadius = c * 1.05f
            val cCentre = Offset(a / 2 + 6f * unit, -(a / 2 + c / 2 + 4f * unit) - cRadius)
            val raw = face.map { spec ->
                when (spec.id) {
                    "A" -> PlayerFaceButton(spec, Offset.Zero, a)
                    "B" -> PlayerFaceButton(spec, bOffset, b)
                    else -> {
                        val direction = when (spec.id) {
                            "C-Up" -> Offset(0f, -1f)
                            "C-Down" -> Offset(0f, 1f)
                            "C-Left" -> Offset(-1f, 0f)
                            else -> Offset(1f, 0f)
                        }
                        PlayerFaceButton(spec, Offset(cCentre.x + direction.x * cRadius, cCentre.y + direction.y * cRadius), c)
                    }
                }
            }
            val minX = raw.minOf { it.offset.x - it.diameter / 2 }
            val maxX = raw.maxOf { it.offset.x + it.diameter / 2 }
            val minY = raw.minOf { it.offset.y - it.diameter / 2 }
            val maxY = raw.maxOf { it.offset.y + it.diameter / 2 }
            val mid = Offset((minX + maxX) / 2, (minY + maxY) / 2)
            raw.map { it.copy(offset = it.offset - mid) }
        }
        PlayerFaceArrangement.CUSTOM -> {
            val minX = face.minOf { it.railX }
            val maxX = face.maxOf { it.railX }
            val minY = face.minOf { it.railY }
            val maxY = face.maxOf { it.railY }
            val k = metrics.customFactor
            face.map { spec ->
                PlayerFaceButton(
                    spec,
                    Offset((spec.railX - (minX + maxX) / 2) * k, (spec.railY - (minY + maxY) / 2) * k),
                    max(PlayerControlMetrics.MINIMUM_FACE, spec.railSize * k),
                )
            }
        }
    }

    /** The face cluster's drawn bounding box, centred on the origin. */
    fun faceSize(metrics: PlayerControlMetrics): Size {
        val geometry = faceGeometry(metrics)
        if (geometry.isEmpty()) return Size.Zero
        val xs = geometry.flatMap { listOf(it.offset.x - it.diameter / 2, it.offset.x + it.diameter / 2) }
        val ys = geometry.flatMap { listOf(it.offset.y - it.diameter / 2, it.offset.y + it.diameter / 2) }
        return Size(2 * max(abs(xs.min()), abs(xs.max())), 2 * max(abs(ys.min()), abs(ys.max())))
    }

    /** A shoulder's nominal size (Nintendo 64's Z is a large tab). */
    internal fun shoulderSize(spec: PlayerControlSpec, metrics: PlayerControlMetrics): Size =
        if (family == "n64" && spec.id == "Z") metrics.zTab else metrics.shoulder

    /** One side's shoulder row: widths (narrower to fit [available]) and height. A large tab keeps its width while the others give way to 56 dp. */
    /**
     * The portrait corner row's height ([PlayerControlAnchors.Shoulders.Corners]): the tallest shoulder, at
     * least [minimum] (44 dp on a phone's band, which plans room for it); every shoulder in the row takes it.
     */
    fun cornerRowHeight(metrics: PlayerControlMetrics, minimum: Float = 44f): Float {
        val heights = (leftShoulders + rightShoulders).map { shoulderSize(it, metrics).height }
        return heights.maxOrNull()?.let { max(minimum, it) } ?: 0f
    }

    internal fun shoulderRow(specs: List<PlayerControlSpec>, available: Float, metrics: PlayerControlMetrics): Pair<List<Float>, Float> {
        if (specs.isEmpty()) return emptyList<Float>() to 0f
        val sizes = specs.map { shoulderSize(it, metrics) }
        val room = max(0f, available - (specs.size - 1) * metrics.shoulderGap)
        var widths = sizes.map { it.width }
        val large = specs.map { sizes.size > 1 && shoulderSize(it, metrics) == metrics.zTab }
        val largeWidth = widths.zip(large).filter { it.second }.sumOf { it.first.toDouble() }.toFloat()
        val others = large.count { !it }
        if (widths.sum() > room && largeWidth > 0f && others > 0) {
            val each = max(min(56f, metrics.shoulder.width), (room - largeWidth) / others)
            widths = widths.zip(large).map { (w, isLarge) -> if (isLarge) w else min(w, each) }
        }
        val total = widths.sum()
        if (total > room) widths = widths.map { it * room / max(total, 1f) }
        return widths to (sizes.maxOfOrNull { it.height } ?: 0f)
    }

    /**
     * What one rail holds, as a vertical stack: shoulders, then (optionally) a secondary control
     * above the primary, the primary in the thumb spot, and a secondary below.
     */
    data class RailContent(
        /** The primary control's drawn size (d-pad disc, stick zone or face box). */
        val cluster: Size,
        val above: Size?,
        val below: Size?,
        /** A secondary beside the primary, toward the screen's centre (10 dp apart). */
        val beside: Size? = null,
        /** The shoulder row at its natural width, and its height. */
        val shoulderRow: Size,
    ) {
        /** The primary control with anything beside it. */
        val block: Float get() = cluster.width + (beside?.let { it.width + 10f } ?: 0f)
        /** The stack's width (a shoulder row narrows to fit it). */
        val width: Float get() = maxOf(block, above?.width ?: 0f, below?.width ?: 0f)
        /** Height above the primary control's top: a secondary above it and its gap. */
        fun aboveHeight(metrics: PlayerControlMetrics): Float = above?.let { it.height + metrics.stackGap } ?: 0f
        /** Height under the primary control's bottom: a secondary below it and its gap. */
        fun belowHeight(metrics: PlayerControlMetrics): Float = below?.let { it.height + metrics.stackGap } ?: 0f
    }

    /** The rail holding the d-pad ([dpadSide]) or the face cluster. */
    fun rail(dpadSide: Boolean, metrics: PlayerControlMetrics): RailContent {
        fun row(specs: List<PlayerControlSpec>): Size {
            if (specs.isEmpty()) return Size.Zero
            val sizes = specs.map { shoulderSize(it, metrics) }
            return Size(sizes.sumOf { it.width.toDouble() }.toFloat() + (specs.size - 1) * metrics.shoulderGap, sizes.maxOf { it.height })
        }
        if (dpadSide) {
            if (leftStickPrimary) {
                val zone = metrics.stickZone(primary = true)
                val pad = if (dpad == null) null else Size(metrics.smallDpad, metrics.smallDpad)
                return RailContent(
                    cluster = Size(zone, zone),
                    above = if (dpadSlot == DPadSlot.ABOVE) pad else null,
                    below = if (dpadSlot == DPadSlot.BELOW) pad else null,
                    beside = if (dpadSlot == DPadSlot.BESIDE) pad else null,
                    shoulderRow = row(leftShoulders),
                )
            }
            val secondary = metrics.stickZone(primary = false)
            return RailContent(
                cluster = if (dpad == null) Size.Zero else Size(metrics.dpad, metrics.dpad),
                above = null,
                below = if (leftStick == null) null else Size(secondary, secondary),
                shoulderRow = row(leftShoulders),
            )
        }
        val secondary = metrics.stickZone(primary = false)
        return RailContent(faceSize(metrics), null, if (rightStick == null) null else Size(secondary, secondary), shoulderRow = row(rightShoulders))
    }

    /** Utilities at the rails' feet: the leading rail takes the first half. */
    internal val leadingUtilityCount: Int get() = (utilities.size + 1) / 2

    /** Menu joins the foot with fewer utilities (the leading one on a tie). */
    internal val menuOnLeadingFoot: Boolean get() = leadingUtilityCount <= utilities.size - leadingUtilityCount

    /** A foot row at its narrowest ([footPill] pills, [footGap] apart), with Menu where it goes. */
    internal fun footWidth(leading: Boolean, metrics: PlayerControlMetrics): Float {
        if (utilitiesBesideMenu) return 0f
        val count = if (leading) leadingUtilityCount else utilities.size - leadingUtilityCount
        val menu = leading == menuOnLeadingFoot
        val gap = footGap(count)
        val pills = if (count == 0) 0f else count * footPill(count) + (count - 1) * gap
        return pills + if (menu) metrics.menu + (if (count == 0) 0f else gap) else 0f
    }

    /**
     * The narrowest pill at a rail's foot: 44 dp (as iOS), or 36 dp 6 dp apart when a foot holds
     * three or more (the Atari consoles' switches), so the switches don't squeeze the game on a
     * small landscape phone. Hit regions stay 58 dp or more either way.
     */
    internal fun footPill(count: Int): Float = if (count >= 3) 36f else 44f

    internal fun footGap(count: Int): Float = if (count >= 3) 6f else 10f

    /** Places every control. [mirrored] (left-handed) swaps the rails, never labels. */
    fun place(anchors: PlayerControlAnchors, metrics: PlayerControlMetrics, mirrored: Boolean): PlayerTouchLayout {
        val placed = mutableListOf<PlacedPlayerControl>()
        val dpadRail = if (mirrored) anchors.rightRail else anchors.leftRail
        val faceRail = if (mirrored) anchors.leftRail else anchors.rightRail
        val dpadY = if (mirrored) anchors.rightClusterY else anchors.leftClusterY
        val faceY = if (mirrored) anchors.leftClusterY else anchors.rightClusterY
        val dpadOnLeft = !mirrored
        val gap = metrics.stackGap

        fun clusterX(rail: ClosedFloatingPointRange<Float>, width: Float, onLeft: Boolean): Float {
            if (!anchors.alignOuter) return (rail.start + rail.endInclusive) / 2
            return if (onLeft) rail.start + width / 2 else rail.endInclusive - width / 2
        }
        fun square(centre: Offset, side: Float) = Rect(centre.x - side / 2, centre.y - side / 2, centre.x + side / 2, centre.y + side / 2)
        fun placedStick(spec: PlayerControlSpec, frame: Rect) = PlacedPlayerControl(
            spec, frame, PlayerTouchShape.ZONE,
            stickProfile = PlayerStickProfile.forStick(family, spec.stick ?: 0).tuned(stickTuning, metrics.scale),
            fixedCentre = stickTuning.centre == PlayerStickTuning.Centre.FIXED,
        )

        val dpadContent = rail(dpadSide = true, metrics)
        val faceContent = rail(dpadSide = false, metrics)
        // A pair of secondary sticks lines up under the taller cluster.
        val tallest = max(dpadContent.cluster.height, faceContent.cluster.height)
        val pairY = max(dpadY, faceY)

        // The d-pad's rail: the primary control (d-pad, or the left stick) and its secondary.
        // With a secondary beside it, the pair is one block: the primary at its outer side.
        val dpadBlockX = clusterX(dpadRail, max(dpadContent.block, 1f), dpadOnLeft)
        val besideShift = (dpadContent.block - dpadContent.cluster.width) / 2
        val dpadPrimaryX = dpadBlockX + if (dpadOnLeft) -besideShift else besideShift
        val primaryTop = dpadY - dpadContent.cluster.height / 2
        val primaryBottom = dpadY + dpadContent.cluster.height / 2
        val stick = leftStick
        if (leftStickPrimary && stick != null) {
            placed += placedStick(stick, square(Offset(dpadPrimaryX, dpadY), dpadContent.cluster.width))
            dpad?.let { spec ->
                val side = metrics.smallDpad
                val centre = when (dpadSlot) {
                    DPadSlot.ABOVE -> Offset(dpadPrimaryX, primaryTop - gap - side / 2)
                    DPadSlot.BESIDE -> Offset(dpadPrimaryX + (dpadContent.cluster.width / 2 + gap + side / 2) * (if (dpadOnLeft) 1 else -1), dpadY)
                    else -> Offset(dpadPrimaryX, primaryBottom + gap + side / 2)
                }
                placed += PlacedPlayerControl(spec, square(centre, side), PlayerTouchShape.CIRCLE, fourWay = true)
            }
        } else {
            dpad?.let { placed += PlacedPlayerControl(it, square(Offset(dpadPrimaryX, dpadY), metrics.dpad), PlayerTouchShape.CIRCLE) }
            if (stick != null) {
                val side = metrics.stickZone(primary = false)
                val top = if (stickPair) pairY + tallest / 2 else primaryBottom
                placed += placedStick(stick, square(Offset(dpadPrimaryX, top + gap + side / 2), side))
            }
        }

        // The face rail.
        var faceZone: Rect? = null
        val facePrimaryX = clusterX(faceRail, max(faceContent.cluster.width, 1f), !dpadOnLeft)
        if (face.isNotEmpty()) {
            for (button in faceGeometry(metrics)) {
                val cx = facePrimaryX + button.offset.x
                val cy = faceY + button.offset.y
                val r = button.diameter / 2
                placed += PlacedPlayerControl(button.spec, Rect(cx - r, cy - r, cx + r, cy + r), PlayerTouchShape.CIRCLE)
            }
            faceZone = placed.filter { it.kind == PlayerTouchKind.FACE }.map { it.frame }.reduce(Rect::union).inflate(PlayerControlMetrics.HIT_GROWTH)
        }
        rightStick?.let { spec ->
            val side = metrics.stickZone(primary = false)
            val top = if (stickPair) pairY + tallest / 2 else faceY + faceContent.cluster.height / 2
            placed += placedStick(spec, square(Offset(facePrimaryX, top + gap + side / 2), side))
        }

        // Shoulders: the top of each rail (landscape), or the portrait corners.
        fun stackTop(content: RailContent, clusterY: Float): Float = clusterY - content.cluster.height / 2 - content.aboveHeight(metrics)
        val levelTop = min(stackTop(dpadContent, dpadY), stackTop(faceContent, faceY)) - metrics.shoulderLift - cornerRowHeight(metrics, minimum = 0f)
        fun shoulders(specs: List<PlayerControlSpec>, rail: ClosedFloatingPointRange<Float>, onLeft: Boolean) {
            if (specs.isEmpty()) return
            val (widths, rowHeight) = shoulderRow(specs, rail.endInclusive - rail.start, metrics)
            val total = widths.sum() + (specs.size - 1) * metrics.shoulderGap
            val top: Float
            val startX: Float
            val main = specs.indexOfFirst { it.label in setOf("L", "R", "L1", "R1") }.coerceAtLeast(0)
            val outward = listOf(main) + specs.indices.filter { it != main }
            when (val shoulders = anchors.shoulders) {
                is PlayerControlAnchors.Shoulders.Top -> {
                    if (shoulders.stacked && specs.size > 1) {
                        // A column in the rail, the main shoulder on top.
                        val railWidth = rail.endInclusive - rail.start
                        var rowTop = shoulders.y
                        outward.forEach { index ->
                            val size = shoulderSize(specs[index], metrics)
                            val width = min(size.width, railWidth)
                            val x = when {
                                !anchors.alignOuter -> (rail.start + rail.endInclusive) / 2 - width / 2
                                onLeft -> rail.start
                                else -> rail.endInclusive - width
                            }
                            placed += PlacedPlayerControl(specs[index], Rect(x, rowTop, x + width, rowTop + size.height), PlayerTouchShape.TAB)
                            rowTop += size.height + SHOULDER_STACK_GAP
                        }
                        return
                    }
                    top = shoulders.y
                    startX = if (anchors.alignOuter) {
                        if (onLeft) rail.start else rail.endInclusive - total
                    } else {
                        (rail.start + rail.endInclusive) / 2 - total / 2
                    }
                }
                is PlayerControlAnchors.Shoulders.Corners -> {
                    // From the edge inward: the main shoulder as a bumper (over the rail's margin), then the others.
                    val edge = if (onLeft) shoulders.leftEdge else shoulders.rightEdge
                    val margin = max(0f, if (onLeft) rail.start - shoulders.leftEdge else shoulders.rightEdge - rail.endInclusive)
                    val height = cornerRowHeight(metrics, minimum = shoulders.minHeight)
                    val rowTop = shoulders.top ?: levelTop
                    val bumper = if (onLeft) PlayerTouchShape.BUMPER_LEADING else PlayerTouchShape.BUMPER_TRAILING
                    if (shoulders.stacked) {
                        // A column against the edge, the main shoulder on top; every one a bumper.
                        val railWidth = rail.endInclusive - rail.start
                        outward.forEachIndexed { n, index ->
                            val width = min(shoulderSize(specs[index], metrics).width, railWidth) + margin
                            val x = if (onLeft) edge else edge - width
                            val y = rowTop + n * (height + SHOULDER_STACK_GAP)
                            placed += PlacedPlayerControl(specs[index], Rect(x, y, x + width, y + height), bumper)
                        }
                        return
                    }
                    var offset = 0f
                    (listOf(main) + specs.indices.filter { it != main }).forEachIndexed { n, index ->
                        val width = widths[index] + if (n == 0) margin else 0f
                        val x = if (onLeft) edge + offset else edge - offset - width
                        val shape = when {
                            n > 0 -> PlayerTouchShape.TAB
                            onLeft -> PlayerTouchShape.BUMPER_LEADING
                            else -> PlayerTouchShape.BUMPER_TRAILING
                        }
                        placed += PlacedPlayerControl(specs[index], Rect(x, rowTop, x + width, rowTop + height), shape)
                        offset += width + metrics.shoulderGap
                    }
                    return
                }
            }
            var x = startX
            specs.zip(widths).forEach { (spec, width) ->
                val height = shoulderSize(spec, metrics).height
                placed += PlacedPlayerControl(spec, Rect(x, top + rowHeight - height, x + width, top + rowHeight), PlayerTouchShape.TAB)
                x += width + metrics.shoulderGap
            }
        }
        shoulders(leftShoulders, dpadRail, dpadOnLeft)
        shoulders(rightShoulders, faceRail, !dpadOnLeft)

        // Select · Menu · Start.
        val pill = metrics.pill
        val gapBetweenPills = 10f
        var menuCenter = Offset.Zero
        when (val utility = anchors.utility) {
            is PlayerControlAnchors.Utility.Row -> {
                val count = utilities.size
                var rowGap = gapBetweenPills
                var width = if (count == 0) pill.width else max(44f, min(pill.width, (utility.maxWidth - metrics.menu - count * rowGap) / count))
                if (count > 0 && count * (width + rowGap) + metrics.menu > utility.maxWidth) {
                    // A narrow phone with many switches (Atari 2600): closer and narrower pills,
                    // never under 30 dp (their hit regions stay 58 dp or more).
                    rowGap = 6f
                    width = max(30f, min(pill.width, (utility.maxWidth - metrics.menu - count * rowGap) / count))
                }
                val menuIndex = count / 2
                val items = (0..count).map { if (it == menuIndex) metrics.menu else width }
                val total = items.sum() + count * rowGap
                var x = utility.midX - total / 2
                menuCenter = Offset(utility.midX, utility.y)
                var utilityIndex = 0
                items.forEachIndexed { slot, itemWidth ->
                    if (slot == menuIndex) {
                        menuCenter = Offset(x + itemWidth / 2, utility.y)
                    } else {
                        val spec = utilities[utilityIndex++]
                        placed += PlacedPlayerControl(spec, Rect(x, utility.y - pill.height / 2, x + itemWidth, utility.y + pill.height / 2), PlayerTouchShape.CAPSULE)
                    }
                    x += itemWidth + rowGap
                }
            }
            is PlayerControlAnchors.Utility.Feet -> {
                val under = utility.menu as? PlayerControlAnchors.MenuSpot.Under
                if (under != null && utilitiesBesideMenu && utilities.isNotEmpty()) {
                    // Nintendo 64: Menu and Start together, bottom centre.
                    val total = metrics.menu + utilities.size * (pill.width + gapBetweenPills)
                    var x = under.point.x - total / 2
                    menuCenter = Offset(x + metrics.menu / 2, under.point.y)
                    x += metrics.menu + gapBetweenPills
                    for (spec in utilities) {
                        placed += PlacedPlayerControl(spec, Rect(x, under.point.y - pill.height / 2, x + pill.width, under.point.y + pill.height / 2), PlayerTouchShape.CAPSULE)
                        x += pill.width + gapBetweenPills
                    }
                } else {
                    // Leading rail: the first half; trailing rail: the rest; Menu under the game or
                    // beside them (leading first, innermost on the trailing rail).
                    val leading = utilities.take(leadingUtilityCount)
                    val trailing = utilities.drop(leadingUtilityCount)
                    under?.let { menuCenter = it.point }
                    fun row(specs: List<PlayerControlSpec>, rail: ClosedFloatingPointRange<Float>, withMenu: Boolean, menuFirst: Boolean) {
                        val railWidth = rail.endInclusive - rail.start
                        val count = specs.size
                        val footGap = footGap(count)
                        val menuWidth = if (withMenu) metrics.menu + footGap else 0f
                        if (specs.isEmpty() && !withMenu) return
                        val width = if (specs.isEmpty()) 0f else max(footPill(count), min(pill.width, (railWidth - menuWidth - (count - 1) * footGap) / count))
                        val total = menuWidth + if (specs.isEmpty()) 0f else count * width + (count - 1) * footGap
                        var x = (rail.start + rail.endInclusive) / 2 - total / 2
                        if (withMenu && menuFirst) {
                            menuCenter = Offset(x + metrics.menu / 2, utility.y)
                            x += metrics.menu + footGap
                        }
                        for (spec in specs) {
                            placed += PlacedPlayerControl(spec, Rect(x, utility.y - pill.height / 2, x + width, utility.y + pill.height / 2), PlayerTouchShape.CAPSULE)
                            x += width + footGap
                        }
                        if (withMenu && !menuFirst) menuCenter = Offset(x + metrics.menu / 2, utility.y)
                    }
                    row(leading, anchors.leftRail, utility.menu == PlayerControlAnchors.MenuSpot.LeadingFoot, menuFirst = false)
                    row(trailing, anchors.rightRail, utility.menu == PlayerControlAnchors.MenuSpot.TrailingFoot, menuFirst = true)
                }
            }
        }

        val chord = if (arrangement == PlayerFaceArrangement.TWO_BUTTON) face.map { it.id } else null
        return PlayerTouchLayout(placed, faceZone, chord, menuCenter, metrics.menu, metrics)
    }

    companion object {
        /** The gap between stacked shoulders. */
        const val SHOULDER_STACK_GAP = 8f
        /**
         * The family's plan, or null when it has no touch controls. [analogControls]: PlayStation's
         * per-game Analog controls; [stickTuning]: the game's stick tuning.
         */
        fun of(family: String, analogControls: Boolean = false, stickTuning: PlayerStickTuning = PlayerStickTuning()): PlayerControlPlan? =
            ExpansionControls.controls(family)?.let { from(family, it, analogControls, stickTuning) }

        fun from(
            family: String,
            controls: List<ExpansionControl>,
            analogControls: Boolean = false,
            stickTuning: PlayerStickTuning = PlayerStickTuning(),
        ): PlayerControlPlan {
            fun spec(control: ExpansionControl, kind: PlayerTouchKind) = PlayerControlSpec(
                id = control.id,
                label = control.label,
                kind = kind,
                keys = control.combo.ifEmpty { listOfNotNull(control.key) },
                stick = control.stick,
                axisX = control.axisX,
                axisY = control.axisY,
                left = control.left,
                railX = control.x,
                railY = control.y,
                railSize = control.size,
            )
            // PlayStation without Analog controls: no sticks and no L3/R3 (the Analog mode button stays).
            val hidesAnalog = family == "psx" && !analogControls
            val kept = controls.filterNot { hidesAnalog && (it.stick != null || it.id == "L3" || it.id == "R3") }
            val directions = kept.filter { !it.center && it.key in PlayerDirectionKeys }
            val dpad = if (directions.isEmpty()) null else PlayerControlSpec(
                id = "dpad", label = "D-pad", kind = PlayerTouchKind.DPAD, keys = PlayerDirectionKeys, stick = null,
                axisX = 0f, axisY = 0f, left = true, railX = 74f, railY = 122f, railSize = 144f,
            )
            val rest = kept.filter { control -> directions.none { it.id == control.id } }
            val utilities = rest.filter { it.center }.map { spec(it, PlayerTouchKind.SYSTEM) }
            val sticks = rest.filter { !it.center && it.stick != null }.map { spec(it, PlayerTouchKind.STICK) }.toMutableList()
            if (family == "3ds" && sticks.none { it.left }) {
                // The circle pad: left analog to the core (docs/player-controls.md, per console).
                sticks += PlayerControlSpec(
                    id = "circlePad", label = "Circle Pad", kind = PlayerTouchKind.STICK, keys = emptyList(), stick = 0,
                    axisX = 0f, axisY = 0f, left = true, railX = 74f, railY = 122f, railSize = 160f,
                )
            }
            val leftStick = sticks.firstOrNull { it.left }
            val rightStick = sticks.firstOrNull { !it.left }
            val (leftStickPrimary, dpadSlot) = when (family) {
                "n64" -> (leftStick != null) to (if (leftStick == null) DPadSlot.PRIMARY else DPadSlot.ABOVE)
                "3ds" -> (leftStick != null) to (if (leftStick == null) DPadSlot.PRIMARY else DPadSlot.BELOW)
                else -> false to DPadSlot.PRIMARY
            }
            // Shoulders are the rail's top row.
            val shoulders = rest.filter { !it.center && it.stick == null && it.y <= 30f }
            val face = rest.filter { control -> !control.center && control.stick == null && shoulders.none { it.id == control.id } }
                .map { spec(it, PlayerTouchKind.FACE) }
            return PlayerControlPlan(
                family = family,
                dpad = dpad,
                dpadSlot = dpadSlot,
                face = face,
                arrangement = if (family == "n64") PlayerFaceArrangement.N64 else arrangement(face),
                leftShoulders = shoulders.filter { it.left }.sortedBy { it.x }.map { spec(it, PlayerTouchKind.SHOULDER) },
                rightShoulders = shoulders.filter { !it.left }.sortedBy { it.x }.map { spec(it, PlayerTouchKind.SHOULDER) },
                leftStick = leftStick,
                rightStick = rightStick,
                leftStickPrimary = leftStickPrimary,
                utilities = utilities,
                analogControls = analogControls,
                stickTuning = stickTuning,
            )
        }

        private fun arrangement(face: List<PlayerControlSpec>): PlayerFaceArrangement = when (face.size) {
            0 -> PlayerFaceArrangement.NONE
            2 -> PlayerFaceArrangement.TWO_BUTTON
            4 -> {
                // Four around one centre, one in each direction.
                val cx = face.map { it.railX }.average().toFloat()
                val cy = face.map { it.railY }.average().toFloat()
                val radii = face.map { hypot(it.railX - cx, it.railY - cy) }
                if (radii.max() - radii.min() < 2f) PlayerFaceArrangement.DIAMOND else PlayerFaceArrangement.CUSTOM
            }
            6 -> if (face.map { it.railY.roundToInt() }.distinct().size == 2) PlayerFaceArrangement.SIX_BUTTON else PlayerFaceArrangement.CUSTOM
            else -> PlayerFaceArrangement.CUSTOM
        }
    }
}

/** The smallest rectangle holding both. */
internal fun Rect.union(other: Rect): Rect =
    Rect(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))
