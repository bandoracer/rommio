import Foundation
#if canImport(Metal)
import CoreGraphics
import Metal
import MetalKit

/// Draws a game frame through a screen (`ScreenShaders`) into a viewport of a render pass,
/// chaining the passes the screen needs first: a composite or S-Video signal encoded and
/// decoded, the glow blurred, an LCD's last frames kept. The shaders compile from source once
/// per device; a pipeline per function and pixel format is made on first use.
public final class ScreenRenderer {
    public let device: MTLDevice
    private let library: MTLLibrary
    private var pipelines: [String: MTLRenderPipelineState] = [:]
    private var scratch: [String: MTLTexture] = [:]
    /// The LCD's memory of earlier frames: two textures in turn, for one source texture.
    private var history: (textures: [MTLTexture], current: Int, source: ObjectIdentifier)?
    private var frameIndex = 0

    /// Fails when the device has no Metal or the shaders don't compile.
    public init(device: MTLDevice) throws {
        self.device = device
        library = try device.makeLibrary(source: ScreenShaders.source, options: nil)
    }

    /// The frame as a texture (8-bit RGBA, rows from the top, no colour conversion).
    public func texture(from image: CGImage) throws -> MTLTexture {
        try MTKTextureLoader(device: device).newTexture(cgImage: image, options: [
            .SRGB: false,
            .textureUsage: NSNumber(value: MTLTextureUsage.shaderRead.rawValue),
            .textureStorageMode: NSNumber(value: MTLStorageMode.private.rawValue),
        ])
    }

    /// Encodes `source` through `style` into `viewport` (drawable pixels) of `target`; `crop`
    /// is the part of the frame shown (x, y, width, height in 0…1). `newFrame` is the game's
    /// frame number when `source` holds a new frame (an LCD's response advances, a composite
    /// signal's carrier alternates); nil redraws the same frame (a still, a changed setting).
    public func encode(_ source: MTLTexture, style: ScreenStyle, commandBuffer: MTLCommandBuffer,
                       target: MTLRenderPassDescriptor, pixelFormat: MTLPixelFormat, viewport: CGRect,
                       crop: CGRect = CGRect(x: 0, y: 0, width: 1, height: 1), newFrame: Int? = nil) throws {
        if let newFrame { frameIndex = newFrame }
        let width = source.width, height = source.height
        let size = SIMD2(Float(width), Float(height))
        let base = style.uniforms(sourceSize: size, outputSize: size)
        var picture = source
        var bloom = source
        var decoded = false, bloomed = false

        switch style.kind {
        case .crt:
            if style.signal != .rgb,
               let signal = scratchTexture("signal", width: width * 4, height: height, format: .rg16Float),
               let rgb = scratchTexture("decoded", width: width * 4, height: height) {
                var u = base
                u.outputSize = SIMD2(Float(width * 4), Float(height))
                // NES and SNES alternate the carrier's phase every frame; a still holds one.
                let framePhase: Float = style.linePhase == 0 ? 0 : Float(frameIndex % 2) * .pi
                u.a = SIMD4(ScreenShaders.carrierCycles, framePhase, 0, 0)
                try pass("screen_signal_encode", into: signal, commandBuffer, textures: [source], uniforms: u)
                try pass("screen_signal_decode", into: rgb, commandBuffer, textures: [signal], uniforms: u)
                picture = rgb
                decoded = true
            }
            if style.glow > 0,
               let across = scratchTexture("glow-across", width: width, height: height),
               let down = scratchTexture("glow-down", width: width, height: height) {
                var u = base
                u.a = SIMD4(1, 0, Float(style.glowSize), 0)
                try pass("screen_blur", into: across, commandBuffer, textures: [picture], uniforms: u)
                u.a = SIMD4(0, 1, Float(style.glowSize), 0)
                try pass("screen_blur", into: down, commandBuffer, textures: [across], uniforms: u)
                bloom = down
                bloomed = true
            }
        case .lcd where style.response > 0:
            picture = try remembered(source, response: Float(style.response), advance: newFrame != nil, commandBuffer, base)
        case .lcd, .sharp, .smooth:
            break
        }

        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: target) else { return }
        encoder.setRenderPipelineState(try pipeline(style.kind.function, pixelFormat))
        encoder.setViewport(MTLViewport(originX: viewport.minX, originY: viewport.minY, width: viewport.width,
                                        height: viewport.height, znear: 0, zfar: 1))
        var uniforms = style.uniforms(sourceSize: size, outputSize: SIMD2(Float(viewport.width), Float(viewport.height)),
                                      crop: SIMD4(Float(crop.minX), Float(crop.minY), Float(crop.width), Float(crop.height)))
        uniforms.d.y = decoded ? 1 : 0
        uniforms.d.z = bloomed ? 1 : 0
        encoder.setFragmentBytes(&uniforms, length: MemoryLayout<ScreenUniforms>.stride, index: 0)
        encoder.setFragmentTexture(picture, index: 0)
        encoder.setFragmentTexture(bloom, index: 1)
        encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
        encoder.endEncoding()
    }

    /// The LCD's picture: each new frame mixed with what the panel still showed. Redrawing
    /// the same frame shows the same picture; a different still starts clean.
    private func remembered(_ source: MTLTexture, response: Float, advance: Bool, _ buffer: MTLCommandBuffer,
                            _ base: ScreenUniforms) throws -> MTLTexture {
        var u = base
        u.a = SIMD4(response, 0, 0, 0)
        let id = ObjectIdentifier(source)
        if var kept = history, kept.textures[0].width == source.width, kept.textures[0].height == source.height,
           advance || kept.source == id {
            guard advance else { return kept.textures[kept.current] }
            let next = 1 - kept.current
            try pass("screen_history", into: kept.textures[next], buffer, textures: [source, kept.textures[kept.current]], uniforms: u)
            kept.current = next
            kept.source = id
            history = kept
            return kept.textures[next]
        }
        guard let first = makeTexture(width: source.width, height: source.height, format: .rgba16Float),
              let second = makeTexture(width: source.width, height: source.height, format: .rgba16Float) else { return source }
        u.a.x = 0
        try pass("screen_history", into: first, buffer, textures: [source, source], uniforms: u)
        history = ([first, second], 0, id)
        return first
    }

    private func pass(_ function: String, into target: MTLTexture, _ buffer: MTLCommandBuffer, textures: [MTLTexture],
                      uniforms: ScreenUniforms) throws {
        let descriptor = MTLRenderPassDescriptor()
        descriptor.colorAttachments[0].texture = target
        descriptor.colorAttachments[0].loadAction = .dontCare
        descriptor.colorAttachments[0].storeAction = .store
        guard let encoder = buffer.makeRenderCommandEncoder(descriptor: descriptor) else { return }
        encoder.setRenderPipelineState(try pipeline(function, target.pixelFormat))
        var u = uniforms
        encoder.setFragmentBytes(&u, length: MemoryLayout<ScreenUniforms>.stride, index: 0)
        for (index, texture) in textures.enumerated() { encoder.setFragmentTexture(texture, index: index) }
        encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
        encoder.endEncoding()
    }

    private func scratchTexture(_ name: String, width: Int, height: Int, format: MTLPixelFormat = .rgba16Float) -> MTLTexture? {
        if let texture = scratch[name], texture.width == width, texture.height == height, texture.pixelFormat == format { return texture }
        let texture = makeTexture(width: width, height: height, format: format)
        scratch[name] = texture
        return texture
    }

    private func makeTexture(width: Int, height: Int, format: MTLPixelFormat) -> MTLTexture? {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: format, width: width, height: height, mipmapped: false)
        descriptor.usage = [.renderTarget, .shaderRead]
        descriptor.storageMode = .private
        return device.makeTexture(descriptor: descriptor)
    }

    private func pipeline(_ function: String, _ format: MTLPixelFormat) throws -> MTLRenderPipelineState {
        let key = "\(function)-\(format.rawValue)"
        if let cached = pipelines[key] { return cached }
        let descriptor = MTLRenderPipelineDescriptor()
        descriptor.vertexFunction = library.makeFunction(name: "screen_vertex")
        descriptor.fragmentFunction = library.makeFunction(name: function)
        descriptor.colorAttachments[0].pixelFormat = format
        let state = try device.makeRenderPipelineState(descriptor: descriptor)
        pipelines[key] = state
        return state
    }

    /// Renders `image` through `style` at `size` into a new image (for tests and previews).
    public func render(_ image: CGImage, style: ScreenStyle, size: CGSize,
                       crop: CGRect = CGRect(x: 0, y: 0, width: 1, height: 1)) throws -> CGImage? {
        try render(texture(from: image), style: style, size: size, crop: crop)
    }

    /// Renders a frame already on the GPU (`newFrame` as in `encode`).
    public func render(_ source: MTLTexture, style: ScreenStyle, size: CGSize,
                       crop: CGRect = CGRect(x: 0, y: 0, width: 1, height: 1), newFrame: Int? = nil) throws -> CGImage? {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .rgba8Unorm, width: Int(size.width),
                                                                  height: Int(size.height), mipmapped: false)
        descriptor.usage = [.renderTarget, .shaderRead]
        descriptor.storageMode = .shared
        guard let target = device.makeTexture(descriptor: descriptor),
              let queue = device.makeCommandQueue(), let buffer = queue.makeCommandBuffer() else { return nil }
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = target
        pass.colorAttachments[0].loadAction = .clear
        pass.colorAttachments[0].clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 1)
        pass.colorAttachments[0].storeAction = .store
        try encode(source, style: style, commandBuffer: buffer, target: pass, pixelFormat: .rgba8Unorm,
                   viewport: CGRect(origin: .zero, size: size), crop: crop, newFrame: newFrame)
        buffer.commit()
        buffer.waitUntilCompleted()

        let width = target.width, height = target.height
        var bytes = [UInt8](repeating: 0, count: width * height * 4)
        target.getBytes(&bytes, bytesPerRow: width * 4, from: MTLRegionMake2D(0, 0, width, height), mipmapLevel: 0)
        guard let provider = CGDataProvider(data: Data(bytes) as CFData) else { return nil }
        return CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: width * 4,
                       space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
                       provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent)
    }
}

/// The test card in motion, frame after frame: a sprite gliding, a bar sweeping and a sprite
/// shown on every other frame (games flickered sprites to show more of them, or to fake
/// transparency on a slow LCD). What shows an LCD's response and a composite signal's crawl.
/// Frames go into three textures in turn, so a frame isn't drawn over while the GPU reads it.
public final class ScreenMotionCard {
    /// The last frame drawn.
    public private(set) var texture: MTLTexture
    private let textures: [MTLTexture]
    private let context: CGContext
    private let still: CGImage

    public init?(device: MTLDevice, width: Int, height: Int) {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .rgba8Unorm, width: width, height: height, mipmapped: false)
        descriptor.usage = .shaderRead
        descriptor.storageMode = .shared
        let textures = (0..<3).compactMap { _ in device.makeTexture(descriptor: descriptor) }
        guard let still = ScreenTestCard.image(width: width, height: height),
              let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue),
              textures.count == 3 else { return nil }
        context.setAllowsAntialiasing(false)
        context.interpolationQuality = .none
        self.still = still
        self.context = context
        self.textures = textures
        texture = textures[0]
    }

    /// Draws frame `index` (60 a second); `texture` then holds it.
    @discardableResult
    public func draw(frame index: Int) -> MTLTexture {
        texture = textures[index % textures.count]
        let width = texture.width, height = texture.height
        context.draw(still, in: CGRect(x: 0, y: 0, width: width, height: height))
        context.saveGState()
        context.translateBy(x: 0, y: CGFloat(height))
        context.scaleBy(x: 1, y: -1)
        let layout = ScreenTestCard.Layout(width: width, height: height)
        // A bar sweeping across the open space, 2 pixels a frame.
        let travel = max(width - 40, 1)
        let sweep = index * 2 % (travel * 2)
        let barX = 20 + (sweep < travel ? sweep : travel * 2 - sweep)
        context.setFillColor(red: 1, green: 1, blue: 1, alpha: 1)
        context.fill(CGRect(x: CGFloat(barX), y: layout.openTop, width: 2, height: max(layout.openBottom - layout.openTop - 30, 4)))
        // A sprite gliding along the bottom, 1 pixel a frame.
        let glideTravel = max(width - 70, 1)
        let glide = index % (glideTravel * 2)
        let spriteX = 40 + (glide < glideTravel ? glide : glideTravel * 2 - glide)
        ScreenTestCard.drawSprite(in: context, x: CGFloat(spriteX), y: layout.spriteTop)
        // A sprite on every other frame.
        if index % 2 == 0 {
            ScreenTestCard.drawSprite(in: context, x: CGFloat(width - 34), y: layout.openTop + 4)
        }
        context.restoreGState()
        if let data = context.data {
            texture.replace(region: MTLRegionMake2D(0, 0, width, height), mipmapLevel: 0, withBytes: data, bytesPerRow: width * 4)
        }
        return texture
    }
}
#endif
