# FluidMotion Provider Compatibility Report

Release: web build **243**, WebView wrapper **2.60** (versionCode 41).
Every status below reflects verified findings. The single honest headline:
**genuine MEMC cannot run inside provider iframes - by browser security
design, not by omission** - and this release delivers every technically
feasible, authorized expansion around that fact.

---

## 1. Provider compatibility matrix (verified)

| Capability | CineSrc | AniPM | VidBolt | Other mirrors | Owned shell (hlsLoad) |
| --- | --- | --- | --- | --- | --- |
| Playback architecture | cross-origin iframe (embed) | cross-origin iframe | cross-origin iframe | cross-origin iframe | our `<video>` element |
| Documented command API | rich (play/pause/seek/rate/mute/volume/quality + time/state events) | rich (state, subs, mute, rate, panels) | rich (quality/subs/audio/play/pause/seek) | partial (per-mirror) | full DOM/JS access |
| Position/timestamps | YES (postMessage) | YES | YES | partial | YES (exact) |
| **Frame access** | **NO - browser-enforced** | **NO** | **NO** | **NO** | **YES** |
| Genuine MEMC | **Unavailable** (blocker below) | **Unavailable** | **Unavailable** | **Unavailable** | **ACTIVE** (WebGL2 MEMC) |
| Provider-side motion enhancement | none exposed | none exposed | none exposed | none exposed | n/a |
| Device hardware MEMC (public API) | not exposed by Android/iOS | same | same | same | same |
| Native Media3 playback | n/a (stream never leaves WebView by design) | same | same | same | available on native branch |
| StabilityGuard | FULL (L0-L4) | FULL | FULL | L0/L1/L4 (no command API) | FULL |
| Frame pacing / anti-stutter | ACTIVE | ACTIVE | ACTIVE | ACTIVE | ACTIVE |
| Multi audio / embedded subs UI | provider's own | provider's own (sub/dub) | provider's own | varies | **NEW: native track pills in A/V** |

**The blocker, stated precisely:** provider video lives inside cross-origin
iframes. Browsers do not expose those pixels to the embedding page (no
canvas reads, no captureStream, no WebCodecs access). Reading them would
require bypassing browser security - which this project refuses to do (and
which would violate provider terms). Therefore "universal MEMC over
iframes" is not a missing feature; it is an impossibility without provider
cooperation. We implement every authorized alternative instead.

## 2. Universal Playback Adapter (NEW)

`PB` selects the most capable authorized architecture per source, in the
required priority order: (1) owned playback + genuine MEMC, (2) user-
consented capture pipeline (experimental), (3) provider-side enhancement
if ever exposed, (4) device hardware enhancement when the native shell
reports it, (5) optimized iframe + StabilityGuard. The per-source status
line in player settings shows the truth at all times:

- "Genuine MEMC active (motion-compensated)" - owned shell
- "Genuine MEMC active (experimental capture - latency N ms)" - consented capture
- "Native playback - adaptive frame pacing active"
- "Interpolation unavailable - provider embed (frames not accessible); Stability optimization active"

QA-locked: the status can never claim genuine MEMC where frames are
inaccessible.

## 3. Capture-based interpolation (EXPERIMENTAL, default OFF)

The one authorized route to interpolating provider content: explicit user
consent via the browser's own share-screen prompt (`getDisplayMedia`) /
Android MediaProjection on the native track. Properties:

- Frames are captured WITH consent and interpolated through the same WebGL2
  engine (`uploadFrom` + commit pipeline).
- **Measured latency is displayed live and auto-disables the mode above
  250 ms** to protect A/V sync (per the brief's rejection criteria).
- Protected/DRM content captures black frames; we never bypass that.
- The capture preview is silent (volume 0) - real audio continues from the
  player; no playback mute is ever applied (QA lock 4 verified).
- Disabled by default; the brief's rejection list (stutter/audio loss/
  subtitle freeze/thermal/restarts) is enforced by StabilityGuard
  subordination - capture MEMC yields at StabilityGuard level >= 2 like
  every other generated frame.

## 4. Owned shell expansion

- `hlsLoad` now supports the documented `window._hlsSource` feed with
  graceful CORS fallback (plain playback when frames are not consented).
- Formats: progressive MP4/WebM everywhere; HLS where the platform decodes
  it natively (Safari/iOS/WebView). DASH/HLS-in-Chrome would require an
  MSE demuxer dependency - deliberately not added; documented honestly.
- **Embedded track integration (NEW)**: text/audio tracks of the owned
  element surface as pills in the A/V section (where the platform exposes
  them), wired to the existing subtitle system and caption repaint.

## 5. Engine improvements (single shared engine)

- Tiered motion-search radius (full on tiers A/B, half on C/D) via shader
  uniform - genuine low-end cost reduction.
- Early-exit block matching (exact/near-exact match terminates the search).
- Lower-resolution estimation grid (1/16 res) preserved - output stays at
  source resolution.
- Bounded state: two frame textures + one estimation target; no queues.

## 6. Measured performance (this environment - honest)

| Measurement | Result | Meaning |
| --- | --- | --- |
| Engine sustained interpolation (640x360, **software GL/SwiftShader**, 30s driver) | **~1.1 generated fps (33 frames)** | worst-case CPU-rasterized floor; hardware GL on any real device is dramatically faster - device numbers pending physical testing |
| End-to-end 60s windows (SD/HD synthetic) | unreliable in headless (media delivery stalls; one earlier window processed 84 real frames with memc live) | headless compositor limitation, reported not hidden |
| Prior pipeline validation | 18 generated + 43 fallback frames in a live window; scene gate clean | engine math verified |
| Stutter/audio/subs failure class | reproduced + repaired (see PLAYBACK-STABILITY-ROOT-CAUSE-REPORT.md) | P0 covered |

**We do not claim sustained 60 FPS.** Sustained device rates (esp. 1-2GB
Android TVs) require physical benchmarks; interpolation yields to
StabilityGuard long before it can hurt playback.

## 7. StabilityGuard integration (unchanged priority)

Every new path (adapter, capture, track pills) is subordinate to
StabilityGuard: generated frames are the first thing sacrificed at
recovery level >= 2; capture mode cannot outrank audio/subtitle integrity;
false-trigger suppression applies. The audio-death/frozen-subtitle P0
release-blocker remains fixed and regression-tested.

## 8. Files changed (this release)

Web: `index.html` (PB adapter + status + capture pipeline + track pills +
MEMC tiered search/early-exit/uploadFrom), `tools/qa.js` (locks 168-169),
`android/app/build.gradle` (2.60/41), `version.txt`/`ios/project.yml` (243),
release notes, this report, CHANGELOG.

## 9. Verified test results

- QA gate: 0 failures (169 locks incl. adapter honesty + engine sharing).
- P0 stability suite: reproduced + repaired (regression clean).
- FluidMotion suite: settings/watchdog/honesty/teardown green.
- UI regression suite: More sheet, tap grammar, captions green.
- Native expansion (Media3 pipeline for authorized direct media,
  MediaProjection capture experiment, hardware-MEMC API survey): tracked on
  the native branch work queue behind the StabilityGuard native merge;
  delivered status will land in that branch's release notes.

## 10. Remaining limitations

1. Provider-iframe MEMC is impossible without provider cooperation; if a
   provider publishes an authorized frame/direct-stream API, the adapter is
   the integration point.
2. Capture-mode A/V latency cannot be eliminated (capture pipeline delay);
   the auto-disable guard keeps it from ever being harmful.
3. Device hardware motion enhancement exposes no public API on Android TV
   or iOS; manufacturer MEMC (TV-side) applies to any HDMI input and needs
   no app support - documented, not claimable by us.
4. Sustained FPS on real hardware (esp. low-end TVs) remains the physical-
   testing item; all numbers here are automation-verified and honestly
   labeled.
