import SwiftUI
import RommioContract
import RommioFoundation
import RommioPlayerKit

/// Sign in with a password or single sign-on.
struct LoginStepView: View {
    @Environment(\.rommioTheme) private var theme
    @Bindable var model: RommioAppModel
    @FocusState private var focusedField: Field?
    private enum Field { case username, password }

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            StepHeader(step: "Step 2 of 2", title: "Sign in", subtitle: "Your account")
            ThemedGroup(inset: 58) {
                ListRow(title: model.activeProfile?.label ?? "RomM", subtitle: model.activeProfile?.baseURL.absoluteString, leading: {
                    IconTile(systemImage: "server.rack", tone: .live)
                })
            }
            VStack(alignment: .leading, spacing: 12) {
                switch model.effectiveOriginAuthMode {
                case .rommBearerPassword, .rommBasicLegacy:
                    FieldLabel(text: "Username")
                    TextField("Username", text: $model.username)
                        .rommioUsernameFieldTraits()
                        .focused($focusedField, equals: .username)
                        .submitLabel(.next)
                        .onSubmit { focusedField = .password }
                        .onboardingField()
                        .accessibilityIdentifier("login.username")
                    FieldLabel(text: "Password")
                    SecureField("Password", text: $model.password)
                        .rommioPasswordFieldTraits()
                        .focused($focusedField, equals: .password)
                        .submitLabel(.go)
                        .onSubmit { signIn() }
                        .onboardingField()
                        .accessibilityIdentifier("login.password")
                    OnboardingError(message: model.errorMessage)
                    Button(action: signIn) {
                        OnboardingButtonLabel(title: "Sign in", isBusy: model.isBusy)
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(model.username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || model.password.isEmpty || model.isBusy)
                    .accessibilityIdentifier("login.signIn")
                case .rommOIDCSession:
                    OnboardingError(message: model.errorMessage)
                    Button {
                        model.beginInteractiveOriginLogin()
                    } label: {
                        OnboardingButtonLabel(title: "Continue with single sign-on", isBusy: model.isBusy)
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .accessibilityIdentifier("login.continueWithSSO")
                case .none:
                    OnboardingError(message: model.errorMessage)
                    Button(action: signIn) {
                        OnboardingButtonLabel(title: "Continue", isBusy: model.isBusy)
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .accessibilityIdentifier("login.validateCurrentSession")
                }
            }
            .disabled(model.isBusy)
        }
    }

    private func signIn() {
        focusedField = nil
        Task { await model.submitLogin() }
    }
}
