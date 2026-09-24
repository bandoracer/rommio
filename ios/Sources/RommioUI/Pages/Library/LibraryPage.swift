import SwiftUI
import RommioContract
import RommioFoundation
import RommioPlayerKit

/// What the Library's detail column shows.
enum LibrarySelection: Hashable {
    case platform(PlatformDTO)
    case collection(RommCollectionDTO)
}

/// Everything the Library layouts draw.
struct LibraryContent {
    var sections: [PlatformSupportSection] = []
    var collectionSections: [CollectionTypeSection] = []
    /// Downloaded games per platform slug.
    var installedByPlatform: [String: Int] = [:]
    /// The cached library, for browsing covers.
    var roms: [RomDTO] = []
    /// Games in progress, most recent first (their covers lead the browse row).
    var inProgress: [RomDTO] = []
    /// Games downloaded to this device.
    var installedRomIDs: Set<Int> = []
    var staleMessage: String?
    /// Nothing could be loaded (shown in the empty state).
    var errorMessage: String?
    var isLoading = false

    var platforms: [PlatformDTO] { sections.flatMap(\.platforms) }
    var collections: [RommCollectionDTO] { collectionSections.flatMap(\.collections) }
    var totalGames: Int { platforms.reduce(0) { $0 + $1.romCount } }
    var installedCount: Int { installedByPlatform.values.reduce(0, +) }
    var isEmpty: Bool { sections.isEmpty && collectionSections.isEmpty }

    /// "2,164 games · 22 platforms · 91 on this device".
    var summary: String {
        "\(PlayFormat.games(totalGames)) · \(platforms.count) platform\(platforms.count == 1 ? "" : "s") · \(installedCount.formatted()) on this device"
    }

    func installed(_ platform: PlatformDTO) -> Int { installedByPlatform[platform.slug] ?? 0 }

    func tier(of platform: PlatformDTO) -> EmbeddedSupportTier {
        sections.first { $0.platforms.contains(platform) }?.supportTier ?? .unsupported
    }
}

/// Library: platforms grouped by how they play, and collections. Aurora browses
/// covers with platforms and tiers as filters; Riso sets a contents page. On a
/// regular-width display (iPhone Duo open) platforms and collections sit in a
/// narrow list beside the selected one, on one shared background.
struct LibraryPage: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    let model: RommioAppModel
    let feature: LibraryFeatureModel
    let collections: CollectionsFeatureModel

    @State private var selection: LibrarySelection?
    @State private var columnVisibility: NavigationSplitViewVisibility = .all
    @State private var path = NavigationPath()

    var body: some View {
        Group {
            if horizontalSizeClass == .regular {
                split
            } else {
                NavigationStack(path: $path) {
                    browse
                        .navigationTitle("Library")
                        .appDestinations(model)
                }
            }
        }
        .task {
            await feature.refresh(forceRemote: false)
            await collections.refresh(forceRemote: false)
        }
    }

    @ViewBuilder
    private var browse: some View {
        let actions = LibraryActions(
            openPlatform: { path.append(AppDestination.platform($0)) },
            openCollection: { path.append(AppDestination.collection($0)) },
            openGame: { path.append(AppDestination.game($0)) }
        )
        Group {
            if theme.isRiso {
                RisoLibraryLayout(content: content, actions: actions)
            } else {
                AuroraLibraryLayout(content: content, actions: actions, artworkURL: model.artworkURL(path:))
            }
        }
        .accessibilityIdentifier("shell.libraryList")
        .refreshable { await refresh() }
    }

    // MARK: Regular width: a narrow list beside the selection

    private var split: some View {
        NavigationSplitView(columnVisibility: $columnVisibility) {
            LibrarySidebar(content: content, selection: $selection, artworkURL: model.artworkURL(path:))
                .navigationTitle("Library")
                .accessibilityIdentifier("shell.libraryList")
                .refreshable { await refresh() }
        } detail: {
            NavigationStack {
                Group {
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
                .appDestinations(model)
            }
        }
        .navigationSplitViewStyle(.balanced)
        .onChange(of: feature.sections.first?.platforms.first?.id, initial: true) { _, _ in
            if selection == nil, let first = feature.sections.first?.platforms.first {
                selection = .platform(first)
            }
        }
    }

    private func refresh() async {
        await feature.refresh(forceRemote: true)
        await collections.refresh(forceRemote: true)
    }

    private var content: LibraryContent {
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
        content.roms = Array(model.statsFeature.games.values)
        content.inProgress = model.playFeature.snapshot.continuePlaying
        content.staleMessage = feature.staleMessage ?? collections.staleMessage
        content.errorMessage = feature.errorMessage
        content.isLoading = feature.isLoading
        return content
    }
}

struct LibraryActions {
    let openPlatform: (PlatformDTO) -> Void
    let openCollection: (RommCollectionDTO) -> Void
    let openGame: (RomDTO) -> Void
}

/// The regular-width sidebar: platforms by tier, then collections, as a
/// selectable list in the theme (glass rows or printed contents rows).
private struct LibrarySidebar: View {
    @Environment(\.rommioTheme) private var theme
    let content: LibraryContent
    @Binding var selection: LibrarySelection?
    let artworkURL: (String?) -> URL?

    var body: some View {
        List(selection: $selection) {
            if content.isEmpty {
                EmptyStateView(title: "No platforms yet", systemImage: "square.stack.3d.up.slash",
                               message: content.errorMessage ?? "Pull to refresh once your server is reachable.")
                    .listRowBackground(Color.clear)
            }
            ForEach(content.sections) { section in
                Section {
                    ForEach(Array(section.platforms.enumerated()), id: \.element.id) { index, platform in
                        Group {
                            if theme.isRiso {
                                ChapterRow(number: number(of: platform), platform: platform, installed: content.installed(platform), compact: true)
                            } else {
                                ListRow(title: platform.name, subtitle: "\(PlayFormat.games(platform.romCount)) · \(content.installed(platform)) here")
                            }
                        }
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

    private func number(of platform: PlatformDTO) -> Int {
        (content.platforms.firstIndex(of: platform) ?? 0) + 1
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
            Group {
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
            .frame(width: compact ? 30 : 44, alignment: .trailing)
        }
        .padding(.vertical, 6)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(platform.name), \(PlayFormat.games(platform.romCount)), \(installed) on this device")
    }
}
