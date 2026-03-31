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

class AppContainer(appContext: Context) {
    val libraryStore = AppManagedLibraryStore(appContext).also { it.ensureRootLayout() }
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
    val secretStore = AuthSecretStore(appContext)
    val authJsonCodec = AuthJsonCodec()
    val controlsJsonCodec = ControlsJsonCodec()
    val controlsPreferencesStore = PlayerControlsPreferencesStore(appContext)
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
    val thumbnailCacheStore = ThumbnailCacheStore(appContext, mediaCacheEntryDao)
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
    )

    val coreResolver = CoreCatalogResolver(appContext, libraryStore)
    val controlProfileResolver = PlatformControlProfileResolver()
    val coreInstaller = LibretroCoreInstaller(libraryStore)
    val syncBridge = RommSyncBridge(
        authManager = authManager,
        serviceFactory = serviceFactory,
        downloadClient = downloadClient,
        libraryStore = libraryStore,
        saveStateDao = saveStateDao,
        gameSyncJournalDao = gameSyncJournalDao,
        saveStateSyncJournalDao = saveStateSyncJournalDao,
        recoveryStateDao = recoveryStateDao,
        deviceName = deviceName,
    )

    val repository = RommRepository(
        context = appContext,
        authManager = authManager,
        serviceFactory = serviceFactory,
        downloadedRomDao = downloadedRomDao,
        downloadRecordDao = downloadRecordDao,
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
