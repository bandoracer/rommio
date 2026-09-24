import SwiftUI
import RommioContract
import RommioFoundation

/// First launch: full screen, no tabs. Connect is pushed; the setup code is a sheet.
struct WelcomeFlow: View {
    let model: RommioAppModel
    @State private var showsConnect = false
    @State private var showsSetupCode = false

    var body: some View {
        NavigationStack {
            WelcomePage(
                onConnect: { showsConnect = true },
                onSetupCode: { showsSetupCode = true }
            )
            .navigationDestination(isPresented: $showsConnect) {
                OnboardingFlowView(model: model, route: .serverAccess)
            }
            .sheet(isPresented: $showsSetupCode) {
                OneTouchSetupView(appModel: model)
            }
        }
    }
}

struct WelcomePage: View {
    @Environment(\.rommioTheme) private var theme
    let onConnect: () -> Void
    let onSetupCode: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                WelcomeHero()
                VStack(spacing: 12) {
                    Button(action: onConnect) {
                        HStack {
                            Text("Connect to your server")
                            if theme.isRiso { Spacer(); Image(systemName: "arrow.right") }
                        }
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .accessibilityIdentifier("welcome.connect")
                    Button(action: onSetupCode) {
                        Label("Scan setup code", systemImage: "qrcode.viewfinder")
                    }
                    .buttonStyle(SecondaryButtonStyle(fullWidth: true))
                    .accessibilityIdentifier("welcome.setupCode")
                }
                Text("Rommio doesn't include any games. It plays the library on a RomM server you run.")
                    .themeText(.caption)
                    .foregroundStyle(theme.inkTertiary)
                    .frame(maxWidth: .infinity)
                    .multilineTextAlignment(.center)
            }
            .padding(.horizontal, theme.spacing.gutter + 4)
            .padding(.bottom, 24)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
        }
        .scrollBounceBehavior(.basedOnSize)
        .background { PageBackground(style: .welcome) }
        .hidesNavigationBar()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("screen.welcome")
    }
}

/// The welcome's hero. Aurora: three covers fanned out and glowing, the app mark,
/// then the promise as a display headline. Riso: the name as a masthead over
/// the promise, then the covers as prints pinned over a yellow sun.
private struct WelcomeHero: View {
    @Environment(\.rommioTheme) private var theme
    private let promise = "Every game on your server, right where you left it."
    private let detail = "Browse your RomM library, play offline, and continue on any device."

    var body: some View {
        if theme.isRiso {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text("RomM")
                    Spacer()
                    Text("Your library")
                }
                .themeText(.label, size: 10.5)
                .foregroundStyle(theme.ink)
                .padding(.vertical, 8)
                .overlay(alignment: .bottom) { Rectangle().fill(theme.ink).frame(height: 1.5) }
                .padding(.top, 12)
                AppMark(size: 32)
                Text(promise)
                    .themeText(.body, size: 26)
                    .italic()
                    .foregroundStyle(theme.ink)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader)
                    .padding(.bottom, 12)
                    .overlay(alignment: .bottom) { Rectangle().fill(theme.ink).frame(height: 3) }
                CoverFan().padding(.vertical, 6)
                Text(detail)
                    .themeText(.body, size: 16)
                    .foregroundStyle(theme.ink)
                    .padding(.top, 10)
                    .overlay(alignment: .top) { Rectangle().fill(theme.ink).frame(height: 1) }
            }
        } else {
            VStack(alignment: .leading, spacing: 22) {
                Spacer(minLength: 24)
                CoverFan()
                    .frame(maxWidth: .infinity)
                    .padding(.bottom, 8)
                AppMark()
                VStack(alignment: .leading, spacing: 12) {
                    Text(promise)
                        .themeText(.display, size: 40)
                        .foregroundStyle(theme.ink)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityAddTraits(.isHeader)
                    Text(detail)
                        .themeText(.body)
                        .foregroundStyle(theme.inkSecondary)
                }
            }
        }
    }
}

/// Three stand-in covers. Aurora: soft, glowing cards in the page's light.
/// Riso: halftone prints on white mounts, tilted, over a yellow sun.
private struct CoverFan: View {
    @Environment(\.rommioTheme) private var theme

    var body: some View {
        ZStack {
            if theme.isRiso {
                Circle().fill(theme.accent3)
                    .frame(width: 210, height: 210)
                    .offset(x: 120, y: 20)
                    .blendMode(theme.isDark ? .screen : .multiply)
                plate(dots: theme.accent1, ground: theme.accent1.opacity(0.35)).rotationEffect(.degrees(-8)).offset(x: -104, y: 18)
                plate(dots: theme.accent2, ground: theme.accent1.opacity(0.25)).rotationEffect(.degrees(9)).offset(x: 104, y: 24)
                plate(dots: theme.accent1, ground: theme.accent2).rotationEffect(.degrees(2)).scaleEffect(1.1)
                    .background(Rectangle().fill(theme.ink).offset(x: 5, y: 5).rotationEffect(.degrees(2)).scaleEffect(1.1))
            } else {
                card(colors: [theme.accent1, theme.accent3]).rotationEffect(.degrees(-9)).offset(x: -92, y: 14)
                card(colors: [theme.accent2, theme.accent1]).rotationEffect(.degrees(8)).offset(x: 92, y: 14)
                card(colors: [theme.accent3, theme.accent2]).scaleEffect(1.12)
            }
        }
        .frame(height: theme.isRiso ? 236 : 230)
        .frame(maxWidth: .infinity)
        .clipped()
        .accessibilityHidden(true)
    }

    private func card(colors: [Color]) -> some View {
        RoundedRectangle(cornerRadius: theme.radius.cover, style: .continuous)
            .fill(LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing))
            .overlay {
                Image(systemName: "gamecontroller")
                    .font(.system(size: 30, weight: .regular))
                    .foregroundStyle(theme.onAccent.opacity(0.45))
            }
            .overlay {
                RoundedRectangle(cornerRadius: theme.radius.cover, style: .continuous)
                    .strokeBorder(theme.glassRim.opacity(theme.isDark ? 0.12 : 0.5), lineWidth: 0.5)
            }
            .frame(width: 138, height: 184)
            .background {
                RoundedRectangle(cornerRadius: theme.radius.cover, style: .continuous)
                    .fill(LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing))
                    .blur(radius: 26)
                    .opacity(theme.isDark ? 0.8 : 0.55)
                    .offset(y: 24)
            }
            .shadow(color: .black.opacity(theme.isDark ? 0.5 : 0.18), radius: 20, y: 12)
    }

    private func plate(dots: Color, ground: Color) -> some View {
        ZStack {
            ground
            HalftoneField(dot: dots, pitch: 5, coverage: 0.9)
        }
        .frame(width: 106, height: 136)
        .padding(.horizontal, 6)
        .padding(.top, 6)
        .padding(.bottom, 20)
        .background(theme.surface)
        .overlay { Rectangle().strokeBorder(theme.ink, lineWidth: 1.5) }
    }
}

/// First sync after signing in, then into Play.
struct FirstSyncPage: View {
    @Environment(\.rommioTheme) private var theme
    let model: RommioAppModel

    var body: some View {
        let platforms = model.libraryFeature.platforms
        let games = platforms.reduce(0) { $0 + $1.romCount }
        let collections = model.collectionsFeature.collections.count
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                Spacer(minLength: 24)
                StepHeader(step: "First sync", title: "Getting your library ready", subtitle: model.activeProfile?.label ?? "RomM")
                HStack(spacing: 10) {
                    ProgressView()
                    Text("Fetching platforms, games and collections")
                        .themeText(.secondary)
                        .foregroundStyle(theme.inkSecondary)
                }
                ThemedGroup {
                    syncRow("Platforms", platforms.isEmpty ? nil : "\(platforms.count)")
                    syncRow("Games", games == 0 ? nil : games.formatted())
                    syncRow("Collections", collections == 0 ? nil : "\(collections)")
                }
                Label {
                    Text("Game saves and resume points sync between your devices through your server.")
                        .themeText(.secondary)
                        .foregroundStyle(theme.inkSecondary)
                } icon: {
                    Image(systemName: "arrow.triangle.2.circlepath").foregroundStyle(theme.readableTint)
                }
                Button {
                    model.isFirstSync = false
                } label: {
                    Label("Start playing", systemImage: "play.fill")
                }
                .buttonStyle(PrimaryButtonStyle())
                .accessibilityIdentifier("firstSync.start")
                Text("Artwork keeps arriving in the background.")
                    .themeText(.caption)
                    .foregroundStyle(theme.inkTertiary)
                    .frame(maxWidth: .infinity)
            }
            .padding(.horizontal, theme.spacing.gutter + 4)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
        }
        .background { PageBackground(style: .welcome) }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("screen.firstSync")
    }

    private func syncRow(_ title: String, _ value: String?) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text(title).themeText(theme.isRiso ? .section : .bodyEmphasis, size: theme.isRiso ? 20 : 16).foregroundStyle(theme.ink)
            if theme.isRiso { DotLeader() } else { Spacer(minLength: 8) }
            Text(value ?? "…").themeText(.numeral, size: 15).foregroundStyle(theme.ink)
            Image(systemName: value == nil ? "circle.dotted" : "checkmark.circle.fill")
                .foregroundStyle(value == nil ? theme.inkTertiary : theme.ok)
                .accessibilityHidden(true)
        }
        .accessibilityElement(children: .combine)
    }
}
