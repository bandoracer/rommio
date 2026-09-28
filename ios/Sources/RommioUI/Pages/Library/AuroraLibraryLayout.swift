import SwiftUI
import RommioContract
import RommioFoundation

/// Aurora Library: every game on one wall of covers (docs/theme-layouts.md, Library).
/// One choice orders it (Console · Series · Year · Genre · A–Z). The covers flow
/// continuously; each group starts with a tile that names it (a console's tile opens its
/// page), so a library of small groups stays dense. An index rail jumps between groups,
/// density switches between three and four across, and a press and hold gives a quick
/// look. Lit by the game you're playing; collections close the wall.
struct AuroraLibraryLayout: View {
    @Environment(\.rommioTheme) private var theme
    let content: LibraryContent
    let actions: LibraryActions

    @AppStorage("aurora.library.lens") private var lens: LibraryLens = .console
    @AppStorage("aurora.library.columns") private var columns = 3
    @State private var memo = LibraryWallMemo()

    private static let top = "library.top"

    var body: some View {
        // Grouped again only when the content or the lens changes.
        let sections = memo.sections(content: content, lens: lens)
        let rail = sections.count >= 4
        let gutter = theme.spacing.gutter
        let spacing: CGFloat = columns == 3 ? 12 : 9
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if content.isEmpty {
                        EmptyStateView(title: content.isLoading ? "Loading your library" : "No platforms yet", systemImage: "square.stack.3d.up.slash",
                                       message: content.isLoading ? nil : content.errorMessage ?? "Pull to refresh once your server is reachable.")
                            .padding(.horizontal, gutter)
                    } else {
                        VStack(alignment: .leading, spacing: 14) {
                            Text(content.summary).themeText(.secondary).foregroundStyle(theme.inkSecondary)
                                .padding(.horizontal, gutter)
                            LibraryLensBar(lens: $lens, columns: $columns)
                        }
                        .padding(.bottom, 18)
                        .id(Self.top)
                        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: spacing, alignment: .top), count: columns),
                                  alignment: .leading, spacing: columns == 3 ? 18 : 12) {
                            ForEach(sections) { section in
                                LibraryGroupTile(section: section, compact: columns != 3, actions: actions)
                                    .id(section.id)
                                ForEach(section.games) { game in
                                    LibraryWallCover(game: game, title: section.title(of: game), showsTitle: columns == 3,
                                                     inProgress: content.inProgressIDs.contains(game.id),
                                                     installed: content.installedRomIDs.contains(game.id),
                                                     browseOnly: content.tierBySlug[game.rom.platformSlug] == .unsupported, actions: actions)
                                        .id("\(section.id).\(game.id)")
                                }
                            }
                        }
                        .padding(.leading, gutter)
                        .padding(.trailing, rail ? gutter + 24 : gutter)
                        LibraryCollectionRow(collections: content.collections, actions: actions)
                            .padding(.top, 30)
                        if let staleMessage = content.staleMessage {
                            Text(staleMessage).themeText(.caption).foregroundStyle(theme.inkSecondary)
                                .padding(.horizontal, gutter)
                        }
                    }
                }
                .padding(.bottom, 40)
            }
            .overlay(alignment: .trailing) {
                if rail {
                    LibraryIndexRail(sections: sections) { id in proxy.scrollTo(id, anchor: .top) }
                        .padding(.trailing, 4)
                }
            }
            .onChange(of: lens) { _, _ in
                // A new order starts from its first group, with the lens still in view.
                withAnimation(.smooth(duration: 0.3)) { proxy.scrollTo(Self.top, anchor: .top) }
            }
        }
        .themedPage(.page)
        .litBy(content.lightURL)
        .accessibilityIdentifier("library.wall")
    }
}

/// The wall's sections, worked out once per content and lens (not per scroll frame).
@MainActor
final class LibraryWallMemo {
    private var key: (LibraryContent, LibraryLens)?
    private var cached: [LibraryWallSection] = []

    func sections(content: LibraryContent, lens: LibraryLens) -> [LibraryWallSection] {
        if let key, key.0 == content, key.1 == lens { return cached }
        cached = content.wall(lens)
        key = (content, lens)
        return cached
    }
}

extension LibraryContent {
    /// Games in progress (their covers carry a lit ring).
    var inProgressIDs: Set<Int> { Set(inProgress.map(\.id)) }

    /// Each platform's support tier by slug (browse-only covers carry an eye).
    var tierBySlug: [String: EmbeddedSupportTier] {
        Dictionary(platforms.map { ($0.slug, tier(of: $0)) }, uniquingKeysWith: { first, _ in first })
    }

    /// The page's light: the game you're playing, else the first cover.
    var lightURL: URL? {
        let byID = Dictionary(games.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        return inProgress.lazy.compactMap { byID[$0.id]?.artworkURL }.first ?? games.first?.artworkURL
    }
}

/// The lens: one glass chip per order, and the density switch beside them.
private struct LibraryLensBar: View {
    @Environment(\.rommioTheme) private var theme
    @Binding var lens: LibraryLens
    @Binding var columns: Int

    var body: some View {
        HStack(spacing: 10) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(LibraryLens.allCases) { option in
                        FilterChip(title: option.title, systemImage: option.systemImage, selected: option == lens) {
                            withAnimation(.smooth(duration: 0.3)) { lens = option }
                        }
                        .accessibilityIdentifier("library.lens.\(option.rawValue)")
                    }
                }
                .padding(.leading, theme.spacing.gutter)
                .padding(.trailing, 6)
            }
            .mask {
                // The chips fade out before the density switch rather than slide under it.
                LinearGradient(stops: [.init(color: .black, location: 0), .init(color: .black, location: 0.9), .init(color: .clear, location: 1)],
                               startPoint: .leading, endPoint: .trailing)
            }
            Button {
                withAnimation(.smooth(duration: 0.3)) { columns = columns == 3 ? 4 : 3 }
            } label: {
                Image(systemName: columns == 3 ? "square.grid.3x3" : "square.grid.2x2")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(theme.ink)
                    .frame(width: 44, height: 44)
                    .background { GlassFill(shape: Circle()); GlassEdge(shape: Circle()) }
            }
            .buttonStyle(.plain)
            .padding(.trailing, theme.spacing.gutter)
            .accessibilityLabel(columns == 3 ? "Show more games" : "Show larger covers")
            .accessibilityIdentifier("library.density")
        }
    }
}

/// A group's first cell: its name and counts on glass, the size of a cover; a console's
/// tile opens its page.
private struct LibraryGroupTile: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.pageLight) private var light
    let section: LibraryWallSection
    let compact: Bool
    let actions: LibraryActions

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: compact ? 9 : 12, style: .continuous)
        let tile = VStack(alignment: .leading, spacing: 6) {
            Color.clear
                .aspectRatio(2 / 3, contentMode: .fit)
                .overlay(alignment: .topLeading) {
                    VStack(alignment: .leading, spacing: compact ? 4 : 6) {
                        Capsule().fill(light.mark1).frame(width: compact ? 18 : 24, height: 3)
                        // A non-breaking hyphen keeps "16-bit" whole in a narrow tile.
                        Text(section.title.replacingOccurrences(of: "-", with: "\u{2011}"))
                            .themeText(.headline, size: compact ? 14 : 18)
                            .foregroundStyle(theme.ink)
                            .lineLimit(4)
                            .minimumScaleFactor(0.75)
                        if let subtitle = section.subtitle {
                            Text(subtitle).themeText(.numeral, size: compact ? 11 : 13).foregroundStyle(theme.inkSecondary)
                        }
                        Spacer(minLength: 0)
                        Text(PlayFormat.games(section.platform?.romCount ?? section.games.count))
                            .themeText(.caption, size: compact ? 11 : 12.5)
                            .foregroundStyle(theme.inkSecondary)
                        if section.installed > 0 {
                            Label("\(section.installed) here", systemImage: "checkmark")
                                .labelStyle(.titleAndIcon)
                                .themeText(.caption, size: compact ? 10.5 : 12)
                                .foregroundStyle(theme.inkSecondary)
                        }
                        if section.platform != nil && !compact {
                            HStack(spacing: 3) {
                                Text("Open").themeText(.bodyEmphasis, size: 12.5)
                                Image(systemName: "chevron.right").font(.system(size: 10, weight: .bold))
                            }
                            .foregroundStyle(light.readableTint)
                            .padding(.top, 2)
                        }
                    }
                    .padding(compact ? 9 : 12)
                }
                // Glass lit by the group's first cover, so the tile belongs to the wall.
                .background {
                    ZStack {
                        if theme.isGlass, let url = section.games.first?.artworkURL {
                            BlurredCover(url: url, dark: theme.isDark)
                                .scaledToFill()
                                .opacity(theme.isDark ? 0.75 : 0.6)
                        }
                        GlassFill(shape: shape)
                    }
                    .clipShape(shape)
                }
                .overlay { GlassEdge(shape: shape) }
            if !compact {
                // Keeps the tile's row the same height as the covers' titles.
                Text(" \n ").themeText(.caption, size: 12.5).lineLimit(2, reservesSpace: true).hidden()
            }
        }
        if let platform = section.platform {
            Button { actions.openPlatform(platform) } label: { tile.contentShape(Rectangle()) }
                .buttonStyle(.plain)
                .accessibilityLabel("\(platform.name), \(section.detail)")
                .accessibilityIdentifier("library.platform.\(platform.slug)")
        } else {
            tile
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(.isHeader)
                .accessibilityLabel("\(section.title), \(section.detail)")
        }
    }
}

/// One cover on the wall: the poster at 2:3, a lit ring while in progress, a check when
/// on this device, an eye when browse only; its title under it at three across.
private struct LibraryWallCover: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.pageLight) private var light
    let game: LibraryGame
    let title: String
    let showsTitle: Bool
    let inProgress: Bool
    let installed: Bool
    let browseOnly: Bool
    let actions: LibraryActions

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: showsTitle ? 12 : 9, style: .continuous)
        Button { actions.openGame(game.rom) } label: {
            VStack(alignment: .leading, spacing: 6) {
                Color.clear
                    .aspectRatio(2 / 3, contentMode: .fit)
                    .overlay {
                        GameCover(url: game.artworkURL, title: game.rom.displayName, cornerRadius: showsTitle ? 12 : 9,
                                  maxPointSize: showsTitle ? 200 : 150, shadow: true)
                    }
                    .overlay {
                        if inProgress { shape.strokeBorder(light.mark1, lineWidth: 2.5).shadow(color: light.mark1.opacity(0.6), radius: 8) }
                    }
                    .overlay(alignment: .bottomTrailing) { badge.padding(6) }
                if showsTitle {
                    Text(title)
                        .themeText(.caption, size: 12.5)
                        .foregroundStyle(theme.ink)
                        .lineLimit(2, reservesSpace: true)
                        .multilineTextAlignment(.leading)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button("Game details", systemImage: "info.circle") { actions.openGame(game.rom) }
        } preview: {
            GameCover(url: game.artworkURL, title: game.rom.displayName, cornerRadius: 18, maxPointSize: 420, shadow: false)
                .frame(width: 280, height: 420)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel([game.rom.displayName, game.rom.platformAndYear, inProgress ? "In progress" : nil,
                             installed ? "On this device" : nil, browseOnly ? "Browse only" : nil].compactMap { $0 }.joined(separator: ", "))
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier("library.game.\(game.id)")
    }

    @ViewBuilder private var badge: some View {
        if installed || browseOnly {
            Image(systemName: installed ? "checkmark" : "eye")
                .font(.system(size: showsTitle ? 10 : 9, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: showsTitle ? 20 : 17, height: showsTitle ? 20 : 17)
                .background(Circle().fill(.black.opacity(0.55)))
                .background(Circle().fill(.ultraThinMaterial).environment(\.colorScheme, .dark))
                .overlay(Circle().strokeBorder(.white.opacity(0.35), lineWidth: 0.5))
        }
    }
}

/// The trailing index: one label per group; tap to jump.
private struct LibraryIndexRail: View {
    @Environment(\.rommioTheme) private var theme
    let sections: [LibraryWallSection]
    let jump: (String) -> Void

    var body: some View {
        VStack(spacing: 0) {
            ForEach(sections) { section in
                Button { jump(section.id) } label: {
                    Text(section.index)
                        .font(.system(size: 9.5, weight: .semibold).monospacedDigit())
                        .foregroundStyle(theme.inkSecondary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                        .frame(width: 30, height: 19)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Jump to \(section.title)")
            }
        }
        .padding(.vertical, 6)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("library.index")
    }
}

private struct LibraryCollectionRow: View {
    @Environment(\.rommioTheme) private var theme
    let collections: [LibraryCollection]
    let actions: LibraryActions

    var body: some View {
        if !collections.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                SectionHeader(title: "Collections", detail: "\(collections.count)")
                    .padding(.horizontal, theme.spacing.gutter + 6)
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(spacing: 14) {
                        ForEach(collections) { item in
                            Button { actions.openCollection(item.collection) } label: {
                                CollectionTile(collection: item.collection, artworkURL: item.artworkURL, width: 240)
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("library.collection.\(item.collection.id)")
                        }
                    }
                    .padding(.horizontal, theme.spacing.gutter)
                    .padding(.vertical, 6)
                    .padding(.bottom, 10)
                }
                .scrollClipDisabled()
            }
        }
    }
}

/// A filter choice with its count: glass (Aurora) or an outlined stamp (Riso);
/// filled with ink when selected.
struct FilterChip: View {
    @Environment(\.rommioTheme) private var theme
    let title: String
    var count: Int?
    var systemImage: String?
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let systemImage { Image(systemName: systemImage).font(.system(size: 12, weight: .semibold)) }
                Text(title).themeText(theme.isRiso ? .label : .bodyEmphasis, size: theme.isRiso ? 10.5 : 14)
                if let count {
                    Text("\(count)").themeText(.numeral, size: 13).opacity(0.7)
                }
            }
            .foregroundStyle(selected ? theme.buttonInk : theme.ink)
            .padding(.horizontal, 12)
            .frame(minHeight: 36)
            .background {
                if theme.isRiso {
                    Rectangle().fill(selected ? theme.ink : .clear)
                    Rectangle().strokeBorder(theme.ink, lineWidth: 1.5)
                } else if selected {
                    Capsule().fill(theme.buttonFill)
                } else {
                    GlassFill(shape: Capsule())
                    GlassEdge(shape: Capsule())
                }
            }
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// Aurora's eras by release year: the wall's Year lens (docs/theme-layouts.md, Library
/// eras; the same names, spans and identifiers as Android).
struct LibraryEra: Hashable, Identifiable {
    let id: String
    let name: String
    let first: Int?
    let last: Int?

    func contains(_ year: Int) -> Bool { (first.map { year >= $0 } ?? true) && (last.map { year <= $0 } ?? true) }

    static let all: [LibraryEra] = [
        LibraryEra(id: "e80", name: "Arcades & cartridges", first: nil, last: 1988),
        LibraryEra(id: "e16", name: "The 16-bit years", first: 1989, last: 1995),
        LibraryEra(id: "e3d", name: "Polygons & pockets", first: 1996, last: 2000),
        LibraryEra(id: "e00", name: "The 2000s", first: 2001, last: 2011),
        LibraryEra(id: "e12", name: "Since 2012", first: 2012, last: nil),
    ]

    static func era(of year: Int) -> LibraryEra? { all.first { $0.contains(year) } }

    /// "1986–88", "1994", "1998–2001": the years a set of games spans.
    static func span(_ years: [Int]) -> String {
        guard let low = years.min(), let high = years.max() else { return "" }
        if low == high { return String(low) }
        return low / 100 == high / 100 ? "\(low)–\(String(format: "%02d", high % 100))" : "\(low)–\(high)"
    }

    struct Group: Identifiable, Hashable {
        let era: LibraryEra
        /// By release year, then title.
        let games: [RomDTO]
        var id: String { era.id }
        var span: String { LibraryEra.span(games.compactMap(\.releaseYear)) }
    }

    /// Eras that have games with a release year, oldest first.
    static func groups(_ roms: [RomDTO]) -> [Group] {
        all.compactMap { era in
            let games = roms.filter { $0.releaseYear.map(era.contains) == true }
                .sorted { ($0.releaseYear ?? 0, $0.displayName) < ($1.releaseYear ?? 0, $1.displayName) }
            return games.isEmpty ? nil : Group(era: era, games: games)
        }
    }

    /// Eras of a library with covers, with each era's span and light covers (games in progress first).
    static func groups(_ games: [LibraryGame], inProgress: [RomDTO]) -> [LibraryEraGroup] {
        let byID = Dictionary(games.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        return groups(games.map(\.rom)).map { group in
            let members = group.games.compactMap { byID[$0.id] }
            let memberIDs = Set(members.map(\.id))
            let progress = inProgress.map(\.id).filter { memberIDs.contains($0) }.compactMap { byID[$0] }
            let progressIDs = Set(progress.map(\.id))
            let lights = Array((progress + members.filter { !progressIDs.contains($0.id) }).prefix(3)).map(\.artworkURL)
            return LibraryEraGroup(era: group.era, games: members, span: group.span, lightURLs: lights)
        }
    }
}
