package io.github.bandoracer.rommio.domain.player

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import io.github.bandoracer.rommio.ui.screen.player.PlayerControlDeckLayout
import io.github.bandoracer.rommio.ui.screen.player.resolvePlayerVisualTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import io.github.bandoracer.rommio.domain.input.*
import io.github.bandoracer.rommio.ui.screen.player.ExpansionControlsOverlay
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ExpansionControlsTest {
    @get:Rule val ui = createAndroidComposeRule<NativePlayerTestActivity>()
    @Test fun allConsoleButtonsSticksAndMenuEmitAndReleaseInputs() {
        ui.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        var family by mutableStateOf("sega16")
        var hidden by mutableStateOf(false)
        var mirrored by mutableStateOf(false)
        val digital = mutableListOf<Pair<Int,Boolean>>()
        val motion = mutableListOf<Triple<PlayerMotionSource,Float,Float>>()
        ui.setContent { io.github.bandoracer.rommio.ui.theme.RommioTheme(io.github.bandoracer.rommio.ThemeUnderTest.preferences) {
            val appTheme = io.github.bandoracer.rommio.ui.theme.RommioTheme.current
            BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF0D1015))) {
            val controls = ExpansionControls.controls(family)!!
            val aspect = PlatformControlProfileResolver().supportedProfiles().first { it.familyId == family }.preferredViewportAspectRatio
            val deck = PlayerControlDeckLayout(maxWidth.value, maxHeight.value, bottomInset = 16f, deckHeight = ExpansionControls.deckHeight(controls), visible = !hidden, aspectRatio = aspect, utilityCount = if (hidden) 1 else controls.count { it.center } + 1)
            Box(Modifier.offset(deck.gameLeft.dp, deck.gameTop.dp).size((deck.gameRight - deck.gameLeft).dp, (deck.gameBottom - deck.gameTop).dp).background(Color(0xFF1F303D)).testTag("qa.viewport"))
            ExpansionControlsOverlay(controls, mirrored, 1f, 1f, !hidden, 16.dp, theme = resolvePlayerVisualTheme(null, family, appTheme), aspectRatio = aspect,
                onDigital = { k,p -> digital.add(k to p) }, onMotion = { s,x,y -> motion.add(Triple(s,x,y)) }, onMenu = { hidden = !hidden })
            }
        } }
        for (slug in listOf("genesis", "segacd", "sms", "gamegear", "psx", "n64", "psp", "nes", "snes", "gb", "gba", "atari2600", "tg16", "arcade", "nds")) {
            ui.runOnIdle { family = PlatformControlProfileResolver().resolve(slug).familyId; hidden = false; mirrored = false }
            val controls = ExpansionControls.controls(family)!!
            for (control in controls) {
                ui.runOnIdle { digital.clear(); motion.clear() }
                val node = ui.onNodeWithTag("player.control.${control.label}").assertIsDisplayed()
                val targetBounds = node.fetchSemanticsNode().boundsInRoot
                assertFalse("Overlaps game $slug ${control.label}", targetBounds.overlaps(ui.onNodeWithTag("qa.viewport").fetchSemanticsNode().boundsInRoot))
                if (control.stick == null) {
                    node.performTouchInput { down(center); advanceEventTime(40); up() }
                    ui.runOnIdle {
                        if (control.combo.isNotEmpty()) assertEquals(control.combo.map { it to true } + control.combo.map { it to false }, digital)
                        else if (control.key != null) assertEquals("$slug ${control.label}", listOf(control.key to true, control.key to false),digital)
                        else {
                            assertTrue(motion.any { it == Triple(PlayerMotionSource.ANALOG_RIGHT,control.axisX,control.axisY) })
                            assertEquals(Triple(PlayerMotionSource.ANALOG_RIGHT,0f,0f),motion.last())
                        }
                    }
                    // Cancellation must release, too.
                    node.performTouchInput { down(center); cancel() }
                    ui.runOnIdle { if (control.combo.isNotEmpty()) assertEquals(control.combo.last() to false,digital.last()) else if (control.key != null) assertEquals(control.key to false,digital.last()) else assertEquals(0f,motion.last().second) }
                } else {
                    node.performTouchInput { down(center); moveTo(centerRight); up() }
                    ui.runOnIdle { assertTrue(motion.any { it.second > .99f }); assertEquals(0f,motion.last().second); assertEquals(0f,motion.last().third) }
                    node.performTouchInput { down(center); moveTo(bottomCenter); cancel() }
                    ui.runOnIdle { assertTrue(motion.any { it.third > .99f }); assertEquals(0f,motion.last().third) }
                }
            }
            // Two independent fingers remain held and release independently.
            val left = ui.onNodeWithTag("player.control.Left").fetchSemanticsNode().boundsInRoot.center
            val right = ui.onNodeWithTag("player.control.${controls.first { !it.left && !it.center && it.key != null }.label}").fetchSemanticsNode().boundsInRoot.center
            ui.runOnIdle { digital.clear() }
            ui.onRoot().performTouchInput { down(0,left); down(1,right); up(0); up(1) }
            ui.runOnIdle { assertEquals(4,digital.size); assertEquals(listOf(true,true,false,false),digital.map { it.second }) }
            ui.runOnIdle { digital.clear() }
            ui.onNodeWithTag("player.dpad").performTouchInput { down(Offset(width * 5f / 6f, height / 6f)); up() }
            ui.runOnIdle { assertTrue(digital.contains(android.view.KeyEvent.KEYCODE_DPAD_UP to true)); assertTrue(digital.contains(android.view.KeyEvent.KEYCODE_DPAD_RIGHT to true)); assertTrue(digital.takeLast(2).all { !it.second }) }
            ui.onNodeWithTag("player.menu").performClick()
            ui.onNodeWithTag("player.control.Up").assertDoesNotExist()
            ui.onNodeWithTag("player.menu").assertIsDisplayed().performClick()
            ui.runOnIdle { mirrored = true }
            ui.onNodeWithTag("player.control.Up").assertIsDisplayed()
            val screenshot = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            java.io.File(ui.activity.getExternalFilesDir(null), "deck-$slug-${io.github.bandoracer.rommio.ThemeUnderTest.label}-landscape.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            ui.runOnIdle { ui.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            ui.waitForIdle()
            controls.forEach { control ->
                val bounds = ui.onNodeWithTag("player.control.${control.label}").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertFalse("Portrait overlaps game $slug ${control.label}", bounds.overlaps(ui.onNodeWithTag("qa.viewport").fetchSemanticsNode().boundsInRoot))
            }
            val portrait = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            java.io.File(ui.activity.getExternalFilesDir(null), "deck-$slug-${io.github.bandoracer.rommio.ThemeUnderTest.label}-portrait.png").outputStream().use { portrait.compress(Bitmap.CompressFormat.PNG, 100, it) }
            ui.runOnIdle { ui.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            ui.waitForIdle()
            println("PASS console controls $slug (${io.github.bandoracer.rommio.ThemeUnderTest.label}): press/release/cancel, analog, multi-touch, menu, mirror")
        }
    }
}
