import CryptoKit
import Foundation
import RommioContract

struct ProfileCookieContext: Codable, Sendable {
    let profileID: String
    let baseURL: URL
    let generation: String
}

public actor ProfileCookies {
    private struct Jar: Codable { var generation: String; var cookies: [BrowserCookie] }
    private let secrets: SecretStore
    private var jars: [String: Jar] = [:]
    private static let requestKey = "io.rommio.cookie-context"
    public init(secrets: SecretStore) { self.secrets = secrets }

    private func key(_ profileID: String, _ baseURL: URL) -> String {
        let input = Data("\(profileID.utf8.count):\(profileID)\(baseURL.absoluteString)".utf8)
        return "cookies_" + SHA256.hash(data: input).map { String(format: "%02x", $0) }.joined()
    }
    private func jar(_ profileID: String, _ baseURL: URL) throws -> Jar {
        let key = key(profileID, baseURL)
        if let jar = jars[key] { return jar }
        let jar: Jar
        if let data = try secrets.read(key: key) { jar = try JSONDecoder().decode(Jar.self, from: data) }
        else { jar = Jar(generation: UUID().uuidString, cookies: []) }
        jars[key] = jar
        return jar
    }
    private func persist(_ jar: Jar, context: ProfileCookieContext) throws {
        let key = key(context.profileID, context.baseURL)
        // Session cookies last for this process; only cookies with an explicit future expiry survive restart.
        let durable = Jar(generation: jar.generation, cookies: jar.cookies.filter { ($0.expiresAt ?? .distantPast) > Date() })
        try secrets.save(JSONEncoder().encode(durable), for: key)
        jars[key] = jar
    }
    func context(profileID: String, baseURL: URL) throws -> ProfileCookieContext {
        ProfileCookieContext(profileID: profileID, baseURL: baseURL, generation: try jar(profileID, baseURL).generation)
    }
    func snapshot(_ context: ProfileCookieContext) throws -> [BrowserCookie] {
        let jar = try jar(context.profileID, context.baseURL)
        guard jar.generation == context.generation else { throw CancellationError() }
        return jar.cookies.filter { ($0.expiresAt ?? .distantFuture) > Date() }
    }
    public func clear(profileID: String, baseURL: URL) throws {
        let next = Jar(generation: UUID().uuidString, cookies: [])
        try persist(next, context: ProfileCookieContext(profileID: profileID, baseURL: baseURL, generation: next.generation))
    }
    func importBrowser(_ cookies: [BrowserCookie], context: ProfileCookieContext) throws {
        guard try jar(context.profileID, context.baseURL).generation == context.generation else { throw CancellationError() }
        try merge(cookies.filter { $0.matchesDomain(context.baseURL.host ?? "") }, context: context)
    }
    func decorate(_ request: URLRequest, profileID: String, baseURL: URL) throws -> URLRequest {
        let context = try context(profileID: profileID, baseURL: baseURL)
        return try decorate(request, context: context)
    }
    private func decorate(_ request: URLRequest, context: ProfileCookieContext) throws -> URLRequest {
        var request = request
        request.httpShouldHandleCookies = false
        request.setValue(nil, forHTTPHeaderField: "Cookie")
        if let url = request.url, CredentialScope(baseURL: context.baseURL).contains(url) {
            let selected = try snapshot(context).filter { $0.matches(url) }.sorted { $0.path.count > $1.path.count }
            if !selected.isEmpty { request.setValue(selected.map { "\($0.name)=\($0.value)" }.joined(separator: "; "), forHTTPHeaderField: "Cookie") }
        }
        let mutable = (request as NSURLRequest).mutableCopy() as! NSMutableURLRequest
        URLProtocol.setProperty(try JSONEncoder().encode(context), forKey: Self.requestKey, in: mutable)
        return mutable as URLRequest
    }
    private func requestContext(_ request: URLRequest) throws -> ProfileCookieContext? {
        guard let data = URLProtocol.property(forKey: Self.requestKey, in: request) as? Data else { return nil }
        return try JSONDecoder().decode(ProfileCookieContext.self, from: data)
    }
    func redirect(_ request: URLRequest, original: URLRequest) throws -> URLRequest {
        guard let context = try requestContext(original) else { return request }
        return try decorate(request, context: context)
    }
    func receive(_ response: HTTPURLResponse, request: URLRequest) throws {
        guard let context = try requestContext(request), let url = response.url,
              CredentialScope(baseURL: context.baseURL).contains(url),
              try jar(context.profileID, context.baseURL).generation == context.generation else { return }
        let headers = response.allHeaderFields.reduce(into: [String: String]()) { result, item in
            if let key = item.key as? String { result[key] = String(describing: item.value) }
        }
        let cookies = HTTPCookie.cookies(withResponseHeaderFields: headers, for: url).map(BrowserCookie.init)
        try merge(cookies.filter { $0.matchesDomain(url.host ?? "") }, context: context)
    }
    private func merge(_ cookies: [BrowserCookie], context: ProfileCookieContext) throws {
        var jar = try jar(context.profileID, context.baseURL)
        guard jar.generation == context.generation else { throw CancellationError() }
        jar.cookies.removeAll { ($0.expiresAt ?? .distantFuture) <= Date() }
        for cookie in cookies {
            guard cookie.foundationCookie != nil, cookie.value.utf8.count <= 8192 else { continue }
            jar.cookies.removeAll { $0.name == cookie.name && $0.domain == cookie.domain && $0.path == cookie.path }
            if (cookie.expiresAt ?? .distantFuture) > Date() { jar.cookies.append(cookie) }
        }
        guard jar.cookies.count <= 256 else { throw URLError(.dataLengthExceedsMaximum) }
        try persist(jar, context: context)
    }
}

extension BrowserCookie {
    init(_ cookie: HTTPCookie) {
        self.init(name: cookie.name, value: cookie.value, domain: cookie.domain.lowercased(), path: cookie.path,
                  secure: cookie.isSecure, httpOnly: cookie.isHTTPOnly, expiresAt: cookie.expiresDate)
    }
    var foundationCookie: HTTPCookie? {
        var properties: [HTTPCookiePropertyKey: Any] = [.name: name, .value: value, .domain: domain, .path: path]
        if secure { properties[.secure] = "TRUE" }
        if httpOnly { properties[HTTPCookiePropertyKey("HttpOnly")] = "TRUE" }
        if let expiresAt { properties[.expires] = expiresAt }
        return HTTPCookie(properties: properties)
    }
    func matchesDomain(_ host: String) -> Bool {
        let host = host.lowercased(); let domain = domain.lowercased()
        return domain.hasPrefix(".") ? (host == String(domain.dropFirst()) || host.hasSuffix(domain)) : host == domain
    }
    func matches(_ url: URL) -> Bool {
        guard matchesDomain(url.host ?? ""), !secure || url.scheme == "https", (expiresAt ?? .distantFuture) > Date() else { return false }
        return url.path == path || (url.path.hasPrefix(path) && (path.hasSuffix("/") || url.path.dropFirst(path.count).hasPrefix("/")))
    }
}
