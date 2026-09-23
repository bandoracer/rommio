package io.github.bandoracer.rommio.domain.player

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.bandoracer.rommio.data.local.AppDatabase
import io.github.bandoracer.rommio.domain.progress.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real packaged cores and privately staged ROMs on a disposable emulator. */
class ExpansionProgressTest {
    @Test fun packagedExpansionCoresCaptureRestoreAndColdBootBothLanes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixtures = File(context.filesDir, "expansion-fixtures")
        assumeTrue("Stage private fixtures on a dedicated emulator", fixtures.isDirectory)
        val requestedRuntime = InstrumentationRegistry.getArguments().getString("rommioRuntime")
        val cases = listOf("genesis_plus_gx" to "md", "genesis_plus_gx" to "sms", "genesis_plus_gx" to "gg", "pcsx_rearmed" to "exe", "mupen64plus_next_gles3" to "z64", "ppsspp" to "prx",
            "stella" to "a26", "prosystem" to "a78", "handy" to "lnx").filter { (runtime, extension) ->
            File(fixtures, "fixture.$extension").isFile && (requestedRuntime == null || runtime == requestedRuntime)
        }
        assertTrue("Stage at least one private expansion fixture", cases.isNotEmpty())
        for ((runtime, extension) in cases) {
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
            val system = File(files, "system").apply { mkdirs() }
            val firmware = mutableMapOf<String, String>()
            if (runtime == "ppsspp") {
                val manifest = org.json.JSONObject(context.assets.open("cores/ppsspp/system-manifest.json").bufferedReader().use { it.readText() })
                manifest.keys().forEach { name ->
                    val target = File(system, name); target.parentFile!!.mkdirs()
                    target.writeBytes(context.assets.open("cores/ppsspp/system/$name").use { it.readBytes() })
                    val digest = manifest.getJSONObject(name).getString("sha256")
                    assertEquals(digest, CoreBinary.sha256(target)); firmware[name] = digest
                }
            }
            val variables = profile.defaultVariables + if (runtime == "pcsx_rearmed") mapOf("pcsx_rearmed_bios" to "HLE") else emptyMap()
            val session = PlayerSession(1, "Cartridge fixture", rom, core.file, profile.copy(shader = PlayerShader.DEFAULT),
                system, File(files, "save").apply { mkdirs() }, File(files, "save/legacy.srm"), File(files, "states").apply { mkdirs() }, variables = variables, firmwareSha256 = firmware, coreIdentity = core.identity, progressBinding = binding)
            try { withTimeout(90_000) {
                val owner = manager.prepare(session, false, true).await()
                scenario.onActivity { it.setContentView(owner.attach(it, it)) }
                val initial = owner.select(ProgressLaunchRequest.Automatic).await()
                assertTrue("$runtime: $initial", initial is ProgressLaunchDecision.Ready)
                owner.resume().await(); delay(400)
                owner.checkpoint("pause").await()
                if (runtime == "ppsspp") {
                    val slot = File(owner.workspace.session.savesDirectory, "PSP/SAVEDATA/ROMMIO001").apply { mkdirs() }
                    File(slot, "DATA.BIN").writeText("portable game progress")
                    File(slot, "PARAM.SFO").writeText("slot metadata")
                    File(slot, "EMPTY").mkdir()
                }
                if (runtime != "ppsspp") {
                    val initialMemory = owner.engine.captureMemorySnapshot(true)
                    val ram = initialMemory.memory.getValue(PersistentMemory.SRAM).copyOf()
                    if (ram.isNotEmpty()) {
                        ram[ram.size / 4] = (ram[ram.size / 4].toInt() xor 0x5a).toByte()
                        ram[ram.size * 3 / 4] = (ram[ram.size * 3 / 4].toInt() xor 0xa5).toByte()
                        assertTrue(owner.engine.restoreMemorySnapshot(initialMemory.copy(memory = initialMemory.memory + (PersistentMemory.SRAM to ram))))
                    }
                }
                val checkpoint = owner.checkpoint("manual").await()
                assertFalse("$runtime snapshot capture", checkpoint.stateRejected)
                val before = owner.engine.captureMemorySnapshot(true)
                assertNotNull(before.state)
                if (runtime == "ppsspp") assertTrue("Compress the PSP snapshot before Java allocation", before.state!!.size < 8 * 1024 * 1024)
                owner.resume().await(); delay(200); owner.checkpoint("pause").await()
                if (runtime == "ppsspp") {
                    File(owner.workspace.session.savesDirectory, "PSP/SAVEDATA/ROMMIO001").deleteRecursively()
                    File(owner.workspace.session.savesDirectory, "LATER.BIN").writeText("new progress")
                }
                owner.restore(checkpoint.checkpoint.checkpointId, true).await()
                val restored = owner.engine.captureMemorySnapshot(false)
                before.memory.forEach { (id, data) -> assertArrayEquals("$runtime restored $id", data, restored.memory[id]) }
                if (runtime == "ppsspp") {
                    val bytes = restored.memory.getValue(PersistentMemory.SRAM)
                    val audit = PSPMemoryStickBundle.matches(owner.workspace.session.savesDirectory, bytes)
                    if (!audit) PSPMemoryStickBundle.files(bytes).forEach { (name, entry) ->
                        val path = File(owner.workspace.session.savesDirectory, name).toPath()
                        println("PSP audit $name directory=${entry.directory} readonly=${entry.readOnly} expected=${entry.seconds}:${entry.nanos} actual=${java.nio.file.Files.getLastModifiedTime(path).to(java.util.concurrent.TimeUnit.NANOSECONDS)} permissions=${java.nio.file.Files.getPosixFilePermissions(path)}")
                    }
                    assertTrue("Live PSP workspace must match its native bundle before close", audit)
                }
                owner.finishExit().await()
                val coldOwner = manager.prepare(session, false, true).await()
                scenario.onActivity { it.setContentView(coldOwner.attach(it, it)) }
                val coldDecision = coldOwner.select(ProgressLaunchRequest.ColdBootCurrent).await()
                assertTrue("$runtime: $coldDecision; retained files: ${root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path }.toList()}", coldDecision is ProgressLaunchDecision.Ready)
                val cold = coldDecision as ProgressLaunchDecision.Ready
                assertFalse(cold.useState)
                val actual = coldOwner.engine.captureMemorySnapshot(false)
                before.memory.forEach { (id, data) -> assertArrayEquals("$runtime cold boot $id", data, actual.memory[id]) }
                if (runtime == "handy" && before.memory.getValue(PersistentMemory.SRAM).isNotEmpty()) {
                    val eeprom = coldOwner.workspace.session.savesDirectory.walkTopDown().single { it.isFile && it.extension == "eeprom" }
                    assertArrayEquals("Lynx EEPROM cold boot file", before.memory.getValue(PersistentMemory.SRAM), eeprom.readBytes())
                }
                if (runtime == "ppsspp") {
                    assertEquals("portable game progress", File(coldOwner.workspace.session.savesDirectory, "PSP/SAVEDATA/ROMMIO001/DATA.BIN").readText())
                    assertFalse(File(coldOwner.workspace.session.savesDirectory, "LATER.BIN").exists())
                }
                coldOwner.finishExit().await()
                val freshOwner = manager.prepare(session, false, true).await()
                scenario.onActivity { it.setContentView(freshOwner.attach(it, it)) }
                val freshDecision = freshOwner.select(ProgressLaunchRequest.Automatic).await()
                assertTrue("$runtime fresh snapshot: $freshDecision", freshDecision is ProgressLaunchDecision.Ready)
                assertTrue("$runtime must use the matching snapshot", (freshDecision as ProgressLaunchDecision.Ready).useState)
                val fresh = freshOwner.engine.captureMemorySnapshot(false)
                before.memory.forEach { (id, data) -> assertArrayEquals("$runtime fresh snapshot $id", data, fresh.memory[id]) }
                freshOwner.finishExit().await()
                println("PASS Android cartridge $runtime $extension: game-save cold boot, snapshot restore, fresh snapshot restore")
            } } finally {
                withContext(NonCancellable) { manager.active.value?.exitWithoutLatestProgress()?.await(); scenario.close(); store.close(); db.close(); root.deleteRecursively() }
            }
        }
    }
}
