package io.github.bandoracer.rommio.ui.component

import android.text.format.Formatter
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * docs/theme-layouts.md §4: sizes are the platform formatter's decimal units. [Fmt.size] is a pure
 * port (so JVM tests pin it); this checks it matches `Formatter.formatShortFileSize` on a device.
 */
class FormattersPlatformTest {
    @Test
    fun sizesMatchThePlatformFormatter() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        listOf(1L, 512L, 900L, 950L, 1_500L, 12_345L, 999_999L, 4_300_000L, 62_400_000L, 512_000_000L, 1_200_000_000L, 64_000_000_000L)
            .forEach { bytes ->
                assertEquals("$bytes bytes", Formatter.formatShortFileSize(context, bytes), Fmt.size(bytes, locale))
            }
    }
}
