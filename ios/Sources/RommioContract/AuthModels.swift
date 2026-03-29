import Foundation

public enum EdgeAuthMode: String, Codable, Sendable, CaseIterable {
    case none = "NONE"
    case cloudflareAccessSession = "CLOUDFLARE_ACCESS_SESSION"
    case cloudflareAccessService = "CLOUDFLARE_ACCESS_SERVICE"
    case genericCookieSSO = "GENERIC_COOKIE_SSO"
}

public enum OriginAuthMode: String, Codable, Sendable, CaseIterable {
    case none = "NONE"
    case rommBearerPassword = "ROMM_BEARER_PASSWORD"
    case rommBasicLegacy = "ROMM_BASIC_LEGACY"
    case rommOIDCSession = "ROMM_OIDC_SESSION"
}

public enum AuthStatus: String, Codable, Sendable, CaseIterable {
    case connected = "CONNECTED"
    case reauthRequiredEdge = "REAUTH_REQUIRED_EDGE"
    case reauthRequiredOrigin = "REAUTH_REQUIRED_ORIGIN"
    case unsupportedPolicy = "UNSUPPORTED_POLICY"
    case invalidConfiguration = "INVALID_CONFIGURATION"
}

public enum ServerAccessStatus: String, Codable, Sendable, CaseIterable {
    case unknown = "UNKNOWN"
    case checking = "CHECKING"
    case ready = "READY"
    case failed = "FAILED"
}

public enum ServerAccessResponseKind: String, Codable, Sendable, CaseIterable {
    case json = "JSON"
    case html = "HTML"
    case networkError = "NETWORK_ERROR"
    case unauthorized = "UNAUTHORIZED"
}

public struct AuthCapabilities: Codable, Hashable, Sendable {
    public var cloudflareAccessDetected: Bool
    public var genericCookieSSODetected: Bool
    public var rommOIDCAvailable: Bool
    public var rommTokenAvailable: Bool
    public var requiresPrivateOverlay: Bool

    public init(
        cloudflareAccessDetected: Bool = false,
        genericCookieSSODetected: Bool = false,
        rommOIDCAvailable: Bool = false,
        rommTokenAvailable: Bool = true,
        requiresPrivateOverlay: Bool = false
    ) {
        self.cloudflareAccessDetected = cloudflareAccessDetected
        self.genericCookieSSODetected = genericCookieSSODetected
        self.rommOIDCAvailable = rommOIDCAvailable
        self.rommTokenAvailable = rommTokenAvailable
        self.requiresPrivateOverlay = requiresPrivateOverlay
    }
}

public struct SessionState: Codable, Hashable, Sendable {
    public var hasEdgeSession: Bool
    public var hasOriginSession: Bool
    public var lastValidatedAt: String?
    public var expiresAt: String?
    public var canRefreshInBackground: Bool

    public init(
        hasEdgeSession: Bool = false,
        hasOriginSession: Bool = false,
        lastValidatedAt: String? = nil,
        expiresAt: String? = nil,
        canRefreshInBackground: Bool = false
    ) {
        self.hasEdgeSession = hasEdgeSession
        self.hasOriginSession = hasOriginSession
        self.lastValidatedAt = lastValidatedAt
        self.expiresAt = expiresAt
        self.canRefreshInBackground = canRefreshInBackground
    }
}

public struct ServerAccessState: Codable, Hashable, Sendable {
    public var status: ServerAccessStatus
    public var verifiedAt: String?
    public var lastTestedAt: String?
    public var lastError: String?
    public var lastHTTPStatus: Int?
    public var lastResponseKind: ServerAccessResponseKind?
    public var cookieNamesSeen: [String]

    public init(
        status: ServerAccessStatus = .unknown,
        verifiedAt: String? = nil,
        lastTestedAt: String? = nil,
        lastError: String? = nil,
        lastHTTPStatus: Int? = nil,
        lastResponseKind: ServerAccessResponseKind? = nil,
        cookieNamesSeen: [String] = []
    ) {
        self.status = status
        self.verifiedAt = verifiedAt
        self.lastTestedAt = lastTestedAt
        self.lastError = lastError
        self.lastHTTPStatus = lastHTTPStatus
        self.lastResponseKind = lastResponseKind
        self.cookieNamesSeen = cookieNamesSeen
    }
}

public struct ServerProfile: Codable, Hashable, Sendable, Identifiable {
    public var id: String
    public var label: String
    public var baseURL: URL
    public var edgeAuthMode: EdgeAuthMode
    public var originAuthMode: OriginAuthMode
    public var capabilities: AuthCapabilities
    public var serverAccess: ServerAccessState
    public var sessionState: SessionState
    public var isActive: Bool
    public var status: AuthStatus
    public var lastValidationAt: String?
    public var createdAt: String
    public var updatedAt: String

    public init(
        id: String,
        label: String,
        baseURL: URL,
        edgeAuthMode: EdgeAuthMode,
        originAuthMode: OriginAuthMode,
        capabilities: AuthCapabilities,
        serverAccess: ServerAccessState,
        sessionState: SessionState,
        isActive: Bool,
        status: AuthStatus,
        lastValidationAt: String? = nil,
        createdAt: String,
        updatedAt: String
    ) {
        self.id = id
        self.label = label
        self.baseURL = baseURL
        self.edgeAuthMode = edgeAuthMode
        self.originAuthMode = originAuthMode
        self.capabilities = capabilities
        self.serverAccess = serverAccess
        self.sessionState = sessionState
        self.isActive = isActive
        self.status = status
        self.lastValidationAt = lastValidationAt
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }

    enum CodingKeys: String, CodingKey {
        case id
        case label
        case baseURL = "base_url"
        case edgeAuthMode
        case originAuthMode
        case capabilities
        case serverAccess
        case sessionState
        case isActive
        case status
        case lastValidationAt
        case createdAt
        case updatedAt
    }
}

public struct AuthDiscoveryResult: Codable, Hashable, Sendable {
    public var baseURL: URL
    public var capabilities: AuthCapabilities
    public var recommendedEdgeAuthMode: EdgeAuthMode
    public var recommendedOriginAuthMode: OriginAuthMode
    public var warnings: [String]

    public init(
        baseURL: URL,
        capabilities: AuthCapabilities,
        recommendedEdgeAuthMode: EdgeAuthMode,
        recommendedOriginAuthMode: OriginAuthMode,
        warnings: [String] = []
    ) {
        self.baseURL = baseURL
        self.capabilities = capabilities
        self.recommendedEdgeAuthMode = recommendedEdgeAuthMode
        self.recommendedOriginAuthMode = recommendedOriginAuthMode
        self.warnings = warnings
    }

    enum CodingKeys: String, CodingKey {
        case baseURL = "base_url"
        case capabilities
        case recommendedEdgeAuthMode
        case recommendedOriginAuthMode
        case warnings
    }
}

public struct CloudflareServiceCredentials: Codable, Hashable, Sendable {
    public var clientID: String
    public var clientSecret: String

    public init(clientID: String, clientSecret: String) {
        self.clientID = clientID
        self.clientSecret = clientSecret
    }

    enum CodingKeys: String, CodingKey {
        case clientID = "client_id"
        case clientSecret = "client_secret"
    }
}

public struct DirectLoginCredentials: Codable, Hashable, Sendable {
    public var username: String
    public var password: String

    public init(username: String, password: String) {
        self.username = username
        self.password = password
    }
}

public enum InteractiveSessionProvider: String, Codable, Sendable, CaseIterable {
    case edge = "EDGE"
    case origin = "ORIGIN"
}

public struct InteractiveSessionConfig: Codable, Hashable, Sendable {
    public var profileID: String
    public var title: String
    public var startURL: URL
    public var provider: InteractiveSessionProvider
    public var expectedBaseURL: URL

    public init(
        profileID: String,
        title: String,
        startURL: URL,
        provider: InteractiveSessionProvider,
        expectedBaseURL: URL
    ) {
        self.profileID = profileID
        self.title = title
        self.startURL = startURL
        self.provider = provider
        self.expectedBaseURL = expectedBaseURL
    }

    enum CodingKeys: String, CodingKey {
        case profileID = "profile_id"
        case title
        case startURL = "start_url"
        case provider
        case expectedBaseURL = "expected_base_url"
    }
}

public struct RequestDecoration: Codable, Hashable, Sendable {
    public var url: URL
    public var headers: [String: String]
    public var shouldPersistCookies: Bool

    public init(url: URL, headers: [String: String], shouldPersistCookies: Bool = true) {
        self.url = url
        self.headers = headers
        self.shouldPersistCookies = shouldPersistCookies
    }
}

public struct ServerAccessResult: Codable, Hashable, Sendable {
    public var status: ServerAccessStatus
    public var httpStatus: Int?
    public var responseKind: ServerAccessResponseKind
    public var cookieNamesSeen: [String]
    public var message: String

    public init(
        status: ServerAccessStatus,
        httpStatus: Int? = nil,
        responseKind: ServerAccessResponseKind,
        cookieNamesSeen: [String] = [],
        message: String
    ) {
        self.status = status
        self.httpStatus = httpStatus
        self.responseKind = responseKind
        self.cookieNamesSeen = cookieNamesSeen
        self.message = message
    }
}

public struct TokenBundle: Codable, Hashable, Sendable {
    public var accessToken: String
    public var refreshToken: String?
    public var tokenType: String
    public var expiresAt: String?

    public init(accessToken: String, refreshToken: String? = nil, tokenType: String = "Bearer", expiresAt: String? = nil) {
        self.accessToken = accessToken
        self.refreshToken = refreshToken
        self.tokenType = tokenType
        self.expiresAt = expiresAt
    }

    enum CodingKeys: String, CodingKey {
        case accessToken = "access_token"
        case refreshToken = "refresh_token"
        case tokenType = "token_type"
        case expiresAt = "expires_at"
    }
}

public struct AuthenticatedServerContext: Codable, Hashable, Sendable {
    public var profile: ServerProfile
    public var deviceID: String?

    public init(profile: ServerProfile, deviceID: String? = nil) {
        self.profile = profile
        self.deviceID = deviceID
    }

    enum CodingKeys: String, CodingKey {
        case profile
        case deviceID = "device_id"
    }
}

public struct ClientIdentity: Codable, Hashable, Sendable {
    public var platform: String
    public var client: String
    public var clientVersion: String
    public var hostname: String?
    public var deviceRegistrationName: String

    public init(
        platform: String,
        client: String,
        clientVersion: String,
        hostname: String? = nil,
        deviceRegistrationName: String
    ) {
        self.platform = platform
        self.client = client
        self.clientVersion = clientVersion
        self.hostname = hostname
        self.deviceRegistrationName = deviceRegistrationName
    }

    public func deviceRegistrationRequest() -> DeviceRegistrationRequest {
        DeviceRegistrationRequest(
            name: deviceRegistrationName,
            platform: platform,
            client: client,
            clientVersion: clientVersion,
            hostname: hostname
        )
    }
}

public struct DeviceRegistrationRequest: Codable, Hashable, Sendable {
    public var name: String
    public var platform: String
    public var client: String
    public var clientVersion: String
    public var hostname: String?
    public var allowExisting: Bool
    public var allowDuplicate: Bool
    public var resetSyncs: Bool

    public init(
        name: String,
        platform: String,
        client: String,
        clientVersion: String,
        hostname: String? = nil,
        allowExisting: Bool = true,
        allowDuplicate: Bool = false,
        resetSyncs: Bool = false
    ) {
        self.name = name
        self.platform = platform
        self.client = client
        self.clientVersion = clientVersion
        self.hostname = hostname
        self.allowExisting = allowExisting
        self.allowDuplicate = allowDuplicate
        self.resetSyncs = resetSyncs
    }

    enum CodingKeys: String, CodingKey {
        case name
        case platform
        case client
        case clientVersion = "client_version"
        case hostname
        case allowExisting = "allow_existing"
        case allowDuplicate = "allow_duplicate"
        case resetSyncs = "reset_syncs"
    }
}

public struct DeviceRegistrationResponse: Codable, Hashable, Sendable {
    public var deviceID: String

    public init(deviceID: String) {
        self.deviceID = deviceID
    }

    enum CodingKeys: String, CodingKey {
        case deviceID = "device_id"
    }
}
