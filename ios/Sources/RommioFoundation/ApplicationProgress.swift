import Foundation
#if DEBUG
import Darwin
#endif
import RommioContract

/// Application lifetime owner of the store and durable sync drains. Screen cancellation does not own these tasks.
public actor ApplicationProgress {
    public nonisolated let root: URL
    private let database: AppDatabase
    private let profiles: GRDBServerProfileStore
    private let auth: AuthSessionController
    private var instance: ProgressStore?
    private var jobs: [String: Task<ProgressSyncResult, Error>] = [:]
    private var results: [String: ProgressSyncResult] = [:]
    private var failures: [String: String] = [:]
    private var epoch = 0
    private var maintenance = false
    private var players = Set<String>()
    private var timer: Task<Void, Never>?
    public init(database: AppDatabase, root: URL, profiles: GRDBServerProfileStore, auth: AuthSessionController) {
        self.database = database; self.root = root; self.profiles = profiles; self.auth = auth
    }
    public func store() throws -> ProgressStore {
        guard !maintenance else { throw ProgressStoreError.invalid("Progress backup or recovery is in progress") }
        if let instance { return instance }
        do { let lock = try ProgressStorageLock(root.appendingPathExtension("owner.lock")); defer { withExtendedLifetime(lock) {} }; try transfer.recover() }
        let store = try ProgressStore(database: database, rootDirectory: root.appending(path: "store"), ownerLock: root.appendingPathExtension("owner.lock")); instance = store
        return store
    }
    public func deviceID() throws -> String {
        let path = root.appending(path: "device.json")
        if FileManager.default.fileExists(atPath: path.path) {
            let id = try JSONDecoder().decode(String.self, from: Data(contentsOf: path))
            guard UUID(uuidString: id)?.uuidString.lowercased() == id else { throw ProgressStoreError.invalid("Invalid installation identity") }; return id
        }
        let id = UUID().uuidString.lowercased(); try ProgressDurability.write(JSONEncoder().encode(id), to: path); return id
    }
    /// Wakes every game's sync now and every 30 seconds. While a player is open only games with
    /// saves waiting to upload sync (in practice the one being played), because the emulator shares
    /// the device with this work; closing the last player wakes everything again.
    public func startForeground() {
        guard timer == nil else { return }
        timer = Task { [weak self] in
            await self?.removeClosedSessions()
            while !Task.isCancelled {
                await self?.periodicWake()
                do { try await Task.sleep(for: .seconds(30)) } catch { return }
            }
        }
    }
    /// Call at app start, when no game is running: a session folder without a closed marker belongs
    /// to a run that ended abruptly. Recovery keeps its saves; its copy of the game isn't needed, as
    /// the game stays installed, and before games were unzipped once it was a full 3DS game. The
    /// folders are listed now, so a game started meanwhile keeps its copy.
    public nonisolated func releaseInterruptedSessionContent() {
        let fileManager = FileManager.default
        let copies = ((try? fileManager.contentsOfDirectory(at: root.appending(path: "sessions"), includingPropertiesForKeys: nil)) ?? [])
            .filter { !fileManager.fileExists(atPath: $0.appending(path: "closed").path) }
            .map { $0.appending(path: "content") }
        Task.detached(priority: .utility) {
            for copy in copies { try? FileManager.default.removeItem(at: copy) }
        }
    }
    /// A session's folder holds its working saves and its copy of the game. Once the session closed,
    /// its progress is in the store, so the folder is deleted, as on Android. Folders of sessions
    /// that never closed stay for recovery.
    public func removeClosedSessions() async {
        guard !maintenance else { return }
        let sessions = root.appending(path: "sessions")
        await Task.detached(priority: .utility) {
            let fileManager = FileManager.default
            for session in (try? fileManager.contentsOfDirectory(at: sessions, includingPropertiesForKeys: nil)) ?? []
            where fileManager.fileExists(atPath: session.appending(path: "closed").path) {
                try? fileManager.removeItem(at: session)
            }
        }.value
    }
    private func periodicWake() async {
        guard !players.isEmpty else { return await wake() }
        guard let store = try? store(), let identities = try? await store.identities() else { return }
        for identity in identities {
            let unpublished = (try? await store.outbox(identity))?.contains { $0.status == "PENDING" || $0.status == "LEASED" } ?? false
            if unpublished { kick(identity) }
        }
    }
    public func stopForeground() { timer?.cancel(); timer = nil }
    public func cancelNetworkWork() { for task in jobs.values { task.cancel() } }
    public func wake() async {
        guard let store = try? store(), let identities = try? await store.identities() else { return }
        for identity in identities { kick(identity) }
    }
    public func kick(_ identity: ProgressIdentity) {
        let key = identity.contentId
        guard !maintenance, jobs[key] == nil else { return }
        results[key] = nil; failures[key] = nil
        let job = Task { try await self.run(identity) }
        jobs[key] = job
        let generation = epoch
        Task {
            do { let result = try await job.value; if generation == epoch { results[key] = result } }
            catch { if generation == epoch { failures[key] = error.localizedDescription } }
            if generation == epoch { jobs[key] = nil }
        }
    }

    public func synchronize(_ identity: ProgressIdentity) async throws -> ProgressSyncResult {
        kick(identity)
        if let job = jobs[identity.contentId] { return try await job.value }
        if let result = results[identity.contentId] { return result }
        throw ProgressStoreError.invalid(failures[identity.contentId] ?? "Cloud progress not checked")
    }
    /// Bounded foreground check; a slow upload remains owned by the application.
    public func checkBeforeLaunch(_ identity: ProgressIdentity) async -> String? {
        kick(identity)
        for _ in 0..<30 {
            if jobs[identity.contentId] == nil {
                if let result = results[identity.contentId], result.errors.isEmpty { return nil }
                return failures[identity.contentId] ?? results[identity.contentId]?.message ?? "Cloud progress not checked"
            }
            try? await Task.sleep(for: .milliseconds(100))
        }
        return "Cloud progress was not checked within three seconds. Continue using this device's progress or retry the check."
    }
    public func status(_ identity: ProgressIdentity) -> String {
        if let result = results[identity.contentId] { return result.message }
        return failures[identity.contentId] ?? (jobs[identity.contentId] == nil ? "Cloud progress not checked" : "Checking game saves and snapshots…")
    }
    private func run(_ identity: ProgressIdentity) async throws -> ProgressSyncResult {
        #if DEBUG
        if root.deletingLastPathComponent().lastPathComponent == "rommio-progress-fixture",
           ProcessInfo.processInfo.environment["ROMMIO_PROGRESS_PROBE_OFFLINE"] == "1" {
            throw URLError(.notConnectedToInternet)
        }
        #endif
        let profileList = try await profiles.listProfiles()
        for profile in profileList {
            guard let owner = try await auth.verifiedAccount(profileID: profile.id), String(owner.userID) == identity.binding.accountId,
                  Self.serverKey(owner.baseURL) == identity.binding.serverKey, owner.baseURL == profile.baseURL else { continue }
            let client = await auth.client(for: profile.id, baseURL: profile.baseURL)
            let transport = RommProgressTransport(client: client, binding: identity.binding, verifyOwner: { [auth, profiles] in
                guard let current = try await profiles.profile(id: profile.id), current.baseURL == owner.baseURL,
                      try await auth.verifiedAccount(profileID: profile.id) == owner else { throw AccountBindingError() }
            })
            let ownedStore = try store()
            _ = try await ProgressUpgrade.run(store: ownedStore, identity: identity)
            return try await ProgressSync(store: ownedStore, identity: identity, remote: transport, staging: root.appending(path: "transfers")).synchronize()
        }
        throw ProgressStoreError.invalid("Sign in to the original account to sync. Local progress remains available.")
    }
    private var transfer: ProgressTransfer { ProgressTransfer(database: database, root: root, library: root.deletingLastPathComponent().appending(path: "Library")) }
    public func beginPlayer() throws -> String {
        guard !maintenance else { throw ProgressStoreError.invalid("Finish progress recovery before launching a player") }
        _ = try store()
        let lease = UUID().uuidString.lowercased(); players.insert(lease); return lease
    }
    public func endPlayer(_ lease: String) {
        guard players.remove(lease) != nil, players.isEmpty, timer != nil else { return }
        Task { await self.wake() }
    }
    private func exclusive<T: Sendable>(_ action: (ProgressTransfer) async throws -> T) async throws -> T {
        guard !maintenance, players.isEmpty else { throw ProgressStoreError.invalid("Exit the player before backing up or recovering progress") }
        maintenance = true; epoch += 1
        defer { maintenance = false }
        let pending = Array(jobs.values)
        for job in pending { job.cancel() }
        for job in pending { _ = try? await job.value }
        jobs.removeAll(); results.removeAll(); failures.removeAll()
        if let instance { await instance.close(); self.instance = nil }
        let lock = try ProgressStorageLock(transfer.lock)
        defer { withExtendedLifetime(lock) {} }
        return try await action(transfer)
    }
    public func exportProgress() async throws -> URL {
        try await exclusive { transfer in
            let url = transfer.recovery.appending(path: "exports/Progress-\(UUID().uuidString.lowercased()).rommioprogress")
            try transfer.export(to: url); return url
        }
    }
    public func inspectImport(_ file: URL) async throws -> ProgressImportPreview {
        try await exclusive { try $0.inspect(file) }
    }
    public func importProgress(_ previewID: String) async throws {
        // Accepted activation is independent of the UI caller's cancellation.
        try await exclusive { transfer in
            try transfer.activate(previewID) { boundary in
                #if DEBUG
                let environment = ProcessInfo.processInfo.environment
                if transfer.root.deletingLastPathComponent().lastPathComponent == "rommio-progress-fixture",
                   environment["ROMMIO_IMPORT_PROBE_BOUNDARY"] == String(describing: boundary) {
                    let ready = transfer.root.deletingLastPathComponent().appending(path: "import-boundary.json")
                    try ProgressDurability.write(JSONEncoder().encode(["boundary": String(describing: boundary), "pid": String(ProcessInfo.processInfo.processIdentifier)]), to: ready)
                    // The external fixture runner kills this stopped process; no catch/finalizer runs.
                    raise(SIGSTOP)
                }
                #endif
            }
        }
    }
    public func rollbackPendingImport() async throws { try await exclusive { try $0.rollbackPendingImport() } }
    public func recoverImport() async throws { try await exclusive { try $0.recover() } }
    public func exportPrevious(_ id: String) async throws -> URL {
        try await exclusive { transfer in
            let url = transfer.recovery.appending(path: "exports/Previous-\(UUID().uuidString.lowercased()).rommioprogress")
            try transfer.exportPrevious(id, to: url); return url
        }
    }
    public func recoveryItems() async throws -> [ProgressRecoveryItem] {
        try await exclusive { try $0.recoveryItems() }
    }
    public func archiveRecovery(_ id: String) async throws { try await exclusive { try $0.archiveRecovery(id) } }
    public func restoreRecoveryArchive(_ id: String) async throws { try await exclusive { try $0.restoreArchive(id) } }
    public func retryCapture(_ id: String) async throws {
        try await exclusive { transfer in
            try transfer.recover(); try ProgressArchive.safePath(id)
            guard id.hasPrefix("captures/"), id.split(separator: "/").count == 3 else { throw ProgressStoreError.invalid("Invalid capture") }
            let directory = transfer.root.appending(path: id)
            _ = try ProgressArchive.inventory(directory)
            let journal = try JSONDecoder().decode(ProgressCaptureJournal.self, from: Data(contentsOf: directory.appending(path: "capture.json")))
            let store = try ProgressStore(database: transfer.database, rootDirectory: transfer.root.appending(path: "store"))
            do {
                let errors = try await ProgressCaptureJournal.recover(root: directory.deletingLastPathComponent(), store: store, identity: journal.identity)
                await store.close()
                guard errors.isEmpty else { throw ProgressStoreError.invalid(errors.joined(separator: " ")) }
            } catch { await store.close(); throw error }
        }
    }
    public func repairArtifact(_ item: ProgressRecoveryItem, source: URL) async throws {
        try await exclusive { transfer in
            try transfer.recover()
            guard let identity = item.identity, let artifact = item.artifact, item.kind == .damagedArtifact else { throw ProgressStoreError.invalid("Select a damaged artifact") }
            let store = try ProgressStore(database: transfer.database, rootDirectory: transfer.root.appending(path: "store"))
            do { try await store.repairArtifact(identity, artifact: artifact, source: source); await store.close() }
            catch { await store.close(); throw error }
        }
    }
    public func publishLocalHistory(_ identity: ProgressIdentity) async throws {
        try await exclusive { transfer in
            try transfer.recover()
            let store = try ProgressStore(database: transfer.database, rootDirectory: transfer.root.appending(path: "store"))
            do { try await store.enablePublication(identity); await store.close() }
            catch { await store.close(); throw error }
        }
        kick(identity)
    }
    public func legacyRecoveryFiles() throws -> [URL] {
        guard !maintenance else { throw ProgressStoreError.invalid("Progress recovery is busy") }
        let folder = root.appending(path: "legacy-recovery")
        return try ProgressArchive.inventory(folder).filter { $0.sha256 != nil && ["srm", "state"].contains(URL(fileURLWithPath: $0.path).pathExtension.lowercased()) }.map { folder.appending(path: $0.path) }
    }
    public static func serverKey(_ url: URL) -> String {
        var components = URLComponents(url: url, resolvingAgainstBaseURL: false)!
        components.scheme = components.scheme?.lowercased(); components.host = components.host?.lowercased()
        if (components.scheme == "https" && components.port == 443) || (components.scheme == "http" && components.port == 80) { components.port = nil }
        var value = components.string!; if value.hasSuffix("/") { value.removeLast() }; return value
    }
}
