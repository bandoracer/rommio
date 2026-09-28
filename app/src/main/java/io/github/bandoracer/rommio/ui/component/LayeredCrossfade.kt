package io.github.bandoracer.rommio.ui.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Dissolves from the previous value's content to the new one (iOS `LayeredCrossfade`). The old
 * content fades out as the new fades in, and the two are added (`BlendMode.Plus`) inside one
 * offscreen layer, so their weights always sum to one: opaque content never shows what's behind
 * it mid-way (the dip of a plain crossfade, where two stacked fading layers let the ground
 * through), and translucent content (art fading into the page) dissolves evenly instead of leaving
 * the old content showing through until it is removed. Used for Aurora's page field and the Home
 * hero's art.
 *
 * Each value's content keeps its composition while it moves from the top to the fading layer, so
 * nothing it loaded (an image) is loaded again. The fade is read while drawing: it redraws the
 * two layers without recomposing them.
 */
@Composable
fun <T> LayeredCrossfade(
    value: T,
    modifier: Modifier = Modifier,
    durationMillis: Int = 550,
    content: @Composable (T) -> Unit,
) {
    val slots = remember { CrossfadeSlots(value) }
    LaunchedEffect(value) {
        if (slots.shown.value == value) return@LaunchedEffect
        // A change mid-fade continues from what's on screen. The new content starts hidden.
        slots.under = if (slots.reveal < 1f) (slots.under ?: slots.shown) else slots.shown
        slots.shown = slots.next(value)
        slots.reveal = 0f
        animate(0f, 1f, animationSpec = tween(durationMillis, easing = FastOutSlowInEasing)) { progress, _ -> slots.reveal = progress }
        slots.under = null
    }
    val under = slots.under
    val shown = slots.shown
    // The two are summed with each other, then composited over the page as one.
    Box(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }, propagateMinConstraints = true) {
        for (slot in listOfNotNull(under, shown)) {
            key(slot.id) {
                val top = slot.id == shown.id
                val fading = under != null
                Box(
                    when {
                        !top -> Modifier.graphicsLayer {
                            alpha = 1f - slots.reveal
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                        fading -> Modifier.drawWithContent {
                            // Flattened first, so blends inside it (the field's screened lights) stay inside it.
                            val paint = Paint().apply {
                                alpha = slots.reveal
                                blendMode = BlendMode.Plus
                            }
                            drawIntoCanvas { canvas -> canvas.saveLayer(Rect(Offset.Zero, size), paint) }
                            drawContent()
                            drawIntoCanvas { canvas -> canvas.restore() }
                        }
                        else -> Modifier
                    },
                    propagateMinConstraints = true,
                ) {
                    content(slot.value)
                }
            }
        }
    }
}

/** The content on top, the content fading out under it, and how far the top one is revealed. */
private class CrossfadeSlots<T>(initial: T) {
    class Slot<T>(val value: T, val id: Long)

    private var counter = 0L
    var shown by mutableStateOf(Slot(initial, counter))
    var under by mutableStateOf<Slot<T>?>(null)
    var reveal by mutableFloatStateOf(1f)

    fun next(value: T): Slot<T> = Slot(value, ++counter)
}
