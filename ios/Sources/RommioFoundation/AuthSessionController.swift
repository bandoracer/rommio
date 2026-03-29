import Foundation
import RommioContract

private struct TokenResponsePayload: Decodable {
    var accessToken: String?
    var refreshToken: String?
    var tokenType: String?
    var expiresIn: Int64?
    var expires: Int64?

    enum CodingKeys: String, CodingKey {
        case accessToken = "access_token"
        case refreshToken = "refresh_token"
        case tokenType = "token_type"
        case expiresIn = "expires_in"
        case expires
    }
}

private struct JsonProbeResult<Payload: Decodable> {
    let ok: Bool
    let status: Int
    let isHTML: Bool
    let data: Payload?
    let bodyPreview: String
}

private let mobileAppScopes = """
me.read me.write roms.read platforms.read assets.read assets.write devices.read devices.write firmware.read collections.read roms.user.read roms.user.write users.read users.write
"""

public actor AuthSessionController {
    private let profileStore: ServerProfileStore
    private let secretStore: SecretStore
    private let discovery: AuthDiscoveryService
    private let session: URLSession
    private let clientIdentity: ClientIdentity
    private let cookieStorage: HTTPCookieStorage

    public init(
        profileStore: ServerProfileStore,
        secretStore: SecretStore,
        discovery: AuthDiscoveryService = AuthDiscoveryService(),
        session: URLSession = .shared,
        clientIdentity: ClientIdentity = iosClientIdentity()
    ) {
        self.profileStore = profileStore
        self.secretStore = secretStore
        self.discovery = discovery
        self.session = session
        self.clientIdentity = clientIdentity
        self.cookieStorage = session.configuration.httpCookieStorage ?? .shared
    }

    public func discoverServer(rawBaseURL: String) async throws -> AuthDiscoveryResult {
        try await discovery.discover(rawBaseURL: rawBaseURL)
    }

    public func configureProfile(
        baseURL: String,
        label: String? = nil,
        edgeAuthMode: EdgeAuthMode? = nil,
        originAuthMode: OriginAuthMode? = nil,
        discoveryResult: AuthDiscoveryResult? = nil,
        makeActive: Bool = true
    ) async throws -> ServerProfile {
        let resolvedDiscovery = if let discoveryResult {
            discoveryResult
        } else {
            try await discoverServer(rawBaseURL: baseURL)
        }
        let existing = try await profileStore.listProfiles().first { $0.baseURL == resolvedDiscovery.baseURL }
        let nextEdgeMode = edgeAuthMode ?? existing?.edgeAuthMode ?? resolvedDiscovery.recommendedEdgeAuthMode
        let nextOriginMode = originAuthMode ?? existing?.originAuthMode ?? resolvedDiscovery.recommendedOriginAuthMode
        let shouldResetServerAccess = existing?.edgeAuthMode != nextEdgeMode
        let now = isoTimestamp()

        let profile = ServerProfile(
            id: existing?.id ?? "server_\(UUID().uuidString)",
            label: normalizedLabel(label, fallback: existing?.label ?? labelFromURL(resolvedDiscovery.baseURL)),
            baseURL: resolvedDiscovery.baseURL,
            edgeAuthMode: nextEdgeMode,
            originAuthMode: nextOriginMode,
            capabilities: resolvedDiscovery.capabilities,
            serverAccess: shouldResetServerAccess ? ServerAccessState() : (existing?.serverAccess ?? ServerAccessState()),
            sessionState: existing?.sessionState ?? SessionState(),
            isActive: makeActive,
            status: shouldResetServerAccess ? .invalidConfiguration : (existing?.status ?? .invalidConfiguration),
            lastValidationAt: shouldResetServerAccess ? nil : existing?.lastValidationAt,
            createdAt: existing?.createdAt ?? now,
            updatedAt: now
        )

        try await profileStore.save(profile, makeActive: makeActive)
        return profile
    }

    public func interactiveSessionConfig(profileID: String, provider: InteractiveSessionProvider) async throws -> InteractiveSessionConfig {
        guard let profile = try await profileStore.profile(id: profileID) else {
            throw URLError(.userAuthenticationRequired)
        }

        switch provider {
        case .edge:
            return InteractiveSessionConfig(
                profileID: profileID,
                title: profile.edgeAuthMode == .cloudflareAccessSession ? "Cloudflare Access" : "Protected server login",
                startURL: profile.baseURL,
                provider: provider,
                expectedBaseURL: profile.baseURL
            )
        case .origin:
            return InteractiveSessionConfig(
                profileID: profileID,
                title: "RomM SSO",
                startURL: profile.baseURL.appending(path: "api/login/openid"),
                provider: provider,
                expectedBaseURL: profile.baseURL
            )
        }
    }

    public func beginEdgeAccess(profileID: String) async throws {
        guard var profile = try await profileStore.profile(id: profileID) else {
            throw URLError(.userAuthenticationRequired)
        }

        profile.serverAccess.status = .checking
        profile.serverAccess.lastError = nil
        profile.serverAccess.lastHTTPStatus = nil
        profile.serverAccess.lastResponseKind = nil
        profile.updatedAt = isoTimestamp()
        try await profileStore.save(profile, makeActive: profile.isActive)
    }

    public func completeEdgeAccessAttempt(profileID: String) async throws -> ServerAccessState {
        guard var profile = try await profileStore.profile(id: profileID) else {
            throw URLError(.userAuthenticationRequired)
        }

        let names = currentCookieNames(for: profile.baseURL)
        let hasRequiredCookie = hasRequiredEdgeCookie(profile: profile, names: names)
        let now = isoTimestamp()

        profile.sessionState.hasEdgeSession = hasRequiredCookie || profile.edgeAuthMode == .cloudflareAccessService
        profile.sessionState.lastValidatedAt = now
        profile.sessionState.canRefreshInBackground = profile.edgeAuthMode == .cloudflareAccessService
        profile.serverAccess.status = .checking
        profile.serverAccess.lastError = hasRequiredCookie
            ? "Protected login finished. Run the native access test to verify the app can reach RomM."
            : "Protected login finished, but the app did not see a reusable authorization cookie yet. Run the native access test to confirm."
        profile.serverAccess.lastHTTPStatus = nil
        profile.serverAccess.lastResponseKind = nil
        profile.serverAccess.lastTestedAt = now
        profile.serverAccess.cookieNamesSeen = names
        profile.updatedAt = now
        try await profileStore.save(profile, makeActive: profile.isActive)
        return profile.serverAccess
    }

    public func completeInteractiveLogin(profileID: String, provider: InteractiveSessionProvider) async throws -> AuthStatus {
        switch provider {
        case .edge:
            _ = try await completeEdgeAccessAttempt(profileID: profileID)
            return .reauthRequiredOrigin
        case .origin:
            guard var profile = try await profileStore.profile(id: profileID) else {
                throw URLError(.userAuthenticationRequired)
            }
            let now = isoTimestamp()
            profile.sessionState.hasOriginSession = !currentCookieNames(for: profile.baseURL).isEmpty
            profile.sessionState.lastValidatedAt = now
            profile.sessionState.canRefreshInBackground = profile.edgeAuthMode == .cloudflareAccessService
            profile.updatedAt = now
            try await profileStore.save(profile, makeActive: profile.isActive)
            return try await validateProfile(profileID: profileID)
        }
    }

    public func saveCloudflareCredentials(profileID: String, credentials: CloudflareServiceCredentials) async throws {
        try secretStore.saveCloudflareCredentials(credentials, for: profileID)
        guard var profile = try await profileStore.profile(id: profileID) else { return }
        profile.edgeAuthMode = .cloudflareAccessService
        profile.serverAccess.status = .checking
        profile.status = .invalidConfiguration
        profile.updatedAt = isoTimestamp()
        try await profileStore.save(profile, makeActive: profile.isActive)
    }

    public func hasCloudflareServiceCredentials(profileID: String) throws -> Bool {
        try secretStore.readCloudflareCredentials(profileID: profileID) != nil
    }

    public func testServerAccess(profileID: String) async throws -> ServerAccessResult {
        guard var profile = try await profileStore.profile(id: profileID) else {
            return ServerAccessResult(
                status: .failed,
                responseKind: .networkError,
                message: "Configure a server before testing access."
            )
        }

        var request = URLRequest(url: try RommEndpoint.heartbeat.url(baseURL: profile.baseURL))
        request.httpMethod = "GET"
        request = try await requestDecorator(profileID: profileID, scope: .edgeOnly).decorate(request)

        let result: ServerAccessResult
        do {
            let probe = try await probe(request, decode: HeartbeatDTO.self)
            let responseKind: ServerAccessResponseKind
            let message: String
            let status: ServerAccessStatus

            if probe.ok && probe.data?.system != nil {
                responseKind = .json
                status = .ready
                message = "The app can reach the RomM server from native requests."
            } else if probe.status == 401 || probe.status == 403 {
                responseKind = .unauthorized
                status = .failed
                message = "Protected server access is still incomplete."
            } else if probe.isHTML {
                responseKind = .html
                status = .failed
                message = "The app is still receiving an HTML login page instead of RomM JSON."
            } else {
                responseKind = .networkError
                status = .failed
                message = "The app could not confirm protected server access."
            }

            result = ServerAccessResult(
                status: status,
                httpStatus: probe.status == 0 ? nil : probe.status,
                responseKind: responseKind,
                cookieNamesSeen: currentCookieNames(for: profile.baseURL),
                message: message
            )
        } catch {
            result = ServerAccessResult(
                status: .failed,
                responseKind: .networkError,
                cookieNamesSeen: currentCookieNames(for: profile.baseURL),
                message: error.localizedDescription
            )
        }

        let now = isoTimestamp()
        profile.serverAccess = ServerAccessState(
            status: result.status,
            verifiedAt: result.status == .ready ? now : nil,
            lastTestedAt: now,
            lastError: result.status == .ready ? nil : result.message,
            lastHTTPStatus: result.httpStatus,
            lastResponseKind: result.responseKind,
            cookieNamesSeen: result.cookieNamesSeen
        )
        profile.sessionState.hasEdgeSession = switch profile.edgeAuthMode {
        case .none:
            false
        case .cloudflareAccessService:
            true
        case .cloudflareAccessSession, .genericCookieSSO:
            result.status == .ready
        }
        profile.sessionState.lastValidatedAt = now
        profile.updatedAt = now
        try await profileStore.save(profile, makeActive: profile.isActive)
        return result
    }

    public func loginWithDirectCredentials(profileID: String, credentials: DirectLoginCredentials) async throws {
        guard var profile = try await profileStore.profile(id: profileID) else {
            throw URLError(.userAuthenticationRequired)
        }

        let normalized = DirectLoginCredentials(
            username: credentials.username.trimmingCharacters(in: .whitespacesAndNewlines),
            password: credentials.password.trimmingCharacters(in: .whitespacesAndNewlines)
        )
        guard !normalized.username.isEmpty, !normalized.password.isEmpty else {
            throw URLError(.userAuthenticationRequired)
        }

        let tokenLoginSucceeded = profile.originAuthMode != .rommBasicLegacy
            ? try await tryTokenLogin(profile: profile, credentials: normalized)
            : false

        if !tokenLoginSucceeded {
            try secretStore.saveBasicCredentials(normalized, for: profileID)
        }

        let status = try await validateProfile(profileID: profileID)
        guard status == .connected else {
            throw URLError(.userAuthenticationRequired)
        }

        profile = try await profileStore.profile(id: profileID) ?? profile
        profile.sessionState.hasOriginSession = true
        profile.status = .connected
        profile.updatedAt = isoTimestamp()
        try await profileStore.save(profile, makeActive: true)
    }

    public func validateProfile(profileID: String? = nil) async throws -> AuthStatus {
        var profile = try await resolvedProfile(profileID)

        if profile.serverAccess.status != .ready {
            let status: AuthStatus = profile.edgeAuthMode == .none ? .invalidConfiguration : .reauthRequiredEdge
            profile.status = status
            profile.sessionState.hasOriginSession = false
            profile.sessionState.lastValidatedAt = isoTimestamp()
            profile.lastValidationAt = isoTimestamp()
            profile.updatedAt = isoTimestamp()
            try await profileStore.save(profile, makeActive: profile.isActive)
            return status
        }

        do {
            try await refreshBearerTokenIfNeeded(profileID: profile.id)

            var heartbeatRequest = URLRequest(url: try RommEndpoint.heartbeat.url(baseURL: profile.baseURL))
            heartbeatRequest.httpMethod = "GET"
            heartbeatRequest = try await requestDecorator(profileID: profile.id).decorate(heartbeatRequest)
            let heartbeatProbe = try await probe(heartbeatRequest, decode: HeartbeatDTO.self)

            if heartbeatProbe.status == 401 || heartbeatProbe.status == 403 {
                let status: AuthStatus = profile.edgeAuthMode == .none ? .reauthRequiredOrigin : .reauthRequiredEdge
                profile.status = status
                profile.sessionState.hasEdgeSession = false
                profile.sessionState.hasOriginSession = false
                profile.sessionState.lastValidatedAt = isoTimestamp()
                if profile.edgeAuthMode != .none {
                    profile.serverAccess.status = .failed
                    profile.serverAccess.verifiedAt = nil
                    profile.serverAccess.lastError = "Protected server access is no longer valid. Reauthenticate the server access step."
                }
                profile.serverAccess.lastTestedAt = isoTimestamp()
                profile.serverAccess.lastHTTPStatus = heartbeatProbe.status
                profile.serverAccess.lastResponseKind = .unauthorized
                profile.serverAccess.cookieNamesSeen = currentCookieNames(for: profile.baseURL)
                profile.lastValidationAt = isoTimestamp()
                profile.updatedAt = isoTimestamp()
                try await profileStore.save(profile, makeActive: profile.isActive)
                return status
            }

            if !heartbeatProbe.ok || heartbeatProbe.data?.system == nil {
                let status: AuthStatus = profile.edgeAuthMode == .none ? .invalidConfiguration : .reauthRequiredEdge
                profile.status = status
                profile.sessionState.hasEdgeSession = false
                profile.sessionState.hasOriginSession = false
                profile.sessionState.lastValidatedAt = isoTimestamp()
                profile.serverAccess.status = .failed
                profile.serverAccess.verifiedAt = nil
                profile.serverAccess.lastTestedAt = isoTimestamp()
                profile.serverAccess.lastError = "The app is no longer reaching RomM JSON through the configured server access layer."
                profile.serverAccess.lastHTTPStatus = heartbeatProbe.status == 0 ? nil : heartbeatProbe.status
                profile.serverAccess.lastResponseKind = heartbeatProbe.isHTML ? .html : .json
                profile.serverAccess.cookieNamesSeen = currentCookieNames(for: profile.baseURL)
                profile.lastValidationAt = isoTimestamp()
                profile.updatedAt = isoTimestamp()
                try await profileStore.save(profile, makeActive: profile.isActive)
                return status
            }

            var userRequest = URLRequest(url: try RommEndpoint.currentUser.url(baseURL: profile.baseURL))
            userRequest.httpMethod = "GET"
            userRequest = try await requestDecorator(profileID: profile.id).decorate(userRequest)
            let userProbe = try await probe(userRequest, decode: UserDTO.self)

            let nextStatus: AuthStatus
            if userProbe.status == 401 || userProbe.status == 403 {
                nextStatus = profile.originAuthMode == .none ? .invalidConfiguration : .reauthRequiredOrigin
            } else if !userProbe.ok || userProbe.data?.id == nil || (userProbe.data?.username ?? "").isEmpty {
                nextStatus = profile.originAuthMode == .none ? .invalidConfiguration : .reauthRequiredOrigin
            } else {
                nextStatus = .connected
            }

            profile.status = nextStatus
            profile.sessionState.hasEdgeSession = profile.edgeAuthMode == .none ? false : true
            profile.sessionState.hasOriginSession = nextStatus == .connected
            profile.sessionState.lastValidatedAt = isoTimestamp()
            profile.sessionState.canRefreshInBackground = profile.originAuthMode == .rommBearerPassword || profile.edgeAuthMode == .cloudflareAccessService
            profile.serverAccess.status = .ready
            profile.serverAccess.verifiedAt = profile.serverAccess.verifiedAt ?? isoTimestamp()
            profile.serverAccess.lastTestedAt = profile.serverAccess.lastTestedAt ?? isoTimestamp()
            profile.serverAccess.lastError = nextStatus == .connected ? nil : profile.serverAccess.lastError
            profile.serverAccess.lastHTTPStatus = heartbeatProbe.status
            profile.serverAccess.lastResponseKind = .json
            profile.serverAccess.cookieNamesSeen = currentCookieNames(for: profile.baseURL)
            profile.lastValidationAt = isoTimestamp()
            profile.updatedAt = isoTimestamp()
            try await profileStore.save(profile, makeActive: profile.isActive)

            if nextStatus == .connected {
                let client = await client(for: profile.id, baseURL: profile.baseURL)
                try await registerDeviceIfNeeded(profileID: profile.id, client: client)
            }
            return nextStatus
        } catch {
            let status: AuthStatus = profile.edgeAuthMode == .none ? .reauthRequiredOrigin : .reauthRequiredEdge
            profile.status = status
            profile.sessionState.hasOriginSession = false
            profile.sessionState.hasEdgeSession = profile.edgeAuthMode == .cloudflareAccessService
            profile.sessionState.lastValidatedAt = isoTimestamp()
            if profile.edgeAuthMode != .none {
                profile.serverAccess.status = .failed
                profile.serverAccess.verifiedAt = nil
            }
            profile.serverAccess.lastTestedAt = isoTimestamp()
            profile.serverAccess.lastError = error.localizedDescription
            profile.serverAccess.lastResponseKind = .networkError
            profile.serverAccess.cookieNamesSeen = currentCookieNames(for: profile.baseURL)
            profile.lastValidationAt = isoTimestamp()
            profile.updatedAt = isoTimestamp()
            try await profileStore.save(profile, makeActive: profile.isActive)
            return status
        }
    }

    public func logout(profileID: String? = nil, clearServerAccess: Bool = false) async throws {
        guard var profile = try await resolvedOptionalProfile(profileID) else { return }

        try secretStore.clearOriginAuthSecrets(profileID: profile.id)
        if clearServerAccess {
            try secretStore.clearServerAccessSecrets(profileID: profile.id)
            clearCookies()
        }

        profile.sessionState.hasOriginSession = false
        profile.sessionState.lastValidatedAt = isoTimestamp()
        profile.status = profile.serverAccess.status == .ready ? .reauthRequiredOrigin : .invalidConfiguration
        if clearServerAccess {
            profile.serverAccess = ServerAccessState()
            profile.sessionState.hasEdgeSession = false
        }
        profile.updatedAt = isoTimestamp()
        try await profileStore.save(profile, makeActive: profile.isActive)
    }

    public func signOut(profileID: String) async throws {
        try await logout(profileID: profileID, clearServerAccess: false)
    }

    public func clearServerAccess(profileID: String) async throws {
        guard var profile = try await profileStore.profile(id: profileID) else { return }
        try secretStore.clearServerAccessSecrets(profileID: profile.id)
        clearCookies()

        profile.serverAccess = ServerAccessState()
        profile.sessionState.hasEdgeSession = false
        profile.sessionState.hasOriginSession = false
        profile.sessionState.lastValidatedAt = isoTimestamp()
        profile.status = .invalidConfiguration
        profile.updatedAt = isoTimestamp()
        try await profileStore.save(profile, makeActive: profile.isActive)
    }

    public func deleteProfile(profileID: String) async throws {
        let activeProfileID = try await profileStore.activeProfile()?.id
        if activeProfileID == profileID {
            clearCookies()
        }
        try secretStore.clearAuthSecrets(profileID: profileID)
        try await profileStore.deleteProfile(id: profileID)
    }

    public func activeAuthenticatedContext() async throws -> AuthenticatedServerContext? {
        guard let profile = try await profileStore.activeProfile() else { return nil }
        let deviceID = try secretStore.readDeviceID(profileID: profile.id)
        return AuthenticatedServerContext(profile: profile, deviceID: deviceID)
    }

    public func client(for profileID: String, baseURL: URL? = nil) async -> RommAPIClient {
        let resolvedBaseURL = baseURL ?? URL(string: "http://localhost")!
        return RommAPIClient(
            baseURL: resolvedBaseURL,
            session: session,
            decorator: requestDecorator(profileID: profileID)
        )
    }

    public func activeClient() async throws -> RommAPIClient? {
        guard let profile = try await profileStore.activeProfile() else { return nil }
        return await client(for: profile.id, baseURL: profile.baseURL)
    }

    private func tryTokenLogin(profile: ServerProfile, credentials: DirectLoginCredentials) async throws -> Bool {
        var request = URLRequest(url: profile.baseURL.appending(path: "api/token"))
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = formEncodedBody([
            "grant_type": "password",
            "username": credentials.username,
            "password": credentials.password,
            "scope": mobileAppScopes,
        ])
        request = try await requestDecorator(profileID: profile.id, scope: .edgeOnly).decorate(request)

        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200 ..< 300).contains(status) else { return false }

        let body = String(decoding: data, as: UTF8.self)
        guard !looksLikeHTML(body: body, contentType: (response as? HTTPURLResponse)?.value(forHTTPHeaderField: "Content-Type")) else {
            return false
        }

        let payload = try JSONDecoder().decode(TokenResponsePayload.self, from: data)
        guard let accessToken = payload.accessToken else { return false }
        let expiresAt = (payload.expiresIn ?? payload.expires).map { seconds in
            ISO8601DateFormatter().string(from: Date(timeIntervalSinceNow: TimeInterval(seconds)))
        }
        try secretStore.saveTokenBundle(
            TokenBundle(
                accessToken: accessToken,
                refreshToken: payload.refreshToken,
                tokenType: payload.tokenType ?? "Bearer",
                expiresAt: expiresAt
            ),
            for: profile.id
        )
        try? secretStore.clearBasicCredentials(profileID: profile.id)
        return true
    }

    private func refreshBearerTokenIfNeeded(profileID: String) async throws {
        guard let tokenBundle = try secretStore.readTokenBundle(profileID: profileID),
              let refreshToken = tokenBundle.refreshToken,
              let expiresAt = tokenBundle.expiresAt,
              let expiry = ISO8601DateFormatter().date(from: expiresAt),
              expiry.timeIntervalSinceNow < 90 else {
            return
        }

        let profile = try await resolvedProfile(profileID)
        var request = URLRequest(url: profile.baseURL.appending(path: "api/token"))
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = formEncodedBody([
            "grant_type": "refresh_token",
            "refresh_token": refreshToken,
        ])
        request = try await requestDecorator(profileID: profileID, scope: .edgeOnly).decorate(request)

        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200 ..< 300).contains(status) else { return }

        let payload = try JSONDecoder().decode(TokenResponsePayload.self, from: data)
        guard let accessToken = payload.accessToken else { return }
        let nextExpiry = (payload.expiresIn ?? payload.expires).map { seconds in
            ISO8601DateFormatter().string(from: Date(timeIntervalSinceNow: TimeInterval(seconds)))
        }
        try secretStore.saveTokenBundle(
            TokenBundle(
                accessToken: accessToken,
                refreshToken: payload.refreshToken ?? refreshToken,
                tokenType: payload.tokenType ?? tokenBundle.tokenType,
                expiresAt: nextExpiry ?? tokenBundle.expiresAt
            ),
            for: profileID
        )
    }

    private func registerDeviceIfNeeded(profileID: String, client: RommAPIClient) async throws {
        if try secretStore.readDeviceID(profileID: profileID) != nil {
            return
        }
        let response = try await client.registerDevice(clientIdentity.deviceRegistrationRequest())
        try secretStore.saveDeviceID(response.deviceID, for: profileID)
    }

    private func requestDecorator(profileID: String, scope: RequestAuthScope = .full) -> AuthenticatedRequestDecorator {
        AuthenticatedRequestDecorator(
            profileID: profileID,
            secretStore: secretStore,
            scope: scope
        ) { [profileStore] requestedProfileID in
            try await profileStore.profile(id: requestedProfileID)
        }
    }

    private func resolvedProfile(_ profileID: String?) async throws -> ServerProfile {
        if let profileID, let profile = try await profileStore.profile(id: profileID) {
            return profile
        }
        if let profile = try await profileStore.activeProfile() {
            return profile
        }
        throw URLError(.userAuthenticationRequired)
    }

    private func resolvedOptionalProfile(_ profileID: String?) async throws -> ServerProfile? {
        if let profileID {
            return try await profileStore.profile(id: profileID)
        }
        return try await profileStore.activeProfile()
    }

    private func probe<Payload: Decodable>(_ request: URLRequest, decode type: Payload.Type) async throws -> JsonProbeResult<Payload> {
        let (data, response) = try await session.data(for: request)
        let httpResponse = response as? HTTPURLResponse
        let body = String(decoding: data, as: UTF8.self)
        let status = httpResponse?.statusCode ?? 0
        let isHTML = looksLikeHTML(body: body, contentType: httpResponse?.value(forHTTPHeaderField: "Content-Type"))

        guard (200 ..< 300).contains(status), !isHTML else {
            return JsonProbeResult(
                ok: false,
                status: status,
                isHTML: isHTML,
                data: nil,
                bodyPreview: String(body.prefix(240))
            )
        }

        let payload = data.isEmpty ? nil : try JSONDecoder().decode(Payload.self, from: data)
        return JsonProbeResult(
            ok: true,
            status: status,
            isHTML: false,
            data: payload,
            bodyPreview: String(body.prefix(240))
        )
    }

    private func normalizedLabel(_ label: String?, fallback: String) -> String {
        let trimmed = label?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmed.isEmpty ? fallback : trimmed
    }

    private func currentCookieNames(for baseURL: URL) -> [String] {
        cookieStorage.cookies(for: baseURL)?.map(\.name).sorted() ?? []
    }

    private func hasRequiredEdgeCookie(profile: ServerProfile, names: [String]) -> Bool {
        switch profile.edgeAuthMode {
        case .cloudflareAccessSession:
            names.contains { $0.hasPrefix("CF_Authorization") }
        case .genericCookieSSO:
            !names.isEmpty
        case .none, .cloudflareAccessService:
            false
        }
    }

    private func clearCookies() {
        for cookie in cookieStorage.cookies ?? [] {
            cookieStorage.deleteCookie(cookie)
        }
    }

    private func labelFromURL(_ baseURL: URL) -> String {
        if let host = baseURL.host, !host.isEmpty {
            return host
        }
        return baseURL.absoluteString
    }

    private func formEncodedBody(_ values: [String: String]) -> Data? {
        let query = values.map { key, value in
            let allowed = CharacterSet.urlQueryAllowed.subtracting(CharacterSet(charactersIn: "&=?+"))
            let escapedKey = key.addingPercentEncoding(withAllowedCharacters: allowed) ?? key
            let escapedValue = value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
            return "\(escapedKey)=\(escapedValue)"
        }
        .sorted()
        .joined(separator: "&")
        return Data(query.utf8)
    }

    private func looksLikeHTML(body: String, contentType: String?) -> Bool {
        let normalizedType = contentType?.lowercased() ?? ""
        let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
        return normalizedType.contains("text/html")
            || trimmed.hasPrefix("<!DOCTYPE")
            || trimmed.lowercased().hasPrefix("<html")
            || trimmed.hasPrefix("<")
    }

    private func isoTimestamp() -> String {
        ISO8601DateFormatter().string(from: Date())
    }
}

public func iosClientIdentity(bundle: Bundle = .main) -> ClientIdentity {
    let version = (bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String)
        ?? (bundle.object(forInfoDictionaryKey: "CFBundleVersion") as? String)
        ?? "0.1.0"
    let hostName = ProcessInfo.processInfo.hostName
    return ClientIdentity(
        platform: "ios",
        client: "romm-ios-native",
        clientVersion: version,
        hostname: hostName,
        deviceRegistrationName: "Rommio \(hostName)"
    )
}
