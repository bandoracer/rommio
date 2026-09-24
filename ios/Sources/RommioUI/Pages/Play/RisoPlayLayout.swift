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

    private var lead: PlayGameItem? { content.continuePlaying.first }
    private var briefs: [PlayGameItem] { Array(content.continuePlaying.dropFirst().prefix(4)) }

    var body: some View {
        ScrollView {
            Group {
                if horizontalSizeClass == .regular { broadsheet } else { column }
            }
            .padding(.horizontal, horizontalSizeClass == .regular ? 28 : theme.spacing.gutter)
            .padding(.bottom, 40)
            .frame(maxWidth: 1180)
            .frame(maxWidth: .infinity)
        }
        .themedPage()
    }

    // MARK: Compact: one column

    private var column: some View {
        VStack(alignment: .leading, spacing: 0) {
            masthead
            Dateline(leading: PlayFormat.longDate(Date()), trailing: content.serverName)
                .padding(.top, 6)
            ears
            if let lead { leadStory(lead, big: false).padding(.top, 14) }
            columns.padding(.top, 20)
            RisoRecentSaves(items: content.recentSaves, open: actions.openSave).padding(.top, 26)
            notices.padding(.top, 20)
        }
    }

    // MARK: Regular: a broadsheet across the fold

    private var broadsheet: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .center, spacing: 20) {
                downloadsEar.frame(maxWidth: 240, alignment: .leading).padding(10).overlay { Rectangle().strokeBorder(theme.ink, lineWidth: 1.5) }
                Spacer(minLength: 0)
                masthead
                Spacer(minLength: 0)
                syncEar.frame(maxWidth: 240, alignment: .leading).padding(10).overlay { Rectangle().strokeBorder(theme.ink, lineWidth: 1.5) }
            }
            Dateline(leading: PlayFormat.longDate(Date()), trailing: content.serverName)
                .padding(.top, 10)
            HStack(alignment: .top, spacing: 0) {
                VStack(alignment: .leading, spacing: 22) {
                    if let lead { leadStory(lead, big: true) }
                    if let collection = content.collection { collectionColumn(collection, wide: true) }
                    notices
                }
                .padding(.trailing, 20)
                .frame(maxWidth: .infinity, alignment: .leading)
                Rectangle().fill(theme.ink).frame(width: 1)
                VStack(alignment: .leading, spacing: 22) {
                    if !briefs.isEmpty {
                        VStack(alignment: .leading, spacing: 12) {
                            SectionHeader(title: "In progress", detail: "\(briefs.count)")
                            ForEach(briefs) { brief($0) }
                        }
                    }
                    RisoRecentSaves(items: content.recentSaves, limit: 8, open: actions.openSave)
                }
                .padding(.leading, 20)
                .frame(width: 330, alignment: .leading)
            }
            .padding(.top, 14)
        }
    }

    // MARK: Masthead and ears

    private var masthead: some View {
        DisplayText(text: "Play", role: .display, size: horizontalSizeClass == .regular ? 88 : 52, plateOffset: 1.25, lineLimit: 1)
            .padding(.top, 2)
    }

    private var ears: some View {
        HStack(alignment: .top, spacing: 0) {
            downloadsEar.frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 9).padding(.trailing, 10)
            Rectangle().fill(theme.ink).frame(width: 1)
            syncEar.frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 9).padding(.leading, 12)
        }
        .fixedSize(horizontal: false, vertical: true)
        .overlay(alignment: .bottom) { Rectangle().fill(theme.ink).frame(height: 1.5) }
    }

    private var downloadsEar: some View {
        Button(action: actions.openDownloads) {
            VStack(alignment: .leading, spacing: 4) {
                Label("Downloads", systemImage: "arrow.down.to.line")
                    .themeText(.label, size: 9.5)
                    .foregroundStyle(theme.inkSecondary)
                if let download = content.download, !download.name.isEmpty {
                    Text("\(download.name) · \(Int((download.progress * 100).rounded()))%")
                        .themeText(.bodyEmphasis, size: 14)
                        .foregroundStyle(theme.ink)
                        .lineLimit(1)
                    Text(queueLine(download)).themeText(.label, size: 9.5).foregroundStyle(theme.inkSecondary).lineLimit(1)
                    ThemedProgressBar(value: download.progress, tone: .live, height: 5)
                } else if let download = content.download {
                    Text(queueLine(download)).themeText(.bodyEmphasis, size: 14).foregroundStyle(theme.ink).lineLimit(1)
                } else {
                    Text("Nothing downloading").themeText(.bodyEmphasis, size: 14).foregroundStyle(theme.ink).lineLimit(1)
                    Text("\(PlayFormat.games(content.installedCount)) on this device").themeText(.label, size: 9.5)
                        .foregroundStyle(theme.inkSecondary).lineLimit(1)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("play.downloadsEar")
    }

    private var syncEar: some View {
        VStack(alignment: .leading, spacing: 4) {
            Label(content.isOffline ? "Offline" : "Online", systemImage: content.isOffline ? "wifi.slash" : "wifi")
                .themeText(.label, size: 9.5)
                .foregroundStyle(theme.inkSecondary)
            Text("\(PlayFormat.games(content.installedCount)) ready")
                .themeText(.bodyEmphasis, size: 14)
                .foregroundStyle(theme.ink)
                .lineLimit(1)
            Text(content.lastSync.map { "Synced \(PlayFormat.inline($0))" } ?? (content.isOffline ? "Waiting for a connection" : "Library not synced yet"))
                .themeText(.label, size: 9.5)
                .foregroundStyle(theme.inkSecondary)
                .lineLimit(1)
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("play.syncLine")
    }

    private func queueLine(_ download: PlayDownloadItem) -> String {
        var parts: [String] = []
        if download.waiting > 0 { parts.append("\(download.waiting) waiting") }
        if download.failed > 0 { parts.append("\(download.failed) failed") }
        return parts.isEmpty ? "Downloading" : parts.joined(separator: " · ")
    }

    // MARK: The lead

    private func leadStory(_ game: PlayGameItem, big: Bool) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Kicker(label: "Continue playing", detail: [game.rom.platformSlug.uppercased(), game.lastPlayedAt.map { PlayFormat.relative($0) }]
                    .compactMap { $0 }.joined(separator: " · "), fill: .pink)
            Button { actions.open(game.rom) } label: {
                DisplayText(text: game.rom.displayName, role: .display, size: big ? 76 : 60, lineLimit: 2)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.plain)
            RatioColumns(ratio: big ? 0.63 : 0.6, spacing: big ? 18 : 14) {
                VStack(alignment: .leading, spacing: 7) {
                    GameCover(url: game.artworkURL, title: game.rom.displayName, maxPointSize: big ? 360 : 260)
                        .aspectRatio(3 / 4, contentMode: .fit)
                        .onTapGesture { actions.open(game.rom) }
                        .contextMenu { PlayGameMenu(game: game, actions: actions) }
                    FigureCaption(text: "\(game.rom.platformName). \(game.lastPlayedAt.map { "Played \(PlayFormat.inline($0))." } ?? "Not played on this device yet.")")
                }
                VStack(alignment: .leading, spacing: 12) {
                    Text(dek(game))
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
                    Button { actions.resume(game.rom) } label: {
                        Label("Resume", systemImage: "play.fill")
                    }
                    .buttonStyle(PrimaryButtonStyle(large: true))
                    .accessibilityLabel("Resume \(game.rom.displayName)")
                    .accessibilityIdentifier("play.resume.\(game.rom.id)")
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("play.continue")
    }

    private func dek(_ game: PlayGameItem) -> String {
        guard let last = game.lastPlayedAt else { return "Pick up where you left off. Your resume point is waiting." }
        return "You last played \(PlayFormat.inline(last)), \(PlayFormat.duration(game.playtime)) in so far on this device."
    }

    // MARK: Briefs and the pinned collection

    @ViewBuilder
    private var columns: some View {
        let hasBriefs = !briefs.isEmpty
        let collection = content.collection
        if hasBriefs || collection != nil {
            HStack(alignment: .top, spacing: 0) {
                if hasBriefs {
                    VStack(alignment: .leading, spacing: 12) {
                        columnHeading("In progress")
                        ForEach(briefs.prefix(collection == nil ? 4 : 3)) { brief($0) }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.trailing, collection == nil ? 0 : 14)
                }
                if let collection {
                    if hasBriefs { Rectangle().fill(theme.ink).frame(width: 1) }
                    collectionColumn(collection, wide: !hasBriefs)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.leading, hasBriefs ? 14 : 0)
                }
            }
            .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func columnHeading(_ title: String) -> some View {
        Text(title)
            .themeText(.section, size: 16)
            .foregroundStyle(theme.ink)
            .accessibilityAddTraits(.isHeader)
    }

    private func brief(_ game: PlayGameItem) -> some View {
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

    private func collectionColumn(_ item: PlayCollectionItem, wide: Bool) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Button { actions.openCollection(item.collection) } label: {
                HStack(spacing: 8) {
                    columnHeading(item.collection.name)
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
                        HStack(alignment: .firstTextBaseline, spacing: 8) {
                            Text("\(index + 1)")
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
                    .buttonStyle(.plain)
                }
            }
        }
    }

    // MARK: Notices and empty

    @ViewBuilder
    private var notices: some View {
        if let staleMessage = content.staleMessage {
            VStack(alignment: .leading, spacing: 4) {
                Kicker(label: "Showing your saved library", fill: .yellow)
                Text(staleMessage).themeText(.caption).foregroundStyle(theme.inkSecondary).lineLimit(3)
            }
        }
        if let error = content.errorMessage {
            Text(error)
                .themeText(.body, size: 15)
                .foregroundStyle(theme.ink)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("play.error")
        } else if content.isEmpty && !content.isLoading {
            EmptyStateView(title: "Nothing to continue yet", systemImage: "play.circle",
                           message: "Games you play show up here. Open the Library to start one.")
        }
    }
}
