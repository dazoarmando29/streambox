# Watch Party Sync Rebuild + Ad Protection — Report (0.89 / build 219 / APK 2.17)

## Root causes found (from real reported behavior)

| Symptom | Root cause | Fix |
|---|---|---|
| "constantly seeking / drift gets worse" | drift corrector hard-SEEKED for every drift >0.35s (both branches of the correction were seeks) | subtle 4% rate nudges for 0.25–2s drift (via `window.__sb.rate`), one clean seek only past 2s; seek-only bridges correct gently and rarely; 4s floor between corrections |
| "we start at different times / someone stays on the old episode" | episode changes were detected by a 5s poll and each device started when ITS player happened to load | **scheduled start**: the server stamps a shared gate (updatedAt = now+8s on the server clock); every device holds at 00:00 until the gate, then plays together. Live-verified on the deployed hub (gate 7.66s out, position pinned at 0) |
| "joiners land at 00:00 / wrong spot" | joining opened the video but never applied the authoritative position | `syncToRoom()`: waits for real player readiness (never fires into a loading player), then lands on the room timeline — 35 minutes in joins at 35 minutes |
| "pause/seek don't reach everyone reliably" | corrections only triggered past 3s; clock drift from single-stamp estimates | correction threshold 0.6s; clock anchored on lowest-RTT heartbeats (asymmetric-route noise filtered out); event seq + echo suppression unchanged |
| "commands in wrong order / loops" | (already fixed in 0.83+) seq numbers + sessionVersion + suppress windows | verified intact; content changes now report instantly from openWatch instead of the poll |

## Implemented

**Servers (both the Node `party-server` and the deployed PartyHub Durable Object):** scheduled episode starts (future-dated `updatedAt` survives state snapshots), START_DELAY 8s, `expectedPosition` clamps to 0 before the gate (shared math, unit-tested).

**Website + WebView APKs (full/lite) + Native app (phone/TV):** syncToRoom late-join sync, scheduled-gate handling, nudge-first drift correction, live latency/drift diagnostics on the party pill, instant episode reporting, join-only home door (code + password) vs create-on-movie-page (room auto-carries that movie/episode).

**Ad protection (honest scope):** wrapper blocklist grew with curated ad-tech/popunder/tracking hosts (media-first ordering preserved — media always rides; per-source sandbox compatibility preserved — sandbox-breaking players like VidBolt stay unsandboxed, which is the actual fix for "sandbox errors when filtering"). Website keeps the Ad Shield (sandbox + popup/redirect blocking). In-stream (SSAI) ads are NOT claimed removable.

**Sandbox-detection verdict:** the error class comes from (a) iframe `sandbox` attributes that some players refuse (solved per-source via NO_SANDBOX) and (b) external blockers killing player scripts (our filter never touches player/media resources). No identity spoofing; no DRM/access bypass.

## Tests

- party-server protocol suite: **24/24** (incl. new scheduled-start gate test)
- Live edge probes: basic flow **PASS**, scheduled gate **PASS** (7.66s gate, position 0)
- QA gates: website **140/140**, native **85/85**
- Builds: native `app-debug.apk` 0.89 SUCCESS; WebView APKs 2.17 SUCCESS (CI)

## Measured synchronization performance — honest

Server-side timing verified live (gate scheduling exact on the server clock; drift math unit-tested). **No two-device drift measurement was possible in this environment** (no devices attached). The architecture targets the brief's 100–250ms window (nudges engage at 250ms) but real-device drift numbers are UNMEASURED — run the two-device session to confirm.

## Remaining limitations

- Cross-device drift needs field measurement; coordinated-only mirrors (VidLink/VidFast/YapGrid/etc.) show honest "manual sync" — no remote control possible cross-origin.
- Different edits/durations across mirrors can't be frame-matched (by design we prefer same-source rooms).
- SSAI/in-stream ads can't be stripped without breaking media (not attempted).
- A parallel workstream ("audit/polish" merges) landed 0.88/2.16 during this work; this release rides cleanly on top (QA/tests green on the combined tree).
