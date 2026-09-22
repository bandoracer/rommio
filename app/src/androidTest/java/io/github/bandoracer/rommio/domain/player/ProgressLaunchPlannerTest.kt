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
import java.util.UUID

class ProgressLaunchPlannerTest {
    private class Fixture(val root: File, val store: ProgressStore, val identity: ProgressIdentity,
        val engine: LibretroPlayerEngine, val session: PlayerSession) {
        val device = UUID.randomUUID().toString()
        val qualification = MemoryOnlyPersistence("capture-test", session.coreIdentity!!.sha256, session.coreIdentity.abi,
            ProgressSaveCompatibility("fixture", "memory", "1", "capture-test", false), PersistentMemory.STANDARD,
            "synthetic-v1", MemoryRestoreOrder.STATE_THEN_MEMORY)
        val planner = ProgressLaunchPlanner(store)
        var coordinator: ProgressSessionCoordinator? = null
        fun writer(parents: Map<String, List<String>> = emptyMap()) = SessionCheckpointWriter(store, identity, qualification,
            device, UUID.randomUUID().toString(), parents, File(root, "staging"), engine::captureMemorySnapshot, true)
        suspend fun plan(request: ProgressLaunchRequest = ProgressLaunchRequest.Automatic, legacy: Boolean = false,
            actual: CoreMemorySnapshot? = null) = planner.plan(identity, qualification, actual ?: engine.captureMemorySnapshot(false), request, legacy)
        suspend fun start(ready: ProgressLaunchDecision.Ready, target: PlayerEngine = engine) =
            planner.start(ready, target, device, File(root, "session"), true).also { coordinator = it }
        fun corrupt(checkpoint: ProgressCheckpoint, role: String) {
            val artifact = checkpoint.artifacts.single { it.role == role }
            File(root, "store/${identity.namespaceId}/${identity.contentId}/blobs/${artifact.sha256}").writeText("broken")
        }
        suspend fun advance() { engine.setPaused(false); delay(100); engine.setPaused(true) }
        suspend fun unrelatedSlotTombstone() {
            val id = UUID.randomUUID().toString()
            val operation = ProgressOperation(2, id, identity.binding,
                listOf(ProgressRefChange("slot/${UUID.randomUUID()}", emptyList(), null, true)), emptyList(),
                device, UUID.randomUUID().toString(), 1)
            store.commit(identity, ProgressProtocol.encode(operation), emptyMap())
        }
    }
    private suspend fun fixture(block: suspend Fixture.() -> Unit) = withSyntheticNativeCore { engine, session ->
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.filesDir, "launch-plan-${UUID.randomUUID()}").apply { mkdirs() }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val store = ProgressStore(db, File(root, "store"))
        val fixture = Fixture(root, store, store.resolveIdentity(session.progressBinding!!), engine, session)
        try { fixture.block() }
        finally {
            withContext(NonCancellable) {
                fixture.coordinator?.let {
                    if (it.status.value.phase !in setOf(ProgressSessionPhase.CLOSED, ProgressSessionPhase.STOPPED)) it.exitWithoutLatestProgress().await()
                }
            }
            store.close(); db.close(); root.deleteRecursively()
        }
    }

    @Test fun freshLaunchNeverInfersLegacyMigrationOrIgnoresDetachedHistory() = runBlocking {
        fixture {
            val fresh = plan() as ProgressLaunchDecision.Ready
            assertNull(fresh.checkpointId); assertFalse(fresh.useState)
            assertTrue(fresh.message.contains("Cloud progress not checked"))
            assertTrue(plan(legacy = true) is ProgressLaunchDecision.RecoveryRequired)
            unrelatedSlotTombstone()
            assertTrue(plan() is ProgressLaunchDecision.RecoveryRequired)
            assertTrue(store.heads(identity, "save/current").isEmpty())
        }
    }

    @Test fun selectedResumeRestoresMatchingBundleAndKeepsFrozenCausalBase() = runBlocking {
        fixture {
            val first = writer().captureAndCommit("manual")
            val expected = engine.captureMemorySnapshot(false)
            advance()
            val ready = plan() as ProgressLaunchDecision.Ready
            assertEquals(first.checkpoint.checkpointId, ready.checkpointId); assertTrue(ready.useState)
            val coordinator = start(ready)
            assertEquals(ProgressSessionPhase.PAUSED, coordinator.status.value.phase)
            val restored = engine.captureMemorySnapshot(false)
            expected.memory.keys.forEach { assertArrayEquals(expected.memory[it], restored.memory[it]) }
            val remote = writer().captureAndCommit("manual") // Arrives after this session chose its baseline.
            val local = coordinator.checkpoint("manual").await()
            assertEquals(setOf(remote.operationId, local.operationId), store.heads(identity, "save/current").toSet())
        }
    }

    @Test fun newerSaveOnlyCheckpointCannotAutomaticallyLoadOlderResume() = runBlocking {
        fixture {
            val writer = writer()
            val old = writer.captureAndCommit("manual")
            advance()
            val latest = writer.captureAndCommit("periodic", includeState = false)
            val ready = plan() as ProgressLaunchDecision.Ready
            assertFalse(ready.useState)
            assertEquals(latest.checkpoint.checkpointId, ready.checkpointId)
            assertEquals(listOf(old.operationId), store.heads(identity, old.resumeReference!!))
            assertEquals(listOf(latest.operationId), ready.baselineParents["save/current"])
        }
    }

    @Test fun corruptResumeOffersVerifiedColdBootWhileCorruptSaveBlocksIt() = runBlocking {
        fixture {
            val first = writer().captureAndCommit("manual")
            corrupt(first.checkpoint, "state")
            assertTrue(plan() is ProgressLaunchDecision.OfferColdBoot)
            val cold = plan(ProgressLaunchRequest.ColdBootCurrent) as ProgressLaunchDecision.Ready
            assertFalse(cold.useState); assertEquals(first.checkpoint.checkpointId, cold.checkpointId)
            corrupt(first.checkpoint, "sram")
            assertTrue(plan(ProgressLaunchRequest.ColdBootCurrent) is ProgressLaunchDecision.RecoveryRequired)
            assertEquals(listOf(first.operationId), store.heads(identity, "save/current"))
        }
    }

    @Test fun optionsAndFirmwareMismatchOfferColdBootWithoutDeletingResume() = runBlocking {
        fixture {
            val first = writer().captureAndCommit("manual")
            val actual = engine.captureMemorySnapshot(false)
            val changed = actual.copy(effectiveOptions = actual.effectiveOptions + ("new-option" to "changed"),
                firmwareSha256 = mapOf("system.bin" to "a".repeat(64)))
            assertTrue(plan(actual = changed) is ProgressLaunchDecision.OfferColdBoot)
            assertFalse((plan(ProgressLaunchRequest.ColdBootCurrent, actual = changed) as ProgressLaunchDecision.Ready).useState)
            assertTrue(store.inspectCheckpoint(identity, first.checkpoint.checkpointId).isLocallyAvailable)
        }
    }

    @Test fun explicitBranchContinuationPreservesOtherBranchAndNeverMergesItsParents() = runBlocking {
        fixture {
            val first = writer().captureAndCommit("manual")
            advance()
            val second = writer().captureAndCommit("manual")
            assertTrue(plan() is ProgressLaunchDecision.ChooseBranch)
            val ready = plan(ProgressLaunchRequest.ContinueBranch(first.operationId, true)) as ProgressLaunchDecision.Ready
            assertEquals(listOf(first.operationId), ready.baselineParents["save/current"])
            val coordinator = start(ready)
            val next = coordinator.checkpoint("manual").await()
            assertEquals(setOf(second.operationId, next.operationId), store.heads(identity, "save/current").toSet())
        }
    }

    @Test fun lateCurrentChangeRejectsStaleLaunchButUnrelatedSlotDoesNot() = runBlocking {
        fixture {
            val writer = writer()
            writer.captureAndCommit("manual")
            val stale = plan() as ProgressLaunchDecision.Ready
            advance(); writer.captureAndCommit("manual")
            val before = engine.captureMemorySnapshot(true)
            assertNotNull(runCatching { start(stale) }.exceptionOrNull())
            assertArrayEquals(before.state, engine.captureMemorySnapshot(true).state)
            val fresh = plan() as ProgressLaunchDecision.Ready
            unrelatedSlotTombstone()
            assertEquals(ProgressSessionPhase.PAUSED, start(fresh).status.value.phase)
        }
    }

    @Test fun currentDeletionRemainsACausalBaselineAndDoesNotResurrectOldResume() = runBlocking {
        fixture {
            val first = writer().captureAndCommit("manual")
            val tombstone = ProgressOperation(2, UUID.randomUUID().toString(), identity.binding,
                listOf(ProgressRefChange("save/current", listOf(first.operationId), null, true)), emptyList(),
                device, UUID.randomUUID().toString(), 1)
            store.commit(identity, ProgressProtocol.encode(tombstone), emptyMap())
            val ready = plan(legacy = true) as ProgressLaunchDecision.Ready
            assertNull(ready.checkpointId); assertFalse(ready.useState)
            assertEquals(listOf(tombstone.operationId), ready.baselineParents["save/current"])
            val coordinator = start(ready)
            coordinator.checkpoint("manual").await()
            assertTrue(store.inspectCheckpoint(identity, first.checkpoint.checkpointId).isLocallyAvailable)
        }
    }

    @Test fun selectedResumeConflictRequiresChoiceEvenWhenCurrentSaveIsResolved() = runBlocking {
        fixture {
            val first = writer().captureAndCommit("manual")
            advance()
            val second = writer().captureAndCommit("manual")
            val resolvedSave = ProgressOperation(2, UUID.randomUUID().toString(), identity.binding,
                listOf(ProgressRefChange("save/current", listOf(first.operationId, second.operationId), first.checkpoint.checkpointId, false)),
                listOf(first.checkpoint), device, UUID.randomUUID().toString(), 1)
            store.commit(identity, ProgressProtocol.encode(resolvedSave), emptyMap())
            val decision = plan() as ProgressLaunchDecision.ChooseBranch
            assertEquals(1, decision.current.size); assertEquals(2, decision.resume.size)
            val cold = plan(ProgressLaunchRequest.ColdBootCurrent) as ProgressLaunchDecision.Ready
            assertFalse(cold.useState)
            assertEquals(first.checkpoint.checkpointId, cold.checkpointId)
        }
    }

    @Test fun canceledStartupWaiterCannotLeakAnAcceptedNativeSession() = runBlocking {
        fixture {
            val ready = plan() as ProgressLaunchDecision.Ready
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val delayed = object : PlayerEngine by engine {
                override suspend fun awaitReady() { entered.complete(Unit); release.await(); engine.awaitReady() }
            }
            val job = launch { start(ready, delayed) }
            entered.await(); job.cancel(); release.complete(Unit); job.join()
            assertTrue(job.isCancelled)
            NativePlayerOwnership.acquire().close()
            val lease = store.acquireSession(identity, null, emptyMap())
            store.releaseSession(lease)
            assertNotNull(runCatching { engine.captureMemorySnapshot(false) }.exceptionOrNull())
        }
    }

    @Test fun sessionReservationLastsUntilNativeTeardownCompletes() = runBlocking {
        fixture {
            val ready = plan() as ProgressLaunchDecision.Ready
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val delayed = object : PlayerEngine by engine {
                override suspend fun close() { entered.complete(Unit); release.await(); engine.close() }
            }
            val owner = start(ready, delayed)
            assertNotNull(runCatching { start(ready) }.exceptionOrNull())
            assertEquals(ProgressSessionPhase.PAUSED, owner.status.value.phase)
            val exit = owner.exitWithoutLatestProgress()
            try {
                entered.await()
                assertNotNull(runCatching { store.acquireSession(identity, null, emptyMap()) }.exceptionOrNull())
            } finally { release.complete(Unit); exit.await() }
            val lease = store.acquireSession(identity, null, emptyMap())
            store.releaseSession(lease)
        }
    }
}
