import SwiftUI
import RommioFoundation

struct PlayerControlStyle {
    let fillColor: Color
    let pressedFillColor: Color
    let borderColor: Color
    let pressedBorderColor: Color
    let contentColor: Color
}

struct PlayerVisualTheme {
    let canvasColor: Color
    let panelColor: Color
    let panelAltColor: Color
    let textColor: Color
    let accentColor: Color
    let neutralControlStyle: PlayerControlStyle
    let faceControlStyles: [String: PlayerControlStyle]

    func faceStyle(label: String) -> PlayerControlStyle {
        faceControlStyles[label.uppercased()] ?? neutralControlStyle
    }
}

func resolvePlayerVisualTheme(controls: PlayerControlsState?, familyOverride: String? = nil) -> PlayerVisualTheme {
    let oledBlackMode = controls?.preferences.oledBlackModeEnabled == true
    let consoleColorsEnabled = controls?.preferences.consoleColorsEnabled ?? true
    let familyID = familyOverride ?? controls?.platformProfile.familyID
    let canvasColor = oledBlackMode ? Color.black : Color(red: 0.05, green: 0.06, blue: 0.08)
    let panelColor = oledBlackMode ? Color.black : Color(red: 0.10, green: 0.11, blue: 0.15)
    let panelAltColor = oledBlackMode ? Color(red: 0.03, green: 0.03, blue: 0.03) : Color(red: 0.14, green: 0.16, blue: 0.20)
    let accentColor = consoleColorsEnabled ? consoleNeutralAccent(familyID: familyID) : Color(red: 0.20, green: 0.52, blue: 0.98)
    let neutral = neutralStyle(baseColor: panelAltColor, accentColor: accentColor)
    let faceStyles = consoleColorsEnabled ? consoleFaceColors(familyID: familyID).mapValues { coloredStyle(color: $0) } : [:]
    return PlayerVisualTheme(
        canvasColor: canvasColor,
        panelColor: panelColor,
        panelAltColor: panelAltColor,
        textColor: .white,
        accentColor: accentColor,
        neutralControlStyle: neutral,
        faceControlStyles: faceStyles
    )
}

private func neutralStyle(baseColor: Color, accentColor: Color) -> PlayerControlStyle {
    PlayerControlStyle(
        fillColor: baseColor.opacity(0.92),
        pressedFillColor: accentColor.opacity(0.38),
        borderColor: accentColor.opacity(0.20),
        pressedBorderColor: accentColor.opacity(0.64),
        contentColor: .white
    )
}

private func coloredStyle(color: Color) -> PlayerControlStyle {
    PlayerControlStyle(
        fillColor: Color(red: 0.12, green: 0.13, blue: 0.16),
        pressedFillColor: color.opacity(0.38),
        borderColor: color.opacity(0.55),
        pressedBorderColor: .white.opacity(0.75),
        contentColor: color
    )
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

private extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255.0,
            green: Double((hex >> 8) & 0xFF) / 255.0,
            blue: Double(hex & 0xFF) / 255.0,
            opacity: alpha
        )
    }

    var brightness: Double {
        #if canImport(UIKit)
        let uiColor = UIColor(self)
        var hue: CGFloat = 0
        var saturation: CGFloat = 0
        var brightness: CGFloat = 0
        var alpha: CGFloat = 0
        uiColor.getHue(&hue, saturation: &saturation, brightness: &brightness, alpha: &alpha)
        return brightness
        #else
        return 0.5
        #endif
    }
}
