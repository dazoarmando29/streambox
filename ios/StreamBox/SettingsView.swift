import SwiftUI

/// Native Settings sheet - the Apple-style grouped list for everything the
/// SHELL owns (the web app's own settings stay in its Settings panel).
/// Sections follow the brief's taxonomy where they apply to the shell:
/// Appearance (experience modes), Player, Privacy & Security, Accessibility,
/// and About. Every control saves instantly; nothing here deletes watch data.
struct SettingsView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss

    private var liteBinding: Binding<Bool> {
        Binding(
            get: { model.settings.liteMode },
            set: { on in
                model.settings.liteMode = on
                if on { model.settings.performanceMode = "lite" }
                else if model.settings.performanceMode == "lite" { model.settings.performanceMode = "adaptive" }
                model.reload()
            })
    }

    private var modeBinding: Binding<String> {
        Binding(
            get: { model.settings.performanceMode },
            set: { m in
                model.settings.performanceMode = m
                if m == "lite" && !model.settings.liteMode {
                    model.settings.liteMode = true
                    model.reload()
                } else if m != "lite" && model.settings.liteMode {
                    model.settings.liteMode = false
                    model.reload()
                }
                Haptics.select()
            })
    }

    private var lockBinding: Binding<Bool> {
        Binding(
            get: { model.settings.appLockEnabled },
            set: { on in
                if on && !AppLock.canAuthenticate() {
                    // honest: no passcode on the device means the lock is theater
                    model.lockNotice = "Set a device passcode (or Face ID / Touch ID) in iOS Settings first - the lock needs it to protect StreamBox."
                    model.settings.appLockEnabled = false
                    return
                }
                model.settings.appLockEnabled = on
                if on { model.locked = true; model.requestUnlock() }
                Haptics.tap()
            })
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("Experience", selection: modeBinding) {
                        Text("Adaptive").tag("adaptive")
                        Text("Premium").tag("premium")
                        Text("Lite").tag("lite")
                    }
                    .pickerStyle(.segmented)
                    Toggle("Lite Mode (web calm edition)", isOn: liteBinding)
                } header: {
                    Text("Appearance & Performance")
                } footer: {
                    Text("Adaptive balances quality and battery. Premium keeps richer materials. Lite drops decorative effects and motion for the weakest devices.")
                }

                Section {
                    Toggle("Native player for direct media", isOn: Binding(
                        get: { model.settings.nativePlayerEnabled },
                        set: { model.settings.nativePlayerEnabled = $0; Haptics.tap() }))
                } header: {
                    Text("Playback")
                } footer: {
                    Text("Authorized direct media URLs play in the native AVKit player (Picture-in-Picture, AirPlay). Provider embeds always play in their own frames.")
                }

                Section {
                    Toggle("App Lock (Face ID / Passcode)", isOn: lockBinding)
                    if model.settings.appLockEnabled {
                        Toggle("Lock after leaving the app", isOn: Binding(
                            get: { model.settings.appLockOnBackground },
                            set: { model.settings.appLockOnBackground = $0 }))
                    }
                } header: {
                    Text("Privacy & Security")
                } footer: {
                    Text("The lock gates the app only - StreamBox never sees your biometrics and stores no personal data to protect beyond what stays on this device.")
                }

                Section {
                    Toggle("Haptic feedback", isOn: Binding(
                        get: { model.settings.hapticsEnabled },
                        set: { model.settings.hapticsEnabled = $0; Haptics.setEnabled($0); if $0 { Haptics.tap() } }))
                } header: {
                    Text("Accessibility")
                } footer: {
                    Text("Subtle feedback on meaningful actions. Reduce Motion and Reduce Transparency in iOS Settings are respected automatically.")
                }

                Section {
                    LabeledContent("Version", value: AppConfig.appVersion + " (" + AppConfig.appBuild + ")")
                    LabeledContent("Web build", value: "238")
                    Link("Install Center", destination: URL(string: "https://dazoarmando29.github.io/streambox/install.html")!)
                    Link("Privacy Policy", destination: URL(string: "https://dazoarmando29.github.io/streambox/privacy.html")!)
                    Link("Project on GitHub", destination: URL(string: "https://github.com/dazoarmando29/streambox")!)
                } header: {
                    Text("About StreamBox")
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .onAppear { Haptics.setEnabled(model.settings.hapticsEnabled) }
        }
    }
}
