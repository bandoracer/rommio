import SwiftUI
import RommioFoundation
#if canImport(UIKit)
import UIKit
#endif

/// Resolves the theme from the appearance setting and the effective light or
/// dark scheme, and hands it to everything below (sheets included).
struct ThemeHost<Content: View>: View {
    let appearance: AppearanceStore
    @ViewBuilder let content: () -> Content

    var body: some View {
        ThemeInjector(themeID: appearance.theme, content: content)
            .preferredColorScheme(appearance.scheme.colorScheme)
            .environment(appearance)
    }
}

private struct ThemeInjector<Content: View>: View {
    let themeID: AppearanceTheme
    @ViewBuilder let content: () -> Content
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let theme = RommioTheme(themeID, dark: colorScheme == .dark)
        content()
            .environment(\.rommioTheme, theme)
            .environment(\.pageLight, theme.unlitLight)
            // The system tint colours bar buttons and list buttons (text): the readable tint.
            .tint(theme.unlitLight.readableTint)
            .onChange(of: themeID, initial: true) { _, id in
                ThemeChrome.apply(RommioTheme(id, dark: false))
            }
    }
}

extension View {
    /// Re-applies the theme below a boundary that doesn't inherit it (a new window).
    func rommioTheme(_ theme: RommioTheme) -> some View {
        environment(\.rommioTheme, theme).environment(\.pageLight, theme.unlitLight).tint(theme.unlitLight.readableTint)
    }
}

/// UIKit chrome SwiftUI has no API for: navigation bar title faces.
@MainActor
enum ThemeChrome {
    static func apply(_ theme: RommioTheme) {
        #if canImport(UIKit)
        RommioFonts.registerIfNeeded()
        let set = theme.tokens
        let ink = UIColor { $0.userInterfaceStyle == .dark ? set.dark.ink.uiColor : set.light.ink.uiColor }
        let title = set.type.title
        let headline = set.type.headline
        let large = UIFontMetrics(forTextStyle: .largeTitle).scaledFont(
            for: UIFont(name: title.postScriptName, size: title.size) ?? .systemFont(ofSize: 34, weight: .bold))
        let inline = UIFontMetrics(forTextStyle: .headline).scaledFont(
            for: UIFont(name: headline.postScriptName, size: 18) ?? .systemFont(ofSize: 17, weight: .semibold))
        var largeAttributes: [NSAttributedString.Key: Any] = [.font: large, .foregroundColor: ink]
        var inlineAttributes: [NSAttributedString.Key: Any] = [.font: inline, .foregroundColor: ink]
        if title.tracking != 0 { largeAttributes[.kern] = title.tracking * title.size }
        if headline.tracking != 0 { inlineAttributes[.kern] = headline.tracking * 18 }

        let standard = UINavigationBarAppearance()
        standard.configureWithDefaultBackground()
        standard.largeTitleTextAttributes = largeAttributes
        standard.titleTextAttributes = inlineAttributes
        let edge = UINavigationBarAppearance()
        edge.configureWithTransparentBackground()
        edge.largeTitleTextAttributes = largeAttributes
        edge.titleTextAttributes = inlineAttributes

        let proxy = UINavigationBar.appearance()
        proxy.standardAppearance = standard
        proxy.compactAppearance = standard
        proxy.scrollEdgeAppearance = edge
        proxy.compactScrollEdgeAppearance = edge

        // Bars already on screen don't pick up proxy changes.
        for window in UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).flatMap(\.windows) {
            updateBars(in: window, standard: standard, edge: edge)
        }
        #endif
    }

    #if canImport(UIKit)
    private static func updateBars(in view: UIView, standard: UINavigationBarAppearance, edge: UINavigationBarAppearance) {
        if let bar = view as? UINavigationBar {
            bar.standardAppearance = standard
            bar.compactAppearance = standard
            bar.scrollEdgeAppearance = edge
            bar.compactScrollEdgeAppearance = edge
        }
        for subview in view.subviews { updateBars(in: subview, standard: standard, edge: edge) }
    }
    #endif
}
