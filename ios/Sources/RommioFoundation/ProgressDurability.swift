import Foundation
import CryptoKit
import Darwin

public enum ProgressFileHash {
    /// Streams the file a megabyte at a time in constant memory, so a multi-gigabyte game hashes like a save.
    public static func sha256(_ url: URL) throws -> String {
        let input = try FileHandle(forReadingFrom: url); defer { try? input.close() }
        var hash = SHA256()
        while try FileChunks.next(input, upTo: 1 << 20, { hash.update(data: $0) }) {}
        return hash.finalize().map { String(format: "%02x", $0) }.joined()
    }
}

/// Chunked file reads that free each chunk before the next one. FileHandle's reads are autoreleased,
/// and in optimized builds a plain loop keeps every chunk until it ends: hashing a 2.9 GB game at
/// launch passed iOS's memory limit and Rommio was killed before it could finish.
public enum FileChunks {
    /// Reads up to `count` bytes and hands them to `body`; false at the end of the file.
    public static func next(_ input: FileHandle, upTo count: Int, _ body: (Data) throws -> Void) throws -> Bool {
        try autoreleasepool {
            guard let data = try input.read(upToCount: count), !data.isEmpty else { return false }
            try body(data)
            return true
        }
    }
}
public enum ProgressDurability {
    public static func write(_ data: Data, to url: URL) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let temporary = url.deletingLastPathComponent().appending(path: ".pending-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: temporary) }
        let fd = Darwin.open(temporary.path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, S_IRUSR | S_IWUSR)
        guard fd >= 0 else { throw POSIXError(.EIO) }
        let handle = FileHandle(fileDescriptor: fd, closeOnDealloc: true)
        try handle.write(contentsOf: data); try handle.synchronize(); try handle.close()
        guard rename(temporary.path, url.path) == 0 else { throw POSIXError(.EIO) }
        let directory = Darwin.open(url.deletingLastPathComponent().path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW)
        guard directory >= 0 else { throw POSIXError(.EIO) }
        defer { Darwin.close(directory) }
        guard fsync(directory) == 0 else { throw POSIXError(.EIO) }
    }
}
