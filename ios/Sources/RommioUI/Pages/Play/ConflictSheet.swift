import SwiftUI
import RommioContract
import RommioFoundation

/// Conflict (sheet from Play and Game; docs/theme-layouts.md): "<Game> has two
/// resume points. Choose which to keep playing." Each head shows its frame (as
/// captured), device and when. Keep playing opens the player with the chosen one;
/// the player resolves the branch with the existing progress rules (the other head
/// stays in history). The sheet itself writes nothing. One layout; components pick
/// the theme's variant.
struct ConflictSheet: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    let item: ConflictItem
    /// Opens the player with the chosen head (after the sheet closes).
    let keep: (ProgressConflict.Choice) -> Void
    @State private var selection: String?

    private var choices: [ProgressConflict.Choice] { item.conflict.choices }
    /// The first (newest) choice is selected.
    private var selected: ProgressConflict.Choice? { choices.first { $0.operationId == selection } ?? choices.first }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                heading
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12, alignment: .top),
                                         count: horizontalSizeClass == .regular ? min(3, max(2, choices.count)) : 2),
                          alignment: .leading, spacing: 12) {
                    ForEach(Array(choices.enumerated()), id: \.element.id) { index, choice in
                        ConflictChoiceCard(choice: choice, item: item, selected: choice.operationId == selected?.operationId) {
                            withAnimation(.snappy) { selection = choice.operationId }
                        }
                        .accessibilityIdentifier("conflict.choice.\(index)")
                    }
                }
                Text("The other \(otherNoun) stays in this game's saved progress.")
                    .themeText(theme.isRiso ? .body : .caption, size: theme.isRiso ? 14.5 : nil)
                    .italic(theme.isRiso)
                    .foregroundStyle(theme.inkSecondary)
                VStack(spacing: 8) {
                    Button {
                        guard let selected else { return }
                        dismiss()
                        keep(selected)
                    } label: {
                        Label("Keep playing", systemImage: "play.fill")
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(selected == nil || !item.installed)
                    .accessibilityIdentifier("conflict.keep")
                    if !item.installed {
                        Text("Download \(item.rom.displayName) to this device to keep playing.")
                            .themeText(.caption)
                            .foregroundStyle(theme.inkSecondary)
                            .frame(maxWidth: .infinity)
                            .multilineTextAlignment(.center)
                    }
                    Button("Not now") { dismiss() }
                        .themeText(.bodyEmphasis)
                        .foregroundStyle(theme.readableTint)
                        .frame(minHeight: 44)
                        .accessibilityIdentifier("conflict.dismiss")
                }
            }
            .padding(.horizontal, theme.spacing.gutter)
            .padding(.top, 28)
            .padding(.bottom, 24)
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
        }
        .themedPage(.quiet)
        .accessibilityIdentifier("conflict.sheet")
    }

    private var otherNoun: String {
        switch item.conflict.kind {
        case .resume: "resume point"
        case .gameSave: "game-save data"
        case .slot: "save state"
        }
    }

    @ViewBuilder
    private var heading: some View {
        VStack(alignment: .leading, spacing: 10) {
            if theme.isRiso {
                Kicker(label: "Conflict", detail: "\(choices.count) \(otherNoun)\(choices.count == 1 || item.conflict.kind == .gameSave ? "" : "s")", fill: .pink)
                DisplayText(text: item.rom.displayName, role: .display, size: 44, lineLimit: 3)
                PrintRule(weight: .keyline)
                Text(item.prompt).themeText(.body, size: 17).italic().foregroundStyle(theme.ink).fixedSize(horizontal: false, vertical: true)
            } else {
                StatusChip(title: "Conflict", tone: .bad)
                Text(item.rom.displayName).themeText(.title, size: 34).foregroundStyle(theme.ink).lineLimit(3)
                    .accessibilityAddTraits(.isHeader)
                Text(item.prompt).themeText(.secondary).foregroundStyle(theme.inkSecondary).fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

/// One head to choose: its frame, the device with a radio, and when.
private struct ConflictChoiceCard: View {
    @Environment(\.rommioTheme) private var theme
    let choice: ProgressConflict.Choice
    let item: ConflictItem
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 8) {
                let frameShape = RoundedRectangle(cornerRadius: theme.isRiso ? 0 : 12, style: .continuous)
                // A fixed 4:3 frame: the preview letterboxed, or the cover filling it.
                Color.clear
                    .aspectRatio(4 / 3, contentMode: .fit)
                    .frame(maxWidth: .infinity)
                    .overlay { SavePreviewImage(url: choice.entry?.thumbnailURL, coverURL: item.artworkURL, title: item.rom.displayName) }
                    .clipShape(frameShape)
                    .overlay { frameShape.strokeBorder(theme.isRiso ? theme.ink : theme.glassRim.opacity(0.1), lineWidth: theme.isRiso ? 1 : 0.5) }
                HStack(alignment: .center, spacing: 8) {
                    Text(choice.entry?.deviceLabel ?? "Removed")
                        .themeText(.headline, size: theme.isRiso ? 20 : 19)
                        .foregroundStyle(theme.ink)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                    Spacer(minLength: 4)
                    radio
                }
                Text(detail)
                    .themeText(theme.isRiso ? .body : .caption, size: theme.isRiso ? 14 : 12.5)
                    .italic(theme.isRiso)
                    .foregroundStyle(theme.inkSecondary)
                    .lineLimit(2)
            }
            .padding(theme.isRiso ? 10 : 8)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background {
                if theme.isGlass {
                    GlassFill(shape: RoundedRectangle(cornerRadius: theme.radius.card, style: .continuous))
                } else {
                    Rectangle().fill(theme.ground)
                }
            }
            .overlay {
                if theme.isGlass {
                    RoundedRectangle(cornerRadius: theme.radius.card, style: .continuous)
                        .strokeBorder(selected ? theme.mark1 : theme.glassRim.opacity(theme.isDark ? 0.08 : 0.6), lineWidth: selected ? 2 : 0.5)
                } else {
                    Rectangle().strokeBorder(theme.ink, lineWidth: selected ? 2.5 : 1.5)
                }
            }
            .background(alignment: .topLeading) {
                if theme.isRiso && selected {
                    Rectangle().fill(theme.accent1)
                        .offset(x: theme.material.misregistration * 2, y: theme.material.misregistration * 2)
                }
            }
            .shadow(color: theme.isGlass && selected ? theme.mark1.opacity(0.55) : .clear, radius: 14, y: 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }

    /// "Resume point · 7:07 PM · Yesterday".
    private var detail: String {
        guard let entry = choice.entry else { return "The other device removed it." }
        return [entry.label ?? (entry.kind == .saveState ? entry.kindTitle : entry.kind.title), PlayFormat.clock(entry.capturedAt), SaveFormat.day(entry.capturedAt)]
            .joined(separator: " · ")
    }

    private var radio: some View { ChoiceRadio(selected: selected) }
}

extension String {
    var capitalizedFirst: String { prefix(1).uppercased() + dropFirst() }
}
