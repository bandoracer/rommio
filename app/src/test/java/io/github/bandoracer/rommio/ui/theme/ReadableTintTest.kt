package io.github.bandoracer.rommio.ui.theme

import androidx.compose.ui.graphics.toArgb
import io.github.bandoracer.rommio.model.AppearanceTheme
import io.github.bandoracer.rommio.ui.art.AuroraLight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/theme-layouts.md §1.2a: the pinned table (reference `readable_tint.py`; iOS pins the same). */
class ReadableTintTest {
    private data class Case(val name: String, val dark: Boolean, val tint: String, val ground: String, val readable: String)

    private val table = listOf(
        Case("smetroid", false, "#d64300", "#f9f4f3", "#b23800"),
        Case("smetroid", true, "#ff621a", "#0e0910", "#ff621a"),
        Case("chrono", false, "#c83c0e", "#f9f4f3", "#b5360d"),
        Case("linksawak", false, "#2bab56", "#f3f9f5", "#1d743a"),
        Case("grey", true, "#d34545", "#100909", "#d75858"),
        Case("earthbound", false, "#207fb6", "#f3f6f9", "#1b6a98"),
        Case("unlit Aurora", false, "#a84010", "#f6f4f1", "#a84010"),
    )

    // The rest of the reference script's cases (not in the doc table, same rule).
    private val extra = listOf(
        Case("chrono", true, "#f05a28", "#100c09", "#f05a28"),
        Case("linksawak", true, "#45d375", "#091009", "#45d375"),
        Case("grey", false, "#ab2b2b", "#f9f3f3", "#ab2b2b"),
        Case("earthbound", true, "#3ca1dd", "#10090b", "#3ca1dd"),
        Case("unlit Aurora", true, "#ff6a24", "#07070c", "#ff6a24"),
        Case("Riso", false, "#0078bf", "#fbfaf6", "#006bab"),
    )

    @Test
    fun matchesThePinnedTable() {
        (table + extra).forEach { case ->
            val out = ReadableTint.readableTint(hex(case.tint), hex(case.ground), case.dark)
            assertEquals("${case.name} ${if (case.dark) "dark" else "light"}", case.readable, AuroraLight.hex(out))
        }
    }

    @Test
    fun resultsMeetContrastOnGroundAndOnTheirPill() {
        (table + extra).forEach { case ->
            val out = ReadableTint.readableTint(hex(case.tint), hex(case.ground), case.dark)
            assertTrue(case.name, ReadableTint.contrast(out, hex(case.ground)) >= ReadableTint.MIN_CONTRAST)
        }
    }

    @Test
    fun themeExposesTheReadableTintForItsGround() {
        AppearanceTheme.entries.forEach { theme ->
            listOf(false, true).forEach { dark ->
                val value = RommioThemeValue.of(theme, dark)
                val expected = ReadableTint.readableTint(
                    value.colors.tint.toArgb() and 0xFFFFFF,
                    value.colors.ground.toArgb() and 0xFFFFFF,
                    dark,
                )
                assertEquals("$theme dark=$dark", expected, value.tintText.toArgb() and 0xFFFFFF)
            }
        }
        // Unlit Aurora's token already passes, so the text colour is the token itself.
        val aurora = RommioThemeValue.of(AppearanceTheme.AURORA, dark = false)
        assertEquals(aurora.colors.tint, aurora.tintText)
    }

    private fun hex(value: String): Int = value.removePrefix("#").toInt(16)
}
