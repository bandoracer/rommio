import XCTest
@testable import RommioFoundation

final class OneTouchSetupClientTests: XCTestCase {
    private func client(host: String = "setup.example.com") -> OneTouchSetupClient {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [SetupURLProtocol.self]
        return OneTouchSetupClient(baseURL: URL(string: "https://\(host)")!,
                                   approvalOrigin: URL(string: "https://approve.example.com")!,
                                   session: URLSession(configuration: config))
    }
    func testStartAndPollUsePostBodyAndDecodeApprovedConfiguration() async throws {
        let client = client()
        let pairing = try await client.start(deviceName: "Phone")
        XCTAssertEqual(pairing.code, "ABCDEF")
        guard case .approved(let configuration) = try await client.poll(pairing) else { return XCTFail("Expected approval") }
        XCTAssertEqual(configuration.rommUrl.host, "romm.example.com")
        XCTAssertEqual(configuration.accessProfile.clientSecret, "test-secret")
        try await client.complete(pairing)
    }
    func testMissingSetupServiceIsNotPresentedAsAnExpiredCode() async {
        do { _ = try await client(host: "missing.example.com").start(deviceName: "Phone"); XCTFail("Missing service accepted") }
        catch OneTouchSetupError.unavailable { }
        catch { XCTFail("Unexpected error: \(error)") }
    }
    func testRejectsApprovalLinkToAnotherHost() async {
        do { _ = try await client(host: "bad-approval.example.com").start(deviceName: "Phone"); XCTFail("Accepted untrusted approval host") }
        catch { XCTAssertTrue(error is OneTouchSetupError) }
    }
    func testRejectsUnsafeServerURL() throws {
        for url in ["http://romm.example.com", "https://user:pass@romm.example.com", "https://romm.example.com?token=secret"] {
            let data = Data("{\"rommUrl\":\"\(url)\",\"accessProfile\":{\"type\":\"serviceToken\",\"clientId\":\"id\",\"clientSecret\":\"secret\"}}".utf8)
            let configuration = try JSONDecoder().decode(SetupConfiguration.self, from: data)
            XCTAssertThrowsError(try configuration.validate())
        }
    }
    func testExpiredAndDeniedAreExplicitStates() async throws {
        for host in ["expired.example.com", "denied.example.com"] {
            let client = client(host: host)
            let pairing = try await client.start(deviceName: "Phone")
            switch try await client.poll(pairing) {
            case .expired: XCTAssertTrue(host.hasPrefix("expired"))
            case .denied: XCTAssertTrue(host.hasPrefix("denied"))
            default: XCTFail("Expected terminal state")
            }
        }
    }
}

private final class SetupURLProtocol: URLProtocol, @unchecked Sendable {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let url = request.url!
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertNil(url.query)
        XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
        XCTAssertNil(request.value(forHTTPHeaderField: "CF-Access-Client-Secret"))
        var status = 200
        let body: String
        if url.host == "missing.example.com" {
            status = 404; body = "Not found"
        } else if url.path == "/device/start" {
            let approval = url.host == "bad-approval.example.com" ? "evil.example.com" : "approve.example.com"
            body = "{\"code\":\"ABCDEF\",\"secret\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\",\"approveUrl\":\"https://\(approval)/a/ABCDEF\",\"expiresInSeconds\":600,\"pollIntervalSeconds\":3}"
        } else if url.host == "expired.example.com" {
            status = 404; body = "{\"state\":\"expired\"}"
        } else if url.host == "denied.example.com" {
            body = "{\"state\":\"denied\"}"
        } else {
            body = "{\"state\":\"approved\",\"rommUrl\":\"https://romm.example.com\",\"accessProfile\":{\"type\":\"serviceToken\",\"clientId\":\"test-id\",\"clientSecret\":\"test-secret\"}}"
        }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: url, statusCode: status, httpVersion: nil, headerFields: ["Content-Type":"application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
