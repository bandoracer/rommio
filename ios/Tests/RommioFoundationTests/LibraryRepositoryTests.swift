import XCTest
@testable import RommioContract
@testable import RommioFoundation

final class LibraryRepositoryTests: XCTestCase {
    override func tearDown() {
        ContinuePlayingQueryProtocol.recordedRomsURLs = []
        super.tearDown()
    }

    func testLoadHomeRequestsContinuePlayingByLastPlayedDescending() async throws {
        let repository = try await makeRepository()

        _ = try await repository.loadHome()

        let continuePlayingURL = try XCTUnwrap(
            ContinuePlayingQueryProtocol.recordedRomsURLs.first(where: {
                URLComponents(url: $0, resolvingAgainstBaseURL: false)?
                    .queryItems?
                    .contains(where: { $0.name == "last_played" && $0.value == "true" }) == true
            })
        )
        let queryItems = URLComponents(url: continuePlayingURL, resolvingAgainstBaseURL: false)?.queryItems ?? []
        XCTAssertTrue(queryItems.contains(where: { $0.name == "last_played" && $0.value == "true" }))
        XCTAssertTrue(queryItems.contains(where: { $0.name == "order_by" && $0.value == "last_played" }))
        XCTAssertTrue(queryItems.contains(where: { $0.name == "order_dir" && $0.value == "desc" }))
    }

    /// A page drawn from the cache keeps its order when the server answers (the Library's
    /// console groups reordered a moment after it opened).
    func testCachedPlatformsKeepTheServersOrder() async throws {
        let repository = try await makeRepository()

        let loaded = try await repository.loadPlatforms().map(\.name)
        let cached = try await repository.cachedPlatforms().map(\.name)

        XCTAssertEqual(loaded, ["Atari 7800", "game boy", "Super Nintendo"])
        XCTAssertEqual(cached, loaded)
    }

    private func makeRepository() async throws -> DefaultLibraryRepository {
        let root = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        let database = try AppDatabase(rootDirectory: root.appending(path: "Database"))
        let profileStore = GRDBServerProfileStore(database: database)
        let offlineStore = GRDBOfflineReadinessStore(database: database)
        let secretStore = try FileSecretStore(rootDirectory: root.appending(path: "Secrets"))
        let profile = ServerProfile(
            id: "profile-1",
            label: "Test Server",
            baseURL: URL(string: "https://romm.example.com")!,
            edgeAuthMode: .none,
            originAuthMode: .rommBearerPassword,
            capabilities: AuthCapabilities(
                cloudflareAccessDetected: false,
                genericCookieSSODetected: false,
                rommOIDCAvailable: false,
                rommTokenAvailable: true
            ),
            serverAccess: ServerAccessState(status: .ready, verifiedAt: "2026-03-18T12:00:00Z"),
            sessionState: SessionState(
                hasEdgeSession: false,
                hasOriginSession: true,
                lastValidatedAt: "2026-03-18T12:00:00Z"
            ),
            isActive: true,
            status: .connected,
            lastValidationAt: "2026-03-18T12:00:00Z",
            createdAt: "2026-03-18T11:00:00Z",
            updatedAt: "2026-03-18T12:00:00Z"
        )
        try await profileStore.save(profile, makeActive: true)
        try secretStore.saveDeviceID("device-ios-1", for: profile.id)

        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ContinuePlayingQueryProtocol.self]
        let session = URLSession(configuration: configuration)
        let authController = AuthSessionController(
            profileStore: profileStore,
            secretStore: secretStore,
            session: session
        )
        return DefaultLibraryRepository(
            database: database,
            profileStore: profileStore,
            offlineStore: offlineStore,
            authController: authController
        )
    }
}

private final class ContinuePlayingQueryProtocol: URLProtocol, @unchecked Sendable {
    nonisolated(unsafe) static var recordedRomsURLs: [URL] = []

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url else {
            client?.urlProtocol(self, didFailWithError: URLError(.badURL))
            return
        }

        let body: String
        switch url.path {
        case "/api/roms":
            Self.recordedRomsURLs.append(url)
            body = #"{"items":[],"total":0,"page":1,"per_page":0}"#
        case "/api/collections", "/api/collections/smart", "/api/collections/virtual":
            body = "[]"
        case "/api/platforms":
            // By id, which isn't by name.
            body = #"""
            [{"id": 1, "slug": "snes", "fs_slug": "snes", "name": "Super Nintendo", "rom_count": 3},
             {"id": 2, "slug": "atari7800", "fs_slug": "atari7800", "name": "Atari 7800", "rom_count": 2},
             {"id": 3, "slug": "gb", "fs_slug": "gb", "name": "game boy", "rom_count": 5}]
            """#
        default:
            client?.urlProtocol(self, didFailWithError: URLError(.unsupportedURL))
            return
        }

        let response = HTTPURLResponse(
            url: url,
            statusCode: 200,
            httpVersion: nil,
            headerFields: ["Content-Type": "application/json"]
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
