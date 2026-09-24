package io.github.bandoracer.rommio.ui.component

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import io.github.bandoracer.rommio.ThemeUnderTest
import io.github.bandoracer.rommio.domain.playdata.SaveConflictKind
import io.github.bandoracer.rommio.domain.playdata.SaveEntry
import io.github.bandoracer.rommio.domain.playdata.SaveKey
import io.github.bandoracer.rommio.domain.playdata.SaveKind
import io.github.bandoracer.rommio.domain.playdata.SaveSyncStatus
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The Conflict sheet (docs/theme-layouts.md "Conflict"): the plan's copy, the newest resume point
 * selected first, a choice hands back exactly that resume point, and Keep playing waits for the
 * game to be on this device. Runs under each theme (instrumentation arguments) and saves a
 * screenshot per theme.
 */
class ResumeConflictSheetTest {
    @get:Rule val ui = createComposeRule()

    private val now = System.currentTimeMillis()

    private fun head(id: String, device: String, agoMs: Long) = SaveEntry(
        key = SaveKey("namespace", "content", id),
        romId = 7,
        fileId = 1,
        operationId = "op-$id",
        kind = SaveKind.RESUME_POINT,
        capturedAtEpochMs = now - agoMs,
        captureReason = "exit",
        deviceId = device,
        fromThisDevice = device == "This phone",
        deviceLabel = device,
        sync = SaveSyncStatus.CONFLICT,
        label = null,
        slotNumber = null,
        isCurrentResume = true,
        isCurrentSlot = false,
        loadable = true,
        thumbnailPath = null,
    )

    private val heads = listOf(head("newer", "This phone", 3 * 60_000L), head("older", "Another device", 2 * 3_600_000L))

    @Test
    fun choosingAResumePointKeepsExactlyThatOne() {
        var kept: SaveEntry? = null
        var dismissed = false
        ui.setContent {
            RommioTheme(ThemeUnderTest.preferences) {
                ResumeConflictSheet("EarthBound", heads, coverUrl = null, canPlay = true, onKeep = { kept = it }, onDismiss = { dismissed = true })
            }
        }
        ui.onNodeWithText("EarthBound has two resume points. Choose which to keep playing.").assertIsDisplayed()
        ui.onNodeWithTag("conflict.choice.0").assertIsSelected()
        ui.onNodeWithTag("conflict.choice.1").assertIsNotSelected().performClick()
        ui.onNodeWithTag("conflict.choice.1").assertIsSelected()
        ui.onNodeWithTag("conflict.choice.0").assertIsNotSelected()
        // Let the sheet finish sliding in before the display is captured.
        ui.mainClock.advanceTimeBy(1_000)
        ui.waitForIdle()
        Thread.sleep(1_200)
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        File(dir, "conflict-${ThemeUnderTest.label}.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        ui.onNodeWithTag("conflict.keep").performScrollTo().assertIsEnabled().performClick()
        ui.runOnIdle {
            assertEquals("older", kept?.key?.checkpointId)
            assertFalse(dismissed)
        }
    }

    @Test
    fun theCopyCountsTheHeadsAndChoicesCarryNoSyncBadge() {
        val three = heads + head("oldest", "Another device", 26 * 3_600_000L).copy(sync = SaveSyncStatus.SYNCED)
        ui.setContent {
            RommioTheme(ThemeUnderTest.preferences) {
                ResumeConflictSheet("EarthBound", three, coverUrl = null, canPlay = true, onKeep = {}, onDismiss = {})
            }
        }
        ui.onNodeWithText("EarthBound has three resume points. Choose which to keep playing.").assertIsDisplayed()
        ui.onNodeWithTag("conflict.choice.2").performScrollTo().assertIsNotSelected()
        // Every head of a conflicted reference reads "Conflict": no sync badge on a choice.
        ui.onAllNodesWithText("Synced").assertCountEquals(0)
        ui.onNodeWithText("The other resume point stays in this game's saved progress.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun gameSaveConflictsUseTheirOwnWords() {
        ui.setContent {
            RommioTheme(ThemeUnderTest.preferences) {
                ResumeConflictSheet("EarthBound", heads, coverUrl = null, canPlay = true, onKeep = {}, onDismiss = {}, kind = SaveConflictKind.GAME_SAVE)
            }
        }
        ui.onNodeWithText("EarthBound has two versions of its game-save data. Choose which to keep playing.").assertIsDisplayed()
        ui.onNodeWithText("The other game-save data stays in this game's saved progress.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun keepPlayingWaitsForTheGameOnThisDevice() {
        var kept: SaveEntry? = null
        var dismissed = false
        ui.setContent {
            RommioTheme(ThemeUnderTest.preferences) {
                ResumeConflictSheet("EarthBound", heads, coverUrl = null, canPlay = false, onKeep = { kept = it }, onDismiss = { dismissed = true })
            }
        }
        ui.onNodeWithTag("conflict.keep").performScrollTo().assertIsNotEnabled()
        ui.onNodeWithText("Download EarthBound to this device to keep playing.").performScrollTo().assertIsDisplayed()
        ui.onNodeWithTag("conflict.dismiss").performScrollTo().performClick()
        ui.runOnIdle {
            assertNull(kept)
            assertEquals(true, dismissed)
        }
    }
}
