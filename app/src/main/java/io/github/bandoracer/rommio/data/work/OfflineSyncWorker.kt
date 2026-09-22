package io.github.bandoracer.rommio.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.bandoracer.rommio.RommNativeApplication

class OfflineSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as RommNativeApplication).container
        return if (runOfflineMaintenance(
            refresh = { container.repository.refreshActiveProfileCache(force = true) },
            drain = { container.repository.drainPendingRemoteActions() },
        )) Result.success() else Result.retry()
    }

    companion object {
        const val WORK_NAME = "offline-profile-refresh"
    }
}
