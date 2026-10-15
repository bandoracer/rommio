import SwiftUI
import RommioPlayerKit

// The haptics playground's touch surfaces: buttons, a d-pad, a stick and the motor pad.
// Each plays its feel from the finger's first contact, with touches straight from UIKit
// (`padTouch`), and notes how long the haptic took from the touch (`noteLatency`).

/// Pad colours: a quiet fill at rest, the accent while held (Aurora); paper and ink (Riso).
private struct PadStyle {
    let theme: RommioTheme
    func fill(_ pressed: Bool) -> Color {
        if theme.isRiso { return pressed ? theme.ink : .clear }
        return pressed ? theme.buttonFill : theme.surfaceStrong
    }
    func ink(_ pressed: Bool) -> Color {
        if theme.isRiso { return pressed ? theme.ground : theme.ink }
        return pressed ? theme.buttonInk : theme.ink
    }
    var stroke: Color { theme.isRiso ? theme.ink : theme.hairline }
    var strokeWidth: CGFloat { theme.isRiso ? 1.5 : 1 }
}

/// A button: plays its material's press on touch-down and its release on lift.
struct PressPad: View {
    enum Shape: Equatable {
        case round(CGFloat)
        case pill(width: CGFloat, height: CGFloat)
    }

    @Environment(\.rommioTheme) private var theme
    let label: String
    let material: ButtonMaterial
    let lab: HapticsLab
    var shape: Shape = .round(52)
    @State private var pressed = false

    var body: some View {
        let style = PadStyle(theme: theme)
        Text(label)
            .themeText(.bodyEmphasis, size: label.count > 2 ? 12 : 17)
            .foregroundStyle(style.ink(pressed))
            .frame(width: width, height: height)
            .background {
                switch shape {
                case .round:
                    Circle().fill(style.fill(pressed))
                    Circle().strokeBorder(style.stroke, lineWidth: style.strokeWidth)
                case .pill:
                    Capsule().fill(style.fill(pressed))
                    Capsule().strokeBorder(style.stroke, lineWidth: style.strokeWidth)
                }
            }
            .scaleEffect(pressed ? 0.94 : 1)
            .animation(.easeOut(duration: 0.08), value: pressed)
            .contentShape(Rectangle())
            .padTouch { event in
                switch event.phase {
                case .began:
                    guard !pressed else { return }
                    pressed = true
                    lab.play(material.press)
                    lab.noteLatency(since: event.timestamp)
                case .moved:
                    break
                case .ended:
                    guard pressed else { return }
                    pressed = false
                    lab.play(material.release)
                case .cancelled:
                    pressed = false
                }
            }
            .accessibilityLabel(label)
            .accessibilityAddTraits(.isButton)
    }

    private var width: CGFloat {
        switch shape {
        case let .round(size): size
        case let .pill(width, _): width
        }
    }

    private var height: CGFloat {
        switch shape {
        case let .round(size): size
        case let .pill(_, height): height
        }
    }
}

/// A material as a card to press: its name and where it comes from.
struct MaterialPad: View {
    @Environment(\.rommioTheme) private var theme
    let material: ButtonMaterial
    let lab: HapticsLab
    @State private var pressed = false
    @State private var intent = PressIntent()
    @State private var hold: Task<Void, Never>?
    @State private var touchTime: TimeInterval = 0

    var body: some View {
        let style = PadStyle(theme: theme)
        VStack(alignment: .leading, spacing: 4) {
            Text(material.name).themeText(.bodyEmphasis, size: 15).foregroundStyle(style.ink(pressed))
            Text(material.source).themeText(.caption).foregroundStyle(style.ink(pressed).opacity(0.75))
                .lineLimit(3).fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(12)
        .frame(maxWidth: .infinity, minHeight: 104, alignment: .topLeading)
        .background {
            RoundedRectangle(cornerRadius: theme.isRiso ? 0 : 16, style: .continuous).fill(style.fill(pressed))
            RoundedRectangle(cornerRadius: theme.isRiso ? 0 : 16, style: .continuous).strokeBorder(style.stroke, lineWidth: style.strokeWidth)
        }
        .scaleEffect(pressed ? 0.97 : 1)
        .animation(.easeOut(duration: 0.08), value: pressed)
        .contentShape(Rectangle())
        // Alongside the page's scrolling, not instead of it (the cards fill the page's
        // width): a touch that starts a scroll never presses (revised 2026-09-28: the cards
        // captured every touch, so the grid couldn't be scrolled).
        .padTouch(.share) { event in
            switch event.phase {
            case .began:
                intent.begin()
                touchTime = event.timestamp
                hold?.cancel()
                hold = Task { @MainActor in
                    try? await Task.sleep(for: .milliseconds(Int(PressIntent.hold * 1000)))
                    guard !Task.isCancelled else { return }
                    run(intent.holdElapsed())
                }
            case .moved:
                intent.moved(event.distance)
            case .ended:
                hold?.cancel()
                run(intent.end(moved: event.distance))
            case .cancelled:
                hold?.cancel()
                run(intent.end(moved: .infinity))
            }
            pressed = intent.phase == .pressed
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier("haptics.material.\(material.id)")
    }

    private func run(_ effects: [PressIntent.Effect]) {
        for effect in effects {
            switch effect {
            case .press:
                lab.play(material.press)
                lab.noteLatency(since: touchTime)
            case .release:
                lab.play(material.release)
            case .releaseAfterTap:
                // A tap shorter than the hold: the press now, the release a moment later.
                pressed = true
                Task { @MainActor in
                    try? await Task.sleep(for: .milliseconds(60))
                    pressed = false
                    lab.play(material.release)
                }
            }
        }
        pressed = intent.phase == .pressed
    }
}

/// A press that shares the touch with scrolling (pure: the pad plays the effects). The
/// press waits `hold` seconds in case the finger is starting a scroll; moving past `slop`
/// points first makes it a scroll, which never presses, and a scroll that takes over a held
/// press lets go without the release.
struct PressIntent: Equatable, Sendable {
    enum Phase: Equatable, Sendable { case idle, waiting, pressed, scrolling }
    enum Effect: Equatable, Sendable { case press, release, releaseAfterTap }

    /// 12 ms (was 35: the largest part of a card's delay; a flick starting a scroll still
    /// passes the slop sooner).
    static let hold = 0.012
    static let slop = 8.0

    private(set) var phase = Phase.idle

    mutating func begin() {
        phase = .waiting
    }

    mutating func holdElapsed() -> [Effect] {
        guard phase == .waiting else { return [] }
        phase = .pressed
        return [.press]
    }

    mutating func moved(_ distance: Double) {
        if distance > Self.slop, phase == .waiting || phase == .pressed { phase = .scrolling }
    }

    mutating func end(moved distance: Double) -> [Effect] {
        defer { phase = .idle }
        guard distance <= Self.slop else { return [] }
        switch phase {
        case .pressed: return [.release]
        case .waiting: return [.press, .releaseAfterTap]
        case .idle, .scrolling: return []
        }
    }
}

/// A d-pad to roll a thumb around: a tap entering each direction, another feel for the
/// diagonals, and one coming back to rest.
struct DpadPad: View {
    @Environment(\.rommioTheme) private var theme
    let feel: DpadFeel
    let lab: HapticsLab
    var size: CGFloat = 132
    @State private var tracker = DpadTracker()

    var body: some View {
        let style = PadStyle(theme: theme)
        let arm = size * 0.34
        ZStack {
            if feel.id == DpadFeel.rockingDisc.id || feel.id == DpadFeel.arcadeStick.id {
                Circle().fill(style.fill(false))
                Circle().strokeBorder(style.stroke, lineWidth: style.strokeWidth)
            } else {
                DpadCross(arm: arm).fill(style.fill(false))
                DpadCross(arm: arm).stroke(style.stroke, lineWidth: style.strokeWidth)
            }
            let vector = tracker.direction.vector
            Circle()
                .fill(style.fill(true))
                .frame(width: arm * 0.8, height: arm * 0.8)
                .offset(x: vector.x * size * 0.33, y: vector.y * size * 0.33)
                .opacity(tracker.direction == .none ? 0 : 1)
        }
        .frame(width: size, height: size)
        .contentShape(Rectangle())
        .padTouch { event in
            switch event.phase {
            case .began, .moved:
                let radius = Double(size / 2)
                let recipes = tracker.update(x: Double(event.location.x) / radius - 1, y: Double(event.location.y) / radius - 1, feel: feel)
                recipes.forEach(lab.play)
                if !recipes.isEmpty { lab.noteLatency(since: event.timestamp) }
            case .ended, .cancelled:
                tracker.release(feel: feel).forEach(lab.play)
            }
        }
        .onChange(of: feel) { _, _ in tracker = DpadTracker() }
        .accessibilityLabel("D-pad")
    }
}

/// A plus: two rounded bars, as one outline.
private struct DpadCross: Shape {
    let arm: CGFloat

    func path(in rect: CGRect) -> Path {
        let corner = CGSize(width: arm * 0.22, height: arm * 0.22)
        let vertical = Path(roundedRect: CGRect(x: rect.midX - arm / 2, y: rect.minY, width: arm, height: rect.height), cornerSize: corner)
        let horizontal = Path(roundedRect: CGRect(x: rect.minX, y: rect.midY - arm / 2, width: rect.width, height: arm), cornerSize: corner)
        return vertical.union(horizontal)
    }
}

/// A stick in its gate: the ring drawn in the gate's shape, the knob following the thumb,
/// and the feel's texture played as it moves (grit or ticks along the rim, notches, full
/// tilt, the spring back to the centre, a hum while it slides).
struct StickPad: View {
    @Environment(\.rommioTheme) private var theme
    let feel: StickFeel
    let lab: HapticsLab
    var size: CGFloat = 180
    @State private var knob = CGPoint.zero
    @State private var texture = StickTexture()
    @State private var humToken = 0
    /// When the last touch update came, for spreading scattered grit until the next one.
    @State private var lastTouch: TimeInterval?
    @State private var touchInterval = 1.0 / 120

    var body: some View {
        let style = PadStyle(theme: theme)
        let knobSize = size * 0.34
        let radius = (size - knobSize) / 2
        let ring = CGRect(x: knobSize / 2, y: knobSize / 2, width: radius * 2, height: radius * 2)
        ZStack {
            Path(PlayerGatePath.path(in: ring, reach: CGFloat(feel.profile.diagonalReach)))
                .fill(style.fill(false))
            Path(PlayerGatePath.path(in: ring, reach: CGFloat(feel.profile.diagonalReach)))
                .stroke(style.stroke, lineWidth: style.strokeWidth)
            Circle()
                .fill(style.fill(knob != .zero))
                .overlay { Circle().strokeBorder(style.stroke, lineWidth: style.strokeWidth) }
                .frame(width: knobSize, height: knobSize)
                .offset(x: knob.x * radius, y: knob.y * radius)
        }
        .frame(width: size, height: size)
        .contentShape(Rectangle())
        .padTouch { event in
            switch event.phase {
            case .began, .moved: move(event.location, velocity: event.velocity, at: event.timestamp, radius: radius)
            case .ended, .cancelled: release()
            }
        }
        .onChange(of: feel) { _, _ in release() }
        .accessibilityLabel("Stick")
    }

    private func move(_ location: CGPoint, velocity: CGSize, at timestamp: TimeInterval, radius: CGFloat) {
        if let lastTouch { touchInterval = min(max(timestamp - lastTouch, 0.004), 0.02) }
        lastTouch = timestamp
        let feel = lab.noiseTuned(feel)
        // Full tilt at the ring: the finger's offset (and speed) scaled to the profile's travel.
        let scale = feel.profile.travel / Double(radius)
        let dx = Double(location.x - size / 2) * scale
        let dy = Double(location.y - size / 2) * scale
        for cue in texture.update(dx: dx, dy: dy, vx: Double(velocity.width) * scale, vy: Double(velocity.height) * scale,
                                  feel: feel, tuning: lab.stickTuning) {
            play(cue)
        }
        lab.readStick(texture)
        let output = PlayerStickModel.output(dx: dx, dy: dy, feel.profile)
        knob = CGPoint(x: output.x, y: output.y)
        if let hum = feel.hum {
            lab.hum(hum, level: texture.humLevel(hum, tuning: lab.stickTuning))
        }
        if let bed = feel.bed {
            let noise = lab.noiseTuning
            lab.bed(bed, level: bed.level(rimSpeed: texture.rimSpeed, force: texture.force,
                                          floor: lab.stickTuning.contactFloor * 0.8, gain: noise.gain),
                    rate: bed.rate(rimSpeed: texture.rimSpeed, response: noise.speedResponse))
        }
        if feel.hum != nil || feel.bed != nil {
            // The finger may stop without another update: the hum and the bed fade out after a pause.
            humToken += 1
            let token = humToken
            Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(70))
                guard token == humToken else { return }
                if let hum = feel.hum { lab.hum(hum, level: 0) }
                if let bed = feel.bed { lab.bed(bed, level: 0, rate: 1) }
            }
        }
    }

    private func release() {
        for cue in texture.release() { play(cue) }
        lastTouch = nil
        lab.hum(nil, level: 0)
        lab.bed(nil, level: 0, rate: 1)
        withAnimation(.spring(duration: 0.18, bounce: 0.35)) { knob = .zero }
    }

    private func play(_ cue: StickTexture.Cue) {
        switch cue {
        // A slow arrival is soft and dull, a fast one full and a little sharper.
        case let .edge(impact): lab.play(feel.edge.scaled(intensity: 0.2 + 0.8 * impact, sharpness: (impact - 0.5) * 0.6))
        case let .notch(force, glide):
            lab.play(feel.notch.scaled(intensity: 0.4 + 0.35 * force + 0.35 * glide, sharpness: 0.1 * force + 0.15 * glide - 0.1))
        case let .center(strength): lab.play(feel.center.scaled(intensity: strength))
        case let .grain(intensity, sharpness): lab.play(.tap(intensity, sharpness))
        // Spread over the time to the next touch update, so the grit isn't on the touch clock.
        case let .scatter(grains):
            let gain = lab.noiseTuning.gain
            lab.playOnce(HapticRecipe(events: grains.map {
                HapticRecipe.Event(at: $0.offset * touchInterval, intensity: min($0.intensity * gain, 1), sharpness: $0.sharpness)
            }))
        }
    }
}

/// Both motors under one finger: up for the strong motor's rumble, right for the weak
/// motor's buzz.
struct MotorPad: View {
    @Environment(\.rommioTheme) private var theme
    let lab: HapticsLab
    @State private var point: CGPoint?

    var body: some View {
        let style = PadStyle(theme: theme)
        GeometryReader { proxy in
            let size = proxy.size
            ZStack(alignment: .bottomLeading) {
                RoundedRectangle(cornerRadius: theme.isRiso ? 0 : 18, style: .continuous).fill(style.fill(false))
                RoundedRectangle(cornerRadius: theme.isRiso ? 0 : 18, style: .continuous).strokeBorder(style.stroke, lineWidth: style.strokeWidth)
                MotorGrid().stroke(style.stroke.opacity(0.6), lineWidth: 0.5)
                VStack(alignment: .leading, spacing: 2) {
                    Text("↑ Rumble (strong motor)").themeText(.caption)
                    Text("→ Buzz (weak motor)").themeText(.caption)
                }
                .foregroundStyle(theme.inkSecondary)
                .padding(12)
                if let point {
                    Circle().fill(style.fill(true)).frame(width: 34, height: 34)
                        .position(point)
                }
            }
            .contentShape(Rectangle())
            .padTouch { event in
                switch event.phase {
                case .began, .moved:
                    let x = min(max(event.location.x, 0), size.width)
                    let y = min(max(event.location.y, 0), size.height)
                    point = CGPoint(x: x, y: y)
                    lab.drive(strong: Double(1 - y / size.height), weak: Double(x / size.width))
                case .ended, .cancelled:
                    point = nil
                    lab.releaseMotors()
                }
            }
        }
        .frame(height: 190)
        .accessibilityLabel("Motor pad")
    }
}

private struct MotorGrid: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        for step in 1..<4 {
            let x = rect.width * CGFloat(step) / 4, y = rect.height * CGFloat(step) / 4
            path.move(to: CGPoint(x: x, y: 0)); path.addLine(to: CGPoint(x: x, y: rect.height))
            path.move(to: CGPoint(x: 0, y: y)); path.addLine(to: CGPoint(x: rect.width, y: y))
        }
        return path
    }
}

/// The strong and weak motors' levels as two bars.
struct MotorMeters: View {
    @Environment(\.rommioTheme) private var theme
    let lab: HapticsLab

    var body: some View {
        HStack(alignment: .bottom, spacing: 4) {
            bar(lab.meterStrong)
            bar(lab.meterWeak)
        }
        .frame(width: 22, height: 30)
        .accessibilityHidden(true)
    }

    private func bar(_ level: Double) -> some View {
        VStack {
            Spacer(minLength: 0)
            RoundedRectangle(cornerRadius: 2).fill(theme.isRiso ? theme.ink : theme.tint)
                .frame(height: max(2, 30 * level))
        }
        .frame(width: 9)
    }
}

/// A console's pad: shoulders on top, the stick and d-pad on the left, face buttons on
/// the right, Start and Select below.
struct ControllerPad: View {
    let console: ConsoleFeel
    let lab: HapticsLab

    var body: some View {
        VStack(spacing: 18) {
            if !console.shoulders.isEmpty {
                HStack(spacing: 10) {
                    ForEach(Array(console.shoulders.enumerated()), id: \.offset) { _, button in
                        PressPad(label: button.label, material: button.material, lab: lab, shape: .pill(width: 64, height: 38))
                    }
                }
            }
            HStack(alignment: .center, spacing: 12) {
                VStack(spacing: 14) {
                    if let stick = console.stick { StickPad(feel: stick, lab: lab, size: 128) }
                    DpadPad(feel: console.dpad, lab: lab, size: 118)
                }
                Spacer(minLength: 0)
                FaceCluster(buttons: console.face, lab: lab)
            }
            if !console.small.isEmpty {
                HStack(spacing: 14) {
                    ForEach(Array(console.small.enumerated()), id: \.offset) { _, button in
                        PressPad(label: button.label, material: button.material, lab: lab, shape: .pill(width: 70, height: 30))
                    }
                }
            }
        }
        .padding(.vertical, 6)
    }
}

/// Face buttons: four in a diamond (left, top, bottom, right), otherwise a row rising to
/// the right.
private struct FaceCluster: View {
    let buttons: [ConsoleButton]
    let lab: HapticsLab

    var body: some View {
        if buttons.count == 4 {
            let offsets: [CGSize] = [CGSize(width: -44, height: 0), CGSize(width: 0, height: -44),
                                     CGSize(width: 0, height: 44), CGSize(width: 44, height: 0)]
            ZStack {
                ForEach(Array(buttons.enumerated()), id: \.offset) { index, button in
                    PressPad(label: button.label, material: button.material, lab: lab, shape: .round(50))
                        .offset(offsets[index])
                }
            }
            .frame(width: 150, height: 150)
        } else {
            HStack(alignment: .bottom, spacing: 10) {
                ForEach(Array(buttons.enumerated()), id: \.offset) { index, button in
                    PressPad(label: button.label, material: button.material, lab: lab,
                             shape: .round(button.material == .cButton ? 42 : 56))
                        .padding(.bottom, CGFloat(index) * 16)
                }
            }
        }
    }
}
