import Foundation
import XCTest
@testable import RommioContract
@testable import RommioFoundation

final class AuthLiveSmokeTests: XCTestCase {
    func testCloudflareServiceTokenPasswordGrantConnectsAndResumes() async throws {
        let env = try DebugTestEnvironment.loadOrSkip()
        let root = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("rommio-live-smoke-\(UUID().uuidString)", isDirectory: true)

        let harness = try LiveHarness(rootDirectory: root)

        let discovery = try await harness.authController.discoverServer(rawBaseURL: env.baseURL)
        XCTAssertTrue(discovery.capabilities.cloudflareAccessDetected, "Expected Cloudflare Access detection.")
        XCTAssertEqual(discovery.recommendedOriginAuthMode, .rommBearerPassword)

        var profile = try await harness.authController.configureProfile(
            baseURL: env.baseURL,
            edgeAuthMode: .cloudflareAccessService,
            originAuthMode: .rommBearerPassword,
            discoveryResult: discovery,
            makeActive: true
        )

        try await harness.authController.logout(profileID: profile.id, clearServerAccess: true)

        profile = try await harness.authController.configureProfile(
            baseURL: env.baseURL,
            edgeAuthMode: .cloudflareAccessService,
            originAuthMode: .rommBearerPassword,
            discoveryResult: discovery,
            makeActive: true
        )

        try await harness.authController.saveCloudflareCredentials(
            profileID: profile.id,
            credentials: CloudflareServiceCredentials(
                clientID: env.clientID,
                clientSecret: env.clientSecret
            )
        )

        let accessResult = try await harness.authController.testServerAccess(profileID: profile.id)
        XCTAssertEqual(accessResult.status, .ready, accessResult.message)

        try await harness.authController.loginWithDirectCredentials(
            profileID: profile.id,
            credentials: DirectLoginCredentials(
                username: env.username,
                password: env.password
            )
        )

        let connectedStatus = try await harness.authController.validateProfile(profileID: profile.id)
        XCTAssertEqual(connectedStatus, .connected)

        try await harness.libraryRepository.refreshActiveProfile(force: true)
        _ = try await harness.libraryRepository.loadHome()
        _ = try await harness.libraryRepository.loadPlatforms()

        let restartedHarness = try LiveHarness(rootDirectory: root)
        let activeProfile = try await restartedHarness.profileStore.activeProfile()
        let resumedProfile = try XCTUnwrap(activeProfile)
        XCTAssertEqual(resumedProfile.serverAccess.status, .ready)
        let resumedStatus = try await restartedHarness.authController.validateProfile(profileID: resumedProfile.id)
        XCTAssertEqual(resumedStatus, .connected)
    }
}

private struct DebugTestEnvironment {
    let baseURL: String
    let clientID: String
    let clientSecret: String
    let username: String
    let password: String

    static func loadOrSkip(
        environment: [String: String] = ProcessInfo.processInfo.environment
    ) throws -> DebugTestEnvironment {
        guard environment["SIMULATOR_DEVICE_NAME"] != nil else {
            throw XCTSkip("Live auth smoke tests run only on the iOS Simulator.")
        }

        let fileValues = mergedFileValues(environment: environment)
        func value(for key: String) -> String {
            let candidate = environment[key] ?? fileValues[key] ?? ""
            return candidate.trimmingCharacters(in: .whitespacesAndNewlines)
        }

        let configuration = DebugTestEnvironment(
            baseURL: value(for: "ROMM_TEST_BASE_URL"),
            clientID: value(for: "ROMM_TEST_CLIENT_ID"),
            clientSecret: value(for: "ROMM_TEST_CLIENT_SECRET"),
            username: value(for: "ROMM_TEST_USERNAME"),
            password: value(for: "ROMM_TEST_PASSWORD")
        )

        guard !configuration.baseURL.isEmpty else {
            throw XCTSkip("ROMM_TEST_BASE_URL is required for live auth tests.")
        }
        guard !configuration.clientID.isEmpty else {
            throw XCTSkip("ROMM_TEST_CLIENT_ID is required for live auth tests.")
        }
        guard !configuration.clientSecret.isEmpty else {
            throw XCTSkip("ROMM_TEST_CLIENT_SECRET is required for live auth tests.")
        }
        guard !configuration.username.isEmpty else {
            throw XCTSkip("ROMM_TEST_USERNAME is required for live auth tests.")
        }
        guard !configuration.password.isEmpty else {
            throw XCTSkip("ROMM_TEST_PASSWORD is required for live auth tests.")
        }

        return configuration
    }

    private static func mergedFileValues(environment: [String: String]) -> [String: String] {
        let explicitEnvFile = environment["ROMMIO_ENV_FILE"]
            .map { URL(fileURLWithPath: $0) }

        let candidateFiles = [
            explicitEnvFile,
            URL(fileURLWithPath: FileManager.default.currentDirectoryPath).appending(path: ".env.test.local"),
            repoRoot().appending(path: ".env.test.local"),
        ].compactMap { $0 }

        return candidateFiles.reduce(into: [:]) { result, url in
            result.merge(parseDotEnv(at: url), uniquingKeysWith: { current, _ in current })
        }
    }

    private static func repoRoot(filePath: String = #filePath) -> URL {
        URL(fileURLWithPath: filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
    }

    private static func parseDotEnv(at url: URL) -> [String: String] {
        guard let contents = try? String(contentsOf: url, encoding: .utf8) else {
            return [:]
        }

        return contents
            .split(whereSeparator: \.isNewline)
            .reduce(into: [:]) { partialResult, rawLine in
                let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !line.isEmpty, !line.hasPrefix("#"), let separator = line.firstIndex(of: "=") else {
                    return
                }
                let key = String(line[..<separator]).trimmingCharacters(in: .whitespacesAndNewlines)
                let value = String(line[line.index(after: separator)...]).trimmingCharacters(in: .whitespacesAndNewlines)
                partialResult[key] = value
            }
    }
}

private struct LiveHarness {
    let profileStore: GRDBServerProfileStore
    let authController: AuthSessionController
    let libraryRepository: DefaultLibraryRepository

    init(rootDirectory: URL) throws {
        let database = try AppDatabase(rootDirectory: rootDirectory.appending(path: "Database"))
        let profileStore = GRDBServerProfileStore(database: database)
        let offlineStore = GRDBOfflineReadinessStore(database: database)
        let secretStore = try FileSecretStore(rootDirectory: rootDirectory.appending(path: "Secrets"))
        let authController = AuthSessionController(
            profileStore: profileStore,
            secretStore: secretStore
        )
        self.profileStore = profileStore
        self.authController = authController
        self.libraryRepository = DefaultLibraryRepository(
            database: database,
            profileStore: profileStore,
            offlineStore: offlineStore,
            authController: authController
        )
    }
}
