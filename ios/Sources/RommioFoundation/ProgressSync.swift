import Foundation
import RommioContract

public struct ProgressRemoteRecord: Sendable {
    public let name: String
    public let id: Int
    public let byteLength: Int64
    public init(name: String, id: Int, byteLength: Int64) { self.name = name; self.id = id; self.byteLength = byteLength }
}
public protocol ProgressTransport: Sendable {
    func qualify() async throws
    func inventory() async throws -> [ProgressRemoteRecord]
    func read(_ record: ProgressRemoteRecord, to destination: URL, maxBytes: Int64) async throws
    func upload(name: String, source: URL) async throws -> ProgressRemoteRecord
}
public extension ProgressTransport {
    func qualify() async throws {}
}
public struct ProgressSyncResult: Sendable {
    public let gamePending: Int
    public let statePending: Int
    public let gameLocalOnly: Int
    public let stateLocalOnly: Int
    public let gameConflicts: Int
    public let stateConflicts: Int
    public let errors: [String]
    public var complete: Bool { gamePending == 0 && statePending == 0 && gameConflicts == 0 && stateConflicts == 0 && errors.isEmpty }
    public var message: String {
        func status(_ pending: Int, _ conflicts: Int, _ localOnly: Int) -> String {
            if conflicts > 0 { return "choice required" }
            if pending > 0 { return "\(pending) pending" }
            if localOnly > 0 { return "\(localOnly) local only" }
            return errors.isEmpty ? "synced" : "check incomplete"
        }
        return "Game saves: \(status(gamePending, gameConflicts, gameLocalOnly)). Resume/states: \(status(statePending, stateConflicts, stateLocalOnly))." + (errors.first.map { " \($0)" } ?? "")
    }
}

/// One instance per reconciliation. All local edits retain their own frozen parents.
public actor ProgressSync {
    private let store: ProgressStore
    private let identity: ProgressIdentity
    private let remote: any ProgressTransport
    private let run: URL
    private var errors: [String] = []
    private var statePublishing = false
    // Immutable envelopes are checked once per reconciliation, then reused by
    // both lanes. Keep bytes on disk so large histories do not grow RAM usage.
    private var envelopes: [Int: (String, Int64, URL)] = [:]
    private var prefix: String { "__rommio3_\(ProgressProtocol.bindingKey(identity.binding))_" }
    public init(store: ProgressStore, identity: ProgressIdentity, remote: any ProgressTransport, staging: URL) {
        self.store = store; self.identity = identity; self.remote = remote
        self.run = staging.resolvingSymlinksInPath().appending(path: UUID().uuidString.lowercased())
    }
    private func inventory() async throws -> [ProgressRemoteRecord] {
        let records = try await remote.inventory()
        guard records.count <= 100_000 else { throw ProgressStoreError.invalid("Remote inventory is too large") }
        return records.filter { $0.name.hasPrefix(prefix) }
    }
    private func opName(_ op: ProgressOperation, _ payload: Data) -> String { "\(prefix)op_\(op.operationId)_\(ProgressProtocol.sha256(payload)).json" }
    private func blobName(_ hash: String) -> String { "\(prefix)blob_\(hash).bin" }
    public func synchronize() async throws -> ProgressSyncResult {
        try FileManager.default.createDirectory(at: run, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: run) }
        try await remote.qualify()
        try await store.resumeAuthenticatedWork(identity)
        try await receive(inventory(), states: false)
        try await publish(states: false)
        statePublishing = true
        async let states: Void = publishStateLane()
        while statePublishing { try await publish(states: false); try await Task.sleep(for: .milliseconds(200)) }
        try await states
        try await receive(inventory(), states: false)
        try await receive(inventory(), states: true)
        let known = try await store.payloads(identity).map(ProgressProtocol.decode)
        let queue = try await store.outbox(identity).filter { row in row.status != "PUBLISHED" && known.contains { $0.operationId == row.operationId && $0.schemaVersion == 3 } }
        let localOnly = queue.filter { $0.status == "LOCAL_ONLY" }
        let publishable = queue.filter { $0.status != "LOCAL_ONLY" }
        let gameLocalOnly = localOnly.filter { row in known.first { $0.operationId == row.operationId }!.changes.contains { $0.reference.hasPrefix("game/") } }.count
        let gamePending = publishable.filter { row in known.first { $0.operationId == row.operationId }!.changes.contains { $0.reference.hasPrefix("game/") } }.count
        let refs = try await store.references(identity)
        return ProgressSyncResult(gamePending: gamePending, statePending: publishable.count - gamePending, gameLocalOnly: gameLocalOnly, stateLocalOnly: localOnly.count - gameLocalOnly,
            gameConflicts: (refs["game/current"]?.count ?? 0) > 1 ? 1 : 0,
            stateConflicts: refs.filter { $0.key.hasPrefix("state/") && $0.value.count > 1 }.count, errors: Array(Set(errors)).sorted())
    }
    private func receive(_ records: [ProgressRemoteRecord], states: Bool) async throws {
        let local = try await store.payloads(identity)
        var known = Dictionary(uniqueKeysWithValues: try local.map { (try ProgressProtocol.decode($0).operationId, $0) })
        let published = Set(try await store.outbox(identity).filter { $0.status == "PUBLISHED" }.map(\.operationId))
        var pending: [String: (Data, ProgressOperation)] = [:]; var blocked = Set<String>()
        for record in records where record.name.hasPrefix(prefix + "op_") {
            var bytes: Data?
            do {
                let file: URL
                if let cached = envelopes[record.id], cached.0 == record.name, cached.1 == record.byteLength {
                    file = cached.2
                } else {
                    file = run.appending(path: UUID().uuidString.lowercased())
                    try await remote.read(record, to: file, maxBytes: Int64(ProgressProtocol.maxPayloadBytes))
                }
                let payload = try Data(contentsOf: file); bytes = payload
                guard payload.count == record.byteLength else { throw ProgressStoreError.invalid("Remote envelope size mismatch") }
                let op = try ProgressProtocol.decode(payload)
                guard op.schemaVersion == 3 && op.binding == identity.binding && record.name == opName(op, payload) else { throw ProgressStoreError.invalid("Remote operation identity mismatch") }
                envelopes[record.id] = (record.name, record.byteLength, file)
                if op.changes.contains(where: { $0.reference.hasPrefix("state/") }) != states { continue }
                if blocked.contains(op.operationId) { continue }
                if let previous = pending[op.operationId], previous.0 != payload {
                    blocked.insert(op.operationId); pending.removeValue(forKey: op.operationId)
                    throw ProgressStoreError.invalid("Remote operation ID reused")
                }
                if let old = known[op.operationId] {
                    guard old == payload else { throw ProgressStoreError.invalid("Remote operation differs from immutable local bytes") }
                    var healthy = true
                    for artifact in op.checkpoints.flatMap(\.artifacts) {
                        if (try? await store.artifact(identity, artifact)) == nil { healthy = false }
                    }
                    if !healthy || !published.contains(op.operationId) { pending[op.operationId] = (payload, op) }
                } else { pending[op.operationId] = (payload, op) }
            } catch {
                try Task.checkCancellation()
                if let bytes { try await store.quarantine(identity, payload: bytes, reason: error.localizedDescription) }
                errors.append(error.localizedDescription)
            }
        }
        var progressed = true
        while progressed {
            progressed = false
            for (id, (payload, op)) in pending.sorted(by: { a, b in
                a.value.1.changes[0].reference < b.value.1.changes[0].reference
            }) {
                if !ProgressProtocol.dependencies(op).allSatisfy({ known[$0] != nil }) { continue }
                do {
                    var sources: [String: URL] = [:]
                    for artifact in op.checkpoints.flatMap(\.artifacts) {
                        // Different checkpoints often share the same game-save
                        // blob. artifact() verifies size/hash before reuse.
                        if let existing = try? await store.artifact(identity, artifact) { sources[artifact.sha256] = existing; continue }
                        guard let record = records.first(where: { $0.name == blobName(artifact.sha256) }), artifact.byteLength <= 256 * 1024 * 1024 else { throw ProgressStoreError.invalid("Waiting for \(artifact.role) bytes") }
                        let file = run.appending(path: UUID().uuidString.lowercased())
                        try await remote.read(record, to: file, maxBytes: artifact.byteLength)
                        guard Int64(try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? -1) == artifact.byteLength,
                            try ProgressFileHash.sha256(file) == artifact.sha256 else { throw ProgressStoreError.invalid("Remote artifact verification failed") }
                        sources[artifact.sha256] = file
                    }
                    if known[id] != nil { for cp in op.checkpoints { try await store.repair(identity, checkpointID: cp.checkpointId, sources: sources) } }
                    try await store.received(identity, payload: payload, sources: sources)
                    known[id] = payload; pending.removeValue(forKey: id); progressed = true
                } catch { try Task.checkCancellation(); errors.append(error.localizedDescription); pending.removeValue(forKey: id) }
            }
        }
        if !pending.isEmpty { errors.append("Waiting for \(pending.count) operation dependencies") }
    }
    private func publishStateLane() async throws {
        defer { statePublishing = false }
        try await receive(inventory(), states: true)
        try await publish(states: true)
    }
    private func publish(states: Bool) async throws {
        while let lease = try await store.leaseNext(identity, now: Self.now, durationMs: 120_000, states: states) {
            do {
                let op = try ProgressProtocol.decode(lease.payload)
                for artifact in op.checkpoints.flatMap(\.artifacts) {
                    let source = try await store.artifact(identity, artifact)
                    try await ensure(name: blobName(artifact.sha256), source: source, hash: artifact.sha256, count: artifact.byteLength)
                }
                let envelope = run.appending(path: lease.operationId + ".json")
                try ProgressDurability.write(lease.payload, to: envelope)
                try await ensure(name: opName(op, lease.payload), source: envelope, hash: lease.payloadHash, count: Int64(lease.payload.count))
                _ = try await store.acknowledge(lease, verifiedPayloadHash: lease.payloadHash)
            } catch {
                let cancelled = Task.isCancelled
                let auth: Bool
                if case RommClientError.http(let status, _) = error { auth = status == 401 || status == 403 } else { auth = false }
                _ = try await store.retry(lease, nextAttempt: cancelled ? 0 : Self.now + 15_000, error: error.localizedDescription, authBlocked: auth)
                try Task.checkCancellation()
                errors.append(error.localizedDescription)
            }
        }
    }
    private func ensure(name: String, source: URL, hash: String, count: Int64) async throws {
        var records = try await inventory().filter { $0.name == name }
        if records.isEmpty {
            let record = try await remote.upload(name: name, source: source)
            guard record.name == name else { throw ProgressStoreError.invalid("Upload name changed") }
            records = try await inventory().filter { $0.name == name }
        }
        guard !records.isEmpty else { throw ProgressStoreError.invalid("Upload is not yet visible") }
        for record in records {
            let copy = run.appending(path: UUID().uuidString.lowercased())
            defer { try? FileManager.default.removeItem(at: copy) }
            try await remote.read(record, to: copy, maxBytes: count)
            guard Int64(try copy.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? -1) == count, try ProgressFileHash.sha256(copy) == hash else { throw ProgressStoreError.invalid("Immutable readback differs; no overwrite attempted") }
        }
    }
    private static var now: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
