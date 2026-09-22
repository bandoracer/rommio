package io.github.bandoracer.rommio.data.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.github.bandoracer.rommio.RommNativeApplication

class DownloadRomWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        return createForegroundInfo(
            romName = "Queued download",
            fileName = "Preparing the next file",
            progressPercent = 0,
            indeterminate = true,
        )
    }

    override suspend fun doWork(): Result {
        val container = (applicationContext as RommNativeApplication).container
        createNotificationChannel()
        container.ownedDownloads.recover()
        try {
            while (!isStopped) {
                val next = container.ownedDownloads.next() ?: break
                setForeground(createForegroundInfo(next.romName, next.fileName, 0, true))
                container.ownedDownloads.execute(next, id.toString(), stopped = { isStopped }) { progress ->
                    setProgress(Data.Builder().putInt(KEY_PROGRESS_PERCENT, progress.progressPercent)
                        .putLong(KEY_BYTES_DOWNLOADED, progress.bytesDownloaded).putLong(KEY_TOTAL_BYTES, progress.totalBytes).build())
                    updateNotification(progress.romName, progress.fileName, progress.progressPercent, progress.totalBytes <= 0)
                }
            }
            return if (container.ownedDownloads.hasPending()) Result.retry() else Result.success()
        } finally {
            dismissNotification()
        }
    }

    private fun createForegroundInfo(
        romName: String,
        fileName: String,
        progressPercent: Int,
        indeterminate: Boolean,
    ): ForegroundInfo {
        val notification = buildNotification(
            romName = romName,
            fileName = fileName,
            progressPercent = progressPercent,
            indeterminate = indeterminate,
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId(),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(notificationId(), notification)
        }
    }

    private fun buildNotification(
        romName: String,
        fileName: String,
        progressPercent: Int,
        indeterminate: Boolean,
    ) = NotificationCompat.Builder(applicationContext, DOWNLOAD_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("Downloading $romName")
        .setContentText(fileName)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setProgress(100, progressPercent, indeterminate)
        .build()

    private fun updateNotification(
        romName: String,
        fileName: String,
        progressPercent: Int,
        indeterminate: Boolean,
    ) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(applicationContext).notify(
            notificationId(),
            buildNotification(
                romName = romName,
                fileName = fileName,
                progressPercent = progressPercent,
                indeterminate = indeterminate,
            ),
        )
    }

    private fun dismissNotification() {
        NotificationManagerCompat.from(applicationContext).cancel(notificationId())
    }

    private fun notificationId(): Int = DOWNLOAD_NOTIFICATION_ID

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(DOWNLOAD_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                DOWNLOAD_CHANNEL_ID,
                "Rom downloads",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        private const val DOWNLOAD_CHANNEL_ID = "rommio.downloads"
        private const val DOWNLOAD_NOTIFICATION_ID = 4100
        const val QUEUE_WORK_NAME = "rom-download-queue"
        const val KEY_PROGRESS_PERCENT = "progress_percent"
        const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
        const val KEY_TOTAL_BYTES = "total_bytes"
    }
}
