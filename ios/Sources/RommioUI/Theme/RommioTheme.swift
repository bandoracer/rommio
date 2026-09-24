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

/// The active theme: a token set resolved for light or dark. Themes are
/// presentation only; they never change navigation, strings or selectors.
struct RommioTheme: Sendable {
    let tokens: ThemeTokenSet
    let isDark: Bool
    /// Aurora: the light of the game a page is about (its cover's palette). It
    /// replaces the page ground, the lights and the tint below the page. Riso
    /// ignores it.
    private(set) var light: PageLight?

    init(tokens: ThemeTokenSet, isDark: Bool) {
        self.tokens = tokens
        self.isDark = isDark
    }

    init(_ theme: AppearanceTheme, dark: Bool) {
        self.init(tokens: ThemeTokens.forKey(theme.rawValue), isDark: dark)
    }

    static let aurora = RommioTheme(.aurora, dark: false)

    var appearance: AppearanceTheme { AppearanceTheme(rawValue: tokens.key) ?? .aurora }
    var isRiso: Bool { tokens.key == ThemeTokens.riso.key }
    /// Changes whenever anything a view draws with changes.
    var identity: String { tokens.key + (isDark ? ".dark" : ".light") + (light.map { "." + $0.lights.tint.hex } ?? "") }

    /// This theme lit by a game (Aurora only; Riso prints on plain paper).
    func lit(by light: PageLight?) -> RommioTheme {
        var copy = self
        copy.light = isGlass ? light : nil
        return copy
    }

    /// The page's lights: the game's when lit, otherwise the theme's own.
    var lights: AuroraLights {
        if let light { return light.lights }
        let c = colors
        func hsl(_ x: ThemeRGBA) -> ArtHSL { ArtColorMath.hsl(ArtRGB(red: x.red, green: x.green, blue: x.blue)) }
        func rgb(_ x: ThemeRGBA) -> ArtRGB { ArtRGB(red: x.red, green: x.green, blue: x.blue) }
        return AuroraLights(l1: rgb(c.accent1), l2: rgb(c.accent2), l3: rgb(c.accent3),
                            k1: AuroraLights.mark(hsl(c.tint), dark: isDark), k2: AuroraLights.mark(hsl(c.accent2), dark: isDark),
                            k3: AuroraLights.mark(hsl(c.accent3), dark: isDark), tint: rgb(c.tint), ground: rgb(c.ground))
    }

    /// Vivid marks in the page's light (dots, rules, bars, glows).
    var mark1: Color { lights.k1.themeColor.color }
    var mark2: Color { lights.k2.themeColor.color }
    var mark3: Color { lights.k3.themeColor.color }

    var colors: ThemeColorTokens { tokens.colors(dark: isDark) }
    var type: ThemeTypeTokens { tokens.type }
    var radius: ThemeRadiusTokens { tokens.radius }
    var spacing: ThemeSpacingTokens { tokens.spacing }
    var material: ThemeMaterialTokens { tokens.material }
    var player: PlayerColorTokens { tokens.player(dark: isDark) }
    var playerShape: PlayerShapeTokens { tokens.playerShape }
    /// Aurora draws surfaces as glass over the page light; Riso prints on paper.
    var isGlass: Bool { material.surfaceIsGlass }

    // MARK: Colours

    var ground: Color { light?.lights.ground.themeColor.color ?? colors.ground.color }
    var groundRaised: Color { colors.groundRaised.color }
    var surface: Color { colors.surface.color }
    var surfaceStrong: Color { colors.surfaceStrong.color }
    var ink: Color { colors.ink.color }
    var inkSecondary: Color { colors.inkSecondary.color }
    var inkTertiary: Color { colors.inkTertiary.color }
    var hairline: Color { colors.hairline.color }
    var keyline: Color { colors.keyline.color }
    var tint: Color { light?.lights.tint.themeColor.color ?? colors.tint.color }
    var buttonFill: Color { colors.buttonFill.color }
    var buttonInk: Color { colors.buttonInk.color }
    var ok: Color { colors.ok.color }
    var wait: Color { colors.wait.color }
    var live: Color { colors.live.color }
    var bad: Color { colors.bad.color }
    /// Error and destructive text (`bad` stays for fills, chips and glyph washes).
    var badText: Color { colors.badText.color }
    /// The tint for text and meaningful glyphs (docs/theme-layouts.md §1.2a): a lit
    /// page's tint stepped until it reads at 4.5:1; unlit pages use the token, which
    /// already passes. This is also the system tint (bar buttons, list buttons).
    var readableTint: Color { readableTintRGB.themeColor.color }
    var readableTintRGB: ArtRGB {
        let token = colors.tint
        guard let light else { return ArtRGB(red: token.red, green: token.green, blue: token.blue) }
        return ReadableTint.resolve(tint: light.lights.tint, ground: light.lights.ground, dark: isDark)
    }
    var accent1: Color { light?.lights.l1.themeColor.color ?? colors.accent1.color }
    var accent2: Color { light?.lights.l2.themeColor.color ?? colors.accent2.color }
    var accent3: Color { light?.lights.l3.themeColor.color ?? colors.accent3.color }
    var accent4: Color { colors.accent4.color }
    var onAccent: Color { colors.onAccent.color }
    var scrim: Color { colors.scrim.color }

    // MARK: Component colours derived from tokens

    /// Glass rims and keylines, and ink on vivid marks (avatar, app mark): the
    /// light set's raised ground (white), whatever the scheme.
    var glassRim: Color { tokens.light.groundRaised.color }
    var markInk: Color { tokens.light.groundRaised.color }
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
