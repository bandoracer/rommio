package io.github.mattsays.rommnative.model

enum class EdgeAuthMode {
    NONE,
    CLOUDFLARE_ACCESS_SESSION,
    CLOUDFLARE_ACCESS_SERVICE,
    GENERIC_COOKIE_SSO,
}

enum class OriginAuthMode {
    NONE,
    ROMM_BEARER_PASSWORD,
    ROMM_BASIC_LEGACY,
    ROMM_OIDC_SESSION,
}

enum class AuthStatus {
    CONNECTED,
    REAUTH_REQUIRED_EDGE,
    REAUTH_REQUIRED_ORIGIN,
    UNSUPPORTED_POLICY,
    INVALID_CONFIGURATION,
}

enum class ServerAccessStatus {
    UNKNOWN,
    CHECKING,
    READY,
    FAILED,
}

enum class ServerAccessResponseKind {
    JSON,
    HTML,
    NETWORK_ERROR,
    UNAUTHORIZED,
}

data class AuthCapabilities(
    val cloudflareAccessDetected: Boolean = false,
    val genericCookieSsoDetected: Boolean = false,
    val rommOidcAvailable: Boolean = false,
    val rommTokenAvailable: Boolean = true,
    val requiresPrivateOverlay: Boolean = false,
)

data class SessionState(
    val hasEdgeSession: Boolean = false,
    val hasOriginSession: Boolean = false,
    val lastValidatedAt: String? = null,
    val expiresAt: String? = null,
    val canRefreshInBackground: Boolean = false,
)

data class ServerAccessState(
    val status: ServerAccessStatus = ServerAccessStatus.UNKNOWN,
    val verifiedAt: String? = null,
    val lastTestedAt: String? = null,
    val lastError: String? = null,
    val lastHttpStatus: Int? = null,
    val lastResponseKind: ServerAccessResponseKind? = null,
    val cookieNamesSeen: List<String> = emptyList(),
)

data class ServerProfile(
    val id: String,
    val label: String,
    val baseUrl: String,
    val edgeAuthMode: EdgeAuthMode,
    val originAuthMode: OriginAuthMode,
    val capabilities: AuthCapabilities,
    val serverAccess: ServerAccessState,
    val sessionState: SessionState,
    val isActive: Boolean,
    val status: AuthStatus,
    val lastValidationAt: String?,
    val createdAt: String,
    val updatedAt: String,
)

data class AuthDiscoveryResult(
    val baseUrl: String,
    val capabilities: AuthCapabilities,
    val recommendedEdgeAuthMode: EdgeAuthMode,
    val recommendedOriginAuthMode: OriginAuthMode,
    val warnings: List<String>,
)

data class CloudflareServiceCredentials(
    val clientId: String,
    val clientSecret: String,
)

data class DirectLoginCredentials(
    val username: String,
    val password: String,
)

data class InteractiveSessionConfig(
    val profileId: String,
    val title: String,
    val startUrl: String,
    val provider: InteractiveSessionProvider,
    val expectedBaseUrl: String,
)

enum class InteractiveSessionProvider {
    EDGE,
    ORIGIN,
}

data class RequestDecoration(
    val url: String,
    val headers: Map<String, String>,
    val shouldPersistCookies: Boolean = true,
)

data class ServerAccessResult(
    val status: ServerAccessStatus,
    val httpStatus: Int? = null,
    val responseKind: ServerAccessResponseKind,
    val cookieNamesSeen: List<String>,
    val message: String,
)

data class TokenBundle(
    val accessToken: String,
    val refreshToken: String? = null,
    val tokenType: String = "Bearer",
    val expiresAt: String? = null,
)

data class AuthenticatedServerContext(
    val profile: ServerProfile,
    val deviceId: String? = null,
)
