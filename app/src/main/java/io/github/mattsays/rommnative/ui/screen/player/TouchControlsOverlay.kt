package io.github.mattsays.rommnative.ui.screen.player

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.mattsays.rommnative.domain.input.PlayerControlsState
import io.github.mattsays.rommnative.domain.input.TouchElementLayoutKind
import io.github.mattsays.rommnative.domain.input.TouchElementSpec
import io.github.mattsays.rommnative.domain.input.TouchElementState
import io.github.mattsays.rommnative.domain.input.TouchLayoutProfile
import kotlin.math.roundToInt

@Composable
fun TouchControlsOverlay(
    controlsState: PlayerControlsState,
    layout: TouchLayoutProfile,
    editMode: Boolean,
    modifier: Modifier = Modifier,
    onLayoutChange: (TouchLayoutProfile) -> Unit,
    onLayoutCommit: (TouchLayoutProfile) -> Unit,
    onDigitalInput: (keyCode: Int, pressed: Boolean) -> Unit,
) {
    val preset = controlsState.platformProfile.presets.firstOrNull { it.presetId == layout.presetId } ?: return
    val latestLayout = rememberUpdatedState(layout)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        preset.elements.forEach { spec ->
            val state = latestLayout.value.findState(spec)
            val visualState = if (layout.leftHanded) state.copy(centerX = 1f - state.centerX) else state
            val metrics = measureElement(spec.layoutKind, visualState.scale * layout.globalScale)
            val widthPx = with(density) { metrics.width.toPx() }
            val heightPx = with(density) { metrics.height.toPx() }
            val parentWidthPx = with(density) { maxWidth.toPx() }
            val parentHeightPx = with(density) { maxHeight.toPx() }
            val xOffset = (parentWidthPx * visualState.centerX - (widthPx / 2f)).roundToInt()
            val yOffset = (parentHeightPx * visualState.centerY - (heightPx / 2f)).roundToInt()

            Box(
                modifier = Modifier
                    .offset { IntOffset(xOffset, yOffset) }
                    .size(metrics.width, metrics.height)
                    .let { base ->
                        if (!editMode) {
                            base
                        } else {
                            base
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                    shape = RoundedCornerShape(24.dp),
                                )
                                .pointerInput(spec.id, layout, maxWidth, maxHeight) {
                                    var pendingLayout = layout
                                    detectDragGestures(
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            pendingLayout = pendingLayout.moveElement(
                                                elementId = spec.id,
                                                dx = dragAmount.x / parentWidthPx,
                                                dy = dragAmount.y / parentHeightPx,
                                            )
                                            onLayoutChange(pendingLayout)
                                        },
                                        onDragEnd = { onLayoutCommit(pendingLayout) },
                                    )
                                }
                        }
                    },
            ) {
                RenderElement(
                    spec = spec,
                    alpha = layout.opacity,
                    metrics = metrics,
                    enabled = !editMode,
                    onDigitalInput = onDigitalInput,
                )
                if (editMode) {
                    ResizeHandle(
                        modifier = Modifier.align(Alignment.BottomEnd),
                        onScaleDelta = { delta ->
                            val updated = latestLayout.value.scaleElement(spec.id, delta)
                            onLayoutChange(updated)
                            onLayoutCommit(updated)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RenderElement(
    spec: TouchElementSpec,
    alpha: Float,
    metrics: ElementMetrics,
    enabled: Boolean,
    onDigitalInput: (keyCode: Int, pressed: Boolean) -> Unit,
) {
    when (spec.layoutKind) {
        TouchElementLayoutKind.DPAD_CROSS -> {
            Box(modifier = Modifier.fillMaxSize().alpha(alpha)) {
                TouchControlButton(
                    label = spec.buttons[0].label,
                    size = metrics.button,
                    modifier = Modifier.align(Alignment.TopCenter),
                    enabled = enabled,
                    onPressChanged = { onDigitalInput(spec.buttons[0].action.keyCode, it) },
                )
                TouchControlButton(
                    label = spec.buttons[1].label,
                    size = metrics.button,
                    modifier = Modifier.align(Alignment.CenterStart),
                    enabled = enabled,
                    onPressChanged = { onDigitalInput(spec.buttons[1].action.keyCode, it) },
                )
                TouchControlButton(
                    label = spec.buttons[2].label,
                    size = metrics.button,
                    modifier = Modifier.align(Alignment.CenterEnd),
                    enabled = enabled,
                    onPressChanged = { onDigitalInput(spec.buttons[2].action.keyCode, it) },
                )
                TouchControlButton(
                    label = spec.buttons[3].label,
                    size = metrics.button,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enabled = enabled,
                    onPressChanged = { onDigitalInput(spec.buttons[3].action.keyCode, it) },
                )
            }
        }

        TouchElementLayoutKind.FACE_DIAGONAL -> {
            Box(modifier = Modifier.fillMaxSize().alpha(alpha)) {
                TouchControlButton(
                    label = spec.buttons[0].label,
                    size = metrics.button,
                    modifier = Modifier.align(Alignment.BottomStart),
                    enabled = enabled,
                    onPressChanged = { onDigitalInput(spec.buttons[0].action.keyCode, it) },
                )
                TouchControlButton(
                    label = spec.buttons[1].label,
                    size = metrics.button,
                    modifier = Modifier.align(Alignment.TopEnd),
                    enabled = enabled,
                    onPressChanged = { onDigitalInput(spec.buttons[1].action.keyCode, it) },
                )
            }
        }

        TouchElementLayoutKind.FACE_DIAMOND -> {
            Box(modifier = Modifier.fillMaxSize().alpha(alpha)) {
                TouchControlButton(
                    label = spec.buttons[0].label,
                    size = metrics.button,
                    modifier = Modifier.align(Alignment.CenterStart),
                    enabled = enabled,
                    onPressChanged = { onDigitalInput(spec.buttons[0].action.keyCode, it) },
                )
                TouchControlButton(
                    label = spec.buttons[1].label,
                    size = metrics.button,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enabled = enabled,
                    onPressChanged = { onDigitalInput(spec.buttons[1].action.keyCode, it) },
                )
                TouchControlButton(
                    label = spec.buttons[2].label,
                    size = metrics.button,
                    modifier = Modifier.align(Alignment.CenterEnd),
                    enabled = enabled,
                    onPressChanged = { onDigitalInput(spec.buttons[2].action.keyCode, it) },
                )
                TouchControlButton(
                    label = spec.buttons[3].label,
                    size = metrics.button,
                    modifier = Modifier.align(Alignment.TopCenter),
                    enabled = enabled,
                    onPressChanged = { onDigitalInput(spec.buttons[3].action.keyCode, it) },
                )
            }
        }

        TouchElementLayoutKind.BUTTON_ROW -> {
            Row(
                modifier = Modifier.fillMaxSize().alpha(alpha),
                horizontalArrangement = Arrangement.spacedBy(metrics.gap, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                spec.buttons.forEach { button ->
                    TouchControlButton(
                        label = button.label,
                        size = metrics.button,
                        enabled = enabled,
                        onPressChanged = { onDigitalInput(button.action.keyCode, it) },
                    )
                }
            }
        }

        TouchElementLayoutKind.BUTTON_COLUMN -> {
            Column(
                modifier = Modifier.fillMaxSize().alpha(alpha),
                verticalArrangement = Arrangement.spacedBy(metrics.gap, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                spec.buttons.forEach { button ->
                    TouchControlButton(
                        label = button.label,
                        size = metrics.button,
                        enabled = enabled,
                        onPressChanged = { onDigitalInput(button.action.keyCode, it) },
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun TouchControlButton(
    label: String,
    size: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onPressChanged: (Boolean) -> Unit,
) {
    var buttonWasPressed by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.78f else 0.48f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), CircleShape)
            .pointerInteropFilter {
                if (!enabled) return@pointerInteropFilter false
                when (it.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (!buttonWasPressed) {
                            buttonWasPressed = true
                            onPressChanged(true)
                        }
                        true
                    }

                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        if (buttonWasPressed) {
                            buttonWasPressed = false
                            onPressChanged(false)
                        }
                        true
                    }

                    else -> true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ResizeHandle(
    modifier: Modifier = Modifier,
    onScaleDelta: (Float) -> Unit,
) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onScaleDelta((dragAmount.x - dragAmount.y) / 480f)
                }
            },
    )
}

private data class ElementMetrics(
    val width: Dp,
    val height: Dp,
    val button: Dp,
    val gap: Dp,
)

private fun measureElement(kind: TouchElementLayoutKind, scale: Float): ElementMetrics {
    val button = (58.dp * scale.coerceIn(0.65f, 1.85f))
    val gap = 10.dp
    return when (kind) {
        TouchElementLayoutKind.DPAD_CROSS -> ElementMetrics(
            width = button * 2.6f,
            height = button * 2.6f,
            button = button,
            gap = gap,
        )

        TouchElementLayoutKind.FACE_DIAGONAL -> ElementMetrics(
            width = button * 2.2f,
            height = button * 2.2f,
            button = button,
            gap = gap,
        )

        TouchElementLayoutKind.FACE_DIAMOND -> ElementMetrics(
            width = button * 2.8f,
            height = button * 2.8f,
            button = button,
            gap = gap,
        )

        TouchElementLayoutKind.BUTTON_ROW -> ElementMetrics(
            width = button * 2.4f,
            height = button * 1.1f,
            button = button * 0.9f,
            gap = 8.dp,
        )

        TouchElementLayoutKind.BUTTON_COLUMN -> ElementMetrics(
            width = button * 1.1f,
            height = button * 2.4f,
            button = button * 0.9f,
            gap = 8.dp,
        )
    }
}

private fun TouchLayoutProfile.findState(spec: TouchElementSpec): TouchElementState {
    return elementStates.firstOrNull { it.elementId == spec.id }
        ?: TouchElementState(
            elementId = spec.id,
            centerX = spec.centerX,
            centerY = spec.centerY,
            scale = spec.baseScale,
        )
}

private fun TouchLayoutProfile.moveElement(
    elementId: String,
    dx: Float,
    dy: Float,
): TouchLayoutProfile {
    return copy(
        elementStates = elementStates.map { state ->
            if (state.elementId != elementId) {
                state
            } else {
                state.copy(
                    centerX = (state.centerX + dx).coerceIn(0.08f, 0.92f),
                    centerY = (state.centerY + dy).coerceIn(0.12f, 0.88f),
                )
            }
        },
        updatedAtEpochMs = System.currentTimeMillis(),
    )
}

private fun TouchLayoutProfile.scaleElement(
    elementId: String,
    delta: Float,
): TouchLayoutProfile {
    return copy(
        elementStates = elementStates.map { state ->
            if (state.elementId != elementId) {
                state
            } else {
                state.copy(scale = (state.scale + delta).coerceIn(0.6f, 1.8f))
            }
        },
        updatedAtEpochMs = System.currentTimeMillis(),
    )
}
