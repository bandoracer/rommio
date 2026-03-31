package io.github.bandoracer.rommio.data.cache

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import io.github.bandoracer.rommio.data.local.MediaCacheEntryDao
import io.github.bandoracer.rommio.data.local.MediaCacheEntryEntity
import io.github.bandoracer.rommio.model.MediaCacheCategory
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ThumbnailCacheStore(
    context: Context,
    private val mediaCacheEntryDao: MediaCacheEntryDao,
) {
    private val rootDirectory = File(context.cacheDir, "rommio-thumbnails").apply { mkdirs() }
    private val maxBytes = 512L * 1024L * 1024L

    suspend fun resolveCachedUri(profileId: String, sourceUrl: String): String? = withContext(Dispatchers.IO) {
        val entry = mediaCacheEntryDao.getBySource(profileId, sourceUrl) ?: return@withContext null
        val file = File(entry.localPath)
        if (!file.exists()) {
            mediaCacheEntryDao.deleteBySource(profileId, sourceUrl)
            return@withContext null
        }
        mediaCacheEntryDao.upsert(
            entry.copy(
                sizeBytes = file.length(),
                lastAccessEpochMs = System.currentTimeMillis(),
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
        Uri.fromFile(file).toString()
    }

    suspend fun cacheIfNeeded(
        profileId: String,
        sourceUrl: String,
        category: MediaCacheCategory,
        pinned: Boolean,
        writer: suspend (File) -> Unit,
    ): String? = withContext(Dispatchers.IO) {
        resolveCachedUri(profileId, sourceUrl)?.let { return@withContext it }
        val target = File(rootDirectory, buildFileName(profileId, sourceUrl))
        runCatching { writer(target) }.getOrElse {
            runCatching { target.delete() }
            return@withContext null
        }
        if (!target.exists() || target.length() <= 0L) {
            runCatching { target.delete() }
            return@withContext null
        }
        if (!isValidCachedImage(target, sourceUrl)) {
            runCatching { target.delete() }
            return@withContext null
        }
        val now = System.currentTimeMillis()
        mediaCacheEntryDao.upsert(
            MediaCacheEntryEntity(
                profileId = profileId,
                sourceUrl = sourceUrl,
                localPath = target.absolutePath,
                category = category.name,
                pinned = pinned,
                sizeBytes = target.length(),
                lastAccessEpochMs = now,
                updatedAtEpochMs = now,
            ),
        )
        enforceLruLimit()
        Uri.fromFile(target).toString()
    }

    suspend fun totalBytes(profileId: String? = null): Long = withContext(Dispatchers.IO) {
        if (profileId == null) mediaCacheEntryDao.totalBytes() else mediaCacheEntryDao.totalBytesByProfile(profileId)
    }

    suspend fun clearProfile(profileId: String) = withContext(Dispatchers.IO) {
        val entries = mediaCacheEntryDao.listForEviction().filter { it.profileId == profileId }
        entries.forEach { entry -> runCatching { File(entry.localPath).delete() } }
        mediaCacheEntryDao.deleteByProfile(profileId)
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        mediaCacheEntryDao.listForEviction().forEach { entry ->
            runCatching { File(entry.localPath).delete() }
        }
        runCatching { rootDirectory.deleteRecursively() }
        rootDirectory.mkdirs()
    }

    private suspend fun enforceLruLimit() {
        var total = mediaCacheEntryDao.totalBytes()
        if (total <= maxBytes) return
        mediaCacheEntryDao.listForEviction().forEach { entry ->
            if (total <= maxBytes) return
            val file = File(entry.localPath)
            val size = entry.sizeBytes.takeIf { it > 0L } ?: file.length()
            runCatching { file.delete() }
            mediaCacheEntryDao.deleteBySource(entry.profileId, entry.sourceUrl)
            total -= size
        }
    }

    private fun buildFileName(profileId: String, sourceUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$profileId::$sourceUrl".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val extension = sourceUrl.substringBefore('?').substringAfterLast('.', "").lowercase()
            .takeIf { it.length in 2..6 } ?: "img"
        return "$digest.$extension"
    }

    private fun isValidCachedImage(file: File, sourceUrl: String): Boolean {
        val extension = sourceUrl.substringBefore('?').substringAfterLast('.', "").lowercase()
        if (extension == "svg") {
            return runCatching {
                file.inputStream().buffered().use { stream ->
                    val header = ByteArray(2048)
                    val bytesRead = stream.read(header)
                    bytesRead > 0 && String(header, 0, bytesRead).contains("<svg", ignoreCase = true)
                }
            }.getOrDefault(false)
        }

        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth > 0 && options.outHeight > 0
    }
}
