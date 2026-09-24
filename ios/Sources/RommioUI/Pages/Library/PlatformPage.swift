import SwiftUI
import RommioContract
import RommioFoundation
import RommioPlayerKit

/// A platform's games with what this device knows about each.
struct PlatformGame: Identifiable, Hashable {
    let rom: RomDTO
    let artworkURL: URL?
    let installed: Bool
    let playtime: TimeInterval
    let lastPlayedAt: Date?
    var id: Int { rom.id }
}

enum PlatformFilter: String, CaseIterable, Identifiable {
    case all, onDevice, played
    var id: String { rawValue }
}

enum PlatformOrder: String, CaseIterable {
    case alphabetical, recentlyPlayed
}

struct PlatformContent {
    let platform: PlatformDTO
    var tier: EmbeddedSupportTier = .unsupported
    var games: [PlatformGame] = []
    var staleMessage: String?
    /// Nothing could be loaded (Android's copy): shown in place of the empty message.
    var errorMessage: String?
    var isLoading = false

    var installedCount: Int { games.filter(\.installed).count }
    var playedCount: Int { games.filter { $0.playtime > 0 }.count }
    var totalCount: Int { max(platform.romCount, games.count) }

    func filtered(_ filter: PlatformFilter, order: PlatformOrder) -> [PlatformGame] {
        let chosen: [PlatformGame] = switch filter {
        case .all: games
        case .onDevice: games.filter(\.installed)
        case .played: games.filter { $0.playtime > 0 }
        }
        switch order {
        case .alphabetical:
            return chosen.sorted { $0.rom.displayName.localizedStandardCompare($1.rom.displayName) == .orderedAscending }
        case .recentlyPlayed:
            return chosen.sorted { lhs, rhs in
                switch (lhs.lastPlayedAt, rhs.lastPlayedAt) {
                case let (l?, r?): return l > r
                case (.some, nil): return true
                case (nil, .some): return false
                default: return lhs.rom.displayName.localizedStandardCompare(rhs.rom.displayName) == .orderedAscending
                }
            }
        }
    }

    /// The empty state (both themes): an error when nothing could be loaded.
    func emptyTitle(_ filter: PlatformFilter) -> String { filter == .all ? "No games yet" : "Nothing here yet" }
    func emptyMessage(_ filter: PlatformFilter) -> String {
        if let errorMessage { return errorMessage }
        switch filter {
        case .all: return "This platform has no games on your server yet."
        case .onDevice: return "Download a game from its page to play it offline."
        case .played: return "Games you play on this device show up here."
        }
    }

    func count(_ filter: PlatformFilter) -> Int {
        switch filter {
        case .all: games.count
        case .onDevice: installedCount
        case .played: playedCount
        }
    }
}

/// Platform: its games. Aurora: a summary card and a grid of covers lit by the
/// most recent game. Riso: a platform opener with the most played covers, then
/// an A–Z index. Reached from Library; the detail column when split.
struct PlatformDetailView: View {
    @Environment(\.rommioTheme) private var theme
    let model: RommioAppModel
    let platform: PlatformDTO

    @State private var feature: PlatformDetailFeatureModel
    @State private var filter: PlatformFilter = .all
    @State private var order: PlatformOrder = .alphabetical

    init(model: RommioAppModel, platform: PlatformDTO) {
        self.model = model
        self.platform = platform
        _feature = State(initialValue: model.makePlatformDetailFeature(platform: platform))
    }

    var body: some View {
        Group {
            if theme.isRiso {
                RisoPlatformLayout(content: content, filter: $filter, order: $order)
            } else {
                AuroraPlatformLayout(content: content, filter: $filter, order: $order)
            }
        }
        .navigationTitle(platform.name)
        .accessibilityIdentifier("screen.platform")
        .task { await feature.refresh(forceRemote: false) }
        .refreshable { await feature.refresh(forceRemote: true) }
    }

    private var content: PlatformContent {
        var content = PlatformContent(platform: platform)
        content.tier = feature.sections.first?.supportTier ?? model.controlProfile(for: platform.slug).supportTier
        let installed = Set(model.downloadsFeature.records.filter { $0.status == .completed }.map(\.romID))
        let played = Dictionary(uniqueKeysWithValues: model.statsFeature.stats.games.map { ($0.romID, $0) })
        content.games = feature.roms.map { rom in
            PlatformGame(rom: rom, artworkURL: model.artworkURL(path: rom.urlCover), installed: installed.contains(rom.id),
                         playtime: played[rom.id]?.playtime ?? 0, lastPlayedAt: played[rom.id]?.lastPlayedAt)
        }
        content.staleMessage = feature.roms.isEmpty ? nil : feature.staleMessage
        content.errorMessage = feature.errorMessage
        content.isLoading = feature.isLoading
        return content
    }
}

private func filterTitle(_ filter: PlatformFilter) -> String {
    switch filter {
    case .all: "All"
    case .onDevice: "On this device"
    case .played: "Played"
    }
}

// MARK: Aurora

private struct AuroraPlatformLayout: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    let content: PlatformContent
    @Binding var filter: PlatformFilter
    @Binding var order: PlatformOrder

    var body: some View {
        let games = content.filtered(filter, order: order)
        let lead = content.filtered(.all, order: .recentlyPlayed).first
        ThemedScrollPage(style: .page) {
            summary
            VStack(alignment: .leading, spacing: 12) {
                Picker("Show", selection: $filter) {
                    ForEach(PlatformFilter.allCases) { option in
                        Text(filterTitle(option)).tag(option)
                    }
                }
                .pickerStyle(.segmented)
                .accessibilityIdentifier("platform.filter")
                Button {
                    order = order == .alphabetical ? .recentlyPlayed : .alphabetical
                } label: {
                    Label(order == .alphabetical ? "A–Z" : "Recently played", systemImage: "arrow.up.arrow.down")
                        .themeText(.bodyEmphasis, size: 14)
                        .foregroundStyle(theme.inkSecondary)
                }
                .buttonStyle(.plain)
                .frame(minHeight: 32)
                .accessibilityIdentifier("platform.order")
            }
            if games.isEmpty {
                EmptyStateView(title: content.isLoading ? "Loading games" : content.emptyTitle(filter), systemImage: "gamecontroller",
                               message: content.isLoading ? nil : content.emptyMessage(filter))
            } else {
                VStack(alignment: .leading, spacing: 14) {
                    SectionHeader(title: filterTitle(filter) == "All" ? "Games" : filterTitle(filter), detail: "\(games.count)")
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: horizontalSizeClass == .regular ? 128 : 104), spacing: 14, alignment: .top)],
                              alignment: .leading, spacing: 20) {
                        ForEach(games) { game in
                            NavigationLink(value: AppDestination.game(game.rom)) {
                                GridTile(game: game)
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("platform.game.\(game.rom.id)")
                        }
                    }
                }
            }
            if let staleMessage = content.staleMessage {
                Text(staleMessage).themeText(.caption).foregroundStyle(theme.inkSecondary)
            }
        }
        .litBy(lead?.artworkURL)
    }

    private var summary: some View {
        HStack(alignment: .center, spacing: 18) {
            figure(content.totalCount.formatted(), content.totalCount == 1 ? "game" : "games")
            figure(content.installedCount.formatted(), "on this device")
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 8) {
                SupportTierBadge(tier: content.tier)
                if content.playedCount > 0 {
                    StatusChip(title: "\(content.playedCount) played", tone: .ok)
                }
            }
        }
        .padding(18)
        .themeSurface()
    }

    private func figure(_ value: String, _ label: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value).themeText(.statValue, size: 32).foregroundStyle(theme.ink)
            Text(label).themeText(.caption).foregroundStyle(theme.inkSecondary)
        }
        .accessibilityElement(children: .combine)
    }

}

/// A cover with its title and when it was last played.
private struct GridTile: View {
    @Environment(\.rommioTheme) private var theme
    let game: PlatformGame

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            GameCover(url: game.artworkURL, title: game.rom.displayName, maxPointSize: 180)
                .aspectRatio(3 / 4, contentMode: .fit)
                .overlay(alignment: .topTrailing) {
                    if game.installed {
                        Image(systemName: "checkmark.circle.fill")
                            .font(.system(size: 15, weight: .semibold))
                            .symbolRenderingMode(.palette)
                            .foregroundStyle(theme.markInk, theme.ok)
                            .padding(6)
                            .accessibilityLabel("On this device")
                    }
                }
            VStack(alignment: .leading, spacing: 1) {
                Text(game.rom.displayName).themeText(.bodyEmphasis, size: 14).foregroundStyle(theme.ink).lineLimit(2)
                Text(game.lastPlayedAt.map { PlayFormat.relative($0) } ?? (game.installed ? "On this device" : "On your server"))
                    .themeText(.caption, size: 12)
                    .foregroundStyle(theme.inkSecondary)
                    .lineLimit(1)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

// MARK: Riso

private struct RisoPlatformLayout: View {
    @Environment(\.rommioTheme) private var theme
    let content: PlatformContent
    @Binding var filter: PlatformFilter
    @Binding var order: PlatformOrder

    var body: some View {
        let games = content.filtered(filter, order: order)
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                opener
                plates
                filters.padding(.top, 18)
                Text("Bold: on this device. Not downloaded entries are set lighter.")
                    .themeText(.caption, size: 12.5)
                    .foregroundStyle(theme.inkSecondary)
                    .padding(.vertical, 6)
                PrintRule(weight: .keyline)
                if games.isEmpty {
                    EmptyStateView(title: content.isLoading ? "Loading games" : content.emptyTitle(filter), systemImage: "gamecontroller",
                                   message: content.isLoading ? nil : content.emptyMessage(filter))
                } else if order == .alphabetical {
                    ForEach(letterGroups(games), id: \.letter) { group in
                        HStack(alignment: .top, spacing: 0) {
                            Text(group.letter)
                                .themeText(.statValue, size: 30)
                                .foregroundStyle(theme.accent2)
                                .frame(width: 30, alignment: .leading)
                                .padding(.top, 8)
                                .accessibilityHidden(true)
                            VStack(spacing: 0) {
                                ForEach(Array(group.games.enumerated()), id: \.element.id) { index, game in
                                    if index > 0 { Rectangle().fill(theme.hairline).frame(height: 1) }
                                    entry(game)
                                }
                            }
                        }
                        .overlay(alignment: .bottom) { Rectangle().fill(theme.hairline).frame(height: 1) }
                    }
                } else {
                    ForEach(games) { game in
                        entry(game).overlay(alignment: .bottom) { Rectangle().fill(theme.hairline).frame(height: 1) }
                    }
                }
                if let staleMessage = content.staleMessage {
                    Text(staleMessage).themeText(.caption).foregroundStyle(theme.inkSecondary).padding(.top, 16)
                }
            }
            .padding(.horizontal, theme.spacing.gutter)
            .padding(.bottom, 40)
            .frame(maxWidth: 760)
            .frame(maxWidth: .infinity)
        }
        .themedPage()
        .inlineNavigationTitle()
    }

    private var opener: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Platform")
                Spacer()
                Text(content.tier.title)
                    .foregroundStyle(theme.inkSecondary)
            }
            .themeText(.label, size: 10.5)
            .foregroundStyle(theme.ink)
            .padding(.bottom, 6)
            .overlay(alignment: .bottom) { Rectangle().fill(theme.ink).frame(height: 3) }
            DisplayText(text: content.platform.name, role: .display, size: 56, lineLimit: 2)
                .padding(.top, 4)
            Text("\(PlayFormat.games(content.totalCount)) on your server, \(PlayFormat.count(content.installedCount)) on this device.")
                .themeText(.body, size: 16.5)
                .foregroundStyle(theme.ink)
            HStack(spacing: 12) {
                SupportTierBadge(tier: content.tier)
                if content.playedCount > 0 {
                    StatusChip(title: "\(content.playedCount) played", tone: .ok)
                }
            }
        }
    }

    /// The most played covers, printed (or the first on this device).
    @ViewBuilder
    private var plates: some View {
        let played = content.games.filter { $0.playtime > 0 }.sorted { $0.playtime > $1.playtime }
        let picks = Array((played.isEmpty ? content.games.filter(\.installed) : played).prefix(4))
        if !picks.isEmpty {
            VStack(alignment: .leading, spacing: 9) {
                HStack(alignment: .top, spacing: 12) {
                    ForEach(picks) { game in
                        NavigationLink(value: AppDestination.game(game.rom)) {
                            VStack(alignment: .leading, spacing: 3) {
                                GameCover(url: game.artworkURL, title: game.rom.displayName, maxPointSize: 140)
                                    .aspectRatio(3 / 4, contentMode: .fit)
                                    .padding(.bottom, 4)
                                Text(game.rom.displayName).themeText(.bodyEmphasis, size: 12.5).foregroundStyle(theme.ink).lineLimit(2)
                                Text(game.playtime > 0 ? PlayFormat.duration(game.playtime) : "On this device")
                                    .themeText(.label, size: 9.5).foregroundStyle(theme.inkSecondary)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .buttonStyle(.plain)
                    }
                    ForEach(0 ..< max(0, 4 - picks.count), id: \.self) { _ in Color.clear.frame(maxWidth: .infinity) }
                }
                Text(played.isEmpty ? "On this device" : "Most played on this platform")
                    .themeText(.caption, size: 12.5)
                    .foregroundStyle(theme.inkSecondary)
                    .padding(.top, 6)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .overlay(alignment: .top) { Rectangle().fill(theme.ink).frame(height: 1) }
            }
            .padding(.top, 18)
        }
    }

    private var filters: some View {
        // The segmented filter keeps the full width; the order sits under it (its
        // label is the same in both themes, "Recently played").
        VStack(alignment: .leading, spacing: 4) {
            Picker("Show", selection: $filter) {
                ForEach(PlatformFilter.allCases) { option in
                    Text(filterTitle(option)).tag(option)
                }
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("platform.filter")
            Button {
                order = order == .alphabetical ? .recentlyPlayed : .alphabetical
            } label: {
                Label(order == .alphabetical ? "A–Z" : "Recently played", systemImage: "arrow.up.arrow.down")
                    .themeText(.label, size: 11)
                    .foregroundStyle(theme.ink)
            }
            .buttonStyle(.plain)
            .frame(minHeight: 44)
            .accessibilityIdentifier("platform.order")
        }
    }

    private func entry(_ game: PlatformGame) -> some View {
        NavigationLink(value: AppDestination.game(game.rom)) {
            HStack(alignment: .center, spacing: 8) {
                Text(game.rom.displayName)
                    .themeText(game.installed ? .bodyEmphasis : .body, size: 16.5)
                    .foregroundStyle(game.installed ? theme.ink : theme.inkSecondary)
                    .lineLimit(1)
                    .layoutPriority(1)
                DotLeader()
                Text(game.playtime > 0 ? PlayFormat.duration(game.playtime) : (game.installed ? "Ready" : ""))
                    .themeText(.numeral, size: 11.5)
                    .foregroundStyle(theme.ink)
                    .lineLimit(1)
                    .fixedSize()
                Group {
                    if game.installed {
                        Color.clear
                    } else {
                        Image(systemName: "arrow.down.to.line")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(theme.ground)
                            .frame(width: 26, height: 26)
                            .background(theme.ink)
                            .accessibilityLabel("Not downloaded")
                    }
                }
                .frame(width: 34, alignment: .trailing)
            }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(RowButtonStyle())
        .accessibilityIdentifier("platform.game.\(game.rom.id)")
    }

    private func letterGroups(_ games: [PlatformGame]) -> [(letter: String, games: [PlatformGame])] {
        var order: [String] = []
        var groups: [String: [PlatformGame]] = [:]
        for game in games {
            let first = game.rom.displayName.first.map { String($0).uppercased() } ?? "#"
            let letter = first.rangeOfCharacter(from: .letters) == nil ? "#" : first
            if groups[letter] == nil { order.append(letter) }
            groups[letter, default: []].append(game)
        }
        return order.map { ($0, groups[$0]!) }
    }
}
