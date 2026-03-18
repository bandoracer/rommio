package io.github.mattsays.rommnative.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.mattsays.rommnative.RommNativeApplication
import io.github.mattsays.rommnative.model.DownloadedRomEntity
import io.github.mattsays.rommnative.util.buildRomContentPath

class DownloadRomWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as RommNativeApplication).container
        val profile = container.authManager.getActiveProfile() ?: return Result.failure()
        val romId = inputData.getInt(KEY_ROM_ID, -1)
        val fileId = inputData.getInt(KEY_FILE_ID, -1)
        val romName = inputData.getString(KEY_ROM_NAME).orEmpty()
        val platformSlug = inputData.getString(KEY_PLATFORM_SLUG).orEmpty()
        val fileName = inputData.getString(KEY_FILE_NAME).orEmpty()
        val fileSize = inputData.getLong(KEY_FILE_SIZE, 0L)

        if (romId <= 0 || fileId <= 0 || fileName.isBlank() || platformSlug.isBlank()) {
            return Result.failure()
        }

        return runCatching {
            val target = container.libraryStore.romTarget(platformSlug, fileName)
            val url = profile.baseUrl.removeSuffix("/") + buildRomContentPath(romId, fileName)
            container.downloadClient.downloadToFile(profile.id, url, target)
            container.downloadedRomDao.upsert(
                DownloadedRomEntity(
                    romId = romId,
                    fileId = fileId,
                    platformSlug = platformSlug,
                    romName = romName,
                    fileName = fileName,
                    localPath = target.absolutePath,
                    fileSizeBytes = fileSize,
                    downloadedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
    }

    companion object {
        const val KEY_ROM_ID = "rom_id"
        const val KEY_FILE_ID = "file_id"
        const val KEY_ROM_NAME = "rom_name"
        const val KEY_PLATFORM_SLUG = "platform_slug"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_FILE_SIZE = "file_size"
    }
}
