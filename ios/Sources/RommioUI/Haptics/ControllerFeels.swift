import Foundation
import RommioPlayerKit

// Controller feel, designed per console: what a button, a d-pad and a stick feel like.
// Values are intensity (I) and sharpness (S) in 0…1; sharpness low is a deep thud, high a
// crisp tick. Presses are built from a few parts: the stroke (a short buzz for a soft
// dome, nothing for a snap switch), the click (a tap), and the bottom-out (a second, duller
// tap a few milliseconds later). Releases are lighter and sharper than presses, as a real
// switch's return is. Every press starts with a crisp contact at 0 (revised 2026-09-28): a
// press that opens with a deep buzz or a click 15–20 ms in reads as late, since a low
// sharpness takes a full cycle of its ~80 Hz drive to be felt. Tuned by feel in Settings ›
// Haptics playground.

/// A button's material: what its press and release are made of.
struct ButtonMaterial: Identifiable, Hashable, Sendable {
    let id: String
    let name: String
    /// Where it comes from, in a few words.
    let source: String
    let press: HapticRecipe
    let release: HapticRecipe

    static let rubberDome = ButtonMaterial(
        id: "rubber-dome", name: "Rubber dome", source: "SNES and Genesis face buttons: a soft dome collapsing",
        press: .tap(0.3, 0.55) + .buzz(0.36, 0.16, for: 0.022, attack: 0.002, decay: 0.016) + .tap(0.5, 0.22, at: 0.008),
        release: .tap(0.2, 0.28)).voiced(.domeThup, 0.5, at: 0.006, release: .domeRelease, 0.35)
    static let hardSquare = ButtonMaterial(
        id: "hard-square", name: "Hard square", source: "NES A and B: hard plastic on a membrane",
        press: .tap(0.62, 0.48) + .tap(0.24, 0.18, at: 0.016),
        release: .tap(0.3, 0.52)).voiced(.plasticTock, 0.5, release: .plasticTockRelease, 0.4)
    static let gameBoyDome = ButtonMaterial(
        id: "game-boy-dome", name: "Snappy dome", source: "Game Boy A and B: rubber with a snap at the bottom",
        press: .tap(0.3, 0.7) + .buzz(0.2, 0.2, for: 0.01, attack: 0.002) + .tap(0.62, 0.6, at: 0.006),
        release: .tap(0.3, 0.66)).voiced(.snapPing, 0.45, at: 0.006, release: .plasticTockRelease, 0.3)
    static let tactSwitch = ButtonMaterial(
        id: "tact-switch", name: "Tact switch", source: "Game Boy Advance, DS and PSP: a crisp micro-switch",
        press: .tap(0.85, 0.92),
        release: .tap(0.42, 1)).voiced(.tactTic, 0.5, release: .tactTicRelease, 0.4)
    static let n64Face = ButtonMaterial(
        id: "n64-face", name: "Big convex", source: "N64 A and B: a short, firm stroke",
        press: .tap(0.72, 0.5) + .buzz(0.3, 0.3, for: 0.016, at: 0.004, decay: 0.012),
        release: .tap(0.3, 0.55)).voiced(.clack, 0.5, release: .plasticTockRelease, 0.3)
    static let cButton = ButtonMaterial(
        id: "c-button", name: "Shallow", source: "N64 C buttons: small and quick",
        press: .tap(0.5, 0.72),
        release: .tap(0.2, 0.82)).voiced(.shallowTic, 0.45, release: .tactTicRelease, 0.25)
    static let dualShockFace = ButtonMaterial(
        id: "dualshock-face", name: "Soft travel", source: "PlayStation face buttons: travel, then the bottom",
        press: .tap(0.5, 0.5) + .tap(0.42, 0.14, at: 0.016),
        release: .tap(0.24, 0.44)).voiced(.thock, 0.5, release: .domeRelease, 0.3)
    static let shoulderClick = ButtonMaterial(
        id: "shoulder-click", name: "Hinge click", source: "SNES, GBA and DS shoulders: a loud hinge",
        press: .tap(0.95, 0.78) + .tap(0.34, 0.5, at: 0.009),
        release: .tap(0.55, 0.9)).voiced(.hingeClick, 0.55, release: .tactTicRelease, 0.45)
    static let longTrigger = ButtonMaterial(
        id: "long-trigger", name: "Long trigger", source: "N64 Z, PlayStation L2 and R2: travel, then the break",
        press: HapticRecipe.tap(0.25, 0.7) + HapticRecipe.buzz(1, 0.3, for: 0.04).curve(.intensity, [(0, 0.1), (0.04, 0.5)])
            + .tap(0.9, 0.7, at: 0.04),
        release: HapticRecipe.buzz(1, 0.3, for: 0.04).curve(.intensity, [(0, 0.4), (0.04, 0)]) + .tap(0.2, 0.55)).voiced(.hingeClick, 0.5, at: 0.04, release: .tactTicRelease, 0.3).voiced(.swish, 0.3, release: .swish, 0.2)
    static let startSelect = ButtonMaterial(
        id: "start-select", name: "Rubber pill", source: "Start and Select: soft and a little mushy",
        press: .tap(0.22, 0.5) + .tap(0.3, 0.14, at: 0.004) + .buzz(0.2, 0.08, for: 0.026, attack: 0.004, decay: 0.018),
        release: .tap(0.14, 0.2)).voiced(.pillPuff, 0.4, release: .puff, 0.25)
    static let arcade = ButtonMaterial(
        id: "arcade", name: "Arcade microswitch", source: "Arcade and Neo Geo: two contacts, loud",
        press: .tap(1, 1) + .tap(0.5, 0.9, at: 0.006),
        release: .tap(0.62, 1) + .tap(0.2, 0.9, at: 0.005)).voiced(.microswitch, 0.6, release: .microswitchRelease, 0.5)
    static let clickyKey = ButtonMaterial(
        id: "clicky-key", name: "Clicky key", source: "A mechanical keyboard switch: the bump, then the click",
        press: .tap(0.38, 0.45) + .tap(0.9, 1, at: 0.009),
        release: .tap(0.5, 0.95)).voiced(.blueClick, 0.55, release: .microswitchRelease, 0.35)
    static let bigButton = ButtonMaterial(
        id: "big-button", name: "Big button", source: "Heavy and deliberate: a nudge, then the thunk",
        press: .tap(0.3, 0.65) + .tap(1, 0.22, at: 0.02) + .buzz(0.5, 0.1, for: 0.06, at: 0.02, decay: 0.05),
        release: .tap(0.3, 0.2)).voiced(.thunk, 0.6, at: 0.02, release: .domeRelease, 0.3)
    static let wornRubber = ButtonMaterial(
        id: "worn-rubber", name: "Worn rubber", source: "A well-loved pad: soft, no click at all",
        press: .tap(0.18, 0.45) + .buzz(0.32, 0.06, for: 0.036, attack: 0.006, decay: 0.026),
        release: .empty).voiced(.puff, 0.3)
    static let glass = ButtonMaterial(
        id: "glass", name: "Glass", source: "Barely there: a tick under the finger",
        press: .tap(0.32, 1),
        release: .empty).voiced(.tink, 0.35)
    static let today = ButtonMaterial(
        id: "today", name: "Today", source: "How the player's buttons feel now: one light tap",
        press: .tap(0.75, 0.55),
        release: .empty)

    /// The material with sounds on its press (at `at`) and release.
    func voiced(_ press: SoundRecipe, _ pressVolume: Double, at: Double = 0,
                release: SoundRecipe? = nil, _ releaseVolume: Double = 0) -> ButtonMaterial {
        ButtonMaterial(id: id, name: name, source: source,
                       press: self.press.sound(press, volume: pressVolume, at: at),
                       release: release.map { self.release.sound($0, volume: releaseVolume) } ?? self.release)
    }

    static let all: [ButtonMaterial] = [
        rubberDome, hardSquare, gameBoyDome, tactSwitch, n64Face, cButton, dualShockFace, shoulderClick, longTrigger,
        startSelect, arcade, clickyKey, bigButton, wornRubber, glass, today,
    ]
}

/// A d-pad's feel: how it reads the thumb (`model`, `deadZone`), and entering a direction,
/// entering a diagonal, and coming back to rest.
struct DpadFeel: Identifiable, Hashable, Sendable {
    enum Model: Hashable, Sendable {
        /// One piece on a pivot: the direction from the thumb's angle. `diagonal` is each
        /// diagonal's sector in degrees (45 splits evenly; narrower makes diagonals
        /// deliberate, so a side-to-side sweep that wobbles stays side to side).
        case pivot(diagonal: Double)
        /// Four switches, one per direction, each closing past its throw and clicking open
        /// on its own: an arcade stick's microswitches, the PlayStation's separate arms. A
        /// diagonal is two switches; `direction` plays as one closes, `center` as one opens.
        case switches
    }

    let id: String
    let name: String
    let source: String
    var direction: HapticRecipe
    var diagonal: HapticRecipe
    var center: HapticRecipe
    var model: Model = .pivot(diagonal: 30)
    /// How far (share of the pad's radius) the thumb leaves the centre before a direction
    /// engages; for switches, each axis's throw (revised 2026-09-28: was 0.24, too twitchy).
    var deadZone = 0.36

    static let nintendoCross = DpadFeel(
        id: "nintendo-cross", name: "Nintendo cross", source: "NES, SNES and Game Boy: a cross on a pivot",
        direction: .tap(0.55, 0.7),
        diagonal: .tap(0.42, 0.6) + .tap(0.42, 0.6, at: 0.012),
        center: .tap(0.18, 0.45)).voiced(.plasticTock, 0.3, release: .plasticTockRelease, 0.2)
    static let n64Plus = DpadFeel(
        id: "n64-plus", name: "Stiff plus", source: "N64: a stiff, precise cross",
        direction: .tap(0.6, 0.62) + .tap(0.2, 0.3, at: 0.01),
        diagonal: .tap(0.5, 0.6) + .tap(0.45, 0.55, at: 0.015),
        center: .tap(0.2, 0.4),
        model: .pivot(diagonal: 26)).voiced(.shallowTic, 0.32, release: .tactTicRelease, 0.2)
    static let rockingDisc = DpadFeel(
        id: "rocking-disc", name: "Rocking disc", source: "Genesis: a round pad that rolls between directions",
        direction: .tap(0.25, 0.55) + .buzz(0.38, 0.28, for: 0.016, decay: 0.01),
        diagonal: .tap(0.3, 0.32),
        center: .tap(0.12, 0.3),
        model: .pivot(diagonal: 34), deadZone: 0.44).voiced(.thock, 0.25, release: .domeRelease, 0.15)
    static let splitArms = DpadFeel(
        id: "split-arms", name: "Split arms", source: "PlayStation: four separate arms, a diagonal is two presses",
        direction: .tap(0.5, 0.45) + .tap(0.28, 0.2, at: 0.016),
        diagonal: .tap(0.5, 0.45) + .tap(0.28, 0.2, at: 0.016) + .tap(0.5, 0.45, at: 0.028) + .tap(0.28, 0.2, at: 0.044),
        center: .tap(0.22, 0.5),
        model: .switches, deadZone: 0.42).voiced(.thock, 0.35, release: .domeRelease, 0.25)
    static let tinyTact = DpadFeel(
        id: "tiny-tact", name: "Tiny tact", source: "Game Boy Advance and DS: small and clicky",
        direction: .tap(0.75, 0.95),
        diagonal: .tap(0.55, 0.95) + .tap(0.55, 0.95, at: 0.008),
        center: .tap(0.25, 1),
        deadZone: 0.34).voiced(.tactTic, 0.4, release: .tactTicRelease, 0.3)
    static let arcadeStick = DpadFeel(
        id: "arcade-stick", name: "Arcade stick", source: "A bat-top stick on four microswitches: each clicks on and off by itself",
        direction: .tap(0.9, 1) + .tap(0.4, 0.9, at: 0.006),
        diagonal: .tap(0.9, 1) + .tap(0.4, 0.9, at: 0.006) + .tap(0.9, 1, at: 0.02) + .tap(0.4, 0.9, at: 0.026),
        center: .tap(0.5, 1),
        model: .switches, deadZone: 0.46).voiced(.microswitch, 0.5, release: .microswitchRelease, 0.45)
    static let today = DpadFeel(
        id: "today", name: "Today", source: "How the player's d-pad feels now: a selection tick",
        direction: .tap(0.42, 0.85),
        diagonal: .tap(0.42, 0.85),
        center: .empty,
        model: .pivot(diagonal: 45))

    /// The d-pad with a sound entering a direction (twice for a diagonal, a switch at a time)
    /// and one coming back to rest.
    func voiced(_ sound: SoundRecipe, _ volume: Double, release: SoundRecipe? = nil, _ releaseVolume: Double = 0) -> DpadFeel {
        var copy = self
        copy.direction = direction.sound(sound, volume: volume)
        copy.diagonal = diagonal.sound(sound, volume: volume * 0.85).sound(sound, volume: volume * 0.85, at: 0.012)
        if let release { copy.center = center.sound(release, volume: releaseVolume) }
        return copy
    }

    static let all: [DpadFeel] = [nintendoCross, n64Plus, rockingDisc, splitArms, tinyTact, arcadeStick, today]
}

/// A stick's feel: its gate (from the player's stick profile), a texture along the rim,
/// and what reaching full tilt, a notch and the centre feel like.
struct StickFeel: Identifiable, Hashable, Sendable {
    /// Taps as the thumb slides along the rim at full tilt: one every `degrees`, each with an
    /// intensity and sharpness drawn from the ranges (grit is uneven).
    struct Grain: Hashable, Sendable {
        /// How the ticks are spaced along the rim.
        enum Spacing: Hashable, Sendable {
            case even
            /// In pairs `gap` degrees apart, a pair every two `degrees` (a rumble strip).
            case pairs(gap: Double)
            /// Each gap `degrees` × a value drawn from the range (gravel, sand).
            case irregular(ClosedRange<Double>)
        }

        var degrees: Double
        var intensity: ClosedRange<Double>
        var sharpness: ClosedRange<Double>
        var spacing: Spacing = .even
    }

    /// A buzz while the thumb moves along the rim, its level following the thumb's speed
    /// and how hard it pushes.
    struct Hum: Hashable, Sendable {
        var sharpness: Double
        /// The level at `fullSpeed` and full force.
        var level: Double
        /// Degrees per second along the rim (one and a half turns a second).
        var fullSpeed: Double = 540
    }

    let id: String
    let name: String
    let source: String
    let profile: PlayerStickProfile
    var grain: Grain?
    var hum: Hum?
    /// Settling into one of the gate's notches (the octagon's eight, the square's corners).
    var notch: HapticRecipe = .empty
    var edge: HapticRecipe = .empty
    var center: HapticRecipe = .empty

    static let n64New = StickFeel(
        id: "n64-new", name: "N64, new", source: "The octagon gate: a clean click into each notch",
        profile: .n64,
        grain: Grain(degrees: 10, intensity: 0.15...0.22, sharpness: 0.7...0.85),
        notch: .tap(0.75, 0.82), edge: .tap(0.42, 0.5), center: .tap(0.3, 0.35)).voiced(notch: (.gateTick, 0.3), edge: (.rimTap, 0.35), center: (.springClack, 0.35))
    static let n64Worn = StickFeel(
        id: "n64-worn", name: "N64, worn", source: "Ground-down plastic: grit around the rim, notches that clunk",
        profile: .n64,
        grain: Grain(degrees: 8, intensity: 0.16...0.28, sharpness: 0.5...0.8),
        notch: .tap(0.85, 0.7) + .tap(0.3, 0.3, at: 0.01), edge: .tap(0.5, 0.42),
        center: .tap(0.38, 0.3) + .tap(0.15, 0.3, at: 0.03)).voiced(notch: (.gateTick, 0.35), edge: (.rimTap, 0.35), center: (.springClack, 0.4))
    static let dualShock = StickFeel(
        id: "dualshock", name: "DualShock", source: "Smooth and damped; the square gate's corners bump",
        profile: .psx,
        hum: Hum(sharpness: 0.22, level: 0.3),
        notch: .tap(0.62, 0.36) + .tap(0.2, 0.22, at: 0.009), edge: .tap(0.32, 0.28)).voiced(notch: (.domeRelease, 0.25), edge: (.rimTap, 0.25))
    static let circlePad = StickFeel(
        id: "circle-pad", name: "Circle pad", source: "3DS: a slick glide on a flat disc, with faint landmarks",
        profile: .threeDS,
        grain: Grain(degrees: 12, intensity: 0.15...0.2, sharpness: 0.9...0.95),
        hum: Hum(sharpness: 0.34, level: 0.24),
        edge: .tap(0.25, 0.6), center: .tap(0.15, 0.8)).voiced(edge: (.rimTap, 0.2), center: (.tactTicRelease, 0.2))
    static let pspNub = StickFeel(
        id: "psp-nub", name: "PSP nub", source: "A little slider that drags",
        profile: .psp,
        hum: Hum(sharpness: 0.55, level: 0.28),
        edge: .tap(0.55, 0.15)).voiced(edge: (.rimTap, 0.25))
    static let ratchet = StickFeel(
        id: "ratchet", name: "Ratchet", source: "Not a console: a click every 10°, like a dial",
        profile: .threeDS,
        grain: Grain(degrees: 10, intensity: 0.55...0.55, sharpness: 0.85...0.85),
        edge: .tap(0.4, 0.5), center: .tap(0.2, 0.5)).voiced(edge: (.rimTap, 0.3), center: (.springClack, 0.25))
    static let today = StickFeel(
        id: "today", name: "Today", source: "How the player's N64 stick feels now: a tick at full tilt, a click in notches",
        profile: .n64,
        notch: .tap(0.8, 0.8), edge: .tap(0.4, 0.5))

    /// The stick with sounds on its notches, its arrival at the rim and its spring back.
    func voiced(notch: (SoundRecipe, Double)? = nil, edge: (SoundRecipe, Double)? = nil,
                center: (SoundRecipe, Double)? = nil) -> StickFeel {
        var copy = self
        if let notch { copy.notch = self.notch.sound(notch.0, volume: notch.1) }
        if let edge { copy.edge = self.edge.sound(edge.0, volume: edge.1) }
        if let center { copy.center = self.center.sound(center.0, volume: center.1) }
        return copy
    }

    /// The stick with another texture around its rim (`RimTexture.designed` keeps its own).
    func textured(_ texture: RimTexture) -> StickFeel {
        guard texture != .designed else { return self }
        var copy = self
        copy.grain = texture.grain
        copy.hum = texture.hum
        return copy
    }

    static let all: [StickFeel] = [n64New, n64Worn, dualShock, circlePad, pspNub, ratchet, today]
}

/// A texture around a stick's rim, to try on any stick (the playground's "Rim texture"). Every
/// stick should have some: the rim is where the thumb finds out where it is (revised
/// 2026-09-28).
struct RimTexture: Identifiable, Hashable, Sendable {
    let id: String
    let name: String
    let source: String
    var grain: StickFeel.Grain?
    var hum: StickFeel.Hum?

    static let designed = RimTexture(id: "designed", name: "As designed", source: "The stick's own texture")
    static let silk = RimTexture(
        id: "silk", name: "Silk", source: "The faintest drag, only while moving",
        hum: .init(sharpness: 0.4, level: 0.24))
    static let satin = RimTexture(
        id: "satin", name: "Satin", source: "A fine, even weave",
        grain: .init(degrees: 4, intensity: 0.15...0.2, sharpness: 0.9...1), hum: .init(sharpness: 0.42, level: 0.16))
    static let glide = RimTexture(
        id: "glide", name: "Glide", source: "Smooth, with faint landmarks every 12°",
        grain: .init(degrees: 12, intensity: 0.15...0.2, sharpness: 0.9...0.95), hum: .init(sharpness: 0.34, level: 0.24))
    static let fineSand = RimTexture(
        id: "fine-sand", name: "Fine sand", source: "Dense, uneven specks",
        grain: .init(degrees: 3, intensity: 0.15...0.26, sharpness: 0.6...0.95, spacing: .irregular(0.6...1.4)))
    static let sandpaper = RimTexture(
        id: "sandpaper", name: "Sandpaper", source: "Fine and scratchy, with a hiss under it",
        grain: .init(degrees: 2, intensity: 0.15...0.28, sharpness: 0.85...1, spacing: .irregular(0.5...1.5)),
        hum: .init(sharpness: 0.6, level: 0.12))
    static let coarseGrit = RimTexture(
        id: "coarse-grit", name: "Coarse grit", source: "Worn plastic: rough and uneven",
        grain: .init(degrees: 7, intensity: 0.2...0.38, sharpness: 0.5...0.85, spacing: .irregular(0.7...1.3)))
    static let gravel = RimTexture(
        id: "gravel", name: "Gravel", source: "Chunky and lumpy, in clusters",
        grain: .init(degrees: 9, intensity: 0.26...0.45, sharpness: 0.25...0.55, spacing: .irregular(0.35...1.8)))
    static let ribbed = RimTexture(
        id: "ribbed", name: "Ribbed", source: "Evenly knurled, like a dial's edge",
        grain: .init(degrees: 6, intensity: 0.26...0.26, sharpness: 0.75...0.75))
    static let rumbleStrip = RimTexture(
        id: "rumble-strip", name: "Rumble strip", source: "Ticks in pairs",
        grain: .init(degrees: 12, intensity: 0.3...0.36, sharpness: 0.5...0.6, spacing: .pairs(gap: 2.5)))
    static let velvet = RimTexture(
        id: "velvet", name: "Velvet drag", source: "A soft, low resistance",
        hum: .init(sharpness: 0.18, level: 0.32))
    static let fizz = RimTexture(
        id: "fizz", name: "Fizz", source: "A buzz, like the PSP nub",
        hum: .init(sharpness: 0.55, level: 0.26))

    static let all: [RimTexture] = [
        designed, silk, satin, glide, fineSand, sandpaper, coarseGrit, gravel, ribbed, rumbleStrip, velvet, fizz,
    ]
}

/// A labelled control on a console's pad and its material.
struct ConsoleButton: Hashable, Sendable {
    let label: String
    let material: ButtonMaterial
}

/// A console's pad, assembled from the feels above.
struct ConsoleFeel: Identifiable, Hashable, Sendable {
    let id: String
    let name: String
    let dpad: DpadFeel
    let face: [ConsoleButton]
    var shoulders: [ConsoleButton] = []
    var small: [ConsoleButton] = []
    var stick: StickFeel?

    private static func buttons(_ labels: [String], _ material: ButtonMaterial) -> [ConsoleButton] {
        labels.map { ConsoleButton(label: $0, material: material) }
    }

    static let nes = ConsoleFeel(
        id: "nes", name: "NES", dpad: .nintendoCross, face: buttons(["B", "A"], .hardSquare),
        small: buttons(["Select", "Start"], .startSelect))
    static let gameBoy = ConsoleFeel(
        id: "game-boy", name: "Game Boy", dpad: .nintendoCross, face: buttons(["B", "A"], .gameBoyDome),
        small: buttons(["Select", "Start"], .startSelect))
    static let snes = ConsoleFeel(
        id: "snes", name: "SNES", dpad: .nintendoCross, face: buttons(["Y", "X", "B", "A"], .rubberDome),
        shoulders: buttons(["L", "R"], .shoulderClick), small: buttons(["Select", "Start"], .startSelect))
    static let genesis = ConsoleFeel(
        id: "genesis", name: "Genesis", dpad: .rockingDisc, face: buttons(["A", "B", "C"], .rubberDome),
        small: buttons(["Start"], .startSelect))
    static let gba = ConsoleFeel(
        id: "gba", name: "Game Boy Advance", dpad: .tinyTact, face: buttons(["B", "A"], .tactSwitch),
        shoulders: buttons(["L", "R"], .shoulderClick), small: buttons(["Select", "Start"], .startSelect))
    static let n64 = ConsoleFeel(
        id: "n64", name: "N64", dpad: .n64Plus,
        face: buttons(["B", "A"], .n64Face) + buttons(["C"], .cButton),
        shoulders: buttons(["L"], .shoulderClick) + buttons(["Z"], .longTrigger) + buttons(["R"], .shoulderClick),
        small: buttons(["Start"], .startSelect), stick: .n64Worn)
    static let playstation = ConsoleFeel(
        id: "playstation", name: "PlayStation", dpad: .splitArms, face: buttons(["□", "△", "✕", "○"], .dualShockFace),
        shoulders: buttons(["L2"], .longTrigger) + buttons(["L1", "R1"], .dualShockFace) + buttons(["R2"], .longTrigger),
        small: buttons(["Select", "Start"], .startSelect), stick: .dualShock)
    static let ds = ConsoleFeel(
        id: "ds", name: "DS", dpad: .tinyTact, face: buttons(["Y", "X", "B", "A"], .tactSwitch),
        shoulders: buttons(["L", "R"], .shoulderClick), small: buttons(["Select", "Start"], .startSelect))
    static let threeDS = ConsoleFeel(
        id: "3ds", name: "3DS", dpad: .tinyTact, face: buttons(["Y", "X", "B", "A"], .tactSwitch),
        shoulders: buttons(["L", "R"], .shoulderClick), small: buttons(["Select", "Start"], .startSelect), stick: .circlePad)
    static let psp = ConsoleFeel(
        id: "psp", name: "PSP", dpad: .tinyTact, face: buttons(["□", "△", "✕", "○"], .tactSwitch),
        shoulders: buttons(["L", "R"], .shoulderClick), small: buttons(["Select", "Start"], .startSelect), stick: .pspNub)
    static let arcade = ConsoleFeel(
        id: "arcade", name: "Arcade", dpad: .arcadeStick, face: buttons(["A", "B", "C", "D"], .arcade),
        small: buttons(["Coin", "Start"], .arcade))

    static let all: [ConsoleFeel] = [nes, gameBoy, snes, genesis, gba, n64, playstation, ds, threeDS, psp, arcade]
}

// MARK: Following a finger

/// The eight d-pad directions (y grows downward).
enum DpadDirection: Int, Hashable, Sendable, CaseIterable {
    case none, right, downRight, down, downLeft, left, upLeft, up, upRight

    var isDiagonal: Bool { [.downRight, .downLeft, .upLeft, .upRight].contains(self) }

    /// The direction's angle in degrees (0 right, 90 down).
    var degrees: Double { Double(rawValue - 1) * 45 }

    /// Half the direction's sector, for a pivot pad whose diagonals are `diagonal` wide.
    func halfWidth(diagonal: Double) -> Double {
        isDiagonal ? diagonal / 2 : (90 - diagonal) / 2
    }

    /// The direction for `angle` degrees on a pivot pad whose diagonals are `diagonal` wide.
    static func sector(_ angle: Double, diagonal: Double) -> DpadDirection {
        allCases.dropFirst().first { offset(angle, $0.degrees) <= $0.halfWidth(diagonal: diagonal) } ?? .right
    }

    /// Degrees between two angles (0…180).
    static func offset(_ a: Double, _ b: Double) -> Double {
        var d = (a - b).truncatingRemainder(dividingBy: 360)
        if d < 0 { d += 360 }
        return min(d, 360 - d)
    }

    /// The unit offset of the direction (for drawing).
    var vector: (x: Double, y: Double) {
        guard self != .none else { return (0, 0) }
        let angle = Double(rawValue - 1) * .pi / 4
        return (cos(angle), sin(angle))
    }

    /// What moving from `previous` to this direction plays.
    func recipe(from previous: DpadDirection, _ feel: DpadFeel) -> HapticRecipe? {
        guard self != previous else { return nil }
        if self == .none { return feel.center }
        return isDiagonal ? feel.diagonal : feel.direction
    }
}

/// A d-pad following a thumb (pure: the playground plays the recipes). Positions are in the
/// pad's radius (1 at its edge), y down.
struct DpadTracker: Sendable {
    enum Switch: CaseIterable, Sendable { case right, down, left, up }

    /// The held direction keeps the thumb until it is this many degrees past its sector.
    static let angleHold = 10.0
    /// Having left the centre, the thumb is back only inside this share of the dead zone.
    static let returnShare = 0.72
    /// A closed switch opens when its axis falls below this share of the throw.
    static let openShare = 0.6

    private(set) var direction: DpadDirection = .none
    private var closed: Set<Switch> = []

    /// What moving the thumb to `x, y` plays.
    mutating func update(x: Double, y: Double, feel: DpadFeel) -> [HapticRecipe] {
        switch feel.model {
        case let .pivot(diagonal):
            let r = hypot(x, y)
            let engaged = direction == .none ? r > feel.deadZone : r > feel.deadZone * Self.returnShare
            var next = DpadDirection.none
            if engaged {
                var angle = atan2(y, x) * 180 / .pi
                if angle < 0 { angle += 360 }
                if direction != .none,
                   DpadDirection.offset(angle, direction.degrees) <= direction.halfWidth(diagonal: diagonal) + Self.angleHold {
                    next = direction
                } else {
                    next = DpadDirection.sector(angle, diagonal: diagonal)
                }
            }
            defer { direction = next }
            return next.recipe(from: direction, feel).map { [$0] } ?? []
        case .switches:
            var next = closed
            for key in Switch.allCases {
                let along: Double = switch key {
                case .right: x
                case .left: -x
                case .down: y
                case .up: -y
                }
                if closed.contains(key) {
                    if along < feel.deadZone * Self.openShare { next.remove(key) }
                } else if along > feel.deadZone {
                    next.insert(key)
                }
            }
            let recipes = Array(repeating: feel.center, count: closed.subtracting(next).count)
                + Array(repeating: feel.direction, count: next.subtracting(closed).count)
            closed = next
            direction = Self.direction(of: next)
            return recipes.filter { !$0.isEmpty }
        }
    }

    /// The thumb lifted: back to the centre.
    mutating func release(feel: DpadFeel) -> [HapticRecipe] {
        let recipes: [HapticRecipe]
        switch feel.model {
        case .pivot: recipes = direction == .none ? [] : [feel.center]
        case .switches: recipes = Array(repeating: feel.center, count: closed.count)
        }
        self = DpadTracker()
        return recipes.filter { !$0.isEmpty }
    }

    private static func direction(of switches: Set<Switch>) -> DpadDirection {
        let x = switches.contains(.right) ? 1 : (switches.contains(.left) ? -1 : 0)
        let y = switches.contains(.down) ? 1 : (switches.contains(.up) ? -1 : 0)
        switch (x, y) {
        case (1, 0): return .right
        case (1, 1): return .downRight
        case (0, 1): return .down
        case (-1, 1): return .downLeft
        case (-1, 0): return .left
        case (-1, -1): return .upLeft
        case (0, -1): return .up
        case (1, -1): return .upRight
        default: return .none
        }
    }
}

/// How a stick's texture responds to the thumb: how fast an arrival at the rim is a full
/// smack, and how far past the rim is full force (tuned in the playground).
struct StickTuning: Hashable, Sendable {
    /// Radial speed (stick radii a second) below which an arrival is at its softest.
    static let softHit = 2.0
    /// Radial speed for a full-strength smack (revised 2026-09-28 from 16: nearly every
    /// arrival maxed out).
    var fullHit = 30.0
    /// How far past the rim (share of the travel) is full force (was 0.5).
    var fullPush = 0.8
    /// The least any contact with the rim plays at: plastic on plastic always gives
    /// something, so grit and hum never fade to nothing at no push (revised 2026-09-28:
    /// light textures couldn't be felt).
    var contactFloor = 0.15

    /// An arrival at `radial` radii a second as 0…1.
    func impact(_ radial: Double) -> Double {
        min(max((radial - Self.softHit) / (fullHit - Self.softHit), 0), 1)
    }

    /// Pushing `beyond` travels past the rim as 0…1 (gentle at first).
    func force(beyond: Double) -> Double {
        pow(min(max(beyond / fullPush, 0), 1), 1.3)
    }
}

/// A stick's haptic texture as the thumb moves (pure: the playground plays the cues).
/// Revised 2026-09-28: the texture knows the thumb's speed and how hard it pushes. Reaching
/// the rim fast is a sharp smack, slowly a soft touch (`impact`); pushing past the rim is
/// force, and sliding along it is glide: both make the grit stronger, sharper and (force)
/// denser, the notches harder and the hum louder; the spring back to the centre is as strong
/// as the stick was far out, or as fast as it came back.
struct StickTexture: Sendable {
    enum Cue: Equatable, Sendable {
        /// Reaching full tilt; `impact` 0…1 from how fast the thumb got there.
        case edge(impact: Double)
        /// Settling into a notch; `force` and `glide` 0…1.
        case notch(force: Double, glide: Double)
        /// Springing back to the centre; `strength` 0…1.
        case center(strength: Double)
        case grain(intensity: Double, sharpness: Double)
    }

    private var atEdge = false
    private var notch: Int?
    private var lastRimAngle: Double?
    private var travel = 0.0
    private var engaged = false
    private var grains = 0
    private var reach = 0.0
    /// How hard the thumb pushes past the rim, 0…1 (0 off the rim).
    private(set) var force = 0.0
    /// How fast the thumb slides along the rim, 0…1 (two turns a second is 1).
    private(set) var glide = 0.0
    /// Degrees per second along the rim (0 off the rim), for a hum.
    private(set) var rimSpeed = 0.0
    /// The last arrival at the rim, 0…1.
    private(set) var lastImpact: Double?

    /// The cues for the thumb at `dx, dy` points from the stick's centre, moving at `vx, vy`
    /// points per second (the profile's points, as `dx, dy`).
    mutating func update(dx: Double, dy: Double, vx: Double = 0, vy: Double = 0, feel: StickFeel,
                         tuning: StickTuning = StickTuning()) -> [Cue] {
        let profile = feel.profile
        var cues: [Cue] = []
        let r = hypot(dx, dy)
        // Travels per second outward (negative: inward).
        let radial = r > 0 ? (vx * dx + vy * dy) / r / profile.travel : 0
        reach = min(r / profile.travel, 1)

        let output = PlayerStickModel.output(dx: dx, dy: dy, profile)
        if output.x != 0 || output.y != 0 {
            engaged = true
        } else if engaged {
            engaged = false
            cues.append(.center(strength: Self.step(min(max(-radial / 8, 0.25), 1))))
        }
        let full = PlayerStickModel.fullTilt(dx: dx, dy: dy, profile)
        if full, !atEdge {
            let impact = Self.step(tuning.impact(radial))
            lastImpact = impact
            cues.append(.edge(impact: impact))
        }
        atEdge = full
        let rimRadius = profile.travel * (profile.deadZone + profile.saturation * (1 - profile.deadZone))
        force = full ? Self.step(tuning.force(beyond: (r - rimRadius) / profile.travel)) : 0
        rimSpeed = full && r > 0 ? abs(dx * vy - dy * vx) / (r * r) * 180 / .pi : 0
        glide = Self.step(min(rimSpeed / 720, 1))

        let caught = PlayerStickModel.notch(dx: dx, dy: dy, profile, current: notch)
        if let caught, caught != notch { cues.append(.notch(force: force, glide: glide)) }
        notch = caught

        guard full else {
            lastRimAngle = nil
            travel = 0
            return cues
        }
        let angle = atan2(dy, dx) * 180 / .pi
        if let last = lastRimAngle, let grain = feel.grain {
            var delta = angle - last
            if delta > 180 { delta -= 360 } else if delta < -180 { delta += 360 }
            travel += abs(delta)
            // More force: denser grit.
            let density = 1.25 - 0.5 * force
            var spacing = Self.gap(grain, grains + 1) * density
            var emitted = 0
            while travel >= spacing {
                travel -= spacing
                grains += 1
                spacing = Self.gap(grain, grains + 1) * density
                // A flick can pass many grains in one update: at most three play (taps
                // that land together merge anyway).
                if emitted < 3 {
                    // On top of a floor any contact reaches, pushing harder and sliding faster
                    // make the grit stronger and sharper.
                    let raw = Self.pick(grain.intensity, grains, 1) * (0.6 + 0.4 * force + 0.3 * glide)
                    let intensity = max(tuning.contactFloor, min(raw, 1))
                    let sharpness = Self.pick(grain.sharpness, grains, 2) + 0.08 * force + 0.12 * glide - 0.05
                    cues.append(.grain(intensity: Self.step(intensity), sharpness: Self.step(min(max(sharpness, 0), 1))))
                    emitted += 1
                }
            }
        }
        lastRimAngle = angle
        return cues
    }

    /// A hum's level now: while the thumb slides along the rim, at least near the contact
    /// floor, and more with speed and push.
    func humLevel(_ hum: StickFeel.Hum, tuning: StickTuning = StickTuning()) -> Double {
        guard rimSpeed > 15 else { return 0 }
        let speed = min(1, rimSpeed / hum.fullSpeed)
        let level = hum.level * (0.55 + 0.45 * force) * (0.35 + 0.65 * speed)
        return min(max(level, tuning.contactFloor * 0.8), 1)
    }

    /// The thumb lifted: the stick springs back to the centre, harder from further out.
    mutating func release() -> [Cue] {
        let cues: [Cue] = engaged ? [.center(strength: Self.step(max(reach, 0.2)))] : []
        self = StickTexture()
        return cues
    }

    /// Degrees before grain `n`, by the grain's spacing.
    private static func gap(_ grain: StickFeel.Grain, _ n: Int) -> Double {
        switch grain.spacing {
        case .even: grain.degrees
        case let .pairs(gap): n.isMultiple(of: 2) ? gap : grain.degrees * 2 - gap
        case let .irregular(range): grain.degrees * pick(range, n, 3)
        }
    }

    /// A value in `range`, varying from grain to grain (deterministic).
    private static func pick(_ range: ClosedRange<Double>, _ n: Int, _ salt: Int) -> Double {
        var x = UInt64(truncatingIfNeeded: n &* 2246822519 &+ salt &* 3266489917)
        x ^= x >> 15; x = x &* 0x2c1b3c6d; x ^= x >> 12
        let unit = Double(x % 1000) / 999
        return range.lowerBound + (range.upperBound - range.lowerBound) * unit
    }

    /// Rounded to 0.05: nearby values play the same pattern (the engine caches patterns).
    private static func step(_ value: Double) -> Double {
        (value * 20).rounded() / 20
    }
}
