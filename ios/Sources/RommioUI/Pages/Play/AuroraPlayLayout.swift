import SwiftUI
import RommioContract
import RommioFoundation

/// Aurora Play (docs/theme-layouts.md, Play). Compact: the game you're playing fills the
/// top of the page edge to edge with its cover art (as the Game page does; a screenshot
/// from RomM when it has no cover), dissolving into the page's light, with its caption
/// and Resume over the fade; every game in progress in one row of
/// covers (tap to bring one to the top); three picks with their reasons; then recent
/// saves as a lit gallery by day. On a regular-width display (iPhone Duo open, iPad)
/// the room is one canvas: the screen beside its caption, the other games beneath.
struct AuroraPlayLayout: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    let content: PlayContent
    let actions: PlayActions
    @State private var focusID: Int?
    @State private var palettes: [URL: CoverPalette] = [:]

    var body: some View {
        let games = content.continuePlaying
        let focused = games.first { $0.id == focusID } ?? games.first
        Group {
            if horizontalSizeClass == .regular {
                ScrollView {
                    VStack(alignment: .leading, spacing: theme.spacing.section) {
                        AuroraPlayCanvas(games: games, focused: focused, statusLine: content.statusLine, offline: content.isOffline,
                                         marks: marks(games), palettes: palettes, focusID: $focusID, actions: actions)
                        AuroraRecentSaves(days: content.recentSaveDays, count: content.recentSaves.count, palettes: palettes, actions: actions)
                            .padding(.horizontal, 32)
                        AuroraPlayNotices(staleMessage: content.staleMessage, errorMessage: content.errorMessage,
                                          showsEmpty: content.isEmpty && !content.isLoading)
                            .padding(.horizontal, 32)
                    }
                    .padding(.bottom, 40)
                    .frame(maxWidth: 1180)
                    .frame(maxWidth: .infinity)
                }
                .themedPage(.room)
            } else {
                AuroraHome(content: content, games: games, focused: focused, focusID: $focusID, palettes: palettes, actions: actions)
            }
        }
        .litBy(focused?.artworkURL)
        .coverPalettes(content.markURLs, into: $palettes)
    }

    /// The pager's palettes: each game's cover, when known.
    private func marks(_ games: [PlayGameItem]) -> [CoverPalette?] {
        games.map { game in game.artworkURL.flatMap { palettes[$0] } }
    }
}

// MARK: Compact: the game you're playing, edge to edge

private struct AuroraHome: View {
    let content: PlayContent
    let games: [PlayGameItem]
    let focused: PlayGameItem?
    @Binding var focusID: Int?
    let palettes: [URL: CoverPalette]
    let actions: PlayActions

    var body: some View {
        // Split into small structs (page, hero, sections): one big body overflowed the
        // iPhone's main-thread stack (StackBudgetTests).
        GeometryReader { proxy in
            HomePage(content: content, games: games, focused: focused, focusID: $focusID, palettes: palettes, actions: actions,
                     topInset: proxy.safeAreaInsets.top, size: proxy.size)
        }
    }
}

/// The page: its scroll view, the bar over the art, and the art prepared ahead of a tap.
private struct HomePage: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.artworkPipeline) private var pipeline
    @Environment(\.displayScale) private var displayScale
    let content: PlayContent
    let games: [PlayGameItem]
    let focused: PlayGameItem?
    @Binding var focusID: Int?
    let palettes: [URL: CoverPalette]
    let actions: PlayActions
    let topInset: CGFloat
    let size: CGSize
    @State private var scrolledPastArt = false
    /// The tone of the top of the hero's art, where the status bar sits over it.
    @State private var artTop: ColorScheme?

    /// The hero keeps one height whatever the game, so Resume and everything below it stay
    /// put; a longer caption grows upward into the art (revised 2026-09-27).
    private var heroHeight: CGFloat { min(720, max(480, (size.height + topInset) * 0.78)) }

    var body: some View {
        ScrollView {
            HomeColumn(content: content, games: games, focused: focused, focusID: $focusID, palettes: palettes, actions: actions,
                       topInset: topInset, heroHeight: heroHeight)
        }
        .ignoresSafeArea(edges: .top)
        .onScrollGeometryChange(for: Bool.self) { [heroHeight] in $0.contentOffset.y > heroHeight * 0.35 } action: { _, past in
            scrolledPastArt = past
        }
        // Over the art, no scroll-edge fade under the bar (it washed the art with the ground),
        // and the status bar reads in the art's tone: light text over a dark top.
        .modifier(HomeBar(overArt: focused != nil && !scrolledPastArt, artTop: artTop))
        .task(id: focused?.artworkURL) { artTop = await artTopTone(focused?.artworkURL) }
        .themedPage(.room, artUnderBar: true)
        .accessibilityIdentifier("play.continue")
        // Every game in play is ready to be the hero: its art at the hero's size, its
        // light and its blurred cover, so a tap swaps without a placeholder or a dip.
        .task(id: games.map(\.id)) { await prepare(games.compactMap(\.artworkURL)) }
    }

    /// `.dark` when the top of the art is dark (the status bar then reads in light text).
    /// The poster fills a box as wide as the screen and 1.5 times as tall (cropped about its
    /// centre), from the top; the bar covers its first `topInset` points.
    private func artTopTone(_ url: URL?) async -> ColorScheme? {
        let inset = topInset / max(1, size.width)
        return await ArtTone.scheme(of: url, pipeline: pipeline, maxPixelSize: Int(HeroPoster.maxPointSize * displayScale)) { image in
            GameArtHero.statusBarBand(image: image, hero: CGSize(width: 1, height: 1.5), topInset: inset)
        }
    }

    private func prepare(_ urls: [URL]) async {
        let dark = theme.isDark
        for url in urls {
            guard !Task.isCancelled else { return }
            _ = await pipeline.image(for: url, maxPixelSize: Int(HeroPoster.maxPointSize * displayScale))
            _ = await pipeline.palette(for: url)
            _ = await pipeline.backdrop(for: url, dark: dark)
        }
    }
}

/// The bar over the hero's art: no scroll-edge fade, the status bar in the art's tone.
private struct HomeBar: ViewModifier {
    let overArt: Bool
    let artTop: ColorScheme?

    func body(content: Content) -> some View {
        content
            .modifier(TopEdgeEffectHidden(hidden: overArt))
            .statusBarText(over: overArt ? artTop : nil)
    }
}

/// The page's column: the hero and its actions, then the sections.
private struct HomeColumn: View {
    @Environment(\.rommioTheme) private var theme
    let content: PlayContent
    let games: [PlayGameItem]
    let focused: PlayGameItem?
    @Binding var focusID: Int?
    let palettes: [URL: CoverPalette]
    let actions: PlayActions
    let topInset: CGFloat
    let heroHeight: CGFloat

    var body: some View {
        let gutter = theme.spacing.gutter
        VStack(alignment: .leading, spacing: 0) {
            if let focused {
                HomeHeroBlock(game: focused, actions: actions, topInset: topInset, heroHeight: heroHeight)
                VStack(alignment: .leading, spacing: 16) {
                    AuroraPlayActionRow(game: focused, actions: actions)
                    SyncStatusLine(text: content.statusLine, offline: content.isOffline)
                }
                .padding(.horizontal, gutter)
                .padding(.top, 6)
            } else if !content.isLoading {
                // (While the games load, nothing: the line would sit at the top, then drop
                // below the hero a moment later.)
                SyncStatusLine(text: content.statusLine, offline: content.isOffline)
                    .padding(.horizontal, gutter)
                    .padding(.top, topInset + 8)
            }
            HomeSections(content: content, games: games, focusedID: focused?.id, focusID: $focusID, palettes: palettes, actions: actions)
                .padding(.top, theme.spacing.section + 4)
        }
        .padding(.bottom, 40)
        .animation(.smooth(duration: 0.35), value: focused?.id)
    }
}

/// The hero at a fixed height: the art dissolving between games, the caption anchored to
/// its foot and growing upward into the art.
private struct HomeHeroBlock: View {
    @Environment(\.rommioTheme) private var theme
    let game: PlayGameItem
    let actions: PlayActions
    let topInset: CGFloat
    let heroHeight: CGFloat
    /// The caption's height: the art fades out by its top.
    @State private var captionHeight: CGFloat = 150

    /// The caption's inset from the hero's bottom edge.
    private static let captionInset: CGFloat = 14

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            // The next game's art dissolves over the last one's.
            LayeredCrossfade(value: game) { game in
                HomeHero(game: game, topInset: topInset, clearAt: max(160, heroHeight - Self.captionInset - captionHeight + 8))
            }
            // Pulled down past the top, the art grows up from its foot to fill the gap
            // instead of leaving the page showing above it.
            .visualEffect { content, geometry in
                let pull = max(0, geometry.frame(in: .scrollView(axis: .vertical)).minY)
                return content.scaleEffect(1 + pull / max(1, geometry.size.height), anchor: .bottom)
            }
            // One layer, so a caption on its way out never stacks with the new one.
            ZStack(alignment: .bottomLeading) {
                HomeCaption(game: game, actions: actions)
                    .id("caption.\(game.id)")
                    // The old caption is gone in a frame and the new one fades in: two
                    // titles never show at once.
                    .transition(.asymmetric(insertion: .opacity.animation(.easeOut(duration: 0.3)),
                                            removal: .opacity.animation(.linear(duration: 0.01))))
            }
            .padding(.horizontal, theme.spacing.gutter)
            .padding(.bottom, Self.captionInset)
            .onGeometryChange(for: CGFloat.self) { $0.size.height - Self.captionInset } action: { height in
                withAnimation(.smooth(duration: 0.35)) { captionHeight = height }
            }
        }
        .frame(height: heroHeight)
    }
}

/// In play, the picks, recent saves and notices.
private struct HomeSections: View {
    @Environment(\.rommioTheme) private var theme
    let content: PlayContent
    let games: [PlayGameItem]
    let focusedID: Int?
    @Binding var focusID: Int?
    let palettes: [URL: CoverPalette]
    let actions: PlayActions

    var body: some View {
        let gutter = theme.spacing.gutter
        VStack(alignment: .leading, spacing: theme.spacing.section) {
            if games.count > 1 {
                HomeInPlayRow(games: games, focusedID: focusedID, focusID: $focusID, actions: actions)
            }
            if !content.picks.isEmpty {
                PlayPicksRow(picks: content.picks, actions: actions)
            }
            AuroraRecentSaves(days: content.recentSaveDays, count: content.recentSaves.count, palettes: palettes, actions: actions)
                .padding(.horizontal, gutter)
            AuroraPlayNotices(staleMessage: content.staleMessage, errorMessage: content.errorMessage,
                              showsEmpty: content.isEmpty && !content.isLoading)
                .padding(.horizontal, gutter)
        }
    }
}

/// The hero: the game's cover art from the top down (a screenshot from RomM when it has no
/// cover), filling its box and dissolving into the page's light by `clearAt`, the top of
/// the caption over it.
private struct HomeHero: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.pageLight) private var light
    let game: PlayGameItem
    let topInset: CGFloat
    /// Points from the top where the art has faded out completely.
    let clearAt: CGFloat

    var body: some View {
        Color.clear
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .overlay(alignment: .top) {
                // The game's artwork; saved screens stay in Recent saves (revised 2026-09-27).
                if game.artworkURL == nil, let screenshot = game.screenshotURL {
                    HeroScreen(url: screenshot, topInset: topInset) {
                        HeroPoster(url: nil, title: game.rom.displayName)
                    }
                } else {
                    HeroPoster(url: game.artworkURL, title: game.rom.displayName)
                }
            }
            .clipped()
            // The art is gone by the caption, so a cover's own logo never sits behind the title.
            .mask(alignment: .top) {
                LinearGradient(stops: [
                    .init(color: .black, location: 0),
                    .init(color: .black, location: 0.5),
                    .init(color: .black.opacity(0.35), location: 0.76),
                    .init(color: .black.opacity(0.06), location: 0.91),
                    .init(color: .clear, location: 1),
                ], startPoint: .top, endPoint: .bottom)
                .frame(height: clearAt)
            }
            .contentShape(Rectangle())
            .accessibilityElement()
            .accessibilityLabel("Cover art for \(game.rom.displayName)")
            .accessibilityIdentifier("play.screen.\(game.rom.id)")
    }
}

/// RomM's screenshot of a game without cover art, at its own aspect, as large as the hero
/// allows (edge to edge when it fits the width), over a blurred copy of itself that lights
/// the top of the page. Until it loads, and if it can't, the fallback shows.
private struct HeroScreen<Fallback: View>: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.artworkPipeline) private var pipeline
    let url: URL
    let topInset: CGFloat
    @ViewBuilder var fallback: () -> Fallback
    @State private var image: CGImage?

    var body: some View {
        GeometryReader { proxy in
            let width = proxy.size.width, height = proxy.size.height
            ZStack(alignment: .top) {
                if image == nil {
                    fallback()
                } else {
                    Color.black.opacity(theme.isDark ? 0.35 : 0.12)
                }
                if let image {
                    Image(decorative: image, scale: 1)
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                        .frame(width: width, height: height)
                        .blur(radius: 42)
                        .saturation(1.35)
                        .opacity(theme.isDark ? 0.9 : 0.75)
                        .clipped()
                    // The screen sits in the space between the bars and the caption, centred.
                    let box = CGSize(width: width, height: max(160, height - topInset - 150))
                    let aspect = CGFloat(image.width) / CGFloat(max(1, image.height))
                    let size = aspect >= box.width / box.height
                        ? CGSize(width: box.width, height: box.width / aspect)
                        : CGSize(width: box.height * aspect, height: box.height)
                    let edge = size.width >= width - 1
                    // Pixel art enlarged stays crisp; a large screenshot shrinks smoothly.
                    let enlarged = CGFloat(image.width) < size.width * 2
                    Image(decorative: image, scale: 1)
                        .resizable()
                        .interpolation(enlarged ? .none : .high)
                        .frame(width: size.width, height: size.height)
                        .clipShape(RoundedRectangle(cornerRadius: edge ? 0 : 16, style: .continuous))
                        .shadow(color: .black.opacity(theme.isDark ? 0.45 : 0.25), radius: 24, y: 12)
                        .padding(.top, topInset + 4 + max(0, (box.height - size.height) / 2))
                }
            }
            .frame(width: width, height: height)
        }
        .task(id: url) {
            let loaded = await pipeline.image(for: url, maxPixelSize: 1280)?.cgImage
            // Letterboxing the game drew itself (cutscenes, widescreen modes) is trimmed.
            let trimmed = await Task.detached(priority: .userInitiated) { loaded.map(ScreenTrim.trimmed) }.value
            if !Task.isCancelled { withAnimation(.easeOut(duration: 0.25)) { image = trimmed } }
        }
    }
}


/// How light the top of a picture is, where the status bar sits over it.
enum ArtTone {
    /// The status bar's scheme over a picture: `.dark` (light text) when the band of rows
    /// under the bar (fractions of its height, from the top) is dark on average.
    static func scheme(of url: URL?, pipeline: ArtworkPipeline, maxPixelSize: Int,
                       band: @escaping @Sendable (CGImage) -> ClosedRange<CGFloat>) async -> ColorScheme? {
        guard let url, let image = await pipeline.image(for: url, maxPixelSize: maxPixelSize)?.cgImage else { return nil }
        return await Task.detached(priority: .userInitiated) { isDark(image, band: band(image)) }.value ? .dark : .light
    }

    /// Whether rows `band` (fractions of the height, from the top) are dark on average
    /// (relative luminance < 0.5).
    static func isDark(_ image: CGImage, band: ClosedRange<CGFloat>) -> Bool {
        let from = Int(CGFloat(image.height) * min(max(band.lowerBound, 0), 1))
        let rows = max(1, Int(CGFloat(image.height) * min(band.upperBound, 1)) - from)
        guard let top = image.cropping(to: CGRect(x: 0, y: from, width: image.width, height: rows)),
              let context = CGContext(data: nil, width: 16, height: 4, bitsPerComponent: 8, bytesPerRow: 64,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
        else { return true }
        context.interpolationQuality = .medium
        context.draw(top, in: CGRect(x: 0, y: 0, width: 16, height: 4))
        guard let data = context.data else { return true }
        let bytes = data.assumingMemoryBound(to: UInt8.self)
        var sum = 0.0
        for i in 0..<64 {
            sum += 0.2126 * Double(bytes[i * 4]) / 255 + 0.7152 * Double(bytes[i * 4 + 1]) / 255 + 0.0722 * Double(bytes[i * 4 + 2]) / 255
        }
        return sum / 64 < 0.5
    }
}

/// Uniform black borders around a captured screen (a game's own letterbox or pillarbox).
enum ScreenTrim {
    /// The image without rows and columns at its edges that are all but black; unchanged
    /// when there are none, or when trimming would leave less than 40% of either side.
    static func trimmed(_ image: CGImage) -> CGImage {
        let width = image.width, height = image.height
        guard width > 8, height > 8, width * height <= 4_000_000,
              let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return image }
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        guard let data = context.data?.assumingMemoryBound(to: UInt8.self) else { return image }
        func dark(_ x: Int, _ y: Int) -> Bool {
            let i = (y * width + x) * 4
            return max(data[i], data[i + 1], data[i + 2]) < 20
        }
        // A line is border when at least 98% of its pixels are near black.
        func rowIsBorder(_ y: Int) -> Bool { (0..<width).filter { dark($0, y) }.count * 50 >= width * 49 }
        func columnIsBorder(_ x: Int, _ top: Int, _ bottom: Int) -> Bool {
            (top..<bottom).filter { dark(x, $0) }.count * 50 >= (bottom - top) * 49
        }
        var top = 0, bottom = height
        while top < bottom - 1, rowIsBorder(top) { top += 1 }
        while bottom - 1 > top, rowIsBorder(bottom - 1) { bottom -= 1 }
        var left = 0, right = width
        while left < right - 1, columnIsBorder(left, top, bottom) { left += 1 }
        while right - 1 > left, columnIsBorder(right - 1, top, bottom) { right -= 1 }
        let keptWidth = right - left, keptHeight = bottom - top
        guard keptWidth < width || keptHeight < height, keptWidth * 5 >= width * 2, keptHeight * 5 >= height * 2 else { return image }
        // The bitmap's first row is the image's top row, as cropping counts it.
        return image.cropping(to: CGRect(x: left, y: top, width: keptWidth, height: keptHeight)) ?? image
    }
}

/// Cover art from the top down (covers are usually 2:3 posters, taller than the hero).
private struct HeroPoster: View {
    static let maxPointSize: CGFloat = 900
    let url: URL?
    let title: String

    var body: some View {
        GeometryReader { proxy in
            TreatedCover(url: url, maxPointSize: Self.maxPointSize) { CoverPlaceholder(title: title) }
                .frame(width: proxy.size.width, height: proxy.size.width * 1.5)
                .frame(width: proxy.size.width, height: proxy.size.height, alignment: .top)
                .clipped()
        }
    }
}

/// Platform · year, the game, where it continues from, and its sync state or conflict.
private struct HomeCaption: View {
    @Environment(\.rommioTheme) private var theme
    let game: PlayGameItem
    let actions: PlayActions

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(game.rom.platformAndYear).themeText(.section).foregroundStyle(theme.inkSecondary).lineLimit(1)
            DisplayText(text: game.rom.displayName, role: .title, size: 34, lineLimit: 2)
            Text(game.captionLine).themeText(.secondary).foregroundStyle(theme.inkSecondary).lineLimit(2)
            HomeSyncState(game: game, onConflict: actions.chooseConflict)
                .padding(.top, 4)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .contain)
    }
}

/// The hero's sync state on the page (§11 wording), or its conflict (opens the Conflict sheet).
private struct HomeSyncState: View {
    let game: PlayGameItem
    let onConflict: (ConflictItem) -> Void

    var body: some View {
        if let conflict = game.conflict {
            Button { onConflict(conflict) } label: {
                StatusChip(title: conflict.chipTitle, tone: .bad, systemImage: "exclamationmark.triangle.fill")
            }
            .buttonStyle(.plain)
            .accessibilityLabel(conflict.prompt)
            .accessibilityIdentifier("play.conflict.\(game.rom.id)")
        } else if let badge = game.latest?.sync.badge {
            SyncBadge(state: badge)
        }
    }
}

/// Every game in progress as a cover; the one at the top is ringed in its light.
private struct HomeInPlayRow: View {
    @Environment(\.rommioTheme) private var theme
    let games: [PlayGameItem]
    let focusedID: Int?
    @Binding var focusID: Int?
    let actions: PlayActions

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: "In play", detail: "\(games.count)")
                .padding(.horizontal, theme.spacing.gutter)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 14) {
                    ForEach(games) { game in
                        Button {
                            withAnimation(.smooth(duration: 0.35)) { focusID = game.id }
                        } label: {
                            HomeInPlayCover(game: game, selected: game.id == focusedID)
                        }
                        .buttonStyle(.plain)
                        .contextMenu { PlayGameMenu(game: game, actions: actions) }
                        .accessibilityLabel("\(game.rom.displayName), \(game.captionLine)")
                        .accessibilityAddTraits(game.id == focusedID ? .isSelected : [])
                        .accessibilityIdentifier("play.inProgress.\(game.rom.id)")
                    }
                }
                .padding(.vertical, 6)
            }
            .contentMargins(.horizontal, theme.spacing.gutter, for: .scrollContent)
            .scrollClipDisabled()
        }
    }
}

private struct HomeInPlayCover: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.pageLight) private var light
    let game: PlayGameItem
    let selected: Bool

    private static let width: CGFloat = 92

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 12, style: .continuous)
        VStack(alignment: .leading, spacing: 7) {
            Color.clear
                .frame(width: Self.width, height: Self.width * 1.5)
                .overlay { GameCover(url: game.artworkURL, title: game.rom.displayName, cornerRadius: 12, maxPointSize: 200) }
                .overlay {
                    if selected { shape.strokeBorder(light.mark1, lineWidth: 2.5).shadow(color: light.mark1.opacity(0.65), radius: 10) }
                }
                .overlay(alignment: .topTrailing) {
                    if game.conflict != nil {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundStyle(DarkFrame.bad)
                            .frame(width: 24, height: 24)
                            .background(Circle().fill(DarkFrame.glass.opacity(0.8)))
                            .padding(5)
                    }
                }
            VStack(alignment: .leading, spacing: 2) {
                Text(game.rom.displayName)
                    .themeText(.caption, size: 12.5)
                    .foregroundStyle(selected ? theme.ink : theme.inkSecondary)
                    .lineLimit(2, reservesSpace: true)
                if let played = game.lastPlayedAt ?? game.latest?.capturedAt {
                    Text(PlayFormat.inline(played)).themeText(.caption, size: 11).foregroundStyle(theme.inkTertiary).lineLimit(1)
                }
            }
            .frame(width: Self.width, alignment: .leading)
        }
        .scaleEffect(selected ? 1 : 0.97)
    }
}

/// Keep going · Something new · Quick hit: a card each, with its reason.
private struct PlayPicksRow: View {
    @Environment(\.rommioTheme) private var theme
    let picks: [PlayPick]
    let actions: PlayActions

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: "Pick something", detail: nil)
                .padding(.horizontal, theme.spacing.gutter)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 12) {
                    ForEach(picks) { pick in
                        PlayPickCard(pick: pick, actions: actions)
                    }
                }
                .padding(.vertical, 6)
                .padding(.bottom, 8)
            }
            .contentMargins(.horizontal, theme.spacing.gutter, for: .scrollContent)
            .scrollClipDisabled()
        }
    }
}

private struct PlayPickCard: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.pageLight) private var light
    let pick: PlayPick
    let actions: PlayActions

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Color.clear
                .frame(width: 84, height: 126)
                .overlay { GameCover(url: pick.artworkURL, title: pick.rom.displayName, cornerRadius: 10, maxPointSize: 180) }
            VStack(alignment: .leading, spacing: 5) {
                Label(pick.title, systemImage: pick.systemImage)
                    .themeText(.section)
                    .foregroundStyle(light.readableTint)
                    .lineLimit(1)
                Text(pick.rom.displayName)
                    .themeText(.bodyEmphasis, size: 16)
                    .foregroundStyle(theme.ink)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
                Text(pick.reason)
                    .themeText(.caption, size: 12.5)
                    .foregroundStyle(theme.inkSecondary)
                    .lineLimit(3)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 6)
                Button {
                    if pick.installed || pick.kind == .keepGoing { actions.resume(pick.rom) } else { actions.open(pick.rom) }
                } label: {
                    Label(actionTitle, systemImage: pick.installed || pick.kind == .keepGoing ? "play.fill" : "arrow.down.circle")
                        .themeText(.bodyEmphasis, size: 14)
                        .foregroundStyle(theme.buttonInk)
                        .padding(.horizontal, 14)
                        .frame(height: 34)
                        .background(Capsule().fill(theme.buttonFill))
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("play.pick.\(pick.kind.rawValue).action")
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(12)
        .frame(width: 300, alignment: .topLeading)
        .frame(minHeight: 150, alignment: .topLeading)
        .themeSurface(radius: 22)
        .contentShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .onTapGesture { actions.open(pick.rom) }
        .accessibilityElement(children: .combine)
        .accessibilityAction(named: "Game details") { actions.open(pick.rom) }
        .accessibilityIdentifier("play.pick.\(pick.kind.rawValue)")
    }

    private var actionTitle: String {
        if pick.kind == .keepGoing { return "Resume" }
        return pick.installed ? "Play" : "Get it"
    }
}

/// Page dots in each game's light.
private struct LitPagerDots: View {
    @Environment(\.pageLight) private var light
    let marks: [CoverPalette?]
    let current: Int
    let onSelect: (Int) -> Void

    var body: some View {
        PagerDots(colours: marks.map { light.mark(for: $0) }, current: current, onSelect: onSelect)
    }
}

/// A game's screen: its latest resume point (or save state) as captured, at its own
/// aspect on a black 4:3 screen with its frame glowing behind it; without one, the
/// cover centred on its blurred backdrop. Never art-treated. The sync state (or the
/// conflict) sits bottom-left in a dark glass chip.
struct PlayScreen: View {
    @Environment(\.rommioTheme) private var theme
    let game: PlayGameItem
    let width: CGFloat
    let glow: Color
    /// A conflicted game's chip opens the Conflict sheet.
    var onConflict: ((ConflictItem) -> Void)? = nil

    var body: some View {
        let height = width * 3 / 4
        let shape = RoundedRectangle(cornerRadius: 22, style: .continuous)
        ZStack {
            // The frame, blurred, glowing in the game's light.
            ScreenPicture(game: game, height: height)
                .frame(width: width * 0.9, height: height * 0.9)
                .blur(radius: 26)
                .opacity(theme.isDark ? 0.7 : 0.45)
                .offset(y: height * 0.08)
                .accessibilityHidden(true)
            ScreenPicture(game: game, height: height)
                .frame(width: width, height: height)
                .background(Color.black)
                .clipShape(shape)
                .overlay { shape.strokeBorder(Color.white.opacity(0.22), lineWidth: 0.5) }
                .overlay(alignment: .bottomLeading) { ScreenChip(game: game, onConflict: onConflict).padding(12) }
                .shadow(color: glow.opacity(theme.isDark ? 0.55 : 0.5), radius: 22, y: 14)
        }
        .frame(width: width, height: height)
        .contentShape(shape)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("\(game.rom.displayName), \(game.captionLine)")
        .accessibilityIdentifier("play.screen.\(game.rom.id)")
    }
}

private struct ScreenPicture: View {
    let game: PlayGameItem
    let height: CGFloat

    var body: some View {
        if let preview = game.previewURL {
            ZStack {
                Color.black
                ScreenFrame(url: preview)
            }
        } else {
            // No preview: the cover on its blurred backdrop.
            ZStack {
                Color.black
                if let url = game.artworkURL {
                    BlurredCover(url: url, dark: true)
                        .scaledToFill()
                        .opacity(0.9)
                }
                GameCover(url: game.artworkURL, title: game.rom.displayName, cornerRadius: 10, maxPointSize: height, shadow: true)
                    .frame(width: height * 0.72 * 0.75, height: height * 0.72)
            }
            .clipped()
        }
    }
}

/// A preview frame at its own aspect (pixel art stays crisp).
private struct ScreenFrame: View {
    let url: URL
    @State private var image: CGImage?

    var body: some View {
        ZStack {
            if let image {
                Image(decorative: image, scale: 1)
                    .resizable()
                    .interpolation(.none)
                    .aspectRatio(contentMode: .fit)
                    .transition(.opacity)
            }
        }
        .task(id: url) {
            let loaded = await SavePreviewCache.shared.image(url)
            if !Task.isCancelled { withAnimation(.easeOut(duration: 0.15)) { image = loaded } }
        }
    }
}

/// The screen's sync state in dark glass (§11 wording), or the conflict in `bad`.
private struct ScreenChip: View {
    let game: PlayGameItem
    let onConflict: ((ConflictItem) -> Void)?

    var body: some View {
        if let conflict = game.conflict {
            // "Two resume points": the way to the Conflict sheet (and Resume asks first).
            Button { onConflict?(conflict) } label: {
                chip(icon: "exclamationmark.triangle.fill", text: conflict.chipTitle, tone: DarkFrame.bad)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(conflict.prompt)
            .accessibilityIdentifier("play.conflict.\(game.rom.id)")
        } else if let latest = game.latest, latest.sync != .unknown {
            chip(icon: nil, text: latest.sync.label, tone: DarkFrame.tone(latest.sync))
        }
    }

    private func chip(icon: String?, text: String, tone: Color) -> some View {
        HStack(spacing: 6) {
            if let icon {
                Image(systemName: icon).font(.system(size: 11, weight: .bold))
            } else {
                Circle().frame(width: 7, height: 7)
            }
            Text(text).font(.system(size: 13, weight: .semibold)).lineLimit(1)
        }
        .foregroundStyle(tone)
        .padding(.horizontal, 11)
        .frame(height: 28)
        .background(Capsule().fill(DarkFrame.glass.opacity(0.62)))
        .background(Capsule().fill(.ultraThinMaterial).environment(\.colorScheme, .dark))
    }
}

private struct AuroraPlayCaption: View {
    @Environment(\.rommioTheme) private var theme
    let game: PlayGameItem
    let large: Bool
    let actions: PlayActions

    var body: some View {
        VStack(alignment: .leading, spacing: large ? 10 : 6) {
            Text(game.rom.platformAndYear).themeText(.section).foregroundStyle(theme.inkSecondary).lineLimit(1)
            DisplayText(text: game.rom.displayName, role: .title, size: large ? 48 : 32, lineLimit: 2)
            Text(game.captionLine)
                .themeText(.secondary)
                .foregroundStyle(theme.inkSecondary)
                .lineLimit(2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .contain)
    }
}

private struct AuroraPlayActionRow: View {
    let game: PlayGameItem
    let actions: PlayActions

    var body: some View {
        HStack(spacing: 12) {
            Button { actions.resume(game.rom) } label: {
                Label("Resume", systemImage: "play.fill")
            }
            .buttonStyle(PrimaryButtonStyle())
            .accessibilityLabel("Resume \(game.rom.displayName)")
            .accessibilityIdentifier("play.resume.\(game.rom.id)")

            Button { actions.open(game.rom) } label: {
                Image(systemName: "info.circle").font(.system(size: 20, weight: .regular))
            }
            .buttonStyle(IconButtonStyle(size: 54))
            .accessibilityLabel("\(game.rom.displayName) details")
            .accessibilityIdentifier("play.details.\(game.rom.id)")
        }
    }
}

// MARK: Regular: one canvas

private struct AuroraPlayCanvas: View {
    @Environment(\.pageLight) private var light
    let games: [PlayGameItem]
    let focused: PlayGameItem?
    let statusLine: String
    let offline: Bool
    let marks: [CoverPalette?]
    let palettes: [URL: CoverPalette]
    @Binding var focusID: Int?
    let actions: PlayActions

    var body: some View {
        VStack(alignment: .leading, spacing: 26) {
            HStack {
                SyncStatusLine(text: statusLine, offline: offline)
                Spacer()
                if games.count > 1 {
                    LitPagerDots(marks: marks, current: games.firstIndex { $0.id == focused?.id } ?? 0) { index in
                        withAnimation(.smooth) { focusID = games[index].id }
                    }
                }
            }
            if let game = focused {
                AuroraPlayFeature(game: game, glow: light.mark(for: game.artworkURL, in: palettes), actions: actions)
            }
            if games.count > 1 {
                AuroraPlayRoomRow(games: games, focusedID: focused?.id, focusID: $focusID, actions: actions)
            }
        }
        .padding(.horizontal, 32)
        .padding(.top, 4)
    }
}

/// The game in focus across the canvas: its screen beside its caption and actions.
private struct AuroraPlayFeature: View {
    let game: PlayGameItem
    let glow: Color
    let actions: PlayActions
    @State private var canvasWidth: CGFloat = 1100

    var body: some View {
        // The screen takes what the caption (up to 380) leaves, 320–600 wide.
        let screenWidth = min(600, max(320, canvasWidth - 44 - 340))
        HStack(alignment: .center, spacing: 44) {
            PlayScreen(game: game, width: screenWidth, glow: glow) { conflict in actions.chooseConflict(conflict) }
                .onTapGesture { actions.open(game.rom) }
                .contextMenu { PlayGameMenu(game: game, actions: actions) }
                .id(game.id)
                .transition(.opacity)
            VStack(alignment: .leading, spacing: 22) {
                AuroraPlayCaption(game: game, large: true, actions: actions)
                AuroraPlayActionRow(game: game, actions: actions)
            }
            .frame(maxWidth: 380, alignment: .leading)
        }
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { canvasWidth = $0 }
        .animation(.smooth, value: game.id)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("play.continue")
    }
}

/// The games in progress as small lit cards: tap focuses one, press and hold for its menu.
private struct AuroraPlayRoomRow: View {
    let games: [PlayGameItem]
    let focusedID: Int?
    @Binding var focusID: Int?
    let actions: PlayActions

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: "Continue playing", detail: PlayFormat.games(games.count))
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 14) {
                    ForEach(games) { game in
                        Button { withAnimation(.smooth) { focusID = game.id } } label: {
                            RoomCard(item: game, width: 176, selected: game.id == focusedID)
                        }
                        .buttonStyle(.plain)
                        .contextMenu { PlayGameMenu(game: game, actions: actions) }
                        .accessibilityIdentifier("play.quickResume.\(game.rom.id)")
                    }
                }
                .padding(.vertical, 6)
                .padding(.bottom, 12)
            }
            .scrollClipDisabled()
        }
    }
}

// MARK: Notices and empty

private struct AuroraPlayNotices: View {
    @Environment(\.rommioTheme) private var theme
    let staleMessage: String?
    let errorMessage: String?
    let showsEmpty: Bool

    var body: some View {
        Group {
            if let staleMessage {
                Label {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Showing your saved library").themeText(.bodyEmphasis).foregroundStyle(theme.ink)
                        Text(staleMessage).themeText(.caption).foregroundStyle(theme.inkSecondary).lineLimit(3)
                    }
                } icon: {
                    Image(systemName: "icloud.slash").foregroundStyle(theme.wait)
                }
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .themeSurface()
            }
            if let errorMessage {
                Label(errorMessage, systemImage: "exclamationmark.circle")
                    .themeText(.secondary)
                    .foregroundStyle(theme.badText)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("play.error")
            } else if showsEmpty {
                EmptyStateView(title: "Nothing to continue yet", systemImage: "play.circle",
                               message: "Games you play show up here. Open the Library to start one.")
            }
        }
    }
}
