import Foundation
import Darwin

enum AtomicFileInstaller {
    /// Validates before touching the destination and returns the actual installed byte count.
    @discardableResult
    static func installDownload(
        at temporaryURL: URL,
        response: URLResponse,
        to destination: URL,
        expectedBytes: Int64? = nil
    ) throws -> Int64 {
        guard let response = response as? HTTPURLResponse else { throw RommClientError.invalidResponse }
        guard (200..<300).contains(response.statusCode), response.statusCode != 206 else {
            throw RommClientError.http(status: response.statusCode, bodyPreview: "Download was not complete.")
        }
        let size = (try FileManager.default.attributesOfItem(atPath: temporaryURL.path)[.size] as? NSNumber)?.int64Value ?? -1
        guard size >= 0, expectedBytes == nil || expectedBytes == size else { throw URLError(.cannotDecodeContentData) }
        let encoding = response.value(forHTTPHeaderField: "Content-Encoding")?.lowercased()
        if (encoding == nil || encoding == "identity"), response.expectedContentLength >= 0,
           response.expectedContentLength != size { throw URLError(.cannotDecodeContentData) }
        let reader = try FileHandle(forReadingFrom: temporaryURL)
        defer { try? reader.close() }
        let prefix = String(decoding: try reader.read(upToCount: 512) ?? Data(), as: UTF8.self)
            .trimmingCharacters(in: .whitespacesAndNewlines.union(CharacterSet(charactersIn: "\u{FEFF}"))).lowercased()
        guard !(response.mimeType?.lowercased().contains("html") ?? false),
              !prefix.hasPrefix("<!doctype html"), !prefix.hasPrefix("<html") else {
            throw RommClientError.http(status: response.statusCode, bodyPreview: "Received a login/error page instead of game data.")
        }
        try Task.checkCancellation()
        try installCopy(of: temporaryURL, to: destination)
        return size
    }

    static func installCopy(of source: URL, to destination: URL) throws {
        let manager = FileManager.default
        try manager.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
        let staged = destination.deletingLastPathComponent().appendingPathComponent(".rommio-\(UUID().uuidString).partial")
        defer { try? manager.removeItem(at: staged) }
        try manager.copyItem(at: source, to: staged)
        let writer = try FileHandle(forWritingTo: staged)
        do {
            try writer.synchronize()
            try writer.close()
        } catch {
            try? writer.close()
            throw error
        }
        try Task.checkCancellation()
        // POSIX rename atomically replaces the destination on the same volume.
        guard rename(staged.path, destination.path) == 0 else {
            throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno))
        }
    }
}
