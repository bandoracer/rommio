package io.github.bandoracer.rommio.ui.theme

import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.bandoracer.rommio.model.AppearanceScheme
import io.github.bandoracer.rommio.model.AppearanceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RommioThemeValueTest {
    @Test
    fun everyThemeAndSchemeResolves() {
        AppearanceTheme.entries.forEach { theme ->
            listOf(false, true).forEach { dark ->
                val value = RommioThemeValue.of(theme, dark)
                assertEquals(theme, value.theme)
                assertEquals(theme.key, value.tokens.key)
                assertEquals(dark, value.isDark)
                assertEquals(value.tokens.colors(dark), value.colors)
                assertEquals(value.tokens.player(dark), value.player)
            }
        }
    }

    @Test
    fun lightAndDarkMaterialSchemesAreDistinct() {
        AppearanceTheme.entries.forEach { theme ->
            val light = RommioThemeValue.of(theme, dark = false).materialColorScheme
            val dark = RommioThemeValue.of(theme, dark = true).materialColorScheme
            assertNotEquals(light.background, dark.background)
            assertNotEquals(light.surface, dark.surface)
            assertTrue("$theme light ground is light", light.background.luminance() > 0.8f)
            assertTrue("$theme dark ground is dark", dark.background.luminance() < 0.05f)
            assertTrue("$theme light ink is dark", light.onSurface.luminance() < 0.1f)
            assertTrue("$theme dark ink is light", dark.onSurface.luminance() > 0.8f)
            // Material needs opaque containers even where the theme surface is translucent glass.
            listOf(light, dark).forEach { scheme ->
                assertEquals(1f, scheme.surfaceContainer.alpha)
                assertEquals(1f, scheme.surfaceContainerHigh.alpha)
                assertEquals(1f, scheme.surfaceVariant.alpha)
                assertEquals(1f, scheme.onSurfaceVariant.alpha)
            }
        }
    }

    @Test
    fun typeRolesCarrySizeTrackingCaseAndFigures() {
        val aurora = RommioThemeValue.of(AppearanceTheme.AURORA, dark = false).type
        assertEquals(44f.sp, aurora.display.style.fontSize)
        assertEquals((-0.028f).em, aurora.display.style.letterSpacing)
        assertTrue(aurora.section.uppercase)
        assertEquals("CONTINUE PLAYING", aurora.section.format("Continue playing"))
        assertFalse(aurora.body.uppercase)
        assertEquals("tnum", aurora.statValue.style.fontFeatureSettings)
        assertNull(aurora.body.style.fontFeatureSettings)

        val riso = RommioThemeValue.of(AppearanceTheme.RISO, dark = true).type
        assertTrue(riso.button.uppercase)
        assertEquals("RESUME", riso.button.format("Resume"))
        assertEquals(RommioFontFamilies.BigShoulders, riso.display.style.fontFamily)
        assertEquals(RommioFontFamilies.Newsreader, riso.body.style.fontFamily)
    }

    @Test
    fun schemeChoiceResolvesAgainstTheSystem() {
        assertTrue(AppearanceScheme.SYSTEM.resolveDark(systemDark = true))
        assertFalse(AppearanceScheme.SYSTEM.resolveDark(systemDark = false))
        assertTrue(AppearanceScheme.DARK.resolveDark(systemDark = false))
        assertFalse(AppearanceScheme.LIGHT.resolveDark(systemDark = true))
    }

    @Test
    fun defaultIsAurora() {
        assertEquals(AppearanceTheme.AURORA, RommioThemeValue.Default.theme)
        assertEquals(AppearanceTheme.AURORA, AppearanceTheme.Default)
    }

    private fun argb(rgb: Int) = androidx.compose.ui.graphics.Color(0xFF000000.toInt() or rgb)

    @Test
    fun aLitPageTakesTheGamesGroundTintLightsAndMarks() {
        // docs/theme-layouts.md §1.2, the Super Metroid palette iOS pins.
        val palette = listOf(0x0b0620, 0x3a1450, 0x5d2a6e, 0x1c0c2b, 0xff7a3d)
        val lights = io.github.bandoracer.rommio.ui.art.AuroraLight.lights(palette, dark = false)
        val base = RommioThemeValue.of(AppearanceTheme.AURORA, dark = false)
        val lit = base.lit(lights)
        assertEquals(argb(lights.ground), lit.colors.ground)
        assertEquals(argb(0xd64300), lit.colors.tint)
        assertEquals(argb(lights.l1), lit.colors.accent1)
        assertEquals(argb(lights.l2), lit.colors.accent2)
        assertEquals(argb(lights.l3), lit.colors.accent3)
        assertEquals(argb(0xf9601a), lit.mark1)
        // Everything else stays the theme's.
        assertEquals(base.colors.ink, lit.colors.ink)
        assertEquals(base.colors.buttonFill, lit.colors.buttonFill)
        // Unlighting returns the cached base value.
        assertTrue(lit.lit(null) === base)
    }

    @Test
    fun risoNeverLightsAPage() {
        val palette = listOf(0x0b0620, 0x3a1450, 0x5d2a6e, 0x1c0c2b, 0xff7a3d)
        val riso = RommioThemeValue.of(AppearanceTheme.RISO, dark = false)
        assertTrue(riso.lit(io.github.bandoracer.rommio.ui.art.AuroraLight.lights(palette, dark = false)) === riso)
    }

    @Test
    fun unlitMarksComeFromTintAndTheAccents() {
        val base = RommioThemeValue.of(AppearanceTheme.AURORA, dark = true)
        val rgb = { c: androidx.compose.ui.graphics.Color -> io.github.bandoracer.rommio.ui.art.AuroraLight.toHsl(0xFFFFFF and c.toArgb()) }
        assertEquals(argb(io.github.bandoracer.rommio.ui.art.AuroraLight.mark(rgb(base.colors.tint), dark = true)), base.mark1)
        assertEquals(argb(io.github.bandoracer.rommio.ui.art.AuroraLight.mark(rgb(base.colors.accent2), dark = true)), base.mark2)
        assertEquals(base.colors.accent1, argb(base.lights.l1))
    }
}
