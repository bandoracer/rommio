import Foundation

public enum TokenRefreshError: Error, LocalizedError, Sendable {
    case invalidCredentials
    case accessRequired
    case unavailable(status: Int)
    case invalidResponse

    public var errorDescription: String? {
        switch self {
        case .invalidCredentials: "RomM sign-in has expired. Sign in again to resume pending work."
        case .accessRequired: "Protected server access needs attention. Your RomM credentials have been kept."
        case let .unavailable(status): "Token refresh failed (HTTP \(status)). Your credentials have been kept; retry when the server is available."
        case .invalidResponse: "RomM returned an invalid token response. Your credentials have been kept."
        }
    }
}

// A proxy's bare 401/403 is not proof that RomM rejected the refresh credential.
func isConfirmedTokenRejection(status: Int, data: Data) -> Bool {
    guard [400, 401, 403].contains(status),
          let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return false }
    return json["error"] as? String == "invalid_grant"
        || (status == 401 && json["detail"] as? String == "Invalid refresh token")
        || (status == 403 && json["detail"] as? String == "User account is disabled")
}
