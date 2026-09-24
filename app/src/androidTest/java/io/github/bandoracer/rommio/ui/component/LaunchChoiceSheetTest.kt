package io.github.bandoracer.rommio.ui.component

import android.graphics.Bitmap
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import io.github.bandoracer.rommio.ThemeUnderTest
import io.github.bandoracer.rommio.domain.playdata.LaunchOptions
import io.github.bandoracer.rommio.domain.playdata.LaunchStart
import io.github.bandoracer.rommio.domain.playdata.SaveEntry
import io.github.bandoracer.rommio.domain.playdata.SaveKey
import io.github.bandoracer.rommio.domain.playdata.SaveKind
import io.github.bandoracer.rommio.domain.playdata.SaveSyncStatus
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Launch choice (docs/theme-layouts.md "Launch choice"): Resume is selected first, the primary
 * button's label follows the selection, the chosen start is handed back, and a resume point this
 * device can't load leaves only the game save. Runs under each theme and saves a screenshot.
 */
class LaunchChoiceSheetTest {
    @get:Rule val ui = createComposeRule()

    private val now = System.currentTimeMillis()

    private fun entry(id: String, kind: SaveKind, agoMs: Long) = SaveEntry(
        key = SaveKey("namespace", "content", id),
        romId = 7, fileId = 1, operationId = "op-$id", kind = kind,
        capturedAtEpochMs = now - agoMs, captureReason = "exit", deviceId = "device", fromThisDevice = true,
        deviceLabel = "This phone", sync = SaveSyncStatus.SYNCED, label = null, slotNumber = null,
        isCurrentResume = kind == SaveKind.RESUME_POINT, isCurrentSlot = false, loadable = kind != SaveKind.GAME_SAVE,
        thumbnailPath = null, isCurrentGameSave = kind == SaveKind.GAME_SAVE,
    )

    private val resume = entry("resume", SaveKind.RESUME_POINT, 3 * 60_000L)
    private val gameSave = entry("game", SaveKind.GAME_SAVE, 3 * 60_000L)

    @Test
    fun resumeIsTheDefaultAndTheButtonFollowsTheSelection() {
        var started: LaunchStart? = null
        var dismissed = false
        ui.setContent {
            RommioTheme(ThemeUnderTest.preferences) {
                LaunchChoiceSheet("EarthBound", LaunchOptions(resume, gameSave), coverUrl = null,
                    onStart = { started = it }, onDismiss = { dismissed = true }, platformSlug = "snes")
            }
        }
        ui.onNodeWithTag("launch.sheet").assertIsDisplayed()
        ui.onNodeWithText("EarthBound").assertIsDisplayed()
        ui.onNodeWithTag("launch.resume").assertIsSelected()
        ui.onNodeWithTag("launch.gameSave").assertIsNotSelected()
        ui.onNode(hasText("Automatic resume · 3 min ago · This phone")).assertIsDisplayed()
        ui.onNode(hasText("Game-save data · 3 min ago · the game starts from its own save")).assertIsDisplayed()
        ui.onNodeWithTag("launch.start").assert(hasText("Resume"))
        // Let the sheet finish sliding in before the display is captured.
        ui.mainClock.advanceTimeBy(1_000)
        ui.waitForIdle()
        Thread.sleep(1_200)
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        File(dir, "launch-choice-${ThemeUnderTest.label}.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        ui.onNodeWithTag("launch.gameSave").performClick()
        ui.onNodeWithTag("launch.gameSave").assertIsSelected()
        ui.onNodeWithTag("launch.resume").assertIsNotSelected()
        ui.onNodeWithTag("launch.start").performScrollTo().assert(hasText("Start with game save")).performClick()
        ui.runOnIdle {
            assertEquals(LaunchStart.GAME_SAVE, started)
            assertEquals(false, dismissed)
        }
    }

    @Test
    fun aResumePointThisDeviceCantLoadLeavesTheGameSave() {
        var started: LaunchStart? = null
        var dismissed = false
        ui.setContent {
            RommioTheme(ThemeUnderTest.preferences) {
                LaunchChoiceSheet("EarthBound", LaunchOptions(resume.copy(foreignState = true), gameSave, resumeUnavailable = true),
                    coverUrl = null, onStart = { started = it }, onDismiss = { dismissed = true })
            }
        }
        ui.onNodeWithTag("launch.resume").assertIsNotEnabled()
        ui.onNodeWithTag("launch.gameSave").assertIsSelected().assertIsEnabled()
        ui.onNodeWithTag("launch.note").performScrollTo().assertIsDisplayed()
        ui.onNodeWithTag("launch.dismiss").performScrollTo().performClick()
        ui.runOnIdle {
            assertNull(started)
            assertTrue(dismissed)
        }
    }
}
