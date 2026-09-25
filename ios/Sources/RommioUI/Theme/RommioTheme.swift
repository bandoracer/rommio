import SwiftUI
import RommioFoundation

/// Type roles from `design/tokens/themes.json`. Pages ask for a role, never a font.
enum TypeRole: String, CaseIterable, Sendable {
    case display, title, headline, section, body, bodyEmphasis, secondary, caption, label, numeral, statValue, button, playerLegend, playerLabel

    /// The Dynamic Type style each role scales with.
    var textStyle: Font.TextStyle {
        switch self {
        case .display, .title, .statValue: .largeTitle
        case .headline, .playerLegend: .title2
        case .section, .caption, .playerLabel: .caption
        case .body, .bodyEmphasis, .numeral, .button: .body
        case .secondary: .subheadline
        case .label: .caption2
        }
    }
}

/// The active base theme: a token set resolved for light or dark. Themes are
/// presentation only; they never change navigation, strings or selectors.
///
/// Cheap to copy and compare (two references and a flag): it changes only with the
/// appearance setting. The light a page takes from a game (Aurora) is not part of
/// it: it is the separate `PageLight` (`@Environment(\.pageLight)`), read only by
/// views that draw in the page's light. `ground`, `tint` and `accent1…3` here are
/// the theme's own (unlit) values: Riso never lights a page, and anything Aurora
/// draws in the page's light reads the page light instead.
struct RommioTheme: Equatable, Sendable {
    let tokens: ThemeTokenSet
    let isDark: Bool
    /// The colours of this theme and scheme, resolved once.
    let palette: ThemePalette

    init(tokens: ThemeTokenSet, isDark: Bool) {
        self.tokens = tokens
        self.isDark = isDark
        palette = ThemePalette.resolved(tokens, dark: isDark)
    }

    init(_ theme: AppearanceTheme, dark: Bool) {
        self.init(tokens: ThemeTokens.forKey(theme.rawValue), isDark: dark)
    }

    static let aurora = RommioTheme(.aurora, dark: false)

    static func == (lhs: RommioTheme, rhs: RommioTheme) -> Bool {
        lhs.isDark == rhs.isDark && lhs.tokens == rhs.tokens
    }

    var appearance: AppearanceTheme { AppearanceTheme(rawValue: tokens.key) ?? .aurora }
    var isRiso: Bool { tokens.key == ThemeTokens.riso.key }
    /// Changes whenever anything the base theme draws with changes.
    var identity: String { tokens.key + (isDark ? ".dark" : ".light") }

    /// The page light when no game lights the page: the theme's own ground, tint and lights.
    var unlitLight: PageLight { palette.unlit }

    var colors: ThemeColorTokens { tokens.colors(dark: isDark) }
    var type: ThemeTypeTokens { tokens.type }
    var radius: ThemeRadiusTokens { tokens.radius }
    var spacing: ThemeSpacingTokens { tokens.spacing }
    var material: ThemeMaterialTokens { tokens.material }
    var player: PlayerColorTokens { tokens.player(dark: isDark) }
    var playerShape: PlayerShapeTokens { tokens.playerShape }
    /// Aurora draws surfaces as glass over the page light; Riso prints on paper.
    var isGlass: Bool { material.surfaceIsGlass }

    // MARK: Colours (unlit)

    var ground: Color { palette.ground }
    var groundRaised: Color { palette.groundRaised }
    var surface: Color { palette.surface }
    var surfaceStrong: Color { palette.surfaceStrong }
    var ink: Color { palette.ink }
    var inkSecondary: Color { palette.inkSecondary }
    var inkTertiary: Color { palette.inkTertiary }
    var hairline: Color { palette.hairline }
    var keyline: Color { palette.keyline }
    var tint: Color { palette.tint }
    var buttonFill: Color { palette.buttonFill }
    var buttonInk: Color { palette.buttonInk }
    var ok: Color { palette.ok }
    var wait: Color { palette.wait }
    var live: Color { palette.live }
    var bad: Color { palette.bad }
    /// Error and destructive text (`bad` stays for fills, chips and glyph washes).
    var badText: Color { palette.badText }
    var accent1: Color { palette.accent1 }
    var accent2: Color { palette.accent2 }
    var accent3: Color { palette.accent3 }
    var accent4: Color { palette.accent4 }
    var onAccent: Color { palette.onAccent }
    var scrim: Color { palette.scrim }

    // MARK: Component colours derived from tokens

    /// Glass rims and keylines, and ink on vivid marks (avatar, app mark): the
    /// light set's raised ground (white), whatever the scheme.
    var glassRim: Color { palette.glassRim }
    var markInk: Color { palette.glassRim }
    /// Text on a `bad` fill (badges): ink on Riso's pink and on Aurora's pale dark
    /// red; white on Aurora's deep light red.
    var onBad: Color { isRiso || isDark ? onAccent : markInk }

    // MARK: Type

    func role(_ role: TypeRole) -> TypeRoleToken {
        switch role {
        case .display: type.display
        case .title: type.title
        case .headline: type.headline
        case .section: type.section
        case .body: type.body
        case .bodyEmphasis: type.bodyEmphasis
        case .secondary: type.secondary
        case .caption: type.caption
        case .label: type.label
        case .numeral: type.numeral
        case .statValue: type.statValue
        case .button: type.button
        case .playerLegend: type.playerLegend
        case .playerLabel: type.playerLabel
        }
    }

    /// A bundled face that scales with Dynamic Type. `size` overrides the role's size.
    func font(_ role: TypeRole, size: CGFloat? = nil) -> Font {
        RommioFonts.registerIfNeeded()
        let token = self.role(role)
        let font = Font.custom(token.postScriptName, size: size ?? token.size, relativeTo: role.textStyle)
        return token.tabular ? font.monospacedDigit() : font
    }
}

/// A theme's colours for one scheme, resolved from the tokens once and shared.
final class ThemePalette: Sendable {
    let ground, groundRaised, surface, surfaceStrong: Color
    let ink, inkSecondary, inkTertiary, hairline, keyline: Color
    let tint, buttonFill, buttonInk: Color
    let ok, wait, live, bad, badText: Color
    let accent1, accent2, accent3, accent4, onAccent, scrim: Color
    let glassRim: Color
    /// The page light when no game lights the page.
    let unlit: PageLight

    init(_ tokens: ThemeTokenSet, dark: Bool) {
        let c = tokens.colors(dark: dark)
        ground = c.ground.color
        groundRaised = c.groundRaised.color
        surface = c.surface.color
        surfaceStrong = c.surfaceStrong.color
        ink = c.ink.color
        inkSecondary = c.inkSecondary.color
        inkTertiary = c.inkTertiary.color
        hairline = c.hairline.color
        keyline = c.keyline.color
        tint = c.tint.color
        buttonFill = c.buttonFill.color
        buttonInk = c.buttonInk.color
        ok = c.ok.color
        wait = c.wait.color
        live = c.live.color
        bad = c.bad.color
        badText = c.badText.color
        accent1 = c.accent1.color
        accent2 = c.accent2.color
        accent3 = c.accent3.color
        accent4 = c.accent4.color
        onAccent = c.onAccent.color
        scrim = c.scrim.color
        glassRim = tokens.light.groundRaised.color
        unlit = PageLight(unlit: c, key: tokens.key, dark: dark)
    }

    private static let all: [String: ThemePalette] = Dictionary(uniqueKeysWithValues: ThemeTokens.all.flatMap { set in
        [false, true].map { dark in ("\(set.key).\(dark)", ThemePalette(set, dark: dark)) }
    })

    static func resolved(_ tokens: ThemeTokenSet, dark: Bool) -> ThemePalette {
        all["\(tokens.key).\(dark)"] ?? ThemePalette(tokens, dark: dark)
    }
}

private struct RommioThemeKey: EnvironmentKey {
    static let defaultValue = RommioTheme.aurora
}

extension EnvironmentValues {
    var rommioTheme: RommioTheme {
        get { self[RommioThemeKey.self] }
        set { self[RommioThemeKey.self] = newValue }
    }
}

/// Applies a type role: face, size (Dynamic Type), tracking, case and figures.
private struct ThemeTextModifier: ViewModifier {
    @Environment(\.rommioTheme) private var theme
    let role: TypeRole
    let size: CGFloat?

    func body(content: Content) -> some View {
        let token = theme.role(role)
        let points = size ?? token.size
        content
            .font(theme.font(role, size: size))
            .tracking(token.tracking * points)
            .textCase(token.uppercase ? .uppercase : nil)
            .lineSpacing(max(0, (token.lineHeight - 1.2) * points))
    }
}

extension View {
    /// Styles text with a theme type role.
    func themeText(_ role: TypeRole, size: CGFloat? = nil) -> some View {
        modifier(ThemeTextModifier(role: role, size: size))
    }
}

extension AppearanceScheme {
    var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}
