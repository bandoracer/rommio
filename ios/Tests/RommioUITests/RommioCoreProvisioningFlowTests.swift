import Foundation
import XCTest
@testable import RommioContract
@testable import RommioFoundation
@testable import RommioPlayerKit
@testable import RommioUI

@MainActor
final class RommioCoreProvisioningFlowTests: XCTestCase {
    func testLaunchInstalledGameAutoProvisionsBundledCore() async throws {
        let harness = try CoreProvisioningHarness()
        let bundle = try harness.makeBundle(corePath: "Cores/faux.dylib")
        let runtime = IOSRuntimeProfile(
            runtimeID: "fake-runtime",
            displayName: "Fake Runtime",
            platformSlugs: ["nes"],
            bundleRelativePath: "Cores/faux.dylib",
            supportedExtensions: ["nes"]
        )
        let catalog = BundledCoreCatalog(
            families: [
                IOSRuntimeFamily(
                    familyID: "nes",
                    displayName: "NES",
                    platformSlugs: ["nes"],
                    defaultRuntimeID: runtime.runtimeID,
                    runtimeOptions: [runtime]
                ),
            ]
        )
        let engine = RecordingPlayerEngine()
        let model = harness.makeModel(
            coreCatalog: catalog,
            coreBundle: bundle,
            playerEngineFactory: { engine }
        )

        let rom = RomDTO(
            id: 1,
            name: "Galaga",
            summary: nil,
            platformID: 1,
            platformName: "Nintendo Entertainment System",
            platformSlug: "nes",
            fsName: "galaga",
            files: [
                RomFileDTO(
                    id: 1,
                    romID: 1,
                    fileName: "galaga.nes",
                    fileExtension: "nes",
                    fileSizeBytes: 32_768
                ),
            ]
        )
        let file = try XCTUnwrap(rom.files.first)
        let romURL = harness.libraryStore.romURL(platformSlug: rom.platformSlug, fileName: file.fileName)
        try FileManager.default.createDirectory(at: romURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data([0, 1, 2, 3]).write(to: romURL, options: .atomic)

        XCTAssertEqual(model.coreResolution(for: rom, file: file).provisioningStatus, .missingCoreInstallable)

        await model.launchInstalledGame(rom: rom, file: file)
        let presentationID = try XCTUnwrap(model.activePlayer?.id)
        await model.startPresentedPlayerIfNeeded(presentationID: presentationID)

        XCTAssertEqual(model.coreResolution(for: rom, file: file).provisioningStatus, .ready)
        XCTAssertNotNil(model.activePlayer)
        XCTAssertEqual(engine.prepareCallCount, 1)
        XCTAssertEqual(engine.startCallCount, 1)
        XCTAssertTrue(FileManager.default.fileExists(atPath: harness.libraryStore.coreInstallReceiptURL(runtimeID: runtime.runtimeID).path))
    }
}

@MainActor
private struct CoreProvisioningHarness {
    let database: AppDatabase
    let secretStore: any SecretStore
    let profileStore: GRDBServerProfileStore
    let offlineStore: GRDBOfflineReadinessStore
    let authController: AuthSessionController
    let libraryRepository: DefaultLibraryRepository
    let downloadQueue: ManagedDownloadQueue
    let libraryStore: AppManagedLibraryStore
    let networkMonitor: NetworkMonitor
    let root: URL

    init() throws {
        root = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        database = try AppDatabase(rootDirectory: root.appending(path: "Database"))
        secretStore = try FileSecretStore(rootDirectory: root.appending(path: "Secrets"))
        profileStore = GRDBServerProfileStore(database: database)
        offlineStore = GRDBOfflineReadinessStore(database: database)
        authController = AuthSessionController(
            profileStore: profileStore,
            secretStore: secretStore,
            session: .shared
        )
        libraryStore = AppManagedLibraryStore(rootDirectory: root.appending(path: "Library"))
        try libraryStore.ensureRootLayout()
        libraryRepository = DefaultLibraryRepository(
            database: database,
            profileStore: profileStore,
            offlineStore: offlineStore,
            authController: authController
        )
        downloadQueue = ManagedDownloadQueue(
            database: database,
            profileStore: profileStore,
            secretStore: secretStore,
            libraryStore: libraryStore
        )
        networkMonitor = NetworkMonitor()
    }

    func makeModel(
        coreCatalog: CoreCatalog,
        coreBundle: Bundle,
        playerEngineFactory: @escaping PlayerEngineFactory
    ) -> RommioAppModel {
        RommioAppModel(
            services: RommioServices(
                database: database,
                secretStore: secretStore,
                profileStore: profileStore,
                offlineStore: offlineStore,
                authController: authController,
                libraryRepository: libraryRepository,
                downloadQueue: downloadQueue,
                libraryStore: libraryStore,
                networkMonitor: networkMonitor
            ),
            coreCatalog: coreCatalog,
            coreBundle: coreBundle,
            playerEngineFactory: playerEngineFactory
        )
    }

    func makeBundle(corePath: String) throws -> Bundle {
        let bundleURL = root.appending(path: "Fixtures.bundle")
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

        let coreURL = bundleURL.appending(path: corePath)
        try FileManager.default.createDirectory(at: coreURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data([0, 1, 2, 3]).write(to: coreURL, options: .atomic)

        let manifestURL = bundleURL.appending(path: "Cores/CoreLicenses.json")
        try FileManager.default.createDirectory(at: manifestURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        let licenseURL = bundleURL.appending(path: "Cores/licenses/fake-runtime.LICENSE.txt")
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

        guard let bundle = Bundle(url: bundleURL) else {
            XCTFail("Failed to create a fixture bundle.")
            throw NSError(domain: "RommioCoreProvisioningFlowTests", code: 1)
        }
        return bundle
    }
}

private final class RecordingPlayerEngine: PlayerEngine, @unchecked Sendable {
    var prepareCallCount = 0
    var startCallCount = 0

    func prepare(session: PlayerSession) async throws {
        prepareCallCount += 1
    }

    func makeHostController() throws -> PlayerHostController {
        #if canImport(UIKit)
        return PlayerHostController()
        #else
        return PlayerHostController()
        #endif
    }

    func start() async throws {
        startCallCount += 1
    }

    func stop() async {}
    func persistSaveRAM() async throws -> URL? { nil }
    func saveState(to url: URL) async throws -> URL { url }
    func saveState(slot: Int) async throws -> URL { URL(fileURLWithPath: NSTemporaryDirectory()).appending(path: "state") }
    func loadState(from url: URL) async throws -> Bool { true }
    func setPaused(_ paused: Bool) async throws {}
    func reset() async throws {}
    func updateVariables(_ variables: [String : String]) async throws {}
    func dispatchDigital(keyCode: Int, pressed: Bool, port: Int) async throws {}
    func dispatchMotion(source: PlayerMotionSource, x: Double, y: Double, port: Int) async throws {}
    func updateInputConfiguration(_ configuration: PlayerInputConfiguration) async throws {}
    func availableControllerTypes(port: Int) async throws -> [PlayerControllerDescriptor] { [] }
    func setControllerType(port: Int, controllerTypeID: Int) async throws {}
    func hotkeySignals() -> AsyncStream<PlayerHotkeyAction> { AsyncStream { _ in } }
    func rumbleSignals() -> AsyncStream<PlayerRumbleSignal> { AsyncStream { _ in } }
    func detach() {}
}
