package io.github.bandoracer.rommio.ui.screen

import io.github.bandoracer.rommio.domain.player.EmbeddedSupportTier
import io.github.bandoracer.rommio.domain.stats.GamePlayTotal
import io.github.bandoracer.rommio.domain.stats.PlaySession
import io.github.bandoracer.rommio.domain.stats.PlayStats
import io.github.bandoracer.rommio.model.DownloadRecord
import io.github.bandoracer.rommio.model.DownloadStatus
import io.github.bandoracer.rommio.model.PlatformDto
import io.github.bandoracer.rommio.model.RomDto
import io.github.bandoracer.rommio.ui.screen.downloads.DownloadsPage
import io.github.bandoracer.rommio.ui.screen.downloads.DownloadsUiState
import io.github.bandoracer.rommio.ui.screen.platform.PlatformFilter
import io.github.bandoracer.rommio.ui.screen.platform.PlatformGame
import io.github.bandoracer.rommio.ui.screen.platform.PlatformOrder
import io.github.bandoracer.rommio.ui.screen.platform.PlatformPage
import io.github.bandoracer.rommio.ui.screen.platform.PlatformUiState
import io.github.bandoracer.rommio.ui.screen.stats.StatsGame
import io.github.bandoracer.rommio.ui.screen.stats.StatsPage
import io.github.bandoracer.rommio.ui.screen.stats.StatsSession
import io.github.bandoracer.rommio.ui.screen.stats.StatsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/**
 * Pages are built by their models once per data change (never in composition), and two builds of
 * the same data are equal, so an unchanged page is not published again (`distinctUntilChanged`).
 */
class PageBuildersTest {
    private val base = "https://romm.example"
    private val snes = PlatformDto(id = 1, slug = "snes", name = "Super Nintendo", fsSlug = "snes", romCount = 4)

    private fun rom(id: Int, name: String) = RomDto(
        id = id,
        name = name,
        platformId = 1,
        platformName = "Super Nintendo",
        platformSlug = "snes",
        urlCover = "/assets/romm/resources/roms/1/$id/cover/big.png",
    )

    private val platformState = PlatformUiState(
        isLoading = false,
        platform = snes,
        tier = EmbeddedSupportTier.TOUCH_SUPPORTED,
        games = listOf(
            PlatformGame(rom(1, "Super Metroid"), installed = true, playedMs = 3_600_000, lastPlayedAtEpochMs = 2_000),
            PlatformGame(rom(2, "EarthBound"), installed = false, playedMs = 0, lastPlayedAtEpochMs = null),
            PlatformGame(rom(3, "Chrono Trigger"), installed = true, playedMs = 60_000, lastPlayedAtEpochMs = 3_000),
            PlatformGame(rom(4, "1942"), installed = false, playedMs = 0, lastPlayedAtEpochMs = null),
        ),
    )

    @Test
    fun platformPageIsBuiltInTheModelAndEqualWhenTheDataIs() {
        val page = PlatformPage.build(platformState, "Super Nintendo", PlatformFilter.ALL, PlatformOrder.ALPHABETICAL, base)
        assertEquals(listOf("1942", "Chrono Trigger", "EarthBound", "Super Metroid"), page.games.map { it.title })
        // Riso's A–Z index: digits under "#", then each letter in order.
        assertEquals(listOf("#", "C", "E", "S"), page.letters.map { it.letter })
        // Plates: most played first; the lead (Aurora's light) is the most recently played.
        assertEquals(listOf("Super Metroid", "Chrono Trigger"), page.plates.map { it.title })
        assertTrue(page.platesArePlayed)
        assertEquals("$base/assets/romm/resources/roms/1/3/cover/big.png", page.leadCoverUrl)
        assertEquals(io.github.bandoracer.rommio.ui.component.formatPlayTime(60_000), page.games.first { it.title == "Chrono Trigger" }.figure)
        assertEquals(" ", page.games.first { it.title == "EarthBound" }.figure)
        assertEquals("On your server", page.games.first { it.title == "EarthBound" }.caption)
        // The same data builds an equal page (a new object), so it is not published again.
        val again = PlatformPage.build(platformState, "Super Nintendo", PlatformFilter.ALL, PlatformOrder.ALPHABETICAL, base)
        assertNotSame(page, again)
        assertEquals(page, again)
    }

    @Test
    fun platformChoicesAreTheModels() {
        val onDevice = PlatformPage.build(platformState, "Super Nintendo", PlatformFilter.ON_DEVICE, PlatformOrder.RECENTLY_PLAYED, base)
        assertEquals(listOf("Chrono Trigger", "Super Metroid"), onDevice.games.map { it.title })
        // The index is only for A–Z.
        assertTrue(onDevice.letters.isEmpty())
        assertEquals("Recently played", onDevice.orderLabel)
    }

    @Test
    fun statsDaysAreGroupedOnceWithUniqueKeys() {
        val zone = ZoneOffset.UTC
        val day = 86_400_000L
        val now = 10 * day + 12 * 3_600_000L
        val metroid = rom(1, "Super Metroid")
        val sessions = listOf(
            PlaySession(sessionId = "a", romId = 1, deviceId = "d", startedAtEpochMs = now - 7_200_000, endedAtEpochMs = now - 3_600_000, playedMs = 600_000, checkpointCount = 2),
            PlaySession(sessionId = "b", romId = 1, deviceId = "d", startedAtEpochMs = now - 7_000_000, endedAtEpochMs = now - 3_600_000, playedMs = 600_000, checkpointCount = 2),
            PlaySession(sessionId = "c", romId = 1, deviceId = "d", startedAtEpochMs = now - day - 7_200_000, endedAtEpochMs = now - day, playedMs = 60_000, checkpointCount = 1),
        )
        val stats = PlayStats(totalPlayedMs = 1_260_000, sessionCount = 3, lastPlayedAtEpochMs = now - 3_600_000, currentStreakDays = 2,
            games = listOf(GamePlayTotal(1, 1_260_000, 3, now - 3_600_000)), sessions = sessions)
        val state = StatsUiState(isLoading = false, stats = stats, games = listOf(StatsGame(stats.games.first(), metroid, 10)),
            sessions = sessions.map { StatsSession(it, metroid) })
        val page = StatsPage.build(state, base, now, zone)
        assertEquals(2, page.days.size)
        assertEquals("Today", page.days.first().title)
        assertEquals(listOf(2, 1), page.days.map { it.sessions.size })
        // Two sessions with the same game and end time still have distinct keys (lazy items need them).
        val keys = page.days.flatMap { day -> day.sessions.map { it.key } }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals("2 days", page.streakText)
        assertEquals(page, StatsPage.build(state, base, now, zone))
    }

    @Test
    fun downloadsAreGroupedByTheModel() {
        fun record(id: Int, status: DownloadStatus) = DownloadRecord(
            romId = id, fileId = id * 10, romName = "Game $id", platformSlug = "snes", fileName = "g$id.sfc",
            fileSizeBytes = 1_048_576, status = status, progressPercent = 40, enqueuedAtEpochMs = 0, updatedAtEpochMs = 0,
        )
        val state = DownloadsUiState(
            records = listOf(
                record(1, DownloadStatus.RUNNING),
                record(2, DownloadStatus.QUEUED),
                record(3, DownloadStatus.FAILED),
                record(4, DownloadStatus.COMPLETED),
                record(5, DownloadStatus.COMPLETED),
            ),
        )
        val page = DownloadsPage.build(state, base)
        assertEquals("Game 1", page.current?.record?.romName)
        assertEquals(listOf("Game 2"), page.upNext.map { it.record.romName })
        assertEquals(listOf("Game 3"), page.attention.map { it.record.romName })
        assertEquals(listOf("Game 4", "Game 5"), page.finished.map { it.record.romName })
        assertEquals("40%", page.current?.status)
        assertFalse(page.isEmpty)
        val keys = (listOfNotNull(page.current) + page.upNext + page.attention + page.finished).map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }
}
