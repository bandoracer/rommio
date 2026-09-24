package io.github.bandoracer.rommio.ui.art

import io.github.bandoracer.rommio.ui.theme.AuroraArtTokens
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A colour as aurora.js keeps it: hue in degrees [0, 360), saturation and lightness in [0, 1]. */
data class Hsl(val h: Double, val s: Double, val l: Double)

/**
 * The light a game casts on an Aurora page, as `0xRRGGBB` ints.
 *
 * - [l1], [l2], [l3]: the three page lights (radial glows behind the content).
 * - [k1], [k2], [k3]: vivid mid-tones for small marks (dots, rules, strips, button glow).
 * - [tint]: the key colour deepened for chrome (selection, switches, links).
 * - [ground]: the page ground under the lights.
 */
data class AuroraLights(
    val l1: Int,
    val l2: Int,
    val l3: Int,
    val k1: Int,
    val k2: Int,
    val k3: Int,
    val tint: Int,
    val ground: Int,
) {
    fun hex(): Map<String, String> = mapOf(
        "l1" to AuroraLight.hex(l1), "l2" to AuroraLight.hex(l2), "l3" to AuroraLight.hex(l3),
        "k1" to AuroraLight.hex(k1), "k2" to AuroraLight.hex(k2), "k3" to AuroraLight.hex(k3),
        "tint" to AuroraLight.hex(tint), "bg" to AuroraLight.hex(ground),
    )
}

/**
 * Aurora's colour engine, a line-for-line port of `design/studio/versions/aurora/aurora.js`
 * (`toHsl`, `toHex`, `chroma`, `yellowish`, `keyColour`, `sources`, `glow`, `lights`). The same
 * palettes must give the same hex values on every platform; `AuroraLightTest` pins the values the
 * studio script produces under node.
 *
 * A palette is five `0xRRGGBB` colours: four dominant colours of the art, then its accent
 * ([CoverPalette] extracts them; the studio's `StudioArt.palette` has the same shape).
 */
object AuroraLight {
    // Constants that aurora.js writes inline and the generated tokens do not carry.
    /** Below this saturation a colour is treated as grey and keeps (or halves) its saturation. */
    private const val GREY_SATURATION = 0.1
    /** keyColour: the accent's weight, the damping of yellows for tint, and of near-greys. */
    private const val ACCENT_WEIGHT = 1.15
    private const val YELLOW_TINT_WEIGHT = 0.3
    private const val NEAR_GREY_SATURATION = 0.12
    private const val NEAR_GREY_WEIGHT = 0.1
    /** sources: a yellow accent becomes the third light when the art has this much chroma elsewhere. */
    private const val YELLOW_ACCENT_MIN_CHROMA = 0.12
    /** Light-scheme marks: saturation and lightness windows for the vivid mid-tones. */
    private const val MARK_MIN_SATURATION = 0.55
    private const val MARK_MAX_SATURATION = 0.95
    private const val MARK_MIN_LIGHTNESS = 0.46
    private const val MARK_MAX_LIGHTNESS = 0.54

    /** `lights([id], scheme)`: one game's palette. */
    fun lights(palette: List<Int>, dark: Boolean): AuroraLights = lightsFor(listOf(palette), dark)

    /** `lights(ids, scheme)`: [palettes] are the games in focus, first one leads. */
    fun lightsFor(palettes: List<List<Int>>, dark: Boolean): AuroraLights {
        require(palettes.isNotEmpty()) { "At least one palette" }
        palettes.forEach { require(it.size == 5) { "A palette has five colours" } }
        val src = sources(palettes)
        val l = src.map { glow(it, dark) }
        val k = src.map { mark(it, dark) }
        val key = keyColour(palettes[0], forTint = true)
        val tint = if (dark) {
            toRgb(key.h, max(key.s, AuroraArtTokens.tintDarkMinSaturation.d), AuroraArtTokens.tintDarkLightness.d)
        } else {
            toRgb(key.h, max(key.s, AuroraArtTokens.tintLightMinSaturation.d), AuroraArtTokens.tintLightLightness.d)
        }
        val ground = if (dark) {
            toRgb(src[1].h, AuroraArtTokens.pageGroundDarkSaturation.d, AuroraArtTokens.pageGroundDarkLightness.d)
        } else {
            toRgb(src[0].h, AuroraArtTokens.pageGroundLightSaturation.d, AuroraArtTokens.pageGroundLightLightness.d)
        }
        return AuroraLights(l[0], l[1], l[2], k[0], k[1], k[2], tint, ground)
    }

    /** The three light sources: for one game its accent and two most chromatic colours. */
    internal fun sources(palettes: List<List<Int>>): List<Hsl> {
        if (palettes.size == 1) {
            val p = palettes[0].map(::toHsl)
            val acc = p[4]
            // Stable sort, as Array.prototype.sort is: equal chroma keeps palette order.
            val rest = p.subList(0, 4).sortedByDescending(::chroma)
            // A yellow accent glows olive at night and vanishes by day: it becomes the third light.
            if (yellowish(acc.h) && chroma(rest[0]) > YELLOW_ACCENT_MIN_CHROMA) return listOf(rest[0], rest[1], acc)
            return listOf(acc, rest[0], rest[1])
        }
        val out = palettes.take(3).map { keyColour(it, forTint = true) }.toMutableList()
        while (out.size < 3) out.add(out.last())
        return out
    }

    /** A game's most characterful colour (accent preferred; yellow avoided for UI tint). */
    internal fun keyColour(palette: List<Int>, forTint: Boolean): Hsl {
        val p = palette.map(::toHsl)
        return p.mapIndexed { n, x ->
            // Same factor order as aurora.js so floating-point ties resolve identically.
            val v = chroma(x) *
                (if (n == 4) ACCENT_WEIGHT else 1.0) *
                (if (forTint && yellowish(x.h)) YELLOW_TINT_WEIGHT else 1.0) *
                (if (x.s < NEAR_GREY_SATURATION) NEAR_GREY_WEIGHT else 1.0)
            x to v
        }.sortedByDescending { it.second }.first().first
    }

    internal fun glow(c: Hsl, dark: Boolean): Int = if (dark) {
        toRgb(
            c.h,
            if (c.s < GREY_SATURATION) c.s else max(c.s, AuroraArtTokens.glowDarkMinSaturation.d),
            clamp(c.l, AuroraArtTokens.glowDarkMinLightness.d, AuroraArtTokens.glowDarkMaxLightness.d),
        )
    } else {
        toRgb(
            c.h,
            if (c.s < GREY_SATURATION) {
                c.s * AuroraArtTokens.glowLightGreySaturationScale.d
            } else {
                clamp(c.s, AuroraArtTokens.glowLightMinSaturation.d, AuroraArtTokens.glowLightMaxSaturation.d)
            },
            clamp(
                AuroraArtTokens.glowLightLightnessBase.d + (c.l - 0.5) * AuroraArtTokens.glowLightLightnessSpread.d,
                AuroraArtTokens.glowLightMinLightness.d,
                AuroraArtTokens.glowLightMaxLightness.d,
            ),
        )
    }

    /** A vivid mark that reads on either ground (dots, rules, bars, glows); by night it is the light. */
    fun mark(c: Hsl, dark: Boolean): Int = if (dark) {
        glow(c, dark = true)
    } else {
        toRgb(
            c.h,
            if (c.s < GREY_SATURATION) c.s else clamp(c.s, MARK_MIN_SATURATION, MARK_MAX_SATURATION),
            clamp(c.l, MARK_MIN_LIGHTNESS, MARK_MAX_LIGHTNESS),
        )
    }

    fun chroma(c: Hsl): Double = c.s * (1 - abs(2 * c.l - 1))

    private fun yellowish(h: Double): Boolean = h > 40 && h < 76

    /** `toHsl(hex)`. */
    fun toHsl(rgb: Int): Hsl {
        val r = ((rgb shr 16) and 0xFF) / 255.0
        val g = ((rgb shr 8) and 0xFF) / 255.0
        val b = (rgb and 0xFF) / 255.0
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2
        var h = 0.0
        var s = 0.0
        if (mx != mn) {
            val d = mx - mn
            s = if (l > 0.5) d / (2 - mx - mn) else d / (mx + mn)
            h = when (mx) {
                r -> (g - b) / d + (if (g < b) 6 else 0)
                g -> (b - r) / d + 2
                else -> (r - g) / d + 4
            }
            h *= 60
        }
        return Hsl(h, s, l)
    }

    /** `toHex(h, s, l)` as an int. Channels round half up, like `Math.round`. */
    fun toRgb(h: Double, s: Double, l: Double): Int {
        val a = s * min(l, 1 - l)
        fun f(n: Int): Int {
            val q = (n + h / 30) % 12
            val c = l - a * max(-1.0, min(min(q - 3, 9 - q), 1.0))
            return floor(c * 255 + 0.5).toInt().coerceIn(0, 255)
        }
        return (f(0) shl 16) or (f(8) shl 8) or f(4)
    }

    fun hex(rgb: Int): String = "#%06x".format(rgb and 0xFFFFFF)

    fun parseHex(hex: String): Int = hex.removePrefix("#").toInt(16)

    private fun clamp(v: Double, a: Double, b: Double): Double = min(b, max(a, v))

    private val Float.d: Double get() = this.toString().toDouble()
}
