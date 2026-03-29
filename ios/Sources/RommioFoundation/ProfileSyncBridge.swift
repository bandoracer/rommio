import Foundation
import RommioContract
#if canImport(UIKit)
import UIKit
#endif

private actor PendingLaunchTargetStore {
    private var targets: [String: PlayerLaunchTarget] = [:]

    func set(_ target: PlayerLaunchTarget, key: String) {
        targets[key] = target
    }

    func consume(key: String) -> PlayerLaunchTarget? {
        targets.removeValue(forKey: key)
    }
}

public struct ProfileSyncBridge: Sendable {
    public let authController: AuthSessionController
    public let libraryStore: LibraryStore
    public let database: AppDatabase
    public let deviceName: String
    private let pendingLaunchTargets = PendingLaunchTargetStore()

    public init(
        authController: AuthSessionController,
        libraryStore: LibraryStore,
        database: AppDatabase,
        deviceName: String? = nil
    ) {
        self.authController = authController
        self.libraryStore = libraryStore
        self.database = database
        self.deviceName = deviceName ?? currentDeviceName()
    }

    public func syncGame(
        profile: ServerProfile,
        installation: InstalledROMReference,
        rom: RomDTO,
        runtimeID: String
    ) async throws -> SyncSummary {
        let client = await authController.client(for: profile.id, baseURL: profile.baseURL)
        let deviceID = try await authController.activeAuthenticatedContext()?.deviceID
        let bridge = RommSyncBridge(
            client: client,
            libraryStore: libraryStore,
            database: database,
            deviceName: deviceName
        )
        return try await bridge.syncGame(
            profileID: profile.id,
            installation: installation,
            rom: rom,
            runtimeID: runtimeID,
            remoteBaseURL: profile.baseURL,
            deviceID: deviceID
        )
    }

    public func flushContinuity(
        profile: ServerProfile,
        installation: InstalledROMReference,
        rom: RomDTO,
        runtimeID: String,
        sessionActive: Bool,
        sessionStartedAtEpochMS: Int64?
    ) async throws -> SyncSummary {
        let client = await authController.client(for: profile.id, baseURL: profile.baseURL)
        let deviceID = try await authController.activeAuthenticatedContext()?.deviceID
        let bridge = RommSyncBridge(
            client: client,
            libraryStore: libraryStore,
            database: database,
            deviceName: deviceName
        )
        return try await bridge.syncGame(
            profileID: profile.id,
            installation: installation,
            rom: rom,
            runtimeID: runtimeID,
            remoteBaseURL: profile.baseURL,
            deviceID: deviceID,
            includeManualSlots: false,
            sessionActive: sessionActive,
            sessionStartedAtEpochMS: sessionStartedAtEpochMS
        )
    }

    public func preparePlayerLaunch(
        profile: ServerProfile,
        installation: InstalledROMReference,
        rom: RomDTO,
        runtimeID: String,
        connectivity: ConnectivityState
    ) async throws -> PlayerLaunchPreparation {
        let pendingKey = launchKey(installation: installation)
        if let pending = await pendingLaunchTargets.consume(key: pendingKey) {
            let cached = try cachedPlayerLaunch(profile: profile, installation: installation, connectivity: connectivity)
            return PlayerLaunchPreparation(
                launchTarget: pending,
                syncPresentation: cached.syncPresentation
            )
        }
        let client = await authController.client(for: profile.id, baseURL: profile.baseURL)
        let deviceID = try await authController.activeAuthenticatedContext()?.deviceID
        let bridge = RommSyncBridge(
            client: client,
            libraryStore: libraryStore,
            database: database,
            deviceName: deviceName
        )
        return try await bridge.preparePlayerEntry(
            profileID: profile.id,
            installation: installation,
            rom: rom,
            runtimeID: runtimeID,
            remoteBaseURL: profile.baseURL,
            deviceID: deviceID,
            connectivity: connectivity
        )
    }

    public func cachedPlayerLaunch(
        profile: ServerProfile?,
        installation: InstalledROMReference,
        connectivity: ConnectivityState
    ) throws -> PlayerLaunchPreparation {
        let bridge = RommSyncBridge(
            client: NullRommService(),
            libraryStore: libraryStore,
            database: database,
            deviceName: deviceName
        )
        return try bridge.cachedPlayerLaunch(
            profileID: profile?.id,
            installation: installation,
            connectivity: connectivity
        )
    }

    public func refreshStateBrowser(
        profile: ServerProfile,
        installation: InstalledROMReference,
        rom: RomDTO,
        runtimeID: String,
        connectivity: ConnectivityState
    ) async throws -> GameStateBrowser {
        let client = await authController.client(for: profile.id, baseURL: profile.baseURL)
        let deviceID = try await authController.activeAuthenticatedContext()?.deviceID
        let bridge = RommSyncBridge(
            client: client,
            libraryStore: libraryStore,
            database: database,
            deviceName: deviceName
        )
        return try await bridge.refreshStateBrowser(
            profileID: profile.id,
            installation: installation,
            rom: rom,
            runtimeID: runtimeID,
            remoteBaseURL: profile.baseURL,
            deviceID: deviceID,
            connectivity: connectivity
        )
    }

    public func cachedStateBrowser(
        profile: ServerProfile?,
        installation: InstalledROMReference,
        connectivity: ConnectivityState
    ) throws -> GameStateBrowser {
        let bridge = RommSyncBridge(
            client: NullRommService(),
            libraryStore: libraryStore,
            database: database,
            deviceName: deviceName
        )
        return try bridge.buildStateBrowser(
            profileID: profile?.id,
            installation: installation,
            connectivity: connectivity
        )
    }

    public func recordManualState(
        profile: ServerProfile,
        installation: InstalledROMReference,
        slot: Int,
        fileURL: URL
    ) async throws {
        let deviceID = try await authController.activeAuthenticatedContext()?.deviceID
        let bridge = RommSyncBridge(
            client: NullRommService(),
            libraryStore: libraryStore,
            database: database,
            deviceName: deviceName
        )
        try bridge.recordManualState(
            profileID: profile.id,
            installation: installation,
            slot: slot,
            fileURL: fileURL,
            deviceID: deviceID
        )
    }

    public func deleteBrowsableState(
        profile: ServerProfile,
        installation: InstalledROMReference,
        state: BrowsableGameState
    ) async throws {
        let bridge = RommSyncBridge(
            client: NullRommService(),
            libraryStore: libraryStore,
            database: database,
            deviceName: deviceName
        )
        try bridge.deleteBrowsableState(
            profileID: profile.id,
            installation: installation,
            state: state
        )
    }

    public func setPendingLaunchTarget(
        installation: InstalledROMReference,
        state: BrowsableGameState
    ) async throws {
        guard FileManager.default.fileExists(atPath: state.localPath) else {
            throw NSError(
                domain: "RommioState",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: "\(state.label) is not available on this device yet."]
            )
        }
        await pendingLaunchTargets.set(
            PlayerLaunchTarget(
                kind: state.kind.asLaunchTargetKind,
                localStatePath: state.localPath,
                stateID: state.id,
                label: state.label
            ),
            key: launchKey(installation: installation)
        )
    }
}

private func launchKey(installation: InstalledROMReference) -> String {
    "\(installation.romID):\(installation.fileID)"
}

private extension BrowsableGameStateKind {
    var asLaunchTargetKind: PlayerLaunchTargetKind {
        switch self {
        case .manualSlot:
            return .manualSlot
        case .recoveryHistory:
            return .recoveryHistory
        case .importedCloud:
            return .importedCloud
        }
    }
}

private actor NullRommService: RommServicing {
    func getCurrentUser() async throws -> UserDTO { throw URLError(.userAuthenticationRequired) }
    func getHeartbeat() async throws -> HeartbeatDTO { throw URLError(.userAuthenticationRequired) }
    func getPlatforms() async throws -> [PlatformDTO] { throw URLError(.userAuthenticationRequired) }
    func getRecentlyAdded() async throws -> ItemsResponse<RomDTO> { throw URLError(.userAuthenticationRequired) }
    func getRoms(query: RomQuery) async throws -> ItemsResponse<RomDTO> { throw URLError(.userAuthenticationRequired) }
    func getRom(id: Int) async throws -> RomDTO { throw URLError(.userAuthenticationRequired) }
    func getCollections() async throws -> [CollectionResponseDTO] { throw URLError(.userAuthenticationRequired) }
    func getSmartCollections() async throws -> [SmartCollectionResponseDTO] { throw URLError(.userAuthenticationRequired) }
    func getVirtualCollections(type: String, limit: Int?) async throws -> [VirtualCollectionResponseDTO] { throw URLError(.userAuthenticationRequired) }
    func listSaves(romID: Int, deviceID: String?) async throws -> [SaveDTO] { throw URLError(.userAuthenticationRequired) }
    func listStates(romID: Int) async throws -> [StateDTO] { throw URLError(.userAuthenticationRequired) }
    func registerDevice(_ request: DeviceRegistrationRequest) async throws -> DeviceRegistrationResponse { throw URLError(.userAuthenticationRequired) }
    func uploadSave(romID: Int, emulator: String?, slot: String?, deviceID: String?, overwrite: Bool?, fileURL: URL) async throws -> SaveDTO { throw URLError(.userAuthenticationRequired) }
    func uploadState(romID: Int, emulator: String?, fileURL: URL) async throws -> StateDTO { throw URLError(.userAuthenticationRequired) }
    func download(from absoluteURL: URL, to destinationURL: URL) async throws { throw URLError(.userAuthenticationRequired) }
}

private func currentDeviceName() -> String {
    #if canImport(UIKit)
    return UIDevice.current.name
    #elseif os(macOS)
    return Host.current().localizedName ?? "Apple Device"
    #else
    return "Apple Device"
    #endif
}
