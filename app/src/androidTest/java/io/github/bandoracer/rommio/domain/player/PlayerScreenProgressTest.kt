package io.github.bandoracer.rommio.domain.player

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.*
import androidx.test.core.app.ApplicationProvider
import io.github.bandoracer.rommio.AppContainer
import io.github.bandoracer.rommio.data.auth.VerifiedAccount
import io.github.bandoracer.rommio.domain.progress.*
import io.github.bandoracer.rommio.model.*
import io.github.bandoracer.rommio.ui.screen.player.PlayerScreen
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Actual production screen + repository + disk store + packaged native core; no user/server data. */
class PlayerScreenProgressTest {
    @get:Rule val ui = createAndroidComposeRule<NativePlayerTestActivity>()

    private class EngineControl {
        val failCapture = AtomicBoolean(false)
        val restored = java.util.concurrent.atomic.AtomicReference<CoreMemorySnapshot?>(null)
    }
    private fun fixture(legacy: Boolean = false, platform: String = "nes", block: suspend (AppContainer, File, EngineControl) -> Unit) = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val id = "player-screen-${UUID.randomUUID()}"
        val root = File(base.filesDir, id).apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
            override fun getDatabasePath(name: String) = File(root, name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: android.database.DatabaseErrorHandler?) =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, handler)
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$id-$name", mode)
        }
        val control = EngineControl()
        val container = AppContainer(context) {
            val native = LibretroPlayerEngine()
            object : PlayerEngine by native {
                override suspend fun captureMemorySnapshot(includeState: Boolean): CoreMemorySnapshot {
                    check(!control.failCapture.getAndSet(false)) { "Fixture save failure" }
                    return native.captureMemorySnapshot(includeState)
                }
                override suspend fun restoreMemorySnapshot(snapshot: CoreMemorySnapshot): Boolean {
                    val restored = native.restoreMemorySnapshot(snapshot)
                    if (restored) control.restored.set(native.captureMemorySnapshot(true))
                    return restored
                }
            }
        }
        val baseUrl = "https://player-fixture.invalid"
        val profile = container.authManager.configureServerProfile(baseUrl, discoveryResult = AuthDiscoveryResult(
            baseUrl, AuthCapabilities(), EdgeAuthMode.NONE, OriginAuthMode.ROMM_BEARER_PASSWORD, emptyList()))
        container.secretStore.bindVerifiedAccount(profile.id, VerifiedAccount(baseUrl, 1))
        val filename = when (platform) { "genesis" -> "fixture.md"; "sms" -> "fixture.sms"; "gamegear" -> "fixture.gg"; "n64" -> "fixture.z64"; "psp" -> "fixture.prx"; else -> "counter.nes" }
        val rom = container.libraryStore.romTarget(platform, filename).apply {
            parentFile!!.mkdirs()
            writeBytes(if (platform == "nes") counterRom() else File(base.filesDir, "expansion-fixtures/$filename").readBytes())
        }
        val digest = CoreBinary.sha256(rom)
        val install = DownloadedRomEntity(1, 1, platform, "Save flow fixture", filename, rom.path, rom.length(), 1,
            profile.id, baseUrl, 1, digest, progressScope = InstallIdentity.progress(baseUrl, 1, 1, 1, digest))
        container.downloadedRomDao.upsert(install)
        val original = container.libraryStore.saveRamFile(install)
        if (legacy) original.apply { parentFile!!.mkdirs(); writeBytes(ByteArray(8192) { 37 }) }
        ui.setContent {
            MaterialTheme {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "library") {
                    composable("library") { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Button(onClick = { nav.navigate("player") }) { Text("Play fixture") } } }
                    composable("player") { PlayerScreen(container, 1, 1) { nav.popBackStack() } }
                }
            }
        }
        try { withTimeout(60_000) { block(container, original, control) } }
        finally {
            withContext(NonCancellable) { container.progressSessions.active.value?.exitWithoutLatestProgress()?.await() }
            container.secretStore.clearAll(profile.id)
        }
    }

    private suspend fun active(container: AppContainer): ApplicationProgressSession {
        try {
            ui.waitUntil(20_000) { container.progressSessions.active.value?.state?.value?.progress?.phase == ProgressSessionPhase.RUNNING }
        } catch (error: Throwable) {
            throw AssertionError("Owner: ${container.progressSessions.active.value?.state?.value}\n${ui.onRoot().printToString()}", error)
        }
        return requireNotNull(container.progressSessions.active.value)
    }
    private fun click(text: String) { ui.onNodeWithText(text).performScrollTo().performClick() }
    private fun playFixture() {
        ui.onNodeWithText("Play fixture").assertIsDisplayed().performClick()
        ui.waitUntil(15_000) { ui.onAllNodesWithText("Continue with local progress").fetchSemanticsNodes().isNotEmpty() }
        click("Continue with local progress")
    }
    private fun menu() { ui.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }; ui.waitForIdle() }

    private fun consolePlayer(platform: String) = fixture(platform = platform) { container, _, _ ->
        playFixture()
        val owner = active(container)
        ui.onNodeWithTag("player.control.Up").assertIsDisplayed().performTouchInput { down(center); advanceEventTime(100); up() }
        val button = if (platform in listOf("sms", "gamegear")) "1" else if (platform == "psp") "Cross" else "A"
        ui.onNodeWithTag("player.control.$button").assertIsDisplayed().performTouchInput { down(center); advanceEventTime(100); up() }
        delay(1500)
        ui.onNodeWithTag("player.control.$button").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Released"))
        ui.onNodeWithText("The coroutine scope left the composition").assertDoesNotExist()
        val screenshot = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val base = ApplicationProvider.getApplicationContext<Context>()
        File(base.getExternalFilesDir(null), "controls-$platform.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        ui.onNodeWithTag("player.menu").performClick()
        click("Save snapshot")
        ui.waitUntil(10_000) { owner.state.value.progress?.lastCommitted?.checkpoint?.captureReason == "manual" }
        click("Snapshots & game saves")
        ui.waitUntil(10_000) { ui.onAllNodes(hasText("Manual ·", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodes(hasText("Manual ·", substring = true)).onFirst().performScrollTo().performClick()
        ui.waitUntil(10_000) { owner.state.value.progress?.lastCommitted?.checkpoint?.captureReason == "restore" }
        click("Leave game")
        ui.waitUntil(10_000) { container.progressSessions.active.value == null }
    }
    @Test fun genesisPlayerControlsAndSnapshotMenu() = consolePlayer("genesis")
    @Test fun masterSystemPlayerControlsAndSnapshotMenu() = consolePlayer("sms")
    @Test fun gameGearPlayerControlsAndSnapshotMenu() = consolePlayer("gamegear")
    @Test fun n64PlayerControlsAndSnapshotMenu() = consolePlayer("n64")
    @Test fun pspPlayerControlsAndSnapshotMenu() = consolePlayer("psp")

    @Test fun actualPlayerSavesExitsAndReopensThroughTheLibrary() = fixture { container, original, control ->
        playFixture()
        val first = active(container)
        delay(200)
        menu()
        click("Save snapshot")
        ui.waitUntil(10_000) { first.state.value.progress?.lastCommitted?.checkpoint?.captureReason == "manual" }
        val saved = requireNotNull(first.state.value.progress?.lastCommitted).checkpoint
        assertNotNull(saved.stateCompatibility)
        click("Snapshots & game saves")
        ui.waitUntil(10_000) { ui.onAllNodes(hasText("Manual ·", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodes(hasText("Manual ·", substring = true)).onFirst().performScrollTo().performClick()
        ui.waitUntil(10_000) { first.state.value.progress?.lastCommitted?.checkpoint?.captureReason == "restore" }
        assertTrue(first.history().await().entries.any { it.checkpoint.captureReason == "safety" })
        val beforeReset = first.state.value.progress?.lastCommitted?.operationId
        click("Reset core")
        ui.waitUntil(10_000) { first.state.value.progress?.phase == ProgressSessionPhase.PAUSED &&
            first.state.value.progress?.lastCommitted?.operationId != beforeReset }
        val expected = first.engine.captureMemorySnapshot(true)
        click("Leave game")
        ui.waitUntil(10_000) { container.progressSessions.active.value == null }
        playFixture()
        val second = active(container)
        val selection = second.state.value.decision as ProgressLaunchDecision.Ready
        assertTrue(selection.useState)
        assertNotNull(selection.checkpointId)
        assertNotSame(first, second)
        val restored = requireNotNull(control.restored.get())
        assertArrayEquals("Reopen restores the exact committed state before gameplay", expected.state, restored.state)
        expected.memory.forEach { (region, bytes) -> assertArrayEquals(bytes, restored.memory.getValue(region)) }
        assertFalse("The new player must not dual-write the legacy SRAM", original.exists())
        menu(); click("Leave game")
        ui.waitUntil(10_000) { container.progressSessions.active.value == null }
    }

    @Test fun actualPlayerImportsOldSaveWithoutChangingOriginal() = fixture(legacy = true) { container, original, _ ->
        val bytes = original.readBytes()
        playFixture()
        ui.waitUntil(15_000) { container.progressSessions.active.value?.state?.value?.phase == ApplicationProgressPhase.AWAITING_CHOICE }
        ui.waitUntil(10_000) { ui.onAllNodesWithText("Import in-game save").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Import in-game save").performClick()
        val owner = active(container)
        assertArrayEquals(bytes, original.readBytes())
        val history = owner.history().await().entries
        val imported = history.single { it.checkpoint.captureReason == "import" && it.checkpoint.stateCompatibility == null }
        assertTrue(imported.pinned)
        assertEquals(ProgressProtocol.sha256(bytes), imported.checkpoint.artifacts.single { it.role == "sram" }.sha256)
        menu(); click("Leave game")
        ui.waitUntil(10_000) { container.progressSessions.active.value == null }
        assertArrayEquals(bytes, original.readBytes())
    }

    @Test fun actualPlayerShowsFailedSaveAndRetriesBeforeExit() = fixture { container, _, fail ->
        playFixture()
        val owner = active(container)
        menu()
        ui.waitUntil(10_000) { owner.state.value.progress?.phase == ProgressSessionPhase.PAUSED }
        fail.failCapture.set(true)
        click("Leave game")
        ui.waitUntil(10_000) { owner.state.value.progress?.phase == ProgressSessionPhase.SAVE_FAILED }
        assertSame(owner, container.progressSessions.active.value)
        ui.onNodeWithText("Progress could not be saved").assertIsDisplayed()
        ui.onNodeWithText("Retry save").performClick()
        ui.waitUntil(10_000) { container.progressSessions.active.value == null }
        ui.onNodeWithText("Play fixture").assertIsDisplayed()
    }
}
