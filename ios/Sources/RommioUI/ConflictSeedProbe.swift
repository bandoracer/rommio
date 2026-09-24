#if DEBUG && canImport(UIKit)
import Foundation
import RommioContract
import RommioFoundation

/// Opt-in QA fixture (Debug builds only, `ROMMIO_SEED_RESUME_CONFLICT=1`): turns an
/// existing local resume history into the shape a second device leaves behind, so
/// the Conflict sheet can be exercised against a mock server without progress
/// endpoints. For each game whose resume reference has one head with a parent, it
/// commits (local only, never published) a sibling head on that reference: a copy of
/// an earlier state checkpoint attributed to another device. Nothing is deleted.
@MainActor
extension RommioAppModel {
    func seedResumeConflictIfRequested(services: RommioServices) async {
        guard ProcessInfo.processInfo.environment["ROMMIO_SEED_RESUME_CONFLICT"] == "1" else { return }
        do {
            let store = try await services.progress.store()
            let peer = "00000000-0000-4000-8000-00000000d0e0"
            for identity in try await store.identities() {
                let references = try await store.references(identity)
                guard let (reference, heads) = references.first(where: { $0.key.hasPrefix("state/resume/") }), heads.count == 1,
                      let head = heads.first else { continue }
                let operations = try await store.payloads(identity).map(ProgressProtocol.decode)
                guard let parents = operations.first(where: { $0.operationId == head.operationId })?.changes.first(where: { $0.reference == reference })?.parents,
                      !parents.isEmpty else { continue }
                let history = try await store.history(identity)
                guard let earlier = history.first(where: { $0.checkpoint.stateCompatibility != nil && $0.checkpoint.checkpointId != head.checkpoint?.checkpointId
                                                       && $0.checkpoint.captureReason != "restore" && $0.checkpoint.captureReason != "safety" })?.checkpoint else { continue }
                let copy = ProgressCheckpoint(schemaVersion: earlier.schemaVersion, checkpointId: UUID().uuidString.lowercased(), binding: earlier.binding,
                                              saveCompatibility: earlier.saveCompatibility, stateCompatibility: earlier.stateCompatibility,
                                              saveDataComplete: earlier.saveDataComplete, artifacts: earlier.artifacts, deviceId: peer,
                                              sessionId: UUID().uuidString.lowercased(), captureReason: "background",
                                              createdAtEpochMs: max(earlier.createdAtEpochMs, (head.checkpoint?.createdAtEpochMs ?? 0) - 3_600_000),
                                              gameSaveOperationId: earlier.gameSaveOperationId)
                let branch = ProgressOperation(schemaVersion: 3, operationId: UUID().uuidString.lowercased(), binding: identity.binding,
                                               changes: [ProgressRefChange(reference: reference, parents: parents, checkpointId: copy.checkpointId, tombstone: false)],
                                               checkpoints: [copy], deviceId: peer, sessionId: copy.sessionId, createdAtEpochMs: copy.createdAtEpochMs)
                try await store.commit(identity, payload: ProgressProtocol.encode(branch), sources: [:], publish: false)
                NSLog("[ConflictSeedProbe] seeded a resume conflict for rom %lld", identity.binding.romId)
            }
        } catch {
            NSLog("[ConflictSeedProbe] %@", error.localizedDescription)
        }
    }
}
#endif
