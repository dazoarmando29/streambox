import SwiftUI

/// Native boot screen: brand plate on the Obsidian Glow canvas. Fades out on
/// first page paint (or a short cap) - the web app carries its own branded
/// splash underneath, so this one just hides the WKWebView white flash.
struct SplashView: View {
    @State private var pulse = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            Color(red: 0.043, green: 0.067, blue: 0.118)
                .ignoresSafeArea()
            VStack(spacing: 18) {
                RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .fill(
                        LinearGradient(
                            colors: [
                                Color(red: 0.49, green: 0.83, blue: 0.98),
                                Color(red: 0.18, green: 0.83, blue: 0.75),
                            ],
                            startPoint: .topLeading, endPoint: .bottomTrailing)
                    )
                    .frame(width: 96, height: 96)
                    .overlay(
                        Image(systemName: "play.fill")
                            .font(.system(size: 38, weight: .black))
                            .foregroundColor(Color(red: 0.04, green: 0.08, blue: 0.15))
                    )
                    .scaleEffect(pulse ? 1.04 : 0.98)
                    .shadow(color: Color(red: 0.18, green: 0.83, blue: 0.75).opacity(0.35), radius: 24)
                Text("StreamBox")
                    .font(.system(size: 26, weight: .black, design: .rounded))
                    .foregroundColor(.white)
                Text("LOADING")
                    .font(.system(size: 11, weight: .bold))
                    .tracking(3)
                    .foregroundColor(Color(red: 0.20, green: 0.89, blue: 0.69))
            }
        }
        .onAppear {
            // Reduce Motion: the plate stays still (system accessibility rule)
            guard !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) {
                pulse = true
            }
        }
        .accessibilityLabel("StreamBox is loading")
    }
}

/// Full-screen native error/offline state with a one-tap retry (and an
/// honest escape hatch to Safari). Shown only for real load failures.
struct ErrorView: View {
    let message: String
    let onRetry: () -> Void

    var body: some View {
        ZStack {
            Color(red: 0.043, green: 0.067, blue: 0.118)
                .ignoresSafeArea()
            VStack(spacing: 16) {
                Image(systemName: "wifi.exclamationmark")
                    .font(.system(size: 44, weight: .bold))
                    .foregroundColor(Color(red: 0.20, green: 0.89, blue: 0.69))
                Text("Couldn't load StreamBox")
                    .font(.system(size: 22, weight: .black, design: .rounded))
                    .foregroundColor(.white)
                Text(message)
                    .font(.footnote)
                    .foregroundColor(.gray)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)
                Button(action: onRetry) {
                    Label("Try Again", systemImage: "arrow.clockwise")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(Color(red: 0.04, green: 0.08, blue: 0.15))
                        .padding(.horizontal, 28)
                        .padding(.vertical, 14)
                        .background(Color(red: 0.20, green: 0.89, blue: 0.69), in: Capsule())
                }
                .accessibilityLabel("Try loading StreamBox again")
                Button("Open in Safari") {
                    UIApplication.shared.open(AppConfig.siteURL)
                }
                .font(.footnote.weight(.semibold))
                .foregroundColor(.gray)
            }
            .padding(24)
        }
    }
}
