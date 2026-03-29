import Foundation
import XCTest
import RommioFoundation
@testable import RommioPlayerKit

final class BundledCoreVerifierTests: XCTestCase {
    func testVerifierPassesWhenBundledCoreExists() throws {
        let workspace = try makeWorkspace()
        let bundle = try makeBundle(in: workspace, corePath: "Cores/faux.dylib", includeLicenseEntry: true)
        let store = AppManagedLibraryStore(rootDirectory: workspace.appending(path: "Library"))
        try store.ensureRootLayout()
        let catalog = BundledCoreCatalog(families: [makeFamily(bundleRelativePath: "Cores/faux.dylib")])

        let report = BundledCoreVerifier().verify(catalog: catalog, libraryStore: store, bundle: bundle)

        XCTAssertTrue(report.issues.isEmpty)
    }

    func testVerifierReportsMissingCore() throws {
        let workspace = try makeWorkspace()
        let bundle = try makeBundle(in: workspace, corePath: nil, includeLicenseEntry: true)
        let store = AppManagedLibraryStore(rootDirectory: workspace.appending(path: "Library"))
        try store.ensureRootLayout()
        let catalog = BundledCoreCatalog(families: [makeFamily(bundleRelativePath: "Cores/faux.dylib")])

        let report = BundledCoreVerifier().verify(catalog: catalog, libraryStore: store, bundle: bundle)

        XCTAssertEqual(report.issues.count, 1)
        XCTAssertEqual(report.issues.first?.severity, .missingCore)
    }

    func testVerifierReportsMissingLicenseEntry() throws {
        let workspace = try makeWorkspace()
        let bundle = try makeBundle(in: workspace, corePath: "Cores/faux.dylib", includeLicenseEntry: false)
        let store = AppManagedLibraryStore(rootDirectory: workspace.appending(path: "Library"))
        try store.ensureRootLayout()
        let catalog = BundledCoreCatalog(families: [makeFamily(bundleRelativePath: "Cores/faux.dylib")])

        let report = BundledCoreVerifier().verify(catalog: catalog, libraryStore: store, bundle: bundle)

        XCTAssertEqual(report.issues.count, 1)
        XCTAssertEqual(report.issues.first?.severity, .missingLicenseEntry)
    }

    private func makeFamily(bundleRelativePath: String) -> IOSRuntimeFamily {
        IOSRuntimeFamily(
            familyID: "fake",
            displayName: "Fake",
            platformSlugs: ["fake"],
            defaultRuntimeID: "fake-runtime",
            runtimeOptions: [
                IOSRuntimeProfile(
                    runtimeID: "fake-runtime",
                    displayName: "Fake Runtime",
                    platformSlugs: ["fake"],
                    bundleRelativePath: bundleRelativePath
                ),
            ]
        )
    }

    private func makeWorkspace() throws -> URL {
        let root = FileManager.default.temporaryDirectory.appending(path: "BundledCoreVerifierTests").appending(path: UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        return root
    }

    private func makeBundle(in workspace: URL, corePath: String?, includeLicenseEntry: Bool) throws -> Bundle {
        let bundleURL = workspace.appending(path: "Fixtures.bundle")
        let contentsURL = bundleURL.appending(path: "Contents")
        try FileManager.default.createDirectory(at: contentsURL, withIntermediateDirectories: true)

        let infoURL = contentsURL.appending(path: "Info.plist")
        let info: [String: Any] = [
            "CFBundleIdentifier": "io.github.mattsays.rommio.tests.bundle",
            "CFBundleName": "Fixtures",
            "CFBundlePackageType": "BNDL",
            "CFBundleVersion": "1",
            "CFBundleShortVersionString": "1.0",
        ]
        let data = try PropertyListSerialization.data(fromPropertyList: info, format: .xml, options: 0)
        try data.write(to: infoURL, options: .atomic)

        if let corePath {
            let coreURL = bundleURL.appending(path: corePath)
            try FileManager.default.createDirectory(at: coreURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            try Data([0, 1, 2, 3]).write(to: coreURL, options: .atomic)
        }

        let licensesURL = bundleURL.appending(path: "Cores/licenses")
        try FileManager.default.createDirectory(at: licensesURL, withIntermediateDirectories: true)
        try Data("fake license".utf8).write(to: licensesURL.appending(path: "fake-runtime.LICENSE.txt"), options: .atomic)
        let manifestEntries: [[String: String]] = includeLicenseEntry ? [[
            "id": "fake-runtime",
            "binary_path": "Cores/faux.dylib",
            "license_path": "Cores/licenses/fake-runtime.LICENSE.txt",
        ]] : []
        let manifestData = try JSONSerialization.data(withJSONObject: manifestEntries, options: [.prettyPrinted])
        try manifestData.write(to: bundleURL.appending(path: "Cores/CoreLicenses.json"), options: .atomic)

        guard let bundle = Bundle(url: bundleURL) else {
            XCTFail("Failed to create a bundle for packaging verification.")
            throw NSError(domain: "BundledCoreVerifierTests", code: 1)
        }
        return bundle
    }
}
