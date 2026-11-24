# StreamBox for iPhone + iPad

Native (Swift / SwiftUI / WKWebView) shell around the StreamBox web app,
with genuine native integration: native splash, secure JS<->Swift bridge,
system share sheet, AVPlayer path for authorized direct media, Ad Shield
content blocking, orientation + keep-awake marker channel, deep links
(`streambox://party/CODE`), offline/error states, and persistent settings.

No third-party dependencies. No Capacitor. Swift + SwiftUI + WebKit + AVKit only.

## Requirements

- macOS with Xcode 16+ (only for building; users do not need any of this)
- [XcodeGen](https://github.com/yonaskolb/XcodeGen): `brew install xcodegen`

## Build

```sh
cd ios
make project     # xcodegen generate -> StreamBox.xcodeproj
make build       # Debug build for the iOS Simulator (unsigned)
make test        # unit + UI tests on an iPhone simulator
make ipa         # unsigned device payload for AltStore/SideStore re-signing
```

Or open `StreamBox.xcodeproj` after `make project` and press Run.

## What is where

| Path | Purpose |
| --- | --- |
| `project.yml` | Xcode project definition (source of truth) |
| `StreamBox/StreamBoxApp.swift` | App entry + orientation policy hook |
| `StreamBox/AppModel.swift` | Shell state: connectivity, markers, deep links, dialogs |
| `StreamBox/WebViewModel.swift` | WKWebView owner: allowlist, bridge, back cascade |
| `StreamBox/AppConfig.swift` | Shared contract: hosts, ad hosts, markers, media gate |
| `StreamBox/BackCascade.js` | iOS port of the Android Back cascade (QA-locked order) |
| `StreamBox/NativeBridge.js` | `window.SB_NATIVE` (share / playNative) |
| `StreamBox/AdShield.swift` | WKContentRuleList ad blocking (same hosts as Android) |
| `StreamBox/NativePlayer.swift` | AVKit playback for authorized direct media URLs |
| `StreamBox/SettingsStore.swift` | UserDefaults settings (privacy manifest CA92.1) |
| `StreamBox/PrivacyInfo.xcprivacy` | Privacy manifest (no tracking, no collection) |
| `StreamBoxTests/` | Contract tests (config, allowlist, cascade order, rules) |
| `StreamBoxUITests/` | Boot smoke tests |
| `INSTALL.md` | Every install path, free-first, with honest limits |
| `APPSTORE.md` | App Store / TestFlight submission preparation |
| `SECURITY-AUDIT.md` | Threat model and audit results |
| `TVOS-EVALUATION.md` | Apple TV feasibility notes (kept out of this release) |

## Versioning

- `MARKETING_VERSION` in `project.yml` follows the app release train (2.36).
- `CURRENT_PROJECT_VERSION` must equal the repo-root `version.txt` (web build
  number). CI enforces this via `node tools/sync-ios-version.js --check`.
- Update with `node tools/sync-ios-version.js --write`.

## Signing

The project builds with **no** team configured (free Apple ID friendly).
See `INSTALL.md` for what each signing mode can and cannot do.
