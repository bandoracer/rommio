package io.github.mattsays.rommnative

import android.content.Context
import androidx.room.Room
import io.github.mattsays.rommnative.data.cache.ThumbnailCacheStore
import io.github.mattsays.rommnative.data.auth.AuthDiscovery
import io.github.mattsays.rommnative.data.auth.AuthJsonCodec
import io.github.mattsays.rommnative.data.auth.AuthManager
import io.github.mattsays.rommnative.data.auth.AuthSecretStore
import io.github.mattsays.rommnative.data.input.ControlsJsonCodec
import io.github.mattsays.rommnative.data.input.ExternalControllerMonitor
import io.github.mattsays.rommnative.data.input.PlayerControlsPreferencesStore
import io.github.mattsays.rommnative.data.local.AppDatabase
import io.github.mattsays.rommnative.data.network.ConnectivityMonitor
import io.github.mattsays.rommnative.data.network.DownloadClient
import io.github.mattsays.rommnative.data.repository.PlayerControlsRepository
import io.github.mattsays.rommnative.data.network.RommServiceFactory
import io.github.mattsays.rommnative.data.repository.RommRepository
import io.github.mattsays.rommnative.domain.input.PlatformControlProfileResolver
import io.github.mattsays.rommnative.domain.player.CoreCatalogResolver
import io.github.mattsays.rommnative.domain.player.LibretroCoreInstaller
import io.github.mattsays.rommnative.domain.player.LibretroPlayerEngine
import io.github.mattsays.rommnative.domain.storage.AppManagedLibraryStore
import io.github.mattsays.rommnative.domain.sync.RommSyncBridge

class AppContainer(appContext: Context) {
    val libraryStore = AppManagedLibraryStore(appContext).also { it.ensureRootLayout() }

    private val database = Room.databaseBuilder(
        appContext,
        AppDatabase::class.java,
        "romm_native.db",
    ).addMigrations(
        AppDatabase.MIGRATION_1_2,
        AppDatabase.MIGRATION_2_3,
        AppDatabase.MIGRATION_3_4,
        AppDatabase.MIGRATION_4_5,
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

    val coreResolver = CoreCatalogResolver(appContext, libraryStore)
    val controlProfileResolver = PlatformControlProfileResolver()
    val coreInstaller = LibretroCoreInstaller(libraryStore)
    val syncBridge = RommSyncBridge(
        authManager = authManager,
        serviceFactory = serviceFactory,
        downloadClient = downloadClient,
        libraryStore = libraryStore,
        saveStateDao = saveStateDao,
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
        libraryStore = libraryStore,
        coreResolver = coreResolver,
        coreInstaller = coreInstaller,
        syncBridge = syncBridge,
        connectivityMonitor = connectivityMonitor,
        thumbnailCacheStore = thumbnailCacheStore,
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
