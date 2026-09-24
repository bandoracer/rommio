import SwiftUI
import UniformTypeIdentifiers
import RommioFoundation

struct ProgressRecoveryView: View {
    let service: ApplicationProgress
    @State private var items: [ProgressRecoveryItem] = []
    @State private var busy = false
    @State private var message: String?
    @State private var error: String?
    @State private var exported: URL?
    @State private var importing = false
    @State private var preview: ProgressImportPreview?
    @State private var confirmingImport = false
    @State private var confirmingRollback = false
    @State private var archiveChoice: ProgressRecoveryItem?
    @State private var publishChoice: ProgressRecoveryItem?
    @State private var repairChoice: ProgressRecoveryItem?
    var body: some View {
        List {
            Section("Progress backup") {
                Text("Includes game saves, snapshots, history, conflicts, pending sync work, and retained recovery files. Accounts, passwords, games, and cores are not included.")
                Button("Create backup") { perform { exported = try await service.exportProgress(); message = "Backup ready to save or share." } }
                if let exported { ShareLink("Save or share backup", item: exported) }
                Button("Restore a backup") { repairChoice = nil; importing = true }
                Text("Exit the player first. Restore preserves the current progress as a recoverable previous version. Imported legacy files need an explicit game/account choice in the player.").font(.footnote)
            }
            .themedRow()
            if let message { Section { Text(message).foregroundStyle(.secondary) }.themedRow() }
            if let error { Section("Recovery needs attention") { Text(error); Button("Retry interrupted import") { perform { try await service.recoverImport(); message = "Import recovery completed." } } }.themedRow() }
            // "Recovery" as on Android; "Progress backup" is iOS-only (see docs/theme-experiences-plan.md).
            Section("Recovery") {
                if items.isEmpty { Text("No recovery items found.").foregroundStyle(.secondary) }
                ForEach(items) { item in
                    VStack(alignment: .leading, spacing: 8) {
                        Text(item.title).font(.headline)
                        Text(item.detail).font(.footnote)
                        switch item.kind {
                        case .localOnly:
                            Button("Enable sync for this history") { publishChoice = item }
                        case .capture:
                            if item.identity != nil { Button("Retry captured progress") { perform { try await service.retryCapture(item.id); message = "Captured progress committed." } } }
                            Button("Archive for recovery") { archiveChoice = item }
                        case .session:
                            Button("Archive workspace") { archiveChoice = item }
                        case .archived:
                            Button("Return to recovery queue") { perform { try await service.restoreRecoveryArchive(item.id) } }
                        case .previous:
                            Button("Export previous progress") { perform { exported = try await service.exportPrevious(item.id); message = "Previous progress backup ready. Inspect it with Restore a backup to restore it." } }
                        case .damagedArtifact:
                            Button("Repair from matching file") { repairChoice = item; importing = true }
                            if let identity = item.identity { Button("Retry cloud repair") { perform { message = try await service.synchronize(identity).message } } }
                        case .interruptedImport:
                            Button("Finish interrupted import") { perform { try await service.recoverImport() } }
                            Button("Restore pre-import progress") { confirmingRollback = true }
                        }
                    }
                }
            }
            .themedRow()
        }
        .themeText(.body, size: 15)
        .themedList()
        .navigationTitle("Backup and recovery")
        .disabled(busy)
        .overlay { if busy { ProgressView("Preserving progress…").padding().background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12)) } }
        .task { await refresh() }
        .refreshable { await refresh() }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.data]) { result in
            switch result {
            case .failure(let failure): error = failure.localizedDescription
            case .success(let url):
                perform {
                    let granted = url.startAccessingSecurityScopedResource()
                    defer { if granted { url.stopAccessingSecurityScopedResource() } }
                    if let item = repairChoice { try await service.repairArtifact(item, source: url); message = "Matching bytes restored. Damaged original retained." }
                    else { preview = try await service.inspectImport(url); confirmingImport = true }
                }
            }
        }
        .alert("Restore progress backup?", isPresented: $confirmingImport) {
            Button("Cancel", role: .cancel) { preview = nil }
            Button("Preserve current and restore", role: .destructive) {
                guard let preview else { return }
                perform { try await service.importProgress(preview.id); self.preview = nil; message = "Progress restored. Previous progress is retained below. Sign in to the original account before syncing." }
            }
        } message: {
            if let preview { Text("Replace progress for all accounts with \(preview.operations) recorded operations and \(preview.files) files. \(preview.damagedArtifacts) missing or damaged artifacts remain recoverable history and cannot be loaded until repaired. Current progress will be retained. Local-only records remain local-only.") }
        }
        .confirmationDialog("Return to the progress before this import?", isPresented: $confirmingRollback) {
            Button("Restore pre-import progress", role: .destructive) { perform { try await service.rollbackPendingImport(); message = "Pre-import progress restored. Both versions remain retained." } }
        } message: { Text("This cancels the interrupted replacement and restores its retained original files and metadata. The replacement files will also be retained.") }
        .confirmationDialog("Upload this local-only history?", isPresented: Binding(get: { publishChoice != nil }, set: { if !$0 { publishChoice = nil } })) {
            if let identity = publishChoice?.identity { Button("Enable game-save and snapshot sync") { perform { try await service.publishLocalHistory(identity); publishChoice = nil } } }
        } message: { Text("This queues retained game saves and snapshots for the original account. No remote history is overwritten.") }
        .confirmationDialog("Preserve these files in the recovery archive?", isPresented: Binding(get: { archiveChoice != nil }, set: { if !$0 { archiveChoice = nil } })) {
            if let item = archiveChoice { Button("Archive without deleting") { perform { try await service.archiveRecovery(item.id); archiveChoice = nil; message = "Files retained in the recovery archive." } } }
        } message: { Text("Automatic capture retry will stop for this item. Its original bytes stay in backups and can be returned to the recovery queue.") }
    }
    @MainActor private func refresh() async {
        do { items = try await service.recoveryItems() }
        catch { self.error = error.localizedDescription }
    }
    @MainActor private func perform(_ action: @escaping @MainActor () async throws -> Void) {
        guard !busy else { return }
        busy = true; error = nil
        Task {
            defer { busy = false }
            do { try await action() } catch { self.error = error.localizedDescription }
            await refresh()
        }
    }
}
