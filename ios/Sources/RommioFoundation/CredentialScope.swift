import Foundation

struct CredentialScope: Sendable {
    let baseURL: URL

    func contains(_ url: URL) -> Bool {
        guard let scheme = baseURL.scheme?.lowercased(), ["http", "https"].contains(scheme),
              url.scheme?.lowercased() == scheme,
              url.host?.lowercased() == baseURL.host?.lowercased(),
              (url.port ?? (scheme == "https" ? 443 : 80)) == (baseURL.port ?? (scheme == "https" ? 443 : 80)),
              url.user == nil, url.password == nil else { return false }
        let encoded = URLComponents(url: url, resolvingAgainstBaseURL: false)?.percentEncodedPath.lowercased() ?? ""
        guard !encoded.contains("%2f"), !encoded.contains("%5c"), !url.path.contains("\\"),
              !url.path.split(separator: "/").contains(where: { $0 == "." || $0 == ".." }) else { return false }
        let prefix = baseURL.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let path = url.path
        return prefix.isEmpty || path == "/\(prefix)" || path.hasPrefix("/\(prefix)/")
    }

    static let credentialHeaders = ["Authorization", "Cookie", "Proxy-Authorization", "CF-Access-Client-Id", "CF-Access-Client-Secret"]

    static func removingCredentials(from request: URLRequest) -> URLRequest {
        var result = request
        credentialHeaders.forEach { result.setValue(nil, forHTTPHeaderField: $0) }
        result.httpShouldHandleCookies = false
        return result
    }
}

/// Applied per task, including when an injected session is used by a caller.
final class ScopedRedirectDelegate: NSObject, URLSessionTaskDelegate, Sendable {
    let scope: CredentialScope

    let decorator: (any RequestDecorating)?
    let original: URLRequest?
    init(baseURL: URL, decorator: (any RequestDecorating)? = nil, original: URLRequest? = nil) {
        scope = CredentialScope(baseURL: baseURL)
        self.decorator = decorator; self.original = original
    }

    func redirectedRequest(_ request: URLRequest) -> URLRequest? {
        guard let url = request.url else { return nil }
        if scope.contains(url) { return request }
        // Never forward password grants, multipart saves, or other mutation bodies.
        guard ["GET", "HEAD"].contains(request.httpMethod ?? "GET"),
              request.httpBody == nil, request.httpBodyStream == nil else { return nil }
        return CredentialScope.removingCredentials(from: request)
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping @Sendable (URLRequest?) -> Void
    ) {
        guard let decorator, let original else { completionHandler(redirectedRequest(request)); return }
        Task {
            do {
                try await decorator.receive(response, for: original)
                guard let next = redirectedRequest(request) else { completionHandler(nil); return }
                completionHandler(try await decorator.redirect(next, original: original))
            } catch { completionHandler(nil) }
        }
    }
}
