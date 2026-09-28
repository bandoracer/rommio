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
struct StatsActions: PageActions {
    let open: (RomDTO) -> Void
    let resume: (RomDTO) -> Void
}

/// One day of recent sessions.
struct StatsDay: Identifiable, Hashable {
    let day: Date
    let sessions: [PlaySessionItem]
    var id: Date { day }
    var playtime: TimeInterval { sessions.reduce(0) { $0 + $1.playtime } }
}

/// The last 28 days in figures (Riso's month).
struct StatsWindow: Hashable {
    var playtime: TimeInterval = 0
    var sessions = 0
    var games = 0
}

/// Everything the Stats layouts draw, built once per change of the play data
/// (`StatsContent.build`), never in a view.
struct StatsContent: Equatable {
    var total: TimeInterval = 0
    var sessionCount = 0
    var streakDays = 0
    var lastPlayedAt: Date?
    var games: [StatsGameItem] = []
    var sessions: [PlaySessionItem] = [] {
        didSet { sessionDays = Self.days(sessions) }
    }
    var isLoading = false
    var errorMessage: String?
    /// Some play shown was estimated from automatic saves (from before sessions were recorded).
    var includesEstimates = false
    /// Recent sessions by day, newest first.
    private(set) var sessionDays: [StatsDay] = []

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
    func window(days: Int = 28, now: Date = Date()) -> StatsWindow {
        let start = now.addingTimeInterval(-TimeInterval(days) * 86_400)
        let recent = sessions.filter { $0.endedAt >= start }
        return StatsWindow(playtime: recent.reduce(0) { $0 + $1.playtime }, sessions: recent.count, games: Set(recent.map(\.rom.id)).count)
    }

    var streakText: String { streakDays == 1 ? "1 day" : "\(streakDays) days" }

    private static func days(_ sessions: [PlaySessionItem]) -> [StatsDay] {
        let calendar = Calendar.current
        let groups = Dictionary(grouping: sessions) { calendar.startOfDay(for: $0.endedAt) }
        return groups.keys.sorted(by: >).map { StatsDay(day: $0, sessions: groups[$0]!.sorted { $0.endedAt > $1.endedAt }) }
    }

    /// Stats from the feature models: play on this device, most played first.
    @MainActor
    static func build(_ model: RommioAppModel) -> StatsContent {
        let feature = model.statsFeature
        let stats = feature.stats
        let installed = model.installedRomIDs
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
        // Loading only shows in an empty state: a refresh of a full page doesn't republish it.
        content.isLoading = feature.isLoading && content.isEmpty
        content.errorMessage = feature.errorMessage
        return content
    }
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
            StatsLayout(content: model.statsContent.value, actions: actions, riso: theme.isRiso)
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
        .appNavigator($path)
    }

    private var actions: StatsActions {
        let model = model
        let path = $path
        let conflict = $conflict
        return StatsActions(
            open: { path.wrappedValue.append(AppDestination.game($0)) },
            // A game whose resume point is in conflict asks which to keep first (as on Play).
            resume: { rom in
                if let found = model.savesFeature.conflicts.first(where: { $0.romId == Int64(rom.id) }) {
                    conflict.wrappedValue = ConflictItem(conflict: found, rom: rom, artworkURL: model.artworkURL(path: rom.urlCover), installed: true)
                } else {
                    Task { await model.quickResume(rom: rom) }
                }
            }
        )
    }
}

/// The theme's Stats layout.
private struct StatsLayout: View {
    let content: StatsContent
    let actions: StatsActions
    let riso: Bool

    var body: some View {
        if riso {
            RisoStatsLayout(content: content, actions: actions)
        } else {
            AuroraStatsLayout(content: content, actions: actions)
        }
    }
}

private struct StatsEmpty: View {
    @Environment(\.rommioTheme) private var theme
    let isLoading: Bool
    let errorMessage: String?

    var body: some View {
        VStack(spacing: 8) {
            EmptyStateView(
                title: isLoading ? "Adding up your play" : "No play recorded yet",
                systemImage: "chart.bar",
                message: isLoading ? nil : "Play a game on this device and your time, sessions and streak show up here."
            )
            .padding(.top, 40)
            if let errorMessage {
                Text(errorMessage).themeText(.caption).foregroundStyle(theme.inkSecondary).frame(maxWidth: .infinity)
            }
        }
    }
}

// MARK: Aurora

struct AuroraStatsLayout: View {
    let content: StatsContent
    let actions: StatsActions
    @State private var palettes: [URL: CoverPalette] = [:]

    var body: some View {
        ThemedScrollPage(style: .page, maxWidth: 1000) {
            if content.isEmpty {
                StatsEmpty(isLoading: content.isLoading, errorMessage: content.errorMessage)
            } else {
                if let top = content.top { AuroraStatsHero(top: top, actions: actions) }
                AuroraStatsSummary(total: content.total, sessionCount: content.sessionCount, streak: content.streakText,
                                   lastPlayedAt: content.lastPlayedAt, lastGame: content.sessions.first?.rom.displayName)
                AuroraTimeByGame(games: Array(content.games.prefix(8)), count: content.games.count, palettes: palettes, actions: actions)
                AuroraRecentSessions(days: Array(content.sessionDays.prefix(5)), sessionCount: content.sessionCount, palettes: palettes, actions: actions)
                StatsFootnote(text: content.footnote)
            }
        }
        .litBy(content.top?.artworkURL)
        .coverPalettes(content.games.prefix(8).compactMap(\.artworkURL), into: $palettes)
    }
}

private struct StatsFootnote: View {
    @Environment(\.rommioTheme) private var theme
    let text: String

    var body: some View {
        Text(text)
            .themeText(.caption)
            .foregroundStyle(theme.isRiso ? theme.inkSecondary : theme.inkTertiary)
            .padding(.top, theme.isRiso ? 20 : 0)
            .accessibilityIdentifier("stats.footnote")
    }
}

private struct AuroraStatsHero: View {
    @Environment(\.rommioTheme) private var theme
    let top: StatsGameItem
    let actions: StatsActions

    var body: some View {
        HStack(alignment: .center, spacing: 18) {
            Button { actions.open(top.rom) } label: {
                GlowingCover(url: top.artworkURL, title: top.rom.displayName, height: 150, reflection: false)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(top.rom.displayName) details")
            VStack(alignment: .leading, spacing: 6) {
                Button { actions.open(top.rom) } label: {
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
}

private struct AuroraStatsSummary: View {
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    let total: TimeInterval
    let sessionCount: Int
    let streak: String
    let lastPlayedAt: Date?
    let lastGame: String?

    var body: some View {
        let columns = Array(repeating: GridItem(.flexible(), spacing: 12), count: horizontalSizeClass == .regular ? 4 : 2)
        LazyVGrid(columns: columns, spacing: 12) {
            StatCard(title: "Total playtime", value: PlayFormat.compactDuration(total), systemImage: "clock")
                .accessibilityIdentifier("stats.total")
            StatCard(title: "Sessions", value: PlayFormat.count(sessionCount), systemImage: "play.circle")
            StatCard(title: "Streak", value: streak, systemImage: "flame")
            StatCard(title: "Last played", value: lastPlayedAt.map { PlayFormat.relative($0) } ?? "—", detail: lastGame, systemImage: "calendar")
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("stats.summary")
    }
}

private struct AuroraTimeByGame: View {
    let games: [StatsGameItem]
    let count: Int
    let palettes: [URL: CoverPalette]
    let actions: StatsActions

    var body: some View {
        let longest = max(games.first?.playtime ?? 1, 1)
        ThemedGroup(title: "Time by game", detail: "\(count)", inset: 68) {
            ForEach(games) { game in
                Button { actions.open(game.rom) } label: {
                    TimeByGameRow(game: game, share: game.playtime / longest, palette: game.artworkURL.flatMap { palettes[$0] })
                }
                .buttonStyle(RowButtonStyle())
                .accessibilityIdentifier("stats.game.\(game.rom.id)")
            }
        }
    }
}

private struct TimeByGameRow: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.pageLight) private var light
    let game: StatsGameItem
    let share: Double
    let palette: CoverPalette?

    var body: some View {
        let mark = light.mark(for: palette)
        VStack(alignment: .leading, spacing: 7) {
            GameRow(rom: game.rom, artworkURL: game.artworkURL,
                    subtitle: "\(PlayFormat.count(game.sessionCount, "session")) · last \(PlayFormat.inline(game.lastPlayedAt))") {
                Text(PlayFormat.duration(game.playtime)).themeText(.numeral).foregroundStyle(theme.ink)
            }
            // (No glow: a shadow per bar was redrawn on every frame of a scroll.)
            Capsule()
                .fill(mark)
                .frame(maxWidth: .infinity, alignment: .leading)
                .frame(height: 4)
                .scaleEffect(x: max(0.02, share), y: 1, anchor: .leading)
                .padding(.leading, 52)
        }
    }
}

private struct AuroraRecentSessions: View {
    let days: [StatsDay]
    let sessionCount: Int
    let palettes: [URL: CoverPalette]
    let actions: StatsActions

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            SectionHeader(title: "Recent sessions", detail: PlayFormat.count(sessionCount))
            LazyVStack(alignment: .leading, spacing: 18) {
                ForEach(days) { day in
                    AuroraSessionDay(day: day, palettes: palettes, actions: actions)
                }
            }
        }
    }
}

private struct AuroraSessionDay: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.pageLight) private var light
    let day: StatsDay
    let palettes: [URL: CoverPalette]
    let actions: StatsActions

    var body: some View {
        var seen = Set<Int>()
        let lights = day.sessions.filter { seen.insert($0.rom.id).inserted }.map { light.mark(for: $0.artworkURL, in: palettes) }
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .lastTextBaseline) {
                Text(PlayFormat.dayTitle(day.day)).themeText(.headline).foregroundStyle(theme.ink).accessibilityAddTraits(.isHeader)
                Spacer()
                Text(PlayFormat.line([PlayFormat.count(day.sessions.count, "session"), PlayFormat.duration(day.playtime)]))
                    .themeText(.numeral, size: 12.5).foregroundStyle(theme.inkSecondary)
            }
            LightStrip(colours: lights)
            ThemedGroup(inset: 68) {
                ForEach(day.sessions) { session in
                    Button { actions.open(session.rom) } label: {
                        AuroraSessionRow(session: session)
                    }
                    .buttonStyle(RowButtonStyle())
                }
            }
        }
    }
}

private struct AuroraSessionRow: View {
    @Environment(\.rommioTheme) private var theme
    let session: PlaySessionItem

    var body: some View {
        GameRow(rom: session.rom, artworkURL: session.artworkURL,
                subtitle: [PlayFormat.clock(session.endedAt), session.endReason?.title].compactMap { $0 }.joined(separator: " · ")) {
            Text(PlayFormat.duration(session.playtime)).themeText(.numeral).foregroundStyle(theme.inkSecondary)
        }
    }
}

// MARK: Riso

struct RisoStatsLayout: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    let content: StatsContent
    let actions: StatsActions

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                if content.isEmpty {
                    StatsEmpty(isLoading: content.isLoading, errorMessage: content.errorMessage)
                } else {
                    RisoStatsStandfirst(sessionCount: content.sessionCount)
                    if horizontalSizeClass == .regular {
                        HStack(alignment: .top, spacing: 0) {
                            VStack(alignment: .leading, spacing: 0) {
                                RisoStatsMonth(content: RisoMonthContent(content), actions: actions)
                                RisoTimeByGame(games: Array(content.games.prefix(6)), count: content.games.count, actions: actions)
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.trailing, 20)
                            Rectangle().fill(theme.ink).frame(width: 1)
                            RisoDiary(days: Array(content.sessionDays.prefix(6)), actions: actions)
                                .frame(maxWidth: .infinity)
                                .padding(.leading, 20)
                        }
                    } else {
                        RisoStatsMonth(content: RisoMonthContent(content), actions: actions)
                        RisoTimeByGame(games: Array(content.games.prefix(6)), count: content.games.count, actions: actions)
                        // The diary's days are items of this page's lazy stack.
                        ForEach(content.sessionDays.prefix(6)) { day in
                            RisoDiaryDay(day: day, actions: actions)
                        }
                    }
                    StatsFootnote(text: content.footnote)
                }
            }
            .padding(.horizontal, theme.spacing.gutter)
            .padding(.bottom, 40)
            .frame(maxWidth: 1100)
            .frame(maxWidth: .infinity)
        }
        .themedPage()
    }
}

private struct RisoStatsStandfirst: View {
    @Environment(\.rommioTheme) private var theme
    let sessionCount: Int

    var body: some View {
        Text("\(PlayFormat.count(sessionCount, "session")) recorded · kept on this device")
            .themeText(.body, size: 15)
            .italic()
            .foregroundStyle(theme.inkSecondary)
            .padding(.bottom, 12)
    }
}

/// The month's figures, bars and lead.
private struct RisoMonthContent: Equatable {
    let window: StatsWindow
    let daily: [TimeInterval]
    let total: TimeInterval
    let sessionCount: Int
    let streakDays: Int
    let streakText: String
    let top: StatsGameItem?

    init(_ content: StatsContent) {
        window = content.window()
        daily = content.daily()
        total = content.total
        sessionCount = content.sessionCount
        streakDays = content.streakDays
        streakText = content.streakText
        top = content.top
    }
}

/// The month in ink: a sentence of figures and a bar per day.
private struct RisoStatsMonth: View {
    @Environment(\.rommioTheme) private var theme
    let content: RisoMonthContent
    let actions: StatsActions

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .lastTextBaseline) {
                Text("This month").themeText(.section, size: 18).foregroundStyle(theme.ink).accessibilityAddTraits(.isHeader)
                Spacer()
                Text("Last 28 days").themeText(.label, size: 10).foregroundStyle(theme.inkSecondary)
            }
            RisoMonthSentence(window: content.window, streakDays: content.streakDays, streakText: content.streakText)
            RisoDailyBars(daily: content.daily)
            HStack {
                Text("4 weeks ago")
                Spacer()
                Text("This week")
            }
            .themeText(.label, size: 9.5)
            .foregroundStyle(theme.inkSecondary)
            StatStrip(items: [StatStrip.Item("Total", PlayFormat.compactDuration(content.total)), StatStrip.Item("Sessions", PlayFormat.count(content.sessionCount)),
                              StatStrip.Item("Streak", content.streakText)])
                .padding(.top, 6)
                .accessibilityIdentifier("stats.summary")
            if let top = content.top { RisoStatsLead(top: top, actions: actions).padding(.top, 10) }
        }
        .padding(.top, 10)
        .padding(.bottom, 14)
        .overlay(alignment: .top) { Rectangle().fill(theme.ink).frame(height: 3) }
    }
}

private struct RisoMonthSentence: View {
    @Environment(\.rommioTheme) private var theme
    let window: StatsWindow
    let streakDays: Int
    let streakText: String

    var body: some View {
        (Text(PlayFormat.duration(window.playtime)).bold() + Text(" across ") + Text(PlayFormat.count(window.sessions, "session")).bold()
            + Text(" and ") + Text(PlayFormat.games(window.games)).bold()
            + Text(streakDays > 0 ? ". Streak: \(streakText)." : "."))
            .themeText(.body, size: 17)
            .foregroundStyle(theme.ink)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityIdentifier("stats.total")
    }
}

private struct RisoDailyBars: View {
    @Environment(\.rommioTheme) private var theme
    let daily: [TimeInterval]

    var body: some View {
        let longest = max(daily.max() ?? 1, 1)
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
    }
}

/// The month's lead: the most played game, and Resume when it is on this device.
private struct RisoStatsLead: View {
    @Environment(\.rommioTheme) private var theme
    let top: StatsGameItem
    let actions: StatsActions

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            Button { actions.open(top.rom) } label: {
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
}

private struct RisoTimeByGame: View {
    let games: [StatsGameItem]
    let count: Int
    let actions: StatsActions

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionHeader(title: "Time by game", detail: "\(count)").padding(.top, 20)
            ForEach(Array(games.enumerated()), id: \.element.id) { index, game in
                Button { actions.open(game.rom) } label: {
                    RisoTimeByGameRow(rank: index + 1, game: game)
                }
                .buttonStyle(RowButtonStyle())
                .accessibilityIdentifier("stats.game.\(game.rom.id)")
            }
        }
    }
}

private struct RisoTimeByGameRow: View {
    @Environment(\.rommioTheme) private var theme
    let rank: Int
    let game: StatsGameItem

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text("\(rank)").themeText(.statValue, size: 22).foregroundStyle(theme.accent2).frame(width: 22, alignment: .leading)
            Text(game.rom.displayName).themeText(.bodyEmphasis, size: 16).foregroundStyle(theme.ink).lineLimit(1).layoutPriority(1)
            DotLeader()
            Text(PlayFormat.duration(game.playtime)).themeText(.numeral, size: 12).foregroundStyle(theme.ink).fixedSize()
        }
        .frame(minHeight: 46)
        .overlay(alignment: .bottom) { Rectangle().fill(theme.hairline).frame(height: 1) }
        .contentShape(Rectangle())
    }
}

/// Recent sessions by day, as diary entries (the wide layout's right column).
private struct RisoDiary: View {
    let days: [StatsDay]
    let actions: StatsActions

    var body: some View {
        LazyVStack(alignment: .leading, spacing: 0) {
            ForEach(days) { day in RisoDiaryDay(day: day, actions: actions) }
        }
    }
}

private struct RisoDiaryDay: View {
    @Environment(\.rommioTheme) private var theme
    let day: StatsDay
    let actions: StatsActions

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
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
                Button { actions.open(session.rom) } label: {
                    RisoDiaryEntry(session: session, tilt: index.isMultiple(of: 2) ? -1.2 : 1.4)
                }
                .buttonStyle(.plain)
            }
        }
    }
}

private struct RisoDiaryEntry: View {
    @Environment(\.rommioTheme) private var theme
    let session: PlaySessionItem
    let tilt: Double

    var body: some View {
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
                .rotationEffect(.degrees(tilt))
        }
        .padding(.vertical, 11)
        .overlay(alignment: .bottom) { Rectangle().fill(theme.hairline).frame(height: 1) }
        .contentShape(Rectangle())
    }
}

/// Figures in a ruled strip: mono labels over display numerals (Riso "numbers").
struct StatStrip: View {
    struct Item: Hashable {
        let title: String
        let value: String
        init(_ title: String, _ value: String) {
            self.title = title
            self.value = value
        }
    }

    @Environment(\.rommioTheme) private var theme
    let items: [Item]

    var body: some View {
        HStack(spacing: 0) {
            ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                if index > 0 { Rectangle().fill(theme.ink).frame(width: 1) }
                StatCard(title: item.title, value: item.value)
            }
        }
        .fixedSize(horizontal: false, vertical: true)
        .overlay { Rectangle().strokeBorder(theme.ink, lineWidth: 2) }
    }
}
