package io.github.bandoracer.rommio.domain.player

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.bandoracer.rommio.data.local.AppDatabase
import io.github.bandoracer.rommio.domain.progress.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID

class NativeCheckpointCaptureTest {
    private fun uuid() = UUID.randomUUID().toString()
    private suspend fun rejected(block: suspend () -> Unit) {
        var failure: Exception? = null
        try { block() } catch (error: Exception) { failure = error }
        assertNotNull("Expected rejection", failure)
    }

    private class Fixture(val root: File, val store: ProgressStore, val identity: ProgressIdentity,
        val engine: LibretroPlayerEngine, val session: PlayerSession) {
        val qualification = MemoryOnlyPersistence("capture-test", session.coreIdentity!!.sha256,
            session.coreIdentity.abi, ProgressSaveCompatibility("fixture", "memory", "1", "capture-test", false),
            PersistentMemory.STANDARD, "synthetic-v1")
        fun writer(parents: Map<String, List<String>> = emptyMap(), contract: MemoryOnlyPersistence = qualification) =
            SessionCheckpointWriter(store, identity, contract, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                parents, File(root, "staging"), engine::captureMemorySnapshot, publish = true)
        suspend fun files() = requireNotNull(store.materialize(identity, "save/current", UUID.randomUUID().toString()))
    }

    private suspend fun withFixture(block: suspend Fixture.() -> Unit) = withSyntheticNativeCore { engine, session ->
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.filesDir, "checkpoint-capture-${uuid()}").apply { mkdirs() }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val store = ProgressStore(db, File(root, "store"))
        try {
            val binding = ProgressBinding("https://fixture.example", "test", 10,
                listOf(ProgressContentFile(11, 0, ProgressProtocol.sha256(session.romPath.readBytes()), null, null)))
            Fixture(root, store, store.resolveIdentity(binding), engine, session).block()
        } finally { store.close(); db.close(); root.deleteRecursively() }
    }

    @Test fun runningCoreCapturesStateSramRtcAndOptionsAtOneBarrier() = runBlocking {
        withFixture {
            val writer = writer()
            engine.setPaused(false)
            repeat(8) {
                delay(25)
                val result = writer.captureAndCommit("periodic")
                val files = files()
                val state = File(files, "resume.state").readBytes()
                assertArrayEquals(state.copyOfRange(0, 4), File(files, "save.rtc").readBytes())
                assertArrayEquals(state.copyOfRange(4, 8), File(files, "save.srm").readBytes())
                assertEquals("success", result.checkpoint.stateCompatibility!!.options["rommio_test_capture"])
                assertEquals(session.coreIdentity!!.sha256, result.checkpoint.stateCompatibility!!.coreSha256)
                assertEquals(result.checkpoint, store.checkpoint(identity, result.resumeReference!!))
            }
            engine.setPaused(true)
            assertFalse(session.saveRamFile.exists()) // New writer never mutates the legacy save path.
        }
    }

    @Test fun failedStateCommitsCompleteSaveDataAndRetainsOldResumeOnlyInHistory() = runBlocking {
        withFixture {
            val writer = writer()
            val first = writer.captureAndCommit("periodic")
            val old = File(files(), "resume.state").readBytes()
            engine.setPaused(false); delay(100); engine.setPaused(true)
            engine.updateVariables(mapOf("rommio_test_capture" to "reject"))
            val next = writer.captureAndCommit("exit")
            assertTrue(next.stateRejected)
            assertNull(next.resumeReference)
            assertTrue(next.checkpoint.saveDataComplete)
            assertEquals(setOf("sram", "rtc"), next.checkpoint.artifacts.map { it.role }.toSet())
            assertNull(store.checkpoint(identity, first.resumeReference!!))
            assertEquals(next.checkpoint, store.checkpoint(identity, "save/current"))
            val recovery = store.materializeCheckpoint(identity, first.checkpoint.checkpointId, uuid())
            assertArrayEquals(old, File(recovery, "resume.state").readBytes())
            assertFalse(old.copyOfRange(4, 8).contentEquals(File(files(), "save.srm").readBytes()))
            assertEquals(2, store.outbox(identity).size)
            engine.updateVariables(mapOf("rommio_test_capture" to "success"))
            assertNotNull(writer.captureAndCommit("periodic").resumeReference)
        }
    }

    @Test fun memoryReadFailureCannotPublishStateWithMissingOrEmptySaveData() = runBlocking {
        withFixture {
            val writer = writer()
            val first = writer.captureAndCommit("periodic")
            for (mode in listOf("missing", "oversize", "empty")) {
                engine.updateVariables(mapOf("rommio_test_memory" to mode))
                rejected { writer.captureAndCommit("manual") }
                assertEquals(listOf(first.operationId), store.heads(identity, "save/current"))
                assertEquals(1, store.outbox(identity).size)
            }
            engine.updateVariables(mapOf("rommio_test_memory" to "normal"))
            assertNotNull(writer.captureAndCommit("manual"))
        }
    }

    @Test fun interruptedCommitsRetryExactCaptureWithoutCreatingSiblingOrRecapturing() = runBlocking {
        withFixture {
            val writer = writer()
            var previous = writer.captureAndCommit("manual")
            for (boundary in ProgressCommitBoundary.entries) {
                rejected { writer.captureAndCommit("manual") { if (it == boundary) throw IOException("Interrupted $boundary") } }
                val afterFailure = store.heads(identity, "save/current")
                if (boundary != ProgressCommitBoundary.TRANSACTION_COMMITTED) assertEquals(listOf(previous.operationId), afterFailure)
                rejected { writer.captureAndCommit("manual") }
                // Retrying must not consult the core: it now refuses all persistence reads.
                engine.updateVariables(mapOf("rommio_test_memory" to "missing"))
                val retried = writer.retryCommit()
                if (boundary == ProgressCommitBoundary.TRANSACTION_COMMITTED) assertEquals(afterFailure, listOf(retried.operationId))
                assertEquals(listOf(retried.operationId), store.heads(identity, "save/current"))
                engine.updateVariables(mapOf("rommio_test_memory" to "normal"))
                previous = retried
            }
            assertEquals(1 + ProgressCommitBoundary.entries.size, store.outbox(identity).size)
            assertTrue(File(root, "staging").listFiles()!!.isEmpty())
        }
    }

    @Test fun unqualifiedCoreOrUndeclaredRtcCannotClaimCompletePersistence() = runBlocking {
        withFixture {
            rejected { writer(contract = qualification.copy(coreSha256 = "0".repeat(64))).captureAndCommit("periodic") }
            rejected { writer(contract = qualification.copy(regions = setOf(PersistentMemory.SRAM))).captureAndCommit("periodic") }
            assertTrue(store.heads(identity, "save/current").isEmpty())
            assertTrue(store.outbox(identity).isEmpty())
        }
    }

    @Test fun capturedAccountAndContentCannotBeWrittenIntoAnotherBinding() = runBlocking {
        withFixture {
            for (binding in listOf(identity.binding.copy(accountId = "other"),
                identity.binding.copy(files = identity.binding.files.map { it.copy(contentSha256 = "f".repeat(64)) }))) {
                val other = store.resolveIdentity(binding)
                val writer = SessionCheckpointWriter(store, other, qualification, uuid(), uuid(), emptyMap(),
                    File(root, "staging"), engine::captureMemorySnapshot, publish = true)
                rejected { writer.captureAndCommit("periodic") }
                assertTrue(store.outbox(other).isEmpty())
            }
            assertTrue(store.outbox(identity).isEmpty())
        }
    }

    @Test fun cancellationAfterCommitCanRetryWithoutLosingOrDuplicatingAcknowledgedBytes() = runBlocking {
        withFixture {
            val writer = writer()
            val task = launch {
                val owner = currentCoroutineContext().job
                writer.captureAndCommit("manual") {
                    if (it == ProgressCommitBoundary.TRANSACTION_COMMITTED) owner.cancel(CancellationException("Screen left"))
                }
            }
            task.join()
            assertTrue(task.isCancelled)
            val committed = store.heads(identity, "save/current").single()
            assertEquals(committed, writer.retryCommit().operationId)
            assertEquals(1, store.outbox(identity).size)
            assertNotNull(store.checkpoint(identity, "save/current"))
        }
    }

    @Test fun stagingFailureRetainsLastCheckpointAndAllowsFreshCaptureAfterRepair() = runBlocking {
        withFixture {
            val writer = writer()
            val first = writer.captureAndCommit("manual")
            val staging = File(root, "staging")
            assertTrue(staging.deleteRecursively())
            staging.writeText("directory unavailable")
            rejected { writer.captureAndCommit("manual") }
            assertEquals(listOf(first.operationId), store.heads(identity, "save/current"))
            assertEquals(1, store.outbox(identity).size)
            assertTrue(staging.delete())
            assertNotEquals(first.operationId, writer.captureAndCommit("manual").operationId)
        }
    }

    @Test fun unresolvedBaselineCannotBeSilentlyMergedByCapture() = runBlocking {
        withFixture {
            rejected { writer(mapOf("save/current" to listOf(uuid(), uuid()))).captureAndCommit("manual") }
            assertTrue(store.heads(identity, "save/current").isEmpty())
        }
    }

    @Test fun runningSessionDoesNotAdoptAnUnplayedIncomingHeadAsItsParent() = runBlocking {
        withFixture {
            val local = writer()
            val first = local.captureAndCommit("manual")
            val baseline = mapOf("save/current" to listOf(first.operationId), first.resumeReference!! to listOf(first.operationId))
            val remote = writer(baseline).captureAndCommit("manual")
            val next = local.captureAndCommit("manual")
            assertEquals(setOf(remote.operationId, next.operationId), store.heads(identity, "save/current").toSet())
            rejected { store.checkpoint(identity, "save/current") }
            assertNotNull(store.materializeCheckpoint(identity, remote.checkpoint.checkpointId, uuid()))
            assertNotNull(store.materializeCheckpoint(identity, next.checkpoint.checkpointId, uuid()))
        }
    }

    @Test fun unchangedAutomaticCaptureIsCoalescedButManualSlotsKeepExplicitRevisions() = runBlocking {
        withFixture {
            val writer = writer()
            val first = writer.captureAndCommit("periodic", includeState = false)
            val same = writer.captureAndCommit("background", includeState = false)
            assertEquals(first.operationId, same.operationId)
            assertTrue(same.unchanged)
            assertEquals(1, store.outbox(identity).size)
            val slot = uuid()
            val manual = writer.captureAndCommit("manual", slotId = slot)
            val overwrite = writer.captureAndCommit("manual", slotId = slot)
            assertNotEquals(manual.operationId, overwrite.operationId)
            assertEquals(listOf(overwrite.operationId), store.heads(identity, "slot/$slot"))
            assertEquals(3, store.outbox(identity).size)
        }
    }
}
