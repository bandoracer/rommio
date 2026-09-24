import CoreText
import SwiftUI
import XCTest
@testable import RommioFoundation
@testable import RommioUI

final class ThemeTests: XCTestCase {
    func testEveryTypeRoleResolvesToItsBundledFaceAfterRegistration() {
        XCTAssertEqual(RommioFonts.registerIfNeeded(), [], "Fonts that failed to register")
        for set in ThemeTokens.all {
            let theme = RommioTheme(tokens: set, isDark: false)
            for role in TypeRole.allCases {
                let expected = theme.role(role).postScriptName
                let font = CTFontCreateWithName(expected as CFString, 12, nil)
                XCTAssertEqual(CTFontCopyPostScriptName(font) as String, expected, "\(set.key).\(role.rawValue)")
            }
        }
    }

    func testRegistrationIsIdempotent() {
        XCTAssertEqual(RommioFonts.registerIfNeeded(), [])
        XCTAssertEqual(RommioFonts.registerIfNeeded(), [])
    }

    func testEveryBundledFontFileIsPresent() {
        for name in ThemeFontFiles.all {
            XCTAssertNotNil(Bundle.module.url(forResource: name, withExtension: "ttf", subdirectory: "Fonts")
                            ?? Bundle.module.url(forResource: name, withExtension: "ttf"), name)
        }
    }

    func testThemeResolvesTokensForTheSchemeAndDefaultsToAurora() {
        XCTAssertEqual(AppearanceTheme.default, .aurora)
        XCTAssertEqual(RommioTheme.aurora.tokens.key, "aurora")
        let risoDark = RommioTheme(.riso, dark: true)
        XCTAssertEqual(risoDark.colors.ground, ThemeTokens.riso.dark.ground)
        XCTAssertEqual(risoDark.player.faceFill, ThemeTokens.riso.playerDark.faceFill)
        XCTAssertFalse(risoDark.isGlass)
        let auroraLight = RommioTheme(.aurora, dark: false)
        XCTAssertEqual(auroraLight.colors.tint, ThemeTokens.aurora.light.tint)
        XCTAssertTrue(auroraLight.isGlass)
        XCTAssertNotEqual(auroraLight.identity, RommioTheme(.aurora, dark: true).identity)
    }

    func testEveryAppearanceThemeHasATokenSet() {
        for theme in AppearanceTheme.allCases {
            XCTAssertEqual(ThemeTokens.forKey(theme.rawValue).key, theme.rawValue)
        }
    }

    func testPlayerColoursComeFromThePlayerTokens() {
        let light = resolvePlayerVisualTheme(controls: nil, familyOverride: "nes", appTheme: RommioTheme(.aurora, dark: false))
        XCTAssertEqual(light.canvasColor, ThemeTokens.aurora.playerLight.ground.color)
        XCTAssertEqual(light.neutralControlStyle.fillColor, ThemeTokens.aurora.playerLight.controlFill.color)
        let riso = resolvePlayerVisualTheme(controls: nil, familyOverride: "nes", appTheme: RommioTheme(.riso, dark: false))
        XCTAssertEqual(riso.canvasColor, ThemeTokens.riso.playerLight.ground.color)
        XCTAssertEqual(riso.neutralControlStyle.contentColor, ThemeTokens.riso.playerLight.controlInk.color)
    }
}
