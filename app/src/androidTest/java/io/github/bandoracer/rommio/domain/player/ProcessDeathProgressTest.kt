package io.github.bandoracer.rommio.domain.player

import android.content.Context
import android.os.Process
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.bandoracer.rommio.data.local.AppDatabase
import io.github.bandoracer.rommio.domain.progress.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** The host script kills the seed process only after observing its fsynced background-save marker. */
class ProcessDeathProgressTest {
    @Test fun backgroundCheckpointSurvivesExternalProcessKillAndOfflineRelaunch(): Unit = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("progressKillPhase")
        assumeTrue("Opt-in external force-stop test", phase in setOf("seed", "verify"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.filesDir, "process-death-progress")
        if (phase == "seed") { root.deleteRecursively(); context.deleteDatabase("process-death-progress.db") }
        root.mkdirs()
        val database = Room.databaseBuilder(context, AppDatabase::class.java, "process-death-progress.db").build()
        val store = ProgressStore(database, File(root, "store"))
        val profile = buildPlatformRuntimeFamilies().first { it.familyId == "nes" }.runtimeOptions.first()
        val artifacts = CoreArtifactStore(File(root, "cores"))
        val packaged = PackagedCoreArtifacts(File(root, "cores"), artifacts) { context.assets.open(it) }
        val core = packaged.install(profile, requireNotNull(currentCoreAbi()))
        val rom = File(root, "counter.nes").apply { if (!exists()) writeBytes(counterRom()) }
        val binding = ProgressBinding("https://offline.fixture", "1", 1, listOf(ProgressContentFile(1, 0, CoreBinary.sha256(rom), null, null)))
        val source = PlayerSession(1, "Process fixture", rom, core.file, profile.copy(shader = PlayerShader.DEFAULT),
            File(root, "system").apply { mkdirs() }, File(root, "save").apply { mkdirs() }, File(root, "save/legacy.srm"),
            File(root, "states").apply { mkdirs() }, coreIdentity = core.identity, progressBinding = binding)
        val manager = ApplicationProgressSessions({ store }, FceummPersistenceRegistry(packaged), { UUID.randomUUID().toString() }, modern = true)
        val scenario = ActivityScenario.launch(NativePlayerTestActivity::class.java)
        try {
            val owner = manager.prepare(source, false, true).await()
            scenario.onActivity { it.setContentView(owner.attach(it, it)) }
            if (phase == "seed") {
                assertTrue(owner.select().await() is ProgressLaunchDecision.Ready)
                owner.resume().await(); delay(250)
                scenario.moveToState(Lifecycle.State.CREATED)
                withTimeout(15_000) { while (owner.state.value.progress?.lastCommitted?.checkpoint?.captureReason != "background") delay(25) }
                val checkpoint = owner.state.value.progress!!.lastCommitted!!.checkpoint
                val marker = JSONObject().put("pid", Process.myPid()).put("checkpointId", checkpoint.checkpointId)
                    .put("stateHash", checkpoint.artifacts.single { it.role == "state" }.sha256)
                FileOutputStream(File(root, "ready.json")).use { it.write(marker.toString().toByteArray()); it.fd.sync() }
                withTimeout(90_000) { awaitCancellation() } // Must be externally killed, never gracefully closed.
            } else {
                val marker = JSONObject(File(root, "ready.json").readText())
                assertNotEquals(marker.getInt("pid"), Process.myPid())
                assertTrue(owner.select().await() is ProgressLaunchDecision.RecoveryRequired)
                val abandoned = owner.interruptedSessions().await()
                assertEquals(1, abandoned.size)
                assertTrue(abandoned.single().second.isEmpty()) // Background transaction completed before the kill.
                val choice = owner.discardInterruptedSession(abandoned.single().first.sessionId).await() as ProgressLaunchDecision.Ready
                assertEquals(marker.getString("checkpointId"), choice.checkpointId)
                assertTrue(choice.useState)
                val actual = owner.engine.captureMemorySnapshot(true)
                assertEquals(marker.getString("stateHash"), ProgressProtocol.sha256(actual.state!!))
                val identity = store.resolveIdentity(binding)
                val expected = store.checkpoint(identity, "game/current")!!
                for (artifact in expected.artifacts) {
                    val region = PersistentMemory.FCEUMM_SEGMENTS_V2.single { it.path == artifact.relativePath }
                    assertEquals(artifact.sha256, ProgressProtocol.sha256(actual.memory.getValue(region)))
                }
                assertTrue(store.publicationStatus(identity).all { it.status == "PENDING" })
                owner.finishExit().await()
            }
        } finally {
            withContext(NonCancellable) {
                manager.active.value?.exitWithoutLatestProgress()?.await()
                if (scenario.state == Lifecycle.State.CREATED) scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.close(); store.close(); database.close()
                if (phase == "verify") { root.deleteRecursively(); context.deleteDatabase("process-death-progress.db") }
            }
        }
    }
}
