import Foundation
import Observation

/// The look of the app. Themes are presentation only: they never change
/// navigation, copy or data.
public enum AppearanceTheme: String, CaseIterable, Sendable, Identifiable {
    case aurora
    case riso

    public var id: String { rawValue }
    public static let `default`: AppearanceTheme = .aurora

    public var title: String {
        switch self {
        case .aurora: "Aurora"
        case .riso: "Riso"
        }
    }
}

/// Light or dark, or follow the system.
public enum AppearanceScheme: String, CaseIterable, Sendable, Identifiable {
    case system
    case light
    case dark

    public var id: String { rawValue }
    public static let `default`: AppearanceScheme = .system

    public var title: String {
        switch self {
        case .system: "System"
        case .light: "Light"
        case .dark: "Dark"
        }
    }
}

/// Device-local appearance preferences (`appearance.theme`, `appearance.scheme`
/// in `UserDefaults`). Not part of backup bundles and not synced.
@MainActor
@Observable
public final class AppearanceStore {
    public static let themeKey = "appearance.theme"
    public static let schemeKey = "appearance.scheme"

    public var theme: AppearanceTheme {
        didSet { if theme != oldValue { defaults.set(theme.rawValue, forKey: Self.themeKey) } }
    }

    public var scheme: AppearanceScheme {
        didSet { if scheme != oldValue { defaults.set(scheme.rawValue, forKey: Self.schemeKey) } }
    }

    @ObservationIgnored
    private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        theme = defaults.string(forKey: Self.themeKey).flatMap(AppearanceTheme.init(rawValue:)) ?? .default
        scheme = defaults.string(forKey: Self.schemeKey).flatMap(AppearanceScheme.init(rawValue:)) ?? .default
    }
}
