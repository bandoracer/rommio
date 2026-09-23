import XCTest
import GRDB
import Network
import RommioContract
@testable import RommioFoundation

final class DownloadQueueTests: XCTestCase {
    func testEnqueueReusesVerifiedCompletedInstall() async throws {
        let h = try Harness(); defer { h.clean() }
        let profile = try await h.profile("A", userID: 1)
        try await h.queue.enqueue(rom: h.rom, file: h.file)
        _ = try await h.pipe.waitForCall(0)
        await h.pipe.finish(0, bytes: "GOOD")
        let installed = try await h.waitFor(profile.id, .completed)

        try await h.queue.enqueue(rom: h.rom, file: h.file)
        let reusedRows = try await h.queue.records(profileID: profile.id)
        let reused = try XCTUnwrap(reusedRows.first)
        XCTAssertEqual(reused, installed)
        let calls = await h.pipe.count
        XCTAssertEqual(calls, 1)
        let verified = try await h.queue.verifiedLocalURL(profileID: profile.id, romID: 42, fileID: 7)
        XCTAssertEqual(verified?.path, installed.localPath)
    }

    func testMovedContainerRecoversOnlyDigestVerifiedOwnedInstall() async throws {
        let h = try Harness(); defer { h.clean() }
        let profile = try await h.profile("A", userID: 1)
        try await h.queue.enqueue(rom: h.rom, file: h.file)
        _ = try await h.pipe.waitForCall(0)
        await h.pipe.finish(0, bytes: "MOVE")
        var record = try await h.waitFor(profile.id, .completed)
        let actualPath = try XCTUnwrap(record.localPath)
        record.localPath = "/old-container/Library/" + (try XCTUnwrap(record.destinationRelativePath))
        try h.db.write { try $0.execute(sql: "UPDATE download_records SET payload = ? WHERE profile_id = ?", arguments: [try h.db.encoded(record), profile.id]) }
        let recovered = try await h.queue.records(profileID: profile.id).first
        XCTAssertEqual(recovered?.localPath, actualPath)
        let verified = try await h.queue.verifiedLocalURL(profileID: profile.id, romID: 42, fileID: 7)
        XCTAssertEqual(verified?.path, actualPath)

        try Data("BAD!".utf8).write(to: URL(fileURLWithPath: actualPath))
        try h.db.write { try $0.execute(sql: "UPDATE download_records SET payload = ? WHERE profile_id = ?", arguments: [try h.db.encoded(record), profile.id]) }
        let corrupt = try await h.queue.records(profileID: profile.id).first
        XCTAssertEqual(corrupt?.localPath, record.localPath, "Never adopt a different file after relocation")
    }

    func testMovedContainerPrefersCurrentOwnedCopyEvenWhileOldPathExists() async throws {
        let h = try Harness(); defer { h.clean() }
        let profile = try await h.profile("A", userID: 1)
        try await h.queue.enqueue(rom: h.rom, file: h.file)
        _ = try await h.pipe.waitForCall(0)
        await h.pipe.finish(0, bytes: "MOVE")
        var record = try await h.waitFor(profile.id, .completed)
        let currentPath = try XCTUnwrap(record.localPath)
        let oldPath = h.root.appending(path: "old-container/Library/roms/installs/\(try XCTUnwrap(record.attemptID))/fixture.nes")
        try FileManager.default.createDirectory(at: oldPath.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data("MOVE".utf8).write(to: oldPath)
        record.localPath = oldPath.path
        try h.db.write { try $0.execute(sql: "UPDATE download_records SET payload = ? WHERE profile_id = ?", arguments: [try h.db.encoded(record), profile.id]) }

        let recoveredRows = try await h.queue.records(profileID: profile.id)
        let recovered = try XCTUnwrap(recoveredRows.first)
        XCTAssertEqual(recovered.localPath, currentPath)
        let verified = try await h.queue.verifiedLocalURL(profileID: profile.id, romID: 42, fileID: 7)
        XCTAssertEqual(verified?.path, currentPath)
    }

    func testReenqueueDropsUnrecoverableOldContainerPointer() async throws {
        let h = try Harness(); defer { h.clean() }
        let profile = try await h.profile("A", userID: 1)
        try await h.queue.enqueue(rom: h.rom, file: h.file)
        _ = try await h.pipe.waitForCall(0)
        await h.pipe.finish(0, bytes: "GOOD")
        var record = try await h.waitFor(profile.id, .completed)
        try Data("BAD!".utf8).write(to: URL(fileURLWithPath: try XCTUnwrap(record.localPath)))
        record.localPath = "/old-container/Library/" + (try XCTUnwrap(record.destinationRelativePath))
        try h.db.write { try $0.execute(sql: "UPDATE download_records SET payload = ? WHERE profile_id = ?", arguments: [try h.db.encoded(record), profile.id]) }

        try await h.queue.enqueue(rom: h.rom, file: h.file)
        _ = try await h.pipe.waitForCall(1)
        await h.pipe.fail(1, error: RommClientError.invalidResponse)
        let failed = try await h.waitFor(profile.id, .failed)
        XCTAssertNil(failed.localPath)
        XCTAssertNil(failed.contentSHA256)
        let verified = try await h.queue.verifiedLocalURL(profileID: profile.id, romID: 42, fileID: 7)
        XCTAssertNil(verified)
    }

    func testProductionTransferUsesFrozenOwnerAndCommitsVerifiedFile() async throws {
        let server = try DownloadHTTPFixture()
        server.start(); defer { server.stop() }
        await fulfillment(of: [server.ready], timeout: 3)
        let h = try Harness(); defer { h.clean() }
        let a = try await h.profile("A", userID: 1, baseURL: XCTUnwrap(server.url), bindOwner: false)
        XCTAssertNil(try h.secrets.verifiedAccount(profileID: a.id))
        try h.secrets.saveTokenBundle(TokenBundle(accessToken: "account-A-token"), for: a.id)
        let queue = ManagedDownloadQueue(database: h.db, profileStore: h.profiles, authController: h.auth, libraryStore: h.library)
        try await queue.enqueue(rom: h.rom, file: h.file)
        _ = try await h.profile("B", userID: 2)
        for _ in 0..<200 {
            if let record = try await queue.records(profileID: a.id).first, record.status == .completed {
                let installed = try await queue.verifiedLocalURL(profileID: a.id, romID: 42, fileID: 7)
                XCTAssertEqual(try Data(contentsOf: XCTUnwrap(installed)), Data("REAL".utf8))
                XCTAssertEqual(record.bytesDownloaded, 4)
                XCTAssertEqual(record.owner?.baseURL, a.baseURL)
                let romRequests = server.requests.filter { $0.hasPrefix("GET /api/roms/") }
                XCTAssertEqual(romRequests.count, 1)
                XCTAssertTrue(romRequests.allSatisfy { $0.contains("Authorization: Bearer account-A-token") })
                XCTAssertEqual(try h.secrets.verifiedAccount(profileID: a.id)?.userID, 1)
                return
            }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("Production download failed: \(try await queue.records(profileID: a.id))")
    }

    func testAuthenticationParkResumesOnlyAfterOriginalProfileSignsIn() async throws {
        let h = try Harness(); defer { h.clean() }
        var a = try await h.profile("A", userID: 1)
        var pending = h.record(profile: a)
        pending.owner = try await h.auth.verifiedAccount(profileID: a.id)
        pending.attemptID = UUID().uuidString.lowercased()
        try h.save(pending)
        a.status = .reauthRequiredOrigin; a.sessionState.hasOriginSession = false
        try await h.profiles.save(a, makeActive: true)
        try await h.queue.resumePending()
        let parked = try await h.waitFor(a.id, .failed)
        XCTAssertEqual(parked.parkedForAuthentication, true)
        _ = try await h.profile("B", userID: 2)
        try await h.queue.resumePending()
        let noCalls = await h.pipe.count
        XCTAssertEqual(noCalls, 0)
        a.status = .connected; a.sessionState.hasOriginSession = true
        try await h.profiles.save(a, makeActive: false)
        try await h.queue.resumePending()
        let call = try await h.pipe.waitForCall(0)
        XCTAssertEqual(call.profileID, a.id)
        await h.pipe.finish(0, bytes: "AUTH")
        let result = try await h.waitFor(a.id, .completed)
        XCTAssertNil(result.parkedForAuthentication)
    }

    func testDatabaseAttemptFenceRejectsCompletionFromAnotherQueueOwner() async throws {
        let h = try Harness(); defer { h.clean() }
        let a = try await h.profile("A", userID: 1)
        try await h.queue.enqueue(rom: h.rom, file: h.file)
        let oldCall = try await h.pipe.waitForCall(0)
        let replacement = ManagedDownloadQueue(database: h.db, profileStore: h.profiles, authController: h.auth, libraryStore: h.library, transfer: h.transfer)
        try await replacement.resumePending()
        _ = try await h.pipe.waitForCall(1)
        await h.pipe.finish(1, bytes: "NEXT")
        let completed = try await h.waitFor(a.id, .completed)
        await h.pipe.finish(0, bytes: "LATE") // Different actor: there is no shared in-memory cancellation flag.
        try await Task.sleep(for: .milliseconds(40))
        let final = try await h.queue.records(profileID: a.id).first
        XCTAssertEqual(final?.attemptID, completed.attemptID)
        XCTAssertEqual(final?.localPath, completed.localPath)
        XCTAssertEqual(try String(contentsOfFile: XCTUnwrap(final?.localPath), encoding: .utf8), "NEXT")
        XCTAssertFalse(FileManager.default.fileExists(atPath: oldCall.destination.path))
    }

    func testSameNamesAndIDsAcrossProfilesInstallDifferentBytesWithoutCollision() async throws {
        let h = try Harness(); defer { h.clean() }
        let a = try await h.profile("A", userID: 1)
        try await h.queue.enqueue(rom: h.rom, file: h.file)
        let first = try await h.pipe.waitForCall(0)
        let b = try await h.profile("B", userID: 2)
        try await h.queue.enqueue(rom: h.rom, file: h.file)
        let second = try await h.pipe.waitForCall(1)
        XCTAssertEqual(first.profileID, a.id); XCTAssertEqual(second.profileID, b.id)
        XCTAssertNotEqual(first.destination, second.destination)
        await h.pipe.finish(0, bytes: "AAAA")
        await h.pipe.finish(1, bytes: "BBBB")
        let ra = try await h.waitFor(a.id, .completed)
        let rb = try await h.waitFor(b.id, .completed)
        XCTAssertNotEqual(ra.localPath, rb.localPath)
        XCTAssertEqual(try String(contentsOfFile: XCTUnwrap(ra.localPath), encoding: .utf8), "AAAA")
        XCTAssertEqual(try String(contentsOfFile: XCTUnwrap(rb.localPath), encoding: .utf8), "BBBB")
        XCTAssertNotEqual(ra.contentSHA256, rb.contentSHA256)
    }

    func testCancelRetryIgnoresLateCompletionAndOldTaskCleanup() async throws {
        let h = try Harness(); defer { h.clean() }
        let a = try await h.profile("A", userID: 1)
        try await h.queue.enqueue(rom: h.rom, file: h.file)
        _ = try await h.pipe.waitForCall(0)
        let id = "\(a.id):42:7"
        try await h.queue.cancel(recordID: id)
        try await h.queue.retry(recordID: id)
        _ = try await h.pipe.waitForCall(1)
        let retry = try await h.queue.records(profileID: a.id).first
        await h.pipe.finish(0, bytes: "OLD!") // Deliberately ignores cancellation like a late native completion.
        try await Task.sleep(for: .milliseconds(40))
        try await h.queue.resumePending()
        let calls = await h.pipe.count
        XCTAssertEqual(calls, 2, "Old cleanup cleared the replacement task and started a duplicate")
        let running = try await h.queue.records(profileID: a.id).first
        XCTAssertEqual(running?.attemptID, retry?.attemptID)
        XCTAssertEqual(running?.status, .running)
        await h.pipe.finish(1, bytes: "NEW!")
        let completed = try await h.waitFor(a.id, .completed)
        XCTAssertEqual(try String(contentsOfFile: XCTUnwrap(completed.localPath), encoding: .utf8), "NEW!")
    }

    func testStartupRecoversOriginalProfileAndDestinationAfterActiveProfileSwitch() async throws {
        let h = try Harness(); defer { h.clean() }
        let a = try await h.profile("A:original", userID: 1)
        var interrupted = h.record(profile: a)
        interrupted.owner = try await h.auth.verifiedAccount(profileID: a.id)
        interrupted.attemptID = UUID().uuidString.lowercased()
        interrupted.destinationRelativePath = "roms/installs/\(interrupted.attemptID!)/fixture.nes"
        interrupted.status = .running
        try h.save(interrupted)
        _ = try await h.profile("B", userID: 2)
        // Fresh service graph against the same on-disk database, as on application restart.
        let reopened = try AppDatabase(rootDirectory: h.root.appending(path: "db"))
        let profiles = GRDBServerProfileStore(database: reopened)
        let auth = AuthSessionController(profileStore: profiles, secretStore: h.secrets)
        let queue = ManagedDownloadQueue(database: reopened, profileStore: profiles, authController: auth, libraryStore: h.library, transfer: h.transfer)
        try await queue.resumePending()
        let request = try await h.pipe.waitForCall(0)
        XCTAssertEqual(request.profileID, a.id)
        XCTAssertEqual(request.baseURL, a.baseURL)
        XCTAssertTrue(request.source.absoluteString.hasPrefix(a.baseURL.absoluteString))
        XCTAssertFalse(request.destination.path.contains(interrupted.attemptID!))
        await h.pipe.finish(0, bytes: "AAAA")
        for _ in 0..<100 {
            if let result = try await queue.records(profileID: a.id).first, result.status == .completed {
                XCTAssertEqual(result.owner, interrupted.owner)
                XCTAssertEqual(result.localPath, request.destination.path)
                return
            }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("Recovered download did not complete")
    }

    func testLegacyWorkIsPreservedWithoutGuessingAnAccount() async throws {
        let h = try Harness(); defer { h.clean() }
        let a = try await h.profile("A", userID: 1)
        var legacy = h.record(profile: a)
        let oldFile = h.library.rootDirectory.appending(path: "legacy.nes")
        try Data("OLD!".utf8).write(to: oldFile)
        legacy.localPath = oldFile.path
        try h.save(legacy)
        try await h.queue.resumePending()
        let failed = try await h.waitFor(a.id, .failed)
        XCTAssertNil(failed.owner)
        XCTAssertEqual(failed.localPath, oldFile.path)
        XCTAssertTrue(failed.lastError?.contains("no verified account") == true)
        XCTAssertEqual(try Data(contentsOf: oldFile), Data("OLD!".utf8))
        let calls = await h.pipe.count
        XCTAssertEqual(calls, 0)
    }

    func testChangedServerAndAccountCannotRetargetQueuedWork() async throws {
        let h = try Harness(); defer { h.clean() }
        var a = try await h.profile("A", userID: 1)
        var pending = h.record(profile: a)
        pending.owner = try await h.auth.verifiedAccount(profileID: a.id)
        pending.attemptID = UUID().uuidString.lowercased()
        try h.save(pending)
        a.baseURL = URL(string: "https://other.example")!
        try await h.profiles.save(a, makeActive: true)
        try await h.queue.resumePending()
        let failed = try await h.waitFor(a.id, .failed)
        XCTAssertEqual(failed.owner, pending.owner)
        XCTAssertEqual(failed.attemptID, pending.attemptID)
        let calls = await h.pipe.count
        XCTAssertEqual(calls, 0)
    }

    func testFailedReplacementPreservesLastInstallAndDigestRejectsTampering() async throws {
        let h = try Harness(); defer { h.clean() }
        let a = try await h.profile("A", userID: 1)
        try await h.queue.enqueue(rom: h.rom, file: h.file)
        _ = try await h.pipe.waitForCall(0)
        await h.pipe.finish(0, bytes: "GOOD")
        let good = try await h.waitFor(a.id, .completed)
        try await h.queue.retry(recordID: good.id)
        _ = try await h.pipe.waitForCall(1)
        let duringReplacement = try await h.queue.verifiedLocalURL(profileID: a.id, romID: 42, fileID: 7)
        XCTAssertNil(duringReplacement,
                     "A running replacement must not expose a stale install pointer")
        await h.pipe.fail(1, error: RommClientError.invalidResponse)
        let failed = try await h.waitFor(a.id, .failed)
        XCTAssertEqual(failed.localPath, good.localPath)
        XCTAssertEqual(failed.contentSHA256, good.contentSHA256)
        let file = try await h.queue.verifiedLocalURL(profileID: a.id, romID: 42, fileID: 7)
        XCTAssertEqual(file?.path, good.localPath)
        try Data("EVIL".utf8).write(to: XCTUnwrap(file))
        do { _ = try await h.queue.verifiedLocalURL(profileID: a.id, romID: 42, fileID: 7); XCTFail("Tampered content was playable") }
        catch DownloadOwnershipError.corruptInstall { }
    }

    func testDeleteDuringTransferDoesNotResurrectInstall() async throws {
        let h = try Harness(); defer { h.clean() }
        let a = try await h.profile("A", userID: 1)
        try await h.queue.enqueue(rom: h.rom, file: h.file)
        let call = try await h.pipe.waitForCall(0)
        try await h.queue.deleteLocalContent(recordID: "\(a.id):42:7")
        await h.pipe.finish(0, bytes: "LATE")
        try await Task.sleep(for: .milliseconds(40))
        let canceled = try await h.waitFor(a.id, .canceled)
        XCTAssertNil(canceled.localPath)
        XCTAssertFalse(FileManager.default.fileExists(atPath: call.destination.path))
    }

    func testTransientFailureRetainsOwnedQueueForLaterReplay() async throws {
        let h = try Harness(); defer { h.clean() }
        let a = try await h.profile("A", userID: 1)
        try await h.queue.enqueue(rom: h.rom, file: h.file)
        _ = try await h.pipe.waitForCall(0)
        await h.pipe.fail(0, error: URLError(.notConnectedToInternet))
        let queued = try await h.waitFor(a.id, .queued)
        XCTAssertNotNil(queued.retryAfterEpochMS)
        XCTAssertEqual(queued.owner?.userID, 1)
        try await h.queue.resumePending()
        let calls = await h.pipe.count
        XCTAssertEqual(calls, 1, "Backoff was ignored")
    }
}

private struct Harness {
    let root: URL
    let db: AppDatabase
    let profiles: GRDBServerProfileStore
    let secrets: FileSecretStore
    let auth: AuthSessionController
    let library: AppManagedLibraryStore
    let pipe: ControlledTransfer
    let queue: ManagedDownloadQueue
    let transfer: ManagedDownloadQueue.Transfer
    let file = RomFileDTO(id: 7, romID: 42, fileName: "fixture.nes", fileSizeBytes: 4)
    var rom: RomDTO { RomDTO(id: 42, name: "Fixture", platformSlug: "nes", files: [file]) }

    init() throws {
        root = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        db = try AppDatabase(rootDirectory: root.appending(path: "db"))
        profiles = GRDBServerProfileStore(database: db)
        secrets = try FileSecretStore(rootDirectory: root.appending(path: "secrets"))
        auth = AuthSessionController(profileStore: profiles, secretStore: secrets)
        library = AppManagedLibraryStore(rootDirectory: root.appending(path: "library"))
        try library.ensureRootLayout()
        let pipe = ControlledTransfer(); self.pipe = pipe
        transfer = { profile, base, source, destination, _ in
            let data = try await pipe.request(profileID: profile, baseURL: base, source: source, destination: destination)
            try FileManager.default.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
            try data.write(to: destination, options: .atomic)
            return Int64(data.count)
        }
        queue = ManagedDownloadQueue(database: db, profileStore: profiles, authController: auth, libraryStore: library, transfer: transfer)
    }
    func clean() { try? FileManager.default.removeItem(at: root) }
    func profile(_ id: String, userID: Int, baseURL: URL? = nil, bindOwner: Bool = true) async throws -> ServerProfile {
        let profile = ServerProfile(id: id, label: id, baseURL: baseURL ?? URL(string: "https://server\(userID).example")!, edgeAuthMode: .none,
                                    originAuthMode: .rommBearerPassword, capabilities: AuthCapabilities(), serverAccess: ServerAccessState(status: .ready),
                                    sessionState: SessionState(hasOriginSession: true), isActive: true, status: .connected,
                                    createdAt: "2026-09-17T00:00:00Z", updatedAt: "2026-09-17T00:00:00Z")
        try await profiles.save(profile, makeActive: true)
        if bindOwner { try secrets.bindVerifiedAccount(VerifiedAccount(baseURL: profile.baseURL, userID: userID), profileID: id) }
        return profile
    }
    func record(profile: ServerProfile) -> DownloadRecord {
        DownloadRecord(profileID: profile.id, romID: 42, fileID: 7, romName: "Fixture", platformSlug: "nes", fileName: file.fileName,
                       fileSizeBytes: 4, enqueuedAtEpochMS: 1, updatedAtEpochMS: 1)
    }
    func save(_ record: DownloadRecord) throws {
        try db.write { db in
            try db.execute(sql: "INSERT INTO download_records(profile_id, rom_id, file_id, payload) VALUES (?, ?, ?, ?)",
                           arguments: [record.profileID, record.romID, record.fileID, try self.db.encoded(record)])
        }
    }
    func waitFor(_ profileID: String, _ status: DownloadStatus) async throws -> DownloadRecord {
        for _ in 0..<100 {
            if let row = try await queue.records(profileID: profileID).first, row.status == status { return row }
            try await Task.sleep(for: .milliseconds(10))
        }
        throw NSError(domain: "DownloadTest", code: 1, userInfo: [NSLocalizedDescriptionKey: "Timed out waiting for \(status)"])
    }
}

private actor ControlledTransfer {
    struct Call: Sendable { let profileID: String; let baseURL: URL; let source: URL; let destination: URL }
    var calls: [Call] = []
    var continuations: [Int: CheckedContinuation<Data, Error>] = [:]
    var count: Int { calls.count }
    func request(profileID: String, baseURL: URL, source: URL, destination: URL) async throws -> Data {
        let index = calls.count
        calls.append(Call(profileID: profileID, baseURL: baseURL, source: source, destination: destination))
        return try await withCheckedThrowingContinuation { continuations[index] = $0 }
    }
    func finish(_ index: Int, bytes: String) { continuations.removeValue(forKey: index)?.resume(returning: Data(bytes.utf8)) }
    func fail(_ index: Int, error: Error) { continuations.removeValue(forKey: index)?.resume(throwing: error) }
    func waitForCall(_ index: Int) async throws -> Call {
        for _ in 0..<100 {
            if calls.count > index { return calls[index] }
            try await Task.sleep(for: .milliseconds(10))
        }
        throw NSError(domain: "DownloadTest", code: 2, userInfo: [NSLocalizedDescriptionKey: "No transfer started"])
    }
}

private final class DownloadHTTPFixture: @unchecked Sendable {
    let ready = XCTestExpectation(description: "Download fixture ready")
    private let listener: NWListener
    private let lock = NSLock()
    private var captured: [String] = []
    var requests: [String] { lock.withLock { captured } }
    var url: URL? { listener.port.flatMap { URL(string: "http://127.0.0.1:\($0.rawValue)") } }
    init() throws { listener = try NWListener(using: .tcp, on: .any) }
    func start() {
        listener.stateUpdateHandler = { [weak self] state in if case .ready = state { self?.ready.fulfill() } }
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { connection.cancel(); return }
            connection.start(queue: .global()); self.receive(connection, data: Data())
        }
        listener.start(queue: .global())
    }
    private func receive(_ connection: NWConnection, data: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [self] chunk, _, complete, error in
            let combined = data + (chunk ?? Data())
            guard combined.range(of: Data("\r\n\r\n".utf8)) != nil else {
                if !complete && error == nil { receive(connection, data: combined) } else { connection.cancel() }
                return
            }
            lock.withLock { captured.append(String(decoding: combined, as: UTF8.self)) }
            let request = String(decoding: combined, as: UTF8.self)
            let body: String
            let contentType: String
            if request.hasPrefix("GET /api/heartbeat ") {
                body = #"{"SYSTEM":{"VERSION":"test","SHOW_SETUP_WIZARD":false}}"#; contentType = "application/json"
            } else if request.hasPrefix("GET /api/users/me ") {
                body = #"{"id":1,"username":"fixture","email":"fixture@example.test","role":"user"}"#; contentType = "application/json"
            } else if request.hasPrefix("POST /api/devices ") {
                body = #"{"device_id":"fixture-device"}"#; contentType = "application/json"
            } else { body = "REAL"; contentType = "application/octet-stream" }
            let response = "HTTP/1.1 200 OK\r\nContent-Type: \(contentType)\r\nContent-Length: \(body.utf8.count)\r\nConnection: close\r\n\r\n\(body)"
            connection.send(content: Data(response.utf8), completion: .contentProcessed { _ in connection.cancel() })
        }
    }
    func stop() { listener.cancel() }
}
