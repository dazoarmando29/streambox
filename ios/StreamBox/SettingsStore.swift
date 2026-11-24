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
        static let haptics = "sb.hapticsEnabled"
        static let appLock = "sb.appLockEnabled"
        static let appLockBackground = "sb.appLockOnBackground"
        static let performanceMode = "sb.performanceMode"
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

    /// StreamBox-specific haptics (system feedback generators). On by default,
    /// user-disableable; never fires on plain scrolling or every button.
    var hapticsEnabled: Bool {
        get {
            guard defaults.object(forKey: Keys.haptics) != nil else { return true }
            return defaults.bool(forKey: Keys.haptics)
        }
        set { defaults.set(newValue, forKey: Keys.haptics) }
    }

    /// Optional app lock (Face ID / Touch ID / device passcode).
    var appLockEnabled: Bool {
        get { defaults.bool(forKey: Keys.appLock) }
        set { defaults.set(newValue, forKey: Keys.appLock) }
    }

    /// Re-lock shortly after backgrounding (default on when the lock is on).
    var appLockOnBackground: Bool {
        get {
            guard defaults.object(forKey: Keys.appLockBackground) != nil else { return true }
            return defaults.bool(forKey: Keys.appLockBackground)
        }
        set { defaults.set(newValue, forKey: Keys.appLockBackground) }
    }

    /// Experience mode: "adaptive" (default), "premium", "lite".
    /// Lite maps onto the web's calm=1 edition and reduces native effects.
    var performanceMode: String {
        get { defaults.string(forKey: Keys.performanceMode) ?? "adaptive" }
        set { defaults.set(newValue, forKey: Keys.performanceMode) }
    }
}
