import Foundation
import RommioContract

#if canImport(AuthenticationServices)
import AuthenticationServices

public enum InteractiveSessionAuthenticationError: Error, LocalizedError, Sendable {
    case missingPresentationAnchor
    case cancelled
    case callbackURLMismatch(URL)
    case failedToStart

    public var errorDescription: String? {
        switch self {
        case .missingPresentationAnchor: "A presentation anchor is required to launch browser sign-in."
        case .cancelled: "The interactive sign-in session was canceled."
        case .callbackURLMismatch: "Finish sign-in on the configured RomM server before returning to the app."
        case .failedToStart: "Browser sign-in could not be started."
        }
    }
}
public protocol PresentationAnchorProviding: AnyObject {
    @MainActor func presentationAnchor() -> ASPresentationAnchor
}
public struct InteractiveSessionResult: Sendable {
    public let callbackURL: URL
    public let cookies: [BrowserCookie]
    public init(callbackURL: URL, cookies: [BrowserCookie]) { self.callbackURL = callbackURL; self.cookies = cookies }
}
public protocol InteractiveSessionAuthenticating: Sendable {
    @MainActor func authenticate(using config: InteractiveSessionConfig) async throws -> InteractiveSessionResult
}

#if canImport(UIKit) && canImport(WebKit)
import UIKit
import WebKit

/// A private WebKit session exposes cookies explicitly; system browser sessions do not
/// provide a transferable cookie jar for RomM's existing cookie-based API authentication.
@MainActor
public final class WebAuthenticationSessionAuthenticator: NSObject, InteractiveSessionAuthenticating, @unchecked Sendable {
    private let anchorProvider: any PresentationAnchorProviding
    private var controller: CookieSignInController?
    public init(anchorProvider: any PresentationAnchorProviding) { self.anchorProvider = anchorProvider }

    public func authenticate(using config: InteractiveSessionConfig) async throws -> InteractiveSessionResult {
        guard controller == nil else { throw InteractiveSessionAuthenticationError.failedToStart }
        guard var presenter = anchorProvider.presentationAnchor().rootViewController else { throw InteractiveSessionAuthenticationError.missingPresentationAnchor }
        while let next = presenter.presentedViewController { presenter = next }
        try Task.checkCancellation()
        let browser = CookieSignInController(config: config)
        self.controller = browser
        defer { self.controller = nil }
        return try await withTaskCancellationHandler {
            for cookie in config.cookies ?? [] {
                if let native = cookie.foundationCookie { await browser.webView.configuration.websiteDataStore.httpCookieStore.setCookie(native) }
                try Task.checkCancellation()
            }
            return try await withCheckedThrowingContinuation { continuation in
                browser.completion = { continuation.resume(with: $0) }
                let navigation = UINavigationController(rootViewController: browser)
                navigation.modalPresentationStyle = .pageSheet
                navigation.presentationController?.delegate = browser
                presenter.present(navigation, animated: true)
            }
        } onCancel: { Task { @MainActor [weak self] in self?.controller?.cancel() } }
    }
    public func cancel() { controller?.cancel() }
}

@MainActor
private final class CookieSignInController: UIViewController, WKNavigationDelegate, WKUIDelegate, UIAdaptivePresentationControllerDelegate {
    let config: InteractiveSessionConfig
    let webView: WKWebView
    var completion: ((Result<InteractiveSessionResult, Error>) -> Void)?
    private var completing = false
    init(config: InteractiveSessionConfig) {
        self.config = config
        let settings = WKWebViewConfiguration()
        settings.websiteDataStore = .nonPersistent()
        webView = WKWebView(frame: .zero, configuration: settings)
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }
    override func loadView() { view = webView }
    override func viewDidLoad() {
        super.viewDidLoad()
        title = config.title
        navigationItem.leftBarButtonItem = UIBarButtonItem(barButtonSystemItem: .cancel, target: self, action: #selector(cancel))
        navigationItem.rightBarButtonItem = UIBarButtonItem(barButtonSystemItem: .done, target: self, action: #selector(done))
        navigationItem.rightBarButtonItem?.isEnabled = false
        webView.navigationDelegate = self; webView.uiDelegate = self
        webView.load(URLRequest(url: config.startURL))
    }
    @objc func cancel() { finish(.failure(InteractiveSessionAuthenticationError.cancelled)) }
    @objc private func done() {
        guard !completing, let url = webView.url, CredentialScope(baseURL: config.expectedBaseURL).contains(url) else { return }
        completing = true
        Task { @MainActor in
            let cookies = await webView.configuration.websiteDataStore.httpCookieStore.allCookies().map(BrowserCookie.init)
            finish(.success(InteractiveSessionResult(callbackURL: url, cookies: cookies)))
        }
    }
    private func finish(_ result: Result<InteractiveSessionResult, Error>) {
        guard let completion else { return }
        self.completion = nil
        webView.stopLoading()
        dismiss(animated: true)
        completion(result)
    }
    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) { cancel() }
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        navigationItem.rightBarButtonItem?.isEnabled = webView.url.map { CredentialScope(baseURL: config.expectedBaseURL).contains($0) } ?? false
        navigationItem.prompt = nil
    }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        if (error as NSError).code != NSURLErrorCancelled { navigationItem.prompt = error.localizedDescription }
    }
    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        decisionHandler(["https", "http", "about"].contains(navigationAction.request.url?.scheme ?? "") ? .allow : .cancel)
    }
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if navigationAction.targetFrame == nil, ["https", "http"].contains(navigationAction.request.url?.scheme ?? "") { webView.load(navigationAction.request) }
        return nil
    }
}
#endif
#endif
