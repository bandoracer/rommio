import SwiftUI

// MARK: Lighting a page

/// Lights everything below with one or more games' covers (Aurora only): sets
/// the page light (ground, lights, tint, glows) that lit views read. Riso and
/// games without usable colour keep the theme's own light.
///
/// Palettes already in memory light the page on its first frame (covers decoded in
/// Aurora prepare their light in the background). Otherwise the light arrives once
/// and only the views that read it update; the page field crossfades
/// (`PageBackground`), nothing else animates. When the lighting game changes, the light
/// already shown stays until the new one is ready (its palettes and blurred cover), so
/// the page never drops to its unlit ground on the way.
private struct LitByModifier: ViewModifier {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.artworkPipeline) private var pipeline
    let urls: [URL]
    let artURL: URL?
    @State private var loaded: (key: LightKey, light: PageLight)?

    private struct LightKey: Hashable {
        let urls: [URL]
        let artURL: URL?
        let theme: String
    }

    func body(content: Content) -> some View {
        let key = LightKey(urls: Array(urls.prefix(3)), artURL: artURL, theme: theme.identity)
        let light = resolved(key)
        content
            .environment(\.pageLight, light)
            // The system tint colours text (bar and list buttons): readable on the lit ground.
            .tint(light.readableTint)
            .task(id: key) {
                guard theme.isGlass, !key.urls.isEmpty else { return }
                // Remembered even when ready at once, so the next change can hold it.
                if let light = ready(key) { loaded = (key, light); return }
                var palettes: [CoverPalette] = []
                for url in key.urls {
                    if let palette = await pipeline.palette(for: url) { palettes.append(palette) }
                }
                if let artURL { _ = await pipeline.backdrop(for: artURL, dark: theme.isDark) }
                if !Task.isCancelled { loaded = (key, PageLight.lit(by: palettes, artURL: artURL, theme: theme)) }
            }
    }

    private func resolved(_ key: LightKey) -> PageLight {
        guard theme.isGlass, !key.urls.isEmpty else { return theme.unlitLight }
        if let loaded, loaded.key == key { return loaded.light }
        if let light = ready(key) { return light }
        // Still loading: keep the light already shown (in this theme) rather than the unlit page.
        if let loaded, loaded.key.theme == key.theme { return loaded.light }
        return theme.unlitLight
    }

    /// The light, when its palettes and blurred cover are both in memory.
    private func ready(_ key: LightKey) -> PageLight? {
        guard let palettes = pipeline.cachedPalettes(for: key.urls) else { return nil }
        if let artURL, pipeline.cachedBackdrop(for: artURL, dark: theme.isDark) == nil { return nil }
        return PageLight.lit(by: palettes, artURL: artURL, theme: theme)
    }
}

extension View {
    /// Aurora: light the page with this game's cover (ground, lights, tint) and
    /// blur the cover into the page background.
    func litBy(_ url: URL?) -> some View {
        modifier(LitByModifier(urls: url.map { [$0] } ?? [], artURL: url))
    }

    /// Aurora: light the page with several games (up to three lights); `art` is
    /// blurred into the background.
    func litBy(_ urls: [URL], art: URL?) -> some View {
        modifier(LitByModifier(urls: urls, artURL: art))
    }
}

/// Palettes of several covers for small marks (pager dots, day light strips, bars),
/// read from memory at once and loaded in the background otherwise (Aurora only).
struct CoverPalettes: ViewModifier {
    @Environment(\.artworkPipeline) private var pipeline
    @Environment(\.rommioTheme) private var theme
    let urls: [URL]
    @Binding var palettes: [URL: CoverPalette]

    func body(content: Content) -> some View {
        content.task(id: urls.map(\.absoluteString).joined(separator: "|") + (theme.isGlass ? "#glass" : "#paper")) {
            guard theme.isGlass else { return }
            var found = palettes
            for url in urls where found[url] == nil {
                if let cached = pipeline.cachedPalette(for: url) { found[url] = cached }
            }
            if found != palettes { palettes = found }
            for url in urls where palettes[url] == nil {
                if let palette = await pipeline.palette(for: url), !Task.isCancelled { palettes[url] = palette }
            }
        }
    }
}

extension View {
    func coverPalettes(_ urls: [URL], into palettes: Binding<[URL: CoverPalette]>) -> some View {
        modifier(CoverPalettes(urls: urls, palettes: palettes))
    }
}

// MARK: Page backgrounds

/// Where the lights sit (the studio's `.a-field` variants): the room of Play, a
/// page with a hero (Game, Stats), the quieter lists, the welcome fan.
enum PageLightStyle: Sendable {
    case room, page, quiet, welcome

    struct Spot {
        let centre: CGPoint
        let radii: CGSize
        let fade: CGFloat
    }

    /// Centres and radii as fractions of the page (radial-gradient(sx sy at px py, …, transparent 70–72%)).
    var spots: [Spot] {
        switch self {
        case .room:
            [Spot(centre: CGPoint(x: 0.26, y: 0.24), radii: CGSize(width: 0.9, height: 0.46), fade: 0.7),
             Spot(centre: CGPoint(x: 0.96, y: 0.06), radii: CGSize(width: 0.7, height: 0.34), fade: 0.7),
             Spot(centre: CGPoint(x: 0.4, y: 0.86), radii: CGSize(width: 1.1, height: 0.4), fade: 0.72)]
        case .page:
            [Spot(centre: CGPoint(x: 0.08, y: 0), radii: CGSize(width: 0.8, height: 0.3), fade: 0.7),
             Spot(centre: CGPoint(x: 0.96, y: 0.04), radii: CGSize(width: 0.7, height: 0.3), fade: 0.7),
             Spot(centre: CGPoint(x: 0.5, y: 0.26), radii: CGSize(width: 1.1, height: 0.26), fade: 0.72)]
        case .quiet:
            [Spot(centre: CGPoint(x: 0.1, y: 0), radii: CGSize(width: 0.8, height: 0.26), fade: 0.7),
             Spot(centre: CGPoint(x: 0.96, y: 0.02), radii: CGSize(width: 0.6, height: 0.24), fade: 0.7),
             Spot(centre: CGPoint(x: 0.5, y: 0.2), radii: CGSize(width: 1.0, height: 0.18), fade: 0.72)]
        case .welcome:
            [Spot(centre: CGPoint(x: 0.14, y: 0.18), radii: CGSize(width: 0.8, height: 0.4), fade: 0.7),
             Spot(centre: CGPoint(x: 0.94, y: 0.3), radii: CGSize(width: 0.7, height: 0.4), fade: 0.7),
             Spot(centre: CGPoint(x: 0.5, y: 0.5), radii: CGSize(width: 1.0, height: 0.44), fade: 0.72)]
        }
    }

    /// The veil that settles the light into the ground: (location, ground opacity).
    var veil: [(CGFloat, Double)] {
        switch self {
        case .room: [(0, 0), (0.48, 0), (0.76, 0.55), (1, 0.88)]
        case .page: [(0, 0), (0.3, 0.4), (0.58, 0.82), (0.86, 1), (1, 1)]
        case .quiet: [(0, 0), (0.36, 0.7), (0.66, 1), (1, 1)]
        case .welcome: [(0, 0), (0.42, 0.3), (0.76, 0.86), (1, 1)]
        }
    }

    /// The whole field's opacity (quiet pages are dimmer).
    var opacity: Double { self == .quiet ? 0.62 : 1 }
}

/// Aurora's ambient field: the ground, the blurred cover, three radial lights and
/// a veil (studio `.a-field`), in the page's light; when the light changes the
/// field crossfades (the only thing that animates with the light). Riso: paper
/// stock with a fine grain.
struct PageBackground: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.pageLight) private var light
    var style: PageLightStyle = .quiet

    var body: some View {
        ZStack {
            if theme.isGlass {
                theme.ground
                // The new light fades in over the old one (never through the ground).
                LayeredCrossfade(value: FieldKey(light: light, style: !light.isLit && style != .welcome ? .quiet : style)) { field in
                    AuroraField(light: field.light, style: field.style)
                }
            } else {
                theme.ground
                PaperGrain()
            }
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
        .allowsHitTesting(false)
    }
}

private struct FieldKey: Equatable {
    let light: PageLight
    let style: PageLightStyle
}

private struct AuroraField: View {
    let light: PageLight
    let style: PageLightStyle

    var body: some View {
        let dark = light.isDark
        GeometryReader { proxy in
            let size = proxy.size
            ZStack(alignment: .topLeading) {
                light.ground
                if let url = light.artURL {
                    BlurredCover(url: url, dark: dark)
                        .frame(width: size.width * 1.5, height: size.height * 0.78)
                        // A CSS blur spills past the image's box; fade the baked
                        // blur out instead of ending on a hard edge.
                        .mask(LinearGradient(stops: [.init(color: .black, location: 0), .init(color: .black, location: 0.55),
                                                     .init(color: .clear, location: 1)], startPoint: .top, endPoint: .bottom))
                        .offset(x: -size.width * 0.25, y: -size.height * 0.12)
                        .opacity(Double(dark ? AuroraArtTokens.backdropOpacityDark : AuroraArtTokens.backdropOpacityLight))
                }
                FieldLights(lights: light.lights, style: style)
                    .opacity(Double(dark ? AuroraArtTokens.lightOpacityDark : AuroraArtTokens.lightOpacityLight))
                    .blendMode(dark ? .screen : .normal)
                LinearGradient(stops: style.veil.map { .init(color: light.ground.opacity($0.1), location: $0.0) },
                               startPoint: .top, endPoint: .bottom)
            }
            .frame(width: size.width, height: size.height, alignment: .topLeading)
            .clipped()
        }
        .opacity(style.opacity)
    }
}

/// The three radial lights of the field.
/// Each light is a circle filled with a radial gradient, squashed to its ellipse. Shapes
/// and gradients are drawn by the GPU; a full-screen `Canvas` rasterised on the CPU cost
/// tens of milliseconds whenever the light changed (twice during a crossfade), a visible hitch.
private struct FieldLights: View {
    let lights: AuroraLights
    let style: PageLightStyle

    var body: some View {
        GeometryReader { proxy in
            let size = proxy.size
            let colours = [lights.l1, lights.l2, lights.l3].map { $0.themeColor.color }
            ZStack(alignment: .topLeading) {
                ForEach(Array(zip(style.spots, colours).enumerated()), id: \.offset) { _, light in
                    let (spot, colour) = light
                    let rx = max(1, spot.radii.width * size.width)
                    let ry = max(1, spot.radii.height * size.height)
                    Circle()
                        .fill(RadialGradient(stops: [.init(color: colour, location: 0), .init(color: colour.opacity(0), location: spot.fade)],
                                             center: .center, startRadius: 0, endRadius: rx))
                        .frame(width: rx * 2, height: rx * 2)
                        .scaleEffect(x: 1, y: ry / rx)
                        .position(x: spot.centre.x * size.width, y: spot.centre.y * size.height)
                }
            }
            .frame(width: size.width, height: size.height, alignment: .topLeading)
        }
        // The lights blend with each other first, then as one layer with the page (as the canvas did).
        .compositingGroup()
    }
}

/// The cover blurred, saturated and brightened into light (studio
/// `.a-field-art`, `--art-filter`), baked once by the artwork pipeline (in the
/// background as soon as the cover was shown in Aurora, so usually from memory).
struct BlurredCover: View {
    @Environment(\.artworkPipeline) private var pipeline
    let url: URL
    let dark: Bool
    @State private var image: (key: String, image: DecodedArtwork)?

    var body: some View {
        let key = url.absoluteString + (dark ? "#d" : "#l")
        let art = image.flatMap { $0.key == key ? $0.image : nil } ?? pipeline.cachedBackdrop(for: url, dark: dark)
        ZStack {
            if let art {
                Image(decorative: art.cgImage, scale: 1)
                    .resizable()
                    .interpolation(.high)
                    .transition(.opacity)
            }
        }
        .task(id: key) {
            guard art == nil else { return }
            if let baked = await pipeline.backdrop(for: url, dark: dark), !Task.isCancelled {
                withAnimation(.easeOut(duration: 0.35)) { image = (key, baked) }
            }
        }
    }
}

/// Riso paper stock: a fine, deterministic grain over the ground.
struct PaperGrain: View {
    @Environment(\.rommioTheme) private var theme

    var body: some View {
        // The tile is white stock with dark specks: multiplied onto paper by
        // day; inverted (black with light specks) and screened by night.
        if theme.isDark {
            Image(decorative: GrainTexture.image, scale: 2)
                .resizable(resizingMode: .tile)
                .colorInvert()
                .opacity(0.5)
                .blendMode(.screen)
        } else {
            Image(decorative: GrainTexture.image, scale: 2)
                .resizable(resizingMode: .tile)
                .opacity(0.35)
                .blendMode(.multiply)
        }
    }
}

enum GrainTexture {
    /// 180 px of seeded noise (studio `StudioArt.grain(0.06, 180)`).
    static let image: CGImage = {
        let size = 180
        var state: UInt32 = 0x9E37_79B9
        var bytes = [UInt8](repeating: 0, count: size * size * 4)
        for index in 0 ..< size * size {
            state ^= state << 13
            state ^= state >> 17
            state ^= state << 5
            let noise = Double(state % 1000) / 1000
            // Mostly neutral with sparse specks, so the paper reads as stock, not static.
            let value = UInt8(max(0, min(255, 255 - Int(noise * noise * 0.12 * 255 * 2.2))))
            bytes[index * 4] = value
            bytes[index * 4 + 1] = value
            bytes[index * 4 + 2] = value
            bytes[index * 4 + 3] = 255
        }
        let provider = CGDataProvider(data: Data(bytes) as CFData)!
        return CGImage(width: size, height: size, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: size * 4,
                       space: CGColorSpace(name: CGColorSpace.sRGB)!,
                       bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
                       provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent)!
    }()
}

// MARK: Covers

/// A cover with the theme's art treatment: Aurora shows it as it is; Riso prints
/// it in two inks. Gameplay frames and save-state previews never come here.
struct TreatedCover<Placeholder: View>: View {
    @Environment(\.rommioTheme) private var theme
    let url: URL?
    var maxPointSize: CGFloat = 320
    @ViewBuilder var placeholder: () -> Placeholder

    var body: some View {
        if theme.isRiso {
            RisoPrintImage(url: url, maxPointSize: maxPointSize, placeholder: placeholder)
        } else {
            ArtworkImage(url: url, maxPointSize: maxPointSize, preparesLight: theme.isDark, placeholder: placeholder)
        }
    }
}

/// A cover printed in two inks, off register (Riso).
struct RisoPrintImage<Placeholder: View>: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.artworkPipeline) private var pipeline
    @Environment(\.displayScale) private var displayScale
    let url: URL?
    var maxPointSize: CGFloat = 320
    @ViewBuilder var placeholder: () -> Placeholder
    @State private var loaded: (key: String, image: DecodedArtwork)?

    var body: some View {
        let key = "\(url?.absoluteString ?? "")#\(theme.identity)#\(Int(maxPointSize))"
        ZStack {
            if let loaded, loaded.key == key {
                Image(decorative: loaded.image.cgImage, scale: displayScale)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
                    .transition(.opacity)
            } else {
                placeholder()
            }
        }
        .task(id: key) {
            guard let url else { loaded = nil; return }
            let colours = theme.colors
            let style = RisoPrintStyle(
                plateA: ArtRGB(red: colours.accent1.red, green: colours.accent1.green, blue: colours.accent1.blue),
                plateB: ArtRGB(red: colours.accent2.red, green: colours.accent2.green, blue: colours.accent2.blue),
                paper: theme.isDark ? ArtRGB(red: 0, green: 0, blue: 0)
                                    : ArtRGB(red: colours.ground.red, green: colours.ground.green, blue: colours.ground.blue),
                dark: theme.isDark)
            let pixels = Int(maxPointSize * displayScale)
            // Misregistration and the dot pitch are in points; the print is in pixels.
            let image = await pipeline.risoPrint(for: url, maxPixelSize: pixels, style: style,
                                                 misregistration: Double(RisoArtTokens.misregistration * displayScale),
                                                 halftone: Double(RisoArtTokens.halftone),
                                                 dotWidth: Double(max(3, min(5, maxPointSize / 60)) * displayScale))
            if let image, !Task.isCancelled {
                withAnimation(.easeOut(duration: 0.2)) { loaded = (key, image) }
            }
        }
    }
}
