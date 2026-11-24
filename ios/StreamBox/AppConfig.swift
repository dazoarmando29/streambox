import Foundation

/// Central configuration and the native side of the shared shell contract.
///
/// The web app (index.html) already speaks to the Android wrapper through
/// three channels that this file mirrors exactly:
///   1. `document.title` markers (`sb:landscape` / `sb:portrait` /
///      `sb:awake` / `sb:sleep`),
///   2. a Back-cascade script that returns a sentinel string,
///   3. a main-frame host allowlist + an ad-host blocklist.
/// Keeping the lists identical on both platforms is a deliberate rule: the
/// same sites that work on Android must work here, and nothing else loads.
enum AppConfig {

    // MARK: - URLs

    static let siteURL = URL(string: "https://dazoarmando29.github.io/streambox/")!
    static let siteHost = "dazoarmando29.github.io"
    static let urlScheme = "streambox"

    /// Home URL. `lite` mirrors the Android `lite` flavor (`?calm=1`), and a
    /// `party` code mirrors the `streambox://party/CODE` deep link.
    static func homeURL(lite: Bool, partyCode: String? = nil) -> URL {
        var comps = URLComponents(url: siteURL, resolvingAgainstBaseURL: false)!
        var items: [URLQueryItem] = []
        if lite { items.append(URLQueryItem(name: "calm", value: "1")) }
        if let code = sanitizedPartyCode(partyCode) {
            items.append(URLQueryItem(name: "party", value: code))
        }
        if !items.isEmpty { comps.queryItems = items }
        return comps.url ?? siteURL
    }

    /// Party codes are 1-6 alphanumeric characters. Same rule as the Android
    /// wrapper sanitising `streambox://party/<CODE>` before `PARTY.open()`.
    static func sanitizedPartyCode(_ raw: String?) -> String? {
        guard let raw = raw else { return nil }
        let cleaned = String(raw.filter { $0.isLetter || $0.isNumber }.prefix(6))
        return cleaned.isEmpty ? nil : cleaned
    }

    // MARK: - Navigation allowlist (mirrors MainActivity.shouldOverrideUrlLoading)

    /// Main-frame navigations are limited to these hosts (host or subdomain).
    /// Subframe traffic is NOT filtered by host - players navigate their own
    /// inner frames to video hosts and killing those kills playback. Only
    /// non-http(s) schemes are blocked in subframes (same as Android).
    static let allowedHosts: Set<String> = [
        "dazoarmando29.github.io",
        "cinesrc.st",
        "themoviedb.org", "image.tmdb.org",
        "vidlink.pro", "vidfast.pro", "vidfast.vc",
        "vidsrc.to", "vsembed.su",
        "autoembed.co", "2embed.cc",
        "yapgrid.com", "vaplayer.ru",
        "vidcore.org", "vidrift.net",
        "vidzen.fun", "vidbolt.pro",
        "ani.pm",
        "youtube-nocookie.com", "youtube.com", "youtu.be",
    ]

    /// Host-or-subdomain match (mirrors MainActivity.isHostOrSubdomain).
    static func isHostAllowed(_ host: String) -> Bool {
        let h = host.lowercased()
        for allowed in allowedHosts {
            if h == allowed || h.hasSuffix("." + allowed) { return true }
        }
        return false
    }

    // MARK: - Ad Shield (mirrors MainActivity.AD_HOSTS)

    /// Ad-tech / popunder / tracker infrastructure - never media delivery.
    /// Blocked in every frame via WKContentRuleList. The exact list the
    /// Android wrapper blocks in shouldInterceptRequest.
    static let adHosts: [String] = [
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
        "googletagmanager.com", "adservice.google.com", "adnxs.com", "adsrvr.org", "adroll.com",
        "popads.net", "popcash.net", "propellerads.com", "propellerads.net", "popundertotal.com",
        "tapadu.com", "exoclick.com", "juicyads.com", "hilltopads.com", "highcpm.com",
        "adsterra.com", "adsterra.net", "histats.com", "hotjar.com", "facebook.net",
        "amazon-adsystem.com", "scorecardresearch.com", "outbrain.com", "taboola.com",
        "trafficjunky.net", "trafficjunky.com", "a-ads.com", "ads-mrg.com", "onclickmax.com",
        "adblokkster.com", "bc.vc", "bc-ads.com", "tsyndicate.com", "syndicat-france.com",
        "adform.net", "smartadserver.com", "rubiconproject.com", "pubmatic.com",
        "criteo.com", "criteo.net", "casalemedia.com", "openx.net", "openx.com",
        "bidswitch.net", "sharethrough.com", "moatads.com", "doubleverify.com",
        "bluekai.com", "demdex.net", "krxd.net", "rlcdn.com", "agkn.com",
        "teads.tv", "yieldmo.com", "revcontent.com", "mgid.com", "content-ad.net",
        "ad-maven.com", "onclicka.com", "ads.yahoo.com", "adtech.de", "adition.com",
    ]

    // MARK: - document.title marker channel

    enum TitleMarker: String {
        case landscape = "sb:landscape"
        case portrait = "sb:portrait"
        case awake = "sb:awake"
        case sleep = "sb:sleep"
    }

    static func titleMarker(_ title: String) -> TitleMarker? {
        TitleMarker(rawValue: title)
    }

    // MARK: - Native playback (authorized direct media URLs only)

    /// The shipping web app plays through provider embed iframes; those stay
    /// in WebKit. The native AVPlayer path is reserved for direct, authorized
    /// media URLs (HLS/MP4) the page may hand over. Never used to bypass DRM,
    /// embedding restrictions, or provider security controls.
    static let directMediaExtensions: Set<String> = ["m3u8", "mp4", "m4v", "mov", "aac"]

    static func isDirectMediaURL(_ url: URL) -> Bool {
        guard let scheme = url.scheme?.lowercased(), scheme == "https" else { return false }
        return directMediaExtensions.contains(url.pathExtension.lowercased())
    }

    // MARK: - Version info

    static var appVersion: String {
        (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "?"
    }

    static var appBuild: String {
        (Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String) ?? "?"
    }
}
