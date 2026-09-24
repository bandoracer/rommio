import SwiftUI
import RommioContract
import RommioFoundation

/// A game's total play on this device.
struct StatsGameItem: Identifiable, Hashable {
    let rom: RomDTO
    let artworkURL: URL?
    let playtime: TimeInterval
    let sessionCount: Int
    let lastPlayedAt: Date
    /// On this device: Resume can start it.
    var playable = false
    var id: Int { rom.id }
}

/// What Stats can do: open a game's page, or resume it (the player opens from Stats).
struct StatsActions {
    let open: (RomDTO) -> Void
    let resume: (RomDTO) -> Void
}

struct StatsContent {
    var total: TimeInterval = 0
    var sessionCount = 0
    var streakDays = 0
    var lastPlayedAt: Date?
    var games: [StatsGameItem] = []
    var sessions: [PlaySessionItem] = []
    var isLoading = false
    var errorMessage: String?
    /// Some play shown was estimated from automatic saves (from before sessions were recorded).
    var includesEstimates = false

    var isEmpty: Bool { sessions.isEmpty }

    /// Recorded sessions count active play; earlier play is estimated (docs/play-data-local.md §1).
    var footnote: String {
        includesEstimates
            ? "Play time counts active play on this device; paused and background time is left out. Play from before sessions were recorded is estimated from automatic saves."
            : "Play time counts active play on this device; paused and background time is left out."
    }
    var top: StatsGameItem? { games.first }

    /// Playtime per day for the last `days` days, oldest first.
    func daily(days: Int = 28, now: Date = Date(), calendar: Calendar = .current) -> [TimeInterval] {
        let today = calendar.startOfDay(for: now)
        var totals = [TimeInterval](repeating: 0, count: days)
        for session in sessions {
            let day = calendar.startOfDay(for: session.endedAt)
            guard let offset = calendar.dateComponents([.day], from: day, to: today).day, offset >= 0, offset < days else { continue }
            totals[days - 1 - offset] += session.playtime
        }
        return totals
    }

    /// Sessions, games and playtime within the last `days` days.
    func window(days: Int = 28, now: Date = Date()) -> (playtime: TimeInterval, sessions: Int, games: Int) {
        let start = now.addingTimeInterval(-TimeInterval(days) * 86_400)
        let recent = sessions.filter { $0.endedAt >= start }
        return (recent.reduce(0) { $0 + $1.playtime }, recent.count, Set(recent.map(\.rom.id)).count)
    }

    var sessionDays: [(day: Date, sessions: [PlaySessionItem])] {
        let calendar = Calendar.current
        let groups = Dictionary(grouping: sessions) { calendar.startOfDay(for: $0.endedAt) }
        return groups.keys.sorted(by: >).map { ($0, groups[$0]!.sorted { $0.endedAt > $1.endedAt }) }
    }

    var streakText: String { streakDays == 1 ? "1 day" : "\(streakDays) days" }
}

/// Stats, from play recorded on this device. Aurora: summary cards and time by
/// game, lit by the most played game. Riso: the month in ink, then recent
/// sessions by day.
struct StatsPage: View {
    @Environment(\.rommioTheme) private var theme
    let model: RommioAppModel
    let feature: StatsFeatureModel
    @State private var path = NavigationPath()
    @State private var conflict: ConflictItem?

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if theme.isRiso {
                    RisoStatsLayout(content: content, actions: actions)
                } else {
                    AuroraStatsLayout(content: content, actions: actions)
                }
            }
            .navigationTitle("Stats")
            .appDestinations(model)
            .accessibilityIdentifier("shell.statsList")
            .task { await feature.refresh() }
            .task { await model.savesFeature.refresh() }
            .refreshable { await feature.refresh() }
            .sheet(item: $conflict) { item in
                ConflictSheet(item: item) { choice in
                    Task { await model.keepPlaying(item.conflict, choice: choice) }
                }
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
            }
        }
    }

    private var actions: StatsActions {
        StatsActions(
            open: { path.append(AppDestination.game($0)) },
            // A game whose resume point is in conflict asks which to keep first (as on Play).
            resume: { rom in
                if let found = model.savesFeature.conflicts.first(where: { $0.romId == Int64(rom.id) }) {
                    conflict = ConflictItem(conflict: found, rom: rom, artworkURL: model.artworkURL(path: rom.urlCover), installed: true)
                } else {
                    Task { await model.quickResume(rom: rom) }
                }
            }
        )
    }

    private var content: StatsContent {
        let stats = feature.stats
        let installed = Set(model.downloadsFeature.records.filter { $0.status == .completed }.map(\.romID))
        var content = StatsContent()
        content.total = stats.totalPlaytime
        content.sessionCount = stats.sessionCount
        content.streakDays = stats.currentStreakDays
        content.lastPlayedAt = stats.lastPlayedAt
        content.games = stats.games.map { game in
            let rom = feature.game(game.romID)
            return StatsGameItem(rom: rom, artworkURL: model.artworkURL(path: rom.urlCover), playtime: game.playtime,
                                 sessionCount: game.sessionCount, lastPlayedAt: game.lastPlayedAt, playable: installed.contains(rom.id))
        }
        content.sessions = stats.sessions.prefix(40).map { session in
            let rom = feature.game(session.romID)
            return PlaySessionItem(id: session.id, rom: rom, artworkURL: model.artworkURL(path: rom.urlCover),
                                   startedAt: session.startedAt, endedAt: session.endedAt, playtime: session.playtime,
                                   endReason: session.endReason)
        }
        content.includesEstimates = stats.includesEstimates
        content.isLoading = feature.isLoading
        content.errorMessage = feature.errorMessage
        return content
    }
}

private struct StatsEmpty: View {
    @Environment(\.rommioTheme) private var theme
    let content: StatsContent

    var body: some View {
        VStack(spacing: 8) {
            EmptyStateView(
                title: content.isLoading ? "Adding up your play" : "No play recorded yet",
                systemImage: "chart.bar",
                message: content.isLoading ? nil : "Play a game on this device and your time, sessions and streak show up here."
            )
            .padding(.top, 40)
            if let error = content.errorMessage {
                Text(error).themeText(.caption).foregroundStyle(theme.inkSecondary).frame(maxWidth: .infinity)
            }
        }
    }
}

// MARK: Aurora

private struct AuroraStatsLayout: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    let content: StatsContent
    let actions: StatsActions
    private var open: (RomDTO) -> Void { actions.open }
    @State private var palettes: [URL: CoverPalette] = [:]

    var body: some View {
        ThemedScrollPage(style: .page, maxWidth: 1000) {
            if content.isEmpty {
                StatsEmpty(content: content)
            } else {
                if let top = content.top { hero(top) }
                summary
                timeByGame
                recent
                Text(content.footnote)
                    .themeText(.caption)
                    .foregroundStyle(theme.inkTertiary)
                    .accessibilityIdentifier("stats.footnote")
            }
        }
        .litBy(content.top?.artworkURL)
        .coverPalettes(content.games.prefix(8).compactMap(\.artworkURL), into: $palettes)
    }

    private func hero(_ top: StatsGameItem) -> some View {
        HStack(alignment: .center, spacing: 18) {
            Button { open(top.rom) } label: {
                GlowingCover(url: top.artworkURL, title: top.rom.displayName, height: 150, reflection: false)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(top.rom.displayName) details")
            VStack(alignment: .leading, spacing: 6) {
                Button { open(top.rom) } label: {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Most played").themeText(.section).foregroundStyle(theme.inkSecondary)
                        Text(top.rom.displayName).themeText(.title, size: 30).foregroundStyle(theme.ink).lineLimit(3)
                        Text(PlayFormat.line([PlayFormat.duration(top.playtime), PlayFormat.count(top.sessionCount, "session")]))
                            .themeText(.secondary).foregroundStyle(theme.inkSecondary)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("stats.top")
                if top.playable {
                    Button { actions.resume(top.rom) } label: {
                        Label("Resume", systemImage: "play.fill")
                    }
                    .buttonStyle(PrimaryButtonStyle(fullWidth: false, large: false))
                    .accessibilityLabel("Resume \(top.rom.displayName)")
                    .accessibilityIdentifier("stats.resume")
                    .padding(.top, 6)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 6)
    }

    private var summary: some View {
        let columns = Array(repeating: GridItem(.flexible(), spacing: 12), count: horizontalSizeClass == .regular ? 4 : 2)
        return LazyVGrid(columns: columns, spacing: 12) {
            StatCard(title: "Total playtime", value: PlayFormat.compactDuration(content.total), systemImage: "clock")
                .accessibilityIdentifier("stats.total")
            StatCard(title: "Sessions", value: PlayFormat.count(content.sessionCount), systemImage: "play.circle")
            StatCard(title: "Streak", value: content.streakText, systemImage: "flame")
            StatCard(title: "Last played", value: content.lastPlayedAt.map { PlayFormat.relative($0) } ?? "—",
                     detail: content.sessions.first?.rom.displayName, systemImage: "calendar")
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("stats.summary")
    }

    private var timeByGame: some View {
        let games = Array(content.games.prefix(8))
        let longest = max(games.first?.playtime ?? 1, 1)
        return ThemedGroup(title: "Time by game", detail: "\(content.games.count)", inset: 68) {
            ForEach(games) { game in
                Button { open(game.rom) } label: {
                    VStack(alignment: .leading, spacing: 7) {
                        GameRow(rom: game.rom, artworkURL: game.artworkURL,
                                subtitle: "\(PlayFormat.count(game.sessionCount, "session")) · last \(PlayFormat.inline(game.lastPlayedAt))") {
                            Text(PlayFormat.duration(game.playtime)).themeText(.numeral).foregroundStyle(theme.ink)
                        }
                        let mark = theme.mark(for: game.artworkURL, in: palettes)
                        Capsule()
                            .fill(mark)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .frame(height: 4)
                            .scaleEffect(x: max(0.02, game.playtime / longest), y: 1, anchor: .leading)
                            .shadow(color: mark.opacity(0.6), radius: 5)
                            .padding(.leading, 52)
                    }
                }
                .buttonStyle(RowButtonStyle())
                .accessibilityIdentifier("stats.game.\(game.rom.id)")
            }
        }
    }

    private var recent: some View {
        let days = Array(content.sessionDays.prefix(5))
        return VStack(alignment: .leading, spacing: 18) {
            SectionHeader(title: "Recent sessions", detail: PlayFormat.count(content.sessionCount))
            ForEach(days, id: \.day) { day in
                let total = day.sessions.reduce(0) { $0 + $1.playtime }
                var seen = Set<Int>()
                let lights = day.sessions.filter { seen.insert($0.rom.id).inserted }.map { theme.mark(for: $0.artworkURL, in: palettes) }
                VStack(alignment: .leading, spacing: 10) {
                    HStack(alignment: .lastTextBaseline) {
                        Text(PlayFormat.dayTitle(day.day)).themeText(.headline).foregroundStyle(theme.ink).accessibilityAddTraits(.isHeader)
                        Spacer()
                        Text(PlayFormat.line([PlayFormat.count(day.sessions.count, "session"), PlayFormat.duration(total)]))
                            .themeText(.numeral, size: 12.5).foregroundStyle(theme.inkSecondary)
                    }
                    LightStrip(colours: lights)
                    ThemedGroup(inset: 68) {
                        ForEach(day.sessions) { session in
                            Button { open(session.rom) } label: {
                                GameRow(rom: session.rom, artworkURL: session.artworkURL,
                                        subtitle: [PlayFormat.clock(session.endedAt), session.endReason?.title].compactMap { $0 }.joined(separator: " · ")) {
                                    Text(PlayFormat.duration(session.playtime)).themeText(.numeral).foregroundStyle(theme.inkSecondary)
                                }
                            }
                            .buttonStyle(RowButtonStyle())
                        }
                    }
                }
            }
        }
    }
}

// MARK: Riso

private struct RisoStatsLayout: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    let content: StatsContent
    let actions: StatsActions
    private var open: (RomDTO) -> Void { actions.open }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if content.isEmpty {
                    StatsEmpty(content: content)
                } else {
                    Text("\(PlayFormat.count(content.sessionCount, "session")) recorded · kept on this device")
                        .themeText(.body, size: 15)
                        .italic()
                        .foregroundStyle(theme.inkSecondary)
                        .padding(.bottom, 12)
                    if horizontalSizeClass == .regular {
                        HStack(alignment: .top, spacing: 0) {
                            VStack(alignment: .leading, spacing: 0) { month; games }
                                .frame(maxWidth: .infinity)
                                .padding(.trailing, 20)
                            Rectangle().fill(theme.ink).frame(width: 1)
                            diary.frame(maxWidth: .infinity).padding(.leading, 20)
                        }
                    } else {
                        month
                        games
                        diary
                    }
                    Text(content.footnote)
                        .themeText(.caption)
                        .foregroundStyle(theme.inkSecondary)
                        .padding(.top, 20)
                        .accessibilityIdentifier("stats.footnote")
                }
            }
            .padding(.horizontal, theme.spacing.gutter)
            .padding(.bottom, 40)
            .frame(maxWidth: 1100)
            .frame(maxWidth: .infinity)
        }
        .themedPage()
    }

    /// The month in ink: a sentence of figures and a bar per day.
    private var month: some View {
        let window = content.window()
        let daily = content.daily()
        let longest = max(daily.max() ?? 1, 1)
        return VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .lastTextBaseline) {
                Text("This month").themeText(.section, size: 18).foregroundStyle(theme.ink).accessibilityAddTraits(.isHeader)
                Spacer()
                Text("Last 28 days").themeText(.label, size: 10).foregroundStyle(theme.inkSecondary)
            }
            (Text(PlayFormat.duration(window.playtime)).bold() + Text(" across ") + Text(PlayFormat.count(window.sessions, "session")).bold()
                + Text(" and ") + Text(PlayFormat.games(window.games)).bold()
                + Text(content.streakDays > 0 ? ". Streak: \(content.streakText)." : "."))
                .themeText(.body, size: 17)
                .foregroundStyle(theme.ink)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("stats.total")
            HStack(alignment: .bottom, spacing: 3) {
                ForEach(Array(daily.enumerated()), id: \.offset) { index, value in
                    if value > 0 {
                        Rectangle()
                            .fill(index >= daily.count - 7 ? theme.accent1 : theme.accent2)
                            .frame(height: max(4, 54 * value / longest))
                    } else {
                        Rectangle().fill(theme.hairline).frame(height: 1.5)
                    }
                }
            }
            .frame(height: 56, alignment: .bottom)
            .accessibilityElement()
            .accessibilityLabel("Playtime per day for the last 28 days")
            HStack {
                Text("4 weeks ago")
                Spacer()
                Text("This week")
            }
            .themeText(.label, size: 9.5)
            .foregroundStyle(theme.inkSecondary)
            StatStrip(items: [("Total", PlayFormat.compactDuration(content.total)), ("Sessions", PlayFormat.count(content.sessionCount)),
                              ("Streak", content.streakText)])
                .padding(.top, 6)
                .accessibilityIdentifier("stats.summary")
            if let top = content.top { lead(top).padding(.top, 10) }
        }
        .padding(.top, 10)
        .padding(.bottom, 14)
        .overlay(alignment: .top) { Rectangle().fill(theme.ink).frame(height: 3) }
    }

    /// The month's lead: the most played game, and Resume when it is on this device.
    private func lead(_ top: StatsGameItem) -> some View {
        HStack(alignment: .center, spacing: 12) {
            Button { open(top.rom) } label: {
                HStack(alignment: .center, spacing: 12) {
                    GameCover(url: top.artworkURL, title: top.rom.displayName, maxPointSize: 90)
                        .frame(width: 48, height: 64)
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Most played").themeText(.label, size: 9.5).foregroundStyle(theme.inkSecondary)
                        Text(top.rom.displayName).themeText(.headline, size: 20).foregroundStyle(theme.ink).lineLimit(2)
                        Text(PlayFormat.line([PlayFormat.duration(top.playtime), PlayFormat.count(top.sessionCount, "session")]))
                            .themeText(.label, size: 9.5).foregroundStyle(theme.inkSecondary)
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("stats.top")
            if top.playable {
                Button { actions.resume(top.rom) } label: {
                    Image(systemName: "play.fill")
                }
                .buttonStyle(IconButtonStyle(size: 48, prominent: true))
                .accessibilityLabel("Resume \(top.rom.displayName)")
                .accessibilityIdentifier("stats.resume")
            }
        }
    }

    private var games: some View {
        let top = Array(content.games.prefix(6))
        return VStack(alignment: .leading, spacing: 0) {
            SectionHeader(title: "Time by game", detail: "\(content.games.count)").padding(.top, 20)
            ForEach(Array(top.enumerated()), id: \.element.id) { index, game in
                Button { open(game.rom) } label: {
                    HStack(alignment: .firstTextBaseline, spacing: 10) {
                        Text("\(index + 1)").themeText(.statValue, size: 22).foregroundStyle(theme.accent2).frame(width: 22, alignment: .leading)
                        Text(game.rom.displayName).themeText(.bodyEmphasis, size: 16).foregroundStyle(theme.ink).lineLimit(1).layoutPriority(1)
                        DotLeader()
                        Text(PlayFormat.duration(game.playtime)).themeText(.numeral, size: 12).foregroundStyle(theme.ink).fixedSize()
                    }
                    .frame(minHeight: 46)
                    .overlay(alignment: .bottom) { Rectangle().fill(theme.hairline).frame(height: 1) }
                    .contentShape(Rectangle())
                }
                .buttonStyle(RowButtonStyle())
                .accessibilityIdentifier("stats.game.\(game.rom.id)")
            }
        }
    }

    /// Recent sessions by day, as diary entries.
    private var diary: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(content.sessionDays.prefix(6)), id: \.day) { day in
                HStack(alignment: .firstTextBaseline, spacing: 10) {
                    Text(PlayFormat.dayTitle(day.day)).themeText(.headline, size: 26).foregroundStyle(theme.ink).accessibilityAddTraits(.isHeader)
                    if let caption = PlayFormat.dayCaption(day.day) {
                        Text(caption).themeText(.caption, size: 14).foregroundStyle(theme.inkSecondary).lineLimit(1)
                    }
                    Spacer(minLength: 6)
                    Text(PlayFormat.count(day.sessions.count, "entry", "entries")).themeText(.label, size: 10).foregroundStyle(theme.inkSecondary)
                }
                .padding(.top, 18)
                .padding(.bottom, 7)
                .overlay(alignment: .bottom) { Rectangle().fill(theme.ink).frame(height: 1.5) }
                ForEach(Array(day.sessions.enumerated()), id: \.element.id) { index, session in
                    Button { open(session.rom) } label: {
                        HStack(alignment: .top, spacing: 12) {
                            Text(PlayFormat.clock(session.endedAt)).themeText(.numeral, size: 11.5).foregroundStyle(theme.ink)
                                .lineLimit(1).fixedSize().frame(width: 62, alignment: .leading).padding(.top, 3)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(session.rom.displayName).themeText(.headline, size: 19).foregroundStyle(theme.ink).lineLimit(2)
                                Text("\(PlayFormat.duration(session.playtime)) on this device.").themeText(.body, size: 15).foregroundStyle(theme.ink)
                                Text([session.rom.platformSlug.uppercased(), session.endReason?.title].compactMap { $0 }.joined(separator: " · "))
                                    .themeText(.label, size: 9.5).foregroundStyle(theme.inkSecondary)
                            }
                            Spacer(minLength: 8)
                            GameCover(url: session.artworkURL, title: session.rom.displayName, maxPointSize: 90)
                                .frame(width: 52, height: 68)
                                .padding(3)
                                .background(theme.surface)
                                .rotationEffect(.degrees(index.isMultiple(of: 2) ? -1.2 : 1.4))
                        }
                        .padding(.vertical, 11)
                        .overlay(alignment: .bottom) { Rectangle().fill(theme.hairline).frame(height: 1) }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}

/// Figures in a ruled strip: mono labels over display numerals (Riso "numbers").
struct StatStrip: View {
    @Environment(\.rommioTheme) private var theme
    let items: [(String, String)]

    var body: some View {
        HStack(spacing: 0) {
            ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                if index > 0 { Rectangle().fill(theme.ink).frame(width: 1) }
                StatCard(title: item.0, value: item.1)
            }
        }
        .fixedSize(horizontal: false, vertical: true)
        .overlay { Rectangle().strokeBorder(theme.ink, lineWidth: 2) }
    }
}
