import Foundation
import RommioContract

/// The signed-in account a reader filters by (a progress namespace's binding).
public struct ProgressAccount: Hashable, Sendable {
    public let serverKey: String
    public let accountId: String

    public init(serverKey: String, accountId: String) {
        self.serverKey = serverKey
        self.accountId = accountId
    }
}

/// A reference with more than one head: a choice is needed before loading it automatically.
public struct ProgressConflict: Sendable, Hashable, Identifiable {
    public enum Kind: Sendable, Hashable { case resume, gameSave, slot }

    /// One competing head.
    public struct Choice: Sendable, Hashable, Identifiable {
        public let operationId: String
        /// The head's checkpoint as a save (nil for a deletion).
        public let entry: SaveEntry?
        public var id: String { operationId }
    }

    public let identity: ProgressIdentity
    public let reference: String
    public let kind: Kind
    /// Newest first; a deletion last.
    public let choices: [Choice]
    public var id: String { "\(identity.namespaceId)/\(identity.contentId)/\(reference)" }
    public var romId: Int64 { identity.binding.romId }

    /// "EarthBound has two resume points. Choose which to keep playing."
    public func prompt(gameName: String) -> String {
        let count = choices.count
        let number = count == 2 ? "two" : count == 3 ? "three" : "\(count)"
        switch kind {
        case .resume: return "\(gameName) has \(number) resume points. Choose which to keep playing."
        case .gameSave: return "\(gameName) has \(number) versions of its game-save data. Choose which to keep playing."
        case .slot: return "\(gameName) has \(number) versions of a save state. Choose which to keep."
        }
    }
}

/// Read-only view of saved progress across games for the signed-in account
/// (docs/play-data-local.md §3). Uses only the progress store's read APIs
/// (`identities`, `references`, `history`, `outbox`); names and previews come from
/// local play data. It never creates identities, commits, pins or changes references.
public actor SavedProgressReader {
    public static let recentLimit = 24
    public static let maxHistory = 200

    private let storeProvider: @Sendable () async throws -> ProgressStore
    private let deviceProvider: @Sendable () async -> String?
    private let playData: PlayDataStore
    private let thumbnails: SaveThumbnailStore
    private let formFactor: @Sendable () async -> DeviceFormFactor

    public init(storeProvider: @escaping @Sendable () async throws -> ProgressStore,
                deviceProvider: @escaping @Sendable () async -> String?,
                playData: PlayDataStore,
                thumbnails: SaveThumbnailStore,
                formFactor: @escaping @Sendable () async -> DeviceFormFactor) {
        self.storeProvider = storeProvider
        self.deviceProvider = deviceProvider
        self.playData = playData
        self.thumbnails = thumbnails
        self.formFactor = formFactor
    }

    private struct Context {
        let store: ProgressStore
        let identities: [ProgressIdentity]
        let device: String?
        let formFactor: DeviceFormFactor
    }

    private func context(_ account: ProgressAccount) async throws -> Context {
        let store = try await storeProvider()
        let all = try await store.identities()
        // Previews of identities that no longer exist (e.g. after an import) go.
        thumbnails.pruneIdentities(keeping: Set(all.map { "\($0.namespaceId)/\($0.contentId)" }))
        let mine = all.filter { $0.binding.serverKey == account.serverKey && $0.binding.accountId == account.accountId }
        return Context(store: store, identities: mine, device: await deviceProvider(), formFactor: await formFactor())
    }

    /// Recent saves (Play): newest first, at most `limit`.
    public func recent(_ account: ProgressAccount, limit: Int = recentLimit) async throws -> [SaveEntry] {
        let context = try await context(account)
        var entries: [SaveEntry] = []
        for identity in context.identities {
            entries += try await self.entries(context, identity, prune: true)
        }
        return SavedProgressRules.recent(entries, limit: limit)
    }

    /// One game's saves. The install's content generation (its file and content
    /// digest) is used when known; otherwise every generation of the ROM.
    public func gameSaves(_ account: ProgressAccount, romId: Int64, fileId: Int64? = nil, contentSha256: String? = nil) async throws -> GameSaves {
        let context = try await context(account)
        let generations = context.identities.filter { $0.binding.romId == romId }
        let install = generations.filter { identity in
            guard let first = identity.binding.files.min(by: { $0.discIndex < $1.discIndex }) else { return false }
            if let fileId, first.fileId != fileId { return false }
            if let contentSha256 { return first.contentSha256 == contentSha256 || first.containerSha256 == contentSha256 }
            return fileId != nil
        }
        var entries: [SaveEntry] = []
        for identity in install.isEmpty ? generations : install {
            entries += try await self.entries(context, identity, prune: true)
        }
        return SavedProgressRules.forGame(entries)
    }

    /// Describes entries a live player session already read (its own history).
    public func describe(_ identity: ProgressIdentity, history: [ProgressHistoryEntry]) async throws -> [SaveEntry] {
        let store = try await storeProvider()
        let stored = try await storedCheckpoints(store, identity, history)
        let labels = try playData.labels(namespaceId: identity.namespaceId, contentId: identity.contentId)
        let device = await deviceProvider(), form = await formFactor()
        return stored.map { SavedProgressRules.entry($0, labels: labels, localDeviceId: device, formFactor: form, thumbnail: thumbnails.urlIfPresent) }
    }

    /// References with more than one head, for the Conflict sheet. With `romId`, one game only.
    public func conflicts(_ account: ProgressAccount, romId: Int64? = nil) async throws -> [ProgressConflict] {
        let context = try await context(account)
        var result: [ProgressConflict] = []
        for identity in context.identities where romId == nil || identity.binding.romId == romId {
            let references = try await context.store.references(identity)
            guard references.values.contains(where: { $0.count > 1 }) else { continue }
            let history = Array(try await context.store.history(identity).prefix(Self.maxHistory))
            let stored = Dictionary(try await storedCheckpoints(context.store, identity, history).map { ($0.key.checkpointId, $0) }, uniquingKeysWith: { a, _ in a })
            let labels = try playData.labels(namespaceId: identity.namespaceId, contentId: identity.contentId)
            for (reference, heads) in references.sorted(by: { $0.key < $1.key }) where heads.count > 1 {
                let kind: ProgressConflict.Kind
                if SavedProgressRules.isResumeReference(reference) { kind = .resume }
                else if SavedProgressRules.slotId(reference) != nil { kind = .slot }
                else if reference == "game/current" || reference == "save/current" { kind = .gameSave }
                else { continue }
                let choices = heads.map { head -> ProgressConflict.Choice in
                    let entry = head.checkpoint.flatMap { stored[$0.checkpointId] }.map {
                        SavedProgressRules.entry($0, labels: labels, localDeviceId: context.device, formFactor: context.formFactor, thumbnail: thumbnails.urlIfPresent)
                    }
                    return ProgressConflict.Choice(operationId: head.operationId, entry: entry)
                }
                .sorted { ($0.entry?.capturedAtEpochMs ?? .min, $0.operationId) > ($1.entry?.capturedAtEpochMs ?? .min, $1.operationId) }
                result.append(ProgressConflict(identity: identity, reference: reference, kind: kind, choices: choices))
            }
        }
        return result
    }

    /// Names a save state; an empty name removes it. Local only: the checkpoint is unchanged.
    @discardableResult
    public func rename(_ key: SaveKey, to raw: String) throws -> String? {
        try playData.setLabel(key, raw: raw)
    }

    // MARK: Reading the store

    private func entries(_ context: Context, _ identity: ProgressIdentity, prune: Bool) async throws -> [SaveEntry] {
        let full = try await context.store.history(identity)
        let history = Array(full.prefix(Self.maxHistory))
        let stored = try await storedCheckpoints(context.store, identity, history)
        if prune && full.count <= Self.maxHistory {
            // The complete history was read: previews of checkpoints no longer kept go.
            thumbnails.prune(namespaceId: identity.namespaceId, contentId: identity.contentId,
                             keep: Set(stored.filter { $0.retained && $0.hasState }.map(\.key.checkpointId)))
        }
        let labels = try playData.labels(namespaceId: identity.namespaceId, contentId: identity.contentId)
        return stored.map { SavedProgressRules.entry($0, labels: labels, localDeviceId: context.device, formFactor: context.formFactor, thumbnail: thumbnails.urlIfPresent) }
    }

    private func storedCheckpoints(_ store: ProgressStore, _ identity: ProgressIdentity, _ history: [ProgressHistoryEntry]) async throws -> [StoredCheckpoint] {
        var currentMap: [String: Set<String>] = [:]
        var conflictedMap: [String: Set<String>] = [:]
        var operationMap: [String: String] = [:]
        var checkpointMap: [String: ProgressCheckpoint] = [:]
        for (reference, heads) in try await store.references(identity) {
            for head in heads {
                guard let checkpoint = head.checkpoint else { continue }
                currentMap[checkpoint.checkpointId, default: []].insert(reference)
                if heads.count > 1 { conflictedMap[checkpoint.checkpointId, default: []].insert(reference) }
                if operationMap[checkpoint.checkpointId] == nil { operationMap[checkpoint.checkpointId] = head.operationId }
                if checkpointMap[checkpoint.checkpointId] == nil { checkpointMap[checkpoint.checkpointId] = checkpoint }
            }
        }
        let current = currentMap, conflicted = conflictedMap, headOperation = operationMap, headCheckpoint = checkpointMap
        let outbox = Dictionary(try await store.outbox(identity).map { ($0.operationId, $0.status) }, uniquingKeysWith: { a, _ in a })
        let fileId = identity.binding.files.min(by: { $0.discIndex < $1.discIndex })?.fileId
        func stored(_ checkpoint: ProgressCheckpoint, operationId: String, manual: Bool) -> StoredCheckpoint {
            StoredCheckpoint(
                key: SaveKey(namespaceId: identity.namespaceId, contentId: identity.contentId, checkpointId: checkpoint.checkpointId),
                romId: identity.binding.romId,
                fileId: fileId,
                operationId: operationId,
                capturedAtEpochMs: checkpoint.createdAtEpochMs,
                captureReason: checkpoint.captureReason,
                deviceId: checkpoint.deviceId,
                hasState: checkpoint.stateCompatibility != nil,
                manual: manual,
                // iOS keeps every checkpoint of a game's history locally (no retention eviction).
                retained: true,
                currentReferences: current[checkpoint.checkpointId] ?? [],
                conflictedReferences: conflicted[checkpoint.checkpointId] ?? [],
                outboxStatus: outbox[operationId]
            )
        }
        let fromHistory = history.map { entry in
            stored(entry.checkpoint, operationId: entry.operationId.isEmpty ? (headOperation[entry.checkpoint.checkpointId] ?? "") : entry.operationId,
                   manual: entry.manual || (current[entry.checkpoint.checkpointId] ?? []).contains { SavedProgressRules.slotId($0) != nil })
        }
        // Heads older than the entries read are still current; include them from the references.
        let seen = Set(fromHistory.map(\.key.checkpointId))
        let olderHeads = headCheckpoint.filter { !seen.contains($0.key) }.sorted { $0.key < $1.key }.map { id, checkpoint in
            stored(checkpoint, operationId: headOperation[id] ?? "",
                   manual: checkpoint.captureReason == "manual" || (current[id] ?? []).contains { SavedProgressRules.slotId($0) != nil })
        }
        return fromHistory + olderHeads
    }
}
