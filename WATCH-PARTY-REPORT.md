# StreamBox Watch Party — Implementation Report

**Branch:** `feature/watch-party` (from `audit/stability-2026-11`, build 215) · **Date:** 2026-11-05

---

## 1. Watch Party Architecture

```
[Website / WebView APKs]                 [party-server (Node + ws)]
  PARTY module (index.html)  <-- WS -->   server.js  : /party transport, origin check,
  - room UI + chat (glass modal)           dispatch, broadcast, sweep timer
  - sync engine (drift corrector)        rooms.js   : authoritative room + playback state,
  - mirror adapters (play/pause/seek)     permissions, rate limits, TTL/reconnect model
  - reconnect/backoff/heartbeat          protocol.js: validation + expectedPosition() math
```

- **Transport:** WebSocket (JSON envelope `{ t, ... }`), one connection per page, `ws` is the only dependency. Server = single source of truth for playback *state* (position/rate/status stamped on the server clock, monotonic `seq`, `sessionVersion` per episode). **No media bytes ever cross the server** — every device plays its own stream through its own legitimate session.
- **Sync math (shared, tested):** `expectedPosition = position + (serverNow − updatedAt)·rate` while playing. Clients keep an NTP-lite clock offset (20s heartbeats, symmetric-latency assumption — documented, not invented).
- **Room model:** 128-bit room ids, 6-char codes (unambiguous alphabet), optional salted-hash password, max participants (default 8), `allowControl`, `chatEnabled`, host seat + grace/transfer policy, participant seats with resume tokens (5-min "recent seat" memory restores identity *and* the host seat; kicked tokens never restore).
- **Deployment shape:** ephemeral in-memory rooms (no database — rooms are short-lived by design and swept by TTL). Single-instance; multi-instance would need a shared store (documented, not implemented).

## 2. Features Implemented

Create room (name/password/max/guest-controls/chat) · join by **invite link** (`https://dazoarmando29.github.io/streambox/?party=CODE` — real origin, code only, never a password) or **room code** (+ password when set) · participant list with connection state, host star, mute icon, host kick · host controls (guest playback permission, chat on/off, End Party behind a confirm) · synchronized **play/pause/seek/episode change** on controllable players · **late join** syncs to the party position and never resets others · **drift correction** (nudge <3s, hard seek >3s, 3s correction floor) · **rejoin** after disconnect (backoff+jitter, token reattach, seat/host restore) · **chat** (timestamps, emoji-as-text, dedupe by id, auto-scroll unless reading back, rate limit, mute/kick, TV quick messages) · Resync · copy invite/code · session restore after refresh · `?party=` deep links · honest **sync-mode pills** (FULL / PARTIAL / COORDINATED START).

## 3. Website Integration

Entry points: **Watch Party** button on the detail modal and the watch sidebar (same `glass-pill` styling as My List/Share); live status pill in the sidebar while in a room. The room panel is one modal in the house style (lobby ↔ room views). Sync hooks wrap `ctlToggle`/`ctlSeekRel`/`mirrorSeek` + the seek-bar commit; host episode changes propagate via a content-key diff; non-hosts suppress local auto-advance (host drives episodes → no double-advance).

**Tests:** QA gate **138/138 green** (incl. new locks 133–138: adapter honesty, chat escaping, invite hygiene, server-side permissions, reconnect shape, party-server syntax). `node --check` passes on every inline script. Protocol behavior is covered by the server suite (below). *Not run here: real-browser session (no browser in this environment).*

## 4. Android Phone Integration

Both **full and lite APKs** run the website, so the complete party experience ships in them automatically (same UI, touch chat panel, bottom-safe inputs). Native additions:
- `streambox://party/CODE` deep links via **SplashActivity** (keeps `MainActivity` `exported=false`), sanitized code (6 alnum chars), instant launch on invite taps.
- `singleTask` relaunch routes the code into the **live page** (`PARTY.open(code)`) — never a second WebView/connection (duplicate-connection rule).
- Background/resume: visibility resume sends a heartbeat (never reconnects while the socket is alive); if the socket died, reconnect+token reattach restores the seat.

**Verified:** both flavors `assembleDebug` **BUILD SUCCESSFUL**, manifest contains the `streambox://party` filter (`aapt dump xmltree`), signed with the stable preview key (`apksigner`). *Not run: on-device lifecycle/rotation tests (no device attached).*

## 5. Android TV Integration

TV mode reuses the same panel with the TV contract: enlarged room code (letter-spaced, 2.2rem), 56px quick-message/chat targets, `focusable`/`data-focusable` chips that work with the existing D-pad engine, read-and-quick-reply chat (typing never required), Esc/Back closes the panel through the existing popup chain. Room codes are designed for 10-foot reading.

**Verified:** code-level + QA TV checks green. *Not run: physical remote testing (no TV hardware).*

## 6. Streaming Source Compatibility (audit-verified from the real bridges)

| Source | play | pause | seek | time events | Class |
|---|---|---|---|---|---|
| CineSrc | ✓ | ✓ | ✓ | ✓ | **Full sync** |
| AniPM | ✓ | ✓ | ✓ | ✓ | **Full sync** |
| VidBolt | ✓ | ✓ | ✓ | ✓ | **Full sync** |
| VidZen | ✓ | ✓ | ✓ | ✓ | **Full sync** |
| VidRift | ✗ (none exists) | ✗ | ✓ | ✓ | **Partial** — seeks + play follow; pausing is manual |
| VidLink, VidFast, VidSrc, VsEmbed, AutoEmbed, 2Embed, YapGrid, VidAPI, VidCore | ✗ | ✗ | ✗ | ✗ | **Coordinated start only** — same episode at the same moment; Resync shows the party time and suggests switching to a synced source |
| Trailer (YouTube) | — | — | — | — | **Unsupported** (excluded from party sync) |

The UI labels the mode honestly at all times (QA lock 133). No cross-origin restrictions were bypassed.

## 7. Synchronization Results

- **Deterministic (measured in tests):** server-authoritative state converges across 3 simulated clients (same `seq`, same position); late-joiner position matches the party within the test window; stale `sessionVersion` seeks are rejected and never reach peers; duplicate seeks are idempotent; paused state freezes the expected position while playing advances at `rate` (exact assertions on `expectedPosition`).
- **Not measured here:** real drift numbers on real players/devices — no browser or device exists in this environment, and third-party iframes can't be driven from a test harness honestly. The thresholds (`SYNC_NUDGE 0.35s`, `SYNC_SEEK 3.0s`, `SYNC_MIN_GAP 3.0s`) are configurable constants (`CFG`) ready for field tuning; no precision claims are made beyond the tested math.

## 8. Security Review

- **Access control is server-side** (QA lock 136): playback requires host or `allowControl`; content change/kick/mute/settings/end are host-only (6 host gates in `rooms.js`).
- Room ids are 128-bit random; short codes use an unambiguous alphabet with **per-IP guessing lockout** (12 attempts / 5 min).
- Passwords: per-room salted SHA-256, compared server-side, **never sent to any client, never in URLs** (QA lock 135).
- Chat: plain text only (control chars stripped, 300-char cap), escaped on render (QA lock 134), rate limited (6/10s), mute/kick enforced server-side; **kicked resume tokens can never restore** (tested).
- Transport: WebSocket upgrade checks an origin allowlist (`PARTY_ALLOWED_ORIGINS`), 8KB frame cap, per-connection message rate limit, no secrets in code (config via env), TLS expected via reverse proxy.
- Privacy: memberships visible only to room members; minimal data (display names, playback state); rooms + recent seats expire on TTLs (24h max age).
- Media legality: no redistribution — each participant uses their own playback session (invites are not media authorization).

## 9. Performance Results

- Non-participants pay **zero** cost: the party client is lazy (no connection until used), one heartbeat/20s and one drift tick/5s while in a room only, `sync_report` server-side is fire-and-forget (never rebroadcast).
- Message discipline: seq dedupe, id dedupe (chat), 3s correction floor prevents seek storms; chat history bounded (50 server / 50 client); participants/chips repainted only on events.
- Single connection enforced (QA lock 137); all timers cleared on leave; sessionStorage-only session data.
- Lite APK keeps its calm behavior (no new animations added).
- *Not measured here:* CPU/memory profiles on devices (no devices). Code paths are event-driven with bounded arrays by construction.

## 10. Files Created or Modified

**Created:** `party-server/package.json`, `party-server/package-lock.json`, `party-server/protocol.js`, `party-server/rooms.js`, `party-server/server.js`, `party-server/test/party.test.js` (23 tests), `WATCH-PARTY-REPORT.md`.

**Modified:** `index.html` (PARTY module + panel markup + CSS + hooks + deep-link join, BUILD 215), `tools/qa.js` (checks 133–138), `android/app/src/main/java/com/streambox/app/MainActivity.java` (`partyCode`, `readPartyIntent`, `onNewIntent` routing), `android/app/src/main/java/com/streambox/app/SplashActivity.java` (deep-link entry, instant party launch), `android/app/src/main/AndroidManifest.xml` (`streambox://party` filter), `version.txt`.

## 11. Deployment Instructions

**Server** (any host/VM; Node ≥ 18):
```bash
cd party-server && npm install && npm start        # PORT=8787 by default
# restrict origins: PARTY_ALLOWED_ORIGINS="https://dazoarmando29.github.io" npm start
```
Put TLS in front (nginx/Caddy/load balancer) and proxy `wss://<host>/party-ws` → `127.0.0.1:8787/party`. Health check: `GET /healthz`.

**Website:** set the endpoint once (top of the PARTY module) — `window.SB_PARTY_WS = "wss://your-host/party-ws"` (or `localStorage.streambox_party_ws`), or serve the site behind the same proxy so the same-origin default works. Then deploy `index.html` as usual (Pages).

**Android:** rebuild `android/` (full + lite) via the existing `android-apk.yml` (QA gate runs first). Invites open in-app through `streambox://party/CODE`; https `?party=` links land in the site as before.

## 12. Remaining Limitations

1. **Native Compose app (`android-native/`)** has no party screen yet — the shipping phone/TV apps (WebView full/lite) are fully covered; the preview Compose track needs its own room UI (protocol is ready for it).
2. **Real-device cross-matrix** (website↔phone↔TV, 3+ devices, sleep/wake, network switches) was verified at protocol level with simulated clients only — no devices/emulators were available. Field validation needed.
3. **Drift constants** are principled but untuned on real embeds; tune `CFG.SYNC_*` after real-world observation.
4. **VidRift / coordinated-only mirrors** can't give full sync (no APIs) — by design, clearly labeled; recommending synced sources in the Resync hint.
5. **Single-instance server** — multi-instance needs shared room state (e.g. Redis) or sticky routing; documented, not implemented.
6. **NTP-lite offset** assumes symmetric latency; residual error is absorbed by the drift corrector but sub-second global precision is not claimed.
7. Chat has no per-message history persistence (ephemeral by design); unread counts reset on panel open.
