import SwiftUI
import RommioFoundation

struct PlayerControlStyle {
    let fillColor: Color
    let pressedFillColor: Color
    let borderColor: Color
    let pressedBorderColor: Color
    let contentColor: Color
    /// The misregistered plate printed behind the control (Riso), or nil.
    var plateColor: Color? = nil
    var borderWidth: CGFloat = 1
    /// Drawn as frosted glass over the game's light (Aurora).
    var frosted = false
}

/// A face used for legends or labels in the player.
struct PlayerFontSpec: Equatable, Sendable {
    let postScriptName: String
    /// Points at the reference control size (`referenceDiameter`).
    let size: CGFloat
    let uppercase: Bool
    /// Em.
    let tracking: CGFloat

    init(_ token: TypeRoleToken) {
        postScriptName = token.postScriptName
        size = token.size
        uppercase = token.uppercase
        tracking = token.tracking
    }
}

/// The glyphs a player style draws for directions and the menu.
enum PlayerGlyphSet: Equatable, Sendable {
    /// Aurora: light chevrons and a three-line menu glyph.
    case chevrons
    /// Riso: solid ink triangles on the arms.
    case triangles

    func direction(_ label: String) -> String {
        switch self {
        case .chevrons:
            ["Up": "chevron.up", "Down": "chevron.down", "Left": "chevron.left", "Right": "chevron.right"][label] ?? "circle"
        case .triangles:
            ["Up": "arrowtriangle.up.fill", "Down": "arrowtriangle.down.fill", "Left": "arrowtriangle.left.fill", "Right": "arrowtriangle.right.fill"][label] ?? "circle.fill"
        }
    }

    var menu: String { "line.3.horizontal" }
}

struct PlayerVisualTheme {
    let canvasColor: Color
    let panelColor: Color
    let panelAltColor: Color
    let textColor: Color
    let accentColor: Color
    let neutralControlStyle: PlayerControlStyle
    let faceControlStyles: [String: PlayerControlStyle]
    /// Shapes and print behaviour from the theme's player tokens.
    var shape: PlayerShapeTokens = ThemeTokens.aurora.playerShape
    /// The resolved player colours (after OLED black).
    var palette: PlayerColorTokens = ThemeTokens.aurora.playerDark
    var legendFont = PlayerFontSpec(ThemeTokens.aurora.type.playerLegend)
    var labelFont = PlayerFontSpec(ThemeTokens.aurora.type.playerLabel)
    var glyphs: PlayerGlyphSet = .chevrons

    func faceStyle(label: String) -> PlayerControlStyle {
        faceControlStyles[label.uppercased()] ?? baseFaceStyle
    }

    /// Face buttons without a console colour.
    var baseFaceStyle: PlayerControlStyle {
        shape.frosted ? neutralControlStyle : PlayerControlStyle(
            fillColor: palette.faceFill.color,
            pressedFillColor: palette.faceFill.color.mix(with: .white, by: 0.35),
            borderColor: .clear,
            pressedBorderColor: .clear,
            contentColor: palette.faceInk.color,
            plateColor: palette.offsetPlate.color,
            borderWidth: 0,
            frosted: false
        )
    }
}

/// The player's colours, shapes and legend faces from the active theme's player
/// tokens. OLED black forces a pure black canvas with the dark player palette;
/// console colours give face buttons their console's colours in any theme
/// (Aurora washes glass with them; Riso prints each in its nearest ink).
func resolvePlayerVisualTheme(controls: PlayerControlsState?, familyOverride: String? = nil,
                              appTheme: RommioTheme = RommioTheme(.aurora, dark: true)) -> PlayerVisualTheme {
    let oledBlackMode = controls?.preferences.oledBlackModeEnabled == true
    let consoleColorsEnabled = controls?.preferences.consoleColorsEnabled ?? true
    let familyID = familyOverride ?? controls?.platformProfile.familyID
    let dark = oledBlackMode || appTheme.isDark
    let palette = appTheme.tokens.player(dark: dark)
    let colors = appTheme.tokens.colors(dark: dark)
    let shape = appTheme.tokens.playerShape
    let canvasColor = oledBlackMode ? Color.black : palette.ground.color
    let panelColor = oledBlackMode ? Color.black : colors.groundRaised.color
    let panelAltColor = oledBlackMode ? Color(red: 0.03, green: 0.03, blue: 0.03) : palette.menuFill.color
    let accentColor = consoleColorsEnabled ? consoleNeutralAccent(familyID: familyID) : colors.tint.color
    let neutral = PlayerControlStyle(
        fillColor: palette.controlFill.color,
        pressedFillColor: palette.controlPressed.color,
        borderColor: palette.controlStroke.color,
        pressedBorderColor: shape.frosted ? accentColor.opacity(0.7) : palette.controlInk.color,
        contentColor: palette.controlInk.color,
        borderWidth: shape.frosted ? 1 : 1.5,
        frosted: shape.frosted
    )
    let face = shape.frosted ? neutral : PlayerControlStyle(
        fillColor: palette.faceFill.color,
        pressedFillColor: palette.faceFill.color.mix(with: .white, by: 0.35),
        borderColor: .clear,
        pressedBorderColor: .clear,
        contentColor: palette.faceInk.color,
        plateColor: palette.offsetPlate.color,
        borderWidth: 0
    )
    let faceStyles: [String: PlayerControlStyle]
    if consoleColorsEnabled {
        faceStyles = consoleFaceColors(familyID: familyID).mapValues { color in
            shape.frosted ? coloredStyle(color: color, dark: dark) : inkStyle(color: color, palette: palette)
        }
    } else {
        // Without console colours every face button takes the theme's face style.
        faceStyles = consoleFaceColors(familyID: familyID).mapValues { _ in face }
    }
    return PlayerVisualTheme(
        canvasColor: canvasColor,
        panelColor: panelColor,
        panelAltColor: panelAltColor,
        textColor: oledBlackMode ? .white : palette.menuInk.color,
        accentColor: accentColor,
        neutralControlStyle: neutral,
        faceControlStyles: faceStyles,
        shape: shape,
        palette: palette,
        legendFont: PlayerFontSpec(appTheme.tokens.type.playerLegend),
        labelFont: PlayerFontSpec(appTheme.tokens.type.playerLabel),
        glyphs: shape.dpad == .twoPlateCross ? .triangles : .chevrons
    )
}

private func coloredStyle(color: Color, dark: Bool) -> PlayerControlStyle {
    // On light player grounds the console colour darkens so legends stay legible.
    let legend = dark ? color : color.mix(with: .black, by: 0.3)
    // A wash of the console colour over the frosted face.
    return PlayerControlStyle(
        fillColor: color.opacity(dark ? 0.24 : 0.2),
        pressedFillColor: color.opacity(0.42),
        borderColor: color.opacity(0.55),
        pressedBorderColor: dark ? .white.opacity(0.75) : .black.opacity(0.5),
        contentColor: legend,
        frosted: true
    )
}

/// Riso prints a console's button colour in the nearest of its four inks, flat,
/// with the pink plate off register behind it.
private func inkStyle(color: Color, palette: PlayerColorTokens) -> PlayerControlStyle {
    let ink = nearestInk(color, palette: palette)
    return PlayerControlStyle(
        fillColor: ink,
        pressedFillColor: ink.mix(with: .white, by: 0.35),
        borderColor: .clear,
        pressedBorderColor: .clear,
        contentColor: palette.faceInk.color,
        plateColor: ink == palette.dpadFill.color ? palette.dpadAltFill.color : palette.offsetPlate.color,
        borderWidth: 0
    )
}

/// The ink (pink, blue, yellow, green) whose hue is closest to `color`.
func nearestInk(_ color: Color, palette: PlayerColorTokens) -> Color {
    let inks: [ThemeRGBA] = [palette.dpadFill, palette.dpadAltFill, palette.faceFill, ThemeTokens.riso.dark.accent4]
    let target = color.rgba
    let hue = ArtColorMath.hsl(ArtRGB(red: target.red, green: target.green, blue: target.blue)).hue
    func distance(_ ink: ThemeRGBA) -> Double {
        let h = ArtColorMath.hsl(ArtRGB(red: ink.red, green: ink.green, blue: ink.blue)).hue
        let d = abs(h - hue).truncatingRemainder(dividingBy: 360)
        return min(d, 360 - d)
    }
    return (inks.min { distance($0) < distance($1) } ?? palette.faceFill).color
}

private func consoleFaceColors(familyID: String?) -> [String: Color] {
    switch familyID {
    case "snes", "nds":
        ["Y": Color(hex: 0x46B06E), "B": Color(hex: 0xF1C94B), "A": Color(hex: 0xDA5A58), "X": Color(hex: 0x5F7DDE)]
    case "psx", "psp":
        ["SQUARE": Color(hex: 0xD06AAF), "CROSS": Color(hex: 0x4E8EEA), "CIRCLE": Color(hex: 0xE05C4B), "TRIANGLE": Color(hex: 0x55B96D)]
    case "sega16":
        ["A": Color(hex: 0xBBC6D8), "B": Color(hex: 0xBBC6D8), "C": Color(hex: 0xBBC6D8), "X": Color(hex: 0xBBC6D8), "Y": Color(hex: 0xBBC6D8), "Z": Color(hex: 0xBBC6D8), "START": Color(hex: 0xE17C79)]
    case "n64":
        ["A": Color(hex: 0x619CF4), "B": Color(hex: 0x60CF92), "C-UP": Color(hex: 0xEBC861), "C-DOWN": Color(hex: 0xEBC861), "C-LEFT": Color(hex: 0xEBC861), "C-RIGHT": Color(hex: 0xEBC861)]
    case "sms", "gamegear":
        ["1": Color(hex: 0x6BB9D0), "2": Color(hex: 0x6BB9D0)]
    case "arcade":
        ["1": Color(hex: 0x4A78D0), "2": Color(hex: 0x4FB56B), "3": Color(hex: 0xF0C14A), "4": Color(hex: 0xD85E4B)]
    case "nes":
        ["A": Color(hex: 0xD85C56), "B": Color(hex: 0xB54845)]
    case "gb":
        ["A": Color(hex: 0x8A4FA8), "B": Color(hex: 0xB45AA2)]
    case "gba":
        ["A": Color(hex: 0x7D70D6), "B": Color(hex: 0x9887E4)]
    case "tg16":
        ["I": Color(hex: 0x4479CF), "II": Color(hex: 0xE08C3E)]
    case "atari2600":
        ["Fire": Color(hex: 0xE08C3E), "Trigger": Color(hex: 0xD85E4B)]
    case "atari7800":
        ["1": Color(hex: 0xE08C3E), "2": Color(hex: 0xD85E4B)]
    case "lynx":
        ["A": Color(hex: 0xE08C3E), "B": Color(hex: 0xD85E4B)]
    default:
        [:]
    }
}

private func consoleNeutralAccent(familyID: String?) -> Color {
    switch familyID {
    case "snes", "nds":
        Color(hex: 0x7F6BC4)
    case "psx", "psp":
        Color(hex: 0x5E82D8)
    case "sega16":
        Color(hex: 0x4A78D0)
    case "n64":
        Color(hex: 0x60CF92)
    case "sms", "gamegear":
        Color(hex: 0x6BB9D0)
    case "arcade":
        Color(hex: 0xD85E4B)
    case "nes":
        Color(hex: 0xB54845)
    case "gb":
        Color(hex: 0x8A4FA8)
    case "gba":
        Color(hex: 0x7D70D6)
    case "tg16":
        Color(hex: 0x4479CF)
    case "atari2600", "atari7800", "lynx":
        Color(hex: 0xE08C3E)
    default:
        Color(red: 0.20, green: 0.52, blue: 0.98)
    }
}

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255.0,
            green: Double((hex >> 8) & 0xFF) / 255.0,
            blue: Double(hex & 0xFF) / 255.0,
            opacity: alpha
        )
    }

    /// sRGB components (0...1).
    var rgba: (red: Double, green: Double, blue: Double, alpha: Double) {
        let resolved = resolve(in: EnvironmentValues())
        return (Double(resolved.red), Double(resolved.green), Double(resolved.blue), Double(resolved.opacity))
    }
}
