import Darwin
import Foundation
import RommioContract
import Synchronization

/// Actor-owned files, fenced across processes by an exclusive lock on the stable storage root.
final class ProgressFileVault: @unchecked Sendable {
    private let root: URL
    private var owner: Int32 = -1
    init(root: URL, ownerLock: URL? = nil) throws {
        try Self.check(!Self.symlink(root), "Progress root is a symlink")
        self.root = try Self.canonicalRoot(root)
        if ownerLock == nil { try Self.directories(self.root) }
        let lock = ownerLock ?? self.root.appending(path: ".owner.lock")
        owner = Darwin.open(lock.path, O_RDWR | O_CREAT | O_NOFOLLOW, S_IRUSR | S_IWUSR)
        guard owner >= 0 else { throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO) }
        guard flock(owner, LOCK_EX | LOCK_NB) == 0 else { Darwin.close(owner); owner = -1; throw ProgressStoreError.invalid("Progress store already has a writer") }
        do { try Self.directories(self.root) } catch { close(); throw error }
    }
    deinit { close() }
    func close() { if owner >= 0 { flock(owner, LOCK_UN); Darwin.close(owner); owner = -1 } }
    private static func check(_ value: Bool, _ message: String) throws { if !value { throw ProgressStoreError.invalid(message) } }
    // URL.resolvingSymlinksInPath deliberately preserves macOS /var aliases.
    // Resolve the existing ancestor with realpath, then append missing children.
    private static func canonicalRoot(_ url: URL) throws -> URL {
        if let path = realpath(url.path, nil) {
            defer { free(path) }
            return URL(fileURLWithPath: String(cString: path), isDirectory: true)
        }
        guard errno == ENOENT, url.path != "/" else { throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO) }
        return try canonicalRoot(url.deletingLastPathComponent()).appending(path: url.lastPathComponent)
    }
    private static func symlink(_ url: URL) -> Bool { (try? FileManager.default.attributesOfItem(atPath: url.path)[.type] as? FileAttributeType) == .typeSymbolicLink }
    private func content(_ identity: ProgressIdentity) -> URL { root.appending(path: identity.namespaceId).appending(path: identity.contentId) }
    private func blob(_ identity: ProgressIdentity, digest: String) -> URL { content(identity).appending(path: "blobs").appending(path: digest) }
    private static func directories(_ url: URL) throws {
        if url.path == "/" { return }
        try directories(url.deletingLastPathComponent())
        try check(!symlink(url), "Progress directory must not be a symlink")
        var directory: ObjCBool = false
        if !FileManager.default.fileExists(atPath: url.path, isDirectory: &directory) {
            try FileManager.default.createDirectory(at: url, withIntermediateDirectories: false)
            try syncDirectory(url.deletingLastPathComponent())
        } else { try check(directory.boolValue, "Expected directory") }
    }
    private static func syncDirectory(_ url: URL) throws {
        let fd = Darwin.open(url.path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW)
        guard fd >= 0 else { throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO) }
        defer { Darwin.close(fd) }
        if fsync(fd) != 0 { throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO) }
    }
    private static func verifyFile(_ url: URL, artifact: ProgressArtifact) throws {
        let before = VerifiedArtifacts.Identity(url)
        if let before, verified.matches(url.path, before, artifact) { return }
        try directories(url.deletingLastPathComponent())
        let info = try FileManager.default.attributesOfItem(atPath: url.path)
        try check(info[.type] as? FileAttributeType == .typeRegular && (info[.size] as? NSNumber)?.int64Value == artifact.byteLength, "Missing or truncated checkpoint artifact")
        try check(try ProgressFileHash.sha256(url) == artifact.sha256, "Corrupt checkpoint artifact")
        if let before, VerifiedArtifacts.Identity(url) == before { verified.insert(url.path, before, artifact) }
    }
    private static let verified = VerifiedArtifacts()
    func verify(_ identity: ProgressIdentity, artifacts: [ProgressArtifact]) throws {
        for artifact in artifacts { try Self.verifyFile(blob(identity, digest: artifact.sha256), artifact: artifact) }
    }
    private static func copy(_ source: URL, to target: URL, artifact: ProgressArtifact) throws {
        let info = try FileManager.default.attributesOfItem(atPath: source.path)
        try check(info[.type] as? FileAttributeType == .typeRegular && (info[.size] as? NSNumber)?.int64Value == artifact.byteLength, "Invalid artifact source")
        let fd = Darwin.open(target.path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, S_IRUSR | S_IWUSR)
        guard fd >= 0 else { throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO) }
        let output = FileHandle(fileDescriptor: fd, closeOnDealloc: true)
        defer { try? output.close() }
        let input = try FileHandle(forReadingFrom: source); defer { try? input.close() }
        var remaining = artifact.byteLength
        while remaining > 0 {
            let more = try FileChunks.next(input, upTo: Int(min(65536, remaining))) { data in
                try output.write(contentsOf: data); remaining -= Int64(data.count)
            }
            guard more else { throw ProgressStoreError.invalid("Truncated artifact") }
        }
        try check((try input.read(upToCount: 1))?.isEmpty != false, "Artifact grew during capture")
        try output.synchronize()
        try verifyFile(target, artifact: artifact)
    }
    func install(_ identity: ProgressIdentity, artifact: ProgressArtifact, source: URL?) throws {
        let target = blob(identity, digest: artifact.sha256)
        try Self.directories(target.deletingLastPathComponent())
        if FileManager.default.fileExists(atPath: target.path) || Self.symlink(target) { try Self.verifyFile(target, artifact: artifact); return }
        guard let source else { throw ProgressStoreError.invalid("Checkpoint artifact source is missing") }
        let staging = target.deletingLastPathComponent().appending(path: ".staging-\(UUID().uuidString.lowercased())")
        defer { try? FileManager.default.removeItem(at: staging) }
        try Self.copy(source, to: staging, artifact: artifact)
        if rename(staging.path, target.path) != 0 { throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO) }
        try Self.syncDirectory(target.deletingLastPathComponent())
    }
    func artifactURL(_ identity: ProgressIdentity, artifact: ProgressArtifact) throws -> URL {
        let url = blob(identity, digest: artifact.sha256)
        try Self.verifyFile(url, artifact: artifact)
        return url
    }
    func repair(_ identity: ProgressIdentity, artifact: ProgressArtifact, source: URL) throws {
        try Self.check(!Self.symlink(source), "Repair source must not be a symlink")
        let source = try Self.canonicalRoot(source)
        let target = blob(identity, digest: artifact.sha256)
        if (try? Self.verifyFile(target, artifact: artifact)) != nil { return }
        try Self.verifyFile(source, artifact: artifact)
        try Self.directories(target.deletingLastPathComponent())
        try Self.check(!Self.symlink(target), "Repair target must not be a symlink")
        let staging = target.deletingLastPathComponent().appending(path: ".repair-\(UUID().uuidString.lowercased())")
        defer { try? FileManager.default.removeItem(at: staging) }
        try Self.copy(source, to: staging, artifact: artifact)
        if FileManager.default.fileExists(atPath: target.path) {
            let backup = content(identity).appending(path: "repair-backups/\(UUID().uuidString.lowercased())")
            try Self.directories(backup.deletingLastPathComponent())
            try FileManager.default.copyItem(at: target, to: backup)
            let handle = try FileHandle(forWritingTo: backup); try handle.synchronize(); try handle.close()
            try Self.syncDirectory(backup.deletingLastPathComponent())
        }
        if rename(staging.path, target.path) != 0 { throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO) }
        try Self.syncDirectory(target.deletingLastPathComponent())
    }
    func materialize(_ identity: ProgressIdentity, checkpoint: ProgressCheckpoint, sessionId: String) throws -> URL {
        let working = content(identity).appending(path: "working")
        try Self.directories(working)
        let target = working.appending(path: sessionId)
        try Self.check(!FileManager.default.fileExists(atPath: target.path) && !Self.symlink(target), "Working session already exists")
        let staging = working.appending(path: ".staging-\(UUID().uuidString.lowercased())")
        try Self.directories(staging)
        defer { try? FileManager.default.removeItem(at: staging) }
        for artifact in checkpoint.artifacts {
            let path = staging.appending(path: artifact.relativePath)
            try Self.directories(path.deletingLastPathComponent())
            try Self.copy(blob(identity, digest: artifact.sha256), to: path, artifact: artifact)
            try Self.syncDirectory(path.deletingLastPathComponent())
        }
        try Self.syncDirectory(staging)
        if rename(staging.path, target.path) != 0 { throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO) }
        try Self.syncDirectory(working)
        return target
    }
}

/// Stored artifacts are immutable, so one that matched its digest is not hashed again while its file
/// identity is unchanged. Any write, replacement or move changes the device, inode, size, modification
/// or status-change time, and the next read verifies it in full. Sync reads every stored save and
/// state of every game every 30 seconds, and re-hashing them all hitched games mid-play.
final class VerifiedArtifacts: Sendable {
    typealias Identity = FileIdentity
    private struct Entry { let identity: Identity; let digest: String; let length: Int64 }
    private let entries = Mutex<[String: Entry]>([:])

    func matches(_ path: String, _ identity: Identity, _ artifact: ProgressArtifact) -> Bool {
        guard let entry = entries.withLock({ $0[path] }) else { return false }
        return entry.identity == identity && entry.digest == artifact.sha256 && entry.length == artifact.byteLength
    }
    func insert(_ path: String, _ identity: Identity, _ artifact: ProgressArtifact) {
        entries.withLock { $0[path] = Entry(identity: identity, digest: artifact.sha256, length: artifact.byteLength) }
    }
}
