import Foundation
import XCTest
@testable import RommioContract
@testable import RommioFoundation

final class AuthDiscoveryServiceTests: XCTestCase {
    override func tearDown() {
        StubURLProtocol.handler = nil
        super.tearDown()
    }

    func testDiscoverPrefersHTTPSWhenSchemeIsMissing() async throws {
        let service = makeService { request in
            switch (request.httpMethod ?? "GET", request.url?.absoluteString) {
            case ("GET", "https://romm.example.com"):
                return .json(url: request.url!, status: 200, body: #"{"SYSTEM":"RomM"}"#)
            case ("GET", "https://romm.example.com/api/login/openid"):
                return .empty(url: request.url!, status: 404)
            case ("POST", "https://romm.example.com/api/token"):
                return .json(url: request.url!, status: 400, body: #"{"detail":"missing credentials"}"#)
            default:
                throw URLError(.cannotConnectToHost)
            }
        }

        let result = try await service.discover(rawBaseURL: "romm.example.com/")

        XCTAssertEqual(result.baseURL.absoluteString, "https://romm.example.com")
        XCTAssertEqual(result.recommendedOriginAuthMode, .rommBearerPassword)
    }

    func testDiscoverFallsBackToHTTPWhenHTTPSIsUnavailable() async throws {
        let service = makeService { request in
            guard let url = request.url?.absoluteString else {
                throw URLError(.badURL)
            }

            if url.hasPrefix("https://") {
                throw URLError(.cannotConnectToHost)
            }

            switch (request.httpMethod ?? "GET", url) {
            case ("GET", "http://192.168.1.50"):
                return .json(url: request.url!, status: 200, body: #"{"SYSTEM":"RomM"}"#)
            case ("GET", "http://192.168.1.50/api/login/openid"):
                return .empty(url: request.url!, status: 404)
            case ("POST", "http://192.168.1.50/api/token"):
                return .empty(url: request.url!, status: 404)
            default:
                throw URLError(.cannotConnectToHost)
            }
        }

        let result = try await service.discover(rawBaseURL: "192.168.1.50")

        XCTAssertEqual(result.baseURL.absoluteString, "http://192.168.1.50")
        XCTAssertEqual(result.recommendedOriginAuthMode, .rommBasicLegacy)
    }

    func testDiscoverReturnsHelpfulErrorForUnreachableHost() async {
        let service = makeService { _ in
            throw URLError(.cannotConnectToHost)
        }

        do {
            _ = try await service.discover(rawBaseURL: "romm.example.com")
            XCTFail("Expected discovery to fail.")
        } catch {
            XCTAssertTrue(error.localizedDescription.contains("https://romm.example.com"))
            XCTAssertTrue(error.localizedDescription.contains("http://romm.example.com"))
            XCTAssertFalse(error.localizedDescription.contains("NSURLErrorDomain"))
        }
    }

    private func makeService(
        handler: @escaping @Sendable (URLRequest) throws -> StubResponse
    ) -> AuthDiscoveryService {
        StubURLProtocol.handler = handler
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubURLProtocol.self]
        let session = URLSession(configuration: configuration)
        return AuthDiscoveryService(session: session)
    }
}

private struct StubResponse {
    let response: HTTPURLResponse
    let data: Data

    static func empty(url: URL, status: Int) -> StubResponse {
        StubResponse(
            response: HTTPURLResponse(url: url, statusCode: status, httpVersion: nil, headerFields: [:])!,
            data: Data()
        )
    }

    static func json(url: URL, status: Int, body: String) -> StubResponse {
        StubResponse(
            response: HTTPURLResponse(
                url: url,
                statusCode: status,
                httpVersion: nil,
                headerFields: ["Content-Type": "application/json"]
            )!,
            data: Data(body.utf8)
        )
    }
}

private final class StubURLProtocol: URLProtocol, @unchecked Sendable {
    nonisolated(unsafe) static var handler: (@Sendable (URLRequest) throws -> StubResponse)?

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let handler = Self.handler else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
            return
        }

        do {
            let stub = try handler(request)
            client?.urlProtocol(self, didReceive: stub.response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: stub.data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}
}
