import XCTest
@testable import RommioContract
@testable import RommioFoundation
@testable import RommioPlayerKit
@testable import RommioUI

final class RommioAuthFlowTests: XCTestCase {
    func testGateDecisionMatchesAndroidReferenceStates() {
        XCTAssertEqual(AuthGateDecision.resolve(profile: nil), .welcome)

        XCTAssertEqual(
            AuthGateDecision.resolve(profile: profile(serverAccessStatus: .unknown, status: .invalidConfiguration)),
            .serverAccess
        )

        XCTAssertEqual(
            AuthGateDecision.resolve(profile: profile(serverAccessStatus: .ready, status: .connected)),
            .app
        )

        XCTAssertEqual(
            AuthGateDecision.resolve(
                profile: profile(serverAccessStatus: .ready, status: .reauthRequiredOrigin, hasOriginSession: true),
                offlineState: OfflineState(connectivity: .offline, catalogReady: true)
            ),
            .app
        )

        XCTAssertEqual(
            AuthGateDecision.resolve(profile: profile(serverAccessStatus: .ready, status: .reauthRequiredEdge)),
            .serverAccess
        )

        XCTAssertEqual(
            AuthGateDecision.resolve(profile: profile(serverAccessStatus: .ready, status: .reauthRequiredOrigin)),
            .login
        )
    }

    @MainActor
    func testBootstrapRoutesIncompleteProfileToServerAccess() async throws {
        let harness = try TestHarness()
        let model = harness.makeModel()

        try await harness.profileStore.save(
            profile(serverAccessStatus: .failed, status: .reauthRequiredEdge, isActive: true),
            makeActive: true
        )

        await model.bootstrap()

        XCTAssertEqual(model.route, .onboarding(.serverAccess))
    }

    @MainActor
    func testBootstrapRoutesConnectedProfileToApp() async throws {
        let harness = try TestHarness()
        let model = harness.makeModel()

        try await harness.profileStore.save(
            profile(serverAccessStatus: .ready, status: .connected, isActive: true),
            makeActive: true
        )

        await model.bootstrap()

        XCTAssertEqual(model.route, .app)
    }

    @MainActor
    func testResumeSetupRoutesReadyProfileToLogin() async throws {
        let harness = try TestHarness()
        let model = harness.makeModel()
        let active = profile(serverAccessStatus: .ready, status: .reauthRequiredOrigin, isActive: true)

        try await harness.profileStore.save(active, makeActive: true)
        await model.reloadProfiles()

        model.resumeSetup()

        XCTAssertEqual(model.route, .onboarding(.login))
    }

    @MainActor
    func testReconfigureSetupClearsServerAccessAndReturnsToServerStep() async throws {
        let harness = try TestHarness()
        let model = harness.makeModel()
        let active = profile(
            serverAccessStatus: .ready,
            status: .connected,
            edgeAuthMode: .cloudflareAccessService,
            isActive: true
        )

        try await harness.profileStore.save(active, makeActive: true)
        await model.reloadProfiles()

        await model.reconfigureSetup()

        XCTAssertEqual(model.route, .onboarding(.serverAccess))
        XCTAssertEqual(model.activeProfile?.serverAccess.status, .unknown)
        XCTAssertEqual(model.activeProfile?.status, .invalidConfiguration)
    }

    private func profile(
        serverAccessStatus: ServerAccessStatus,
        status: AuthStatus,
        edgeAuthMode: EdgeAuthMode = .cloudflareAccessSession,
        originAuthMode: OriginAuthMode = .rommBearerPassword,
        hasOriginSession: Bool = false,
        isActive: Bool = false
    ) -> ServerProfile {
        ServerProfile(
            id: "server-\(UUID().uuidString)",
            label: "Test Server",
            baseURL: URL(string: "https://romm.example.com")!,
            edgeAuthMode: edgeAuthMode,
            originAuthMode: originAuthMode,
            capabilities: AuthCapabilities(
                cloudflareAccessDetected: edgeAuthMode != .none,
                genericCookieSSODetected: edgeAuthMode == .genericCookieSSO,
                rommOIDCAvailable: originAuthMode == .rommOIDCSession,
                rommTokenAvailable: originAuthMode == .rommBearerPassword
            ),
            serverAccess: ServerAccessState(
                status: serverAccessStatus,
                verifiedAt: serverAccessStatus == .ready ? "2026-03-18T12:00:00Z" : nil
            ),
            sessionState: SessionState(
                hasEdgeSession: serverAccessStatus == .ready,
                hasOriginSession: hasOriginSession,
                lastValidatedAt: "2026-03-18T12:00:00Z"
            ),
            isActive: isActive,
            status: status,
            lastValidationAt: "2026-03-18T12:00:00Z",
            createdAt: "2026-03-18T11:00:00Z",
            updatedAt: "2026-03-18T12:00:00Z"
        )
    }
}

@MainActor
private struct TestHarness {
    let database: AppDatabase
    let secretStore: any SecretStore
    let profileStore: GRDBServerProfileStore
    let offlineStore: GRDBOfflineReadinessStore
    let authController: AuthSessionController
    let libraryRepository: DefaultLibraryRepository
    let downloadQueue: ManagedDownloadQueue
    let libraryStore: AppManagedLibraryStore
    let networkMonitor: NetworkMonitor

    init() throws {
        let root = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        database = try AppDatabase(rootDirectory: root.appending(path: "Database"))
        secretStore = try FileSecretStore(rootDirectory: root.appending(path: "Secrets"))
        profileStore = GRDBServerProfileStore(database: database)
        offlineStore = GRDBOfflineReadinessStore(database: database)
        authController = AuthSessionController(
            profileStore: profileStore,
            secretStore: secretStore,
            session: .shared
        )
        libraryStore = AppManagedLibraryStore(rootDirectory: root.appending(path: "Library"))
        try libraryStore.ensureRootLayout()
        libraryRepository = DefaultLibraryRepository(
            database: database,
            profileStore: profileStore,
            offlineStore: offlineStore,
            authController: authController
        )
        downloadQueue = ManagedDownloadQueue(
            database: database,
            profileStore: profileStore,
            secretStore: secretStore,
            libraryStore: libraryStore
        )
        networkMonitor = NetworkMonitor()
    }

    func makeModel() -> RommioAppModel {
        RommioAppModel(
            services: RommioServices(
                database: database,
                secretStore: secretStore,
                profileStore: profileStore,
                offlineStore: offlineStore,
                authController: authController,
                libraryRepository: libraryRepository,
                downloadQueue: downloadQueue,
                libraryStore: libraryStore,
                networkMonitor: networkMonitor
            )
        )
    }
}
