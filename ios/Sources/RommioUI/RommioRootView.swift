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
    @Environment(\.scenePhase) private var scenePhase

    public init(
        services: RommioServices,
        interactiveAuthenticator: InteractiveSessionAuthenticating? = nil
    ) {
        _model = State(initialValue: RommioAppModel(
            services: services,
            interactiveAuthenticator: interactiveAuthenticator
        ))
    }

    public var body: some View {
        Group {
            switch model.route {
            case .gate:
                ProgressView("Preparing Rommio")
                    .task { await model.bootstrap() }
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
                AuthenticatedShell(model: model)
                    .task { await model.refreshShell(forceRemote: false) }
            }
        }
        .onChange(of: scenePhase) { _, phase in
            Task {
                await model.playerSceneChanged(active: phase == .active)
                if phase == .active { await model.resumeDownloads() }
            }
        }
        .rommioPlayerPresentation(model: model, bindable: bindable)
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

private struct OnboardingFlowView: View {
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
            .padding(.horizontal, 24)
            .padding(.top, 40)
            .padding(.bottom, 24)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier(route == .login ? "screen.login" : "screen.serverAccess")
        }
        .scrollDismissesKeyboard(.interactively)
        .background(.background)
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

private struct ServerAccessStepView: View {
    @Bindable var model: RommioAppModel
    @State private var showsAccessProfile = false
    @State private var showsOneTouchSetup = false
    @FocusState private var addressFocused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            VStack(alignment: .leading, spacing: 8) {
                Image(systemName: "gamecontroller.fill")
                    .font(.system(size: 36))
                    .foregroundStyle(.tint)
                    .accessibilityHidden(true)
                Text("Rommio")
                    .font(.largeTitle.bold())
                Text("Connect to your RomM server")
                    .foregroundStyle(.secondary)
            }

            VStack(spacing: 12) {
                Button {
                    addressFocused = false
                    showsOneTouchSetup = true
                } label: {
                    Label("One-touch setup", systemImage: "qrcode.viewfinder")
                        .frame(maxWidth: .infinity).padding(.vertical, 8)
                }
                .buttonStyle(.borderedProminent)
                .disabled(model.isBusy)
                .accessibilityIdentifier("serverAccess.oneTouchSetup")
                Text("or connect manually").font(.footnote).foregroundStyle(.secondary)
            }

            VStack(alignment: .leading, spacing: 12) {
                TextField("romm.example.com", text: $model.serverURL)
                    .rommioServerURLFieldTraits { connect() }
                    .focused($addressFocused)
                    .onboardingField()
                    .accessibilityLabel("Server address")
                    .accessibilityIdentifier("serverAccess.serverURL")

                Button {
                    addressFocused = false
                    showsAccessProfile = true
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "lock.shield")
                        Text("Access profile")
                        Spacer()
                        Text(model.isEdgeAuthModeOverridden ? accessProfileLabel(model.selectedEdgeAuthMode) : "Automatic")
                            .foregroundStyle(.secondary)
                        Image(systemName: "chevron.right")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.tertiary)
                    }
                    .onboardingField()
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("serverAccess.accessProfile")

                OnboardingError(message: model.errorMessage)

                Button(action: connect) {
                    OnboardingButtonLabel(title: "Connect", isBusy: model.isBusy)
                }
                .buttonStyle(.borderedProminent)
                .disabled(model.serverURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || model.isBusy)
                .accessibilityIdentifier("serverAccess.connect")
            }
            .disabled(model.isBusy)

            if !model.profiles.isEmpty {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Saved servers").font(.headline)
                    ForEach(model.profiles) { profile in
                        Button {
                            addressFocused = false
                            Task { await model.switchProfile(to: profile) }
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(profile.label).font(.headline)
                                    Text(profile.baseURL.absoluteString)
                                        .font(.footnote)
                                        .foregroundStyle(.secondary)
                                        .lineLimit(2)
                                }
                                Spacer()
                                Image(systemName: "arrow.right.circle")
                                    .foregroundStyle(.tint)
                            }
                            .onboardingField()
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .disabled(model.isBusy)
                    }
                }
            }
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

private struct AccessProfileSheet: View {
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
                    Text("Most servers work automatically. Choose a profile if your server requires extra access credentials.")
                }
                if !automatic && mode == .cloudflareAccessService {
                    Section("Cloudflare service token") {
                        TextField("Client ID", text: $clientID)
                            .rommioUsernameFieldTraits()
                            .accessibilityIdentifier("serverAccess.cloudflareClientID")
                        SecureField("Client secret", text: $clientSecret)
                            .accessibilityIdentifier("serverAccess.cloudflareClientSecret")
                    }
                }
                if !automatic && (mode == .cloudflareAccessSession || mode == .genericCookieSSO) {
                    Section {
                        Text("A browser will open if your server needs you to sign in before connecting.")
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("Access profile")
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

private struct LoginStepView: View {
    @Bindable var model: RommioAppModel
    @FocusState private var focusedField: Field?
    private enum Field { case username, password }

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            VStack(alignment: .leading, spacing: 8) {
                Text("Sign in").font(.largeTitle.bold())
                Text(model.activeProfile?.label ?? "RomM")
                    .font(.headline)
                if let url = model.activeProfile?.baseURL {
                    Text(url.absoluteString)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
            VStack(alignment: .leading, spacing: 12) {
                switch model.effectiveOriginAuthMode {
                case .rommBearerPassword, .rommBasicLegacy:
                    TextField("Username", text: $model.username)
                        .rommioUsernameFieldTraits()
                        .focused($focusedField, equals: .username)
                        .submitLabel(.next)
                        .onSubmit { focusedField = .password }
                        .onboardingField()
                        .accessibilityIdentifier("login.username")
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
                    .buttonStyle(.borderedProminent)
                    .disabled(model.username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || model.password.isEmpty || model.isBusy)
                    .accessibilityIdentifier("login.signIn")
                case .rommOIDCSession:
                    OnboardingError(message: model.errorMessage)
                    Button {
                        model.beginInteractiveOriginLogin()
                    } label: {
                        OnboardingButtonLabel(title: "Continue with SSO", isBusy: model.isBusy)
                    }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("login.continueWithSSO")
                case .none:
                    OnboardingError(message: model.errorMessage)
                    Button(action: signIn) {
                        OnboardingButtonLabel(title: "Continue", isBusy: model.isBusy)
                    }
                    .buttonStyle(.borderedProminent)
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

private struct OnboardingButtonLabel: View {
    let title: String
    let isBusy: Bool
    var body: some View {
        HStack(spacing: 8) {
            if isBusy { ProgressView().tint(.white) }
            Text(isBusy ? "Connecting…" : title).bold()
        }
        .frame(maxWidth: .infinity, minHeight: 32)
        .padding(.vertical, 6)
    }
}

private struct OnboardingError: View {
    let message: String?
    var body: some View {
        if let message {
            Label(message, systemImage: "exclamationmark.circle")
                .font(.callout)
                .foregroundStyle(.red)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("onboarding.error")
        }
    }
}

private func accessProfileLabel(_ mode: EdgeAuthMode) -> String {
    switch mode {
    case .none: "Direct"
    case .cloudflareAccessSession: "Cloudflare login"
    case .cloudflareAccessService: "Service token"
    case .genericCookieSSO: "Browser login"
    }
}

private extension View {
    func onboardingField() -> some View {
        self
            .padding(16)
            .frame(minHeight: 52)
            .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 14))
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
}

private extension View {
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

private struct InteractiveAuthOverlay: View {
    let provider: InteractiveSessionProvider

    var body: some View {
        ZStack {
            Color.black.opacity(0.18)
                .ignoresSafeArea()
            VStack(spacing: 12) {
                ProgressView()
                Text(provider == .edge ? "Completing protected login…" : "Completing RomM sign-in…")
                    .font(.headline)
                Text(provider == .edge
                     ? "Finish signing in in your browser. Rommio will check the connection automatically."
                     : "Finish the system authentication sheet, then the app will validate the RomM session.")
                    .multilineTextAlignment(.center)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .padding(20)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18))
            .padding(24)
        }
    }
}

private struct CardSection<Content: View>: View {
    let title: String
    @ViewBuilder let content: () -> Content

    var body: some View {
        GroupBox {
            VStack(alignment: .leading, spacing: 12) {
                content()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        } label: {
            Text(title)
                .font(.headline)
        }
    }
}

private struct AuthenticatedShell: View {
    let model: RommioAppModel

    var body: some View {
        // Sidebar-adaptable tabs become the vertical bar on iPhone Duo's outer
        // display and can expand into a sidebar on the regular-width inner display.
        TabView(selection: bindable.selectedTab) {
            Tab("Home", systemImage: "house.fill", value: RommioAppModel.ShellTab.home) {
                HomeTab(model: model, feature: model.homeFeature)
            }

            Tab("Library", systemImage: "books.vertical.fill", value: RommioAppModel.ShellTab.library) {
                LibraryTab(model: model, feature: model.libraryFeature)
            }

            Tab("Collections", systemImage: "square.stack.3d.up.fill", value: RommioAppModel.ShellTab.collections) {
                CollectionsTab(model: model, feature: model.collectionsFeature)
            }

            Tab("Downloads", systemImage: "arrow.down.circle.fill", value: RommioAppModel.ShellTab.downloads) {
                DownloadsTab(model: model, feature: model.downloadsFeature)
            }
            .badge(model.downloadsFeature.queueSummary.runningCount + model.downloadsFeature.queueSummary.queuedCount)

            Tab("Settings", systemImage: "gearshape.fill", value: RommioAppModel.ShellTab.settings) {
                SettingsTab(model: model, feature: model.settingsFeature)
            }
        }
        .tabViewStyle(.sidebarAdaptable)
        // Leading side: the trailing edge holds iPhone Duo's vertical bar.
        .overlay(alignment: .bottomLeading) {
            if model.isRefreshing {
                ProgressView()
                    .padding()
                    .background(.thinMaterial, in: Capsule())
                    .padding()
            }
        }
    }

    private var bindable: Bindable<RommioAppModel> {
        Bindable(model)
    }
}

private func edgeAuthLabel(_ mode: EdgeAuthMode) -> String {
    switch mode {
    case .none:
        "No protected edge access"
    case .cloudflareAccessSession:
        "Cloudflare Access session"
    case .cloudflareAccessService:
        "Cloudflare Access service token"
    case .genericCookieSSO:
        "Generic cookie SSO"
    }
}

private func edgeAuthDescription(_ mode: EdgeAuthMode) -> String {
    switch mode {
    case .none:
        "Use this only when the RomM origin is directly reachable."
    case .cloudflareAccessSession:
        "Carry a Cloudflare browser session into native app requests."
    case .cloudflareAccessService:
        "Send static Cloudflare Access client credentials from the app."
    case .genericCookieSSO:
        "Carry reusable edge cookies from system web authentication."
    }
}

private func originAuthLabel(_ mode: OriginAuthMode) -> String {
    switch mode {
    case .none:
        "None"
    case .rommBearerPassword:
        "Bearer password grant"
    case .rommBasicLegacy:
        "Legacy Basic auth"
    case .rommOIDCSession:
        "OIDC session"
    }
}

private func originAuthMessage(_ mode: OriginAuthMode) -> String {
    switch mode {
    case .rommOIDCSession:
        "This RomM server uses interactive sign-in. Continue below to finish authentication."
    case .rommBasicLegacy:
        "This server falls back to legacy Basic auth. Use the same username and password that work on the web."
    case .rommBearerPassword:
        "Use the same username and password that work in the RomM web app."
    case .none:
        "No separate RomM sign-in mechanism was detected. The app will validate the current session directly."
    }
}

private struct HomeTab: View {
    let model: RommioAppModel
    let feature: HomeFeatureModel
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    var body: some View {
        NavigationStack {
            ScrollView {
                // Regular width (iPhone Duo inner display) shows the summary beside
                // the shelves; compact width leads with what is ready to play.
                if horizontalSizeClass == .regular {
                    HStack(alignment: .top, spacing: 16) {
                        summaryCard
                            .containerRelativeFrame(.horizontal) { width, _ in min(380, width * 0.4) }
                        LazyVStack(alignment: .leading, spacing: 16) {
                            continuePlayingShelf
                            shelves
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 14)
                } else {
                    LazyVStack(alignment: .leading, spacing: 16) {
                        continuePlayingShelf
                        summaryCard
                        shelves
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 14)
                }
            }
            .navigationTitle("Home")
            .accessibilityIdentifier("shell.homeList")
            .task { await feature.refresh(refreshCatalog: false) }
            .refreshable { await model.refreshShell(forceRemote: true) }
        }
    }

    private var summaryCard: some View {
        ShellFeatureCard(
            eyebrow: "Home",
            title: "Welcome back",
            subtitle: "Jump into the library, monitor transfers, and surface what is ready to play.",
            badge: "Console view"
        ) {
            MetricGrid(
                metrics: [
                    MetricValue(title: "Installed", value: "\(feature.storageSummary.installedGameCount)", systemImage: "square.stack.3d.up.fill"),
                    MetricValue(title: "Storage", value: ByteCountFormatter.string(fromByteCount: feature.storageSummary.totalBytes, countStyle: .file), systemImage: "internaldrive.fill"),
                    MetricValue(title: "Queue", value: "\(feature.queueSummary.queuedCount + feature.queueSummary.runningCount)", systemImage: "arrow.triangle.2.circlepath"),
                    MetricValue(title: "Attention", value: "\(feature.queueSummary.failedCount)", systemImage: "exclamationmark.circle.fill"),
                ]
            )

            QuickActionGrid(actions: [
                QuickActionValue(title: "Library", systemImage: "books.vertical.fill") {
                    model.selectedTab = .library
                },
                QuickActionValue(title: "Queue", systemImage: "arrow.down.circle.fill") {
                    model.selectedTab = .downloads
                },
            ])
        }
    }

    @ViewBuilder
    private var continuePlayingShelf: some View {
        if !feature.snapshot.continuePlaying.isEmpty {
            ShelfSection(title: "Continue playing", meta: "\(feature.snapshot.continuePlaying.count)") {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 14) {
                        ForEach(feature.snapshot.continuePlaying) { rom in
                            NavigationLink {
                                GameDetailView(model: model, rom: rom)
                            } label: {
                                RomPosterTile(rom: rom, artworkURL: model.artworkURL(path: rom.urlCover))
                            }
                            .buttonStyle(.plain)
                            .contextMenu {
                                Button("Resume", systemImage: "play.fill") {
                                    Task { await model.quickResume(rom: rom) }
                                }
                            }
                            .overlay(alignment: .topTrailing) {
                                // Outer-display quick resume: one tap back into the
                                // latest snapshot without opening the detail page.
                                Button {
                                    Task { await model.quickResume(rom: rom) }
                                } label: {
                                    Image(systemName: "play.fill")
                                        .font(.headline)
                                        .foregroundStyle(.white)
                                        .frame(width: 44, height: 44)
                                        .background(.black.opacity(0.55), in: Circle())
                                }
                                .buttonStyle(.plain)
                                .padding(8)
                                .accessibilityLabel("Resume \(rom.displayName)")
                                .accessibilityIdentifier("home.quickResume.\(rom.id)")
                            }
                        }
                    }
                    .padding(.horizontal, 2)
                }
            }
        }
    }

    @ViewBuilder
    private var shelves: some View {
        if !feature.snapshot.highlightedCollections.isEmpty {
            ShelfSection(title: "Collection highlights", meta: "\(feature.snapshot.highlightedCollections.count)") {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 14) {
                        ForEach(feature.snapshot.highlightedCollections) { collection in
                            NavigationLink {
                                CollectionDetailView(model: model, collection: collection)
                            } label: {
                                CollectionHighlightTile(
                                    collection: collection,
                                    artworkURL: model.artworkURL(path: collection.pathCoverLarge ?? collection.pathCoverSmall)
                                )
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 2)
                }
            }
        }

        if !feature.snapshot.recentlyAdded.isEmpty {
            ShelfSection(title: "Recently added", meta: "\(feature.snapshot.recentlyAdded.count)") {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 14) {
                        ForEach(feature.snapshot.recentlyAdded) { rom in
                            NavigationLink {
                                GameDetailView(model: model, rom: rom)
                            } label: {
                                RomPosterTile(rom: rom, artworkURL: model.artworkURL(path: rom.urlCover))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 2)
                }
            }
        }

        if !feature.activeDownloads.isEmpty {
            ShellCompactPanel(
                eyebrow: "Downloads",
                title: "Transfer queue",
                subtitle: "Running, queued, and failed downloads stay visible here and in the dedicated manager.",
                badge: "\(feature.activeDownloads.count) active"
            ) {
                ForEach(Array(feature.activeDownloads.prefix(3)), id: \.id) { record in
                    DownloadSummaryRow(record: record)
                }
            }
        }

        if let staleMessage = feature.staleMessage, feature.hasRenderableContent {
            ShellCompactPanel(
                eyebrow: "Refresh",
                title: "Cached content preserved",
                subtitle: staleMessage,
                badge: "Stale"
            ) {
                EmptyView()
            }
        }

        if !feature.hasRenderableContent && !feature.isLoading {
            ContentUnavailableView(
                "Your dashboard is clear",
                systemImage: "sparkles",
                description: Text("As you install games, queue downloads, and build out collections, Home will surface the next best actions here.")
            )
        }
    }
}

private struct LibraryTab: View {
    let model: RommioAppModel
    let feature: LibraryFeatureModel

    @State private var selectedPlatform: PlatformDTO?
    @State private var columnVisibility: NavigationSplitViewVisibility = .all
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    var body: some View {
        // Platforms beside their games on the iPhone Duo inner display; a single
        // stack on the outer display. Game pages push within the games column.
        NavigationSplitView(columnVisibility: $columnVisibility) {
            platformList
                .navigationTitle("Library")
        } detail: {
            NavigationStack {
                if let platform = selectedPlatform {
                    PlatformDetailView(model: model, platform: platform)
                        .id(platform.id)
                } else {
                    ContentUnavailableView("Choose a platform", systemImage: "square.stack.3d.up")
                }
            }
        }
        .navigationSplitViewStyle(.balanced)
        .onChange(of: feature.sections.first?.platforms.first?.id, initial: true) { _, _ in
            // Regular width shows both columns, so start with something selected.
            if horizontalSizeClass == .regular, selectedPlatform == nil {
                selectedPlatform = feature.sections.first?.platforms.first
            }
        }
    }

    private var platformList: some View {
        List(selection: $selectedPlatform) {
            if feature.sections.isEmpty {
                ContentUnavailableView(
                    "No platforms available",
                    systemImage: "square.stack.3d.up.slash",
                    description: Text("Hydrate the active profile to browse touch-ready, controller-first, and unsupported platform families.")
                )
            } else {
                ForEach(feature.sections) { section in
                    Section {
                        ForEach(section.platforms) { platform in
                            VStack(alignment: .leading, spacing: 6) {
                                HStack(alignment: .center) {
                                    Text(platform.name)
                                    Spacer()
                                    SupportTierChip(tier: section.supportTier)
                                }
                                Text("\(platform.romCount) game\(platform.romCount == 1 ? "" : "s")")
                                    .foregroundStyle(.secondary)
                                    .font(.footnote)
                            }
                            .tag(platform)
                        }
                    } header: {
                        SectionHeaderBlock(title: section.title, supportingText: section.supportingText)
                    }
                }
            }

            if let staleMessage = feature.staleMessage, !feature.sections.isEmpty {
                Section {
                    Text(staleMessage)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } header: {
                    SectionHeaderBlock(
                        title: "Cached content preserved",
                        supportingText: "The cached library stays visible while the latest refresh remains stale or offline."
                    )
                }
            }
        }
        .task { await feature.refresh(forceRemote: false) }
        .refreshable { await feature.refresh(forceRemote: true) }
    }
}

private struct CollectionsTab: View {
    let model: RommioAppModel
    let feature: CollectionsFeatureModel

    var body: some View {
        NavigationStack {
            List {
                if feature.sections.isEmpty {
                    ContentUnavailableView(
                        "No collections available",
                        systemImage: "square.grid.2x2",
                        description: Text("Collections are grouped by type once the active profile has hydrated collection metadata.")
                    )
                } else {
                    ForEach(feature.sections) { section in
                        Section {
                            ForEach(section.collections) { collection in
                                NavigationLink {
                                    CollectionDetailView(model: model, collection: collection)
                                } label: {
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(collection.name)
                                        Text(section.title)
                                            .foregroundStyle(.secondary)
                                            .font(.footnote)
                                    }
                                }
                            }
                        } header: {
                            SectionHeaderBlock(title: section.title, supportingText: section.supportingText)
                        }
                    }
                }

                if let staleMessage = feature.staleMessage, !feature.sections.isEmpty {
                    Section {
                        Text(staleMessage)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    } header: {
                        SectionHeaderBlock(
                            title: "Cached collections preserved",
                            supportingText: "The grouped collection list remains available even when the latest refresh cannot complete."
                        )
                    }
                }
            }
            .navigationTitle("Collections")
            .task { await feature.refresh(forceRemote: false) }
            .refreshable { await feature.refresh(forceRemote: true) }
        }
    }
}

private struct DownloadsTab: View {
    let model: RommioAppModel
    let feature: DownloadsFeatureModel

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ShellCompactPanel(
                        eyebrow: "Downloads",
                        title: "Download manager",
                        subtitle: feature.offlineState.connectivity == .offline
                            ? "Transfers stay listed offline until connectivity returns."
                            : "Transfers run one at a time and survive relaunches.",
                        badge: "\(feature.trackedCount) tracked"
                    ) {
                        MetricGrid(
                            metrics: [
                                MetricValue(title: "Running", value: "\(feature.queueSummary.runningCount)", systemImage: "arrow.down.circle.fill"),
                                MetricValue(title: "Queued", value: "\(feature.queueSummary.queuedCount)", systemImage: "clock.arrow.circlepath"),
                                MetricValue(title: "Attention", value: "\(feature.records.count { $0.status == .failed || $0.status == .canceled })", systemImage: "exclamationmark.circle.fill"),
                                MetricValue(title: "Completed", value: "\(feature.queueSummary.completedCount)", systemImage: "checkmark.circle.fill"),
                            ],
                            columnCount: 4
                        )
                    }
                }

                if feature.queueSummary.offlineQueuedCount > 0 {
                    Section("Offline queue") {
                        Label(
                            "\(feature.queueSummary.offlineQueuedCount) download\(feature.queueSummary.offlineQueuedCount == 1 ? "" : "s") waiting for connectivity.",
                            systemImage: "wifi.slash"
                        )
                        .foregroundStyle(.secondary)
                    }
                }

                if feature.records.isEmpty {
                    ContentUnavailableView(
                        "No downloads queued",
                        systemImage: "arrow.down.circle",
                        description: Text("Queue a ROM from any game detail screen.")
                    )
                } else {
                    if !feature.activeRecords.isEmpty {
                        Section {
                            ForEach(feature.activeRecords, id: \.id) { record in
                                DownloadRecordPanel(
                                    record: record,
                                    onDownloadNow: (record.status == .queued || record.status == .failed || record.status == .canceled) ? {
                                        _ = Task<Void, Never> { await feature.downloadNow(recordID: record.id) }
                                    } : nil,
                                    onRetry: (record.status == .failed || record.status == .canceled) ? {
                                        _ = Task<Void, Never> { await feature.retry(recordID: record.id) }
                                    } : nil,
                                    onCancel: (record.status == .queued || record.status == .running) ? {
                                        _ = Task<Void, Never> { await feature.cancel(recordID: record.id) }
                                    } : nil,
                                    onDeleteLocal: record.localPath != nil ? {
                                        _ = Task<Void, Never> { await feature.deleteLocalContent(recordID: record.id) }
                                    } : nil
                                )
                            }
                        } header: {
                            SectionHeaderBlock(
                                title: "Active and queued",
                                supportingText: "Queued and active transfers stay cancelable, and queued work can be promoted back into immediate download."
                            )
                        }
                    }

                    if !feature.recentRecords.isEmpty {
                        Section {
                            ForEach(feature.recentRecords, id: \.id) { record in
                                DownloadRecordPanel(
                                    record: record,
                                    onDownloadNow: (record.status == .queued || record.status == .failed || record.status == .canceled) ? {
                                        _ = Task<Void, Never> { await feature.downloadNow(recordID: record.id) }
                                    } : nil,
                                    onRetry: (record.status == .failed || record.status == .canceled) ? {
                                        _ = Task<Void, Never> { await feature.retry(recordID: record.id) }
                                    } : nil,
                                    onCancel: nil,
                                    onDeleteLocal: record.localPath != nil ? {
                                        _ = Task<Void, Never> { await feature.deleteLocalContent(recordID: record.id) }
                                    } : nil
                                )
                            }
                        } header: {
                            SectionHeaderBlock(
                                title: "Recent activity",
                                supportingText: "Completed, failed, and canceled records remain manageable after the transfer ends."
                            )
                        }
                    }
                }

                if let staleMessage = feature.staleMessage, !feature.records.isEmpty {
                    Section {
                        Text(staleMessage)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    } header: {
                        SectionHeaderBlock(
                            title: "Cached content preserved",
                            supportingText: "Tracked download history stays visible while the latest refresh remains stale or offline."
                        )
                    }
                }

                Section {
                    let summary = feature.runtimeSummary
                    LabeledContent("Shipped in build", value: String(summary.shippedCount))
                    LabeledContent("Provisioned", value: String(summary.provisionedCount))
                    LabeledContent("Needs attention", value: String(summary.failedCount))
                    LabeledContent("Blocked by validation", value: String(summary.blockedCount))
                } header: {
                    SectionHeaderBlock(
                        title: "Runtime inventory",
                        supportingText: "Bundled-core inventory remains visible here, but it should not displace the download manager workflow."
                    )
                }
            }
            .navigationTitle("Downloads")
            .task { await feature.refresh() }
            .refreshable { await feature.refresh() }
        }
    }
}

private struct SettingsTab: View {
    let model: RommioAppModel
    let feature: SettingsFeatureModel
    @State private var selectingBIOS = false
    @State private var biosMessage: String?
    @State private var profilePendingRemoval: ServerProfile?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ShellFeatureCard(
                        eyebrow: "Account",
                        title: feature.activeProfile?.label ?? "No active server",
                        subtitle: feature.activeProfile?.baseURL.absoluteString ?? "Configure a server profile to keep using Rommio.",
                        badge: feature.activeProfile == nil ? "Needs setup" : "Active"
                    ) {
                        MetricGrid(
                            metrics: [
                                MetricValue(title: "Games", value: "\(feature.storageSummary.installedGameCount)", systemImage: "square.stack.3d.up.fill"),
                                MetricValue(title: "Files", value: "\(feature.storageSummary.installedFileCount)", systemImage: "doc.on.doc.fill"),
                                MetricValue(title: "Storage", value: ByteCountFormatter.string(fromByteCount: feature.storageSummary.totalBytes, countStyle: .file), systemImage: "internaldrive.fill"),
                                MetricValue(title: "Profiles", value: "\(feature.profiles.count)", systemImage: "person.2.fill"),
                            ]
                        )
                    }
                }

                Section {
                    ShellCompactPanel(
                        eyebrow: "Offline",
                        title: feature.offlineState.connectivity == .offline
                            ? "Offline mode active"
                            : (feature.offlineState.catalogReady ? "Online and ready" : "Online, catalog syncing"),
                        subtitle: feature.offlineState.catalogReady
                            ? "This profile is hydrated and can browse offline with cached media."
                            : "The active profile is still hydrating. Cached content remains visible while sync continues.",
                        badge: feature.offlineState.catalogReady ? "Ready" : "Syncing"
                    ) {
                        LabeledContent("Connectivity", value: feature.offlineState.connectivity.rawValue.capitalized)
                        LabeledContent("Hydration", value: feature.offlineState.catalogReady ? "Offline ready" : "Hydrating")
                        LabeledContent("Cache", value: ByteCountFormatter.string(fromByteCount: feature.storageSummary.cacheBytes, countStyle: .file))
                        if let lastSync = feature.offlineState.lastFullSyncAtEpochMS {
                            LabeledContent("Last catalog sync", value: relativeDate(lastSync))
                        }
                        if let lastMediaSync = feature.offlineState.lastMediaSyncAtEpochMS {
                            LabeledContent("Last media sync", value: relativeDate(lastMediaSync))
                        }
                    }
                }

                Section("Game saves and snapshots") {
                    NavigationLink("Backup and recovery") {
                        ProgressRecoveryView(service: feature.progressService)
                    }
                }

                Section("Account and session") {
                    if let status = feature.activeProfile?.status {
                        LabeledContent("Auth status", value: status.rawValue)
                    }
                    Button("Reconfigure server access") {
                        Task { await feature.reconfigureServerAccess() }
                    }
                    Button("Re-authenticate") {
                        Task { await feature.reauthenticate() }
                    }
                    Button("Sign out", role: .destructive) {
                        Task { await feature.signOut() }
                    }
                }

                if !feature.profiles.isEmpty {
                    Section {
                        ForEach(feature.profiles) { profile in
                            VStack(alignment: .leading, spacing: 10) {
                                HStack {
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(profile.label)
                                            .font(.headline)
                                        Text(profile.baseURL.absoluteString)
                                            .font(.footnote)
                                            .foregroundStyle(.secondary)
                                    }
                                    Spacer()
                                    if profile.id == feature.activeProfile?.id {
                                        StatusBadge(title: "Active", tint: .green)
                                    }
                                }

                                HStack {
                                    if profile.id != feature.activeProfile?.id {
                                        Button("Activate profile") {
                                            Task { await feature.activateProfile(profile) }
                                        }
                                    }
                                    Spacer()
                                    Button("Remove…", role: .destructive) {
                                        profilePendingRemoval = profile
                                    }
                                }
                                .buttonStyle(.borderless)
                            }
                            .padding(.vertical, 4)
                        }
                    } header: {
                        SectionHeaderBlock(
                            title: "Profiles",
                            supportingText: "Switch between saved RomM servers without re-entering every connection detail."
                        )
                    }
                }

                Section("Controls and player preferences") {
                    Toggle("Show touch controls", isOn: Binding(
                        get: { feature.preferences.touchControlsEnabled },
                        set: { value in Task { await feature.setTouchControlsEnabled(value) } }
                    ))
                    Toggle("Auto-hide on controller", isOn: Binding(
                        get: { feature.preferences.autoHideTouchOnController },
                        set: { value in Task { await feature.setAutoHideTouchOnController(value) } }
                    ))
                    Toggle("Rumble to device", isOn: Binding(
                        get: { feature.preferences.rumbleToDeviceEnabled },
                        set: { value in Task { await feature.setRumbleToDeviceEnabled(value) } }
                    ))
                    Toggle("OLED black mode", isOn: Binding(
                        get: { feature.preferences.oledBlackModeEnabled },
                        set: { value in Task { await feature.setOLEDBlackModeEnabled(value) } }
                    ))
                    Toggle("Console colors", isOn: Binding(
                        get: { feature.preferences.consoleColorsEnabled },
                        set: { value in Task { await feature.setConsoleColorsEnabled(value) } }
                    ))
                }

                Section("Storage and cache") {
                    LabeledContent("Installed games", value: "\(feature.storageSummary.installedGameCount)")
                    LabeledContent("Installed files", value: "\(feature.storageSummary.installedFileCount)")
                    LabeledContent("Library footprint", value: ByteCountFormatter.string(fromByteCount: feature.storageSummary.totalBytes, countStyle: .file))
                    LabeledContent("Catalog cache", value: ByteCountFormatter.string(fromByteCount: feature.storageSummary.cacheBytes, countStyle: .file))
                }

                if let staleMessage = feature.staleMessage,
                   feature.activeProfile != nil || !feature.profiles.isEmpty {
                    Section {
                        Text(staleMessage)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    } header: {
                        SectionHeaderBlock(
                            title: "Cached content preserved",
                            supportingText: "Profile, storage, and runtime details remain visible while the latest refresh stays stale or offline."
                        )
                    }
                }

                Section("Console BIOS files") {
                    Button("Import BIOS files") { selectingBIOS = true }
                    Text("PlayStation requires scph5501.bin. Sega CD requires the US, Europe, and Japan BIOS files. Import your own copies; Rommio verifies and names them automatically.")
                        .font(.footnote).foregroundStyle(.secondary)
                    if let biosMessage { Text(biosMessage).font(.footnote) }
                }

                Section("Emulators") {
                    ForEach(feature.runtimeInventory.filter(\.isShippedInBuild), id: \.id) { entry in
                        RuntimeInventoryRow(entry: entry)
                    }
                    let unavailable = feature.runtimeInventory.filter { !$0.isShippedInBuild }
                    if !unavailable.isEmpty {
                        DisclosureGroup("Not included in this build (\(unavailable.count))") {
                            ForEach(unavailable, id: \.id) { entry in
                                RuntimeInventoryRow(entry: entry)
                            }
                        }
                    }
                }
            }
            .navigationTitle("Settings")
            .confirmationDialog(
                "Remove \(profilePendingRemoval?.label ?? "profile")?",
                isPresented: Binding(get: { profilePendingRemoval != nil }, set: { if !$0 { profilePendingRemoval = nil } }),
                titleVisibility: .visible
            ) {
                Button("Remove profile", role: .destructive) {
                    if let profile = profilePendingRemoval {
                        Task { await feature.deleteProfile(profile) }
                    }
                    profilePendingRemoval = nil
                }
            } message: {
                Text(profilePendingRemoval?.id == feature.activeProfile?.id
                    ? "This is the active server. You'll need to set up server access again."
                    : "Saved connection details for this server will be removed.")
            }
            .accessibilityIdentifier("shell.settingsList")
            .fileImporter(isPresented: $selectingBIOS, allowedContentTypes: [.data], allowsMultipleSelection: true) { result in
                do {
                    let urls = try result.get()
                    var messages: [String] = []
                    for url in urls {
                        do { messages.append("Imported " + (try model.importBIOS(url))) }
                        catch { messages.append("\(url.lastPathComponent): \(error.localizedDescription)") }
                    }
                    biosMessage = messages.joined(separator: "\n")
                    Task { await feature.refresh() }
                } catch { biosMessage = error.localizedDescription }
            }
            .task { await feature.refresh() }
        }
    }

    private func relativeDate(_ epochMS: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(epochMS) / 1000)
        return RelativeDateTimeFormatter().localizedString(for: date, relativeTo: .now)
    }
}

private struct PlatformDetailView: View {
    let model: RommioAppModel
    let platform: PlatformDTO

    @State private var feature: PlatformDetailFeatureModel

    init(model: RommioAppModel, platform: PlatformDTO) {
        self.model = model
        self.platform = platform
        _feature = State(initialValue: model.makePlatformDetailFeature(platform: platform))
    }

    var body: some View {
        List {
            if feature.sections.isEmpty {
                ContentUnavailableView(
                    "No games surfaced yet",
                    systemImage: "gamecontroller.slash",
                    description: Text("This platform is visible in RomM, but it does not currently expose any ROMs to the iOS client.")
                )
            } else {
                ForEach(feature.sections) { section in
                    Section {
                        ForEach(section.roms) { rom in
                            NavigationLink {
                                GameDetailView(model: model, rom: rom)
                            } label: {
                                RomRow(rom: rom, artworkURL: model.artworkURL(path: rom.urlCover))
                            }
                        }
                    } header: {
                        SectionHeaderBlock(title: section.title, supportingText: section.supportingText)
                    }
                }
            }

            if let staleMessage = feature.staleMessage, !feature.sections.isEmpty {
                Section {
                    Text(staleMessage)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } header: {
                    SectionHeaderBlock(
                        title: "Cached content preserved",
                        supportingText: "Cached platform entries stay available while the latest refresh is stale or offline."
                    )
                }
            }
        }
        .navigationTitle(platform.name)
        .task { await feature.refresh(forceRemote: false) }
        .refreshable { await feature.refresh(forceRemote: true) }
    }
}

private struct CollectionDetailView: View {
    let model: RommioAppModel
    let collection: RommCollectionDTO

    @State private var feature: CollectionDetailFeatureModel

    init(model: RommioAppModel, collection: RommCollectionDTO) {
        self.model = model
        self.collection = collection
        _feature = State(initialValue: model.makeCollectionDetailFeature(collection: collection))
    }

    var body: some View {
        List {
            Section {
                if !collection.description.isEmpty {
                    Text(collection.description)
                        .foregroundStyle(.secondary)
                }
                Text("\(collection.romCount) games")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            if feature.sections.isEmpty {
                ContentUnavailableView(
                    "Nothing here yet",
                    systemImage: "shippingbox",
                    description: Text("This collection is available, but it does not currently expose any ROMs to the iOS client.")
                )
            } else {
                ForEach(feature.sections) { section in
                    Section {
                        ForEach(section.roms) { rom in
                            NavigationLink {
                                GameDetailView(model: model, rom: rom)
                            } label: {
                                RomRow(rom: rom, artworkURL: model.artworkURL(path: rom.urlCover))
                            }
                        }
                    } header: {
                        SectionHeaderBlock(title: section.title, supportingText: section.supportingText)
                    }
                }
            }

            if let staleMessage = feature.staleMessage, !feature.sections.isEmpty {
                Section {
                    Text(staleMessage)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } header: {
                    SectionHeaderBlock(
                        title: "Cached content preserved",
                        supportingText: "Cached collection entries remain available while a background refresh is stale or unavailable."
                    )
                }
            }
        }
        .navigationTitle(collection.name)
        .task { await feature.refresh(forceRemote: false) }
        .refreshable { await feature.refresh(forceRemote: true) }
    }
}

private struct SectionHeaderBlock: View {
    let title: String
    let supportingText: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
            Text(supportingText)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .textCase(nil)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.top, 2)
    }
}

private struct SupportTierChip: View {
    let tier: EmbeddedSupportTier

    var body: some View {
        Text(tier.title)
            .font(.caption.weight(.semibold))
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(backgroundColor.opacity(0.18), in: Capsule())
            .foregroundStyle(backgroundColor)
    }

    private var backgroundColor: Color {
        switch tier {
        case .touchSupported:
            .green
        case .controllerSupported:
            .blue
        case .unsupported:
            .secondary
        }
    }
}

private struct StatusBadge: View {
    let title: String
    let tint: Color

    var body: some View {
        Text(title)
            .font(.caption.weight(.semibold))
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(tint.opacity(0.16), in: Capsule())
            .foregroundStyle(tint)
    }
}

private struct MetricValue: Identifiable {
    let title: String
    let value: String
    let systemImage: String

    var id: String { title }

    /// "1 Game" rather than "1 Games" for the plural metric labels.
    var singularTitle: String {
        ["Games": "Game", "Files": "File", "Profiles": "Profile"][title] ?? title
    }
}

private struct QuickActionValue: Identifiable {
    let title: String
    let systemImage: String
    let action: @MainActor () -> Void

    var id: String { title }
}

private struct ShellFeatureCard<Content: View>: View {
    let eyebrow: String
    let title: String
    let subtitle: String
    let badge: String
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(eyebrow.uppercased())
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                    Text(title)
                        .font(.title3.weight(.bold))
                    Text(subtitle)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                StatusBadge(title: badge, tint: .blue)
            }
            content()
        }
        .padding(18)
        .background(
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .fill(Color.primary.opacity(0.07))
        )
    }
}

private struct ShellCompactPanel<Content: View>: View {
    let eyebrow: String
    let title: String
    let subtitle: String
    let badge: String
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(eyebrow.uppercased())
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                    Text(title)
                        .font(.headline)
                    Text(subtitle)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                StatusBadge(title: badge, tint: .secondary)
            }
            content()
        }
        .padding(16)
        .background(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .fill(Color.primary.opacity(0.06))
        )
    }
}

private struct MetricGrid: View {
    let metrics: [MetricValue]
    /// Keep this even so tiles divide cleanly around iPhone Duo's fold.
    var columnCount = 2

    var body: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: columnCount), spacing: 10) {
            ForEach(metrics) { metric in
                VStack(alignment: .leading, spacing: 6) {
                    Image(systemName: metric.systemImage)
                        .font(.headline)
                        .foregroundStyle(.blue)
                    Text(metric.value)
                        .font(.headline.weight(.bold))
                    Text(metric.value == "1" ? metric.singularTitle : metric.title)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(12)
                .background(
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(Color.primary.opacity(0.05))
                )
            }
        }
    }
}

private struct QuickActionGrid: View {
    let actions: [QuickActionValue]

    var body: some View {
        HStack(spacing: 10) {
            ForEach(actions) { action in
                Button {
                    action.action()
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: action.systemImage)
                        Text(action.title)
                            .fontWeight(.semibold)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .fill(Color.blue.opacity(0.12))
                    )
                }
                .buttonStyle(.plain)
            }
        }
    }
}

private struct ShelfSection<Content: View>: View {
    let title: String
    let meta: String
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(title)
                    .font(.headline)
                Spacer()
                Text(meta)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
            }
            content()
        }
    }
}

private struct RomPosterTile: View {
    let rom: RomDTO
    let artworkURL: URL?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            AsyncImage(url: artworkURL) { image in
                image.resizable().scaledToFill()
            } placeholder: {
                RoundedRectangle(cornerRadius: 18)
                    .fill(.quaternary)
            }
            .frame(width: 168, height: 228)
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))

            Text(rom.displayName)
                .font(.headline)
                .lineLimit(2)
            Text(rom.platformName)
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .frame(width: 168, alignment: .leading)
    }
}

private struct CollectionHighlightTile: View {
    let collection: RommCollectionDTO
    let artworkURL: URL?

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            AsyncImage(url: artworkURL) { image in
                image.resizable().scaledToFill()
            } placeholder: {
                RoundedRectangle(cornerRadius: 20)
                    .fill(.quaternary)
            }
            .frame(width: 236, height: 144)
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))

            LinearGradient(
                colors: [Color.black.opacity(0.0), Color.black.opacity(0.72)],
                startPoint: .top,
                endPoint: .bottom
            )
            .frame(width: 236, height: 144)
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))

            VStack(alignment: .leading, spacing: 4) {
                Text(collection.name)
                    .font(.headline.weight(.bold))
                    .foregroundStyle(.white)
                    .lineLimit(2)
                Text("\(collection.romCount) games")
                    .font(.footnote)
                    .foregroundStyle(.white.opacity(0.85))
            }
            .padding(14)
        }
    }
}

private struct DownloadSummaryRow: View {
    let record: DownloadRecord

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(record.romName)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                Text(record.status.rawValue.lowercased().capitalized)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            if record.status == .running || record.status == .queued {
                Text("\(record.progressPercent)%")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
            }
        }
    }
}

private struct DownloadRecordPanel: View {
    let record: DownloadRecord
    let onDownloadNow: (() -> Void)?
    let onRetry: (() -> Void)?
    let onCancel: (() -> Void)?
    let onDeleteLocal: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(record.romName)
                        .font(.headline)
                    Text(record.fileName)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                StatusBadge(title: record.status.rawValue.lowercased().capitalized, tint: tint)
            }

            ProgressView(value: Double(record.progressPercent), total: 100)

            if let lastError = record.lastError, !lastError.isEmpty, record.status == .failed {
                Text(lastError)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            HStack {
                if let onDownloadNow,
                   record.status == .queued || record.status == .failed || record.status == .canceled {
                    Button("Download now", action: onDownloadNow)
                }
                if let onRetry, record.status == .failed || record.status == .canceled {
                    Button("Retry", action: onRetry)
                }
                if let onCancel, record.status == .queued || record.status == .running {
                    Button("Cancel", role: .destructive, action: onCancel)
                }
                if let onDeleteLocal, record.localPath != nil {
                    Button("Delete local", role: .destructive, action: onDeleteLocal)
                }
                Spacer()
                if let localPath = record.localPath {
                    Text(URL(fileURLWithPath: localPath).lastPathComponent)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(.vertical, 4)
    }

    private var tint: Color {
        switch record.status {
        case .completed:
            .green
        case .failed:
            .orange
        case .running:
            .blue
        case .queued:
            .secondary
        case .canceled:
            .secondary
        }
    }
}

private struct GameDetailView: View {
    let model: RommioAppModel
    @State private var feature: GameDetailFeatureModel

    init(model: RommioAppModel, rom: RomDTO) {
        self.model = model
        _feature = State(initialValue: model.makeGameDetailFeature(rom: rom))
    }

    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    var body: some View {
        ScrollView {
            // Regular width splits artwork and details from actions and files so
            // neither column is stretched across iPhone Duo's fold.
            if horizontalSizeClass == .regular {
                HStack(alignment: .top, spacing: 18) {
                    VStack(alignment: .leading, spacing: 18) {
                        heroCard
                        stateSummaryCard
                    }
                    VStack(alignment: .leading, spacing: 18) {
                        actionCards
                    }
                }
                .padding(16)
            } else {
                LazyVStack(alignment: .leading, spacing: 18) {
                    heroCard
                    actionCards
                    stateSummaryCard
                }
                .padding(16)
            }
        }
        .toolbar {
            if let primary = feature.actionDeckPresentation?.primary,
               let fileID = feature.selectedFileState?.file.id,
               primary.kind == .play || primary.kind == .downloadNow {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        Task { await feature.perform(primary, fileID: fileID) }
                    } label: {
                        Label(primary.title, systemImage: primary.kind == .play ? "play.fill" : "arrow.down.circle")
                    }
                    .disabled(!primary.enabled)
                    .accessibilityIdentifier("gameDetail.toolbar.\(primary.kind.rawValue)")
                }
            }
        }
        .navigationTitle(feature.rom.displayName)
        .modifier(GameDetailInlineNavigationTitle())
        .task { await feature.refresh(forceRemote: false) }
        .task { await feature.monitorDownloads() }
        .onChange(of: model.activePlayer?.id) { _, playerID in
            if playerID == nil { Task { await feature.refresh(forceRemote: false) } }
        }
        .refreshable { await feature.refresh(forceRemote: true) }
        .sheet(isPresented: Binding(
            get: { feature.stateBrowserPresented },
            set: { feature.stateBrowserPresented = $0 }
        )) {
            StateBrowserView(
                browser: feature.stateBrowser,
                onUseResume: { Task { await feature.playFromResume() } },
                onLoadState: { state in
                    Task { await feature.playFromState(state) }
                },
                onDeleteState: { state in
                    Task { await feature.deleteState(state) }
                },
                onClose: { feature.stateBrowserPresented = false }
            )
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
            .accessibilityIdentifier("gameDetail.stateBrowser.sheet")
        }
    }

    private var heroCard: some View {
        GameDetailHeroCard(
            artworkURL: model.artworkURL(path: feature.rom.urlCover),
            rom: feature.rom,
            presentation: feature.heroPresentation,
            supportTier: feature.controlProfile.supportTier
        )
        .accessibilityIdentifier("gameDetail.hero")
    }

    @ViewBuilder
    private var actionCards: some View {
        if let actionDeck = feature.actionDeckPresentation,
           let selectedFileState = feature.selectedFileState {
            GameDetailActionDeckCard(
                presentation: actionDeck,
                fileID: selectedFileState.file.id,
                onAction: { action in
                    Task { await feature.perform(action, fileID: selectedFileState.file.id) }
                }
            )
        }

        if feature.showsSegmentedFileSelection {
            VStack(alignment: .leading, spacing: 10) {
                SectionHeaderBlock(
                    title: "Files",
                    supportingText: "Choose which file should drive downloads, runtime checks, and play."
                )
                Picker(
                    "File",
                    selection: Binding(
                        get: { feature.selectedFileState?.file.id ?? feature.fileSelectionOptions.first?.id ?? 0 },
                        set: { selection in
                            Task { await feature.selectFile(selection) }
                        }
                    )
                ) {
                    ForEach(feature.fileSelectionOptions) { option in
                        Text(option.title).tag(option.id)
                    }
                }
                .pickerStyle(.segmented)
                .accessibilityIdentifier("gameDetail.filePicker")
            }
        }

        if let selectedFile = feature.selectedFilePresentation,
           let selectedFileState = feature.selectedFileState {
            GameDetailSelectedFileCard(
                presentation: selectedFile,
                badgeTint: playabilityColor(for: selectedFileState.controlProfile, resolution: selectedFileState.resolution)
            )
        }

        if let preserved = feature.preservedContentPresentation {
            ShellCompactPanel(
                eyebrow: "Offline and stale state",
                title: preserved.title,
                subtitle: "Installed files and cached metadata remain manageable even when the latest refresh cannot complete.",
                badge: "Cached"
            ) {
                Text(preserved.message)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder
    private var stateSummaryCard: some View {
        if let stateSummary = feature.stateSummaryPresentation {
            GameDetailStateSummaryCard(
                presentation: stateSummary,
                onBrowseStates: {
                    feature.stateBrowserPresented = true
                }
            )
        }
    }

    private func color(for capability: PlayerCapability) -> Color {
        switch capability {
        case .ready:
            .green
        case .missingCore, .missingBIOS:
            .orange
        case .unsupported:
            .secondary
        }
    }

    private func playabilityLabel(for profile: PlatformControlProfile, resolution: CoreResolution) -> String {
        resolution.capabilityLabel ?? profile.supportTier.title
    }

    private func playabilityColor(for profile: PlatformControlProfile, resolution: CoreResolution) -> Color {
        if resolution.capability == .ready || resolution.capabilityLabel == nil {
            switch profile.supportTier {
            case .touchSupported:
                return Color.green
            case .controllerSupported:
                return Color.blue
            case .unsupported:
                return Color.secondary
            }
        }
        return color(for: resolution.capability)
    }
}

private struct GameDetailStateSummaryCard: View {
    let presentation: GameDetailStateSummaryPresentation
    let onBrowseStates: () -> Void

    var body: some View {
        ShellFeatureCard(
            eyebrow: "Resume",
            title: presentation.statusText,
            subtitle: presentation.countSummary,
            badge: "States"
        ) {
            VStack(alignment: .leading, spacing: 10) {
                if let sourceText = presentation.sourceText {
                    Text(sourceText)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                if let updatedText = presentation.updatedText {
                    Text(updatedText)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                if let problemText = presentation.problemText {
                    Text(problemText)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Button("Browse states", action: onBrowseStates)
                    .buttonStyle(.bordered)
            }
        }
    }
}

private struct GameDetailInlineNavigationTitle: ViewModifier {
    func body(content: Content) -> some View {
        #if os(iOS)
        content.navigationBarTitleDisplayMode(.inline)
        #else
        content
        #endif
    }
}

private struct GameDetailHeroCard: View {
    let artworkURL: URL?
    let rom: RomDTO
    let presentation: GameDetailHeroPresentation
    let supportTier: EmbeddedSupportTier

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Group {
                if let artworkURL {
                    AsyncImage(url: artworkURL) { image in
                        image
                            .resizable()
                            .scaledToFill()
                    } placeholder: {
                        RoundedRectangle(cornerRadius: 28)
                            .fill(.quaternary)
                            .overlay { ProgressView() }
                    }
                } else {
                    RoundedRectangle(cornerRadius: 28)
                        .fill(.quaternary)
                        .overlay {
                            Image(systemName: "gamecontroller")
                                .font(.system(size: 40, weight: .medium))
                                .foregroundStyle(.secondary)
                        }
                }
            }
            // Scale with the available height: a fixed 280pt hero filled most of
            // iPhone Duo's 515pt open landscape display.
            .containerRelativeFrame(.vertical) { height, _ in min(280, max(160, height * 0.4)) }
            .frame(maxWidth: .infinity)
            .clipped()
            .overlay {
                LinearGradient(
                    stops: [
                        .init(color: .clear, location: 0.35),
                        .init(color: .black.opacity(0.85), location: 1),
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )
            }
            .overlay(alignment: .bottomLeading) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(rom.displayName)
                        .font(.title.bold())
                        .foregroundStyle(.white)
                        .lineLimit(2)
                        .minimumScaleFactor(0.75)
                    Text(rom.platformName)
                        .font(.subheadline)
                        .foregroundStyle(.white.opacity(0.86))
                }
                .shadow(color: .black.opacity(0.6), radius: 6, y: 1)
                .padding(18)
            }

            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .center, spacing: 8) {
                    SupportTierChip(tier: supportTier)
                    StatusBadge(title: presentation.statusText, tint: heroStatusTint)
                    Spacer()
                }

                if let supportMessage = presentation.supportMessage {
                    Text(supportMessage)
                        .font(.subheadline)
                        .foregroundStyle(.primary)
                }

                if let summary = presentation.summaryText {
                    Text(summary)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(4)
                }

                Text(presentation.fileCountText)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
            }
            .padding(18)
        }
        .background(
            RoundedRectangle(cornerRadius: 28, style: .continuous)
                .fill(Color.primary.opacity(0.06))
        )
        .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
    }

    private var heroStatusTint: Color {
        if presentation.statusText.localizedCaseInsensitiveContains("failed") {
            return .orange
        }
        if presentation.statusText.localizedCaseInsensitiveContains("offline") {
            return .secondary
        }
        if presentation.statusText.localizedCaseInsensitiveContains("installed") ||
            presentation.statusText.localizedCaseInsensitiveContains("downloaded") {
            return .green
        }
        if presentation.statusText.localizedCaseInsensitiveContains("downloading") ||
            presentation.statusText.localizedCaseInsensitiveContains("queued") {
            return .blue
        }
        return .secondary
    }
}

private struct GameDetailActionDeckCard: View {
    let presentation: GameDetailActionDeckPresentation
    let fileID: Int
    let onAction: (GameDetailAction) -> Void

    var body: some View {
        ShellFeatureCard(
            eyebrow: "Play state",
            title: "Action deck",
            subtitle: presentation.subtitle,
            badge: presentation.badge
        ) {
            VStack(alignment: .leading, spacing: 12) {
                if let recommendedCoreName = presentation.recommendedCoreName {
                    Text("Recommended core: \(recommendedCoreName)")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                if let progress = presentation.downloadProgressValue {
                    ProgressView(value: progress, total: 1)
                    if let progressDetailText = presentation.progressDetailText {
                        Text(progressDetailText)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }

                if let errorMessage = presentation.errorMessage, !errorMessage.isEmpty {
                    Text(errorMessage)
                        .font(.footnote)
                        .foregroundStyle(.orange)
                }

                ForEach(presentation.notes, id: \.self) { note in
                    Text(note)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                if let primary = presentation.primary {
                    Button(primary.title) {
                        onAction(primary)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!primary.enabled)
                    .accessibilityIdentifier("gameDetail.primary.\(fileID).\(primary.kind.rawValue)")
                }

                if !presentation.secondary.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 10) {
                            ForEach(presentation.secondary, id: \.kind) { action in
                                Button(action.title) {
                                    onAction(action)
                                }
                                .buttonStyle(.bordered)
                                .disabled(!action.enabled)
                                .accessibilityIdentifier("gameDetail.secondary.\(fileID).\(action.kind.rawValue)")
                            }
                        }
                    }
                }
            }
        }
    }
}

private struct GameDetailSelectedFileCard: View {
    let presentation: GameDetailSelectedFilePresentation
    let badgeTint: Color

    var body: some View {
        ShellCompactPanel(
            eyebrow: "Selected file",
            title: presentation.title,
            subtitle: presentation.metaLine,
            badge: presentation.playabilityLabel
        ) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 8) {
                    StatusBadge(title: presentation.availabilityLabel, tint: availabilityTint)
                    StatusBadge(title: presentation.playabilityLabel, tint: badgeTint)
                }

                if let downloadStatusText = presentation.downloadStatusText {
                    Text(downloadStatusText)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                if let progress = presentation.downloadProgressValue {
                    ProgressView(value: progress, total: 1)
                }

                if let progressDetailText = presentation.progressDetailText {
                    Text(progressDetailText)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                if let readinessMessage = presentation.readinessMessage, !readinessMessage.isEmpty {
                    Text(readinessMessage)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                if let localFileName = presentation.localFileName {
                    Text("Local file: \(localFileName)")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                if let errorMessage = presentation.errorMessage, !errorMessage.isEmpty {
                    Text(errorMessage)
                        .font(.footnote)
                        .foregroundStyle(.orange)
                }
            }
        }
    }

    private var availabilityTint: Color {
        if presentation.availabilityLabel.localizedCaseInsensitiveContains("installed") ||
            presentation.availabilityLabel.localizedCaseInsensitiveContains("complete") {
            return .green
        }
        if presentation.availabilityLabel.localizedCaseInsensitiveContains("failed") {
            return .orange
        }
        if presentation.availabilityLabel.localizedCaseInsensitiveContains("queued") ||
            presentation.availabilityLabel.localizedCaseInsensitiveContains("downloading") {
            return .blue
        }
        return .secondary
    }
}

private extension CoreInventoryEntry {
    var isShippedInBuild: Bool {
        availabilityStatus != .missingBundledCore && availabilityStatus != .unsupportedByCurrentBuild
            && provisioningStatus != .missingCoreNotShipped && provisioningStatus != .unsupported
    }

    var statusLabel: String {
        switch provisioningStatus {
        case .ready: "Ready"
        case .missingCoreInstallable: "Sets up on first play"
        case .installingCore: "Setting up"
        case .failedCoreInstall: "Setup failed"
        case .missingBIOS: "Needs BIOS"
        case .missingCoreNotShipped: "Not included"
        case .unsupported: "Unsupported"
        }
    }

    var interactionProfileLabel: String {
        interactionProfile.rawValue.replacingOccurrences(of: "_", with: " ").capitalized
    }
}

private struct RuntimeInventoryRow: View {
    let entry: CoreInventoryEntry

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.familyName)
                Text("\(entry.runtimeName) · \(entry.interactionProfileLabel)")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Text(entry.statusLabel)
                .font(.caption.weight(.semibold))
                .foregroundStyle(entry.provisioningStatus == .failedCoreInstall ? .orange : .secondary)
        }
    }
}

private struct RomRow: View {
    let rom: RomDTO
    let artworkURL: URL?

    var body: some View {
        HStack(spacing: 12) {
            AsyncImage(url: artworkURL) { image in
                image.resizable().scaledToFill()
            } placeholder: {
                RoundedRectangle(cornerRadius: 10)
                    .fill(.quaternary)
            }
            .frame(width: 56, height: 56)
            .clipShape(RoundedRectangle(cornerRadius: 10))

            VStack(alignment: .leading, spacing: 4) {
                Text(rom.displayName)
                    .font(.headline)
                Text(rom.platformName)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                // List endpoints omit files; only detail responses carry them.
                if !rom.files.isEmpty {
                    Text("\(rom.files.count) file\(rom.files.count == 1 ? "" : "s")")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(.vertical, 2)
    }
}

private struct Banner: View {
    let message: String
    let isError: Bool

    var body: some View {
        Text(message)
            .font(.footnote.weight(.semibold))
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(isError ? Color.red.opacity(0.92) : Color.blue.opacity(0.92), in: Capsule())
            .foregroundStyle(.white)
            .shadow(radius: 10, y: 3)
            .accessibilityIdentifier(isError ? "banner.error" : "banner.notice")
    }
}
