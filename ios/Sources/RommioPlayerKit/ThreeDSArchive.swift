import Foundation
import RommioFoundation
import ZIPFoundation

/// Materializes one playable 3DS member without trusting ZIP paths as filesystem paths.
public enum ThreeDSArchive {
    public struct Member: Sendable {
        public let url: URL
        public let sha256: String
        public let path: String
    }

    private static let playableExtensions: Set<String> = ["3ds", "3dsx", "cci", "cxi", "app"]
    private static let maximumMemberBytes: UInt64 = 8 * 1024 * 1024 * 1024

    public static func materialize(_ container: URL, in directory: URL) throws -> Member {
        let archive = try Archive(url: container, accessMode: .read)
        let candidates = archive.filter { entry in
            let ext = URL(fileURLWithPath: entry.path).pathExtension.lowercased()
            return playableExtensions.contains(ext)
        }
        guard candidates.count == 1, let entry = candidates.first, entry.type == .file,
              entry.uncompressedSize >= 512, entry.uncompressedSize <= maximumMemberBytes,
              safeMemberPath(entry.path) else {
            throw ThreeDSArchiveError.invalidMembers
        }
        let fileManager = FileManager.default
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        let output = directory.appending(path: "game.\(URL(fileURLWithPath: entry.path).pathExtension.lowercased())")
        let staging = directory.appending(path: ".extract-\(UUID().uuidString)")
        defer { try? fileManager.removeItem(at: staging) }
        _ = try archive.extract(entry, to: staging)
        let extractedSize = try fileManager.attributesOfItem(atPath: staging.path)[.size] as? NSNumber
        guard extractedSize?.uint64Value == entry.uncompressedSize else {
            throw ThreeDSArchiveError.incompleteMember
        }
        if fileManager.fileExists(atPath: output.path) { try fileManager.removeItem(at: output) }
        try fileManager.moveItem(at: staging, to: output)
        return Member(url: output, sha256: try ProgressFileHash.sha256(output), path: entry.path)
    }

    private static func safeMemberPath(_ path: String) -> Bool {
        guard !path.isEmpty, path.utf8.count <= 512, !path.hasPrefix("/"), !path.hasSuffix("/"),
              !path.contains("\\"), !path.contains(":"),
              !path.unicodeScalars.contains(where: { $0.value < 32 || $0.value == 127 }) else { return false }
        let parts = path.split(separator: "/", omittingEmptySubsequences: false)
        return parts.count <= 16 && parts.allSatisfy { !$0.isEmpty && $0 != "." && $0 != ".." }
    }
}

public enum ThreeDSArchiveError: LocalizedError {
    case invalidMembers, incompleteMember

    public var errorDescription: String? {
        switch self {
        case .invalidMembers: "The ZIP must contain exactly one supported 3DS game file, with a safe path and size."
        case .incompleteMember: "The extracted 3DS game file is incomplete."
        }
    }
}
