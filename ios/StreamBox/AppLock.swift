import Foundation
import LocalAuthentication

/// Optional app lock using the system's own authentication (Face ID / Touch
/// ID / device passcode). StreamBox never sees biometric data - LocalAuthentication
/// only returns a yes/no. Falls back to the device passcode automatically
/// when biometrics are unavailable or locked out.
enum AppLock {

    static func canAuthenticate() -> Bool {
        let ctx = LAContext()
        var err: NSError?
        return ctx.canEvaluatePolicy(.deviceOwnerAuthentication, error: &err)
    }

    /// Runs the system prompt. `reason` shows in the Face ID / passcode sheet.
    static func authenticate(reason: String, completion: @escaping (Bool) -> Void) {
        let ctx = LAContext()
        var err: NSError?
        guard ctx.canEvaluatePolicy(.deviceOwnerAuthentication, error: &err) else {
            // No passcode set on the device: the lock cannot protect anything -
            // report honestly instead of blocking the app forever.
            DispatchQueue.main.async { completion(false) }
            return
        }
        ctx.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) { ok, _ in
            DispatchQueue.main.async { completion(ok) }
        }
    }
}
