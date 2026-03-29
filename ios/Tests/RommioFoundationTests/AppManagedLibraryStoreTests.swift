import XCTest
@testable import RommioContract
@testable import RommioFoundation

final class AppManagedLibraryStoreTests: XCTestCase {
    func testLibraryStoreCreatesStableLayout() throws {
        let temporaryRoot = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        let store = AppManagedLibraryStore(rootDirectory: temporaryRoot)

        try store.ensureRootLayout()

        XCTAssertTrue(FileManager.default.fileExists(atPath: store.romDirectory(platformSlug: "snes").deletingLastPathComponent().path))
        XCTAssertEqual(
            store.romURL(platformSlug: "snes", fileName: "smw.sfc").path,
            temporaryRoot.appending(path: "roms").appending(path: "snes").appending(path: "smw.sfc").path
        )
        XCTAssertEqual(store.coresDirectory().lastPathComponent, "cores")
        XCTAssertEqual(store.biosDirectory().lastPathComponent, "bios")
    }

    func testSaveStatePathsMirrorAndroidLayout() {
        let temporaryRoot = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        let store = AppManagedLibraryStore(rootDirectory: temporaryRoot)
        let installation = InstalledROMReference(
            romID: 99,
            fileID: 7,
            platformSlug: "snes",
            romName: "Super Mario World",
            fileName: "smw.sfc"
        )

        XCTAssertEqual(store.saveRAMURL(for: installation).lastPathComponent, "99_7.srm")
        XCTAssertEqual(store.saveStateURL(for: installation, slot: 3).lastPathComponent, "99_slot3.state")
    }
}
