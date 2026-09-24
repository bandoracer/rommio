package io.github.bandoracer.rommio.ui.art

import io.github.bandoracer.rommio.ui.theme.AuroraArtTokens
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Pixel bakes behind the art treatments (docs/theme-layouts.md §1.3, §1.4), pure so they run on
 * every API level and can be tested:
 * - [backdrop]: Aurora's blurred cover — `saturate(s)`, RGB × brightness, clamp, then a Gaussian
 *   blur whose σ is the token blur scaled from the studio's field width ([REFERENCE_FIELD_WIDTH]:
 *   150 % of a 402 pt phone) to the bake width.
 * - [grainTile]: Riso's paper grain, a seeded 180 px xorshift tile, the same bytes as iOS.
 */
object ArtBake {
    /** The studio's field width the blur token is measured against. */
    const val REFERENCE_FIELD_WIDTH = 402.0 * 1.5
    const val GRAIN_SIZE = 180

    /** Aurora's page light from a small copy of the cover (ARGB, opaque output). */
    fun backdrop(argb: IntArray, width: Int, height: Int, dark: Boolean): IntArray {
        val blur = (if (dark) AuroraArtTokens.backdropBlurDark else AuroraArtTokens.backdropBlurLight).toDouble()
        val saturation = (if (dark) AuroraArtTokens.backdropSaturationDark else AuroraArtTokens.backdropSaturationLight).toDouble()
        val brightness = (if (dark) AuroraArtTokens.backdropBrightnessDark else AuroraArtTokens.backdropBrightnessLight).toDouble()
        val graded = grade(argb, saturation, brightness)
        return gaussianBlur(graded, width, height, blur / REFERENCE_FIELD_WIDTH * width)
    }

    /**
     * CSS `saturate(s)` (Filter Effects colour matrix), then every channel × [brightness], clamped.
     * Alpha is dropped (the bake is opaque); transparent pixels read as black.
     */
    fun grade(argb: IntArray, saturation: Double, brightness: Double): IntArray = IntArray(argb.size) { i ->
        val p = argb[i]
        val a = (p ushr 24) / 255.0
        val r = (p shr 16 and 0xFF) / 255.0 * a
        val g = (p shr 8 and 0xFF) / 255.0 * a
        val b = (p and 0xFF) / 255.0 * a
        val s = saturation
        val rr = (0.213 + 0.787 * s) * r + (0.715 - 0.715 * s) * g + (0.072 - 0.072 * s) * b
        val gg = (0.213 - 0.213 * s) * r + (0.715 + 0.285 * s) * g + (0.072 - 0.072 * s) * b
        val bb = (0.213 - 0.213 * s) * r + (0.715 - 0.715 * s) * g + (0.072 + 0.928 * s) * b
        (0xFF shl 24) or (byte(rr * brightness) shl 16) or (byte(gg * brightness) shl 8) or byte(bb * brightness)
    }

    /** Separable Gaussian blur with clamped (extended) edges; σ in pixels. */
    fun gaussianBlur(argb: IntArray, width: Int, height: Int, sigma: Double): IntArray {
        if (sigma < 0.3 || width <= 0 || height <= 0) return argb.copyOf()
        val radius = ceil(sigma * 3).toInt()
        val kernel = DoubleArray(radius * 2 + 1) { i -> val x = (i - radius).toDouble(); exp(-(x * x) / (2 * sigma * sigma)) }
        val sum = kernel.sum()
        for (i in kernel.indices) kernel[i] /= sum
        val channels = Array(3) { c -> DoubleArray(argb.size) { i -> (argb[i] shr (16 - 8 * c) and 0xFF).toDouble() } }
        val tmp = DoubleArray(argb.size)
        for (plane in channels) {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    var v = 0.0
                    for (k in kernel.indices) {
                        val sx = (x + k - radius).coerceIn(0, width - 1)
                        v += plane[y * width + sx] * kernel[k]
                    }
                    tmp[y * width + x] = v
                }
            }
            for (y in 0 until height) {
                for (x in 0 until width) {
                    var v = 0.0
                    for (k in kernel.indices) {
                        val sy = (y + k - radius).coerceIn(0, height - 1)
                        v += tmp[sy * width + x] * kernel[k]
                    }
                    plane[y * width + x] = v
                }
            }
        }
        return IntArray(argb.size) { i ->
            (0xFF shl 24) or
                (channels[0][i].roundToInt().coerceIn(0, 255) shl 16) or
                (channels[1][i].roundToInt().coerceIn(0, 255) shl 8) or
                channels[2][i].roundToInt().coerceIn(0, 255)
        }
    }

    /**
     * 180 × 180 px of seeded noise (studio `StudioArt.grain(0.06, 180)`, the iOS `GrainTexture`):
     * mostly white stock with sparse dark specks. Opaque grey ARGB.
     */
    fun grainTile(): IntArray {
        var state = 0x9E3779B9.toInt()
        return IntArray(GRAIN_SIZE * GRAIN_SIZE) {
            // xorshift32 on unsigned bits, as the Swift UInt32 code does.
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            val noise = (state.toUInt() % 1000u).toInt() / 1000.0
            val value = (255 - (noise * noise * 0.12 * 255 * 2.2).toInt()).coerceIn(0, 255)
            (0xFF shl 24) or (value shl 16) or (value shl 8) or value
        }
    }

    private fun byte(v: Double): Int = (v.coerceIn(0.0, 1.0) * 255).roundToInt()
}
