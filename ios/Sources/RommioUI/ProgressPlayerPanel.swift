import SwiftUI
import UniformTypeIdentifiers
import RommioContract
import RommioFoundation

#if canImport(UIKit)
/// Pause › Save states (and, before the game starts, the launch choice): this
/// game's save states, resume points and game-save data with their previews,
/// names, device and sync (docs/play-data-local.md §3). Loading one first keeps
/// the current progress in a safety copy. Branch choices, sync status, retained
/// captures, imports and Leave game stay here.
struct ProgressPlayerPanel: View {
    @Environment(\.rommioTheme) private var theme
    let feature: PlayerFeatureModel
    @State private var importing = false
    @State private var importSnapshot = false
    @State private var selectedImport: URL?
    @State private var renaming: SaveEntry?

    /// Game-save data rows shown before the rest are counted.
    private static let gameSaveRows = 3

    var body: some View {
        NavigationStack {
            List {
                // The fallback for launches that bypass the Launch choice sheet: the
                // same two choices (save-sync-spec §11) and Back.
                if let message = feature.cloudMessage {
                    Section {
                        Text(message).themeText(.secondary).foregroundStyle(theme.inkSecondary)
                        startChoice("Check cloud progress again", detail: nil, symbol: "arrow.triangle.2.circlepath", identifier: "player.start.retryCloud") {
                            await feature.retryCloudCheck()
                        }
                        startChoices
                    } header: {
                        SectionHeader(title: "Cloud progress not checked")
                    }
                    .themedRow()
                } else if !feature.hasStarted {
                    Section {
                        startChoices
                    } header: {
                        SectionHeader(title: "Start")
                    }
                    .themedRow()
                }
                Section {
                    Text("Loading a save state also loads the game-save data saved with it. Your current progress is kept in a safety copy first.")
                        .themeText(.secondary)
                        .foregroundStyle(theme.inkSecondary)
                }
                .themedRow()
                ForEach(feature.progressReferences.keys.sorted(), id: \.self) { ref in
                    let heads = feature.progressReferences[ref] ?? []
                    if heads.count > 1 {
                        Section(ref == "game/current" ? "Choose game-save branch" : "Choose snapshot branch") {
                            Text("Every alternative is retained. This choice updates saved progress; it does not replace running gameplay.")
                            ForEach(heads, id: \.operationId) { head in
                                Button(head.checkpoint.map(label) ?? "Keep deletion") {
                                    Task { await feature.resolveProgress(reference: ref, selected: head.operationId, expected: heads.map(\.operationId)) }
                                }
                            }
                        }
                        .themedRow()
                    }
                }
                saveSection("Save states", saves(.saveState), empty: "No save states yet. Save snapshot in the pause menu makes one.")
                saveSection("Resume points", saves(.resumePoint), empty: nil)
                let gameSaves = saves(.gameSave)
                saveSection("Game-save data", Array(gameSaves.prefix(Self.gameSaveRows)), empty: nil,
                            footer: gameSaves.count > Self.gameSaveRows ? "\(gameSaves.count - Self.gameSaveRows) earlier capture\(gameSaves.count - Self.gameSaveRows == 1 ? "" : "s") of game-save data kept on this device." : nil)
                if feature.progressSaves.isEmpty && !feature.progressHistory.isEmpty {
                    // The store answered but decoration failed: still offer the plain list.
                    Section("Saved progress") {
                        ForEach(feature.progressHistory) { entry in
                            Button(label(entry.checkpoint)) { Task { await feature.restoreProgress(entry.id) } }
                                .disabled(!canRestore(entry.checkpoint))
                        }
                    }
                    .themedRow()
                }
                if feature.progressHistory.isEmpty {
                    Section { Text("No saved progress yet.").foregroundStyle(theme.inkSecondary) }.themedRow()
                }
                DisclosureGroup("Save and sync status") {
                    Text(feature.progressSyncMessage)
                    if let notice = feature.noticeMessage { Text(notice) }
                    if let error = feature.errorMessage { Text(error).foregroundStyle(theme.badText) }
                    Button("Retry retained captures") { Task { await feature.retryProgress() } }
                    Button("Sync game saves and snapshots") { Task { await feature.syncSaves() } }
                }
                .themedRow()
                if feature.hasStarted {
                    Section("Import retained progress") {
                        Text("Choose a file for this account and exact game. Originals are retained, and a safety checkpoint is required before replacing current progress.")
                        ForEach(feature.legacyProgressFiles, id: \.self) { file in
                            Button("Import retained \(file.lastPathComponent)") {
                                importSnapshot = file.pathExtension.lowercased() != "srm"
                                selectedImport = file
                            }
                        }
                        Button("Import game-save SRAM") { importSnapshot = false; importing = true }
                        Button("Recover a legacy snapshot") { importSnapshot = true; importing = true }
                    }
                    .themedRow()
                }
                Section {
                    Button("Leave game") { Task { await feature.leaveGame() } }
                }
                .themedRow()
            }
            .themeText(.body, size: 15)
            .tint(theme.unlitLight.readableTint)
            .themedList()
            .accessibilityIdentifier("player.states.list")
            .navigationTitle("Save states")
            .toolbar {
                if feature.hasStarted { Button("Done") { feature.stateBrowserPresented = false } }
            }
        }
        .task { await feature.refreshProgress() }
        .renameSaveAlert($renaming, tagPrefix: "player.states") { entry, name in
            Task { await feature.renameSave(entry, to: name) }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.data]) { result in
            switch result { case .success(let file): selectedImport = file; case .failure(let error): feature.errorMessage = error.localizedDescription }
        }
        .alert("Import progress for this game?", isPresented: Binding(get: { selectedImport != nil }, set: { if !$0 { selectedImport = nil } })) {
            if let file = selectedImport { Button("Preserve current progress and import", role: .destructive) { Task { await feature.importProgress(file, snapshot: importSnapshot) }; selectedImport = nil } }
            Button("Cancel", role: .cancel) { selectedImport = nil }
        } message: { Text("You are assigning this file to the current account and game. A compatible legacy snapshot will be converted together with all of its game-save memory. The original file will not be changed.") }
        .confirmationDialog("Replace game-save data?", isPresented: Binding(get: { feature.pendingRestoreID != nil }, set: { if !$0 { feature.pendingRestoreID = nil } }), titleVisibility: .visible) {
            if let id = feature.pendingRestoreID {
                Button("Preserve a safety checkpoint and restore", role: .destructive) { Task { await feature.restoreProgress(id, confirmed: true) } }
                Button("Cancel", role: .cancel) { feature.pendingRestoreID = nil }
            }
        } message: { Text("This restore contains different game-save data. Your current game-save data and emulator state will be preserved together before anything is replaced.") }
    }

    @ViewBuilder
    private var startChoices: some View {
        startChoice(LaunchChoice.Option.resume.title, detail: "The resume point and its game-save data", symbol: "play.fill", identifier: "player.start.resume") {
            await feature.continueLocal()
        }
        startChoice(LaunchChoice.Option.gameSave.title, detail: "The game starts from its own save", symbol: "sdcard", identifier: "player.start.gameSave") {
            await feature.continueLocal(coldBoot: true)
        }
        startChoice("Back", detail: nil, symbol: "chevron.backward", identifier: "player.start.back") {
            await feature.leaveGame()
        }
    }

    private func startChoice(_ title: String, detail: String?, symbol: String, identifier: String, action: @escaping () async -> Void) -> some View {
        Button { Task { await action() } } label: {
            ListRow(title: title, subtitle: detail, leading: { IconTile(systemImage: symbol, tone: title == "Back" ? .neutral : .live) })
        }
        .buttonStyle(RowButtonStyle())
        .accessibilityIdentifier(identifier)
    }

    private func saves(_ kind: SaveKind) -> [SaveEntry] {
        feature.progressSaves.filter { $0.kind == kind }.sorted { $0.capturedAtEpochMs > $1.capturedAtEpochMs }
    }

    private func checkpoint(_ entry: SaveEntry) -> ProgressCheckpoint? {
        feature.progressHistory.first { $0.id == entry.key.checkpointId }?.checkpoint
    }

    /// Loadable here: the session runs and the state (if any) was made by this core build.
    private func canRestore(_ checkpoint: ProgressCheckpoint) -> Bool {
        feature.hasStarted && (checkpoint.stateCompatibility == nil || checkpoint.stateCompatibility == feature.progressOwner?.qualification.state)
    }

    private func isSlotOrResumeHead(_ entry: SaveEntry) -> Bool {
        feature.progressReferences.contains { $0.key.hasPrefix("state/") && $0.value.count == 1 && $0.value[0].checkpoint?.checkpointId == entry.key.checkpointId }
    }

    @ViewBuilder
    private func saveSection(_ title: String, _ list: [SaveEntry], empty: String?, footer: String? = nil) -> some View {
        if !list.isEmpty || empty != nil {
            Section {
                if list.isEmpty, let empty {
                    Text(empty).themeText(.secondary).foregroundStyle(theme.inkSecondary)
                }
                ForEach(list) { entry in
                    let checkpoint = checkpoint(entry)
                    let loadable = entry.loadable || entry.kind == .gameSave
                    let restorable = loadable && checkpoint.map(canRestore) == true
                    SaveRow(entry: entry, coverURL: feature.coverURL, gameTitle: feature.presentation.romTitle,
                            subtitle: subtitle(entry, checkpoint: checkpoint),
                            renameIdentifier: "player.states.rename",
                            action: restorable ? { Task { await feature.restoreProgress(entry.key.checkpointId) } } : nil,
                            onRename: { renaming = $0 })
                    .accessibilityHint(restorable ? (entry.kind == .gameSave ? "Restores this game-save data" : "Loads this state") : "")
                    .accessibilityIdentifier("player.states.row")
                    .contextMenu {
                        if restorable {
                            Button(entry.kind == .gameSave ? "Restore game-save data" : "Restore snapshot", systemImage: "arrow.uturn.backward") {
                                Task { await feature.restoreProgress(entry.key.checkpointId) }
                            }
                        }
                        if entry.kind == .saveState {
                            Button(entry.label == nil ? "Name this save state" : "Rename save state", systemImage: "pencil") { renaming = entry }
                        }
                        if isSlotOrResumeHead(entry) {
                            Button("Remove from resume and manual slots", systemImage: "minus.circle", role: .destructive) {
                                Task { await feature.deleteProgressSnapshot(entry.key.checkpointId) }
                            }
                        }
                    }
                }
            } header: {
                Text(title)
            } footer: {
                if let footer { Text(footer) }
            }
            .themedRow()
        }
    }

    private func subtitle(_ entry: SaveEntry, checkpoint: ProgressCheckpoint?) -> String {
        var line = SaveFormat.gameDetail(entry)
        if let checkpoint, checkpoint.stateCompatibility != nil, checkpoint.stateCompatibility != feature.progressOwner?.qualification.state {
            line += " · Made by another core or platform"
        }
        if feature.progressHistory.first(where: { $0.id == entry.key.checkpointId })?.pinned == true { line += " · Preserved" }
        return line
    }

    private func label(_ cp: ProgressCheckpoint) -> String {
        Date(timeIntervalSince1970: Double(cp.createdAtEpochMs) / 1000).formatted(date: .abbreviated, time: .standard) + " · " + SavedProgressRules.reasonLabel(cp.captureReason)
    }
}
#endif
