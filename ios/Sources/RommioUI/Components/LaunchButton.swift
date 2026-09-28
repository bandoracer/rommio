import SwiftUI

extension EnvironmentValues {
    /// The game being launched: from a Resume or Play tap until its player or its Launch
    /// choice appears, or the launch fails (`RommioAppModel.launchingRomID`).
    @Entry var launchingRomID: Int?
}

/// Resume or Play for a game (revised 2026-09-28: a tap gave no sign of life until the player
/// appeared, seconds later on a slow sync). The tap is felt at once, the button turns to
/// "Starting…" with a spinner while the game prepares, and a second tap is ignored.
struct LaunchButton: View {
    @Environment(\.launchingRomID) private var launchingRomID
    let title: String
    let romID: Int
    var large = true
    let action: () -> Void
    @State private var taps = 0

    var body: some View {
        let starting = launchingRomID == romID
        Button {
            taps += 1
            action()
        } label: {
            LaunchButtonLabel(title: title, starting: starting)
        }
        .buttonStyle(PrimaryButtonStyle(large: large))
        // Another game's launch holds this one too; this game's shows as starting.
        .disabled(launchingRomID != nil && !starting)
        .allowsHitTesting(!starting)
        .sensoryFeedback(.impact(weight: .medium, intensity: 0.9), trigger: taps)
        .animation(.snappy(duration: 0.2), value: starting)
    }
}

/// The toolbar's Play: the symbol turns to a spinner while the game starts.
struct LaunchToolbarLabel: View {
    @Environment(\.launchingRomID) private var launchingRomID
    let title: String
    let systemImage: String
    let romID: Int

    var body: some View {
        if launchingRomID == romID {
            ProgressView().accessibilityLabel("Starting")
        } else {
            Label(title, systemImage: systemImage)
        }
    }
}

private struct LaunchButtonLabel: View {
    @Environment(\.rommioTheme) private var theme
    let title: String
    let starting: Bool

    var body: some View {
        if starting {
            HStack(spacing: 10) {
                ProgressView()
                    .progressViewStyle(.circular)
                    .tint(theme.buttonInk)
                    .controlSize(.small)
                Text("Starting…")
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel("Starting")
        } else {
            Label(title, systemImage: "play.fill")
        }
    }
}
