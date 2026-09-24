package io.github.bandoracer.rommio.ui.art

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Riso's two-ink cover print, a port of the studio's `.rz` plates (design/studio riso.css) to a
 * pixel pipeline:
 *
 * - Plate A (pink, `accent1`) is separated from the art's green channel, plate B (blue, `accent2`)
 *   from its red channel — the channels each ink absorbs — through the studio's filter chain
 *   (grayscale luminance weight, brightness, contrast; each step clamped).
 * - Light paper: ink goes down where the channel is dark and the plates multiply over the paper.
 *   Dark stock: the plates print light where the channel is bright and combine as screen over black.
 * - Plate B is printed off register by `misregistration` (right, and up by half); the strip it
 *   uncovers is blank stock.
 * - Plate B is screened with round halftone dots at 15° (pitch [RisoInks.cell], sharpness 0.7)
 *   mixed over the continuous tone by the [RisoInks.halftone] strength (0.55 dots over 45 % solid).
 *   Plate A prints solid.
 *
 * This is docs/theme-layouts.md §1.5 (the iOS `RisoPrinter`): maths on encoded sRGB values; each
 * plate's mask is `clamp(gain × channel + bias)` ([curveA], [curveB]). By day the full overprint
 * is navy (≈ `#002183`).
 *
 * Only covers are printed. Gameplay frames, save-state previews and screenshots never are.
 */
data class RisoInks(
    /** Paper (light) or stock (dark) colour, `0xRRGGBB`. */
    val paper: Int,
    val plateA: Int,
    val plateB: Int,
    val dark: Boolean,
    /** Halftone strength 0–1 (`RisoArtTokens.halftone`). */
    val halftone: Float,
    /** Halftone dot pitch in output pixels: `clamp(longest side in dp / 60, 3, 5)` dp × density. */
    val cell: Float,
    /** Plate B offset in output pixels (`RisoArtTokens.misregistration` × density). */
    val misregistration: Float,
)

object RisoPrint {
    // The studio's filter chains for each plate: grayscale(1) brightness(b) contrast(c).
    private const val LUMA_R = 0.2126
    private const val LUMA_G = 0.7152
    private const val LIGHT_A_BRIGHTNESS = 1.55
    private const val LIGHT_B_BRIGHTNESS = 5.2
    private const val LIGHT_CONTRAST = 1.1
    private const val DARK_A_BRIGHTNESS = 1.7
    private const val DARK_B_BRIGHTNESS = 5.6
    private const val DARK_CONTRAST = 1.25
    private const val SCREEN_ANGLE = 15.0

    /** A plate's mask: how much light it lets through (1 = paper, 0 = full ink by day). */
    data class Curve(val gain: Double, val bias: Double) {
        fun mask(value: Double): Double = (gain * value + bias).coerceIn(0.0, 1.0)

        companion object {
            /** CSS `grayscale(1) brightness(b) contrast(c)` on a channel with luminance weight `w`, folded. */
            fun css(weight: Double, brightness: Double, contrast: Double) = Curve(weight * brightness * contrast, 0.5 - 0.5 * contrast)
        }
    }

    /** Plate A (pink) from green: light `clamp(1.21944 G − 0.05)`, dark `clamp(1.51980 G − 0.125)`. */
    fun curveA(dark: Boolean): Curve =
        Curve.css(LUMA_G, if (dark) DARK_A_BRIGHTNESS else LIGHT_A_BRIGHTNESS, if (dark) DARK_CONTRAST else LIGHT_CONTRAST)

    /** Plate B (blue) from red: light `clamp(1.21606 R − 0.05)`, dark `clamp(1.48820 R − 0.125)`. */
    fun curveB(dark: Boolean): Curve =
        Curve.css(LUMA_R, if (dark) DARK_B_BRIGHTNESS else LIGHT_B_BRIGHTNESS, if (dark) DARK_CONTRAST else LIGHT_CONTRAST)

    /** Halftone pitch in dp for a cover whose longest displayed side is [longestSideDp]. */
    fun dotPitchDp(longestSideDp: Float): Float = (longestSideDp / 60f).coerceIn(3f, 5f)

    /** The printed colour of one pixel with both plates in register and no halftone (reference). */
    fun printed(rgb: Int, inks: RisoInks): Int {
        val out = print(intArrayOf(0xFF000000.toInt() or rgb), 1, 1, inks.copy(halftone = 0f, misregistration = 0f))
        return out[0] and 0xFFFFFF
    }

    /** Plate A's mask for a green level (0–255): 0 = full ink on paper. */
    fun plateAValue(green: Int, dark: Boolean): Double = curveA(dark).mask(green / 255.0)

    /** Plate B's mask for a red level (0–255). */
    fun plateBValue(red: Int, dark: Boolean): Double = curveB(dark).mask(red / 255.0)

    /**
     * Prints [src] (ARGB, [width]×[height], already cropped to the output) and returns ARGB pixels
     * of the same size. Transparent source pixels print no ink.
     */
    fun print(src: IntArray, width: Int, height: Int, inks: RisoInks): IntArray {
        require(src.size == width * height)
        val out = IntArray(src.size)
        val ox = inks.misregistration
        val oy = -inks.misregistration / 2f
        val screenB = Screen(SCREEN_ANGLE, inks.cell)
        val pr = (inks.paper shr 16 and 0xFF) / 255.0
        val pg = (inks.paper shr 8 and 0xFF) / 255.0
        val pb = (inks.paper and 0xFF) / 255.0
        val ar = (inks.plateA shr 16 and 0xFF) / 255.0
        val ag = (inks.plateA shr 8 and 0xFF) / 255.0
        val ab = (inks.plateA and 0xFF) / 255.0
        val br = (inks.plateB shr 16 and 0xFF) / 255.0
        val bg = (inks.plateB shr 8 and 0xFF) / 255.0
        val bb = (inks.plateB and 0xFF) / 255.0
        val strength = inks.halftone.toDouble().coerceIn(0.0, 1.0)
        for (y in 0 until height) {
            for (x in 0 until width) {
                // Plate A in register; plate B sampled from where its offset plate lands.
                val a = src[y * width + x]
                val bx = floor(x - ox + 0.5).toInt()
                val by = floor(y - oy + 0.5).toInt()
                val b = if (bx in 0 until width && by in 0 until height) src[by * width + bx] else 0
                val ca = coverage(a, plateA = true, dark = inks.dark)
                var cb = coverage(b, plateA = false, dark = inks.dark)
                if (strength > 0 && inks.cell >= 2f) {
                    cb = cb + (screenB.dot(cb, x.toDouble() - ox, y.toDouble() - oy) - cb) * strength
                }
                val r: Double
                val g: Double
                val bl: Double
                if (!inks.dark) {
                    // Multiply: each plate lets through 1 - c·(1 - ink).
                    r = pr * (1 - ca * (1 - ar)) * (1 - cb * (1 - br))
                    g = pg * (1 - ca * (1 - ag)) * (1 - cb * (1 - bg))
                    bl = pb * (1 - ca * (1 - ab)) * (1 - cb * (1 - bb))
                } else {
                    // Screen over the stock: light adds.
                    r = 1 - (1 - pr) * (1 - ca * ar) * (1 - cb * br)
                    g = 1 - (1 - pg) * (1 - ca * ag) * (1 - cb * bg)
                    bl = 1 - (1 - pb) * (1 - ca * ab) * (1 - cb * bb)
                }
                out[y * width + x] = (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(bl)
            }
        }
        return out
    }

    /** Ink (light) or light (dark) coverage 0–1 of one plate for a source pixel. */
    internal fun coverage(argb: Int, plateA: Boolean, dark: Boolean): Double {
        if ((argb ushr 24) < 128) return 0.0
        val value = if (plateA) plateAValue(argb shr 8 and 0xFF, dark) else plateBValue(argb shr 16 and 0xFF, dark)
        return if (dark) value else 1 - value
    }

    private fun channel(v: Double): Int = floor(v.coerceIn(0.0, 1.0) * 255 + 0.5).toInt()

    /** A rotated round-dot halftone screen; [dot] returns the screened coverage with soft edges. */
    private const val SHARPNESS = 0.7

    private class Screen(angleDegrees: Double, cell: Float) {
        private val cos = cos(angleDegrees * PI / 180)
        private val sin = sin(angleDegrees * PI / 180)
        private val frequency = 2 * PI / cell.coerceAtLeast(2f)

        fun dot(coverage: Double, x: Double, y: Double): Double {
            if (coverage <= 0.0) return 0.0
            if (coverage >= 1.0) return 1.0
            val u = (x * cos + y * sin) * frequency
            val v = (-x * sin + y * cos) * frequency
            // Threshold 0..1 whose level sets are round dots growing into a checkerboard at 50%.
            val threshold = 0.5 - 0.25 * (cos(u) + cos(v))
            // CIDotScreen sharpness 0.7: a narrow soft edge around each dot.
            val edge = 0.3 * (1 - SHARPNESS) + 0.02
            val t = ((coverage - threshold) / edge + 0.5).coerceIn(0.0, 1.0)
            return t * t * (3 - 2 * t)
        }
    }
}
