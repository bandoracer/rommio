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
    let artworkURL: (String?) -> URL?

    @State private var platformFilter: PlatformDTO?
    /// nil shows every tier.
    @State private var tierFilter: EmbeddedSupportTier?
    /// nil shows every era.
    @State private var eraFilter: LibraryEra?
    @State private var focusID: Int?
    @State private var palettes: [URL: CoverPalette] = [:]

    /// Games that pass the platform and tier filters.
    private var filtered: [RomDTO] {
        content.roms.filter { rom in
            if let platformFilter { return rom.platformID == platformFilter.id || rom.platformSlug == platformFilter.slug }
            if let tierFilter { return content.sections.first { $0.supportTier == tierFilter }?.platforms.contains { $0.slug == rom.platformSlug } ?? false }
            return true
        }
    }

    /// Eras of the whole cached library (the ruler shows with at least two).
    private var eras: [LibraryEra.Group] { LibraryEra.groups(content.roms) }
    @State private var initialEraApplied = false

    private var browseGames: [RomDTO] {
        if let eraFilter {
            // An era (∩ platform/tier filter): games in progress first, then by release year.
            let games = filtered.filter { $0.releaseYear.map(eraFilter.contains) == true }
                .sorted { ($0.releaseYear ?? 0, $0.displayName) < ($1.releaseYear ?? 0, $1.displayName) }
            let progress = content.inProgress.compactMap { rom in games.first { $0.id == rom.id } }
            let rest = games.filter { rom in !progress.contains { $0.id == rom.id } }
            return Array((progress + rest).prefix(18))
        }
        let pool = filtered
        // The cached library's copy of each game in progress (it carries RomM metadata).
        let progress = content.inProgress.compactMap { rom in pool.first { $0.id == rom.id } }
        let rest = pool.filter { rom in !progress.contains { $0.id == rom.id } }
            .sorted { $0.displayName.localizedStandardCompare($1.displayName) == .orderedAscending }
        return Array((progress + rest).prefix(18))
    }

    private var focused: RomDTO? {
        let games = browseGames
        return games.first { $0.id == focusID } ?? games.first
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                if !content.isEmpty {
                    Text(content.summary)
                        .themeText(.secondary)
                        .foregroundStyle(theme.inkSecondary)
                        .padding(.horizontal, theme.spacing.gutter)
                }
                if content.isEmpty {
                    EmptyStateView(title: content.isLoading ? "Loading your library" : "No platforms yet", systemImage: "square.stack.3d.up.slash",
                                   message: content.isLoading ? nil : content.errorMessage ?? "Pull to refresh once your server is reachable.")
                } else {
                    if eras.count >= 2 { eraRuler }
                    browseHeader.padding(.horizontal, theme.spacing.gutter)
                    coverRow
                    facets
                    platforms.padding(.horizontal, theme.spacing.gutter)
                    collections
                    if let staleMessage = content.staleMessage {
                        Text(staleMessage).themeText(.caption).foregroundStyle(theme.inkSecondary)
                            .padding(.horizontal, theme.spacing.gutter)
                    }
                }
            }
            .padding(.bottom, 40)
        }
        .themedPage(.page)
        .litBy(artworkURL(focused?.urlCover))
        .coverPalettes(eras.flatMap { eraLights($0) }.compactMap { artworkURL($0.urlCover) }, into: $palettes)
        .onChange(of: eras.map(\.id) + content.inProgress.map { String($0.id) }, initial: true) { _, _ in
            // Opens on the era of the most recent game in progress that has a year.
            guard !initialEraApplied, eras.count >= 2 else { return }
            let years = content.inProgress.lazy.compactMap { rom in content.roms.first { $0.id == rom.id }?.releaseYear ?? rom.releaseYear }
            guard let year = years.first else { return }
            initialEraApplied = true
            eraFilter = LibraryEra.era(of: year)
        }
    }

    /// Up to three of an era's games for its light: games in progress first.
    private func eraLights(_ group: LibraryEra.Group) -> [RomDTO] {
        let progress = content.inProgress.compactMap { rom in group.games.first { $0.id == rom.id } }
        return Array((progress + group.games.filter { rom in !progress.contains { $0.id == rom.id } }).prefix(3))
    }

    // MARK: Eras

    /// The era ruler: one segment per era with games, width by its count (at least
    /// 15%): a bar of its games' light, the years they span and the count.
    private var eraRuler: some View {
        let total = max(1, eras.reduce(0) { $0 + $1.games.count })
        return GeometryReader { proxy in
            let gap: CGFloat = 6
            let available = proxy.size.width - gap * CGFloat(eras.count - 1)
            let weights = eras.map { max(0.15, Double($0.games.count) / Double(total)) }
            let sum = weights.reduce(0, +)
            HStack(alignment: .top, spacing: gap) {
                ForEach(Array(eras.enumerated()), id: \.element.id) { index, group in
                    let selected = eraFilter == group.era
                    let lights = eraLights(group).map { theme.mark(for: artworkURL($0.urlCover), in: palettes) }
                    Button {
                        withAnimation(.smooth) { eraFilter = selected ? nil : group.era; focusID = nil }
                    } label: {
                        VStack(alignment: .leading, spacing: 5) {
                            RoundedRectangle(cornerRadius: 4, style: .continuous)
                                .fill(LinearGradient(colors: lights.count == 1 ? [lights[0], lights[0]] : lights, startPoint: .leading, endPoint: .trailing))
                                .opacity(selected ? 1 : 0.4)
                                .frame(height: selected ? 8 : 6)
                                .shadow(color: selected ? (lights.first ?? theme.mark1).opacity(0.6) : .clear, radius: 8, y: 4)
                            Text(group.span)
                                .themeText(.bodyEmphasis, size: 12)
                                .foregroundStyle(selected ? theme.ink : theme.inkSecondary)
                                .lineLimit(1)
                                .minimumScaleFactor(0.7)
                            Text(group.games.count.formatted()).themeText(.caption, size: 11).foregroundStyle(theme.inkTertiary)
                        }
                        .frame(width: max(0, available * weights[index] / sum), alignment: .leading)
                        .frame(minHeight: 48, alignment: .top)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(group.era.name), \(group.span), \(PlayFormat.games(group.games.count))")
                    .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
                    .accessibilityIdentifier("library.era.\(group.era.id)")
                }
            }
        }
        .frame(height: 52)
        .padding(.horizontal, theme.spacing.gutter)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("library.eras")
    }

    // MARK: Browse

    private var browseHeader: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                Text(eraFilter?.name ?? platformFilter?.name ?? tierFilter?.title ?? "All platforms")
                    .themeText(.headline, size: eraFilter == nil ? nil : 24)
                    .foregroundStyle(theme.ink)
                    .accessibilityAddTraits(.isHeader)
                if let eraFilter {
                    // "[SLUG or tier ·] N games · N here", counted over the filtered pool.
                    let pool = filtered.filter { $0.releaseYear.map(eraFilter.contains) == true }
                    let here = pool.filter { content.installedRomIDs.contains($0.id) }.count
                    Text([platformFilter?.slug.uppercased() ?? tierFilter?.title, PlayFormat.games(pool.count), "\(here) here"].compactMap { $0 }.joined(separator: " · "))
                        .themeText(.caption, size: 12.5).foregroundStyle(theme.inkSecondary)
                } else if let platformFilter {
                    Text("\(PlayFormat.games(platformFilter.romCount)) · \(content.installed(platformFilter)) here")
                        .themeText(.caption).foregroundStyle(theme.inkSecondary)
                }
            }
            Spacer()
            if let platformFilter {
                Button("See all") { actions.openPlatform(platformFilter) }
                    .buttonStyle(SecondaryButtonStyle())
                    .accessibilityIdentifier("library.seeAll")
            }
        }
    }

    @ViewBuilder
    private var coverRow: some View {
        let games = browseGames
        if games.isEmpty, let eraFilter {
            Text("No games here from \(eras.first { $0.era == eraFilter }?.span ?? eraFilter.name).")
                .themeText(.secondary).foregroundStyle(theme.inkSecondary)
                .padding(.horizontal, theme.spacing.gutter)
        } else if games.isEmpty {
            Text("Covers appear here as your library loads.")
                .themeText(.secondary).foregroundStyle(theme.inkSecondary)
                .padding(.horizontal, theme.spacing.gutter)
        } else {
            VStack(spacing: 18) {
                GeometryReader { proxy in
                    let itemWidth: CGFloat = 150
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(spacing: -18) {
                            ForEach(games) { rom in
                                Button { actions.openGame(rom) } label: {
                                    GameCover(url: artworkURL(rom.urlCover), title: rom.displayName, maxPointSize: 240)
                                        .frame(width: itemWidth, height: itemWidth * 4 / 3)
                                }
                                .buttonStyle(.plain)
                                .scrollTransition(.interactive, axis: .horizontal) { view, phase in
                                    view
                                        .scaleEffect(1 - min(abs(phase.value), 1) * 0.22)
                                        .rotation3DEffect(.degrees(phase.value * -34), axis: (x: 0, y: 1, z: 0), perspective: 0.6)
                                        .opacity(1 - min(abs(phase.value), 1.5) * 0.28)
                                }
                                .zIndex(rom.id == focused?.id ? 1 : 0)
                                .id(rom.id)
                                .accessibilityLabel(rom.displayName)
                                .accessibilityIdentifier("library.cover.\(rom.id)")
                            }
                        }
                        .scrollTargetLayout()
                    }
                    .contentMargins(.horizontal, (proxy.size.width - itemWidth) / 2, for: .scrollContent)
                    .scrollTargetBehavior(.viewAligned)
                    .scrollPosition(id: $focusID, anchor: .center)
                    .scrollClipDisabled()
                }
                .frame(height: 210)
                if let focused {
                    VStack(spacing: 3) {
                        Text(focused.displayName)
                            .themeText(.headline, size: 21)
                            .foregroundStyle(theme.ink)
                            .lineLimit(1)
                        Text(focusLine(focused))
                            .themeText(.caption, size: 13)
                            .foregroundStyle(theme.inkSecondary)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.horizontal, theme.spacing.gutter)
                    .accessibilityElement(children: .combine)
                }
            }
        }
    }

    private func focusLine(_ rom: RomDTO) -> String {
        var parts = [rom.platformSlug.uppercased()]
        if let year = rom.releaseYear { parts.append(String(year)) }
        if content.inProgress.contains(where: { $0.id == rom.id }) { parts.append("In progress") }
        return parts.joined(separator: " · ")
    }

    // MARK: Filters

    private var facets: some View {
        VStack(alignment: .leading, spacing: 10) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(content.platforms.filter { tierFilter == nil || content.tier(of: $0) == tierFilter }) { platform in
                        PlatformFacet(platform: platform, installed: content.installed(platform), selected: platformFilter == platform) {
                            withAnimation(.smooth) {
                                platformFilter = platformFilter == platform ? nil : platform
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
                    FilterChip(title: "All", count: content.platforms.count, selected: tierFilter == nil) {
                        withAnimation(.smooth) { tierFilter = nil }
                    }
                    .accessibilityIdentifier("library.filter.all")
                    ForEach(content.sections) { section in
                        FilterChip(title: section.supportTier.title, count: section.platforms.count, systemImage: symbol(section.supportTier),
                                   selected: tierFilter == section.supportTier) {
                            withAnimation(.smooth) {
                                tierFilter = tierFilter == section.supportTier ? nil : section.supportTier
                                if let platformFilter, content.tier(of: platformFilter) != tierFilter { self.platformFilter = nil }
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

    // MARK: Platforms and collections

    private var platforms: some View {
        let sections = content.sections.filter { tierFilter == nil || $0.supportTier == tierFilter }
        return VStack(alignment: .leading, spacing: theme.spacing.section) {
            ForEach(sections) { section in
                ThemedGroup(title: section.title, detail: "\(section.platforms.count)") {
                    ForEach(section.platforms) { platform in
                        Button { actions.openPlatform(platform) } label: {
                            ListRow(title: platform.name,
                                    subtitle: "\(PlayFormat.games(platform.romCount)) · \(content.installed(platform)) on this device",
                                    trailing: { Chevron() })
                        }
                        .buttonStyle(RowButtonStyle())
                        .accessibilityIdentifier("library.platform.\(platform.slug)")
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var collections: some View {
        let all = content.collections
        if !all.isEmpty && tierFilter == nil && platformFilter == nil {
            VStack(alignment: .leading, spacing: 12) {
                SectionHeader(title: "Collections", detail: "\(all.count)")
                    .padding(.horizontal, theme.spacing.gutter + 6)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 14) {
                        ForEach(all) { collection in
                            Button { actions.openCollection(collection) } label: {
                                CollectionTile(collection: collection,
                                               artworkURL: artworkURL(collection.pathCoverLarge ?? collection.pathCoverSmall),
                                               width: 240)
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("library.collection.\(collection.id)")
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
    let platform: PlatformDTO
    let installed: Int
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 1) {
                HStack(spacing: 6) {
                    Circle().fill(selected ? theme.mark1 : theme.ink.opacity(0.7)).frame(width: 8, height: 8)
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
                    .strokeBorder(selected ? theme.mark1 : theme.glassRim.opacity(theme.isDark ? 0.08 : 0.6), lineWidth: selected ? 1.5 : 0.5)
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
}
