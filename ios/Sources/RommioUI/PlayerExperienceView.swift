import SwiftUI
import RommioFoundation
import RommioPlayerKit

#if canImport(UIKit)
import UIKit

struct PlayerExperienceView: View {
    let feature: PlayerFeatureModel
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.rommioTheme) private var appTheme
    /// On a wide display (iPhone Duo open), Pause, Controls and Save states open
    /// as a floating inspector beside the game instead of a sheet over it
    /// (`PlayerPanelPresentation`); the game stays uncovered.
    @State private var usesSidePanes = false
    @State private var arrangedGameWidth: CGFloat?

    private var sidePaneVisible: Bool {
        usesSidePanes && (feature.pauseSheetPresented || feature.controlsSheetPresented || feature.stateBrowserPresented)
    }

    var body: some View {
        GeometryReader { geometry in
            let insets = geometry.safeAreaInsets
            let size = CGSize(width: geometry.size.width + insets.leading + insets.trailing,
                              height: geometry.size.height + insets.top + insets.bottom)
            let fold = divisionRegion(geometry)
            // An overlay does not enlarge its parent's layout proposal. Keep the
            // native surface and touch chrome in exactly the same window space.
            feature.theme.canvasColor
                .overlay(alignment: .topLeading) {
                    ZStack(alignment: .topLeading) {
                        // The native renderer is transparent outside video; paint the
                        // entire window, including the cutout and home-indicator areas.
                        feature.theme.canvasColor
                        PlayerBackdrop(url: feature.backdropURL,
                                       enabled: feature.controlsState?.preferences.oledBlackModeEnabled != true)
                        PlayerHostContainer { try feature.makeHostController() }
                            .accessibilityIdentifier("player.host")
                        Color.clear.contentShape(Rectangle())
                            .onTapGesture {
                                feature.bumpPrimaryControls()
                                feature.bumpTertiaryControls(wakePrimary: false)
                            }
                        if feature.progressOwner != nil && !feature.hasStarted {
                            ProgressPlayerPanel(feature: feature)
                            // Start failures (such as a snapshot from a different core
                            // build) must be visible, not just leave the panel in place.
                            if let message = feature.errorMessage {
                                PlayerBanner(message: message, isError: true)
                                    .padding(.horizontal, 16)
                                    .padding(.bottom, insets.bottom + 16)
                                    .frame(width: size.width, height: size.height, alignment: .bottom)
                            }
                        } else {
                            PlayerOverlayChrome(feature: feature, size: size, safeAreaInsets: insets,
                                                livePreview: sidePaneVisible && feature.controlsSheetPresented)
                        }
                        if sidePaneVisible {
                            PlayerSidePaneArrangement(size: size, insets: insets, arrangedGameWidth: $arrangedGameWidth) {
                                sidePaneContent
                            }
                        }
                    }
                    .frame(width: size.width, height: size.height)
                    .offset(x: -insets.leading, y: -insets.top)
                }
                .onChange(of: fold, initial: true) { _, region in
                    feature.updateWindowFold(region?.frame, isActive: region?.isActive ?? false)
                }
                .onChange(of: size.width, initial: true) { _, width in
                    usesSidePanes = PlayerPanelPresentation.resolve(regularWidth: horizontalSizeClass == .regular, windowWidth: width) == .sidePane
                }
                .onChange(of: sidePaneVisible ? (arrangedGameWidth.map { size.width - $0 } ?? PlayerPanelPresentation.paneWidth(windowWidth: size.width)) : 0, initial: true) { _, width in
                    feature.updateSidePaneWidth(width)
                }
        }
        .interactiveDismissDisabled()
        .statusBarHidden()
        .persistentSystemOverlays(.hidden)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("player.screen")
        .sheet(isPresented: pauseSheetBinding, onDismiss: {
            if feature.isPaused && !feature.controlsSheetPresented && !feature.stateBrowserPresented && feature.errorMessage == nil {
                Task { await feature.resumeGame() }
            }
        }) {
            PlayerPauseSheet(feature: feature)
                // Tall enough for every action (including Leave game) on
                // iPhone Duo's 515pt open display without scrolling.
                .presentationDetents([.height(430), .large])
                .presentationDragIndicator(.visible)
                .modifier(PlayerSheetBars())
                .accessibilityIdentifier("player.pause.sheet")
        }
        .sheet(isPresented: controlsSheetBinding, onDismiss: resumeAfterPanelDismissal) {
            PlayerControlsSheet(feature: feature)
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
                .modifier(PlayerSheetBars())
                .accessibilityIdentifier("player.controls.sheet")
        }
        .sheet(isPresented: stateBrowserSheetBinding, onDismiss: resumeAfterPanelDismissal) {
            stateBrowserContent
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
                .modifier(PlayerSheetBars())
        }
        .onChange(of: sidePaneVisible) { wasVisible, visible in
            if wasVisible && !visible { resumeAfterPanelDismissal() }
        }
        .onChange(of: feature.pendingRestoreID) { _, value in
            if value != nil { feature.pauseSheetPresented = false; feature.stateBrowserPresented = true }
        }
        .task(id: feature.id) {
            await feature.startIfNeeded()
        }
        .onChange(of: appTheme.identity, initial: true) { _, _ in
            feature.appTheme = appTheme
        }
        // Touch-free controller play shouldn't let the display sleep mid-game.
        .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
    }

    @ViewBuilder
    private var sidePaneContent: some View {
        if feature.controlsSheetPresented {
            PlayerControlsSheet(feature: feature)
        } else if feature.stateBrowserPresented {
            stateBrowserContent
        } else {
            PlayerPauseSheet(feature: feature)
                .accessibilityIdentifier("player.pause.sheet")
        }
    }

    @ViewBuilder
    private var stateBrowserContent: some View {
        if feature.progressOwner != nil {
            ProgressPlayerPanel(feature: feature)
        } else {
            StateBrowserView(
                browser: feature.stateBrowser,
                onUseResume: { Task { await feature.loadResumeState() } },
                onLoadState: { state in
                    Task { await feature.loadBrowsableState(state) }
                },
                onDeleteState: { state in
                    Task { await feature.deleteState(state) }
                },
                onClose: { feature.stateBrowserPresented = false }
            )
            .accessibilityIdentifier("player.stateBrowser.sheet")
        }
    }

    /// iPhone Duo's hinge in window coordinates, from the division reserved
    /// region (iOS 27.1). Inactive regions still mark a flat display's centre.
    private func divisionRegion(_ geometry: GeometryProxy) -> PlayerFoldRegion? {
        if #available(iOS 27.1, *) {
            guard let region = geometry.reservedRegions(kind: .division, options: .includeInactive).first else { return nil }
            let insets = geometry.safeAreaInsets
            return PlayerFoldRegion(frame: region.frame.offsetBy(dx: insets.leading, dy: insets.top), isActive: region.isActive)
        }
        return nil
    }

    private func resumeAfterPanelDismissal() {
        if feature.isPaused && feature.errorMessage == nil {
            Task { await feature.resumeGame() }
        }
    }

    private var pauseSheetBinding: Binding<Bool> {
        Binding(
            get: { feature.pauseSheetPresented && !usesSidePanes },
            set: { feature.pauseSheetPresented = $0 }
        )
    }

    private var controlsSheetBinding: Binding<Bool> {
        Binding(
            get: { feature.controlsSheetPresented && !usesSidePanes },
            set: { feature.controlsSheetPresented = $0 }
        )
    }

    private var stateBrowserSheetBinding: Binding<Bool> {
        Binding(
            get: { feature.stateBrowserPresented && !usesSidePanes },
            set: { feature.stateBrowserPresented = $0 }
        )
    }
}

struct PlayerFoldRegion: Equatable {
    let frame: CGRect
    let isActive: Bool
}

/// Splits the window between the game and a side pane with ArrangementView
/// (iOS 27.1), which sizes both sides around iPhone Duo's fold.
private struct PlayerSidePaneArrangement<Pane: View>: View {
    let size: CGSize
    let insets: EdgeInsets
    @Binding var arrangedGameWidth: CGFloat?
    @ViewBuilder let pane: () -> Pane

    var body: some View {
        Group {
            if #available(iOS 27.1, *) {
                ArrangementView {
                    gamePlaceholder
                } secondary: {
                    paneBody
                }
                .arrangementViewStyle(.split.axes(.horizontal))
            } else {
                HStack(spacing: 0) {
                    gamePlaceholder
                    paneBody.frame(width: PlayerPanelPresentation.paneWidth(windowWidth: size.width))
                }
            }
        }
        .frame(width: size.width, height: size.height)
        .onDisappear { arrangedGameWidth = nil }
    }

    private var gamePlaceholder: some View {
        Color.clear
            .allowsHitTesting(false)
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { arrangedGameWidth = $0 }
    }

    private var paneBody: some View {
        pane()
            .padding(.top, insets.top)
            .padding(.bottom, insets.bottom)
            .padding(.trailing, insets.trailing)
            .background(.regularMaterial)
            .accessibilityIdentifier("player.sidePane")
    }
}

/// Player sheets keep standard horizontal bars; iPhone Duo's vertical bars
/// would crowd their control-heavy forms.
private struct PlayerSheetBars: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 27.1, *) {
            content.toolbarVerticalBehavior(.disabled)
        } else {
            content
        }
    }
}

/// Aurora bias lighting: the game's art, blurred and brightened, spills into the
/// rails behind the controls. Riso prints on black stock, so there is none.
private struct PlayerBackdrop: View {
    @Environment(\.rommioTheme) private var theme
    let url: URL?
    let enabled: Bool

    var body: some View {
        // The clear base takes the proposed (window) size; an aspect-filled image
        // on its own would grow the player's stage to the artwork's shape.
        Color.clear
            .overlay {
                if enabled, theme.isGlass, let url {
                    BlurredCover(url: url, dark: theme.isDark)
                        .scaledToFill()
                        .opacity(0.85)
                        .overlay {
                            RadialGradient(colors: [.clear, theme.player.ground.color.opacity(0.55)], center: .center,
                                           startRadius: 40, endRadius: 520)
                        }
                }
            }
            .clipped()
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}

private struct PlayerOverlayChrome: View {
    let feature: PlayerFeatureModel
    let size: CGSize
    let safeAreaInsets: EdgeInsets
    /// Keep touch controls visible while the controls editor pane is open.
    var livePreview = false
    @State private var noticeVisible = false

    private var insets: PlayerViewportSafeAreaInsets {
        PlayerViewportSafeAreaInsets(top: safeAreaInsets.top, bottom: safeAreaInsets.bottom,
                                     left: safeAreaInsets.leading, right: safeAreaInsets.trailing)
    }

    var body: some View {
        // Banners sit below the camera housing (the Dynamic Island in portrait): this layer
        // covers the whole window, cutout included.
        let bannerTop = safeAreaInsets.top + 10
        let showsNotice = feature.noticeMessage != nil && feature.noticeOverGame && noticeVisible
        ZStack {
            VStack(spacing: 8) {
                if let message = feature.noticeMessage, showsNotice {
                    PlayerBanner(message: message, isError: false)
                        .transition(.opacity)
                }
                if let message = feature.errorMessage {
                    PlayerBanner(message: message, isError: true)
                }
            }
            .padding(.top, bannerTop)
            .frame(maxHeight: .infinity, alignment: .top)

            if let state = feature.controlsState, let layout = feature.touchLayout(containerSize: size, safeAreaInsets: insets) {
                PlayerControlsOverlay(
                    layout: layout, theme: feature.theme, opacity: state.touchLayout?.opacity ?? 0.72,
                    showControls: state.showTouchControls && (!feature.isPaused || livePreview),
                    haptics: feature.hapticsEnabled,
                    onInput: { output in feature.touchInput(output) },
                    onMenu: { Task { await feature.openPauseMenu() } })
                    .frame(width: size.width, height: size.height)
                if let snapshot = feature.overlaySnapshot(containerSize: size, safeAreaInsets: insets), snapshot.showsPointerOverlay {
                    DSPointerOverlay(feature: feature, touchScreenFrame: snapshot.touchScreenFrame
                        ?? CGRect(x: snapshot.viewportFrame.minX, y: snapshot.viewportFrame.midY,
                                  width: snapshot.viewportFrame.width, height: snapshot.viewportFrame.height / 2))
                }
            }
        }
        .animation(.easeOut(duration: 0.2), value: showsNotice)
        .task(id: feature.noticeMessage) {
            // Keep the detail in the progress panel, without leaving a success
            // banner across the game for the rest of the session.
            noticeVisible = feature.noticeMessage != nil
            do { try await Task.sleep(for: .seconds(2.5)) } catch { return }
            noticeVisible = false
        }
    }
}

private struct DSPointerOverlay: View {
    let feature: PlayerFeatureModel
    /// Where the touch screen is drawn: below, beside, or before the top screen.
    let touchScreenFrame: CGRect

    var body: some View {
        let width = touchScreenFrame.width
        let height = touchScreenFrame.height
        // libretro pointer coordinates cover the combined video frame.
        Color.clear
            .frame(width: width, height: height)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in send(value.location, width: width, height: height, pressed: true) }
                    .onEnded { value in send(value.location, width: width, height: height, pressed: false) }
            )
            .position(x: touchScreenFrame.midX, y: touchScreenFrame.midY)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(feature.isThreeDS ? "Nintendo 3DS touch screen" : "Nintendo DS touch screen")
            .accessibilityIdentifier(feature.isThreeDS ? "player.3ds.pointer" : "player.nds.pointer")
    }

    private func send(_ point: CGPoint, width: CGFloat, height: CGFloat, pressed: Bool) {
        let localX = min(max(point.x / max(width, 1), 0), 1)
        let x = feature.isThreeDS ? 0.1 + localX * 0.8 : localX
        let y = 0.5 + min(max(point.y / max(height, 1), 0), 1) / 2
        feature.enqueuePointer(normalizedX: x, normalizedY: y, pressed: pressed)
    }
}

/// Pause for a running player.
private struct PlayerPauseSheet: View {
    let feature: PlayerFeatureModel

    var body: some View {
        PlayerPausePanel(
            title: feature.presentation.romTitle,
            platformSlug: feature.presentation.platformSlug,
            runtimeName: feature.presentation.runtimeName,
            audioMuted: feature.audioMuted,
            actions: PlayerPauseActions(
                resume: { await feature.resumeGame() },
                quickSave: { await feature.quickSave() },
                savedProgress: { await feature.openStateBrowser() },
                controls: {
                    feature.pauseSheetPresented = false
                    feature.controlsSheetPresented = true
                },
                sync: { await feature.syncSaves() },
                reset: { await feature.resetCore() },
                leave: { await feature.leaveGame() },
                toggleSound: { await feature.toggleAudioMuted() }
            )
        )
    }
}

private struct PlayerControlsSheet: View {
    @Environment(\.rommioTheme) private var theme
    let feature: PlayerFeatureModel

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Toggle("Show touch controls", isOn: Binding(
                        get: { feature.controlsState?.preferences.touchControlsEnabled ?? true },
                        set: { value in Task { await feature.setTouchControlsEnabled(value) } }
                    ))

                    Toggle("Auto-hide on controller", isOn: Binding(
                        get: { feature.controlsState?.preferences.autoHideTouchOnController ?? true },
                        set: { value in Task { await feature.setAutoHideTouchOnController(value) } }
                    ))

                    Toggle("Left-handed swap", isOn: Binding(
                        get: { feature.controlsState?.touchLayout?.leftHanded ?? false },
                        set: { value in Task { await feature.setLeftHanded(value) } }
                    ))

                    Toggle("Haptics", isOn: Binding(
                        get: { feature.controlsState?.preferences.hapticsEnabled ?? true },
                        set: { value in Task { await feature.setHapticsEnabled(value) } }
                    ))
                    .accessibilityIdentifier("player.controls.haptics")

                    LabeledSlider(
                        title: "Opacity",
                        value: Binding(
                            get: { feature.controlsState?.touchLayout?.opacity ?? 0.72 },
                            set: { value in Task { await feature.updateTouchOpacity(value) } }
                        ),
                        range: 0.25...1.0
                    )

                    LabeledSlider(
                        title: "Global size",
                        value: Binding(
                            get: { feature.controlsState?.touchLayout?.globalScale ?? 1.0 },
                            set: { value in Task { await feature.updateTouchGlobalScale(value) } }
                        ),
                        range: 0.8...1.3
                    )
                } header: {
                    SectionHeader(title: "Touch controls")
                }
                .themedRow()

                if feature.offersAnalogControls {
                    Section {
                        Toggle("Analog controls", isOn: Binding(
                            get: { feature.analogControls },
                            set: { value in Task { await feature.setAnalogControls(value) } }
                        ))
                        .accessibilityIdentifier("player.controls.analogControls")
                    } header: {
                        SectionHeader(title: "This game")
                    } footer: {
                        Text("Shows both sticks, L3 and R3 for this game. The Analog button stays either way.")
                    }
                    .themedRow()
                }

                if feature.offersStickTuning {
                    Section {
                        Picker("Sensitivity", selection: Binding(
                            get: { feature.gameControls.stick.sensitivity },
                            set: { value in Task { await feature.updateStickTuning { $0.sensitivity = value } } }
                        )) {
                            Text("Low").tag(PlayerStickTuning.Sensitivity.low)
                            Text("Normal").tag(PlayerStickTuning.Sensitivity.normal)
                            Text("High").tag(PlayerStickTuning.Sensitivity.high)
                        }
                        .accessibilityIdentifier("player.controls.stickSensitivity")

                        Picker("Dead zone", selection: Binding(
                            get: { feature.gameControls.stick.deadZone },
                            set: { value in Task { await feature.updateStickTuning { $0.deadZone = value } } }
                        )) {
                            Text("Small").tag(PlayerStickTuning.DeadZone.small)
                            Text("Normal").tag(PlayerStickTuning.DeadZone.normal)
                            Text("Large").tag(PlayerStickTuning.DeadZone.large)
                        }
                        .accessibilityIdentifier("player.controls.stickDeadZone")

                        Picker("Centre", selection: Binding(
                            get: { feature.gameControls.stick.centre },
                            set: { value in Task { await feature.updateStickTuning { $0.centre = value } } }
                        )) {
                            Text("Floating").tag(PlayerStickTuning.Centre.floating)
                            Text("Fixed").tag(PlayerStickTuning.Centre.fixed)
                        }
                        .accessibilityIdentifier("player.controls.stickCentre")

                        Button("Reset") { Task { await feature.resetStickTuning() } }
                            .disabled(feature.gameControls.stick == .standard)
                            .accessibilityIdentifier("player.controls.stickReset")
                    } header: {
                        SectionHeader(title: "Stick")
                    } footer: {
                        Text("For this game. Reset returns to the console's feel.")
                    }
                    .themedRow()
                }

                Section {
                    LabeledSlider(
                        title: "Deadzone",
                        value: Binding(
                            get: { feature.controlsState?.hardwareBinding.deadzone ?? 0.2 },
                            set: { value in Task { await feature.updateDeadzone(value) } }
                        ),
                        range: 0.05...0.5
                    )

                    if !feature.availableControllerTypes.isEmpty {
                        Picker("Controller type", selection: Binding(
                            get: { feature.controlsState?.hardwareBinding.controllerTypeID ?? -1 },
                            set: { value in
                                Task { await feature.setControllerType(value < 0 ? nil : value) }
                            }
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
                } header: {
                    SectionHeader(title: "Controller")
                }
                .themedRow()

                Section {
                    Toggle("OLED black mode", isOn: Binding(
                        get: { feature.controlsState?.preferences.oledBlackModeEnabled ?? false },
                        set: { value in Task { await feature.setOLEDBlackModeEnabled(value) } }
                    ))

                    Toggle("Console colors", isOn: Binding(
                        get: { feature.controlsState?.preferences.consoleColorsEnabled ?? false },
                        set: { value in Task { await feature.setConsoleColorsEnabled(value) } }
                    )).accessibilityIdentifier("player.controls.consoleColors")
                } header: {
                    SectionHeader(title: "Display")
                }
                .themedRow()

                Section {
                    Button("Reset control tuning", role: .destructive) {
                        Task { await feature.resetControlTuning() }
                    }
                    .foregroundStyle(theme.badText)
                }
                .themedRow()
            }
            .themeText(.body, size: 16)
            .tint(theme.unlitLight.readableTint)
            .themedList()
            .navigationTitle("Controls")
            .accessibilityIdentifier("player.controls.form")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        feature.controlsSheetPresented = false
                    }
                }
            }
        }
    }
}

private struct LabeledSlider: View {
    @Environment(\.rommioTheme) private var theme
    let title: String
    @Binding var value: Double
    let range: ClosedRange<Double>

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(title).foregroundStyle(theme.ink)
                Spacer()
                Text(value.formatted(.percent.precision(.fractionLength(0))))
                    .themeText(.numeral, size: 13)
                    .foregroundStyle(theme.inkSecondary)
            }
            Slider(value: $value, in: range)
        }
    }
}

/// Notices and errors over the game: a dark glass pill (Aurora) or a paper
/// ticket with a yellow plate (Riso).
private struct PlayerBanner: View {
    @Environment(\.rommioTheme) private var theme
    let message: String
    let isError: Bool

    var body: some View {
        let label = theme.tokens.type.playerLabel
        HStack(spacing: 8) {
            Image(systemName: isError ? "exclamationmark.triangle.fill" : "checkmark")
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(theme.isRiso ? theme.player.toastInk.color : (isError ? DarkFrame.bad : DarkFrame.ink))
                .padding(theme.isRiso ? 3 : 0)
                .background(theme.isRiso ? (isError ? theme.accent1 : theme.accent4) : .clear)
            Text(message)
                .themeText(.playerLabel, size: theme.isRiso ? label.size : 13)
                .foregroundStyle(theme.isRiso ? theme.player.toastInk.color : DarkFrame.ink)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 9)
        .background {
            if theme.isRiso {
                Rectangle().fill(theme.player.toastFill.color)
                    .background(Rectangle().fill(isError ? theme.accent1 : theme.accent3).offset(x: 3, y: 3))
            } else {
                Capsule().fill(DarkFrame.glass.opacity(0.62))
                    .background(Capsule().fill(.ultraThinMaterial))
            }
        }
        .accessibilityElement(children: .combine)
    }
}

private struct PlayerHostContainer: UIViewControllerRepresentable {
    let controllerProvider: () throws -> PlayerHostController

    func makeUIViewController(context: Context) -> UIViewController {
        do {
            return try controllerProvider()
        } catch {
            return PlayerErrorViewController(message: error.localizedDescription)
        }
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

private final class PlayerErrorViewController: UIViewController {
    private let message: String

    init(message: String) {
        self.message = message
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        view.accessibilityIdentifier = "player.error"

        let label = UILabel()
        label.translatesAutoresizingMaskIntoConstraints = false
        label.text = message
        label.textColor = .white
        label.numberOfLines = 0
        label.textAlignment = .center
        label.accessibilityIdentifier = "player.errorMessage"
        view.addSubview(label)

        NSLayoutConstraint.activate([
            label.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            label.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
            label.centerYAnchor.constraint(equalTo: view.centerYAnchor),
        ])
    }
}
#else
struct PlayerExperienceView: View {
    let feature: PlayerFeatureModel

    var body: some View {
        VStack(spacing: 12) {
            Text(feature.presentation.romTitle)
                .font(.headline)
            Text("Player hosting requires UIKit.")
                .foregroundStyle(.secondary)
        }
        .padding(24)
    }
}
#endif
