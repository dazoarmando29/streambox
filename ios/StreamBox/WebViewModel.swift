import WebKit
import SwiftUI

/// Owns the WKWebView and translates the shared shell contract (markers,
/// back cascade, allowlist, bridge messages) between web and native.
final class WebViewModel: NSObject, ObservableObject, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {

    let webView: WKWebView
    weak var model: AppModel?

    private var titleObservation: NSKeyValueObservation?
    private var progressObservation: NSKeyValueObservation?
    private var startedFirstLoad = false
    private var rulesReady = false
    private var pendingPartyAfterLoad: String?

    /// Keep a strong ref to the message handler proxy (WKUserContentController
    /// retains handlers; the proxy breaks the retain cycle back to us).
    private var handlerProxy: ScriptHandlerProxy?

    override init() {
        let config = WKWebViewConfiguration()
        // The web app auto-plays its media and controls playback through the
        // provider frames - mirror Android setMediaPlaybackRequiresUserGesture(false).
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        config.allowsPictureInPictureMediaPlayback = true
        config.allowsAirPlayForMediaPlayback = true
        // Persistent store: localStorage (Continue Watching / My List / PIN /
        // settings) must survive app updates - never wipe user data.
        config.websiteDataStore = .default()

        let proxy = ScriptHandlerProxy()
        config.userContentController.add(proxy, name: "native")

        let bundle = Bundle.main
        if let url = bundle.url(forResource: "NativeBridge", withExtension: "js"),
           let src = try? String(contentsOf: url, encoding: .utf8) {
            config.userContentController.addUserScript(
                WKUserScript(source: src, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        }
        if let url = bundle.url(forResource: "BackCascade", withExtension: "js"),
           let src = try? String(contentsOf: url, encoding: .utf8) {
            config.userContentController.addUserScript(
                WKUserScript(source: src, injectionTime: .atDocumentEnd, forMainFrameOnly: true))
        }

        webView = WKWebView(frame: .zero, configuration: config)
        super.init()
        handlerProxy = proxy
        proxy.owner = self
        webView.navigationDelegate = self
        webView.uiDelegate = self
        // The SPA owns Back via the cascade script; iOS edge-swipe runs the
        // same cascade (web history is useless for a single-page app).
        webView.allowsBackForwardNavigationGestures = false
        // The web app pads for safe areas itself (viewport-fit=cover +
        // env(safe-area-inset-*)); do not double-inset.
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.isOpaque = false
        webView.backgroundColor = UIColor(red: 0.043, green: 0.067, blue: 0.118, alpha: 1)

        titleObservation = webView.observe(\.title, options: [.new]) { [weak self] wv, _ in
            guard let self = self, let title = wv.title,
                  let marker = AppConfig.titleMarker(title) else { return }
            DispatchQueue.main.async { self.model?.titleMarkerChanged(marker) }
        }
        progressObservation = webView.observe(\.estimatedProgress, options: [.new]) { [weak self] wv, _ in
            DispatchQueue.main.async { self?.model?.pageLoadProgress = wv.estimatedProgress }
        }

        // Ad Shield compiles before the first load so ads die from frame one.
        AdShield.prepare { [weak self] rules in
            guard let self = self else { return }
            if let rules = rules {
                self.webView.configuration.userContentController.add(rules)
            }
            self.rulesReady = true
            self.startLoadingIfNeeded()
        }
    }

    // MARK: - Loading

    var homeURL: URL {
        AppConfig.homeURL(lite: model?.settings.liteMode ?? false)
    }

    func startLoadingIfNeeded() {
        guard rulesReady, !startedFirstLoad else { return }
        startedFirstLoad = true
        webView.load(URLRequest(url: homeURL))
    }

    func reload() {
        webView.reload()
    }

    func evaluate(_ js: String) {
        webView.evaluateJavaScript(js, completionHandler: nil)
    }

    func openParty(_ code: String) {
        pendingPartyAfterLoad = code
        let safe = AppConfig.sanitizedPartyCode(code) ?? ""
        guard !safe.isEmpty else { return }
        evaluate("try{PARTY.open('" + safe + "');}catch(e){}")
        // page may still be booting: retry once after a beat
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) { [weak self] in
            if let code = self?.pendingPartyAfterLoad {
                self?.evaluate("try{PARTY.open('" + code + "');}catch(e){}")
                self?.pendingPartyAfterLoad = nil
            }
        }
    }

    // MARK: - Back cascade

    func runBackCascade() {
        webView.evaluateJavaScript("window.__sbBackCascade ? window.__sbBackCascade() : 'exit'") { [weak self] value, _ in
            guard let self = self else { return }
            let result = (value as? String) ?? "exit"
            if result == "exit" {
                // Fail-safe (same as Android): on an allowlisted non-SPA page
                // the cascade knows nothing - walk WebView history instead.
                let onAppSite = self.webView.url?.host?.lowercased() == AppConfig.siteHost
                if self.webView.canGoBack && !onAppSite {
                    self.webView.goBack()
                }
                // iOS: nothing above 'exit' - the app stays open.
            }
        }
    }

    // MARK: - WKNavigationDelegate (allowlist gate)

    func webView(_ webView: WKWebView,
                 decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = navigationAction.request.url else {
            decisionHandler(.cancel)
            return
        }
        let scheme = (url.scheme ?? "").lowercased()
        let isHTTP = scheme == "http" || scheme == "https"

        // Subframes (provider players) navigate their own inner frames to
        // video hosts - only exotic schemes die here (same rule as Android).
        if navigationAction.targetFrame?.isMainFrame == false {
            decisionHandler(isHTTP ? .allow : .cancel)
            return
        }

        // Main frame (and target=_blank, whose targetFrame is nil):
        // scheme gate FIRST, then the host allowlist.
        if !isHTTP {
            if scheme == AppConfig.urlScheme {
                decisionHandler(.cancel)
                model?.handleDeepLink(url)
            } else {
                decisionHandler(.cancel)
            }
            return
        }
        if AppConfig.isHostAllowed(url.host ?? "") {
            decisionHandler(.allow)
        } else {
            decisionHandler(.cancel)
        }
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        DispatchQueue.main.async { [weak self] in
            self?.model?.webError = nil
            self?.model?.dismissSplashIfNeeded()
        }
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        handleFailure(error)
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        handleFailure(error)
    }

    private func handleFailure(_ error: Error) {
        let ns = error as NSError
        if ns.domain == NSURLErrorDomain && ns.code == NSURLErrorCancelled { return }
        DispatchQueue.main.async { [weak self] in
            self?.model?.dismissSplashIfNeeded()
            self?.model?.webError = ns.localizedDescription
        }
    }

    // MARK: - WKUIDelegate

    /// window.open / target=_blank: open allowlisted URLs in this same shell
    /// (decidePolicyFor already vetted them), never spawn new web views.
    func webView(_ webView: WKWebView,
                 createWebViewWith configuration: WKWebViewConfiguration,
                 for navigationAction: WKNavigationAction,
                 windowFeatures: WKWindowFeatures) -> WKWebView? {
        if navigationAction.targetFrame == nil, let url = navigationAction.request.url {
            webView.load(URLRequest(url: url))
        }
        return nil
    }

    func webView(_ webView: WKWebView,
                 runJavaScriptAlertPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping () -> Void) {
        DispatchQueue.main.async { [weak self] in
            self?.model?.jsDialog = JSDialog(
                title: "StreamBox", message: message,
                kind: .alert(completionHandler))
        }
    }

    func webView(_ webView: WKWebView,
                 runJavaScriptConfirmPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping (Bool) -> Void) {
        DispatchQueue.main.async { [weak self] in
            self?.model?.jsDialog = JSDialog(
                title: "StreamBox", message: message,
                kind: .confirm({ completionHandler(true) }, { completionHandler(false) }))
        }
    }

    func webView(_ webView: WKWebView,
                 runJavaScriptTextInputPanelWithPrompt prompt: String,
                 defaultText: String?,
                 initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping (String?) -> Void) {
        DispatchQueue.main.async { [weak self] in
            self?.model?.jsDialog = JSDialog(
                title: prompt.isEmpty ? "StreamBox" : prompt,
                message: "",
                kind: .prompt(completionHandler))
        }
    }

    // MARK: - WKScriptMessageHandler

    func userContentController(_ userContentController: WKUserContentController,
                               didReceive message: WKScriptMessage) {
        guard message.name == "native",
              let body = message.body as? [String: Any],
              let type = body["type"] as? String else { return }
        switch type {
        case "share":
            let url = (body["url"] as? String).flatMap(URL.init(string:))
            let text = (body["text"] as? String) ?? "StreamBox"
            DispatchQueue.main.async { [weak self] in
                self?.model?.share(url: url, text: text)
            }
        case "play":
            // Re-validated here: https + direct media extension only. Embed
            // frames are never native-played (authorization stays with the
            // providers' own players).
            guard let s = body["url"] as? String, let url = URL(string: s),
                  AppConfig.isDirectMediaURL(url) else { return }
            DispatchQueue.main.async { [weak self] in
                self?.model?.openNativePlayer(url)
            }
        default:
            break
        }
    }
}

/// WKUserContentController retains its message handlers; this proxy keeps
/// the cycle from pinning the WebViewModel alive.
private final class ScriptHandlerProxy: NSObject, WKScriptMessageHandler {
    weak var owner: WebViewModel?
    func userContentController(_ userContentController: WKUserContentController,
                               didReceive message: WKScriptMessage) {
        owner?.userContentController(userContentController, didReceive: message)
    }
}
