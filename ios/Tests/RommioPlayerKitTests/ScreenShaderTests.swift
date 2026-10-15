import XCTest
import ImageIO
import UniformTypeIdentifiers
#if canImport(Metal)
import Metal
#endif
@testable import RommioPlayerKit

final class ScreenShaderTests: XCTestCase {
    func testTheTestCardIsNativeSize() throws {
        for style in ScreenStyle.all {
            let card = try XCTUnwrap(ScreenTestCard.image(width: style.nativeSize.x, height: style.nativeSize.y), style.id)
            XCTAssertEqual(card.width, style.nativeSize.x)
            XCTAssertEqual(card.height, style.nativeSize.y)
        }
        XCTAssertEqual(Set(ScreenStyle.all.map(\.id)).count, ScreenStyle.all.count)
    }

    func testAPlatformSuggestsItsOwnScreen() {
        XCTAssertEqual(ScreenStyle.suggested(platformSlug: "gb").id, "game-boy")
        XCTAssertEqual(ScreenStyle.suggested(platformSlug: "gba").id, "gba")
        XCTAssertEqual(ScreenStyle.suggested(platformSlug: "new-nintendo-3ds").id, "3ds")
        XCTAssertEqual(ScreenStyle.suggested(platformSlug: "snes").id, "consumer-tv")
    }

    func testTheUniformsMatchTheMetalLayout() {
        // float2, float2, then thirteen float4s.
        XCTAssertEqual(MemoryLayout<ScreenUniforms>.stride, 16 + 13 * 16)
    }

    #if canImport(Metal)
    /// Every screen compiles and draws something sensible over its test card. The renders
    /// are written to the temporary directory (`rommio-screens`) to look at.
    func testEveryScreenRendersItsTestCard() throws {
        guard let device = MTLCreateSystemDefaultDevice() else { throw XCTSkip("No Metal device") }
        let renderer = try ScreenRenderer(device: device)
        let folder = FileManager.default.temporaryDirectory.appending(path: "rommio-screens", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        for style in ScreenStyle.all {
            let card = try XCTUnwrap(ScreenTestCard.image(width: style.nativeSize.x, height: style.nativeSize.y))
            let aspect = style.displayAspect ?? Double(card.width) / Double(card.height)
            let width = Double(card.width) * 5
            let size = CGSize(width: width, height: (width / aspect).rounded())
            let output = try XCTUnwrap(try renderer.render(card, style: style, size: size), style.id)
            XCTAssertEqual(output.width, Int(size.width))
            let mean = Self.meanBrightness(output)
            XCTAssertGreaterThan(mean, 0.05, "\(style.id) is nearly black")
            XCTAssertLessThan(mean, 0.95, "\(style.id) is nearly white")
            try Self.writePNG(output, to: folder.appending(path: "\(style.id).png"))
            // And magnified: the middle third.
            let zoomed = try XCTUnwrap(try renderer.render(card, style: style, size: size, crop: CGRect(x: 0.33, y: 0.33, width: 0.34, height: 0.34)))
            try Self.writePNG(zoomed, to: folder.appending(path: "\(style.id)-zoom.png"))
        }
        print("SCREENS \(folder.path)")
    }

    /// A composite signal blends one-pixel dithering (its luma filter) and colours it (fine
    /// detail near the carrier decodes as colour); RGB keeps the columns apart and grey, and
    /// S-Video blends nothing into colour.
    func testCompositeBlendsDitheringAndColoursIt() throws {
        guard let device = MTLCreateSystemDefaultDevice() else { throw XCTSkip("No Metal device") }
        let renderer = try ScreenRenderer(device: device)
        let stripes = try XCTUnwrap(Self.stripes(width: 256, height: 224))
        var flat = ScreenStyle.consumerTV
        flat.scanlines = 0; flat.maskType = 0; flat.curvature = 0; flat.vignette = 0; flat.glow = 0
        flat.cornerRadius = 0; flat.blur = 0.3; flat.chromaBleed = 0; flat.brightness = 1; flat.linePhase = 0
        let size = CGSize(width: 1024, height: 768)
        flat.signal = .rgb
        let rgb = Self.row(try XCTUnwrap(renderer.render(stripes, style: flat, size: size)), y: 384)
        flat.signal = .composite
        let compositeImage = try XCTUnwrap(renderer.render(stripes, style: flat, size: size))
        let composite = Self.row(compositeImage, y: 384)
        flat.signal = .sVideo
        let sVideo = Self.row(try XCTUnwrap(renderer.render(stripes, style: flat, size: size)), y: 384)

        XCTAssertGreaterThan(Self.lumaSpread(rgb), 0.3, "RGB keeps the columns apart")
        XCTAssertLessThan(Self.lumaSpread(composite), Self.lumaSpread(rgb) * 0.4, "composite blends the columns")
        XCTAssertLessThan(Self.colourfulness(rgb), 0.02, "RGB stays grey")
        XCTAssertGreaterThan(Self.colourfulness(composite), 0.03, "composite colours fine detail")
        XCTAssertLessThan(Self.colourfulness(sVideo), 0.02, "S-Video keeps colour off fine detail")
        try Self.writePNG(compositeImage, to: Self.folder().appending(path: "stripes-composite.png"))
    }

    /// An LCD's response only shows in motion: a still draws the same however slow the panel,
    /// and moving things leave a trail (and a sprite on every other frame stays half there).
    func testResponseLeavesStillsAloneAndTrailsMotion() throws {
        guard let device = MTLCreateSystemDefaultDevice() else { throw XCTSkip("No Metal device") }
        let renderer = try ScreenRenderer(device: device)
        let card = try XCTUnwrap(ScreenMotionCard(device: device, width: 240, height: 160))
        var slow = ScreenStyle.gameBoyAdvance
        slow.response = 0.6
        var instant = slow
        instant.response = 0
        let size = CGSize(width: 960, height: 640)

        card.draw(frame: 0)
        let still = try XCTUnwrap(renderer.render(card.texture, style: slow, size: size))
        let reference = try XCTUnwrap(renderer.render(card.texture, style: instant, size: size))
        XCTAssertEqual(Self.meanBrightness(still), Self.meanBrightness(reference), accuracy: 0.001)

        for frame in 1...13 {
            card.draw(frame: frame)
            _ = try renderer.render(card.texture, style: slow, size: size, newFrame: frame)
        }
        card.draw(frame: 14)
        let moving = try XCTUnwrap(renderer.render(card.texture, style: slow, size: size, newFrame: 14))
        let sharp = try XCTUnwrap(renderer.render(card.texture, style: instant, size: size))
        // Frame 14 shows the flickering sprite; the slow panel still shows frame 13's absence of
        // it partly, and the bar's and the gliding sprite's trails: different pictures.
        XCTAssertGreaterThan(Self.meanDifference(moving, sharp), 0.002)
        try Self.writePNG(moving, to: Self.folder().appending(path: "gba-motion-slow.png"))
        try Self.writePNG(sharp, to: Self.folder().appending(path: "gba-motion-instant.png"))
    }
    #endif

    private static func folder() throws -> URL {
        let folder = FileManager.default.temporaryDirectory.appending(path: "rommio-screens", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }

    /// Alternating one-pixel black and white columns: dithering at its finest.
    private static func stripes(width: Int, height: Int) -> CGImage? {
        guard let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
        context.setFillColor(red: 0, green: 0, blue: 0, alpha: 1)
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        context.setFillColor(red: 1, green: 1, blue: 1, alpha: 1)
        for x in stride(from: 0, to: width, by: 2) { context.fill(CGRect(x: x, y: 0, width: 1, height: height)) }
        return context.makeImage()
    }

    private static func pixels(_ image: CGImage) -> [UInt8] {
        let width = image.width, height = image.height
        var bytes = [UInt8](repeating: 0, count: width * height * 4)
        guard let context = CGContext(data: &bytes, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return bytes }
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        return bytes
    }

    /// The middle 80% of row `y`, as (r, g, b) in 0…1.
    private static func row(_ image: CGImage, y: Int) -> [SIMD3<Double>] {
        let bytes = pixels(image)
        let width = image.width
        return (width / 10..<width * 9 / 10).map { x in
            let i = (y * width + x) * 4
            return SIMD3(Double(bytes[i]), Double(bytes[i + 1]), Double(bytes[i + 2])) / 255
        }
    }

    private static func lumaSpread(_ row: [SIMD3<Double>]) -> Double {
        let luma = row.map { 0.299 * $0.x + 0.587 * $0.y + 0.114 * $0.z }
        let mean = luma.reduce(0, +) / Double(luma.count)
        return (luma.map { ($0 - mean) * ($0 - mean) }.reduce(0, +) / Double(luma.count)).squareRoot()
    }

    private static func colourfulness(_ row: [SIMD3<Double>]) -> Double {
        row.map { max($0.x, $0.y, $0.z) - min($0.x, $0.y, $0.z) }.reduce(0, +) / Double(row.count)
    }

    private static func meanDifference(_ a: CGImage, _ b: CGImage) -> Double {
        let x = pixels(a), y = pixels(b)
        guard x.count == y.count else { return 1 }
        var sum = 0.0
        for index in stride(from: 0, to: x.count, by: 4) {
            for channel in 0..<3 { sum += abs(Double(x[index + channel]) - Double(y[index + channel])) }
        }
        return sum / Double(x.count / 4 * 3) / 255
    }

    private static func meanBrightness(_ image: CGImage) -> Double {
        let width = image.width, height = image.height
        var bytes = [UInt8](repeating: 0, count: width * height * 4)
        guard let context = CGContext(data: &bytes, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return 0 }
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        var sum = 0.0
        for index in stride(from: 0, to: bytes.count, by: 4) {
            sum += (Double(bytes[index]) + Double(bytes[index + 1]) + Double(bytes[index + 2])) / 3
        }
        return sum / Double(width * height) / 255
    }

    private static func writePNG(_ image: CGImage, to url: URL) throws {
        guard let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil) else {
            throw CocoaError(.fileWriteUnknown)
        }
        CGImageDestinationAddImage(destination, image, nil)
        guard CGImageDestinationFinalize(destination) else { throw CocoaError(.fileWriteUnknown) }
    }
}
