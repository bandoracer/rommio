import Foundation
import GRDB
import RommioContract

public enum ProgressStoreError: LocalizedError, Equatable, Sendable {
    case invalid(String)
    case conflict([String])
    public var errorDescription: String? {
        switch self {
        case .invalid(let reason): reason
        case .conflict: "Multiple progress branches need a choice. All versions have been retained."
        }
    }
}
public enum ProgressCommitBoundary: CaseIterable, Sendable { case artifactsInstalled, operationInserted, headsUpdated, outboxInserted, transactionCommitted }
public struct ProgressLease: Sendable {
    public let identity: ProgressIdentity
    public let operationId: String
    public let payloadHash: String
    public let leaseId: String
    public let payload: Data
}
public struct ProgressOutboxRecord: FetchableRecord, Decodable, Sendable {
    public var namespaceId: String
    public var contentId: String
    public var operationId: String
    public var payloadHash: String
    public var status: String
    public var attempts: Int
    public var leaseId: String?
    public var leaseUntil: Int64?
    public var nextAttempt: Int64
    public var lastError: String?
}

/// Isolated schema-2/schema-3 owner. No implicit migration, network activity, or activation of legacy saves.
/// Methods do not suspend inside file installation or the SQLite transaction.
public actor ProgressStore {
    private let database: AppDatabase
    private let files: ProgressFileVault
    private var recovered = false
    private var closed = false
    public init(database: AppDatabase, rootDirectory: URL, ownerLock: URL? = nil) throws {
        self.database = database
        files = try ProgressFileVault(root: rootDirectory, ownerLock: ownerLock)
        try database.dbQueue.writeWithoutTransaction { try $0.execute(sql: "PRAGMA synchronous = FULL") }
    }
    public func close() { if !closed { closed = true; files.close() } }
    private func ready() throws {
        try require(!closed, "Progress store closed")
        if !recovered {
            try database.write { try $0.execute(sql: "UPDATE progress_v2_outbox SET status = 'PENDING', leaseId = NULL, leaseUntil = NULL WHERE status = 'LEASED'") }
            recovered = true
        }
    }
    private func require(_ value: Bool, _ message: String) throws { if !value { throw ProgressStoreError.invalid(message) } }
    private func arguments(_ identity: ProgressIdentity) -> StatementArguments { [identity.namespaceId, identity.contentId] }
    private func requireIdentity(_ identity: ProgressIdentity, db: Database) throws {
        try ProgressProtocol.validateIdentity(identity)
        let namespace = try String.fetchOne(db, sql: "SELECT namespaceId FROM progress_v2_namespaces WHERE serverKey = ? AND accountId = ?", arguments: [identity.binding.serverKey, identity.binding.accountId])
        let content = try String.fetchOne(db, sql: "SELECT contentId FROM progress_v2_contents WHERE namespaceId = ? AND bindingKey = ?", arguments: [identity.namespaceId, ProgressProtocol.bindingKey(identity.binding)])
        try require(namespace == identity.namespaceId && content == identity.contentId, "Wrong progress identity")
    }
    public func resolveIdentity(_ binding: ProgressBinding) throws -> ProgressIdentity {
        try ready(); try ProgressProtocol.validateBinding(binding)
        return try database.write { db in
            var namespace = try String.fetchOne(db, sql: "SELECT namespaceId FROM progress_v2_namespaces WHERE serverKey = ? AND accountId = ?", arguments: [binding.serverKey, binding.accountId])
            if namespace == nil {
                namespace = UUID().uuidString.lowercased()
                try db.execute(sql: "INSERT INTO progress_v2_namespaces VALUES (?, ?, ?)", arguments: [namespace!, binding.serverKey, binding.accountId])
            }
            let key = ProgressProtocol.bindingKey(binding)
            var content = try String.fetchOne(db, sql: "SELECT contentId FROM progress_v2_contents WHERE namespaceId = ? AND bindingKey = ?", arguments: [namespace!, key])
            if content == nil {
                content = UUID().uuidString.lowercased()
                try db.execute(sql: "INSERT INTO progress_v2_contents VALUES (?, ?, ?)", arguments: [content!, namespace!, key])
            }
            return ProgressIdentity(namespaceId: namespace!, contentId: content!, binding: binding)
        }
    }
    public func commit(_ identity: ProgressIdentity, payload: Data, sources: [String: URL], publish: Bool = true,
                       fault: @Sendable (ProgressCommitBoundary) throws -> Void = { _ in }) throws {
        try ready()
        let operation = try ProgressProtocol.decode(payload)
        try require(operation.binding == identity.binding, "Wrong progress binding")
        let hash = ProgressProtocol.sha256(payload)
        let known = try database.read { db -> [String: ProgressOperation] in
            try requireIdentity(identity, db: db)
            return try operations(identity, db: db)
        }
        if known[operation.operationId] != nil {
            let old: Data? = try database.read { try Data.fetchOne($0, sql: "SELECT payload FROM progress_v2_operations WHERE namespaceId = ? AND contentId = ? AND operationId = ?", arguments: [identity.namespaceId, identity.contentId, operation.operationId]) }
            try require(old == payload, "Operation ID reused with different bytes")
            try files.verify(identity, artifacts: operation.checkpoints.flatMap(\.artifacts)); return
        }
        try ProgressProtocol.validateParents(operation, known: known)
        for checkpoint in operation.checkpoints {
            for old in known.values.flatMap(\.checkpoints) where old.checkpointId == checkpoint.checkpointId {
                try require(old == checkpoint, "Checkpoint ID reused")
            }
        }
        let artifacts = Dictionary(grouping: operation.checkpoints.flatMap(\.artifacts), by: \.sha256)
        for (digest, group) in artifacts {
            try require(Set(group.map(\.byteLength)).count == 1, "Conflicting artifact sizes")
            try files.install(identity, artifact: group[0], source: sources[digest])
        }
        try fault(.artifactsInstalled)
        try database.write { db in
            try db.execute(sql: "INSERT INTO progress_v2_operations VALUES (?, ?, ?, ?, ?)", arguments: [identity.namespaceId, identity.contentId, operation.operationId, payload, hash])
            try fault(.operationInserted)
            for change in operation.changes {
                for parent in change.parents {
                    try db.execute(sql: "DELETE FROM progress_v2_heads WHERE namespaceId = ? AND contentId = ? AND reference = ? AND operationId = ?", arguments: [identity.namespaceId, identity.contentId, change.reference, parent])
                }
                try db.execute(sql: "INSERT INTO progress_v2_heads VALUES (?, ?, ?, ?)", arguments: [identity.namespaceId, identity.contentId, change.reference, operation.operationId])
            }
            for change in operation.changes where change.parents.count > 1 {
                for parent in change.parents {
                    if let cp = known[parent]?.changes.first(where: { $0.reference == change.reference })?.checkpointId {
                        try db.execute(sql: "INSERT OR IGNORE INTO progress_pins VALUES (?, ?, ?)", arguments: [identity.namespaceId, identity.contentId, cp])
                    }
                }
            }
            try fault(.headsUpdated)
            try db.execute(sql: "INSERT INTO progress_v2_outbox VALUES (?, ?, ?, ?, ?, 0, NULL, NULL, 0, NULL)", arguments: [identity.namespaceId, identity.contentId, operation.operationId, hash, publish ? "PENDING" : "LOCAL_ONLY"])
            try fault(.outboxInserted)
        }
        try fault(.transactionCommitted)
    }
    private func operations(_ identity: ProgressIdentity, db: Database) throws -> [String: ProgressOperation] {
        var result: [String: ProgressOperation] = [:]
        for row in try Row.fetchAll(db, sql: "SELECT * FROM progress_v2_operations WHERE namespaceId = ? AND contentId = ?", arguments: arguments(identity)) {
            let payload: Data = row["payload"]; let hash: String = row["payloadHash"]; let id: String = row["operationId"]
            try require(ProgressProtocol.sha256(payload) == hash, "Corrupt operation")
            let op = try ProgressProtocol.decode(payload)
            try require(op.operationId == id && op.binding == identity.binding, "Wrong operation binding")
            result[id] = op
        }
        return result
    }
    private func readHeads(_ identity: ProgressIdentity, reference: String, db: Database) throws -> [String] {
        try String.fetchAll(db, sql: "SELECT operationId FROM progress_v2_heads WHERE namespaceId = ? AND contentId = ? AND reference = ? ORDER BY operationId", arguments: [identity.namespaceId, identity.contentId, reference])
    }
    public func heads(_ identity: ProgressIdentity, reference: String) throws -> [String] {
        try ready()
        return try database.read { db in try requireIdentity(identity, db: db); return try readHeads(identity, reference: reference, db: db) }
    }
    public func checkpoint(_ identity: ProgressIdentity, reference: String) throws -> ProgressCheckpoint? {
        try ready()
        let result: ProgressCheckpoint? = try database.read { db in
            try requireIdentity(identity, db: db)
            let heads = try readHeads(identity, reference: reference, db: db)
            if heads.isEmpty { return nil }
            if heads.count > 1 { throw ProgressStoreError.conflict(heads) }
            guard let op = try operations(identity, db: db)[heads[0]], let change = op.changes.first(where: { $0.reference == reference }) else { throw ProgressStoreError.invalid("Missing reference operation") }
            if change.tombstone { return nil }
            if reference.hasPrefix("resume/") {
                if try readHeads(identity, reference: "save/current", db: db) != [op.operationId] { return nil }
            }
            guard let checkpoint = op.checkpoints.first(where: { $0.checkpointId == change.checkpointId }) else { throw ProgressStoreError.invalid("Missing checkpoint") }
            return checkpoint
        }
        if let result { try files.verify(identity, artifacts: result.artifacts) }
        return result
    }
    public func materialize(_ identity: ProgressIdentity, reference: String, sessionId: String) throws -> URL? {
        guard let checkpoint = try checkpoint(identity, reference: reference) else { return nil }
        try require(UUID(uuidString: sessionId)?.uuidString.lowercased() == sessionId, "Invalid session ID")
        return try files.materialize(identity, checkpoint: checkpoint, sessionId: sessionId)
    }
    private func readOutbox(_ identity: ProgressIdentity, db: Database) throws -> [ProgressOutboxRecord] {
        try ProgressOutboxRecord.fetchAll(db, sql: "SELECT * FROM progress_v2_outbox WHERE namespaceId = ? AND contentId = ? ORDER BY operationId", arguments: arguments(identity))
    }
    public func outbox(_ identity: ProgressIdentity) throws -> [ProgressOutboxRecord] {
        try ready(); return try database.read { db in try requireIdentity(identity, db: db); return try readOutbox(identity, db: db) }
    }
    public func leaseNext(_ identity: ProgressIdentity, now: Int64, durationMs: Int64, states: Bool? = nil) throws -> ProgressLease? {
        try ready(); try require((1...3_600_000).contains(durationMs) && now <= Int64.max - durationMs, "Invalid lease interval")
        return try database.write { db in
            try requireIdentity(identity, db: db)
            let queue = try readOutbox(identity, db: db)
            let published = Set(queue.filter { $0.status == "PUBLISHED" }.map(\.operationId))
            let known = try operations(identity, db: db)
            guard let pending = queue.first(where: { row in
                (states == nil || (known[row.operationId]?.schemaVersion == 3 && known[row.operationId]?.changes.contains(where: { $0.reference.hasPrefix("state/") }) == states)) &&
                ((row.status == "PENDING" && row.nextAttempt <= now) || (row.status == "LEASED" && (row.leaseUntil ?? 0) <= now)) &&
                (known[row.operationId].map(ProgressProtocol.dependencies)?.allSatisfy { published.contains($0) } == true)
            }) else { return nil }
            guard let payload = try Data.fetchOne(db, sql: "SELECT payload FROM progress_v2_operations WHERE namespaceId = ? AND contentId = ? AND operationId = ?", arguments: [identity.namespaceId, identity.contentId, pending.operationId]) else { throw ProgressStoreError.invalid("Missing outbox payload") }
            try require(ProgressProtocol.sha256(payload) == pending.payloadHash, "Corrupt outbox payload")
            let ticket = UUID().uuidString.lowercased()
            try db.execute(sql: "UPDATE progress_v2_outbox SET status = 'LEASED', attempts = attempts + 1, leaseId = ?, leaseUntil = ? WHERE namespaceId = ? AND contentId = ? AND operationId = ?", arguments: [ticket, now + durationMs, identity.namespaceId, identity.contentId, pending.operationId])
            return ProgressLease(identity: identity, operationId: pending.operationId, payloadHash: pending.payloadHash, leaseId: ticket, payload: payload)
        }
    }
    public func acknowledge(_ lease: ProgressLease, verifiedPayloadHash: String) throws -> Bool {
        try finish(lease, status: "PUBLISHED", nextAttempt: 0, error: nil, verifiedHash: verifiedPayloadHash)
    }
    public func retry(_ lease: ProgressLease, nextAttempt: Int64, error: String, authBlocked: Bool = false) throws -> Bool {
        try finish(lease, status: authBlocked ? "PARKED_AUTH" : "PENDING", nextAttempt: nextAttempt, error: error, verifiedHash: nil)
    }
    private func finish(_ lease: ProgressLease, status: String, nextAttempt: Int64, error: String?, verifiedHash: String?) throws -> Bool {
        try ready()
        return try database.write { db in
            try requireIdentity(lease.identity, db: db)
            guard let row = try readOutbox(lease.identity, db: db).first(where: { $0.operationId == lease.operationId }), row.status == "LEASED", row.leaseId == lease.leaseId, row.payloadHash == lease.payloadHash else { return false }
            if status == "PUBLISHED" { try require(verifiedHash == row.payloadHash, "Acknowledgment does not match uploaded bytes") }
            try db.execute(sql: "UPDATE progress_v2_outbox SET status = ?, leaseId = NULL, leaseUntil = NULL, nextAttempt = ?, lastError = ? WHERE namespaceId = ? AND contentId = ? AND operationId = ?", arguments: [status, nextAttempt, error, lease.identity.namespaceId, lease.identity.contentId, lease.operationId])
            return true
        }
    }
    public func resumeAuthenticatedWork(_ identity: ProgressIdentity) throws {
        try ready(); try database.write { db in
            try requireIdentity(identity, db: db)
            try db.execute(sql: "UPDATE progress_v2_outbox SET status = 'PENDING' WHERE namespaceId = ? AND contentId = ? AND status = 'PARKED_AUTH'", arguments: arguments(identity))
        }
    }
    public func quarantine(_ identity: ProgressIdentity, payload: Data, reason: String) throws {
        try ready(); try require(payload.count <= ProgressProtocol.maxPayloadBytes, "Oversized quarantine payload")
        try database.write { db in
            try requireIdentity(identity, db: db)
            try db.execute(sql: "INSERT OR IGNORE INTO progress_v2_quarantine VALUES (?, ?, ?, ?, ?)", arguments: [identity.namespaceId, identity.contentId, ProgressProtocol.sha256(payload), payload, reason])
        }
    }
    public func payloads(_ identity: ProgressIdentity) throws -> [Data] {
        try ready()
        return try database.read { db in
            try requireIdentity(identity, db: db)
            return try Data.fetchAll(db, sql: "SELECT payload FROM progress_v2_operations WHERE namespaceId = ? AND contentId = ? ORDER BY rowid", arguments: arguments(identity))
        }
    }
    public func identities() throws -> [ProgressIdentity] {
        try ready()
        return try database.read { db in
            try Row.fetchAll(db, sql: "SELECT namespaceId, contentId, payload FROM progress_v2_operations GROUP BY namespaceId, contentId").map { row in
                let payload: Data = row["payload"]
                return ProgressIdentity(namespaceId: row["namespaceId"], contentId: row["contentId"], binding: try ProgressProtocol.decode(payload).binding)
            }
        }
    }
    public func references(_ identity: ProgressIdentity) throws -> [String: [ProgressHead]] {
        try ready()
        return try database.read { db in
            try requireIdentity(identity, db: db)
            let known = try operations(identity, db: db)
            var result: [String: [ProgressHead]] = [:]
            for row in try Row.fetchAll(db, sql: "SELECT reference, operationId FROM progress_v2_heads WHERE namespaceId = ? AND contentId = ? ORDER BY operationId", arguments: arguments(identity)) {
                let ref: String = row["reference"]; let id: String = row["operationId"]
                guard let op = known[id], let change = op.changes.first(where: { $0.reference == ref }) else { throw ProgressStoreError.invalid("Missing reference") }
                result[ref, default: []].append(ProgressHead(operationId: id, checkpoint: op.checkpoints.first { $0.checkpointId == change.checkpointId }))
            }
            return result
        }
    }
    public func checkpointByID(_ identity: ProgressIdentity, id: String) throws -> ProgressCheckpoint {
        try ready()
        return try database.read { db in
            try requireIdentity(identity, db: db)
            guard let cp = try operations(identity, db: db).values.flatMap(\.checkpoints).first(where: { $0.checkpointId == id }) else { throw ProgressStoreError.invalid("Missing checkpoint") }
            return cp
        }
    }
    public func artifact(_ identity: ProgressIdentity, _ artifact: ProgressArtifact) throws -> URL {
        try ready(); try database.read { try requireIdentity(identity, db: $0) }
        return try files.artifactURL(identity, artifact: artifact)
    }
    public func received(_ identity: ProgressIdentity, payload: Data, sources: [String: URL]) throws {
        try commit(identity, payload: payload, sources: sources, publish: false)
        let operation = try ProgressProtocol.decode(payload)
        try database.write { try $0.execute(sql: "UPDATE progress_v2_outbox SET status = 'PUBLISHED', leaseId = NULL, leaseUntil = NULL WHERE namespaceId = ? AND contentId = ? AND operationId = ?", arguments: [identity.namespaceId, identity.contentId, operation.operationId]) }
    }
    public func enablePublication(_ identity: ProgressIdentity) throws {
        for payload in try payloads(identity) {
            let op = try ProgressProtocol.decode(payload)
            if op.schemaVersion == 3 { try database.write { try $0.execute(sql: "UPDATE progress_v2_outbox SET status = 'PENDING' WHERE namespaceId = ? AND contentId = ? AND operationId = ? AND status = 'LOCAL_ONLY'", arguments: [identity.namespaceId, identity.contentId, op.operationId]) } }
        }
    }
    public func repair(_ identity: ProgressIdentity, checkpointID: String, sources: [String: URL]) throws {
        let cp = try checkpointByID(identity, id: checkpointID)
        for artifact in cp.artifacts {
            if (try? files.artifactURL(identity, artifact: artifact)) == nil {
                guard let source = sources[artifact.sha256] else { throw ProgressStoreError.invalid("Missing repair source") }
                try files.repair(identity, artifact: artifact, source: source)
            }
        }
    }
    public func repairArtifact(_ identity: ProgressIdentity, artifact: ProgressArtifact, source: URL) throws {
        try ready()
        let known = try database.read { db in try requireIdentity(identity, db: db); return try operations(identity, db: db) }
        guard known.values.flatMap(\.checkpoints).flatMap(\.artifacts).contains(artifact) else { throw ProgressStoreError.invalid("Repair artifact is not part of this progress history") }
        try files.repair(identity, artifact: artifact, source: source)
    }
    public func history(_ identity: ProgressIdentity) throws -> [ProgressHistoryEntry] {
        var seen = Set<String>(); var result: [ProgressHistoryEntry] = []
        for payload in try payloads(identity).reversed() {
            for cp in try ProgressProtocol.decode(payload).checkpoints where seen.insert(cp.checkpointId).inserted {
                let pinned = try database.read { try Bool.fetchOne($0, sql: "SELECT EXISTS(SELECT 1 FROM progress_pins WHERE namespaceId = ? AND contentId = ? AND checkpointId = ?)", arguments: [identity.namespaceId, identity.contentId, cp.checkpointId])! }
                result.append(ProgressHistoryEntry(checkpoint: cp, pinned: pinned || cp.captureReason == "import"))
            }
        }
        return result
    }
    public func pin(_ identity: ProgressIdentity, checkpointID: String) throws {
        _ = try checkpointByID(identity, id: checkpointID)
        try database.write { try $0.execute(sql: "INSERT OR IGNORE INTO progress_pins VALUES (?, ?, ?)", arguments: [identity.namespaceId, identity.contentId, checkpointID]) }
    }
    public func isGameAncestor(_ identity: ProgressIdentity, ancestor: String, descendant: String) throws -> Bool {
        let known = try database.read { try operations(identity, db: $0) }
        var pending = [descendant]; var seen = Set<String>()
        while let next = pending.popLast() {
            if next == ancestor { return true }
            if seen.insert(next).inserted { pending += known[next]?.changes.filter { $0.reference == "game/current" }.flatMap(\.parents) ?? [] }
        }
        return false
    }
    public func resolve(_ identity: ProgressIdentity, reference: String, selected: String, expected: [String], deviceID: String, sessionID: String) throws {
        let heads = try references(identity)[reference] ?? []
        try require(Set(heads.map(\.operationId)) == Set(expected) && expected.contains(selected) && expected.count > 1, "Progress changed; refresh the choices")
        let checkpoint = heads.first { $0.operationId == selected }?.checkpoint
        let op = ProgressOperation(schemaVersion: 3, operationId: UUID().uuidString.lowercased(), binding: identity.binding,
            changes: [ProgressRefChange(reference: reference, parents: expected, checkpointId: checkpoint?.checkpointId, tombstone: checkpoint == nil)],
            checkpoints: checkpoint.map { [$0] } ?? [], deviceId: deviceID, sessionId: sessionID, createdAtEpochMs: Int64(Date().timeIntervalSince1970 * 1000))
        try commit(identity, payload: ProgressProtocol.encode(op), sources: [:])
    }

}

public struct ProgressHead: Sendable, Equatable {
    public let operationId: String
    public let checkpoint: ProgressCheckpoint?
}
public struct ProgressHistoryEntry: Sendable, Identifiable {
    public var id: String { checkpoint.checkpointId }
    public let checkpoint: ProgressCheckpoint
    public let pinned: Bool
}
