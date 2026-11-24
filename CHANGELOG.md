# Changelog

## FluidMotion Update - web 241 / wrapper 2.40 (39) / native 0.110

### Added
- **StreamBox FluidMotion Engine** (web app, PWA, Standard + Lite WebView APKs):
  adaptive playback optimization with real capability detection, four auto
  performance tiers (A-D) driven by live stall signals with cooldown +
  hysteresis, and a fully wired motion-enhancement settings section
  (Auto / Smooth 60 / Balanced / Low-End TV Boost / Cinematic / Off,
  adaptive quality opt-in, FluidMotion Stats toggle). Persisted per user.
- **Genuine motion-compensated frame interpolation** for owned streams
  (window._hlsSource / hlsLoad, CORS-consented): WebGL2 block-matching
  motion estimation, motion-compensated warp synthesis with scene-change +
  confidence gating and occlusion-safe fallback, presented at true
  in-between timestamps. Audio, duration and subtitle timing unchanged.
- **FluidMotion Stats** overlay: real measurements only (source frames via
  requestVideoFrameCallback, interpolated/fallback counters, scene cuts,
  dropped frames via getVideoPlaybackQuality, WebGL backend, tier) - with
  "frames not accessible" honesty for provider embeds and estimates
  labelled as estimates.
- **Playback-active performance mode**: decorative animation, hero motion
  and blur work pause while video plays (real CPU/GPU relief on low-RAM TV
  sticks); tier C/D flattens blur entirely.
- **Adaptive quality** (opt-in, default off): repeated stalls step quality
  down only when the pill is on Auto - manual quality is never overridden.
- **Owned-stream loader** (`hlsLoad`) with graceful CORS fallback to plain
  playback.
- **Native track (native branch, 0.110)**: FluidMotion frame-rate matching
  via Surface.setFrameRate on the owned Media3 surface (true judder
  elimination for 23.976/24/25/29.97 content on capable displays),
  Media3 dropped-frame watchdog with the same tier logic, Motion mode row
  in the A/V panel, honest frames-not-accessible labeling for embeds.

### Changed
- Motion Judder and FluidMotion coexist: the existing per-source judder
  control stays authoritative for provider playback; FluidMotion governs
  engine posture, pacing and owned-stream interpolation.
- QA gate extended with 4 FluidMotion locks (settings wiring, honesty,
  genuine MEMC, performance posture) - all ~164 checks green.

### Fixed
- (from 240 line) tap-to-minimize toggle, More sheet dismissal, caption
  position/pill, iPhone deck - see release notes of 240.

### Honest limits
- Interpolation exists only where frames are legally readable; provider
  embeds cannot be interpolated by anyone and are never claimed to be.
- Web cannot switch display refresh rates (no API); the native track does
  real refresh matching on capable TVs.
- Physical-device smoothness verification pending; the pipeline is verified
  in automated browser tests against real moving content.
