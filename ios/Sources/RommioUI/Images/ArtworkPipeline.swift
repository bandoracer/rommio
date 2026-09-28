import CryptoKit
import Foundation
import ImageIO
import os
import SwiftUI

/// A decoded, downsampled image. CGImage is immutable once created.
struct DecodedArtwork: @unchecked Sendable {
    let cgImage: CGImage
}

/// Cover art and other server images: requests are decorated like API calls
/// (edge credentials, cookies, authorization; see `RommAPIClient.fetchAsset`),
/// decoded off the main thread at display size, and cached in memory and on disk.
actor ArtworkPipeline {
    typealias Loader = @Sendable (URL) async throws -> Data

    private let loader: Loader
    private let directory: URL?
    /// Decoded images, prints and backdrops (NSCache is thread-safe; reads may skip the actor).
    private nonisolated(unsafe) let memory = NSCache<NSString, CGImageBox>()
    private var inflight: [String: Task<DecodedArtwork?, Never>] = [:]
    /// Palettes known in memory, readable synchronously so a page lights on its first frame.
    private nonisolated let palettes = OSAllocatedUnfairLock<[String: CoverPalette]>(initialState: [:])
    private var paletteTasks: [String: Task<CoverPalette?, Never>] = [:]
    private var lightTasks: [String: Task<Void, Never>] = [:]

    init(directory: URL? = ArtworkPipeline.defaultDirectory, loader: @escaping Loader = ArtworkPipeline.plainLoader) {
        self.directory = directory
        self.loader = loader
        memory.countLimit = 400
        if let directory { try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true) }
    }

    static let defaultDirectory: URL? = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first?
        .appending(path: "rommio-artwork", directoryHint: .isDirectory)

    static let plainLoader: Loader = { url in
        let (data, response) = try await URLSession.shared.data(from: url)
        if let http = response as? HTTPURLResponse, !(200 ..< 300).contains(http.statusCode) { throw URLError(.badServerResponse) }
        return data
    }

    /// The image if it's already decoded in memory at this size.
    nonisolated func cachedImage(for url: URL, maxPixelSize: Int) -> DecodedArtwork? {
        memory.object(forKey: "\(url.absoluteString)#\(maxPixelSize)" as NSString).map { DecodedArtwork(cgImage: $0.image) }
    }

    /// The image at `url`, downsampled so its longest side is at most `maxPixelSize`.
    func image(for url: URL, maxPixelSize: Int) async -> DecodedArtwork? {
        let key = "\(url.absoluteString)#\(maxPixelSize)"
        if let cached = memory.object(forKey: key as NSString) { return DecodedArtwork(cgImage: cached.image) }
        if let running = inflight[key] { return await running.value }
        let file = directory?.appending(path: Self.fileName(for: url))
        let loader = self.loader
        let task = Task.detached(priority: .userInitiated) { () -> DecodedArtwork? in
            if let file, let data = try? Data(contentsOf: file), let image = Self.decode(data, maxPixelSize: maxPixelSize) {
                return image
            }
            guard let data = try? await loader(url), let image = Self.decode(data, maxPixelSize: maxPixelSize) else { return nil }
            if let file { try? data.write(to: file, options: .atomic) }
            return image
        }
        inflight[key] = task
        let result = await task.value
        inflight[key] = nil
        if let result { memory.setObject(CGImageBox(result.cgImage), forKey: key as NSString) }
        return result
    }

    /// The cover's five-colour palette (Aurora ambient light), cached in memory
    /// and on disk beside the image. Deterministic for a given image.
    func palette(for url: URL) async -> CoverPalette? {
        let key = url.absoluteString
        if let cached = palettes.withLock({ $0[key] }) { return cached }
        if let running = paletteTasks[key] { return await running.value }
        let file = directory?.appending(path: Self.fileName(for: url) + ".palette.json")
        let task = Task<CoverPalette?, Never> {
            if let file, let data = try? Data(contentsOf: file), let palette = try? JSONDecoder().decode(CoverPalette.self, from: data) {
                return palette
            }
            guard let image = await self.image(for: url, maxPixelSize: 96) else { return nil }
            let palette = await Task.detached(priority: .utility) {
                CoverPaletteExtractor.palette(from: image.cgImage, context: ArtworkPipeline.artContext)
            }.value
            if let file, let palette, let data = try? JSONEncoder().encode(palette) { try? data.write(to: file, options: .atomic) }
            return palette
        }
        paletteTasks[key] = task
        let result = await task.value
        paletteTasks[key] = nil
        if let result { palettes.withLock { $0[key] = result } }
        return result
    }

    /// The palette if it's already known (memory only), for a first frame without flicker.
    nonisolated func cachedPalette(for url: URL) -> CoverPalette? { palettes.withLock { $0[url.absoluteString] } }

    /// Every palette if all are already known in memory, else nil.
    nonisolated func cachedPalettes(for urls: [URL]) -> [CoverPalette]? {
        palettes.withLock { known in
            let found = urls.compactMap { known[$0.absoluteString] }
            return found.count == urls.count ? found : nil
        }
    }

    /// Puts a palette in memory (tests, and palettes read elsewhere).
    nonisolated func rememberPalette(_ palette: CoverPalette, for url: URL) {
        palettes.withLock { $0[url.absoluteString] = palette }
    }

    /// The baked backdrop if it's already in memory, for a first frame without a fade.
    nonisolated func cachedBackdrop(for url: URL, dark: Bool) -> DecodedArtwork? {
        memory.object(forKey: Self.backdropKey(url, dark: dark)).map { DecodedArtwork(cgImage: $0.image) }
    }

    /// Aurora: a cover was decoded for display, so work out its light in the background
    /// (palette and baked backdrop) before a page asks for it. A Game page opened from
    /// here renders lit on its first frame.
    nonisolated func prepareLight(for url: URL, dark: Bool) {
        if cachedPalette(for: url) != nil, cachedBackdrop(for: url, dark: dark) != nil { return }
        Task(priority: .utility) { await self.startLight(for: url, dark: dark) }
    }

    private func startLight(for url: URL, dark: Bool) {
        let key = url.absoluteString + (dark ? "#d" : "#l")
        guard lightTasks[key] == nil else { return }
        lightTasks[key] = Task(priority: .utility) {
            if let palette = await self.palette(for: url), !palette.isNeutral {
                _ = await self.backdrop(for: url, dark: dark)
            }
            await self.finishLight(key)
        }
    }

    private func finishLight(_ key: String) { lightTasks[key] = nil }

    /// The cover printed in two inks (Riso), at display size, cached in memory.
    func risoPrint(for url: URL, maxPixelSize: Int, style: RisoPrintStyle, misregistration: Double,
                   halftone: Double, dotWidth: Double) async -> DecodedArtwork? {
        let key = "\(url.absoluteString)#\(maxPixelSize)#riso#\(style.dark ? 1 : 0)#\(style.plateA.hex)\(style.plateB.hex)\(style.paper.hex)#\(misregistration)#\(halftone)#\(dotWidth)" as NSString
        if let cached = memory.object(forKey: key) { return DecodedArtwork(cgImage: cached.image) }
        guard let image = await image(for: url, maxPixelSize: maxPixelSize) else { return nil }
        let printed = await Task.detached(priority: .userInitiated) {
            RisoPrinter.print(image.cgImage, style: style, misregistration: misregistration, halftone: halftone,
                              dotWidth: dotWidth, context: ArtworkPipeline.artContext)
        }.value
        guard let printed else { return nil }
        memory.setObject(CGImageBox(printed), forKey: key)
        return DecodedArtwork(cgImage: printed)
    }

    /// The cover as Aurora page light: small, blurred, saturated and brightened
    /// (the studio's `--art-filter`), cached in memory.
    func backdrop(for url: URL, dark: Bool) async -> DecodedArtwork? {
        let key = Self.backdropKey(url, dark: dark)
        if let cached = memory.object(forKey: key) { return DecodedArtwork(cgImage: cached.image) }
        guard let image = await image(for: url, maxPixelSize: 96) else { return nil }
        let baked = await Task.detached(priority: .utility) {
            AuroraBackdrop.render(image.cgImage, dark: dark, context: ArtworkPipeline.artContext)
        }.value
        guard let baked else { return nil }
        memory.setObject(CGImageBox(baked), forKey: key)
        return DecodedArtwork(cgImage: baked)
    }

    private nonisolated static func backdropKey(_ url: URL, dark: Bool) -> NSString {
        "\(url.absoluteString)#backdrop#\(dark ? 1 : 0)" as NSString
    }

    /// One Core Image context for palette extraction and printing: maths on
    /// encoded sRGB values, like the studio's CSS.
    /// (CIContext is thread-safe.)
    nonisolated(unsafe) static let artContext = RisoPrinter.makeContext()

    /// Drops everything cached on disk and in memory.
    func removeAll() {
        palettes.withLock { $0.removeAll() }
        memory.removeAllObjects()
        if let directory {
            try? FileManager.default.removeItem(at: directory)
            try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        }
    }

    nonisolated static func fileName(for url: URL) -> String {
        SHA256.hash(data: Data(url.absoluteString.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    nonisolated static func decode(_ data: Data, maxPixelSize: Int) -> DecodedArtwork? {
        guard let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: max(1, maxPixelSize),
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        return DecodedArtwork(cgImage: image)
    }
}

private final class CGImageBox: @unchecked Sendable {
    let image: CGImage
    init(_ image: CGImage) { self.image = image }
}

private struct ArtworkPipelineKey: EnvironmentKey {
    static let defaultValue = ArtworkPipeline()
}

extension EnvironmentValues {
    var artworkPipeline: ArtworkPipeline {
        get { self[ArtworkPipelineKey.self] }
        set { self[ArtworkPipelineKey.self] = newValue }
    }
}

/// Server artwork through the shared pipeline. An image already in memory shows on the
/// first frame; while one loads nothing is drawn (the container's quiet surface shows), and
/// `placeholder` stands in only when there is no art or it can't be loaded (a bright
/// placeholder flashing over every cover as a page loads read as the page jumping).
struct ArtworkImage<Placeholder: View>: View {
    let url: URL?
    var contentMode: ContentMode = .fill
    /// Longest side in points; the image is decoded at this size × display scale.
    var maxPointSize: CGFloat = 320
    /// Aurora: once the cover is decoded, work out its light (palette and backdrop)
    /// in the background for this scheme, so its Game page opens lit.
    var preparesLight: Bool? = nil
    @ViewBuilder var placeholder: () -> Placeholder

    @Environment(\.artworkPipeline) private var pipeline
    @Environment(\.displayScale) private var displayScale
    @State private var loaded: (url: URL, image: DecodedArtwork)?
    @State private var failed: URL?

    var body: some View {
        let pixels = Int(maxPointSize * displayScale)
        let image = loaded.flatMap { $0.url == url ? $0.image : nil } ?? url.flatMap { pipeline.cachedImage(for: $0, maxPixelSize: pixels) }
        ZStack {
            if let image {
                Image(decorative: image.cgImage, scale: displayScale)
                    .resizable()
                    .aspectRatio(contentMode: contentMode)
                    .transition(.opacity)
            } else if url == nil || failed == url {
                placeholder()
            } else {
                // Loading: an empty box at full size, so the image fades in where it belongs
                // (an empty stack has no size, and the arriving image would animate from zero).
                Color.clear
            }
        }
        .task(id: url) {
            guard let url else { loaded = nil; return }
            if image == nil {
                let decoded = await pipeline.image(for: url, maxPixelSize: pixels)
                guard !Task.isCancelled else { return }
                guard let decoded else { failed = url; return }
                withAnimation(.easeOut(duration: 0.2)) { loaded = (url, decoded) }
            }
            // Aurora: decoded for display (so on disk too); work out its light in the background.
            if let dark = preparesLight { pipeline.prepareLight(for: url, dark: dark) }
        }
    }
}
