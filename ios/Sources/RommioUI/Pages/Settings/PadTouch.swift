import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// A touch on a playground pad, straight from UIKit (revised 2026-09-28): a SwiftUI gesture
/// reaches its handler up to a frame later than UIKit delivers the touch, and a pad's haptic
/// should start with the touch. A recognizer (not a view's own touch handling) also gets the
/// touch at once inside a scroll view, which holds touches back from its views.
struct PadTouchEvent: Sendable {
    enum Phase: Sendable {
        case began
        case moved
        case ended
        case cancelled
    }

    var phase: Phase
    /// Where the finger is, in the pad's coordinates.
    var location: CGPoint
    /// Points per second, smoothed.
    var velocity: CGSize
    /// Points moved on screen since touch-down (a scroll moves the view with the finger).
    var distance: Double
    /// When the screen registered the touch (system uptime, seconds).
    var timestamp: TimeInterval
}

/// How a pad shares a touch with an enclosing scroll view.
enum PadTouchMode: Sendable {
    /// The pad keeps the touch: the page doesn't scroll from it (buttons, d-pads, sticks).
    case capture
    /// The scroll view scrolls from it too (the material cards, which fill the page's width).
    case share
}

extension View {
    /// Reports touches on this view as UIKit delivers them.
    func padTouch(_ mode: PadTouchMode = .capture, _ handler: @escaping (PadTouchEvent) -> Void) -> some View {
        #if canImport(UIKit)
        gesture(PadTouchGesture(mode: mode, handler: handler))
        #else
        gesture(DragGesture(minimumDistance: 0)
            .onChanged { value in
                let distance = hypot(value.translation.width, value.translation.height)
                handler(PadTouchEvent(phase: distance == 0 ? .began : .moved, location: value.location, velocity: value.velocity,
                                      distance: distance, timestamp: ProcessInfo.processInfo.systemUptime))
            }
            .onEnded { value in
                handler(PadTouchEvent(phase: .ended, location: value.location, velocity: value.velocity,
                                      distance: hypot(value.translation.width, value.translation.height),
                                      timestamp: ProcessInfo.processInfo.systemUptime))
            })
        #endif
    }
}

#if canImport(UIKit)
private struct PadTouchGesture: UIGestureRecognizerRepresentable {
    let mode: PadTouchMode
    let handler: (PadTouchEvent) -> Void

    func makeUIGestureRecognizer(context: Context) -> PadRecognizer {
        let recognizer = PadRecognizer()
        recognizer.sharesTouch = mode == .share
        recognizer.cancelsTouchesInView = false
        recognizer.delaysTouchesBegan = false
        recognizer.delegate = context.coordinator
        return recognizer
    }

    func updateUIGestureRecognizer(_ recognizer: PadRecognizer, context: Context) {
        recognizer.sharesTouch = mode == .share
    }

    func handleUIGestureRecognizerAction(_ recognizer: PadRecognizer, context: Context) {
        guard var event = recognizer.event else { return }
        // The recognizer measures in the window; the pad wants its own coordinates. The
        // recognizer's own view is SwiftUI's host, not the pad (revised 2026-09-28: reading
        // positions in that view put every touch far below the pad's centre, so sticks and
        // d-pads always pointed down).
        event.location = context.converter.convert(globalPoint: event.location, to: .local)
        handler(event)
    }

    func makeCoordinator(converter: CoordinateSpaceConverter) -> Coordinator {
        Coordinator()
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        /// Pads work together (two thumbs on two pads); a sharing pad works with the scroll
        /// view too; a capturing pad keeps the touch from it.
        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                               shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
            if otherGestureRecognizer is PadRecognizer { return true }
            return (gestureRecognizer as? PadRecognizer)?.sharesTouch ?? false
        }
    }
}

/// Begins on touch-down (no delay) and follows one finger, in window coordinates (the pad's
/// converter maps them into the pad).
private final class PadRecognizer: UIGestureRecognizer {
    var sharesTouch = false
    private(set) var event: PadTouchEvent?
    private var tracked: UITouch?
    private var start = CGPoint.zero
    private var last: (point: CGPoint, time: TimeInterval)?
    private var velocity = CGSize.zero

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        guard tracked == nil, let touch = touches.first else { return }
        tracked = touch
        start = touch.location(in: nil)
        let point = touch.location(in: nil)
        last = (point, touch.timestamp)
        velocity = .zero
        self.event = PadTouchEvent(phase: .began, location: point, velocity: .zero, distance: 0, timestamp: touch.timestamp)
        state = .began
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent) {
        guard let tracked, touches.contains(tracked) else { return }
        // Every sample since the last delivery (the screen samples touches faster than it
        // draws), for a steadier speed.
        for sample in event.coalescedTouches(for: tracked) ?? [tracked] {
            let point = sample.location(in: nil)
            if let last, sample.timestamp > last.time {
                let dt = sample.timestamp - last.time
                let instant = CGSize(width: (point.x - last.point.x) / dt, height: (point.y - last.point.y) / dt)
                velocity = CGSize(width: velocity.width * 0.5 + instant.width * 0.5, height: velocity.height * 0.5 + instant.height * 0.5)
            }
            last = (point, sample.timestamp)
        }
        self.event = current(.moved, tracked)
        state = .changed
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent) {
        guard let tracked, touches.contains(tracked) else { return }
        self.event = current(.ended, tracked)
        state = .ended
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent) {
        guard let tracked, touches.contains(tracked) else { return }
        self.event = current(.cancelled, tracked)
        state = .cancelled
    }

    override func reset() {
        tracked = nil
        last = nil
        velocity = .zero
    }

    private func current(_ phase: PadTouchEvent.Phase, _ touch: UITouch) -> PadTouchEvent {
        let window = touch.location(in: nil)
        return PadTouchEvent(phase: phase, location: window, velocity: velocity,
                             distance: Double(hypot(window.x - start.x, window.y - start.y)), timestamp: touch.timestamp)
    }
}
#endif
