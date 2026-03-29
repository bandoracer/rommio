import SwiftUI
import RommioFoundation

struct StateBrowserView: View {
    let browser: GameStateBrowser
    let onUseResume: (() -> Void)?
    let onLoadState: (BrowsableGameState) -> Void
    let onDeleteState: ((BrowsableGameState) -> Void)?
    let onClose: (() -> Void)?

    @State private var pendingDelete: BrowsableGameState?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("These are emulator states. In-game saves stay inside the game.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Section("Resume") {
                    resumeContent
                }

                Section("Save slots") {
                    if browser.saveSlots.isEmpty {
                        Text("No save slots available.")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(browser.saveSlots) { state in
                            stateRow(state)
                        }
                    }
                }

                Section("Snapshots") {
                    if browser.snapshots.isEmpty {
                        Text("No snapshots available.")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(browser.snapshots) { state in
                            stateRow(state)
                        }
                    }
                }
            }
            .navigationTitle("Browse states")
            .toolbar {
                if let onClose {
                    ToolbarItem {
                        Button("Done", action: onClose)
                    }
                }
            }
            .alert(item: $pendingDelete) { state in
                Alert(
                    title: Text("Delete \(state.label)?"),
                    message: Text(deleteMessage(for: state)),
                    primaryButton: .destructive(Text("Delete")) {
                        onDeleteState?(state)
                    },
                    secondaryButton: .cancel()
                )
            }
        }
    }

    @ViewBuilder
    private var resumeContent: some View {
        if let resume = browser.resume {
            VStack(alignment: .leading, spacing: 8) {
                Text(resume.primaryStatusMessage)
                    .font(.body.weight(.medium))
                if let source = resume.sourceDeviceName,
                   !source.isEmpty,
                   resume.sourceOrigin == .remoteDevice {
                    Text("From \(source)")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                if let updated = resume.updatedAtEpochMS {
                    Text("Updated \(formattedDate(updated))")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                if resume.available, let onUseResume {
                    Button(buttonTitle(for: resume), action: onUseResume)
                }
            }
            .padding(.vertical, 4)
        } else {
            Text("No resume state yet.")
                .foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private func stateRow(_ state: BrowsableGameState) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(state.label)
                .font(.body.weight(.medium))
            Text(subtitle(for: state))
                .font(.footnote)
                .foregroundStyle(.secondary)
            if let source = state.sourceDeviceName, !source.isEmpty {
                Text("From \(source)")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            HStack {
                Button("Load") {
                    onLoadState(state)
                }
                if state.deletePolicy != .none, onDeleteState != nil {
                    Button("Delete", role: .destructive) {
                        pendingDelete = state
                    }
                }
            }
            .buttonStyle(.borderless)
        }
        .padding(.vertical, 4)
    }

    private func subtitle(for state: BrowsableGameState) -> String {
        let timestamp = formattedDate(state.updatedAtEpochMS)
        switch state.originKind {
        case .manualSlot:
            return timestamp
        case .autoSnapshot:
            return "Snapshot • \(timestamp)"
        case .importedPlayable:
            return "Imported playable • \(timestamp)"
        }
    }

    private func buttonTitle(for resume: ResumeStateSummary) -> String {
        switch resume.statusKind {
        case .syncedRemoteSource, .cloudAvailable:
            return "Use resume"
        default:
            return "Play from resume"
        }
    }

    private func deleteMessage(for state: BrowsableGameState) -> String {
        switch state.deletePolicy {
        case .localAndRemoteWhenSupported:
            return "This removes the state from this device and marks the slot as deleted for sync."
        case .localOnly:
            return "This removes the imported local copy from this device."
        case .none:
            return "\(state.label) cannot be deleted."
        }
    }
}

private func formattedDate(_ epochMS: Int64) -> String {
    let formatter = DateFormatter()
    formatter.dateStyle = .medium
    formatter.timeStyle = .short
    return formatter.string(from: Date(timeIntervalSince1970: TimeInterval(epochMS) / 1000))
}
