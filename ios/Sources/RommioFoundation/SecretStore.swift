import Foundation
import Security
import RommioContract

public protocol SecretStore: Sendable {
    func save(_ value: Data, for key: String) throws
    func read(key: String) throws -> Data?
    func remove(key: String) throws
}

public final class KeychainSecretStore: SecretStore, @unchecked Sendable {
    public let service: String

    public init(service: String = "io.github.mattsays.rommio") {
        self.service = service
    }

    public func save(_ value: Data, for key: String) throws {
        try remove(key: key)
        let status = SecItemAdd([
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecValueData as String: value,
        ] as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw KeychainError(status: status)
        }
    }

    public func read(key: String) throws -> Data? {
        var result: CFTypeRef?
        let status = SecItemCopyMatching([
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ] as CFDictionary, &result)
        if status == errSecItemNotFound {
            return nil
        }
        guard status == errSecSuccess else {
            throw KeychainError(status: status)
        }
        return result as? Data
    }

    public func remove(key: String) throws {
        let status = SecItemDelete([
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ] as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainError(status: status)
        }
    }
}

public final class FileSecretStore: SecretStore, @unchecked Sendable {
    private let rootDirectory: URL
    private let fileManager: FileManager

    public init(rootDirectory: URL, fileManager: FileManager = .default) throws {
        self.rootDirectory = rootDirectory
        self.fileManager = fileManager
        try fileManager.createDirectory(at: rootDirectory, withIntermediateDirectories: true)
    }

    public func save(_ value: Data, for key: String) throws {
        try fileManager.createDirectory(at: rootDirectory, withIntermediateDirectories: true)
        try value.write(to: url(for: key), options: .atomic)
    }

    public func read(key: String) throws -> Data? {
        let url = url(for: key)
        guard fileManager.fileExists(atPath: url.path) else {
            return nil
        }
        return try Data(contentsOf: url)
    }

    public func remove(key: String) throws {
        let url = url(for: key)
        guard fileManager.fileExists(atPath: url.path) else {
            return
        }
        try fileManager.removeItem(at: url)
    }

    private func url(for key: String) -> URL {
        let sanitizedKey = key.replacingOccurrences(of: "[^A-Za-z0-9._-]", with: "_", options: .regularExpression)
        return rootDirectory.appending(path: "\(sanitizedKey).secret")
    }
}

public struct KeychainError: LocalizedError, CustomStringConvertible {
    public let status: OSStatus

    public var description: String {
        errorDescription ?? "Keychain error \(status)"
    }

    public var errorDescription: String? {
        if let message = SecCopyErrorMessageString(status, nil) as String? {
            return "Keychain error \(status): \(message)"
        }
        return "Keychain error \(status)"
    }
}

public extension SecretStore {
    func saveTokenBundle(_ tokenBundle: TokenBundle, for profileID: String) throws {
        let data = try JSONEncoder().encode(tokenBundle)
        try save(data, for: key(profileID: profileID, suffix: "token_bundle"))
    }

    func readTokenBundle(profileID: String) throws -> TokenBundle? {
        guard let data = try read(key: key(profileID: profileID, suffix: "token_bundle")) else {
            return nil
        }
        return try JSONDecoder().decode(TokenBundle.self, from: data)
    }

    func clearTokenBundle(profileID: String) throws {
        try remove(key: key(profileID: profileID, suffix: "token_bundle"))
    }

    func saveDeviceID(_ deviceID: String, for profileID: String) throws {
        try save(Data(deviceID.utf8), for: key(profileID: profileID, suffix: "device_id"))
    }

    func readDeviceID(profileID: String) throws -> String? {
        guard let data = try read(key: key(profileID: profileID, suffix: "device_id")) else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    func clearDeviceID(profileID: String) throws {
        try remove(key: key(profileID: profileID, suffix: "device_id"))
    }

    func saveBasicCredentials(_ credentials: DirectLoginCredentials, for profileID: String) throws {
        let data = try JSONEncoder().encode(credentials)
        try save(data, for: key(profileID: profileID, suffix: "basic_credentials"))
    }

    func readBasicCredentials(profileID: String) throws -> DirectLoginCredentials? {
        guard let data = try read(key: key(profileID: profileID, suffix: "basic_credentials")) else {
            return nil
        }
        return try JSONDecoder().decode(DirectLoginCredentials.self, from: data)
    }

    func clearBasicCredentials(profileID: String) throws {
        try remove(key: key(profileID: profileID, suffix: "basic_credentials"))
    }

    func saveCloudflareCredentials(_ credentials: CloudflareServiceCredentials, for profileID: String) throws {
        let data = try JSONEncoder().encode(credentials)
        try save(data, for: key(profileID: profileID, suffix: "cloudflare_credentials"))
    }

    func readCloudflareCredentials(profileID: String) throws -> CloudflareServiceCredentials? {
        guard let data = try read(key: key(profileID: profileID, suffix: "cloudflare_credentials")) else {
            return nil
        }
        return try JSONDecoder().decode(CloudflareServiceCredentials.self, from: data)
    }

    func clearCloudflareCredentials(profileID: String) throws {
        try remove(key: key(profileID: profileID, suffix: "cloudflare_credentials"))
    }

    func clearOriginAuthSecrets(profileID: String) throws {
        try clearTokenBundle(profileID: profileID)
        try clearBasicCredentials(profileID: profileID)
    }

    func clearServerAccessSecrets(profileID: String) throws {
        try clearDeviceID(profileID: profileID)
        try clearCloudflareCredentials(profileID: profileID)
    }

    func clearAuthSecrets(profileID: String) throws {
        try clearOriginAuthSecrets(profileID: profileID)
        try clearServerAccessSecrets(profileID: profileID)
    }

    private func key(profileID: String, suffix: String) -> String {
        let sanitizedID = profileID.replacingOccurrences(of: "[^A-Za-z0-9._-]", with: "_", options: .regularExpression)
        let sanitizedSuffix = suffix.replacingOccurrences(of: "[^A-Za-z0-9._-]", with: "_", options: .regularExpression)
        return "auth_\(sanitizedID)_\(sanitizedSuffix)"
    }
}
