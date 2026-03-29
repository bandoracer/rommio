import Foundation
import XCTest
import RommioContract
import RommioFoundation
@testable import RommioPlayerKit

final class BundledCoreInstallerTests: XCTestCase {
    func testCatalogReportsProvisionableMissingCoreWhenBundledCoreExistsButIsNotInstalled() throws {
        let workspace = try makeWorkspace()
        let bundle = try makeBundle(in: workspace, corePath: "Cores/faux.dylib")
        let store = AppManagedLibraryStore(rootDirectory: workspace.appending(path: "Library"))
        try store.ensureRootLayout()
        let runtime = makeRuntime(bundleRelativePath: "Cores/faux.dylib")
        let catalog = BundledCoreCatalog(families: [makeFamily(runtime: runtime)])
        let resolution = catalog.resolve(
            rom: makeROM(),
            file: makeFile(),
            libraryStore: store,
            bundle: bundle
        )

        XCTAssertEqual(resolution.capability, .missingCore)
        XCTAssertEqual(resolution.provisioningStatus, .missingCoreInstallable)
        XCTAssertTrue(resolution.canAutoProvision)
        XCTAssertNil(resolution.coreURL)
    }

    func testInstallerMarksRuntimeInstalledAndCatalogResolvesReady() throws {
        let workspace = try makeWorkspace()
        let bundle = try makeBundle(in: workspace, corePath: "Cores/faux.dylib")
        let store = AppManagedLibraryStore(rootDirectory: workspace.appending(path: "Library"))
        try store.ensureRootLayout()
        let runtime = makeRuntime(bundleRelativePath: "Cores/faux.dylib")
        let installer = BundledCoreInstaller()

        let receipt = try installer.install(runtime: runtime, libraryStore: store, bundle: bundle)
        let catalog = BundledCoreCatalog(families: [makeFamily(runtime: runtime)], installer: installer)
        let resolution = catalog.resolve(
            rom: makeROM(),
            file: makeFile(),
            libraryStore: store,
            bundle: bundle
        )

        XCTAssertEqual(receipt.runtimeID, runtime.runtimeID)
        XCTAssertEqual(resolution.capability, .ready)
        XCTAssertEqual(resolution.provisioningStatus, .ready)
        XCTAssertFalse(resolution.canAutoProvision)
        XCTAssertNotNil(resolution.coreURL)
    }

    func testInstallerRejectsRuntimeWhenBuildDoesNotShipCore() throws {
        let workspace = try makeWorkspace()
        let bundle = try makeBundle(in: workspace, corePath: nil)
        let store = AppManagedLibraryStore(rootDirectory: workspace.appending(path: "Library"))
        try store.ensureRootLayout()
        let runtime = makeRuntime(bundleRelativePath: "Cores/missing.dylib")

        XCTAssertThrowsError(try BundledCoreInstaller().install(runtime: runtime, libraryStore: store, bundle: bundle))
    }

    func testStaleReceiptIsInvalidatedWhenBundledCoreChanges() throws {
        let workspace = try makeWorkspace()
        let bundle = try makeBundle(in: workspace, corePath: "Cores/faux.dylib")
        let store = AppManagedLibraryStore(rootDirectory: workspace.appending(path: "Library"))
        try store.ensureRootLayout()
        let runtime = makeRuntime(bundleRelativePath: "Cores/faux.dylib")
        let installer = BundledCoreInstaller()
        _ = try installer.install(runtime: runtime, libraryStore: store, bundle: bundle)

        let coreURL = bundle.bundleURL.appending(path: "Cores/faux.dylib")
        try Data([9, 9, 9, 9, 9]).write(to: coreURL, options: .atomic)

        let catalog = BundledCoreCatalog(families: [makeFamily(runtime: runtime)], installer: installer)
        let resolution = catalog.resolve(
            rom: makeROM(),
            file: makeFile(),
            libraryStore: store,
            bundle: bundle
        )

        XCTAssertEqual(resolution.provisioningStatus, .missingCoreInstallable)
        XCTAssertFalse(FileManager.default.fileExists(atPath: store.coreInstallReceiptURL(runtimeID: runtime.runtimeID).path))
    }

    func testInstallerSupportsFlattenedAppBundleResources() throws {
        let workspace = try makeWorkspace()
        let bundle = try makeBundle(in: workspace, corePath: "Cores/faux.dylib", flattenResources: true)
        let store = AppManagedLibraryStore(rootDirectory: workspace.appending(path: "Library"))
        try store.ensureRootLayout()
        let runtime = makeRuntime(bundleRelativePath: "Cores/faux.dylib")
        let installer = BundledCoreInstaller()

        let receipt = try installer.install(runtime: runtime, libraryStore: store, bundle: bundle)
        let catalog = BundledCoreCatalog(families: [makeFamily(runtime: runtime)], installer: installer)
        let resolution = catalog.resolve(
            rom: makeROM(),
            file: makeFile(),
            libraryStore: store,
            bundle: bundle
        )

        XCTAssertEqual(receipt.runtimeID, runtime.runtimeID)
        XCTAssertEqual(resolution.provisioningStatus, .ready)
        XCTAssertEqual(resolution.coreURL?.lastPathComponent, "faux.dylib")
    }

    private func makeRuntime(bundleRelativePath: String) -> IOSRuntimeProfile {
        IOSRuntimeProfile(
            runtimeID: "fake-runtime",
            displayName: "Fake Runtime",
            platformSlugs: ["nes"],
            bundleRelativePath: bundleRelativePath,
            supportedExtensions: ["nes"]
        )
    }

    private func makeFamily(runtime: IOSRuntimeProfile) -> IOSRuntimeFamily {
        IOSRuntimeFamily(
            familyID: "fake",
            displayName: "Fake",
            platformSlugs: ["nes"],
            defaultRuntimeID: runtime.runtimeID,
            runtimeOptions: [runtime]
        )
    }

    private func makeROM() -> RomDTO {
        RomDTO(
            id: 1,
            name: "Galaga",
            summary: nil,
            platformID: 1,
            platformName: "Nintendo Entertainment System",
            platformSlug: "nes",
            fsName: "galaga",
            files: [makeFile()]
        )
    }

    private func makeFile() -> RomFileDTO {
        RomFileDTO(
            id: 1,
            romID: 1,
            fileName: "galaga.nes",
            fileExtension: "nes",
            fileSizeBytes: 32_768
        )
    }

    private func makeWorkspace() throws -> URL {
        let root = FileManager.default.temporaryDirectory
            .appending(path: "BundledCoreInstallerTests")
            .appending(path: UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        return root
    }

    private func makeBundle(in workspace: URL, corePath: String?, flattenResources: Bool = false) throws -> Bundle {
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
            let coreURL = flattenResources
                ? bundleURL.appending(path: URL(filePath: corePath).lastPathComponent)
                : bundleURL.appending(path: corePath)
            try FileManager.default.createDirectory(at: coreURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            try Data([0, 1, 2, 3]).write(to: coreURL, options: .atomic)

            let manifestURL = flattenResources
                ? bundleURL.appending(path: "CoreLicenses.json")
                : bundleURL.appending(path: "Cores/CoreLicenses.json")
            try FileManager.default.createDirectory(at: manifestURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            let licenseURL = flattenResources
                ? bundleURL.appending(path: "fake-runtime.LICENSE.txt")
                : bundleURL.appending(path: "Cores/licenses/fake-runtime.LICENSE.txt")
            try FileManager.default.createDirectory(at: licenseURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            try Data("Test license".utf8).write(to: licenseURL, options: .atomic)
            let manifest = """
            [
              {
                "id": "fake-runtime",
                "binary_path": "\(corePath)",
                "license_path": "Cores/licenses/fake-runtime.LICENSE.txt",
                "imported_at": "2026-03-18T00:00:00Z"
              }
            ]
            """
            try XCTUnwrap(manifest.data(using: .utf8)).write(to: manifestURL, options: .atomic)
        }

        guard let bundle = Bundle(url: bundleURL) else {
            XCTFail("Failed to create a fixture bundle.")
            throw NSError(domain: "BundledCoreInstallerTests", code: 1)
        }
        return bundle
    }
}
