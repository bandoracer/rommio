import Foundation

/// A regular file's identity: device, inode, size, and modification and status-change times in
/// nanoseconds. Any write, replacement or move changes it, so a file whose identity still matches
/// the one recorded when it was hashed holds the same bytes and needs no hashing.
public struct FileIdentity: Codable, Hashable, Sendable {
    public let device: Int64, inode: UInt64, size: Int64, modified: Int64, changed: Int64

    /// Without following a final symlink. Nil unless a regular file is there.
    public init?(_ url: URL) {
        var info = stat()
        guard lstat(url.path, &info) == 0, info.st_mode & S_IFMT == S_IFREG else { return nil }
        device = Int64(info.st_dev); inode = UInt64(info.st_ino); size = Int64(info.st_size)
        modified = Int64(info.st_mtimespec.tv_sec) * 1_000_000_000 + Int64(info.st_mtimespec.tv_nsec)
        changed = Int64(info.st_ctimespec.tv_sec) * 1_000_000_000 + Int64(info.st_ctimespec.tv_nsec)
    }
}
