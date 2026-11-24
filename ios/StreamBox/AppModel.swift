import SwiftUI
import UIKit
import Network

/// Observable shell state. Everything the native chrome draws (splash,
/// error/offline states, menu, dialogs, player sheet) reads from here, and
/// the web layer writes to here through the title-marker channel and the
/// `native` message handler.
final class AppModel: ObservableObject {

    // Splash (native boot screen; hides on first page paint or a short cap)
    @Published var splashVisible = true

    // Connectivity + failure states
    @Published var isOnline = true
    @Published var webError: String? = nil
    @Published var pageLoadProgress: Double = 0

    // Shell state driven by the document.title marker channel
    @Published var landscapeLocked = false
    @Published var keepAwake = false

    // Native menu + share
    @Published var shareItems: [Any]? = nil

    // Native player (authorized direct media URLs only)
    @Published var playerURL: URL? = nil

    // JS dialogs (alert / confirm / prompt) surfaced as native sheets
    @Published var jsDialog: JSDialog? = nil

    // Deep links wait for the page before PARTY.open() runs
    @Published var pendingPartyCode: String? = nil

    let settings = SettingsStore()
    let web: WebViewModel
    private let monitor = NWPathMonitor()
    private var wasOffline = false

    init() {
        web = WebViewModel()
        web.model = self
        monitor.pathUpdateHandler = { [weak self] path in
            DispatchQueue.main.async { self?.applyPath(path) }
        }
        monitor.start(queue: DispatchQueue(label: "streambox.network"))
        RemoteCommands.install { [weak self] js in
            self?.web.evaluate(js)
        }
    }

    // MARK: - Connectivity

    private func applyPath(_ path: NWPath) {
        let online = path.status == .satisfied
        isOnline = online
        if online && wasOffline {
            // network came back after a failure: recover automatically
            if webError != nil {
                webError = nil
                web.reload()
            }
        }
        wasOffline = !online
    }

    // MARK: - document.title marker channel (shared contract with Android)

    func titleMarkerChanged(_ marker: AppConfig.TitleMarker) {
        switch marker {
        case .landscape:
            landscapeLocked = true
            applyOrientation()
        case .portrait:
            landscapeLocked = false
            applyOrientation()
        case .awake:
            keepAwake = true
            applyIdleTimer()
        case .sleep:
            keepAwake = false
            applyIdleTimer()
        }
    }

    /// Orientation policy: the app supports all orientations; the marker
    /// channel only REQUESTS a rotation and tilts the supported mask so the
    /// cinema mode stays landscape (mirrors the Android setRequestedOrientation).
    func applyOrientation() {
        if UIDevice.current.userInterfaceIdiom == .pad {
            AppDelegate.orientationMask = .all
        } else {
            AppDelegate.orientationMask = landscapeLocked ? .landscape : .allButUpsideDown
        }
        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene else { return }
        if landscapeLocked {
            scene.requestGeometryUpdate(.iOS(interfaceOrientations: .landscape))
        } else {
            scene.requestGeometryUpdate(.iOS(interfaceOrientations: .portrait))
        }
        // re-evaluate the (temporarily narrowed) orientation mask
        scene.keyWindow?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
    }

    func applyIdleTimer() {
        UIApplication.shared.isIdleTimerDisabled = keepAwake
    }

    // MARK: - Deep links (streambox://party/CODE)

    func handleDeepLink(_ url: URL) {
        guard url.scheme?.lowercased() == AppConfig.urlScheme else { return }
        if url.host?.lowercased() == "party" {
            let raw = url.lastPathComponent
            if let code = AppConfig.sanitizedPartyCode(raw) {
                pendingPartyCode = code
                web.openParty(code)
            }
        }
    }

    // MARK: - Actions surfaced by the native chrome

    func share(url: URL?, text: String) {
        var items: [Any] = []
        items.append(text)
        if let url = url { items.append(url) }
        shareItems = items
    }

    func reload() {
        webError = nil
        web.reload()
    }

    /// The shell Back action: run the SPA cascade first; only when the page
    /// reports 'exit' and we are on a non-app page do we walk WebView
    /// history (same fail-safe as Android; iOS never kills the app).
    func back() {
        web.runBackCascade()
    }

    func openNativePlayer(_ url: URL) {
        guard settings.nativePlayerEnabled, AppConfig.isDirectMediaURL(url) else { return }
        playerURL = url
    }

    func dismissSplashIfNeeded() {
        guard splashVisible else { return }
        withAnimation(.easeOut(duration: 0.35)) { splashVisible = false }
    }
}

/// JS dialog model (alert / confirm / prompt). The web app builds its own
/// UI, but any page-level dialog must still land somewhere native rather
/// than silently hanging its JavaScript callback.
struct JSDialog: Identifiable {
    enum Kind {
        case alert(() -> Void)
        case confirm(() -> Void, () -> Void)
        case prompt((String?) -> Void)
    }
    let id = UUID()
    let title: String
    let message: String
    let kind: Kind
}
