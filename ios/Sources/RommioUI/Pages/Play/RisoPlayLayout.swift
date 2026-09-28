import SwiftUI
import RommioContract

/// Riso Play: set like a front page. The game to resume leads (with a conflict
/// stamp when its resume point needs a choice); the other games in progress run as briefs beside a pinned
/// collection; recent saves close the page as a list by day. On a regular-width
/// display (iPhone Duo open) it becomes one broadsheet: the lead across the fold,
/// the briefs and saves in a rail.
struct RisoPlayLayout: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    let content: PlayContent
    let actions: PlayActions

    var body: some View {
        ScrollView {
            Group {
                if horizontalSizeClass == .regular {
                    RisoPlayBroadsheet(content: content, actions: actions)
                } else {
                    RisoPlayColumn(content: content, actions: actions)
                }
            }
            .padding(.horizontal, horizontalSizeClass == .regular ? 28 : theme.spacing.gutter)
            .padding(.bottom, 40)
            .frame(maxWidth: 1180)
            .frame(maxWidth: .infinity)
        }
        .themedPage()
    }
}

private extension PlayContent {
    var lead: PlayGameItem? { continuePlaying.first }
    var briefs: [PlayGameItem] { Array(continuePlaying.dropFirst().prefix(4)) }
}

// MARK: Compact: one column

private struct RisoPlayColumn: View {
    let content: PlayContent
    let actions: PlayActions

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            RisoPlayMasthead(wide: false)
            Dateline(leading: PlayFormat.longDate(Date()), trailing: content.serverName)
                .padding(.top, 6)
            RisoPlayEars(ears: RisoEars(content), actions: actions)
            if let lead = content.lead { RisoLeadStory(game: lead, big: false, actions: actions).padding(.top, 14) }
            RisoPlayColumns(briefs: content.briefs, collection: content.collection, actions: actions).padding(.top, 20)
            RisoRecentSaves(days: content.recentSaveDays, count: content.recentSaves.count, actions: actions).padding(.top, 26)
            RisoPlayNotices(staleMessage: content.staleMessage, errorMessage: content.errorMessage, showsEmpty: content.isEmpty && !content.isLoading)
                .padding(.top, 20)
        }
    }
}

// MARK: Regular: a broadsheet across the fold

private struct RisoPlayBroadsheet: View {
    @Environment(\.rommioTheme) private var theme
    let content: PlayContent
    let actions: PlayActions

    var body: some View {
        let ears = RisoEars(content)
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .center, spacing: 20) {
                RisoDownloadsEar(ears: ears, action: actions.openDownloads)
                    .frame(maxWidth: 240, alignment: .leading).padding(10).overlay { Rectangle().strokeBorder(theme.ink, lineWidth: 1.5) }
                Spacer(minLength: 0)
                RisoPlayMasthead(wide: true)
                Spacer(minLength: 0)
                RisoSyncEar(ears: ears)
                    .frame(maxWidth: 240, alignment: .leading).padding(10).overlay { Rectangle().strokeBorder(theme.ink, lineWidth: 1.5) }
            }
            Dateline(leading: PlayFormat.longDate(Date()), trailing: content.serverName)
                .padding(.top, 10)
            HStack(alignment: .top, spacing: 0) {
                VStack(alignment: .leading, spacing: 22) {
                    if let lead = content.lead { RisoLeadStory(game: lead, big: true, actions: actions) }
                    if let collection = content.collection { RisoCollectionColumn(item: collection, wide: true, actions: actions) }
                    RisoPlayNotices(staleMessage: content.staleMessage, errorMessage: content.errorMessage, showsEmpty: content.isEmpty && !content.isLoading)
                }
                .padding(.trailing, 20)
                .frame(maxWidth: .infinity, alignment: .leading)
                Rectangle().fill(theme.ink).frame(width: 1)
                VStack(alignment: .leading, spacing: 22) {
                    if !content.briefs.isEmpty {
                        VStack(alignment: .leading, spacing: 12) {
                            SectionHeader(title: "In progress", detail: "\(content.briefs.count)")
                            ForEach(content.briefs) { RisoBrief(game: $0, actions: actions) }
                        }
                    }
                    RisoRecentSaves(days: content.recentSaveDaysShort, count: content.recentSaves.count, limit: 8, actions: actions)
                }
                .padding(.leading, 20)
                .frame(width: 330, alignment: .leading)
            }
            .padding(.top, 14)
        }
    }
}

// MARK: Masthead and ears

private struct RisoPlayMasthead: View {
    let wide: Bool

    var body: some View {
        DisplayText(text: "Play", role: .display, size: wide ? 88 : 52, plateOffset: 1.25, lineLimit: 1)
            .padding(.top, 2)
    }
}

/// What the ears print: the download queue and the library's sync.
private struct RisoEars: Equatable {
    let download: PlayDownloadItem?
    let installedCount: Int
    let isOffline: Bool
    let lastSync: Date?

    init(_ content: PlayContent) {
        download = content.download
        installedCount = content.installedCount
        isOffline = content.isOffline
        lastSync = content.lastSync
    }
}

private struct RisoPlayEars: View {
    @Environment(\.rommioTheme) private var theme
    let ears: RisoEars
    let actions: PlayActions

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            RisoDownloadsEar(ears: ears, action: actions.openDownloads)
                .frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 9).padding(.trailing, 10)
            Rectangle().fill(theme.ink).frame(width: 1)
            RisoSyncEar(ears: ears)
                .frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 9).padding(.leading, 12)
        }
        .fixedSize(horizontal: false, vertical: true)
        .overlay(alignment: .bottom) { Rectangle().fill(theme.ink).frame(height: 1.5) }
    }
}

private struct RisoDownloadsEar: View {
    @Environment(\.rommioTheme) private var theme
    let ears: RisoEars
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 4) {
                Label("Downloads", systemImage: "arrow.down.to.line")
                    .themeText(.label, size: 9.5)
                    .foregroundStyle(theme.inkSecondary)
                if let download = ears.download, !download.name.isEmpty {
                    Text("\(download.name) · \(Int((download.progress * 100).rounded()))%")
                        .themeText(.bodyEmphasis, size: 14)
                        .foregroundStyle(theme.ink)
                        .lineLimit(1)
                    Text(Self.queueLine(download)).themeText(.label, size: 9.5).foregroundStyle(theme.inkSecondary).lineLimit(1)
                    ThemedProgressBar(value: download.progress, tone: .live, height: 5)
                } else if let download = ears.download {
                    Text(Self.queueLine(download)).themeText(.bodyEmphasis, size: 14).foregroundStyle(theme.ink).lineLimit(1)
                } else {
                    Text("Nothing downloading").themeText(.bodyEmphasis, size: 14).foregroundStyle(theme.ink).lineLimit(1)
                    Text("\(PlayFormat.games(ears.installedCount)) on this device").themeText(.label, size: 9.5)
                        .foregroundStyle(theme.inkSecondary).lineLimit(1)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("play.downloadsEar")
    }

    static func queueLine(_ download: PlayDownloadItem) -> String {
        var parts: [String] = []
        if download.waiting > 0 { parts.append("\(download.waiting) waiting") }
        if download.failed > 0 { parts.append("\(download.failed) failed") }
        return parts.isEmpty ? "Downloading" : parts.joined(separator: " · ")
    }
}

private struct RisoSyncEar: View {
    @Environment(\.rommioTheme) private var theme
    let ears: RisoEars

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Label(ears.isOffline ? "Offline" : "Online", systemImage: ears.isOffline ? "wifi.slash" : "wifi")
                .themeText(.label, size: 9.5)
                .foregroundStyle(theme.inkSecondary)
            Text("\(PlayFormat.games(ears.installedCount)) ready")
                .themeText(.bodyEmphasis, size: 14)
                .foregroundStyle(theme.ink)
                .lineLimit(1)
            Text(ears.lastSync.map { "Synced \(PlayFormat.inline($0))" } ?? (ears.isOffline ? "Waiting for a connection" : "Library not synced yet"))
                .themeText(.label, size: 9.5)
                .foregroundStyle(theme.inkSecondary)
                .lineLimit(1)
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("play.syncLine")
    }
}

// MARK: The lead

private struct RisoLeadStory: View {
    let game: PlayGameItem
    let big: Bool
    let actions: PlayActions

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Kicker(label: "Continue playing", detail: [game.rom.platformSlug.uppercased(), game.lastPlayedAt.map { PlayFormat.relative($0) }]
                    .compactMap { $0 }.joined(separator: " · "), fill: .pink)
            Button { actions.open(game.rom) } label: {
                DisplayText(text: game.rom.displayName, role: .display, size: big ? 76 : 60, lineLimit: 2)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.plain)
            RatioColumns(ratio: big ? 0.63 : 0.6, spacing: big ? 18 : 14) {
                RisoLeadFigure(game: game, big: big, actions: actions)
                RisoLeadText(game: game, big: big, actions: actions)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("play.continue")
    }
}

private struct RisoLeadFigure: View {
    let game: PlayGameItem
    let big: Bool
    let actions: PlayActions

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            GameCover(url: game.artworkURL, title: game.rom.displayName, maxPointSize: big ? 360 : 260)
                .aspectRatio(3 / 4, contentMode: .fit)
                .onTapGesture { actions.open(game.rom) }
                .contextMenu { PlayGameMenu(game: game, actions: actions) }
            FigureCaption(text: "\(game.rom.platformName). \(game.lastPlayedAt.map { "Played \(PlayFormat.inline($0))." } ?? "Not played on this device yet.")")
        }
    }
}

private struct RisoLeadText: View {
    @Environment(\.rommioTheme) private var theme
    let game: PlayGameItem
    let big: Bool
    let actions: PlayActions

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(dek)
                .themeText(.body, size: big ? 17 : 16)
                .foregroundStyle(theme.ink)
                .fixedSize(horizontal: false, vertical: true)
            if let conflict = game.conflict {
                ConflictChip(item: conflict) { actions.chooseConflict(conflict) }
            }
            if game.sessionCount > 0 {
                VStack(alignment: .leading, spacing: 5) {
                    Text("On this device").themeText(.label, size: 9.5).foregroundStyle(theme.inkSecondary)
                    StatusChip(title: game.sessionCount == 1 ? "1 session" : "\(game.sessionCount) sessions", tone: .ok)
                }
            }
            LaunchButton(title: "Resume", romID: game.rom.id) { actions.resume(game.rom) }
                .accessibilityLabel("Resume \(game.rom.displayName)")
                .accessibilityIdentifier("play.resume.\(game.rom.id)")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var dek: String {
        guard let last = game.lastPlayedAt else { return "Pick up where you left off. Your resume point is waiting." }
        return "You last played \(PlayFormat.inline(last)), \(PlayFormat.duration(game.playtime)) in so far on this device."
    }
}

// MARK: Briefs and the pinned collection

private struct RisoPlayColumns: View {
    @Environment(\.rommioTheme) private var theme
    let briefs: [PlayGameItem]
    let collection: PlayCollectionItem?
    let actions: PlayActions

    var body: some View {
        let hasBriefs = !briefs.isEmpty
        if hasBriefs || collection != nil {
            HStack(alignment: .top, spacing: 0) {
                if hasBriefs {
                    VStack(alignment: .leading, spacing: 12) {
                        RisoColumnHeading(title: "In progress")
                        ForEach(briefs.prefix(collection == nil ? 4 : 3)) { RisoBrief(game: $0, actions: actions) }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.trailing, collection == nil ? 0 : 14)
                }
                if let collection {
                    if hasBriefs { Rectangle().fill(theme.ink).frame(width: 1) }
                    RisoCollectionColumn(item: collection, wide: !hasBriefs, actions: actions)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.leading, hasBriefs ? 14 : 0)
                }
            }
            .fixedSize(horizontal: false, vertical: true)
        }
    }
}

private struct RisoColumnHeading: View {
    @Environment(\.rommioTheme) private var theme
    let title: String

    var body: some View {
        Text(title)
            .themeText(.section, size: 16)
            .foregroundStyle(theme.ink)
            .accessibilityAddTraits(.isHeader)
    }
}

private struct RisoBrief: View {
    @Environment(\.rommioTheme) private var theme
    let game: PlayGameItem
    let actions: PlayActions

    var body: some View {
        Button { actions.open(game.rom) } label: {
            HStack(alignment: .top, spacing: 10) {
                GameCover(url: game.artworkURL, title: game.rom.displayName, maxPointSize: 80)
                    .frame(width: 48, height: 64)
                VStack(alignment: .leading, spacing: 3) {
                    Text([game.rom.platformSlug.uppercased(), game.lastPlayedAt.map { PlayFormat.relative($0) }].compactMap { $0 }.joined(separator: " · "))
                        .themeText(.label, size: 9.5)
                        .foregroundStyle(theme.inkSecondary)
                        .lineLimit(1)
                    Text(game.rom.displayName)
                        .themeText(.headline, size: 18)
                        .foregroundStyle(theme.ink)
                        .lineLimit(2)
                    Text(game.playtime > 0 ? "\(PlayFormat.duration(game.playtime)) so far." : "Ready to resume.")
                        .themeText(.secondary, size: 13.5)
                        .foregroundStyle(theme.ink)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .contextMenu { PlayGameMenu(game: game, actions: actions) }
        .accessibilityIdentifier("play.quickResume.\(game.rom.id)")
    }
}

private struct RisoCollectionColumn: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    let item: PlayCollectionItem
    let wide: Bool
    let actions: PlayActions

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button { actions.openCollection(item.collection) } label: {
                HStack(spacing: 8) {
                    RisoColumnHeading(title: item.collection.name)
                    if wide { Rectangle().fill(theme.ink).frame(height: 1.5) }
                    Text("\(item.playedCount) of \(item.games.count) played").themeText(.label, size: 9.5).foregroundStyle(theme.inkSecondary)
                        .fixedSize()
                }
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("play.collection")
            Text(item.collection.description.isEmpty ? "\(PlayFormat.duration(item.playtime)) played so far." : item.collection.description)
                .themeText(.caption, size: 13.5)
                .foregroundStyle(theme.ink)
                .lineLimit(2)
                .padding(.bottom, 4)
            let rows = Array(item.games.prefix(wide ? 6 : 5).enumerated())
            LazyVGrid(columns: wide && horizontalSizeClass == .regular ? Array(repeating: GridItem(.flexible(), spacing: 14, alignment: .top), count: 3)
                                                                     : [GridItem(.flexible(), alignment: .top)], alignment: .leading, spacing: 0) {
                ForEach(rows, id: \.element.id) { index, game in
                    Button { actions.open(game.rom) } label: {
                        RisoCollectionEntry(rank: index + 1, game: game)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}

private struct RisoCollectionEntry: View {
    @Environment(\.rommioTheme) private var theme
    let rank: Int
    let game: PlayGameItem

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text("\(rank)")
                .themeText(.statValue, size: 19)
                .foregroundStyle(game.playtime > 0 ? theme.ink : theme.inkSecondary)
                .frame(width: 16, alignment: .leading)
            VStack(alignment: .leading, spacing: 1) {
                Text(game.rom.displayName).themeText(.bodyEmphasis, size: 14).foregroundStyle(theme.ink).lineLimit(2)
                Text(game.playtime > 0 ? PlayFormat.duration(game.playtime) : "Not started")
                    .themeText(.label, size: 9.5)
                    .foregroundStyle(theme.inkSecondary)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 7)
        .overlay(alignment: .top) { Rectangle().fill(theme.hairline).frame(height: 1) }
        .contentShape(Rectangle())
    }
}

// MARK: Notices and empty

private struct RisoPlayNotices: View {
    @Environment(\.rommioTheme) private var theme
    let staleMessage: String?
    let errorMessage: String?
    let showsEmpty: Bool

    var body: some View {
        if let staleMessage {
            VStack(alignment: .leading, spacing: 4) {
                Kicker(label: "Showing your saved library", fill: .yellow)
                Text(staleMessage).themeText(.caption).foregroundStyle(theme.inkSecondary).lineLimit(3)
            }
        }
        if let errorMessage {
            Text(errorMessage)
                .themeText(.body, size: 15)
                .foregroundStyle(theme.ink)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("play.error")
        } else if showsEmpty {
            EmptyStateView(title: "Nothing to continue yet", systemImage: "play.circle",
                           message: "Games you play show up here. Open the Library to start one.")
        }
    }
}
