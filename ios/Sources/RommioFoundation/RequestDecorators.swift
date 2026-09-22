import Foundation
import RommioContract

public enum RequestAuthScope: Sendable {
    case edgeOnly
    case full
}

public func shouldSendCookies(for profile: ServerProfile) -> Bool {
    switch profile.edgeAuthMode {
    case .cloudflareAccessService:
        return profile.originAuthMode == .rommOIDCSession
    case .cloudflareAccessSession, .genericCookieSSO:
        return true
    case .none:
        return profile.originAuthMode == .rommOIDCSession
    }
}

public final class AuthenticatedRequestDecorator: RequestDecorating, @unchecked Sendable {
    private let cookies: ProfileCookies?
    private let profileID: String
    private let secretStore: SecretStore
    private let scope: RequestAuthScope
    private let refreshToken: @Sendable (String?) async throws -> Void
    private let profileLookup: @Sendable (String) async throws -> ServerProfile?

    public init(
        profileID: String,
        secretStore: SecretStore,
        scope: RequestAuthScope = .full,
        cookies: ProfileCookies? = nil,
        refreshToken: @escaping @Sendable (String?) async throws -> Void = { _ in },
        profileLookup: @escaping @Sendable (String) async throws -> ServerProfile?
    ) {
        self.cookies = cookies
        self.profileID = profileID
        self.secretStore = secretStore
        self.scope = scope
        self.refreshToken = refreshToken
        self.profileLookup = profileLookup
    }

    public func decorate(_ request: URLRequest) async throws -> URLRequest {
        var request = CredentialScope.removingCredentials(from: request)
        guard let profile = try await profileLookup(profileID), let url = request.url,
              CredentialScope(baseURL: profile.baseURL).contains(url) else { return request }
        request.httpShouldHandleCookies = false
        if shouldSendCookies(for: profile), let cookies {
            request = try await cookies.decorate(request, profileID: profileID, baseURL: profile.baseURL)
        }

        if profile.edgeAuthMode == .cloudflareAccessService,
           let cloudflare = try secretStore.readCloudflareCredentials(profileID: profileID) {
            request.setValue(cloudflare.clientID, forHTTPHeaderField: "CF-Access-Client-Id")
            request.setValue(cloudflare.clientSecret, forHTTPHeaderField: "CF-Access-Client-Secret")
        }

        guard scope == .full else { return request }
        if let owner = try secretStore.verifiedAccount(profileID: profileID), owner.baseURL != profile.baseURL {
            throw AccountBindingError()
        }

        if profile.originAuthMode == .rommBearerPassword {
            try await refreshToken(nil)
            try Task.checkCancellation()
        }
        if profile.originAuthMode == .rommBearerPassword,
           let tokenBundle = try secretStore.readTokenBundle(profileID: profileID) {
            request.setValue(
                "\(tokenBundle.tokenType) \(tokenBundle.accessToken)",
                forHTTPHeaderField: "Authorization"
            )
        } else if profile.originAuthMode == .rommBasicLegacy,
                  let credentials = try secretStore.readBasicCredentials(profileID: profileID) {
            let raw = "\(credentials.username):\(credentials.password)"
            let encoded = Data(raw.utf8).base64EncodedString()
            request.setValue("Basic \(encoded)", forHTTPHeaderField: "Authorization")
        }

        return request
    }

    public func receive(_ response: HTTPURLResponse, for request: URLRequest) async throws {
        try await cookies?.receive(response, request: request)
    }
    public func redirect(_ request: URLRequest, original: URLRequest) async throws -> URLRequest {
        guard let cookies else { return request }
        return try await cookies.redirect(request, original: original)
    }

    public func retryAfterUnauthorized(_ request: URLRequest, response: HTTPURLResponse) async throws -> URLRequest? {
        guard scope == .full, response.statusCode == 401,
              !(response.value(forHTTPHeaderField: "Content-Type") ?? "").lowercased().contains("text/html"),
              ["GET", "HEAD"].contains(request.httpMethod ?? "GET"), request.httpBody == nil, request.httpBodyStream == nil,
              let profile = try await profileLookup(profileID), profile.originAuthMode == .rommBearerPassword,
              let requestURL = request.url, let responseURL = response.url,
              CredentialScope(baseURL: profile.baseURL).contains(requestURL),
              CredentialScope(baseURL: profile.baseURL).contains(responseURL),
              let rejected = request.value(forHTTPHeaderField: "Authorization") else { return nil }
        try await refreshToken(rejected)
        let next = try await decorate(request)
        guard let authorization = next.value(forHTTPHeaderField: "Authorization"), authorization != rejected else { return nil }
        return next
    }

}
