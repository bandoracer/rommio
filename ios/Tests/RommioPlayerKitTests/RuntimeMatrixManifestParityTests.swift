import Foundation
import XCTest
@testable import RommioPlayerKit

final class RuntimeMatrixManifestParityTests: XCTestCase {
    func testIOSDefaultFamiliesMatchCanonicalManifest() throws {
        let manifest = try loadManifest()
        let expected = Dictionary(uniqueKeysWithValues: manifest.families.map { ($0.familyID, $0.defaultRuntimeID) })
        let actual = Dictionary(uniqueKeysWithValues: IOSRuntimeMatrix.fullParityFamilies.map { ($0.familyID, $0.defaultRuntimeID) })

        XCTAssertEqual(actual, expected)
    }

    func testIOSRuntimeMetadataMatchesCanonicalManifest() throws {
        let manifest = try loadManifest()
        let actualFamilies = Dictionary(uniqueKeysWithValues: IOSRuntimeMatrix.fullParityFamilies.map { ($0.familyID, $0) })

        for family in manifest.families {
            let actualFamily = try XCTUnwrap(actualFamilies[family.familyID], "Missing family \(family.familyID)")
            XCTAssertEqual(actualFamily.platformSlugs, Set(family.platformSlugs), "Platform slugs drifted for \(family.familyID)")

            let runtime = try XCTUnwrap(
                actualFamily.runtimeOptions.first(where: { $0.runtimeID == family.defaultRuntimeID }),
                "Missing runtime \(family.defaultRuntimeID) for \(family.familyID)"
            )

            XCTAssertEqual(runtime.bundleRelativePath, family.ios.bundleRelativePath)
            XCTAssertEqual(runtime.renderBackend, family.ios.renderBackend)
            XCTAssertEqual(runtime.interactionProfile, family.ios.interactionProfile)
            XCTAssertEqual(runtime.validationState, family.ios.validationState)
            XCTAssertEqual(runtime.validationBlockReason, family.ios.validationBlockReason)
            XCTAssertEqual(runtime.supportedExtensions, Set(family.ios.supportedExtensions))
            XCTAssertEqual(runtime.requiredBIOSFiles, family.ios.requiredBIOSFiles)
        }
    }

    private func loadManifest() throws -> RuntimeMatrixManifest {
        let fileURL = URL(fileURLWithPath: #filePath)
        let repositoryRoot = fileURL
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let manifestURL = repositoryRoot.appending(path: "docs").appending(path: "runtime-matrix.json")
        let data = try Data(contentsOf: manifestURL)
        return try JSONDecoder().decode(RuntimeMatrixManifest.self, from: data)
    }
}
