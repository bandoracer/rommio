import SwiftUI

/// Settings › Haptics playground: rumble and controller-feel designs to try by touch
/// (the designs are in `Haptics/`). Nothing here changes the player yet; it's where the
/// feel is tuned before it ships.
struct HapticsPlaygroundPage: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var lab = HapticsLab()

    var body: some View {
        ThemedScrollPage(maxWidth: 760) {
            PlaygroundIntro(supported: lab.isSupported)
            PlaygroundTuning(lab: lab)
            PlaygroundController(lab: lab)
            PlaygroundRumble(lab: lab)
            PlaygroundMaterials(lab: lab)
            PlaygroundDpads(lab: lab)
            PlaygroundSticks(lab: lab)
        }
        .navigationTitle("Haptics playground")
        .accessibilityIdentifier("screen.hapticsPlayground")
        .task { lab.prepare() }
        // The system stops the engine in the background: ready again before the first touch.
        .onChange(of: scenePhase) { _, phase in if phase == .active { lab.prepare() } }
        .onDisappear { lab.stopAll() }
    }
}

private struct PlaygroundIntro: View {
    @Environment(\.rommioTheme) private var theme
    let supported: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Rumble and controller feel, played on the Taptic Engine. Press, hold, roll and slide: everything here is live.")
                .themeText(.body)
                .foregroundStyle(theme.ink)
            Text(supported
                 ? "System Haptics (Sounds & Haptics) and Vibration (Accessibility › Touch) need to be on. Silent mode doesn't matter."
                 : "This device has no Taptic Engine, so nothing here can be felt.")
                .themeText(.secondary, size: 13)
                .foregroundStyle(supported ? theme.inkSecondary : theme.badText)
        }
        .fixedSize(horizontal: false, vertical: true)
    }
}

private struct PlaygroundTuning: View {
    @Bindable var lab: HapticsLab

    var body: some View {
        ThemedGroup(title: "Tuning", footer: "Applies to everything on this page: strength scales every intensity, sharpness shifts every design deeper or crisper. Sounds give buttons, d-pads and sticks a quiet voice, in time with the haptic; they follow the Ring/Silent switch.") {
            SliderRow(title: "Strength", value: $lab.strength, range: 0.2...1, label: "\(Int((lab.strength * 100).rounded()))%")
            SliderRow(title: "Sharpness", value: $lab.sharpnessBias, range: -0.3...0.3,
                      label: lab.sharpnessBias == 0 ? "As designed" : String(format: "%+.2f", lab.sharpnessBias))
            LatencyRow(lab: lab)
            ToggleRow(title: "Sounds", isOn: $lab.soundsOn)
                .accessibilityIdentifier("haptics.sounds")
            if lab.soundsOn {
                SliderRow(title: "Sound level", value: $lab.soundLevel, range: 0.05...1,
                          label: "\(Int((lab.soundLevel * 100).rounded()))%")
                PlaygroundChips(items: SoundStyle.allCases, title: \.title, selection: $lab.soundStyle)
            }
        }
    }
}

private struct SliderRow: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.pageLight) private var light
    let title: String
    @Binding var value: Double
    let range: ClosedRange<Double>
    let label: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(title).themeText(.bodyEmphasis, size: 16).foregroundStyle(theme.ink)
                Spacer()
                Text(label).themeText(.numeral, size: 14).foregroundStyle(theme.inkSecondary)
            }
            Slider(value: $value, in: range, step: 0.05)
                .tint(light.readableTint)
        }
    }
}

/// The last press's time from touch to haptic (the part software controls: the screen
/// registering the touch, the app, Core Haptics accepting the pattern; the actuator's own
/// response comes after).
private struct LatencyRow: View {
    @Environment(\.rommioTheme) private var theme
    let lab: HapticsLab

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Touch to haptic").themeText(.bodyEmphasis, size: 16).foregroundStyle(theme.ink)
                Text("The last press, and the average of the last 20").themeText(.secondary, size: 13).foregroundStyle(theme.inkSecondary)
            }
            Spacer(minLength: 8)
            Text(label).themeText(.numeral, size: 15).foregroundStyle(theme.inkSecondary)
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("haptics.latency")
    }

    private var label: String {
        guard let last = lab.lastLatency, let average = lab.averageLatency else { return "Press anything" }
        return String(format: "%.1f ms · %.1f", last, average)
    }
}

/// A row of chips choosing one of `items`.
private struct PlaygroundChips<Item: Identifiable & Hashable>: View {
    let items: [Item]
    let title: (Item) -> String
    @Binding var selection: Item

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(items) { item in
                        FilterChip(title: title(item), selected: item == selection) {
                            withAnimation(.smooth(duration: 0.2)) { selection = item }
                        }
                        .id(item.id)
                    }
                }
                .padding(.vertical, 2)
            }
            // The chosen chip in view, even when it isn't the first.
            .onAppear { proxy.scrollTo(selection.id, anchor: .center) }
        }
    }
}

private struct PlaygroundController: View {
    @Environment(\.rommioTheme) private var theme
    let lab: HapticsLab
    @State private var console = ConsoleFeel.n64

    var body: some View {
        ThemedGroup(title: "Feel a controller", footer: summary) {
            VStack(alignment: .leading, spacing: 14) {
                PlaygroundChips(items: ConsoleFeel.all, title: \.name, selection: $console)
                ControllerPad(console: console, lab: lab)
                    .frame(maxWidth: .infinity)
                    .id(console.id)
            }
            .padding(.vertical, 6)
        }
    }

    private var summary: String {
        var parts = ["Face buttons: \(console.face.first?.material.name ?? "")", "d-pad: \(console.dpad.name)"]
        let shoulderNames = Array(Set(console.shoulders.map(\.material.name))).sorted()
        if !shoulderNames.isEmpty { parts.append("shoulders: \(shoulderNames.joined(separator: ", "))") }
        if let stick = console.stick { parts.append("stick: \(stick.name)") }
        return parts.joined(separator: " · ") + ". Every control plays its press and its release."
    }
}

private struct PlaygroundRumble: View {
    @Environment(\.rommioTheme) private var theme
    @Bindable var lab: HapticsLab

    var body: some View {
        VStack(alignment: .leading, spacing: theme.spacing.section * 0.6) {
            ThemedGroup(title: "Rumble", footer: rendererNote) {
                VStack(alignment: .leading, spacing: 12) {
                    Picker("Renderer", selection: $lab.renderer) {
                        ForEach(RumbleRenderer.allCases) { Text($0.title).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    MotorPad(lab: lab)
                }
                .padding(.vertical, 6)
            }
            ThemedGroup(title: "Rumble scenes", detail: "\(RumbleScene.all.count)",
                        footer: "Recorded the way each core reports: most only when a motor changes, mGBA every frame.") {
                ForEach(RumbleScene.all) { scene in
                    SceneRow(scene: scene, lab: lab)
                }
            }
        }
    }

    private var rendererNote: String {
        switch lab.renderer {
        case .twoVoices:
            "Two voices: a deep rumble for the strong motor and a separate buzz for the weak one (at most 80%), each spinning up and winding down like a motor, with a kick when one starts hard."
        case .blend:
            "Blend: both motors as one continuous rumble, as strong as the two together and sliding from deep to buzzy with the mix, with the same spin-up and wind-down."
        case .oneTap:
            "Today: the player turns every rumble report into one tap. A held rumble is a single tap; a core that reports every frame becomes a stream of taps."
        }
    }
}

private struct SceneRow: View {
    @Environment(\.rommioTheme) private var theme
    let scene: RumbleScene
    let lab: HapticsLab

    var body: some View {
        let playing = lab.playingScene == scene.id
        Button {
            if playing { lab.stopRumble() } else { lab.play(scene) }
        } label: {
            HStack(alignment: .center, spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(scene.title).themeText(.bodyEmphasis, size: 16).foregroundStyle(theme.ink)
                    Text(scene.source).themeText(.secondary, size: 13).foregroundStyle(theme.inkSecondary)
                    Text(scene.detail).themeText(.caption).foregroundStyle(theme.inkTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 8)
                if playing {
                    MotorMeters(lab: lab)
                } else {
                    Image(systemName: "play.fill")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(theme.inkSecondary)
                        .frame(width: 22, height: 30)
                }
            }
        }
        .buttonStyle(RowButtonStyle())
        .accessibilityIdentifier("haptics.scene.\(scene.id)")
    }
}

private struct PlaygroundMaterials: View {
    @Environment(\.rommioTheme) private var theme
    let lab: HapticsLab

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionHeader(title: "Button materials", detail: "\(ButtonMaterial.all.count)")
                .padding(.horizontal, theme.isGlass ? 6 : 0)
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
                ForEach(ButtonMaterial.all) { material in
                    MaterialPad(material: material, lab: lab)
                }
            }
            Text("Hold to feel the press, lift to feel the release.")
                .themeText(.caption)
                .foregroundStyle(theme.inkSecondary)
                .padding(.horizontal, theme.isGlass ? 6 : 0)
        }
    }
}

private struct PlaygroundDpads: View {
    let lab: HapticsLab
    @State private var feel = DpadFeel.nintendoCross

    var body: some View {
        ThemedGroup(title: "D-pads", detail: "\(DpadFeel.all.count)", footer: feel.source + ". Roll your thumb around: diagonals feel different.") {
            VStack(spacing: 16) {
                PlaygroundChips(items: DpadFeel.all, title: \.name, selection: $feel)
                DpadPad(feel: feel, lab: lab, size: 168)
                    .frame(maxWidth: .infinity)
            }
            .padding(.vertical, 6)
        }
    }
}

private struct PlaygroundSticks: View {
    @Environment(\.rommioTheme) private var theme
    @Bindable var lab: HapticsLab
    @State private var feel = StickFeel.n64Worn
    @State private var texture = RimTexture.designed

    var body: some View {
        ThemedGroup(title: "Sticks", detail: "\(StickFeel.all.count)",
                    footer: feel.source + (texture == .designed ? "" : "; rim: \(texture.source.lowercased())")
                        + ". Flick to the rim or ease out to it; push past it and circle the gate, slowly and fast.") {
            VStack(spacing: 16) {
                PlaygroundChips(items: StickFeel.all, title: \.name, selection: $feel)
                StickPad(feel: feel.textured(texture), lab: lab, size: 210)
                    .frame(maxWidth: .infinity)
                StickReadout(lab: lab)
                VStack(alignment: .leading, spacing: 8) {
                    Text("Rim texture").themeText(.caption).foregroundStyle(theme.inkSecondary)
                    PlaygroundChips(items: RimTexture.all, title: \.name, selection: $texture)
                }
            }
            .padding(.vertical, 6)
            SliderRow(title: "Full-strength hit", value: $lab.stickTuning.fullHit, range: 8...80,
                      label: "\(Int(lab.stickTuning.fullHit.rounded())) radii/s")
            SliderRow(title: "Full push", value: $lab.stickTuning.fullPush, range: 0.3...1.5,
                      label: String(format: "%.2f past the rim", lab.stickTuning.fullPush))
            SliderRow(title: "Contact floor", value: $lab.stickTuning.contactFloor, range: 0...0.4,
                      label: "\(Int((lab.stickTuning.contactFloor * 100).rounded()))%")
        }
    }
}

/// What the stick measured: the last arrival at the rim, and the push and glide now.
private struct StickReadout: View {
    @Environment(\.rommioTheme) private var theme
    let lab: HapticsLab

    var body: some View {
        HStack(spacing: 18) {
            value("Last hit", lab.stickImpact)
            value("Push", lab.stickForce)
            value("Glide", lab.stickGlide)
        }
        .frame(maxWidth: .infinity)
    }

    private func value(_ title: String, _ level: Double?) -> some View {
        VStack(spacing: 2) {
            Text(level.map { "\(Int(($0 * 100).rounded()))%" } ?? "–")
                .themeText(.numeral, size: 17).foregroundStyle(theme.ink)
            Text(title).themeText(.caption).foregroundStyle(theme.inkSecondary)
        }
        .frame(minWidth: 64)
    }
}
