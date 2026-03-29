import CoreGraphics

public struct PlayerViewportLayout {
    public let frame: CGRect
    public let isPortrait: Bool

    public init(frame: CGRect, isPortrait: Bool) {
        self.frame = frame
        self.isPortrait = isPortrait
    }
}

public func resolvePlayerViewportLayout(
    containerSize: CGSize,
    safeAreaInsets: PlayerViewportSafeAreaInsets,
    aspectRatio: Double?,
    topAlignedInPortrait: Bool
) -> PlayerViewportLayout {
    let isPortrait = containerSize.height >= containerSize.width
    let resolvedAspect = CGFloat(max(aspectRatio ?? (containerSize.width / max(containerSize.height, 1)), 0.1))
    let horizontalInset: CGFloat
    if isPortrait {
        horizontalInset = 12
    } else {
        horizontalInset = max(safeAreaInsets.left, safeAreaInsets.right) + 14
    }
    let portraitTopInset: CGFloat
    if isPortrait && topAlignedInPortrait {
        portraitTopInset = safeAreaInsets.top + 18
    } else {
        portraitTopInset = 0
    }
    let effectiveBottomInset: CGFloat
    if isPortrait && topAlignedInPortrait {
        effectiveBottomInset = max(safeAreaInsets.bottom + 16, 24)
    } else {
        effectiveBottomInset = max(safeAreaInsets.bottom, 16)
    }
    let availableWidth = max(1, containerSize.width - (horizontalInset * 2))
    let availableHeight = max(1, containerSize.height - portraitTopInset - effectiveBottomInset)

    var width = availableWidth
    var height = width / resolvedAspect
    if height > availableHeight {
        height = availableHeight
        width = height * resolvedAspect
    }

    let originX = (containerSize.width - width) / 2
    let originY: CGFloat
    if isPortrait && topAlignedInPortrait {
        originY = portraitTopInset
    } else {
        originY = (containerSize.height - height) / 2
    }

    return PlayerViewportLayout(
        frame: CGRect(x: originX, y: originY, width: width, height: height),
        isPortrait: isPortrait
    )
}

public struct PlayerViewportSafeAreaInsets {
    public let top: CGFloat
    public let bottom: CGFloat
    public let left: CGFloat
    public let right: CGFloat

    public init(top: CGFloat, bottom: CGFloat, left: CGFloat, right: CGFloat) {
        self.top = top
        self.bottom = bottom
        self.left = left
        self.right = right
    }
}
