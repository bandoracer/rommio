import Foundation
import XCTest
@testable import RommioPlayerKit

final class CoreBuildManifestTests: XCTestCase {
    func testCoreBuildManifestCoversEveryAndroidParityRuntime() throws {
        let manifest = try loadBuildManifest()
        let expectedRuntimeIDs = IOSRuntimeMatrix.fullParityFamilies.map(\.defaultRuntimeID).sorted()
        let actualRuntimeIDs = manifest.map(\.runtimeID).sorted()

        XCTAssertEqual(actualRuntimeIDs, expectedRuntimeIDs)
    }

    func testCoreBuildManifestPathsMatchRuntimeCatalog() throws {
        let manifest = try loadBuildManifest()
        let runtimeByID = Dictionary(
            uniqueKeysWithValues: IOSRuntimeMatrix.fullParityFamilies.flatMap(\.runtimeOptions).map { ($0.runtimeID, $0) }
        )

        for entry in manifest {
            let runtime = try XCTUnwrap(runtimeByID[entry.runtimeID], "Missing runtime \(entry.runtimeID) in iOS catalog")
            XCTAssertEqual(entry.bundleRelativePath, runtime.bundleRelativePath)
            XCTAssertEqual(entry.deviceArtifact, "build/ios/iphoneos/\(entry.runtimeID).dylib")
            XCTAssertEqual(entry.simulatorArtifact, "build/ios/iphonesimulator/\(entry.runtimeID).dylib")
            XCTAssertEqual(entry.licenseFile, "ios/App/Resources/Cores/licenses/\(entry.runtimeID).LICENSE.txt")
        }
    }

    private func loadBuildManifest() throws -> [CoreBuildManifestEntry] {
        let fileURL = URL(fileURLWithPath: #filePath)
        let repositoryRoot = fileURL
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let manifestURL = repositoryRoot.appending(path: "scripts").appending(path: "ios").appending(path: "core-build-manifest.json")
        let data = try Data(contentsOf: manifestURL)
        return try JSONDecoder().decode([CoreBuildManifestEntry].self, from: data)
    }
}

private struct CoreBuildManifestEntry: Codable, Hashable {
    let runtimeID: String
    let recipeID: String
    let sourceCheckout: String
    let deviceArtifact: String
    let simulatorArtifact: String
    let bundleRelativePath: String
    let licenseFile: String

    enum CodingKeys: String, CodingKey {
        case runtimeID = "runtime_id"
        case recipeID = "recipe_id"
        case sourceCheckout = "source_checkout"
        case deviceArtifact = "device_artifact"
        case simulatorArtifact = "simulator_artifact"
        case bundleRelativePath = "bundle_relative_path"
        case licenseFile = "license_file"
    }
}
