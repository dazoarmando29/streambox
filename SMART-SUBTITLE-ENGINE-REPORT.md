# Smart Subtitle Engine - Implementation Report

Status: **implemented, tested, and pushed to `feature/smart-subtitles` - NOT
merged/released, per the instruction to hold deployment without explicit
authorization.** All numbers below are real test output.

## 1. Previous architecture (audited)

Subtitle chain: Wyzie (`wyzAutoSubs/wyzSearch/wyzDownload`, 1,000/day quota
with 1h cap memory) -> TSDB -> BetaSeries (Worker `/api/bs-subs`, file text
via CORS relay). `parseSubs()` -> `setSubs()` -> localStorage per episode
(`sb_sub_*`, pruned to 8). Rendering: `capNow()` staleness-aware clock ->
`paintCaps()` overlay with the styling/offset ladders. Weaknesses found:
selection was **popularity-first** (`downloadCount` sort - "popular but
wrong release" won), no candidate validation beyond `cues.length`, timing
was a single manual offset, and sync knowledge was never remembered.

## 2. Implemented

| Brief section | What shipped | Where |
| --- | --- | --- |
| Smart Chooser (2-3) | `subParseRelease` (WEB-DL/WEBRip/BluRay/HDTV/DVDRip, Extended/Director's Cut/Theatrical/Unrated/Remastered/REPACK/PROPER, group, SxxExx) + `subEligible` hard gates (episode/season/forced/commentary) + `subScore` weighted exactly per table (30/25/20/10/5/5/5), popularity tiebreak-only, unknown metadata neutral, evidence coverage tracked | index.html |
| Validation (4) | `subValidateRaw` (HTML/empty) + `subValidate` (corrupt timestamps, excess overlap, duplicates, ad/promo cues, plausibility vs real duration). "Ends before credits" stays VALID per spec. Invalid files fall through to the next candidate | index.html |
| Adaptive sync (5) | `subMapCore/subMapTime` affine transform at LOOKUP (cues immutable), constant offset (`subSyncNudge`), two-point drift (`subCalTwo`) with fps-ratio snapping ONLY within 0.006 evidence and outlier rejection, segmented piecewise (`subBuildSegments`, 3+ anchors, monotonic-enforced, max 8 segments, falls back to global fit when unstable) | index.html |
| Dialogue-aware (6) | Consent-gated `subDialogueProbe` (configured speech endpoint + explicit consent; 3x2s clips from OWNED media only; fuzzy LCS text match -> anchor -> correction). On provider iframes: honest "Timing verification unavailable on this player" - no simulation | index.html |
| Clock integration (8) | Single clock = existing `capNow()` estimator (rate-correct, lag-compensated, seek-snap, monotonic). Corrections compose via `subMapTime`; subFile.offset rides on top. Re-anchors after seeks (StabilityGuard) | index.html |
| Source switching (9) | `subSyncReevaluate()` on mirror switches (updateMirrorUI) + `sessionKey` per episode + stale-guard in auto chain | index.html |
| Memory (10) | `sb_sync_*` entries: fingerprint (mirror + duration), offset, scale, segments, anchors, verifiedBy, fileId, timestamp. Release-fingerprint guard (cross-release application IMPOSSIBLE), schema versioning, 90-day expiry, max 8 entries, `subSyncReset` | index.html |
| Settings UI (11) | SMART SUBTITLES block in the Subs tab: Smart Chooser + Adaptive Auto-Sync toggles (both default ON), Find Better Match, Resync Subtitles, Subs Too Early/Late, Sync This Line Now, Remember This Match, Reset Synchronization, Dialogue Sync, live match line. Labels: Recommended Match / Likely Match / Previously Confirmed / Different Release / Timing Unverified / Verified Match (frame-rate corrected|drift corrected|segmented) | index.html |
| Status (12) | Brief's exact strings via `subStatus()` (nonintrusive, watchStatus) | index.html |
| Manual calibration (13) | One-point (`subCalOne`: on-screen line + current clock), two-point drift (`subCalTwo`, rejects anchors <20s apart or unstable scale), reset/undo (`subSyncReset`); manual always outranks automatic | index.html |
| Quota/network (14) | `subFetchGuard`: request dedup (verified by fetch counter), 15s timeouts, 429 -> 5min provider cooldown, search cache structure, max **2 downloads** per auto attempt (`SUB.dlCap`), existing Wyzie quota/backup chain preserved, manual "Find Better Match" refills budget | index.html |

## 3. Player capability matrix (verified from repository behavior)

| Capability | CineSrc | VidBolt | AniPM | Other mirrors | Owned shell (hlsLoad) |
| --- | --- | --- | --- | --- | --- |
| Authoritative timestamps | postMessage | postMessage | postMessage | partial | exact |
| Duration/state/rate | yes | yes | yes | partial | yes |
| Seeking events | yes | yes | yes | partial | yes |
| Custom subtitle injection | overlay (ours) | overlay / own tracks | overlay / own tracks | overlay | overlay + native tracks |
| Accessible audio (for dialogue sync) | NO - cross-origin | NO | NO | NO | YES (consent-gated) |
| Trusted transcripts | no | no | no | no | via configured endpoint |

## 4. Test results (23/23 PASS, automated browser)

- release parsing; compatible-release outranks popular-but-incompatible (0.652 > 0.43)
- gates reject wrong episode / language / forced (the suite caught a REAL substring bug: "french" matched "en" - fixed with token matching)
- validation rejects empty/corrupt/overlap/ad; clean passes; **ends-before-credits stays valid**; HTML rejected
- constant offset (3s early -> corrected to +3 exactly)
- drift: two-point fit snaps 25/23.976 ratio (scale 1.04167), mid-movie mapping correct
- segmented: 2 piecewise sections, intro shifted +10, later section clean
- memory: restore / wrong-release REFUSED / reset
- quota: 2-download cap; dedup = exactly 1 real network trip for 2 calls
- security: markup escaped in captions; untrusted postMessage origins ignored; session key set
- manual correction precedence + honest status text; full status vocabulary present
- regression: subtitle-clock suite (0 yanks, rogue-message immune, rate-correct) + StabilityGuard suite (RESYNCED, budget, priority) all green after these changes
- QA gate: 0 failures (3 new locks: 171 scoring honesty, 172 quota+immutability, 173 memory guards)

## 5. Honest limitations

1. **Dialogue-aware sync cannot work on provider iframes** (audio is not
   reachable by any browser - not a gap, a boundary). It runs only on owned
   media with an explicitly configured + consented speech endpoint; nothing
   is transmitted otherwise. The 250ms median target is unverified without
   such an endpoint - metadata-based sync is exact by construction for
   offset/drift once anchors exist.
2. fps corrections only engage within 0.006 of a known ratio and after two
   real anchors; otherwise labeled "drift corrected" without an fps claim.
3. Commentary/forced detection is name-based (honest: "Commentary track"
   rejection); burned-in captions cannot be detected from cue text alone.
4. Physical-device UX verification pending (iOS/Android/TV) - behavior is
   automation-verified in a real browser engine.

## 6. Files changed (branch `feature/smart-subtitles`)

- `index.html`: SUB engine (normalize/score/validate/sync/memory/quota/
  dialogue/status/actions), smart auto-pick wiring in `wyzAutoSubs`,
  lookup-time transform in `paintCaps`, memory binding in `setSubs`,
  rebind in `updateMirrorUI`, SMART SUBTITLES UI block, match line.
- `tools/qa.js`: locks 171-173 + two re-anchored ticker locks.
- (version bump included for the eventual release; see delivery message)
