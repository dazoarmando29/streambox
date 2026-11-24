import SwiftUI
import WebKit

/// WKWebView host (the web app fills the full screen and pads for safe
/// areas itself - viewport-fit=cover + env(safe-area-inset-*), same as the
/// Android shell's edge-to-edge WebView).
struct WebViewHost: UIViewRepresentable {
    let webView: WKWebView
    func makeUIView(context: Context) -> WKWebView { webView }
    func updateUIView(_ uiView: WKWebView, context: Context) {}
}

struct ContentView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var promptText = ""
    @State private var showSettings = false

    private var sharePresented: Binding<Bool> {
        Binding(get: { model.shareItems != nil },
                set: { if !$0 { model.shareItems = nil } })
    }

    private var playerPresented: Binding<Bool> {
        Binding(get: { model.playerURL != nil },
                set: { if !$0 { model.playerURL = nil } })
    }

    private var dialogPresented: Binding<Bool> {
        Binding(get: { model.jsDialog != nil },
                set: { if !$0 { model.jsDialog = nil } })
    }

    var body: some View {
        ZStack {
            Color(red: 0.043, green: 0.067, blue: 0.118)
                .ignoresSafeArea()

            WebViewHost(webView: model.web.webView)
                .ignoresSafeArea()

            // thin load progress (the web app renders its own splash state)
            VStack(spacing: 0) {
                if model.pageLoadProgress > 0 && model.pageLoadProgress < 1 {
                    ProgressView(value: model.pageLoadProgress)
                        .progressViewStyle(.linear)
                        .tint(Color(red: 0.20, green: 0.89, blue: 0.69))
                }
                Spacer()
            }

            if !model.isOnline {
                VStack {
                    OfflineBanner()
                    Spacer()
                }
                .transition(.move(edge: .top).combined(with: .opacity))
            }

            if let err = model.webError {
                ErrorView(message: err) { model.reload() }
                    .transition(.opacity)
            }

            // native chrome: one glass menu (share / reload / lite / awake)
            if !model.splashVisible && !model.landscapeLocked {
                VStack {
                    HStack {
                        Spacer()
                        nativeMenu
                    }
                    Spacer()
                }
            }

            if model.splashVisible {
                SplashView()
                    .transition(.opacity)
            }

            // App lock: opaque cover (also masks the app-switcher snapshot
            // content behind it while locked).
            if model.locked {
                LockOverlay(onUnlock: { model.requestUnlock() })
                    .transition(.opacity)
            }
        }
        .onChange(of: scenePhase) { phase in
            switch phase {
            case .active: model.onScenePhaseActive()
            case .background: model.onScenePhaseBackground()
            default: break
            }
        }
        .alert("App Lock", isPresented: Binding(
            get: { model.lockNotice != nil },
            set: { if !$0 { model.lockNotice = nil } })) {
            Button("OK", role: .cancel) { model.lockNotice = nil }
        } message: {
            Text(model.lockNotice ?? "")
        }
        .animation(.easeInOut(duration: 0.25), value: model.splashVisible)
        .animation(.easeInOut(duration: 0.25), value: model.isOnline)
        .animation(.easeInOut(duration: 0.25), value: model.webError != nil)
        // Back: edge-swipe (left) runs the same SPA cascade as Android Back.
        .simultaneousGesture(
            DragGesture(minimumDistance: 24)
                .onEnded { value in
                    if value.startLocation.x < 32,
                       value.translation.width > 90,
                       abs(value.translation.height) < 80 {
                        model.back()
                    }
                }
        )
        // hardware keyboard parity (iPad / keyboard cases). Command-only so
        // plain keys keep typing into the web layer's own fields.
        .background(
            VStack {
                Button("") { model.back() }
                    .keyboardShortcut(.cancelAction)
                    .opacity(0)
                Button("") { model.reload() }
                    .keyboardShortcut("r", modifiers: .command)
                    .opacity(0)
                Button("") { model.share(url: model.web.webView.url, text: "StreamBox") }
                    .keyboardShortcut("s", modifiers: .command)
                    .opacity(0)
                Button("") { model.web.evaluate("try{goHome();}catch(e){}") }
                    .keyboardShortcut("1", modifiers: .command)
                    .opacity(0)
                Button("") { model.web.evaluate("try{filterType('movie');}catch(e){}") }
                    .keyboardShortcut("2", modifiers: .command)
                    .opacity(0)
                Button("") { model.web.evaluate("try{showList();}catch(e){}") }
                    .keyboardShortcut("3", modifiers: .command)
                    .opacity(0)
            }
        )
        .sheet(isPresented: sharePresented) {
            if let items = model.shareItems {
                ActivityView(items: items)
            }
        }
        .fullScreenCover(isPresented: playerPresented) {
            if let url = model.playerURL {
                NativePlayerContainer(url: url) { model.playerURL = nil }
            }
        }
        .sheet(isPresented: dialogPresented) {
            if let dialog = model.jsDialog {
                JSDialogView(dialog: dialog, promptText: $promptText) {
                    model.jsDialog = nil
                }
            }
        }
    }

    private var nativeMenu: some View {
        Menu {
            Button {
                model.share(url: model.web.webView.url, text: "StreamBox")
            } label: {
                Label("Share StreamBox", systemImage: "square.and.arrow.up")
            }
            Button {
                model.reload()
            } label: {
                Label("Reload", systemImage: "arrow.clockwise")
            }
            Button {
                UIApplication.shared.open(AppConfig.siteURL)
            } label: {
                Label("Open in Safari", systemImage: "safari")
            }
            Button {
                showSettings = true
                Haptics.tap()
            } label: {
                Label("Settings", systemImage: "gearshape")
            }
            Divider()
            Toggle(isOn: Binding(
                get: { model.settings.liteMode },
                set: { model.settings.liteMode = $0; Haptics.tap(); model.reload() }
            )) {
                Label("Lite Mode", systemImage: "leaf")
            }
            Toggle(isOn: Binding(
                get: { model.keepAwake },
                set: { model.keepAwake = $0; model.applyIdleTimer(); Haptics.tap() }
            )) {
                Label("Keep Screen Awake", systemImage: "sun.max")
            }
            Divider()
            Text("StreamBox " + AppConfig.appVersion + " (" + AppConfig.appBuild + ")")
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: 17, weight: .bold))
                .foregroundColor(.white)
                .frame(width: 44, height: 44)
                .background(.ultraThinMaterial, in: Circle())
                .overlay(Circle().stroke(Color.white.opacity(0.15), lineWidth: 1))
        }
        .accessibilityLabel("StreamBox menu")
        .sheet(isPresented: $showSettings) {
            SettingsView()
        }
    }
}

/// Face ID / Touch ID / passcode gate. Opaque by design: while locked the
/// app content must not be readable (including in the app switcher).
struct LockOverlay: View {
    let onUnlock: () -> Void

    var body: some View {
        ZStack {
            Color(red: 0.043, green: 0.067, blue: 0.118)
                .ignoresSafeArea()
            VStack(spacing: 18) {
                Image(systemName: "lock.fill")
                    .font(.system(size: 42, weight: .bold))
                    .foregroundColor(Color(red: 0.20, green: 0.89, blue: 0.69))
                Text("StreamBox is locked")
                    .font(.system(size: 22, weight: .black, design: .rounded))
                    .foregroundColor(.white)
                Button(action: onUnlock) {
                    Label("Unlock", systemImage: "faceid")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(Color(red: 0.04, green: 0.08, blue: 0.15))
                        .padding(.horizontal, 30)
                        .padding(.vertical, 15)
                        .background(Color(red: 0.20, green: 0.89, blue: 0.69), in: Capsule())
                }
                .accessibilityLabel("Unlock StreamBox with Face ID or passcode")
            }
        }
    }
}

/// Offline / reconnecting pill (native state, shown above the web layer).
struct OfflineBanner: View {
    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "wifi.slash")
            Text("No connection - streaming will resume automatically")
                .font(.footnote.weight(.semibold))
        }
        .foregroundColor(.white)
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(Color(red: 0.06, green: 0.09, blue: 0.16).opacity(0.95), in: Capsule())
        .padding(.top, 8)
        .accessibilityLabel("No internet connection")
    }
}

/// Alert / confirm / prompt rendered natively so page dialogs never hang.
struct JSDialogView: View {
    let dialog: JSDialog
    @Binding var promptText: String
    let onClose: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Text(dialog.title)
                .font(.headline)
            if !dialog.message.isEmpty {
                Text(dialog.message)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            switch dialog.kind {
            case .alert(let done):
                Button("OK") { done(); onClose() }
                    .buttonStyle(.borderedProminent)
            case .confirm(let yes, let no):
                HStack {
                    Button("Cancel") { no(); onClose() }
                        .buttonStyle(.bordered)
                    Button("OK") { yes(); onClose() }
                        .buttonStyle(.borderedProminent)
                }
            case .prompt(let done):
                TextField("", text: $promptText)
                    .textFieldStyle(.roundedBorder)
                HStack {
                    Button("Cancel") { done(nil); onClose() }
                        .buttonStyle(.bordered)
                    Button("OK") { done(promptText); onClose() }
                        .buttonStyle(.borderedProminent)
                }
            }
        }
        .padding(24)
        .presentationDetents([.height(280)])
    }
}
