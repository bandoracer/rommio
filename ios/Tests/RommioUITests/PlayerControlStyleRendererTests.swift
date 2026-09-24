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

    func testFaceGeometryIsUnchangedByStyle() {
        let bounds = CGRect(x: 0, y: 0, width: 48, height: 48)
        XCTAssertEqual(PlayerControlAppearance.faceRect(in: bounds, role: .face).width, 48 * 0.78, accuracy: 0.001)
        let pill = PlayerControlAppearance.faceRect(in: bounds, role: .system)
        XCTAssertEqual(pill.width, 48 * 0.94, accuracy: 0.001)
        XCTAssertEqual(pill.height, 48 * 0.94 * 0.72, accuracy: 0.001)
        XCTAssertEqual(pill.midY, bounds.midY, accuracy: 0.001)
        XCTAssertEqual(PlayerControlAppearance.faceRect(in: bounds, role: .stick).width, 48, accuracy: 0.001)
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
        XCTAssertNil(dpad.disc)

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
        XCTAssertEqual(dpad.disc, player.controlFill.color)
        XCTAssertTrue(dpad.frosted)
        XCTAssertEqual(dpad.glyphs, .chevrons)
        XCTAssertEqual(aurora.opacity(user: 0.6), 0.6)
    }

    func testConsoleColoursKeepWorkingInBothThemes() {
        // Aurora washes glass with the console's colours.
        let aurora = renderer(.aurora, dark: true, console: true)
        XCTAssertNotEqual(aurora.appearance(label: "A").fill, aurora.appearance(label: "X").fill)
        XCTAssertTrue(aurora.appearance(label: "A").frosted)

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
