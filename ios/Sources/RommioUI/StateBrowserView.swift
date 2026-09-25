import SwiftUI
import RommioFoundation

/// Save states for one game: the automatic resume, save slots and snapshots,
/// in the theme's list (glass groups, or paper rows between rules).
struct StateBrowserView: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.pageLight) private var light
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
                        .themeText(.caption)
                        .foregroundStyle(theme.inkSecondary)
                }
                .themedRow()

                Section {
                    resumeContent
                } header: {
                    SectionHeader(title: "Resume")
                }
                .themedRow()

                Section {
                    if browser.saveSlots.isEmpty {
                        Text("No save slots available.")
                            .themeText(.secondary)
                            .foregroundStyle(theme.inkSecondary)
                    } else {
                        ForEach(browser.saveSlots) { state in
                            stateRow(state)
                        }
                    }
                } header: {
                    SectionHeader(title: "Save slots", detail: browser.saveSlots.isEmpty ? nil : "\(browser.saveSlots.count)")
                }
                .themedRow()

                Section {
                    if browser.snapshots.isEmpty {
                        Text("No snapshots available.")
                            .themeText(.secondary)
                            .foregroundStyle(theme.inkSecondary)
                    } else {
                        ForEach(browser.snapshots) { state in
                            stateRow(state)
                        }
                    }
                } header: {
                    SectionHeader(title: "Snapshots", detail: browser.snapshots.isEmpty ? nil : "\(browser.snapshots.count)")
                }
                .themedRow()
            }
            .themedList()
            .tint(light.readableTint)
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
                HStack(alignment: .firstTextBaseline) {
                    Text(resume.primaryStatusMessage)
                        .themeText(.bodyEmphasis, size: 16)
                        .foregroundStyle(theme.ink)
                    Spacer(minLength: 8)
                    if let badge = GameDetailView.badge(resume.statusKind) {
                        SyncBadge(state: badge)
                    }
                }
                if let source = resume.sourceDeviceName,
                   !source.isEmpty,
                   resume.sourceOrigin == .remoteDevice {
                    Text("From \(source)")
                        .themeText(.caption)
                        .foregroundStyle(theme.inkSecondary)
                }
                if let updated = resume.updatedAtEpochMS {
                    Text("Updated \(formattedDate(updated))")
                        .themeText(.caption)
                        .foregroundStyle(theme.inkSecondary)
                }
                if resume.available, let onUseResume {
                    Button(buttonTitle(for: resume), action: onUseResume)
                        .buttonStyle(PrimaryButtonStyle(fullWidth: false, large: false))
                        .padding(.top, 4)
                }
            }
            .padding(.vertical, 4)
        } else {
            Text("No resume state yet.")
                .themeText(.secondary)
                .foregroundStyle(theme.inkSecondary)
        }
    }

    @ViewBuilder
    private func stateRow(_ state: BrowsableGameState) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                if let slot = state.slot {
                    Text("\(slot)")
                        .themeText(.statValue, size: 22)
                        .foregroundStyle(theme.isRiso ? theme.accent2 : light.mark1)
                }
                Text(state.label)
                    .themeText(.bodyEmphasis, size: 16)
                    .foregroundStyle(theme.ink)
            }
            Text(subtitle(for: state))
                .themeText(.caption)
                .foregroundStyle(theme.inkSecondary)
            if let source = state.sourceDeviceName, !source.isEmpty {
                Text("From \(source)")
                    .themeText(.caption)
                    .foregroundStyle(theme.inkSecondary)
            }
            HStack(spacing: 10) {
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
            .themeText(.bodyEmphasis, size: 15)
        }
        .padding(.vertical, 4)
    }

    private func subtitle(for state: BrowsableGameState) -> String {
        let timestamp = formattedDate(state.updatedAtEpochMS)
        switch state.originKind {
        case .manualSlot:
            return timestamp
        case .autoSnapshot:
            return "Snapshot · \(timestamp)"
        case .importedPlayable:
            return "Imported playable · \(timestamp)"
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
