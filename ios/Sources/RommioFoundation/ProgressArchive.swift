import Foundation
import Darwin

public struct ProgressArchiveEntry: Codable, Sendable, Equatable {
    public let path: String
    public let length: Int64
    public let sha256: String?
}
public struct ProgressArchiveManifest: Codable, Sendable {
    public var format = "rommio-apple-progress"
    public var version = 1
    public let snapshot: ProgressBackupSnapshot
    public let entries: [ProgressArchiveEntry]
}
public enum ProgressArchive {
    static let magic = Data("ROMMIO-PROGRESS-1\n".utf8)
    static let maxMetadata = 64 * 1024 * 1024
    static let maxFile: Int64 = 8 * 1024 * 1024 * 1024
    static let maxTotal: Int64 = 64 * 1024 * 1024 * 1024
    static func check(_ condition: Bool, _ reason: String) throws { if !condition { throw ProgressStoreError.invalid(reason) } }
    static func safePath(_ path: String) throws {
        let components = path.split(separator: "/", omittingEmptySubsequences: false)
        try check(!components.isEmpty && path.utf8.count <= 4096 && !path.contains("\\") && !path.contains("\0") && components.allSatisfy { !$0.isEmpty && $0 != "." && $0 != ".." && !$0.contains(":") }, "Unsafe backup path")
        try check(!components.contains(".owner.lock") && !components.contains("device.json") && !components.contains(".generation"), "Reserved installation file in backup")
    }
    static func directory(_ url: URL) throws {
        if ["/", "/var", "/tmp"].contains(url.path) { return }
        try directory(url.deletingLastPathComponent())
        if FileManager.default.fileExists(atPath: url.path) {
            try check(try FileManager.default.attributesOfItem(atPath: url.path)[.type] as? FileAttributeType == .typeDirectory, "Unsafe progress directory")
        } else {
            try FileManager.default.createDirectory(at: url, withIntermediateDirectories: false)
            try sync(url.deletingLastPathComponent())
        }
    }
    static func sync(_ directory: URL) throws {
        let fd = Darwin.open(directory.path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW)
        guard fd >= 0 else { throw POSIXError(.EIO) }; defer { Darwin.close(fd) }
        guard fsync(fd) == 0 else { throw POSIXError(.EIO) }
    }
    static func move(_ from: URL, _ to: URL) throws {
        try directory(to.deletingLastPathComponent())
        try check(!FileManager.default.fileExists(atPath: to.path), "Recovery destination already exists")
        guard rename(from.path, to.path) == 0 else { throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO) }
        try sync(from.deletingLastPathComponent()); try sync(to.deletingLastPathComponent())
    }
    static func inventory(_ root: URL) throws -> [ProgressArchiveEntry] {
        guard FileManager.default.fileExists(atPath: root.path) else { return [] }
        try directory(root)
        var result: [ProgressArchiveEntry] = []
        func walk(_ url: URL, prefix: String) throws {
            for file in try FileManager.default.contentsOfDirectory(at: url, includingPropertiesForKeys: nil).sorted(by: { $0.lastPathComponent < $1.lastPathComponent }) {
                let name = file.lastPathComponent
                if name == ".owner.lock" || name == "device.json" || name == ".generation" { continue }
                let path = prefix + name; try safePath(path)
                let attr = try FileManager.default.attributesOfItem(atPath: file.path)
                if attr[.type] as? FileAttributeType == .typeDirectory {
                    result.append(.init(path: path, length: 0, sha256: nil)); try walk(file, prefix: path + "/")
                } else {
                    try check(attr[.type] as? FileAttributeType == .typeRegular, "Backup cannot follow symbolic links or special files")
                    let size = (attr[.size] as? NSNumber)?.int64Value ?? -1
                    try check((0...maxFile).contains(size), "Progress file exceeds backup size limit")
                    result.append(.init(path: path, length: size, sha256: try ProgressFileHash.sha256(file)))
                }
                try check(result.count <= 200_000, "Too many progress backup entries")
            }
        }
        try walk(root, prefix: ""); return result
    }
    static func read(_ input: FileHandle, count: Int) throws -> Data {
        var result = Data()
        while result.count < count {
            guard try FileChunks.next(input, upTo: count - result.count, { result.append($0) }) else { throw ProgressStoreError.invalid("Truncated progress backup") }
        }
        return result
    }
    static func copy(_ input: FileHandle, _ output: FileHandle, length: Int64) throws {
        var remaining = length
        while remaining > 0 {
            try autoreleasepool {
                let chunk = try read(input, count: Int(min(remaining, 65536)))
                try output.write(contentsOf: chunk); remaining -= Int64(chunk.count)
            }
        }
    }
    static func output(_ url: URL) throws -> FileHandle {
        try directory(url.deletingLastPathComponent())
        let fd = Darwin.open(url.path, O_CREAT | O_EXCL | O_WRONLY | O_NOFOLLOW, S_IRUSR | S_IWUSR)
        guard fd >= 0 else { throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO) }
        return FileHandle(fileDescriptor: fd, closeOnDealloc: true)
    }
    static func write(root: URL, snapshot: ProgressBackupSnapshot, to destination: URL) throws {
        let entries = try inventory(root)
        try check(entries.reduce(Int64(0), { $0 + $1.length }) <= maxTotal, "Backup exceeds size limit")
        let data = try JSONEncoder().encode(ProgressArchiveManifest(snapshot: snapshot, entries: entries))
        try check(data.count <= maxMetadata, "Backup metadata exceeds size limit")
        let temporary = destination.deletingLastPathComponent().appending(path: ".backup-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: temporary) }
        let handle = try output(temporary); defer { try? handle.close() }
        try handle.write(contentsOf: magic)
        var size = UInt64(data.count).bigEndian
        try withUnsafeBytes(of: &size) { try handle.write(contentsOf: $0) }
        try handle.write(contentsOf: data)
        for entry in entries where entry.sha256 != nil {
            let file = root.appending(path: entry.path)
            let input = try FileHandle(forReadingFrom: file); defer { try? input.close() }
            try copy(input, handle, length: entry.length)
            try check((try input.read(upToCount: 1))?.isEmpty != false && (try ProgressFileHash.sha256(file)) == entry.sha256, "Progress changed during backup")
        }
        try handle.synchronize(); try handle.close()
        try move(temporary, destination)
    }
    static func extract(_ archive: URL, to root: URL) throws -> ProgressArchiveManifest {
        try check(!FileManager.default.fileExists(atPath: root.path), "Import staging already exists")
        let input = try FileHandle(forReadingFrom: archive); defer { try? input.close() }
        try check(try read(input, count: magic.count) == magic, "This is not a supported iOS progress backup")
        let header = try read(input, count: 8)
        let size = header.reduce(UInt64(0)) { ($0 << 8) | UInt64($1) }
        try check(size > 0 && size <= maxMetadata, "Invalid backup header")
        let manifest = try JSONDecoder().decode(ProgressArchiveManifest.self, from: read(input, count: Int(size)))
        try check(manifest.format == "rommio-apple-progress" && manifest.version == 1 && manifest.entries.count <= 200_000, "Unsupported progress backup version")
        var paths = Set<String>(); var total: Int64 = 0
        for entry in manifest.entries {
            try safePath(entry.path)
            try check(paths.insert(entry.path).inserted && (0...maxFile).contains(entry.length), "Duplicate or oversized backup entry")
            total += entry.length; try check(total <= maxTotal, "Oversized progress backup")
            if let hash = entry.sha256 { try check(hash.count == 64 && hash.allSatisfy { "0123456789abcdef".contains($0) }, "Invalid file digest") }
            else { try check(entry.length == 0, "Invalid backup directory") }
        }
        let directories = Set(manifest.entries.filter { $0.sha256 == nil }.map(\.path))
        for entry in manifest.entries {
            let parts = entry.path.split(separator: "/")
            for index in 1..<parts.count {
                let parent = parts.prefix(index).joined(separator: "/")
                try check(directories.contains(parent), "Missing directory inventory")
            }
        }
        try directory(root)
        for entry in manifest.entries {
            let target = root.appending(path: entry.path)
            if entry.sha256 == nil { try directory(target); continue }
            let output = try output(target); defer { try? output.close() }
            try copy(input, output, length: entry.length); try output.synchronize(); try output.close()
            try check(try ProgressFileHash.sha256(target) == entry.sha256, "Backup file checksum mismatch")
            try sync(target.deletingLastPathComponent())
        }
        try check((try input.read(upToCount: 1))?.isEmpty != false, "Uninventoried bytes in backup")
        return manifest
    }
}
