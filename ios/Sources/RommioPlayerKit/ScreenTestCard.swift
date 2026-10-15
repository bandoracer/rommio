import CoreGraphics
import Foundation

/// A pixel-exact test card at a screen's native resolution: colour bars, a grey ramp, one-
/// and two-pixel checkers, fine lines, a circle's stepped edge and a sprite — what shows a
/// screen shader's grid, scanlines, mask and colour. `ScreenMotionCard` sets it moving.
public enum ScreenTestCard {
    /// Where things sit on a card (game pixels from the top left).
    struct Layout {
        let barHeight: CGFloat
        let rampTop: CGFloat
        let rampHeight: CGFloat
        let panelTop: CGFloat
        let panel: CGFloat
        /// The open space below the checkers.
        let openTop: CGFloat
        let openBottom: CGFloat
        /// The sprite's row, near the bottom.
        let spriteTop: CGFloat

        init(width: Int, height: Int) {
            let h = CGFloat(height)
            barHeight = (h * 0.22).rounded()
            rampTop = barHeight
            rampHeight = (h * 0.1).rounded()
            panelTop = rampTop + rampHeight + 4
            panel = min((h - panelTop - 4).rounded(), 40)
            openTop = panelTop + panel + 4
            openBottom = h - 4
            spriteTop = h - CGFloat(ScreenTestCard.sprite.count * 2) - 4
        }
    }

    /// A little mushroom, drawn at 2×.
    static let sprite = [
        "....RRRR....",
        "..RRWWRRRR..",
        ".RRWWWWRRRR.",
        ".RRWWWWRRWWR",
        "RRRRWWRRWWWW",
        "RRRRRRRRRWWR",
        "RWWRRRRRRRRR",
        "WWWWRRRRWWRR",
        ".WWW.SSSS.W.",
        "....SSKSS...",
        "....SSKSS...",
        ".....SSSS...",
    ]

    public static func image(width: Int, height: Int) -> CGImage? {
        guard width > 8, height > 8,
              let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else {
            return nil
        }
        context.setAllowsAntialiasing(false)
        context.setShouldAntialias(false)
        // Top-left origin, one unit per game pixel.
        context.translateBy(x: 0, y: CGFloat(height))
        context.scaleBy(x: 1, y: -1)

        func fill(_ rect: CGRect, _ r: CGFloat, _ g: CGFloat, _ b: CGFloat) {
            context.setFillColor(red: r, green: g, blue: b, alpha: 1)
            context.fill(rect)
        }

        let w = CGFloat(width), h = CGFloat(height)
        let layout = Layout(width: width, height: height)
        fill(CGRect(x: 0, y: 0, width: w, height: h), 0.08, 0.1, 0.22)

        // Colour bars.
        let bars: [(CGFloat, CGFloat, CGFloat)] = [(1, 1, 1), (1, 1, 0), (0, 1, 1), (0, 1, 0), (1, 0, 1), (1, 0, 0), (0, 0, 1), (0, 0, 0)]
        let barWidth = w / CGFloat(bars.count)
        for (index, bar) in bars.enumerated() {
            fill(CGRect(x: (CGFloat(index) * barWidth).rounded(), y: 0, width: barWidth.rounded(.up), height: layout.barHeight), bar.0, bar.1, bar.2)
        }

        // A grey ramp in 16 steps.
        for step in 0..<16 {
            let v = CGFloat(step) / 15
            fill(CGRect(x: (CGFloat(step) * w / 16).rounded(), y: layout.rampTop, width: (w / 16).rounded(.up), height: layout.rampHeight), v, v, v)
        }

        // Checkers (1 px and 2 px) and fine lines.
        let panelTop = layout.panelTop
        let panel = layout.panel
        for y in 0..<Int(panel) {
            for x in 0..<Int(panel) {
                let one = (x + y) % 2 == 0
                let two = (x / 2 + y / 2) % 2 == 0
                if one { fill(CGRect(x: 4 + CGFloat(x), y: panelTop + CGFloat(y), width: 1, height: 1), 1, 1, 1) }
                if two { fill(CGRect(x: 8 + panel + CGFloat(x), y: panelTop + CGFloat(y), width: 1, height: 1), 1, 1, 1) }
            }
        }
        let linesLeft = 12 + panel * 2
        for x in stride(from: 0, to: Int(panel), by: 2) {
            fill(CGRect(x: linesLeft + CGFloat(x), y: panelTop, width: 1, height: panel / 2), 1, 0.85, 0.3)
        }
        for y in stride(from: 0, to: Int(panel / 2), by: 2) {
            fill(CGRect(x: linesLeft, y: panelTop + panel / 2 + CGFloat(y), width: panel, height: 1), 0.3, 0.85, 1)
        }

        // A circle's stepped edge.
        let circleLeft = linesLeft + panel + 6
        let radius = min(panel / 2, (w - circleLeft - 4) / 2)
        if radius > 4 {
            let centre = CGPoint(x: circleLeft + radius, y: panelTop + radius)
            for y in 0..<Int(radius * 2) {
                for x in 0..<Int(radius * 2) {
                    let dx = CGFloat(x) + 0.5 - radius, dy = CGFloat(y) + 0.5 - radius
                    let d = (dx * dx + dy * dy).squareRoot()
                    if d < radius && d > radius - 2.5 {
                        fill(CGRect(x: centre.x - radius + CGFloat(x), y: centre.y - radius + CGFloat(y), width: 1, height: 1), 1, 0.35, 0.45)
                    }
                }
            }
        }

        // The sprite, near the bottom.
        if layout.spriteTop > panelTop + panel {
            drawSprite(in: context, x: 6, y: layout.spriteTop)
        }
        return context.makeImage()
    }

    /// Draws the sprite with its top left at (x, y), in a context set to top-left game pixels.
    static func drawSprite(in context: CGContext, x: CGFloat, y: CGFloat) {
        for (row, line) in sprite.enumerated() {
            for (column, character) in line.enumerated() {
                let colour: (CGFloat, CGFloat, CGFloat)
                switch character {
                case "R": colour = (0.85, 0.15, 0.12)
                case "W": colour = (0.98, 0.95, 0.88)
                case "S": colour = (0.95, 0.8, 0.6)
                case "K": colour = (0.1, 0.08, 0.06)
                default: continue
                }
                context.setFillColor(red: colour.0, green: colour.1, blue: colour.2, alpha: 1)
                context.fill(CGRect(x: x + CGFloat(column * 2), y: y + CGFloat(row * 2), width: 2, height: 2))
            }
        }
    }
}
