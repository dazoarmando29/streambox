import Foundation

/// Persistent app settings (UserDefaults - declared in PrivacyInfo.xcprivacy
/// under reason CA92.1). The injected suite keeps unit tests hermetic.
///
/// None of these are secrets. Sensitive data lives only in the web layer's
/// own localStorage, which persists across app updates (the shell never
/// wipes the WKWebsiteDataStore - same rule as the Android wrapper).
final class SettingsStore {
    private let defaults: UserDefaults

    private enum Keys {
        static let liteMode = "sb.liteMode"
        static let nativePlayer = "sb.nativePlayerEnabled"
        static let menuHintShown = "sb.menuHintShown"
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// Lite / low-resource mode: reloads the web app with `?calm=1` (the same
    /// edition the Android `lite` flavor ships). Off by default on iPhones,
    /// but any user can flip it for max smoothness on weak devices.
    var liteMode: Bool {
        get { defaults.bool(forKey: Keys.liteMode) }
        set { defaults.set(newValue, forKey: Keys.liteMode) }
    }

    /// Whether the page may hand direct, authorized media URLs to the native
    /// AVPlayer. Defaults to on; the page is the sole decider of which URLs
    /// qualify (AppConfig.isDirectMediaURL gates them again here).
    var nativePlayerEnabled: Bool {
        get {
            guard defaults.object(forKey: Keys.nativePlayer) != nil else { return true }
            return defaults.bool(forKey: Keys.nativePlayer)
        }
        set { defaults.set(newValue, forKey: Keys.nativePlayer) }
    }

    var menuHintShown: Bool {
        get { defaults.bool(forKey: Keys.menuHintShown) }
        set { defaults.set(newValue, forKey: Keys.menuHintShown) }
    }
}
