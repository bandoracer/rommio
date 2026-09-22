import Foundation
import CryptoKit
import Darwin

public enum ProgressFileHash {
    public static func sha256(_ url: URL) throws -> String {
        let input = try FileHandle(forReadingFrom: url); defer { try? input.close() }
        var hash = SHA256()
        while let data = try input.read(upToCount: 65536), !data.isEmpty { hash.update(data: data) }
        return hash.finalize().map { String(format: "%02x", $0) }.joined()
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
