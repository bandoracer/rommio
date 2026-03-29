import Foundation
import RommioContract

private struct DiscoveryProbeResult {
    let status: Int
    let finalURL: URL
    let headers: [String: String]
    let body: String
}

private enum AuthDiscoveryError: LocalizedError {
    case invalidURL
    case unreachable(rawBaseURL: String, attemptedBaseURLs: [URL])

    var errorDescription: String? {
        switch self {
        case .invalidURL:
            return "Enter a valid RomM server URL. Use the full address, such as https://romm.example.com."
        case let .unreachable(rawBaseURL, attemptedBaseURLs):
            let trimmed = rawBaseURL.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.hasPrefix("http://") || trimmed.hasPrefix("https://") {
                return "Could not reach \(trimmed). Check the host, port, and server availability, then try again."
            }

            let candidates = attemptedBaseURLs.map(\.absoluteString).joined(separator: " and ")
            return "Could not reach this server over \(candidates). Enter the full URL explicitly, usually https://…, or use http:// only for local servers."
        }
    }
}

public final class AuthDiscoveryService: @unchecked Sendable {
    private let session: URLSession

    public init(session: URLSession = .shared) {
        self.session = session
    }

    public func discover(rawBaseURL: String) async throws -> AuthDiscoveryResult {
        let candidates = normalizedServerURLCandidates(rawBaseURL)
        guard !candidates.isEmpty else {
            throw AuthDiscoveryError.invalidURL
        }

        for baseURL in candidates {
            let rootProbe = await probe(url: baseURL)
            let oidcProbe = await probe(url: baseURL.appending(path: "api/login/openid"))
            let tokenProbe = await postProbe(url: baseURL.appending(path: "api/token"))

            if rootProbe != nil || oidcProbe != nil || tokenProbe != nil {
                return buildResult(
                    baseURL: baseURL,
                    rootProbe: rootProbe,
                    oidcProbe: oidcProbe,
                    tokenProbe: tokenProbe
                )
            }
        }

        throw AuthDiscoveryError.unreachable(rawBaseURL: rawBaseURL, attemptedBaseURLs: candidates)
    }

    private func buildResult(
        baseURL: URL,
        rootProbe: DiscoveryProbeResult?,
        oidcProbe: DiscoveryProbeResult?,
        tokenProbe: DiscoveryProbeResult?
    ) -> AuthDiscoveryResult {
        var capabilities = AuthCapabilities()
        var warnings: [String] = []

        if let rootProbe {
            let body = rootProbe.body.lowercased()
            let finalURL = rootProbe.finalURL.absoluteString.lowercased()
            let serverHeader = rootProbe.headers["server", default: ""].lowercased()

            if finalURL.contains("/cdn-cgi/access")
                || body.contains("cloudflare access")
                || body.contains("cdn-cgi/access")
                || body.contains("cf-access")
                || serverHeader.contains("cloudflare") {
                capabilities.cloudflareAccessDetected = true
            }

            if !capabilities.cloudflareAccessDetected,
               rootProbe.status == 401 || rootProbe.status == 403
                || containsAny(body, snippets: ["sign in", "log in", "single sign-on", "authentication required"]) {
                capabilities.genericCookieSSODetected = true
            }

            if capabilities.cloudflareAccessDetected,
               containsAny(body, snippets: ["warp", "device posture", "managed device", "private network"]) {
                capabilities.requiresPrivateOverlay = true
                warnings.append("This server appears to require WARP or private-network posture.")
            }
        } else {
            warnings.append("The server could not be reached during discovery. Manual auth mode selection may be required.")
        }

        if let oidcProbe, oidcProbe.status != 404 {
            capabilities.rommOIDCAvailable = true
        }
        if let tokenProbe {
            capabilities.rommTokenAvailable = tokenProbe.status != 404
        }

        let edgeMode: EdgeAuthMode = if capabilities.requiresPrivateOverlay {
            .none
        } else if capabilities.cloudflareAccessDetected {
            .cloudflareAccessSession
        } else if capabilities.genericCookieSSODetected {
            .genericCookieSSO
        } else {
            .none
        }

        let originMode: OriginAuthMode = if capabilities.rommTokenAvailable {
            .rommBearerPassword
        } else if capabilities.rommOIDCAvailable {
            .rommOIDCSession
        } else {
            .rommBasicLegacy
        }

        return AuthDiscoveryResult(
            baseURL: baseURL,
            capabilities: capabilities,
            recommendedEdgeAuthMode: edgeMode,
            recommendedOriginAuthMode: originMode,
            warnings: warnings
        )
    }

    private func containsAny(_ body: String, snippets: [String]) -> Bool {
        snippets.contains { body.contains($0) }
    }

    private func probe(url: URL) async -> DiscoveryProbeResult? {
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else { return nil }
            return DiscoveryProbeResult(
                status: http.statusCode,
                finalURL: response.url ?? url,
                headers: http.allHeaderFields.reduce(into: [:]) { partialResult, pair in
                    if let key = pair.key as? String, let value = pair.value as? String {
                        partialResult[key.lowercased()] = value
                    }
                },
                body: String(decoding: data, as: UTF8.self)
            )
        } catch {
            return nil
        }
    }

    private func postProbe(url: URL) async -> DiscoveryProbeResult? {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = "grant_type=password".data(using: .utf8)
        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else { return nil }
            return DiscoveryProbeResult(
                status: http.statusCode,
                finalURL: response.url ?? url,
                headers: http.allHeaderFields.reduce(into: [:]) { partialResult, pair in
                    if let key = pair.key as? String, let value = pair.value as? String {
                        partialResult[key.lowercased()] = value
                    }
                },
                body: String(decoding: data, as: UTF8.self)
            )
        } catch {
            return nil
        }
    }
}
