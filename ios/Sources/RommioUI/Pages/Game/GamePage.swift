import SwiftUI
import RommioContract
import RommioFoundation
import RommioPlayerKit

/// One saved point for a game: the automatic resume, a save state, an earlier
/// resume point (or, for games without progress-store saves, a legacy snapshot),
/// named as in save-sync-spec §11.
struct SaveTimelineItem: Identifiable, Hashable {
    enum Kind: Hashable { case resume, saveState, snapshot, earlier }
    let id: String
    let kind: Kind
    /// "Automatic resume", "Save state · Slot 2", "Earlier resume point", "Snapshot".
    let kindTitle: String
    let title: String
    let date: Date?
    let device: String
    let sync: SyncBadgeState?
    /// The save from the progress store (with its preview), when there is one.
    var entry: SaveEntry?

    var symbol: String {
        switch kind {
        case .resume, .earlier: "clock.arrow.circlepath"
        case .saveState: "square.stack.3d.up"
        case .snapshot: "camera.metering.center.weighted"
        }
    }

    /// Stops from the saves reader: the resume point, save states and up to six
    /// earlier resume points; newest first, at most ten (as Android's `saveStops`).
    static func stops(_ saves: GameSaves) -> [SaveTimelineItem] {
        var items: [SaveTimelineItem] = []
        func add(_ entry: SaveEntry, kind: Kind, kindTitle: String, title: String) {
            items.append(SaveTimelineItem(id: entry.key.checkpointId, kind: kind, kindTitle: kindTitle, title: title, date: entry.capturedAt,
                                          device: entry.deviceLabel, sync: entry.sync.badge, entry: entry))
        }
        if let resume = saves.resume {
            // On iOS a manual save state also becomes the resume point until the next capture.
            let manual = resume.kind == .saveState
            add(resume, kind: manual ? .saveState : .resume, kindTitle: manual ? resume.kindTitle : "Automatic resume", title: resume.label ?? (manual ? "Save state" : "Resume point"))
        }
        for state in saves.saveStates { add(state, kind: .saveState, kindTitle: state.kindTitle, title: state.label ?? state.title) }
        for earlier in saves.history.prefix(6) { add(earlier, kind: .earlier, kindTitle: "Earlier resume point", title: earlier.label ?? earlier.reasonTitle) }
        return Array(items.sorted { ($0.date ?? .distantPast) > ($1.date ?? .distantPast) }.prefix(10))
    }
}

/// A label and its value in the Game page's details (plain text).
struct GameDetailItem: Hashable {
    let label: String
    let value: String

    /// RomM metadata in reading order; empty fields are left out (docs/play-data-local.md §6).
    static func items(_ metadata: RomMetadata) -> [GameDetailItem] {
        var items: [GameDetailItem] = []
        func add(_ label: String, _ value: String?) { if let value, !value.isEmpty { items.append(GameDetailItem(label: label, value: value)) } }
        add("Released", metadata.releaseYear.map(String.init))
        add(metadata.genres.count == 1 ? "Genre" : "Genres", metadata.genres.joined(separator: ", "))
        add(metadata.publishers.isEmpty ? "Companies" : "Publisher", metadata.publisherLine.joined(separator: ", "))
        add("Developer", metadata.developers.joined(separator: ", "))
        add("Series", metadata.franchises.joined(separator: ", "))
        add("Players", metadata.playersText)
        add("Rating", metadata.ratingOutOf100.map { "\($0) / 100" })
        add(metadata.regions.count == 1 ? "Region" : "Regions", metadata.regions.joined(separator: ", "))
        add(metadata.languages.count == 1 ? "Language" : "Languages", metadata.languages.joined(separator: ", "))
        return items
    }
}

/// Everything the Game layouts draw, from `GameDetailFeatureModel` and the play on
/// this device; built once per change (`GameContent.build`), never in a view.
struct GameContent: Equatable {
    let rom: RomDTO
    let artworkURL: URL?
    let tier: EmbeddedSupportTier
    let hero: GameDetailHeroPresentation
    let deck: GameDetailActionDeckPresentation?
    let fileID: Int?
    let file: GameDetailSelectedFilePresentation?
    let fileOptions: [GameDetailFileOption]
    let showsFilePicker: Bool
    let selectedFileID: Int
    let savedSummary: GameDetailStateSummaryPresentation?
    let timeline: [SaveTimelineItem]
    /// This game's saves from the progress store (empty for legacy-only games).
    let saves: GameSaves
    let usesProgressStore: Bool
    /// The first conflicted reference, for the Conflict sheet.
    let conflict: ConflictItem?
    /// RomM metadata as plain text, and its screenshots (resolved against the server).
    let details: [GameDetailItem]
    let screenshots: [URL]
    let playtime: TimeInterval
    let sessionCount: Int
    let lastPlayedAt: Date?
    let preserved: GameDetailPreservedContentPresentation?
    let statusTone: StatusTone
    let availabilityTone: StatusTone
    let playabilityTone: StatusTone
    /// Start options (the Launch choice): the game can be played and has a resume
    /// point and game-save data.
    var showsStartOptions = false

    /// "Played 1 h 05 min · 3 sessions · last 2 hours ago".
    var playLine: String? {
        guard sessionCount > 0 else { return nil }
        var parts = ["Played \(PlayFormat.duration(playtime))", PlayFormat.count(sessionCount, "session")]
        if let lastPlayedAt { parts.append("last \(PlayFormat.inline(lastPlayedAt))") }
        return parts.joined(separator: " · ")
    }

    /// "Super Nintendo · 1994".
    var platformLine: String { rom.platformAndYear }

    /// The saved-progress status line when there are no stops yet.
    var savedStatus: String {
        if usesProgressStore { return saves.resume == nil ? "No resume point yet." : "Resume point ready." }
        return savedSummary?.statusText ?? "No resume point yet."
    }

    /// "1 resume point · 2 save states" (store) or the legacy count.
    var savesCount: String? {
        if usesProgressStore { let count = SaveFormat.count(saves); return count.isEmpty ? nil : count }
        return timeline.isEmpty ? nil : savedSummary?.countSummary
    }

    /// Shows the Saved progress section: the store has saves, or the legacy browser applies.
    var showsSavedProgress: Bool { usesProgressStore || savedSummary != nil }

    /// The resume point's sync (the heading's badge).
    var resumeSync: SyncBadgeState? {
        usesProgressStore ? saves.resume?.sync.badge : timeline.first(where: { $0.kind == .resume })?.sync
    }

    /// The first sentence of the summary, for standfirsts.
    var standfirst: String? {
        guard let summary = hero.summaryText else { return nil }
        let sentence = summary.split(separator: ".", maxSplits: 1).first.map { String($0) + "." } ?? summary
        return sentence.count > 180 ? String(sentence.prefix(177)) + "…" : sentence
    }

    /// The game's page from its feature model and this device's play of it.
    @MainActor
    static func build(_ feature: GameDetailFeatureModel, model: RommioAppModel) -> GameContent {
        let hero = feature.heroPresentation
        let played = model.statsFeature.stats.games.first { $0.romID == feature.rom.id }
        let state = feature.selectedFileState
        let artwork = model.artworkURL(path: feature.rom.urlCover)
        return GameContent(
            rom: feature.rom,
            artworkURL: artwork,
            tier: feature.controlProfile.supportTier,
            hero: hero,
            deck: feature.actionDeckPresentation,
            fileID: state?.file.id,
            file: feature.selectedFilePresentation,
            fileOptions: feature.fileSelectionOptions,
            showsFilePicker: feature.showsSegmentedFileSelection,
            selectedFileID: state?.file.id ?? feature.fileSelectionOptions.first?.id ?? 0,
            savedSummary: feature.stateSummaryPresentation,
            timeline: feature.usesProgressStore ? SaveTimelineItem.stops(feature.saves) : legacyTimeline(feature.stateBrowser),
            saves: feature.saves,
            usesProgressStore: feature.usesProgressStore,
            conflict: feature.conflicts.sorted { ($0.kind == .resume ? 0 : 1) < ($1.kind == .resume ? 0 : 1) }.first.map {
                ConflictItem(conflict: $0, rom: feature.rom, artworkURL: artwork, installed: state?.localPath != nil)
            },
            details: GameDetailItem.items(feature.rom.metadata),
            screenshots: feature.rom.metadata.screenshots.compactMap { model.artworkURL(path: $0) },
            playtime: played?.playtime ?? 0,
            sessionCount: played?.sessionCount ?? 0,
            lastPlayedAt: played?.lastPlayedAt,
            preserved: feature.preservedContentPresentation,
            statusTone: hero.statusTone,
            availabilityTone: feature.selectedFilePresentation?.availabilityTone ?? .neutral,
            playabilityTone: state.map(GameDetailView.playabilityTone) ?? .neutral,
            showsStartOptions: feature.actionDeckPresentation?.primary.map { $0.kind == .play && $0.enabled } == true
                && feature.conflicts.isEmpty && LaunchChoice.offersStartOptions(feature.saves)
        )
    }

    /// The saves as a timeline, newest first.
    private static func legacyTimeline(_ browser: GameStateBrowser) -> [SaveTimelineItem] {
        var items: [SaveTimelineItem] = []
        if let resume = browser.resume, resume.available {
            items.append(SaveTimelineItem(
                id: "resume", kind: .resume, kindTitle: "Automatic resume", title: resume.primaryStatusMessage,
                date: resume.updatedAtEpochMS.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) },
                device: resume.sourceOrigin == .remoteDevice ? (resume.sourceDeviceName ?? "Another device") : "This device",
                sync: GameDetailView.badge(resume.statusKind)))
        }
        for state in browser.saveSlots {
            items.append(SaveTimelineItem(
                id: state.id, kind: .saveState, kindTitle: state.slot.map { "Save state · Slot \($0)" } ?? "Save state", title: state.label,
                date: Date(timeIntervalSince1970: TimeInterval(state.updatedAtEpochMS) / 1000),
                device: state.sourceDeviceName ?? "This device", sync: nil))
        }
        for state in browser.snapshots {
            items.append(SaveTimelineItem(
                id: state.id, kind: .snapshot, kindTitle: "Snapshot", title: state.label,
                date: Date(timeIntervalSince1970: TimeInterval(state.updatedAtEpochMS) / 1000),
                device: state.sourceDeviceName ?? "This device", sync: nil))
        }
        return items.sorted { ($0.date ?? .distantPast) > ($1.date ?? .distantPast) }
    }
}

struct GameActions: PageActions {
    let perform: (GameDetailAction) -> Void
    let selectFile: (Int) -> Void
    let browseSaves: () -> Void
    /// Opens the Conflict sheet.
    let chooseConflict: () -> Void
    /// Opens the Launch choice (Resume or Start with game save).
    var startOptions: () -> Void = {}
}

/// A game's feature model and its page content.
@MainActor
final class GamePageModel {
    let feature: GameDetailFeatureModel
    let content: DerivedContent<GameContent>

    init(model: RommioAppModel, rom: RomDTO) {
        let feature = model.makeGameDetailFeature(rom: rom)
        self.feature = feature
        content = DerivedContent { [unowned model] in GameContent.build(feature, model: model) }
    }
}

/// Game: reached from Play, Library, Search and Stats. Aurora: an art-forward
/// hero lit by the cover, with the game's saves as a timeline. Riso: an article
/// with where you left off and a details box.
struct GameDetailView: View {
    @Environment(\.rommioTheme) private var theme
    let model: RommioAppModel
    @State private var page: GamePageModel

    init(model: RommioAppModel, rom: RomDTO) {
        self.model = model
        _page = State(initialValue: GamePageModel(model: model, rom: rom))
    }

    private var feature: GameDetailFeatureModel { page.feature }

    var body: some View {
        let content = page.content.value
        GameLayout(content: content, actions: actions, riso: theme.isRiso)
            .toolbar {
                // The title is the page's headline; the bar stays clear over the art.
                ToolbarItem(placement: .principal) { Color.clear.frame(width: 1, height: 1).accessibilityHidden(true) }
                if let primary = content.deck?.primary, let fileID = content.fileID,
                   primary.kind == .play || primary.kind == .downloadNow {
                    ToolbarItem(placement: .primaryAction) {
                        GameToolbarAction(primary: primary, fileID: fileID, romID: feature.rom.id, perform: actions.perform)
                    }
                }
            }
            .navigationTitle(content.rom.displayName)
            .inlineNavigationTitle()
            .task { await feature.refresh(forceRemote: false) }
            .task { await feature.monitorDownloads() }
            .modifier(RefreshAfterPlayer(model: model, feature: feature))
            .refreshable { await feature.refresh(forceRemote: true) }
            .sheet(isPresented: Binding(
                get: { feature.savedProgressPresented },
                set: { feature.savedProgressPresented = $0 }
            )) {
                SavedProgressSheet(
                    gameTitle: content.rom.displayName,
                    saves: content.saves,
                    coverURL: content.artworkURL,
                    canPlay: content.deck?.primary.map { $0.kind == .play && $0.enabled } ?? false,
                    onResume: { feature.savedProgressPresented = false; Task { await feature.playFromResume() } },
                    onRename: { entry, name in Task { await feature.renameSave(entry, to: name) } },
                    onClose: { feature.savedProgressPresented = false }
                )
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
            }
            .sheet(isPresented: Binding(
                get: { feature.conflictPresented },
                set: { feature.conflictPresented = $0 }
            )) {
                if let item = content.conflict {
                    ConflictSheet(item: item) { choice in Task { await model.keepPlaying(item.conflict, choice: choice) } }
                        .presentationDetents([.large])
                        .presentationDragIndicator(.visible)
                }
            }
            .sheet(isPresented: Binding(
                get: { feature.stateBrowserPresented },
                set: { feature.stateBrowserPresented = $0 }
            )) {
                StateBrowserView(
                    browser: feature.stateBrowser,
                    onUseResume: { Task { await feature.playFromResume() } },
                    onLoadState: { state in Task { await feature.playFromState(state) } },
                    onDeleteState: { state in Task { await feature.deleteState(state) } },
                    onClose: { feature.stateBrowserPresented = false }
                )
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
                .accessibilityIdentifier("gameDetail.stateBrowser.sheet")
            }
    }

    /// Actions read the feature model when called (never a copy taken at render).
    private var actions: GameActions {
        let feature = feature
        let model = model
        return GameActions(
            perform: { action in
                guard let fileID = feature.selectedFileState?.file.id else { return }
                // With two resume points, Play asks which to keep first (docs/theme-layouts.md, Game saves rule).
                if action.kind == .play && !feature.conflicts.isEmpty { feature.conflictPresented = true; return }
                Task { await feature.perform(action, fileID: fileID) }
            },
            selectFile: { id in Task { await feature.selectFile(id) } },
            // Progress-store games open Saved progress; legacy runtimes keep their state browser.
            browseSaves: { if feature.usesProgressStore { feature.savedProgressPresented = true } else { feature.stateBrowserPresented = true } },
            chooseConflict: { feature.conflictPresented = true },
            startOptions: {
                if let choice = LaunchChoice.make(rom: feature.rom, artworkURL: model.artworkURL(path: feature.rom.urlCover), saves: feature.saves) {
                    model.showLaunchChoice(choice)
                }
            }
        )
    }

    static func badge(_ kind: ResumeStateStatusKind) -> SyncBadgeState? {
        switch kind {
        case .synced, .syncedRemoteSource: .synced
        case .pendingUpload: .waiting
        case .cloudAvailable: nil
        case .conflict: .conflict
        case .error: .failed
        case .localOnly: .localOnly
        case .unavailable: nil
        }
    }

    static func playabilityTone(_ state: GameDetailFileState) -> StatusTone {
        if state.resolution.capability == .ready || state.resolution.capabilityLabel == nil {
            switch state.controlProfile.supportTier {
            case .touchSupported: return .ok
            case .controllerSupported: return .live
            case .unsupported: return .neutral
            }
        }
        switch state.resolution.capability {
        case .ready: return .ok
        case .missingCore, .missingBIOS: return .wait
        case .unsupported: return .neutral
        }
    }
}

/// Refreshes the page when the player closes (play changes the saves).
private struct RefreshAfterPlayer: ViewModifier {
    let model: RommioAppModel
    let feature: GameDetailFeatureModel

    func body(content: Content) -> some View {
        content.onChange(of: model.activePlayer?.id) { _, playerID in
            if playerID == nil { Task { await feature.refresh(forceRemote: false) } }
        }
    }
}

/// The toolbar's Play or Download.
private struct GameToolbarAction: View {
    @Environment(\.launchingRomID) private var launchingRomID
    let primary: GameDetailAction
    let fileID: Int
    let romID: Int
    let perform: (GameDetailAction) -> Void
    @State private var taps = 0

    var body: some View {
        Button {
            taps += 1
            perform(primary)
        } label: {
            if primary.kind == .play {
                LaunchToolbarLabel(title: "Play", systemImage: "play.fill", romID: romID)
            } else {
                Label("Download", systemImage: "arrow.down.circle")
            }
        }
        // The tap is felt at once; while a game starts, Play waits.
        .sensoryFeedback(.impact(weight: .medium, intensity: 0.9), trigger: taps)
        .disabled(!primary.enabled || (primary.kind == .play && launchingRomID != nil))
        .accessibilityIdentifier("gameDetail.toolbar.\(primary.kind.rawValue)")
    }
}

private struct GameLayout: View {
    let content: GameContent
    let actions: GameActions
    let riso: Bool

    var body: some View {
        if riso {
            RisoGameLayout(content: content, actions: actions)
        } else {
            AuroraGameLayout(content: content, actions: actions)
        }
    }
}

// MARK: Shared pieces

/// The main action and the quieter ones for the selected file, with progress and notes.
struct GameActionDeck: View {
    @Environment(\.rommioTheme) private var theme
    let deck: GameDetailActionDeckPresentation?
    let fileID: Int?
    /// The game, for Play's starting state (`LaunchButton`).
    let romID: Int
    let showsStartOptions: Bool
    let actions: GameActions
    var showsNotes = true

    var body: some View {
        if let deck, let fileID {
            VStack(alignment: .leading, spacing: 12) {
                if let primary = deck.primary {
                    if primary.kind == .play {
                        LaunchButton(title: primary.title, romID: romID) { actions.perform(primary) }
                            .disabled(!primary.enabled)
                            .accessibilityIdentifier("gameDetail.primary.\(fileID).\(primary.kind.rawValue)")
                    } else {
                        Button { actions.perform(primary) } label: {
                            Label(primary.title, systemImage: primary.kind == .downloadNow ? "arrow.down.circle.fill" : "arrow.clockwise")
                        }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(!primary.enabled)
                        .accessibilityIdentifier("gameDetail.primary.\(fileID).\(primary.kind.rawValue)")
                    }
                }
                if showsStartOptions {
                    Button(action: actions.startOptions) {
                        Label("Start options", systemImage: "slider.horizontal.3")
                    }
                    .buttonStyle(SecondaryButtonStyle(fullWidth: true))
                    .accessibilityIdentifier("game.startOptions")
                }
                if let progress = deck.downloadProgressValue {
                    VStack(alignment: .leading, spacing: 6) {
                        ThemedProgressBar(value: progress, tone: .live)
                        if let detail = deck.progressDetailText {
                            Text(detail).themeText(.caption).foregroundStyle(theme.inkSecondary)
                        }
                    }
                }
                if !deck.secondary.isEmpty {
                    FlowRow(spacing: 10) {
                        ForEach(deck.secondary, id: \.kind) { action in
                            Button(action.title) { actions.perform(action) }
                                .buttonStyle(SecondaryButtonStyle(destructive: action.kind == .deleteLocal || action.kind == .cancel))
                                .disabled(!action.enabled)
                                .accessibilityIdentifier("gameDetail.secondary.\(fileID).\(action.kind.rawValue)")
                        }
                    }
                }
                if showsNotes {
                    GameDeckNotes(deck: deck)
                }
            }
        }
    }
}

private struct GameDeckNotes: View {
    @Environment(\.rommioTheme) private var theme
    let deck: GameDetailActionDeckPresentation

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(deck.subtitle).themeText(.secondary).foregroundStyle(theme.inkSecondary)
            if let core = deck.recommendedCoreName {
                Text("Runs on \(core)").themeText(.caption).foregroundStyle(theme.inkTertiary)
            }
            ForEach(deck.notes, id: \.self) { note in
                Text(note).themeText(.caption).foregroundStyle(theme.inkSecondary)
            }
            if let error = deck.errorMessage, !error.isEmpty {
                Text(error).themeText(.caption).foregroundStyle(theme.badText)
            }
        }
    }
}

/// The file picker when a game has several files.
struct GameFilePicker: View {
    let shows: Bool
    let options: [GameDetailFileOption]
    let selectedFileID: Int
    let select: (Int) -> Void

    var body: some View {
        if shows {
            Picker("File", selection: Binding(get: { selectedFileID }, set: { select($0) })) {
                ForEach(options) { option in
                    Text(option.title).tag(option.id)
                }
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("gameDetail.filePicker")
        }
    }
}

/// Wraps chips and buttons onto new lines as needed.
struct FlowRow: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, lineHeight: CGFloat = 0, maxX: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > 0, x + size.width > width {
                x = 0
                y += lineHeight + spacing
                lineHeight = 0
            }
            x += size.width + spacing
            maxX = max(maxX, x - spacing)
            lineHeight = max(lineHeight, size.height)
        }
        return CGSize(width: min(maxX, width), height: y + lineHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, lineHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > bounds.minX, x + size.width > bounds.maxX {
                x = bounds.minX
                y += lineHeight + spacing
                lineHeight = 0
            }
            subview.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            lineHeight = max(lineHeight, size.height)
        }
    }
}

/// RomM details as plain text: label and value rows (Aurora glass group; Riso
/// leader lines).
struct GameDetailsList: View {
    @Environment(\.rommioTheme) private var theme
    let items: [GameDetailItem]

    var body: some View {
        ForEach(items, id: \.self) { item in
            if theme.isRiso {
                SpecRow(term: item.label, value: item.value).padding(.vertical, 8)
            } else {
                HStack(alignment: .firstTextBaseline, spacing: 16) {
                    Text(item.label).themeText(.secondary).foregroundStyle(theme.inkSecondary).frame(width: 96, alignment: .leading)
                    Text(item.value).themeText(.body).foregroundStyle(theme.ink).frame(maxWidth: .infinity, alignment: .trailing)
                        .multilineTextAlignment(.trailing)
                }
                .accessibilityElement(children: .combine)
            }
        }
    }
}

/// RomM screenshots, shown as delivered: no art treatment in either theme.
struct GameScreenshotsRow: View {
    @Environment(\.rommioTheme) private var theme
    let urls: [URL]
    var gutter: CGFloat

    var body: some View {
        if !urls.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                SectionHeader(title: "Screenshots", detail: "\(urls.count)")
                    .padding(.horizontal, gutter + (theme.isGlass ? 6 : 0))
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(spacing: 10) {
                        ForEach(urls, id: \.self) { url in GameScreenshot(url: url) }
                    }
                    .padding(.horizontal, gutter)
                }
                .scrollClipDisabled()
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("game.screenshots")
        }
    }
}

/// One RomM screenshot, as delivered.
private struct GameScreenshot: View {
    @Environment(\.rommioTheme) private var theme
    let url: URL

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: theme.isRiso ? 0 : min(theme.radius.cover, 12), style: .continuous)
        ArtworkImage(url: url, contentMode: .fit, maxPointSize: 360) { theme.player.ground.color }
            .frame(width: 176, height: 132)
            .background(theme.player.ground.color)
            .clipShape(shape)
            .overlay { shape.strokeBorder(theme.isRiso ? theme.ink : theme.ink.opacity(0.1), lineWidth: theme.isRiso ? 1 : 0.5) }
            .accessibilityLabel("Screenshot")
    }
}

/// The conflict copy and "Choose resume point" under a game's saved progress.
struct GameConflictProblem: View {
    @Environment(\.rommioTheme) private var theme
    let item: ConflictItem
    let action: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label(item.prompt, systemImage: "exclamationmark.triangle.fill")
                .themeText(theme.isRiso ? .body : .secondary, size: theme.isRiso ? 14.5 : nil)
                .foregroundStyle(theme.isRiso ? theme.ink : theme.badText)
                .fixedSize(horizontal: false, vertical: true)
            Button("Choose resume point", systemImage: "arrow.triangle.branch", action: action)
                .buttonStyle(SecondaryButtonStyle())
                .accessibilityIdentifier("game.chooseResume")
        }
    }
}
