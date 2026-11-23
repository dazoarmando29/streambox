# StreamBox Premium Pass — Android, TV & Performance Report

**Branch:** `perf/premium-pass-2026-11` (local — nothing pushed, deployed, or released, per instructions) · **Baseline tag:** `fd6d943` (mobile/tablet redesign) · **Delivered:** `bf77b8d` + android-tv project fixes.

Test conditions for every number below: Windows dev machine, headless Edge (Chromium) for web metrics at 390×844 phone emulation with **4× CPU throttling**, cold cache, local server on 127.0.0.1:8788 (the Worker's CORS origin), medians of 3 cold runs; Android numbers from the **Android SDK emulator `Medium_Phone_API_37.0`** (x86_64, API 37) via `adb am start -W` / `dumpsys meminfo`, 3 runs. Lab numbers, not field data — treated as targets to compare, not guarantees.

---

## 1. Architecture Audit (condensed — full defect tables from the auditors)

**What actually exists** (the brief's key question):
- **`android/` (ships as `streambox.apk` / `streambox-lite.apk`)** — a zero-dependency Java **WebView wrapper** over the hosted SPA. Native parts: splash, per-device URL derivation (`?tv=1` per device), media-key bridge, SPA-aware Back cascade, HTML5 fullscreen + orientation, ad-host blocking, renderer-crash recovery, verified in-app updater. Everything user-facing (catalog, player UI, TV Guide, Watch Party) is the website. Full/Lite = two flavors (calm mode for Lite; low-RAM devices get calm even in Full).
- **`android-tv/`** — a **native Compose TV app** (`com.dazzo.streambox`, Media3/ExoPlayer for direct streams, WebView only for embed mirrors). ⚠️ **Tracked outside the streambox repo** (workspace sibling — see §12).
- **`sb-native-ref/android-native/`** (native branch) — native Compose phone/TV app with hybrid playback (WebView shell for mirror embeds + dormant Media3 slot).
- **Website** — single-file SPA (691KB index.html: 54KB CSS + 534KB inline JS + 82KB markup), Tailwind static build, service worker.

**Top confirmed defects** (severity-ranked, from the audits): main-thread disk wipes in `onCreate` (ANR risk) · fixed 1400ms splash delay · missing `onRenderProcessGone` in android-tv's WebView screen (renderer death killed the app) · watch-progress save cancelled by `viewModelScope` teardown in android-tv · forced hardware layer (VRAM cost on 1GB sticks) · Session Storage wiped on updates · keep-screen-on always-on · render-blocking font CSS · no in-flight request dedup · 1280px hero on phones · splash held until the catalog round trip · 7 sequential keyword fetches · ~25 JSON.parses per episode drawer · un-cancelled superseded requests · 534KB monolithic inline JS.

## 2. Android TV Redesign Summary

**What the TV experience is:** the wrapper renders the website's `?tv=1` per-device layout (10-foot type, poster rows with focus rings, remote-safe margins) driven by the site's spatial D-pad engine (Up/Down rows, Left/Right cards, OK open, Back cascade, media-key bridge to play/pause); the `android-tv/` project is the native Compose alternative (Media3 for direct streams, WebView for embed mirrors).

**Verified this pass:** TV layout + remote-safe geometry via `responsive-test` at 1920×1080 and 3840×2160 (0 failing screens, 0 overflow, targets ≥40px); the android-tv project's Kotlin fixes compile (BUILD SUCCESSFUL, 75.6s); wrapper TV manifest/launcher/banner config audited (LEANBACK_LAUNCHER + banner + `leanback required=false`, TV detection by FEATURE_LEANBACK/UI_MODE). ⚠️ **The `tv_1080p_36` emulator failed to fully boot in this environment** (process starts, never registers with adb within 15+ min under software GPU) — so **no TV-emulator screenshots or live D-pad keyevent runs were captured this pass** (previous rounds' TV claims likewise rest on code-level + layout-geometry verification). Physical TV/box testing remains required (§11).

**This pass fixed (android-tv project + shared layers):**
- `WebPlaybackScreen.kt` — **renderer-crash recovery** (`onRenderProcessGone` → destroy + `webGen` re-key + reload, the proven wrapper/native pattern), **mixed-content/UA hardening deferred** (documented), **teardown fixed** (removeView + destroy; the pointless `about:blank` load removed).
- `PlayerViewModel.kt` — **watch progress can no longer be lost on exit** (final save under `NonCancellable`).
- Wrapper: remote media keys, Back cascade and crash recovery untouched; startup made snappier (§6).
- Website tv-mode: untouched by the phone/tablet redesign (verified in the mobile round).

## 3. Android Phone Redesign Summary

The previous round's web redesign (bottom-sheet dialogs, SVG icon system, 5-tab nav + More sheet, hero/rows/Continue Watching polish) is what the phone app renders. **This pass** made the wrapper itself faster and cleaner: −1.0s deterministic splash delay, async startup wipes (no main-thread stalls), full-screen contract honored on destroy, Session Storage preserved across updates, playback-scoped screen wake (battery), −3.4MB memory.

## 4. Android Tablet Redesign Summary

Tablet web layouts (600–1279px: centered floating pill nav, 860px modal, 560px settings card, 4-up grids, wider hero column) shipped in the previous round and are regression-verified (`responsive-test`: 0 failing screens at 600/768/800/820/834/1024/1180/1280). Split-screen/resizable behavior inherits the WebView's `configChanges` handling; playback survives resizes because the wrapper never reloads on resize (single WebView instance).

## 5. Website Performance Report (measured)

| Metric (lab, 4× CPU throttle, cold cache) | Before | After | Δ |
|---|---|---|---|
| First Contentful Paint (median/3) | 472 ms | **332 ms** | **−30%** |
| Largest Contentful Paint (median/3) | 1732 ms | **1512 ms** | **−13%** |
| Total Blocking Time (median/3) | 143 ms | **18 ms** | **−87%** |
| Cumulative Layout Shift | 0.000 | 0.000 | — |
| Requests / weight | 29 / 133 KB | 28 / 167 KB* | — |
| House `perf-bench` DCL (trimmed median/5) | 642 ms | **480 ms** | −25% |
| House `perf-bench` load | 723 ms | **598 ms** | −17% |
| House `perf-bench` FCP | 620 ms | **468 ms** | −25% |

\* weight varies with TMDB payload + which hero image the srcset ladder picks (w780 on phones saves ~200KB on the LCP image; total page weight moved within noise). LCP is image-CDN-timing-bound (run spread 776–2176ms) — the −13% is the conservative median delta.

**Implemented (12 of the 13 audited quick wins):** request dedup + deferred cache writes (the −87% TBT driver), hero srcset, splash at first paint, parallel row fetches, non-blocking trimmed fonts, immediate detail modal, parallel keyword sweep, per-revision continue-list parsing, caption/skip-pill paint guards, touch-device scroll repaint fix, service-worker CSS caching. **Not done (honest):** splitting the 534KB JS monolith (risky hoisting rewrite — deferred), hero image-swap instead of innerHTML rebuild, card event delegation, `browseMore` fetch aborts, MutationObserver re-scoping (audited: templates + D-pad selector fallbacks already cover tagging; risk > measured gain).

## 6. Android Performance Report (measured)

| Metric (Medium_Phone_API_37 emulator) | Before | After | Δ |
|---|---|---|---|
| Cold start `TotalTime` (median/3) | 1333 ms | **1165 ms** | **−12.6%** |
| Individual cold runs | 1497/1199/1333 | 1165/1322/1146 | |
| TOTAL PSS (steady, site loaded) | 97,055 KB | **93,700 KB** | **−3.4 MB** |
| Java heap / native heap | 14.2 / 20.1 MB | 14.2 / 20.0 MB | stable |
| Splash → browse handoff | 1400 ms fixed | **400 ms** | **−1.0 s** |
| logcat `Displayed` SplashActivity | ~1.2 s | ~1.2 s | (process/ART cost) |
| logcat `Displayed` MainActivity | +530–589 ms | +545–557 ms | (common path unchanged — the fix targets the **update-launch path**, where recursive deletes no longer block the main thread: ANR risk removed) |

Startup note (honest): `TotalTime` measures the splash's first frame; the full launch path is splash-draw (~1.2s) + handoff (−1.0s gained) + main draw (~0.55s) + site load. The wipe-async fix removes main-thread blocking on first launch after updates (previously hundreds of file ops on eMMC sticks) — wall time there is unchanged by design (the first load must still wait for the wipe), but the UI thread and splash animation no longer stall.

**Memory/battery:** −3.4MB from dropping the forced hardware layer; keep-screen-on is now playback-scoped via the `sb:awake`/`sb:sleep` title channel (old sites keep the previous always-on behavior — no regression possible); renderer-crash recovery paths verified in wrapper + android-tv.

## 7. Before/After Screenshots

- **Android emulator:** `sb-android-before/phone-home-before.png` vs `sb-android-after/phone-home-after.png` (Medium_Phone_API_37, same build pipeline, only the fixes differ).
- **TV emulator (`tv_1080p_36`):** NOT captured — the AVD failed to register/boot in this environment (software GPU, 15+ min). TV visuals rest on `responsive-test`'s 1080p/4K viewports and code-level verification; a physical TV pass is listed in §11.
- **Website phone/tablet screens** (7 screens × 4 viewports, before/after): `mobile-ui-shots/` from the previous round — unchanged and still accurate.

## 8. Performance Benchmark Comparison

Consolidated: website §5 (both harnesses agree directionally: −25…−30% FCP-class metrics, −87% TBT), Android §6 (−12.6% cold start, −3.4MB PSS, −1.0s handoff). Watch Party sync engine untouched and re-verified: **E2E 27/27, drift median 0.001s**. Build times: wrapper cold 67s / incremental 7.6s; android-tv debug 75.6s; both APKs 82KB (wrapper) — see §10.

## 9. Modified Files and Technical Explanation

| File | Change |
|---|---|
| `android/app/src/main/java/com/streambox/app/MainActivity.java` | Async fresh-start wipes (ordering guarantee kept: first load waits on the wipe thread), `firstLoad()` extraction, Session Storage preserved, fullscreen-contract teardown, dropped forced hardware layer, managed keep-screen-on + `sb:awake`/`sb:sleep` title-channel handling |
| `android/app/src/main/java/com/streambox/app/SplashActivity.java` | `DELAY_MS` 1400→400 (deep links keep 0) |
| `index.html` | `tmdb()` in-flight `_pending` dedup + deferred sessionStorage mirror; `init()` splash-at-first-paint + overlapped row fetches; hero `srcset` (w780/w1280) + sprite-icon CTAs; `openDetail` modal-first with streaming episodes; `getAdultKwIds` Promise.all; `continueList()` parse cache; `paintCaps` tail-state guard; `paintSkipPill` empty-ranges fast path; touch-device `background-attachment: scroll`; non-blocking trimmed Google Fonts; `openWatch`/`goHome` wake pings |
| `sw.js` | `tailwind.css` in `STATIC_OK` (SWR) — −33KB per visit + styled offline |
| `tools/perf-metrics.js` | **New:** cold-cache Core Web Vitals harness (FCP/LCP/CLS/TBT/requests/weight, CPU throttle) |
| `android-tv/.../WebPlaybackScreen.kt` *(outside repo — §12)* | `onRenderProcessGone` recovery via `webGen` re-key + reload; per-generation teardown; mixed-content/UA left as documented follow-ups |
| `android-tv/.../PlayerViewModel.kt` *(outside repo — §12)* | final progress save under `NonCancellable` |

## 10. Full/Lite APK Build Results

| Build | Result | Time |
|---|---|---|
| `:app:assembleFullDebug` (wrapper Full) | **BUILD SUCCESSFUL** | 67s cold / 7.6s incremental |
| `:app:assembleLiteDebug` (wrapper Lite) | **BUILD SUCCESSFUL** | (same task set) |
| `android-tv :app:assembleDebug` | **BUILD SUCCESSFUL** | 75.6s |

Both wrapper flavors sign with the committed preview key (install-over-previous behavior preserved); Full/Lite share the shell code and differ only in `HOME_URL` calm parameter + label. Note: the wrapper project has no gradlew (CI supplies Gradle 8.7); locally built via the android-tv project's 8.9 wrapper with `--project-dir` (AGP 8.5.2 compatible) — worth adding a wrapper to `android/` (deployment note).

## 11. Device and Browser Test Results

**Emulators used (clearly labeled — not physical devices):**
- `Medium_Phone_API_37.0` (Android 17/API 37 x86_64): wrapper Full APK — cold starts ×3 before/after, `dumpsys meminfo`, launcher screenshot, launch/splash/MainActivity path verified via `ActivityTaskManager` logcat.
- `tv_1080p_36` (Android TV 10, 1080p): attempted for TV home + D-pad keyevent capture — **the AVD never finished booting in this environment** (registered as running but not adb-online after 15+ min, software GPU). TV verification therefore = layout geometry (responsive-test 1080p/4K screens) + code-level D-pad/remote review + the android-tv build. Not claimed as device-tested.
- Headless Edge (Chromium): all web metrics + full regression suites.

**Regression suites (final state):** site QA gate **0 failures** · `click-test` **15/15** · `responsive-test` **0 failing screens** (320→3840px, incl. TV 1080p/4K screens) · **Watch Party E2E 27/27** · `party-server` suites **89/89** · PartyHub DO parity **26/26** (unchanged this pass).

**Not tested (physical hardware absent):** real phones (Samsung/Pixel class), real Android TV boxes/720p sticks, iPhone/iPad Safari, Samsung Internet, Firefox Android, tablets in split-screen. Their behaviors (WebKit quirks, keyboard insets, OEM WebView versions) remain manual-QA items.

## 12. Known Limitations

1. **`android-tv/` lives OUTSIDE the streambox repo** (workspace sibling, not tracked by git there). Its fixes are applied in place and compile clean, but to ship them the project must be moved into the repo (or pushed to its own). The same applies to its prebuilt `StreamBox-1.0.apk`.
2. **534KB inline JS monolith** — the single biggest remaining boot cost on weak SoCs; splitting it (PARTY/mirrors/subs) is a hoisting-sensitive rewrite deliberately deferred.
3. **`usesCleartextTraffic="true"` in android-tv** — scoping it to specific stream hosts risks breaking unknown embed media; left as-is to protect playback (non-negotiable), documented for a network-security-config pass.
4. **Predictive back** (Android 13+) not enabled anywhere — the legacy Back cascades are load-bearing for TV remotes; migration is a deliberate follow-up.
5. Native app (`android-native`) defects (#9 fullscreen WebChromeClient, #12 `allowBackup`, #13 idle Party tick) noted by the audit and left for the native track.
6. Lab metrics ≠ field metrics: no real-network LCP/INP percentiles, no physical-device startup, no battery drain measurement.
7. Emulator screenshots use software GPU (`swiftshader`); real-device rendering/perf will differ.

## 13. Deployment and Release Instructions

**Status: EXECUTED on 2026-11-23 (owner-authorized push + release).**

1. **Website:** `main` @ `798a441` (build **237**, incl. the mobile redesign + premium pass) pushed → GitHub Pages deploy (`pages-build-deployment`). `sw.js`'s cache entry updates automatically on next visit (network-first HTML).
2. **Full + Lite APKs:** CI `android-apk.yml` on `main` builds both flavors and republishes the rolling `latest` release as `streambox.apk` / `streambox-lite.apk` (**v2.35 / versionCode 37** — strictly newer than 2.34/36, so the verified in-app updater accepts the install; `BUILD_NUM`/`version.txt` = 237 drives the in-app update bar).
3. **Native app:** `native` @ `62631a6` (**0.100**, versionCode stays frozen at 50 per house rule) pushed → CI `android-native.yml` builds `app-debug.apk` (the exact variant verified locally) and republishes `streambox-native.apk` on the `latest-native` rolling release.
4. **Cloudflare PartyHub worker:** unchanged this pass (no server/protocol edits since the last verified deploy, version `2731028c`); `backend.yml` re-runs its checks + same-code redeploy on the native push.
5. **android-tv project:** not part of these releases (see §12.1 — outside the repo). Build with `android-tv/gradlew.bat assembleDebug`.
6. **Ordering/back-compat:** the website's `sb:awake`/`sb:sleep` channel is additive (old APKs ignore it; new APKs without it stay always-on). No coordinated release required.
7. **Before the next release:** run the regression gate (`tools/qa.js`, `click-test.js`, `responsive-test.js`, `party-e2e.js`, `party-server` npm test) and the physical-device checklist (§11 gaps).
