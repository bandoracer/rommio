import SwiftUI
import ImageIO
import RommioFoundation
import RommioPlayerKit

/// Settings › Screen playground: game frames drawn the way a console's screen showed them
/// (`ScreenShaders`: a CRT's scanlines, mask and glow; a handheld LCD's grid, colours and
/// shadow). Nothing here changes the player yet; it's where the looks are tuned before
/// they ship.
struct ScreenPlaygroundPage: View {
    let samples: [ScreenSample]
    @State private var lab = ScreenLab()

    var body: some View {
        ThemedScrollPage(maxWidth: 760, topPadding: 12) {
            ScreenSources(lab: lab, samples: samples)
            ScreenPresets(lab: lab)
            ScreenControls(lab: lab)
        }
        // The screen stays in view while the sliders below it scroll.
        .safeAreaInset(edge: .top, spacing: 0) { ScreenStage(lab: lab) }
        .navigationTitle("Screen playground")
        .inlineNavigationTitle()
        .accessibilityIdentifier("screen.screenPlayground")
        .task(id: lab.frameKey) { lab.loadFrame() }
    }
}

/// A game frame to try the screens on: a save's preview (the frame captured with it).
struct ScreenSample: Identifiable, Hashable {
    let id: String
    let title: String
    let platformSlug: String
    let url: URL

    /// One recent save preview per game, newest first.
    @MainActor
    static func recent(_ model: RommioAppModel, limit: Int = 12) -> [ScreenSample] {
        let saves = model.savesFeature
        var seen = Set<Int64>()
        var samples: [ScreenSample] = []
        for entry in saves.recent where samples.count < limit {
            guard let url = entry.thumbnailURL, seen.insert(entry.romId).inserted else { continue }
            let rom = saves.game(entry.romId)
            samples.append(ScreenSample(id: "\(entry.romId)", title: rom.displayName, platformSlug: rom.platformSlug, url: url))
        }
        return samples
    }
}

/// What the stage shows: the test card (still or moving) or a save's frame.
enum ScreenSource: Identifiable, Hashable {
    case testCard
    case motion
    case sample(ScreenSample)

    var id: String {
        switch self {
        case .testCard: "test-card"
        case .motion: "motion"
        case .sample(let sample): sample.id
        }
    }

    var title: String {
        switch self {
        case .testCard: "Test card"
        case .motion: "Motion test"
        case .sample(let sample): sample.title
        }
    }
}

/// The playground's state: the screen (a preset, then edited), the frame, and the loupe.
@MainActor
@Observable
final class ScreenLab {
    private(set) var preset = ScreenStyle.consumerTV
    var style = ScreenStyle.consumerTV
    private(set) var source = ScreenSource.testCard
    private(set) var frame: CGImage?
    /// 1 (the whole screen), 2 or 4, around `focus` (0…1 across the frame).
    var zoom = 1
    var focus = CGPoint(x: 0.5, y: 0.5)
    var fullScreen = false

    /// The frame to load: the test card follows the screen's resolution.
    var frameKey: String {
        switch source {
        case .testCard, .motion: "card-\(style.nativeSize.x)x\(style.nativeSize.y)"
        case .sample(let sample): sample.id
        }
    }

    var edited: Bool { style != preset }
    /// The moving test card plays instead of a still.
    var moving: Bool { source == .motion }

    /// The picture's shape: a CRT's 4:3, else the frame's own.
    var aspect: Double {
        if let aspect = style.displayAspect { return aspect }
        guard let frame, frame.height > 0 else { return 4.0 / 3.0 }
        return Double(frame.width) / Double(frame.height)
    }

    /// The part of the frame shown.
    var crop: CGRect {
        let size = 1 / Double(max(zoom, 1))
        let x = min(max(focus.x - size / 2, 0), 1 - size)
        let y = min(max(focus.y - size / 2, 0), 1 - size)
        return CGRect(x: x, y: y, width: size, height: size)
    }

    func choose(_ preset: ScreenStyle) {
        self.preset = preset
        style = preset
    }

    /// A save's frame suggests its platform's screen.
    func choose(_ source: ScreenSource) {
        self.source = source
        if case .sample(let sample) = source { choose(ScreenStyle.suggested(platformSlug: sample.platformSlug)) }
    }

    func reset() { style = preset }

    /// Tap: magnify around the spot; tap again to step back.
    func tap(at point: CGPoint) {
        if zoom == 1 {
            focus = point
            zoom = 4
        } else {
            zoom = 1
        }
    }

    func loadFrame() {
        switch source {
        case .testCard, .motion:
            frame = ScreenTestCard.image(width: style.nativeSize.x, height: style.nativeSize.y)
        case .sample(let sample):
            guard let image = CGImageSourceCreateWithURL(sample.url as CFURL, nil)
                .flatMap({ CGImageSourceCreateImageAtIndex($0, 0, nil) }) else {
                frame = ScreenTestCard.image(width: style.nativeSize.x, height: style.nativeSize.y)
                return
            }
            frame = image
        }
    }
}

private struct ScreenStage: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Bindable var lab: ScreenLab

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ScreenFrame(lab: lab)
                // Room for the sliders below on a phone; a tablet shows the screen larger.
                .frame(maxWidth: .infinity, maxHeight: horizontalSizeClass == .regular ? 420 : 260)
                .fullScreenScreen(lab: lab)
            HStack(alignment: .center) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(lab.style.name + (lab.edited ? " · edited" : "")).themeText(.bodyEmphasis, size: 16).foregroundStyle(theme.ink)
                    Text(lab.zoom == 1 ? "Tap the screen to look closely at a spot" : "Magnified \(lab.zoom)×: tap to step back")
                        .themeText(.caption).foregroundStyle(theme.inkSecondary)
                }
                Spacer(minLength: 8)
                Button { lab.fullScreen = true } label: {
                    Image(systemName: "arrow.up.left.and.arrow.down.right")
                        .font(.system(size: 15, weight: .semibold))
                        .frame(width: 40, height: 36)
                }
                .buttonStyle(.plain)
                .foregroundStyle(theme.inkSecondary)
                .accessibilityLabel("Full screen")
                .accessibilityIdentifier("screen.fullScreen")
            }
        }
        .padding(.horizontal, theme.spacing.gutter)
        .padding(.vertical, 10)
        .frame(maxWidth: 760)
        .frame(maxWidth: .infinity)
        .background(theme.ground)
        .overlay(alignment: .bottom) { Rectangle().fill(theme.hairline).frame(height: 1) }
    }
}

/// The frame through the shader, at the screen's shape.
private struct ScreenFrame: View {
    let lab: ScreenLab

    var body: some View {
        GeometryReader { proxy in
            ScreenCanvas(frame: lab.frame, style: lab.style, crop: lab.crop, motion: lab.moving)
                .contentShape(Rectangle())
                .onTapGesture(coordinateSpace: .local) { point in
                    withAnimation(.smooth(duration: 0.2)) {
                        lab.tap(at: CGPoint(x: point.x / max(proxy.size.width, 1), y: point.y / max(proxy.size.height, 1)))
                    }
                }
        }
        .aspectRatio(lab.aspect, contentMode: .fit)
        .background(Color.black)
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        .accessibilityElement()
        .accessibilityLabel("\(lab.source.title) on \(lab.style.name)")
        .accessibilityIdentifier("screen.stage")
    }
}

private extension View {
    /// The screen alone, as large as it fits on black; turn the phone for the most of it.
    func fullScreenScreen(lab: ScreenLab) -> some View {
        #if os(iOS)
        fullScreenCover(isPresented: Binding(get: { lab.fullScreen }, set: { lab.fullScreen = $0 })) {
            ScreenFullView(lab: lab)
        }
        #else
        self
        #endif
    }
}

#if os(iOS)
private struct ScreenFullView: View {
    let lab: ScreenLab

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            ScreenCanvas(frame: lab.frame, style: lab.style, crop: lab.crop, motion: lab.moving)
                .aspectRatio(lab.aspect, contentMode: .fit)
        }
        .contentShape(Rectangle())
        .onTapGesture { lab.fullScreen = false }
        .statusBarHidden()
        .accessibilityAddTraits(.isButton)
        .accessibilityLabel("Close full screen")
    }
}
#endif

private struct ScreenSources: View {
    let lab: ScreenLab
    let samples: [ScreenSample]

    var body: some View {
        ThemedGroup(title: "Picture", footer: samples.isEmpty
                    ? "Save previews appear here after you play: each save keeps the frame it was made on."
                    : "Motion test plays the card at 60 frames a second: a sweeping bar, a gliding sprite and one shown every other frame. Save previews are the frame each save was made on, kept small (at most 320 pixels wide) and compressed, so the finest detail is softer than the game's own frame. Choosing one picks its console's screen.") {
            PlaygroundChips(items: [ScreenSource.testCard, .motion] + samples.map(ScreenSource.sample), title: \.title,
                            selection: Binding(get: { lab.source }, set: { lab.choose($0) }))
                .padding(.vertical, 4)
        }
    }
}

private struct ScreenPresets: View {
    let lab: ScreenLab

    var body: some View {
        ThemedGroup(title: "Screens", detail: "\(ScreenStyle.all.count)", footer: lab.preset.source) {
            PlaygroundChips(items: ScreenStyle.all, title: \.name,
                            selection: Binding(get: { lab.preset }, set: { lab.choose($0) }))
                .padding(.vertical, 4)
        }
    }
}

private struct ScreenControls: View {
    @Environment(\.rommioTheme) private var theme
    @Bindable var lab: ScreenLab

    private var signalNote: String {
        switch lab.style.signal {
        case .rgb:
            "RGB: each colour on its own wire, as a studio monitor or a SCART TV got it. Composite is the living-room look: dithering blends and fine detail shimmers with colour."
        case .sVideo:
            "S-Video: brightness and colour on separate wires. Colour softens, but nothing turns into rainbows."
        case .composite:
            "Composite: one wire for both, which the TV's filters can't fully pull apart, so fine detail picks up rainbows and dithering blends. The carrier sets the pattern: standing still on a Genesis, crawling diagonals on a NES or SNES. A narrower luma filter is sharper but lets the carrier through as dot crawl."
        }
    }

    var body: some View {
        switch lab.style.kind {
        case .crt:
            VStack(alignment: .leading, spacing: theme.spacing.section) {
                ThemedGroup(title: "Signal", footer: signalNote) {
                    CRTSignalControls(lab: lab)
                }
                ThemedGroup(title: "Tube", footer: "Lines are the beam: thin in the dark, swelling in bright parts. The mask is the phosphor pattern, measured in this screen's own pixels. Glow is the light spreading through the glass.") {
                    CRTBeamControls(lab: lab)
                    CRTMaskControls(lab: lab)
                    CRTGlassControls(lab: lab)
                    ResetRow(lab: lab)
                }
            }
        case .lcd:
            ThemedGroup(title: "Panel", footer: (lab.style.palette != nil
                        ? "A mono panel: the frame's brightness picks a shade between the screen's darkest and lightest, and each pixel casts a shadow on the layer behind it."
                        : "Colour turns the frame into the panel's own, duller response; darken adds the gamma of an unlit screen.")
                        + " Response is the panel's slowness, how much of the last frame each pixel keeps: it shows in motion, so try Motion test.") {
                LCDControls(lab: lab)
                ResetRow(lab: lab)
            }
        case .sharp, .smooth:
            EmptyView()
        }
    }
}

private struct CRTSignalControls: View {
    @Environment(\.rommioTheme) private var theme
    @Bindable var lab: ScreenLab

    var body: some View {
        Picker("Signal", selection: $lab.style.signal) {
            Text("RGB").tag(ScreenStyle.Signal.rgb)
            Text("S-Video").tag(ScreenStyle.Signal.sVideo)
            Text("Composite").tag(ScreenStyle.Signal.composite)
        }
        .pickerStyle(.segmented)
        .padding(.vertical, 4)
        .accessibilityIdentifier("screen.signal")
        if lab.style.signal != .rgb {
            VStack(alignment: .leading, spacing: 8) {
                Text("Carrier").themeText(.bodyEmphasis, size: 16).foregroundStyle(theme.ink)
                Picker("Carrier", selection: $lab.style.linePhase) {
                    Text("Genesis").tag(0.0)
                    Text("NES · SNES").tag(120.0)
                    Text("Broadcast").tag(180.0)
                }
                .pickerStyle(.segmented)
            }
            SliderRow(title: "Colour filter", value: $lab.style.colorFilter, range: 0.5...3,
                      label: String(format: "%.2f cycles", lab.style.colorFilter))
            SliderRow(title: "Luma filter", value: $lab.style.lumaFilter, range: 0.15...0.8, step: 0.01,
                      label: String(format: "%.2f cycle", lab.style.lumaFilter))
        }
    }
}

private struct CRTBeamControls: View {
    @Bindable var lab: ScreenLab

    var body: some View {
        SliderRow(title: "Scanlines", value: $lab.style.scanlines, range: 0...1, label: percent(lab.style.scanlines))
        SliderRow(title: "Thinnest beam", value: $lab.style.thinnestBeam, range: 0.15...0.6, step: 0.01,
                  label: String(format: "%.2f line", lab.style.thinnestBeam))
        SliderRow(title: "Widest beam", value: $lab.style.widestBeam, range: 0.35...1, step: 0.01,
                  label: String(format: "%.2f line", lab.style.widestBeam))
        SliderRow(title: "Softness", value: $lab.style.blur, range: 0.3...1.5, label: String(format: "%.2f px", lab.style.blur))
        if lab.style.signal == .rgb {
            // A signal's own filters set its colour's softness.
            SliderRow(title: "Colour bleed", value: $lab.style.chromaBleed, range: 0...1, label: percent(lab.style.chromaBleed))
        }
    }
}

private struct CRTMaskControls: View {
    @Environment(\.rommioTheme) private var theme
    @Bindable var lab: ScreenLab

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Mask").themeText(.bodyEmphasis, size: 16).foregroundStyle(theme.ink)
            Picker("Mask", selection: $lab.style.maskType) {
                Text("None").tag(0)
                Text("Grille").tag(1)
                Text("Slot").tag(2)
                Text("Dots").tag(3)
            }
            .pickerStyle(.segmented)
        }
        SliderRow(title: "Mask strength", value: $lab.style.maskStrength, range: 0...0.7, label: percent(lab.style.maskStrength))
        SliderRow(title: "Mask pitch", value: $lab.style.maskPitch, range: 1...4, step: 1,
                  label: "\(Int(lab.style.maskPitch)) px per phosphor")
        SliderRow(title: "Brightness", value: $lab.style.brightness, range: 0.8...1.8, label: percent(lab.style.brightness))
        SliderRow(title: "Glow", value: $lab.style.glow, range: 0...0.5, step: 0.01, label: percent(lab.style.glow))
        if lab.style.glow > 0 {
            SliderRow(title: "Glow size", value: $lab.style.glowSize, range: 0.5...6, step: 0.1,
                      label: String(format: "%.1f px", lab.style.glowSize))
        }
    }
}

private struct CRTGlassControls: View {
    @Bindable var lab: ScreenLab

    var body: some View {
        SliderRow(title: "Curvature", value: $lab.style.curvature, range: 0...0.3, step: 0.01, label: percent(lab.style.curvature))
        SliderRow(title: "Corners", value: $lab.style.cornerRadius, range: 0...0.12, step: 0.01, label: percent(lab.style.cornerRadius))
        SliderRow(title: "Vignette", value: $lab.style.vignette, range: 0...0.6, label: percent(lab.style.vignette))
    }
}

private struct LCDControls: View {
    @Bindable var lab: ScreenLab

    var body: some View {
        SliderRow(title: "Grid", value: $lab.style.grid, range: 0...1, label: percent(lab.style.grid))
        SliderRow(title: "Gap width", value: $lab.style.gapWidth, range: 0.02...0.3, step: 0.01,
                  label: String(format: "%.2f pixel", lab.style.gapWidth))
        if lab.style.palette != nil {
            SliderRow(title: "Shadow", value: $lab.style.shadow, range: 0...0.7, label: percent(lab.style.shadow))
            SliderRow(title: "Shadow offset", value: $lab.style.shadowOffset, range: 0.1...0.8,
                      label: String(format: "%.2f pixel", lab.style.shadowOffset))
        } else {
            SliderRow(title: "Subpixels", value: $lab.style.subpixels, range: 0...0.6, label: percent(lab.style.subpixels))
            SliderRow(title: "Colour", value: $lab.style.colorCorrection, range: 0...1,
                      label: lab.style.colorCorrection == 0 ? "As the game drew it" : percent(lab.style.colorCorrection))
            SliderRow(title: "Darken", value: $lab.style.darken, range: 0...1, label: percent(lab.style.darken))
        }
        SliderRow(title: "Brightness", value: $lab.style.brightness, range: 0.7...1.3, step: 0.01, label: percent(lab.style.brightness))
        SliderRow(title: "Response", value: $lab.style.response, range: 0...0.85, step: 0.01,
                  label: lab.style.response == 0 ? "Instant" : percent(lab.style.response))
    }
}

private struct ResetRow: View {
    @Environment(\.rommioTheme) private var theme
    let lab: ScreenLab

    var body: some View {
        Button { withAnimation(.smooth(duration: 0.2)) { lab.reset() } } label: {
            Text("Back to \(lab.preset.name)")
                .themeText(.bodyEmphasis, size: 16)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .buttonStyle(.plain)
        .foregroundStyle(lab.edited ? theme.ink : theme.inkTertiary)
        .disabled(!lab.edited)
        .accessibilityIdentifier("screen.reset")
    }
}

private func percent(_ value: Double) -> String { "\(Int((value * 100).rounded()))%" }
