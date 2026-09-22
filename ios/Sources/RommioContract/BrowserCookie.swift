import Foundation

/// Cookie attributes cross the browser/native boundary without exposing a shared cookie store.
public struct BrowserCookie: Codable, Hashable, Sendable {
    public var name: String
    public var value: String
    public var domain: String
    public var path: String
    public var secure: Bool
    public var httpOnly: Bool
    public var expiresAt: Date?
    public init(name: String, value: String, domain: String, path: String, secure: Bool, httpOnly: Bool, expiresAt: Date?) {
        self.name = name; self.value = value; self.domain = domain; self.path = path
        self.secure = secure; self.httpOnly = httpOnly; self.expiresAt = expiresAt
    }
}
