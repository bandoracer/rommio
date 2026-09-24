import SwiftUI
import RommioContract
import RommioFoundation
import RommioPlayerKit

struct OnboardingButtonLabel: View {
    let title: String
    let isBusy: Bool
    var body: some View {
        HStack(spacing: 8) {
            if isBusy { ProgressView() }
            Text(isBusy ? "Connecting…" : title)
        }
        .frame(maxWidth: .infinity)
    }
}

struct OnboardingError: View {
    @Environment(\.rommioTheme) private var theme
    let message: String?
    var body: some View {
        if let message {
            Label(message, systemImage: "exclamationmark.circle")
                .themeText(.secondary)
                .foregroundStyle(theme.badText)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("onboarding.error")
        }
    }
}

func accessProfileLabel(_ mode: EdgeAuthMode) -> String {
    switch mode {
    case .none: "Direct"
    case .cloudflareAccessSession: "Cloudflare login"
    case .cloudflareAccessService: "Service token"
    case .genericCookieSSO: "Browser login"
    }
}

/// A text field or tappable row on the theme's field surface: glass (Aurora) or
/// a paper field inside a 1.5 pt ink box with mono text (Riso).
private struct OnboardingFieldModifier: ViewModifier {
    @Environment(\.rommioTheme) private var theme

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: theme.radius.field, style: .continuous)
        if theme.isRiso {
            content
                .themeText(.numeral, size: 15)
                .foregroundStyle(theme.ink)
                .padding(.horizontal, 14)
                .padding(.vertical, 14)
                .frame(minHeight: 52)
                .background(theme.surface)
                .overlay { Rectangle().strokeBorder(theme.ink, lineWidth: theme.material.strokeWidth) }
        } else {
            content
                .themeText(.body)
                .foregroundStyle(theme.ink)
                .padding(.horizontal, 16)
                .padding(.vertical, 14)
                .frame(minHeight: 52)
                .background { GlassFill(shape: shape, strong: true) }
                .overlay { GlassEdge(shape: shape) }
        }
    }
}

/// A setup step's header. Aurora: the page title and a quiet step line. Riso: a
/// mono step rule over a misregistered headline.
struct StepHeader: View {
    @Environment(\.rommioTheme) private var theme
    let step: String
    let title: String
    var subtitle: String?

    var body: some View {
        if theme.isRiso {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text(step)
                    Spacer()
                    if let subtitle { Text(subtitle).foregroundStyle(theme.inkSecondary) }
                }
                .themeText(.label, size: 10.5)
                .foregroundStyle(theme.ink)
                .padding(.bottom, 7)
                .overlay(alignment: .bottom) { Rectangle().fill(theme.ink).frame(height: 1.5) }
                DisplayText(text: title, role: .display, size: 54, lineLimit: 2)
                    .padding(.top, 6)
            }
        } else {
            VStack(alignment: .leading, spacing: 6) {
                Text(title)
                    .themeText(.title)
                    .foregroundStyle(theme.ink)
                    .accessibilityAddTraits(.isHeader)
                Text([step, subtitle].compactMap { $0 }.joined(separator: " · "))
                    .themeText(.secondary)
                    .foregroundStyle(theme.inkSecondary)
            }
        }
    }
}

/// A caption over a field (Riso mono caps; Aurora quiet label).
struct FieldLabel: View {
    @Environment(\.rommioTheme) private var theme
    let text: String

    var body: some View {
        Text(text)
            .themeText(theme.isRiso ? .label : .caption, size: theme.isRiso ? 9.5 : 12.5)
            .foregroundStyle(theme.isRiso ? theme.ink : theme.inkSecondary)
            .accessibilityHidden(true)
    }
}

extension View {
    /// Full-screen pages that draw their own header.
    @ViewBuilder
    func hidesNavigationBar() -> some View {
#if os(iOS)
        toolbar(.hidden, for: .navigationBar)
#else
        self
#endif
    }

    /// The bar stays clear over art-forward headers.
    @ViewBuilder
    func transparentNavigationBar() -> some View {
#if os(iOS)
        toolbarBackground(.hidden, for: .navigationBar)
#else
        self
#endif
    }

    /// Inline navigation titles on iOS.
    @ViewBuilder
    func inlineNavigationTitle() -> some View {
#if os(iOS)
        navigationBarTitleDisplayMode(.inline)
#else
        self
#endif
    }

    /// Large navigation titles on iOS.
    @ViewBuilder
    func largeNavigationTitle() -> some View {
#if os(iOS)
        navigationBarTitleDisplayMode(.large)
#else
        self
#endif
    }

    func onboardingField() -> some View {
        modifier(OnboardingFieldModifier())
    }

    @ViewBuilder
    func rommioUsernameFieldTraits() -> some View {
#if os(iOS)
        autocorrectionDisabled()
            .textInputAutocapitalization(.never)
            .textContentType(.username)
#else
        autocorrectionDisabled()
#endif
    }

    @ViewBuilder
    func rommioPasswordFieldTraits() -> some View {
#if os(iOS)
        textContentType(.password)
#else
        self
#endif
    }

    @ViewBuilder
    func rommioServerURLFieldTraits(onSubmit action: @escaping () -> Void) -> some View {
#if os(iOS)
        autocorrectionDisabled()
            .textInputAutocapitalization(.never)
            .keyboardType(.URL)
            .textContentType(.URL)
            .submitLabel(.go)
            .onSubmit(action)
#else
        autocorrectionDisabled()
            .onSubmit(action)
#endif
    }
}

struct InteractiveAuthOverlay: View {
    @Environment(\.rommioTheme) private var theme
    let provider: InteractiveSessionProvider

    var body: some View {
        ZStack {
            theme.scrim
                .ignoresSafeArea()
            VStack(spacing: 12) {
                ProgressView()
                Text(provider == .edge ? "Completing protected login…" : "Completing RomM sign-in…")
                    .themeText(.bodyEmphasis)
                    .foregroundStyle(theme.ink)
                Text(provider == .edge
                     ? "Finish signing in in your browser. Rommio will check the connection automatically."
                     : "Finish the system authentication sheet, then the app will validate the RomM session.")
                    .multilineTextAlignment(.center)
                    .themeText(.secondary)
                    .foregroundStyle(theme.inkSecondary)
            }
            .padding(22)
            .themeSurface(strong: true)
            .padding(24)
        }
    }
}

/// The app mark: the game-controller glyph on the page's light (Aurora); the
/// name as a misregistered masthead (Riso).
struct AppMark: View {
    @Environment(\.rommioTheme) private var theme
    var size: CGFloat = 30

    var body: some View {
        Group {
            if theme.isRiso {
                DisplayText(text: "Rommio", role: .display, size: size * 3.6, plateOffset: 2, lineLimit: 1)
            } else {
                HStack(spacing: 10) {
                    Image(systemName: "gamecontroller.fill")
                        .font(.system(size: size * 0.5, weight: .semibold))
                        .foregroundStyle(theme.markInk)
                        .frame(width: size, height: size)
                        .background(
                            RoundedRectangle(cornerRadius: size * 0.28, style: .continuous)
                                .fill(LinearGradient(colors: [theme.mark1, theme.mark2], startPoint: .topLeading, endPoint: .bottomTrailing))
                        )
                    Text("Rommio")
                        .themeText(.headline, size: size * 0.7)
                        .foregroundStyle(theme.ink)
                }
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Rommio")
    }
}
