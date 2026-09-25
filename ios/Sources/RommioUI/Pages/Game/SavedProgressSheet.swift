import SwiftUI
import RommioContract
import RommioFoundation

/// Saved progress (Game › Browse saved progress): every resume point, save state
/// and earlier save of this game with its preview, device and sync. Save states can
/// be named here; loading one happens in the player (Pause › Save states), where the
/// current progress is kept in a safety copy first. Same sections and copy as Android.
/// The lists are built as they scroll into view.
struct SavedProgressSheet: View {
    @Environment(\.rommioTheme) private var theme
    let gameTitle: String
    let saves: GameSaves
    let coverURL: URL?
    let canPlay: Bool
    let onResume: () -> Void
    let onRename: (SaveEntry, String) -> Void
    let onClose: () -> Void
    @State private var renaming: SaveEntry?

    private static let historyShown = 20

    var body: some View {
        let rename: (SaveEntry) -> Void = { renaming = $0 }
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 14) {
                    SavedProgressIntro()
                    if let resume = saves.resume {
                        SaveRows(title: "Resume point", entries: [resume], coverURL: coverURL, gameTitle: gameTitle, rename: rename)
                        if canPlay {
                            Button(action: onResume) { Label("Resume", systemImage: "play.fill") }
                                .buttonStyle(PrimaryButtonStyle())
                                .accessibilityIdentifier("game.saves.resume")
                        }
                    }
                    if saves.saveStates.isEmpty {
                        NoSaveStates()
                    } else {
                        SaveRows(title: "Save states", entries: saves.saveStates, coverURL: coverURL, gameTitle: gameTitle, rename: rename)
                    }
                    if !saves.history.isEmpty {
                        SaveRows(title: "Earlier resume points", entries: Array(saves.history.prefix(Self.historyShown)), detail: "\(saves.history.count)",
                                 coverURL: coverURL, gameTitle: gameTitle, rename: rename)
                    }
                    if let latest = saves.gameSaveData.first {
                        SaveRows(title: "Game-save data", entries: [latest],
                                 footer: "Saved with every capture; a cold boot starts from the latest."
                                    + (saves.gameSaveData.count > 1 ? " \(saves.gameSaveData.count - 1) earlier capture\(saves.gameSaveData.count == 2 ? "" : "s") kept on this device." : ""),
                                 coverURL: coverURL, gameTitle: gameTitle, rename: rename)
                    }
                }
                .padding(.horizontal, theme.spacing.gutter)
                .padding(.top, 4)
                .padding(.bottom, 28)
                .frame(maxWidth: 760)
                .frame(maxWidth: .infinity)
            }
            .themedPage(.quiet)
            .navigationTitle("Saved progress")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done", action: onClose) }
            }
        }
        .renameSaveAlert($renaming, tagPrefix: "game.saves") { entry, name in onRename(entry, name) }
        .accessibilityIdentifier("game.saves.sheet")
    }
}

private struct SavedProgressIntro: View {
    @Environment(\.rommioTheme) private var theme

    var body: some View {
        Text("To load a save state, start the game and open Save states from the pause menu. Your current progress is kept in a safety copy first.")
            .themeText(.secondary)
            .foregroundStyle(theme.inkSecondary)
            .fixedSize(horizontal: false, vertical: true)
    }
}

private struct NoSaveStates: View {
    @Environment(\.rommioTheme) private var theme

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            SectionHeader(title: "Save states")
            Text("No save states yet.").themeText(.secondary).foregroundStyle(theme.inkSecondary)
        }
    }
}

private struct SaveRows: View {
    let title: String
    let entries: [SaveEntry]
    var detail: String?
    var footer: String?
    let coverURL: URL?
    let gameTitle: String
    let rename: (SaveEntry) -> Void

    var body: some View {
        ThemedLazyGroup(title: title, detail: detail, footer: footer, inset: 92, items: entries) { entry in
            SaveRow(entry: entry, coverURL: coverURL, gameTitle: gameTitle, renameIdentifier: "game.saves.rename", onRename: rename)
                .accessibilityIdentifier("game.saves.row")
        }
    }
}
