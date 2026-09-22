package io.github.bandoracer.rommio.ui.screen.player

import android.view.MotionEvent
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.bandoracer.rommio.domain.input.ExpansionControls
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.text.font.FontWeight
import io.github.bandoracer.rommio.domain.input.ExpansionControl
import io.github.bandoracer.rommio.domain.player.PlayerMotionSource
import kotlin.math.*

@Composable
fun ExpansionControlsOverlay(
    controls: List<ExpansionControl>, leftHanded: Boolean, scale: Float, opacity: Float,
    showControls: Boolean, bottomInset: Dp, sideInset: Dp = 0.dp, topInset: Dp = 0.dp,
    onDigital: (Int, Boolean) -> Unit, onMotion: (PlayerMotionSource, Float, Float) -> Unit,
    onMenu: () -> Unit, theme: PlayerVisualTheme? = null, aspectRatio: Float? = null,
) {
    val heldC = remember { mutableSetOf<String>() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val deck = PlayerControlDeckLayout(maxWidth.value, maxHeight.value, topInset.value, bottomInset.value, sideInset.value, ExpansionControls.deckHeight(controls), scale, showControls, aspectRatio, if (showControls) controls.count { it.center } + 1 else 1)
        val factor = deck.scale
        val utility = if (showControls) controls.filter { it.center } else emptyList()
        val menuIndex = utility.size / 2
        if (showControls && controls.any { it.key == KeyEvent.KEYCODE_DPAD_UP }) {
            ConsoleDPad(theme?.neutralControlStyle, opacity,
                Modifier.offset(((if (leftHanded) deck.rightOrigin else deck.leftOrigin) + 2 * factor).dp,
                    (deck.railTop + 50 * factor).dp).size((144 * factor).dp), onDigital)
        }
        if (showControls) controls.filter { it.key !in directionKeys }.forEach { control ->
            val left = control.left != leftHanded
            val origin = if (left) deck.leftOrigin else deck.rightOrigin
            val diameter = (if (control.center) 48f else control.size * factor).dp
            val utilityIndex = utility.indexOf(control)
            val x = if (control.center) deck.utilityX(if (utilityIndex < menuIndex) utilityIndex else utilityIndex + 1, utility.size + 1) else origin + control.x * factor
            val y = if (control.center) deck.utilityY else deck.railTop + control.y * factor
            ConsoleTouchSurface(control.label, control.stick != null, control.label == "Z" && control.key == KeyEvent.KEYCODE_BUTTON_L2, opacity, theme?.faceStyle(control.label),
                Modifier.offset(x.dp - diameter / 2, y.dp - diameter / 2).size(diameter)) { pressed, x, y ->
                when {
                    control.combo.isNotEmpty() -> control.combo.forEach { onDigital(it, pressed) }
                    control.key != null -> onDigital(control.key, pressed)
                    control.stick != null -> onMotion(if (control.stick == 0) PlayerMotionSource.ANALOG_LEFT else PlayerMotionSource.ANALOG_RIGHT, x, y)
                    else -> {
                        if (pressed) heldC.add(control.id) else heldC.remove(control.id)
                        val held = controls.filter { it.id in heldC }
                        onMotion(PlayerMotionSource.ANALOG_RIGHT, held.sumOf { it.axisX.toDouble() }.toFloat().coerceIn(-1f,1f), held.sumOf { it.axisY.toDouble() }.toFloat().coerceIn(-1f,1f))
                    }
                }
            }
        }
        FilledTonalButton(onClick = onMenu, modifier = Modifier.offset((deck.utilityX(menuIndex, utility.size + 1) - 24).dp, (deck.utilityY - 22).dp).size(48.dp,44.dp).testTag("player.menu"),
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = theme?.panelAltColor ?: Color(0xFF242832), contentColor = Color.White),
            shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(0.dp)) { Text("≡", fontSize = 24.sp) }

    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ConsoleTouchSurface(label: String, stick: Boolean, trigger: Boolean, opacity: Float, style: PlayerControlStyle?, modifier: Modifier, onValue: (Boolean, Float, Float) -> Unit) {
    val callback by rememberUpdatedState(onValue)
    var active by remember { mutableStateOf<Int?>(null) }
    var downSent by remember { mutableStateOf(false) }
    var x by remember { mutableFloatStateOf(0f) }
    var y by remember { mutableFloatStateOf(0f) }
    var bounds by remember { mutableStateOf(IntSize.Zero) }
    fun release() { if (active != null) { active = null; downSent = false; x = 0f; y = 0f; callback(false, 0f, 0f) } }
    DisposableEffect(Unit) { onDispose { release() } }
    Box(modifier.onSizeChanged { bounds = it }.testTag("player.control.$label")
        .semantics { contentDescription = label; role = Role.Button; stateDescription = if (active == null) "Released" else "Pressed"; if (!stick) onClick { callback(true, 0f, 0f); callback(false, 0f, 0f); true } }
        .pointerInteropFilter { event ->
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val index = event.actionIndex
                    if (active == null && event.getX(index) >= 0 && event.getX(index) < bounds.width && event.getY(index) >= 0 && event.getY(index) < bounds.height) {
                        active = event.getPointerId(index)
                    }
                }
                MotionEvent.ACTION_CANCEL -> release()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> if (active == event.getPointerId(event.actionIndex)) release()
            }
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
                val index = active?.let { event.findPointerIndex(it) } ?: -1
                if (index >= 0) {
                    val px = event.getX(index); val py = event.getY(index)
                    if (stick) {
                        val radius = max(1f, bounds.width * .32f)
                        val dx = (px - bounds.width/2f)/radius; val dy = (py - bounds.height/2f)/radius
                        val length = max(1f, hypot(dx,dy)); x = dx / length; y = dy / length
                        callback(true,x,y)
                    } else if (px >= 0 && px < bounds.width && py >= 0 && py < bounds.height) {
                        if (!downSent) { downSent = true; callback(true,0f,0f) }
                    }
                    else release()
                }
            }
            true
        }, contentAlignment = Alignment.Center) {
        val pill = trigger || label in listOf("L", "R", "L1", "L2", "R1", "R2", "L3", "R3", "Start", "Select", "Mode", "Pause", "Coin", "Option", "Analog")
        val shape = if (pill) RoundedCornerShape(8.dp) else CircleShape
        Box(Modifier.fillMaxWidth(if (stick) 1f else if (pill) .94f else .78f).fillMaxHeight(if (stick) 1f else if (pill) .56f else .78f)
            .background((if (active == null) style?.fillColor ?: Color(0xFF242832) else style?.pressedFillColor ?: Color.DarkGray).copy(alpha = opacity), shape)
            .border(1.dp, (if (active == null) style?.borderColor ?: Color.White.copy(alpha = .22f) else style?.pressedBorderColor ?: Color.White), shape))
        if (stick) BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(Modifier.offset(maxWidth * x * .32f, maxHeight * y * .32f).size(maxWidth * .42f).background(Color.White.copy(alpha=.5f),CircleShape))
        } else {
            val symbol = mapOf("Up" to "↑", "Down" to "↓", "Left" to "←", "Right" to "→", "Square" to "□", "Cross" to "×", "Circle" to "○", "Triangle" to "△", "C-Up" to "C↑", "C-Down" to "C↓", "C-Left" to "C←", "C-Right" to "C→")[label] ?: label
            Text(symbol, color=style?.contentColor ?: Color.White, fontSize=if (symbol.length == 1) 20.sp else 13.sp, maxLines=1)
        }
    }
}

private val directionKeys = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ConsoleDPad(style: PlayerControlStyle?, opacity: Float, modifier: Modifier, onDigital: (Int, Boolean) -> Unit) {
    val callback by rememberUpdatedState(onDigital)
    var held by remember { mutableStateOf(emptySet<Int>()) }
    var active by remember { mutableStateOf<Int?>(null) }
    var bounds by remember { mutableStateOf(IntSize.Zero) }
    fun change(next: Set<Int>) {
        (held - next).sorted().forEach { callback(it, false) }
        (next - held).sorted().forEach { callback(it, true) }
        held = next
    }
    fun release() { active = null; change(emptySet()) }
    DisposableEffect(Unit) { onDispose { release() } }
    BoxWithConstraints(modifier.onSizeChanged { bounds = it }.testTag("player.dpad").pointerInteropFilter { event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> if (active == null) {
                val i = event.actionIndex
                if (event.getX(i) in 0f..bounds.width.toFloat() && event.getY(i) in 0f..bounds.height.toFloat()) active = event.getPointerId(i)
            }
            MotionEvent.ACTION_CANCEL -> release()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> if (active == event.getPointerId(event.actionIndex)) release()
        }
        if (event.actionMasked in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE)) {
            val i = active?.let { event.findPointerIndex(it) } ?: -1
            if (i >= 0) {
                val px = event.getX(i); val py = event.getY(i)
                if (px !in 0f..bounds.width.toFloat() || py !in 0f..bounds.height.toFloat()) release()
                else {
                    val x = (px - bounds.width / 2f) / (bounds.width / 3f)
                    val y = (py - bounds.height / 2f) / (bounds.height / 3f)
                    val next = mutableSetOf<Int>()
                    if (max(abs(x), abs(y)) > .28f) {
                        if (abs(x) >= abs(y) * .42f) next += if (x < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
                        if (abs(y) >= abs(x) * .42f) next += if (y < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
                    }
                    change(next)
                }
            }
        }
        true
    }) {
        val third = maxWidth / 3
        val directions = listOf(Triple("Up", KeyEvent.KEYCODE_DPAD_UP, 1 to 0), Triple("Down", KeyEvent.KEYCODE_DPAD_DOWN, 1 to 2),
            Triple("Left", KeyEvent.KEYCODE_DPAD_LEFT, 0 to 1), Triple("Right", KeyEvent.KEYCODE_DPAD_RIGHT, 2 to 1))
        directions.forEach { (label, key, point) ->
            Box(Modifier.offset(third * point.first, third * point.second).size(third).testTag("player.control.$label")
                .semantics { contentDescription = label; role = Role.Button; onClick { callback(key, true); callback(key, false); true } }, contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxSize(.78f).background((if (key in held) style?.pressedFillColor else style?.fillColor ?: Color(0xFF242832)) ?: Color.DarkGray, RoundedCornerShape(8.dp))
                    .border(1.dp, if (key in held) style?.pressedBorderColor ?: Color.White else Color.White.copy(alpha = .16f * opacity), RoundedCornerShape(8.dp)))
                Text(mapOf("Up" to "↑", "Down" to "↓", "Left" to "←", "Right" to "→")[label]!!, color = Color.White.copy(alpha = opacity), fontSize = 24.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
