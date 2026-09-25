package io.github.bandoracer.rommio.ui.theme

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Text in the tint colour (docs/theme-layouts.md §1.2a). The studio maths keeps the lit tint for
 * fills, marks and glows; wherever the tint colours text or a meaningful glyph it goes through
 * [readableTint], which darkens it on a light ground (lightens it on a dark one) in HSL lightness
 * steps of 0.005 until it reaches WCAG 2 contrast 4.5 against the ground **and** against a 13 % wash
 * of itself over the ground (the pill background). Colours are `0xRRGGBB` ints.
 *
 * Pinned to the reference script's values by `ReadableTintTest` (iOS pins the same table).
 */
object ReadableTint {
    const val MIN_CONTRAST = 4.5
    /** A tint pill's background: the colour at 13 % over the ground. */
    const val PILL_WASH = 0.13
    private const val STEP = 0.005
    private const val MAX_STEPS = 400

    fun readableTint(tint: Int, ground: Int, dark: Boolean): Int {
        val g = channels(ground)
        val (h, l0, s) = rgbToHls(channels(tint))
        var l = l0
        repeat(MAX_STEPS) {
            val candidate = quantise(hlsToRgb(h, l.coerceIn(0.0, 1.0), s))
            if (contrast(candidate, g) >= MIN_CONTRAST && contrast(candidate, mix(candidate, g, PILL_WASH)) >= MIN_CONTRAST) {
                return pack(candidate)
            }
            l += if (dark) STEP else -STEP
        }
        return pack(quantise(hlsToRgb(h, l.coerceIn(0.0, 1.0), s)))
    }

    /** WCAG 2 contrast ratio of two `0xRRGGBB` colours. */
    fun contrast(a: Int, b: Int): Double = contrast(channels(a), channels(b))

    /**
     * [color] stepped in HSL lightness by 0.01 (darker on a light [face], lighter on a dark one)
     * until it reaches [minimum] contrast against [face]: player legends in a console's colour
     * (docs/player-controls.md, Look; iOS `legibleLegend`). `0xRRGGBB` colours.
     */
    fun legibleOn(color: Int, face: Int, minimum: Double = 3.0): Int {
        val f = channels(face)
        val lighten = luminance(f) < 0.2
        val (h, l0, s) = rgbToHls(channels(color))
        var l = l0
        var candidate = channels(color)
        repeat(100) {
            if (contrast(candidate, f) >= minimum) return pack(quantise(candidate))
            l = (l + if (lighten) 0.01 else -0.01).coerceIn(0.0, 1.0)
            candidate = hlsToRgb(h, l, s)
        }
        return pack(quantise(candidate))
    }

    private fun contrast(a: DoubleArray, b: DoubleArray): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun luminance(rgb: DoubleArray): Double {
        fun lin(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        return 0.2126 * lin(rgb[0]) + 0.7152 * lin(rgb[1]) + 0.0722 * lin(rgb[2])
    }

    private fun mix(fg: DoubleArray, bg: DoubleArray, alpha: Double) =
        DoubleArray(3) { fg[it] * alpha + bg[it] * (1 - alpha) }

    private fun channels(rgb: Int) = doubleArrayOf(
        ((rgb shr 16) and 0xFF) / 255.0,
        ((rgb shr 8) and 0xFF) / 255.0,
        (rgb and 0xFF) / 255.0,
    )

    private fun quantise(rgb: DoubleArray) = DoubleArray(3) { (rgb[it].coerceIn(0.0, 1.0) * 255).roundHalfEven() / 255.0 }

    private fun pack(rgb: DoubleArray): Int {
        val r = (rgb[0] * 255).roundHalfEven().toInt()
        val g = (rgb[1] * 255).roundHalfEven().toInt()
        val b = (rgb[2] * 255).roundHalfEven().toInt()
        return (r shl 16) or (g shl 8) or b
    }

    /** Python's `round`: halves go to the even neighbour. */
    private fun Double.roundHalfEven(): Double = Math.rint(this)

    // Python colorsys (h, l, s in [0, 1]); the round trip is what the reference script computes.
    private fun rgbToHls(rgb: DoubleArray): Triple<Double, Double, Double> {
        val (r, g, b) = Triple(rgb[0], rgb[1], rgb[2])
        val maxc = max(r, max(g, b))
        val minc = min(r, min(g, b))
        val sumc = maxc + minc
        val rangec = maxc - minc
        val l = sumc / 2.0
        if (minc == maxc) return Triple(0.0, l, 0.0)
        val s = if (l <= 0.5) rangec / sumc else rangec / (2.0 - maxc - minc)
        val rc = (maxc - r) / rangec
        val gc = (maxc - g) / rangec
        val bc = (maxc - b) / rangec
        var h = when (maxc) {
            r -> bc - gc
            g -> 2.0 + rc - bc
            else -> 4.0 + gc - rc
        }
        h = (h / 6.0).mod(1.0)
        return Triple(h, l, s)
    }

    private fun hlsToRgb(h: Double, l: Double, s: Double): DoubleArray {
        if (s == 0.0) return doubleArrayOf(l, l, l)
        val m2 = if (l <= 0.5) l * (1.0 + s) else l + s - l * s
        val m1 = 2.0 * l - m2
        return doubleArrayOf(v(m1, m2, h + 1.0 / 3.0), v(m1, m2, h), v(m1, m2, h - 1.0 / 3.0))
    }

    private fun v(m1: Double, m2: Double, hue: Double): Double {
        val h = hue.mod(1.0)
        return when {
            h < 1.0 / 6.0 -> m1 + (m2 - m1) * h * 6.0
            h < 0.5 -> m2
            h < 2.0 / 3.0 -> m1 + (m2 - m1) * (2.0 / 3.0 - h) * 6.0
            else -> m1
        }
    }
}
