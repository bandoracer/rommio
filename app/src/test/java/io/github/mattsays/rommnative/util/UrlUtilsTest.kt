package io.github.mattsays.rommnative.util

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlUtilsTest {
    @Test
    fun fileBackedThumbnailIsLeftUntouched() {
        val uri = "file:///data/user/0/io.github.mattsays.rommnative/cache/thumb.png"
        assertEquals(uri, resolveRemoteAssetUrl("https://romm.example", uri))
    }
}
