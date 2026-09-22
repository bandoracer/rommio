import Foundation

public struct SetupPairing: Decodable, Sendable {
    public let code: String
    public let secret: String
    public let approveUrl: URL
    public let expiresInSeconds: Int
    public let pollIntervalSeconds: Int
}

public struct SetupConfiguration: Decodable, Sendable {
    public struct AccessProfile: Decodable, Sendable {
        public let type: String
        public let clientId: String
        public let clientSecret: String
    }
    public let rommUrl: URL
    public let accessProfile: AccessProfile

    public func validate() throws {
        guard Self.isSecureURL(rommUrl), accessProfile.type == "serviceToken",
              !accessProfile.clientId.isEmpty, !accessProfile.clientSecret.isEmpty,
              !accessProfile.clientId.contains(where: { $0.isNewline }),
              !accessProfile.clientSecret.contains(where: { $0.isNewline }) else {
            throw OneTouchSetupError.invalidResponse
        }
    }

    static func isSecureURL(_ url: URL) -> Bool {
        url.scheme == "https" && url.host?.isEmpty == false && url.user == nil && url.password == nil
            && url.query == nil && url.fragment == nil
    }
}

public enum SetupPollResult: Sendable {
    case pending
    case approved(SetupConfiguration)
    case denied
    case expired
}

public enum OneTouchSetupError: Error, LocalizedError {
    case invalidResponse, unavailable, rateLimited, expired, denied
    public var errorDescription: String? {
        switch self {
        case .invalidResponse: "The setup service returned an invalid configuration."
        case .unavailable: "Could not reach the setup service. Check your connection and try again."
        case .rateLimited: "Too many setup attempts. Wait a minute and try again."
        case .expired: "This setup code expired. Start again for a new code."
        case .denied: "This setup request was denied. You can start again."
        }
    }
}

public protocol OneTouchSetupServing: Sendable {
    func start(deviceName: String) async throws -> SetupPairing
    func poll(_ pairing: SetupPairing) async throws -> SetupPollResult
    func complete(_ pairing: SetupPairing) async throws
    func cancel(_ pairing: SetupPairing) async
}

/// The unauthenticated pairing connection never carries an existing server's
/// cookies or credentials. Polling secrets travel in POST bodies, not URLs.
public final class OneTouchSetupClient: OneTouchSetupServing, Sendable {
    public static let defaultURL = URL(string: "https://rommio-setup.ryan-personal-fa9.workers.dev")!
    private let baseURL: URL
    private let approvalOrigin: URL
    private let session: URLSession

    public init(
        baseURL: URL = OneTouchSetupClient.defaultURL,
        approvalOrigin: URL = URL(string: "https://rommio-setup.borchetta.xyz")!,
        session: URLSession? = nil
    ) {
        self.baseURL = baseURL
        self.approvalOrigin = approvalOrigin
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpShouldSetCookies = false
        configuration.urlCredentialStorage = nil
        configuration.timeoutIntervalForRequest = 20
        self.session = session ?? URLSession(configuration: configuration, delegate: NoSetupRedirects(), delegateQueue: nil)
    }

    public func start(deviceName: String) async throws -> SetupPairing {
        let data = try await post("start", body: ["deviceName": String(deviceName.prefix(64))])
        let pairing = try JSONDecoder().decode(SetupPairing.self, from: data)
        guard pairing.code.range(of: "^[A-Z2-9]{6}$", options: .regularExpression) != nil,
              pairing.secret.range(of: "^[A-Z2-9]{32}$", options: .regularExpression) != nil,
              SetupConfiguration.isSecureURL(pairing.approveUrl),
              pairing.approveUrl.host == approvalOrigin.host,
              pairing.approveUrl.port == approvalOrigin.port,
              pairing.approveUrl.path == "/a/\(pairing.code)",
              (1...600).contains(pairing.expiresInSeconds),
              (1...30).contains(pairing.pollIntervalSeconds) else { throw OneTouchSetupError.invalidResponse }
        return pairing
    }

    public func poll(_ pairing: SetupPairing) async throws -> SetupPollResult {
        let data: Data
        do { data = try await post("poll", body: credentials(pairing)) }
        catch OneTouchSetupError.expired { return .expired }
        struct State: Decodable { let state: String }
        switch try JSONDecoder().decode(State.self, from: data).state {
        case "pending": return .pending
        case "approved":
            let configuration = try JSONDecoder().decode(SetupConfiguration.self, from: data)
            try configuration.validate()
            return .approved(configuration)
        case "denied": return .denied
        case "expired": return .expired
        default: throw OneTouchSetupError.invalidResponse
        }
    }

    public func complete(_ pairing: SetupPairing) async throws {
        _ = try await post("complete", body: credentials(pairing))
    }
    public func cancel(_ pairing: SetupPairing) async {
        _ = try? await post("cancel", body: credentials(pairing))
    }
    private func credentials(_ pairing: SetupPairing) -> [String: String] {
        ["code": pairing.code, "secret": pairing.secret]
    }
    private func post(_ endpoint: String, body: [String: String]) async throws -> Data {
        guard SetupConfiguration.isSecureURL(baseURL) else { throw OneTouchSetupError.invalidResponse }
        var request = URLRequest(url: baseURL.appending(path: "device/\(endpoint)"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(body)
        let (data, response) = try await session.data(for: request)
        guard let response = response as? HTTPURLResponse else { throw OneTouchSetupError.invalidResponse }
        switch response.statusCode {
        case 200: return data
        case 404 where endpoint != "start": throw OneTouchSetupError.expired
        case 429: throw OneTouchSetupError.rateLimited
        default: throw OneTouchSetupError.unavailable
        }
    }
}

private final class NoSetupRedirects: NSObject, URLSessionTaskDelegate, Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping @Sendable (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}
