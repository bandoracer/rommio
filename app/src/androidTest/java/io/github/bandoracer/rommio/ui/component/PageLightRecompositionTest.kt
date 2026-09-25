package io.github.bandoracer.rommio.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.bandoracer.rommio.model.AppearancePreferences
import io.github.bandoracer.rommio.model.AppearanceScheme
import io.github.bandoracer.rommio.model.AppearanceTheme
import io.github.bandoracer.rommio.ui.art.AuroraLight
import io.github.bandoracer.rommio.ui.theme.PageLight
import io.github.bandoracer.rommio.ui.theme.ProvidePageLight
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import io.github.bandoracer.rommio.ui.theme.RommioThemeValue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The page light is separate from the base theme (docs/android-theme-art.md "Performance"): when a
 * page lights up, only the composables that read [RommioTheme.light] recompose; a section that
 * draws with the base theme alone is skipped. Counted with a side effect per composition.
 */
class PageLightRecompositionTest {
    @get:Rule val ui = createComposeRule()

    private class Counts {
        var plain = 0
        var tokens = 0
        var reader = 0
    }

    @Composable
    private fun PlainSection(counts: Counts) {
        SideEffect { counts.plain++ }
        RoleText("Continue playing", RommioTheme.type.section)
    }

    @Composable
    private fun TokenSection(counts: Counts) {
        // Reads the base theme's colours (ink, surface): the light is not among them.
        val theme = RommioTheme.current
        SideEffect { counts.tokens++ }
        RoleText("Super Metroid", theme.type.headline, color = theme.colors.ink)
    }

    @Composable
    private fun LightReader(counts: Counts) {
        val mark = RommioTheme.light.mark1
        SideEffect { counts.reader++ }
        RoleText("Lit", RommioTheme.type.caption, color = mark)
    }

    @Test
    fun aChangeOfLightRecomposesOnlyItsReaders() {
        val base = RommioThemeValue.of(AppearanceTheme.AURORA, dark = true)
        val palette = listOf(0x0b0620, 0x3a1450, 0x5d2a6e, 0x1c0c2b, 0xff7a3d)
        val lit = PageLight.of(base, AuroraLight.lights(palette, dark = true))
        var light by mutableStateOf(base.unlit)
        val counts = Counts()
        ui.setContent {
            RommioTheme(AppearancePreferences(theme = AppearanceTheme.AURORA, scheme = AppearanceScheme.DARK)) {
                ProvidePageLight(light) {
                    Column {
                        PlainSection(counts)
                        TokenSection(counts)
                        LightReader(counts)
                    }
                }
            }
        }
        ui.waitForIdle()
        assertEquals(1, counts.plain)
        assertEquals(1, counts.tokens)
        assertEquals(1, counts.reader)

        light = lit
        ui.waitForIdle()
        // Only the reader of the light recomposed.
        assertEquals(1, counts.plain)
        assertEquals(1, counts.tokens)
        assertEquals(2, counts.reader)

        // Providing an equal light again changes nothing.
        light = PageLight.of(RommioThemeValue.of(AppearanceTheme.AURORA, dark = true), AuroraLight.lights(palette, dark = true))
        ui.waitForIdle()
        assertEquals(2, counts.reader)
    }
}
