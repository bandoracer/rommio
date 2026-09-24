import CoreGraphics
import CoreImage
import Foundation

/// How one ink plate is separated from a cover: a single source channel, scaled
/// and offset (the studio's CSS `grayscale → brightness → contrast` chain on that
/// channel, folded into one gain and bias), then clamped to 0...1.
struct RisoPlateCurve: Hashable, Sendable {
    enum Channel: Int, Sendable { case red, green, blue }
    let channel: Channel
    let gain: Double
    let bias: Double

    /// How much light the plate lets through at a source value (1 = paper, 0 = full ink by day).
    func mask(_ value: Double) -> Double { min(1, max(0, gain * value + bias)) }

    /// CSS `grayscale(1) brightness(b) contrast(c)` applied to a colour whose only
    /// non-zero channel has luminance weight `weight`.
    static func css(channel: Channel, weight: Double, brightness: Double, contrast: Double) -> RisoPlateCurve {
        RisoPlateCurve(channel: channel, gain: weight * brightness * contrast, bias: 0.5 - 0.5 * contrast)
    }
}

/// Riso two-ink print: a cover separated into two plates (pink = accent1 from the
/// green channel, blue = accent2 from the red channel), the blue plate
/// half-toned and off register, overprinted with multiply on paper (light) or
/// screen on black stock (dark). Mirrors the studio's `rz()` covers
/// (`design/studio/versions/riso/riso.css`, `.rz`).
struct RisoPrintStyle: Hashable, Sendable {
    let plateA: ArtRGB
    let plateB: ArtRGB
    let paper: ArtRGB
    let dark: Bool

    /// Plate curves by appearance (riso.css `.rz-a .rz-s` / `.rz-b .rz-s`).
    var curveA: RisoPlateCurve {
        dark ? .css(channel: .green, weight: 0.7152, brightness: 1.7, contrast: 1.25)
             : .css(channel: .green, weight: 0.7152, brightness: 1.55, contrast: 1.1)
    }

    var curveB: RisoPlateCurve {
        dark ? .css(channel: .red, weight: 0.2126, brightness: 5.6, contrast: 1.25)
             : .css(channel: .red, weight: 0.2126, brightness: 5.2, contrast: 1.1)
    }

    /// Ink coverage of a plate at a source value: by day dark areas take ink; on
    /// black stock the inks glow where the cover is bright.
    func coverage(_ curve: RisoPlateCurve, value: Double) -> Double {
        dark ? curve.mask(value) : 1 - curve.mask(value)
    }

    /// The printed colour of a pixel with both plates in register (tests and the
    /// halftone-free reference; the renderer adds the halftone and offset).
    func printed(_ source: ArtRGB) -> ArtRGB {
        func value(_ channel: RisoPlateCurve.Channel) -> Double {
            switch channel {
            case .red: source.red
            case .green: source.green
            case .blue: source.blue
            }
        }
        let a = coverage(curveA, value: value(curveA.channel))
        let b = coverage(curveB, value: value(curveB.channel))
        func ink(_ plate: ArtRGB, _ c: Double) -> (Double, Double, Double) {
            dark ? (plate.red * c, plate.green * c, plate.blue * c)
                 : (1 - c * (1 - plate.red), 1 - c * (1 - plate.green), 1 - c * (1 - plate.blue))
        }
        let pa = ink(plateA, a), pb = ink(plateB, b)
        if dark {
            func screen(_ x: Double, _ y: Double, _ z: Double) -> Double { 1 - (1 - x) * (1 - y) * (1 - z) }
            return ArtRGB(red: screen(paper.red, pa.0, pb.0), green: screen(paper.green, pa.1, pb.1), blue: screen(paper.blue, pa.2, pb.2))
        }
        return ArtRGB(red: paper.red * pa.0 * pb.0, green: paper.green * pa.1 * pb.1, blue: paper.blue * pa.2 * pb.2)
    }
}

enum RisoPrinter {
    /// A context that does its maths on the encoded sRGB values, like the CSS it mirrors.
    static func makeContext() -> CIContext {
        CIContext(options: [.workingColorSpace: NSNull(), .outputColorSpace: NSNull(), .cacheIntermediates: false])
    }

    /// Prints `image` in two inks. `misregistration` and `dotWidth` are in pixels
    /// of the image; `halftone` (0...1) is how much of the blue plate is screened.
    static func print(_ image: CGImage, style: RisoPrintStyle, misregistration: Double, halftone: Double,
                      dotWidth: Double, context: CIContext) -> CGImage? {
        let source = CIImage(cgImage: image)
        let extent = source.extent
        guard extent.width > 1, extent.height > 1 else { return nil }

        func plateMask(_ curve: RisoPlateCurve) -> CIImage {
            // Every output channel = gain × source channel + bias (then clamped).
            let pick = CIVector(x: curve.channel == .red ? curve.gain : 0,
                                y: curve.channel == .green ? curve.gain : 0,
                                z: curve.channel == .blue ? curve.gain : 0, w: 0)
            let bias = CIVector(x: curve.bias, y: curve.bias, z: curve.bias, w: 1)
            return source.applyingFilter("CIColorMatrix", parameters: [
                "inputRVector": pick, "inputGVector": pick, "inputBVector": pick,
                "inputAVector": CIVector(x: 0, y: 0, z: 0, w: 0), "inputBiasVector": bias,
            ]).applyingFilter("CIColorClamp")
        }

        /// Coverage (0...1 grey) → the plate as printed on its own.
        func inked(_ mask: CIImage, plate: ArtRGB) -> CIImage {
            if style.dark {
                // coverage = mask; ink × coverage (glows on black stock).
                return mask.applyingFilter("CIColorMatrix", parameters: [
                    "inputRVector": CIVector(x: plate.red, y: 0, z: 0, w: 0),
                    "inputGVector": CIVector(x: 0, y: plate.green, z: 0, w: 0),
                    "inputBVector": CIVector(x: 0, y: 0, z: plate.blue, w: 0),
                    "inputAVector": CIVector(x: 0, y: 0, z: 0, w: 0),
                    "inputBiasVector": CIVector(x: 0, y: 0, z: 0, w: 1),
                ])
            }
            // coverage = 1 − mask; 1 − coverage × (1 − ink) = mask × (1 − ink) + ink.
            return mask.applyingFilter("CIColorMatrix", parameters: [
                "inputRVector": CIVector(x: 1 - plate.red, y: 0, z: 0, w: 0),
                "inputGVector": CIVector(x: 0, y: 1 - plate.green, z: 0, w: 0),
                "inputBVector": CIVector(x: 0, y: 0, z: 1 - plate.blue, w: 0),
                "inputAVector": CIVector(x: 0, y: 0, z: 0, w: 0),
                "inputBiasVector": CIVector(x: plate.red, y: plate.green, z: plate.blue, w: 1),
            ])
        }

        let maskA = plateMask(style.curveA)
        var maskB = plateMask(style.curveB)
        if halftone > 0, dotWidth >= 2 {
            // The dot screen draws dark dots for ink; by night ink follows the bright
            // areas, so screen the inverse and invert back.
            let screenInput = style.dark ? maskB.applyingFilter("CIColorInvert") : maskB
            var dots = screenInput.applyingFilter("CIDotScreen", parameters: [
                kCIInputCenterKey: CIVector(x: extent.minX, y: extent.minY),
                kCIInputAngleKey: 0.2618, // 15°: the conventional angle for a second colour
                kCIInputWidthKey: dotWidth,
                kCIInputSharpnessKey: 0.7,
            ]).cropped(to: extent)
            if style.dark { dots = dots.applyingFilter("CIColorInvert") }
            maskB = dots.applyingFilter("CIDissolveTransition", parameters: [
                kCIInputTargetImageKey: maskB, kCIInputTimeKey: 1 - min(max(halftone, 0), 1),
            ]).cropped(to: extent)
        }

        let neutral = style.dark ? CIColor(red: 0, green: 0, blue: 0) : CIColor(red: 1, green: 1, blue: 1)
        let plateA = inked(maskA, plate: style.plateA).cropped(to: extent)
        // Off register: right and up by the misregistration (CSS translate(mis, −mis/2)).
        let shifted = inked(maskB, plate: style.plateB)
            .transformed(by: CGAffineTransform(translationX: misregistration, y: misregistration / 2))
        let blank = CIImage(color: neutral).cropped(to: extent)
        let plateB = shifted.composited(over: blank).cropped(to: extent)
        let paper = CIImage(color: CIColor(red: style.paper.red, green: style.paper.green, blue: style.paper.blue)).cropped(to: extent)
        let blend = style.dark ? "CIScreenBlendMode" : "CIMultiplyBlendMode"
        let printed = plateA
            .applyingFilter(blend, parameters: [kCIInputBackgroundImageKey: paper])
            .applyingFilter(blend, parameters: [kCIInputBackgroundImageKey: plateB])
            .cropped(to: extent)
        let space = CGColorSpace(name: CGColorSpace.sRGB)!
        return context.createCGImage(printed, from: extent, format: .RGBA8, colorSpace: space)
    }
}
