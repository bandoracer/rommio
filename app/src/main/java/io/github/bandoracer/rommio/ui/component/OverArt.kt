package io.github.bandoracer.rommio.ui.component

import androidx.compose.ui.graphics.Color
import io.github.bandoracer.rommio.model.AppearanceTheme
import io.github.bandoracer.rommio.ui.theme.ThemeColorTokens
import io.github.bandoracer.rommio.ui.theme.ThemeTokens

/**
 * Colours for content set over captured frames, screenshots, the player and cover art. Those
 * surfaces are dark in every theme and scheme (a gameplay frame is never lit or printed), so their
 * captions, chips, scrims and keylines take Aurora's night tokens instead of raw colours
 * (docs/theme-layouts.md "Play rules": the frame tones equal Aurora dark `ok`/`wait`/`live`/`bad`).
 */
object OverArt {
    private val night: ThemeColorTokens = ThemeTokens.forKey(AppearanceTheme.AURORA.key).colors(true)

    /** Text and glyphs over a frame (Aurora night `ink`). */
    val ink: Color = night.ink

    /** Sync tones over a frame. */
    val ok: Color = night.ok
    val wait: Color = night.wait
    val live: Color = night.live
    val bad: Color = night.bad

    /** Behind a letterboxed frame or a missing preview (Aurora night `onAccent`, #0b0a12). */
    val frameGround: Color = night.onAccent

    /** A scrim or dark glass over art: the night ground at [alpha]. */
    fun shade(alpha: Float): Color = night.ground.copy(alpha = alpha)

    /** The dark glass of chips and pills over frames (50 %). */
    val glass: Color = shade(0.5f)

    /** The player's toast pill (62 %). */
    val toast: Color = shade(0.62f)

    /** A 0.5 dp keyline around a frame: night ink at 10 %. */
    val keyline: Color = night.ink.copy(alpha = 0.1f)

    /** [ink] at a quieter strength (captions, "local" states). */
    fun quiet(alpha: Float): Color = night.ink.copy(alpha = alpha)

    /**
     * Light edges on glass and covers (Aurora's rims and keylines, §1.6): the light scheme's glass
     * surface colour (white), used at the spec's strengths.
     */
    val rim: Color = ThemeTokens.forKey(AppearanceTheme.AURORA.key).colors(false).buttonInk
}
