import SwiftUI
import UniformTypeIdentifiers
import Observation
import RommioContract
import RommioFoundation
import RommioPlayerKit

#if canImport(AuthenticationServices) && canImport(UIKit)
import AuthenticationServices
import UIKit

@MainActor
private final class ApplicationPresentationAnchorProvider: NSObject, PresentationAnchorProviding {
    func presentationAnchor() -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first(where: \.isKeyWindow) ?? ASPresentationAnchor()
    }
}
#endif

public struct RommioRootView: View {
    @State private var model: RommioAppModel
    @State private var appearance: AppearanceStore
    @Environment(\.scenePhase) private var scenePhase

    public init(
        services: RommioServices,
        interactiveAuthenticator: InteractiveSessionAuthenticating? = nil,
        appearance: AppearanceStore? = nil
    ) {
        RommioFonts.registerIfNeeded()
        _model = State(initialValue: RommioAppModel(
            services: services,
            interactiveAuthenticator: interactiveAuthenticator
        ))
        _appearance = State(initialValue: appearance ?? AppearanceStore())
    }

    public var body: some View {
        ThemeHost(appearance: appearance) {
            content
        }
    }

    private var content: some View {
        Group {
            switch model.route {
            case .gate:
                LaunchView()
                    .task { await model.bootstrap() }
            case .onboarding(.welcome):
                WelcomeFlow(model: model)
            case let .onboarding(route):
                NavigationStack {
                    OnboardingFlowView(model: model, route: route)
                }
            case let .interactive(context):
                NavigationStack {
                    OnboardingFlowView(model: model, route: context.returnRoute)
                        .overlay {
                            InteractiveAuthOverlay(provider: context.provider)
                        }
                }
                .task(id: context.id) {
                    await model.performInteractiveSessionIfNeeded(context)
                }
            case .app:
                if model.isFirstSync {
                    FirstSyncPage(model: model)
                        .transition(.opacity)
                } else {
                    AuthenticatedShell(model: model)
                        .task { await model.refreshShell(forceRemote: false) }
                        .transition(.opacity)
                }
            }
        }
        .animation(.smooth, value: model.isFirstSync)
        .environment(\.artworkPipeline, model.artworkPipeline)
        .onChange(of: scenePhase) { _, phase in
            Task {
                await model.playerSceneChanged(active: phase == .active)
                if phase == .active { await model.resumeDownloads() }
            }
        }
        .rommioPlayerPresentation(model: model, bindable: bindable)
        // Pages that tint the status bar over their art stand down while a game plays.
        .environment(\.playerCoversApp, model.activePlayer != nil)
        .environment(\.launchingRomID, model.launchingRomID)
        // Launch choice: Start options from Play and Game, and a launch that would
        // otherwise stop inside the player to ask (the player opens after it closes).
        .sheet(item: bindable.launchChoice, onDismiss: { Task { await model.launchChoiceClosed() } }) { presentation in
            LaunchChoiceSheet(
                choice: presentation.choice,
                start: { model.confirmLaunchChoice($0) },
                dismiss: { model.launchChoice = nil },
                retry: presentation.prepared?.cloudMessage == nil ? nil : { await model.retryLaunchCloudCheck() }
            )
            // Medium fits the two options; a note (why the launch asked) needs the full height.
            .presentationDetents(presentation.choice.note == nil ? [.medium, .large] : [.large])
            .presentationDragIndicator(.visible)
        }
        // Once after an unzipped game closes, and from the Game page's Delete ZIP.
        .alert("Delete the ZIP?", isPresented: Binding(get: { model.archiveDeletionOffer != nil },
                                                       set: { if !$0 { model.archiveDeletionOffer = nil } }),
               presenting: model.archiveDeletionOffer) { offer in
            Button("Delete ZIP", role: .destructive) { Task { await model.deleteArchive(offer) } }
            Button("Keep ZIP", role: .cancel) { Task { await model.keepArchive(offer) } }
        } message: { offer in
            Text("\(offer.title) is unzipped on this device and doesn't need its \(PlayFormat.bytes(offer.archiveBytes)) ZIP to play. You can download the ZIP again from your server.")
        }
        .overlay(alignment: .bottom) {
            // Bottom placement keeps navigation (Back, vertical bars) uncovered.
            if model.onboardingRoute == nil, let message = model.errorMessage ?? model.noticeMessage {
                Banner(message: message, isError: model.errorMessage != nil)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 88)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.snappy, value: model.errorMessage)
        .animation(.snappy, value: model.noticeMessage)
        .task(id: model.noticeMessage) {
            guard let message = model.noticeMessage else { return }
            do { try await Task.sleep(for: .seconds(5)) } catch { return }
            if model.noticeMessage == message { model.noticeMessage = nil }
        }
    }

    private var bindable: Bindable<RommioAppModel> {
        Bindable(model)
    }
}

public extension RommioRootView {
    @MainActor
    static func live() throws -> RommioRootView {
        let services = try RommioServices.live()
        #if canImport(AuthenticationServices) && canImport(UIKit)
        let authenticator = WebAuthenticationSessionAuthenticator(
            anchorProvider: ApplicationPresentationAnchorProvider()
        )
        return RommioRootView(services: services, interactiveAuthenticator: authenticator)
        #else
        return RommioRootView(services: services)
        #endif
    }
}

/// Shown while the app decides where to start.
private struct LaunchView: View {
    @Environment(\.rommioTheme) private var theme

    var body: some View {
        VStack(spacing: 14) {
            ProgressView()
            Text("Preparing Rommio")
                .themeText(.secondary)
                .foregroundStyle(theme.inkSecondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background { PageBackground() }
    }
}

extension View {
    @ViewBuilder
    func rommioPlayerPresentation(
        model: RommioAppModel,
        bindable: Bindable<RommioAppModel>
    ) -> some View {
#if os(iOS)
        fullScreenCover(item: bindable.activePlayer) { presentation in
            PlayerExperienceView(feature: presentation)
        }
#else
        sheet(item: bindable.activePlayer) { presentation in
            PlayerExperienceView(feature: presentation)
        }
#endif
    }
}
