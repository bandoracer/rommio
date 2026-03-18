package io.github.mattsays.rommnative.domain.sync

import io.github.mattsays.rommnative.data.auth.AuthManager
import io.github.mattsays.rommnative.data.local.SaveStateDao
import io.github.mattsays.rommnative.data.network.DownloadClient
import io.github.mattsays.rommnative.data.network.RommServiceFactory
import io.github.mattsays.rommnative.domain.player.RuntimeProfile
import io.github.mattsays.rommnative.domain.storage.LibraryStore
import io.github.mattsays.rommnative.model.AuthenticatedServerContext
import io.github.mattsays.rommnative.model.DownloadedRomEntity
import io.github.mattsays.rommnative.model.RomDto
import io.github.mattsays.rommnative.model.SaveStateEntity
import io.github.mattsays.rommnative.model.SyncSummary
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

class RommSyncBridge(
    private val authManager: AuthManager,
    private val serviceFactory: RommServiceFactory,
    private val downloadClient: DownloadClient,
    private val libraryStore: LibraryStore,
    private val saveStateDao: SaveStateDao,
) : SyncBridge {
    override suspend fun syncGame(
        installation: DownloadedRomEntity,
        rom: RomDto,
        runtimeProfile: RuntimeProfile,
    ): SyncSummary = withContext(Dispatchers.IO) {
        val authContext = ensureAuthenticatedContext()
        val profile = authContext.profile
        val deviceId = authContext.deviceId
        val service = serviceFactory.create(profile)
        val notes = mutableListOf<String>()
        var uploaded = 0
        var downloaded = 0

        val saveFile = libraryStore.saveRamFile(installation)
        val remoteSave = service.listSaves(rom.id, deviceId).firstOrNull { it.fileName == saveFile.name }

        if (saveFile.exists()) {
            val localNewer = remoteSave == null || saveFile.lastModified() >= remoteSave.updatedAt.toEpochMillis()
            if (localNewer) {
                service.uploadSave(
                    romId = rom.id,
                    emulator = runtimeProfile.runtimeId,
                    slot = null,
                    deviceId = deviceId,
                    overwrite = true,
                    saveFile = saveFile.toMultipart("saveFile"),
                )
                uploaded += 1
            }
        } else if (remoteSave != null) {
            downloadClient.downloadToFile(
                profileId = profile.id,
                absoluteUrl = buildSaveContentUrl(profile.baseUrl, remoteSave.id, deviceId),
                target = saveFile,
            )
            downloaded += 1
        } else {
            notes += "No SRAM data available to sync."
        }

        val stateDir = libraryStore.saveStatesDirectory(installation)
        val localStates = stateDir.listFiles().orEmpty().filter { it.isFile }
        val remoteStates = service.listStates(rom.id).associateBy { it.fileName }

        for (localFile in localStates) {
            val remote = remoteStates[localFile.name]
            val localNewer = remote == null || localFile.lastModified() >= remote.updatedAt.toEpochMillis()
            if (localNewer) {
                service.uploadState(
                    romId = rom.id,
                    emulator = runtimeProfile.runtimeId,
                    stateFile = localFile.toMultipart("stateFile"),
                )
                uploaded += 1
            }
        }

        remoteStates.values.forEach { remote ->
            val localFile = File(stateDir, remote.fileName)
            if (!localFile.exists() || remote.updatedAt.toEpochMillis() > localFile.lastModified()) {
                downloadClient.downloadToFile(
                    profileId = profile.id,
                    absoluteUrl = profile.baseUrl.removeSuffix("/") + remote.downloadPath,
                    target = localFile,
                )
                downloaded += 1
                saveStateDao.upsert(
                    SaveStateEntity(
                        romId = installation.romId,
                        slot = extractSlot(localFile.name),
                        label = "Slot ${extractSlot(localFile.name)}",
                        localPath = localFile.absolutePath,
                        updatedAtEpochMs = localFile.lastModified(),
                    ),
                )
            }
        }

        SyncSummary(uploaded = uploaded, downloaded = downloaded, notes = notes)
    }

    private suspend fun ensureAuthenticatedContext(): AuthenticatedServerContext {
        val profile = authManager.getActiveProfile()
            ?: error("Configure server access before syncing.")
        val deviceId = authManager.ensureDeviceId(profile.id)
        val refreshedProfile = authManager.getProfile(profile.id) ?: profile
        return AuthenticatedServerContext(
            profile = refreshedProfile,
            deviceId = deviceId,
        )
    }

    private fun File.toMultipart(partName: String): MultipartBody.Part {
        return MultipartBody.Part.createFormData(
            partName,
            name,
            asRequestBody("application/octet-stream".toMediaType()),
        )
    }

    private fun String.toEpochMillis(): Long {
        return runCatching { Instant.parse(this).toEpochMilli() }.getOrDefault(0L)
    }

    private fun buildSaveContentUrl(baseUrl: String, saveId: Int, deviceId: String?): String {
        val suffix = if (deviceId.isNullOrBlank()) "" else "&device_id=$deviceId"
        return "${baseUrl.removeSuffix("/")}/api/saves/$saveId/content?optimistic=false$suffix"
    }

    private fun extractSlot(fileName: String): Int {
        return Regex("slot(\\d+)").find(fileName)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }
}
