package io.github.bandoracer.rommio.util

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlUtilsTest {
    @Test
    fun fileBackedThumbnailIsLeftUntouched() {
        val uri = "file:///data/user/0/io.github.bandoracer.rommio/cache/thumb.png"
        assertEquals(uri, resolveRemoteAssetUrl("https://romm.example", uri))
    }
}
