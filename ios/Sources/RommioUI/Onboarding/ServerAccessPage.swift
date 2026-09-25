import SwiftUI
import RommioContract
import RommioFoundation
import RommioPlayerKit

/// Connect: the server address, saved servers, access options and the setup code.
struct ServerAccessStepView: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.pageLight) private var light
    @Bindable var model: RommioAppModel
    @State private var showsAccessProfile = false
    @State private var showsOneTouchSetup = false
    @FocusState private var addressFocused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            StepHeader(step: "Step 1 of 2", title: "Connect", subtitle: "Your RomM server")

            VStack(alignment: .leading, spacing: 12) {
                FieldLabel(text: "Server address")
                HStack(spacing: 12) {
                    Image(systemName: "globe")
                        .foregroundStyle(theme.inkSecondary)
                        .accessibilityHidden(true)
                    TextField("romm.example.com", text: $model.serverURL)
                        .rommioServerURLFieldTraits { connect() }
                        .focused($addressFocused)
                        .accessibilityLabel("Server address")
                        .accessibilityIdentifier("serverAccess.serverURL")
                }
                .onboardingField()

                Button {
                    addressFocused = false
                    showsAccessProfile = true
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "lock.shield")
                            .foregroundStyle(theme.inkSecondary)
                        Text("Access options")
                        Spacer()
                        Text(model.isEdgeAuthModeOverridden ? accessProfileLabel(model.selectedEdgeAuthMode) : "Automatic")
                            .foregroundStyle(theme.inkSecondary)
                        Image(systemName: "chevron.right")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(theme.inkTertiary)
                    }
                    .onboardingField()
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("serverAccess.accessProfile")

                OnboardingError(message: model.errorMessage)

                Button(action: connect) {
                    OnboardingButtonLabel(title: "Continue", isBusy: model.isBusy)
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(model.serverURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || model.isBusy)
                .accessibilityIdentifier("serverAccess.connect")
            }
            .disabled(model.isBusy)

            if !model.profiles.isEmpty {
                ThemedGroup(title: "Saved servers", detail: "\(model.profiles.count)", inset: 58) {
                    ForEach(model.profiles) { profile in
                        Button {
                            addressFocused = false
                            Task { await model.switchProfile(to: profile) }
                        } label: {
                            ListRow(title: profile.label, subtitle: profile.baseURL.absoluteString) {
                                IconTile(systemImage: "server.rack", tone: .live)
                            } trailing: {
                                Chevron()
                            }
                        }
                        .buttonStyle(RowButtonStyle())
                        .disabled(model.isBusy)
                    }
                }
            }

            HStack(spacing: 6) {
                Text("Have a setup code from RomM?")
                    .foregroundStyle(theme.inkSecondary)
                Button {
                    addressFocused = false
                    showsOneTouchSetup = true
                } label: {
                    Label("Scan it", systemImage: "qrcode.viewfinder")
                        .foregroundStyle(light.readableTint)
                }
                .buttonStyle(.plain)
                .disabled(model.isBusy)
                .accessibilityIdentifier("serverAccess.oneTouchSetup")
            }
            .themeText(.secondary)
            .frame(maxWidth: .infinity)
        }
        .sheet(isPresented: $showsAccessProfile) {
            AccessProfileSheet(model: model)
        }
        .sheet(isPresented: $showsOneTouchSetup) {
            OneTouchSetupView(appModel: model)
        }
    }

    private func connect() {
        addressFocused = false
        Task { await model.connectServer() }
    }
}
