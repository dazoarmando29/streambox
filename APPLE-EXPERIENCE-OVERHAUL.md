# StreamBox - Premium Apple Experience: Implementation Ledger

Scope: the iPhone/iPad native app (`ios/`), the shared web app as experienced
on Apple devices, and the PWA. This file is the honest status of every
section of the Premium Apple Experience brief - what is IMPLEMENTED,
PARTIAL, or NOT IMPLEMENTED (with the real reason). No claims without
evidence; verification sources are named per row.

Quality benchmarks (Apple TV+, Netflix, Disney+, Infuse) informed the
interaction patterns; no branding or layouts were copied.

---

## 1. Status legend

- **DONE** - implemented, compiled/tested (source named)
- **CI** - implemented; verified by GitHub Actions macOS builds + iOS
  Simulator tests (no physical Apple device is available to this project)
- **PARTIAL** - meaningful subset implemented; gap named
- **DOC** - documented design/decision; not built (reason named)
- **N/A** - does not apply to this architecture

## 2. The three real-device bugs (iPhone report) - root causes and fixes

| Bug | Root cause | Fix | Verified |
| --- | --- | --- | --- |
| More sheet would not dismiss | Only dismissal was a backdrop tap guarded by `event.target === this`; no close button, no swipe, not in the Back cascade | Close-grabber button, swipe-down dismiss, empty-area tap, and `moreSheet` added to BOTH Back cascades (Android + iOS) | QA gate (handler resolution), cascade contract tests (CI) |
| No subtitles on iPhone (CineSrc / VidBolt / AniPM) | iOS WebKit promotes a playing video (even inside a cross-origin iframe) to a hardware overlay plane that buries plain sibling layers; `#capOverlay` was a plain layer, while the glass `#ctlBar` survived via `backdrop-filter` compositing | Every player overlay (`#capOverlay`, `#tapLayer`, `#titleCard`, `#skipFloat`, `#upNextCard`, `#volToast`, `#trailerBar`) gets its own compositing layer (`translate3d(0,0,0)` + `will-change`) - identity transform on Android/desktop | QA gate; layer promotion is the established WebKit remedy - physical-device re-check pending (see "Unverified") |
| Player controls misbehaving ("tap again, controls gone") | `#tapLayer` buried by the same promotion, so taps fell through to the provider's own iOS video controls; plus `pointerup` is cancelled whenever iOS suspects a pan/zoom, losing taps | Tap layer also promoted; tap grammar moved from `pointerup` to `click` (fires reliably for taps, never for scrolls; `touch-action: manipulation` keeps clicks instant) | QA gate (tap-layer lock re-anchored to the new handler shape) |
| "Delay when tapping stuff" (reported earlier) | `backdrop-filter: blur(18px)` glass is expensive on older iPhones | WebKit path caps blur at 9px; Reduce Transparency swaps glass for solid | CSS-only; measured feel requires a device |

## 3. Brief section-by-section

### Design system & HIG (brief 3-5)
| Item | Status | Where |
| --- | --- | --- |
| SF Pro / SF Symbols in native chrome | DONE | `SplashView`, `ErrorView`, `SettingsView`, `LockOverlay`, menu |
| Premium dark visuals, restrained gradients | DONE | brand colors preserved; Obsidian Glow palette untouched (QA lock 98) |
| Liquid Glass via genuine system materials | PARTIAL | real system materials used (`.ultraThinMaterial`, `ProgressViewStyle`) with Reduce Transparency fallbacks; iOS 26 "Liquid Glass" APIs are out of reach for an iOS 16 minimum target - the app uses their evergreen equivalents |
| Reduce Transparency / Increase Contrast | DONE | web CSS (`prefers-reduced-transparency`, `prefers-contrast`) + native materials |
| Loading skeletons / empty states | PARTIAL | native splash + error/offline states done; the web app owns in-page skeletons (unchanged) |

### Device support (brief 6)
| Item | Status |
| --- | --- |
| iPhone SE -> Pro Max, notches, Dynamic Island, home indicator, landscape safe areas | CI: universal target, fluid layout, `viewport-fit=cover` + `env(safe-area-inset-*)`, no hardcoded dimensions |
| Display Zoom, accessibility text sizes | PARTIAL: web layouts are fluid and scrollable; native chrome uses Dynamic Type fonts where system-provided (`Form`, `Label`); full Dynamic Type pass on custom native chrome needs device QA |

### iPhone layout (brief 7)
| Item | Status |
| --- | --- |
| Native-style bottom navigation | DONE (web): 5-tab bottom nav + More sheet (Home/Movies/TV/Anime/My List/More) from the mobile redesign pass |
| Thumb-reach actions, bottom sheets, hero, rows | DONE (web) |
| Long-press poster actions / swipe actions | DOC (not built; the web poster grid drives navigation and a long-press layer would fight the site's own click grammar - staged for the native-catalog phase) |

### iPad experience (brief 8)
| Item | Status |
| --- | --- |
| Adaptive tablet layouts (multi-column grids, wide hero) | DONE (web tablet layouts) |
| Multitasking: Split View / Stage Manager / resizing | CI: `UIRequiresFullScreen=false`, all orientations, single persistent WKWebView - playback survives resizes (no reload on geometry change) |
| Native sidebar + master-detail | DOC (staged; see Roadmap) |
| Pointer/trackpad + hardware keyboard | DONE: native shortcuts (Cmd+1/2/3, Cmd+R, Cmd+S, Esc), pointer works natively through WebKit |
| Side-by-side video + chat on iPad (Watch Party) | DOC (staged; the party UI is web-side) |

### Gestures (brief 9-10)
| Item | Status |
| --- | --- |
| Edge-swipe back (runs the shared Back cascade) | DONE | 
| Swipe-down dismiss (More sheet; SwiftUI sheets) | DONE |
| Pull to refresh | DONE (native `UIRefreshControl`) |
| Player tap grammar: single tap toggle bar, double-tap left -10s / right +30s | DONE (web tap layer; fixed for iOS in this pass) |
| Pinch zoom, drag-scrub, brightness/volume drags, 2x hold | DOC for embed players: those gestures need authoritative media control that cross-origin provider frames do not expose. The native AVKit path gets pinch/scrub/speed from the system. Unsupported gestures are NOT faked |
| Gesture settings | PARTIAL: tap-through toggle exists in the player bar; per-gesture switches staged with the native player phase |

### Media playback (brief 11-14)
| Item | Status |
| --- | --- |
| Native AVKit player for authorized direct media (HLS/MP4) | CI: `NativePlayer.swift` (fullscreen, resume, PiP, AirPlay, speed via system) |
| Provider embed playback (the shipping catalog) | DONE (web): same app as Android; capability matrix in section 5 |
| Subtitles/audio/quality/skip/auto-next | DONE (web features; fixed overlay rendering on iOS this pass) |
| Now Playing / remote commands | PARTIAL: `MPRemoteCommandCenter` routes play/pause/toggle to the page player (with the Android-parity pause/play guards); Lock Screen artwork/metadata for embed players is impossible (the media session belongs to the provider's frame) - only the native AVKit path shows real Now Playing metadata |
| Picture-in-Picture | PARTIAL: full for the native AVKit path (`audio` background mode); embed players use whatever their own frames expose - honestly labeled in the install center |
| AirPlay | PARTIAL: system route picker for native path; embed frames vary per provider (not guaranteed - documented) |
| Player control lock, quality selection, HDR/DV claims | DOC/limited: no fake quality advertising; lock button staged |

### Apple ecosystem (brief 18-22)
| Item | Status | Reason |
| --- | --- | --- |
| App Intents / Siri / Shortcuts | DOC | meaningful intents need a native catalog surface to act on; staged after the native catalog phase. Not faked |
| Home Screen widgets / Lock Screen widgets | DOC | WidgetKit extension target + CI time; catalog data lives in web storage the extension cannot read without an app group + native models. Staged |
| Dynamic Island / Live Activities | DOC | no honest time-bounded events yet (no downloads; party countdown is a candidate later). Not misused decoratively |
| Now Playing | PARTIAL (see playback) |
| Face ID / Touch ID app lock | DONE/CI: `AppLock.swift`, lock-on-background, opaque overlay, honest no-passcode notice |
| iCloud sync / Handoff | DOC | requires Apple-account-backed sync of watch data; must never become mandatory. Design: CloudKit private DB behind an opt-in, local-first. Not built this pass |
| Spotlight indexing | DOC | needs native models |

### Accessibility (brief 24)
| Item | Status |
| --- | --- |
| VoiceOver labels on all native chrome | DONE (accessibility labels on menu, lock, error, buttons) |
| Reduce Motion | DONE (splash animation gated; web `calm` injected) |
| Reduce Transparency / Increase Contrast | DONE (see above) |
| 44pt touch targets | DONE (native chrome); web mobile UI was already checked at >=44px (QA lock 155) |
| Non-gesture alternatives | DONE (every gesture has a button; Back cascade has the menu/About routes) |
| Dynamic Type across all screens | PARTIAL (needs device QA) |

### Settings (brief 28) & performance modes (brief 30)
| Item | Status |
| --- | --- |
| Native grouped Settings sheet | DONE/CI: `SettingsView` (Appearance & Performance, Playback, Privacy & Security, Accessibility, About) |
| Adaptive / Premium / Lite modes | DONE/CI: mode picker + Lite maps to the web `calm=1` edition live |
| Respect Low Power Mode automatically | DOC (planned: `ProcessInfo.isLowPowerModeEnabled` -> auto-Lite; trivial, staged) |
| Web settings untouched | DONE (the site's own Settings panel remains the playback/subtitle authority) |

### Performance (brief 29)
| Item | Status |
| --- | --- |
| iOS blur reduction (tap latency on older phones) | DONE (this pass) |
| Persistent store, no user-data wipes | DONE (house rule; QA-locked) |
| Memory: player teardown, no timers in native chrome | DONE |
| Instruments profiling | NOT DONE here: no Mac exists in this project. CI measures build + test only. Named as the device-phase step |

### PWA (brief 32)
| Item | Status |
| --- | --- |
| Safe areas, viewport-fit, standalone display, apple-touch icons 152/167/180 + maskable | DONE (previous pass + this one) |
| SW updates, offline shell, honest capability matrix | DONE (`install.html` comparison table; PWA limits stated) |
| iOS Safari-specific bugs (input zoom, overflow, home indicator) | DONE (viewport + safe-area audit; tap-layer/caption fixes land for the PWA too since it is the same web app) |

## 4. Test results (honest ledger)

| Check | Result | Source |
| --- | --- | --- |
| Website QA gate (~145 locks) | PASS - 0 failures, 0 warnings | local `node tools/qa.js` |
| iOS compile + unit + UI tests | **PASS - 20 unit + 2 UI tests, 0 failures** (run 45367233697; prior pass: 19+2 in run 45358602477) | GitHub Actions macOS runner + iOS Simulator |
| Android wrapper compile + APK train | CI on merge | `Build StreamBox APK` |
| Real iPhone behaviors (More sheet, captions, taps) | Fixes implemented; **physical re-test pending by the user on their iPhone** | - |
| Instruments/leaks, VoiceOver, PiP, AirPlay on hardware | NOT verified (no Mac, no device) | - |

## 5. Playback provider capability matrix (Apple devices)

| Provider | Play/Pause | Seek | Our captions overlay | Provider captions | PiP/AirPlay | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| CineSrc | full (postMessage) | full | YES (this fix) | via our chain (Wyzie/TSDB/BetaSeries) | frame-dependent | primary; coordinated |
| VidBolt | full | full | YES (this fix) | own English tracks (K-dramas) | frame-dependent | coordinated |
| AniPM | full | full | YES (this fix) | own tracks (sub/dub versions) | frame-dependent | coordinated |
| VidRift/VidZen/VidCore/etc. | per-frame | per-frame | partial (paired mirrors) | varies | frame-dependent | unpaired mirrors use their own controls |

"Do not advertise unsupported quality modes" is enforced: the UI shows only
Auto/1080/720/480 (QA lock 5) and the matrix above refuses claims the frames
do not support.

## 6. Native vs PWA matrix (also live on install.html)

| Capability | Native app | Safari PWA |
| --- | --- | --- |
| Price / expiry | Free (7-day re-sign beta) | Free forever |
| PiP / AirPlay | Full for native path; frame-dependent for embeds | Limited by Safari |
| Ad Shield | WebKit content blocker | Browser-level |
| App lock (Face ID) | Yes | No |
| Haptics / Now Playing / pull-to-refresh | Yes | No |
| Same catalog / player / parties | Yes | Yes |

## 7. Roadmap (staged, in priority order)

1. Physical-device QA of this pass (user's iPhone): captions, taps, More sheet.
2. Native catalog screens (SwiftUI) behind the same API worker -> unlocks
   real App Intents, Spotlight, widgets, long-press actions.
3. iPad sidebar + master-detail (wraps the native catalog, not the web).
4. CloudKit opt-in sync (local-first, never mandatory).
5. Instruments profiling session on a Mac (memory, startup, scroll pacing).

## 8. Changed files (this pass)

Modified web: `index.html` (overlay compositing, iOS glass budget + a11y
media queries, More sheet close paths + swipe, tap layer click grammar),
`android/app/src/main/java/com/streambox/app/MainActivity.java` (Back
cascade: moreSheet), `tools/qa.js` (tap-layer lock re-anchored).
Modified native: `ios/StreamBox/{AppModel,WebViewModel,ContentView,SplashView,
SettingsStore,Info.plist,BackCascade.js}.swift|js|plist`,
`ios/StreamBoxTests/{AdShieldAndSettingsTests,BackCascadeTests}.swift`.
New: `ios/StreamBox/{Haptics,AppLock,SettingsView}.swift`,
`APPLE-EXPERIENCE-OVERHAUL.md` (this file).
