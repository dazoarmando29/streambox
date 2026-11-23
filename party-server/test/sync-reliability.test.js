"use strict";
/* StreamBox Watch Party - synchronization reliability suite.
   Reproduces the real reported failure modes against the AUTHORITATIVE room
   state machine with a FAKE CLOCK (no sleeps, no flakiness):

     joins landing at 00:00, slow/buffering participants falling behind,
     episode changes desyncing the room, stale/duplicate/out-of-order events,
     reconnection, background returns, unauthorized control, dead players
     trapping the room, and leftover synchronization work after leaving.

   Where a scenario is client-side (drift correction policy, adapter
   capabilities) the SHARED pure functions are tested - the website copies
   them verbatim (QA lock keeps the copies identical).

   Also measures the drift distribution of N simulated clients following the
   room timeline through the shared correction policy (median/p95/worst). */

const test = require("node:test");
const assert = require("node:assert");
const { RoomManager } = require("../rooms");
const { expectedPosition, planCorrection, LIMITS } = require("../protocol");

const T0 = 1_700_000_000_000;

/* ---------- deterministic harness ---------- */

function mkManager(ttl = {}) {
  const clock = { t: T0 };
  const rm = new RoomManager({ now: () => clock.t, ...ttl });
  return { rm, clock };
}

function person(name) {
  return { id: "p-" + name, name, token: "tok-" + name, conn: { tag: name } };
}

/* A room with `members` participants: host + (members-1) guests. */
function makeParty({ members = 2, allowControl = false, ttl } = {}) {
  const { rm, clock } = mkManager(ttl);
  const host = person("host");
  const created = rm.create(host, { roomName: "Test party", allowControl, maxParticipants: 16 });
  const rid = created.room.roomId;
  const code = created.room.code;
  const people = { host };
  for (let i = 1; i < members; i++) {
    const p = person("g" + i);
    const r = rm.join(p, { code });
    assert.ok(r.room, "guest g" + i + " must join");
    people["g" + i] = p;
  }
  const ids = {};
  for (const [k, p] of Object.entries(people)) ids[k] = p.id;
  return { rm, clock, rid, code, people, ids, host };
}

const of = (events, t) => (events || []).filter((e) => e.t === t);

/* Advance the fake clock and run the periodic tick (the production server
   runs tick every 2s and sweep every 10s). Pass { sweep: true } to also run
   the expiry sweep - only for tests that keep their users "alive". */
function advance(ctx, ms, step = 1000, opts = {}) {
  const batches = [];
  let left = ms;
  while (left > 0) {
    const dt = Math.min(step, left);
    ctx.clock.t += dt;
    batches.push(...ctx.rm.tick());
    if (opts.sweep) batches.push(...ctx.rm.sweep());
    left -= dt;
  }
  return batches;
}

function pbState(ctx) {
  return ctx.rm.snapshot(ctx.rm.rooms.get(ctx.rid), "x").playback;
}

/* ---------- 1-2. join + play together, 3+ participants ---------- */

test("two participants join and play together: one authoritative state, same seq", () => {
  const ctx = makeParty({ members: 2 });
  const sv = pbState(ctx).sessionVersion;
  const r = ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "play", sessionVersion: sv });
  assert.ok(!r.error && !r.stale, "host play accepted");
  const ev = of(r.events, "playback")[0];
  assert.ok(ev, "a playback event is broadcast");
  assert.strictEqual(ev.seq, pbState(ctx).seq, "broadcast seq == canonical seq");
  assert.strictEqual(ev.state.status, "playing");
  // both participants observe the same state through the same snapshot math
  const snap = ctx.rm.snapshot(ctx.rm.rooms.get(ctx.rid), ctx.ids.g1);
  assert.strictEqual(snap.playback.seq, ev.seq);
  assert.strictEqual(snap.playback.status, "playing");
  // and both resolve the same expected position at the same server time
  ctx.clock.t += 10_000;
  assert.strictEqual(
    expectedPosition(ctx.rm.rooms.get(ctx.rid).playback, ctx.clock.t),
    expectedPosition(ctx.rm.rooms.get(ctx.rid).playback, ctx.clock.t)
  );
});

test("three or more participants converge on one state after play/pause/seek", () => {
  const ctx = makeParty({ members: 4 });
  const sv = pbState(ctx).sessionVersion;
  ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "play", sessionVersion: sv });
  advance(ctx, 30_000);
  ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "seek", position: 500, sessionVersion: sv });
  ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "pause", sessionVersion: sv });
  const room = ctx.rm.rooms.get(ctx.rid);
  for (const id of Object.values(ctx.ids)) {
    const snap = ctx.rm.snapshot(room, id);
    assert.strictEqual(snap.playback.status, "paused");
    assert.strictEqual(snap.playback.position, 500, "every member resolves the same position");
    assert.strictEqual(snap.playback.seq, room.playback.seq);
    assert.strictEqual(snap.playback.sessionVersion, sv);
  }
});

/* ---------- 3-5. join positioning ---------- */

test("late join during playback lands on the room timeline (35 minutes in -> ~minute 35)", () => {
  const ctx = makeParty({ members: 2 });
  const sv = pbState(ctx).sessionVersion;
  ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "play", sessionVersion: sv });
  advance(ctx, 35 * 60_000, 30_000);
  const late = person("late");
  const r = ctx.rm.join(late, { code: ctx.code });
  assert.ok(r.room, "late joiner gets in");
  const pos = r.room.playback.position;
  assert.ok(Math.abs(pos - 35 * 60) < 30, "snapshot position tracks the room (got " + pos + ")");
  assert.strictEqual(r.room.playback.status, "playing");
  // the JOINER'S stale snapshot position must be re-derivable at any later
  // apply time - that is what expectedPosition() is for (the client recomputes
  // at apply time instead of using the frozen snapshot value)
  ctx.clock.t += 20_000; // 20s of "loading" before the player is ready
  const at = expectedPosition(ctx.rm.rooms.get(ctx.rid).playback, ctx.clock.t);
  assert.ok(Math.abs(at - (35 * 60 + 20)) < 2, "authoritative position is recomputed after the load, not the stale snapshot");
});

test("late join while paused stays paused at the exact position", () => {
  const ctx = makeParty({ members: 2 });
  const sv = pbState(ctx).sessionVersion;
  ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "play", sessionVersion: sv });
  advance(ctx, 600_000, 30_000);
  ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "pause", position: 613, sessionVersion: sv });
  const late = person("late");
  const r = ctx.rm.join(late, { code: ctx.code });
  assert.strictEqual(r.room.playback.status, "paused");
  assert.strictEqual(r.room.playback.position, 613, "paused room never advances");
  advance(ctx, 120_000, 30_000);
  const r2 = ctx.rm.join(person("late2"), { code: ctx.code });
  assert.strictEqual(r2.room.playback.position, 613, "still frozen minutes later");
});

test("slow participant takes 20s to load: room starts on time, they land on the timeline (never 00:00)", () => {
  const ctx = makeParty({ members: 3 });
  ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "play", sessionVersion: 1 });
  // host switches episode; g1 loads fast, g2 needs 20 seconds
  const r = ctx.rm.content(ctx.rid, ctx.ids.host, { type: "tv", tmdbId: 42, season: 1, episode: 2 });
  const sv = r.events.find((e) => e.t === "content").sessionVersion;
  assert.strictEqual(sv, 2, "content session version bumped");
  ctx.rm.pstate(ctx.rid, ctx.ids.g1, { st: "loading", sv });
  ctx.rm.pstate(ctx.rid, ctx.ids.g2, { st: "loading", sv });
  ctx.rm.pstate(ctx.rid, ctx.ids.g1, { st: "ready", sv });
  // g2 is still loading at the readiness deadline -> the room must move on
  advance(ctx, LIMITS.READY_TIMEOUT_DEFAULT_MS + 1000);
  const room = ctx.rm.rooms.get(ctx.rid);
  assert.strictEqual(room.gate.phase, "live", "transition resolved despite the slow participant");
  assert.strictEqual(room.playback.sessionVersion, 2);
  // g2 becomes ready 20s after the change: the authoritative position has
  // advanced - a correct client lands THERE, not at 00:00 and not at the
  // stale snapshot it received while loading
  ctx.clock.t = T0 + 20_000;
  const snap = ctx.rm.snapshot(room, ctx.ids.g2);
  assert.ok(snap.playback.position > 0, "room timeline already moved (got " + snap.playback.position + ")");
  assert.strictEqual(snap.playback.sessionVersion, 2, "joiner applies the CURRENT session");
});

/* ---------- 6-7. buffering behavior ---------- */

test("one participant buffers repeatedly: default policy never disturbs the room timeline", () => {
  const ctx = makeParty({ members: 3 });
  const sv = pbState(ctx).sessionVersion;
  ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "play", sessionVersion: sv });
  advance(ctx, 100_000, 5000);
  const before = pbState(ctx);
  for (let i = 0; i < 5; i++) {
    const r = ctx.rm.pstate(ctx.rid, ctx.ids.g1, { st: "buffering", sv, position: 100 + i });
    assert.strictEqual(of(r.events, "playback").length, 0, "buffering must never touch the timeline in move mode");
    assert.ok(of(r.events, "peer_state").length >= 1, "the room SEES the buffering participant");
    advance(ctx, 2000);
    ctx.rm.pstate(ctx.rid, ctx.ids.g1, { st: "recovered", sv });
    advance(ctx, 3000);
  }
  const after = pbState(ctx);
  assert.strictEqual(after.status, "playing", "the party keeps moving");
  assert.strictEqual(after.seq, before.seq, "no playback events were generated");
  assert.ok(after.position > before.position, "the timeline is untouched and advancing");
});

test("participant resumes after buffering: recovery is reported and the room state stays authoritative", () => {
  const ctx = makeParty({ members: 2 });
  const sv = pbState(ctx).sessionVersion;
  ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "play", sessionVersion: sv });
  advance(ctx, 30_000);
  ctx.rm.pstate(ctx.rid, ctx.ids.g1, { st: "buffering", sv });
  advance(ctx, 15_000); // 15s stall
  const r = ctx.rm.pstate(ctx.rid, ctx.ids.g1, { st: "recovered", sv, position: 15 });
  assert.ok(of(r.events, "peer_state").some((e) => e.st === "recovered"), "recovery reported");
  // the recovering client recomputes the authoritative position at recovery
  // time (the room did NOT wait for them in move mode)
  const target = expectedPosition(ctx.rm.rooms.get(ctx.rid).playback, ctx.clock.t);
  assert.ok(target > 30, "authoritative timeline moved past the stall (got " + target + ")");
});

test("wait-for-everyone: a buffering participant holds the room, recovery releases it", () => {
  const ctx = makeParty({ members: 3 });
  const s = ctx.rm.settings(ctx.rid, ctx.ids.host, { waitMode: "everyone" });
  assert.ok(of(s.events, "settings")[0].waitMode === "everyone");
  const sv = pbState(ctx).sessionVersion;
  ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "play", sessionVersion: sv });
  advance(ctx, 20_000);
  const holdAt = expectedPosition(ctx.rm.rooms.get(ctx.rid).playback, ctx.clock.t);
  const r = ctx.rm.pstate(ctx.rid, ctx.ids.g1, { st: "buffering", sv });
  const pauseEv = of(r.events, "playback")[0];
  assert.ok(pauseEv && pauseEv.auto === true && pauseEv.action === "pause", "room auto-pauses for the stalled viewer");
  assert.strictEqual(pauseEv.state.position, holdAt, "paused at the AUTHORITATIVE position, not the stalled player's timestamp");
  advance(ctx, 10_000);
  assert.strictEqual(pbState(ctx).status, "paused", "room is still holding");
  assert.strictEqual(pbState(ctx).position, holdAt, "held room never advances");
  const rel = ctx.rm.pstate(ctx.rid, ctx.ids.g1, { st: "recovered", sv });
  const playEv = of(rel.events, "playback")[0];
  assert.ok(playEv && playEv.auto === true && playEv.action === "play", "room resumes when the viewer recovers");
  advance(ctx, 5000);
  assert.strictEqual(pbState(ctx).status, "playing");
});

test("buffering participant NEVER becomes ready: the hold times out and the room moves on", () => {
  const ctx = makeParty({ members: 3 });
  ctx.rm.settings(ctx.rid, ctx.ids.host, { waitMode: "everyone", holdTimeoutMs: 10000 });
  const sv = pbState(ctx).sessionVersion;
  ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "play", sessionVersion: sv });
  advance(ctx, 10_000);
  ctx.rm.pstate(ctx.rid, ctx.ids.g1, { st: "buffering", sv });
  assert.strictEqual(pbState(ctx).status, "paused");
  const batches = advance(ctx, 12_000);
  assert.strictEqual(pbState(ctx).status, "playing", "the party is not trapped by one dead source");
  const notices = batches.flatMap((b) => b.events).filter((e) => e.t === "notice" && e.notice === "hold_released");
  assert.ok(notices.length >= 1, "the room is told the hold released without the stuck viewer");
});

test("a player in error state never blocks the room (transition + hold)", () => {
  const ctx = makeParty({ members: 3 });
  const r = ctx.rm.content(ctx.rid, ctx.ids.host, { type: "movie", tmdbId: 7, season: 1, episode: 1 });
  const sv = r.events.find((e) => e.t === "content").sessionVersion;
  ctx.rm.pstate(ctx.rid, ctx.ids.g1, { st: "error", sv }); // broken source
  ctx.rm.pstate(ctx.rid, ctx.ids.g2, { st: "ready", sv });
  advance(ctx, LIMITS.READY_TIMEOUT_DEFAULT_MS + 1000);
  assert.strictEqual(ctx.rm.rooms.get(ctx.rid).gate.phase, "live", "a dead player cannot trap the transition");
});

/* ---------- 8-10. episode transitions ---------- */

test("host changes episode while everyone is playing: coordinated gate + new session for all", () => {
  const ctx = makeParty({ members: 3 });
  const sv = pbState(ctx).sessionVersion;
  ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "play", sessionVersion: sv });
  advance(ctx, 120_000);
  const r = ctx.rm.content(ctx.rid, ctx.ids.host, { type: "tv", tmdbId: 42, season: 1, episode: 2 });
  const contentEv = of(r.events, "content")[0];
  const schedEv = of(r.events, "schedule")[0];
  assert.strictEqual(contentEv.sessionVersion, sv + 1);
  assert.strictEqual(contentEv.state.position, 0);
  assert.ok(contentEv.state.updatedAt > ctx.clock.t, "shared gate is in the future (scheduled start)");
  assert.ok(schedEv && schedEv.phase === "loading", "clients are told the transition is loading");
  // every participant reports loading then ready -> readiness schedules the start
  for (const id of Object.values(ctx.ids)) ctx.rm.pstate(ctx.rid, id, { st: "loading", sv: sv + 1 });
  for (const id of Object.values(ctx.ids)) ctx.rm.pstate(ctx.rid, id, { st: "ready", sv: sv + 1 });
  const batches = advance(ctx, LIMITS.TRANSITION_MIN_MS + LIMITS.START_COUNTDOWN_MS + 2000);
  const room = ctx.rm.rooms.get(ctx.rid);
  assert.strictEqual(room.gate.phase, "live");
  assert.ok(room.playback.updatedAt <= ctx.clock.t, "the shared start fired");
  const finalContent = batches.flatMap((b) => b.events).find((e) => e.t === "content");
  assert.ok(finalContent, "the finalized gate is re-broadcast as a content event");
  assert.ok(finalContent.state.updatedAt >= T0 + 120_000, "finalized gate carried to every client");
  assert.strictEqual(finalContent.sessionVersion, sv + 1, "same session - no reload, just a re-armed start");
});

test("host changes episode while a guest is buffering: the transition still completes", () => {
  const ctx = makeParty({ members: 3 });
  ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "play", sessionVersion: 1 });
  advance(ctx, 60_000);
  ctx.rm.pstate(ctx.rid, ctx.ids.g1, { st: "buffering", sv: 1 });
  const r = ctx.rm.content(ctx.rid, ctx.ids.host, { type: "tv", tmdbId: 42, season: 1, episode: 2 });
  const sv = of(r.events, "content")[0].sessionVersion;
  // g1 is stuck on the OLD episode and never reports for the new one
  ctx.rm.pstate(ctx.rid, ctx.ids.g2, { st: "ready", sv });
  advance(ctx, LIMITS.READY_TIMEOUT_DEFAULT_MS + 2000);
  const room = ctx.rm.rooms.get(ctx.rid);
  assert.strictEqual(room.playback.sessionVersion, sv);
  assert.strictEqual(room.gate.phase, "live", "stuck guest did not block the change");
  // g1 finally recovers: its client must snap to the NEW episode's timeline
  const snap = ctx.rm.snapshot(room, ctx.ids.g1);
  assert.strictEqual(snap.content.episode, 2);
  assert.strictEqual(snap.playback.sessionVersion, sv);
});

test("rapid episode switching: only the latest session is actionable, stale events bounce", () => {
  const ctx = makeParty({ members: 2, allowControl: true });
  ctx.rm.content(ctx.rid, ctx.ids.host, { type: "tv", tmdbId: 42, season: 1, episode: 2 });
  const r2 = ctx.rm.content(ctx.rid, ctx.ids.host, { type: "tv", tmdbId: 42, season: 1, episode: 3 });
  const r3 = ctx.rm.content(ctx.rid, ctx.ids.host, { type: "tv", tmdbId: 42, season: 1, episode: 4 });
  const sv3 = of(r3.events, "content")[0].sessionVersion;
  assert.strictEqual(sv3, 4, "each change bumps the session exactly once");
  // an event from the episode-2 session arrives late
  const stale = ctx.rm.playback(ctx.rid, ctx.ids.g1, { action: "play", sessionVersion: 2 });
  assert.strictEqual(stale.stale, true, "stale session playback is rejected");
  const staleEnd = ctx.rm.playback(ctx.rid, ctx.ids.g1, { action: "ended", sessionVersion: 3 });
  assert.strictEqual(staleEnd.stale, true, "a stale completion can never advance the room");
  // current session works and the gate is consistent (exactly one schedule)
  ctx.rm.pstate(ctx.rid, ctx.ids.host, { st: "ready", sv: sv3 });
  ctx.rm.pstate(ctx.rid, ctx.ids.g1, { st: "ready", sv: sv3 });
  advance(ctx, LIMITS.TRANSITION_MIN_MS + LIMITS.START_COUNTDOWN_MS + 2000);
  const room = ctx.rm.rooms.get(ctx.rid);
  assert.strictEqual(room.playback.sessionVersion, sv3);
  assert.strictEqual(room.gate.phase, "live");
  const ok = ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "play", sessionVersion: sv3 });
  assert.ok(!ok.stale && !ok.error, "current-session commands still work after the storm");
});

/* ---------- 11-12. ordering, idempotency, rapid input ---------- */

test("two seek commands arrive out of order: the stale one is rejected, seq stays monotonic", () => {
  const ctx = makeParty({ members: 2, allowControl: true });
  const sv = pbState(ctx).sessionVersion;
  ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "play", sessionVersion: sv });
  // two rapid seeks from different participants (double-click race)
  const a = ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "seek", position: 300, sessionVersion: sv });
  const b = ctx.rm.playback(ctx.rid, ctx.ids.g1, { action: "seek", position: 120, sessionVersion: sv });
  const seqA = of(a.events, "playback")[0].seq;
  const seqB = of(b.events, "playback")[0].seq;
  assert.ok(seqB > seqA, "seq is monotonic per event");
  assert.strictEqual(pbState(ctx).position, 120, "last writer wins deterministically");
  // a late duplicate from BEFORE an episode change must not rewind anything
  ctx.rm.content(ctx.rid, ctx.ids.host, { type: "tv", tmdbId: 42, season: 1, episode: 2 });
  const late = ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "seek", position: 300, sessionVersion: sv });
  assert.strictEqual(late.stale, true);
  assert.strictEqual(pbState(ctx).position, 0, "the new episode was not rewound");
});

test("rapid play-pause-play applies in order and duplicates are idempotent", () => {
  const ctx = makeParty({ members: 2 });
  const sv = pbState(ctx).sessionVersion;
  ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "play", sessionVersion: sv });
  ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "pause", position: 55, sessionVersion: sv });
  ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "play", sessionVersion: sv });
  assert.strictEqual(pbState(ctx).status, "playing");
  // duplicate events (replayed frames) must not double-advance or re-fire
  const seqBefore = pbState(ctx).seq;
  const dup = ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "play", sessionVersion: sv });
  assert.ok(of(dup.events, "playback").length === 1, "one event per command");
  const dupEnd = ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "ended", sessionVersion: sv });
  assert.ok(of(dupEnd.events, "playback").length === 1);
  const dupEnd2 = ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "ended", sessionVersion: sv });
  assert.strictEqual(of(dupEnd2.events, "playback").length, 0, "a duplicated completion is swallowed");
  assert.ok(pbState(ctx).seq > seqBefore, "real commands still move the seq");
});

/* ---------- 13-15. reconnection / background ---------- */

test("participant disconnects and reconnects: same seat, timeline intact", () => {
  const ctx = makeParty({ members: 2 });
  const sv = pbState(ctx).sessionVersion;
  ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "play", sessionVersion: sv });
  advance(ctx, 300_000, 10_000);
  // Wi-Fi -> mobile data: socket drops, TCP never closes cleanly
  ctx.rm.disconnect(ctx.rid, ctx.ids.g1, ctx.people.g1.conn);
  advance(ctx, 20_000);
  // the same participant comes back on a NEW socket with the resume token
  const p2 = person("g1-reborn");
  const r = ctx.rm.join(p2, { code: ctx.code, token: "tok-g1" });
  assert.strictEqual(r.reattached, ctx.ids.g1, "same seat - never a ghost duplicate");
  assert.strictEqual(r.room.playback.status, "playing");
  assert.ok(Math.abs(r.room.playback.position - 320) < 5, "reconnector lands on the CURRENT timeline (got " + r.room.playback.position + ")");
});

test("participant returns from background: silence expires the seat, the token restores it", () => {
  const ctx = makeParty({ members: 2, ttl: { staleMs: 100_000, participantTtl: 45_000, seatMemory: 300_000 } });
  const sv = pbState(ctx).sessionVersion;
  ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "play", sessionVersion: sv });
  // iPhone Safari backgrounded: throttled heartbeats, no traffic at all from
  // g1 - the host (foreground) keeps its session alive
  for (let i = 0; i < 16; i++) {
    ctx.clock.t += 10_000;
    ctx.rm.markSeen(ctx.rid, ctx.ids.host, ctx.people.host.conn);
    ctx.rm.sweep();
  }
  const room = ctx.rm.rooms.get(ctx.rid);
  const gone = !room.participants.has(ctx.ids.g1) || !room.participants.get(ctx.ids.g1).connected;
  assert.ok(gone, "silence counts as gone (seat expired, identity remembered)");
  // screen wakes: the client rejoins on its token
  const p2 = person("woke");
  const r = ctx.rm.join(p2, { code: ctx.code, token: "tok-g1" });
  assert.strictEqual(r.reattached, ctx.ids.g1);
  assert.ok(r.room.playback.position > 60, "woke client recovers to the live timeline");
});

/* ---------- 16. authorization ---------- */

test("a guest cannot change the episode (host-only) even mid-transition", () => {
  const ctx = makeParty({ members: 2 });
  const r = ctx.rm.content(ctx.rid, ctx.ids.host, { type: "tv", tmdbId: 42, season: 1, episode: 2 });
  const sv = of(r.events, "content")[0].sessionVersion;
  const evil = ctx.rm.content(ctx.rid, ctx.ids.g1, { type: "tv", tmdbId: 42, season: 1, episode: 9 });
  assert.strictEqual(evil.error, "unauthorized");
  assert.strictEqual(pbState(ctx).sessionVersion, sv, "room session untouched");
  // and playback is gated too unless the host opened control
  const p = ctx.rm.playback(ctx.rid, ctx.ids.g1, { action: "pause", sessionVersion: sv });
  assert.strictEqual(p.error, "unauthorized");
});

/* ---------- 17-18. source capabilities ---------- */

test("per-participant source + duration reports surface for cut/duration mismatch detection", () => {
  const ctx = makeParty({ members: 3 });
  const sv = pbState(ctx).sessionVersion;
  ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "play", sessionVersion: sv });
  ctx.rm.syncReport(ctx.rid, ctx.ids.g1, { t: "sync_report", position: 100, paused: false, dur: 1400, source: "VidBolt" });
  ctx.rm.syncReport(ctx.rid, ctx.ids.g2, { t: "sync_report", position: 100, paused: false, dur: 1620, source: "CineSrc" });
  const room = ctx.rm.rooms.get(ctx.rid);
  const g1 = room.participants.get(ctx.ids.g1);
  const g2 = room.participants.get(ctx.ids.g2);
  assert.strictEqual(g1.source, "VidBolt");
  assert.strictEqual(g2.dur, 1620, "different cuts/durations are VISIBLE, never blindly aligned");
  // a report stamped for a previous session never marks anyone ready
  const r = ctx.rm.syncReport(ctx.rid, ctx.ids.g1, { t: "sync_report", position: 0, paused: true, st: "ready", sv: 99 });
  assert.ok(!r.events || r.events.length === 0 || !ctx.rm.rooms.get(ctx.rid).gate, "stale-sv readiness is ignored");
});

test("planCorrection never commands an unsupported player (coordinated-start mirrors)", () => {
  const d = planCorrection({ drift: 30, canSeek: false, canRate: false, ready: true });
  assert.strictEqual(d.action, "none");
  assert.strictEqual(d.reason, "unsupported");
});

/* ---------- 19-20. auto-next + stale completions ---------- */

test("auto-next during a party: ended advances the room exactly once, then the host's content change lands", () => {
  const ctx = makeParty({ members: 2 });
  const sv = pbState(ctx).sessionVersion;
  ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "play", sessionVersion: sv });
  advance(ctx, 45 * 60_000, 30_000);
  const e1 = ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "ended", sessionVersion: sv });
  assert.strictEqual(of(e1.events, "playback")[0].state.status, "ended");
  const seqAfter = pbState(ctx).seq;
  const e2 = ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "ended", sessionVersion: sv });
  assert.strictEqual(of(e2.events, "playback").length, 0, "duplicate completion is idempotent");
  assert.strictEqual(pbState(ctx).seq, seqAfter, "no second advance");
  // host auto-nexts to the next episode
  const r = ctx.rm.content(ctx.rid, ctx.ids.host, { type: "tv", tmdbId: 42, season: 1, episode: 3 });
  assert.strictEqual(of(r.events, "content")[0].sessionVersion, sv + 1);
});

test("a stale previous-episode completion can never advance the room again", () => {
  const ctx = makeParty({ members: 2 });
  ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "play", sessionVersion: 1 });
  advance(ctx, 600_000, 30_000);
  const r = ctx.rm.content(ctx.rid, ctx.ids.host, { type: "tv", tmdbId: 42, season: 1, episode: 2 });
  const sv2 = of(r.events, "content")[0].sessionVersion;
  // the OLD episode's "ended" arrives after the switch (slow socket)
  const staleEnded = ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "ended", sessionVersion: 1 });
  assert.strictEqual(staleEnded.stale, true);
  assert.strictEqual(pbState(ctx).sessionVersion, sv2, "new session intact");
  // and even without a session stamp, an ended during a pending transition is
  // refused (a completion can never race the episode switch)
  const racing = ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "ended" });
  assert.strictEqual(racing.stale, true);
});

/* ---------- 22-23. scheduled starts ---------- */

test("scheduled start fires even with zero traffic (tick-driven, timer-independent)", () => {
  const ctx = makeParty({ members: 2 });
  ctx.rm.content(ctx.rid, ctx.ids.host, { type: "tv", tmdbId: 42, season: 1, episode: 2 });
  // nobody reports anything (all-legacy clients): classic deadline behavior
  const batches = advance(ctx, LIMITS.READY_TIMEOUT_DEFAULT_MS + 2000);
  const room = ctx.rm.rooms.get(ctx.rid);
  assert.strictEqual(room.gate.phase, "live");
  const sched = batches.flatMap((b) => b.events).find((e) => e.t === "schedule");
  assert.ok(sched, "a schedule event was emitted without any client traffic");
  assert.ok(sched.startAt != null);
});

test("client joins during a scheduled start: snapshot holds at 00:00 until the shared gate", () => {
  const ctx = makeParty({ members: 2 });
  ctx.rm.content(ctx.rid, ctx.ids.host, { type: "tv", tmdbId: 42, season: 1, episode: 2 });
  advance(ctx, 1000);
  const late = person("early-bird");
  const r = ctx.rm.join(late, { code: ctx.code });
  assert.strictEqual(r.room.playback.position, 0, "gate clamps the position at 0");
  assert.ok(r.room.playback.updatedAt > r.room.serverTs, "the gate is still ahead on the server clock");
  assert.ok(r.room.gate && r.room.gate.phase !== "live", "joiner can see a transition is pending");
});

test("host override: pressing play during a pending transition starts the room now", () => {
  const ctx = makeParty({ members: 2 });
  ctx.rm.content(ctx.rid, ctx.ids.host, { type: "tv", tmdbId: 42, season: 1, episode: 2 });
  const r = ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "play" });
  assert.ok(!r.stale && !r.error, "override accepted");
  const room = ctx.rm.rooms.get(ctx.rid);
  assert.strictEqual(room.playback.status, "playing");
  advance(ctx, 3000);
  assert.strictEqual(room.gate.phase, "live");
});

/* ---------- 24. host leaves ---------- */

test("host disconnects during a transition: the room survives and transfers", () => {
  const ctx = makeParty({ members: 3, ttl: { hostGrace: 60_000 } });
  ctx.rm.content(ctx.rid, ctx.ids.host, { type: "tv", tmdbId: 42, season: 1, episode: 2 });
  ctx.rm.disconnect(ctx.rid, ctx.ids.host, ctx.people.host.conn);
  // guests stay alive (foreground) while the host is gone past the grace
  for (let i = 0; i < 13; i++) {
    ctx.clock.t += 5000;
    ctx.rm.markSeen(ctx.rid, ctx.ids.g1, ctx.people.g1.conn);
    ctx.rm.markSeen(ctx.rid, ctx.ids.g2, ctx.people.g2.conn);
    ctx.rm.sweep();
  }
  const room = ctx.rm.rooms.get(ctx.rid);
  assert.notStrictEqual(room.hostId, ctx.ids.host, "host seat transferred");
  assert.strictEqual(room.ended, false, "the party is still alive");
  // the new host can drive the room
  const r = ctx.rm.playback(ctx.rid, room.hostId, { action: "play" });
  assert.ok(!r.error, "new host has control");
});

/* ---------- 26-27. simultaneous control + duplicates ---------- */

test("two participants control playback simultaneously: serialized, ordered, consistent", () => {
  const ctx = makeParty({ members: 3, allowControl: true });
  const sv = pbState(ctx).sessionVersion;
  ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "play", sessionVersion: sv });
  const seqs = [];
  for (let i = 0; i < 6; i++) {
    const r = ctx.rm.playback(ctx.rid, i % 2 ? ctx.ids.g1 : ctx.ids.host, {
      action: i % 2 ? "seek" : "pause",
      position: i * 10,
      sessionVersion: sv,
    });
    for (const e of of(r.events, "playback")) seqs.push(e.seq);
  }
  for (let i = 1; i < seqs.length; i++) assert.ok(seqs[i] > seqs[i - 1], "monotonic seq under contention");
  const room = ctx.rm.rooms.get(ctx.rid);
  assert.strictEqual(room.playback.seq, seqs[seqs.length - 1], "canonical seq matches the last event");
});

test("duplicate socket events (replayed/duplicated frames) never double-advance the room", () => {
  const ctx = makeParty({ members: 2 });
  const sv = pbState(ctx).sessionVersion;
  ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "play", position: 100, sessionVersion: sv });
  ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "play", position: 100, sessionVersion: sv });
  assert.strictEqual(pbState(ctx).position, 100, "duplicate play with position is idempotent");
  ctx.rm.pstate(ctx.rid, ctx.ids.g1, { st: "buffering", sv });
  ctx.rm.pstate(ctx.rid, ctx.ids.g1, { st: "buffering", sv });
  assert.strictEqual(ctx.rm.rooms.get(ctx.rid).participants.get(ctx.ids.g1).pstate, "buffering");
});

/* ---------- 28. leaving with pending synchronization work ---------- */

test("a participant leaving mid-hold / mid-transition cannot strand the room", () => {
  const ctx = makeParty({ members: 3 });
  ctx.rm.settings(ctx.rid, ctx.ids.host, { waitMode: "everyone" });
  const sv = pbState(ctx).sessionVersion;
  ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "play", sessionVersion: sv });
  advance(ctx, 10_000);
  ctx.rm.pstate(ctx.rid, ctx.ids.g1, { st: "buffering", sv });
  assert.strictEqual(pbState(ctx).status, "paused");
  // the stalled participant gives up and LEAVES (or is kicked) while the hold
  // is pending - the room must resume
  ctx.rm.leave(ctx.rid, ctx.ids.g1);
  const batches = advance(ctx, 3000);
  assert.strictEqual(pbState(ctx).status, "playing", "a leaver never freezes the party");
  // and a leaver during a transition does not hold back the start
  const r = ctx.rm.content(ctx.rid, ctx.ids.host, { type: "tv", tmdbId: 42, season: 1, episode: 2 });
  const sv2 = of(r.events, "content")[0].sessionVersion;
  ctx.rm.pstate(ctx.rid, ctx.ids.g2, { st: "loading", sv: sv2 });
  ctx.rm.leave(ctx.rid, ctx.ids.g2);
  advance(ctx, LIMITS.READY_TIMEOUT_DEFAULT_MS + 1000);
  assert.strictEqual(ctx.rm.rooms.get(ctx.rid).gate.phase, "live");
});

/* ---------- shared correction policy (client copy) ---------- */

test("planCorrection: tiny drift is ignored (no fighting the player)", () => {
  const d = planCorrection({ drift: 0.1, canSeek: true, canRate: true, ready: true, nowMs: 10000 });
  assert.strictEqual(d.action, "none");
  assert.strictEqual(d.reason, "in_band");
});

test("planCorrection: moderate drift nudges rate gently and schedules a restore", () => {
  const d = planCorrection({ drift: 0.8, canSeek: true, canRate: true, ready: true, nowMs: 10000, lastCorrectMs: 0 });
  assert.strictEqual(d.action, "nudge");
  assert.strictEqual(d.rate, 0.9, "ahead of the room -> slow down slightly");
  assert.ok(d.restoreMs > 0 && d.restoreMs <= 8000, "the rate restore is bounded");
  const back = planCorrection({ drift: -0.8, canSeek: true, canRate: true, ready: true, nowMs: 10000, lastCorrectMs: 0 });
  assert.strictEqual(back.rate, 1.1, "behind the room -> speed up slightly");
});

test("planCorrection: large drift performs one controlled seek", () => {
  const d = planCorrection({ drift: 4.2, canSeek: true, canRate: true, ready: true, nowMs: 10000, lastCorrectMs: 0, pos: 500, dur: 3600 });
  assert.strictEqual(d.action, "seek");
});

test("planCorrection: buffering/seeking/gate/unready players are never fought", () => {
  assert.strictEqual(planCorrection({ drift: 60, canSeek: true, buffering: true }).reason, "buffering");
  assert.strictEqual(planCorrection({ drift: 60, canSeek: true, seeking: true }).reason, "seeking");
  assert.strictEqual(planCorrection({ drift: 60, canSeek: true, ready: false }).reason, "not_ready");
  assert.strictEqual(planCorrection({ drift: 60, canSeek: true, ready: true, gateAt: 20000, serverNowMs: 10000 }).reason, "gate");
});

test("planCorrection: cooldown and end-zone guards stop correction loops and credits jumps", () => {
  const c = planCorrection({ drift: 5, canSeek: true, ready: true, nowMs: 11000, lastCorrectMs: 10000, pos: 100, dur: 3600 });
  assert.strictEqual(c.reason, "cooldown");
  const e = planCorrection({ drift: 5, canSeek: true, ready: true, nowMs: 30000, lastCorrectMs: 0, pos: 3595, dur: 3600 });
  assert.strictEqual(e.reason, "end_zone");
});

test("planCorrection: seek-only bridges correct gently and rarely", () => {
  const small = planCorrection({ drift: 0.5, canSeek: true, canRate: false, ready: true, nowMs: 10000, lastCorrectMs: 0 });
  assert.strictEqual(small.action, "none");
  const big = planCorrection({ drift: 2.5, canSeek: true, canRate: false, ready: true, nowMs: 10000, lastCorrectMs: 0, pos: 100, dur: 3600 });
  assert.strictEqual(big.action, "seek");
});

test("planCorrection: different cuts are never blindly aligned below the bridge floor", () => {
  const d = planCorrection({ drift: 3, canSeek: true, canRate: true, ready: true, nowMs: 10000, lastCorrectMs: 0, cutMismatch: true });
  assert.strictEqual(d.reason, "cut_mismatch");
});

/* ---------- measured drift distribution (simulated clients) ---------- */

test("simulated 4-client room: drift distribution stays sub-second for controllable players", () => {
  // Four virtual clients follow the room through the SHARED math: each keeps a
  // local media clock, applies room events with 20-250ms latency (like real
  // WebSocket delivery), and runs planCorrection every 2s like the real
  // driftTick. Rate nudges close drift at the modeled 10%; hard seeks snap.
  const ctx = makeParty({ members: 2 });
  const sv = pbState(ctx).sessionVersion;
  ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "play", sessionVersion: sv });

  const rnd = (() => { let s = 42; return () => (s = (s * 1103515245 + 12345) % 2147483648) / 2147483648; })();
  const clients = [];
  for (let i = 0; i < 4; i++) {
    clients.push({
      media: 0,             // local playback position
      rate: 1,
      playing: true,
      latency: 20 + rnd() * 230,
      lastCorrect: -99999,
      lastSeq: 0,
      drifts: [],
    });
  }

  const SAMPLE_MS = 1000;
  const TOTAL_MS = 5 * 60_000;
  for (let elapsed = 0; elapsed < TOTAL_MS; elapsed += SAMPLE_MS) {
    ctx.clock.t += SAMPLE_MS;
    // mid-play churn: the host seeks twice, pauses once
    if (elapsed === 60_000) ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "seek", position: 900, sessionVersion: sv });
    if (elapsed === 150_000) ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "pause", sessionVersion: sv });
    if (elapsed === 160_000) ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "play", sessionVersion: sv });
    if (elapsed === 240_000) ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "seek", position: 1200, sessionVersion: sv });

    const room = ctx.rm.rooms.get(ctx.rid);
    for (const c of clients) {
      // room events arrive with latency and are APPLIED immediately (that is
      // what applyPlayback does - playback actions never wait for driftTick)
      const seenAt = ctx.clock.t - c.latency;
      if (room.playback.seq !== c.lastSeq) {
        c.lastSeq = room.playback.seq;
        c.media = expectedPosition(room.playback, seenAt) + (rnd() - 0.5) * 0.06;
        c.playing = room.playback.status === "playing";
        c.rate = 1;
        // the apply itself is the correction for this step
      } else {
        if (c.playing) c.media += (SAMPLE_MS / 1000) * c.rate;
        // drift check every 2s (like driftTick)
        if (elapsed % 2000 === 0) {
          const target = expectedPosition(room.playback, ctx.clock.t);
          const d = planCorrection({
            drift: c.media - target,
            canSeek: true,
            canRate: true,
            ready: true,
            nowMs: elapsed,
            lastCorrectMs: c.lastCorrect,
            pos: c.media,
            dur: 5400,
          });
          if (d.action === "seek") { c.media = target + 0.25; c.rate = 1; c.lastCorrect = elapsed; }
          else if (d.action === "nudge") { c.rate = d.rate; c.lastCorrect = elapsed; }
        }
        // rate restore (the real client uses a timer, bounded at 8s)
        if (c.rate !== 1 && elapsed - c.lastCorrect >= 8000) c.rate = 1;
        c.drifts.push(Math.abs(c.media - expectedPosition(room.playback, ctx.clock.t)));
      }
    }
  }

  const all = clients.flatMap((c) => c.drifts).sort((a, b) => a - b);
  const pct = (p) => all[Math.min(all.length - 1, Math.floor((p / 100) * all.length))];
  const median = pct(50), p95 = pct(95), worst = all[all.length - 1];
  console.log("  [drift] n=" + all.length +
    " median=" + median.toFixed(3) + "s p95=" + p95.toFixed(3) + "s worst=" + worst.toFixed(3) + "s");
  assert.ok(median < 0.5, "median drift under 0.5s (got " + median.toFixed(3) + ")");
  assert.ok(p95 < 1.5, "p95 drift under 1.5s (got " + p95.toFixed(3) + ")");
  assert.ok(worst < 5, "worst-case drift bounded (got " + worst.toFixed(3) + ")");
});
