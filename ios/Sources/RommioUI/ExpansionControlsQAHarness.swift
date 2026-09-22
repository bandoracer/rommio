#if DEBUG && canImport(UIKit)
import SwiftUI
import RommioFoundation
import RommioPlayerKit

/// Opt-in input/geometry fixture; no ROM, server or profile data is opened.
public struct ExpansionControlsQAHarness: View {
    @State private var family: String
    @State private var hidden = false
    @State private var mirrored = false
    @State private var events: [String] = []
    public init(family: String) { _family = State(initialValue: family) }
    public var body: some View {
        GeometryReader { geometry in
            let insets = geometry.safeAreaInsets
            let size = CGSize(width: geometry.size.width + insets.leading + insets.trailing, height: geometry.size.height + insets.top + insets.bottom)
            let controls = ExpansionControls.controls(for: family)!
            let profile = PlatformControlProfileResolver().supportedProfiles().first { $0.familyID == family }!
            let theme = resolvePlayerVisualTheme(controls: nil, familyOverride: family)
            let viewport = resolvePlayerViewportLayout(containerSize: size,
                safeAreaInsets: PlayerViewportSafeAreaInsets(top: insets.top, bottom: insets.bottom, left: insets.leading, right: insets.trailing),
                aspectRatio: profile.preferredViewportAspectRatio, topAlignedInPortrait: true,
                controlDeckHeight: ExpansionControls.deckHeight(controls), reserveTouchSpace: !hidden, utilityCount: hidden ? 1 : controls.filter(\.center).count + 1).frame
            ZStack(alignment: .topLeading) {
                theme.canvasColor
                RoundedRectangle(cornerRadius: 4).fill(Color(red: 0.12, green: 0.19, blue: 0.24))
                    .overlay(RoundedRectangle(cornerRadius: 4).stroke(theme.accentColor.opacity(0.4), lineWidth: 1))
                    .frame(width: viewport.width, height: viewport.height)
                    .position(x: viewport.midX, y: viewport.midY)
                    .accessibilityElement().accessibilityLabel("Game viewport").accessibilityIdentifier("qa.viewport")
                VStack(spacing: 12) {
                    Text(profile.displayName).font(.headline)
                    Text("GAME AREA").font(.caption.monospaced()).tracking(3).opacity(0.55)
                    Button("Mirror") { mirrored.toggle() }
                    Text(events.suffix(8).joined(separator: "|")).font(.system(size: 8)).accessibilityIdentifier("qa.events")
                }.foregroundStyle(.white).frame(width: viewport.width, height: viewport.height)
                    .position(x: viewport.midX, y: viewport.midY)
                ExpansionControlsOverlay(controls: controls, size: size, insets: insets,
                    leftHanded: mirrored, scale: 1, opacity: 1, showControls: !hidden,
                    onDigital: { key, pressed in events.append("D\(key):\(pressed ? 1 : 0)") },
                    onMotion: { stick, x, y in events.append("A\(stick):\(Int((x * 100).rounded())),\(Int((y * 100).rounded()))") },
                    onMenu: { hidden.toggle(); events.append("menu") }, theme: theme, aspectRatio: profile.preferredViewportAspectRatio)
            }.frame(width: size.width, height: size.height).offset(x: -insets.leading, y: -insets.top)
        }.statusBarHidden()
    }
}
#endif
