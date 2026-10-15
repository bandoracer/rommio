import Foundation
import GRDB
import RommioContract

public protocol DownloadQueue: Sendable {
    func records(profileID: String?) async throws -> [DownloadRecord]
    func enqueue(rom: RomDTO, file: RomFileDTO) async throws
    func retry(recordID: String) async throws
    func cancel(recordID: String) async throws
    func deleteLocalContent(recordID: String) async throws
    func verifiedInstall(profileID: String, romID: Int, fileID: Int) async throws -> VerifiedInstall?
    func adoptExtracted(_ staged: URL, recordID: String, archiveSHA256: String, memberPath: String, sha256: String) async throws -> VerifiedInstall
    func deleteArchive(recordID: String) async throws
    func keepArchive(recordID: String) async throws
}

/// A completed download checked against its recorded digests, and the file a player opens.
public struct VerifiedInstall: Sendable, Equatable {
    public struct Extracted: Sendable, Equatable {
        public let url: URL
        public let memberPath: String
        public let sha256: String
    }
    public let recordID: String
    /// The download, an archive for an unzipped game. Nil once the user deleted the archive.
    public let download: URL?
    /// The download's digest. An unzipped game keeps its archive's digest after the archive is gone.
    public let downloadSHA256: String?
    public let extracted: Extracted?
    public let playable: URL
    /// The playable file's digest and its identity when it matched just now. Nil for a legacy
    /// record without a verified owner, which was never hashed.
    public let playableSHA256: String?
    public let playableIdentity: FileIdentity?
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
    /// Moved installs whose digest didn't match: path, size, modification time and expected digest.
    private var unverifiedRelocations: Set<String> = []

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
            let sameFile = existing.status == .completed && existing.fileName == file.fileName
                && existing.fileSizeBytes == file.fileSizeBytes
            // An unzipped game is installed, with or without its archive.
            if sameFile, let extracted = existing.extracted,
               FileManager.default.fileExists(atPath: libraryStore.rootDirectory.appending(path: extracted.relativePath).path) { return }
            if let path = existing.localPath, let digest = existing.contentSHA256 {
                let installed = URL(fileURLWithPath: path)
                var identity: FileIdentity?, verified = false
                if (try? validateManagedURL(installed)) != nil, FileManager.default.fileExists(atPath: path) {
                    do { identity = try await verify(installed, sha256: digest, known: existing.verifiedFile); verified = true } catch {}
                }
                if verified {
                    if sameFile { return }
                    next.localPath = path
                    next.contentSHA256 = digest
                    next.verifiedFile = identity
                }
            }
        }
        next.owner = owner
        try await requireOwner(next)
        try prepareAttempt(&next)
        guard try commit(next, replacing: previous) else { return }
        // A new download replaces the game unzipped from the old one.
        if let old = previous?.extracted { removeUnreferenced(old) }
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
        let extracted = next.extracted
        // Detach first. An interrupted unlink cannot resurrect an install or accept a late completion.
        next.status = .canceled; next.attemptID = UUID().uuidString.lowercased()
        next.localPath = nil; next.contentSHA256 = nil; next.retryAfterEpochMS = nil
        next.verifiedFile = nil; next.extracted = nil; next.keepsArchive = nil
        next.updatedAtEpochMS = now(); next.lastError = nil
        guard try commit(next, replacing: previous) else { return }
        activeTasks[recordID]?.task.cancel()
        if let path, try !allRecords().contains(where: { $0.localPath == path }) {
            let url = URL(fileURLWithPath: path)
            try validateManagedURL(url)
            if FileManager.default.fileExists(atPath: path) { try FileManager.default.removeItem(at: url) }
        }
        if let extracted { removeUnreferenced(extracted) }
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

    /// The download itself, checked against its digest. New installs are selected through their
    /// durable record, never inferred from a filename.
    public func verifiedLocalURL(profileID: String, romID: Int, fileID: Int) async throws -> URL? {
        guard let saved = try await ownedInstall(profileID: profileID, romID: romID, fileID: fileID) else { return nil }
        return try await verifiedDownload(saved)?.url
    }

    /// What a player opens: the unzipped game when there is one, else the download, each checked
    /// against its digest. A file is hashed once and again only after its identity changes, so
    /// launching a game doesn't read gigabytes.
    public func verifiedInstall(profileID: String, romID: Int, fileID: Int) async throws -> VerifiedInstall? {
        guard let saved = try await ownedInstall(profileID: profileID, romID: romID, fileID: fileID) else { return nil }
        let download = saved.localPath.map { URL(fileURLWithPath: $0) }.flatMap { FileManager.default.fileExists(atPath: $0.path) ? $0 : nil }
        if let extracted = saved.extracted, saved.owner != nil {
            let url = libraryStore.rootDirectory.appending(path: extracted.relativePath)
            try validateManagedURL(url)
            if FileManager.default.fileExists(atPath: url.path) {
                let identity = try await verify(url, sha256: extracted.sha256, known: extracted.verifiedFile)
                if let identity, identity != extracted.verifiedFile {
                    try rememberVerified(saved) { $0.extracted?.verifiedFile = identity }
                }
                return VerifiedInstall(recordID: saved.id, download: download, downloadSHA256: saved.contentSHA256,
                                       extracted: .init(url: url, memberPath: extracted.memberPath, sha256: extracted.sha256),
                                       playable: url, playableSHA256: extracted.sha256, playableIdentity: identity)
            }
        }
        guard let verified = try await verifiedDownload(saved) else { return nil }
        return VerifiedInstall(recordID: saved.id, download: verified.url, downloadSHA256: saved.contentSHA256, extracted: nil,
                               playable: verified.url, playableSHA256: saved.owner == nil ? nil : saved.contentSHA256,
                               playableIdentity: verified.identity)
    }

    /// Keeps a game unzipped from a verified archive download next to it, so later launches open
    /// it directly. `staged` must be in the library; it is moved, not copied.
    public func adoptExtracted(_ staged: URL, recordID: String, archiveSHA256: String, memberPath: String, sha256: String) async throws -> VerifiedInstall {
        guard var next = try record(recordID: recordID), next.status == .completed, next.owner != nil,
              next.contentSHA256 == archiveSHA256, let archive = next.localPath,
              let attempt = next.attemptID, next.destinationRelativePath == "roms/installs/\(attempt)/\(next.fileName)" else {
            throw DownloadOwnershipError.corruptInstall
        }
        let expected = next
        let name = URL(fileURLWithPath: memberPath).lastPathComponent
        guard !name.isEmpty, name != ".", name != "..", name != next.fileName, !name.contains("\\"),
              !name.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else {
            throw DownloadOwnershipError.invalidDestination
        }
        let relative = "roms/installs/\(attempt)/\(name)"
        let target = libraryStore.rootDirectory.appending(path: relative)
        try validateManagedURL(staged); try validateManagedURL(target)
        if FileManager.default.fileExists(atPath: target.path) { try FileManager.default.removeItem(at: target) }
        try FileManager.default.moveItem(at: staged, to: target)
        let identity = FileIdentity(target)
        next.extracted = ExtractedContent(relativePath: relative, memberPath: memberPath, sha256: sha256,
                                          byteCount: identity?.size ?? 0, verifiedFile: identity)
        next.updatedAtEpochMS = now()
        guard try commit(next, replacing: expected) else {
            try? FileManager.default.removeItem(at: target)
            throw DownloadOwnershipError.corruptInstall
        }
        return VerifiedInstall(recordID: recordID, download: URL(fileURLWithPath: archive), downloadSHA256: archiveSHA256,
                               extracted: .init(url: target, memberPath: memberPath, sha256: sha256),
                               playable: target, playableSHA256: sha256, playableIdentity: identity)
    }

    /// Deletes an unzipped game's archive, which the user agreed to. The unzipped game must still
    /// match its digest. The record keeps the archive's digest, which the game's progress is bound to.
    public func deleteArchive(recordID: String) async throws {
        guard let saved = try record(recordID: recordID), let extracted = saved.extracted, let path = saved.localPath else { return }
        let game = libraryStore.rootDirectory.appending(path: extracted.relativePath)
        try validateManagedURL(game)
        guard FileManager.default.fileExists(atPath: game.path) else { throw DownloadOwnershipError.corruptInstall }
        let identity = try await verify(game, sha256: extracted.sha256, known: extracted.verifiedFile)
        // Re-read after hashing suspends; a delete or new download may have won meanwhile.
        guard var next = try record(recordID: recordID), next == saved else { return }
        next.localPath = nil; next.verifiedFile = nil; next.keepsArchive = nil
        if let identity { next.extracted?.verifiedFile = identity }
        next.updatedAtEpochMS = now()
        guard try commit(next, replacing: saved) else { return }
        if try !allRecords().contains(where: { $0.localPath == path }) {
            let url = URL(fileURLWithPath: path)
            try validateManagedURL(url)
            if FileManager.default.fileExists(atPath: path) { try FileManager.default.removeItem(at: url) }
        }
    }

    public func keepArchive(recordID: String) async throws {
        guard var next = try record(recordID: recordID), next.hasRemovableArchive, next.keepsArchive != true else { return }
        let expected = next
        next.keepsArchive = true; next.updatedAtEpochMS = now()
        _ = try commit(next, replacing: expected)
    }

    private func ownedInstall(profileID: String, romID: Int, fileID: Int) async throws -> DownloadRecord? {
        guard let saved = try record(recordID: "\(profileID):\(romID):\(fileID)"),
              saved.status == .completed || saved.status == .failed else { return nil }
        if let owner = saved.owner {
            guard let profile = try await profileStore.profile(id: profileID), profile.baseURL == owner.baseURL,
                  try await authController.verifiedAccount(profileID: profileID) == owner else { throw AccountBindingError() }
        }
        return saved
    }

    /// The download checked against its digest. A legacy record without a verified owner was never
    /// hashed, so it isn't now either.
    private func verifiedDownload(_ saved: DownloadRecord) async throws -> (url: URL, identity: FileIdentity?)? {
        guard let path = saved.localPath else { return nil }
        let url = URL(fileURLWithPath: path)
        try validateManagedURL(url)
        guard FileManager.default.fileExists(atPath: path) else { return nil }
        guard saved.owner != nil else { return (url, nil) }
        guard let digest = saved.contentSHA256 else { throw DownloadOwnershipError.corruptInstall }
        let identity = try await verify(url, sha256: digest, known: saved.verifiedFile)
        if let identity, identity != saved.verifiedFile { try rememberVerified(saved) { $0.verifiedFile = identity } }
        return (url, identity)
    }

    /// Checks a file against its digest unless its identity is the one recorded when it last
    /// matched. Returns its identity, or nil if it changed while being hashed.
    private func verify(_ url: URL, sha256: String, known: FileIdentity?) async throws -> FileIdentity? {
        let before = FileIdentity(url)
        if let before, before == known { return before }
        // A game can be gigabytes: hash off this actor, which keeps serving the UI meanwhile.
        let actual = try await Task.detached(priority: .userInitiated) { try ProgressFileHash.sha256(url) }.value
        guard actual == sha256 else { throw DownloadOwnershipError.corruptInstall }
        let after = FileIdentity(url)
        return after == before ? after : nil
    }

    /// Records a verified identity, unless the record changed meanwhile.
    private func rememberVerified(_ saved: DownloadRecord, _ update: (inout DownloadRecord) -> Void) throws {
        guard let current = try record(recordID: saved.id), current == saved else { return }
        var next = current
        update(&next)
        _ = try commit(next, replacing: current)
    }

    /// Deletes an unzipped game that no record points to any more.
    private func removeUnreferenced(_ extracted: ExtractedContent) {
        guard let records = try? allRecords(),
              !records.contains(where: { $0.extracted?.relativePath == extracted.relativePath }) else { return }
        let url = libraryStore.rootDirectory.appending(path: extracted.relativePath)
        if (try? validateManagedURL(url)) != nil { try? FileManager.default.removeItem(at: url) }
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
            // Hash before the final checks and off this actor: a game can be gigabytes, and the queue
            // keeps serving the UI meanwhile. The identity kept with the digest spares launches from
            // hashing it again.
            let hashed = FileIdentity(target)
            let digest = try await Task.detached(priority: .utility) { try ProgressFileHash.sha256(target) }.value
            try await requireOwner(running)
            try Task.checkCancellation()
            guard var completed = try current(recordID, attemptID: attemptID, status: .running) else { return }
            try validateManagedURL(target)
            let actual = (try FileManager.default.attributesOfItem(atPath: target.path)[.size] as? NSNumber)?.int64Value
            guard actual == bytes, bytes >= 0, running.fileSizeBytes <= 0 || bytes == running.fileSizeBytes,
                  let hashed, FileIdentity(target) == hashed else { throw RommClientError.invalidResponse }
            let expected = completed
            let replaced = completed.extracted
            completed.contentSHA256 = digest; completed.verifiedFile = hashed
            // A game unzipped from the previous download doesn't belong to these bytes.
            completed.extracted = nil; completed.keepsArchive = nil
            completed.status = .completed; completed.localPath = target.path
            completed.progressPercent = 100; completed.bytesDownloaded = bytes; completed.totalBytes = bytes
            completed.completedAtEpochMS = now(); completed.updatedAtEpochMS = now(); completed.lastError = nil
            // No suspension between the final ownership/attempt check and this durable pointer commit.
            if try commit(completed, replacing: expected), let replaced { removeUnreferenced(replaced) }
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
        let stored = try database.read { db in
            try Row.fetchAll(db, sql: "SELECT payload FROM download_records").map { try database.decoded(DownloadRecord.self, from: $0["payload"]) }
        }
        return try stored.map(relocated).sorted { $0.enqueuedAtEpochMS > $1.enqueuedAtEpochMS }
    }

    private func record(recordID: String) throws -> DownloadRecord? {
        let parts = recordID.split(separator: ":", omittingEmptySubsequences: false)
        guard parts.count >= 3, let romID = Int(parts[parts.count - 2]), let fileID = Int(parts.last!) else { return nil }
        let profileID = parts.dropLast(2).joined(separator: ":")
        let stored = try database.read { db -> DownloadRecord? in
            guard let row = try Row.fetchOne(db, sql: "SELECT payload FROM download_records WHERE profile_id = ? AND rom_id = ? AND file_id = ?", arguments: [profileID, romID, fileID]) else { return nil }
            return try database.decoded(DownloadRecord.self, from: row["payload"])
        }
        return try stored.map(relocated)
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
    /// Recover only the exact owned install destination, verified against its digest. A game can be
    /// gigabytes, so the hash runs outside any database transaction, the new path is adopted only if
    /// the record is unchanged meanwhile, and a file that fails is not hashed again until it changes.
    private func relocated(_ record: DownloadRecord) throws -> DownloadRecord {
        guard record.status == .completed, record.owner != nil,
              let path = record.localPath,
              let relative = record.destinationRelativePath,
              let attempt = record.attemptID,
              relative == "roms/installs/\(attempt)/\(record.fileName)",
              UUID(uuidString: attempt) != nil,
              !record.fileName.contains("/"), !record.fileName.contains("\\"),
              let digest = record.contentSHA256 else { return record }
        let target = libraryStore.rootDirectory.appending(path: relative)
        guard path != target.path else { return record }
        try validateManagedURL(target)
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: target.path),
              let size = (attributes[.size] as? NSNumber)?.int64Value,
              record.bytesDownloaded <= 0 || size == record.bytesDownloaded else { return record }
        let modified = (attributes[.modificationDate] as? Date)?.timeIntervalSince1970 ?? 0
        let candidate = "\(target.path)|\(size)|\(modified)|\(digest)"
        guard !unverifiedRelocations.contains(candidate) else { return record }
        let identity = FileIdentity(target)
        guard try Self.sha256(target) == digest else {
            unverifiedRelocations.insert(candidate)
            return record
        }
        var moved = record
        moved.localPath = target.path
        moved.verifiedFile = FileIdentity(target) == identity ? identity : nil
        let adopted = try database.write { db -> Bool in
            guard let row = try Row.fetchOne(db, sql: "SELECT payload FROM download_records WHERE profile_id = ? AND rom_id = ? AND file_id = ?", arguments: [record.profileID, record.romID, record.fileID]),
                  try database.decoded(DownloadRecord.self, from: row["payload"]) == record else { return false }
            try db.execute(sql: "UPDATE download_records SET payload = ? WHERE profile_id = ? AND rom_id = ? AND file_id = ?",
                           arguments: [try database.encoded(moved), record.profileID, record.romID, record.fileID])
            return true
        }
        return adopted ? moved : record
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
        try ProgressFileHash.sha256(url)
    }

    private func now() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
