package io.github.mattsays.rommnative.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.mattsays.rommnative.RommNativeApplication

class OfflineSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as RommNativeApplication).container
        return runCatching {
            container.repository.refreshActiveProfileCache(force = true)
            container.repository.drainPendingRemoteActions()
            Result.success()
        }.getOrElse {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "offline-profile-refresh"
    }
}
