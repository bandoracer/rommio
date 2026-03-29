import XCTest
@testable import RommioContract
@testable import RommioFoundation

final class RommSyncBridgeTests: XCTestCase {
    func testSyncDownloadsRemoteAssetsWhenLocalFilesAreMissing() async throws {
        let root = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        let store = AppManagedLibraryStore(rootDirectory: root)
        try store.ensureRootLayout()
        let database = try AppDatabase(rootDirectory: root.appending(path: "Database"))

        let client = MockRommService(
            saves: [
                SaveDTO(
                    id: 1,
                    romID: 42,
                    fileName: "42_7.srm",
                    fileSizeBytes: 4,
                    downloadPath: "/api/saves/42_7.srm",
                    updatedAt: "2026-03-18T10:00:00Z"
                ),
            ],
            states: [
                StateDTO(
                    id: 2,
                    romID: 42,
                    fileName: "42_slot1.state",
                    fileSizeBytes: 4,
                    downloadPath: "/api/states/42_slot1.state",
                    updatedAt: "2026-03-18T10:05:00Z"
                ),
            ]
        )
        let bridge = RommSyncBridge(client: client, libraryStore: store, database: database, deviceName: "Test iPhone")

        let summary = try await bridge.syncGame(
            profileID: "profile-test",
            installation: installation,
            rom: rom,
            runtimeID: "snes9x",
            remoteBaseURL: URL(string: "https://romm.example.com")!,
            deviceID: "device-ios-1"
        )

        XCTAssertEqual(summary.uploaded, 1)
        XCTAssertEqual(summary.downloaded, 1)
        XCTAssertFalse(FileManager.default.fileExists(atPath: store.saveRAMURL(for: installation).path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: store.saveStateURL(for: installation, slot: 1).path))
        let downloads = await client.downloadedTargets()
        XCTAssertEqual(downloads.count, 1)
        XCTAssertTrue(downloads.contains(store.saveStateURL(for: installation, slot: 1).path))
    }

    func testSyncUploadsNewerLocalAssets() async throws {
        let root = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        let store = AppManagedLibraryStore(rootDirectory: root)
        try store.ensureRootLayout()
        let database = try AppDatabase(rootDirectory: root.appending(path: "Database"))

        let saveURL = store.saveRAMURL(for: installation)
        try Data("save".utf8).write(to: saveURL)

        let stateURL = store.saveStateURL(for: installation, slot: 1)
        try FileManager.default.createDirectory(at: stateURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data("state".utf8).write(to: stateURL)

        let client = MockRommService(
            saves: [
                SaveDTO(
                    id: 1,
                    romID: 42,
                    fileName: "42_7.srm",
                    fileSizeBytes: 4,
                    downloadPath: "/api/saves/42_7.srm",
                    updatedAt: "2025-03-18T10:00:00Z"
                ),
            ],
            states: [
                StateDTO(
                    id: 2,
                    romID: 42,
                    fileName: "42_slot1.state",
                    fileSizeBytes: 4,
                    downloadPath: "/api/states/42_slot1.state",
                    updatedAt: "2025-03-18T10:05:00Z"
                ),
            ]
        )
        let bridge = RommSyncBridge(client: client, libraryStore: store, database: database, deviceName: "Test iPhone")

        let summary = try await bridge.syncGame(
            profileID: "profile-test",
            installation: installation,
            rom: rom,
            runtimeID: "snes9x",
            remoteBaseURL: URL(string: "https://romm.example.com")!,
            deviceID: "device-ios-1"
        )

        XCTAssertEqual(summary.uploaded, 3)
        XCTAssertEqual(summary.downloaded, 0)
        let uploadedSaves = await client.uploadedSaveTargets()
        let uploadedStates = await client.uploadedStateTargets()
        XCTAssertEqual(uploadedSaves, [saveURL.lastPathComponent])
        XCTAssertEqual(Set(uploadedStates), Set([stateURL.lastPathComponent, "__rommio_sync_42_7.json"]))
    }

    private let installation = InstalledROMReference(
        romID: 42,
        fileID: 7,
        platformSlug: "snes",
        romName: "Super Mario World",
        fileName: "smw.sfc"
    )

    private let rom = RomDTO(
        id: 42,
        name: "Super Mario World",
        platformID: 1,
        platformName: "SNES",
        platformSlug: "snes",
        fsName: "smw.sfc"
    )
}

private actor MockRommService: RommServicing {
    private let saves: [SaveDTO]
    private let states: [StateDTO]
    private var downloadedDestinationPaths: [String] = []
    private var uploadedSaveFileNames: [String] = []
    private var uploadedStateFileNames: [String] = []

    init(saves: [SaveDTO], states: [StateDTO]) {
        self.saves = saves
        self.states = states
    }

    func getCurrentUser() async throws -> UserDTO { fatalError("Unused in test") }
    func getHeartbeat() async throws -> HeartbeatDTO { fatalError("Unused in test") }
    func getPlatforms() async throws -> [PlatformDTO] { fatalError("Unused in test") }
    func getRecentlyAdded() async throws -> ItemsResponse<RomDTO> { fatalError("Unused in test") }
    func getRoms(query: RomQuery) async throws -> ItemsResponse<RomDTO> { fatalError("Unused in test") }
    func getRom(id: Int) async throws -> RomDTO { fatalError("Unused in test") }
    func getCollections() async throws -> [CollectionResponseDTO] { fatalError("Unused in test") }
    func getSmartCollections() async throws -> [SmartCollectionResponseDTO] { fatalError("Unused in test") }
    func getVirtualCollections(type: String, limit: Int?) async throws -> [VirtualCollectionResponseDTO] { fatalError("Unused in test") }
    func listSaves(romID: Int, deviceID: String?) async throws -> [SaveDTO] { saves }
    func listStates(romID: Int) async throws -> [StateDTO] { states }
    func registerDevice(_ request: DeviceRegistrationRequest) async throws -> DeviceRegistrationResponse { fatalError("Unused in test") }

    func uploadSave(
        romID: Int,
        emulator: String?,
        slot: String?,
        deviceID: String?,
        overwrite: Bool?,
        fileURL: URL
    ) async throws -> SaveDTO {
        uploadedSaveFileNames.append(fileURL.lastPathComponent)
        return SaveDTO(
            id: 101,
            romID: romID,
            fileName: fileURL.lastPathComponent,
            fileSizeBytes: Int64((try? Data(contentsOf: fileURL).count) ?? 0),
            downloadPath: "/uploaded/\(fileURL.lastPathComponent)",
            updatedAt: "2026-03-18T12:00:00Z",
            emulator: emulator
        )
    }

    func uploadState(
        romID: Int,
        emulator: String?,
        fileURL: URL
    ) async throws -> StateDTO {
        uploadedStateFileNames.append(fileURL.lastPathComponent)
        return StateDTO(
            id: 102,
            romID: romID,
            fileName: fileURL.lastPathComponent,
            fileSizeBytes: Int64((try? Data(contentsOf: fileURL).count) ?? 0),
            downloadPath: "/uploaded/\(fileURL.lastPathComponent)",
            updatedAt: "2026-03-18T12:00:00Z",
            emulator: emulator
        )
    }

    func download(from absoluteURL: URL, to destinationURL: URL) async throws {
        try FileManager.default.createDirectory(at: destinationURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data(absoluteURL.absoluteString.utf8).write(to: destinationURL)
        downloadedDestinationPaths.append(destinationURL.path)
    }

    func downloadedTargets() -> [String] {
        downloadedDestinationPaths
    }

    func uploadedSaveTargets() -> [String] {
        uploadedSaveFileNames
    }

    func uploadedStateTargets() -> [String] {
        uploadedStateFileNames
    }
}
