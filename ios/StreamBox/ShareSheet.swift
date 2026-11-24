import SwiftUI
import UIKit

/// System share sheet (UIActivityViewController) - the native way to hand a
/// StreamBox link or invite to Messages, Mail, AirDrop, or any app.
struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}
