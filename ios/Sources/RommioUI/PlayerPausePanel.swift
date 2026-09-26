import SwiftUI

// Pause, shared by the player (a sheet, or the floating inspector beside the game on
// a wide window) and the controls QA harness. SwiftUI only, so the stack budget
// measures it on every platform.

/// What the Pause panel's buttons do.
struct PlayerPauseActions {
    let resume: () async -> Void
    let quickSave: () async -> Void
    let savedProgress: () async -> Void
    let controls: () -> Void
    let sync: () async -> Void
    let reset: () async -> Void
    let leave: () async -> Void
    /// Mutes or unmutes the game's audio (the silent switch never does).
    var toggleSound: () async -> Void = {}
}

/// Pause: the game, Resume, and the actions as tiles. Aurora: glass tiles.
/// Riso: a numbered manual page, Leave game printed in pink. The action titles
/// and identifiers are the player's contract and never change. A sheet over the
/// game, or the floating inspector beside it on a wide window.
struct PlayerPausePanel: View {
    @Environment(\.rommioTheme) private var theme
    let title: String
    let platformSlug: String
    let runtimeName: String
    var audioMuted = false
    let actions: PlayerPauseActions

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            header
            HStack(spacing: 10) {
                Button {
                    Task { await actions.resume() }
                } label: {
                    Label("Resume", systemImage: "play.fill")
                }
                .buttonStyle(PrimaryButtonStyle(large: false))
                .accessibilityIdentifier("player.pause.resume")
                PauseSoundToggle(muted: audioMuted) {
                    Task { await actions.toggleSound() }
                }
            }

            LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
                PauseTile(number: 1, title: "Save snapshot", symbol: "camera") {
                    Task { await actions.quickSave() }
                }
                .accessibilityIdentifier("player.pause.quickSave")
                PauseTile(number: 2, title: "Saved progress", symbol: "square.stack.3d.up") {
                    Task { await actions.savedProgress() }
                }
                .accessibilityIdentifier("player.pause.stateBrowser")
                PauseTile(number: 3, title: "Controls", symbol: "gamecontroller") {
                    actions.controls()
                }
                .accessibilityIdentifier("player.pause.controls")
                PauseTile(number: 4, title: "Sync game saves and snapshots", symbol: "arrow.triangle.2.circlepath") {
                    Task { await actions.sync() }
                }
                .accessibilityIdentifier("player.pause.sync")
                PauseTile(number: 5, title: "Reset core", symbol: "arrow.counterclockwise") {
                    Task { await actions.reset() }
                }
                .accessibilityIdentifier("player.pause.reset")
                PauseTile(number: 6, title: "Leave game", symbol: "rectangle.portrait.and.arrow.right", destructive: true) {
                    Task { await actions.leave() }
                }
                .accessibilityIdentifier("player.pause.leave")
            }
        }
        .padding(.horizontal, theme.spacing.gutter)
        .padding(.top, 22)
        .padding(.bottom, 12)
        .frame(maxWidth: 620, maxHeight: .infinity, alignment: .top)
        .frame(maxWidth: .infinity)
        .background { PageBackground(style: .quiet) }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("player.pause.actions")
    }

    @ViewBuilder
    private var header: some View {
        if theme.isRiso {
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text("Paused")
                    Spacer()
                    Text("\(platformSlug.uppercased()) · \(runtimeName)").foregroundStyle(theme.inkSecondary)
                }
                .themeText(.label, size: 10.5)
                .foregroundStyle(theme.ink)
                .padding(.bottom, 6)
                .overlay(alignment: .bottom) { Rectangle().fill(theme.ink).frame(height: 3) }
                Text(title).themeText(.headline, size: 26).foregroundStyle(theme.ink).lineLimit(1)
            }
        } else {
            VStack(alignment: .leading, spacing: 3) {
                Text("Paused").themeText(.section).foregroundStyle(theme.inkSecondary)
                Text(title).themeText(.headline).foregroundStyle(theme.ink).lineLimit(1)
            }
        }
    }
}

/// The game's sound, beside Resume: a speaker, struck through and inked when muted.
private struct PauseSoundToggle: View {
    @Environment(\.rommioTheme) private var theme
    let muted: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: muted ? "speaker.slash.fill" : "speaker.wave.2.fill")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(muted ? (theme.isRiso ? theme.onAccent : theme.badText) : (theme.isRiso ? theme.ink : theme.unlitLight.readableTint))
                .frame(width: 44, height: 44)
                .background {
                    if theme.isRiso {
                        Rectangle().fill(muted ? theme.accent1 : theme.surface)
                        if !muted { Rectangle().strokeBorder(theme.ink, lineWidth: theme.material.strokeWidth) }
                    } else {
                        GlassFill(shape: Circle())
                        GlassEdge(shape: Circle())
                    }
                }
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Sound")
        .accessibilityValue(muted ? "Muted" : "On")
        .accessibilityAddTraits(muted ? .isSelected : [])
        .accessibilityIdentifier("player.pause.sound")
    }
}

/// One pause action.
private struct PauseTile: View {
    @Environment(\.rommioTheme) private var theme
    let number: Int
    let title: String
    let symbol: String
    var destructive = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(alignment: .center, spacing: 10) {
                if theme.isRiso {
                    Text("\(number)").themeText(.statValue, size: 26).foregroundStyle(destructive ? theme.onAccent : theme.accent2)
                        .frame(width: 20)
                }
                Image(systemName: symbol)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(destructive ? (theme.isRiso ? theme.onAccent : theme.badText) : (theme.isRiso ? theme.ink : theme.unlitLight.readableTint))
                    .frame(width: 22)
                Text(title)
                    .themeText(theme.isRiso ? .section : .bodyEmphasis, size: theme.isRiso ? 15 : 15)
                    .foregroundStyle(destructive ? (theme.isRiso ? theme.onAccent : theme.badText) : theme.ink)
                    .multilineTextAlignment(.leading)
                    .lineLimit(3)
                    .fixedSize(horizontal: false, vertical: true)
                    .minimumScaleFactor(0.8)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12)
            .frame(maxWidth: .infinity, minHeight: 60, alignment: .leading)
            .background {
                if theme.isRiso {
                    Rectangle().fill(destructive ? theme.accent1 : theme.surface)
                    if !destructive { Rectangle().strokeBorder(theme.ink, lineWidth: theme.material.strokeWidth) }
                } else {
                    GlassFill(shape: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    GlassEdge(shape: RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
    }
}
