import Foundation
import RommioContract

#if canImport(AuthenticationServices)
import AuthenticationServices

public enum InteractiveSessionAuthenticationError: Error, LocalizedError, Sendable {
    case missingPresentationAnchor
    case cancelled
    case callbackURLMismatch(URL)
    case failedToStart

    public var errorDescription: String? {
        switch self {
        case .missingPresentationAnchor:
            return "A presentation anchor is required to launch native web authentication."
        case .cancelled:
            return "The interactive sign-in session was cancelled."
        case let .callbackURLMismatch(url):
            return "The authentication callback URL \(url.absoluteString) did not match the expected RomM server."
        case .failedToStart:
            return "The interactive sign-in session could not be started."
        }
    }
}

public protocol PresentationAnchorProviding: AnyObject {
    @MainActor func presentationAnchor() -> ASPresentationAnchor
}

public protocol InteractiveSessionAuthenticating: Sendable {
    @MainActor
    func authenticate(using config: InteractiveSessionConfig) async throws -> URL
}

@MainActor
public final class WebAuthenticationSessionAuthenticator: NSObject, InteractiveSessionAuthenticating, ASWebAuthenticationPresentationContextProviding, @unchecked Sendable {
    private weak var anchorProvider: (any PresentationAnchorProviding)?
    private let prefersEphemeralWebBrowserSession: Bool
    private var session: ASWebAuthenticationSession?

    public init(
        anchorProvider: (any PresentationAnchorProviding)?,
        prefersEphemeralWebBrowserSession: Bool = false
    ) {
        self.anchorProvider = anchorProvider
        self.prefersEphemeralWebBrowserSession = prefersEphemeralWebBrowserSession
    }

    public func authenticate(using config: InteractiveSessionConfig) async throws -> URL {
        guard anchorProvider != nil else {
            throw InteractiveSessionAuthenticationError.missingPresentationAnchor
        }

        let callbackScheme = config.expectedBaseURL.scheme

        return try await withCheckedThrowingContinuation { continuation in
            let session = ASWebAuthenticationSession(
                url: config.startURL,
                callbackURLScheme: callbackScheme
            ) { [weak self] callbackURL, error in
                defer { self?.session = nil }

                if let authError = error as? ASWebAuthenticationSessionError,
                   authError.code == .canceledLogin {
                    continuation.resume(throwing: InteractiveSessionAuthenticationError.cancelled)
                    return
                }

                if let error {
                    continuation.resume(throwing: error)
                    return
                }

                guard let callbackURL else {
                    continuation.resume(throwing: InteractiveSessionAuthenticationError.failedToStart)
                    return
                }

                guard callbackURL.host == config.expectedBaseURL.host else {
                    continuation.resume(throwing: InteractiveSessionAuthenticationError.callbackURLMismatch(callbackURL))
                    return
                }

                continuation.resume(returning: callbackURL)
            }

            session.prefersEphemeralWebBrowserSession = prefersEphemeralWebBrowserSession
            session.presentationContextProvider = self
            self.session = session

            guard session.start() else {
                self.session = nil
                continuation.resume(throwing: InteractiveSessionAuthenticationError.failedToStart)
                return
            }
        }
    }

    public func cancel() {
        session?.cancel()
        session = nil
    }

    public func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        guard let anchor = anchorProvider?.presentationAnchor() else {
            preconditionFailure("A presentation anchor must be provided before starting web authentication.")
        }
        return anchor
    }
}
#endif
