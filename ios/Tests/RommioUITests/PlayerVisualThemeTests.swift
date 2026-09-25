import SwiftUI
import XCTest
@testable import RommioFoundation
@testable import RommioUI

final class PlayerVisualThemeTests: XCTestCase {
    func testOLEDBlackOnlyChangesPlayerCanvasAndPanels() {
        let controls = PlayerControlsState(
            platformProfile: PlatformControlProfileResolver().resolve(platformSlug: "nes"),
            touchLayout: PlatformControlProfileResolver().resolve(platformSlug: "nes").defaultTouchLayout(),
            hardwareBinding: HardwareBindingProfile(platformFamilyID: "nes"),
            preferences: PlayerControlsPreferences(
                touchControlsEnabled: true,
                autoHideTouchOnController: true,
                rumbleToDeviceEnabled: true,
                oledBlackModeEnabled: true,
                consoleColorsEnabled: false
            ),
            connectedControllers: [],
            inputMode: .touch,
            showTouchControls: true
        )

        let theme = resolvePlayerVisualTheme(controls: controls)
        XCTAssertEqual(theme.canvasColor, .black)
        XCTAssertEqual(theme.panelColor, .black)
        XCTAssertEqual(theme.textColor, .white)
    }

    func testConsoleColorsUseFamilySpecificFaceStyles() {
        let controls = PlayerControlsState(
            platformProfile: PlatformControlProfileResolver().resolve(platformSlug: "snes"),
            touchLayout: PlatformControlProfileResolver().resolve(platformSlug: "snes").defaultTouchLayout(),
            hardwareBinding: HardwareBindingProfile(platformFamilyID: "snes"),
            preferences: PlayerControlsPreferences(
                touchControlsEnabled: true,
                autoHideTouchOnController: true,
                rumbleToDeviceEnabled: true,
                oledBlackModeEnabled: false,
                consoleColorsEnabled: true
            ),
            connectedControllers: [],
            inputMode: .touch,
            showTouchControls: true
        )

        let theme = resolvePlayerVisualTheme(controls: controls)
        // Aurora (docs/player-controls.md, Look): the face stays frosted; the console
        // colour is the legend and a 2 pt ring.
        XCTAssertEqual(theme.faceStyle(label: "A").fillColor, theme.neutralControlStyle.fillColor)
        XCTAssertNotNil(theme.faceStyle(label: "A").ring)
        XCTAssertNotEqual(theme.faceStyle(label: "A").ring, theme.faceStyle(label: "X").ring)
        XCTAssertNotEqual(theme.faceStyle(label: "X").contentColor, theme.faceStyle(label: "A").contentColor)
    }
}
