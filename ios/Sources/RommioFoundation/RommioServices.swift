import Foundation
import Network
import Observation
import RommioContract

@MainActor
@Observable
public final class NetworkMonitor {
    public private(set) var connectivity: ConnectivityState = .online

    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "io.github.mattsays.rommio.network")

    public init() {
        monitor.pathUpdateHandler = { [weak self] path in
            Task { @MainActor in
                self?.connectivity = path.status == .satisfied ? .online : .offline
            }
        }
        monitor.start(queue: queue)
    }

    deinit {
        monitor.cancel()
    }
}

public struct RommioServices: Sendable {
    public let database: AppDatabase
    public let secretStore: any SecretStore
    public let profileStore: GRDBServerProfileStore
    public let offlineStore: GRDBOfflineReadinessStore
    public let authController: AuthSessionController
    public let libraryRepository: DefaultLibraryRepository
    public let downloadQueue: ManagedDownloadQueue
    public let libraryStore: AppManagedLibraryStore
    public let networkMonitor: NetworkMonitor
    public let controllerMonitor: any ControllerMonitoring
    public let playerControlsRepository: PlayerControlsRepository
    public let syncBridge: ProfileSyncBridge

    public init(
        database: AppDatabase,
        secretStore: any SecretStore,
        profileStore: GRDBServerProfileStore,
        offlineStore: GRDBOfflineReadinessStore,
        authController: AuthSessionController,
        libraryRepository: DefaultLibraryRepository,
        downloadQueue: ManagedDownloadQueue,
        libraryStore: AppManagedLibraryStore,
        networkMonitor: NetworkMonitor,
        controllerMonitor: (any ControllerMonitoring)? = nil,
        playerControlsRepository: PlayerControlsRepository? = nil,
        syncBridge: ProfileSyncBridge? = nil
    ) {
        let resolvedControllerMonitor = controllerMonitor ?? GameControllerMonitor()
        self.database = database
        self.secretStore = secretStore
        self.profileStore = profileStore
        self.offlineStore = offlineStore
        self.authController = authController
        self.libraryRepository = libraryRepository
        self.downloadQueue = downloadQueue
        self.libraryStore = libraryStore
        self.networkMonitor = networkMonitor
        self.controllerMonitor = resolvedControllerMonitor
        self.playerControlsRepository = playerControlsRepository ?? PlayerControlsRepository(
            database: database,
            controllerMonitor: resolvedControllerMonitor
        )
        self.syncBridge = syncBridge ?? ProfileSyncBridge(
            authController: authController,
            libraryStore: libraryStore,
            database: database
        )
    }

    @MainActor
    public static func live() throws -> RommioServices {
        let supportRoot = try applicationSupportRoot()
        let database = try AppDatabase(rootDirectory: supportRoot.appending(path: "Database"))
        let secretStore = try liveSecretStore(rootDirectory: supportRoot)
        let profileStore = GRDBServerProfileStore(database: database)
        let offlineStore = GRDBOfflineReadinessStore(database: database)
        let authController = AuthSessionController(
            profileStore: profileStore,
            secretStore: secretStore
        )
        let libraryStore = AppManagedLibraryStore(rootDirectory: supportRoot.appending(path: "Library"))
        try libraryStore.ensureRootLayout()
        let controllerMonitor = GameControllerMonitor()
        let playerControlsRepository = PlayerControlsRepository(
            database: database,
            controllerMonitor: controllerMonitor
        )
        let libraryRepository = DefaultLibraryRepository(
            database: database,
            profileStore: profileStore,
            offlineStore: offlineStore,
            authController: authController
        )
        let downloadQueue = ManagedDownloadQueue(
            database: database,
            profileStore: profileStore,
            secretStore: secretStore,
            libraryStore: libraryStore
        )
        let syncBridge = ProfileSyncBridge(
            authController: authController,
            libraryStore: libraryStore,
            database: database
        )

        return RommioServices(
            database: database,
            secretStore: secretStore,
            profileStore: profileStore,
            offlineStore: offlineStore,
            authController: authController,
            libraryRepository: libraryRepository,
            downloadQueue: downloadQueue,
            libraryStore: libraryStore,
            networkMonitor: NetworkMonitor(),
            controllerMonitor: controllerMonitor,
            playerControlsRepository: playerControlsRepository,
            syncBridge: syncBridge
        )
    }

    private static func liveSecretStore(rootDirectory: URL) throws -> any SecretStore {
        let environment = ProcessInfo.processInfo.environment
        #if targetEnvironment(simulator)
        return try FileSecretStore(rootDirectory: rootDirectory.appending(path: "Secrets"))
        #else
        if environment["ROMMIO_SECRET_STORE"] == "file" {
            return try FileSecretStore(rootDirectory: rootDirectory.appending(path: "Secrets"))
        }
        return KeychainSecretStore(service: "io.github.mattsays.rommio.ios")
        #endif
    }

    private static func applicationSupportRoot() throws -> URL {
        if let override = ProcessInfo.processInfo.environment["ROMMIO_APP_ROOT"]?
            .trimmingCharacters(in: .whitespacesAndNewlines),
           !override.isEmpty {
            let root = URL(fileURLWithPath: override, isDirectory: true)
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            return root
        }

        let base = try FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let root = base.appending(path: "Rommio")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        return root
    }
}
