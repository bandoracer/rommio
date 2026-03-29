import Foundation
import GRDB
import RommioContract

public protocol DownloadQueue: Sendable {
    func records(profileID: String?) async throws -> [DownloadRecord]
    func enqueue(rom: RomDTO, file: RomFileDTO) async throws
    func retry(recordID: String) async throws
    func cancel(recordID: String) async throws
    func deleteLocalContent(recordID: String) async throws
}

public actor ManagedDownloadQueue: DownloadQueue {
    private let database: AppDatabase
    private let profileStore: ServerProfileStore
    private let secretStore: SecretStore
    private let libraryStore: LibraryStore
    private var activeTasks: [String: Task<Void, Never>] = [:]

    public init(
        database: AppDatabase,
        profileStore: ServerProfileStore,
        secretStore: SecretStore,
        libraryStore: LibraryStore
    ) {
        self.database = database
        self.profileStore = profileStore
        self.secretStore = secretStore
        self.libraryStore = libraryStore
    }

    public func records(profileID: String? = nil) async throws -> [DownloadRecord] {
        let scopeID = if let profileID { profileID } else { try await profileStore.activeProfile()?.id }
        return try database.read { db in
            let rows: [Row]
            if let scopeID {
                rows = try Row.fetchAll(db, sql: "SELECT payload FROM download_records WHERE profile_id = ? ORDER BY payload DESC", arguments: [scopeID])
            } else {
                rows = try Row.fetchAll(db, sql: "SELECT payload FROM download_records")
            }
            return try rows.map { try database.decoded(DownloadRecord.self, from: $0["payload"]) }
                .sorted { $0.enqueuedAtEpochMS > $1.enqueuedAtEpochMS }
        }
    }

    public func enqueue(rom: RomDTO, file: RomFileDTO) async throws {
        guard let profile = try await profileStore.activeProfile() else {
            throw URLError(.userAuthenticationRequired)
        }
        let record = DownloadRecord(
            profileID: profile.id,
            romID: rom.id,
            fileID: file.id,
            romName: rom.displayName,
            platformSlug: rom.platformSlug,
            fileName: file.fileName,
            fileSizeBytes: file.fileSizeBytes,
            status: .queued,
            totalBytes: file.fileSizeBytes,
            enqueuedAtEpochMS: now(),
            updatedAtEpochMS: now()
        )
        try upsert(record)
        startIfNeeded(recordID: record.id)
    }

    public func retry(recordID: String) async throws {
        guard var record = try await record(recordID: recordID) else { return }
        record.status = .queued
        record.lastError = nil
        record.progressPercent = 0
        record.bytesDownloaded = 0
        record.startedAtEpochMS = nil
        record.completedAtEpochMS = nil
        record.updatedAtEpochMS = now()
        try upsert(record)
        startIfNeeded(recordID: recordID)
    }

    public func cancel(recordID: String) async throws {
        activeTasks[recordID]?.cancel()
        activeTasks[recordID] = nil
        guard var record = try await record(recordID: recordID) else { return }
        record.status = .canceled
        record.lastError = "Canceled."
        record.updatedAtEpochMS = now()
        try upsert(record)
    }

    public func deleteLocalContent(recordID: String) async throws {
        guard var record = try await record(recordID: recordID) else { return }
        if let localPath = record.localPath, FileManager.default.fileExists(atPath: localPath) {
            try FileManager.default.removeItem(atPath: localPath)
        }
        record.localPath = nil
        record.updatedAtEpochMS = now()
        try upsert(record)
    }

    private func startIfNeeded(recordID: String) {
        guard activeTasks[recordID] == nil else { return }
        activeTasks[recordID] = Task {
            await execute(recordID: recordID)
            clearActiveTask(recordID: recordID)
        }
    }

    private func execute(recordID: String) async {
        do {
            guard let record = try await record(recordID: recordID),
                  let profile = try await profileStore.profile(id: record.profileID) else { return }

            var running = record
            running.status = .running
            running.startedAtEpochMS = now()
            running.updatedAtEpochMS = now()
            try upsert(running)

            let absoluteURL = buildRomContentURL(baseURL: profile.baseURL, romID: record.romID, fileName: record.fileName)
            var request = URLRequest(url: absoluteURL)
            request.httpMethod = "GET"
            request = try await AuthenticatedRequestDecorator(
                profileID: profile.id,
                secretStore: secretStore
            ) { [profileStore] requestedProfileID in
                try await profileStore.profile(id: requestedProfileID)
            }.decorate(request)
            let (temporaryURL, _) = try await URLSession.shared.download(for: request)

            let destination = libraryStore.romURL(platformSlug: record.platformSlug, fileName: record.fileName)
            try FileManager.default.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
            if FileManager.default.fileExists(atPath: destination.path) {
                try FileManager.default.removeItem(at: destination)
            }
            try FileManager.default.moveItem(at: temporaryURL, to: destination)

            var completed = running
            completed.status = .completed
            completed.localPath = destination.path
            completed.progressPercent = 100
            completed.bytesDownloaded = record.fileSizeBytes
            completed.totalBytes = record.fileSizeBytes
            completed.completedAtEpochMS = now()
            completed.updatedAtEpochMS = now()
            try upsert(completed)
        } catch is CancellationError {
            try? await cancel(recordID: recordID)
        } catch {
            if var failed = try? await record(recordID: recordID) {
                failed.status = .failed
                failed.lastError = error.localizedDescription
                failed.updatedAtEpochMS = now()
                try? upsert(failed)
            }
        }
    }

    private func clearActiveTask(recordID: String) {
        activeTasks[recordID] = nil
    }

    private func record(recordID: String) async throws -> DownloadRecord? {
        try database.read { db in
            let parts = recordID.split(separator: ":")
            guard parts.count == 3,
                  let romID = Int(parts[1]),
                  let fileID = Int(parts[2]) else {
                return nil
            }
            guard let row = try Row.fetchOne(
                db,
                sql: "SELECT payload FROM download_records WHERE profile_id = ? AND rom_id = ? AND file_id = ?",
                arguments: [String(parts[0]), romID, fileID]
            ) else {
                return nil
            }
            return try database.decoded(DownloadRecord.self, from: row["payload"])
        }
    }

    private func upsert(_ record: DownloadRecord) throws {
        try database.write { db in
            try db.execute(
                sql: """
                    INSERT INTO download_records (profile_id, rom_id, file_id, payload)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT(profile_id, rom_id, file_id) DO UPDATE SET payload = excluded.payload
                    """,
                arguments: [record.profileID, record.romID, record.fileID, try database.encoded(record)]
            )
        }
    }

    private func now() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}
