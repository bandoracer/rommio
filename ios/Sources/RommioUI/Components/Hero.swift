import SwiftUI
import RommioContract

/// A cover presented as a lit object: its own blurred copy glows beneath it and a
/// faint reflection falls below (Aurora, studio `.a-gs`). Riso prints the cover
/// flat with an ink keyline and no glow.
struct GlowingCover: View {
    @Environment(\.rommioTheme) private var theme
    let url: URL?
    let title: String
    var height: CGFloat = 280
    var reflection = true

    var body: some View {
        let width = height * 3 / 4
        let radius = theme.isRiso ? 0 : min(22, height * 0.07)
        ZStack(alignment: .top) {
            if theme.isGlass, let url {
                // The glow: the art itself, blurred and saturated, spilling below. Baked once
                // by the artwork pipeline (a live `.blur` is redrawn on every frame it moves).
                BlurredCover(url: url, dark: theme.isDark)
                    .frame(width: width * 0.86, height: height * 0.92)
                    .opacity(theme.isDark ? 0.85 : 0.6)
                    .offset(y: height * 0.14)
                    .accessibilityHidden(true)
            }
            GameCover(url: url, title: title, cornerRadius: radius, maxPointSize: height * 1.2, shadow: false)
                .frame(width: width, height: height)
                .overlay {
                    if theme.isGlass {
                        RoundedRectangle(cornerRadius: radius, style: .continuous)
                            .fill(LinearGradient(colors: [Color.white.opacity(0.16), .clear], startPoint: .topLeading, endPoint: UnitPoint(x: 0.4, y: 0.3)))
                            .allowsHitTesting(false)
                    }
                }
                .shadow(color: theme.isGlass ? (theme.isDark ? Color.black.opacity(0.6) : Color(red: 0.16, green: 0.08, blue: 0.24).opacity(0.35)) : .clear,
                        radius: 22, y: 18)
            if theme.isGlass && reflection {
                GameCover(url: url, title: title, cornerRadius: radius, maxPointSize: height, shadow: false)
                    .frame(width: width, height: height)
                    .scaleEffect(x: 1, y: -1)
                    .mask(LinearGradient(stops: [.init(color: .clear, location: 0.8), .init(color: .white.opacity(0.14), location: 1)],
                                         startPoint: .bottom, endPoint: .top))
                    .offset(y: height + 5)
                    .accessibilityHidden(true)
            }
        }
        .frame(width: width, height: height, alignment: .top)
        .accessibilityElement()
        .accessibilityLabel("Cover art for \(title)")
    }
}

/// Another game in progress as a small lit card: the cover under a dark scrim
/// with its caption (Aurora, studio `.a-rc`).
struct RoomCard: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.pageLight) private var light
    let item: PlayGameItem
    var width: CGFloat = 168
    /// Defaults to 0.76 of the width.
    var height: CGFloat? = nil
    var selected = false
    var showsPlatform = true

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 18, style: .continuous)
        let height = self.height ?? width * 0.76
        ZStack(alignment: .bottomLeading) {
            // The game's own screen when there is one (never treated), else its cover.
            Group {
                if let preview = item.previewURL {
                    SavePreviewImage(url: preview, coverURL: item.artworkURL, title: item.rom.displayName, contentMode: .fill)
                } else {
                    ArtworkImage(url: item.artworkURL, maxPointSize: width * 1.2) { CoverPlaceholder(title: item.rom.displayName) }
                }
            }
            .frame(width: width, height: height)
            .clipped()
            LinearGradient(stops: [.init(color: .clear, location: 0.3), .init(color: Color.black.opacity(0.78), location: 1)],
                           startPoint: .top, endPoint: .bottom)
            VStack(alignment: .leading, spacing: 2) {
                if showsPlatform {
                    Text(item.rom.platformSlug.uppercased())
                        .themeText(.section, size: 10)
                        .foregroundStyle(.white.opacity(0.72))
                }
                Text(item.rom.displayName)
                    .themeText(.bodyEmphasis, size: 15)
                    .foregroundStyle(.white)
                    .lineLimit(1)
                if let last = item.lastPlayedAt {
                    Text(PlayFormat.relative(last))
                        .themeText(.caption, size: 11.5)
                        .foregroundStyle(.white.opacity(0.76))
                        .lineLimit(1)
                }
            }
            .padding(12)
        }
        .frame(width: width, height: height)
        .clipShape(shape)
        .overlay { shape.strokeBorder(selected ? light.mark1 : Color.white.opacity(0.14), lineWidth: selected ? 2 : 0.5) }
        .shadow(color: .black.opacity(theme.isDark ? 0.45 : 0.16), radius: 14, y: 10)
        .accessibilityElement(children: .combine)
    }
}

/// Page dots coloured by each game's light; the current one stretches.
struct PagerDots: View {
    let colours: [Color]
    let current: Int
    var onSelect: ((Int) -> Void)?

    var body: some View {
        HStack(spacing: 5) {
            ForEach(Array(colours.enumerated()), id: \.offset) { index, colour in
                Capsule()
                    .fill(colour.opacity(index == current ? 1 : 0.75))
                    .frame(width: index == current ? 18 : 6, height: 6)
                    .frame(minWidth: 24, minHeight: 44)
                    .contentShape(Rectangle())
                    .onTapGesture { onSelect?(index) }
            }
        }
        .animation(.snappy, value: current)
        .accessibilityElement()
        .accessibilityLabel("Game \(current + 1) of \(colours.count)")
    }
}

/// A strip of light made of the games played on a day (Aurora day headers).
struct LightStrip: View {
    let colours: [Color]

    var body: some View {
        Capsule()
            .fill(LinearGradient(colors: colours.isEmpty ? [.clear] : (colours.count == 1 ? [colours[0], colours[0]] : colours),
                                 startPoint: .leading, endPoint: .trailing))
            .frame(height: 3)
            .accessibilityHidden(true)
    }
}

/// Connection and sync in one quiet line.
struct SyncStatusLine: View {
    @Environment(\.rommioTheme) private var theme
    let text: String
    let offline: Bool

    var body: some View {
        HStack(spacing: 7) {
            Circle().fill(offline ? theme.wait : theme.ok).frame(width: 7, height: 7)
            Text(text)
                .themeText(.caption, size: 12.5)
                .foregroundStyle(theme.inkSecondary)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("play.syncLine")
    }
}
