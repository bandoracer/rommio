import Foundation
import GRDB
import RommioContract

/// Portable progress archive metadata. Exact operation bytes are BLOB cells, never re-encoded.
public enum ProgressBackupCell: Codable, Sendable, Equatable {
    case null, text(String), integer(Int64), blob(Data)
    var value: DatabaseValueConvertible? {
        switch self { case .null: nil; case .text(let v): v; case .integer(let v): v; case .blob(let v): v }
    }
}
public struct ProgressBackupSnapshot: Codable, Sendable, Equatable {
    public var version = 1
    public var tables: [String: [[ProgressBackupCell]]]
    static let columns: [String: [String]] = [
        "progress_v2_namespaces": ["namespaceId", "serverKey", "accountId"],
        "progress_v2_contents": ["contentId", "namespaceId", "bindingKey"],
        "progress_v2_operations": ["namespaceId", "contentId", "operationId", "payload", "payloadHash"],
        "progress_v2_heads": ["namespaceId", "contentId", "reference", "operationId"],
        "progress_v2_outbox": ["namespaceId", "contentId", "operationId", "payloadHash", "status", "attempts", "leaseId", "leaseUntil", "nextAttempt", "lastError"],
        "progress_v2_quarantine": ["namespaceId", "contentId", "payloadHash", "payload", "reason"],
        "progress_pins": ["namespaceId", "contentId", "checkpointId"]
    ]
    static func read(_ db: Database) throws -> Self {
        var tables: [String: [[ProgressBackupCell]]] = [:]
        for (table, columns) in columns {
            tables[table] = try Row.fetchAll(db, sql: "SELECT \(columns.joined(separator: ",")) FROM \(table) ORDER BY rowid").map { row in
                try (0..<columns.count).map { index in
                    let value: DatabaseValue = row[index]
                    switch value.storage {
                    case .null: return .null
                    case .string(let v): return .text(v)
                    case .int64(let v): return .integer(v)
                    case .blob(let v): return .blob(v)
                    default: throw ProgressStoreError.invalid("Unsupported progress metadata value")
                    }
                }
            }
        }
        return Self(tables: tables)
    }
    func install(_ db: Database, normalizeLeases: Bool = true) throws {
        guard version == 1, Set(tables.keys) == Set(Self.columns.keys) else { throw ProgressStoreError.invalid("Unsupported backup metadata schema") }
        for table in Self.columns.keys { try db.execute(sql: "DELETE FROM \(table)") }
        for (table, columns) in Self.columns {
            guard let rows = tables[table], rows.count <= 200_000 else { throw ProgressStoreError.invalid("Oversized backup metadata") }
            for row in rows {
                guard row.count == columns.count else { throw ProgressStoreError.invalid("Invalid backup row") }
                try db.execute(sql: "INSERT INTO \(table) (\(columns.joined(separator: ","))) VALUES (\(Array(repeating: "?", count: columns.count).joined(separator: ",")))", arguments: StatementArguments(row.map(\.value)))
            }
        }
        if normalizeLeases { try db.execute(sql: "UPDATE progress_v2_outbox SET status = CASE WHEN status = 'LEASED' THEN 'PENDING' ELSE status END, leaseId = NULL, leaseUntil = NULL") }
    }
    /// Validate on an isolated database so SQLite constraints and the protocol graph both apply.
    func validate(at root: URL) throws -> [ProgressIdentity] {
        let database = try AppDatabase(rootDirectory: root)
        try database.write { try install($0, normalizeLeases: false) }
        return try database.read { db in
            func reject(_ reason: String) throws -> Never { throw ProgressStoreError.invalid(reason) }
            let namespaces = try Row.fetchAll(db, sql: "SELECT * FROM progress_v2_namespaces")
            for ns in namespaces {
                let id: String = ns["namespaceId"]
                guard UUID(uuidString: id)?.uuidString.lowercased() == id else { try reject("Invalid namespace") }
            }
            let contents = try Row.fetchAll(db, sql: "SELECT * FROM progress_v2_contents")
            for content in contents {
                let id: String = content["contentId"], ns: String = content["namespaceId"], key: String = content["bindingKey"]
                guard UUID(uuidString: id)?.uuidString.lowercased() == id, key.count == 64, key.allSatisfy({ "0123456789abcdef".contains($0) }), namespaces.contains(where: { ($0["namespaceId"] as String) == ns }) else { try reject("Invalid content ownership") }
            }
            var identities: [String: ProgressIdentity] = [:]
            var all: [String: [String: ProgressOperation]] = [:]
            for row in try Row.fetchAll(db, sql: "SELECT * FROM progress_v2_operations") {
                let payload: Data = row["payload"], digest: String = row["payloadHash"], id: String = row["operationId"], ns: String = row["namespaceId"], content: String = row["contentId"]
                let op = try ProgressProtocol.decode(payload)
                guard op.operationId == id, ProgressProtocol.sha256(payload) == digest,
                      let owner = namespaces.first(where: { ($0["namespaceId"] as String) == ns }),
                      (owner["serverKey"] as String) == op.binding.serverKey, (owner["accountId"] as String) == op.binding.accountId,
                      contents.contains(where: { ($0["contentId"] as String) == content && ($0["namespaceId"] as String) == ns && ($0["bindingKey"] as String) == (try? ProgressProtocol.bindingKey(op.binding)) }) else { try reject("Invalid operation identity or bytes") }
                let identity = ProgressIdentity(namespaceId: ns, contentId: content, binding: op.binding)
                try ProgressProtocol.validateIdentity(identity)
                identities[content] = identity; all[content, default: [:]][id] = op
            }
            for (content, known) in all {
                var installed: [String: ProgressOperation] = [:], pending = known
                var checkpoints: [String: ProgressCheckpoint] = [:]
                while !pending.isEmpty {
                    let ready = pending.values.filter { ProgressProtocol.dependencies($0).allSatisfy { installed[$0] != nil } }
                    guard !ready.isEmpty else { try reject("Missing or cyclic progress dependencies") }
                    for op in ready {
                        try ProgressProtocol.validateParents(op, known: installed)
                        for cp in op.checkpoints {
                            if let old = checkpoints[cp.checkpointId], old != cp { try reject("Checkpoint ID reused") }
                            checkpoints[cp.checkpointId] = cp
                        }
                        installed[op.operationId] = op; pending.removeValue(forKey: op.operationId)
                    }
                }
                var heads: [String: Set<String>] = [:]
                for op in known.values { for change in op.changes { heads[change.reference, default: []].insert(op.operationId) } }
                for op in known.values { for change in op.changes { heads[change.reference]?.subtract(change.parents) } }
                var actual: [String: Set<String>] = [:]
                for row in try Row.fetchAll(db, sql: "SELECT * FROM progress_v2_heads WHERE contentId = ?", arguments: [content]) { actual[row["reference"], default: []].insert(row["operationId"]) }
                guard actual == heads.filter({ !$0.value.isEmpty }) else { try reject("Backup reference heads do not match ancestry") }
            }
            let statuses: Set<String> = ["PENDING", "PUBLISHED", "PARKED_AUTH", "LOCAL_ONLY", "LEASED"]
            var outbox: Set<String> = []
            for row in try Row.fetchAll(db, sql: "SELECT * FROM progress_v2_outbox") {
                let content: String = row["contentId"], ns: String = row["namespaceId"], id: String = row["operationId"]
                guard let identity = identities[content], identity.namespaceId == ns, all[content]?[id] != nil,
                      statuses.contains(row["status"]), (row["attempts"] as Int64) >= 0,
                      let hash = try String.fetchOne(db, sql: "SELECT payloadHash FROM progress_v2_operations WHERE contentId = ? AND operationId = ?", arguments: [content, id]), hash == (row["payloadHash"] as String) else { try reject("Invalid publication intent") }
                outbox.insert(content + id)
            }
            guard outbox.count == all.values.reduce(0, { $0 + $1.count }) else { try reject("Missing publication intent") }
            for row in try Row.fetchAll(db, sql: "SELECT * FROM progress_pins") {
                let content: String = row["contentId"], id: String = row["checkpointId"]
                guard identities[content]?.namespaceId == (row["namespaceId"] as String), all[content]?.values.flatMap(\.checkpoints).contains(where: { $0.checkpointId == id }) == true else { try reject("Invalid pinned history") }
            }
            for row in try Row.fetchAll(db, sql: "SELECT * FROM progress_v2_quarantine") {
                let content: String = row["contentId"], ns: String = row["namespaceId"], payload: Data = row["payload"]
                guard contents.contains(where: { ($0["contentId"] as String) == content && ($0["namespaceId"] as String) == ns }), ProgressProtocol.sha256(payload) == (row["payloadHash"] as String) else { try reject("Invalid retained quarantine") }
            }
            for row in try Row.fetchAll(db, sql: "SELECT * FROM progress_v2_heads") {
                guard identities[row["contentId"]]?.namespaceId == (row["namespaceId"] as String) else { try reject("Orphan reference") }
            }
            return Array(identities.values)
        }
    }
}
