# StreamBox Watch Party — Reliability Overhaul: Root Cause & Verification Report

**Date:** 2026-11-23 · **Branches:** `fix/watch-party-reliability` (from `main`) + `fix/watch-party-reliability-native` (from `native`) · **Nothing pushed, merged, or deployed.**

This report covers the P0 rebuild of Watch Party synchronization (timeline authority, join positioning, episode transitions, buffering/slow-network recovery, reconnection & stale-event handling), the P1 player-adapter corrections, and the responsive/mobile verification. Every claim below is backed by a named test that was **actually run** in this environment; anything that could not be run is explicitly listed in *D. Remaining Limitations*.

---

## A. Root Cause Report

### A1. "People joining a party land at 00:00 / the wrong spot"

| | |
|---|---|
| **Symptom** | Joiners opened the video but stayed at 00:00 or a stale position, especially on slow connections. |
| **Root cause** | `syncToRoom()` (index.html PARTY module) gave up waiting for the player after **24 × 500 ms = 12 s** of readiness retries; a player that became ready at 20 s was never positioned. It also trusted the frozen snapshot value instead of recomputing at apply time. |
| **Fix** | `syncToRoom` now waits up to **60 × 500 ms = 30 s** (`CFG.SYNC_READY_TRIES`), retries are stamped with the player generation (an episode change drops stale retries), the authoritative position is **recomputed at apply time** from `expectedPos()` (server clock + measured offset), readiness is reported to the room (`pstate ready/playing`), and a permanent failure shows an actionable Resync message instead of silence. |
| **Verified by** | `sync-reliability.test.js` *"late join during playback lands on the room timeline (35 minutes in)"*, *"late join while paused stays paused"*, *"slow participant takes 20s to load…never 00:00"*; `tools/party-e2e.js` scenarios 5 & 8 in real Edge (slow joiner landed at **28.8 s vs host 28.8 s**). |

### A2. "Episode changes desync the room / someone stays on the old episode"

| | |
|---|---|
| **Symptom** | After a host episode change, participants started at different times, or a device kept playing the previous episode; stale events from the old episode could rewind or re-advance the room. |
| **Root cause** | (1) Episode starts were a **blind fixed 8 s gate** (`START_DELAY_MS`) with no readiness concept — devices that loaded slowly started late and drifted. (2) `sessionVersion` staleness was only enforced **when the client chose to send it** — a bare `ended`/`seek` from a previous episode was accepted. (3) The client's "instant episode reporting" hook `PARTY.onLocalOpenWatch` was **never exported** (silently swallowed by `try/catch`), so content changes were detected only by a 2 s backstop poll — the build-219 "fix" did not actually work (found by the new E2E harness). |
| **Fix** | **Coordinated episode-transition lifecycle** in `rooms.js` / PartyHub: host `content` → new `sessionVersion` + a readiness-gated transition (`gate: loading → scheduled → live`); clients report `loading → ready`; the shared start is scheduled on the **server clock** when readiness quorum is reached (min 3 s + 2.5 s countdown) or at the readiness timeout (default 8 s, host-configurable 4 s–2 min); the finalized gate is re-broadcast as a second `content` event (same content key — every client generation re-arms without reloading) plus a new `schedule` event. `ended` is **idempotent** and refused during transitions; playback commands stamped for older sessions are rejected; `seq` gaps trigger snapshot reconciliation; `onLocalOpenWatch` is exported so host changes report instantly. Host `play`/`seek` overrides a pending transition; `pause` cancels it. |
| **Verified by** | *"host changes episode while everyone is playing"*, *"…while a guest is buffering"*, *"rapid episode switching: only the latest session is actionable"*, *"a stale previous-episode completion can never advance the room"*, *"host override"*, *"client joins during a scheduled start"*; E2E scenario 7 (episode change while playing: **host 0.9 s vs guest 0.9 s** on the new episode). |

### A3. "Slow-internet participants fall behind and never recover"

| | |
|---|---|
| **Symptom** | A participant's player buffers; the room keeps going; the stalled device either stays behind forever, or corrections seek it repeatedly into unavailable data (stutter/seek loops). |
| **Root cause** | The protocol's `buffer` action was a **silent no-op flag** (Node and PartyHub both); there was no per-participant player state, no recovery policy, no "Buffering — catching up" UI, and `driftTick` kept issuing seeks against a stalled player. The pending playback-rate-restore `setTimeout` could also fire against a **recreated player or a new episode**. |
| **Fix** | New **player-state channel** (`pstate`: loading/ready/playing/paused/buffering/recovered/error/disconnected) tracked **per participant**; real stall detection in `driftTick` (frozen media clock while the room plays) → clear **"Buffering — catching up"** pill, corrections suspended while stalled (planner returns `none` on `buffering`), **one** catch-up seek after recovery (cooldown-guarded), repeated-stall remedy message (quality step-down / synced source), prolonged-stall message. Room policy: default **"Keep Party Moving"** (timeline untouched — tested seq-identical across 5 stall cycles) or host-enabled **"Wait for Everyone"** (auto-hold at the *authoritative* position with `auto` playback events, released on recovery / configurable timeout with `hold_released` notice / host override). The rate-restore timer is now tracked (`S._rateT`), stamped with the player generation, and cancelled on episode change (`invalidateSyncWork`), player recreate (`loadPlayer` hook → `onPlayerRecreate`), and room teardown. |
| **Verified by** | *"one participant buffers repeatedly: default policy never disturbs the room timeline"*, *"participant resumes after buffering"*, *"wait-for-everyone: hold + release"*, *"buffering participant NEVER becomes ready: the hold times out"*, *"a player in error state never blocks the room"*, *"planCorrection: buffering/seeking/gate/unready players are never fought"*. |

### A4. "Commands go missing, loop, or arrive out of order; reconnects create ghosts"

| | |
|---|---|
| **Root cause** | Session-stamped staleness was optional (see A2); there was no gap detection for missed events; old socket callbacks could still mutate state after a reconnect; background/online transitions did not force a fresh snapshot; the PartyHub (deployed DO) did not refresh liveness on heartbeats (backgrounded phones evicted after 100 s) and handled garbage `sessionVersion`/`sync_report` validation differently from the Node server. |
| **Fix** | Client: `sessionVersion` guards on every applied event, `seq > lastSeq + 1` → `resync` snapshot reconciliation, per-connection **epochs** (`S._connEpoch`) so stale socket callbacks no-op, `visibilitychange`/`online` handlers force a heartbeat + `resync` + `syncToRoom`, exponential backoff with jitter retained (0.5 s→15 s cap). Server (both): `ended` idempotency, gate lifecycle (`_sweepGate`) so legitimate completions after a transition still work, lazy deadline evaluation on every command **and** a 2 s tick (correctness never depends on a bare timer). PartyHub parity fixes are listed in B (heartbeat liveness, hashed passwords, `sync_report` limiter, `room_ended` to the ending host). |
| **Verified by** | *"two seek commands arrive out of order"*, *"rapid play-pause-play … duplicates are idempotent"*, *"duplicate socket events … never double-advance"*, *"participant disconnects and reconnects: same seat"*, *"participant returns from background"*, E2E scenario 9 (socket killed → auto-rejoin, **same seat id**, re-landed on the live timeline). |

### A5. Player-adapter defects found by the adapter audit (P1)

| Symptom | Root cause | Fix | Verified by |
|---|---|---|---|
| Scrubbing the seek bar did nothing on VidCore | The bar `change` handler fell through to `sendCmd` (CineSrc-only) — no `coreSend("seek")` arm | Added the `vidcore` arm (same for `mirrorSeek`, auto-skip dispatch) | code-level; QA gate; click-test 15/15 |
| Skip Intro/Recap/Credits absent on VidCore | Skip gate mirror-lists excluded `vidcore` despite its real seek API | All 4 gate lists + dispatch chains include `vidcore` | code-level + QA |
| Sleep timer claimed "paused" on VidCore but didn't | `sleepTick`/`applySleepOnEnd` never sent `coreSend("pause")` | Added both arms | code-level + QA |
| Settings Speed/Volume rows did nothing on most mirrors (visual-only controls) | `speedSet`/`volStep` only spoke CineSrc/AniPM; rows stayed visible on blind/no-API mirrors | Rows now hide on mirrors that cannot honor them (`speedCapable()`/`volCapable()`), matching the documented house rule that blind toggles are never shown | QA + responsive 0-fail |
| Autoplay-gesture one-shot could fire `ctlToggle()` on a *later* player | `window._autoGo` listeners only removed when they fired | Removed at every `loadPlayer` teardown | code-level + QA |

### A6. Regression hunt: reported fixes that were not actually working

- **`PARTY.onLocalOpenWatch` missing from exports** (A2): the build-219 "instant episode reporting" never fired. Fixed; caught by the new browser E2E.
- **`cinesrc-website/backend/dist`** is a stale October build **without any party hub**; only `sb-native-ref/backend/src` holds the deployed PartyHub source. Documented in F so the wrong bundle is never deployed.

---

## B. Changed Files

### `main` branch (`cinesrc-website/`)

| File | Why |
|---|---|
| `party-server/protocol.js` | New `pstate` message + `peer_state`/`schedule` server events; `waitMode`/`readyTimeoutMs`/`holdTimeoutMs` settings; `PSTATE_VALUES`/`PSTATE_BLOCKING`/`WAIT_MODES`; transition/hold limits; `sync_report` gains optional `sv`/`st`; **`planCorrection()` — the shared, unit-tested drift-correction policy**. |
| `party-server/rooms.js` | Injectable clock (deterministic tests); readiness-gated two-phase episode transitions (`gate`, `_maybeScheduleStart`, `_finalizeStart`, `_sweepGate`); per-participant `pstate`/`pstateSv`; wait-for-everyone holds (`_maybeCreateHold`/`_releaseHold`/`_maybeReleaseHold`); idempotent `ended`; stale-completion refusal; `tick()` lazy deadlines; snapshot gains `gate`/`hold`/`waitMode`/pstate fields. |
| `party-server/server.js` | `pstate` dispatch with its own rate limiter; `sync_report` routed with full message (sv/st); 2 s deadline tick; `proto: 2` in `welcome`; tick cleanup on close. |
| `party-server/test/sync-reliability.test.js` | **New:** 37 deterministic scenario tests (fake clock) + planner policy tests + measured drift distribution. |
| `party-server/test/fixtures/test-video.mp4` | **New:** 120 s seekable ffmpeg-generated fixture for browser tests (self-contained, no user media). |
| `party-server/package.json` | `npm test` runs both suites. |
| `index.html` | PARTY module: readiness/buffering channel (`reportState`, `bufPill`), stall detection + catch-up in `driftTick` (via `planCorrection`), `syncToRoom` 30 s budget + generation-stamped retries, `schedule`/`peer_state`/`hold_released` handling, session/seq guards, connection epochs, `visibilitychange`/`online` resync, `invalidateSyncWork`/`onPlayerRecreate` timer hygiene (incl. `loadPlayer` wrap), `teardownRoom` full cleanup, host **"Wait for everyone"** toggle, participant state chips, `onLocalOpenWatch` export fix; player fixes from A5; phone bar 40 px touch floor. |
| `tools/qa.js` | Drift lock now asserts the client planner is **byte-identical** to `party-server/protocol.js`; phone-bar lock updated to the 40 px policy. |
| `tools/party-e2e.js` | **New:** real-browser E2E (21 scenarios, drift distribution measurement). |

### `native` branch (`sb-native-ref/`, worktree)

| File | Why |
|---|---|
| `backend/src/party.js` | PartyHub Durable Object: full behavioral parity with the Node room machine — readiness-gated transitions (`gate`, `_maybeScheduleStart`, `_finalizeStart`, `_sweepGate`, `tick`), wait-for-everyone holds, `pstate`/`peer_state`/`schedule`, idempotent `ended`, stale-completion refusal, snapshot extensions — plus the audit parity fixes: heartbeat/any-traffic liveness (`markSeen`), **salted-hash passwords** (sha256 of `salt + "\0" + pw`, digest-compatible with Node; async because Workers only ships async digests), `sync_report` + `pstate` rate limiters, `room_ended` reaches the host who ended the room, `proto: 2` in `welcome`. Alarm now fires at `min(+10s, next gate deadline, next hold expiry)` so transitions resolve on time without traffic. |
| `backend/test-party-parity.mjs` | **New:** 15-scenario dispatch-level harness (fake WebSockets + fake DO state + pinned clock) covering the full gate lifecycle (incl. the second `content` re-arm event and `ended` after `live`), holds (create/release/timeout-via-alarm/override), stale/idempotent completions, stale-sv guards, settings clamps, end→host, heartbeat liveness, hashed passwords, both rate limiters. Caught one real porting bug (`_pbState` name mismatch) before completion. |

Deliberate divergences (documented, not defects): DO `sync_report` keeps its lenient position clamp (old clients depend on it) where Node rejects; wire-level `bad_message` shapes preserved as before (no `for:` suffix on some DO errors); timer granularity ≤10 s vs Node's 2 s tick (same end state); `planCorrection` is client-side only. `Party.kt` (native client) needs no change — protocol additions are additive and it rides the legacy path (see D5).

---

## C. Verification Report

### Automated tests (all run in this environment)

| Suite | Result |
|---|---|
| `party-server` protocol/room suite (`test/party.test.js`, 35 tests — untouched legacy behavior) | **35/35 pass** |
| `party-server` reliability suite (`test/sync-reliability.test.js`, 37 tests) | **37/37 pass** |
| `tools/qa.js` site gate (~160 invariants incl. sync honesty, chat escaping, invite hygiene, server-side permissions, reconnect shape, planner-equality) | **0 failures, 0 warnings** |
| `tools/responsive-test.js` (home/details/party/search/player × 320→3840 px) | **0 failing screens, 0 compact-target warnings** |
| `tools/click-test.js` (interaction regression) | **15/15 pass** |
| `tools/party-e2e.js` (real Edge pages × real WebSockets × real party-server) | **21/21 pass** |
| `node --check` on every touched JS (incl. inline `<script>` extraction of index.html) | **clean** |
| PartyHub DO parity harness (`sb-native-ref/backend/test-party-parity.mjs`, 15 scenarios, dispatch-level with pinned clock) | **15/15 pass** (independently re-run after review; the hub diff was line-reviewed against `rooms.js` — gate/hold lifecycle, idempotent `ended`, hash compatibility verified) |
| DO `node --check src/party.js` + `npm run check` | **clean** |

Coverage maps to your 28 required scenarios as follows (test names in `sync-reliability.test.js` unless noted): (1,2) join+play, 3+ participants ✓ · (3,4) late join playing/paused ✓ · (5) 20 s slow load ✓ + E2E · (6,7) repeated buffering / resume ✓ · (8,9) episode change while playing / while buffering ✓ + E2E · (10) rapid episode switching ✓ · (11) out-of-order seeks ✓ · (12) rapid play-pause-play ✓ · (13,14) disconnect/reconnect, network switch ✓ + E2E · (15) background return ✓ · (16) unauthorized episode change ✓ · (17) different source capabilities ✓ (cut/duration visibility + planner) · (18) unsupported player ✓ (planner) · (19) auto-next ✓ · (20) stale previous-episode event ✓ · (21) player init failure ✓ · (22) disconnect during scheduled start ✓ · (23) join during scheduled start ✓ · (24) host leaves ✓ · (25) buffering participant never ready ✓ · (26) simultaneous controllers ✓ · (27) duplicate socket events ✓ · (28) leave with pending timers ✓.

### Measured synchronization results

- **Deterministic simulation (4 clients, 5 min, shared correction policy, 20–250 ms latency):** `median 0.156 s · p95 0.291 s · worst 0.398 s` (n=1180 samples).
- **Real-browser E2E (4 Edge pages, real WebSockets, controllable adapter, localhost):** `median 0.001 s · p95 0.002 s · worst 0.577 s` (n=360; worst sample = post-reconnect catch-up).
- **Honest reading:** these numbers verify the *sync engine* (state authority, join positioning, correction policy) under favorable latency. They are **not** a claim about third-party embeds over real networks: iframe bridge latency, provider buffer behavior and different cuts dominate real-world drift. Sub-second sync is the *goal* for fully controllable sources on decent connections — field measurement on real devices is still required (D/E).

### Browser/device coverage

- **Run:** headless Edge (Chromium) at 320/360/390/414/430/600/768/820/1024/1280/1366/1440/1920/2560/3840 px + real-Websocket multi-client party sessions (Windows dev machine).
- **Not run:** physical iPhone/iPad Safari, Android Chrome/WebView, Android TV hardware, tablet split-screen. **Chrome/Edge emulation does not prove iPhone Safari compatibility** (WebKit quirks: dynamic viewport height, keyboard-driven pan, autoplay, fullscreen restrictions). See E for the manual device checklist.

### Remaining unsupported player capabilities (audit-verified, unchanged by design)

| Source | Play | Pause | Seek | Time events | Rate | Sync class |
|---|---|---|---|---|---|---|
| CineSrc | ✓ | ✓ | ✓ | ✓ | ✓ | **FULL** |
| AniPM | ✓ | ✓ | ✓ | ✓ | ✓ | **FULL** |
| VidBolt | ✓ | ✓ | ✓ | ✓ | ✗ (blind) | **FULL** (position), rate unsupported |
| VidZen | ✓ | ✓ | ✓ | ✓ | ✗ (blind) | **FULL** (position), rate unsupported |
| VidCore | ✓ | ✓ | ✓ (now wired everywhere) | ✓ | ✗ (blind) | **FULL** (position), rate unsupported |
| VidRift | ✗ (no API) | ✗ | ✓ (resume) | ✓ | ✗ | **PARTIAL** — seeks follow, pause manual |
| VidLink, VidFast, VidSrc, VsEmbed, AutoEmbed, 2Embed, YapGrid, VidAPI, trailer | ✗ | ✗ | ✗ | ✗ | ✗ | **COORDINATED START ONLY** (honest UI label; Resync guidance) |

Different mirrors can carry different cuts/durations (sub vs dub): the UI now *detects* (duration mismatch), *admits* it, and refuses to blindly align timestamps below a 15 s bridge floor.

---

## D. Remaining Limitations

1. **Real-device cross-matrix untested here** (no devices attached): iPhone Safari background/foreground, Android WebView lifecycle, screen lock/wake, Wi-Fi↔cellular switches need the manual checklist (E).
2. **Third-party embeds are opaque by design** (cross-origin): VidRift has no pause API; coordinated-only mirrors cannot be remote-driven at all — the sync code cannot and does not pretend otherwise. In-stream (SSAI) ads cannot be stripped.
3. **Drift numbers above are engine-level** (controllable adapter, localhost/LAN-like latency). Real embed drift needs field measurement; `CFG.SYNC_NUDGE/SYNC_SEEK/SYNC_MIN_GAP` remain the documented tuning knobs.
4. **Clock sync is NTP-lite** (symmetric-latency assumption, lowest-RTT anchor with outlier re-anchoring). Residual error is absorbed by the drift corrector; sub-second *global* precision is not claimed.
5. **Native Android client (`Party.kt`)** works through the legacy protocol path (additive changes — nothing breaks), but does not yet *report* readiness/buffering, so native-only rooms start at the readiness-timeout deadline instead of early. Listed as follow-up work.
6. **Multi-instance server** would need shared room state (Redis/sticky routing) — unchanged single-instance design.
7. Chat remains ephemeral (by design); no persistence.

---

## E. Manual QA Checklist (2–3 devices, one on a slow network)

**Setup:** device 1 = host (phone or laptop), device 2 = guest, device 3 (optional) = guest on throttled network. On device 3 use iOS Settings → Developer → Network Link Conditioner ("3G"/"Very Bad Network") or Android emulator throttling / a far-from-router Wi-Fi.

1. **Join positioning:** host opens a movie → Watch Party → Create. Guest joins via the invite link. Host plays and fast-forwards to ~10:00. *Expect:* guest lands at ~10:00 (±2 s), not 00:00 — even on the slow device (give it up to 30 s to load).
2. **Play/pause/seek:** alternate play/pause rapidly 5× on the host; drag the seek bar; use ±10/30 s. *Expect:* guests follow each action within a beat; no double-fires; no one rewinds afterwards.
3. **Episode change:** on a series, switch to the next episode mid-playback. *Expect:* every device shows "Starting soon/Starting together", all land on the same episode at nearly the same time (≤2 s apart); the slow device joins *at the room's position* once loaded. Rapid-fire 3 episode switches: the room ends on the last one everywhere.
4. **Buffering (default):** throttle device 3 until it buffers. *Expect:* other viewers keep watching; device 3 shows "Buffering — catching up", then jumps to the room position once — no repeated seek stutter; after repeated stalls a suggestion to lower quality/switch source appears.
5. **Wait for everyone:** host enables "Wait for everyone". Throttle device 3 again. *Expect:* the room pauses with "Waiting for 1 viewer…", resumes when device 3 recovers, and starts without it if it stalls past ~20 s (notice shown). Host play/pause overrides the hold.
6. **Reconnection:** on a guest, toggle airplane mode 10 s, or walk Wi-Fi→cellular. *Expect:* the pill shows CONNECTING then rejoins the SAME identity (no duplicate in the list) at the current position. Background the app 1–2 min, return: it catches up.
7. **Unauthorized control:** with "Guests control playback" off, guests' play/pause/seek do nothing (server-enforced); guests cannot change episodes.
8. **Resync button:** always lands the player at the party time (or explains the source can't be driven remotely).
9. **iPhone-specific:** safe-area/notch clearance of the player bar and party chat; chat input stays above the keyboard; no page zoom when focusing inputs; fullscreen rotates; returning from background re-syncs (step 6).
10. **TV remote (if available):** D-pad through the party panel; OK/Back behavior; large room-code legibility.

---

## F. Deployment Notes

1. **Party server (self-host option):** `cd party-server && npm install && npm start` — the Node server is complete and tested (72/72). No new dependencies.
2. **Cloudflare Worker / PartyHub Durable Object (the deployed hub):** the updated source lives on the `native` branch in `backend/src/party.js` (+ `index.js` unchanged routing). **Deploy = `npx wrangler deploy` from `backend/`** (bundles `src` at deploy time). ⚠️ Do **not** deploy `cinesrc-website/backend/dist` — it is a stale October bundle *without* the party hub. After deploying, run `party-server/test/edge-smoke.js` against `wss://streambox-api.dazoarmando.workers.dev/party` to re-verify live.
3. **Protocol migration:** all changes are **additive** with a `proto: 2` capability marker in `welcome`. Old clients (stale PWA caches, released APKs) keep working through the legacy path (fixed deadline gate, no readiness early-start); new clients automatically use the improved path. No flag day, no breaking message shapes.
4. **Website:** ships via GitHub Pages on push to `main` (per repo policy — not done from here). The WebView APKs (full/lite) embed the site and inherit everything automatically; rebuild via `android-apk.yml` when releasing.
5. **Native Android app (`android-native/`):** rebuild `app-debug.apk` to pick up the DO compatibility changes; no code changes are *required* (legacy path intact). Readiness reporting for native clients is a recommended follow-up, not a deployment blocker.
6. **Verification gates to run before release:** `cd party-server && npm test` (72) · `node tools/qa.js` (0 failures) · `node tools/party-e2e.js` (21) · `node tools/responsive-test.js` (0) · `node tools/click-test.js` (15).
