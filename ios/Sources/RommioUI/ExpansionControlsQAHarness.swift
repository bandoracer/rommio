#if DEBUG && canImport(UIKit)
import SwiftUI
import RommioFoundation
import RommioPlayerKit

/// Opt-in input/geometry fixture; no ROM, server or profile data is opened.
/// `ROMMIO_CONTROLS_QA_PAUSE=1` also opens Pause the way the player does
/// (`PlayerPanelPresentation`): a sheet, or on a wide window (iPhone Duo open,
/// iPad) the floating inspector beside the game, with the stage keeping the game
/// clear of it. The menu button then opens and closes Pause.
/// PlayStation shows its Analog controls unless `ROMMIO_CONTROLS_QA_ANALOG=0`. Taps
/// that reach a game screen are logged (`screen0:x,y`), so UI tests can check that the
/// touch surface lets them through.
public struct ExpansionControlsQAHarness: View {
    @State private var family: String
    @State private var hidden = false
    @State private var mirrored = false
    @State private var events: [String] = []
    @State private var paused: Bool
    private let pauseFixture: Bool
    private let analogControls = ProcessInfo.processInfo.environment["ROMMIO_CONTROLS_QA_ANALOG"] != "0"
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    public init(family: String) {
        _family = State(initialValue: family)
        let pause = ProcessInfo.processInfo.environment["ROMMIO_CONTROLS_QA_PAUSE"] == "1"
        pauseFixture = pause
        _paused = State(initialValue: pause)
    }
    public var body: some View {
        GeometryReader { geometry in
            let insets = geometry.safeAreaInsets
            let size = CGSize(width: geometry.size.width + insets.leading + insets.trailing, height: geometry.size.height + insets.top + insets.bottom)
            let controls = ExpansionControls.controls(for: family)!
            let profile = PlatformControlProfileResolver().supportedProfiles().first { $0.familyID == family }!
            // The stored appearance, so each theme's player style can be checked here.
            let appearance = AppearanceStore()
            let dark = appearance.scheme == .dark || (appearance.scheme == .system && colorScheme == .dark)
            let appTheme = RommioTheme(appearance.theme, dark: dark)
            let theme = resolvePlayerVisualTheme(controls: nil, familyOverride: family, appTheme: appTheme)
            let presentation = PlayerPanelPresentation.resolve(regularWidth: horizontalSizeClass == .regular, windowWidth: size.width)
            let paneWidth = paused && presentation == .sidePane ? PlayerPanelPresentation.paneWidth(windowWidth: size.width) : 0
            // The same stage facts the player uses, so iPhone Duo shows its rules here too.
            let stage = PlayerStageContext(dualScreen: ["nds", "3ds"].contains(family), threeDS: family == "3ds",
                                           isPhoneFormFactor: UIDevice.current.userInterfaceIdiom == .phone,
                                           trailingPaneWidth: paneWidth)
            let layout = resolvePlayerStageLayout(containerSize: size,
                safeAreaInsets: PlayerViewportSafeAreaInsets(top: insets.top, bottom: insets.bottom, left: insets.leading, right: insets.trailing),
                aspectRatio: profile.preferredViewportAspectRatio, topAlignedInPortrait: true,
                controlDeckHeight: ExpansionControls.deckHeight(controls), reserveTouchSpace: !hidden, utilityCount: 1,
                stage: stage, controls: PlayerControlPlan(family: family, analogControls: analogControls), mirrored: mirrored)
            let viewport = layout.screens.first?.frame ?? layout.viewport.frame
            ZStack(alignment: .topLeading) {
                theme.canvasColor
                // Aurora's frosted controls sit over the game's bias light; the theme's
                // own light stands in for a cover here.
                if appTheme.isGlass { PageBackground(style: .room) }
                ForEach(Array(layout.screens.enumerated()), id: \.offset) { index, screen in
                    RoundedRectangle(cornerRadius: 4).fill(Color(red: 0.12, green: 0.19, blue: 0.24))
                        .overlay(RoundedRectangle(cornerRadius: 4).stroke(theme.accentColor.opacity(0.4), lineWidth: 1))
                        .frame(width: screen.frame.width, height: screen.frame.height)
                        .contentShape(Rectangle())
                        .onTapGesture { point in events.append("screen\(index):\(Int(point.x)),\(Int(point.y))") }
                        .position(x: screen.frame.midX, y: screen.frame.midY)
                        .accessibilityElement().accessibilityLabel(screen.isTouchScreen ? "Touch screen" : "Game viewport")
                        .accessibilityIdentifier(index == 0 ? "qa.viewport" : "qa.viewport.\(index)")
                }
                VStack(spacing: 12) {
                    Text(profile.displayName).font(.headline)
                    Text("GAME AREA").font(.caption.monospaced()).tracking(3).opacity(0.55)
                    Button("Mirror") { mirrored.toggle() }
                    Text(events.suffix(8).joined(separator: "|")).font(.system(size: 8)).accessibilityIdentifier("qa.events")
                }.foregroundStyle(.white).frame(width: viewport.width, height: viewport.height)
                    .position(x: viewport.midX, y: viewport.midY)
                if let touch = layout.controls {
                    PlayerControlsOverlay(layout: touch, theme: theme, opacity: 0.72, showControls: !hidden, haptics: true,
                        title: "\(profile.displayName) controls", detail: "QA harness · no game",
                        onInput: { output in
                            switch output {
                            case let .digital(key, pressed): events.append("D\(key):\(pressed ? 1 : 0)")
                            case let .motion(stick, x, y): events.append("A\(stick):\(Int((x * 100).rounded())),\(Int((y * 100).rounded()))")
                            default: break
                            }
                        },
                        onMenu: {
                            if pauseFixture { paused.toggle(); events.append(paused ? "pause" : "resume") }
                            else { hidden.toggle(); events.append("menu") }
                        })
                        .frame(width: size.width, height: size.height)
                }
                if paneWidth > 0 {
                    // The floating inspector beside the game, as the player shows it on a wide window.
                    pausePanel(profile: profile)
                        .padding(.top, insets.top)
                        .padding(.bottom, insets.bottom)
                        .padding(.trailing, insets.trailing)
                        .frame(width: paneWidth, height: size.height)
                        .background(.regularMaterial)
                        .accessibilityIdentifier("player.sidePane")
                        .offset(x: size.width - paneWidth)
                }
            }.frame(width: size.width, height: size.height).offset(x: -insets.leading, y: -insets.top)
            .environment(\.rommioTheme, appTheme)
            .environment(\.pageLight, appTheme.unlitLight)
            // Materials follow the stored scheme, as ThemeHost makes them in the app.
            .environment(\.colorScheme, dark ? .dark : .light)
            .sheet(isPresented: Binding(get: { paused && presentation == .sheet }, set: { paused = $0 })) {
                pausePanel(profile: profile)
                    .presentationDetents([.height(430), .large])
                    .presentationDragIndicator(.visible)
                    .environment(\.rommioTheme, appTheme)
                    .environment(\.colorScheme, dark ? .dark : .light)
            }
        }.statusBarHidden()
    }

    private func pausePanel(profile: PlatformControlProfile) -> some View {
        PlayerPausePanel(
            title: "\(profile.displayName) controls", platformSlug: family, runtimeName: "QA harness",
            actions: PlayerPauseActions(
                resume: { paused = false; events.append("resume") },
                quickSave: { events.append("pause.quickSave") },
                savedProgress: { events.append("pause.savedProgress") },
                controls: { events.append("pause.controls") },
                sync: { events.append("pause.sync") },
                reset: { events.append("pause.reset") },
                leave: { events.append("pause.leave") }
            )
        )
        .accessibilityIdentifier("player.pause.sheet")
    }
}
#endif
