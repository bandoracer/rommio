import Foundation
import os
import SwiftUI

/// The light a page is drawn in (Aurora): the game's light when a cover lights the
/// page (`litBy`), else the theme's own. Riso never lights a page, so its page light
/// is always the unlit one.
///
/// Separate from `RommioTheme` so a light arriving (or changing as the user pages
/// through games) only updates the views that draw in it: the page field, lit
/// marks, tinted text and glows. Every colour is derived once, when the light is
/// made; a light is one shared reference, cheap to copy and to compare.
struct PageLight: Equatable, Sendable {
    private let storage: Storage

    /// The page's lights: the game's when lit, otherwise the theme's own.
    var lights: AuroraLights { storage.lights }
    /// The cover blurred into the page field, when lit by a game.
    var artURL: URL? { storage.artURL }
    var isLit: Bool { storage.isLit }
    var isDark: Bool { storage.isDark }

    /// The page ground (washed with the light's hue when lit).
    var ground: Color { storage.ground }
    /// Fills in the light: buttons, toggles, selection.
    var tint: Color { storage.tint }
    /// The tint for text and meaningful glyphs (docs/theme-layouts.md §1.2a): a lit
    /// page's tint stepped until it reads at 4.5:1; unlit pages use the token, which
    /// already passes. This is also the system tint (bar buttons, list buttons).
    var readableTint: Color { storage.readableTint }
    var readableTintRGB: ArtRGB { storage.readableTintRGB }
    /// The three soft lights (Aurora placeholders and gradients).
    var accent1: Color { storage.accent1 }
    var accent2: Color { storage.accent2 }
    var accent3: Color { storage.accent3 }
    /// Vivid marks in the page's light (dots, rules, bars, glows).
    var mark1: Color { storage.mark1 }
    var mark2: Color { storage.mark2 }
    var mark3: Color { storage.mark3 }

    /// Identifies the light (for animation and task identity).
    var id: String { storage.id }

    static func == (lhs: PageLight, rhs: PageLight) -> Bool {
        lhs.storage === rhs.storage || lhs.storage.id == rhs.storage.id
    }

    /// The theme's own light.
    init(unlit colors: ThemeColorTokens, key: String, dark: Bool) {
        func rgb(_ x: ThemeRGBA) -> ArtRGB { ArtRGB(red: x.red, green: x.green, blue: x.blue) }
        func hsl(_ x: ThemeRGBA) -> ArtHSL { ArtColorMath.hsl(rgb(x)) }
        let lights = AuroraLights(l1: rgb(colors.accent1), l2: rgb(colors.accent2), l3: rgb(colors.accent3),
                                  k1: AuroraLights.mark(hsl(colors.tint), dark: dark), k2: AuroraLights.mark(hsl(colors.accent2), dark: dark),
                                  k3: AuroraLights.mark(hsl(colors.accent3), dark: dark), tint: rgb(colors.tint), ground: rgb(colors.ground))
        storage = Storage(lights: lights, artURL: nil, isLit: false, isDark: dark, id: "\(key).\(dark ? "dark" : "light")",
                          ground: colors.ground.color, tint: colors.tint.color, readableTintRGB: rgb(colors.tint),
                          accents: (colors.accent1.color, colors.accent2.color, colors.accent3.color))
    }

    /// A game's light.
    private init(lit lights: AuroraLights, artURL: URL?, dark: Bool) {
        let readable = ReadableTint.resolve(tint: lights.tint, ground: lights.ground, dark: dark)
        let id = [lights.l1, lights.l2, lights.l3, lights.tint, lights.ground].map(\.hex).joined() + (dark ? "d" : "l") + (artURL?.absoluteString ?? "")
        storage = Storage(lights: lights, artURL: artURL, isLit: true, isDark: dark, id: id,
                          ground: lights.ground.themeColor.color, tint: lights.tint.themeColor.color, readableTintRGB: readable,
                          accents: (lights.l1.themeColor.color, lights.l2.themeColor.color, lights.l3.themeColor.color))
    }

    /// The light of up to three games' palettes in this theme, or the theme's own
    /// light (Riso, or covers without usable colour). Derived lights are cached.
    static func lit(by palettes: [CoverPalette], artURL: URL?, theme: RommioTheme) -> PageLight {
        let usable = palettes.filter { !$0.isNeutral }
        guard theme.isGlass, !usable.isEmpty else { return theme.unlitLight }
        let key = LitKey(palettes: usable, artURL: artURL, dark: theme.isDark)
        if let cached = cache.withLock({ $0[key] }) { return cached }
        guard let lights = AuroraLights.derive(palettes: usable, dark: theme.isDark) else { return theme.unlitLight }
        let light = PageLight(lit: lights, artURL: artURL, dark: theme.isDark)
        cache.withLock { cache in
            if cache.count > 256 { cache.removeAll() }
            cache[key] = light
        }
        return light
    }

    /// A light for one game's palette (tests and previews).
    static func lit(by palette: CoverPalette, artURL: URL? = nil, theme: RommioTheme) -> PageLight {
        lit(by: [palette], artURL: artURL, theme: theme)
    }

    private struct LitKey: Hashable {
        let palettes: [CoverPalette]
        let artURL: URL?
        let dark: Bool
    }

    private static let cache = OSAllocatedUnfairLock<[LitKey: PageLight]>(initialState: [:])

    private final class Storage: Sendable {
        let lights: AuroraLights
        let artURL: URL?
        let isLit: Bool
        let isDark: Bool
        let id: String
        let ground, tint, readableTint, accent1, accent2, accent3, mark1, mark2, mark3: Color
        let readableTintRGB: ArtRGB

        init(lights: AuroraLights, artURL: URL?, isLit: Bool, isDark: Bool, id: String, ground: Color, tint: Color,
             readableTintRGB: ArtRGB, accents: (Color, Color, Color)) {
            self.lights = lights
            self.artURL = artURL
            self.isLit = isLit
            self.isDark = isDark
            self.id = id
            self.ground = ground
            self.tint = tint
            self.readableTintRGB = readableTintRGB
            readableTint = readableTintRGB.themeColor.color
            (accent1, accent2, accent3) = accents
            mark1 = lights.k1.themeColor.color
            mark2 = lights.k2.themeColor.color
            mark3 = lights.k3.themeColor.color
        }
    }
}

private struct PageLightKey: EnvironmentKey {
    static let defaultValue = RommioTheme.aurora.unlitLight
}

extension EnvironmentValues {
    /// The light the page below is drawn in (see `PageLight`).
    var pageLight: PageLight {
        get { self[PageLightKey.self] }
        set { self[PageLightKey.self] = newValue }
    }
}

extension PageLight {
    /// A game's vivid mark in this light's scheme, or the page's own mark when the
    /// cover has no usable colour (or its palette isn't known yet).
    func mark(for palette: CoverPalette?) -> Color {
        guard let palette, !palette.isNeutral else { return mark1 }
        return MarkCache.mark(palette, dark: isDark)
    }

    /// A game's vivid mark from the palettes loaded so far.
    func mark(for url: URL?, in palettes: [URL: CoverPalette]) -> Color {
        mark(for: url.flatMap { palettes[$0] })
    }
}

/// Vivid marks of single palettes (pager dots, light strips, bars), derived once.
enum MarkCache {
    private struct Key: Hashable {
        let palette: CoverPalette
        let dark: Bool
    }

    private static let cache = OSAllocatedUnfairLock<[Key: Color]>(initialState: [:])

    static func mark(_ palette: CoverPalette, dark: Bool) -> Color {
        let key = Key(palette: palette, dark: dark)
        if let cached = cache.withLock({ $0[key] }) { return cached }
        let colour = AuroraLights.derive(palette: palette, dark: dark).k1.themeColor.color
        cache.withLock { cache in
            if cache.count > 1024 { cache.removeAll() }
            cache[key] = colour
        }
        return colour
    }
}
