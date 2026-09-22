import Foundation
import CryptoKit
import RommioContract

/// Copies qualified v2 references to independent v3 lanes with the same deterministic IDs as Android.
public enum ProgressUpgrade {
    static func id(_ value: String) -> String {
        var bytes = Array(Insecure.MD5.hash(data: Data("rommio-v3-upgrade:\(value)".utf8)))
        bytes[6] = (bytes[6] & 0x0f) | 0x30; bytes[8] = (bytes[8] & 0x3f) | 0x80
        let hex = bytes.map { String(format: "%02x", $0) }.joined()
        return [String(hex.prefix(8)), String(hex.dropFirst(8).prefix(4)), String(hex.dropFirst(12).prefix(4)), String(hex.dropFirst(16).prefix(4)), String(hex.dropFirst(20))].joined(separator: "-")
    }
    public static func run(store: ProgressStore, identity: ProgressIdentity) async throws -> [String] {
        let old = try await store.references(identity).filter { $0.key == "save/current" || $0.key.hasPrefix("resume/") || $0.key.hasPrefix("slot/") }
        if old.isEmpty { return [] }
        let all = try await store.payloads(identity).map(ProgressProtocol.decode)
        let known = Dictionary(uniqueKeysWithValues: all.map { ($0.operationId, $0) })
        let intents = try await store.outbox(identity)
        var errors: [String] = []
        for operationID in Set(old.values.flatMap { $0.map(\.operationId) }).sorted() {
            guard let original = known[operationID] else { continue }
            let refs = Set(old.filter { $0.value.contains { $0.operationId == operationID } }.keys)
            for cp in original.checkpoints where original.changes.contains(where: { refs.contains($0.reference) && $0.checkpointId == cp.checkpointId }) {
                guard cp.saveDataComplete else { errors.append("An unpaired legacy snapshot remains in recovery history."); continue }
                let gameID = id("game:\(operationID):\(cp.checkpointId)")
                let game = ProgressCheckpoint(schemaVersion: 3, checkpointId: id("game-cp:\(cp.checkpointId)"), binding: cp.binding, saveCompatibility: cp.saveCompatibility, stateCompatibility: nil, saveDataComplete: true, artifacts: ProgressProtocol.gameArtifacts(cp), deviceId: cp.deviceId, sessionId: cp.sessionId, captureReason: cp.captureReason, createdAtEpochMs: cp.createdAtEpochMs)
                let current = original.changes.contains { $0.reference == "save/current" && refs.contains($0.reference) && $0.checkpointId == cp.checkpointId }
                let gameOp = ProgressOperation(schemaVersion: 3, operationId: gameID, binding: identity.binding, changes: [.init(reference: current ? "game/current" : "game/archive/\(gameID)", parents: [], checkpointId: game.checkpointId, tombstone: false)], checkpoints: [game], deviceId: original.deviceId, sessionId: original.sessionId, createdAtEpochMs: original.createdAtEpochMs)
                let publish = intents.first { $0.operationId == operationID }?.status != "LOCAL_ONLY"
                func commit(_ op: ProgressOperation) async throws {
                    if known[op.operationId] != nil { return }
                    var sources: [String: URL] = [:]
                    for artifact in op.checkpoints.flatMap(\.artifacts) { sources[artifact.sha256] = try await store.artifact(identity, artifact) }
                    try await store.commit(identity, payload: ProgressProtocol.encode(op), sources: sources, publish: publish)
                }
                do { try await commit(gameOp) } catch { errors.append("Legacy game data needs repair: \(error.localizedDescription)"); continue }
                let stateRefs = original.changes.filter { refs.contains($0.reference) && $0.checkpointId == cp.checkpointId && $0.reference != "save/current" }
                if let compatibility = cp.stateCompatibility, !stateRefs.isEmpty {
                    let state = ProgressCheckpoint(schemaVersion: 3, checkpointId: id("state-cp:\(operationID):\(cp.checkpointId)"), binding: cp.binding, saveCompatibility: cp.saveCompatibility, stateCompatibility: compatibility, saveDataComplete: true, artifacts: cp.artifacts, deviceId: cp.deviceId, sessionId: cp.sessionId, captureReason: cp.captureReason, createdAtEpochMs: cp.createdAtEpochMs, gameSaveOperationId: gameID)
                    let op = ProgressOperation(schemaVersion: 3, operationId: id("state:\(operationID):\(cp.checkpointId)"), binding: identity.binding, changes: stateRefs.map { .init(reference: "state/\($0.reference)", parents: [], checkpointId: state.checkpointId, tombstone: false) }, checkpoints: [state], deviceId: original.deviceId, sessionId: original.sessionId, createdAtEpochMs: original.createdAtEpochMs)
                    do { try await commit(op) } catch { errors.append("Legacy snapshot retained for repair: \(error.localizedDescription)") }
                }
            }
        }
        return errors
    }
}
