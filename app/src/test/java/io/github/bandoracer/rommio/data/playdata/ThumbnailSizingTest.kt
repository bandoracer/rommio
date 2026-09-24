package io.github.bandoracer.rommio.data.playdata

import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailSizingTest {
    @Test
    fun wideFramesScaleTo320KeepingAspect() {
        assertEquals(320 to 240, ThumbnailSizing.target(1440, 1080))
        assertEquals(320 to 213, ThumbnailSizing.target(1620, 1080)) // GBA 3:2
        assertEquals(320 to 480, ThumbnailSizing.target(900, 1350)) // DS screens stacked
    }

    @Test
    fun smallFramesAreNeverUpscaledOrCollapsed() {
        assertEquals(256 to 224, ThumbnailSizing.target(256, 224))
        assertEquals(320 to 1, ThumbnailSizing.target(4000, 1))
    }
}
