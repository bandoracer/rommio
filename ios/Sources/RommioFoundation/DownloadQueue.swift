import Foundation
import GRDB
import CryptoKit
import RommioContract

public protocol DownloadQueue: Sendable {
    func records(profileID: String?) async throws -> [DownloadRecord]
    func enqueue(rom: RomDTO, file: RomFileDTO) async throws
    func retry(recordID: String) async throws
    func cancel(recordID: String) async throws
    func deleteLocalContent(recordID: String) async throws
}

public enum DownloadOwnershipError: LocalizedError {
    case legacyOwnership, invalidDestination, unavailableProfile, corruptInstall
    public var errorDescription: String? {
        switch self {
        case .legacyOwnership: "This older download has no verified account owner. Its files and record are preserved for migration."
        case .invalidDestination: "The download has an invalid local destination."
        case .unavailableProfile: "Sign in to the original server account to continue this download."
        case .corruptInstall: "The installed file no longer matches the verified download. Download it again before playing."
        }
    }
}

public actor ManagedDownloadQueue: DownloadQueue {
    public typealias Transfer = @Sendable (_ profileID: String, _ baseURL: URL, _ source: URL, _ destination: URL, _ expectedBytes: Int64?) async throws -> Int64
    private struct Flight { let attemptID: String; let task: Task<Void, Never> }
    private let database: AppDatabase
    private let profileStore: ServerProfileStore
    private let authController: AuthSessionController
    private let libraryStore: LibraryStore
    private let transfer: Transfer
    private var activeTasks: [String: Flight] = [:]
    private var retryTask: Task<Void, Never>?
    private var retryDeadline: Int64?

    public init(database: AppDatabase, profileStore: ServerProfileStore, authController: AuthSessionController,
                libraryStore: LibraryStore, transfer: Transfer? = nil) {
        self.database = database; self.profileStore = profileStore
        self.authController = authController; self.libraryStore = libraryStore
        self.transfer = transfer ?? { profileID, baseURL, source, destination, expectedBytes in
            let client = await authController.client(for: profileID, baseURL: baseURL)
            return try await client.download(from: source, to: destination, expectedBytes: expectedBytes)
        }
    }

    public func records(profileID: String? = nil) async throws -> [DownloadRecord] {
        guard let scopeID = if let profileID { profileID } else { try await profileStore.activeProfile()?.id } else { return [] }
        return try allRecords().filter { $0.profileID == scopeID }
    }

    public func enqueue(rom: RomDTO, file: RomFileDTO) async throws {
        guard file.romID == rom.id, file.fileSizeBytes >= 0 else { throw RommClientError.invalidResponse }
        guard let profile = try await profileStore.activeProfile() else { throw DownloadOwnershipError.unavailableProfile }
        var verifiedOwner = try await authController.verifiedAccount(profileID: profile.id)
        if verifiedOwner == nil {
            // Upgrade an existing authenticated profile through a real account check, never by guessing.
            _ = try await authController.validateProfile(profileID: profile.id)
            verifiedOwner = try await authController.verifiedAccount(profileID: profile.id)
        }
        guard let owner = verifiedOwner, owner.baseURL == profile.baseURL else { throw DownloadOwnershipError.unavailableProfile }
        var next = DownloadRecord(profileID: profile.id, romID: rom.id, fileID: file.id, romName: rom.displayName,
                                  platformSlug: rom.platformSlug, fileName: file.fileName, fileSizeBytes: file.fileSizeBytes,
                                  totalBytes: file.fileSizeBytes, enqueuedAtEpochMS: now(), updatedAtEpochMS: now())
        let previous = try record(recordID: next.id)
        if let existing = previous {
            guard existing.owner != nil else { throw DownloadOwnershipError.legacyOwnership }
            guard existing.owner == owner else { throw AccountBindingError() }
            if existing.status == .queued || existing.status == .running { return }
            if let path = existing.localPath, let digest = existing.contentSHA256 {
                let installed = URL(fileURLWithPath: path)
                if (try? validateManagedURL(installed)) != nil,
                   FileManager.default.fileExists(atPath: path),
                   (try? Self.sha256(installed)) == digest {
                    if existing.status == .completed, existing.fileName == file.fileName,
                       existing.fileSizeBytes == file.fileSizeBytes { return }
                    next.localPath = path
                    next.contentSHA256 = digest
                }
            }
        }
        next.owner = owner
        try await requireOwner(next)
        try prepareAttempt(&next)
        guard try commit(next, replacing: previous) else { return }
        start(next)
    }

    public func retry(recordID: String) async throws {
        guard let previous = try record(recordID: recordID) else { return }
        try await requireOwner(previous)
        // Re-read after authentication suspends; a cancel/delete/retry may have won meanwhile.
        guard var next = try record(recordID: recordID), next.attemptID == previous.attemptID,
              next.status == previous.status else { return }
        let expected = next
        try prepareAttempt(&next)
        guard try commit(next, replacing: expected) else { return }
        start(next)
    }

    public func cancel(recordID: String) async throws {
        guard var next = try record(recordID: recordID) else { return }
        let previous = next
        next.status = .canceled
        next.attemptID = UUID().uuidString.lowercased() // Invalidates every callback from the canceled attempt.
        next.retryAfterEpochMS = nil
        next.lastError = nil; next.updatedAtEpochMS = now()
        guard try commit(next, replacing: previous) else { return }
        activeTasks[recordID]?.task.cancel()
    }

    public func deleteLocalContent(recordID: String) async throws {
        guard var next = try record(recordID: recordID) else { return }
        let previous = next
        let path = next.localPath
        // Detach first. An interrupted unlink cannot resurrect an install or accept a late completion.
        next.status = .canceled; next.attemptID = UUID().uuidString.lowercased()
        next.localPath = nil; next.contentSHA256 = nil; next.retryAfterEpochMS = nil
        next.updatedAtEpochMS = now(); next.lastError = nil
        guard try commit(next, replacing: previous) else { return }
        activeTasks[recordID]?.task.cancel()
        if let path, try !allRecords().contains(where: { $0.localPath == path }) {
            let url = URL(fileURLWithPath: path)
            try validateManagedURL(url)
            if FileManager.default.fileExists(atPath: path) { try FileManager.default.removeItem(at: url) }
        }
    }

    /// Invoke on startup, foreground, reconnect, and while an active queue is being observed.
    public func resumePending() async throws {
        for previous in try allRecords() where previous.status == .queued || previous.status == .running || (previous.status == .failed && previous.parkedForAuthentication == true) {
            if let flight = activeTasks[previous.id], flight.attemptID == previous.attemptID { continue }
            if let after = previous.retryAfterEpochMS, after > now() { continue }
            do {
                try await requireOwner(previous)
                guard var next = try record(recordID: previous.id), next.attemptID == previous.attemptID,
                      next.status == previous.status else { continue }
                // An abandoned attempt never reuses its file, even if the old process installed bytes.
                let expected = next
                try prepareAttempt(&next)
                guard try commit(next, replacing: expected) else { continue }
                start(next)
            } catch {
                guard var latest = try record(recordID: previous.id), latest.attemptID == previous.attemptID,
                      latest.status == previous.status else { continue }
                let expected = latest
                latest.status = .failed; latest.lastError = error.localizedDescription; latest.updatedAtEpochMS = now()
                latest.parkedForAuthentication = Self.authenticationFailure(error)
                _ = try commit(latest, replacing: expected)
            }
        }
        scheduleRetry()
    }

    /// New installs are selected through their durable record, never inferred from a filename.
    public func verifiedLocalURL(profileID: String, romID: Int, fileID: Int) async throws -> URL? {
        guard let saved = try record(recordID: "\(profileID):\(romID):\(fileID)"),
              saved.status == .completed || saved.status == .failed,
              let path = saved.localPath else { return nil }
        if let owner = saved.owner {
            guard let profile = try await profileStore.profile(id: profileID), profile.baseURL == owner.baseURL,
                  try await authController.verifiedAccount(profileID: profileID) == owner else { throw AccountBindingError() }
        }
        let url = URL(fileURLWithPath: path)
        try validateManagedURL(url)
        guard FileManager.default.fileExists(atPath: path) else { return nil }
        if saved.owner != nil {
            guard let digest = saved.contentSHA256, try Self.sha256(url) == digest else { throw DownloadOwnershipError.corruptInstall }
        }
        return url
    }

    private func prepareAttempt(_ record: inout DownloadRecord) throws {
        guard !record.fileName.isEmpty, record.fileName != ".", record.fileName != "..",
              !record.fileName.contains("/"), !record.fileName.contains("\\"),
              !record.fileName.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else {
            throw DownloadOwnershipError.invalidDestination
        }
        let attempt = UUID().uuidString.lowercased()
        record.attemptID = attempt
        record.destinationRelativePath = "roms/installs/\(attempt)/\(record.fileName)"
        record.status = .queued; record.lastError = nil; record.retryAfterEpochMS = nil; record.parkedForAuthentication = nil
        record.progressPercent = 0; record.bytesDownloaded = 0; record.totalBytes = record.fileSizeBytes
        record.startedAtEpochMS = nil; record.completedAtEpochMS = nil; record.updatedAtEpochMS = now()
    }

    private func requireOwner(_ record: DownloadRecord) async throws {
        guard let owner = record.owner else { throw DownloadOwnershipError.legacyOwnership }
        guard let profile = try await profileStore.profile(id: record.profileID),
              profile.baseURL == owner.baseURL,
              try await authController.verifiedAccount(profileID: record.profileID) == owner else { throw AccountBindingError() }
        guard profile.status == .connected, profile.sessionState.hasOriginSession else { throw DownloadOwnershipError.unavailableProfile }
    }

    private func start(_ record: DownloadRecord) {
        guard let attemptID = record.attemptID else { return }
        activeTasks[record.id]?.task.cancel()
        let task = Task {
            await execute(recordID: record.id, attemptID: attemptID)
            if activeTasks[record.id]?.attemptID == attemptID { activeTasks[record.id] = nil }
            scheduleRetry()
        }
        activeTasks[record.id] = Flight(attemptID: attemptID, task: task)
    }

    private func execute(recordID: String, attemptID: String) async {
        var destination: URL?
        defer {
            // Every return path retires only this attempt's unreferenced bytes.
            if let destination, let records = try? allRecords(), !records.contains(where: { $0.localPath == destination.path }) {
                if (try? validateManagedURL(destination)) != nil { try? FileManager.default.removeItem(at: destination) }
            }
        }
        do {
            guard let initial = try current(recordID, attemptID: attemptID, status: .queued) else { return }
            try await requireOwner(initial)
            try Task.checkCancellation()
            guard var running = try current(recordID, attemptID: attemptID, status: .queued),
                  let owner = running.owner, let relative = running.destinationRelativePath else { return }
            let expectedPath = "roms/installs/\(attemptID)/\(running.fileName)"
            guard UUID(uuidString: attemptID) != nil, relative == expectedPath else { throw DownloadOwnershipError.invalidDestination }
            let target = libraryStore.rootDirectory.appending(path: relative)
            try validateManagedURL(target)
            destination = target
            let queued = running
            running.status = .running; running.startedAtEpochMS = now(); running.updatedAtEpochMS = now()
            guard try commit(running, replacing: queued) else { return }
            let source = buildRomContentURL(baseURL: owner.baseURL, romID: running.romID, fileName: running.fileName)
            let bytes = try await transfer(running.profileID, owner.baseURL, source, target, running.fileSizeBytes > 0 ? running.fileSizeBytes : nil)
            try Task.checkCancellation()
            try await requireOwner(running)
            try Task.checkCancellation()
            guard var completed = try current(recordID, attemptID: attemptID, status: .running) else { return }
            try validateManagedURL(target)
            let actual = (try FileManager.default.attributesOfItem(atPath: target.path)[.size] as? NSNumber)?.int64Value
            guard actual == bytes, bytes >= 0, running.fileSizeBytes <= 0 || bytes == running.fileSizeBytes else { throw RommClientError.invalidResponse }
            let expected = completed
            completed.contentSHA256 = try Self.sha256(target)
            completed.status = .completed; completed.localPath = target.path
            completed.progressPercent = 100; completed.bytesDownloaded = bytes; completed.totalBytes = bytes
            completed.completedAtEpochMS = now(); completed.updatedAtEpochMS = now(); completed.lastError = nil
            // No suspension between the final ownership/attempt check and this durable pointer commit.
            _ = try commit(completed, replacing: expected)
        } catch {
            if var failed = try? current(recordID, attemptID: attemptID, status: .running)
                ?? current(recordID, attemptID: attemptID, status: .queued) {
                let expected = failed
                failed.parkedForAuthentication = Self.authenticationFailure(error)
                if error is CancellationError || Task.isCancelled {
                    failed.status = .canceled; failed.lastError = nil
                } else if let network = error as? URLError,
                          [.notConnectedToInternet, .networkConnectionLost, .timedOut, .cannotConnectToHost, .cannotFindHost, .dnsLookupFailed].contains(network.code) {
                    failed.status = .queued; failed.retryAfterEpochMS = now() + 30_000
                    failed.lastError = "Waiting to retry the connection to the original server."
                } else {
                    failed.status = .failed; failed.lastError = error.localizedDescription
                }
                failed.updatedAtEpochMS = now()
                _ = try? commit(failed, replacing: expected)
            }
        }
    }

    private func scheduleRetry() {
        let deadline = (try? allRecords())?.filter { $0.status == .queued }.compactMap(\.retryAfterEpochMS).min()
        guard deadline != retryDeadline else { return }
        retryTask?.cancel(); retryDeadline = deadline
        guard let deadline else { retryTask = nil; return }
        let delay = max(0, deadline - now())
        retryTask = Task { [weak self] in
            do { try await Task.sleep(for: .milliseconds(delay)) } catch { return }
            await self?.retryWake()
        }
    }

    private func retryWake() async {
        retryDeadline = nil; retryTask = nil
        try? await resumePending()
    }

    private func current(_ id: String, attemptID: String, status: DownloadStatus) throws -> DownloadRecord? {
        guard let record = try record(recordID: id), record.attemptID == attemptID, record.status == status else { return nil }
        return record
    }

    private func allRecords() throws -> [DownloadRecord] {
        try database.write { db in
            try Row.fetchAll(db, sql: "SELECT payload FROM download_records").map { try relocatedRecord(database.decoded(DownloadRecord.self, from: $0["payload"]), db: db) }
                .sorted { $0.enqueuedAtEpochMS > $1.enqueuedAtEpochMS }
        }
    }

    private func record(recordID: String) throws -> DownloadRecord? {
        let parts = recordID.split(separator: ":", omittingEmptySubsequences: false)
        guard parts.count >= 3, let romID = Int(parts[parts.count - 2]), let fileID = Int(parts.last!) else { return nil }
        let profileID = parts.dropLast(2).joined(separator: ":")
        return try database.write { db in
            guard let row = try Row.fetchOne(db, sql: "SELECT payload FROM download_records WHERE profile_id = ? AND rom_id = ? AND file_id = ?", arguments: [profileID, romID, fileID]) else { return nil }
            return try relocatedRecord(database.decoded(DownloadRecord.self, from: row["payload"]), db: db)
        }
    }

    private func commit(_ record: DownloadRecord, replacing expected: DownloadRecord?) throws -> Bool {
        try database.write { db in
            let row = try Row.fetchOne(db, sql: "SELECT payload FROM download_records WHERE profile_id = ? AND rom_id = ? AND file_id = ?", arguments: [record.profileID, record.romID, record.fileID])
            let current = try row.map { try database.decoded(DownloadRecord.self, from: $0["payload"]) }
            guard current == expected else { return false }
            guard try Bool.fetchOne(db, sql: "SELECT EXISTS(SELECT 1 FROM server_profiles WHERE id = ?)", arguments: [record.profileID]) == true else {
                throw DownloadOwnershipError.unavailableProfile
            }
            try db.execute(sql: "INSERT INTO download_records (profile_id, rom_id, file_id, payload) VALUES (?, ?, ?, ?) ON CONFLICT(profile_id, rom_id, file_id) DO UPDATE SET payload = excluded.payload",
                           arguments: [record.profileID, record.romID, record.fileID, try database.encoded(record)])
            return true
        }
    }

    /// iOS may move an application's data container when installing an update.
    /// Recover only the exact owned install destination, verified against its digest.
    private func relocatedRecord(_ record: DownloadRecord, db: Database) throws -> DownloadRecord {
        guard record.status == .completed, record.owner != nil,
              let path = record.localPath,
              let relative = record.destinationRelativePath,
              let attempt = record.attemptID,
              relative == "roms/installs/\(attempt)/\(record.fileName)",
              UUID(uuidString: attempt) != nil,
              !record.fileName.contains("/"), !record.fileName.contains("\\"),
              let digest = record.contentSHA256 else { return record }
        let target = libraryStore.rootDirectory.appending(path: relative)
        try validateManagedURL(target)
        guard path != target.path else { return record }
        guard FileManager.default.fileExists(atPath: target.path),
              try Self.sha256(target) == digest else { return record }
        var relocated = record
        relocated.localPath = target.path
        try db.execute(sql: "UPDATE download_records SET payload = ? WHERE profile_id = ? AND rom_id = ? AND file_id = ?",
                       arguments: [try database.encoded(relocated), record.profileID, record.romID, record.fileID])
        return relocated
    }

    private static func authenticationFailure(_ error: Error) -> Bool {
        if case DownloadOwnershipError.unavailableProfile = error { return true }
        if case RommClientError.http(let status, _) = error { return status == 401 || status == 403 }
        if let error = error as? URLError { return error.code == .userAuthenticationRequired }
        if let error = error as? TokenRefreshError {
            switch error {
            case .invalidCredentials, .accessRequired: return true
            default: return false
            }
        }
        return false
    }

    private func validateManagedURL(_ url: URL) throws {
        let root = libraryStore.rootDirectory.resolvingSymlinksInPath().standardizedFileURL
        let resolved = url.resolvingSymlinksInPath().standardizedFileURL
        guard resolved.path.hasPrefix(root.path + "/"), resolved != root else { throw DownloadOwnershipError.invalidDestination }
    }

    static func sha256(_ url: URL) throws -> String {
        let reader = try FileHandle(forReadingFrom: url)
        defer { try? reader.close() }
        var digest = SHA256()
        while let data = try reader.read(upToCount: 1_048_576), !data.isEmpty { digest.update(data: data) }
        return digest.finalize().map { String(format: "%02x", $0) }.joined()
    }

    private func now() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
