package io.github.bandoracer.rommio.domain.storage

import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class StorageAccessTest {
    @After fun reset() { StorageAccess.requireRecovery(false) }

    @Test fun finishingOneJournalCannotReleaseAnotherPendingRecovery() = runBlocking {
        try {
            StorageAccess.requireRecovery(true)
            StorageAccess.requireRecovery(true, "second-journal")
            StorageAccess.requireRecovery(false)
            try { StorageAccess.normal { fail("Second recovery was bypassed") }; fail("Recovery required") }
            catch (_: IllegalStateException) { }
            try { StorageAccess.exclusive { StorageAccess.checkRecovered() }; fail("New migration was allowed") }
            catch (_: IllegalStateException) { }
        } finally { StorageAccess.requireRecovery(false, "second-journal") }
        StorageAccess.normal { }
    }

    @Test fun writerDrainsReadersAndNewWorkWaitsUntilActivation() = runBlocking {
        withTimeout(5000) {
            val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
            val reader = launch { StorageAccess.normal { entered.complete(Unit); finish.await(); StorageAccess.normal { } } }
            entered.await()
            val writerEntered = CompletableDeferred<Unit>(); val writerDone = CompletableDeferred<Unit>()
            val writer = launch { StorageAccess.exclusive { writerEntered.complete(Unit); writerDone.await() } }
            yield(); assertTrue(StorageAccess.busy.value); assertFalse(writerEntered.isCompleted)
            val newReader = async { StorageAccess.normal { "after" } }
            yield(); assertFalse(newReader.isCompleted)
            finish.complete(Unit); reader.join(); writerEntered.await(); assertFalse(newReader.isCompleted)
            writerDone.complete(Unit); writer.join(); assertEquals("after", newReader.await())
        }
    }

    @Test fun retainedRefreshOutlivesWaiterWithoutBlockingNestedWork() = runBlocking {
        withTimeout(5000) {
            val finish = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
            val independent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val waiter = launch { StorageAccess.normal {
                val (context, lease) = StorageAccess.fork()
                independent.launch(context) { try { entered.complete(Unit); finish.await(); StorageAccess.normal { } } finally { lease.close() } }
                awaitCancellation()
            } }
            entered.await(); waiter.cancelAndJoin()
            var imported = false
            val writer = launch { StorageAccess.exclusive { imported = true } }
            yield(); assertFalse(imported)
            finish.complete(Unit); writer.join(); independent.cancel(); assertTrue(imported)
        }
    }

    @Test fun commandQueuedBeforeReplacementCannotMutateImportedLibrary() = runBlocking {
        withTimeout(5000) {
            val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
            val writer = launch { StorageAccess.exclusive {
                entered.complete(Unit); finish.await(); StorageAccess.didReplaceLibrary()
            } }
            entered.await()
            var staleMutation = false
            val stale = async { StorageAccess.normal { staleMutation = true } }
            yield(); finish.complete(Unit); writer.join()
            try { stale.await(); fail("Stale command ran after import") } catch (_: CancellationException) { }
            assertFalse(staleMutation)
            StorageAccess.normal { } // New commands can use the new library.
        }
    }

    @Test fun activeNativeSessionRejectsImportAndCloseIsIdempotent() = runBlocking {
        val session = StorageAccess.session()
        try { StorageAccess.exclusive { fail("Imported during gameplay") }; fail("Import should reject active session") }
        catch (_: IllegalStateException) { }
        session.close(); session.close()
        StorageAccess.exclusive { assertTrue(StorageAccess.busy.value) }
        assertFalse(StorageAccess.busy.value)
    }

    @Test fun canceledWriterReleasesWaitingReaders() = runBlocking {
        withTimeout(5000) {
            val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
            val reader = launch { StorageAccess.normal { entered.complete(Unit); finish.await() } }
            entered.await()
            val writer = launch { StorageAccess.exclusive { fail("Still has a reader") } }
            yield(); writer.cancelAndJoin()
            assertFalse(StorageAccess.busy.value)
            StorageAccess.normal { }
            finish.complete(Unit); reader.join()
        }
    }

    @Test fun failedRecoveryBlocksOrdinaryWorkButAllowsRecoveryOwner() = runBlocking {
        StorageAccess.requireRecovery(true)
        try { StorageAccess.normal { fail("Mixed storage became usable") }; fail("Recovery required") }
        catch (_: IllegalStateException) { }
        StorageAccess.exclusive { StorageAccess.normal { StorageAccess.requireRecovery(false) } }
        StorageAccess.normal { }
    }

    @Test fun waitingMaintenanceDoesNotGrantExclusiveAuthorityToDrainingReader() = runBlocking {
        withTimeout(5000) {
            val entered = CompletableDeferred<Unit>(); val inspect = CompletableDeferred<Unit>()
            val reader = launch { StorageAccess.normal {
                entered.complete(Unit); inspect.await()
                try { StorageAccess.checkExclusive(); fail("Reader borrowed maintenance authority") }
                catch (_: IllegalStateException) { }
            } }
            entered.await()
            val maintenance = launch { StorageAccess.exclusive {
                StorageAccess.checkExclusive()
                StorageAccess.normal { StorageAccess.checkExclusive() }
            } }
            yield(); assertTrue(StorageAccess.busy.value)
            inspect.complete(Unit); reader.join(); maintenance.join()
            try { StorageAccess.checkExclusive(); fail("Authority survived owner") } catch (_: IllegalStateException) { }
        }
    }
}
