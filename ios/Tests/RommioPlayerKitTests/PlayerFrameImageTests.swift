import CoreGraphics
import CoreImage
import Foundation
import Metal
import XCTest
import RommioPlayerBridge
@testable import RommioPlayerKit

final class PlayerFrameImageTests: XCTestCase {
    func testAllNegotiatedFormatsRenderRedGreenBlueWithPaddedRows() throws {
        let cases: [(Int, Data, Int)] = [
            (1, Data([0,0,255,0, 0,255,0,0, 255,0,0,0, 9,9,9,9]), 16),
            (2, Data([0,248, 224,7, 31,0, 9,9]), 8),
            (3, Data([0,124, 224,3, 31,0, 9,9]), 8),
        ]
        for (format, bytes, pitch) in cases {
            let image = try XCTUnwrap(PlayerFrameImage.make(data: bytes, width: 3, height: 1, pitch: pitch, format: RMLLibretroPixelFormat(rawValue: format)!))
            var rendered = [UInt8](repeating: 0, count: 12)
            try rendered.withUnsafeMutableBytes { storage in
                let context = try XCTUnwrap(CGContext(data: storage.baseAddress, width: 3, height: 1, bitsPerComponent: 8, bytesPerRow: 12, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue))
                context.draw(image, in: CGRect(x: 0, y: 0, width: 3, height: 1))
            }
            XCTAssertEqual(rendered, [255,0,0,255, 0,255,0,255, 0,0,255,255], "format \(format)")
        }
    }

    func testSecondRowSkipsPaddingAndInvalidFramesAreRejected() throws {
        let format = RMLLibretroPixelFormat(rawValue: 2)!
        let bytes = Data([0,248, 9,9, 31,0, 9,9])
        let image = try XCTUnwrap(PlayerFrameImage.make(data: bytes, width: 1, height: 2, pitch: 4, format: format))
        XCTAssertEqual(image.height, 2)
        XCTAssertEqual(image.dataProvider?.data as Data?, Data([0,0,255,255, 255,0,0,255]))
        XCTAssertNil(PlayerFrameImage.make(data: bytes, width: 2, height: 2, pitch: 3, format: format))
        XCTAssertNil(PlayerFrameImage.make(data: bytes, width: 1, height: 3, pitch: 4, format: format))
        XCTAssertNil(PlayerFrameImage.make(data: bytes, width: Int.max, height: 1, pitch: 4, format: format))
        XCTAssertNil(PlayerFrameImage.make(data: bytes, width: 1, height: 1, pitch: 4, format: RMLLibretroPixelFormat(rawValue: 0)!))
    }

    /// Azahar hands over the 3DS framebuffer's alpha; Pokémon Ultra Sun's video call leaves
    /// it at zero. Rendered the way the player renders, the picture must stay opaque, keep
    /// its display values (not be treated as linear light), and stay top-down.
    func testHardwareFramesAreOpaqueUnchangedAndUpright() throws {
        let device = try XCTUnwrap(MTLCreateSystemDefaultDevice(), "Needs a Metal device")
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .rgba8Unorm, width: 1, height: 2, mipmapped: false)
        descriptor.usage = [.shaderRead]
        let texture = try XCTUnwrap(device.makeTexture(descriptor: descriptor))
        // Row 0 (top): mid tones with zero alpha. Row 1: opaque blue.
        let pixels: [UInt8] = [128, 64, 200, 0, 0, 0, 255, 255]
        texture.replace(region: MTLRegionMake2D(0, 0, 1, 2), mipmapLevel: 0, withBytes: pixels, bytesPerRow: 4)

        let image = try XCTUnwrap(PlayerFrameImage.hardware(texture, height: 2))
        var rendered = [UInt8](repeating: 0, count: 8)
        CIContext(mtlDevice: device).render(image, toBitmap: &rendered, rowBytes: 4, bounds: CGRect(x: 0, y: 0, width: 1, height: 2), format: .RGBA8, colorSpace: CGColorSpaceCreateDeviceRGB())
        // The bitmap's first row is the image's top row.
        let expected: [UInt8] = [128, 64, 200, 255, 0, 0, 255, 255]
        for (value, wanted) in zip(rendered, expected) {
            XCTAssertLessThanOrEqual(abs(Int(value) - Int(wanted)), 1, "\(rendered)")
        }
    }
}
