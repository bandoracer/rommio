import CoreGraphics
import XCTest
import RommioFoundation
@testable import RommioPlayerKit

/// Touch controls v2 (docs/player-controls.md): the family plans, the phone geometry,
/// and the touch model (hit regions, chord, rolling, hysteresis, dead zone, diagonals,
/// mirrored layouts, ordered release).
final class PlayerTouchModelTests: XCTestCase {
    // iPhone 17 Pro, iPhone SE and a Pro Max, portrait and landscape.
    private struct Phone {
        let name: String
        let size: CGSize
        let insets: PlayerViewportSafeAreaInsets
    }

    private let proPortrait = Phone(name: "17 Pro portrait", size: CGSize(width: 402, height: 874),
                                    insets: PlayerViewportSafeAreaInsets(top: 62, bottom: 34, left: 0, right: 0))
    private let proLandscape = Phone(name: "17 Pro landscape", size: CGSize(width: 874, height: 402),
                                     insets: PlayerViewportSafeAreaInsets(top: 0, bottom: 21, left: 62, right: 62))
    private var phones: [Phone] {
        [proPortrait, proLandscape,
         Phone(name: "SE portrait", size: CGSize(width: 375, height: 667), insets: PlayerViewportSafeAreaInsets(top: 20, bottom: 0, left: 0, right: 0)),
         Phone(name: "SE landscape", size: CGSize(width: 667, height: 375), insets: PlayerViewportSafeAreaInsets(top: 0, bottom: 0, left: 0, right: 0)),
         Phone(name: "Pro Max portrait", size: CGSize(width: 440, height: 956), insets: PlayerViewportSafeAreaInsets(top: 62, bottom: 34, left: 0, right: 0)),
         Phone(name: "Pro Max landscape", size: CGSize(width: 956, height: 440), insets: PlayerViewportSafeAreaInsets(top: 0, bottom: 21, left: 62, right: 62)),
         Phone(name: "iPad portrait", size: CGSize(width: 820, height: 1180), insets: PlayerViewportSafeAreaInsets(top: 24, bottom: 20, left: 0, right: 0)),
         Phone(name: "iPad landscape", size: CGSize(width: 1180, height: 820), insets: PlayerViewportSafeAreaInsets(top: 24, bottom: 20, left: 0, right: 0))]
    }

    private func plan(_ family: String, analog: Bool = false) -> PlayerControlPlan { PlayerControlPlan(family: family, analogControls: analog)! }

    private func stage(_ family: String, _ phone: Phone, scale: Double = 1, mirrored: Bool = false, analog: Bool = false) -> PlayerStageLayout {
        let plan = plan(family, analog: analog)
        let aspect = PlatformControlProfileResolver().supportedProfiles().first { $0.familyID == family }?.preferredViewportAspectRatio ?? 4.0 / 3.0
        let dual = ["nds", "3ds"].contains(family)
        return resolvePlayerStageLayout(containerSize: phone.size, safeAreaInsets: phone.insets, aspectRatio: aspect, topAlignedInPortrait: true,
                                        controlScale: scale, reserveTouchSpace: true,
                                        stage: PlayerStageContext(dualScreen: dual, threeDS: family == "3ds", isPhoneFormFactor: true),
                                        controls: plan, mirrored: mirrored)
    }

    // MARK: Plans

    func testFamiliesClassifyIntoTheSpecArrangements() {
        XCTAssertEqual(plan("nes").arrangement, .twoButton)
        XCTAssertEqual(plan("gb").arrangement, .twoButton)
        XCTAssertEqual(plan("gba").arrangement, .twoButton)
        XCTAssertEqual(plan("snes").arrangement, .diamond)
        XCTAssertEqual(plan("psx").arrangement, .diamond)
        XCTAssertEqual(plan("sega16").arrangement, .sixButton)
        XCTAssertEqual(plan("n64").arrangement, .n64)
        XCTAssertEqual(plan("gba").leftShoulders.map(\.label), ["L"])
        XCTAssertEqual(plan("gba").rightShoulders.map(\.label), ["R"])
        XCTAssertEqual(plan("psx").leftShoulders.map(\.label), ["L1", "L2"])
        XCTAssertNotNil(plan("psx", analog: true).leftStick)
        XCTAssertNotNil(plan("psx", analog: true).rightStick)
        XCTAssertEqual(plan("nes").utilities.map(\.label), ["Select", "Start"])
        XCTAssertEqual(plan("nes").dpad?.keys, [4, 5, 6, 7])
    }

    func testSpecSizesAtFullSizeAndTheFloorsAtTheSmallest() {
        let full = PlayerControlMetrics(scale: 1)
        XCTAssertEqual(full.dpad, 150)
        XCTAssertEqual(full.twoButtonDiameter, 64)
        XCTAssertEqual(full.twoButtonSpacing, 76)
        XCTAssertEqual(full.diamondDiameter, 56)
        XCTAssertEqual(full.diamondRadius, 62)
        XCTAssertEqual(full.sixDiameter, 50)
        XCTAssertEqual(full.shoulder, CGSize(width: 88, height: 40))
        XCTAssertEqual(full.pill, CGSize(width: 64, height: 34))
        XCTAssertEqual(full.menu, 44)
        // Two-button: B lower-left, A upper-right, centres 76 apart on a 25° rise.
        let nes = plan("nes").faceGeometry(full)
        let b = nes.first { $0.spec.label == "B" }!, a = nes.first { $0.spec.label == "A" }!
        XCTAssertLessThan(b.offset.x, a.offset.x)
        XCTAssertGreaterThan(b.offset.y, a.offset.y)
        XCTAssertEqual(hypot(a.offset.x - b.offset.x, a.offset.y - b.offset.y), 76, accuracy: 0.01)
        XCTAssertEqual(atan2(b.offset.y - a.offset.y, a.offset.x - b.offset.x) * 180 / .pi, 25, accuracy: 0.01)
        // Diamond centres 62 pt from the cluster centre.
        for button in plan("snes").faceGeometry(full) {
            XCTAssertEqual(hypot(button.offset.x, button.offset.y), 62, accuracy: 0.01)
        }
        // The smallest setting keeps every face at 50 pt or more (Nintendo 64's C buttons,
        // 44 pt by the per-console layout, at 44); the scale range is 0.8…1.3.
        XCTAssertEqual(PlayerControlMetrics(scale: 0.5).scale, 0.8)
        XCTAssertEqual(PlayerControlMetrics(scale: 2).scale, 1.3)
        let small = PlayerControlMetrics(scale: 0.8)
        for family in ["nes", "snes", "sega16", "n64", "psx"] {
            for button in plan(family).faceGeometry(small) {
                XCTAssertGreaterThanOrEqual(button.diameter, button.spec.isAxisButton ? 44 : 50, family)
            }
        }
    }

    // MARK: Geometry

    func testPortraitPutsTheTitleStripUnderTheGameAndClustersAt55PercentOfTheBand() throws {
        let layout = stage("nes", proPortrait)
        let controls = try XCTUnwrap(layout.controls)
        let game = layout.viewport.frame
        let strip = try XCTUnwrap(controls.titleStrip)
        XCTAssertTrue(layout.viewport.isPortrait)
        XCTAssertEqual(game.minY, 62 + 8, accuracy: 0.5, "Game at the top")
        XCTAssertEqual(game.width, 402 - 24, accuracy: 0.5, "Game spans the width")
        XCTAssertEqual(strip.minY - game.maxY, 12, accuracy: 0.5, "Title strip 12 pt under the game")
        let dpad = try XCTUnwrap(controls.dpad)
        let face = controls.controls.filter { $0.kind == .face }
        let faceBox = face.reduce(CGRect.null) { $0.union($1.frame) }
        XCTAssertEqual(dpad.frame.minX, 20, accuracy: 0.5, "D-pad 20 pt from the left edge")
        XCTAssertEqual(faceBox.maxX, 402 - 20, accuracy: 0.5, "Face 20 pt from the right edge")
        let bandTop = strip.maxY + 8, bandBottom = 874 - 34.0
        XCTAssertEqual(dpad.center.y, bandTop + 0.55 * (bandBottom - bandTop), accuracy: 0.5, "Clusters at 55% of the band")
        XCTAssertEqual(faceBox.midY, dpad.center.y, accuracy: 0.5)
        // Select · Menu · Start centred at the bottom, 12 pt above the home indicator.
        XCTAssertEqual(controls.menuCenter.x, 201, accuracy: 0.5)
        XCTAssertEqual(controls.menuFrame.maxY, bandBottom - 12, accuracy: 0.5)
        let select = try XCTUnwrap(controls.control("Select")), start = try XCTUnwrap(controls.control("Start"))
        XCTAssertEqual(select.frame.size, CGSize(width: 64, height: 34))
        XCTAssertLessThan(select.frame.maxX, controls.menuFrame.minX)
        XCTAssertGreaterThan(start.frame.minX, controls.menuFrame.maxX)
        XCTAssertEqual(select.center.y, controls.menuCenter.y, accuracy: 0.5)
    }

    func testPortraitShouldersSit16PointsAboveTheirClusterAlignedToItsOuterEdge() throws {
        for family in ["gba", "snes", "psx"] {
            for mirrored in [false, true] {
                let controls = try XCTUnwrap(stage(family, proPortrait, mirrored: mirrored).controls)
                let strip = try XCTUnwrap(controls.titleStrip)
                let dpad = try XCTUnwrap(controls.dpad)
                let face = controls.controls.filter { $0.kind == .face }.reduce(CGRect.null) { $0.union($1.frame) }
                let label = "\(family) mirrored=\(mirrored)"
                for (shoulders, cluster) in [(plan(family).leftShoulders, dpad.frame), (plan(family).rightShoulders, face)] {
                    let row = try shoulders.map { try XCTUnwrap(controls.control($0.id)) }.reduce(CGRect.null) { $0.union($1.frame) }
                    XCTAssertEqual(row.maxY, cluster.minY - 16, accuracy: 0.5, "16 pt above the cluster: \(label)")
                    // Aligned to the cluster's outer edge (the screen edge side).
                    if cluster.midX < 201 {
                        XCTAssertEqual(row.minX, cluster.minX, accuracy: 0.5, "Outer edge: \(label)")
                    } else {
                        XCTAssertEqual(row.maxX, cluster.maxX, accuracy: 0.5, "Outer edge: \(label)")
                    }
                    XCTAssertGreaterThanOrEqual(row.minY, strip.maxY + 8 - 0.5, "Inside the band: \(label)")
                }
                if family == "gba" {
                    XCTAssertEqual(try XCTUnwrap(controls.control("L")).frame.size, CGSize(width: 88, height: 40))
                }
            }
        }
    }

    func testLandscapeCentresTheGameBetweenRailsWithClustersAt60Percent() throws {
        let layout = stage("snes", proLandscape)
        let controls = try XCTUnwrap(layout.controls)
        let game = layout.viewport.frame
        XCTAssertFalse(layout.viewport.isPortrait)
        XCTAssertEqual(game.midX, 437, accuracy: 0.5, "Game centred")
        let dpad = try XCTUnwrap(controls.dpad)
        let faceBox = controls.controls.filter { $0.kind == .face }.reduce(CGRect.null) { $0.union($1.frame) }
        XCTAssertEqual(dpad.center.y, 402 * 0.6, accuracy: 0.5, "D-pad at 60% of the height")
        XCTAssertEqual(faceBox.midY, 402 * 0.6, accuracy: 0.5, "Face at 60% of the height")
        XCTAssertLessThan(dpad.frame.maxX, game.minX)
        XCTAssertGreaterThan(faceBox.minX, game.maxX)
        // Centred in their rails.
        XCTAssertEqual(dpad.center.x, (66 + game.minX - 10) / 2, accuracy: 0.5)
        XCTAssertEqual(faceBox.midX, (game.maxX + 10 + 874 - 66) / 2, accuracy: 0.5)
        // Shoulders at the top of each rail; Select and Start at the foot.
        let l = try XCTUnwrap(controls.control("L")), r = try XCTUnwrap(controls.control("R"))
        XCTAssertEqual(l.frame.minY, 8, accuracy: 0.5)
        XCTAssertEqual(r.frame.minY, 8, accuracy: 0.5)
        let select = try XCTUnwrap(controls.control("Select")), start = try XCTUnwrap(controls.control("Start"))
        XCTAssertLessThan(select.frame.maxX, game.minX)
        XCTAssertGreaterThan(start.frame.minX, game.maxX)
        XCTAssertGreaterThan(select.frame.minY, dpad.frame.maxY)
        // Menu bottom centre, under the game.
        XCTAssertEqual(controls.menuCenter.x, game.midX, accuracy: 0.5)
        XCTAssertGreaterThan(controls.menuFrame.minY, game.maxY)
    }

    func testMirroringSwapsTheRailsButNotTheLabelsOrTheGame() throws {
        for phone in [proPortrait, proLandscape] {
            let normal = stage("nes", phone), mirrored = stage("nes", phone, mirrored: true)
            XCTAssertEqual(normal.viewport.frame, mirrored.viewport.frame, phone.name)
            let pad = try XCTUnwrap(mirrored.controls?.dpad), a = try XCTUnwrap(mirrored.controls?.control("A")), b = try XCTUnwrap(mirrored.controls?.control("B"))
            XCTAssertGreaterThan(pad.center.x, phone.size.width / 2, "D-pad moves right: \(phone.name)")
            XCTAssertLessThan(a.center.x, phone.size.width / 2, "Face moves left: \(phone.name)")
            // B stays lower-left of A.
            XCTAssertLessThan(b.center.x, a.center.x)
            XCTAssertGreaterThan(b.center.y, a.center.y)
        }
    }

    /// Every touch family, phone and orientation, size and hand: controls stay on the
    /// display, off the game and the title strip, and off each other; faces are 50 pt
    /// or more and every hit region at least 58 pt.
    func testEveryFamilyFitsEveryPhoneWithoutOverlap() throws {
        let families = PlatformControlProfileResolver().supportedProfiles()
            .filter { $0.supportTier == .touchSupported && !["nds", "3ds"].contains($0.familyID) && ExpansionControls.controls(for: $0.familyID) != nil }
            .map(\.familyID)
        XCTAssertTrue(Set(families).isSuperset(of: ["nes", "snes", "gba", "gb", "psx", "n64", "sega16", "psp"]), "\(families)")
        // PlayStation with and without its Analog controls.
        for (family, analog) in families.map({ ($0, false) }) + [("psx", true)] {
            for phone in phones {
                for scale in [0.8, 1.0, 1.3] {
                    for mirrored in [false, true] {
                        let layout = stage(family, phone, scale: scale, mirrored: mirrored, analog: analog)
                        let label = "\(family)\(analog ? " analog" : "") \(phone.name) scale=\(scale) mirrored=\(mirrored)"
                        let controls = try XCTUnwrap(layout.controls, label)
                        let display = CGRect(origin: .zero, size: phone.size).insetBy(dx: -0.5, dy: -0.5)
                        let game = layout.viewport.frame
                        // (Atari 2600 on an iPhone SE in landscape, six switches at the feet, is the tightest.)
                        XCTAssertGreaterThan(game.width, phone.size.width * 0.28, "Game too small: \(label)")
                        var frames = controls.controls.map { ($0.id, $0.frame) } + [("menu", controls.menuFrame)]
                        if let strip = controls.titleStrip {
                            XCTAssertFalse(strip.intersects(game), "Strip over the game: \(label)")
                            frames.append(("strip", strip))
                        }
                        for control in controls.controls {
                            XCTAssertGreaterThanOrEqual(min(control.hitFrame.width, control.hitFrame.height), 58, "Small hit region \(control.id): \(label)")
                            if control.kind == .face {
                                XCTAssertGreaterThanOrEqual(control.frame.width, control.spec.isAxisButton ? 44 : 50, "Small face \(control.id): \(label)")
                            }
                        }
                        for (index, (id, frame)) in frames.enumerated() {
                            XCTAssertTrue(display.contains(frame), "\(id) off the display: \(label) \(frame)")
                            if id != "strip" { XCTAssertFalse(game.insetBy(dx: 0.5, dy: 0.5).intersects(frame), "\(id) over the game: \(label) \(frame) \(game)") }
                            for (other, otherFrame) in frames.dropFirst(index + 1) {
                                XCTAssertFalse(frame.insetBy(dx: 0.5, dy: 0.5).intersects(otherFrame), "\(id) overlaps \(other): \(label)")
                            }
                        }
                    }
                }
            }
        }
    }

    func testTheRendererAndTheOverlayAgreeOnTheGame() {
        // The renderer resolves with the family alone (no mirroring); the overlay with the hand.
        for phone in [proPortrait, proLandscape] {
            let renderer = resolvePlayerStageLayout(containerSize: phone.size, safeAreaInsets: phone.insets, aspectRatio: 4.0 / 3.0,
                                                    topAlignedInPortrait: true, reserveTouchSpace: true,
                                                    stage: PlayerStageContext(isPhoneFormFactor: true), controls: plan("nes"))
            XCTAssertEqual(renderer.viewport.frame, stage("nes", phone, mirrored: true).viewport.frame, phone.name)
        }
    }

    // MARK: Hit regions

    private func nes() -> PlayerTouchLayout { stage("nes", proPortrait).controls! }
    private func snes() -> PlayerTouchLayout { stage("snes", proPortrait).controls! }

    func testAButtonsEdgeAndItsGrownRegionHit() throws {
        let layout = nes()
        let a = try XCTUnwrap(layout.control("A"))
        // Just inside the grown region, straight up from A (away from B).
        let edge = CGPoint(x: a.center.x, y: a.frame.minY - 13)
        XCTAssertEqual(layout.target(at: edge), .buttons(["A"]))
        XCTAssertNil(layout.target(at: CGPoint(x: a.center.x, y: a.frame.minY - 16)), "Outside the grown region and the cluster zone")
    }

    func testNoDeadGapsInsideAFaceCluster() throws {
        let layout = snes()
        let x = try XCTUnwrap(layout.control("X")), y = try XCTUnwrap(layout.control("Y"))
        // The diamond's centre is out of every button's hit region but inside the zone.
        let centre = CGPoint(x: x.center.x, y: y.center.y)
        XCTAssertFalse(layout.controls.contains { $0.kind == .face && PlayerTouchRules.hits(centre, $0) })
        XCTAssertNotNil(layout.target(at: centre))
        // Between X (top) and Y (left), nearer Y.
        let between = CGPoint(x: y.center.x + 20, y: y.center.y - 26)
        XCTAssertEqual(layout.target(at: between), .buttons(["Y"]))
    }

    func testTheChordBandBetweenAAndBPressesBoth() throws {
        let layout = nes()
        let a = try XCTUnwrap(layout.control("A")).center, b = try XCTUnwrap(layout.control("B")).center
        let mid = CGPoint(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2)
        let distance = hypot(a.x - b.x, a.y - b.y)
        func toward(_ share: CGFloat) -> CGPoint { CGPoint(x: mid.x + (a.x - mid.x) / (distance / 2) * distance * share, y: mid.y + (a.y - mid.y) / (distance / 2) * distance * share) }
        XCTAssertEqual(layout.target(at: mid)?.sorted(layout), .buttons(["B", "A"]).sorted(layout))
        XCTAssertEqual(layout.target(at: toward(0.2))?.sorted(layout), .buttons(["B", "A"]).sorted(layout))
        XCTAssertEqual(layout.target(at: toward(0.3)), .buttons(["A"]))
    }

    func testTheDPadClaimsItsGrownDisc() throws {
        let layout = nes()
        let pad = try XCTUnwrap(layout.dpad)
        XCTAssertEqual(layout.target(at: CGPoint(x: pad.frame.minX - 12, y: pad.center.y)), .dpad)
        XCTAssertNil(layout.target(at: CGPoint(x: pad.center.x, y: pad.frame.maxY + 40)).flatMap { $0 == .dpad ? $0 : nil })
    }

    // MARK: D-pad directions

    func testDPadDeadZoneAndEightWaySectors() {
        let r: CGFloat = 75
        func direction(_ degrees: Double, _ distance: CGFloat = 50, previous: PlayerDPadDirection = .none) -> PlayerDPadDirection {
            let radians = degrees * .pi / 180
            return PlayerTouchRules.dpadDirection(dx: distance * CGFloat(cos(radians)), dy: distance * CGFloat(sin(radians)), radius: r, previous: previous)
        }
        XCTAssertEqual(direction(0, 13), .none, "Inside the dead zone (18% of 75 = 13.5)")
        XCTAssertEqual(direction(0, 14), .right)
        XCTAssertEqual(direction(0), .right)
        XCTAssertEqual(direction(24), .right)
        XCTAssertEqual(direction(26), .downRight, "Diagonal sectors are 40° wide (25°–65°)")
        XCTAssertEqual(direction(45), .downRight)
        XCTAssertEqual(direction(90), .down)
        XCTAssertEqual(direction(135), .downLeft)
        XCTAssertEqual(direction(180), .left)
        XCTAssertEqual(direction(225), .upLeft)
        XCTAssertEqual(direction(270), .up)
        XCTAssertEqual(direction(315), .upRight)
        XCTAssertEqual(direction(64), .downRight)
        XCTAssertEqual(direction(66), .down)
        // Hysteresis: a held direction keeps a slightly wider sector.
        XCTAssertEqual(direction(28, previous: .right), .right)
        XCTAssertEqual(direction(30, previous: .right), .downRight)
        XCTAssertEqual(direction(0, 12, previous: .right), .right, "Held: the dead zone shrinks to 15%")
        XCTAssertEqual(PlayerDPadDirection.downRight.keys, [5, 7])
    }

    // MARK: Tracker

    func testAQuickTapPressesThenReleasesInOrder() throws {
        var tracker = PlayerTouchTracker(layout: nes())
        let a = try XCTUnwrap(tracker.layout.control("A")).center
        XCTAssertEqual(tracker.began(1, at: a), [.digital(key: 8, pressed: true), .pressHaptic])
        XCTAssertEqual(tracker.ended(1), [.digital(key: 8, pressed: false)])
        XCTAssertFalse(tracker.isTracking)
        XCTAssertTrue(tracker.pressedControls.isEmpty)
    }

    func testRollingFromBToAReleasesBThenPressesA() throws {
        var tracker = PlayerTouchTracker(layout: nes())
        let a = try XCTUnwrap(tracker.layout.control("A")).center, b = try XCTUnwrap(tracker.layout.control("B")).center
        _ = tracker.began(1, at: b)
        XCTAssertEqual(tracker.moved(1, to: a), [.digital(key: 0, pressed: false), .digital(key: 8, pressed: true), .pressHaptic])
        XCTAssertEqual(tracker.pressedControls, ["A"])
        XCTAssertEqual(tracker.ended(1), [.digital(key: 8, pressed: false)])
    }

    func testRollingThroughTheMidpointChordsThenSettles() throws {
        var tracker = PlayerTouchTracker(layout: nes())
        let a = try XCTUnwrap(tracker.layout.control("A")).center, b = try XCTUnwrap(tracker.layout.control("B")).center
        _ = tracker.began(1, at: b)
        let mid = CGPoint(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2)
        XCTAssertEqual(tracker.moved(1, to: mid), [.digital(key: 8, pressed: true), .pressHaptic])
        XCTAssertEqual(tracker.pressedControls, ["A", "B"])
        XCTAssertEqual(tracker.moved(1, to: a), [.digital(key: 0, pressed: false)])
        XCTAssertEqual(tracker.pressedControls, ["A"])
    }

    func testRollingHysteresisKeepsTheHeldButtonNearTheBoundary() throws {
        let layout = snes()
        var tracker = PlayerTouchTracker(layout: layout)
        let y = try XCTUnwrap(layout.control("Y")), x = try XCTUnwrap(layout.control("X"))
        _ = tracker.began(1, at: y.center)
        // A point barely nearer X than Y (normalised) keeps Y.
        let boundary = CGPoint(x: (x.center.x + y.center.x) / 2 + 0.75, y: (x.center.y + y.center.y) / 2 - 0.75)
        XCTAssertLessThan(PlayerTouchRules.normalizedDistance(boundary, to: x), PlayerTouchRules.normalizedDistance(boundary, to: y))
        XCTAssertEqual(tracker.moved(1, to: boundary), [])
        XCTAssertEqual(tracker.pressedControls, ["Y"])
        // Clearly on X: switches.
        XCTAssertEqual(tracker.moved(1, to: x.center), [.digital(key: 1, pressed: false), .digital(key: 9, pressed: true), .pressHaptic])
    }

    func testDriftingOffKeepsTheButtonUntil40PointsBeyondIt() throws {
        var tracker = PlayerTouchTracker(layout: nes())
        let a = try XCTUnwrap(tracker.layout.control("A"))
        _ = tracker.began(1, at: a.center)
        let reach = a.frame.width / 2 + PlayerControlMetrics.hitGrowth
        XCTAssertEqual(tracker.moved(1, to: CGPoint(x: a.center.x, y: a.center.y - reach - 30)), [], "30 pt beyond: still held")
        XCTAssertEqual(tracker.pressedControls, ["A"])
        XCTAssertEqual(tracker.moved(1, to: CGPoint(x: a.center.x, y: a.center.y - reach - 45)), [.digital(key: 8, pressed: false)])
        // Back onto the button: pressed again.
        XCTAssertEqual(tracker.moved(1, to: a.center), [.digital(key: 8, pressed: true), .pressHaptic])
        XCTAssertEqual(tracker.ended(1), [.digital(key: 8, pressed: false)])
    }

    func testRollingStaysWithinItsKind() throws {
        var tracker = PlayerTouchTracker(layout: stage("gba", proPortrait).controls!)
        let a = try XCTUnwrap(tracker.layout.control("A")), r = try XCTUnwrap(tracker.layout.control("R"))
        _ = tracker.began(1, at: a.center)
        // Onto the R shoulder: a face touch never presses a shoulder (it drifts, then lets go).
        let output = tracker.moved(1, to: r.center)
        XCTAssertFalse(output.contains(.digital(key: 11, pressed: true)))
    }

    func testADPadDragThatLeavesThePadKeepsItsDirection() throws {
        var tracker = PlayerTouchTracker(layout: nes())
        let pad = try XCTUnwrap(tracker.layout.dpad).center
        XCTAssertEqual(tracker.began(1, at: CGPoint(x: pad.x + 40, y: pad.y)), [.digital(key: 7, pressed: true), .directionHaptic])
        XCTAssertEqual(tracker.moved(1, to: CGPoint(x: pad.x + 260, y: pad.y + 10)), [], "Still right, far off the pad")
        XCTAssertEqual(tracker.dpadKeys, [7])
        XCTAssertEqual(tracker.moved(1, to: CGPoint(x: pad.x + 200, y: pad.y + 200)),
                       [.digital(key: 5, pressed: true), .directionHaptic], "Diagonal down-right")
        XCTAssertEqual(tracker.ended(1), [.digital(key: 5, pressed: false), .digital(key: 7, pressed: false)])
    }

    func testTwoTouchesOnOneButtonReleaseOnlyWhenTheLastLifts() throws {
        var tracker = PlayerTouchTracker(layout: nes())
        let a = try XCTUnwrap(tracker.layout.control("A")).center
        _ = tracker.began(1, at: a)
        XCTAssertEqual(tracker.began(2, at: CGPoint(x: a.x + 5, y: a.y)), [.pressHaptic])
        XCTAssertEqual(tracker.ended(1), [])
        XCTAssertEqual(tracker.ended(2), [.digital(key: 8, pressed: false)])
    }

    func testReleaseAllLetsGoOfEverything() throws {
        var tracker = PlayerTouchTracker(layout: stage("gba", proPortrait).controls!)
        let layout = tracker.layout
        _ = tracker.began(1, at: CGPoint(x: layout.dpad!.center.x, y: layout.dpad!.center.y - 50))
        _ = tracker.began(2, at: layout.control("A")!.center)
        _ = tracker.began(3, at: layout.control("R")!.center)
        let output = tracker.releaseAll()
        XCTAssertEqual(Set(output.compactMap { if case let .digital(key, pressed) = $0, !pressed { return key } else { return nil } }), [4, 8, 11])
        XCTAssertFalse(tracker.isTracking)
        XCTAssertTrue(tracker.dpadKeys.isEmpty)
        XCTAssertEqual(tracker.releaseAll(), [])
    }

    func testAxisButtonsPushTheRightStick() throws {
        var tracker = PlayerTouchTracker(layout: stage("n64", proLandscape).controls!)
        let up = try XCTUnwrap(tracker.layout.control("C-Up")).center
        XCTAssertEqual(tracker.began(1, at: up), [.motion(stick: 1, x: 0, y: -1), .pressHaptic])
        XCTAssertEqual(tracker.ended(1), [.motion(stick: 1, x: 0, y: 0)])
    }

    func testMirroredLayoutsHitTheMovedControls() throws {
        let layout = stage("nes", proPortrait, mirrored: true).controls!
        let pad = try XCTUnwrap(layout.dpad), a = try XCTUnwrap(layout.control("A"))
        XCTAssertEqual(layout.target(at: pad.center), .dpad)
        XCTAssertEqual(layout.target(at: a.center), .buttons(["A"]))
        XCTAssertNil(layout.target(at: CGPoint(x: 20 + 75, y: pad.center.y)).flatMap { $0 == .dpad ? $0 : nil }, "Nothing left where the d-pad was")
    }

    // MARK: Analog sticks (the maths itself: PlayerTouchStickTests)

    func testTheStickCentreIsWhereTheThumbLands() {
        let zone = CGRect(x: 0, y: 0, width: 160, height: 160)
        // Anywhere in the zone, edges and corners included: the touch point itself, so a
        // touch-down never reads as tilt.
        XCTAssertEqual(PlayerStickModel.center(for: CGPoint(x: 70, y: 90), zone: zone, travel: 48), CGPoint(x: 70, y: 90))
        XCTAssertEqual(PlayerStickModel.center(for: CGPoint(x: 5, y: 80), zone: zone, travel: 48), CGPoint(x: 5, y: 80))
        XCTAssertEqual(PlayerStickModel.center(for: CGPoint(x: 158, y: 2), zone: zone, travel: 48), CGPoint(x: 158, y: 2))
    }

    func testAStickTouchFloatsFollowsTicksOnceAtFullTiltAndDropsToZeroOnLift() throws {
        var tracker = PlayerTouchTracker(layout: stage("n64", proLandscape).controls!)
        let stick = try XCTUnwrap(tracker.layout.control("stick0"))
        let profile = try XCTUnwrap(stick.stickProfile)
        let s = tracker.layout.metrics.scale
        XCTAssertEqual(stick.kind, .stick)
        XCTAssertEqual(profile.name, "n64")
        XCTAssertEqual(stick.travel, 48 * s, accuracy: 0.001, "N64 travel at the control size")
        let travel = Double(stick.travel)
        let down = CGPoint(x: stick.frame.minX + 70, y: stick.frame.minY + 75)
        // Touch-down there makes it the centre: no deflection, no haptic.
        XCTAssertEqual(tracker.began(1, at: down), [.motion(stick: 0, x: 0, y: 0)])
        XCTAssertEqual(tracker.stickPositions["stick0"]?.center, down)
        // Halfway right: the reference output for that offset.
        let half = PlayerStickModel.output(dx: travel / 2, dy: 0, profile)
        let halfway = tracker.moved(1, to: CGPoint(x: down.x + travel / 2, y: down.y))
        XCTAssertEqual(motionX(halfway), half.x, accuracy: 0.0001)
        XCTAssertEqual(halfway.count, 1)
        XCTAssertEqual(tracker.stickPositions["stick0"]?.fullTilt, false)
        // Into saturation: full right and one soft tick; no second tick while it stays there.
        XCTAssertEqual(tracker.moved(1, to: CGPoint(x: down.x + travel * 0.97, y: down.y)), [.motion(stick: 0, x: 1, y: 0), .stickHaptic])
        XCTAssertEqual(tracker.moved(1, to: CGPoint(x: down.x + travel * 0.99, y: down.y)), [])
        XCTAssertEqual(tracker.stickPositions["stick0"]?.fullTilt, true)
        // Far past the rim: the centre follows, so the thumb sits at the rim.
        let far = CGPoint(x: down.x + 3 * travel, y: down.y)
        XCTAssertEqual(tracker.moved(1, to: far), [])
        let followed = try XCTUnwrap(tracker.stickPositions["stick0"]?.center)
        XCTAssertEqual(Double(far.x - followed.x), travel, accuracy: 0.001)
        XCTAssertEqual(tracker.stickPositions["stick0"]?.knob.x ?? 0, far.x, accuracy: 0.001, "The knob at the output position")
        // Reversing responds at once: a little back from the rim is already less than full.
        let back = CGPoint(x: far.x - travel * 0.5, y: far.y)
        let reversed = tracker.moved(1, to: back)
        let expected = PlayerStickModel.output(dx: travel * 0.5, dy: 0, profile)
        XCTAssertEqual(motionX(reversed), expected.x, accuracy: 0.0001)
        XCTAssertEqual(reversed.count, 1, "No tick on the way back")
        // Back into full tilt after leaving it: another tick.
        XCTAssertEqual(tracker.moved(1, to: CGPoint(x: followed.x + travel, y: far.y)), [.motion(stick: 0, x: 1, y: 0), .stickHaptic])
        // A second touch in the zone doesn't take the held stick.
        XCTAssertEqual(tracker.began(2, at: stick.center), [])
        // Lift: zero at once, the ring back to the zone's centre.
        XCTAssertEqual(tracker.ended(1), [.motion(stick: 0, x: 0, y: 0)])
        XCTAssertNil(tracker.stickPositions["stick0"])
        // A touch at the zone's corner makes that point the centre: no tilt, no haptic.
        let cornerPoint = CGPoint(x: stick.frame.minX + 1, y: stick.frame.minY + 1)
        let corner = tracker.began(3, at: cornerPoint)
        XCTAssertFalse(corner.contains(.stickHaptic))
        XCTAssertEqual(motionX(corner), 0, accuracy: 0.0001)
        let landed = try XCTUnwrap(tracker.stickPositions["stick0"]?.center)
        XCTAssertEqual(landed.x, cornerPoint.x, accuracy: 0.001)
        XCTAssertEqual(landed.y, cornerPoint.y, accuracy: 0.001)
        XCTAssertEqual(tracker.releaseAll(), [.motion(stick: 0, x: 0, y: 0)])
    }

    private func motionX(_ output: [PlayerTouchOutput]) -> Double {
        for item in output { if case let .motion(_, x, _) = item { return x } }
        return .nan
    }

    func testAGamesTuningReachesTheStick() throws {
        let tuning = PlayerStickTuning(sensitivity: .high, deadZone: .large, centre: .fixed)
        let plan = try XCTUnwrap(PlayerControlPlan(family: "psp", stickTuning: tuning))
        let controls = try XCTUnwrap(resolvePlayerStageLayout(containerSize: proLandscape.size, safeAreaInsets: proLandscape.insets,
                                                              aspectRatio: 1.7647, topAlignedInPortrait: true, reserveTouchSpace: true,
                                                              stage: PlayerStageContext(isPhoneFormFactor: true), controls: plan).controls)
        let nub = try XCTUnwrap(controls.control("stick0"))
        XCTAssertEqual(nub.stickProfile, PlayerStickProfile.psp.tuned(tuning, scale: Double(controls.metrics.scale)))
        XCTAssertTrue(nub.fixedCentre)
        // Fixed: the zone's centre is the centre wherever the touch lands, and it never follows.
        var tracker = PlayerTouchTracker(layout: controls)
        let travel = Double(nub.travel)
        let touch = CGPoint(x: nub.center.x + 10, y: nub.center.y)
        XCTAssertEqual(motionX(tracker.began(1, at: touch)), PlayerStickModel.output(dx: 10, dy: 0, nub.stickProfile!).x, accuracy: 0.0001)
        XCTAssertEqual(tracker.stickPositions["stick0"]?.center, nub.center)
        _ = tracker.moved(1, to: CGPoint(x: nub.center.x + 4 * travel, y: nub.center.y))
        XCTAssertEqual(tracker.stickPositions["stick0"]?.center, nub.center)
        _ = tracker.ended(1)
    }

    func testClicksOnStickConsolesArePressesWithTheLightTick() throws {
        var tracker = PlayerTouchTracker(layout: stage("psx", proLandscape, analog: true).controls!)
        let l3 = try XCTUnwrap(tracker.layout.control("L3"))
        XCTAssertEqual(tracker.began(1, at: l3.center), [.digital(key: 14, pressed: true), .pressHaptic])
        XCTAssertEqual(tracker.ended(1), [.digital(key: 14, pressed: false)])
        // The two PlayStation sticks feel different.
        XCTAssertEqual(tracker.layout.control("stick0")?.stickProfile?.name, "psx")
        XCTAssertEqual(tracker.layout.control("stick1")?.stickProfile?.name, "psx-right")
    }

    // MARK: Per-console layouts

    func testNintendo64PutsTheStickInTheThumbSpotWithTheSmallDPadAbove() throws {
        let layout = stage("n64", proLandscape)
        let controls = try XCTUnwrap(layout.controls)
        let game = layout.viewport.frame
        // (The control size gives way a little so the game keeps 70% of its height.)
        let s = controls.metrics.scale
        XCTAssertGreaterThanOrEqual(s, 0.88)
        let stick = try XCTUnwrap(controls.control("stick0")), pad = try XCTUnwrap(controls.dpad)
        XCTAssertEqual(stick.frame.width, 160 * s, accuracy: 0.01, "Primary zone")
        XCTAssertEqual(stick.frame.height, 160 * s, accuracy: 0.01, "Primary zone")
        XCTAssertEqual(pad.frame.width, 104 * s, accuracy: 0.01, "Small d-pad")
        XCTAssertEqual(pad.center.x, stick.center.x, accuracy: 0.5)
        XCTAssertEqual(stick.frame.minY - pad.frame.maxY, 10, accuracy: 0.5, "D-pad just above the stick")
        XCTAssertLessThan(stick.frame.maxX, game.minX, "Left rail")
        // Z: a large tab at the top of the left rail, L at its outer corner; R at the top of the right rail.
        let z = try XCTUnwrap(controls.control("Z")), l = try XCTUnwrap(controls.control("L")), r = try XCTUnwrap(controls.control("R"))
        XCTAssertEqual(z.frame.width, 92 * s, accuracy: 0.01)
        XCTAssertEqual(z.frame.height, max(40, 48 * s), accuracy: 0.01)
        XCTAssertGreaterThan(z.frame.width, l.frame.width)
        XCTAssertEqual(z.frame.minY, 8, accuracy: 0.5)
        XCTAssertLessThan(l.frame.maxX, z.frame.minX)
        XCTAssertLessThan(z.frame.maxX, game.minX)
        XCTAssertGreaterThan(r.frame.minX, game.maxX)
        XCTAssertEqual(r.frame.minY, 8, accuracy: 0.5)
        // A (64) with B (56) down-left; the C buttons a 44 pt diamond above-right of A.
        let a = try XCTUnwrap(controls.control("A")), b = try XCTUnwrap(controls.control("B"))
        XCTAssertEqual(a.frame.width, 64 * s, accuracy: 0.01)
        XCTAssertEqual(b.frame.width, 56 * s, accuracy: 0.01)
        XCTAssertLessThan(b.center.x, a.center.x)
        XCTAssertGreaterThan(b.center.y, a.center.y)
        let cs = try ["C-Up", "C-Down", "C-Left", "C-Right"].map { try XCTUnwrap(controls.control($0)) }
        let cUp = cs[0], cDown = cs[1], cLeft = cs[2], cRight = cs[3]
        for c in cs {
            XCTAssertEqual(c.frame.width, 44, c.id)
            XCTAssertTrue(c.spec.isAxisButton, "C buttons push the right stick")
        }
        let cCentre = CGPoint(x: cs.map(\.center.x).reduce(0, +) / 4, y: cs.map(\.center.y).reduce(0, +) / 4)
        XCTAssertGreaterThan(cCentre.x, a.center.x, "C diamond right of A")
        XCTAssertLessThan(cCentre.y, a.center.y, "C diamond above A")
        XCTAssertEqual(cUp.center.x, cDown.center.x, accuracy: 0.01)
        XCTAssertEqual(cLeft.center.y, cRight.center.y, accuracy: 0.01)
        XCTAssertLessThan(cUp.center.y, cDown.center.y)
        XCTAssertLessThan(cLeft.center.x, cRight.center.x)
        // Primary controls level: the stick and the face cluster share a height.
        let face = controls.controls.filter { $0.kind == .face }.reduce(CGRect.null) { $0.union($1.frame) }
        XCTAssertEqual(face.midY, stick.center.y, accuracy: 0.5)
        // Start beside Menu, bottom centre under the game; nothing at the rails' feet.
        let start = try XCTUnwrap(controls.control("Start"))
        XCTAssertGreaterThan(controls.menuFrame.minY, game.maxY)
        XCTAssertEqual(start.center.y, controls.menuCenter.y, accuracy: 0.5)
        XCTAssertGreaterThan(start.frame.minX, controls.menuFrame.maxX)
        XCTAssertEqual((controls.menuFrame.minX + start.frame.maxX) / 2, game.midX, accuracy: 0.5)
    }

    func testNintendo3DSAddsTheCirclePadWithTheDPadBelowIt() throws {
        let plan = plan("3ds")
        XCTAssertEqual(plan.leftStick?.stick, 0, "Left analog to the core")
        XCTAssertTrue(plan.leftStickPrimary)
        XCTAssertEqual(plan.dpadSlot, .below)
        XCTAssertEqual(plan.arrangement, .diamond)
        XCTAssertEqual(plan.leftShoulders.map(\.id), ["ZL", "L"])
        XCTAssertEqual(plan.rightShoulders.map(\.id), ["R", "ZR"])
        for phone in [proPortrait, proLandscape] {
            let layout = stage("3ds", phone)
            let controls = try XCTUnwrap(layout.controls, phone.name)
            let pad = try XCTUnwrap(controls.control("circlePad"), phone.name), dpad = try XCTUnwrap(controls.dpad, phone.name)
            XCTAssertEqual(pad.kind, .stick)
            XCTAssertEqual(pad.stickProfile?.name, "3ds")
            XCTAssertEqual(pad.frame.width, 160 * controls.metrics.scale, accuracy: 0.01, phone.name)
            XCTAssertEqual(dpad.frame.width, 104 * controls.metrics.scale, accuracy: 0.01, phone.name)
            XCTAssertEqual(dpad.frame.minY - pad.frame.maxY, 10, accuracy: 0.5, "D-pad below the circle pad: \(phone.name)")
            XCTAssertEqual(dpad.center.x, pad.center.x, accuracy: 0.5)
            // The face diamond level with the circle pad.
            let face = controls.controls.filter { $0.kind == .face }.reduce(CGRect.null) { $0.union($1.frame) }
            XCTAssertEqual(face.midY, pad.center.y, accuracy: 0.5, phone.name)
            // Nothing over either screen.
            for screen in layout.screens.map(\.frame) {
                for control in controls.controls {
                    XCTAssertFalse(screen.insetBy(dx: 0.5, dy: 0.5).intersects(control.frame), "\(control.id) over a screen: \(phone.name)")
                }
            }
        }
    }

    func testPSPPutsTheNubBelowTheDPad() throws {
        let controls = try XCTUnwrap(stage("psp", proLandscape).controls)
        let nub = try XCTUnwrap(controls.control("stick0")), dpad = try XCTUnwrap(controls.dpad)
        XCTAssertEqual(nub.frame.width, 120 * controls.metrics.scale, accuracy: 0.01, "Secondary zone")
        XCTAssertEqual(nub.travel, 36 * controls.metrics.scale, accuracy: 0.01, "The PSP nub's travel")
        XCTAssertEqual(nub.stickProfile?.name, "psp")
        XCTAssertEqual(dpad.frame.width, 150 * controls.metrics.scale, accuracy: 0.01, "The d-pad keeps the thumb spot")
        XCTAssertEqual(nub.frame.minY - dpad.frame.maxY, 10, accuracy: 0.5)
        XCTAssertEqual(nub.center.x, dpad.center.x, accuracy: 0.5)
        XCTAssertFalse(plan("psp").leftStickPrimary)
    }

    func testPlayStationShowsSticksAndClicksOnlyWithAnalogControls() throws {
        let off = plan("psx")
        XCTAssertNil(off.leftStick)
        XCTAssertNil(off.rightStick)
        XCTAssertEqual(off.utilities.map(\.id), ["Select", "Start", "Analog"], "The Analog mode button stays")
        let on = plan("psx", analog: true)
        XCTAssertEqual(on.utilities.map(\.id), ["Select", "Start", "L3", "R3", "Analog"])
        XCTAssertEqual(on.leftStick?.stick, 0)
        XCTAssertEqual(on.rightStick?.stick, 1)
        for phone in [proPortrait, proLandscape] {
            let controls = try XCTUnwrap(stage("psx", phone, analog: true).controls)
            let left = try XCTUnwrap(controls.control("stick0")), right = try XCTUnwrap(controls.control("stick1"))
            let dpad = try XCTUnwrap(controls.dpad)
            let face = controls.controls.filter { $0.kind == .face }.reduce(CGRect.null) { $0.union($1.frame) }
            // Under the clusters, lined up under the taller one.
            XCTAssertGreaterThan(left.frame.minY, dpad.frame.maxY, phone.name)
            XCTAssertEqual(left.frame.minY - face.maxY, 10, accuracy: 0.5, phone.name)
            XCTAssertEqual(left.frame.minY, right.frame.minY, accuracy: 0.5, phone.name)
            XCTAssertEqual(left.center.x, dpad.center.x, accuracy: 0.5)
            XCTAssertEqual(right.center.x, face.midX, accuracy: 0.5)
            XCTAssertGreaterThanOrEqual(left.frame.width, controls.metrics.minimumSecondaryZone - 0.01)
            let hidden = try XCTUnwrap(stage("psx", phone).controls)
            XCTAssertNil(hidden.control("stick0"))
            XCTAssertNil(hidden.control("L3"))
            XCTAssertNotNil(hidden.control("Analog"))
        }
    }

    func testConsolesWithoutSticksGetNone() {
        for family in ["nes", "snes", "gba", "gb", "sega16", "nds", "atari2600"] {
            XCTAssertFalse(PlayerControlPlan(family: family)?.hasSticks ?? true, family)
        }
    }

    func testTouchesOnAGameScreenGoToTheGame() throws {
        // DS and 3DS touch screens, and any game screen a grown hit region reaches.
        for family in ["nds", "3ds", "n64", "nes"] {
            for phone in [proPortrait, proLandscape] {
                let layout = stage(family, phone)
                let controls = try XCTUnwrap(layout.controls)
                XCTAssertEqual(controls.gameScreens, layout.screens.map(\.frame))
                for screen in layout.screens.map(\.frame) {
                    for point in [CGPoint(x: screen.midX, y: screen.midY), CGPoint(x: screen.minX + 1, y: screen.maxY - 1),
                                  CGPoint(x: screen.maxX - 1, y: screen.maxY - 1), CGPoint(x: screen.minX + 1, y: screen.minY + 1)] {
                        XCTAssertNil(controls.target(at: point), "\(family) \(phone.name) \(point)")
                    }
                }
            }
        }
    }
}

private extension PlayerTouchTarget {
    /// Chord ids in layout order, for comparison.
    func sorted(_ layout: PlayerTouchLayout) -> PlayerTouchTarget {
        guard case let .buttons(ids) = self else { return self }
        let order = layout.controls.map(\.id)
        return .buttons(ids.sorted { (order.firstIndex(of: $0) ?? 0) < (order.firstIndex(of: $1) ?? 0) })
    }
}
