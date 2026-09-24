import SwiftUI
import RommioContract
import RommioFoundation
import RommioPlayerKit

struct OnboardingFlowView: View {
    @Environment(\.rommioTheme) private var theme
    let model: RommioAppModel
    let route: OnboardingRoute

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                if route == .login {
                    LoginStepView(model: model)
                } else {
                    ServerAccessStepView(model: model)
                }
            }
            .padding(.horizontal, theme.spacing.gutter + 4)
            .padding(.top, 12)
            .padding(.bottom, 24)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier(route == .login ? "screen.login" : "screen.serverAccess")
        }
        .scrollDismissesKeyboard(.interactively)
        .background { PageBackground(style: .welcome) }
        .toolbar {
            if route == .login {
                ToolbarItem(placement: .cancellationAction) {
                    Button {
                        model.changeServerAccess()
                    } label: {
                        Label("Server", systemImage: "chevron.left")
                    }
                    .disabled(model.isBusy)
                    .accessibilityIdentifier("login.changeServerAccess")
                }
            }
        }
    }
}
