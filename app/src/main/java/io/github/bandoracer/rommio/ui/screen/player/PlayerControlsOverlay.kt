package io.github.bandoracer.rommio.ui.screen.player

import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.bandoracer.rommio.BuildConfig
import io.github.bandoracer.rommio.ui.component.RoleText
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * The player's touch controls (docs/player-controls.md): one multi-touch surface owns every
 * control; control composables only draw and carry the `player.*` test tags and semantics; the
 * title strip (portrait on a phone) and the Menu button sit on top. Where things go is
 * [PlayerTouchLayout]'s; how they look, the style renderer's; what touches do,
 * [PlayerTouchTracker]'s.
 */

/**
 * Debug builds' record of what the touch controls sent to the core and which haptics they played,
 * in order. Instrumentation tests read it (they run in the app's process); release builds keep
 * nothing.
 */
object PlayerInputLog {
    private val lock = Any()
    private val entries = ArrayDeque<PlayerTouchOutput>()
    private const val LIMIT = 4000

    fun record(output: PlayerTouchOutput) {
        if (!BuildConfig.DEBUG) return
        synchronized(lock) {
            entries.addLast(output)
            while (entries.size > LIMIT) entries.removeFirst()
        }
    }

    fun snapshot(): List<PlayerTouchOutput> = synchronized(lock) { entries.toList() }

    fun clear() = synchronized(lock) { entries.clear() }
}

/**
 * The touch surface's state: the tracker, what each control draws (pressed, the d-pad's held keys,
 * the sticks' rings and knobs) and where its output goes. Main thread only; every input goes out
 * at once, in touch order.
 */
@Stable
class PlayerTouchSurfaceState internal constructor() {
    internal var tracker = PlayerTouchTracker(PlayerTouchLayout.Empty)
        private set
    internal var onInput: (PlayerTouchOutput) -> Unit = {}
    internal var haptics = true
    internal var view: View? = null
    internal var density = 1f
    internal var active = true
        private set

    private val pressed = mutableMapOf<String, MutableState<Boolean>>()
    private val sticks = mutableMapOf<String, MutableState<PlayerStickPosition?>>()
    private val dpadHeld = mutableStateOf(emptySet<Int>())
    private var nextSyntheticTouch = -1L

    fun pressedState(id: String): State<Boolean> = pressed.getOrPut(id) { mutableStateOf(id in tracker.pressedControls) }
    /** A held stick's floating centre and knob; null at rest. */
    fun stickState(id: String): State<PlayerStickPosition?> = sticks.getOrPut(id) { mutableStateOf(tracker.stickPositions[id]) }
    val dpadKeys: State<Set<Int>> get() = dpadHeld

    internal fun setLayout(layout: PlayerTouchLayout) {
        if (layout == tracker.layout) return
        perform(tracker.update(layout))
        refresh()
    }

    internal fun setActive(active: Boolean) {
        if (active == this.active) return
        this.active = active
        // Pause (or anything else that hides the controls) lets go of everything.
        if (!active) releaseAll()
    }

    /** Lets go of every held input: pause, background, cancelled touches, the overlay going away. */
    fun releaseAll() {
        perform(tracker.releaseAll())
        refresh()
    }

    internal fun handle(event: PointerEvent) {
        for (change in event.changes) {
            val id = change.id.value
            val point = Offset(change.position.x / density, change.position.y / density)
            when {
                change.pressed && !change.previousPressed -> {
                    // A touch another handler took (Menu) or one while paused is not ours.
                    if (!active || change.isConsumed) continue
                    // A touch that misses every control (or lands on the game) is not consumed:
                    // it reaches the game view below.
                    val output = tracker.began(id, point)
                    if (tracker.tracks(id)) change.consume()
                    perform(output)
                }
                change.pressed -> if (tracker.tracks(id)) {
                    change.consume()
                    perform(tracker.moved(id, point))
                }
                change.previousPressed -> if (tracker.tracks(id)) {
                    change.consume()
                    perform(tracker.ended(id))
                }
            }
        }
        refresh()
    }

    /** Presses a control for accessibility (TalkBack double tap, UI tests' click). */
    internal fun activate(id: String) {
        val control = tracker.layout.control(id) ?: return
        val touch = nextSyntheticTouch--
        perform(tracker.began(touch, control.center))
        perform(tracker.ended(touch))
        refresh()
    }

    internal fun activateDirection(key: Int) {
        val pad = tracker.layout.dpad ?: return
        val reach = pad.frame.width * 0.35f
        val offset = when (key) {
            KeyEvent.KEYCODE_DPAD_UP -> Offset(0f, -reach)
            KeyEvent.KEYCODE_DPAD_DOWN -> Offset(0f, reach)
            KeyEvent.KEYCODE_DPAD_LEFT -> Offset(-reach, 0f)
            else -> Offset(reach, 0f)
        }
        val touch = nextSyntheticTouch--
        perform(tracker.began(touch, pad.center + offset))
        perform(tracker.ended(touch))
        refresh()
    }

    private fun perform(output: List<PlayerTouchOutput>) {
        for (item in output) {
            when (item) {
                is PlayerTouchOutput.Digital, is PlayerTouchOutput.Motion -> {
                    PlayerInputLog.record(item)
                    onInput(item)
                }
                PlayerTouchOutput.PressHaptic -> if (haptics) {
                    PlayerInputLog.record(item)
                    view?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
                PlayerTouchOutput.DirectionHaptic, PlayerTouchOutput.FullTiltHaptic -> if (haptics) {
                    PlayerInputLog.record(item)
                    view?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
        }
    }

    /** Redraws the controls whose pressed state changed. */
    private fun refresh() {
        val held = tracker.pressedControls
        for ((id, state) in pressed) {
            val value = id in held
            if (state.value != value) state.value = value
        }
        val positions = tracker.stickPositions
        for ((id, stick) in sticks) {
            val value = positions[id]
            if (stick.value != value) stick.value = value
        }
        val keys = tracker.dpadKeys
        if (dpadHeld.value != keys) dpadHeld.value = keys
    }
}

/** The one pointer handler over the overlay (docs/player-controls.md, "One surface"). */
private class PlayerTouchSurfaceElement(private val state: PlayerTouchSurfaceState) : ModifierNodeElement<PlayerTouchSurfaceNode>() {
    override fun create() = PlayerTouchSurfaceNode(state)
    override fun update(node: PlayerTouchSurfaceNode) {
        node.state = state
    }
    override fun equals(other: Any?) = other is PlayerTouchSurfaceElement && other.state === state
    override fun hashCode() = System.identityHashCode(state)
    override fun InspectorInfo.inspectableProperties() {
        name = "playerTouchSurface"
    }
}

private class PlayerTouchSurfaceNode(var state: PlayerTouchSurfaceState) : Modifier.Node(), PointerInputModifierNode {
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        // Main pass: after Menu (a child) has taken its own touches.
        if (pass == PointerEventPass.Main) state.handle(pointerEvent)
    }

    override fun onCancelPointerInput() = state.releaseAll()

    /** Touches that miss every control still reach the game view below (touch-screen cores). */
    override fun sharePointerInputWithSiblings(): Boolean = true

    override fun onDetach() = state.releaseAll()
}

/**
 * The touch controls over the player. [showControls] hides the controls (and lets go of
 * everything) while paused; Menu always shows. [haptics] plays `VIRTUAL_KEY` on button presses and
 * `CLOCK_TICK` on d-pad changes (the system's haptics setting still applies).
 */
@Composable
fun PlayerControlsOverlay(
    layout: PlayerTouchLayout,
    visual: PlayerVisualTheme,
    opacity: Float,
    showControls: Boolean,
    haptics: Boolean,
    onInput: (PlayerTouchOutput) -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    detail: String? = null,
) {
    val state = remember { PlayerTouchSurfaceState() }
    val view = LocalView.current
    val density = LocalDensity.current.density
    val input by rememberUpdatedState(onInput)
    // Applied with the frame that shows this layout, before any touch can reach it.
    SideEffect {
        state.view = view
        state.density = density
        state.haptics = haptics
        state.onInput = { input(it) }
        state.setLayout(layout)
        state.setActive(showControls)
    }
    DisposableEffect(state) { onDispose { state.releaseAll() } }
    // Leaving the foreground lets go of everything.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, state) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) state.releaseAll() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Box(modifier.fillMaxSize().then(PlayerTouchSurfaceElement(state)).testTag("player.touchControls")) {
        if (showControls) {
            layout.controls.forEach { control ->
                key(control.id) {
                    when (control.kind) {
                        PlayerTouchKind.DPAD -> DpadView(control, visual, opacity, state)
                        PlayerTouchKind.STICK -> StickView(control, visual, opacity, state)
                        else -> ControlView(control, visual, opacity, state)
                    }
                }
            }
        }
        val strip = layout.titleStrip
        if (strip != null && title != null) TitleStrip(strip, title, detail, visual)
        MenuButton(layout, visual, opacity, onMenu)
    }
}

private fun Modifier.placedAt(frame: Rect): Modifier = absoluteOffset(frame.left.dp, frame.top.dp).size(frame.width.dp, frame.height.dp)

private fun PlacedPlayerControl.drawnKind(): PlayerControlKind = when (kind) {
    PlayerTouchKind.FACE -> PlayerControlKind.FACE
    PlayerTouchKind.SHOULDER -> PlayerControlKind.SHOULDER
    PlayerTouchKind.SYSTEM -> PlayerControlKind.TERTIARY
    PlayerTouchKind.STICK -> PlayerControlKind.STICK
    PlayerTouchKind.DPAD -> PlayerControlKind.DPAD_ARM
}

/** One control, drawn; it takes no touches (the surface does). */
@Composable
private fun ControlView(control: PlacedPlayerControl, visual: PlayerVisualTheme, opacity: Float, state: PlayerTouchSurfaceState) {
    val pressed by state.pressedState(control.id)
    // Pressed: a quick 94 % dip (Aurora); Riso presses into its plate instead.
    val scale by animateFloatAsState(if (pressed) visual.style.pressedScale else 1f, tween(60), label = "press")
    val label = control.spec.label
    Box(
        Modifier
            .placedAt(control.frame)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .testTag("player.control.$label")
            .semantics {
                contentDescription = label
                role = Role.Button
                stateDescription = if (pressed) "Pressed" else "Released"
                onClick {
                    state.activate(control.id)
                    true
                }
            },
    ) {
        PlayerControlGraphic(
            kind = control.drawnKind(),
            label = label,
            pressed = pressed,
            visual = visual,
            opacity = opacity,
            rightSide = !control.spec.left,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * A stick, drawn (docs/player-controls.md, "Touch sticks"): its view is its whole zone (touches
 * anywhere in it take the stick); the travel ring draws at the centre (where the thumb set it down,
 * dragged by follow) and brightens at full tilt, the knob at the output. At rest both sit at the
 * zone's centre. They move in the layout phase, so a moving thumb never recomposes.
 */
@Composable
private fun StickView(control: PlacedPlayerControl, visual: PlayerVisualTheme, opacity: Float, state: PlayerTouchSurfaceState) {
    val position = state.stickState(control.id)
    val held by remember(position) { derivedStateOf { position.value != null } }
    val fullTilt by remember(position) { derivedStateOf { position.value?.fullTilt == true } }
    val label = control.spec.label
    val travel = max(control.travel, 12f)
    val knob = travel * 0.95f
    val origin = control.frame.topLeft
    fun Offset.local(size: Float): Offset = Offset(x - origin.x - size / 2, y - origin.y - size / 2)
    Box(
        Modifier
            .placedAt(control.frame)
            .testTag("player.control.$label")
            .semantics {
                contentDescription = label
                role = Role.Button
                stateDescription = if (held) "Pressed" else "Released"
            },
    ) {
        PlayerControlGraphic(
            kind = PlayerControlKind.STICK,
            label = label,
            pressed = held,
            visual = visual,
            opacity = opacity,
            rightSide = !control.spec.left,
            modifier = Modifier
                .offset {
                    val centre = (position.value?.center ?: control.center).local(2 * travel)
                    IntOffset((centre.x * density).roundToInt(), (centre.y * density).roundToInt())
                }
                .size((2 * travel).dp),
        )
        if (fullTilt) {
            PlayerStickFullTiltRing(
                visual = visual,
                modifier = Modifier
                    .offset {
                        val centre = (position.value?.center ?: control.center).local(2 * travel)
                        IntOffset((centre.x * density).roundToInt(), (centre.y * density).roundToInt())
                    }
                    .size((2 * travel).dp),
            )
        }
        PlayerStickKnob(
            visual = visual,
            opacity = opacity,
            modifier = Modifier
                .offset {
                    val at = (position.value?.knob ?: control.center).local(knob)
                    IntOffset((at.x * density).roundToInt(), (at.y * density).roundToInt())
                }
                .size(knob.dp),
        )
    }
}

private val directionOfKey = mapOf(
    KeyEvent.KEYCODE_DPAD_UP to PlayerDirection.UP,
    KeyEvent.KEYCODE_DPAD_DOWN to PlayerDirection.DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT to PlayerDirection.LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT to PlayerDirection.RIGHT,
)

/** The d-pad, drawn. Four direction boxes keep `player.control.Up` … for accessibility and tests. */
@Composable
private fun DpadView(control: PlacedPlayerControl, visual: PlayerVisualTheme, opacity: Float, state: PlayerTouchSurfaceState) {
    val keys by state.dpadKeys
    val held = remember(keys) { keys.mapNotNull { directionOfKey[it] }.toSet() }
    val third = control.frame.width / 3
    Box(Modifier.placedAt(control.frame).testTag("player.dpad")) {
        PlayerDpadGraphic(held = held, visual = visual, opacity = opacity, modifier = Modifier.fillMaxSize())
        listOf(
            Triple("Up", KeyEvent.KEYCODE_DPAD_UP, 1 to 0),
            Triple("Down", KeyEvent.KEYCODE_DPAD_DOWN, 1 to 2),
            Triple("Left", KeyEvent.KEYCODE_DPAD_LEFT, 0 to 1),
            Triple("Right", KeyEvent.KEYCODE_DPAD_RIGHT, 2 to 1),
        ).forEach { (name, keyCode, cell) ->
            val down = keyCode in keys
            Box(
                Modifier
                    .absoluteOffset((third * cell.first).dp, (third * cell.second).dp)
                    .size(third.dp)
                    .testTag("player.control.$name")
                    .semantics {
                        contentDescription = name
                        role = Role.Button
                        stateDescription = if (down) "Pressed" else "Released"
                        onClick {
                            state.activateDirection(keyCode)
                            true
                        }
                    },
            )
        }
    }
}

/** The Menu (pause) button: an ordinary button, drawn by the style renderer. */
@Composable
private fun MenuButton(layout: PlayerTouchLayout, visual: PlayerVisualTheme, opacity: Float, onMenu: () -> Unit) {
    val d = layout.menuDiameter
    val width = maxOf(d, 52f)
    Box(
        Modifier
            .absoluteOffset((layout.menuCenter.x - width / 2).dp, (layout.menuCenter.y - d / 2).dp)
            .size(width.dp, d.dp)
            .testTag("player.menu")
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                // A round press light that follows the drawn control, not the hit box.
                indication = ripple(bounded = false, radius = (d / 2).dp),
                role = Role.Button,
                onClickLabel = "Menu",
                onClick = onMenu,
            )
            .semantics { contentDescription = "Menu" },
        contentAlignment = Alignment.Center,
    ) {
        PlayerControlGraphic(
            PlayerControlKind.MENU, "Menu", pressed = false, visual = visual,
            opacity = opacity.coerceAtLeast(PlayerStyle.DEFAULT_OPACITY),
            modifier = if (visual.style.isInk) Modifier.fillMaxSize() else Modifier.size(d.dp),
        )
    }
}

/**
 * The game's name and its play line under the game (portrait, docs/player-controls.md): Aurora
 * `headline` 18 with the line in `secondary`, side by side when they fit, else stacked; Riso
 * `headline` over a `label`.
 */
@Composable
private fun TitleStrip(strip: Rect, title: String, detail: String?, visual: PlayerVisualTheme) {
    val theme = RommioTheme.current
    val ink = visual.style.colors.controlInk
    Box(
        Modifier
            .placedAt(strip)
            .testTag("player.titleStrip")
            .semantics(mergeDescendants = true) {},
    ) {
        if (visual.style.isInk) {
            androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp)) {
                RoleText(title, theme.type.headline, size = 22f, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                detail?.let { RoleText(it, theme.type.label, size = 10.5f, color = ink.copy(alpha = ink.alpha * 0.72f), maxLines = 1) }
            }
        } else {
            FitRowOrStack(spacing = 12f) {
                RoleText(title, theme.type.headline, size = 18f, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                detail?.let { RoleText(it, theme.type.secondary, size = 13f, color = ink.copy(alpha = ink.alpha * 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/** Two texts side by side (the second at the trailing edge) when they fit, else one over the other. */
@Composable
private fun FitRowOrStack(spacing: Float, content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val loose = Constraints(maxWidth = constraints.maxWidth)
        val gap = (spacing * density).toInt()
        val natural = measurables.map { it.maxIntrinsicWidth(Int.MAX_VALUE) }
        val fits = natural.sum() + gap * (measurables.size - 1) <= constraints.maxWidth
        val placeables = measurables.map { it.measure(loose) }
        if (fits && placeables.size == 2) {
            val height = placeables.maxOf { it.height }
            layout(constraints.maxWidth, height) {
                val first = placeables[0]
                val second = placeables[1]
                val baseline = { p: androidx.compose.ui.layout.Placeable -> p[androidx.compose.ui.layout.FirstBaseline].takeIf { it != androidx.compose.ui.layout.AlignmentLine.Unspecified } ?: p.height }
                val line = maxOf(baseline(first), baseline(second))
                first.placeRelative(0, line - baseline(first))
                second.placeRelative(constraints.maxWidth - second.width, line - baseline(second))
            }
        } else {
            val height = placeables.sumOf { it.height } + (2 * density).toInt() * (placeables.size - 1)
            layout(constraints.maxWidth, height) {
                var y = 0
                placeables.forEach {
                    it.placeRelative(0, y)
                    y += it.height + (2 * density).toInt()
                }
            }
        }
    }
}
