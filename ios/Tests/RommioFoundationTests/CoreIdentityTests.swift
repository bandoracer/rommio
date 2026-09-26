import Foundation
import XCTest
import RommioContract
import RommioPlayerKit
@testable import RommioFoundation

/// A core's snapshot identity (docs/save-sync-spec.md, "Snapshot compatibility"): the pinned
/// artifact's digest before the app build signs it, so resume points survive app builds; and the
/// resume points pre-fix iOS builds made, which named the signed file's per-build digest.
final class CoreIdentityTests: XCTestCase {
    private let options = ["mgba_use_bios": "ON", "mgba_skip_bios": "OFF"]
    private let inWindow: Int64 = 1_790_400_330_270 // 2026-09-26 05:25:30 UTC, the Fire Emblem resume point
    private let current = String(repeating: "e", count: 64)
    private let previousBuild = String(repeating: "4", count: 64)

    private func state(_ digest: String, platform: String = "ios", core: String = "mgba", options: [String: String]? = nil,
                       systemFiles: [String: String] = [:]) -> ProgressStateCompatibility {
        ProgressStateCompatibility(coreId: core, coreSha256: digest, stateFormat: "\(core)-native-v1", platform: platform,
                                   architecture: "arm64", options: options ?? self.options, systemFiles: systemFiles)
    }

    func testTheSameCoreRestoresItsOwnSnapshotsAtAnyTime() {
        XCTAssertTrue(state(current).restores(state(current), createdAtEpochMs: 0))
        XCTAssertTrue(state(current).restores(state(current), createdAtEpochMs: Int64.max))
    }

    func testAPreFixDeviceSnapshotDifferingOnlyInTheDigestRestoresInTheWindow() {
        let running = state(current)
        XCTAssertTrue(running.restores(state(previousBuild), createdAtEpochMs: inWindow))
        let window = ProgressStateCompatibility.legacySignedDigestWindow
        XCTAssertTrue(running.restores(state(previousBuild), createdAtEpochMs: window.lowerBound))
        XCTAssertFalse(running.restores(state(previousBuild), createdAtEpochMs: window.lowerBound - 1), "Before the last core build change")
        XCTAssertFalse(running.restores(state(previousBuild), createdAtEpochMs: window.upperBound), "Made by a build with the stable identity")
    }

    func testEverythingButTheDigestMustStillMatch() {
        let running = state(current)
        XCTAssertFalse(running.restores(state(previousBuild, core: "gambatte"), createdAtEpochMs: inWindow), "Another core")
        XCTAssertFalse(running.restores(state(previousBuild, options: ["mgba_use_bios": "OFF", "mgba_skip_bios": "OFF"]), createdAtEpochMs: inWindow), "Other options")
        XCTAssertFalse(running.restores(state(previousBuild, systemFiles: ["gba_bios.bin": previousBuild]), createdAtEpochMs: inWindow), "Other system files")
        XCTAssertFalse(running.restores(state(previousBuild, platform: "ios-simulator"), createdAtEpochMs: inWindow), "The Simulator's cores are other builds")
        XCTAssertFalse(state(current, platform: "android").restores(state(previousBuild, platform: "android"), createdAtEpochMs: inWindow),
                       "Android cores were never re-signed")
    }

    func testAQualificationRestoresACheckpointsSnapshotByTheRule() throws {
        let capture = NativeProgressCapture(memory: [0: Data(count: 32768), 1: Data(count: 24)], state: nil, stateError: nil,
                                            options: options, descriptor: "rommio-cart-v1:gambatte:gb:32768:24")
        let qualification = try CartridgePersistence(capture: capture, coreSHA256: current, platform: "ios", architecture: "arm64")
        func checkpoint(_ snapshot: ProgressStateCompatibility?, at time: Int64) -> ProgressCheckpoint {
            ProgressCheckpoint(schemaVersion: 3, checkpointId: UUID().uuidString,
                               binding: ProgressBinding(serverKey: "https://romm.example", accountId: "1", romId: 1, files: []),
                               saveCompatibility: qualification.save, stateCompatibility: snapshot, saveDataComplete: true, artifacts: [],
                               deviceId: "device", sessionId: "session", captureReason: "background", createdAtEpochMs: time)
        }
        XCTAssertTrue(qualification.restoresState(checkpoint(qualification.state, at: 0)))
        let earlierBuild = state(previousBuild, core: "gambatte", options: options)
        XCTAssertTrue(qualification.restoresState(checkpoint(earlierBuild, at: inWindow)))
        XCTAssertFalse(qualification.restoresState(checkpoint(earlierBuild, at: ProgressStateCompatibility.legacySignedDigestWindow.upperBound)))
        XCTAssertFalse(qualification.restoresState(checkpoint(nil, at: inWindow)), "A game-save checkpoint has no snapshot to restore")
    }

    func testTheCoreIsIdentifiedByItsArtifactDigestWhenProvenanceRecordsIt() throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "rommio-core-identity-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let core = root.appending(path: "fceumm")
        try Data("signed core bytes".utf8).write(to: core)
        let signed = try ProgressFileHash.sha256(core)
        let artifact = String(repeating: "5", count: 64)
        func manifest(_ extra: [String: Any]) throws -> URL {
            var record: [String: Any] = ["schemaVersion": 1, "runtimeId": "fceumm", "sourceManifestSha256": PinnedAppleCore.sourceManifestSHA256,
                                         "persistenceExtension": "rommio-fceumm-segments-v2", "architecture": CartridgePersistence.architecture,
                                         "sha256": signed]
            record.merge(extra) { $1 }
            let url = root.appending(path: "\(UUID().uuidString).json")
            try JSONSerialization.data(withJSONObject: record).write(to: url)
            return url
        }
        XCTAssertEqual(try PinnedAppleCore.verify(core: core, manifest: manifest(["artifactSha256": artifact])), artifact,
                       "The same for every app build from this core build")
        XCTAssertEqual(try PinnedAppleCore.verify(core: core, manifest: manifest([:])), signed, "Provenance without it names the file")
        XCTAssertEqual(try PinnedAppleCore.verify(core: core, manifest: manifest(["artifactSha256": "not a digest"])), signed)
        // The signed file is still checked: other bytes are refused whatever the identity.
        XCTAssertThrowsError(try PinnedAppleCore.verify(core: core, manifest: manifest(["artifactSha256": artifact, "sha256": artifact])))
    }
}
