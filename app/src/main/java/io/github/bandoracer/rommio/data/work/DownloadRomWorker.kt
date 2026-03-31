package io.github.bandoracer.rommio.data.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.bandoracer.rommio.AppContainer
import io.github.bandoracer.rommio.RommNativeApplication
import io.github.bandoracer.rommio.data.repository.nextQueuedDownload
import io.github.bandoracer.rommio.model.DownloadRecordEntity
import io.github.bandoracer.rommio.model.DownloadStatus
import io.github.bandoracer.rommio.model.DownloadedRomEntity
import io.github.bandoracer.rommio.util.buildRomContentPath
import java.util.concurrent.CancellationException

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
        requeueInterruptedDownloads(container)

        while (!isStopped) {
            val next = nextQueuedDownload(container.downloadRecordDao.listAll()) ?: break
            processDownload(container, next)
            if (isStopped) {
                scheduleQueueIfNeeded(container)
                break
            }
        }

        dismissNotification()
        return Result.success()
    }

    private suspend fun processDownload(
        container: AppContainer,
        record: DownloadRecordEntity,
    ) {
        val profile = container.authManager.getActiveProfile() ?: error("Active profile missing.")
        val now = System.currentTimeMillis()
        val installed = container.downloadedRomDao.getByIds(record.romId, record.fileId)
        val localPath = installed?.localPath ?: record.localPath

        updateRecord(
            container = container,
            record = record.copy(
                workId = id.toString(),
                status = DownloadStatus.RUNNING.name,
                progressPercent = 0,
                bytesDownloaded = 0,
                totalBytes = record.fileSizeBytes,
                localPath = localPath,
                lastError = null,
                startedAtEpochMs = now,
                completedAtEpochMs = null,
                updatedAtEpochMs = now,
            ),
        )
        setForeground(
            createForegroundInfo(
                romName = record.romName,
                fileName = record.fileName,
                progressPercent = 0,
                indeterminate = true,
            ),
        )

        var latestProgressPercent = 0
        var latestBytesDownloaded = 0L
        var latestTotalBytes = record.fileSizeBytes

        runCatching {
            val target = container.libraryStore.romTarget(record.platformSlug, record.fileName)
            val url = profile.baseUrl.removeSuffix("/") + buildRomContentPath(record.romId, record.fileName)
            var lastProgress = -1
            container.downloadClient.downloadToFile(
                profileId = profile.id,
                absoluteUrl = url,
                target = target,
            ) { bytesDownloaded, totalBytes ->
                if (isStopped) throw CancellationException("Download cancelled.")
                val progressPercent = if (totalBytes > 0L) {
                    ((bytesDownloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                } else {
                    0
                }
                if (progressPercent != lastProgress) {
                    lastProgress = progressPercent
                    latestProgressPercent = progressPercent
                    latestBytesDownloaded = bytesDownloaded
                    latestTotalBytes = if (totalBytes > 0L) totalBytes else record.fileSizeBytes
                    setProgress(
                        Data.Builder()
                            .putInt(KEY_PROGRESS_PERCENT, progressPercent)
                            .putLong(KEY_BYTES_DOWNLOADED, bytesDownloaded)
                            .putLong(KEY_TOTAL_BYTES, totalBytes)
                            .build(),
                    )
                    updateRecord(
                        container = container,
                        record = record.copy(
                            workId = id.toString(),
                            status = DownloadStatus.RUNNING.name,
                            progressPercent = progressPercent,
                            bytesDownloaded = bytesDownloaded,
                            totalBytes = if (totalBytes > 0L) totalBytes else record.fileSizeBytes,
                            localPath = localPath,
                            lastError = null,
                            startedAtEpochMs = now,
                            completedAtEpochMs = null,
                            updatedAtEpochMs = System.currentTimeMillis(),
                        ),
                    )
                    updateNotification(
                        romName = record.romName,
                        fileName = record.fileName,
                        progressPercent = progressPercent,
                        indeterminate = totalBytes <= 0L,
                    )
                }
            }
            if (isStopped) throw CancellationException("Download cancelled.")

            container.downloadedRomDao.upsert(
                DownloadedRomEntity(
                    romId = record.romId,
                    fileId = record.fileId,
                    platformSlug = record.platformSlug,
                    romName = record.romName,
                    fileName = record.fileName,
                    localPath = target.absolutePath,
                    fileSizeBytes = record.fileSizeBytes,
                    downloadedAtEpochMs = System.currentTimeMillis(),
                ),
            )
            updateRecord(
                container = container,
                record = record.copy(
                    workId = null,
                    status = DownloadStatus.COMPLETED.name,
                    progressPercent = 100,
                    bytesDownloaded = target.length(),
                    totalBytes = target.length().coerceAtLeast(record.fileSizeBytes),
                    localPath = target.absolutePath,
                    lastError = null,
                    startedAtEpochMs = now,
                    completedAtEpochMs = System.currentTimeMillis(),
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }.onFailure { error ->
            val status = if (error is CancellationException || isStopped) {
                DownloadStatus.CANCELED
            } else {
                DownloadStatus.FAILED
            }
            val latestRecord = container.downloadRecordDao.getByIds(record.romId, record.fileId)
            if (latestRecord?.workId != id.toString() || latestRecord.status != DownloadStatus.RUNNING.name) {
                return
            }
            updateRecord(
                container = container,
                record = record.copy(
                    workId = null,
                    status = status.name,
                    progressPercent = latestProgressPercent,
                    bytesDownloaded = latestBytesDownloaded,
                    totalBytes = latestTotalBytes,
                    localPath = localPath,
                    lastError = if (status == DownloadStatus.FAILED) {
                        error.message ?: "Download failed."
                    } else {
                        null
                    },
                    startedAtEpochMs = now,
                    completedAtEpochMs = null,
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    private suspend fun requeueInterruptedDownloads(container: AppContainer) {
        val now = System.currentTimeMillis()
        container.downloadRecordDao.listAll()
            .filter { it.status == DownloadStatus.RUNNING.name }
            .forEach { record ->
                updateRecord(
                    container = container,
                    record = record.copy(
                        workId = null,
                        status = DownloadStatus.QUEUED.name,
                        progressPercent = 0,
                        bytesDownloaded = 0,
                        totalBytes = record.fileSizeBytes,
                        lastError = null,
                        startedAtEpochMs = null,
                        completedAtEpochMs = null,
                        updatedAtEpochMs = now,
                    ),
                )
            }
    }

    private suspend fun scheduleQueueIfNeeded(container: AppContainer) {
        val hasQueued = container.downloadRecordDao.listAll().any { it.status == DownloadStatus.QUEUED.name }
        if (!hasQueued) return
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            QUEUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<DownloadRomWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build(),
        )
    }

    private suspend fun updateRecord(
        container: AppContainer,
        record: DownloadRecordEntity,
    ) {
        container.downloadRecordDao.upsert(record)
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
