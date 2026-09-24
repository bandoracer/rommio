import SwiftUI
import RommioContract

/// Stand-in art: the page's light behind the game's initials (Aurora); a
/// halftone proof with the initials in display caps (Riso).
struct CoverPlaceholder: View {
    @Environment(\.rommioTheme) private var theme
    let title: String

    var body: some View {
        ZStack {
            if theme.isRiso {
                theme.surfaceStrong
                HalftoneField(dot: theme.accent2.opacity(theme.isDark ? 0.5 : 0.35), pitch: 6)
                Text(initials)
                    .themeText(.headline)
                    .foregroundStyle(theme.ink.opacity(0.7))
                    .accessibilityHidden(true)
            } else {
                LinearGradient(colors: [theme.accent1.opacity(0.9), theme.accent2.opacity(0.9), theme.accent3.opacity(0.95)],
                               startPoint: .topLeading, endPoint: .bottomTrailing)
                Text(initials)
                    .themeText(.headline)
                    .foregroundStyle(theme.onAccent.opacity(0.7))
                    .accessibilityHidden(true)
            }
        }
    }

    private var initials: String {
        let words = title.split(whereSeparator: { !$0.isLetter && !$0.isNumber }).prefix(2)
        return words.compactMap(\.first).map(String.init).joined().uppercased()
    }
}

/// Cover art with the theme's treatment, clipped to the theme's cover shape:
/// rounded with a soft shadow (Aurora) or a two-ink print with a 1 pt ink
/// keyline (Riso).
struct GameCover: View {
    @Environment(\.rommioTheme) private var theme
    let url: URL?
    let title: String
    var cornerRadius: CGFloat?
    var maxPointSize: CGFloat = 320
    var shadow = true

    var body: some View {
        let radius = theme.isRiso ? 0 : (cornerRadius ?? theme.radius.cover)
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        TreatedCover(url: url, maxPointSize: maxPointSize) {
            CoverPlaceholder(title: title)
        }
        .clipShape(shape)
        .overlay {
            if theme.isRiso {
                shape.strokeBorder(theme.ink, lineWidth: 1)
            } else {
                shape.strokeBorder(theme.ink.opacity(theme.isDark ? 0.14 : 0.1), lineWidth: 0.5)
            }
        }
        .shadow(color: shadow && theme.material.coverShadow ? coverShadow : .clear, radius: 16, y: 12)
        .accessibilityHidden(true)
    }

    private var coverShadow: Color {
        theme.isDark ? .black.opacity(0.55) : Color(red: 40 / 255, green: 20 / 255, blue: 60 / 255).opacity(0.28)
    }
}

/// A game in a shelf or grid: cover, title, detail.
struct GameTile: View {
    @Environment(\.rommioTheme) private var theme
    let rom: RomDTO
    let artworkURL: URL?
    var width: CGFloat = 148
    var detail: String?

    var body: some View {
        VStack(alignment: .leading, spacing: theme.isRiso ? 6 : 8) {
            GameCover(url: artworkURL, title: rom.displayName, maxPointSize: width * 1.4)
                .frame(width: width, height: width * 4 / 3)
            VStack(alignment: .leading, spacing: 2) {
                Text(rom.displayName)
                    .themeText(.bodyEmphasis, size: theme.isRiso ? 13.5 : 14.5)
                    .foregroundStyle(theme.ink)
                    .lineLimit(2, reservesSpace: true)
                Text(detail ?? rom.platformAndYear)
                    .themeText(theme.isRiso ? .label : .caption, size: theme.isRiso ? 9.5 : 12)
                    .foregroundStyle(theme.inkSecondary)
                    .lineLimit(1)
            }
        }
        .frame(width: width, alignment: .leading)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

/// A game in a list: small cover, title, platform and an optional trailing view.
struct GameRow<Trailing: View>: View {
    @Environment(\.rommioTheme) private var theme
    let rom: RomDTO
    let artworkURL: URL?
    var subtitle: String?
    @ViewBuilder var trailing: () -> Trailing

    var body: some View {
        ListRow(title: rom.displayName, subtitle: subtitle ?? rom.platformName) {
            GameCover(url: artworkURL, title: rom.displayName, cornerRadius: 8, maxPointSize: 64, shadow: false)
                .frame(width: 40, height: 52)
        } trailing: {
            trailing()
        }
    }
}

extension GameRow where Trailing == EmptyView {
    init(rom: RomDTO, artworkURL: URL?, subtitle: String? = nil) {
        self.init(rom: rom, artworkURL: artworkURL, subtitle: subtitle, trailing: { EmptyView() })
    }
}

/// A collection with its cover art.
struct CollectionTile: View {
    @Environment(\.rommioTheme) private var theme
    let collection: RommCollectionDTO
    let artworkURL: URL?
    var width: CGFloat = 220

    var body: some View {
        if theme.isRiso {
            VStack(alignment: .leading, spacing: 5) {
                GameCover(url: artworkURL, title: collection.name, maxPointSize: width)
                    .frame(width: width, height: width * 0.62)
                Text("Collection").themeText(.label, size: 9.5).foregroundStyle(theme.inkSecondary)
                Text(collection.name).themeText(.headline, size: 22).foregroundStyle(theme.ink).lineLimit(2)
                Text(PlayFormat.games(collection.romCount)).themeText(.label, size: 9.5).foregroundStyle(theme.inkSecondary)
            }
            .frame(width: width, alignment: .leading)
            .accessibilityElement(children: .combine)
        } else {
            ZStack(alignment: .bottomLeading) {
                ArtworkImage(url: artworkURL, maxPointSize: width) { CoverPlaceholder(title: collection.name) }
                    .frame(width: width, height: width * 0.62)
                LinearGradient(colors: [.clear, .black.opacity(0.7)], startPoint: .center, endPoint: .bottom)
                VStack(alignment: .leading, spacing: 2) {
                    Text(collection.name)
                        .themeText(.bodyEmphasis)
                        .foregroundStyle(.white)
                        .lineLimit(2)
                    Text(PlayFormat.games(collection.romCount))
                        .themeText(.caption)
                        .foregroundStyle(.white.opacity(0.85))
                }
                .padding(12)
            }
            .frame(width: width, height: width * 0.62)
            .clipShape(RoundedRectangle(cornerRadius: theme.radius.card * 0.8, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: theme.radius.card * 0.8, style: .continuous)
                    .strokeBorder(Color.white.opacity(theme.isDark ? 0.1 : 0.4), lineWidth: 0.5)
            }
            .shadow(color: .black.opacity(theme.isDark ? 0.4 : 0.12), radius: 14, y: 8)
            .accessibilityElement(children: .combine)
        }
    }
}

/// A horizontal shelf with a section header.
struct Shelf<Content: View>: View {
    @Environment(\.rommioTheme) private var theme
    let title: String
    var detail: String?
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: title, detail: detail)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: theme.isRiso ? 12 : 14) {
                    content()
                }
                .padding(.vertical, 4)
                .padding(.bottom, theme.isGlass ? 14 : 0)
            }
            .scrollClipDisabled()
        }
    }
}
