import SwiftUI
import RommioContract
import RommioFoundation
import RommioPlayerKit

/// A game on the Play page with what this device knows about its play.
struct PlayGameItem: Identifiable, Hashable {
    let rom: RomDTO
    let artworkURL: URL?
    let lastPlayedAt: Date?
    let playtime: TimeInterval
    let sessionCount: Int
    /// Its current resume point is in conflict: Resume opens the Conflict sheet.
    var conflict: ConflictItem? = nil
    /// Its latest place to continue from (resume point, else a save state), from the saves reader.
    var latest: SaveEntry? = nil
    var id: Int { rom.id }

    /// "Played 2 hours ago · 1 h 05 min on this device".
    var playedLine: String? {
        guard let lastPlayedAt else { return nil }
        return "Played \(PlayFormat.inline(lastPlayedAt)) · \(PlayFormat.duration(playtime)) on this device"
    }

    /// "Automatic resume · 1 min ago · This iPhone" (or the save state's name or kind).
    var resumeLine: String? {
        guard let latest else { return nil }
        let what = latest.kind == .saveState ? (latest.label.map { "“\($0)”" } ?? latest.kindTitle) : "Automatic resume"
        return PlayFormat.line([what, PlayFormat.inline(latest.capturedAt), latest.deviceLabel])
    }

    /// The caption's last line: where it continues from, else how it was played.
    var captionLine: String { resumeLine ?? playedLine ?? "Resume where you left off" }
    /// The game's screen: the latest resume point's (or save state's) preview.
    var previewURL: URL? { latest?.thumbnailURL }
}

/// One play session, newest first (Stats).
struct PlaySessionItem: Identifiable, Hashable {
    let id: String
    let rom: RomDTO
    let artworkURL: URL?
    let startedAt: Date
    let endedAt: Date
    let playtime: TimeInterval
    /// How a recorded session ended (nil for sessions estimated from saves, or still open).
    var endReason: PlaySessionEndReason? = nil
}

/// The collection pinned on Play (Riso), with the games Rommio knows of in it.
struct PlayCollectionItem: Hashable {
    let collection: RommCollectionDTO
    let games: [PlayGameItem]
    let playedCount: Int
    let playtime: TimeInterval
}

/// The download in progress and the queue behind it.
struct PlayDownloadItem: Hashable {
    let name: String
    let progress: Double
    let waiting: Int
    let failed: Int
}

/// Everything the Play layouts draw. Built once per change of the feature models
/// (`PlayContent.build`); both themes read the same content.
struct PlayContent: Equatable {
    var continuePlaying: [PlayGameItem] = []
    /// Recent saves across games (docs/play-data-local.md §3), newest first.
    var recentSaves: [RecentSaveItem] = [] {
        didSet {
            recentSaveDays = RecentSaveDay.days(recentSaves, limit: 12)
            recentSaveDaysShort = RecentSaveDay.days(recentSaves, limit: 8)
        }
    }
    /// References that need a choice (resume points first), by game.
    var conflicts: [ConflictItem] = []
    var collection: PlayCollectionItem?
    var download: PlayDownloadItem?
    var isOffline = false
    var installedCount = 0
    var lastSync: Date?
    var serverName = "RomM"
    var staleMessage: String?
    /// Nothing could be loaded (Android's copy); shown instead of the empty state.
    var errorMessage: String?
    var isLoading = false
    /// Downloads running and waiting (the toolbar badge).
    var queueCount = 0
    /// Recent saves by day: at most 12 saves, and at most 8 (Riso's rail).
    private(set) var recentSaveDays: [RecentSaveDay] = []
    private(set) var recentSaveDaysShort: [RecentSaveDay] = []

    var isEmpty: Bool { continuePlaying.isEmpty && recentSaves.isEmpty }

    /// Covers whose palettes colour the small marks (pager dots, strips, glows).
    var markURLs: [URL] { continuePlaying.compactMap(\.artworkURL) + recentSaves.compactMap(\.artworkURL) }

    /// The error when there is nothing to show: offline before the first sync, or the failure.
    static func errorMessage(isEmpty: Bool, isLoading: Bool, offline: Bool, failure: String?) -> String? {
        guard isEmpty, !isLoading, failure != nil || offline else { return nil }
        if offline { return "You're offline. Your games appear here after this server syncs once." }
        return failure ?? "Couldn't load your games."
    }

    /// "Online · synced 2 min ago · 12 games on this device".
    var statusLine: String {
        PlayFormat.line([isOffline ? "Offline" : "Online", lastSync.map { "synced \(PlayFormat.inline($0))" },
                         "\(PlayFormat.games(installedCount)) on this device"])
    }

    /// Play from the feature models: games in progress, recent saves, conflicts,
    /// the pinned collection and the download queue.
    @MainActor
    static func build(_ model: RommioAppModel) -> PlayContent {
        let feature = model.playFeature
        let stats = model.statsFeature.stats
        let byGame = Dictionary(stats.games.map { ($0.romID, $0) }, uniquingKeysWith: { first, _ in first })
        let saves = model.savesFeature
        let installed = model.installedRomIDs
        let conflicts = saves.conflicts
            .sorted { ($0.kind == .resume ? 0 : 1) < ($1.kind == .resume ? 0 : 1) }
            .map { conflict in
                let rom = saves.game(conflict.romId)
                return ConflictItem(conflict: conflict, rom: rom, artworkURL: model.artworkURL(path: rom.urlCover), installed: installed.contains(rom.id))
            }
        func item(_ rom: RomDTO) -> PlayGameItem {
            let played = byGame[rom.id]
            return PlayGameItem(rom: rom, artworkURL: model.artworkURL(path: rom.urlCover), lastPlayedAt: played?.lastPlayedAt,
                                playtime: played?.playtime ?? 0, sessionCount: played?.sessionCount ?? 0,
                                conflict: conflicts.first { $0.rom.id == rom.id }, latest: saves.latest[Int64(rom.id)])
        }
        var content = PlayContent()
        content.continuePlaying = feature.snapshot.continuePlaying.map(item)
        content.recentSaves = saves.recent.map { entry in
            let rom = saves.game(entry.romId)
            return RecentSaveItem(entry: entry, rom: rom, artworkURL: model.artworkURL(path: rom.urlCover))
        }
        content.conflicts = conflicts
        content.collection = pinnedCollection(model, item: item)
        let downloads = model.downloadsFeature
        let summary = downloads.queueSummary
        content.queueCount = summary.runningCount + summary.queuedCount
        if let running = downloads.records.first(where: { $0.status == .running }) {
            content.download = PlayDownloadItem(name: running.romName, progress: Double(running.progressPercent) / 100,
                                                waiting: summary.queuedCount, failed: summary.failedCount)
        } else if summary.queuedCount > 0 || summary.failedCount > 0 {
            content.download = PlayDownloadItem(name: "", progress: 0, waiting: summary.queuedCount, failed: summary.failedCount)
        }
        content.isOffline = feature.offlineState.connectivity == .offline
        content.installedCount = feature.storageSummary.installedGameCount
        content.lastSync = feature.offlineState.lastFullSyncAtEpochMS.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) }
        content.serverName = model.activeProfile?.label ?? "RomM"
        content.staleMessage = feature.hasRenderableContent ? feature.staleMessage : nil
        // Loading only shows in an empty state: a refresh of a full page doesn't republish it.
        content.isLoading = feature.isLoading && content.isEmpty
        content.errorMessage = PlayContent.errorMessage(isEmpty: content.isEmpty, isLoading: feature.isLoading,
                                                        offline: content.isOffline, failure: feature.staleMessage)
        return content
    }

    /// The first collection the user made (then any): its games, played ones first.
    @MainActor
    private static func pinnedCollection(_ model: RommioAppModel, item: (RomDTO) -> PlayGameItem) -> PlayCollectionItem? {
        let collections = model.collectionsFeature.collections
        guard let pinned = collections.first(where: { !$0.isVirtual && !$0.isSmart && !$0.romIDs.isEmpty })
            ?? collections.first(where: { !$0.romIDs.isEmpty }) else { return nil }
        let games = pinned.romIDs.compactMap { model.statsFeature.games[$0] }.map(item)
            .sorted { lhs, rhs in
                if lhs.playtime != rhs.playtime { return lhs.playtime > rhs.playtime }
                return lhs.rom.displayName.localizedStandardCompare(rhs.rom.displayName) == .orderedAscending
            }
        guard !games.isEmpty else { return nil }
        return PlayCollectionItem(collection: pinned, games: games, playedCount: games.filter { $0.playtime > 0 }.count,
                                  playtime: games.reduce(0) { $0 + $1.playtime })
    }
}

/// What Play can do. The layouts only call these.
struct PlayActions: PageActions {
    let resume: (RomDTO) -> Void
    let open: (RomDTO) -> Void
    /// A save in Recent saves: opens its game.
    let openSave: (RecentSaveItem) -> Void
    /// Opens the Conflict sheet.
    let chooseConflict: (ConflictItem) -> Void
    let openCollection: (RommCollectionDTO) -> Void
    let openDownloads: () -> Void
    /// Start options: the Launch choice (Resume or Start with game save).
    let startOptions: (RomDTO) -> Void
}

/// Play: Continue playing first, then recent activity. Aurora pages through the
/// games in progress, lit by each; Riso sets the page like a front page.
/// Downloads are one tap away in the toolbar.
struct PlayPage: View {
    @Environment(\.rommioTheme) private var theme
    let model: RommioAppModel
    let feature: PlayFeatureModel
    @State private var path = NavigationPath()
    @State private var conflict: ConflictItem?

    var body: some View {
        let content = model.playContent.value
        NavigationStack(path: $path) {
            PlayLayout(content: content, actions: actions, riso: theme.isRiso)
                .navigationTitle("Play")
                .inlineNavigationTitle()
                .toolbar {
                    ToolbarItem(placement: .principal) { Color.clear.frame(width: 1, height: 1).accessibilityHidden(true) }
                    ToolbarItem(placement: .primaryAction) {
                        DownloadsToolbarButton(count: content.queueCount)
                    }
                }
                .appDestinations(model)
                .accessibilityIdentifier("shell.playList")
                .task { await feature.refresh(refreshCatalog: false) }
                .task { await model.savesFeature.refresh() }
                .refreshable { await model.refreshShell(forceRemote: true) }
                .sheet(item: $conflict) { item in
                    ConflictSheet(item: item) { choice in
                        Task { await model.keepPlaying(item.conflict, choice: choice) }
                    }
                    .presentationDetents([.large])
                    .presentationDragIndicator(.visible)
                }
        }
        .appNavigator($path)
    }

    /// Actions read the current conflicts when called (never a copy taken at render).
    private var actions: PlayActions {
        let model = model
        let path = $path
        let conflict = $conflict
        func conflicts() -> [ConflictItem] { model.playContent.value.conflicts }
        return PlayActions(
            // A game whose resume point is in conflict asks which to keep first.
            resume: { rom in
                if let item = conflicts().first(where: { $0.rom.id == rom.id }) { conflict.wrappedValue = item }
                else { Task { await model.quickResume(rom: rom) } }
            },
            open: { rom in path.wrappedValue.append(AppDestination.game(rom)) },
            openSave: { item in
                if item.inConflict, let found = conflicts().first(where: { $0.rom.id == item.rom.id }) { conflict.wrappedValue = found }
                else { path.wrappedValue.append(AppDestination.game(item.rom)) }
            },
            chooseConflict: { item in conflict.wrappedValue = item },
            openCollection: { collection in path.wrappedValue.append(AppDestination.collection(collection)) },
            openDownloads: { path.wrappedValue.append(AppDestination.downloads) },
            startOptions: { rom in
                Task {
                    switch await model.startOptions(for: rom) {
                    case let .choice(choice): model.showLaunchChoice(choice)
                    case .conflict: conflict.wrappedValue = conflicts().first { $0.rom.id == rom.id }
                    case .nothingToChoose: await model.quickResume(rom: rom)
                    }
                }
            }
        )
    }
}

/// The theme's Play layout.
private struct PlayLayout: View {
    let content: PlayContent
    let actions: PlayActions
    let riso: Bool

    var body: some View {
        if riso {
            RisoPlayLayout(content: content, actions: actions)
        } else {
            AuroraPlayLayout(content: content, actions: actions)
        }
    }
}

/// A game in progress, pressed and held: Resume, Start options (the Launch
/// choice) and its Game page.
struct PlayGameMenu: View {
    let game: PlayGameItem
    let actions: PlayActions

    var body: some View {
        Button("Resume", systemImage: "play.fill") { actions.resume(game.rom) }
        Button("Start options", systemImage: "slider.horizontal.3") { actions.startOptions(game.rom) }
        Button("Game details", systemImage: "info.circle") { actions.open(game.rom) }
    }
}

/// The Play toolbar's Downloads button with its queue count.
private struct DownloadsToolbarButton: View {
    let count: Int

    var body: some View {
        NavigationLink(value: AppDestination.downloads) {
            DownloadsToolbarLabel(count: count)
        }
        .accessibilityLabel(count == 0 ? "Downloads" : "Downloads, \(count) in queue")
        .accessibilityIdentifier("play.downloads")
    }
}

private struct DownloadsToolbarLabel: View {
    @Environment(\.rommioTheme) private var theme
    let count: Int

    var body: some View {
        Image(systemName: "arrow.down.circle")
            .overlay(alignment: .topTrailing) {
                if count > 0 {
                    Text("\(count)")
                        .font(.system(size: 11, weight: .bold).monospacedDigit())
                        .foregroundStyle(theme.onBad)
                        .padding(.horizontal, 5)
                        .frame(minWidth: 17, minHeight: 17)
                        .background(Capsule().fill(theme.bad))
                        .offset(x: 9, y: -8)
                }
            }
    }
}
