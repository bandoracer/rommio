import Foundation
import RommioContract
import RommioFoundation

public struct ProgressCaptureResult: Sendable {
    public let game: ProgressCheckpoint
    public let snapshot: ProgressCheckpoint?
    public let snapshotError: String?
    public var message: String { snapshot != nil ? "Game-save data saved. Resume snapshot saved." : "Game-save data saved. \(snapshotError ?? "Snapshot not requested.")" }
}
public struct ProgressColdBootRequired: LocalizedError, Sendable {
    public let reason: String
    public var errorDescription: String? { reason }
}
/// What a launch would stop to ask (`ProgressSessionOwner.launchQuestion()`).
public enum ProgressLaunchQuestion: Sendable, Equatable {
    /// It starts without asking: it resumes, or starts from the game-save data.
    case none
    /// Resume points exist but none can be loaded by this core with the current
    /// game-save data: only Start with game save is possible.
    case resumeUnavailable(String)
}
public struct ProgressRestoreConfirmation: Error, Sendable {
    public let checkpointID: String
}

/// All accepted commands are retained and ordered, including across suspension points.
/// Network reception never changes this session's frozen causal parents or native memory.
public actor ProgressSessionOwner {
    public nonisolated let identity: ProgressIdentity
    public nonisolated let sessionID: String
    public nonisolated let qualification: CartridgePersistence
    private let store: ProgressStore
    private let engine: any ProgressPlayerEngine
    private let deviceID: String
    private let captureRoot: URL
    private let workspace: URL
    private let sync: ApplicationProgress?
    private let preparedSession: PlayerSession?
    /// Local play data (sessions and previews); observes, never affects a save.
    private let playDataRecorder: (any PlayDataRecording)?
    private let playDataStart: PlaySessionStart?
    private var playData: (any PlayDataSession)?
    private var parents: [String: [String]] = [:]
    private var tail: Task<Void, Never>?
    private var pendingGame: URL?
    private var started = false
    private var paused = true
    private var closed = false
    public init(identity: ProgressIdentity, sessionID: String, qualification: CartridgePersistence, store: ProgressStore, engine: any ProgressPlayerEngine, deviceID: String, captureRoot: URL, workspace: URL, sync: ApplicationProgress? = nil, preparedSession: PlayerSession? = nil,
                playData: (any PlayDataRecording)? = nil, platformSlug: String? = nil) {
        self.identity = identity; self.sessionID = sessionID; self.qualification = qualification; self.store = store; self.engine = engine
        self.deviceID = deviceID; self.captureRoot = captureRoot; self.workspace = workspace; self.sync = sync; self.preparedSession = preparedSession
        self.playDataRecorder = playData
        self.playDataStart = PlaySessionStart(sessionId: sessionID, identity: identity, deviceId: deviceID,
                                              platformSlug: platformSlug, coreId: qualification.state.coreId)
    }

    /// Paused or running, as the engine was told; local play data counts running time only.
    private func setEnginePaused(_ value: Bool) async throws {
        try await engine.setPaused(value); paused = value
        playData?.phase(value ? .paused : .running)
    }

    /// Ends the local play-data session if the player never closed it (e.g. the player was dismissed on sign-out).
    public func endPlayData() {
        guard let playData else { return }
        playData.phase(.stopped); self.playData = nil
    }
    private func ordered<T: Sendable>(_ action: @escaping @Sendable (isolated ProgressSessionOwner) async throws -> T) async throws -> T {
        let prior = tail
        let accepted = Task { if let prior { await prior.value }; return try await action(self) }
        tail = Task { _ = try? await accepted.value }
        return try await accepted.value
    }
    public func launch(coldBoot: Bool = false, startPaused: Bool = false) async throws -> String {
        try await ordered { owner in try await owner.launchNow(coldBoot: coldBoot, startPaused: startPaused) }
    }
    /// Whether `launch()` would stop to ask before starting, read-only (nothing is
    /// recovered, restored or written): the Launch choice sheet asks before the
    /// player opens instead. A game-save conflict and format errors are left to the
    /// launch itself.
    public func launchQuestion() async throws -> ProgressLaunchQuestion {
        try await ordered { owner in
            let refs = try await owner.store.references(owner.identity)
            let gameHeads = refs["game/current"] ?? []
            guard gameHeads.count == 1, let game = gameHeads.first?.checkpoint, let current = gameHeads.first?.operationId,
                  game.saveCompatibility == owner.qualification.save else { return .none }
            let resumeRefs = refs.filter { $0.key.hasPrefix("state/resume/") }
            guard !resumeRefs.isEmpty else { return .none }
            for head in resumeRefs.values.flatMap({ $0.count == 1 ? $0 : [] }) {
                guard let cp = head.checkpoint, cp.stateCompatibility == owner.qualification.state,
                      let pair = cp.gameSaveOperationId,
                      try await owner.store.isGameAncestor(owner.identity, ancestor: pair, descendant: current),
                      ProgressProtocol.gameArtifacts(cp) == ProgressProtocol.gameArtifacts(game) else { continue }
                return .none
            }
            return .resumeUnavailable("No resume point made by this core matches the current game-save data. Start with game save; every resume point is kept.")
        }
    }
    private func launchNow(coldBoot: Bool, startPaused: Bool = false) async throws -> String {
        guard !closed else { throw ProgressStoreError.invalid("Session closed") }
        if started { return "Session already running" }
        // Progress selection runs now: the local play session starts (0 ms active).
        if playData == nil, let playDataRecorder, let playDataStart { playData = playDataRecorder.begin(playDataStart) }
        do { return try await launchSelected(coldBoot: coldBoot, startPaused: startPaused) }
        catch {
            // A failed start that closed the session ends it as "quit" (0 ms). A cold-boot
            // choice keeps it open for the player's next attempt.
            if closed { playData?.phase(.stopped); playData = nil }
            throw error
        }
    }
    private func launchSelected(coldBoot: Bool, startPaused: Bool) async throws -> String {
        let errors = try await ProgressCaptureJournal.recover(root: captureRoot, store: store, identity: identity)
        guard errors.isEmpty else { throw ProgressStoreError.invalid("Retry retained captures before launching: \(errors[0])") }
        let refs = try await store.references(identity)
        for (ref, heads) in refs { parents[ref] = heads.map(\.operationId) }
        let gameHeads = refs["game/current"] ?? []
        guard gameHeads.count <= 1 else { throw ProgressStoreError.conflict(gameHeads.map(\.operationId)) }
        var message = "Cold boot."
        if let game = gameHeads.first?.checkpoint {
            guard game.saveCompatibility == qualification.save else { throw ProgressStoreError.invalid("Game-save format differs from the loaded cartridge. Select a compatible retained version.") }
            let memory = try await readMemory(game)
            try await engine.restoreProgress(memory: memory, state: nil)
            message = "Loaded game-save data."
            if !coldBoot {
                let candidates = refs.filter { $0.key.hasPrefix("state/resume/") }.values.flatMap { heads -> [ProgressHead] in heads.count == 1 ? heads : [] }
                var usedState = false
                for head in candidates {
                    guard let cp = head.checkpoint, cp.stateCompatibility == qualification.state,
                          let pair = cp.gameSaveOperationId, let current = gameHeads.first?.operationId,
                          try await store.isGameAncestor(identity, ancestor: pair, descendant: current),
                          ProgressProtocol.gameArtifacts(cp) == ProgressProtocol.gameArtifacts(game) else { continue }
                    do {
                        try await restoreNative(cp)
                        message = "Resumed snapshot and its matching game-save data."
                        usedState = true
                    } catch {
                        // A failed unserialize may have mutated the core. Never continue it as a cold boot.
                        await engine.stop()
                        playData?.phase(.paused)
                        if let preparedSession {
                            try await engine.prepare(session: preparedSession)
                            throw ProgressColdBootRequired(reason: "The resume point could not be restored and was kept. Choose Start with game save to use the intact game-save data.")
                        }
                        closed = true
                        throw ProgressStoreError.invalid("The resume point could not be restored. Reopen the game and choose Start with game save to use the kept game-save data. \(error.localizedDescription)")
                    }
                    break
                }
                if !usedState && refs.keys.contains(where: { $0.hasPrefix("state/resume/") }) {
                    throw ProgressColdBootRequired(reason: "No resume point made by this core matches the current game-save data. Choose Start with game save; every resume point is kept.")
                }
            }
        }
        try ProgressDurability.write(JSONEncoder().encode(identity), to: workspace.appending(path: "identity.json"))
        try await engine.startProgress(paused: startPaused); started = true; paused = startPaused
        playData?.phase(startPaused ? .paused : .running)
        return message
    }
    public func capture(reason: String, snapshot: Bool = true, slotID: String? = nil, remainPaused: Bool = false) async throws -> ProgressCaptureResult {
        try await ordered { owner in
            let result = try await owner.captureNow(reason: reason, snapshot: snapshot, slotID: slotID)
            if !remainPaused { try await owner.resumeNow() }
            return result
        }
    }
    private func captureNow(reason: String, snapshot: Bool = true, slotID: String? = nil) async throws -> ProgressCaptureResult {
        guard started && !closed else { throw ProgressStoreError.invalid("Session is not running") }
        guard pendingGame == nil else { throw ProgressStoreError.invalid("Retry the retained game-save capture before continuing") }
        try await setEnginePaused(true)
        // The engine is paused: the surface shows exactly the moment being serialised. A
        // restore capture shows the pre-restore frame, so it takes the source's preview instead.
        playData?.captureStarting(includeState: snapshot, frame: snapshot && reason != "restore" ? engine.previewFrame() : nil)
        let captured = try await engine.captureProgress(includeState: snapshot); try qualification.validate(captured)
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        var bytes: [String: Data] = [:]
        var artifacts: [ProgressArtifact] = []
        for region in qualification.sizes.keys.sorted() where qualification.sizes[region]! > 0 {
            let (role, path) = qualification.artifactInfo(region); let data = captured.memory[region]!
            bytes[path] = data
            artifacts.append(ProgressArtifact(sha256: ProgressProtocol.sha256(data), byteLength: Int64(data.count), role: role, relativePath: path, format: qualification.artifactFormat))
        }
        let game = ProgressCheckpoint(schemaVersion: 3, checkpointId: Self.uuid, binding: identity.binding, saveCompatibility: qualification.save, stateCompatibility: nil, saveDataComplete: true, artifacts: artifacts, deviceId: deviceID, sessionId: sessionID, captureReason: reason, createdAtEpochMs: now)
        let gameOp = try operation(game, refs: ["game/current"])
        pendingGame = try ProgressCaptureJournal.stage(identity: identity, operation: gameOp, bytes: bytes, root: captureRoot)
        let committed = try await ProgressCaptureJournal.commit(pendingGame!, store: store, expected: identity)
        adopt(committed); pendingGame = nil
        playData?.committed(game)
        await sync?.kick(identity)
        guard let data = captured.state, snapshot else { return ProgressCaptureResult(game: game, snapshot: nil, snapshotError: captured.stateError) }
        bytes["resume.state"] = data
        let state = ProgressCheckpoint(schemaVersion: 3, checkpointId: Self.uuid, binding: identity.binding, saveCompatibility: qualification.save, stateCompatibility: qualification.state, saveDataComplete: true, artifacts: artifacts + [ProgressArtifact(sha256: ProgressProtocol.sha256(data), byteLength: Int64(data.count), role: "state", relativePath: "resume.state", format: qualification.state.stateFormat)], deviceId: deviceID, sessionId: sessionID, captureReason: reason, createdAtEpochMs: now, gameSaveOperationId: gameOp.operationId)
        do {
            let refs = ["state/resume/\(try ProgressProtocol.stateCompatibilityKey(state))"] + (slotID.map { ["state/slot/\($0)"] } ?? [])
            let op = try operation(state, refs: refs)
            let journal = try ProgressCaptureJournal.stage(identity: identity, operation: op, bytes: bytes, root: captureRoot)
            adopt(try await ProgressCaptureJournal.commit(journal, store: store, expected: identity))
            playData?.committed(state)
            if reason == "safety" || reason == "import" { try await store.pin(identity, checkpointID: state.checkpointId) }
            await sync?.kick(identity)
            return ProgressCaptureResult(game: game, snapshot: state, snapshotError: nil)
        } catch { return ProgressCaptureResult(game: game, snapshot: nil, snapshotError: "Snapshot was not saved: \(error.localizedDescription). The previous snapshot is retained.") }
    }
    private func operation(_ cp: ProgressCheckpoint, refs: [String]) throws -> ProgressOperation {
        guard refs.allSatisfy({ (parents[$0]?.count ?? 0) <= 1 }) else { throw ProgressStoreError.invalid("Choose a snapshot branch before replacing it") }
        return ProgressOperation(schemaVersion: 3, operationId: Self.uuid, binding: identity.binding, changes: refs.map { ProgressRefChange(reference: $0, parents: parents[$0] ?? [], checkpointId: cp.checkpointId, tombstone: false) }, checkpoints: [cp], deviceId: deviceID, sessionId: sessionID, createdAtEpochMs: cp.createdAtEpochMs)
    }
    private func adopt(_ op: ProgressOperation) { for change in op.changes { parents[change.reference] = [op.operationId] } }
    public func retry() async throws {
        try await ordered { owner in
            if let pending = owner.pendingGame {
                owner.adopt(try await ProgressCaptureJournal.commit(pending, store: owner.store, expected: owner.identity)); owner.pendingGame = nil
            }
            let errors = try await ProgressCaptureJournal.recover(root: owner.captureRoot, store: owner.store, identity: owner.identity)
            guard errors.isEmpty else { throw ProgressStoreError.invalid(errors[0]) }
            await owner.sync?.kick(owner.identity)
        }
    }
    public func resume() async throws { try await ordered { try await $0.resumeNow() } }
    private func resumeNow() async throws {
        guard pendingGame == nil && started && !closed else { throw ProgressStoreError.invalid("Retry the save or reopen the session before continuing") }
        try await setEnginePaused(false)
    }
    public func pause() async throws { try await ordered { owner in try await owner.setEnginePaused(true) } }
    public func restore(checkpointID: String, confirmed: Bool = false) async throws {
        try await ordered { owner in
            guard owner.started && !owner.closed else { throw ProgressStoreError.invalid("Start the session before restoring") }
            let cp = try await owner.store.checkpointByID(owner.identity, id: checkpointID)
            guard cp.saveCompatibility == owner.qualification.save, cp.stateCompatibility == nil || cp.stateCompatibility == owner.qualification.state else { throw ProgressStoreError.invalid("This snapshot requires a different core, platform, or options. Its game-save data remains in history.") }
            let target = try await owner.readMemory(cp)
            try await owner.setEnginePaused(true)
            let current = try await owner.engine.captureProgress(includeState: false); try owner.qualification.validate(current)
            if current.memory != target && !confirmed { throw ProgressRestoreConfirmation(checkpointID: checkpointID) }
            let safety = try await owner.captureNow(reason: "safety")
            guard let rollback = safety.snapshot else { throw ProgressStoreError.invalid("Unable to preserve a safety snapshot. Nothing was restored. \(safety.snapshotError ?? "")") }
            do {
                try await owner.restoreNative(cp)
            } catch {
                do { try await owner.restoreNative(rollback) }
                catch { await owner.engine.stop(); owner.closed = true; owner.playData?.phase(.stopped); owner.playData = nil; throw ProgressStoreError.invalid("Restore and rollback failed. The session stopped; the safety checkpoint is retained.") }
                throw ProgressStoreError.invalid("Restore failed; the safety checkpoint was restored.")
            }
            let restored = try await owner.captureNow(reason: "restore")
            // The new checkpoint shows the loaded moment: the source's preview.
            if let result = restored.snapshot { owner.playData?.restored(sourceCheckpointId: checkpointID, result: result) }
        }
    }
    public func reset() async throws {
        try await ordered { owner in
            let safety = try await owner.captureNow(reason: "safety")
            guard safety.snapshot != nil else { throw ProgressStoreError.invalid("Reset requires a durable safety snapshot") }
            try await owner.engine.reset()
        }
    }
    public func exit() async throws -> ProgressCaptureResult? {
        try await ordered { owner in
            if owner.closed { return nil }
            let result = owner.started ? try await owner.captureNow(reason: "exit") : nil
            await owner.engine.stop(); owner.engine.detach(); owner.closed = true
            // Left the game: "resume" when the exit capture committed a resume point (else
            // "quit"), or "state" right after a manual save state.
            owner.playData?.phase(.closed(exitUpdatedResume: result?.snapshot != nil)); owner.playData = nil
            try ProgressDurability.write(Data("closed".utf8), to: owner.workspace.appending(path: "closed"))
            return result
        }
    }
    public func history() async throws -> [ProgressHistoryEntry] { try await store.history(identity) }
    public func references() async throws -> [String: [ProgressHead]] { try await store.references(identity) }
    public func resolve(reference: String, selected: String, expected: [String]) async throws {
        try await ordered { owner in
            try await owner.store.resolve(owner.identity, reference: reference, selected: selected, expected: expected, deviceID: owner.deviceID, sessionID: owner.sessionID)
            // Resolving cloud metadata does not replace running memory or its causal baseline.
            await owner.sync?.kick(owner.identity)
        }
    }
    public func deleteSnapshot(checkpointID: String) async throws {
        try await ordered { owner in
            let refs = try await owner.store.references(owner.identity)
            let changes = refs.filter { $0.key.hasPrefix("state/") && $0.value.count == 1 && $0.value[0].checkpoint?.checkpointId == checkpointID }
                .map { ProgressRefChange(reference: $0.key, parents: [$0.value[0].operationId], checkpointId: nil, tombstone: true) }
            guard !changes.isEmpty else { throw ProgressStoreError.invalid("This snapshot is no longer the current resume or a manual slot. Its history remains retained.") }
            let op = ProgressOperation(schemaVersion: 3, operationId: Self.uuid, binding: owner.identity.binding, changes: changes, checkpoints: [], deviceId: owner.deviceID, sessionId: owner.sessionID, createdAtEpochMs: Int64(Date().timeIntervalSince1970 * 1000))
            try await owner.store.commit(owner.identity, payload: ProgressProtocol.encode(op), sources: [:])
            owner.adopt(op); await owner.sync?.kick(owner.identity)
        }
    }
    public func importLegacy(file: URL, snapshot: Bool) async throws -> ProgressCaptureResult {
        try await ordered { owner in
            guard owner.started && !owner.closed else { throw ProgressStoreError.invalid("Start the game before importing progress") }
            let size = try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? -1
            guard size > 0 && size <= 256 * 1024 * 1024 else { throw ProgressStoreError.invalid("Import file is empty or too large") }
            let bytes = try Data(contentsOf: file)
            let original = owner.workspace.appending(path: "import-originals/\(ProgressProtocol.sha256(bytes))")
            try ProgressDurability.write(bytes, to: original)
            let safety = try await owner.captureNow(reason: "safety")
            guard let rollback = safety.snapshot else { throw ProgressStoreError.invalid("Import requires a durable safety snapshot") }
            do {
                if snapshot {
                    _ = try await owner.engine.loadState(from: original)
                } else {
                    guard owner.qualification.sizes.filter({ $0.value > 0 }).keys.sorted() == [0], bytes.count == owner.qualification.sizes[0] else { throw ProgressStoreError.invalid("This cartridge requires a complete memory bundle; a lone SRAM file is insufficient") }
                    if let session = owner.preparedSession { try await owner.engine.prepare(session: session); try await owner.engine.startProgress(paused: true) }
                    else { try await owner.engine.reset() }
                    var memory = Dictionary(uniqueKeysWithValues: owner.qualification.sizes.keys.map { ($0, Data()) }); memory[0] = bytes
                    try await owner.engine.restoreProgress(memory: memory, state: nil)
                }
            } catch {
                do { try await owner.restoreNative(rollback) }
                catch { await owner.engine.stop(); owner.closed = true; owner.playData?.phase(.stopped); owner.playData = nil; throw ProgressStoreError.invalid("Import rollback failed. The session stopped; original and safety files remain retained.") }
                throw error
            }
            // Once capture starts, a failed commit may already have durable replay intent.
            // Keep native memory aligned with those exact bytes instead of rolling it back.
            let result = try await owner.captureNow(reason: "import")
            try await owner.store.pin(owner.identity, checkpointID: result.game.checkpointId)
            return result
        }
    }

    private func readMemory(_ cp: ProgressCheckpoint) async throws -> [Int: Data] {
        guard cp.saveCompatibility == qualification.save && cp.saveDataComplete else { throw ProgressStoreError.invalid("Incomplete or incompatible game-save data") }
        var memory = Dictionary(uniqueKeysWithValues: qualification.sizes.keys.map { ($0, Data()) })
        let gameArtifacts = ProgressProtocol.gameArtifacts(cp)
        let expected = qualification.sizes.filter { $0.value > 0 }.keys
        guard gameArtifacts.count == expected.count else { throw ProgressStoreError.invalid("Incomplete persistent memory inventory") }
        for region in expected {
            let (role, path) = qualification.artifactInfo(region)
            guard let artifact = gameArtifacts.first(where: { $0.relativePath == path && $0.role == role && $0.format == qualification.artifactFormat }), qualification.validSize(region: region, count: Int(artifact.byteLength)) else { throw ProgressStoreError.invalid("Game-save memory region differs") }
            memory[region] = try Data(contentsOf: await store.artifact(identity, artifact))
        }
        return memory
    }
    private func restoreNative(_ cp: ProgressCheckpoint) async throws {
        let memory = try await readMemory(cp)
        var state: Data?
        if cp.stateCompatibility != nil {
            guard cp.stateCompatibility == qualification.state, let artifact = cp.artifacts.first(where: { $0.role == "state" }) else { throw ProgressStoreError.invalid("Incompatible snapshot") }
            state = try Data(contentsOf: await store.artifact(identity, artifact))
        }
        if state == nil && started {
            if let preparedSession { try await engine.prepare(session: preparedSession); try await engine.startProgress(paused: true) }
            else { try await engine.reset() }
        }
        try await engine.restoreProgress(memory: memory, state: state)
    }
    private static var uuid: String { UUID().uuidString.lowercased() }
}
