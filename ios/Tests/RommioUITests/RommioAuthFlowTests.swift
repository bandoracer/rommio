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

    @MainActor
    func testConnectVerifiesServerAndAdvancesStraightToLogin() async throws {
        let harness = try TestHarness(session: connectionSession())
        let model = harness.makeModel()
        model.serverURL = "romm.example.com"

        await model.connectServer()

        XCTAssertEqual(model.route, .onboarding(.login))
        XCTAssertEqual(model.activeProfile?.baseURL.absoluteString, "https://romm.example.com")
        XCTAssertEqual(model.activeProfile?.serverAccess.status, .ready)
        XCTAssertNil(model.errorMessage)
        XCTAssertFalse(model.isBusy)
    }

    @MainActor
    func testConnectDoesNotAcceptAnHTMLPageAsAWorkingServer() async throws {
        let harness = try TestHarness(session: connectionSession())
        let model = harness.makeModel()
        model.beginSetup()
        model.serverURL = "https://unrelated.example.com"
        model.selectEdgeAuthMode(.none)

        await model.connectServer()

        XCTAssertEqual(model.route, .onboarding(.serverAccess))
        XCTAssertEqual(model.activeProfile?.serverAccess.status, .failed)
        XCTAssertNotNil(model.errorMessage)
    }

    @MainActor
    func testConnectAfterEditingAddressDoesNotReusePreviousDiscovery() async throws {
        let harness = try TestHarness(session: connectionSession())
        let model = harness.makeModel()
        model.serverURL = "https://romm.example.com"
        await model.connectServer()
        let previousID = model.activeProfile?.id

        model.changeServerAccess()
        model.serverURL = "https://second.example.com"
        await model.connectServer()

        XCTAssertEqual(model.route, .onboarding(.login))
        XCTAssertEqual(model.activeProfile?.baseURL.host, "second.example.com")
        XCTAssertNotEqual(model.activeProfile?.id, previousID)
    }

    @MainActor
    func testEditingAddressClearsPendingCredentialsAndAccessOverride() async throws {
        let model = try TestHarness().makeModel()
        model.cloudflareClientID = "old-client"
        model.cloudflareClientSecret = "old-secret"
        model.password = "old-password"
        model.selectEdgeAuthMode(.cloudflareAccessService)
        model.serverURL = "https://another.example.com"
        XCTAssertTrue(model.cloudflareClientID.isEmpty)
        XCTAssertTrue(model.cloudflareClientSecret.isEmpty)
        XCTAssertTrue(model.password.isEmpty)
        XCTAssertFalse(model.isEdgeAuthModeOverridden)
    }

    @MainActor
    func testOneTouchStoresEdgeCredentialsAndVerifiesBeforeLogin() async throws {
        let harness = try TestHarness(session: connectionSession())
        let model = harness.makeModel()
        let configuration = try JSONDecoder().decode(SetupConfiguration.self, from: Data("""
        {"rommUrl":"https://romm.example.com","accessProfile":{"type":"serviceToken","clientId":"setup-client","clientSecret":"setup-secret"}}
        """.utf8))
        try await model.applyOneTouchConfiguration(configuration)
        XCTAssertEqual(model.route, .onboarding(.login))
        XCTAssertEqual(model.activeProfile?.serverAccess.status, .ready)
        let profile = try XCTUnwrap(model.activeProfile)
        let credentials = try harness.secretStore.readCloudflareCredentials(profileID: profile.id)
        XCTAssertEqual(credentials?.clientID, "setup-client")
        XCTAssertTrue(model.cloudflareClientSecret.isEmpty)
    }

    @MainActor
    func testOneTouchDoesNotAdvanceWhenServerReturnsHTML() async throws {
        let model = try TestHarness(session: connectionSession()).makeModel()
        model.beginSetup()
        let configuration = try JSONDecoder().decode(SetupConfiguration.self, from: Data("""
        {"rommUrl":"https://unrelated.example.com","accessProfile":{"type":"serviceToken","clientId":"setup-client","clientSecret":"setup-secret"}}
        """.utf8))
        do { try await model.applyOneTouchConfiguration(configuration); XCTFail("Unverified server accepted") }
        catch { XCTAssertEqual(model.route, .onboarding(.serverAccess)) }
        XCTAssertFalse(model.isBusy)
    }

    private func connectionSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ConnectionURLProtocol.self]
        return URLSession(configuration: configuration)
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

    init(session: URLSession = .shared) throws {
        let root = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        database = try AppDatabase(rootDirectory: root.appending(path: "Database"))
        secretStore = try FileSecretStore(rootDirectory: root.appending(path: "Secrets"))
        profileStore = GRDBServerProfileStore(database: database)
        offlineStore = GRDBOfflineReadinessStore(database: database)
        authController = AuthSessionController(
            profileStore: profileStore,
            secretStore: secretStore,
            discovery: AuthDiscoveryService(session: session),
            session: session
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
            authController: authController,
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

private final class ConnectionURLProtocol: URLProtocol, @unchecked Sendable {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let url = request.url!
        let status: Int
        let body: String
        let contentType: String
        if url.host == "unrelated.example.com" {
            status = 200
            body = "<html>Other website</html>"
            contentType = "text/html"
        } else if url.path == "/api/heartbeat" {
            status = 200
            body = #"{"SYSTEM":{"VERSION":"4.8.1","SHOW_SETUP_WIZARD":false}}"#
            contentType = "application/json"
        } else if url.path == "/api/token" {
            status = 400
            body = #"{"detail":"Missing credentials"}"#
            contentType = "application/json"
        } else {
            status = url.path == "/api/login/openid" ? 404 : 200
            body = "<html>RomM</html>"
            contentType = "text/html"
        }
        let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: nil,
                                       headerFields: ["Content-Type": contentType])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
