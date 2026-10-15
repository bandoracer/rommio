import Foundation

/// Shaders that draw a game frame the way a console's screen showed it: a CRT's scanlines,
/// phosphor mask and glow, fed RGB or a composite or S-Video signal; a handheld LCD's pixel
/// grid, subpixels, colours, slow response and (on the Game Boy) the shadow each pixel casts
/// on the screen behind it. Metal source compiled when the app runs (`ScreenRenderer`, which
/// chains the passes). Tuned in Settings › Screen playground before the player uses them.
public enum ScreenShaders {
    /// The fragment functions.
    public enum Kind: String, CaseIterable, Sendable {
        /// Each game pixel a crisp square (sharp-bilinear: even widths at any scale).
        case sharp
        /// Bilinear smoothing, as the player draws today.
        case smooth
        case crt
        case lcd

        var function: String { "screen_\(rawValue)" }
    }

    /// Carrier cycles across the picture: NTSC's 3.58 MHz over the active line (256 pixels at
    /// the SNES's 5.37 MHz, 1.5 pixels a cycle; 320 at the Genesis's 6.71 MHz comes to the same).
    static let carrierCycles: Float = 256 / 1.5

    static let source = #"""
    #include <metal_stdlib>
    using namespace metal;

    struct VertexOut {
        float4 position [[position]];
        float2 uv;
    };

    struct ScreenUniforms {
        float2 sourceSize;   // game pixels
        float2 outputSize;   // the screen's size in drawable pixels
        float4 crop;         // the part of the frame shown: x, y, w, h in 0…1 (magnify)
        float4 a;
        float4 b;
        float4 c;
        float4 d;
        float4 e;            // the signal: kind (0 RGB, 1 S-Video, 2 composite), line phase
                             // (radians), colour filter and luma filter (carrier cycles)
        float4 f;            // glow size (game pixels), LCD response
        float4 colorR;       // colour correction: each output channel's weights; w unused
        float4 colorG;
        float4 colorB;
        float4 paletteDark;  // mono screens: the darkest and lightest shade
        float4 paletteLight;
        float4 background;   // what the gaps between LCD pixels show
    };

    // A quad covering the viewport; uv from the top left.
    vertex VertexOut screen_vertex(uint vid [[vertex_id]]) {
        float2 corner = float2(vid & 1, vid >> 1);
        VertexOut out;
        out.position = float4(corner * 2.0 - 1.0, 0.0, 1.0);
        out.uv = float2(corner.x, 1.0 - corner.y);
        return out;
    }

    constexpr sampler pointSampler(filter::nearest, address::clamp_to_edge);
    constexpr sampler linearSampler(filter::linear, address::clamp_to_edge);

    static float luma(float3 c) { return dot(c, float3(0.299, 0.587, 0.114)); }

    // The frame position (game pixels) under the fragment.
    static float2 sourcePosition(float2 uv, constant ScreenUniforms &u) {
        return (u.crop.xy + uv * u.crop.zw) * u.sourceSize;
    }

    static float2 pixelScale(constant ScreenUniforms &u) {
        return u.outputSize / max(u.sourceSize * u.crop.zw, float2(1.0));
    }

    // Sharp bilinear: nearest inside each game pixel, a one-output-pixel blend at its edges,
    // so pixels stay square and even at fractional scales.
    static float3 sharpSample(texture2d<float> src, float2 p, float2 scale, float2 size) {
        float2 floored = floor(p);
        float2 s = fract(p);
        float2 range = 0.5 - 0.5 / max(scale, float2(1.0));
        float2 centre = s - 0.5;
        float2 f = (centre - clamp(centre, -range, range)) * scale + 0.5;
        return src.sample(linearSampler, (floored + f) / size).rgb;
    }

    fragment float4 screen_sharp(VertexOut in [[stage_in]], texture2d<float> src [[texture(0)]],
                                 constant ScreenUniforms &u [[buffer(0)]]) {
        float2 p = sourcePosition(in.uv, u);
        return float4(sharpSample(src, p, pixelScale(u), u.sourceSize), 1.0);
    }

    fragment float4 screen_smooth(VertexOut in [[stage_in]], texture2d<float> src [[texture(0)]],
                                  constant ScreenUniforms &u [[buffer(0)]]) {
        float2 p = sourcePosition(in.uv, u);
        return float4(src.sample(linearSampler, p / u.sourceSize).rgb, 1.0);
    }

    // MARK: LCD
    // a: grid strength, gap width (share of a pixel), subpixel stripes, gaps show the
    //    background (1) or darken (0)
    // b: shadow strength, shadow offset (game pixels), brightness, colour correction amount
    // c: mono (1) or colour (0), screen darkening (extra gamma), unused, unused
    fragment float4 screen_lcd(VertexOut in [[stage_in]], texture2d<float> src [[texture(0)]],
                               constant ScreenUniforms &u [[buffer(0)]]) {
        float2 scale = pixelScale(u);
        float2 p = sourcePosition(in.uv, u);
        float3 c = sharpSample(src, p, scale, u.sourceSize);

        // The screen's own colours: a mono palette, or the LCD's colour response.
        if (u.c.x > 0.5) {
            float l = luma(c);
            c = mix(u.paletteDark.rgb, u.paletteLight.rgb, l);
        } else {
            float3 lin = pow(max(c, 0.0), 2.2 + u.c.y);
            float3 corrected = float3(dot(u.colorR.xyz, lin), dot(u.colorG.xyz, lin), dot(u.colorB.xyz, lin));
            corrected = pow(max(corrected, 0.0), 1.0 / 2.2);
            c = mix(c, corrected, u.b.w);
        }

        // Subpixel stripes (red, green, blue across each pixel).
        float2 f = fract(p);
        if (u.a.z > 0.0) {
            float x = f.x * 3.0;
            float3 stripe = float3(1.0 - smoothstep(0.85, 1.15, x),
                                   smoothstep(0.85, 1.15, x) * (1.0 - smoothstep(1.85, 2.15, x)),
                                   smoothstep(1.85, 2.15, x));
            c *= mix(float3(1.0), stripe * 2.4, u.a.z);
        }

        // The grid: the gap between pixels, measured in drawable pixels so it stays crisp.
        float2 edge = min(f, 1.0 - f) * scale;
        float2 gap = u.a.y * scale * 0.5;
        float2 inside = smoothstep(gap - 0.5, gap + 0.5, edge);
        float cell = inside.x * inside.y;

        // The screen behind the pixels, shadowed by the pixel up and to the left (the Game
        // Boy's segments sit above a reflective layer).
        float3 behind = u.background.rgb;
        if (u.b.x > 0.0) {
            float2 q = p - u.b.y;
            float3 caster = src.sample(pointSampler, (floor(q) + 0.5) / u.sourceSize).rgb;
            float darkness = 1.0 - luma(caster);
            behind *= 1.0 - u.b.x * darkness;
            if (u.c.x > 0.5) {
                // The pixel's own area keeps a little of the shadow too.
                c *= 1.0 - u.b.x * darkness * 0.25;
            }
        }

        // Inside a pixel its colour; in the gaps the screen behind, or the colour darkened.
        float coverage = mix(1.0 - u.a.x, 1.0, cell);
        float3 colour = u.a.w > 0.5 ? mix(behind, c, coverage) : c * coverage;
        return float4(colour * u.b.z, 1.0);
    }

    // MARK: CRT
    // a: scanline strength, thinnest beam, widest beam (game pixels), horizontal blur
    // b: mask type (0 none, 1 aperture grille, 2 slot, 3 dots), mask strength, mask pitch
    //    (drawable pixels per phosphor), brightness
    // c: curvature, vignette, glow, chroma bleed
    // d: corner radius, the source is a decoded signal (1), a glow texture is bound (1)

    static float3 toYIQ(float3 c) {
        return float3(dot(c, float3(0.299, 0.587, 0.114)),
                      dot(c, float3(0.596, -0.274, -0.322)),
                      dot(c, float3(0.211, -0.523, 0.312)));
    }

    static float3 fromYIQ(float3 c) {
        return float3(c.x + 0.956 * c.y + 0.621 * c.z,
                      c.x - 0.272 * c.y - 0.647 * c.z,
                      c.x - 1.106 * c.y + 1.703 * c.z);
    }

    // One row of the frame filtered horizontally: luma with `sigma`, chroma wider (a
    // composite signal's colour smears further than its brightness).
    static float3 scanRow(texture2d<float> src, float x, float row, float sigma, float bleed, float2 size) {
        float3 sharp = 0.0, wide = 0.0;
        float sharpWeight = 0.0, wideWeight = 0.0;
        float wideSigma = sigma + bleed * 2.0;
        for (int i = -4; i <= 4; i++) {
            float px = floor(x) + float(i) + 0.5;
            float dist = px - x;
            float3 texel = src.sample(pointSampler, float2(px, row + 0.5) / size).rgb;
            float w1 = exp(-dist * dist / (2.0 * sigma * sigma));
            float w2 = exp(-dist * dist / (2.0 * wideSigma * wideSigma));
            sharp += texel * w1; sharpWeight += w1;
            wide += texel * w2; wideWeight += w2;
        }
        float3 a = toYIQ(sharp / sharpWeight);
        float3 b = toYIQ(wide / wideWeight);
        return max(fromYIQ(float3(a.x, mix(a.y, b.y, min(bleed, 1.0)), mix(a.z, b.z, min(bleed, 1.0)))), 0.0);
    }

    static float beam(float distance, float3 colour, float thinnest, float widest) {
        float width = mix(thinnest, widest, sqrt(luma(colour)));
        return exp(-0.5 * (distance * distance) / (width * width));
    }

    // One row of a decoded signal (already band-limited, several samples per game pixel):
    // a light gaussian over it.
    static float3 scanRowSignal(texture2d<float> src, float x, float row, float sigma, float2 size) {
        float3 sum = 0.0;
        float total = 0.0;
        for (int i = -4; i <= 4; i++) {
            float dx = float(i) * 0.5;
            float w = exp(-dx * dx / (2.0 * sigma * sigma));
            sum += src.sample(linearSampler, float2((x + dx) / size.x, (row + 0.5) / size.y)).rgb * w;
            total += w;
        }
        return sum / total;
    }

    // MARK: Composite and S-Video
    // The frame becomes the signal a console sent a TV: brightness (Y) plus colour (I and Q)
    // riding a carrier, sampled four times per game pixel; the TV then separates them again
    // with filters that never quite manage it. A composite cable carries both on one wire, so
    // fine brightness detail near the carrier's frequency decodes as colour (rainbows) and the
    // luma filter that removes the carrier also blends dithering. S-Video keeps them apart.
    // a: carrier cycles across the picture, frame phase (radians)
    constant float kTau = 6.28318530718;

    static float carrierPhase(float sample, float row, float samples, constant ScreenUniforms &u) {
        return kTau * (sample + 0.5) / samples * u.a.x + row * u.e.y + u.a.y;
    }

    // Output: luma in r, the modulated colour in g.
    fragment float4 screen_signal_encode(VertexOut in [[stage_in]], texture2d<float> src [[texture(0)]],
                                         constant ScreenUniforms &u [[buffer(0)]]) {
        float sample = floor(in.position.x);
        float row = floor(in.position.y);
        float x = (sample + 0.5) / u.outputSize.x * u.sourceSize.x;
        float3 rgb = src.sample(pointSampler, float2((floor(x) + 0.5) / u.sourceSize.x, (row + 0.5) / u.sourceSize.y)).rgb;
        float3 yiq = toYIQ(rgb);
        float phase = carrierPhase(sample, row, u.outputSize.x, u);
        return float4(yiq.x, yiq.y * cos(phase) + yiq.z * sin(phase), 0.0, 1.0);
    }

    fragment float4 screen_signal_decode(VertexOut in [[stage_in]], texture2d<float> signal [[texture(0)]],
                                         constant ScreenUniforms &u [[buffer(0)]]) {
        float sample = floor(in.position.x);
        float row = floor(in.position.y);
        float samples = u.outputSize.x;
        float v = (row + 0.5) / u.outputSize.y;
        float period = samples / u.a.x;
        bool composite = u.e.x > 1.5;

        // Luma: a lowpass narrow enough to keep detail, wide enough to (mostly) drop the carrier.
        float lumaSigma = max(u.e.w * period, 0.5);
        int lumaRadius = min(int(ceil(lumaSigma * 2.5)), 24);
        float y = 0.0, yWeight = 0.0;
        for (int k = -lumaRadius; k <= lumaRadius; k++) {
            float2 s = signal.sample(pointSampler, float2((sample + float(k) + 0.5) / samples, v)).rg;
            float w = exp(-float(k * k) / (2.0 * lumaSigma * lumaSigma));
            y += (composite ? s.r + s.g : s.r) * w;
            yWeight += w;
        }

        // Colour: multiply by the carrier, then lowpass what's left.
        float chromaSigma = max(u.e.z * period * 0.5, 1.0);
        int chromaRadius = min(int(ceil(chromaSigma * 2.5)), 40);
        float i = 0.0, q = 0.0, cWeight = 0.0;
        for (int k = -chromaRadius; k <= chromaRadius; k++) {
            float at = sample + float(k);
            float2 s = signal.sample(pointSampler, float2((at + 0.5) / samples, v)).rg;
            float c = composite ? s.r + s.g : s.g;
            float phase = carrierPhase(at, row, samples, u);
            float w = exp(-float(k * k) / (2.0 * chromaSigma * chromaSigma));
            i += 2.0 * c * cos(phase) * w;
            q += 2.0 * c * sin(phase) * w;
            cWeight += w;
        }
        return float4(max(fromYIQ(float3(y / yWeight, i / cWeight, q / cWeight)), 0.0), 1.0);
    }

    // MARK: Glow
    // A separable gaussian at the frame's resolution; a: direction (1, 0 or 0, 1), size
    fragment float4 screen_blur(VertexOut in [[stage_in]], texture2d<float> src [[texture(0)]],
                                constant ScreenUniforms &u [[buffer(0)]]) {
        float2 p = in.uv * u.sourceSize;
        float sigma = max(u.a.z, 0.5);
        int radius = min(int(ceil(sigma * 2.5)), 16);
        float3 sum = 0.0;
        float total = 0.0;
        for (int k = -radius; k <= radius; k++) {
            float w = exp(-float(k * k) / (2.0 * sigma * sigma));
            sum += src.sample(linearSampler, (p + u.a.xy * float(k)) / u.sourceSize).rgb * w;
            total += w;
        }
        return float4(sum / total, 1.0);
    }

    // MARK: LCD response
    // Each pixel keeps part of the last frame (in light, not in the signal's gamma): an LCD's
    // crystals take frames to turn. a: how much of the last frame stays.
    fragment float4 screen_history(VertexOut in [[stage_in]], texture2d<float> src [[texture(0)]],
                                   texture2d<float> last [[texture(1)]], constant ScreenUniforms &u [[buffer(0)]]) {
        float2 uv = (floor(in.position.xy) + 0.5) / u.sourceSize;
        float3 now = pow(src.sample(pointSampler, uv).rgb, 2.2);
        float3 before = pow(last.sample(pointSampler, uv).rgb, 2.2);
        return float4(pow(mix(now, before, u.a.x), 1.0 / 2.2), 1.0);
    }

    fragment float4 screen_crt(VertexOut in [[stage_in]], texture2d<float> src [[texture(0)]],
                               texture2d<float> bloom [[texture(1)]], constant ScreenUniforms &u [[buffer(0)]]) {
        // Curvature: the tube's face bends the picture outward.
        float2 cc = in.uv * 2.0 - 1.0;
        cc *= 1.0 + u.c.x * (cc.yx * cc.yx) * 0.5;
        float2 uv = cc * 0.5 + 0.5;
        if (any(uv < 0.0) || any(uv > 1.0)) return float4(0.0, 0.0, 0.0, 1.0);
        // Rounded corners.
        float2 corner = max(abs(cc) - (1.0 - u.d.x), 0.0) / max(u.d.x, 0.0001);
        float rounded = 1.0 - smoothstep(0.9, 1.0, length(corner));

        float2 p = sourcePosition(uv, u);
        float sigma = max(u.a.w, 0.3);
        float y = p.y - 0.5;
        float row = floor(y);
        float dy = y - row;
        float row0 = clamp(row, 0.0, u.sourceSize.y - 1.0);
        float row1 = clamp(row + 1.0, 0.0, u.sourceSize.y - 1.0);
        bool decoded = u.d.y > 0.5;
        float3 c0 = decoded ? scanRowSignal(src, p.x, row0, sigma, u.sourceSize) : scanRow(src, p.x, row0, sigma, u.c.w, u.sourceSize);
        float3 c1 = decoded ? scanRowSignal(src, p.x, row1, sigma, u.sourceSize) : scanRow(src, p.x, row1, sigma, u.c.w, u.sourceSize);

        // Each line is a beam whose width grows with its brightness.
        float3 lit = c0 * beam(dy, c0, u.a.y, u.a.z) + c1 * beam(1.0 - dy, c1, u.a.y, u.a.z);
        float3 flat = mix(c0, c1, dy);
        float3 colour = mix(flat, lit, u.a.x);

        // Glow: the phosphor's light spreading through the glass (blurred in its own passes).
        if (u.c.z > 0.0 && u.d.z > 0.5) {
            colour += bloom.sample(linearSampler, p / u.sourceSize).rgb * u.c.z;
        }

        // The phosphor mask, in drawable pixels.
        int type = int(u.b.x + 0.5);
        if (type > 0) {
            float pitch = max(u.b.z, 1.0);
            float2 pos = in.position.xy / pitch;
            float column = floor(pos.x);
            float shift = (type == 3) ? fmod(floor(pos.y), 2.0) * 1.5 : 0.0;
            int index = int(fmod(column + shift, 3.0));
            float3 mask = float3(1.0 - u.b.y);
            mask[index] = 1.0;
            if (type == 2) {
                // Slot mask: every triad is broken by a dark line, staggered.
                float triad = floor(column / 3.0);
                float line = fmod(pos.y + fmod(triad, 2.0) * 2.0, 4.0);
                if (line < 0.75) mask *= 1.0 - u.b.y * 0.8;
            }
            colour *= mask;
        }
        colour *= u.b.w;
        // Highlights roll off rather than clip (the brightness makes up for the mask and
        // the dark between the lines).
        colour = select(colour, 0.8 + 0.2 * (1.0 - exp(-(colour - 0.8) / 0.2)), colour > 0.8);

        // Vignette.
        float2 v = uv * (1.0 - uv.yx);
        float vignette = pow(clamp(v.x * v.y * 16.0, 0.0, 1.0), 0.3);
        colour *= mix(1.0, vignette, u.c.y);
        return float4(colour * rounded, 1.0);
    }
    """#
}

/// A console screen's look: which shader and its settings.
public struct ScreenStyle: Identifiable, Hashable, Sendable {
    /// The cable from console to TV.
    public enum Signal: Int, CaseIterable, Hashable, Sendable {
        /// Each colour on its own wire: the picture as drawn.
        case rgb
        /// Brightness and colour on separate wires: colour softens, no rainbows.
        case sVideo
        /// One wire for both: softer, rainbows on fine detail, dithering blends.
        case composite
    }

    public enum LCDGaps: Hashable, Sendable {
        /// The gaps between pixels darken (backlit or front-lit colour screens).
        case darken
        /// The gaps show the screen behind (the Game Boy's reflective layer).
        case background
    }

    public let id: String
    public var name: String
    public var source: String
    public var kind: ScreenShaders.Kind
    /// The test card's resolution for this screen.
    public var nativeSize: SIMD2<Int>
    /// The picture's shape on screen (CRTs stretch to 4:3; LCDs keep square pixels).
    public var displayAspect: Double?

    // CRT
    public var scanlines = 0.0
    public var thinnestBeam = 0.35
    public var widestBeam = 0.6
    public var blur = 0.6
    public var maskType = 0
    public var maskStrength = 0.0
    public var maskPitch = 1.0
    public var brightness = 1.0
    public var curvature = 0.0
    public var vignette = 0.0
    public var glow = 0.0
    public var chromaBleed = 0.0
    public var cornerRadius = 0.0
    /// How the console reached the TV.
    public var signal = Signal.rgb
    /// Degrees the colour carrier shifts each line: 0 (Genesis: rainbows stand still), 120
    /// (NES and SNES: they crawl), 180 (broadcast).
    public var linePhase = 120.0
    /// The colour filter's width in carrier cycles: wider is smoother colour with fewer rainbows.
    public var colorFilter = 2.0
    /// The luma filter's width in carrier cycles: narrower is sharper but lets the carrier
    /// through (dot crawl), wider blends more.
    public var lumaFilter = 0.45
    /// The glow's spread, in game pixels.
    public var glowSize = 2.0

    // LCD
    public var grid = 0.0
    public var gapWidth = 0.1
    public var subpixels = 0.0
    public var gaps = LCDGaps.darken
    public var shadow = 0.0
    public var shadowOffset = 0.35
    public var colorCorrection = 1.0
    public var darken = 0.0
    /// How much of the last frame each pixel keeps: an LCD's slow response (motion only).
    public var response = 0.0
    /// Each output channel's weights over (r, g, b), after the screen's gamma.
    public var colorMatrix: [SIMD3<Float>] = [SIMD3(1, 0, 0), SIMD3(0, 1, 0), SIMD3(0, 0, 1)]
    /// Mono screens: the darkest and lightest shade (nil: a colour screen).
    public var palette: (dark: SIMD3<Float>, light: SIMD3<Float>)?
    public var background = SIMD3<Float>(0, 0, 0)

    public init(id: String, name: String, source: String, kind: ScreenShaders.Kind, nativeSize: SIMD2<Int>, displayAspect: Double? = nil) {
        self.id = id
        self.name = name
        self.source = source
        self.kind = kind
        self.nativeSize = nativeSize
        self.displayAspect = displayAspect
    }

    public static func == (lhs: ScreenStyle, rhs: ScreenStyle) -> Bool {
        lhs.uniforms(sourceSize: .one, outputSize: .one).bytes == rhs.uniforms(sourceSize: .one, outputSize: .one).bytes
            && lhs.id == rhs.id && lhs.kind == rhs.kind
    }

    public func hash(into hasher: inout Hasher) {
        hasher.combine(id)
        hasher.combine(uniforms(sourceSize: .one, outputSize: .one).bytes)
    }

    /// The shader's inputs for a frame `sourceSize` drawn at `outputSize` drawable pixels.
    func uniforms(sourceSize: SIMD2<Float>, outputSize: SIMD2<Float>, crop: SIMD4<Float> = SIMD4(0, 0, 1, 1)) -> ScreenUniforms {
        var u = ScreenUniforms()
        u.sourceSize = sourceSize
        u.outputSize = outputSize
        u.crop = crop
        switch kind {
        case .crt:
            u.a = SIMD4(Float(scanlines), Float(thinnestBeam), Float(widestBeam), Float(blur))
            u.b = SIMD4(Float(maskType), Float(maskStrength), Float(maskPitch), Float(brightness))
            u.c = SIMD4(Float(curvature), Float(vignette), Float(glow), Float(chromaBleed))
            u.d = SIMD4(Float(cornerRadius), 0, 0, 0)
        case .lcd:
            u.a = SIMD4(Float(grid), Float(gapWidth), Float(subpixels), gaps == .background ? 1 : 0)
            u.b = SIMD4(Float(shadow), Float(shadowOffset), Float(brightness), Float(colorCorrection))
            u.c = SIMD4(palette == nil ? 0 : 1, Float(darken), 0, 0)
        case .sharp, .smooth:
            break
        }
        u.e = SIMD4(Float(signal.rawValue), Float(linePhase * .pi / 180), Float(colorFilter), Float(lumaFilter))
        u.f = SIMD4(Float(glowSize), Float(response), 0, 0)
        u.colorR = SIMD4(colorMatrix[0], 0)
        u.colorG = SIMD4(colorMatrix[1], 0)
        u.colorB = SIMD4(colorMatrix[2], 0)
        u.paletteDark = SIMD4(palette?.dark ?? .zero, 1)
        u.paletteLight = SIMD4(palette?.light ?? .one, 1)
        u.background = SIMD4(background, 1)
        return u
    }
}

/// The shader's uniforms, laid out as the Metal struct (float2, float2, then float4s).
struct ScreenUniforms {
    var sourceSize = SIMD2<Float>(1, 1)
    var outputSize = SIMD2<Float>(1, 1)
    var crop = SIMD4<Float>(0, 0, 1, 1)
    var a = SIMD4<Float>(repeating: 0)
    var b = SIMD4<Float>(repeating: 0)
    var c = SIMD4<Float>(repeating: 0)
    var d = SIMD4<Float>(repeating: 0)
    var e = SIMD4<Float>(repeating: 0)
    var f = SIMD4<Float>(repeating: 0)
    var colorR = SIMD4<Float>(1, 0, 0, 0)
    var colorG = SIMD4<Float>(0, 1, 0, 0)
    var colorB = SIMD4<Float>(0, 0, 1, 0)
    var paletteDark = SIMD4<Float>(0, 0, 0, 1)
    var paletteLight = SIMD4<Float>(1, 1, 1, 1)
    var background = SIMD4<Float>(0, 0, 0, 1)

    /// The raw bytes (for equality and hashing).
    var bytes: [UInt8] {
        withUnsafeBytes(of: self) { Array($0) }
    }
}

// MARK: The screens

extension ScreenStyle {
    /// Colour responses of handheld LCDs, after the community's colour-correction shaders
    /// (approximate: tune with the colour slider).
    static let gbcColor: [SIMD3<Float>] = [SIMD3(0.87, 0.18, -0.05), SIMD3(0.115, 0.66, 0.225), SIMD3(0.14, 0.07, 0.79)]
    static let gbaColor: [SIMD3<Float>] = [SIMD3(0.845, 0.17, -0.015), SIMD3(0.09, 0.68, 0.23), SIMD3(0.16, 0.085, 0.755)]
    static let ndsColor: [SIMD3<Float>] = [SIMD3(0.835, 0.205, -0.04), SIMD3(0.1, 0.645, 0.255), SIMD3(0.1325, 0.0575, 0.73)]
    static let pspColor: [SIMD3<Float>] = [SIMD3(0.98, 0.2, -0.18), SIMD3(0.04, 0.795, 0.165), SIMD3(0.01, 0.01, 0.98)]

    public static let sharp = ScreenStyle(id: "sharp", name: "Sharp", source: "Every game pixel a crisp square", kind: .sharp,
                                          nativeSize: SIMD2(256, 224), displayAspect: 4.0 / 3.0)
    public static let smooth = ScreenStyle(id: "smooth", name: "Today", source: "How the player draws now: smoothed", kind: .smooth,
                                           nativeSize: SIMD2(256, 224), displayAspect: 4.0 / 3.0)

    public static let consumerTV: ScreenStyle = {
        var s = ScreenStyle(id: "consumer-tv", name: "Living-room TV", source: "A composite CRT: soft, glowing, colour bleeding a little",
                            kind: .crt, nativeSize: SIMD2(256, 224), displayAspect: 4.0 / 3.0)
        s.scanlines = 0.6; s.thinnestBeam = 0.4; s.widestBeam = 0.68; s.blur = 0.5
        s.maskType = 2; s.maskStrength = 0.3; s.maskPitch = 2; s.brightness = 1.2
        s.curvature = 0.12; s.vignette = 0.3; s.glow = 0.2; s.glowSize = 3; s.chromaBleed = 0.6; s.cornerRadius = 0.06
        s.signal = .composite; s.linePhase = 120; s.colorFilter = 2; s.lumaFilter = 0.45
        return s
    }()
    public static let pvm: ScreenStyle = {
        var s = ScreenStyle(id: "pvm", name: "PVM", source: "A studio monitor over RGB: sharp lines, aperture grille",
                            kind: .crt, nativeSize: SIMD2(256, 224), displayAspect: 4.0 / 3.0)
        s.scanlines = 0.9; s.thinnestBeam = 0.28; s.widestBeam = 0.55; s.blur = 0.45
        s.maskType = 1; s.maskStrength = 0.28; s.maskPitch = 2; s.brightness = 1.3
        s.curvature = 0.03; s.vignette = 0.12; s.glow = 0.08; s.glowSize = 1.5; s.chromaBleed = 0; s.cornerRadius = 0.02
        return s
    }()
    public static let arcade: ScreenStyle = {
        var s = ScreenStyle(id: "arcade", name: "Arcade", source: "A low-res arcade monitor: bold lines, a dot mask, lots of glow",
                            kind: .crt, nativeSize: SIMD2(256, 224), displayAspect: 4.0 / 3.0)
        s.scanlines = 0.8; s.thinnestBeam = 0.32; s.widestBeam = 0.7; s.blur = 0.6
        s.maskType = 3; s.maskStrength = 0.35; s.maskPitch = 2; s.brightness = 1.35
        s.curvature = 0.08; s.vignette = 0.22; s.glow = 0.25; s.glowSize = 3; s.chromaBleed = 0.15; s.cornerRadius = 0.05
        return s
    }()

    public static let gameBoy: ScreenStyle = {
        var s = ScreenStyle(id: "game-boy", name: "Game Boy", source: "Pea-green segments over a reflective layer, each casting a shadow",
                            kind: .lcd, nativeSize: SIMD2(160, 144))
        s.palette = (SIMD3(0.06, 0.22, 0.06), SIMD3(0.61, 0.74, 0.06))
        s.background = SIMD3(0.64, 0.75, 0.1)
        s.grid = 0.55; s.gapWidth = 0.12; s.gaps = .background; s.shadow = 0.35; s.shadowOffset = 0.35; s.response = 0.6
        return s
    }()
    public static let gameBoyPocket: ScreenStyle = {
        var s = ScreenStyle(id: "game-boy-pocket", name: "Pocket", source: "Game Boy Pocket: grey on silver, sharper",
                            kind: .lcd, nativeSize: SIMD2(160, 144))
        s.palette = (SIMD3(0.12, 0.12, 0.1), SIMD3(0.77, 0.78, 0.72))
        s.background = SIMD3(0.8, 0.81, 0.76)
        s.grid = 0.45; s.gapWidth = 0.1; s.gaps = .background; s.shadow = 0.25; s.shadowOffset = 0.3; s.response = 0.45
        return s
    }()
    public static let gameBoyColor: ScreenStyle = {
        var s = ScreenStyle(id: "game-boy-color", name: "GBC", source: "Game Boy Color: an unlit colour LCD, gentle grid",
                            kind: .lcd, nativeSize: SIMD2(160, 144))
        s.colorMatrix = gbcColor; s.darken = 0.4; s.response = 0.3
        s.grid = 0.35; s.gapWidth = 0.1; s.subpixels = 0.12
        return s
    }()
    public static let gameBoyAdvance: ScreenStyle = {
        var s = ScreenStyle(id: "gba", name: "GBA", source: "Game Boy Advance: unlit, darker and washed out",
                            kind: .lcd, nativeSize: SIMD2(240, 160))
        s.colorMatrix = gbaColor; s.darken = 0.5; s.brightness = 0.97; s.response = 0.3
        s.grid = 0.3; s.gapWidth = 0.1; s.subpixels = 0.12
        return s
    }()
    public static let gameBoyAdvanceSP: ScreenStyle = {
        var s = ScreenStyle(id: "gba-sp", name: "GBA SP", source: "The backlit SP (AGS-101): brighter, finer grid",
                            kind: .lcd, nativeSize: SIMD2(240, 160))
        s.colorMatrix = gbaColor; s.darken = 0.15; s.brightness = 1.04; s.response = 0.15
        s.grid = 0.2; s.gapWidth = 0.08; s.subpixels = 0.2
        return s
    }()
    public static let nintendoDS: ScreenStyle = {
        var s = ScreenStyle(id: "ds", name: "DS", source: "A DS TFT: visible RGB stripes",
                            kind: .lcd, nativeSize: SIMD2(256, 192))
        s.colorMatrix = ndsColor; s.darken = 0.1; s.response = 0.15
        s.grid = 0.22; s.gapWidth = 0.08; s.subpixels = 0.35
        return s
    }()
    public static let nintendo3DS: ScreenStyle = {
        var s = ScreenStyle(id: "3ds", name: "3DS", source: "A 3DS: finer pixels, a faint grid",
                            kind: .lcd, nativeSize: SIMD2(400, 240))
        s.colorCorrection = 0; s.response = 0.05
        s.grid = 0.14; s.gapWidth = 0.08; s.subpixels = 0.2
        return s
    }()
    public static let psp: ScreenStyle = {
        var s = ScreenStyle(id: "psp", name: "PSP", source: "PSP: a wide, bright LCD with fine stripes",
                            kind: .lcd, nativeSize: SIMD2(480, 272))
        s.colorMatrix = pspColor; s.colorCorrection = 0.6; s.response = 0.25
        s.grid = 0.1; s.gapWidth = 0.08; s.subpixels = 0.25
        return s
    }()

    public static let all: [ScreenStyle] = [
        sharp, smooth, consumerTV, pvm, arcade, gameBoy, gameBoyPocket, gameBoyColor, gameBoyAdvance, gameBoyAdvanceSP,
        nintendoDS, nintendo3DS, psp,
    ]

    /// The screen a platform's games were made for: its handheld's LCD, else a living-room TV.
    public static func suggested(platformSlug: String) -> ScreenStyle {
        switch platformSlug {
        case "gb": gameBoy
        case "gbc": gameBoyColor
        case "gba": gameBoyAdvance
        case "nds", "dsi", "nintendo-dsi": nintendoDS
        case "3ds", "new-nintendo-3ds": nintendo3DS
        case "psp": psp
        default: consumerTV
        }
    }
}
