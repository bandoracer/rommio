package io.github.bandoracer.rommio.ui.component

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp

/**
 * What is behind Aurora glass (docs/theme-layouts.md §1.6). Compose has no backdrop filter, but the
 * only thing behind a glass surface is the page field the app draws itself, so the field records
 * itself into [layer] as it draws and each glass surface draws that recording again — offset to its
 * own place, clipped to its shape, blurred by `material.glassBlur` with a `RenderEffect` (API 31+).
 * Below API 31 surfaces stay translucent over the field.
 */
@Stable
class GlassBackdrop internal constructor(val layer: GraphicsLayer) {
    /** Where the field is laid out (surfaces offset the recording by their position in it). */
    var fieldCoordinates by mutableStateOf<LayoutCoordinates?>(null)
        internal set

    /** Reads the field's animated inputs, so a surface redraws while the field's light animates. */
    internal var observe: () -> Unit = {}

    companion object {
        val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }
}

/** The page's glass backdrop (null where no field records itself: sheets, previews, tests). */
val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

/** A backdrop for a page field and the glass above it; null below API 31 (no blur). */
@Composable
fun rememberGlassBackdrop(): GlassBackdrop? {
    if (!GlassBackdrop.supported) return null
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdrop(layer) }
}

/** The page field side: record the field into the backdrop as it draws. */
internal fun Modifier.recordGlassBackdrop(backdrop: GlassBackdrop?): Modifier {
    if (backdrop == null) return this
    return this
        .onGloballyPositioned { backdrop.fieldCoordinates = it }
        .drawWithContent {
            backdrop.layer.record { this@drawWithContent.drawContent() }
            drawLayer(backdrop.layer)
        }
}

/**
 * The glass side: under the surface's fill, the field recording offset to this surface, blurred.
 * Apply after the surface's clip so the blur stays inside its shape. Skipped when there is no
 * backdrop, below API 31, or when the surface is in another window (a sheet) than the field.
 */
internal fun Modifier.glassBackdropBlur(radius: Dp): Modifier = composed {
    val backdrop = LocalGlassBackdrop.current
    if (backdrop == null || !GlassBackdrop.supported) return@composed Modifier
    val blurLayer = rememberGraphicsLayer()
    var own by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Modifier
        .onGloballyPositioned { own = it }
        .drawWithContent {
            val field = backdrop.fieldCoordinates
            val me = own
            backdrop.observe()
            if (field != null && me != null && field.isAttached && me.isAttached && field.findRootCoordinates() === me.findRootCoordinates()) {
                val offset: Offset = field.localPositionOf(me, Offset.Zero)
                val r = radius.toPx()
                blurLayer.renderEffect = BlurEffect(r, r, TileMode.Clamp)
                blurLayer.record {
                    translate(-offset.x, -offset.y) { drawLayer(backdrop.layer) }
                }
                drawLayer(blurLayer)
            }
            drawContent()
        }
}
