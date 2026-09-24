package io.github.bandoracer.rommio.ui.screen

import io.github.bandoracer.rommio.domain.playdata.GameSaves
import io.github.bandoracer.rommio.domain.playdata.LaunchStart
import io.github.bandoracer.rommio.domain.playdata.SaveConflictKind
import io.github.bandoracer.rommio.domain.playdata.SaveEntry
import io.github.bandoracer.rommio.domain.playdata.SaveKey
import io.github.bandoracer.rommio.domain.playdata.SaveKind
import io.github.bandoracer.rommio.domain.playdata.SaveSyncStatus
import io.github.bandoracer.rommio.domain.playdata.SavedProgressRules
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/theme-layouts.md "Launch choice": the choices the planner would stop for come first. */
class LaunchFlowTest {
    private data class Opened(val romId: Int, val fileId: Int, val keep: String?, val start: LaunchStart?)

    private fun entry(id: String, kind: SaveKind, refs: Set<String>, conflicted: Boolean = false, ago: Long = 0, foreign: Boolean = false) = SaveEntry(
        key = SaveKey("ns", "content", id), romId = 7, fileId = 1, operationId = "op-$id", kind = kind,
        capturedAtEpochMs = 1_000_000L - ago, captureReason = "exit", deviceId = "d", fromThisDevice = true, deviceLabel = "This phone",
        sync = if (conflicted) SaveSyncStatus.CONFLICT else SaveSyncStatus.SYNCED, label = null, slotNumber = null,
        isCurrentResume = refs.any(SavedProgressRules::isResumeReference), isCurrentSlot = false,
        loadable = kind != SaveKind.GAME_SAVE, thumbnailPath = null,
        isCurrentGameSave = refs.any(SavedProgressRules::isGameSaveReference), foreignState = foreign,
    )

    private val target = LaunchTarget(7, "EarthBound", 1, null)

    private fun flow(saves: GameSaves, opened: MutableList<Opened>, scope: kotlinx.coroutines.CoroutineScope) =
        LaunchFlow(scope, { _, _ -> saves }) { r, f, k, s -> opened += Opened(r, f, k, s) }

    @Test
    fun aPlainLaunchOpensThePlayer() = runBlocking {
        val opened = mutableListOf<Opened>()
        val resume = entry("r", SaveKind.RESUME_POINT, setOf("state/resume/a"))
        val flow = flow(SavedProgressRules.forGame(listOf(resume)), opened, this)
        flow.play(target)
        yield()
        assertEquals(listOf(Opened(7, 1, null, null)), opened)
        assertNull(flow.conflict)
        assertNull(flow.choice)
    }

    @Test
    fun aConflictAsksFirstAndKeepCarriesTheChoice() = runBlocking {
        val opened = mutableListOf<Opened>()
        val a = entry("a", SaveKind.RESUME_POINT, setOf("state/resume/a"), conflicted = true)
        val b = entry("b", SaveKind.RESUME_POINT, setOf("state/resume/a"), conflicted = true, ago = 60_000)
        val flow = flow(SavedProgressRules.forGame(listOf(a, b)), opened, this)
        flow.play(target)
        yield()
        assertTrue(opened.isEmpty())
        assertEquals(SaveConflictKind.RESUME, flow.conflict?.conflict?.kind)
        flow.keep(b)
        assertEquals(listOf(Opened(7, 1, "b", null)), opened)
        assertNull(flow.conflict)
    }

    @Test
    fun aResumePointThisDeviceCantLoadAsksHowToStart() = runBlocking {
        val opened = mutableListOf<Opened>()
        val resume = entry("r", SaveKind.RESUME_POINT, setOf("state/resume/ios"), foreign = true)
        val game = entry("g", SaveKind.GAME_SAVE, setOf("game/current"))
        val flow = flow(SavedProgressRules.forGame(listOf(resume, game)), opened, this)
        flow.play(target)
        yield()
        val choice = requireNotNull(flow.choice)
        assertTrue(choice.options.resumeUnavailable)
        flow.start(LaunchStart.GAME_SAVE)
        assertEquals(listOf(Opened(7, 1, null, LaunchStart.GAME_SAVE)), opened)
    }

    @Test
    fun startOptionsOffersTheChoiceOnlyWhenThereIsOne() = runBlocking {
        val opened = mutableListOf<Opened>()
        val resume = entry("r", SaveKind.RESUME_POINT, setOf("state/resume/a"))
        val game = entry("g", SaveKind.GAME_SAVE, setOf("game/current"))
        val flow = flow(SavedProgressRules.forGame(listOf(resume, game)), opened, this)
        flow.startOptions(target)
        yield()
        assertEquals(LaunchStart.RESUME, flow.choice?.options?.default)
        flow.dismiss()
        assertNull(flow.choice)
        val plain = flow(SavedProgressRules.forGame(listOf(resume)), opened, this)
        plain.startOptions(target)
        yield()
        assertEquals("Nothing to choose: the game starts", listOf(Opened(7, 1, null, null)), opened)
    }

    @Test
    fun aGameThatIsNotHereCanStillShowItsConflict() = runBlocking {
        val opened = mutableListOf<Opened>()
        val a = entry("a", SaveKind.GAME_SAVE, setOf("game/current"), conflicted = true)
        val b = entry("b", SaveKind.GAME_SAVE, setOf("game/current"), conflicted = true, ago = 1)
        var otherwise = 0
        val flow = flow(SavedProgressRules.forGame(listOf(a, b)), opened, this)
        flow.chooseConflict(target.copy(fileId = null)) { otherwise++ }
        yield()
        assertEquals(SaveConflictKind.GAME_SAVE, flow.conflict?.conflict?.kind)
        flow.keep(a)
        assertTrue("Keep needs the game on this device", opened.isEmpty())
        val none = flow(GameSaves(), opened, this)
        none.chooseConflict(target) { otherwise++ }
        yield()
        assertEquals(1, otherwise)
    }
}
