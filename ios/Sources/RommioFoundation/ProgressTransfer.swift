import Foundation
import GRDB
import Darwin
import RommioContract

final class ProgressStorageLock {
    private var fd: Int32 = -1
    init(_ url: URL) throws {
        try ProgressArchive.directory(url.deletingLastPathComponent())
        fd = Darwin.open(url.path, O_RDWR | O_CREAT | O_NOFOLLOW, S_IRUSR | S_IWUSR)
        guard fd >= 0 else { throw POSIXError(.EIO) }
        guard flock(fd, LOCK_EX | LOCK_NB) == 0 else { Darwin.close(fd); fd = -1; throw ProgressStoreError.invalid("Progress storage is busy") }
    }
    deinit { if fd >= 0 { flock(fd, LOCK_UN); Darwin.close(fd) } }
}
public struct ProgressImportPreview: Sendable, Identifiable {
    public let id: String
    public let operations: Int
    public let files: Int
    public let damagedArtifacts: Int
    public let containsLegacy: Bool
}
public enum ProgressImportBoundary: CaseIterable, Sendable { case journalReady, originalMoved, replacementMoved, metadataCommitted, completed }
struct ProgressImportJournal: Codable {
    var version = 1
    var rollback: Bool? = nil
    let id: String
    let hadOriginal: Bool
    let previous: ProgressBackupSnapshot
    let replacement: ProgressBackupSnapshot
}
/// Called only under application maintenance and the stable sibling process lock.
struct ProgressTransfer {
    let database: AppDatabase
    let root: URL
    let library: URL
    var recovery: URL { root.appendingPathExtension("recovery") }
    var lock: URL { root.appendingPathExtension("owner.lock") }
    var journalURL: URL { recovery.appending(path: "pending.json") }
    func workspace(_ id: String) throws -> URL {
        guard UUID(uuidString: id)?.uuidString.lowercased() == id else { throw ProgressStoreError.invalid("Invalid recovery identifier") }
        return recovery.appending(path: id)
    }
    func snapshot() throws -> ProgressBackupSnapshot { try database.read { try ProgressBackupSnapshot.read($0) } }
    func damaged(_ snapshot: ProgressBackupSnapshot, files: URL) throws -> Int {
        var seen = Set<String>(); var count = 0
        for row in snapshot.tables["progress_v2_operations"] ?? [] {
            guard case .text(let ns) = row[0], case .text(let content) = row[1], case .blob(let payload) = row[3] else { throw ProgressStoreError.invalid("Invalid operation row") }
            for artifact in try ProgressProtocol.decode(payload).checkpoints.flatMap(\.artifacts) {
                let key = "store/\(ns)/\(content)/blobs/\(artifact.sha256)"
                if seen.insert(key).inserted {
                    let url = files.appending(path: key)
                    if (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value != artifact.byteLength || (try? ProgressFileHash.sha256(url)) != artifact.sha256 { count += 1 }
                }
            }
        }
        return count
    }
    func export(to destination: URL) throws {
        try recover()
        let temporary = recovery.appending(path: "export-\(UUID().uuidString.lowercased())")
        defer { try? FileManager.default.removeItem(at: temporary) }
        let content = temporary.appending(path: "files")
        try ProgressArchive.directory(content)
        try copyTree(root, to: content)
        // Legacy records remain opaque recovery material; imported profile IDs never authenticate an account.
        let legacy = content.appending(path: "legacy-recovery/\(UUID().uuidString.lowercased())")
        for name in ["saves", "states", "screenshots"] { try copyTree(library.appending(path: name), to: legacy.appending(path: name)) }
        let old: [String: [Data]] = try database.read { db in
            var rows: [String: [Data]] = [:]
            for table in ["game_sync_journal", "save_state_sync_journal", "recovery_states"] { rows[table] = try Data.fetchAll(db, sql: "SELECT payload FROM \(table)") }
            return rows
        }
        if old.values.contains(where: { !$0.isEmpty }) { try ProgressDurability.write(JSONEncoder().encode(old), to: legacy.appending(path: "records.json")) }
        let snapshot = try snapshot()
        _ = try snapshot.validate(at: temporary.appending(path: "validation"))
        try ProgressArchive.write(root: content, snapshot: snapshot, to: destination)
    }
    private func copyTree(_ from: URL, to destination: URL) throws {
        for entry in try ProgressArchive.inventory(from) {
            let target = destination.appending(path: entry.path)
            if entry.sha256 == nil { try ProgressArchive.directory(target) }
            else {
                let output = try ProgressArchive.output(target); defer { try? output.close() }
                let input = try FileHandle(forReadingFrom: from.appending(path: entry.path)); defer { try? input.close() }
                try ProgressArchive.copy(input, output, length: entry.length); try output.synchronize(); try output.close()
                try ProgressArchive.check(try ProgressFileHash.sha256(target) == entry.sha256, "Progress changed while taking backup")
                try ProgressArchive.sync(target.deletingLastPathComponent())
            }
        }
    }
    func inspect(_ archive: URL) throws -> ProgressImportPreview {
        try recover()
        let id = UUID().uuidString.lowercased(), work = try workspace(id)
        do {
            let manifest = try ProgressArchive.extract(archive, to: work.appending(path: "incoming"))
            _ = try manifest.snapshot.validate(at: work.appending(path: "validation"))
            try ProgressDurability.write(JSONEncoder().encode(manifest), to: work.appending(path: "manifest.json"))
            return ProgressImportPreview(id: id, operations: manifest.snapshot.tables["progress_v2_operations"]?.count ?? 0, files: manifest.entries.filter { $0.sha256 != nil }.count, damagedArtifacts: try damaged(manifest.snapshot, files: work.appending(path: "incoming")), containsLegacy: manifest.entries.contains { $0.path.hasPrefix("legacy-recovery/") })
        } catch { try? FileManager.default.removeItem(at: work); throw error }
    }
    func activate(_ id: String, fault: (ProgressImportBoundary) throws -> Void = { _ in }) throws {
        try recover()
        let work = try workspace(id)
        let manifest = try JSONDecoder().decode(ProgressArchiveManifest.self, from: Data(contentsOf: work.appending(path: "manifest.json")))
        let inventory = try ProgressArchive.inventory(work.appending(path: "incoming"))
        try ProgressArchive.check(Set(inventory.map(\.path)) == Set(manifest.entries.map(\.path)) && Dictionary(uniqueKeysWithValues: inventory.map { ($0.path, $0) }) == Dictionary(uniqueKeysWithValues: manifest.entries.map { ($0.path, $0) }), "Staged backup changed; inspect it again")
        _ = try manifest.snapshot.validate(at: work.appending(path: "activation-validation"))
        // Device identity belongs to this installation and never comes from the archive.
        let device = root.appending(path: "device.json")
        if FileManager.default.fileExists(atPath: device.path) { try ProgressDurability.write(Data(contentsOf: device), to: work.appending(path: "incoming/device.json")) }
        try ProgressDurability.write(Data(id.utf8), to: work.appending(path: "incoming/.generation"))
        let journal = ProgressImportJournal(id: id, hadOriginal: FileManager.default.fileExists(atPath: root.path), previous: try snapshot(), replacement: manifest.snapshot)
        try ProgressDurability.write(JSONEncoder().encode(journal), to: work.appending(path: "transaction.json"))
        try ProgressDurability.write(JSONEncoder().encode(journal), to: journalURL)
        try fault(.journalReady)
        try complete(journal, fault: fault)
    }
    func recover(fault: (ProgressImportBoundary) throws -> Void = { _ in }) throws {
        guard FileManager.default.fileExists(atPath: journalURL.path) else { return }
        let journal: ProgressImportJournal
        do { journal = try JSONDecoder().decode(ProgressImportJournal.self, from: Data(contentsOf: journalURL)) }
        catch {
            let candidates = try FileManager.default.contentsOfDirectory(at: recovery, includingPropertiesForKeys: nil).filter {
                FileManager.default.fileExists(atPath: $0.appending(path: "transaction.json").path) && !FileManager.default.fileExists(atPath: $0.appending(path: "completed.json").path)
            }
            guard candidates.count == 1 else { throw ProgressStoreError.invalid("Import journal is damaged. Retained files need manual recovery; no replacement was attempted.") }
            journal = try JSONDecoder().decode(ProgressImportJournal.self, from: Data(contentsOf: candidates[0].appending(path: "transaction.json")))
            guard journal.id == candidates[0].lastPathComponent else { throw ProgressStoreError.invalid("Recovery journal identity differs") }
            try ProgressArchive.move(journalURL, candidates[0].appending(path: "damaged-journal-\(UUID().uuidString.lowercased()).json"))
            try ProgressDurability.write(JSONEncoder().encode(journal), to: journalURL)
        }
        guard journal.version == 1 else { throw ProgressStoreError.invalid("Unsupported import recovery journal; originals remain retained") }
        try complete(journal, fault: fault)
    }
    private func complete(_ journal: ProgressImportJournal, fault: (ProgressImportBoundary) throws -> Void) throws {
        if journal.rollback == true { try completeRollback(journal); return }
        let work = try workspace(journal.id), old = work.appending(path: "previous"), incoming = work.appending(path: "incoming")
        let fm = FileManager.default
        if fm.fileExists(atPath: incoming.path) {
            if journal.hadOriginal && !fm.fileExists(atPath: old.path) { try ProgressArchive.move(root, old) }
            else if fm.fileExists(atPath: root.path) { throw ProgressStoreError.invalid("Unexpected active progress root; recovery retained both copies") }
            try fault(.originalMoved)
            try ProgressArchive.move(incoming, root)
        }
        try ProgressArchive.check((try? String(contentsOf: root.appending(path: ".generation"), encoding: .utf8)) == journal.id, "Replacement root does not match import journal")
        if journal.hadOriginal { try ProgressArchive.check(fm.fileExists(atPath: old.path), "Original progress backup is missing") }
        try fault(.replacementMoved)
        try database.write { db in
            try journal.replacement.install(db)
            try db.execute(sql: "INSERT OR REPLACE INTO progress_import_generation (singleton, generation) VALUES (1, ?)", arguments: [journal.id])
        }
        try fault(.metadataCommitted)
        // Retain the complete journal and prior tree for explicit rollback/export.
        try ProgressArchive.move(journalURL, work.appending(path: "completed.json"))
        try fault(.completed)
    }
    func rollbackPendingImport() throws {
        var journal = try JSONDecoder().decode(ProgressImportJournal.self, from: Data(contentsOf: journalURL))
        guard journal.version == 1 else { throw ProgressStoreError.invalid("Unsupported recovery journal") }
        let work = try workspace(journal.id)
        _ = try journal.previous.validate(at: work.appending(path: "rollback-validation"))
        journal.rollback = true
        try ProgressDurability.write(JSONEncoder().encode(journal), to: work.appending(path: "transaction.json"))
        try ProgressDurability.write(JSONEncoder().encode(journal), to: journalURL)
        try completeRollback(journal)
    }
    private func completeRollback(_ journal: ProgressImportJournal) throws {
        let fm = FileManager.default, work = try workspace(journal.id)
        let previous = work.appending(path: "previous"), staging = work.appending(path: "rollback-incoming")
        let marker = journal.id + "-rollback"
        if (try? String(contentsOf: root.appending(path: ".generation"), encoding: .utf8)) != marker {
            if journal.hadOriginal && !fm.fileExists(atPath: previous.path) {
                // No root rename happened yet; preserve the original before replacing anything.
                try ProgressArchive.check(fm.fileExists(atPath: work.appending(path: "incoming").path), "Cannot identify the original progress root")
                try ProgressArchive.move(root, previous)
            }
            if (try? String(contentsOf: staging.appending(path: ".generation"), encoding: .utf8)) != marker {
                if fm.fileExists(atPath: staging.path) { try ProgressArchive.move(staging, work.appending(path: "partial-rollback-\(UUID().uuidString.lowercased())")) }
                try ProgressArchive.directory(staging)
                if journal.hadOriginal { try copyTree(previous, to: staging) }
                let device = previous.appending(path: "device.json")
                if fm.fileExists(atPath: device.path) { try ProgressDurability.write(Data(contentsOf: device), to: staging.appending(path: "device.json")) }
                try ProgressDurability.write(Data(marker.utf8), to: staging.appending(path: ".generation"))
            }
            if fm.fileExists(atPath: root.path) {
                try ProgressArchive.check((try? String(contentsOf: root.appending(path: ".generation"), encoding: .utf8)) == journal.id, "Unexpected root blocks rollback")
                try ProgressArchive.move(root, work.appending(path: "rolled-back-replacement"))
            }
            try ProgressArchive.move(staging, root)
        }
        try database.write { db in
            try journal.previous.install(db)
            try db.execute(sql: "INSERT OR REPLACE INTO progress_import_generation (singleton, generation) VALUES (1, ?)", arguments: [marker])
        }
        try ProgressArchive.move(journalURL, work.appending(path: "completed.json"))
    }
    func exportPrevious(_ id: String, to destination: URL) throws {
        try recover()
        let work = try workspace(id)
        let journal = try JSONDecoder().decode(ProgressImportJournal.self, from: Data(contentsOf: work.appending(path: "completed.json")))
        try ProgressArchive.write(root: work.appending(path: "previous"), snapshot: journal.previous, to: destination)
    }
}
