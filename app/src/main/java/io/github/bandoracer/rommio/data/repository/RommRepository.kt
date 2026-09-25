package io.github.bandoracer.rommio.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import io.github.bandoracer.rommio.domain.storage.StorageAccess

import io.github.bandoracer.rommio.model.InstallIdentity
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import io.github.bandoracer.rommio.data.auth.AuthManager
import io.github.bandoracer.rommio.data.cache.ThumbnailCacheStore
import io.github.bandoracer.rommio.data.migration.MigrationBundleManager
import io.github.bandoracer.rommio.data.local.CachedCollectionDao
import io.github.bandoracer.rommio.data.local.CachedCollectionEntity
import io.github.bandoracer.rommio.data.local.CachedCollectionRomDao
import io.github.bandoracer.rommio.data.local.CachedCollectionRomEntity
import io.github.bandoracer.rommio.data.local.CachedHomeEntryDao
import io.github.bandoracer.rommio.data.local.CachedHomeEntryEntity
import io.github.bandoracer.rommio.data.local.CachedPlatformDao
import io.github.bandoracer.rommio.data.local.CachedPlatformEntity
import io.github.bandoracer.rommio.data.local.CachedRomDao
import io.github.bandoracer.rommio.data.local.CachedRomEntity
import io.github.bandoracer.rommio.data.local.DownloadRecordDao
import io.github.bandoracer.rommio.data.local.DownloadedRomDao
import io.github.bandoracer.rommio.data.local.GameSyncJournalDao
import io.github.bandoracer.rommio.data.local.GameSyncJournalEntity
import io.github.bandoracer.rommio.data.local.PendingRemoteActionDao
import io.github.bandoracer.rommio.data.local.PendingRemoteActionEntity
import io.github.bandoracer.rommio.data.local.ProfileCacheStateDao
import io.github.bandoracer.rommio.data.local.ProfileCacheStateEntity
import io.github.bandoracer.rommio.data.local.RecoveryStateDao
import io.github.bandoracer.rommio.data.local.RecoveryStateEntity
import io.github.bandoracer.rommio.data.local.SaveStateDao
import io.github.bandoracer.rommio.data.local.SaveStateSyncJournalEntity
import io.github.bandoracer.rommio.data.local.SaveStateSyncJournalDao
import io.github.bandoracer.rommio.data.local.toBrowsableGameState
import io.github.bandoracer.rommio.data.network.ConnectivityMonitor
import io.github.bandoracer.rommio.data.network.RommService
import io.github.bandoracer.rommio.data.network.RommServiceFactory
import io.github.bandoracer.rommio.data.work.DownloadRomWorker
import io.github.bandoracer.rommio.data.work.OfflineSyncWorker
import io.github.bandoracer.rommio.domain.player.CoreInstaller
import io.github.bandoracer.rommio.domain.player.CoreResolution
import io.github.bandoracer.rommio.domain.player.CoreResolver
import io.github.bandoracer.rommio.domain.player.EmbeddedSupportTier
import io.github.bandoracer.rommio.domain.player.PlayerCapability
import io.github.bandoracer.rommio.domain.player.PlayerSession
import io.github.bandoracer.rommio.domain.player.RuntimeProfile
import io.github.bandoracer.rommio.domain.storage.LibraryStore
import io.github.bandoracer.rommio.domain.sync.SyncBridge
import io.github.bandoracer.rommio.model.AuthDiscoveryResult
import io.github.bandoracer.rommio.model.AuthStatus
import io.github.bandoracer.rommio.model.CloudflareServiceCredentials
import io.github.bandoracer.rommio.model.CollectionKind
import io.github.bandoracer.rommio.model.ConnectivityState
import io.github.bandoracer.rommio.model.DirectLoginCredentials
import io.github.bandoracer.rommio.model.DownloadRecord
import io.github.bandoracer.rommio.model.DownloadRecordEntity
import io.github.bandoracer.rommio.model.DownloadStatus
import io.github.bandoracer.rommio.model.DownloadedRomEntity
import io.github.bandoracer.rommio.model.EdgeAuthMode
import io.github.bandoracer.rommio.model.BrowsableGameState
import io.github.bandoracer.rommio.model.BrowsableGameStateOrigin
import io.github.bandoracer.rommio.model.BrowsableGameStateKind
import io.github.bandoracer.rommio.model.GameStateDeletePolicy
import io.github.bandoracer.rommio.model.GameStateRecovery
import io.github.bandoracer.rommio.model.GameSyncPresentation
import io.github.bandoracer.rommio.model.GameSyncStatusKind
import io.github.bandoracer.rommio.model.InstalledPlatformSummary
import io.github.bandoracer.rommio.model.InteractiveSessionConfig
import io.github.bandoracer.rommio.model.InteractiveSessionProvider
import io.github.bandoracer.rommio.model.LibraryStorageSummary
import io.github.bandoracer.rommio.model.MediaCacheCategory
import io.github.bandoracer.rommio.model.MigrationBundleInspection
import io.github.bandoracer.rommio.model.MigrationBundleManifest
import io.github.bandoracer.rommio.model.MigrationImportSummary
import io.github.bandoracer.rommio.model.OfflineHomeFeedType
import io.github.bandoracer.rommio.model.OfflineState
import io.github.bandoracer.rommio.model.OriginAuthMode
import io.github.bandoracer.rommio.model.PendingContinuitySyncPayload
import io.github.bandoracer.rommio.model.PendingCoreDownloadPayload
import io.github.bandoracer.rommio.model.PendingManualSlotPayload
import io.github.bandoracer.rommio.model.PendingRemoteActionStatus
import io.github.bandoracer.rommio.model.PendingRemoteActionType
import io.github.bandoracer.rommio.model.PlatformDto
import io.github.bandoracer.rommio.model.PlayerLaunchPreparation
import io.github.bandoracer.rommio.model.PlayerLaunchTarget
import io.github.bandoracer.rommio.model.PlayerLaunchTargetKind
import io.github.bandoracer.rommio.model.ResumeStateSourceOrigin
import io.github.bandoracer.rommio.model.ResumeStateStatusKind
import io.github.bandoracer.rommio.model.ResumeStateSummary
import io.github.bandoracer.rommio.model.RomDto
import io.github.bandoracer.rommio.model.RomFileDto
import io.github.bandoracer.rommio.model.RommCollectionDto
import io.github.bandoracer.rommio.model.SaveStateEntity
import io.github.bandoracer.rommio.model.ServerAccessResult
import io.github.bandoracer.rommio.model.ServerAccessStatus
import io.github.bandoracer.rommio.model.ServerProfile
import io.github.bandoracer.rommio.model.SyncSummary
import io.github.bandoracer.rommio.model.toDomain
import io.github.bandoracer.rommio.model.toModel
import io.github.bandoracer.rommio.util.buildRomContentPath
import io.github.bandoracer.rommio.util.resolveRemoteAssetUrl
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
import retrofit2.HttpException

data class CachedHomeSnapshot(
    val continuePlaying: List<RomDto> = emptyList(),
    val recentRoms: List<RomDto> = emptyList(),
    val featuredCollections: List<RommCollectionDto> = emptyList(),
    val collectionPreviewCoverUrls: Map<String, List<String>> = emptyMap(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class RommRepository(
    private val context: Context,
    private val database: io.github.bandoracer.rommio.data.local.AppDatabase,
    private val authManager: AuthManager,
    private val serviceFactory: RommServiceFactory,
    private val downloadedRomDao: DownloadedRomDao,
    private val downloadRecordDao: DownloadRecordDao,
    private val ownedDownloads: OwnedDownloadQueue,
    private val saveStateDao: SaveStateDao,
    private val cachedPlatformDao: CachedPlatformDao,
    private val cachedRomDao: CachedRomDao,
    private val cachedCollectionDao: CachedCollectionDao,
    private val cachedCollectionRomDao: CachedCollectionRomDao,
    private val cachedHomeEntryDao: CachedHomeEntryDao,
    private val profileCacheStateDao: ProfileCacheStateDao,
    private val pendingRemoteActionDao: PendingRemoteActionDao,
    private val gameSyncJournalDao: GameSyncJournalDao,
    private val saveStateSyncJournalDao: SaveStateSyncJournalDao,
    private val recoveryStateDao: RecoveryStateDao,
    private val libraryStore: LibraryStore,
    private val coreResolver: CoreResolver,
    private val coreInstaller: CoreInstaller,
    private val syncBridge: SyncBridge,
    private val connectivityMonitor: ConnectivityMonitor,
    private val thumbnailCacheStore: ThumbnailCacheStore,
    private val migrationBundleManager: MigrationBundleManager,
    private val firmwareStore: io.github.bandoracer.rommio.domain.player.FirmwareStore = io.github.bandoracer.rommio.domain.player.FirmwareStore(libraryStore),
) {
    private val catalogCache = CatalogCache(database, thumbnailCacheStore::resolveCachedUri)
    private val catalogLanes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private val workManager = WorkManager.getInstance(context)
    private val cacheCodec = OfflineCacheJsonCodec()
    private val pendingActionCodec = PendingActionJsonCodec()
    private val refreshMutex = Mutex()
    private val inFlightRefreshes = mutableMapOf<String, CompletableDeferred<Result<Unit>>>()
    private val pendingPlayerLaunchTargets = mutableMapOf<String, PlayerLaunchTarget>()
    private val playerLaunchTargetMutex = Mutex()

    fun activeProfileFlow(): Flow<ServerProfile?> = authManager.activeProfileFlow()

    fun observeRuntimeAssets(): Flow<Long> = firmwareStore.changes

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
                cachedContentAvailable = state.activeProfileId?.let { profileId ->
                    cachedRomDao.listAll(profileId).isNotEmpty() || downloadedRomDao.listAll().any { it.ownerProfileId == profileId || it.ownerProfileId == null }
                } == true,
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

    suspend fun initializeAuth() = StorageAccess.normal {
        kickDownloadQueue()
        if (connectivityMonitor.currentState() == ConnectivityState.ONLINE) {
            authManager.initializeForAppLaunch()
        }
        scheduleOfflineSync(force = false)
    }

    suspend fun currentProfile(): ServerProfile? = StorageAccess.normal { authManager.getActiveProfile() }

    fun currentConnectivityState(): ConnectivityState = connectivityMonitor.currentState()

    suspend fun discoverServer(baseUrl: String): AuthDiscoveryResult = StorageAccess.normal {
        authManager.discoverServer(baseUrl)
    }

    suspend fun configureServerProfile(
        baseUrl: String,
        label: String? = null,
        edgeAuthMode: EdgeAuthMode? = null,
        originAuthMode: OriginAuthMode? = null,
        discoveryResult: AuthDiscoveryResult? = null,
        makeActive: Boolean = true,
    ): ServerProfile = StorageAccess.normal {
        val profile = authManager.configureServerProfile(
            baseUrl = baseUrl,
            label = label,
            edgeAuthMode = edgeAuthMode,
            originAuthMode = originAuthMode,
            discoveryResult = discoveryResult,
            makeActive = makeActive,
        )
        ensureProfileCacheState(profile.id)
        return@normal profile
    }

    suspend fun setCloudflareServiceCredentials(profileId: String, credentials: CloudflareServiceCredentials) = StorageAccess.normal {
        authManager.setCloudflareServiceCredentials(profileId, credentials)
    }

    fun hasStoredCloudflareServiceCredentials(profileId: String): Boolean {
        return authManager.hasCloudflareServiceCredentials(profileId)
    }

    suspend fun beginEdgeAccess(profileId: String) = StorageAccess.normal {
        authManager.beginEdgeAccess(profileId)
    }

    suspend fun completeEdgeAccessAttempt(profileId: String) = StorageAccess.normal { authManager.completeEdgeAccessAttempt(profileId) }

    suspend fun getInteractiveSessionConfig(
        profileId: String,
        provider: InteractiveSessionProvider,
    ): InteractiveSessionConfig = StorageAccess.normal {
        authManager.getInteractiveSessionConfig(profileId, provider)
    }

    suspend fun completeInteractiveLogin(
        profileId: String,
        provider: InteractiveSessionProvider,
    ): AuthStatus = StorageAccess.normal {
        val status = authManager.completeInteractiveLogin(profileId, provider)
        if (status == AuthStatus.CONNECTED) {
            kickDownloadQueue()
            scheduleOfflineSync(force = true)
        }
        return@normal status
    }

    suspend fun testServerAccess(profileId: String? = null): ServerAccessResult = StorageAccess.normal {
        return@normal authManager.testServerAccess(profileId)
    }

    suspend fun loginWithDirectCredentials(profileId: String, credentials: DirectLoginCredentials) = StorageAccess.normal {
        authManager.loginWithDirectCredentials(profileId, credentials)
        kickDownloadQueue()
        scheduleOfflineSync(force = true)
    }

    suspend fun validateProfile(profileId: String? = null): AuthStatus = StorageAccess.normal {
        val status = authManager.validateProfile(profileId)
        if (status == AuthStatus.CONNECTED) {
            kickDownloadQueue()
            scheduleOfflineSync(force = true)
        }
        return@normal status
    }

    suspend fun logout(clearServerAccess: Boolean = false) = StorageAccess.normal {
        authManager.getActiveProfile()?.let {
            workManager.cancelUniqueWork(DownloadRomWorker.QUEUE_WORK_NAME).await()
            workManager.cancelUniqueWork(OfflineSyncWorker.WORK_NAME)
        }
        authManager.logout(clearServerAccess = clearServerAccess)
        kickDownloadQueue()
    }

    suspend fun clearServerAccess(profileId: String) = StorageAccess.normal {
        authManager.clearServerAccess(profileId)
    }

    suspend fun listProfiles(): List<ServerProfile> = StorageAccess.normal {
        return@normal authManager.listProfiles().sortedByDescending { it.updatedAt }
    }

    suspend fun activateProfile(profileId: String): ServerProfile = StorageAccess.normal {
        val profile = authManager.setActiveProfile(profileId)
        ensureProfileCacheState(profile.id)
        scheduleOfflineSync(force = true)
        return@normal profile
    }

    suspend fun deleteProfile(profileId: String): ServerProfile? = StorageAccess.normal {
        catalogLanes.getOrPut(profileId) { Mutex() }.withLock {
            val wasActive = authManager.getActiveProfile()?.id == profileId
            pendingRemoteActionDao.deleteByProfile(profileId)
            cachedHomeEntryDao.deleteByProfile(profileId)
            cachedCollectionRomDao.deleteByProfile(profileId)
            cachedCollectionDao.deleteByProfile(profileId)
            cachedRomDao.deleteByProfile(profileId)
            cachedPlatformDao.deleteByProfile(profileId)
            profileCacheStateDao.deleteByProfile(profileId)
            gameSyncJournalDao.deleteByProfile(profileId)
            saveStateSyncJournalDao.deleteByProfile(profileId)
            thumbnailCacheStore.clearProfile(profileId)
            val nextProfile = authManager.deleteProfile(profileId)
            if (wasActive) {
                workManager.cancelUniqueWork(DownloadRomWorker.QUEUE_WORK_NAME).await()
                workManager.cancelUniqueWork(OfflineSyncWorker.WORK_NAME)
                nextProfile?.let {
                    ensureProfileCacheState(it.id)
                    scheduleOfflineSync(force = true)
                }
            }
            return@normal nextProfile
        }
    }

    suspend fun getCurrentUser() = StorageAccess.normal { if (isOnline()) {
        createService().getCurrentUser()
    } else {
        error("Offline. Cached library data is still available.")
    } }

    suspend fun refreshPlatformsInBackground() = StorageAccess.normal {
        val profile = requireActiveProfile()
        catalogLanes.getOrPut(profile.id) { Mutex() }.withLock {
            if (!isOnline()) return@normal
            runTargetedRefresh(profile, "${profile.id}:platforms") {
                val service = createService(profile)
                val platforms = service.getPlatforms()
                cachePlatforms(profile, platforms)
                warmPlatformMedia(profile, platforms)
                catalogCache.finishTargetedRefresh(profile.id)
            }
        }
    }

    suspend fun refreshCollectionsInBackground() = StorageAccess.normal {
        val profile = requireActiveProfile()
        catalogLanes.getOrPut(profile.id) { Mutex() }.withLock {
            if (!isOnline()) return@normal
            runTargetedRefresh(profile, "${profile.id}:collections") {
                val service = createService(profile)
                val collections = fetchCollections(service)
                cacheCollections(profile, collections)
                cacheCollectionFeed(profile.id, collections.take(8))
                warmCollectionMedia(profile, collections)
                catalogCache.finishTargetedRefresh(profile.id)
            }
        }
    }

    suspend fun refreshPlatformInBackground(platformId: Int) = StorageAccess.normal {
        val profile = requireActiveProfile()
        catalogLanes.getOrPut(profile.id) { Mutex() }.withLock {
            if (!isOnline()) return@normal
            runTargetedRefresh(profile, "${profile.id}:platform:$platformId") {
                val service = createService(profile)
                val roms = CatalogReader(service).roms(platformId = platformId)
                cacheRoms(profile, roms, filesIncluded = true)
                warmRomMedia(profile, roms, pinnedRomIds = emptySet())
                catalogCache.finishTargetedRefresh(profile.id)
            }
        }
    }

    suspend fun refreshCollectionInBackground(kind: CollectionKind, id: String) = StorageAccess.normal {
        val profile = requireActiveProfile()
        catalogLanes.getOrPut(profile.id) { Mutex() }.withLock {
            if (!isOnline()) return@normal
            runTargetedRefresh(profile, "${profile.id}:collection:${kind.name}:$id") {
                val service = createService(profile)
                val collections = fetchCollections(service)
                val collection = collections.firstOrNull { it.kind == kind && it.id == id }
                    ?: error("This collection is no longer available.")
                val roms = CatalogReader(service).roms(collection = collection)
                check(roms.map { it.id }.toSet() == collection.romIds) { "Collection changed during refresh; retry." }
                database.withTransaction {
                    cacheCollections(profile, collections)
                    cacheCollectionFeed(profile.id, collections.take(8))
                    cacheRoms(profile, roms, filesIncluded = true)
                    replaceCollectionMembership(profile.id, collection, roms.map { it.id })
                }
                warmCollectionMedia(profile, listOf(collection))
                warmRomMedia(profile, roms, pinnedRomIds = roms.map { it.id }.toSet())
                catalogCache.finishTargetedRefresh(profile.id)
            }
        }
    }

    suspend fun refreshRomInBackground(romId: Int) = StorageAccess.normal {
        val profile = requireActiveProfile()
        catalogLanes.getOrPut(profile.id) { Mutex() }.withLock {
            if (!isOnline()) return@normal
            runTargetedRefresh(profile, "${profile.id}:rom:$romId") {
                val service = createService(profile)
                val rom = fetchDetailedRom(service, romId)
                cacheRoms(profile, listOf(rom), detailed = true)
                warmRomMedia(profile, listOf(rom), pinnedRomIds = setOf(romId))
                catalogCache.finishTargetedRefresh(profile.id)
            }
        }
    }

    suspend fun getPlatforms(): List<PlatformDto> = StorageAccess.normal {
        val profile = requireActiveProfile()
        catalogLanes.getOrPut(profile.id) { Mutex() }.withLock {
            if (!isOnline()) {
                return@normal getCachedPlatforms(profile.id).ifEmpty {
                    if (profileCacheStateDao.getByProfile(profile.id)?.catalogReady == true) return@normal emptyList()
                    error("Offline. This profile has not been hydrated yet.")
                }
            }
            return@normal runCatching {
                createService(profile).getPlatforms().also { platforms ->
                    cachePlatforms(profile, platforms)
                }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                getCachedPlatforms(profile.id).ifEmpty { throw error }
            }
        }
    }

    suspend fun getRecentlyAdded(): List<RomDto> = StorageAccess.normal {
        val profile = requireActiveProfile()
        catalogLanes.getOrPut(profile.id) { Mutex() }.withLock {
            if (!isOnline()) {
                return@normal cachedHomeRoms(profile.id, OfflineHomeFeedType.RECENTLY_ADDED).ifEmpty {
                    if (profileCacheStateDao.getByProfile(profile.id)?.catalogReady == true) return@normal emptyList()
                    error("Offline. Recently added titles are unavailable until this profile finishes syncing.")
                }
            }
            return@normal runCatching {
                createService(profile).getRecentlyAdded().items.also { roms ->
                    cacheRoms(profile, roms)
                    cacheRomFeed(profile.id, OfflineHomeFeedType.RECENTLY_ADDED, roms)
                }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                cachedHomeRoms(profile.id, OfflineHomeFeedType.RECENTLY_ADDED).ifEmpty { throw error }
            }
        }
    }

    suspend fun getContinuePlaying(limit: Int = 12): List<RomDto> = StorageAccess.normal {
        val profile = requireActiveProfile()
        catalogLanes.getOrPut(profile.id) { Mutex() }.withLock {
            if (!isOnline()) {
                return@normal cachedHomeRoms(profile.id, OfflineHomeFeedType.CONTINUE_PLAYING).take(limit).ifEmpty {
                    if (profileCacheStateDao.getByProfile(profile.id)?.catalogReady == true) return@normal emptyList()
                    error("Offline. Continue playing is unavailable until this profile finishes syncing.")
                }
            }
            return@normal runCatching {
                createService(profile)
                    .getRoms(
                        lastPlayed = true,
                        limit = limit,
                        orderBy = "last_played",
                        orderDir = "desc",
                    )
                    .items
                    .also { roms ->
                        cacheRoms(profile, roms)
                        cacheRomFeed(profile.id, OfflineHomeFeedType.CONTINUE_PLAYING, roms)
                    }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                cachedHomeRoms(profile.id, OfflineHomeFeedType.CONTINUE_PLAYING).take(limit).ifEmpty { throw error }
            }
        }
    }

    suspend fun getRomsByPlatform(platformId: Int): List<RomDto> = StorageAccess.normal {
        val profile = requireActiveProfile()
        catalogLanes.getOrPut(profile.id) { Mutex() }.withLock {
            if (!isOnline()) {
                return@normal cachedRomDao.listByPlatform(profile.id, platformId).map { it.toDomain(cacheCodec) }.ifEmpty {
                    if (profileCacheStateDao.getByProfile(profile.id)?.catalogReady == true) return@normal emptyList()
                    error("Offline. This platform has not been hydrated yet.")
                }
            }
            return@normal runCatching {
                CatalogReader(createService(profile)).roms(platformId = platformId)
                    .also { cacheRoms(profile, it, filesIncluded = true) }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                cachedRomDao.listByPlatform(profile.id, platformId).map { it.toDomain(cacheCodec) }.ifEmpty { throw error }
            }
        }
    }

    suspend fun getRomById(romId: Int): RomDto = StorageAccess.normal {
        val profile = requireActiveProfile()
        catalogLanes.getOrPut(profile.id) { Mutex() }.withLock {
            if (!isOnline()) {
                return@normal cachedRomDao.getById(profile.id, romId)?.toDomain(cacheCodec)
                    ?: error("Offline. This title is unavailable until the active profile is synced.")
            }

            return@normal runCatching {
                val service = createService(profile)
                fetchDetailedRom(service, romId).also { detailedRom ->
                    cacheRoms(profile, listOf(detailedRom), detailed = true)
                }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                cachedRomDao.getById(profile.id, romId)?.toDomain(cacheCodec) ?: throw error
            }
        }
    }

    suspend fun getCollections(): List<RommCollectionDto> = StorageAccess.normal {
        val profile = requireActiveProfile()
        catalogLanes.getOrPut(profile.id) { Mutex() }.withLock {
            if (!isOnline()) {
                return@normal cachedCollectionDao.listAll(profile.id).map { it.toDomain(cacheCodec) }.ifEmpty {
                    if (profileCacheStateDao.getByProfile(profile.id)?.catalogReady == true) return@normal emptyList()
                    error("Offline. Collections are unavailable until this profile finishes syncing.")
                }
            }
            return@normal runCatching {
                val service = createService(profile)
                val collections = fetchCollections(service)
                cacheCollections(profile, collections)
                collections
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                cachedCollectionDao.listAll(profile.id).map { it.toDomain(cacheCodec) }.ifEmpty { throw error }
            }
        }
    }

    fun observeInstalledFiles(romId: Int): Flow<List<DownloadedRomEntity>> {
        return activeProfileFlow().flatMapLatest { profile ->
            downloadedRomDao.observeByRomId(romId).map { rows -> visibleInstalls(rows, profile?.id) }
        }
    }

    fun observeInstalledLibrary(): Flow<List<DownloadedRomEntity>> {
        return activeProfileFlow().flatMapLatest { profile ->
            downloadedRomDao.observeAll().map { rows -> visibleInstalls(rows, profile?.id) }
        }
    }

    fun observeInstalledPlatformSummaries(): Flow<List<InstalledPlatformSummary>> {
        return observeInstalledLibrary().map(::summarizeInstalledPlatforms)
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
                        .filter { it.ownerProfileId == null || it.ownerProfileId == profile.id }
                        .map { install ->
                            romMap[install.romId]?.toDomain(cacheCodec) ?: install.toFallbackRom()
                        }
                        .distinctBy { it.id }
                        .take(limit)
                }
            }
        }
    }







































































    /** Every cached game for the active profile, for offline search and name lookups. */
    fun observeCachedRoms(): Flow<List<RomDto>> {
        return activeProfileFlow().flatMapLatest { profile ->
            if (profile == null) {
                flowOf(emptyList())
            } else {
                cachedRomDao.observeAll(profile.id).map { roms -> roms.map { it.toDomain(cacheCodec) } }
            }
        }
    }

    fun observeLibraryStorageSummary(): Flow<LibraryStorageSummary> {
        return observeInstalledLibrary().map(::summarizeInstalledLibrary)
    }

    fun observeDownloadHistory(): Flow<List<DownloadRecord>> =
        activeProfileFlow().flatMapLatest { profile ->
            combine(downloadRecordDao.observeAll(), downloadedRomDao.observeAll()) { records, installed ->
                records.filter { it.ownerProfileId == null || it.ownerProfileId == profile?.id }
                    .map { entity ->
                        val installedMatch = installed.firstOrNull {
                            it.romId == entity.romId && it.fileId == entity.fileId && it.namespaceKey == entity.namespaceKey
                        }
                        entity.copy(localPath = installedMatch?.localPath ?: entity.localPath).toModel()
                    }.sortedWith(downloadComparator)
            }
        }

    fun observeDownloadRecord(romId: Int, fileId: Int): Flow<DownloadRecord?> =
        activeProfileFlow().flatMapLatest { profile ->
            downloadRecordDao.observeAll().map { rows ->
                rows.filter { it.romId == romId && it.fileId == fileId && (it.ownerProfileId == null || it.ownerProfileId == profile?.id) }
                    .minByOrNull { it.namespaceKey == InstallIdentity.LEGACY }?.toModel()
            }
        }

    fun observeDownloadAttentionCount(): Flow<Int> {
        return observeDownloadHistory().map { records ->
            records.count { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.FAILED }
        }
    }

    fun observeSaveStates(romId: Int, fileId: Int): Flow<List<SaveStateEntity>> =
        observeInstalledFiles(romId).flatMapLatest { installed ->
            val scope = installed.firstOrNull { it.fileId == fileId }?.progressScope ?: InstallIdentity.LEGACY
            saveStateDao.observeByRomId(romId, scope)
        }

    fun observeGameStateRecovery(romId: Int, fileId: Int): Flow<GameStateRecovery> =
        activeProfileFlow().flatMapLatest { profile ->
            observeInstalledFiles(romId).flatMapLatest { installed ->
                val installation = installed.firstOrNull { it.fileId == fileId }
                val scope = installation?.progressScope ?: InstallIdentity.LEGACY
                combine(
                    saveStateDao.observeByRomId(romId, scope),
                    recoveryStateDao.observeByGame(romId, fileId, scope),
                    profile?.let { saveStateSyncJournalDao.observeByGame(it.id, romId, fileId, scope) } ?: flowOf(emptyList()),
                    profile?.let { gameSyncJournalDao.observeByKey(it.id, romId, fileId, scope) } ?: flowOf(null),
                    connectivityMonitor.observe(),
                ) { manual, recovery, journals, journal, connectivity ->
                    buildGameStateRecovery(
                        resumeFile = installation?.let(libraryStore::continuityResumeStateFile),
                        manualStates = manual, manualStateJournals = journals, recoveryStates = recovery,
                        journal = journal, connectivity = connectivity,
                    )
                }
            }
        }

    fun observeGameSyncPresentation(romId: Int, fileId: Int): Flow<GameSyncPresentation> =
        activeProfileFlow().flatMapLatest { profile ->
            observeInstalledFiles(romId).flatMapLatest { installed ->
                val scope = installed.firstOrNull { it.fileId == fileId }?.progressScope ?: InstallIdentity.LEGACY
                if (profile == null) flowOf(GameSyncPresentation())
                else combine(gameSyncJournalDao.observeByKey(profile.id, romId, fileId, scope), connectivityMonitor.observe()) { journal, connectivity ->
                    journal.toPresentation(connectivity)
                }
            }
        }

    private fun visibleInstalls(rows: List<DownloadedRomEntity>, profileId: String?) = rows
        .filter { it.ownerProfileId == null || it.ownerProfileId == profileId }
        .sortedBy { it.namespaceKey == InstallIdentity.LEGACY }
        .distinctBy { it.romId to it.fileId }

    suspend fun exportMigrationBundle(destinationUri: Uri): MigrationBundleManifest {
        return migrationBundleManager.exportMigrationBundle(destinationUri)
    }

    suspend fun inspectMigrationBundle(sourceUri: Uri): MigrationBundleInspection {
        return migrationBundleManager.inspectMigrationBundle(sourceUri)
    }

    suspend fun importMigrationBundle(sourceUri: Uri, replaceExisting: Boolean): MigrationImportSummary {
        return migrationBundleManager.importMigrationBundle(sourceUri, replaceExisting)
    }

    /** Reads the disk (BIOS identification, the installed core): from a coroutine, use [coreSupport]. */
    fun resolveCoreSupport(rom: RomDto, file: RomFileDto?): CoreResolution {
        return coreResolver.resolve(rom.platformSlug, file?.effectiveFileExtension)
    }

    /** [resolveCoreSupport] on the IO dispatcher, so no caller resolves a core on the main thread. */
    suspend fun coreSupport(rom: RomDto, file: RomFileDto?): CoreResolution =
        withContext(Dispatchers.IO) { resolveCoreSupport(rom, file) }

    /** Settings › Cores and BIOS: every platform family's core, resolved on this device (disk reads). */
    suspend fun coreInventory(): List<io.github.bandoracer.rommio.domain.player.CoreInventoryEntry> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            io.github.bandoracer.rommio.domain.player.CoreInventory.resolve(coreResolver)
        }

    fun embeddedPlayerSupport(rom: RomDto): CoreResolution {
        return resolveCoreSupport(rom, rom.files.firstOrNull())
    }

    fun embeddedSupportTier(platform: PlatformDto): EmbeddedSupportTier {
        return coreResolver.platformSupport(platform.slug)?.supportTier
            ?: coreResolver.platformSupport(platform.fsSlug)?.supportTier
            ?: EmbeddedSupportTier.UNSUPPORTED
    }

    /**
     * A game's tier from its platform and first file's type, without reading the disk (lists call
     * this per game; it is the tier [embeddedPlayerSupport] implies, see [CoreResolver.supportTier]).
     */
    fun embeddedSupportTier(rom: RomDto): EmbeddedSupportTier {
        return coreResolver.supportTier(rom.platformSlug, rom.files.firstOrNull()?.effectiveFileExtension)
    }

    fun supportsEmbeddedPlayer(platform: PlatformDto): Boolean {
        return embeddedSupportTier(platform) != EmbeddedSupportTier.UNSUPPORTED
    }

    fun isUnsupportedInApp(rom: RomDto): Boolean {
        return embeddedSupportTier(rom) == EmbeddedSupportTier.UNSUPPORTED
    }

    suspend fun getCollectionPreviewCoverUrls(
        collection: RommCollectionDto,
        limit: Int = 3,
    ): List<String> = StorageAccess.normal {
        val profile = requireActiveProfile()
        return@normal previewCoverUrlsFromCache(profile.id, collection, limit = limit)
    }

    suspend fun installRecommendedCore(rom: RomDto, file: RomFileDto?): CoreResolution = StorageAccess.normal {
        val resolution = coreSupport(rom, file)
        val profile = recommendedRuntimeProfile(resolution)
        coreInstaller.installCore(profile)
        return@normal coreSupport(rom, file)
    }

    suspend fun enqueuePendingSync(installation: DownloadedRomEntity, rom: RomDto) = StorageAccess.normal {
        val profile = requireInstallProfile(installation)
        cacheRoms(profile, listOf(rom))
        val payload = moshiContinuityPayload(PendingContinuitySyncPayload(installation.romId, installation.fileId, installation.progressScope))
        enqueuePendingAction(
            profileId = profile.id,
            actionType = PendingRemoteActionType.CONTINUITY_UPLOAD,
            dedupeKey = "${installation.progressScope}:${installation.romId}:${installation.fileId}",
            payloadJson = payload,
        )
    }

    suspend fun enqueuePendingManualSlotUpload(installation: DownloadedRomEntity, slot: Int) = StorageAccess.normal {
        val profile = requireInstallProfile(installation)
        val payload = moshiManualSlotPayload(PendingManualSlotPayload(installation.romId, installation.fileId, slot, installation.progressScope))
        enqueuePendingAction(
            profileId = profile.id,
            actionType = PendingRemoteActionType.MANUAL_SLOT_UPLOAD,
            dedupeKey = "${installation.progressScope}:${installation.romId}:${installation.fileId}:$slot",
            payloadJson = payload,
        )
    }

    suspend fun enqueuePendingManualSlotDelete(installation: DownloadedRomEntity, slot: Int) = StorageAccess.normal {
        val profile = requireInstallProfile(installation)
        val payload = moshiManualSlotPayload(PendingManualSlotPayload(installation.romId, installation.fileId, slot, installation.progressScope))
        enqueuePendingAction(
            profileId = profile.id,
            actionType = PendingRemoteActionType.MANUAL_SLOT_DELETE,
            dedupeKey = "${installation.progressScope}:${installation.romId}:${installation.fileId}:$slot",
            payloadJson = payload,
        )
    }

    suspend fun enqueuePendingCoreDownload(runtimeProfile: RuntimeProfile) = StorageAccess.normal {
        val profile = requireActiveProfile()
        val payload = moshiCorePayload(PendingCoreDownloadPayload(runtimeProfile.runtimeId))
        enqueuePendingAction(
            profileId = profile.id,
            actionType = PendingRemoteActionType.CORE_DOWNLOAD,
            dedupeKey = runtimeProfile.runtimeId,
            payloadJson = payload,
        )
    }

    suspend fun drainPendingRemoteActions() = StorageAccess.normal {
        if (!isOnline()) return@normal
        val profile = authManager.getActiveProfile() ?: return@normal
        pendingRemoteActionDao.listPendingByProfile(profile.id).forEach { action ->
            if (action.actionType != PendingRemoteActionType.CORE_DOWNLOAD.name) {
                pendingRemoteActionDao.upsert(action.copy(
                    status = PendingRemoteActionStatus.FAILED.name,
                    lastError = io.github.bandoracer.rommio.domain.sync.LEGACY_SYNC_PAUSED_MESSAGE,
                    updatedAtEpochMs = System.currentTimeMillis(),
                ))
                return@forEach
            }
            val running = action.copy(
                status = PendingRemoteActionStatus.RUNNING.name,
                updatedAtEpochMs = System.currentTimeMillis(),
                lastError = null,
            )
            pendingRemoteActionDao.upsert(running)
            runCatching {
                drainCoreDownloadAction(running)
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

    suspend fun refreshActiveProfileCache(force: Boolean = false) = StorageAccess.normal {
        val profile = authManager.getActiveProfile() ?: return@normal
        catalogLanes.getOrPut(profile.id) { Mutex() }.withLock {
            ensureProfileCacheState(profile.id)
            if (!isOnline()) return@normal
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
                        orderBy = "last_played",
                        orderDir = "desc",
                    ).items
                    val collections = fetchCollections(service)
                    val allRoms = fetchAllRoms(service)

                    val catalogSyncedAt = System.currentTimeMillis()
                    catalogCache.commitFull(profile, CatalogSnapshot(platforms, allRoms, collections, continuePlaying, recent), catalogSyncedAt)

                    val mediaReady = warmMedia(profile, platforms, allRoms, collections, continuePlaying, recent)
                    cachePlatforms(profile, platforms)
                    cacheRoms(profile, allRoms, filesIncluded = true)
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
                    markRefreshFailed(profile.id, error)
                    throw error
                }
            }
        }
    }

    suspend fun canOpenOffline(profile: ServerProfile?): Boolean = StorageAccess.normal {
        if (profile == null) return@normal false
        if (profile.serverAccess.status != ServerAccessStatus.READY) return@normal false
        if (profile.status != AuthStatus.CONNECTED && !profile.sessionState.hasOriginSession) return@normal false
        val cacheState = profileCacheStateDao.getByProfile(profile.id)
        return@normal cacheState?.catalogReady == true || cachedRomDao.listAll(profile.id).isNotEmpty() ||
            downloadedRomDao.listAll().any { it.ownerProfileId == profile.id || it.ownerProfileId == null }
    }

    /** Cold inputs for the workspace owner. Legacy progress must be inventoried/migrated explicitly. */
    suspend fun buildProgressPlayerSession(installation: DownloadedRomEntity, rom: RomDto): PlayerSession =
        buildPlayerSession(installation, rom, seedLegacySave = false)

    suspend fun buildPlayerSession(installation: DownloadedRomEntity, rom: RomDto, seedLegacySave: Boolean = true): PlayerSession = StorageAccess.normal { withContext(Dispatchers.IO) {
        if (seedLegacySave) requireLegacyPlayerProgress(database.progressDao(), installation)
        ownedDownloads.verifyInstall(installation)
        val resolution = resolveCoreSupport(
            rom = rom,
            file = rom.files.firstOrNull { it.id == installation.fileId } ?: rom.files.firstOrNull(),
        )
        val profile = resolution.runtimeProfile ?: error(resolution.message ?: "Unsupported platform.")
        check(resolution.capability == PlayerCapability.READY) { resolution.message ?: "Core is not ready." }
        val frozenCore = coreResolver.prepareCore(
            profile, requireNotNull(resolution.coreIdentity) { "Core identity is missing." },
        )
        val coreLibrary = frozenCore.file
        val saveRamFile = libraryStore.saveRamFile(installation)
        val firmware = firmwareStore.prepare(installation, profile)
        check(saveRamFile.parentFile!!.isDirectory || saveRamFile.parentFile!!.mkdirs()) { "Cannot create the game's save directory." }
        val statesDirectory = libraryStore.saveStatesDirectory(installation)
        check(statesDirectory.isDirectory || statesDirectory.mkdirs()) { "Cannot create the game's state directory." }

        PlayerSession(
            romId = installation.romId,
            romTitle = installation.romName,
            romPath = File(installation.localPath),
            coreLibrary = coreLibrary,
            runtimeProfile = profile,
            systemDirectory = firmware.systemDirectory,
            savesDirectory = saveRamFile.parentFile ?: libraryStore.systemDirectory(),
            saveRamFile = saveRamFile,
            saveStatesDirectory = statesDirectory,
            firmwareSha256 = firmware.sha256,
            coreIdentity = frozenCore.identity,
            progressBinding = if (installation.progressScope == InstallIdentity.LEGACY) null else
                io.github.bandoracer.rommio.domain.progress.ProgressBinding(
                    requireNotNull(installation.ownerBaseUrl), requireNotNull(installation.ownerAccountId).toString(),
                    installation.romId.toLong(), listOf(io.github.bandoracer.rommio.domain.progress.ProgressContentFile(
                        installation.fileId.toLong(), 0, requireNotNull(installation.contentSha256), null, null)))
                    .also {
                        io.github.bandoracer.rommio.domain.progress.ProgressProtocol.validateBinding(it)
                        check(io.github.bandoracer.rommio.domain.progress.ProgressProtocol.bindingKey(it) == installation.progressScope) {
                            "Installed content does not match its progress identity."
                        }
                    },
            variables = profile.defaultVariables,
            initialSaveRam = if (seedLegacySave) saveRamFile.takeIf { it.exists() }?.readBytes() else null,
            platformSlug = installation.platformSlug,
        )
    } }

    suspend fun installedFileOrNull(romId: Int, fileId: Int): DownloadedRomEntity? = StorageAccess.normal {
        val profile = authManager.getActiveProfile()
        val account = profile?.let { authManager.verifiedAccount(it.id) }
        val namespace = account?.let { InstallIdentity.namespace(it.baseUrl, it.userId) } ?: InstallIdentity.LEGACY
        return@normal downloadedRomDao.getByIds(romId, fileId, namespace)
            ?: downloadedRomDao.getByIds(romId, fileId, InstallIdentity.LEGACY)
    }

    suspend fun latestInstalledFile(romId: Int): DownloadedRomEntity? = StorageAccess.normal {
        return@normal observeInstalledFiles(romId).first().firstOrNull()
    }

    suspend fun refreshGameStateRecovery(installation: DownloadedRomEntity, rom: RomDto) = StorageAccess.normal {
        val resolution = coreSupport(
            rom = rom,
            file = rom.files.firstOrNull { it.id == installation.fileId } ?: rom.files.firstOrNull(),
        )
        val profile = resolution.runtimeProfile ?: error(resolution.message ?: "Unsupported platform.")
        syncBridge.refreshStateRecovery(installation, rom, profile)
    }

    suspend fun setPendingPlayerLaunchTarget(
        installation: DownloadedRomEntity,
        state: BrowsableGameState,
    ) = StorageAccess.normal {
        require(File(state.localPath).canonicalPath.startsWith(libraryStore.saveStatesDirectory(installation).canonicalPath + File.separator)) { "This state belongs to another installed content generation." }
        if (!File(state.localPath).exists()) {
            error("${state.label} is not available on this device yet.")
        }
        playerLaunchTargetMutex.withLock {
            pendingPlayerLaunchTargets[playerLaunchKey(installation.romId, installation.fileId, installation.progressScope)] = PlayerLaunchTarget(
                kind = state.kind.toLaunchTargetKind(),
                localStatePath = state.localPath,
                stateId = state.id,
                label = state.label,
            )
        }
    }

    suspend fun enqueueDownload(
        rom: RomDto,
        file: RomFileDto,
        replaceExisting: Boolean = false,
        prioritize: Boolean = false,
    ) = StorageAccess.normal {
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
    ) = StorageAccess.normal {
        enqueueDownload(
            rom = rom,
            file = file,
            replaceExisting = replaceExisting,
            prioritize = true,
        )
    }

    suspend fun consumePendingPlayerLaunchTarget(romId: Int, fileId: Int): PlayerLaunchTarget? = StorageAccess.normal {
        return@normal takePendingPlayerLaunchTarget(romId, fileId)
    }

    private suspend fun takePendingPlayerLaunchTarget(romId: Int, fileId: Int): PlayerLaunchTarget? {
        val scope = installedFileOrNull(romId, fileId)?.progressScope ?: InstallIdentity.LEGACY
        return playerLaunchTargetMutex.withLock {
            pendingPlayerLaunchTargets.remove(playerLaunchKey(romId, fileId, scope))
        }
    }

    private suspend fun currentGameSyncPresentation(installation: DownloadedRomEntity): GameSyncPresentation {
        val profile = requireInstallProfile(installation)
        return gameSyncJournalDao.getByKey(profile.id, installation.romId, installation.fileId, installation.progressScope).toPresentation(currentConnectivityState())
    }

    private suspend fun requireInstallProfile(installation: DownloadedRomEntity): ServerProfile =
        if (installation.ownerProfileId == null) requireActiveProfile()
        else authManager.getProfile(installation.ownerProfileId) ?: error("The original account is unavailable. Local progress is retained.")

    suspend fun cancelDownload(record: DownloadRecord) = StorageAccess.normal {
        ownedDownloads.cancel(record)
        if (record.status == DownloadStatus.RUNNING) workManager.cancelUniqueWork(DownloadRomWorker.QUEUE_WORK_NAME).await()
        kickDownloadQueue()
    }

    suspend fun retryDownload(record: DownloadRecord) = StorageAccess.normal {
        ownedDownloads.retry(record)
        kickDownloadQueue()
    }

    private suspend fun queueDownload(
        romId: Int, fileId: Int, romName: String, platformSlug: String, fileName: String,
        fileSizeBytes: Long, replaceExisting: Boolean, prioritize: Boolean,
    ) {
        ownedDownloads.enqueue(romId, fileId, romName, platformSlug, fileName, fileSizeBytes, replaceExisting, prioritize)
        kickDownloadQueue()
    }

    private fun kickDownloadQueue() {
        workManager.enqueueUniqueWork(
            DownloadRomWorker.QUEUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
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

    suspend fun downloadNow(record: DownloadRecord) = StorageAccess.normal {
        ownedDownloads.retry(record, prioritize = true)
        // Cancel after invalidating the selected attempt. Other interrupted jobs recover under fresh attempts.
        workManager.cancelUniqueWork(DownloadRomWorker.QUEUE_WORK_NAME).await()
        kickDownloadQueue()
    }

    suspend fun deleteLocalDownload(record: DownloadRecord) = StorageAccess.normal {
        ownedDownloads.removeInstall(record.romId, record.fileId, record.localPath, record.namespaceKey)
    }

    suspend fun deleteInstalledFile(installation: DownloadedRomEntity) = StorageAccess.normal {
        ownedDownloads.removeInstall(installation.romId, installation.fileId, installation.localPath, installation.namespaceKey)
    }

    suspend fun recordSaveState(installation: DownloadedRomEntity, slot: Int, file: File) = StorageAccess.normal {
        syncBridge.recordManualState(installation, slot, file)
        if (!isOnline()) {
            runCatching { enqueuePendingManualSlotUpload(installation, slot) }
        }
    }

    suspend fun deleteSaveState(installation: DownloadedRomEntity, slot: Int) = StorageAccess.normal {
        syncBridge.markManualStateDeleted(installation, slot)
        if (!isOnline()) {
            runCatching { enqueuePendingManualSlotDelete(installation, slot) }
        }
    }

    suspend fun syncGame(installation: DownloadedRomEntity, rom: RomDto): SyncSummary = StorageAccess.normal {
        val resolution = coreSupport(
            rom = rom,
            file = rom.files.firstOrNull { it.id == installation.fileId } ?: rom.files.firstOrNull(),
        )
        val profile = resolution.runtimeProfile ?: error(resolution.message ?: "Unsupported platform.")
        return@normal syncBridge.syncGame(installation, rom, profile)
    }

    suspend fun deleteBrowsableState(installation: DownloadedRomEntity, state: BrowsableGameState) = StorageAccess.normal {
        require(File(state.localPath).canonicalPath.startsWith(libraryStore.saveStatesDirectory(installation).canonicalPath + File.separator)) { "This state belongs to another installed content generation." }
        when (state.deletePolicy) {
            GameStateDeletePolicy.NONE -> error("${state.label} cannot be deleted.")
            GameStateDeletePolicy.LOCAL_AND_REMOTE -> {
                val slot = state.slot ?: error("${state.label} is missing a slot number.")
                deleteSaveState(installation, slot)
            }
            GameStateDeletePolicy.LOCAL_ONLY -> {
                runCatching { File(state.localPath).delete() }
                recoveryStateDao.deleteByKey(installation.romId, installation.fileId, state.id, installation.progressScope)
            }
        }
    }

    suspend fun preparePlayerLaunch(installation: DownloadedRomEntity, rom: RomDto): PlayerLaunchPreparation = StorageAccess.normal {
        requireLegacyPlayerProgress(database.progressDao(), installation)
        val pendingTarget = takePendingPlayerLaunchTarget(installation.romId, installation.fileId)
        if (pendingTarget != null) {
            return@normal PlayerLaunchPreparation(
                launchTarget = pendingTarget,
                syncPresentation = currentGameSyncPresentation(installation),
            )
        }
        val resolution = coreSupport(
            rom = rom,
            file = rom.files.firstOrNull { it.id == installation.fileId } ?: rom.files.firstOrNull(),
        )
        val profile = resolution.runtimeProfile ?: error(resolution.message ?: "Unsupported platform.")
        return@normal syncBridge.preparePlayerEntry(installation, rom, profile)
    }

    suspend fun adoptRemoteContinuity(installation: DownloadedRomEntity, rom: RomDto): PlayerLaunchPreparation = StorageAccess.normal {
        val resolution = coreSupport(
            rom = rom,
            file = rom.files.firstOrNull { it.id == installation.fileId } ?: rom.files.firstOrNull(),
        )
        val profile = resolution.runtimeProfile ?: error(resolution.message ?: "Unsupported platform.")
        return@normal syncBridge.adoptRemoteContinuity(installation, rom, profile)
    }

    suspend fun flushContinuity(installation: DownloadedRomEntity, rom: RomDto, sessionActive: Boolean): SyncSummary = StorageAccess.normal {
        val resolution = coreSupport(
            rom = rom,
            file = rom.files.firstOrNull { it.id == installation.fileId } ?: rom.files.firstOrNull(),
        )
        val profile = resolution.runtimeProfile ?: error(resolution.message ?: "Unsupported platform.")
        return@normal syncBridge.flushContinuity(installation, rom, profile, sessionActive = sessionActive)
    }

    suspend fun findCachedRom(romId: Int): RomDto? = StorageAccess.normal {
        val profile = authManager.getActiveProfile() ?: return@normal null
        return@normal cachedRomDao.getById(profile.id, romId)?.toDomain(cacheCodec)
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

    private suspend fun cachePlatforms(profile: ServerProfile, platforms: List<PlatformDto>) = catalogCache.cachePlatforms(profile, platforms)
    private suspend fun cacheRoms(profile: ServerProfile, roms: List<RomDto>, detailed: Boolean = false, filesIncluded: Boolean = false) = catalogCache.cacheRoms(profile, roms, detailed, filesIncluded)
    private suspend fun cacheCollections(profile: ServerProfile, collections: List<RommCollectionDto>) = catalogCache.cacheCollections(profile, collections)
    private suspend fun replaceCollectionMembership(profileId: String, collection: RommCollectionDto, romIds: List<Int>) = catalogCache.replaceCollectionMembership(profileId, collection, romIds)
    private suspend fun cacheRomFeed(profileId: String, feedType: OfflineHomeFeedType, roms: List<RomDto>) = catalogCache.cacheRomFeed(profileId, feedType, roms)
    private suspend fun cacheCollectionFeed(profileId: String, collections: List<RommCollectionDto>) = catalogCache.cacheCollectionFeed(profileId, collections)

    private suspend fun fetchAllRoms(service: RommService): List<RomDto> = CatalogReader(service).roms()
    private suspend fun fetchCollections(service: RommService): List<RommCollectionDto> = CatalogReader(service).collections()

    private suspend fun fetchDetailedRom(service: RommService, romId: Int): RomDto {
        val rom = service.getRomById(romId)
        val siblingFiles = rom.siblings.orEmpty().mapNotNull { sibling ->
            service.getRomById(sibling.id).files.firstOrNull()
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
        val installedRomIds = downloadedRomDao.observeAll().first().filter { it.ownerProfileId == null || it.ownerProfileId == profile.id }.map { it.romId }.toSet()
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
                    pinned = collection.cacheKey in pinnedCollections,
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
            val downloadClient = io.github.bandoracer.rommio.data.network.DownloadClient(authManager)
            downloadClient.downloadToFile(
                profileId = profile.id,
                absoluteUrl = sourceUrl,
                target = target,
                includeOriginAuth = true,
            )
        }
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
                markRefreshFailed(profile.id, error)
                throw error
            }
        }
    }

    private suspend fun markRefreshFailed(profileId: String, error: Throwable) = withContext(NonCancellable) {
        val current = profileCacheStateDao.getByProfile(profileId) ?: return@withContext
        profileCacheStateDao.upsert(current.copy(isRefreshing = false,
            lastError = if (error is CancellationException) "Refresh interrupted; cached content retained." else error.message ?: "Refresh failed."))
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
        withContext(NonCancellable) {
            refreshMutex.withLock { inFlightRefreshes.remove(scopeKey) }
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

    private fun moshiContinuityPayload(payload: PendingContinuitySyncPayload): String =
        pendingActionCodec.encode(payload)

    private fun moshiContinuityPayload(raw: String): PendingContinuitySyncPayload =
        pendingActionCodec.decodeContinuity(raw)

    private fun moshiLegacyGameSyncPayload(raw: String): PendingContinuitySyncPayload =
        pendingActionCodec.decodeContinuity(raw)

    





















































/*
        DownloadStatus.CANCELED -> 3
        DownloadStatus.COMPLETED -> 4
    }
}

private data class LegacyGameSyncPayload(
    val romId: Int,
    val fileId: Int,
)

private fun GameSyncJournalEntity?.toPresentation(connectivity: ConnectivityState): GameSyncPresentation {
    if (this == null) {
        return GameSyncPresentation()
    }
    val kind = when {
        lastError != null -> GameSyncStatusKind.ERROR
        remoteContinuityAvailable -> GameSyncStatusKind.CLOUD_PROGRESS_AVAILABLE
        pendingContinuityUpload && connectivity == ConnectivityState.OFFLINE -> GameSyncStatusKind.OFFLINE_PENDING
        pendingContinuityUpload -> GameSyncStatusKind.OFFLINE_PENDING
        lastSuccessfulSyncAtEpochMs != null -> GameSyncStatusKind.SYNCED
        else -> GameSyncStatusKind.IDLE
    }
    val message = when (kind) {
        GameSyncStatusKind.ERROR -> lastError ?: "Sync failed."
        GameSyncStatusKind.CLOUD_PROGRESS_AVAILABLE -> remoteDeviceName?.let { "Cloud progress available from $it." }
            ?: "Cloud progress available."
        GameSyncStatusKind.OFFLINE_PENDING -> if (connectivity == ConnectivityState.OFFLINE) {
            "Offline changes pending."
        } else {
            "Changes queued for upload."
        }
        GameSyncStatusKind.SYNCED -> "Synced just now."
        GameSyncStatusKind.CONFLICT -> "Choose which progress to keep."
        GameSyncStatusKind.LOCAL_ONLY -> "Local-only install."
        GameSyncStatusKind.IDLE -> lastSyncNote ?: "Ready to sync."
    }
    return GameSyncPresentation(
        kind = kind,
        message = message,
        lastSuccessfulSyncAtEpochMs = lastSuccessfulSyncAtEpochMs,
        remoteDeviceName = remoteDeviceName,
    )
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
*/

    private fun moshiManualSlotPayload(payload: PendingManualSlotPayload): String =
        pendingActionCodec.encode(payload)

    private fun moshiManualSlotPayload(raw: String): PendingManualSlotPayload =
        pendingActionCodec.decodeManualSlot(raw)

    private fun moshiCorePayload(payload: PendingCoreDownloadPayload): String =
        pendingActionCodec.encode(payload)

    private fun moshiCorePayload(raw: String): PendingCoreDownloadPayload =
        pendingActionCodec.decodeCore(raw)

    private fun playerLaunchKey(romId: Int, fileId: Int, scope: String): String = "$scope:$romId:$fileId"
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

private data class LegacyGameSyncPayload(
    val romId: Int,
    val fileId: Int,
)

private fun GameSyncJournalEntity?.toPresentation(connectivity: ConnectivityState): GameSyncPresentation =
    io.github.bandoracer.rommio.domain.sync.legacyLocalPresentation(this?.lastSuccessfulSyncAtEpochMs)

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

internal fun buildGameStateRecovery(
    resumeFile: File?,
    manualStates: List<SaveStateEntity>,
    manualStateJournals: List<SaveStateSyncJournalEntity>,
    recoveryStates: List<RecoveryStateEntity>,
    journal: GameSyncJournalEntity?,
    connectivity: ConnectivityState,
): GameStateRecovery {
    val journalBySlot = manualStateJournals.associateBy { it.slot }
    val playableImports = recoveryStates
        .filter { it.origin == io.github.bandoracer.rommio.model.RecoveryStateOrigin.LEGACY_IMPORT.name }
        .map { entry ->
            entry.toBrowsableGameState().copy(
                deletePolicy = GameStateDeletePolicy.LOCAL_ONLY,
                originType = BrowsableGameStateOrigin.IMPORTED_PLAYABLE,
            )
        }
        .sortedByDescending { it.updatedAtEpochMs }

    return GameStateRecovery(
        resume = journal.toResumeStateSummary(resumeFile, connectivity),
        snapshots = recoveryStates
            .filter { it.origin == io.github.bandoracer.rommio.model.RecoveryStateOrigin.AUTO_HISTORY.name }
            .map { it.toBrowsableGameState() }
            .sortedByDescending { it.updatedAtEpochMs },
        saveSlots = manualStates
            .filter { journalBySlot[it.slot]?.let { row -> !row.deleted && !row.pendingDelete } ?: true }
            .sortedBy { it.slot }
            .map { state ->
                BrowsableGameState(
                    id = "manual:${state.slot}",
                    kind = BrowsableGameStateKind.MANUAL_SLOT,
                    label = "Save slot ${state.slot}",
                    localPath = state.localPath,
                    updatedAtEpochMs = state.updatedAtEpochMs,
                    slot = state.slot,
                    sourceDeviceName = journalBySlot[state.slot]?.sourceDeviceName,
                    originType = BrowsableGameStateOrigin.MANUAL_SLOT,
                    deletePolicy = GameStateDeletePolicy.LOCAL_AND_REMOTE,
                )
            } + playableImports,
    )
}

private fun BrowsableGameStateKind.toLaunchTargetKind(): PlayerLaunchTargetKind {
    return when (this) {
        BrowsableGameStateKind.MANUAL_SLOT -> PlayerLaunchTargetKind.MANUAL_SLOT
        BrowsableGameStateKind.RECOVERY_HISTORY -> PlayerLaunchTargetKind.RECOVERY_HISTORY
        BrowsableGameStateKind.IMPORTED_CLOUD -> PlayerLaunchTargetKind.IMPORTED_CLOUD
    }
}

private fun GameSyncJournalEntity?.toResumeStateSummary(
    resumeFile: File?,
    connectivity: ConnectivityState,
): ResumeStateSummary {
    val available = resumeFile?.isFile == true
    return ResumeStateSummary(
        available = available,
        localPath = resumeFile?.takeIf { available }?.absolutePath,
        updatedAtEpochMs = resumeFile?.takeIf { available }?.lastModified(),
        statusKind = if (available) ResumeStateStatusKind.LOCAL_ONLY else ResumeStateStatusKind.UNAVAILABLE,
        primaryStatusMessage = if (available) io.github.bandoracer.rommio.domain.sync.LEGACY_SYNC_PAUSED_MESSAGE else "No resume state yet.",
        lastSuccessfulSyncAtEpochMs = this?.lastSuccessfulSyncAtEpochMs,
    )
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

internal fun PlatformDto.platformLogoSource(baseUrl: String): String? {
    return listOf(slug, fsSlug)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .firstNotNullOfOrNull { resolveRemoteAssetUrl(baseUrl, "/assets/platforms/$it.svg") }
        ?: resolveRemoteAssetUrl(baseUrl, urlLogo)
}

internal fun RomDto.coverSource(baseUrl: String): String? {
    return resolveRemoteAssetUrl(baseUrl, urlCover)
}

internal fun RommCollectionDto.collectionCoverSource(baseUrl: String): String? {
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
        cachedMetadata = codec.decodeMetadata(metadataJson),
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
