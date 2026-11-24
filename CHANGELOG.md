# Changelog

## Provider Compatibility Update - web 243 / wrapper 2.60 (41)

### Added
- **Universal Playback Adapter** (PB): picks the most capable authorized
  playback architecture per source (owned+MEMC > consented capture >
  provider enhancement > hardware enhancement > iframe+StabilityGuard) with
  a live per-source status that can never overstate MEMC (QA-locked):
  "Genuine MEMC active" / "adaptive frame pacing" / "Interpolation
  unavailable - provider embed (frames not accessible)".
- **Experimental capture MEMC** (default OFF): explicit user consent via
  the browser share-screen prompt feeds captured frames through the real
  WebGL2 MEMC engine, with live measured latency and auto-disable above
  250ms to protect A/V sync. Protected content captures black; never
  bypassed. Subordinate to StabilityGuard like all generated frames.
- **Owned-shell track pills**: embedded text/audio tracks surface in the A/V
  section and wire to the existing subtitle system.

### Changed
- MEMC engine: tiered motion-search radius (low-end halves the search),
  early-exit block matching, generalized frame ingest (uploadFrom) - one
  shared engine, no duplicates (QA-locked).
- Honest provider matrix documented in FLUIDMOTION-PROVIDER-COMPATIBILITY-
  REPORT.md: genuine MEMC inside provider iframes is blocked by browser
  security (frames unreadable to any engine without bypassing it) - the
  best authorized alternative is implemented per provider.

### Measured (honest)
- Engine sustained interpolation on software GL (SwiftShader, worst case):
  ~1.1 generated fps at 360p over a 30s driver window. Hardware GL on real
  devices is dramatically faster; device benchmarks pending physical
  testing. No 60fps claim is made.

## StabilityGuard Update - web 242 / wrapper 2.50 (40) / native 0.120

## StabilityGuard Update - web 242 / wrapper 2.50 (40) / native 0.120

### Added
- **StreamBox StabilityGuard** (P0 playback stability, all platforms):
  repair-first L0-L4 recovery ladder that kills the production "audio dies +
  subtitles freeze after a stutter" failure class. L0 prevention (early
  degradation demotes optional work), L1 sync repair (truth-query through
  the documented command API, stuck-flag clearing, subtitle re-anchoring -
  no reload), L2 audio continuity (documented unmute nudge that never
  overrides user mute), L3 controlled pause/play kick, L4 budgeted
  reload-keep-position (15 min cooldown, max 3/hour).
- StabilityGuard settings (Auto stability / Audio continuity / Subtitle
  resync) - all wired to real subsystems, all default ON.
- StabilityGuard diagnostics in FluidMotion Stats: health, level, JS loop
  latency, position cadence, subtitle clock state, repair/nudge/kick/
  restart counters, last action. Real measurements only.
- False-trigger suppression: user pause, user mute, in-flight seeks and
  episode transitions never trigger recovery actions (verified by test).

### Changed
- FluidMotion yields to stability: generated frames are sacrificed first
  whenever StabilityGuard level >= 2 (QA-locked priority override).
- Caption clock repair: stuck `paused`/`seeking` flags (the confirmed
  freeze mechanism) are detected and repaired against provider truth.

### Honest limits
- Provider iframe decoders are not directly controllable; we prevent,
  detect, nudge and budget - we do not claim to fix provider-internal
  decoders. Physical Android TV sessions (60-120 min) remain required to
  certify device-level audio recovery.

## FluidMotion Update - web 241 / wrapper 2.40 (39) / native 0.110

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
