import SwiftUI
import UIKit

/// Reads the orientation mask the shell state tilts (sb:landscape / sb:portrait
/// marker channel). Supported orientations stay wide open by default so iPad
/// multitasking and video rotation keep working - the marker only REQUESTS
/// a rotation and temporarily narrows the mask while cinema mode is on.
final class AppDelegate: NSObject, UIApplicationDelegate {
    static var orientationMask: UIInterfaceOrientationMask = .allButUpsideDown

    func application(_ application: UIApplication,
                     supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        AppDelegate.orientationMask
    }
}

@main
struct StreamBoxApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
                .preferredColorScheme(.dark)
                .onOpenURL { url in
                    model.handleDeepLink(url)
                }
                .onAppear {
                    if UIDevice.current.userInterfaceIdiom == .pad {
                        AppDelegate.orientationMask = .all
                    }
                    // The web app paints its own branded splash underneath,
                    // so the native one can leave early - but never earlier
                    // than the first page paint when that arrives first.
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) {
                        model.dismissSplashIfNeeded()
                    }
                }
        }
    }
}
