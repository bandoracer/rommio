package io.github.bandoracer.rommio.domain.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.bandoracer.rommio.data.local.AppDatabase
import io.github.bandoracer.rommio.domain.progress.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class ApplicationProgressSessionTest {
    private class Fixture(val root: File, val store: ProgressStore, val source: PlayerSession,
        val manager: ApplicationProgressSessions, val scenario: ActivityScenario<NativePlayerTestActivity>) {
        suspend fun open(legacy: Boolean = false): ApplicationProgressSession {
            val owner = manager.prepare(source, legacy, false).await()
            scenario.onActivity { it.setContentView(owner.attach(it, it)) }
            return owner
        }
        suspend fun playable(): ApplicationProgressSession = open().also {
            assertTrue(it.select().await() is ProgressLaunchDecision.Ready)
            it.resume().await(); delay(150)
        }
    }

    private suspend fun fixture(engineFactory: () -> PlayerEngine = { LibretroPlayerEngine() },
        providerGate: (suspend () -> Unit)? = null, block: suspend Fixture.() -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.filesDir, "app-session-${UUID.randomUUID()}").apply { mkdirs() }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val store = ProgressStore(db, File(root, "store"))
        val profile = buildPlatformRuntimeFamilies().first { it.familyId == "nes" }.runtimeOptions.first()
        val cores = CoreArtifactStore(File(root, "cores"))
        val packaged = PackagedCoreArtifacts(File(root, "cores"), cores) { context.assets.open(it) }
        val installed = packaged.install(profile, requireNotNull(currentCoreAbi()))
        val rom = File(root, "counter.nes").apply { writeBytes(counterRom()) }
        val source = PlayerSession(1, "Owner fixture", rom, installed.file, profile.copy(shader = PlayerShader.DEFAULT),
            File(root, "system").apply { mkdirs() }, File(root, "save").apply { mkdirs() }, File(root, "save/legacy.srm"),
            File(root, "states").apply { mkdirs() }, coreIdentity = installed.identity,
            progressBinding = ProgressBinding("https://fixture.example", "test", 1,
                listOf(ProgressContentFile(1, 0, CoreBinary.sha256(rom), null, null))))
        val manager = ApplicationProgressSessions({ providerGate?.invoke(); store }, FceummPersistenceRegistry(packaged), { UUID.randomUUID().toString() }, engineFactory)
        val scenario = ActivityScenario.launch(NativePlayerTestActivity::class.java)
        try { withTimeout(45_000) { Fixture(root, store, source, manager, scenario).block() } }
        finally {
            withContext(NonCancellable) {
                manager.active.value?.exitWithoutLatestProgress()?.await()
                NativePlayerOwnership.acquire().close()
                if (scenario.state == Lifecycle.State.CREATED) scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.close(); store.close(); db.close(); root.deleteRecursively()
            }
        }
    }

    @Test fun applicationOwnerCommitsExitAndRestoresThroughAFreshOwnedLaunch() = runBlocking {
        fixture {
            val first = playable()
            first.checkpoint("pause").await()
            val expected = first.engine.captureMemorySnapshot(true)
            assertSame(first, manager.active.value)
            assertNotNull(runCatching { manager.prepare(source, false, false).await() }.exceptionOrNull())
            val committed = first.finishExit().await()
            assertNull(manager.active.value)
            assertFalse(first.workspace.directory.exists())
            assertEquals(committed.checkpoint, store.checkpoint(first.workspace.identity, "save/current"))
            val second = open()
            assertTrue((second.select().await() as ProgressLaunchDecision.Ready).useState)
            val restored = second.engine.captureMemorySnapshot(true)
            assertArrayEquals(expected.state, restored.state)
            expected.memory.forEach { (region, bytes) -> assertArrayEquals(bytes, restored.memory[region]) }
            second.finishExit().await()
        }
    }

    @Test fun canceledPreparationWaiterLeavesAnObservableOwnedSession() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        fixture(providerGate = { entered.complete(Unit); release.await() }) {
            val pending = manager.prepare(source, false, false)
            entered.await(); pending.cancel(); release.complete(Unit)
            val owned = manager.active.first { it != null }!!
            assertTrue(owned.workspace.directory.exists())
            assertEquals(ApplicationProgressPhase.WAITING_FOR_VIEW, owned.state.value.phase)
            owned.exitWithoutLatestProgress().await()
            assertNull(manager.active.value)
            assertFalse(owned.workspace.directory.exists())
        }
    }

    @Test fun legacyProgressRequiresChoiceWithoutStartingGameplayOrWritingV2() = runBlocking {
        fixture {
            source.saveRamFile.writeText("legacy must remain")
            val owner = open(legacy = true)
            assertTrue(owner.select().await() is ProgressLaunchDecision.RecoveryRequired)
            owner.engine.prepareForProgressSelection()
            assertTrue(store.heads(owner.workspace.identity, "save/current").isEmpty())
            assertEquals("legacy must remain", source.saveRamFile.readText())
            owner.exitWithoutLatestProgress().await()
        }
    }

    @Test fun rejectedStateCaptureDuringLegacyImportRequiresFreshColdBoot() = runBlocking {
        val rejectState = java.util.concurrent.atomic.AtomicBoolean(false)
        fixture(engineFactory = {
            val native = LibretroPlayerEngine()
            object : PlayerEngine by native {
                override suspend fun captureMemorySnapshot(includeState: Boolean): CoreMemorySnapshot {
                    val captured = native.captureMemorySnapshot(includeState)
                    return if (includeState && rejectState.get()) captured.copy(state = null, stateRejected = true) else captured
                }
            }
        }) {
            val owner = open(legacy = true)
            assertTrue(owner.select().await() is ProgressLaunchDecision.RecoveryRequired)
            val bytes = requireNotNull(owner.engine.captureMemorySnapshot(true).state)
            val original = File(source.saveStatesDirectory, "old.state").apply { writeBytes(bytes) }
            rejectState.set(true)
            val error = runCatching { owner.importLegacy(original).await() }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("Close and reopen"))
            assertEquals(ApplicationProgressPhase.AWAITING_CHOICE, owner.state.value.phase)
            assertNull(owner.state.value.progress)
            assertArrayEquals(bytes, original.readBytes())
            assertNull(requireNotNull(store.checkpoint(owner.workspace.identity, "save/current")).stateCompatibility)
            owner.exitWithoutLatestProgress().await()
            rejectState.set(false)
            val fresh = open(legacy = true)
            val decision = fresh.select().await() as ProgressLaunchDecision.Ready
            assertFalse(decision.useState)
            fresh.finishExit().await()
        }
    }

    @Test fun controlledViewRemovalAndActivityRecreationPreserveTheLoadedCore() = runBlocking {
        fixture {
            val owner = playable()
            owner.checkpoint("pause").await()
            val expected = owner.engine.captureMemorySnapshot(true)
            val before = centerPixel(scenario)
            assertNotEquals("Fixture must have a visible nonblack frame", Color.BLACK, before)
            owner.prepareViewRemoval().await()
            scenario.recreate()
            scenario.onActivity { it.setContentView(owner.attach(it, it)) }
            delay(150)
            assertSame(owner, manager.active.value)
            val restored = owner.engine.captureMemorySnapshot(true)
            assertArrayEquals(expected.state, restored.state)
            expected.memory.forEach { (region, bytes) -> assertArrayEquals(bytes, restored.memory[region]) }
            owner.resume().await(); delay(150); owner.checkpoint("pause").await()
            assertEquals(before, centerPixel(scenario))
            assertFalse(expected.memory[PersistentMemory.SRAM]!!.contentEquals(owner.engine.captureMemorySnapshot(false).memory[PersistentMemory.SRAM]))
            owner.finishExit().await()
        }
    }

    @Test fun backgroundSavesThenForegroundResumesOnlyPreviouslyRunningGameplay() = runBlocking {
        fixture {
            val owner = playable()
            scenario.moveToState(Lifecycle.State.CREATED)
            owner.state.first { it.progress?.phase == ProgressSessionPhase.PAUSED && it.progress.lastCommitted?.checkpoint?.captureReason == "background" }
            val background = owner.engine.captureMemorySnapshot(false)
            delay(100)
            assertArrayEquals(background.memory[PersistentMemory.SRAM], owner.engine.captureMemorySnapshot(false).memory[PersistentMemory.SRAM])
            scenario.moveToState(Lifecycle.State.RESUMED)
            owner.state.first { it.progress?.phase == ProgressSessionPhase.RUNNING }
            owner.checkpoint("pause").await()
            scenario.moveToState(Lifecycle.State.CREATED)
            delay(100); scenario.moveToState(Lifecycle.State.RESUMED); delay(100)
            assertEquals(ProgressSessionPhase.PAUSED, owner.state.value.progress!!.phase)
            owner.finishExit().await()
        }
    }

    @Test fun pendingCommandsSurviveDetachedSurfaceAndActivityRecreation() = runBlocking {
        fixture {
            val owner = playable()
            owner.checkpoint("pause").await()
            val expected = owner.engine.captureMemorySnapshot(true)
            owner.prepareViewRemoval().await()
            val pending = owner.checkpoint("manual") // No GL thread until the view returns.
            delay(100); assertFalse(pending.isCompleted)
            scenario.recreate()
            scenario.onActivity { it.setContentView(owner.attach(it, it)) }
            pending.await()
            assertArrayEquals(expected.state, owner.engine.captureMemorySnapshot(true).state)
            owner.finishExit().await()
        }
    }

    @Test fun activityRecreationWhileRunningKeepsApplicationOwnership() = runBlocking {
        fixture {
            val owner = playable()
            owner.checkpoint("manual").await()
            scenario.recreate()
            scenario.onActivity { it.setContentView(owner.attach(it, it)) }
            assertSame(owner, manager.active.value)
            owner.checkpoint("pause").await()
            assertEquals(ApplicationProgressPhase.ACTIVE, owner.state.value.phase)
            assertTrue(store.history(owner.workspace.identity).entries.isNotEmpty())
            owner.finishExit().await()
        }
    }

    @Test fun failedLocalCaptureKeepsOwnerAndCanRetryBeforeExit() = runBlocking {
        fixture {
            val owner = playable()
            val captures = owner.workspace.stagingDirectory
            assertTrue(captures.delete()); captures.writeText("storage failure fixture")
            assertNotNull(runCatching { owner.checkpoint("manual").await() }.exceptionOrNull())
            owner.state.first { it.progress?.phase == ProgressSessionPhase.SAVE_FAILED }
            assertSame(owner, manager.active.value)
            assertNotNull(runCatching { owner.finishExit().await() }.exceptionOrNull())
            assertNotNull(runCatching { owner.prepareViewRemoval().await() }.exceptionOrNull())
            assertTrue(captures.delete()); assertTrue(captures.mkdir())
            owner.retrySave().await()
            assertNull(owner.state.value.error)
            assertEquals(ProgressSessionPhase.PAUSED, owner.state.value.progress!!.phase)
            owner.resume().await() // Rejected navigation must not mislabel the still-visible screen as background.
            owner.finishExit().await()
            assertNull(manager.active.value)
        }
    }

    @Test fun canceledExitWaiterCannotReleaseOwnershipBeforeNativeTeardown() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        fixture(engineFactory = {
            val native = LibretroPlayerEngine()
            object : PlayerEngine by native {
                override suspend fun close() { entered.complete(Unit); release.await(); native.close() }
            }
        }) {
            val owner = playable()
            val exit = owner.finishExit()
            try {
                entered.await(); exit.cancel()
                assertSame(owner, manager.active.value)
                assertNotNull(runCatching { manager.prepare(source, false, false).await() }.exceptionOrNull())
                assertNotNull(store.checkpoint(owner.workspace.identity, "save/current"))
                assertTrue(owner.workspace.directory.exists())
            } finally { release.complete(Unit) }
            manager.active.first { it == null }
            assertEquals(ApplicationProgressPhase.CLOSED, owner.state.value.phase)
            assertFalse(owner.workspace.directory.exists())
            NativePlayerOwnership.acquire().close()
        }
    }

    private suspend fun centerPixel(scenario: ActivityScenario<NativePlayerTestActivity>): Int {
        val answer = CompletableDeferred<Int>()
        scenario.onActivity { activity ->
            fun surface(view: android.view.View): SurfaceView? {
                if (view is SurfaceView) return view
                if (view is ViewGroup) for (index in 0 until view.childCount) surface(view.getChildAt(index))?.let { return it }
                return null
            }
            val target = requireNotNull(surface(activity.window.decorView))
            val bitmap = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(target, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) answer.complete(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
                else answer.completeExceptionally(IllegalStateException("PixelCopy failed: $result"))
                bitmap.recycle()
            }, Handler(Looper.getMainLooper()))
        }
        return answer.await()
    }
}
