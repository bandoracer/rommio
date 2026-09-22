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
    public func startForeground() {
        guard timer == nil else { return }
        timer = Task { [weak self] in
            while !Task.isCancelled {
                await self?.wake()
                do { try await Task.sleep(for: .seconds(30)) } catch { return }
            }
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
    public func endPlayer(_ lease: String) { players.remove(lease) }
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
