package io.github.mattsays.rommnative.model

import androidx.room.Entity
import com.squareup.moshi.Json

enum class CollectionKind {
    REGULAR,
    SMART,
    VIRTUAL,
}

data class RommCollectionDto(
    val kind: CollectionKind,
    val id: String,
    val name: String,
    val description: String = "",
    val romIds: Set<Int> = emptySet(),
    val romCount: Int = 0,
    val pathCoverSmall: String? = null,
    val pathCoverLarge: String? = null,
    val pathCoversSmall: List<String> = emptyList(),
    val pathCoversLarge: List<String> = emptyList(),
    val isPublic: Boolean = false,
    val isFavorite: Boolean = false,
    val isVirtual: Boolean = false,
    val isSmart: Boolean = false,
    val ownerUsername: String? = null,
)

data class CollectionResponseDto(
    val id: Int,
    val name: String,
    val description: String = "",
    @Json(name = "rom_ids") val romIds: Set<Int> = emptySet(),
    @Json(name = "rom_count") val romCount: Int = 0,
    @Json(name = "path_cover_small") val pathCoverSmall: String? = null,
    @Json(name = "path_cover_large") val pathCoverLarge: String? = null,
    @Json(name = "path_covers_small") val pathCoversSmall: List<String> = emptyList(),
    @Json(name = "path_covers_large") val pathCoversLarge: List<String> = emptyList(),
    @Json(name = "is_public") val isPublic: Boolean = false,
    @Json(name = "is_favorite") val isFavorite: Boolean = false,
    @Json(name = "owner_username") val ownerUsername: String? = null,
)

data class SmartCollectionResponseDto(
    val id: Int,
    val name: String,
    val description: String = "",
    @Json(name = "rom_ids") val romIds: Set<Int> = emptySet(),
    @Json(name = "rom_count") val romCount: Int = 0,
    @Json(name = "path_cover_small") val pathCoverSmall: String? = null,
    @Json(name = "path_cover_large") val pathCoverLarge: String? = null,
    @Json(name = "path_covers_small") val pathCoversSmall: List<String> = emptyList(),
    @Json(name = "path_covers_large") val pathCoversLarge: List<String> = emptyList(),
    @Json(name = "is_public") val isPublic: Boolean = false,
    @Json(name = "owner_username") val ownerUsername: String? = null,
    @Json(name = "filter_summary") val filterSummary: String? = null,
)

data class VirtualCollectionResponseDto(
    val id: String,
    val type: String,
    val name: String,
    val description: String = "",
    @Json(name = "rom_ids") val romIds: Set<Int> = emptySet(),
    @Json(name = "rom_count") val romCount: Int = 0,
    @Json(name = "path_cover_small") val pathCoverSmall: String? = null,
    @Json(name = "path_cover_large") val pathCoverLarge: String? = null,
    @Json(name = "path_covers_small") val pathCoversSmall: List<String> = emptyList(),
    @Json(name = "path_covers_large") val pathCoversLarge: List<String> = emptyList(),
)

enum class DownloadStatus {
    QUEUED,
    RUNNING,
    FAILED,
    CANCELED,
    COMPLETED,
}

@Entity(tableName = "download_records", primaryKeys = ["romId", "fileId"])
data class DownloadRecordEntity(
    val romId: Int,
    val fileId: Int,
    val romName: String,
    val platformSlug: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val workId: String? = null,
    val status: String,
    val progressPercent: Int = 0,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = 0,
    val localPath: String? = null,
    val lastError: String? = null,
    val enqueuedAtEpochMs: Long,
    val startedAtEpochMs: Long? = null,
    val completedAtEpochMs: Long? = null,
    val updatedAtEpochMs: Long,
)

data class DownloadRecord(
    val romId: Int,
    val fileId: Int,
    val romName: String,
    val platformSlug: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val workId: String? = null,
    val status: DownloadStatus,
    val progressPercent: Int = 0,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = 0,
    val localPath: String? = null,
    val lastError: String? = null,
    val enqueuedAtEpochMs: Long,
    val startedAtEpochMs: Long? = null,
    val completedAtEpochMs: Long? = null,
    val updatedAtEpochMs: Long,
)

data class InstalledPlatformSummary(
    val platformSlug: String,
    val installedGameCount: Int,
    val installedFileCount: Int,
    val totalBytes: Long,
)

data class LibraryStorageSummary(
    val installedGameCount: Int = 0,
    val installedFileCount: Int = 0,
    val totalBytes: Long = 0,
)

fun DownloadRecordEntity.toModel(): DownloadRecord {
    return DownloadRecord(
        romId = romId,
        fileId = fileId,
        romName = romName,
        platformSlug = platformSlug,
        fileName = fileName,
        fileSizeBytes = fileSizeBytes,
        workId = workId,
        status = runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.QUEUED),
        progressPercent = progressPercent,
        bytesDownloaded = bytesDownloaded,
        totalBytes = totalBytes,
        localPath = localPath,
        lastError = lastError,
        enqueuedAtEpochMs = enqueuedAtEpochMs,
        startedAtEpochMs = startedAtEpochMs,
        completedAtEpochMs = completedAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
    )
}

fun CollectionResponseDto.toDomain(): RommCollectionDto {
    return RommCollectionDto(
        kind = CollectionKind.REGULAR,
        id = id.toString(),
        name = name,
        description = description,
        romIds = romIds,
        romCount = romCount,
        pathCoverSmall = pathCoverSmall,
        pathCoverLarge = pathCoverLarge,
        pathCoversSmall = pathCoversSmall,
        pathCoversLarge = pathCoversLarge,
        isPublic = isPublic,
        isFavorite = isFavorite,
        ownerUsername = ownerUsername,
    )
}

fun SmartCollectionResponseDto.toDomain(): RommCollectionDto {
    return RommCollectionDto(
        kind = CollectionKind.SMART,
        id = id.toString(),
        name = name,
        description = description.ifBlank { filterSummary.orEmpty() },
        romIds = romIds,
        romCount = romCount,
        pathCoverSmall = pathCoverSmall,
        pathCoverLarge = pathCoverLarge,
        pathCoversSmall = pathCoversSmall,
        pathCoversLarge = pathCoversLarge,
        isPublic = isPublic,
        isSmart = true,
        ownerUsername = ownerUsername,
    )
}

fun VirtualCollectionResponseDto.toDomain(): RommCollectionDto {
    return RommCollectionDto(
        kind = CollectionKind.VIRTUAL,
        id = id,
        name = name,
        description = description,
        romIds = romIds,
        romCount = romCount,
        pathCoverSmall = pathCoverSmall,
        pathCoverLarge = pathCoverLarge,
        pathCoversSmall = pathCoversSmall,
        pathCoversLarge = pathCoversLarge,
        isVirtual = true,
    )
}
