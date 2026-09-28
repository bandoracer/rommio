import XCTest
@testable import RommioPlayerKit

/// Touch sticks (docs/player-controls.md, "Touch sticks"): the maths is pinned to
/// scripts/touch_stick_reference.py. Every vector the script prints, for every profile,
/// to 4 decimals (the table below is its output; regenerate it from the script, never by
/// hand).
final class PlayerTouchStickTests: XCTestCase {
    private let profiles: [String: PlayerStickProfile] = Dictionary(uniqueKeysWithValues: PlayerStickProfile.all.map { ($0.name, $0) })

    /// (profile, dx, dy) -> (x, y), full tilt — `python3 scripts/touch_stick_reference.py`.
    private let vectors: [(String, Double, Double, Double, Double, Bool)] = [
        ("n64", 0, 0, 0.0000, 0.0000, false),
        ("n64", 3, 0, 0.0000, 0.0000, false),
        ("n64", 5, 0, 0.0006, 0.0000, false),
        ("n64", 12, 0, 0.0943, 0.0000, false),
        ("n64", 24, 0, 0.3724, 0.0000, false),
        ("n64", 40, 0, 0.8700, 0.0000, false),
        ("n64", 60, 0, 1.0000, 0.0000, true),
        ("n64", 0, -30, 0.0000, -0.5449, false),
        ("n64", 20, 20, 0.4246, 0.4246, false),
        ("n64", 34, 34, 0.8600, 0.8600, true),
        ("n64", -15, 26, -0.2877, 0.4986, false),
        ("3ds", 0, 0, 0.0000, 0.0000, false),
        ("3ds", 3, 0, 0.0000, 0.0000, false),
        ("3ds", 5, 0, 0.0072, 0.0000, false),
        ("3ds", 12, 0, 0.1525, 0.0000, false),
        ("3ds", 24, 0, 0.4753, 0.0000, false),
        ("3ds", 40, 0, 0.9727, 0.0000, false),
        ("3ds", 60, 0, 1.0000, 0.0000, true),
        ("3ds", 0, -30, 0.0000, -0.6548, false),
        ("3ds", 20, 20, 0.4260, 0.4260, false),
        ("3ds", 34, 34, 0.7071, 0.7071, true),
        ("3ds", -15, 26, -0.3275, 0.5676, false),
        ("psp", 0, 0, 0.0000, 0.0000, false),
        ("psp", 3, 0, 0.0000, 0.0000, false),
        ("psp", 5, 0, 0.0238, 0.0000, false),
        ("psp", 12, 0, 0.2694, 0.0000, false),
        ("psp", 24, 0, 0.6902, 0.0000, false),
        ("psp", 40, 0, 1.0000, 0.0000, true),
        ("psp", 60, 0, 1.0000, 0.0000, true),
        ("psp", 0, -30, 0.0000, -0.9007, false),
        ("psp", 20, 20, 0.5943, 0.5943, false),
        ("psp", 34, 34, 0.7071, 0.7071, true),
        ("psp", -15, 26, -0.4504, 0.7807, false),
        ("psx", 0, 0, 0.0000, 0.0000, false),
        ("psx", 3, 0, 0.0000, 0.0000, false),
        ("psx", 5, 0, 0.0049, 0.0000, false),
        ("psx", 12, 0, 0.1341, 0.0000, false),
        ("psx", 24, 0, 0.4596, 0.0000, false),
        ("psx", 40, 0, 0.9985, 0.0000, false),
        ("psx", 60, 0, 1.0000, 0.0000, true),
        ("psx", 0, -30, 0.0000, -0.6504, false),
        ("psx", 20, 20, 0.5646, 0.5646, false),
        ("psx", 34, 34, 0.9500, 0.9500, true),
        ("psx", -15, 26, -0.3645, 0.6318, false),
        ("psx-right", 0, 0, 0.0000, 0.0000, false),
        ("psx-right", 3, 0, 0.0000, 0.0000, false),
        ("psx-right", 5, 0, 0.0003, 0.0000, false),
        ("psx-right", 12, 0, 0.0934, 0.0000, false),
        ("psx-right", 24, 0, 0.4488, 0.0000, false),
        ("psx-right", 40, 0, 1.0000, 0.0000, true),
        ("psx-right", 60, 0, 1.0000, 0.0000, true),
        ("psx-right", 0, -30, 0.0000, -0.6934, false),
        ("psx-right", 20, 20, 0.5885, 0.5885, false),
        ("psx-right", 34, 34, 0.9500, 0.9500, true),
        ("psx-right", -15, 26, -0.3887, 0.6737, false),
    ]

    /// (profile, degrees at full tilt (60 pt out), notch entering, notch holding notch 1).
    private let notches: [(String, Double, Int?, Int?)] = [
        ("n64", 0, 0, 0),
        ("n64", 5, 0, 0),
        ("n64", 10, nil, nil),
        ("n64", 40, 1, 1),
        ("n64", 45, 1, 1),
        ("n64", 52, 1, 1),
        ("n64", 55, nil, 1),
        ("n64", 58, nil, nil),
        ("n64", 90, 2, 2),
        ("n64", 135, 3, 3),
        ("n64", 270, 6, 6),
        ("3ds", 0, nil, nil),
        ("3ds", 5, nil, nil),
        ("3ds", 10, nil, nil),
        ("3ds", 40, nil, nil),
        ("3ds", 45, nil, nil),
        ("3ds", 52, nil, nil),
        ("3ds", 55, nil, nil),
        ("3ds", 58, nil, nil),
        ("3ds", 90, nil, nil),
        ("3ds", 135, nil, nil),
        ("3ds", 270, nil, nil),
        ("psp", 0, nil, nil),
        ("psp", 5, nil, nil),
        ("psp", 10, nil, nil),
        ("psp", 40, nil, nil),
        ("psp", 45, nil, nil),
        ("psp", 52, nil, nil),
        ("psp", 55, nil, nil),
        ("psp", 58, nil, nil),
        ("psp", 90, nil, nil),
        ("psp", 135, nil, nil),
        ("psp", 270, nil, nil),
        ("psx", 0, nil, nil),
        ("psx", 5, nil, nil),
        ("psx", 10, nil, nil),
        ("psx", 40, 0, 0),
        ("psx", 45, 0, 0),
        ("psx", 52, 0, 0),
        ("psx", 55, nil, nil),
        ("psx", 58, nil, nil),
        ("psx", 90, nil, nil),
        ("psx", 135, 1, 1),
        ("psx", 270, nil, nil),
        ("psx-right", 0, nil, nil),
        ("psx-right", 5, nil, nil),
        ("psx-right", 10, nil, nil),
        ("psx-right", 40, 0, 0),
        ("psx-right", 45, 0, 0),
        ("psx-right", 52, 0, 0),
        ("psx-right", 55, nil, nil),
        ("psx-right", 58, nil, nil),
        ("psx-right", 90, nil, nil),
        ("psx-right", 135, 1, 1),
        ("psx-right", 270, nil, nil),
    ]

    func testTheProfilesMatchTheReference() {
        XCTAssertEqual(PlayerStickProfile.all.map(\.name), ["n64", "3ds", "psp", "psx", "psx-right"])
        let n64 = PlayerStickProfile.n64
        XCTAssertEqual([n64.travel, n64.deadZone, n64.saturation, n64.curve, n64.diagonalReach], [48, 0.10, 0.90, 1.4, 0.86])
        let threeDS = PlayerStickProfile.threeDS
        XCTAssertEqual([threeDS.travel, threeDS.deadZone, threeDS.saturation, threeDS.curve, threeDS.diagonalReach], [44, 0.10, 0.92, 1.2, 0.7071])
        let psp = PlayerStickProfile.psp
        XCTAssertEqual([psp.travel, psp.deadZone, psp.saturation, psp.curve, psp.diagonalReach], [36, 0.12, 0.90, 1.0, 0.7071])
        let psx = PlayerStickProfile.psx
        XCTAssertEqual([psx.travel, psx.deadZone, psx.saturation, psx.curve, psx.diagonalReach], [44, 0.10, 0.90, 1.3, 0.95])
        let right = PlayerStickProfile.psxRight
        XCTAssertEqual([right.travel, right.deadZone, right.saturation, right.curve, right.diagonalReach], [40, 0.12, 0.90, 1.6, 0.95])
        XCTAssertEqual(PlayerStickProfile.all.map(\.gate), [.octagon, .circle, .circle, .square, .square])
    }

    func testEveryPrintedVectorForEveryProfile() throws {
        XCTAssertEqual(vectors.count, 5 * 11)
        for (name, dx, dy, x, y, tilt) in vectors {
            let profile = try XCTUnwrap(profiles[name])
            let value = PlayerStickModel.output(dx: dx, dy: dy, profile)
            let label = "\(name) (\(dx), \(dy))"
            XCTAssertEqual(value.x, x, accuracy: 0.00005, label)
            XCTAssertEqual(value.y, y, accuracy: 0.00005, label)
            XCTAssertEqual(PlayerStickModel.fullTilt(dx: dx, dy: dy, profile), tilt, label)
        }
    }

    /// The gate's notches (revised 2026-09-26): the octagon's eight, the square's four
    /// corners, none on a circle; caught within 8°, held out to 12°.
    func testEveryPrintedNotchForEveryProfile() throws {
        XCTAssertEqual(notches.count, 5 * 11)
        for (name, degrees, entering, holding) in notches {
            let profile = try XCTUnwrap(profiles[name])
            let dx = 60 * cos(degrees * .pi / 180), dy = 60 * sin(degrees * .pi / 180)
            XCTAssertEqual(PlayerStickModel.notch(dx: dx, dy: dy, profile), entering, "\(name) \(degrees)°")
            XCTAssertEqual(PlayerStickModel.notch(dx: dx, dy: dy, profile, current: 1), holding, "\(name) \(degrees)° holding 1")
        }
        // Below full tilt there is no notch, even on one.
        XCTAssertNil(PlayerStickModel.notch(dx: 20, dy: 0, .n64))
    }

    /// The drawn ring is the gate the output reaches: its corners sit on `gateScale`'s
    /// boundary (the N64's octagon, the DualShock's near-square); a circle draws round.
    func testTheGateOutlineIsTheShapeTheOutputReaches() throws {
        XCTAssertNil(PlayerStickModel.gateOutline(diagonalReach: 0.7071))
        for reach in [0.86, 0.95] {
            let outline = try XCTUnwrap(PlayerStickModel.gateOutline(diagonalReach: reach))
            XCTAssertEqual(outline.count, 8)
            for point in outline {
                let angle = atan2(Double(point.y), Double(point.x))
                XCTAssertEqual(hypot(Double(point.x), Double(point.y)), PlayerStickModel.gateScale(angle: angle, diagonalReach: reach), accuracy: 1e-9)
            }
            // Its corners are the octagon's notches: the cardinals and the diagonals.
            XCTAssertEqual(outline.map { (atan2($0.y, $0.x) * 180 / .pi + 360).truncatingRemainder(dividingBy: 360).rounded() },
                           PlayerStickGate.octagon.notches)
        }
    }

    func testTheGateReachesItsCornerAtFullTilt() {
        // Circle (the profiles' 0.7071, within 1e-4 of √½): 1 everywhere, in-between angles
        // too; octagon (N64) and near-square (DualShock): the 45° corner.
        XCTAssertEqual(PlayerStickModel.gateScale(angle: .pi / 8, diagonalReach: 0.7071), 1)
        XCTAssertEqual(PlayerStickModel.gateScale(angle: -2.1, diagonalReach: 0.5.squareRoot()), 1)
        XCTAssertLessThan(PlayerStickModel.gateScale(angle: .pi / 8, diagonalReach: 0.86), PlayerStickModel.gateScale(angle: .pi / 4, diagonalReach: 0.86))
        XCTAssertEqual(PlayerStickModel.gateScale(angle: 0, diagonalReach: 0.86), 1, accuracy: 0.0001)
        XCTAssertEqual(PlayerStickModel.gateScale(angle: -.pi / 2, diagonalReach: 0.95), 1, accuracy: 0.0001)
        XCTAssertEqual(cos(Double.pi / 4) * PlayerStickModel.gateScale(angle: .pi / 4, diagonalReach: 0.86), 0.86, accuracy: 0.0001)
        XCTAssertEqual(cos(Double.pi / 4) * PlayerStickModel.gateScale(angle: -3 * .pi / 4, diagonalReach: 0.95), 0.95, accuracy: 0.0001)
    }

    func testTuningScalesTravelAndDeadZoneAndKeepsTheGate() {
        let tuned = PlayerStickProfile.n64.tuned(.init(sensitivity: .low, deadZone: .large, centre: .fixed), scale: 0.9)
        XCTAssertEqual(tuned.travel, 48 * 1.2 * 0.9, accuracy: 0.0001)
        XCTAssertEqual(tuned.deadZone, 0.16, accuracy: 0.0001)
        XCTAssertEqual(tuned.gate, .octagon)
        XCTAssertEqual(tuned.saturation, 0.90)
        XCTAssertEqual(tuned.curve, 1.4)
        let high = PlayerStickProfile.psp.tuned(.init(sensitivity: .high, deadZone: .small), scale: 1)
        XCTAssertEqual(high.travel, 36 * 0.8, accuracy: 0.0001)
        XCTAssertEqual(high.deadZone, 0.12 * 0.6, accuracy: 0.0001)
        XCTAssertEqual(high.gate, .circle)
        XCTAssertEqual(PlayerStickProfile.n64.tuned(.standard, scale: 1), PlayerStickProfile.n64)
        XCTAssertEqual(PlayerStickProfile.forStick(family: "n64", stick: 0), .n64)
        XCTAssertEqual(PlayerStickProfile.forStick(family: "3ds", stick: 0), .threeDS)
        XCTAssertEqual(PlayerStickProfile.forStick(family: "psp", stick: 0), .psp)
        XCTAssertEqual(PlayerStickProfile.forStick(family: "psx", stick: 0), .psx)
        XCTAssertEqual(PlayerStickProfile.forStick(family: "psx", stick: 1), .psxRight)
    }
}
