import Foundation
import RommioContract

public struct ProgressCaptureJournal: Codable, Sendable {
    public let identity: ProgressIdentity
    public let payload: Data
    public static func stage(identity: ProgressIdentity, operation: ProgressOperation, bytes: [String: Data], root: URL) throws -> URL {
        let payload = try ProgressProtocol.encode(operation)
        let directory = root.appending(path: operation.operationId)
        for artifact in operation.checkpoints.flatMap(\.artifacts) {
            guard let data = bytes[artifact.relativePath], data.count == artifact.byteLength, ProgressProtocol.sha256(data) == artifact.sha256 else { throw ProgressStoreError.invalid("Captured bytes do not match the manifest") }
            try ProgressDurability.write(data, to: directory.appending(path: artifact.sha256))
        }
        try ProgressDurability.write(JSONEncoder().encode(Self(identity: identity, payload: payload)), to: directory.appending(path: "capture.json"))
        return directory
    }
    public static func commit(_ directory: URL, store: ProgressStore, expected: ProgressIdentity) async throws -> ProgressOperation {
        let journal = try JSONDecoder().decode(Self.self, from: Data(contentsOf: directory.appending(path: "capture.json")))
        guard journal.identity == expected else { throw ProgressStoreError.invalid("Capture belongs to another session identity") }
        let operation = try ProgressProtocol.decode(journal.payload)
        let sources = Dictionary(operation.checkpoints.flatMap(\.artifacts).map { ($0.sha256, directory.appending(path: $0.sha256)) }, uniquingKeysWith: { a, _ in a })
        try await store.commit(expected, payload: journal.payload, sources: sources)
        // A surviving journal after interruption is safe: commit compares exact immutable bytes.
        try FileManager.default.removeItem(at: directory)
        return operation
    }
    public static func pending(root: URL, identity: ProgressIdentity) throws -> [URL] {
        guard FileManager.default.fileExists(atPath: root.path) else { return [] }
        return try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: [.isSymbolicLinkKey]).filter { directory in
            guard try directory.resourceValues(forKeys: [.isSymbolicLinkKey]).isSymbolicLink != true else { throw ProgressStoreError.invalid("Capture directory is a symlink") }
            let record = directory.appending(path: "capture.json")
            guard FileManager.default.fileExists(atPath: record.path) else { return false }
            let journal = try JSONDecoder().decode(Self.self, from: Data(contentsOf: record))
            return journal.identity == identity
        }
    }
    public static func recover(root: URL, store: ProgressStore, identity: ProgressIdentity) async throws -> [String] {
        var remaining = try pending(root: root, identity: identity)
        var errors: [String] = []
        while !remaining.isEmpty {
            var next: [URL] = []; errors = []
            for directory in remaining {
                do { _ = try await commit(directory, store: store, expected: identity) }
                catch { next.append(directory); errors.append(error.localizedDescription) }
            }
            if next.count == remaining.count { return errors }
            remaining = next
        }
        return []
    }
}
