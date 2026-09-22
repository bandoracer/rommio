import Foundation
import CryptoKit

/// Auth ownership is independent of legacy file ownership and survives sign-out.
public struct VerifiedAccount: Codable, Hashable, Sendable {
    public let baseURL: URL
    public let userID: Int
    public init(baseURL: URL, userID: Int) { self.baseURL = baseURL; self.userID = userID }
}

public struct AccountBindingError: LocalizedError, Sendable {
    public init() {}
    public var errorDescription: String? {
        "This server profile belongs to a different RomM account. Sign in with its original account; existing progress has not been reassigned."
    }
}

extension SecretStore {
    func verifiedAccount(profileID: String) throws -> VerifiedAccount? {
        guard let raw = try read(key: accountKey(profileID)) else { return nil }
        return try JSONDecoder().decode(VerifiedAccount.self, from: raw)
    }

    // AuthSessionController calls this without suspension after its generation check.
    func bindVerifiedAccount(_ account: VerifiedAccount, profileID: String) throws {
        if let previous = try verifiedAccount(profileID: profileID) {
            guard previous == account else { throw AccountBindingError() }
            return
        }
        try save(JSONEncoder().encode(account), for: accountKey(profileID))
    }

    private func accountKey(_ profileID: String) -> String {
        "verified_account_" + SHA256.hash(data: Data(profileID.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}
