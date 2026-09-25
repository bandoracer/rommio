import Foundation
import Synchronization
import RommioContract
import RommioFoundation
import RommioPlayerKit

/// Disk-heavy work pages need — the emulator core inventory (inspects every core on disk)
/// and the library's size (walks the whole library folder) — runs off the main thread;
/// the main actor only receives the results.
enum BackgroundScans {
    static func inventory(
        installer: any CoreInstalling,
        catalog: any CoreCatalog,
        libraryStore: AppManagedLibraryStore,
        bundle: Bundle
    ) async -> [CoreInventoryEntry] {
        await Task.detached(priority: .utility) {
            installer.inventory(catalog: catalog, libraryStore: libraryStore, bundle: bundle)
        }.value
    }

    /// Verifies every bundled core once in the background (see `BundledCoreVerificationCache`),
    /// so the first Settings, Downloads or Platform page doesn't pay for hashing the cores.
    static func warmCoreVerification(
        installer: any CoreInstalling,
        catalog: any CoreCatalog,
        libraryStore: AppManagedLibraryStore,
        bundle: Bundle
    ) {
        Task.detached(priority: .background) {
            _ = installer.inventory(catalog: catalog, libraryStore: libraryStore, bundle: bundle)
        }
    }

    static func storageSummary(
        libraryStore: AppManagedLibraryStore,
        downloads: [DownloadRecord],
        offlineState: OfflineState
    ) async -> LocalStorageSummary {
        await Task.detached(priority: .utility) {
            localStorageSummary(libraryStore: libraryStore, downloads: downloads, offlineState: offlineState)
        }.value
    }
}

/// The library folder's size, remembered while the set of installed files is unchanged:
/// Play, Settings and Downloads all ask for it, and walking the folder is slow on a phone.
enum LibrarySizeCache {
    private struct Entry {
        let key: String
        let bytes: Int64
        let measuredAt: Date
    }

    private static let entry = Mutex<Entry?>(nil)
    private static let maxAge: TimeInterval = 120

    static func size(of root: URL, installedPaths: [String], measure: (URL) -> Int64) -> Int64 {
        let key = root.path + "\u{1F}" + installedPaths.sorted().joined(separator: "\u{1F}")
        if let hit = entry.withLock({ $0 }), hit.key == key, Date().timeIntervalSince(hit.measuredAt) < maxAge {
            return hit.bytes
        }
        let bytes = measure(root)
        entry.withLock { $0 = Entry(key: key, bytes: bytes, measuredAt: Date()) }
        return bytes
    }

    /// Test support.
    static func reset() { entry.withLock { $0 = nil } }
}
