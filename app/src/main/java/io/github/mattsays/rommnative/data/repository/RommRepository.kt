package io.github.mattsays.rommnative.data.repository

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.mattsays.rommnative.data.auth.AuthManager
import io.github.mattsays.rommnative.data.local.DownloadedRomDao
import io.github.mattsays.rommnative.data.local.SaveStateDao
import io.github.mattsays.rommnative.data.network.RommServiceFactory
import io.github.mattsays.rommnative.data.work.DownloadRomWorker
import io.github.mattsays.rommnative.domain.player.CoreInstaller
import io.github.mattsays.rommnative.domain.player.CoreResolver
import io.github.mattsays.rommnative.domain.player.CoreResolution
import io.github.mattsays.rommnative.domain.player.PlayerSession
import io.github.mattsays.rommnative.domain.player.RuntimeProfile
import io.github.mattsays.rommnative.domain.storage.LibraryStore
import io.github.mattsays.rommnative.domain.sync.SyncBridge
import io.github.mattsays.rommnative.model.AuthDiscoveryResult
import io.github.mattsays.rommnative.model.AuthStatus
import io.github.mattsays.rommnative.model.CloudflareServiceCredentials
import io.github.mattsays.rommnative.model.DirectLoginCredentials
import io.github.mattsays.rommnative.model.DownloadedRomEntity
import io.github.mattsays.rommnative.model.EdgeAuthMode
import io.github.mattsays.rommnative.model.InteractiveSessionConfig
import io.github.mattsays.rommnative.model.InteractiveSessionProvider
import io.github.mattsays.rommnative.model.OriginAuthMode
import io.github.mattsays.rommnative.model.PlatformDto
import io.github.mattsays.rommnative.model.RomDto
import io.github.mattsays.rommnative.model.RomFileDto
import io.github.mattsays.rommnative.model.SaveStateEntity
import io.github.mattsays.rommnative.model.ServerAccessResult
import io.github.mattsays.rommnative.model.ServerAccessStatus
import io.github.mattsays.rommnative.model.ServerProfile
import io.github.mattsays.rommnative.model.SyncSummary
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class RommRepository(
    private val context: Context,
    private val authManager: AuthManager,
    private val serviceFactory: RommServiceFactory,
    private val downloadedRomDao: DownloadedRomDao,
    private val saveStateDao: SaveStateDao,
    private val libraryStore: LibraryStore,
    private val coreResolver: CoreResolver,
    private val coreInstaller: CoreInstaller,
    private val syncBridge: SyncBridge,
) {
    fun activeProfileFlow(): Flow<ServerProfile?> = authManager.activeProfileFlow()

    suspend fun initializeAuth() {
        authManager.initializeForAppLaunch()
    }

    suspend fun currentProfile(): ServerProfile? = authManager.getActiveProfile()

    suspend fun discoverServer(baseUrl: String): AuthDiscoveryResult {
        return authManager.discoverServer(baseUrl)
    }

    suspend fun configureServerProfile(
        baseUrl: String,
        label: String? = null,
        edgeAuthMode: EdgeAuthMode? = null,
        originAuthMode: OriginAuthMode? = null,
        discoveryResult: AuthDiscoveryResult? = null,
        makeActive: Boolean = true,
    ): ServerProfile {
        return authManager.configureServerProfile(
            baseUrl = baseUrl,
            label = label,
            edgeAuthMode = edgeAuthMode,
            originAuthMode = originAuthMode,
            discoveryResult = discoveryResult,
            makeActive = makeActive,
        )
    }

    suspend fun setCloudflareServiceCredentials(profileId: String, credentials: CloudflareServiceCredentials) {
        authManager.setCloudflareServiceCredentials(profileId, credentials)
    }

    fun hasStoredCloudflareServiceCredentials(profileId: String): Boolean {
        return authManager.hasCloudflareServiceCredentials(profileId)
    }

    suspend fun beginEdgeAccess(profileId: String) {
        authManager.beginEdgeAccess(profileId)
    }

    suspend fun completeEdgeAccessAttempt(profileId: String) = authManager.completeEdgeAccessAttempt(profileId)

    suspend fun getInteractiveSessionConfig(
        profileId: String,
        provider: InteractiveSessionProvider,
    ): InteractiveSessionConfig {
        return authManager.getInteractiveSessionConfig(profileId, provider)
    }

    suspend fun completeInteractiveLogin(
        profileId: String,
        provider: InteractiveSessionProvider,
    ): AuthStatus {
        return authManager.completeInteractiveLogin(profileId, provider)
    }

    suspend fun testServerAccess(profileId: String? = null): ServerAccessResult {
        return authManager.testServerAccess(profileId)
    }

    suspend fun loginWithDirectCredentials(profileId: String, credentials: DirectLoginCredentials) {
        authManager.loginWithDirectCredentials(profileId, credentials)
    }

    suspend fun validateProfile(profileId: String? = null): AuthStatus {
        return authManager.validateProfile(profileId)
    }

    suspend fun logout(clearServerAccess: Boolean = false) {
        authManager.logout(clearServerAccess = clearServerAccess)
    }

    suspend fun clearServerAccess(profileId: String) {
        authManager.clearServerAccess(profileId)
    }

    suspend fun getPlatforms(): List<PlatformDto> {
        return createService().getPlatforms()
    }

    suspend fun getRecentlyAdded(): List<RomDto> {
        return createService().getRecentlyAdded().items
    }

    suspend fun getRomsByPlatform(platformId: Int): List<RomDto> {
        return createService()
            .getRomsByPlatform(platformIds = platformId, legacyPlatformId = platformId)
            .items
    }

    suspend fun getRomById(romId: Int): RomDto {
        val service = createService()
        val rom = service.getRomById(romId)
        val siblingFiles = rom.siblings.orEmpty().mapNotNull { sibling ->
            runCatching { service.getRomById(sibling.id).files.firstOrNull() }.getOrNull()
        }
        return rom.copy(files = rom.files + siblingFiles)
    }

    fun observeInstalledFiles(romId: Int): Flow<List<DownloadedRomEntity>> {
        return downloadedRomDao.observeByRomId(romId)
    }

    fun observeSaveStates(romId: Int): Flow<List<SaveStateEntity>> {
        return saveStateDao.observeByRomId(romId)
    }

    fun resolveCoreSupport(rom: RomDto, file: RomFileDto?): CoreResolution {
        return coreResolver.resolve(rom.platformSlug, file?.effectiveFileExtension)
    }

    suspend fun installRecommendedCore(rom: RomDto, file: RomFileDto?): CoreResolution {
        val resolution = resolveCoreSupport(rom, file)
        val profile = recommendedRuntimeProfile(resolution)
        coreInstaller.installCore(profile)
        return resolveCoreSupport(rom, file)
    }

    fun enqueueDownload(rom: RomDto, file: RomFileDto) {
        val request = OneTimeWorkRequestBuilder<DownloadRomWorker>()
            .setInputData(
                Data.Builder()
                    .putInt(DownloadRomWorker.KEY_ROM_ID, rom.id)
                    .putInt(DownloadRomWorker.KEY_FILE_ID, file.id)
                    .putString(DownloadRomWorker.KEY_ROM_NAME, rom.displayName)
                    .putString(DownloadRomWorker.KEY_PLATFORM_SLUG, rom.platformSlug)
                    .putString(DownloadRomWorker.KEY_FILE_NAME, file.fileName)
                    .putLong(DownloadRomWorker.KEY_FILE_SIZE, file.fileSizeBytes)
                    .build(),
            )
            .addTag(downloadTag(rom.id, file.id))
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            downloadTag(rom.id, file.id),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    suspend fun buildPlayerSession(installation: DownloadedRomEntity, rom: RomDto): PlayerSession {
        val resolution = resolveCoreSupport(
            rom = rom,
            file = rom.files.firstOrNull { it.id == installation.fileId } ?: rom.files.firstOrNull(),
        )
        val profile = resolution.runtimeProfile ?: error(resolution.message ?: "Unsupported platform.")
        val coreLibrary = resolution.coreLibrary ?: error(resolution.message ?: "Missing core.")
        val saveRamFile = libraryStore.saveRamFile(installation)

        return PlayerSession(
            romId = installation.romId,
            romTitle = installation.romName,
            romPath = File(installation.localPath),
            coreLibrary = coreLibrary,
            runtimeProfile = profile,
            systemDirectory = libraryStore.systemDirectory(),
            savesDirectory = saveRamFile.parentFile ?: libraryStore.systemDirectory(),
            saveRamFile = saveRamFile,
            saveStatesDirectory = libraryStore.saveStatesDirectory(installation),
            variables = profile.defaultVariables,
            initialSaveRam = saveRamFile.takeIf { it.exists() }?.readBytes(),
        )
    }

    suspend fun installedFileOrNull(romId: Int, fileId: Int): DownloadedRomEntity? {
        return downloadedRomDao.getByIds(romId, fileId)
    }

    suspend fun latestInstalledFile(romId: Int): DownloadedRomEntity? {
        return downloadedRomDao.observeByRomId(romId).first().firstOrNull()
    }

    suspend fun recordSaveState(installation: DownloadedRomEntity, slot: Int, file: File) {
        saveStateDao.upsert(
            SaveStateEntity(
                romId = installation.romId,
                slot = slot,
                label = "Slot $slot",
                localPath = file.absolutePath,
                updatedAtEpochMs = file.lastModified(),
            ),
        )
    }

    suspend fun syncGame(installation: DownloadedRomEntity, rom: RomDto): SyncSummary {
        val resolution = resolveCoreSupport(
            rom = rom,
            file = rom.files.firstOrNull { it.id == installation.fileId } ?: rom.files.firstOrNull(),
        )
        val profile = resolution.runtimeProfile ?: error(resolution.message ?: "Unsupported platform.")
        return syncBridge.syncGame(installation, rom, profile)
    }

    private suspend fun requireActiveProfile(): ServerProfile {
        return authManager.getActiveProfile()
            ?: error("Configure server access before using the RomM library.")
    }

    private fun recommendedRuntimeProfile(resolution: CoreResolution): RuntimeProfile {
        return resolution.runtimeProfile
            ?: resolution.platformFamily
                ?.runtimeOptions
                ?.firstOrNull { it.runtimeId == resolution.platformFamily.defaultRuntimeId }
            ?: resolution.availableProfiles.firstOrNull()
            ?: error("No recommended core is configured for this platform yet.")
    }

    private suspend fun createService() = serviceFactory.create(requireActiveProfile())

    companion object {
        fun downloadTag(romId: Int, fileId: Int): String = "download-$romId-$fileId"
    }
}
