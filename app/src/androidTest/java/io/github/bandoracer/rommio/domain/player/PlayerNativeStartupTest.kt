package io.github.bandoracer.rommio.domain.player

import android.content.Context
import androidx.room.Room
import io.github.bandoracer.rommio.data.local.AppDatabase
import io.github.bandoracer.rommio.domain.progress.*
import com.swordfish.libretrodroid.LibretroDroid
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Mandatory bundled-core checks; an explicit fixture can qualify an additional build locally. */
@RunWith(AndroidJUnit4::class)
class PlayerNativeStartupTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    private suspend fun fixture(validRom: Boolean = true, romBytes: ByteArray = counterRom(), bundledOnly: Boolean = false, initialMemory: ByteArray? = ByteArray(8192) { if (it == 0) 37 else 0 }, prepareSession: suspend (PlayerSession) -> PlayerSession = { it }, block: suspend (PlayerSession, LibretroPlayerEngine, ActivityScenario<NativePlayerTestActivity>) -> Unit) {
        val root = File(context.filesDir, "native-startup-${UUID.randomUUID()}").apply { mkdirs() }
        val abi = requireNotNull(currentCoreAbi())
        val profile = buildPlatformRuntimeFamilies().first { it.familyId == "nes" }.runtimeOptions.first()
        val supplied = !bundledOnly && assets.list("native-player")?.contains("fceumm_libretro_android.so") == true
        val installed = if (supplied) {
            val source = File(root, "supplied.so").apply {
                assets.open("native-player/fceumm_libretro_android.so").use { input -> outputStream().use { input.copyTo(it) } }
            }
            CoreArtifactStore(File(root, "cores")).install(profile, abi, source, "supplied-test")
        } else {
            val store = CoreArtifactStore(File(root, "cores"))
            PackagedCoreArtifacts(File(root, "cores"), store) { context.assets.open(it) }.install(profile, abi)
        }
        val core = installed.file
        val rom = File(root, "counter.nes").apply { writeBytes(if (validRom) romBytes else byteArrayOf(1, 2, 3)) }
        val session = prepareSession(PlayerSession(1, "Synthetic counter", rom, core,
            buildPlatformRuntimeFamilies().first { it.familyId == "nes" }.runtimeOptions.first(),
            File(root, "system").apply { mkdirs() }, File(root, "saves").apply { mkdirs() },
            File(root, "saves/counter.srm"), File(root, "states").apply { mkdirs() },
            initialSaveRam = initialMemory,
            coreIdentity = installed.identity,
            progressBinding = io.github.bandoracer.rommio.domain.progress.ProgressBinding("https://fixture.example", "test", 1,
                listOf(io.github.bandoracer.rommio.domain.progress.ProgressContentFile(1, 0,
                    io.github.bandoracer.rommio.domain.progress.ProgressProtocol.sha256(rom.readBytes()), null, null)))))
        val scenario = ActivityScenario.launch(NativePlayerTestActivity::class.java)
        val engine = LibretroPlayerEngine()
        try {
            scenario.onActivity { activity -> activity.setContentView(engine.createOrAttachView(activity, activity, session)) }
            withTimeout(25_000) { block(session, engine, scenario) }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { engine.detach() }
            NativePlayerOwnership.acquire().close()
            android.util.Log.i("NativeStartupTest", "Native owner detached and released")
            // ActivityScenario's background helper can wait 45 seconds before finishing a
            // stopped activity. Native ownership is already released and asserted above.
            if (scenario.state == Lifecycle.State.CREATED) scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.close()
            android.util.Log.i("NativeStartupTest", "Test activity closed")
            // Cleanup is independent of the caller and must finish before removing core bytes.
            withContext(NonCancellable) {
                withTimeout(5000) {
                    while (true) {
                        val lease = runCatching { NativePlayerOwnership.acquire() }.getOrNull()
                        if (lease != null) { lease.close(); break }
                        delay(10)
                    }
                }
                root.deleteRecursively()
            }
        }
    }

    @Test fun readinessDoesNotRunGameAndRestorePrecedesGameplay() = runBlocking {
        fixture { session, engine, _ ->
            engine.awaitReady()
            assertArrayEquals(session.initialSaveRam, engine.persistSaveRam()!!.readBytes())
            val baseline = engine.saveState(1).readBytes()
            delay(100)
            assertArrayEquals(session.initialSaveRam, engine.persistSaveRam()!!.readBytes())
            engine.setPaused(false); delay(250); engine.setPaused(true)
            val advanced = engine.persistSaveRam()!!.readBytes()
            assertFalse("CPU counter must advance during gameplay", advanced.contentEquals(session.initialSaveRam))
            val selected = File(session.saveStatesDirectory, "selected.state").apply { writeBytes(baseline) }
            startPreparedPlayer(engine::awaitReady, { engine.loadState(selected) }, {})
            assertArrayEquals("Restore must return to the exact SRAM baseline", session.initialSaveRam, engine.persistSaveRam()!!.readBytes())
            // Capture while the real core is advancing, then prove its serialized state
            // describes the exact SRAM from that same callback, not a later emulation frame.
            repeat(3) {
                engine.setPaused(false); delay(75)
                val snapshot = engine.captureMemorySnapshot(includeState = true)
                assertFalse(snapshot.stateRejected)
                assertTrue(snapshot.memory.getValue(PersistentMemory.RTC).isEmpty())
                delay(75); engine.setPaused(true)
                selected.writeBytes(requireNotNull(snapshot.state))
                assertTrue(engine.loadState(selected))
                assertArrayEquals(snapshot.memory.getValue(PersistentMemory.SRAM), engine.persistSaveRam()!!.readBytes())
            }
        }
    }

    @Test fun backgroundCaptureAndRejectedStateReturnWithoutHanging() = runBlocking {
        fixture { session, engine, scenario ->
            engine.awaitReady(); engine.setPaused(false); delay(100)
            android.util.Log.i("NativeStartupTest", "Moving to background")
            scenario.moveToState(Lifecycle.State.CREATED)
            android.util.Log.i("NativeStartupTest", "Capturing in background")
            val first = engine.persistSaveRam()!!.readBytes()
            delay(100); assertArrayEquals(first, engine.persistSaveRam()!!.readBytes())
            assertTrue(engine.saveState(1).length() > 0)
            val bad = File(session.saveStatesDirectory, "bad.state").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            android.util.Log.i("NativeStartupTest", "Rejecting malformed state")
            withTimeout(2000) { assertFalse("Malformed state must reject promptly", engine.loadState(bad)) }
            // A rejected load must not poison later commands or leave a blocked GL latch.
            assertTrue(engine.saveState(2).length() > 0)
            android.util.Log.i("NativeStartupTest", "Background commands complete")
        }
    }

    @Test fun coherentMemoryRestoreRoundTripsThroughStateThenMemoryOnRealCore() = runBlocking {
        fixture { _, engine, _ ->
            engine.awaitReady()
            val initial = engine.captureMemorySnapshot(true)
            engine.setPaused(false); delay(150); engine.setPaused(true)
            assertFalse(initial.memory.getValue(PersistentMemory.SRAM).contentEquals(engine.captureMemorySnapshot(false).memory[PersistentMemory.SRAM]))
            assertTrue(engine.restoreMemorySnapshot(initial))
            val restored = engine.captureMemorySnapshot(false)
            initial.memory.keys.forEach { assertArrayEquals(initial.memory[it], restored.memory[it]) }
            assertTrue(restored.memory.getValue(PersistentMemory.RTC).isEmpty())
        }
    }

    @Test fun failedNativeLoadReportsFailureWithoutStartingGameplay() = runBlocking {
        fixture(validRom = false) { _, engine, _ ->
            try { engine.awaitReady(); fail("Malformed ROM became ready") }
            catch (error: IllegalStateException) {
                assertTrue(error.message.orEmpty().contains("native core failed"))
            }
        }
    }

    @Test fun replacingClosedPlayerCannotRevivePriorLifecycleObserver() = runBlocking {
        fixture { session, first, scenario ->
            first.awaitReady()
            val second = LibretroPlayerEngine()
            try {
                scenario.onActivity { activity ->
                    assertThrows(IllegalStateException::class.java) { second.createOrAttachView(activity, activity, session) }
                    first.detach()
                    activity.setContentView(second.createOrAttachView(activity, activity, session))
                }
                second.awaitReady()
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                assertArrayEquals(session.initialSaveRam, second.persistSaveRam()!!.readBytes())
            } finally { withContext(NonCancellable + Dispatchers.Main) { second.detach() } }
        }
    }

    @Test fun mapper19ExtraBatterySurvivesDurableRestoreRollbackAndColdBoot() = runBlocking {
        val root = File(context.filesDir, "battery-store-${UUID.randomUUID()}").apply { mkdirs() }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val store = ProgressStore(db, File(root, "store"))
        lateinit var expected: CoreMemorySnapshot
        lateinit var identity: ProgressIdentity
        lateinit var qualification: MemoryOnlyPersistence
        lateinit var checkpointId: String
        try {
            fixture(romBytes = mapper19Rom(), bundledOnly = true) { session, engine, _ ->
                engine.awaitReady()
                engine.setPaused(false); delay(150); engine.setPaused(true)
                expected = engine.captureMemorySnapshot(true)
                assertEquals(PersistentMemory.FCEUMM_SEGMENTS_V2, expected.memory.keys)
                assertEquals(8192, expected.memory.getValue(PersistentMemory.SRAM).size)
                assertEquals(128, expected.memory.getValue(PersistentMemory.BATTERY_1).size)
                assertTrue(expected.memory.getValue(PersistentMemory.BATTERY_1).any { it != 0.toByte() })
                identity = store.resolveIdentity(session.progressBinding!!)
                qualification = (registry(session).qualify(session, expected) as CorePersistenceSupport.Qualified).contract
                fun writer(capture: suspend (Boolean) -> CoreMemorySnapshot = engine::captureMemorySnapshot) =
                    SessionCheckpointWriter(store, identity, qualification, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                        emptyMap(), File(root, "staging"), capture, false)
                val writer = writer()
                val first = writer.captureAndCommit("manual")
                checkpointId = first.checkpoint.checkpointId
                assertEquals(setOf("save.srm", "save-1.srm", "resume.state"), first.checkpoint.artifacts.map { it.relativePath }.toSet())
                engine.setPaused(false); delay(150); engine.setPaused(true)
                val invalid = writer { state -> engine.captureMemorySnapshot(state).copy(state = byteArrayOf(1, 2, 3)) }
                    .captureAndCommit("manual")
                val coordinator = ProgressSessionCoordinator(engine, writer, MemoryCheckpointLoader(store, identity, qualification))
                try {
                    coordinator.start().await()
                    coordinator.restore(checkpointId, confirmGameRollback = true).await()
                    val restored = engine.captureMemorySnapshot(false)
                    expected.memory.keys.forEach { assertArrayEquals(expected.memory[it], restored.memory[it]) }
                    engine.setPaused(false); delay(100); engine.setPaused(true)
                    val beforeRejected = engine.captureMemorySnapshot(true)
                    val failure = runCatching { coordinator.restore(invalid.checkpoint.checkpointId, confirmGameRollback = true).await() }.exceptionOrNull()
                    assertTrue(failure?.message.orEmpty().contains("safety checkpoint has been restored"))
                    val afterRejected = engine.captureMemorySnapshot(true)
                    assertArrayEquals(beforeRejected.state, afterRejected.state)
                    expected.memory.keys.forEach { assertArrayEquals(beforeRejected.memory[it], afterRejected.memory[it]) }
                } finally { coordinator.exitWithoutLatestProgress().await() }
            }
            // New native instance, no serialized state: both battery regions must be restored before CPU execution.
            fixture(romBytes = mapper19Rom(), bundledOnly = true) { _, engine, _ ->
                engine.awaitReady()
                val actual = engine.captureMemorySnapshot(false)
                val cold = MemoryCheckpointLoader(store, identity, qualification).load(checkpointId, actual, false)
                assertNull(cold.state)
                assertTrue(engine.restoreMemorySnapshot(cold))
                val restored = engine.captureMemorySnapshot(false)
                expected.memory.keys.forEach { assertArrayEquals(expected.memory[it], restored.memory[it]) }
                engine.setPaused(false); delay(100); engine.setPaused(true)
                val advanced = engine.captureMemorySnapshot(false)
                assertFalse(expected.memory.getValue(PersistentMemory.SRAM).contentEquals(advanced.memory[PersistentMemory.SRAM]))
                assertFalse(expected.memory.getValue(PersistentMemory.BATTERY_1).contentEquals(advanced.memory[PersistentMemory.BATTERY_1]))
            }
        } finally { store.close(); db.close(); root.deleteRecursively() }
    }

    @Test fun allBatteryDestinationsArePreflightedBeforeAnyNativeWrite() = runBlocking {
        fixture(romBytes = mapper19Rom(), bundledOnly = true) { _, engine, _ ->
            engine.awaitReady()
            engine.setPaused(false); delay(100); engine.setPaused(true)
            val before = engine.captureMemorySnapshot(true)
            val regions = before.memory.keys.sortedBy { it.id }
            val data = regions.map { before.memory.getValue(it).copyOf() }.toTypedArray()
            data[0].fill(99)
            data[data.lastIndex] = byteArrayOf(1) // The final bank is absent; earlier banks must remain untouched.
            assertFalse(LibretroDroid.unserializeMemoryRegions(regions.map { it.id }.toIntArray(), data))
            assertFalse(LibretroDroid.unserializeMemoryRegions(intArrayOf(0, 0), arrayOf(data[0], data[0])))
            assertFalse(LibretroDroid.unserializeMemoryRegions(intArrayOf(0, 0x400), arrayOf(data[0], byteArrayOf())))
            assertFalse(LibretroDroid.unserializeMemoryRegions(intArrayOf(0), emptyArray()))
            val after = engine.captureMemorySnapshot(true)
            assertArrayEquals(before.state, after.state)
            regions.forEach { assertArrayEquals(before.memory[it], after.memory[it]) }
            val mismatched = before.copy(memory = before.memory + (PersistentMemory.BATTERY_1 to ByteArray(1)))
            assertFalse(engine.restoreMemorySnapshot(mismatched))
            val still = engine.captureMemorySnapshot(true)
            assertArrayEquals(before.state, still.state)
        }
    }

    @Test fun coldBatteryRestoreRebuildsNamcoSoundCachesBeforeGameplay() = runBlocking {
        fixture(romBytes = mapper19Rom(), bundledOnly = true) { _, engine, _ ->
            engine.awaitReady()
            val initial = engine.captureMemorySnapshot(false)
            val iram = initial.memory.getValue(PersistentMemory.BATTERY_1).copyOf()
            // Channel seven: nonzero frequency, short waveform, nonzero envelope.
            // The ROM only writes IRAM 0..2, so gameplay cannot rebuild these register caches.
            iram[0x78] = 0x40; iram[0x7a] = 1; iram[0x7c] = 0xe0.toByte(); iram[0x7f] = 0x0f
            assertTrue(engine.restoreMemorySnapshot(initial.copy(memory = initial.memory + (PersistentMemory.BATTERY_1 to iram))))
            engine.setPaused(false); delay(125); engine.setPaused(true)
            val state = requireNotNull(engine.captureMemorySnapshot(true).state)
            val marker = "IDX7".toByteArray()
            val offsets = (0..state.size - 12).filter { offset -> marker.indices.all { state[offset + it] == marker[it] } }
            assertEquals("Expected one Namco oscillator phase field", 1, offsets.size)
            val data = offsets.single() + 8
            assertTrue("Cold-restored frequency cache must advance the oscillator", (data until data + 4).any { state[it] != 0.toByte() })
        }
    }

    private fun registry(session: PlayerSession): FceummPersistenceRegistry {
        val root = session.coreLibrary.parentFile!!
        return FceummPersistenceRegistry(PackagedCoreArtifacts(root, CoreArtifactStore(root)) { context.assets.open(it) })
    }

    @Test fun qualificationUsesEffectiveCorrectedMapperAndAllowsProvenEmptySaves() = runBlocking {
        val corrected = counterRom().also { "DiskDude".toByteArray().copyInto(it, 7) }
        fixture(romBytes = corrected, bundledOnly = true, initialMemory = null) { session, engine, _ ->
            engine.awaitReady()
            val actual = engine.prepareForProgressSelection()
            assertEquals("fceumm-ines-v1:1:0:0:1:0:0", actual.persistenceDescriptor)
            val contract = (registry(session).qualify(session, actual) as CorePersistenceSupport.Qualified).contract
            assertEquals("ines1-m0-s0-b1-p0-c0", contract.saveCompatibility.layoutVersion)
            assertEquals(setOf(PersistentMemory.SRAM), contract.regions)
            assertEquals(8192, contract.requiredRegionSizes!![PersistentMemory.SRAM])
        }
        val nes2 = counterRom().also { it[7] = 8; it[10] = 0x70 }
        fixture(romBytes = nes2, bundledOnly = true, initialMemory = null) { session, engine, _ ->
            engine.awaitReady()
            val actual = engine.prepareForProgressSelection()
            assertEquals("fceumm-ines-v1:2:0:0:1:8192:0", actual.persistenceDescriptor)
            assertTrue(registry(session).qualify(session, actual) is CorePersistenceSupport.Qualified)
        }
        // This core's NROM mapper does not expose CHR NVRAM. Do not approve a partial bundle.
        val chrNv = nes2.copyOf().also { it[11] = 0x70 }
        fixture(romBytes = chrNv, bundledOnly = true, initialMemory = null) { session, engine, _ ->
            engine.awaitReady()
            val actual = engine.prepareForProgressSelection()
            assertEquals("fceumm-ines-v1:2:0:0:1:8192:8192", actual.persistenceDescriptor)
            assertTrue(registry(session).qualify(session, actual) is CorePersistenceSupport.Unsupported)
        }
        val noBattery = counterRom().also { it[6] = 0 }
        fixture(romBytes = noBattery, bundledOnly = true, initialMemory = null) { session, engine, _ ->
            engine.awaitReady()
            val actual = engine.prepareForProgressSelection()
            val contract = (registry(session).qualify(session, actual) as CorePersistenceSupport.Qualified).contract
            assertTrue(contract.saveCompatibility.allowsEmpty); assertTrue(contract.regions.isEmpty())
            assertTrue(actual.memory.values.all { it.isEmpty() })
            val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            val store = ProgressStore(db, File(session.saveStatesDirectory, "empty-store"))
            try {
                val identity = store.resolveIdentity(session.progressBinding!!)
                val writer = SessionCheckpointWriter(store, identity, contract, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                    emptyMap(), File(session.saveStatesDirectory, "empty-staging"), engine::captureMemorySnapshot, false)
                val committed = writer.captureAndCommit("manual", includeState = false)
                assertTrue(committed.checkpoint.saveDataComplete); assertTrue(committed.checkpoint.artifacts.isEmpty())
            } finally { store.close(); db.close() }
        }
    }

    @Test fun qualificationRejectsChangedContentUnknownBoardsAndMemoryLayoutDrift() = runBlocking {
        fixture(bundledOnly = true, initialMemory = null) { session, engine, _ ->
            engine.awaitReady()
            val actual = engine.prepareForProgressSelection()
            val registry = registry(session)
            assertTrue(registry.qualify(session, actual) is CorePersistenceSupport.Qualified)
            assertTrue(registry.qualify(session, actual.copy(persistenceDescriptor = "fceumm-ines-v1:1:4:0:1:0:0")) is CorePersistenceSupport.Unsupported)
            assertTrue(registry.qualify(session, actual.copy(persistenceDescriptor = "fceumm-ines-v1:1:0:1:1:0:0")) is CorePersistenceSupport.Unsupported)
            assertTrue(registry.qualify(session, actual.copy(persistenceDescriptor = null)) is CorePersistenceSupport.Unsupported)
            assertNotNull(runCatching { registry.qualify(session, actual.copy(memory = actual.memory + (PersistentMemory.SRAM to ByteArray(1)))) }.exceptionOrNull())
            val original = session.romPath.readBytes()
            session.romPath.writeBytes(original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            try { assertNotNull(runCatching { registry.qualify(session, actual) }.exceptionOrNull()) }
            finally { session.romPath.writeBytes(original) }
            val badRestore = actual.copy(persistenceDescriptor = "fceumm-ines-v1:1:19:0:1:0:0")
            assertNotNull(runCatching { engine.restoreMemorySnapshot(badRestore) }.exceptionOrNull())
            assertEquals(actual.persistenceDescriptor, engine.captureMemorySnapshot(false).persistenceDescriptor)
        }
    }

    @Test fun qualifiedPreparationAndCoordinatorResumeRealCoreAcrossLaunches() = runBlocking {
        val root = File(context.filesDir, "qualified-launch-${UUID.randomUUID()}").apply { mkdirs() }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val store = ProgressStore(db, File(root, "store")); val planner = ProgressLaunchPlanner(store)
        lateinit var expected: CoreMemorySnapshot
        val device = UUID.randomUUID().toString()
        try {
            repeat(2) { pass ->
                var workspace: ProgressWorkspace? = null
                try {
                    fixture(bundledOnly = true, initialMemory = null, prepareSession = { source ->
                        store.prepareWorkspace(store.resolveIdentity(source.progressBinding!!), source).also { workspace = it }.session
                    }) { session, engine, _ ->
                        val working = requireNotNull(workspace)
                        assertEquals(working.directory, session.savesDirectory.parentFile)
                        val prepared = planner.prepare(working, engine, registry(session)) as ProgressLaunchDecision.Ready
                        assertEquals(pass == 1, prepared.useState)
                        val coordinator = planner.start(prepared, engine, device, File(root, "staging"), false, working)
                        try {
                            if (pass == 0) {
                                coordinator.resume().await(); delay(125)
                                coordinator.checkpoint("pause").await()
                                expected = engine.captureMemorySnapshot(true)
                            } else {
                                val restored = engine.captureMemorySnapshot(true)
                                assertArrayEquals(expected.state, restored.state)
                                expected.memory.keys.forEach { assertArrayEquals(expected.memory[it], restored.memory[it]) }
                            }
                            coordinator.finishExit().await()
                            assertFalse(working.directory.exists())
                            assertTrue(store.recoverWorkingSessions(working.identity).isEmpty())
                        } finally {
                            if (coordinator.status.value.phase !in setOf(ProgressSessionPhase.CLOSED, ProgressSessionPhase.STOPPED))
                                coordinator.exitWithoutLatestProgress().await()
                        }
                    }
                } finally { workspace?.let { store.releaseSession(it.lease) } }
            }
        } finally { store.close(); db.close(); root.deleteRecursively() }
    }

    @Test fun preparationRequiresColdUnseededSessionAndCannotRewindAnActiveGame() = runBlocking {
        fixture(bundledOnly = true) { session, engine, _ ->
            val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            val store = ProgressStore(db, File(session.saveStatesDirectory, "seeded-store"))
            try {
                assertNotNull(runCatching { ProgressLaunchPlanner(store).prepare(session, engine, registry(session)) }.exceptionOrNull())
            } finally { store.close(); db.close() }
        }
        fixture(bundledOnly = true, initialMemory = null) { _, engine, _ ->
            engine.awaitReady(); engine.setPaused(false); delay(100); engine.setPaused(true)
            val before = engine.captureMemorySnapshot(true)
            assertNotNull(runCatching { engine.prepareForProgressSelection() }.exceptionOrNull())
            assertArrayEquals(before.state, engine.captureMemorySnapshot(true).state)
        }
    }

    @Test fun retainedWorkingFilesRequireRecoveryBeforeAnotherQualifiedLaunch() = runBlocking {
        val root = File(context.filesDir, "orphan-launch-${UUID.randomUUID()}").apply { mkdirs() }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val store = ProgressStore(db, File(root, "store")); val planner = ProgressLaunchPlanner(store)
        var workspace: ProgressWorkspace? = null
        try {
            fixture(bundledOnly = true, initialMemory = null, prepareSession = { source ->
                val identity = store.resolveIdentity(source.progressBinding!!)
                val abandoned = store.prepareWorkspace(identity, source)
                File(abandoned.session.savesDirectory, "unexpected.sav").writeText("retained native output")
                store.releaseSession(abandoned.lease)
                store.prepareWorkspace(identity, source).also { workspace = it }.session
            }) { session, engine, _ ->
                val working = requireNotNull(workspace)
                assertTrue(planner.prepare(working, engine, registry(session)) is ProgressLaunchDecision.RecoveryRequired)
                assertTrue(store.heads(working.identity, "save/current").isEmpty())
                engine.awaitReady(); engine.prepareForProgressSelection() // Recovery did not start gameplay.
                val orphan = store.recoverWorkingSessions(working.identity).single()
                assertEquals("retained native output", File(orphan.directory, "saves/unexpected.sav").readText())
                store.discardAbandonedWorkspace(working.identity, orphan.sessionId)
                val ready = planner.prepare(working, engine, registry(session)) as ProgressLaunchDecision.Ready
                val owner = planner.start(ready, engine, UUID.randomUUID().toString(), File(root, "unused"), false, working)
                owner.finishExit().await()
                assertFalse(working.directory.exists())
            }
        } finally {
            workspace?.let { store.releaseSession(it.lease) }
            store.close(); db.close(); root.deleteRecursively()
        }
    }

    private fun mapper19Rom(): ByteArray {
        val bytes = ByteArray(16 + 32768)
        byteArrayOf(0x4e, 0x45, 0x53, 0x1a, 2, 0, 0x32, 0x10).copyInto(bytes)
        // Original 6502 fixture: increment battery WRAM then copy its counter to Namco IRAM.
        val program = intArrayOf(0x78, 0xee, 0, 0x60, 0xd0, 8, 0xee, 1, 0x60, 0xd0, 3, 0xee, 2, 0x60,
            0xa9, 0x80, 0x8d, 0, 0xf8, 0xad, 0, 0x60, 0x8d, 0, 0x48,
            0xad, 1, 0x60, 0x8d, 0, 0x48, 0xad, 2, 0x60, 0x8d, 0, 0x48, 0x4c, 1, 0xe0)
        program.map { it.toByte() }.toByteArray().copyInto(bytes, 16 + 24576)
        for (offset in listOf(0x7ffa, 0x7ffc, 0x7ffe)) { bytes[16 + offset] = 0; bytes[16 + offset + 1] = 0xe0.toByte() }
        return bytes
    }


}
