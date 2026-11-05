# StreamBox — Full Codebase Audit & Stabilization Report

**Date:** 2026-11-05 · **Auditor:** opencode (mimo-v2.6-pro) · **Repo:** dazoarmando29/streambox

## Scope & branch topology (read this first)

The repo carries three branches that have **diverged**:

| Branch | Content | Status |
|---|---|---|
| `main` | Website (BUILD 213→214) + WebView wrapper `android/` (full/lite flavors) + `tools/qa.js` (124 checks) | **Production** — deploys to GitHub Pages, publishes `streambox.apk`/`streambox-lite.apk` |
| `native` | Native Compose app `android-native/` + Cloudflare Worker `backend/` + a **stale website copy (179)** and stale wrapper | Preview track (`streambox-native.apk`, `latest-native` release) |
| `lite` | Website build 51 | **Stale** (Oct 8) — superseded by the lite *flavor* of `android/` |

The audit and fixes were made on two safe working branches (local only — **nothing pushed, merged, or released**):

- **`audit/stability-2026-11`** (from `main`, in `cinesrc-website/`) — website + wrapper + CI + QA gate. 5 commits.
- **`audit/stability-native`** (from `native`, in `sb-native-ref/` worktree) — Worker + native CI. 1 commit.

All findings are labeled **CONFIRMED** (root cause proven in code, fix implemented & verified), **RISK** (real exposure, not exploited/not fixed), or **NIT**.

---

## A. Executive Summary

**Overall health:** The codebase is unusually well-guarded for its size (132 QA invariants, deliberate house rules, defensive naming), but the audit found real defects at every layer: a **user-data-wiping update flow** (Priority 0), an **API-level crash** on Android 7.x, a **Back-button deadlock** in the wrapper, an **endless mirror ping-pong loop** in the player, **cache poisoning** of the catalog, **stored/reflected XSS** sinks, broken anime pagination, and **release-pipeline failure modes** that could ship stale APKs green or destroy the native update channel.

**Major risks remaining** (details in J):
1. Third-party embed players are opaque by design (cross-origin); failures inside them can only be observed, not fixed.
2. The `native` track carries a **stale fork** of the website/wrapper (179 vs 214) — divergence risk.
3. No device/emulator runtime testing was possible in this environment (honest limits in I).

**Most important improvements:** (1) app updates no longer erase Continue Watching/My List/PIN/settings; (2) the wrapper no longer crashes on Android 7.x or deadlocks Back; (3) the player's auto-fallback can no longer loop; (4) both release pipelines now fail loudly instead of lying green; (5) all identified XSS sinks are escaped; (6) every build signs with one stable key so updates install in place.

---

## B. Confirmed Bugs (all fixed unless noted)

### P0 — Critical

| # | Bug | Platform | Location | Root cause | Fix | Verified |
|---|---|---|---|---|---|---|
| B-1 | **App updates wipe all user data.** Continue Watching, My List, adult PIN, subtitle prefs, personal Wyzie key vanished after every APK update | Android phone + TV | `android/.../MainActivity.java` `freshStartIfNewVersion()` (old :261) | The per-version "fresh start" deleted `app_webview/` **wholesale** — that directory holds Local Storage/IndexedDB/Cookies, i.e. the entire web app state | Wipe now touches only `Cache`, `Code Cache`, `GPUCache`, `Service Worker`, `Session Storage`, `getCacheDir()`; user data always survives. Stale-page guarantee kept (Service Worker registration+CacheStorage still cleared) | Compiled in both flavors; locked by QA 126 |
| B-2 | **Crash exiting fullscreen video on Android 7.0/7.1** | Android phone + TV | `MainActivity.java` old :293 | `WebView.getWebChromeClient()` is API 26; `minSdk 24` → `NoSuchMethodError` | Chrome client kept in a field; `getWebChromeClient()` never called | Compiled; QA 125 |
| B-3 | **Back press swallowed on any JS error** (user could not exit the app) | Android phone + TV | `MainActivity.java` `onBackPressed` IIFE | Site and APK are version-decoupled; one missing page function made `evaluateJavascript` return `null`, which matched no known result → Back dead | IIFE wrapped in try/catch returning `exit`; `null`/`"null"`/empty results fail safe to `exit` | QA 125 (gate present) |

### P1 — High

| # | Bug | Platform | Location | Root cause | Fix | Verified |
|---|---|---|---|---|---|---|
| B-4 | **Mirror ping-pong loop:** dead title hopped AniPM↔VidBolt↔YapGrid forever (~25–45s cycle) | All | `index.html` `armStuckWatchdogs` stage-2/3, `anipmArmStartRetry`, `boltArmStartRetry` | "Heal-once" guard `window._autoHopped` was checked in some hop sites only; the unguarded ones chained hops and reset nothing | Every auto-hop site now takes the one-hop heal budget (recommend instead of chaining) | QA 130 |
| B-5 | **Player reloads in hidden view after Back** (background audio, phantom watchdogs) | All | `index.html` `goHome`, retry callbacks | Retry/kick/hello/quality timers checked only `mirror`+episode key — both unchanged after `goHome` | `goHome` clears every clock (`_qT/_playKickT/_boltRetryT/_anipmRetryT/_anipmKickT/_anipmHelloTo/_tapT/...`); retry callbacks bail when `#watchView` is hidden | QA (regression suite green), code-reviewed |
| B-6 | **Catalog cache poisoning:** transient 502 JSON cached as catalog data for 10–30 min (blank rows) | Web + APK | `index.html` `tmdb()` :1601, `feedFetchJson()` | `r.json()` cached without `r.ok` check | Non-OK throws before any cache write; `_mem` got a 48-entry FIFO cap (was unbounded) | QA 128 |
| B-7 | **Reflected/stored XSS** via TMDB/free-text metadata | All | hero overview/title, Top 10 + card titles/meta, Continue row titles + poster URLs, episode drawer names/synopses/stills, modal genres, `posterTag` attribute breakout | `escapeHtml` existed but was applied to 3 sinks only | All listed sinks escaped; image paths strip quote/bracket chars | QA 129 + code-reviewed |
| B-8 | **Anime "Load More" walked in place or duplicated forever** | Web | `index.html` `browseMore` + `backend/src/index.js` `animeBrowse` | Worker ignored `page` (always page 1) and client appended without dedupe, faking `total_pages` | Worker pages AniList/Kitsu for real with provider `hasNext`; client dedupes against the grid and stops when a page adds nothing new | Worker: `node --check` + QA; client: QA. Runtime pagination needs a live run |
| B-9 | **`latest-native` release could be destroyed by a flaky publish** (delete-then-create; updater channel dies) | Native TV/phone | `.github/workflows/android-native.yml` | `gh release delete` before `gh release create`; delete OK + 5 failed creates = permanent loss | Create-or-update with `--clobber` (no delete), `published` flag exits 1 on total failure, `--target $GITHUB_SHA`, empty-`VN` guard, concurrency group | YAML reviewed; QA 85 (native) green |
| B-10 | **Green job published stale APKs** under notes claiming the new SHA | Release pipeline | `.github/workflows/android-apk.yml` | Edit succeeded + upload failed 5× → loop ended, final `gh release view` passed | `published` flag + `exit 1`; concurrency group; create pins tag at built SHA | QA 127 |
| B-11 | **Updates never installed over previous APKs** (each CI run re-signed with a fresh runner debug key) | Android phone + TV | `android/app/build.gradle` | No `signingConfig` | Committed `android/preview.keystore` wired into debug+release (same stable key every build, mirroring the native track's rule) | Built locally: `apksigner` shows `CN=StreamBox Preview` on both flavors; QA 132 |

### P2 — Medium

| # | Bug | Location | Fix | Verified |
|---|---|---|---|---|
| B-12 | Stale responses corrupted browse grid on filter change (old category cards/`page` written into new session) | `index.html` `browseMore` + 5 reset sites | Request token; resets clear `loading` | QA green |
| B-13 | Subtitle auto-load races attached the previous episode's file and persisted it under the new episode's key | `wyzAutoSubs`, `bsAuto` | Storage-key re-check after every await | QA green |
| B-14 | Stale intro/recap/outro ranges labeled the wrong episode's skip marks | `fetchIntroRanges` | Episode-key re-check after fetch | QA green |
| B-15 | Slow detail fetch resurrected the player after Back / overwrote a newer title | `openWatch` | Sequence token (`owKey`/`stillMine`) around both awaits | QA green |
| B-16 | Deep-link `id` stayed a string → duplicate Continue/My List rows; deep-link boot left Home empty | `index.html` bootstrap + `openWatch` | Numeric coercion + `init()` alongside `openWatch()` | QA green |
| B-17 | Esc/Back couldn't dismiss the resume dialog (stuck open, body scroll unlocked) | `index.html` Esc chain + `closePanels` | `resumeDlg` gets `resumePick(true)` (parity with APK Back) and counts in `modal-open` | QA green |
| B-18 | My List rendered adult titles behind the locked 18+ gate | `renderList` + 3 save sites | Adult filter + stored `overview` for accurate heuristic | QA green |
| B-19 | Hero rotation stacked one `setInterval` per `init()` run (Retry / lock toggles) | `buildHero` | Single hoisted timer, cleared before re-arm | QA green |
| B-20 | Sleep timer silently dead on VidRift/VidZen/VidBolt | 3 progress handlers | `sleepTick()` in each | QA green |
| B-21 | `/api/anime-map` cache key omitted the reflected `tmdb` param (24h cross-user field bleed) | `backend/src/index.js` | `tmdb` digit-validated and keyed | `node --check` + QA |
| B-22 | Worker 502s dropped the request origin (allowed localhost dev got opaque errors) | `backend/src/index.js` top-level catch | Origin passed through | `node --check` + QA |
| B-23 | `cachePut` never received `ctx` → `caches.default.put` un-awaited (silent cache drops, unhandled rejections) | `backend/src/index.js` | `ctx` threaded through `route()` into all 3 writers | `node --check` + QA |
| B-24 | Main-frame URL allowlist had **no scheme gate** — `intent://youtu.be/#Intent...` passed the host check and could launch other apps | `MainActivity.java` `shouldOverrideUrlLoading` | http(s) scheme gate before the allowlist (parity with subframe branch) | QA 125 |
| B-25 | `CustomViewCallback.onCustomViewHidden()` never invoked on app-initiated fullscreen exit (media stuck logically fullscreen) | `MainActivity.java` | Contract honored in the Back path | QA 125 |
| B-26 | Renderer-process death killed the whole app (realistic on 1GB TV sticks) | `MainActivity.java` | `onRenderProcessGone` → destroy + `recreate()` (with blank-restore guard) | QA 125 |
| B-27 | Splash fired `startActivity` while backgrounded (dropped launches on Android 10+) | `SplashActivity.java` | Countdown arms in `onResume`, cancels in `onPause`, `handedOff` guard | Compiled |
| B-28 | sw.js cache writes floated (killed mid-flight), error responses cached, cache-busted `version.txt` polls cached forever (unbounded growth), offline fallback could resolve `undefined` | `sw.js` | `e.waitUntil` on every put, `res.ok` gate, non-shell GETs pass through uncached, total offline fallback | QA 131 |
| B-29 | TMDB cache keys split on query order; `removeContinue`/`paintSubsUI` crashed on corrupted/legacy stored data; recent-search chips raced the debounce timer; `init()` double-run interleaved rows; CineSrc bridge lacked the stale-frame guard the other 4 bridges had; episode loaders applied stale season responses | various in `index.html` | All fixed (sorted keys, try/guards, token per loader) | QA green |

---

## C. Performance Improvements

| Change | Why | Impact |
|---|---|---|
| `_mem` capped at 48 FIFO entries (`index.html:1601`) | Detail+credits payloads are 20–100KB and were retained all session | Bounded memory in long sessions |
| `sb_sub_*` caches pruned to 8 (live in `setSubs`, at boot) | 100–300KB cue arrays per episode silently blew the localStorage quota — after which Continue/My List writes died too | Quota safety on binges |
| `sb:srv*` server memory capped at 40 at boot | One entry per watched title, forever | Bounded storage |
| sessionStorage response cache swept at boot (expired entries) | Long-lived TV tabs accumulate | Bounded storage |
| Hero rotation: exactly one interval | N stacked intervals repainted the hero N× per tick | Removes periodic jank after Retry/lock toggles |
| sw.js: version.txt polls never cached | Each 30-min poll cached a new URL forever | Removes slow unbounded cache growth; update banner always fresh |
| sw.js cache writes awaited (`waitUntil`) | Writes were being dropped → extra network on repeat loads | Better cache hit rate |
| Browse grid: stale responses dropped, cards deduped | Re-render + duplicate DOM from racing fetches | Smoother filtering, honest Load More |
| Interactive detail fetches: 15s timeout | Hung connections left spinners forever | Faster failure feedback |
| CineSrc bridge stale-frame guard | Switched-away frames mutated `pState` and repainted | Fewer wasted repaints during mirror switches |

*Measured impact note:* no browser/device benchmarking was possible in this environment (see I) — impacts above are reasoned from code behavior, not measured numbers.

---

## D. Android Phone Improvements

- Data-loss fix (B-1) — the single biggest phone/TV win: **updates now preserve watch progress, My List, PIN, settings, subtitle memory**.
- Crash fixes: B-2 (Android 7.x fullscreen exit), B-26 (renderer death now recovers), B-3 (Back deadlock).
- Security: B-24 scheme gate, `setAllowContentAccess(false)`, `MainActivity exported=false`.
- Stable signing (B-11): updates install over previous builds (one manual uninstall needed only to leave the old ephemeral-key builds).
- `versionName 2.13` (versionCode frozen at 23 — any build installs over any other, rollback-friendly, native-track house rule).
- Remaining limitation: `allowBackup=true` kept intentionally (it aids data restore across reinstalls; adb-backup extraction requires physical access) — see G.

## E. Android TV Improvements

- All phone fixes apply (TV shares the wrapper + site).
- Renderer-death recovery (B-26) is targeted at low-RAM TV boxes under memory pressure.
- Proper 320×180dp leanback banner (`drawable-xhdpi/tv_banner.png`, was a square 512×512 icon).
- Player loop fix (B-4) matters most on TV: the AniPM/VidBolt auto-hop chains were TV-mode paths (`boltArmStartRetry` is TV-only).
- Timer cleanup (B-5) prevents background audio after Back on the TV remote.
- `android-native.yml` release-loss fix (B-9) protects the TV-native update channel.
- TV D-pad behavior is implemented web-side (spatial nav engine, `data-focusable` tagging) and was **code-reviewed only** — no TV device available (see I).

## F. Streaming and Subtitle Improvements

- **Fallback system:** one auto-hop per title (heal-once) enforced on *every* hop path (mid-play rescue, AniPM watchdog stages 2–3, VidBolt/VidZen watchdog, both start-retry fallbacks). Manual Source picks reset the budget. Result: bounded fallback, no loops, clear status text ("moved you to X" / recommendation when the budget is spent). Preserves the Smart Source design (auto-switch preference, last-known-good).
- **Subtitles:** Wyzie→TSDB→BetaSeries cascade untouched (no source disabled); races fixed (B-13/B-14) so episode switches can no longer attach/persist the wrong file; quota behavior and per-title memory preserved; storage now bounded (C).
- **Third-party limitations (unchanged, by design):** cross-origin embeds can't be introspected; a "dead" player may be a robot-check or regional block, not a bug in StreamBox. The code already treats unknown state carefully (patience clocks, no DRM/access-control bypass attempted).

## G. Security Findings

| Finding | Severity | Status |
|---|---|---|
| XSS sinks (B-7): TMDB free-text → innerHTML | High | **Fixed** — all identified sinks escaped |
| `intent://` scheme escape via host-look-alike URLs (B-24) | Medium | **Fixed** — http(s) gate |
| `CustomViewCallback` contract violation (B-25) | Low | **Fixed** |
| Worker `anime-map` cross-user `tmdb` bleed (B-21) | Medium | **Fixed** |
| `MainActivity exported=true` without intent filter | Low | **Fixed** (`exported=false`) |
| `setAllowContentAccess` default-on | Low | **Fixed** (disabled) |
| TMDB API key: **gone from the website** — server-side Wrangler secret via `streambox-api` Worker; no keys/shards found in `index.html`, `backend/`, or compiled native classes | — | Clean (verified by grep + agent sweeps) |
| SSL `proceed()` for `*.vidcore.org` chain-trust errors (`MainActivity.java:194-213`) | Medium (accepted) | **RISK, not fixed** — deliberate compatibility workaround, scoped to `SSL_UNTRUSTED` on that host only; documented. Narrowing to exact host or shipping a network-security-config pin is a recommended next step |
| `allowBackup=true` (WebView profile backup-able via adb on old devices) | Low | **RISK, accepted** — aids data preservation across reinstalls |
| Committed preview keystore + password in `build.gradle` (now for both tracks) | Low | **Accepted by design** (native track precedent, QA-enforced): sideload key, not production; the in-app updater verifies SHA-256 digests, and release notes say so. Recommendation: rotate to a base64 repo secret when a production channel exists |
| PIN default hash is a public SHA-256; 6-digit space offline-brute-forceable | Low | **RISK** — kid-lock strength only; noted in code comments |
| GitHub Actions: mutable action tags, no environment approval on deploy | Low | **Noted** (R) — `permissions` now minimal on all workflows; pinning SHAs recommended |
| Cache poisoning via error bodies (B-6) | Medium | **Fixed** |

No secrets were printed in this report or the commits.

## H. Files Modified

**Branch `audit/stability-2026-11`** (from `main`):
| File | Changes |
|---|---|
| `android/app/src/main/java/com/streambox/app/MainActivity.java` | B-1 wipe narrowed to cache layers; B-2 chrome-client field; B-3 Back fail-safe; B-24 scheme gate; B-25 `onCustomViewHidden`; B-26 `onRenderProcessGone` + `webviewCrashed` restore guard; `setAllowContentAccess(false)`; `hideFullscreen()` helper |
| `android/app/src/main/java/com/streambox/app/SplashActivity.java` | B-27 visible-only countdown |
| `android/app/src/main/AndroidManifest.xml` | `MainActivity exported=false`; banner → `@drawable/tv_banner` |
| `android/app/src/main/res/drawable-xhdpi/tv_banner.png` | **new** 640×360 (320×180dp) leanback banner |
| `android/preview.keystore` | **new** stable signing key (B-11) |
| `android/app/build.gradle` | `signingConfigs.preview`; versionName 2.13 (versionCode frozen 23) |
| `sw.js` | B-28 rewrite (waitUntil puts, res.ok gate, pass-through non-shell GETs, total offline fallback) |
| `.github/workflows/android-apk.yml` | B-10 publish `published` guard + `exit 1`; concurrency; `--target` on create; updated release notes |
| `index.html` | B-4/5/6/7/12–20/29 fixes (see B/C), build 214 |
| `version.txt` | 214 |
| `tools/qa.js` | **+8 checks** (125–132) locking the above |

**Branch `audit/stability-native`** (from `native`):
| File | Changes |
|---|---|
| `.github/workflows/android-native.yml` | B-9 edit-or-create publish, `published` guard, `--target`, VN guard, concurrency |
| `.github/workflows/backend.yml` | Read-only `permissions`, undeploy warning |
| `backend/src/index.js` | B-8 real paging + `hasNext`; B-21 cache key; B-22 origin; B-23 `ctx` threading; sorted cache keys |

Also (follow-up commit on `audit/stability-2026-11`): `index.html` browse uses the Worker's `hasNext`.

## I. Test Results

| Test | Result |
|---|---|
| `node tools/qa.js` (main track, **132 checks** incl. 8 new) | **PASS** — 0 failures, 0 warnings |
| `node tools/qa.js` (native track, 85 checks) | **PASS** — 0 failures, 0 warnings |
| `node --check` on inline scripts + `sw.js` (QA section 1) | **PASS** |
| `node --check backend/src/index.js` | **PASS** |
| `gradle :app:assembleDebug` (both flavors, JDK 17, SDK 34, Gradle 8.9) | **PASS** — `app-full-debug.apk` + `app-lite-debug.apk` built from the fixed sources |
| `apksigner verify --print-certs` both APKs | **PASS** — `CN=StreamBox Preview` (stable key wired correctly) |
| `aapt dump badging` | **PASS** — `com.streambox.app.debug` v2.13 (vc 23), leanback/touchscreen optional |
| Browser smoke / LCP / CLS / INP benchmarks | **NOT RUN** — no browser automation in this environment; no invented numbers |
| Android device/emulator tests (lifecycle, TV remote, playback) | **NOT RUN** — no device/emulator attached; code-reviewed only |
| Live streaming / subtitle provider tests | **NOT RUN** — would require live provider access; behavior verified at code level |

## J. Remaining Issues & Recommended Next Steps

1. **Branch divergence:** `native` carries website build 179 and an older wrapper — missing all of main's 180–214 fixes. Decide: merge `audit/stability-2026-11` into `main` and rebase `native` onto it, or make `native` consume the live site only (the wrapper already loads the GitHub Pages URL).
2. **Un-fixed RISKs from G:** SSL bypass scope (vidcore), `allowBackup`, public preview keystore, PIN strength. None urgent; all documented.
3. **Native Compose app (`android-native/`)**: its 5k-line `PlayerScreen.kt` state machine was audited at QA-invariant level (85 locks) but a Kotlin-level deep pass (coroutine scopes, WebView cleanup) is recommended — this session's fixes there were CI/Worker only.
4. **Runtime verification** of: resume/persistence across an app update (B-1), TV remote flows, mirror hop behavior on a dead title (B-4), anime pagination with the updated Worker (B-8). These need device/live runs.
5. **Publish the Worker** (`backend/`) from `audit/stability-native` so the paging/cache-key fixes reach production; deploy is CI-gated on the Cloudflare token.
6. **Smaller leftovers (NITs, unfixed):** fetch timeouts on subtitle downloads (detail paths done); `saveProgress` rewrites the Continue array every 15s (could diff-write); `nextEpisode(-1)` has no previous-season rollback; workflow action tags unpinned (supply-chain hygiene); `android-tv/` folder in the workspace is a stale checkout (safe to delete).
7. **Rollback plan:** branches are local and additive; `main`/`native` are untouched. Review with `git log main..audit/stability-2026-11` and `git log native..audit/stability-native`.

## K. Overall Readiness

| Platform | Rating | Evidence |
|---|---|---|
| **Website** | **8/10** | All identified crash/data/race/XSS classes fixed; QA 132/132 green; but no runtime browser benchmarks or live streaming tests (−1), and a few NITs remain (−1) |
| **Android Phone APK** | **8/10** | Compiles clean both flavors, stable signature verified via apksigner, crash + data-loss classes fixed; not device-tested (−1.5), SSL/backup accepted risks (−0.5) |
| **Android TV APK** | **7/10** | Same wrapper wins + renderer-death recovery + real TV banner + loop/timer fixes targeted at TV paths; but TV D-pad behavior is web-side and untested on hardware (−2), native TV app only CI-fixed (−1) |
| **Standard (full) APK** | **8/10** | `app-full-debug.apk` builds and signs correctly with the fixes; full-motion behavior preserved by design (no UI redesign); same device-testing gap |
| **Lite APK** | **8/10** | `app-lite-debug.apk` builds and signs correctly; calm-mode invariants untouched (QA 124 still green); lite flavor benefits identically from all wrapper fixes |

*Ratings are conservative because runtime/device verification was unavailable; every "fixed" claim above is backed by compilation, static QA, or direct code evidence listed in B/I.*
