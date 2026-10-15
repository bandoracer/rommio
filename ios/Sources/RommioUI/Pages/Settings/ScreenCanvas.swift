import SwiftUI
import RommioPlayerKit
#if os(iOS)
import MetalKit
#endif

/// A frame drawn through a screen (`ScreenRenderer`) at the view's full pixel resolution:
/// the shaders work in the display's own pixels (the mask's phosphors, the LCD's gaps), so a
/// scaled-up image of a smaller render would blur them. In motion it plays the moving test
/// card at 60 frames a second instead of `frame`.
struct ScreenCanvas: View {
    let frame: CGImage?
    let style: ScreenStyle
    let crop: CGRect
    var motion = false

    var body: some View {
        #if os(iOS)
        ScreenMetalView(frame: frame, style: style, crop: crop, motion: motion)
        #else
        Color.black
        #endif
    }
}

#if os(iOS)
private struct ScreenMetalView: UIViewRepresentable {
    let frame: CGImage?
    let style: ScreenStyle
    let crop: CGRect
    let motion: Bool

    func makeCoordinator() -> ScreenDrawer { ScreenDrawer() }

    func makeUIView(context: Context) -> MTKView {
        let view = MTKView(frame: .zero, device: context.coordinator.device)
        // The shaders write the frame's own values: no sRGB conversion on the way out.
        view.colorPixelFormat = .bgra8Unorm
        view.clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 1)
        view.framebufferOnly = true
        view.preferredFramesPerSecond = 60
        view.backgroundColor = .black
        view.delegate = context.coordinator
        return view
    }

    func updateUIView(_ view: MTKView, context: Context) {
        context.coordinator.update(frame: frame, style: style, crop: crop, motion: motion)
        // A still draws when something changes; motion draws every frame.
        view.enableSetNeedsDisplay = !motion
        view.isPaused = !motion
        if !motion { view.setNeedsDisplay() }
    }

    static func dismantleUIView(_ view: MTKView, coordinator: ScreenDrawer) {
        view.isPaused = true
    }
}

@MainActor
final class ScreenDrawer: NSObject, MTKViewDelegate {
    let device = MTLCreateSystemDefaultDevice()
    private lazy var renderer: ScreenRenderer? = device.flatMap { try? ScreenRenderer(device: $0) }
    private lazy var queue = device?.makeCommandQueue()
    private var frame: CGImage?
    private var texture: MTLTexture?
    private var motionCard: ScreenMotionCard?
    private var motion = false
    private var frameIndex = 0
    private var style = ScreenStyle.sharp
    private var crop = CGRect(x: 0, y: 0, width: 1, height: 1)

    func update(frame: CGImage?, style: ScreenStyle, crop: CGRect, motion: Bool) {
        if frame !== self.frame {
            self.frame = frame
            texture = frame.flatMap { try? renderer?.texture(from: $0) }
        }
        self.motion = motion
        if motion, let frame, let device, motionCard?.texture.width != frame.width || motionCard?.texture.height != frame.height {
            motionCard = ScreenMotionCard(device: device, width: frame.width, height: frame.height)
        }
        self.style = style
        self.crop = crop
    }

    func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}

    func draw(in view: MTKView) {
        guard let renderer, let pass = view.currentRenderPassDescriptor, let drawable = view.currentDrawable,
              let buffer = queue?.makeCommandBuffer() else { return }
        var source = texture
        var newFrame: Int?
        if motion, let motionCard {
            frameIndex += 1
            source = motionCard.draw(frame: frameIndex)
            newFrame = frameIndex
        }
        if let source {
            try? renderer.encode(source, style: style, commandBuffer: buffer, target: pass, pixelFormat: view.colorPixelFormat,
                                 viewport: CGRect(origin: .zero, size: view.drawableSize), crop: crop, newFrame: newFrame)
        } else if let encoder = buffer.makeRenderCommandEncoder(descriptor: pass) {
            encoder.endEncoding()
        }
        buffer.present(drawable)
        buffer.commit()
    }
}
#endif
