import Foundation
import Network
import XCTest
import RommioContract
@testable import RommioFoundation

final class ProfileCookiesTests: XCTestCase {
    private func secrets() throws -> FileSecretStore {
        let root = FileManager.default.temporaryDirectory.appending(path: "rommio-cookies-\(UUID())")
        addTeardownBlock { try? FileManager.default.removeItem(at: root) }
        return try FileSecretStore(rootDirectory: root)
    }
    private let base = URL(string: "https://romm.example/romm")!
    private func response(_ base: URL, cookie: String) -> HTTPURLResponse {
        HTTPURLResponse(url: base.appending(path: "api/users/me"), statusCode: 200, httpVersion: nil, headerFields: ["Set-Cookie": cookie])!
    }
    func testProfilesPathsExpiryAndLogoutGenerationAreIsolated() async throws {
        let owner = ProfileCookies(secrets: try secrets())
        let url = base.appending(path: "api/users/me")
        let a = try await owner.decorate(URLRequest(url: url), profileID: "A", baseURL: base)
        let b = try await owner.decorate(URLRequest(url: url), profileID: "B", baseURL: base)
        try await owner.receive(response(base, cookie: "session=A; Path=/romm; Secure; HttpOnly; Max-Age=3600"), request: a)
        try await owner.receive(response(base, cookie: "session=B; Path=/romm; Secure; Max-Age=3600"), request: b)
        let updatedA = try await owner.decorate(URLRequest(url: url), profileID: "A", baseURL: base)
        let updatedB = try await owner.decorate(URLRequest(url: url), profileID: "B", baseURL: base)
        XCTAssertEqual(updatedA.value(forHTTPHeaderField: "Cookie"), "session=A")
        XCTAssertEqual(updatedB.value(forHTTPHeaderField: "Cookie"), "session=B")
        XCTAssertFalse(updatedA.httpShouldHandleCookies)
        for raw in ["https://romm.example/other/api/me", "http://romm.example/romm/api/me", "https://romm.example:8443/romm/api/me"] {
            let outside = try await owner.redirect(URLRequest(url: URL(string: raw)!), original: updatedA)
            XCTAssertNil(outside.value(forHTTPHeaderField: "Cookie"))
        }
        try await owner.clear(profileID: "A", baseURL: base)
        try await owner.receive(response(base, cookie: "session=LATE; Path=/romm"), request: a)
        let afterLogout = try await owner.decorate(URLRequest(url: url), profileID: "A", baseURL: base)
        let bAfter = try await owner.decorate(URLRequest(url: url), profileID: "B", baseURL: base)
        XCTAssertNil(afterLogout.value(forHTTPHeaderField: "Cookie"))
        XCTAssertEqual(bAfter.value(forHTTPHeaderField: "Cookie"), "session=B")
        try await owner.receive(response(base, cookie: "session=; Path=/romm; Max-Age=0"), request: bAfter)
        let expired = try await owner.decorate(URLRequest(url: url), profileID: "B", baseURL: base)
        XCTAssertNil(expired.value(forHTTPHeaderField: "Cookie"))
    }
    func testPersistentCookiesSurviveRecreationButSessionCookiesDoNot() async throws {
        let secrets = try secrets(); let owner = ProfileCookies(secrets: secrets)
        let request = try await owner.decorate(URLRequest(url: base.appending(path: "api/me")), profileID: "A", baseURL: base)
        try await owner.receive(response(base, cookie: "persistent=yes; Path=/; Max-Age=3600"), request: request)
        try await owner.receive(response(base, cookie: "session=temporary; Path=/"), request: request)
        let recreated = ProfileCookies(secrets: secrets)
        let restored = try await recreated.decorate(URLRequest(url: base.appending(path: "api/me")), profileID: "A", baseURL: base)
        XCTAssertEqual(restored.value(forHTTPHeaderField: "Cookie"), "persistent=yes")
        let otherDeployment = URL(string: "https://romm.example/other")!
        let separate = try await recreated.decorate(URLRequest(url: otherDeployment), profileID: "A", baseURL: otherDeployment)
        XCTAssertNil(separate.value(forHTTPHeaderField: "Cookie"))
    }
    func testBrowserImportRejectsRetiredGenerationAndForeignDomain() async throws {
        let owner = ProfileCookies(secrets: try secrets())
        let context = try await owner.context(profileID: "A", baseURL: base)
        let cookie = BrowserCookie(name: "session", value: "A", domain: "romm.example", path: "/", secure: true, httpOnly: true, expiresAt: Date().addingTimeInterval(3600))
        var foreign = cookie; foreign.domain = "foreign.example"
        try await owner.importBrowser([cookie, foreign], context: context)
        let stored = try await owner.snapshot(context)
        XCTAssertEqual(stored, [cookie])
        try await owner.clear(profileID: "A", baseURL: base)
        do { try await owner.importBrowser([cookie], context: context); XCTFail("Retired browser session was imported") } catch is CancellationError { }
    }
    func testWrongDomainSetCookieCannotPolluteNativeJar() async throws {
        let owner = ProfileCookies(secrets: try secrets())
        let request = try await owner.decorate(URLRequest(url: base), profileID: "A", baseURL: base)
        try await owner.receive(response(base, cookie: "session=evil; Domain=foreign.example; Path=/"), request: request)
        let native = try await owner.decorate(URLRequest(url: base), profileID: "A", baseURL: base)
        XCTAssertNil(native.value(forHTTPHeaderField: "Cookie"))
    }
    func testRealHTTPRedirectTransfersCookieWithoutUsingGlobalStore() async throws {
        let server = try CookieHTTPFixture()
        server.start(); defer { server.stop() }
        await fulfillment(of: [server.ready], timeout: 3)
        let base = try XCTUnwrap(server.url)
        let secrets = try secrets(); let owner = ProfileCookies(secrets: secrets)
        let cookie = HTTPCookie(properties: [.name: "global", .value: "MUST-NOT-SEND", .domain: "127.0.0.1", .path: "/"])!
        let config = URLSessionConfiguration.ephemeral
        config.httpCookieStorage?.setCookie(cookie)
        let session = URLSession(configuration: config); defer { session.invalidateAndCancel() }
        let profile = ServerProfile(id: "A", label: "A", baseURL: base, edgeAuthMode: .none, originAuthMode: .rommOIDCSession, capabilities: AuthCapabilities(), serverAccess: ServerAccessState(), sessionState: SessionState(), isActive: true, status: .connected, createdAt: "", updatedAt: "")
        let decorator = AuthenticatedRequestDecorator(profileID: profile.id, secretStore: secrets, cookies: owner) { _ in profile }
        let client = RommAPIClient(baseURL: base, session: session, decorator: decorator)
        let user = try await client.getCurrentUser()
        XCTAssertEqual(user.id, 7)
        let requests = server.requests
        XCTAssertEqual(requests.count, 2)
        XCTAssertFalse(requests[0].lowercased().contains("cookie:"))
        XCTAssertTrue(requests[1].lowercased().contains("cookie: session=fresh"))
        XCTAssertFalse(requests.joined().contains("MUST-NOT-SEND"))
        let updated = try await decorator.decorate(URLRequest(url: base.appending(path: "api/users/me")))
        XCTAssertEqual(updated.value(forHTTPHeaderField: "Cookie"), "session=fresh")
        let other = try await owner.decorate(URLRequest(url: base.appending(path: "api/users/me")), profileID: "B", baseURL: base)
        XCTAssertNil(other.value(forHTTPHeaderField: "Cookie"))
        let destination = FileManager.default.temporaryDirectory.appending(path: "rommio-cookie-download-\(UUID())")
        defer { try? FileManager.default.removeItem(at: destination) }
        let count = try await client.download(from: base.appending(path: "game.bin"), to: destination, expectedBytes: 13)
        XCTAssertEqual(count, 13)
        XCTAssertEqual(try Data(contentsOf: destination), Data("synthetic ROM".utf8))
        XCTAssertTrue(server.requests.last!.lowercased().contains("cookie: session=fresh"))
    }
}

private final class CookieHTTPFixture: @unchecked Sendable {
    let ready = XCTestExpectation(description: "Cookie HTTP fixture listening")
    private let listener: NWListener
    private let lock = NSLock()
    private var captured: [String] = []
    var requests: [String] { lock.withLock { captured } }
    var url: URL? { listener.port.flatMap { URL(string: "http://127.0.0.1:\($0.rawValue)/romm/") } }
    init() throws { listener = try NWListener(using: .tcp, on: .any) }
    func start() {
        listener.stateUpdateHandler = { [weak self] state in if case .ready = state { self?.ready.fulfill() } }
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { connection.cancel(); return }
            connection.start(queue: .global())
            self.receive(connection, accumulated: Data())
        }
        listener.start(queue: .global())
    }
    private func receive(_ connection: NWConnection, accumulated: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [self] data, _, complete, error in
            let data = accumulated + (data ?? Data())
            guard let end = data.range(of: Data("\r\n\r\n".utf8)) else {
                if !complete && error == nil { receive(connection, accumulated: data) } else { connection.cancel() }
                return
            }
            let text = String(decoding: data[..<end.upperBound], as: UTF8.self)
            lock.withLock { captured.append(text) }
            let response: String
            if text.hasPrefix("GET /romm/api/users/me ") {
                response = "HTTP/1.1 302 Found\r\nLocation: /romm/api/after\r\nSet-Cookie: session=fresh; Path=/romm; HttpOnly; Max-Age=3600\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            } else if text.hasPrefix("GET /romm/game.bin ") {
                response = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: 13\r\nConnection: close\r\n\r\nsynthetic ROM"
            } else {
                let body = "{\"id\":7,\"username\":\"fixture\",\"email\":\"fixture@example.test\",\"role\":\"user\"}"
                response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: \(body.utf8.count)\r\nConnection: close\r\n\r\n\(body)"
            }
            connection.send(content: Data(response.utf8), completion: .contentProcessed { _ in connection.cancel() })
        }
    }
    func stop() { listener.cancel() }
}
