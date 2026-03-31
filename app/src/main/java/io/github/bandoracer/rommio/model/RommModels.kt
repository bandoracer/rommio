package io.github.bandoracer.rommio.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.squareup.moshi.Json

data class UserDto(
    val id: Int,
    val username: String,
    val email: String,
    val role: String,
)

data class PlatformDto(
    val id: Int,
    val slug: String,
    val name: String,
    @Json(name = "fs_slug") val fsSlug: String,
    @Json(name = "url_logo") val urlLogo: String? = null,
    @Json(name = "rom_count") val romCount: Int = 0,
)

data class ItemsResponse<T>(
    val items: List<T>,
    val total: Int? = null,
    val page: Int? = null,
    @Json(name = "per_page") val perPage: Int? = null,
)

data class RomFileDto(
    val id: Int,
    @Json(name = "rom_id") val romId: Int,
    @Json(name = "file_name") val fileName: String,
    @Json(name = "file_extension") val fileExtension: String = "",
    @Json(name = "file_size_bytes") val fileSizeBytes: Long,
) {
    val effectiveFileExtension: String
        get() = fileExtension.ifBlank {
            fileName.substringAfterLast('.', "")
        }
}

data class RomSiblingDto(
    val id: Int,
    val name: String? = null,
)

data class RomDto(
    val id: Int,
    val name: String? = null,
    val summary: String? = null,
    @Json(name = "platform_id") val platformId: Int = 0,
    @Json(name = "platform_name") val platformName: String = "",
    @Json(name = "platform_slug") val platformSlug: String = "",
    @Json(name = "fs_name") val fsName: String = "",
    val files: List<RomFileDto> = emptyList(),
    val siblings: List<RomSiblingDto>? = null,
    @Json(name = "url_cover") val urlCover: String? = null,
) {
    val displayName: String
        get() = name ?: fsName
}

data class HeartbeatDto(
    @Json(name = "SYSTEM") val system: HeartbeatSystemDto,
)

data class HeartbeatSystemDto(
    @Json(name = "VERSION") val version: String,
    @Json(name = "SHOW_SETUP_WIZARD") val showSetupWizard: Boolean,
)

data class DeviceRegistrationRequest(
    val name: String,
    val platform: String = "android",
    val client: String = "romm-android-native",
    @Json(name = "client_version") val clientVersion: String = "0.1.0",
    val hostname: String? = android.os.Build.MODEL,
    @Json(name = "allow_existing") val allowExisting: Boolean = true,
    @Json(name = "allow_duplicate") val allowDuplicate: Boolean = false,
    @Json(name = "reset_syncs") val resetSyncs: Boolean = false,
)

data class DeviceRegistrationResponse(
    @Json(name = "device_id") val deviceId: String,
)

data class BaseAssetDto(
    val id: Int,
    @Json(name = "rom_id") val romId: Int,
    @Json(name = "file_name") val fileName: String,
    @Json(name = "file_size_bytes") val fileSizeBytes: Long,
    @Json(name = "download_path") val downloadPath: String,
    @Json(name = "updated_at") val updatedAt: String,
)

data class SaveDto(
    val id: Int,
    @Json(name = "rom_id") val romId: Int,
    @Json(name = "file_name") val fileName: String,
    @Json(name = "file_size_bytes") val fileSizeBytes: Long,
    @Json(name = "download_path") val downloadPath: String,
    @Json(name = "updated_at") val updatedAt: String,
    val emulator: String? = null,
)

data class StateDto(
    val id: Int,
    @Json(name = "rom_id") val romId: Int,
    @Json(name = "file_name") val fileName: String,
    @Json(name = "file_size_bytes") val fileSizeBytes: Long,
    @Json(name = "download_path") val downloadPath: String,
    @Json(name = "updated_at") val updatedAt: String,
    val emulator: String? = null,
)

@Entity(tableName = "downloaded_roms", primaryKeys = ["romId", "fileId"])
data class DownloadedRomEntity(
    val romId: Int,
    val fileId: Int,
    val platformSlug: String,
    val romName: String,
    val fileName: String,
    val localPath: String,
    val fileSizeBytes: Long,
    val downloadedAtEpochMs: Long,
)

@Entity(tableName = "save_states", primaryKeys = ["romId", "slot"])
data class SaveStateEntity(
    val romId: Int,
    val slot: Int,
    val label: String,
    val localPath: String,
    val screenshotPath: String? = null,
    val updatedAtEpochMs: Long,
)

data class LocalInstallStatus(
    val installedFiles: List<DownloadedRomEntity> = emptyList(),
    val activeFile: DownloadedRomEntity? = installedFiles.firstOrNull(),
)

data class SyncSummary(
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val notes: List<String> = emptyList(),
)
