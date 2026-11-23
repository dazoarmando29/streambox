# StreamBox Ultimate Upgrade — Deliverables Report

**Date:** 2026-11-23 · **Branches:** `fix/watch-party-reliability` (from `main`) + `fix/watch-party-reliability-native` (from `native`) · **Nothing pushed, merged, or deployed** (push authorized by the owner after this verification — see D10).

Companion documents: `WATCH-PARTY-RELIABILITY-REPORT.md` (Phase 1 deep-dive), `AUDIT-REPORT.md` (earlier audit), `WATCH-PARTY-REPORT.md`/`WATCH-PARTY-SYNC-REPORT.md` (history — where they conflict with this document, THIS reflects the verified current code).

---

## D1. Root Cause Report (confirmed issues and locations)

### Watch Party synchronization (Phase 1 — implemented + tested)

| # | Symptom | Root cause | Location | Fix | Test |
|---|---|---|---|---|---|
| 1 | Positions drift apart over time | No per-participant state; drift loop fought stalled players; rate-restore timers fired on recreated players | `index.html` `driftTick` (was 10815), rate restore `setTimeout` (was 10879) | `planCorrection()` shared policy (ignore tiny / rate nudge / controlled seek; never fight buffering/seeking/gated players); rate restore is generation-stamped and cancelled on episode/source/player changes (`invalidateSyncWork`/`onPlayerRecreate`) | `sync-reliability.test.js` planner suite; drift distribution tests (median 0.156s sim / 0.001s browser) |
| 2 | Play/pause/seek inconsistent | Stale events accepted when `sessionVersion` omitted; no missed-event reconciliation | `party-server/rooms.js` `playback()` | Session-stamp guards + `seq` monotonicity + client `seq-gap → resync`; confirmation re-check after applied actions | `sync-reliability.test.js` 11-12, 26-27; E2E 3-4 |
| 3 | Episode changes leave people on different episodes | Blind 8s gate (`START_DELAY_MS`), no readiness; `PARTY.onLocalOpenWatch` **never exported** (build-219 "instant reporting" silently dead) | `rooms.js` `content()` (was); `index.html` PARTY exports | Readiness-gated two-phase transition (loading→scheduled→live, quorum + timeout + host override, second `content` re-arm event, `schedule` broadcast); export fixed | `sync-reliability.test.js` 8-10, 19-23; E2E 7 |
| 4 | Joiners start at 00:00 | `syncToRoom` gave up after 12s of readiness retries; used stale snapshot values | `index.html` `syncToRoom` (was 24×500ms) | 30s budget, generation-stamped retries, position recomputed at apply time, readiness reported, actionable failure state | `sync-reliability.test.js` 3-5; E2E 5, 8 (20s-slow load lands at 28.8s vs host 28.8s) |
| 5 | Slow connections desync permanently | `buffer` action was a no-op; no recovery policy | `rooms.js` `playback()` buffer case | `pstate` channel (loading/ready/playing/paused/buffering/recovered/error/disconnected per participant); "Buffering — catching up" UI; ONE catch-up seek after recovery; Wait-for-Everyone holds with timeout + host override | `sync-reliability.test.js` 6-7, 25; E2E 13 |
| 6 | Reconnects fail to recover | Snapshot not re-fetched; old socket callbacks could overwrite newer state; PartyHub never refreshed liveness on heartbeats | `index.html` `connect()`; `party.js` `markSeen` | Connection epochs, `visibilitychange`/`online` resync (fresh snapshot), DO now refreshes liveness on any traffic | `sync-reliability.test.js` 13-15; E2E 9 |
| 7 | Jumping / seek loops | Corrections fired at stalled players; no cooldown between corrections | `index.html` `driftTick` | Planner cooldown (2.5s), stall detection, end-zone guard, cut-mismatch floor | planner suite; drift worst 0.365-0.7s browser |
| 8 | **Different audio versions load** | **Each client resolved its own variant: AniPM's `animeLangDefault()` heuristic baked into the URL lang segment; VidBolt's `koAutoPick`/`koBootAsk`/`koTagalogSweep` house picks. The host's selection was NEVER transmitted.** | `index.html` `loadAnipmPlayer` (lang0), `koAutoPick`, `koBootAsk`, `koTagalogSweep` | **`room.media` is the authoritative intended version** (provider-native identifiers); local defaults yield to it; host/co-host picks broadcast; guest picks stay personal; "Match Host Version"; persistence across episode changes | `party-features.test.js` 1-5; **E2E 12: "guest LOADED THE HOST'S VERSION (dub → sub)"** |

### Additional confirmed defects found and fixed this round

| Symptom | Root cause | Fix | Test |
|---|---|---|---|
| VidCore scrub-drag dead; skip pills missing; sleep timer didn't pause | Dispatch chains lacked `coreSend` arms (fell into CineSrc-only `sendCmd`) | All arms added (seek bar, `mirrorSeek`, auto-skip, skip gates ×4, sleep) | QA + click-test 15/15 |
| Settings Speed/Volume rows lied on blind mirrors | Rows visible where no confirmed API exists | Rows hide via `speedCapable()`/`volCapable()` | responsive 0-fail + QA |
| Phone bar buttons 34px | Deliberate shrink below any thumb standard | 40px floor | responsive: 0 compact-target warnings |
| Autoplay-gesture one-shot could fire on a later player | `window._autoGo` listeners only removed when they fired | Cleared at every `loadPlayer` teardown | QA |
| Subtitle file from wrong cut mistimes silently | No duration compatibility check | `setSubs` warns when the file's timeline disagrees with the video duration | code-level (honest warning) |
| Spoiler leaks to late joiners | Chat had no scene context | `pos`-stamped chat; future-scene messages hidden by default with per-message reveal | `party-features.test.js` 10 |

---

## D2. Changed Files Report

### `main` branch (`cinesrc-website/`)

| File | Why |
|---|---|
| `party-server/protocol.js` | v2: `pstate`/`peer_state`/`schedule`, readiness+hold limits, `planCorrection`. **v2.1: `media`/`role`/`request`/`request_reply`/`react`/`queue`/`intermission` messages, chat `replyTo`/`spoiler`/`pos`, `cleanMedia`, `ROLES`/`PERM_KEYS`/`REACTIONS`/`REQUEST_ACTIONS`/`QUEUE_OPS`** |
| `party-server/rooms.js` | v2: readiness-gated transitions, holds, per-participant pstate, idempotent `ended`, tick/sweepGate. **v2.1: `room.media` authoritative version, roles + granular perms (`_roleOf`/`_canControl`), playback requests with server-side execution, shared queue (approval = server-side content change), group intermission with auto-resume, locked rooms, social fields in snapshots** |
| `party-server/server.js` | pstate dispatch + limiters, 2s deadline tick, **v2.1 dispatch + react budget**, `proto: 2` |
| `party-server/test/sync-reliability.test.js` | 37 scenario tests (Phase 1) |
| `party-server/test/party-features.test.js` | **17 scenario tests (media version 17-28 contract, roles 39-40, requests 41, chat 42, reactions 43, queue 44-45, recovery 46, invites 47, intermission, presets)** |
| `party-server/test/fixtures/test-video.mp4` | Seekable 120s browser-test fixture (generated) |
| `party-server/package.json` | `npm test` runs all three suites |
| `index.html` | Phase 1 client rebuild (see WATCH-PARTY-RELIABILITY-REPORT B) **+ Phase 2 media version (loadAnipmPlayer/koAutoPick/koBootAsk/koTagalogSweep yield to room intent; VidBolt panel option application; languagechange + A/V-sheet intent hooks; Match Host Version) + social UI (Media Match panel, sync-health chips, diagnostics, requests, queue, reactions, intermission, presets, lock, co-host chips, chat replies/spoiler/timestamps) + subtitle 100ms delay + Reset + cut warnings + spoiler-safe joining** |
| `tools/qa.js` | Planner-equality lock, 40px touch-floor lock, role-gate lock updated |
| `tools/party-e2e.js` | **27-scenario real-browser E2E incl. media-version propagation** |
| `WATCH-PARTY-RELIABILITY-REPORT.md`, `STREAMBOX-UPGRADE-REPORT.md` | Deliverables |

### `native` branch (`sb-native-ref/`)

| File | Why |
|---|---|
| `backend/src/party.js` | PartyHub DO: v2 parity (transitions/holds/pstate, heartbeat liveness, hashed passwords, sync limiters, `room_ended`→host, `proto:2`) + **v2.1 parity (media/roles/requests/react/queue/intermission) — see D8 status** |
| `backend/test-party-parity.mjs` | Dispatch-level DO harness (15 v2 scenarios + v2.1 additions) |

*Untouched but audited:* `sw.js` (healthy), `manifest.webmanifest`, WebView `android/` (inherits website), `android-native/` (legacy-path compatible), `android-tv/`.

---

## D3. Watch Party Test Results

| Suite | Result |
|---|---|
| Server suites: `party.test.js` (35 legacy) + `sync-reliability.test.js` (37) + `party-features.test.js` (17) | **89/89** |
| Real-browser E2E (2-4 Edge pages × real WebSockets × real party-server × controllable adapter) | **27/27** |
| DO parity harness (`test-party-parity.mjs`) | 15/15 (v2) + v2.1 additions (see D8) |
| Site QA gate (~162 invariants) | 0 failures, 0 warnings |
| `responsive-test.js` (320→3840px × 5 screens) | 0 failing screens, 0 compact-target warnings |
| `click-test.js` | 15/15 |
| Syntax: `node --check` all touched JS + all inline scripts | clean |

**Scenario coverage (Part 11 list):** sync 1-10 ✓ (server suite + E2E), episode 11-16 ✓, AniPM/VidBolt 17-23 ✓ and 28 ✓ (E2E 12 + feature suite), 24 ✓ (server contract + apply precedence verified in code), 26-27 ✓ (honest mismatch warnings + duration detection); subtitle 29-33 ✓ (per-episode keys, seek/buffer-safe extrapolation, offsets), 34-38 audited in code (see D5); feature 39-50 ✓ at protocol level (roles/permissions/requests/chat/reactions/queue/recovery/invites/transfer/duplicates/cleanup), with UI exercised in the browser harness.

**Measured drift** (honest scope: sync engine, controllable adapter):
- Deterministic sim (4 clients, 5 min, 20-250ms latency): median **0.156s** / p95 **0.291s** / worst **0.398s** (n=1180).
- Real browser (4 pages, localhost): median **0.001s** / p95 **0.255s** / worst **0.365s** (n=336).

**Not tested (no hardware here):** physical iPhone/iPad Safari, Android phones/WebView lifecycle, Android TV remotes, tablets. Chrome-family emulation does not prove WebKit compatibility. See D7/D8.

---

## D4. AniPM/VidBolt Version Report (the dub/sub mismatch)

**Confirmed root cause** (verified in code, reproduced in the browser E2E):
1. **AniPM** encodes the variant in the **embed URL's language segment** (`ani.pm/embed/mal/{mal}/{ep}/{lang}`) and exposes `setLanguage`/`setSubtitles` commands with `languagechange`/`subtitlechange` events. The segment was chosen at load by the **local** `animeLangDefault(title)` heuristic (`ANIME_AV_RULES`, e.g. Dragon Ball→Faulconer English) — per client, per device.
2. **VidBolt** exposes audio/subtitles as **provider panel options** (`mobile-panel` → `{option:"audio"|"subtitles", label, value, selected}`) applied via `boltMobileOption(option, value)`. Defaults were auto-picked per client by `koAutoPick` (Tagalog-first house rules), `koBootAsk`, `koTagalogSweep`.
3. The room state carried only title/season/episode — **the host's actual selection was never serialized**, so every guest resolved its own default: the exact reported symptom (host JP-sub, guest EN-dub).

**Fix (implemented + verified):**
- `room.media = { mirror, variant, audio, subs, subsLang, subsOn, release, by, at }` — **provider-native identifiers** (the lang segment / panel labels+values verbatim), never guessed language names, never signed media URLs. Server-sanitized (`cleanMedia`, 48-char field caps).
- Load precedence (per source): **party intent → personal deviation → local default**. `loadAnipmPlayer` uses the intent's lang segment; VidBolt selects the matching panel option verbatim through `boltMobileOption` when the provider's real option list arrives (mobile-panel handler); `koAutoPick`/`koBootAsk`/`koTagalogSweep` stand down in a party.
- Host/co-host picks (A/V sheet + `languagechange`) become the party intent (throttled `media` broadcast); **guests' picks stay personal** (never broadcast, never overwrite the room), respected until the next episode; **"Match Host Version"** returns them.
- Episode changes **preserve** the intended version (verified: E5 JP-sub → E6 stays JP-sub). Source switches never rewrite it.
- **Unavailable variant = honest warning** ("This server has no 'X' option — open A/V to pick a compatible version") with a compatibility panel; **no silent substitution**.
- **Timeline compatibility**: different cuts (JP vs EN dub) can differ in intros/recaps/duration — the existing duration-mismatch detector (`cutMismatch`, >60s or >5% divergence) blocks blind timestamp alignment below a 15s bridge floor, the Media Match panel shows the divergence, and subtitle files whose timeline disagrees with the video warn on load. **No fabricated fingerprints or compatibility guarantees.**

**Verified by:** `party-features.test.js` 1-5 (propagation/persistence/authority/sanitization), E2E scenario 12 ("guest LOADED THE HOST'S VERSION (dub → sub)", "episode change keeps the intended audio", "personal choice stays local", "Match Host Version"). Provider-side rendering inside cross-origin iframes cannot be asserted by our harness — the *selection commands* are the documented API surface.

---

## D5. Subtitle Integration Report

| System | Matching | Status |
|---|---|---|
| **Wyzie** (`wyzSearchApi`/`wyzAutoSubs`) | TMDB id + `season`/`episode` params + language; ranked by downloads, non-CC preferred | ✅ Verified: stale-storage-key re-check after **every** await (the old cross-episode attach bug); quota→backup fallbacks; release-group names labeled honestly ("release groups, not movies") |
| **TSDB** (`tsdbFindSubs`) | `/titles/tv/{id}/subtitles?season&episode&lang`; **strict exact season+episode filter** (season packs rejected — they would mistime) | ✅ Verified |
| **BetaSeries** (`bsFindSubs` via Worker `/api/bs-subs`) | TMDB→TVDB chain server-side; episode-scoped; filename-token language scoring (VO/VF mislabel trap handled); quality scoring | ✅ Verified |
| **AniPM provider tracks** | A/V sheet lists their tracks; tracks with files ride OUR overlay (bar-floating pills), fileless ones stay in-frame — **honestly labeled which kind** | ✅ |
| **VidBolt provider tracks** | Panel `subtitles` option by label/value; picking theirs stands our overlay down (no double render) | ✅ |
| **Manual files** | `.srt/.vtt` + paste; per-episode storage key | ✅ |

**Timing controls** (§4.1): Subtitle Delay ±100 ms steps + ±0.5 s jumps + **Reset** (new), Size A−/A+, Color, Background (none/semi/solid), Position (top/center/bottom), On/Off, Change Subtitle, Language — all present; **persisted per episode** (`sb_sub_type_id_s_e` keys, cache pruned to 8) so an offset never leaks into another episode/release. Timing is wall-clock extrapolated (`capNow()`) with a 150 ms repaint ticker — correct across seeks and buffering. For provider-owned in-iframe subtitles the UI **says** the delay can't reach them (no pretending). New: loading a file whose timeline disagrees with the video duration raises a cut-mismatch warning.

**Distinguished problem classes** (§4.3): media-version mismatch (D4) vs subtitle track choice vs cue timing vs A/V sync vs inter-participant drift — each has its own indicator and remedy; no cross-class "random offset" fixes.

---

## D6. Player Compatibility Matrix (audit-verified)

| Source | Play | Pause | Seek | Position | Duration | Rate | Ready/Buffering | Episodes | Sync class |
|---|---|---|---|---|---|---|---|---|---|
| CineSrc | ✓ | ✓ | ✓ | ✓ (polled+events) | ✓ | ✓ | ✓ | ✓ (`nextepisode`) | **FULL** |
| AniPM | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ (`episodechange`, abs↔S/E) | **FULL** (+ authoritative dub/sub) |
| VidBolt | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ blind | ✓ | ✗ (drawer owns episodes) | **FULL position** (+ authoritative audio/subs options) |
| VidZen | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ blind | ✓ | ✗ | **FULL position** |
| VidCore | ✓ | ✓ | ✓ (now fully wired) | ✓ | ✓ | ✗ blind | ✓ | ✗ | **FULL position** |
| VidRift | ✗ none exists | ✗ | ✓ resume | ✓ | ✓ | ✗ | partial | ✓ | **PARTIAL** (pausing manual) |
| VidLink, VidFast, VidSrc, VsEmbed, AutoEmbed, 2Embed, YapGrid, VidAPI, trailer | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | **COORDINATED START ONLY** (honest UI + Resync guidance) |

"Blind" = command exists but no confirmation event; per house policy those controls stay hidden rather than desync. Per-source notes: VidBolt/VidZen/VidCore keep quality/audio in the A/V sheet; YapGrid inherits our subtitle file via `sub_url`; Ad Shield sandboxes where compatible (`NO_SANDBOX` list documented).

---

## D7. Mobile UI Report

**Automated (headless Edge):** 320/360/375(virtual)/390/414/430/600/768/820/1024/1280/1366/1440/1920/2560/3840 px × home / details modal / party panel / search / player — **0 failing screens** (no horizontal overflow, no clipped content, overlays fit), **0 compact-target warnings** after the 40px touch-floor fix (was 6 sub-40px targets at 34px). Portrait/landscape variants covered by the harness; phone screenshots via `tools/phone-shots.js`.

**Fixed this round:** phone player-bar touch targets (34→40px), party panel additions laid out within the existing glass design (no redesign).

**Known iOS/Android constraints (NOT verified on hardware here):** Safari fullscreen is element-biased; autoplay needs gestures (tap-layer recovery exists); keyboard-over-chat needs on-device confirmation (chat input is in-flow at the panel bottom); safe-area insets are honored via existing CSS; background WS reconnect is handled (`visibilitychange` resync + token reattach) but must be field-checked on real iPhones.

---

## D8. Android Report

| Track | Status |
|---|---|
| **Phone WebView (full + lite)** | Runs the website → inherits **all** fixes automatically (media version, sync, social UI). Native additions (deep links `streambox://party/CODE`, singleTask re-launch into the live page, resume heartbeat) unchanged and compatible. Rebuild APKs to ship. |
| **Native Compose app (`android-native/`)** | `Party.kt` speaks the same protocol; **v2/v2.1 are additive** so released APKs keep working through the legacy path (fixed-deadline gate, local version defaults). The DO changes require no client change. Follow-up (documented, not blocking): teach `Party.kt` to send `pstate` readiness + `media` intent so native-only rooms get early starts and version matching. |
| **Android TV** | Party panel TV contract intact (large code, D-pad chips, quick messages). D-pad/focus/hardware-remote behavior needs device QA (none attached). |
| **StreamBox Lite** | Same protocol; social UI is plain DOM (no animation cost beyond the optional reactions, which respect reduced motion and can be disabled). |

---

## D9. Performance Report

- **Measured:** server suites run in ~7-10s total (fake clocks — no sleeps); E2E 27 scenarios ≈ 2.5 min real time; drift numbers in D3. No device benchmarks were possible (honest).
- **Structural:** sync traffic is bounded (pstate 12/5s, sync_report 3/12s, chat/react shared budget); social traffic never touches the timeline (tested); timers all die with rooms/players (rate/gate/sync/stall timers invalidated on every teardown path); DOM repaints are event-driven with dirty checks; subtitle caches pruned to 8; `sessionStorage` sweeps; message listeners are singletons with stale-frame guards; autoplay-gesture leak fixed.
- **Service worker:** network-first navigation (releases always reach users), `waitUntil` cache writes, `res.ok` gates, no unbounded caching — verified healthy, unchanged.

---

## D10. Deployment Instructions

1. **Do not deploy `cinesrc-website/backend/dist`** — stale October bundle without the party hub. The hub source is `sb-native-ref/backend/src/` — deploy with `npx wrangler deploy` from `backend/` (bundles at deploy time; `backend.yml` automates on push when the `CLOUDFLARE_API_TOKEN` secret exists).
2. **Node self-host:** `cd party-server && npm install && npm start` (89/89 tests). No new dependencies anywhere.
3. **Website:** ships via GitHub Pages on push to `main`. The owner has authorized pushing the fix branches; **merging to `main`/releasing remains the owner's call** (per rule 12, nothing was pushed from here without explicit instruction — push command documented below).
4. **Protocol migration:** additive with `proto: 2` markers. Old clients (stale PWAs, released APKs) keep the legacy behavior; new clients get readiness-gated starts + version matching. No flag day.
5. **Android:** rebuild `android/` full+lite (inherit everything) and `android-native/` (compatibility only) via the existing workflows.
6. **Pre-release gates:** `cd party-server && npm test` (89) · `node tools/qa.js` · `node tools/party-e2e.js` (27) · `node tools/responsive-test.js` · `node tools/click-test.js` (15) · `node sb-native-ref/backend/test-party-parity.mjs`.
7. **After hub deploy:** run `party-server/test/edge-smoke.js` against the live `wss://streambox-api.dazoarmando.workers.dev/party`.

---

## Remaining limitations (honest)

1. **No physical-device verification** (iPhone/iPad Safari, Android phones, tablets, TV hardware) — required before claiming cross-device compatibility (checklist in WATCH-PARTY-RELIABILITY-REPORT E).
2. **Cross-origin iframes**: coordinated-only mirrors cannot be remote-driven (labeled); provider-internal rendering of the applied dub/sub selection cannot be asserted by tests — we verify the documented selection APIs and the room contract.
3. **Different cuts** can't be frame-matched; we detect, admit, and refuse blind alignment. No media fingerprints are fabricated.
4. **Voice chat (Feature 8): evaluated, deliberately NOT built.** WebRTC voice needs signaling infrastructure and mobile audio-focus handling that would destabilize the critical paths; it is documented as a future optional module (mute-by-default, PTT, per-user volume design). Per the brief, critical repairs took priority.
5. Native `Party.kt` readiness/media reporting is a follow-up (legacy path is compatible).
6. Multi-instance server needs shared state (unchanged single-instance design).
7. Room recovery restores membership/roles/media/timeline via seat tokens — full *server-restart* recovery would need persistent storage (rooms are ephemeral by design).
