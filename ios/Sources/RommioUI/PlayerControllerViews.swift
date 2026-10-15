import SwiftUI
import RommioFoundation

/// Pause › Controls › Controller: who's connected and which player each is, the stick
/// deadzone, the core's controller type, rumble, and Remap buttons.
struct PlayerControllerSection: View {
    @Environment(\.rommioTheme) private var theme
    let feature: PlayerFeatureModel

    var body: some View {
        Section {
            ConnectedControllerRows(feature: feature)
            NavigationLink {
                ControllerRemapPage(feature: feature)
            } label: {
                LabeledContent("Remap buttons", value: feature.controllerBindings.isStandard ? "Standard" : "Changed")
            }
            .accessibilityIdentifier("player.controls.remap")
            ControllerTuningRows(feature: feature)
        } header: {
            SectionHeader(title: "Controller")
        } footer: {
            Text("Controllers play in the order they connect: the first is player 1, the next player 2. A keyboard plays as player 1. Home, or both stick clicks, opens the pause menu; on a keyboard Escape does, ⌘S quick-saves and ⌘L quick-loads.")
        }
        .themedRow()
    }
}

private struct ConnectedControllerRows: View {
    @Environment(\.rommioTheme) private var theme
    let feature: PlayerFeatureModel

    var body: some View {
        let controllers = feature.controlsState?.connectedControllers ?? []
        let players = feature.controllerPlayers
        if controllers.isEmpty && !feature.keyboardConnected {
            Text("No controller connected")
                .foregroundStyle(theme.inkSecondary)
                .accessibilityIdentifier("player.controls.noController")
        }
        ForEach(controllers) { controller in
            LabeledContent(controller.name, value: players[controller.deviceID].map { "Player \($0)" } ?? "Connected")
        }
        if feature.keyboardConnected {
            LabeledContent("Keyboard", value: "Player 1")
        }
    }
}

private struct ControllerTuningRows: View {
    let feature: PlayerFeatureModel

    var body: some View {
        ControllerDeadzoneRow(feature: feature)
        if !feature.availableControllerTypes.isEmpty {
            Picker("Controller type", selection: Binding(
                get: { feature.controlsState?.hardwareBinding.controllerTypeID ?? -1 },
                set: { value in Task { await feature.setControllerType(value < 0 ? nil : value) } }
            )) {
                Text("Default").tag(-1)
                ForEach(feature.availableControllerTypes) { descriptor in
                    Text(descriptor.description).tag(descriptor.id)
                }
            }
        }
        Toggle("Rumble to device", isOn: Binding(
            get: { feature.controlsState?.preferences.rumbleToDeviceEnabled ?? true },
            set: { value in Task { await feature.setRumbleToDeviceEnabled(value) } }
        ))
    }
}

private struct ControllerDeadzoneRow: View {
    @Environment(\.rommioTheme) private var theme
    let feature: PlayerFeatureModel

    var body: some View {
        let value = Binding(
            get: { feature.controlsState?.hardwareBinding.deadzone ?? 0.2 },
            set: { value in Task { await feature.updateDeadzone(value) } }
        )
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("Deadzone").foregroundStyle(theme.ink)
                Spacer()
                Text(value.wrappedValue.formatted(.percent.precision(.fractionLength(0))))
                    .themeText(.numeral, size: 13)
                    .foregroundStyle(theme.inkSecondary)
            }
            Slider(value: value, in: 0.05...0.5)
        }
    }
}

/// Remap buttons: each of the console's buttons with the controller button and key that
/// press it. Tap one, then press a button or a key; whatever pressed it before takes over
/// the old one, so every button stays reachable.
struct ControllerRemapPage: View {
    @Environment(\.rommioTheme) private var theme
    let feature: PlayerFeatureModel
    @State private var listening: Int?

    var body: some View {
        List {
            Section {
                ForEach(feature.remappableButtons, id: \.button) { item in
                    RemapRow(feature: feature, button: item.button, name: item.name, listening: listening == item.button) {
                        listen(item.button)
                    }
                }
            } header: {
                SectionHeader(title: "Buttons")
            } footer: {
                Text(hint)
            }
            .themedRow()

            Section {
                Button("Reset to standard", role: .destructive) {
                    stopListening()
                    Task { await feature.resetControllerBindings() }
                }
                .disabled(feature.controllerBindings.isStandard)
                .foregroundStyle(feature.controllerBindings.isStandard ? theme.inkTertiary : theme.badText)
                .accessibilityIdentifier("player.remap.reset")
            } footer: {
                Text("For this console, on this device. Standard is by position: the bottom face button is the console's lower button, and so on.")
            }
            .themedRow()
        }
        .themeText(.body, size: 16)
        .themedList()
        .navigationTitle("Remap buttons")
        .accessibilityIdentifier("player.remap")
        .onDisappear { stopListening() }
    }

    private var hint: String {
        if listening != nil { return "Press a button on the controller, or a key. Tap the row again to cancel." }
        if (feature.controlsState?.connectedControllers.isEmpty ?? true) && !feature.keyboardConnected {
            return "Connect a controller or a keyboard to remap its buttons."
        }
        return "Tap a button, then press the control you want for it."
    }

    private func listen(_ button: Int) {
        guard listening != button else { stopListening(); return }
        listening = button
        feature.listenForControl { control in
            Task { @MainActor in
                feature.stopListeningForControl()
                listening = nil
                await feature.assign(control, to: button)
            }
        }
    }

    private func stopListening() {
        listening = nil
        feature.stopListeningForControl()
    }
}

private struct RemapRow: View {
    @Environment(\.rommioTheme) private var theme
    let feature: PlayerFeatureModel
    let button: Int
    let name: String
    let listening: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Text(name).foregroundStyle(theme.ink)
                Spacer(minLength: 8)
                if listening {
                    Text("Press a control…")
                        .themeText(.caption)
                        .foregroundStyle(theme.unlitLight.readableTint)
                } else {
                    BoundControls(feature: feature, button: button)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("player.remap.\(button)")
    }
}

/// The controller buttons (as the controller's own symbols where it has them) and keys
/// that press a console button.
private struct BoundControls: View {
    @Environment(\.rommioTheme) private var theme
    let feature: PlayerFeatureModel
    let button: Int

    var body: some View {
        let bindings = feature.controllerBindings
        let elements = bindings.elements(for: button)
        let keys = bindings.keys(for: button)
        HStack(spacing: 8) {
            ForEach(elements, id: \.self) { element in
                if let symbol = feature.controllerSymbol(element) {
                    Image(systemName: symbol).accessibilityLabel(element.name)
                } else {
                    Text(element.name)
                }
            }
            ForEach(keys, id: \.self) { key in
                Text(KeyboardKey.name(key))
                    .themeText(.numeral, size: 12)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .overlay(RoundedRectangle(cornerRadius: 4).strokeBorder(theme.hairline))
            }
            if elements.isEmpty && keys.isEmpty {
                Text("None")
            }
        }
        .themeText(.caption)
        .foregroundStyle(theme.inkSecondary)
    }
}
