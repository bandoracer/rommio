import XCTest
@testable import RommioContract
@testable import RommioFoundation

final class AuthSessionControllerTests: XCTestCase {
    override func tearDown() {
        super.tearDown()
        HTTPCookieStorage.shared.cookies?.forEach { HTTPCookieStorage.shared.deleteCookie($0) }
    }

    func testShouldSendCookiesMatchesAndroidRules() {
        XCTAssertFalse(shouldSendCookies(for: profile(edge: .none, origin: .rommBearerPassword)))
        XCTAssertTrue(shouldSendCookies(for: profile(edge: .cloudflareAccessSession, origin: .rommBearerPassword)))
        XCTAssertTrue(shouldSendCookies(for: profile(edge: .genericCookieSSO, origin: .rommBasicLegacy)))
        XCTAssertFalse(shouldSendCookies(for: profile(edge: .cloudflareAccessService, origin: .rommBearerPassword)))
        XCTAssertTrue(shouldSendCookies(for: profile(edge: .cloudflareAccessService, origin: .rommOIDCSession)))
        XCTAssertTrue(shouldSendCookies(for: profile(edge: .none, origin: .rommOIDCSession)))
    }

    func testCompleteEdgeAccessAttemptCapturesReusableCookieState() async throws {
        let harness = try ControllerHarness()
        let profile = profile(edge: .cloudflareAccessSession, origin: .rommBearerPassword)
        try await harness.profileStore.save(profile, makeActive: true)

        let cookie = try XCTUnwrap(
            HTTPCookie(properties: [
                .domain: "romm.example.com",
                .path: "/",
                .name: "CF_Authorization",
                .value: "cookie-value",
                .secure: "TRUE",
                .expires: Date(timeIntervalSinceNow: 300),
            ])
        )
        HTTPCookieStorage.shared.setCookie(cookie)

        let accessState = try await harness.controller.completeEdgeAccessAttempt(profileID: profile.id)
        let persisted = try await harness.profileStore.profile(id: profile.id)

        XCTAssertEqual(accessState.status, .checking)
        XCTAssertTrue(accessState.cookieNamesSeen.contains("CF_Authorization"))
        XCTAssertTrue(accessState.lastError?.contains("native access test") == true)
        XCTAssertEqual(persisted?.sessionState.hasEdgeSession, true)
    }

    func testLogoutPreservesServerAccessByDefault() async throws {
        let harness = try ControllerHarness()
        let profile = profile(edge: .cloudflareAccessService, origin: .rommBearerPassword, status: .connected)
        try await harness.profileStore.save(profile, makeActive: true)
        try harness.secretStore.saveCloudflareCredentials(
            CloudflareServiceCredentials(clientID: "client", clientSecret: "secret"),
            for: profile.id
        )
        try harness.secretStore.saveBasicCredentials(
            DirectLoginCredentials(username: "user", password: "pass"),
            for: profile.id
        )

        try await harness.controller.logout(profileID: profile.id, clearServerAccess: false)

        let persisted = try await harness.profileStore.profile(id: profile.id)
        XCTAssertEqual(persisted?.serverAccess.status, .ready)
        XCTAssertEqual(persisted?.status, .reauthRequiredOrigin)
        XCTAssertEqual(persisted?.sessionState.hasOriginSession, false)
        XCTAssertNotNil(try harness.secretStore.readCloudflareCredentials(profileID: profile.id))
        XCTAssertNil(try harness.secretStore.readBasicCredentials(profileID: profile.id))
    }

    func testLogoutCanClearServerAccess() async throws {
        let harness = try ControllerHarness()
        let profile = profile(edge: .cloudflareAccessService, origin: .rommOIDCSession, status: .connected)
        try await harness.profileStore.save(profile, makeActive: true)
        try harness.secretStore.saveCloudflareCredentials(
            CloudflareServiceCredentials(clientID: "client", clientSecret: "secret"),
            for: profile.id
        )
        try harness.secretStore.saveDeviceID("device-ios-1", for: profile.id)

        try await harness.controller.logout(profileID: profile.id, clearServerAccess: true)

        let persisted = try await harness.profileStore.profile(id: profile.id)
        XCTAssertEqual(persisted?.serverAccess.status, .unknown)
        XCTAssertEqual(persisted?.status, .reauthRequiredOrigin)
        XCTAssertEqual(persisted?.sessionState.hasEdgeSession, false)
        XCTAssertNil(try harness.secretStore.readCloudflareCredentials(profileID: profile.id))
        XCTAssertNil(try harness.secretStore.readDeviceID(profileID: profile.id))
    }

    func testBearerPasswordGrantIncludesAndroidMobileScopes() async throws {
        let recorder = ScopeRecordingProtocol.Recorder()
        let session = makeStubSession(recorder: recorder)
        let harness = try ControllerHarness(session: session)
        let profile = profile(edge: .cloudflareAccessService, origin: .rommBearerPassword, status: .reauthRequiredOrigin)
        try await harness.profileStore.save(profile, makeActive: true)
        try harness.secretStore.saveCloudflareCredentials(
            CloudflareServiceCredentials(clientID: "client", clientSecret: "secret"),
            for: profile.id
        )

        try await harness.controller.loginWithDirectCredentials(
            profileID: profile.id,
            credentials: DirectLoginCredentials(username: "player", password: "password")
        )

        let capturedBody = try XCTUnwrap(recorder.tokenRequestBody)
        let decodedBody = capturedBody.replacingOccurrences(of: "+", with: " ")
            .removingPercentEncoding ?? capturedBody

        XCTAssertTrue(decodedBody.contains("grant_type=password"))
        XCTAssertTrue(decodedBody.contains("username=player"))
        XCTAssertTrue(decodedBody.contains("password=password"))
        XCTAssertTrue(decodedBody.contains("scope=me.read me.write roms.read platforms.read assets.read assets.write devices.read devices.write firmware.read collections.read roms.user.read roms.user.write users.read users.write"))
        XCTAssertEqual(recorder.currentUserAuthorization, "Bearer token-123")
        XCTAssertEqual(recorder.deviceRegistrationAuthorization, "Bearer token-123")
    }

    private func profile(
        edge: EdgeAuthMode,
        origin: OriginAuthMode,
        status: AuthStatus = .reauthRequiredOrigin
    ) -> ServerProfile {
        ServerProfile(
            id: "server-\(UUID().uuidString)",
            label: "Test Server",
            baseURL: URL(string: "https://romm.example.com")!,
            edgeAuthMode: edge,
            originAuthMode: origin,
            capabilities: AuthCapabilities(
                cloudflareAccessDetected: edge != .none,
                genericCookieSSODetected: edge == .genericCookieSSO,
                rommOIDCAvailable: origin == .rommOIDCSession,
                rommTokenAvailable: origin == .rommBearerPassword
            ),
            serverAccess: ServerAccessState(status: .ready, verifiedAt: "2026-03-18T12:00:00Z"),
            sessionState: SessionState(
                hasEdgeSession: edge != .none,
                hasOriginSession: true,
                lastValidatedAt: "2026-03-18T12:00:00Z"
            ),
            isActive: true,
            status: status,
            lastValidationAt: "2026-03-18T12:00:00Z",
            createdAt: "2026-03-18T11:00:00Z",
            updatedAt: "2026-03-18T12:00:00Z"
        )
    }
}

private struct ControllerHarness {
    let profileStore: GRDBServerProfileStore
    let secretStore: InMemorySecretStore
    let controller: AuthSessionController

    init(session: URLSession = .shared) throws {
        let root = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        let database = try AppDatabase(rootDirectory: root.appending(path: "Database"))
        let profileStore = GRDBServerProfileStore(database: database)
        let secretStore = InMemorySecretStore()
        self.profileStore = profileStore
        self.secretStore = secretStore
        self.controller = AuthSessionController(
            profileStore: profileStore,
            secretStore: secretStore,
            session: session
        )
    }
}

private final class InMemorySecretStore: SecretStore, @unchecked Sendable {
    private var storage: [String: Data] = [:]
    private let lock = NSLock()

    func save(_ value: Data, for key: String) throws {
        lock.lock()
        storage[key] = value
        lock.unlock()
    }

    func read(key: String) throws -> Data? {
        lock.lock()
        let value = storage[key]
        lock.unlock()
        return value
    }

    func remove(key: String) throws {
        lock.lock()
        storage.removeValue(forKey: key)
        lock.unlock()
    }
}

private final class ScopeRecordingProtocol: URLProtocol, @unchecked Sendable {
    final class Recorder: @unchecked Sendable {
        var tokenRequestBody: String?
        var currentUserAuthorization: String?
        var deviceRegistrationAuthorization: String?
    }

    nonisolated(unsafe) static var recorder: Recorder?

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url else {
            client?.urlProtocol(self, didFailWithError: URLError(.badURL))
            return
        }

        switch (request.httpMethod ?? "GET", url.path) {
        case ("POST", "/api/token"):
            Self.recorder?.tokenRequestBody = requestBodyString(request)
            respond(
                status: 200,
                headers: ["Content-Type": "application/json"],
                body: #"{"access_token":"token-123","refresh_token":"refresh-123","token_type":"Bearer","expires_in":3600}"#
            )
        case ("GET", "/api/heartbeat"):
            respond(
                status: 200,
                headers: ["Content-Type": "application/json"],
                body: #"{"SYSTEM":{"VERSION":"1.0.0","SHOW_SETUP_WIZARD":false}}"#
            )
        case ("GET", "/api/users/me"):
            Self.recorder?.currentUserAuthorization = request.value(forHTTPHeaderField: "Authorization")
            respond(
                status: 200,
                headers: ["Content-Type": "application/json"],
                body: #"{"id":1,"username":"player","email":"player@example.com","role":"admin"}"#
            )
        case ("POST", "/api/devices"):
            Self.recorder?.deviceRegistrationAuthorization = request.value(forHTTPHeaderField: "Authorization")
            respond(
                status: 201,
                headers: ["Content-Type": "application/json"],
                body: #"{"device_id":"device-123"}"#
            )
        default:
            client?.urlProtocol(self, didFailWithError: URLError(.unsupportedURL))
        }
    }

    override func stopLoading() {}

    private func respond(status: Int, headers: [String: String], body: String) {
        let response = HTTPURLResponse(
            url: request.url!,
            statusCode: status,
            httpVersion: nil,
            headerFields: headers
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    private func requestBodyString(_ request: URLRequest) -> String? {
        if let httpBody = request.httpBody {
            return String(data: httpBody, encoding: .utf8)
        }

        guard let bodyStream = request.httpBodyStream else {
            return nil
        }

        bodyStream.open()
        defer { bodyStream.close() }

        let bufferSize = 1024
        var data = Data()
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: bufferSize)
        defer { buffer.deallocate() }

        while bodyStream.hasBytesAvailable {
            let read = bodyStream.read(buffer, maxLength: bufferSize)
            guard read > 0 else { break }
            data.append(buffer, count: read)
        }

        return String(data: data, encoding: .utf8)
    }
}

private func makeStubSession(recorder: ScopeRecordingProtocol.Recorder) -> URLSession {
    ScopeRecordingProtocol.recorder = recorder
    let configuration = URLSessionConfiguration.ephemeral
    configuration.protocolClasses = [ScopeRecordingProtocol.self]
    configuration.httpCookieStorage = HTTPCookieStorage.shared
    return URLSession(configuration: configuration)
}
