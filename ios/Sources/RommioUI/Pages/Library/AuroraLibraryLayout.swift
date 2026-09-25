import SwiftUI
import RommioContract
import RommioFoundation

/// Aurora Library: browse by era (release years from RomM) with a cover row, lit
/// by the cover in focus, with platforms and support tiers as filters above the
/// platform list and collections. Without any release years the era ruler is
/// left out and the row shows games in progress first, then the rest.
struct AuroraLibraryLayout: View {
    @Environment(\.rommioTheme) private var theme
    let content: LibraryContent
    let actions: LibraryActions

    @State private var filters = LibraryFilters()
    @State private var focusID: Int?
    @State private var palettes: [URL: CoverPalette] = [:]
    @State private var initialEraApplied = false
    @State private var memo = LibraryBrowseMemo()

    var body: some View {
        // Worked out again only when the content or the filters change (not as the row scrolls).
        let browse = memo.result(content: content, filters: filters)
        let focused = browse.games.first { $0.id == focusID } ?? browse.games.first
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                if content.isEmpty {
                    EmptyStateView(title: content.isLoading ? "Loading your library" : "No platforms yet", systemImage: "square.stack.3d.up.slash",
                                   message: content.isLoading ? nil : content.errorMessage ?? "Pull to refresh once your server is reachable.")
                } else {
                    LibrarySummaryLine(text: content.summary)
                    if content.eras.count >= 2 {
                        LibraryEraRuler(eras: content.eras, selected: filters.era, palettes: palettes) { era in
                            withAnimation(.smooth) { filters.era = era; focusID = nil }
                        }
                    }
                    LibraryBrowseHeader(browse: browse, filters: filters, actions: actions)
                        .padding(.horizontal, theme.spacing.gutter)
                    LibraryCoverRow(browse: browse, focused: focused, focusID: $focusID, actions: actions)
                    LibraryFacets(content: content, filters: $filters, focusID: $focusID)
                    LibraryPlatformGroups(sections: content.sections.filter { filters.tier == nil || $0.supportTier == filters.tier },
                                          installedByPlatform: content.installedByPlatform, actions: actions)
                        .padding(.horizontal, theme.spacing.gutter)
                    if filters.tier == nil && filters.platform == nil {
                        LibraryCollectionRow(collections: content.collections, actions: actions)
                    }
                    if let staleMessage = content.staleMessage {
                        Text(staleMessage).themeText(.caption).foregroundStyle(theme.inkSecondary)
                            .padding(.horizontal, theme.spacing.gutter)
                    }
                }
            }
            .padding(.bottom, 40)
        }
        .themedPage(.page)
        .litBy(focused?.artworkURL)
        .coverPalettes(content.eras.flatMap(\.lightURLs).compactMap { $0 }, into: $palettes)
        .onChange(of: content.eras.map(\.id) + content.inProgress.map { String($0.id) }, initial: true) { _, _ in
            // Opens on the era of the most recent game in progress that has a year.
            guard !initialEraApplied, content.eras.count >= 2 else { return }
            let known = Dictionary(content.games.map { ($0.id, $0.rom) }, uniquingKeysWith: { first, _ in first })
            let years = content.inProgress.lazy.compactMap { rom in known[rom.id]?.releaseYear ?? rom.releaseYear }
            guard let year = years.first else { return }
            initialEraApplied = true
            filters.era = LibraryEra.era(of: year)
        }
    }
}

/// The browse row's filters: a platform, a support tier, an era (nil: all).
struct LibraryFilters: Equatable {
    var platform: PlatformDTO?
    var tier: EmbeddedSupportTier?
    var era: LibraryEra?
}

/// Keeps the last browse result: sorting the library again as the cover row
/// scrolls (focus) or palettes arrive would be wasted work.
@MainActor
final class LibraryBrowseMemo {
    private var key: (games: [LibraryGame], inProgress: [RomDTO], sections: [PlatformSupportSection], installed: Set<Int>, filters: LibraryFilters)?
    private var last: LibraryBrowseResult?

    func result(content: LibraryContent, filters: LibraryFilters) -> LibraryBrowseResult {
        // Unchanged content shares its storage, so these comparisons are cheap.
        if let key, let last, key.filters == filters, key.games == content.games, key.inProgress == content.inProgress,
           key.sections == content.sections, key.installed == content.installedRomIDs {
            return last
        }
        let result = LibraryBrowseResult(content: content, filters: filters)
        key = (content.games, content.inProgress, content.sections, content.installedRomIDs, filters)
        last = result
        return result
    }
}

/// The games the browse row shows for the filters, and the header's figures.
struct LibraryBrowseResult: Equatable {
    let games: [LibraryGame]
    /// The era's pool under the platform/tier filter ("N games · N here").
    let eraPoolCount: Int
    let eraHereCount: Int
    /// "No games here from <span>" when an era is chosen.
    let emptyEraName: String?
    /// Games on this device on the chosen platform.
    let platformInstalled: Int
    let inProgressIDs: Set<Int>

    init(content: LibraryContent, filters: LibraryFilters) {
        let tierSlugs = filters.tier.map { tier in Set(content.sections.first { $0.supportTier == tier }?.platforms.map(\.slug) ?? []) }
        let filtered = content.games.filter { game in
            if let platform = filters.platform { return game.rom.platformID == platform.id || game.rom.platformSlug == platform.slug }
            if let tierSlugs { return tierSlugs.contains(game.rom.platformSlug) }
            return true
        }
        let progressIDs = content.inProgress.map(\.id)
        let byID = Dictionary(filtered.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        inProgressIDs = Set(progressIDs)
        platformInstalled = filters.platform.map(content.installed) ?? 0
        if let era = filters.era {
            // An era (∩ platform/tier filter): games in progress first, then by release year.
            let pool = filtered.filter { $0.rom.releaseYear.map(era.contains) == true }
            let sorted = pool.sorted { ($0.rom.releaseYear ?? 0, $0.rom.displayName) < ($1.rom.releaseYear ?? 0, $1.rom.displayName) }
            let inPool = Set(pool.map(\.id))
            let progress = progressIDs.filter { inPool.contains($0) }.compactMap { byID[$0] }
            let progressSet = Set(progress.map(\.id))
            games = Array((progress + sorted.filter { !progressSet.contains($0.id) }).prefix(18))
            eraPoolCount = pool.count
            eraHereCount = pool.filter { content.installedRomIDs.contains($0.id) }.count
            emptyEraName = content.eras.first { $0.era == era }?.span ?? era.name
        } else {
            // The cached library's copy of each game in progress (it carries RomM metadata).
            let progress = progressIDs.compactMap { byID[$0] }
            let progressSet = Set(progress.map(\.id))
            let rest = filtered.filter { !progressSet.contains($0.id) }
                .sorted { $0.rom.displayName.localizedStandardCompare($1.rom.displayName) == .orderedAscending }
            games = Array((progress + rest).prefix(18))
            eraPoolCount = 0
            eraHereCount = 0
            emptyEraName = nil
        }
    }
}

private struct LibrarySummaryLine: View {
    @Environment(\.rommioTheme) private var theme
    let text: String

    var body: some View {
        Text(text)
            .themeText(.secondary)
            .foregroundStyle(theme.inkSecondary)
            .padding(.horizontal, theme.spacing.gutter)
    }
}

// MARK: Eras

/// The era ruler: one segment per era with games, width by its count (at least
/// 15%): a bar of its games' light, the years they span and the count.
private struct LibraryEraRuler: View {
    @Environment(\.rommioTheme) private var theme
    let eras: [LibraryEraGroup]
    let selected: LibraryEra?
    let palettes: [URL: CoverPalette]
    let select: (LibraryEra?) -> Void

    var body: some View {
        let total = max(1, eras.reduce(0) { $0 + $1.games.count })
        GeometryReader { proxy in
            let gap: CGFloat = 6
            let available = proxy.size.width - gap * CGFloat(eras.count - 1)
            let weights = eras.map { max(0.15, Double($0.games.count) / Double(total)) }
            let sum = weights.reduce(0, +)
            HStack(alignment: .top, spacing: gap) {
                ForEach(Array(eras.enumerated()), id: \.element.id) { index, group in
                    let isSelected = selected == group.era
                    Button {
                        select(isSelected ? nil : group.era)
                    } label: {
                        LibraryEraSegment(group: group, selected: isSelected, palettes: group.lightURLs.map { $0.flatMap { palettes[$0] } })
                            .frame(width: max(0, available * weights[index] / sum), alignment: .leading)
                            .frame(minHeight: 48, alignment: .top)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(group.era.name), \(group.span), \(PlayFormat.games(group.games.count))")
                    .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : .isButton)
                    .accessibilityIdentifier("library.era.\(group.era.id)")
                }
            }
        }
        .frame(height: 52)
        .padding(.horizontal, theme.spacing.gutter)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("library.eras")
    }
}

private struct LibraryEraSegment: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.pageLight) private var light
    let group: LibraryEraGroup
    let selected: Bool
    /// The palettes known so far of the era's light covers (a missing one draws the page's mark).
    let palettes: [CoverPalette?]

    var body: some View {
        let lights = palettes.map { light.mark(for: $0) }
        VStack(alignment: .leading, spacing: 5) {
            RoundedRectangle(cornerRadius: 4, style: .continuous)
                .fill(LinearGradient(colors: lights.count == 1 ? [lights[0], lights[0]] : lights, startPoint: .leading, endPoint: .trailing))
                .opacity(selected ? 1 : 0.4)
                .frame(height: selected ? 8 : 6)
                .shadow(color: selected ? (lights.first ?? light.mark1).opacity(0.6) : .clear, radius: 8, y: 4)
            Text(group.span)
                .themeText(.bodyEmphasis, size: 12)
                .foregroundStyle(selected ? theme.ink : theme.inkSecondary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Text(group.games.count.formatted()).themeText(.caption, size: 11).foregroundStyle(theme.inkTertiary)
        }
    }
}

// MARK: Browse

private struct LibraryBrowseHeader: View {
    @Environment(\.rommioTheme) private var theme
    let browse: LibraryBrowseResult
    let filters: LibraryFilters
    let actions: LibraryActions

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                Text(filters.era?.name ?? filters.platform?.name ?? filters.tier?.title ?? "All platforms")
                    .themeText(.headline, size: filters.era == nil ? nil : 24)
                    .foregroundStyle(theme.ink)
                    .accessibilityAddTraits(.isHeader)
                if filters.era != nil {
                    // "[SLUG or tier ·] N games · N here", counted over the filtered pool.
                    Text([filters.platform?.slug.uppercased() ?? filters.tier?.title, PlayFormat.games(browse.eraPoolCount), "\(browse.eraHereCount) here"]
                            .compactMap { $0 }.joined(separator: " · "))
                        .themeText(.caption, size: 12.5).foregroundStyle(theme.inkSecondary)
                } else if let platform = filters.platform {
                    Text("\(PlayFormat.games(platform.romCount)) · \(browse.platformInstalled) here")
                        .themeText(.caption).foregroundStyle(theme.inkSecondary)
                }
            }
            Spacer()
            if let platform = filters.platform {
                Button("See all") { actions.openPlatform(platform) }
                    .buttonStyle(SecondaryButtonStyle())
                    .accessibilityIdentifier("library.seeAll")
            }
        }
    }
}

private struct LibraryCoverRow: View {
    @Environment(\.rommioTheme) private var theme
    let browse: LibraryBrowseResult
    let focused: LibraryGame?
    @Binding var focusID: Int?
    let actions: LibraryActions

    var body: some View {
        if browse.games.isEmpty, let era = browse.emptyEraName {
            Text("No games here from \(era).")
                .themeText(.secondary).foregroundStyle(theme.inkSecondary)
                .padding(.horizontal, theme.spacing.gutter)
        } else if browse.games.isEmpty {
            Text("Covers appear here as your library loads.")
                .themeText(.secondary).foregroundStyle(theme.inkSecondary)
                .padding(.horizontal, theme.spacing.gutter)
        } else {
            VStack(spacing: 18) {
                LibraryCoverCarousel(games: browse.games, focusedID: focused?.id, focusID: $focusID, actions: actions)
                    .frame(height: 210)
                if let focused {
                    LibraryFocusCaption(game: focused, inProgress: browse.inProgressIDs.contains(focused.id))
                }
            }
        }
    }
}

private struct LibraryCoverCarousel: View {
    let games: [LibraryGame]
    let focusedID: Int?
    @Binding var focusID: Int?
    let actions: LibraryActions

    var body: some View {
        GeometryReader { proxy in
            let itemWidth: CGFloat = 150
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: -18) {
                    ForEach(games) { game in
                        Button { actions.openGame(game.rom) } label: {
                            GameCover(url: game.artworkURL, title: game.rom.displayName, maxPointSize: 240)
                                .frame(width: itemWidth, height: itemWidth * 4 / 3)
                        }
                        .buttonStyle(.plain)
                        .scrollTransition(.interactive, axis: .horizontal) { view, phase in
                            view
                                .scaleEffect(1 - min(abs(phase.value), 1) * 0.22)
                                .rotation3DEffect(.degrees(phase.value * -34), axis: (x: 0, y: 1, z: 0), perspective: 0.6)
                                .opacity(1 - min(abs(phase.value), 1.5) * 0.28)
                        }
                        .zIndex(game.id == focusedID ? 1 : 0)
                        .id(game.id)
                        .accessibilityLabel(game.rom.displayName)
                        .accessibilityIdentifier("library.cover.\(game.id)")
                    }
                }
                .scrollTargetLayout()
            }
            .contentMargins(.horizontal, (proxy.size.width - itemWidth) / 2, for: .scrollContent)
            .scrollTargetBehavior(.viewAligned)
            .scrollPosition(id: $focusID, anchor: .center)
            .scrollClipDisabled()
        }
    }
}

private struct LibraryFocusCaption: View {
    @Environment(\.rommioTheme) private var theme
    let game: LibraryGame
    let inProgress: Bool

    var body: some View {
        VStack(spacing: 3) {
            Text(game.rom.displayName)
                .themeText(.headline, size: 21)
                .foregroundStyle(theme.ink)
                .lineLimit(1)
            Text(line)
                .themeText(.caption, size: 13)
                .foregroundStyle(theme.inkSecondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, theme.spacing.gutter)
        .accessibilityElement(children: .combine)
    }

    private var line: String {
        var parts = [game.rom.platformSlug.uppercased()]
        if let year = game.rom.releaseYear { parts.append(String(year)) }
        if inProgress { parts.append("In progress") }
        return parts.joined(separator: " · ")
    }
}

// MARK: Filters

private struct LibraryFacets: View {
    @Environment(\.rommioTheme) private var theme
    let content: LibraryContent
    @Binding var filters: LibraryFilters
    @Binding var focusID: Int?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 8) {
                    ForEach(content.platforms.filter { filters.tier == nil || content.tier(of: $0) == filters.tier }) { platform in
                        PlatformFacet(platform: platform, installed: content.installed(platform), selected: filters.platform == platform) {
                            withAnimation(.smooth) {
                                filters.platform = filters.platform == platform ? nil : platform
                                focusID = nil
                            }
                        }
                        .accessibilityIdentifier("library.facet.\(platform.slug)")
                    }
                }
                .padding(.horizontal, theme.spacing.gutter)
                .padding(.vertical, 4)
            }
            .scrollClipDisabled()
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    FilterChip(title: "All", count: content.platforms.count, selected: filters.tier == nil) {
                        withAnimation(.smooth) { filters.tier = nil }
                    }
                    .accessibilityIdentifier("library.filter.all")
                    ForEach(content.sections) { section in
                        FilterChip(title: section.supportTier.title, count: section.platforms.count, systemImage: symbol(section.supportTier),
                                   selected: filters.tier == section.supportTier) {
                            withAnimation(.smooth) {
                                filters.tier = filters.tier == section.supportTier ? nil : section.supportTier
                                if let platform = filters.platform, content.tier(of: platform) != filters.tier { filters.platform = nil }
                                focusID = nil
                            }
                        }
                        .accessibilityIdentifier("library.filter.\(section.supportTier.rawValue.lowercased())")
                    }
                }
                .padding(.horizontal, theme.spacing.gutter)
            }
        }
    }

    private func symbol(_ tier: EmbeddedSupportTier) -> String {
        switch tier {
        case .touchSupported: "hand.tap"
        case .controllerSupported: "gamecontroller"
        case .unsupported: "eye"
        }
    }
}

// MARK: Platforms and collections

private struct LibraryPlatformGroups: View {
    @Environment(\.rommioTheme) private var theme
    let sections: [PlatformSupportSection]
    let installedByPlatform: [String: Int]
    let actions: LibraryActions

    var body: some View {
        LazyVStack(alignment: .leading, spacing: theme.spacing.section) {
            ForEach(sections) { section in
                ThemedLazyGroup(title: section.title, detail: "\(section.platforms.count)", items: section.platforms) { platform in
                    Button { actions.openPlatform(platform) } label: {
                        ListRow(title: platform.name,
                                subtitle: "\(PlayFormat.games(platform.romCount)) · \(installedByPlatform[platform.slug] ?? 0) on this device",
                                trailing: { Chevron() })
                    }
                    .buttonStyle(RowButtonStyle())
                    .accessibilityIdentifier("library.platform.\(platform.slug)")
                }
            }
        }
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

/// A platform as a filter: a coloured dot, its name, its count and how many
/// are on this device; outlined in the page's light when selected.
private struct PlatformFacet: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.pageLight) private var light
    let platform: PlatformDTO
    let installed: Int
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 1) {
                HStack(spacing: 6) {
                    Circle().fill(selected ? light.mark1 : theme.ink.opacity(0.7)).frame(width: 8, height: 8)
                    Text(platform.slug.uppercased()).themeText(.bodyEmphasis, size: 14).foregroundStyle(theme.ink)
                    Text(platform.romCount.formatted()).themeText(.numeral, size: 13).foregroundStyle(theme.inkSecondary)
                }
                Text(installed == 0 ? "none here" : "\(installed) here")
                    .themeText(.caption, size: 11)
                    .foregroundStyle(theme.inkTertiary)
                    .padding(.leading, 14)
            }
            .padding(.horizontal, 12)
            .frame(minHeight: 48)
            .background { GlassFill(shape: RoundedRectangle(cornerRadius: 16, style: .continuous)) }
            .overlay {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .strokeBorder(selected ? light.mark1 : theme.glassRim.opacity(theme.isDark ? 0.08 : 0.6), lineWidth: selected ? 1.5 : 0.5)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(platform.name), \(PlayFormat.games(platform.romCount)), \(installed) on this device")
        .accessibilityAddTraits(selected ? .isSelected : [])
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

/// Aurora's eras by release year (docs/theme-layouts.md, Library era rule; the
/// same names, spans and identifiers as Android).
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
