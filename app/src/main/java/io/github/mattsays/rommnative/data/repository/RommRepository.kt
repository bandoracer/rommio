package io.github.mattsays.rommnative.data.repository

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.mattsays.rommnative.data.auth.AuthManager
import io.github.mattsays.rommnative.data.local.DownloadRecordDao
import io.github.mattsays.rommnative.data.local.DownloadedRomDao
import io.github.mattsays.rommnative.data.local.SaveStateDao
import io.github.mattsays.rommnative.data.network.RommServiceFactory
import io.github.mattsays.rommnative.data.work.DownloadRomWorker
import io.github.mattsays.rommnative.domain.player.CoreInstaller
import io.github.mattsays.rommnative.domain.player.CoreResolver
import io.github.mattsays.rommnative.domain.player.CoreResolution
import io.github.mattsays.rommnative.domain.player.PlayerCapability
import io.github.mattsays.rommnative.domain.player.PlayerSession
import io.github.mattsays.rommnative.domain.player.RuntimeProfile
import io.github.mattsays.rommnative.domain.storage.LibraryStore
import io.github.mattsays.rommnative.domain.sync.SyncBridge
import io.github.mattsays.rommnative.model.AuthDiscoveryResult
import io.github.mattsays.rommnative.model.AuthStatus
import io.github.mattsays.rommnative.model.CollectionKind
import io.github.mattsays.rommnative.model.CloudflareServiceCredentials
import io.github.mattsays.rommnative.model.DownloadRecord
import io.github.mattsays.rommnative.model.DownloadRecordEntity
import io.github.mattsays.rommnative.model.DownloadStatus
import io.github.mattsays.rommnative.model.DirectLoginCredentials
import io.github.mattsays.rommnative.model.DownloadedRomEntity
import io.github.mattsays.rommnative.model.EdgeAuthMode
import io.github.mattsays.rommnative.model.InstalledPlatformSummary
import io.github.mattsays.rommnative.model.InteractiveSessionConfig
import io.github.mattsays.rommnative.model.InteractiveSessionProvider
import io.github.mattsays.rommnative.model.LibraryStorageSummary
import io.github.mattsays.rommnative.model.OriginAuthMode
import io.github.mattsays.rommnative.model.PlatformDto
import io.github.mattsays.rommnative.model.RomDto
import io.github.mattsays.rommnative.model.RomFileDto
import io.github.mattsays.rommnative.model.RommCollectionDto
import io.github.mattsays.rommnative.model.SaveStateEntity
import io.github.mattsays.rommnative.model.ServerAccessResult
import io.github.mattsays.rommnative.model.ServerAccessStatus
import io.github.mattsays.rommnative.model.ServerProfile
import io.github.mattsays.rommnative.model.SyncSummary
import io.github.mattsays.rommnative.model.toDomain
import io.github.mattsays.rommnative.model.toModel
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class RommRepository(
    private val context: Context,
    private val authManager: AuthManager,
    private val serviceFactory: RommServiceFactory,
    private val downloadedRomDao: DownloadedRomDao,
    private val downloadRecordDao: DownloadRecordDao,
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

    suspend fun listProfiles(): List<ServerProfile> {
        return authManager.listProfiles().sortedByDescending { it.updatedAt }
    }

    suspend fun activateProfile(profileId: String): ServerProfile {
        return authManager.setActiveProfile(profileId)
    }

    suspend fun getCurrentUser() = createService().getCurrentUser()

    suspend fun getPlatforms(): List<PlatformDto> {
        return createService().getPlatforms()
    }

    suspend fun getRecentlyAdded(): List<RomDto> {
        return createService().getRecentlyAdded().items
    }

    suspend fun getContinuePlaying(limit: Int = 12): List<RomDto> {
        return createService()
            .getRoms(
                lastPlayed = true,
                limit = limit,
                orderBy = "name",
                orderDir = "asc",
            )
            .items
    }

    suspend fun getRomsByPlatform(platformId: Int): List<RomDto> {
        return createService()
            .getRoms(platformIds = platformId, legacyPlatformId = platformId)
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

    suspend fun getCollections(): List<RommCollectionDto> {
        val service = createService()
        val regular = service.getCollections().map { it.toDomain() }
        val smart = service.getSmartCollections().map { it.toDomain() }
        val virtual = service.getVirtualCollections(type = "all", limit = 24).map { it.toDomain() }
        return (regular + smart + virtual)
            .sortedWith(
                compareByDescending<RommCollectionDto> { it.isFavorite }
                    .thenByDescending { it.romCount }
                    .thenBy { it.name.lowercase() },
            )
    }

    suspend fun getRomsForCollection(
        collection: RommCollectionDto,
        limit: Int = 50,
        offset: Int = 0,
    ): List<RomDto> {
        val response = when (collection.kind) {
            CollectionKind.REGULAR -> createService().getRoms(
                collectionId = collection.id.toIntOrNull(),
                limit = limit,
                offset = offset,
            )

            CollectionKind.SMART -> createService().getRoms(
                smartCollectionId = collection.id.toIntOrNull(),
                limit = limit,
                offset = offset,
            )

            CollectionKind.VIRTUAL -> createService().getRoms(
                virtualCollectionId = collection.id,
                limit = limit,
                offset = offset,
            )
        }
        return response.items
    }

    fun observeInstalledFiles(romId: Int): Flow<List<DownloadedRomEntity>> {
        return downloadedRomDao.observeByRomId(romId)
    }

    fun observeInstalledLibrary(): Flow<List<DownloadedRomEntity>> {
        return downloadedRomDao.observeAll()
    }

    fun observeInstalledPlatformSummaries(): Flow<List<InstalledPlatformSummary>> {
        return downloadedRomDao.observeAll().map(::summarizeInstalledPlatforms)
    }

    fun observeLibraryStorageSummary(): Flow<LibraryStorageSummary> {
        return downloadedRomDao.observeAll().map(::summarizeInstalledLibrary)
    }

    fun observeDownloadHistory(): Flow<List<DownloadRecord>> {
        return combine(
            downloadRecordDao.observeAll(),
            downloadedRomDao.observeAll(),
        ) { records, installed ->
            records
                .map { entity ->
                    val installedMatch = installed.firstOrNull { it.romId == entity.romId && it.fileId == entity.fileId }
                    entity.copy(localPath = installedMatch?.localPath ?: entity.localPath).toModel()
                }
                .sortedWith(downloadComparator)
        }
    }

    fun observeDownloadRecord(romId: Int, fileId: Int): Flow<DownloadRecord?> {
        return combine(
            downloadRecordDao.observeByIds(romId, fileId),
            downloadedRomDao.observeByRomId(romId),
        ) { record, installed ->
            record?.copy(
                localPath = installed.firstOrNull { it.fileId == fileId }?.localPath ?: record.localPath,
            )?.toModel()
        }
    }

    fun observeDownloadAttentionCount(): Flow<Int> {
        return observeDownloadHistory().map { records ->
            records.count { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.FAILED }
        }
    }

    fun observeSaveStates(romId: Int): Flow<List<SaveStateEntity>> {
        return saveStateDao.observeByRomId(romId)
    }

    fun resolveCoreSupport(rom: RomDto, file: RomFileDto?): CoreResolution {
        return coreResolver.resolve(rom.platformSlug, file?.effectiveFileExtension)
    }

    fun embeddedPlayerSupport(rom: RomDto): CoreResolution {
        return resolveCoreSupport(rom, rom.files.firstOrNull())
    }

    fun supportsEmbeddedPlayer(platform: PlatformDto): Boolean {
        return coreResolver.platformSupport(platform.slug) != null || coreResolver.platformSupport(platform.fsSlug) != null
    }

    fun isUnsupportedInApp(rom: RomDto): Boolean {
        return embeddedPlayerSupport(rom).capability == PlayerCapability.UNSUPPORTED
    }

    suspend fun getCollectionPreviewCoverUrls(
        collection: RommCollectionDto,
        limit: Int = 3,
    ): List<String> {
        return getRomsForCollection(collection, limit = limit)
            .mapNotNull { rom -> rom.urlCover?.takeIf { it.isNotBlank() } }
            .distinct()
            .take(limit)
    }

    suspend fun installRecommendedCore(rom: RomDto, file: RomFileDto?): CoreResolution {
        val resolution = resolveCoreSupport(rom, file)
        val profile = recommendedRuntimeProfile(resolution)
        coreInstaller.installCore(profile)
        return resolveCoreSupport(rom, file)
    }

    suspend fun enqueueDownload(
        rom: RomDto,
        file: RomFileDto,
        replaceExisting: Boolean = false,
        prioritize: Boolean = false,
    ) {
        queueDownload(
            romId = rom.id,
            fileId = file.id,
            romName = rom.displayName,
            platformSlug = rom.platformSlug,
            fileName = file.fileName,
            fileSizeBytes = file.fileSizeBytes,
            replaceExisting = replaceExisting,
            prioritize = prioritize,
        )
    }

    suspend fun downloadNow(
        rom: RomDto,
        file: RomFileDto,
        replaceExisting: Boolean = false,
    ) {
        preemptActiveDownloadFor(rom.id, file.id)
        enqueueDownload(
            rom = rom,
            file = file,
            replaceExisting = replaceExisting,
            prioritize = true,
        )
    }

    private suspend fun queueDownload(
        romId: Int,
        fileId: Int,
        romName: String,
        platformSlug: String,
        fileName: String,
        fileSizeBytes: Long,
        replaceExisting: Boolean,
        prioritize: Boolean,
    ) {
        val now = System.currentTimeMillis()
        val existing = downloadRecordDao.getByIds(romId, fileId)
        val installed = downloadedRomDao.getByIds(romId, fileId)

        if (!replaceExisting) {
            when (existing?.toModel()?.status) {
                DownloadStatus.RUNNING -> return
                DownloadStatus.QUEUED -> {
                    if (!prioritize) return
                    val allRecords = downloadRecordDao.listAll().filterNot { it.romId == romId && it.fileId == fileId }
                    downloadRecordDao.upsert(
                        existing.copy(
                            workId = null,
                            status = DownloadStatus.QUEUED.name,
                            progressPercent = 0,
                            bytesDownloaded = 0,
                            totalBytes = fileSizeBytes,
                            localPath = installed?.localPath ?: existing.localPath,
                            lastError = null,
                            enqueuedAtEpochMs = nextPriorityEnqueuedAt(allRecords, now),
                            startedAtEpochMs = null,
                            completedAtEpochMs = null,
                            updatedAtEpochMs = now,
                        ),
                    )
                    kickDownloadQueue()
                    return
                }

                else -> Unit
            }
        }

        val otherRecords = downloadRecordDao.listAll().filterNot { it.romId == romId && it.fileId == fileId }
        downloadRecordDao.upsert(
            DownloadRecordEntity(
                romId = romId,
                fileId = fileId,
                romName = romName,
                platformSlug = platformSlug,
                fileName = fileName,
                fileSizeBytes = fileSizeBytes,
                workId = null,
                status = DownloadStatus.QUEUED.name,
                progressPercent = 0,
                bytesDownloaded = 0,
                totalBytes = fileSizeBytes,
                localPath = installed?.localPath ?: existing?.localPath,
                lastError = null,
                enqueuedAtEpochMs = if (prioritize) nextPriorityEnqueuedAt(otherRecords, now) else now,
                startedAtEpochMs = null,
                completedAtEpochMs = null,
                updatedAtEpochMs = now,
            ),
        )
        kickDownloadQueue()
    }

    private fun kickDownloadQueue() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            DownloadRomWorker.QUEUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DownloadRomWorker>()
                .addTag(DownloadRomWorker.QUEUE_WORK_NAME)
                .build(),
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

    suspend fun cancelDownload(record: DownloadRecord) {
        if (record.status == DownloadStatus.RUNNING) {
            WorkManager.getInstance(context).cancelUniqueWork(DownloadRomWorker.QUEUE_WORK_NAME)
        }
        val now = System.currentTimeMillis()
        downloadRecordDao.upsert(
            DownloadRecordEntity(
                romId = record.romId,
                fileId = record.fileId,
                romName = record.romName,
                platformSlug = record.platformSlug,
                fileName = record.fileName,
                fileSizeBytes = record.fileSizeBytes,
                workId = null,
                status = DownloadStatus.CANCELED.name,
                progressPercent = record.progressPercent,
                bytesDownloaded = record.bytesDownloaded,
                totalBytes = record.totalBytes,
                localPath = record.localPath,
                lastError = null,
                enqueuedAtEpochMs = record.enqueuedAtEpochMs,
                startedAtEpochMs = record.startedAtEpochMs,
                completedAtEpochMs = record.completedAtEpochMs,
                updatedAtEpochMs = now,
            ),
        )
    }

    suspend fun retryDownload(record: DownloadRecord) {
        queueDownload(
            romId = record.romId,
            fileId = record.fileId,
            romName = record.romName,
            platformSlug = record.platformSlug,
            fileName = record.fileName,
            fileSizeBytes = record.fileSizeBytes,
            replaceExisting = true,
            prioritize = false,
        )
    }

    suspend fun downloadNow(record: DownloadRecord) {
        preemptActiveDownloadFor(record.romId, record.fileId)
        queueDownload(
            romId = record.romId,
            fileId = record.fileId,
            romName = record.romName,
            platformSlug = record.platformSlug,
            fileName = record.fileName,
            fileSizeBytes = record.fileSizeBytes,
            replaceExisting = true,
            prioritize = true,
        )
    }

    private suspend fun preemptActiveDownloadFor(targetRomId: Int, targetFileId: Int) {
        val now = System.currentTimeMillis()
        val activeRecord = downloadRecordDao.listAll().firstOrNull { record ->
            record.status == DownloadStatus.RUNNING.name &&
                !(record.romId == targetRomId && record.fileId == targetFileId)
        } ?: return

        downloadRecordDao.upsert(
            activeRecord.copy(
                workId = null,
                status = DownloadStatus.QUEUED.name,
                progressPercent = 0,
                bytesDownloaded = 0,
                totalBytes = activeRecord.fileSizeBytes,
                lastError = null,
                startedAtEpochMs = null,
                completedAtEpochMs = null,
                updatedAtEpochMs = now,
            ),
        )
        WorkManager.getInstance(context).cancelUniqueWork(DownloadRomWorker.QUEUE_WORK_NAME)
    }

    suspend fun deleteLocalDownload(record: DownloadRecord) {
        record.localPath?.let { path ->
            runCatching { File(path).delete() }
        }
        downloadedRomDao.delete(record.romId, record.fileId)
        val existing = downloadRecordDao.getByIds(record.romId, record.fileId) ?: return
        downloadRecordDao.upsert(
            existing.copy(
                localPath = null,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
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

}

private val downloadComparator = Comparator<DownloadRecord> { left, right ->
    val rankDiff = downloadStatusRank(left.status) - downloadStatusRank(right.status)
    if (rankDiff != 0) {
        rankDiff
    } else if (left.status == DownloadStatus.QUEUED && right.status == DownloadStatus.QUEUED) {
        compareValues(left.enqueuedAtEpochMs, right.enqueuedAtEpochMs)
            .takeIf { it != 0 }
            ?: compareValues(right.updatedAtEpochMs, left.updatedAtEpochMs)
    } else {
        compareValues(right.updatedAtEpochMs, left.updatedAtEpochMs)
    }
}

private fun downloadStatusRank(status: DownloadStatus): Int {
    return when (status) {
        DownloadStatus.RUNNING -> 0
        DownloadStatus.QUEUED -> 1
        DownloadStatus.FAILED -> 2
        DownloadStatus.CANCELED -> 3
        DownloadStatus.COMPLETED -> 4
    }
}

internal fun nextPriorityEnqueuedAt(records: List<DownloadRecordEntity>, now: Long): Long {
    val firstQueuedAt = records
        .filter { it.status == DownloadStatus.QUEUED.name }
        .minOfOrNull { it.enqueuedAtEpochMs }
        ?: return now
    return minOf(now, firstQueuedAt - 1L)
}

internal fun nextQueuedDownload(records: List<DownloadRecordEntity>): DownloadRecordEntity? {
    return records
        .filter { it.status == DownloadStatus.QUEUED.name }
        .sortedWith(
            compareBy<DownloadRecordEntity>(
                { it.enqueuedAtEpochMs },
                { it.updatedAtEpochMs },
                { it.romId },
                { it.fileId },
            ),
        )
        .firstOrNull()
}

fun summarizeInstalledLibrary(installed: List<DownloadedRomEntity>): LibraryStorageSummary {
    return LibraryStorageSummary(
        installedGameCount = installed.map { it.romId }.distinct().size,
        installedFileCount = installed.size,
        totalBytes = installed.sumOf { it.fileSizeBytes },
    )
}

fun summarizeInstalledPlatforms(installed: List<DownloadedRomEntity>): List<InstalledPlatformSummary> {
    return installed
        .groupBy { it.platformSlug }
        .map { (platformSlug, files) ->
            InstalledPlatformSummary(
                platformSlug = platformSlug,
                installedGameCount = files.map { it.romId }.distinct().size,
                installedFileCount = files.size,
                totalBytes = files.sumOf { it.fileSizeBytes },
            )
        }
        .sortedByDescending { it.installedGameCount }
}
