import CoreGraphics
import Foundation
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
}
