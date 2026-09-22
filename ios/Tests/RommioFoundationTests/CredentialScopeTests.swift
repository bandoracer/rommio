import Foundation
import Network
import XCTest
import RommioContract
@testable import RommioFoundation

final class CredentialScopeTests: XCTestCase {
    func testURLSessionRedirectDoesNotLeakCredentialsToAnotherPort() async throws {
        let external = try RedirectHTTPFixture(response: "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK")
        external.start()
        defer { external.stop() }
        await fulfillment(of: [external.ready], timeout: 3)
        let destination = try XCTUnwrap(external.url)
        let origin = try RedirectHTTPFixture(response: "HTTP/1.1 302 Found\r\nLocation: \(destination.absoluteString)\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        origin.start()
        defer { origin.stop() }
        await fulfillment(of: [origin.ready], timeout: 3)
        let baseURL = try XCTUnwrap(origin.url)
        var request = URLRequest(url: baseURL)
        for header in CredentialScope.credentialHeaders { request.setValue("secret", forHTTPHeaderField: header) }
        let session = URLSession(configuration: .ephemeral)
        defer { session.invalidateAndCancel() }
        let (data, response) = try await session.data(for: request, delegate: ScopedRedirectDelegate(baseURL: baseURL))
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
        XCTAssertEqual(data, Data("OK".utf8))
        let initial = origin.requestText.lowercased()
        let redirected = external.requestText.lowercased()
        XCTAssertTrue(initial.contains("cf-access-client-secret: secret"))
        XCTAssertTrue(initial.contains("authorization: secret"))
        for header in CredentialScope.credentialHeaders { XCTAssertFalse(redirected.contains("\(header.lowercased()):"), header) }
    }

    func testScopeIncludesSchemeHostPortAndDeploymentPath() throws {
        let scope = CredentialScope(baseURL: URL(string: "https://romm.example:443/romm/")!)
        XCTAssertTrue(scope.contains(URL(string: "https://romm.example/romm/api/me")!))
        for raw in [
            "http://romm.example/romm/api/me", "https://romm.example:8443/romm/api/me",
            "https://images.example/romm/api/me", "https://romm.example/romm-other/api/me",
            "https://romm.example/romm/../other", "https://romm.example/romm/a%2f..%2fother",
            "https://user:password@romm.example/romm/api/me",
        ] { XCTAssertFalse(scope.contains(try XCTUnwrap(URL(string: raw))), raw) }
    }

    func testRedirectsStripAllCredentialsAndRefuseMutationBodiesOutsideScope() throws {
        let delegate = ScopedRedirectDelegate(baseURL: URL(string: "https://romm.example/romm")!)
        for raw in ["https://other.example/image", "https://romm.example/other", "http://romm.example/romm/image"] {
            var request = URLRequest(url: URL(string: raw)!)
            for name in CredentialScope.credentialHeaders { request.setValue("secret", forHTTPHeaderField: name) }
            let redirected = try XCTUnwrap(delegate.redirectedRequest(request))
            for name in CredentialScope.credentialHeaders { XCTAssertNil(redirected.value(forHTTPHeaderField: name)) }
            XCTAssertFalse(redirected.httpShouldHandleCookies)
            request.httpMethod = "POST"
            request.httpBody = Data("password=private".utf8)
            XCTAssertNil(delegate.redirectedRequest(request))
        }
    }

    func testDecoratorDoesNotReadOrAttachSecretsForExternalAssets() async throws {
        let profile = ServerProfile(
            id: "test", label: "Test", baseURL: URL(string: "https://romm.example/romm")!,
            edgeAuthMode: .cloudflareAccessService, originAuthMode: .rommBasicLegacy,
            capabilities: AuthCapabilities(), serverAccess: ServerAccessState(), sessionState: SessionState(),
            isActive: true, status: .connected, createdAt: "2026-09-17", updatedAt: "2026-09-17"
        )
        let decorator = AuthenticatedRequestDecorator(profileID: profile.id, secretStore: RejectSecretReadStore()) { _ in profile }
        var request = URLRequest(url: URL(string: "https://images.example/cover")!)
        request.setValue("stale", forHTTPHeaderField: "Authorization")
        let decorated = try await decorator.decorate(request)
        for name in CredentialScope.credentialHeaders { XCTAssertNil(decorated.value(forHTTPHeaderField: name)) }
        XCTAssertFalse(decorated.httpShouldHandleCookies)
    }
}

private final class RedirectHTTPFixture: @unchecked Sendable {
    let ready = XCTestExpectation(description: "HTTP fixture listening")
    private let listener: NWListener
    private let response: Data
    private let lock = NSLock()
    private var captured = ""
    var url: URL? { listener.port.flatMap { URL(string: "http://127.0.0.1:\($0.rawValue)/romm/") } }
    var requestText: String { lock.withLock { captured } }

    init(response: String) throws {
        listener = try NWListener(using: .tcp, on: .any)
        self.response = Data(response.utf8)
    }

    func start() {
        listener.stateUpdateHandler = { [weak self] state in
            if case .ready = state { self?.ready.fulfill() }
        }
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { connection.cancel(); return }
            connection.start(queue: .global())
            connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [self] data, _, _, _ in
                lock.withLock { captured = String(decoding: data ?? Data(), as: UTF8.self) }
                connection.send(content: response, completion: .contentProcessed { _ in connection.cancel() })
            }
        }
        listener.start(queue: .global())
    }

    func stop() { listener.cancel() }
}

private struct RejectSecretReadStore: SecretStore {
    func save(_ value: Data, for key: String) throws { throw URLError(.noPermissionsToReadFile) }
    func read(key: String) throws -> Data? { throw URLError(.noPermissionsToReadFile) }
    func remove(key: String) throws { throw URLError(.noPermissionsToReadFile) }
}
