import CoreGraphics
import XCTest
@testable import RommioPlayerKit

/// iPhone Duo geometries from the Xcode 27.1 simulator (real, not compatibility
/// mode): closed 466×678, open landscape 951×669, open portrait 669×951. The
/// rules match the design studio's `stage()` (design/studio/core/kit.js):
/// controls never overlap a game screen, clusters sit low, and the game is never
/// on one half with the controls on the other.
final class PlayerDuoStageLayoutTests: XCTestCase {
    private struct Pose {
        let name: String
        let size: CGSize
        let insets: PlayerViewportSafeAreaInsets
        var fold: CGRect? = nil
        var foldIsActive = false
    }

    private let closedPortrait = Pose(name: "closed portrait", size: CGSize(width: 466, height: 678),
                                      insets: PlayerViewportSafeAreaInsets(top: 16, bottom: 20, left: 0, right: 0))
    private let closedLandscape = Pose(name: "closed landscape", size: CGSize(width: 678, height: 466),
                                       insets: PlayerViewportSafeAreaInsets(top: 0, bottom: 16, left: 20, right: 20))
    private let openLandscape = Pose(name: "open landscape", size: CGSize(width: 951, height: 669),
                                     insets: PlayerViewportSafeAreaInsets(top: 16, bottom: 20, left: 0, right: 0))
    private let openPortrait = Pose(name: "open portrait", size: CGSize(width: 669, height: 951),
                                    insets: PlayerViewportSafeAreaInsets(top: 16, bottom: 20, left: 0, right: 0))
    private var poses: [Pose] {
        [closedPortrait, closedLandscape, openLandscape, openPortrait,
         // A flat display reports its hinge as an inactive, zero-width region.
         Pose(name: "open landscape, flat hinge", size: openLandscape.size, insets: openLandscape.insets,
              fold: CGRect(x: 475.5, y: 0, width: 0, height: 669)),
         Pose(name: "book", size: openLandscape.size, insets: openLandscape.insets,
              fold: CGRect(x: 465.5, y: 0, width: 20, height: 669), foldIsActive: true),
         Pose(name: "laptop", size: openPortrait.size, insets: openPortrait.insets,
              fold: CGRect(x: 0, y: 465.5, width: 669, height: 20), foldIsActive: true)]
    }

    private func layout(_ pose: Pose, aspect: Double? = 4.0 / 3.0, deckHeight: Double = 200, scale: Double = 1,
                        utilityCount: Int = 3, dual: Bool = false, threeDS: Bool = false, controls: Bool = true,
                        pane: CGFloat = 0) -> PlayerStageLayout {
        resolvePlayerStageLayout(
            containerSize: pose.size, safeAreaInsets: pose.insets,
            aspectRatio: dual ? (threeDS ? 5.0 / 6.0 : 2.0 / 3.0) : aspect,
            topAlignedInPortrait: true, controlDeckHeight: deckHeight, controlScale: scale,
            reserveTouchSpace: controls, utilityCount: utilityCount,
            stage: PlayerStageContext(fold: pose.fold, foldIsActive: pose.foldIsActive, dualScreen: dual, threeDS: threeDS,
                                      isPhoneFormFactor: true, trailingPaneWidth: pane))
    }

    /// The two rail boxes and every utility target.
    private func controlRects(_ layout: PlayerStageLayout, deckHeight: Double, utilityCount: Int) -> [CGRect] {
        let deck = layout.deck
        let railHeight = CGFloat(deckHeight) * deck.scale
        var rects = [CGRect(x: deck.leftOrigin, y: deck.railTop, width: deck.railWidth, height: railHeight),
                     CGRect(x: deck.rightOrigin, y: deck.railTop, width: deck.railWidth, height: railHeight)]
        for index in 0..<utilityCount {
            let x = deck.utilityX(index: index, count: utilityCount, width: 0)
            rects.append(CGRect(x: x - 24, y: deck.utilityY - 24, width: 48, height: 48))
        }
        return rects
    }

    func testControlsNeverOverlapAGameScreenInAnyDuoPose() {
        for pose in poses {
            for dual in [false, true] {
                for threeDS in dual ? [false, true] : [false] {
                    for deckHeight in [200.0, 300.0] {
                        for scale in [0.92, 1.0, 1.3] {
                            for utilityCount in [1, 3, 6] {
                                let stage = layout(pose, deckHeight: deckHeight, scale: scale, utilityCount: utilityCount, dual: dual, threeDS: threeDS)
                                let label = "\(pose.name) dual=\(dual) 3ds=\(threeDS) deck=\(deckHeight) scale=\(scale) utilities=\(utilityCount)"
                                let bounds = CGRect(origin: .zero, size: pose.size).insetBy(dx: -0.5, dy: -0.5)
                                let controls = controlRects(stage, deckHeight: deckHeight, utilityCount: utilityCount)
                                XCTAssertEqual(stage.screens.count, dual ? 2 : 1, label)
                                for screen in stage.screens {
                                    XCTAssertTrue(bounds.contains(screen.frame), "Screen off the display: \(label)")
                                    XCTAssertGreaterThan(screen.frame.width, 100, "Screen too small: \(label)")
                                    for control in controls {
                                        XCTAssertFalse(screen.frame.insetBy(dx: 0.5, dy: 0.5).intersects(control), "Control over a screen: \(label) \(control) \(screen.frame)")
                                    }
                                }
                                for (index, control) in controls.enumerated() {
                                    XCTAssertTrue(bounds.contains(control), "Control off the display: \(label) \(control)")
                                    for other in controls.dropFirst(index + 1) {
                                        XCTAssertFalse(control.insetBy(dx: 0.5, dy: 0.5).intersects(other), "Controls overlap: \(label) \(control) \(other)")
                                    }
                                }
                                if dual {
                                    XCTAssertLessThan(stage.screens[0].frame.maxY, stage.screens[1].frame.minY, "Screens not stacked: \(label)")
                                    XCTAssertTrue(stage.screens[1].isTouchScreen)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    func testClustersSitLowWhereThumbsRest() {
        for pose in poses {
            for dual in [false, true] {
                let stage = layout(pose, dual: dual)
                let cluster = stage.deck.railTop + PlayerControlDeckLayout.clusterCenter * stage.deck.scale
                let isLandscape = pose.size.width > pose.size.height
                // Landscape targets 0.68 (closed) or 0.7 (open) of the height.
                XCTAssertGreaterThanOrEqual(cluster / pose.size.height, isLandscape && !dual ? 0.66 : 0.6, "\(pose.name) dual=\(dual)")
                // Shoulders (the top of the rail) sit just above the clusters, never on a screen.
                XCTAssertGreaterThan(stage.deck.railTop, pose.size.height * 0.4, "\(pose.name) dual=\(dual)")
            }
        }
    }

    func testOpenLandscapeCentresTheGameBetweenSideRailsWithUtilitiesUnderIt() {
        let stage = layout(openLandscape)
        let game = stage.viewport.frame
        XCTAssertFalse(stage.deck.isPortrait)
        XCTAssertEqual(game.midX, openLandscape.size.width / 2, accuracy: 1)
        XCTAssertLessThanOrEqual(stage.deck.leftOrigin + stage.deck.railWidth, game.minX)
        XCTAssertGreaterThanOrEqual(stage.deck.rightOrigin, game.maxX)
        XCTAssertEqual(stage.deck.railTop + PlayerControlDeckLayout.clusterCenter * stage.deck.scale, 669 * 0.7, accuracy: 1)
        XCTAssertTrue(stage.deck.usesUtilityRow)
        XCTAssertGreaterThan(stage.deck.utilityY - 24, game.maxY)
        XCTAssertEqual(stage.deck.utilityX(index: 1, count: 3, width: 951), game.midX, accuracy: 0.5)
    }

    func testClosedLandscapeKeepsClustersLowAndTheGameBetweenTheRails() {
        let stage = layout(closedLandscape)
        let game = stage.viewport.frame
        XCTAssertEqual(stage.deck.railTop + PlayerControlDeckLayout.clusterCenter * stage.deck.scale, 466 * 0.68, accuracy: 1)
        XCTAssertLessThanOrEqual(stage.deck.leftOrigin + stage.deck.railWidth, game.minX)
        XCTAssertGreaterThanOrEqual(stage.deck.rightOrigin, game.maxX)
    }

    func testUtilitiesMoveToTheFootOfTheRailsWhenTheGameFillsTheHeight() {
        // A tall game (Game Boy) leaves no room under it; neither does an unknown aspect.
        for aspect in [10.0 / 9.0, nil] {
            let stage = layout(openLandscape, aspect: aspect)
            XCTAssertFalse(stage.deck.usesUtilityRow)
            let menuX = stage.deck.utilityX(index: 1, count: 3, width: 951)
            XCTAssertLessThan(menuX + 24, stage.viewport.frame.minX)
        }
    }

    func testBookPoseNoLongerSplitsGameAndControls() {
        let fold = CGRect(x: 465.5, y: 0, width: 20, height: 669)
        let stage = layout(Pose(name: "book", size: openLandscape.size, insets: openLandscape.insets, fold: fold, foldIsActive: true))
        let game = stage.viewport.frame
        // The game spans both halves; the controls sit on both outer edges.
        XCTAssertLessThan(game.minX, fold.minX)
        XCTAssertGreaterThan(game.maxX, fold.maxX)
        XCTAssertLessThan(stage.deck.leftOrigin, fold.minX)
        XCTAssertGreaterThan(stage.deck.rightOrigin, fold.maxX)
    }

    func testOpenPortraitPutsTheGameFullWidthOnTopWithControlsBelow() {
        let stage = layout(openPortrait)
        let game = stage.viewport.frame
        XCTAssertTrue(stage.deck.isPortrait)
        XCTAssertEqual(game.width, 669 - 28, accuracy: 0.5)
        XCTAssertLessThan(game.minY, 40)
        // A flat fold may run through the game.
        XCTAssertGreaterThan(game.maxY, 951 / 2)
        XCTAssertGreaterThan(stage.deck.railTop, game.maxY)
        XCTAssertGreaterThan(stage.deck.utilityY, stage.deck.railTop + 200 * stage.deck.scale)
        // Clusters about a quarter in from each side.
        XCTAssertEqual(stage.deck.leftOrigin + stage.deck.railWidth / 2, 669 * 0.26, accuracy: 1)
        XCTAssertEqual(stage.deck.rightOrigin + stage.deck.railWidth / 2, 669 * 0.74, accuracy: 1)
    }

    func testLaptopPoseKeepsTheGameAboveTheBend() {
        let fold = CGRect(x: 0, y: 465.5, width: 669, height: 20)
        let stage = layout(Pose(name: "laptop", size: openPortrait.size, insets: openPortrait.insets, fold: fold, foldIsActive: true))
        XCTAssertLessThanOrEqual(stage.viewport.frame.maxY, fold.minY)
        XCTAssertGreaterThanOrEqual(stage.deck.railTop, fold.maxY)
    }

    func testClosedPortraitLeavesRoomForTheCamera() {
        let stage = layout(closedPortrait)
        XCTAssertGreaterThanOrEqual(stage.viewport.frame.minY, 16 + 10 + 10)
        XCTAssertLessThan(stage.viewport.frame.maxY, stage.deck.railTop)
    }

    func testUnfoldedPortraitStacksDSScreensAcrossTheHinge() {
        // Beta 1 reports no hinge, so the midline stands in for it.
        let stage = layout(openPortrait, dual: true)
        let (top, touch) = (stage.screens[0], stage.screens[1])
        XCTAssertEqual(top.source, CGRect(x: 0, y: 0, width: 1, height: 0.5))
        XCTAssertTrue(touch.isTouchScreen)
        XCTAssertLessThanOrEqual(top.frame.maxY, 951 / 2)
        XCTAssertGreaterThanOrEqual(touch.frame.minY, 951 / 2)
        XCTAssertGreaterThan(top.frame.width, touch.frame.width)
        XCTAssertLessThanOrEqual(top.frame.width, touch.frame.width * 1.3 + 0.01)
        XCTAssertEqual(top.frame.width / top.frame.height, 4.0 / 3.0, accuracy: 0.01)
        // A control band at the bottom: clusters in the corners beside the touch screen.
        XCTAssertTrue(stage.deck.isPortrait)
        XCTAssertGreaterThanOrEqual(stage.deck.railTop, 951 / 2)
        XCTAssertLessThanOrEqual(stage.deck.leftOrigin + stage.deck.railWidth, touch.frame.minX)
        XCTAssertGreaterThanOrEqual(stage.deck.rightOrigin, touch.frame.maxX)
        XCTAssertGreaterThan(stage.deck.utilityY - 24, touch.frame.maxY)
    }

    func testReportedHorizontalHingeSplitsTheScreens() {
        let fold = CGRect(x: 0, y: 465.5, width: 669, height: 20)
        let stage = layout(Pose(name: "hinge", size: openPortrait.size, insets: openPortrait.insets, fold: fold, foldIsActive: true), dual: true)
        XCTAssertLessThanOrEqual(stage.screens[0].frame.maxY, fold.minY)
        XCTAssertGreaterThanOrEqual(stage.screens[1].frame.minY, fold.maxY)
    }

    func testDSStaysStackedWithControlsInTheBottomCornersWhenOpenInLandscape() {
        let stage = layout(Pose(name: "flat", size: openLandscape.size, insets: openLandscape.insets,
                                fold: CGRect(x: 475.5, y: 0, width: 0, height: 669)), dual: true)
        let (top, touch) = (stage.screens[0], stage.screens[1])
        XCTAssertEqual(top.frame.midX, touch.frame.midX, accuracy: 0.5)
        XCTAssertLessThan(top.frame.maxY, touch.frame.minY)
        XCTAssertGreaterThan(stage.deck.railTop, 669 / 2)
    }

    func testClosedDSStacksScreensAboveTheControls() {
        let stage = layout(closedPortrait, dual: true)
        XCTAssertLessThan(stage.screens[0].frame.maxY, stage.screens[1].frame.minY)
        XCTAssertLessThan(stage.screens[1].frame.maxY, stage.deck.railTop)
    }

    func testOpenDuoThreeDSEnlargesTheUpperScreenWithoutCrossingTheCrease() {
        let stage = layout(openPortrait, dual: true, threeDS: true)
        let (top, touch) = (stage.screens[0], stage.screens[1])
        XCTAssertEqual(touch.source, CGRect(x: 0.1, y: 0.5, width: 0.8, height: 0.5))
        XCTAssertEqual(top.frame.width / top.frame.height, 5.0 / 3.0, accuracy: 0.01)
        XCTAssertEqual(touch.frame.width / touch.frame.height, 4.0 / 3.0, accuracy: 0.01)
        XCTAssertLessThanOrEqual(top.frame.maxY, 951 / 2)
        XCTAssertGreaterThanOrEqual(touch.frame.minY, 951 / 2)
        XCTAssertGreaterThan(top.frame.width, touch.frame.width * 1.25)
        XCTAssertLessThanOrEqual(top.frame.width, touch.frame.width * 1.5 + 0.01)
        XCTAssertLessThanOrEqual(stage.deck.leftOrigin + stage.deck.railWidth, touch.frame.minX)
        XCTAssertGreaterThanOrEqual(stage.deck.rightOrigin, touch.frame.maxX)
    }

    func testSidePaneKeepsTheGameAndControlsInTheLeadingArea() {
        let stage = layout(openLandscape, pane: 380)
        let leading = 951 - 380.0
        XCTAssertLessThanOrEqual(stage.viewport.frame.maxX, leading)
        XCTAssertLessThanOrEqual(stage.deck.rightOrigin + stage.deck.railWidth, leading)
        for control in controlRects(stage, deckHeight: 200, utilityCount: 3) {
            XCTAssertFalse(stage.viewport.frame.intersects(control))
        }
    }

    func testHiddenControlsKeepTheStandardLayout() {
        let stage = layout(openLandscape, controls: false, pane: 0)
        let legacy = resolvePlayerStageLayout(containerSize: openLandscape.size, safeAreaInsets: openLandscape.insets,
                                              aspectRatio: 4.0 / 3.0, topAlignedInPortrait: true, reserveTouchSpace: false)
        XCTAssertEqual(stage.viewport.frame, legacy.viewport.frame)
    }

    // MARK: Devices that don't fold keep their layouts

    func testSlabIPhoneKeepsTheLegacyLayout() {
        for (size, insets) in [(CGSize(width: 440, height: 956), PlayerViewportSafeAreaInsets(top: 62, bottom: 34, left: 0, right: 0)),
                               (CGSize(width: 956, height: 440), PlayerViewportSafeAreaInsets(top: 0, bottom: 21, left: 62, right: 62))] {
            let stage = resolvePlayerStageLayout(containerSize: size, safeAreaInsets: insets, aspectRatio: 4.0 / 3.0,
                                                 topAlignedInPortrait: true, reserveTouchSpace: true,
                                                 stage: PlayerStageContext(isPhoneFormFactor: true))
            let legacy = PlayerControlDeckLayout(size: size, insets: insets, deckHeight: 200, aspectRatio: 4.0 / 3.0)
            XCTAssertEqual(stage.deck.gameArea, legacy.gameArea)
            XCTAssertEqual(stage.deck.railTop, legacy.railTop)
            XCTAssertEqual(stage.deck.usesUtilityRow, legacy.isPortrait)
        }
    }

    func testWindowWithoutFoldFactsMatchesTheLegacyLayout() {
        // Without fold, foldable or phone facts (an iPad window of this size), layouts match the pre-Duo behavior.
        let size = CGSize(width: 466, height: 678)
        let insets = PlayerViewportSafeAreaInsets(top: 0, bottom: 20, left: 0, right: 54)
        let stage = resolvePlayerStageLayout(containerSize: size, safeAreaInsets: insets, aspectRatio: 4.0 / 3.0,
                                             topAlignedInPortrait: true, reserveTouchSpace: true)
        let legacyDeck = PlayerControlDeckLayout(size: size, insets: insets, deckHeight: 200, aspectRatio: 4.0 / 3.0)
        XCTAssertEqual(stage.deck.gameArea, legacyDeck.gameArea)
        XCTAssertTrue(stage.viewport.isPortrait)
    }

    func testFoldableFactsAreDetected() {
        XCTAssertTrue(PlayerStageContext(foldable: true).isFoldable(in: CGSize(width: 402, height: 874)))
        XCTAssertTrue(PlayerStageContext(fold: CGRect(x: 475, y: 0, width: 0, height: 669)).isFoldable(in: openLandscape.size))
        XCTAssertTrue(PlayerStageContext(isPhoneFormFactor: true).isFoldable(in: closedPortrait.size))
        XCTAssertFalse(PlayerStageContext(isPhoneFormFactor: true).isFoldable(in: CGSize(width: 440, height: 956)))
        XCTAssertFalse(PlayerStageContext(isPhoneFormFactor: false).isFoldable(in: CGSize(width: 820, height: 1180)))
    }

    func testPhoneDSLayoutStaysStackedWithControlsBelow() {
        let stage = resolvePlayerStageLayout(containerSize: CGSize(width: 430, height: 932), safeAreaInsets: PlayerViewportSafeAreaInsets(top: 59, bottom: 34, left: 0, right: 0),
                                             aspectRatio: 2.0 / 3.0, topAlignedInPortrait: true, reserveTouchSpace: true,
                                             stage: PlayerStageContext(dualScreen: true))
        XCTAssertTrue(stage.deck.isPortrait)
        XCTAssertLessThan(stage.screens[0].frame.maxY, stage.screens[1].frame.minY)
    }

    func testThreeDSScreensKeepNativeAspectsAndCenteredTouchSource() {
        let stage = resolvePlayerStageLayout(
            containerSize: CGSize(width: 430, height: 932),
            safeAreaInsets: PlayerViewportSafeAreaInsets(top: 59, bottom: 34, left: 0, right: 0),
            aspectRatio: 5.0 / 6.0, topAlignedInPortrait: true,
            reserveTouchSpace: true,
            stage: PlayerStageContext(dualScreen: true, threeDS: true)
        )
        XCTAssertEqual(stage.screens.count, 2)
        XCTAssertEqual(stage.screens[0].frame.width / stage.screens[0].frame.height, 5.0 / 3.0, accuracy: 0.01)
        XCTAssertEqual(stage.screens[1].frame.width / stage.screens[1].frame.height, 4.0 / 3.0, accuracy: 0.01)
        XCTAssertEqual(stage.screens[1].source, CGRect(x: 0.1, y: 0.5, width: 0.8, height: 0.5))
        XCTAssertTrue(stage.screens[1].isTouchScreen)
        XCTAssertLessThan(stage.screens[0].frame.maxY, stage.screens[1].frame.minY)
    }

    func testIPadThreeDSScreensStayTogetherWithoutAnInventedHinge() {
        let stage = resolvePlayerStageLayout(
            containerSize: CGSize(width: 820, height: 1180),
            safeAreaInsets: PlayerViewportSafeAreaInsets(top: 24, bottom: 20, left: 0, right: 0),
            aspectRatio: 5.0 / 6.0, topAlignedInPortrait: true,
            reserveTouchSpace: true,
            stage: PlayerStageContext(dualScreen: true, threeDS: true, isPhoneFormFactor: false)
        )
        XCTAssertEqual(stage.screens.count, 2)
        XCTAssertEqual(stage.screens[1].frame.minY - stage.screens[0].frame.maxY, 12, accuracy: 0.5)
    }

    func testNearSquareWindowChoosesTheLargerGame() {
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
}
