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
    private let profileID: String
    private let secretStore: SecretStore
    private let scope: RequestAuthScope
    private let profileLookup: @Sendable (String) async throws -> ServerProfile?

    public init(
        profileID: String,
        secretStore: SecretStore,
        scope: RequestAuthScope = .full,
        profileLookup: @escaping @Sendable (String) async throws -> ServerProfile?
    ) {
        self.profileID = profileID
        self.secretStore = secretStore
        self.scope = scope
        self.profileLookup = profileLookup
    }

    public func decorate(_ request: URLRequest) async throws -> URLRequest {
        var request = request
        let profile = try await profileLookup(profileID)
        request.httpShouldHandleCookies = profile.map(shouldSendCookies(for:)) ?? true

        if profile?.edgeAuthMode == .cloudflareAccessService,
           let cloudflare = try secretStore.readCloudflareCredentials(profileID: profileID) {
            request.setValue(cloudflare.clientID, forHTTPHeaderField: "CF-Access-Client-Id")
            request.setValue(cloudflare.clientSecret, forHTTPHeaderField: "CF-Access-Client-Secret")
        }

        guard scope == .full else { return request }

        if profile?.originAuthMode == .rommBearerPassword,
           let tokenBundle = try secretStore.readTokenBundle(profileID: profileID) {
            request.setValue(
                "\(tokenBundle.tokenType) \(tokenBundle.accessToken)",
                forHTTPHeaderField: "Authorization"
            )
        } else if profile?.originAuthMode == .rommBasicLegacy,
                  let credentials = try secretStore.readBasicCredentials(profileID: profileID) {
            let raw = "\(credentials.username):\(credentials.password)"
            let encoded = Data(raw.utf8).base64EncodedString()
            request.setValue("Basic \(encoded)", forHTTPHeaderField: "Authorization")
        }

        return request
    }
}
