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

struct SearchContent {
    var query = ""
    var hits: [SearchHit] = []
    var related: [SearchRelated] = []
    var inProgress: [SearchHit] = []
    var libraryCount = 0
    var platforms: [PlatformDTO] = []
    var isSearching: Bool { !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    var trimmedQuery: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }
}

struct SearchActions {
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
        @Bindable var feature = feature
        NavigationStack(path: $path) {
            Group {
                if theme.isRiso {
                    RisoSearchLayout(content: content, actions: actions)
                } else {
                    AuroraSearchLayout(content: content, actions: actions)
                }
            }
            .scrollDismissesKeyboard(.immediately)
            .navigationTitle("Search")
            .modifier(SearchFieldPlacement(query: $feature.query))
            .appDestinations(model)
            .accessibilityIdentifier("shell.searchList")
            .task {
                await feature.refresh(recentlyPlayed: model.playFeature.snapshot.continuePlaying, stats: model.statsFeature.stats)
            }
        }
    }

    private var actions: SearchActions {
        SearchActions(open: { path.append($0) }, resume: { rom in Task { await model.quickResume(rom: rom) } })
    }

    private var content: SearchContent {
        let installed = Set(model.downloadsFeature.records.filter { $0.status == .completed }.map(\.romID))
        let progress = Set(feature.recent.map(\.id))
        let played = Dictionary(uniqueKeysWithValues: model.statsFeature.stats.games.map { ($0.romID, $0.lastPlayedAt) })
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

/// Text with the query marked: a wash of the page's light (Aurora) or a yellow
/// highlighter (Riso).
struct HighlightedText: View {
    @Environment(\.rommioTheme) private var theme
    let text: String
    let query: String

    var body: some View {
        Text(attributed)
    }

    private var attributed: AttributedString {
        var string = AttributedString(text)
        guard !query.isEmpty, let range = string.range(of: query, options: [.caseInsensitive, .diacriticInsensitive]) else { return string }
        string[range].backgroundColor = theme.isRiso ? theme.accent3 : theme.mark1.opacity(0.26)
        if theme.isRiso { string[range].foregroundColor = theme.onAccent }
        return string
    }
}

// MARK: Aurora

private struct AuroraSearchLayout: View {
    @Environment(\.rommioTheme) private var theme
    let content: SearchContent
    let actions: SearchActions

    var body: some View {
        ThemedScrollPage(style: content.hits.first == nil ? .quiet : .page, maxWidth: 900) {
            if content.isSearching {
                results
            } else {
                suggestions
            }
        }
        .litBy(content.isSearching ? content.hits.first?.artworkURL : nil)
    }

    @ViewBuilder
    private var results: some View {
        if let best = content.hits.first {
            Label("Ranked by recent play, then title", systemImage: "sparkles")
                .themeText(.caption, size: 12.5)
                .foregroundStyle(theme.inkSecondary)
            Button { actions.open(.game(best.rom)) } label: {
                HStack(alignment: .center, spacing: 16) {
                    GameCover(url: best.artworkURL, title: best.rom.displayName, maxPointSize: 180)
                        .frame(width: 104, height: 138)
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Best match").themeText(.section).foregroundStyle(theme.ink)
                        if let played = best.lastPlayedAt {
                            Text("Played \(PlayFormat.inline(played))").themeText(.section, size: 10.5).foregroundStyle(theme.inkSecondary)
                        }
                        HighlightedText(text: best.rom.displayName, query: content.trimmedQuery)
                            .themeText(.headline)
                            .foregroundStyle(theme.ink)
                            .lineLimit(3)
                        Text(best.rom.platformAndYear).themeText(.secondary).foregroundStyle(theme.inkSecondary)
                        if best.inProgress {
                            Button { actions.resume(best.rom) } label: { Label("Resume", systemImage: "play.fill") }
                                .buttonStyle(PrimaryButtonStyle(fullWidth: false, large: false))
                                .accessibilityIdentifier("search.resume.\(best.rom.id)")
                        }
                    }
                    Spacer(minLength: 0)
                }
                .padding(14)
                .background { RoundedRectangle(cornerRadius: theme.radius.card, style: .continuous).strokeBorder(theme.mark1.opacity(0.7), lineWidth: 1) }
                .themeSurface()
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("search.bestMatch")
        }
        if content.hits.count > 1 {
            ThemedGroup(title: "Games", detail: "\(content.hits.count - 1)", inset: 68) {
                ForEach(content.hits.dropFirst()) { hit in
                    Button { actions.open(.game(hit.rom)) } label: {
                        HStack(spacing: 12) {
                            GameCover(url: hit.artworkURL, title: hit.rom.displayName, cornerRadius: 8, maxPointSize: 64, shadow: false)
                                .frame(width: 40, height: 52)
                            VStack(alignment: .leading, spacing: 2) {
                                HighlightedText(text: hit.rom.displayName, query: content.trimmedQuery)
                                    .themeText(.bodyEmphasis, size: 16).foregroundStyle(theme.ink).lineLimit(2)
                                Text(hit.detail).themeText(.secondary, size: 13).foregroundStyle(theme.inkSecondary).lineLimit(1)
                            }
                            Spacer(minLength: 8)
                            Chevron()
                        }
                    }
                    .buttonStyle(RowButtonStyle())
                    .accessibilityIdentifier("search.result.\(hit.rom.id)")
                }
            }
        }
        if !content.related.isEmpty {
            ThemedGroup(title: "Platforms and collections", detail: "\(content.related.count)") {
                ForEach(content.related) { item in
                    NavigationLink(value: item.destination) {
                        ListRow(title: item.title, subtitle: "\(item.kindTitle) · \(PlayFormat.games(item.count))", trailing: { Chevron() })
                    }
                    .buttonStyle(RowButtonStyle())
                    .accessibilityIdentifier("search.related.\(item.id)")
                }
            }
        }
        if content.hits.isEmpty && content.related.isEmpty {
            EmptyStateView(title: "No results for “\(content.trimmedQuery)”", systemImage: "magnifyingglass",
                           message: "Search looks through the games Rommio has loaded from your server. Browse a platform in the Library to load more.")
        }
    }

    @ViewBuilder
    private var suggestions: some View {
        if !content.inProgress.isEmpty {
            Shelf(title: "Continue playing") {
                ForEach(content.inProgress) { hit in
                    Button { actions.open(.game(hit.rom)) } label: {
                        GameTile(rom: hit.rom, artworkURL: hit.artworkURL, width: 116)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        EmptyStateView(title: "Search your library", systemImage: "magnifyingglass",
                       message: content.libraryCount == 0 ? "Games appear here once your library has loaded."
                                                         : "Find any of \(PlayFormat.games(content.libraryCount)) by title or platform.")
    }
}

// MARK: Riso

private struct RisoSearchLayout: View {
    @Environment(\.rommioTheme) private var theme
    let content: SearchContent
    let actions: SearchActions

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if content.isSearching { results } else { suggestions }
            }
            .padding(.horizontal, theme.spacing.gutter)
            .padding(.bottom, 40)
            .frame(maxWidth: 760)
            .frame(maxWidth: .infinity)
        }
        .themedPage()
    }

    @ViewBuilder
    private var results: some View {
        (Text("\(content.hits.count) \(content.hits.count == 1 ? "entry" : "entries")").bold()
            + Text(" for “\(content.trimmedQuery)”. ")
            + Text("Ranked by what you've played, then by title.").italic())
            .themeText(.body, size: 16)
            .foregroundStyle(theme.ink)
            .padding(.bottom, 12)
        if !content.related.isEmpty {
            HStack(alignment: .top, spacing: 10) {
                Text("See also").themeText(.label, size: 9.5).foregroundStyle(theme.inkSecondary).padding(.top, 10)
                FlowRow(spacing: 6) {
                    ForEach(content.related.prefix(6)) { item in
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
        if !content.hits.isEmpty {
            SectionHeader(title: "Games", detail: "\(content.hits.count)")
            ForEach(Array(content.hits.enumerated()), id: \.element.id) { index, hit in
                entry(hit)
                    .accessibilityIdentifier(index == 0 ? "search.bestMatch" : "search.result.\(hit.rom.id)")
            }
        }
        if !content.related.isEmpty {
            SectionHeader(title: "Platforms and collections").padding(.top, 26)
            ForEach(content.related) { item in
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
        if content.hits.isEmpty && content.related.isEmpty {
            EmptyStateView(title: "No results for “\(content.trimmedQuery)”", systemImage: "magnifyingglass",
                           message: "Search looks through the games Rommio has loaded from your server. Browse a platform in the Library to load more.")
        }
    }

    private func entry(_ hit: SearchHit) -> some View {
        HStack(alignment: .center, spacing: 12) {
            Button { actions.open(.game(hit.rom)) } label: {
                HStack(alignment: .center, spacing: 12) {
                    GameCover(url: hit.artworkURL, title: hit.rom.displayName, maxPointSize: 80)
                        .frame(width: 44, height: 58)
                    VStack(alignment: .leading, spacing: 3) {
                        HighlightedText(text: hit.rom.displayName, query: content.trimmedQuery)
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

    @ViewBuilder
    private var suggestions: some View {
        Text(content.libraryCount == 0 ? "Games appear here once your library has loaded."
                                        : "Look up any of \(PlayFormat.games(content.libraryCount)) by title or platform.")
            .themeText(.body, size: 16)
            .italic()
            .foregroundStyle(theme.inkSecondary)
            .padding(.bottom, 14)
        if !content.inProgress.isEmpty {
            SectionHeader(title: "Continue playing", detail: "\(content.inProgress.count)")
            ForEach(content.inProgress) { entry($0) }
        }
        if !content.platforms.isEmpty {
            SectionHeader(title: "Platforms", detail: "\(content.platforms.count)").padding(.top, 26)
            ForEach(content.platforms) { platform in
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
    }
}

/// The search field, always visible under the Search title. (With the
/// sidebar-adaptable tab bar the search tab is an ordinary tab, so the field
/// is placed on the page explicitly rather than left to automatic placement.)
private struct SearchFieldPlacement: ViewModifier {
    @Binding var query: String

    func body(content: Content) -> some View {
#if os(iOS)
        content.searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Games and platforms")
            .background { SearchFieldTagger(identifier: "search.field") }
#else
        content.searchable(text: $query, prompt: "Games and platforms")
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
