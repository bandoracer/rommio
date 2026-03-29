import Foundation
import Observation
import OSLog
import RommioContract
import RommioFoundation
import RommioPlayerKit

public struct PlayerPresentationState: Identifiable, Hashable, Sendable {
    public let id: UUID
    public let romID: Int
    public let fileID: Int
    public let romTitle: String
    public let platformSlug: String
    public let runtimeName: String
    public let supportsSaveStates: Bool

    public init(
        id: UUID = UUID(),
        romID: Int,
        fileID: Int,
        romTitle: String,
        platformSlug: String,
        runtimeName: String,
        supportsSaveStates: Bool
    ) {
        self.id = id
        self.romID = romID
        self.fileID = fileID
        self.romTitle = romTitle
        self.platformSlug = platformSlug
        self.runtimeName = runtimeName
        self.supportsSaveStates = supportsSaveStates
    }
}

public struct RuntimeInventorySummary: Hashable, Sendable {
    public let shippedCount: Int
    public let provisionedCount: Int
    public let failedCount: Int
    public let blockedCount: Int
}

public typealias PlayerEngineFactory = @Sendable () -> PlayerEngine

@MainActor
@Observable
public final class RommioAppModel {
    private let logger = Logger(subsystem: "io.github.mattsays.rommio.ios", category: "AppModel")
    public enum ShellTab: String, Hashable, CaseIterable, Sendable {
        case home
        case library
        case collections
        case downloads
        case settings
    }

    public var route: AppRoute = .gate
    public var selectedTab: ShellTab = .home

    public var serverURL: String = ""
    public var profileLabel: String = ""
    public var username: String = ""
    public var password: String = ""
    public var cloudflareClientID: String = ""
    public var cloudflareClientSecret: String = ""
    public var selectedEdgeAuthMode: EdgeAuthMode = .none
    public var isEdgeAuthModeOverridden = false

    public var profiles: [ServerProfile] = []
    public var activeProfile: ServerProfile?
    public var discoveryResult: AuthDiscoveryResult?
    public var serverAccessResult: ServerAccessResult?
    public var offlineState = OfflineState(connectivity: .online)
    public var activePlayer: PlayerFeatureModel?
    let homeFeature: HomeFeatureModel
    let libraryFeature: LibraryFeatureModel
    let collectionsFeature: CollectionsFeatureModel
    let downloadsFeature: DownloadsFeatureModel
    let settingsFeature: SettingsFeatureModel

    public var isBusy = false
    public var isRefreshing = false
    public var errorMessage: String?
    public var noticeMessage: String?

    @ObservationIgnored
    private let services: RommioServices
    @ObservationIgnored
    private let interactiveAuthenticator: InteractiveSessionAuthenticating?
    @ObservationIgnored
    private let coreCatalog: CoreCatalog
    @ObservationIgnored
    private let coreInstaller: any CoreInstalling
    @ObservationIgnored
    private let coreBundle: Bundle
    @ObservationIgnored
    private let playerEngineFactory: PlayerEngineFactory
    @ObservationIgnored
    private var activeInteractiveContextID: UUID?
    @ObservationIgnored
    private var downloadPollingTask: Task<Void, Never>?
    @ObservationIgnored
    private var provisioningRuntimeIDs: Set<String> = []

    public init(
        services: RommioServices,
        interactiveAuthenticator: InteractiveSessionAuthenticating? = nil,
        coreCatalog: CoreCatalog = BundledCoreCatalog(),
        coreInstaller: any CoreInstalling = BundledCoreInstaller(),
        coreBundle: Bundle = .main,
        playerEngineFactory: @escaping PlayerEngineFactory = { IOSLibretroPlayerEngine(bundle: .main) }
    ) {
        self.services = services
        self.interactiveAuthenticator = interactiveAuthenticator
        self.coreCatalog = coreCatalog
        self.coreInstaller = coreInstaller
        self.coreBundle = coreBundle
        self.playerEngineFactory = playerEngineFactory
        self.homeFeature = HomeFeatureModel(services: services)
        self.libraryFeature = LibraryFeatureModel(
            services: services,
            resolveProfile: { services.playerControlsRepository.resolveProfile(platformSlug: $0) }
        )
        self.collectionsFeature = CollectionsFeatureModel(services: services)
        self.downloadsFeature = DownloadsFeatureModel(
            services: services,
            coreCatalog: coreCatalog,
            coreInstaller: coreInstaller,
            coreBundle: coreBundle
        )
        self.settingsFeature = SettingsFeatureModel(
            services: services,
            coreCatalog: coreCatalog,
            coreInstaller: coreInstaller,
            coreBundle: coreBundle
        )
        self.offlineState.connectivity = services.networkMonitor.connectivity
        self.settingsFeature.configureActions(
            onReconfigure: { [weak self] in
                await self?.reconfigureSetup()
            },
            onReauthenticate: { [weak self] in
                await self?.reauthenticateFromSettings()
            },
            onSignOut: { [weak self] in
                await self?.signOut()
            },
            onActivateProfile: { [weak self] profile in
                await self?.switchProfile(to: profile)
            },
            onDeleteProfile: { [weak self] profile in
                await self?.deleteProfile(profile)
            }
        )
    }

    public var onboardingRoute: OnboardingRoute? {
        switch route {
        case let .onboarding(onboardingRoute):
            onboardingRoute
        case let .interactive(context):
            context.returnRoute
        default:
            nil
        }
    }

    public var requiresInteractiveEdgeAuth: Bool {
        selectedEdgeAuthMode == .cloudflareAccessSession || selectedEdgeAuthMode == .genericCookieSSO
    }

    public var effectiveOriginAuthMode: OriginAuthMode {
        activeProfile?.originAuthMode ?? discoveryResult?.recommendedOriginAuthMode ?? .rommBearerPassword
    }

    public var canContinueToLogin: Bool {
        activeProfile?.serverAccess.status == .ready
    }

    public var canSavePlayerState: Bool {
        activePlayer?.presentation.supportsSaveStates == true
    }

    public func bootstrap() async {
        guard route == .gate else { return }
        await reloadProfiles()
        transitionFromGateDecision()
    }

    public func reloadProfiles() async {
        do {
            let loadedProfiles = try await services.profileStore.listProfiles()
            profiles = loadedProfiles.sorted { $0.updatedAt > $1.updatedAt }
            activeProfile = try await services.profileStore.activeProfile()
            if let activeProfile {
                offlineState = try await services.offlineStore.load(profileID: activeProfile.id)
                offlineState.connectivity = services.networkMonitor.connectivity
            } else {
                offlineState = OfflineState(connectivity: services.networkMonitor.connectivity)
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    public func beginSetup() {
        clearTransientMessages()
        if let activeProfile {
            prepareServerAccessDraft(from: activeProfile)
        } else {
            clearSetupDraft()
            selectedEdgeAuthMode = .none
        }
        route = .onboarding(.serverAccess)
    }

    public func resumeSetup() {
        clearTransientMessages()
        if let activeProfile {
            if activeProfile.serverAccess.status == .ready {
                route = .onboarding(.login)
            } else {
                prepareServerAccessDraft(from: activeProfile)
                route = .onboarding(.serverAccess)
            }
        } else {
            beginSetup()
        }
    }

    public func reconfigureSetup() async {
        guard let activeProfile else {
            beginSetup()
            return
        }

        await performBusyOperation { model in
            try await model.services.authController.clearServerAccess(profileID: activeProfile.id)
            await model.reloadProfiles()
            if let refreshed = model.activeProfile {
                model.prepareServerAccessDraft(from: refreshed)
            } else {
                model.clearSetupDraft()
            }
            model.route = .onboarding(.serverAccess)
            model.noticeMessage = "Server access was reset. Verify protected access again before signing in."
        }
    }

    public func discoverServer() async {
        guard !serverURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            errorMessage = "Enter a RomM server URL first."
            return
        }

        await performBusyOperation { model in
            let result = try await model.services.authController.discoverServer(rawBaseURL: model.serverURL)
            model.discoveryResult = result
            model.serverURL = result.baseURL.absoluteString
            if model.profileLabel.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                model.profileLabel = result.baseURL.host ?? result.baseURL.absoluteString
            }
            if let activeProfile = model.activeProfile,
               activeProfile.baseURL == result.baseURL,
               activeProfile.serverAccess.status == .ready {
                model.selectedEdgeAuthMode = activeProfile.edgeAuthMode
            } else if !model.isEdgeAuthModeOverridden {
                model.selectedEdgeAuthMode = result.recommendedEdgeAuthMode
            }
            model.serverAccessResult = model.activeProfile.flatMap(model.serverAccessResult(for:))
            model.noticeMessage = "Server discovery completed."
        }
    }

    public func selectEdgeAuthMode(_ mode: EdgeAuthMode) {
        selectedEdgeAuthMode = mode
        isEdgeAuthModeOverridden = true
        clearTransientMessages()
    }

    public func useDetectedEdgeRecommendation() {
        guard let discoveryResult else { return }
        selectedEdgeAuthMode = discoveryResult.recommendedEdgeAuthMode
        isEdgeAuthModeOverridden = false
        clearTransientMessages()
    }

    public func testProtectedAccess() async {
        await performBusyOperation { model in
            let profile = try await model.ensureConfiguredProfileForServerStep()
            model.serverAccessResult = try await model.services.authController.testServerAccess(profileID: profile.id)
            await model.reloadProfiles()
            if let refreshed = model.activeProfile {
                model.prepareServerAccessDraft(from: refreshed)
            }
            model.route = .onboarding(.serverAccess)
            if model.serverAccessResult?.status == .ready {
                model.noticeMessage = "Protected server access is ready. Continue to RomM sign-in."
            }
        }
    }

    public func continueToLogin() {
        guard activeProfile?.serverAccess.status == .ready else {
            errorMessage = "Run the native access test and wait for a ready result before continuing."
            return
        }
        clearTransientMessages()
        route = .onboarding(.login)
    }

    public func changeServerAccess() {
        clearTransientMessages()
        if let activeProfile {
            prepareServerAccessDraft(from: activeProfile)
        }
        route = .onboarding(.serverAccess)
    }

    public func beginInteractiveEdgeAccess() async {
        guard requiresInteractiveEdgeAuth else {
            errorMessage = "Select a protected-server login method before launching web authentication."
            return
        }
        guard interactiveAuthenticator != nil else {
            errorMessage = "Interactive web authentication is not available in this build context."
            return
        }

        await performBusyOperation { model in
            let profile = try await model.ensureConfiguredProfileForServerStep()
            try await model.services.authController.beginEdgeAccess(profileID: profile.id)
            await model.reloadProfiles()
            model.route = .interactive(
                InteractiveAuthContext(
                    provider: .edge,
                    returnRoute: .serverAccess
                )
            )
        }
    }

    public func beginInteractiveOriginLogin() {
        guard interactiveAuthenticator != nil else {
            errorMessage = "Interactive web authentication is not available in this build context."
            return
        }
        guard activeProfile != nil else {
            errorMessage = "Configure a server profile before signing in."
            return
        }
        clearTransientMessages()
        route = .interactive(
            InteractiveAuthContext(
                provider: .origin,
                returnRoute: .login
            )
        )
    }

    public func performInteractiveSessionIfNeeded(_ context: InteractiveAuthContext) async {
        guard activeInteractiveContextID != context.id else { return }
        activeInteractiveContextID = context.id
        defer { activeInteractiveContextID = nil }

        guard let activeProfile else {
            route = .onboarding(context.returnRoute)
            errorMessage = "Configure a server profile before launching interactive authentication."
            return
        }
        guard let interactiveAuthenticator else {
            route = .onboarding(context.returnRoute)
            errorMessage = "Interactive web authentication is not available in this build context."
            return
        }

        isBusy = true
        defer { isBusy = false }

        do {
            let config = try await services.authController.interactiveSessionConfig(
                profileID: activeProfile.id,
                provider: context.provider
            )
            _ = try await interactiveAuthenticator.authenticate(using: config)

            switch context.provider {
            case .edge:
                let accessState = try await services.authController.completeEdgeAccessAttempt(profileID: activeProfile.id)
                await reloadProfiles()
                if let refreshed = self.activeProfile {
                    prepareServerAccessDraft(from: refreshed)
                }
                route = .onboarding(.serverAccess)
                noticeMessage = accessState.lastError ?? "Protected login finished. Run the native access test next."
            case .origin:
                let status = try await services.authController.completeInteractiveLogin(
                    profileID: activeProfile.id,
                    provider: .origin
                )
                await reloadProfiles()
                if status == .connected {
                    route = .onboarding(.success)
                    noticeMessage = "RomM sign-in completed."
                } else {
                    route = .onboarding(.login)
                    errorMessage = "RomM sign-in is still incomplete. Finish the web flow and try again."
                }
            }
        } catch {
            route = .onboarding(context.returnRoute)
            errorMessage = error.localizedDescription
        }
    }

    public func submitLogin() async {
        guard let activeProfile else {
            errorMessage = "Configure a server profile before signing in."
            return
        }

        await performBusyOperation { model in
            switch activeProfile.originAuthMode {
            case .rommOIDCSession:
                model.route = .interactive(
                    InteractiveAuthContext(
                        provider: .origin,
                        returnRoute: .login
                    )
                )
                return
            case .none:
                let status = try await model.services.authController.validateProfile(profileID: activeProfile.id)
                guard status == .connected else {
                    throw URLError(.userAuthenticationRequired)
                }
            case .rommBearerPassword, .rommBasicLegacy:
                try await model.services.authController.loginWithDirectCredentials(
                    profileID: activeProfile.id,
                    credentials: DirectLoginCredentials(
                        username: model.username.trimmingCharacters(in: .whitespacesAndNewlines),
                        password: model.password
                    )
                )
            }

            model.password = ""
            await model.reloadProfiles()
            model.route = .onboarding(.success)
            model.noticeMessage = "RomM sign-in completed."
        }
    }

    public func enterApp() async {
        selectedTab = .home
        route = .app
        await refreshShell(forceRemote: true)
    }

    public func switchProfile(to profile: ServerProfile) async {
        await performBusyOperation { model in
            try await model.services.profileStore.setActiveProfile(id: profile.id)
            await model.reloadProfiles()
            model.transitionFromGateDecision()
            if model.route == .app {
                await model.refreshShell(forceRemote: false)
            }
        }
    }

    public func signOut() async {
        guard let activeProfile else { return }
        await performBusyOperation { model in
            await model.closePlayer(noticeMessage: nil)
            model.stopDownloadPolling()
            try await model.services.authController.logout(profileID: activeProfile.id, clearServerAccess: false)
            await model.reloadProfiles()
            model.transitionFromGateDecision()
            model.noticeMessage = "Signed out of \(activeProfile.label)."
        }
    }

    public func deleteProfile(_ profile: ServerProfile) async {
        await performBusyOperation { model in
            if model.activeProfile?.id == profile.id {
                await model.closePlayer(noticeMessage: nil)
            }
            try await model.services.authController.deleteProfile(profileID: profile.id)
            await model.reloadProfiles()
            model.transitionFromGateDecision()
        }
    }

    public func refreshShell(forceRemote: Bool = true) async {
        guard activeProfile != nil else { return }
        isRefreshing = true
        defer { isRefreshing = false }

        var failures: [String] = []

        if let activeProfile {
            do {
                offlineState = try await services.offlineStore.load(profileID: activeProfile.id)
                offlineState.connectivity = services.networkMonitor.connectivity
            } catch {
                failures.append(error.localizedDescription)
            }
        }

        await homeFeature.refresh(refreshCatalog: forceRemote)
        await libraryFeature.refresh(forceRemote: forceRemote)
        await collectionsFeature.refresh(forceRemote: forceRemote)
        await downloadsFeature.refresh()
        await settingsFeature.refresh()
        updateDownloadPollingState()

        errorMessage = failures.isEmpty ? nil : failures.joined(separator: "\n")
    }

    private func refreshDownloads() async {
        await downloadsFeature.refresh()
        await homeFeature.refresh(refreshCatalog: false)
        updateDownloadPollingState()
    }

    private func queueDownload(rom: RomDTO, file: RomFileDTO) async {
        await performBusyOperation { model in
            try await model.services.downloadQueue.enqueue(rom: rom, file: file)
            let provisioningMessage = try await model.autoProvisionCoreIfNeededForDownload(rom: rom, file: file)
            await model.downloadsFeature.refresh()
            await model.homeFeature.refresh(refreshCatalog: false)
            model.updateDownloadPollingState()
            if let provisioningMessage {
                model.noticeMessage = "Queued \(file.fileName). \(provisioningMessage)"
            } else {
                model.noticeMessage = "Queued \(file.fileName)."
            }
        }
    }

    private func retryDownload(recordID: String) async {
        await performBusyOperation { model in
            try await model.services.downloadQueue.retry(recordID: recordID)
            await model.downloadsFeature.refresh()
            await model.homeFeature.refresh(refreshCatalog: false)
            model.updateDownloadPollingState()
        }
    }

    private func cancelDownload(recordID: String) async {
        await performBusyOperation { model in
            try await model.services.downloadQueue.cancel(recordID: recordID)
            await model.downloadsFeature.refresh()
            await model.homeFeature.refresh(refreshCatalog: false)
            model.updateDownloadPollingState()
        }
    }

    private func deleteLocalDownloadContent(recordID: String) async {
        await performBusyOperation { model in
            try await model.services.downloadQueue.deleteLocalContent(recordID: recordID)
            await model.downloadsFeature.refresh()
            await model.homeFeature.refresh(refreshCatalog: false)
        }
    }

    public func artworkURL(path: String?) -> URL? {
        guard let activeProfile else { return nil }
        return resolveRemoteAssetURL(baseURL: activeProfile.baseURL, rawPath: path)
    }

    public func localROMPath(for rom: RomDTO, file: RomFileDTO) -> String? {
        let url = services.libraryStore.romURL(platformSlug: rom.platformSlug, fileName: file.fileName)
        return FileManager.default.fileExists(atPath: url.path) ? url.path : nil
    }

    public func coreResolution(for rom: RomDTO, file: RomFileDTO) -> CoreResolution {
        var resolution = coreCatalog.resolve(rom: rom, file: file, libraryStore: services.libraryStore, bundle: coreBundle)
        if let runtimeID = resolution.runtimeProfile?.runtimeID,
           provisioningRuntimeIDs.contains(runtimeID),
           resolution.provisioningStatus == .missingCoreInstallable || resolution.provisioningStatus == .failedCoreInstall {
            resolution.provisioningStatus = .installingCore
            resolution.canAutoProvision = false
            resolution.canRetryProvisioning = false
            resolution.message = "Setting up \(resolution.runtimeProfile?.displayName ?? "core")…"
        }
        return resolution
    }

    public func runtimeFamilies() -> [IOSRuntimeFamily] {
        coreCatalog.allFamilies()
    }

    private func retryCoreProvisioning(rom: RomDTO, file: RomFileDTO) async {
        await performBusyOperation { model in
            let resolution = model.coreResolution(for: rom, file: file)
            guard resolution.canRetryProvisioning, let runtime = resolution.runtimeProfile else {
                model.noticeMessage = "The recommended core is already ready."
                return
            }
            try model.provisionRuntime(runtime)
            model.noticeMessage = "Provisioned \(runtime.displayName)."
        }
    }

    public func runtimeInventory() -> [CoreInventoryEntry] {
        coreInstaller.inventory(catalog: coreCatalog, libraryStore: services.libraryStore, bundle: coreBundle)
            .map { entry in
                guard provisioningRuntimeIDs.contains(entry.runtimeID),
                      entry.provisioningStatus == .missingCoreInstallable || entry.provisioningStatus == .failedCoreInstall else {
                    return entry
                }
                return CoreInventoryEntry(
                    familyID: entry.familyID,
                    familyName: entry.familyName,
                    runtimeID: entry.runtimeID,
                    runtimeName: entry.runtimeName,
                    provisioningStatus: .installingCore,
                    availabilityStatus: entry.availabilityStatus,
                    renderBackend: entry.renderBackend,
                    interactionProfile: entry.interactionProfile,
                    message: "Setting up \(entry.runtimeName)…",
                    provisionedAt: entry.provisionedAt
                )
            }
    }

    public func controlProfile(for platformSlug: String) -> PlatformControlProfile {
        services.playerControlsRepository.resolveProfile(platformSlug: platformSlug)
    }

    func makePlatformDetailFeature(platform: PlatformDTO) -> PlatformDetailFeatureModel {
        PlatformDetailFeatureModel(
            platform: platform,
            services: services,
            resolveProfile: { [services] in
                services.playerControlsRepository.resolveProfile(platformSlug: $0)
            }
        )
    }

    func makeCollectionDetailFeature(collection: RommCollectionDTO) -> CollectionDetailFeatureModel {
        CollectionDetailFeatureModel(
            collection: collection,
            services: services,
            resolveProfile: { [services] in
                services.playerControlsRepository.resolveProfile(platformSlug: $0)
            }
        )
    }

    func makeGameDetailFeature(rom: RomDTO) -> GameDetailFeatureModel {
        GameDetailFeatureModel(
            rom: rom,
            services: services,
            coreCatalog: coreCatalog,
            coreBundle: coreBundle,
            resolveProfile: { [services] in
                services.playerControlsRepository.resolveProfile(platformSlug: $0)
            },
            onQueueDownload: { [weak self] rom, file in
                await self?.queueDownload(rom: rom, file: file)
            },
            onRetryCoreProvisioning: { [weak self] rom, file in
                await self?.retryCoreProvisioning(rom: rom, file: file)
            },
            onPlay: { [weak self] rom, file in
                await self?.launchInstalledGame(rom: rom, file: file)
            },
            onRetryDownload: { [weak self] recordID in
                await self?.retryDownload(recordID: recordID)
            },
            onCancelDownload: { [weak self] recordID in
                await self?.cancelDownload(recordID: recordID)
            },
            onDeleteLocalContent: { [weak self] recordID in
                await self?.deleteLocalDownloadContent(recordID: recordID)
            }
        )
    }

    func launchInstalledGame(rom: RomDTO, file: RomFileDTO) async {
        await performBusyOperation { model in
            try await model.presentInstalledGame(rom: rom, file: file)
        }
    }

    public func dismissPlayer() async {
        if let activePlayer {
            await activePlayer.leaveGame()
        } else {
            await closePlayer(noticeMessage: nil)
        }
    }

    public func startPresentedPlayerIfNeeded(presentationID: UUID) async {
        guard activePlayer?.id == presentationID else { return }
        await activePlayer?.startIfNeeded()
    }

    public func togglePlayerPause() async {
        guard let activePlayer else { return }
        if activePlayer.isPaused || activePlayer.pauseSheetPresented {
            await activePlayer.resumeGame()
        } else {
            await activePlayer.openPauseMenu()
        }
    }

    public func resetPlayer() async {
        await activePlayer?.resetCore()
    }

    public func savePlayerState(slot: Int = 0) async {
        _ = slot
        await activePlayer?.quickSave()
    }

    private func ensureConfiguredProfileForServerStep() async throws -> ServerProfile {
        let discovery: AuthDiscoveryResult
        if let existingDiscovery = discoveryResult {
            discovery = existingDiscovery
        } else {
            let result = try await services.authController.discoverServer(rawBaseURL: serverURL)
            discoveryResult = result
            serverURL = result.baseURL.absoluteString
            if profileLabel.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                profileLabel = result.baseURL.host ?? result.baseURL.absoluteString
            }
            discovery = result
        }

        let existingOriginAuthMode: OriginAuthMode
        if let activeProfile, activeProfile.baseURL == discovery.baseURL {
            existingOriginAuthMode = activeProfile.originAuthMode
        } else {
            existingOriginAuthMode = discovery.recommendedOriginAuthMode
        }

        let profile = try await services.authController.configureProfile(
            baseURL: serverURL,
            label: profileLabel,
            edgeAuthMode: selectedEdgeAuthMode,
            originAuthMode: existingOriginAuthMode,
            discoveryResult: discovery,
            makeActive: true
        )

        if selectedEdgeAuthMode == .cloudflareAccessService {
            let clientID = cloudflareClientID.trimmingCharacters(in: .whitespacesAndNewlines)
            let clientSecret = cloudflareClientSecret.trimmingCharacters(in: .whitespacesAndNewlines)
            if !clientID.isEmpty || !clientSecret.isEmpty {
                guard !clientID.isEmpty, !clientSecret.isEmpty else {
                    throw NSError(
                        domain: "RommioAuth",
                        code: 1,
                        userInfo: [NSLocalizedDescriptionKey: "Enter both the Cloudflare Access client ID and client secret."]
                    )
                }
                try await services.authController.saveCloudflareCredentials(
                    profileID: profile.id,
                    credentials: CloudflareServiceCredentials(
                        clientID: clientID,
                        clientSecret: clientSecret
                    )
                )
            } else if try await !services.authController.hasCloudflareServiceCredentials(profileID: profile.id) {
                throw NSError(
                    domain: "RommioAuth",
                    code: 2,
                    userInfo: [NSLocalizedDescriptionKey: "Enter the Cloudflare Access client ID and client secret."]
                )
            }
        }

        await reloadProfiles()
        if let refreshed = activeProfile {
            return refreshed
        }
        return profile
    }

    private func transitionFromGateDecision() {
        let decision = AuthGateDecision.resolve(profile: activeProfile, offlineState: offlineState)
        route = decision.route
        switch decision {
        case .welcome:
            break
        case .serverAccess:
            if let activeProfile {
                prepareServerAccessDraft(from: activeProfile)
            }
        case .login:
            if let activeProfile {
                prepareServerAccessDraft(from: activeProfile)
            }
        case .app:
            selectedTab = .home
        }
    }

    private func prepareServerAccessDraft(from profile: ServerProfile) {
        serverURL = profile.baseURL.absoluteString
        profileLabel = profile.label
        selectedEdgeAuthMode = profile.edgeAuthMode
        isEdgeAuthModeOverridden = false
        discoveryResult = AuthDiscoveryResult(
            baseURL: profile.baseURL,
            capabilities: profile.capabilities,
            recommendedEdgeAuthMode: profile.edgeAuthMode,
            recommendedOriginAuthMode: profile.originAuthMode,
            warnings: []
        )
        serverAccessResult = serverAccessResult(for: profile)
    }

    private func serverAccessResult(for profile: ServerProfile) -> ServerAccessResult? {
        let accessState = profile.serverAccess
        guard accessState.status != .unknown || accessState.lastHTTPStatus != nil || accessState.lastError != nil || !accessState.cookieNamesSeen.isEmpty else {
            return nil
        }
        let responseKind = accessState.lastResponseKind
            ?? (accessState.status == .ready ? .json : .networkError)
        let message = accessState.lastError
            ?? (accessState.status == .ready
                ? "Protected server access is ready."
                : "Run the native access test to verify protected server access.")
        return ServerAccessResult(
            status: accessState.status,
            httpStatus: accessState.lastHTTPStatus,
            responseKind: responseKind,
            cookieNamesSeen: accessState.cookieNamesSeen,
            message: message
        )
    }

    private func clearSetupDraft() {
        serverURL = ""
        profileLabel = ""
        username = ""
        password = ""
        cloudflareClientID = ""
        cloudflareClientSecret = ""
        selectedEdgeAuthMode = .none
        isEdgeAuthModeOverridden = false
        discoveryResult = nil
        serverAccessResult = nil
    }

    private func clearTransientMessages() {
        errorMessage = nil
        noticeMessage = nil
    }

    private func performBusyOperation(_ operation: @escaping @MainActor (RommioAppModel) async throws -> Void) async {
        isBusy = true
        defer { isBusy = false }

        do {
            errorMessage = nil
            try await operation(self)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func updateDownloadPollingState() {
        let hasActiveDownloads = downloadsFeature.activeRecords.contains { record in
            record.status == .queued || record.status == .running
        }

        if hasActiveDownloads {
            startDownloadPollingIfNeeded()
        } else {
            stopDownloadPolling()
        }
    }

    private func startDownloadPollingIfNeeded() {
        guard downloadPollingTask == nil else { return }
        downloadPollingTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                guard let self else { return }
                await self.downloadsFeature.refresh()
                await self.homeFeature.refresh(refreshCatalog: false)
                let stillActive = !self.downloadsFeature.activeRecords.isEmpty
                if !stillActive {
                    self.downloadPollingTask = nil
                    return
                }
            }
        }
    }

    private func stopDownloadPolling() {
        downloadPollingTask?.cancel()
        downloadPollingTask = nil
    }

    private func presentInstalledGame(rom: RomDTO, file: RomFileDTO) async throws {
        logger.info("presentInstalledGame romID=\(rom.id, privacy: .public) fileID=\(file.id, privacy: .public)")
        NSLog("[RommioAppModel] presentInstalledGame romID=%d fileID=%d", rom.id, file.id)
        guard let romPath = localROMPath(for: rom, file: file) else {
            throw NSError(
                domain: "RommioPlayer",
                code: 100,
                userInfo: [NSLocalizedDescriptionKey: "Download \(file.fileName) before launching it."]
            )
        }

        let resolution = try await ensureRuntimeReadyForLaunch(rom: rom, file: file)
        logger.info("runtime resolution capability=\(String(describing: resolution.capability), privacy: .public) runtimeID=\(resolution.runtimeProfile?.runtimeID ?? "nil", privacy: .public)")
        NSLog("[RommioAppModel] resolution capability=%@ runtimeID=%@", String(describing: resolution.capability), resolution.runtimeProfile?.runtimeID ?? "nil")
        guard resolution.capability == .ready, let runtime = resolution.runtimeProfile else {
            throw NSError(
                domain: "RommioPlayer",
                code: 101,
                userInfo: [NSLocalizedDescriptionKey: resolution.message ?? "This title is not playable on iOS yet."]
            )
        }

        guard let coreURL = resolution.coreURL else {
            throw PlayerEngineError.coreBundleIncomplete("The bundled \(runtime.displayName) core is not installed and ready.")
        }

        try services.libraryStore.ensureRootLayout()
        await closePlayer(noticeMessage: nil)

        let installation = InstalledROMReference(
            romID: rom.id,
            fileID: file.id,
            platformSlug: rom.platformSlug,
            romName: rom.displayName,
            fileName: file.fileName
        )
        let launchPreparation: PlayerLaunchPreparation
        let selectedProfile: ServerProfile?
        if let activeProfile {
            selectedProfile = activeProfile
        } else {
            selectedProfile = try? await services.profileStore.activeProfile()
        }
        if let profile = selectedProfile {
            if let preparedLaunch = try? await services.syncBridge.preparePlayerLaunch(
                profile: profile,
                installation: installation,
                rom: rom,
                runtimeID: runtime.runtimeID,
                connectivity: offlineState.connectivity
            ) {
                launchPreparation = preparedLaunch
            } else {
                launchPreparation = try services.syncBridge.cachedPlayerLaunch(
                    profile: profile,
                    installation: installation,
                    connectivity: offlineState.connectivity
                )
            }
        } else {
            launchPreparation = try services.syncBridge.cachedPlayerLaunch(
                profile: nil,
                installation: installation,
                connectivity: offlineState.connectivity
            )
        }
        let playerEngine = playerEngineFactory()
        let session = makePlayerSession(
            rom: rom,
            file: file,
            installation: installation,
            runtime: runtime,
            romURL: URL(fileURLWithPath: romPath),
            coreURL: coreURL
        )

        do {
            logger.info("player prepare begin")
            NSLog("[RommioAppModel] player prepare begin")
            try await playerEngine.prepare(session: session)
            logger.info("player prepare complete")
            NSLog("[RommioAppModel] player prepare complete")
            _ = try playerEngine.makeHostController()
            logger.info("player host controller created")
            NSLog("[RommioAppModel] player host controller created")
        } catch {
            logger.error("player prepare failure: \(error.localizedDescription, privacy: .public)")
            NSLog("[RommioAppModel] player prepare failure: %@", error.localizedDescription)
            await playerEngine.stop()
            playerEngine.detach()
            throw error
        }

        let presentation = PlayerPresentationState(
            romID: rom.id,
            fileID: file.id,
            romTitle: rom.displayName,
            platformSlug: rom.platformSlug,
            runtimeName: runtime.displayName,
            supportsSaveStates: runtime.supportsSaveStates
        )
        self.activePlayer = PlayerFeatureModel(
            presentation: presentation,
            launchPreparation: launchPreparation,
            engine: playerEngine,
            services: services,
            rom: rom,
            file: file,
            installation: installation,
            runtimeID: runtime.runtimeID,
            onLeave: { [weak self] in
                await self?.closePlayer(noticeMessage: nil)
            }
        )
        self.noticeMessage = "Launching \(rom.displayName) with \(runtime.displayName)."
    }

    private func makePlayerSession(
        rom: RomDTO,
        file: RomFileDTO,
        installation: InstalledROMReference,
        runtime: IOSRuntimeProfile,
        romURL: URL,
        coreURL: URL
    ) -> PlayerSession {
        let saveRAMURL = services.libraryStore.saveRAMURL(for: installation)
        let initialSaveRAM = try? Data(contentsOf: saveRAMURL)
        let preferredViewportAspectRatio = services
            .playerControlsRepository
            .resolveProfile(platformSlug: rom.platformSlug)
            .preferredViewportAspectRatio
        return PlayerSession(
            romID: rom.id,
            fileID: file.id,
            romTitle: rom.displayName,
            romURL: romURL,
            coreURL: coreURL,
            runtimeProfile: runtime,
            systemDirectory: services.libraryStore.systemDirectory(),
            savesDirectory: saveRAMURL.deletingLastPathComponent(),
            saveRAMURL: saveRAMURL,
            saveStatesDirectory: services.libraryStore.saveStatesDirectory(for: installation),
            preferredViewportAspectRatio: preferredViewportAspectRatio,
            variables: runtime.defaultVariables,
            initialSaveRAM: initialSaveRAM
        )
    }

    private func closePlayer(noticeMessage: String?) async {
        activePlayer = nil
        if let noticeMessage {
            self.noticeMessage = noticeMessage
        }
    }

    public func reauthenticateFromSettings() async {
        guard let activeProfile else {
            errorMessage = "Configure a server profile before re-authenticating."
            return
        }

        clearTransientMessages()
        if activeProfile.serverAccess.status == .ready {
            route = .onboarding(.login)
        } else {
            prepareServerAccessDraft(from: activeProfile)
            route = .onboarding(.serverAccess)
        }
    }

    private func autoProvisionCoreIfNeededForDownload(rom: RomDTO, file: RomFileDTO) async throws -> String? {
        let resolution = coreResolution(for: rom, file: file)
        guard let runtime = resolution.runtimeProfile else {
            return nil
        }

        switch resolution.provisioningStatus {
        case .missingCoreInstallable:
            try provisionRuntime(runtime)
            return "\(runtime.displayName) is ready for play."
        case .failedCoreInstall:
            return "Core setup previously failed. Retry core setup before playing."
        default:
            return nil
        }
    }

    private func ensureRuntimeReadyForLaunch(rom: RomDTO, file: RomFileDTO) async throws -> CoreResolution {
        let initialResolution = coreResolution(for: rom, file: file)
        guard let runtime = initialResolution.runtimeProfile else {
            return initialResolution
        }

        switch initialResolution.provisioningStatus {
        case .missingCoreInstallable:
            try provisionRuntime(runtime)
            return coreResolution(for: rom, file: file)
        case .failedCoreInstall:
            throw BundledCoreInstallerError.failedProvisioning(
                initialResolution.message ?? "Core setup failed. Retry core setup before playing."
            )
        default:
            return initialResolution
        }
    }

    private func provisionRuntime(_ runtime: IOSRuntimeProfile) throws {
        provisioningRuntimeIDs.insert(runtime.runtimeID)
        defer { provisioningRuntimeIDs.remove(runtime.runtimeID) }
        _ = try coreInstaller.install(runtime: runtime, libraryStore: services.libraryStore, bundle: coreBundle)
    }
}
