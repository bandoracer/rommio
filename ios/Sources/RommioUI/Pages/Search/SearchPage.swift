import SwiftUI
import RommioContract
import RommioFoundation
#if os(iOS)
import UIKit
#endif

/// A search result with what this device knows about it.
struct SearchHit: Identifiable, Hashable {
    let rom: RomDTO
    let artworkURL: URL?
    let lastPlayedAt: Date?
    let installed: Bool
    let inProgress: Bool
    var id: Int { rom.id }
    /// Keys for lazy lists that show the same game in two places (results, and games in
    /// progress before typing): each list keeps its own rows.
    var resultKey: String { "result.\(rom.id)" }
    var progressKey: String { "progress.\(rom.id)" }

    /// "SNES · on this device · played 2 hours ago".
    var detail: String {
        var parts = [rom.platformSlug.uppercased()]
        if let year = rom.releaseYear { parts.append(String(year)) }
        if installed { parts.append("on this device") }
        if let lastPlayedAt { parts.append("played \(PlayFormat.inline(lastPlayedAt))") }
        return parts.joined(separator: " · ")
    }
}

/// A platform or collection that matches the query (related results).
struct SearchRelated: Identifiable, Hashable {
    enum Kind: Hashable { case platform(PlatformDTO), collection(RommCollectionDTO) }
    let kind: Kind
    let title: String
    let count: Int
    var id: String {
        switch kind {
        case let .platform(p): "p\(p.id)"
        case let .collection(c): "c\(c.id)"
        }
    }
    var kindTitle: String {
        switch kind {
        case .platform: "Platform"
        case .collection: "Collection"
        }
    }
    var destination: AppDestination {
        switch kind {
        case let .platform(p): .platform(p)
        case let .collection(c): .collection(c)
        }
    }
}

/// Everything the Search layouts draw, ranked once per change of the query or
/// the library (`SearchContent.build`).
struct SearchContent: Equatable {
    var query = ""
    var hits: [SearchHit] = []
    var related: [SearchRelated] = []
    var inProgress: [SearchHit] = []
    var libraryCount = 0
    var platforms: [PlatformDTO] = []
    var isSearching: Bool { !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    var trimmedQuery: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// Search from the feature models: results for the query, then related platforms and collections.
    @MainActor
    static func build(_ model: RommioAppModel) -> SearchContent {
        let feature = model.searchFeature
        let installed = model.installedRomIDs
        let progress = Set(feature.recent.map(\.id))
        let played = Dictionary(model.statsFeature.stats.games.map { ($0.romID, $0.lastPlayedAt) }, uniquingKeysWith: { first, _ in first })
        func hit(_ rom: RomDTO) -> SearchHit {
            SearchHit(rom: rom, artworkURL: model.artworkURL(path: rom.urlCover), lastPlayedAt: played[rom.id],
                      installed: installed.contains(rom.id), inProgress: progress.contains(rom.id))
        }
        var content = SearchContent(query: feature.query)
        content.libraryCount = feature.roms.count
        content.platforms = model.libraryFeature.platforms
        content.inProgress = feature.recent.map(hit)
        if content.isSearching {
            content.hits = feature.results.prefix(60).map { hit($0.rom) }
            let needle = content.trimmedQuery
            content.related = model.libraryFeature.platforms
                .filter { $0.name.localizedCaseInsensitiveContains(needle) || $0.slug.localizedCaseInsensitiveContains(needle) }
                .map { SearchRelated(kind: .platform($0), title: $0.name, count: $0.romCount) }
                + model.collectionsFeature.collections
                .filter { $0.name.localizedCaseInsensitiveContains(needle) }
                .map { SearchRelated(kind: .collection($0), title: $0.name, count: $0.romCount) }
        }
        return content
    }
}

struct SearchActions: PageActions {
    let open: (AppDestination) -> Void
    let resume: (RomDTO) -> Void
}

/// Search the cached library: the best match first, recently played first
/// within a match; platforms and collections that match follow. Aurora leads
/// with a lit best match; Riso sets an index lookup with "see also" entries.
struct SearchPage: View {
    @Environment(\.rommioTheme) private var theme
    let model: RommioAppModel
    let feature: SearchFeatureModel
    @State private var path = NavigationPath()

    var body: some View {
        NavigationStack(path: $path) {
            SearchLayout(content: model.searchContent.value, actions: actions, riso: theme.isRiso)
                .scrollDismissesKeyboard(.immediately)
                .navigationTitle("Search")
                .modifier(SearchFieldPlacement(feature: feature))
                .appDestinations(model)
                .accessibilityIdentifier("shell.searchList")
                .task {
                    await feature.refresh(recentlyPlayed: model.playFeature.snapshot.continuePlaying, stats: model.statsFeature.stats)
                }
        }
        .appNavigator($path)
    }

    private var actions: SearchActions {
        let model = model
        let path = $path
        return SearchActions(open: { path.wrappedValue.append($0) }, resume: { rom in Task { await model.quickResume(rom: rom) } })
    }
}

private struct SearchLayout: View {
    let content: SearchContent
    let actions: SearchActions
    let riso: Bool

    var body: some View {
        if riso {
            RisoSearchLayout(content: content, actions: actions)
        } else {
            AuroraSearchLayout(content: content, actions: actions)
        }
    }
}

/// Text with the query marked: a wash of the page's light (Aurora) or a yellow
/// highlighter (Riso).
struct HighlightedText: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.pageLight) private var light
    let text: String
    let query: String

    var body: some View {
        Text(attributed)
    }

    private var attributed: AttributedString {
        var string = AttributedString(text)
        guard !query.isEmpty, let range = string.range(of: query, options: [.caseInsensitive, .diacriticInsensitive]) else { return string }
        string[range].backgroundColor = theme.isRiso ? theme.accent3 : light.mark1.opacity(0.26)
        if theme.isRiso { string[range].foregroundColor = theme.onAccent }
        return string
    }
}

// MARK: Aurora

struct AuroraSearchLayout: View {
    let content: SearchContent
    let actions: SearchActions

    var body: some View {
        ThemedScrollPage(style: content.hits.first == nil ? .quiet : .page, maxWidth: 900) {
            if content.isSearching {
                if let best = content.hits.first {
                    AuroraSearchRankNote()
                    AuroraBestMatch(best: best, query: content.trimmedQuery, actions: actions)
                }
                if content.hits.count > 1 {
                    AuroraSearchHits(hits: Array(content.hits.dropFirst()), query: content.trimmedQuery, actions: actions)
                }
                if !content.related.isEmpty {
                    AuroraSearchRelated(related: content.related, actions: actions)
                }
                if content.hits.isEmpty && content.related.isEmpty {
                    SearchNoResults(query: content.trimmedQuery)
                }
            } else {
                AuroraSearchSuggestions(inProgress: content.inProgress, libraryCount: content.libraryCount, actions: actions)
            }
        }
        .litBy(content.isSearching ? content.hits.first?.artworkURL : nil)
    }
}

private struct SearchNoResults: View {
    let query: String

    var body: some View {
        EmptyStateView(title: "No results for “\(query)”", systemImage: "magnifyingglass",
                       message: "Search looks through the games Rommio has loaded from your server. Browse a platform in the Library to load more.")
    }
}

private struct AuroraSearchRankNote: View {
    @Environment(\.rommioTheme) private var theme

    var body: some View {
        Label("Ranked by recent play, then title", systemImage: "sparkles")
            .themeText(.caption, size: 12.5)
            .foregroundStyle(theme.inkSecondary)
    }
}

private struct AuroraBestMatch: View {
    @Environment(\.rommioTheme) private var theme
    let best: SearchHit
    let query: String
    let actions: SearchActions

    var body: some View {
        // The whole card opens the game, and Resume is its own control. They are siblings,
        // not a button inside a button (SwiftUI doesn't route taps reliably through nested
        // buttons: the card stopped opening once the game had a Resume button).
        HStack(alignment: .center, spacing: 16) {
            GameCover(url: best.artworkURL, title: best.rom.displayName, maxPointSize: 180)
                .frame(width: 104, height: 138)
                .allowsHitTesting(false)
            VStack(alignment: .leading, spacing: 6) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Best match").themeText(.section).foregroundStyle(theme.ink)
                    if let played = best.lastPlayedAt {
                        Text("Played \(PlayFormat.inline(played))").themeText(.section, size: 10.5).foregroundStyle(theme.inkSecondary)
                    }
                    HighlightedText(text: best.rom.displayName, query: query)
                        .themeText(.headline)
                        .foregroundStyle(theme.ink)
                        .lineLimit(3)
                    Text(best.rom.platformAndYear).themeText(.secondary).foregroundStyle(theme.inkSecondary)
                }
                .allowsHitTesting(false)
                .accessibilityHidden(true)
                if best.inProgress {
                    Button { actions.resume(best.rom) } label: { Label("Resume", systemImage: "play.fill") }
                        .buttonStyle(PrimaryButtonStyle(fullWidth: false, large: false))
                        .accessibilityIdentifier("search.resume.\(best.rom.id)")
                }
            }
            Spacer(minLength: 0)
        }
        .padding(14)
        .background {
            Button { actions.open(.game(best.rom)) } label: {
                RoundedRectangle(cornerRadius: theme.radius.card, style: .continuous)
                    .fill(.clear)
                    .contentShape(RoundedRectangle(cornerRadius: theme.radius.card, style: .continuous))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Best match: \(best.rom.displayName), \(best.rom.platformAndYear)")
            .accessibilityIdentifier("search.bestMatch")
        }
        .background { LitCardOutline() }
        .themeSurface()
    }
}

/// The best match's outline in the page's light.
private struct LitCardOutline: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.pageLight) private var light

    var body: some View {
        RoundedRectangle(cornerRadius: theme.radius.card, style: .continuous).strokeBorder(light.mark1.opacity(0.7), lineWidth: 1)
    }
}

private struct AuroraSearchHits: View {
    let hits: [SearchHit]
    let query: String
    let actions: SearchActions

    var body: some View {
        ThemedLazyGroup(title: "Games", detail: "\(hits.count)", inset: 68, items: hits) { hit in
            Button { actions.open(.game(hit.rom)) } label: {
                AuroraSearchHitRow(hit: hit, query: query)
            }
            .buttonStyle(RowButtonStyle())
            .accessibilityIdentifier("search.result.\(hit.rom.id)")
        }
    }
}

private struct AuroraSearchHitRow: View {
    @Environment(\.rommioTheme) private var theme
    let hit: SearchHit
    let query: String

    var body: some View {
        HStack(spacing: 12) {
            GameCover(url: hit.artworkURL, title: hit.rom.displayName, cornerRadius: 8, maxPointSize: 64, shadow: false)
                .frame(width: 40, height: 52)
            VStack(alignment: .leading, spacing: 2) {
                HighlightedText(text: hit.rom.displayName, query: query)
                    .themeText(.bodyEmphasis, size: 16).foregroundStyle(theme.ink).lineLimit(2)
                Text(hit.detail).themeText(.secondary, size: 13).foregroundStyle(theme.inkSecondary).lineLimit(1)
            }
            Spacer(minLength: 8)
            Chevron()
        }
    }
}

private struct AuroraSearchRelated: View {
    let related: [SearchRelated]
    let actions: SearchActions

    var body: some View {
        ThemedGroup(title: "Platforms and collections", detail: "\(related.count)") {
            ForEach(related) { item in
                Button { actions.open(item.destination) } label: {
                    ListRow(title: item.title, subtitle: "\(item.kindTitle) · \(PlayFormat.games(item.count))", trailing: { Chevron() })
                }
                .buttonStyle(RowButtonStyle())
                .accessibilityIdentifier("search.related.\(item.id)")
            }
        }
    }
}

private struct AuroraSearchSuggestions: View {
    let inProgress: [SearchHit]
    let libraryCount: Int
    let actions: SearchActions

    var body: some View {
        if !inProgress.isEmpty {
            Shelf(title: "Continue playing") {
                ForEach(inProgress) { hit in
                    Button { actions.open(.game(hit.rom)) } label: {
                        GameTile(rom: hit.rom, artworkURL: hit.artworkURL, width: 116)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        EmptyStateView(title: "Search your library", systemImage: "magnifyingglass",
                       message: libraryCount == 0 ? "Games appear here once your library has loaded."
                                                  : "Find any of \(PlayFormat.games(libraryCount)) by title or platform.")
    }
}

// MARK: Riso

struct RisoSearchLayout: View {
    @Environment(\.rommioTheme) private var theme
    let content: SearchContent
    let actions: SearchActions

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                if content.isSearching {
                    RisoSearchCount(count: content.hits.count, query: content.trimmedQuery)
                    if !content.related.isEmpty {
                        RisoSeeAlso(related: Array(content.related.prefix(6)), actions: actions)
                    }
                    if !content.hits.isEmpty {
                        SectionHeader(title: "Games", detail: "\(content.hits.count)")
                        ForEach(Array(content.hits.enumerated()), id: \.element.resultKey) { index, hit in
                            RisoSearchEntry(hit: hit, query: content.trimmedQuery, actions: actions)
                                .accessibilityIdentifier(index == 0 ? "search.bestMatch" : "search.result.\(hit.rom.id)")
                        }
                    }
                    if !content.related.isEmpty {
                        SectionHeader(title: "Platforms and collections").padding(.top, 26)
                        ForEach(content.related) { item in
                            RisoRelatedEntry(item: item, actions: actions)
                        }
                    }
                    if content.hits.isEmpty && content.related.isEmpty {
                        SearchNoResults(query: content.trimmedQuery)
                    }
                } else {
                    RisoSearchPrompt(libraryCount: content.libraryCount)
                    if !content.inProgress.isEmpty {
                        SectionHeader(title: "Continue playing", detail: "\(content.inProgress.count)")
                        ForEach(content.inProgress, id: \.progressKey) { RisoSearchEntry(hit: $0, query: content.trimmedQuery, actions: actions) }
                    }
                    if !content.platforms.isEmpty {
                        SectionHeader(title: "Platforms", detail: "\(content.platforms.count)").padding(.top, 26)
                        ForEach(content.platforms) { platform in
                            RisoPlatformLookup(platform: platform, actions: actions)
                        }
                    }
                }
            }
            .padding(.horizontal, theme.spacing.gutter)
            .padding(.bottom, 40)
            .frame(maxWidth: 760)
            .frame(maxWidth: .infinity)
        }
        .themedPage()
    }
}

private struct RisoSearchCount: View {
    @Environment(\.rommioTheme) private var theme
    let count: Int
    let query: String

    var body: some View {
        (Text("\(count) \(count == 1 ? "entry" : "entries")").bold()
            + Text(" for “\(query)”. ")
            + Text("Ranked by what you've played, then by title.").italic())
            .themeText(.body, size: 16)
            .foregroundStyle(theme.ink)
            .padding(.bottom, 12)
    }
}

private struct RisoSeeAlso: View {
    @Environment(\.rommioTheme) private var theme
    let related: [SearchRelated]
    let actions: SearchActions

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Text("See also").themeText(.label, size: 9.5).foregroundStyle(theme.inkSecondary).padding(.top, 10)
            FlowRow(spacing: 6) {
                ForEach(related) { item in
                    Button { actions.open(item.destination) } label: {
                        Text(item.title)
                            .themeText(.body, size: 14.5)
                            .italic()
                            .foregroundStyle(theme.ink)
                            .padding(.horizontal, 10)
                            .frame(minHeight: 36)
                            .overlay { Rectangle().strokeBorder(theme.ink, lineWidth: 1.5) }
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("search.related.\(item.id)")
                }
            }
        }
        .padding(.bottom, 18)
    }
}

private struct RisoSearchEntry: View {
    @Environment(\.rommioTheme) private var theme
    let hit: SearchHit
    let query: String
    let actions: SearchActions

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            Button { actions.open(.game(hit.rom)) } label: {
                HStack(alignment: .center, spacing: 12) {
                    GameCover(url: hit.artworkURL, title: hit.rom.displayName, maxPointSize: 80)
                        .frame(width: 44, height: 58)
                    VStack(alignment: .leading, spacing: 3) {
                        HighlightedText(text: hit.rom.displayName, query: query)
                            .themeText(.headline, size: 24)
                            .foregroundStyle(theme.ink)
                            .lineLimit(2)
                        Text(hit.detail).themeText(.label, size: 9.5).foregroundStyle(theme.inkSecondary).lineLimit(1)
                    }
                    Spacer(minLength: 4)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            if hit.inProgress {
                Button { actions.resume(hit.rom) } label: { Image(systemName: "play.fill") }
                    .buttonStyle(IconButtonStyle(size: 44, prominent: true))
                    .accessibilityLabel("Resume \(hit.rom.displayName)")
                    .accessibilityIdentifier("search.resume.\(hit.rom.id)")
            } else {
                Chevron()
            }
        }
        .padding(.vertical, 10)
        .overlay(alignment: .bottom) { Rectangle().fill(theme.hairline).frame(height: 1) }
    }
}

private struct RisoRelatedEntry: View {
    @Environment(\.rommioTheme) private var theme
    let item: SearchRelated
    let actions: SearchActions

    var body: some View {
        Button { actions.open(item.destination) } label: {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text(item.kindTitle).themeText(.label, size: 9.5).foregroundStyle(theme.inkSecondary).frame(width: 84, alignment: .leading)
                Text(item.title).themeText(.bodyEmphasis, size: 16).foregroundStyle(theme.ink).lineLimit(1).layoutPriority(1)
                DotLeader()
                Text(PlayFormat.games(item.count)).themeText(.numeral, size: 11.5).foregroundStyle(theme.ink).fixedSize()
            }
            .frame(minHeight: 44)
            .overlay(alignment: .bottom) { Rectangle().fill(theme.hairline).frame(height: 1) }
            .contentShape(Rectangle())
        }
        .buttonStyle(RowButtonStyle())
    }
}

private struct RisoSearchPrompt: View {
    @Environment(\.rommioTheme) private var theme
    let libraryCount: Int

    var body: some View {
        Text(libraryCount == 0 ? "Games appear here once your library has loaded."
                               : "Look up any of \(PlayFormat.games(libraryCount)) by title or platform.")
            .themeText(.body, size: 16)
            .italic()
            .foregroundStyle(theme.inkSecondary)
            .padding(.bottom, 14)
    }
}

private struct RisoPlatformLookup: View {
    @Environment(\.rommioTheme) private var theme
    let platform: PlatformDTO
    let actions: SearchActions

    var body: some View {
        Button { actions.open(.platform(platform)) } label: {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text(platform.name).themeText(.bodyEmphasis, size: 16).foregroundStyle(theme.ink).lineLimit(1).layoutPriority(1)
                DotLeader()
                Text(platform.romCount.formatted()).themeText(.numeral, size: 11.5).foregroundStyle(theme.ink)
            }
            .frame(minHeight: 44)
            .overlay(alignment: .bottom) { Rectangle().fill(theme.hairline).frame(height: 1) }
            .contentShape(Rectangle())
        }
        .buttonStyle(RowButtonStyle())
    }
}

/// The search field, always visible under the Search title. (With the
/// sidebar-adaptable tab bar the search tab is an ordinary tab, so the field
/// is placed on the page explicitly rather than left to automatic placement.)
private struct SearchFieldPlacement: ViewModifier {
    @Bindable var feature: SearchFeatureModel

    func body(content: Content) -> some View {
#if os(iOS)
        content.searchable(text: $feature.query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Games and platforms")
            // On an iPad an active search takes the tab bar's place, and a query kept after
            // opening a game would leave the other tabs out of reach until it's cleared.
            .searchPresentationToolbarBehavior(.avoidHidingContent)
            .background { SearchFieldTagger(identifier: "search.field") }
#else
        content.searchable(text: $feature.query, prompt: "Games and platforms")
#endif
    }
}

#if os(iOS)
/// `.searchable` offers no identifier for its field; this tags the system search
/// field `search.field` (the identifier both platforms use) once it is on screen.
private struct SearchFieldTagger: UIViewRepresentable {
    let identifier: String

    func makeUIView(context: Context) -> TaggerView { TaggerView(identifier: identifier) }
    func updateUIView(_ view: TaggerView, context: Context) { view.tagFields() }

    final class TaggerView: UIView {
        let identifier: String

        init(identifier: String) {
            self.identifier = identifier
            super.init(frame: .zero)
            isUserInteractionEnabled = false
            isAccessibilityElement = false
        }

        required init?(coder: NSCoder) { nil }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            tagFields()
            // The bar installs its field after the first layout pass.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in self?.tagFields() }
        }

        func tagFields() {
            guard let window else { return }
            for field in Self.searchFields(in: window) where (field.accessibilityIdentifier ?? "").isEmpty {
                field.accessibilityIdentifier = identifier
            }
        }

        private static func searchFields(in view: UIView) -> [UISearchTextField] {
            (view as? UISearchTextField).map { [$0] } ?? view.subviews.flatMap { searchFields(in: $0) }
        }
    }
}
#endif
