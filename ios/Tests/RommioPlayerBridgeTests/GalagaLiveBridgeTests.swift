import Foundation
import XCTest
@testable import RommioContract
@testable import RommioFoundation
@testable import RommioPlayerBridge

final class GalagaLiveBridgeTests: XCTestCase {
    func testBundledFCEUMMCanBootLiveGalagaOnSimulator() async throws {
        let env = try LiveBridgeEnvironment.loadOrSkip()
        let workspace = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("rommio-galaga-bridge-\(UUID().uuidString)", isDirectory: true)
        let harness = try LiveBridgeHarness(rootDirectory: workspace)

        let discovery = try await harness.authController.discoverServer(rawBaseURL: env.baseURL)
        XCTAssertEqual(discovery.recommendedOriginAuthMode, .rommBearerPassword)

        var profile = try await harness.authController.configureProfile(
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
        let profileStatus = try await harness.authController.validateProfile(profileID: profile.id)
        XCTAssertEqual(profileStatus, .connected)

        let activeProfile = try await harness.profileStore.activeProfile()
        profile = try XCTUnwrap(activeProfile)
        let client = await harness.authController.client(for: profile.id, baseURL: profile.baseURL)
        let rom = try await client.getRom(id: 3)
        XCTAssertEqual(rom.platformSlug, "nes")

        let galagaFile = try XCTUnwrap(rom.files.first { $0.fileName.localizedCaseInsensitiveContains("Galaga") })
        let romURL = workspace
            .appendingPathComponent("roms", isDirectory: true)
            .appendingPathComponent(galagaFile.fileName)
        try await client.download(
            from: buildRomContentURL(baseURL: profile.baseURL, romID: rom.id, fileName: galagaFile.fileName),
            to: romURL
        )
        XCTAssertTrue(FileManager.default.fileExists(atPath: romURL.path))

        let coreURL = try liveCoreURL(named: "fceumm")
        let boot = try await withTimeout(seconds: 20) {
            try await Task.detached(priority: .userInitiated) { () throws -> LiveGalagaBootResult in
                let bridge = try RMLLibretroBridge(coreURL: coreURL)
                try bridge.prepareGame(
                    at: romURL,
                    systemDirectory: workspace.appendingPathComponent("system", isDirectory: true),
                    savesDirectory: workspace.appendingPathComponent("saves", isDirectory: true),
                    variables: [:]
                )

                for _ in 0..<180 {
                    try bridge.runFrame()
                    if let frame = bridge.copyLatestVideoFrame(), frame.width > 0, frame.height > 0 {
                        let audioFrameCount = bridge.drainAudioBatches().reduce(0) { $0 + Int($1.frameCount) }
                        let saveRAM = try bridge.serializeSaveRAM()
                        bridge.stop()
                        return LiveGalagaBootResult(
                            width: Int(frame.width),
                            height: Int(frame.height),
                            pixelBytes: frame.pixelData.count,
                            audioFrames: audioFrameCount,
                            saveRAMBytes: saveRAM.count
                        )
                    }
                }

                bridge.stop()
                throw NSError(
                    domain: "GalagaLiveBridgeTests",
                    code: 1,
                    userInfo: [NSLocalizedDescriptionKey: "The libretro core never produced a video frame for live Galaga."]
                )
            }.value
        }

        XCTAssertGreaterThan(boot.width, 0)
        XCTAssertGreaterThan(boot.height, 0)
        XCTAssertGreaterThan(boot.pixelBytes, 0)
        XCTAssertGreaterThan(boot.audioFrames, 0)
    }

    private func liveCoreURL(named runtimeID: String, filePath: String = #filePath) throws -> URL {
        let repoRoot = URL(fileURLWithPath: filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let url = repoRoot
            .appendingPathComponent("ios", isDirectory: true)
            .appendingPathComponent("App", isDirectory: true)
            .appendingPathComponent("Resources", isDirectory: true)
            .appendingPathComponent("Cores", isDirectory: true)
            .appendingPathComponent("\(runtimeID).dylib")
        guard FileManager.default.fileExists(atPath: url.path) else {
            throw XCTSkip("Bundled core \(runtimeID) is missing at \(url.path)")
        }
        return url
    }

    private func withTimeout<T: Sendable>(
        seconds: TimeInterval,
        operation: @escaping @Sendable () async throws -> T
    ) async throws -> T {
        try await withThrowingTaskGroup(of: T.self) { group in
            group.addTask {
                try await operation()
            }
            group.addTask {
                try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                throw NSError(
                    domain: "GalagaLiveBridgeTests",
                    code: 2,
                    userInfo: [NSLocalizedDescriptionKey: "Timed out after \(seconds)s waiting for the live libretro boot path."]
                )
            }

            let result = try await group.next()
            group.cancelAll()
            return try XCTUnwrap(result)
        }
    }
}

private struct LiveGalagaBootResult: Sendable {
    let width: Int
    let height: Int
    let pixelBytes: Int
    let audioFrames: Int
    let saveRAMBytes: Int
}

private struct LiveBridgeEnvironment {
    let baseURL: String
    let clientID: String
    let clientSecret: String
    let username: String
    let password: String

    static func loadOrSkip(
        environment: [String: String] = ProcessInfo.processInfo.environment
    ) throws -> LiveBridgeEnvironment {
        guard environment["SIMULATOR_DEVICE_NAME"] != nil else {
            throw XCTSkip("Live Galaga bridge tests run only on the iOS Simulator.")
        }

        let fileValues = mergedFileValues(environment: environment)
        func value(for key: String) -> String {
            let candidate = environment[key] ?? fileValues[key] ?? ""
            return candidate.trimmingCharacters(in: .whitespacesAndNewlines)
        }

        let configuration = LiveBridgeEnvironment(
            baseURL: value(for: "ROMM_TEST_BASE_URL"),
            clientID: value(for: "ROMM_TEST_CLIENT_ID"),
            clientSecret: value(for: "ROMM_TEST_CLIENT_SECRET"),
            username: value(for: "ROMM_TEST_USERNAME"),
            password: value(for: "ROMM_TEST_PASSWORD")
        )

        guard !configuration.baseURL.isEmpty else {
            throw XCTSkip("ROMM_TEST_BASE_URL is required for live bridge tests.")
        }
        guard !configuration.clientID.isEmpty else {
            throw XCTSkip("ROMM_TEST_CLIENT_ID is required for live bridge tests.")
        }
        guard !configuration.clientSecret.isEmpty else {
            throw XCTSkip("ROMM_TEST_CLIENT_SECRET is required for live bridge tests.")
        }
        guard !configuration.username.isEmpty else {
            throw XCTSkip("ROMM_TEST_USERNAME is required for live bridge tests.")
        }
        guard !configuration.password.isEmpty else {
            throw XCTSkip("ROMM_TEST_PASSWORD is required for live bridge tests.")
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

private struct LiveBridgeHarness {
    let profileStore: GRDBServerProfileStore
    let authController: AuthSessionController

    init(rootDirectory: URL) throws {
        let database = try AppDatabase(rootDirectory: rootDirectory.appending(path: "Database"))
        let profileStore = GRDBServerProfileStore(database: database)
        let secretStore = try FileSecretStore(rootDirectory: rootDirectory.appending(path: "Secrets"))
        let authController = AuthSessionController(
            profileStore: profileStore,
            secretStore: secretStore
        )
        self.profileStore = profileStore
        self.authController = authController
    }
}
