import SwiftUI
import AVKit

/// Native playback for DIRECT, authorized media URLs (HLS / MP4).
///
/// Scope and honesty: the shipping StreamBox catalog plays through provider
/// embed iframes (CineSrc, AniPM, VidBolt, ...) inside WebKit - those are
/// never routed here, because their authorization and embedding terms live
/// with the providers. This path exists for media the page explicitly hands
/// over (test streams today, licensed direct URLs later), gated twice:
/// `AppConfig.isDirectMediaURL` (https + media extension) and the page-side
/// opt-in. Nothing here bypasses DRM, embed restrictions, or geo rules.
final class NativePlayerController: ObservableObject {
    let player = AVPlayer()

    func open(_ url: URL) {
        guard AppConfig.isDirectMediaURL(url) else { return }
        player.replaceCurrentItem(with: AVPlayerItem(url: url))
        player.play()
    }

    /// Battery rule: an inactive player never lingers.
    func close() {
        player.pause()
        player.replaceCurrentItem(with: nil)
    }
}

/// Fullscreen AVPlayerViewController with Picture-in-Picture + AirPlay.
struct NativePlayerContainer: View {
    let url: URL
    let onClose: () -> Void
    @StateObject private var controller = NativePlayerController()

    var body: some View {
        ZStack(alignment: .topTrailing) {
            NativePlayerView(player: controller.player)
                .ignoresSafeArea()
            Button(action: { controller.close(); onClose() }) {
                Image(systemName: "xmark")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundColor(.white)
                    .frame(width: 44, height: 44)
                    .background(.ultraThinMaterial, in: Circle())
            }
            .padding(.top, 12)
            .padding(.trailing, 12)
            .accessibilityLabel("Close player")
        }
        .onAppear { controller.open(url) }
        .onDisappear { controller.close() }
    }
}

struct NativePlayerView: UIViewControllerRepresentable {
    let player: AVPlayer

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let vc = AVPlayerViewController()
        vc.player = player
        vc.allowsPictureInPicture = true
        vc.allowsAirPlay = true
        vc.canStartPictureInPictureAutomaticallyFromInline = true
        return vc
    }

    func updateUIViewController(_ vc: AVPlayerViewController, context: Context) {}
}
