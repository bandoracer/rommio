package io.github.bandoracer.rommio.ui.screen.play

import io.github.bandoracer.rommio.domain.player.EmbeddedSupportTier
import io.github.bandoracer.rommio.domain.playdata.SaveEntry
import io.github.bandoracer.rommio.domain.playdata.SaveKey
import io.github.bandoracer.rommio.domain.playdata.SaveKind
import io.github.bandoracer.rommio.domain.playdata.SaveSyncStatus
import io.github.bandoracer.rommio.domain.playdata.SavedProgressRules
import io.github.bandoracer.rommio.model.RomDto
import io.github.bandoracer.rommio.model.RomMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/**
 * Aurora Play v2 (docs/theme-layouts.md "Play"): each game's screen and caption come from its
 * latest resume point (else save state), and the recent saves mosaic never leaves a hole or a lone
 * half tile. Mirrors iOS `PlayV2Tests`.
 */
class PlayV2Test {
    private val now = 1_800_000_000_000L
    private val zone = ZoneOffset.UTC

    private fun entry(
        kind: SaveKind,
        rom: Long = 7,
        minutesAgo: Double,
        resume: Boolean = false,
        loadable: Boolean = true,
        label: String? = null,
        slot: Int? = null,
        thumbnail: Boolean = true,
    ) = SaveEntry(
        key = SaveKey("n", "c$rom", "$kind-$minutesAgo"), romId = rom, fileId = rom * 10, operationId = "op-$minutesAgo", kind = kind,
        capturedAtEpochMs = now - (minutesAgo * 60_000).toLong(), captureReason = if (kind == SaveKind.SAVE_STATE) "manual" else "exit",
        deviceId = "me", fromThisDevice = true, deviceLabel = "This phone", sync = SaveSyncStatus.WAITING, label = label, slotNumber = slot,
        isCurrentResume = resume, isCurrentSlot = slot != null, loadable = loadable,
        thumbnailPath = if (thumbnail) "/tmp/preview-$minutesAgo.png" else null,
    )

    // ---- Latest place to continue from ----

    @Test fun theCurrentResumePointBeatsANewerSaveState() {
        val resume = entry(SaveKind.RESUME_POINT, minutesAgo = 30.0, resume = true)
        val state = entry(SaveKind.SAVE_STATE, minutesAgo = 5.0, label = "Before the boss")
        val older = entry(SaveKind.RESUME_POINT, minutesAgo = 90.0)
        assertEquals(resume.key, SavedProgressRules.latestByGame(listOf(older, state, resume))[7]?.key)
    }

    @Test fun aSaveStateStandsInWithoutAResumePoint() {
        val state = entry(SaveKind.SAVE_STATE, minutesAgo = 5.0, slot = 2)
        val gone = entry(SaveKind.SAVE_STATE, minutesAgo = 1.0, loadable = false)
        val gameSave = entry(SaveKind.GAME_SAVE, minutesAgo = 0.0)
        val latest = SavedProgressRules.latestByGame(listOf(gone, gameSave, state, entry(SaveKind.RESUME_POINT, rom = 8, minutesAgo = 3.0, resume = true)))
        assertEquals("Loadable save state; game-save data never", state.key, latest[7]?.key)
        assertEquals(SaveKind.RESUME_POINT, latest[8]?.kind)
    }

    private fun page(latest: SaveEntry?): PlayGame {
        val rom = RomDto(id = 7, name = "Super Metroid", platformName = "Super Nintendo", platformSlug = "snes",
            cachedMetadata = RomMetadata(firstReleaseEpochMs = 765_158_400_000L))
        val game = InProgressGame(rom, lastPlayedAtEpochMs = now - 7_200_000, playedMs = 3_900_000, installedFileId = 70,
            supportTier = EmbeddedSupportTier.TOUCH_SUPPORTED, sessionCount = 2)
        val state = PlayUiState(isLoading = false, continuePlaying = listOf(game), latest = listOfNotNull(latest).associateBy { it.romId })
        return PlayPage.build(state, null, now, zone).games.single()
    }

    @Test fun theCaptionReadsTheResumePointElseHowItWasPlayed() {
        val played = page(null)
        assertEquals("Super Nintendo · 1994", played.platformLine)
        assertEquals("Played 2 h ago · 1 h 05 min on this device", played.captionLine)
        assertNull(played.previewPath)
        val resumed = page(entry(SaveKind.RESUME_POINT, minutesAgo = 1.2, resume = true))
        assertEquals("Automatic resume · 1 min ago · This phone", resumed.captionLine)
        assertEquals("/tmp/preview-1.2.png", resumed.previewPath)
        assertEquals(SaveSyncStatus.WAITING, resumed.screenSync)
        assertEquals("“Before the boss” · 3 min ago · This phone", page(entry(SaveKind.SAVE_STATE, minutesAgo = 3.5, label = "Before the boss")).captionLine)
        assertEquals("Save state · Slot 2 · 3 min ago · This phone", page(entry(SaveKind.SAVE_STATE, minutesAgo = 3.5, slot = 2)).captionLine)
    }

    @Test fun thePageLoadsUntilThisDevicesPlayAndSavesAreIn() {
        val games = (1..3).map { id ->
            InProgressGame(RomDto(id = id, name = "Game $id", platformName = "NES", platformSlug = "nes"), now - id * 60_000L, 60_000L, null, EmbeddedSupportTier.TOUCH_SUPPORTED)
        }
        val waiting = PlayPage.build(PlayUiState(isLoading = false), null, now, zone)
        assertTrue("No empty state before the local reads", waiting.isLoading && !waiting.showsEmpty)
        val ready = PlayPage.build(PlayUiState(isLoading = false, localReady = true, continuePlaying = games), null, now, zone)
        assertEquals(listOf(1, 2, 3), ready.games.map { it.id })
        assertEquals("1 min ago", ready.games.first().lastActive)
        assertTrue(PlayPage.build(PlayUiState(isLoading = false, localReady = true), null, now, zone).showsEmpty)
    }

    // ---- Mosaic v2 ----

    @Test fun compactMosaicNeverLeavesAHoleOrALoneHalfTile() {
        assertEquals(emptyList<List<Int>>(), SaveMosaic.rows(0, 2))
        assertEquals(listOf(listOf(0)), SaveMosaic.rows(1, 2))
        assertEquals(listOf(listOf(0, 1)), SaveMosaic.rows(2, 2))
        assertEquals(listOf(listOf(0), listOf(1, 2)), SaveMosaic.rows(3, 2))
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3)), SaveMosaic.rows(4, 2))
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3, 4)), SaveMosaic.rows(5, 2))
        for (count in 1..12) {
            val rows = SaveMosaic.rows(count, 2)
            assertEquals("Every save once, in order", (0 until count).toList(), rows.flatten())
            assertTrue("Full width or a pair", rows.all { it.size == 1 || it.size == 2 })
        }
    }

    @Test fun regularMosaicFillsRowsOfFourAndStretchesTheLast() {
        assertEquals(listOf(listOf(0, 1, 2, 3), listOf(4, 5)), SaveMosaic.rows(6, 4))
        assertEquals(listOf(listOf(0, 1, 2, 3)), SaveMosaic.rows(4, 4))
        assertEquals("Tiles keep 180 dp: four columns", 4, SaveMosaic.columns(800f))
        assertEquals(3, SaveMosaic.columns(600f))
        assertEquals("Narrow canvas: the compact rule", 2, SaveMosaic.columns(380f))
    }

    @Test fun eachDayCarriesItsCompactRows() {
        val rom = RomDto(id = 7, name = "Super Metroid", platformName = "Super Nintendo", platformSlug = "snes")
        val saves = (1..3).map { RecentSaveItem(entry(SaveKind.SAVE_STATE, minutesAgo = it.toDouble(), slot = it), rom) }
        val day = PlayPage.build(PlayUiState(isLoading = false, recentSaves = saves), null, now, zone).saveDays.single()
        assertEquals(listOf(listOf(0), listOf(1, 2)), day.rows)
    }
}
