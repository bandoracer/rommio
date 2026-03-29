import XCTest
@testable import RommioPlayerKit

final class IOSRuntimeMatrixTests: XCTestCase {
    func testAllRuntimesUseBundledSignedCorePolicy() {
        let runtimes = IOSRuntimeMatrix.fullParityFamilies.flatMap(\.runtimeOptions)

        XCTAssertFalse(runtimes.isEmpty)
        XCTAssertTrue(runtimes.allSatisfy { $0.packagingPolicy == .bundledSignedDynamicLibrary })
        XCTAssertTrue(runtimes.allSatisfy { $0.bundleRelativePath.hasPrefix("Cores/") })
    }

    func testEveryFamilyDefaultRuntimeExistsInOptions() {
        for family in IOSRuntimeMatrix.fullParityFamilies {
            XCTAssertTrue(
                family.runtimeOptions.contains(where: { $0.runtimeID == family.defaultRuntimeID }),
                "Missing default runtime \(family.defaultRuntimeID) for family \(family.familyID)"
            )
        }
    }

    func testNintendoDSUsesTouchTierAndIsNotValidationBlocked() throws {
        let family = try XCTUnwrap(IOSRuntimeMatrix.fullParityFamilies.first(where: { $0.familyID == "nds" }))
        let runtime = try XCTUnwrap(family.runtimeOptions.first(where: { $0.runtimeID == family.defaultRuntimeID }))

        XCTAssertEqual(runtime.interactionProfile, .dualScreenTouch)
        XCTAssertEqual(runtime.validationState, .playable)
        XCTAssertNil(runtime.validationBlockReason)
    }
}
