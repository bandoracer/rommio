package io.github.bandoracer.rommio.domain.player

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.bandoracer.rommio.data.local.AppDatabase
import io.github.bandoracer.rommio.domain.progress.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real packaged cores and privately staged ROMs on a disposable emulator. */
class CartridgeProgressTest {
    @Test fun packagedCartridgesCaptureRestoreAndColdBootBothLanes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixtures = File(context.filesDir, "cartridge-fixtures")
        assumeTrue("Stage private fixtures on a dedicated emulator", fixtures.isDirectory)
        for ((runtime, extension) in listOf("gambatte" to "gb", "gambatte" to "gbc", "mgba" to "gba", "snes9x" to "sfc", "melonds_ds" to "nds")) {
            val root = File(context.filesDir, "cartridge-roundtrip-${UUID.randomUUID()}").apply { mkdirs() }
            val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            val store = ProgressStore(db, File(root, "store"))
            val profile = buildPlatformRuntimeFamilies().flatMap { it.runtimeOptions }.first { it.runtimeId == runtime }
            val packaged = PackagedCoreArtifacts(File(root, "cores"), CoreArtifactStore(File(root, "cores"))) { context.assets.open(it) }
            val core = packaged.install(profile, requireNotNull(currentCoreAbi()))
            val rom = File(fixtures, "fixture.$extension")
            val binding = ProgressBinding("https://fixture.example", "cartridge-test", 1, listOf(ProgressContentFile(1, 0, CoreBinary.sha256(rom), null, null)))
            val manager = ApplicationProgressSessions({ store }, FceummPersistenceRegistry(packaged), { UUID.randomUUID().toString() }, modern = true)
            val scenario = ActivityScenario.launch(NativePlayerTestActivity::class.java)
            val files = File(root, "native").apply { mkdirs() }
            val session = PlayerSession(1, "Cartridge fixture", rom, core.file, profile.copy(shader = PlayerShader.DEFAULT),
                File(files, "system").apply { mkdirs() }, File(files, "save").apply { mkdirs() }, File(files, "save/legacy.srm"), File(files, "states").apply { mkdirs() }, coreIdentity = core.identity, progressBinding = binding)
            try { withTimeout(90_000) {
                val owner = manager.prepare(session, false, true).await()
                scenario.onActivity { it.setContentView(owner.attach(it, it)) }
                val initial = owner.select(ProgressLaunchRequest.Automatic).await()
                assertTrue("$runtime: $initial", initial is ProgressLaunchDecision.Ready)
                owner.resume().await(); delay(400)
                val checkpoint = owner.checkpoint("manual").await()
                assertFalse("$runtime snapshot capture", checkpoint.stateRejected)
                val before = owner.engine.captureMemorySnapshot(true)
                assertNotNull(before.state)
                owner.resume().await(); delay(200); owner.checkpoint("pause").await()
                owner.restore(checkpoint.checkpoint.checkpointId, true).await()
                val restored = owner.engine.captureMemorySnapshot(false)
                before.memory.forEach { (id, data) -> assertArrayEquals("$runtime restored $id", data, restored.memory[id]) }
                owner.finishExit().await()
                val coldOwner = manager.prepare(session, false, true).await()
                scenario.onActivity { it.setContentView(coldOwner.attach(it, it)) }
                val coldDecision = coldOwner.select(ProgressLaunchRequest.ColdBootCurrent).await()
                assertTrue("$runtime: $coldDecision; retained files: ${root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path }.toList()}", coldDecision is ProgressLaunchDecision.Ready)
                val cold = coldDecision as ProgressLaunchDecision.Ready
                assertFalse(cold.useState)
                val actual = coldOwner.engine.captureMemorySnapshot(false)
                before.memory.forEach { (id, data) -> assertArrayEquals("$runtime cold boot $id", data, actual.memory[id]) }
                coldOwner.finishExit().await()
                println("PASS Android cartridge $runtime $extension: game-save cold boot and snapshot restore")
            } } finally {
                withContext(NonCancellable) { manager.active.value?.exitWithoutLatestProgress()?.await(); scenario.close(); store.close(); db.close(); root.deleteRecursively() }
            }
        }
    }
}
