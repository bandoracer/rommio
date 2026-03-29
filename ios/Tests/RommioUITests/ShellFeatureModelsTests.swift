import Foundation
import XCTest
@testable import RommioContract
@testable import RommioFoundation
@testable import RommioPlayerKit
@testable import RommioUI

@MainActor
final class ShellFeatureModelsTests: XCTestCase {
    func testHomeFeatureUsesCachedSnapshotAndQueueSummary() async throws {
        let harness = try ShellFeatureHarness()
        let profile = try await harness.seedActiveProfile(connectivity: .online)

        let rom = harness.makeRom(id: 101, name: "Galaga", platformSlug: "nes")
        let snapshot = HomeSnapshot(
            continuePlaying: [rom],
            recentlyAdded: [rom],
            highlightedCollections: [harness.makeCollection(id: "favorites", name: "Favorites")]
        )
        try harness.cacheHome(snapshot, profileID: profile.id)

        let localURL = harness.libraryStore.romURL(platformSlug: "nes", fileName: "galaga.nes")
        try FileManager.default.createDirectory(at: localURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data([0, 1, 2]).write(to: localURL, options: .atomic)
        try harness.saveDownloadRecord(
            DownloadRecord(
                profileID: profile.id,
                romID: rom.id,
                fileID: 1,
                romName: rom.displayName,
                platformSlug: rom.platformSlug,
                fileName: "galaga.nes",
                fileSizeBytes: 3,
                status: .completed,
                progressPercent: 100,
                bytesDownloaded: 3,
                totalBytes: 3,
                localPath: localURL.path,
                enqueuedAtEpochMS: 1,
                completedAtEpochMS: 2,
                updatedAtEpochMS: 2
            )
        )

        let feature = HomeFeatureModel(services: harness.services)
        await feature.refresh(refreshCatalog: false)

        XCTAssertEqual(feature.snapshot.continuePlaying.map(\.id), [rom.id])
        XCTAssertEqual(feature.snapshot.recentlyAdded.map(\.id), [rom.id])
        XCTAssertEqual(feature.snapshot.highlightedCollections.map(\.id), ["favorites"])
        XCTAssertEqual(feature.activeDownloads.count, 0)
        XCTAssertEqual(feature.queueSummary.completedCount, 0)
        XCTAssertEqual(feature.storageSummary.installedGameCount, 1)
        XCTAssertTrue(feature.hasRenderableContent)
        XCTAssertNil(feature.staleMessage)
    }

    func testDownloadsFeatureSeparatesActiveAndRecentRecords() async throws {
        let harness = try ShellFeatureHarness()
        let profile = try await harness.seedActiveProfile(connectivity: .online)

        try harness.saveDownloadRecord(
            DownloadRecord(
                profileID: profile.id,
                romID: 1,
                fileID: 1,
                romName: "Queued",
                platformSlug: "nes",
                fileName: "queued.nes",
                fileSizeBytes: 12,
                status: .queued,
                enqueuedAtEpochMS: 10,
                updatedAtEpochMS: 10
            )
        )
        try harness.saveDownloadRecord(
            DownloadRecord(
                profileID: profile.id,
                romID: 2,
                fileID: 2,
                romName: "Running",
                platformSlug: "nes",
                fileName: "running.nes",
                fileSizeBytes: 12,
                status: .running,
                progressPercent: 40,
                bytesDownloaded: 4,
                totalBytes: 12,
                enqueuedAtEpochMS: 20,
                updatedAtEpochMS: 20
            )
        )
        try harness.saveDownloadRecord(
            DownloadRecord(
                profileID: profile.id,
                romID: 3,
                fileID: 3,
                romName: "Finished",
                platformSlug: "nes",
                fileName: "finished.nes",
                fileSizeBytes: 12,
                status: .completed,
                progressPercent: 100,
                bytesDownloaded: 12,
                totalBytes: 12,
                enqueuedAtEpochMS: 30,
                completedAtEpochMS: 30,
                updatedAtEpochMS: 30
            )
        )

        let feature = DownloadsFeatureModel(
            services: harness.services,
            coreCatalog: BundledCoreCatalog(families: []),
            coreInstaller: BundledCoreInstaller(),
            coreBundle: harness.bundle
        )
        await feature.refresh()

        XCTAssertEqual(feature.activeRecords.count, 2)
        XCTAssertEqual(feature.recentRecords.count, 1)
        XCTAssertEqual(feature.queueSummary.queuedCount, 1)
        XCTAssertEqual(feature.queueSummary.runningCount, 1)
        XCTAssertEqual(feature.queueSummary.completedCount, 1)
        XCTAssertEqual(feature.queueSummary.offlineQueuedCount, 0)
        XCTAssertEqual(feature.trackedCount, 3)
    }

    func testDownloadsFeatureDownloadNowPromotesFailedRecordBackToQueued() async throws {
        let harness = try ShellFeatureHarness()
        let profile = try await harness.seedActiveProfile(connectivity: .online)

        let record = DownloadRecord(
            profileID: profile.id,
            romID: 9,
            fileID: 9,
            romName: "Failed",
            platformSlug: "nes",
            fileName: "failed.nes",
            fileSizeBytes: 12,
            status: .failed,
            progressPercent: 0,
            bytesDownloaded: 0,
            totalBytes: 12,
            lastError: "Timeout",
            enqueuedAtEpochMS: 10,
            updatedAtEpochMS: 10
        )
        try harness.saveDownloadRecord(record)

        let feature = DownloadsFeatureModel(
            services: harness.services,
            coreCatalog: BundledCoreCatalog(families: []),
            coreInstaller: BundledCoreInstaller(),
            coreBundle: harness.bundle
        )

        await feature.downloadNow(recordID: record.id)
        let refreshed = try await harness.services.downloadQueue.records(profileID: profile.id)
        let refreshedStatus = try XCTUnwrap(refreshed.first?.status)
        XCTAssertTrue([DownloadStatus.queued, .running].contains(refreshedStatus))
        XCTAssertNil(refreshed.first?.lastError)
    }

    func testSettingsFeatureSharesPlayerPreferencesWithRepository() async throws {
        let harness = try ShellFeatureHarness()
        _ = try await harness.seedActiveProfile(connectivity: .online)

        let feature = SettingsFeatureModel(
            services: harness.services,
            coreCatalog: BundledCoreCatalog(families: []),
            coreInstaller: BundledCoreInstaller(),
            coreBundle: harness.bundle
        )

        await feature.refresh()
        XCTAssertTrue(feature.preferences.touchControlsEnabled)
        XCTAssertFalse(feature.preferences.oledBlackModeEnabled)

        await feature.setOLEDBlackModeEnabled(true)
        let persisted = try await harness.services.playerControlsRepository.preferences()
        XCTAssertTrue(persisted.oledBlackModeEnabled)

        try await harness.services.playerControlsRepository.setConsoleColorsEnabled(true)
        await feature.refresh()
        XCTAssertTrue(feature.preferences.consoleColorsEnabled)
    }

    func testLibraryFeatureRendersCachedPlatformsBeforeStaleRefresh() async throws {
        let harness = try ShellFeatureHarness()
        _ = try await harness.seedActiveProfile(connectivity: .online)

        try harness.cachePlatforms([
            PlatformDTO(id: 7, slug: "nes", name: "Nintendo Entertainment System", fsSlug: "nes", romCount: 1)
        ])

        let feature = LibraryFeatureModel(
            services: harness.services,
            resolveProfile: { harness.services.playerControlsRepository.resolveProfile(platformSlug: $0) }
        )

        await feature.refresh(forceRemote: false)

        XCTAssertEqual(feature.platforms.map(\.id), [7])
        XCTAssertEqual(feature.sections.first?.title, "Touch-ready in app")
        XCTAssertFalse(feature.sections.isEmpty)
        XCTAssertNil(feature.staleMessage)
    }

    func testCollectionDetailFeaturePreservesCachedGroupedRomsOnRefreshFailure() async throws {
        let harness = try ShellFeatureHarness()
        _ = try await harness.seedActiveProfile(connectivity: .online)

        let touchRom = harness.makeRom(
            id: 201,
            name: "Galaga",
            platformSlug: "arcade",
            files: [harness.makeFile(id: 1, romID: 201, fileName: "galaga.zip", fileSizeBytes: 128)]
        )
        let controllerRom = harness.makeRom(
            id: 202,
            name: "Ridge Racer",
            platformSlug: "psp",
            files: [harness.makeFile(id: 2, romID: 202, fileName: "ridge.cso", fileSizeBytes: 256)]
        )
        try harness.cacheRoms([touchRom, controllerRom])

        let collection = RommCollectionDTO(
            kind: .regular,
            id: "mixed",
            name: "Mixed",
            description: "",
            romIDs: [201, 202],
            romCount: 2,
            pathCoverSmall: nil,
            pathCoverLarge: nil
        )

        let feature = CollectionDetailFeatureModel(
            collection: collection,
            services: harness.services,
            resolveProfile: { harness.services.playerControlsRepository.resolveProfile(platformSlug: $0) }
        )

        await feature.refresh(forceRemote: false)

        XCTAssertEqual(feature.sections.map(\.title), ["Touch-ready in app", "Controller play in app"])
        XCTAssertEqual(feature.sections.flatMap(\.roms).map(\.id), [201, 202])
        XCTAssertNil(feature.staleMessage)
    }

    func testGameDetailFeatureBuildsActionDeckFromLocalInstallAndDownloadState() async throws {
        let harness = try ShellFeatureHarness()
        let profile = try await harness.seedActiveProfile(connectivity: .online)

        let playableFile = harness.makeFile(id: 11, romID: 301, fileName: "galaga.nes", fileSizeBytes: 32)
        let failedFile = harness.makeFile(id: 12, romID: 301, fileName: "galaga-alt.nes", fileSizeBytes: 32)
        let rom = harness.makeRom(id: 301, name: "Galaga", platformSlug: "nes", files: [playableFile, failedFile])
        try harness.cacheRoms([rom])

        let localURL = harness.libraryStore.romURL(platformSlug: "nes", fileName: playableFile.fileName)
        try FileManager.default.createDirectory(at: localURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data([0x00, 0x01]).write(to: localURL)

        try harness.saveDownloadRecord(
            DownloadRecord(
                profileID: profile.id,
                romID: rom.id,
                fileID: playableFile.id,
                romName: rom.displayName,
                platformSlug: rom.platformSlug,
                fileName: playableFile.fileName,
                fileSizeBytes: playableFile.fileSizeBytes,
                status: .completed,
                progressPercent: 100,
                bytesDownloaded: playableFile.fileSizeBytes,
                totalBytes: playableFile.fileSizeBytes,
                localPath: localURL.path,
                enqueuedAtEpochMS: 1,
                completedAtEpochMS: 2,
                updatedAtEpochMS: 2
            )
        )
        try harness.saveDownloadRecord(
            DownloadRecord(
                profileID: profile.id,
                romID: rom.id,
                fileID: failedFile.id,
                romName: rom.displayName,
                platformSlug: rom.platformSlug,
                fileName: failedFile.fileName,
                fileSizeBytes: failedFile.fileSizeBytes,
                status: .failed,
                progressPercent: 0,
                bytesDownloaded: 0,
                totalBytes: failedFile.fileSizeBytes,
                localPath: nil,
                lastError: "Timeout",
                enqueuedAtEpochMS: 3,
                updatedAtEpochMS: 4
            )
        )

        let runtime = IOSRuntimeProfile(
            runtimeID: "fceumm",
            displayName: "FCEUmm",
            platformSlugs: ["nes"],
            bundleRelativePath: "Cores/fceumm.dylib"
        )
        let catalog = StaticGameDetailCoreCatalog(resolutions: [
            playableFile.id: CoreResolution(
                capability: .ready,
                provisioningStatus: .ready,
                availabilityStatus: .playable,
                runtimeProfile: runtime,
                coreURL: URL(fileURLWithPath: "/tmp/fceumm.dylib"),
                message: "Ready"
            ),
            failedFile.id: CoreResolution(
                capability: .missingCore,
                provisioningStatus: .failedCoreInstall,
                availabilityStatus: .missingBundledCore,
                runtimeProfile: runtime,
                canRetryProvisioning: true,
                message: "Core setup failed."
            ),
        ])

        let feature = GameDetailFeatureModel(
            rom: rom,
            services: harness.services,
            coreCatalog: catalog,
            coreBundle: harness.bundle,
            resolveProfile: { harness.services.playerControlsRepository.resolveProfile(platformSlug: $0) },
            onQueueDownload: { _, _ in },
            onRetryCoreProvisioning: { _, _ in },
            onPlay: { _, _ in },
            onRetryDownload: { _ in },
            onCancelDownload: { _ in },
            onDeleteLocalContent: { _ in }
        )

        await feature.refresh(forceRemote: false)

        let playableState = try XCTUnwrap(feature.fileStates.first(where: { $0.file.id == playableFile.id }))
        XCTAssertEqual(playableState.actionDeck.primary?.kind, .play)
        XCTAssertEqual(playableState.actionDeck.secondary.map(\.kind), [.deleteLocal])
        XCTAssertEqual(feature.selectedFileState?.file.id, playableFile.id)
        XCTAssertEqual(feature.heroPresentation.statusText, "Installed locally")
        XCTAssertTrue(feature.showsSegmentedFileSelection)
        XCTAssertEqual(feature.fileSelectionOptions.map(\.id), [playableFile.id, failedFile.id])
        let selectedPresentation = try XCTUnwrap(feature.selectedFilePresentation)
        XCTAssertEqual(selectedPresentation.title, playableFile.fileName)
        XCTAssertEqual(selectedPresentation.availabilityLabel, "Installed locally")
        XCTAssertEqual(selectedPresentation.playabilityLabel, "Touch")

        let failedState = try XCTUnwrap(feature.fileStates.first(where: { $0.file.id == failedFile.id }))
        XCTAssertEqual(failedState.actionDeck.primary?.kind, .retryCoreSetup)
        XCTAssertEqual(failedState.actionDeck.secondary.map(\.kind), [.retry])
        XCTAssertEqual(failedState.playabilityLabel, "Missing core")

        await feature.selectFile(failedFile.id)
        XCTAssertEqual(feature.selectedFileState?.file.id, failedFile.id)
        XCTAssertEqual(feature.selectedFilePresentation?.title, failedFile.fileName)
        XCTAssertEqual(feature.actionDeckPresentation?.primary?.kind, .retryCoreSetup)
        XCTAssertEqual(feature.actionDeckPresentation?.secondary.map(\.kind), [.retry])
        XCTAssertEqual(feature.actionDeckPresentation?.badge, "Missing core")
    }

    func testGameDetailFeatureSuppressesSegmentedPickerForSingleFileTitles() async throws {
        let harness = try ShellFeatureHarness()
        _ = try await harness.seedActiveProfile(connectivity: .online)

        let rom = harness.makeRom(
            id: 401,
            name: "Metroid",
            platformSlug: "nes",
            files: [harness.makeFile(id: 21, romID: 401, fileName: "metroid.nes", fileSizeBytes: 64)]
        )
        try harness.cacheRoms([rom])

        let runtime = IOSRuntimeProfile(
            runtimeID: "fceumm",
            displayName: "FCEUmm",
            platformSlugs: ["nes"],
            bundleRelativePath: "Cores/fceumm.dylib"
        )
        let catalog = StaticGameDetailCoreCatalog(resolutions: [
            21: CoreResolution(
                capability: .ready,
                provisioningStatus: .ready,
                availabilityStatus: .playable,
                runtimeProfile: runtime,
                coreURL: URL(fileURLWithPath: "/tmp/fceumm.dylib"),
                message: "Ready"
            )
        ])

        let feature = GameDetailFeatureModel(
            rom: rom,
            services: harness.services,
            coreCatalog: catalog,
            coreBundle: harness.bundle,
            resolveProfile: { harness.services.playerControlsRepository.resolveProfile(platformSlug: $0) },
            onQueueDownload: { _, _ in },
            onRetryCoreProvisioning: { _, _ in },
            onPlay: { _, _ in },
            onRetryDownload: { _ in },
            onCancelDownload: { _ in },
            onDeleteLocalContent: { _ in }
        )

        await feature.refresh(forceRemote: false)

        XCTAssertFalse(feature.showsSegmentedFileSelection)
        XCTAssertEqual(feature.fileSelectionOptions.map(\.title), ["NES"])
        XCTAssertEqual(feature.selectedFilePresentation?.title, "metroid.nes")
    }
}

@MainActor
private struct ShellFeatureHarness {
    let root: URL
    let database: AppDatabase
    let secretStore: any SecretStore
    let profileStore: GRDBServerProfileStore
    let offlineStore: GRDBOfflineReadinessStore
    let authController: AuthSessionController
    let libraryRepository: DefaultLibraryRepository
    let downloadQueue: ManagedDownloadQueue
    let libraryStore: AppManagedLibraryStore
    let networkMonitor: NetworkMonitor
    let services: RommioServices
    let bundle: Bundle

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
        networkMonitor = NetworkMonitor()
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
        services = RommioServices(
            database: database,
            secretStore: secretStore,
            profileStore: profileStore,
            offlineStore: offlineStore,
            authController: authController,
            libraryRepository: libraryRepository,
            downloadQueue: downloadQueue,
            libraryStore: libraryStore,
            networkMonitor: networkMonitor,
            controllerMonitor: StaticShellControllerMonitor()
        )
        bundle = Bundle.main
    }

    func seedActiveProfile(connectivity: ConnectivityState) async throws -> ServerProfile {
        let now = ISO8601DateFormatter().string(from: .now)
        let profile = ServerProfile(
            id: UUID().uuidString,
            label: "Test Server",
            baseURL: URL(string: "https://example.com")!,
            edgeAuthMode: .none,
            originAuthMode: .rommBearerPassword,
            capabilities: AuthCapabilities(),
            serverAccess: ServerAccessState(status: .ready, verifiedAt: now),
            sessionState: SessionState(hasOriginSession: true, lastValidatedAt: now),
            isActive: true,
            status: .connected,
            lastValidationAt: now,
            createdAt: now,
            updatedAt: now
        )
        try await profileStore.save(profile, makeActive: true)
        try await offlineStore.save(
            OfflineState(
                connectivity: connectivity,
                activeProfileID: profile.id,
                catalogReady: true,
                mediaReady: true,
                lastFullSyncAtEpochMS: 1,
                lastMediaSyncAtEpochMS: 1,
                cacheBytes: 2048
            ),
            profileID: profile.id
        )
        return profile
    }

    func cacheHome(_ snapshot: HomeSnapshot, profileID: String) throws {
        try database.write { db in
            try db.execute(
                sql: """
                    INSERT INTO home_snapshots (profile_id, payload, updated_at_epoch_ms)
                    VALUES (?, ?, ?)
                    ON CONFLICT(profile_id) DO UPDATE SET
                        payload = excluded.payload,
                        updated_at_epoch_ms = excluded.updated_at_epoch_ms
                    """,
                arguments: [profileID, try database.encoded(snapshot), Int64(Date().timeIntervalSince1970 * 1000)]
            )
        }
    }

    func saveDownloadRecord(_ record: DownloadRecord) throws {
        try database.write { db in
            try db.execute(
                sql: """
                    INSERT INTO download_records (profile_id, rom_id, file_id, payload)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT(profile_id, rom_id, file_id) DO UPDATE SET
                        payload = excluded.payload
                    """,
                arguments: [record.profileID, record.romID, record.fileID, try database.encoded(record)]
            )
        }
    }

    func cachePlatforms(_ platforms: [PlatformDTO]) throws {
        let profileID = try database.read { db in
            try String.fetchOne(db, sql: "SELECT id FROM server_profiles WHERE is_active = 1 LIMIT 1")
        }
        guard let profileID else { return }
        try database.write { db in
            try db.execute(sql: "DELETE FROM cached_platforms WHERE profile_id = ?", arguments: [profileID])
            for platform in platforms {
                try db.execute(
                    sql: "INSERT INTO cached_platforms (profile_id, platform_id, payload, updated_at_epoch_ms) VALUES (?, ?, ?, ?)",
                    arguments: [profileID, platform.id, try database.encoded(platform), Int64(Date().timeIntervalSince1970 * 1000)]
                )
            }
        }
    }

    func cacheRoms(_ roms: [RomDTO]) throws {
        let profileID = try database.read { db in
            try String.fetchOne(db, sql: "SELECT id FROM server_profiles WHERE is_active = 1 LIMIT 1")
        }
        guard let profileID else { return }
        try database.write { db in
            for rom in roms {
                try db.execute(
                    sql: """
                        INSERT INTO cached_roms (profile_id, rom_id, platform_id, payload, updated_at_epoch_ms)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT(profile_id, rom_id) DO UPDATE SET
                            platform_id = excluded.platform_id,
                            payload = excluded.payload,
                            updated_at_epoch_ms = excluded.updated_at_epoch_ms
                        """,
                    arguments: [profileID, rom.id, rom.platformID, try database.encoded(rom), Int64(Date().timeIntervalSince1970 * 1000)]
                )
            }
        }
    }

    func makeRom(id: Int, name: String, platformSlug: String, files: [RomFileDTO] = []) -> RomDTO {
        RomDTO(
            id: id,
            name: name,
            summary: nil,
            platformID: 1,
            platformName: "Nintendo Entertainment System",
            platformSlug: platformSlug,
            fsName: name.lowercased(),
            files: files
        )
    }

    func makeFile(id: Int, romID: Int, fileName: String, fileSizeBytes: Int64) -> RomFileDTO {
        RomFileDTO(
            id: id,
            romID: romID,
            fileName: fileName,
            fileExtension: URL(fileURLWithPath: fileName).pathExtension,
            fileSizeBytes: fileSizeBytes
        )
    }

    func makeCollection(id: String, name: String) -> RommCollectionDTO {
        RommCollectionDTO(
            kind: .regular,
            id: id,
            name: name,
            description: "",
            romIDs: [101],
            romCount: 1,
            pathCoverSmall: nil,
            pathCoverLarge: nil
        )
    }
}

private struct StaticShellControllerMonitor: ControllerMonitoring, @unchecked Sendable {
    func currentControllers() -> [ConnectedController] { [] }
    func updates() -> AsyncStream<[ConnectedController]> {
        AsyncStream { continuation in
            continuation.yield([])
            continuation.finish()
        }
    }
}

private struct StaticGameDetailCoreCatalog: CoreCatalog, Sendable {
    let resolutions: [Int: CoreResolution]

    func allFamilies() -> [IOSRuntimeFamily] { [] }

    func family(for platformSlug: String) -> IOSRuntimeFamily? { nil }

    func resolve(rom: RomDTO, file: RomFileDTO, libraryStore: LibraryStore, bundle: Bundle) -> CoreResolution {
        resolutions[file.id] ?? CoreResolution(
            capability: .unsupported,
            provisioningStatus: .unsupported,
            availabilityStatus: .unsupportedByCurrentBuild,
            message: "Unsupported"
        )
    }
}
