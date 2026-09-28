import SwiftUI
import RommioContract
import RommioFoundation
import RommioPlayerKit

/// What the Library's detail column shows.
enum LibrarySelection: Hashable {
    case platform(PlatformDTO)
    case collection(RommCollectionDTO)
}

/// A game in the Library's browse row, with its cover.
struct LibraryGame: Identifiable, Hashable {
    let rom: RomDTO
    let artworkURL: URL?
    var id: Int { rom.id }
}

/// A collection with its cover.
struct LibraryCollection: Identifiable, Hashable {
    let collection: RommCollectionDTO
    let artworkURL: URL?
    var id: String { collection.id }
}

/// An era with games (Aurora's ruler): its games by release year, the years they
/// span and the covers that give it its light.
struct LibraryEraGroup: Identifiable, Hashable {
    let era: LibraryEra
    let games: [LibraryGame]
    let span: String
    /// Up to three games for its light: games in progress first (nil: no cover).
    let lightURLs: [URL?]
    var id: String { era.id }
}

/// Everything the Library layouts draw, built once per change of the feature
/// models (`LibraryContent.build`).
struct LibraryContent: Equatable {
    var sections: [PlatformSupportSection] = [] {
        didSet {
            platforms = sections.flatMap(\.platforms)
            numbers = Dictionary(platforms.enumerated().map { ($0.element.id, $0.offset + 1) }, uniquingKeysWith: { first, _ in first })
            tiers = Dictionary(sections.flatMap { section in section.platforms.map { ($0.slug, section.supportTier) } }, uniquingKeysWith: { first, _ in first })
        }
    }
    var collectionSections: [CollectionTypeSection] = []
    /// Downloaded games per platform slug.
    var installedByPlatform: [String: Int] = [:]
    /// The cached library with covers, for browsing.
    var games: [LibraryGame] = [] {
        didSet { eras = LibraryEra.groups(games, inProgress: inProgress) }
    }
    /// Games in progress, most recent first (their covers lead the browse row).
    var inProgress: [RomDTO] = [] {
        didSet { eras = LibraryEra.groups(games, inProgress: inProgress) }
    }
    /// Games downloaded to this device.
    var installedRomIDs: Set<Int> = []
    /// Collections with their covers.
    var collections: [LibraryCollection] = []
    var staleMessage: String?
    /// Nothing could be loaded (shown in the empty state).
    var errorMessage: String?
    var isLoading = false

    private(set) var platforms: [PlatformDTO] = []
    /// Contents numbers (Riso): each platform's place in the tiers.
    private(set) var numbers: [Int: Int] = [:]
    private(set) var tiers: [String: EmbeddedSupportTier] = [:]
    /// Eras of the whole cached library (the ruler shows with at least two).
    private(set) var eras: [LibraryEraGroup] = []

    var totalGames: Int { platforms.reduce(0) { $0 + $1.romCount } }
    var installedCount: Int { installedByPlatform.values.reduce(0, +) }
    var isEmpty: Bool { sections.isEmpty && collectionSections.isEmpty }
    var roms: [RomDTO] { games.map(\.rom) }

    /// "2,164 games · 22 platforms · 91 on this device".
    var summary: String {
        "\(PlayFormat.games(totalGames)) · \(platforms.count) platform\(platforms.count == 1 ? "" : "s") · \(installedCount.formatted()) on this device"
    }

    func installed(_ platform: PlatformDTO) -> Int { installedByPlatform[platform.slug] ?? 0 }

    func tier(of platform: PlatformDTO) -> EmbeddedSupportTier { tiers[platform.slug] ?? .unsupported }

    func number(of platform: PlatformDTO) -> Int { numbers[platform.id] ?? 1 }

    /// The Library from the feature models.
    @MainActor
    static func build(_ model: RommioAppModel) -> LibraryContent {
        let feature = model.libraryFeature
        let collections = model.collectionsFeature
        var content = LibraryContent()
        content.sections = feature.sections
        content.collectionSections = collections.sections
        var installed: [String: Int] = [:]
        var counted = Set<Int>()
        for record in model.downloadsFeature.records where record.status == .completed && counted.insert(record.romID).inserted {
            installed[record.platformSlug, default: 0] += 1
        }
        content.installedRomIDs = counted
        content.installedByPlatform = installed
        content.inProgress = PlayContent.inPlay(romm: model.playFeature.snapshot.continuePlaying, latest: model.savesFeature.latest,
                                                playtimes: model.statsFeature.stats.games, library: model.statsFeature.games)
        content.games = model.statsFeature.games.values
            .sorted { $0.id < $1.id }
            .map { LibraryGame(rom: $0, artworkURL: model.artworkURL(path: $0.urlCover)) }
        content.collections = content.collectionSections.flatMap(\.collections).map {
            LibraryCollection(collection: $0, artworkURL: model.artworkURL(path: $0.pathCoverLarge ?? $0.pathCoverSmall))
        }
        content.staleMessage = feature.staleMessage ?? collections.staleMessage
        content.errorMessage = feature.errorMessage
        // Loading only shows in an empty state: a refresh of a full page doesn't republish it.
        content.isLoading = feature.isLoading && content.isEmpty
        return content
    }
}

/// Library: platforms grouped by how they play, and collections. Aurora browses
/// covers with platforms and tiers as filters; Riso sets a contents page. On a
/// regular-width display (iPhone Duo open) platforms and collections sit in a
/// narrow list beside the selected one, on one shared background.
struct LibraryPage: View {
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    let model: RommioAppModel
    let feature: LibraryFeatureModel
    let collections: CollectionsFeatureModel

    var body: some View {
        Group {
            if horizontalSizeClass == .regular {
                LibrarySplit(model: model, content: model.libraryContent.value, refresh: refresh)
            } else {
                LibraryStack(model: model, content: model.libraryContent.value, refresh: refresh)
            }
        }
        .task {
            await feature.refresh(forceRemote: false)
            await collections.refresh(forceRemote: false)
        }
    }

    private func refresh() async {
        await feature.refresh(forceRemote: true)
        await collections.refresh(forceRemote: true)
    }
}

struct LibraryActions: PageActions {
    let openPlatform: (PlatformDTO) -> Void
    let openCollection: (RommCollectionDTO) -> Void
    let openGame: (RomDTO) -> Void
}

/// Compact width: the theme's browse page on a navigation stack.
private struct LibraryStack: View {
    @Environment(\.rommioTheme) private var theme
    let model: RommioAppModel
    let content: LibraryContent
    let refresh: () async -> Void
    @State private var path = NavigationPath()

    var body: some View {
        NavigationStack(path: $path) {
            LibraryBrowse(content: content, actions: actions, riso: theme.isRiso)
                .accessibilityIdentifier("shell.libraryList")
                .refreshable { await refresh() }
                .navigationTitle("Library")
                .appDestinations(model)
        }
        .appNavigator($path)
    }

    private var actions: LibraryActions {
        let path = $path
        return LibraryActions(
            openPlatform: { path.wrappedValue.append(AppDestination.platform($0)) },
            openCollection: { path.wrappedValue.append(AppDestination.collection($0)) },
            openGame: { path.wrappedValue.append(AppDestination.game($0)) }
        )
    }
}

private struct LibraryBrowse: View {
    let content: LibraryContent
    let actions: LibraryActions
    let riso: Bool

    var body: some View {
        if riso {
            RisoLibraryLayout(content: content, actions: actions)
        } else {
            AuroraLibraryLayout(content: content, actions: actions)
        }
    }
}

// MARK: Regular width: a narrow list beside the selection

private struct LibrarySplit: View {
    let model: RommioAppModel
    let content: LibraryContent
    let refresh: () async -> Void
    @State private var selection: LibrarySelection?
    @State private var columnVisibility: NavigationSplitViewVisibility = .all
    @State private var detailPath = NavigationPath()

    var body: some View {
        NavigationSplitView(columnVisibility: $columnVisibility) {
            LibrarySidebar(content: content, selection: $selection)
                .navigationTitle("Library")
                .accessibilityIdentifier("shell.libraryList")
                .refreshable { await refresh() }
        } detail: {
            NavigationStack(path: $detailPath) {
                LibraryDetail(model: model, selection: selection)
                    .appDestinations(model)
            }
            .appNavigator($detailPath)
        }
        .navigationSplitViewStyle(.balanced)
        .onChange(of: content.platforms.first?.id, initial: true) { _, _ in
            if selection == nil, let first = content.platforms.first {
                selection = .platform(first)
            }
        }
    }
}

private struct LibraryDetail: View {
    let model: RommioAppModel
    let selection: LibrarySelection?

    var body: some View {
        switch selection {
        case let .platform(platform):
            PlatformDetailView(model: model, platform: platform).id(platform.id)
        case let .collection(collection):
            CollectionDetailView(model: model, collection: collection).id(collection.id)
        case nil:
            EmptyStateView(title: "Choose a platform or collection", systemImage: "books.vertical")
                .frame(maxHeight: .infinity)
                .background { PageBackground() }
        }
    }
}

/// The regular-width sidebar: platforms by tier, then collections, as a
/// selectable list in the theme (glass rows or printed contents rows).
private struct LibrarySidebar: View {
    let content: LibraryContent
    @Binding var selection: LibrarySelection?

    var body: some View {
        List(selection: $selection) {
            if content.isEmpty {
                EmptyStateView(title: "No platforms yet", systemImage: "square.stack.3d.up.slash",
                               message: content.errorMessage ?? "Pull to refresh once your server is reachable.")
                    .listRowBackground(Color.clear)
            }
            ForEach(content.sections) { section in
                Section {
                    ForEach(section.platforms) { platform in
                        LibrarySidebarPlatform(platform: platform, number: content.number(of: platform), installed: content.installed(platform))
                            .tag(LibrarySelection.platform(platform))
                            .accessibilityIdentifier("library.platform.\(platform.slug)")
                    }
                } header: {
                    SectionHeader(title: section.supportTier.title, detail: "\(section.platforms.count)")
                }
                .themedRow()
            }
            ForEach(content.collectionSections) { section in
                Section {
                    ForEach(section.collections) { collection in
                        ListRow(title: collection.name, subtitle: PlayFormat.games(collection.romCount))
                            .tag(LibrarySelection.collection(collection))
                            .accessibilityIdentifier("library.collection.\(collection.id)")
                    }
                } header: {
                    SectionHeader(title: section.title, detail: "\(section.collections.count)")
                }
                .themedRow()
            }
        }
        .themedList()
    }
}

private struct LibrarySidebarPlatform: View {
    @Environment(\.rommioTheme) private var theme
    let platform: PlatformDTO
    let number: Int
    let installed: Int

    var body: some View {
        if theme.isRiso {
            ChapterRow(number: number, platform: platform, installed: installed, compact: true)
        } else {
            ListRow(title: platform.name, subtitle: "\(PlayFormat.games(platform.romCount)) · \(installed) here")
        }
    }
}

/// A platform as a contents entry (Riso): its number, name, dot leaders, the
/// games on the server and those on this device.
struct ChapterRow: View {
    @Environment(\.rommioTheme) private var theme
    let number: Int
    let platform: PlatformDTO
    let installed: Int
    var compact = false
    var dimmed = false

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text(String(format: "%02d", number))
                .themeText(.headline, size: 19)
                .foregroundStyle(theme.accent2)
                .frame(width: 28, alignment: .leading)
            VStack(alignment: .leading, spacing: 2) {
                Text(platform.name)
                    .themeText(.body, size: compact ? 16 : 17)
                    .foregroundStyle(dimmed ? theme.inkSecondary : theme.ink)
                    .lineLimit(2)
                Text(platform.slug.uppercased())
                    .themeText(.label, size: 9.5)
                    .foregroundStyle(theme.inkSecondary)
            }
            .layoutPriority(1)
            DotLeader()
            Text(platform.romCount.formatted())
                .themeText(.numeral, size: 12.5)
                .foregroundStyle(theme.ink)
                .frame(minWidth: 34, alignment: .trailing)
            ChapterInstalled(installed: installed)
                .frame(width: compact ? 30 : 44, alignment: .trailing)
        }
        .padding(.vertical, 6)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(platform.name), \(PlayFormat.games(platform.romCount)), \(installed) on this device")
    }
}

private struct ChapterInstalled: View {
    @Environment(\.rommioTheme) private var theme
    let installed: Int

    var body: some View {
        if installed > 0 {
            Text("\(installed)")
                .themeText(.numeral, size: 11.5)
                .foregroundStyle(theme.ground)
                .padding(.horizontal, 5)
                .padding(.vertical, 2)
                .background(theme.ink)
        } else {
            Text("–").themeText(.numeral, size: 11.5).foregroundStyle(theme.inkSecondary)
        }
    }
}
