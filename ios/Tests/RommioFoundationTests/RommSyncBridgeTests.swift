import XCTest
@testable import RommioContract
@testable import RommioFoundation

final class RommSyncBridgeTests: XCTestCase {
    func testLegacySyncRetainsLocalBytesAndPendingIntentWithoutAcknowledgingPublication() async throws {
        let h = try Harness()
        defer { h.remove() }
        try Data("save".utf8).write(to: h.store.saveRAMURL(for: installation))
        let state = h.store.saveStateURL(for: installation, slot: 1)
        try FileManager.default.createDirectory(at: state.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data("state".utf8).write(to: state)
        try h.bridge.recordManualState(profileID: "profile", installation: installation, slot: 1, fileURL: state, deviceID: "device")
        var journal = GameSyncJournal(profileID: "profile", romID: 42, fileID: 7)
        journal.lastSuccessfulSyncAtEpochMS = 123
        journal.lastSyncedSRAMHash = "old-hash"
        try h.database.upsertGameSyncJournal(journal)
        for includeSlots in [false, true] {
            do {
                _ = try await h.bridge.syncGame(profileID: "profile", installation: installation, rom: rom, runtimeID: "snes9x",
                    remoteBaseURL: URL(string: "https://must-not-be-contacted.invalid")!, deviceID: "device", includeManualSlots: includeSlots)
                XCTFail("Legacy publication must report unavailable, never success")
            } catch is LegacySyncUnavailableError {}
        }
        let retained = try XCTUnwrap(h.database.gameSyncJournal(profileID: "profile", romID: 42, fileID: 7))
        XCTAssertTrue(retained.pendingContinuityUpload)
        XCTAssertEqual(retained.lastSuccessfulSyncAtEpochMS, 123)
        XCTAssertEqual(retained.lastSyncedSRAMHash, "old-hash")
        XCTAssertEqual(try Data(contentsOf: h.store.saveRAMURL(for: installation)), Data("save".utf8))
        XCTAssertEqual(try Data(contentsOf: state), Data("state".utf8))
        XCTAssertEqual(try h.database.saveStateSyncJournal(profileID: "profile", romID: 42, fileID: 7, slot: 1)?.pendingUpload, true)
    }

    func testOnlineLaunchAndRefreshNeverResurrectTombstoneOrErasePendingUpload() async throws {
        let h = try Harness()
        defer { h.remove() }
        let slot = h.store.saveStateURL(for: installation, slot: 1)
        try FileManager.default.createDirectory(at: slot.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data("manual".utf8).write(to: slot)
        try h.bridge.recordManualState(profileID: "profile", installation: installation, slot: 1, fileURL: slot, deviceID: "device")
        let browser = try h.bridge.buildStateBrowser(profileID: "profile", installation: installation, connectivity: .online)
        try h.bridge.deleteBrowsableState(profileID: "profile", installation: installation, state: XCTUnwrap(browser.saveSlots.first))
        // Simulate interruption after the tombstone commit, before unlinking the old file.
        try Data("stale-file".utf8).write(to: slot)
        let other = h.store.saveStateURL(for: installation, slot: 2)
        try Data("new-slot".utf8).write(to: other)
        try h.bridge.recordManualState(profileID: "profile", installation: installation, slot: 2, fileURL: other, deviceID: "device")
        let resume = h.store.continuityResumeStateURL(for: installation)
        try Data("latest-resume".utf8).write(to: resume)
        let launch = try await h.bridge.preparePlayerEntry(profileID: "profile", installation: installation, rom: rom, runtimeID: "snes9x",
            remoteBaseURL: URL(string: "https://must-not-be-contacted.invalid")!, deviceID: nil, connectivity: .online)
        let refreshed = try await h.bridge.refreshStateBrowser(profileID: "profile", installation: installation, rom: rom, runtimeID: "snes9x",
            remoteBaseURL: URL(string: "https://must-not-be-contacted.invalid")!, deviceID: nil, connectivity: .online)
        XCTAssertEqual(launch.launchTarget?.localStatePath, resume.path)
        XCTAssertEqual(launch.syncPresentation.kind, .localOnly)
        XCTAssertEqual(refreshed.saveSlots.map(\.slot), [2])
        XCTAssertEqual(refreshed.resume?.statusKind, .localOnly)
        let deleted = try XCTUnwrap(h.database.saveStateSyncJournal(profileID: "profile", romID: 42, fileID: 7, slot: 1))
        XCTAssertTrue(deleted.deleted)
        XCTAssertTrue(deleted.pendingDelete)
        XCTAssertEqual(try h.database.saveStateSyncJournal(profileID: "profile", romID: 42, fileID: 7, slot: 2)?.pendingUpload, true)
        XCTAssertEqual(try Data(contentsOf: resume), Data("latest-resume".utf8))
    }

    func testLegacyAliasImportPreservesDifferingOriginals() throws {
        let h = try Harness()
        defer { h.remove() }
        let canonical = h.store.saveStateURL(for: installation, slot: 1)
        try FileManager.default.createDirectory(at: canonical.deletingLastPathComponent(), withIntermediateDirectories: true)
        let alias = canonical.deletingLastPathComponent().appending(path: "7_slot1.state")
        try Data("original".utf8).write(to: alias)
        try Data("different".utf8).write(to: canonical)
        _ = try h.bridge.buildStateBrowser(profileID: "profile", installation: installation, connectivity: .offline)
        XCTAssertEqual(try Data(contentsOf: alias), Data("original".utf8))
        XCTAssertEqual(try Data(contentsOf: canonical), Data("different".utf8))
    }

    private let installation = InstalledROMReference(romID: 42, fileID: 7, platformSlug: "snes", romName: "Fixture", fileName: "fixture.sfc")
    private let rom = RomDTO(id: 42, name: "Fixture", platformID: 1, platformName: "SNES", platformSlug: "snes", fsName: "fixture.sfc")
}

private struct Harness {
    let root: URL
    let store: AppManagedLibraryStore
    let database: AppDatabase
    let bridge: RommSyncBridge
    init() throws {
        root = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        store = AppManagedLibraryStore(rootDirectory: root)
        try store.ensureRootLayout()
        database = try AppDatabase(rootDirectory: root.appending(path: "Database"))
        bridge = RommSyncBridge(libraryStore: store, database: database, deviceName: "Fixture")
    }
    func remove() { try? FileManager.default.removeItem(at: root) }
}
