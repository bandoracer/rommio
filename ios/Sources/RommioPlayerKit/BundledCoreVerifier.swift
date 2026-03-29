import Foundation
import RommioContract
import RommioFoundation

public struct BundledCoreVerificationIssue: Hashable, Sendable {
    public enum Severity: String, Hashable, Sendable {
        case missingCore = "MISSING_CORE"
        case invalidPackagingPolicy = "INVALID_PACKAGING_POLICY"
        case missingLicenseManifest = "MISSING_LICENSE_MANIFEST"
        case missingLicenseEntry = "MISSING_LICENSE_ENTRY"
        case invalidLicenseEntry = "INVALID_LICENSE_ENTRY"
    }

    public var runtimeID: String
    public var bundleRelativePath: String
    public var severity: Severity
    public var message: String

    public init(
        runtimeID: String,
        bundleRelativePath: String,
        severity: Severity,
        message: String
    ) {
        self.runtimeID = runtimeID
        self.bundleRelativePath = bundleRelativePath
        self.severity = severity
        self.message = message
    }
}

public struct BundledCoreVerificationReport: Hashable, Sendable {
    public var issues: [BundledCoreVerificationIssue]

    public init(issues: [BundledCoreVerificationIssue] = []) {
        self.issues = issues
    }

    public var isPlayable: Bool {
        issues.isEmpty
    }
}

public struct BundledCoreVerifier: Sendable {
    public init() {}

    public func verify(
        catalog: CoreCatalog,
        libraryStore: LibraryStore,
        bundle: Bundle = .main
    ) -> BundledCoreVerificationReport {
        let verificationIssues = catalog
            .allFamilies()
            .flatMap(\.runtimeOptions)
            .flatMap { runtime in
                issues(for: runtime, libraryStore: libraryStore, bundle: bundle)
            }
        return BundledCoreVerificationReport(issues: verificationIssues)
    }

    private func issues(
        for runtime: IOSRuntimeProfile,
        libraryStore: LibraryStore,
        bundle: Bundle
    ) -> [BundledCoreVerificationIssue] {
        var issues: [BundledCoreVerificationIssue] = []
        let licenseManifest = loadLicenseManifest(bundle: bundle)

        if runtime.packagingPolicy != .bundledSignedDynamicLibrary {
            issues.append(
                BundledCoreVerificationIssue(
                    runtimeID: runtime.runtimeID,
                    bundleRelativePath: runtime.bundleRelativePath,
                    severity: .invalidPackagingPolicy,
                    message: "The runtime does not use the bundled signed core policy."
                )
            )
        }

        switch licenseManifest {
        case .missingManifest:
            issues.append(
                BundledCoreVerificationIssue(
                    runtimeID: runtime.runtimeID,
                    bundleRelativePath: runtime.bundleRelativePath,
                    severity: .missingLicenseManifest,
                    message: "The application bundle does not include Cores/CoreLicenses.json."
                )
            )
        case let .invalidManifest(errorDescription):
            issues.append(
                BundledCoreVerificationIssue(
                    runtimeID: runtime.runtimeID,
                    bundleRelativePath: runtime.bundleRelativePath,
                    severity: .invalidLicenseEntry,
                    message: "The bundled core license manifest is invalid: \(errorDescription)"
                )
            )
        case let .loaded(entries):
            guard let entry = entries[runtime.runtimeID] else {
                issues.append(
                    BundledCoreVerificationIssue(
                        runtimeID: runtime.runtimeID,
                        bundleRelativePath: runtime.bundleRelativePath,
                        severity: .missingLicenseEntry,
                        message: "The bundled core license manifest is missing an entry for \(runtime.runtimeID)."
                    )
                )
                break
            }

            if entry.binaryPath != runtime.bundleRelativePath {
                issues.append(
                    BundledCoreVerificationIssue(
                        runtimeID: runtime.runtimeID,
                        bundleRelativePath: runtime.bundleRelativePath,
                        severity: .invalidLicenseEntry,
                        message: "The bundled core license manifest points \(runtime.runtimeID) at \(entry.binaryPath) instead of \(runtime.bundleRelativePath)."
                    )
                )
            }

            if bundleResourceURL(for: entry.licensePath, bundle: bundle) == nil {
                issues.append(
                    BundledCoreVerificationIssue(
                        runtimeID: runtime.runtimeID,
                        bundleRelativePath: runtime.bundleRelativePath,
                        severity: .invalidLicenseEntry,
                        message: "The bundled core license file \(entry.licensePath) is missing from the application bundle."
                    )
                )
            }
        }

        if libraryStore.bundledCoreURL(bundleRelativePath: runtime.bundleRelativePath, bundle: bundle) == nil {
            issues.append(
                BundledCoreVerificationIssue(
                    runtimeID: runtime.runtimeID,
                    bundleRelativePath: runtime.bundleRelativePath,
                    severity: .missingCore,
                    message: "The core is not present in the application bundle."
                )
            )
        }

        return issues
    }

    private enum LicenseManifestLoadResult: Sendable {
        case loaded([String: LicenseManifestEntry])
        case missingManifest
        case invalidManifest(String)
    }

    private struct LicenseManifestEntry: Codable, Hashable, Sendable {
        var id: String
        var binaryPath: String
        var licensePath: String

        enum CodingKeys: String, CodingKey {
            case id
            case binaryPath = "binary_path"
            case licensePath = "license_path"
        }
    }

    private func loadLicenseManifest(bundle: Bundle) -> LicenseManifestLoadResult {
        guard let manifestURL = bundleResourceURL(for: "Cores/CoreLicenses.json", bundle: bundle) else {
            return .missingManifest
        }

        do {
            let data = try Data(contentsOf: manifestURL)
            let entries = try JSONDecoder().decode([LicenseManifestEntry].self, from: data)
            let mapped = Dictionary(uniqueKeysWithValues: entries.map { ($0.id, $0) })
            return .loaded(mapped)
        } catch {
            return .invalidManifest(error.localizedDescription)
        }
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
}
