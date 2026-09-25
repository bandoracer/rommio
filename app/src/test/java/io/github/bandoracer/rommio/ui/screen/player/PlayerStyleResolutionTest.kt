package io.github.bandoracer.rommio.ui.screen.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import io.github.bandoracer.rommio.domain.input.ActiveInputMode
import io.github.bandoracer.rommio.domain.input.HardwareBindingProfile
import io.github.bandoracer.rommio.domain.input.PlatformControlProfileResolver
import io.github.bandoracer.rommio.domain.input.PlayerControlsPreferences
import io.github.bandoracer.rommio.domain.input.PlayerControlsState
import io.github.bandoracer.rommio.model.AppearanceTheme
import io.github.bandoracer.rommio.ui.theme.RommioFontFamilies
import io.github.bandoracer.rommio.ui.theme.RommioThemeValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStyleResolutionTest {
    private val aurora = RommioThemeValue.of(AppearanceTheme.AURORA, dark = false)
    private val auroraDark = RommioThemeValue.of(AppearanceTheme.AURORA, dark = true)
    private val riso = RommioThemeValue.of(AppearanceTheme.RISO, dark = false)
    private val risoDark = RommioThemeValue.of(AppearanceTheme.RISO, dark = true)

    private fun controls(slug: String, oled: Boolean = false, consoleColors: Boolean = true): PlayerControlsState {
        val profile = PlatformControlProfileResolver().resolve(slug)
        return PlayerControlsState(
            platformProfile = profile,
            touchLayout = null,
            hardwareBinding = HardwareBindingProfile(profile.familyId, updatedAtEpochMs = 0L),
            preferences = PlayerControlsPreferences(oledBlackModeEnabled = oled, consoleColorsEnabled = consoleColors),
            connectedControllers = emptyList(),
            inputMode = ActiveInputMode.TOUCH,
            showTouchControls = true,
        )
    }

    @Test
    fun auroraControlsAreFrostedWithChevronsAndTheUiFont() {
        val visual = resolvePlayerVisualTheme(null, appTheme = aurora)
        val style = visual.style
        assertTrue(style.frosted)
        assertEquals(PlayerGlyphSet.CHEVRONS, style.glyphs)
        assertFalse(style.shoulderIsTab)
        assertFalse(style.dpadIsTwoPlate)
        assertFalse(style.tertiaryIsOutlined)
        assertEquals(0.dp, style.plateOffset)
        assertEquals(RommioFontFamilies.Figtree, style.legend.fontFamily)
        assertEquals(aurora.player.ground, visual.canvasColor)
        assertEquals(aurora.player.faceFill, visual.faceDefault.fillColor)
        assertEquals(Color.Transparent, visual.faceDefault.plateColor)
        // The user's opacity applies as is (the studio's --ctl-o); face buttons never below 0.5.
        assertEquals(0.72f, style.controlAlpha(0.72f), 1e-6f)
        assertEquals(0.3f, style.controlAlpha(0.3f), 1e-6f)
        assertEquals(0.5f, style.controlAlpha(0.3f, face = true), 1e-6f)
        assertEquals(0.8f, style.controlAlpha(0.8f, face = true), 1e-6f)
        // Pressed faces dip to 94 %.
        assertEquals(0.94f, style.pressedScale, 0f)
    }

    @Test
    fun risoControlsAreFlatInkOnBlack() {
        listOf(riso, risoDark).forEach { theme ->
            val visual = resolvePlayerVisualTheme(null, appTheme = theme)
            val style = visual.style
            assertFalse(style.frosted)
            assertEquals(PlayerGlyphSet.TRIANGLES, style.glyphs)
            assertTrue(style.shoulderIsTab)
            assertTrue(style.dpadIsTwoPlate)
            assertTrue(style.tertiaryIsOutlined)
            assertTrue(style.legendUppercase)
            assertEquals(2.5.dp, style.plateOffset)
            assertEquals(RommioFontFamilies.BigShoulders, style.legend.fontFamily)
            assertEquals(RommioFontFamilies.PlexMono, style.label.fontFamily)
            assertEquals(Color.Black, visual.canvasColor)
            // Yellow face buttons and blue shoulder tabs, each with the pink plate.
            val pink = Color(0xFFFF48B0)
            assertEquals(Color(0xFFFFE800), visual.styleFor(PlayerControlKind.FACE, "B").fillColor)
            assertEquals(pink, visual.styleFor(PlayerControlKind.FACE, "B").plateColor)
            assertEquals(Color(0xFF5EC8E5), visual.styleFor(PlayerControlKind.SHOULDER, "L").fillColor)
            assertEquals(pink, visual.styleFor(PlayerControlKind.SHOULDER, "L").plateColor)
            // Pink Menu pill without a plate; outlined Select/Start.
            assertEquals(pink, visual.styleFor(PlayerControlKind.MENU, "Menu").fillColor)
            assertEquals(Color.Transparent, visual.styleFor(PlayerControlKind.MENU, "Menu").plateColor)
            assertEquals(0f, visual.styleFor(PlayerControlKind.TERTIARY, "Select").fillColor.alpha, 0f)
            assertTrue(visual.styleFor(PlayerControlKind.TERTIARY, "Select").borderColor.alpha > 0.5f)
            // Two-plate d-pad: pink over blue with a yellow pivot.
            assertEquals(pink, style.dpad.fillColor)
            assertEquals(Color(0xFF5EC8E5), style.dpadAltFill)
            assertEquals(Color(0xFFFFE800), style.pivot)
            // Riso always prints at full ink: the opacity setting is Aurora's only.
            assertEquals(1f, style.controlAlpha(0.72f), 1e-6f)
            assertEquals(1f, style.controlAlpha(0.36f), 1e-6f)
            assertEquals(1f, style.controlAlpha(0.25f, face = true), 1e-6f)
            assertEquals(1f, style.pressedScale, 0f)
        }
    }

    @Test
    fun oledBlackForcesABlackStageWithTheDarkPalette() {
        val visual = resolvePlayerVisualTheme(controls("snes", oled = true), appTheme = aurora)
        assertEquals(Color.Black, visual.canvasColor)
        assertEquals(Color.Black, visual.panelColor)
        assertTrue(visual.oledBlack)
        assertTrue(visual.appTheme.isDark)
        assertEquals(auroraDark.player.controlFill, visual.neutralControlStyle.fillColor)
        assertEquals(auroraDark.colors.ink, visual.textColor)
    }

    @Test
    fun consoleColoursTintFaceButtonsInBothStyles() {
        val auroraSnes = resolvePlayerVisualTheme(controls("snes"), appTheme = auroraDark)
        val red = Color(0xFFDA5A58)
        // Aurora: the colour paints the legend and a 2 dp ring; the frosted face stays as it is.
        assertEquals(red, auroraSnes.styleFor(PlayerControlKind.FACE, "A").contentColor)
        assertEquals(red, auroraSnes.styleFor(PlayerControlKind.FACE, "A").ring)
        assertEquals(auroraSnes.faceDefault.fillColor, auroraSnes.styleFor(PlayerControlKind.FACE, "A").fillColor)
        assertNotEquals(auroraSnes.faceDefault, auroraSnes.styleFor(PlayerControlKind.FACE, "A"))
        // Riso prints each console colour in its nearest ink by hue: SNES red A in pink (on the
        // blue plate), blue X in blue, yellow B in yellow, green Y in green.
        val risoSnes = resolvePlayerVisualTheme(controls("snes"), appTheme = riso)
        val pink = Color(0xFFFF48B0)
        val blue = Color(0xFF5EC8E5)
        assertEquals(pink, risoSnes.styleFor(PlayerControlKind.FACE, "A").fillColor)
        assertEquals(blue, risoSnes.styleFor(PlayerControlKind.FACE, "A").plateColor)
        assertEquals(blue, risoSnes.styleFor(PlayerControlKind.FACE, "X").fillColor)
        assertEquals(pink, risoSnes.styleFor(PlayerControlKind.FACE, "X").plateColor)
        assertEquals(Color(0xFFFFE800), risoSnes.styleFor(PlayerControlKind.FACE, "B").fillColor)
        assertEquals(riso.player.faceInk, risoSnes.styleFor(PlayerControlKind.FACE, "Y").contentColor)
        assertNotEquals(risoSnes.faceDefault.fillColor, risoSnes.styleFor(PlayerControlKind.FACE, "Y").fillColor)
        // Shoulders, Menu and the d-pad keep the style's own colours.
        assertEquals(risoSnes.style.shoulder, risoSnes.styleFor(PlayerControlKind.SHOULDER, "L"))
        assertEquals(risoSnes.style.menu, risoSnes.styleFor(PlayerControlKind.MENU, "Menu"))
        // Labels match whatever their case (Atari's "Fire").
        val atari = resolvePlayerVisualTheme(controls("atari2600"), appTheme = riso)
        assertEquals(Color(0xFFFFE800), atari.styleFor(PlayerControlKind.FACE, "Fire").fillColor)
        // Turning console colours off returns every button to the style.
        val plain = resolvePlayerVisualTheme(controls("snes", consoleColors = false), appTheme = riso)
        assertEquals(plain.faceDefault, plain.styleFor(PlayerControlKind.FACE, "A"))
    }

    /**
     * docs/player-controls.md, Look: a console-coloured legend keeps 3:1 against the frosted face
     * (stepped to 3.5:1, drawn at full ink over a face faded to 0.5 at most).
     */
    @Test
    fun consoleColouredLegendsKeepThreeToOneAgainstTheFace() {
        for (theme in listOf(aurora, auroraDark)) {
            for (family in listOf("snes", "psx", "nes", "gb", "gba", "n64", "sega16", "sms", "arcade", "tg16", "atari2600", "lynx")) {
                val visual = resolvePlayerVisualTheme(controls(family), familyOverride = family, appTheme = theme)
                val face = effectiveControlFill(visual.style.colors).toArgb() and 0xFFFFFF
                visual.faceControlStyles.forEach { (label, style) ->
                    val legend = style.contentColor.toArgb() and 0xFFFFFF
                    val contrast = io.github.bandoracer.rommio.ui.theme.ReadableTint.contrast(legend, face)
                    assertTrue("$family $label ${if (theme.isDark) "dark" else "light"}: $contrast", contrast >= 3.5 - 1e-6)
                    assertTrue("$family $label keeps a ring", style.ring.alpha > 0f)
                }
            }
        }
        // Stepped only as far as needed: SNES yellow darkens on the light face, keeps its hue.
        val light = resolvePlayerVisualTheme(controls("snes"), appTheme = aurora).styleFor(PlayerControlKind.FACE, "B").contentColor
        assertNotEquals(Color(0xFFF1C94B), light)
        assertEquals(Color(0xFFF1C94B), resolvePlayerVisualTheme(controls("snes"), appTheme = aurora).styleFor(PlayerControlKind.FACE, "B").ring)
    }

    @Test
    fun playStationSymbolsAndArrowsAreGlyphs() {
        assertEquals(PlayerGlyph.CROSS, glyphForLabel("Cross"))
        assertEquals(PlayerGlyph.TRIANGLE, glyphForLabel("Triangle"))
        assertEquals(PlayerGlyph.UP, glyphForLabel("Up"))
        assertEquals(null, glyphForLabel("A"))
        assertEquals(null, glyphForLabel("Start"))
    }
}
