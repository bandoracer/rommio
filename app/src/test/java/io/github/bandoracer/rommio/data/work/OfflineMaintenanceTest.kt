package io.github.bandoracer.rommio.data.work

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OfflineMaintenanceTest {
    @Test fun failedCatalogStillAttemptsQueuedWorkAndRequestsRetry() = runBlocking {
        var drained = false
        assertFalse(runOfflineMaintenance({ error("Catalog unavailable") }, { drained = true }))
        assertTrue(drained)
        assertFalse(runOfflineMaintenance({}, { error("Queue unavailable") }))
        assertTrue(runOfflineMaintenance({}, {}))
    }
    @Test fun cancellationStopsMaintenanceWithoutStartingAnotherOperation() = runBlocking {
        var drained = false
        val started = CompletableDeferred<Unit>()
        val job = launch { runOfflineMaintenance({ started.complete(Unit); awaitCancellation() }, { drained = true }) }
        started.await(); job.cancelAndJoin(); assertFalse(drained)
    }
}
