import CoreGraphics
import CoreImage
import XCTest
@testable import RommioUI

/// Aurora ambient light and the Riso print, pinned to the studio. The expected
/// hex values come from running `lights()` in
/// design/studio/versions/aurora/aurora.js with node on the same palettes.
final class ArtTreatmentTests: XCTestCase {
    private func palette(_ hex: [String]) -> CoverPalette { CoverPalette(hex: hex)! }

    private func assertLights(_ lights: AuroraLights, _ expected: [String: String], file: StaticString = #filePath, line: UInt = #line) {
        let actual = ["l1": lights.l1.hex, "l2": lights.l2.hex, "l3": lights.l3.hex, "k1": lights.k1.hex, "k2": lights.k2.hex,
                      "k3": lights.k3.hex, "tint": lights.tint.hex, "bg": lights.ground.hex]
        XCTAssertEqual(actual, expected, file: file, line: line)
    }

    func testSuperMetroidLightsMatchTheStudio() {
        let p = palette(["#0b0620", "#3a1450", "#5d2a6e", "#1c0c2b", "#ff7a3d"])
        assertLights(AuroraLights.derive(palette: p, dark: false), [
            "l1": "#fbc5ac", "l2": "#dab3e7", "l3": "#d3acea", "k1": "#f9601a", "k2": "#9635b6", "k3": "#882fbc",
            "tint": "#d64300", "bg": "#f9f4f3",
        ])
        assertLights(AuroraLights.derive(palette: p, dark: true), [
            "l1": "#ff7a3d", "l2": "#a339c6", "l3": "#9433cc", "k1": "#ff7a3d", "k2": "#a339c6", "k3": "#9433cc",
            "tint": "#ff621a", "bg": "#0e0910",
        ])
    }

    func testAYellowAccentBecomesTheThirdLight() {
        // Chrono Trigger: a yellow accent over a warm palette.
        let p = palette(["#f7b267", "#f4845f", "#6c4a8c", "#2b2d5c", "#ffe066"])
        assertLights(AuroraLights.derive(palette: p, dark: false), [
            "l1": "#fac2af", "l2": "#fbd6af", "l3": "#fbecb0", "k1": "#f05623", "k2": "#f38e20", "k3": "#f9cc1a",
            "tint": "#c83c0e", "bg": "#f9f4f3",
        ])
        assertLights(AuroraLights.derive(palette: p, dark: true), [
            "l1": "#f37b53", "l2": "#f6a751", "l3": "#ffda47", "k1": "#f37b53", "k2": "#f6a751", "k3": "#ffda47",
            "tint": "#f05a28", "bg": "#100c09",
        ])
    }

    func testCoolAndGreenPalettesMatchTheStudio() {
        let earthbound = palette(["#b8e0f6", "#7cc0e8", "#e97fa3", "#5e3a6e", "#fff27a"])
        assertLights(AuroraLights.derive(palette: earthbound, dark: false), [
            "l1": "#b8ddf2", "l2": "#f3b8cc", "l3": "#fbf4b1", "k1": "#379fdc", "k2": "#dd376f", "k3": "#f9e31a",
            "tint": "#207fb6", "bg": "#f3f6f9",
        ])
        let links = palette(["#8fd694", "#3f8f5a", "#2c5d3f", "#173826", "#f6d04d"])
        assertLights(AuroraLights.derive(palette: links, dark: true), [
            "l1": "#39c669", "l2": "#71d678", "l3": "#f6d04d", "k1": "#39c669", "k2": "#71d678", "k3": "#f6d04d",
            "tint": "#45d375", "bg": "#091009",
        ])
    }

    func testGreyPaletteMatchesTheStudioButCountsAsNeutral() {
        let grey = palette(["#202020", "#505050", "#808080", "#b0b0b0", "#e0e0e0"])
        assertLights(AuroraLights.derive(palette: grey, dark: false), [
            "l1": "#d9d9d9", "l2": "#c9c9c9", "l3": "#cdcdcd", "k1": "#8a8a8a", "k2": "#757575", "k3": "#757575",
            "tint": "#ab2b2b", "bg": "#f9f3f3",
        ])
        XCTAssertTrue(grey.isNeutral, "the app falls back to the theme's light for greyscale covers")
        XCTAssertFalse(palette(["#0b0620", "#3a1450", "#5d2a6e", "#1c0c2b", "#ff7a3d"]).isNeutral)
    }

    func testSeveralGamesLightAPageWithTheirKeyColours() {
        let metroid = palette(["#0b0620", "#3a1450", "#5d2a6e", "#1c0c2b", "#ff7a3d"])
        let chrono = palette(["#f7b267", "#f4845f", "#6c4a8c", "#2b2d5c", "#ffe066"])
        let links = palette(["#8fd694", "#3f8f5a", "#2c5d3f", "#173826", "#f6d04d"])
        assertLights(AuroraLights.derive(palettes: [metroid, chrono, links], dark: false)!, [
            "l1": "#fbc5ac", "l2": "#fac2af", "l3": "#b6e8c7", "k1": "#f9601a", "k2": "#f05623", "k3": "#35b660",
            "tint": "#d64300", "bg": "#f9f4f3",
        ])
        assertLights(AuroraLights.derive(palettes: [metroid, chrono, links], dark: true)!, [
            "l1": "#ff7a3d", "l2": "#f37b53", "l3": "#39c669", "k1": "#ff7a3d", "k2": "#f37b53", "k3": "#39c669",
            "tint": "#ff621a", "bg": "#100a09",
        ])
        XCTAssertNil(AuroraLights.derive(palettes: [], dark: false))
    }

    func testColourMathRoundTrips() {
        for hex in ["#ff7a3d", "#0b0620", "#6c4a8c", "#ffffff", "#000000", "#7cc0e8"] {
            let rgb = ArtRGB(hex: hex)!
            let x = ArtColorMath.hsl(rgb)
            XCTAssertEqual(ArtColorMath.rgb(hue: x.hue, saturation: x.saturation, lightness: x.lightness).hex, hex)
        }
    }

    // MARK: Palette extraction

    func testPaletteExtractionIsDeterministicAndFindsTheAccent() {
        // A dark violet cover with a small ember accent and a mid violet band.
        var pixels: [ArtRGB] = []
        for index in 0 ..< 576 {
            switch index % 24 {
            case 0 ..< 14: pixels.append(ArtRGB(hex: "#1c0c2b")!)
            case 14 ..< 21: pixels.append(ArtRGB(hex: "#5d2a6e")!)
            case 21 ..< 23: pixels.append(ArtRGB(hex: "#0b0620")!)
            default: pixels.append(ArtRGB(hex: "#ff7a3d")!)
            }
        }
        let first = CoverPaletteExtractor.palette(pixels: pixels)
        let second = CoverPaletteExtractor.palette(pixels: pixels)
        XCTAssertEqual(first, second)
        XCTAssertEqual(first.colors.count, 5)
        XCTAssertEqual(first.accent.hex, "#ff7a3d")
        XCTAssertEqual(first.colors[0].hex, "#1c0c2b", "the most populous colour leads")
        XCTAssertTrue(first.colors.prefix(4).map(\.hex).contains("#5d2a6e"))
    }

    func testPaletteExtractionReadsARealImageThroughCoreImage() throws {
        // 40×30: left half teal, right half coral.
        let width = 40, height = 30
        var bytes = [UInt8](repeating: 255, count: width * height * 4)
        for y in 0 ..< height {
            for x in 0 ..< width {
                let i = (y * width + x) * 4
                let (r, g, b): (UInt8, UInt8, UInt8) = x < width / 2 ? (0x1f, 0x8a, 0x8f) : (0xf2, 0x6b, 0x5b)
                bytes[i] = r; bytes[i + 1] = g; bytes[i + 2] = b
            }
        }
        let provider = CGDataProvider(data: Data(bytes) as CFData)!
        let image = try XCTUnwrap(CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: width * 4,
                                          space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                          bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
                                          provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent))
        let palette = try XCTUnwrap(CoverPaletteExtractor.palette(from: image, context: RisoPrinter.makeContext()))
        let hexes = Set(palette.colors.map(\.hex))
        XCTAssertTrue(hexes.contains { close($0, "#1f8a8f") }, "\(hexes)")
        XCTAssertTrue(hexes.contains { close($0, "#f26b5b") }, "\(hexes)")
    }

    private func close(_ a: String, _ b: String, tolerance: Int = 6) -> Bool {
        let x = ArtRGB(hex: a)!, y = ArtRGB(hex: b)!
        return abs(ArtRGB.byte(x.red) - ArtRGB.byte(y.red)) <= tolerance
            && abs(ArtRGB.byte(x.green) - ArtRGB.byte(y.green)) <= tolerance
            && abs(ArtRGB.byte(x.blue) - ArtRGB.byte(y.blue)) <= tolerance
    }

    // MARK: Riso print

    func testRisoPlatesFollowTheStudioCurves() {
        let light = RisoPrintStyle(plateA: ThemeTokens.riso.light.accent1.art, plateB: ThemeTokens.riso.light.accent2.art,
                                   paper: ThemeTokens.riso.light.ground.art, dark: false)
        // CSS: grayscale(1) of a green-only colour is 0.7152 × G, then brightness(1.55), contrast(1.1).
        XCTAssertEqual(light.curveA.gain, 0.7152 * 1.55 * 1.1, accuracy: 1e-9)
        XCTAssertEqual(light.curveA.bias, -0.05, accuracy: 1e-9)
        // Black takes both inks by day (pink × blue overprint); white stays paper.
        let black = light.printed(ArtRGB(hex: "#000000")!)
        let white = light.printed(ArtRGB(hex: "#ffffff")!)
        XCTAssertEqual(white.hex, ThemeTokens.riso.light.ground.art.hex)
        XCTAssertTrue(close(black.hex, "#002183"), "full overprint is the studio's pink × blue navy, got \(black.hex)")

        let dark = RisoPrintStyle(plateA: ThemeTokens.riso.dark.accent1.art, plateB: ThemeTokens.riso.dark.accent2.art,
                                  paper: ArtRGB(hex: "#000000")!, dark: true)
        // On black stock the inks glow where the cover is bright.
        XCTAssertEqual(dark.printed(ArtRGB(hex: "#000000")!).hex, "#000000")
        let glow = dark.printed(ArtRGB(hex: "#ffffff")!)
        XCTAssertGreaterThan(glow.red + glow.green + glow.blue, 2.4)
    }

    func testRisoPrinterRendersTwoInks() throws {
        let width = 32, height = 32
        var bytes = [UInt8](repeating: 255, count: width * height * 4)
        for i in stride(from: 0, to: bytes.count, by: 4) where (i / 4) % width < 16 {
            bytes[i] = 0; bytes[i + 1] = 0; bytes[i + 2] = 0
        }
        let provider = CGDataProvider(data: Data(bytes) as CFData)!
        let image = try XCTUnwrap(CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: width * 4,
                                          space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                          bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
                                          provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent))
        let style = RisoPrintStyle(plateA: ThemeTokens.riso.light.accent1.art, plateB: ThemeTokens.riso.light.accent2.art,
                                   paper: ThemeTokens.riso.light.ground.art, dark: false)
        let printed = try XCTUnwrap(RisoPrinter.print(image, style: style, misregistration: 2, halftone: Double(RisoArtTokens.halftone),
                                                     dotWidth: 4, context: RisoPrinter.makeContext()))
        XCTAssertEqual(printed.width, width)
        XCTAssertEqual(printed.height, height)
    }
}

private extension ThemeRGBA {
    var art: ArtRGB { ArtRGB(red: red, green: green, blue: blue) }
}
