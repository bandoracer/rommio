package io.github.bandoracer.rommio.ui.screen.auth

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import io.github.bandoracer.rommio.ThemeUnderTest
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Welcome (docs/theme-layouts.md "Setup"): first launch only, so it is rendered here rather than
 * reached on a signed-in emulator. Same identifiers in every theme; saves a screenshot per theme.
 */
class OnboardingWelcomeScreenTest {
    @get:Rule val ui = createComposeRule()

    @Test
    fun welcomeStartsSetupFromConnect() {
        var started = 0
        var resumed = 0
        var setupCode = 0
        ui.setContent {
            RommioTheme(ThemeUnderTest.preferences) {
                OnboardingWelcomeScreen(hasProfile = false, onStart = { started++ }, onResume = null, onSetupCode = { setupCode++ })
            }
        }
        ui.onNodeWithTag("screen.welcome").assertIsDisplayed()
        ui.waitForIdle()
        // Drawn from the composition itself (the display may not have shown the first frame yet).
        val screenshot = ui.onNodeWithTag("screen.welcome").captureToImage().asAndroidBitmap()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        File(dir, "welcome-${ThemeUnderTest.label}.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        ui.onNodeWithTag("welcome.connect").assertIsDisplayed().performClick()
        // One-touch setup: "Scan setup code" opens the Setup code sheet.
        ui.onNodeWithTag("welcome.setupCode").performScrollTo().assertIsDisplayed().performClick()
        ui.runOnIdle {
            assertEquals(1, started)
            assertEquals(0, resumed)
            assertEquals(1, setupCode)
        }
    }
}
