package io.github.bandoracer.rommio.ui.screen.player

import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.bandoracer.rommio.domain.input.PlayerStickTuning
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The touch model of docs/player-controls.md, pure: hit regions, the chord band, rolling with
 * hysteresis, the d-pad's dead zone and 8-way sectors, and a tracker that turns touches into
 * ordered presses and releases. The touch surface (`PlayerControlsOverlay`) only forwards pointer
 * changes and performs the output. Names follow iOS `PlayerTouchTracker`.
 */

/** A d-pad direction as the keys it holds. Ordinals match the sector order (0° = right, clockwise on screen). */
enum class PlayerDPadDirection {
    NONE, RIGHT, DOWN_RIGHT, DOWN, DOWN_LEFT, LEFT, UP_LEFT, UP, UP_RIGHT;

    val keys: Set<Int>
        get() = when (this) {
            NONE -> emptySet()
            RIGHT -> setOf(KeyEvent.KEYCODE_DPAD_RIGHT)
            DOWN_RIGHT -> setOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
            DOWN -> setOf(KeyEvent.KEYCODE_DPAD_DOWN)
            DOWN_LEFT -> setOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)
            LEFT -> setOf(KeyEvent.KEYCODE_DPAD_LEFT)
            UP_LEFT -> setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT)
            UP -> setOf(KeyEvent.KEYCODE_DPAD_UP)
            UP_RIGHT -> setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT)
        }

    /** The sector's centre in degrees (0 = right, 90 = down: screen coordinates). */
    internal val angle: Double get() = (ordinal - 1) * 45.0
    internal val isDiagonal: Boolean get() = ordinal % 2 == 0 && this != NONE
}

object PlayerTouchRules {
    /** A two-button chord: within this share of the centre distance of their midpoint. */
    const val CHORD_BAND = 0.22f
    /** Leaving the chord needs a little more than entering it. */
    const val CHORD_HYSTERESIS = 4f
    /** A touch that drifts off every control keeps its control until this far beyond it. */
    const val RELEASE_DISTANCE = 40f
    /** Rolling keeps the current control until another is clearly nearer (normalised distance). */
    const val ROLL_HYSTERESIS = 0.12f
    /** D-pad dead zone as a share of the pad's radius (smaller once a direction is held). */
    const val DEAD_ZONE = 0.18f
    const val HELD_DEAD_ZONE = 0.15f
    /** Diagonal sectors are this wide (degrees); cardinal ones take the rest (50°). */
    const val DIAGONAL_SECTOR = 40.0
    /** A held direction keeps its sector this much wider (degrees). */
    const val SECTOR_HYSTERESIS = 4.0

    /** The d-pad direction for a touch at ([dx], [dy]) from the pad's centre. */
    fun dpadDirection(dx: Float, dy: Float, radius: Float, previous: PlayerDPadDirection = PlayerDPadDirection.NONE): PlayerDPadDirection {
        val distance = hypot(dx, dy)
        val dead = (if (previous == PlayerDPadDirection.NONE) DEAD_ZONE else HELD_DEAD_ZONE) * max(radius, 1f)
        if (distance < dead) return PlayerDPadDirection.NONE
        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
        if (angle < 0) angle += 360.0
        fun delta(a: Double, b: Double): Double {
            val d = abs(a - b) % 360.0
            return min(d, 360.0 - d)
        }
        fun halfWidth(direction: PlayerDPadDirection): Double =
            if (direction.isDiagonal) DIAGONAL_SECTOR / 2 else (90.0 - DIAGONAL_SECTOR) / 2
        if (previous != PlayerDPadDirection.NONE && delta(angle, previous.angle) <= halfWidth(previous) + SECTOR_HYSTERESIS) return previous
        return PlayerDPadDirection.entries.drop(1).firstOrNull { delta(angle, it.angle) <= halfWidth(it) } ?: PlayerDPadDirection.NONE
    }

    /** How far a point is into a control: 0 at its centre, 1 at its drawn edge. */
    fun normalizedDistance(point: Offset, control: PlacedPlayerControl): Float {
        val dx = point.x - control.center.x
        val dy = point.y - control.center.y
        val rx = max(control.frame.width / 2, 1f)
        val ry = max(control.frame.height / 2, 1f)
        return if (control.shape == PlayerTouchShape.CIRCLE) hypot(dx, dy) / rx else hypot(dx / rx, dy / ry)
    }

    /** The point is inside the control's hit region (its shape grown by the hit growth). */
    fun hits(point: Offset, control: PlacedPlayerControl): Boolean {
        if (control.shape == PlayerTouchShape.CIRCLE) {
            return hypot(point.x - control.center.x, point.y - control.center.y) <= control.frame.width / 2 + PlayerControlMetrics.HIT_GROWTH
        }
        val box = control.hitFrame
        return point.x >= box.left && point.x <= box.right && point.y >= box.top && point.y <= box.bottom
    }

    /** Distance from the point to the control's hit region (0 inside). */
    fun distanceBeyond(point: Offset, control: PlacedPlayerControl): Float {
        val grow = PlayerControlMetrics.HIT_GROWTH
        if (control.shape == PlayerTouchShape.CIRCLE) {
            return max(0f, hypot(point.x - control.center.x, point.y - control.center.y) - control.frame.width / 2 - grow)
        }
        val box = control.hitFrame
        val dx = maxOf(box.left - point.x, 0f, point.x - box.right)
        val dy = maxOf(box.top - point.y, 0f, point.y - box.bottom)
        return hypot(dx, dy)
    }
}

/**
 * A touch stick's profile (docs/player-controls.md, "Touch sticks"): travel (dp of thumb travel for
 * full tilt, before saturation), dead zone (share of travel with no output), saturation (share of
 * travel after the dead zone that already reads as full tilt), response curve, the gate's reach
 * per axis at 45° (0.7071 = circle; higher = octagon or square) and whether the centre follows a
 * thumb past full travel. `scripts/touch_stick_reference.py` pins the numbers and the maths.
 */
data class PlayerStickProfile(
    val name: String,
    val travel: Double,
    val deadZone: Double,
    val saturation: Double,
    val curve: Double,
    val diagonalReach: Double,
    val follow: Boolean,
) {
    /**
     * The profile at a control size and a game's tuning: Sensitivity scales the travel (Low × 1.2,
     * High × 0.8), Dead zone the dead zone (Small × 0.6, Large × 1.6); a Fixed centre never follows.
     */
    fun tuned(tuning: PlayerStickTuning, scale: Float = 1f): PlayerStickProfile = copy(
        travel = travel * scale * tuning.sensitivity.travelFactor,
        deadZone = deadZone * tuning.deadZone.factor,
        follow = follow && tuning.centre == PlayerStickTuning.Centre.FLOATING,
    )

    companion object {
        val N64 = PlayerStickProfile("n64", travel = 48.0, deadZone = 0.10, saturation = 0.90, curve = 1.4, diagonalReach = 0.86, follow = true)
        val THREE_DS = PlayerStickProfile("3ds", travel = 44.0, deadZone = 0.10, saturation = 0.92, curve = 1.2, diagonalReach = 0.7071, follow = true)
        val PSP = PlayerStickProfile("psp", travel = 36.0, deadZone = 0.12, saturation = 0.90, curve = 1.0, diagonalReach = 0.7071, follow = true)
        val PSX = PlayerStickProfile("psx", travel = 44.0, deadZone = 0.10, saturation = 0.90, curve = 1.3, diagonalReach = 0.95, follow = true)
        val PSX_RIGHT = PlayerStickProfile("psx-right", travel = 40.0, deadZone = 0.12, saturation = 0.90, curve = 1.6, diagonalReach = 0.95, follow = true)

        /** Every profile, keyed as the reference script prints them. */
        val all: Map<String, PlayerStickProfile> = listOf(N64, THREE_DS, PSP, PSX, PSX_RIGHT).associateBy { it.name }

        /** A console's stick: [stick] 0 is the left stick, 1 the right. (PlayStation's are the fallback.) */
        fun forStick(family: String, stick: Int): PlayerStickProfile = when (family) {
            "n64" -> N64
            "3ds" -> THREE_DS
            "psp" -> PSP
            else -> if (stick == 1) PSX_RIGHT else PSX
        }
    }
}

/**
 * The touch stick maths (docs/player-controls.md, "Touch sticks"), an exact port of
 * `scripts/touch_stick_reference.py` (`gate_scale`, `stick_output`, `follow_centre`, `full_tilt`);
 * the unit tests assert the vectors the script prints. Points are dp; y grows downward.
 */
object PlayerStickModel {
    /**
     * The stick's centre for a touch-down at [point] (floating): exactly where the thumb landed.
     * The zone only decides which touches start the stick; clamping the centre inward made a
     * touch near the zone's edge read as instant tilt. The ring may draw past the zone.
     */
    @Suppress("UNUSED_PARAMETER")
    fun center(point: Offset, zone: Rect, travel: Float): Offset = point

    /**
     * How far full tilt reaches along [angle] for a gate whose 45° corner is at ([diagonalReach],
     * [diagonalReach]): 1 at the cardinals, straight edges between. A reach of 0.7071 is a true
     * circle (1 everywhere).
     */
    fun gateScale(angle: Double, diagonalReach: Double): Double {
        if (abs(diagonalReach - sqrt(0.5)) < 1e-4) return 1.0 // 0.7071 in the profiles means a circle
        var folded = abs(angle) % (PI / 2)
        if (folded > PI / 4) folded = PI / 2 - folded
        return 1.0 / (cos(folded) + sin(folded) * (1.0 - diagonalReach) / diagonalReach)
    }

    /** Thumb offset from the stick centre (dp) → (x, y) in −1…1 for the core. */
    fun output(dx: Double, dy: Double, profile: PlayerStickProfile): Pair<Double, Double> {
        val distance = hypot(dx, dy)
        if (distance == 0.0) return 0.0 to 0.0
        var m = min(distance / profile.travel, 1.0)
        if (m <= profile.deadZone) return 0.0 to 0.0
        m = (m - profile.deadZone) / (1.0 - profile.deadZone)
        m = min(m / profile.saturation, 1.0)
        m = m.pow(profile.curve)
        val angle = atan2(dy, dx)
        val reach = m * gateScale(angle, profile.diagonalReach)
        return (cos(angle) * reach).coerceIn(-1.0, 1.0) to (sin(angle) * reach).coerceIn(-1.0, 1.0)
    }

    /** With `follow`, a thumb past full travel drags the centre so it sits exactly at the rim. */
    fun followCentre(cx: Double, cy: Double, tx: Double, ty: Double, profile: PlayerStickProfile): Pair<Double, Double> {
        val dx = tx - cx
        val dy = ty - cy
        val distance = hypot(dx, dy)
        if (!profile.follow || distance <= profile.travel) return cx to cy
        val pull = 1.0 - profile.travel / distance
        return (cx + dx * pull) to (cy + dy * pull)
    }

    /** Where the full-tilt haptic tick fires (entering saturation). */
    fun fullTilt(dx: Double, dy: Double, profile: PlayerStickProfile): Boolean {
        val m = min(hypot(dx, dy) / profile.travel, 1.0)
        if (m <= profile.deadZone) return false
        return (m - profile.deadZone) / (1.0 - profile.deadZone) >= profile.saturation
    }
}

/** Where a held stick is drawn: its centre (the ring), the knob at the output, and whether it is at full tilt (the ring brightens). */
data class PlayerStickPosition(val center: Offset, val knob: Offset, val fullTilt: Boolean = false)

/** What a touch lands on. */
sealed interface PlayerTouchTarget {
    data object Dpad : PlayerTouchTarget
    /** One control, or both buttons of a two-button chord. */
    data class Buttons(val ids: List<String>) : PlayerTouchTarget
    data class Stick(val id: String) : PlayerTouchTarget
}

/** The control a new touch at [point] goes to, or null (the touch falls through to the game). */
fun PlayerTouchLayout.target(point: Offset): PlayerTouchTarget? {
    // A touch on a game screen goes to the game.
    if (gameScreens.any { it.contains(point) }) return null
    val candidates = controls.filter { PlayerTouchRules.hits(point, it) }.toMutableList()
    // Inside the face cluster's zone there are no dead gaps: the nearest button takes it.
    val zone = faceZone
    if (zone != null && zone.contains(point)) {
        val nearest = controls.filter { it.kind == PlayerTouchKind.FACE }.minByOrNull { PlayerTouchRules.normalizedDistance(point, it) }
        if (nearest != null && nearest !in candidates) candidates += nearest
    }
    val best = candidates.minByOrNull { PlayerTouchRules.normalizedDistance(point, it) } ?: return null
    return when (best.kind) {
        PlayerTouchKind.DPAD -> PlayerTouchTarget.Dpad
        PlayerTouchKind.STICK -> PlayerTouchTarget.Stick(best.id)
        PlayerTouchKind.FACE -> {
            val pair = chordPair
            if (pair != null && inChord(point, pair, holding = false)) PlayerTouchTarget.Buttons(pair.map { it.id })
            else PlayerTouchTarget.Buttons(listOf(best.id))
        }
        PlayerTouchKind.SHOULDER, PlayerTouchKind.SYSTEM -> PlayerTouchTarget.Buttons(listOf(best.id))
    }
}

/**
 * Where a rolling touch of [kind] goes as it moves: the same, another control of its kind, the
 * chord, or null when it is off every control of that kind.
 */
fun PlayerTouchLayout.rollTarget(point: Offset, kind: PlayerTouchKind, current: List<String>): List<String>? {
    val pair = chordPair
    if (kind == PlayerTouchKind.FACE && pair != null && inChord(point, pair, holding = current.size == 2)) return pair.map { it.id }
    val ofKind = controls.filter { it.kind == kind }
    var candidates = ofKind.filter { PlayerTouchRules.hits(point, it) }
    // Inside the face cluster's zone every button is in reach: the nearest wins, with hysteresis
    // for the one held.
    val zone = faceZone
    if (kind == PlayerTouchKind.FACE && zone != null && zone.contains(point)) candidates = ofKind
    fun distance(control: PlacedPlayerControl) = PlayerTouchRules.normalizedDistance(point, control)
    val best = candidates.minByOrNull(::distance) ?: return null
    // Hysteresis: keep the current control until another is clearly nearer.
    if (current.size == 1) {
        val held = candidates.firstOrNull { it.id == current[0] }
        if (held != null && distance(held) <= distance(best) + PlayerTouchRules.ROLL_HYSTERESIS) return current
    }
    return listOf(best.id)
}

/** The touch is further than the release distance beyond every control it holds. */
fun PlayerTouchLayout.driftedAway(point: Offset, ids: List<String>): Boolean {
    val held = controls.filter { it.id in ids }
    if (held.isEmpty()) return true
    return held.all { PlayerTouchRules.distanceBeyond(point, it) > PlayerTouchRules.RELEASE_DISTANCE }
}

internal val PlayerTouchLayout.chordPair: List<PlacedPlayerControl>?
    get() {
        val ids = chord ?: return null
        if (ids.size != 2) return null
        val pair = ids.mapNotNull { control(it) }
        return pair.takeIf { it.size == 2 }
    }

internal fun inChord(point: Offset, pair: List<PlacedPlayerControl>, holding: Boolean): Boolean {
    val a = pair[0].center
    val b = pair[1].center
    val mid = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
    val band = PlayerTouchRules.CHORD_BAND * hypot(a.x - b.x, a.y - b.y) + if (holding) PlayerTouchRules.CHORD_HYSTERESIS else 0f
    return hypot(point.x - mid.x, point.y - mid.y) <= band
}

/** What the tracker asks the player to do, in order. */
sealed interface PlayerTouchOutput {
    data class Digital(val key: Int, val pressed: Boolean) : PlayerTouchOutput
    /** A stick's position (0 = left stick, 1 = right stick). */
    data class Motion(val stick: Int, val x: Float, val y: Float) : PlayerTouchOutput
    /** A button was pressed (`VIRTUAL_KEY`). */
    data object PressHaptic : PlayerTouchOutput
    /** The d-pad changed to a new direction (`CLOCK_TICK`). */
    data object DirectionHaptic : PlayerTouchOutput
    /** A stick entered full tilt (one soft `CLOCK_TICK`; again only after it has left it). */
    data object FullTiltHaptic : PlayerTouchOutput
}

/**
 * Turns touches into ordered input. Several touches may hold controls at once; a key held by two
 * touches is released when the last lets go. Main-thread only.
 */
class PlayerTouchTracker(layout: PlayerTouchLayout) {
    var layout: PlayerTouchLayout = layout
        private set

    private sealed interface State {
        data class Dpad(val direction: PlayerDPadDirection) : State
        /** Holding these controls of a rolling kind (empty after drifting off). */
        data class Buttons(val kind: PlayerTouchKind, val ids: List<String>) : State
        /** A stick; its centre (set down, then dragged by follow) is in [stickPositions]. */
        data class Stick(val id: String) : State
    }

    private val touches = mutableMapOf<Long, State>()
    /** Order of touches, so releasing everything is deterministic. */
    private val order = mutableListOf<Long>()
    private val keyHolds = mutableMapOf<Int, Int>()
    private val axisHolds = mutableMapOf<String, Int>()
    private val controlHolds = mutableMapOf<String, Int>()
    private val directions = mutableMapOf<Long, PlayerDPadDirection>()
    private val stickValues = mutableMapOf<Int, Offset>()

    /** Held sticks, for drawing (ring and knob at the floating centre). */
    var stickPositions: Map<String, PlayerStickPosition> = emptyMap()
        private set

    /** Controls drawn pressed (sticks while held). */
    val pressedControls: Set<String> get() = controlHolds.filterValues { it > 0 }.keys.toSet()

    /** The d-pad's held keys (for drawing). */
    val dpadKeys: Set<Int> get() = directions.values.flatMapTo(mutableSetOf()) { it.keys }

    val isTracking: Boolean get() = touches.isNotEmpty()

    /** Whether [touch] is one the tracker follows. */
    fun tracks(touch: Long): Boolean = touch in touches

    /** A new layout (rotation, size): everything held is released first. */
    fun update(layout: PlayerTouchLayout): List<PlayerTouchOutput> {
        if (layout == this.layout) return emptyList()
        val output = releaseAll()
        this.layout = layout
        return output
    }

    fun began(touch: Long, point: Offset): List<PlayerTouchOutput> {
        if (touch in touches) return emptyList()
        val target = layout.target(point) ?: return emptyList()
        order += touch
        return when (target) {
            PlayerTouchTarget.Dpad -> {
                touches[touch] = State.Dpad(PlayerDPadDirection.NONE)
                moveDpad(touch, point)
            }
            is PlayerTouchTarget.Buttons -> {
                val kind = layout.control(target.ids[0])?.kind ?: PlayerTouchKind.FACE
                touches[touch] = State.Buttons(kind, target.ids)
                press(target.ids, haptic = true)
            }
            is PlayerTouchTarget.Stick -> {
                // A stick already held by another touch stays with it.
                val control = layout.control(target.id)
                if (target.id in stickPositions || control == null) {
                    order.remove(touch)
                    return emptyList()
                }
                // Floating: touch-down sets the centre; Fixed: the zone's centre.
                val center = if (control.fixedCentre) control.center else PlayerStickModel.center(point, control.frame, control.travel)
                touches[touch] = State.Stick(target.id)
                stickMotion(target.id, center, point, follow = false)
            }
        }
    }

    fun moved(touch: Long, point: Offset): List<PlayerTouchOutput> {
        return when (val state = touches[touch] ?: return emptyList()) {
            is State.Dpad -> moveDpad(touch, point)
            is State.Stick -> stickMotion(state.id, stickPositions[state.id]?.center ?: point, point, follow = true)
            is State.Buttons -> {
                val held = state.ids
                val next = layout.rollTarget(point, state.kind, held)
                if (next != null) {
                    if (next == held) return emptyList()
                    touches[touch] = State.Buttons(state.kind, next)
                    val released = held.filter { it !in next }
                    val pressed = next.filter { it !in held }
                    return release(released) + press(pressed, haptic = true)
                }
                // Off every control of its kind: hold until well beyond, then let go.
                if (held.isEmpty() || !layout.driftedAway(point, held)) return emptyList()
                touches[touch] = State.Buttons(state.kind, emptyList())
                release(held)
            }
        }
    }

    fun ended(touch: Long): List<PlayerTouchOutput> {
        val state = touches.remove(touch) ?: return emptyList()
        order.remove(touch)
        return when (state) {
            is State.Dpad -> {
                val previous = directions.remove(touch) ?: PlayerDPadDirection.NONE
                keysChanged(previous.keys, emptySet())
            }
            is State.Buttons -> release(state.ids)
            is State.Stick -> {
                controlHolds.remove(state.id)
                stickPositions = stickPositions - state.id
                val stick = layout.control(state.id)?.spec?.stick ?: return emptyList()
                stickValues.remove(stick)
                listOf(PlayerTouchOutput.Motion(stick, 0f, 0f))
            }
        }
    }

    /** Releases every held input (touches cancelled, pause, background, overlay gone). */
    fun releaseAll(): List<PlayerTouchOutput> {
        val output = mutableListOf<PlayerTouchOutput>()
        for (touch in order.toList()) output += ended(touch)
        touches.clear()
        order.clear()
        return output
    }

    // ---- Input ----

    private fun moveDpad(touch: Long, point: Offset): List<PlayerTouchOutput> {
        val pad = layout.dpad ?: return emptyList()
        val previous = directions[touch] ?: PlayerDPadDirection.NONE
        val next = PlayerTouchRules.dpadDirection(point.x - pad.center.x, point.y - pad.center.y, pad.frame.width / 2, previous)
        if (next == previous) return emptyList()
        directions[touch] = next
        touches[touch] = State.Dpad(next)
        val output = keysChanged(previous.keys, next.keys).toMutableList()
        if (next != PlayerDPadDirection.NONE) output += PlayerTouchOutput.DirectionHaptic
        return output
    }

    private fun keysChanged(old: Set<Int>, new: Set<Int>): List<PlayerTouchOutput> {
        val output = mutableListOf<PlayerTouchOutput>()
        for (key in (old - new).sorted()) output += releaseKey(key)
        for (key in (new - old).sorted()) output += pressKey(key)
        return output
    }

    private fun press(ids: List<String>, haptic: Boolean): List<PlayerTouchOutput> {
        val output = mutableListOf<PlayerTouchOutput>()
        var axisChanged = false
        for (id in ids) {
            val control = layout.control(id) ?: continue
            controlHolds[id] = (controlHolds[id] ?: 0) + 1
            if (control.spec.isAxisButton) {
                axisHolds[id] = (axisHolds[id] ?: 0) + 1
                axisChanged = true
            }
            for (key in control.spec.keys) output += pressKey(key)
        }
        if (axisChanged) output += axisMotion()
        if (haptic && ids.isNotEmpty()) output += PlayerTouchOutput.PressHaptic
        return output
    }

    private fun release(ids: List<String>): List<PlayerTouchOutput> {
        val output = mutableListOf<PlayerTouchOutput>()
        var axisChanged = false
        for (id in ids) {
            val control = layout.control(id) ?: continue
            val holds = (controlHolds[id] ?: 1) - 1
            if (holds <= 0) controlHolds.remove(id) else controlHolds[id] = holds
            if (control.spec.isAxisButton) {
                val axis = (axisHolds[id] ?: 1) - 1
                if (axis <= 0) axisHolds.remove(id) else axisHolds[id] = axis
                axisChanged = true
            }
            for (key in control.spec.keys) output += releaseKey(key)
        }
        if (axisChanged) output += axisMotion()
        return output
    }

    private fun pressKey(key: Int): List<PlayerTouchOutput> {
        val holds = (keyHolds[key] ?: 0) + 1
        keyHolds[key] = holds
        return if (holds == 1) listOf(PlayerTouchOutput.Digital(key, true)) else emptyList()
    }

    private fun releaseKey(key: Int): List<PlayerTouchOutput> {
        val count = keyHolds[key] ?: return emptyList()
        if (count <= 1) {
            keyHolds.remove(key)
            return listOf(PlayerTouchOutput.Digital(key, false))
        }
        keyHolds[key] = count - 1
        return emptyList()
    }

    /** Held axis buttons (N64 C buttons) push the right stick. */
    private fun axisMotion(): PlayerTouchOutput {
        val held = layout.controls.filter { it.id in axisHolds }
        val x = held.sumOf { it.spec.axisX.toDouble() }.toFloat().coerceIn(-1f, 1f)
        val y = held.sumOf { it.spec.axisY.toDouble() }.toFloat().coerceIn(-1f, 1f)
        return PlayerTouchOutput.Motion(1, x, y)
    }

    /**
     * A stick's value for the touch, sent now (and only when it changed). As the thumb moves past
     * full travel the centre follows it ([follow]; floating sticks); the touch keeps the stick
     * outside its zone until it lifts. Entering full tilt ticks once.
     */
    private fun stickMotion(id: String, center: Offset, point: Offset, follow: Boolean): List<PlayerTouchOutput> {
        val control = layout.control(id) ?: return emptyList()
        val stick = control.spec.stick ?: return emptyList()
        val profile = control.stickProfile ?: return emptyList()
        controlHolds[id] = 1
        val (cx, cy) = if (follow && !control.fixedCentre) {
            PlayerStickModel.followCentre(center.x.toDouble(), center.y.toDouble(), point.x.toDouble(), point.y.toDouble(), profile)
        } else {
            center.x.toDouble() to center.y.toDouble()
        }
        val dx = point.x - cx
        val dy = point.y - cy
        val (x, y) = PlayerStickModel.output(dx, dy, profile)
        val tilt = PlayerStickModel.fullTilt(dx, dy, profile)
        val wasTilted = stickPositions[id]?.fullTilt == true
        val centre = Offset(cx.toFloat(), cy.toFloat())
        val travel = profile.travel.toFloat()
        stickPositions = stickPositions + (id to PlayerStickPosition(centre, Offset(centre.x + x.toFloat() * travel, centre.y + y.toFloat() * travel), tilt))
        val output = mutableListOf<PlayerTouchOutput>()
        // (cos and sin leave ~1e-17 on the cardinals: that is 0.)
        fun axis(v: Double): Float = if (abs(v) < 1e-6) 0f else v.toFloat()
        val value = Offset(axis(x), axis(y))
        if (stickValues[stick] != value) {
            stickValues[stick] = value
            output += PlayerTouchOutput.Motion(stick, value.x, value.y)
        }
        if (tilt && !wasTilted) output += PlayerTouchOutput.FullTiltHaptic
        return output
    }
}
