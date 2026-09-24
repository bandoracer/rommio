import Foundation

/// An sRGB colour with 0...1 components, rounded to 8 bits like the studio's hex colours.
struct ArtRGB: Hashable, Sendable, Codable {
    let red: Double
    let green: Double
    let blue: Double

    init(red: Double, green: Double, blue: Double) {
        self.red = red
        self.green = green
        self.blue = blue
    }

    init(red8: Int, green8: Int, blue8: Int) {
        self.init(red: Double(red8) / 255, green: Double(green8) / 255, blue: Double(blue8) / 255)
    }

    /// "#rrggbb" (or "rrggbb").
    init?(hex: String) {
        let digits = hex.hasPrefix("#") ? String(hex.dropFirst()) : hex
        guard digits.count == 6, let value = Int(digits, radix: 16) else { return nil }
        self.init(red8: (value >> 16) & 0xFF, green8: (value >> 8) & 0xFF, blue8: value & 0xFF)
    }

    var hex: String {
        String(format: "#%02x%02x%02x", Self.byte(red), Self.byte(green), Self.byte(blue))
    }

    var themeColor: ThemeRGBA { ThemeRGBA(red: red, green: green, blue: blue, alpha: 1) }

    static func byte(_ value: Double) -> Int { Int((min(max(value, 0), 1) * 255).rounded()) }
}

/// Hue in degrees, saturation and lightness in 0...1.
struct ArtHSL: Hashable, Sendable {
    let hue: Double
    let saturation: Double
    let lightness: Double
}

/// The studio's colour maths (`design/studio/versions/aurora/aurora.js`: toHsl,
/// toHex, chroma, yellowish), reproduced exactly so the app lights a page the way
/// the mockups do.
enum ArtColorMath {
    static func hsl(_ colour: ArtRGB) -> ArtHSL {
        let r = colour.red, g = colour.green, b = colour.blue
        let maximum = max(r, g, b)
        let minimum = min(r, g, b)
        let lightness = (maximum + minimum) / 2
        var hue = 0.0
        var saturation = 0.0
        if maximum != minimum {
            let delta = maximum - minimum
            saturation = lightness > 0.5 ? delta / (2 - maximum - minimum) : delta / (maximum + minimum)
            if maximum == r {
                hue = (g - b) / delta + (g < b ? 6 : 0)
            } else if maximum == g {
                hue = (b - r) / delta + 2
            } else {
                hue = (r - g) / delta + 4
            }
            hue *= 60
        }
        return ArtHSL(hue: hue, saturation: saturation, lightness: lightness)
    }

    /// HSL to an 8-bit sRGB colour (the studio's `toHex`).
    static func rgb(hue: Double, saturation: Double, lightness: Double) -> ArtRGB {
        let a = saturation * min(lightness, 1 - lightness)
        func channel(_ n: Double) -> Int {
            let q = (n + hue / 30).truncatingRemainder(dividingBy: 12)
            let c = lightness - a * max(-1, min(q - 3, 9 - q, 1))
            return Int((c * 255).rounded())
        }
        return ArtRGB(red8: channel(0), green8: channel(8), blue8: channel(4))
    }

    static func chroma(_ x: ArtHSL) -> Double { x.saturation * (1 - abs(2 * x.lightness - 1)) }

    static func isYellowish(_ hue: Double) -> Bool { hue > 40 && hue < 76 }

    static func clamp(_ value: Double, _ lower: Double, _ upper: Double) -> Double { min(upper, max(lower, value)) }
}

/// Readable tint (docs/theme-layouts.md §1.2a): wherever the tint colours text or a
/// meaningful glyph, it is stepped in HSL lightness (studio `toHsl`, 8-bit colours)
/// until it reads at 4.5:1 against the ground and against a 13% wash of itself over
/// the ground (the pill background). Fills, marks and glows keep the studio tint.
enum ReadableTint {
    /// WCAG 2 contrast target for text.
    static let minimumContrast = 4.5
    /// The pill wash a tinted label may sit on.
    static let washOpacity = 0.13
    /// Lightness step and the most steps taken.
    static let step = 0.005
    static let maxSteps = 400

    static func resolve(tint: ArtRGB, ground: ArtRGB, dark: Bool) -> ArtRGB {
        let hsl = ArtColorMath.hsl(tint)
        var lightness = hsl.lightness
        var colour = tint
        for _ in 0 ..< maxSteps {
            colour = ArtColorMath.rgb(hue: hsl.hue, saturation: hsl.saturation, lightness: ArtColorMath.clamp(lightness, 0, 1))
            if contrast(colour, ground) >= minimumContrast && contrast(colour, mix(colour, over: ground, alpha: washOpacity)) >= minimumContrast {
                return colour
            }
            lightness += dark ? step : -step
        }
        return ArtColorMath.rgb(hue: hsl.hue, saturation: hsl.saturation, lightness: ArtColorMath.clamp(lightness, 0, 1))
    }

    /// WCAG 2 relative luminance of an sRGB colour.
    static func luminance(_ colour: ArtRGB) -> Double {
        func linear(_ c: Double) -> Double { c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4) }
        return 0.2126 * linear(colour.red) + 0.7152 * linear(colour.green) + 0.0722 * linear(colour.blue)
    }

    /// WCAG 2 contrast ratio (1...21).
    static func contrast(_ a: ArtRGB, _ b: ArtRGB) -> Double {
        let la = luminance(a), lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /// `colour` at `alpha` over `ground` (not quantised, as a renderer blends it).
    static func mix(_ colour: ArtRGB, over ground: ArtRGB, alpha: Double) -> ArtRGB {
        ArtRGB(red: colour.red * alpha + ground.red * (1 - alpha),
               green: colour.green * alpha + ground.green * (1 - alpha),
               blue: colour.blue * alpha + ground.blue * (1 - alpha))
    }
}

/// A cover's five-colour palette in the studio's order: four base colours, then
/// the accent (the most characterful colour).
struct CoverPalette: Hashable, Sendable, Codable {
    let colors: [ArtRGB]

    init(colors: [ArtRGB]) {
        precondition(!colors.isEmpty, "A palette needs at least one colour")
        var padded = colors
        while padded.count < 5 { padded.append(padded[padded.count - 1]) }
        self.colors = Array(padded.prefix(5))
    }

    init?(hex: [String]) {
        let parsed = hex.compactMap(ArtRGB.init(hex:))
        guard parsed.count == hex.count, !parsed.isEmpty else { return nil }
        self.init(colors: parsed)
    }

    var accent: ArtRGB { colors[4] }

    /// A near-greyscale cover gives the studio maths nothing to light with (its
    /// tint would come out red from hue 0); pages fall back to the theme's light.
    var isNeutral: Bool {
        colors.map(ArtColorMath.hsl).allSatisfy { ArtColorMath.chroma($0) < AuroraLights.neutralChroma }
    }
}

/// Aurora ambient light: a page's three lights, the vivid marks drawn from the
/// same sources, the tint for native chrome, and the page ground. Derived from
/// cover palettes exactly as the studio's `lights()` does, with the
/// `AuroraArtTokens` constants.
struct AuroraLights: Hashable, Sendable {
    /// Soft page lights (radial fields behind the page).
    let l1: ArtRGB
    let l2: ArtRGB
    let l3: ArtRGB
    /// Vivid mid-tones for small marks (dots, rules, bars, glows).
    let k1: ArtRGB
    let k2: ArtRGB
    let k3: ArtRGB
    /// Accent for buttons, toggles, selection.
    let tint: ArtRGB
    /// The page ground, washed with the light's hue.
    let ground: ArtRGB

    /// Below this chroma everywhere, a palette counts as greyscale (not a token:
    /// an app-side guard, see `CoverPalette.isNeutral`).
    static let neutralChroma = 0.06
    /// Grey sources below this saturation keep their saturation (studio constant).
    static let greySaturation = 0.1
    /// Saturation below which a colour barely counts as a key colour (studio constant).
    static let keyGreySaturation = 0.12
    /// Accent weight when choosing a key colour, and the weight of yellow for a tint (studio constants).
    static let keyAccentWeight = 1.15
    static let keyYellowTintWeight = 0.3
    static let keyGreyWeight = 0.1
    /// Vivid marks by day: saturation and lightness bounds (studio constants; by night marks equal the lights).
    static let markLightMinSaturation = 0.55
    static let markLightMaxSaturation = 0.95
    static let markLightMinLightness = 0.46
    static let markLightMaxLightness = 0.54

    /// Lights for one game (the studio's `lights([id])`).
    static func derive(palette: CoverPalette, dark: Bool) -> AuroraLights {
        derive(sources: sources(palette), key: keyColour(palette, forTint: true), dark: dark)
    }

    /// Lights for several games (a day of play, a collection): each game's key
    /// colour becomes one light, and the first game gives the tint.
    static func derive(palettes: [CoverPalette], dark: Bool) -> AuroraLights? {
        guard let first = palettes.first else { return nil }
        if palettes.count == 1 { return derive(palette: first, dark: dark) }
        var lights = palettes.prefix(3).map { keyColour($0, forTint: true) }
        while lights.count < 3 { lights.append(lights[lights.count - 1]) }
        return derive(sources: lights, key: keyColour(first, forTint: true), dark: dark)
    }

    /// The most characterful colour: chroma, with the accent favoured, yellow
    /// avoided for a tint and greys suppressed. Ties keep palette order.
    static func keyColour(_ palette: CoverPalette, forTint: Bool) -> ArtHSL {
        let scored = palette.colors.map(ArtColorMath.hsl).enumerated().map { index, x -> (ArtHSL, Double, Int) in
            var value = ArtColorMath.chroma(x)
            if index == 4 { value *= keyAccentWeight }
            if forTint && ArtColorMath.isYellowish(x.hue) { value *= keyYellowTintWeight }
            if x.saturation < keyGreySaturation { value *= keyGreyWeight }
            return (x, value, index)
        }
        return scored.sorted { $0.1 != $1.1 ? $0.1 > $1.1 : $0.2 < $1.2 }[0].0
    }

    /// The three light sources of one palette: the accent and the two most
    /// chromatic base colours; a yellow accent becomes the third light.
    static func sources(_ palette: CoverPalette) -> [ArtHSL] {
        let p = palette.colors.map(ArtColorMath.hsl)
        let accent = p[4]
        let rest = p.prefix(4).enumerated()
            .sorted { lhs, rhs in
                let a = ArtColorMath.chroma(lhs.element), b = ArtColorMath.chroma(rhs.element)
                return a != b ? a > b : lhs.offset < rhs.offset
            }
            .map(\.element)
        if ArtColorMath.isYellowish(accent.hue) && ArtColorMath.chroma(rest[0]) > keyGreySaturation {
            return [rest[0], rest[1], accent]
        }
        return [accent, rest[0], rest[1]]
    }

    /// A soft page light: pastel by day, glowing mid-tone by night.
    static func glow(_ x: ArtHSL, dark: Bool) -> ArtRGB {
        typealias T = AuroraArtTokens
        if dark {
            let s = x.saturation < greySaturation ? x.saturation : max(x.saturation, Double(T.glowDarkMinSaturation))
            let l = ArtColorMath.clamp(x.lightness, Double(T.glowDarkMinLightness), Double(T.glowDarkMaxLightness))
            return ArtColorMath.rgb(hue: x.hue, saturation: s, lightness: l)
        }
        let s = x.saturation < greySaturation
            ? x.saturation * Double(T.glowLightGreySaturationScale)
            : ArtColorMath.clamp(x.saturation, Double(T.glowLightMinSaturation), Double(T.glowLightMaxSaturation))
        let l = ArtColorMath.clamp(Double(T.glowLightLightnessBase) + (x.lightness - 0.5) * Double(T.glowLightLightnessSpread),
                                   Double(T.glowLightMinLightness), Double(T.glowLightMaxLightness))
        return ArtColorMath.rgb(hue: x.hue, saturation: s, lightness: l)
    }

    /// A vivid mark that reads on either ground.
    static func mark(_ x: ArtHSL, dark: Bool) -> ArtRGB {
        if dark { return glow(x, dark: true) }
        let s = x.saturation < greySaturation ? x.saturation : ArtColorMath.clamp(x.saturation, markLightMinSaturation, markLightMaxSaturation)
        return ArtColorMath.rgb(hue: x.hue, saturation: s, lightness: ArtColorMath.clamp(x.lightness, markLightMinLightness, markLightMaxLightness))
    }

    private static func derive(sources: [ArtHSL], key: ArtHSL, dark: Bool) -> AuroraLights {
        typealias T = AuroraArtTokens
        let tint = dark
            ? ArtColorMath.rgb(hue: key.hue, saturation: max(key.saturation, Double(T.tintDarkMinSaturation)), lightness: Double(T.tintDarkLightness))
            : ArtColorMath.rgb(hue: key.hue, saturation: max(key.saturation, Double(T.tintLightMinSaturation)), lightness: Double(T.tintLightLightness))
        let ground = dark
            ? ArtColorMath.rgb(hue: sources[1].hue, saturation: Double(T.pageGroundDarkSaturation), lightness: Double(T.pageGroundDarkLightness))
            : ArtColorMath.rgb(hue: sources[0].hue, saturation: Double(T.pageGroundLightSaturation), lightness: Double(T.pageGroundLightLightness))
        return AuroraLights(
            l1: glow(sources[0], dark: dark), l2: glow(sources[1], dark: dark), l3: glow(sources[2], dark: dark),
            k1: mark(sources[0], dark: dark), k2: mark(sources[1], dark: dark), k3: mark(sources[2], dark: dark),
            tint: tint, ground: ground
        )
    }
}
