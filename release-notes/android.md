The iPhone & iPad expansion, install center, and PWA polish - the same StreamBox now installs everywhere, free-first.

What's new
- iOS / iPadOS: a complete native Xcode project (Swift + SwiftUI + WKWebView) joins the repo - native splash, secure JS bridge, share sheet, AVKit playback path for authorized direct media, Ad Shield content blocking, deep links (streambox://party/CODE), offline states, iPad multitasking and keyboard/trackpad support. Built and simulator-tested by GitHub Actions on macOS runners (no Mac needed)
- Install center (install.html): one polished page that detects your device and shows the right free options first - Web App for iPhone/iPad (Safari Add to Home Screen guide), Android APKs, TV and desktop routes, plus honest cards for TestFlight/App Store that activate only when real links exist. Footer now carries Install + Privacy doors
- PWA upgrade: proper apple-touch-icon sizes (180/167/152), 1024 manifest icons with a true maskable variant, service worker shell cache v3 with the new icons, and the More-sheet Install door now always leads somewhere useful on iOS (which never fires beforeinstallprompt)
- Privacy page (privacy.html): plain-language policy - no accounts, no analytics, no ads of our own; what stays on device, what crosses the network, and the iOS privacy manifest that declares it to Apple

Housekeeping
- Build 238; Android wrapper 2.36 (versionCode 38). The iOS build number is kept in step with version.txt by tools/sync-ios-version.js and CI checks it
- QA gate green (0 failures) with every lock preserved: footer legend, About update flow, sw cache honesty, wrapper safety

Honest limits
- iOS provider playback depends on each embed supporting mobile WebKit - where one cannot play, the app shows a clear fallback and a mirror switcher instead of a dead frame
- Native iOS beta installs via free Apple ID signing (AltStore/SideStore): 7-day expiry, refresh weekly. TestFlight and App Store links appear only when a real listing exists - never before
