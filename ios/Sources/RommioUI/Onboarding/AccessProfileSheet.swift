import SwiftUI
import RommioContract
import RommioFoundation
import RommioPlayerKit

/// Access options: how Rommio reaches a protected server.
struct AccessProfileSheet: View {
    @Environment(\.rommioTheme) private var theme
    let model: RommioAppModel
    @Environment(\.dismiss) private var dismiss
    @State private var automatic: Bool
    @State private var mode: EdgeAuthMode
    @State private var clientID: String
    @State private var clientSecret: String

    init(model: RommioAppModel) {
        self.model = model
        _automatic = State(initialValue: !model.isEdgeAuthModeOverridden)
        _mode = State(initialValue: model.selectedEdgeAuthMode)
        _clientID = State(initialValue: model.cloudflareClientID)
        _clientSecret = State(initialValue: model.cloudflareClientSecret)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle("Detect automatically", isOn: $automatic)
                    if !automatic {
                        Picker("Connection", selection: $mode) {
                            ForEach(EdgeAuthMode.allCases, id: \.self) { mode in
                                Text(accessProfileLabel(mode)).tag(mode)
                            }
                        }
                    }
                } footer: {
                    Text("Most servers work automatically. Choose an option if your server needs extra access credentials.")
                }
                .themedRow()
                if !automatic && mode == .cloudflareAccessService {
                    Section("Cloudflare service token") {
                        TextField("Client ID", text: $clientID)
                            .rommioUsernameFieldTraits()
                            .accessibilityIdentifier("serverAccess.cloudflareClientID")
                        SecureField("Client secret", text: $clientSecret)
                            .accessibilityIdentifier("serverAccess.cloudflareClientSecret")
                    }
                    .themedRow()
                }
                if !automatic && (mode == .cloudflareAccessSession || mode == .genericCookieSSO) {
                    Section {
                        Text("A browser will open if your server needs you to sign in before connecting.")
                            .foregroundStyle(theme.inkSecondary)
                    }
                    .themedRow()
                }
            }
            .themedPage()
            .navigationTitle("Access options")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        model.selectedEdgeAuthMode = automatic ? .none : mode
                        model.isEdgeAuthModeOverridden = !automatic
                        model.cloudflareClientID = clientID
                        model.cloudflareClientSecret = clientSecret
                        model.errorMessage = nil
                        dismiss()
                    }
                }
            }
        }
    }
}
