package io.github.mattsays.rommnative.data.repository

import io.github.mattsays.rommnative.model.DownloadedRomEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class RommRepositoryAggregatesTest {
    @Test
    fun summarizeInstalledLibraryCountsDistinctGamesAndFiles() {
        val installed = listOf(
            DownloadedRomEntity(1, 10, "nes", "Mario", "mario.nes", "/tmp/mario.nes", 1024, 1),
            DownloadedRomEntity(1, 11, "nes", "Mario", "mario-alt.nes", "/tmp/mario-alt.nes", 2048, 2),
            DownloadedRomEntity(2, 12, "snes", "Zelda", "zelda.sfc", "/tmp/zelda.sfc", 4096, 3),
        )

        val summary = summarizeInstalledLibrary(installed)

        assertEquals(2, summary.installedGameCount)
        assertEquals(3, summary.installedFileCount)
        assertEquals(7168L, summary.totalBytes)
    }

    @Test
    fun summarizeInstalledPlatformsGroupsByPlatformSlug() {
        val installed = listOf(
            DownloadedRomEntity(1, 10, "nes", "Mario", "mario.nes", "/tmp/mario.nes", 1024, 1),
            DownloadedRomEntity(1, 11, "nes", "Mario", "mario-alt.nes", "/tmp/mario-alt.nes", 2048, 2),
            DownloadedRomEntity(2, 12, "snes", "Zelda", "zelda.sfc", "/tmp/zelda.sfc", 4096, 3),
        )

        val summaries = summarizeInstalledPlatforms(installed)

        assertEquals(2, summaries.size)
        assertEquals("nes", summaries.first().platformSlug)
        assertEquals(1, summaries.first().installedGameCount)
        assertEquals(2, summaries.first().installedFileCount)
        assertEquals(3072L, summaries.first().totalBytes)
    }
}
