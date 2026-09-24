import SwiftUI
import RommioContract
import RommioFoundation

/// Saved progress (Game › Browse saved progress): every resume point, save state
/// and earlier save of this game with its preview, device and sync. Save states can
/// be named here; loading one happens in the player (Pause › Save states), where the
/// current progress is kept in a safety copy first. Same sections and copy as Android.
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
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("To load a save state, start the game and open Save states from the pause menu. Your current progress is kept in a safety copy first.")
                        .themeText(.secondary)
                        .foregroundStyle(theme.inkSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                    if let resume = saves.resume {
                        rows("Resume point", [resume])
                        if canPlay {
                            Button(action: onResume) { Label("Resume", systemImage: "play.fill") }
                                .buttonStyle(PrimaryButtonStyle())
                                .accessibilityIdentifier("game.saves.resume")
                        }
                    }
                    if saves.saveStates.isEmpty {
                        VStack(alignment: .leading, spacing: 6) {
                            SectionHeader(title: "Save states")
                            Text("No save states yet.").themeText(.secondary).foregroundStyle(theme.inkSecondary)
                        }
                    } else {
                        rows("Save states", saves.saveStates)
                    }
                    if !saves.history.isEmpty {
                        rows("Earlier resume points", Array(saves.history.prefix(Self.historyShown)), detail: "\(saves.history.count)")
                    }
                    if let latest = saves.gameSaveData.first {
                        rows("Game-save data", [latest],
                             footer: "Saved with every capture; a cold boot starts from the latest."
                                + (saves.gameSaveData.count > 1 ? " \(saves.gameSaveData.count - 1) earlier capture\(saves.gameSaveData.count == 2 ? "" : "s") kept on this device." : ""))
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

    private func rows(_ title: String, _ entries: [SaveEntry], detail: String? = nil, footer: String? = nil) -> some View {
        ThemedGroup(title: title, detail: detail, footer: footer, inset: 92) {
            ForEach(entries) { entry in
                SaveRow(entry: entry, coverURL: coverURL, gameTitle: gameTitle, renameIdentifier: "game.saves.rename",
                        onRename: { renaming = $0 })
                    .accessibilityIdentifier("game.saves.row")
            }
        }
    }
}
