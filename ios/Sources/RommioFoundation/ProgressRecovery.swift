import Foundation
import RommioContract

public struct ProgressRecoveryItem: Identifiable, Sendable {
    public enum Kind: String, Sendable { case capture, session, archived, previous, damagedArtifact, interruptedImport, localOnly }
    public let id: String
    public let kind: Kind
    public let title: String
    public let detail: String
    public let identity: ProgressIdentity?
    public let artifact: ProgressArtifact?
    init(id: String, kind: Kind, title: String, detail: String, identity: ProgressIdentity? = nil, artifact: ProgressArtifact? = nil) {
        self.id = id; self.kind = kind; self.title = title; self.detail = detail; self.identity = identity; self.artifact = artifact
    }
}
extension ProgressTransfer {
    func recoveryItems() throws -> [ProgressRecoveryItem] {
        var result: [ProgressRecoveryItem] = []
        let fm = FileManager.default
        if fm.fileExists(atPath: journalURL.path) { result.append(.init(id: "pending", kind: .interruptedImport, title: "Interrupted progress import", detail: "Retry recovery before playing. The prior files and metadata are retained.")) }
        for child in (try? fm.contentsOfDirectory(at: recovery, includingPropertiesForKeys: nil)) ?? [] {
            if fm.fileExists(atPath: child.appending(path: "completed.json").path) {
                result.append(.init(id: child.lastPathComponent, kind: .previous, title: "Progress before an import", detail: "Export this retained version to inspect or restore it."))
            }
        }
        let captureRoot = root.appending(path: "captures")
        for content in (try? fm.contentsOfDirectory(at: captureRoot, includingPropertiesForKeys: nil)) ?? [] {
            for capture in (try? fm.contentsOfDirectory(at: content, includingPropertiesForKeys: nil)) ?? [] {
                let path = "captures/\(content.lastPathComponent)/\(capture.lastPathComponent)"
                let journal = try? JSONDecoder().decode(ProgressCaptureJournal.self, from: Data(contentsOf: capture.appending(path: "capture.json")))
                result.append(.init(id: path, kind: .capture, title: journal == nil ? "Incomplete or damaged capture" : "Captured progress awaiting commit", detail: journal == nil ? "Keep an archived copy or export it for recovery. No bytes will be discarded." : "Retry commits these exact captured bytes. It does not take a new snapshot.", identity: journal?.identity))
            }
        }
        for session in (try? fm.contentsOfDirectory(at: root.appending(path: "sessions"), includingPropertiesForKeys: nil)) ?? [] where !fm.fileExists(atPath: session.appending(path: "closed").path) {
            result.append(.init(id: "sessions/\(session.lastPathComponent)", kind: .session, title: "Interrupted player workspace", detail: "Committed progress stays in history. Preserve the remaining workspace in an archive."))
        }
        for archive in (try? fm.contentsOfDirectory(at: root.appending(path: "recovery-archives"), includingPropertiesForKeys: nil)) ?? [] where fm.fileExists(atPath: archive.appending(path: "contents").path) {
            result.append(.init(id: "recovery-archives/\(archive.lastPathComponent)", kind: .archived, title: "Archived recovery files", detail: "Retained in progress backups; restore to the recovery queue when needed."))
        }
        var seen = Set<String>()
        for row in try snapshot().tables["progress_v2_operations"] ?? [] {
            guard case .text(let ns) = row[0], case .text(let content) = row[1], case .blob(let payload) = row[3] else { throw ProgressStoreError.invalid("Invalid progress metadata") }
            let op = try ProgressProtocol.decode(payload)
            let identity = ProgressIdentity(namespaceId: ns, contentId: content, binding: op.binding)
            for artifact in op.checkpoints.flatMap(\.artifacts) {
                let path = "store/\(ns)/\(content)/blobs/\(artifact.sha256)"
                if seen.insert(path).inserted, (try? ProgressFileHash.sha256(root.appending(path: path))) != artifact.sha256 {
                    result.append(.init(id: path, kind: .damagedArtifact, title: artifact.role == "state" ? "Snapshot needs repair" : "Game-save data needs repair", detail: "Choose an original file with matching bytes or retry cloud sync. Damaged originals are retained.", identity: identity, artifact: artifact))
                }
            }
        }
        let snapshot = try snapshot()
        var localContents = Set<String>()
        for row in snapshot.tables["progress_v2_outbox"] ?? [] {
            guard case .text(let status) = row[4], status == "LOCAL_ONLY", case .text(let content) = row[1],
                  let operation = snapshot.tables["progress_v2_operations"]?.first(where: { $0[1] == row[1] && $0[2] == row[2] }),
                  case .blob(let payload) = operation[3], case .text(let ns) = row[0] else { continue }
            let op = try ProgressProtocol.decode(payload)
            guard op.schemaVersion == 3, localContents.insert(content).inserted else { continue }
            result.append(.init(id: "local-only/" + content, kind: .localOnly, title: "Local-only progress for game \(op.binding.romId)", detail: "This history is not queued for upload. Enabling sync explicitly publishes it to its original account.", identity: .init(namespaceId: ns, contentId: content, binding: op.binding)))
        }
        return result
    }
    func archiveRecovery(_ path: String) throws {
        try recover(); try ProgressArchive.safePath(path)
        let parts = path.split(separator: "/")
        try ProgressArchive.check((parts.first == "captures" && parts.count == 3) || (parts.first == "sessions" && parts.count == 2), "Only inactive capture/session files may be archived")
        let source = root.appending(path: path)
        _ = try ProgressArchive.inventory(source)
        let target = root.appending(path: "recovery-archives/\(UUID().uuidString.lowercased())")
        try ProgressDurability.write(Data(path.utf8), to: target.appending(path: "origin.txt"))
        try ProgressArchive.move(source, target.appending(path: "contents"))
    }
    func restoreArchive(_ path: String) throws {
        try recover(); try ProgressArchive.safePath(path)
        try ProgressArchive.check(path.split(separator: "/").count == 2 && path.hasPrefix("recovery-archives/"), "Invalid recovery archive")
        let archive = root.appending(path: path)
        let origin = try String(contentsOf: archive.appending(path: "origin.txt"), encoding: .utf8)
        try ProgressArchive.safePath(origin)
        let parts = origin.split(separator: "/")
        try ProgressArchive.check((parts.first == "captures" && parts.count == 3) || (parts.first == "sessions" && parts.count == 2), "Invalid original recovery location")
        try ProgressArchive.move(archive.appending(path: "contents"), root.appending(path: origin))
        try ProgressDurability.write(Data("restored".utf8), to: archive.appending(path: "restored"))
    }
}
