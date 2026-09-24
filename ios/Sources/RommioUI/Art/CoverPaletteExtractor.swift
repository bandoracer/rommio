import CoreGraphics
import CoreImage
import Foundation

/// Extracts a cover's five-colour palette (four base colours, then the accent),
/// deterministically: Core Image averages the cover down to a small grid, then
/// median cut seeds a k-means over the grid's colours.
enum CoverPaletteExtractor {
    /// Grid the cover is averaged down to (its longer side).
    static let gridSize = 24
    /// Median-cut boxes that seed k-means, and k-means passes.
    static let clusterCount = 8
    static let passes = 6
    /// A cluster must hold at least this share of the cover to be the accent.
    static let accentMinimumShare = 0.02

    /// The palette of a decoded cover, or nil when it can't be read.
    static func palette(from image: CGImage, context: CIContext) -> CoverPalette? {
        guard let pixels = gridPixels(of: image, context: context), !pixels.isEmpty else { return nil }
        return palette(pixels: pixels)
    }

    /// Area-averages the cover to at most `gridSize` × `gridSize` pixels (Lanczos
    /// downsampling filters before it samples, so every grid cell is an average).
    static func gridPixels(of image: CGImage, context: CIContext) -> [ArtRGB]? {
        let input = CIImage(cgImage: image)
        let extent = input.extent
        guard extent.width > 0, extent.height > 0 else { return nil }
        let scale = Double(gridSize) / Double(max(extent.width, extent.height))
        guard let filter = CIFilter(name: "CILanczosScaleTransform") else { return nil }
        filter.setValue(input.clampedToExtent().cropped(to: extent), forKey: kCIInputImageKey)
        filter.setValue(scale, forKey: kCIInputScaleKey)
        filter.setValue(1.0, forKey: kCIInputAspectRatioKey)
        guard let scaled = filter.outputImage else { return nil }
        let width = max(1, Int((Double(extent.width) * scale).rounded()))
        let height = max(1, Int((Double(extent.height) * scale).rounded()))
        let origin = scaled.extent.origin
        let bounds = CGRect(x: origin.x, y: origin.y, width: CGFloat(width), height: CGFloat(height))
        var bytes = [UInt8](repeating: 0, count: width * height * 4)
        let space = CGColorSpace(name: CGColorSpace.sRGB)!
        context.render(scaled, toBitmap: &bytes, rowBytes: width * 4, bounds: bounds, format: .RGBA8, colorSpace: space)
        var pixels: [ArtRGB] = []
        pixels.reserveCapacity(width * height)
        for index in stride(from: 0, to: bytes.count, by: 4) where bytes[index + 3] > 16 {
            // Premultiplied: undo it for partly transparent cells.
            let alpha = Double(bytes[index + 3]) / 255
            pixels.append(ArtRGB(red: min(1, Double(bytes[index]) / 255 / alpha),
                                 green: min(1, Double(bytes[index + 1]) / 255 / alpha),
                                 blue: min(1, Double(bytes[index + 2]) / 255 / alpha)))
        }
        return pixels
    }

    /// The palette of a set of colours: the four most populous clusters, then the
    /// most characterful cluster (chroma weighted by the square root of its share).
    static func palette(pixels: [ArtRGB]) -> CoverPalette {
        guard !pixels.isEmpty else { return CoverPalette(colors: [ArtRGB(red: 0.5, green: 0.5, blue: 0.5)]) }
        let clusters = kMeans(pixels, seeds: medianCut(pixels, boxes: clusterCount), passes: passes)
            .filter { $0.count > 0 }
            .sorted { $0.count != $1.count ? $0.count > $1.count : $0.mean.hex < $1.mean.hex }
        let total = Double(pixels.count)
        let accentIndex = clusters.indices
            .filter { Double(clusters[$0].count) / total >= accentMinimumShare }
            .max { lhs, rhs in
                let a = characterScore(clusters[lhs], total: total), b = characterScore(clusters[rhs], total: total)
                return a != b ? a < b : lhs > rhs
            } ?? 0
        let accent = clusters[accentIndex].mean
        var base = clusters.enumerated().filter { $0.offset != accentIndex }.map(\.element.mean)
        if base.isEmpty { base = [accent] }
        return CoverPalette(colors: Array(base.prefix(4)) + Array(repeating: base.last!, count: max(0, 4 - base.count)) + [accent])
    }

    struct Cluster: Hashable {
        let mean: ArtRGB
        let count: Int
    }

    private static func characterScore(_ cluster: Cluster, total: Double) -> Double {
        ArtColorMath.chroma(ArtColorMath.hsl(cluster.mean)) * (Double(cluster.count) / total).squareRoot()
    }

    /// Median cut: split the box with the widest channel range (weighted by its
    /// size) at the median of that channel, until there are `boxes` boxes.
    static func medianCut(_ pixels: [ArtRGB], boxes: Int) -> [ArtRGB] {
        var queue: [[ArtRGB]] = [pixels]
        while queue.count < boxes {
            var best = -1
            var bestScore = 0.0
            var bestChannel = 0
            for (index, box) in queue.enumerated() where box.count > 1 {
                let (channel, range) = widestChannel(box)
                let score = range * Double(box.count)
                if score > bestScore { best = index; bestScore = score; bestChannel = channel }
            }
            guard best >= 0, bestScore > 0 else { break }
            let box = queue.remove(at: best).sorted { component($0, bestChannel) < component($1, bestChannel) }
            let middle = box.count / 2
            queue.insert(Array(box[middle...]), at: best)
            queue.insert(Array(box[..<middle]), at: best)
        }
        return queue.map(mean)
    }

    /// Lloyd's k-means from fixed seeds; ties go to the lower cluster index.
    static func kMeans(_ pixels: [ArtRGB], seeds: [ArtRGB], passes: Int) -> [Cluster] {
        var means = seeds
        var assignment = [Int](repeating: 0, count: pixels.count)
        for _ in 0 ..< max(1, passes) {
            for (index, pixel) in pixels.enumerated() {
                var best = 0
                var bestDistance = Double.infinity
                for (cluster, centre) in means.enumerated() {
                    let distance = squaredDistance(pixel, centre)
                    if distance < bestDistance { best = cluster; bestDistance = distance }
                }
                assignment[index] = best
            }
            means = means.indices.map { cluster in
                let members = pixels.indices.filter { assignment[$0] == cluster }.map { pixels[$0] }
                return members.isEmpty ? means[cluster] : mean(members)
            }
        }
        return means.indices.map { cluster in
            Cluster(mean: quantised(means[cluster]), count: assignment.filter { $0 == cluster }.count)
        }
    }

    private static func widestChannel(_ box: [ArtRGB]) -> (Int, Double) {
        var result = (0, 0.0)
        for channel in 0 ..< 3 {
            let values = box.map { component($0, channel) }
            let range = (values.max() ?? 0) - (values.min() ?? 0)
            if range > result.1 { result = (channel, range) }
        }
        return result
    }

    private static func component(_ colour: ArtRGB, _ channel: Int) -> Double {
        switch channel {
        case 0: colour.red
        case 1: colour.green
        default: colour.blue
        }
    }

    private static func mean(_ colours: [ArtRGB]) -> ArtRGB {
        let count = Double(max(colours.count, 1))
        return ArtRGB(red: colours.reduce(0) { $0 + $1.red } / count,
                      green: colours.reduce(0) { $0 + $1.green } / count,
                      blue: colours.reduce(0) { $0 + $1.blue } / count)
    }

    private static func quantised(_ colour: ArtRGB) -> ArtRGB {
        ArtRGB(red8: ArtRGB.byte(colour.red), green8: ArtRGB.byte(colour.green), blue8: ArtRGB.byte(colour.blue))
    }

    private static func squaredDistance(_ a: ArtRGB, _ b: ArtRGB) -> Double {
        let r = a.red - b.red, g = a.green - b.green, bl = a.blue - b.blue
        // Perceptual-ish weights (green reads brightest).
        return 2 * r * r + 4 * g * g + 3 * bl * bl
    }
}
