import SwiftUI
import RommioContract
import RommioFoundation

/// Launch choice (docs/theme-layouts.md "Launch choice"): how to start a game that
/// has both a resume point and game-save data. Labels are save-sync-spec §11's:
/// **Resume** and **Start with game save**. One content model, one layout
/// restyled through components; several resume heads are a Conflict instead.
struct LaunchChoice: Identifiable, Hashable {
    enum Option: String, CaseIterable, Hashable, Sendable {
        case resume, gameSave

        var title: String {
            switch self {
            case .resume: "Resume"
            case .gameSave: "Start with game save"
            }
        }

        var identifier: String {
            switch self {
            case .resume: "launch.resume"
            case .gameSave: "launch.gameSave"
            }
        }
    }

    struct Row: Identifiable, Hashable {
        let option: Option
        /// "Automatic resume · 3 min ago · This iPhone".
        let detail: String
        /// The save whose frame the row shows (the resume point); nil shows a save glyph.
        let preview: SaveEntry?
        var enabled = true
        var id: Option { option }
        var title: String { option.title }
    }

    let rom: RomDTO
    let artworkURL: URL?
    let rows: [Row]
    /// Why the player would otherwise have asked (cloud not checked, resume unusable).
    var note: String?
    /// Chosen before the sheet opened (Start options, then a question in the player).
    var preferred: Option?
    var id: Int { rom.id }

    /// Resume unless it can't be used; else the first option that can.
    var defaultSelection: Option {
        if let preferred, rows.contains(where: { $0.option == preferred && $0.enabled }) { return preferred }
        return rows.first { $0.option == .resume && $0.enabled }?.option ?? rows.first { $0.enabled }?.option ?? .gameSave
    }

    /// The primary button follows the selection.
    static func primaryTitle(_ option: Option) -> String { option.title }

    /// The choice for one game's saves, or nil when there is nothing to choose
    /// between (no resume point and no game-save data) or its resume point is in
    /// conflict (the Conflict sheet asks instead).
    static func make(rom: RomDTO, artworkURL: URL?, saves: GameSaves, resumeUnavailable: String? = nil, note: String? = nil,
                     preferred: Option? = nil, now: Date = Date(), calendar: Calendar = .current, locale: Locale = .current) -> LaunchChoice? {
        guard !saves.resumeConflict else { return nil }
        var rows: [Row] = []
        if let resume = saves.resume {
            let kind = resume.kind == .saveState ? resume.kindTitle : "Automatic resume"
            rows.append(Row(option: .resume,
                            detail: PlayFormat.line([kind, PlayFormat.relative(resume.capturedAt, now: now, calendar: calendar, locale: locale), resume.deviceLabel]),
                            preview: resume, enabled: resumeUnavailable == nil && resume.loadable))
        }
        if let latest = saves.gameSaveData.first {
            rows.append(Row(option: .gameSave,
                            detail: PlayFormat.line(["Game-save data", PlayFormat.relative(latest.capturedAt, now: now, calendar: calendar, locale: locale),
                                                     "the game starts from its own save"]),
                            preview: nil))
        }
        guard rows.contains(where: \.enabled) else { return nil }
        return LaunchChoice(rom: rom, artworkURL: artworkURL, rows: rows, note: resumeUnavailable ?? note, preferred: preferred)
    }

    /// Start options applies (Game page, Play context menu): a resume point and game-save data.
    static func offersStartOptions(_ saves: GameSaves) -> Bool {
        saves.resume != nil && !saves.gameSaveData.isEmpty && !saves.resumeConflict
    }
}

/// The Launch choice as the app presents it: for a game not started yet (Start
/// options), or with the player already prepared (a launch that would have asked).
struct LaunchChoicePresentation: Identifiable {
    let choice: LaunchChoice
    let prepared: PlayerFeatureModel?
    /// Stable for one prepared player, so a refreshed choice updates the open sheet.
    var id: String { prepared.map { "player-\($0.id.uuidString)" } ?? "rom-\(choice.rom.id)" }
}

/// The Launch choice sheet: kicker "Start", the game, one row per option, the
/// primary button (its label follows the selection) and "Not now".
struct LaunchChoiceSheet: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.pageLight) private var light
    let choice: LaunchChoice
    /// Starts the game with the chosen option (the sheet closes first).
    let start: (LaunchChoice.Option) -> Void
    let dismiss: () -> Void
    /// Checks cloud progress again (only when the cloud check had not finished).
    var retry: (() async -> Void)?
    @State private var selection: LaunchChoice.Option?
    @State private var checking = false

    private var selected: LaunchChoice.Option { selection ?? choice.defaultSelection }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                LaunchChoiceHeading(rom: choice.rom)
                ThemedGroup(inset: 90) {
                    ForEach(choice.rows) { row in
                        LaunchChoiceRow(row: row, coverURL: choice.artworkURL, gameTitle: choice.rom.displayName, selected: row.option == selected) {
                            withAnimation(.snappy) { selection = row.option }
                        }
                        .accessibilityIdentifier(row.option.identifier)
                    }
                }
                if let note = choice.note {
                    Label(note, systemImage: "info.circle")
                        .themeText(theme.isRiso ? .body : .caption, size: theme.isRiso ? 14.5 : nil)
                        .italic(theme.isRiso)
                        .foregroundStyle(theme.inkSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("launch.note")
                }
                if let retry {
                    Button {
                        Task { checking = true; await retry(); checking = false }
                    } label: {
                        Label(checking ? "Checking…" : "Check cloud progress again", systemImage: "arrow.triangle.2.circlepath")
                    }
                    .buttonStyle(SecondaryButtonStyle(fullWidth: true))
                    .disabled(checking)
                    .accessibilityIdentifier("launch.retryCloud")
                }
                VStack(spacing: 8) {
                    Button { start(selected) } label: {
                        Label(LaunchChoice.primaryTitle(selected), systemImage: selected == .resume ? "play.fill" : "gamecontroller.fill")
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .accessibilityIdentifier("launch.start")
                    Button("Not now", action: dismiss)
                        .themeText(.bodyEmphasis)
                        .foregroundStyle(light.readableTint)
                        .frame(minHeight: 44)
                        .accessibilityIdentifier("launch.dismiss")
                }
            }
            .padding(.horizontal, theme.spacing.gutter)
            .padding(.top, 26)
            .padding(.bottom, 20)
            .frame(maxWidth: 620)
            .frame(maxWidth: .infinity)
        }
        .scrollBounceBehavior(.basedOnSize)
        .themedPage(.quiet)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("launch.sheet")
    }

}

/// "Start" and the game.
private struct LaunchChoiceHeading: View {
    @Environment(\.rommioTheme) private var theme
    let rom: RomDTO

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if theme.isRiso {
                Kicker(label: "Start", detail: rom.platformSlug.uppercased(), fill: .ink)
                DisplayText(text: rom.displayName, role: .display, size: 40, lineLimit: 3)
                PrintRule(weight: .keyline)
            } else {
                Text("Start").themeText(.section).foregroundStyle(theme.inkSecondary)
                Text(rom.displayName)
                    .themeText(.title, size: 30)
                    .foregroundStyle(theme.ink)
                    .lineLimit(3)
                    .accessibilityAddTraits(.isHeader)
            }
        }
    }
}

/// One option: the resume point's frame (as captured) or a save glyph, the
/// option, when and where, and a radio.
private struct LaunchChoiceRow: View {
    @Environment(\.rommioTheme) private var theme
    let row: LaunchChoice.Row
    let coverURL: URL?
    let gameTitle: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Group {
                    if let preview = row.preview {
                        SavePreviewThumb(entry: preview, coverURL: coverURL, title: gameTitle, width: 76)
                    } else {
                        // A save glyph in a frame the size of a preview, so the rows align.
                        let shape = RoundedRectangle(cornerRadius: theme.isRiso ? 0 : min(theme.radius.cover, 8), style: .continuous)
                        Image(systemName: "sdcard")
                            .font(.system(size: 20, weight: .medium))
                            .foregroundStyle(theme.inkSecondary)
                            .frame(width: 76, height: 57)
                            .background(shape.fill(theme.surfaceStrong))
                            .overlay { shape.strokeBorder(theme.isRiso ? theme.ink : theme.hairline, lineWidth: theme.isRiso ? 1 : 0.5) }
                            .accessibilityHidden(true)
                    }
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text(row.title)
                        .themeText(theme.isRiso ? .headline : .bodyEmphasis, size: theme.isRiso ? 20 : 17)
                        .foregroundStyle(theme.ink)
                    Text(row.detail)
                        .themeText(theme.isRiso ? .body : .secondary, size: theme.isRiso ? 14 : 13)
                        .italic(theme.isRiso)
                        .foregroundStyle(theme.inkSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 8)
                ChoiceRadio(selected: selected)
            }
            .frame(minHeight: 64)
            .contentShape(Rectangle())
        }
        .buttonStyle(RowButtonStyle())
        .disabled(!row.enabled)
        .opacity(row.enabled ? 1 : 0.45)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }
}

/// A radio: a filled `mark1` disc with a centre (Aurora), an ink square (Riso).
struct ChoiceRadio: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.pageLight) private var light
    let selected: Bool

    var body: some View {
        Group {
            if theme.isRiso {
                Rectangle()
                    .fill(selected ? theme.ink : .clear)
                    .overlay { Rectangle().strokeBorder(theme.ink, lineWidth: 1.5) }
                    .frame(width: 18, height: 18)
            } else {
                ZStack {
                    Circle().strokeBorder(selected ? light.mark1 : theme.inkTertiary, lineWidth: 1.5)
                    if selected {
                        Circle().fill(light.mark1)
                        Circle().fill(theme.markInk).frame(width: 7, height: 7)
                    }
                }
                .frame(width: 20, height: 20)
            }
        }
        .accessibilityHidden(true)
    }
}
