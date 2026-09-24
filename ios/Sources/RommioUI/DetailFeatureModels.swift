import Foundation
import Observation
import RommioContract
import RommioFoundation
import RommioPlayerKit

enum GameDetailActionKind: String, Hashable, Sendable {
    case play
    case downloadNow
    case queue
    case retry
    case cancel
    case deleteLocal
    case retryCoreSetup

    public var title: String {
        switch self {
        case .play:
            "Play"
        case .downloadNow:
            "Download now"
        case .queue:
            "Add to queue"
        case .retry:
            "Retry"
        case .cancel:
            "Cancel"
        case .deleteLocal:
            "Delete local"
        case .retryCoreSetup:
            "Retry core setup"
        }
    }
}

struct GameDetailAction: Hashable, Sendable {
    public let kind: GameDetailActionKind
    public let recordID: String?
    public let enabled: Bool
    /// A label for this state of the action ("Download active"), else the kind's title.
    public let label: String?

    public init(kind: GameDetailActionKind, recordID: String? = nil, enabled: Bool = true, label: String? = nil) {
        self.kind = kind
        self.recordID = recordID
        self.enabled = enabled
        self.label = label
    }

    public var title: String { label ?? kind.title }
}

/// Where a game's file stands on this device, in Android's Game page words
/// (docs/theme-layouts.md §4: Game page status is owned by Android).
enum GameDownloadStatus: Hashable, Sendable {
    case onDevice, downloading, queued, failed, notDownloaded

    init(localPath: String?, record: DownloadRecord?) {
        if localPath != nil { self = .onDevice; return }
        switch record?.status {
        case .running: self = .downloading
        case .queued: self = .queued
        case .failed: self = .failed
        default: self = .notDownloaded
        }
    }

    var title: String {
        switch self {
        case .onDevice: "On this device"
        case .downloading: "Downloading"
        case .queued: "Queued"
        case .failed: "Download failed"
        case .notDownloaded: "Not downloaded"
        }
    }

    var tone: StatusTone {
        switch self {
        case .onDevice: .ok
        case .downloading, .queued: .live
        case .failed: .bad
        case .notDownloaded: .neutral
        }
    }
}

struct GameFileActionDeck: Hashable, Sendable {
    public let primary: GameDetailAction?
    public let secondary: [GameDetailAction]

    public init(
        primary: GameDetailAction?,
        secondary: [GameDetailAction]
    ) {
        self.primary = primary
        self.secondary = secondary
    }
}

extension CoreResolution {
    /// Short warning for badges; nil when the file can be played. A bundled
    /// core that only needs first-run setup provisions itself during Play, so it
    /// is not a problem worth badging.
    var capabilityLabel: String? {
        switch capability {
        case .ready:
            return nil
        case .missingBIOS:
            return "Missing BIOS"
        case .missingCore:
            if provisioningStatus == .failedCoreInstall { return "Core setup failed" }
            return canAutoProvision || provisioningStatus == .missingCoreInstallable ? nil : "Core unavailable"
        case .unsupported:
            return "Unsupported"
        }
    }
}

struct GameDetailFileState: Identifiable, Hashable, Sendable {
    public let rom: RomDTO
    public let file: RomFileDTO
    public let controlProfile: PlatformControlProfile
    public let resolution: CoreResolution
    public let localPath: String?
    public let downloadRecord: DownloadRecord?
    public let actionDeck: GameFileActionDeck

    public var id: Int { file.id }

    public var playabilityLabel: String {
        resolution.capabilityLabel ?? controlProfile.supportTier.title
    }

    public var localFileName: String? {
        localPath.map { URL(fileURLWithPath: $0).lastPathComponent }
    }

    /// "SFC · 1.5 MB".
    public var fileMetaLine: String {
        PlayFormat.line([file.effectiveFileExtension.isEmpty ? "FILE" : file.effectiveFileExtension.uppercased(), PlayFormat.bytes(file.fileSizeBytes)])
    }

    var downloadStatus: GameDownloadStatus { GameDownloadStatus(localPath: localPath, record: downloadRecord) }

    public var availabilityLabel: String { downloadStatus.title }
}

struct GameDetailHeroPresentation: Hashable, Sendable {
    let statusText: String
    var statusTone: StatusTone = .neutral
    let supportMessage: String?
    let summaryText: String?
    let fileCountText: String
}

struct GameDetailFileOption: Hashable, Sendable, Identifiable {
    let id: Int
    let title: String
}

struct GameDetailSelectedFilePresentation: Hashable, Sendable {
    let title: String
    let metaLine: String
    let availabilityLabel: String
    var availabilityTone: StatusTone = .neutral
    let playabilityLabel: String
    let downloadStatusText: String?
    let downloadProgressValue: Double?
    let progressDetailText: String?
    let errorMessage: String?
    let readinessMessage: String?
    let localFileName: String?
}

struct GameDetailActionDeckPresentation: Hashable, Sendable {
    let subtitle: String
    let badge: String
    let recommendedCoreName: String?
    let downloadProgressValue: Double?
    let progressDetailText: String?
    let errorMessage: String?
    let notes: [String]
    let primary: GameDetailAction?
    let secondary: [GameDetailAction]
}

struct GameDetailPreservedContentPresentation: Hashable, Sendable {
    let title: String
    let message: String
}

struct GameDetailStateSummaryPresentation: Hashable, Sendable {
    let statusText: String
    let sourceText: String?
    let updatedText: String?
    let countSummary: String
    let problemText: String?
    let canBrowse: Bool
}

@MainActor
@Observable
final class LibraryFeatureModel {
    var platforms: [PlatformDTO] = []
    var isLoading = false
    var staleMessage: String?
    /// Nothing could be loaded (Android's copy): shown in the empty state.
    var errorMessage: String?

    private let services: RommioServices
    private let resolveProfile: (String) -> PlatformControlProfile

    init(
        services: RommioServices,
        resolveProfile: @escaping (String) -> PlatformControlProfile
    ) {
        self.services = services
        self.resolveProfile = resolveProfile
    }

    var sections: [PlatformSupportSection] {
        groupedPlatformSections(platforms: platforms, resolveProfile: resolveProfile)
    }

    func refresh(forceRemote: Bool) async {
        if isLoading { return }
        isLoading = true
        defer { isLoading = false }

        var errors: [String] = []

        do {
            let cached = try await services.libraryRepository.cachedPlatforms()
            if !cached.isEmpty {
                platforms = cached
            }
        } catch {
            errors.append(error.localizedDescription)
        }

        do {
            let loaded = try await services.libraryRepository.loadPlatforms()
            platforms = loaded
        } catch {
            errors.append(error.localizedDescription)
        }

        staleMessage = errors.isEmpty || platforms.isEmpty ? nil : errors.joined(separator: "\n")
        errorMessage = loadErrorMessage(empty: platforms.isEmpty, errors: errors, offline: services.networkMonitor.connectivity == .offline,
                                        offlineText: "You're offline. Your library appears here after this server syncs once.",
                                        fallback: "Couldn't load the library.")
    }
}

@MainActor
@Observable
final class CollectionsFeatureModel {
    var collections: [RommCollectionDTO] = []
    var isLoading = false
    var staleMessage: String?

    private let services: RommioServices

    init(services: RommioServices) {
        self.services = services
    }

    var sections: [CollectionTypeSection] {
        groupedCollectionSections(collections: collections)
    }

    func refresh(forceRemote: Bool) async {
        if isLoading { return }
        isLoading = true
        defer { isLoading = false }

        var errors: [String] = []

        do {
            let cached = try await services.libraryRepository.cachedCollections()
            if !cached.isEmpty {
                collections = cached
            }
        } catch {
            errors.append(error.localizedDescription)
        }

        do {
            let loaded = try await services.libraryRepository.loadCollections()
            collections = loaded
        } catch {
            errors.append(error.localizedDescription)
        }

        staleMessage = errors.isEmpty || collections.isEmpty ? nil : errors.joined(separator: "\n")
    }
}

@MainActor
@Observable
final class PlatformDetailFeatureModel {
    let platform: PlatformDTO

    var roms: [RomDTO] = []
    var isLoading = false
    var staleMessage: String?
    /// Nothing could be loaded (Android's copy): shown in the empty state.
    var errorMessage: String?

    private let services: RommioServices
    private let resolveProfile: (String) -> PlatformControlProfile

    init(
        platform: PlatformDTO,
        services: RommioServices,
        resolveProfile: @escaping (String) -> PlatformControlProfile
    ) {
        self.platform = platform
        self.services = services
        self.resolveProfile = resolveProfile
    }

    var sections: [RomSupportSection] {
        groupedRomSections(roms: roms, context: .platformDetail, resolveProfile: resolveProfile)
    }

    func refresh(forceRemote: Bool) async {
        if isLoading { return }
        isLoading = true
        defer { isLoading = false }

        var errors: [String] = []

        do {
            let cached = try await services.libraryRepository.cachedRoms(platformID: platform.id)
            if !cached.isEmpty {
                roms = cached
            }
        } catch {
            errors.append(error.localizedDescription)
        }

        do {
            let loaded = try await services.libraryRepository.loadRoms(platformID: platform.id)
            roms = loaded
        } catch {
            errors.append(error.localizedDescription)
        }

        staleMessage = errors.isEmpty || roms.isEmpty ? nil : errors.joined(separator: "\n")
        errorMessage = loadErrorMessage(empty: roms.isEmpty, errors: errors, offline: services.networkMonitor.connectivity == .offline,
                                        offlineText: "Offline. This platform will appear after the profile sync completes.",
                                        fallback: "Unable to load this platform.")
    }
}

@MainActor
@Observable
final class CollectionDetailFeatureModel {
    let collection: RommCollectionDTO

    var roms: [RomDTO] = []
    var isLoading = false
    var staleMessage: String?
    /// Nothing could be loaded (Android's copy): shown in the empty state.
    var errorMessage: String?

    private let services: RommioServices
    private let resolveProfile: (String) -> PlatformControlProfile

    init(
        collection: RommCollectionDTO,
        services: RommioServices,
        resolveProfile: @escaping (String) -> PlatformControlProfile
    ) {
        self.collection = collection
        self.services = services
        self.resolveProfile = resolveProfile
    }

    var sections: [RomSupportSection] {
        groupedRomSections(roms: roms, context: .collectionDetail, resolveProfile: resolveProfile)
    }

    func refresh(forceRemote: Bool) async {
        if isLoading { return }
        isLoading = true
        defer { isLoading = false }

        var errors: [String] = []

        do {
            let cached = try await services.libraryRepository.cachedRoms(collection: collection)
            if !cached.isEmpty {
                roms = cached
            }
        } catch {
            errors.append(error.localizedDescription)
        }

        do {
            let loaded = try await services.libraryRepository.loadRoms(collection: collection)
            roms = loaded
        } catch {
            errors.append(error.localizedDescription)
        }

        staleMessage = errors.isEmpty || roms.isEmpty ? nil : errors.joined(separator: "\n")
        errorMessage = loadErrorMessage(empty: roms.isEmpty, errors: errors, offline: services.networkMonitor.connectivity == .offline,
                                        offlineText: "Offline. This collection will appear after the profile sync completes.",
                                        fallback: "Unable to load this collection.")
    }
}

@MainActor
@Observable
final class GameDetailFeatureModel {
    var rom: RomDTO
    var offlineState = OfflineState(connectivity: .online)
    var fileStates: [GameDetailFileState] = []
    var selectedFileID: Int?
    var stateBrowser = GameStateBrowser()
    var stateBrowserPresented = false
    /// This game's saves from the progress store (docs/play-data-local.md §3, "One game").
    var saves = GameSaves.empty
    /// Conflicted references of this game (Conflict sheet).
    var conflicts: [ProgressConflict] = []
    /// The game has progress in the store (its saves come from the reader, not the legacy browser).
    var usesProgressStore = false
    var savedProgressPresented = false
    var conflictPresented = false
    var isLoading = false
    var staleMessage: String?

    private let services: RommioServices
    private let coreCatalog: CoreCatalog
    private let coreBundle: Bundle
    private let resolveProfile: (String) -> PlatformControlProfile
    private let onQueueDownload: @MainActor (RomDTO, RomFileDTO) async -> Void
    private let onRetryCoreProvisioning: @MainActor (RomDTO, RomFileDTO) async -> Void
    private let onPlay: @MainActor (RomDTO, RomFileDTO) async -> Void
    private let onRetryDownload: @MainActor (String) async -> Void
    private let onCancelDownload: @MainActor (String) async -> Void
    private let onDeleteLocalContent: @MainActor (String) async -> Void

    init(
        rom: RomDTO,
        services: RommioServices,
        coreCatalog: CoreCatalog,
        coreBundle: Bundle,
        resolveProfile: @escaping (String) -> PlatformControlProfile,
        onQueueDownload: @escaping @MainActor (RomDTO, RomFileDTO) async -> Void,
        onRetryCoreProvisioning: @escaping @MainActor (RomDTO, RomFileDTO) async -> Void,
        onPlay: @escaping @MainActor (RomDTO, RomFileDTO) async -> Void,
        onRetryDownload: @escaping @MainActor (String) async -> Void,
        onCancelDownload: @escaping @MainActor (String) async -> Void,
        onDeleteLocalContent: @escaping @MainActor (String) async -> Void
    ) {
        self.rom = rom
        self.services = services
        self.coreCatalog = coreCatalog
        self.coreBundle = coreBundle
        self.resolveProfile = resolveProfile
        self.onQueueDownload = onQueueDownload
        self.onRetryCoreProvisioning = onRetryCoreProvisioning
        self.onPlay = onPlay
        self.onRetryDownload = onRetryDownload
        self.onCancelDownload = onCancelDownload
        self.onDeleteLocalContent = onDeleteLocalContent
    }

    var controlProfile: PlatformControlProfile {
        resolveProfile(rom.platformSlug)
    }

    var selectedFileState: GameDetailFileState? {
        if let selectedFileID,
           let selected = fileStates.first(where: { $0.file.id == selectedFileID }) {
            return selected
        }
        return preferredFileState(from: fileStates)
    }

    var heroPresentation: GameDetailHeroPresentation {
        let trimmedSummary = rom.summary?.trimmingCharacters(in: .whitespacesAndNewlines)
        let status = selectedFileState?.downloadStatus ?? .notDownloaded
        return GameDetailHeroPresentation(
            statusText: status.title,
            statusTone: status.tone,
            supportMessage: controlProfile.supportTier == .controllerSupported ? controlProfile.controllerFallbackMessage : nil,
            summaryText: trimmedSummary?.isEmpty == true ? nil : trimmedSummary,
            fileCountText: "\(PlayFormat.count(rom.files.count, "file")) available"
        )
    }

    var showsSegmentedFileSelection: Bool {
        fileStates.count > 1
    }

    var fileSelectionOptions: [GameDetailFileOption] {
        let labels = compactFileLabels(states: fileStates)
        return fileStates.map { state in
            GameDetailFileOption(id: state.file.id, title: labels[state.file.id] ?? state.file.fileName)
        }
    }

    var selectedFilePresentation: GameDetailSelectedFilePresentation? {
        guard let state = selectedFileState else { return nil }

        let progressDetailText: String?
        let downloadStatusText: String?
        let downloadProgressValue: Double?
        if let record = state.downloadRecord,
           record.status == .running || record.status == .queued {
            downloadProgressValue = Double(record.progressPercent) / 100
            progressDetailText = Self.progressDetail(record)
            downloadStatusText = state.downloadStatus.title
        } else {
            downloadProgressValue = nil
            progressDetailText = nil
            downloadStatusText = nil
        }

        return GameDetailSelectedFilePresentation(
            title: state.file.fileName,
            metaLine: state.fileMetaLine,
            availabilityLabel: state.availabilityLabel,
            availabilityTone: state.downloadStatus.tone,
            playabilityLabel: state.playabilityLabel,
            downloadStatusText: downloadStatusText,
            downloadProgressValue: downloadProgressValue,
            progressDetailText: progressDetailText,
            errorMessage: state.downloadRecord?.lastError,
            readinessMessage: selectedFileReadinessMessage(for: state),
            localFileName: state.localFileName
        )
    }

    var actionDeckPresentation: GameDetailActionDeckPresentation? {
        guard let state = selectedFileState else { return nil }

        let badge = actionDeckBadge(for: state)
        let subtitle = state.resolution.message ?? "Choose the next action for this title."
        let recommendedCoreName = state.resolution.runtimeProfile?.displayName

        let downloadProgressValue: Double?
        let progressDetailText: String?
        let errorMessage: String?
        if let record = state.downloadRecord,
           record.status == .running || record.status == .queued {
            downloadProgressValue = Double(record.progressPercent) / 100
            progressDetailText = Self.progressDetail(record)
            errorMessage = nil
        } else {
            downloadProgressValue = nil
            progressDetailText = nil
            errorMessage = state.downloadRecord?.lastError
        }

        return GameDetailActionDeckPresentation(
            subtitle: subtitle,
            badge: badge,
            recommendedCoreName: recommendedCoreName,
            downloadProgressValue: downloadProgressValue,
            progressDetailText: progressDetailText,
            errorMessage: errorMessage,
            notes: actionDeckNotes(for: state),
            primary: state.actionDeck.primary,
            secondary: state.actionDeck.secondary
        )
    }

    /// "42% · 1.2 MB of 3.4 MB".
    static func progressDetail(_ record: DownloadRecord) -> String {
        "\(record.progressPercent)% · \(PlayFormat.bytes(record.bytesDownloaded)) of \(PlayFormat.bytes(max(record.totalBytes, record.fileSizeBytes)))"
    }

    var preservedContentPresentation: GameDetailPreservedContentPresentation? {
        guard let staleMessage, hasRenderableContent else { return nil }
        return GameDetailPreservedContentPresentation(
            title: "Cached content preserved",
            message: staleMessage
        )
    }

    var stateSummaryPresentation: GameDetailStateSummaryPresentation? {
        guard supportsExternalStateBrowser else { return nil }
        let saveSlotCount = stateBrowser.saveSlots.count
        let snapshotCount = stateBrowser.snapshots.count
        let countSummary = stateCountSummary(saveSlots: saveSlotCount, snapshots: snapshotCount)
        let resume = stateBrowser.resume
        let problemText: String?
        switch resume?.statusKind {
        case .conflict:
            problemText = "Use Browse states to review resume choices."
        case .cloudAvailable:
            problemText = "A newer cloud resume is available."
        case .error:
            problemText = "Resume sync needs attention."
        case .pendingUpload:
            problemText = offlineState.connectivity == .offline ? "Waiting for a connection to sync." : nil
        default:
            problemText = nil
        }
        return GameDetailStateSummaryPresentation(
            statusText: resume?.primaryStatusMessage ?? "No resume state yet.",
            sourceText: resume?.sourceDeviceName.flatMap { source in
                resume?.sourceOrigin == .remoteDevice ? "From \(source)" : nil
            },
            updatedText: resume?.updatedAtEpochMS.map { "Updated \(formatStateTimestamp($0))" },
            countSummary: countSummary,
            problemText: problemText,
            canBrowse: true
        )
    }

    func refresh(forceRemote: Bool) async {
        if isLoading { return }
        isLoading = true
        defer { isLoading = false }

        var errors: [String] = []

        do {
            if let profile = try await services.profileStore.activeProfile() {
                offlineState = try await services.offlineStore.load(profileID: profile.id)
            } else {
                offlineState = OfflineState(connectivity: services.networkMonitor.connectivity)
            }
            offlineState.connectivity = services.networkMonitor.connectivity
        } catch {
            errors.append(error.localizedDescription)
        }

        do {
            if let cached = try await services.libraryRepository.cachedRom(id: rom.id) {
                rom = cached
            }
        } catch {
            errors.append(error.localizedDescription)
        }

        do {
            let loaded = try await services.libraryRepository.loadRom(id: rom.id)
            rom = loaded
        } catch {
            errors.append(error.localizedDescription)
        }

        do {
            let records = try await services.downloadQueue.records(profileID: try await services.profileStore.activeProfile()?.id)
            fileStates = buildFileStates(from: records)
            selectedFileID = preferredFileState(from: fileStates)?.file.id
        } catch {
            errors.append(error.localizedDescription)
            fileStates = buildFileStates(from: [])
            selectedFileID = preferredFileState(from: fileStates)?.file.id
        }

        await refreshSaves()
        if !usesProgressStore { await refreshStateBrowser() }

        staleMessage = errors.isEmpty || !hasRenderableContent ? nil : errors.joined(separator: "\n")
    }

    /// Reads this game's saves from the current store: the install's content
    /// generation first, else every generation of the ROM.
    func refreshSaves() async {
        guard let account = await services.activeProgressAccount() else { saves = .empty; conflicts = []; usesProgressStore = false; return }
        await services.playData.flush()
        let state = selectedFileState
        do {
            saves = try await services.savedProgress.gameSaves(account, romId: Int64(rom.id), fileId: state.map { Int64($0.file.id) },
                                                              contentSha256: state?.downloadRecord?.contentSHA256)
            conflicts = try await services.savedProgress.conflicts(account, romId: Int64(rom.id))
            usesProgressStore = !saves.isEmpty || !conflicts.isEmpty || runsOnProgressStore
        } catch {
            // Storage busy (backup or recovery): keep what is shown.
        }
    }

    func renameSave(_ entry: SaveEntry, to name: String) async {
        guard let label = try? await services.savedProgress.rename(entry.key, to: name) else {
            await refreshSaves(); return
        }
        func apply(_ item: SaveEntry) -> SaveEntry { item.key == entry.key ? relabelled(item, label) : item }
        saves = GameSaves(resume: saves.resume.map(apply), resumeConflict: saves.resumeConflict, saveStates: saves.saveStates.map(apply),
                          history: saves.history.map(apply), gameSaveData: saves.gameSaveData)
    }


    var hasRenderableContent: Bool {
        !fileStates.isEmpty
    }

    /// Observe local transfer completion without refetching remote metadata each second.
    func monitorDownloads() async {
        while !Task.isCancelled {
            do {
                let profileID = try await services.profileStore.activeProfile()?.id
                let records = try await services.downloadQueue.records(profileID: profileID)
                guard !Task.isCancelled else { return }
                let updated = buildFileStates(from: records)
                if updated != fileStates {
                    fileStates = updated
                    selectedFileID = preferredFileState(from: updated)?.file.id
                }
                try await Task.sleep(for: .seconds(1))
            } catch is CancellationError {
                return
            } catch {
                // Preserve rendered content; an explicit refresh exposes persistent failures.
                do { try await Task.sleep(for: .seconds(2)) } catch { return }
            }
        }
    }

    func play(fileID: Int) async {
        guard let file = rom.files.first(where: { $0.id == fileID }) else { return }
        await onPlay(rom, file)
        await refresh(forceRemote: false)
    }

    func selectFile(_ fileID: Int) async {
        guard fileStates.contains(where: { $0.file.id == fileID }) else { return }
        selectedFileID = fileID
        await refreshSaves()
        if !usesProgressStore { await refreshStateBrowser() }
    }

    func retryCoreProvisioning(fileID: Int) async {
        guard let file = rom.files.first(where: { $0.id == fileID }) else { return }
        await onRetryCoreProvisioning(rom, file)
        await refresh(forceRemote: false)
    }

    func perform(_ action: GameDetailAction, fileID: Int) async {
        guard let fileState = fileStates.first(where: { $0.file.id == fileID }) else { return }
        guard action.enabled else { return }

        switch action.kind {
        case .play:
            await play(fileID: fileID)
            return
        case .downloadNow, .queue:
            await onQueueDownload(rom, fileState.file)
        case .retry:
            if let recordID = action.recordID {
                await onRetryDownload(recordID)
            }
        case .cancel:
            if let recordID = action.recordID {
                await onCancelDownload(recordID)
            }
        case .deleteLocal:
            if let recordID = action.recordID {
                await onDeleteLocalContent(recordID)
            } else if let localPath = fileState.localPath, FileManager.default.fileExists(atPath: localPath) {
                try? FileManager.default.removeItem(atPath: localPath)
            }
        case .retryCoreSetup:
            await retryCoreProvisioning(fileID: fileID)
            return
        }
        await refresh(forceRemote: false)
    }

    func playFromResume() async {
        guard let selectedFile = selectedFileState else { return }
        await play(fileID: selectedFile.file.id)
    }

    func playFromState(_ state: BrowsableGameState) async {
        guard let selectedFile = selectedFileState,
              (try? await services.profileStore.activeProfile()) != nil else { return }
        do {
            try await services.syncBridge.setPendingLaunchTarget(
                installation: InstalledROMReference(
                    romID: rom.id,
                    fileID: selectedFile.file.id,
                    platformSlug: rom.platformSlug,
                    romName: rom.displayName,
                    fileName: selectedFile.file.fileName
                ),
                state: state
            )
            await play(fileID: selectedFile.file.id)
        } catch {
            staleMessage = error.localizedDescription
        }
    }

    func deleteState(_ state: BrowsableGameState) async {
        guard let selectedFile = selectedFileState,
              let profile = try? await services.profileStore.activeProfile() else { return }
        do {
            try await services.syncBridge.deleteBrowsableState(
                profile: profile,
                installation: InstalledROMReference(
                    romID: rom.id,
                    fileID: selectedFile.file.id,
                    platformSlug: rom.platformSlug,
                    romName: rom.displayName,
                    fileName: selectedFile.file.fileName
                ),
                state: state
            )
            await refreshStateBrowser()
        } catch {
            staleMessage = error.localizedDescription
        }
    }

    private func buildFileStates(from records: [DownloadRecord]) -> [GameDetailFileState] {
        let recordsByFileID = Dictionary(uniqueKeysWithValues: records.filter { $0.romID == rom.id }.map { ($0.fileID, $0) })
        return rom.files.map { file in
            let record = recordsByFileID[file.id]
            let localPath = resolveLocalPath(for: file, record: record)
            let resolution = coreCatalog.resolve(rom: rom, file: file, libraryStore: services.libraryStore, bundle: coreBundle)
            let actionDeck = resolveActionDeck(
                file: file,
                record: record,
                localPath: localPath,
                resolution: resolution
            )
            return GameDetailFileState(
                rom: rom,
                file: file,
                controlProfile: controlProfile,
                resolution: resolution,
                localPath: localPath,
                downloadRecord: record,
                actionDeck: actionDeck
            )
        }
    }

    private func preferredFileState(from states: [GameDetailFileState]) -> GameDetailFileState? {
        if let selectedFileID,
           let existingSelection = states.first(where: { $0.file.id == selectedFileID }) {
            return existingSelection
        }
        return states.first(where: { $0.localPath != nil })
            ?? states.first(where: { $0.actionDeck.primary?.kind == .play })
            ?? states.first
    }

    private func resolveLocalPath(for file: RomFileDTO, record: DownloadRecord?) -> String? {
        if let record {
            return record.localPath.flatMap { FileManager.default.fileExists(atPath: $0) ? $0 : nil }
        }
        let libraryURL = services.libraryStore.romURL(platformSlug: rom.platformSlug, fileName: file.fileName)
        return FileManager.default.fileExists(atPath: libraryURL.path) ? libraryURL.path : nil
    }

    private func resolveActionDeck(
        file: RomFileDTO,
        record: DownloadRecord?,
        localPath: String?,
        resolution: CoreResolution
    ) -> GameFileActionDeck {
        let canPlay =
            localPath != nil &&
            resolution.capability != .unsupported &&
            resolution.capability != .missingBIOS &&
            (
                resolution.capability == .ready ||
                resolution.provisioningStatus == .missingCoreInstallable ||
                resolution.canRetryProvisioning
            )

        let primary: GameDetailAction?
        if canPlay {
            primary = GameDetailAction(kind: .play)
        } else if resolution.canRetryProvisioning {
            primary = GameDetailAction(kind: .retryCoreSetup)
        } else if let record, record.status == .running || record.status == .queued {
            primary = GameDetailAction(kind: .downloadNow, enabled: false, label: record.status == .running ? "Download active" : nil)
        } else if localPath == nil {
            primary = GameDetailAction(kind: .downloadNow)
        } else {
            primary = nil
        }

        var secondary: [GameDetailAction] = []
        if localPath == nil, record == nil {
            secondary.append(GameDetailAction(kind: .queue))
        }
        if let record, record.status == .queued || record.status == .running {
            secondary.append(GameDetailAction(kind: .cancel, recordID: record.id))
        }
        if let record, record.status == .failed || record.status == .canceled {
            secondary.append(GameDetailAction(kind: .retry, recordID: record.id))
        }
        if localPath != nil {
            secondary.append(GameDetailAction(kind: .deleteLocal, recordID: record?.id))
        }
        if resolution.canRetryProvisioning, primary?.kind != .retryCoreSetup {
            secondary.append(GameDetailAction(kind: .retryCoreSetup))
        }

        return GameFileActionDeck(primary: primary, secondary: secondary)
    }

    private func compactFileLabels(states: [GameDetailFileState]) -> [Int: String] {
        let extensions = states.map { $0.file.effectiveFileExtension.uppercased() }
        let uniqueExtensions = Set(extensions.filter { !$0.isEmpty })
        let useExtensions = uniqueExtensions.count == states.count
        return Dictionary(uniqueKeysWithValues: states.enumerated().map { index, state in
            let title: String
            if useExtensions {
                title = state.file.effectiveFileExtension.uppercased()
            } else {
                title = "#\(index + 1)"
            }
            return (state.file.id, title)
        })
    }

    private func selectedFileReadinessMessage(for state: GameDetailFileState) -> String? {
        if !state.resolution.missingBIOS.isEmpty {
            return "BIOS: \(state.resolution.missingBIOS.joined(separator: ", "))"
        }
        return state.resolution.message
    }

    private func actionDeckBadge(for state: GameDetailFileState) -> String {
        if state.actionDeck.primary?.kind == .play {
            return "Ready"
        }
        return state.resolution.capabilityLabel ?? state.controlProfile.supportTier.title
    }

    private func actionDeckNotes(for state: GameDetailFileState) -> [String] {
        var notes: [String] = []
        if !state.resolution.missingBIOS.isEmpty {
            notes.append("BIOS required: \(state.resolution.missingBIOS.joined(separator: ", "))")
        }
        if let localFileName = state.localFileName {
            notes.append("Local file: \(localFileName)")
        }
        return notes
    }

    /// The selected file plays on a runtime whose saves live in the progress store
    /// (the same runtimes the player launches with a progress session).
    private var runsOnProgressStore: Bool {
        guard let state = selectedFileState, state.localPath != nil, let runtimeID = state.resolution.runtimeProfile?.runtimeID else { return false }
        return runtimeID == "fceumm" || PinnedAppleCore.cartridgeRuntimes.contains(runtimeID) || PinnedAppleCore.candidateRuntimes.contains(runtimeID)
    }

    private var supportsExternalStateBrowser: Bool {
        guard let selectedFileState else { return false }
        return selectedFileState.localPath != nil && selectedFileState.resolution.runtimeProfile?.supportsSaveStates == true
    }

    private func refreshStateBrowser() async {
        guard supportsExternalStateBrowser,
              let selectedFile = selectedFileState?.file else {
            stateBrowser = GameStateBrowser()
            return
        }
        let installation = InstalledROMReference(
            romID: rom.id,
            fileID: selectedFile.id,
            platformSlug: rom.platformSlug,
            romName: rom.displayName,
            fileName: selectedFile.fileName
        )
        let activeProfile = try? await services.profileStore.activeProfile()
        if let activeProfile {
            do {
                stateBrowser = try await services.syncBridge.refreshStateBrowser(
                    profile: activeProfile,
                    installation: installation,
                    rom: rom,
                    runtimeID: selectedFileState?.resolution.runtimeProfile?.runtimeID ?? "",
                    connectivity: offlineState.connectivity
                )
                return
            } catch {
                staleMessage = error.localizedDescription
            }
        }
        do {
            stateBrowser = try services.syncBridge.cachedStateBrowser(
                profile: activeProfile,
                installation: installation,
                connectivity: offlineState.connectivity
            )
        } catch {
            staleMessage = error.localizedDescription
        }
    }
}

/// An error for a page that could load nothing (Android's copy): offline before
/// the first sync, else the failure.
func loadErrorMessage(empty: Bool, errors: [String], offline: Bool, offlineText: String, fallback: String) -> String? {
    guard empty, !errors.isEmpty else { return nil }
    return offline ? offlineText : (errors.first { !$0.isEmpty } ?? fallback)
}

private func stateCountSummary(saveSlots: Int, snapshots: Int) -> String {
    PlayFormat.line([PlayFormat.count(saveSlots, "save slot"), PlayFormat.count(snapshots, "snapshot")])
}

private func formatStateTimestamp(_ epochMS: Int64) -> String {
    let formatter = DateFormatter()
    formatter.dateStyle = .medium
    formatter.timeStyle = .short
    return formatter.string(from: Date(timeIntervalSince1970: TimeInterval(epochMS) / 1000))
}
