package io.github.bandoracer.rommio.data.work

import android.content.Context
import androidx.work.*
import io.github.bandoracer.rommio.RommNativeApplication
import java.util.concurrent.TimeUnit

class ProgressSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result =
        if ((applicationContext as RommNativeApplication).container.progressSync.drain()) Result.success() else Result.retry()

    companion object {
        private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("progress-v3-sync", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<ProgressSyncWorker>().setConstraints(connected)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS).build())
        }
        fun start(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("progress-v3-periodic", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ProgressSyncWorker>(15, TimeUnit.MINUTES).setConstraints(connected).build())
            enqueue(context)
        }
    }
}
