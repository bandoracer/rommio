import XCTest
@testable import RommioFoundation

final class LaunchStorageTests: XCTestCase {
    private var root: URL!

    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory.appending(path: "launch-storage-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: root)
    }

    /// A session's copy of a verified install is a clone that isn't hashed: a wrong digest goes
    /// unnoticed. Without the verified identity the copy is hashed.
    func testSessionCopyOfAVerifiedInstallIsAnUnhashedClone() throws {
        let install = root.appending(path: "install/game.3ds")
        try FileManager.default.createDirectory(at: install.deletingLastPathComponent(), withIntermediateDirectories: true)
        let bytes = Data(repeating: 0x33, count: 8192)
        try bytes.write(to: install)
        let digest = try ProgressFileHash.sha256(install)
        let identity = try XCTUnwrap(FileIdentity(install))

        let clone = root.appending(path: "sessions/a/content/game.3ds")
        try SessionContent.isolate(install, to: clone, sha256: "not-checked", verified: identity)
        XCTAssertEqual(try Data(contentsOf: clone), bytes)
        XCTAssertNotEqual(FileIdentity(clone)?.inode, identity.inode)

        XCTAssertThrowsError(try SessionContent.isolate(install, to: root.appending(path: "sessions/b/content/game.3ds"),
                                                        sha256: "not-checked", verified: nil))
        try SessionContent.isolate(install, to: root.appending(path: "sessions/c/content/game.3ds"), sha256: digest, verified: nil)

        // An install that changed since it was verified is hashed.
        try Data(repeating: 0x44, count: 8192).write(to: install)
        XCTAssertThrowsError(try SessionContent.isolate(install, to: root.appending(path: "sessions/d/content/game.3ds"),
                                                        sha256: digest, verified: identity))
    }

    func testClosedSessionsAreDeletedAndInterruptedOnesKept() async throws {
        let database = try AppDatabase(rootDirectory: root.appending(path: "db"))
        let profiles = GRDBServerProfileStore(database: database)
        let secrets = try FileSecretStore(rootDirectory: root.appending(path: "secrets"))
        let progress = ApplicationProgress(database: database, root: root.appending(path: "progress"), profiles: profiles,
                                           auth: AuthSessionController(profileStore: profiles, secretStore: secrets))
        let sessions = root.appending(path: "progress/sessions")
        for name in ["closed", "interrupted"] {
            let content = sessions.appending(path: "\(name)/content")
            try FileManager.default.createDirectory(at: content, withIntermediateDirectories: true)
            try Data("game".utf8).write(to: content.appending(path: "game.3ds"))
        }
        try Data("closed".utf8).write(to: sessions.appending(path: "closed/closed"))

        await progress.removeClosedSessions()
        XCTAssertFalse(FileManager.default.fileExists(atPath: sessions.appending(path: "closed").path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: sessions.appending(path: "interrupted/content/game.3ds").path))

        // At app start an interrupted session's game copy goes and its saves stay for recovery.
        let saves = sessions.appending(path: "interrupted/saves/save.srm")
        try FileManager.default.createDirectory(at: saves.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data("save".utf8).write(to: saves)
        progress.releaseInterruptedSessionContent()
        for _ in 0..<100 where FileManager.default.fileExists(atPath: sessions.appending(path: "interrupted/content").path) {
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: sessions.appending(path: "interrupted/content").path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: saves.path))
    }
}
