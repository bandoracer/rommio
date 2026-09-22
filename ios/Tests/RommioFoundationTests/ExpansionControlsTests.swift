import XCTest
@testable import RommioFoundation

final class ExpansionControlsTests: XCTestCase {
    func testEveryExpansionPlatformHasTouchControlsAndIndependentFamilyProfiles() throws {
        let resolver = PlatformControlProfileResolver()
        XCTAssertEqual(Set(resolver.supportedProfiles().map(\.familyID)).count, resolver.supportedProfiles().count)
        for slug in ["genesis", "segacd", "sms", "gamegear", "psx", "n64", "psp", "nes", "snes", "gb", "gba", "atari2600", "tg16", "arcade", "nds"] {
            let profile = resolver.resolve(platformSlug: slug)
            XCTAssertEqual(profile.supportTier, .touchSupported, slug)
            XCTAssertEqual(profile.touchSupportMode, .full, slug)
            let controls = try XCTUnwrap(ExpansionControls.controls(for: profile.familyID))
            XCTAssertEqual(Set(controls.map(\.id)).count, controls.count)
            for c in controls { XCTAssertTrue((0...180).contains(c.x)); XCTAssertTrue((0...260).contains(c.y)) }
            for left in controls { for right in controls where !left.center && !right.center && left.id != right.id && left.left == right.left {
                let distance = hypot(left.x-right.x, left.y-right.y)
                XCTAssertGreaterThanOrEqual(distance, (left.size + right.size)/2, "Overlapping \(slug): \(left.label), \(right.label)")
            } }
        }
        XCTAssertNotEqual(resolver.resolve(platformSlug: "sms").familyID, resolver.resolve(platformSlug: "genesis").familyID)
    }
    func testPinnedCoreButtonMapsAndStickCoverage() throws {
        let sega = try XCTUnwrap(ExpansionControls.controls(for: "sega16"))
        let mapping = Dictionary(uniqueKeysWithValues: sega.compactMap { c in c.key.map { (c.label, $0) } })
        XCTAssertEqual(mapping["A"], 1); XCTAssertEqual(mapping["B"], 0); XCTAssertEqual(mapping["C"], 8)
        XCTAssertEqual(mapping["X"], 10); XCTAssertEqual(mapping["Y"], 9); XCTAssertEqual(mapping["Z"], 11)
        XCTAssertEqual(mapping["Mode"], 2)
        for family in ["psx", "psp", "n64"] { XCTAssertTrue(ExpansionControls.controls(for: family)!.contains { $0.stick == 0 }) }
        XCTAssertTrue(ExpansionControls.controls(for: "psx")!.contains { $0.stick == 1 })
        XCTAssertEqual(ExpansionControls.controls(for: "psx")!.first { $0.id == "Analog" }?.combo, [10, 11, 2])
        let c = ExpansionControls.controls(for: "n64")!.filter { $0.label.hasPrefix("C-") }
        XCTAssertEqual(c.count, 4); XCTAssertTrue(c.allSatisfy { abs($0.axisX) + abs($0.axisY) == 1 })
        XCTAssertEqual(ExpansionControls.profiles.first { $0.familyID == "psp" }?.preferredViewportAspectRatio ?? 0, 480.0/272, accuracy: 0.00001)
    }
}
