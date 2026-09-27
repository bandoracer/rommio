import SwiftUI
import XCTest
@testable import RommioFoundation
@testable import RommioUI

/// The player style renderer: which shape, fill, plate, font and glyph each
/// control gets in each theme. Touch handling and geometry are not styled.
final class PlayerControlStyleRendererTests: XCTestCase {
    private func controls(family: String, console: Bool, oled: Bool = false) -> PlayerControlsState {
        let profile = PlatformControlProfileResolver().resolve(platformSlug: family)
        return PlayerControlsState(
            platformProfile: profile,
            touchLayout: profile.defaultTouchLayout(),
            hardwareBinding: HardwareBindingProfile(platformFamilyID: family),
            preferences: PlayerControlsPreferences(touchControlsEnabled: true, autoHideTouchOnController: true, rumbleToDeviceEnabled: true,
                                                   oledBlackModeEnabled: oled, consoleColorsEnabled: console),
            connectedControllers: [], inputMode: .touch, showTouchControls: true)
    }

    private func renderer(_ theme: AppearanceTheme, dark: Bool = false, family: String = "snes", console: Bool = false, oled: Bool = false) -> PlayerControlStyleRenderer {
        PlayerControlStyleRenderer(theme: resolvePlayerVisualTheme(controls: controls(family: family, console: console, oled: oled),
                                                                   appTheme: RommioTheme(theme, dark: dark)))
    }

    func testRolesFollowTheControlNotTheTheme() {
        XCTAssertEqual(PlayerControlRole.resolve(label: "A", stick: false, trigger: false), .face)
        XCTAssertEqual(PlayerControlRole.resolve(label: "Up", stick: false, trigger: false), .direction)
        XCTAssertEqual(PlayerControlRole.resolve(label: "L1", stick: false, trigger: false), .shoulder)
        XCTAssertEqual(PlayerControlRole.resolve(label: "Z", stick: false, trigger: true), .shoulder)
        XCTAssertEqual(PlayerControlRole.resolve(label: "Start", stick: false, trigger: false), .system)
        XCTAssertEqual(PlayerControlRole.resolve(label: "Analog stick", stick: true, trigger: false), .stick)
    }

    /// docs/player-controls.md, Look: legends at `playerLegend`, 22 pt on two-button
    /// (64 pt) faces and about 20 pt on diamond (56 pt) faces; pills keep the token size.
    func testLegendsFollowTheSpecSizes() {
        let aurora = renderer(.aurora, dark: true)
        let face = aurora.appearance(label: "A")
        XCTAssertEqual(face.legendSize(for: 64), 22, accuracy: 0.01)
        XCTAssertEqual(face.legendSize(for: 56), 20, accuracy: 0.3)
        let start = aurora.appearance(label: "Start")
        XCTAssertEqual(start.legendSize(for: 34), ThemeTokens.aurora.type.playerLabel.size, accuracy: 0.01)
        // Pressed Aurora controls dip to 94%; Riso keeps its size and lightens.
        XCTAssertEqual(face.pressedScale, 0.94)
        XCTAssertEqual(renderer(.riso).appearance(label: "A").pressedScale, 1)
    }

    func testRisoPrintsFlatInkShapesWithTheOffsetPlate() {
        let riso = renderer(.riso)
        let player = ThemeTokens.riso.playerLight
        let misregistration = ThemeTokens.riso.playerShape.misregistration

        let face = riso.appearance(label: "A")
        XCTAssertEqual(face.shape, .circle)
        XCTAssertEqual(face.fill, player.faceFill.color, "yellow face")
        XCTAssertEqual(face.plate, player.offsetPlate.color, "pink plate")
        XCTAssertEqual(face.plateOffset, CGSize(width: misregistration, height: misregistration))
        XCTAssertFalse(face.frosted)
        XCTAssertEqual(face.font.postScriptName, ThemeTokens.riso.type.playerLegend.postScriptName)

        let shoulder = riso.appearance(label: "L")
        XCTAssertEqual(shoulder.shape, .tab(top: 18, bottom: 6))
        XCTAssertEqual(shoulder.fill, player.shoulderFill.color, "blue shoulder tab")
        XCTAssertEqual(shoulder.plate, player.offsetPlate.color)

        let select = riso.appearance(label: "Select")
        XCTAssertEqual(select.shape, .capsule)
        XCTAssertEqual(select.stroke, player.controlStroke.color, "outlined")
        XCTAssertEqual(select.strokeWidth, 1.5)
        XCTAssertNil(select.plate)
        XCTAssertEqual(select.font.postScriptName, ThemeTokens.riso.type.playerLabel.postScriptName)
        XCTAssertTrue(select.font.uppercase, "mono caps")

        let menu = riso.menu()
        XCTAssertEqual(menu.shape, .capsule)
        XCTAssertEqual(menu.fill, player.menuFill.color, "pink menu pill")
        XCTAssertEqual(menu.symbol, "line.3.horizontal")

        let dpad = riso.dpad()
        XCTAssertTrue(dpad.twoPlate)
        XCTAssertEqual(dpad.arm, player.dpadFill.color, "pink arms")
        XCTAssertEqual(dpad.plate, player.dpadAltFill.color, "blue plate")
        XCTAssertEqual(dpad.pivot, player.pivot.color, "yellow pivot")
        XCTAssertEqual(dpad.glyphs, .triangles)
        XCTAssertNil(dpad.stroke, "ink plates, no keyline")

        XCTAssertEqual(riso.opacity(user: 0.6), 1, "Riso never prints over a game, so it keeps full ink")
    }

    func testAuroraFrostsEveryControlInTheGamesLight() {
        let aurora = renderer(.aurora, dark: true)
        let player = ThemeTokens.aurora.playerDark

        let face = aurora.appearance(label: "A")
        XCTAssertEqual(face.shape, .circle)
        XCTAssertTrue(face.frosted)
        XCTAssertNil(face.plate)
        XCTAssertEqual(face.fill, player.controlFill.color)
        XCTAssertEqual(face.font.postScriptName, ThemeTokens.aurora.type.playerLegend.postScriptName)

        let shoulder = aurora.appearance(label: "R")
        XCTAssertEqual(shoulder.shape, .tab(top: 14, bottom: 20))
        XCTAssertTrue(shoulder.frosted)

        XCTAssertEqual(aurora.menu().shape, .circle)
        XCTAssertTrue(aurora.menu().frosted)

        let dpad = aurora.dpad()
        XCTAssertFalse(dpad.twoPlate)
        XCTAssertEqual(dpad.arm, player.controlFill.color, "a glass cross (no disc)")
        XCTAssertNotNil(dpad.stroke)
        XCTAssertTrue(dpad.frosted)
        XCTAssertEqual(dpad.glyphs, .chevrons)
        XCTAssertEqual(aurora.opacity(user: 0.6), 0.6)
        XCTAssertEqual(aurora.opacity(user: 0.3, role: .face), 0.5, "Faces never fade below 0.5")
        XCTAssertEqual(aurora.opacity(user: 0.3, role: .shoulder), 0.3)
        // The glass edge: a keyline, a top rim and a soft shadow.
        XCTAssertNotNil(face.rim)
        XCTAssertNotNil(face.shadow)
        XCTAssertEqual(face.strokeWidth, 0.5)
    }

    func testConsoleColoursKeepWorkingInBothThemes() {
        // Aurora keeps the frosted face and colours the legend and a 2 pt ring (never a wash).
        for dark in [false, true] {
            let aurora = renderer(.aurora, dark: dark, console: true)
            let a = aurora.appearance(label: "A"), x = aurora.appearance(label: "X"), b = aurora.appearance(label: "B")
            let player = dark ? ThemeTokens.aurora.playerDark : ThemeTokens.aurora.playerLight
            XCTAssertEqual(a.fill, player.controlFill.color, "The face stays frosted")
            XCTAssertEqual(a.fill, x.fill)
            XCTAssertTrue(a.frosted)
            XCTAssertNotNil(a.ring)
            XCTAssertNotEqual(a.ring, x.ring)
            XCTAssertNotEqual(a.ink, x.ink)
            // Legends keep 3:1 against the face, even SNES's yellow B on a light face, and
            // even at the lowest face opacity (0.5) over the player ground.
            let face = effectiveControlFill(player)
            let ground = ArtRGB(red: player.ground.red, green: player.ground.green, blue: player.ground.blue)
            for legend in [a.ink, x.ink, b.ink] {
                let rgba = legend.rgba
                let ink = ArtRGB(red: rgba.red, green: rgba.green, blue: rgba.blue)
                XCTAssertGreaterThanOrEqual(ReadableTint.contrast(ink, face), 3.5, "dark=\(dark)")
                // The legend stays at full ink while the face fades.
                XCTAssertGreaterThanOrEqual(ReadableTint.contrast(ink, ReadableTint.mix(face, over: ground, alpha: 0.5)), 3, "dark=\(dark) at 0.5")
            }
        }

        // Riso prints each console colour in its nearest ink.
        let riso = renderer(.riso, console: true)
        let player = ThemeTokens.riso.playerLight
        XCTAssertEqual(riso.appearance(label: "A").fill, player.dpadFill.color, "SNES red A prints pink")
        XCTAssertEqual(riso.appearance(label: "X").fill, player.dpadAltFill.color, "SNES blue X prints blue")
        XCTAssertEqual(riso.appearance(label: "B").fill, player.faceFill.color, "SNES yellow B prints yellow")
        XCTAssertNotNil(riso.appearance(label: "Y").plate)

        // Without console colours every face is the theme's face.
        let plain = renderer(.riso, console: false)
        XCTAssertEqual(plain.appearance(label: "A").fill, plain.appearance(label: "X").fill)
    }

    func testOLEDBlackKeepsWorkingUnderRiso() {
        let theme = resolvePlayerVisualTheme(controls: controls(family: "nes", console: false, oled: true), appTheme: RommioTheme(.riso, dark: false))
        XCTAssertEqual(theme.canvasColor, .black)
        XCTAssertEqual(theme.panelColor, .black)
        XCTAssertEqual(theme.palette.faceFill, ThemeTokens.riso.playerDark.faceFill)
    }

    func testLegendsScaleWithTheFace() {
        let face = renderer(.riso).appearance(label: "A")
        XCTAssertEqual(face.legendSize(for: PlayerControlStyleRenderer.referenceDiameter), ThemeTokens.riso.type.playerLegend.size, accuracy: 0.001)
        XCTAssertLessThan(face.legendSize(for: 37), face.legendSize(for: 56))
        XCTAssertEqual(PlayerControlStyleRenderer.legend(for: "Cross"), "×")
    }

    func testPlayTimeReadsLikeAndroid() {
        XCTAssertEqual(PlayFormat.compactDuration(120), "2 min")
        XCTAssertEqual(PlayFormat.compactDuration(0), "0 min")
        XCTAssertEqual(PlayFormat.compactDuration(3 * 3600 + 5 * 60), "3 h 05 min")
        XCTAssertEqual(PlayFormat.duration(30), "Under a minute")
        XCTAssertEqual(PlayFormat.duration(2 * 3600), "2 h")
    }
}
