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
    private let topAlignedInPortraitProvider: @Sendable () -> Bool

    private var metalView: MTKView?
    private var ciContext: CIContext?
    private let statusLabel = UILabel()

    init(
        frameAdvanceProvider: @escaping @Sendable () -> Void,
        frameProvider: @escaping @Sendable () -> PlayerVideoFrameSnapshot?,
        statusProvider: @escaping @Sendable () -> String,
        preferredFPSProvider: @escaping @Sendable () -> Int,
        preferredAspectRatioProvider: @escaping @Sendable () -> Double?,
        topAlignedInPortraitProvider: @escaping @Sendable () -> Bool
    ) {
        self.frameAdvanceProvider = frameAdvanceProvider
        self.frameProvider = frameProvider
        self.statusProvider = statusProvider
        self.preferredFPSProvider = preferredFPSProvider
        self.preferredAspectRatioProvider = preferredAspectRatioProvider
        self.topAlignedInPortraitProvider = topAlignedInPortraitProvider
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        view.accessibilityIdentifier = "player.surface"

        if let device = MTLCreateSystemDefaultDevice() {
            let metalView = MTKView(frame: .zero, device: device)
            metalView.translatesAutoresizingMaskIntoConstraints = false
            metalView.framebufferOnly = false
            metalView.enableSetNeedsDisplay = false
            metalView.isPaused = false
            metalView.preferredFramesPerSecond = preferredFPSProvider()
            metalView.delegate = self
            metalView.clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 1)
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

        guard let currentDrawable = view.currentDrawable,
              let commandQueue = view.device?.makeCommandQueue(),
              let commandBuffer = commandQueue.makeCommandBuffer() else {
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

        let targetRect = resolvePlayerViewportLayout(
            containerSize: view.drawableSize,
            safeAreaInsets: PlayerViewportSafeAreaInsets(
                top: view.safeAreaInsets.top,
                bottom: view.safeAreaInsets.bottom,
                left: view.safeAreaInsets.left,
                right: view.safeAreaInsets.right
            ),
            aspectRatio: preferredAspectRatioProvider() ?? (Double(frame.width) / Double(max(frame.height, 1))),
            topAlignedInPortrait: topAlignedInPortraitProvider()
        ).frame
        let ciTargetRect = CGRect(
            x: targetRect.minX,
            y: view.drawableSize.height - targetRect.maxY,
            width: targetRect.width,
            height: targetRect.height
        )
        let scaleX = targetRect.width / CGFloat(frame.width)
        let scaleY = targetRect.height / CGFloat(frame.height)
        let scaled = ciImage.transformed(by: .init(scaleX: scaleX, y: scaleY))
        let translated = scaled.transformed(by: .init(translationX: ciTargetRect.minX, y: ciTargetRect.minY))
        ciContext.render(translated, to: currentDrawable.texture, commandBuffer: commandBuffer, bounds: CGRect(origin: .zero, size: view.drawableSize), colorSpace: CGColorSpaceCreateDeviceRGB())
    }

    func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}

    private func makeImage(from frame: PlayerVideoFrameSnapshot) -> CIImage? {
        switch frame.pixelFormat.rawValue {
        case 0, 1:
            let cfData = frame.pixelData as CFData
            guard let provider = CGDataProvider(data: cfData) else {
                return nil
            }
            let bitmapInfo = CGBitmapInfo.byteOrder32Little.union(.init(rawValue: CGImageAlphaInfo.noneSkipFirst.rawValue))
            guard let image = CGImage(
                width: frame.width,
                height: frame.height,
                bitsPerComponent: 8,
                bitsPerPixel: 32,
                bytesPerRow: frame.pitch,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: bitmapInfo,
                provider: provider,
                decode: nil,
                shouldInterpolate: false,
                intent: .defaultIntent
            ) else {
                return nil
            }
            return CIImage(cgImage: image)
        case 2:
            return nil
        default:
            return nil
        }
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
