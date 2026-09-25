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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toIntSize
import kotlin.math.ceil

/**
 * What is behind Aurora glass (docs/theme-layouts.md §1.6). Compose has no backdrop filter, but the
 * only thing behind a glass surface is the page field the app draws itself, so:
 *
 * - the field records itself into [field] as it draws (only when the field itself changes: it
 *   sits on its own layer);
 * - [blurred] draws that recording blurred by `material.glassBlur` (a `RenderEffect`, API 31+) and
 *   [cache] holds the blurred result as an offscreen layer. Both are recorded once per field size;
 *   they reference the field's recording, so the blur is recomputed only when the field changes;
 * - each glass surface draws [cache] through a child layer of its own, moved by a layer property
 *   when the surface moves (a scroll) — no re-recording and no new blur per frame.
 *
 * Below API 31 surfaces stay translucent over the field.
 */
@Stable
class GlassBackdrop internal constructor(
    internal val field: GraphicsLayer,
    internal val blurred: GraphicsLayer,
    internal val cache: GraphicsLayer,
) {
    /** Where the field is laid out (surfaces offset the blurred field by their position in it). */
    var fieldCoordinates by mutableStateOf<LayoutCoordinates?>(null)
        internal set

    /** The field size and blur radius the blurred layers were recorded for. */
    internal var recordedSize = IntSize.Zero
        private set
    private var recordedRadius = -1f

    /**
     * Records the blurred copy of the field when its size (or the blur radius) changed. The blur
     * is worked out at a quarter of the field's resolution (nothing finer survives a 24 dp blur)
     * with the same Gaussian: 1/16 of the pixels to blur and to keep.
     */
    internal fun DrawScope.recordBlurIfNeeded(radiusPx: Float) {
        val size = size.toIntSize()
        if (size == recordedSize && radiusPx == recordedRadius) return
        recordedSize = size
        recordedRadius = radiusPx
        val small = IntSize(ceil(size.width * SCALE).toInt().coerceAtLeast(1), ceil(size.height * SCALE).toInt().coerceAtLeast(1))
        val radius = smallRadius(radiusPx)
        blurred.renderEffect = if (radius > 0f) BlurEffect(radius, radius, TileMode.Clamp) else null
        blurred.record(small) { scale(SCALE, SCALE, pivot = Offset.Zero) { drawLayer(field) } }
        // An offscreen layer keeps the blurred pixels, so surfaces draw a cached image.
        cache.compositingStrategy = CompositingStrategy.Offscreen
        cache.record(small) { drawLayer(blurred) }
    }

    /** Draws the blurred field at full size with its top-left at this scope's origin. */
    internal fun DrawScope.drawBlurredField() {
        scale(1f / SCALE, 1f / SCALE, pivot = Offset.Zero) { drawLayer(cache) }
    }

    companion object {
        val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        /** The blurred copy's resolution, as a fraction of the field's. */
        internal const val SCALE = 0.25f

        /**
         * The blur radius at [SCALE] for the same Gaussian as [radiusPx] at full size: Android turns
         * a radius r into sigma 0.57735 r + 0.5, so sigma/4 needs r/4 − 1.5/(4 × 0.57735).
         */
        internal fun smallRadius(radiusPx: Float): Float =
            ((0.57735f * radiusPx + 0.5f) * SCALE - 0.5f).coerceAtLeast(0f) / 0.57735f
    }
}

/** The page's glass backdrop (null where no field records itself: sheets, previews, tests). */
val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

/** A backdrop for a page field and the glass above it; null below API 31 (no blur). */
@Composable
fun rememberGlassBackdrop(): GlassBackdrop? {
    if (!GlassBackdrop.supported) return null
    val field = rememberGraphicsLayer()
    val blurred = rememberGraphicsLayer()
    val cache = rememberGraphicsLayer()
    return remember(field, blurred, cache) { GlassBackdrop(field, blurred, cache) }
}

/**
 * The page field side: record the field into the backdrop as it draws, and (once per size) the
 * blurred copy. Apply on a node that has its own layer, so the field re-records only when it changes.
 */
internal fun Modifier.recordGlassBackdrop(backdrop: GlassBackdrop?, blurRadius: Dp): Modifier {
    if (backdrop == null) return this
    return this
        .onGloballyPositioned { backdrop.fieldCoordinates = it }
        .drawWithContent {
            backdrop.field.record { this@drawWithContent.drawContent() }
            drawLayer(backdrop.field)
            with(backdrop) { recordBlurIfNeeded(blurRadius.toPx()) }
        }
}

/**
 * The glass side: under the surface's fill, the blurred field at this surface's place. Apply after
 * the surface's clip so it stays inside its shape. Skipped when there is no backdrop, below API 31,
 * or when the surface is in another window (a sheet) than the field.
 */
internal fun Modifier.glassBackdropBlur(): Modifier =
    if (GlassBackdrop.supported) this.then(GlassBlurElement) else this

private object GlassBlurElement : ModifierNodeElement<GlassBlurNode>() {
    override fun create() = GlassBlurNode()
    override fun update(node: GlassBlurNode) = Unit
    override fun InspectorInfo.inspectableProperties() {
        name = "glassBackdropBlur"
    }
    override fun hashCode(): Int = javaClass.hashCode()
    override fun equals(other: Any?): Boolean = other === this
}

private class GlassBlurNode :
    Modifier.Node(),
    DrawModifierNode,
    GlobalPositionAwareModifierNode,
    CompositionLocalConsumerModifierNode {
    /** This surface's window onto the blurred field, positioned by layer properties. */
    private var window: GraphicsLayer? = null
    private var windowSource: GlassBackdrop? = null
    private var windowSize = IntSize.Zero
    private var coordinates: LayoutCoordinates? = null

    override fun onDetach() {
        window?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        window = null
        windowSource = null
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        val backdrop = windowSource ?: return
        // A move (scroll) only moves the window layer; nothing is re-recorded or re-blurred.
        offsetIn(backdrop)?.let { place(it) }
    }

    private fun offsetIn(backdrop: GlassBackdrop): Offset? {
        val field = backdrop.fieldCoordinates ?: return null
        val me = coordinates ?: return null
        if (!field.isAttached || !me.isAttached || field.findRootCoordinates() !== me.findRootCoordinates()) return null
        return field.localPositionOf(me, Offset.Zero)
    }

    private fun place(offset: Offset) {
        window?.let {
            it.translationX = -offset.x
            it.translationY = -offset.y
        }
    }

    override fun ContentDrawScope.draw() {
        val backdrop = currentValueOf(LocalGlassBackdrop)
        val offset = backdrop?.let(::offsetIn)
        if (backdrop != null && offset != null) {
            val layer = window ?: requireGraphicsContext().createGraphicsLayer().also { window = it }
            // The window spans the field (its bounds are the field's, moved to this surface).
            if (windowSource !== backdrop || windowSize != backdrop.recordedSize) {
                windowSource = backdrop
                windowSize = backdrop.recordedSize
                layer.record(backdrop.recordedSize) { with(backdrop) { drawBlurredField() } }
            }
            place(offset)
            drawLayer(layer)
        }
        drawContent()
    }
}
