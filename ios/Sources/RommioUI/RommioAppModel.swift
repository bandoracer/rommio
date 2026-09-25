import Foundation
import Observation
import OSLog
import RommioContract
import RommioFoundation
import RommioPlayerKit
#if canImport(UIKit)
import UIKit
#endif

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
    /// One navigation structure for every theme: Play · Library · Search · Stats · Settings.
    public enum ShellTab: String, Hashable, CaseIterable, Sendable {
        case play
        case library
        case search
        case stats
        case settings
    }

    public var route: AppRoute = .gate
    public var selectedTab: ShellTab = .play
    /// First sync after signing in: the library is being fetched for the first time.
    public var isFirstSync = false

    public var serverURL: String = "" {
        didSet {
            if serverURL != oldValue { serverAddressChanged() }
        }
    }
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
    let playFeature: PlayFeatureModel
    let libraryFeature: LibraryFeatureModel
    let collectionsFeature: CollectionsFeatureModel
    let downloadsFeature: DownloadsFeatureModel
    let settingsFeature: SettingsFeatureModel
    let statsFeature: StatsFeatureModel
    let searchFeature: SearchFeatureModel
    /// Recent saves and conflicts across games (Play, Conflict sheet).
    let savesFeature: SavedProgressFeatureModel
    /// Cover art, fetched with the active profile's request decoration.
    @ObservationIgnored
    let artworkPipeline: ArtworkPipeline

    // Tab pages' content, built from the feature models off the render path and
    // published only when it changes (`DerivedContent`).
    @ObservationIgnored private(set) lazy var playContent = DerivedContent { [unowned self] in PlayContent.build(self) }
    @ObservationIgnored private(set) lazy var libraryContent = DerivedContent { [unowned self] in LibraryContent.build(self) }
    @ObservationIgnored private(set) lazy var searchContent = DerivedContent { [unowned self] in SearchContent.build(self) }
    @ObservationIgnored private(set) lazy var statsContent = DerivedContent { [unowned self] in StatsContent.build(self) }
    @ObservationIgnored private(set) lazy var downloadsContent = DerivedContent { [unowned self] in DownloadsContent.build(self) }

    /// Games downloaded to this device.
    var installedRomIDs: Set<Int> {
        Set(downloadsFeature.records.filter { $0.status == .completed }.map(\.romID))
    }

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
    /// A Conflict-sheet choice for the next player of that game.
    @ObservationIgnored
    private var pendingResolution: PendingProgressResolution?
    /// The Launch choice sheet: Start options (Play, Game), or a player that is
    /// prepared but would stop to ask how to start (docs/theme-layouts.md, Launch choice).
    var launchChoice: LaunchChoicePresentation? {
        didSet { if let launchChoice { presentedLaunch = launchChoice } }
    }
    /// The sheet last shown, handled when it has closed.
    @ObservationIgnored
    private var presentedLaunch: LaunchChoicePresentation?
    /// The option confirmed in the sheet, applied when it has closed.
    @ObservationIgnored
    private var confirmedLaunch: LaunchChoice.Option?
    /// A Start options choice for the next player (Resume or Start with game save).
    @ObservationIgnored
    private var pendingStartOption: LaunchChoice.Option?

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
        self.playFeature = PlayFeatureModel(services: services)
        self.libraryFeature = LibraryFeatureModel(
            services: services,
            resolveProfile: { Self.availableControlProfile(for: $0, services: services, catalog: coreCatalog, installer: coreInstaller, bundle: coreBundle) }
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
        self.statsFeature = StatsFeatureModel(services: services)
        self.searchFeature = SearchFeatureModel(services: services)
        self.savesFeature = SavedProgressFeatureModel(services: services)
        let authController = services.authController
        self.artworkPipeline = ArtworkPipeline { url in
            guard let client = try await authController.activeClient() else { return try await ArtworkPipeline.plainLoader(url) }
            return try await client.fetchAsset(url)
        }
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
        // Built (and tracking their feature models) before any page renders, so a
        // page's first render depends only on its content, not on what builds it.
        playContent.prepare()
        libraryContent.prepare()
        searchContent.prepare()
        statsContent.prepare()
        downloadsContent.prepare()
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
        // Hash-verify the bundled cores once, in the background, before any page needs them.
        BackgroundScans.warmCoreVerification(installer: coreInstaller, catalog: coreCatalog,
                                             libraryStore: services.libraryStore, bundle: coreBundle)
        await reloadProfiles()
        await resumeDownloads()
        transitionFromGateDecision()
        #if DEBUG && canImport(UIKit)
        Task { await self.runProgressProbe(services: self.services) }
        Task { await self.seedResumeConflictIfRequested(services: self.services) }
        #endif
    }

    public func resumeDownloads() async {
        await services.progress.startForeground()
        do { try await services.downloadQueue.resumePending() }
        catch { errorMessage = error.localizedDescription }
    }

    public func playerSceneChanged(active: Bool) async {
        if active {
            await services.progress.startForeground()
            await activePlayer?.sceneBecameActive()
            return
        }
        await services.progress.stopForeground()
        #if canImport(UIKit)
        let progress = services.progress
        var token: UIBackgroundTaskIdentifier = .invalid
        token = UIApplication.shared.beginBackgroundTask(withName: "Preserve game progress") {
            Task { @MainActor in
                if token != .invalid { UIApplication.shared.endBackgroundTask(token); token = .invalid }
                await progress.cancelNetworkWork()
            }
        }
        defer { if token != .invalid { UIApplication.shared.endBackgroundTask(token) } }
        #endif
        await activePlayer?.sceneBecameInactive()
        await services.progress.wake()
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

    /// One Connect action discovers the server, verifies native access, and
    /// advances to sign-in. Gateway browser authentication is only a fallback.
    public func connectServer() async {
        guard !isBusy else { return }
        guard !serverURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            errorMessage = "Enter your RomM server address."
            return
        }
        await performBusyOperation { model in
            model.noticeMessage = nil
            let result = try await model.services.authController.discoverServer(rawBaseURL: model.serverURL)
            model.discoveryResult = result
            model.serverURL = result.baseURL.absoluteString
            if !model.isEdgeAuthModeOverridden {
                model.selectedEdgeAuthMode = model.profiles.first(where: { $0.baseURL == result.baseURL })?.edgeAuthMode
                    ?? result.recommendedEdgeAuthMode
            }
            if model.profileLabel.isEmpty { model.profileLabel = result.baseURL.host ?? "RomM" }
            let profile = try await model.ensureConfiguredProfileForServerStep()
            let access = try await model.services.authController.testServerAccess(profileID: profile.id)
            model.serverAccessResult = access
            await model.reloadProfiles()
            if access.status == .ready {
                model.route = .onboarding(.login)
            } else if model.requiresInteractiveEdgeAuth,
                      access.responseKind == .html || access.responseKind == .unauthorized {
                guard model.interactiveAuthenticator != nil else {
                    model.errorMessage = "This server requires a browser login. Open Access options to choose another access option."
                    return
                }
                try await model.services.authController.beginEdgeAccess(profileID: profile.id)
                model.route = .interactive(InteractiveAuthContext(provider: .edge, returnRoute: .serverAccess))
            } else {
                model.errorMessage = "Could not connect to RomM. Check the address and your network, or choose an access option."
            }
        }
    }

    /// Provision edge credentials through the same profile-scoped Keychain path
    /// as manual setup, and verify RomM before advancing to account sign-in.
    public func applyOneTouchConfiguration(_ configuration: SetupConfiguration) async throws {
        try configuration.validate()
        guard !isBusy else { throw OneTouchSetupError.unavailable }
        isBusy = true
        defer { isBusy = false }
        clearTransientMessages()
        let discovery = AuthDiscoveryResult(
            baseURL: configuration.rommUrl,
            capabilities: AuthCapabilities(cloudflareAccessDetected: true, rommTokenAvailable: true),
            recommendedEdgeAuthMode: .cloudflareAccessService,
            recommendedOriginAuthMode: .rommBearerPassword
        )
        let profile = try await services.authController.configureProfile(
            baseURL: configuration.rommUrl.absoluteString,
            edgeAuthMode: .cloudflareAccessService,
            originAuthMode: .rommBearerPassword,
            discoveryResult: discovery
        )
        try await services.authController.saveCloudflareCredentials(
            profileID: profile.id,
            credentials: CloudflareServiceCredentials(
                clientID: configuration.accessProfile.clientId,
                clientSecret: configuration.accessProfile.clientSecret
            )
        )
        serverURL = configuration.rommUrl.absoluteString
        discoveryResult = discovery
        selectedEdgeAuthMode = .cloudflareAccessService
        isEdgeAuthModeOverridden = true
        // Keep the token out of text-field state after secure storage.
        cloudflareClientID = ""
        cloudflareClientSecret = ""
        serverAccessResult = try await services.authController.testServerAccess(profileID: profile.id)
        await reloadProfiles()
        guard serverAccessResult?.status == .ready else { throw OneTouchSetupError.unavailable }
        route = .onboarding(.login)
    }

    public func serverAddressChanged() {
        guard !isBusy else { return }
        discoveryResult = nil
        serverAccessResult = nil
        profileLabel = ""
        password = ""
        cloudflareClientID = ""
        cloudflareClientSecret = ""
        selectedEdgeAuthMode = .none
        isEdgeAuthModeOverridden = false
        clearTransientMessages()
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
            let result = try await interactiveAuthenticator.authenticate(using: config)
            try await services.authController.importBrowserCookies(config: config, cookies: result.cookies)

            switch context.provider {
            case .edge:
                _ = try await services.authController.completeEdgeAccessAttempt(profileID: activeProfile.id)
                let access = try await services.authController.testServerAccess(profileID: activeProfile.id)
                serverAccessResult = access
                await reloadProfiles()
                route = .onboarding(access.status == .ready ? .login : .serverAccess)
                if access.status != .ready {
                    errorMessage = "Browser sign-in finished, but Rommio still cannot reach the server. Check your access option and try again."
                }
            case .origin:
                let status = try await services.authController.completeInteractiveLogin(
                    profileID: activeProfile.id,
                    provider: .origin
                )
                await reloadProfiles()
                if status == .connected {
                    await enterApp()
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
        guard !isBusy else { return }
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
            model.noticeMessage = nil
            await model.enterApp()
        }
    }

    public func enterApp() async {
        selectedTab = .play
        isFirstSync = !offlineState.catalogReady
        route = .app
        await refreshShell(forceRemote: true)
        isFirstSync = false
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
        await resumeDownloads()
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

        await playFeature.refresh(refreshCatalog: forceRemote)
        await libraryFeature.refresh(forceRemote: forceRemote)
        await collectionsFeature.refresh(forceRemote: forceRemote)
        await downloadsFeature.refresh()
        await settingsFeature.refresh()
        await statsFeature.refresh()
        await savesFeature.refresh()
        await searchFeature.refresh(recentlyPlayed: playFeature.snapshot.continuePlaying, stats: statsFeature.stats)
        updateDownloadPollingState()

        errorMessage = failures.isEmpty ? nil : failures.joined(separator: "\n")
    }

    private func refreshDownloads() async {
        await resumeDownloads()
        await downloadsFeature.refresh()
        await playFeature.refresh(refreshCatalog: false)
        updateDownloadPollingState()
    }

    private func queueDownload(rom: RomDTO, file: RomFileDTO) async {
        await performBusyOperation { model in
            try await model.services.downloadQueue.enqueue(rom: rom, file: file)
            let provisioningMessage = try await model.autoProvisionCoreIfNeededForDownload(rom: rom, file: file)
            await model.downloadsFeature.refresh()
            await model.playFeature.refresh(refreshCatalog: false)
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
            await model.playFeature.refresh(refreshCatalog: false)
            model.updateDownloadPollingState()
        }
    }

    private func cancelDownload(recordID: String) async {
        await performBusyOperation { model in
            try await model.services.downloadQueue.cancel(recordID: recordID)
            await model.downloadsFeature.refresh()
            await model.playFeature.refresh(refreshCatalog: false)
            model.updateDownloadPollingState()
        }
    }

    private func deleteLocalDownloadContent(recordID: String) async {
        await performBusyOperation { model in
            try await model.services.downloadQueue.deleteLocalContent(recordID: recordID)
            await model.downloadsFeature.refresh()
            await model.playFeature.refresh(refreshCatalog: false)
        }
    }

    public func artworkURL(path: String?) -> URL? {
        guard let activeProfile else { return nil }
        return resolveRemoteAssetURL(baseURL: activeProfile.baseURL, rawPath: path)
    }

    public func localROMPath(for rom: RomDTO, file: RomFileDTO) -> String? {
        if let record = downloadsFeature.records.first(where: { $0.profileID == activeProfile?.id && $0.romID == rom.id && $0.fileID == file.id }) {
            return record.localPath.flatMap { FileManager.default.fileExists(atPath: $0) ? $0 : nil }
        }
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
        Self.availableControlProfile(for: platformSlug, services: services, catalog: coreCatalog, installer: coreInstaller, bundle: coreBundle)
    }

    private static func availableControlProfile(for slug: String, services: RommioServices,
                                                catalog: CoreCatalog, installer: any CoreInstalling,
                                                bundle: Bundle) -> PlatformControlProfile {
        var profile = services.playerControlsRepository.resolveProfile(platformSlug: slug)
        let available = catalog.family(for: slug)?.runtimeOptions.contains { runtime in
            guard runtime.validationState != .blockedByValidation else { return false }
            let status = installer.inspect(runtime: runtime, libraryStore: services.libraryStore, bundle: bundle).provisioningStatus
            return status == .ready || status == .missingCoreInstallable
        } ?? false
        if !available { profile.supportTier = .unsupported }
        return profile
    }

    func makePlatformDetailFeature(platform: PlatformDTO) -> PlatformDetailFeatureModel {
        PlatformDetailFeatureModel(
            platform: platform,
            services: services,
            resolveProfile: { [services, coreCatalog, coreInstaller, coreBundle] in
                Self.availableControlProfile(for: $0, services: services, catalog: coreCatalog, installer: coreInstaller, bundle: coreBundle)
            }
        )
    }

    func makeCollectionDetailFeature(collection: RommCollectionDTO) -> CollectionDetailFeatureModel {
        CollectionDetailFeatureModel(
            collection: collection,
            services: services,
            resolveProfile: { [services, coreCatalog, coreInstaller, coreBundle] in
                Self.availableControlProfile(for: $0, services: services, catalog: coreCatalog, installer: coreInstaller, bundle: coreBundle)
            }
        )
    }

    /// Cores such as melonDS DS show this as the player's name. Launch uses the
    /// last known RomM username immediately and never waits on the network; the
    /// cached name refreshes in the background for the next launch.
    private func updateFrontendUsername() {
        let key = "player.frontendUsername.\(activeProfile?.id ?? "none")"
        PlayerFrontendIdentity.setUsername(UserDefaults.standard.string(forKey: key))
        guard services.networkMonitor.connectivity == .online else { return }
        let authController = services.authController
        Task.detached {
            guard let client = try? await authController.activeClient(),
                  let user = try? await client.getCurrentUser() else { return }
            UserDefaults.standard.set(user.username, forKey: key)
            PlayerFrontendIdentity.setUsername(user.username)
        }
    }

    /// Resumes a Continue playing game directly from Home, using the same file
    /// choice and readiness rules as the detail page's primary Play action.
    func quickResume(rom: RomDTO) async {
        let detail = makeGameDetailFeature(rom: rom)
        await detail.refresh(forceRemote: false)
        guard let primary = detail.actionDeckPresentation?.primary,
              primary.kind == .play, primary.enabled else {
            errorMessage = "\(rom.displayName) isn't ready to resume yet. Open it to download or set it up."
            return
        }
        await detail.playFromResume()
    }

    // MARK: Launch choice

    /// What Start options can offer for a game (Play's context menu).
    enum StartOptionsOutcome {
        case choice(LaunchChoice)
        /// Its resume point is in conflict: the Conflict sheet asks instead.
        case conflict
        /// Nothing saved to choose between: it just starts.
        case nothingToChoose
    }

    func startOptions(for rom: RomDTO) async -> StartOptionsOutcome {
        guard let account = await services.activeProgressAccount() else { return .nothingToChoose }
        await services.playData.flush()
        guard let saves = try? await services.savedProgress.gameSaves(account, romId: Int64(rom.id)) else { return .nothingToChoose }
        if saves.resumeConflict { return .conflict }
        guard LaunchChoice.offersStartOptions(saves),
              let choice = LaunchChoice.make(rom: rom, artworkURL: artworkURL(path: rom.urlCover), saves: saves) else { return .nothingToChoose }
        return .choice(choice)
    }

    /// Opens the Launch choice sheet for a game that is not running yet.
    func showLaunchChoice(_ choice: LaunchChoice) {
        confirmedLaunch = nil
        launchChoice = LaunchChoicePresentation(choice: choice, prepared: nil)
    }

    /// The sheet's primary button: remembered and applied once the sheet has closed.
    func confirmLaunchChoice(_ option: LaunchChoice.Option) {
        confirmedLaunch = option
        launchChoice = nil
    }

    /// The sheet closed ("Not now", a swipe, or after the primary button).
    func launchChoiceClosed() async {
        guard launchChoice == nil, let presentation = presentedLaunch else { return }
        presentedLaunch = nil
        let option = confirmedLaunch
        confirmedLaunch = nil
        if let player = presentation.prepared {
            guard let option else {
                // Not now: the prepared session closes without starting (nothing is captured).
                await player.leaveGame()
                return
            }
            player.preferColdBoot = option == .gameSave
            player.cloudMessage = nil
            activePlayer = player
            noticeMessage = "Launching \(presentation.choice.rom.displayName)."
        } else if let option {
            await startGame(presentation.choice.rom, option: option)
        }
    }

    /// Starts a game with a chosen option (Resume or Start with game save).
    func startGame(_ rom: RomDTO, option: LaunchChoice.Option) async {
        pendingStartOption = option
        await quickResume(rom: rom)
        pendingStartOption = nil
    }

    /// "Check again" in the sheet when the cloud check had not finished: rechecks,
    /// then starts at once if nothing is left to ask.
    func retryLaunchCloudCheck() async {
        guard let presentation = launchChoice, let player = presentation.prepared, let owner = player.progressOwner else { return }
        do {
            let result = try await services.progress.synchronize(owner.identity)
            player.cloudMessage = result.errors.isEmpty ? nil : result.message
        } catch {
            player.cloudMessage = error.localizedDescription
        }
        let rom = presentation.choice.rom
        if let choice = await preLaunchChoice(rom: rom, owner: owner, cloudMessage: player.cloudMessage, fileID: player.presentation.fileID,
                                              preferred: presentation.choice.preferred) {
            launchChoice = LaunchChoicePresentation(choice: choice, prepared: player)
        } else {
            confirmLaunchChoice(presentation.choice.preferred ?? .resume)
        }
    }

    /// Before the player opens: the Launch choice when the launch would otherwise
    /// stop inside the player to ask (the cloud check did not finish, or no resume
    /// point can be loaded with the current game-save data). A Conflict-sheet
    /// resolution and conflicted resume points are left to the player.
    private func preLaunchChoice(rom: RomDTO, owner: ProgressSessionOwner, cloudMessage: String?, fileID: Int,
                                 preferred: LaunchChoice.Option?) async -> LaunchChoice? {
        var unavailable: String?
        if case let .resumeUnavailable(reason) = (try? await owner.launchQuestion()) ?? .none { unavailable = reason }
        guard cloudMessage != nil || unavailable != nil, let account = await services.activeProgressAccount() else { return nil }
        await services.playData.flush()
        let record = try? await services.downloadQueue.records(profileID: activeProfile?.id).first { $0.romID == rom.id && $0.fileID == fileID }
        guard let saves = try? await services.savedProgress.gameSaves(account, romId: Int64(rom.id), fileId: Int64(fileID),
                                                                      contentSha256: record?.contentSHA256) else { return nil }
        return LaunchChoice.make(rom: rom, artworkURL: artworkURL(path: rom.urlCover), saves: saves, resumeUnavailable: unavailable,
                                 note: cloudMessage, preferred: preferred)
    }

    /// Conflict sheet › Keep playing: opens the game; its player resolves the reference
    /// to the chosen head before it starts (the same call as choosing in the player).
    func keepPlaying(_ conflict: ProgressConflict, choice: ProgressConflict.Choice) async {
        pendingResolution = PendingProgressResolution(identity: conflict.identity, reference: conflict.reference,
                                                      selected: choice.operationId, expected: conflict.choices.map(\.operationId))
        let rom = (try? await services.libraryRepository.cachedRom(id: Int(conflict.romId))) ?? savesFeature.game(conflict.romId)
        await quickResume(rom: rom)
        pendingResolution = nil
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
        if let existingDiscovery = discoveryResult,
           normalizedServerURLCandidates(serverURL).contains(existingDiscovery.baseURL) {
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
            selectedTab = .play
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
            if route == .onboarding(.login) {
                if (error as? URLError)?.code == .userAuthenticationRequired {
                    errorMessage = "Sign-in was rejected. Check your username, password, and account access."
                } else {
                    errorMessage = "Could not sign in. Check your connection and try again."
                }
            } else {
                errorMessage = error.localizedDescription
            }
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
                await self.playFeature.refresh(refreshCatalog: false)
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

    private var progressPlayerLease: String?
    private func presentInstalledGame(rom: RomDTO, file: RomFileDTO) async throws {
        if let activePlayer {
            await activePlayer.leaveGame()
            guard self.activePlayer == nil else { throw ProgressStoreError.invalid("Finish saving the active session before launching another game") }
        }
        let lease = try await services.progress.beginPlayer()
        do {
            try await prepareInstalledGame(rom: rom, file: file)
            progressPlayerLease = lease
        } catch { await services.progress.endPlayer(lease); throw error }
    }
    private func prepareInstalledGame(rom: RomDTO, file: RomFileDTO) async throws {
        logger.info("presentInstalledGame romID=\(rom.id, privacy: .public) fileID=\(file.id, privacy: .public)")
        NSLog("[RommioAppModel] presentInstalledGame romID=%d fileID=%d", rom.id, file.id)
        let launchProfileID = activeProfile?.id
        let launchPath: String?
        if let launchProfileID,
           try await services.downloadQueue.records(profileID: launchProfileID).contains(where: { $0.romID == rom.id && $0.fileID == file.id }) {
            launchPath = try await services.downloadQueue.verifiedLocalURL(profileID: launchProfileID, romID: rom.id, fileID: file.id)?.path
        } else { launchPath = localROMPath(for: rom, file: file) }
        guard activeProfile?.id == launchProfileID else { throw CancellationError() }
        guard let romPath = launchPath else {
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
        if let activePlayer {
            await activePlayer.leaveGame()
            guard self.activePlayer == nil else { throw ProgressStoreError.invalid("Finish saving the active session before launching another game") }
        }

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
        let manifest = coreBundle.bundleURL.appending(path: runtime.runtimeID == "fceumm" ? "FceummProvenance.json" : "\(runtime.runtimeID)Provenance.json")
        var progressIdentity: ProgressIdentity?
        var coreDigest: String?
        let sessionID = UUID().uuidString.lowercased()
        let workspace = services.progress.root.appending(path: "sessions/\(sessionID)")
        var cloudMessage: String?
        var legacyProgressFiles: [URL] = []
        var threeDSArchiveMember: ThreeDSArchive.Member?
        if (runtime.runtimeID == "fceumm" || PinnedAppleCore.cartridgeRuntimes.contains(runtime.runtimeID) || PinnedAppleCore.candidateRuntimes.contains(runtime.runtimeID)), FileManager.default.fileExists(atPath: manifest.path), let profile = selectedProfile,
           let record = try await services.downloadQueue.records(profileID: profile.id).first(where: { $0.romID == rom.id && $0.fileID == file.id }),
           let owner = record.owner, let digest = record.contentSHA256 {
            guard try await services.authController.verifiedAccount(profileID: profile.id) == owner,
                  owner.baseURL == profile.baseURL, try ProgressFileHash.sha256(URL(fileURLWithPath: romPath)) == digest else { throw AccountBindingError() }
            coreDigest = try PinnedAppleCore.verify(core: coreURL, manifest: manifest, runtimeID: runtime.runtimeID)
            if runtime.runtimeID == "azahar", file.effectiveFileExtension.lowercased() == "zip" {
                threeDSArchiveMember = try ThreeDSArchive.materialize(URL(fileURLWithPath: romPath), in: workspace.appending(path: "content"))
            }
            let binding = ProgressBinding(serverKey: ApplicationProgress.serverKey(owner.baseURL), accountId: String(owner.userID), romId: Int64(rom.id), files: [ProgressContentFile(fileId: Int64(file.id), discIndex: 0, contentSha256: threeDSArchiveMember?.sha256 ?? digest, containerSha256: threeDSArchiveMember == nil ? nil : digest, memberPath: threeDSArchiveMember?.path)])
            let store = try await services.progress.store()
            progressIdentity = try await store.resolveIdentity(binding)
            let upgradeWarnings = try await ProgressUpgrade.run(store: store, identity: progressIdentity!)
            cloudMessage = await services.progress.checkBeforeLaunch(progressIdentity!)
            if !upgradeWarnings.isEmpty { cloudMessage = upgradeWarnings.joined(separator: " ") }
            let oldDirectory = services.libraryStore.saveStatesDirectory(for: installation)
            legacyProgressFiles = [services.libraryStore.saveRAMURL(for: installation), services.libraryStore.continuityResumeStateURL(for: installation)]
                + ((try? FileManager.default.contentsOfDirectory(at: oldDirectory, includingPropertiesForKeys: [.isRegularFileKey])) ?? [])
            legacyProgressFiles += try await services.progress.legacyRecoveryFiles()
            legacyProgressFiles = Array(Set(legacyProgressFiles)).filter { (try? $0.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true }
            if !(try await store.references(progressIdentity!)).keys.contains("game/current") && !legacyProgressFiles.isEmpty {
                cloudMessage = "Older local progress is retained and needs an explicit import. Continue with local progress to open recovery, then choose the file to assign to this account and game. " + (cloudMessage ?? "")
            }
        }
        if progressIdentity != nil {
            launchPreparation = PlayerLaunchPreparation()
        } else if let profile = selectedProfile {
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
        if runtime.runtimeID == "azahar", file.effectiveFileExtension.lowercased() == "zip", threeDSArchiveMember == nil {
            throw ProgressStoreError.invalid("This 3DS ZIP needs a verified download and account before it can be opened.")
        }
        updateFrontendUsername()
        let playerEngine = playerEngineFactory()
        var session = makePlayerSession(
            rom: rom,
            file: file,
            installation: installation,
            runtime: runtime,
            romURL: threeDSArchiveMember?.url ?? URL(fileURLWithPath: romPath),
            coreURL: coreURL
        )

        if progressIdentity != nil {
            session.ownsProgress = true; session.initialSaveRAM = nil
            if threeDSArchiveMember == nil {
                let isolatedContent = workspace.appending(path: "content/game.\(file.effectiveFileExtension.lowercased())")
                try FileManager.default.createDirectory(at: isolatedContent.deletingLastPathComponent(), withIntermediateDirectories: true)
                try FileManager.default.copyItem(at: session.romURL, to: isolatedContent)
                guard try ProgressFileHash.sha256(isolatedContent) == progressIdentity?.binding.files.first?.contentSha256 else {
                    throw ProgressStoreError.invalid("Content changed while preparing the session")
                }
                session.romURL = isolatedContent
            }
            session.savesDirectory = workspace.appending(path: "saves")
            session.saveRAMURL = session.savesDirectory.appending(path: "save.srm")
            session.saveStatesDirectory = workspace.appending(path: "states")
            session.systemDirectory = workspace.appending(path: "system")
            for dir in [session.savesDirectory, session.saveStatesDirectory, session.systemDirectory] { try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true) }
        }
        let systemFiles = progressIdentity == nil ? [:] : try ProgressFirmware.prepare(runtime: runtime, platform: rom.platformSlug,
            roots: [services.libraryStore.systemDirectory(), services.libraryStore.biosDirectory()], destination: session.systemDirectory, assets: coreBundle.bundleURL.appending(path: "Cores/system/ppsspp"))
        var progressOwner: ProgressSessionOwner?
        do {
            logger.info("player prepare begin")
            NSLog("[RommioAppModel] player prepare begin")
            try await playerEngine.prepare(session: session)
            if let identity = progressIdentity, let coreDigest {
                guard let engine = playerEngine as? any ProgressPlayerEngine else { throw ProgressStoreError.invalid("The player does not implement coherent persistence") }
                let capture = try await engine.captureProgress(includeState: false)
                let qualification = try CartridgePersistence(capture: capture, coreSHA256: coreDigest, systemFiles: systemFiles)
                progressOwner = ProgressSessionOwner(identity: identity, sessionID: sessionID, qualification: qualification, store: try await services.progress.store(), engine: engine, deviceID: try await services.progress.deviceID(), captureRoot: services.progress.root.appending(path: "captures/\(identity.contentId)"), workspace: workspace, sync: services.progress, preparedSession: session,
                                                     playData: services.playData, platformSlug: rom.platformSlug)
            }
            guard activeProfile?.id == launchProfileID else { throw CancellationError() }
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
        let player = PlayerFeatureModel(
            presentation: presentation,
            launchPreparation: launchPreparation,
            engine: playerEngine,
            services: services,
            rom: rom,
            file: file,
            installation: installation,
            runtimeID: runtime.runtimeID,
            progressOwner: progressOwner,
            resolution: progressIdentity.flatMap { identity in
                pendingResolution.flatMap { $0.identity.contentId == identity.contentId && $0.identity.namespaceId == identity.namespaceId ? $0 : nil }
            },
            cloudMessage: cloudMessage,
            legacyProgressFiles: legacyProgressFiles,
            onLeave: { [weak self] message in
                await self?.closePlayer(noticeMessage: message)
            }
        )
        player.backdropURL = artworkURL(path: rom.urlCover)
        // The title strip's line: "Super Nintendo · 25 min played" (docs/player-controls.md).
        let played = statsFeature.stats.games.first { $0.romID == rom.id }?.playtime ?? 0
        player.titleDetail = PlayFormat.line([rom.platformName.isEmpty ? nil : rom.platformName,
                                              played >= 60 ? "\(PlayFormat.duration(played)) played" : nil])
        let chosen = pendingStartOption
        pendingStartOption = nil
        // The choice is made before the player opens (docs/theme-layouts.md, Launch choice).
        if let progressOwner, pendingResolution == nil,
           let choice = await preLaunchChoice(rom: rom, owner: progressOwner, cloudMessage: cloudMessage, fileID: file.id, preferred: chosen) {
            confirmedLaunch = nil
            launchChoice = LaunchChoicePresentation(choice: choice, prepared: player)
            return
        }
        if let chosen { player.preferColdBoot = chosen == .gameSave }
        self.activePlayer = player
        self.noticeMessage = "Launching \(rom.displayName) with \(runtime.displayName)."
    }

    public func importBIOS(_ url: URL) throws -> String {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        return try ProgressFirmware.importBIOS(url, destination: services.libraryStore.biosDirectory())
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
        // A player dismissed without leaving (sign-out, profile removal) ends its play session as "quit".
        await activePlayer?.progressOwner?.endPlayData()
        if let lease = progressPlayerLease { await services.progress.endPlayer(lease); progressPlayerLease = nil }
        activePlayer = nil
        if let noticeMessage {
            self.noticeMessage = noticeMessage
        }
        // The session row, new saves and previews show up on Play and Stats.
        Task { [weak self] in
            await self?.statsFeature.refresh()
            await self?.savesFeature.refresh()
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
