package io.github.bandoracer.rommio.data.work

import kotlinx.coroutines.CancellationException

/** Catalog availability must not prevent already queued work from being attempted. */
internal suspend fun runOfflineMaintenance(refresh: suspend () -> Unit, drain: suspend () -> Unit): Boolean {
    var succeeded = true
    for (operation in listOf(refresh, drain)) {
        try { operation() } catch (error: Exception) {
            if (error is CancellationException) throw error
            succeeded = false
        }
    }
    return succeeded
}
