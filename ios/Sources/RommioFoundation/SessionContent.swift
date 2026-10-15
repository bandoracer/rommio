import Foundation

/// A session plays its own copy of the game, so replacing the install mid-game can't change it.
public enum SessionContent {
    /// On APFS the copy is a clone that shares the install's storage. The install's identity is the
    /// one verified moments ago, before and after cloning, so the clone holds the verified bytes and
    /// needs no hashing. Without a clone, or if the install changed, the copy is hashed.
    public static func isolate(_ source: URL, to destination: URL, sha256: String, verified: FileIdentity?) throws {
        try FileManager.default.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
        if let verified, FileIdentity(source) == verified,
           clonefile(source.path, destination.path, 0) == 0 {
            if FileIdentity(source) == verified { return }
            try? FileManager.default.removeItem(at: destination)
        }
        try FileManager.default.copyItem(at: source, to: destination)
        guard try ProgressFileHash.sha256(destination) == sha256 else {
            throw ProgressStoreError.invalid("Content changed while preparing the session")
        }
    }
}
