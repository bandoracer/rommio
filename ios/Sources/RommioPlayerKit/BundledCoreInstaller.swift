import Foundation
import RommioFoundation

private enum ProvisioningReceiptStatus: String, Codable, Sendable {
    case ready = "READY"
    case failed = "FAILED"
}

private struct BundledCoreLicenseEntry: Codable, Hashable, Sendable {
    let id: String
    let binaryPath: String
    let licensePath: String
    let importedAt: String?

    enum CodingKeys: String, CodingKey {
        case id
        case binaryPath = "binary_path"
        case licensePath = "license_path"
        case importedAt = "imported_at"
    }
}

public struct BundledCoreInstallationReceipt: Codable, Hashable, Sendable {
    public var runtimeID: String
    public var displayName: String
    public var bundleRelativePath: String
    public var bundleFingerprint: String?
    public var status: String
    public var installedAt: String?
    public var lastFailure: String?

    public init(
        runtimeID: String,
        displayName: String,
        bundleRelativePath: String,
        bundleFingerprint: String?,
        status: String,
        installedAt: String?,
        lastFailure: String?
    ) {
        self.runtimeID = runtimeID
        self.displayName = displayName
        self.bundleRelativePath = bundleRelativePath
        self.bundleFingerprint = bundleFingerprint
        self.status = status
        self.installedAt = installedAt
        self.lastFailure = lastFailure
    }
}

public enum BundledCoreInstallerError: LocalizedError, Sendable {
    case missingBundledCore(String)
    case missingLicenseManifest(String)
    case missingLicenseEntry(String)
    case invalidLicenseEntry(String)
    case failedProvisioning(String)

    public var errorDescription: String? {
        switch self {
        case let .missingBundledCore(runtimeName):
            "This build does not include the bundled \(runtimeName) core."
        case let .missingLicenseManifest(runtimeName):
            "This build is missing license metadata for \(runtimeName)."
        case let .missingLicenseEntry(runtimeName):
            "This build does not include a license manifest entry for \(runtimeName)."
        case let .invalidLicenseEntry(runtimeName):
            "This build has invalid bundled-core metadata for \(runtimeName)."
        case let .failedProvisioning(message):
            message
        }
    }
}

private struct CoreProvisioningInspection: Sendable {
    let status: CoreProvisioningStatus
    let coreURL: URL?
    let message: String
    let provisionedAt: String?
    let lastFailure: String?
}

public protocol CoreInstalling: Sendable {
    func inspect(
        runtime: IOSRuntimeProfile,
        libraryStore: LibraryStore,
        bundle: Bundle
    ) -> CoreInventoryEntry
    func inventory(
        catalog: CoreCatalog,
        libraryStore: LibraryStore,
        bundle: Bundle
    ) -> [CoreInventoryEntry]
    func install(
        runtime: IOSRuntimeProfile,
        libraryStore: LibraryStore,
        bundle: Bundle
    ) throws -> BundledCoreInstallationReceipt
}

public struct BundledCoreInstaller: CoreInstalling, Sendable {
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    public init() {}

    public func inspect(
        runtime: IOSRuntimeProfile,
        libraryStore: LibraryStore,
        bundle: Bundle = .main
    ) -> CoreInventoryEntry {
        let inspection = inspectProvisioning(runtime: runtime, libraryStore: libraryStore, bundle: bundle)
        return CoreInventoryEntry(
            familyID: runtime.runtimeID,
            familyName: runtime.displayName,
            runtimeID: runtime.runtimeID,
            runtimeName: runtime.displayName,
            provisioningStatus: inspection.status,
            availabilityStatus: availabilityStatus(for: runtime, provisioningStatus: inspection.status),
            renderBackend: runtime.renderBackend,
            interactionProfile: runtime.interactionProfile,
            message: inventoryMessage(for: runtime, inspection: inspection),
            provisionedAt: inspection.provisionedAt
        )
    }

    public func inventory(
        catalog: CoreCatalog,
        libraryStore: LibraryStore,
        bundle: Bundle = .main
    ) -> [CoreInventoryEntry] {
        catalog.allFamilies().flatMap { family in
            family.runtimeOptions.map { runtime in
                let inspection = inspectProvisioning(runtime: runtime, libraryStore: libraryStore, bundle: bundle)
                return CoreInventoryEntry(
                    familyID: family.familyID,
                    familyName: family.displayName,
                    runtimeID: runtime.runtimeID,
                    runtimeName: runtime.displayName,
                    provisioningStatus: inspection.status,
                    availabilityStatus: availabilityStatus(for: runtime, provisioningStatus: inspection.status),
                    renderBackend: runtime.renderBackend,
                    interactionProfile: runtime.interactionProfile,
                    message: inventoryMessage(for: runtime, inspection: inspection),
                    provisionedAt: inspection.provisionedAt
                )
            }
        }
    }

    @discardableResult
    public func install(
        runtime: IOSRuntimeProfile,
        libraryStore: LibraryStore,
        bundle: Bundle = .main
    ) throws -> BundledCoreInstallationReceipt {
        let bundledCoreURL = libraryStore.bundledCoreURL(bundleRelativePath: runtime.bundleRelativePath, bundle: bundle)
        guard let bundledCoreURL else {
            throw BundledCoreInstallerError.missingBundledCore(runtime.displayName)
        }

        do {
            _ = try licenseEntry(for: runtime, bundle: bundle)
            let fingerprint = try bundleFingerprint(for: bundledCoreURL)
            let receipt = BundledCoreInstallationReceipt(
                runtimeID: runtime.runtimeID,
                displayName: runtime.displayName,
                bundleRelativePath: runtime.bundleRelativePath,
                bundleFingerprint: fingerprint,
                status: ProvisioningReceiptStatus.ready.rawValue,
                installedAt: ISO8601DateFormatter().string(from: Date()),
                lastFailure: nil
            )
            try libraryStore.ensureRootLayout()
            try writeReceipt(receipt, runtimeID: runtime.runtimeID, libraryStore: libraryStore)
            return receipt
        } catch let error as BundledCoreInstallerError {
            try? writeFailureReceipt(
                runtime: runtime,
                bundledCoreURL: bundledCoreURL,
                libraryStore: libraryStore,
                message: error.localizedDescription
            )
            throw error
        } catch {
            let wrapped = BundledCoreInstallerError.failedProvisioning(
                "Failed to provision \(runtime.displayName): \(error.localizedDescription)"
            )
            try? writeFailureReceipt(
                runtime: runtime,
                bundledCoreURL: bundledCoreURL,
                libraryStore: libraryStore,
                message: wrapped.localizedDescription
            )
            throw wrapped
        }
    }

    private func inspectProvisioning(
        runtime: IOSRuntimeProfile,
        libraryStore: LibraryStore,
        bundle: Bundle
    ) -> CoreProvisioningInspection {
        guard let bundledCoreURL = libraryStore.bundledCoreURL(bundleRelativePath: runtime.bundleRelativePath, bundle: bundle) else {
            return CoreProvisioningInspection(
                status: .missingCoreNotShipped,
                coreURL: nil,
                message: "This build does not include the bundled \(runtime.displayName) core.",
                provisionedAt: nil,
                lastFailure: nil
            )
        }

        do {
            _ = try licenseEntry(for: runtime, bundle: bundle)
        } catch {
            return CoreProvisioningInspection(
                status: .failedCoreInstall,
                coreURL: nil,
                message: error.localizedDescription,
                provisionedAt: nil,
                lastFailure: error.localizedDescription
            )
        }

        let bundleFingerprint = try? bundleFingerprint(for: bundledCoreURL)
        let receipt = validatedReceipt(
            runtime: runtime,
            bundleFingerprint: bundleFingerprint,
            libraryStore: libraryStore
        )

        if let receipt {
            if receipt.status == ProvisioningReceiptStatus.ready.rawValue {
                return CoreProvisioningInspection(
                    status: .ready,
                    coreURL: bundledCoreURL,
                    message: "\(runtime.displayName) is provisioned and ready.",
                    provisionedAt: receipt.installedAt,
                    lastFailure: nil
                )
            }
            return CoreProvisioningInspection(
                status: .failedCoreInstall,
                coreURL: nil,
                message: receipt.lastFailure ?? "Core setup failed. Retry core setup.",
                provisionedAt: receipt.installedAt,
                lastFailure: receipt.lastFailure
            )
        }

        return CoreProvisioningInspection(
            status: .missingCoreInstallable,
            coreURL: nil,
            message: "\(runtime.displayName) will be set up automatically when needed.",
            provisionedAt: nil,
            lastFailure: nil
        )
    }

    private func validatedReceipt(
        runtime: IOSRuntimeProfile,
        bundleFingerprint: String?,
        libraryStore: LibraryStore
    ) -> BundledCoreInstallationReceipt? {
        let receiptURL = libraryStore.coreInstallReceiptURL(runtimeID: runtime.runtimeID)
        guard FileManager.default.fileExists(atPath: receiptURL.path) else {
            return nil
        }
        guard let data = try? Data(contentsOf: receiptURL),
              let receipt = try? decoder.decode(BundledCoreInstallationReceipt.self, from: data) else {
            try? FileManager.default.removeItem(at: receiptURL)
            return nil
        }

        if receipt.bundleRelativePath != runtime.bundleRelativePath || receipt.bundleFingerprint != bundleFingerprint {
            try? FileManager.default.removeItem(at: receiptURL)
            return nil
        }

        return receipt
    }

    private func writeFailureReceipt(
        runtime: IOSRuntimeProfile,
        bundledCoreURL: URL?,
        libraryStore: LibraryStore,
        message: String
    ) throws {
        let fingerprint = try bundledCoreURL.flatMap(bundleFingerprint(for:))
        let receipt = BundledCoreInstallationReceipt(
            runtimeID: runtime.runtimeID,
            displayName: runtime.displayName,
            bundleRelativePath: runtime.bundleRelativePath,
            bundleFingerprint: fingerprint,
            status: ProvisioningReceiptStatus.failed.rawValue,
            installedAt: nil,
            lastFailure: message
        )
        try libraryStore.ensureRootLayout()
        try writeReceipt(receipt, runtimeID: runtime.runtimeID, libraryStore: libraryStore)
    }

    private func writeReceipt(
        _ receipt: BundledCoreInstallationReceipt,
        runtimeID: String,
        libraryStore: LibraryStore
    ) throws {
        let data = try encoder.encode(receipt)
        try data.write(to: libraryStore.coreInstallReceiptURL(runtimeID: runtimeID), options: .atomic)
    }

    private func licenseEntry(
        for runtime: IOSRuntimeProfile,
        bundle: Bundle
    ) throws -> BundledCoreLicenseEntry {
        guard let manifestURL = bundleResourceURL(for: "Cores/CoreLicenses.json", bundle: bundle) else {
            throw BundledCoreInstallerError.missingLicenseManifest(runtime.displayName)
        }
        let manifestData = try Data(contentsOf: manifestURL)
        let entries = try decoder.decode([BundledCoreLicenseEntry].self, from: manifestData)
        guard let entry = entries.first(where: { $0.id == runtime.runtimeID }) else {
            throw BundledCoreInstallerError.missingLicenseEntry(runtime.displayName)
        }
        guard entry.binaryPath == runtime.bundleRelativePath else {
            throw BundledCoreInstallerError.invalidLicenseEntry(runtime.displayName)
        }

        guard bundleResourceURL(for: entry.binaryPath, bundle: bundle) != nil else {
            throw BundledCoreInstallerError.missingBundledCore(runtime.displayName)
        }
        guard bundleResourceURL(for: entry.licensePath, bundle: bundle) != nil else {
            throw BundledCoreInstallerError.invalidLicenseEntry(runtime.displayName)
        }

        return entry
    }

    private func bundleResourceURL(for relativePath: String, bundle: Bundle) -> URL? {
        let candidatePaths = [
            relativePath,
            relativePath.split(separator: "/").last.map(String.init),
        ].compactMap { $0 }

        for path in candidatePaths {
            let candidate = bundle.bundleURL.appending(path: path)
            if FileManager.default.fileExists(atPath: candidate.path) {
                return candidate
            }
        }

        return nil
    }

    private func bundleFingerprint(for url: URL) throws -> String {
        let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
        let size = (attributes[.size] as? NSNumber)?.int64Value ?? 0
        let modifiedAt = (attributes[.modificationDate] as? Date)?.timeIntervalSince1970 ?? 0
        return "\(url.lastPathComponent):\(size):\(Int64(modifiedAt))"
    }

    private func availabilityStatus(
        for runtime: IOSRuntimeProfile,
        provisioningStatus: CoreProvisioningStatus
    ) -> RuntimeAvailabilityStatus {
        if runtime.validationState == .blockedByValidation {
            return .blockedByValidation
        }

        switch provisioningStatus {
        case .missingCoreNotShipped:
            return .missingBundledCore
        case .missingBIOS:
            return .missingBIOS
        case .unsupported:
            return .unsupportedByCurrentBuild
        case .ready, .missingCoreInstallable, .installingCore, .failedCoreInstall:
            return .playable
        }
    }

    private func inventoryMessage(
        for runtime: IOSRuntimeProfile,
        inspection: CoreProvisioningInspection
    ) -> String {
        if runtime.validationState == .blockedByValidation {
            return runtime.validationBlockReason ?? "\(runtime.displayName) is tracked in the iOS catalog but blocked by validation."
        }
        return inspection.message
    }
}
