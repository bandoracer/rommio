import SwiftUI
import UniformTypeIdentifiers
import RommioContract
import RommioFoundation

#if canImport(UIKit)
struct ProgressPlayerPanel: View {
    let feature: PlayerFeatureModel
    @State private var importing = false
    @State private var importSnapshot = false
    @State private var selectedImport: URL?
    @State private var showingSnapshots = true
    var body: some View {
        NavigationStack {
            List {
                if let message = feature.cloudMessage {
                    Section("Cloud progress not checked") {
                        Text(message)
                        Button("Retry cloud check") { Task { await feature.retryCloudCheck() } }
                        Button("Continue with local progress") { Task { await feature.continueLocal() } }
                        Button("Cold boot with game-save data") { Task { await feature.continueLocal(coldBoot: true) } }
                    }
                } else if !feature.hasStarted {
                    Section("Start game") {
                        Button("Resume compatible snapshot") { Task { await feature.continueLocal() } }
                        Button("Cold boot with game-save data") { Task { await feature.continueLocal(coldBoot: true) } }
                    }
                }
                Section {
                    Picker("Progress type", selection: $showingSnapshots) {
                        Text("Snapshots").tag(true)
                        Text("Game saves").tag(false)
                    }.pickerStyle(.segmented)
                }
                DisclosureGroup("Save and sync status") {
                    Text(feature.progressSyncMessage)
                    if let notice = feature.noticeMessage { Text(notice) }
                    if let error = feature.errorMessage { Text(error).foregroundStyle(.red) }
                    Button("Retry retained captures") { Task { await feature.retryProgress() } }
                    Button("Sync game saves and snapshots") { Task { await feature.syncSaves() } }
                }
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
                    }
                }
                historySection(showingSnapshots ? "Resume and manual snapshots" : "Game-save history", snapshots: showingSnapshots)
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
                }
                Section {
                    Button("Leave game") { Task { await feature.leaveGame() } }
                }
            }
            .navigationTitle("Game progress")
            .toolbar {
                if feature.hasStarted { Button("Done") { feature.stateBrowserPresented = false } }
            }
        }
        .task { await feature.refreshProgress() }
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
    private func label(_ cp: ProgressCheckpoint) -> String {
        Date(timeIntervalSince1970: Double(cp.createdAtEpochMs) / 1000).formatted(date: .abbreviated, time: .standard) + " · " + cp.captureReason
    }
    @ViewBuilder private func historySection(_ title: String, snapshots: Bool) -> some View {
        Section(title) {
            let entries = feature.progressHistory.filter { ($0.checkpoint.stateCompatibility != nil) == snapshots }
            if entries.isEmpty { Text("No saved progress yet.").foregroundStyle(.secondary) }
            ForEach(entries) { entry in
                VStack(alignment: .leading) {
                    Text(label(entry.checkpoint))
                    if snapshots && feature.progressReferences.contains(where: { $0.key.hasPrefix("state/") && $0.value.count == 1 && $0.value[0].checkpoint?.checkpointId == entry.id }) {
                        Button("Remove from resume and manual slots", role: .destructive) { Task { await feature.deleteProgressSnapshot(entry.id) } }
                    }
                    if entry.pinned { Text("Preserved").font(.caption).foregroundStyle(.secondary) }
                    if snapshots && entry.checkpoint.stateCompatibility != feature.progressOwner?.qualification.state {
                        Text("Retained for its original core and platform. Its matching game save remains available separately.").font(.caption).foregroundStyle(.secondary)
                    } else {
                        Button(snapshots ? "Restore snapshot" : "Restore game-save data") { Task { await feature.restoreProgress(entry.id) } }.disabled(!feature.hasStarted)
                    }
                }
            }
        }
    }
}
#endif
