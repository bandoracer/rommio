import XCTest
@testable import RommioFoundation

final class VerifiedAccountTests: XCTestCase {
    func testDurableOwnershipSurvivesCredentialRemovalAndRejectsRebinding() throws {
        let root = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = try FileSecretStore(rootDirectory: root)
        let original = VerifiedAccount(baseURL: URL(string: "https://romm.example/romm")!, userID: 1)
        try store.bindVerifiedAccount(original, profileID: "a/b")
        try store.clearAuthSecrets(profileID: "a/b")
        let reopened = try FileSecretStore(rootDirectory: root)
        XCTAssertEqual(try reopened.verifiedAccount(profileID: "a/b"), original)
        XCTAssertNil(try reopened.verifiedAccount(profileID: "a_b"))
        let other = VerifiedAccount(baseURL: original.baseURL, userID: 2)
        XCTAssertThrowsError(try reopened.bindVerifiedAccount(other, profileID: "a/b")) { XCTAssertTrue($0 is AccountBindingError) }
        XCTAssertThrowsError(try reopened.bindVerifiedAccount(VerifiedAccount(baseURL: URL(string: "https://romm.example/other")!, userID: 1), profileID: "a/b"))
        XCTAssertEqual(try reopened.verifiedAccount(profileID: "a/b"), original)
    }

    func testCorruptOwnershipDoesNotBecomeAnUnboundProfile() throws {
        let root = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = try FileSecretStore(rootDirectory: root)
        let original = VerifiedAccount(baseURL: URL(string: "https://romm.example")!, userID: 1)
        try store.bindVerifiedAccount(original, profileID: "profile")
        let file = try XCTUnwrap(FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil).first)
        try Data("broken ownership record".utf8).write(to: file, options: .atomic)
        XCTAssertThrowsError(try store.verifiedAccount(profileID: "profile"))
        XCTAssertThrowsError(try store.bindVerifiedAccount(original, profileID: "profile"))
        XCTAssertEqual(try Data(contentsOf: file), Data("broken ownership record".utf8))
    }
}
