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
    var recentKey: String { "recent.\(rom.id)" }
}

enum PlatformFilter: String, CaseIterable, Identifiable {
    case all, onDevice, played
    var id: String { rawValue }
}

enum PlatformOrder: String, CaseIterable {
    case alphabetical, recentlyPlayed
}

/// One letter of the A–Z index (Riso).
struct PlatformLetterGroup: Hashable, Identifiable {
    let letter: String
    let games: [PlatformGame]
    var id: String { letter }
}

/// One filter's games in both orders, and its A–Z index.
struct PlatformGameList: Hashable {
    var alphabetical: [PlatformGame] = []
    var recentlyPlayed: [PlatformGame] = []
    var letters: [PlatformLetterGroup] = []

    func games(_ order: PlatformOrder) -> [PlatformGame] { order == .alphabetical ? alphabetical : recentlyPlayed }
}

/// Everything the Platform layouts draw: built once per change of the platform's
/// games (`PlatformContent.build`), sorted and grouped for every filter, so
/// switching the filter or the order only picks a list.
struct PlatformContent: Equatable {
    let platform: PlatformDTO
    var tier: EmbeddedSupportTier = .unsupported
    var games: [PlatformGame] = [] {
        didSet { prepare() }
    }
    var staleMessage: String?
    /// Nothing could be loaded (Android's copy): shown in place of the empty message.
    var errorMessage: String?
    var isLoading = false

    private(set) var lists: [PlatformFilter: PlatformGameList] = [:]
    private(set) var installedCount = 0
    private(set) var playedCount = 0
    /// The most played covers, printed (or the first on this device), and whether they are the played ones (Riso).
    private(set) var plates: [PlatformGame] = []
    private(set) var platesArePlayed = false

    init(platform: PlatformDTO) {
        self.platform = platform
    }

    var totalCount: Int { max(platform.romCount, games.count) }
    /// The most recently played game (Aurora lights the page with it).
    var lead: PlatformGame? { lists[.all]?.recentlyPlayed.first }

    func filtered(_ filter: PlatformFilter, order: PlatformOrder) -> [PlatformGame] {
        lists[filter]?.games(order) ?? []
    }

    func letters(_ filter: PlatformFilter) -> [PlatformLetterGroup] { lists[filter]?.letters ?? [] }

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

    private mutating func prepare() {
        installedCount = games.filter(\.installed).count
        playedCount = games.filter { $0.playtime > 0 }.count
        let alphabetical = games.sorted { $0.rom.displayName.localizedStandardCompare($1.rom.displayName) == .orderedAscending }
        let recent = games.sorted { lhs, rhs in
            switch (lhs.lastPlayedAt, rhs.lastPlayedAt) {
            case let (l?, r?): return l > r
            case (.some, nil): return true
            case (nil, .some): return false
            default: return lhs.rom.displayName.localizedStandardCompare(rhs.rom.displayName) == .orderedAscending
            }
        }
        var lists: [PlatformFilter: PlatformGameList] = [:]
        for filter in PlatformFilter.allCases {
            let keep: (PlatformGame) -> Bool = switch filter {
            case .all: { _ in true }
            case .onDevice: { $0.installed }
            case .played: { $0.playtime > 0 }
            }
            let sorted = alphabetical.filter(keep)
            lists[filter] = PlatformGameList(alphabetical: sorted, recentlyPlayed: recent.filter(keep), letters: Self.letterGroups(sorted))
        }
        self.lists = lists
        let played = games.filter { $0.playtime > 0 }.sorted { $0.playtime > $1.playtime }
        platesArePlayed = !played.isEmpty
        plates = Array((played.isEmpty ? games.filter(\.installed) : played).prefix(4))
    }

    private static func letterGroups(_ games: [PlatformGame]) -> [PlatformLetterGroup] {
        var order: [String] = []
        var groups: [String: [PlatformGame]] = [:]
        for game in games {
            let first = game.rom.displayName.first.map { String($0).uppercased() } ?? "#"
            let letter = first.rangeOfCharacter(from: .letters) == nil ? "#" : first
            if groups[letter] == nil { order.append(letter) }
            groups[letter, default: []].append(game)
        }
        return order.map { PlatformLetterGroup(letter: $0, games: groups[$0]!) }
    }

    /// The platform's games from its feature model, with play and downloads on this device.
    @MainActor
    static func build(_ feature: PlatformDetailFeatureModel, model: RommioAppModel) -> PlatformContent {
        var content = PlatformContent(platform: feature.platform)
        content.tier = feature.sections.first?.supportTier ?? model.controlProfile(for: feature.platform.slug).supportTier
        let installed = model.installedRomIDs
        let played = Dictionary(model.statsFeature.stats.games.map { ($0.romID, $0) }, uniquingKeysWith: { first, _ in first })
        content.games = feature.roms.map { rom in
            PlatformGame(rom: rom, artworkURL: model.artworkURL(path: rom.urlCover), installed: installed.contains(rom.id),
                         playtime: played[rom.id]?.playtime ?? 0, lastPlayedAt: played[rom.id]?.lastPlayedAt)
        }
        content.staleMessage = feature.roms.isEmpty ? nil : feature.staleMessage
        content.errorMessage = feature.errorMessage
        // Loading only shows in an empty list: a refresh of a full page doesn't republish it.
        content.isLoading = feature.isLoading && (content.games.isEmpty || content.installedCount == 0 || content.playedCount == 0)
        return content
    }
}

/// A platform's feature model and its page content.
@MainActor
final class PlatformPageModel {
    let feature: PlatformDetailFeatureModel
    let content: DerivedContent<PlatformContent>

    init(model: RommioAppModel, platform: PlatformDTO) {
        let feature = model.makePlatformDetailFeature(platform: platform)
        self.feature = feature
        content = DerivedContent { [unowned model] in PlatformContent.build(feature, model: model) }
    }
}

/// Platform: its games. Aurora: a summary card and a grid of covers lit by the
/// most recent game. Riso: a platform opener with the most played covers, then
/// an A–Z index. Reached from Library; the detail column when split.
struct PlatformDetailView: View {
    @Environment(\.rommioTheme) private var theme
    let platform: PlatformDTO
    @State private var page: PlatformPageModel
    @State private var filter: PlatformFilter = .all
    @State private var order: PlatformOrder = .alphabetical

    init(model: RommioAppModel, platform: PlatformDTO) {
        self.platform = platform
        _page = State(initialValue: PlatformPageModel(model: model, platform: platform))
    }

    var body: some View {
        PlatformLayout(content: page.content.value, filter: $filter, order: $order, riso: theme.isRiso)
            .navigationTitle(platform.name)
            .accessibilityIdentifier("screen.platform")
            .task { await page.feature.refresh(forceRemote: false) }
            .refreshable { await page.feature.refresh(forceRemote: true) }
    }
}

private struct PlatformLayout: View {
    let content: PlatformContent
    @Binding var filter: PlatformFilter
    @Binding var order: PlatformOrder
    let riso: Bool

    var body: some View {
        if riso {
            RisoPlatformLayout(content: content, filter: $filter, order: $order)
        } else {
            AuroraPlatformLayout(content: content, filter: $filter, order: $order)
        }
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

struct AuroraPlatformLayout: View {
    @Environment(\.rommioTheme) private var theme
    let content: PlatformContent
    @Binding var filter: PlatformFilter
    @Binding var order: PlatformOrder

    var body: some View {
        let games = content.filtered(filter, order: order)
        ThemedScrollPage(style: .page) {
            AuroraPlatformSummary(total: content.totalCount, installed: content.installedCount, played: content.playedCount, tier: content.tier)
            AuroraPlatformControls(filter: $filter, order: $order)
            if games.isEmpty {
                EmptyStateView(title: content.isLoading ? "Loading games" : content.emptyTitle(filter), systemImage: "gamecontroller",
                               message: content.isLoading ? nil : content.emptyMessage(filter))
            } else {
                AuroraPlatformGrid(title: filterTitle(filter) == "All" ? "Games" : filterTitle(filter), games: games)
            }
            if let staleMessage = content.staleMessage {
                Text(staleMessage).themeText(.caption).foregroundStyle(theme.inkSecondary)
            }
        }
        .litBy(content.lead?.artworkURL)
    }
}

private struct AuroraPlatformSummary: View {
    let total: Int
    let installed: Int
    let played: Int
    let tier: EmbeddedSupportTier

    var body: some View {
        HStack(alignment: .center, spacing: 18) {
            PlatformFigure(value: total.formatted(), label: total == 1 ? "game" : "games")
            PlatformFigure(value: installed.formatted(), label: "on this device")
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 8) {
                SupportTierBadge(tier: tier)
                if played > 0 {
                    StatusChip(title: "\(played) played", tone: .ok)
                }
            }
        }
        .padding(18)
        .themeSurface()
    }
}

private struct PlatformFigure: View {
    @Environment(\.rommioTheme) private var theme
    let value: String
    let label: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value).themeText(.statValue, size: 32).foregroundStyle(theme.ink)
            Text(label).themeText(.caption).foregroundStyle(theme.inkSecondary)
        }
        .accessibilityElement(children: .combine)
    }
}

private struct AuroraPlatformControls: View {
    @Environment(\.rommioTheme) private var theme
    @Binding var filter: PlatformFilter
    @Binding var order: PlatformOrder

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            PlatformFilterPicker(filter: $filter)
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
    }
}

private struct PlatformFilterPicker: View {
    @Binding var filter: PlatformFilter

    var body: some View {
        Picker("Show", selection: $filter) {
            ForEach(PlatformFilter.allCases) { option in
                Text(filterTitle(option)).tag(option)
            }
        }
        .pickerStyle(.segmented)
        .accessibilityIdentifier("platform.filter")
    }
}

private struct AuroraPlatformGrid: View {
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    let title: String
    let games: [PlatformGame]

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            SectionHeader(title: title, detail: "\(games.count)")
            LazyVGrid(columns: [GridItem(.adaptive(minimum: horizontalSizeClass == .regular ? 128 : 104), spacing: 14, alignment: .top)],
                      alignment: .leading, spacing: 20) {
                ForEach(games) { game in
                    PushButton(value: AppDestination.game(game.rom)) {
                        GridTile(game: game)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("platform.game.\(game.rom.id)")
                }
            }
        }
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

struct RisoPlatformLayout: View {
    @Environment(\.rommioTheme) private var theme
    let content: PlatformContent
    @Binding var filter: PlatformFilter
    @Binding var order: PlatformOrder

    var body: some View {
        let games = content.filtered(filter, order: order)
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                RisoPlatformOpener(platform: content.platform, tier: content.tier, total: content.totalCount,
                                   installed: content.installedCount, played: content.playedCount)
                if !content.plates.isEmpty {
                    RisoPlatformPlates(picks: content.plates, played: content.platesArePlayed).padding(.top, 18)
                }
                RisoPlatformControls(filter: $filter, order: $order).padding(.top, 18)
                RisoIndexNote()
                PrintRule(weight: .keyline)
                if games.isEmpty {
                    EmptyStateView(title: content.isLoading ? "Loading games" : content.emptyTitle(filter), systemImage: "gamecontroller",
                                   message: content.isLoading ? nil : content.emptyMessage(filter))
                } else if order == .alphabetical {
                    // Every entry is its own item: the index is built as it scrolls in.
                    ForEach(content.letters(filter)) { group in
                        ForEach(Array(group.games.enumerated()), id: \.element.id) { index, game in
                            RisoIndexRow(letter: index == 0 ? group.letter : nil, game: game, first: index == 0, last: index == group.games.count - 1)
                        }
                    }
                } else {
                    // Keyed apart from the A–Z rows so switching the order never reuses a row.
                    ForEach(games, id: \.recentKey) { game in
                        RisoPlatformEntry(game: game)
                            .overlay(alignment: .bottom) { RisoHairline() }
                    }
                }
                if let staleMessage = content.staleMessage {
                    RisoPlatformStale(message: staleMessage)
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
}

private struct RisoHairline: View {
    @Environment(\.rommioTheme) private var theme
    var body: some View { Rectangle().fill(theme.hairline).frame(height: 1) }
}

private struct RisoIndexNote: View {
    @Environment(\.rommioTheme) private var theme

    var body: some View {
        Text("Bold: on this device. Not downloaded entries are set lighter.")
            .themeText(.caption, size: 12.5)
            .foregroundStyle(theme.inkSecondary)
            .padding(.vertical, 6)
    }
}

private struct RisoPlatformStale: View {
    @Environment(\.rommioTheme) private var theme
    let message: String

    var body: some View {
        Text(message).themeText(.caption).foregroundStyle(theme.inkSecondary).padding(.top, 16)
    }
}

private struct RisoPlatformOpener: View {
    @Environment(\.rommioTheme) private var theme
    let platform: PlatformDTO
    let tier: EmbeddedSupportTier
    let total: Int
    let installed: Int
    let played: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Platform")
                Spacer()
                Text(tier.title)
                    .foregroundStyle(theme.inkSecondary)
            }
            .themeText(.label, size: 10.5)
            .foregroundStyle(theme.ink)
            .padding(.bottom, 6)
            .overlay(alignment: .bottom) { Rectangle().fill(theme.ink).frame(height: 3) }
            DisplayText(text: platform.name, role: .display, size: 56, lineLimit: 2)
                .padding(.top, 4)
            Text("\(PlayFormat.games(total)) on your server, \(PlayFormat.count(installed)) on this device.")
                .themeText(.body, size: 16.5)
                .foregroundStyle(theme.ink)
            HStack(spacing: 12) {
                SupportTierBadge(tier: tier)
                if played > 0 {
                    StatusChip(title: "\(played) played", tone: .ok)
                }
            }
        }
    }
}

/// The most played covers, printed (or the first on this device).
private struct RisoPlatformPlates: View {
    @Environment(\.rommioTheme) private var theme
    let picks: [PlatformGame]
    let played: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack(alignment: .top, spacing: 12) {
                ForEach(picks) { game in
                    PushButton(value: AppDestination.game(game.rom)) {
                        RisoPlate(game: game)
                    }
                    .buttonStyle(.plain)
                }
                ForEach(0 ..< max(0, 4 - picks.count), id: \.self) { _ in Color.clear.frame(maxWidth: .infinity) }
            }
            Text(played ? "Most played on this platform" : "On this device")
                .themeText(.caption, size: 12.5)
                .foregroundStyle(theme.inkSecondary)
                .padding(.top, 6)
                .frame(maxWidth: .infinity, alignment: .leading)
                .overlay(alignment: .top) { Rectangle().fill(theme.ink).frame(height: 1) }
        }
    }
}

private struct RisoPlate: View {
    @Environment(\.rommioTheme) private var theme
    let game: PlatformGame

    var body: some View {
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
}

private struct RisoPlatformControls: View {
    @Environment(\.rommioTheme) private var theme
    @Binding var filter: PlatformFilter
    @Binding var order: PlatformOrder

    var body: some View {
        // The segmented filter keeps the full width; the order sits under it (its
        // label is the same in both themes, "Recently played").
        VStack(alignment: .leading, spacing: 4) {
            PlatformFilterPicker(filter: $filter)
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
}

/// One entry of the A–Z index: the letter beside the first entry of its group,
/// a rule between entries and one under the group.
private struct RisoIndexRow: View {
    @Environment(\.rommioTheme) private var theme
    let letter: String?
    let game: PlatformGame
    let first: Bool
    let last: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            // The letter hangs in the margin of its group's first entry.
            Color.clear
                .frame(width: 30, height: 1)
                .overlay(alignment: .topLeading) {
                    if let letter {
                        Text(letter)
                            .themeText(.statValue, size: 30)
                            .foregroundStyle(theme.accent2)
                            .fixedSize()
                            .padding(.top, 8)
                            .accessibilityHidden(true)
                    }
                }
            VStack(spacing: 0) {
                if !first { RisoHairline() }
                RisoPlatformEntry(game: game)
            }
        }
        .overlay(alignment: .bottom) { if last { RisoHairline() } }
    }
}

private struct RisoPlatformEntry: View {
    @Environment(\.rommioTheme) private var theme
    let game: PlatformGame

    var body: some View {
        PushButton(value: AppDestination.game(game.rom)) {
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
                RisoDownloadMark(installed: game.installed)
                    .frame(width: 34, alignment: .trailing)
            }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(RowButtonStyle())
        .accessibilityIdentifier("platform.game.\(game.rom.id)")
    }
}

private struct RisoDownloadMark: View {
    @Environment(\.rommioTheme) private var theme
    let installed: Bool

    var body: some View {
        if installed {
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
}
