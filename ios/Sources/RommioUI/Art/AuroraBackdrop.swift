import CoreGraphics
import CoreImage
import Foundation

/// Bakes a cover into Aurora page light: `blur(b) saturate(s) brightness(v)` from
/// `AuroraArtTokens` (studio `--art-filter`), at a small size that the page
/// scales up. The blur is expressed against the studio's field width (150% of a
/// 402 pt phone), so it keeps its look at any bake size.
enum AuroraBackdrop {
    static let referenceFieldWidth: Double = 402 * 1.5

    static func render(_ image: CGImage, dark: Bool, context: CIContext) -> CGImage? {
        let source = CIImage(cgImage: image)
        let extent = source.extent
        guard extent.width > 1, extent.height > 1 else { return nil }
        let blurPoints = Double(dark ? AuroraArtTokens.backdropBlurDark : AuroraArtTokens.backdropBlurLight)
        let saturation = Double(dark ? AuroraArtTokens.backdropSaturationDark : AuroraArtTokens.backdropSaturationLight)
        let brightness = Double(dark ? AuroraArtTokens.backdropBrightnessDark : AuroraArtTokens.backdropBrightnessLight)
        let sigma = blurPoints / referenceFieldWidth * Double(extent.width)
        let filtered = source
            .applyingFilter("CIColorControls", parameters: [kCIInputSaturationKey: saturation])
            .applyingFilter("CIColorMatrix", parameters: [
                "inputRVector": CIVector(x: brightness, y: 0, z: 0, w: 0),
                "inputGVector": CIVector(x: 0, y: brightness, z: 0, w: 0),
                "inputBVector": CIVector(x: 0, y: 0, z: brightness, w: 0),
            ])
            .applyingFilter("CIColorClamp")
            .clampedToExtent()
            .applyingFilter("CIGaussianBlur", parameters: [kCIInputRadiusKey: sigma])
            .cropped(to: extent)
        return context.createCGImage(filtered, from: extent, format: .RGBA8, colorSpace: CGColorSpace(name: CGColorSpace.sRGB)!)
    }
}
