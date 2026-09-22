package io.github.bandoracer.rommio.ui.screen.player

import kotlin.math.*

/** DP geometry shared by the viewport, controls and layout qualification. */
data class PlayerControlDeckLayout(
    val width: Float, val height: Float, val topInset: Float = 0f, val bottomInset: Float = 0f,
    val sideInset: Float = 0f, val deckHeight: Float = 200f, val requestedScale: Float = 1f,
    val visible: Boolean = true, val aspectRatio: Float? = null, val utilityCount: Int = 3,
) {
    val portrait = height >= width
    private val safeWidth = (width - sideInset * 2).coerceAtLeast(1f)
    private val safeHeight = (height - topInset - bottomInset).coerceAtLeast(1f)
    private val requested = requestedScale.coerceIn(.92f, 1.3f)
    private val baseScale = minOf(requested, (safeWidth - 16) / 296,
        (safeHeight - 72).coerceAtLeast(1f) / deckHeight.coerceAtLeast(1f))
    // Use natural pillarboxing first. Compact default controls only as far as
    // 44dp targets; explicitly enlarged controls retain the requested scale.
    val scale = if (!portrait && visible && requested <= 1 && aspectRatio != null) {
        val naturalWidth = minOf(safeWidth, safeHeight * aspectRatio.coerceAtLeast(.1f))
        minOf(baseScale, maxOf(.92f, ((safeWidth - naturalWidth) / 2 - 4) / 148))
    } else baseScale
    private val utilityWidth = maxOf(0, (utilityCount + 1) / 2 - 1) * 50f + 48
    val railWidth = if (portrait) 148 * scale else maxOf(148 * scale, utilityWidth)
    val leftOrigin = sideInset + if (portrait) 8 else 0
    val rightOrigin = width - sideInset - (if (portrait) 8 else 0) - railWidth
    val utilityY = height - bottomInset - 28
    val railTop = if (portrait) height - bottomInset - 56 - deckHeight * scale else
        topInset + 8 + (safeHeight - 72 - deckHeight * scale) / 2
    val gameLeft = sideInset + if (portrait) 12f else if (visible) railWidth + 4 else 52f
    val gameRight = width - sideInset - if (portrait) 12f else if (visible) railWidth + 4 else 52f
    val gameTop = topInset + if (portrait) 8 else 0
    // Utilities share the side rails, so video can use the full landscape height.
    val gameBottom = if (portrait) { if (visible) railTop - 12 else height - bottomInset - 56 } else height - bottomInset
    fun utilityX(index: Int, count: Int): Float {
        if (portrait) return width / 2 + (index - (count - 1) / 2f) * 52
        // Menu stays anchored when hiding controls or opening a pause sheet.
        val menuIndex = (count - 1) / 2
        if (index == menuIndex) return leftOrigin + 24
        val slot = if (index < menuIndex) index + 1 else index
        val leadingCount = (count + 1) / 2
        if (slot < leadingCount) return leftOrigin + 24 + slot * 50
        val trailingCount = count - leadingCount
        return rightOrigin + railWidth / 2 + (slot - leadingCount - (trailingCount - 1) / 2f) * 50
    }
}
