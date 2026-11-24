# StreamBox iOS / iPadOS Expansion Report

**Ship target:** iPhone + iPad, free-first installation, honest about every
Apple constraint. **Hard rule honored throughout:** no Mac and no paid
Apple account were used - every claim below is either code in this repo, a
local QA gate result, or a GitHub Actions result. Nothing is fabricated:
no fake build successes, no fake signatures, no fake device tests, no fake
store links.

---

## 1. What was delivered

| # | Deliverable | Where |
| --- | --- | --- |
| 1 | Complete Xcode project (XcodeGen-defined) | `ios/project.yml` + `ios/StreamBox/` |
| 2 | Universal iPhone + iPad native app (SwiftUI + WKWebView shell) | `ios/StreamBox/` |
| 3 | Improved Safari PWA | `manifest.webmanifest`, `sw.js`, `index.html` head, new icons |
| 4 | Native media playback integration (AVKit, authorized direct URLs) | `ios/StreamBox/NativePlayer.swift` |
| 5 | Automated macOS GitHub Actions workflow (build + tests + artifacts) | `.github/workflows/ios.yml` |
| 6 | Signed IPA export configuration (secret-gated) | `ios.yml` + `ios/APPSTORE.md` |
| 7 | TestFlight deployment configuration (secret-gated upload) | `ios.yml` + `ios/APPSTORE.md` |
| 8 | App Store submission documentation | `ios/APPSTORE.md` |
| 9 | Universal installation center | `install.html` (linked from footer + Install buttons) |
| 10 | Device / OS compatibility matrix | section 4 below |
| 11 | Signing + installation instructions | `ios/INSTALL.md` |
| 12 | Security & privacy audit | `ios/SECURITY-AUDIT.md` + `ios/StreamBox/PrivacyInfo.xcprivacy` |
| 13 | This report | `IOS-IPADOS-EXPANSION-REPORT.md` |
| 14 | Changed files list | section 12 below |
| 15 | Test results + limitations | sections 10-11 below |

Free-vs-paid reality driving the design (the "no Mac, free" constraint):

| Route | Cost | Needs Mac? | Status |
| --- | --- | --- | --- |
| PWA via Safari Add to Home Screen | Free | No | **LIVE** - primary install |
| Native via AltStore/SideStore + free Apple ID | Free | No (Windows works) | Ready; 7-day re-sign |
| CI macOS builds + simulator tests | Free (public repo) | No | **LIVE** |
| TestFlight | USD 99/yr membership | No | Config ready, link NOT shown until real |
| App Store | USD 99/yr + review | No | Submission-ready, not submitted |
| Xcode personal signing | Free | Yes | Documented |
| Ad Hoc | USD 99/yr | No | Documented |
| EU alternative distribution | USD 99/yr + EU | No | Not applicable (PH); documented |

## 2. Repository audit summary (what already worked, reused as-is)

- `index.html`: 12,398-line single-file vanilla-JS SPA; the whole product
  (catalog, player, party, subtitles, settings). **Not rewritten** - the
  iOS app reuses it wholesale.
- Native shell contract already existed for Android and is now mirrored
  exactly on iOS: (a) `document.title` marker channel `sb:landscape` /
  `sb:portrait` / `sb:awake` / `sb:sleep`; (b) the Back cascade returning
  `'exit'`; (c) main-frame host allowlist + ad-host blocklist; (d)
  `PARTY.open(code)` deep links (`streambox://party/CODE`).
- Lite edition = `?calm=1` URL parameter (Android ships it as a flavor) -
  on iOS it is the **Lite Mode** toggle in the native menu + app reload.
- `tools/qa.js` (~140 locks) remains the gate for every change; all edits
  passed with **0 failures, 0 warnings**.
- Watch Party hub is a Cloudflare Worker + Durable Object with a shared
  protocol (`party-server/protocol.js`); WebSocket works in WKWebView
  unchanged, so **Android, iOS, desktop and TV clients stay interoperable
  by construction**.
- Workflows: `android-apk.yml` (main) and native-branch workflows were
  untouched; `ios.yml` is additive.

## 3. Native app architecture

Swift + SwiftUI + WKWebView + AVKit. **Zero third-party dependencies**
(Capacitor evaluated and rejected: it would add a Node toolchain, plugins,
and ~10 MB of runtime to wrap the same web app our 11-file Swift shell
wraps natively; the Swift shell also gives us the exact Android-parity
contracts Capacitor would fight us on).

```
StreamBoxApp (SwiftUI entry, deep links, orientation policy)
  +-- AppModel           shell state: connectivity (NWPathMonitor), marker
  |                      channel results, dialogs, share, player, splash
  +-- WebViewModel       WKWebView owner: nav allowlist (scheme-first),
  |                      title KVO -> markers, bridge, Back cascade, KVO
  |                      load progress, error surfacing
  +-- AdShield           WKContentRuleList from the shared ad-host list
  +-- NativePlayer       AVKit fullscreen + PiP + AirPlay (direct URLs only)
  +-- RemoteCommands     headset/Bluetooth transport -> page player JS
  +-- SettingsStore      UserDefaults (privacy manifest CA92.1)
  +-- SplashView/ErrorView/OfflineBanner/JSDialogView/ActivityView
```

Native features beyond "website wrapper": native splash (hides WKWebView
flash), native error/offline states with auto-recovery on reconnect,
system share sheet, native alert/confirm/prompt for page dialogs, edge-swipe
Back + hardware keyboard shortcuts (Esc/⌘R/⌘S), orientation lock driven by
the page's cinema mode, keep-screen-awake channel, PiP/AirPlay-capable
AVPlayer path, ad blocking at the WebKit layer, deep links, Lite Mode,
persistent user data across updates (never wipes localStorage).

## 4. Device & OS compatibility matrix

| Device class | Example | Status | Notes |
| --- | --- | --- | --- |
| Small iPhone | iPhone SE (2nd/3rd gen) | Supported (iOS 16+) | Web app's compact layouts; 44pt targets |
| Standard iPhone | iPhone 15/16/17 | Supported | all orientations except upside-down |
| Large iPhone | iPhone Pro Max (incl. future 18 Pro Max) | Supported | no hardcoded dimensions; fluid layout + `viewport-fit=cover` |
| Dynamic Island / notch | iPhone 14 Pro+ | Supported | `black-translucent` status bar + safe-area env() padding |
| iPad / iPad Air / mini | all | Supported | all 4 orientations, Split View / Stage Manager ready (`UIRequiresFullScreen=false`) |
| iPad Pro | 11"/13" | Supported | fluid poster grid; pointer/trackpad + hardware keyboard |
| High refresh | ProMotion | Enabled | `CADisableMinimumFrameDurationOnPhone` |

| OS | Status |
| --- | --- |
| iOS/iPadOS 16.x | Minimum target (all used APIs are 16-safe; geometry updates use the iOS 16 API) |
| iOS/iPadOS 17.x | Supported |
| iOS/iPadOS 18.x / 26.x-era SDK | Supported (built with Xcode 16 SDK; App Store single-size icon) |

Feature detection notes: orientation geometry requests use the iOS 16
`UIWindowScene.requestGeometryUpdate`; `#available` guards cover older
paths. Nothing requires the newest hardware.

## 5. PWA improvements (the free flagship install)

- `apple-touch-icon` set: new 180/167/152 PNGs (was a single 192 that iOS
  rescales) + existing 192/512 kept.
- New `icon-1024.png` (manifest "any") and `icon-1024-maskable.png`
  (true maskable: content inside the safe circle on brand background).
- `manifest.webmanifest`: added `id`, 1024 icons, maskable entry (parses
  clean under QA lock 14).
- `sw.js`: cache `streambox-v2` -> `streambox-v3`, new icons precached in
  the shell and cache-first list (QA lock 131 shape preserved exactly:
  every `c.put` still rides `e.waitUntil`).
- Head: multi-size `apple-touch-icon` links; existing iOS meta
  (`apple-mobile-web-app-capable`, `black-translucent`, `viewport-fit=cover`)
  verified present and correct.
- Safe areas: already handled (`env(safe-area-inset-*)` + `viewport-fit=cover`)
  - audited, unchanged, verified in the mobile UI pass.
- Update flow: existing network-first `version.txt` polling + update banner
  retained (fresh HTML every visit; `version.txt` never cached).
- Footer now links **Install** and **Privacy**; the More-sheet Install door
  is now always visible and falls back to the install center where
  `beforeinstallprompt` does not exist (iOS Safari).

iOS-specific install guide with the exact 6 steps (Share -> Add to Home
Screen -> Open as Web App if offered -> Add -> launch) lives at
`install.html` and `ios/INSTALL.md`, including honest limitations (PiP/
AirPlay restricted in Safari, offline = shell only, some providers do not
support iOS browsers - shown as clear fallbacks).

## 6. Media playback

- **Primary path (shipping today):** provider embed iframes inside WebKit,
  exactly as Android and the website. Fullscreen, seeking, subtitles,
  auto-next, Skip Intro, Up Next - all page features - work because it is
  the same app. iOS WebKit gives HTML5 video native fullscreen, and the
  `sb:landscape` marker rotates into cinema mode like on Android.
- **Native AVPlayer path:** implemented (AVPlayerViewController with PiP +
  AirPlay + playback speed via system controls) and gated to direct,
  authorized media URLs (https + `m3u8/mp4/m4v/mov/aac`). The shipping
  catalog does not use it yet - **honest statement**: today's providers
  serve embeds whose authorization lives with them; the page can hand over
  direct URLs (e.g. licensed/public-domain streams) via
  `window.SB_NATIVE.playNative(url)` and they play natively.
- No DRM bypass, no embed restriction bypass, no geo/auth circumvention
  anywhere (audit: `ios/SECURITY-AUDIT.md`).
- Interruption handling: WKWebView resumes with the page's own state; the
  shell recovers from network loss automatically; remote commands route to
  the page's transport functions with the same pause/play guards as
  Android.

## 7. Watch Party on Apple devices

Audited: the party hub is a WebSocket service; WKWebView supports
WebSocket fully. The iOS shell: preserves the socket across orientation
changes and menus (single persistent WKWebView), re-runs `PARTY.open(code)`
for `streambox://party/CODE` invites (sanitized), and on foregrounding the
page's existing reconnect logic (v2 resume tokens, ghost replacement) runs
as designed. Backgrounding suspends the socket like any iOS app; the room
survives via the hub's resume-token flow. Cross-client compatibility holds
because all clients run the same web client and protocol. Honest limit:
sync quality is bound to what the playing provider's iframe exposes - same
limit as Android/desktop, documented in the party reports.

## 8. Updates

| Channel | Mechanism |
| --- | --- |
| PWA | network-first `version.txt` + update banner + pull-to-refresh reality (existing, tested) |
| Native (AltStore free signing) | reinstall newer IPA; data survives (persistent web storage; shell never wipes) |
| Native (TestFlight) | official TestFlight updates (when membership exists) |
| Native (App Store) | official store updates (when listed) |

No executable code is ever downloaded outside Apple's update channels
(policy + platform requirement). The Android self-update flow is untouched.

## 9. Security & privacy (summary - full: `ios/SECURITY-AUDIT.md`)

Scheme-first navigation gate + host allowlist (mirror of the Android rules
that QA lock 125 pins); subframes allowed over http(s) only (players need
it); ad-tech domains blocked via WKContentRuleList (~60 hosts, shared list,
unit-tested); strict ATS with **no** TLS exceptions (Android has one for a
broken-chain mirror; iOS refuses to weaken TLS and fails honestly there);
JS bridge is outbound-only with re-validated verbs; no API keys in the
binary; `PrivacyInfo.xcprivacy` declares zero tracking, zero collection,
UserDefaults CA92.1; privacy policy published at `privacy.html`.

## 10. Test results (honest ledger)

Verified in this environment (Windows; no Mac):

| Check | Result | How |
| --- | --- | --- |
| Website QA gate (~140 locks) | **PASS - 0 failures, 0 warnings** | `node tools/qa.js` |
| sw.js + tools syntax | PASS | `node --check` |
| iOS version sync | PASS (238 == version.txt) | `node tools/sync-ios-version.js --check` |
| manifest parses | PASS | QA lock 14 |
| Workflow YAML ASCII | PASS | QA lock 13 |
| PWA/iOS icons generated | PASS (180/152/167/1024/maskable/AppIcon) | System.Drawing, brand art preserved |
| Web edits regression | PASS (QA locks on footer/About/install/sw preserved) | QA gate |

Verified on GitHub Actions macOS runners (real `xcodebuild`, real iOS
Simulator) - workflow run 45358602477, branch `ios-expansion`:

| Check | Result | Where verified |
| --- | --- | --- |
| Project generation (XcodeGen) | PASS | CI "Generate project" |
| App compiles for iOS Simulator | PASS (3 CI iterations to green; compile errors fixed honestly as found) | CI "Build (iOS Simulator, unsigned)" |
| Unit tests (config/allowlist/cascade/rule/settings) | **PASS - 19 tests, 0 failures** | CI "Unit + UI tests" |
| UI smoke tests (boot, WKWebView hosted, native menu) | **PASS - 2 tests, 0 failures** | CI "Unit + UI tests" (iPhone simulator) |
| Unsigned device build + .ipa payload | PASS | CI "Build unsigned device payload" |
| Artifact honesty metadata | PASS (declares simulator-only / re-sign-required / signed-absent) | CI "Write install metadata" |
| Artifacts uploaded | `streambox-ios-simulator`, `streambox-ios-unsigned-device` (+ metadata) | run 45358602477 |
| Signed export + TestFlight steps | Correctly SKIPPED (no secrets configured) | run 45358602477 |

NOT verified (impossible without hardware/accounts - explicitly unclaimed):

- Physical iPhone/iPad runs (no device available here).
- VoiceOver / Reduce Motion on hardware (simulator does not exercise them).
- Real Picture-in-Picture / AirPlay behavior on hardware.
- AltStore/SideStore install flow end-to-end (needs a device + Apple ID).
- TestFlight / App Store distribution (no membership).
- Provider playback breadth on iOS (each embed must be checked per-title on
  a real device; the app shows honest fallbacks where a provider lacks iOS
  support).

## 11. Known limitations

1. **iOS provider playback**: some mirror players ship desktop-only or
   WebRTC features that mobile Safari/WebKit cannot run. The app surfaces a
   clear fallback ("switch server") rather than a dead frame.
2. **No TLS exception for vidcore.org** (Android makes one): if that
   mirror's chain is broken on iOS it will simply not work there. Chosen
   deliberately (no weakened security).
3. **Free-signing expiry**: 7-day re-sign for the native beta is Apple's
   rule for free accounts.
4. **PWA PiP/AirPlay** are limited by Safari - the install center says so.
5. **Safari page-tab UX**: the PWA runs its own window only when added via
   Add to Home Screen; plain Safari keeps tabs.
6. The What's New/updates for the native app rely on reinstall (no in-app
   native updater on iOS - Apple forbids sideloaded update flows; TestFlight
   solves this when the membership exists).

## 12. Changed files

New:
- `ios/project.yml`, `ios/Makefile`, `ios/README.md`, `ios/INSTALL.md`,
  `ios/APPSTORE.md`, `ios/SECURITY-AUDIT.md`, `ios/TVOS-EVALUATION.md`
- `ios/StreamBox/`: `StreamBoxApp.swift`, `AppModel.swift`, `AppConfig.swift`,
  `WebViewModel.swift`, `ContentView.swift`, `SplashView.swift`,
  `NativePlayer.swift`, `ShareSheet.swift`, `AdShield.swift`,
  `RemoteCommands.swift`, `SettingsStore.swift`, `BackCascade.js`,
  `NativeBridge.js`, `Info.plist`, `PrivacyInfo.xcprivacy`,
  `Assets.xcassets/` (AppIcon 1024, LaunchBackground color, catalog meta)
- `ios/StreamBoxTests/`: `AppConfigTests.swift`, `BackCascadeTests.swift`,
  `AdShieldAndSettingsTests.swift`
- `ios/StreamBoxUITests/SmokeTests.swift`
- `.github/workflows/ios.yml`
- `tools/sync-ios-version.js`
- `install.html`, `privacy.html`
- `icon-152.png`, `icon-167.png`, `icon-180.png`, `icon-1024.png`,
  `icon-1024-maskable.png`
- `IOS-IPADOS-EXPANSION-REPORT.md` (this file)

Modified:
- `index.html` (multi-size apple-touch-icons, footer Install/Privacy links,
  Install buttons -> install-center fallback, BUILD_NUM 237 -> 238)
- `manifest.webmanifest` (id + 1024 + real maskable icon)
- `sw.js` (cache v3, new icons in shell + cache-first list)
- `version.txt` (237 -> 238)
- `android/app/build.gradle` (2.35 -> 2.36, versionCode 37 -> 38)
- `release-notes/android.md` (2.36 notes)

Untouched (regression-checked by the QA gate): the entire player, party,
subtitle, catalog code in `index.html`; `android/` wrapper logic;
`party-server/`; native-branch workflows.

## 13. Release / verification trail

- QA gate green before every push (this repo's `tools/qa.js`).
- iOS: `Build StreamBox iOS` workflow on GitHub Actions
  (https://github.com/dazoarmando29/streambox/actions/workflows/ios.yml) -
  green run 45358602477 (19 unit + 2 UI tests, 0 failures, artifacts up).
- APK train: existing `Build StreamBox APK` workflow republishes the
  rolling `latest` release as `apk-v2.36`.
- Website: served from `main` at https://dazoarmando29.github.io/streambox/
  (build 238); install center at .../streambox/install.html.
