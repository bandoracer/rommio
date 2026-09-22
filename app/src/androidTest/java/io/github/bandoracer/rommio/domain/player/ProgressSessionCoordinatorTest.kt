package io.github.bandoracer.rommio.domain.player

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.swordfish.libretrodroid.LibretroDroid
import io.github.bandoracer.rommio.data.local.AppDatabase
import io.github.bandoracer.rommio.domain.progress.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProgressSessionCoordinatorTest {
    private fun uuid() = UUID.randomUUID().toString()
    private suspend fun rejected(block: suspend () -> Unit): Exception {
        try { block() } catch (error: Exception) { return error }
        throw AssertionError("Expected rejection")
    }
    private class Fixture(val root: File, val store: ProgressStore, val identity: ProgressIdentity,
        val engine: LibretroPlayerEngine, val session: PlayerSession) {
        val qualification = MemoryOnlyPersistence("capture-test", session.coreIdentity!!.sha256, session.coreIdentity.abi,
            ProgressSaveCompatibility("fixture", "memory", "1", "capture-test", false), PersistentMemory.STANDARD,
            "synthetic-v1", MemoryRestoreOrder.STATE_THEN_MEMORY)
        val writer = SessionCheckpointWriter(store, identity, qualification, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            emptyMap(), File(root, "staging"), engine::captureMemorySnapshot, true)
        val loader = MemoryCheckpointLoader(store, identity, qualification)
        var coordinator: ProgressSessionCoordinator? = null
        fun coordinator(interval: Long = 30_000, fault: (ProgressCommitBoundary) -> Unit = {}) =
            ProgressSessionCoordinator(engine, writer, loader, fault, interval, interval * 4).also { coordinator = it }
        suspend fun advance() { engine.setPaused(false); delay(100); engine.setPaused(true) }
    }
    private suspend fun fixture(game: String = "game.fixture", block: suspend Fixture.() -> Unit) =
        withSyntheticNativeCore(gameName = game) { engine, session ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            val root = File(context.filesDir, "session-test-${uuid()}").apply { mkdirs() }
            val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            val store = ProgressStore(db, File(root, "store"))
            val fixture = Fixture(root, store, store.resolveIdentity(session.progressBinding!!), engine, session)
            try { fixture.block() }
            finally {
                withContext(NonCancellable) {
                    fixture.coordinator?.let { coordinator ->
                        if (coordinator.status.value.phase !in setOf(ProgressSessionPhase.CLOSED, ProgressSessionPhase.STOPPED)) {
                            coordinator.exitWithoutLatestProgress().await()
                        }
                    }
                }
                store.close(); db.close(); root.deleteRecursively()
            }
        }

    @Test fun oldStateRestoresItsOwnSramAndRtcAfterDurableSafetyCapture() = runBlocking {
        fixture {
            val first = writer.captureAndCommit("manual")
            val expected = engine.captureMemorySnapshot(true)
            advance()
            val coordinator = coordinator()
            coordinator.start().await()
            coordinator.checkpoint("manual").await()
            val restored = coordinator.restore(first.checkpoint.checkpointId, confirmGameRollback = true).await()
            assertEquals("restore", restored.checkpoint.captureReason)
            val actual = engine.captureMemorySnapshot(true)
            assertArrayEquals(expected.state, actual.state)
            PersistentMemory.STANDARD.forEach { assertArrayEquals(expected.memory[it], actual.memory[it]) }
            val history = store.history(identity).entries
            assertEquals(listOf("restore", "safety", "manual", "manual"), history.map { it.checkpoint.captureReason })
            assertEquals(listOf(restored.operationId), store.heads(identity, "save/current"))
            assertEquals(ProgressSessionPhase.PAUSED, coordinator.status.value.phase)
        }
    }

    @Test fun rejectedStateMayMutateCoreButSafetyRollbackRestoresExactProgress() = runBlocking {
        fixture("reject-once.fixture") {
            val first = writer.captureAndCommit("manual")
            advance()
            val before = engine.captureMemorySnapshot(true)
            val coordinator = coordinator(); coordinator.start().await()
            val error = rejected { coordinator.restore(first.checkpoint.checkpointId, confirmGameRollback = true).await() }
            assertTrue(error.message.orEmpty().contains("safety checkpoint has been restored"))
            val actual = engine.captureMemorySnapshot(true)
            assertArrayEquals(before.state, actual.state)
            PersistentMemory.STANDARD.forEach { assertArrayEquals(before.memory[it], actual.memory[it]) }
            assertEquals("safety", store.checkpoint(identity, "save/current")!!.captureReason)
            assertEquals(ProgressSessionPhase.PAUSED, coordinator.status.value.phase)
        }
    }

    @Test fun failedSafetyRollbackStopsAndReleasesCoreWhileRetainingCommittedRecovery() = runBlocking {
        fixture("reject-always.fixture") {
            val first = writer.captureAndCommit("manual")
            advance()
            val coordinator = coordinator(); coordinator.start().await()
            rejected { coordinator.restore(first.checkpoint.checkpointId, confirmGameRollback = true).await() }
            assertEquals(ProgressSessionPhase.STOPPED, coordinator.status.value.phase)
            NativePlayerOwnership.acquire().close()
            assertEquals("safety", store.checkpoint(identity, "save/current")!!.captureReason)
            rejected { coordinator.resume().await() }
        }
    }

    @Test fun incompatibleOptionsRejectBeforeMutationAndColdSaveLoaderIgnoresBrokenState() = runBlocking {
        fixture {
            val first = writer.captureAndCommit("manual")
            advance()
            engine.updateVariables(mapOf("rommio_test_capture" to "reject"))
            val before = engine.captureMemorySnapshot(false)
            val coordinator = coordinator(); coordinator.start().await()
            rejected { coordinator.restore(first.checkpoint.checkpointId, confirmGameRollback = true).await() }
            assertEquals(1, store.outbox(identity).size)
            assertArrayEquals(before.memory[PersistentMemory.SRAM], engine.captureMemorySnapshot(false).memory[PersistentMemory.SRAM])
            val state = first.checkpoint.artifacts.single { it.role == "state" }
            File(root, "store/${identity.namespaceId}/${identity.contentId}/blobs/${state.sha256}").writeText("damaged")
            val cold = loader.load(first.checkpoint.checkpointId, before, includeState = false)
            assertNull(cold.state)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), cold.memory[PersistentMemory.SRAM])
            rejected { engine.restoreMemorySnapshot(cold) } // Cannot hot-load save-only progress after gameplay.
        }
    }

    @Test fun coldBootLoadsSaveOnlyMemoryBeforeAnyGameplay() = runBlocking {
        fixture {
            val initial = engine.captureMemorySnapshot(false)
            val expected = initial.copy(memory = mapOf(PersistentMemory.SRAM to byteArrayOf(8, 7, 6, 5),
                PersistentMemory.RTC to byteArrayOf(9, 8, 7, 6)))
            assertTrue(engine.restoreMemorySnapshot(expected))
            val saved = writer.captureAndCommit("manual", includeState = false)
            assertTrue(engine.restoreMemorySnapshot(initial))
            val coordinator = coordinator()
            coordinator.start(saved.checkpoint.checkpointId, useState = false).await()
            val actual = engine.captureMemorySnapshot(false)
            PersistentMemory.STANDARD.forEach { assertArrayEquals(expected.memory[it], actual.memory[it]) }
            assertEquals(ProgressSessionPhase.PAUSED, coordinator.status.value.phase)
        }
    }

    @Test fun resetRequiresRestorableSafetyAndRecordsIntentionalNewRevision() = runBlocking {
        fixture {
            advance()
            val coordinator = coordinator(); coordinator.start().await()
            val before = engine.captureMemorySnapshot(true)
            engine.updateVariables(mapOf("rommio_test_capture" to "reject"))
            rejected { coordinator.reset().await() }
            engine.updateVariables(mapOf("rommio_test_capture" to "success"))
            assertArrayEquals(before.state, engine.captureMemorySnapshot(true).state)
            val reset = coordinator.reset().await()
            assertEquals("restore", reset.checkpoint.captureReason)
            assertArrayEquals(ByteArray(4), engine.captureMemorySnapshot(true).state!!.copyOfRange(0, 4))
            assertEquals(listOf("restore", "safety", "safety"), store.history(identity).entries.map { it.checkpoint.captureReason })
        }
    }

    @Test fun nativeMemoryPreflightRejectsBadRtcWithoutChangingSram() = runBlocking {
        fixture {
            val before = engine.captureMemorySnapshot(false)
            assertFalse(LibretroDroid.unserializePersistentMemory(byteArrayOf(9, 9, 9, 9), byteArrayOf(1)))
            // An old/unknown core has no completion hook: reject private-bank writes before mutating SRAM.
            assertFalse(LibretroDroid.unserializeMemoryRegions(intArrayOf(0, 1, 0x100),
                arrayOf(byteArrayOf(9, 9, 9, 9), before.memory.getValue(PersistentMemory.RTC), byteArrayOf())))
            val after = engine.captureMemorySnapshot(false)
            PersistentMemory.STANDARD.forEach { assertArrayEquals(before.memory[it], after.memory[it]) }
        }
    }

    @Test fun unqualifiedArtifactInventoryIsRejectedBeforeMaterializingFiles() = runBlocking {
        fixture {
            val first = writer.captureAndCommit("manual")
            val prior = first.checkpoint
            val checkpoint = prior.copy(checkpointId = uuid(), artifacts = prior.artifacts +
                prior.artifacts.first { it.role == "sram" }.copy(role = "saveFile", relativePath = "unexpected.bin"))
            val operation = ProgressOperation(2, uuid(), identity.binding,
                listOf(ProgressRefChange("save/current", listOf(first.operationId), checkpoint.checkpointId, false)),
                listOf(checkpoint), prior.deviceId, prior.sessionId, System.currentTimeMillis())
            store.commit(identity, ProgressProtocol.encode(operation), emptyMap())
            val working = File(root, "store/${identity.namespaceId}/${identity.contentId}/working")
            assertFalse(working.exists())
            rejected { loader.load(checkpoint.checkpointId, engine.captureMemorySnapshot(false), includeState = true) }
            assertFalse(working.exists())
        }
    }

    @Test fun canceledScreenWaiterCannotCancelAcceptedSlotOrDisposeBeforeQueuedExitCommits() = runBlocking {
        fixture {
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            var hold = true
            val coordinator = coordinator { boundary ->
                if (hold && boundary == ProgressCommitBoundary.ARTIFACTS_INSTALLED) {
                    hold = false; entered.complete(Unit)
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            coordinator.start().await()
            val slot = uuid()
            val accepted = coordinator.checkpoint("manual", slotId = slot)
            val waiter = launch { accepted.await() }
            try {
                entered.await(); waiter.cancelAndJoin()
                assertFalse(accepted.isCancelled)
                val exit = coordinator.finishExit()
                assertFalse(exit.isCompleted)
                assertThrows(IllegalStateException::class.java) { NativePlayerOwnership.acquire() }
                release.countDown()
                val saved = accepted.await(); exit.await()
                assertEquals(listOf(saved.operationId), store.heads(identity, "slot/$slot"))
                assertEquals(ProgressSessionPhase.CLOSED, coordinator.status.value.phase)
                NativePlayerOwnership.acquire().close()
            } finally { release.countDown() }
        }
    }

    @Test fun failedExitRemainsPausedAndRetryFinishesCommitBeforeDisposal() = runBlocking {
        fixture {
            var fail = true
            val coordinator = coordinator { if (fail && it == ProgressCommitBoundary.HEADS_UPDATED) throw IOException("Disk failure") }
            coordinator.start().await(); coordinator.resume().await(); delay(50)
            rejected { coordinator.finishExit().await() }
            assertEquals(ProgressSessionPhase.SAVE_FAILED, coordinator.status.value.phase)
            assertTrue(store.outbox(identity).isEmpty())
            assertThrows(IllegalStateException::class.java) { NativePlayerOwnership.acquire() }
            val paused = engine.captureMemorySnapshot(true)
            delay(50); assertArrayEquals(paused.state, engine.captureMemorySnapshot(true).state)
            rejected { coordinator.resume().await() }
            fail = false
            coordinator.retrySave().await()
            assertEquals(1, store.outbox(identity).size)
            assertEquals(ProgressSessionPhase.CLOSED, coordinator.status.value.phase)
            NativePlayerOwnership.acquire().close()
        }
    }

    @Test fun manualCaptureRetryBeforeStagingPreservesItsSlotRequest() = runBlocking {
        fixture {
            val coordinator = coordinator(); coordinator.start().await()
            engine.updateVariables(mapOf("rommio_test_memory" to "missing"))
            val slot = uuid()
            rejected { coordinator.checkpoint("manual", slotId = slot).await() }
            engine.updateVariables(mapOf("rommio_test_memory" to "normal"))
            val result = coordinator.retrySave().await()
            assertEquals(listOf(result.operationId), store.heads(identity, "slot/$slot"))
            assertEquals(ProgressSessionPhase.PAUSED, coordinator.status.value.phase)
        }
    }

    @Test fun restoredWorkingStateCannotResumeUntilItsExplicitRevisionCommits() = runBlocking {
        fixture {
            val first = writer.captureAndCommit("manual")
            val original = engine.captureMemorySnapshot(true)
            advance()
            var commitCount = 0
            val coordinator = coordinator {
                if (it == ProgressCommitBoundary.ARTIFACTS_INSTALLED && ++commitCount == 2) throw IOException("Restore commit interrupted")
            }
            coordinator.start().await()
            rejected { coordinator.restore(first.checkpoint.checkpointId, confirmGameRollback = true).await() }
            assertEquals(ProgressSessionPhase.SAVE_FAILED, coordinator.status.value.phase)
            assertArrayEquals(original.state, engine.captureMemorySnapshot(true).state)
            assertEquals("safety", store.checkpoint(identity, "save/current")!!.captureReason)
            rejected { coordinator.resume().await() }
            val saved = coordinator.retrySave().await()
            assertEquals("restore", saved.checkpoint.captureReason)
            assertEquals(listOf(saved.operationId), store.heads(identity, "save/current"))
            coordinator.resume().await()
        }
    }

    @Test fun rejectedInitialRestoreStopsBeforeGameplayAndPreservesSourceCheckpoint() = runBlocking {
        fixture("reject-always.fixture") {
            val first = writer.captureAndCommit("manual")
            val coordinator = coordinator()
            rejected { coordinator.start(first.checkpoint.checkpointId).await() }
            assertEquals(ProgressSessionPhase.STOPPED, coordinator.status.value.phase)
            assertEquals(first.checkpoint, store.checkpoint(identity, "save/current"))
            assertEquals(1, store.outbox(identity).size)
            NativePlayerOwnership.acquire().close()
        }
    }

    @Test fun periodicWorkCapturesWhileRunningAndStopsWhenBackgrounded() = runBlocking {
        fixture {
            val coordinator = coordinator(interval = 100)
            coordinator.start().await(); coordinator.resume().await()
            withTimeout(3000) {
                while (store.history(identity).entries.none { it.checkpoint.stateCompatibility != null }) delay(50)
            }
            coordinator.checkpoint("background").await()
            val count = store.outbox(identity).size
            delay(300)
            assertEquals(count, store.outbox(identity).size)
            assertEquals(ProgressSessionPhase.PAUSED, coordinator.status.value.phase)
            assertTrue(store.history(identity).entries.any { it.checkpoint.stateCompatibility == null })
        }
    }
}
