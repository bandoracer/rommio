import Foundation

#if canImport(UIKit) && canImport(MetalKit) && canImport(CoreImage)
import AVFoundation
import CoreImage
import MetalKit
import RommioPlayerBridge
import UIKit

struct PlayerVideoFrameSnapshot: Sendable {
    var pixelData: Data
    var width: Int
    var height: Int
    var pitch: Int
    var pixelFormat: RMLLibretroPixelFormat
}

final class PlayerSurfaceController: UIViewController, MTKViewDelegate {
    private let frameAdvanceProvider: @Sendable () -> Void
    private let frameProvider: @Sendable () -> PlayerVideoFrameSnapshot?
    private let statusProvider: @Sendable () -> String
    private let preferredFPSProvider: @Sendable () -> Int
    private let preferredAspectRatioProvider: @Sendable () -> Double?
    private let inputConfigurationProvider: @Sendable () -> PlayerInputConfiguration
    private let topAlignedInPortraitProvider: @Sendable () -> Bool

    private var metalView: MTKView?
    private var ciContext: CIContext?
    private var commandQueue: MTLCommandQueue?
    private let statusLabel = UILabel()

    init(
        frameAdvanceProvider: @escaping @Sendable () -> Void,
        frameProvider: @escaping @Sendable () -> PlayerVideoFrameSnapshot?,
        statusProvider: @escaping @Sendable () -> String,
        preferredFPSProvider: @escaping @Sendable () -> Int,
        preferredAspectRatioProvider: @escaping @Sendable () -> Double?,
        topAlignedInPortraitProvider: @escaping @Sendable () -> Bool,
        inputConfigurationProvider: @escaping @Sendable () -> PlayerInputConfiguration
    ) {
        self.frameAdvanceProvider = frameAdvanceProvider
        self.frameProvider = frameProvider
        self.statusProvider = statusProvider
        self.preferredFPSProvider = preferredFPSProvider
        self.preferredAspectRatioProvider = preferredAspectRatioProvider
        self.topAlignedInPortraitProvider = topAlignedInPortraitProvider
        self.inputConfigurationProvider = inputConfigurationProvider
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        view.accessibilityIdentifier = "player.surface"

        if let device = MTLCreateSystemDefaultDevice() {
            let metalView = MTKView(frame: .zero, device: device)
            metalView.translatesAutoresizingMaskIntoConstraints = false
            metalView.framebufferOnly = false
            metalView.isOpaque = false
            metalView.backgroundColor = .clear
            metalView.enableSetNeedsDisplay = false
            metalView.isPaused = false
            metalView.preferredFramesPerSecond = preferredFPSProvider()
            metalView.delegate = self
            metalView.clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 0)
            metalView.accessibilityIdentifier = "player.surface.metal"
            view.addSubview(metalView)
            NSLayoutConstraint.activate([
                metalView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
                metalView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
                metalView.topAnchor.constraint(equalTo: view.topAnchor),
                metalView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            ])
            self.metalView = metalView
            self.ciContext = CIContext(mtlDevice: device)
            self.commandQueue = device.makeCommandQueue()
        }

        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        statusLabel.textColor = .white
        statusLabel.font = .preferredFont(forTextStyle: .headline)
        statusLabel.numberOfLines = 0
        statusLabel.textAlignment = .center
        statusLabel.backgroundColor = UIColor.black.withAlphaComponent(0.45)
        statusLabel.layer.cornerRadius = 14
        statusLabel.layer.masksToBounds = true
        statusLabel.text = statusProvider()
        statusLabel.accessibilityIdentifier = "player.status"
        view.addSubview(statusLabel)
        NSLayoutConstraint.activate([
            statusLabel.leadingAnchor.constraint(greaterThanOrEqualTo: view.leadingAnchor, constant: 24),
            statusLabel.trailingAnchor.constraint(lessThanOrEqualTo: view.trailingAnchor, constant: -24),
            statusLabel.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            statusLabel.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -28),
        ])
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        metalView?.preferredFramesPerSecond = preferredFPSProvider()
    }

    func draw(in view: MTKView) {
        frameAdvanceProvider()
        let status = statusProvider()
        statusLabel.text = status
        statusLabel.isHidden = status == "Running" || status == "Ready"
            || (inputConfigurationProvider().stage?.trailingPaneWidth ?? 0) > 0

        guard let currentDrawable = view.currentDrawable,
              let commandBuffer = commandQueue?.makeCommandBuffer() else {
            return
        }

        defer {
            commandBuffer.present(currentDrawable)
            commandBuffer.commit()
        }

        guard let frame = frameProvider(),
              let ciContext else {
            return
        }

        guard let ciImage = makeImage(from: frame) else {
            return
        }

        let configuration = inputConfigurationProvider()
        let layout = resolvePlayerStageLayout(
            containerSize: view.bounds.size,
            safeAreaInsets: PlayerViewportSafeAreaInsets(
                top: view.safeAreaInsets.top,
                bottom: view.safeAreaInsets.bottom,
                left: view.safeAreaInsets.left,
                right: view.safeAreaInsets.right
            ),
            aspectRatio: preferredAspectRatioProvider() ?? (Double(frame.width) / Double(max(frame.height, 1))),
            topAlignedInPortrait: topAlignedInPortraitProvider(),
            controlDeckHeight: configuration.controlDeckHeight ?? 200,
            controlScale: configuration.controlScale ?? 1,
            reserveTouchSpace: configuration.reserveTouchSpace,
            utilityCount: configuration.controlUtilityCount ?? 3,
            stage: configuration.stage ?? PlayerStageContext()
        )
        // Layout and safe-area insets are UIKit points; Metal draws physical pixels.
        let drawableScaleX = view.drawableSize.width / max(view.bounds.width, 1)
        let drawableScaleY = view.drawableSize.height / max(view.bounds.height, 1)
        let frameWidth = CGFloat(frame.width)
        let frameHeight = CGFloat(frame.height)
        var composite: CIImage?
        // Draw each emulated screen (one, or a DS pair) into its own rect.
        for screen in layout.screens {
            // Core Image is bottom-left based; the source rect is top-left based.
            let sourcePixels = CGRect(
                x: screen.source.minX * frameWidth,
                y: (1 - screen.source.maxY) * frameHeight,
                width: screen.source.width * frameWidth,
                height: screen.source.height * frameHeight
            )
            let targetRect = screen.frame.applying(CGAffineTransform(scaleX: drawableScaleX, y: drawableScaleY))
            let ciTargetRect = CGRect(
                x: targetRect.minX,
                y: view.drawableSize.height - targetRect.maxY,
                width: targetRect.width,
                height: targetRect.height
            )
            let placed = ciImage.cropped(to: sourcePixels)
                .transformed(by: .init(translationX: -sourcePixels.minX, y: -sourcePixels.minY))
                .transformed(by: .init(scaleX: targetRect.width / max(sourcePixels.width, 1), y: targetRect.height / max(sourcePixels.height, 1)))
                .transformed(by: .init(translationX: ciTargetRect.minX, y: ciTargetRect.minY))
            composite = composite.map { placed.composited(over: $0) } ?? placed
        }
        guard let composite else { return }
        // Drawables are recycled: clear everything outside the screens so a
        // previous arrangement (for example before a fold) never lingers.
        let fullDrawable = CGRect(origin: .zero, size: view.drawableSize)
        let cleared = composite.composited(over: CIImage(color: .clear).cropped(to: fullDrawable))
        ciContext.render(cleared, to: currentDrawable.texture, commandBuffer: commandBuffer, bounds: CGRect(origin: .zero, size: view.drawableSize), colorSpace: CGColorSpaceCreateDeviceRGB())
    }

    func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}

    private func makeImage(from frame: PlayerVideoFrameSnapshot) -> CIImage? {
        guard let image = PlayerFrameImage.make(
            data: frame.pixelData, width: frame.width, height: frame.height,
            pitch: frame.pitch, format: frame.pixelFormat
        ) else { return nil }
        return CIImage(cgImage: image)
    }

}
#else
struct PlayerVideoFrameSnapshot: Sendable {
    var pixelData: Data
    var width: Int
    var height: Int
    var pitch: Int
    var pixelFormat: Int
}

final class PlayerSurfaceController: PlayerHostController {}
#endif
