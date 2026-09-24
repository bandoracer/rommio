package io.github.bandoracer.rommio

import android.content.Context
import android.os.Build
import androidx.room.Room
import io.github.bandoracer.rommio.data.cache.ThumbnailCacheStore
import io.github.bandoracer.rommio.data.auth.AuthDiscovery
import io.github.bandoracer.rommio.data.auth.AuthJsonCodec
import io.github.bandoracer.rommio.data.auth.AuthManager
import io.github.bandoracer.rommio.data.auth.AuthSecretStore
import io.github.bandoracer.rommio.data.input.ControlsJsonCodec
import io.github.bandoracer.rommio.data.input.ExternalControllerMonitor
import io.github.bandoracer.rommio.data.input.PlayerControlsPreferencesStore
import io.github.bandoracer.rommio.data.local.AppDatabase
import io.github.bandoracer.rommio.data.migration.MigrationBundleManager
import io.github.bandoracer.rommio.data.network.ConnectivityMonitor
import io.github.bandoracer.rommio.data.network.DownloadClient
import io.github.bandoracer.rommio.data.repository.PlayerControlsRepository
import io.github.bandoracer.rommio.data.network.RommServiceFactory
import io.github.bandoracer.rommio.data.repository.RommRepository
import io.github.bandoracer.rommio.domain.input.PlatformControlProfileResolver
import io.github.bandoracer.rommio.domain.player.CoreCatalogResolver
import io.github.bandoracer.rommio.domain.player.LibretroCoreInstaller
import io.github.bandoracer.rommio.domain.player.LibretroPlayerEngine
import io.github.bandoracer.rommio.domain.storage.AppManagedLibraryStore
import io.github.bandoracer.rommio.domain.sync.RommSyncBridge
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

class AppContainer(appContext: Context, progressEngineFactory: () -> io.github.bandoracer.rommio.domain.player.PlayerEngine = { LibretroPlayerEngine() }) {
    private val appContextFilesForRecovery = appContext.noBackupFilesDir
    val libraryStore = AppManagedLibraryStore(appContext)
    private val deviceName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"

    private val database = Room.databaseBuilder(
        appContext,
        AppDatabase::class.java,
        "romm_native.db",
    ).addMigrations(
        AppDatabase.MIGRATION_1_2,
        AppDatabase.MIGRATION_2_3,
        AppDatabase.MIGRATION_3_4,
        AppDatabase.MIGRATION_4_5,
        AppDatabase.MIGRATION_5_6,
        AppDatabase.MIGRATION_6_7,
        AppDatabase.MIGRATION_7_8,
        AppDatabase.MIGRATION_8_9,
        AppDatabase.MIGRATION_9_10,
        AppDatabase.MIGRATION_10_11,
        AppDatabase.MIGRATION_11_12,
        AppDatabase.MIGRATION_12_13,
        AppDatabase.MIGRATION_13_14,
    )
        .build()

    val downloadedRomDao = database.downloadedRomDao()
    val downloadRecordDao = database.downloadRecordDao()
    val saveStateDao = database.saveStateDao()
    val serverProfileDao = database.serverProfileDao()
    val touchLayoutProfileDao = database.touchLayoutProfileDao()
    val hardwareBindingProfileDao = database.hardwareBindingProfileDao()
    val cachedPlatformDao = database.cachedPlatformDao()
    val cachedRomDao = database.cachedRomDao()
    val cachedCollectionDao = database.cachedCollectionDao()
    val cachedCollectionRomDao = database.cachedCollectionRomDao()
    val cachedHomeEntryDao = database.cachedHomeEntryDao()
    val profileCacheStateDao = database.profileCacheStateDao()
    val pendingRemoteActionDao = database.pendingRemoteActionDao()
    val mediaCacheEntryDao = database.mediaCacheEntryDao()
    val gameSyncJournalDao = database.gameSyncJournalDao()
    val saveStateSyncJournalDao = database.saveStateSyncJournalDao()
    val recoveryStateDao = database.recoveryStateDao()
    /** Local-only play data (docs/play-data-local.md): sessions, save-state names and thumbnails. */
    val playDataDao = database.playDataDao()
    val saveThumbnails = io.github.bandoracer.rommio.data.playdata.SaveThumbnailStore(java.io.File(appContext.noBackupFilesDir, "save-previews"))
    val localPlayData = io.github.bandoracer.rommio.data.playdata.LocalPlayData(playDataDao, saveThumbnails)
    val secretStore = AuthSecretStore(appContext)
    val authJsonCodec = AuthJsonCodec()
    val controlsJsonCodec = ControlsJsonCodec()
    val controlsPreferencesStore = PlayerControlsPreferencesStore(appContext)
    /** Device-local theme and light/dark choice; deliberately not part of migration bundles. */
    val appearancePreferencesStore = io.github.bandoracer.rommio.data.input.AppearancePreferencesStore(appContext)
    val externalControllerMonitor = ExternalControllerMonitor(appContext)
    val connectivityMonitor = ConnectivityMonitor(appContext)
    val authDiscovery = AuthDiscovery()
    val authManager = AuthManager(
        serverProfileDao = serverProfileDao,
        secretStore = secretStore,
        discovery = authDiscovery,
        jsonCodec = authJsonCodec,
    )
    val serviceFactory = RommServiceFactory(authManager)
    val downloadClient = DownloadClient(authManager)
    val ownedDownloads = io.github.bandoracer.rommio.data.repository.OwnedDownloadQueue(database, authManager, libraryStore, downloadClient)
    val legacyProgressRecovery = io.github.bandoracer.rommio.data.migration.LegacyProgressRecovery(
        database, libraryStore, java.io.File(appContext.noBackupFilesDir, "legacy-progress-recovery"),
        currentOwner = {
            authManager.getActiveProfile()?.let { profile ->
                authManager.verifiedAccount(profile.id)?.takeIf { it.baseUrl == profile.baseUrl }?.let { profile.id to it }
            }
        },
    )
    val thumbnailCacheStore = ThumbnailCacheStore(appContext, mediaCacheEntryDao)
    /** Internal so instrumentation fixtures can seed the store (e.g. a synced two-branch conflict). */
    internal val progressStorage = io.github.bandoracer.rommio.domain.progress.ApplicationProgressStorage(
        database, java.io.File(appContext.noBackupFilesDir, "progress-v2"),
    )
    val migrationBundleManager = MigrationBundleManager(
        context = appContext,
        database = database,
        serverProfileDao = serverProfileDao,
        downloadedRomDao = downloadedRomDao,
        downloadRecordDao = downloadRecordDao,
        saveStateDao = saveStateDao,
        touchLayoutProfileDao = touchLayoutProfileDao,
        hardwareBindingProfileDao = hardwareBindingProfileDao,
        cachedPlatformDao = cachedPlatformDao,
        cachedRomDao = cachedRomDao,
        cachedCollectionDao = cachedCollectionDao,
        cachedCollectionRomDao = cachedCollectionRomDao,
        cachedHomeEntryDao = cachedHomeEntryDao,
        profileCacheStateDao = profileCacheStateDao,
        pendingRemoteActionDao = pendingRemoteActionDao,
        mediaCacheEntryDao = mediaCacheEntryDao,
        gameSyncJournalDao = gameSyncJournalDao,
        saveStateSyncJournalDao = saveStateSyncJournalDao,
        recoveryStateDao = recoveryStateDao,
        secretStore = secretStore,
        controlsPreferencesStore = controlsPreferencesStore,
        thumbnailCacheStore = thumbnailCacheStore,
        libraryStore = libraryStore,
        closeProgressForReplacement = { progressStorage.closeForReplacement() },
    )

    init {
        // Recover before constructing consumers or allowing workers/screens to use imported storage.
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            recoverStorage()
        }
    }

    suspend fun recoverStorage() {
        // Mark both journals before either recovery releases its exclusive lease to background work.
        io.github.bandoracer.rommio.domain.storage.StorageAccess.requireRecovery(
            java.io.File(appContextFilesForRecovery, "library-imports/pending").exists(),
        )
        io.github.bandoracer.rommio.domain.storage.StorageAccess.requireRecovery(
            java.io.File(appContextFilesForRecovery, "legacy-progress-recovery/pending.json").exists(), "legacy-progress",
        )
        runCatching { migrationBundleManager.recoverInterruptedImport() }
        runCatching { legacyProgressRecovery.recover() }
    }

    val firmwareStore = io.github.bandoracer.rommio.domain.player.FirmwareStore(libraryStore, openAsset = { appContext.assets.open(it) })
    val coreArtifacts = io.github.bandoracer.rommio.domain.player.CoreArtifactStore(libraryStore.coresDirectory())
    val packagedCores = io.github.bandoracer.rommio.domain.player.PackagedCoreArtifacts(appContext, libraryStore, coreArtifacts)
    val persistenceRegistry = io.github.bandoracer.rommio.domain.progress.FceummPersistenceRegistry(packagedCores)
    val progressSync = io.github.bandoracer.rommio.domain.progress.ApplicationProgressSync(
        { progressStorage.get() }, authManager, serviceFactory, java.io.File(appContext.cacheDir, "progress-transfer"),
        { io.github.bandoracer.rommio.data.work.ProgressSyncWorker.enqueue(appContext) },
    )
    // Qualified player sessions own their native lifecycle and durable local progress.
    val progressSessions = io.github.bandoracer.rommio.domain.progress.ApplicationProgressSessions(
        storeProvider = { progressStorage.get() },
        engineFactory = progressEngineFactory,
        registry = persistenceRegistry,
        modern = true,
        beforePrepare = { progressSync.beforeLaunch(requireNotNull(it.progressBinding)) },
        afterCommit = { result ->
            if (result?.stateRejected == true) android.widget.Toast.makeText(appContext,
                result.stateError ?: "Game-save data saved. Resume snapshot failed; the previous snapshot is preserved.", android.widget.Toast.LENGTH_LONG).show()
            progressSync.request()
        },
        deviceId = { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val preferences = appContext.getSharedPreferences("progress-installation", Context.MODE_PRIVATE)
            preferences.getString("device-id", null) ?: java.util.UUID.randomUUID().toString().also {
                check(preferences.edit().putString("device-id", it).commit()) { "Unable to persist the device identity." }
            }
        } },
        playData = localPlayData,
    )
    /** Play data changes while no game is running; pages re-read then (never mid-game, off the core lane). */
    val playDataChanges: kotlinx.coroutines.flow.Flow<Long> =
        kotlinx.coroutines.flow.combine(localPlayData.changes, progressSessions.active) { revision, active -> revision.takeIf { active == null } }
            .filterNotNull().distinctUntilChanged()
    private val activeVerifiedAccount: suspend () -> io.github.bandoracer.rommio.data.auth.VerifiedAccount? = {
        authManager.getActiveProfile()?.let { profile ->
            authManager.verifiedAccount(profile.id)?.takeIf { it.baseUrl == profile.baseUrl }
        }
    }
    // Read-only: never creates the identity. No identity means nothing was played here yet.
    private val existingDeviceId: suspend () -> String? = {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            appContext.getSharedPreferences("progress-installation", Context.MODE_PRIVATE).getString("device-id", null)
        }
    }
    /** Stats: recorded play sessions, with play before the first recorded session derived from checkpoints. */
    val playActivity = io.github.bandoracer.rommio.data.stats.PlayActivityRepository(
        storeProvider = { progressStorage.get() },
        accountProvider = activeVerifiedAccount,
        localDeviceId = existingDeviceId,
        recordedSessions = { serverKey, accountId ->
            playDataDao.sessionsFor(serverKey, accountId).map { row ->
                io.github.bandoracer.rommio.domain.stats.RecordedPlaySession(row.sessionId, row.romId, row.deviceId, row.platformSlug,
                    row.startedAtEpochMs, row.endedAtEpochMs, row.lastHeartbeatEpochMs, row.activeDurationMs,
                    io.github.bandoracer.rommio.domain.playdata.PlaySessionEndReason.fromKey(row.endReason))
            }
        },
    )
    /** Recent saves and a game's saved progress, read-only from the progress store. */
    val savedProgress = io.github.bandoracer.rommio.data.playdata.SavedProgressRepository(
        storeProvider = { progressStorage.get() },
        accountProvider = activeVerifiedAccount,
        localDeviceId = existingDeviceId,
        dao = playDataDao,
        thumbnails = saveThumbnails,
        formFactor = { io.github.bandoracer.rommio.data.playdata.DeviceFormFactors.resolve(appContext) },
    )
    val coreResolver = CoreCatalogResolver(appContext, libraryStore, firmwareStore, coreArtifacts, packagedCores)
    val controlProfileResolver = PlatformControlProfileResolver()
    val coreInstaller = LibretroCoreInstaller(libraryStore, coreArtifacts, packaged = packagedCores)
    val syncBridge = RommSyncBridge(
        profileIdProvider = { authManager.getActiveProfile()?.id },
        libraryStore = libraryStore,
        saveStateDao = saveStateDao,
        gameSyncJournalDao = gameSyncJournalDao,
        saveStateSyncJournalDao = saveStateSyncJournalDao,
        recoveryStateDao = recoveryStateDao,
        deviceName = deviceName,
    )

    val repository = RommRepository(
        context = appContext,
        database = database,
        authManager = authManager,
        serviceFactory = serviceFactory,
        downloadedRomDao = downloadedRomDao,
        downloadRecordDao = downloadRecordDao,
        ownedDownloads = ownedDownloads,
        saveStateDao = saveStateDao,
        cachedPlatformDao = cachedPlatformDao,
        cachedRomDao = cachedRomDao,
        cachedCollectionDao = cachedCollectionDao,
        cachedCollectionRomDao = cachedCollectionRomDao,
        cachedHomeEntryDao = cachedHomeEntryDao,
        profileCacheStateDao = profileCacheStateDao,
        pendingRemoteActionDao = pendingRemoteActionDao,
        gameSyncJournalDao = gameSyncJournalDao,
        saveStateSyncJournalDao = saveStateSyncJournalDao,
        recoveryStateDao = recoveryStateDao,
        libraryStore = libraryStore,
        coreResolver = coreResolver,
        coreInstaller = coreInstaller,
        syncBridge = syncBridge,
        connectivityMonitor = connectivityMonitor,
        thumbnailCacheStore = thumbnailCacheStore,
        migrationBundleManager = migrationBundleManager,
        firmwareStore = firmwareStore,
    )
    val playerControlsRepository = PlayerControlsRepository(
        resolver = controlProfileResolver,
        touchLayoutDao = touchLayoutProfileDao,
        hardwareBindingDao = hardwareBindingProfileDao,
        preferencesStore = controlsPreferencesStore,
        controllerMonitor = externalControllerMonitor,
        codec = controlsJsonCodec,
    )

    fun createPlayerEngine() = LibretroPlayerEngine()
}
