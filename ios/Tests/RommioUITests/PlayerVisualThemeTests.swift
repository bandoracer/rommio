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
        XCTAssertNotEqual(theme.faceStyle(label: "A").fillColor, theme.neutralControlStyle.fillColor)
        XCTAssertNotEqual(theme.faceStyle(label: "X").fillColor, theme.faceStyle(label: "A").fillColor)
    }
}
