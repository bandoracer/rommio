import Foundation
import RommioContract

public enum OnboardingRoute: String, Hashable, Sendable, CaseIterable {
    case welcome
    case serverAccess
    case login
    case success
}

public struct InteractiveAuthContext: Hashable, Sendable, Identifiable {
    public let id: UUID
    public let provider: InteractiveSessionProvider
    public let returnRoute: OnboardingRoute

    public init(
        id: UUID = UUID(),
        provider: InteractiveSessionProvider,
        returnRoute: OnboardingRoute
    ) {
        self.id = id
        self.provider = provider
        self.returnRoute = returnRoute
    }
}

public enum AppRoute: Hashable, Sendable {
    case gate
    case onboarding(OnboardingRoute)
    case interactive(InteractiveAuthContext)
    case app
}

public enum AuthGateDecision: String, Hashable, Sendable, CaseIterable {
    case welcome
    case serverAccess
    case login
    case app

    public static func resolve(
        profile: ServerProfile?,
        offlineState: OfflineState = OfflineState(connectivity: .online)
    ) -> AuthGateDecision {
        switch true {
        case profile == nil:
            .welcome
        case profile?.serverAccess.status != .ready:
            .serverAccess
        case profile?.status == .connected:
            .app
        case offlineState.connectivity == .offline && offlineState.catalogReady && profile?.sessionState.hasOriginSession == true:
            .app
        case profile?.status == .reauthRequiredEdge:
            .serverAccess
        default:
            .login
        }
    }

    public var route: AppRoute {
        switch self {
        case .welcome:
            .onboarding(.welcome)
        case .serverAccess:
            .onboarding(.serverAccess)
        case .login:
            .onboarding(.login)
        case .app:
            .app
        }
    }
}
