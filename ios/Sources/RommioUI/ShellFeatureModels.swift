import Foundation
import Observation
import RommioContract
import RommioFoundation
import RommioPlayerKit

public struct LocalStorageSummary: Hashable, Sendable {
    public var installedGameCount: Int
    public var installedFileCount: Int
    public var totalBytes: Int64
    public var cacheBytes: Int64

    public init(
        installedGameCount: Int = 0,
        installedFileCount: Int = 0,
        totalBytes: Int64 = 0,
        cacheBytes: Int64 = 0
    ) {
        self.installedGameCount = installedGameCount
        self.installedFileCount = installedFileCount
        self.totalBytes = totalBytes
        self.cacheBytes = cacheBytes
    }
}

public struct DownloadQueueSummary: Hashable, Sendable {
    public var queuedCount: Int
    public var runningCount: Int
    public var failedCount: Int
    public var completedCount: Int
    public var offlineQueuedCount: Int

    public init(
        queuedCount: Int = 0,
        runningCount: Int = 0,
        failedCount: Int = 0,
        completedCount: Int = 0,
        offlineQueuedCount: Int = 0
    ) {
        self.queuedCount = queuedCount
        self.runningCount = runningCount
        self.failedCount = failedCount
        self.completedCount = completedCount
        self.offlineQueuedCount = offlineQueuedCount
    }
}

@MainActor
@Observable
public final class HomeFeatureModel {
    public var activeProfile: ServerProfile?
    public var snapshot = HomeSnapshot()
    public var downloads: [DownloadRecord] = []
    public var offlineState = OfflineState(connectivity: .online)
    public var storageSummary = LocalStorageSummary()
    public var isLoading = false
    public var staleMessage: String?

    private let services: RommioServices

    public init(services: RommioServices) {
        self.services = services
    }

    public var queueSummary: DownloadQueueSummary {
        downloadQueueSummary(records: activeDownloads, offlineState: offlineState)
    }

    public var activeDownloads: [DownloadRecord] {
        downloads.filter { record in
            record.status == .queued || record.status == .running || record.status == .failed
        }
    }

    public var hasRenderableContent: Bool {
        !snapshot.continuePlaying.isEmpty ||
        !snapshot.recentlyAdded.isEmpty ||
        !snapshot.highlightedCollections.isEmpty ||
        !activeDownloads.isEmpty
    }

    public func refresh(refreshCatalog: Bool) async {
        if isLoading { return }
        isLoading = true
        defer { isLoading = false }

        await loadCachedState()
        var errors: [String] = []

        if refreshCatalog {
            do {
                try await services.libraryRepository.refreshActiveProfile(force: true)
            } catch {
                errors.append(error.localizedDescription)
            }
        }

        do {
            if let cached = try await services.libraryRepository.cachedHome() {
                snapshot = cached
            } else if !refreshCatalog {
                snapshot = try await services.libraryRepository.loadHome()
            }
        } catch {
            errors.append(error.localizedDescription)
        }

        do {
            downloads = try await services.downloadQueue.records(profileID: activeProfile?.id)
        } catch {
            errors.append(error.localizedDescription)
        }

        do {
            try await refreshProfileState()
        } catch {
            errors.append(error.localizedDescription)
        }
        staleMessage = errors.isEmpty ? nil : errors.joined(separator: "\n")
    }

    private func loadCachedState() async {
        do {
            try await refreshProfileState()
            if let cached = try await services.libraryRepository.cachedHome() {
                snapshot = cached
            }
            downloads = try await services.downloadQueue.records(profileID: activeProfile?.id)
        } catch {
            staleMessage = error.localizedDescription
        }
    }

    private func refreshProfileState() async throws {
        activeProfile = try await services.profileStore.activeProfile()
        if let activeProfile {
            offlineState = try await services.offlineStore.load(profileID: activeProfile.id)
        } else {
            offlineState = OfflineState(connectivity: services.networkMonitor.connectivity)
        }
        offlineState.connectivity = services.networkMonitor.connectivity
        storageSummary = localStorageSummary(libraryStore: services.libraryStore, downloads: downloads, offlineState: offlineState)
    }
}

@MainActor
@Observable
public final class DownloadsFeatureModel {
    public var activeProfile: ServerProfile?
    public var records: [DownloadRecord] = []
    public var offlineState = OfflineState(connectivity: .online)
    public var inventory: [CoreInventoryEntry] = []
    public var isLoading = false
    public var staleMessage: String?

    private let services: RommioServices
    private let coreCatalog: CoreCatalog
    private let coreInstaller: any CoreInstalling
    private let coreBundle: Bundle

    public init(
        services: RommioServices,
        coreCatalog: CoreCatalog,
        coreInstaller: any CoreInstalling,
        coreBundle: Bundle
    ) {
        self.services = services
        self.coreCatalog = coreCatalog
        self.coreInstaller = coreInstaller
        self.coreBundle = coreBundle
    }

    public var activeRecords: [DownloadRecord] {
        records.filter { $0.status == .queued || $0.status == .running }
    }

    public var recentRecords: [DownloadRecord] {
        records.filter { $0.status == .failed || $0.status == .completed || $0.status == .canceled }
    }

    public var queueSummary: DownloadQueueSummary {
        downloadQueueSummary(records: records, offlineState: offlineState)
    }

    public var trackedCount: Int {
        records.count
    }

    public var runtimeSummary: RuntimeInventorySummary {
        RuntimeInventorySummary(
            shippedCount: inventory.filter { $0.provisioningStatus != .missingCoreNotShipped }.count,
            provisionedCount: inventory.filter { $0.provisioningStatus == .ready }.count,
            failedCount: inventory.filter { $0.provisioningStatus == .failedCoreInstall }.count,
            blockedCount: inventory.filter { $0.availabilityStatus == .blockedByValidation }.count
        )
    }

    public func refresh() async {
        if isLoading { return }
        isLoading = true
        defer { isLoading = false }

        var errors: [String] = []
        do {
            activeProfile = try await services.profileStore.activeProfile()
            if let activeProfile {
                offlineState = try await services.offlineStore.load(profileID: activeProfile.id)
            } else {
                offlineState = OfflineState(connectivity: services.networkMonitor.connectivity)
            }
            offlineState.connectivity = services.networkMonitor.connectivity
        } catch {
            errors.append(error.localizedDescription)
        }

        do {
            records = try await services.downloadQueue.records(profileID: activeProfile?.id)
        } catch {
            errors.append(error.localizedDescription)
        }

        inventory = coreInstaller.inventory(
            catalog: coreCatalog,
            libraryStore: services.libraryStore,
            bundle: coreBundle
        )

        staleMessage = errors.isEmpty ? nil : errors.joined(separator: "\n")
    }

    public func retry(recordID: String) async {
        do {
            try await services.downloadQueue.retry(recordID: recordID)
            await refresh()
        } catch {
            staleMessage = error.localizedDescription
        }
    }

    public func downloadNow(recordID: String) async {
        do {
            try await services.downloadQueue.retry(recordID: recordID)
            await refresh()
        } catch {
            staleMessage = error.localizedDescription
        }
    }

    public func cancel(recordID: String) async {
        do {
            try await services.downloadQueue.cancel(recordID: recordID)
            await refresh()
        } catch {
            staleMessage = error.localizedDescription
        }
    }

    public func deleteLocalContent(recordID: String) async {
        do {
            try await services.downloadQueue.deleteLocalContent(recordID: recordID)
            await refresh()
        } catch {
            staleMessage = error.localizedDescription
        }
    }
}

@MainActor
@Observable
public final class SettingsFeatureModel {
    public var activeProfile: ServerProfile?
    public var profiles: [ServerProfile] = []
    public var offlineState = OfflineState(connectivity: .online)
    public var preferences = PlayerControlsPreferences()
    public var runtimeInventory: [CoreInventoryEntry] = []
    public var storageSummary = LocalStorageSummary()
    public var managedLibraryPath = ""
    public var isLoading = false
    public var staleMessage: String?

    private let services: RommioServices
    private let coreCatalog: CoreCatalog
    private let coreInstaller: any CoreInstalling
    private let coreBundle: Bundle
    @ObservationIgnored
    private var reconfigureAction: (@MainActor () async -> Void)?
    @ObservationIgnored
    private var reauthenticateAction: (@MainActor () async -> Void)?
    @ObservationIgnored
    private var signOutAction: (@MainActor () async -> Void)?
    @ObservationIgnored
    private var activateProfileAction: (@MainActor (ServerProfile) async -> Void)?
    @ObservationIgnored
    private var deleteProfileAction: (@MainActor (ServerProfile) async -> Void)?

    public init(
        services: RommioServices,
        coreCatalog: CoreCatalog,
        coreInstaller: any CoreInstalling,
        coreBundle: Bundle
    ) {
        self.services = services
        self.coreCatalog = coreCatalog
        self.coreInstaller = coreInstaller
        self.coreBundle = coreBundle
    }

    public func configureActions(
        onReconfigure: @escaping @MainActor () async -> Void,
        onReauthenticate: @escaping @MainActor () async -> Void,
        onSignOut: @escaping @MainActor () async -> Void,
        onActivateProfile: @escaping @MainActor (ServerProfile) async -> Void,
        onDeleteProfile: @escaping @MainActor (ServerProfile) async -> Void
    ) {
        reconfigureAction = onReconfigure
        reauthenticateAction = onReauthenticate
        signOutAction = onSignOut
        activateProfileAction = onActivateProfile
        deleteProfileAction = onDeleteProfile
    }

    public func refresh() async {
        if isLoading { return }
        isLoading = true
        defer { isLoading = false }

        var errors: [String] = []
        do {
            profiles = try await services.profileStore.listProfiles()
                .sorted { $0.updatedAt > $1.updatedAt }
            activeProfile = try await services.profileStore.activeProfile()
            if let activeProfile {
                offlineState = try await services.offlineStore.load(profileID: activeProfile.id)
            } else {
                offlineState = OfflineState(connectivity: services.networkMonitor.connectivity)
            }
            offlineState.connectivity = services.networkMonitor.connectivity
        } catch {
            errors.append(error.localizedDescription)
        }

        do {
            preferences = try await services.playerControlsRepository.preferences()
        } catch {
            errors.append(error.localizedDescription)
        }

        do {
            let downloads = try await services.downloadQueue.records(profileID: activeProfile?.id)
            storageSummary = localStorageSummary(
                libraryStore: services.libraryStore,
                downloads: downloads,
                offlineState: offlineState
            )
        } catch {
            errors.append(error.localizedDescription)
        }

        managedLibraryPath = services.libraryStore.rootDirectory.path

        runtimeInventory = coreInstaller.inventory(
            catalog: coreCatalog,
            libraryStore: services.libraryStore,
            bundle: coreBundle
        )

        staleMessage = errors.isEmpty ? nil : errors.joined(separator: "\n")
    }

    public func setTouchControlsEnabled(_ enabled: Bool) async { await updatePreference { try await $0.setTouchControlsEnabled(enabled) } }
    public func setAutoHideTouchOnController(_ enabled: Bool) async { await updatePreference { try await $0.setAutoHideTouchOnController(enabled) } }
    public func setRumbleToDeviceEnabled(_ enabled: Bool) async { await updatePreference { try await $0.setRumbleToDeviceEnabled(enabled) } }
    public func setOLEDBlackModeEnabled(_ enabled: Bool) async { await updatePreference { try await $0.setOLEDBlackModeEnabled(enabled) } }
    public func setConsoleColorsEnabled(_ enabled: Bool) async { await updatePreference { try await $0.setConsoleColorsEnabled(enabled) } }

    public func reconfigureServerAccess() async {
        guard let reconfigureAction else { return }
        await reconfigureAction()
        await refresh()
    }

    public func reauthenticate() async {
        guard let reauthenticateAction else { return }
        await reauthenticateAction()
        await refresh()
    }

    public func signOut() async {
        guard let signOutAction else { return }
        await signOutAction()
        await refresh()
    }

    public func activateProfile(_ profile: ServerProfile) async {
        guard let activateProfileAction else { return }
        await activateProfileAction(profile)
        await refresh()
    }

    public func deleteProfile(_ profile: ServerProfile) async {
        guard let deleteProfileAction else { return }
        await deleteProfileAction(profile)
        await refresh()
    }

    private func updatePreference(
        _ operation: @escaping (PlayerControlsRepository) async throws -> Void
    ) async {
        do {
            try await operation(services.playerControlsRepository)
            preferences = try await services.playerControlsRepository.preferences()
        } catch {
            staleMessage = error.localizedDescription
        }
    }
}

private func downloadQueueSummary(
    records: [DownloadRecord],
    offlineState: OfflineState
) -> DownloadQueueSummary {
    DownloadQueueSummary(
        queuedCount: records.count { $0.status == .queued },
        runningCount: records.count { $0.status == .running },
        failedCount: records.count { $0.status == .failed },
        completedCount: records.count { $0.status == .completed },
        offlineQueuedCount: offlineState.connectivity == .offline ? records.count { $0.status == .queued } : 0
    )
}

private func localStorageSummary(
    libraryStore: AppManagedLibraryStore,
    downloads: [DownloadRecord],
    offlineState: OfflineState
) -> LocalStorageSummary {
    let installedRecords = downloads.filter {
        guard let localPath = $0.localPath else { return false }
        return FileManager.default.fileExists(atPath: localPath)
    }

    return LocalStorageSummary(
        installedGameCount: Set(installedRecords.map(\.romID)).count,
        installedFileCount: installedRecords.count,
        totalBytes: directorySize(at: libraryStore.rootDirectory),
        cacheBytes: offlineState.cacheBytes
    )
}

private func directorySize(at url: URL) -> Int64 {
    guard let enumerator = FileManager.default.enumerator(
        at: url,
        includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey],
        options: [.skipsHiddenFiles]
    ) else {
        return 0
    }

    var total: Int64 = 0
    for case let fileURL as URL in enumerator {
        let values = try? fileURL.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
        guard values?.isRegularFile == true, let fileSize = values?.fileSize else { continue }
        total += Int64(fileSize)
    }
    return total
}
