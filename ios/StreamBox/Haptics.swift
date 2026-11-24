import UIKit

/// Subtle system haptics for meaningful actions only (never per-button,
/// never while scrolling). Respects the user's StreamBox haptics setting and
/// the device's capabilities - a disabled generator is a no-op.
enum Haptics {

    private static var enabled: Bool = true

    static func setEnabled(_ on: Bool) {
        enabled = on
    }

    /// Selection / option picked (playback options, settings, menus).
    static func select() {
        guard enabled else { return }
        let gen = UISelectionFeedbackGenerator()
        gen.selectionChanged()
    }

    /// Light impact: adding to My List, toggling a meaningful switch.
    static func tap() {
        guard enabled else { return }
        let gen = UIImpactFeedbackGenerator(style: .light)
        gen.impactOccurred()
    }

    /// Medium impact: opening/closing the player, big actions.
    static func impact() {
        guard enabled else { return }
        let gen = UIImpactFeedbackGenerator(style: .medium)
        gen.impactOccurred()
    }

    /// Success: share sheet launched, settings confirmed.
    static func success() {
        guard enabled else { return }
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }

    /// Warning: a meaningful error (load failure, permission missing).
    static func warning() {
        guard enabled else { return }
        UINotificationFeedbackGenerator().notificationOccurred(.warning)
    }
}
