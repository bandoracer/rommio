import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

extension View {
    /// The status bar's text over art this page draws under it: `.dark` gives light text
    /// (a dark picture), `.light` dark text; nil leaves it to the app. Only while this view
    /// is on screen (Play's hero, docs/theme-layouts.md).
    func statusBarText(over scheme: ColorScheme?) -> some View {
        #if os(iOS)
        modifier(StatusBarTextModifier(scheme: scheme))
        #else
        self
        #endif
    }
}

#if os(iOS)
/// Asks for the style only while the page is on screen (its tab selected, nothing pushed over it).
private struct StatusBarTextModifier: ViewModifier {
    @Environment(\.playerCoversApp) private var playerCoversApp
    let scheme: ColorScheme?
    @State private var onScreen = false

    func body(content: Content) -> some View {
        let asked = onScreen && !playerCoversApp ? scheme : nil
        content
            .background {
                StatusBarProbe(style: asked.map { $0 == .dark ? .lightContent : .darkContent })
                    .allowsHitTesting(false)
            }
            .onAppear { onScreen = true }
            .onDisappear { onScreen = false }
    }
}
#endif

/// A game is playing over the app (the player covers every page).
private struct PlayerCoversAppKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    var playerCoversApp: Bool {
        get { self[PlayerCoversAppKey.self] }
        set { self[PlayerCoversAppKey.self] = newValue }
    }
}

/// Hides the system's scroll-edge fade at the top (iOS 26), which washes art drawn under
/// the bars with the page's ground.
struct TopEdgeEffectHidden: ViewModifier {
    let hidden: Bool

    func body(content: Content) -> some View {
        if #available(iOS 26, macOS 26, *) {
            content.scrollEdgeEffectHidden(hidden, for: .top)
        } else {
            content
        }
    }
}

#if os(iOS)
/// The app sets one colour scheme for its window (`ThemeHost`), and the status bar follows
/// it; a page can't choose its own. This window, above the app's and taking no touches or
/// focus, decides only the status bar's style while a page asks for one. Everything else it
/// could affect (rotation, the home indicator, a hidden status bar) and anything presented
/// full screen over the app (the player) still follow the app's window.
@MainActor
final class StatusBarOverride {
    static let shared = StatusBarOverride()
    private var window: UIWindow?
    private let controller = StatusBarStyleController()
    /// Pages asking for a style, oldest first. The newest decides: when one page replaces
    /// another (a push, a tab switch), the new page asks before the old one stands down.
    private var requests: [(owner: ObjectIdentifier, style: UIStatusBarStyle, scene: UIWindowScene)] = []

    /// `owner` asks for `style` (nil: stands down).
    func set(_ style: UIStatusBarStyle?, scene: UIWindowScene?, owner: AnyObject) {
        let id = ObjectIdentifier(owner)
        let existing = requests.firstIndex { $0.owner == id }
        if let style, let scene {
            if let existing, requests[existing].style == style, requests[existing].scene === scene { return }
            if let existing { requests.remove(at: existing) }
            requests.append((id, style, scene))
        } else if let existing {
            requests.remove(at: existing)
        }
        show(requests.last)
    }

    private func show(_ request: (owner: ObjectIdentifier, style: UIStatusBarStyle, scene: UIWindowScene)?) {
        guard let style = request?.style, let scene = request?.scene else {
            window?.isHidden = true
            return
        }
        if window?.windowScene !== scene {
            window?.isHidden = true
            let overlay = PassthroughWindow(windowScene: scene)
            overlay.windowLevel = .normal + 1
            overlay.backgroundColor = .clear
            overlay.accessibilityElementsHidden = true
            overlay.rootViewController = controller
            window = overlay
        }
        controller.app = scene.windows.first { $0 !== window && $0.windowLevel == .normal }?.rootViewController
        controller.style = style
        window?.isHidden = false
    }
}

/// A window that never takes a touch: every hit falls through to the app below.
private final class PassthroughWindow: UIWindow {
    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? { nil }
}

private final class StatusBarStyleController: UIViewController {
    weak var app: UIViewController?
    var style: UIStatusBarStyle = .default {
        didSet { if style != oldValue { setNeedsStatusBarAppearanceUpdate() } }
    }

    override func loadView() {
        let view = UIView()
        view.backgroundColor = .clear
        view.isUserInteractionEnabled = false
        view.accessibilityElementsHidden = true
        self.view = view
    }

    /// The app's controller that would decide the status bar: through anything presented
    /// full screen (the player) and the containers' chosen children.
    private func deciding(_ child: (UIViewController) -> UIViewController?) -> UIViewController? {
        guard var controller = app else { return nil }
        while true {
            if let presented = controller.presentedViewController, !presented.isBeingDismissed,
               presented.modalPresentationCapturesStatusBarAppearance
                || [.fullScreen, .overFullScreen].contains(presented.modalPresentationStyle) {
                controller = presented
            } else if let next = child(controller) {
                controller = next
            } else {
                return controller
            }
        }
    }

    /// Something (the player) is presented full screen over the app.
    private var appCovered: Bool {
        guard let presented = app?.presentedViewController, !presented.isBeingDismissed else { return false }
        return [.fullScreen, .overFullScreen].contains(presented.modalPresentationStyle)
    }

    override var preferredStatusBarStyle: UIStatusBarStyle {
        appCovered ? (deciding { $0.childForStatusBarStyle }?.preferredStatusBarStyle ?? .default) : style
    }
    override var prefersStatusBarHidden: Bool { deciding { $0.childForStatusBarHidden }?.prefersStatusBarHidden ?? false }
    override var prefersHomeIndicatorAutoHidden: Bool { deciding { $0.childForHomeIndicatorAutoHidden }?.prefersHomeIndicatorAutoHidden ?? false }
    override var preferredScreenEdgesDeferringSystemGestures: UIRectEdge {
        deciding { $0.childForScreenEdgesDeferringSystemGestures }?.preferredScreenEdgesDeferringSystemGestures ?? []
    }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask {
        deciding { _ in nil }?.supportedInterfaceOrientations ?? .all
    }
}

/// Finds the page's window scene and asks for (or releases) the style.
private struct StatusBarProbe: UIViewRepresentable {
    let style: UIStatusBarStyle?

    func makeUIView(context: Context) -> ProbeView {
        let view = ProbeView()
        view.isUserInteractionEnabled = false
        view.isAccessibilityElement = false
        return view
    }

    func updateUIView(_ view: ProbeView, context: Context) {
        view.style = style
    }

    final class ProbeView: UIView {
        var style: UIStatusBarStyle? {
            didSet { if style != oldValue { apply() } }
        }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            apply()
        }

        private func apply() {
            StatusBarOverride.shared.set(window == nil ? nil : style, scene: window?.windowScene, owner: self)
        }
    }
}
#endif
