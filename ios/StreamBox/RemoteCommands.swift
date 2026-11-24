import MediaPlayer

/// Headset / Bluetooth / Control Center transport keys routed into the web
/// player (the same JS the Android wrapper sends from dispatchKeyEvent).
/// Play and pause only fire when the page state disagrees, so a quick
/// pause-then-play is never swallowed by the first echo (same guard the
/// site's own controls use).
enum RemoteCommands {

    static let toggleJS = "try{ctlToggle();}catch(e){}"
    static let playJS = "try{if(pState.paused)ctlToggle();}catch(e){}"
    static let pauseJS = "try{if(!pState.paused)ctlToggle();}catch(e){}"

    static func install(evaluate: @escaping (String) -> Void) {
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { _ in
            evaluate(playJS)
            return .success
        }
        center.pauseCommand.addTarget { _ in
            evaluate(pauseJS)
            return .success
        }
        center.togglePlayPauseCommand.addTarget { _ in
            evaluate(toggleJS)
            return .success
        }
        center.stopCommand.addTarget { _ in
            evaluate(pauseJS)
            return .success
        }
    }
}
