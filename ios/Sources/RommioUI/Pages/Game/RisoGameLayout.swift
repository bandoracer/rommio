import SwiftUI
import RommioContract
import RommioFoundation

/// Riso Game: an article. The cover printed across the top; the title as a
/// headline with its standfirst; where you left off in a boxed panel; the main
/// action; the story; and a details box. On a regular-width display the art
/// and headline span the fold and the body runs in three columns.
struct RisoGameLayout: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    let content: GameContent
    let actions: GameActions

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                GameArtHero(url: content.artworkURL, title: content.rom.displayName)
                    .accessibilityIdentifier("gameDetail.hero")
                Group {
                    if horizontalSizeClass == .regular {
                        RisoGameBroadsheet(content: content, actions: actions)
                    } else {
                        RisoGameColumn(content: content, actions: actions)
                    }
                }
                .padding(.horizontal, horizontalSizeClass == .regular ? 28 : theme.spacing.gutter)
                .padding(.top, 16)
                .padding(.bottom, 40)
                .frame(maxWidth: 1100)
                .frame(maxWidth: .infinity)
            }
        }
        .ignoresSafeArea(edges: .top)
        .themedPage(artUnderBar: true)
    }
}

private struct RisoGameColumn: View {
    @Environment(\.rommioTheme) private var theme
    let content: GameContent
    let actions: GameActions

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            RisoGameHeadline(rom: content.rom, platformLine: content.platformLine, standfirst: content.standfirst, large: false)
            RisoGameByline(content: content).padding(.top, 12)
            RisoWhereYouLeftOff(content: content, actions: actions).padding(.top, 22)
            VStack(alignment: .leading, spacing: 12) {
                GameFilePicker(shows: content.showsFilePicker, options: content.fileOptions, selectedFileID: content.selectedFileID, select: actions.selectFile)
                GameActionDeck(deck: content.deck, fileID: content.fileID, romID: content.rom.id, showsStartOptions: content.showsStartOptions, actions: actions, showsNotes: false)
            }
            .padding(.top, 22)
            RisoGameStory(summary: content.hero.summaryText).padding(.top, 26)
            GameScreenshotsRow(urls: content.screenshots, gutter: theme.spacing.gutter)
                .padding(.horizontal, -theme.spacing.gutter)
                .padding(.top, 26)
            RisoGameDetails(content: content).padding(.top, 26)
            RisoGamePreserved(preserved: content.preserved)
        }
    }
}

private struct RisoGameBroadsheet: View {
    @Environment(\.rommioTheme) private var theme
    let content: GameContent
    let actions: GameActions

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            RisoGameHeadline(rom: content.rom, platformLine: content.platformLine, standfirst: nil, large: true)
            RisoGameByline(content: content).padding(.top, 12)
            HStack(alignment: .top, spacing: 0) {
                VStack(alignment: .leading, spacing: 18) {
                    if let standfirst = content.standfirst {
                        Text(standfirst).themeText(.body, size: 17).italic().foregroundStyle(theme.ink)
                    }
                    GameFilePicker(shows: content.showsFilePicker, options: content.fileOptions, selectedFileID: content.selectedFileID, select: actions.selectFile)
                    GameActionDeck(deck: content.deck, fileID: content.fileID, romID: content.rom.id, showsStartOptions: content.showsStartOptions, actions: actions, showsNotes: false)
                }
                .frame(width: 230, alignment: .leading)
                .padding(.trailing, 18)
                Rectangle().fill(theme.ink).frame(width: 1)
                RisoWhereYouLeftOff(content: content, actions: actions)
                    .padding(.horizontal, 18)
                    .frame(maxWidth: .infinity)
                Rectangle().fill(theme.ink).frame(width: 1)
                VStack(alignment: .leading, spacing: 18) {
                    RisoGameDetails(content: content)
                }
                .frame(width: 280, alignment: .leading)
                .padding(.leading, 18)
            }
            .padding(.top, 20)
            RisoGameStory(summary: content.hero.summaryText).padding(.top, 26)
            GameScreenshotsRow(urls: content.screenshots, gutter: 0).padding(.top, 26)
            RisoGamePreserved(preserved: content.preserved)
        }
    }
}

// MARK: Headline

private struct RisoGameHeadline: View {
    @Environment(\.rommioTheme) private var theme
    let rom: RomDTO
    let platformLine: String
    /// Compact: the standfirst runs under the headline (wide: in the first column).
    let standfirst: String?
    let large: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Kicker(label: rom.platformSlug.uppercased(), detail: platformLine, fill: .pink)
            DisplayText(text: rom.displayName, role: .display, size: large ? 84 : 62, lineLimit: 3)
            if let standfirst {
                Text(standfirst)
                    .themeText(.body, size: 19)
                    .italic()
                    .foregroundStyle(theme.ink)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

private struct RisoGameByline: View {
    @Environment(\.rommioTheme) private var theme
    let content: GameContent

    var body: some View {
        FlowRow(spacing: 14) {
            if let company = content.rom.metadata.publisherLine.first { Text(company) }
            if !content.rom.metadata.genres.isEmpty { Text(content.rom.metadata.genres.prefix(2).joined(separator: " · ")) }
            Text(content.rom.platformName)
            Text(content.tier.title)
            if content.sessionCount > 0 { Text("\(PlayFormat.duration(content.playtime)) played") }
            Text(content.hero.statusText)
        }
        .themeText(.label, size: 10)
        .foregroundStyle(theme.inkSecondary)
        .padding(.top, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .overlay(alignment: .top) { Rectangle().fill(theme.ink).frame(height: 1) }
    }
}

// MARK: Where you left off

private struct RisoWhereYouLeftOff: View {
    let content: GameContent
    let actions: GameActions

    var body: some View {
        if content.usesProgressStore {
            RisoStoreLeftOff(content: content, actions: actions)
        } else {
            RisoLegacyLeftOff(content: content, actions: actions)
        }
    }
}

/// From the saves reader: the resume point with its frame, save states,
/// game-save data, play on this device, any conflict, and the full list.
private struct RisoStoreLeftOff: View {
    @Environment(\.rommioTheme) private var theme
    let content: GameContent
    let actions: GameActions

    var body: some View {
        let saves = content.saves
        InkBox(title: "Where you left off", detail: (saves.resume?.capturedAt ?? content.lastPlayedAt).map { PlayFormat.relative($0) }) {
            VStack(alignment: .leading, spacing: 0) {
                if let resume = saves.resume {
                    HStack(alignment: .top, spacing: 12) {
                        VStack(alignment: .leading, spacing: 4) {
                            SavePreviewImage(url: resume.thumbnailURL, coverURL: content.artworkURL, title: content.rom.displayName)
                                .frame(width: 150, height: 112)
                                .overlay { Rectangle().strokeBorder(theme.ink, lineWidth: 1) }
                            Text([resume.label, resume.deviceLabel].compactMap { $0 }.joined(separator: " · "))
                                .themeText(.caption, size: 12.5).italic().foregroundStyle(theme.inkSecondary).lineLimit(2)
                        }
                        RisoLeftOffEntry(term: resume.kind == .saveState ? resume.kindTitle : "Automatic resume",
                                         value: PlayFormat.line([PlayFormat.relative(resume.capturedAt), PlayFormat.clock(resume.capturedAt)]),
                                         sync: resume.sync.badge)
                    }
                    .padding(.bottom, 4)
                } else {
                    RisoLeftOffEntry(term: "Automatic resume", value: content.savedStatus, sync: nil)
                }
                if !saves.saveStates.isEmpty {
                    RisoBoxRule()
                    RisoLeftOffEntry(term: "Save states", value: saves.saveStates.prefix(3).map(\.title).joined(separator: " · "), sync: nil)
                }
                if let latest = saves.gameSaveData.first {
                    RisoBoxRule()
                    RisoLeftOffEntry(term: "Game-save data", value: "Latest \(PlayFormat.inline(latest.capturedAt)) · \(PlayFormat.count(saves.gameSaveData.count)) kept on this device", sync: latest.sync.badge)
                }
                if let line = content.playLine {
                    RisoBoxRule()
                    RisoLeftOffEntry(term: "On this device", value: line, sync: nil)
                }
            }
            if let conflict = content.conflict {
                GameConflictProblem(item: conflict, action: actions.chooseConflict)
                    .padding(.vertical, 6)
            }
            Button("Browse saved progress", systemImage: "square.stack.3d.up", action: actions.browseSaves)
                .buttonStyle(SecondaryButtonStyle(fullWidth: true))
                .accessibilityIdentifier("gameDetail.browseStates")
                .padding(.top, 4)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("game.savedProgress")
    }
}

/// Runtimes that still keep saves outside the progress store.
private struct RisoLegacyLeftOff: View {
    @Environment(\.rommioTheme) private var theme
    let content: GameContent
    let actions: GameActions

    var body: some View {
        let resume = content.timeline.first { $0.kind == .resume }
        if content.savedSummary != nil || content.sessionCount > 0 {
            InkBox(title: "Where you left off", detail: (resume?.date ?? content.lastPlayedAt).map { PlayFormat.relative($0) }) {
                VStack(alignment: .leading, spacing: 0) {
                    if let resume {
                        RisoLeftOffEntry(term: "Automatic resume", value: "\(resume.date.map { PlayFormat.relative($0) } ?? "Ready") · \(resume.device)", sync: resume.sync)
                    } else if let summary = content.savedSummary {
                        RisoLeftOffEntry(term: "Automatic resume", value: summary.statusText, sync: nil)
                    }
                    let states = content.timeline.filter { $0.kind != .resume }
                    if !states.isEmpty {
                        RisoBoxRule()
                        RisoLeftOffEntry(term: "Save states", value: states.prefix(3).map { $0.title }.joined(separator: " · "), sync: nil)
                    }
                    if let line = content.playLine {
                        RisoBoxRule()
                        RisoLeftOffEntry(term: "On this device", value: line, sync: nil)
                    }
                    if let problem = content.savedSummary?.problemText {
                        RisoBoxRule()
                        Text(problem).themeText(.caption).foregroundStyle(theme.ink).padding(.vertical, 8)
                    }
                }
                if content.savedSummary != nil {
                    Button("Browse saved progress", systemImage: "square.stack.3d.up", action: actions.browseSaves)
                        .buttonStyle(SecondaryButtonStyle(fullWidth: true))
                        .accessibilityIdentifier("gameDetail.browseStates")
                        .padding(.top, 4)
                }
            }
        }
    }
}

private struct RisoBoxRule: View {
    @Environment(\.rommioTheme) private var theme
    var body: some View { Rectangle().fill(theme.hairline).frame(height: 1) }
}

private struct RisoLeftOffEntry: View {
    @Environment(\.rommioTheme) private var theme
    let term: String
    let value: String
    let sync: SyncBadgeState?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(term).themeText(.label, size: 10).foregroundStyle(theme.ink)
            Text(value).themeText(.body, size: 14.5).foregroundStyle(theme.ink).fixedSize(horizontal: false, vertical: true)
            if let sync { SyncBadge(state: sync) }
        }
        .padding(.vertical, 9)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: Story and details

private struct RisoGameStory: View {
    @Environment(\.rommioTheme) private var theme
    let summary: String?

    var body: some View {
        if let summary {
            VStack(alignment: .leading, spacing: 10) {
                SectionHeader(title: "About")
                Text(summary)
                    .themeText(.body, size: 17)
                    .foregroundStyle(theme.ink)
                    .lineSpacing(3)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

private struct RisoGameDetails: View {
    @Environment(\.rommioTheme) private var theme
    let content: GameContent

    var body: some View {
        InkBox(title: "Details") {
            VStack(alignment: .leading, spacing: 0) {
                // RomM metadata first (plain text, in the shared order), then how it plays here.
                ForEach(content.details, id: \.self) { item in
                    SpecRow(term: item.label, value: item.value).padding(.vertical, 8)
                    RisoBoxRule()
                }
                SpecRow(term: "Platform", value: content.rom.platformName).padding(.vertical, 8)
                RisoBoxRule()
                SpecRow(term: "How it plays", value: content.tier.title).padding(.vertical, 8)
                if let file = content.file {
                    RisoBoxRule()
                    SpecRow(term: "File", value: "\(file.title)\n\(file.metaLine)").padding(.vertical, 8)
                    RisoBoxRule()
                    SpecRow(term: "Status", value: file.availabilityLabel).padding(.vertical, 8)
                    if let readiness = file.readinessMessage, !readiness.isEmpty {
                        RisoBoxRule()
                        SpecRow(term: "Ready", value: readiness).padding(.vertical, 8)
                    }
                    if let error = file.errorMessage, !error.isEmpty {
                        RisoBoxRule()
                        SpecRow(term: "Problem", value: error).padding(.vertical, 8)
                    }
                }
                if let core = content.deck?.recommendedCoreName {
                    RisoBoxRule()
                    SpecRow(term: "Runs on", value: core).padding(.vertical, 8)
                }
                ForEach(content.deck?.notes ?? [], id: \.self) { note in
                    RisoBoxRule()
                    Text(note).themeText(.caption).foregroundStyle(theme.inkSecondary).padding(.vertical, 8)
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("game.details")
    }
}

private struct RisoGamePreserved: View {
    @Environment(\.rommioTheme) private var theme
    let preserved: GameDetailPreservedContentPresentation?

    var body: some View {
        if let preserved {
            VStack(alignment: .leading, spacing: 4) {
                Kicker(label: "Showing saved details", fill: .yellow)
                Text(preserved.message).themeText(.caption).foregroundStyle(theme.inkSecondary)
            }
            .padding(.top, 20)
        }
    }
}
