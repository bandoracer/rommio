#if DEBUG && canImport(UIKit)
import SwiftUI
import ImageIO
import RommioFoundation

/// Opt-in fixture (`ROMMIO_SCREENS_QA=1`): Settings › Screen playground on its own, with
/// the save previews already on this device as its frames; no server or profile is
/// opened. A preview's console is guessed from its size (160×144 a Game Boy, 240×160 a
/// GBA, 320×120 a DS's two screens side by side), else it's a living-room game.
public struct ScreenPlaygroundQAHarness: View {
    @State private var appearance = AppearanceStore()
    private let samples: [ScreenSample]

    public init() {
        samples = Self.previews()
    }

    public var body: some View {
        ThemeHost(appearance: appearance) {
            NavigationStack {
                ScreenPlaygroundPage(samples: samples)
            }
        }
    }

    private static func previews(limit: Int = 8) -> [ScreenSample] {
        guard let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first,
              let files = FileManager.default.enumerator(at: support.appending(path: "Rommio/SavePreviews"),
                                                        includingPropertiesForKeys: [.contentModificationDateKey]) else { return [] }
        let previews = files.compactMap { $0 as? URL }.filter { $0.pathExtension == "jpg" }
            .map { ($0, (try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast) }
            .sorted { $0.1 > $1.1 }
        var seenSizes = Set<String>()
        var samples: [ScreenSample] = []
        for (url, _) in previews where samples.count < limit {
            guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
                  let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
                  let width = properties[kCGImagePropertyPixelWidth] as? Int,
                  let height = properties[kCGImagePropertyPixelHeight] as? Int else { continue }
            // One per size: a spread of consoles (two previews of one size are usually one game).
            guard seenSizes.insert("\(width)x\(height)").inserted else { continue }
            let platform = switch (width, height) {
            case (160, 144): "gbc"
            case (240, 160): "gba"
            case (320, 120): "nds"
            default: ""
            }
            samples.append(ScreenSample(id: url.lastPathComponent, title: "Save \(samples.count + 1) · \(width)×\(height)",
                                        platformSlug: platform, url: url))
        }
        return samples
    }
}
#endif
