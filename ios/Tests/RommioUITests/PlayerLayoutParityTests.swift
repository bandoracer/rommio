import CoreGraphics
import XCTest
@testable import RommioFoundation
@testable import RommioUI
@testable import RommioPlayerKit

@MainActor
final class PlayerLayoutParityTests: XCTestCase {
    func testPortraitTouchFamilyUsesTopViewportAndCenteredSystemRow() async throws {
        let controls = try await controlsState(platformSlug: "arcade")
        let snapshot = resolvePlayerOverlaySnapshot(
            containerSize: CGSize(width: 430, height: 932),
            safeAreaInsets: PlayerViewportSafeAreaInsets(top: 59, bottom: 34, left: 0, right: 0),
            controlsState: controls
        )

        XCTAssertTrue(snapshot.isPortrait)
        XCTAssertEqual(snapshot.primaryControlsPlacement, .bottomBand)
        XCTAssertEqual(snapshot.menuPlacement, .utilityRow)
        XCTAssertEqual(snapshot.systemPlacement, .utilityRow)
        XCTAssertTrue(snapshot.showsGameplayTouchControls)
        XCTAssertFalse(snapshot.showsPointerOverlay)
        XCTAssertLessThan(snapshot.viewportFrame.minY, 140)
        XCTAssertLessThan(snapshot.viewportFrame.maxY, 640)
        XCTAssertNotNil(snapshot.menuCenter)
        XCTAssertGreaterThan(snapshot.menuCenter?.y ?? 0, snapshot.viewportFrame.maxY + 22)
        XCTAssertGreaterThan(snapshot.menuCenter?.x ?? 0, snapshot.viewportFrame.minX)
        XCTAssertEqual(snapshot.menuCenter?.x ?? 0, snapshot.viewportFrame.midX, accuracy: 1)
        XCTAssertNotNil(snapshot.systemFrame)
        XCTAssertEqual(snapshot.systemFrame?.midX ?? 0, snapshot.viewportFrame.midX, accuracy: 1)
    }

    func testLandscapeTouchFamilyUsesSideRailsAndLowerEdgeMenu() async throws {
        let controls = try await controlsState(platformSlug: "snes")
        let snapshot = resolvePlayerOverlaySnapshot(
            containerSize: CGSize(width: 932, height: 430),
            safeAreaInsets: PlayerViewportSafeAreaInsets(top: 0, bottom: 21, left: 59, right: 59),
            controlsState: controls
        )

        XCTAssertFalse(snapshot.isPortrait)
        XCTAssertEqual(snapshot.primaryControlsPlacement, .sideRails)
        XCTAssertEqual(snapshot.menuPlacement, .landscapeLowerLeading)
        XCTAssertEqual(snapshot.systemPlacement, .sideRails)
        XCTAssertTrue(snapshot.showsGameplayTouchControls)
        XCTAssertEqual(snapshot.viewportFrame.midX, 466, accuracy: 1)
        XCTAssertLessThan(snapshot.menuCenter!.x + 24, snapshot.viewportFrame.minX)
        XCTAssertNotNil(snapshot.leftRailInset)
        XCTAssertNotNil(snapshot.rightRailInset)
        XCTAssertGreaterThanOrEqual(snapshot.leftRailInset ?? 0, 59)
        XCTAssertGreaterThanOrEqual(snapshot.rightRailInset ?? 0, 59 - 0.001)
        XCTAssertNotNil(snapshot.menuCenter)
        XCTAssertGreaterThan(snapshot.menuCenter?.y ?? 0, 360)
        XCTAssertNotNil(snapshot.systemFrame)
        XCTAssertGreaterThan(snapshot.systemFrame?.maxY ?? 0, 360)
    }

    func testControllerFirstFamilyHidesGameplayControlsButKeepsMenuPath() async throws {
        let controls = try await controlsState(platformSlug: "dreamcast")
        let snapshot = resolvePlayerOverlaySnapshot(
            containerSize: CGSize(width: 932, height: 430),
            safeAreaInsets: PlayerViewportSafeAreaInsets(top: 0, bottom: 21, left: 59, right: 59),
            controlsState: controls
        )

        XCTAssertFalse(snapshot.isPortrait)
        XCTAssertEqual(snapshot.primaryControlsPlacement, .hiddenControllerFirst)
        XCTAssertEqual(snapshot.menuPlacement, .landscapeLowerLeading)
        XCTAssertEqual(snapshot.systemPlacement, .none)
        XCTAssertFalse(snapshot.showsGameplayTouchControls)
        XCTAssertFalse(snapshot.showsPointerOverlay)
        XCTAssertNotNil(snapshot.menuCenter)
        XCTAssertGreaterThan(snapshot.menuCenter?.y ?? 0, 360)
        XCTAssertNil(snapshot.leftRailInset)
        XCTAssertNil(snapshot.rightRailInset)
    }

    func testDSPortraitLayoutKeepsPointerOverlay() async throws {
        let controls = try await controlsState(platformSlug: "nds")
        let snapshot = resolvePlayerOverlaySnapshot(
            containerSize: CGSize(width: 430, height: 932),
            safeAreaInsets: PlayerViewportSafeAreaInsets(top: 59, bottom: 34, left: 0, right: 0),
            controlsState: controls
        )

        XCTAssertEqual(controls.platformProfile.playerOrientationPolicy, .portraitOnly)
        XCTAssertTrue(snapshot.isPortrait)
        XCTAssertEqual(snapshot.menuPlacement, .utilityRow)
        XCTAssertTrue(snapshot.showsGameplayTouchControls)
        XCTAssertTrue(snapshot.showsPointerOverlay)
        XCTAssertGreaterThan(snapshot.menuCenter?.y ?? 0, snapshot.viewportFrame.maxY + 22)
    }

    func testThreeDSUsesTouchControlsAndPointerOverlay() async throws {
        let controls = try await controlsState(platformSlug: "3ds")
        let snapshot = resolvePlayerOverlaySnapshot(
            containerSize: CGSize(width: 430, height: 932),
            safeAreaInsets: PlayerViewportSafeAreaInsets(top: 59, bottom: 34, left: 0, right: 0),
            controlsState: controls,
            stage: PlayerStageContext(dualScreen: true, threeDS: true)
        )
        XCTAssertTrue(snapshot.showsGameplayTouchControls)
        XCTAssertTrue(snapshot.showsPointerOverlay)
        XCTAssertNotNil(snapshot.touchScreenFrame)
    }

    func testEveryTouchFamilyKeepsAllControlsOutsideVideoInBothOrientations() throws {
        let resolver = PlatformControlProfileResolver()
        for profile in resolver.supportedProfiles() where profile.supportTier == .touchSupported {
            let controls = try XCTUnwrap(ExpansionControls.controls(for: profile.familyID), profile.familyID)
            for size in [CGSize(width: 420, height: 912), CGSize(width: 912, height: 420), CGSize(width: 375, height: 667), CGSize(width: 667, height: 375)] {
                let portrait = size.height > size.width
                let insets = PlayerViewportSafeAreaInsets(top: portrait ? 62 : 0, bottom: 21, left: portrait ? 0 : 62, right: portrait ? 0 : 62)
                for scale in [0.92, 1.0, 1.3] {
                    let deck = PlayerControlDeckLayout(size: size, insets: insets, deckHeight: ExpansionControls.deckHeight(controls), scale: scale, aspectRatio: profile.preferredViewportAspectRatio, utilityCount: controls.filter(\.center).count + 1)
                    let viewport = resolvePlayerViewportLayout(containerSize: size, safeAreaInsets: insets, aspectRatio: profile.preferredViewportAspectRatio,
                        topAlignedInPortrait: true, controlDeckHeight: ExpansionControls.deckHeight(controls), controlScale: scale, utilityCount: controls.filter(\.center).count + 1).frame
                    for mirror in [false, true] {
                        var targets: [CGRect] = []
                        let utility = controls.filter(\.center)
                        let menuIndex = utility.count / 2
                        for control in controls {
                            let index = utility.firstIndex { $0.id == control.id } ?? 0
                            let x = control.center ? deck.utilityX(index: index < menuIndex ? index : index + 1, count: utility.count + 1, width: size.width) : (control.left != mirror ? deck.leftOrigin : deck.rightOrigin) + control.x * deck.scale
                            let y = control.center ? deck.utilityY : deck.railTop + control.y * deck.scale
                            let diameter = control.center ? 48 : control.size * deck.scale
                            targets.append(CGRect(x: x - diameter / 2, y: y - diameter / 2, width: diameter, height: diameter))
                        }
                        targets.append(CGRect(x: deck.utilityX(index: menuIndex, count: utility.count + 1, width: size.width) - 24, y: deck.utilityY - 22, width: 48, height: 44))
                        for (i, target) in targets.enumerated() {
                            XCTAssertTrue(CGRect(origin: .zero, size: size).contains(target), "Clipped \(profile.familyID) \(size) \(scale)")
                            XCTAssertGreaterThanOrEqual(target.width, 44, "Small target \(profile.familyID)")
                            XCTAssertFalse(viewport.intersects(target), "Game obscured \(profile.familyID)")
                            for other in targets.dropFirst(i + 1) { XCTAssertFalse(target.insetBy(dx: 0.1, dy: 0.1).intersects(other.insetBy(dx: 0.1, dy: 0.1)), "Overlapping targets \(profile.familyID)") }
                        }
                    }
                }
            }
        }
    }

    /// iPhone Duo closed and open, landscape and portrait (and the book and
    /// laptop poses), single and dual screen: every control target, laid out
    /// exactly as `ExpansionControlsOverlay` places it, stays off every game
    /// screen and off the other targets, and the clusters sit in the lower part.
    func testDuoControlsNeverOverlapAGameScreenAndClustersSitLow() throws {
        let resolver = PlatformControlProfileResolver()
        let poses: [(name: String, size: CGSize, insets: PlayerViewportSafeAreaInsets, fold: CGRect?, active: Bool)] = [
            ("closed portrait", CGSize(width: 466, height: 678), PlayerViewportSafeAreaInsets(top: 16, bottom: 20, left: 0, right: 0), nil, false),
            ("closed landscape", CGSize(width: 678, height: 466), PlayerViewportSafeAreaInsets(top: 0, bottom: 16, left: 20, right: 20), nil, false),
            ("open landscape", CGSize(width: 951, height: 669), PlayerViewportSafeAreaInsets(top: 16, bottom: 20, left: 0, right: 0), nil, false),
            ("open portrait", CGSize(width: 669, height: 951), PlayerViewportSafeAreaInsets(top: 16, bottom: 20, left: 0, right: 0), nil, false),
            ("book", CGSize(width: 951, height: 669), PlayerViewportSafeAreaInsets(top: 16, bottom: 20, left: 0, right: 0), CGRect(x: 465.5, y: 0, width: 20, height: 669), true),
            ("laptop", CGSize(width: 669, height: 951), PlayerViewportSafeAreaInsets(top: 16, bottom: 20, left: 0, right: 0), CGRect(x: 0, y: 465.5, width: 669, height: 20), true),
        ]
        var families = Set<String>()
        for profile in resolver.supportedProfiles() where profile.supportTier == .touchSupported {
            guard let controls = ExpansionControls.controls(for: profile.familyID) else { continue }
            families.insert(profile.familyID)
            let dual = ["nds", "3ds"].contains(profile.familyID)
            let hasSticks = controls.contains { $0.stick != nil }
            let utility = controls.filter(\.center)
            let menuIndex = utility.count / 2
            for pose in poses {
                let stage = PlayerStageContext(fold: pose.fold, foldIsActive: pose.active, dualScreen: dual,
                                               threeDS: profile.familyID == "3ds", isPhoneFormFactor: true)
                for scale in [0.92, 1.0, 1.3] {
                    let layout = resolvePlayerStageLayout(
                        containerSize: pose.size, safeAreaInsets: pose.insets, aspectRatio: profile.preferredViewportAspectRatio,
                        topAlignedInPortrait: true, controlDeckHeight: ExpansionControls.deckHeight(controls), controlScale: scale,
                        reserveTouchSpace: true, utilityCount: utility.count + 1, stage: stage)
                    let deck = layout.deck
                    let label = "\(profile.familyID) \(pose.name) scale=\(scale)"
                    XCTAssertEqual(layout.screens.count, dual ? 2 : 1, label)
                    for mirror in [false, true] {
                        var targets: [CGRect] = []
                        if controls.contains(where: { [4, 5, 6, 7].contains($0.key ?? -1) }) {
                            let dpadSize = 144 * deck.scale
                            let center = CGPoint(x: (mirror ? deck.rightOrigin : deck.leftOrigin) + 74 * deck.scale,
                                                 y: deck.railTop + 122 * deck.scale)
                            targets.append(CGRect(x: center.x - dpadSize / 2, y: center.y - dpadSize / 2, width: dpadSize, height: dpadSize))
                            if hasSticks {
                                // Analog sticks sit under the clusters, so the whole rail
                                // rests on the bottom of the display instead.
                                let railBottom = deck.railTop + CGFloat(ExpansionControls.deckHeight(controls)) * deck.scale
                                XCTAssertGreaterThanOrEqual(railBottom, pose.size.height * 0.8, "Rail not low: \(label)")
                            } else {
                                // The D-pad sits in the lower part of the display.
                                XCTAssertGreaterThanOrEqual(center.y, pose.size.height * 0.5, "Cluster too high: \(label)")
                                if pose.size.width > pose.size.height, !dual, scale <= 1 {
                                    XCTAssertGreaterThanOrEqual(center.y, pose.size.height * 0.64, "Cluster not low: \(label)")
                                }
                            }
                        }
                        for control in controls where ![4, 5, 6, 7].contains(control.key ?? -1) {
                            let index = utility.firstIndex { $0.id == control.id } ?? 0
                            let x = control.center
                                ? deck.utilityX(index: index < menuIndex ? index : index + 1, count: utility.count + 1, width: pose.size.width)
                                : (control.left != mirror ? deck.leftOrigin : deck.rightOrigin) + control.x * deck.scale
                            let y = control.center ? deck.utilityY : deck.railTop + control.y * deck.scale
                            let diameter = control.center ? 48 : control.size * deck.scale
                            targets.append(CGRect(x: x - diameter / 2, y: y - diameter / 2, width: diameter, height: diameter))
                        }
                        targets.append(CGRect(x: deck.utilityX(index: menuIndex, count: utility.count + 1, width: pose.size.width) - 24,
                                              y: deck.utilityY - 22, width: 48, height: 44))
                        let display = CGRect(origin: .zero, size: pose.size).insetBy(dx: -0.5, dy: -0.5)
                        for (i, target) in targets.enumerated() {
                            XCTAssertTrue(display.contains(target), "Clipped \(label) \(target)")
                            XCTAssertGreaterThanOrEqual(target.width, 44, "Small target \(label)")
                            for screen in layout.screens {
                                XCTAssertFalse(screen.frame.insetBy(dx: 0.5, dy: 0.5).intersects(target), "Game obscured \(label) \(target) \(screen.frame)")
                            }
                            for other in targets.dropFirst(i + 1) {
                                XCTAssertFalse(target.insetBy(dx: 0.1, dy: 0.1).intersects(other.insetBy(dx: 0.1, dy: 0.1)), "Overlapping targets \(label)")
                            }
                        }
                    }
                }
            }
        }
        XCTAssertTrue(families.isSuperset(of: ["snes", "gba", "nds", "3ds", "psx", "n64"]), "\(families)")
    }

    func testDuoLandscapePutsSelectMenuStartUnderTheGame() async throws {
        let controls = try await controlsState(platformSlug: "snes")
        let snapshot = resolvePlayerOverlaySnapshot(
            containerSize: CGSize(width: 951, height: 669),
            safeAreaInsets: PlayerViewportSafeAreaInsets(top: 16, bottom: 20, left: 0, right: 0),
            controlsState: controls,
            stage: PlayerStageContext(isPhoneFormFactor: true)
        )
        XCTAssertEqual(snapshot.primaryControlsPlacement, .sideRails)
        XCTAssertEqual(snapshot.menuPlacement, .utilityRow)
        XCTAssertEqual(snapshot.systemPlacement, .utilityRow)
        XCTAssertEqual(snapshot.menuCenter?.x ?? 0, snapshot.viewportFrame.midX, accuracy: 1)
        XCTAssertGreaterThan((snapshot.menuCenter?.y ?? 0) - 22, snapshot.viewportFrame.maxY)
    }

    func testLandscapeKeepsNaturalVideoSizeWhenSideControlsFit() {
        // A wide landscape display and a narrow handheld on iPhone Air both
        // have enough existing pillarboxing. Neither should lose video height.
        for (size, insets, aspect) in [
            (CGSize(width: 1000, height: 420), PlayerViewportSafeAreaInsets(top: 0, bottom: 21, left: 0, right: 0), 4.0 / 3.0),
            (CGSize(width: 912, height: 420), PlayerViewportSafeAreaInsets(top: 0, bottom: 21, left: 62, right: 62), 10.0 / 9.0)
        ] {
            let expectedHeight = size.height - insets.top - insets.bottom
            let frame = resolvePlayerViewportLayout(containerSize: size, safeAreaInsets: insets, aspectRatio: aspect,
                topAlignedInPortrait: true).frame
            XCTAssertEqual(frame.height, expectedHeight, accuracy: 0.01)
            XCTAssertEqual(frame.width, expectedHeight * aspect, accuracy: 0.01)
            XCTAssertEqual(frame.midX, size.width / 2, accuracy: 0.01)
        }
    }

    func testLandscapeOnlyReducesVideoToRequiredControlWidth() {
        let size = CGSize(width: 912, height: 420)
        let insets = PlayerViewportSafeAreaInsets(top: 0, bottom: 21, left: 62, right: 62)
        let frame = resolvePlayerViewportLayout(containerSize: size, safeAreaInsets: insets, aspectRatio: 16.0 / 9.0,
            topAlignedInPortrait: true, controlDeckHeight: 300).frame
        // Minimum comfortable controls: 148 * .92 wide, 4pt gap on each side.
        XCTAssertEqual(frame.width, 912 - 124 - 2 * (148 * 0.92 + 4), accuracy: 0.01)
        XCTAssertEqual(frame.width / frame.height, 16.0 / 9.0, accuracy: 0.001)
        XCTAssertEqual(frame.midY, (420 - 21) / 2.0, accuracy: 0.01)
        let hidden = resolvePlayerViewportLayout(containerSize: size, safeAreaInsets: insets, aspectRatio: 4.0 / 3.0,
            topAlignedInPortrait: true, reserveTouchSpace: false, utilityCount: 1).frame
        XCTAssertEqual(hidden.height, 399, accuracy: 0.01)
    }

    private func controlsState(platformSlug: String) async throws -> PlayerControlsState {
        let root = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        let database = try AppDatabase(rootDirectory: root.appending(path: "Database"))
        let repository = PlayerControlsRepository(
            database: database,
            controllerMonitor: StaticControllerMonitor()
        )
        return try await repository.controls(platformSlug: platformSlug)
    }
}

private struct StaticControllerMonitor: ControllerMonitoring, @unchecked Sendable {
    func currentControllers() -> [ConnectedController] { [] }
    func updates() -> AsyncStream<[ConnectedController]> {
        AsyncStream { continuation in
            continuation.yield([])
            continuation.finish()
        }
    }
}
