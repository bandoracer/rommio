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
    var id: Int { rom.id }

    /// "Played 2 hours ago · 1 h 05 min on this device".
    var playedLine: String? {
        guard let lastPlayedAt else { return nil }
        return "Played \(PlayFormat.inline(lastPlayedAt)) · \(PlayFormat.duration(playtime)) on this device"
    }
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

/// Everything the Play layouts draw. Built once from the feature models; both
/// themes read the same content.
struct PlayContent {
    var continuePlaying: [PlayGameItem] = []
    /// Recent saves across games (docs/play-data-local.md §3), newest first.
    var recentSaves: [RecentSaveItem] = []
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

    var isEmpty: Bool { continuePlaying.isEmpty && recentSaves.isEmpty }

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
}

/// What Play can do. The layouts only call these.
struct PlayActions {
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
        NavigationStack(path: $path) {
            Group {
                if theme.isRiso {
                    RisoPlayLayout(content: content, actions: actions)
                } else {
                    AuroraPlayLayout(content: content, actions: actions)
                }
            }
            .navigationTitle("Play")
            .inlineNavigationTitle()
            .toolbar {
                ToolbarItem(placement: .principal) { Color.clear.frame(width: 1, height: 1).accessibilityHidden(true) }
                ToolbarItem(placement: .primaryAction) {
                    NavigationLink(value: AppDestination.downloads) {
                        DownloadsToolbarLabel(count: queueCount)
                    }
                    .accessibilityLabel(queueCount == 0 ? "Downloads" : "Downloads, \(queueCount) in queue")
                    .accessibilityIdentifier("play.downloads")
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
    }

    private var queueCount: Int {
        model.downloadsFeature.queueSummary.runningCount + model.downloadsFeature.queueSummary.queuedCount
    }

    private var actions: PlayActions {
        let conflicts = content.conflicts
        return PlayActions(
            // A game whose resume point is in conflict asks which to keep first.
            resume: { rom in
                if let item = conflicts.first(where: { $0.rom.id == rom.id }) { conflict = item }
                else { Task { await model.quickResume(rom: rom) } }
            },
            open: { rom in path.append(AppDestination.game(rom)) },
            openSave: { item in
                if item.inConflict, let found = conflicts.first(where: { $0.rom.id == item.rom.id }) { conflict = found }
                else { path.append(AppDestination.game(item.rom)) }
            },
            chooseConflict: { item in conflict = item },
            openCollection: { collection in path.append(AppDestination.collection(collection)) },
            openDownloads: { path.append(AppDestination.downloads) },
            startOptions: { rom in
                Task {
                    switch await model.startOptions(for: rom) {
                    case let .choice(choice): model.showLaunchChoice(choice)
                    case .conflict: conflict = conflicts.first { $0.rom.id == rom.id }
                    case .nothingToChoose: await model.quickResume(rom: rom)
                    }
                }
            }
        )
    }

    private var content: PlayContent {
        let stats = model.statsFeature.stats
        let byGame = Dictionary(uniqueKeysWithValues: stats.games.map { ($0.romID, $0) })
        let saves = model.savesFeature
        let installed = Set(model.downloadsFeature.records.filter { $0.status == .completed }.map(\.romID))
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
                                conflict: conflicts.first { $0.rom.id == rom.id })
        }
        var content = PlayContent()
        content.continuePlaying = feature.snapshot.continuePlaying.map(item)
        content.recentSaves = saves.recent.map { entry in
            let rom = saves.game(entry.romId)
            return RecentSaveItem(entry: entry, rom: rom, artworkURL: model.artworkURL(path: rom.urlCover))
        }
        content.conflicts = conflicts
        content.collection = pinnedCollection(item: item)
        let summary = model.downloadsFeature.queueSummary
        if let running = model.downloadsFeature.records.first(where: { $0.status == .running }) {
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
        content.isLoading = feature.isLoading
        content.errorMessage = PlayContent.errorMessage(isEmpty: content.isEmpty, isLoading: feature.isLoading,
                                                        offline: content.isOffline,
                                                        failure: feature.staleMessage)
        return content
    }

    /// The first collection the user made (then any): its games, played ones first.
    private func pinnedCollection(item: (RomDTO) -> PlayGameItem) -> PlayCollectionItem? {
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

/// Loads cover palettes for small marks (pager dots, day light strips, bars).
struct CoverPalettes: ViewModifier {
    @Environment(\.artworkPipeline) private var pipeline
    @Environment(\.rommioTheme) private var theme
    let urls: [URL]
    @Binding var palettes: [URL: CoverPalette]

    func body(content: Content) -> some View {
        content.task(id: urls.map(\.absoluteString).joined(separator: "|") + (theme.isGlass ? "#glass" : "#paper")) {
            guard theme.isGlass else { return }
            for url in urls where palettes[url] == nil {
                if let palette = await pipeline.palette(for: url), !Task.isCancelled { palettes[url] = palette }
            }
        }
    }
}

extension View {
    func coverPalettes(_ urls: [URL], into palettes: Binding<[URL: CoverPalette]>) -> some View {
        modifier(CoverPalettes(urls: urls, palettes: palettes))
    }
}

extension RommioTheme {
    /// A game's vivid mark in this appearance, or the page's own when the cover has no usable colour.
    func mark(for url: URL?, in palettes: [URL: CoverPalette]) -> Color {
        guard let url, let palette = palettes[url], !palette.isNeutral else { return mark1 }
        return AuroraLights.derive(palette: palette, dark: isDark).k1.themeColor.color
    }
}
