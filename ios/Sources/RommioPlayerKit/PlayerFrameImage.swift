import Foundation
import CoreGraphics
import RommioPlayerBridge

/// Shared by the actual surface and pixel-pattern tests, including padded core frame rows.
enum PlayerFrameImage {
    static func make(data: Data, width: Int, height: Int, pitch: Int, format: RMLLibretroPixelFormat) -> CGImage? {
        guard width > 0, height > 0, pitch > 0 else { return nil }
        let bytesPerPixel: Int
        switch format.rawValue {
        case 1: bytesPerPixel = 4
        case 2, 3: bytesPerPixel = 2
        default: return nil
        }
        let (rowBytes, rowOverflow) = width.multipliedReportingOverflow(by: bytesPerPixel)
        let (required, sizeOverflow) = pitch.multipliedReportingOverflow(by: height)
        let (outputPitch, outputRowOverflow) = width.multipliedReportingOverflow(by: 4)
        let (outputSize, outputOverflow) = outputPitch.multipliedReportingOverflow(by: height)
        guard !rowOverflow, !sizeOverflow, !outputRowOverflow, !outputOverflow,
              pitch >= rowBytes, data.count >= required else { return nil }

        let pixels: Data
        let imagePitch: Int
        if bytesPerPixel == 4 {
            pixels = data
            imagePitch = pitch
        } else {
            imagePitch = outputPitch
            var expanded = Data(count: outputSize)
            data.withUnsafeBytes { source in
                expanded.withUnsafeMutableBytes { destination in
                    let output = destination.bindMemory(to: UInt8.self)
                    for y in 0..<height {
                        for x in 0..<width {
                            let value = UInt16(littleEndian: source.loadUnaligned(fromByteOffset: y * pitch + x * 2, as: UInt16.self))
                            let red = (value >> (format.rawValue == 2 ? 11 : 10)) & 31
                            let green = (value >> 5) & (format.rawValue == 2 ? 63 : 31)
                            let blue = value & 31
                            let index = y * outputPitch + x * 4
                            output[index] = UInt8((blue << 3) | (blue >> 2))
                            output[index + 1] = format.rawValue == 2
                                ? UInt8((green << 2) | (green >> 4))
                                : UInt8((green << 3) | (green >> 2))
                            output[index + 2] = UInt8((red << 3) | (red >> 2))
                            output[index + 3] = 255
                        }
                    }
                }
            }
            pixels = expanded
        }
        guard let provider = CGDataProvider(data: pixels as CFData) else { return nil }
        return CGImage(
            width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32,
            bytesPerRow: imagePitch, space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: .byteOrder32Little.union(.init(rawValue: CGImageAlphaInfo.noneSkipFirst.rawValue)),
            provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent
        )
    }
}
