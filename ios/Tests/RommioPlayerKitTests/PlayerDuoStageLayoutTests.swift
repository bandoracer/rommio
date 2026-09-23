import CoreGraphics
import XCTest
@testable import RommioPlayerKit

/// iPhone Duo geometries from the Xcode 27.1 simulator: outer display
/// 466×678, inner display open 803×515, rotated 669×703.
final class PlayerDuoStageLayoutTests: XCTestCase {
    private let openSize = CGSize(width: 803, height: 515)
    private let openInsets = PlayerViewportSafeAreaInsets(top: 0, bottom: 20, left: 0, right: 0)
    /// A flat display reports the hinge as an inactive, zero-width region.
    private var flatFold: CGRect { CGRect(x: 401.5, y: 0, width: 0, height: 515) }

    private func dsLayout(size: CGSize, insets: PlayerViewportSafeAreaInsets, controls: Bool = true,
                          stage: PlayerStageContext) -> PlayerStageLayout {
        resolvePlayerStageLayout(containerSize: size, safeAreaInsets: insets, aspectRatio: 2.0 / 3.0,
                                 topAlignedInPortrait: true, controlDeckHeight: 200, reserveTouchSpace: controls,
                                 utilityCount: 3, stage: stage)
    }

    /// iPhone Duo unfolded and held in portrait: 669×951 with the hinge across the middle.
    private let portraitSize = CGSize(width: 669, height: 951)
    private let portraitInsets = PlayerViewportSafeAreaInsets(top: 20, bottom: 20, left: 0, right: 0)

    func testUnfoldedPortraitStacksDSScreensAcrossTheHinge() {
        // Beta 1 reports no hinge, so the midline stands in for it.
        let layout = dsLayout(size: portraitSize, insets: portraitInsets, stage: PlayerStageContext(dualScreen: true, isPhoneFormFactor: true))
        XCTAssertEqual(layout.screens.count, 2)
        let (top, touch) = (layout.screens[0], layout.screens[1])
        XCTAssertEqual(top.source, CGRect(x: 0, y: 0, width: 1, height: 0.5))
        XCTAssertTrue(touch.isTouchScreen)
        XCTAssertLessThanOrEqual(top.frame.maxY, portraitSize.height / 2)
        XCTAssertGreaterThanOrEqual(touch.frame.minY, portraitSize.height / 2)
        XCTAssertGreaterThan(top.frame.width, touch.frame.width)
        XCTAssertLessThanOrEqual(top.frame.width, touch.frame.width * 1.3 + 0.01)
        XCTAssertEqual(top.frame.width / top.frame.height, 4.0 / 3.0, accuracy: 0.01)
        // Controls sit beside the touch screen, entirely in the lower half.
        XCTAssertFalse(layout.deck.isPortrait)
        XCTAssertGreaterThanOrEqual(layout.deck.railTop, portraitSize.height / 2)
        XCTAssertLessThanOrEqual(layout.deck.leftOrigin + layout.deck.railWidth, touch.frame.minX)
        XCTAssertGreaterThanOrEqual(layout.deck.rightOrigin, touch.frame.maxX)
    }

    func testReportedHorizontalHingeSplitsTheScreens() {
        let fold = CGRect(x: 0, y: 465.5, width: 669, height: 20)
        let layout = dsLayout(size: portraitSize, insets: portraitInsets,
                              stage: PlayerStageContext(fold: fold, foldIsActive: true, dualScreen: true))
        XCTAssertLessThanOrEqual(layout.screens[0].frame.maxY, fold.minY)
        XCTAssertGreaterThanOrEqual(layout.screens[1].frame.minY, fold.maxY)
    }

    func testDSStaysStackedWhenUnfoldedInLandscape() {
        let layout = dsLayout(size: CGSize(width: 951, height: 669), insets: openInsets,
                              stage: PlayerStageContext(fold: CGRect(x: 475.5, y: 0, width: 0, height: 669), dualScreen: true))
        let (top, touch) = (layout.screens[0], layout.screens[1])
        XCTAssertEqual(top.frame.minX, touch.frame.minX, accuracy: 0.5)
        XCTAssertLessThan(top.frame.maxY, touch.frame.minY)
    }

    func testBookPoseMovesStackedDSScreensOffTheFold() {
        let fold = CGRect(x: 465.5, y: 0, width: 20, height: 669)
        let layout = dsLayout(size: CGSize(width: 951, height: 669), insets: openInsets,
                              stage: PlayerStageContext(fold: fold, foldIsActive: true, dualScreen: true))
        XCTAssertTrue(layout.isPadMode)
        XCTAssertLessThanOrEqual(layout.viewport.frame.maxX, fold.minX)
        XCTAssertLessThan(layout.screens[0].frame.maxY, layout.screens[1].frame.minY)
        XCTAssertGreaterThanOrEqual(layout.deck.leftOrigin, fold.maxX)
    }

    func testPhoneDSLayoutStaysStackedWithControlsBelow() {
        let layout = dsLayout(size: CGSize(width: 430, height: 932), insets: PlayerViewportSafeAreaInsets(top: 59, bottom: 34, left: 0, right: 0),
                              stage: PlayerStageContext(dualScreen: true))
        XCTAssertTrue(layout.deck.isPortrait)
        XCTAssertLessThan(layout.screens[0].frame.maxY, layout.screens[1].frame.minY)
    }

    func testThreeDSScreensKeepNativeAspectsAndCenteredTouchSource() {
        let layout = resolvePlayerStageLayout(
            containerSize: CGSize(width: 430, height: 932),
            safeAreaInsets: PlayerViewportSafeAreaInsets(top: 59, bottom: 34, left: 0, right: 0),
            aspectRatio: 5.0 / 6.0, topAlignedInPortrait: true,
            reserveTouchSpace: true,
            stage: PlayerStageContext(dualScreen: true, threeDS: true)
        )
        XCTAssertEqual(layout.screens.count, 2)
        XCTAssertEqual(layout.screens[0].frame.width / layout.screens[0].frame.height, 5.0 / 3.0, accuracy: 0.01)
        XCTAssertEqual(layout.screens[1].frame.width / layout.screens[1].frame.height, 4.0 / 3.0, accuracy: 0.01)
        XCTAssertEqual(layout.screens[1].source, CGRect(x: 0.1, y: 0.5, width: 0.8, height: 0.5))
        XCTAssertTrue(layout.screens[1].isTouchScreen)
        XCTAssertLessThan(layout.screens[0].frame.maxY, layout.screens[1].frame.minY)
    }

    func testOpenDuoThreeDSEnlargesTheUpperScreenWithoutCrossingTheCrease() {
        let layout = resolvePlayerStageLayout(
            containerSize: portraitSize, safeAreaInsets: portraitInsets,
            aspectRatio: 5.0 / 6.0, topAlignedInPortrait: true,
            reserveTouchSpace: true,
            stage: PlayerStageContext(dualScreen: true, threeDS: true, isPhoneFormFactor: true)
        )
        let (top, touch) = (layout.screens[0], layout.screens[1])
        XCTAssertLessThanOrEqual(top.frame.maxY, portraitSize.height / 2)
        XCTAssertGreaterThanOrEqual(touch.frame.minY, portraitSize.height / 2)
        XCTAssertGreaterThan(top.frame.width, touch.frame.width * 1.25)
        XCTAssertLessThanOrEqual(top.frame.width, touch.frame.width * 1.5 + 0.01)
        XCTAssertLessThanOrEqual(layout.deck.leftOrigin + layout.deck.railWidth, touch.frame.minX)
        XCTAssertGreaterThanOrEqual(layout.deck.rightOrigin, touch.frame.maxX)
    }

    func testIPadThreeDSScreensStayTogetherWithoutAnInventedHinge() {
        let layout = resolvePlayerStageLayout(
            containerSize: CGSize(width: 820, height: 1180),
            safeAreaInsets: PlayerViewportSafeAreaInsets(top: 24, bottom: 20, left: 0, right: 0),
            aspectRatio: 5.0 / 6.0, topAlignedInPortrait: true,
            reserveTouchSpace: true,
            stage: PlayerStageContext(dualScreen: true, threeDS: true, isPhoneFormFactor: false)
        )
        XCTAssertEqual(layout.screens.count, 2)
        XCTAssertEqual(layout.screens[1].frame.minY - layout.screens[0].frame.maxY, 12, accuracy: 0.5)
    }

    func testBookPoseGivesSingleScreenGamesAPadHalf() {
        let fold = CGRect(x: 391.5, y: 0, width: 20, height: 515)
        let layout = resolvePlayerStageLayout(containerSize: openSize, safeAreaInsets: openInsets, aspectRatio: 4.0 / 3.0,
                                              topAlignedInPortrait: true, reserveTouchSpace: true,
                                              stage: PlayerStageContext(fold: fold, foldIsActive: true))
        XCTAssertTrue(layout.isPadMode)
        XCTAssertLessThanOrEqual(layout.viewport.frame.maxX, fold.minX)
        XCTAssertGreaterThanOrEqual(layout.deck.leftOrigin, fold.maxX)
    }

    func testLeftHandedPadModeSwapsHalves() {
        let fold = CGRect(x: 391.5, y: 0, width: 20, height: 515)
        let layout = resolvePlayerStageLayout(containerSize: openSize, safeAreaInsets: openInsets, aspectRatio: 4.0 / 3.0,
                                              topAlignedInPortrait: true, reserveTouchSpace: true,
                                              stage: PlayerStageContext(fold: fold, foldIsActive: true, leftHanded: true))
        XCTAssertGreaterThanOrEqual(layout.viewport.frame.minX, fold.maxX)
        XCTAssertLessThanOrEqual(layout.deck.rightOrigin + layout.deck.railWidth, fold.minX)
    }

    func testRequestedSplitPadUsesTheCentreWhenTheHingeIsNotReported() {
        // Xcode 27.1 beta 1 reports no reserved regions; the split still lands mid-display.
        let layout = resolvePlayerStageLayout(containerSize: openSize, safeAreaInsets: openInsets, aspectRatio: 4.0 / 3.0,
                                              topAlignedInPortrait: true, reserveTouchSpace: true,
                                              stage: PlayerStageContext(prefersSplitPad: true))
        XCTAssertTrue(layout.isPadMode)
        XCTAssertLessThanOrEqual(layout.viewport.frame.maxX, openSize.width / 2)
        XCTAssertGreaterThanOrEqual(layout.deck.leftOrigin, openSize.width / 2)
    }

    func testRequestedSplitPadIsIgnoredOnNarrowDisplays() {
        let layout = resolvePlayerStageLayout(containerSize: CGSize(width: 466, height: 678), safeAreaInsets: openInsets,
                                              aspectRatio: 4.0 / 3.0, topAlignedInPortrait: true, reserveTouchSpace: true,
                                              stage: PlayerStageContext(prefersSplitPad: true))
        XCTAssertFalse(layout.isPadMode)
    }

    func testFlatDisplayKeepsSingleScreenGamesWhole() {
        let layout = resolvePlayerStageLayout(containerSize: openSize, safeAreaInsets: openInsets, aspectRatio: 4.0 / 3.0,
                                              topAlignedInPortrait: true, reserveTouchSpace: true,
                                              stage: PlayerStageContext(fold: flatFold))
        XCTAssertFalse(layout.isPadMode)
        XCTAssertEqual(layout.screens.count, 1)
    }

    func testNearSquareRotatedPoseChoosesTheLargerGame() {
        let size = CGSize(width: 669, height: 703)
        let insets = PlayerViewportSafeAreaInsets(top: 20, bottom: 20, left: 0, right: 0)
        let chosen = resolvePlayerStageLayout(containerSize: size, safeAreaInsets: insets, aspectRatio: 4.0 / 3.0,
                                              topAlignedInPortrait: true, reserveTouchSpace: true)
        for portrait in [true, false] {
            let deck = PlayerControlDeckLayout(bounds: CGRect(origin: .zero, size: size), insets: insets, deckHeight: 200,
                                               aspectRatio: 4.0 / 3.0, portrait: portrait)
            let width = min(deck.gameArea.width, deck.gameArea.height * 4 / 3)
            XCTAssertGreaterThanOrEqual(chosen.viewport.frame.width + 0.5, width)
        }
    }

    func testSidePaneShrinksTheGameToTheLeadingArea() {
        let layout = resolvePlayerStageLayout(containerSize: openSize, safeAreaInsets: openInsets, aspectRatio: 4.0 / 3.0,
                                              topAlignedInPortrait: true, reserveTouchSpace: true,
                                              stage: PlayerStageContext(trailingPaneWidth: 401))
        XCTAssertLessThanOrEqual(layout.viewport.frame.maxX, 402)
        XCTAssertLessThanOrEqual(layout.deck.rightOrigin + layout.deck.railWidth, 402)
    }

    func testClosedOuterDisplayMatchesTheLegacyPhoneLayout() {
        // Without fold or dual-screen facts, layouts match the pre-Duo behavior.
        let size = CGSize(width: 466, height: 678)
        let insets = PlayerViewportSafeAreaInsets(top: 0, bottom: 20, left: 0, right: 54)
        let stage = resolvePlayerStageLayout(containerSize: size, safeAreaInsets: insets, aspectRatio: 4.0 / 3.0,
                                             topAlignedInPortrait: true, reserveTouchSpace: true)
        let legacyDeck = PlayerControlDeckLayout(size: size, insets: insets, deckHeight: 200, aspectRatio: 4.0 / 3.0)
        XCTAssertEqual(stage.deck.gameArea, legacyDeck.gameArea)
        XCTAssertTrue(stage.viewport.isPortrait)
    }
}
