package io.github.bandoracer.rommio.ui.screen.auth

import android.graphics.Bitmap
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import io.github.bandoracer.rommio.ThemeUnderTest
import io.github.bandoracer.rommio.data.setup.OneTouchSetupService
import io.github.bandoracer.rommio.data.setup.SetupConfiguration
import io.github.bandoracer.rommio.data.setup.SetupPairing
import io.github.bandoracer.rommio.data.setup.SetupPollResult
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Setup code (docs/theme-layouts.md "Setup"): the code, its QR code and "Approve on This Device"
 * while waiting; approval stores the configuration before acknowledging and moves on; a denial
 * offers "Try Again" with a fresh code. A fake service stands in (never the real setup service).
 */
class SetupCodeSheetTest {
    @get:Rule val ui = createComposeRule()

    private class FakeService(private val release: CompletableDeferred<SetupPollResult>) : OneTouchSetupService {
        val starts = AtomicInteger()
        val completions = AtomicInteger()
        override suspend fun start(deviceName: String): SetupPairing {
            val n = starts.incrementAndGet()
            return SetupPairing("ABC23$n", "A".repeat(32), "https://approve.example/a/ABC23$n", 600, 1)
        }
        override suspend fun poll(pairing: SetupPairing): SetupPollResult =
            if (starts.get() == 1) release.await() else SetupPollResult.Pending
        override suspend fun complete(pairing: SetupPairing) { completions.incrementAndGet() }
        override suspend fun cancel(pairing: SetupPairing) {}
    }

    @Test
    fun approvalStoresTheServerThenMovesOn() {
        val release = CompletableDeferred<SetupPollResult>()
        val service = FakeService(release)
        var stored: SetupConfiguration? = null
        var acknowledgedWhenStored = -1
        var done = 0
        ui.setContent {
            RommioTheme(ThemeUnderTest.preferences) {
                SetupCodeSheet(
                    apply = { stored = it; acknowledgedWhenStored = service.completions.get() },
                    onDone = { done++ },
                    onDismiss = {},
                    service = service,
                )
            }
        }
        ui.waitUntil(5_000) { runCatching { ui.onNodeWithTag("oneTouch.code").assert(hasText("ABC231")) }.isSuccess }
        ui.onNodeWithTag("oneTouch.qr").performScrollTo().assertIsDisplayed()
        ui.onNodeWithTag("oneTouch.approve").performScrollTo().assertIsDisplayed()
        // Let the sheet finish sliding in before the display is captured.
        ui.mainClock.advanceTimeBy(1_000)
        ui.waitForIdle()
        Thread.sleep(1_200)
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        File(dir, "setup-code-${ThemeUnderTest.label}.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        release.complete(SetupPollResult.Approved(SetupConfiguration("https://romm.example.com", "id", "secret")))
        ui.waitUntil(5_000) { done == 1 }
        assertEquals("https://romm.example.com", stored?.rommUrl)
        assertEquals("Stored before acknowledgement", 0, acknowledgedWhenStored)
        assertEquals(1, service.completions.get())
    }

    @Test
    fun aDenialOffersAFreshCode() {
        val release = CompletableDeferred<SetupPollResult>(SetupPollResult.Denied)
        val service = FakeService(release)
        ui.setContent {
            RommioTheme(ThemeUnderTest.preferences) {
                SetupCodeSheet(apply = { error("Denied setup applied") }, onDone = {}, onDismiss = {}, service = service)
            }
        }
        ui.waitUntil(5_000) { runCatching { ui.onNodeWithTag("oneTouch.error").assertIsDisplayed() }.isSuccess }
        ui.onNodeWithTag("oneTouch.error").assert(hasText("This setup request was denied. You can start again."))
        ui.onNodeWithTag("oneTouch.retry").performScrollTo().performClick()
        ui.waitUntil(5_000) { runCatching { ui.onNodeWithTag("oneTouch.code").assert(hasText("ABC232")) }.isSuccess }
        assertEquals(2, service.starts.get())
    }
}
