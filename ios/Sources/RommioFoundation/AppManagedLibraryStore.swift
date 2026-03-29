import Foundation
import RommioContract

public protocol LibraryStore: Sendable {
    var rootDirectory: URL { get }
    func ensureRootLayout() throws
    func romDirectory(platformSlug: String) -> URL
    func romURL(platformSlug: String, fileName: String) -> URL
    func saveRAMURL(for installation: InstalledROMReference) -> URL
    func saveStatesDirectory(for installation: InstalledROMReference) -> URL
    func continuityResumeStateURL(for installation: InstalledROMReference) -> URL
    func saveStateURL(for installation: InstalledROMReference, slot: Int) -> URL
    func legacySaveStateURL(for installation: InstalledROMReference, slot: Int) -> URL
    func syncManifestURL(for installation: InstalledROMReference) -> URL
    func autoSnapshotURL(for installation: InstalledROMReference, ringIndex: Int) -> URL
    func screenshotsDirectory(for installation: InstalledROMReference) -> URL
    func systemDirectory() -> URL
    func biosDirectory() -> URL
    func coresDirectory() -> URL
    func coreInstallReceiptURL(runtimeID: String) -> URL
    func bundledCoreURL(bundleRelativePath: String, bundle: Bundle) -> URL?
}

public struct AppManagedLibraryStore: LibraryStore, Sendable {
    public let rootDirectory: URL

    public init(rootDirectory: URL) {
        self.rootDirectory = rootDirectory
    }

    public func ensureRootLayout() throws {
        try requiredDirectories().forEach { url in
            try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        }
    }

    public func romDirectory(platformSlug: String) -> URL {
        rootDirectory.appending(path: "roms").appending(path: platformSlug)
    }

    public func romURL(platformSlug: String, fileName: String) -> URL {
        romDirectory(platformSlug: platformSlug).appending(path: fileName)
    }

    public func saveRAMURL(for installation: InstalledROMReference) -> URL {
        rootDirectory.appending(path: "saves").appending(path: "\(installation.romID)_\(installation.fileID).srm")
    }

    public func saveStatesDirectory(for installation: InstalledROMReference) -> URL {
        rootDirectory.appending(path: "states").appending(path: String(installation.romID))
    }

    public func continuityResumeStateURL(for installation: InstalledROMReference) -> URL {
        saveStatesDirectory(for: installation).appending(path: "__rommio_resume_\(installation.romID)_\(installation.fileID).state")
    }

    public func saveStateURL(for installation: InstalledROMReference, slot: Int) -> URL {
        saveStatesDirectory(for: installation).appending(path: "\(installation.romID)_slot\(slot).state")
    }

    public func legacySaveStateURL(for installation: InstalledROMReference, slot: Int) -> URL {
        saveStatesDirectory(for: installation).appending(path: "\(installation.fileID)_slot\(slot).state")
    }

    public func syncManifestURL(for installation: InstalledROMReference) -> URL {
        saveStatesDirectory(for: installation).appending(path: "__rommio_sync_\(installation.romID)_\(installation.fileID).json")
    }

    public func autoSnapshotURL(for installation: InstalledROMReference, ringIndex: Int) -> URL {
        saveStatesDirectory(for: installation).appending(path: "\(installation.romID)_recovery_auto_\(ringIndex).state")
    }

    public func screenshotsDirectory(for installation: InstalledROMReference) -> URL {
        rootDirectory.appending(path: "screenshots").appending(path: String(installation.romID))
    }

    public func systemDirectory() -> URL {
        rootDirectory.appending(path: "system")
    }

    public func biosDirectory() -> URL {
        rootDirectory.appending(path: "bios")
    }

    public func coresDirectory() -> URL {
        rootDirectory.appending(path: "cores")
    }

    public func coreInstallReceiptURL(runtimeID: String) -> URL {
        coresDirectory().appending(path: "\(runtimeID).installed.json")
    }

    public func bundledCoreURL(bundleRelativePath: String, bundle: Bundle = .main) -> URL? {
        let candidatePaths = [
            bundleRelativePath,
            bundleRelativePath.split(separator: "/").last.map(String.init),
        ].compactMap { $0 }

        for path in candidatePaths {
            let candidate = bundle.bundleURL.appending(path: path)
            if FileManager.default.fileExists(atPath: candidate.path) {
                return candidate
            }
        }

        return nil
    }

    private func requiredDirectories() -> [URL] {
        [
            rootDirectory,
            rootDirectory.appending(path: "roms"),
            rootDirectory.appending(path: "saves"),
            rootDirectory.appending(path: "states"),
            rootDirectory.appending(path: "screenshots"),
            rootDirectory.appending(path: "bios"),
            rootDirectory.appending(path: "cores"),
            rootDirectory.appending(path: "system"),
        ]
    }
}
