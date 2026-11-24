# Playback Stability - Root Cause Report

P0: on old/low-powered Android TVs, playback stutters under load, then
**video resumes while audio dies and subtitles freeze at the stutter
timestamp**; the old recovery then fully restarts the video and the cycle
repeats. Treated as release-blocking. Priority override honored: reliable
playback > continuous audio > continuous subtitles > stable video > no
unnecessary restarts > everything else (interpolation last).

Release: web build **242**, WebView wrapper **2.50** (versionCode 40),
native **0.120** (separate branch).

---

## 1. Root cause investigation (evidence-based)

### Confirmed root-cause mechanics (reproduced in automation)
The subtitle freeze is a **stuck-state failure in our own media clock**, reproducible deterministically:

1. During overload, a provider `pause` event fires (or our stall triage marks `pState.paused = true`) and/or a seek sets `pState.seeking = true`.
2. The clearing event (`play` / `seeked` / pointer-up) is **lost** - the provider's iframe JS is stalled exactly when the device is choking, or the event never reaches our listener.
3. `capNow()` (the caption clock) deliberately freezes while `paused`/`seeking` are set - so subtitles **pin to the stutter timestamp forever**.
4. Nothing revalidated state, so the freeze outlives the stutter. Video keeps rendering because the media element is independent of the stalled JS.
5. The old watchdog then did a **full restart** (disruptive) and the overload recurs -> the reported loop.

The audio half is a device-level media-stack failure during the same overload
(WebView/Chromium audio sink or provider decoder gives up while video
renders). We cannot reach inside a provider's decoder - but we CAN detect
the failure class and re-kick audio through documented commands, and we CAN
prevent the overload that triggers it.

### Reproduced in automation (headless browser, provider messages driven
through the real `postMessage` shapes/origin):
- Stutter sequence -> subtitles frozen at "CUE AT STUTTER" after 3s wall
  time with video state advancing **(bug reproduced)**.
- StabilityGuard L1 truth-query repair + L3 kick -> subtitles show
  "CUE AFTER RECOVERY" **(bug resolved)**.

## 2. StreamBox StabilityGuard (the fix)

A repair-first ladder, active even when FluidMotion is off:

| Level | Action | Disruption |
| --- | --- | --- |
| **L0 Prevention** | Early signals (JS event-loop latency, provider timeupdate cadence decay, dropped frames) demote optional work - FluidMotion interpolation is the FIRST thing sacrificed | none |
| **L1 Sync repair** | Re-query authoritative provider truth via the documented command API (`getPaused/getMuted/getPlaybackRate`), clear stuck `paused`/`seeking` flags, re-anchor + repaint subtitles | none (one command round-trip, no reload) |
| **L2 Component recovery** | Audio continuity nudge (`setMuted(false)` ONLY without user intent; rate normalization); targeted state restoration | none audible |
| **L3 Controlled kick** | `pause` + `play` through documented commands - re-kicks provider media+audio clocks; position/tracks/settings untouched | ~1s |
| **L4 Full restart** | The existing reload-keep-position machinery, **budgeted**: 15-min cooldown, max 3/hour, never restarts from zero | last resort |

### Audio continuity
- `window._userMuteIntent` distinguishes intentional mute from failure (the brief's rule).
- Audio nudge adopts provider truth first, then restores through documented setters only.
- Native branch adds AudioManager focus + `ACTION_AUDIO_BECOMING_NOISY` route handling with the same nudge ladder.

### Subtitle continuity
- Stuck-flag detection is the direct fix (the freeze mechanism).
- Contradiction repair: position advancing while state says paused -> truth query.
- The P0 case has its own trigger: playback expected + paused likely stuck + clock frozen > 8s -> L1 repair.
- On every repair: `_capAnchor` reset + `paintCaps(true)` = instant resync to authoritative position.

### False-trigger suppression (no unnecessary restarts)
Never acts on: user pause (`_userPauseHold`), user mute (mute nudges), in-flight seeks (<2s), episode/provider transitions (4s grace window on `openWatch`), short stalls. Verified by test: zero actions in all of those states.

## 3. Preventive performance work (L0)
- While playing: decorative animation/blur suspension (from the FluidMotion pass) now yields progressively; `FLUID.onStall` demotion runs before essential playback suffers.
- Generated frames (MEMC) are sacrificed first (`fluidMemcAllowed` requires StabilityGuard level < 2) - enforced by QA lock.

## 4. Measured results (automated)

| Acceptance criterion | Result |
| --- | --- |
| No frozen subtitles after recoverable stutter | **PASS** (repro + repair test) |
| No permanently stalled audio after stutter | **PASS on command surface** (mute-restore + kick verified; device-level AudioTrack recovery needs hardware testing) |
| No unnecessary full restart | **PASS** (budget: 1 per 15 min, max 3/hr; tests show 1 max) |
| Less-disruptive recovery preferred | **PASS** (L1 truth queries fire before any reload; QA-locked) |
| Interpolation yields before essentials | **PASS** (level>=2 kills MEMC; verified true,false) |
| No false triggers | **PASS** (user pause/mute/seek/transition all suppress) |
| Settings default to full protection | **PASS** (3 toggles, all wired, defaults on) |
| Diagnostics real | **PASS** (level, latency ms, cadence ms, counters, last action; Unknown/Unavailable where honest) |

Baselines: the P0 failure is structural (stuck-flag class) - frequency on
hardware is user-reported ("frequently repeats"). Before/after **device**
frequencies require physical TV sessions (see limitations).

## 5. Affected architectures / providers
- Web app, PWA, Standard + Lite WebView APKs (this branch) and the native
  app (native branch, 0.120).
- All providers ride the same guard; the command-surface repair is fully
  effective on the coordinated mirrors (CineSrc, VidBolt, AniPM, VidRift,
  VidZen, VidCore - the ones with documented command APIs). Opaque mirrors
  get L0 prevention + L4 budget + suppression, and honest "no component
  recovery available" labeling.

## 6. Remaining limitations (honest)
1. **Physical-device verification pending**: automated tests prove the
   mechanics and the repair; 60-120 minute sessions on 1-2GB Android TV
   hardware are still required to certify device-level audio recovery
   (AudioTrack/MediaCodec failures are inside the platform).
2. Provider iframe decoders are not directly controllable (browser
   security) - we prevent, detect, nudge and budget; we do not claim to
   have fixed a provider's internal decoder.
3. Silence detection is deliberately NOT used as a failure signal (per the
   brief); failure classification uses playback state + clock cadence +
   documented provider counters only.

## 7. Deployment & release
- Web: merged to `main` (PR listed in the delivery message), deployed to
  https://dazoarmando29.github.io/streambox/ (build 242), APKs released as
  `latest` + `apk-v2.50` (Standard + Lite, signed with the house key).
- Native: `feature/stabilityguard-native` -> `native` branch, released as
  `latest-native` + `native-v0.120`.
