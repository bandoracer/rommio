import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// Pure sizing rule for save previews (docs/play-data-local.md §2, shared with Android).
public enum ThumbnailSizing {
    public static let maxWidth = 320
    /// JPEG quality (Android encodes WebP at 80).
    public static let jpegQuality = 0.8

    /// At most `maxWidth` wide, aspect kept, never upscaled, height at least 1.
    public static func target(width: Int, height: Int, maxWidth: Int = maxWidth) -> (width: Int, height: Int)? {
        guard width > 0, height > 0 else { return nil }
        if width <= maxWidth { return (width, height) }
        return (maxWidth, max(1, Int(Int64(height) * Int64(maxWidth) / Int64(width))))
    }

    /// Scales a frame to the preview size (a copy; the source is left alone).
    public static func scaled(_ image: CGImage, maxWidth: Int = maxWidth) -> CGImage? {
        guard let size = target(width: image.width, height: image.height, maxWidth: maxWidth) else { return nil }
        if size.width == image.width && size.height == image.height { return image }
        guard let context = CGContext(data: nil, width: size.width, height: size.height, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
        // Pixel art stays crisp only when not smoothed on a downscale either; medium keeps
        // small text legible without a blurry halo.
        context.interpolationQuality = .medium
        context.draw(image, in: CGRect(x: 0, y: 0, width: size.width, height: size.height))
        return context.makeImage()
    }

    /// Lossy JPEG of an already-sized frame.
    public static func encode(_ image: CGImage, quality: Double = jpegQuality) -> Data? {
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return data as Data
    }
}

/// Save previews in app-private storage that is excluded from backups, at a
/// deterministic path `<root>/<namespaceId>/<contentId>/<checkpointId>.jpg`. Local
/// only: never a checkpoint artifact, never synced, never given a theme art treatment.
public final class SaveThumbnailStore: @unchecked Sendable {
    public static let fileExtension = "jpg"
    /// Files younger than this are never pruned: a reader that looked at the store a
    /// moment before a commit must not delete the new frame.
    public static let pruneGraceMs: Int64 = 10 * 60_000

    public let root: URL
    private let fileManager = FileManager.default

    public init(root: URL) {
        self.root = root
    }

    static func isID(_ value: String) -> Bool {
        value.range(of: "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", options: .regularExpression) != nil
    }

    /// The preview's file; nil for identities that are not UUIDs (never a path outside the root).
    public func file(_ key: SaveKey) -> URL? {
        guard Self.isID(key.namespaceId), Self.isID(key.contentId), Self.isID(key.checkpointId) else { return nil }
        return root.appending(path: key.namespaceId).appending(path: key.contentId).appending(path: "\(key.checkpointId).\(Self.fileExtension)")
    }

    /// The preview if it exists and is not empty.
    public func urlIfPresent(_ key: SaveKey) -> URL? {
        guard let url = file(key),
              let size = (try? fileManager.attributesOfItem(atPath: url.path))?[.size] as? NSNumber, size.intValue > 0 else { return nil }
        return url
    }

    /// Scales, encodes and writes a frame atomically (stage, then rename). Throws on
    /// any failure and leaves no partial file.
    public func write(_ key: SaveKey, image: CGImage) throws {
        guard let scaled = ThumbnailSizing.scaled(image), let data = ThumbnailSizing.encode(scaled) else {
            throw CocoaError(.fileWriteUnknown)
        }
        try write(key, data: data)
    }

    func write(_ key: SaveKey, data: Data) throws {
        guard let target = file(key) else { throw CocoaError(.fileWriteInvalidFileName) }
        try prepareDirectory(target.deletingLastPathComponent())
        let staging = target.deletingLastPathComponent().appending(path: ".staging-\(UUID().uuidString.lowercased())")
        defer { try? fileManager.removeItem(at: staging) }
        try data.write(to: staging)
        if rename(staging.path, target.path) != 0 { throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO) }
    }

    /// A restore commits a new checkpoint of the moment it loaded; it shows the source's frame.
    public func copy(from source: SaveKey, to target: SaveKey) throws {
        guard let from = urlIfPresent(source) else { return }
        try write(target, data: Data(contentsOf: from))
    }

    public func delete(_ key: SaveKey) {
        if let url = file(key) { try? fileManager.removeItem(at: url) }
    }

    /// Deletes previews of checkpoints that are no longer kept on this device. Files
    /// younger than `graceMs` are left alone.
    @discardableResult
    public func prune(namespaceId: String, contentId: String, keep: Set<String>, now: Date = Date(), graceMs: Int64 = pruneGraceMs) -> Int {
        guard Self.isID(namespaceId), Self.isID(contentId) else { return 0 }
        let directory = root.appending(path: namespaceId).appending(path: contentId)
        var deleted = 0
        for url in (try? fileManager.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.contentModificationDateKey, .isRegularFileKey])) ?? [] {
            let name = url.lastPathComponent
            let id = url.deletingPathExtension().lastPathComponent
            let stale = name.hasPrefix(".staging-") || (url.pathExtension == Self.fileExtension && !keep.contains(id))
            if stale, old(url, now: now, graceMs: graceMs), (try? fileManager.removeItem(at: url)) != nil { deleted += 1 }
        }
        return deleted
    }

    /// After an import or a store change, previews of identities that no longer exist
    /// in the store are removed (older than the grace period).
    @discardableResult
    public func pruneIdentities(keeping identities: Set<String>, now: Date = Date(), graceMs: Int64 = pruneGraceMs) -> Int {
        var deleted = 0
        for namespace in (try? fileManager.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? [] where Self.isID(namespace.lastPathComponent) {
            for content in (try? fileManager.contentsOfDirectory(at: namespace, includingPropertiesForKeys: nil)) ?? [] where Self.isID(content.lastPathComponent) {
                let key = "\(namespace.lastPathComponent)/\(content.lastPathComponent)"
                guard !identities.contains(key) else { continue }
                deleted += prune(namespaceId: namespace.lastPathComponent, contentId: content.lastPathComponent, keep: [], now: now, graceMs: graceMs)
                if (try? fileManager.contentsOfDirectory(atPath: content.path))?.isEmpty == true { try? fileManager.removeItem(at: content) }
            }
        }
        return deleted
    }

    private func old(_ url: URL, now: Date, graceMs: Int64) -> Bool {
        guard let modified = (try? url.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate else { return true }
        return now.timeIntervalSince(modified) * 1000 >= Double(graceMs)
    }

    private func prepareDirectory(_ directory: URL) throws {
        if !fileManager.fileExists(atPath: root.path) {
            try fileManager.createDirectory(at: root, withIntermediateDirectories: true)
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            var excluded = root
            try? excluded.setResourceValues(values)
        }
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
    }
}
