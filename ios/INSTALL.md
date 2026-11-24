# Installing StreamBox on iPhone + iPad

Every legitimate route, free options first. Apple controls how iOS installs
apps - this guide is honest about what each route requires. It never
suggests unsigned IPAs install directly (they don't), and never uses
enterprise certificates or signing bypasses (those get apps and accounts
banned).

The one-line answer for almost everyone: **use the web app - it is free,
needs no computer, and never expires.** The native app is for people who
want Picture-in-Picture, AirPlay, and system integration and are fine with
a 7-day free-signing refresh.

---

## Option A - Web app via Safari (FREE, recommended)

No account, no computer, no expiry. Apple ships this feature (Add to Home
Screen) and it runs the full StreamBox app in its own window.

1. Open StreamBox in **Safari**: https://dazoarmando29.github.io/streambox/
2. Tap the **Share** button (square with an up arrow).
3. Scroll and tap **Add to Home Screen**.
4. Enable **Open as Web App** if your iOS version offers that toggle.
5. Tap **Add**.
6. Launch StreamBox from your Home Screen.

Honest limits on iOS:
- Picture-in-Picture and AirPlay are limited by Safari (full screen works).
- Offline covers the app shell; streaming needs internet.
- Provider players that only support desktop browsers show a fallback
  message with a mirror switcher - some embeds are simply not iOS-friendly.
- Updates are automatic: the app refreshes itself on launch (a refresh
  banner appears when a new build ships).

## Option B - Native app with a FREE Apple ID (AltStore / SideStore)

The native app is built by this repository's CI (`.github/workflows/ios.yml`)
on every change. Free installation uses Apple's own personal-signing
program, via community tools that re-sign the IPA with YOUR Apple ID:

1. Install [AltStore](https://altstore.io/) on Windows or macOS (or
   [SideStore](https://sidestore.io/) on the device), following their guides.
2. Download `streambox-ios-unsigned-device.ipa` from the newest run of the
   **Build StreamBox iOS** workflow (Actions tab -> latest green run ->
   Artifacts). The artifact ships with `install-metadata.json` stating
   exactly what it is.
3. Open the IPA in AltStore/SideStore. It re-signs with your Apple ID and
   installs on your device (trust the developer under Settings -> General ->
   VPN & Device Management on first launch).

Realities of free signing (Apple's rules, not ours):
- Builds expire after **7 days**. AltStore refreshes them when your computer
  is on the same Wi-Fi; SideStore can refresh anywhere with its VPN trick.
- A free Apple ID can hold **3 active sideloaded apps**.
- Keep your data: reinstalls preserve Continue Watching, My List and
  settings (they live in the app's web storage and the shell never wipes it).

What this IPA is NOT: it is not signed, so it does **not** install via
AirDrop, Files, or `itms-services`. If someone claims an unsigned IPA
installs directly, they are wrong.

## Option C - Xcode development signing (needs a Mac)

With a Mac and Xcode 16: `cd ios && make project`, open `StreamBox.xcodeproj`,
plug in your device, select your (free) Personal Team under Signing & Capabilities,
and Run. Free accounts: 7-day builds, up to 3 apps, 10-device cap does not
apply to personal team (it installs only to YOUR devices registered by Xcode).

## Option D - Ad Hoc distribution (paid membership)

Ad Hoc IPAs need the Apple Developer Program (USD 99/year), device UDIDs
registered in the portal, and a distribution certificate + provisioning
profile. If the project ever has that membership, the CI's signed export
path (see below) produces `streambox-ios-signed.ipa` automatically.

## Option E - TestFlight (paid membership on the publishing side)

TestFlight builds require the paid membership to publish. When a real
public invite link exists it is listed on the website install center
(install.html) and nowhere before. TestFlight builds expire after 90 days
and need periodic updates - the release train (version bumps on every
change) keeps betas alive.

## Option F - App Store (paid membership + review)

The app is kept submission-ready (privacy manifest, metadata, review notes:
see `APPSTORE.md`). The install center shows an App Store button only when a
real listing exists.

## Option G - Alternative distribution (EU) and web-based installs

Apple's alternative distribution (marketplaces / web install) applies only
in the EU and only with a paid membership and Apple's entitlements. It is
**not** available in the Philippines and is not offered here.

---

## CI artifacts - what each file actually is

| Artifact | Signing | Installable on a device? |
| --- | --- | --- |
| `streambox-ios-simulator.zip` | none | No - iOS Simulator only |
| `streambox-ios-unsigned-device.ipa` | none | No - re-sign via AltStore/SideStore first |
| `streambox-ios-signed.ipa` | Apple (only with CI secrets) | Yes - per its provisioning profile |
| `install-metadata.json` | - | Machine-readable statement of the above |

## Updating

- Web app: automatic on launch (banner when a new build is live).
- Native beta: install the newer IPA over the old one (same bundle id) -
  your data survives; AltStore/SideStore re-signs the new build.
- Free signing: remember the 7-day refresh.
- TestFlight / App Store (when live): official update mechanisms only. The
  app never downloads executable code outside Apple's update channels -
  that is both Apple policy and ours.

## Troubleshooting

- **"Add to Home Screen" missing**: you are not in Safari itself (open in
  Safari, not an in-app browser).
- **Icon opens a Safari tab**: remove and re-add the icon; enable "Open as
  Web App" if offered.
- **"Untrusted developer"**: Settings -> General -> VPN & Device Management
  -> trust your Apple ID.
- **Beta stopped launching**: free signing expired - refresh/re-install.
- **A provider won't play**: switch mirrors under the player; some embeds
  don't support iOS browsers - the app says so honestly instead of failing
  silently.
