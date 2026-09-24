package io.github.bandoracer.rommio.domain.player

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.test.core.app.ApplicationProvider
import io.github.bandoracer.rommio.AppContainer
import io.github.bandoracer.rommio.ThemeUnderTest
import io.github.bandoracer.rommio.data.auth.VerifiedAccount
import io.github.bandoracer.rommio.domain.playdata.SaveConflict
import io.github.bandoracer.rommio.domain.playdata.SaveConflictKind
import io.github.bandoracer.rommio.domain.progress.ApplicationProgressPhase
import io.github.bandoracer.rommio.domain.progress.ProgressLaunchDecision
import io.github.bandoracer.rommio.domain.progress.ProgressOperation
import io.github.bandoracer.rommio.domain.progress.ProgressProtocol
import io.github.bandoracer.rommio.domain.progress.ProgressRefChange
import io.github.bandoracer.rommio.domain.progress.ProgressSessionPhase
import io.github.bandoracer.rommio.model.AuthCapabilities
import io.github.bandoracer.rommio.model.AuthDiscoveryResult
import io.github.bandoracer.rommio.model.DownloadedRomEntity
import io.github.bandoracer.rommio.model.EdgeAuthMode
import io.github.bandoracer.rommio.model.InstallIdentity
import io.github.bandoracer.rommio.model.OriginAuthMode
import io.github.bandoracer.rommio.ui.component.ResumeConflictSheet
import io.github.bandoracer.rommio.ui.navigation.NavRoutes
import io.github.bandoracer.rommio.ui.screen.player.PlayerScreen
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Conflict end to end (docs/theme-layouts.md "Conflict"): a real game session commits a resume
 * point, a second head of the same resume reference arrives as another device's synced operation
 * (a test-only fixture built from the first head's checkpoint), the saves reader reports the
 * conflict, the Conflict sheet chooses the older, non-default head, and the real player — opened
 * with that choice exactly as the app's route does — resolves the branch and starts from it.
 */
class ConflictToPlayerTest {
    @get:Rule val ui = createAndroidComposeRule<NativePlayerTestActivity>()

    @Test
    fun choosingTheOlderResumePointStartsThePlayerFromIt() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val id = "conflict-e2e-${UUID.randomUUID()}"
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
        val container = AppContainer(context) { LibretroPlayerEngine() }
        val baseUrl = "https://conflict-fixture.invalid"
        val profile = container.authManager.configureServerProfile(baseUrl, discoveryResult = AuthDiscoveryResult(
            baseUrl, AuthCapabilities(), EdgeAuthMode.NONE, OriginAuthMode.ROMM_BEARER_PASSWORD, emptyList()))
        container.secretStore.bindVerifiedAccount(profile.id, VerifiedAccount(baseUrl, 1))
        val rom = container.libraryStore.romTarget("nes", "counter.nes").apply { parentFile!!.mkdirs(); writeBytes(counterRom()) }
        val digest = CoreBinary.sha256(rom)
        container.downloadedRomDao.upsert(DownloadedRomEntity(1, 1, "nes", "Counter Quest", "counter.nes", rom.path, rom.length(), 1,
            profile.id, baseUrl, 1, digest, progressScope = InstallIdentity.progress(baseUrl, 1, 1, 1, digest)))

        var conflict by mutableStateOf<SaveConflict?>(null)
        ui.setContent {
            RommioTheme(ThemeUnderTest.preferences) {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "home") {
                    composable("home") {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Button(onClick = { nav.navigate(NavRoutes.player(1, 1)) }) { Text("Play fixture") }
                        }
                        conflict?.let { found ->
                            ResumeConflictSheet(
                                gameTitle = "Counter Quest",
                                heads = found.choices,
                                coverUrl = null,
                                canPlay = true,
                                kind = found.kind,
                                // The app's route: the chosen head's checkpoint rides along as `keep`.
                                onKeep = { entry -> conflict = null; nav.navigate(NavRoutes.player(1, 1, entry.key.checkpointId)) },
                                onDismiss = { conflict = null },
                            )
                        }
                    }
                    composable(
                        NavRoutes.PLAYER,
                        arguments = listOf(
                            navArgument("romId") { type = NavType.IntType },
                            navArgument("fileId") { type = NavType.IntType },
                            navArgument("keep") { type = NavType.StringType; nullable = true; defaultValue = null },
                            navArgument("start") { type = NavType.StringType; nullable = true; defaultValue = null },
                        ),
                    ) { entry ->
                        PlayerScreen(container, 1, 1, keepResume = entry.arguments?.getString("keep")) { nav.popBackStack() }
                    }
                }
            }
        }
        try {
            withTimeout(120_000) {
                // 1. A real session commits a resume point (a save snapshot), then leaves.
                ui.onNodeWithText("Play fixture").performClick()
                ui.waitUntil(20_000) { ui.onAllNodesWithTag("player.start.resume").fetchSemanticsNodes().isNotEmpty() }
                ui.onNodeWithTag("player.start.resume").performScrollTo().performClick()
                ui.waitUntil(20_000) { container.progressSessions.active.value?.state?.value?.progress?.phase == ProgressSessionPhase.RUNNING }
                val first = requireNotNull(container.progressSessions.active.value)
                val identity = first.workspace.identity
                val saved = first.checkpoint("manual").await()
                assertTrue("The capture has a state", saved.checkpoint.stateCompatibility != null)
                first.exitWithoutLatestProgress().await()
                ui.waitUntil(20_000) { container.progressSessions.active.value == null }

                // 2. Another device's resume point for the same reference arrives (a sibling head).
                val store = container.progressStorage.get()
                val references = store.referenceSnapshot(identity)
                val (resumeReference, heads) = references.entries.single { it.key.startsWith("state/resume/") }
                val head = heads.single()
                val newest = requireNotNull(head.checkpoint)
                val other = newest.copy(
                    checkpointId = UUID.randomUUID().toString(),
                    deviceId = UUID.randomUUID().toString(),
                    sessionId = UUID.randomUUID().toString(),
                    captureReason = "exit",
                    createdAtEpochMs = newest.createdAtEpochMs - 60 * 60_000L,
                )
                val remote = ProgressOperation(3, UUID.randomUUID().toString(), identity.binding,
                    listOf(ProgressRefChange(resumeReference, emptyList(), other.checkpointId, false)), listOf(other),
                    other.deviceId, other.sessionId, other.createdAtEpochMs)
                store.received(identity, ProgressProtocol.encode(remote), emptyMap())
                assertEquals("Two heads of one resume reference", 2, store.heads(identity, resumeReference).size)

                // 3. The saves reader reports it; the Conflict sheet chooses the older head.
                val saves = container.savedProgress.gameSaves(identity.binding.romId, null)
                val found = requireNotNull(saves.conflict) { "No conflict: $saves" }
                assertEquals(SaveConflictKind.RESUME, found.kind)
                assertEquals(listOf(newest.checkpointId, other.checkpointId), found.choices.map { it.key.checkpointId })
                conflict = found
                ui.waitUntil(10_000) { ui.onAllNodesWithText("Counter Quest has two resume points. Choose which to keep playing.").fetchSemanticsNodes().isNotEmpty() }
                ui.onNodeWithTag("conflict.choice.0").assertIsSelected()
                ui.onNodeWithTag("conflict.choice.1").performClick()
                ui.onNodeWithTag("conflict.choice.1").assertIsSelected()
                ui.onNodeWithTag("conflict.keep").performScrollTo().performClick()

                // 4. The player applies the choice (resolving the branch) and starts from it.
                ui.waitUntil(20_000) { ui.onAllNodesWithTag("player.start.resume").fetchSemanticsNodes().isNotEmpty() }
                ui.onNodeWithTag("player.start.resume").performScrollTo().performClick()
                try {
                    ui.waitUntil(30_000) {
                        container.progressSessions.active.value?.state?.value?.phase == ApplicationProgressPhase.ACTIVE
                    }
                } catch (error: Throwable) {
                    throw AssertionError("Owner: ${container.progressSessions.active.value?.state?.value}\n${ui.onRoot().printToString()}", error)
                }
                val second = requireNotNull(container.progressSessions.active.value)
                val ready = second.state.value.decision as ProgressLaunchDecision.Ready
                assertEquals("The player starts from the chosen resume point", other.checkpointId, ready.checkpointId)
                assertTrue(ready.useState)
                assertEquals("The choice resolved the reference", 1, store.heads(identity, resumeReference).size)
                val after = container.savedProgress.gameSaves(identity.binding.romId, null)
                assertEquals(null, after.conflict)
                assertEquals(other.checkpointId, after.resume?.key?.checkpointId)
            }
        } finally {
            withContext(NonCancellable) { container.progressSessions.active.value?.exitWithoutLatestProgress()?.await() }
            container.secretStore.clearAll(profile.id)
        }
    }
}
