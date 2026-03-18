package io.github.mattsays.rommnative.data.repository

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.mattsays.rommnative.data.auth.AuthManager
import io.github.mattsays.rommnative.data.cache.ThumbnailCacheStore
import io.github.mattsays.rommnative.data.local.CachedCollectionDao
import io.github.mattsays.rommnative.data.local.CachedCollectionEntity
import io.github.mattsays.rommnative.data.local.CachedCollectionRomDao
import io.github.mattsays.rommnative.data.local.CachedCollectionRomEntity
import io.github.mattsays.rommnative.data.local.CachedHomeEntryDao
import io.github.mattsays.rommnative.data.local.CachedHomeEntryEntity
import io.github.mattsays.rommnative.data.local.CachedPlatformDao
import io.github.mattsays.rommnative.data.local.CachedPlatformEntity
import io.github.mattsays.rommnative.data.local.CachedRomDao
import io.github.mattsays.rommnative.data.local.CachedRomEntity
import io.github.mattsays.rommnative.data.local.DownloadRecordDao
import io.github.mattsays.rommnative.data.local.DownloadedRomDao
import io.github.mattsays.rommnative.data.local.PendingRemoteActionDao
import io.github.mattsays.rommnative.data.local.PendingRemoteActionEntity
import io.github.mattsays.rommnative.data.local.ProfileCacheStateDao
import io.github.mattsays.rommnative.data.local.ProfileCacheStateEntity
import io.github.mattsays.rommnative.data.local.SaveStateDao
import io.github.mattsays.rommnative.data.network.ConnectivityMonitor
import io.github.mattsays.rommnative.data.network.RommService
import io.github.mattsays.rommnative.data.network.RommServiceFactory
import io.github.mattsays.rommnative.data.work.DownloadRomWorker
import io.github.mattsays.rommnative.data.work.OfflineSyncWorker
import io.github.mattsays.rommnative.domain.player.CoreInstaller
import io.github.mattsays.rommnative.domain.player.CoreResolution
import io.github.mattsays.rommnative.domain.player.CoreResolver
import io.github.mattsays.rommnative.domain.player.PlayerCapability
import io.github.mattsays.rommnative.domain.player.PlayerSession
import io.github.mattsays.rommnative.domain.player.RuntimeProfile
import io.github.mattsays.rommnative.domain.storage.LibraryStore
import io.github.mattsays.rommnative.domain.sync.SyncBridge
import io.github.mattsays.rommnative.model.AuthDiscoveryResult
import io.github.mattsays.rommnative.model.AuthStatus
import io.github.mattsays.rommnative.model.CloudflareServiceCredentials
import io.github.mattsays.rommnative.model.CollectionKind
import io.github.mattsays.rommnative.model.ConnectivityState
import io.github.mattsays.rommnative.model.DirectLoginCredentials
import io.github.mattsays.rommnative.model.DownloadRecord
import io.github.mattsays.rommnative.model.DownloadRecordEntity
import io.github.mattsays.rommnative.model.DownloadStatus
import io.github.mattsays.rommnative.model.DownloadedRomEntity
import io.github.mattsays.rommnative.model.EdgeAuthMode
import io.github.mattsays.rommnative.model.InstalledPlatformSummary
import io.github.mattsays.rommnative.model.InteractiveSessionConfig
import io.github.mattsays.rommnative.model.InteractiveSessionProvider
import io.github.mattsays.rommnative.model.LibraryStorageSummary
import io.github.mattsays.rommnative.model.MediaCacheCategory
import io.github.mattsays.rommnative.model.OfflineHomeFeedType
import io.github.mattsays.rommnative.model.OfflineState
import io.github.mattsays.rommnative.model.OriginAuthMode
import io.github.mattsays.rommnative.model.PendingCoreDownloadPayload
import io.github.mattsays.rommnative.model.PendingGameSyncPayload
import io.github.mattsays.rommnative.model.PendingRemoteActionStatus
import io.github.mattsays.rommnative.model.PendingRemoteActionType
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
import io.github.mattsays.rommnative.util.buildRomContentPath
import io.github.mattsays.rommnative.util.resolveRemoteAssetUrl
import java.io.File
import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CachedHomeSnapshot(
    val continuePlaying: List<RomDto> = emptyList(),
    val recentRoms: List<RomDto> = emptyList(),
    val featuredCollections: List<RommCollectionDto> = emptyList(),
    val collectionPreviewCoverUrls: Map<String, List<String>> = emptyMap(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class RommRepository(
    private val context: Context,
    private val authManager: AuthManager,
    private val serviceFactory: RommServiceFactory,
    private val downloadedRomDao: DownloadedRomDao,
    private val downloadRecordDao: DownloadRecordDao,
    private val saveStateDao: SaveStateDao,
    private val cachedPlatformDao: CachedPlatformDao,
    private val cachedRomDao: CachedRomDao,
    private val cachedCollectionDao: CachedCollectionDao,
    private val cachedCollectionRomDao: CachedCollectionRomDao,
    private val cachedHomeEntryDao: CachedHomeEntryDao,
    private val profileCacheStateDao: ProfileCacheStateDao,
    private val pendingRemoteActionDao: PendingRemoteActionDao,
    private val libraryStore: LibraryStore,
    private val coreResolver: CoreResolver,
    private val coreInstaller: CoreInstaller,
    private val syncBridge: SyncBridge,
    private val connectivityMonitor: ConnectivityMonitor,
    private val thumbnailCacheStore: ThumbnailCacheStore,
) {
    private val workManager = WorkManager.getInstance(context)
    private val cacheCodec = OfflineCacheJsonCodec()
    private val refreshMutex = Mutex()
    private val inFlightRefreshes = mutableMapOf<String, CompletableDeferred<Result<Unit>>>()

    fun activeProfileFlow(): Flow<ServerProfile?> = authManager.activeProfileFlow()

    fun observeOfflineState(): Flow<OfflineState> {
        val activeProfileFlow = activeProfileFlow()
        val activeCacheStateFlow = activeProfileFlow.flatMapLatest { profile ->
            if (profile == null) {
                flowOf(null)
            } else {
                profileCacheStateDao.observeByProfile(profile.id)
            }
        }
        return combine(
            activeProfileFlow,
            connectivityMonitor.observe(),
            activeCacheStateFlow,
        ) { profile, connectivity, cacheState ->
            OfflineState(
                connectivity = connectivity,
                activeProfileId = profile?.id,
                catalogReady = cacheState?.catalogReady == true,
                mediaReady = cacheState?.mediaReady == true,
                isRefreshing = cacheState?.isRefreshing == true,
                lastFullSyncAtEpochMs = cacheState?.lastFullSyncAtEpochMs,
                lastMediaSyncAtEpochMs = cacheState?.lastMediaSyncAtEpochMs,
                lastError = cacheState?.lastError,
            )
        }.mapLatest { state ->
            state.copy(
                cacheBytes = state.activeProfileId?.let { profileId ->
                    thumbnailCacheStore.totalBytes(profileId)
                } ?: 0L,
            )
        }
    }

    fun observeCachedHome(limitContinue: Int = 10, limitRecent: Int = 12, limitCollections: Int = 8): Flow<CachedHomeSnapshot> {
        return activeProfileFlow().flatMapLatest { profile ->
            if (profile == null) {
                flowOf(CachedHomeSnapshot())
            } else {
                combine(
                    cachedHomeEntryDao.observeByFeed(profile.id, OfflineHomeFeedType.CONTINUE_PLAYING.name),
                    cachedHomeEntryDao.observeByFeed(profile.id, OfflineHomeFeedType.RECENTLY_ADDED.name),
                    cachedHomeEntryDao.observeByFeed(profile.id, OfflineHomeFeedType.FEATURED_COLLECTION.name),
                    cachedRomDao.observeAll(profile.id),
                    cachedCollectionDao.observeAll(profile.id),
                ) { continueEntries, recentEntries, featuredEntries, roms, collections ->
                    val romMap = roms.associateBy { it.romId }
                    val collectionMap = collections.associateBy { "${it.kind}:${it.collectionId}" }
                    val featuredCollections = featuredEntries.mapNotNull { entry ->
                        collectionMap["${entry.collectionKind}:${entry.collectionId}"]?.toDomain(cacheCodec)
                    }.take(limitCollections)
                    CachedHomeSnapshot(
                        continuePlaying = continueEntries.mapNotNull { entry ->
                            entry.romId?.let(romMap::get)?.toDomain(cacheCodec)
                        }.take(limitContinue),
                        recentRoms = recentEntries.mapNotNull { entry ->
                            entry.romId?.let(romMap::get)?.toDomain(cacheCodec)
                        }.take(limitRecent),
                        featuredCollections = featuredCollections,
                        collectionPreviewCoverUrls = featuredCollections.associate { collection ->
                            collection.cacheKey to previewCoverUrlsFromCache(
                                profileId = profile.id,
                                collection = collection,
                                cachedRoms = roms,
                                limit = 3,
                            )
                        },
                    )
                }
            }
        }
    }

    fun observeCachedPlatforms(): Flow<List<PlatformDto>> {
        return activeProfileFlow().flatMapLatest { profile ->
            if (profile == null) {
                flowOf(emptyList())
            } else {
                cachedPlatformDao.observeAll(profile.id).map { platforms ->
                    platforms.map(CachedPlatformEntity::toDomain)
                }
            }
        }
    }

    fun observeCachedCollections(): Flow<List<RommCollectionDto>> {
        return activeProfileFlow().flatMapLatest { profile ->
            if (profile == null) {
                flowOf(emptyList())
            } else {
                cachedCollectionDao.observeAll(profile.id).map { collections ->
                    collections.map { it.toDomain(cacheCodec) }
                }
            }
        }
    }

    fun observeCachedPlatformRoms(platformId: Int): Flow<List<RomDto>> {
        return activeProfileFlow().flatMapLatest { profile ->
            if (profile == null) {
                flowOf(emptyList())
            } else {
                cachedRomDao.observeByPlatform(profile.id, platformId).map { roms ->
                    roms.map { it.toDomain(cacheCodec) }
                }
            }
        }
    }

    fun observeCachedCollection(kind: CollectionKind, id: String): Flow<RommCollectionDto?> {
        return activeProfileFlow().flatMapLatest { profile ->
            if (profile == null) {
                flowOf(null)
            } else {
                cachedCollectionDao.observeById(profile.id, kind.name, id).map { entity ->
                    entity?.toDomain(cacheCodec)
                }
            }
        }
    }

    fun observeCachedCollectionRoms(kind: CollectionKind, id: String): Flow<List<RomDto>> {
        return activeProfileFlow().flatMapLatest { profile ->
            if (profile == null) {
                flowOf(emptyList())
            } else {
                combine(
                    cachedCollectionRomDao.observeByCollection(profile.id, kind.name, id),
                    cachedRomDao.observeAll(profile.id),
                ) { memberships, roms ->
                    val romMap = roms.associateBy { it.romId }
                    memberships.mapNotNull { membership ->
                        romMap[membership.romId]?.toDomain(cacheCodec)
                    }
                }
            }
        }
    }

    fun observeCachedRom(romId: Int): Flow<RomDto?> {
        return activeProfileFlow().flatMapLatest { profile ->
            if (profile == null) {
                flowOf(null)
            } else {
                cachedRomDao.observeById(profile.id, romId).map { entity ->
                    entity?.toDomain(cacheCodec)
                }
            }
        }
    }

    suspend fun initializeAuth() {
        if (connectivityMonitor.currentState() == ConnectivityState.ONLINE) {
            authManager.initializeForAppLaunch()
        }
        scheduleOfflineSync(force = false)
    }

    suspend fun currentProfile(): ServerProfile? = authManager.getActiveProfile()

    fun currentConnectivityState(): ConnectivityState = connectivityMonitor.currentState()

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
        val profile = authManager.configureServerProfile(
            baseUrl = baseUrl,
            label = label,
            edgeAuthMode = edgeAuthMode,
            originAuthMode = originAuthMode,
            discoveryResult = discoveryResult,
            makeActive = makeActive,
        )
        ensureProfileCacheState(profile.id)
        return profile
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
        val status = authManager.completeInteractiveLogin(profileId, provider)
        if (status == AuthStatus.CONNECTED) {
            scheduleOfflineSync(force = true)
        }
        return status
    }

    suspend fun testServerAccess(profileId: String? = null): ServerAccessResult {
        return authManager.testServerAccess(profileId)
    }

    suspend fun loginWithDirectCredentials(profileId: String, credentials: DirectLoginCredentials) {
        authManager.loginWithDirectCredentials(profileId, credentials)
        scheduleOfflineSync(force = true)
    }

    suspend fun validateProfile(profileId: String? = null): AuthStatus {
        val status = authManager.validateProfile(profileId)
        if (status == AuthStatus.CONNECTED) {
            scheduleOfflineSync(force = true)
        }
        return status
    }

    suspend fun logout(clearServerAccess: Boolean = false) {
        authManager.getActiveProfile()?.let { profile ->
            pendingRemoteActionDao.deleteByProfile(profile.id)
            workManager.cancelUniqueWork(DownloadRomWorker.QUEUE_WORK_NAME)
            workManager.cancelUniqueWork(OfflineSyncWorker.WORK_NAME)
        }
        authManager.logout(clearServerAccess = clearServerAccess)
    }

    suspend fun clearServerAccess(profileId: String) {
        authManager.clearServerAccess(profileId)
    }

    suspend fun listProfiles(): List<ServerProfile> {
        return authManager.listProfiles().sortedByDescending { it.updatedAt }
    }

    suspend fun activateProfile(profileId: String): ServerProfile {
        val profile = authManager.setActiveProfile(profileId)
        ensureProfileCacheState(profile.id)
        scheduleOfflineSync(force = true)
        return profile
    }

    suspend fun deleteProfile(profileId: String): ServerProfile? {
        val wasActive = authManager.getActiveProfile()?.id == profileId
        pendingRemoteActionDao.deleteByProfile(profileId)
        cachedHomeEntryDao.deleteByProfile(profileId)
        cachedCollectionRomDao.deleteByProfile(profileId)
        cachedCollectionDao.deleteByProfile(profileId)
        cachedRomDao.deleteByProfile(profileId)
        cachedPlatformDao.deleteByProfile(profileId)
        profileCacheStateDao.deleteByProfile(profileId)
        thumbnailCacheStore.clearProfile(profileId)
        val nextProfile = authManager.deleteProfile(profileId)
        if (wasActive) {
            workManager.cancelUniqueWork(DownloadRomWorker.QUEUE_WORK_NAME)
            workManager.cancelUniqueWork(OfflineSyncWorker.WORK_NAME)
            nextProfile?.let {
                ensureProfileCacheState(it.id)
                scheduleOfflineSync(force = true)
            }
        }
        return nextProfile
    }

    suspend fun getCurrentUser() = if (isOnline()) {
        createService().getCurrentUser()
    } else {
        error("Offline. Cached library data is still available.")
    }

    suspend fun refreshPlatformsInBackground() {
        val profile = requireActiveProfile()
        if (!isOnline()) return
        runTargetedRefresh(profile, "${profile.id}:platforms") {
            val service = createService(profile)
            val platforms = service.getPlatforms()
            cachePlatforms(profile, platforms)
            warmPlatformMedia(profile, platforms)
            markRefreshSucceeded(profile.id, updateFullSync = true, updateMediaSync = true)
        }
    }

    suspend fun refreshCollectionsInBackground() {
        val profile = requireActiveProfile()
        if (!isOnline()) return
        runTargetedRefresh(profile, "${profile.id}:collections") {
            val service = createService(profile)
            val collections = fetchCollections(service)
            cacheCollections(profile, collections)
            cacheCollectionFeed(profile.id, collections.take(8))
            warmCollectionMedia(profile, collections)
            markRefreshSucceeded(profile.id, updateFullSync = true, updateMediaSync = true)
        }
    }

    suspend fun refreshPlatformInBackground(platformId: Int) {
        val profile = requireActiveProfile()
        if (!isOnline()) return
        runTargetedRefresh(profile, "${profile.id}:platform:$platformId") {
            val service = createService(profile)
            val roms = service.getRoms(platformIds = platformId, legacyPlatformId = platformId).items
            cacheRoms(profile, roms)
            warmRomMedia(profile, roms, pinnedRomIds = emptySet())
            markRefreshSucceeded(profile.id, updateFullSync = true, updateMediaSync = true)
        }
    }

    suspend fun refreshCollectionInBackground(kind: CollectionKind, id: String) {
        val profile = requireActiveProfile()
        if (!isOnline()) return
        runTargetedRefresh(profile, "${profile.id}:collection:${kind.name}:$id") {
            val service = createService(profile)
            val collections = fetchCollections(service)
            cacheCollections(profile, collections)
            cacheCollectionFeed(profile.id, collections.take(8))
            val collection = collections.firstOrNull { it.kind == kind && it.id == id }
                ?: error("This collection is no longer available.")
            val roms = when (kind) {
                CollectionKind.REGULAR -> service.getRoms(collectionId = collection.id.toIntOrNull()).items
                CollectionKind.SMART -> service.getRoms(smartCollectionId = collection.id.toIntOrNull()).items
                CollectionKind.VIRTUAL -> service.getRoms(virtualCollectionId = collection.id).items
            }
            cacheRoms(profile, roms)
            replaceCollectionMembership(profile.id, collection, roms.map { it.id })
            warmCollectionMedia(profile, listOf(collection))
            warmRomMedia(profile, roms, pinnedRomIds = roms.map { it.id }.toSet())
            markRefreshSucceeded(profile.id, updateFullSync = true, updateMediaSync = true)
        }
    }

    suspend fun refreshRomInBackground(romId: Int) {
        val profile = requireActiveProfile()
        if (!isOnline()) return
        runTargetedRefresh(profile, "${profile.id}:rom:$romId") {
            val service = createService(profile)
            val rom = fetchDetailedRom(service, romId)
            cacheRoms(profile, listOf(rom))
            warmRomMedia(profile, listOf(rom), pinnedRomIds = setOf(romId))
            markRefreshSucceeded(profile.id, updateFullSync = true, updateMediaSync = true)
        }
    }

    suspend fun getPlatforms(): List<PlatformDto> {
        val profile = requireActiveProfile()
        if (!isOnline()) {
            return getCachedPlatforms(profile.id).ifEmpty {
                error("Offline. This profile has not been hydrated yet.")
            }
        }
        return runCatching {
            createService(profile).getPlatforms().also { platforms ->
                cachePlatforms(profile, platforms)
            }
        }.getOrElse { error ->
            getCachedPlatforms(profile.id).ifEmpty { throw error }
        }
    }

    suspend fun getRecentlyAdded(): List<RomDto> {
        val profile = requireActiveProfile()
        if (!isOnline()) {
            return cachedHomeRoms(profile.id, OfflineHomeFeedType.RECENTLY_ADDED).ifEmpty {
                error("Offline. Recently added titles are unavailable until this profile finishes syncing.")
            }
        }
        return runCatching {
            createService(profile).getRecentlyAdded().items.also { roms ->
                cacheRoms(profile, roms)
                cacheRomFeed(profile.id, OfflineHomeFeedType.RECENTLY_ADDED, roms)
            }
        }.getOrElse { error ->
            cachedHomeRoms(profile.id, OfflineHomeFeedType.RECENTLY_ADDED).ifEmpty { throw error }
        }
    }

    suspend fun getContinuePlaying(limit: Int = 12): List<RomDto> {
        val profile = requireActiveProfile()
        if (!isOnline()) {
            return cachedHomeRoms(profile.id, OfflineHomeFeedType.CONTINUE_PLAYING).take(limit).ifEmpty {
                error("Offline. Continue playing is unavailable until this profile finishes syncing.")
            }
        }
        return runCatching {
            createService(profile)
                .getRoms(
                    lastPlayed = true,
                    limit = limit,
                    orderBy = "name",
                    orderDir = "asc",
                )
                .items
                .also { roms ->
                    cacheRoms(profile, roms)
                    cacheRomFeed(profile.id, OfflineHomeFeedType.CONTINUE_PLAYING, roms)
                }
        }.getOrElse { error ->
            cachedHomeRoms(profile.id, OfflineHomeFeedType.CONTINUE_PLAYING).take(limit).ifEmpty { throw error }
        }
    }

    suspend fun getRomsByPlatform(platformId: Int): List<RomDto> {
        val profile = requireActiveProfile()
        if (!isOnline()) {
            return cachedRomDao.listByPlatform(profile.id, platformId).map { it.toDomain(cacheCodec) }.ifEmpty {
                error("Offline. This platform has not been hydrated yet.")
            }
        }
        return runCatching {
            createService(profile)
                .getRoms(platformIds = platformId, legacyPlatformId = platformId)
                .items
                .also { cacheRoms(profile, it) }
        }.getOrElse { error ->
            cachedRomDao.listByPlatform(profile.id, platformId).map { it.toDomain(cacheCodec) }.ifEmpty { throw error }
        }
    }

    suspend fun getRomById(romId: Int): RomDto {
        val profile = requireActiveProfile()
        if (!isOnline()) {
            return cachedRomDao.getById(profile.id, romId)?.toDomain(cacheCodec)
                ?: error("Offline. This title is unavailable until the active profile is synced.")
        }

        return runCatching {
            val service = createService(profile)
            fetchDetailedRom(service, romId).also { detailedRom ->
                cacheRoms(profile, listOf(detailedRom))
            }
        }.getOrElse { error ->
            cachedRomDao.getById(profile.id, romId)?.toDomain(cacheCodec) ?: throw error
        }
    }

    suspend fun getCollections(): List<RommCollectionDto> {
        val profile = requireActiveProfile()
        if (!isOnline()) {
            return cachedCollectionDao.listAll(profile.id).map { it.toDomain(cacheCodec) }.ifEmpty {
                error("Offline. Collections are unavailable until this profile finishes syncing.")
            }
        }
        return runCatching {
            val service = createService(profile)
            val collections = fetchCollections(service)
            cacheCollections(profile, collections)
            cacheCollectionFeed(profile.id, collections.take(8))
            collections
        }.getOrElse { error ->
            cachedCollectionDao.listAll(profile.id).map { it.toDomain(cacheCodec) }.ifEmpty { throw error }
        }
    }

    suspend fun getRomsForCollection(
        collection: RommCollectionDto,
        limit: Int = 50,
        offset: Int = 0,
    ): List<RomDto> {
        val profile = requireActiveProfile()
        if (!isOnline()) {
            return cachedRomsForCollection(profile.id, collection, limit, offset).ifEmpty {
                error("Offline. This collection has not been hydrated yet.")
            }
        }
        return runCatching {
            val response = when (collection.kind) {
                CollectionKind.REGULAR -> createService(profile).getRoms(
                    collectionId = collection.id.toIntOrNull(),
                    limit = limit,
                    offset = offset,
                )

                CollectionKind.SMART -> createService(profile).getRoms(
                    smartCollectionId = collection.id.toIntOrNull(),
                    limit = limit,
                    offset = offset,
                )

                CollectionKind.VIRTUAL -> createService(profile).getRoms(
                    virtualCollectionId = collection.id,
                    limit = limit,
                    offset = offset,
                )
            }
            response.items.also { roms ->
                cacheRoms(profile, roms)
                if (offset == 0 && roms.isNotEmpty()) {
                    replaceCollectionMembership(
                        profileId = profile.id,
                        collection = collection,
                        romIds = roms.map { it.id },
                    )
                }
            }
        }.getOrElse { error ->
            cachedRomsForCollection(profile.id, collection, limit, offset).ifEmpty { throw error }
        }
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

    fun observeRecentInstalledRoms(limit: Int = 8): Flow<List<RomDto>> {
        return activeProfileFlow().flatMapLatest { profile ->
            if (profile == null) {
                flowOf(emptyList())
            } else {
                combine(
                    downloadedRomDao.observeAll(),
                    cachedRomDao.observeAll(profile.id),
                ) { installed, cachedRoms ->
                    val romMap = cachedRoms.associateBy { it.romId }
                    installed
                        .map { install ->
                            romMap[install.romId]?.toDomain(cacheCodec) ?: install.toFallbackRom()
                        }
                        .distinctBy { it.id }
                        .take(limit)
                }
            }
        }
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
        val profile = requireActiveProfile()
        return previewCoverUrlsFromCache(profile.id, collection, limit = limit)
    }

    suspend fun installRecommendedCore(rom: RomDto, file: RomFileDto?): CoreResolution {
        val resolution = resolveCoreSupport(rom, file)
        val profile = recommendedRuntimeProfile(resolution)
        coreInstaller.installCore(profile)
        return resolveCoreSupport(rom, file)
    }

    suspend fun enqueuePendingSync(installation: DownloadedRomEntity, rom: RomDto) {
        val profile = requireActiveProfile()
        cacheRoms(profile, listOf(rom))
        val payload = moshiGameSyncPayload(PendingGameSyncPayload(installation.romId, installation.fileId))
        enqueuePendingAction(
            profileId = profile.id,
            actionType = PendingRemoteActionType.GAME_SYNC,
            dedupeKey = "${installation.romId}:${installation.fileId}",
            payloadJson = payload,
        )
    }

    suspend fun enqueuePendingCoreDownload(runtimeProfile: RuntimeProfile) {
        val profile = requireActiveProfile()
        val payload = moshiCorePayload(PendingCoreDownloadPayload(runtimeProfile.runtimeId))
        enqueuePendingAction(
            profileId = profile.id,
            actionType = PendingRemoteActionType.CORE_DOWNLOAD,
            dedupeKey = runtimeProfile.runtimeId,
            payloadJson = payload,
        )
    }

    suspend fun drainPendingRemoteActions() {
        if (!isOnline()) return
        val profile = authManager.getActiveProfile() ?: return
        pendingRemoteActionDao.listPendingByProfile(profile.id).forEach { action ->
            val running = action.copy(
                status = PendingRemoteActionStatus.RUNNING.name,
                updatedAtEpochMs = System.currentTimeMillis(),
                lastError = null,
            )
            pendingRemoteActionDao.upsert(running)
            runCatching {
                when (PendingRemoteActionType.valueOf(action.actionType)) {
                    PendingRemoteActionType.GAME_SYNC -> drainGameSyncAction(profile, running)
                    PendingRemoteActionType.CORE_DOWNLOAD -> drainCoreDownloadAction(running)
                }
                pendingRemoteActionDao.deleteByKey(profile.id, action.actionType, action.dedupeKey)
            }.onFailure { error ->
                pendingRemoteActionDao.upsert(
                    running.copy(
                        status = PendingRemoteActionStatus.FAILED.name,
                        updatedAtEpochMs = System.currentTimeMillis(),
                        lastError = error.message ?: "Pending action failed.",
                    ),
                )
            }
        }
    }

    suspend fun refreshActiveProfileCache(force: Boolean = false) {
        val profile = authManager.getActiveProfile() ?: return
        ensureProfileCacheState(profile.id)
        if (!isOnline()) return
        runScopedRefresh(scopeKey = "${profile.id}:full") {
            val existingState = profileCacheStateDao.getByProfile(profile.id)
            profileCacheStateDao.upsert(
                (existingState ?: ProfileCacheStateEntity(profileId = profile.id)).copy(
                    isRefreshing = true,
                    lastError = null,
                ),
            )

            runCatching {
                val service = createService(profile)
                val platforms = service.getPlatforms()
                val recent = service.getRecentlyAdded().items
                val continuePlaying = service.getRoms(
                    lastPlayed = true,
                    limit = 12,
                    orderBy = "name",
                    orderDir = "asc",
                ).items
                val collections = fetchCollections(service)
                val allRoms = fetchAllRoms(service)

                cachePlatforms(profile, platforms)
                cacheRoms(profile, allRoms)
                cacheCollections(profile, collections)
                cacheRomFeed(profile.id, OfflineHomeFeedType.CONTINUE_PLAYING, continuePlaying)
                cacheRomFeed(profile.id, OfflineHomeFeedType.RECENTLY_ADDED, recent)
                cacheCollectionFeed(profile.id, collections.take(8))

                val catalogSyncedAt = System.currentTimeMillis()
                profileCacheStateDao.upsert(
                    ProfileCacheStateEntity(
                        profileId = profile.id,
                        lastFullSyncAtEpochMs = catalogSyncedAt,
                        lastMediaSyncAtEpochMs = existingState?.lastMediaSyncAtEpochMs,
                        catalogReady = true,
                        mediaReady = false,
                        isRefreshing = true,
                        lastError = null,
                    ),
                )

                val mediaReady = warmMedia(profile, platforms, allRoms, collections, continuePlaying, recent)
                cachePlatforms(profile, platforms)
                cacheRoms(profile, allRoms)
                cacheCollections(profile, collections)

                val finishedAt = System.currentTimeMillis()
                profileCacheStateDao.upsert(
                    ProfileCacheStateEntity(
                        profileId = profile.id,
                        lastFullSyncAtEpochMs = catalogSyncedAt,
                        lastMediaSyncAtEpochMs = if (mediaReady) finishedAt else existingState?.lastMediaSyncAtEpochMs,
                        catalogReady = true,
                        mediaReady = mediaReady,
                        isRefreshing = false,
                        lastError = null,
                    ),
                )
            }.onFailure { error ->
                val previous = profileCacheStateDao.getByProfile(profile.id)
                profileCacheStateDao.upsert(
                    ProfileCacheStateEntity(
                        profileId = profile.id,
                        lastFullSyncAtEpochMs = previous?.lastFullSyncAtEpochMs,
                        lastMediaSyncAtEpochMs = previous?.lastMediaSyncAtEpochMs,
                        catalogReady = previous?.catalogReady == true,
                        mediaReady = previous?.mediaReady == true,
                        isRefreshing = false,
                        lastError = error.message ?: "Profile cache refresh failed.",
                    ),
                )
                throw error
            }
        }
    }

    suspend fun canOpenOffline(profile: ServerProfile?): Boolean {
        if (profile == null) return false
        if (profile.serverAccess.status != ServerAccessStatus.READY) return false
        if (profile.status != AuthStatus.CONNECTED && !profile.sessionState.hasOriginSession) return false
        val cacheState = profileCacheStateDao.getByProfile(profile.id)
        return cacheState?.catalogReady == true
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
            workManager.cancelUniqueWork(DownloadRomWorker.QUEUE_WORK_NAME)
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

    suspend fun enqueueDownload(
        rom: RomDto,
        file: RomFileDto,
        replaceExisting: Boolean = false,
        prioritize: Boolean = false,
    ) {
        authManager.getActiveProfile()?.let { profile ->
            cacheRoms(profile, listOf(rom))
        }
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
                lastError = if (isOnline()) null else "Waiting for a connection before downloading.",
                enqueuedAtEpochMs = if (prioritize) nextPriorityEnqueuedAt(otherRecords, now) else now,
                startedAtEpochMs = null,
                completedAtEpochMs = null,
                updatedAtEpochMs = now,
            ),
        )
        kickDownloadQueue()
        scheduleOfflineSync(force = false)
    }

    private fun kickDownloadQueue() {
        workManager.enqueueUniqueWork(
            DownloadRomWorker.QUEUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DownloadRomWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .addTag(DownloadRomWorker.QUEUE_WORK_NAME)
                .build(),
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
        workManager.cancelUniqueWork(DownloadRomWorker.QUEUE_WORK_NAME)
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
                screenshotPath = null,
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

    suspend fun findCachedRom(romId: Int): RomDto? {
        val profile = authManager.getActiveProfile() ?: return null
        return cachedRomDao.getById(profile.id, romId)?.toDomain(cacheCodec)
    }

    private suspend fun requireActiveProfile(): ServerProfile {
        return authManager.getActiveProfile()
            ?: error("Configure server access before using the RomM library.")
    }

    private suspend fun isOnline(): Boolean = connectivityMonitor.currentState() == ConnectivityState.ONLINE

    private suspend fun createService() = serviceFactory.create(requireActiveProfile())

    private suspend fun createService(profile: ServerProfile) = serviceFactory.create(profile)

    private fun recommendedRuntimeProfile(resolution: CoreResolution): RuntimeProfile {
        return resolution.runtimeProfile
            ?: resolution.platformFamily
                ?.runtimeOptions
                ?.firstOrNull { it.runtimeId == resolution.platformFamily.defaultRuntimeId }
            ?: resolution.availableProfiles.firstOrNull()
            ?: error("No recommended core is configured for this platform yet.")
    }

    private suspend fun ensureProfileCacheState(profileId: String) {
        if (profileCacheStateDao.getByProfile(profileId) == null) {
            profileCacheStateDao.upsert(ProfileCacheStateEntity(profileId = profileId))
        }
    }

    private suspend fun getCachedPlatforms(profileId: String): List<PlatformDto> {
        return cachedPlatformDao.listAll(profileId).map(CachedPlatformEntity::toDomain)
    }

    private suspend fun cachedHomeRoms(profileId: String, feedType: OfflineHomeFeedType): List<RomDto> {
        val entries = cachedHomeEntryDao.listByFeed(profileId, feedType.name)
        if (entries.isEmpty()) return emptyList()
        val romsById = cachedRomDao.listByIds(profileId, entries.mapNotNull { it.romId }).associateBy { it.romId }
        return entries.mapNotNull { entry -> entry.romId?.let(romsById::get)?.toDomain(cacheCodec) }
    }

    private suspend fun cachedRomsForCollection(
        profileId: String,
        collection: RommCollectionDto,
        limit: Int,
        offset: Int,
    ): List<RomDto> {
        val membership = cachedCollectionRomDao.listByCollection(profileId, collection.kind.name, collection.id)
        if (membership.isEmpty()) return emptyList()
        val targetIds = membership.drop(offset).take(limit).map { it.romId }
        if (targetIds.isEmpty()) return emptyList()
        val romMap = cachedRomDao.listByIds(profileId, targetIds).associateBy { it.romId }
        return targetIds.mapNotNull { romId -> romMap[romId]?.toDomain(cacheCodec) }
    }

    private suspend fun previewCoverUrlsFromCache(
        profileId: String,
        collection: RommCollectionDto,
        cachedRoms: List<CachedRomEntity>? = null,
        limit: Int,
    ): List<String> {
        val romMap = cachedRoms?.associateBy { it.romId }
        val previewFromMembership = cachedCollectionRomDao.listByCollection(profileId, collection.kind.name, collection.id)
            .mapNotNull { membership ->
                romMap?.get(membership.romId)?.toDomain(cacheCodec)?.urlCover
                    ?: cachedRomDao.getById(profileId, membership.romId)?.toDomain(cacheCodec)?.urlCover
            }
            .filter { it.isNotBlank() }
            .distinct()
            .take(limit)
        if (previewFromMembership.isNotEmpty()) {
            return previewFromMembership
        }
        return listOfNotNull(
            collection.pathCoverLarge,
            collection.pathCoverSmall,
            collection.pathCoversLarge.firstOrNull(),
            collection.pathCoversSmall.firstOrNull(),
        )
            .map { resolveRemoteAssetUrl(null, it) ?: it }
            .distinct()
            .take(limit)
    }

    private suspend fun cachePlatforms(profile: ServerProfile, platforms: List<PlatformDto>) {
        val now = System.currentTimeMillis()
        cachedPlatformDao.deleteByProfile(profile.id)
        cachedPlatformDao.upsertAll(
            platforms.map { platform ->
                val absoluteLogo = platform.platformLogoSource(profile.baseUrl)
                CachedPlatformEntity(
                    profileId = profile.id,
                    platformId = platform.id,
                    slug = platform.slug,
                    name = platform.name,
                    fsSlug = platform.fsSlug,
                    urlLogo = platform.urlLogo,
                    romCount = platform.romCount,
                    logoCachedUri = absoluteLogo?.let { thumbnailCacheStore.resolveCachedUri(profile.id, it) },
                    updatedAtEpochMs = now,
                )
            },
        )
    }

    private suspend fun cacheRoms(profile: ServerProfile, roms: List<RomDto>) {
        if (roms.isEmpty()) return
        val now = System.currentTimeMillis()
        cachedRomDao.upsertAll(
            roms.distinctBy { it.id }.map { rom ->
                val absoluteCover = rom.coverSource(profile.baseUrl)
                CachedRomEntity(
                    profileId = profile.id,
                    romId = rom.id,
                    name = rom.name,
                    summary = rom.summary,
                    platformId = rom.platformId,
                    platformName = rom.platformName,
                    platformSlug = rom.platformSlug,
                    fsName = rom.fsName,
                    filesJson = cacheCodec.encodeFiles(rom.files),
                    siblingsJson = cacheCodec.encodeSiblings(rom.siblings.orEmpty()),
                    urlCover = rom.urlCover,
                    coverCachedUri = absoluteCover?.let { thumbnailCacheStore.resolveCachedUri(profile.id, it) },
                    updatedAtEpochMs = now,
                )
            },
        )
    }

    private suspend fun cacheCollections(profile: ServerProfile, collections: List<RommCollectionDto>) {
        val now = System.currentTimeMillis()
        cachedCollectionDao.deleteByProfile(profile.id)
        cachedCollectionRomDao.deleteByProfile(profile.id)
        cachedCollectionDao.upsertAll(
            collections.map { collection ->
                val absoluteCover = collection.collectionCoverSource(profile.baseUrl)
                CachedCollectionEntity(
                    profileId = profile.id,
                    kind = collection.kind.name,
                    collectionId = collection.id,
                    name = collection.name,
                    description = collection.description,
                    romCount = collection.romCount,
                    pathCoverSmall = collection.pathCoverSmall,
                    pathCoverLarge = collection.pathCoverLarge,
                    pathCoversSmallJson = cacheCodec.encodeStringList(collection.pathCoversSmall),
                    pathCoversLargeJson = cacheCodec.encodeStringList(collection.pathCoversLarge),
                    coverCachedUri = absoluteCover?.let { thumbnailCacheStore.resolveCachedUri(profile.id, it) },
                    isPublic = collection.isPublic,
                    isFavorite = collection.isFavorite,
                    isVirtual = collection.isVirtual,
                    isSmart = collection.isSmart,
                    ownerUsername = collection.ownerUsername,
                    updatedAtEpochMs = now,
                )
            },
        )
        collections.forEach { collection ->
            replaceCollectionMembership(
                profileId = profile.id,
                collection = collection,
                romIds = collection.romIds.toList(),
            )
        }
    }

    private suspend fun replaceCollectionMembership(
        profileId: String,
        collection: RommCollectionDto,
        romIds: List<Int>,
    ) {
        cachedCollectionRomDao.deleteByCollection(profileId, collection.kind.name, collection.id)
        if (romIds.isNotEmpty()) {
            cachedCollectionRomDao.upsertAll(
                romIds.distinct().mapIndexed { index, romId ->
                    CachedCollectionRomEntity(
                        profileId = profileId,
                        kind = collection.kind.name,
                        collectionId = collection.id,
                        romId = romId,
                        position = index,
                    )
                },
            )
        }
    }

    private suspend fun cacheRomFeed(profileId: String, feedType: OfflineHomeFeedType, roms: List<RomDto>) {
        cachedHomeEntryDao.deleteByFeed(profileId, feedType.name)
        if (roms.isEmpty()) return
        cachedHomeEntryDao.upsertAll(
            roms.distinctBy { it.id }.mapIndexed { index, rom ->
                CachedHomeEntryEntity(
                    profileId = profileId,
                    feedType = feedType.name,
                    position = index,
                    romId = rom.id,
                )
            },
        )
    }

    private suspend fun cacheCollectionFeed(profileId: String, collections: List<RommCollectionDto>) {
        cachedHomeEntryDao.deleteByFeed(profileId, OfflineHomeFeedType.FEATURED_COLLECTION.name)
        if (collections.isEmpty()) return
        cachedHomeEntryDao.upsertAll(
            collections.mapIndexed { index, collection ->
                CachedHomeEntryEntity(
                    profileId = profileId,
                    feedType = OfflineHomeFeedType.FEATURED_COLLECTION.name,
                    position = index,
                    collectionKind = collection.kind.name,
                    collectionId = collection.id,
                )
            },
        )
    }

    private suspend fun fetchAllRoms(service: RommService): List<RomDto> {
        val pageSize = 100
        val roms = mutableListOf<RomDto>()
        var offset = 0
        var total = Int.MAX_VALUE
        while (offset < total) {
            val response = service.getRoms(limit = pageSize, offset = offset)
            if (response.items.isEmpty()) break
            roms += response.items
            total = response.total ?: (offset + response.items.size)
            offset += response.items.size
            if (response.items.size < pageSize) break
        }
        return roms.distinctBy { it.id }
    }

    private suspend fun fetchCollections(service: RommService): List<RommCollectionDto> {
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

    private suspend fun fetchDetailedRom(service: RommService, romId: Int): RomDto {
        val rom = service.getRomById(romId)
        val siblingFiles = rom.siblings.orEmpty().mapNotNull { sibling ->
            runCatching { service.getRomById(sibling.id).files.firstOrNull() }.getOrNull()
        }
        return rom.copy(files = rom.files + siblingFiles)
    }

    private suspend fun warmMedia(
        profile: ServerProfile,
        platforms: List<PlatformDto>,
        roms: List<RomDto>,
        collections: List<RommCollectionDto>,
        continuePlaying: List<RomDto>,
        recent: List<RomDto>,
    ): Boolean {
        val installedRomIds = downloadedRomDao.observeAll().first().map { it.romId }.toSet()
        val pinnedRomIds = installedRomIds + continuePlaying.map { it.id } + recent.map { it.id }
        val pinnedCollections = collections.take(8).map { it.cacheKey }.toSet()
        var success = true

        platforms.forEach { platform ->
            platform.platformLogoSource(profile.baseUrl)?.let { sourceUrl ->
                val result = cacheMedia(profile, sourceUrl, MediaCacheCategory.PLATFORM_LOGO, pinned = true)
                if (result == null) success = false
            }
        }

        roms.forEach { rom ->
            rom.coverSource(profile.baseUrl)?.let { sourceUrl ->
                val cached = cacheMedia(profile, sourceUrl, MediaCacheCategory.ROM_COVER, rom.id in pinnedRomIds)
                if (cached == null) success = false
            }
        }

        collections.forEach { collection ->
            collection.collectionCoverSource(profile.baseUrl)?.let { sourceUrl ->
                val cached = cacheMedia(
                    profile,
                    sourceUrl,
                    MediaCacheCategory.COLLECTION_COVER,
                    collection.cacheKey in pinnedCollections,
                )
                if (cached == null) success = false
            }
        }

        return success
    }

    private suspend fun warmPlatformMedia(
        profile: ServerProfile,
        platforms: List<PlatformDto>,
    ) {
        platforms.forEach { platform ->
            platform.platformLogoSource(profile.baseUrl)?.let { sourceUrl ->
                cacheMedia(profile, sourceUrl, MediaCacheCategory.PLATFORM_LOGO, pinned = true)
            }
        }
    }

    private suspend fun warmCollectionMedia(
        profile: ServerProfile,
        collections: List<RommCollectionDto>,
    ) {
        collections.forEach { collection ->
            collection.collectionCoverSource(profile.baseUrl)?.let { sourceUrl ->
                cacheMedia(
                    profile = profile,
                    sourceUrl = sourceUrl,
                    category = MediaCacheCategory.COLLECTION_COVER,
                    pinned = collection.isFavorite || collection.isVirtual,
                )
            }
        }
    }

    private suspend fun warmRomMedia(
        profile: ServerProfile,
        roms: List<RomDto>,
        pinnedRomIds: Set<Int>,
    ) {
        roms.forEach { rom ->
            rom.coverSource(profile.baseUrl)?.let { sourceUrl ->
                cacheMedia(
                    profile = profile,
                    sourceUrl = sourceUrl,
                    category = MediaCacheCategory.ROM_COVER,
                    pinned = rom.id in pinnedRomIds,
                )
            }
        }
    }

    private suspend fun cacheMedia(
        profile: ServerProfile,
        sourceUrl: String,
        category: MediaCacheCategory,
        pinned: Boolean,
    ): String? {
        return thumbnailCacheStore.cacheIfNeeded(
            profileId = profile.id,
            sourceUrl = sourceUrl,
            category = category,
            pinned = pinned,
        ) { target ->
            val downloadClient = io.github.mattsays.rommnative.data.network.DownloadClient(authManager)
            downloadClient.downloadToFile(
                profileId = profile.id,
                absoluteUrl = sourceUrl,
                target = target,
                includeOriginAuth = true,
            )
        }
    }

    private suspend fun markRefreshSucceeded(
        profileId: String,
        updateFullSync: Boolean,
        updateMediaSync: Boolean = false,
    ) {
        val current = profileCacheStateDao.getByProfile(profileId) ?: ProfileCacheStateEntity(profileId = profileId)
        val now = System.currentTimeMillis()
        profileCacheStateDao.upsert(
            current.copy(
                lastFullSyncAtEpochMs = if (updateFullSync) now else current.lastFullSyncAtEpochMs,
                lastMediaSyncAtEpochMs = if (updateMediaSync) now else current.lastMediaSyncAtEpochMs,
                catalogReady = current.catalogReady || updateFullSync,
                mediaReady = current.mediaReady || updateMediaSync,
                isRefreshing = false,
                lastError = null,
            ),
        )
    }

    private suspend fun runTargetedRefresh(
        profile: ServerProfile,
        scopeKey: String,
        block: suspend () -> Unit,
    ) {
        ensureProfileCacheState(profile.id)
        val current = profileCacheStateDao.getByProfile(profile.id) ?: ProfileCacheStateEntity(profileId = profile.id)
        profileCacheStateDao.upsert(
            current.copy(
                isRefreshing = true,
                lastError = null,
            ),
        )
        runScopedRefresh(scopeKey) {
            runCatching { block() }.onFailure { error ->
                val latest = profileCacheStateDao.getByProfile(profile.id) ?: current
                profileCacheStateDao.upsert(
                    latest.copy(
                        isRefreshing = false,
                        lastError = error.message ?: "Refresh failed.",
                    ),
                )
                throw error
            }
        }
    }

    private suspend fun runScopedRefresh(
        scopeKey: String,
        block: suspend () -> Unit,
    ) {
        val existing = refreshMutex.withLock { inFlightRefreshes[scopeKey] }
        if (existing != null) {
            existing.await().getOrThrow()
            return
        }

        val gate = CompletableDeferred<Result<Unit>>()
        val isLeader = refreshMutex.withLock {
            val alreadyRunning = inFlightRefreshes[scopeKey]
            if (alreadyRunning == null) {
                inFlightRefreshes[scopeKey] = gate
                true
            } else {
                false
            }
        }
        if (!isLeader) {
            refreshMutex.withLock { inFlightRefreshes[scopeKey] }?.await()?.getOrThrow()
            return
        }

        val result = runCatching { block() }
        gate.complete(result)
        refreshMutex.withLock {
            inFlightRefreshes.remove(scopeKey)
        }
        result.getOrThrow()
    }

    private suspend fun enqueuePendingAction(
        profileId: String,
        actionType: PendingRemoteActionType,
        dedupeKey: String,
        payloadJson: String,
    ) {
        val now = System.currentTimeMillis()
        pendingRemoteActionDao.upsert(
            PendingRemoteActionEntity(
                profileId = profileId,
                actionType = actionType.name,
                dedupeKey = dedupeKey,
                payloadJson = payloadJson,
                status = PendingRemoteActionStatus.PENDING.name,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
                lastError = null,
            ),
        )
        scheduleOfflineSync(force = false)
    }

    private suspend fun drainGameSyncAction(profile: ServerProfile, action: PendingRemoteActionEntity) {
        val payload = moshiGameSyncPayload(action.payloadJson)
        val installation = downloadedRomDao.getByIds(payload.romId, payload.fileId)
            ?: error("Installed file missing for queued sync.")
        val rom = getRomById(payload.romId)
        cacheRoms(profile, listOf(rom))
        syncGame(installation, rom)
    }

    private suspend fun drainCoreDownloadAction(action: PendingRemoteActionEntity) {
        val payload = moshiCorePayload(action.payloadJson)
        val runtime = coreResolver.supportedPlatforms()
            .flatMap { it.runtimeOptions }
            .firstOrNull { it.runtimeId == payload.runtimeId }
            ?: error("Core runtime ${payload.runtimeId} is no longer supported.")
        coreInstaller.installCore(runtime)
    }

    private fun scheduleOfflineSync(force: Boolean) {
        workManager.enqueueUniqueWork(
            OfflineSyncWorker.WORK_NAME,
            if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<OfflineSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build(),
        )
    }

    private fun moshiGameSyncPayload(payload: PendingGameSyncPayload): String {
        return com.squareup.moshi.Moshi.Builder().build()
            .adapter(PendingGameSyncPayload::class.java)
            .toJson(payload)
    }

    private fun moshiGameSyncPayload(raw: String): PendingGameSyncPayload {
        return com.squareup.moshi.Moshi.Builder().build()
            .adapter(PendingGameSyncPayload::class.java)
            .fromJson(raw)
            ?: error("Invalid queued sync payload.")
    }

    private fun moshiCorePayload(payload: PendingCoreDownloadPayload): String {
        return com.squareup.moshi.Moshi.Builder().build()
            .adapter(PendingCoreDownloadPayload::class.java)
            .toJson(payload)
    }

    private fun moshiCorePayload(raw: String): PendingCoreDownloadPayload {
        return com.squareup.moshi.Moshi.Builder().build()
            .adapter(PendingCoreDownloadPayload::class.java)
            .fromJson(raw)
            ?: error("Invalid queued core payload.")
    }
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

private fun DownloadedRomEntity.toFallbackRom(): RomDto {
    return RomDto(
        id = romId,
        name = romName,
        platformName = platformSlug.replace('-', ' ').replaceFirstChar { it.uppercase() },
        platformSlug = platformSlug,
        fsName = fileName.substringBeforeLast('.'),
        urlCover = null,
    )
}

private fun PlatformDto.platformLogoSource(baseUrl: String): String? {
    return listOf(slug, fsSlug)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .firstNotNullOfOrNull { resolveRemoteAssetUrl(baseUrl, "/assets/platforms/$it.svg") }
        ?: resolveRemoteAssetUrl(baseUrl, urlLogo)
}

private fun RomDto.coverSource(baseUrl: String): String? {
    return resolveRemoteAssetUrl(baseUrl, urlCover)
}

private fun RommCollectionDto.collectionCoverSource(baseUrl: String): String? {
    return listOfNotNull(
        pathCoverLarge,
        pathCoversLarge.firstOrNull(),
        pathCoverSmall,
        pathCoversSmall.firstOrNull(),
    ).firstNotNullOfOrNull { resolveRemoteAssetUrl(baseUrl, it) }
}

private val RommCollectionDto.cacheKey: String
    get() = "${kind.name}:${id}"

private fun CachedPlatformEntity.toDomain(): PlatformDto {
    return PlatformDto(
        id = platformId,
        slug = slug,
        name = name,
        fsSlug = fsSlug,
        urlLogo = usableCachedUri(logoCachedUri) ?: urlLogo,
        romCount = romCount,
    )
}

private fun CachedRomEntity.toDomain(codec: OfflineCacheJsonCodec): RomDto {
    return RomDto(
        id = romId,
        name = name,
        summary = summary,
        platformId = platformId,
        platformName = platformName,
        platformSlug = platformSlug,
        fsName = fsName,
        files = codec.decodeFiles(filesJson),
        siblings = codec.decodeSiblings(siblingsJson),
        urlCover = usableCachedUri(coverCachedUri) ?: urlCover,
    )
}

private fun CachedCollectionEntity.toDomain(codec: OfflineCacheJsonCodec): RommCollectionDto {
    val cachedCover = usableCachedUri(coverCachedUri)
    return RommCollectionDto(
        kind = runCatching { CollectionKind.valueOf(kind) }.getOrDefault(CollectionKind.REGULAR),
        id = collectionId,
        name = name,
        description = description,
        romCount = romCount,
        pathCoverSmall = cachedCover ?: pathCoverSmall,
        pathCoverLarge = cachedCover ?: pathCoverLarge,
        pathCoversSmall = codec.decodeStringList(pathCoversSmallJson),
        pathCoversLarge = codec.decodeStringList(pathCoversLargeJson),
        isPublic = isPublic,
        isFavorite = isFavorite,
        isVirtual = isVirtual,
        isSmart = isSmart,
        ownerUsername = ownerUsername,
    )
}

private fun usableCachedUri(rawUri: String?): String? {
    if (rawUri.isNullOrBlank()) return null
    if (!rawUri.startsWith("file:")) return rawUri
    return runCatching {
        val file = File(URI(rawUri))
        Uri.fromFile(file).toString().takeIf { file.exists() && file.length() > 0L && isDecodableCachedImage(file) }
    }.getOrNull()
}

private fun isDecodableCachedImage(file: File): Boolean {
    if (file.extension.equals("svg", ignoreCase = true)) {
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
