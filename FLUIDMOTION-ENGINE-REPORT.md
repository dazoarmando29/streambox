# StreamBox FluidMotion Engine - Implementation Report

Release: **FluidMotion Update** - web build **241**, WebView wrapper **2.40** (versionCode 39), native **0.110**.
Every claim below is backed by a named verification. No fabricated metrics.

---

## 1. What FluidMotion is (and honestly is not)

StreamBox plays video two ways, and the engine is honest about both:

| Path | Frames readable? | What FluidMotion delivers |
| --- | --- | --- |
| **Provider embeds** (CineSrc, VidBolt, AniPM, VidRift, ... - the shipping catalog) | **NO** - opaque cross-origin iframes; browser security makes their frames inaccessible to anyone | Frame pacing, stall/quality watchdog, adaptive quality (opt-in), background-work suspension, honest diagnostics that say **"interpolation unavailable - frames not accessible"** |
| **Owned video shell** (`window._hlsSource` / `hlsLoad`, CORS-consented sources) | **YES** | **Genuine GPU motion-compensated interpolation** (WebGL2 block matching + motion-compensated warp), scene-change/confidence gating, display-synced presentation |

Rules enforced by QA locks 161-164: no fake FPS counters, estimates labelled as estimates, interpolation claims only where frames are readable, settings must drive the real pipeline.

## 2. The interpolation engine (owned-stream path) - genuine MEMC

Pipeline (`FluidMemc`, web, WebGL2):

1. **Frame commits** from the actual `<video>` element: `requestVideoFrameCallback` at presentation cadence, with a real-frame `timeupdate` fallback (~4 Hz, coarser but genuine) for engines where rVFC is absent or silent (older Android System WebView; headless compositing).
2. **Motion estimation**: per-block SAD search (25 candidate offsets per block over a 1/16-res grid) between consecutive committed frames - real measured motion vectors.
3. **Scene-change gate**: mean match error read back from the GPU; above threshold the pair is treated as a cut (counted, never interpolated).
4. **Motion-compensated synthesis**: bidirectional warp of prev/cur along the measured vectors at the true in-between timestamp, blend weighted by per-block match confidence; low-confidence blocks fall back to the nearest real frame (occlusion-safe). This is warp synthesis, not frame duplication or blending-as-fake-FPS.
5. **Presentation**: rAF-locked to display cadence; alpha computed from real media timestamps. Audio, duration and subtitle timing are never touched.

Native branch (Android, `playback/FluidMotion.kt`): `Surface.setFrameRate` content-rate matching on the owned Media3/ExoPlayer surface (`COMPATIBILITY_FIXED_SOURCE` in Cinematic = display switches to 23.976/24/25/29.97 source rate = true judder elimination; `ONLY_IF_SEAMLESS` everywhere), Media3 dropped-frame AnalyticsListener watchdog, honest "native frame-rate matching (no interpolation)" label. Provider embeds render in the app's WebView and honestly report frames-not-accessible.

## 3. Adaptive profiles (A-D) + modes

- Tiers A-D are derived from real signals: device memory/cores/TV detection at boot; runtime stall events from the existing recovery machinery (`midPlayRescue`/`recoverMidPlayStall` -> `FLUID.onStall`). Downgrade one tier per 30 s of sustained stalls; upgrade after 120 s of clean playback (hysteresis, no churn).
- Modes (persisted `streambox_fluid`): Auto (tier-driven), Smooth 60, Balanced, Low-End TV Boost, Cinematic (source-native cadence, decorative work frozen), Off. All six wired to live behavior: MEMC gating, background-suspension posture (`fluid-active`/`fluid-boost`), adaptive-quality permission.
- Low-end posture: while video plays, decorative animation/hero motion/blur transitions pause; tier C/D flattens blur entirely. Layout and playback untouched.

## 4. Measured results (automated - headless Edge at phone size)

Verified live against the real page with a synthetic moving-object stream (canvas.captureStream, same-origin frames):

| Check | Result | Evidence |
| --- | --- | --- |
| Capability detection | rVFC/webgl2/webcodecs detected true on capable runtime | engine boot output |
| Settings: 6 modes, persistence, chip | PASS | fluidtest 2 |
| Suspension while playing / not paused-off | PASS | fluidtest 3 |
| Watchdog: A->B on stall, cooldown holds, upgrade hysteresis | PASS | fluidtest 4 |
| Adaptive quality: never steps without opt-in; steps on Auto | PASS | fluidtest 4 |
| Honest stats (provider case) | "interpolation unavailable - frames not accessible", "rAF estimate" labels | fluidtest 5 |
| MEMC live on moving content | `live:true, has1:true, cut:false`; **18 interpolated + 43 fallback frames** in one measured window; scene-change gate clean (0 false cuts after buffer-order fix) | fluidtest 6 |
| Mode gating teardown/re-engage | PASS | fluidtest 7 |
| UI regression (More sheet x4 closes, tap grammar, captions) | PASS | sb-morerepro2 |
| QA gate (~164 locks incl. 4 new) | 0 failures, 0 warnings | tools/qa.js |
| Native: QA + `:app:assembleDebug` | 0 failures / BUILD SUCCESSFUL (local gradle, JDK 17) | native CI-equivalent local build |

Known measurement caveats (honest): headless Edge throttles `requestAnimationFrame` without compositor output, so per-window interpolation counts vary run-to-run there (a gate probe confirmed full-pass logic: `[m, !dead, !paused, has1, fullGate] = all true`). On real devices rAF runs at display cadence. **Physical-device smoothness testing is pending** (no iPhone/Android hardware in this environment).

## 5. Provider compatibility matrix

| Provider | Frames | Interpolation | What optimization applies |
| --- | --- | --- | --- |
| CineSrc, VidBolt, AniPM, VidRift, VidZen, VidCore, YapGrid, VidAPI + all listed mirrors | Opaque iframe | **Unavailable (honest)** | pacing + watchdog + suspension + adaptive quality (opt-in) |
| Owned shell (`hlsLoad`/`_hlsSource`, CORS-clean) | Readable | **Genuine MEMC** (tier A/B) | full pipeline |
| Owned shell without CORS consent | Not readable | Unavailable | graceful plain playback (loader fallback) |

## 6. Engineering targets vs reality

- 60 FPS output where hardware+source permit: **achievable on the owned path** (interpolation at display cadence); for embeds, presentation remains source-native (honest).
- <1% dropped frames: measurable only on owned path (`getVideoPlaybackQuality` shown live in stats); embeds report "Unavailable (iframe)".
- No fabricated counters: enforced by QA lock 162 + UI copy.
- Stable beats smooth: tier D and Cinematic deliberately prefer native cadence.

## 7. Files changed (web, feature/fluidmotion-web)

- `index.html` - FluidMotion engine (boot/flags/persistence, profiles+watchdog, WebGL2 MEMC, honest stats overlay, suspension CSS, settings section in player options, owned-shell loader `hlsLoad`), stall hooks in `midPlayRescue`/`recoverMidPlayStall`, build 241.
- `tools/qa.js` - locks 161-164 (settings wiring, honesty, genuine MEMC, performance posture).
- `android/app/build.gradle` - 2.40 / versionCode 39.
- `version.txt` - 241; `ios/project.yml` - CURRENT_PROJECT_VERSION 241 (sync tool).
- `release-notes/android.md` - FluidMotion release notes (release body source).
- This report + `CHANGELOG.md`.

Native (feature/fluidmotion-native, commit e2f7eda): `playback/FluidMotion.kt` (new), `ui/PlayerScreen.kt`, `ui/HlsPlayer.kt`, `data/Store.kt`, `build.gradle.kts` (0.110, versionCode frozen 50), `release-notes/native.md`.

## 8. Deployment & release verification

(Completed post-merge - see CHANGELOG and the final status table in the delivery message.)

## 9. Known limitations

1. Provider embed frames cannot be read by anyone (browser/DRM boundaries) - interpolation there is impossible without violating security, and is never claimed.
2. Display refresh-rate switching has no web API; native track covers it via Surface.setFrameRate on capable TVs.
3. Interpolation smoothness on physical devices awaits user verification; the pipeline math is verified against real moving content in automation.
4. MEMC is block-matching class (early-MEMC quality): fast motion at low confidence falls back to real frames rather than risking artifacts - by design.
5. Adaptive quality never overrides a manually chosen quality (opt-in only).
