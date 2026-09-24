package io.github.bandoracer.rommio.data.playdata

import android.graphics.Bitmap
import android.os.Build
import io.github.bandoracer.rommio.domain.playdata.SaveKey
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Pure sizing rule for save-state thumbnails (shared with iOS through docs/play-data-local.md). */
object ThumbnailSizing {
    const val MAX_WIDTH = 320
    const val WEBP_QUALITY = 80

    /** At most [maxWidth] wide, aspect kept, never upscaled, never zero. */
    fun target(width: Int, height: Int, maxWidth: Int = MAX_WIDTH): Pair<Int, Int> {
        require(width > 0 && height > 0)
        if (width <= maxWidth) return width to height
        val scaledHeight = (height.toLong() * maxWidth / width).toInt().coerceAtLeast(1)
        return maxWidth to scaledHeight
    }
}

/**
 * Save-state thumbnails in app-private, non-backed-up storage at a deterministic path:
 * `<root>/<namespaceId>/<contentId>/<checkpointId>.webp`. Local only; never a checkpoint artifact,
 * never synced, and never given a theme art treatment.
 */
class SaveThumbnailStore(private val root: File) {
    fun file(key: SaveKey): File {
        require(ID.matches(key.namespaceId) && ID.matches(key.contentId) && ID.matches(key.checkpointId)) { "Unexpected save identity." }
        return File(File(File(root, key.namespaceId), key.contentId), "${key.checkpointId}.webp")
    }

    fun pathIfPresent(key: SaveKey): String? = runCatching { file(key).takeIf { it.isFile && it.length() > 0 }?.absolutePath }.getOrNull()

    /** Encodes an already-downscaled frame. Writes atomically; a failure leaves no partial file. */
    fun write(key: SaveKey, bitmap: Bitmap) {
        val target = file(key)
        val directory = requireNotNull(target.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create the thumbnail directory." }
        val staging = File(directory, ".staging-${UUID.randomUUID()}")
        try {
            FileOutputStream(staging).use { output ->
                val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
                    else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
                check(bitmap.compress(format, ThumbnailSizing.WEBP_QUALITY, output)) { "Thumbnail encoding failed." }
            }
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            staging.delete()
        }
    }

    /** A restore commits a new checkpoint of the moment it loaded; it shows the source's frame. */
    fun copy(from: SaveKey, to: SaveKey) {
        val source = file(from).takeIf { it.isFile } ?: return
        val target = file(to)
        val directory = requireNotNull(target.parentFile)
        check(directory.isDirectory || directory.mkdirs())
        val staging = File(directory, ".staging-${UUID.randomUUID()}")
        try {
            source.copyTo(staging, overwrite = true)
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            staging.delete()
        }
    }

    fun delete(key: SaveKey) { runCatching { file(key).delete() } }

    /**
     * Deletes thumbnails of checkpoints that are no longer kept on this device. Files younger
     * than [graceMs] are left alone so a frame written for a just-committed checkpoint is never
     * removed by a reader that looked at the store a moment earlier.
     */
    fun prune(namespaceId: String, contentId: String, keep: Set<String>, now: Long = System.currentTimeMillis(), graceMs: Long = PRUNE_GRACE_MS): Int {
        if (!ID.matches(namespaceId) || !ID.matches(contentId)) return 0
        val directory = File(File(root, namespaceId), contentId)
        var deleted = 0
        directory.listFiles().orEmpty().forEach { file ->
            val id = file.name.removeSuffix(".webp")
            val stale = file.name.startsWith(".staging-") || (file.name.endsWith(".webp") && id !in keep)
            if (file.isFile && stale && now - file.lastModified() >= graceMs && file.delete()) deleted++
        }
        return deleted
    }

    companion object {
        const val PRUNE_GRACE_MS = 10 * 60_000L
        private val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
